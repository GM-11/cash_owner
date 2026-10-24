package com.protrader.cash_owner.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.ApplyResult;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Outcome;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.HoldingView;

/**
 * Step 5, piece 6: every Ledger change caused by an engine event comes back as one AccountChange per account touched.
 * Money is in paise: ₹1 = 100.
 */
class LedgerAccountChangeTest {

    private static final int MOOG = 1;
    private static final long BOB = 21;
    private static final long ALICE = 1;
    private static final long CAROL = 2;

    private Ledger ledger;
    private long seq; // last MOOG event seq used

    private static long rupees(long r) {
        return r * 100;
    }

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.openAccount(BOB, rupees(10_000), Map.of());
        ledger.openAccount(ALICE, 0, Map.of(MOOG, 20L));
        ledger.openAccount(CAROL, 0, Map.of(MOOG, 10L));
        assertLocked(ledger.place(ALICE, "s1", MOOG, Side.SELL, rupees(98), 6));
        assertLocked(ledger.place(BOB, "b1", MOOG, Side.BUY, rupees(100), 10));
    }

    private static void assertLocked(PlaceResult r) {
        assertTrue(r instanceof PlaceResult.Locked, "expected Locked but got " + r);
    }

    // ---------------------------------------------------------------- event builders (seq is automatic)

    private EngineEvent event(EngineEventKind kind, long orderId, long passiveId, long owner, long other, Side side,
            Long price, long qty, String clientOrderId) {
        return new EngineEvent(MOOG, ++seq, kind, 0, orderId, passiveId, owner, other, side, price, qty,
                clientOrderId);
    }

    private EngineEvent accepted(long orderId, long owner, String clientOrderId, Side side) {
        return event(EngineEventKind.ACCEPTED, orderId, 0, owner, 0, side, null, 0, clientOrderId);
    }

    /** buyer is the aggressor when {@code aggressorIsBuyer}. */
    private EngineEvent trade(long buyOrderId, long buyer, long sellOrderId, long seller, boolean aggressorIsBuyer,
            long priceMinor, long qty) {
        return event(EngineEventKind.TRADE, aggressorIsBuyer ? buyOrderId : sellOrderId,
                aggressorIsBuyer ? sellOrderId : buyOrderId, buyer, seller,
                aggressorIsBuyer ? Side.BUY : Side.SELL, priceMinor, qty, "");
    }

    private EngineEvent remainderEnded(EngineEventKind kind, long orderId, long owner, long qty) {
        return event(kind, orderId, 0, owner, 0, null, null, qty, "");
    }

    private EngineEvent rejected(long orderId, long owner, String clientOrderId) {
        return event(EngineEventKind.REJECTED, orderId, 0, owner, 0, null, null, 0, clientOrderId);
    }

    // ---------------------------------------------------------------- helpers

    private ApplyResult applyOk(EngineEvent e) {
        ApplyResult r = ledger.applyEngineEvent(e);
        assertEquals(Outcome.APPLIED, r.outcome(), "event " + e);
        return r;
    }

    private static AccountChange only(ApplyResult r) {
        assertEquals(1, r.changes().size(), "changes: " + r.changes());
        return r.changes().get(0);
    }

    private static AccountChange forAccount(ApplyResult r, long accountId) {
        List<AccountChange> mine = r.changes().stream().filter(c -> c.accountId() == accountId).toList();
        assertEquals(1, mine.size(), "changes for account " + accountId + ": " + r.changes());
        return mine.get(0);
    }

    /** Compares share counts only; cost and P&L have their own tests (LedgerPnlTest). */
    private static void assertShares(long freeQty, long lockedQty, HoldingView position) {
        assertNotNull(position);
        assertEquals(freeQty, position.freeQty(), "free qty");
        assertEquals(lockedQty, position.lockedQty(), "locked qty");
    }

    private AccountView view(long accountId) {
        return ledger.account(accountId).orElseThrow();
    }

    /** Alice sells 6 @ ₹98 (engine id 1), Bob buys 10 @ ₹100 (engine id 2), both accepted. */
    private void acceptAliceAndBob() {
        applyOk(accepted(1, ALICE, "s1", Side.SELL));
        applyOk(accepted(2, BOB, "b1", Side.BUY));
    }

    // ---------------------------------------------------------------- 1-2

    @Test
    void sellAcceptedCarriesTheLockedShares() {
        // Alice holds 20 MOOG; placing sell 6 moved 6 from free to locked, so her NEW must show 14 free / 6 locked
        AccountChange c = only(applyOk(accepted(1, ALICE, "s1", Side.SELL)));

        assertEquals(ALICE, c.accountId());
        assertEquals(AccountChangeKind.NEW, c.kind());
        assertShares(14, 6, c.position());
        assertEquals(view(ALICE).holdings().get(MOOG), c.position());
    }

    @Test
    void acceptedMakesOneNewChange() {
        applyOk(accepted(1, ALICE, "s1", Side.SELL));
        long before = view(BOB).accountSeq();
        EngineEvent e = accepted(2, BOB, "b1", Side.BUY);

        AccountChange c = only(applyOk(e));

        assertEquals(BOB, c.accountId());
        assertEquals(AccountChangeKind.NEW, c.kind());
        assertEquals(before + 1, c.accountSeq());
        assertEquals(OrderStage.OPEN, c.order().stage());
        assertEquals(2, c.order().orderId());
        assertEquals(rupees(9_000), c.balances().freeCashMinor());
        assertEquals(rupees(1_000), c.balances().lockedCashMinor());
        assertNull(c.position());
        assertEquals(e.seq(), c.engineSeq());
        assertEquals(0, c.fillPriceMinor());
        assertEquals(0, c.fillQty());
    }

    @Test
    void placingAloneChangesNothing() {
        assertEquals(0, view(BOB).accountSeq());
        assertEquals(0, view(ALICE).accountSeq());
        assertEquals(0, view(CAROL).accountSeq());
    }

    @Test
    void changesCarryTheEngineEventTimestamp() {
        // the builders always pass ts = 0, so a change built with 0 instead of e.ts() would pass every other test
        long acceptedTs = 1_700_000_000_123L;
        long tradeTs = 1_700_000_000_456L;
        EngineEvent a = new EngineEvent(MOOG, ++seq, EngineEventKind.ACCEPTED, acceptedTs, 1, 0, ALICE, 0, Side.SELL,
                null, 0, "s1");
        assertEquals(acceptedTs, only(applyOk(a)).ts());
        applyOk(accepted(2, BOB, "b1", Side.BUY));

        EngineEvent t = new EngineEvent(MOOG, ++seq, EngineEventKind.TRADE, tradeTs, 2, 1, BOB, ALICE, Side.BUY,
                rupees(98), 6, "");
        ApplyResult r = applyOk(t);

        assertEquals(2, r.changes().size());
        for (AccountChange c : r.changes()) {
            assertEquals(tradeTs, c.ts());
        }
    }

    // ---------------------------------------------------------------- 3-5

    @Test
    void partialFillTradeMakesTwoChanges() {
        acceptAliceAndBob();
        EngineEvent t = trade(2, BOB, 1, ALICE, true, rupees(98), 6);

        ApplyResult r = applyOk(t);

        assertEquals(2, r.changes().size());
        AccountChange bob = forAccount(r, BOB);
        assertEquals(AccountChangeKind.PARTIAL_FILL, bob.kind());
        assertEquals(6, bob.order().filledQty());
        assertEquals(10, bob.order().originalQty());
        assertEquals(rupees(98), bob.fillPriceMinor());
        assertEquals(6, bob.fillQty());
        assertEquals(rupees(400), bob.balances().lockedCashMinor());
        assertEquals(rupees(9_012), bob.balances().freeCashMinor());
        assertShares(6, 0, bob.position());
        assertEquals(t.seq(), bob.engineSeq());

        AccountChange alice = forAccount(r, ALICE);
        assertEquals(AccountChangeKind.FILL, alice.kind());
        assertEquals(rupees(98), alice.fillPriceMinor());
        assertEquals(6, alice.fillQty());
        assertEquals(rupees(588), alice.balances().freeCashMinor());
        assertShares(14, 0, alice.position());
        assertEquals(t.seq(), alice.engineSeq());
    }

    @Test
    void eachAccountCountsOnItsOwn() {
        List<ApplyResult> results = new ArrayList<>();
        results.add(applyOk(accepted(1, ALICE, "s1", Side.SELL)));
        results.add(applyOk(accepted(2, BOB, "b1", Side.BUY)));
        results.add(applyOk(trade(2, BOB, 1, ALICE, true, rupees(98), 6)));

        assertEquals(1, forAccount(results.get(0), ALICE).accountSeq());
        assertEquals(1, forAccount(results.get(1), BOB).accountSeq());
        assertEquals(2, forAccount(results.get(2), ALICE).accountSeq());
        assertEquals(2, forAccount(results.get(2), BOB).accountSeq());
        // Alice's ACCEPTED did not move Bob's seq and the other way round
        assertEquals(1, results.get(0).changes().size());
        assertEquals(1, results.get(1).changes().size());
    }

    @Test
    void secondTradeFullyFillsBob() {
        acceptAliceAndBob();
        applyOk(trade(2, BOB, 1, ALICE, true, rupees(98), 6));
        assertLocked(ledger.place(CAROL, "s2", MOOG, Side.SELL, rupees(99), 4));
        applyOk(accepted(3, CAROL, "s2", Side.SELL));

        // Carol's sell is the aggressor and hits Bob's resting remainder
        ApplyResult r = applyOk(trade(2, BOB, 3, CAROL, false, rupees(99), 4));

        AccountChange bob = forAccount(r, BOB);
        assertEquals(AccountChangeKind.FILL, bob.kind());
        assertEquals(OrderStage.DONE, bob.order().stage());
        assertEquals(DoneReason.FILLED, bob.order().doneReason());
        assertEquals(0, bob.balances().lockedCashMinor());
        // paid 6 × ₹98 + 4 × ₹99 = ₹984 out of ₹10,000
        assertEquals(rupees(10_000 - 984), bob.balances().freeCashMinor());
        assertEquals(3, bob.accountSeq()); // NEW, PARTIAL_FILL, FILL
    }

    // ---------------------------------------------------------------- 6-7

    @Test
    void duplicateChangesNothing() {
        acceptAliceAndBob();
        EngineEvent t = trade(2, BOB, 1, ALICE, true, rupees(98), 6);
        applyOk(t);
        AccountView bob = view(BOB);
        AccountView alice = view(ALICE);

        ApplyResult again = ledger.applyEngineEvent(t);

        assertEquals(Outcome.DUPLICATE, again.outcome());
        assertTrue(again.changes().isEmpty());
        assertEquals(bob, view(BOB));
        assertEquals(alice, view(ALICE));
    }

    @Test
    void ignoredEventsChangeNothing() {
        acceptAliceAndBob();
        AccountView bob = view(BOB);
        AccountView alice = view(ALICE);

        for (EngineEventKind kind : List.of(EngineEventKind.RESTED, EngineEventKind.HALTED, EngineEventKind.OTHER)) {
            ApplyResult r = ledger.applyEngineEvent(event(kind, 1, 0, ALICE, 0, Side.SELL, null, 6, ""));
            assertEquals(Outcome.IGNORED, r.outcome(), kind.toString());
            assertTrue(r.changes().isEmpty(), kind.toString());
        }

        assertEquals(bob, view(BOB));
        assertEquals(alice, view(ALICE));
    }

    @Test
    void anEventForAnOrderThatIsNotOursIsIgnoredWithNoChange() {
        ApplyResult r = ledger.applyEngineEvent(accepted(99, 777, "nope", Side.BUY));

        assertEquals(Outcome.IGNORED, r.outcome());
        assertTrue(r.changes().isEmpty());
    }

    // ---------------------------------------------------------------- 8

    @Test
    void cancelledMakesOneCanceledChangeForABuy() {
        acceptAliceAndBob();
        long before = view(BOB).accountSeq();

        AccountChange c = only(applyOk(remainderEnded(EngineEventKind.CANCELLED, 2, BOB, 10)));

        assertEquals(AccountChangeKind.CANCELED, c.kind());
        assertEquals(BOB, c.accountId());
        assertEquals(before + 1, c.accountSeq());
        assertEquals(DoneReason.CANCELED, c.order().doneReason());
        assertEquals(rupees(10_000), c.balances().freeCashMinor());
        assertEquals(0, c.balances().lockedCashMinor());
        assertNull(c.position());
    }

    @Test
    void cancelledMakesOneCanceledChangeForASellWithThePosition() {
        acceptAliceAndBob();
        long before = view(ALICE).accountSeq();

        AccountChange c = only(applyOk(remainderEnded(EngineEventKind.CANCELLED, 1, ALICE, 6)));

        assertEquals(AccountChangeKind.CANCELED, c.kind());
        assertEquals(before + 1, c.accountSeq());
        assertShares(20, 0, c.position());
    }

    @Test
    void expiredMakesOneExpiredChange() {
        acceptAliceAndBob();
        long bobBefore = view(BOB).accountSeq();
        long aliceBefore = view(ALICE).accountSeq();

        AccountChange buy = only(applyOk(remainderEnded(EngineEventKind.EXPIRED, 2, BOB, 10)));
        AccountChange sell = only(applyOk(remainderEnded(EngineEventKind.EXPIRED, 1, ALICE, 6)));

        assertEquals(AccountChangeKind.EXPIRED, buy.kind());
        assertEquals(bobBefore + 1, buy.accountSeq());
        assertEquals(rupees(10_000), buy.balances().freeCashMinor());
        assertNull(buy.position());
        assertEquals(DoneReason.EXPIRED, buy.order().doneReason());
        assertEquals(AccountChangeKind.EXPIRED, sell.kind());
        assertEquals(aliceBefore + 1, sell.accountSeq());
        assertShares(20, 0, sell.position());
    }

    @Test
    void rejectedMakesOneRejectedChangeAndKeepsTheRecord() {
        long bobBefore = view(BOB).accountSeq();
        long aliceBefore = view(ALICE).accountSeq();

        AccountChange buy = only(applyOk(rejected(2, BOB, "b1")));
        AccountChange sell = only(applyOk(rejected(1, ALICE, "s1")));

        assertEquals(AccountChangeKind.REJECTED, buy.kind());
        assertEquals(bobBefore + 1, buy.accountSeq());
        assertEquals(rupees(10_000), buy.balances().freeCashMinor());
        assertEquals(0, buy.balances().lockedCashMinor());
        assertNull(buy.position());
        assertEquals(AccountChangeKind.REJECTED, sell.kind());
        assertEquals(aliceBefore + 1, sell.accountSeq());
        assertShares(20, 0, sell.position());

        // the rejected order's record is kept, unlike a refused one
        var kept = ledger.order(new OrderKey(BOB, "b1")).orElseThrow();
        assertEquals(OrderStage.DONE, kept.stage());
        assertEquals(DoneReason.REJECTED, kept.doneReason());
        assertEquals(DoneReason.REJECTED, buy.order().doneReason());
    }

    // ---------------------------------------------------------------- 9

    @Test
    void aFailedEventBurnsNoSeq() {
        acceptAliceAndBob();
        AccountView bob = view(BOB);
        AccountView alice = view(ALICE);
        AccountView carol = view(CAROL);
        var bobOrder = ledger.order(new OrderKey(BOB, "b1")).orElseThrow();
        var aliceOrder = ledger.order(new OrderKey(ALICE, "s1")).orElseThrow();
        long failedSeq = seq + 1;

        // 11 > Bob's 10 remaining: the Ledger refuses before changing anything
        assertThrows(RuntimeException.class, () -> ledger.applyEngineEvent(trade(2, BOB, 1, ALICE, true,
                rupees(98), 11)));

        assertEquals(bob, view(BOB));
        assertEquals(alice, view(ALICE));
        assertEquals(carol, view(CAROL));
        assertEquals(bobOrder, ledger.order(new OrderKey(BOB, "b1")).orElseThrow());
        assertEquals(aliceOrder, ledger.order(new OrderKey(ALICE, "s1")).orElseThrow());

        // the same seq is used again by the next valid event; no gap
        seq = failedSeq - 1;
        ApplyResult r = applyOk(trade(2, BOB, 1, ALICE, true, rupees(98), 6));
        assertEquals(bob.accountSeq() + 1, forAccount(r, BOB).accountSeq());
        assertEquals(alice.accountSeq() + 1, forAccount(r, ALICE).accountSeq());
    }

    // ---------------------------------------------------------------- 10

    @Test
    void snapshotSeqEqualsTheLastChangesSeq() {
        Map<Long, Long> last = new HashMap<>();
        List<ApplyResult> results = new ArrayList<>();
        results.add(applyOk(accepted(1, ALICE, "s1", Side.SELL)));
        results.add(applyOk(accepted(2, BOB, "b1", Side.BUY)));
        results.add(applyOk(trade(2, BOB, 1, ALICE, true, rupees(98), 6)));
        results.add(applyOk(remainderEnded(EngineEventKind.CANCELLED, 2, BOB, 4)));
        results.forEach(r -> r.changes().forEach(c -> last.put(c.accountId(), c.accountSeq())));

        assertEquals(last.get(BOB).longValue(), view(BOB).accountSeq());
        assertEquals(last.get(ALICE).longValue(), view(ALICE).accountSeq());
        assertEquals(0, view(CAROL).accountSeq());
        assertEquals(3, view(BOB).accountSeq());
        assertEquals(2, view(ALICE).accountSeq());
    }

    // ---------------------------------------------------------------- 11

    /** One order the random run is tracking. */
    private static final class Tracked {
        final long accountId;
        final String clientOrderId;
        final int symbol;
        final Side side;
        final long limit;
        long remaining;
        long engineId;

        Tracked(long accountId, String clientOrderId, int symbol, Side side, long limit, long qty) {
            this.accountId = accountId;
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.limit = limit;
            this.remaining = qty;
        }
    }

    @Test
    void randomRunKeepsEveryAccountsSeqsGaplessAndBalancesTrue() {
        for (long runSeed = 1; runSeed <= 5; runSeed++) {
            randomRun(runSeed);
        }
    }

    private void randomRun(long runSeed) {
        Random rnd = new Random(runSeed);
        Ledger l = new Ledger();
        long[] accounts = { 11, 12, 13, 14, 15 };
        int[] symbols = { 1, 2 };
        for (long a : accounts) {
            l.openAccount(a, rupees(1_000_000), Map.of(1, 500L, 2, 500L));
        }

        Map<Integer, Long> lastSeq = new HashMap<>();
        Map<Integer, Long> lastEngineId = new HashMap<>();
        Map<Integer, List<EngineEvent>> history = new HashMap<>();
        List<Tracked> sent = new ArrayList<>(); // placed, not accepted yet
        List<Tracked> open = new ArrayList<>();
        Map<Long, List<AccountChange>> changesByAccount = new HashMap<>();
        int clientCounter = 0;

        for (int step = 0; step < 2_000; step++) {
            int symbol = symbols[rnd.nextInt(symbols.length)];
            int action = rnd.nextInt(100);
            EngineEvent e = null;

            if (action < 25) { // place a new order
                long account = accounts[rnd.nextInt(accounts.length)];
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                long limit = rupees(95 + rnd.nextInt(10));
                long qty = 1 + rnd.nextInt(10);
                String cid = "c" + (++clientCounter);
                if (l.place(account, cid, symbol, side, limit, qty) instanceof PlaceResult.Locked) {
                    sent.add(new Tracked(account, cid, symbol, side, limit, qty));
                }
            } else if (action < 45 && !sent.isEmpty()) { // engine accepts
                Tracked t = sent.remove(rnd.nextInt(sent.size()));
                t.engineId = lastEngineId.merge(t.symbol, 1L, Long::sum);
                e = new EngineEvent(t.symbol, nextSeq(lastSeq, t.symbol), EngineEventKind.ACCEPTED, 0, t.engineId, 0,
                        t.accountId, 0, t.side, null, 0, t.clientOrderId);
                open.add(t);
            } else if (action < 70 && open.size() >= 2) { // trade between a buy and a sell that cross
                Tracked buy = pick(rnd, open, Side.BUY, null);
                Tracked sell = buy == null ? null : pick(rnd, open, Side.SELL, buy);
                if (sell != null) {
                    long qty = 1 + rnd.nextInt((int) Math.min(buy.remaining, sell.remaining));
                    boolean buyerAggresses = rnd.nextBoolean();
                    e = new EngineEvent(buy.symbol, nextSeq(lastSeq, buy.symbol), EngineEventKind.TRADE, 0,
                            buyerAggresses ? buy.engineId : sell.engineId,
                            buyerAggresses ? sell.engineId : buy.engineId, buy.accountId, sell.accountId,
                            buyerAggresses ? Side.BUY : Side.SELL, sell.limit, qty, "");
                    buy.remaining -= qty;
                    sell.remaining -= qty;
                    open.removeIf(t -> t.remaining == 0);
                }
            } else if (action < 80 && !open.isEmpty()) { // cancel or expire the remainder
                Tracked t = open.remove(rnd.nextInt(open.size()));
                e = new EngineEvent(t.symbol, nextSeq(lastSeq, t.symbol),
                        rnd.nextBoolean() ? EngineEventKind.CANCELLED : EngineEventKind.EXPIRED, 0, t.engineId, 0,
                        t.accountId, 0, null, null, t.remaining, "");
            } else if (action < 86 && !sent.isEmpty()) { // engine refuses an order it never accepted
                Tracked t = sent.remove(rnd.nextInt(sent.size()));
                long engineId = lastEngineId.merge(t.symbol, 1L, Long::sum);
                e = new EngineEvent(t.symbol, nextSeq(lastSeq, t.symbol), EngineEventKind.REJECTED, 0, engineId, 0,
                        t.accountId, 0, t.side, null, 0, t.clientOrderId);
            } else if (action < 92) { // events that move no money
                EngineEventKind kind = rnd.nextBoolean() ? EngineEventKind.RESTED : EngineEventKind.HALTED;
                e = new EngineEvent(symbol, nextSeq(lastSeq, symbol), kind, 0, 1, 0, 11, 0, Side.BUY, null, 0, "");
            } else { // the bridge resends something old
                List<EngineEvent> past = history.getOrDefault(symbol, List.of());
                if (!past.isEmpty()) {
                    ApplyResult dup = l.applyEngineEvent(past.get(rnd.nextInt(past.size())));
                    assertEquals(Outcome.DUPLICATE, dup.outcome(), "run " + runSeed + " step " + step);
                    assertTrue(dup.changes().isEmpty(), "run " + runSeed + " step " + step);
                }
            }

            if (e == null) {
                continue;
            }
            history.computeIfAbsent(e.symbol(), s -> new ArrayList<>()).add(e);
            ApplyResult r = l.applyEngineEvent(e);
            String where = "run " + runSeed + " step " + step + " " + e;
            if (r.outcome() != Outcome.APPLIED) {
                assertTrue(r.changes().isEmpty(), where);
                continue;
            }
            for (AccountChange c : r.changes()) {
                // balances in a change are the Ledger's own, right after the event
                assertEquals(l.account(c.accountId()).orElseThrow().freeCashMinor(), c.balances().freeCashMinor(),
                        where);
                assertEquals(l.account(c.accountId()).orElseThrow().lockedCashMinor(),
                        c.balances().lockedCashMinor(), where);
                boolean fill = c.kind() == AccountChangeKind.PARTIAL_FILL || c.kind() == AccountChangeKind.FILL;
                // position = shares moved: any fill, or any change to a sell (NEW locks shares, an end frees them)
                if (fill || c.order().side() == Side.SELL) {
                    assertNotNull(c.position(), where);
                    assertEquals(l.account(c.accountId()).orElseThrow().holdings().get(c.order().symbol()),
                            c.position(), where);
                } else {
                    assertNull(c.position(), where);
                }
                assertEquals(e.seq(), c.engineSeq(), where);
                assertEquals(e.ts(), c.ts(), where);
                changesByAccount.computeIfAbsent(c.accountId(), a -> new ArrayList<>()).add(c);
            }
        }

        l.checkInvariants();
        assertTrue(!changesByAccount.isEmpty(), "run " + runSeed + " produced no changes at all");
        for (long a : accounts) {
            List<AccountChange> list = changesByAccount.getOrDefault(a, List.of());
            for (int i = 0; i < list.size(); i++) {
                assertEquals(i + 1, list.get(i).accountSeq(), "account " + a + " run " + runSeed + " change " + i);
            }
            assertEquals(list.size(), l.account(a).orElseThrow().accountSeq(), "account " + a + " run " + runSeed);
        }
    }

    private static long nextSeq(Map<Integer, Long> lastSeq, int symbol) {
        return lastSeq.merge(symbol, 1L, Long::sum);
    }

    /** A random open order of this side whose symbol and price cross {@code against}, from another account. */
    private static Tracked pick(Random rnd, List<Tracked> open, Side side, Tracked against) {
        List<Tracked> candidates = new ArrayList<>();
        for (Tracked t : open) {
            if (t.side != side) {
                continue;
            }
            if (against != null) {
                // the sell's limit is the trade price: it must not be above the buy's limit
                if (t.symbol != against.symbol || t.accountId == against.accountId || t.limit > against.limit) {
                    continue;
                }
            }
            candidates.add(t);
        }
        return candidates.isEmpty() ? null : candidates.get(rnd.nextInt(candidates.size()));
    }
}
