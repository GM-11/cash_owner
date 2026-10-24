package com.protrader.cash_owner.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.cash_owner.services.Ledger;
import com.protrader.proto.exchange.v1.AccountSnapshot;
import com.protrader.proto.exchange.v1.GetAccountRequest;
import com.protrader.proto.exchange.v1.OrderStatus;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetAccountTest {

    private static final int MOOG = 1;

    private LedgerOwner owner;
    private CashOwnerService service;

    @BeforeEach
    void setUp() {
        owner = LedgerOwner.forTests(new LedgerOwner.Settings(100, true, Duration.ofSeconds(5)),
                Ledger::checkInvariants);
        owner.start();
        service = new CashOwnerService(null, null, null, null, owner);
    }

    @AfterEach
    void tearDown() {
        owner.stop();
    }

    private AccountSnapshot call(long accountId) throws Exception {
        CompletableFuture<AccountSnapshot> result = new CompletableFuture<>();
        service.getAccount(GetAccountRequest.newBuilder().setAccountId(accountId).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(AccountSnapshot value) {
                        result.complete(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        result.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    @Test
    void showsBalancesPositionsAndOpenOrdersAfterSettlement() throws Exception {
        // account 21: ₹1,00,000; buys 10 @ ₹101 (fills fully) and 10 @ ₹100 (rests)
        owner.followUp(l -> {
            l.openAccount(21, 10_000_000, Map.of(MOOG, 500L));
            l.place(21, "b1", MOOG, Side.BUY, 10_000, 10);
            l.place(21, "b2", MOOG, Side.BUY, 10_100, 10);
            l.applyEngineEvent(new EngineEvent(MOOG, 1, EngineEventKind.ACCEPTED, 0, 1, 0, 21, 0, Side.BUY,
                    10_000L, 10, "b1"));
            l.applyEngineEvent(new EngineEvent(MOOG, 2, EngineEventKind.ACCEPTED, 0, 3, 0, 21, 0, Side.BUY,
                    10_100L, 10, "b2"));
            l.applyEngineEvent(new EngineEvent(MOOG, 3, EngineEventKind.TRADE, 0, 50, 3, 21, 99, Side.SELL,
                    10_100L, 10, ""));
            return null;
        }).get();

        AccountSnapshot s = call(21);

        assertTrue(s.getFound());
        assertEquals(21, s.getAccountId());
        assertEquals(10_000_000 - 100_000 - 101_000, s.getBalances().getCashFreeMinor());
        assertEquals(100_000, s.getBalances().getCashLockedMinor()); // b1 still resting
        assertEquals(1, s.getPositionsCount());
        assertEquals(MOOG, s.getPositions(0).getSymbol());
        assertEquals(510, s.getPositions(0).getFreeQty());
        assertEquals(0, s.getPositions(0).getLockedQty());
        assertEquals(1, s.getOpenOrdersCount()); // b2 is FILLED, so only b1 is open
        assertEquals("b1", s.getOpenOrders(0).getClientOrderId());
        assertEquals(OrderStatus.ORDER_STATUS_NEW, s.getOpenOrders(0).getStatus());
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        AccountSnapshot s = call(404);
        assertFalse(s.getFound());
        assertEquals(404, s.getAccountId());
    }

    @Test
    void missingAccountIdIsInvalid() {
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> call(0));
        Throwable cause = e.getCause();
        assertTrue(cause instanceof StatusRuntimeException, "got " + cause);
        assertEquals(Status.Code.INVALID_ARGUMENT, ((StatusRuntimeException) cause).getStatus().getCode());
    }
}
