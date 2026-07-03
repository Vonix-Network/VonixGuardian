/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.Registry;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.perms.LookupPermissionFilter;
import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParseException;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.rollback.RollbackOptions;
import network.vonix.guardian.core.rollback.RollbackProgress;
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
                .executes(ctx -> Help.usage(ctx, g, "root"))
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
                        .executes(ctx -> Help.usage(ctx, g, "lookup"))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Lookup.run(ctx, g))))
                .then(Commands.literal("l")
                        .requires(s -> hasPerm(s, "vonixguardian.command.lookup", g))
                        .executes(ctx -> Help.usage(ctx, g, "lookup"))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Lookup.run(ctx, g))))
                // rollback (long + short)
                .then(Commands.literal("rollback")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .executes(ctx -> Help.usage(ctx, g, "rollback"))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Rollback.run(ctx, g, false))))
                .then(Commands.literal("rb")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .executes(ctx -> Help.usage(ctx, g, "rollback"))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Rollback.run(ctx, g, false))))
                // restore (long + short)
                .then(Commands.literal("restore")
                        .requires(s -> hasPerm(s, "vonixguardian.command.restore", g))
                        .executes(ctx -> Help.usage(ctx, g, "restore"))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Restore.run(ctx, g))))
                .then(Commands.literal("rs")
                        .requires(s -> hasPerm(s, "vonixguardian.command.restore", g))
                        .executes(ctx -> Help.usage(ctx, g, "restore"))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .suggests(GuardianSuggestions.filterTokens())
                                .executes(ctx -> Restore.run(ctx, g))))
                // purge
                .then(Commands.literal("purge")
                        .requires(s -> hasPerm(s, "vonixguardian.command.purge", g))
                        .executes(ctx -> Help.usage(ctx, g, "purge"))
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
                // config get/set (hot-swap-safe keys only)
                .then(Commands.literal("config")
                        .requires(s -> hasPerm(s, "vonixguardian.command.config", g))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ctx -> Config.get(ctx, g))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> Config.set(ctx, g))))))
                // teleport <world> <x> [y] <z>  (CP parity /co teleport)
                .then(Commands.literal("teleport")
                        .requires(s -> hasPerm(s, "vonixguardian.command.teleport", g))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .then(Commands.argument("coords", StringArgumentType.greedyString())
                                        .executes(ctx -> Teleport.run(ctx, g)))))
                .then(Commands.literal("tp")
                        .requires(s -> hasPerm(s, "vonixguardian.command.teleport", g))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .then(Commands.argument("coords", StringArgumentType.greedyString())
                                        .executes(ctx -> Teleport.run(ctx, g)))))
                // give <itemId> [amount]  (CP parity /co give)
                .then(Commands.literal("give")
                        .requires(s -> hasPerm(s, "vonixguardian.command.give", g))
                        .then(Commands.argument("itemId", StringArgumentType.word())
                                .executes(ctx -> Give.run(ctx, g, 1))
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .executes(ctx -> Give.runWithAmount(ctx, g)))))
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

    /**
     * Deliver {@code msg} to the calling player if still online (looked up
     * fresh by {@code actor} UUID inside {@code server.execute}); otherwise
     * fall back to the (possibly stale) {@link CommandSourceStack} success
     * channel. Introduced in v1.2.7 to fix async output landing on the server
     * console instead of the player's chat — the {@link CommandSourceStack}
     * captured at dispatch time can lose its entity binding by the time an
     * async worker's {@code server.execute(...)} callback fires.
     */
    private static void sendToPlayerOrSrc(MinecraftServer server, CommandSourceStack src, UUID actor, Component msg) {
        try {
            ServerPlayer sp = actor == null || server == null
                    ? null
                    : server.getPlayerList().getPlayer(actor);
            if (sp != null) {
                sp.sendSystemMessage(msg);
                return;
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "Failed to route to player", t);
        }
        send(src, msg);
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

    private static RollbackOptions rollbackOptions(MinecraftServer server, CommandSourceStack src) {
        return new RollbackOptions(
                RollbackOptions.DEFAULT_PAGE_SIZE,
                RollbackOptions.DEFAULT_MAX_SCANNED_ACTIONS,
                RollbackOptions.DEFAULT_MAX_PLANNED_STEPS,
                () -> Thread.currentThread().isInterrupted(),
                progress -> {
                    if (!shouldReportRollbackProgress(progress)) return;
                    UUID progressActor = actorUuid(src);
                    server.execute(() -> sendToPlayerOrSrc(server, src, progressActor, ChatRenderer.muted(null,
                            "[VonixGuardian] Rollback planning: scanned=" + progress.scannedActions()
                                    + " planned=" + progress.plannedSteps()
                                    + " skipped=" + progress.skippedActions())));
                }
        );
    }

    private static boolean shouldReportRollbackProgress(RollbackProgress progress) {
        return progress.scanLimitReached() || progress.plannedLimitReached() || progress.cancelled()
                || progress.pagesFetched() == 1 || progress.pagesFetched() % 10 == 0;
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
                qf.rolledBack(), qf.countOnly(), qf.preview(), qf.verbose(), qf.silent(), qf.optimize(),
                qf.worldEditPlayer()
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
            // Position-anchored inspector lookup. `p:x,y,z` pins the search
            // center to the clicked block regardless of caller position,
            // `r:2` widens the window to a 5x5x5 around the clicked block, and
            // `r:#world_<key>` scopes the query to the block's world so
            // cross-world coord collisions don't leak in.
            String f = "p:" + x + "," + y + "," + z + " r:2 t:30d r:#world_" + worldId;
            runWithFilter(src, g, f, 1, DEFAULT_PAGE_SIZE);
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
            final UUID viewer = actorUuid(src);
            WORKER.submit(() -> {
                try {
                    long total = g.dao().count(filter);
                    int pages = (int) Math.max(1L, (total + perPageF - 1) / Math.max(1, perPageF));
                    int pageActual = Math.min(Math.max(1, pageF), pages);
                    int offset = (pageActual - 1) * perPageF;
                    List<Action> rows = g.dao().query(filter, offset, perPageF);
                    // W3-B7: filter rows by CoreProtect-style child perms (e.g. lookup.chat)
                    rows = LookupPermissionFilter.filter(g.perms(), viewer, PermissionNode.LOOKUP, rows);
                    long now = System.currentTimeMillis();
                    List<Component> pageOut = LookupFormatter.page(g.theme(), rows, total, pageActual, perPageF, now);
                    final long totalF = total;
                    server.execute(() -> {
                        if (totalF == 0) {
                            sendToPlayerOrSrc(server, src, viewer, ChatRenderer.muted(g.theme(),
                                    "[VonixGuardian] No results found. Try a wider radius (r:20+) or longer time (t:24h+), or check filter tokens."));
                            return;
                        }
                        for (Component c : pageOut) {
                            sendToPlayerOrSrc(server, src, viewer, c);
                        }
                    });
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Lookup failed", t);
                    server.execute(() -> sendToPlayerOrSrc(server, src, viewer, ChatRenderer.error(g.theme(),
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
                    RollbackOptions options = rollbackOptions(server, src);
                    RollbackResult result = g.rollbackEngine().rollback(filter, preview, actor, options);
                    g.undoStack().push(actor != null ? actor
                            : network.vonix.guardian.core.rollback.UndoStack.CONSOLE_KEY, result);
                    server.execute(() -> {
                        sendToPlayerOrSrc(server, src, actor, ChatRenderer.success(g.theme(),
                                "[VonixGuardian] Rollback " + (preview ? "(preview) " : "")
                                        + "affected=" + result.affectedCount()
                                        + " planned=" + result.plannedSteps()));
                        if (result.affectedCount() == 0) {
                            sendToPlayerOrSrc(server, src, actor, ChatRenderer.muted(g.theme(),
                                    "[VonixGuardian] Rollback found 0 matching actions. Explosions record their blast CENTER — try r:20+ or move to the source of the damage."));
                        }
                    });
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Rollback failed", t);
                    server.execute(() -> sendToPlayerOrSrc(server, src, actor, ChatRenderer.error(g.theme(),
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
                    RollbackOptions options = rollbackOptions(server, src);
                    RollbackResult result = g.rollbackEngine().restore(filter, filter.preview(), actor, options);
                    g.undoStack().push(actor != null ? actor
                            : network.vonix.guardian.core.rollback.UndoStack.CONSOLE_KEY, result);
                    server.execute(() -> {
                        sendToPlayerOrSrc(server, src, actor, ChatRenderer.success(g.theme(),
                                "[VonixGuardian] Restore affected=" + result.affectedCount()
                                        + " planned=" + result.plannedSteps()));
                        if (result.affectedCount() == 0) {
                            sendToPlayerOrSrc(server, src, actor, ChatRenderer.muted(g.theme(),
                                    "[VonixGuardian] Restore found 0 matching actions. Explosions record their blast CENTER — try r:20+ or move to the source of the damage."));
                        }
                    });
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Restore failed", t);
                    server.execute(() -> sendToPlayerOrSrc(server, src, actor, ChatRenderer.error(g.theme(),
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
                    server.execute(() -> sendToPlayerOrSrc(server, src, actorUuid(src), ChatRenderer.warning(g.theme(),
                            "[VonixGuardian] Purge removed " + result.deletedCount()
                                    + " rows (minAge=" + minAgeSeconds + "s).")));
                } catch (IllegalArgumentException tooRecent) {
                    server.execute(() -> sendToPlayerOrSrc(server, src, actorUuid(src), ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Purge refused: " + tooRecent.getMessage()
                                    + " (CP-1:1 safety floor; use a larger t: window)")));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Purge failed", t);
                    server.execute(() -> sendToPlayerOrSrc(server, src, actorUuid(src), ChatRenderer.error(g.theme(),
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
                    server.execute(() -> sendToPlayerOrSrc(server, src, actor, ChatRenderer.success(g.theme(),
                            "[VonixGuardian] Undo (" + inverse + ") affected="
                                    + result.affectedCount()
                                    + " planned=" + result.plannedSteps())));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Undo failed", t);
                    server.execute(() -> sendToPlayerOrSrc(server, src, actor, ChatRenderer.error(g.theme(),
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
            MinecraftServer server = src.getServer();
            UUID actor = actorUuid(src);
            // v1.1.7: full CoreProtect-parity /vg status — multi-line, on-demand.
            // Section headers begin with "§ " and render as bold+aqua; body lines as primary.
            // v1.2.7: force per-player delivery — src.sendSuccess() logs to server
            // console when the CommandSourceStack's entity binding is stale.
            for (String line : network.vonix.guardian.core.diagnostics.GuardianStatus.render(g)) {
                if (line.startsWith("§ ")) {
                    sendToPlayerOrSrc(server, src, actor,
                            ChatRenderer.section(g.theme(), "[VonixGuardian] " + line.substring(2)));
                } else {
                    sendToPlayerOrSrc(server, src, actor,
                            ChatRenderer.primary(g.theme(), line));
                }
            }
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


    // ====================================================================== Config

    /** {@code /vg config get|set} — hot-swap-safe config keys. */
    public static final class Config {
        private Config() {}

        public static int get(CommandContext<CommandSourceStack> ctx, Guardian g) {
            String key = StringArgumentType.getString(ctx, "key");
            String value = readValue(g.config(), key);
            if (value == null) {
                send(ctx.getSource(), ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Unknown or read-only config key: " + key));
                return 0;
            }
            send(ctx.getSource(), ChatRenderer.primary(g.theme(),
                    "[VonixGuardian] " + key + " = " + value));
            return 1;
        }

        public static int set(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            String key = StringArgumentType.getString(ctx, "key");
            String value = StringArgumentType.getString(ctx, "value").trim();
            java.nio.file.Path path = g.configPath();
            if (path == null) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Cannot persist config: no config path is known."));
                return 0;
            }
            final GuardianConfig next;
            try {
                next = withValue(g.config(), key, value);
            } catch (IllegalArgumentException e) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] " + e.getMessage()));
                return 0;
            }
            if (next == null) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Unknown or read-only config key: " + key));
                return 0;
            }
            try {
                next.validate();
                ConfigLoader.save(path, next);
                Guardian.ReloadResult r = g.reloadConfig(path);
                if (!r.errors().isEmpty()) {
                    send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Config saved but reload failed: " + String.join(", ", r.errors())));
                    return 0;
                }
                send(src, ChatRenderer.success(g.theme(),
                        "[VonixGuardian] Updated " + key + " = " + readValue(g.config(), key)
                                + " (hot-swapped: " + (r.hotSwapped().isEmpty() ? "none" : String.join(", ", r.hotSwapped())) + ")"));
                return 1;
            } catch (Exception e) {
                LOG.warn(Guardian.MARKER, "Config set failed", e);
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Config update failed: " + e.getMessage()));
                return 0;
            }
        }

        private static String readValue(GuardianConfig c, String key) {
            return switch (key) {
                case "theme" -> c.theme();
                case "logFile.enabled" -> Boolean.toString(c.logFile().enabled());
                case "lookup.defaultPageSize" -> Integer.toString(c.lookup().defaultPageSize());
                case "lookup.maxRadius" -> Integer.toString(c.lookup().maxRadius());
                case "lookup.maxResultRows" -> Integer.toString(c.lookup().maxResultRows());
                case "privacy.hashIps" -> Boolean.toString(c.privacy().hashIps());
                case "purge.minAgeSecondsConsole" -> Long.toString(c.purge().minAgeSecondsConsole());
                case "purge.minAgeSecondsInGame" -> Long.toString(c.purge().minAgeSecondsInGame());
                case "purge.autoPurgeSeconds" -> Long.toString(c.purge().autoPurgeSeconds());
                case "purge.autoPurgeTime" -> c.purge().autoPurgeTime();
                case "actions.logBlocks" -> Boolean.toString(c.actions().logBlocks());
                case "actions.logContainers" -> Boolean.toString(c.actions().logContainers());
                case "actions.logItems" -> Boolean.toString(c.actions().logItems());
                case "actions.logEntities" -> Boolean.toString(c.actions().logEntities());
                case "actions.logExplosions" -> Boolean.toString(c.actions().logExplosions());
                case "actions.logChat" -> Boolean.toString(c.actions().logChat());
                case "actions.logCommands" -> Boolean.toString(c.actions().logCommands());
                case "actions.logSessions" -> Boolean.toString(c.actions().logSessions());
                case "actions.logSigns" -> Boolean.toString(c.actions().logSigns());
                case "actions.logInteractions" -> Boolean.toString(c.actions().logInteractions());
                case "actions.logWorldEvents" -> Boolean.toString(c.actions().logWorldEvents());
                case "actions.entityBlockChangeCoalesceWindowMs" -> Long.toString(c.actions().entityBlockChangeCoalesceWindowMs());
                case "actions.entityBlockChangeMaxTracked" -> Integer.toString(c.actions().entityBlockChangeMaxTracked());
                case "actions.entityChangeLogAllEntities" -> Boolean.toString(c.actions().entityChangeLogAllEntities());
                case "actions.mixinHotEvents" -> Boolean.toString(c.actions().mixinHotEvents());
                case "storage.persistNbt" -> Boolean.toString(c.storage().persistNbt());
                case "rollback.explosionSupplementalReach" -> Integer.toString(c.rollback().explosionSupplementalReach());
                case "language" -> c.language();
                default -> null;
            };
        }

        private static GuardianConfig withValue(GuardianConfig c, String key, String value) {
            GuardianConfig.Actions a = c.actions();
            GuardianConfig.LogFile lf = c.logFile();
            GuardianConfig.Lookup l = c.lookup();
            GuardianConfig.Privacy pr = c.privacy();
            GuardianConfig.Purge pu = c.purge();
            return switch (key) {
                case "theme" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, pu, c.storage(), c.rollback(), value, c.language());
                case "logFile.enabled" -> new GuardianConfig(c.database(), c.queue(), new GuardianConfig.LogFile(parseBool(value, key), lf.directory(), lf.gzipRotated(), lf.retentionDays(), lf.forceSyncOnFlush()), a, c.permissions(), l, pr, pu, c.storage(), c.rollback(), c.theme(), c.language());
                case "lookup.defaultPageSize" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), new GuardianConfig.Lookup(parseInt(value, key), l.maxRadius(), l.maxResultRows(), l.maxConcurrent()), pr, pu, c.storage(), c.rollback(), c.theme(), c.language());
                case "lookup.maxRadius" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), new GuardianConfig.Lookup(l.defaultPageSize(), parseInt(value, key), l.maxResultRows(), l.maxConcurrent()), pr, pu, c.storage(), c.rollback(), c.theme(), c.language());
                case "lookup.maxResultRows" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), new GuardianConfig.Lookup(l.defaultPageSize(), l.maxRadius(), parseInt(value, key), l.maxConcurrent()), pr, pu, c.storage(), c.rollback(), c.theme(), c.language());
                case "privacy.hashIps" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, new GuardianConfig.Privacy(parseBool(value, key), pr.salt()), pu, c.storage(), c.rollback(), c.theme(), c.language());
                case "purge.minAgeSecondsConsole" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, new GuardianConfig.Purge(parseLong(value, key), pu.minAgeSecondsInGame(), pu.autoPurgeSeconds(), pu.autoPurgeTime()), c.storage(), c.rollback(), c.theme(), c.language());
                case "purge.minAgeSecondsInGame" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, new GuardianConfig.Purge(pu.minAgeSecondsConsole(), parseLong(value, key), pu.autoPurgeSeconds(), pu.autoPurgeTime()), c.storage(), c.rollback(), c.theme(), c.language());
                case "purge.autoPurgeSeconds" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, new GuardianConfig.Purge(pu.minAgeSecondsConsole(), pu.minAgeSecondsInGame(), parseLong(value, key), pu.autoPurgeTime()), c.storage(), c.rollback(), c.theme(), c.language());
                case "purge.autoPurgeTime" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, new GuardianConfig.Purge(pu.minAgeSecondsConsole(), pu.minAgeSecondsInGame(), pu.autoPurgeSeconds(), value), c.storage(), c.rollback(), c.theme(), c.language());
                case "actions.logBlocks" -> withActions(c, new GuardianConfig.Actions(parseBool(value, key), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logContainers" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), parseBool(value, key), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logItems" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), parseBool(value, key), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logEntities" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), parseBool(value, key), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logExplosions" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), parseBool(value, key), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logChat" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), parseBool(value, key), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logCommands" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), parseBool(value, key), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logSessions" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), parseBool(value, key), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logSigns" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), parseBool(value, key), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logInteractions" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), parseBool(value, key), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.logWorldEvents" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), parseBool(value, key), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.entityBlockChangeCoalesceWindowMs" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), parseLong(value, key), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.entityBlockChangeMaxTracked" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), parseInt(value, key), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.entityChangeLogAllEntities" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), parseBool(value, key), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()));
                case "actions.mixinHotEvents" -> withActions(c, new GuardianConfig.Actions(a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(), a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(), a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(), a.entityChangeAllowlist(), a.entityChangeLogAllEntities(), a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(), a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(), a.logDuplicateSuppression(), a.logCancelledChat(), parseBool(value, key)));
                case "storage.persistNbt" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, pu, new GuardianConfig.Storage(parseBool(value, key)), c.rollback(), c.theme(), c.language());
                case "rollback.explosionSupplementalReach" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, pu, c.storage(), new GuardianConfig.Rollback(parseInt(value, key)), c.theme(), c.language());
                case "language" -> new GuardianConfig(c.database(), c.queue(), lf, a, c.permissions(), l, pr, pu, c.storage(), c.rollback(), c.theme(), value);
                default -> null;
            };
        }

        private static GuardianConfig withActions(GuardianConfig c, GuardianConfig.Actions a) {
            return new GuardianConfig(c.database(), c.queue(), c.logFile(), a, c.permissions(), c.lookup(), c.privacy(), c.purge(), c.storage(), c.rollback(), c.theme(), c.language());
        }

        private static boolean parseBool(String value, String key) {
            if ("true".equalsIgnoreCase(value)) return true;
            if ("false".equalsIgnoreCase(value)) return false;
            throw new IllegalArgumentException(key + " must be true or false");
        }

        private static int parseInt(String value, String key) {
            try { return Integer.parseInt(value); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer"); }
        }

        private static long parseLong(String value, String key) {
            try { return Long.parseLong(value); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer"); }
        }
    }

    // ====================================================================== Teleport

    /** {@code /vg teleport <world> <x> [y] <z>} (alias {@code /vg tp}) — CoreProtect-parity admin teleport. */
    public static final class Teleport {
        private Teleport() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            if (!(src.getEntity() instanceof ServerPlayer p)) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] /vg teleport must be run by a player."));
                return 0;
            }
            String worldArg = StringArgumentType.getString(ctx, "world");
            String coords = StringArgumentType.getString(ctx, "coords").trim();
            String[] parts = coords.split("\\s+");
            if (parts.length < 2 || parts.length > 3) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Usage: /vg teleport <world> <x> [y] <z>"));
                return 0;
            }
            ServerLevel level = resolveLevel(src.getServer(), worldArg);
            if (level == null) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Unknown world: " + worldArg));
                return 0;
            }
            Double x, y, z;
            try {
                x = Double.parseDouble(parts[0].replaceAll("[^0-9.\\-]", ""));
                if (parts.length == 3) {
                    y = Double.parseDouble(parts[1].replaceAll("[^0-9.\\-]", ""));
                    z = Double.parseDouble(parts[2].replaceAll("[^0-9.\\-]", ""));
                } else {
                    z = Double.parseDouble(parts[1].replaceAll("[^0-9.\\-]", ""));
                    double curY = p.getY();
                    if (curY > 63) curY = 63;
                    y = curY;
                }
            } catch (NumberFormatException e) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Invalid coordinates: " + coords));
                return 0;
            }
            final double fx = x, fy = y, fz = z;
            final ServerLevel fl = level;
            src.getServer().execute(() -> {
                try {
                    p.teleportTo(fl, fx, fy, fz, p.getYRot(), p.getXRot());
                    send(src, ChatRenderer.success(g.theme(),
                            "[VonixGuardian] Teleported to " + worldArg + " " + fx + " " + fy + " " + fz));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Teleport failed", t);
                    send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Teleport failed: " + t.getMessage()));
                }
            });
            return 1;
        }

        private static ServerLevel resolveLevel(MinecraftServer server, String arg) {
            if (server == null || arg == null) return null;
            String key = arg;
            if (!key.contains(":")) {
                // Accept plain "nether"/"overworld"/"end" shortcuts.
                switch (key) {
                    case "overworld": key = "minecraft:overworld"; break;
                    case "nether":    key = "minecraft:the_nether"; break;
                    case "end":       key = "minecraft:the_end"; break;
                    default:          key = "minecraft:" + key;
                }
            }
            ResourceLocation rl = ResourceLocation.tryParse(key);
            if (rl == null) return null;
            ResourceKey<Level> key0 = ResourceKey.create(Registry.DIMENSION_REGISTRY, rl);
            return server.getLevel(key0);
        }
    }

    // ====================================================================== Give

    /** {@code /vg give <itemId> [amount]} — CoreProtect-parity give command. */
    public static final class Give {
        private Give() {}

        public static int runWithAmount(CommandContext<CommandSourceStack> ctx, Guardian g) {
            int amount = 1;
            try { amount = Math.max(1, Integer.parseInt(StringArgumentType.getString(ctx, "amount"))); }
            catch (Exception ignore) {}
            return run(ctx, g, amount);
        }

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g, int amount) {
            CommandSourceStack src = ctx.getSource();
            if (!(src.getEntity() instanceof ServerPlayer p)) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] /vg give must be run by a player."));
                return 0;
            }
            String itemArg = StringArgumentType.getString(ctx, "itemId");
            String id = itemArg.contains(":") ? itemArg : "minecraft:" + itemArg;
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Invalid item id: " + itemArg));
                return 0;
            }
            Item item = Registry.ITEM.get(rl);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                send(src, ChatRenderer.error(g.theme(),
                        "[VonixGuardian] Unknown item: " + id));
                return 0;
            }
            final int qty = Math.max(1, amount);
            src.getServer().execute(() -> {
                try {
                    ItemStack stack = new ItemStack(item, qty);
                    boolean ok = p.getInventory().add(stack);
                    if (!ok || !stack.isEmpty()) {
                        p.drop(stack, false);
                    }
                    send(src, ChatRenderer.success(g.theme(),
                            "[VonixGuardian] Gave " + qty + "x " + id));
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Give failed", t);
                    send(src, ChatRenderer.error(g.theme(),
                            "[VonixGuardian] Give failed: " + t.getMessage()));
                }
            });
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
            final UUID actor = actorUuid(src);
            WORKER.submit(() -> MigrateDbCommand.run(g, target, confirmed, line ->
                    server.execute(() -> sendToPlayerOrSrc(server, src, actor, ChatRenderer.muted(g.theme(), line)))));
            return 1;
        }
    }

    // ====================================================================== Help

    /** {@code /vg help} — CoreProtect-style command summary. */
    public static final class Help {
        private Help() {}

        /**
         * Compact 3-4 line usage hint for a specific subcommand. Called from
         * bare-literal {@code .executes(...)} handlers so we never render
         * Brigadier's cryptic {@code Unknown or incomplete command ...
         * <--[HERE]}. Matches CoreProtect UX.
         *
         * @param name usage key: "root", "lookup", "rollback", "restore", "purge"
         * @since 1.2.7
         */
        public static int usage(CommandContext<CommandSourceStack> ctx, Guardian g, String name) {
            CommandSourceStack src = ctx.getSource();
            switch (name) {
                case "lookup":
                    send(src, ChatRenderer.primary(g.theme(),
                            "[VonixGuardian] /vg lookup [page[:perPage]] <filter>"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Examples:  /vg lookup r:10 t:2h  |  /vg lookup u:Pargon a:-block  |  /vg lookup #count r:20"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Tokens:    u: user  t: time  r: radius  a: action  i: include  e: exclude   #preview #count"));
                    break;
                case "rollback":
                    send(src, ChatRenderer.primary(g.theme(),
                            "[VonixGuardian] /vg rollback <filter>   (alias: /vg rb)"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Examples:  /vg rollback r:10 t:2m  |  /vg rollback u:Griefer t:1h  |  /vg rollback #preview r:20 t:5m"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Tip: default radius is 10 if r: omitted. Explosions record their blast CENTER — widen r: to cover splash."));
                    break;
                case "restore":
                    send(src, ChatRenderer.primary(g.theme(),
                            "[VonixGuardian] /vg restore <filter>    (alias: /vg rs)"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Examples:  /vg restore r:10 t:2m  |  /vg restore u:Pargon t:1h"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Tip: default radius is 10 if r: omitted. Redoes rows previously rolled back."));
                    break;
                case "purge":
                    send(src, ChatRenderer.primary(g.theme(),
                            "[VonixGuardian] /vg purge <filter>"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Examples:  /vg purge t:30d  |  /vg purge t:60d a:block"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  Note: t: means retention — rows OLDER than t: are deleted. Console safety floor: 24h; in-game: 30d."));
                    break;
                case "root":
                default:
                    send(src, ChatRenderer.primary(g.theme(),
                            "[VonixGuardian] Available commands (see /vg help for full list):"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  /vg lookup <filter>   /vg rollback <filter>   /vg restore <filter>   /vg purge <filter>"));
                    send(src, ChatRenderer.muted(g.theme(),
                            "  /vg inspect   /vg near   /vg status   /vg undo   /vg consumer pause|resume   /vg help"));
                    break;
            }
            return 1;
        }

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
                    "/vg config get <key>                 — show a hot-swap-safe config value",
                    "/vg config set <key> <value>         — persist + hot-swap a safe config value",
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
