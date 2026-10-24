package com.protrader.cash_owner.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.cash_owner.owner.LedgerOwner;

class DevSeedRunnerTest {

    @TempDir
    Path dir;

    private CashOwnerProperties props() {
        return new CashOwnerProperties(null, null,
                new CashOwnerProperties.Owner(100, true, Duration.ofSeconds(2)),
                new CashOwnerProperties.DevSeed(
                        List.of(new CashOwnerProperties.SeedAccount(1, 5_000, Map.of(1, 7L)))),
                new CashOwnerProperties.Persistence(true, dir.toString(), 1000));
    }

    @Test
    void givenAPersistentDir_whenStartedTwice_thenTheFirstStartSeedsAndTheSecondSkips() throws Exception {
        CashOwnerProperties props = props();

        LedgerOwner first = new LedgerOwner(props);
        first.start();
        try {
            assertTrue(first.recoveredEmpty());
            new DevSeedRunner(first, props).run(null);
            assertEquals(5_000, first.request(l -> l.account(1).orElseThrow().freeCashMinor()).get(5, TimeUnit.SECONDS));
        } finally {
            first.stop();
        }

        LedgerOwner second = new LedgerOwner(props);
        second.start();
        try {
            assertFalse(second.recoveredEmpty());
            new DevSeedRunner(second, props).run(null); // would fail with "account already exists" if it seeded
            assertTrue(second.isHealthy());
            assertEquals(5_000, second.request(l -> l.account(1).orElseThrow().freeCashMinor()).get(5, TimeUnit.SECONDS));
            assertEquals(1, second.request(l -> l.accountCount()).get(5, TimeUnit.SECONDS));
        } finally {
            second.stop();
        }
    }
}
