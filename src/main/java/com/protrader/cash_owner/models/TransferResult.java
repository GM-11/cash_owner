package com.protrader.cash_owner.models;

import com.protrader.cash_owner.models.views.BalancesView;

public sealed interface TransferResult {

    /** The transfer changed the account; {@code change} is the one update to publish. */
    record Applied(AccountChange change) implements TransferResult {
    }

    /** The transfer_id was applied before. Nothing changed; balances and seq are the current ones. */
    record Duplicate(BalancesView balances, long accountSeq) implements TransferResult {
    }

    record InsufficientCash(long shortfallMinor, BalancesView balances, long accountSeq) implements TransferResult {
    }

    record UnknownAccount() implements TransferResult {
    }
}
