package com.protrader.cash_owner.models;

public final class Holding {
    long freeQty;
    long lockedQty;

    public long freeQty() {
        return freeQty;
    }

    public long lockedQty() {
        return lockedQty;
    }
}
