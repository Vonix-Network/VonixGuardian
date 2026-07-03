/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.storage.QueryCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * X5 wave regression pins for VonixGuardian v1.3.1.
 *
 * <p>Locks the three P1 deltas the X5 wave prompt calls out:
 * <ul>
 *   <li>Decimal time syntax {@code t:2.5h} (= 2h30m).</li>
 *   <li>Range time syntax {@code t:1h-2h} (rows aged between 1h and 2h).</li>
 *   <li>Real WorldEdit bridge on {@code r:#we} / {@code r:#worldedit} — when
 *       the WE class is absent from the classpath the parser still accepts the
 *       token and stores the requesting UUID, and the DAO compiler emits the
 *       {@code 1 = 0} sentinel predicate so the query matches zero rows
 *       (fail-closed) instead of silently promoting to global scope.</li>
 * </ul>
 *
 * <p>The AutoPurgeScheduler behavior is exercised in
 * {@link network.vonix.guardian.core.purge.AutoPurgeSchedulerTest}; this file
 * just pins the parser + compiler surface.
 */
class X5RegressionTest {

    private static final QueryParser.QueryParseContext CTX =
        new QueryParser.QueryParseContext(0, 64, 0);

    private QueryParser parser;

    @BeforeEach
    void setUp() {
        parser = new QueryParser();
        // Ensure the reflection cache is clean for each test — the resolver
        // is process-wide, and other test files also flip the UNAVAILABLE flag.
        WorldEditRegionResolver.resetForTests();
        WorldEditRegionResolver.setNameResolver(null);
    }

    // ---- decimal time -------------------------------------------------------

    @Test
    void decimalTime_twoAndAHalfHours_parsesAsTwoAndAHalfHours() {
        long before = System.currentTimeMillis();
        QueryFilter f = parser.parse("t:2.5h", CTX);
        long after = System.currentTimeMillis();
        // 2.5h = 9_000_000ms. Allow +/- 100ms wall-clock jitter.
        long expected = 9_000_000L;
        long ageMs = after - f.sinceMillis();
        assertThat(ageMs).isBetween(expected, expected + (after - before) + 100L);
        assertThat(f.untilMillis()).isNull();
    }

    @Test
    void decimalTime_pointFiveH_isThirtyMinutes() {
        long before = System.currentTimeMillis();
        QueryFilter f = parser.parse("t:0.5h", CTX);
        long after = System.currentTimeMillis();
        long expected = 1_800_000L;                 // 30 min
        long ageMs = after - f.sinceMillis();
        assertThat(ageMs).isBetween(expected, expected + (after - before) + 100L);
    }

    // ---- range time ---------------------------------------------------------

    @Test
    void rangeTime_1hTo2h_setsBothBounds() {
        long before = System.currentTimeMillis();
        QueryFilter f = parser.parse("t:1h-2h", CTX);
        long after = System.currentTimeMillis();
        // "1h-2h" means rows aged 1..2 hours: since = now-2h, until = now-1h.
        long since = f.sinceMillis();
        long until = f.untilMillis();
        assertThat(since).isNotNull();
        assertThat(until).isNotNull();
        long sinceAge = after - since;
        long untilAge = after - until;
        assertThat(sinceAge).isBetween(7_200_000L, 7_200_000L + (after - before) + 100L);
        assertThat(untilAge).isBetween(3_600_000L, 3_600_000L + (after - before) + 100L);
        assertThat(since).isLessThan(until);   // older bound must precede newer
    }

    @Test
    void rangeTime_2hTo1h_isCommutative() {
        QueryFilter fA = parser.parse("t:1h-2h", CTX);
        QueryFilter fB = parser.parse("t:2h-1h", CTX);
        // Order tolerated by CP parity — both must produce a positive window
        // with sinceMillis <= untilMillis.
        assertThat(fA.sinceMillis()).isLessThanOrEqualTo(fA.untilMillis());
        assertThat(fB.sinceMillis()).isLessThanOrEqualTo(fB.untilMillis());
        // ~equal within 50ms wall-clock skew
        assertThat(Math.abs(fA.sinceMillis() - fB.sinceMillis())).isLessThan(50L);
        assertThat(Math.abs(fA.untilMillis() - fB.untilMillis())).isLessThan(50L);
    }

    @Test
    void rangeTime_missingLeftSide_rejected() {
        assertThatThrownBy(() -> parser.parse("t:-2h", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    // ---- WorldEdit bridge ---------------------------------------------------

    @Test
    void worldEditToken_withoutPlayerContext_rejected() {
        // Console (no UUID) using #we is a hard reject — mirrors CP behavior.
        QueryParser.QueryParseContext console = new QueryParser.QueryParseContext(0, 64, 0, null);
        assertThatThrownBy(() -> parser.parse("r:#we", console))
            .isInstanceOf(QueryParseException.class);
        assertThatThrownBy(() -> parser.parse("r:#worldedit", console))
            .isInstanceOf(QueryParseException.class);
    }

    @Test
    void worldEditToken_setsFilterUuid_forCompilerToResolve() {
        UUID uid = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        QueryParser.QueryParseContext ctx = new QueryParser.QueryParseContext(0, 64, 0, uid);
        QueryFilter fWe = parser.parse("r:#we", ctx);
        QueryFilter fWed = parser.parse("r:#worldedit", ctx);
        assertThat(fWe.worldEditPlayer()).isEqualTo(uid);
        assertThat(fWed.worldEditPlayer()).isEqualTo(uid);
    }

    @Test
    void worldEditToken_compilerEmitsImpossiblePredicateWhenWorldEditAbsent() {
        // WorldEdit class is NOT on the test classpath. The compiler's WE
        // branch must fall back to "1 = 0" (fail-closed) rather than dropping
        // the predicate and silently returning global-scope results.
        UUID uid = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        QueryFilter f = QueryFilter.builder()
            .worldEditPlayer(uid)
            .addAction(new QueryFilter.ActionSelect(ActionType.BLOCK_BREAK,
                QueryFilter.ActionSelect.Sign.ANY))
            .build();
        QueryCompiler.Compiled compiled = QueryCompiler.compileSelect(f, 0, 25);
        assertThat(compiled.sql())
            .as("WE-absent selection must fail closed, not silently drop the predicate")
            .contains("1 = 0");
    }

    // ---- ActionTokens parity (light touch — ActionTokenParityTest owns the deep check)

    @Test
    void actionTokens_includesCorePlusAliases_notEmpty() {
        assertThat(ActionTokens.ALL).isNotEmpty();
        assertThat(ActionTokens.ALL).contains("login", "logout", "inventory");
        // The 8-cell GuardianSuggestions.ACTIONS array reads from this constant;
        // ActionTokenParityTest asserts every entry parses successfully via
        // QueryParser.
    }
}
