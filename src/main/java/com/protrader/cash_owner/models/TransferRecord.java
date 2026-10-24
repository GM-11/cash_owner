package com.protrader.cash_owner.models;

/** What the Ledger remembers about an applied transfer, to answer repeats. */
public record TransferRecord(boolean deposit, long amountMinor, long ts) {
}
