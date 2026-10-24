package com.protrader.cash_owner.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.protrader.cash_owner.models.Account;
import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.ApplyResult;
import com.protrader.cash_owner.models.CancelDecision;
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
import com.protrader.cash_owner.models.TransferKey;
import com.protrader.cash_owner.models.TransferRecord;
import com.protrader.cash_owner.models.TransferResult;
import com.protrader.cash_owner.models.views.TransferView;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.HoldingView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.models.state.LedgerState;
import com.protrader.cash_owner.persistence.LedgerCommand;

public final class Ledger {
    private static final Logger log = LoggerFactory.getLogger(Ledger.class);

    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<OrderKey, Order> orders = new LinkedHashMap<>();
    private final Map<EngineRef, OrderKey> byEngineRef = new HashMap<>();
    private final Map<Integer, Long> lastApplied = new HashMap<>();
    /** Applied transfers only, to answer repeats. */
    private final Map<TransferKey, TransferRecord> transfers = new LinkedHashMap<>();

    private static final Pattern TRANSFER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,48}$");

    /**
     * Called with every input that changed state, at the successful end of the public method that took it and
     * before that method returns. Internal calls never record. Off (no-op) while replaying a journal.
     */
    private Consumer<LedgerCommand> recorder = command -> {
    };

    public void setRecorder(Consumer<LedgerCommand> recorder) {
        this.recorder = recorder == null ? command -> {
        } : recorder;
    }

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
            holdings.put(e.getKey(), new Holding(e.getValue(), 0, 0, 0));
        }
        accounts.put(accountId, account);
        recorder.accept(new LedgerCommand.OpenAccount(accountId, cashMinor, new TreeMap<>(shares)));
    }

    public PlaceResult place(long accountId, String clientOrderId, int symbol, Side side, long limitPriceMinor,
            long qty) {
        PlaceResult result = checkAndLock(accountId, clientOrderId, symbol, side, limitPriceMinor, qty);
        if (result instanceof PlaceResult.Locked) {
            recorder.accept(new LedgerCommand.Place(accountId, clientOrderId, symbol, side, limitPriceMinor, qty));
        }
        return result;
    }

    private PlaceResult checkAndLock(long accountId, String clientOrderId, int symbol, Side side,
            long limitPriceMinor, long qty) {
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
        if (acceptOrder(key, orderId)) {
            recorder.accept(new LedgerCommand.Accepted(key, orderId));
        }
    }

    /** True when the order changed. */
    private boolean acceptOrder(OrderKey key, long orderId) {
        Order order = orders.get(key);
        if (order == null || (order.getStage() != OrderStage.SENT && order.getStage() != OrderStage.IN_DOUBT)) {
            return false;
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
        return true;
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
        recorder.accept(new LedgerCommand.Refused(key));
    }

    public void onNoAnswer(OrderKey key) {
        Order order = orders.get(key);
        if (order == null || order.getStage() != OrderStage.SENT) {
            return;
        }
        order.setStage(OrderStage.IN_DOUBT);
        recorder.accept(new LedgerCommand.NoAnswer(key));
    }

    /**
     * A cancel request for an open order. Moves no money: only the engine's CANCELLED event unlocks anything.
     * Repeating a cancel is harmless to the engine, so a repeat answers SEND again.
     */
    public CancelDecision requestCancel(long accountId, EngineRef ref) {
        OrderKey key = byEngineRef.get(ref);
        Order order = key == null ? null : orders.get(key);
        if (order == null || order.getKey().accountId() != accountId) {
            return CancelDecision.NOT_FOUND; // never reveal that someone else's order exists
        }
        return switch (order.getStage()) {
            case DONE -> CancelDecision.CLOSED;
            case OPEN -> {
                order.setCancelPending(true);
                recorder.accept(new LedgerCommand.RequestCancel(accountId, ref));
                yield CancelDecision.SEND;
            }
            default -> CancelDecision.NOT_FOUND;
        };
    }

    /** The sequencer gave a definite no to a cancel: it never reached the engine. */
    public void onCancelRefused(EngineRef ref) {
        OrderKey key = byEngineRef.get(ref);
        Order order = key == null ? null : orders.get(key);
        if (order == null || order.getStage() != OrderStage.OPEN || !order.isCancelPending()) {
            return; // a late answer, or nothing to clear
        }
        order.setCancelPending(false);
        recorder.accept(new LedgerCommand.CancelRefused(ref));
    }

    public TransferResult deposit(long accountId, String transferId, long amountMinor, long tsMillis) {
        return transfer(true, accountId, transferId, amountMinor, tsMillis);
    }

    public TransferResult withdraw(long accountId, String transferId, long amountMinor, long tsMillis) {
        return transfer(false, accountId, transferId, amountMinor, tsMillis);
    }

    /**
     * Checks everything, then changes. A deposit creates a missing account as part of the one recorded command.
     * Only an applied transfer is remembered (and recorded); a refused one may be retried with the same id.
     */
    private TransferResult transfer(boolean deposit, long accountId, String transferId, long amountMinor,
            long tsMillis) {
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive: " + accountId);
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive: " + amountMinor);
        }
        if (transferId == null || !TRANSFER_ID.matcher(transferId).matches()) {
            throw new IllegalArgumentException("transferId must be 1-48 chars of [A-Za-z0-9_-]: " + transferId);
        }
        if (tsMillis < 0) {
            throw new IllegalArgumentException("tsMillis must not be negative: " + tsMillis);
        }

        TransferKey key = new TransferKey(accountId, transferId);
        Account account = accounts.get(accountId);
        TransferRecord earlier = transfers.get(key);
        if (earlier != null) {
            if (earlier.deposit() != deposit || earlier.amountMinor() != amountMinor) {
                log.warn("transfer {} on account {} repeated with different content: stored {} {}, got {} {}",
                        transferId, accountId, earlier.deposit() ? "deposit" : "withdraw", earlier.amountMinor(),
                        deposit ? "deposit" : "withdraw", amountMinor);
            }
            return new TransferResult.Duplicate(balancesOf(account), account.getAccountSeq());
        }
        if (account == null && !deposit) {
            return new TransferResult.UnknownAccount();
        }

        long free = account == null ? 0 : account.getFreeCashMinor();
        long newFree;
        if (deposit) {
            try {
                newFree = Math.addExact(free, amountMinor);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("deposit of " + amountMinor + " overflows free cash " + free, e);
            }
        } else {
            if (free < amountMinor) {
                return new TransferResult.InsufficientCash(amountMinor - free, balancesOf(account),
                        account.getAccountSeq());
            }
            newFree = free - amountMinor;
        }

        if (account == null) {
            account = new Account(accountId, 0, 0);
            accounts.put(accountId, account);
        }
        account.setFreeCashMinor(newFree);
        transfers.put(key, new TransferRecord(deposit, amountMinor, tsMillis));
        long seq = account.nextAccountSeq();
        AccountChange change = new AccountChange(accountId, seq, AccountChangeKind.TRANSFER, null, 0, 0, 0,
                tsMillis, balancesOf(account), null, new TransferView(transferId, deposit, amountMinor));
        recorder.accept(new LedgerCommand.Transfer(accountId, transferId, deposit, amountMinor, tsMillis));
        return new TransferResult.Applied(change);
    }

    private static BalancesView balancesOf(Account account) {
        return new BalancesView(account.getFreeCashMinor(), account.getLockedCashMinor(),
                account.getRealizedPnlMinorTotal());
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

    /** Orders sent to the sequencer whose ack has not come yet, oldest first. Copies only. */
    public List<OrderView> sentOrders() {
        List<OrderView> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.getStage() == OrderStage.SENT) {
                result.add(OrderView.of(order));
            }
        }
        return result;
    }

    public int accountCount() { return accounts.size(); }

    public long openOrdersCount() {
        return orders.values().stream().filter(order -> order.getStage() != OrderStage.DONE).count();
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
        for (TransferKey key : transfers.keySet()) {
            if (!accounts.containsKey(key.accountId())) {
                throw new IllegalStateException("transfer " + key + " belongs to an unknown account");
            }
        }
        for (Account account : accounts.values()) {
            if (account.getFreeCashMinor() < 0 || account.getLockedCashMinor() < 0) {
                throw new IllegalStateException("negative cash for account " + account.getAccountId() + ": free="
                        + account.getFreeCashMinor() + " locked=" + account.getLockedCashMinor());
            }
            long totalRealizedPnl = 0;
            for (Map.Entry<Integer, Holding> e : account.getHoldings().entrySet()) {
                Holding h = e.getValue();
                totalRealizedPnl += h.getRealizedPnlMinorForSymbol();
                if (h.getFreeQty() < 0 || h.getLockedQty() < 0) {
                    throw new IllegalStateException("negative shares for account " + account.getAccountId() + " symbol "
                            + e.getKey() + ": free=" + h.getFreeQty() + " locked=" + h.getLockedQty());
                }
                if (h.getTotalCostMinor() < 0) {
                    throw new IllegalStateException("negative cost for account " + account.getAccountId() + " symbol "
                            + e.getKey() + ": totalCost=" + h.getTotalCostMinor());
                }
                if (h.getFreeQty() + h.getLockedQty() == 0 && h.getTotalCostMinor() != 0) {
                    throw new IllegalStateException(
                            "negative total shares for account " + account.getAccountId() + " symbol "
                                    + e.getKey() + ": free=" + h.getFreeQty() + " locked=" + h.getLockedQty()
                                    + " totalCost=" + h.getTotalCostMinor());
                }
            }

            if (totalRealizedPnl != account.getRealizedPnlMinorTotal()) {
                throw new IllegalStateException("realized PnL mismatch for account " + account.getAccountId()
                        + ": totalRealizedPnl=" + totalRealizedPnl + " realizedPnlTotal="
                        + account.getRealizedPnlMinorTotal());
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
            if (order.getStage() == OrderStage.DONE && order.isCancelPending()) {
                throw new IllegalStateException("DONE order " + order.getKey() + " still has a cancel pending");
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
        Holding holding = account.getHoldings().get(order.getSymbol());
        if (order.getSide() == Side.BUY) {
            if (tradePriceMinor > limitPrice) {
                throw new IllegalArgumentException(
                        "buy filled at " + tradePriceMinor + ", above its limit " + limitPrice);
            }
        } else {
            if (holding == null) {
                throw new IllegalStateException("no holding for symbol " + order.getSymbol());
            }
            if (tradePriceMinor < limitPrice) {
                throw new IllegalArgumentException(
                        "sell filled at " + tradePriceMinor + ", below its limit " + limitPrice);
            }
        }

        // the amounts onFill will compute; checked here so an overflow throws before
        // anything changes
        long priceTimesQty = Math.multiplyExact(tradePriceMinor, qty);
        Math.multiplyExact(limitPrice, qty);
        if (order.getSide() == Side.BUY) {
            // a buy may have no holding yet; onFill creates it with zero cost
            long currentCost = holding == null ? 0 : holding.getTotalCostMinor();
            Math.addExact(currentCost, priceTimesQty);
        } else {
            Math.multiplyExact(holding.getTotalCostMinor(), qty);
        }
        return order;
    }

    public void onFill(EngineRef ref, long tradePriceMinor, long qty) {
        fillOrder(ref, tradePriceMinor, qty);
        Order filled = orders.get(byEngineRef.get(ref));
        if (filled.getStage() == OrderStage.DONE) {
            filled.setCancelPending(false); // the trade path clears it in addFillChanges
        }
        recorder.accept(new LedgerCommand.Fill(ref, tradePriceMinor, qty));
    }

    private void fillOrder(EngineRef ref, long tradePriceMinor, long qty) {
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
            holding.setTotalCostMinor(Math.addExact(holding.getTotalCostMinor(), priceTimesQty));
        } else {
            Holding holding = account.getHoldings().get(order.getSymbol());
            long proceeds = priceTimesQty;
            long heldQty = holding.getLockedQty() + holding.getFreeQty();
            long costRemoved = Math.divideExact(Math.multiplyExact(holding.getTotalCostMinor(), qty), heldQty);
            long pnl = Math.multiplyExact(tradePriceMinor, qty) - costRemoved;

            account.setFreeCashMinor(account.getFreeCashMinor() + proceeds);
            holding.setLockedQty(holding.getLockedQty() - qty);
            holding.setTotalCostMinor(holding.getTotalCostMinor() - costRemoved);
            holding.setRealizedPnlMinorForSymbol(holding.getRealizedPnlMinorForSymbol() + pnl);
            account.setRealizedPnlMinorTotal(account.getRealizedPnlMinorTotal() + pnl);
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
        endRemainder(ref, qty, reason);
        recorder.accept(new LedgerCommand.RemainderEnded(ref, qty, reason));
    }

    private void endRemainder(EngineRef ref, long qty, DoneReason reason) {
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
        order.setCancelPending(false);
    }

    public void onEngineRejected(OrderKey key, long orderId) {
        rejectOrder(key, orderId);
        recorder.accept(new LedgerCommand.EngineRejected(key, orderId));
    }

    private void rejectOrder(OrderKey key, long orderId) {
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
        order.setCancelPending(false);
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
        recorder.accept(new LedgerCommand.EngineEventApplied(e)); // APPLIED and IGNORED; DUPLICATE returned above
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
        acceptOrder(key, e.orderId());
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
            fillOrder(aggressorRef, price, e.qty());
        }
        if (passive != null) {
            fillOrder(passiveRef, price, e.qty());
        }

        List<AccountChange> changes = new ArrayList<>();
        if (aggressor != null) {
            addFillChanges(changes, e, aggressor, price);
        }
        if (passive != null) {
            addFillChanges(changes, e, passive, price);
        }
        return ApplyResult.applied(changes);
    }

    /**
     * The fill's change; plus a CANCEL_REJECTED when the fill finished an order that had a cancel pending: events
     * per symbol are strictly ordered, so the cancel reached the engine too late.
     */
    private void addFillChanges(List<AccountChange> changes, EngineEvent e, Order order, long price) {
        changes.add(fillChange(e, order, price));
        if (order.getStage() == OrderStage.DONE && order.getDoneReason() == DoneReason.FILLED
                && order.isCancelPending()) {
            order.setCancelPending(false);
            changes.add(buildChange(e, order, AccountChangeKind.CANCEL_REJECTED, 0, 0));
        }
    }

    private AccountChange fillChange(EngineEvent e, Order order, long price) {
        AccountChangeKind kind = order.getStage() == OrderStage.DONE ? AccountChangeKind.FILL
                : AccountChangeKind.PARTIAL_FILL;
        return buildChange(e, order, kind, price, e.qty());
    }

    private AccountChange buildChange(EngineEvent e, Order order, AccountChangeKind kind, long fillPriceMinor,
            long fillQty) {
        Account account = accounts.get(order.getKey().accountId());
        BalancesView balances = new BalancesView(account.getFreeCashMinor(), account.getLockedCashMinor(),
                account.getRealizedPnlMinorTotal());
        boolean fill = kind == AccountChangeKind.PARTIAL_FILL || kind == AccountChangeKind.FILL;
        boolean sell = order.getSide() == Side.SELL;
        HoldingView position = null;
        if (fill || sell) {
            Holding holding = account.getHoldings().get(order.getSymbol());
            if (holding == null) {
                throw new IllegalStateException("no holding for symbol " + order.getSymbol() + " on account "
                        + account.getAccountId() + " after " + kind + " of " + order.getKey());
            }
            position = HoldingView.of(holding);
        }
        long seq = account.nextAccountSeq();
        return new AccountChange(account.getAccountId(), seq, kind, OrderView.of(order), fillPriceMinor, fillQty,
                e.seq(), e.ts(), balances, position, null);
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
        endRemainder(ref, e.qty(), reason);
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
        rejectOrder(key, e.orderId());
        return ApplyResult.applied(List.of(buildChange(e, orders.get(key), AccountChangeKind.REJECTED, 0, 0)));
    }

    private static void warnNotOurs(EngineEvent e) {
        log.warn("engine event for an order not in the ledger (engine console or before a restart): {}", e);
    }

    // ---------------------------------------------------------------- snapshot

    /** A plain copy of the whole state, in a fixed order, for a snapshot file. */
    public LedgerState exportState() {
        Map<Long, LedgerState.AccountState> accountStates = new TreeMap<>();
        for (Account account : accounts.values()) {
            Map<Integer, LedgerState.HoldingState> holdingStates = new TreeMap<>();
            for (Map.Entry<Integer, Holding> e : account.getHoldings().entrySet()) {
                Holding holding = e.getValue();
                holdingStates.put(e.getKey(), new LedgerState.HoldingState(holding.getFreeQty(),
                        holding.getLockedQty(), holding.getTotalCostMinor(), holding.getRealizedPnlMinorForSymbol()));
            }
            accountStates.put(account.getAccountId(),
                    new LedgerState.AccountState(account.getAccountId(), account.getFreeCashMinor(),
                            account.getLockedCashMinor(), account.getAccountSeq(),
                            account.getRealizedPnlMinorTotal(), holdingStates));
        }

        List<LedgerState.OrderState> orderStates = new ArrayList<>(); // insertion order is part of the state
        for (Order order : orders.values()) {
            orderStates.add(new LedgerState.OrderState(order.getKey().accountId(), order.getKey().clientOrderId(),
                    order.getSymbol(), order.getSide(), order.getLimitPriceMinor(), order.getOriginalQty(),
                    order.getRemainingQty(), order.getFilledQty(), order.getFilledNotionalMinor(), order.getOrderId(),
                    order.getStage(), order.getDoneReason(), order.isCancelPending()));
        }

        List<LedgerState.EngineRefEntry> refStates = new ArrayList<>();
        for (Map.Entry<EngineRef, OrderKey> e : byEngineRef.entrySet()) {
            refStates.add(new LedgerState.EngineRefEntry(e.getKey().symbol(), e.getKey().orderId(),
                    e.getValue().accountId(), e.getValue().clientOrderId()));
        }
        refStates.sort(Comparator.comparingInt(LedgerState.EngineRefEntry::symbol)
                .thenComparingLong(LedgerState.EngineRefEntry::orderId));

        List<LedgerState.TransferState> transferStates = new ArrayList<>();
        for (Map.Entry<TransferKey, TransferRecord> e : transfers.entrySet()) {
            transferStates.add(new LedgerState.TransferState(e.getKey().accountId(), e.getKey().transferId(),
                    e.getValue().deposit(), e.getValue().amountMinor(), e.getValue().ts()));
        }
        transferStates.sort(Comparator.comparingLong(LedgerState.TransferState::accountId)
                .thenComparing(LedgerState.TransferState::transferId));

        return new LedgerState(accountStates, orderStates, refStates, new TreeMap<>(lastApplied), transferStates);
    }

    /** The Ledger a snapshot describes. Throws when the state points at things that are not there. */
    public static Ledger fromState(LedgerState state) {
        Ledger ledger = new Ledger();
        for (LedgerState.AccountState accountState : state.accounts().values()) {
            Account account = new Account(accountState.accountId(), accountState.freeCashMinor(),
                    accountState.lockedCashMinor());
            account.restoreAccountSeq(accountState.accountSeq());
            account.setRealizedPnlMinorTotal(accountState.realizedPnlMinorTotal());
            for (Map.Entry<Integer, LedgerState.HoldingState> e : accountState.holdings().entrySet()) {
                LedgerState.HoldingState holding = e.getValue();
                account.getHoldings().put(e.getKey(), new Holding(holding.freeQty(), holding.lockedQty(),
                        holding.totalCostMinor(), holding.realizedPnlMinorForSymbol()));
            }
            ledger.accounts.put(account.getAccountId(), account);
        }
        for (LedgerState.OrderState orderState : state.orders()) {
            OrderKey key = new OrderKey(orderState.accountId(), orderState.clientOrderId());
            if (!ledger.accounts.containsKey(key.accountId())) {
                throw new IllegalArgumentException("order " + key + " belongs to an unknown account");
            }
            Order order = new Order(key, orderState.symbol(), orderState.side(), orderState.limitPriceMinor(),
                    orderState.originalQty());
            order.setRemainingQty(orderState.remainingQty());
            order.setFilledQty(orderState.filledQty());
            order.setFilledNotionalMinor(orderState.filledNotionalMinor());
            order.setOrderId(orderState.orderId());
            order.setStage(orderState.stage());
            order.setDoneReason(orderState.doneReason());
            order.setCancelPending(orderState.cancelPending());
            if (ledger.orders.put(key, order) != null) {
                throw new IllegalArgumentException("order " + key + " appears twice");
            }
        }
        for (LedgerState.EngineRefEntry entry : state.byEngineRef()) {
            OrderKey key = new OrderKey(entry.accountId(), entry.clientOrderId());
            if (!ledger.orders.containsKey(key)) {
                throw new IllegalArgumentException("engine ref " + entry + " points at a missing order");
            }
            ledger.byEngineRef.put(new EngineRef(entry.symbol(), entry.orderId()), key);
        }
        ledger.lastApplied.putAll(state.lastApplied());
        for (LedgerState.TransferState t : state.transfers()) {
            if (!ledger.accounts.containsKey(t.accountId())) {
                throw new IllegalArgumentException("transfer " + t.transferId() + " belongs to unknown account "
                        + t.accountId());
            }
            ledger.transfers.put(new TransferKey(t.accountId(), t.transferId()),
                    new TransferRecord(t.deposit(), t.amountMinor(), t.ts()));
        }
        return ledger;
    }
}
