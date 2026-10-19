package com.protrader.cash_owner.models;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

import lombok.Setter;

@Getter
@Setter
public final class Account {
    final long accountId;
    long freeCashMinor;
    long lockedCashMinor;
    final Map<Integer, Holding> holdings = new HashMap<>();

    public Account(long accountId, long freeCashMinor, long lockedCashMinor) {
        this.accountId = accountId;
        this.freeCashMinor = freeCashMinor;
        this.lockedCashMinor = lockedCashMinor;
    }

    public Holding getHoldingFromSymbol(int symbol) {
        return holdings.computeIfAbsent(symbol, ignored -> new Holding(0, 0));
    }
}
