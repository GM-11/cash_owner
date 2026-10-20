package com.protrader.cash_owner.kafka;

import com.protrader.proto.exchange.v1.MarketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Replaced by SettlementHandler (step 5). Not a bean any more; safe to delete. */
public class LoggingEngineEventHandler implements EngineEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingEngineEventHandler.class);

    @Override
    public void onEngineEvent(MarketEvent e) {
        log.info("engine event: symbol={} seq={} kind={} order={} qty={}",
                e.getSymbol(), e.getSeq(), e.getKind(), e.getOrderId(), e.getQuantity());
    }
}
