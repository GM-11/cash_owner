package com.protrader.cash_owner.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The "cash-owner:" block of application.yaml. */
@ConfigurationProperties("cash-owner")
public record CashOwnerProperties(Kafka kafka, Sequencer sequencer) {

    public record Kafka(String engineEventsTopic, String accountUpdatesTopic) {}

    public record Sequencer(boolean enabled, Duration reconnectMin, Duration reconnectMax) {}
}
