/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.command.SubcommandSpec;
import network.vonix.guardian.core.event.EventSubmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Binding and dispatch for {@link GuardianExtension} registrations.
 *
 * <p>This is the part that makes the extension contract an API rather than a
 * manifesto. A declaration alone changes nothing; every token an extension
 * declares is inert until something resolves it and every command is a string
 * until something calls its handler. Both of those paths live here, and both
 * consult the same tables that {@link #register} wrote, so a registered
 * extension is reachable and an unregistered one is not.
 *
 * <h2>Two dispatch paths</h2>
 *
 * <ol>
 *   <li><b>Action tokens</b> — {@link #submitAction} resolves a declared token
 *       to its {@link GuardianExtension.ActionTypeDef}, builds the storage row
 *       under {@link GuardianExtension.ActionTypeDef#base()} and stamps
 *       {@code source_tag} with {@code ext:<extension id>/<token>} before
 *       handing it to the {@link EventSubmitter}. Resolution and submission are
 *       one call so a caller cannot resolve a token and then forget to label
 *       the row.</li>
 *   <li><b>Commands</b> — {@link #dispatchCommand} resolves a literal, checks
 *       the declared permission node, invokes the handler and contains its
 *       failures. {@link #commands()} exposes the registered literals for a
 *       cell to turn into brigadier nodes; the cell routes execution back
 *       here.</li>
 * </ol>
 *
 * <h2>Duplicate rejection</h2>
 *
 * <p>Every registration is all-or-nothing. Before anything is committed the
 * whole declaration is validated; a conflict anywhere rejects the entire
 * extension and leaves the registry exactly as it was. That matters because a
 * partially-registered extension is worse than a rejected one: it would answer
 * some commands and not others, and the operator's only clue would be a command
 * that silently does nothing.
 *
 * <p>Rejected as duplicates or conflicts:</p>
 * <ul>
 *   <li>an {@link GuardianExtension#id()} already registered;</li>
 *   <li>an action token already declared by another extension, or twice by the
 *       same one;</li>
 *   <li>a command literal already declared by another extension, or twice by
 *       the same one;</li>
 *   <li>an action token that collides with a built-in {@link ActionType} token,
 *       an {@code a:} family name, or a CoreProtect compatibility alias — the
 *       query parser already gives those a meaning, so a shadowing declaration
 *       would make {@code a:block} ambiguous. A token is matched after
 *       lower-casing and trimming, and the checked spellings are
 *       {@link ActionType#token()} as written (so {@code +block} and
 *       {@code -block} are covered by the pattern's ban on a leading sign, and
 *       {@code block} by the family list), every
 *       {@link ActionType.Category} name lower-cased, and the three aliases
 *       {@code login}, {@code logout}, {@code inventory};</li>
 *   <li>a command literal that collides with a {@code /vg} subcommand or with
 *       the single alias {@link network.vonix.guardian.core.command.CommandSpec}
 *       declares for it — {@code /vg lookup} must keep meaning what it
 *       means.</li>
 * </ul>
 *
 * <p><b>Known gap in that last check.</b> The reserved set is built from
 * {@code CommandSpec}, which declares one alias per subcommand. Individual
 * cells also register extra literals that {@code CommandSpec} does not know
 * about — {@code inspector}, {@code page}, {@code ro}, {@code re} — so an
 * extension command named {@code page} would pass this registry and still
 * collide in a cell that registers that literal. Closing that needs a cell-side
 * assertion, not a wider constant here: the aliases differ per cell and core
 * cannot enumerate them.
 *
 * <h2>Failure containment</h2>
 *
 * <p>Nothing a registered extension throws as a {@link RuntimeException} escapes
 * into the caller's command path or server tick. A command handler that throws
 * produces {@link Dispatch.Status#FAILED} with a logged stack trace and no
 * propagation; {@link GuardianExtension#onRegister} throwing rolls the
 * registration back and fails the {@code register} call, which is the one place
 * a caller is meant to notice. {@link Error} is not caught: an extension that
 * exhausts the heap or blows the stack has already broken the process, and
 * swallowing that would hide it.
 *
 * <h2>Threading</h2>
 *
 * <p>Registration is serialised on an internal lock and is expected at boot.
 * Reads — token resolution, command dispatch, the snapshot accessors — take no
 * lock: they read immutable maps that registration replaces wholesale. A
 * dispatch that races a registration therefore either sees the old world or the
 * new one, never half of each.
 *
 * @since 2.3.0 (wave 2, lane 28)
 */
public final class ExtensionRegistry {

    /** Identifier grammar: lowercase, namespace-ish, at most 48 characters. */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");

    /** Action token grammar: lowercase, no leading sign, at most 47 characters. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,46}");

    /** Command literal grammar: lowercase word-ish, at most 32 characters. */
    private static final Pattern COMMAND_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    /** Prefix of every {@code source_tag} an extension action is stamped with. */
    private static final String SOURCE_TAG_PREFIX = "ext:";

    /** {@code vg_actions.source_tag} is {@code VARCHAR(64)}; see {@code Schema}. */
    private static final int SOURCE_TAG_MAX = 64;

    /**
     * {@code a:} vocabulary that already means something to
     * {@code QueryParser}: built-in tokens, family names and the CoreProtect
     * aliases. Computed once; the enums are immutable.
     */
    private static final Set<String> RESERVED_ACTION_TOKENS = buildReservedTokens();

    /** {@code /vg} literals an extension command may not shadow. */
    private static final Set<String> RESERVED_COMMAND_NAMES = buildReservedCommands();

    private final EventSubmitter submitter;
    private final CommandAuthorizer authorizer;

    private final Object lock = new Object();

    /** Live registrations by id, in registration order. */
    private volatile Map<String, GuardianExtension.Binding> bindings =
            Collections.unmodifiableMap(new LinkedHashMap<>());

    /** Token (lower-case) to its owning declaration. */
    private volatile Map<String, RegisteredAction> actions =
            Collections.unmodifiableMap(new LinkedHashMap<>());

    /** Command literal to its owning declaration. */
    private volatile Map<String, RegisteredCommand> commands =
            Collections.unmodifiableMap(new LinkedHashMap<>());

    /**
     * @param submitter  receives every action an extension token produces;
     *                   normally {@code Guardian#submitter()}
     * @param authorizer decides whether an actor may run an extension
     *                   command; normally a thin adapter over
     *                   {@code PermissionResolver#has(UUID, String)}
     */
    public ExtensionRegistry(EventSubmitter submitter, CommandAuthorizer authorizer) {
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    private static Set<String> buildReservedTokens() {
        Set<String> reserved = new HashSet<>();
        for (ActionType type : ActionType.values()) {
            reserved.add(type.token());
        }
        for (ActionType.Category category : ActionType.Category.values()) {
            reserved.add(category.name().toLowerCase(Locale.ROOT));
        }
        // Keep in sync with QueryParser.CP_TOKEN_ALIASES / CP_MULTI_ALIASES.
        reserved.add("login");
        reserved.add("logout");
        reserved.add("inventory");
        return Set.copyOf(reserved);
    }

    private static Set<String> buildReservedCommands() {
        Set<String> reserved = new HashSet<>();
        reserved.add(CommandSpec.ROOT);
        reserved.add(CommandSpec.ALIAS);
        for (SubcommandSpec spec : CommandSpec.all()) {
            reserved.add(spec.name());
            if (spec.alias() != null) {
                reserved.add(spec.alias());
            }
        }
        return Set.copyOf(reserved);
    }

    // ------------------------------------------------------------------ register

    /**
     * Validates and binds one extension.
     *
     * <p>All-or-nothing: on any conflict or validation failure nothing is
     * committed, and {@link GuardianExtension#onRegister} is not called. See the
     * class Javadoc for what counts as a conflict.
     *
     * @param extension the extension to bind; must not be {@code null}
     * @return the frozen binding, identical to the value passed to
     *         {@link GuardianExtension#onRegister}
     * @throws RegistrationException if the identifier is malformed, a
     *         declaration conflicts with an existing registration or with a
     *         built-in name, or {@code onRegister} threw
     */
    public GuardianExtension.Binding register(GuardianExtension extension) {
        Objects.requireNonNull(extension, "extension");

        String id = normalizeId(extension.id());
        Map<String, String> config = freezeConfig(extension.configBlock(), id);
        List<GuardianExtension.ActionTypeDef> declaredTypes = declaredActionTypes(extension, id);
        List<GuardianExtension.CommandDef> declaredCommands = declaredCommands(extension, id);

        synchronized (lock) {
            if (bindings.containsKey(id)) {
                throw new RegistrationException("duplicate extension id '" + id
                        + "' — already registered by "
                        + bindings.get(id).extensionId());
            }
            Map<String, RegisteredAction> nextActions = new LinkedHashMap<>(actions);
            Map<String, RegisteredCommand> nextCommands = new LinkedHashMap<>(commands);

            // Normalised declarations, built once and used for both the binding
            // and the dispatch tables, so introspection cannot report a spelling
            // that would not resolve.
            List<GuardianExtension.ActionTypeDef> acceptedTypes = new ArrayList<>(declaredTypes.size());
            for (GuardianExtension.ActionTypeDef def : declaredTypes) {
                String token = normalizeToken(def.token(), id);
                if (RESERVED_ACTION_TOKENS.contains(token)) {
                    throw new RegistrationException("extension '" + id + "' declares action token '"
                            + token + "', which is reserved by the built-in a: vocabulary"
                            + " (an ActionType token, a family name, or a CoreProtect alias)");
                }
                RegisteredAction prior = nextActions.get(token);
                if (prior != null) {
                    throw new RegistrationException("extension '" + id + "' declares action token '"
                            + token + "', already declared by extension '" + prior.extensionId() + "'");
                }
                GuardianExtension.ActionTypeDef normalized =
                        new GuardianExtension.ActionTypeDef(token, def.base(), def.description());
                acceptedTypes.add(normalized);
                nextActions.put(token, new RegisteredAction(
                        id, normalized, sourceTagFor(id, token)));
            }

            List<GuardianExtension.CommandDef> acceptedCommands =
                    new ArrayList<>(declaredCommands.size());
            for (GuardianExtension.CommandDef def : declaredCommands) {
                String name = normalizeCommandName(def.name(), id);
                if (RESERVED_COMMAND_NAMES.contains(name)) {
                    throw new RegistrationException("extension '" + id + "' declares command '"
                            + name + "', which shadows a built-in /vg literal or alias");
                }
                RegisteredCommand prior = nextCommands.get(name);
                if (prior != null) {
                    throw new RegistrationException("extension '" + id + "' declares command '"
                            + name + "', already declared by extension '" + prior.extensionId() + "'");
                }
                GuardianExtension.CommandDef normalized = new GuardianExtension.CommandDef(
                        name, def.permissionNode(), def.usage(), def.description(), def.handler());
                acceptedCommands.add(normalized);
                nextCommands.put(name, new RegisteredCommand(id, normalized));
            }

            GuardianExtension.Binding binding = new GuardianExtension.Binding(
                    id, config, acceptedTypes, acceptedCommands);

            Map<String, GuardianExtension.Binding> nextBindings = new LinkedHashMap<>(bindings);
            nextBindings.put(id, binding);

            // Commit. Publication order is irrelevant to correctness — the
            // dispatch paths consult the action and command tables, and a reader
            // that sees a binding before its entries simply observes an
            // extension that has not declared anything yet.
            bindings = Collections.unmodifiableMap(nextBindings);
            actions = Collections.unmodifiableMap(nextActions);
            commands = Collections.unmodifiableMap(nextCommands);

            try {
                extension.onRegister(binding);
            } catch (RuntimeException failure) {
                // Roll the whole thing back so a half-initialised extension
                // cannot leave a command resolving to a handler that never ran.
                rollBack(id);
                throw new RegistrationException("extension '" + id
                        + "' failed in onRegister and was rolled back: " + failure, failure);
            }
            return binding;
        }
    }

    /**
     * Removes every trace of {@code id}. Called only while holding {@link #lock},
     * from the {@code onRegister} failure path. Rebuilds the maps rather than
     * mutating them, so a concurrent reader holding the previous immutable
     * snapshot is unaffected.
     */
    private void rollBack(String id) {
        Map<String, GuardianExtension.Binding> keptBindings = new LinkedHashMap<>(bindings);
        keptBindings.remove(id);
        bindings = Collections.unmodifiableMap(keptBindings);

        Map<String, RegisteredAction> keptActions = new LinkedHashMap<>();
        actions.forEach((token, action) -> {
            if (!id.equals(action.extensionId())) {
                keptActions.put(token, action);
            }
        });
        actions = Collections.unmodifiableMap(keptActions);

        Map<String, RegisteredCommand> keptCommands = new LinkedHashMap<>();
        commands.forEach((name, command) -> {
            if (!id.equals(command.extensionId())) {
                keptCommands.put(name, command);
            }
        });
        commands = Collections.unmodifiableMap(keptCommands);
    }

    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RegistrationException("extension id must not be null or blank");
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(trimmed).matches()) {
            throw new RegistrationException("extension id '" + trimmed
                    + "' is malformed — expected lowercase [a-z0-9][a-z0-9._-]{0,47}");
        }
        return trimmed;
    }

    private static Map<String, String> freezeConfig(Map<String, String> raw, String id) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new RegistrationException("extension '" + id
                        + "' has a config block with a null or blank key");
            }
            if (entry.getValue() == null) {
                throw new RegistrationException("extension '" + id + "' config key '"
                        + key + "' has a null value");
            }
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<GuardianExtension.ActionTypeDef> declaredActionTypes(
            GuardianExtension extension, String id) {
        List<GuardianExtension.ActionTypeDef> raw = extension.actionTypes();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<GuardianExtension.ActionTypeDef> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (GuardianExtension.ActionTypeDef def : raw) {
            if (def == null) {
                throw new RegistrationException("extension '" + id
                        + "' declares a null action type");
            }
            String token = normalizeToken(def.token(), id);
            if (!seen.add(token)) {
                throw new RegistrationException("extension '" + id
                        + "' declares action token '" + token + "' twice");
            }
            out.add(def);
        }
        return List.copyOf(out);
    }

    private static String normalizeToken(String raw, String id) {
        if (raw == null || raw.isBlank()) {
            throw new RegistrationException("extension '" + id
                    + "' declares an action token that is null or blank");
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!TOKEN_PATTERN.matcher(trimmed).matches()) {
            throw new RegistrationException("extension '" + id + "' action token '" + trimmed
                    + "' is malformed — expected lowercase [a-z0-9][a-z0-9._-]{0,46}"
                    + " (no leading sign: +/- is the built-in place/break marker)");
        }
        String tag = sourceTagFor(id, trimmed);
        if (tag.length() > SOURCE_TAG_MAX) {
            throw new RegistrationException("extension '" + id + "' action token '" + trimmed
                    + "' produces a source_tag of " + tag.length() + " characters ('" + tag
                    + "'); vg_actions.source_tag allows " + SOURCE_TAG_MAX
                    + " and truncation could make two extensions collide");
        }
        return trimmed;
    }

    private static List<GuardianExtension.CommandDef> declaredCommands(
            GuardianExtension extension, String id) {
        List<GuardianExtension.CommandDef> raw = extension.commands();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<GuardianExtension.CommandDef> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (GuardianExtension.CommandDef def : raw) {
            if (def == null) {
                throw new RegistrationException("extension '" + id + "' declares a null command");
            }
            String name = normalizeCommandName(def.name(), id);
            if (!seen.add(name)) {
                throw new RegistrationException("extension '" + id
                        + "' declares command '" + name + "' twice");
            }
            out.add(def);
        }
        return List.copyOf(out);
    }

    private static String normalizeCommandName(String raw, String id) {
        if (raw == null || raw.isBlank()) {
            throw new RegistrationException("extension '" + id
                    + "' declares a command with a null or blank name");
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (!COMMAND_PATTERN.matcher(trimmed).matches()) {
            throw new RegistrationException("extension '" + id + "' command '" + trimmed
                    + "' is malformed — expected lowercase [a-z][a-z0-9_-]{0,31}");
        }
        return trimmed;
    }

    // ------------------------------------------------------- action-type dispatch

    /**
     * Submits an action under an extension-declared token.
     *
     * <p>The row is persisted as {@link GuardianExtension.ActionTypeDef#base()}
     * and tagged {@code ext:<extension id>/<token>}, so storage, query and
     * rollback all see a type they already understand while the audit log still
     * says which extension produced it.
     *
     * <p><b>No return value, deliberately.</b> {@link EventSubmitter#submit(Action)}
     * is {@code void}: the event gate either enqueues the row or drops it, and
     * no caller in core can observe which. Returning {@code true} here would be
     * a claim this class cannot make. A caller that needs to know whether
     * anything was recorded must look at {@code Guardian#submitted()} /
     * {@code Guardian#gated()}, or query the log afterwards.
     *
     * @param token    a declared action token; matched case-insensitively
     * @param actorUuid player UUID, or {@code null} for a non-player source
     * @param actorName resolved name or sentinel; {@code null} becomes
     *                  {@code "#unknown"}
     * @param worldId  dimension key; must not be {@code null}
     * @param x        block X
     * @param y        block Y
     * @param z        block Z
     * @param targetId affected block / item / entity id, or {@code null}
     * @param amount   stack count, or {@code 0} when not applicable
     * @throws IllegalArgumentException if {@code token} was never declared, or
     *         {@code worldId} is {@code null}
     */
    public void submitAction(String token, UUID actorUuid, String actorName,
                             String worldId, int x, int y, int z,
                             String targetId, int amount) {
        Objects.requireNonNull(worldId, "worldId");
        RegisteredAction registered = requireAction(token);
        Action action = new Action(
                -1L,
                System.currentTimeMillis(),
                registered.definition().base(),
                actorUuid,
                actorName == null ? "#unknown" : actorName,
                worldId,
                x, y, z,
                targetId,
                null,
                amount,
                false,
                registered.sourceTag());
        submitter.submit(action);
    }

    /**
     * Resolves a declared action token without submitting anything.
     *
     * <p>Exists for tab-completion, {@code /vg status} and tests. Normal use
     * goes through {@link #submitAction}, which cannot resolve a token and then
     * fail to label the row.
     *
     * @param token token to resolve; {@code null} yields empty
     * @return the registration, or empty when the token is unknown
     */
    public Optional<RegisteredAction> findAction(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(actions.get(token.trim().toLowerCase(Locale.ROOT)));
    }

    private RegisteredAction requireAction(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("action token must not be null or blank");
        }
        String key = token.trim().toLowerCase(Locale.ROOT);
        RegisteredAction registered = actions.get(key);
        if (registered == null) {
            throw new IllegalArgumentException("no extension declares action token '" + key
                    + "'; registered: " + actions.keySet());
        }
        return registered;
    }

    /**
     * The {@code source_tag} this registry stamps on rows submitted through the
     * given token.
     *
     * @param token a declared action token
     * @return {@code ext:<extension id>/<token>}
     * @throws IllegalArgumentException if the token was never declared
     */
    public String sourceTag(String token) {
        return requireAction(token).sourceTag();
    }

    private static String sourceTagFor(String id, String token) {
        return SOURCE_TAG_PREFIX + id + "/" + token;
    }

    // ------------------------------------------------------------ command dispatch

    /**
     * Runs a registered extension command.
     *
     * <p>Resolution, authorisation and invocation in one call, so a cell cannot
     * accidentally skip the permission check by looking the handler up itself.
     * A handler that throws is contained here — see the class Javadoc.
     *
     * <p>Console and other non-player origins ({@code actor == null}) bypass the
     * permission node, matching how every built-in subcommand treats the
     * console: it is already the server operator. A cell that runs commands from
     * an unattended source should pass the UUID of the entity that caused them,
     * or {@code null} only when it genuinely means "the server".
     *
     * @param name      registered command literal
     * @param actor     player UUID, or {@code null} for console / system origins
     * @param arguments raw trailing arguments; {@code null} is treated as empty
     * @return the outcome; never {@code null}
     */
    public Dispatch dispatchCommand(String name, UUID actor, List<String> arguments) {
        if (name == null || name.isBlank()) {
            return Dispatch.unknown("", "command name must not be blank");
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        RegisteredCommand registered = commands.get(key);
        if (registered == null) {
            return Dispatch.unknown(key, "no extension declares command '" + key + "'");
        }

        GuardianExtension.CommandDef def = registered.definition();
        if (actor != null && !isAuthorized(actor, def.permissionNode(), registered)) {
            return Dispatch.denied(registered, "you do not have " + def.permissionNode());
        }

        GuardianExtension.CommandInvocation invocation = new GuardianExtension.CommandInvocation(
                actor,
                registered.extensionId(),
                key,
                arguments,
                configBlockFor(registered.extensionId()));

        GuardianExtension.CommandResult result;
        try {
            result = def.handler().handle(invocation);
        } catch (RuntimeException failure) {
            LOG.warn("extension '{}' command '{}' threw; reporting FAILED",
                    registered.extensionId(), key, failure);
            return Dispatch.failed(registered, "command failed: " + failure);
        }
        if (result == null) {
            return Dispatch.ok(registered, null);
        }
        return result.success()
                ? Dispatch.ok(registered, result.message())
                : Dispatch.failed(registered, result.message());
    }

    private boolean isAuthorized(UUID actor, String node, RegisteredCommand registered) {
        try {
            return authorizer.isPermitted(actor, node);
        } catch (RuntimeException failure) {
            // A permission backend that throws must not turn into an allow, and
            // must not take down the command path either.
            LOG.warn("extension '{}' command '{}' permission check threw; denying",
                    registered.extensionId(), registered.definition().name(), failure);
            return false;
        }
    }

    private Map<String, String> configBlockFor(String extensionId) {
        GuardianExtension.Binding binding = bindings.get(extensionId);
        return binding == null ? Map.of() : binding.configBlock();
    }

    /**
     * Whether a command literal is handled by an extension.
     *
     * @param name literal to test; {@code null} returns {@code false}
     * @return {@code true} when {@link #dispatchCommand} would find it
     */
    public boolean handlesCommand(String name) {
        return name != null && commands.containsKey(name.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * Registered command literals, in registration order — what a cell turns
     * into brigadier nodes.
     *
     * @return immutable, ordered list; never {@code null}
     */
    public List<RegisteredCommand> commands() {
        return List.copyOf(commands.values());
    }

    /**
     * Registered action tokens, in registration order.
     *
     * @return immutable, ordered list; never {@code null}
     */
    public List<RegisteredAction> actionTypes() {
        return List.copyOf(actions.values());
    }

    /**
     * @return registered extension ids in registration order
     */
    public List<String> extensionIds() {
        return List.copyOf(bindings.keySet());
    }

    /**
     * @param id extension identifier
     * @return the frozen binding, or empty when nothing registered under that id
     */
    public Optional<GuardianExtension.Binding> binding(String id) {
        return id == null ? Optional.empty()
                : Optional.ofNullable(bindings.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * @return how many extensions are currently bound
     */
    public int size() {
        return bindings.size();
    }

    /**
     * @param id extension identifier
     * @return {@code true} when an extension is bound under that id
     */
    public boolean isRegistered(String id) {
        return id != null && bindings.containsKey(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Diagnostics only. */
    private static final Logger LOG = LoggerFactory.getLogger(ExtensionRegistry.class);

    // ------------------------------------------------------------------- value types

    /**
     * Decides whether an actor may run an extension command.
     *
     * <p>A functional interface rather than a {@code PermissionResolver}
     * dependency: core's permission stack needs a config block and a LuckPerms
     * bridge, and an extension test should not have to stand those up. A cell
     * adapts it in one lambda.
     */
    @FunctionalInterface
    public interface CommandAuthorizer {

        /**
         * @param actor player asking; never {@code null} — console bypasses the
         *              check before it reaches this method
         * @param node  the declared permission node
         * @return {@code true} to allow
         */
        boolean isPermitted(UUID actor, String node);
    }

    /**
     * One bound action token.
     *
     * @param extensionId owning extension
     * @param definition  the declaration as registered
     * @param sourceTag   the {@code source_tag} stamped on every row it writes
     */
    public record RegisteredAction(String extensionId,
                                   GuardianExtension.ActionTypeDef definition,
                                   String sourceTag) {

        public RegisteredAction {
            Objects.requireNonNull(extensionId, "extensionId");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(sourceTag, "sourceTag");
        }
    }

    /**
     * One bound command.
     *
     * @param extensionId owning extension
     * @param definition  the declaration as registered
     */
    public record RegisteredCommand(String extensionId,
                                    GuardianExtension.CommandDef definition) {

        public RegisteredCommand {
            Objects.requireNonNull(extensionId, "extensionId");
            Objects.requireNonNull(definition, "definition");
        }
    }

    /**
     * Outcome of {@link #dispatchCommand}.
     *
     * @param status      what happened
     * @param extensionId owning extension, or {@code null} when nothing matched
     * @param commandName the literal that was looked up, lower-cased
     * @param message     text to show the caller, or {@code null} for silence
     */
    public record Dispatch(Status status, String extensionId, String commandName, String message) {

        /** How a dispatch ended. */
        public enum Status {
            /** The handler ran and reported success. */
            OK,
            /** The handler ran and reported failure, or threw. */
            FAILED,
            /** The actor lacked the declared permission node; the handler did not run. */
            DENIED,
            /** No extension declares that literal; the handler did not run. */
            UNKNOWN
        }

        public Dispatch {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(commandName, "commandName");
        }

        static Dispatch ok(RegisteredCommand command, String message) {
            return new Dispatch(Status.OK, command.extensionId(), command.definition().name(), message);
        }

        static Dispatch failed(RegisteredCommand command, String message) {
            return new Dispatch(Status.FAILED, command.extensionId(), command.definition().name(), message);
        }

        static Dispatch denied(RegisteredCommand command, String message) {
            return new Dispatch(Status.DENIED, command.extensionId(), command.definition().name(), message);
        }

        static Dispatch unknown(String name, String message) {
            return new Dispatch(Status.UNKNOWN, null, name, message);
        }

        /**
         * @return {@code true} only for {@link Status#OK}; a {@code DENIED} or
         *         {@code UNKNOWN} result is not a failure to report as an error
         *         in a log, but it is not success either
         */
        public boolean succeeded() {
            return status == Status.OK;
        }
    }

    /**
     * Thrown when a registration is rejected.
     *
     * <p>The message states the conflict plainly because it is shown to whoever
     * loaded the extension, and "duplicate extension id" is the one thing that
     * person needs to fix.
     */
    public static final class RegistrationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message what is wrong with the registration
         */
        public RegistrationException(String message) {
            super(message);
        }

        /**
         * @param message what is wrong with the registration
         * @param cause   the failure that triggered the rollback
         */
        public RegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
