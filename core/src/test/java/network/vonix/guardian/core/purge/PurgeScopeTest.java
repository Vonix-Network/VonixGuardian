package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.command.PurgeReport;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.rollback.PurgeScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bare purge used to inherit the caller's world. Standing in the overworld
 * and purging "90 days" then deleted only overworld rows and reported a small
 * count. The caller's world must be ignored unless the filter already names one.
 */
class PurgeScopeTest {

    @Test
    void callerWorldIsNotAppliedToABarePurge() {
        QueryFilter bare = QueryFilter.builder().sinceMillis(1L).build();
        QueryFilter prepared = PurgeScope.prepare(bare, "minecraft:overworld");

        assertThat(prepared.worldSel()).isNull();
        assertThat(PurgeScope.label(prepared)).isEqualTo("all worlds");
        assertThat(prepared.withDefaultWorld("minecraft:overworld").worldSel().worldKey())
            .isEqualTo("minecraft:overworld");
    }

    @Test
    void namedWorldIsKeptAndShown() {
        QueryFilter nether = QueryFilter.builder()
            .sinceMillis(1L)
            .worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false))
            .build();

        QueryFilter prepared = PurgeScope.prepare(nether, "minecraft:overworld");

        assertThat(prepared.worldSel().worldKey()).isEqualTo("minecraft:the_nether");
        assertThat(PurgeScope.label(prepared)).isEqualTo("minecraft:the_nether");
    }

    @Test
    void confirmationNamesTheWorldItActedOn() {
        PurgeEngine.PurgeResult deleted = new PurgeEngine.PurgeResult(
            3L, 2_592_000L, 1L, null, 3L, List.of(), PurgeEngine.PurgeResult.Mode.DELETE);
        QueryFilter nether = QueryFilter.builder()
            .worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false))
            .build();

        assertThat(PurgeReport.lines(deleted, nether).get(0))
            .isEqualTo("[VonixGuardian] Purge removed 3 rows in minecraft:the_nether (minAge=2592000s).");
        assertThat(PurgeReport.lines(deleted, null).get(0))
            .contains("in all worlds");
    }
}
