package com.protrader.cash_owner.sequencer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.proto.exchange.v1.SequencerGrpc;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Uses grpc-java's in-process transport: no real network. */
class SequencerLinkTest {

    /** What the fake server does with each Submit stream. */
    private enum Mode {
        /** accept the stream, never send headers */
        SILENT,
        /** send headers as soon as the call starts */
        HEADERS,
        /** close at once, before any headers */
        CLOSE_BEFORE_HEADERS
    }

    private volatile Mode mode = Mode.SILENT;
    private final AtomicInteger streamsAccepted = new AtomicInteger();
    private final List<SubmitRequest> received = new CopyOnWriteArrayList<>();
    private final List<StreamObserver<SubmitAck>> responders = new CopyOnWriteArrayList<>();

    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger lost = new AtomicInteger();

    private Server server;
    private ManagedChannel channel;
    private SequencerLink link;

    private void startLink(Mode initialMode) throws Exception {
        mode = initialMode;
        String name = "sequencer-" + UUID.randomUUID();
        ServerInterceptor sendHeadersEarly = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                if (mode == Mode.HEADERS) {
                    call.sendHeaders(new Metadata());
                }
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).addService(new SequencerGrpc.SequencerImplBase() {
            @Override
            public StreamObserver<SubmitRequest> submit(StreamObserver<SubmitAck> responseObserver) {
                streamsAccepted.incrementAndGet();
                responders.add(responseObserver);
                if (mode == Mode.CLOSE_BEFORE_HEADERS) {
                    responseObserver.onCompleted();
                }
                return new StreamObserver<>() {
                    @Override
                    public void onNext(SubmitRequest request) {
                        received.add(request);
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                };
            }
        }).intercept(sendHeadersEarly).build().start();
        channel = InProcessChannelBuilder.forName(name).build();

        CashOwnerProperties props = new CashOwnerProperties(null,
                new CashOwnerProperties.Sequencer(true, Duration.ofMillis(50), Duration.ofMillis(200)), null, null, null);
        link = new SequencerLink(SequencerGrpc.newStub(channel), props);
        link.onStreamOpened(opened::incrementAndGet);
        link.onStreamLost(lost::incrementAndGet);
        link.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (link != null) {
            link.stop();
        }
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(10);
        }
    }

    private static SubmitRequest request() {
        return SubmitRequest.newBuilder().setGatewayId("gw1").setClientOrderId("c1").setSymbol(1).setAccountId(1)
                .build();
    }

    @Test
    void serverThatNeverSendsHeadersLeavesTheStreamClosed() throws Exception {
        startLink(Mode.SILENT);
        await("the server to accept the stream", () -> streamsAccepted.get() == 1);

        Thread.sleep(300); // plenty of time for a wrongly "open" link to show itself

        assertFalse(link.isStreamOpen());
        assertFalse(link.submit(request()));
        assertEquals(0, opened.get());
        assertEquals(0, lost.get());
        assertTrue(received.isEmpty());
    }

    @Test
    void headersOpenTheStreamExactlyOnce() throws Exception {
        startLink(Mode.HEADERS);
        await("the stream to open", () -> link.isStreamOpen());

        Thread.sleep(200);

        assertEquals(1, opened.get());
        assertEquals(0, lost.get());
        assertTrue(link.submit(request()));
        await("the server to receive the request", () -> received.size() == 1);
        assertEquals("c1", received.get(0).getClientOrderId());
    }

    @Test
    void closeBeforeHeadersReconnectsWithoutReportingALostStream() throws Exception {
        startLink(Mode.CLOSE_BEFORE_HEADERS);

        await("a reconnect (third attempt)", () -> streamsAccepted.get() >= 3);

        assertFalse(link.isStreamOpen());
        assertFalse(link.submit(request()));
        assertEquals(0, opened.get());
        assertEquals(0, lost.get());
    }

    @Test
    void closeAfterOpenReportsLostOnceThenReconnects() throws Exception {
        startLink(Mode.HEADERS);
        await("the stream to open", () -> link.isStreamOpen() && opened.get() == 1);

        responders.get(0).onCompleted(); // the server closes the first stream

        await("the lost event", () -> lost.get() == 1);
        await("the reconnect", () -> streamsAccepted.get() == 2 && opened.get() == 2);
        Thread.sleep(200);

        assertEquals(1, lost.get());
        assertEquals(2, opened.get());
        assertTrue(link.isStreamOpen());
        assertTrue(link.submit(request()));
    }
}
