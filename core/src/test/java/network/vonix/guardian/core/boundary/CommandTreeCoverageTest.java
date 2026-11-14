package network.vonix.guardian.core.boundary;

import network.vonix.guardian.core.command.CommandTree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CommandTree} is meant to become the single source of truth for the
 * command surface, replacing ten hand-written registrations.
 *
 * <p>Adoption is only safe if the tree describes the commands that actually
 * exist. If it omits a verb, adopting it silently deletes that command from the
 * server; if it invents one, the cell refuses to register a tree it cannot
 * dispatch. This test pins the tree's shape so a future change to it is a
 * deliberate decision rather than a surprise discovered by an operator.</p>
 */
class CommandTreeCoverageTest {

    /** Every literal the tree publishes, at the top level. */
    private static Set<String> topLevelLiterals() {
        Set<String> out = new TreeSet<>();
        for (CommandTree.Node n : CommandTree.topLevel()) {
            out.add(n.literal());
        }
        return out;
    }

    @Test
    void treeIsNotEmpty() {
        assertThat(topLevelLiterals())
            .as("an empty tree would mean every cell registers nothing")
            .isNotEmpty();
    }

    /**
     * The verbs a server operator types. Pinned explicitly so removing one from
     * the tree is a visible diff in this test, not a silently missing command.
     */
    @Test
    void coreVerbsAreAllPresent() {
        Set<String> literals = topLevelLiterals();
        Set<String> required = new TreeSet<>(Set.of(
            "help", "lookup", "inspect", "rollback", "restore", "purge", "near",
            "undo", "status", "reload", "config", "batch", "consumer",
            "entitylog", "teleport", "give"));
        // Deliberately not required: "preview" is a *subcommand* under rollback/
        // restore (/vg rollback ... #preview), not a top-level verb.

        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(literals);
        assertThat(missing)
            .as("verbs missing from CommandTree; adopting the tree would delete them "
                + "from every cell at once")
            .isEmpty();
    }

    /** Redirects let /co and /guardian reach the same tree instead of duplicating it. */
    @Test
    void redirectsCoverCoreprotectMuscleMemory() {
        assertThat(CommandTree.redirects())
            .as("CoreProtect's /co is the alias operators reach for by habit")
            .contains("co");
    }

    /** Every node's handlers must be declared, or the cell cannot dispatch it. */
    @Test
    void everyHandlerTheTreeNeedsIsDeclared() {
        Set<CommandTree.Handler> all = new LinkedHashSet<>(CommandTree.handlers());
        assertThat(all)
            .as("a tree with no handlers is a tree of dead literals")
            .isNotEmpty();
        assertThat(all)
            .as("a null handler id would defeat missingHandlers() checking")
            .doesNotContainNull();
    }

    /**
     * A fresh cell that supplies nothing must be told about every handler. If this
     * returned empty, adoption would look complete while every command failed.
     */
    @Test
    void missingHandlersReportsEverythingWhenNothingIsSupplied() {
        Set<CommandTree.Handler> needed = new LinkedHashSet<>(CommandTree.handlers());
        assertThat(CommandTree.missingHandlers(Set.of()))
            .as("a cell supplying no handlers is missing all of them")
            .hasSize(needed.size());
    }

    /** Supplying everything must report nothing missing. */
    @Test
    void missingHandlersIsEmptyWhenAllAreSupplied() {
        Set<CommandTree.Handler> all = new LinkedHashSet<>(CommandTree.handlers());
        assertThat(CommandTree.missingHandlers(all)).isEmpty();
    }
}
