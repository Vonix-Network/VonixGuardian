package network.vonix.guardian.core.command;

import network.vonix.guardian.core.perms.PermissionNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code /vg} command tree as data, so ten loader cells stop keeping ten
 * copies of it.
 *
 * <p>Before this class the tree existed once per cell: ten
 * {@code GuardianCommands.java} files, roughly 15,400 lines, already
 * functionally diverged. Every command-surface fix in 2.3.0 had to be applied
 * ten times, and the ones that were missed are why {@code /vg batch} existed in
 * some cells and not others. The tree below is the single description; a cell
 * contributes only the platform half.</p>
 *
 * <h2>What is here</h2>
 * <p>Everything an operator can observe about the tree's <em>shape</em>:</p>
 * <ul>
 *   <li>which literals exist and in what order they are registered,</li>
 *   <li>which {@link PermissionNode} gates each one,</li>
 *   <li>which arguments each takes, their brigadier shape
 *       ({@link Type#WORD} / {@link Type#GREEDY_STRING}) and whether the filter
 *       argument offers tab completion,</li>
 *   <li>which {@link Handler} actually runs,</li>
 *   <li>the bare-literal usage topic, and</li>
 *   <li>the two redirect literals.</li>
 * </ul>
 *
 * <h2>What is not here</h2>
 * <p>No brigadier type and no Minecraft type appears in this file. {@code core}
 * stays free of both; {@link Type} and {@link Argument} describe the shape a
 * cell must build, not the builder it builds with. The handler <em>bodies</em>
 * also stay in the cell: they need {@code CommandSourceStack},
 * {@code ServerPlayer}, the level registry and the item registry. This class
 * names the handler; the cell implements it.</p>
 *
 * <h2>Adoption contract — what a cell must supply</h2>
 * <ol>
 *   <li><b>A literal walker.</b> Walk {@link #root()}, emit one
 *       {@code Commands.literal(node.literal())} per node, apply
 *       {@code .requires(...)} when {@link Node#gate()} is neither
 *       {@link Gate#none()} nor {@link Gate#previewDependent()}, attach
 *       {@code .executes(...)} when {@link Node#handler()} is non-null, then
 *       recurse — arguments first, then children, in list order. Arguments:
 *       {@link Type#WORD} is {@code StringArgumentType.word()},
 *       {@link Type#GREEDY_STRING} is {@code StringArgumentType.greedyString()},
 *       and {@link Suggestion#FILTER_TOKENS} means
 *       {@code .suggests(<the cell's filter completer>)}.</li>
 *   <li><b>Every handler in {@link #handlers()}.</b> A cell that implements a
 *       subset must say so at startup rather than discover it in production:
 *       call {@link #missingHandlers(Set)} and refuse to register while it is
 *       non-empty. The checked-in cell does this.</li>
 *   <li><b>Gate resolution.</b> {@link Gate#node()} is the node to evaluate;
 *       {@link Gate#previewDependent()} means the node depends on what is
 *       pending — {@code /vg apply} and {@code /vg cancel} need
 *       {@code ROLLBACK} or {@code RESTORE} according to the pending preview's
 *       mode, so the cell resolves that one per call and must fall back to
 *       "either of the two" when nothing is pending.</li>
 *   <li><b>The two redirects.</b> {@link #redirects()} lists literals that
 *       must {@code .redirect(...)} to the built root node.</li>
 *   <li><b>Nothing else.</b> The cell must not carry its own copy of the
 *       literal table, its own gate assignments, or its own argument shapes;
 *       that is the duplication this class removes.</li>
 * </ol>
 *
 * <h2>Fail-closed construction</h2>
 * <p>{@link #validate()} runs from the static initializer. A tree that omits a
 * subcommand {@link CommandSpec} publishes, omits a documented alias, leaves a
 * literal ungated, or declares a node that would do nothing, throws
 * {@link IllegalStateException} naming every problem at once — at class load,
 * not at the first command a player types.</p>
 *
 * @see CommandSpec the frozen subcommand/permission contract this tree must cover
 * @see CommandHelp the text rendered from {@link CommandSpec}
 */
public final class CommandTree {

    // ================================================================ handler ids

    /**
     * A platform action the tree can name. The cell maps each id onto a method;
     * this enum is the reference, not the implementation.
     *
     * <p>Ids are deliberately fine-grained where the cells run different methods:
     * {@code /vg give} with and without an amount, {@code /vg batch show} versus
     * {@code /vg batch close}, and {@code /vg migrate-db} with and without the
     * {@code CONFIRM} token are separate ids so a cell never has to infer the
     * verb from which arguments happen to be present. Inferring it was a real
     * defect source — a missing argument is indistinguishable from an empty one
     * once it reaches {@code StringArgumentType.getString}.</p>
     */
    public enum Handler {
        /** Bare-literal hint: render {@code CommandHelp.usage(topic)}. */
        USAGE,
        /** {@code /vg help}. */
        HELP,
        INSPECT_TOGGLE,
        LOOKUP,
        ROLLBACK,
        RESTORE,
        PURGE,
        NEAR,
        UNDO,
        BATCH_LIST,
        BATCH_SHOW,
        BATCH_CLOSE,
        PREVIEW_APPLY,
        PREVIEW_CANCEL,
        CONSUMER_STATUS,
        CONSUMER_PAUSE,
        CONSUMER_RESUME,
        CONSUMER_TOGGLE,
        STATUS,
        RELOAD,
        CONFIG_GET,
        CONFIG_SET,
        ENTITYLOG_LIST,
        ENTITYLOG_ADD,
        ENTITYLOG_REMOVE,
        TELEPORT,
        GIVE,
        GIVE_WITH_AMOUNT,
        MIGRATE_DB,
        MIGRATE_DB_CONFIRM
    }

    // ================================================================ value types

    /**
     * The brigadier shape of an argument, expressed without brigadier.
     *
     * <p>Both shapes carry a {@code String} on every supported version, so a
     * cell can build either as an {@code ArgumentType<String>}:
     * {@code StringArgumentType.word()} for {@link #WORD},
     * {@code StringArgumentType.greedyString()} for {@link #GREEDY_STRING}.</p>
     */
    public enum Type {
        /** A single whitespace-free token. */
        WORD,
        /** Everything to the end of the command line. */
        GREEDY_STRING
    }

    /** Tab-completion a cell may attach to an argument. */
    public enum Suggestion {
        /** No completion. */
        NONE,
        /** The {@code u: t: r: a: i: e: #flag} filter completer. */
        FILTER_TOKENS
    }

    /**
     * One argument node.
     *
     * <p>{@link #name()} is the brigadier argument name and therefore also the
     * key the cell's handler reads with {@code StringArgumentType.getString}.
     * It is part of the contract: renaming one here without renaming the read
     * in the handler makes the command throw at execution time, which is why
     * {@link #validate()} rejects blank or duplicate names.</p>
     *
     * @param name       brigadier argument name, e.g. {@code filter}
     * @param type       brigadier shape to build
     * @param suggestion completion to attach, never {@code null}
     * @param handler    handler that runs when this argument is complete,
     *                   or {@code null} when the argument only continues the path
     * @param children   argument nodes nested below this one
     */
    public record Argument(
            String name,
            Type type,
            Suggestion suggestion,
            Handler handler,
            List<Argument> children
    ) {
        public Argument {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (name.isBlank()) {
                throw new IllegalArgumentException("argument name must be non-blank");
            }
            suggestion = suggestion == null ? Suggestion.NONE : suggestion;
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** An argument that executes {@code handler} in place. */
        public static Argument of(String name, Type type, Handler handler) {
            return new Argument(name, type, Suggestion.NONE, handler, List.of());
        }

        /** A filter-token argument that executes {@code handler}. */
        public static Argument filter(String name, Handler handler) {
            return new Argument(name, Type.GREEDY_STRING, Suggestion.FILTER_TOKENS, handler, List.of());
        }

        /** An argument that only continues the path; its children execute. */
        public static Argument path(String name, Type type, Argument... children) {
            return new Argument(name, type, Suggestion.NONE, null, List.of(children));
        }
    }

    /**
     * How a node is gated. Exactly three cases, and they are distinct on
     * purpose:
     * <ul>
     *   <li>{@link #of(PermissionNode)} — a fixed node.</li>
     *   <li>{@link #preview()} — the node depends on the pending preview's mode.
     *       Only {@code /vg apply} and {@code /vg cancel} use this; there is no
     *       {@code apply} node to grant, so the cell must evaluate
     *       {@code ROLLBACK} or {@code RESTORE} per call, falling back to
     *       "either" when nothing is pending.</li>
     *   <li>{@link #none()} — no own gate. Only {@code /vg help} uses this: it
     *       is reached through the root literal, which is already
     *       {@link PermissionNode#BASE}-gated, and it is the one subcommand
     *       whose op level is 0.</li>
     * </ul>
     *
     * @param node             fixed node to evaluate, or {@code null}
     * @param previewDependent {@code true} when the node follows the pending preview
     */
    public record Gate(PermissionNode node, boolean previewDependent) {

        /** Strict variant: exactly one of the two forms. */
        public Gate {
            if (node != null && previewDependent) {
                throw new IllegalArgumentException(
                        "a gate is either a fixed node or preview-dependent, not both");
            }
        }

        /** A gate on a fixed node. */
        public static Gate of(PermissionNode node) {
            return new Gate(Objects.requireNonNull(node, "node"), false);
        }

        /** A gate that follows the pending preview (apply / cancel only). */
        public static Gate preview() {
            return new Gate(null, true);
        }

        /** No own gate. */
        public static Gate none() {
            return new Gate(null, false);
        }

        /** @return {@code true} when the node carries no own gate */
        public boolean isNone() {
            return node == null && !previewDependent;
        }
    }

    /**
     * One literal node.
     *
     * <p>Registration order is the declaration order of {@link #children()} and
     * {@link #arguments()}. Brigadier resolves exact literals, so order is not
     * semantically load-bearing, but {@code /vg help} and tab completion read in
     * it, and it is what makes the tree diffable against the old cell.</p>
     *
     * @param literal    the literal to register, e.g. {@code rollback}
     * @param gate       how the node is gated, never {@code null}
     * @param arguments  argument nodes directly below this literal, in
     *                   registration order
     * @param handler    handler that runs for the bare literal, or {@code null}
     *                   when the literal only groups children or takes arguments
     * @param usageTopic usage topic when {@link #handler()} is {@link Handler#USAGE}
     * @param children   literal children, in registration order
     */
    public record Node(
            String literal,
            Gate gate,
            List<Argument> arguments,
            Handler handler,
            String usageTopic,
            List<Node> children
    ) {
        public Node {
            Objects.requireNonNull(literal, "literal");
            Objects.requireNonNull(gate, "gate");
            if (literal.isBlank()) {
                throw new IllegalArgumentException("literal must be non-blank");
            }
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** A leaf that runs {@code handler}. */
        public static Node exec(String literal, Gate gate, Handler handler) {
            return new Node(literal, gate, List.of(), handler, null, List.of());
        }

        /** A leaf that renders the usage hint for {@code topic}. */
        public static Node hint(String literal, Gate gate, String topic) {
            return new Node(literal, gate, List.of(), Handler.USAGE, topic, List.of());
        }

        /** Renders a usage hint for the bare literal, and takes {@code arguments}. */
        public static Node hintThen(String literal, Gate gate, String topic, Argument... arguments) {
            return new Node(literal, gate, List.of(arguments), Handler.USAGE, topic, List.of());
        }

        /** Takes {@code arguments} with no execute on the bare literal. */
        public static Node takes(String literal, Gate gate, Argument... arguments) {
            return new Node(literal, gate, List.of(arguments), null, null, List.of());
        }

        /** Groups {@code children} with no execute of its own. */
        public static Node group(String literal, Gate gate, Node... children) {
            return new Node(literal, gate, List.of(), null, null, List.of(children));
        }

        /** Runs {@code handler} on the bare literal and groups {@code children}. */
        public static Node execWithChildren(String literal, Gate gate, Handler handler,
                                            Node... children) {
            return new Node(literal, gate, List.of(), handler, null, List.of(children));
        }
    }

    // ================================================================ the tree

    /**
     * {@code /vg} — the root, gated on {@link PermissionNode#BASE}.
     *
     * <p>Every literal below is the one the checked-in 1.18.2 Forge cell
     * registered at the start of this lane, including the undocumented
     * carry-overs ({@code inspector}, {@code page}, {@code ro}, {@code re},
     * {@code stats}, {@code version}). They are kept because removing a literal
     * an operator's macro already types is an operator-visible regression, and
     * this lane is a refactor, not a command-surface change. A future lane that
     * wants them gone deletes one line here instead of editing ten cells.</p>
     */
    public static final Node ROOT = new Node(
            CommandSpec.ROOT,
            Gate.of(PermissionNode.BASE),
            List.of(),
            Handler.USAGE,
            "root",
            List.of(

                    // ---- inspect: long, short, legacy -----------------------
                    Node.exec(CommandSpec.INSPECT, Gate.of(PermissionNode.INSPECT),
                            Handler.INSPECT_TOGGLE),
                    Node.exec("i", Gate.of(PermissionNode.INSPECT), Handler.INSPECT_TOGGLE),
                    Node.exec("inspector", Gate.of(PermissionNode.INSPECT),
                            Handler.INSPECT_TOGGLE),

                    // ---- lookup: long, short, legacy "page" ----------------
                    Node.hintThen(CommandSpec.LOOKUP, Gate.of(PermissionNode.LOOKUP), "lookup",
                            Argument.filter("filter", Handler.LOOKUP)),
                    Node.hintThen("l", Gate.of(PermissionNode.LOOKUP), "lookup",
                            Argument.filter("filter", Handler.LOOKUP)),
                    Node.hintThen("page", Gate.of(PermissionNode.LOOKUP), "lookup",
                            Argument.filter("filter", Handler.LOOKUP)),

                    // ---- rollback: long, short, legacy "ro" ---------------
                    Node.hintThen(CommandSpec.ROLLBACK, Gate.of(PermissionNode.ROLLBACK), "rollback",
                            Argument.filter("filter", Handler.ROLLBACK)),
                    Node.hintThen("rb", Gate.of(PermissionNode.ROLLBACK), "rollback",
                            Argument.filter("filter", Handler.ROLLBACK)),
                    Node.hintThen("ro", Gate.of(PermissionNode.ROLLBACK), "rollback",
                            Argument.filter("filter", Handler.ROLLBACK)),

                    // ---- preview lifecycle ---------------------------------
                    // No apply/cancel node exists to grant: the gate follows
                    // whatever preview is pending.
                    Node.exec(CommandSpec.APPLY, Gate.preview(), Handler.PREVIEW_APPLY),
                    Node.exec(CommandSpec.CANCEL, Gate.preview(), Handler.PREVIEW_CANCEL),

                    // ---- restore: long, short, legacy "re" ----------------
                    Node.hintThen(CommandSpec.RESTORE, Gate.of(PermissionNode.RESTORE), "restore",
                            Argument.filter("filter", Handler.RESTORE)),
                    Node.hintThen("rs", Gate.of(PermissionNode.RESTORE), "restore",
                            Argument.filter("filter", Handler.RESTORE)),
                    Node.hintThen("re", Gate.of(PermissionNode.RESTORE), "restore",
                            Argument.filter("filter", Handler.RESTORE)),

                    // ---- purge ---------------------------------------------
                    Node.hintThen(CommandSpec.PURGE, Gate.of(PermissionNode.PURGE), "purge",
                            Argument.filter("filter", Handler.PURGE)),

                    // ---- undo ----------------------------------------------
                    Node.exec(CommandSpec.UNDO, Gate.of(PermissionNode.UNDO), Handler.UNDO),

                    // ---- batch ---------------------------------------------
                    // Bare /vg batch lists, which is why the literal carries a
                    // handler and still has children. show/close take an id and
                    // print the hint when the id is missing — separate ids so a
                    // cell never infers the verb from an absent argument.
                    Node.execWithChildren(CommandSpec.BATCH, Gate.of(PermissionNode.ROLLBACK),
                            Handler.BATCH_LIST,
                            Node.exec("list", Gate.of(PermissionNode.ROLLBACK), Handler.BATCH_LIST),
                            Node.hintThen("show", Gate.of(PermissionNode.ROLLBACK), "batch",
                                    Argument.of("id", Type.WORD, Handler.BATCH_SHOW)),
                            Node.hintThen("close", Gate.of(PermissionNode.ROLLBACK), "batch",
                                    Argument.of("id", Type.WORD, Handler.BATCH_CLOSE))),

                    // ---- near ----------------------------------------------
                    Node.exec(CommandSpec.NEAR, Gate.of(PermissionNode.NEAR), Handler.NEAR),

                    // ---- consumer ------------------------------------------
                    Node.execWithChildren(CommandSpec.CONSUMER,
                            Gate.of(PermissionNode.CONSUMER), Handler.CONSUMER_STATUS,
                            Node.exec("pause", Gate.of(PermissionNode.CONSUMER),
                                    Handler.CONSUMER_PAUSE),
                            Node.exec("resume", Gate.of(PermissionNode.CONSUMER),
                                    Handler.CONSUMER_RESUME),
                            Node.exec("toggle", Gate.of(PermissionNode.CONSUMER),
                                    Handler.CONSUMER_TOGGLE)),

                    // ---- status: long, "stats", "version" ------------------
                    Node.exec(CommandSpec.STATUS, Gate.of(PermissionNode.STATUS), Handler.STATUS),
                    Node.exec("stats", Gate.of(PermissionNode.STATUS), Handler.STATUS),
                    Node.exec("version", Gate.of(PermissionNode.STATUS), Handler.STATUS),

                    // ---- reload --------------------------------------------
                    Node.exec(CommandSpec.RELOAD, Gate.of(PermissionNode.RELOAD), Handler.RELOAD),

                    // ---- config get|set ------------------------------------
                    Node.group(CommandSpec.CONFIG, Gate.of(PermissionNode.CONFIG),
                            Node.takes("get", Gate.of(PermissionNode.CONFIG),
                                    Argument.of("key", Type.WORD, Handler.CONFIG_GET)),
                            Node.takes("set", Gate.of(PermissionNode.CONFIG),
                                    Argument.path("key", Type.WORD,
                                            Argument.of("value", Type.GREEDY_STRING,
                                                    Handler.CONFIG_SET)))),

                    // ---- entitylog list|add|remove -------------------------
                    // The literal executes list, matching the old cell's bare
                    // /vg entitylog, and still has list/add/remove children.
                    Node.execWithChildren(CommandSpec.ENTITYLOG, Gate.of(PermissionNode.CONFIG),
                            Handler.ENTITYLOG_LIST,
                            Node.exec("list", Gate.of(PermissionNode.CONFIG),
                                    Handler.ENTITYLOG_LIST),
                            Node.takes("add", Gate.of(PermissionNode.CONFIG),
                                    Argument.of("entity", Type.GREEDY_STRING,
                                            Handler.ENTITYLOG_ADD)),
                            Node.takes("remove", Gate.of(PermissionNode.CONFIG),
                                    Argument.of("entity", Type.GREEDY_STRING,
                                            Handler.ENTITYLOG_REMOVE))),

                    // ---- teleport <world> <x> [y] <z>, and "tp" ------------
                    Node.takes(CommandSpec.TELEPORT, Gate.of(PermissionNode.TELEPORT),
                            Argument.path("world", Type.WORD,
                                    Argument.of("coords", Type.GREEDY_STRING, Handler.TELEPORT))),
                    Node.takes("tp", Gate.of(PermissionNode.TELEPORT),
                            Argument.path("world", Type.WORD,
                                    Argument.of("coords", Type.GREEDY_STRING, Handler.TELEPORT))),

                    // ---- give <itemId> [amount] ----------------------------
                    Node.takes(CommandSpec.GIVE, Gate.of(PermissionNode.GIVE),
                            new Argument("itemId", Type.WORD, Suggestion.NONE, Handler.GIVE,
                                    List.of(Argument.of("amount", Type.WORD,
                                            Handler.GIVE_WITH_AMOUNT)))),

                    // ---- migrate-db <target> [CONFIRM] ---------------------
                    Node.takes(CommandSpec.MIGRATE_DB, Gate.of(PermissionNode.MIGRATE_DB),
                            new Argument("target", Type.WORD, Suggestion.NONE, Handler.MIGRATE_DB,
                                    List.of(Argument.of("confirm", Type.WORD,
                                            Handler.MIGRATE_DB_CONFIRM)))),

                    // ---- help ----------------------------------------------
                    // No own gate: the root's BASE already applies, and help is
                    // the one subcommand whose op level is 0.
                    Node.exec(CommandSpec.HELP, Gate.none(), Handler.HELP)
            )
    );

    private CommandTree() {
    }

    // ================================================================ accessors

    /** @return the root node, literal {@code vg} */
    public static Node root() {
        return ROOT;
    }

    /** @return the root's children in registration order */
    public static List<Node> topLevel() {
        return ROOT.children();
    }

    /**
     * Literals that must be registered as redirects onto the built root.
     *
     * <p>{@code co} is CoreProtect muscle memory; {@link CommandSpec#ALIAS} is
     * the documented {@code /guardian} alias. Both redirect rather than rebuild,
     * so the dispatcher holds exactly one tree.</p>
     *
     * @return immutable list of redirect literals
     */
    public static List<String> redirects() {
        return List.of("co", CommandSpec.ALIAS);
    }

    /**
     * Every handler a cell must implement to host this tree.
     *
     * @return unmodifiable set in deterministic tree order (breadth-first from
     *         the root, arguments before children)
     */
    public static Set<Handler> handlers() {
        Set<Handler> out = new LinkedHashSet<>();
        Deque<Node> literals = new ArrayDeque<>();
        literals.add(ROOT);
        while (!literals.isEmpty()) {
            Node node = literals.removeFirst();
            if (node.handler() != null) {
                out.add(node.handler());
            }
            literals.addAll(node.children());
            Deque<Argument> arguments = new ArrayDeque<>(node.arguments());
            while (!arguments.isEmpty()) {
                Argument argument = arguments.removeFirst();
                if (argument.handler() != null) {
                    out.add(argument.handler());
                }
                arguments.addAll(argument.children());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Handlers in {@link #handlers()} that {@code supplied} does not cover.
     *
     * <p>An adopting cell calls this while registering and refuses to publish a
     * tree it cannot dispatch: a literal that exists but has no handler is a
     * command that fails when an operator types it, and no offline test can
     * catch that, because the tree is built where a live server is required.</p>
     *
     * @param supplied handler ids the cell implements; {@code null} is treated
     *                 as empty
     * @return handlers the cell owes, in tree order; empty when it covers all
     */
    public static List<Handler> missingHandlers(Set<Handler> supplied) {
        List<Handler> missing = new ArrayList<>();
        for (Handler handler : handlers()) {
            if (supplied == null || !supplied.contains(handler)) {
                missing.add(handler);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Finds a top-level node by literal.
     *
     * @param literal the literal to find, e.g. {@code rollback}
     * @return the node, or {@code null} when no top-level literal matches
     */
    public static Node find(String literal) {
        for (Node node : ROOT.children()) {
            if (node.literal().equals(literal)) {
                return node;
            }
        }
        return null;
    }

    // ================================================================ validation

    /**
     * Structural invariants. Runs from the static initializer, so a broken tree
     * fails at class load rather than at the first command a player types.
     *
     * @throws IllegalStateException listing every problem found
     */
    public static void validate() {
        List<String> problems = new ArrayList<>();

        if (ROOT.handler() != Handler.USAGE) {
            problems.add("root must run the usage handler");
        }
        if (ROOT.children().isEmpty()) {
            problems.add("root has no children");
        }
        if (!ROOT.gate().equals(Gate.of(PermissionNode.BASE))) {
            problems.add("root must gate on PermissionNode.BASE");
        }

        Set<String> topLevelLiterals = new LinkedHashSet<>();
        for (Node node : ROOT.children()) {
            if (!topLevelLiterals.add(node.literal())) {
                problems.add("duplicate top-level literal: " + node.literal());
            }
        }

        // Every subcommand the frozen spec publishes must be reachable, and so
        // must every alias it advertises. An alias advertised in help that no
        // cell registers is a lie; n and ? were removed for exactly that reason.
        for (SubcommandSpec spec : CommandSpec.all()) {
            if (!topLevelLiterals.contains(spec.name())) {
                problems.add("CommandSpec publishes /vg " + spec.name()
                        + " but the tree registers no such literal");
            }
            if (spec.alias() != null && !topLevelLiterals.contains(spec.alias())) {
                problems.add("CommandSpec advertises alias /vg " + spec.alias()
                        + " for " + spec.name() + " but the tree registers no such literal");
            }
        }

        // /vg help is the one subcommand with no own gate: it is the op-0 entry
        // point and the root already gates on BASE. Everything else that reads
        // or changes anything must be gated, or it is reachable by anyone.
        for (Node node : ROOT.children()) {
            if (CommandSpec.HELP.equals(node.literal())) {
                if (!node.gate().isNone()) {
                    problems.add("/vg help must carry no own gate (root BASE applies)");
                }
                continue;
            }
            if (node.gate().isNone()) {
                problems.add("/vg " + node.literal() + " has no permission gate");
            }
        }

        List<String> redirects = redirects();
        Set<String> redirectSet = new LinkedHashSet<>(redirects);
        for (String redirect : redirectSet) {
            if (redirect.isBlank()) {
                problems.add("blank redirect literal");
            }
            if (topLevelLiterals.contains(redirect)) {
                problems.add("redirect literal " + redirect + " is also a built literal");
            }
        }
        if (!redirectSet.contains(CommandSpec.ALIAS)) {
            problems.add("the documented alias " + CommandSpec.ALIAS + " is not redirected");
        }

        validateNode(ROOT, "vg", problems, new LinkedHashSet<>());

        if (!problems.isEmpty()) {
            throw new IllegalStateException("CommandTree is inconsistent; " + problems.size()
                    + " problem(s):\n  - " + String.join("\n  - ", problems));
        }
    }

    private static void validateNode(Node node, String path, List<String> problems,
                                     Set<String> seenPaths) {
        if (!seenPaths.add(path)) {
            problems.add("node reached twice: " + path);
        }
        if (node.handler() == Handler.USAGE) {
            if (node.usageTopic() == null || node.usageTopic().isBlank()) {
                problems.add(path + " runs the usage handler with no topic");
            }
        } else if (node.usageTopic() != null) {
            problems.add(path + " names a usage topic but does not run the usage handler");
        }
        if (node.handler() == null && node.children().isEmpty() && node.arguments().isEmpty()) {
            problems.add(path + " has no handler, no arguments and no children"
                    + " - it would register as a node that does nothing");
        }

        Set<String> childLiterals = new LinkedHashSet<>();
        for (Node child : node.children()) {
            if (!childLiterals.add(child.literal())) {
                problems.add(path + " has a duplicate child literal " + child.literal());
            }
            validateNode(child, path + " " + child.literal(), problems, seenPaths);
        }

        validateArguments(node.arguments(), path, problems);
    }

    private static void validateArguments(List<Argument> arguments, String path,
                                          List<String> problems) {
        Set<String> names = new LinkedHashSet<>();
        for (Argument argument : arguments) {
            if (!names.add(argument.name())) {
                problems.add(path + " has a duplicate argument named " + argument.name());
            }
            String argumentPath = path + " <" + argument.name() + ">";
            if (argument.handler() == null && argument.children().isEmpty()) {
                problems.add(argumentPath + " has no handler and no children"
                        + " - typing it would fall through to an unknown-command error");
            }
            validateArguments(argument.children(), argumentPath, problems);
        }
    }

    // Static initializer last, so every constant above is assigned first.
    static {
        validate();
    }
}
