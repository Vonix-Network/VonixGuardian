/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite integration coverage for {@link GuardianDao#hasActionsInWindow}
 * (W3-B12+B13). Inserts a couple of block events, then probes the DAO with
 * varying windows / user / type combinations.
 */
class HasActionsInWindowIntegrationTest {

    private SqliteDao dao;
    private final UUID actor = UUID.nameUUIDFromBytes("has-window-actor".getBytes());
    private final UUID otherActor = UUID.nameUUIDFromBytes("has-window-other".getBytes());
    private static final String WORLD = "minecraft:overworld";

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    @Test
    void hasPlaced_returns_true_inside_window_false_outside() throws Exception {
        long now = System.currentTimeMillis();
        // t0 = 30s ago (in window for 60s probe, out of window for 10s probe)
        dao.insertBatch(List.of(row(now - 30_000L, ActionType.BLOCK_PLACE, actor, 10, 64, 20)));

        // In-window (60s): true.
        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, 60_000L)).isTrue();

        // Out-of-window (10s): false.
        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, 10_000L)).isFalse();
    }

    @Test
    void wrong_coord_returns_false() throws Exception {
        long now = System.currentTimeMillis();
        dao.insertBatch(List.of(row(now, ActionType.BLOCK_PLACE, actor, 10, 64, 20)));

        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 21,
                new ActionType[]{ActionType.BLOCK_PLACE}, 60_000L)).isFalse();
        assertThat(dao.hasActionsInWindow(actor, WORLD, 11, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, 60_000L)).isFalse();
    }

    @Test
    void wrong_user_returns_false() throws Exception {
        long now = System.currentTimeMillis();
        dao.insertBatch(List.of(row(now, ActionType.BLOCK_PLACE, actor, 10, 64, 20)));

        assertThat(dao.hasActionsInWindow(otherActor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, 60_000L)).isFalse();
    }

    @Test
    void wrong_type_returns_false_when_type_filter_provided() throws Exception {
        long now = System.currentTimeMillis();
        // Insert a BREAK; probe for PLACE.
        dao.insertBatch(List.of(row(now, ActionType.BLOCK_BREAK, actor, 10, 64, 20)));

        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, 60_000L)).isFalse();
        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_BREAK}, 60_000L)).isTrue();
    }

    @Test
    void null_types_matches_any_type() throws Exception {
        long now = System.currentTimeMillis();
        dao.insertBatch(List.of(row(now, ActionType.BLOCK_BREAK, actor, 10, 64, 20)));

        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20, null, 60_000L)).isTrue();
        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[0], 60_000L)).isTrue();
    }

    @Test
    void zero_or_negative_window_disables_temporal_bound() throws Exception {
        // Very old event.
        dao.insertBatch(List.of(row(1_000_000L, ActionType.BLOCK_PLACE, actor, 10, 64, 20)));

        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, 0L)).isTrue();
        assertThat(dao.hasActionsInWindow(actor, WORLD, 10, 64, 20,
                new ActionType[]{ActionType.BLOCK_PLACE}, -1L)).isTrue();
    }

    private Action row(long ts, ActionType type, UUID uuid, int x, int y, int z) {
        return new Action(-1L, ts, type, uuid, "Actor", WORLD, x, y, z,
                "minecraft:stone", null, 1, false, null);
    }
}
