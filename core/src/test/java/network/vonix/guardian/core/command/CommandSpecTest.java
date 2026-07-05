package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            CommandSpec.UNDO, CommandSpec.STATUS, CommandSpec.RELOAD,
            CommandSpec.HELP);

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
                case "lookup", "rollback", "restore", "purge" ->
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
}
