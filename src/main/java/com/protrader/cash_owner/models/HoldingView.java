package com.protrader.cash_owner.models;

/** Read-only copy of a {@link Holding}. */
public record HoldingView(long freeQty, long lockedQty) {

    public static HoldingView of(Holding holding) {
        return new HoldingView(holding.getFreeQty(), holding.getLockedQty());
    }
}
