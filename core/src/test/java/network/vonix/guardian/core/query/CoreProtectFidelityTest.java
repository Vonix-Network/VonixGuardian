package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter.ActionSelect;
import network.vonix.guardian.core.query.QueryParser.QueryParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 1:1 parser-fidelity tests against the official CoreProtect command spec at
 * https://docs.coreprotect.net/commands/. Each test mirrors a worked example
 * from the docs and asserts the resulting {@link QueryFilter} captures the
 * intended semantics. These tests do NOT exercise the lookup engine — only
 * the parse layer.
 */
class CoreProtectFidelityTest {

    private static final QueryParseContext CTX = new QueryParseContext(0, 64, 0);

    private final QueryParser parser = new QueryParser();

    // --- core lookup recipes ------------------------------------------------

    @Test
    void cp_uNotch_t1h() {
        QueryFilter f = parseOk("u:Notch t:1h");
        assertThat(f.users()).singleElement()
            .satisfies(u -> {
                assertThat(u.name()).isEqualTo("Notch");
                assertThat(u.isSentinel()).isFalse();
            });
        assertThat(f.sinceMillis()).isNotNull();
        assertThat(f.untilMillis()).isNull();
    }

    @Test
    void cp_uNotchIntelli_t1h_preview() {
        QueryFilter f = parseOk("u:Notch,Intelli t:1h #preview");
        assertThat(f.users()).extracting(QueryFilter.UserSel::name)
            .containsExactly("Notch", "Intelli");
        assertThat(f.preview()).isTrue();
    }

    @Test
    void cp_uNotch_t23h17m() {
        QueryFilter f = parseOk("u:Notch t:23h17m");
        assertThat(f.sinceMillis()).isNotNull();
        // 23h17m = 83820000 ms; assert the since-millis is within a 5s window.
        long expectedAgo = (23L * 3_600_000L) + (17L * 60_000L);
        long approxNow = System.currentTimeMillis();
        long delta = (approxNow - f.sinceMillis()) - expectedAgo;
        assertThat(Math.abs(delta)).isLessThan(5_000L);
    }

    @Test
    void cp_uNotch_t1h_iStone() {
        QueryFilter f = parseOk("u:Notch t:1h i:stone");
        assertThat(f.include()).containsExactly("minecraft:stone");
    }

    @Test
    void cp_uNotch_t1h_iStone_aMinusBlock() {
        QueryFilter f = parseOk("u:Notch t:1h i:stone a:-block");
        assertThat(f.include()).containsExactly("minecraft:stone");
        assertThat(f.actions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(ActionType.BLOCK_BREAK);
            assertThat(a.sign()).isEqualTo(ActionSelect.Sign.BREAK_ONLY);
        });
    }

    @Test
    void cp_uNotch_t1h_rGlobal_eStoneDirt() {
        QueryFilter f = parseOk("u:Notch t:1h r:#global e:stone,dirt");
        assertThat(f.radius()).isEqualTo(-1);
        assertThat(f.worldSel()).isNotNull();
        assertThat(f.worldSel().global()).isTrue();
        assertThat(f.exclude()).containsExactly("minecraft:stone", "minecraft:dirt");
    }

    @Test
    void cp_uNotch_t1h_r20() {
        QueryFilter f = parseOk("u:Notch t:1h r:20");
        assertThat(f.radius()).isEqualTo(20);
        assertThat(f.centerX()).isZero();
    }

    @Test
    void cp_uNotch_t1h_rNether() {
        QueryFilter f = parseOk("u:Notch t:1h r:#nether");
        assertThat(f.worldSel()).isNotNull();
        assertThat(f.worldSel().worldKey()).isEqualTo("nether");
        assertThat(f.worldSel().global()).isFalse();
    }

    @Test
    void cp_uNotch_t5m_aInventory() {
        QueryFilter f = parseOk("u:Notch t:5m a:inventory");
        // CP a:inventory -> INVENTORY_DEPOSIT + INVENTORY_WITHDRAW (Sign.ANY each).
        assertThat(f.actions()).extracting(QueryFilter.ActionSelect::type)
            .containsExactlyInAnyOrder(
                ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW);
        assertThat(f.actions()).allSatisfy(a ->
            assertThat(a.sign()).isEqualTo(ActionSelect.Sign.ANY));
    }

    @Test
    void cp_t15m_r30() {
        QueryFilter f = parseOk("t:15m r:30");
        assertThat(f.users()).isEmpty();
        assertThat(f.radius()).isEqualTo(30);
    }

    @Test
    void cp_t15m_rWorldedit() {
        QueryFilter f = parseOk("t:15m r:#worldedit");
        // WE token is accepted but does not set radius/world.
        assertThat(f.radius()).isNull();
        assertThat(f.worldSel()).isNull();
    }

    @Test
    void cp_iDiamondOre_t1h_aMinusBlock() {
        QueryFilter f = parseOk("i:diamond_ore t:1h a:-block");
        assertThat(f.include()).containsExactly("minecraft:diamond_ore");
        assertThat(f.actions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(ActionType.BLOCK_BREAK);
            assertThat(a.sign()).isEqualTo(ActionSelect.Sign.BREAK_ONLY);
        });
    }

    @Test
    void cp_uNotch_t30m_aChat() {
        QueryFilter f = parseOk("u:Notch t:30m a:chat");
        assertThat(f.actions()).singleElement()
            .satisfies(a -> assertThat(a.type()).isEqualTo(ActionType.CHAT));
    }

    @Test
    void cp_uNotch_t3d_aInventory() {
        QueryFilter f = parseOk("u:Notch t:3d a:inventory");
        assertThat(f.actions()).extracting(QueryFilter.ActionSelect::type)
            .containsExactlyInAnyOrder(
                ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW);
    }

    // --- CP alias coverage --------------------------------------------------

    @Test
    void cp_uNotch_aLogin_aliasToPlusSession() {
        QueryFilter f = parseOk("u:Notch a:login");
        assertThat(f.actions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(ActionType.SESSION_JOIN);
            assertThat(a.sign()).isEqualTo(ActionSelect.Sign.PLACE_ONLY);
        });
    }

    @Test
    void cp_aLogout_aliasToMinusSession() {
        QueryFilter f = parseOk("u:Notch a:logout");
        assertThat(f.actions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(ActionType.SESSION_LEAVE);
            assertThat(a.sign()).isEqualTo(ActionSelect.Sign.BREAK_ONLY);
        });
    }

    @Test
    void cp_uNotch_aUsername() {
        QueryFilter f = parseOk("u:Notch a:username");
        assertThat(f.actions()).singleElement()
            .satisfies(a -> assertThat(a.type()).isEqualTo(ActionType.USERNAME_CHANGE));
    }

    // --- purge cases --------------------------------------------------------

    @Test
    void cp_purge_t30d_rWorldNether() {
        QueryFilter f = parseOk("t:30d r:#world_nether");
        assertThat(f.worldSel()).isNotNull();
        assertThat(f.worldSel().worldKey()).isEqualTo("nether");
        assertThat(f.worldSel().global()).isFalse();
    }

    @Test
    void cp_purge_t30d_iStoneDirt() {
        QueryFilter f = parseOk("t:30d i:stone,dirt");
        assertThat(f.include()).containsExactly("minecraft:stone", "minecraft:dirt");
    }

    @Test
    void cp_purge_t30d_optimize() {
        QueryFilter f = parseOk("t:30d #optimize");
        assertThat(f.optimize()).isTrue();
        assertThat(f.sinceMillis()).isNotNull();
    }

    // --- hash-actor users ---------------------------------------------------

    @Test
    void cp_uHashActors_t1h() {
        QueryFilter f = parseOk("u:#fire,#tnt,#creeper,#explosion t:1h");
        assertThat(f.users()).hasSize(4);
        assertThat(f.users()).allSatisfy(u -> {
            assertThat(u.isSentinel()).isTrue();
            assertThat(u.name()).startsWith("#");
        });
        assertThat(f.users()).extracting(QueryFilter.UserSel::name)
            .containsExactly("#fire", "#tnt", "#creeper", "#explosion");
    }

    // --- duration edge cases ------------------------------------------------

    @Test
    void cp_t2_50h_decimal() {
        QueryFilter f = parseOk("t:2.50h");
        assertThat(f.sinceMillis()).isNotNull();
        long expectedAgo = (long) (2.50d * 3_600_000L);
        long delta = (System.currentTimeMillis() - f.sinceMillis()) - expectedAgo;
        assertThat(Math.abs(delta)).isLessThan(5_000L);
    }

    @Test
    void cp_t1h_2h_range() {
        QueryFilter f = parseOk("t:1h-2h");
        assertThat(f.sinceMillis()).isNotNull();
        assertThat(f.untilMillis()).isNotNull();
        // "1h-2h" = between 2h ago (since) and 1h ago (until)
        long now = System.currentTimeMillis();
        long sinceAgo = now - f.sinceMillis();
        long untilAgo = now - f.untilMillis();
        assertThat(Math.abs(sinceAgo - 2L * 3_600_000L)).isLessThan(5_000L);
        assertThat(Math.abs(untilAgo - 1L * 3_600_000L)).isLessThan(5_000L);
    }

    @Test
    void cp_t_commaSeparatedComponents() {
        QueryFilter f = parseOk("t:2w,5d,7h,2m,10s");
        assertThat(f.sinceMillis()).isNotNull();
        long expectedAgo =
            2L * 604_800_000L
            + 5L * 86_400_000L
            + 7L * 3_600_000L
            + 2L * 60_000L
            + 10L * 1_000L;
        long delta = (System.currentTimeMillis() - f.sinceMillis()) - expectedAgo;
        assertThat(Math.abs(delta)).isLessThan(5_000L);
    }

    @Test
    void cp_uNotch_t1_5d_decimal() {
        QueryFilter f = parseOk("u:Notch t:1.5d");
        assertThat(f.sinceMillis()).isNotNull();
        long expectedAgo = (long) (1.5d * 86_400_000L);
        long delta = (System.currentTimeMillis() - f.sinceMillis()) - expectedAgo;
        assertThat(Math.abs(delta)).isLessThan(5_000L);
    }

    // ------------------------------------------------------------------ util

    private QueryFilter parseOk(String expr) {
        QueryFilter[] holder = new QueryFilter[1];
        assertThatCode(() -> holder[0] = parser.parse(expr, CTX))
            .as("parse must succeed for CoreProtect example: '%s'", expr)
            .doesNotThrowAnyException();
        return holder[0];
    }
}
