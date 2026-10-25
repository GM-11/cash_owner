package com.protrader.cash_owner.grpc;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Lets a streaming handler send the response headers before its first message.
 *
 * <p>grpc-java's stub sends headers only together with the first {@code onNext}. The order gateway treats "headers
 * received" on SubscribeAccountUpdates as "registered, from now on I get every update" (same rule as the sequencer
 * and engine streams). Without early headers a gateway stays not-ready until some account happens to change.
 *
 * <p>Installed for every call; the wrapper only makes sure headers go out once, so unary calls are unaffected.
 */
@Component
@GlobalServerInterceptor
public class EarlyHeaders implements ServerInterceptor {

    private static final Context.Key<Holder> KEY = Context.key("early-headers");

    /**
     * Sends the response headers of the current call now (at most once; the stub's own send later is skipped).
     * Call it on the handler's thread, before the first onNext. Outside a gRPC call (plain unit tests) it does
     * nothing and returns false.
     */
    public static boolean sendNow() {
        Holder holder = KEY.get();
        if (holder == null) {
            return false;
        }
        holder.call.sendHeaders(new Metadata());
        return true;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        AtomicBoolean sent = new AtomicBoolean();
        ServerCall<Q, R> once = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendHeaders(Metadata h) {
                if (sent.compareAndSet(false, true)) {
                    super.sendHeaders(h);
                }
            }
        };
        return Contexts.interceptCall(Context.current().withValue(KEY, new Holder(once)), once, headers, next);
    }

    private record Holder(ServerCall<?, ?> call) {
    }
}
