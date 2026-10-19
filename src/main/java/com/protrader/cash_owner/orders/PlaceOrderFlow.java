package com.protrader.cash_owner.orders;

import com.protrader.cash_owner.grpc.ProtoMapper;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.RejectReason;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.owner.OwnerBusyException;
import com.protrader.cash_owner.owner.OwnerUnavailableException;
import com.protrader.cash_owner.sequencer.SequencerPort;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.CashRejectReason;
import com.protrader.proto.exchange.v1.CommandBody;
import com.protrader.proto.exchange.v1.NewOrder;
import com.protrader.proto.exchange.v1.Order;
import com.protrader.proto.exchange.v1.OrderType;
import com.protrader.proto.exchange.v1.PlaceOrderAck;
import com.protrader.proto.exchange.v1.PlaceOrderRequest;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import com.protrader.proto.exchange.v1.TimeInForce;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PlaceOrderFlow {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderFlow.class);

    private static final String UNKNOWN_OUTCOME = "outcome unknown: the sequencer may or may not have the order; "
            + "retry with the same client_order_id";

    /** caller == null: already answered (in doubt / resent). */
    record InFlight(SubmitRequest sent, StreamObserver<PlaceOrderAck> caller) {
    }

    sealed interface Outgoing {
        record Answer(StreamObserver<PlaceOrderAck> to, PlaceOrderAck ack) implements Outgoing {
        }

        record Unknown(StreamObserver<PlaceOrderAck> to) implements Outgoing {
        }
    }

    private final LedgerOwner owner;
    private final SequencerPort port;

    private final Map<OrderKey, InFlight> inFlight = new LinkedHashMap<>();

    public PlaceOrderFlow(LedgerOwner owner, SequencerPort port) {
        this.owner = owner;
        this.port = port;
        port.onAck(ack -> owner.followUp(l -> onAck(l, ack)).thenAccept(this::sendAll)
                .exceptionally(this::logFailure));
        port.onStreamLost(() -> owner.followUp(this::onStreamLost).thenAccept(this::sendAll)
                .exceptionally(this::logFailure));
        port.onStreamOpened(() -> owner.followUp(this::onStreamOpened).thenAccept(this::sendAll)
                .exceptionally(this::logFailure));
    }

    public void place(PlaceOrderRequest req, StreamObserver<PlaceOrderAck> caller) {
        owner.request(l -> placeJob(l, req, caller)).whenComplete((replies, error) -> {
            if (error == null) {
                sendAll(replies);
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                    : error;
            if (cause instanceof OwnerBusyException) {
                sendAll(List
                        .of(new Outgoing.Answer(caller, refused(CashRejectReason.CASH_REJECT_REASON_EXCHANGE_BUSY))));
            } else if (cause instanceof OwnerUnavailableException) {
                fail(caller, Status.UNAVAILABLE.withDescription("cash owner is unavailable; nothing was done"));
            } else {
                log.error("PlaceOrder failed unexpectedly for account {} order {}", req.getAccountId(),
                        req.getClientOrderId(), cause);
                fail(caller, Status.INTERNAL.withDescription("internal error"));
            }
        });
    }

    private List<Outgoing> placeJob(Ledger l, PlaceOrderRequest req, StreamObserver<PlaceOrderAck> caller) {
        OrderKey key = new OrderKey(req.getAccountId(), req.getClientOrderId());
        PlaceResult result = l.place(req.getAccountId(), req.getClientOrderId(), req.getSymbol(),
                ProtoMapper.fromProto(req.getSide()), req.getPriceMinor(), req.getQuantity());

        switch (result) {
            case PlaceResult.Rejected rejected -> {
                PlaceOrderAck.Builder ack = PlaceOrderAck.newBuilder().setAccepted(false)
                        .setReason(ProtoMapper.toProto(rejected.reason()));
                if (rejected.reason() == RejectReason.INSUFFICIENT_CASH) {
                    ack.setShortfallMinor(rejected.shortfallMinor());
                }
                return List.of(new Outgoing.Answer(caller, ack.build()));
            }
            case PlaceResult.Duplicate duplicate -> {
                PlaceOrderAck ack = PlaceOrderAck.newBuilder().setAccepted(false).setDuplicate(true)
                        .setOrder(ProtoMapper.toOrderState(duplicate.existing())).build();
                return List.of(new Outgoing.Answer(caller, ack));
            }
            case PlaceResult.Locked locked -> {
                SubmitRequest request;
                boolean sent;
                try {
                    request = buildSubmit(req);
                    sent = port.submit(request);
                } catch (RuntimeException e) {
                    l.onRefused(key); // never leave a lock behind without an inFlight entry
                    throw e;
                }
                if (!sent) {
                    l.onRefused(key);
                    return List.of(new Outgoing.Answer(caller,
                            refused(CashRejectReason.CASH_REJECT_REASON_EXCHANGE_UNAVAILABLE)));
                }
                inFlight.put(key, new InFlight(request, caller));
                return List.of(); // the answer comes with the sequencer's ack
            }
        }
    }

    private List<Outgoing> onAck(Ledger l, SubmitAck ack) {
        OrderKey key = new OrderKey(ack.getAccountId(), ack.getClientOrderId());
        InFlight f = inFlight.get(key);
        if (f == null) {
            log.debug("late or unknown ack: {}", ack);
            return List.of();
        }
        boolean resent = f.caller() == null;
        StreamObserver<PlaceOrderAck> caller = f.caller();

        switch (ack.getStatus()) {
            case SUBMIT_STATUS_ACCEPTED -> {
                if (ack.getSeq() != 0) {
                    l.onAccepted(key, ack.getSeq());
                    inFlight.remove(key);
                    return resent ? List.of()
                            : List.of(
                                    new Outgoing.Answer(caller, PlaceOrderAck.newBuilder().setAccepted(true).build()));
                }
                log.error("ACCEPTED ack with seq 0, treating it as no answer: {}", ack);
                return noAnswer(l, key, f, resent);
            }
            case SUBMIT_STATUS_BUSY -> {
                return definiteNo(l, key, resent, caller, CashRejectReason.CASH_REJECT_REASON_EXCHANGE_BUSY);
            }
            case SUBMIT_STATUS_UNKNOWN_SYMBOL -> {
                return definiteNo(l, key, resent, caller, CashRejectReason.CASH_REJECT_REASON_UNKNOWN_SYMBOL);
            }
            case SUBMIT_STATUS_NOT_RUNNING -> {
                if (resent) {

                    log.info("NOT_RUNNING for a resent order {}: staying in doubt", key);
                    return List.of();
                }
                return definiteNo(l, key, false, caller, CashRejectReason.CASH_REJECT_REASON_EXCHANGE_UNAVAILABLE);
            }
            default -> {
                log.error("unexpected sequencer ack: {}", ack);
                return noAnswer(l, key, f, resent);
            }
        }
    }

    private List<Outgoing> definiteNo(Ledger l, OrderKey key, boolean resent, StreamObserver<PlaceOrderAck> caller,
            CashRejectReason reason) {
        l.onRefused(key);
        inFlight.remove(key);
        return resent ? List.of() : List.of(new Outgoing.Answer(caller, refused(reason)));
    }

    private List<Outgoing> noAnswer(Ledger l, OrderKey key, InFlight f, boolean resent) {
        if (resent) {
            return List.of();
        }
        l.onNoAnswer(key);
        inFlight.put(key, new InFlight(f.sent(), null));
        return List.of(new Outgoing.Unknown(f.caller()));
    }

    private List<Outgoing> onStreamLost(Ledger l) {
        List<Outgoing> replies = new ArrayList<>();
        List<Map.Entry<OrderKey, InFlight>> changes = new ArrayList<>();
        for (Map.Entry<OrderKey, InFlight> e : inFlight.entrySet()) {
            if (e.getValue().caller() != null) {
                changes.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        for (Map.Entry<OrderKey, InFlight> change : changes) {
            l.onNoAnswer(change.getKey());
            replies.add(new Outgoing.Unknown(change.getValue().caller()));
            inFlight.put(change.getKey(), new InFlight(change.getValue().sent(), null));
        }
        return replies;
    }

    private List<Outgoing> onStreamOpened(Ledger l) {
        for (InFlight f : inFlight.values()) {
            if (f.caller() == null && !port.submit(f.sent())) {
                break; // the stream died again; the next "opened" event retries
            }
        }
        return List.of();
    }

    private static SubmitRequest buildSubmit(PlaceOrderRequest req) {
        Order order = Order.newBuilder().setId(0).setOwnerId(req.getAccountId()).setSide(req.getSide())
                .setType(OrderType.ORDER_TYPE_LIMIT).setPrice(req.getPriceMinor()).setQuantity(req.getQuantity())
                .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC).build();
        CommandBody body = CommandBody.newBuilder().setNewOrder(NewOrder.newBuilder().setOrder(order)).build();
        return SubmitRequest.newBuilder().setGatewayId(req.getGatewayId()).setClientOrderId(req.getClientOrderId())
                .setSymbol(req.getSymbol()).setAccountId(req.getAccountId()).setPayload(body.toByteString()).build();
    }

    private static PlaceOrderAck refused(CashRejectReason reason) {
        return PlaceOrderAck.newBuilder().setAccepted(false).setReason(reason).build();
    }

    private void sendAll(List<Outgoing> replies) {
        for (Outgoing reply : replies) {
            switch (reply) {
                case Outgoing.Answer a -> {
                    try {
                        a.to().onNext(a.ack());
                        a.to().onCompleted();
                    } catch (RuntimeException e) {
                        log.debug("could not send PlaceOrder answer (caller gone?)", e);
                    }
                }
                case Outgoing.Unknown u -> fail(u.to(), Status.UNAVAILABLE.withDescription(UNKNOWN_OUTCOME));
            }
        }
    }

    private static void fail(StreamObserver<?> to, Status status) {
        try {
            to.onError(status.asRuntimeException());
        } catch (RuntimeException e) {
            log.debug("could not send PlaceOrder error (caller gone?)", e);
        }
    }

    private Void logFailure(Throwable t) {
        log.error("sequencer event job failed", t);
        return null;
    }
}
