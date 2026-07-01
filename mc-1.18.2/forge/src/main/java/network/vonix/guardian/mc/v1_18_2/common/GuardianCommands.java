/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.perms.LookupPermissionFilter;
import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParseException;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.rollback.RollbackResult;
import network.vonix.guardian.core.storage.dbmigrate.MigrateDbCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brigadier command tree for {@code /vg ...} (primary), with aliases
 * {@code /vg} and {@code /guardian}.
 *
 * <p>Implements the CoreProtect 1:1 command surface (rooted at {@code /vg}
 * for Vonix branding; {@code /vg} provided as a CoreProtect-muscle-memory
 * alias for operators migrating from CoreProtect) — see
 * <a href="https://docs.coreprotect.net/commands/">CoreProtect command docs</a>
 * — including the short subcommand aliases ({@code i}, {@code l}, {@code rb},
 * {@code rs}), {@code consumer pause|resume|toggle}, and {@code near}.
 *
 * <p>1.18.2 differences vs the 1.20.1 reference: {@code CommandSourceStack
 * .sendSuccess(Component, boolean)} takes the {@link Component} directly
 * (no {@code Supplier}); {@code ServerPlayer.level} is a field, not a method.
 */
public final class GuardianCommands {

    private static final Logger LOG = LoggerFactory.getLogger(GuardianCommands.class);
    private static final QueryParser PARSER = new QueryParser();
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_ROLLBACK_RADIUS = 10;

    /** Matches a pure page token, optionally with {@code :perPage}. */
    private static final Pattern PAGE_TOKEN = Pattern.compile("^(\\d+)(?::(\\d+))?$");

    /** Shared worker pool for command-driven DB queries. Daemonized. */
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VonixGuardian-Cmd");
        t.setDaemon(true);
        return t;
    });

    private GuardianCommands() {
        // utility
    }

    /**
     * Register the {@code /vg} command tree (and aliases {@code /vg} /
     * {@code /guardian}).
     *
     * @param dispatcher brigadier dispatcher
     * @param g          the live Guardian facade
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Guardian g) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("vg")
                .requires(s -> hasPerm(s, "vonixguardian.command.use", g))
                // inspect (long + short)
                .then(Commands.literal("inspect")
                        .requires(s -> hasPerm(s, "vonixguardian.command.inspect", g))
                        .executes(ctx -> Inspect.toggle(ctx, g)))
                .then(Commands.literal("i")
                        .requires(s -> hasPerm(s, "vonixguardian.command.inspect", g))
                        .executes(ctx -> Inspect.toggle(ctx, g)))
                // lookup (long + short)
                .then(Commands.literal("lookup")
                        .requires(s -> hasPerm(s, "vonixguardian.command.lookup", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Lookup.run(ctx, g))))
                .then(Commands.literal("l")
                        .requires(s -> hasPerm(s, "vonixguardian.command.lookup", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Lookup.run(ctx, g))))
                // rollback (long + short)
                .then(Commands.literal("rollback")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Rollback.run(ctx, g, false))))
                .then(Commands.literal("rb")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Rollback.run(ctx, g, false))))
                // restore (long + short)
                .then(Commands.literal("restore")
                        .requires(s -> hasPerm(s, "vonixguardian.command.restore", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Restore.run(ctx, g))))
                .then(Commands.literal("rs")
                        .requires(s -> hasPerm(s, "vonixguardian.command.restore", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Restore.run(ctx, g))))
                // purge
                .then(Commands.literal("purge")
                        .requires(s -> hasPerm(s, "vonixguardian.command.purge", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Purge.run(ctx, g))))
                // undo
                .then(Commands.literal("undo")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .executes(ctx -> Undo.run(ctx, g)))
                // near (CP parity: radius=5 t:1h, current player only)
                .then(Commands.literal("near")
                        .requires(s -> hasPerm(s, "vonixguardian.command.near", g))
                        .executes(ctx -> Near.run(ctx, g)))
                // consumer pause/resume/toggle
                .then(Commands.literal("consumer")
                        .requires(s -> hasPerm(s, "vonixguardian.command.consumer", g))
                        .executes(ctx -> Consumer.status(ctx, g))
                        .then(Commands.literal("pause").executes(ctx -> Consumer.pause(ctx, g)))
                        .then(Commands.literal("resume").executes(ctx -> Consumer.resume(ctx, g)))
                        .then(Commands.literal("toggle").executes(ctx -> Consumer.toggle(ctx, g))))
                // status
                .then(Commands.literal("status")
                        .requires(s -> hasPerm(s, "vonixguardian.command.status", g))
                        .executes(ctx -> Status.run(ctx, g)))
                // reload
                .then(Commands.literal("reload")
                        .requires(s -> hasPerm(s, "vonixguardian.command.reload", g))
                        .executes(ctx -> Reload.run(ctx, g)))
                // migrate-db <target-type> [CONFIRM] (console-only)
                .then(Commands.literal("migrate-db")
                        .requires(s -> hasPerm(s, "vonixguardian.command.migrate-db", g))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> MigrateDb.run(ctx, g, false))
                                .then(Commands.argument("confirm", StringArgumentType.word())
                                        .executes(ctx -> MigrateDb.run(ctx, g, true)))))
                // help
                .then(Commands.literal("help").executes(ctx -> Help.run(ctx, g)));

        dispatcher.register(root);
        // Aliases: /vg (CoreProtect muscle memory) and /guardian both redirect to the /vg tree.
        dispatcher.register(Commands.literal("co").redirect(dispatcher.getRoot().getChild("vg")));
        dispatcher.register(Commands.literal("guardian").redirect(dispatcher.getRoot().getChild("vg")));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasPerm(CommandSourceStack s, String node, Guardian g) {
        if (!(s.getEntity() instanceof ServerPlayer p)) {
            return s.hasPermission(g.config().permissions().defaultOpLevel());
        }
        return g.perms().has(p.getUUID(), node);
    }

    private static void send(CommandSourceStack src, Component msg) {
        try {
            src.sendSuccess(msg, false);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "Failed to send chat", t);
        }
    }

    private static QueryParser.QueryParseContext ctxOf(CommandSourceStack src) {
        Vec3 v = src.getPosition();
        if (v == null) return null;
        return new QueryParser.QueryParseContext((int) v.x, (int) v.y, (int) v.z);
    }

    private static String playerWorldOf(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? WorldKey.of(p.level) : null;
    }

    private static UUID actorUuid(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
    }

    /**
     * Returns a copy of {@code qf} with a default radius of {@code 10} centered
     * on the caller, if the caller did not supply a radius selector. Mirrors
     * CoreProtect's {@code /vg rollback} / {@code /vg restore} defaults. No-op
     * when the source has no position (console).
     */
    private static QueryFilter withDefaultRollbackRadius(QueryFilter qf, CommandSourceStack src) {
        if (qf.radius() != null) {
            return qf;
        }
        Vec3 v = src.getPosition();
        if (v == null) {
            return qf;
        }
        return new QueryFilter(
                qf.users(), qf.sinceMillis(), qf.untilMillis(),
                DEFAULT_ROLLBACK_RADIUS,
                qf.worldSel(),
                qf.centerX() != null ? qf.centerX() : (int) v.x,
                qf.centerY() != null ? qf.centerY() : (int) v.y,
                qf.centerZ() != null ? qf.centerZ() : (int) v.z,
                qf.actions(), qf.include(), qf.exclude(),
                qf.rolledBack(), qf.countOnly(), qf.preview(), qf.verbose(), qf.silent(), qf.optimize()
        );
    }

    // ====================================================================== Inspect

    /** {@code /vg inspect} (alias {@code /vg i}) — toggle inspection mode. */
    public static final class Inspect {
        private Inspect() {}

        public static int toggle(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            if (!(src.getEntity() instanceof ServerPlayer p)) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] /vg inspect must be run by a player."));
                return 0;
            }
            boolean now = Inspector.toggle(p.getUUID());
            if (now) {
                send(src, ChatRenderer.success(g.theme(),
                        "[VonixGuardian] Inspect mode ENABLED. Left-click a block to look it up."));
            } else {
                send(src, ChatRenderer.muted(g.theme(), "[VonixGuardian] Inspect mode disabled."));
            }
            return 1;
        }
    }

    // ====================================================================== Lookup

    /** {@code /vg lookup <filter>} — supports CoreProtect-style pagination. */
    public static final class Lookup {
        private Lookup() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            String raw = StringArgumentType.getString(ctx, "filter");

            // CP pagination: if the FIRST whitespace token is `<n>` or `<n>:<m>`
            // treat it as page (and optional per-page) and strip it from the filter.
            int page = 1;
            int perPage = DEFAULT_PAGE_SIZE;
            String filter = raw;
            String trimmed = raw == null ? "" : raw.trim();
            if (!trimmed.isEmpty()) {
                int sp = trimmed.indexOf(' ');
                String first = sp < 0 ? trimmed : trimmed.substring(0, sp);
                Matcher m = PAGE_TOKEN.matcher(first);
                if (m.matches()) {
                    try {
                        page = Math.max(1, Integer.parseInt(m.group(1)));
                        if (m.group(2) != null) {
                            perPage = Math.max(1, Math.min(50, Integer.parseInt(m.group(2))));
                        }
                    } catch (NumberFormatException ignore) {
                        // fall through with defaults
                    }
                    filter = sp < 0 ? "" : trimmed.substring(sp + 1);
                }
            }
            return runWithFilter(src, g, filter, page, perPage);
        }

        /** Run a lookup at a specific block position (used by the inspector). */
        public static void atPos(Guardian g, int x, int y, int z, String worldId, CommandSourceStack src) {
            String f = "r:1 t:30d";
            // TODO: extend QueryParser with a `p:x,y,z` token for true position lookups.
            runWithFilter(src, g, f + " r:#world_" + worldId, 1, DEFAULT_PAGE_SIZE);
        }

        /** Back-compat overload — kept for existing callers (inspector). */
        public static int runWithFilter(CommandSourceStack src, Guardian g, String raw) {
            return runWithFilter(src, g, raw, 1, DEFAULT_PAGE_SIZE);
        }

        public static int runWithFilter(CommandSourceStack src, Guardian g, String raw, int page, int perPage) {
            QueryFilter qf;
            try {
                qf = PARSER.parse(raw == null ? "" : raw, ctxOf(src)).withDefaultWorld(playerWorldOf(src));
            } catch (QueryParseException e) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] " + e.getMessage()));
                return 0;
            }
            MinecraftServer server = src.getServer();
            final QueryFilter filter = qf;
            final int pageF = page;
            final int perPageF = perPage;
            WORKER.submit(() -> {
                try {
                    long total = g.dao().count(filter);
                    int offset = (pageF - 1) * perPageF;
                    List<Action> rows = g.dao().query(filter, offset, perPageF);
                    // W3-B7: filter rows by CoreProtect-style child perms (e.g. lookup.chat)
                    UUID viewer = actorUuid(src);
                    rows = LookupPermissionFilter.filter(g.perms(), viewer, PermissionNode.LOOKUP, rows);
                    long now = System.currentTimeMillis();
                    List<Component> pageOut = LookupFormatter.page(g.theme(), rows, total, pageF, perPageF, now);
                    server.execute(() -> {
                        for (Component c : pageOut) {
                            send(src, c);
                        }
                    });
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Lookup failed", t);
                    server.execute(() -> send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Lookup error: " + t.getMessage())));
                }
            });
            return 1;
        }
    }

    // ====================================================================== Near

    /** {@code /vg near} — quick lookup r:5 t:1h centered on the caller. */
    public static final class Near {
        private Near() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            if (!(src.getEntity() instanceof ServerPlayer)) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] /vg near must be run by a player."));
                return 0;
            }
            return Lookup.runWithFilter(src, g, "r:5 t:1h", 1, DEFAULT_PAGE_SIZE);
        }
    }

    // ====================================================================== Rollback

    /** {@code /vg rollback <filter>} — default radius is {@value #DEFAULT_ROLLBACK_RADIUS} blocks when omitted. */
    public static final class Rollback {
        private Rollback() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g, boolean previewForced) {
            CommandSourceStack src = ctx.getSource();
            String raw = StringArgumentType.getString(ctx, "filter");
            QueryFilter qf;
            try {
                qf = PARSER.parse(raw, ctxOf(src)).withDefaultWorld(playerWorldOf(src));
            } catch (QueryParseException e) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] " + e.getMessage()));
                return 0;
            }
            qf = withDefaultRollbackRadius(qf, src);
            MinecraftServer server = src.getServer();
            UUID actor = actorUuid(src);
            final QueryFilter filter = qf;
            final boolean preview = previewForced || filter.preview();
            WORKER.submit(() -> {
                try {
                    RollbackResult result = g.rollbackEngine().rollback(filter, preview, actor);
                    g.undoStack().push(actor != null ? actor
                            : network.vonix.guardian.core.rollback.UndoStack.CONSOLE_KEY, result);
                    server.execute(() -> send(src, ChatRenderer.success(g.theme(),
                            "[VonixGuardian] Rollback " + (preview ? "(preview) " : "")
                                    + "affected=" + result.affectedCount()
                                    + " planned=" + result.plannedSteps())));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Rollback failed", t);
                    server.execute(() -> send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Rollback error: " + t.getMessage())));
                }
            });
            return 1;
        }
    }

    // ====================================================================== Restore

    /** {@code /vg restore <filter>} — default radius is {@value #DEFAULT_ROLLBACK_RADIUS} blocks when omitted. */
    public static final class Restore {
        private Restore() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            String raw = StringArgumentType.getString(ctx, "filter");
            QueryFilter qf;
            try {
                qf = PARSER.parse(raw, ctxOf(src)).withDefaultWorld(playerWorldOf(src));
            } catch (QueryParseException e) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] " + e.getMessage()));
                return 0;
            }
            qf = withDefaultRollbackRadius(qf, src);
            MinecraftServer server = src.getServer();
            UUID actor = actorUuid(src);
            final QueryFilter filter = qf;
            WORKER.submit(() -> {
                try {
                    RollbackResult result = g.rollbackEngine().restore(filter, filter.preview(), actor);
                    g.undoStack().push(actor != null ? actor
                            : network.vonix.guardian.core.rollback.UndoStack.CONSOLE_KEY, result);
                    server.execute(() -> send(src, ChatRenderer.success(g.theme(),
                            "[VonixGuardian] Restore affected=" + result.affectedCount()
                                    + " planned=" + result.plannedSteps())));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Restore failed", t);
                    server.execute(() -> send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Restore error: " + t.getMessage())));
                }
            });
            return 1;
        }
    }

    // ====================================================================== Purge

    /** {@code /vg purge <filter>} */
    public static final class Purge {
        private Purge() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            String raw = StringArgumentType.getString(ctx, "filter");
            QueryFilter qf;
            try {
                qf = PARSER.parse(raw, ctxOf(src)).withDefaultWorld(playerWorldOf(src));
            } catch (QueryParseException e) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] " + e.getMessage()));
                return 0;
            }
            // CP-1:1 safety floor: console = 24h, in-game = 30d.
            final boolean isConsole = !(src.getEntity() instanceof ServerPlayer);
            final long minAgeSeconds = isConsole
                    ? g.config().purge().minAgeSecondsConsole()
                    : g.config().purge().minAgeSecondsInGame();
            MinecraftServer server = src.getServer();
            final QueryFilter filter = qf;
            WORKER.submit(() -> {
                try {
                    var result = g.purgeEngine().purge(filter, minAgeSeconds);
                    server.execute(() -> send(src, ChatRenderer.warning(g.theme(),
                            "[VonixGuardian] Purge removed " + result.deletedCount()
                                    + " rows (minAge=" + minAgeSeconds + "s).")));
                } catch (IllegalArgumentException tooRecent) {
                    server.execute(() -> send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Purge refused: " + tooRecent.getMessage()
                                    + " (CP-1:1 safety floor; use a larger t: window)")));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Purge failed", t);
                    server.execute(() -> send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Purge error: " + t.getMessage())));
                }
            });
            return 1;
        }
    }

    // ====================================================================== Undo

    /** {@code /vg undo} — reverse the last rollback/restore by executing its inverse. */
    public static final class Undo {
        private Undo() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            UUID actor = actorUuid(src);
            UUID key = actor != null ? actor
                    : network.vonix.guardian.core.rollback.UndoStack.CONSOLE_KEY;
            var popped = g.undoStack().pop(key);
            if (popped.isEmpty()) {
                send(src, ChatRenderer.muted(g.theme(), "[VonixGuardian] Nothing to undo."));
                return 0;
            }
            RollbackResult prev = popped.get();
            QueryFilter originalFilter = prev.originalFilter();
            if (originalFilter == null) {
                // Legacy pre-v1.1.6 undo entry — no filter captured, cannot revert world state.
                LOG.info(Guardian.MARKER,
                        "Undo: popped legacy entry (no originalFilter); {} affected id(s) dropped from history",
                        prev.affectedCount());
                send(src, ChatRenderer.warning(g.theme(),
                        "[VonixGuardian] Undo: dropped " + prev.affectedCount()
                                + " entries from history (legacy entry — world state not reverted)."));
                return 1;
            }
            MinecraftServer server = src.getServer();
            final RollbackResult.Mode inverse = prev.inverseMode();
            // W3-B2: run the inverse of the previous operation on exactly the same
            // action set (originalFilter is normalized w/r/t rolledBack for the
            // inverse mode by RollbackEngine.plan). We deliberately do NOT push
            // the resulting RollbackResult back onto UndoStack — doing so would
            // let repeated `/vg undo` invocations ping-pong between rollback and
            // restore forever. Undo is a one-shot revert of the last op.
            WORKER.submit(() -> {
                try {
                    var plan = g.rollbackEngine().plan(originalFilter, inverse, actor);
                    RollbackResult result = g.rollbackEngine().execute(plan, false);
                    server.execute(() -> send(src, ChatRenderer.success(g.theme(),
                            "[VonixGuardian] Undo (" + inverse + ") affected="
                                    + result.affectedCount()
                                    + " planned=" + result.plannedSteps())));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Undo failed", t);
                    server.execute(() -> send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Undo error: " + t.getMessage())));
                }
            });
            return 1;
        }
    }

    // ====================================================================== Consumer

    /** {@code /vg consumer [pause|resume|toggle]} — manage the writer queue. */
    public static final class Consumer {
        private Consumer() {}

        public static int status(CommandContext<CommandSourceStack> ctx, Guardian g) {
            send(ctx.getSource(), ChatRenderer.primary(g.theme(),
                    "[VonixGuardian] Consumer is currently "
                            + (g.queue().isPaused() ? "PAUSED" : "RUNNING")
                            + " (queue.depth=" + g.queue().depth() + ")"));
            return 1;
        }

        public static int pause(CommandContext<CommandSourceStack> ctx, Guardian g) {
            g.queue().setPaused(true);
            send(ctx.getSource(), ChatRenderer.warning(g.theme(),
                    "[VonixGuardian] Consumer PAUSED — incoming events are buffered, not written."));
            return 1;
        }

        public static int resume(CommandContext<CommandSourceStack> ctx, Guardian g) {
            g.queue().setPaused(false);
            send(ctx.getSource(), ChatRenderer.success(g.theme(),
                    "[VonixGuardian] Consumer RESUMED — draining buffered events."));
            return 1;
        }

        public static int toggle(CommandContext<CommandSourceStack> ctx, Guardian g) {
            boolean now = !g.queue().isPaused();
            g.queue().setPaused(now);
            if (now) {
                send(ctx.getSource(), ChatRenderer.warning(g.theme(),
                        "[VonixGuardian] Consumer PAUSED."));
            } else {
                send(ctx.getSource(), ChatRenderer.success(g.theme(),
                        "[VonixGuardian] Consumer RESUMED."));
            }
            return 1;
        }
    }

    // ====================================================================== Status

    /** {@code /vg status} — queue + submission counters. */
    public static final class Status {
        private Status() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            send(src, ChatRenderer.primary(g.theme(),
                    "[VonixGuardian] submitted=" + g.submitted()
                            + " gated=" + g.gated()
                            + " queue.depth=" + g.queue().depth()
                            + " queue.dropped=" + g.queue().dropped()
                            + " consumer=" + (g.queue().isPaused() ? "paused" : "running")));
            return 1;
        }
    }

    // ====================================================================== Reload

    /** {@code /vg reload} — placeholder. */
    public static final class Reload {
        private Reload() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            Guardian.ReloadResult r = g.reloadConfig(g.configPath());
            String hot = r.hotSwapped().isEmpty() ? "(none)" : String.join(", ", r.hotSwapped());
            String rst = r.requiresRestart().isEmpty() ? "(none)" : String.join(", ", r.requiresRestart());
            String err = r.errors().isEmpty() ? "(none)" : String.join(", ", r.errors());
            send(src, ChatRenderer.primary(g.theme(),
                    "[VonixGuardian] Hot-swapped: " + r.hotSwapped().size() + " " + hot));
            send(src, ChatRenderer.warning(g.theme(),
                    "[VonixGuardian] Requires restart: " + r.requiresRestart().size() + " " + rst));
            if (r.errors().isEmpty()) {
                send(src, ChatRenderer.muted(g.theme(),
                        "[VonixGuardian] Errors: 0 (none)"));
            } else {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Errors: " + r.errors().size() + " " + err));
            }
            return 1;
        }
    }

    // ====================================================================== MigrateDb

    /** {@code /vg migrate-db <target-type> [CONFIRM]} — console-only backend copy. */
    public static final class MigrateDb {
        private MigrateDb() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g, boolean withConfirmArg) {
            CommandSourceStack src = ctx.getSource();
            // Console-only, matching CoreProtect Patreon's /co migrate-db.
            if (src.getEntity() instanceof ServerPlayer) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] migrate-db is console-only."));
                return 0;
            }
            String target = StringArgumentType.getString(ctx, "target");
            final boolean confirmed;
            if (withConfirmArg) {
                String confirm = StringArgumentType.getString(ctx, "confirm");
                confirmed = MigrateDbCommand.CONFIRM_TOKEN.equals(confirm);
                if (!confirmed) {
                    send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] migrate-db: second argument must be '"
                                    + MigrateDbCommand.CONFIRM_TOKEN + "' to proceed."));
                    return 0;
                }
            } else {
                confirmed = false;
            }
            MinecraftServer server = src.getServer();
            WORKER.submit(() -> MigrateDbCommand.run(g, target, confirmed, line ->
                    server.execute(() -> send(src, ChatRenderer.muted(g.theme(), line)))));
            return 1;
        }
    }

    // ====================================================================== Help

    /** {@code /vg help} — CoreProtect-style command summary. */
    public static final class Help {
        private Help() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            send(src, ChatRenderer.primary(g.theme(),
                    "[VonixGuardian] CoreProtect-style commands (aliases: /co, /guardian):"));
            String[] lines = {
                    "/vg inspect            (alias: /vg i)  — toggle inspect mode",
                    "/vg lookup <filter>    (alias: /vg l)  — search audit log; <page>[:<perPage>] before filter",
                    "/vg rollback <filter>  (alias: /vg rb) — undo logged actions (default radius 10)",
                    "/vg restore <filter>   (alias: /vg rs) — redo rolled-back actions (default radius 10)",
                    "/vg purge <filter>                       — delete log rows",
                    "/vg near                                — quick lookup r:5 t:1h around you",
                    "/vg undo                                — drop last rollback from history",
                    "/vg consumer pause|resume|toggle        — pause the writer queue",
                    "/vg status                              — queue / submission counters",
                    "/vg reload                              — re-read config.json + hot-swap safe knobs",
                    "/vg migrate-db <sqlite|mysql|postgresql> CONFIRM  — console-only backend copy",
                    "",
                    "Filter tokens: u:<player|#sentinel>  t:<time>  r:<n|#global|#world_*>",
                    "               a:[+/-]<action>       i:<id>    e:<id>",
                    "Hash flags:    #preview  #count  #verbose  #silent  #optimize",
                    "User sentinels: #fire #tnt #creeper #explosion #water #lava #mob",
                    "Actions: block container inventory item kill session login chat command click sign username",
            };
            for (String l : lines) {
                send(src, ChatRenderer.muted(g.theme(), "  " + l));
            }
            return 1;
        }
    }
}
