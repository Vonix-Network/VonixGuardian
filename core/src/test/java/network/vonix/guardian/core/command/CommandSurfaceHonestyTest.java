package network.vonix.guardian.core.command;

import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.query.QueryFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command surface must describe what the server does, not what it was
 * meant to do. Three separate claims are pinned here:
 *
 * <ol>
 *   <li>every node {@code /vg help} prints is a node a gate actually evaluates
 *       (a {@link PermissionNode} constant),</li>
 *   <li>the nodes the help calls unenforced really are unenforced and really do
 *       exist, so the list cannot drift into fiction in either direction,</li>
 *   <li>{@code #preview} is never described as drawing in the world, and the
 *       undo text names its restart limitation.</li>
 * </ol>
 */
class CommandSurfaceHonestyTest {

    @Test
    void everySubcommandResolvesToARealPermissionNode() {
        for (SubcommandSpec spec : CommandSpec.all()) {
            String node = CommandSpec.authoritativeNode(spec.name());
            assertThat(PermissionNode.fromNodeOrCommandAlias(node))
                .as("%s advertises node %s, which no enum constant parses to", spec.name(), node)
                .isNotNull();
        }
        assertThat(PermissionNode.fromNodeOrCommandAlias(CommandSpec.authoritativeNode("")))
            .as("the /vg root gate must be a real node")
            .isEqualTo(PermissionNode.BASE);
    }

    @Test
    void helpPrintsTheNodeShapesTheTreeActuallyGatesOn() {
        assertThat(CommandSpec.authoritativeNode(CommandSpec.LOOKUP))
            .as("lookup gates on vonixguardian.lookup, not the .command. spelling")
            .isEqualTo("vonixguardian.lookup");
        assertThat(CommandSpec.authoritativeNode(CommandSpec.ROLLBACK))
            .isEqualTo("vonixguardian.rollback");
        assertThat(CommandSpec.authoritativeNode(CommandSpec.CONFIG))
            .as("config is one of the four genuine .command. nodes")
            .isEqualTo("vonixguardian.command.config");
        assertThat(CommandSpec.authoritativeNode(CommandSpec.ENTITYLOG))
            .as("entitylog edits config and is gated by the config node")
            .isEqualTo("vonixguardian.command.config");
        assertThat(CommandSpec.authoritativeNode(CommandSpec.MIGRATE_DB))
            .as("migrate-db is stored with an underscore")
            .isEqualTo("vonixguardian.migrate_db");
        assertThat(CommandSpec.permissionNode(CommandSpec.LOOKUP))
            .as("the legacy spelling must stay queryable for existing grants")
            .isEqualTo("vonixguardian.command.lookup");
    }

    @Test
    void gatedNodeListIsDeduplicatedAndCoversEverySubcommand() {
        List<String> nodes = CommandSpec.gatedNodes();
        assertThat(nodes).doesNotHaveDuplicates();

        List<String> expected = new ArrayList<>();
        expected.add(CommandSpec.authoritativeNode(""));
        for (SubcommandSpec spec : CommandSpec.all()) {
            String node = CommandSpec.authoritativeNode(spec.name());
            if (!expected.contains(node)) {
                expected.add(node);
            }
        }
        assertThat(nodes).containsExactlyElementsOf(expected);
    }

    @Test
    void unenforcedNodeListIsRealAndDisjointFromTheGatedNodes() {
        List<String> unenforced = CommandHelp.unenforcedNodes();
        assertThat(unenforced).isNotEmpty();
        for (String node : unenforced) {
            assertThat(PermissionNode.fromNodeOrCommandAlias(node))
                .as("%s is named as reserved; it must at least exist in the enum", node)
                .isNotNull();
            assertThat(CommandSpec.gatedNodes())
                .as("%s is named as reserved but the help also lists it as gated", node)
                .doesNotContain(node);
        }
        // The three world-facing nodes the audit named as dead stay named.
        assertThat(unenforced).contains(
            "vonixguardian.lookup.ip",
            "vonixguardian.bypass.inspector",
            "vonixguardian.help");
    }

    @Test
    void helpNeverClaimsPreviewPaintsTheWorld() {
        String text = String.join("\n", CommandHelp.full());
        assertThat(text)
            .as("help has to say what #preview really does")
            .contains("#preview draws nothing");
        assertThat(text.toLowerCase(java.util.Locale.ROOT))
            .as("help must not promise glass, ghost blocks, or markers")
            .doesNotContain("ghost block")
            .doesNotContain("glass block")
            .doesNotContain("in glass");
    }

    @Test
    void undoUsageNamesTheRestartLimitationAndASurvivingPath() {
        String text = String.join("\n", CommandHelp.usage("undo"));
        assertThat(text)
            .as("undo history is in memory only")
            .contains("in memory only")
            .contains("restart")
            .as("the operator needs a path that survives a restart")
            .contains("/vg batch");
        assertThat(text)
            .as("undo still re-states that it changes the world")
            .contains("changes the world");
    }

    @Test
    void helpStatesTheClickRowsAreAuditOnlyRatherThanLeavingTheTokenUnexplained() {
        // LANE 4 wired ActionType.CLICK to real producers (right-click block in
        // ForgeEvents plus the interaction mixins), so the token is no longer a
        // lie. It is still non-rollbackable, and a help page that lists a: against
        // rollback without saying so invites an operator to plan a rollback that
        // silently skips those rows.
        assertThat(String.join("\n", CommandHelp.full()))
            .contains("a:click")
            .contains("never rolled back");
        assertThat(network.vonix.guardian.core.query.ActionTokens.ALL)
            .as("the token must still be offered by tab completion")
            .contains("click");
    }

    @Test
    void previewResultLineNamesTheWorldAndTheTwoWaysOut() {
        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();
        network.vonix.guardian.core.rollback.RollbackResult preview =
            new network.vonix.guardian.core.rollback.RollbackResult(
                null, network.vonix.guardian.core.rollback.RollbackResult.Mode.ROLLBACK,
                true, List.of(1L, 2L), List.of(), 2, 0, filter);
        String line = MutationOutcomeFormatter.summaryLine("Rollback", preview);
        assertThat(line)
            .as("a preview must say the world was not changed, deny drawing, and name both exits: %s", line)
            .contains("The world was not changed")
            .contains("no blocks were drawn")
            .contains("/vg apply")
            .contains("/vg cancel");
    }

    @Test
    void rollbackAndApplyUsageDescribeThePreviewLifecycleWithoutDrawing() {
        String rollback = String.join("\n", CommandHelp.usage("rollback"));
        assertThat(rollback)
            .contains("#preview")
            .contains("draws nothing")
            .contains("/vg apply")
            .contains("/vg cancel");
        assertThat(String.join("\n", CommandHelp.usage("apply")))
            .contains("changes blocks in the world");
        assertThat(String.join("\n", CommandHelp.usage("batch")))
            .as("batch is the restart-surviving path and must say so")
            .contains("survives a restart");
    }
}
