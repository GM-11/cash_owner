package com.protrader.cash_owner.models;

/** Read-only copy of an {@link Order}. Safe to hand to any thread. */
public record OrderView(OrderKey key, int symbol, Side side, long limitPriceMinor, long originalQty,
        long remainingQty, long filledQty, long filledNotionalMinor, long orderId, OrderStage stage) {

    public static OrderView of(Order order) {
        return new OrderView(order.getKey(), order.getSymbol(), order.getSide(), order.getLimitPriceMinor(),
                order.getOriginalQty(), order.getRemainingQty(), order.getFilledQty(),
                order.getFilledNotionalMinor(), order.getOrderId(), order.getStage());
    }
}
