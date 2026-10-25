package com.protrader.cash_owner.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.AccountUpdatesRequest;
import com.protrader.proto.exchange.v1.CashOwnerGrpc;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Given a gateway subscribes to account updates and no account has changed yet,
 * then it still receives the response headers (its "you are registered" signal);
 * and a later update still arrives normally (headers are not sent twice).
 */
class EarlyHeadersTest {

    private Server server;
    private ManagedChannel channel;
    private final CompletableFuture<ServerCallStreamObserver<AccountUpdate>> subscribed = new CompletableFuture<>();

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Same steps as AccountUpdateBus.subscribe: register under the stream lock, then send headers. */
    private void start(boolean earlyHeaders) throws Exception {
        String name = UUID.randomUUID().toString();
        var service = new CashOwnerGrpc.CashOwnerImplBase() {
            @Override
            public void subscribeAccountUpdates(AccountUpdatesRequest request, StreamObserver<AccountUpdate> out) {
                var stream = (ServerCallStreamObserver<AccountUpdate>) out;
                synchronized (stream) {
                    if (earlyHeaders) {
                        assertTrue(EarlyHeaders.sendNow());
                    }
                }
                subscribed.complete(stream);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, new EarlyHeaders())).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private record Client(BlockingQueue<Object> events) {
    }

    /** Opens the stream with the raw ClientCall API so headers and messages are seen separately. */
    private Client subscribe() {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        ClientCall<AccountUpdatesRequest, AccountUpdate> call = channel
                .newCall(CashOwnerGrpc.getSubscribeAccountUpdatesMethod(), io.grpc.CallOptions.DEFAULT);
        call.start(new ClientCall.Listener<>() {
            @Override
            public void onHeaders(Metadata headers) {
                events.add("headers");
            }

            @Override
            public void onMessage(AccountUpdate message) {
                events.add(message);
                call.request(1);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                events.add(status);
            }
        }, new Metadata());
        call.sendMessage(AccountUpdatesRequest.newBuilder().setGatewayId("gw-test").build());
        call.halfClose();
        call.request(1);
        return new Client(events);
    }

    @Test
    void headersArriveBeforeAnyUpdate() throws Exception {
        start(true);
        Client client = subscribe();
        assertEquals("headers", client.events().poll(5, TimeUnit.SECONDS));
    }

    @Test
    void withoutEarlyHeadersNothingArrivesUntilTheFirstUpdate() throws Exception {
        start(false);
        Client client = subscribe();
        subscribed.get(5, TimeUnit.SECONDS);
        assertNull(client.events().poll(300, TimeUnit.MILLISECONDS)); // the bug the live run hit
    }

    @Test
    void laterUpdateStillArrivesAfterEarlyHeaders() throws Exception {
        start(true);
        Client client = subscribe();
        assertEquals("headers", client.events().poll(5, TimeUnit.SECONDS));

        ServerCallStreamObserver<AccountUpdate> stream = subscribed.get(5, TimeUnit.SECONDS);
        AccountUpdate update = AccountUpdate.newBuilder().setAccountId(1000).setAccountSeq(1).build();
        synchronized (stream) {
            stream.onNext(update); // the stub tries to send headers again; the wrapper skips it
        }
        Object next = client.events().poll(5, TimeUnit.SECONDS);
        assertNotNull(next);
        assertEquals(update, next);
    }

    @Test
    void outsideAGrpcCallSendNowDoesNothing() {
        assertFalse(EarlyHeaders.sendNow());
    }
}
