package com.protrader.cash_owner.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.owner.LedgerOwner.Settings;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.CancelOrderAck;
import com.protrader.proto.exchange.v1.CancelOrderRequest;
import com.protrader.proto.exchange.v1.CancelRejectReason;
import com.protrader.proto.exchange.v1.CommandBody;
import com.protrader.proto.exchange.v1.OrderStatus;
import com.protrader.proto.exchange.v1.PlaceOrderRequest;
import com.protrader.proto.exchange.v1.SubmitRequest;
import com.protrader.proto.exchange.v1.SubmitStatus;
import io.grpc.Status;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CancelOrderFlowTest {

    private static final long CASH = 500_000;
    private static final int SYMBOL = 1;
    private static final long ALICE = 1;
    private static final long BOB = 2;
    private static final long LIMIT_PRICE_MINOR = 10_000;

    private LedgerOwner owner;
    private FakeSequencer sequencer;
    private PlaceOrderFlow placeFlow;
    private CancelOrderFlow cancelFlow;

    @BeforeEach
    void setUp() throws Exception {
        start(new Settings(10_000, true, Duration.ofSeconds(5)));
    }

    private void start(Settings settings) throws Exception {
        if (owner != null) {
            owner.stop();
        }
        owner = LedgerOwner.forTests(settings, Ledger::checkInvariants);
        owner.start();
        sequencer = new FakeSequencer();
        placeFlow = new PlaceOrderFlow(owner, sequencer);
        cancelFlow = new CancelOrderFlow(owner, sequencer);
        owner.followUp(l -> {
            l.openAccount(ALICE, CASH, Map.of(SYMBOL, 10L));
            l.openAccount(BOB, CASH, Map.of());
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            owner.followUp(l -> {
                l.checkInvariants();
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            owner.stop();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** An open order (accepted by the engine as orderId) for the account: buy 10 @ ₹100. */
    private void openOrder(long accountId, String clientOrderId, long orderId) throws Exception {
        owner.followUp(l -> {
            l.place(accountId, clientOrderId, SYMBOL, Side.BUY, LIMIT_PRICE_MINOR, 10);
            l.onAccepted(new OrderKey(accountId, clientOrderId), orderId);
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    private static CancelOrderRequest cancelRequest(long accountId, long orderId) {
        return CancelOrderRequest.newBuilder().setAccountId(accountId).setSymbol(SYMBOL).setOrderId(orderId)
                .setGatewayId("gw1").build();
    }

    private FakeCancelCaller cancel(long accountId, long orderId) throws Exception {
        FakeCancelCaller caller = new FakeCancelCaller();
        cancelFlow.cancel(cancelRequest(accountId, orderId), caller);
        settle();
        return caller;
    }

    private void settle() throws Exception {
        owner.followUp(l -> null).get(5, TimeUnit.SECONDS);
        owner.followUp(l -> null).get(5, TimeUnit.SECONDS);
        Thread.sleep(30);
    }

    /** A cancel ack: client_order_id is empty. */
    private void cancelAck(long accountId, SubmitStatus status) throws Exception {
        sequencer.ack(accountId, "", status, status == SubmitStatus.SUBMIT_STATUS_ACCEPTED ? 99 : 0);
        settle();
    }

    private OrderView order(long orderId) throws Exception {
        Optional<OrderView> view = owner.request(l -> l.orderByEngineRef(new EngineRef(SYMBOL, orderId)))
                .get(5, TimeUnit.SECONDS);
        return view.orElseThrow();
    }

    private AccountView account(long accountId) throws Exception {
        return owner.request(l -> l.account(accountId).orElseThrow()).get(5, TimeUnit.SECONDS);
    }

    private void engineCancels(long seq, long orderId, long ownerId, long qty) throws Exception {
        EngineEvent event = new EngineEvent(SYMBOL, seq, EngineEventKind.CANCELLED, 0, orderId, 0, ownerId, 0, null,
                null, qty, "");
        owner.followUp(l -> l.applyEngineEvent(event)).get(5, TimeUnit.SECONDS);
    }

    private static void assertRefused(FakeCancelCaller caller, CancelRejectReason reason) {
        CancelOrderAck ack = caller.onlyAck();
        assertFalse(ack.getAccepted());
        assertEquals(reason, ack.getReason());
        assertTrue(caller.isCompleted());
    }

    // ---------------------------------------------------------------- tests

    @Test
    void givenAnOpenOrder_whenTheSequencerAcceptsTheCancel_thenTheCallerGetsAcceptedAndPendingCancel()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        AccountView before = account(ALICE);

        FakeCancelCaller caller = cancel(ALICE, 5);
        assertTrue(caller.isSilent(), "no answer before the sequencer's ack");
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);

        CancelOrderAck ack = caller.onlyAck();
        assertTrue(ack.getAccepted());
        assertEquals(OrderStatus.ORDER_STATUS_PENDING_CANCEL, ack.getOrder().getStatus());
        assertEquals(5, ack.getOrder().getOrderId());
        assertTrue(caller.isCompleted());
        assertTrue(order(5).cancelPending());
        assertEquals(before, account(ALICE), "a cancel request moves no money");
    }

    @Test
    void givenACancel_whenItIsSent_thenItGoesToTheSequencerWithAnEmptyClientOrderIdAndTheRightPayload()
            throws Exception {
        openOrder(ALICE, "a1", 5);

        cancel(ALICE, 5);

        assertEquals(1, sequencer.submitted.size());
        SubmitRequest sent = sequencer.submitted.get(0);
        assertEquals("", sent.getClientOrderId());
        assertEquals("gw1", sent.getGatewayId());
        assertEquals(SYMBOL, sent.getSymbol());
        assertEquals(ALICE, sent.getAccountId());
        CommandBody body = parse(sent);
        assertTrue(body.hasCancelOrder());
        assertEquals(ALICE, body.getCancelOrder().getRequester());
        assertEquals(5, body.getCancelOrder().getOrderId());
    }

    private static CommandBody parse(SubmitRequest sent) throws InvalidProtocolBufferException {
        return CommandBody.parseFrom(sent.getPayload());
    }

    @Test
    void givenACancelWaiting_whenTheSequencerSaysBusy_thenExchangeBusyAndTheFlagIsCleared() throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller caller = cancel(ALICE, 5);

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_BUSY);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_BUSY);
        assertFalse(order(5).cancelPending());
        assertEquals(OrderStatus.ORDER_STATUS_NEW, ownerState(5));
    }

    private OrderStatus ownerState(long orderId) throws Exception {
        return com.protrader.cash_owner.grpc.ProtoMapper.toOrderState(order(orderId)).getStatus();
    }

    @Test
    void givenACancelWaiting_whenTheSequencerSaysNotRunning_thenExchangeUnavailableAndTheFlagIsCleared()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller caller = cancel(ALICE, 5);

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_NOT_RUNNING);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_UNAVAILABLE);
        assertFalse(order(5).cancelPending());
    }

    @Test
    void givenACancelWaiting_whenTheSequencerSaysUnknownSymbol_thenOrderNotFoundAndTheFlagIsCleared()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller caller = cancel(ALICE, 5);

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_UNKNOWN_SYMBOL);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_ORDER_NOT_FOUND);
        assertFalse(order(5).cancelPending());
    }

    @Test
    void givenAnUnexpectedAckStatus_whenItArrives_thenTheCallerGetsUnavailableAndTheFlagStays() throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller caller = cancel(ALICE, 5);

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_UNSPECIFIED);

        assertEquals(Status.Code.UNAVAILABLE, caller.errorCode());
        assertTrue(order(5).cancelPending());
    }

    @Test
    void givenNoOpenStream_whenCancelling_thenExchangeUnavailableAndTheFlagIsCleared() throws Exception {
        openOrder(ALICE, "a1", 5);
        sequencer.streamOpen = false;

        FakeCancelCaller caller = cancel(ALICE, 5);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_UNAVAILABLE);
        assertFalse(order(5).cancelPending());
        assertTrue(sequencer.submitted.isEmpty());
    }

    @Test
    void givenThreeCancelsWaiting_whenThreeAcksArrive_thenEachCallerGetsItsOwnAckInOrder() throws Exception {
        openOrder(ALICE, "a1", 5);
        openOrder(ALICE, "a2", 6);
        openOrder(ALICE, "a3", 7);
        FakeCancelCaller first = cancel(ALICE, 5);
        FakeCancelCaller second = cancel(ALICE, 6);
        FakeCancelCaller third = cancel(ALICE, 7);

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_BUSY);
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);

        assertTrue(first.onlyAck().getAccepted());
        assertEquals(5, first.onlyAck().getOrder().getOrderId());
        assertRefused(second, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_BUSY);
        assertTrue(third.onlyAck().getAccepted());
        assertEquals(7, third.onlyAck().getOrder().getOrderId());
        assertTrue(order(5).cancelPending());
        assertFalse(order(6).cancelPending());
        assertTrue(order(7).cancelPending());
    }

    @Test
    void givenAPlaceAndACancelInFlight_whenTheirAcksArriveInterleaved_thenEachFlowTakesOnlyItsOwn()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCaller placeCaller = new FakeCaller();
        placeFlow.place(PlaceOrderRequest.newBuilder().setAccountId(ALICE).setClientOrderId("p1").setSymbol(SYMBOL)
                .setSide(com.protrader.proto.exchange.v1.Side.SIDE_BUY).setPriceMinor(LIMIT_PRICE_MINOR)
                .setQuantity(10).setGatewayId("gw1").build(), placeCaller);
        settle();
        FakeCancelCaller cancelCaller = cancel(ALICE, 5);
        assertEquals(2, sequencer.submitted.size());

        // the cancel's ack comes first (empty client_order_id), then the place's
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);
        assertTrue(cancelCaller.onlyAck().getAccepted());
        assertTrue(placeCaller.acks.isEmpty() && placeCaller.errors.isEmpty(), "the place is still waiting");

        sequencer.ack(ALICE, "p1", SubmitStatus.SUBMIT_STATUS_ACCEPTED, 8);
        settle();
        assertTrue(placeCaller.onlyAck().getAccepted());
        assertEquals(1, cancelCaller.acks.size(), "the cancel caller was not answered twice");
        assertEquals(OrderStage.OPEN, order(8).stage());
    }

    @Test
    void givenTwoCancelsWaiting_whenTheStreamIsLost_thenBothAreUnavailableFlagsStayAndNothingIsResent()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        openOrder(ALICE, "a2", 6);
        FakeCancelCaller first = cancel(ALICE, 5);
        FakeCancelCaller second = cancel(ALICE, 6);

        sequencer.loseStream();
        settle();

        assertEquals(Status.Code.UNAVAILABLE, first.errorCode());
        assertEquals(Status.Code.UNAVAILABLE, second.errorCode());
        assertTrue(order(5).cancelPending());
        assertTrue(order(6).cancelPending());

        sequencer.openStream();
        settle();
        assertEquals(2, sequencer.submitted.size(), "nothing is resent on reopen");

        // a late ack on the new stream has nobody to go to; nothing breaks
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);
        assertEquals(1, first.errors.size());
        assertEquals(1, second.errors.size());
    }

    @Test
    void givenTheCancelledEventWasAppliedBeforeTheAck_whenTheAckArrives_thenStillAcceptedAndTheOrderIsDone()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller caller = cancel(ALICE, 5);

        engineCancels(1, 5, ALICE, 10);
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);

        CancelOrderAck ack = caller.onlyAck();
        assertTrue(ack.getAccepted());
        assertEquals(OrderStatus.ORDER_STATUS_CANCELED, ack.getOrder().getStatus());
        assertEquals(OrderStage.DONE, order(5).stage());
        assertEquals(DoneReason.CANCELED, order(5).doneReason());
        assertFalse(order(5).cancelPending());
    }

    @Test
    void givenTwoCancelsForTheSameOrder_whenTheFirstIsBusy_thenTheFlagStaysBecauseTheSecondIsStillOnItsWay()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller first = cancel(ALICE, 5);
        FakeCancelCaller second = cancel(ALICE, 5);

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_BUSY);
        assertRefused(first, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_BUSY);
        assertTrue(order(5).cancelPending());

        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);
        assertTrue(second.onlyAck().getAccepted());
        assertTrue(order(5).cancelPending());
    }

    @Test
    void givenAnAckForAnotherAccount_whenItArrives_thenTheOldestCallerGetsUnavailableAndNothingIsGuessed()
            throws Exception {
        openOrder(ALICE, "a1", 5);
        FakeCancelCaller caller = cancel(ALICE, 5);

        cancelAck(BOB, SubmitStatus.SUBMIT_STATUS_ACCEPTED);

        assertEquals(Status.Code.UNAVAILABLE, caller.errorCode());
        assertTrue(order(5).cancelPending());
    }

    @Test
    void givenNobodyWaiting_whenACancelAckArrives_thenItIsIgnored() throws Exception {
        cancelAck(ALICE, SubmitStatus.SUBMIT_STATUS_ACCEPTED);

        assertTrue(owner.isHealthy());
    }

    @Test
    void givenAnUnknownOrder_whenCancelling_thenOrderNotFoundAndNothingIsSent() throws Exception {
        FakeCancelCaller caller = cancel(ALICE, 404);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_ORDER_NOT_FOUND);
        assertTrue(sequencer.submitted.isEmpty());
    }

    @Test
    void givenSomeoneElsesOrder_whenCancelling_thenOrderNotFoundAndTheOrderIsUntouched() throws Exception {
        openOrder(BOB, "b1", 5);

        FakeCancelCaller caller = cancel(ALICE, 5);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_ORDER_NOT_FOUND);
        assertFalse(order(5).cancelPending());
        assertTrue(sequencer.submitted.isEmpty());
    }

    @Test
    void givenAClosedOrder_whenCancelling_thenOrderClosedWithTheCurrentOrderAndNothingIsSent() throws Exception {
        openOrder(ALICE, "a1", 5);
        engineCancels(1, 5, ALICE, 10);

        FakeCancelCaller caller = cancel(ALICE, 5);

        CancelOrderAck ack = caller.onlyAck();
        assertFalse(ack.getAccepted());
        assertEquals(CancelRejectReason.CANCEL_REJECT_REASON_ORDER_CLOSED, ack.getReason());
        assertEquals(OrderStatus.ORDER_STATUS_CANCELED, ack.getOrder().getStatus());
        assertTrue(sequencer.submitted.isEmpty());
    }

    @Test
    void givenABusyOwner_whenCancelling_thenExchangeBusyNotAnError() throws Exception {
        start(new Settings(1, true, Duration.ofSeconds(5)));
        openOrder(ALICE, "a1", 5);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var blocker = owner.request(l -> {
            started.countDown();
            await(release);
            return 0;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = owner.request(l -> 1);

        FakeCancelCaller caller = new FakeCancelCaller();
        cancelFlow.cancel(cancelRequest(ALICE, 5), caller);
        Thread.sleep(30);

        assertRefused(caller, CancelRejectReason.CANCEL_REJECT_REASON_EXCHANGE_BUSY);
        release.countDown();
        blocker.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
        assertTrue(sequencer.submitted.isEmpty());
    }

    @Test
    void givenAStoppedOwner_whenCancelling_thenUnavailable() throws Exception {
        owner.stop();

        FakeCancelCaller caller = new FakeCancelCaller();
        cancelFlow.cancel(cancelRequest(ALICE, 5), caller);
        Thread.sleep(30);

        assertEquals(Status.Code.UNAVAILABLE, caller.errorCode());
        start(new Settings(10_000, true, Duration.ofSeconds(5))); // a running owner for tearDown
    }

    @Test
    void givenAnIncompleteRequest_whenCancelling_thenInvalidArgument() throws Exception {
        assertInvalid(CancelOrderRequest.newBuilder().setOrderId(5).setGatewayId("gw1").build());
        assertInvalid(CancelOrderRequest.newBuilder().setAccountId(ALICE).setGatewayId("gw1").build());
        assertInvalid(CancelOrderRequest.newBuilder().setAccountId(ALICE).setOrderId(5).build());
        assertTrue(sequencer.submitted.isEmpty());
    }

    private void assertInvalid(CancelOrderRequest request) {
        FakeCancelCaller caller = new FakeCancelCaller();
        cancelFlow.cancel(request, caller);
        assertEquals(Status.Code.INVALID_ARGUMENT, caller.errorCode());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
