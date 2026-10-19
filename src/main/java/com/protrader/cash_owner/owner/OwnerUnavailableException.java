package com.protrader.cash_owner.owner;

/** The ledger owner is not started, is stopping, or is broken. The job was not run. */
public class OwnerUnavailableException extends RuntimeException {

    public OwnerUnavailableException(String message) {
        super(message);
    }
}
