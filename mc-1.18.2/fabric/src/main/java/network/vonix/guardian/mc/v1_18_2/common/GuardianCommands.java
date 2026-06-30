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
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParseException;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.rollback.RollbackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Brigadier command tree for {@code /vg ...} on 1.18.2.
 *
 * <p>1.18.2 quirk: {@code CommandSourceStack.sendSuccess} takes a {@link Component}
 * directly (no {@code Supplier<Component>} overload — that arrived in 1.20).
 */
public final class GuardianCommands {

    private static final Logger LOG = LoggerFactory.getLogger(GuardianCommands.class);
    private static final QueryParser PARSER = new QueryParser();
    private static final int PAGE_SIZE = 10;

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VonixGuardian-Cmd");
        t.setDaemon(true);
        return t;
    });

    private GuardianCommands() {
        // utility
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Guardian g) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("vg")
                .requires(s -> hasPerm(s, "vonixguardian.command.use", g))
                .then(Commands.literal("inspect")
                        .requires(s -> hasPerm(s, "vonixguardian.command.inspect", g))
                        .executes(ctx -> Inspect.toggle(ctx, g)))
                .then(Commands.literal("lookup")
                        .requires(s -> hasPerm(s, "vonixguardian.command.lookup", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> Lookup.run(ctx, g))))
                .then(Commands.literal("rollback")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> Rollback.run(ctx, g, false))))
                .then(Commands.literal("restore")
                        .requires(s -> hasPerm(s, "vonixguardian.command.restore", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> Restore.run(ctx, g))))
                .then(Commands.literal("purge")
                        .requires(s -> hasPerm(s, "vonixguardian.command.purge", g))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> Purge.run(ctx, g))))
                .then(Commands.literal("undo")
                        .requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                        .executes(ctx -> Undo.run(ctx, g)))
                .then(Commands.literal("status")
                        .requires(s -> hasPerm(s, "vonixguardian.command.status", g))
                        .executes(ctx -> Status.run(ctx, g)))
                .then(Commands.literal("reload")
                        .requires(s -> hasPerm(s, "vonixguardian.command.reload", g))
                        .executes(ctx -> Reload.run(ctx, g)))
                .then(Commands.literal("help").executes(ctx -> Help.run(ctx, g)));

        dispatcher.register(root);
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
            // 1.18.2: sendSuccess(Component, boolean) — no Supplier overload yet.
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

    /**
     * Returns the caller's current world-id when the source is a player, else
     * {@code null}. Used to default {@link QueryFilter#worldSel()} so player-issued
     * lookups stay scoped to the player's current dimension (CoreProtect default)
     * instead of leaking events from every dimension. Console callers get
     * {@code null} → no default → global (intentional).
     */
    private static String playerWorldOf(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? WorldKey.of(p.level) : null;
    }

    private static UUID actorUuid(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
    }

    // ====================================================================== Inspect

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

    public static final class Lookup {
        private Lookup() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            String filter = StringArgumentType.getString(ctx, "filter");
            return runWithFilter(src, g, filter);
        }

        public static void atPos(Guardian g, int x, int y, int z, String worldId, CommandSourceStack src) {
            String f = "r:1 t:30d";
            runWithFilter(src, g, f + " r:#world_" + worldId);
        }

        private static int runWithFilter(CommandSourceStack src, Guardian g, String raw) {
            QueryFilter qf;
            try {
                qf = PARSER.parse(raw, ctxOf(src)).withDefaultWorld(playerWorldOf(src));
            } catch (QueryParseException e) {
                send(src, ChatRenderer.error(g.theme(), "[VonixGuardian] " + e.getMessage()));
                return 0;
            }
            MinecraftServer server = src.getServer();
            final QueryFilter filter = qf;
            WORKER.submit(() -> {
                try {
                    long total = g.dao().count(filter);
                    List<Action> rows = g.dao().query(filter, 0, PAGE_SIZE);
                    long now = System.currentTimeMillis();
                    List<Component> page = LookupFormatter.page(g.theme(), rows, total, 1, PAGE_SIZE, now);
                    server.execute(() -> {
                        for (Component c : page) {
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

    // ====================================================================== Rollback

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
            MinecraftServer server = src.getServer();
            final QueryFilter filter = qf;
            WORKER.submit(() -> {
                try {
                    long n = g.dao().purge(filter);
                    server.execute(() -> send(src, ChatRenderer.warning(g.theme(),
                            "[VonixGuardian] Purge removed " + n + " rows.")));
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
            send(src, ChatRenderer.success(g.theme(),
                    "[VonixGuardian] Undo: dropped " + popped.get().affectedCount() + " entries from history."));
            return 1;
        }
    }

    // ====================================================================== Status

    public static final class Status {
        private Status() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            send(src, ChatRenderer.primary(g.theme(),
                    "[VonixGuardian] submitted=" + g.submitted()
                            + " gated=" + g.gated()
                            + " queue.depth=" + g.queue().depth()
                            + " queue.dropped=" + g.queue().dropped()));
            return 1;
        }
    }

    // ====================================================================== Reload

    public static final class Reload {
        private Reload() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            send(ctx.getSource(), ChatRenderer.warning(g.theme(),
                    "[VonixGuardian] Reload is not implemented yet — restart the server to re-read config."));
            return 1;
        }
    }

    // ====================================================================== Help

    public static final class Help {
        private Help() {}

        public static int run(CommandContext<CommandSourceStack> ctx, Guardian g) {
            CommandSourceStack src = ctx.getSource();
            send(src, ChatRenderer.primary(g.theme(), "[VonixGuardian] Commands:"));
            String[] lines = {
                    "/vg inspect — toggle inspect mode",
                    "/vg lookup <filter> — search audit log",
                    "/vg rollback <filter> — undo logged actions",
                    "/vg restore <filter> — redo rolled-back actions",
                    "/vg purge <filter> — delete log rows",
                    "/vg undo — drop last rollback from history",
                    "/vg status — queue / submission counters",
                    "/vg reload — re-read config (TODO)",
            };
            for (String l : lines) {
                send(src, ChatRenderer.muted(g.theme(), "  " + l));
            }
            return 1;
        }
    }
}
