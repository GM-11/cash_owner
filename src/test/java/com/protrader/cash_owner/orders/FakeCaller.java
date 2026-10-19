package com.protrader.cash_owner.orders;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protrader.proto.exchange.v1.PlaceOrderAck;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class FakeCaller implements StreamObserver<PlaceOrderAck> {

    final List<PlaceOrderAck> acks = new CopyOnWriteArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private volatile boolean completed;
    private final CountDownLatch terminal = new CountDownLatch(1);

    @Override
    public void onNext(PlaceOrderAck ack) {
        acks.add(ack);
    }

    @Override
    public void onError(Throwable t) {
        errors.add(t);
        terminal.countDown();
    }

    @Override
    public void onCompleted() {
        completed = true;
        terminal.countDown();
    }

    /** Waits (at most 5 s) for onCompleted or onError. */
    void awaitTerminal() throws InterruptedException {
        assertTrue(terminal.await(5, TimeUnit.SECONDS), "no answer within 5 s");
    }

    boolean isCompleted() {
        return completed;
    }

    PlaceOrderAck onlyAck() {
        assertTrue(acks.size() == 1, "expected exactly one ack, got " + acks);
        return acks.get(0);
    }

    Status.Code errorCode() {
        assertTrue(errors.size() == 1, "expected exactly one error, got " + errors);
        return Status.fromThrowable(errors.get(0)).getCode();
    }
}
