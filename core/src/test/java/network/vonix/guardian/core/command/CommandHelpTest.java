package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The old {@code /vg help} text said undo drops a history entry. The command
 * re-mutates the world. Help must name every subcommand and must not say that.
 */
class CommandHelpTest {

    @Test
    void helpNamesEverySubcommandAndTellsTheTruthAboutUndo() {
        String text = String.join("\n", CommandHelp.full());
        for (SubcommandSpec spec : CommandSpec.all()) {
            assertThat(text).as(spec.name()).contains("/vg " + spec.name());
        }
        assertThat(CommandSpec.all()).hasSizeGreaterThanOrEqualTo(18);
        assertThat(text).doesNotContain("drop last rollback");
        assertThat(text).contains("changes the world again");
        assertThat(text).contains("<page>[:<perPage>] before filter");
        assertThat(text).contains("#preview and #count do not");
    }

    @Test
    void undoUsageDoesNotDescribeAHistoryEdit() {
        List<String> lines = CommandHelp.usage("undo");
        String text = String.join("\n", lines);
        assertThat(text).contains("changes the world");
        assertThat(text).doesNotContain("history");
        assertThat(text).doesNotContain("drop last");
    }
}
