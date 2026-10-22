/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.perms.LookupPermissionFilter;
import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.perms.PermissionResolver;
import network.vonix.guardian.core.query.QueryFilter;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code /guardian lookup}. Pages are read off the command thread. */
final class LookupCommand extends GuardianSubcommand {

    private static final Pattern PAGE_TOKEN = Pattern.compile("^(\\d+)(?::(\\d+))?$");

    private final RequiredArg<String> filterArg;

    LookupCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.LOOKUP, "Query the audit log with filter tokens.",
                false, runtime, logger, CommandSpec.permissionNode(CommandSpec.LOOKUP), false);
        addAliases("l");
        filterArg = withRequiredArg("filter",
                "Filter tokens. A leading page or page:perPage token selects the page.",
                ArgTypes.GREEDY_STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (!admit(context)) {
            return done();
        }
        String raw = context.get(filterArg);
        if (raw == null) {
            runtime.reply(context, runtime.error("[VonixGuardian] A filter is required."));
            return done();
        }
        Guardian guardian = runtime.guardian();
        if (guardian == null) {
            notRunning(context);
            return done();
        }
        int defaultPerPage;
        try {
            defaultPerPage = runtime.defaultPageSize(guardian);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Lookup page size unavailable", ex);
            runtime.reply(context, runtime.error("[VonixGuardian] Lookup page size is unavailable. Refusing."));
            return done();
        }
        PageSplit split = splitPage(raw, defaultPerPage);
        CommandRuntime.Actor actor = runtime.capture(context);
        if (actor.player() && actor.uuid() == null) {
            runtime.reply(context, runtime.error(
                    "[VonixGuardian] Lookup permission scope is unavailable. Refusing."));
            return done();
        }
        CommandRuntime.Prepared prepared = runtime.prepare(guardian, actor, split.filter, CommandRuntime.QueryKind.LOOKUP);
        if (prepared.failed()) {
            runtime.reply(context, runtime.error("[VonixGuardian] " + prepared.error()));
            return done();
        }
        CommandSender sender = context.sender();
        long generation = runtime.beginChat(actor.uuid());
        runtime.submit(sender, () -> query(guardian, sender, actor, generation,
                prepared.filter(), split.page, split.perPage, split.filter));
        return done();
    }

    private void query(Guardian guardian, CommandSender sender, CommandRuntime.Actor actor,
                       long generation, QueryFilter filter, int page, int perPage, String filterText) {
        PermissionResolver perms;
        try {
            perms = guardian.perms();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Lookup permission resolver unavailable", ex);
            runtime.deliver(sender, actor.uuid(), generation, runtime.error(
                    "[VonixGuardian] Lookup permission filter is unavailable. Refusing."));
            return;
        }
        if (perms == null || guardian.dao() == null) {
            runtime.deliver(sender, actor.uuid(), generation, runtime.error(
                    "[VonixGuardian] Lookup permission filter is unavailable. Refusing."));
            return;
        }
        UUID viewer = actor.player() ? actor.uuid() : null;
        try {
            if (filter.countOnly()) {
                long total = LookupPermissionFilter.countVisible(
                        guardian.dao(), perms, viewer, PermissionNode.LOOKUP, filter);
                runtime.deliver(sender, actor.uuid(), generation,
                        runtime.success("[VonixGuardian] Count: " + total));
                return;
            }
            LookupPermissionFilter.VisiblePage visible = LookupPermissionFilter.visiblePage(
                    guardian.dao(), perms, viewer, PermissionNode.LOOKUP,
                    filter, page, perPage, true);
            if (!visible.complete()) {
                runtime.deliver(sender, actor.uuid(), generation, runtime.error(
                        "[VonixGuardian] Lookup aborted: the permission-filtered page exceeded the 100000-row scan limit. Narrow the time, radius, or action filter and try again."));
                return;
            }
            List<Action> rows = visible.rows();
            if (rows.isEmpty()) {
                if (!filter.silent()) {
                    runtime.deliver(sender, actor.uuid(), generation, runtime.muted(
                            "[VonixGuardian] No results found. Try a wider radius (r:20+) or longer time (t:24h+), or check filter tokens."));
                }
                return;
            }
            long now = System.currentTimeMillis();
            for (String line : LookupText.page(rows, page, perPage, now, filterText, visible.hasNext())) {
                Message message = line.startsWith("[VonixGuardian]") || line.startsWith("  >>")
                        ? runtime.primary(line)
                        : runtime.muted(line);
                runtime.deliver(sender, actor.uuid(), generation, message);
            }
        } catch (Exception ex) {
            logger.warn(Guardian.MARKER, "Lookup failed", ex);
            runtime.deliver(sender, actor.uuid(), generation,
                    runtime.error("[VonixGuardian] Lookup error: " + reason(ex)));
        }
    }

    private static PageSplit splitPage(String raw, int defaultPerPage) {
        int page = 1;
        int perPage = defaultPerPage;
        String filter = raw == null ? "" : raw;
        String trimmed = filter.trim();
        if (!trimmed.isEmpty()) {
            int space = trimmed.indexOf(' ');
            String first = space < 0 ? trimmed : trimmed.substring(0, space);
            Matcher matcher = PAGE_TOKEN.matcher(first);
            if (matcher.matches()) {
                try {
                    page = Math.max(1, Integer.parseInt(matcher.group(1)));
                    if (matcher.group(2) != null) {
                        perPage = Math.max(1, Math.min(50, Integer.parseInt(matcher.group(2))));
                    }
                } catch (NumberFormatException ignored) {
                    // An oversized token keeps the defaults and is still removed.
                }
                filter = space < 0 ? "" : trimmed.substring(space + 1);
            }
        }
        return new PageSplit(page, perPage, filter);
    }

    private record PageSplit(int page, int perPage, String filter) {
    }
}
