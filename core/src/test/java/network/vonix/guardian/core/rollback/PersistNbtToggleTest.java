/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.EventGate;
import network.vonix.guardian.core.event.EventHook;
import network.vonix.guardian.core.perms.OpLevelFallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.2 Y1 regression: {@code storage.persistNbt=false} short-circuits every
 * NBT-capturing producer path on the Guardian facade.
 *
 * <p>The Guardian's {@code submitXxx(..., byte[] nbt)} overloads must observe
 * the config toggle and delegate straight to the non-NBT overload when the
 * flag is false, so we never queue rows with NBT columns populated. This is
 * the invariant that keeps toggling the flag off zero-cost on the hot path.</p>
 */
class PersistNbtToggleTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-nbt-toggle-test");
        t.setDaemon(true);
        return t;
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;
    private static final WorldMutator NOOP = new WorldMutator() {
        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {}
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String t, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m) {}
    };

    @Test
    void when_persistNbt_false_producers_do_NOT_populate_nbt_fields(@TempDir Path tmp) throws Exception {
        GuardianConfig cfg = cfgWith(tmp, /*persistNbt=*/ false);
        Path cfgPath = tmp.resolve("config.json");
        ConfigLoader.save(cfgPath, cfg);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP, ZERO_OP, SYNC, DAEMONS);
        try {
            AtomicReference<Action> captured = new AtomicReference<>();
            g.gate().addHook(action -> {
                captured.set(action);
                return EventHook.Decision.DENY;   // stop before queue/dao touches it
            });

            UUID actor = UUID.randomUUID();
            byte[] payload = "SHOULD_NOT_APPEAR".getBytes();

            // NBT-carrying overload — must drop the NBT because toggle=false.
            g.submitBlockBreak(actor, "Notch", "minecraft:overworld", 0, 64, 0,
                    "minecraft:chest", null, "facing=north", payload);

            Action a = captured.get();
            assertThat(a).isNotNull();
            assertThat(a.type()).isEqualTo(ActionType.BLOCK_BREAK);
            assertThat(a.hasNbt()).isFalse();
            assertThat(a.oldBlockState()).isNull();
            assertThat(a.newBlockState()).isNull();
            assertThat(a.blockEntityNbt()).isNull();
            assertThat(a.itemNbt()).isNull();
            assertThat(a.entityNbt()).isNull();
        } finally {
            g.close();
        }
    }

    @Test
    void when_persistNbt_true_producers_DO_populate_nbt_fields(@TempDir Path tmp) throws Exception {
        GuardianConfig cfg = cfgWith(tmp, /*persistNbt=*/ true);
        Path cfgPath = tmp.resolve("config.json");
        ConfigLoader.save(cfgPath, cfg);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP, ZERO_OP, SYNC, DAEMONS);
        try {
            AtomicReference<Action> captured = new AtomicReference<>();
            g.gate().addHook(action -> {
                captured.set(action);
                return EventHook.Decision.DENY;
            });

            byte[] payload = "REAL_CHEST_NBT".getBytes();
            g.submitBlockBreak(UUID.randomUUID(), "Notch", "minecraft:overworld", 0, 64, 0,
                    "minecraft:chest", null, "facing=north", payload);

            Action a = captured.get();
            assertThat(a).isNotNull();
            assertThat(a.hasNbt()).isTrue();
            assertThat(a.oldBlockState()).isEqualTo("facing=north");
            assertThat(a.blockEntityNbt()).isSameAs(payload);
        } finally {
            g.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static GuardianConfig cfgWith(Path dbDir, boolean persistNbt) {
        return new GuardianConfig(
            new GuardianConfig.Database("sqlite", dbDir.resolve("test.db").toString(), null, null, null),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                500L, 8192,
                List.of(), false
            ),
            new GuardianConfig.Permissions(true, 3),
            new GuardianConfig.Lookup(10, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(86_400L, 3_600L, 0L, "03:30"),
            new GuardianConfig.Storage(persistNbt),
            GuardianConfig.Rollback.defaults(),
            "aqua",
            "en_us"
        );
    }
}
