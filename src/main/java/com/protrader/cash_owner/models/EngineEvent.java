package com.protrader.cash_owner.models;

public record EngineEvent(int symbol, long seq, EngineEventKind kind, long ts, long orderId, long passiveId,
        long ownerId, long otherOwnerId, Side side, Long priceMinor, long qty, String clientOrderId) {
}
