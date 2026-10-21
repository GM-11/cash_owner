package com.protrader.cash_owner.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.protrader.cash_owner.models.Account;
import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.ApplyResult;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.Holding;
import com.protrader.cash_owner.models.Order;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.RejectReason;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.HoldingView;
import com.protrader.cash_owner.models.views.OrderView;

public final class Ledger {
    private static final Logger log = LoggerFactory.getLogger(Ledger.class);

    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<OrderKey, Order> orders = new LinkedHashMap<>();
    private final Map<EngineRef, OrderKey> byEngineRef = new HashMap<>();
    private final Map<Integer, Long> lastApplied = new HashMap<>();

    public void openAccount(long accountId, long cashMinor, Map<Integer, Long> shares) {
        if (accounts.containsKey(accountId)) {
            throw new IllegalStateException("account already exists: " + accountId);
        }
        if (cashMinor < 0) {
            throw new IllegalArgumentException("negative cash for account " + accountId + ": " + cashMinor);
        }
        for (Map.Entry<Integer, Long> e : shares.entrySet()) {
            if (e.getValue() < 0) {
                throw new IllegalArgumentException(
                        "negative shares for account " + accountId + " symbol " + e.getKey() + ": " + e.getValue());
            }
        }
        Account account = new Account(accountId, cashMinor, 0);
        for (Map.Entry<Integer, Long> e : shares.entrySet()) {
            Map<Integer, Holding> holdings = account.getHoldings();
            holdings.put(e.getKey(), new Holding(e.getValue(), 0));
        }
        accounts.put(accountId, account);
    }

    public PlaceResult place(long accountId, String clientOrderId, int symbol, Side side, long limitPriceMinor,
            long qty) {
        OrderKey key = new OrderKey(accountId, clientOrderId);
        Order existing = orders.get(key);
        if (existing != null) {
            return new PlaceResult.Duplicate(OrderView.of(existing));
        }

        Account account = accounts.get(accountId);
        if (account == null) {
            return new PlaceResult.Rejected(RejectReason.UNKNOWN_ACCOUNT, 0);
        }

        if (side == null || limitPriceMinor <= 0 || qty <= 0 || clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid order for account " + accountId + ": side=" + side + " limitPriceMinor=" + limitPriceMinor
                            + " qty=" + qty + " clientOrderId=" + clientOrderId);
        }

        if (side == Side.BUY) {
            long need;
            try {
                need = Math.multiplyExact(limitPriceMinor, qty);
            } catch (ArithmeticException e) {
                return new PlaceResult.Rejected(RejectReason.INSUFFICIENT_CASH, Long.MAX_VALUE);
            }
            if (account.getFreeCashMinor() < need) {
                return new PlaceResult.Rejected(RejectReason.INSUFFICIENT_CASH, need - account.getFreeCashMinor());
            }
            account.setFreeCashMinor(account.getFreeCashMinor() - need);
            account.setLockedCashMinor(account.getLockedCashMinor() + need);
        } else {
            Holding currentHolding = account.getHoldings().get(symbol);
            long freeQty = currentHolding == null ? 0 : currentHolding.getFreeQty();
            if (freeQty < qty) {
                return new PlaceResult.Rejected(RejectReason.INSUFFICIENT_HOLDINGS, 0);
            }
            Holding holding = account.getHoldingFromSymbol(symbol);
            holding.setFreeQty(holding.getFreeQty() - qty);
            holding.setLockedQty(holding.getLockedQty() + qty);
        }

        Order order = new Order(key, symbol, side, limitPriceMinor, qty);
        orders.put(key, order);
        return new PlaceResult.Locked(OrderView.of(order));
    }

    public void onAccepted(OrderKey key, long orderId) {
        Order order = orders.get(key);
        if (order == null || (order.getStage() != OrderStage.SENT && order.getStage() != OrderStage.IN_DOUBT)) {
            return;
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive: " + orderId);
        }
        EngineRef ref = new EngineRef(order.getSymbol(), orderId);
        OrderKey clashing = byEngineRef.get(ref);
        if (clashing != null && !clashing.equals(key)) {
            throw new IllegalStateException(
                    "engine ref " + ref + " already maps to " + clashing + ", cannot also map to " + key);
        }
        order.setOrderId(orderId);
        order.setStage(OrderStage.OPEN);
        byEngineRef.put(ref, key);
    }

    public void onRefused(OrderKey key) {
        Order order = orders.get(key);
        if (order == null || (order.getStage() != OrderStage.SENT && order.getStage() != OrderStage.IN_DOUBT)) {
            return;
        }
        releaseLock(order);
        // A refused order was never stored by the sequencer, so forget it here too: a
        // retry with the same
        // client_order_id must be a fresh order, not a Duplicate. It has no byEngineRef
        // entry yet.
        orders.remove(key);
    }

    public void onNoAnswer(OrderKey key) {
        Order order = orders.get(key);
        if (order == null || order.getStage() != OrderStage.SENT) {
            return;
        }
        order.setStage(OrderStage.IN_DOUBT);
    }

    private void releaseLock(Order order) {
        Account account = accounts.get(order.getKey().accountId());
        if (order.getSide() == Side.BUY) {
            long amount = Math.multiplyExact(order.getLimitPriceMinor(), order.getRemainingQty());
            account.setLockedCashMinor(account.getLockedCashMinor() - amount);
            account.setFreeCashMinor(account.getFreeCashMinor() + amount);
        } else {
            Holding holding = account.getHoldingFromSymbol(order.getSymbol());
            holding.setLockedQty(holding.getLockedQty() - order.getRemainingQty());
            holding.setFreeQty(holding.getFreeQty() + order.getRemainingQty());
        }
    }

    public List<OrderView> inDoubt() {
        List<OrderView> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.getStage() == OrderStage.IN_DOUBT) {
                result.add(OrderView.of(order));
            }
        }
        return result;
    }

    public Optional<AccountView> account(long accountId) {
        return Optional.ofNullable(accounts.get(accountId)).map(AccountView::of);
    }

    /**
     * Orders of this account that still hold a lock (SENT, IN_DOUBT, OPEN), oldest
     * first. Copies only.
     */
    public List<OrderView> openOrders(long accountId) {
        List<OrderView> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.getKey().accountId() == accountId && order.getStage() != OrderStage.DONE) {
                result.add(OrderView.of(order));
            }
        }
        return result;
    }

    public Optional<OrderView> order(OrderKey key) {
        return Optional.ofNullable(orders.get(key)).map(OrderView::of);
    }

    public Optional<OrderView> orderByEngineRef(EngineRef ref) {
        OrderKey key = byEngineRef.get(ref);
        return key == null ? Optional.empty() : Optional.ofNullable(orders.get(key)).map(OrderView::of);
    }

    public void checkInvariants() {
        for (Account account : accounts.values()) {
            if (account.getFreeCashMinor() < 0 || account.getLockedCashMinor() < 0) {
                throw new IllegalStateException("negative cash for account " + account.getAccountId() + ": free="
                        + account.getFreeCashMinor() + " locked=" + account.getLockedCashMinor());
            }
            for (Map.Entry<Integer, Holding> e : account.getHoldings().entrySet()) {
                Holding h = e.getValue();
                if (h.getFreeQty() < 0 || h.getLockedQty() < 0) {
                    throw new IllegalStateException("negative shares for account " + account.getAccountId() + " symbol "
                            + e.getKey() + ": free=" + h.getFreeQty() + " locked=" + h.getLockedQty());
                }
            }
        }

        Map<Long, Long> expectedLockedCash = new HashMap<>();
        Map<Long, Map<Integer, Long>> expectedLockedQty = new HashMap<>();
        for (Order order : orders.values()) {
            if (order.getStage() != OrderStage.SENT && order.getStage() != OrderStage.IN_DOUBT
                    && order.getStage() != OrderStage.OPEN) {
                continue;
            }
            long accountId = order.getKey().accountId();
            if (order.getSide() == Side.BUY) {
                long amount = Math.multiplyExact(order.getLimitPriceMinor(), order.getRemainingQty());
                expectedLockedCash.merge(accountId, amount, Long::sum);
            } else {
                expectedLockedQty.computeIfAbsent(accountId, a -> new HashMap<>())
                        .merge(order.getSymbol(), order.getRemainingQty(), Long::sum);
            }
        }

        for (Account account : accounts.values()) {
            long expectedCash = expectedLockedCash.getOrDefault(account.getAccountId(), 0L);
            if (account.getLockedCashMinor() != expectedCash) {
                throw new IllegalStateException(
                        "locked cash mismatch for account " + account.getAccountId() + ": locked="
                                + account.getLockedCashMinor() + " expected=" + expectedCash);
            }

            Map<Integer, Long> expectedQtyBySymbol = expectedLockedQty.getOrDefault(account.getAccountId(), Map.of());
            for (Map.Entry<Integer, Long> e : expectedQtyBySymbol.entrySet()) {
                Holding holding = account.getHoldings().get(e.getKey());
                long actual = holding == null ? 0 : holding.getLockedQty();
                if (actual != e.getValue()) {
                    throw new IllegalStateException("locked qty mismatch for account " + account.getAccountId()
                            + " symbol " + e.getKey() + ": locked=" + actual + " expected=" + e.getValue());
                }
            }
            for (Map.Entry<Integer, Holding> e : account.getHoldings().entrySet()) {
                if (!expectedQtyBySymbol.containsKey(e.getKey()) && e.getValue().getLockedQty() != 0) {
                    throw new IllegalStateException("locked qty mismatch for account " + account.getAccountId()
                            + " symbol " + e.getKey() + ": locked=" + e.getValue().getLockedQty() + " expected=0");
                }
            }
        }

        for (Map.Entry<EngineRef, OrderKey> e : byEngineRef.entrySet()) {
            EngineRef ref = e.getKey();
            Order order = orders.get(e.getValue());
            if (order == null || order.getSymbol() != ref.symbol() || order.getOrderId() != ref.orderId()) {
                throw new IllegalStateException("byEngineRef entry " + ref + " does not match its order");
            }
        }

        for (Order order : orders.values()) {
            if (order.getStage() == OrderStage.DONE && order.getRemainingQty() != 0) {
                throw new IllegalStateException("DONE order " + order.getKey() + " still has remaining qty "
                        + order.getRemainingQty());
            }
        }
    }

    /**
     * Every check a fill must pass. Throws, never changes anything. Returns the
     * order the fill is for.
     * Split out of onFill so a TRADE can check both of its sides before settling
     * either one.
     */
    private Order checkFill(EngineRef ref, long tradePriceMinor, long qty) {
        Order order = orders.get(byEngineRef.get(ref));
        if (order == null) {
            throw new IllegalStateException("no order for ref " + ref);
        }
        if (order.getStage() != OrderStage.OPEN) {
            throw new IllegalStateException("order " + order.getKey() + " for ref " + ref + " is " + order.getStage()
                    + ", not OPEN");
        }

        long accountId = order.getKey().accountId();
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("unknown account " + accountId);
        }

        if (tradePriceMinor <= 0) {
            throw new IllegalArgumentException("trade price must be positive");
        }
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if (qty > order.getRemainingQty()) {
            throw new IllegalArgumentException("qty " + qty + " exceeds remaining qty " + order.getRemainingQty());
        }

        long limitPrice = order.getLimitPriceMinor();
        if (order.getSide() == Side.BUY) {
            if (tradePriceMinor > limitPrice) {
                throw new IllegalArgumentException(
                        "buy filled at " + tradePriceMinor + ", above its limit " + limitPrice);
            }
        } else {
            if (account.getHoldings().get(order.getSymbol()) == null) {
                throw new IllegalStateException("no holding for symbol " + order.getSymbol());
            }
            if (tradePriceMinor < limitPrice) {
                throw new IllegalArgumentException(
                        "sell filled at " + tradePriceMinor + ", below its limit " + limitPrice);
            }
        }

        // the amounts onFill will compute; checked here so an overflow throws before
        // anything changes
        Math.multiplyExact(tradePriceMinor, qty);
        Math.multiplyExact(limitPrice, qty);
        return order;
    }

    public void onFill(EngineRef ref, long tradePriceMinor, long qty) {
        Order order = checkFill(ref, tradePriceMinor, qty);
        Account account = accounts.get(order.getKey().accountId());

        long remaining = order.getRemainingQty();
        long limitPrice = order.getLimitPriceMinor();
        long priceTimesQty = Math.multiplyExact(tradePriceMinor, qty);

        if (order.getSide() == Side.BUY) {
            Holding holding = account.getHoldingFromSymbol(order.getSymbol());

            long lockReleased = Math.multiplyExact(limitPrice, qty);
            long priceImprovement = Math.subtractExact(limitPrice, tradePriceMinor);
            long refund = Math.multiplyExact(priceImprovement, qty);

            account.setLockedCashMinor(account.getLockedCashMinor() - lockReleased);
            account.setFreeCashMinor(account.getFreeCashMinor() + refund);
            holding.setFreeQty(holding.getFreeQty() + qty);
        } else {
            Holding holding = account.getHoldings().get(order.getSymbol());
            long proceeds = priceTimesQty;

            account.setFreeCashMinor(account.getFreeCashMinor() + proceeds);
            holding.setLockedQty(holding.getLockedQty() - qty);
        }

        order.setRemainingQty(remaining - qty);
        if (order.getRemainingQty() == 0) {
            order.setStage(OrderStage.DONE);
            order.setDoneReason(DoneReason.FILLED);
        }
        order.setFilledQty(order.getFilledQty() + qty);
        order.setFilledNotionalMinor(order.getFilledNotionalMinor() + priceTimesQty);
    }

    public void onRemainderEnded(EngineRef ref, long qty, DoneReason reason) {
        if (reason != DoneReason.CANCELED && reason != DoneReason.EXPIRED) {
            throw new IllegalArgumentException("reason must be CANCELED or EXPIRED: " + reason);
        }

        Order order = orders.get(byEngineRef.get(ref));
        if (order == null) {
            throw new IllegalStateException("no order for ref " + ref);
        }

        if (order.getStage() != OrderStage.OPEN) {
            throw new IllegalStateException("order " + order.getKey() + " for ref " + ref + " is "
                    + order.getStage() + ", not OPEN");
        }

        long remaining = order.getRemainingQty();
        if (remaining != qty) {
            throw new IllegalStateException("remaining qty mismatch: " + remaining + " != " + qty);
        }
        this.releaseLock(order);
        order.setRemainingQty(0);
        order.setStage(OrderStage.DONE);
        order.setDoneReason(reason);
    }

    public void onEngineRejected(OrderKey key, long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive: " + orderId);
        }

        Order order = orders.get(key);
        if (order == null) {
            throw new IllegalStateException("no order for key " + key);
        }

        if (order.getStage() != OrderStage.OPEN && order.getStage() != OrderStage.IN_DOUBT
                && order.getStage() != OrderStage.SENT) {
            throw new IllegalStateException("order " + key + " is already " + order.getStage()
                    + " (" + order.getDoneReason() + "), cannot be rejected");
        }

        if (order.getOrderId() != 0 && order.getOrderId() != orderId) {
            throw new IllegalStateException("order " + key + " has engine id " + order.getOrderId()
                    + " but the REJECTED event names " + orderId);
        }

        if (order.getFilledQty() > 0) {
            throw new IllegalStateException("rejected order " + key + " already has " + order.getFilledQty()
                    + " filled");
        }

        this.releaseLock(order);
        order.setRemainingQty(0);
        order.setStage(OrderStage.DONE);
        order.setDoneReason(DoneReason.REJECTED);
        if (order.getOrderId() == 0) {
            order.setOrderId(orderId);
        }
    }

    public ApplyResult applyEngineEvent(EngineEvent e) {

        if (e.seq() <= 0) {
            throw new IllegalStateException("engine event seq must be positive: " + e);
        }
        Long last = lastApplied.get(e.symbol());
        if (last != null) {
            if (e.seq() <= last) {
                return ApplyResult.duplicate(); // seen it already (the bridge resends after a restart): change nothing
            }
            if (e.seq() != last + 1) {
                throw new IllegalStateException("gap in engine events for symbol " + e.symbol() + ": last applied "
                        + last + ", got " + e.seq() + " (" + (e.seq() - last - 1) + " missing)");
            }
        }

        ApplyResult result = switch (e.kind()) {
            case ACCEPTED -> applyAccepted(e);
            case TRADE -> applyTrade(e);
            case CANCELLED -> applyRemainderEnded(e, DoneReason.CANCELED);
            case EXPIRED -> applyRemainderEnded(e, DoneReason.EXPIRED);
            case REJECTED -> applyRejected(e);
            case RESTED, HALTED, OTHER -> ApplyResult.ignored(); // no money or shares move
        };

        lastApplied.put(e.symbol(), e.seq());
        return result;
    }

    public OptionalLong lastApplied(int symbol) {
        Long last = lastApplied.get(symbol);
        return last == null ? OptionalLong.empty() : OptionalLong.of(last);
    }

    private ApplyResult applyAccepted(EngineEvent e) {
        OrderKey key = new OrderKey(e.ownerId(), e.clientOrderId());
        Order order = orders.get(key);
        if (order == null) {
            warnNotOurs(e);
            return ApplyResult.ignored();
        }

        if (order.getOrderId() != 0 && order.getOrderId() != e.orderId()) {
            throw new IllegalStateException("order " + key + " has engine id " + order.getOrderId()
                    + " but the ACCEPTED event names " + e.orderId());
        }
        onAccepted(key, e.orderId());
        return ApplyResult.applied(List.of(buildChange(e, order, AccountChangeKind.NEW, 0, 0)));
    }

    private ApplyResult applyTrade(EngineEvent e) {
        if (e.priceMinor() == null) {
            throw new IllegalStateException("TRADE without a price: " + e);
        }
        long price = e.priceMinor();
        EngineRef aggressorRef = new EngineRef(e.symbol(), e.orderId());
        EngineRef passiveRef = new EngineRef(e.symbol(), e.passiveId());
        Order aggressor = orders.get(byEngineRef.get(aggressorRef));
        Order passive = orders.get(byEngineRef.get(passiveRef));

        if (aggressor == null && passive == null) {
            warnNotOurs(e);
            return ApplyResult.ignored();
        }
        if (aggressor == null || passive == null) {
            warnNotOurs(e); // half ours: settle our side only
        }

        // Check everything for both sides first...
        if (aggressor != null) {
            checkTradeSide(e, aggressor, e.side());
            checkFill(aggressorRef, price, e.qty());
        }
        if (passive != null) {
            checkTradeSide(e, passive, e.side() == Side.BUY ? Side.SELL : Side.BUY);
            checkFill(passiveRef, price, e.qty());
        }

        if (aggressor != null) {
            onFill(aggressorRef, price, e.qty());
        }
        if (passive != null) {
            onFill(passiveRef, price, e.qty());
        }

        List<AccountChange> changes = new ArrayList<>();
        if (aggressor != null) {
            changes.add(fillChange(e, aggressor, price));
        }
        if (passive != null) {
            changes.add(fillChange(e, passive, price));
        }
        return ApplyResult.applied(changes);
    }

    private AccountChange fillChange(EngineEvent e, Order order, long price) {
        AccountChangeKind kind = order.getStage() == OrderStage.DONE ? AccountChangeKind.FILL
                : AccountChangeKind.PARTIAL_FILL;
        return buildChange(e, order, kind, price, e.qty());
    }

    private AccountChange buildChange(EngineEvent e, Order order, AccountChangeKind kind, long fillPriceMinor,
            long fillQty) {
        Account account = accounts.get(order.getKey().accountId());
        BalancesView balances = new BalancesView(account.getFreeCashMinor(), account.getLockedCashMinor());
        boolean fill = kind == AccountChangeKind.PARTIAL_FILL || kind == AccountChangeKind.FILL;
        boolean sellEnded = order.getSide() == Side.SELL && kind != AccountChangeKind.NEW;
        HoldingView position = null;
        if (fill || sellEnded) {
            Holding holding = account.getHoldings().get(order.getSymbol());
            if (holding == null) {
                throw new IllegalStateException("no holding for symbol " + order.getSymbol() + " on account "
                        + account.getAccountId() + " after " + kind + " of " + order.getKey());
            }
            position = HoldingView.of(holding);
        }
        long seq = account.nextAccountSeq();
        return new AccountChange(account.getAccountId(), seq, kind, OrderView.of(order), fillPriceMinor, fillQty,
                e.seq(), e.ts(), balances, position);
    }

    private void checkTradeSide(EngineEvent e, Order order, Side expectedSide) {
        if (order.getSide() != expectedSide) {
            throw new IllegalStateException("order " + order.getKey() + " is a " + order.getSide()
                    + " but the TRADE has it as a " + expectedSide + ": " + e);
        }
        long expectedAccount = expectedSide == Side.BUY ? e.ownerId() : e.otherOwnerId();
        if (order.getKey().accountId() != expectedAccount) {
            throw new IllegalStateException("order " + order.getKey() + " belongs to account "
                    + order.getKey().accountId() + " but the TRADE names account " + expectedAccount + ": " + e);
        }
    }

    private ApplyResult applyRemainderEnded(EngineEvent e, DoneReason reason) {
        EngineRef ref = new EngineRef(e.symbol(), e.orderId());
        if (!byEngineRef.containsKey(ref)) {
            warnNotOurs(e);
            return ApplyResult.ignored();
        }
        onRemainderEnded(ref, e.qty(), reason);
        Order order = orders.get(byEngineRef.get(ref));
        AccountChangeKind kind = switch (reason) {
            case CANCELED -> AccountChangeKind.CANCELED;
            case EXPIRED -> AccountChangeKind.EXPIRED;
            default -> throw new IllegalArgumentException("reason must be CANCELED or EXPIRED: " + reason);
        };
        return ApplyResult.applied(List.of(buildChange(e, order, kind, 0, 0)));
    }

    private ApplyResult applyRejected(EngineEvent e) {
        OrderKey key = new OrderKey(e.ownerId(), e.clientOrderId());
        if (!orders.containsKey(key)) {
            warnNotOurs(e);
            return ApplyResult.ignored();
        }
        onEngineRejected(key, e.orderId());
        return ApplyResult.applied(List.of(buildChange(e, orders.get(key), AccountChangeKind.REJECTED, 0, 0)));
    }

    private static void warnNotOurs(EngineEvent e) {
        log.warn("engine event for an order not in the ledger (engine console or before a restart): {}", e);
    }
}
