/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackResult;
import network.vonix.guardian.hytale.command.InspectMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Interactive menu drawn on the client document {@code Pages/CommandListPage.ui}.
 * Buttons are {@code Pages/BasicTextButton.ui} rows inside {@code #CommandList}.
 *
 * <p>{@code handleDataEvent} is treated as the world thread because it receives
 * the entity ref and store. It does not block on the engine. Database and
 * rollback work runs on {@link MenuHost}'s worker. The worker never calls
 * {@code sendUpdate}: that method reads the entity store on the caller, so
 * the result is hopped through the player's {@link World#execute} first.
 * {@code sendUpdate}'s boolean is {@code CustomPage.clear}. A screen change
 * passes {@code true} and re-appends the document.
 *
 * <p>The superclass keeps {@code playerRef}. This page does not copy it into
 * another field, and {@code onDismiss} drops every other bit of page state.
 * There is no static page map.
 */
public final class MenuPage extends InteractiveCustomUIPage<MenuEvent> {

    private static final String DOCUMENT = "Pages/CommandListPage.ui";
    private static final String BUTTON = "Pages/BasicTextButton.ui";
    private static final String LIST = "#CommandList";
    private static final int STATUS_ROWS = 8;

    private static final int[] RADII = {5, 10, 20, 50};
    private static final String[] ROLLBACK_TIMES = {"1h", "6h", "24h", "7d"};
    private static final String[] PURGE_TIMES = {"30d", "60d", "90d"};

    private final MenuHost host;
    private final MenuOps ops;

    private Screen screen = Screen.HOME;
    private String search = "";
    private int radius = MenuQuery.DEFAULT_ROLLBACK_RADIUS;
    private String window = "1h";
    private String purgeWindow = "30d";
    private int reportPage = 1;
    private boolean reportHasNext;
    private List<String> report = List.of();
    private List<String> notice = List.of();
    private String previewKey;
    private String lookupFilter = "";
    private int lookupPage = 1;
    private int lookupPerPage = MenuQuery.UI_PAGE_CAP;
    private boolean busy;
    private volatile boolean open = true;
    private int job;

    public MenuPage(PlayerRef playerRef, MenuHost host) {
        super(playerRef, CustomPageLifetime.CanDismiss, MenuEvent.CODEC);
        if (host == null) {
            throw new IllegalArgumentException("host is required");
        }
        this.host = host;
        this.ops = new MenuOps(host);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        paint(commands, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, MenuEvent data) {
        if (!open || data == null) {
            return;
        }
        World world = worldOf(store);
        if (data.searchQuery() != null && !data.searchQuery().equals(search)) {
            search = data.searchQuery();
            previewKey = null;
        }
        String action = data.action();
        if (action == null || action.isBlank()) {
            return;
        }
        MenuHost.Actor actor = host.capture(playerRef);
        switch (action) {
            case "close" -> close();
            case "back", "home" -> show(Screen.HOME);
            case "status" -> openStatus(world, actor);
            case "status-refresh" -> loadStatus(world, actor);
            case "status-prev" -> turnReport(-1);
            case "status-next" -> turnReport(1);
            case "lookup" -> show(Screen.LOOKUP);
            case "lookup-search" -> searchLookup(world, actor, true);
            case "lookup-prev" -> searchLookup(world, actor, false, -1);
            case "lookup-next" -> searchLookup(world, actor, false, 1);
            case "inspect" -> show(Screen.INSPECT);
            case "inspect-toggle" -> toggleInspect(actor);
            case "rollback" -> openMutation(Screen.ROLLBACK);
            case "restore" -> openMutation(Screen.RESTORE);
            case "rollback-preview" -> preview(world, actor, false);
            case "restore-preview" -> preview(world, actor, true);
            case "rollback-confirm" -> confirmMutation(world, actor, false);
            case "restore-confirm" -> confirmMutation(world, actor, true);
            case "undo" -> show(Screen.UNDO);
            case "undo-confirm" -> confirmUndo(world, actor);
            case "purge" -> show(Screen.PURGE);
            case "purge-confirm" -> confirmPurge(world, actor);
            default -> pickToken(action);
        }
    }

    /**
     * Radius and time are part of the action name. The event codec only
     * decodes Action and SearchQuery, so a separate value field would be dropped.
     */
    private void pickToken(String action) {
        if (action.startsWith("radius-")) {
            pickRadius(action.substring("radius-".length()));
            return;
        }
        if (action.startsWith("window-")) {
            pickWindow(action.substring("window-".length()));
            return;
        }
        if (action.startsWith("purge-")) {
            pickPurgeWindow(action.substring("purge-".length()));
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        retire();
        super.onDismiss(ref, store);
    }

    @Override
    protected void close() {
        retire();
        super.close();
    }

    private void retire() {
        open = false;
        job++;
        busy = false;
        report = List.of();
        notice = List.of();
        previewKey = null;
        search = "";
        lookupFilter = "";
    }

    private void show(Screen next) {
        screen = next;
        notice = List.of();
        if (next != Screen.STATUS && next != Screen.LOOKUP) {
            report = List.of();
            reportPage = 1;
            reportHasNext = false;
        }
        publish();
    }

    private void openStatus(World world, MenuHost.Actor actor) {
        screen = Screen.STATUS;
        report = List.of();
        reportPage = 1;
        notice = List.of("Loading status...");
        publish();
        loadStatus(world, actor);
    }

    private void loadStatus(World world, MenuHost.Actor actor) {
        if (screen != Screen.STATUS) {
            screen = Screen.STATUS;
        }
        submit(world, actor, CommandSpec.permissionNode(CommandSpec.STATUS),
                () -> ops.status(actor),
                lines -> {
                    report = lines;
                    reportPage = 1;
                    reportHasNext = false;
                    notice = screen == Screen.STATUS || lines.isEmpty()
                            ? List.of()
                            : List.of(lines.get(0));
                });
    }

    private void turnReport(int delta) {
        int pages = Math.max(1, (report.size() + STATUS_ROWS - 1) / STATUS_ROWS);
        reportPage = Math.max(1, Math.min(pages, reportPage + delta));
        publish();
    }

    private void searchLookup(World world, MenuHost.Actor actor, boolean parseBox) {
        searchLookup(world, actor, parseBox, 0);
    }

    private void searchLookup(World world, MenuHost.Actor actor, boolean parseBox, int delta) {
        screen = Screen.LOOKUP;
        Guardian guardian = host.guardian();
        if (guardian == null) {
            notice = List.of("Guardian is not running.");
            publish();
            return;
        }
        int perPage;
        try {
            perPage = host.query().pageSize(guardian);
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Lookup page size unavailable", ex);
            notice = List.of("Lookup page size is unavailable. Refusing.");
            publish();
            return;
        }
        if (parseBox) {
            MenuQuery.PageSplit split = MenuQuery.splitPage(search, perPage);
            lookupFilter = split.filter();
            lookupPage = split.page();
            lookupPerPage = split.perPage();
        } else {
            lookupPage = Math.max(1, lookupPage + delta);
            lookupPerPage = perPage;
        }
        MenuHost.Actor snapshot = actor;
        String raw = lookupFilter;
        int page = lookupPage;
        int size = lookupPerPage;
        MenuQuery.Prepared prepared = host.query().prepare(
                guardian, host, snapshot, raw, MenuQuery.Kind.LOOKUP, false);
        if (prepared.failed()) {
            notice = List.of(prepared.error());
            publish();
            return;
        }
        notice = List.of("Searching...");
        publish();
        submit(world, snapshot, CommandSpec.permissionNode(CommandSpec.LOOKUP),
                () -> ops.lookup(snapshot, prepared.filter(), page, size),
                lines -> {
                    report = lines;
                    reportHasNext = ops.lookupHasNext(lines);
                    reportPage = 1;
                    notice = screen == Screen.LOOKUP || lines.isEmpty()
                            ? List.of()
                            : List.of(lines.get(0));
                });
    }

    private void toggleInspect(MenuHost.Actor actor) {
        screen = Screen.INSPECT;
        String denial = host.gate(actor.uuid(), CommandSpec.permissionNode(CommandSpec.INSPECT));
        if (denial != null) {
            notice = List.of(denial);
            publish();
            return;
        }
        if (actor.uuid() == null) {
            notice = List.of("Permission check is unavailable. Refusing.");
            publish();
            return;
        }
        boolean enabled = InspectMode.toggle(actor.uuid());
        notice = List.of(enabled
                ? "Inspect mode enabled. Click lookup is not wired on this server yet."
                : "Inspect mode disabled.");
        publish();
    }

    private void openMutation(Screen next) {
        screen = next;
        previewKey = null;
        notice = List.of();
        publish();
    }

    private void pickRadius(String token) {
        int parsed;
        try {
            parsed = Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            return;
        }
        for (int value : RADII) {
            if (value == parsed) {
                radius = parsed;
                previewKey = null;
                publish();
                return;
            }
        }
    }

    private void pickWindow(String token) {
        if (!known(ROLLBACK_TIMES, token)) {
            return;
        }
        window = token;
        previewKey = null;
        publish();
    }

    private void pickPurgeWindow(String token) {
        if (!known(PURGE_TIMES, token)) {
            return;
        }
        purgeWindow = token;
        publish();
    }

    private static boolean known(String[] allowed, String token) {
        if (token == null) {
            return false;
        }
        for (String value : allowed) {
            if (value.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private void preview(World world, MenuHost.Actor actor, boolean restore) {
        screen = restore ? Screen.RESTORE : Screen.ROLLBACK;
        String key = previewIdentity(restore);
        Guardian guardian = host.guardian();
        if (guardian == null) {
            notice = List.of("Guardian is not running. Nothing was changed.");
            publish();
            return;
        }
        MenuQuery.Kind kind = restore ? MenuQuery.Kind.RESTORE : MenuQuery.Kind.ROLLBACK;
        MenuQuery.Prepared prepared = host.query().prepare(guardian, host, actor, filterKey(), kind, true);
        if (prepared.failed()) {
            previewKey = null;
            notice = List.of(prepared.error() + " Nothing was changed.");
            publish();
            return;
        }
        String verb = restore ? "Restore" : "Rollback";
        RollbackResult.Mode mode = restore ? RollbackResult.Mode.RESTORE : RollbackResult.Mode.ROLLBACK;
        String node = CommandSpec.permissionNode(restore ? CommandSpec.RESTORE : CommandSpec.ROLLBACK);
        notice = List.of("Preview running...");
        publish();
        submit(world, actor, node,
                () -> ops.mutate(actor, prepared.filter(), mode, verb),
                lines -> {
                    if (key.equals(previewIdentity(restore))) {
                        previewKey = key;
                    } else {
                        previewKey = null;
                    }
                    notice = lines.isEmpty() ? List.of(verb + " preview returned no lines.") : lines;
                });
    }

    private void confirmMutation(World world, MenuHost.Actor actor, boolean restore) {
        screen = restore ? Screen.RESTORE : Screen.ROLLBACK;
        String key = previewIdentity(restore);
        if (previewKey == null || !previewKey.equals(key)) {
            notice = List.of("Preview the current radius, time, and filter before confirm. Nothing was changed.");
            publish();
            return;
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            notice = List.of("Guardian is not running. Nothing was changed.");
            publish();
            return;
        }
        MenuQuery.Kind kind = restore ? MenuQuery.Kind.RESTORE : MenuQuery.Kind.ROLLBACK;
        MenuQuery.Prepared prepared = host.query().prepare(guardian, host, actor, filterKey(), kind, false);
        if (prepared.failed()) {
            notice = List.of(prepared.error() + " Nothing was changed.");
            publish();
            return;
        }
        if (prepared.filter().preview() || prepared.filter().countOnly()) {
            notice = List.of("Confirm does not apply a preview or a count. Nothing was changed.");
            publish();
            return;
        }
        String verb = restore ? "Restore" : "Rollback";
        RollbackResult.Mode mode = restore ? RollbackResult.Mode.RESTORE : RollbackResult.Mode.ROLLBACK;
        String node = CommandSpec.permissionNode(restore ? CommandSpec.RESTORE : CommandSpec.ROLLBACK);
        notice = List.of(verb + " running...");
        publish();
        submit(world, actor, node,
                () -> ops.mutate(actor, prepared.filter(), mode, verb),
                lines -> {
                    previewKey = null;
                    notice = lines.isEmpty() ? List.of(verb + " returned no lines.") : lines;
                });
    }

    private void confirmUndo(World world, MenuHost.Actor actor) {
        screen = Screen.UNDO;
        String denial = host.gate(actor.uuid(), CommandSpec.permissionNode(CommandSpec.UNDO));
        if (denial != null) {
            notice = List.of(denial + " Nothing was changed.");
            publish();
            return;
        }
        Guardian guardian = host.guardian();
        if (guardian == null) {
            notice = List.of("Guardian is not running. Nothing was changed.");
            publish();
            return;
        }
        var peeked = guardian.undoStack().peek(actor.uuid());
        if (peeked.isEmpty()) {
            notice = List.of("Nothing to undo.");
            publish();
            return;
        }
        RollbackResult previous = peeked.get();
        if (previous.originalFilter() == null || previous.affectedIds().isEmpty()) {
            guardian.undoStack().popIfSame(actor.uuid(), previous);
            host.logger().info(Guardian.MARKER,
                    "Undo dropped a legacy or empty history entry; affectedCount={}",
                    previous.affectedCount());
            notice = List.of("Undo: dropped " + previous.affectedCount()
                    + " entries from history (legacy or empty entry — world state not reverted).");
            publish();
            return;
        }
        QueryFilter exact = MenuOps.idFilter(previous.originalFilter(), previous.affectedIds());
        RollbackResult.Mode inverse = previous.inverseMode();
        notice = List.of("Undo running...");
        publish();
        submit(world, actor, CommandSpec.permissionNode(CommandSpec.UNDO),
                () -> ops.undo(actor, previous, exact, inverse),
                lines -> notice = lines.isEmpty() ? List.of("Undo returned no lines.") : lines);
    }

    private void confirmPurge(World world, MenuHost.Actor actor) {
        screen = Screen.PURGE;
        Guardian guardian = host.guardian();
        if (guardian == null) {
            notice = List.of("Guardian is not running. Nothing was deleted.");
            publish();
            return;
        }
        long minAge;
        try {
            minAge = guardian.config().purge().minAgeSecondsInGame();
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Purge age floor unavailable", ex);
            notice = List.of("Purge age floor is unavailable. Nothing was deleted.");
            publish();
            return;
        }
        String raw = purgeKey();
        MenuQuery.Prepared prepared = host.query().prepare(guardian, host, actor, raw, MenuQuery.Kind.PURGE, false);
        if (prepared.failed()) {
            notice = List.of(prepared.error() + " Nothing was deleted.");
            publish();
            return;
        }
        notice = List.of("Purge running...");
        publish();
        submit(world, actor, CommandSpec.permissionNode(CommandSpec.PURGE),
                () -> ops.purge(actor, prepared.filter(), minAge),
                lines -> notice = lines.isEmpty() ? List.of("Purge returned no lines.") : lines);
    }

    private void submit(World world, MenuHost.Actor actor, String node,
                        Supplier<List<String>> work, Consumer<List<String>> apply) {
        if (!open) {
            return;
        }
        String denial = host.gate(actor.uuid(), node);
        if (denial != null) {
            notice = List.of(denial);
            publish();
            return;
        }
        if (busy) {
            notice = List.of("A menu request is still running.");
            publish();
            return;
        }
        if (world == null) {
            notice = List.of("Player world is unavailable. Nothing was changed.");
            publish();
            return;
        }
        busy = true;
        int jobId = ++job;
        boolean accepted = host.execute(() -> finish(world, jobId, work, apply));
        if (!accepted) {
            busy = false;
            if (job == jobId) {
                job++;
            }
            notice = List.of("Menu worker is saturated. Nothing was changed. Retry shortly.");
            publish();
        }
    }

    private void finish(World world, int jobId, Supplier<List<String>> work, Consumer<List<String>> apply) {
        List<String> lines;
        try {
            lines = work.get();
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Menu action failed", ex);
            lines = List.of("Menu action failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        List<String> painted = lines == null ? List.of() : lines;
        host.hop(world, () -> {
            if (!open || jobId != job) {
                return;
            }
            busy = false;
            apply.accept(painted);
            publish();
        });
    }

    private void publish() {
        if (!open) {
            return;
        }
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        paint(commands, events);
        try {
            sendUpdate(commands, events, true);
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Menu update failed", ex);
        }
    }

    private void paint(UICommandBuilder commands, UIEventBuilder events) {
        MenuHost.Actor actor = host.capture(playerRef);
        commands.append(DOCUMENT);
        commands.set("#CommandName.TextSpans", Message.raw("VonixGuardian"));
        commands.set("#CommandDescription.TextSpans", Message.raw(description()));
        commands.set("#CommandUsageLabel.TextSpans", Message.raw(usage()));
        commands.set("#BackButton.Visible", screen != Screen.HOME);
        commands.set("#SendToChatButton.Visible", false);
        commands.set("#VariantsSection.Visible", false);
        commands.set("#SubcommandSection.Visible", false);
        commands.set("#AliasesSection.Visible", false);
        commands.set("#PermissionSection.Visible", false);
        commands.set("#ParametersSection.Visible", false);
        commands.set("#ArgumentTypesSection.Visible", false);
        commands.set("#SearchInput.Value", search == null ? "" : search);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                EventData.of("@" + MenuEvent.SEARCH_QUERY, "#SearchInput.Value"), false);
        if (screen != Screen.HOME) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                    event("back"));
        }
        commands.clear(LIST);
        List<Row> rows = rows(actor);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            commands.append(LIST, BUTTON);
            commands.set(LIST + "[" + i + "].TextSpans", Message.raw(row.label()));
            commands.set(LIST + "[" + i + "].Style", Value.ref(BUTTON,
                    row.selected() ? "SelectedLabelStyle" : "LabelStyle"));
            if (row.action() != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, LIST + "[" + i + "]",
                        event(row.action()));
            }
        }
    }

    private List<Row> rows(MenuHost.Actor actor) {
        List<Row> rows = new ArrayList<>();
        for (String line : notice) {
            if (line != null && !line.isBlank()) {
                rows.add(Row.text(trim(line)));
            }
        }
        switch (screen) {
            case HOME -> home(rows);
            case STATUS -> status(rows, actor);
            case LOOKUP -> lookup(rows, actor);
            case INSPECT -> inspect(rows, actor);
            case ROLLBACK -> mutation(rows, actor, false);
            case RESTORE -> mutation(rows, actor, true);
            case UNDO -> undo(rows, actor);
            case PURGE -> purge(rows, actor);
        }
        rows.add(Row.of("Close", "close"));
        return rows;
    }

    private void home(List<Row> rows) {
        rows.add(Row.of("Status", "status"));
        rows.add(Row.of("Lookup", "lookup"));
        rows.add(Row.of("Inspect", "inspect"));
        rows.add(Row.of("Rollback", "rollback"));
        rows.add(Row.of("Restore", "restore"));
        rows.add(Row.of("Undo", "undo"));
        rows.add(Row.of("Purge", "purge"));
    }

    private void status(List<Row> rows, MenuHost.Actor actor) {
        boolean allowed = host.allows(actor.uuid(), CommandSpec.permissionNode(CommandSpec.STATUS));
        rows.add(actionRow("Refresh", "status-refresh", allowed));
        appendReportPage(rows);
        int pages = Math.max(1, (report.size() + STATUS_ROWS - 1) / STATUS_ROWS);
        if (reportPage > 1) {
            rows.add(Row.of("Previous page", "status-prev"));
        }
        if (reportPage < pages) {
            rows.add(Row.of("Next page", "status-next"));
        }
    }

    private void lookup(List<Row> rows, MenuHost.Actor actor) {
        boolean allowed = host.allows(actor.uuid(), CommandSpec.permissionNode(CommandSpec.LOOKUP));
        rows.add(actionRow("Search", "lookup-search", allowed));
        for (String line : report) {
            rows.add(Row.text(trim(line)));
        }
        if (lookupPage > 1) {
            rows.add(actionRow("Previous page", "lookup-prev", allowed));
        }
        if (reportHasNext) {
            rows.add(actionRow("Next page", "lookup-next", allowed));
        }
    }

    private void inspect(List<Row> rows, MenuHost.Actor actor) {
        boolean allowed = host.allows(actor.uuid(), CommandSpec.permissionNode(CommandSpec.INSPECT));
        boolean on = actor.uuid() != null && InspectMode.isInspecting(actor.uuid());
        rows.add(Row.text(on ? "Inspect mode is on." : "Inspect mode is off."));
        rows.add(Row.text("Click lookup is not wired on this server yet."));
        rows.add(actionRow(on ? "Disable inspect" : "Enable inspect", "inspect-toggle", allowed));
    }

    private void mutation(List<Row> rows, MenuHost.Actor actor, boolean restore) {
        String node = CommandSpec.permissionNode(restore ? CommandSpec.RESTORE : CommandSpec.ROLLBACK);
        boolean allowed = host.allows(actor.uuid(), node);
        String key = filterKey();
        boolean ready = allowed && previewKey != null && previewKey.equals(previewIdentity(restore));
        String verb = restore ? "restore" : "rollback";
        rows.add(Row.text("Filter " + key));
        for (int value : RADII) {
            rows.add(new Row("Radius " + value, "radius-" + value, value == radius));
        }
        for (String time : ROLLBACK_TIMES) {
            rows.add(new Row("Time " + time, "window-" + time, time.equals(window)));
        }
        rows.add(actionRow("Preview", restore ? "restore-preview" : "rollback-preview", allowed));
        if (!allowed) {
            rows.add(Row.text("Confirm " + verb + " (locked)"));
        } else if (ready) {
            rows.add(Row.of("Confirm " + verb, restore ? "restore-confirm" : "rollback-confirm"));
        } else {
            rows.add(Row.text("Confirm " + verb + " (preview first)"));
        }
    }

    private void undo(List<Row> rows, MenuHost.Actor actor) {
        boolean allowed = host.allows(actor.uuid(), CommandSpec.permissionNode(CommandSpec.UNDO));
        Guardian guardian = host.guardian();
        String summary = "Nothing to undo.";
        boolean present = false;
        if (guardian != null && actor.uuid() != null) {
            try {
                var peeked = guardian.undoStack().peek(actor.uuid());
                if (peeked.isPresent()) {
                    present = true;
                    RollbackResult result = peeked.get();
                    summary = "Last change: " + result.mode()
                            + " affected=" + result.affectedCount()
                            + " status=" + result.status();
                }
            } catch (RuntimeException ex) {
                summary = "Undo history is unavailable.";
            }
        } else if (guardian == null) {
            summary = "Guardian is not running.";
        }
        rows.add(Row.text(summary));
        rows.add(actionRow("Confirm undo", "undo-confirm", allowed && present));
    }

    private void purge(List<Row> rows, MenuHost.Actor actor) {
        boolean allowed = host.allows(actor.uuid(), CommandSpec.permissionNode(CommandSpec.PURGE));
        rows.add(Row.text("Filter " + purgeKey()));
        rows.add(Row.text("Confirm deletes audit rows. There is no delete preview."));
        for (String time : PURGE_TIMES) {
            rows.add(new Row("Older than " + time, "purge-" + time, time.equals(purgeWindow)));
        }
        rows.add(actionRow("Confirm purge", "purge-confirm", allowed));
    }

    private void appendReportPage(List<Row> rows) {
        if (report.isEmpty()) {
            return;
        }
        int from = Math.max(0, (reportPage - 1) * STATUS_ROWS);
        int to = Math.min(report.size(), from + STATUS_ROWS);
        for (int i = from; i < to; i++) {
            rows.add(Row.text(trim(report.get(i))));
        }
    }

    private static Row actionRow(String label, String action, boolean allowed) {
        if (!allowed) {
            return Row.text(label + " (locked)");
        }
        return Row.of(label, action);
    }

    private String description() {
        return switch (screen) {
            case HOME -> "Audit, lookup, and rollback.";
            case STATUS -> "Version, storage, queue, and your last rollback.";
            case LOOKUP -> "Filter tokens go in the search box. Search reads that box.";
            case INSPECT -> "Toggles inspect mode for this player.";
            case ROLLBACK -> "Pick a radius and a time, preview, then confirm.";
            case RESTORE -> "Pick a radius and a time, preview, then confirm.";
            case UNDO -> "Undo the last rollback or restore you performed.";
            case PURGE -> "Permanently delete audit rows older than the selected window.";
        };
    }

    private String usage() {
        return switch (screen) {
            case HOME -> "Choose a screen. Close dismisses the menu.";
            case STATUS -> "Refresh reloads the report. Back returns home.";
            case LOOKUP -> "Extra tokens such as u:name t:1h r:20. A leading page token selects the page.";
            case INSPECT -> "Click lookup is not wired on this server yet.";
            case ROLLBACK, RESTORE -> "Search box adds extra tokens. r: and t: come from the buttons.";
            case UNDO -> "Confirm runs the inverse. It does not ask for a second preview.";
            case PURGE -> "Search box adds extra tokens. The in-game age floor still applies.";
        };
    }

    private String filterKey() {
        return joinTokens("r:" + radius, "t:" + window, MenuQuery.stripControls(search));
    }

    private String previewIdentity(boolean restore) {
        return (restore ? "restore " : "rollback ") + filterKey();
    }

    private String purgeKey() {
        return joinTokens("t:" + purgeWindow, MenuQuery.stripControls(search));
    }

    private static String joinTokens(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(part.trim());
        }
        return out.toString();
    }

    private static EventData event(String action) {
        return EventData.of(MenuEvent.ACTION, action)
                .append("@" + MenuEvent.SEARCH_QUERY, "#SearchInput.Value");
    }

    private static String trim(String line) {
        if (line == null) {
            return "";
        }
        String text = line.startsWith("§ ") ? line.substring(2) : line;
        if (text.length() > 180) {
            return text.substring(0, 177) + "...";
        }
        return text;
    }

    private static World worldOf(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        try {
            Object data = store.getExternalData();
            if (!(data instanceof EntityStore entities)) {
                return null;
            }
            return entities.getWorld();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private enum Screen {
        HOME, STATUS, LOOKUP, INSPECT, ROLLBACK, RESTORE, UNDO, PURGE
    }

    private record Row(String label, String action, boolean selected) {
        static Row of(String label, String action) {
            return new Row(label, action, false);
        }

        static Row text(String label) {
            return new Row(label, null, false);
        }
    }
}
