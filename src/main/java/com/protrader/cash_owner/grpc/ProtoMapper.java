package com.protrader.cash_owner.grpc;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.RejectReason;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.HoldingView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.proto.exchange.v1.AccountSnapshot;
import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.Balances;
import com.protrader.proto.exchange.v1.CashRejectReason;
import com.protrader.proto.exchange.v1.OrderEvent;
import com.protrader.proto.exchange.v1.OrderState;
import com.protrader.proto.exchange.v1.OrderStatus;
import com.protrader.proto.exchange.v1.OrderType;
import com.protrader.proto.exchange.v1.Position;
import com.protrader.proto.exchange.v1.TimeInForce;

/** Ledger models <-> proto messages. Static, no Spring. */
public final class ProtoMapper {

    private ProtoMapper() {
    }

    public static OrderState toOrderState(OrderView v) {
        return OrderState.newBuilder()
                .setAccountId(v.key().accountId())
                .setClientOrderId(v.key().clientOrderId())
                .setSymbol(v.symbol())
                .setOrderId(v.orderId())
                .setSide(toProto(v.side()))
                .setType(OrderType.ORDER_TYPE_LIMIT)
                .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                .setPriceMinor(v.limitPriceMinor())
                .setQuantity(v.originalQty())
                .setFilledQuantity(v.filledQty())
                .setFilledNotionalMinor(v.filledNotionalMinor())
                .setStatus(statusOf(v))
                .setCreatedTs(0)
                .setUpdatedTs(0)
                .build();
    }

    private static OrderStatus statusOf(OrderView v) {
        return switch (v.stage()) {
            case SENT, IN_DOUBT -> OrderStatus.ORDER_STATUS_PENDING_NEW;
            case OPEN -> v.cancelPending() ? OrderStatus.ORDER_STATUS_PENDING_CANCEL
                    : v.filledQty() == 0 ? OrderStatus.ORDER_STATUS_NEW : OrderStatus.ORDER_STATUS_PARTIALLY_FILLED;
            case DONE -> v.doneReason() == null ? OrderStatus.ORDER_STATUS_UNSPECIFIED : switch (v.doneReason()) {
                case FILLED -> OrderStatus.ORDER_STATUS_FILLED;
                case CANCELED -> OrderStatus.ORDER_STATUS_CANCELED;
                case EXPIRED -> OrderStatus.ORDER_STATUS_EXPIRED;
                case REJECTED -> OrderStatus.ORDER_STATUS_REJECTED;
            };
        };
    }

    public static AccountSnapshot toSnapshot(long accountId, Optional<AccountView> account,
            List<OrderView> openOrders) {
        if (account.isEmpty()) {
            return AccountSnapshot.newBuilder().setFound(false).setAccountId(accountId).build();
        }
        AccountView a = account.get();
        AccountSnapshot.Builder b = AccountSnapshot.newBuilder()
                .setFound(true)
                .setAccountId(a.accountId())
                .setAccountSeq(a.accountSeq())
                .setBalances(toBalances(a.freeCashMinor(), a.lockedCashMinor(), a.realizedPnlMinor()));
        new TreeMap<>(a.holdings()).forEach((symbol, h) -> b.addPositions(toPosition(symbol, h)));
        openOrders.forEach(o -> b.addOpenOrders(toOrderState(o)));
        return b.build();
    }

    private static Balances.Builder toBalances(long freeCashMinor, long lockedCashMinor, long realizedPnlMinor) {
        return Balances.newBuilder()
                .setCashFreeMinor(freeCashMinor)
                .setCashLockedMinor(lockedCashMinor)
                .setRealizedPnlMinor(realizedPnlMinor);
    }

    private static Position.Builder toPosition(int symbol, HoldingView h) {
        return Position.newBuilder()
                .setSymbol(symbol)
                .setFreeQty(h.freeQty())
                .setLockedQty(h.lockedQty())
                .setAvgPriceMinor(h.avgPriceMinor())
                .setRealizedPnlMinor(h.realizedPnlMinor());
    }

    public static AccountUpdate toAccountUpdate(AccountChange c) {
        OrderEvent.Builder event = OrderEvent.newBuilder()
                .setKind(toProto(c.kind()))
                .setOrder(toOrderState(c.order()))
                .setEngineSeq(c.engineSeq());
        if (c.kind() == AccountChangeKind.PARTIAL_FILL || c.kind() == AccountChangeKind.FILL) {
            event.setFillPriceMinor(c.fillPriceMinor()).setFillQuantity(c.fillQty());
        }
        AccountUpdate.Builder b = AccountUpdate.newBuilder()
                .setAccountId(c.accountId())
                .setAccountSeq(c.accountSeq())
                .setOrderEvent(event)
                .setTs(c.ts())
                .setBalances(toBalances(c.balances().freeCashMinor(), c.balances().lockedCashMinor(),
                        c.balances().realizedPnlMinor()));
        if (c.position() != null) {
            b.addPositions(toPosition(c.order().symbol(), c.position()));
        }
        return b.build();
    }

    public static OrderEvent.Kind toProto(AccountChangeKind kind) {
        return switch (kind) {
            case NEW -> OrderEvent.Kind.KIND_NEW;
            case PARTIAL_FILL -> OrderEvent.Kind.KIND_PARTIAL_FILL;
            case FILL -> OrderEvent.Kind.KIND_FILL;
            case CANCELED -> OrderEvent.Kind.KIND_CANCELED;
            case EXPIRED -> OrderEvent.Kind.KIND_EXPIRED;
            case REJECTED -> OrderEvent.Kind.KIND_REJECTED;
            case CANCEL_REJECTED -> OrderEvent.Kind.KIND_CANCEL_REJECTED;
        };
    }

    public static CashRejectReason toProto(RejectReason reason) {
        return switch (reason) {
            case INSUFFICIENT_CASH -> CashRejectReason.CASH_REJECT_REASON_INSUFFICIENT_CASH;
            case INSUFFICIENT_HOLDINGS -> CashRejectReason.CASH_REJECT_REASON_INSUFFICIENT_HOLDINGS;
            case UNKNOWN_ACCOUNT -> CashRejectReason.CASH_REJECT_REASON_UNKNOWN_ACCOUNT;
        };
    }

    public static com.protrader.cash_owner.models.Side fromProto(com.protrader.proto.exchange.v1.Side side) {
        return switch (side) {
            case SIDE_BUY -> com.protrader.cash_owner.models.Side.BUY;
            case SIDE_SELL -> com.protrader.cash_owner.models.Side.SELL;
            default -> throw new IllegalArgumentException("side must be BUY or SELL: " + side);
        };
    }

    public static com.protrader.proto.exchange.v1.Side toProto(com.protrader.cash_owner.models.Side side) {
        return switch (side) {
            case BUY -> com.protrader.proto.exchange.v1.Side.SIDE_BUY;
            case SELL -> com.protrader.proto.exchange.v1.Side.SIDE_SELL;
        };
    }
}
