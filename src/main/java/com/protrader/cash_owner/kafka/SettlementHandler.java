package com.protrader.cash_owner.kafka;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.Outcome;
import com.protrader.cash_owner.owner.LedgerOwner;
import com.protrader.proto.exchange.v1.MarketEvent;

import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;

@Component
public class SettlementHandler implements EngineEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SettlementHandler.class);

    private final LedgerOwner owner;
    private final Optional<HealthStatusManager> health;

    private volatile boolean stopped;
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    public SettlementHandler(LedgerOwner owner, Optional<HealthStatusManager> health) {
        this.owner = owner;
        this.health = health;
    }

    @Override
    public void onEngineEvent(MarketEvent proto) {
        if (stopped) {
            throw new IllegalStateException("settlement is stopped after an earlier failure; not applying " + proto);
        }

        EngineEvent event;
        try {
            event = EngineEventMapper.fromProto(proto);
        } catch (RuntimeException e) {
            throw stop("could not read engine event " + proto, e);
        }

        Outcome outcome;
        try {
            outcome = owner.followUp(ledger -> ledger.applyEngineEvent(event)).get(); // wait: see class comment
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down
            throw stop("interrupted while settling " + event, e);
        } catch (ExecutionException e) {
            throw stop("could not settle " + event, e.getCause()); // books disagree, or the owner is unavailable
        }

        switch (outcome) {
            case APPLIED -> applied.incrementAndGet();
            case DUPLICATE -> {
                duplicates.incrementAndGet();
                log.debug("duplicate engine event skipped: {}", event);
            }
            case IGNORED -> ignored.incrementAndGet();
        }
    }

    private RuntimeException stop(String what, Throwable cause) {
        stopped = true;
        health.ifPresent(h -> h.setStatus("", ServingStatus.NOT_SERVING));
        log.error("SETTLEMENT STOPPED: {}. No more engine events will be applied. Fix the cause and restart the "
                + "cash owner; this event's Kafka offset was not committed, so it will be read again.", what, cause);
        return new SettlementStoppedException(what, cause);
    }

    /** True once a failure stopped settlement; stays true until restart. */
    public boolean isStopped() {
        return stopped;
    }

    public long appliedCount() {
        return applied.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long ignoredCount() {
        return ignored.get();
    }

    public static final class SettlementStoppedException extends RuntimeException {
        public SettlementStoppedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
