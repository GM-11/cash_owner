package com.protrader.cash_owner.models;

import java.util.HashMap;
import java.util.Map;

/** Read-only copy of an {@link Account}. Safe to hand to any thread. */
public record AccountView(long accountId, long freeCashMinor, long lockedCashMinor,
        Map<Integer, HoldingView> holdings) {

    public static AccountView of(Account account) {
        Map<Integer, HoldingView> holdings = new HashMap<>();
        account.getHoldings().forEach((symbol, holding) -> holdings.put(symbol, HoldingView.of(holding)));
        return new AccountView(account.getAccountId(), account.getFreeCashMinor(), account.getLockedCashMinor(),
                Map.copyOf(holdings));
    }
}
