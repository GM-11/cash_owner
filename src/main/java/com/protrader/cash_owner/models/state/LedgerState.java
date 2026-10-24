package com.protrader.cash_owner.models.state;

import java.util.List;
import java.util.Map;

import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Side;

/**
 * Plain copy of everything a Ledger holds, for snapshot files. No behaviour. Maps are sorted and
 * {@code orders} keeps the Ledger's insertion order (oldest first), so two equal Ledgers export equal states.
 */
public record LedgerState(Map<Long, AccountState> accounts, List<OrderState> orders,
        List<EngineRefEntry> byEngineRef, Map<Integer, Long> lastApplied) {

    public record AccountState(long accountId, long freeCashMinor, long lockedCashMinor, long accountSeq,
            long realizedPnlMinorTotal, Map<Integer, HoldingState> holdings) {
    }

    public record HoldingState(long freeQty, long lockedQty, long totalCostMinor, long realizedPnlMinorForSymbol) {
    }

    public record OrderState(long accountId, String clientOrderId, int symbol, Side side, long limitPriceMinor,
            long originalQty, long remainingQty, long filledQty, long filledNotionalMinor, long orderId,
            OrderStage stage, DoneReason doneReason, boolean cancelPending) {
    }

    /** byEngineRef: (symbol, orderId) -> the order's (accountId, clientOrderId). */
    public record EngineRefEntry(int symbol, long orderId, long accountId, String clientOrderId) {
    }
}
