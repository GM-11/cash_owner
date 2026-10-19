package com.protrader.cash_owner.models;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Account {
    final long accountId;
    long freeCashMinor;
    long lockedCashMinor;
    final Map<Integer, Holding> holdings = new HashMap<>();

    Account(long accountId, long freeCashMinor, long lockedCashMinor) {
        this.accountId = accountId;
        this.freeCashMinor = freeCashMinor;
        this.lockedCashMinor = lockedCashMinor;
    }

    public long accountId() {
        return accountId;
    }

    public long freeCashMinor() {
        return freeCashMinor;
    }

    public long lockedCashMinor() {
        return lockedCashMinor;
    }

    public Map<Integer, Holding> holdings() {
        return Collections.unmodifiableMap(holdings);
    }

    /** Get or create the (empty) holding for a symbol. */
    Holding holding(int symbol) {
        return holdings.computeIfAbsent(symbol, s -> new Holding());
    }
}
