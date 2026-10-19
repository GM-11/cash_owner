package com.protrader.cash_owner.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cash-owner")
public record CashOwnerProperties(Kafka kafka, Sequencer sequencer, Owner owner) {

    public record Kafka(String engineEventsTopic, String accountUpdatesTopic) {
    }

    public record Sequencer(boolean enabled, Duration reconnectMin, Duration reconnectMax) {
    }

    public record Owner(int maxPendingRequests, boolean checkInvariants, Duration shutdownDrain) {
    }
}
