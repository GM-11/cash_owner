package com.protrader.cash_owner.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            account.holding(e.getKey()).freeQty = e.getValue();
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
            if (account.freeCashMinor < need) {
                return new PlaceResult.Rejected(RejectReason.INSUFFICIENT_CASH, need - account.freeCashMinor);
            }
            account.freeCashMinor -= need;
            account.lockedCashMinor += need;
        } else {
            Holding currentHolding = account.holdings.get(symbol);
            long freeQty = currentHolding == null ? 0 : currentHolding.freeQty;
            if (freeQty < qty) {
                return new PlaceResult.Rejected(RejectReason.INSUFFICIENT_HOLDINGS, 0);
            }
            Holding holding = account.holding(symbol);
            holding.freeQty -= qty;
            holding.lockedQty += qty;
        }

        Order order = new Order(key, symbol, side, limitPriceMinor, qty);
        orders.put(key, order);
        return new PlaceResult.Locked(order);
    }

    public void onAccepted(OrderKey key, long orderId) {
        Order order = orders.get(key);
        if (order == null || (order.stage != OrderStage.SENT && order.stage != OrderStage.IN_DOUBT)) {
            return;
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive: " + orderId);
        }
        EngineRef ref = new EngineRef(order.symbol, orderId);
        OrderKey clashing = byEngineRef.get(ref);
        if (clashing != null && !clashing.equals(key)) {
            throw new IllegalStateException(
                    "engine ref " + ref + " already maps to " + clashing + ", cannot also map to " + key);
        }
        order.orderId = orderId;
        order.stage = OrderStage.OPEN;
        byEngineRef.put(ref, key);
    }

    public void onRefused(OrderKey key) {
        Order order = orders.get(key);
        if (order == null || (order.stage != OrderStage.SENT && order.stage != OrderStage.IN_DOUBT)) {
            return;
        }
        releaseLock(order);
        order.remainingQty = 0;
        order.stage = OrderStage.DONE;
    }

    public void onNoAnswer(OrderKey key) {
        Order order = orders.get(key);
        if (order == null || order.stage != OrderStage.SENT) {
            return;
        }
        order.stage = OrderStage.IN_DOUBT;
    }

    private void releaseLock(Order order) {
        Account account = accounts.get(order.key.accountId());
        if (order.side == Side.BUY) {
            long amount = Math.multiplyExact(order.limitPriceMinor, order.remainingQty);
            account.lockedCashMinor -= amount;
            account.freeCashMinor += amount;
        } else {
            Holding holding = account.holding(order.symbol);
            holding.lockedQty -= order.remainingQty;
            holding.freeQty += order.remainingQty;
        }
    }

    public List<Order> inDoubt() {
        List<Order> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.stage == OrderStage.IN_DOUBT) {
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
            if (account.freeCashMinor < 0 || account.lockedCashMinor < 0) {
                throw new IllegalStateException("negative cash for account " + account.accountId + ": free="
                        + account.freeCashMinor + " locked=" + account.lockedCashMinor);
            }
            for (Map.Entry<Integer, Holding> e : account.holdings.entrySet()) {
                Holding h = e.getValue();
                if (h.freeQty < 0 || h.lockedQty < 0) {
                    throw new IllegalStateException("negative shares for account " + account.accountId + " symbol "
                            + e.getKey() + ": free=" + h.freeQty + " locked=" + h.lockedQty);
                }
            }
        }

        Map<Long, Long> expectedLockedCash = new HashMap<>();
        Map<Long, Map<Integer, Long>> expectedLockedQty = new HashMap<>();
        for (Order order : orders.values()) {
            if (order.stage != OrderStage.SENT && order.stage != OrderStage.IN_DOUBT
                    && order.stage != OrderStage.OPEN) {
                continue;
            }
            long accountId = order.key.accountId();
            if (order.side == Side.BUY) {
                long amount = Math.multiplyExact(order.limitPriceMinor, order.remainingQty);
                expectedLockedCash.merge(accountId, amount, Long::sum);
            } else {
                expectedLockedQty.computeIfAbsent(accountId, a -> new HashMap<>())
                        .merge(order.symbol, order.remainingQty, Long::sum);
            }
        }

        for (Account account : accounts.values()) {
            long expectedCash = expectedLockedCash.getOrDefault(account.accountId, 0L);
            if (account.lockedCashMinor != expectedCash) {
                throw new IllegalStateException("locked cash mismatch for account " + account.accountId + ": locked="
                        + account.lockedCashMinor + " expected=" + expectedCash);
            }

            Map<Integer, Long> expectedQtyBySymbol = expectedLockedQty.getOrDefault(account.accountId, Map.of());
            for (Map.Entry<Integer, Long> e : expectedQtyBySymbol.entrySet()) {
                Holding holding = account.holdings.get(e.getKey());
                long actual = holding == null ? 0 : holding.lockedQty;
                if (actual != e.getValue()) {
                    throw new IllegalStateException("locked qty mismatch for account " + account.accountId
                            + " symbol " + e.getKey() + ": locked=" + actual + " expected=" + e.getValue());
                }
            }
            for (Map.Entry<Integer, Holding> e : account.holdings.entrySet()) {
                if (!expectedQtyBySymbol.containsKey(e.getKey()) && e.getValue().lockedQty != 0) {
                    throw new IllegalStateException("locked qty mismatch for account " + account.accountId
                            + " symbol " + e.getKey() + ": locked=" + e.getValue().lockedQty + " expected=0");
                }
            }
        }

        for (Map.Entry<EngineRef, OrderKey> e : byEngineRef.entrySet()) {
            EngineRef ref = e.getKey();
            Order order = orders.get(e.getValue());
            if (order == null || order.symbol != ref.symbol() || order.orderId != ref.orderId()) {
                throw new IllegalStateException("byEngineRef entry " + ref + " does not match its order");
            }
        }
    }
}
