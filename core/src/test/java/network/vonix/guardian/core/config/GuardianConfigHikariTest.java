package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.3.1 X6 (P1-1 / P2-4): validates the {@code GuardianConfig.database.hikari}
 * nested record's defaults, backward-compat constructors, and validation
 * invariants.
 */
class GuardianConfigHikariTest {

    @Test
    void defaults_produce_sane_pool_and_timeouts() {
        var hk = GuardianConfig.Hikari.defaults();
        assertThat(hk.maxPoolSize()).isEqualTo(10);
        assertThat(hk.connectionTimeoutMs()).isEqualTo(30_000L);
        assertThat(hk.maxLifetimeMs()).isEqualTo(1_800_000L);
        assertThat(hk.leakDetectionMs()).isZero();
    }

    @Test
    void backwards_compat_database_ctor_fills_defaults() {
        var db = new GuardianConfig.Database("sqlite", "vg.db", null, null, null, null, GuardianConfig.Hikari.defaults());
        assertThat(db.hikari()).isNotNull();
        assertThat(db.hikari().maxPoolSize()).isEqualTo(10);
    }

    @Test
    void validation_rejects_negative_pool_size() {
        var bad = GuardianConfig.defaults();
        var badDb = new GuardianConfig.Database(
            "sqlite", "vg.db", null, null, null, null,
            new GuardianConfig.Hikari(0, 30_000L, 1_800_000L, 0L)
        );
        var cfg = new GuardianConfig(
            badDb, bad.queue(), bad.logFile(), bad.actions(),
            bad.permissions(), bad.lookup(), bad.privacy(), bad.purge(),
            bad.storage(),
        GuardianConfig.Rollback.defaults(), bad.theme(), bad.language()
        );
        assertThatThrownBy(cfg::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("database.hikari.maxPoolSize");
    }

    @Test
    void validation_rejects_absurdly_small_connection_timeout() {
        var bad = GuardianConfig.defaults();
        var badDb = new GuardianConfig.Database(
            "sqlite", "vg.db", null, null, null, null,
            new GuardianConfig.Hikari(10, 100L, 1_800_000L, 0L)
        );
        var cfg = new GuardianConfig(
            badDb, bad.queue(), bad.logFile(), bad.actions(),
            bad.permissions(), bad.lookup(), bad.privacy(), bad.purge(),
            bad.storage(),
        GuardianConfig.Rollback.defaults(), bad.theme(), bad.language()
        );
        assertThatThrownBy(cfg::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("connectionTimeoutMs");
    }

    @Test
    void validation_accepts_leak_detection_disabled_or_over_2s() {
        var db = new GuardianConfig.Database(
            "sqlite", "vg.db", null, null, null, null,
            new GuardianConfig.Hikari(10, 30_000L, 1_800_000L, 5_000L)
        );
        var cfg = new GuardianConfig(
            db,
            new GuardianConfig.Queue(50_000, 5_000L, 1_000),
            new GuardianConfig.LogFile(true, "logs/vg", true, 30, true),
            GuardianConfig.defaults().actions(),
            GuardianConfig.defaults().permissions(),
            GuardianConfig.defaults().lookup(),
            GuardianConfig.defaults().privacy(),
            GuardianConfig.defaults().purge(),
            GuardianConfig.Storage.defaults(),
        GuardianConfig.Rollback.defaults(),
            "aqua", "en_us"
        );
        cfg.validate(); // must not throw
    }
}
