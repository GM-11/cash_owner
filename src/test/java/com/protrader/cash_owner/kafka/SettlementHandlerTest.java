package com.protrader.cash_owner.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.EventKind;
import com.protrader.proto.exchange.v1.MarketEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Step 5, piece 5: Kafka MarketEvent → owner thread → Ledger, waiting for each answer; any failure stops. */
class SettlementHandlerTest {

    private static final int MOOG = 1;

    private LedgerOwner owner;
    private SettlementHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        owner = LedgerOwner.forTests(new LedgerOwner.Settings(10_000, true, Duration.ofSeconds(5)),
                Ledger::checkInvariants);
        owner.start();
        handler = new SettlementHandler(owner, Optional.empty());
        // account 21 buys 10 MOOG @ ₹100 (₹1,000 locked), account 1 sells 10 @ ₹100 (10 shares locked)
        owner.followUp(l -> {
            l.openAccount(21, 10_000_000, Map.of());
            l.openAccount(1, 0, Map.of(MOOG, 10L));
            l.place(21, "b", MOOG, Side.BUY, 10_000, 10);
            l.place(1, "s", MOOG, Side.SELL, 10_000, 10);
            return null;
        }).get();
    }

    @AfterEach
    void tearDown() {
        owner.stop();
    }

    private static MarketEvent accepted(long seq, long orderId, long owner, String clientOrderId,
            com.protrader.proto.exchange.v1.Side side) {
        return MarketEvent.newBuilder().setSymbol(MOOG).setSeq(seq).setKind(EventKind.EVENT_KIND_ACCEPTED)
                .setOrderId(orderId).setOwnerId(owner).setClientOrderId(clientOrderId).setSide(side)
                .setPrice(10_000).setQuantity(10).build();
    }

    private static MarketEvent trade(long seq, long qty) {
        // incoming sell (id 4, account 1) hits resting buy (id 3, account 21)
        return MarketEvent.newBuilder().setSymbol(MOOG).setSeq(seq).setKind(EventKind.EVENT_KIND_TRADE)
                .setOrderId(4).setPassiveId(3).setOwnerId(21).setOtherOwner(1)
                .setSide(com.protrader.proto.exchange.v1.Side.SIDE_SELL).setPrice(10_000).setQuantity(qty).build();
    }

    private AccountView account(long id) throws Exception {
        return owner.followUp(l -> l.account(id).orElseThrow()).get();
    }

    @Test
    void eventsFromKafkaSettleBothAccounts() throws Exception {
        handler.onEngineEvent(accepted(1, 3, 21, "b", com.protrader.proto.exchange.v1.Side.SIDE_BUY));
        handler.onEngineEvent(accepted(2, 4, 1, "s", com.protrader.proto.exchange.v1.Side.SIDE_SELL));
        handler.onEngineEvent(trade(3, 10));

        assertEquals(0, account(21).lockedCashMinor());
        assertEquals(10_000_000 - 100_000, account(21).freeCashMinor());
        assertEquals(10, account(21).holdings().get(MOOG).freeQty());
        assertEquals(100_000, account(1).freeCashMinor());
        assertEquals(0, account(1).holdings().get(MOOG).lockedQty());
        assertEquals(3, handler.appliedCount());
        assertFalse(handler.isStopped());
    }

    @Test
    void duplicateFromKafkaIsCountedAndChangesNothing() throws Exception {
        handler.onEngineEvent(accepted(1, 3, 21, "b", com.protrader.proto.exchange.v1.Side.SIDE_BUY));
        handler.onEngineEvent(accepted(2, 4, 1, "s", com.protrader.proto.exchange.v1.Side.SIDE_SELL));
        handler.onEngineEvent(trade(3, 4));
        AccountView before = account(21);

        handler.onEngineEvent(trade(3, 4)); // the bridge resent it

        assertEquals(before, account(21));
        assertEquals(1, handler.duplicateCount());
        assertEquals(4, owner.followUp(l -> l.order(new OrderKey(21, "b")).orElseThrow().filledQty()).get());
    }

    @Test
    void aFailureStopsSettlementAndEveryLaterEventIsRefused() throws Exception {
        handler.onEngineEvent(accepted(1, 3, 21, "b", com.protrader.proto.exchange.v1.Side.SIDE_BUY));
        handler.onEngineEvent(accepted(2, 4, 1, "s", com.protrader.proto.exchange.v1.Side.SIDE_SELL));

        // seq 3 is a fill of 11 on orders of 10: our books and the engine disagree
        assertThrows(SettlementHandler.SettlementStoppedException.class, () -> handler.onEngineEvent(trade(3, 11)));
        assertTrue(handler.isStopped());

        // a perfectly good next event is refused too: nothing is applied after a failure
        assertThrows(IllegalStateException.class, () -> handler.onEngineEvent(trade(3, 10)));
        assertEquals(100_000, account(21).lockedCashMinor()); // nothing settled
        assertTrue(owner.isHealthy()); // the Ledger itself is fine: it refused the bad event before changing anything
    }

    @Test
    void anEventThatCannotBeReadStopsSettlement() {
        MarketEvent noKind = MarketEvent.newBuilder().setSymbol(MOOG).setSeq(1).build();

        assertThrows(SettlementHandler.SettlementStoppedException.class, () -> handler.onEngineEvent(noKind));
        assertTrue(handler.isStopped());
    }

    @Test
    void mapperCopiesEveryField() {
        MarketEvent m = trade(7, 5).toBuilder().setTs(1234).build();
        var e = EngineEventMapper.fromProto(m);
        assertEquals(MOOG, e.symbol());
        assertEquals(7, e.seq());
        assertEquals(com.protrader.cash_owner.models.EngineEventKind.TRADE, e.kind());
        assertEquals(1234, e.ts());
        assertEquals(4, e.orderId());
        assertEquals(3, e.passiveId());
        assertEquals(21, e.ownerId());
        assertEquals(1, e.otherOwnerId());
        assertEquals(Side.SELL, e.side());
        assertEquals(10_000L, e.priceMinor());
        assertEquals(5, e.qty());
        assertEquals("", e.clientOrderId());
        // no price on the wire → null, not 0
        assertEquals(null, EngineEventMapper.fromProto(m.toBuilder().clearPrice().build()).priceMinor());
    }
}
