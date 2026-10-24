package com.protrader.cash_owner.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cash-owner")
public record CashOwnerProperties(Kafka kafka, Sequencer sequencer, Owner owner, DevSeed devSeed, Persistence persistence) {

    public record Persistence(boolean enabled, String dataDir, int snapshotEvery) {
    }

    public record Kafka(String engineEventsTopic, String accountUpdatesTopic) {
    }

    public record Sequencer(boolean enabled, Duration reconnectMin, Duration reconnectMax) {
    }

    public record Owner(int maxPendingRequests, boolean checkInvariants, Duration shutdownDrain) {
    }

    /** Accounts opened at startup for development. May be missing (treated as empty). */
    public record DevSeed(List<SeedAccount> accounts) {
    }

    public record SeedAccount(long id, long cashMinor, Map<Integer, Long> shares) {
    }
}
