package com.protrader.cash_owner.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.protrader.proto.exchange.v1.MarketEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EngineEventsListener {

    private final EngineEventHandler handler;

    public EngineEventsListener(EngineEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${cash-owner.kafka.engine-events-topic}")
    public void on(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        handler.onEngineEvent(MarketEvent.parseFrom(record.value()));
    }
}
