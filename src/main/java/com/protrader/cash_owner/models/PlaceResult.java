package com.protrader.cash_owner.models;

public sealed interface PlaceResult {
    record Locked(Order order) implements PlaceResult {
    }

    record Rejected(RejectReason reason, long shortfallMinor) implements PlaceResult {
    }

    record Duplicate(Order existing) implements PlaceResult {
    }
}
