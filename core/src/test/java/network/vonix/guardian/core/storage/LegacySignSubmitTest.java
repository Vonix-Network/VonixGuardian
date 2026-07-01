package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3-B15: verify that the legacy 7-argument {@link EventSubmitter#submitSign}
 * overload still works and produces a row with {@code NULL} sign metadata
 * columns — the "source-compat" half of the CoreProtect-v24 wire-up.
 */
class LegacySignSubmitTest {

    private SqliteDao dao;

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
    void legacy_submitSign_persists_null_sign_metadata() throws Exception {
        // Legacy path: builder without any sign*() calls. Mirrors what the
        // pre-v1.1.7 loader integrations were doing before the metadata wire.
        UUID actor = UUID.nameUUIDFromBytes("OldCell".getBytes());
        Action legacy = new ActionBuilder()
            .type(ActionType.SIGN)
            .actorUuid(actor)
            .actorName("OldCell")
            .worldId("minecraft:overworld")
            .position(0, 64, 0)
            .targetId("legacy sign")
            .build();

        assertThat(legacy.signSide()).isNull();
        assertThat(legacy.signDyeColor()).isNull();
        assertThat(legacy.signWaxed()).isNull();

        dao.insertBatch(List.of(legacy));
        Action back = dao.query(QueryFilter.empty(), 0, 10).get(0);
        assertThat(back.targetId()).isEqualTo("legacy sign");
        assertThat(back.signSide()).isNull();
        assertThat(back.signDyeColor()).isNull();
        assertThat(back.signWaxed()).isNull();
    }

    @Test
    void event_submitter_legacy_overload_delegates_default_correctly() {
        // Prove the interface's default 10-arg method routes back through
        // the legacy 7-arg impl when a submitter has not overridden it.
        AtomicReference<Action> lastSeen = new AtomicReference<>();
        List<Object> legacyCallArgs = new ArrayList<>();
        EventSubmitter legacyOnly = new EventSubmitter() {
            @Override public void submit(Action a) { lastSeen.set(a); }
            @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitContainerChange(UUID u, String n, String w, int x, int y, int z, String i, int d, String s) {}
            @Override public void submitItemDrop(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitItemPickup(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitEntityKill(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
            @Override public void submitExplosion(UUID u, String n, String w, int x, int y, int z, String j, String s) {}
            @Override public void submitChat(UUID u, String n, String w, String m) {}
            @Override public void submitCommand(UUID u, String n, String w, String cmd) {}
            @Override public void submitSign(UUID u, String n, String w, int x, int y, int z, String j) {
                legacyCallArgs.add(j);
            }
            @Override public void submitSessionJoin(UUID u, String n, String w, String i) {}
            @Override public void submitSessionLeave(UUID u, String n, String w, String r) {}
            @Override public void submitUsernameChange(UUID u, String nn, String w, String on) {}
            @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitDispense(UUID u, String n, String w, int x, int y, int z, String i, String s) {}
            @Override public void submitPistonExtend(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitPistonRetract(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitBucketEmpty(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitBucketFill(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
            @Override public void submitEntityChangeBlock(UUID u, String n, String w, int x, int y, int z, String o, String nb, String s) {}
            @Override public void submitInventoryDeposit(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitInventoryWithdraw(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
            @Override public void submitEntitySpawn(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
            @Override public void submitEntityInteract(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
            @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
            @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
            @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
            @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}
        };

        legacyOnly.submitSign(UUID.randomUUID(), "P", "minecraft:overworld",
                              1, 2, 3, "text",
                              "front", "red", Boolean.TRUE);

        // The default method must have forwarded to the legacy overload,
        // dropping the side/dye/waxed args (source-compat behaviour).
        assertThat(legacyCallArgs).containsExactly("text");
    }
}
