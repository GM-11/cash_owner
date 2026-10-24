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

    private final List<Consumer<SubmitAck>> ackHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> openedHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> lostHandlers = new CopyOnWriteArrayList<>();

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
        ackHandlers.add(handler);
    }

    @Override
    public void onStreamOpened(Runnable handler) {
        openedHandlers.add(handler);
    }

    @Override
    public void onStreamLost(Runnable handler) {
        lostHandlers.add(handler);
    }

    void ack(long account, String clientOrderId, SubmitStatus status, long seq) {
        SubmitAck ack = SubmitAck.newBuilder().setAccountId(account).setClientOrderId(clientOrderId)
                .setStatus(status).setSeq(seq).build();
        ackHandlers.forEach(handler -> handler.accept(ack));
    }

    void loseStream() {
        streamOpen = false;
        lostHandlers.forEach(Runnable::run);
    }

    void openStream() {
        streamOpen = true;
        openedHandlers.forEach(Runnable::run);
    }

    /** Fires the "opened" handler without opening the stream (the stream died again straight away). */
    void fireOpenedWhileClosed() {
        openedHandlers.forEach(Runnable::run);
    }
}
