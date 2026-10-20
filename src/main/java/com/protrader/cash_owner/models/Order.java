package com.protrader.cash_owner.models;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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
    DoneReason doneReason;

    public Order(OrderKey key, int symbol, Side side, long limitPriceMinor, long originalQty) {
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
        this.doneReason = null;
    }

}
