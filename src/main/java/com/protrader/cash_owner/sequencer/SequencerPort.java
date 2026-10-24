package com.protrader.cash_owner.sequencer;

import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import java.util.function.Consumer;

/** What the order flow needs from the sequencer link. Lets tests swap in a fake. */
public interface SequencerPort {

    /** Sends one request without waiting. false = no open stream; nothing was sent. */
    boolean submit(SubmitRequest request);

    /** Adds a handler. All handlers are called, in registration order, on the thread that delivers the event. */
    void onAck(Consumer<SubmitAck> handler);

    /** Adds a handler; see {@link #onAck}. */
    void onStreamOpened(Runnable handler);

    /** Adds a handler; see {@link #onAck}. */
    void onStreamLost(Runnable handler);
}
