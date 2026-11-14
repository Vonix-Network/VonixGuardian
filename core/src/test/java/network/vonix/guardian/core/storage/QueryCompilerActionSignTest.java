package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code a:+x} / {@code a:-x} direction modifier must reach the SQL
 * predicate, not just the parsed filter.
 *
 * <p>Before this contract was enforced, {@link QueryCompiler} collected
 * {@code ActionSelect} entries into a {@code Set<Integer>} of type ids and never
 * looked at {@link QueryFilter.ActionSelect#sign()}. Any selector whose sign
 * contradicted its type — {@code BLOCK_BREAK} with {@code PLACE_ONLY},
 * {@code CHAT} with {@code PLACE_ONLY} — produced the same predicate as the bare
 * token, so a caller asking for "placements only" silently received the whole
 * direction.
 */
class QueryCompilerActionSignTest {

    private SqliteDao dao;
    private final UUID actor = UUID.nameUUIDFromBytes("SignTester".getBytes());

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        long t0 = 1_700_000_000_000L;
        dao.insertBatch(List.of(
            action(t0, ActionType.BLOCK_PLACE, "minecraft:stone", 0),
            action(t0 + 1, ActionType.BLOCK_PLACE, "minecraft:dirt", 1),
            action(t0 + 2, ActionType.BLOCK_BREAK, "minecraft:stone", 2),
            action(t0 + 3, ActionType.CHAT, "hello", 3),
            action(t0 + 4, ActionType.EXPLOSION, "0:64:0=minecraft:stone", 4)
        ));
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    private Action action(long ts, ActionType type, String target, int x) {
        return new Action(-1L, ts, type, actor, "SignTester", "minecraft:overworld",
            x, 64, 0, target, null, 1, false, null);
    }

    // --- the parsed token already encodes direction -------------------------

    @Test
    void place_only_token_selects_only_placements() throws Exception {
        QueryFilter f = new QueryParser().parse("a:+block", null);
        assertThat(f.actions()).singleElement()
            .satisfies(a -> assertThat(a.sign())
                .isEqualTo(QueryFilter.ActionSelect.Sign.PLACE_ONLY));

        List<Action> rows = dao.query(f, 0, 100);
        assertThat(rows).extracting(Action::type)
            .containsExactlyInAnyOrder(ActionType.BLOCK_PLACE, ActionType.BLOCK_PLACE);
    }

    @Test
    void break_only_token_selects_only_breaks() throws Exception {
        QueryFilter f = new QueryParser().parse("a:-block", null);
        List<Action> rows = dao.query(f, 0, 100);
        assertThat(rows).extracting(Action::type)
            .containsExactly(ActionType.BLOCK_BREAK);
    }

    // --- a contradictory selector must fail closed, not widen ---------------

    @Test
    void place_only_on_a_break_type_matches_nothing() {
        QueryFilter f = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(
                ActionType.BLOCK_BREAK, QueryFilter.ActionSelect.Sign.PLACE_ONLY))
            .build();

        QueryCompiler.Compiled c = QueryCompiler.compileSelect(f, 0, 10);

        assertThat(c.sql())
            .as("contradictory direction must not degrade to the bare type")
            .contains("1 = 0")
            .doesNotContain("a.type IN");
        assertThat(c.binds()).containsExactly(10, 0);
    }

    @Test
    void break_only_on_a_place_type_matches_nothing() {
        QueryFilter f = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(
                ActionType.BLOCK_PLACE, QueryFilter.ActionSelect.Sign.BREAK_ONLY))
            .build();

        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);

        assertThat(c.sql()).contains("1 = 0").doesNotContain("a.type IN");
        assertThat(c.binds()).isEmpty();
    }

    @Test
    void direction_filter_on_a_neutral_type_matches_nothing() {
        // CHAT is not a place and not a break. Before the sign was read, this
        // selector returned every chat row.
        for (QueryFilter.ActionSelect.Sign sign : List.of(
                QueryFilter.ActionSelect.Sign.PLACE_ONLY,
                QueryFilter.ActionSelect.Sign.BREAK_ONLY)) {
            QueryFilter f = QueryFilter.builder()
                .addAction(new QueryFilter.ActionSelect(ActionType.CHAT, sign))
                .build();
            assertThat(QueryCompiler.compileCount(f).sql())
                .as("CHAT with %s", sign)
                .contains("1 = 0");
        }
    }

    @Test
    void contradictory_selector_returns_no_rows_end_to_end() throws Exception {
        QueryFilter f = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(
                ActionType.BLOCK_BREAK, QueryFilter.ActionSelect.Sign.PLACE_ONLY))
            .build();

        assertThat(dao.count(f)).isZero();
        assertThat(dao.query(f, 0, 100)).isEmpty();
    }

    @Test
    void explosion_uses_its_declared_break_sign() {
        // EXPLOSION declares Sign.BREAK, so -explosion is admitted and
        // +explosion is not. This is the check that makes the enum's own
        // direction the single source of truth.
        assertThat(ActionType.EXPLOSION.sign()).isEqualTo(ActionType.Sign.BREAK);
        QueryFilter breaking = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(
                ActionType.EXPLOSION, QueryFilter.ActionSelect.Sign.BREAK_ONLY))
            .build();
        assertThat(QueryCompiler.compileCount(breaking).sql())
            .contains("a.type IN (?)")
            .doesNotContain("1 = 0");

        QueryFilter placing = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(
                ActionType.EXPLOSION, QueryFilter.ActionSelect.Sign.PLACE_ONLY))
            .build();
        assertThat(QueryCompiler.compileCount(placing).sql()).contains("1 = 0");
    }

    // --- mixed selectors keep the admitted subset ---------------------------

    @Test
    void mixed_selectors_keep_only_the_admitted_types() {
        QueryFilter f = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(
                ActionType.BLOCK_PLACE, QueryFilter.ActionSelect.Sign.PLACE_ONLY))
            .addAction(new QueryFilter.ActionSelect(
                ActionType.BLOCK_BREAK, QueryFilter.ActionSelect.Sign.PLACE_ONLY))
            .addAction(new QueryFilter.ActionSelect(
                ActionType.CONTAINER_WITHDRAW, QueryFilter.ActionSelect.Sign.BREAK_ONLY))
            .build();

        QueryCompiler.Compiled c = QueryCompiler.compileCount(f);

        assertThat(c.sql()).contains("a.type IN (?,?)").doesNotContain("1 = 0");
        assertThat(c.binds()).containsExactly(
            ActionType.BLOCK_PLACE.id(), ActionType.CONTAINER_WITHDRAW.id());
    }

    @Test
    void any_sign_admits_every_type() {
        for (ActionType t : ActionType.values()) {
            assertThat(QueryCompiler.signAdmits(
                    new QueryFilter.ActionSelect(t, QueryFilter.ActionSelect.Sign.ANY)))
                .as("%s with ANY", t)
                .isTrue();
        }
    }

    @Test
    void placement_only_admits_exactly_the_place_signed_types() {
        List<ActionType> admitted = new ArrayList<>();
        for (ActionType t : ActionType.values()) {
            if (QueryCompiler.signAdmits(new QueryFilter.ActionSelect(
                    t, QueryFilter.ActionSelect.Sign.PLACE_ONLY))) {
                admitted.add(t);
            }
        }
        assertThat(admitted).isNotEmpty();
        assertThat(admitted).allMatch(t -> t.sign() == ActionType.Sign.PLACE);
    }

    @Test
    void break_only_admits_exactly_the_break_signed_types() {
        List<ActionType> admitted = new ArrayList<>();
        for (ActionType t : ActionType.values()) {
            if (QueryCompiler.signAdmits(new QueryFilter.ActionSelect(
                    t, QueryFilter.ActionSelect.Sign.BREAK_ONLY))) {
                admitted.add(t);
            }
        }
        assertThat(admitted).isNotEmpty();
        assertThat(admitted).allMatch(t -> t.sign() == ActionType.Sign.BREAK);
    }

    @Test
    void family_expansion_with_a_direction_covers_the_whole_family() throws Exception {
        // a:block expands to all BLOCK-category types with Sign.ANY. Applying
        // BREAK_ONLY to that expansion must admit the break-signed members only.
        QueryFilter family = new QueryParser().parse("a:block", null);
        List<QueryFilter.ActionSelect> breakOnly = new ArrayList<>();
        for (QueryFilter.ActionSelect sel : family.actions()) {
            breakOnly.add(new QueryFilter.ActionSelect(
                sel.type(), QueryFilter.ActionSelect.Sign.BREAK_ONLY));
        }
        QueryFilter f = QueryFilter.builder().build();
        QueryFilter narrowed = new QueryFilter(
            f.users(), f.sinceMillis(), f.untilMillis(), f.radius(), f.worldSel(),
            f.centerX(), f.centerY(), f.centerZ(), breakOnly, f.include(), f.exclude(),
            f.rolledBack(), f.countOnly(), f.preview(), f.verbose(), f.silent(),
            f.optimize(), f.worldEditPlayer(), f.actionIds());

        List<Action> rows = dao.query(narrowed, 0, 100);

        assertThat(rows).extracting(Action::type)
            .containsOnly(ActionType.BLOCK_BREAK);
    }
}
