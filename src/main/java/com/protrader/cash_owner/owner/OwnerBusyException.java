package com.protrader.cash_owner.owner;

/** Too much new work is waiting for the ledger owner. Nothing was done, so it is safe to retry. */
public class OwnerBusyException extends RuntimeException {

    public OwnerBusyException(String message) {
        super(message);
    }
}
