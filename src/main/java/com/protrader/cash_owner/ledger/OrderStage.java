package com.protrader.cash_owner.ledger;

public enum OrderStage {
    /** locked, sent to the sequencer, awaiting ack */
    SENT,
    /** the submit stream broke before an ack; lock kept, to be resent */
    IN_DOUBT,
    /** the sequencer accepted it; it is in the engine */
    OPEN,
    /** finished; nothing locked */
    DONE
}
