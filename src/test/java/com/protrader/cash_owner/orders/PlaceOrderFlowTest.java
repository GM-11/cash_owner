package com.protrader.cash_owner.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.owner.LedgerOwner.Settings;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.CashRejectReason;
import com.protrader.proto.exchange.v1.CommandBody;
import com.protrader.proto.exchange.v1.Order;
import com.protrader.proto.exchange.v1.OrderStatus;
import com.protrader.proto.exchange.v1.OrderType;
import com.protrader.proto.exchange.v1.PlaceOrderAck;
import com.protrader.proto.exchange.v1.PlaceOrderRequest;
import com.protrader.proto.exchange.v1.Side;
import com.protrader.proto.exchange.v1.SubmitStatus;
import com.protrader.proto.exchange.v1.TimeInForce;
import io.grpc.Status;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceOrderFlowTest {

    private static final long CASH = 500_000; // Rs 5,000
    private static final int SYMBOL = 1;
    private static final OrderKey KEY = new OrderKey(1, "c1");

    private LedgerOwner owner;
    private FakeSequencer sequencer;
    private PlaceOrderFlow flow;

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
        flow = new PlaceOrderFlow(owner, sequencer);
        owner.followUp(l -> {
            l.openAccount(1, CASH, Map.of(SYMBOL, 10L));
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

    private static PlaceOrderRequest req(String clientOrderId, Side side, long price, long qty) {
        return PlaceOrderRequest.newBuilder().setAccountId(1).setClientOrderId(clientOrderId).setSymbol(SYMBOL)
                .setSide(side).setPriceMinor(price).setQuantity(qty).setGatewayId("gw1").build();
    }

    private static PlaceOrderRequest buy(String clientOrderId, long qty) {
        return req(clientOrderId, Side.SIDE_BUY, 10_000, qty);
    }

    private FakeCaller place(PlaceOrderRequest request) {
        FakeCaller caller = new FakeCaller();
        flow.place(request, caller);
        return caller;
    }

    /**
     * Places and waits until the order has reached the sequencer (or been
     * answered).
     */
    private FakeCaller placeAndSettle(PlaceOrderRequest request) throws Exception {
        FakeCaller caller = place(request);
        settle();
        return caller;
    }

    /**
     * Waits until the owner has run everything queued, then lets pending replies go
     * out.
     */
    private void settle() throws Exception {
        owner.followUp(l -> null).get(5, TimeUnit.SECONDS);
        owner.followUp(l -> null).get(5, TimeUnit.SECONDS);
        Thread.sleep(30);
    }

    private void ack(String clientOrderId, SubmitStatus status, long seq) throws Exception {
        sequencer.ack(1, clientOrderId, status, seq);
        settle();
    }

    private Optional<OrderView> order(OrderKey key) throws Exception {
        return owner.request(l -> l.order(key)).get(5, TimeUnit.SECONDS);
    }

    private AccountView account() throws Exception {
        return owner.request(l -> l.account(1).orElseThrow()).get(5, TimeUnit.SECONDS);
    }

    private void assertBalances(long free, long locked) throws Exception {
        AccountView a = account();
        assertEquals(free, a.freeCashMinor(), "free cash");
        assertEquals(locked, a.lockedCashMinor(), "locked cash");
    }

    private void assertRefused(FakeCaller caller, CashRejectReason reason) {
        PlaceOrderAck ack = caller.onlyAck();
        assertFalse(ack.getAccepted());
        assertEquals(reason, ack.getReason());
        assertTrue(caller.isCompleted());
    }

    // ---------------------------------------------------------------- tests

    // 1
    @Test
    void acceptedOrderIsSubmittedAnsweredAndOpened() throws Exception {
        FakeCaller caller = placeAndSettle(buy("c1", 10));

        assertEquals(1, sequencer.submitted.size());
        var submit = sequencer.submitted.get(0);
        assertEquals("gw1", submit.getGatewayId());
        assertEquals("c1", submit.getClientOrderId());
        assertEquals(SYMBOL, submit.getSymbol());
        assertEquals(1, submit.getAccountId());
        Order order = CommandBody.parseFrom(submit.getPayload()).getNewOrder().getOrder();
        assertEquals(0, order.getId());
        assertEquals(1, order.getOwnerId());
        assertEquals(Side.SIDE_BUY, order.getSide());
        assertEquals(OrderType.ORDER_TYPE_LIMIT, order.getType());
        assertTrue(order.hasPrice());
        assertEquals(10_000, order.getPrice());
        assertEquals(10, order.getQuantity());
        assertEquals(TimeInForce.TIME_IN_FORCE_GTC, order.getTimeInForce());
        assertTrue(caller.acks.isEmpty(), "no answer before the sequencer's ack");

        ack("c1", SubmitStatus.SUBMIT_STATUS_ACCEPTED, 812);

        assertTrue(caller.onlyAck().getAccepted());
        assertTrue(caller.isCompleted());
        OrderView view = order(KEY).orElseThrow();
        assertEquals(OrderStage.OPEN, view.stage());
        assertEquals(812, view.orderId());
        assertBalances(CASH - 100_000, 100_000);
    }

    // 2
    @Test
    void insufficientCashIsRefusedWithShortfallAndNothingSubmitted() throws Exception {
        FakeCaller caller = placeAndSettle(buy("c1", 60));

        assertRefused(caller, CashRejectReason.CASH_REJECT_REASON_INSUFFICIENT_CASH);
        assertEquals(100_000, caller.onlyAck().getShortfallMinor());
        assertTrue(sequencer.submitted.isEmpty());
        assertBalances(CASH, 0);
    }

    // 3
    @Test
    void insufficientHoldingsIsRefusedAndNothingSubmitted() throws Exception {
        FakeCaller caller = placeAndSettle(req("c1", Side.SIDE_SELL, 10_000, 20));

        assertRefused(caller, CashRejectReason.CASH_REJECT_REASON_INSUFFICIENT_HOLDINGS);
        assertEquals(0, caller.onlyAck().getShortfallMinor());
        assertTrue(sequencer.submitted.isEmpty());
    }

    // 4
    @Test
    void busyUnlocksAndTheSameRequestCanBeRetried() throws Exception {
        FakeCaller first = placeAndSettle(buy("c1", 10));
        ack("c1", SubmitStatus.SUBMIT_STATUS_BUSY, 0);

        assertRefused(first, CashRejectReason.CASH_REJECT_REASON_EXCHANGE_BUSY);
        assertBalances(CASH, 0);

        FakeCaller second = placeAndSettle(buy("c1", 10));

        assertEquals(2, sequencer.submitted.size());
        assertTrue(second.acks.isEmpty(), "a retry is a fresh order, not a duplicate");
        assertBalances(CASH - 100_000, 100_000);
    }

    // 5
    @Test
    void unknownSymbolAndNotRunningUnlockAndForgetTheOrder() throws Exception {
        FakeCaller a = placeAndSettle(buy("c1", 10));
        ack("c1", SubmitStatus.SUBMIT_STATUS_UNKNOWN_SYMBOL, 0);
        assertRefused(a, CashRejectReason.CASH_REJECT_REASON_UNKNOWN_SYMBOL);
        assertBalances(CASH, 0);
        assertTrue(order(KEY).isEmpty());

        FakeCaller b = placeAndSettle(buy("c2", 10));
        ack("c2", SubmitStatus.SUBMIT_STATUS_NOT_RUNNING, 0);
        assertRefused(b, CashRejectReason.CASH_REJECT_REASON_EXCHANGE_UNAVAILABLE);
        assertBalances(CASH, 0);
        assertTrue(order(new OrderKey(1, "c2")).isEmpty());
    }

    // 6
    @Test
    void noOpenStreamRefusesAsUnavailableAndUnlocks() throws Exception {
        sequencer.streamOpen = false;

        FakeCaller caller = placeAndSettle(buy("c1", 10));

        assertRefused(caller, CashRejectReason.CASH_REJECT_REASON_EXCHANGE_UNAVAILABLE);
        assertBalances(CASH, 0);
        assertTrue(order(KEY).isEmpty());
    }

    // 7
    @Test
    void lostStreamMeansUnknownOutcomeThenResendAndAccept() throws Exception {
        FakeCaller caller = placeAndSettle(buy("c1", 10));

        sequencer.loseStream();
        settle();

        assertEquals(Status.Code.UNAVAILABLE, caller.errorCode());
        assertEquals(OrderStage.IN_DOUBT, order(KEY).orElseThrow().stage());
        assertBalances(CASH - 100_000, 100_000);

        sequencer.openStream();
        settle();

        assertEquals(2, sequencer.submitted.size());
        assertEquals(sequencer.submitted.get(0), sequencer.submitted.get(1));

        ack("c1", SubmitStatus.SUBMIT_STATUS_ACCEPTED, 812);

        OrderView view = order(KEY).orElseThrow();
        assertEquals(OrderStage.OPEN, view.stage());
        assertEquals(812, view.orderId());
        assertEquals(1, caller.errors.size());
        assertTrue(caller.acks.isEmpty());
        assertFalse(caller.isCompleted());
    }

    // 8
    @Test
    void notRunningAfterAResendKeepsTheOrderInDoubt() throws Exception {
        FakeCaller caller = placeAndSettle(buy("c1", 10));
        sequencer.loseStream();
        settle();
        sequencer.openStream();
        settle();
        assertEquals(2, sequencer.submitted.size());

        ack("c1", SubmitStatus.SUBMIT_STATUS_NOT_RUNNING, 0);

        assertEquals(OrderStage.IN_DOUBT, order(KEY).orElseThrow().stage());
        assertBalances(CASH - 100_000, 100_000);

        sequencer.openStream();
        settle();

        assertEquals(3, sequencer.submitted.size());
        assertEquals(1, caller.errors.size());
    }

    // 9
    @Test
    void busyAfterAResendUnlocksAndForgets() throws Exception {
        FakeCaller caller = placeAndSettle(buy("c1", 10));
        sequencer.loseStream();
        settle();
        sequencer.openStream();
        settle();

        ack("c1", SubmitStatus.SUBMIT_STATUS_BUSY, 0);

        assertBalances(CASH, 0);
        assertTrue(order(KEY).isEmpty());
        assertEquals(1, caller.errors.size());
        assertTrue(caller.acks.isEmpty());
    }

    // 10
    @Test
    void duplicateWhileStillSentIsAnsweredWithPendingNewAndNotResubmitted() throws Exception {
        FakeCaller first = placeAndSettle(buy("c1", 10));

        FakeCaller second = placeAndSettle(buy("c1", 10));

        PlaceOrderAck ack = second.onlyAck();
        assertFalse(ack.getAccepted());
        assertTrue(ack.getDuplicate());
        assertEquals(0, ack.getOrder().getOrderId());
        assertEquals(OrderStatus.ORDER_STATUS_PENDING_NEW, ack.getOrder().getStatus());
        assertEquals(1, sequencer.submitted.size());
        assertTrue(first.acks.isEmpty());
        assertBalances(CASH - 100_000, 100_000);
    }

    // 11
    @Test
    void duplicateAfterAcceptShowsTheOpenOrder() throws Exception {
        placeAndSettle(buy("c1", 10));
        ack("c1", SubmitStatus.SUBMIT_STATUS_ACCEPTED, 812);

        FakeCaller again = placeAndSettle(buy("c1", 10));

        PlaceOrderAck ack = again.onlyAck();
        assertTrue(ack.getDuplicate());
        assertEquals(812, ack.getOrder().getOrderId());
        assertEquals(OrderStatus.ORDER_STATUS_NEW, ack.getOrder().getStatus());
        assertEquals(1, sequencer.submitted.size());
    }

    // 12
    @Test
    void ackForAnUnknownOrderChangesNothing() throws Exception {
        ack("never-sent", SubmitStatus.SUBMIT_STATUS_ACCEPTED, 5);

        assertTrue(owner.isHealthy());
        assertBalances(CASH, 0);
    }

    // 13
    @Test
    void resendThatFindsTheStreamDownStaysInDoubtAndRetriesOnTheNextOpen() throws Exception {
        placeAndSettle(buy("c1", 10));
        sequencer.loseStream();
        settle();

        sequencer.fireOpenedWhileClosed();
        settle();

        assertTrue(owner.isHealthy());
        assertEquals(1, sequencer.submitted.size());
        assertEquals(OrderStage.IN_DOUBT, order(KEY).orElseThrow().stage());

        sequencer.openStream();
        settle();

        assertEquals(2, sequencer.submitted.size());
    }

    // 14
    @Test
    void unrecognisedAckStatusMeansNoAnswerAndKeepsTheLock() throws Exception {
        FakeCaller caller = placeAndSettle(buy("c1", 10));

        ack("c1", SubmitStatus.SUBMIT_STATUS_UNSPECIFIED, 0);

        assertEquals(Status.Code.UNAVAILABLE, caller.errorCode());
        assertEquals(OrderStage.IN_DOUBT, order(KEY).orElseThrow().stage());
        assertBalances(CASH - 100_000, 100_000);
    }

    // 15
    @Test
    void whenTheOwnerIsBusyThePlaceIsAnsweredExchangeBusyNotAnError() throws Exception {
        start(new Settings(1, true, Duration.ofSeconds(5)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var blocker = owner.request(l -> {
            started.countDown();
            await(release);
            return 0;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = owner.request(l -> 1);

        FakeCaller caller = place(buy("c1", 10));

        assertRefused(caller, CashRejectReason.CASH_REJECT_REASON_EXCHANGE_BUSY);
        assertTrue(caller.errors.isEmpty());
        release.countDown();
        blocker.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
        assertTrue(sequencer.submitted.isEmpty());
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
