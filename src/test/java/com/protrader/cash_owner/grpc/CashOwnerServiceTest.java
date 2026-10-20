package com.protrader.cash_owner.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protrader.cash_owner.orders.PlaceOrderFlow;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.sequencer.SequencerPort;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.CashRejectReason;
import com.protrader.proto.exchange.v1.OrderType;
import com.protrader.proto.exchange.v1.PlaceOrderAck;
import com.protrader.proto.exchange.v1.PlaceOrderRequest;
import com.protrader.proto.exchange.v1.Side;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import com.protrader.proto.exchange.v1.TimeInForce;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CashOwnerServiceTest {

    /** Records what reaches the flow; never touches the owner. */
    private static final class RecordingFlow extends PlaceOrderFlow {
        final List<PlaceOrderRequest> placed = new ArrayList<>();
        StreamObserver<PlaceOrderAck> lastCaller;

        RecordingFlow() {
            super(LedgerOwner.forTests(new LedgerOwner.Settings(1, false, Duration.ofSeconds(1)), l -> {
            }), new SequencerPort() {
                @Override
                public boolean submit(SubmitRequest request) {
                    return false;
                }

                @Override
                public void onAck(Consumer<SubmitAck> handler) {
                }

                @Override
                public void onStreamOpened(Runnable handler) {
                }

                @Override
                public void onStreamLost(Runnable handler) {
                }
            });
        }

        @Override
        public void place(PlaceOrderRequest request, StreamObserver<PlaceOrderAck> caller) {
            placed.add(request);
            lastCaller = caller;
        }
    }

    private static final class Out implements StreamObserver<PlaceOrderAck> {
        final List<PlaceOrderAck> acks = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        boolean completed;

        @Override
        public void onNext(PlaceOrderAck ack) {
            acks.add(ack);
        }

        @Override
        public void onError(Throwable t) {
            errors.add(t);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    private RecordingFlow flow;
    private CashOwnerService service;

    @BeforeEach
    void setUp() {
        flow = new RecordingFlow();
        service = new CashOwnerService(null, flow, null); // bus and owner are not used by PlaceOrder
    }

    private static PlaceOrderRequest.Builder valid() {
        return PlaceOrderRequest.newBuilder().setAccountId(1).setClientOrderId("order-1_A").setSymbol(1)
                .setSide(Side.SIDE_BUY).setPriceMinor(10_000).setQuantity(10).setGatewayId("gw1");
    }

    private void assertInvalid(UnaryOperator<PlaceOrderRequest.Builder> breakIt) {
        Out out = new Out();
        service.placeOrder(breakIt.apply(valid()).build(), out);
        assertEquals(1, out.errors.size());
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(out.errors.get(0)).getCode());
        assertTrue(out.acks.isEmpty());
        assertTrue(flow.placed.isEmpty());
    }

    private void assertNotSupported(UnaryOperator<PlaceOrderRequest.Builder> change) {
        Out out = new Out();
        service.placeOrder(change.apply(valid()).build(), out);
        assertTrue(out.errors.isEmpty());
        assertEquals(1, out.acks.size());
        assertFalse(out.acks.get(0).getAccepted());
        assertEquals(CashRejectReason.CASH_REJECT_REASON_NOT_SUPPORTED, out.acks.get(0).getReason());
        assertTrue(out.completed);
        assertTrue(flow.placed.isEmpty());
    }

    @Test
    void zeroAccountIsInvalid() {
        assertInvalid(b -> b.setAccountId(0));
    }

    @Test
    void badClientOrderIdIsInvalid() {
        assertInvalid(b -> b.setClientOrderId(""));
        assertInvalid(b -> b.setClientOrderId("a".repeat(49)));
        assertInvalid(b -> b.setClientOrderId("has space"));
        assertInvalid(b -> b.setClientOrderId("bad/char"));
    }

    @Test
    void zeroSymbolIsInvalid() {
        assertInvalid(b -> b.setSymbol(0));
    }

    @Test
    void sideOtherThanBuyOrSellIsInvalid() {
        assertInvalid(b -> b.setSide(Side.SIDE_UNSPECIFIED));
    }

    @Test
    void nonPositivePriceOrQuantityIsInvalid() {
        assertInvalid(b -> b.setPriceMinor(0));
        assertInvalid(b -> b.setPriceMinor(-5));
        assertInvalid(b -> b.setQuantity(0));
        assertInvalid(b -> b.setQuantity(-1));
    }

    @Test
    void emptyGatewayIdIsInvalid() {
        assertInvalid(b -> b.setGatewayId(""));
    }

    @Test
    void marketOrderIsNotSupported() {
        assertNotSupported(b -> b.setType(OrderType.ORDER_TYPE_MARKET));
    }

    @Test
    void iocAndDayAreNotSupported() {
        assertNotSupported(b -> b.setTimeInForce(TimeInForce.TIME_IN_FORCE_IOC));
        assertNotSupported(b -> b.setTimeInForce(TimeInForce.TIME_IN_FORCE_DAY));
    }

    @Test
    void stopPriceIsNotSupported() {
        assertNotSupported(b -> b.setStopPriceMinor(9_000));
    }

    @Test
    void validRequestReachesTheFlow() {
        Out out = new Out();
        PlaceOrderRequest request = valid().setType(OrderType.ORDER_TYPE_LIMIT)
                .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC).build();

        service.placeOrder(request, out);

        assertEquals(List.of(request), flow.placed);
        assertSame(out, flow.lastCaller);
        assertTrue(out.errors.isEmpty());
        assertTrue(out.acks.isEmpty());
    }
}
