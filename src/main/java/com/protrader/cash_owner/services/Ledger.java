package com.protrader.cash_owner.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.protrader.cash_owner.models.Account;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.Holding;
import com.protrader.cash_owner.models.Order;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.RejectReason;
import com.protrader.cash_owner.models.Side;

public final class Ledger {
    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<OrderKey, Order> orders = new LinkedHashMap<>();
    private final Map<EngineRef, OrderKey> byEngineRef = new HashMap<>();

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
            return new PlaceResult.Duplicate(existing);
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
        return new PlaceResult.Locked(order);
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
        order.setRemainingQty(0);
        order.setStage(OrderStage.DONE);
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

    public List<Order> inDoubt() {
        List<Order> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.getStage() == OrderStage.IN_DOUBT) {
                result.add(order);
            }
        }
        return result;
    }

    public Optional<Account> account(long accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public Optional<Order> order(OrderKey key) {
        return Optional.ofNullable(orders.get(key));
    }

    public Optional<Order> orderByEngineRef(EngineRef ref) {
        OrderKey key = byEngineRef.get(ref);
        return key == null ? Optional.empty() : Optional.ofNullable(orders.get(key));
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
    }
}
