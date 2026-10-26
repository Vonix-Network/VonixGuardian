/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.command.MutationOutcomeFormatter;
import network.vonix.guardian.core.diagnostics.GuardianStatus;
import network.vonix.guardian.core.perms.LookupPermissionFilter;
import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.perms.PermissionResolver;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.rollback.RollbackCancelledException;
import network.vonix.guardian.core.rollback.RollbackEngine;
import network.vonix.guardian.core.rollback.RollbackLimitExceededException;
import network.vonix.guardian.core.rollback.RollbackOptions;
import network.vonix.guardian.core.rollback.RollbackPlan;
import network.vonix.guardian.core.rollback.RollbackResult;
import network.vonix.guardian.hytale.world.WriteVerifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Engine work for the menu. Callers run these methods on the menu worker,
 * never on the world thread. World mutation itself is inside
 * {@code RollbackEngine.execute}, which hops through the engine executor.
 * After a real world change this class calls {@link WriteVerifier#converge}
 * with {@code GuardianServices.mainThread()}.
 */
final class MenuOps {

    private final MenuHost host;

    MenuOps(MenuHost host) {
        this.host = host;
    }

    List<String> status(MenuHost.Actor actor) {
        String denial = host.gate(actor.uuid(), CommandSpec.permissionNode(CommandSpec.STATUS));
        if (denial != null) {
            return List.of(denial);
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            return List.of("Guardian is not running.");
        }
        List<String> lines = new ArrayList<>();
        try {
            lines.addAll(GuardianStatus.render(guardian));
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Status render failed", ex);
            return List.of("Status failed: " + reason(ex));
        }
        lines.add(lastRollback(guardian, actor.uuid()));
        if (lines.isEmpty()) {
            return List.of("Status returned no lines.");
        }
        return List.copyOf(lines);
    }

    List<String> lookup(MenuHost.Actor actor, QueryFilter filter, int page, int perPage) {
        String denial = host.gate(actor.uuid(), CommandSpec.permissionNode(CommandSpec.LOOKUP));
        if (denial != null) {
            return List.of(denial);
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            return List.of("Guardian is not running.");
        }
        PermissionResolver perms;
        try {
            perms = guardian.perms();
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Lookup permission resolver unavailable", ex);
            return List.of("Lookup permission filter is unavailable. Refusing.");
        }
        if (perms == null || guardian.dao() == null) {
            return List.of("Lookup permission filter is unavailable. Refusing.");
        }
        try {
            if (filter.countOnly()) {
                long total = LookupPermissionFilter.countVisible(
                        guardian.dao(), perms, actor.uuid(), PermissionNode.LOOKUP, filter);
                return List.of("Count: " + total);
            }
            LookupPermissionFilter.VisiblePage visible = LookupPermissionFilter.visiblePage(
                    guardian.dao(), perms, actor.uuid(), PermissionNode.LOOKUP,
                    filter, page, perPage, true);
            if (!visible.complete()) {
                return List.of("Lookup aborted: the permission-filtered page exceeded the 100000-row scan limit. Narrow the time, radius, or action filter and try again.");
            }
            List<Action> rows = visible.rows();
            if (rows.isEmpty()) {
                if (filter.silent()) {
                    return List.of();
                }
                return List.of("No results found. Try a wider radius (r:20+) or longer time (t:24h+), or check filter tokens.");
            }
            List<String> lines = new ArrayList<>(rows.size() + 1);
            lines.add("Page " + page + (visible.hasNext() ? " (more)" : ""));
            for (Action action : rows) {
                lines.add(formatAction(action));
            }
            return List.copyOf(lines);
        } catch (Exception ex) {
            host.logger().warn(Guardian.MARKER, "Lookup failed", ex);
            return List.of("Lookup error: " + reason(ex));
        }
    }

    boolean lookupHasNext(List<String> lines) {
        return !lines.isEmpty() && lines.get(0).startsWith("Page ") && lines.get(0).endsWith("(more)");
    }

    List<String> mutate(MenuHost.Actor actor, QueryFilter filter, RollbackResult.Mode mode, String verb) {
        String node = mode == RollbackResult.Mode.RESTORE
                ? CommandSpec.permissionNode(CommandSpec.RESTORE)
                : CommandSpec.permissionNode(CommandSpec.ROLLBACK);
        String denial = host.gate(actor.uuid(), node);
        if (denial != null) {
            return List.of(denial + " Nothing was changed.");
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            return List.of("Guardian is not running. Nothing was changed.");
        }
        RollbackEngine engine = guardian.rollbackEngine();
        RollbackPlan plan;
        try {
            plan = engine.plan(filter, mode, actor.uuid(), RollbackOptions.defaults());
        } catch (IllegalArgumentException | RollbackLimitExceededException | RollbackCancelledException ex) {
            return List.of(verb + " refused: " + reason(ex) + " Nothing was changed.");
        } catch (Exception ex) {
            host.logger().warn(Guardian.MARKER, verb + " planning failed", ex);
            return List.of(verb + " planning failed: " + reason(ex) + " Nothing was changed.");
        }
        if (filter.countOnly()) {
            RollbackResult counted = new RollbackResult(actor.uuid(), mode, true,
                    plan.actionIds(), plan.skippedIds(), plan.plannedSteps(), 0, filter);
            return MutationOutcomeFormatter.lines(verb, counted, filter);
        }
        boolean preview = filter.preview();
        RollbackResult result;
        try {
            result = engine.execute(plan, preview);
        } catch (Exception ex) {
            host.logger().warn(Guardian.MARKER, verb + " execution failed", ex);
            return List.of(verb + " failed without a result. The world may have changed. Nothing was added to undo.");
        }
        host.logger().info(Guardian.MARKER,
                "{} result: preview={} success={} mutatedWorld={} affected={} applied={} skipped={} filter={}",
                verb, result.preview(), result.isSuccess(), result.mutatedWorld(),
                result.affectedCount(), result.appliedCount(), result.skippedCount(), filter);
        List<String> lines = new ArrayList<>(MutationOutcomeFormatter.lines(verb, result, filter));
        if (!result.preview() && result.mutatedWorld()) {
            try {
                var mainThread = host.services().mainThread();
                if (mainThread == null) {
                    lines.add("Post-" + verb + " convergence was skipped: the server thread is unavailable.");
                } else {
                    WriteVerifier.Report verification = WriteVerifier.converge(
                            host.services().world(), mainThread);
                    host.logger().info(Guardian.MARKER, "{} verification: {}", verb, verification.summary());
                    if (!verification.clean()) {
                        lines.add("Post-" + verb + " convergence: " + verification.summary());
                    }
                }
            } catch (RuntimeException ex) {
                host.logger().warn(Guardian.MARKER, verb + " convergence failed", ex);
                lines.add("Post-" + verb + " convergence failed: " + reason(ex));
            }
            try {
                guardian.undoStack().push(actor.uuid(), result);
            } catch (RuntimeException ex) {
                host.logger().warn(Guardian.MARKER, "Undo history was not updated after " + verb, ex);
                lines.add("The world change was applied, but undo history was not updated.");
            }
        }
        return List.copyOf(lines);
    }

    /**
     * {@code previous} is the instance peeked on the world thread.
     * {@code popIfSame} uses identity, so the worker must not peek again.
     */
    List<String> undo(MenuHost.Actor actor, RollbackResult previous, QueryFilter filter, RollbackResult.Mode inverse) {
        String denial = host.gate(actor.uuid(), CommandSpec.permissionNode(CommandSpec.UNDO));
        if (denial != null) {
            return List.of(denial + " Nothing was changed.");
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            return List.of("Guardian is not running. Nothing was changed.");
        }
        RollbackEngine engine = guardian.rollbackEngine();
        RollbackPlan plan;
        try {
            plan = engine.plan(filter, inverse, actor.uuid(), RollbackOptions.defaults());
        } catch (IllegalArgumentException | RollbackLimitExceededException | RollbackCancelledException ex) {
            return List.of("Undo refused: " + reason(ex) + " Nothing was changed.");
        } catch (Exception ex) {
            host.logger().warn(Guardian.MARKER, "Undo planning failed", ex);
            return List.of("Undo planning failed: " + reason(ex) + " Nothing was changed.");
        }
        RollbackResult result;
        try {
            result = engine.execute(plan, false);
        } catch (Exception ex) {
            host.logger().warn(Guardian.MARKER, "Undo execution failed", ex);
            return List.of("Undo failed without a result. The world may have changed. The undo entry was kept.");
        }
        if (result.isSuccess() || result.mutatedWorld()) {
            guardian.undoStack().popIfSame(actor.uuid(), previous);
        }
        return MutationOutcomeFormatter.lines("Undo (" + inverse + ")", result, filter);
    }

    List<String> purge(MenuHost.Actor actor, QueryFilter filter, long minAge) {
        String denial = host.gate(actor.uuid(), CommandSpec.permissionNode(CommandSpec.PURGE));
        if (denial != null) {
            return List.of(denial + " Nothing was deleted.");
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            return List.of("Guardian is not running. Nothing was deleted.");
        }
        try {
            PurgeEngine.PurgeResult result = guardian.purgeEngine().purge(filter, minAge);
            StringBuilder text = new StringBuilder(160);
            text.append("Purge removed ").append(result.deletedCount())
                    .append(" rows (minAge=").append(result.minAgeSeconds()).append("s).");
            if (filter.optimize()) {
                if (result.optimized()) {
                    text.append(" Optimize returned a result. Its completion flag is not visible to this package, so it is not reported as finished.");
                } else {
                    text.append(" Optimize did not return a result.");
                }
            }
            return List.of(text.toString());
        } catch (IllegalArgumentException ex) {
            return List.of("Purge refused: " + reason(ex) + " Nothing was deleted.");
        } catch (Exception ex) {
            host.logger().warn(Guardian.MARKER, "Purge failed", ex);
            return List.of("Purge failed: " + reason(ex) + " Deleted count is unknown.");
        }
    }

    static QueryFilter idFilter(QueryFilter base, List<Long> ids) {
        return new QueryFilter(
                base.users(), base.sinceMillis(), base.untilMillis(),
                base.radius(), base.worldSel(),
                base.centerX(), base.centerY(), base.centerZ(),
                base.actions(), base.include(), base.exclude(),
                base.rolledBack(),
                false, false, base.verbose(), base.silent(), false,
                null,
                new LinkedHashSet<>(ids));
    }

    private static String lastRollback(Guardian guardian, UUID actor) {
        try {
            var peeked = guardian.undoStack().peek(actor);
            if (peeked.isEmpty()) {
                return "Last rollback: none";
            }
            RollbackResult result = peeked.get();
            return "Last rollback: " + result.mode()
                    + " affected=" + result.affectedCount()
                    + " applied=" + result.appliedCount()
                    + " status=" + result.status()
                    + (result.preview() ? " (preview)" : "");
        } catch (RuntimeException ex) {
            return "Last rollback: unavailable";
        }
    }

    private static String formatAction(Action action) {
        String type = action.type() == null ? "?" : action.type().name();
        String who = action.actorName() == null || action.actorName().isBlank() ? "?" : action.actorName();
        String target = action.targetId() == null ? "" : action.targetId();
        StringBuilder line = new StringBuilder(96);
        line.append(type).append(' ').append(who);
        if (!target.isEmpty()) {
            line.append(' ').append(target);
        }
        if (action.isPositional()) {
            line.append(" @ ").append(action.x()).append(' ').append(action.y()).append(' ').append(action.z());
        }
        if (action.rolledBack()) {
            line.append(" (rolled back)");
        }
        return line.toString();
    }

    private static String reason(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }
}
