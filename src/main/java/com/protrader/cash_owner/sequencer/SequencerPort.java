package com.protrader.cash_owner.sequencer;

import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import java.util.function.Consumer;

/** What the order flow needs from the sequencer link. Lets tests swap in a fake. */
public interface SequencerPort {

    /** Sends one request without waiting. false = no open stream; nothing was sent. */
    boolean submit(SubmitRequest request);

    void onAck(Consumer<SubmitAck> handler);

    void onStreamOpened(Runnable handler);

    void onStreamLost(Runnable handler);
}
