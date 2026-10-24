package com.protrader.cash_owner.orders;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protrader.proto.exchange.v1.CancelOrderAck;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what a CancelOrder caller was told. */
class FakeCancelCaller implements StreamObserver<CancelOrderAck> {

    final List<CancelOrderAck> acks = new CopyOnWriteArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private volatile boolean completed;

    @Override
    public void onNext(CancelOrderAck ack) {
        acks.add(ack);
    }

    @Override
    public void onError(Throwable t) {
        errors.add(t);
    }

    @Override
    public void onCompleted() {
        completed = true;
    }

    boolean isCompleted() {
        return completed;
    }

    boolean isSilent() {
        return acks.isEmpty() && errors.isEmpty();
    }

    CancelOrderAck onlyAck() {
        assertTrue(acks.size() == 1 && errors.isEmpty(), "expected exactly one ack and no error, got " + acks + " / "
                + errors);
        return acks.get(0);
    }

    Status.Code errorCode() {
        assertTrue(errors.size() == 1 && acks.isEmpty(), "expected exactly one error and no ack, got " + errors
                + " / " + acks);
        return Status.fromThrowable(errors.get(0)).getCode();
    }
}
