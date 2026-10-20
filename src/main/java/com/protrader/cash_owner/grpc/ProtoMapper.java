package com.protrader.cash_owner.grpc;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import com.protrader.cash_owner.models.RejectReason;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.proto.exchange.v1.AccountSnapshot;
import com.protrader.proto.exchange.v1.Balances;
import com.protrader.proto.exchange.v1.CashRejectReason;
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
            case OPEN -> v.filledQty() == 0 ? OrderStatus.ORDER_STATUS_NEW : OrderStatus.ORDER_STATUS_PARTIALLY_FILLED;
            case DONE -> v.doneReason() == null ? OrderStatus.ORDER_STATUS_UNSPECIFIED : switch (v.doneReason()) {
                case FILLED -> OrderStatus.ORDER_STATUS_FILLED;
                case CANCELED -> OrderStatus.ORDER_STATUS_CANCELED;
                case EXPIRED -> OrderStatus.ORDER_STATUS_EXPIRED;
                case REJECTED -> OrderStatus.ORDER_STATUS_REJECTED;
            };
        };
    }

    /**
     * GetAccount reply. account_seq is 0 until step 5 piece 6 (AccountUpdate) adds it; average price and
     * realized P&L are 0 until step 6.
     */
    public static AccountSnapshot toSnapshot(long accountId, Optional<AccountView> account, List<OrderView> openOrders) {
        if (account.isEmpty()) {
            return AccountSnapshot.newBuilder().setFound(false).setAccountId(accountId).build();
        }
        AccountView a = account.get();
        AccountSnapshot.Builder b = AccountSnapshot.newBuilder()
                .setFound(true)
                .setAccountId(a.accountId())
                .setAccountSeq(0)
                .setBalances(Balances.newBuilder()
                        .setCashFreeMinor(a.freeCashMinor())
                        .setCashLockedMinor(a.lockedCashMinor())
                        .setRealizedPnlMinor(0));
        new TreeMap<>(a.holdings()).forEach((symbol, h) -> b.addPositions(Position.newBuilder()
                .setSymbol(symbol)
                .setFreeQty(h.freeQty())
                .setLockedQty(h.lockedQty())));
        openOrders.forEach(o -> b.addOpenOrders(toOrderState(o)));
        return b.build();
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
