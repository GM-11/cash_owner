package com.protrader.cash_owner.persistence;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.services.Ledger;

/**
 * One Ledger input that changed state. {@link #applyTo} calls the same public Ledger method the input came through,
 * so replaying a journal re-runs the exact same code. Return values are dropped on replay.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LedgerCommand.OpenAccount.class, name = "OpenAccount"),
        @JsonSubTypes.Type(value = LedgerCommand.Place.class, name = "Place"),
        @JsonSubTypes.Type(value = LedgerCommand.Accepted.class, name = "Accepted"),
        @JsonSubTypes.Type(value = LedgerCommand.Refused.class, name = "Refused"),
        @JsonSubTypes.Type(value = LedgerCommand.NoAnswer.class, name = "NoAnswer"),
        @JsonSubTypes.Type(value = LedgerCommand.Fill.class, name = "Fill"),
        @JsonSubTypes.Type(value = LedgerCommand.RemainderEnded.class, name = "RemainderEnded"),
        @JsonSubTypes.Type(value = LedgerCommand.EngineRejected.class, name = "EngineRejected"),
        @JsonSubTypes.Type(value = LedgerCommand.EngineEventApplied.class, name = "EngineEventApplied"),
        @JsonSubTypes.Type(value = LedgerCommand.RequestCancel.class, name = "RequestCancel"),
        @JsonSubTypes.Type(value = LedgerCommand.CancelRefused.class, name = "CancelRefused"),
        @JsonSubTypes.Type(value = LedgerCommand.Transfer.class, name = "Transfer") })
public sealed interface LedgerCommand {

    void applyTo(Ledger ledger);

    record OpenAccount(long accountId, long cashMinor, Map<Integer, Long> shares) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.openAccount(accountId, cashMinor, shares);
        }
    }

    /** Only recorded when the place was accepted (Locked). */
    record Place(long accountId, String clientOrderId, int symbol, Side side, long limitPriceMinor, long qty)
            implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.place(accountId, clientOrderId, symbol, side, limitPriceMinor, qty);
        }
    }

    record Accepted(OrderKey key, long orderId) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onAccepted(key, orderId);
        }
    }

    record Refused(OrderKey key) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onRefused(key);
        }
    }

    record NoAnswer(OrderKey key) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onNoAnswer(key);
        }
    }

    record Fill(EngineRef ref, long tradePriceMinor, long qty) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onFill(ref, tradePriceMinor, qty);
        }
    }

    record RemainderEnded(EngineRef ref, long qty, DoneReason reason) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onRemainderEnded(ref, qty, reason);
        }
    }

    record EngineRejected(OrderKey key, long orderId) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onEngineRejected(key, orderId);
        }
    }

    /** An engine event that was APPLIED or IGNORED (it moves lastApplied). Never a DUPLICATE. */
    record EngineEventApplied(EngineEvent event) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.applyEngineEvent(event);
        }
    }

    /** Only recorded when the decision was SEND. */
    record RequestCancel(long accountId, EngineRef ref) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.requestCancel(accountId, ref);
        }
    }

    record CancelRefused(EngineRef ref) implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            ledger.onCancelRefused(ref);
        }
    }

    /** Only recorded when the transfer was applied. A deposit may create the account. */
    record Transfer(long accountId, String transferId, boolean deposit, long amountMinor, long tsMillis)
            implements LedgerCommand {
        @Override
        public void applyTo(Ledger ledger) {
            if (deposit)
                ledger.deposit(accountId, transferId, amountMinor, tsMillis);
            else
                ledger.withdraw(accountId, transferId, amountMinor, tsMillis);
        }
    }
}
