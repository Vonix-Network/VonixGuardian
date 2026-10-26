STATUS=CANDIDATE_COMPLETE
CANDIDATE=8 files. Cell (7): hytale/src/main/java/network/vonix/guardian/hytale/menu/{MenuRegistration,MenuCommand,MenuHost,MenuPage,MenuEvent,MenuQuery,MenuOps}.java. Result (1): docs/hytale/results/menu.md. The parent still has to call MenuRegistration.register after CommandRegistration.register. This cell did not edit the plugin class.
EVIDENCE=bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/menu → COMPILE_GATE=PASS classes=29 log=C:/Users/Pargon/DEV/vonixguardian-hytale/.buildlogs/cc/1792743719-224086/javac.log (javac --release 25, Server-0.7.4.jar + bundled classpath). The 29 class files are 13 menu classes from the 7 sources (MenuHost$Actor, MenuQuery$Kind, MenuQuery$Prepared, MenuQuery$PageSplit, MenuPage$Screen, MenuPage$Row) plus sourcepath dependencies javac compiled because they were referenced: spi (BlockSnapshot, GuardianServices, PlayerDirectory, PlayerDirectory$PlayerInfo, WorldBlockAccess), command/InspectMode, and world (WriteVerifier, WriteVerifier$Report, HytaleBlockAccess and its nests, HytaleWorlds). Those dependency sources were not modified. No gradle run.

javap -public/-protected/-private and constant-pool strings against Server-0.7.4.jar:

- com.hypixel.hytale.server.core.modules.ui.UIModule contains the string "Serverside Noesis UI is disabled. This feature is still in development and not available yet. Use the current custom UI system instead." and requireServersideNoesisUi. That path was not used.
- com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage(PlayerRef, CustomPageLifetime, BuilderCodec<T>). handleDataEvent(Ref<EntityStore>, Store<EntityStore>, T). protected sendUpdate(UICommandBuilder, UIEventBuilder, boolean). The boolean is the CustomPage clear flag. rebuild uses clear=true. This cell passes true on every screen publish.
- com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage(PlayerRef, CustomPageLifetime). abstract build(Ref, UICommandBuilder, UIEventBuilder, Store). onDismiss(Ref, Store). protected close(). protected sendUpdate(UICommandBuilder, boolean). protected final PlayerRef playerRef.
- com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager.openCustomPage(Ref<EntityStore>, Store<EntityStore>, CustomUIPage).
- com.hypixel.hytale.server.core.entity.entities.Player.getPageManager() and getComponentType(). com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType().
- com.hypixel.hytale.server.core.ui.builder.UICommandBuilder.append(String), append(String, String), clear(String), set(String, String|Message|boolean|int|Value). com.hypixel.hytale.server.core.ui.builder.UIEventBuilder.addEventBinding(CustomUIEventBindingType, String, EventData) and the boolean overload. com.hypixel.hytale.server.core.ui.builder.EventData.of(String, String) and append(String, String).
- com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime.CanDismiss. CustomUIEventBindingType.Activating and ValueChanged.
- com.hypixel.hytale.codec.builder.BuilderCodec.builder(Class, Supplier).append(KeyedCodec, BiConsumer, Function) returns BuilderField.FieldBuilder, whose add() returns the builder. build() returns BuilderCodec. KeyedCodec(String, Codec). Codec.STRING. MenuEvent codec keys are Action and SearchQuery. The @ prefix is only the client substitution marker on the binding, copied from CommandListPage (@SearchQuery paired with #SearchInput.Value).
- com.hypixel.hytale.server.core.ui.Value.ref(String documentPath, String valueName).
- CommandListPage constant pool: Pages/CommandListPage.ui, Pages/BasicTextButton.ui, #CommandList, #CommandList[ / ].TextSpans / ].Style, #SearchInput, #SearchInput.Value, #BackButton, #BackButton.Visible, #SendToChatButton, #CommandName.TextSpans, #CommandDescription.TextSpans, #CommandUsageLabel.TextSpans, LabelStyle, SelectedLabelStyle, and the section ids #VariantsSection.Visible, #SubcommandSection.Visible, #AliasesSection.Visible, #PermissionSection.Visible, #ParametersSection.Visible, #ArgumentTypesSection.Visible. The class also references clear, append, and set.
- HelpCommand.openHelpUI does not open the page on the command thread. It uses CommandContext.senderAsPlayerRef(), Ref.getStore(), Store.getExternalData() as EntityStore, EntityStore.getWorld(), then CompletableFuture.runAsync(..., world). The world runnable loads Player and PlayerRef with getComponent and calls PageManager.openCustomPage. MenuCommand does the same hop with World.execute and does not join.
- AbstractCommand.addSubCommand throws IllegalStateException "Cannot add new subcommands when a command has already completed registration" when private boolean hasBeenRegistered is true. It then puts the child in the live subcommand map and sets the child's hasBeenRegistered true without calling completeRegistration. getSubCommand(String) lowercases the key and reads that live map, then subCommandsAliases. completeRegistration sets the flag, registers the permission when the PermissionQuery is non-null, and does not return early. setOwner(CommandOwner) stores the owner and, when permission is null, calls generatePermission. CommandManager.get(), getCommandRegistration(), and resolveCommand(String) are public. MenuRegistration clears the parent flag, addSubCommand, restores the flag, setOwner(parent owner), then completeRegistration on the child only.
PREMISES=The interactable page exists, so the menu is not a chat substitute. These brief assumptions did not match the 0.7.4 jar. (1) Serverside Noesis UIPage is disabled by UIModule. The page is InteractiveCustomUIPage on the custom UI system. (2) MenuRegistration cannot add menu through CommandRegistry.registerCommand without a second root, and AbstractCommand.addSubCommand throws after the guardian tree has completed registration. The CommandRegistry argument is null-checked and otherwise unused. Attachment clears private hasBeenRegistered around addSubCommand, then setOwner and completeRegistration on the child. Dispatch uses the live subcommand map. (3) CustomUIPage.playerRef is protected final. onDismiss and close drop every other field and there is no static page map. An in-flight worker still holds that page object until the job finishes. (4) IncludesAssetPack is false and was not edited, and no plugin .ui document is shipped. The shell is the client document Pages/CommandListPage.ui plus Pages/BasicTextButton.ui. (5) sendUpdate reads the entity store on the caller and then World.execute. The worker never calls it. Results hop through the World captured from the click's Store. The boolean is clear, and screen publishes pass true. (6) The post-mutation hook in this tree is WriteVerifier.converge(WorldBlockAccess, Executor), called with GuardianServices.mainThread after RollbackEngine.execute returns. It is not called from inside the world mutation. (7) CommandRuntime, LookupText, and MutationReport are package-private. MenuQuery copies prepare's scoping, and MenuOps formats lookup lines from public Action fields and prints MutationOutcomeFormatter.lines.
GAPS=Plugin load is PLUGIN_LOAD_UNVERIFIED. No live client has opened the page, so these are unproven: that Pages/CommandListPage.ui still has every selector CommandListPage writes, that #SendToChatButton.Visible hides the stock button, that a document-level binding on that button does not still fire, that #CommandList scrolls, and that clear("#CommandList") empties children rather than removing the list. handleDataEvent is treated as the player's world thread because it is given Ref and Store. That dispatch thread was not observed on a live server. Command execute's thread was also not observed. The open hop follows HelpCommand anyway. setAccessible on hasBeenRegistered assumes the server jar is not a restricted named module. That is unproven until the plugin loads. If CommandRegistration has not run, MenuRegistration throws IllegalStateException. The parent must call it. Help text was not changed, so /vg help does not list menu. Hytale still checks the generated node {base}.command.guardian.menu before execute. The menu's own gate is the plugin base permission plus vonixguardian.command.use. There is no vonixguardian.command.menu node. Lookup pages are capped at 8 rows so the list stays on one screen. The command allows up to 50. Rollback planning progress is not streamed to the page. Pre-open refusals use Message.raw and are not theme-colored. The menu pool is a daemon BoundedGenerationExecutor named VonixGuardian-HytaleMenu, queue 64, 2 workers, created on the first job, with no shutdown hook. A second register reuses the host and does not add another subcommand. A player who changes worlds before a result arrives is updated on the World captured at click time. Inspect still only toggles InspectMode. Click capture is unwired, same as the command. If mainThread() is null, converge is skipped and undo is still pushed. The command always calls converge. #preview and #count are stripped or honored so confirm cannot mutate a preview or a count. A typed #count must be removed and previewed again before confirm.
EFFECTS=NOT_PERFORMED

## UI shell

Open path: `/guardian menu` or `/vg menu`. Non-players are refused. The sender must have a valid player ref. `PlayerDirectory.hasPermission` must accept the plugin base permission and `vonixguardian.command.use`. A null directory, a thrown check, a null uuid, or a missing owner is a refusal. The page is then opened on the player's world thread with `CustomPageLifetime.CanDismiss`.

The document is `Pages/CommandListPage.ui`. Rows are `Pages/BasicTextButton.ui` appended to `#CommandList`, with `.TextSpans` and `Value.ref("Pages/BasicTextButton.ui", "LabelStyle"|"SelectedLabelStyle")`. `#BackButton` returns home. A Close row calls `CustomUIPage.close()`. Escape dismisses through `CanDismiss` and `onDismiss`. The search box is `#SearchInput`. `ValueChanged` stores the text and does not query. Buttons that need the text also bind `@SearchQuery` to `#SearchInput.Value`.

Denied actions are labeled `(locked)` and have no event binding. The handler checks the node again before any worker starts, and the worker checks it again. Fail closed.

## Screen / action matrix

| Screen | What it shows | Control | World write | Confirm |
| --- | --- | --- | --- | --- |
| Home | Status, Lookup, Inspect, Rollback, Restore, Undo, Purge, Close | navigation | no | no |
| Status | `GuardianStatus.render` lines (version, storage, writer queue, rollback recovery) plus `undoStack.peek` as last rollback, 8 lines per page | Refresh, previous, next | no | no |
| Lookup | Filter box, then a page of action lines | Search, previous, next | no | no |
| Inspect | On or off for this player | Enable or disable | no | no |
| Rollback | `r:5/10/20/50`, `t:1h/6h/24h/7d`, extra tokens from the search box | Preview, then Confirm rollback | preview no; confirm yes | confirm click, and only when that preview key still matches |
| Restore | Same pickers as rollback | Preview, then Confirm restore | preview no; confirm yes | same, and a rollback preview does not arm restore |
| Undo | Peek summary for this player | Confirm undo | yes, inverse mode | confirm click |
| Purge | `t:30d/60d/90d` plus extra tokens. Copy says there is no delete preview | Confirm purge | deletes audit rows only | confirm click |

Preview appends `#preview` after stripping a typed `#preview` and `--confirm`. It plans and calls `RollbackEngine.execute(plan, true)`. It does not call `WriteVerifier.converge` and does not push undo. Confirm parses the same radius, time, and filter without `#preview`. It refuses when the filter is still `preview` or `countOnly`. A real rollback or restore that reports `mutatedWorld()` calls `WriteVerifier.converge(world, mainThread)` on the worker, then `undoStack.push`. Undo peeks on the click thread, drops a legacy or empty entry there without a world write, and otherwise runs the inverse on the worker. `popIfSame` uses that same instance and runs only when the inverse `isSuccess()` or `mutatedWorld()`. Purge reads `config().purge().minAgeSecondsInGame()` and calls `purgeEngine().purge`. Queries use `MenuQuery`, which copies `CommandRuntime.prepare`: default rollback radius 10, `enforceMaxRadius`, world scope, `r:#we` refused, and `vonixguardian.command.viewothers` scoping.

## Callback threads

| Callback | Thread | Blocks on the engine |
| --- | --- | --- |
| `MenuCommand.execute` | Command thread. Permission and the player ref are resolved here, then `World.execute` opens the page. The future is not joined. | no |
| `build` | Framework open, after that world hop. Paints Home. No engine call. | no |
| `handleDataEvent` | Treated as the player's world thread. It snapshots uuid, name, position, and `worldIdOf`, checks permission, and returns. Peek, inspect toggle, and legacy undo-drop are in-memory and stay here. | no |
| Menu worker `VonixGuardian-HytaleMenu` | Off the world thread. Status render, lookup, `RollbackEngine.plan` and `execute`, purge, and `WriteVerifier.converge`. | yes, on this thread. `execute` waits until world mutations finish. `converge` waits on `mainThread()`. |
| Result hop | `World.execute` of the world captured from the click's store. Then `sendUpdate(commands, events, true)`, which schedules another world task. | no |
| `onDismiss` and `close` | Click or dismiss path. `close` is only invoked from the click handler because it reads the store. Both drop report lines, the preview key, the search text, and the open flag, and bump the job id so a late result does not paint. | no |

`GuardianServices.mainThread()` is passed into `WriteVerifier.converge` only. It is not used to publish UI, and it is not called from inside a rollback world mutation.

## Permission nodes

Hytale checks `{base}.command.guardian.menu` before `execute`, because `completeRegistration` registers the generated node. `{base}` is `PluginBase.getBasePermission()` on the owner copied from `/guardian`. Inside `execute`, the player must also hold `{base}` and `vonixguardian.command.use` through `PlayerDirectory.hasPermission`.

| Action | Vonix node checked again on the click and on the worker |
| --- | --- |
| Open | `vonixguardian.command.use` plus plugin base |
| Status refresh | `vonixguardian.command.status` |
| Lookup search and paging | `vonixguardian.command.lookup` |
| Inspect toggle | `vonixguardian.command.inspect` |
| Rollback preview and confirm | `vonixguardian.command.rollback` |
| Restore preview and confirm | `vonixguardian.command.restore` |
| Undo confirm | `vonixguardian.command.undo` |
| Purge confirm | `vonixguardian.command.purge` |
| View another user | `vonixguardian.command.viewothers`, same scoping as the commands |

Lookup still passes `PermissionNode.LOOKUP` to `LookupPermissionFilter` as the action-type family. That is the same call the lookup command makes. It is not the command gate.
