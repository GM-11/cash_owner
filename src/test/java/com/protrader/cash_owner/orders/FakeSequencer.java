package com.protrader.cash_owner.orders;

import com.protrader.cash_owner.sequencer.SequencerPort;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import com.protrader.proto.exchange.v1.SubmitStatus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Records submits; the test plays the sequencer's answers and stream events. */
class FakeSequencer implements SequencerPort {

    final List<SubmitRequest> submitted = new CopyOnWriteArrayList<>();
    volatile boolean streamOpen = true;

    private volatile Consumer<SubmitAck> ackHandler = a -> {
    };
    private volatile Runnable openedHandler = () -> {
    };
    private volatile Runnable lostHandler = () -> {
    };

    @Override
    public boolean submit(SubmitRequest request) {
        if (!streamOpen) {
            return false;
        }
        submitted.add(request);
        return true;
    }

    @Override
    public void onAck(Consumer<SubmitAck> handler) {
        ackHandler = handler;
    }

    @Override
    public void onStreamOpened(Runnable handler) {
        openedHandler = handler;
    }

    @Override
    public void onStreamLost(Runnable handler) {
        lostHandler = handler;
    }

    void ack(long account, String clientOrderId, SubmitStatus status, long seq) {
        ackHandler.accept(SubmitAck.newBuilder().setAccountId(account).setClientOrderId(clientOrderId)
                .setStatus(status).setSeq(seq).build());
    }

    void loseStream() {
        streamOpen = false;
        lostHandler.run();
    }

    void openStream() {
        streamOpen = true;
        openedHandler.run();
    }

    /** Fires the "opened" handler without opening the stream (the stream died again straight away). */
    void fireOpenedWhileClosed() {
        openedHandler.run();
    }
}
