package com.protrader.cash_owner.models;

/** The engine event kinds the cash owner cares about. Everything else arrives as OTHER. */
public enum EngineEventKind {
    ACCEPTED,
    RESTED,
    TRADE,
    CANCELLED,
    EXPIRED,
    REJECTED,
    HALTED,
    OTHER
}
