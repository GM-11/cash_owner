package com.protrader.cash_owner.models;

public final class Order {
    final OrderKey key;
    final int symbol;
    final Side side;
    final long limitPriceMinor;
    final long originalQty;
    long remainingQty;
    long filledQty;
    long filledNotionalMinor;
    long orderId;
    OrderStage stage;

    Order(OrderKey key, int symbol, Side side, long limitPriceMinor, long originalQty) {
        this.key = key;
        this.symbol = symbol;
        this.side = side;
        this.limitPriceMinor = limitPriceMinor;
        this.originalQty = originalQty;
        this.remainingQty = originalQty;
        this.filledQty = 0;
        this.filledNotionalMinor = 0;
        this.orderId = 0;
        this.stage = OrderStage.SENT;
    }

    public OrderKey key() {
        return key;
    }

    public int symbol() {
        return symbol;
    }

    public Side side() {
        return side;
    }

    public long limitPriceMinor() {
        return limitPriceMinor;
    }

    public long originalQty() {
        return originalQty;
    }

    public long remainingQty() {
        return remainingQty;
    }

    public long filledQty() {
        return filledQty;
    }

    public long filledNotionalMinor() {
        return filledNotionalMinor;
    }

    public long orderId() {
        return orderId;
    }

    public OrderStage stage() {
        return stage;
    }
}
