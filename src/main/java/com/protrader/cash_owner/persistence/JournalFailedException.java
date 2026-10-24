package com.protrader.cash_owner.persistence;

/**
 * The journal could not be written. The in-memory Ledger has already changed, so it is ahead of the disk: the owner
 * must stop taking work (restarting recovers the last journaled state).
 */
public final class JournalFailedException extends RuntimeException {

    public JournalFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
