/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandOwner;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.AbstractOptionalArg;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.command.CommandChatGuard;
import network.vonix.guardian.core.concurrent.BoundedGenerationExecutor;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParseException;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.theme.Theme;
import network.vonix.guardian.hytale.spi.GuardianServices;
import network.vonix.guardian.hytale.spi.PlayerDirectory;
import network.vonix.guardian.hytale.spi.WorldBlockAccess;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Shared command services: permission gate, sender snapshot, themed messages,
 * and the off-server-thread worker used for database and rollback work.
 *
 * <p>Rollback planning blocks the caller until world mutations finish, so it
 * must not run on the server thread. The worker is that caller. Chat is
 * hopped back through {@link GuardianServices#mainThread()} after the engine
 * returns, which is outside a world mutation.
 */
final class CommandRuntime {

    static final String USE_NODE = "vonixguardian.command.use";
    static final String VIEW_OTHERS_NODE = "vonixguardian.command.viewothers";
    static final int DEFAULT_ROLLBACK_RADIUS = 10;

    private final GuardianServices services;
    private final Logger logger;
    private final QueryParser parser = new QueryParser();
    private final BoundedGenerationExecutor workers =
            new BoundedGenerationExecutor("VonixGuardian-HytaleCmd", 64, 2);

    CommandRuntime(GuardianServices services, Logger logger) {
        this.services = services;
        this.logger = logger;
    }

    Logger logger() {
        return logger;
    }

    QueryParser parser() {
        return parser;
    }

    /**
     * @return a denial sentence, or {@code null} when the sender may proceed
     */
    String gate(CommandContext context, String node, boolean mutating) {
        if (context == null || context.sender() == null) {
            return "No command sender. Refusing.";
        }
        if (!context.isPlayer()) {
            if (mutating) {
                return "Refusing: a mutating command needs a player permission check, "
                        + "and the console sender does not have one.";
            }
            return null;
        }
        PlayerDirectory players;
        try {
            players = services.players();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Player directory unavailable", ex);
            return "Permission check is unavailable. Refusing.";
        }
        if (players == null) {
            return "Permission check is unavailable. Refusing.";
        }
        UUID uuid;
        try {
            uuid = context.sender().getUuid();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Player UUID unavailable", ex);
            return "Permission check is unavailable. Refusing.";
        }
        if (uuid == null) {
            return "Permission check is unavailable. Refusing.";
        }
        String base = pluginBasePermission(context.getCalledCommand());
        if (base == null) {
            return "Plugin base permission was not passed in. Refusing.";
        }
        Boolean baseHeld = holds(players, uuid, base);
        if (baseHeld == null) {
            return "Permission check failed. Refusing.";
        }
        if (!baseHeld) {
            return "You do not have the plugin base permission (" + base + ").";
        }
        if (node == null || node.isBlank()) {
            return "Permission node is missing. Refusing.";
        }
        Boolean nodeHeld = holds(players, uuid, node);
        if (nodeHeld == null) {
            return "Permission check failed. Refusing.";
        }
        if (!nodeHeld) {
            return "You do not have permission " + node + ".";
        }
        return null;
    }

    /**
     * Destructive commands register Hytale's {@code confirm} flag, and the
     * parser will not call {@code execute} without {@code --confirm}. This
     * re-checks the flag and the raw token so a direct call still fails closed.
     */
    boolean confirmed(AbstractCommand command, CommandContext context) {
        AbstractOptionalArg<?, ?> flag = command.getOptionalArgument("confirm");
        if (flag != null && flag.provided(context)) {
            // get() is safe only after provided(): a missing DefaultValueArgument
            // inserts its default and then looks provided.
            Object value = flag.get(context);
            return Boolean.TRUE.equals(value);
        }
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String token : input.trim().split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if ("--confirm".equals(lower) || "--confirm=true".equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes an explicit confirm token from a greedy filter. The token is not
     * a query term. {@link #confirmed} still decides whether the command runs.
     */
    String withoutConfirmToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (String token : raw.trim().split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if ("--confirm".equals(lower) || "--confirm=true".equals(lower)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }

    Actor capture(CommandContext context) {
        CommandSender sender = context.sender();
        boolean player = context.isPlayer();
        UUID uuid = null;
        String name = "";
        try {
            uuid = sender.getUuid();
            String username = sender.getUsername();
            name = username == null ? "" : username;
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Sender identity unavailable", ex);
        }
        Integer x = null;
        Integer y = null;
        Integer z = null;
        String worldId = null;
        boolean worldFailed = false;
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (player && ref != null && ref.isValid()) {
            try {
                PlayerRef playerRef = context.senderAs(PlayerRef.class);
                Transform transform = playerRef.getTransform();
                if (transform != null && transform.getPosition() != null) {
                    Vector3d pos = transform.getPosition();
                    x = (int) Math.floor(pos.x);
                    y = (int) Math.floor(pos.y);
                    z = (int) Math.floor(pos.z);
                }
                UUID worldUuid = playerRef.getWorldUuid();
                WorldBlockAccess worlds = services.world();
                if (worlds != null && worldUuid != null) {
                    worldId = worlds.worldIdOf(worldUuid);
                }
            } catch (RuntimeException ex) {
                worldFailed = true;
                logger.warn(Guardian.MARKER, "Player position or world id unavailable", ex);
            }
        }
        return new Actor(uuid, name, player, x, y, z, worldId, worldFailed);
    }

    Guardian guardian() {
        try {
            if (!services.isRunning()) {
                return null;
            }
            return services.guardian();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Guardian runtime unavailable", ex);
            return null;
        }
    }

    Prepared prepare(Guardian guardian, Actor actor, String raw, QueryKind kind) {
        QueryFilter filter;
        try {
            QueryParser.QueryParseContext ctx = null;
            if (actor.x() != null && actor.y() != null && actor.z() != null) {
                ctx = new QueryParser.QueryParseContext(actor.x(), actor.y(), actor.z(), actor.uuid());
            }
            filter = parser.parse(raw == null ? "" : raw, ctx);
        } catch (QueryParseException ex) {
            return Prepared.error(ex.getMessage());
        }
        if (filter.worldEditPlayer() != null) {
            return Prepared.error("WorldEdit selections (r:#we) are not available on Hytale. Refusing.");
        }
        if (kind == QueryKind.ROLLBACK || kind == QueryKind.RESTORE) {
            filter = withDefaultRadius(filter, actor);
        }
        try {
            int maxRadius = guardian.config().lookup().maxRadius();
            filter = QueryParser.enforceMaxRadius(filter, maxRadius);
        } catch (QueryParseException ex) {
            return Prepared.error(ex.getMessage());
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Lookup radius limit unavailable", ex);
            return Prepared.error("Lookup radius limit is unavailable. Refusing.");
        }
        boolean explicitWorld = filter.worldSel() != null;
        if (actor.player() && actor.worldLookupFailed()
                && !(kind == QueryKind.LOOKUP && explicitWorld)) {
            return Prepared.error("World id lookup failed. Refusing an unscoped query.");
        }
        if (actor.player() && actor.worldId() != null) {
            filter = filter.withDefaultWorld(actor.worldId());
        }
        if (actor.player() && filter.worldSel() == null) {
            return Prepared.error("Your world id is not available, so this query would not be scoped. Refusing.");
        }
        if ((kind == QueryKind.ROLLBACK || kind == QueryKind.RESTORE)
                && actor.player()
                && filter.radius() == null
                && filter.worldEditPlayer() == null
                && (filter.worldSel() == null || !filter.worldSel().global())) {
            return Prepared.error("Rollback needs your position or an explicit r:<radius>. Refusing.");
        }
        String scopeError = scopeToSelf(actor, filter);
        if (scopeError != null) {
            return Prepared.error(scopeError);
        }
        QueryFilter scoped = scopedFilter(actor, filter);
        if (scoped == null) {
            return Prepared.error("Your identity is unavailable, so this query would not be scoped to you. Refusing.");
        }
        return Prepared.ok(scoped);
    }

    int defaultPageSize(Guardian guardian) {
        int size = guardian.config().lookup().defaultPageSize();
        if (size < 1) {
            return 1;
        }
        return Math.min(50, size);
    }

    long beginChat(UUID actor) {
        return CommandChatGuard.next(actor);
    }

    boolean submit(CommandSender sender, Runnable task) {
        try {
            workers.execute(task);
            return true;
        } catch (RejectedExecutionException ex) {
            logger.warn(Guardian.MARKER, "Command worker queue saturated", ex);
            deliverNow(sender, error("Command worker is saturated. Nothing was changed. Retry shortly."));
            return false;
        }
    }

    void reply(CommandContext context, Message message) {
        deliverNow(context.sender(), message);
    }

    void deliver(CommandSender sender, UUID actor, long generation, Message message) {
        hop(() -> {
            if (!CommandChatGuard.isCurrent(actor, generation)) {
                return;
            }
            send(sender, message);
        });
    }

    void deliverNow(CommandSender sender, Message message) {
        send(sender, message);
    }

    Message primary(String text) {
        return styled(text, Tone.PRIMARY, false);
    }

    Message muted(String text) {
        return styled(text, Tone.MUTED, false);
    }

    Message success(String text) {
        return styled(text, Tone.SUCCESS, false);
    }

    Message warning(String text) {
        return styled(text, Tone.WARNING, false);
    }

    Message error(String text) {
        return styled(text, Tone.ERROR, false);
    }

    Message section(String text) {
        return styled(text, Tone.PRIMARY, true);
    }

    private Boolean holds(PlayerDirectory players, UUID uuid, String node) {
        try {
            return players.hasPermission(uuid, node);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Permission check failed for " + node, ex);
            return null;
        }
    }

    private String scopeToSelf(Actor actor, QueryFilter filter) {
        if (!actor.player()) {
            return null;
        }
        Boolean allowed = viewOthers(actor);
        if (allowed == null) {
            return "viewothers check failed. Refusing to widen the query.";
        }
        if (allowed || filter.users().isEmpty()) {
            return null;
        }
        for (QueryFilter.UserSel user : filter.users()) {
            if (!isSelf(actor, user)) {
                return "You need " + VIEW_OTHERS_NODE + " to target another user.";
            }
        }
        return null;
    }

    private QueryFilter scopedFilter(Actor actor, QueryFilter filter) {
        if (!actor.player() || !filter.users().isEmpty()) {
            return filter;
        }
        Boolean allowed = viewOthers(actor);
        if (allowed == null) {
            return null;
        }
        if (allowed) {
            return filter;
        }
        if (actor.uuid() == null || actor.name().isBlank()) {
            return null;
        }
        return new QueryFilter(
                List.of(new QueryFilter.UserSel(actor.uuid(), actor.name(), false)),
                filter.sinceMillis(), filter.untilMillis(), filter.radius(), filter.worldSel(),
                filter.centerX(), filter.centerY(), filter.centerZ(),
                filter.actions(), filter.include(), filter.exclude(),
                filter.rolledBack(), filter.countOnly(), filter.preview(), filter.verbose(),
                filter.silent(), filter.optimize(), filter.worldEditPlayer(), filter.actionIds());
    }

    private Boolean viewOthers(Actor actor) {
        if (actor.uuid() == null) {
            return null;
        }
        PlayerDirectory players;
        try {
            players = services.players();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Player directory unavailable during viewothers", ex);
            return null;
        }
        if (players == null) {
            return null;
        }
        return holds(players, actor.uuid(), VIEW_OTHERS_NODE);
    }

    private static boolean isSelf(Actor actor, QueryFilter.UserSel user) {
        if (user.isSentinel()) {
            return false;
        }
        if (user.uuid() != null && user.uuid().equals(actor.uuid())) {
            return true;
        }
        return user.name() != null && !actor.name().isBlank()
                && user.name().equalsIgnoreCase(actor.name());
    }

    private static QueryFilter withDefaultRadius(QueryFilter filter, Actor actor) {
        if (filter.radius() != null || filter.worldSel() != null || filter.worldEditPlayer() != null) {
            return filter;
        }
        if (actor.x() == null || actor.y() == null || actor.z() == null) {
            return filter;
        }
        return new QueryFilter(
                filter.users(), filter.sinceMillis(), filter.untilMillis(),
                DEFAULT_ROLLBACK_RADIUS,
                filter.worldSel(),
                filter.centerX() != null ? filter.centerX() : actor.x(),
                filter.centerY() != null ? filter.centerY() : actor.y(),
                filter.centerZ() != null ? filter.centerZ() : actor.z(),
                filter.actions(), filter.include(), filter.exclude(),
                filter.rolledBack(), filter.countOnly(), filter.preview(), filter.verbose(),
                filter.silent(), filter.optimize(), filter.worldEditPlayer(), filter.actionIds());
    }

    private String pluginBasePermission(AbstractCommand command) {
        if (command == null) {
            return null;
        }
        CommandOwner owner = command.getOwner();
        if (!(owner instanceof PluginBase plugin)) {
            return null;
        }
        try {
            String base = plugin.getBasePermission();
            if (base == null || base.isBlank()) {
                return null;
            }
            return base;
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Plugin base permission unavailable", ex);
            return null;
        }
    }

    private Message styled(String text, Tone tone, boolean bold) {
        Message message = Message.raw(text == null ? "" : text);
        if (bold) {
            message = message.bold(true);
        }
        String hex = colorHex(tone);
        if (hex == null) {
            return message;
        }
        try {
            return message.color(Color.decode(hex));
        } catch (RuntimeException ex) {
            return message;
        }
    }

    private String colorHex(Tone tone) {
        Theme theme = theme();
        if (theme == null) {
            return null;
        }
        return switch (tone) {
            case PRIMARY -> theme.primary();
            case SECONDARY -> theme.secondary();
            case SUCCESS -> theme.success();
            case ERROR -> theme.error();
            case WARNING -> theme.warning();
            case MUTED -> theme.muted();
        };
    }

    private Theme theme() {
        try {
            Guardian guardian = services.isRunning() ? services.guardian() : null;
            return guardian == null ? null : guardian.theme();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void hop(Runnable task) {
        Executor main;
        try {
            main = services.mainThread();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Server thread executor unavailable", ex);
            task.run();
            return;
        }
        if (main == null) {
            task.run();
            return;
        }
        try {
            main.execute(task);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Failed to hop command output to the server thread", ex);
            task.run();
        }
    }

    private void send(CommandSender sender, Message message) {
        if (sender == null || message == null) {
            return;
        }
        try {
            sender.sendMessage(message);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Failed to send command message", ex);
        }
    }

    enum Tone {
        PRIMARY, SECONDARY, SUCCESS, ERROR, WARNING, MUTED
    }

    enum QueryKind {
        LOOKUP, ROLLBACK, RESTORE, PURGE
    }

    record Actor(UUID uuid, String name, boolean player,
                 Integer x, Integer y, Integer z,
                 String worldId, boolean worldLookupFailed) {
    }

    record Prepared(QueryFilter filter, String error) {
        static Prepared ok(QueryFilter filter) {
            return new Prepared(filter, null);
        }

        static Prepared error(String error) {
            return new Prepared(null, error);
        }

        boolean failed() {
            return error != null;
        }
    }
}
