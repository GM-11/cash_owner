package com.protrader.cash_owner.models;

import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.HoldingView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.models.views.TransferView;

/** One update to an account. {@code order} is set for order changes, {@code transfer} for TRANSFER; never both. */
public record AccountChange(long accountId, long accountSeq, AccountChangeKind kind, OrderView order,
        long fillPriceMinor, long fillQty, long engineSeq, long ts, BalancesView balances, HoldingView position,
        TransferView transfer) {
}
