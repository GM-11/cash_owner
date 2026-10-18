package com.protrader.cash_owner.kafka;

import com.protrader.proto.exchange.v1.MarketEvent;

public interface EngineEventHandler {
    void onEngineEvent(MarketEvent event);
}
