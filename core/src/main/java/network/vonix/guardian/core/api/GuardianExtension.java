/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.action.ActionType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service-provider contract for code that extends VonixGuardian from another
 * mod or plugin.
 *
 * <p>{@link VonixGuardianAPI} lets a consumer <em>read</em> the audit log and
 * <em>submit</em> events. It has no way to add anything: every action type is a
 * fixed enum constant, every command literal is compiled into the cell, and
 * every storage backend is chosen by {@code config.database.type}. This
 * interface is the minimal answer to the first two: an extension declares an
 * identifier, a configuration block, the action tokens it produces and the
 * commands it answers, and {@link ExtensionRegistry} binds those declarations
 * to something that actually dispatches them.
 *
 * <h2>What an extension is, and is not</h2>
 *
 * <p>An extension is a <em>registration</em>, not a classloader or a sandbox.
 * It runs in the same JVM, on the same classloader as the caller that
 * registered it, with the same access to the server as the caller. There is no
 * isolation, no separate thread, no reload of extension bytecode: register at
 * boot, before players can issue commands. Nothing here makes a hostile
 * extension safe. A jar able to reach this interface is already running
 * arbitrary code.
 *
 * <p>Explicitly <b>out of scope</b>, so nobody reads more into this contract
 * than it says:
 * <ul>
 *   <li><b>Storage backends.</b> There is no hook to add a DAO. Extensions
 *       persist through the configured backend, or not at all.</li>
 *   <li><b>New database action ids.</b> See the next section.</li>
 *   <li><b>Query-language vocabulary.</b> The {@code a:} token set comes from
 *       {@code ActionType} and stays closed; see below.</li>
 *   <li><b>Client-side code.</b> Nothing here sends packets. That seam is
 *       {@link ClientResultSink}.</li>
 * </ul>
 *
 * <h2>Custom action types are declarations, not enum constants</h2>
 *
 * <p>{@link ActionType} is a closed enum, and each constant carries the
 * {@code SMALLINT} id stored in {@code vg_actions.type}. Those ids are stable
 * database keys: a third party cannot append a constant without renumbering
 * every existing row, and a classloader that defines a second copy of the enum
 * would produce values the DAO cannot read. So an extension action type is a
 * <em>token plus a declared base</em>:
 * {@link ActionTypeDef#token()} is the extension's own name for the event, and
 * {@link ActionTypeDef#base()} is the built-in type under which it is actually
 * persisted and later rolled back.
 *
 * <p>Consequences an extension author must accept:
 * <ul>
 *   <li>Rows written through an extension token are indistinguishable from the
 *       base type in storage. They are selected by {@code a:} using the
 *       <em>base</em> token, and rollback/restore follows the base type's
 *       rules. If the base is {@code CLICK}, the row is recorded and never
 *       reversed, exactly like a native click.</li>
 *   <li>An extension token may not reuse a built-in token, a built-in family
 *       name ({@code block}, {@code container}, ...) or a CoreProtect
 *       compatibility alias ({@code login}, {@code logout},
 *       {@code inventory}) — the registry rejects those at registration,
 *       because {@code QueryParser} already gives them a meaning.</li>
 *   <li>The origin of the row is preserved in one place only:
 *       {@code source_tag} becomes {@code ext:<extension id>/<token>}, so an
 *       operator filtering or reading a row can see which extension produced
 *       it. The tag is capped at the 64 characters the column allows; the
 *       registry refuses an id/token pair that would overflow it rather than
 *       truncating, since truncation can make two extensions collide.</li>
 * </ul>
 *
 * <h2>Commands</h2>
 *
 * <p>{@link CommandDef} declares a literal, the permission node that gates it,
 * and a handler. The registry owns resolution, permission checks, argument
 * passing and failure containment; it does not own brigadier. A cell that
 * exposes extension commands registers the literals from
 * {@link ExtensionRegistry#commands()} and routes execution to
 * {@link ExtensionRegistry#dispatchCommand}. Name collisions are rejected at
 * registration: an extension command may not shadow a built-in {@code /vg}
 * subcommand or alias, so {@code /vg lookup} keeps meaning what it means.
 *
 * <h2>Configuration block</h2>
 *
 * <p>{@link #configBlock()} is a flat {@code String -> String} map, and the
 * registry stores it verbatim for the lifetime of the registration. It is
 * deliberately not merged into {@code GuardianConfig}: that record is written
 * to {@code config.json} by {@code ConfigLoader}, and silently injecting
 * third-party keys into an operator's file would change a document they own.
 * An extension that wants persistent settings should read them from its own
 * file and expose the result here; the block is published to command handlers
 * through {@link CommandInvocation#configBlock()} so one extension cannot see
 * another's settings.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #onRegister} runs exactly once, after every declaration has passed
 * validation and been committed to the registry, and receives the frozen
 * {@link Binding}. If it throws, the registration is rolled back: the id, its
 * tokens and its commands all disappear, so a half-bound extension cannot
 * leave a command that resolves to a handler that never initialised. Nothing
 * in this contract runs at shutdown; extensions that hold resources should
 * register their own lifecycle with their loader.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #id()}, {@link #configBlock()}, {@link #actionTypes()} and
 * {@link #commands()} are called once, inside {@code register}, on the thread
 * that registers. They are not consulted again, so an implementation may build
 * its declarations lazily and need not be thread-safe. Handlers, by contrast,
 * run on whatever thread executes the command — normally the server thread —
 * and must be thread-safe if the cell can invoke them from more than one.
 *
 * @since 2.3.0 (wave 2, lane 28)
 */
public interface GuardianExtension {

    /**
     * Version of the shape this interface defines: the component order of
     * {@link ActionTypeDef}, {@link CommandDef}, {@link CommandInvocation},
     * {@link CommandResult} and {@link Binding}.
     *
     * <p>Adding a method with a default implementation does not bump this; a
     * changed or removed record component does. Nothing in this build compares
     * the constant against anything at runtime — a consumer compiled against a
     * different shape fails at link time instead, which is the stronger check.
     * The constant exists so a future out-of-process extension host, where the
     * compiler is not shared, has a number to handshake on.
     */
    int CONTRACT_VERSION = 1;

    /**
     * Stable, namespaced identifier, e.g. {@code "acme.claims"}.
     *
     * <p>Lowercase, and matched by {@code [a-z0-9][a-z0-9._-]{0,47}}. Used as
     * the uniqueness key for the registration and as the first half of every
     * {@code source_tag} this extension writes, so it should name the project,
     * not the feature. Two extensions with the same id cannot coexist — the
     * second registration is rejected, never merged — because a merged id would
     * make {@code ext:acme/x} ambiguous in the audit log.
     *
     * @return the extension identifier; never {@code null}, never blank
     */
    String id();

    /**
     * Flat settings block published to this extension's command handlers.
     *
     * <p>See the class Javadoc for why this is not {@code GuardianConfig}.
     * Keys are free-form but must be non-blank; values must be non-null. The
     * map is copied and frozen at registration, so mutating the returned map
     * afterwards has no effect on what handlers see.
     *
     * @return the settings block; {@code null} is treated as empty
     */
    default Map<String, String> configBlock() {
        return Map.of();
    }

    /**
     * Action tokens this extension produces.
     *
     * <p>Declared tokens are not enum constants: each one persists under the
     * {@link ActionTypeDef#base()} type it names. See the class Javadoc for the
     * full set of consequences.
     *
     * @return the declared action types, in declaration order; may be empty
     */
    default List<ActionTypeDef> actionTypes() {
        return List.of();
    }

    /**
     * Commands this extension answers.
     *
     * @return the declared commands, in declaration order; may be empty
     */
    default List<CommandDef> commands() {
        return List.of();
    }

    /**
     * Called once, after the declarations are validated and committed, with the
     * frozen view of what was bound.
     *
     * <p>The default does nothing. An implementation that throws causes the
     * whole registration to be rolled back and the {@code register} call to
     * fail with {@link ExtensionRegistry.RegistrationException}; throwing the
     * same exception type directly is allowed but changes nothing, since the
     * registry wraps it either way.
     *
     * <p>Do not re-enter the registry from here to register a second
     * extension: {@code register} holds its lock and Java's intrinsic locks are
     * reentrant, so a nested call on the <em>same</em> registry would work but
     * a nested call that fails would roll back only its own state. Register the
     * second extension from the caller after this method returns.
     *
     * @param binding what was bound for this extension; never {@code null}
     */
    default void onRegister(Binding binding) {
        // optional
    }

    /**
     * Declaration of one extension action token and the built-in type it is
     * stored as.
     *
     * @param token       the extension's own name for the event, e.g.
     *                    {@code "claim.enter"}; matched case-insensitively and
     *                    normalised to lower case by the registry
     * @param base        the built-in {@link ActionType} whose database id,
     *                    category, sign and rollback rules apply
     * @param description one line for {@code /vg status} and logs; may be
     *                    {@code null}, stored as an empty string
     */
    record ActionTypeDef(String token, ActionType base, String description) {

        public ActionTypeDef {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(base, "base");
            if (description == null) {
                description = "";
            }
        }
    }

    /**
     * Declaration of one command.
     *
     * @param name           literal, lowercase, e.g. {@code "claims"}; may not
     *                       collide with a built-in {@code /vg} subcommand or
     *                       alias, nor with another extension's command
     * @param permissionNode node the dispatch gate evaluates, e.g.
     *                       {@code "vonixguardian.ext.acme.claims"}. Free-form:
     *                       the registry does not validate it against
     *                       {@code PermissionNode}, because that enum is closed
     *                       and third-party nodes cannot join it. A node no
     *                       permission system grants simply denies everyone
     *                       except console, which is the safe default
     * @param usage          one-line syntax shown by the cell's help and by
     *                       usage errors, e.g. {@code "/vg claims list"}; may be
     *                       {@code null}, stored as an empty string
     * @param description    one line for help text; may be {@code null}
     * @param handler        the code that runs; must not be {@code null}
     */
    record CommandDef(String name, String permissionNode, String usage,
                      String description, CommandHandler handler) {

        public CommandDef {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(permissionNode, "permissionNode");
            Objects.requireNonNull(handler, "handler");
            if (usage == null || usage.isBlank()) {
                usage = "/vg " + name;
            }
            if (description == null) {
                description = "";
            }
        }

        /**
         * Convenience overload for a command with no help text.
         *
         * @param name           literal
         * @param permissionNode permission node the gate evaluates
         * @param handler        the code that runs
         */
        public CommandDef(String name, String permissionNode, CommandHandler handler) {
            this(name, permissionNode, null, null, handler);
        }
    }

    /**
     * One invocation of an extension command.
     *
     * <p>Immutable. {@code arguments} are the raw trailing arguments the cell
     * captured — for a greedy string argument that is a single element
     * containing the rest of the line, exactly as the player typed it. The
     * registry does not split, quote or unescape: an extension that wants
     * shell-style words must do that itself, so no two extensions disagree
     * about what a space means.
     *
     * @param actor       the player running the command, or {@code null} for
     *                    console / command blocks / other system origins
     * @param extensionId the extension that owns the command; never
     *                    {@code null}
     * @param commandName the literal that matched, as registered; never
     *                    {@code null}
     * @param arguments   raw trailing arguments; never {@code null}, may be
     *                    empty
     * @param configBlock this extension's frozen settings block; never
     *                    {@code null}
     */
    record CommandInvocation(UUID actor, String extensionId, String commandName,
                             List<String> arguments, Map<String, String> configBlock) {

        public CommandInvocation {
            Objects.requireNonNull(extensionId, "extensionId");
            Objects.requireNonNull(commandName, "commandName");
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            configBlock = configBlock == null ? Map.of() : Map.copyOf(configBlock);
        }
    }

    /**
     * What a handler reports back to the dispatch gate.
     *
     * <p>{@code message} is handed to the caller as-is; the registry does not
     * localise it, because it cannot know the extension's message bundle. A
     * handler that wants a translated line must translate it before returning.
     *
     * @param success {@code true} when the command did what it says
     * @param message one line for the caller, or {@code null} for silence
     */
    record CommandResult(boolean success, String message) {

        /** @param message line to show the caller
         *  @return a success result carrying {@code message} */
        public static CommandResult ok(String message) {
            return new CommandResult(true, message);
        }

        /** @param message line to show the caller
         *  @return a failure result carrying {@code message} */
        public static CommandResult error(String message) {
            return new CommandResult(false, message);
        }
    }

    /**
     * The frozen view of a completed registration, handed to
     * {@link #onRegister}.
     *
     * <p>Pure data: it holds no reference to the registry, so an extension
     * cannot use it to mutate registration state after the fact.
     *
     * @param extensionId the registered id
     * @param configBlock the frozen settings block
     * @param actionTypes the declarations that were accepted, in order
     * @param commands    the declarations that were accepted, in order
     */
    record Binding(String extensionId,
                   Map<String, String> configBlock,
                   List<ActionTypeDef> actionTypes,
                   List<CommandDef> commands) {

        public Binding {
            Objects.requireNonNull(extensionId, "extensionId");
            configBlock = configBlock == null ? Map.of() : Map.copyOf(configBlock);
            actionTypes = actionTypes == null ? List.of() : List.copyOf(actionTypes);
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    /**
     * Code that runs for a declared command.
     *
     * <p>Called from the dispatch gate. May throw: the registry catches
     * {@link RuntimeException}, reports
     * {@link ExtensionRegistry.Dispatch.Status#FAILED} and logs once, so a
     * broken extension degrades to one failed command instead of taking down
     * the command path. Throwing is still worse than returning
     * {@link CommandResult#error(String)} — the operator gets a generic failure
     * instead of the extension's own explanation.
     */
    @FunctionalInterface
    interface CommandHandler {

        /**
         * @param invocation the resolved invocation; never {@code null}
         * @return the outcome to report; a {@code null} return is treated as a
         *         successful, silent result rather than an error
         */
        CommandResult handle(CommandInvocation invocation);
    }
}
