package com.protrader.cash_owner.orders;

import com.protrader.cash_owner.grpc.ProtoMapper;
import com.protrader.cash_owner.models.CancelDecision;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.owner.OwnerBusyException;
import com.protrader.cash_owner.owner.OwnerUnavailableException;
import com.protrader.cash_owner.sequencer.SequencerPort;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.CancelOrder;
import com.protrader.proto.exchange.v1.CancelOrderAck;
import com.protrader.proto.exchange.v1.CancelOrderRequest;
import com.protrader.proto.exchange.v1.CancelRejectReason;
import com.protrader.proto.exchange.v1.CommandBody;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CancelOrder. Mirrors {@link PlaceOrderFlow}: the Ledger is only touched on the owner thread, answers go out after
 * the owner job returns.
 *
 * <p>
 * A cancel never moves money: only the engine's CANCELLED event unlocks the remainder. The Ledger just remembers
 * that a cancel is pending (so the order shows as PENDING_CANCEL).
 *
 * <p>
 * Cancels carry client_order_id = "", so the sequencer does not dedupe them, and on one stream its acks come back in
 * exactly the order the requests were sent. That is why a plain FIFO queue pairs each ack with its caller.
 */
@Component
public class CancelOrderFlow {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderFlow.class);

    private static final String UNKNOWN_OUTCOME = "outcome unknown: the cancel may or may not have reached the "
            + "exchange; retry";

    /** A cancel the sequencer has not answered yet. */
    record WaitingCancel(EngineRef ref, long accountId, StreamObserver<CancelOrderAck> caller) {
    }

    sealed interface Outgoing {
        record Answer(StreamObserver<CancelOrderAck> to, CancelOrderAck ack) implements Outgoing {
        }

        record Fail(StreamObserver<CancelOrderAck> to, Status status) implements Outgoing {
        }
    }

    private final LedgerOwner owner;
    private final SequencerPort port;

    /** Owner thread only. Queue order = wire order. */
    private final ArrayDeque<WaitingCancel> waiting = new ArrayDeque<>();

    public CancelOrderFlow(LedgerOwner owner, SequencerPort port) {
        this.owner = owner;
        this.port = port;
        port.onAck(ack -> {
            if (!ack.getClientOrderId().isEmpty()) {
                return; // a place ack: PlaceOrderFlow takes it
            }
            owner.followUp(l -> onAck(l, ack)).thenAccept(this::sendAll).exceptionally(this::logFailure);
        });
        port.onStreamLost(() -> owner.followUp(this::onStreamLost).thenAccept(this::sendAll)
                .exceptionally(this::logFailure));
    }

    public void cancel(CancelOrderRequest req, StreamObserver<CancelOrderAck> caller) {
        String invalid = invalidField(req);
        if (invalid != null) {
            fail(caller, Status.INVALID_ARGUMENT.withDescription(invalid));
            return;
        }
        owner.request(l -> cancelJob(l, req, caller)).whenComplete((replies, error) -> {
            if (error == null) {
                sendAll(replies);
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                    : error;
            if (cause instanceof OwnerBusyException) {
                sendAll(List.of(
                        new Outgoing.Answer(caller, refused(CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_BUSY))));
            } else if (cause instanceof OwnerUnavailableException) {
                fail(caller, Status.UNAVAILABLE.withDescription("cash owner is unavailable; nothing was done"));
            } else {
                log.error("CancelOrder failed unexpectedly for account {} order {}", req.getAccountId(),
                        req.getOrderId(), cause);
                fail(caller, Status.INTERNAL.withDescription("internal error"));
            }
        });
    }

    private static String invalidField(CancelOrderRequest req) {
        if (req.getAccountId() == 0) {
            return "account_id is required";
        }
        if (req.getOrderId() == 0) {
            return "order_id is required";
        }
        if (req.getGatewayId().isEmpty()) {
            return "gateway_id is required";
        }
        return null;
    }

    private List<Outgoing> cancelJob(Ledger l, CancelOrderRequest req, StreamObserver<CancelOrderAck> caller) {
        long accountId = req.getAccountId();
        EngineRef ref = new EngineRef(req.getSymbol(), req.getOrderId());
        CancelDecision decision = l.requestCancel(accountId, ref);

        switch (decision) {
            case NOT_FOUND -> {
                return List.of(new Outgoing.Answer(caller,
                        refused(CancelRejectReason.CANCEL_REJECT_REASON_ORDER_NOT_FOUND)));
            }
            case CLOSED -> {
                CancelOrderAck.Builder ack = CancelOrderAck.newBuilder().setAccepted(false)
                        .setReason(CancelRejectReason.CANCEL_REJECT_REASON_ORDER_CLOSED);
                l.orderByEngineRef(ref).ifPresent(order -> ack.setOrder(ProtoMapper.toOrderState(order)));
                return List.of(new Outgoing.Answer(caller, ack.build()));
            }
            case SEND -> {
                boolean sent;
                try {
                    sent = port.submit(buildSubmit(req));
                } catch (RuntimeException e) {
                    refuseUnlessOtherWaiting(l, ref); // never leave a pending flag nobody is answering for
                    throw e;
                }
                if (!sent) {
                    refuseUnlessOtherWaiting(l, ref);
                    return List.of(new Outgoing.Answer(caller,
                            refused(CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_UNAVAILABLE)));
                }
                // Same owner job as the submit: queue order stays equal to wire order.
                waiting.addLast(new WaitingCancel(ref, accountId, caller));
                return List.of(); // the answer comes with the sequencer's ack
            }
        }
        throw new IllegalStateException("unhandled cancel decision " + decision);
    }

    private List<Outgoing> onAck(Ledger l, SubmitAck ack) {
        WaitingCancel waitingCancel = waiting.pollFirst();
        if (waitingCancel == null) {
            log.warn("cancel ack with nobody waiting: {}", ack);
            return List.of();
        }
        if (ack.getAccountId() != waitingCancel.accountId()) {
            log.error("cancel ack for account {} but the oldest waiting cancel is for account {}; not guessing: {}",
                    ack.getAccountId(), waitingCancel.accountId(), ack);
            return List.of(new Outgoing.Fail(waitingCancel.caller(), Status.UNAVAILABLE.withDescription(UNKNOWN_OUTCOME)));
        }

        switch (ack.getStatus()) {
            case SUBMIT_STATUS_ACCEPTED -> {
                // The CANCELLED event may already have been applied: still "accepted".
                CancelOrderAck.Builder answer = CancelOrderAck.newBuilder().setAccepted(true);
                l.orderByEngineRef(waitingCancel.ref()).ifPresent(order -> answer.setOrder(ProtoMapper.toOrderState(order)));
                return List.of(new Outgoing.Answer(waitingCancel.caller(), answer.build()));
            }
            case SUBMIT_STATUS_BUSY -> {
                return definiteNo(l, waitingCancel, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_BUSY);
            }
            case SUBMIT_STATUS_NOT_RUNNING -> {
                return definiteNo(l, waitingCancel, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_UNAVAILABLE);
            }
            case SUBMIT_STATUS_UNKNOWN_SYMBOL -> {
                return definiteNo(l, waitingCancel, CancelRejectReason.CANCEL_REJECT_REASON_ORDER_NOT_FOUND);
            }
            default -> {
                log.error("unexpected sequencer ack for a cancel: {}", ack);
                return List.of(new Outgoing.Fail(waitingCancel.caller(), Status.UNAVAILABLE.withDescription(UNKNOWN_OUTCOME)));
            }
        }
    }

    private List<Outgoing> definiteNo(Ledger l, WaitingCancel waitingCancel, CancelRejectReason reason) {
        refuseUnlessOtherWaiting(l, waitingCancel.ref());
        return List.of(new Outgoing.Answer(waitingCancel.caller(), refused(reason)));
    }

    /** Another cancel for the same order may still be on its way to the engine: then the flag must stay. */
    private void refuseUnlessOtherWaiting(Ledger l, EngineRef ref) {
        for (WaitingCancel other : waiting) {
            if (other.ref().equals(ref)) {
                return;
            }
        }
        l.onCancelRefused(ref);
    }

    private List<Outgoing> onStreamLost(Ledger l) {
        // cancelPending stays: if a cancel did reach the engine, CANCELLED will arrive. Nothing is resent on reopen.
        List<Outgoing> replies = new ArrayList<>();
        for (WaitingCancel waitingCancel : waiting) {
            replies.add(new Outgoing.Fail(waitingCancel.caller(), Status.UNAVAILABLE.withDescription(UNKNOWN_OUTCOME)));
        }
        waiting.clear();
        return replies;
    }

    private static SubmitRequest buildSubmit(CancelOrderRequest req) {
        CommandBody body = CommandBody.newBuilder()
                .setCancelOrder(CancelOrder.newBuilder().setRequester(req.getAccountId()).setOrderId(req.getOrderId()))
                .build();
        return SubmitRequest.newBuilder().setGatewayId(req.getGatewayId()).setClientOrderId("")
                .setSymbol(req.getSymbol()).setAccountId(req.getAccountId()).setPayload(body.toByteString()).build();
    }

    private static CancelOrderAck refused(CancelRejectReason reason) {
        return CancelOrderAck.newBuilder().setAccepted(false).setReason(reason).build();
    }

    private void sendAll(List<Outgoing> replies) {
        for (Outgoing reply : replies) {
            switch (reply) {
                case Outgoing.Answer answer -> {
                    try {
                        answer.to().onNext(answer.ack());
                        answer.to().onCompleted();
                    } catch (RuntimeException e) {
                        log.debug("could not send CancelOrder answer (caller gone?)", e);
                    }
                }
                case Outgoing.Fail failure -> fail(failure.to(), failure.status());
            }
        }
    }

    private static void fail(StreamObserver<?> to, Status status) {
        try {
            to.onError(status.asRuntimeException());
        } catch (RuntimeException e) {
            log.debug("could not send CancelOrder error (caller gone?)", e);
        }
    }

    private Void logFailure(Throwable t) {
        log.error("sequencer event job failed", t);
        return null;
    }
}
