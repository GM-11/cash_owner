package com.protrader.cash_owner.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.cash_owner.kafka.SettlementHandler;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.orders.CancelOrderFlow;
import com.protrader.cash_owner.orders.PlaceOrderFlow;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.sequencer.SequencerPort;
import com.protrader.cash_owner.services.AccountUpdatePublisher;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;

class ReplayNoSideEffectsTest {

    @TempDir
    Path dir;

    private static final class CountingPort implements SequencerPort {
        final AtomicInteger submits = new AtomicInteger();

        @Override
        public boolean submit(SubmitRequest request) {
            submits.incrementAndGet();
            return true;
        }

        @Override
        public void onAck(Consumer<SubmitAck> handler) {
        }

        @Override
        public void onStreamOpened(Runnable handler) {
        }

        @Override
        public void onStreamLost(Runnable handler) {
        }
    }

    @Test
    void givenAJournalWithOrders_whenReplayed_thenNeitherTheSequencerNorTheAccountUpdatePublisherIsCalled()
            throws Exception {
        CashOwnerProperties props = new CashOwnerProperties(null, null,
                new CashOwnerProperties.Owner(100, true, Duration.ofSeconds(2)), null,
                new CashOwnerProperties.Persistence(true, dir.toString(), 3));

        LedgerOwner first = new LedgerOwner(props);
        first.start();
        try {
            first.followUp(l -> {
                l.openAccount(1, 1_000_000, Map.of(1, 10L));
                l.place(1, "b1", 1, Side.BUY, 100, 5);
                l.place(1, "s1", 1, Side.SELL, 100, 5);
                l.onAccepted(new com.protrader.cash_owner.models.OrderKey(1, "s1"), 9);
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            first.stop();
        }

        CountingPort port = new CountingPort();
        AtomicInteger sent = new AtomicInteger();
        AccountUpdatePublisher publisher = new AccountUpdatePublisher(update -> sent.incrementAndGet());
        LedgerOwner second = new LedgerOwner(props);
        new PlaceOrderFlow(second, port);
        new CancelOrderFlow(second, port);
        new SettlementHandler(second, publisher, Optional.empty());
        second.start(); // recovery replays the journal, then marks b1 in doubt
        try {
            assertFalse(second.recoveredEmpty());
            assertEquals(1, second.request(l -> l.inDoubt().size()).get(5, TimeUnit.SECONDS));
            assertEquals(0, port.submits.get());
            assertEquals(0, publisher.queueDepth());
            assertEquals(0, publisher.sentCount());
            assertEquals(0, sent.get());
        } finally {
            second.stop();
        }
        assertTrue(port.submits.get() == 0);
    }
}
