package com.protrader.cash_owner.grpc;

import com.protrader.cash_owner.models.RejectReason;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.proto.exchange.v1.CashRejectReason;
import com.protrader.proto.exchange.v1.OrderState;
import com.protrader.proto.exchange.v1.OrderStatus;
import com.protrader.proto.exchange.v1.OrderType;
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
            // step 5 refines this once cancels and rejects are tracked
            case DONE -> v.filledQty() == v.originalQty() ? OrderStatus.ORDER_STATUS_FILLED
                    : OrderStatus.ORDER_STATUS_CANCELED;
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
