package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.HoldingView;
import com.protrader.cash_owner.services.Ledger;

/**
 * Step 6: average entry price and realized P&L (average cost method). A holding stores the exact total cost of
 * the shares it holds; a buy adds trade price × qty, a sell removes cost × sold / held (rounded down) and
 * realizes proceeds − removed cost. Money is in paise: ₹1 = 100.
 */
class LedgerPnlTest {

    private static final int MOOG = 1;
    private static final int BANANA = 2;
    private static final long RICH = 1_000_000_000_000L; // ₹1,000 crore, never the limit in these tests

    private final Ledger ledger = new Ledger();
    private long nextOrderId = 1;

    private EngineRef open(long accountId, int symbol, Side side, long limitMinor, long qty) {
        long orderId = nextOrderId++;
        String clientOrderId = "o" + orderId;
        PlaceResult result = ledger.place(accountId, clientOrderId, symbol, side, limitMinor, qty);
        assertTrue(result instanceof PlaceResult.Locked, "place failed: " + result);
        ledger.onAccepted(new OrderKey(accountId, clientOrderId), orderId);
        return new EngineRef(symbol, orderId);
    }

    /** Places and fully fills an order at {@code priceMinor} (the limit equals the trade price). */
    private void trade(long accountId, int symbol, Side side, long priceMinor, long qty) {
        ledger.onFill(open(accountId, symbol, side, priceMinor, qty), priceMinor, qty);
    }

    private AccountView account(long id) {
        return ledger.account(id).orElseThrow();
    }

    private HoldingView holding(long accountId, int symbol) {
        return account(accountId).holdings().get(symbol);
    }

    // ------------------------------------------------------------------ buys

    @Test
    void buyAddsTheTradePriceNotTheLimitToCost() {
        // Given buy 10 @ limit ₹104
        ledger.openAccount(21, RICH, Map.of());
        EngineRef ref = open(21, MOOG, Side.BUY, 10_400, 10);

        // When it fills 10 @ ₹103 (₹1 per share price improvement goes back to free cash)
        ledger.onFill(ref, 10_300, 10);

        // Then he paid ₹1,030, so cost is ₹1,030 and the average ₹103; nothing realized
        HoldingView h = holding(21, MOOG);
        assertEquals(103_000, h.costMinor());
        assertEquals(10_300, h.avgPriceMinor());
        assertEquals(0, h.realizedPnlMinor());
        ledger.checkInvariants();
    }

    @Test
    void theFourStepTable() {
        // Given an account with cash and no shares
        ledger.openAccount(21, RICH, Map.of());

        // buy 10 @ ₹100, buy 10 @ ₹110 → 20 shares, cost ₹2,100, average ₹105
        trade(21, MOOG, Side.BUY, 10_000, 10);
        trade(21, MOOG, Side.BUY, 11_000, 10);
        assertEquals(210_000, holding(21, MOOG).costMinor());
        assertEquals(10_500, holding(21, MOOG).avgPriceMinor());
        assertEquals(0, account(21).realizedPnlMinor());

        // sell 5 @ ₹120 → realized (120 − 105) × 5 = ₹75; average unchanged at ₹105
        trade(21, MOOG, Side.SELL, 12_000, 5);
        assertEquals(7_500, holding(21, MOOG).realizedPnlMinor());
        assertEquals(7_500, account(21).realizedPnlMinor());
        assertEquals(157_500, holding(21, MOOG).costMinor());
        assertEquals(10_500, holding(21, MOOG).avgPriceMinor());
        ledger.checkInvariants();

        // sell 15 @ ₹100 → realized 75 + (100 − 105) × 15 = ₹0; no shares, no cost
        trade(21, MOOG, Side.SELL, 10_000, 15);
        HoldingView h = holding(21, MOOG);
        assertEquals(0, h.qty());
        assertEquals(0, h.costMinor());
        assertEquals(0, h.avgPriceMinor());
        assertEquals(0, h.realizedPnlMinor());
        assertEquals(0, account(21).realizedPnlMinor());
        ledger.checkInvariants();
    }

    // ------------------------------------------------------------------ rounding

    @Test
    void roundingMovesAPaisaBetweenSellsButNeverLosesIt() {
        // Given 1 @ ₹100.00 and 2 @ ₹100.01 → 3 shares, cost 30002 (true average 10000.67)
        ledger.openAccount(21, RICH, Map.of());
        trade(21, MOOG, Side.BUY, 10_000, 1);
        trade(21, MOOG, Side.BUY, 10_001, 2);
        assertEquals(30_002, holding(21, MOOG).costMinor());
        assertEquals(10_000, holding(21, MOOG).avgPriceMinor()); // display average, rounded down

        // When he sells 1 @ ₹101: cost removed 30002 × 1 / 3 = 10000 (rounded down)
        trade(21, MOOG, Side.SELL, 10_100, 1);
        assertEquals(100, holding(21, MOOG).realizedPnlMinor());
        assertEquals(20_002, holding(21, MOOG).costMinor());

        // And then the other 2 @ ₹101: the last sell takes all remaining cost
        trade(21, MOOG, Side.SELL, 10_100, 2);

        // Then realized = 30300 received − 30002 paid = 298, exactly
        assertEquals(298, holding(21, MOOG).realizedPnlMinor());
        assertEquals(0, holding(21, MOOG).costMinor());
        assertEquals(298, account(21).realizedPnlMinor());
        ledger.checkInvariants();
    }

    @Test
    void sameSharesSoldInOneGoGiveTheSameTotal() {
        ledger.openAccount(21, RICH, Map.of());
        trade(21, MOOG, Side.BUY, 10_000, 1);
        trade(21, MOOG, Side.BUY, 10_001, 2);

        trade(21, MOOG, Side.SELL, 10_100, 3);

        assertEquals(298, holding(21, MOOG).realizedPnlMinor());
        assertEquals(0, holding(21, MOOG).costMinor());
        ledger.checkInvariants();
    }

    // ------------------------------------------------------------------ sells

    @Test
    void aLosingSellIsAllowed() {
        // Given 10 bought @ ₹100
        ledger.openAccount(21, RICH, Map.of());
        trade(21, MOOG, Side.BUY, 10_000, 10);

        // When all 10 are sold @ ₹90
        trade(21, MOOG, Side.SELL, 9_000, 10);

        // Then realized is −₹100, and that is a valid state (losses are normal)
        assertEquals(-10_000, holding(21, MOOG).realizedPnlMinor());
        assertEquals(-10_000, account(21).realizedPnlMinor());
        ledger.checkInvariants();
    }

    @Test
    void costRemovedIsShareOfAllHeldSharesFreeAndLocked() {
        // Given 10 bought @ ₹100, then sell 4 @ ₹110 placed (4 locked, 6 free)
        ledger.openAccount(21, RICH, Map.of());
        trade(21, MOOG, Side.BUY, 10_000, 10);
        EngineRef sell = open(21, MOOG, Side.SELL, 11_000, 4);

        // When 2 of the 4 fill @ ₹110
        ledger.onFill(sell, 11_000, 2);

        // Then cost removed is 100000 × 2 / 10 (all 10 held count, not just the 4 locked)
        HoldingView h = holding(21, MOOG);
        assertEquals(80_000, h.costMinor());
        assertEquals(2_000, h.realizedPnlMinor());
        assertEquals(10_000, h.avgPriceMinor()); // average unchanged by a sell
        assertEquals(6, h.freeQty());
        assertEquals(2, h.lockedQty());
        ledger.checkInvariants();
    }

    @Test
    void accountTotalIsTheSumOverSymbols() {
        // Given +₹50 on MOOG and −₹20 on BANANA
        ledger.openAccount(21, RICH, Map.of());
        trade(21, MOOG, Side.BUY, 10_000, 10);
        trade(21, MOOG, Side.SELL, 10_500, 10);
        trade(21, BANANA, Side.BUY, 5_000, 4);
        trade(21, BANANA, Side.SELL, 4_500, 4);

        // Then the account total is +₹30
        assertEquals(5_000, holding(21, MOOG).realizedPnlMinor());
        assertEquals(-2_000, holding(21, BANANA).realizedPnlMinor());
        assertEquals(3_000, account(21).realizedPnlMinor());
        ledger.checkInvariants();
    }

    @Test
    void sellingSeededSharesRealizesTheFullProceeds() {
        // Dev seed shares come with no cost, so selling them realizes everything received.
        // Documents current behaviour; real accounts get shares only by buying.
        ledger.openAccount(1001, 0, Map.of(MOOG, 500L));

        trade(1001, MOOG, Side.SELL, 10_000, 4);

        assertEquals(40_000, holding(1001, MOOG).realizedPnlMinor());
        assertEquals(0, holding(1001, MOOG).costMinor());
        ledger.checkInvariants();
    }

    @Test
    void cancelOfASellLeavesCostAndAverageAlone() {
        ledger.openAccount(21, RICH, Map.of());
        trade(21, MOOG, Side.BUY, 10_000, 10);
        EngineRef sell = open(21, MOOG, Side.SELL, 11_000, 4);

        ledger.onRemainderEnded(sell, 4, com.protrader.cash_owner.models.DoneReason.CANCELED);

        HoldingView h = holding(21, MOOG);
        assertEquals(100_000, h.costMinor());
        assertEquals(10_000, h.avgPriceMinor());
        assertEquals(0, h.realizedPnlMinor());
        assertEquals(10, h.freeQty());
        ledger.checkInvariants();
    }

    // ------------------------------------------------------------------ overflow

    @Test
    void overflowOnTheSecondSideOfATradeChangesNeitherSide() {
        long price = 1_500_000_000_000_000_000L; // ₹1.5e16 a share: absurd, to reach the long limit
        long cash = 4_600_000_000_000_000_000L;
        // Seller account 1 bought 3 at that price → cost 4.5e18; cost × 3 does not fit in a long
        ledger.openAccount(1, cash, Map.of());
        trade(1, MOOG, Side.BUY, price, 3);
        ledger.openAccount(2, cash, Map.of());
        EngineRef sell = open(1, MOOG, Side.SELL, price, 3); // passive
        EngineRef buy = open(2, MOOG, Side.BUY, price, 3); // aggressor: settled first
        AccountView sellerBefore = account(1);
        AccountView buyerBefore = account(2);

        EngineEvent t = new EngineEvent(MOOG, 1, EngineEventKind.TRADE, 0, buy.orderId(), sell.orderId(), 2, 1,
                Side.BUY, price, 3, "");

        assertThrows(RuntimeException.class, () -> ledger.applyEngineEvent(t));

        // both sides are checked before either changes, so neither moved
        assertEquals(buyerBefore, account(2));
        assertEquals(sellerBefore, account(1));
    }

    // ------------------------------------------------------------------ random

    @Test
    void realizedMinusCostAlwaysEqualsMoneyReceivedMinusMoneyPaid() {
        // realized = received − Σ costRemoved and cost = paid − Σ costRemoved, so at every step
        // realized − cost == received − paid. When the position is flat, cost is 0 and realized == received − paid.
        for (long runSeed = 1; runSeed <= 200; runSeed++) {
            Random random = new Random(runSeed);
            Ledger l = new Ledger();
            l.openAccount(21, RICH, Map.of());
            long paid = 0;
            long received = 0;
            long held = 0;
            long orderId = 1;
            for (int step = 0; step < 60; step++) {
                long price = 9_000 + random.nextInt(2_001);
                boolean sell = held > 0 && random.nextBoolean();
                long qty = sell ? 1 + random.nextInt((int) Math.min(held, 30)) : 1 + random.nextInt(30);
                String id = "o" + orderId;
                assertTrue(l.place(21, id, MOOG, sell ? Side.SELL : Side.BUY, price, qty)
                        instanceof PlaceResult.Locked);
                l.onAccepted(new OrderKey(21, id), orderId);
                l.onFill(new EngineRef(MOOG, orderId), price, qty);
                orderId++;
                if (sell) {
                    received += price * qty;
                    held -= qty;
                } else {
                    paid += price * qty;
                    held += qty;
                }
                String where = "run " + runSeed + " step " + step;
                HoldingView h = l.account(21).orElseThrow().holdings().get(MOOG);
                assertEquals(held, h.qty(), where);
                assertEquals(received - paid, h.realizedPnlMinor() - h.costMinor(), where);
                assertEquals(h.realizedPnlMinor(), l.account(21).orElseThrow().realizedPnlMinor(), where);
                if (held == 0) {
                    assertEquals(0, h.costMinor(), where);
                    assertEquals(received - paid, h.realizedPnlMinor(), where);
                }
                l.checkInvariants();
            }
        }
    }
}
