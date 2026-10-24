package com.protrader.cash_owner.orders;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.protrader.cash_owner.grpc.ProtoMapper;
import com.protrader.cash_owner.models.TransferResult;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.owner.OwnerBusyException;
import com.protrader.cash_owner.owner.OwnerUnavailableException;
import com.protrader.cash_owner.services.AccountUpdatePublisher;
import com.protrader.proto.exchange.v1.TransferAck;
import com.protrader.proto.exchange.v1.TransferRequest;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Deposit and Withdraw. The Ledger dedupes on (account_id, transfer_id), so any gRPC error ("outcome unknown") is
 * safe to retry with the same transfer_id. The clock is read here, inside the owner job, and passed to the Ledger
 * (which has none, so replay stays deterministic).
 */
@Component
public class TransferFlow {

    private static final Logger log = LoggerFactory.getLogger(TransferFlow.class);

    private static final Pattern TRANSFER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,48}$");

    private final LedgerOwner owner;
    private final AccountUpdatePublisher publisher;

    public TransferFlow(LedgerOwner owner, AccountUpdatePublisher publisher) {
        this.owner = owner;
        this.publisher = publisher;
    }

    public void transfer(boolean deposit, TransferRequest req, StreamObserver<TransferAck> caller) {
        String invalid = invalidField(req);
        if (invalid != null) {
            fail(caller, Status.INVALID_ARGUMENT.withDescription(invalid));
            return;
        }
        owner.request(l -> {
            long ts = System.currentTimeMillis();
            TransferResult result = deposit
                    ? l.deposit(req.getAccountId(), req.getTransferId(), req.getAmountMinor(), ts)
                    : l.withdraw(req.getAccountId(), req.getTransferId(), req.getAmountMinor(), ts);
            if (result instanceof TransferResult.Applied applied) {
                // Inside the owner job, like SettlementHandler: keeps one account's updates in order.
                publisher.submit(List.of(applied.change()));
            }
            return ProtoMapper.toTransferAck(result);
        }).whenComplete((ack, error) -> {
            if (error == null) {
                try {
                    caller.onNext(ack);
                    caller.onCompleted();
                } catch (RuntimeException e) {
                    log.debug("could not send transfer answer (caller gone?)", e);
                }
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                    : error;
            if (cause instanceof OwnerBusyException) {
                fail(caller, Status.UNAVAILABLE
                        .withDescription("cash owner busy; nothing applied; retry with the same transfer_id"));
            } else if (cause instanceof OwnerUnavailableException) {
                fail(caller, Status.UNAVAILABLE.withDescription("cash owner is unavailable; retry with the same transfer_id"));
            } else if (cause instanceof IllegalArgumentException) { // the Ledger refused it before changing anything
                fail(caller, Status.INVALID_ARGUMENT.withDescription(cause.getMessage()));
            } else {
                log.error("{} failed unexpectedly for account {} transfer {}", deposit ? "Deposit" : "Withdraw",
                        req.getAccountId(), req.getTransferId(), cause);
                fail(caller, Status.INTERNAL.withDescription("internal error"));
            }
        });
    }

    private static String invalidField(TransferRequest r) {
        if (r.getAccountId() == 0) {
            return "account_id is required";
        }
        if (!TRANSFER_ID.matcher(r.getTransferId()).matches()) {
            return "transfer_id must be 1-48 chars of [A-Za-z0-9_-]";
        }
        if (r.getAmountMinor() <= 0) {
            return "amount_minor must be > 0";
        }
        return null;
    }

    private static void fail(StreamObserver<?> to, Status status) {
        try {
            to.onError(status.asRuntimeException());
        } catch (RuntimeException e) {
            log.debug("could not send transfer error (caller gone?)", e);
        }
    }
}
