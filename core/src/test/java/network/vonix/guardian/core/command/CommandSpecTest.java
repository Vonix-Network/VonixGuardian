package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CommandSpecTest {

    @Test
    void rootAndAliasMatchSharedContracts() {
        assertThat(CommandSpec.ROOT).isEqualTo("vg");
        assertThat(CommandSpec.ALIAS).isEqualTo("guardian");
    }

    @Test
    void subcommandConstantsMatchSharedContracts() {
        assertThat(CommandSpec.INSPECT).isEqualTo("inspect");
        assertThat(CommandSpec.LOOKUP).isEqualTo("lookup");
        assertThat(CommandSpec.ROLLBACK).isEqualTo("rollback");
        assertThat(CommandSpec.RESTORE).isEqualTo("restore");
        assertThat(CommandSpec.PURGE).isEqualTo("purge");
        assertThat(CommandSpec.NEAR).isEqualTo("near");
        assertThat(CommandSpec.UNDO).isEqualTo("undo");
        assertThat(CommandSpec.STATUS).isEqualTo("status");
        assertThat(CommandSpec.RELOAD).isEqualTo("reload");
        assertThat(CommandSpec.HELP).isEqualTo("help");
    }

    @Test
    void allContainsEverySubcommandExactlyOnce() {
        List<String> expected = List.of(
            CommandSpec.INSPECT, CommandSpec.LOOKUP, CommandSpec.ROLLBACK,
            CommandSpec.RESTORE, CommandSpec.PURGE, CommandSpec.NEAR,
            CommandSpec.UNDO, CommandSpec.BATCH, CommandSpec.STATUS, CommandSpec.RELOAD,
            CommandSpec.HELP, CommandSpec.APPLY, CommandSpec.CANCEL,
            CommandSpec.CONSUMER, CommandSpec.CONFIG, CommandSpec.ENTITYLOG,
            CommandSpec.TELEPORT, CommandSpec.GIVE, CommandSpec.MIGRATE_DB);

        List<SubcommandSpec> all = CommandSpec.all();
        assertThat(all).hasSize(expected.size());
        Set<String> seen = new HashSet<>();
        for (SubcommandSpec s : all) {
            assertThat(seen.add(s.name())).as("duplicate %s", s.name()).isTrue();
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void everySubcommandHasMatchingPermissionNode() {
        for (SubcommandSpec s : CommandSpec.all()) {
            assertThat(s.permissionNode())
                .as("permission node of %s", s.name())
                .isEqualTo("vonixguardian.command." + s.name());
            assertThat(s.permissionNode())
                .isEqualTo(CommandSpec.permissionNode(s.name()));
        }
    }

    @Test
    void everySubcommandHasNonBlankHelpSummary() {
        for (SubcommandSpec s : CommandSpec.all()) {
            assertThat(s.helpSummary()).as("help of %s", s.name()).isNotBlank();
            assertThat(s.arguments()).isNotNull();
        }
    }

    @Test
    void argumentRequiringSubcommandsAreFlagged() {
        for (SubcommandSpec s : CommandSpec.all()) {
            switch (s.name()) {
                case "lookup", "rollback", "restore", "purge", "config", "teleport", "give", "migrate-db" ->
                    assertThat(s.requiresArguments()).as("%s requires args", s.name()).isTrue();
                default ->
                    assertThat(s.requiresArguments()).as("%s no args", s.name()).isFalse();
            }
        }
    }

    @Test
    void purgeUsesFilterTokensArgument() {
        SubcommandSpec purge = findByName(CommandSpec.PURGE);
        assertThat(purge.arguments()).containsExactly(ArgumentSpec.FILTER_TOKENS);
    }

    @Test
    void lookupUsesFilterAndPageArguments() {
        SubcommandSpec lookup = findByName(CommandSpec.LOOKUP);
        assertThat(lookup.arguments()).containsExactly(
            ArgumentSpec.FILTER_TOKENS, ArgumentSpec.PAGE_NUMBER);
    }

    @Test
    void rollbackAndRestoreTakeFilterTokens() {
        assertThat(findByName(CommandSpec.ROLLBACK).arguments())
            .containsExactly(ArgumentSpec.FILTER_TOKENS);
        assertThat(findByName(CommandSpec.RESTORE).arguments())
            .containsExactly(ArgumentSpec.FILTER_TOKENS);
    }

    @Test
    void permissionPrefixIsFrozen() {
        assertThat(CommandSpec.PERMISSION_PREFIX).isEqualTo("vonixguardian.command.");
    }

    /**
     * Every alias the spec advertises must be a literal some loader actually
     * registers. Two aliases ({@code n} for near, {@code ?} for help) were
     * advertised for years and registered in zero cells, so {@code /vg help}
     * offered commands that did not exist. Deleting them is only half the fix;
     * this keeps a future alias from being declared without a tree behind it.
     */
    @Test
    void everyDeclaredAliasIsRegisteredByTheForgeCell() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Path cell = root.resolve("mc-1.18.2/forge/src/main/java/network/vonix/guardian"
            + "/mc/v1_18_2/common/GuardianCommands.java");
        assumeTrue(Files.exists(cell), "1.18.2 forge cell not present");
        String text = Files.readString(cell);
        for (SubcommandSpec spec : CommandSpec.all()) {
            String alias = spec.alias();
            if (alias == null) {
                continue;
            }
            assertThat(text)
                .as("CommandSpec advertises alias %s for %s, but no cell registers it",
                    alias, spec.name())
                .contains("Commands.literal(\"" + alias + "\")");
        }
    }

    @Test
    void subcommandSpecRejectsBlankFields() {
        assertThatThrownBy(() ->
            new SubcommandSpec("", null, "vonixguardian.command.x", false, "h", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subcommandSpecCopiesArgumentList() {
        java.util.ArrayList<ArgumentSpec> mutable = new java.util.ArrayList<>();
        mutable.add(ArgumentSpec.FILTER_TOKENS);
        SubcommandSpec s = new SubcommandSpec(
            "x", null, "vonixguardian.command.x", true, "help", mutable);
        mutable.clear();
        assertThat(s.arguments()).containsExactly(ArgumentSpec.FILTER_TOKENS);
    }

    private static SubcommandSpec findByName(String name) {
        return CommandSpec.all().stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))
                    && Files.exists(p.resolve("mc-1.18.2/forge"))) {
                return p;
            }
        }
        return null;
    }
}
