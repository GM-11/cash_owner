package com.protrader.cash_owner.seed;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.cash_owner.owner.LedgerOwner;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Opens the configured dev accounts through the owner thread at startup. */
@Component
public class DevSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    private final LedgerOwner owner;
    private final List<CashOwnerProperties.SeedAccount> accounts;

    public DevSeedRunner(LedgerOwner owner, CashOwnerProperties props) {
        this.owner = owner;
        CashOwnerProperties.DevSeed seed = props.devSeed();
        this.accounts = seed == null || seed.accounts() == null ? List.of() : seed.accounts();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!owner.recoveredEmpty()) {
            log.info("skipping dev seed: persistent ledger already has state");
            return;
        }
        for (CashOwnerProperties.SeedAccount account : accounts) {
            Map<Integer, Long> shares = account.shares() == null ? Map.of() : account.shares();
            owner.followUp(l -> {
                l.openAccount(account.id(), account.cashMinor(), shares);
                return null;
            }).get(5, TimeUnit.SECONDS);
            log.info("seeded account {}", account.id());
        }
    }
}
