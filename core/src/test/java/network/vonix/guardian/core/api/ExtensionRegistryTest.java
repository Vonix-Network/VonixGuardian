/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.EventSubmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * LANE 28 — proves the extension API is a dispatcher, not a manifest.
 *
 * <p>The defect this lane exists to close is an API that <em>declares</em>
 * extension points while nothing calls them. A test that only asserted
 * {@code register()} returns a binding would pass against a registry that is
 * pure bookkeeping. So every reachability assertion here goes through the
 * dispatch path and checks the thing that actually received the work:
 * <ul>
 *   <li>{@link #registered_action_type_is_reachable_through_the_submitter()} —
 *       a declared token, submitted through the registry, arrives at the
 *       {@link EventSubmitter} as a real {@link Action};</li>
 *   <li>{@link #declared_but_unregistered_extension_is_not_reachable()} — the
 *       same declaration, never registered, does not resolve. Together these
 *       two are the difference between an API and documentation;</li>
 *   <li>{@link #registered_command_reaches_its_handler()} — a declared literal,
 *       dispatched through the registry, runs the handler and receives the
 *       invocation the contract promises.</li>
 * </ul>
 * Duplicate rejection is covered per namespace (id, action token, command) and
 * is proven to be all-or-nothing, because a half-registered extension that
 * answers some commands and not others is worse than a rejected one.
 */
class ExtensionRegistryTest {

    private static final String EXT_ID = "acme.claims";

    private EventSubmitter submitter;
    private ExtensionRegistry.CommandAuthorizer authorizer;
    private ExtensionRegistry registry;

    /** Nodes the fake authorizer treats as granted. */
    private final List<String> granted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        submitter = mock(EventSubmitter.class);
        authorizer = (uuid, node) -> granted.contains(node);
        registry = new ExtensionRegistry(submitter, authorizer);
    }

    // ------------------------------------------------------------ reachability

    @Test
    void registered_action_type_is_reachable_through_the_submitter() {
        registry.register(new TestExtension(EXT_ID)
                .withAction("claim.enter", ActionType.CLICK, "a player entered a claim"));

        UUID actor = UUID.randomUUID();
        registry.submitAction("claim.enter", actor, "Notch", "minecraft:overworld",
                10, 64, -20, "minecraft:oak_door", 1);

        ArgumentCaptor<Action> captured = ArgumentCaptor.forClass(Action.class);
        verify(submitter, times(1)).submit(captured.capture());

        Action action = captured.getValue();
        assertThat(action.type())
                .as("the declared base type is what storage sees")
                .isEqualTo(ActionType.CLICK);
        assertThat(action.sourceTag())
                .as("the row still says which extension produced it")
                .isEqualTo("ext:acme.claims/claim.enter");
        assertThat(action.actorUuid()).isEqualTo(actor);
        assertThat(action.actorName()).isEqualTo("Notch");
        assertThat(action.worldId()).isEqualTo("minecraft:overworld");
        assertThat(action.x()).isEqualTo(10);
        assertThat(action.y()).isEqualTo(64);
        assertThat(action.z()).isEqualTo(-20);
        assertThat(action.targetId()).isEqualTo("minecraft:oak_door");
        assertThat(action.amount()).isEqualTo(1);
        assertThat(action.timestamp()).isPositive();
        assertThat(action.id()).as("not persisted yet").isEqualTo(-1L);
    }

    @Test
    void declared_but_unregistered_extension_is_not_reachable() {
        TestExtension neverRegistered = new TestExtension("acme.never")
                .withAction("ghost.token", ActionType.BLOCK_PLACE, null);

        // The declaration exists as an object; nothing bound it. If submitAction
        // resolved it anyway, registration would be decoration.
        assertThat(neverRegistered.actionTypes()).hasSize(1);
        assertThatThrownBy(() -> registry.submitAction("ghost.token", null, "#tnt",
                "minecraft:overworld", 0, 0, 0, "minecraft:tnt", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost.token");
        verifyNoInteractions(submitter);
    }

    @Test
    void unknown_action_token_does_not_reach_the_submitter() {
        registry.register(new TestExtension(EXT_ID).withAction("claim.enter", ActionType.CLICK, null));

        assertThatThrownBy(() -> registry.submitAction("claim.exit", null, "#unknown",
                "minecraft:overworld", 0, 0, 0, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claim.exit");
        verifyNoInteractions(submitter);
    }

    @Test
    void action_token_resolution_is_case_insensitive_and_exposed_for_completion() {
        registry.register(new TestExtension(EXT_ID).withAction("Claim.Enter", ActionType.CLICK, null));

        assertThat(registry.findAction("claim.enter")).isPresent();
        assertThat(registry.findAction("CLAIM.ENTER")).isPresent();
        assertThat(registry.findAction("nope")).isEmpty();
        assertThat(registry.findAction(null)).isEmpty();

        assertThat(registry.actionTypes())
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.extensionId()).isEqualTo(EXT_ID);
                    assertThat(registered.definition().base()).isEqualTo(ActionType.CLICK);
                    assertThat(registered.sourceTag()).isEqualTo("ext:acme.claims/claim.enter");
                });
        assertThat(registry.sourceTag("claim.enter")).isEqualTo("ext:acme.claims/claim.enter");
    }

    // --------------------------------------------------------- command dispatch

    @Test
    void registered_command_reaches_its_handler() {
        AtomicReference<GuardianExtension.CommandInvocation> seen = new AtomicReference<>();
        Map<String, String> config = new LinkedHashMap<>();
        config.put("max-claims", "8");
        config.put("mode", "strict");

        registry.register(new TestExtension(EXT_ID)
                .withConfig(config)
                .withCommand(new GuardianExtension.CommandDef(
                        "claims", "vonixguardian.ext.acme.claims", "/vg claims <list|info>",
                        "Manage claims.",
                        invocation -> {
                            seen.set(invocation);
                            return GuardianExtension.CommandResult.ok("3 claims");
                        })));

        // A cell turns the exposed literal into a brigadier node ...
        assertThat(registry.commands())
                .singleElement()
                .satisfies(command -> {
                    assertThat(command.definition().name()).isEqualTo("claims");
                    assertThat(command.definition().permissionNode())
                            .isEqualTo("vonixguardian.ext.acme.claims");
                });
        assertThat(registry.handlesCommand("CLAIMS")).isTrue();

        // ... and routes execution back through the dispatcher.
        granted.add("vonixguardian.ext.acme.claims");
        UUID actor = UUID.randomUUID();
        ExtensionRegistry.Dispatch dispatch =
                registry.dispatchCommand("claims", actor, List.of("list", "extra"));

        assertThat(dispatch.status()).isEqualTo(ExtensionRegistry.Dispatch.Status.OK);
        assertThat(dispatch.succeeded()).isTrue();
        assertThat(dispatch.message()).isEqualTo("3 claims");
        assertThat(dispatch.extensionId()).isEqualTo(EXT_ID);

        GuardianExtension.CommandInvocation invocation = seen.get();
        assertThat(invocation).as("the handler actually ran").isNotNull();
        assertThat(invocation.actor()).isEqualTo(actor);
        assertThat(invocation.extensionId()).isEqualTo(EXT_ID);
        assertThat(invocation.commandName()).isEqualTo("claims");
        assertThat(invocation.arguments()).containsExactly("list", "extra");
        assertThat(invocation.configBlock())
                .as("the declared config block is published to the handler")
                .containsEntry("max-claims", "8")
                .containsEntry("mode", "strict");
    }

    @Test
    void command_without_the_permission_node_never_runs_its_handler() {
        List<String> calls = new ArrayList<>();
        registry.register(new TestExtension(EXT_ID)
                .withCommand(simpleCommand("claims", "vonixguardian.ext.acme.claims", calls)));

        ExtensionRegistry.Dispatch dispatch =
                registry.dispatchCommand("claims", UUID.randomUUID(), List.of());

        assertThat(dispatch.status()).isEqualTo(ExtensionRegistry.Dispatch.Status.DENIED);
        assertThat(calls).as("handler must not run for a denied actor").isEmpty();
        assertThat(dispatch.message()).contains("vonixguardian.ext.acme.claims");
    }

    @Test
    void console_origin_bypasses_the_permission_node() {
        List<String> calls = new ArrayList<>();
        registry.register(new TestExtension(EXT_ID)
                .withCommand(simpleCommand("claims", "vonixguardian.ext.acme.claims", calls)));

        ExtensionRegistry.Dispatch dispatch = registry.dispatchCommand("claims", null, List.of());

        assertThat(dispatch.status()).isEqualTo(ExtensionRegistry.Dispatch.Status.OK);
        assertThat(calls).containsExactly("claims");
    }

    @Test
    void unregistered_command_is_unknown_and_does_not_run() {
        registry.register(new TestExtension(EXT_ID)
                .withCommand(simpleCommand("claims", "vonixguardian.ext.acme.claims", new ArrayList<>())));

        ExtensionRegistry.Dispatch dispatch = registry.dispatchCommand("claimz", null, List.of());

        assertThat(dispatch.status()).isEqualTo(ExtensionRegistry.Dispatch.Status.UNKNOWN);
        assertThat(dispatch.extensionId()).isNull();
        assertThat(dispatch.succeeded()).isFalse();
    }

    @Test
    void a_throwing_handler_is_contained_and_reported_as_failed() {
        registry.register(new TestExtension(EXT_ID).withCommand(new GuardianExtension.CommandDef(
                "boom", "vonixguardian.ext.acme.boom",
                invocation -> {
                    throw new IllegalStateException("extension bug");
                })));

        ExtensionRegistry.Dispatch dispatch = registry.dispatchCommand("boom", null, List.of());

        assertThat(dispatch.status())
                .as("an extension bug must degrade to one failed command, not escape")
                .isEqualTo(ExtensionRegistry.Dispatch.Status.FAILED);
        assertThat(dispatch.message()).contains("extension bug");
    }

    @Test
    void a_throwing_permission_backend_denies_rather_than_allows() {
        ExtensionRegistry hostile = new ExtensionRegistry(submitter, (uuid, node) -> {
            throw new IllegalStateException("luckperms down");
        });
        List<String> calls = new ArrayList<>();
        hostile.register(new TestExtension(EXT_ID)
                .withCommand(simpleCommand("claims", "vonixguardian.ext.acme.claims", calls)));

        ExtensionRegistry.Dispatch dispatch =
                hostile.dispatchCommand("claims", UUID.randomUUID(), List.of());

        assertThat(dispatch.status()).isEqualTo(ExtensionRegistry.Dispatch.Status.DENIED);
        assertThat(calls).isEmpty();
    }

    @Test
    void null_result_from_a_handler_is_success_with_no_message() {
        registry.register(new TestExtension(EXT_ID).withCommand(new GuardianExtension.CommandDef(
                "quiet", "vonixguardian.ext.quiet", invocation -> null)));

        ExtensionRegistry.Dispatch dispatch = registry.dispatchCommand("quiet", null, List.of());

        assertThat(dispatch.status()).isEqualTo(ExtensionRegistry.Dispatch.Status.OK);
        assertThat(dispatch.message()).isNull();
    }

    // ------------------------------------------------------- duplicate rejection

    @Test
    void duplicate_extension_id_is_rejected() {
        registry.register(new TestExtension(EXT_ID)
                .withAction("claim.enter", ActionType.CLICK, null)
                .withCommand(simpleCommand("claims", "vonixguardian.ext.acme.claims", new ArrayList<>())));

        TestExtension second = new TestExtension(EXT_ID)
                .withAction("other.token", ActionType.BLOCK_PLACE, null);

        assertThatThrownBy(() -> registry.register(second))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("duplicate extension id")
                .hasMessageContaining(EXT_ID);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.extensionIds()).containsExactly(EXT_ID);
        assertThat(registry.findAction("other.token"))
                .as("the rejected extension contributed nothing")
                .isEmpty();
        assertThat(registry.findAction("claim.enter")).isPresent();
    }

    @Test
    void duplicate_extension_id_is_rejected_even_when_the_second_declares_nothing() {
        registry.register(new TestExtension(EXT_ID));

        assertThatThrownBy(() -> registry.register(new TestExtension(EXT_ID)))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("duplicate extension id");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void duplicate_action_token_across_extensions_is_rejected_and_rolls_back() {
        registry.register(new TestExtension("acme.one")
                .withAction("claim.enter", ActionType.CLICK, null));

        TestExtension conflicting = new TestExtension("acme.two")
                .withAction("acme.two.ok", ActionType.BLOCK_PLACE, null)
                .withAction("claim.enter", ActionType.BLOCK_BREAK, null);

        assertThatThrownBy(() -> registry.register(conflicting))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("claim.enter")
                .hasMessageContaining("acme.one");

        assertThat(registry.isRegistered("acme.two"))
                .as("all-or-nothing: the whole extension is rejected")
                .isFalse();
        assertThat(registry.findAction("acme.two.ok"))
                .as("its non-conflicting declaration was not committed either")
                .isEmpty();
        assertThat(registry.findAction("claim.enter"))
                .as("the original owner keeps its token")
                .isPresent()
                .get()
                .satisfies(registered -> assertThat(registered.extensionId()).isEqualTo("acme.one"));
    }

    @Test
    void duplicate_action_token_inside_one_extension_is_rejected() {
        TestExtension twice = new TestExtension(EXT_ID)
                .withAction("claim.enter", ActionType.CLICK, null)
                .withAction("Claim.Enter", ActionType.CLICK, null);

        assertThatThrownBy(() -> registry.register(twice))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("twice");
        assertThat(registry.size()).isZero();
    }

    @Test
    void duplicate_command_across_extensions_is_rejected() {
        registry.register(new TestExtension("acme.one")
                .withCommand(simpleCommand("claims", "vonixguardian.ext.one.claims", new ArrayList<>())));

        TestExtension conflicting = new TestExtension("acme.two")
                .withCommand(simpleCommand("claims", "vonixguardian.ext.two.claims", new ArrayList<>()));

        assertThatThrownBy(() -> registry.register(conflicting))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("claims")
                .hasMessageContaining("acme.one");
        assertThat(registry.isRegistered("acme.two")).isFalse();
        assertThat(registry.handlesCommand("claims")).isTrue();
    }

    @Test
    void duplicate_command_inside_one_extension_is_rejected() {
        TestExtension twice = new TestExtension(EXT_ID)
                .withCommand(simpleCommand("claims", "vonixguardian.ext.a", new ArrayList<>()))
                .withCommand(simpleCommand("claims", "vonixguardian.ext.b", new ArrayList<>()));

        assertThatThrownBy(() -> registry.register(twice))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void action_token_may_not_shadow_the_builtin_a_vocabulary() {
        // Built-in tokens, family umbrellas and CP aliases are all already
        // meaningful to QueryParser; shadowing any of them makes a: ambiguous.
        for (String reserved : List.of("block", "container", "kill", "login", "inventory")) {
            ExtensionRegistry fresh = new ExtensionRegistry(submitter, authorizer);
            TestExtension shadowing = new TestExtension(EXT_ID)
                    .withAction(reserved, ActionType.CLICK, null);

            assertThatThrownBy(() -> fresh.register(shadowing))
                    .as("token '%s' must be refused", reserved)
                    .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                    .hasMessageContaining(reserved)
                    .hasMessageContaining("reserved");
            assertThat(fresh.size()).isZero();
        }
    }

    @Test
    void a_sign_prefixed_action_token_is_malformed_not_silently_accepted() {
        // '+' and '-' are the built-in place/break markers on a: tokens; letting
        // an extension declare one would create a token QueryParser reads as a
        // sign-modified form of something else.
        ExtensionRegistry fresh = new ExtensionRegistry(submitter, authorizer);

        assertThatThrownBy(() -> fresh.register(
                new TestExtension(EXT_ID).withAction("+claim", ActionType.CLICK, null)))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("malformed")
                .hasMessageContaining("leading sign");
        assertThat(fresh.size()).isZero();
    }

    @Test
    void command_may_not_shadow_a_builtin_vg_literal_or_alias() {
        for (String reserved : List.of("lookup", "rollback", "l", "rb", "tp", "vg", "guardian")) {
            ExtensionRegistry fresh = new ExtensionRegistry(submitter, authorizer);
            TestExtension shadowing = new TestExtension(EXT_ID)
                    .withCommand(simpleCommand(reserved, "vonixguardian.ext.x", new ArrayList<>()));

            assertThatThrownBy(() -> fresh.register(shadowing))
                    .as("command '%s' must be refused", reserved)
                    .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                    .hasMessageContaining(reserved);
            assertThat(fresh.size()).isZero();
        }
    }

    @Test
    void malformed_identifiers_tokens_and_commands_are_rejected() {
        assertThatThrownBy(() -> registry.register(new TestExtension("Acme Claims")))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> registry.register(new TestExtension("")))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> registry.register(new TestExtension(null)))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("id");

        ExtensionRegistry forToken = new ExtensionRegistry(submitter, authorizer);
        assertThatThrownBy(() -> forToken.register(
                new TestExtension(EXT_ID).withAction("Claim Enter", ActionType.CLICK, null)))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("malformed");

        ExtensionRegistry forCommand = new ExtensionRegistry(submitter, authorizer);
        assertThatThrownBy(() -> forCommand.register(
                new TestExtension(EXT_ID).withCommand(
                        simpleCommand("Claims!", "vonixguardian.ext.x", new ArrayList<>()))))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void a_source_tag_that_would_overflow_varchar_64_is_refused_rather_than_truncated() {
        // id up to 48 chars + "/" + token up to 47 + "ext:" = 100 > 64.
        String longId = "a".repeat(48);
        TestExtension overflowing = new TestExtension(longId)
                .withAction("b".repeat(40), ActionType.CLICK, null);

        assertThatThrownBy(() -> registry.register(overflowing))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("source_tag")
                .hasMessageContaining("64");
        assertThat(registry.size()).isZero();
    }

    // ------------------------------------------------------------- registration

    @Test
    void onRegister_receives_the_binding_and_a_throw_rolls_everything_back() {
        AtomicReference<GuardianExtension.Binding> seen = new AtomicReference<>();
        TestExtension good = new TestExtension(EXT_ID)
                .withAction("claim.enter", ActionType.CLICK, null)
                .onRegister(seen::set);

        GuardianExtension.Binding returned = registry.register(good);

        assertThat(seen.get()).as("onRegister ran exactly once").isSameAs(returned);
        assertThat(returned.extensionId()).isEqualTo(EXT_ID);
        assertThat(returned.actionTypes()).hasSize(1);

        TestExtension failing = new TestExtension("acme.failing")
                .withAction("acme.failing.token", ActionType.CLICK, null)
                .withCommand(simpleCommand("failingcmd", "vonixguardian.ext.f", new ArrayList<>()))
                .onRegister(binding -> {
                    throw new IllegalStateException("init failed");
                });

        assertThatThrownBy(() -> registry.register(failing))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("acme.failing")
                .hasMessageContaining("rolled back")
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(registry.isRegistered("acme.failing"))
                .as("a half-initialised extension must not stay bound")
                .isFalse();
        assertThat(registry.findAction("acme.failing.token")).isEmpty();
        assertThat(registry.handlesCommand("failingcmd"))
                .as("no command may resolve to a handler that never initialised")
                .isFalse();
        assertThat(registry.isRegistered(EXT_ID)).as("the earlier extension is untouched").isTrue();
    }

    @Test
    void config_block_is_frozen_at_registration() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("mode", "strict");
        AtomicReference<Map<String, String>> seenByHandler = new AtomicReference<>();

        registry.register(new TestExtension(EXT_ID)
                .withConfig(mutable)
                .withCommand(new GuardianExtension.CommandDef(
                        "claims", "vonixguardian.ext.claims",
                        invocation -> {
                            seenByHandler.set(invocation.configBlock());
                            return GuardianExtension.CommandResult.ok(null);
                        })));

        mutable.put("mode", "permissive");
        mutable.put("injected", "later");

        registry.dispatchCommand("claims", null, List.of());

        assertThat(seenByHandler.get())
                .containsEntry("mode", "strict")
                .doesNotContainKey("injected");
        assertThatThrownBy(() -> seenByHandler.get().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void null_config_values_are_rejected() {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("ok", "fine");
        bad.put("broken", null);

        assertThatThrownBy(() -> registry.register(new TestExtension(EXT_ID).withConfig(bad)))
                .isInstanceOf(ExtensionRegistry.RegistrationException.class)
                .hasMessageContaining("broken");
        assertThat(registry.size()).isZero();
    }

    @Test
    void an_extension_with_no_declarations_registers_and_is_visible() {
        GuardianExtension.Binding binding = registry.register(new TestExtension(EXT_ID));

        assertThat(binding.actionTypes()).isEmpty();
        assertThat(binding.commands()).isEmpty();
        assertThat(binding.configBlock()).isEmpty();
        assertThat(registry.isRegistered(EXT_ID)).isTrue();
        assertThat(registry.binding(EXT_ID)).contains(binding);
        assertThat(registry.commands()).isEmpty();
        assertThat(registry.actionTypes()).isEmpty();
        verify(submitter, never()).submit(any());
    }

    @Test
    void two_tokens_sharing_a_base_type_stay_distinguishable_by_tag() {
        registry.register(new TestExtension(EXT_ID)
                .withAction("claim.enter", ActionType.CLICK, null)
                .withAction("claim.exit", ActionType.CLICK, null));

        // Both tokens share a base type; the registry must still distinguish
        // them by tag, or an operator could not tell entry from exit in the log.
        registry.submitAction("claim.enter", null, "#unknown", "minecraft:overworld", 0, 0, 0, null, 0);
        registry.submitAction("claim.exit", null, "#unknown", "minecraft:overworld", 0, 0, 0, null, 0);

        ArgumentCaptor<Action> captured = ArgumentCaptor.forClass(Action.class);
        verify(submitter, times(2)).submit(captured.capture());
        assertThat(captured.getAllValues())
                .extracting(Action::sourceTag)
                .containsExactly("ext:acme.claims/claim.enter", "ext:acme.claims/claim.exit");
    }

    // ------------------------------------------------------------------ fixtures

    /** Mirrors {@code GuardianExtension#onRegister} without the interface noise. */
    @FunctionalInterface
    private interface RegisterCallback {
        void accept(GuardianExtension.Binding binding);
    }

    /**
     * Minimal concrete extension. Declarations accumulate through the
     * {@code withXxx} methods, which return {@code this} so a test can build
     * one in a single expression.
     */
    private static final class TestExtension implements GuardianExtension {

        private final String id;
        private Map<String, String> config = Map.of();
        private final List<ActionTypeDef> actions = new ArrayList<>();
        private final List<CommandDef> commands = new ArrayList<>();
        private RegisterCallback onRegisterCallback = binding -> { };

        private TestExtension(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Map<String, String> configBlock() {
            return config;
        }

        @Override
        public List<ActionTypeDef> actionTypes() {
            return actions;
        }

        @Override
        public List<CommandDef> commands() {
            return commands;
        }

        @Override
        public void onRegister(Binding binding) {
            onRegisterCallback.accept(binding);
        }

        private TestExtension withConfig(Map<String, String> config) {
            this.config = config;
            return this;
        }

        private TestExtension withAction(String token, ActionType base, String description) {
            actions.add(new ActionTypeDef(token, base, description));
            return this;
        }

        private TestExtension withCommand(CommandDef command) {
            commands.add(command);
            return this;
        }

        private TestExtension onRegister(RegisterCallback callback) {
            this.onRegisterCallback = callback;
            return this;
        }
    }

    private static GuardianExtension.CommandDef simpleCommand(String name, String node, List<String> calls) {
        return new GuardianExtension.CommandDef(name, node, invocation -> {
            calls.add(invocation.commandName());
            return GuardianExtension.CommandResult.ok("ran " + invocation.commandName());
        });
    }
}
