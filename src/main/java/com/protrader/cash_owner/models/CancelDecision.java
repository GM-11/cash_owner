package com.protrader.cash_owner.models;

/** What the Ledger says about a cancel request. */
public enum CancelDecision {
    /** The order is open: send the cancel to the sequencer. */
    SEND,
    /** No such order for this account (or it has no engine id yet). */
    NOT_FOUND,
    /** The order is already finished. */
    CLOSED
}
