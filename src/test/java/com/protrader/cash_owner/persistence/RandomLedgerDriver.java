package com.protrader.cash_owner.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.services.Ledger;

/** Feeds a Ledger random but valid inputs (nothing it does may throw), so a seed always replays the same way. */
final class RandomLedgerDriver {

    private static final int[] SYMBOLS = { 1, 2 };

    private final Random rnd;
    private final Ledger ledger;
    private final List<OrderKey> keys = new ArrayList<>();
    private final Map<Integer, Long> seqs = new HashMap<>();
    private long nextOrderId = 1000;
    private int nextClient;
    private int accounts;

    RandomLedgerDriver(long seed, Ledger ledger) {
        this.rnd = new Random(seed);
        this.ledger = ledger;
    }

    void step() {
        if (accounts < 3) {
            openAccount();
            return;
        }
        switch (rnd.nextInt(26)) {
            case 0 -> {
                if (accounts < 6)
                    openAccount();
                else
                    place();
            }
            case 6 -> accepted();
            case 7 -> refused();
            case 8 -> noAnswer();
            case 9, 10 -> eventAccepted();
            case 11, 12, 13 -> trade();
            case 14 -> remainderEnded();
            case 15 -> eventRejected();
            case 16 -> ignoredEvent();
            case 17, 18 -> requestCancel();
            case 19 -> cancelRefused();
            case 20 -> duplicateEvent();
            case 21, 22 -> deposit();
            case 23, 24 -> withdraw();
            default -> place();
        }
    }

    /** Ids come from a small pool so some repeat (same or different content, same or other account). */
    private String transferId() {
        return "t" + rnd.nextInt(40);
    }

    private void deposit() {
        long account = rnd.nextInt(8) == 0 ? 100 + rnd.nextInt(3) : 1 + rnd.nextInt(accounts); // 100+ do not exist yet
        ledger.deposit(account, transferId(), 1 + rnd.nextInt(1_000_000), rnd.nextInt(1_000_000));
    }

    private void withdraw() {
        long account = rnd.nextInt(10) == 0 ? 100 + rnd.nextInt(3) : 1 + rnd.nextInt(accounts);
        long amount = rnd.nextInt(5) == 0 ? 999_999_999_999L : 1 + rnd.nextInt(2_000_000); // some too large
        ledger.withdraw(account, transferId(), amount, rnd.nextInt(1_000_000));
    }

    private void openAccount() {
        accounts++;
        ledger.openAccount(accounts, 100_000_000L, Map.of(1, 50L, 2, 50L));
    }

    private void place() {
        long account = rnd.nextInt(20) == 0 ? 99 : 1 + rnd.nextInt(accounts);
        String clientOrderId = "c" + nextClient++;
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        int symbol = SYMBOLS[rnd.nextInt(SYMBOLS.length)];
        PlaceResult result = ledger.place(account, clientOrderId, symbol, side, (100 + rnd.nextInt(21)) * 100L,
                1 + rnd.nextInt(10));
        if (result instanceof PlaceResult.Locked)
            keys.add(new OrderKey(account, clientOrderId));
    }

    private List<OrderView> orders(Predicate<OrderView> filter) {
        List<OrderView> found = new ArrayList<>();
        for (OrderKey key : keys)
            ledger.order(key).filter(filter).ifPresent(found::add);
        return found;
    }

    private OrderView any(List<OrderView> list) {
        return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    private static boolean unanswered(OrderView o) {
        return o.stage() == OrderStage.SENT || o.stage() == OrderStage.IN_DOUBT;
    }

    private long nextSeq(int symbol) {
        return seqs.merge(symbol, 1L, Long::sum);
    }

    private void send(EngineEventKind kind, OrderView o, long orderId, long qty) {
        ledger.applyEngineEvent(new EngineEvent(o.symbol(), nextSeq(o.symbol()), kind, 0, orderId, 0,
                o.key().accountId(), 0, o.side(), o.limitPriceMinor(), qty, o.key().clientOrderId()));
    }

    private void accepted() {
        OrderView o = any(orders(RandomLedgerDriver::unanswered));
        if (o != null)
            ledger.onAccepted(o.key(), nextOrderId++);
    }

    private void refused() {
        OrderView o = any(orders(RandomLedgerDriver::unanswered));
        if (o != null)
            ledger.onRefused(o.key());
    }

    private void noAnswer() {
        OrderView o = any(orders(x -> x.stage() == OrderStage.SENT));
        if (o != null)
            ledger.onNoAnswer(o.key());
    }

    private void eventAccepted() {
        OrderView o = any(orders(x -> x.stage() != OrderStage.DONE));
        if (o != null)
            send(EngineEventKind.ACCEPTED, o, o.orderId() != 0 ? o.orderId() : nextOrderId++, o.originalQty());
    }

    private void trade() {
        int symbol = SYMBOLS[rnd.nextInt(SYMBOLS.length)];
        OrderView buy = any(orders(x -> x.stage() == OrderStage.OPEN && x.side() == Side.BUY && x.symbol() == symbol));
        OrderView sell = any(
                orders(x -> x.stage() == OrderStage.OPEN && x.side() == Side.SELL && x.symbol() == symbol));
        boolean buyAggressor = rnd.nextBoolean();
        long buyId;
        long sellId;
        long buyer;
        long seller;
        long low;
        long high;
        long qty;
        if (buy != null && sell != null && sell.limitPriceMinor() <= buy.limitPriceMinor() && rnd.nextInt(10) > 0) {
            buyId = buy.orderId();
            sellId = sell.orderId();
            buyer = buy.key().accountId();
            seller = sell.key().accountId();
            low = sell.limitPriceMinor();
            high = buy.limitPriceMinor();
            qty = 1 + rnd.nextInt((int) Math.min(buy.remainingQty(), sell.remainingQty()));
        } else if (buy != null) { // half ours: the other side is not in the ledger
            buyId = buy.orderId();
            sellId = nextOrderId++;
            buyer = buy.key().accountId();
            seller = 99;
            low = 100;
            high = buy.limitPriceMinor();
            qty = 1 + rnd.nextInt((int) buy.remainingQty());
        } else {
            return;
        }
        long price = low + rnd.nextInt((int) (high - low) / 100 + 1) * 100L;
        ledger.applyEngineEvent(new EngineEvent(symbol, nextSeq(symbol), EngineEventKind.TRADE, 0,
                buyAggressor ? buyId : sellId, buyAggressor ? sellId : buyId, buyer, seller,
                buyAggressor ? Side.BUY : Side.SELL, price, qty, ""));
    }

    private void remainderEnded() {
        OrderView o = any(orders(x -> x.stage() == OrderStage.OPEN));
        if (o != null)
            send(rnd.nextBoolean() ? EngineEventKind.CANCELLED : EngineEventKind.EXPIRED, o, o.orderId(),
                    o.remainingQty());
    }

    private void eventRejected() {
        OrderView o = any(orders(x -> x.stage() != OrderStage.DONE && x.filledQty() == 0));
        if (o != null)
            send(EngineEventKind.REJECTED, o, o.orderId() != 0 ? o.orderId() : nextOrderId++, o.originalQty());
    }

    private void ignoredEvent() {
        EngineEventKind[] kinds = { EngineEventKind.RESTED, EngineEventKind.HALTED, EngineEventKind.OTHER };
        int symbol = SYMBOLS[rnd.nextInt(SYMBOLS.length)];
        ledger.applyEngineEvent(new EngineEvent(symbol, nextSeq(symbol), kinds[rnd.nextInt(kinds.length)], 0, 0, 0,
                0, 0, Side.BUY, 10_000L, 0, ""));
    }

    private void duplicateEvent() {
        for (int symbol : SYMBOLS) {
            long last = seqs.getOrDefault(symbol, 0L);
            if (last > 0) {
                ledger.applyEngineEvent(new EngineEvent(symbol, 1 + rnd.nextInt((int) last), EngineEventKind.OTHER, 0,
                        0, 0, 0, 0, Side.BUY, 1L, 0, ""));
                return;
            }
        }
    }

    private void requestCancel() {
        OrderView o = any(orders(x -> true));
        if (o != null)
            ledger.requestCancel(rnd.nextInt(10) == 0 ? 99 : o.key().accountId(),
                    new EngineRef(o.symbol(), o.orderId()));
    }

    private void cancelRefused() {
        OrderView o = any(orders(x -> x.stage() == OrderStage.OPEN && x.cancelPending()));
        if (o == null)
            o = any(orders(x -> true));
        if (o != null)
            ledger.onCancelRefused(new EngineRef(o.symbol(), o.orderId()));
    }
}
