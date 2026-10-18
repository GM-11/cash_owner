package com.protrader.cash_owner.ledger;

/** order_id is unique per partition only, so a ref is always (symbol, orderId). */
public record EngineRef(int symbol, long orderId) {
}
