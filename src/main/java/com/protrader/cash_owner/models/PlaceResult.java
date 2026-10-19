package com.protrader.cash_owner.models;

public sealed interface PlaceResult {
    record Locked(OrderView order) implements PlaceResult {
    }

    record Rejected(RejectReason reason, long shortfallMinor) implements PlaceResult {
    }

    record Duplicate(OrderView existing) implements PlaceResult {
    }
}
