package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuardianConfigDefaultsTest {
    @Test
    void defaultsUseBoundedRetentionAndSafeAuditBaseline() {
        GuardianConfig config = GuardianConfig.defaults();

        assertThat(config.queue().maxSize()).isEqualTo(50_000);
        assertThat(config.queue().flushIntervalMs()).isEqualTo(5_000L);
        assertThat(config.queue().batchSize()).isEqualTo(1_000);
        assertThat(config.logFile().retentionDays()).isEqualTo(30);
        assertThat(config.storage().persistNbt()).isTrue();
        assertThat(config.actions().logWaterFlow()).isFalse();
        assertThat(config.actions().logLavaFlow()).isFalse();
        assertThat(config.actions().logDuplicateSuppression()).isTrue();
        assertThat(config.actions().entityChangeLogAllEntities()).isFalse();
        assertThat(config.purge().autoPurgeSeconds())
            .isEqualTo(GuardianConfig.DEFAULT_AUTO_PURGE_SECONDS)
            .isEqualTo(7_776_000L);
        assertThat(config.purge().autoPurgeTime()).isEqualTo("03:30");
    }
}
