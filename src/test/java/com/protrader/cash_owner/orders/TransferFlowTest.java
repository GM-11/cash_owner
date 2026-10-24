package com.protrader.cash_owner.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.owner.LedgerOwner.Settings;
import com.protrader.cash_owner.services.AccountUpdatePublisher;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.TransferAck;
import com.protrader.proto.exchange.v1.TransferRequest;
import com.protrader.proto.exchange.v1.TransferStatus;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

class TransferFlowTest {

    private static final long ALICE = 1;

    private static final class Caller implements StreamObserver<TransferAck> {
        final List<TransferAck> acks = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(TransferAck ack) {
            acks.add(ack);
        }

        @Override
        public void onError(Throwable t) {
            errors.add(t);
        }

        @Override
        public void onCompleted() {
        }

        TransferAck ack() {
            assertEquals(1, acks.size(), "acks " + acks + " errors " + errors);
            return acks.get(0);
        }

        Status.Code error() {
            assertEquals(1, errors.size(), "acks " + acks + " errors " + errors);
            return Status.fromThrowable(errors.get(0)).getCode();
        }
    }

    private final List<AccountUpdate> sent = new CopyOnWriteArrayList<>();
    private LedgerOwner owner;
    private AccountUpdatePublisher publisher;
    private TransferFlow flow;

    @BeforeEach
    void setUp() throws Exception {
        start(new Settings(10_000, true, Duration.ofSeconds(5)));
    }

    private void start(Settings settings) throws Exception {
        owner = LedgerOwner.forTests(settings, Ledger::checkInvariants);
        owner.start();
        publisher = new AccountUpdatePublisher(sent::add);
        publisher.start();
        flow = new TransferFlow(owner, publisher);
        owner.followUp(l -> {
            l.openAccount(ALICE, 100_000, Map.of());
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        owner.stop();
        publisher.stop();
    }

    private static TransferRequest request(long account, String id, long amount) {
        return TransferRequest.newBuilder().setAccountId(account).setTransferId(id).setAmountMinor(amount).build();
    }

    private Caller call(boolean deposit, TransferRequest req) throws Exception {
        Caller caller = new Caller();
        flow.transfer(deposit, req, caller);
        long end = System.currentTimeMillis() + 5_000;
        while (caller.acks.isEmpty() && caller.errors.isEmpty() && System.currentTimeMillis() < end)
            Thread.sleep(2);
        return caller;
    }

    private List<AccountUpdate> sentAfterDrain() {
        publisher.stop(); // sends everything already queued
        return sent;
    }

    @Test
    void givenADeposit_whenApplied_thenAppliedAckAndExactlyOneTransferUpdateIsPublished() throws Exception {
        TransferAck ack = call(true, request(ALICE, "t1", 5_000)).ack();

        assertEquals(TransferStatus.TRANSFER_STATUS_APPLIED, ack.getStatus());
        assertEquals(105_000, ack.getBalances().getCashFreeMinor());
        assertEquals(1, ack.getAccountSeq());
        List<AccountUpdate> updates = sentAfterDrain();
        assertEquals(1, updates.size());
        assertEquals(AccountUpdate.CauseCase.TRANSFER, updates.get(0).getCauseCase());
        assertEquals("t1", updates.get(0).getTransfer().getTransferId());
        assertTrue(updates.get(0).getTs() > 0);
        assertEquals(1, updates.get(0).getAccountSeq());
    }

    @Test
    void givenAnAppliedTransfer_whenRepeated_thenDuplicateWithCurrentBalancesAndNothingMoreIsPublished()
            throws Exception {
        call(true, request(ALICE, "t1", 5_000)).ack();

        TransferAck ack = call(true, request(ALICE, "t1", 5_000)).ack();

        assertEquals(TransferStatus.TRANSFER_STATUS_DUPLICATE, ack.getStatus());
        assertEquals(105_000, ack.getBalances().getCashFreeMinor());
        assertEquals(1, ack.getAccountSeq());
        assertEquals(1, sentAfterDrain().size());
    }

    @Test
    void givenTooLargeAWithdraw_whenRequested_thenInsufficientCashWithShortfallAndNothingIsPublished()
            throws Exception {
        owner.followUp(l -> l.place(ALICE, "b1", 1, Side.BUY, 100, 400)).get(5, TimeUnit.SECONDS); // locks 40,000

        TransferAck ack = call(false, request(ALICE, "w1", 80_000)).ack();

        assertEquals(TransferStatus.TRANSFER_STATUS_INSUFFICIENT_CASH, ack.getStatus());
        assertEquals(20_000, ack.getShortfallMinor());
        assertEquals(60_000, ack.getBalances().getCashFreeMinor());
        assertEquals(40_000, ack.getBalances().getCashLockedMinor());
        assertEquals(0, ack.getAccountSeq());
        assertEquals(0, sentAfterDrain().size());
    }

    @Test
    void givenAMissingAccount_whenWithdrawn_thenUnknownAccountAndWhenDepositedThenApplied() throws Exception {
        assertEquals(TransferStatus.TRANSFER_STATUS_UNKNOWN_ACCOUNT,
                call(false, request(9, "w1", 10)).ack().getStatus());
        TransferAck deposit = call(true, request(9, "d1", 10)).ack();
        assertEquals(TransferStatus.TRANSFER_STATUS_APPLIED, deposit.getStatus());
        assertEquals(1, deposit.getAccountSeq());
        assertEquals(1, sentAfterDrain().size());
    }

    @Test
    void givenAWithdrawWithinFreeCash_whenRequested_thenAppliedWithBalancesAfter() throws Exception {
        TransferAck ack = call(false, request(ALICE, "w1", 30_000)).ack();

        assertEquals(TransferStatus.TRANSFER_STATUS_APPLIED, ack.getStatus());
        assertEquals(70_000, ack.getBalances().getCashFreeMinor());
        assertTrue(!sentAfterDrain().get(0).getTransfer().getDeposit());
    }

    @Test
    void givenAnInvalidRequest_whenSent_thenInvalidArgumentAndNothingHappens() throws Exception {
        assertEquals(Status.Code.INVALID_ARGUMENT, call(true, request(0, "t1", 5)).error());
        assertEquals(Status.Code.INVALID_ARGUMENT, call(true, request(ALICE, "", 5)).error());
        assertEquals(Status.Code.INVALID_ARGUMENT, call(true, request(ALICE, "bad id", 5)).error());
        assertEquals(Status.Code.INVALID_ARGUMENT, call(true, request(ALICE, "x".repeat(49), 5)).error());
        assertEquals(Status.Code.INVALID_ARGUMENT, call(false, request(ALICE, "t1", 0)).error());
        assertEquals(Status.Code.INVALID_ARGUMENT, call(false, request(ALICE, "t1", -3)).error());
        assertEquals(0, sentAfterDrain().size());
    }

    @Test
    void givenADepositThatOverflows_whenRequested_thenInvalidArgument() throws Exception {
        assertEquals(Status.Code.INVALID_ARGUMENT, call(true, request(ALICE, "t1", Long.MAX_VALUE)).error());
    }

    @Test
    void givenTheOwnerIsBusy_whenRequested_thenUnavailable() throws Exception {
        owner.stop();
        publisher.stop();
        sent.clear();
        start(new Settings(1, true, Duration.ofSeconds(5)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> running = owner.request(l -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CompletableFuture<Integer> waiting = owner.request(l -> 1); // fills the single waiting slot

        Caller caller = call(true, request(ALICE, "t1", 5));

        assertEquals(Status.Code.UNAVAILABLE, caller.error());
        assertTrue(Status.fromThrowable(caller.errors.get(0)).getDescription().contains("nothing applied"));
        release.countDown();
        running.get(5, TimeUnit.SECONDS);
        waiting.get(5, TimeUnit.SECONDS);
        assertEquals(0, sentAfterDrain().size());
    }

    @Test
    void givenTheOwnerIsStopped_whenRequested_thenUnavailable() throws Exception {
        owner.stop();

        assertEquals(Status.Code.UNAVAILABLE, call(true, request(ALICE, "t1", 5)).error());
    }
}
