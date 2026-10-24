package com.protrader.cash_owner.models;

public enum AccountChangeKind {
    NEW,
    PARTIAL_FILL,
    FILL,
    CANCELED,
    EXPIRED,
    REJECTED,
    CANCEL_REJECTED,
    TRANSFER
}
