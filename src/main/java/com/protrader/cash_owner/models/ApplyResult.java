package com.protrader.cash_owner.models;

import java.util.List;
import java.util.Objects;

public record ApplyResult(Outcome outcome, List<AccountChange> changes) {

    private static final ApplyResult DUPLICATE = new ApplyResult(Outcome.DUPLICATE, List.of());
    private static final ApplyResult IGNORED = new ApplyResult(Outcome.IGNORED, List.of());

    public ApplyResult {
        Objects.requireNonNull(outcome, "outcome");
        changes = List.copyOf(changes);
        if (outcome != Outcome.APPLIED && !changes.isEmpty()) {
            throw new IllegalArgumentException(outcome + " cannot carry changes: " + changes);
        }
    }

    public static ApplyResult duplicate() {
        return DUPLICATE;
    }

    public static ApplyResult ignored() {
        return IGNORED;
    }

    public static ApplyResult applied(List<AccountChange> changes) {
        return new ApplyResult(Outcome.APPLIED, changes);
    }
}
