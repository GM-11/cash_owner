package com.protrader.cash_owner.models.views;

import com.protrader.cash_owner.models.Holding;

/**
 * Read-only copy of a {@link Holding}. {@code costMinor} is the exact total paid for the shares still held;
 * the average price is derived from it for display only and never fed back into money.
 */
public record HoldingView(long freeQty, long lockedQty, long costMinor, long realizedPnlMinor) {

    public static HoldingView of(Holding holding) {
        return new HoldingView(holding.getFreeQty(), holding.getLockedQty(), holding.getTotalCostMinor(),
                holding.getRealizedPnlMinorForSymbol());
    }

    public long qty() {
        return freeQty + lockedQty;
    }

    /** Average entry price in paise, rounded down; 0 when no shares are held. */
    public long avgPriceMinor() {
        long qty = qty();
        return qty == 0 ? 0 : costMinor / qty;
    }
}
