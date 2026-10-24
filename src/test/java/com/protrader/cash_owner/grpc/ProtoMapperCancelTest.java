package com.protrader.cash_owner.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.proto.exchange.v1.OrderEvent;
import com.protrader.proto.exchange.v1.OrderStatus;

/** Cancel in the proto mapping. */
class ProtoMapperCancelTest {

    private static OrderView order(OrderStage stage, long filledQty, DoneReason doneReason, boolean cancelPending) {
        return new OrderView(new OrderKey(21, "b1"), 1, Side.BUY, 10_000, 10, 10 - filledQty, filledQty,
                filledQty * 10_000, 2, stage, doneReason, cancelPending);
    }

    private static OrderStatus status(OrderView view) {
        return ProtoMapper.toOrderState(view).getStatus();
    }

    @Test
    void givenAnOpenOrderWithACancelPending_thenItsStatusIsPendingCancel() {
        assertEquals(OrderStatus.ORDER_STATUS_PENDING_CANCEL, status(order(OrderStage.OPEN, 0, null, true)));
    }

    @Test
    void givenAPartiallyFilledOpenOrderWithACancelPending_thenItsStatusIsStillPendingCancel() {
        assertEquals(OrderStatus.ORDER_STATUS_PENDING_CANCEL, status(order(OrderStage.OPEN, 4, null, true)));
    }

    @Test
    void givenAnOpenOrderWithoutACancelPending_thenItsStatusIsNewOrPartiallyFilled() {
        assertEquals(OrderStatus.ORDER_STATUS_NEW, status(order(OrderStage.OPEN, 0, null, false)));
        assertEquals(OrderStatus.ORDER_STATUS_PARTIALLY_FILLED, status(order(OrderStage.OPEN, 4, null, false)));
    }

    @Test
    void givenACancelRejectedChange_thenTheEventKindIsCancelRejected() {
        assertEquals(OrderEvent.Kind.KIND_CANCEL_REJECTED, ProtoMapper.toProto(AccountChangeKind.CANCEL_REJECTED));
    }
}
