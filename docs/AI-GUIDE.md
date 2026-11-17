# Vystorm Core: guide for AI assistants

This guide covers all 109 capabilities in Core 0.64.0, including the Screens prototype.
Use [API.md](API.md) for signatures and full contracts, [CHANGELOG.md](../CHANGELOG.md) for changes,
and [llms.txt](../llms.txt) to navigate. The capability registry in CoreApi is authoritative for flag versions.
Package names start with `Vystorm.vystorm_core` (capital V); names such as `GuiInteface` are intentional.

## Setup and compatibility

Use a Java 25 toolchain and a local Core jar as `compileOnly`; never shade Core into a consumer plugin.
For a plugin targeting Paper/Purpur 26.2, the minimal descriptor is:

```yaml
name: ExamplePlugin
version: '1.0.0'
main: example.ExamplePlugin
api-version: '26.2'
depend: [vystorm_core]
```

```groovy
dependencies { compileOnly files('libs/Vystorm_Core.jar') }
java { toolchain.languageVersion = JavaLanguageVersion.of(25) }
```

Also add the Paper API for the server range you support. For Paper 1.20.6 compatibility, compile shared code
against that baseline, emit Java 21 bytecode (`options.release = 21`), and declare a compatible minimum
api-version such as `'1.20.6'`; a `'26.2'` descriptor prevents loading on older servers.
Core itself uses a Java 25 build toolchain with Java 21 bytecode. Do not infer server support from that toolchain.
Use Adventure 4 signatures, portable dialog types and `compat.ServerCompat` lookups for newer registry values.
Isolate newer server classes from common code; a capability flag does not make a missing Paper class loadable.
See [compatibility helpers](API.md#compatibility-helpers-since-0480) and [tested builds](../COMPATIBILITY.md).

```java
// In your plugin's enable path; CoreApi and API are in Vystorm.vystorm_core.
boolean supported;
try { supported = CoreApi.has(CoreApi.Capability.LOCALIZATION); }
catch (LinkageError tooOld) { supported = false; }
if (!supported) { /* disable with an explanatory log, or use your standalone path */ return; }
API core = CoreApi.api().orElseThrow();
```

Use `softdepend` only if the plugin works without Core. Keep optional integrations in separately loaded classes,
check before calling them, and treat an empty API or missing provider as a normal unavailable outcome.
Later methods can need `CoreApi.atLeast(...)` as well as the base capability: Screens maps require 0.59.0.

## Working rules

- Register language bundles, menus, settings, commands and providers once during enable on the server thread.
- Put player-visible text in your `lang/en.yml` and `lang/de.yml`; render for the viewer with `Lang`.
  Use `Lang.ref` for shared labels and named placeholders for complete sentences. Do not join translated fragments.
- On Core 0.64.0 or newer reach services through the managers of `API` (`core.compat().platform()`, `core.data().files()`,
  `core.settings().config()`); keep the hub in one field set first in `onEnable`. Builders and value types stay static.
- Use public facades. Never import internal packages, bootstrap classes or handler override seams.
  [API section 19](API.md#19-not-public-api) defines the boundary; Panels in section 18 are public API.
- Bukkit/world/player/inventory/config operations belong on the server thread unless explicitly documented otherwise.
  Web handlers and snapshots use `WebContext.sync`; DB callbacks marshal results back before touching game state.
- Never block the server thread with JDBC, HTTP, heavy file work, confirmed logging or waiting on futures.
- Validate IDs, lengths, ranges, permissions, ownership and current state from every command, UI and web input.
  Parameterize SQL values. A visible button or a client-provided value is not authorization.
- Check web, database, resource-pack, client and content-provider readiness independently. Supply vanilla fallbacks.
- Core releases owner-bound registries; close your own tasks, subscriptions and resources on disable or reload.
  Unregister settings/menu IDs before re-registering them. Never close Core's shared DataSource.
- Keep secrets and player data out of static web assets and logs. External JS/CSS must obey CSP;
  avoid inline scripts/styles, inline event handlers and HTML insertion of untrusted text.
- Prefix diagnostic messages with `[Plugin/Component]`; use `DebugMessages.high(message, throwable)` for failures.
- YAML 1.1 converts unquoted on/off/yes/no/true/false into booleans, including keys. Write `"on": "Enabled"`.
  Quote strings beginning with special YAML characters, MiniMessage tags, or containing `: ` or ` #`.
  Dots denote configuration paths, not literal key characters. Language values can be strings or lists of lines.

## Capability catalog

Each entry gives the constant, string ID, flag version, public entry points and reference section.
Entry-point names below are relative to `Vystorm.vystorm_core`; methods without parentheses identify an overload family.
Some older features received flags later. A flag's absence on an old Core need not mean the original method was absent.

### Foundation and compatibility

- **`CORE_API`** (`core-api`, since 0.11.0).
  Entry points: `CoreApi.has`, `CoreApi.api`, `CoreApi.atLeast`.
  Detect the running Core version and available features before loading optional integration code. A capability does not guarantee that a content provider, client feature or external service is available. Read: [API — 1.4 Version and capability checks](API.md#14-version-and-capability-checks).

- **`MANAGERS`** (`managers`, since 0.64.0).
  Entry points: `API.compat`, `API.tasks`, `API.loot`, `API.web`, `API.parties`, `API.items`, `API.mobs`, `API.runtime`, and parts of the older managers such as `api.data().files()`, `api.settings().config()`, `api.settings().prefs()`, `api.message().chat()`, `api.gui().panels()`, `api.command().register`.
  Reach Core through the managers of `API`; each static service class `X` has a part `XInterface` with the same method names (`PlatformCompat.sendMessage(p, t)` becomes `api.compat().platform().sendMessage(p, t)`). The static classes remain for older jars. A plugin that uses the managers needs Core 0.64.0. Read: [API — The managers (0.64.0)](API.md#the-managers-0640).

- **`EVENT_STAGES`** (`event-stages`, since 0.64.0).
  Entry points: `api.priority().stages()`, `_coreMethods.eventRegulator.EventStages.handle`, `_coreMethods.eventRegulator.EventStage`.
  Register handlers of events that several plugins handle with a stage (`PROTECT`, `ADJUST`, `ACT`, `OBSERVE` ...) instead of a Bukkit priority; Core calls them in one configured order. Remove the `@EventHandler` of a moved handler. `OBSERVE` handlers cannot change the cancelled state. Read: [API — Event order between plugins (0.64.0)](API.md#event-order-between-plugins-0640).

- **`SERVER_COMPAT`** (`server-compat`, since 0.48.0).
  Entry points: `compat.ServerCompat`, `compat.dialog.DialogInterface`.
  Detect server features and resolve newer materials, entities, sounds and attributes with fallbacks. Portable forms use native dialogs where available and chat forms on older servers. Read: [API — Compatibility helpers (since 0.48.0)](API.md#compatibility-helpers-since-0480).

- **`BUKKIT_COMPAT`** (`bukkit-compat`, since 0.51.0).
  Entry points: `compat.PlatformCompat`, `compat.StorageCompat`, `compat.WorldCompat`, `compat.GameplayCompat`, `compat.MobNavigation`, `compat.PassengerTeleport`, `compat.ClientEvents`, `compat.ClickActions`.
  Use shared Bukkit adapters for components, persistence, world operations and client events. Server-thread requirements still apply; packet features need their own runtime support checks. Read: [API — Core 0.51.0: shared Bukkit adapters](API.md#core-0510-shared-bukkit-adapters).

- **`LIFECYCLE_CLEANUP`** (`lifecycle-cleanup`, since 0.10.3).
  Entry points: `lifecycle.CoreLifecycle`, `lifecycle.ProviderFailures`.
  Core releases owner-bound registrations when a provider is disabled and contains provider failures. Consumers still cancel their own tasks and close resources they own; they must not start or stop Core's lifecycle. Read: [API — 3. Ground rules: threads, lifecycle, errors](API.md#3-ground-rules-threads-lifecycle-errors).

- **`TASK_GROUPS`** (`task-groups`, since 0.3.0).
  Entry points: `schedule.TaskGroup`.
  Group plugin-owned scheduled tasks and close the group when its work ends. Keep world operations on the server thread. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`TASK_GROUP_ONE_SHOTS`** (`task-group-one-shots`, since 0.26.4).
  Entry points: `schedule.TaskGroup`.
  Completed one-shot tasks are removed from the group instead of being retained. Scheduling uses the owning plugin's server. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`FEATURE_DIAGNOSTICS`** (`feature-diagnostics`, since 0.26.5).
  Entry points: `lifecycle.FeatureDiagnostics.register`, `lifecycle.FeatureDiagnostics.inspect`.
  Register up to 64 pure status checks per provider for active, fallback or unavailable features. Failures are isolated; checks must not mutate game state. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`UPDATE_CHECK`** (`update-check`, since 0.40.0).
  Entry points: `CoreApi.has`.
  Enables Core's installed-plugin update checks and `/vadmin updates`. This is a built-in feature flag; consumers register their own resources through UPDATE_SOURCES rather than running Core's checker. Read: [API — Update check registration (0.40.2)](API.md#update-check-registration-0402).

- **`UPDATE_SOURCES`** (`update-sources`, since 0.40.2).
  Entry points: `lifecycle.UpdateSources.spigot`.
  Register the plugin's SpigotMC resource ID once during enable for Core's update notices. Core removes the registration on disable. Read: [API — Update check registration (0.40.2)](API.md#update-check-registration-0402).

- **`LICENSED_MODULES`** (`licensed-modules`, since 0.28.0).
  Entry points: `licensing.LicensedModules`, `licensing.ModuleRequest`, `licensing.LicensedModule`.
  Optionally run signed modules with expiring customer leases through a compatible backend. Read the linked licensing protocol before implementing lifecycle or lease handling. Read: [API — Vystorm Core 0.30.0 – Plugin API Reference](API.md#vystorm-core-0300--plugin-api-reference).

- **`NETWORK`** (`network`, since 0.53.0).
  Entry points: `CoreApi.network`, `network.Network`.
  Exchange transient messages and request player transfers through a Velocity or BungeeCord hub. Disconnected calls safely fail or drop messages; close subscriptions on your own plugin's disable. Read: [API — Network (0.53.0, experimental)](API.md#network-0530-experimental).

### Localization, settings and administration

- **`LOCALIZATION`** (`localization`, since 0.22.0).
  Entry points: `lang.Localization.register`, `lang.Lang`.
  Register per-plugin language bundles and render text per viewer. Use references for shared labels and placeholders for values instead of concatenating translations. Read: [API — 4. Localization (api.lang())](API.md#4-localization-apilang).

- **`LANGUAGE_SELECTION`** (`language-selection`, since 0.45.0).
  Entry points: `lang.Localization.fallbackChain`, `lang.Localization.coverage`, `lang.TextWidth`, `lang.PlayerLanguageChangeEvent`.
  Adds language selection, configurable fallbacks, translation coverage and CJK-aware text layout. Redraw viewer-specific UI when the player's language changes. Read: [API — 4.3 /language, live refresh, width and translator workflow (0.45.0)](API.md#43-language-live-refresh-width-and-translator-workflow-0450).

- **`LANGUAGE_AUDIT`** (`language-audit`, since 0.26.5).
  Entry points: `lang.Lang.audit`.
  Inspect missing translations, fallback use and placeholder mismatches. Admins can run the language audit without modifying bundles. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`SETTINGS`** (`settings`, since 0.7.0).
  Entry points: `API.settings`, `_uiMethods.settings.SettingsSection`, `_uiMethods.settings.Setting`, `_uiMethods.settings.PlayerPrefs`.
  Expose personal or server settings with provider-owned getters and setters. Core renders the same sections through its supported interfaces and enforces access. Read: [API — 5. Settings (api.settings())](API.md#5-settings-apisettings).

- **`WEB_SETTINGS`** (`web-settings`, since 0.24.0).
  Entry points: `_uiMethods.settings.SettingsInterface.changed`, `_uiMethods.settings.SettingsRegistry`.
  Registered settings also appear at `/app/settings`. Notify Core when values change outside its editor so open views refresh. Read: [API — 5.5 Web settings page (0.24.0)](API.md#55-web-settings-page-0240).

- **`SETTINGS_DRAFTS`** (`settings-drafts`, since 0.26.5).
  Entry points: `_uiMethods.settings.SettingsDraft`.
  Stage up to 96 changes for 15 minutes and recheck permissions and original values before applying. Cross-section application is not atomic; inspect partial failures. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`SETTINGS_PROFILES`** (`settings-profiles`, since 0.26.5).
  Entry points: `_uiMethods.settings.SettingsProfiles`, `_uiMethods.settings.SettingPolicy`.
  Export explicitly safe server values and import them as a draft tied to provider versions. Never mark credentials, inventories or account data as safe for profiles. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`SETUP_WIZARD`** (`setup-wizard`, since 0.52.0).
  Entry points: `_uiMethods.settings.SetupWizard.open`, `_uiMethods.settings.SettingsProfiles`.
  Guide admins through selectively importing cross-plugin presets. Preview and confirm the draft before applying the existing setters and save hooks. Read: [API — Guided setup (0.52.0)](API.md#guided-setup-0520).

- **`CONFIG_EDITOR`** (`config-editor`, since 0.27.0).
  Entry points: `_uiMethods.settings.ConfigEditor.register`.
  Expose plugin YAML configuration in Core's admin editor, with secret filtering and per-plugin permissions. Register a reload callback so saved changes reach runtime state. Read: [API — 5.6 Config editor: your whole config.yml in the admin menu (0.27.0, merge rule 0.27.1)](API.md#56-config-editor-your-whole-configyml-in-the-admin-menu-0270-merge-rule-0271).

- **`CONFIG_EDITOR_MERGE_OPTION`** (`config-editor-merge-option`, since 0.27.1).
  Entry points: `_uiMethods.settings.ConfigEditor.register`.
  The overload with a mergeDefaults boolean controls automatic insertion of missing defaults. Only config.yml merges automatically by default; other files need explicit opt-in. Read: [API — 5.6 Config editor: your whole config.yml in the admin menu (0.27.0, merge rule 0.27.1)](API.md#56-config-editor-your-whole-configyml-in-the-admin-menu-0270-merge-rule-0271).

- **`CONFIG_HISTORY`** (`config-history`, since 0.44.0).
  Entry points: `_uiMethods.settings.ConfigEditor.history`, `_uiMethods.settings.ConfigEditor.undo`.
  Inspect config change history and request undo with stale-value checks. Forced undo is an explicit administrative choice; secrets are not recorded. Read: [API — 5.6 Config editor: your whole config.yml in the admin menu (0.27.0, merge rule 0.27.1)](API.md#56-config-editor-your-whole-configyml-in-the-admin-menu-0270-merge-rule-0271).

- **`FEATURE_SWITCHES`** (`feature-switches`, since 0.60.0).
  Entry points: `_uiMethods.settings.ConfigEditor.feature`, `notFeature`, `openFeatures`.
  Boolean keys named `enabled` in a registered config.yml are feature switches without any call; declare switches with other names. Admins toggle them in `/vadmin features`; a toggle saves through the config editor and runs the reload hook, so read the config in that hook. Read: [API — 5.6 Config editor: your whole config.yml in the admin menu (0.27.0, merge rule 0.27.1)](API.md#56-config-editor-your-whole-configyml-in-the-admin-menu-0270-merge-rule-0271).

- **`CONFIG_MENU`** (`config-menu`, since 0.54.0).
  Entry points: `_uiMethods.settings.ConfigEditor.registerAll`.
  Register editable YAML files for the chest-based configuration browser. A bundled setup.yml can provide first-run questions and recommendations. Read: [API — 6. Menus and managed inventories (api.gui())](API.md#6-menus-and-managed-inventories-apigui).

- **`ADMIN_MODULES`** (`admin-modules`, since 0.26.4).
  Entry points: `API.admin`, `_uiMethods.admin.AdminModule`.
  Add a provider-owned module to `/vadmin`; an ID matching a built-in catalog entry replaces that entry. The feature predates its capability flag. Read: [API — 10. Admin dashboard modules (api.admin())](API.md#10-admin-dashboard-modules-apiadmin).

- **`PERMISSION_LIST`** (`permission-list`, since 0.27.0).
  Entry points: `_uiMethods.admin.PermissionList`.
  Expose installed Vystorm plugin permission declarations through `/vadmin permissions`. Permission listing does not grant those permissions. Read: [API — 22. Appendix: Core commands and permissions](API.md#22-appendix-core-commands-and-permissions).

### Menus, panels and player communication

- **`COMMANDS`** (`commands`, since 0.45.0).
  Entry points: `commands.CoreCommands.register`, `commands.CommandSpec`, `commands.Arg`.
  Declare localized command trees with permissions, typed arguments, help and aliases. Remove the corresponding plugin.yml declaration for new trees, or use the adoption adapter for an existing executor. Read: [API — 24. Commands (Vystorm.vystorm_core.commands)](API.md#24-commands-vystormvystorm_corecommands).

- **`MENU_EXPLORER`** (`menu-explorer`, since 0.26.5).
  Entry points: `_uiMethods.menu.MenuExplorer.open`, `_uiMethods.autoGui.GuiInteface.menuEntries`.
  Browse menus across groups, search, and keep personal favorites and recent entries. Access is checked again when an entry opens. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`MENU_HISTORY_CLEAR`** (`menu-history-clear`, since 0.49.0).
  Entry points: `_uiMethods.menu.MenuExplorer.clearRecent`.
  Clear only a player's recent-menu history on the server thread. Favorites are retained. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`MENU_ACCESS_DIAGNOSTICS`** (`menu-access-diagnostics`, since 0.26.5).
  Entry points: `_uiMethods.autoGui.GuiInteface.inspectMenus`, `_uiMethods.autoGui.GuiInteface.menuAccessReason`, `_uiMethods.autoGui.GuiInteface.menuRequiredPermission`.
  Describe access failures and declare an additional required permission after registering a menu. Show inspection details only to admins; both permission and provider checks must pass. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`CHAT_ACTIONS`** (`chat-actions`, since 0.8.0).
  Entry points: `_uiMethods.chat.ChatActions`.
  Create expiring, player-bound clickable chat actions. Revalidate ownership and current state when the callback runs. Read: [API — 8. Chat actions and menu buttons](API.md#8-chat-actions-and-menu-buttons).

- **`CHAT_CHANNELS`** (`chat-channels`, since 0.26.6).
  Entry points: `chat.ChatChannels.tag`, `chat.ChatChannels.send`.
  Tag ordinary rich chat as GLOBAL, PARTY, TRADE or SYSTEM for compatible clients, preserving vanilla rendering. Your plugin selects recipients and enforces authorization; this is not a channel registry. Read: [API — Chat channels (0.26.6)](API.md#chat-channels-0266).

- **`TUTORIALS`** (`tutorials`, since 0.26.4).
  Entry points: `API.registerTutorial`, `_uiMethods.tutorial.TutorialGuide`.
  Register a bundled tutorial.yml as a localized guided tutorial. The feature existed before its 0.26.4 flag. Read: [API — 9. Tutorials](API.md#9-tutorials).

- **`ONBOARDING`** (`onboarding`, since 0.26.5).
  Entry points: `_uiMethods.tutorial.CoreOnboarding.markFeature`.
  Mark a successful plugin feature during optional Core onboarding. Opening a menu alone must not claim that a functional task succeeded. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`ROUTINE_MESSAGES`** (`routine-messages`, since 0.26.5).
  Entry points: `_uiMethods.notifications.RoutineMessages.send`.
  Batch only nonurgent notices according to the player's preference. Payments, combat and permission confirmations should remain immediate. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`PANELS`** (`panels`, since 0.25.1).
  Entry points: `panel.Panels`, `panel.PanelSpec`, `panel.Panel`, `panel.Ui`.
  Describe a state-derived UI tree with validated actions and fallback paths. Typed builders require 0.25.1; current Core also renders panels as screens automatically. Read: [API — 18. Native panels (Vystorm.vystorm_core.panel)](API.md#18-native-panels-vystormvystorm_corepanel).

- **`PANEL_EXTRAS`** (`panel-extras`, since 0.26.4).
  Entry points: `panel.Result.pending`, `panel.PanelAction.complete`, `panel.PanelAction.closeAndThen`, `panel.Ui`.
  Adds pending actions, safe handoff, confirmations, selections, progress rates and item icons. Complete asynchronous work through the action contract and revalidate game state. Read: [API — 18. Native panels (Vystorm.vystorm_core.panel)](API.md#18-native-panels-vystormvystorm_corepanel).

- **`CLIENT_UI_TESTING`** (`client-ui-testing`, since 0.26.4).
  Entry points: `panel.PanelTesting`, `web.ClientUiTesting`.
  Exercise panel actions and client UI behavior in detached tests without a running server. Use these helpers for consumer-side interaction tests. Read: [API — 18.11 Testing panels without a server (0.26.4, CLIENT_UI_TESTING)](API.md#1811-testing-panels-without-a-server-0264-client_ui_testing).

- **`SCREENS`** (`screens`, since 0.57.0).
  Entry points: `screen.Screens`, `screen.ScreenSpec`, `screen.ScreenLayout`, `screen.ScreenWidget`, `screen.ScreenSession`.
  Open clickable pinned or floating screens without a client mod. Prefer Panels for normal UI; use this lower-level API for custom layouts, maps and minigames, checking 0.58.0 or 0.59.0 for later additions. Read: [API — Screens (0.57.0, prototype)](API.md#screens-0570-prototype).

- **`PLAYER_MENU`** (`player-menu`, since 0.43.0).
  Entry points: `playermenu.PlayerMenu.register`, `playermenu.PlayerMenuEntry`.
  Add actions to the menu about another player, opened by sneak-right-click or command. Visibility and clicks receive both viewer and target and must enforce your own rules. Read: [API — Player menu (0.43.0)](API.md#player-menu-0430).

- **`NEWS`** (`news`, since 0.44.0).
  Entry points: `news.News.post`, `news.News.open`.
  Publish plain-text announcements on the shared server news board. Core manages unread notices, retention and reader preferences. Read: [API — News board (0.44.0)](API.md#news-board-0440).

- **`NEWS_EDIT`** (`news-edit`, since 0.46.0).
  Entry points: `news.News`.
  Core's news dialog and `/vadmin news edit` can change posts in place. Consumers do not need a new public editing API to use this flag. Read: [API — News board (0.44.0)](API.md#news-board-0440).

- **`SERVER_POLLS`** (`server-polls`, since 0.47.0).
  Entry points: `CoreApi.has`.
  Indicates the built-in `/vpoll` commands for timed polls and localized voting. No public poll registry is exposed; poll state is in memory and cleared on disable. Read: [API — Server polls (0.47.0)](API.md#server-polls-0470).

- **`INBOX`** (`inbox`, since 0.55.0).
  Entry points: `inbox.Inbox.send`, `inbox.Inbox.sendText`, `inbox.Inbox.unread`, `inbox.Inbox.open`.
  Leave personal messages for online or offline players. Key-based messages store your namespace and placeholders and render in the reader's language when opened. Read: [API — Inbox (0.55.0)](API.md#inbox-0550).

- **`PROFILES`** (`profiles`, since 0.56.0).
  Entry points: `profile.Profiles.register`.
  Contribute up to six localized lines to another player's profile card. The target may be offline; return an empty list when there is nothing to show. Read: [API — Profile cards (0.56.0)](API.md#profile-cards-0560).

### Web and optional client mod

- **`WEB`** (`web`, since 0.17.0).
  Entry points: `web.WebServices.register`, `web.WebModule`, `web.WebRoute`.
  Register web pages and authenticated JSON routes on Core's optional web platform. Static assets are public; route input is untrusted even after authentication. Read: [API — 14. Web platform](API.md#14-web-platform).

- **`WEB_LIVE`** (`web-live`, since 0.18.0).
  Entry points: `web.WebServices.push`, `web.WebServices.publishData`, `web.WebModule.Builder.snapshot`.
  Send per-player live events, initial snapshots and versioned public data. Never publish private player state as a static data asset. Read: [API — 14.6 Live data and published data (0.18.0)](API.md#146-live-data-and-published-data-0180).

- **`WEB_LOCALIZATION`** (`web-localization`, since 0.23.0).
  Entry points: `web.WebContext.language`, `lang.Lang.ref`.
  Resolve request language and localized module titles, errors and HTML translation tokens. Web UI text is plain text rather than MiniMessage markup. Read: [API — 14. Web platform](API.md#14-web-platform).

- **`WEB_OPEN`** (`web-open`, since 0.24.0).
  Entry points: `web.WebServices.openOrLink`, `web.WebServices.openOrElse`, `web.WebServices.overlayPage`, `web.WebServices.broadcast`.
  Open a trusted client overlay or choose a chat link or plugin fallback. Core bundles a default web UI, but availability remains a runtime condition. Read: [API — 14.2 WebServices (static facade)](API.md#142-webservices-static-facade).

- **`WEB_OPEN_LABEL`** (`web-open-label`, since 0.26.4).
  Entry points: `web.WebServices.openOrLink`.
  The five-argument overload accepts a custom Adventure component for the chat link label. Keep a fallback for an unavailable platform. Read: [API — 14.2 WebServices (static facade)](API.md#142-webservices-static-facade).

- **`WEB_CLIENT`** (`web-client`, since 0.19.0).
  Entry points: `web.ClientServices`.
  Use the optional client mod's web overlay channel after capability negotiation. Vanilla players need a working alternate interface. Read: [API — 15. Client mod: overlay and native HUD elements](API.md#15-client-mod-overlay-and-native-hud-elements).

- **`WEB_CLIENT_TRUST`** (`web-client-trust`, since 0.21.0).
  Entry points: `web.ClientServices.originTrusted`, `web.ClientServices.overlayAvailable`.
  Protocol 2 reports consent for the web origin so overlay availability can be known after join. Having a client mod alone does not imply trust. Read: [API — 15. Client mod: overlay and native HUD elements](API.md#15-client-mod-overlay-and-native-hud-elements).

- **`WEB_CLIENT_UI`** (`web-client-ui`, since 0.20.0).
  Entry points: `web.ClientUi`, `web.ClientElement`.
  Send native HUD, marker, tooltip and toast elements through the optional client UI channel. Respect client readiness and provide vanilla alternatives. Read: [API — 15. Client mod: overlay and native HUD elements](API.md#15-client-mod-overlay-and-native-hud-elements).

- **`CLIENT_UI_EXTENSIONS`** (`client-ui-extensions`, since 0.26.4).
  Entry points: `web.ClientElement.list`, `web.ClientElement.data`, `web.ClientUi.visible`.
  Adds lists, timed bars, HUD stacks, entity markers, item icons and data-driven tooltip matching. These extensions also require the corresponding capability from client 0.9.0 or newer. Read: [API — 15.5 Client 0.9.0 extensions (Core 0.26.4, CLIENT_UI_EXTENSIONS)](API.md#155-client-090-extensions-core-0264-client_ui_extensions).

- **`CLIENT_TOAST_ACTIONS`** (`client-toast-actions`, since 0.26.4).
  Entry points: `web.ClientUi.toast`.
  Attach a player action to a client toast. The overload takes the toast and a player callback; validate the action again on the server. Read: [API — 15.6 Toasts with an action (0.26.4, CLIENT_TOAST_ACTIONS)](API.md#156-toasts-with-an-action-0264-client_toast_actions).

### Storage and migration

- **`SHARED_DATABASE`** (`shared-database`, since 0.14.0).
  Entry points: `database.SharedDatabase.whenReady`, `database.SchemaMigrator`, `API.database`.
  Use Core's optional shared SQL pool after asynchronous readiness, with your own table prefix and schema migrations. Run JDBC off-thread and close connections, never Core's DataSource. Read: [API — 11. Shared database](API.md#11-shared-database).

- **`VERSIONED_STORE`** (`versioned-store`, since 0.3.0).
  Entry points: `storage.VersionedStore`.
  Persist versioned plugin data with explicit migration steps. Handle unsupported future versions without resetting or silently losing data. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`CONFIG_MIGRATOR`** (`config-migrator`, since 0.3.0).
  Entry points: `config.ConfigMigrator`.
  Apply ordered configuration migrations with backups. Keep migrations idempotent and preserve administrator choices. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`ATOMIC_FILES`** (`atomic-files`, since 0.26.4).
  Entry points: `_coreMethods.dataStorage.AtomicFiles`.
  Read and write storage with temporary-file replacement and recovery support. The utility predates its flag; unreadable data must not be overwritten with an empty default. Read: [API — 12. Player data, XP, preferences, file storage](API.md#12-player-data-xp-preferences-file-storage).

- **`MIGRATION_PREVIEWS`** (`migration-previews`, since 0.26.5).
  Entry points: `storage.MigrationPreviews.register`, `storage.MigrationPreviews.run`.
  Run read-only converters against detached source bytes and compare record counts and totals. Sources stay within the provider's data folder; previews never automatically import data. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`REWARD_POUCH`** (`reward-pouch`, since 0.32.0).
  Entry points: `item.RewardPouch`.
  Credit durable local rewards with stable source IDs, then recover receipt-backed inventory delivery. Treat full, duplicate and ambiguous-save outcomes explicitly; this is not cross-server delivery. Read: [API — Durable reward pouch (0.32.0)](API.md#durable-reward-pouch-0320).

### Items, loot and resource packs

- **`ITEM_REFS`** (`item-refs`, since 0.9.0).
  Entry points: `item.ItemRefs`.
  Resolve namespaced item references through registered providers and built-in fallbacks. Resolve or generate stacks on the server thread and preserve unique-item quantity rules. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`ITEM_REF_PRIORITY`** (`item-ref-priority`, since 0.12.0).
  Entry points: `item.ItemRefs.register`.
  Multiple providers can share a namespace with priorities. Built-in MythicMobs and MMOItems resolution remains a fallback after enabled providers. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`ITEM_SERVICES`** (`item-services`, since 0.10.0).
  Entry points: `item.ItemServices`.
  Query the optional item engine for managed-item creation, validation and equipment information. Missing or disabled providers return the documented empty or neutral outcome. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`ITEM_RESTRICTIONS`** (`item-restrictions`, since 0.26.0).
  Entry points: `item.ItemRestrictions`, `item.ItemServices.validate`.
  Core can restrict an item independently of its content provider. Validation reports restricted items as quarantined; consumers must honor the result. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`ITEM_ANVIL`** (`item-anvil`, since 0.40.1).
  Entry points: `item.ItemServices.prepareAnvil`, `item.ItemServices.commitAnvil`.
  Prepare a defensive-copy preview without consuming items, then commit only after delivery. Repeated commits must be idempotent; the registry's flag version is 0.40.1. Read: [API — Managed anvil bridge (0.40.1)](API.md#managed-anvil-bridge-0401).

- **`LOOT_TABLES`** (`loot-tables`, since 0.34.0).
  Entry points: `loot.LootTables`, `loot.LootTable`.
  Register shared weighted mob and chest loot tables and generate items through ItemRefs. Pure selection may run off-thread; item generation remains on the server thread. Read: [API — Shared loot tables (0.34.0)](API.md#shared-loot-tables-0340).

- **`LOOT_ROLLS`** (`loot-rolls`, since 0.44.0).
  Entry points: `loot.LootRoll`, `loot.LootResult`, `loot.LootTableConfig`, `loot.LootLuck`.
  Roll categories or tables with luck, tiers, pity, seeds, conditions and table references. Distinguish pure roll metadata from server-thread item generation. Read: [API — Shared loot tables (0.34.0)](API.md#shared-loot-tables-0340).

- **`MOB_LOOT`** (`mob-loot`, since 0.44.0).
  Entry points: `loot.MobLoot`, `loot.LootRoll.forMob`.
  Coordinate kill ownership and one roll per mob, using the mob's own table and level-derived luck. Player luck belongs to other reward sources, not mob-drop quality. Read: [API — Shared loot tables (0.34.0)](API.md#shared-loot-tables-0340).

- **`MMOITEMS_IDS`** (`mmoitems-ids`, since 0.26.4).
  Entry points: `mythic.MmoItemsBridge.idOf`.
  Read an MMOItems stack's TYPE:ID identity when the optional integration is available. Avoid replacing provider identity with display-name matching. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`MYTHIC_ITEM_DROPS`** (`mythic-item-drops`, since 0.27.2).
  Entry points: `item.ItemRefs`, `mythic.MythicMobsBridge`.
  Core's optional MythicMobs drop integration resolves native item references without requiring MMOItems. Configure drop IDs and preserve provider-defined uniqueness and quantities. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`RESOURCE_PACK`** (`resource-pack`, since 0.10.0).
  Entry points: `pack.ResourcePacks`, `pack.AssetRegistry`, `pack.AssetRequirement`.
  Declare plugin assets for Core's shared resource pack and check viewer readiness where a feature needs it. Do not assume accepting some other resource pack enables pinned screens. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`STAT_SUMS`** (`stat-sums`, since 0.35.0).
  Entry points: `stat.StatSum`, `item.ItemServices.equipmentStatContributions`.
  Combine named additive and multiplicative contributions in a standalone synchronized container. Provider-final equipment stats may include caps or curves that a raw sum does not reproduce. Read: [API — Source-based stats (0.35.0)](API.md#source-based-stats-0350).

- **`CURVES`** (`curves`, since 0.5.0).
  Entry points: `curve.Curve`, `curve.CurveRegistry`, `curve.RelativeStat`.
  Share level curves and relative-stat calculations across plugins. Honor each curve's domain and keep content-specific policies in your provider. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

### Runtime, mobs, combat and progression

- **`RUNTIME_HUBS`** (`runtime-hubs`, since 0.2.0).
  Entry points: `runtime.RuntimeContextHub`, `runtime.RuntimeActionHub`.
  Share contextual values and registered actions without direct plugin dependencies. Use namespaced keys and handle unavailable providers instead of assuming an implementation exists. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`MOB_SERVICES`** (`mob-services`, since 0.9.0).
  Entry points: `mob.MobServices`, `mob.RelationServices`, `mob.MobContextServices`, `mob.extension.MobExtensions`.
  Access optional mob spawning, relationships, context and extension providers. Provider registration is owner-bound and failures are contained. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`MOB_REFS`** (`mob-refs`, since 0.12.0).
  Entry points: `mob.MobRefs`.
  Resolve mob references through Vystorm providers first, then optional MythicMobs. Handle unresolved IDs without spawning an unrelated fallback mob. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`MOB_SPAWN_RULES`** (`mob-spawn-rules`, since 0.26.4).
  Entry points: `mob.MobService.spawnRules`, `mob.MobSpawnRule`.
  Query provider-owned spawn rules for a region or situation. The functionality existed before its 0.26.4 flag. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`TRIGGER_SINK_OWNER`** (`trigger-sink-owner`, since 0.10.3).
  Entry points: `mob.extension.MobExtensions.sink`.
  Register a trigger sink with an explicit owning plugin. Core removes it when the owner is disabled. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`MYTHIC_BRIDGE`** (`mythic-bridge`, since 0.3.0).
  Entry points: `mythic.MythicMobsBridge`.
  Access optional MythicMobs operations without forcing every consumer to depend directly on MythicMobs. Check integration availability and handle absent content. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`COMBAT_REGISTRY`** (`combat-registry`, since 0.2.0).
  Entry points: `combat.CombatRegistry`.
  Evaluate shared combat policy before applying plugin-owned effects. A policy decision does not itself deal damage or heal. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`COMBAT_KINDS`** (`combat-kinds`, since 0.26.4).
  Entry points: `combat.CombatRegistry.evaluate`, `combat.CombatRegistry.kindOf`.
  Distinguish HARM from SUPPORT in policy context. Classify your effects so friendly support is not treated as hostile damage. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`THREAT`** (`threat`, since 0.2.0).
  Entry points: `threat.ThreatServices`.
  Query and contribute to the optional shared threat provider. Missing providers produce neutral results, so keep your own required combat constraints explicit. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`PARTY`** (`party`, since 0.6.0).
  Entry points: `party.PartyServices`, `party.PartySource`.
  Read shared party membership through an optional source. Recheck membership when executing an action rather than trusting a stale UI snapshot. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`PARTY_ACTIVITIES`** (`party-activities`, since 0.26.5).
  Entry points: `party.PartyServices.readyCheck`, `party.PartyServices.recordProgress`.
  Use expiring ready-check snapshots and report already-granted group progress with stable event IDs. Core neither grants those rewards nor authorizes dungeon entry. Read: [API — Party activities (party-activities, since 0.26.5)](API.md#party-activities-party-activities-since-0265).

- **`REWARD_ELIGIBILITY`** (`reward-eligibility`, since 0.26.5).
  Entry points: `progression.RewardEligibility.allowed`.
  Honor the shared practice marker before awarding progression to participants or for defeated entities. Check both relevant entities; the marker does not intercept third-party rewards automatically. Read: [API — Reward eligibility (0.26.5)](API.md#reward-eligibility-0265).

- **`CHARACTER`** (`character`, since 0.16.0).
  Entry points: `progression.CharacterServices`.
  Read or grant character-level progression through the optional character provider. Distinguish character XP from vanilla experience and discipline XP. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`DISCIPLINES`** (`disciplines`, since 0.10.0).
  Entry points: `progression.DisciplineServices`.
  Access provider-defined discipline levels and XP through the shared facade. Do not assume a particular skills plugin is installed. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

### Worlds, regions and generation

- **`REGIONS`** (`regions`, since 0.4.0).
  Entry points: `region.RegionService`, `region.Region`, `region.RegionShape`, `region.RegionPriority`.
  Register and query shared regions with explicit shapes, flags and overlap priorities. Region metadata does not replace your own authorization checks before changing blocks. Read: [API — 16. Items, regions, curves, NBT and legacy handlers](API.md#16-items-regions-curves-nbt-and-legacy-handlers).

- **`SPAWN_POINTS`** (`spawn-points`, since 0.13.0).
  Entry points: `spawn.SpawnPointService`.
  Store and query fixed spawn points by region. Validate the selected location and applicable provider rules before spawning. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`WORLD_EVENTS`** (`world-events`, since 0.3.0).
  Entry points: `worldevent.WorldEventServices`.
  Query shared active world events and their modifiers through the common service. Optional sources can coexist with Core-owned event domains. Read: [API — 17. Cross-plugin services](API.md#17-cross-plugin-services).

- **`WORLD_EVENT_RUNTIME`** (`world-event-runtime`, since 0.31.0).
  Entry points: `API.events`, `worldevent.WorldEvents`.
  Own durable event domains with definitions, handlers, scopes, cooldowns and conflict rules. Start/resume/stop handlers must tolerate recovery and avoid duplicating external rewards. Read: [API — World-event runtime (0.31.0)](API.md#world-event-runtime-0310).

- **`MANAGED_WORLDS`** (`managed-worlds`, since 0.26.0).
  Entry points: `worlds.ManagedWorlds`, `worlds.WorldSpec`, `worlds.VisitService`, `worlds.VisitPolicy`.
  Manage generated plugin worlds with layout hashes, protection and controlled visits. Visits save player-state recovery evidence on disk; do not delete it during cleanup. Read: [API — 23. Managed worlds and visits (Vystorm.vystorm_core.worlds)](API.md#23-managed-worlds-and-visits-vystormvystorm_coreworlds).

- **`VISIT_RESTORE_OUTCOME`** (`visit-restore-outcome`, since 0.26.1).
  Entry points: `worlds.VisitService.restoreOutcome`, `worlds.RestoreOutcome`.
  Inspect typed restoration outcomes and handle failed or pending recovery explicitly. Never assume a teleport means player state was restored successfully. Read: [API — 23. Managed worlds and visits (Vystorm.vystorm_core.worlds)](API.md#23-managed-worlds-and-visits-vystormvystorm_coreworlds).

- **`ASYNC_DURABLE_VISITS`** (`async-durable-visits`, since 0.26.2).
  Entry points: `worlds.VisitService`.
  Admission persists snapshots in the background and revalidates live state on the server thread. Await the documented result rather than treating scheduling as successful entry. Read: [API — 23. Managed worlds and visits (Vystorm.vystorm_core.worlds)](API.md#23-managed-worlds-and-visits-vystormvystorm_coreworlds).

- **`TREE_GROWTH_PROFILES`** (`tree-growth-profiles`, since 0.26.5).
  Entry points: `_advancedMethods.structureGeneratorHandler.StructureGeneratorInterface.tree`, `_advancedMethods.structureGeneratorHandler.TreeGrowthProfile`.
  Choose bounded height, crown scale and crown form for normal tree generation. Callers remain responsible for all actual block positions, chunks and protection checks. Read: [API — Bedienung und Diagnose seit 0.26.5](API.md#bedienung-und-diagnose-seit-0265).

- **`WORLD_TREE_PLANS`** (`world-tree-plans`, since 0.37.0).
  Entry points: `_advancedMethods.structureGeneratorHandler.StructureGeneratorInterface.worldTree`, `_advancedMethods.structureGeneratorHandler.WorldTreePlan`.
  Generate deterministic immutable block plans for large trees and fungi without touching a world. Validate and place the result with a bounded server-thread budget. Read: [API — World-tree plans (0.37.0)](API.md#world-tree-plans-0370).

- **`FUNGUS_GROWTH_PLANS`** (`fungus-growth-plans`, since 0.38.0).
  Entry points: `_advancedMethods.structureGeneratorHandler.StructureGeneratorInterface.fungus`.
  Generate deterministic fungus plans for supported widths and sizes. The planner does not load chunks, enforce region permissions or place blocks. Read: [API — Fungus growth plans (0.38.0)](API.md#fungus-growth-plans-0380).

- **`GIANT_WORLD_TREES`** (`giant-world-trees`, since 0.39.0).
  Entry points: `_advancedMethods.structureGeneratorHandler.WorldTreePlan`.
  Sapling world-tree plans support giant crowns and up to 400,000 blocks. Check the full footprint and set species-specific block data during placement. Read: [API — Giant world trees (0.39.0)](API.md#giant-world-trees-0390).

- **`UNDERGROUND_TREE_ROOTS`** (`underground-tree-roots`, since 0.39.1).
  Entry points: `_advancedMethods.structureGeneratorHandler.WorldTreePlan.depth`.
  Bound the underground root depth for a world-tree plan. Include the area below the planting origin in height, collision and protection checks. Read: [API — Giant world trees (0.39.0)](API.md#giant-world-trees-0390).

### Visual projections and packet displays

- **`VISUALS`** (`visuals`, since 0.29.0).
  Entry points: `visual.Visuals`, `visual.ViewFrame`, `visual.BlockView`, `visual.ParticlePaths`.
  Render player-specific block projections and particle paths without changing real blocks. Budget refreshes, close stale views and enforce your own interaction rules. Read: [API — Visuals (0.29.0)](API.md#visuals-0290).

- **`HORIZONTAL_VISUALS`** (`horizontal-visuals`, since 0.30.0).
  Entry points: `visual.HorizontalFrame`, `visual.Visuals.projection`.
  Project through a masked horizontal opening using loaded chunks only. Geometry does not teleport players or provide physical collision. Read: [API — Horizontal projections (0.30.0)](API.md#horizontal-projections-0300).

- **`CLIENT_BIOMES`** (`client-biomes`, since 0.33.0).
  Entry points: `packet.ClientBiomes.supported`, `packet.ClientBiomes.snow`.
  Send snowy biome views to one player without editing saved biomes. Check runtime support and retain ownership so the real data can be restored. Read: [API — Client-only snowy biomes (0.33.0)](API.md#client-only-snowy-biomes-0330).

- **`PROJECTED_BIOMES`** (`projected-biomes`, since 0.36.0).
  Entry points: `packet.ClientBiomes.project`, `visual.BlockView.replacePrioritized`.
  Add spatial biome overlays and ordered block updates with restoration budgets. Biome overlays use quart-cell resolution and do not change world lighting or dimension sky. Read: [API — Spatial biome overlays and prioritized views (0.36.0)](API.md#spatial-biome-overlays-and-prioritized-views-0360).

- **`CLIENT_DISPLAYS`** (`client-displays`, since 0.44.0).
  Entry points: `display.ClientDisplays.supported`, `display.MapWall`.
  Show client-only map walls with packet-backed frames and per-viewer pixels. Check adapter support, bound update traffic and close views when no longer needed. Read: [API — Client-only displays (0.44.0)](API.md#client-only-displays-0440).

- **`CLIENT_CHUNKS`** (`client-chunks`, since 0.45.0).
  Entry points: `packet.ClientChunks.supported`, `packet.ClientChunks`.
  Preview entire client-only chunks without generating the corresponding server terrain. Runtime adapter support and caller-managed view lifetime are required. Read: [API — Client-only chunks (0.45.0)](API.md#client-only-chunks-0450).

## Recipes

These method snippets belong in your consumer integration class. Call registration methods once during enable,
and viewer methods on the server thread. They assume Core 0.59.0 after the guards above; no Core internals are used.
Imports for the snippets (use explicit imports in production if your own types have conflicting names):

```java
import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core._uiMethods.settings.*;
import Vystorm.vystorm_core.panel.*;
import Vystorm.vystorm_core.screen.*;
import Vystorm.vystorm_core.commands.*;
import Vystorm.vystorm_core.web.*;
import Vystorm.vystorm_core.database.SchemaMigrator;
import Vystorm.vystorm_core.item.ItemRefs;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.inbox.Inbox;
import Vystorm.vystorm_core.profile.Profiles;
import java.util.*;
import java.sql.*;
import java.util.concurrent.Executor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;
```

### Language, settings and menus

```java
Lang registerLanguage(API core, Plugin plugin) {
    return core.lang().register(plugin);
}
void greet(Lang lang, Player viewer) {
    lang.send(viewer, "example.hello", "name", viewer.getName());
}
void registerSettings(API core, Plugin plugin, Lang lang) {
    core.settings().register(plugin, SettingsSection.player("display",
        lang.ref("example.title"), lang.ref("example.description"), List.of(
            PlayerPrefs.toggle(plugin, "hints", lang.ref("example.hints"),
                lang.ref("example.hints-description"), true))));
}
void registerMenu(API core, Plugin plugin, Lang lang) {
    core.gui().registerMenu(plugin, "example", lang.ref("example.title"), Material.BOOK,
        p -> p.hasPermission("example.use"), p -> openMenu(core, plugin, lang, p));
}
void openMenu(API core, Plugin plugin, Lang lang, Player player) {
    Inventory inventory = core.gui().createInventory(plugin, null, player.getUniqueId(),
        9, lang.text(player, "example.title"), new GuiCallbacks() {
            @Override public void click(InventoryClickEvent event) {
                event.setCancelled(true);
                if (event.getRawSlot() == 4 && player.hasPermission("example.use"))
                    lang.send(player, "example.hello", "name", player.getName());
            }
        });
    inventory.setItem(4, lang.name(new ItemStack(Material.BOOK), player, "example.title"));
    player.openInventory(inventory);
}
```

Create personal inventories per viewer; translations cannot differ inside a shared inventory.
Schedule inventory replacements after the current click event. Managed callbacks cancel clicks/drags by default.
Store all example keys in your language bundles, for example:

```yaml
example:
  title: "Example"
  description: "Your preferences"
  hello: "Hello, {name}!"
  hints: "Show hints"
  hints-description: "Receive optional help"
```

### Portable dialogs

```java
void renamePreference(API core, Plugin plugin, Lang lang, Player player) {
    Vystorm.vystorm_core.compat.dialog.DialogInterface forms = core.dialogs();
    forms.prompt(plugin, player, lang.ref("example.rename-title"),
        lang.ref("example.rename-question"), "",
        value -> {
            if (!player.isOnline() || !player.hasPermission("example.use")) return;
            if (value.isBlank() || value.length() > 32) {
                lang.send(player, "example.invalid-name");
                return;
            }
            PlayerPrefs.setText(plugin, player, "label", value);
        }, () -> {}, p -> p.isOnline() && p.hasPermission("example.use"));
}
```

Use portable types for 1.20.6 consumers. Native Paper dialog classes require a separate guarded code path.
The callback must validate business rules even though the form already checks its own input shape.

### Panels with a Ui tree

```java
void registerPanel(API core, Plugin plugin, Lang lang) {
    Panels panels = Panels.of(plugin);
    panels.register("home", PanelSpec.builder(lang.ref("example.title"))
        .access(p -> p.hasPermission("example.use"))
        .fallback(p -> openMenu(core, plugin, lang, p))
        .render(ctx -> Panel.page(lang.component(ctx.player(), "example.title")).children(
            Ui.text(lang.component(ctx.player(), "example.description")),
            Ui.button("greet").label(lang.ref("example.greet"))
                .onClick(action -> {
                    lang.send(action.player(), "example.hello", "name", action.player().getName());
                    return Result.ok();
                })))
        .build());
}
void openPanel(Plugin plugin, Player player) {
    Panels.of(plugin).open(player, "home");
}
```

Build a fresh tree per render, keep interactive IDs stable, and use typed builders for each element.
Core checks actions against the current document. Still revalidate ownership, balances and other mutable rules.
Normal menus should use Panels: the same tree can reach the client mod, Screens and fallback interfaces.

### Commands

```java
void registerCommand(Plugin plugin, Lang lang) {
    CoreCommands.register(plugin, lang, CommandSpec.command("example")
        .permission("example.use")
        .sub("greet", node -> node.playerOnly().arg(Arg.integer("count", 1, 3))
            .executes(ctx -> {
                int count = ctx.get("count");
                for (int i = 0; i < count; i++)
                    ctx.reply("example.hello", "name", ctx.player().getName());
            }))
        .build());
}
```

Add `commands.example.description`, `commands.example.sub.greet.name` and `commands.example.sub.greet.description`
to the bundles; keep English IDs stable. Remove this root from plugin.yml when registering a new tree.
For an existing plugin.yml executor use `CoreCommands.adopt` and read [section 24](API.md#24-commands-vystormvystorm_corecommands).

### Web module

```java
void registerWeb(Plugin plugin, Lang lang) {
    WebServices.register(plugin, WebModule.builder("example", lang.ref("example.title"))
        .permission("example.use").assets("web/example/")
        .route(WebRoute.get("/status", request -> WebResponse.json(request.sync(() ->
            Map.of("online", plugin.getServer().getOnlinePlayers().size())))).build())
        .build());
}
void openWeb(API core, Plugin plugin, Lang lang, Player player) {
    WebServices.openOrLink(player, "example", "", () -> openMenu(core, plugin, lang, player));
}
```

Supply `web/example/index.html` and external JS/CSS in your jar; the route is `/api/example/status`.
Static files are public. Serve private state through authorized routes, and translate using the request language.
The example reads Bukkit state only inside `sync`; mutations also need domain validation and CSRF-protected routes.

### Shared database

```java
void prepareDatabase(API core, Plugin plugin, Executor databaseExecutor) {
    String table = core.database().sharedTableName("example", "visits");
    core.database().sharedReady().thenAcceptAsync(optional -> {
        if (optional.isEmpty()) return; // choose your documented unavailable-storage behavior
        try {
            new SchemaMigrator("example").step(1, "CREATE TABLE IF NOT EXISTS " + table
                + " (player BINARY(16) PRIMARY KEY, visits INT NOT NULL)").migrate(optional.get());
            try (Connection connection = optional.get().getConnection();
                 PreparedStatement query = connection.prepareStatement(
                     "SELECT visits FROM " + table + " WHERE visits >= ?")) {
                query.setInt(1, 1);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) { int visits = rows.getInt("visits"); /* detached data */ }
                }
            }
        } catch (SQLException | IllegalStateException failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Example/Database] Setup failed", failure);
        }
    }, databaseExecutor);
}
```

Pass an executor that runs off the server thread and shut down executors you own. Do not use an inline executor.
Readiness can complete on the caller or Core's initialization thread; explicitly async continuation is essential.
The table identifier is validated by Core; values use parameters. Schema migration failure must disable dependent work.

### Items, regions, inbox and profile cards

```java
boolean regionAllows(Player player) {
    return RegionService.get().flag(player.getLocation(), "example-use", true);
}
void inspectItem(Player player, Lang lang) {
    // Handle an absent item provider/reference; do not infer identity from display names.
    ItemRefs.resolve("minecraft:stone", 1).ifPresent(item ->
        lang.send(player, "example.item", "amount", item.getAmount()));
}
boolean notifyOffline(Plugin plugin, UUID recipient, String plotName) {
    return Inbox.send(plugin, recipient, "example.rent-expired", "plot", plotName);
}
void registerProfile(Plugin plugin, Lang lang, Map<UUID, Integer> cachedScores) {
    Profiles.register(plugin, (viewer, target) -> {
        Integer score = cachedScores.get(target.getUniqueId());
        return score == null ? List.of() : List.of(lang.component(viewer,
            "example.profile-score", "score", score));
    });
}
```

A region flag is only one policy input: check your own permissions and ownership too.
Inbox keys must remain in your registered language bundle for later reading; send returning false means unavailable.
Profile providers run on the server thread and accept offline targets. Use cached data; never run JDBC in the callback.

## Screens through 0.59.0

Screens are a prototype; `SCREENS` starts at 0.57.0, boxes at 0.58.0 and maps at 0.59.0.
Guard the relevant version before loading code using newer methods. All Screens/session operations run on the server thread.

### Presentation, fallback and display settings

- PINNED stays fixed on the monitor with a head-controlled pointer. It requires the loaded Core resource pack.
- FLOATING sits 2.5 blocks in front of the player and uses the crosshair. It needs no shader pack support.
- AUTO and a PINNED preference fall back to FLOATING when the Core pack is missing or classic mode is enabled.
  Iris and other shader packs can replace Core's shader; advise `/screen classic on` in that case.
  Classic mode disables pinning, so consumers must also handle floating or their own ordinary-menu fallback.
- `/screen settings` configures aspect ratio/resolution, FOV and UI scale. Use `Screens.profile` and
  `ScreenProfile.viewWidth`/`viewHeight` when choosing layout bounds; `Screens.saveProfile` stores a profile.
- Black is the default pinned backdrop; DIM, GRAY, WHITE and NONE are also available via `ScreenSpec.Backdrop`.
  In 0.59.0 the black backdrop is opaque and covers ultrawide views according to the profile.
- A pinned session hides the viewer's hand/held item and rain locally, restoring the temporary presentation on close.
  The pointer tip is the click hotspot. Do not manipulate player inventory to reproduce this behavior yourself.

Since 0.58.0 Panels render as proper screens: headers, sections/cards, rows/grids, tabs, sortable paged tables,
lists, progress, key-value content, toggles, number/choice inputs and forms. Wheel scrolling and hover hints work;
text fields use a typing dialog. The native client panel is preferred; when enabled, `screens.panels` tries a screen
before the plugin fallback, optional dialog and web path. Keep using the public Panels API for this adaptation.

Core-dependent plugins' chest/hopper/dispenser-style menus are mirrored automatically with `screens.inventories`.
The implementation also supports dropper, barrel and shulker menu inventories; real world/entity containers are excluded.
Both depend and softdepend declarations qualify. `screens.inventories-exclude` opts plugins out
(defaults: DeepInventory, SkyInventory, Limitless). The inventory remains open on the server and receives normal
click/close events. An uncancelled item-moving click returns to the real inventory; do not rely on the mirror for transfers.

Opening can be refused while another screen is open, riding, gliding, falling, in liquid or within five seconds of damage.
Supply `ScreenSpec.Builder.fallback` and inspect `ScreenOpenResult.opened`. Sneak closes unless claimed;
damage, death, teleport, world change, quit, owner disable and `/screen close` also end a session.

### Custom layout and map widget

```java
void openMap(Plugin plugin, Lang lang, Player player) {
    // Caller has checked SCREENS and CoreApi.atLeast("0.59.0").
    Screens screens = Screens.of(plugin);
    ScreenWidget.MapGrid grid = new ScreenWidget.MapGrid(2, 1);
    double mapHeight = 0.8 * grid.pixelHeight() / grid.pixelWidth();
    ScreenWidget map = ScreenWidget.map("map", 0.05, 0.12, 0.8, mapHeight, grid,
        lang.component(player, "example.map-unavailable"));
    ScreenLayout layout = new ScreenLayout(0.9, 0.65, List.of(
        ScreenWidget.fill("background", 0, 0, 0.9, 0.65, 0xFF1B1F27),
        ScreenWidget.label("title", 0.05, 0.02, 0.8, 0.07,
            lang.component(player, "example.title")).withAlign(ScreenWidget.Align.START),
        map,
        ScreenWidget.button("close", 0.3, 0.55, 0.3, 0.07,
            lang.component(player, "example.close"))
            .withFill(0xD0303A48).withHighlight(0xF0E3B04B).withSelected(true)));
    ScreenOpenResult opened = screens.open(player, ScreenSpec.builder("example:map", layout)
        .mode(ScreenSpec.Mode.AUTO).backdrop(ScreenSpec.Backdrop.BLACK)
        .fallback(p -> lang.send(p, "example.screen-unavailable"))
        .onInput((session, input) -> {
            if (input instanceof ScreenInput.Click click) {
                if ("close".equals(click.widgetId())) session.close();
                if ("map".equals(click.widgetId()) && session.showsMap("map")) {
                    int[] pixel = map.mapPixel(click.u(), click.v(), layout.width(), layout.height());
                    lang.send(session.player(), "example.pixel", "x", pixel[0], "y", pixel[1]);
                }
            }
        }).build());
    if (!opened.opened()) return;
    // Visual construction can be deferred; upload on the next tick even if showsMap is initially false.
    plugin.getServer().getScheduler().runTask(plugin, () -> screens.session(player).ifPresent(session -> {
        if (!session.spec().id().equals("example:map")) return;
        byte[] colours = new byte[grid.pixelWidth() * grid.pixelHeight()];
        Arrays.fill(colours, (byte) 34); // example Minecraft map-palette index, not RGB
        session.setMap("map", colours);
    }));
}
```

Widgets use top-left coordinates with v down; click coordinates are center-origin with v up.
Use `ScreenWidget.mapPixel` to convert a map click instead of mixing coordinate systems.
Every widget can have an ARGB box: `withFill`, `withAlign`, `withSelected` and `withHighlight` return copies.
`FILL_AUTO` uses kind defaults; `FILL_NONE` removes the box; `ScreenWidget.fill` creates a plain FILL rectangle.
START/CENTER/END align text; selected uses the hover highlight (gold by default). `withTextHeight` fixes line size.
Only enabled buttons, inputs and maps are clickable; keep IDs unique. Session text/enabled/layout setters redraw.

### Map pixels, animation and budgets

`MapGrid` permits 1×1 through 8×8 tiles. Each map is 128×128 on the wire, with a shader header row;
usable pixels are **128×127 per tile**. For square pixels use width × pixelHeight / pixelWidth as the widget height.
Pass a row-major array of Minecraft map-palette bytes to `ScreenSession.setMap`, not ARGB or image-file bytes.
Core retains the array: pass a fresh array per change and never mutate a submitted buffer.
Map widgets require PINNED mode, the Core shader and a working client-only frame adapter.
FLOATING or unsupported map delivery shows the supplied placeholder. `showsMap` reports actual rendering,
but can initially be false during deferred construction; the session retains early pixel updates for the build.
Maps draw above ordinary widgets, with only the pointer above them. Keep labels and controls outside map bounds.

For GIFs, `/screen demo gif NAME` reads `plugins/vystorm_core/screens/NAME.gif`, converts frames off-thread,
and loops them at GIF timing with at least one server tick per frame. The demo caps decoding at 300 frames,
composes offsets/disposal, preserves aspect ratio and applies Floyd–Steinberg dithering to the map palette.
There is no public GIF loader API. In a consumer, own the decoding/conversion and scheduling, then call only
the public session API on the server thread; cancel animation on `ScreenInput.Closed` and owner shutdown.
`/screen demo map` previews loaded terrain, with sneak/jump zoom and click coordinates.

Map updates are sent only to the viewer. The screen path sends full 128×128 tiles on each submitted frame;
do not assume the separate world-display service's rectangle diffing or packet budget applies.
Budget roughly tiles × 16,384 bytes × frames/second **per viewer**, before protocol overhead and compression.
A 2×2 canvas at 10 FPS is about 640 KiB/s per viewer before compression; 8×8 at 20 FPS is about 20 MiB/s.
Prefer fewer tiles and frames, precompute palette conversion off-thread, skip unchanged frames and stop idle animations.
For ordinary menus use Panels; direct Screens are appropriate when you need custom geometry, maps or minigames.

### HUD corner window (0.59.0, capability `hud-window`)

A small panel at the top right of the screen, e.g. current skill, level and XP. It is built from up to three WHITE boss bar titles (the Core pack makes WHITE boss bars draw no bar) and moved to the corner by the Core text shader, so it works with Iris/shader packs and every resolution. Without the pack the rows show as plain boss bar titles at the top centre.

```java
if (CoreApi.has(CoreApi.Capability.HUD_WINDOW)) {
    Huds.of(plugin).show(player, HudCard.of(Material.IRON_PICKAXE, "Mining", "Lv 42", 0.63, "340 / 540 XP")
        .withFooter("+12 XP"), 60);                 // 60 ticks; 0 = until hide()
    // level-up: .highlighted() makes the window glow gold
}
```

One window per player (last `show` wins). Texts are plain strings in the default font; GUI text whose colour has red = 1 is reserved for the window.
