package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter.ActionSelect;
import network.vonix.guardian.core.query.QueryParser.QueryParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryParserTest {

    private static final QueryParseContext CTX = new QueryParseContext(100, 64, -200);

    private final QueryParser parser = new QueryParser();

    // --- happy-path scalars -------------------------------------------------

    @Test
    void nullRawReturnsEmpty() {
        QueryFilter f = parser.parse(null, null);
        assertThat(f).isEqualTo(QueryFilter.empty());
    }

    @Test
    void blankRawReturnsEmpty() {
        assertThat(parser.parse("", CTX)).isEqualTo(QueryFilter.empty());
        assertThat(parser.parse("   \t  ", CTX)).isEqualTo(QueryFilter.empty());
    }

    @Test
    void emptyFilterHasSensibleDefaults() {
        QueryFilter f = QueryFilter.empty();
        assertThat(f.users()).isEmpty();
        assertThat(f.actions()).isEmpty();
        assertThat(f.include()).isEmpty();
        assertThat(f.exclude()).isEmpty();
        assertThat(f.sinceMillis()).isNull();
        assertThat(f.untilMillis()).isNull();
        assertThat(f.radius()).isNull();
        assertThat(f.worldSel()).isNull();
        assertThat(f.preview()).isFalse();
        assertThat(f.countOnly()).isFalse();
        assertThat(f.verbose()).isFalse();
        assertThat(f.silent()).isFalse();
    }

    // --- u: -----------------------------------------------------------------

    @Test
    void parsesSinglePlayer() {
        QueryFilter f = parser.parse("u:Notch", CTX);
        assertThat(f.users()).hasSize(1);
        assertThat(f.users().get(0).name()).isEqualTo("Notch");
        assertThat(f.users().get(0).isSentinel()).isFalse();
    }

    @Test
    void parsesPlayerListCommaSeparated() {
        QueryFilter f = parser.parse("u:Notch,Intelli,_Jeb", CTX);
        assertThat(f.users()).extracting(QueryFilter.UserSel::name)
            .containsExactly("Notch", "Intelli", "_Jeb");
    }

    @Test
    void parsesSentinelAsUser() {
        QueryFilter f = parser.parse("u:#creeper,#tnt", CTX);
        assertThat(f.users()).hasSize(2);
        assertThat(f.users().get(0).isSentinel()).isTrue();
        assertThat(f.users().get(0).name()).isEqualTo("#creeper");
        assertThat(f.users().get(1).name()).isEqualTo("#tnt");
    }

    @Test
    void rejectsInvalidPlayerName() {
        assertThatThrownBy(() -> parser.parse("u:bad!name", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("u:bad!name")
            .hasMessageContaining("not a valid player name");
    }

    @Test
    void rejectsEmptyUserEntry() {
        assertThatThrownBy(() -> parser.parse("u:Notch,,Jeb", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("empty entry");
    }

    @Test
    void rejectsMissingUserValue() {
        assertThatThrownBy(() -> parser.parse("u:", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    // --- t: -----------------------------------------------------------------

    @Test
    void parsesSimpleDuration() {
        long before = System.currentTimeMillis();
        QueryFilter f = parser.parse("t:1h", CTX);
        long after = System.currentTimeMillis();
        assertThat(f.sinceMillis()).isBetween(before - 3_600_000L, after - 3_600_000L);
        assertThat(f.untilMillis()).isNull();
    }

    @Test
    void parsesCompoundDuration() {
        QueryFilter f = parser.parse("t:2w5d", CTX);
        long expected = 2L * 604_800_000L + 5L * 86_400_000L;
        long delta = System.currentTimeMillis() - f.sinceMillis();
        assertThat(delta).isBetween(expected - 1_000L, expected + 1_000L);
    }

    @Test
    void parsesFractionalDuration() {
        QueryFilter f = parser.parse("t:2.5h", CTX);
        long expected = (long) (2.5 * 3_600_000L);
        long delta = System.currentTimeMillis() - f.sinceMillis();
        assertThat(delta).isBetween(expected - 1_000L, expected + 1_000L);
    }

    @Test
    void parsesDurationRange() {
        QueryFilter f = parser.parse("t:1h-2h", CTX);
        long now = System.currentTimeMillis();
        // sinceMillis is the OLDER bound (2h ago); untilMillis is 1h ago.
        assertThat(now - f.sinceMillis()).isBetween(2L * 3_600_000L - 1_000L, 2L * 3_600_000L + 1_000L);
        assertThat(now - f.untilMillis()).isBetween(3_600_000L - 1_000L, 3_600_000L + 1_000L);
    }

    @Test
    void rejectsDurationMissingUnit() {
        assertThatThrownBy(() -> parser.parse("t:42", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("missing a unit");
    }

    @Test
    void rejectsDurationUnknownUnit() {
        assertThatThrownBy(() -> parser.parse("t:5y", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("unknown duration unit");
    }

    @Test
    void rejectsDurationEmpty() {
        assertThatThrownBy(() -> parser.parse("t:", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    @Test
    void rejectsDurationRangeMissingSide() {
        assertThatThrownBy(() -> parser.parse("t:1h-", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("range");
    }

    // --- r: -----------------------------------------------------------------

    @Test
    void parsesNumericRadius() {
        QueryFilter f = parser.parse("r:20", CTX);
        assertThat(f.radius()).isEqualTo(20);
        assertThat(f.centerX()).isEqualTo(100);
        assertThat(f.centerY()).isEqualTo(64);
        assertThat(f.centerZ()).isEqualTo(-200);
    }

    @Test
    void numericRadiusFromConsoleFails() {
        assertThatThrownBy(() -> parser.parse("r:20", null))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("console");
    }

    @Test
    void parsesGlobalRadius() {
        QueryFilter f = parser.parse("r:#global", null);
        assertThat(f.radius()).isEqualTo(-1);
        assertThat(f.worldSel()).isNotNull();
        assertThat(f.worldSel().global()).isTrue();
    }

    @Test
    void parsesWorldRadius() {
        QueryFilter f = parser.parse("r:#world_the_nether", null);
        assertThat(f.worldSel().global()).isFalse();
        assertThat(f.worldSel().worldKey()).isEqualTo("the_nether");
    }

    @Test
    void parsesWorldEditAliases() {
        // These are accepted but don't populate the contract record's fields.
        QueryFilter we = parser.parse("r:#we", CTX);
        QueryFilter wed = parser.parse("r:#worldedit", CTX);
        assertThat(we.radius()).isNull();
        assertThat(wed.radius()).isNull();
    }

    @Test
    void rejectsNegativeRadius() {
        assertThatThrownBy(() -> parser.parse("r:-5", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    @Test
    void rejectsUnknownRadiusKeyword() {
        assertThatThrownBy(() -> parser.parse("r:#whatever", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("unknown radius keyword");
    }

    @Test
    void rejectsEmptyWorldKey() {
        assertThatThrownBy(() -> parser.parse("r:#world_", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    // --- a: -----------------------------------------------------------------

    @Test
    void parsesSignedActionPlace() {
        QueryFilter f = parser.parse("a:+block", CTX);
        assertThat(f.actions()).hasSize(1);
        assertThat(f.actions().get(0).type()).isEqualTo(ActionType.BLOCK_PLACE);
        assertThat(f.actions().get(0).sign()).isEqualTo(ActionSelect.Sign.PLACE_ONLY);
    }

    @Test
    void parsesSignedActionBreak() {
        QueryFilter f = parser.parse("a:-block", CTX);
        assertThat(f.actions().get(0).type()).isEqualTo(ActionType.BLOCK_BREAK);
        assertThat(f.actions().get(0).sign()).isEqualTo(ActionSelect.Sign.BREAK_ONLY);
    }

    @Test
    void parsesBareActionFamilyExpandsBoth() {
        QueryFilter f = parser.parse("a:block", CTX);
        assertThat(f.actions()).hasSize(2);
        assertThat(f.actions()).extracting(ActionSelect::type)
            .containsExactlyInAnyOrder(ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK);
        assertThat(f.actions()).allMatch(a -> a.sign() == ActionSelect.Sign.ANY);
    }

    @Test
    void parsesNonFamilyActionKill() {
        QueryFilter f = parser.parse("a:kill", CTX);
        assertThat(f.actions()).hasSize(1);
        assertThat(f.actions().get(0).type()).isEqualTo(ActionType.ENTITY_KILL);
    }

    @Test
    void parsesContainerSignedAction() {
        QueryFilter f = parser.parse("a:+container", CTX);
        assertThat(f.actions().get(0).type()).isEqualTo(ActionType.CONTAINER_DEPOSIT);
    }

    @Test
    void rejectsUnknownAction() {
        assertThatThrownBy(() -> parser.parse("a:teleport", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("unknown action")
            .hasMessageContaining("valid actions");
    }

    @Test
    void rejectsEmptyAction() {
        assertThatThrownBy(() -> parser.parse("a:", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    // --- i: / e: ------------------------------------------------------------

    @Test
    void parsesIncludeListWithDefaultNamespace() {
        QueryFilter f = parser.parse("i:stone,oak_log", CTX);
        assertThat(f.include()).containsExactly("minecraft:stone", "minecraft:oak_log");
    }

    @Test
    void parsesExcludeListWithExplicitNamespace() {
        QueryFilter f = parser.parse("e:minecraft:tnt,modid:custom_block", CTX);
        assertThat(f.exclude()).containsExactly("minecraft:tnt", "modid:custom_block");
    }

    @Test
    void identifiersLowercased() {
        QueryFilter f = parser.parse("i:STONE", CTX);
        assertThat(f.include()).containsExactly("minecraft:stone");
    }

    @Test
    void rejectsInvalidIdentifier() {
        assertThatThrownBy(() -> parser.parse("i:bad ident", CTX))
            .isInstanceOf(QueryParseException.class);
        assertThatThrownBy(() -> parser.parse("i:foo:bar:baz", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    @Test
    void rejectsEmptyIncludeEntry() {
        assertThatThrownBy(() -> parser.parse("i:stone,,dirt", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("empty entry");
    }

    // --- #flags -------------------------------------------------------------

    @Test
    void parsesAllFlags() {
        QueryFilter f = parser.parse("#preview #count #verbose #silent", CTX);
        assertThat(f.preview()).isTrue();
        assertThat(f.countOnly()).isTrue();
        assertThat(f.verbose()).isTrue();
        assertThat(f.silent()).isTrue();
    }

    @Test
    void parsesFlagsCaseInsensitive() {
        QueryFilter f = parser.parse("#PREVIEW #Count", CTX);
        assertThat(f.preview()).isTrue();
        assertThat(f.countOnly()).isTrue();
    }

    @Test
    void rejectsUnknownFlag() {
        assertThatThrownBy(() -> parser.parse("#nope", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("unknown flag");
    }

    // --- mixed / canonical README-style expressions -------------------------

    @Test
    void parsesCanonicalReadmeLookup() {
        // Modelled on README.md § Commands: /vg lookup u:<user> t:<time> r:<radius>
        //   a:<action> i:<include> e:<exclude> #preview #count #verbose #silent
        QueryFilter f = parser.parse(
            "u:Notch,#creeper t:2w5d r:20 a:-block i:stone,oak_log e:tnt #count #verbose",
            CTX);
        assertThat(f.users()).extracting(QueryFilter.UserSel::name)
            .containsExactly("Notch", "#creeper");
        assertThat(f.sinceMillis()).isNotNull();
        assertThat(f.radius()).isEqualTo(20);
        assertThat(f.actions().get(0).type()).isEqualTo(ActionType.BLOCK_BREAK);
        assertThat(f.actions().get(0).sign()).isEqualTo(ActionSelect.Sign.BREAK_ONLY);
        assertThat(f.include()).containsExactly("minecraft:stone", "minecraft:oak_log");
        assertThat(f.exclude()).containsExactly("minecraft:tnt");
        assertThat(f.countOnly()).isTrue();
        assertThat(f.verbose()).isTrue();
        assertThat(f.preview()).isFalse();
        assertThat(f.silent()).isFalse();
    }

    @Test
    void tokensOrderInsensitive() {
        QueryFilter a = parser.parse("u:Notch t:1h a:-block", CTX);
        QueryFilter b = parser.parse("a:-block t:1h u:Notch", CTX);
        assertThat(a.users()).isEqualTo(b.users());
        assertThat(a.actions()).isEqualTo(b.actions());
        assertThat(Math.abs(a.sinceMillis() - b.sinceMillis())).isLessThan(1_000L);
    }

    @Test
    void tokensCaseInsensitivePrefix() {
        QueryFilter f = parser.parse("U:Notch T:1h R:20 A:+block", CTX);
        assertThat(f.users()).hasSize(1);
        assertThat(f.radius()).isEqualTo(20);
        assertThat(f.actions().get(0).type()).isEqualTo(ActionType.BLOCK_PLACE);
    }

    @Test
    void onlyFlagsParsesCleanly() {
        QueryFilter f = parser.parse("#preview", CTX);
        assertThat(f.preview()).isTrue();
        assertThat(f.users()).isEmpty();
        assertThat(f.actions()).isEmpty();
    }

    @Test
    void multipleWhitespaceBetweenTokens() {
        QueryFilter f = parser.parse("   u:Notch     a:kill\t#count  ", CTX);
        assertThat(f.users()).hasSize(1);
        assertThat(f.actions()).hasSize(1);
        assertThat(f.countOnly()).isTrue();
    }

    @Test
    void multipleActionTokensAccumulate() {
        QueryFilter f = parser.parse("a:+block a:-block a:kill", CTX);
        assertThat(f.actions()).hasSize(3);
        assertThat(f.actions()).extracting(ActionSelect::type).containsExactly(
            ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK, ActionType.ENTITY_KILL);
    }

    // --- structural errors --------------------------------------------------

    @Test
    void rejectsBareWord() {
        assertThatThrownBy(() -> parser.parse("notATypedToken", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    @Test
    void rejectsColonOnlyToken() {
        assertThatThrownBy(() -> parser.parse(":", CTX))
            .isInstanceOf(QueryParseException.class);
    }

    @Test
    void rejectsUnknownPrefix() {
        assertThatThrownBy(() -> parser.parse("x:foo", CTX))
            .isInstanceOf(QueryParseException.class)
            .hasMessageContaining("unknown prefix");
    }

    // --- builder + empty ----------------------------------------------------

    @Test
    void builderRoundTripsEmpty() {
        QueryFilter f = QueryFilter.builder().build();
        assertThat(f).isEqualTo(QueryFilter.empty());
    }

    @Test
    void builderProducesEqualValueAsParser() {
        QueryFilter parsed = parser.parse("u:Notch r:#global #count", null);
        QueryFilter built = QueryFilter.builder()
            .addUser(new QueryFilter.UserSel(null, "Notch", false))
            .radius(-1)
            .worldSel(new QueryFilter.WorldSel(null, true))
            .countOnly(true)
            .build();
        assertThat(built).isEqualTo(parsed);
    }

    @Test
    void emptyFilterIsImmutable() {
        QueryFilter f = QueryFilter.empty();
        assertThatThrownBy(() -> f.users().add(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exceptionCarriesBadToken() {
        try {
            parser.parse("a:teleport", CTX);
            org.assertj.core.api.Assertions.fail("expected QueryParseException");
        } catch (QueryParseException e) {
            assertThat(e.badToken()).isEqualTo("a:teleport");
        }
    }
}
