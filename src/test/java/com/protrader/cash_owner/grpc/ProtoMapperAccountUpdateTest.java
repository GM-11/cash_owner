package com.protrader.cash_owner.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.HoldingView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.proto.exchange.v1.AccountSnapshot;
import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.OrderEvent;

/** Step 5, piece 6: AccountChange → AccountUpdate proto. */
class ProtoMapperAccountUpdateTest {

    private static final int MOOG = 1;

    private static OrderView order() {
        return new OrderView(new OrderKey(21, "b1"), MOOG, Side.BUY, 10_000, 10, 4, 6, 58_800, 2, OrderStage.OPEN,
                null);
    }

    private static AccountView balances() {
        return new AccountView(21, 901_200, 40_000, Map.of(MOOG, new HoldingView(6, 0)), 9);
    }

    private static AccountChange change(AccountChangeKind kind, HoldingView position, long price, long qty) {
        return new AccountChange(21, 9, kind, order(), price, qty, 33, 5_555, new BalancesView(901_200, 40_000), position);
    }

    @Test
    void eachKindMapsToTheMatchingProtoKind() {
        assertEquals(OrderEvent.Kind.KIND_NEW, ProtoMapper.toProto(AccountChangeKind.NEW));
        assertEquals(OrderEvent.Kind.KIND_PARTIAL_FILL, ProtoMapper.toProto(AccountChangeKind.PARTIAL_FILL));
        assertEquals(OrderEvent.Kind.KIND_FILL, ProtoMapper.toProto(AccountChangeKind.FILL));
        assertEquals(OrderEvent.Kind.KIND_CANCELED, ProtoMapper.toProto(AccountChangeKind.CANCELED));
        assertEquals(OrderEvent.Kind.KIND_EXPIRED, ProtoMapper.toProto(AccountChangeKind.EXPIRED));
        assertEquals(OrderEvent.Kind.KIND_REJECTED, ProtoMapper.toProto(AccountChangeKind.REJECTED));
        for (AccountChangeKind kind : AccountChangeKind.values()) {
            AccountUpdate u = ProtoMapper.toAccountUpdate(change(kind, null, 0, 0));
            assertEquals(ProtoMapper.toProto(kind), u.getOrderEvent().getKind(), kind.toString());
        }
    }

    @Test
    void fillFieldsAreSetForFillsOnly() {
        for (AccountChangeKind kind : AccountChangeKind.values()) {
            boolean fill = kind == AccountChangeKind.PARTIAL_FILL || kind == AccountChangeKind.FILL;
            // even if a caller left stray fill numbers on a non-fill, they must not reach the wire
            OrderEvent ev = ProtoMapper.toAccountUpdate(change(kind, null, 9_800, 6)).getOrderEvent();
            assertEquals(fill ? 9_800 : 0, ev.getFillPriceMinor(), kind.toString());
            assertEquals(fill ? 6 : 0, ev.getFillQuantity(), kind.toString());
        }
    }

    @Test
    void idsSeqsAndBalancesAreCopiedExactly() {
        AccountUpdate u = ProtoMapper.toAccountUpdate(change(AccountChangeKind.PARTIAL_FILL,
                new HoldingView(6, 0), 9_800, 6));

        assertEquals(21, u.getAccountId());
        assertEquals(9, u.getAccountSeq());
        assertEquals(33, u.getOrderEvent().getEngineSeq());
        assertEquals(5_555, u.getTs());
        assertEquals(901_200, u.getBalances().getCashFreeMinor());
        assertEquals(40_000, u.getBalances().getCashLockedMinor());
        assertEquals("b1", u.getOrderEvent().getOrder().getClientOrderId());
        assertEquals(6, u.getOrderEvent().getOrder().getFilledQuantity());
        assertEquals(2, u.getOrderEvent().getOrder().getOrderId());
        assertTrue(u.hasOrderEvent());
    }

    @Test
    void positionsHoldTheOnePositionOrNothing() {
        AccountUpdate with = ProtoMapper.toAccountUpdate(change(AccountChangeKind.FILL, new HoldingView(6, 2),
                9_800, 6));
        assertEquals(1, with.getPositionsCount());
        assertEquals(MOOG, with.getPositions(0).getSymbol());
        assertEquals(6, with.getPositions(0).getFreeQty());
        assertEquals(2, with.getPositions(0).getLockedQty());

        AccountUpdate without = ProtoMapper.toAccountUpdate(change(AccountChangeKind.NEW, null, 0, 0));
        assertEquals(0, without.getPositionsCount());
    }

    @Test
    void snapshotCarriesAccountSeqFromTheView() {
        AccountSnapshot s = ProtoMapper.toSnapshot(21, Optional.of(balances()), List.of(order()));

        assertEquals(9, s.getAccountSeq());
        assertEquals(901_200, s.getBalances().getCashFreeMinor());
        assertEquals(1, s.getOpenOrdersCount());
        assertEquals(0, ProtoMapper.toSnapshot(21, Optional.empty(), List.of()).getAccountSeq());
    }
}
