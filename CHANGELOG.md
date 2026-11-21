## 0.66.1 — 2026-11-21

- Fixed: buttons in screen menus that ask your protection plugin first answered "You lack permission for this." even where the player was allowed, for example the Slideshow row in Vystorm Gallery's painting settings. Since 0.65.0 Core stops a player's clicks into the world while a screen is open, so nothing behind the screen gets broken or used; it also stopped the test click that plugins send to WorldGuard, GriefPrevention, Lands and others to ask whether the player may use a block or an entity. Now only the player's own clicks are stopped: everything a plugin does while it handles a click on a screen, a dialog button or Back passes as before. Servers need no change; the plugins need no update.
- Fixed: a config.yml with a YAML error (a missing colon, a wrong indent) could be saved over with almost nothing. Core's config migration, the settings screens that plugins build with `ConfigSettings.of(plugin)`, and the commands that change Core's own config (`/vadmin commands mode|alias`, `/vadmin screens`) now leave such a file exactly as it is and say where the error is, for example "line 6, column 3: could not find expected ':'", in the console and to the admin.
- `/vadmin features` shows readable names for Core's own switches in all seven languages ("Client mod HUD" instead of "Web › Client › Ui"), each with a description, and the heading says "Vystorm Core" instead of `vystorm_core`.
- Long descriptions in the feature list wrap to two lines and end after a whole word with "…", instead of stopping in the middle of a word.
- The pointer in screen menus now moves evenly with the mouse across the whole panel: the same mouse movement moves it by the same distance in the middle and at the edges, horizontally and vertically, and turning while looking up or down no longer bends its path. Before, it sped up towards the edges of wide menus (about one and a half times at the sides). The click still lands exactly at the tip of the pointer. Players get the updated resource pack from Core by themselves.
- Fixed: on 26.3 clients the text of filled buttons right of and below the middle of a screen menu showed only faintly through its own button (about 23 of 54 buttons in a full grid). 26.3 draws the texts of a screen sorted by their distance to the player, so a button could be drawn after its own text. Core now places every box, text and icon so that this order is the same as on 26.2. Nothing moves on the screen, and clicks land where they did. No change to the resource pack.
- For plugin authors: `feature(plugin, path, null, description)` and `label(plugin, path, null, description)` no longer remove a name that was set for that path before; a null label or description keeps the earlier one.

## 0.66.0 — 2026-11-19

- **Settings are a list now:** the settings screen (`/vsettings`, the Settings tile of the main menu) shows every setting as one row across the whole panel: the full name on the left, the current value on the right (ON in green, OFF in red, the number, the chosen option, the text) and the description under the name in grey. Each area is a heading row. Before, the screen menus showed settings as small tiles with cut names and the value only at the bottom or when pointing at a tile, so you could not tell which tile was which setting.
- **Click anywhere on a row:** a switch flips, a choice goes to the next option (right click: the previous one), a slider goes one step up (right click: down), a number or text field opens the typing box. Nothing else changes about saving: the same checks, the same plugin code, the same messages.
- **Settings of other plugins too:** the areas that other Vystorm plugins add to the settings (Chat, Winter, Skills ...) are lists automatically; those plugins need no update. `/vsettings`, and every plugin button that opens settings, now open this screen. Players who cannot get a screen (no resource pack, `/screen classic on`) get the dialogs as before.
- **Feature switches as one list:** `/vadmin features` shows all plugins at once, each as a heading with "on / total", and under it every feature with ON/OFF and its description. Click a row to switch it. `/vadmin features <plugin>` shows one plugin.
- **Config editor as a list:** `/vadmin config` shows a plugin's values as rows: the readable name of the key, the current value (gold when it differs from the default), and the comment from the config file as the description, with the default value and "Needs a server restart" where they apply. Each section of the config file is a heading with its values under it; a click on a heading opens that section alone. The page buttons (Search, Only changed values, Back, Change history, page arrows) are a button bar below the list. Left and right click on a row do what they did on the tile (change, back to the default).
- The real chest of the config editor (the "Chest" button, or where no screen can open) now shows the values of each section on the page of the section above, with names like "Showcase › Enabled" and the current value as the first line of the description. Entries of lists are named "Entry 2: <first value>" instead of only "Entry 2".
- Long lists scroll with the mouse wheel, and the hint line at the bottom says which rows you see, for example "3 to 12 of 27". Pointing at a row shows its full description there.
- The main menu, the admin dashboard and other menus that lead somewhere keep their tiles.
- **For plugin authors:** `Ui.section(title).list()` draws a panel section as such a settings list on screens: toggles, sliders, number fields, choices, text fields and buttons become rows with the same click meaning and the same handlers; texts and other elements keep their look. The client mod and the web page show a normal section. Capability `panel-list-layout`. See "18.12 List layout for settings" in `docs/API.md`.

## 0.65.1 — 2026-11-19

- Fixed: on Minecraft 26.3 clients the screen menus were broken: panels floated in the world as dark boxes, maps stayed black and the player was not seated into the screen. The 26.3 client could not load Core's resource pack at all (the server log showed `FAILED_RELOAD` for every player): 26.3 compiles the game's shaders differently and rejected the text shader in the pack. The pack now carries a second version of that shader that only 26.3 and newer clients use (a pack overlay); 26.2 clients keep the old one. With a 26.3 client the pack loads again and menus are real screens with a mouse pointer.
- Map pictures inside screens (the map demo and tutorial GIFs) are not shown on 26.3 clients yet: 26.3 draws maps before the screen panel, so the panel covered them. There the screen shows the widget's text instead, the pointer stays visible, and the rest of the screen works. 26.2 clients show the maps as before. Maps in item frames (Boards, Snapshot, Gallery) are not affected.
- Players whose game has not loaded Core's resource pack (declined, download failed, could not be loaded, or no pack offered) now get the classic menus (chests, dialogs) instead of broken screens, and no HUD corner window. Before, they got screens drawn with a font their game did not have.
- Clearer log when a player's pack does not load: one warning per player with the status, the pack URL and what to check, for example `[ResourcePacks] Steve: FAILED_DOWNLOAD for http://...:8165/...zip: the client could not download it; check that this URL is reachable from outside the server (open http.port, or set http.public-host or public-url in resource-pack.yml). This player gets chest menus instead of screens ...`. When the built-in pack server could not start (port taken), the "Pack ... at <URL>" line now says that clients cannot download it from there.
- The main menu shortcut (sneak and left-click twice quickly) is off by default. It opened the menu in the middle of building: while sneaking, breaking or mining block after block sends a block click every 250 to 300 ms, which counted as a double click. Now only clicks into the air count, and one click that reaches the server twice counts once. Switch it on with `menu.sneak-click-shortcut: true` in Core's `config.yml` or in `/vadmin features vystorm_core`. Servers that already have the key in their `config.yml` keep their value.

## 0.65.0 — 2026-11-18

- **One place for placeholders:** Core now has one place for the values of all Vystorm plugins. A plugin registers its values once (balance, level, region, votes ...), and you can show them anywhere: as `%vystorm_<plugin>_<value>%` in every plugin that uses PlaceholderAPI (scoreboards, tab lists, holograms, chat formats), and as `{plugin:value}` in the texts of Vystorm plugins that resolve placeholders. `/vadmin placeholders` lists every value with a description; `/vadmin placeholders test <text>` shows a text the way you would see it.
- **Core's own values** are there from the start: player name, display name, UUID, world, coordinates, players online and the limit, server time and date (with your own pattern), the clock of a world, the player's language, TPS and tick time, ping, experience level and points, health, food, game mode, the region the player stands in with its flags and attributes, the party, the Vault balance and the character level. Examples: `%vystorm_core_online%`, `%vystorm_region_id%`, `%vystorm_vault_balance_formatted%`, `{core:time:HH:mm}`. The full list is in `docs/API.md`, "Core's own placeholders".
- **Works with PlaceholderAPI in both directions, without needing it:** if PlaceholderAPI is installed, Core registers the expansion `vystorm` on its own (nothing to download, nothing written to PlaceholderAPI's folder), and texts resolved through Core also show the placeholders of other plugins such as `%player_name%`. It does not matter whether PlaceholderAPI starts before or after Core or is reloaded. Without PlaceholderAPI nothing changes. Switches: `placeholders.placeholderapi.provide`, `consume` and `consume-async` in Core's `config.yml`.
- **Placeholders in language files, if you want them:** with `placeholders.in-messages: true` in Core's `config.yml` the language files (`lang/*.yml`) of all Vystorm plugins may contain placeholders. It is off by default, so every existing text is shown exactly as before.
- **Player input stays text:** what a placeholder inserts is shown literally. A player name, chat line or guestbook entry that contains `<red>`, a click tag or another placeholder is not interpreted. A placeholder nobody answers stays in the text as written; `\%vystorm_core_online%` (with a backslash) shows a placeholder without replacing it.
- **Good to know for scoreboard and chat plugins:** many of them ask for placeholders from a background thread. Values that Core can answer there safely (names, positions, counts, clock, TPS, regions) work as usual; values that need the server thread (party, economy balance, character level, language) stay as written in such a plugin. `/vadmin placeholders <namespace>` marks them.
- **For plugin authors:** `api.placeholders().register(plugin, "namespace")` returns your namespace; declare values with `value`, `player`, `global`, `relational`, `resolver` (arguments) or `PlaceholderValue` (`asyncSafe()`, `formatted()`, `cacheTicks(n)`, `description(..)`). `resolve(viewer, text)` gives plain text, `component(viewer, miniMessage)` a component with values inserted literally, `miniMessage(..)` the tags for your own parser. Namespaces are removed when your plugin is disabled. `lang.placeholders(true)` lets your language bundle resolve placeholders. Other placeholder plugins are connected through `PlaceholderBridge`; `PlaceholderTesting.detached()` tests your values without a server. Capability `placeholders`, manager override `api.overridePlaceholderHandler(..)`. See "Placeholders (0.65.0)" in `docs/API.md`.
- **Reserved names:** `lang`, `data`, `rel`, `vystorm`, `countdown`, `votes`, `vote` and `guestbook` cannot be namespaces, because braces with these names already mean something in Core or in Vystorm Boards.
- **Your own screen menus as files:** design a menu in the Vystorm menu editor (a web page that will be available on vystorm.net), save it and put the file into `plugins/vystorm_core/menus/`. Core shows it as a screen menu, in the look of the main menu, as a grid of tiles like a chest, or as a free panel: labels, buttons, tiles with item icons, pages on the mouse wheel, a description in the footer while you point at a tile. It opens with `/vmenu <id>`, with a command of its own if the file names one (`/shop`), and from a tile in the main menu (`"mainMenu": false` in the file hides the tile). A click runs commands as the player or as the console, sends a message, opens another menu, goes back or closes, with a permission and a cooldown per click; texts show `{player}` and every placeholder of `/vadmin placeholders`. A menu that is larger than a player's window is shrunk to fit, and where no screen can open the menu is a chest with the same buttons. `/vadmin menus` lists the files and says exactly what is wrong with one that was skipped, `/vadmin menus reload` reads the folder again. A file with a mistake never stops the others or the server. Console commands in a menu run with full rights for whoever clicks: give them a permission or a cooldown (Core warns in the console when a file has neither). Switch: `menus.enabled` in Core's `config.yml` and in `/vadmin features`. Guide with every field: `docs/MENUS.md`. For plugin authors: `api.gui().fileMenus()` (`open(player, id)`, `ids()`, `reload()`, `errors()`), capability `file-menus`.
- **Core's features can be switched off:** new switches in Core's `config.yml`, all on by default, so a server that updates keeps working as before; the new keys are added to your `config.yml` with their comments. Each is listed in `/vadmin features vystorm_core` with a one-line description in seven languages and applies at once.
  - `trade-chat.enabled` (`/vtrade`), `clickable-coordinates.enabled` (off: coordinates in chat are plain text), `tutorials.enabled` (the tutorials of all plugins and the first-steps guide), `setup.enabled` (the setup wizard, the first-run setups, their join hint and button).
  - `diagnostics.enabled` (`/vadmin diagnostics`, `/vmenu access`, `/vadmin migration`, `/vadmin trace`; switching it off also stops a running trace).
  - `loot.enabled`: off, mob drops of all plugins ignore the mob's level (no level luck, no pity) and `/vadmin loot` is switched off. The loot tables stay, because the plugins roll their drops from them.
  - `plugin-worlds.enabled` (`/vworlds`; visits of plugin worlds keep saving and giving back the players' inventory and state) and `region-register.enabled` (`/vregion` and the `region` placeholders, which are empty while it is off; the register itself keeps working for protection plugins).
  - Older keys that could already be switched but were not on the feature screen are listed there now: `menu.sneak-click-shortcut`, `messages.show-source`, `screens.panels`, `screens.inventories`, `screens.tutorials`, `screens.dialogs`, `screens.dialog-inputs` and `metrics` (needs a restart), with `update-check.enabled` and `review-reminder.enabled`.
  - `update-check.enabled`, `review-reminder.enabled` and `messages.show-source` now apply at once instead of after a restart.
  - No switch on purpose: the main menu, the settings screen, the config editor, the feature screen, the language system, `/vhelp`, the event order and the placeholder manager. The other Vystorm plugins are built on them.
  - A switched-off feature answers "This feature is switched off on this server." in seven languages (language key `feature-off`).
  - Tab completion and `/vhelp` follow the switches at once: `/vregion` and `/vworlds` suggest nothing while their switch is off, and `/vtrade`, `/vregion` and `/vworlds` leave `/vhelp` while theirs is off. After a reload that changed a switch the players get their command list again.
- **Commands follow feature switches without a restart (for plugin authors):** `CommandSpec.Builder.visibleWhen(() -> featureOn)` (or `visibleWhen(sender -> ...)`) lists a subcommand or root in tab completion and help only while the condition holds. It is asked at every completion, so a switch applies at once; the command still runs, so its handler can answer that the feature is off. `api.command().refresh()` sends the online players their command list again (once at the end inside `CommandBatch.run`, any thread); call it after a reload in which a switch changed instead of registering the tree again. Registering the same label again after startup on Paper now swaps the tree inside the existing registration: before, Paper kept the old command and the new one only reached `/<plugin>:<label>` with the log line "is taken by". The root label itself stays in the client's command list. Capability `command-visibility`.
- Lighter event order: Core no longer builds a text label for every handler call when it runs the staged event handlers; the label is only made when a handler fails. In a measurement the cost per handler went from about 30 to about 5 nanoseconds, the level of ordinary listeners. Behaviour is unchanged.
- Lighter on busy servers: while no NBT action is registered (the normal case) and the NBT manager is Core's own, Core no longer reads every tag of the player, entity or block on each block break, block place, damage, death, interact, item switch and item damage event. Measured about 1.3 to 1.9 microseconds saved per such event with a player involved.
- Lighter clicks in chests: on every click or drag in any inventory, Core's config menu and the network server selector asked the inventory for its holder to see whether it is their own menu. For a chest, barrel, shulker box, furnace or hopper the server copies the whole block with all its items to answer that, twice per click. Core now looks at the inventory's location first (a menu has none) and skips containers in the world. Measured on a freshly started test server with a full chest: Core's share of a click went from about 125 to about 8 microseconds. Opening a container copies it once instead of twice. Menus behave as before. For plugin authors: `api.compat().inventory().menuHolder(inventory)` does the same check for your own menus.
- Fixed: `/screen classic on` and `/screen classic off` answered with the text key (`screen.classic.on`) instead of a sentence. The same happened with `/vadmin trace` ("trace.on", "trace.off") and with the network commands while the network is switched off ("network.off"). The texts were there in all seven languages, but their keys `on` and `off` are read as true/false by YAML. Language files that are already on your server get the missing texts added at the next start; your own texts stay.
- Fixed: a chest menu of Core or of a plugin that uses Core's managed menus could lose its click protection when Core showed it as a real chest instead of a screen: with screens switched off (`screens.enabled: false`, `screens.off`, `/vadmin screens`), when a screen could not open (riding, falling, in water, right after damage), after the "Chest" button of a chest screen. Players could then take the menu's items out. Such a menu now stays protected however it is shown, and the plugin no longer gets a "menu closed" for that change of view.
- Fixed: in `/vadmin features` one left click on a switch could flip it twice, so it ended where it started and the chat showed "saved and reloaded" twice. A left click reaches the server as two signals; Core counts them as one when they arrive close together, but it measured from the start of the click, and saving with a reload took longer than that. The time now runs from the moment the click has been handled. This holds for every screen whose click takes long.
- Fixed: a click on a screen that closed it or opened another screen (a tile, Back, Close) could reach the world behind the screen. The click counts when the mouse button goes down; the screen was gone at once, and the button, still down, then acted on the block in front of the player: in creative mode the block broke, in survival mining started, soft blocks broke at once, and a right click used the block (lever, door, chest). This affected every screen: panels, the main menu, chest menus shown as screens, menus from files. Now the new screen catches clicks from its first tick (before, there was a gap of two ticks), and for half a second after such a click the player's block break, mining, use of blocks, items and entities, and hits are cancelled; while the button stays down, up to 1.5 seconds (a button held longer than that acts on the world again). While a screen is open these actions are cancelled as well. Closing a screen with the sneak key or `/screen close` starts no such pause, and other players are never affected.

## 0.64.0 — 2026-11-17

- **Everything through the managers:** Core started with one idea: the class `API` is the hub and every area is a manager behind one accessor (`api.gui()`, `api.dialogs()`, `api.settings()` ...). Many newer features were only reachable through free-standing static classes. Now each of them is part of a manager, with the same method names and arguments. `PlatformCompat.sendMessage(p, text)` is `api.compat().platform().sendMessage(p, text)`, `ConfigEditor.register(...)` is `api.settings().config().register(...)`, `AtomicFiles.write(...)` is `api.data().files().write(...)`.
- **Folded into the managers that were already there:** `api.data()` (`files()`, `versioned()`, `migrationPreviews()`), `api.settings()` (`config()` with the feature switches, `prefs()`), `api.message()` (`chat()`, `channels()`, `inbox()`, `news()`, `routine()`), `api.gui()` (`panels()`, `screens()`, `huds()`, `playerMenu()`, `profiles()`, `menuExplorer()`, `onboarding()`), `api.dialogs().buttons()`, `api.command()` (the command trees of `CoreCommands` directly), `api.area()` (`regions()`, `spawnPoints()`), `api.worldGenerator()` (`worlds()`, `environments()`), `api.particle()` (`visuals()`, `paths()`), `api.packages()` (`clientBiomes()`, `clientChunks()`, `clientDisplays()`), `api.admin()` (`updates()`, `diagnostics()`), `api.debug()` (`low/mid/high(message)`, `messages()`, `failures()`), `api.ressource().packs()`, `api.player()` (`character()`, `disciplines()`), `api.events().services()`, `api.lang().coreLanguage()`, and single methods on `api.database()` and `api.structureGenerator()`.
- **New managers** for areas that had none: `api.compat()` (`platform()`, `server()`, `inventory()`, `gameplay()`, `world()`, `storage()`, `items()`, `navigation()`, `passengers()`, `json()`, `clicks()`, `clientEvents()`), `api.tasks()`, `api.loot()`, `api.web()`, `api.parties()`, `api.items()`, `api.mobs()` and `api.runtime()`. Each can be replaced with `api.overrideCompatHandler(...)` and its siblings, like the older managers.
- **Nothing was removed.** Every static class and method keeps working and behaves as before, so plugins built against older Core jars run unchanged. The static service classes say in their Javadoc where they are reached now. Builders and value types (`Lang`, `CommandSpec`, `Arg`, `PanelSpec`, `Ui` ...), `CoreApi` and the test helpers stay static.
- **For tests:** the part for a static class `X` is the interface `XInterface` in the same package, with a constant `DEFAULT`. A class that takes `AtomicFilesInterface` in its constructor is tested with `AtomicFilesInterface.DEFAULT` or a fake, without a server.
- **One order for shared events:** when several plugins handle the same event (block break, interact, damage), the order no longer has to depend on the Bukkit priority each plugin chose. A plugin registers its handler through `api.priority().stages()` with a stage that says what it does (`PREPARE`, `PROTECT`, `ADJUST`, `ACT`, `FINISH`, `OBSERVE`); Core calls all handlers in stage order and, inside a stage, in the plugin order from the new `event-order` section of Core's `config.yml` (plugins not listed: alphabetical). A handler that fails does not stop the others, `OBSERVE` handlers cannot cancel, and handlers of a disabled plugin are removed. Nothing changes for an event until a plugin uses this.
- **Testing staged handlers:** `EventStageTesting.detached()` gives a unit test its own stage registry without a server or static mocks: register handlers as in a plugin, `fire(event)` runs Core's real dispatcher, `priority()` stubs `api.priority()`, `plugin(name)` makes an owner.
- **One way for each job:** where Core had two entries for the same job, one is now the documented way and the older one is marked `@Deprecated(since = "0.64.0")`. Nothing was removed and the old entries behave as before. Use `api.command().register(..)` instead of `registerCommand`/`runCommand`/`getCommandsMenu`; `api.data().files()` and `api.data().versioned()` instead of `loader()`/`creator()`/`saver()`/`deleter()` and the `dataEntry` methods; `api.settings().prefs()` instead of `api.player().getSetting`/`setSetting`/`addStandardSettings`; the language bundle (`api.lang().register(plugin)`, `lang.send(..)`) instead of `api.message().sendMessage`/`addPlaceholder`/`removePlaceholder`; `api.area().regions()` instead of the `Area` methods of `api.area()`; `api.worldGenerator().worlds()` instead of `create`/`unload`; `api.events().domain(..)` instead of registering a source with `WorldEventServices.register`; `api.dialogs().buttons()` instead of `MenuButtons`; `api.nbt().getTag`/`setTag` instead of the same methods on `api.inventoryInteractor()`; `api.debug().low/mid/high(message)` instead of the forms that take a time. Table "One way for each job" in `docs/API.md`.
- **For plugin authors:** a plugin that uses the managers needs Core 0.64.0: check `CoreApi.atLeast("0.64.0")` first in `onEnable`, then keep `CoreApi.api().orElseThrow()` in a field. Capabilities `managers` and `event-stages`. See "The managers (0.64.0)" and "Event order between plugins (0.64.0)" at the top of `docs/API.md`.

## 0.63.0 — 2026-11-17

- **Licence:** from this version Vystorm Core is licensed under the LGPL-3.0 (see `LICENSE` and `COPYING`). Plugins may still link against Core without taking over that licence. Versions up to 0.62.0 stay available under the MIT License.
- **Legacy types deprecated for removal:** these types were never used by Core or by any known plugin. They keep their names and signatures, so plugins built against older jars still load, but they are marked `@Deprecated(since = "0.63.0", forRemoval = true)` and do nothing any more (methods are empty or return null, an empty value, `false` or 0): `AdminComand`, `CloudEntry`, `CSVEntry`, `DataBaseEntry`, `ConvertDatabase`, `EntityToDatabase`, `DefaultSaveSelector`, `SaveSelectorInterface`, `YMLConverter`, `JoinLeaveEntity`, `Functions` (in `_uiMethods.autoGui.methods`), `BookHandler`, `BookInterface`, `BookEditorInterface`, `BookEditor`, `temp`, `AutoChecks`, `AutoComplete`, `CommandGuiSetup` and `MapStorageSingelton`. If you use one of them, switch now: `storage.VersionedStore` and `database.SharedDatabase` replace the data types, `commands.CoreCommands` the command helpers.
- **Internal classes are marked:** implementation classes that are public only for technical reasons now say so in their Javadoc and carry `@ApiStatus.Internal`; they are no longer covered by the API compatibility promise. This affects every class in `network.core`, `network.paper` and `trace`, plus `metrics.CoreMetrics`, `mythic.MythicItemDrops`, `lifecycle.UpdateChecker`, `lifecycle.ReviewReminder`, `chat.TradeChatCommand`, `commands.CommandsAdmin`, `commands.CommandOverview` and `_uiMethods.settings.SettingsDraftDialog`, `FirstRunSetup`, `ConfigHistory`. Nothing was moved or renamed. The public network API stays `Vystorm.vystorm_core.network` (`Network`, `NetworkListener`, `NetworkServer`, `Subscription`). See "Internal classes" in `docs/API.md`.
- **Update check for Winter Is Coming:** the built-in fallback id for old Winter Is Coming versions pointed to the former SpigotMC resource (139912); it now points to the current one (140367). Plugins that register their id through `UpdateSources.spigot` are not affected.
- **Cleaner code, same behaviour:** readable formatting and imports in the entry classes, corrected and translated Javadoc of the public entry points, and English console and log messages where German ones were left.

## 0.61.0 (2026-11-16)

- **Back between screens:** Core remembers which screen you came from. A screen opened from another one gets a Back button (bottom left) that shows the previous screen again: main menu → plugin menu → detail and back, step by step. Closing a screen by hand ends the chain. Works for the main menu, panels, chest menus shown as screens, dialogs shown as screens and the feature switches.
- **Confirmations return to the panel:** a "Really?" question asked by a panel used to replace the panel for good. After Yes or No the panel is shown again.
- **One main menu:** "Home" buttons and plugins that open the main directory now open the same tile screen as `/vmenu` (when `menu.screen` is on and a screen can open).
- **Dialogs with input fields are screens too** (`screens.dialog-inputs`, on by default): one row per field. Switches and choices change with a click, numbers with − and +, and a click on a text or number box opens a one-field typing dialog and returns to the screen with the value. This covers the pay, bank, auction, world-creation and similar forms of every plugin.
- **Chest button on chest menus shown as screens:** next to Close, "Chest" shows the same menu as a normal chest, for everything a screen cannot do: putting items in, shift-clicks, dropping. Plugins can keep a menu as a real chest from the start with `Screens.keepAsChest(inventory)` (trade windows, send slots).
- **A click no longer lands twice:** a left click that opened another screen was counted a second time on the new screen.
- **API:** `Screens.open(player, spec, key, reopen)` makes a screen a place Back can return to; `Screens.back(player)` and `Screens.hasBack(player)`. A widget with the id `vystorm:back` is led to the parent screen by Core.


## 0.62.0 — 2026-11-16

- **Screens can be switched off.** `screens.enabled: false` in Core's `config.yml` turns every Vystorm screen off for the whole server; each menu then shows its fallback (the dialog or the chest it had before). The switch is also in `/vadmin features`.
- **Single menus can keep their fallback.** `screens.off` is a list of names: a plugin (`VystormWorlds`), a group (`panel`, `inventory`) or one menu (`panel:mobs-spawn`, `inventory:VystormWorlds`, `vystorm_core:menu`, `vystorm_core:tutorial`, `"dialog:XP banks"`).
- **`/vadmin screens`** shows the state, the names switched off and the names of all menus opened since the start, and edits both settings: `/vadmin screens all on|off`, `/vadmin screens off NAME`, `/vadmin screens on NAME`. Changes apply at once.

## 0.60.0 (2026-11-16)

- **Feature switches:** `/vadmin features` (also `/vmenu` → Administration → Features) shows every plugin with its features as a Vystorm screen. One row per feature, green ON or red OFF; a click switches it and saves through the config editor (backup, history, the plugin's reload, "restart needed" notice). Hovering shows the description, the mouse wheel scrolls. `/vadmin features <plugin>` opens one plugin. Without a screen the config editor opens.
- Core finds the switches itself: every boolean key named `enabled` in a registered `config.yml` (`trains.enabled`, `travel-gates.energy.enabled`) and every boolean at the top level of the file (`speedometer`, `auto-protect`; not housekeeping such as `debug` or `metrics`). Config keys are not renamed, so existing configs keep working. Admins need `vystorm_core.config.<plugin>`.
- **`/vmenu` opened the old menu instead of the screen:** when a screen was already open, every further screen fell back to the classic menu without a word. A new screen now replaces the open one. When a screen cannot open (riding, gliding, falling, in water, just hit) `/vmenu` says why before it shows the classic menu.
- **API:** `ConfigEditor.feature(owner, path)` and `feature(owner, path, label, description)` declare switches with other names, `notFeature(owner, path)` excludes one, `openFeatures(player[, plugin])` opens the screen; capability `feature-switches`.

## 0.59.0 (2026-11-15)

- **Dialog menus as screens:** plugin dialogs without input fields (menus, guides, confirmations of every plugin that uses Core dialogs, e.g. BlockIntegrations) now open as screens in the same look as the other menus: title, text, a button per choice and Close. Dialogs with text or option fields stay native dialogs. Config `screens.dialogs`.
- **Maps in screens:** pinned screens can show map tiles (up to 8x8 tiles of 128 px) with pixels sent only to the viewer. They are drawn through the Core pack shader, so they need the pack and no shader pack (Iris: `/screen classic on`). Floating screens show a placeholder text. Admins can try it with `/screen demo map`: the loaded world around you, sneak zooms in, jump zooms out, a click shows the coordinates.
- **Black backdrop is really opaque now:** clouds, sign texts and other world details no longer show through it.
- **Pointer tip = click point:** the arrow was drawn centred on the click point, so its tip sat a little left of and above where clicks land; now the tip is the hotspot.
- **Backdrop closed on ultrawide screens:** blocks are hidden as far out as the backdrop reaches at the corners of your screen (from your display settings), and a bit more for bushy-leaves resource packs, so nothing pokes through at the sides.
- **Boxes centred:** panels, backdrops, buttons and rows were drawn slightly right of their texts (by a sixteenth of their width); now they line up.
- **No hand, no rain in screens:** while a pinned screen is open your own arm and held item are hidden (empty hotbar slot, restored on close) and rain is turned off for you only.
- **Updated texts reach existing servers:** Core now remembers the plugin texts it last wrote (`<code>.yml.defaults` next to each language file); texts an admin never edited follow plugin updates, edited ones stay. Servers from before 0.59.0 start remembering with this update.
- **Translations:** screen texts in French, Italian, Portuguese and Russian.
- **Tutorials as screens with build GIFs:** a tutorial opens as a screen – its page's build animation (`gif: tutorial/NAME.gif` in `tutorial.yml`, a file in the plugin jar) plays on map tiles next to the text, with back/next. Config `screens.tutorials`; without screens the dialogs stay.
- **Button text centred** vertically in small buttons and tabs.
- **HUD corner window:** `Huds.of(plugin).show(player, HudCard, ticks)` shows a small panel at the top right (icon, title, value, segmented bar, footer; gold highlight for level-ups). Built from boss bar titles in the GUI layer and placed by the pack shader, so it works with shader packs and every resolution. WHITE boss bars draw no bar with the Core pack. Capability `hud-window`; `/screen demo hud`.
- **Docs for AI assistants:** `llms.txt` and `docs/AI-GUIDE.md` (all 105 capabilities with entry points, rules and checked examples) ship in the repository and inside the jar under `META-INF/vystorm-core/`; the capability table in `docs/API.md` is complete again.
- **GIFs on maps:** `/screen demo gif NAME` plays `plugins/vystorm_core/screens/NAME.gif` in a screen (frames converted to map colours with dithering, sent only to the viewer at the GIF speed, looping).
- **API:** `ScreenWidget.map(...)`, `ScreenWidget.MapGrid`, `mapPixel(...)`, `ScreenSession.setMap(id, bytes)`.

## 0.58.0 (2026-11-15, released with 0.59.0)

- **Panels as real screens:** plugin panels are no longer drawn as a dialog-style list on a screen. They now get a proper layout: header with title and close button, sections and cards, rows and grids, tabs, tables with sorting and pages, lists with icons and selection, progress bars, key–value lists, toggles, number fields with −/+, choice fields and forms. Text fields open a short typing box and return to the screen. The mouse wheel scrolls long content, and the footer shows what the item under the pointer does.
- **Chest menus as screens:** chest, hopper and dispenser menus of every plugin that uses Core now open as a screen in the same look: item tiles with name and count, the full name and lore in the bottom line. The menu is really open on the server (only its window stays off the client), so plugins work unchanged and get their normal close event; clicks reach them as normal chest clicks. Menus where items really move switch to the real chest by themselves. Config `screens.inventories` and `screens.inventories-exclude` (default: DeepInventory, SkyInventory, Limitless).
- **Language menu:** `/language` is a panel now: flag, name, code and coverage per language, automatic first.
- **Screens look:** buttons and rows have full-size boxes; the chosen tab and list row are highlighted in gold.
- **API:** `ScreenWidget.fill(...)` (plain rectangles), `withFill`, `withAlign`, `withSelected`, `withHighlight`.

## 0.57.0 (2026-11-15, released with 0.59.0)

- **Vystorm Screens (prototype):** clickable screens in the player's view without a client mod.
  - PINNED screens stay fixed on the monitor and get a mouse pointer that follows the head. The pointer comes from a small `text.vsh` addition in the Core resource pack.
  - FLOATING screens float in front of the player and use the crosshair as cursor. They are used when the pack is missing.
  - Admins can try it with `/screen demo [pinned|floating]`. `/screen close` closes an open screen, and so does sneaking.
- **Menu shortcut:** hold sneak and left-click twice within 0.25 s to open the Vystorm menu (`/menu`). This also works with an empty hand into the air. It does nothing while a screen is open. Config `menu.sneak-click-shortcut`.
- **API:** `screen.Screens`, `ScreenSpec`, `ScreenLayout`, `ScreenWidget`, `ScreenInput`, `ScreenSession`; capability `screens`. The API is free for every plugin.

## 0.56.0 (2026-11-12)

- **Player profile cards:** `/vmenu profile [player]`, the main menu and the player menu (sneak + right-click a player) show one card with first join, last seen, play time and a section from every Vystorm plugin that has something to say about the player.
- **API:** `profile.Profiles.register(plugin, provider)`; capability `profiles`.

## 0.55.0 (2026-11-11)

- **New inbox:** `/vmenu inbox` (main menu "Inbox") keeps short personal messages from the plugins – also about things that happened while you were offline. A clickable line tells you about new ones; each message is shown in your own language. Settings under `inbox` in config.yml.
- **API:** `inbox.Inbox.send(plugin, player, key, placeholders…)`, `sendText`, `unread`, `open`; capability `inbox`.

## 0.54.0 (2026-11-09)

- **New admin config menu:** `/vadmin config [plugin]` opens chest pages (overview, plugin, category, options) with current and default values, changed markers, reset, search and an only-changed filter. Lists of entries can be edited in the menu.
- **First-run setups:** plugins can ship a `setup.yml`. Admins get a clickable hint for new installs, answer a few questions or take the recommendations, see a preview and confirm. `/vadmin setup [plugin]` runs it again; existing installs are not asked.
- **API:** `ConfigEditor.registerAll(owner, reload)`, capability `config-menu`.

## 0.53.0 (2026-11-08)

**Experimental:** add a cross-server network behind Velocity or BungeeCord/Waterfall (proxy support is still experimental; please report problems). The same Core jar now also starts on the proxy (`velocity-plugin.json`, `bungee.yml`), where it only runs the network hub: no menus, worlds or database.

- **Zero configuration with Velocity modern forwarding:** hub and servers derive the network key from the forwarding secret (`forwarding.secret` / `paper-global.yml`, only when `proxies.velocity.enabled` is true). The proxy announces each server's name and the hub address when a player joins; servers remember it in `plugins/vystorm_core/network/hub.properties` and reconnect on their own after restarts.
- **BungeeCord/Waterfall or no forwarding secret:** each server pairs once. Both consoles show the same 6-character code; confirm with `/vnet pair accept <code>` on the server. X25519 key agreement, nothing secret travels over the plugin channel, a second answer cancels the offer, and the hub stores a key only after that server authenticated with it.
- **Security:** mutual HMAC challenge–response, AES-256-GCM with per-direction session keys and implicit sequence numbers (tampering, replay and reordering close the connection), hub bound to `127.0.0.1:25599` by default with optional allow list, IP ban after 5 failed logins per minute, 1 MiB frame limit, keys never logged (fingerprint only). Client plugin messages on `vystorm:net` are dropped by the proxy.
- **Features:** network-wide player list, server selector in the main menu (only while connected; per-server display name, icon and visibility under `network.servers`), `/vnet status|servers|broadcast|pair accept|unpair` (permissions `vystorm_core.network.status|broadcast|admin`), announcements that also reach empty servers. Proxy console: `vnet` and `vnet unpair <server>`; hub settings in the proxy's `plugins/vystorm_core/network.properties`.
- **API:** `CoreApi.network()` returns `Network` (never null; a no-op without proxy): `connected()`, `serverName()`, `servers()`, `serverOf(UUID)`, `sendPlayer(UUID, String)`, `send(server, channel, bytes)`, `sendToPlayer(UUID, channel, bytes)`, `broadcast(channel, bytes)`, `subscribe(channel, listener)` (listeners on the main thread). Capability `network`, since 0.53.0. Channels `vystorm:*` are reserved.
- Verified on a local Velocity 3.5.1 network with two Purpur 26.2 servers (connect, server list, broadcast to an empty server, reconnect 5 s after a proxy restart); Core also loads as hub on Velocity 4.3.1 and Waterfall 1.21. Paper/Spigot servers without a proxy behave as before. Folia is not supported.
- **`/vadmin trace [player] [break place damage interact messages | off]`:** names the plugin and listener class that cancels (or allows again) a traced player's block break, place, block damage or interaction — for any plugin, not only Vystorm ones. With a filter only those kinds are shown; without a filter the command toggles a full trace. Messages sent through Core are reported with plugin and text key too. Listeners are only wrapped while a trace is active.
- **Message source on hover:** chat lines sent through Core show which plugin sent them (`messages.show-source`, default on).
- Tests now pass long classpaths through files, so the suite also runs on Windows.

## 0.52.0 (2026-11-07)

Add a guided setup wizard at `/vsettings setup` (also `/einstellungen setup`). It discovers installed plugins with registered, visible server settings, captures named shared presets from explicitly selected sections, lets administrators select the target plugins and previews every before/after value and activation policy before confirmation.

Presets use the existing `settings-profiles/<name>.yml` format, per-provider version checks and bounded settings drafts. Select section keys shown by the wizard; capture supports at most 96 scalar values. Config-editor sections are allowed only with explicit selection; broad legacy exports still exclude whole configs, and secrets, hidden values and unsupported structures stay excluded. Existing presets can be edited while the server is stopped. Missing selected settings, revoked permissions, version mismatches and stale baselines block application. Each provider retains its own save/reload hooks. Cross-plugin application is not a database transaction: a provider failure is reported as a partial result, and config-editor history/backups remain available for review.

API additions: `SetupWizard.open(Player, SettingsRegistry)`, `SetupWizard.plugins(SettingsRegistry, Player)`, `SettingsProfiles.exportProfile(..., Set<String> selectedSections)` and `SettingsProfiles.importProfile(..., Set<String> selectedPlugins)`; capability `setup-wizard`, since 0.52.0. Existing methods and provider lifecycles remain unchanged.

## 0.51.0 ? Bukkit and Spigot compatibility

- Add the shared `bukkit-compat` capability and portable adapters for components, menus, commands, experience, teleports, gameplay and world operations. Paper keeps its native paths.
- Load Adventure API, MiniMessage and serializers through Bukkit's library loader. Chat click/hover actions retain JSON components on Spigot; callbacks expire and are released when their providing plugin disables.
- Isolate Paper-only inventory and client events. Bukkit inventory changes now also observe player pickup; actual client chunk packets and virtual entity interactions feed the existing display, biome and preview services.
- Preserve item components, persistent data and stored entities through native versioned NBT. Read existing Paper inventory-array frames as well as earlier Core frames on Spigot; reject malformed snapshots before restoration.
- Preserve online skin properties using the live server profile. Add native navigation, passenger-aware teleport, scoreboard, freeze and world helpers for dependent plugins.
- Preserve optional range-slider label formats when converting portable dialogs to native Paper dialogs.
- Add focused fallback, persistence-frame, callback lifecycle and genuine Spigot API class-loading tests. See `COMPATIBILITY.md` for the precise runtime evidence and limits; the historic Paper 1.20.6 minimum does not imply an untested Spigot 1.20.6 claim.

## 0.50.0-Vystorm-Core-26.2 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Include translated tutorials in all four languages.
- Accept the translated words for automatic language selection.
- Quote YAML keys such as `yes`, `no`, `on` and `off` so the server does not reinterpret them as booleans.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

# 0.49.0

- Fix the untranslated `common.close` label in menu search and other dialogs in all three bundled languages, found during the real-client review.
- Let players clear their own recent-menu history from the Recent tab in menu search. Favorites stay intact; English, German and Chinese labels are included.
- Add `MenuExplorer.clearRecent(Player)` (server thread) and capability `MENU_HISTORY_CLEAR` / `menu-history-clear` since 0.49.0.

## 0.48.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode, `api-version: 1.20.6`; newer servers still require their own Java version. The offline `compat1206` check compiles main against the local 1.20.6 libraries and runs with `check`.
- Native dialogs, custom-click events, modern spawn events and hanging-moss handling live in an optional adapter source set, included in the same JAR. Existing public native-dialog signatures remain available. Core menus, settings, news and panel forms use clickable chat with validated input and expiring sessions when native dialogs are absent. Pause-menu/quick-action data packs are skipped there; `/vmenu` remains available.
- Additive `server-compat` capability and public `compat.ServerCompat` helpers for feature detection and material/entity/sound lookup with fallbacks; portable `compat.dialog` form descriptions work across the supported range.
- Older-server fallbacks for item metadata, item arrays, renamed attributes, world folders, Gson and IP literals. Pale oak falls back to oak and vines; item-model hints are omitted when unavailable. Item arrays written on 1.20.6 can be read after upgrade; newer Minecraft item data must not be restored on an older server.
- Client-only biome effects retain their verified 26.2/26.3 restriction; client-only displays/chunks are available only when their runtime packet probes succeed. Other Core services remain usable.
## 0.47.0 (2026-11-03)

- New: timed server polls with clickable, changeable single-choice votes, join visibility, automatic result announcements, early close/cancel and the last result available until restart. `/vpoll start <seconds> <question> | <answer> | <answer>` requires `vystorm_core.admin`; reading and voting use `vystorm_core.poll` (everyone). UUID-based votes survive reconnects within the running poll; stale links cannot vote in a later poll.
- Config: `polls.enabled`, `polls.max-duration-seconds`, `polls.max-options`, `polls.max-text-length`, with safe defaults merged into existing files. English, German and Simplified Chinese included. Additive capability `server-polls`; no existing API changed.

## 0.46.0 (2026-11-02)

- New: edit existing server news in the news dialog or with `/vadmin news edit <number> [title | text]`. Without text, players get a prefilled editor; the console supplies the replacement title and text. Corrections retain the post ID, original author, date, order and read status, and do not send another announcement. Uses the existing news write permission; deleted posts are never recreated by an open editor. English, German and Simplified Chinese included. Capability `news-edit`.

## 0.45.1 (unreleased)

Version bumped to 0.45.1-Vystorm-Core-26.2 (hotfix for 0.45.0).

- Fix: `/vadmin` modules of plugins whose command is a Core tree (`CoreCommands.register`, no plugin.yml entry) showed as unavailable and their actions answered "wrong owner": the dashboard now runs the owner's registered tree (English label or fixed alias) when the plugin has no PluginCommand of that name.
- Fix: `/vadmin commands` now also lists fixed aliases that another plugin's command already has. The server keeps the first one, so such an alias runs the other command (found on a test server: Vystorm Chat's `/m` for `/msg` opens Core's `/menu`, because `/m` is Core's menu alias); `CoreCommands.find` no longer returns a tree for such an alias.
- Client-only chunks (`packet.ClientChunks`, see 0.45.0) are now also in the SpigotMC download. The 0.45.0 file on SpigotMC was built just before they were merged; the Modrinth and Hangar files of 0.45.0 already had them. Nothing else differs.

## 0.45.0 (2026-10-31)

- New (Modrinth and Hangar files only; SpigotMC got it with 0.45.1): client-only chunks (`packet.ClientChunks`, capability `client-chunks`). Whole chunks that exist only on one player's client, sent as ordinary chunk packets, for previews of terrain the server has not generated yet (Vystorm Strata's terrain preview). Describe the chunk with a `Draft` (blocks, biome per column) on any thread, `prepare` builds the packet with the server's own chunk serializer (a detached chunk that is never added to the world, sky light computed from the blocks) and measures it (payload and deflated size), `send` only goes out while the server has not sent the real chunk to that player, and when the server sends the real chunk later it replaces the client-only one on the client and the owner's `onReplaced` listener runs. `forget`/`forgetAll`/`release` remove them again (never a real chunk). One reflective adapter, probed once by building and encoding a test chunk: checked on Paper 1.21.8, 1.21.11, 26.1.2 and 26.3, Purpur 26.2 and 26.3 (26.3 made the packet a record and dropped the climate sampler from biome resolvers, both handled); a protocol bot on Purpur 26.2 received about 1,600 preview chunks, 1,086 of them were later replaced by the real chunks and none was ever sent over a real one.
- Fix: tab completion of a trailing greedy argument (`Arg.greedy`) now asks its suggester for every word, not only the first one.
- Fix: visited worlds' command allow-lists accept the active localized root aliases of an allowed Core command (`/aufwachen` when `wake` is allowed); the alias must belong to that command's plugin.
- Fix: `adopt` no longer offers a localized root alias that is already a plugin.yml alias of the command (it showed up as a collision with the plugin itself).
- New: central command system (capability `commands`, package `commands`, docs/API.md §24). Plugins declare a command tree (`CommandSpec.command("rails").sub("dest", s -> s.arg(Arg.greedy("name").optional()).executes(...))`) and register it with `CoreCommands.register(plugin, lang, spec)`. Core parses and checks it (permissions, player-only, per-player cooldowns, async handlers, argument types: word, greedy, integer/decimal ranges, online player, player name, world, choice, enum, on/off, custom with own suggestions) and localizes everything a player sees from the plugin's language files: subcommand names (`commands.<cmd>.sub.<id>.name`, `.aliases`), keyword values (`on`/`an`/`开`, shared in Core's `command.values.*`), tab completion, usage lines, generated clickable and paged help (`/<cmd> help`, `hilfe`, `帮助`, `?`) and the error messages (no permission, player only, unknown subcommand with a clickable "did you mean", missing/bad argument with the usage line, cooldown). Every installed language's names are accepted for everyone (`/rails ziel` = `/rails dest` = `/rails 目的地`); completion and help use the player's own language and follow `/language` at once (`PlayerLanguageChangeEvent`). Registered through Paper's Brigadier lifecycle API while plugins start, otherwise through the Bukkit command map; works from the console.
- New: localized root aliases. Command labels are global on a server, so the English label and the fixed aliases always exist and the aliases from `commands.<cmd>.aliases` (e.g. `/schienen`, `/铁轨` for `/rails`) are registered server-wide as the admin chooses: `config.yml` `commands.localized-aliases: all` (default; every installed language), `default` (only the server language) or `none`; single aliases can be switched off (`commands.disabled-aliases`). They are applied on the first server tick, after every plugin's own commands exist, and never take a label another command has (skipped and shown as collision).
- New: `/vhelp [plugin] [page]` (`/vhilfe`, `/befehle`, `/v帮助`, `/vcommands`): all commands of all Vystorm plugins the player may use, grouped by plugin, in the player's language, clickable (opens the help or types the command). Plugins that have not migrated are listed from plugin.yml; they can localize their line with `commands.<name>.description`.
- New: `/vadmin commands [page]`: every registered tree with mount (paper, bukkit, adapter), fixed aliases, localized aliases with state and the owner of a colliding label, clickable on/off per alias, subcommand name conflicts per language and the plugin.yml commands not migrated yet; `/vadmin commands mode all|default|none` and `/vadmin commands alias <alias> on|off` write `config.yml` and apply at once.
- API: `CoreCommands.adopt(plugin, lang, declaration)` wraps an existing plugin.yml command for step-by-step migration: the old executor gets localized names translated to the English ids, Core adds help, merged localized completion, root aliases and the `/vhelp` entry. `CoreCommands.find(label)` resolves localized aliases (for mute filters and similar), `CommandRegistration.execute/suggest/help/usage` run a tree directly, `CommandTesting.detached(...)` tests a tree without a server. New permission `vystorm_core.commands.bypass-cooldown` (op). German migration guide with an estimate per plugin: `docs/COMMANDS-MIGRATION-de.md`.
- New: Simplified Chinese (`zh_cn`) as a first-class language. Core ships a complete `lang/zh_cn.yml` (all keys of `en.yml`, Minecraft's official zh_cn terms such as 物品, 方块, 世界, 菜单, 设置; every `{placeholder}`, MiniMessage tag and `&` code unchanged, checked by tests). Clients with the Minecraft language 简体中文 get it automatically; 繁體中文 clients (`zh_tw`, `zh_hk`, `zh_mo`) too while no own file exists. Plugins add `src/main/resources/lang/zh_cn.yml` the same way; it is copied to `plugins/<Plugin>/lang/zh_cn.yml`. The web panel (`/core/i18n`, language switch) serves `zh_cn` as well; `Accept-Language: zh-CN`, `zh-TW` and `zh` resolve to it.
- New: `/language` (aliases `/lang`, `/sprache`, `/语言`; permission `vystorm_core.language`, everyone). Without arguments a chest menu with "Automatic (client language)" and one flag-like banner per language: its own name, code and how much of it is translated over all plugins; the current choice glows. `/language <code|auto>` sets it directly (also `zh-CN`, `de_at` → `de`, or the language's own name, with tab completion), `/language list` lists. Admins: `/language <player> [code|auto]` shows or sets another player's language, also from the console (`vystorm_core.language.others`, op, part of `vystorm_core.admin`). The choice is the same stored value as `/vsettings` → Language (which stays), so both are always in sync; the `/vsettings` option list now follows the available languages and refreshes after plugins load and on reload.
- Offered languages: the union of the language files of all registered bundles, shown when Core has the language or its coverage over all plugins reaches `language.min-coverage` (default 50 %), so a plugin-only file with a handful of texts does not appear as a language.
- New: fallback chain. Missing texts fall back key by key along `zh_tw → zh_cn → en`, `de_at → de → en`, `de_ch → de → en` (code, configured step, language part, English); also used to pick a file for a client locale. Built-in steps `zh_hk`/`zh_mo` → `zh_tw`, `zh_tw`/`zh_sg`/`zh` → `zh_cn`, `pt`/`pt_pt` → `pt_br`, `nn`/`nb` → `no`; `language.fallback` in Core's `config.yml` adds or removes steps (`fr_ca: fr`, `zh_tw: none`).
- New: `language.server` (default `en`): language of the console and of players whose language has no file (instead of always English), and `language.force` (default `false`): everyone gets the server language, `/language` explains that the server uses one language (choices are kept for later).
- `/vadmin language audit [plugin|*] [lang]` now reports per plugin and language the coverage, missing keys, untranslated keys (identical to English) and placeholder mismatches with counts; filtered by plugin or language it lists the keys. Every line has a clickable **[export]**.
- New translator workflow: `/vadmin language export <plugin> <lang> [yml|csv]` writes every key with the English source, the current translation and a status to `plugins/vystorm_core/translations/<plugin>.<lang>.yml` (CSV with BOM for spreadsheets); `/vadmin language import <file> [plugin] [lang]` merges a reviewed file into the plugin's override folder `plugins/<Plugin>/lang/<lang>.yml` (never the jar), keeps the old file as `<lang>.yml.bak-<date-time>`, skips unknown keys and texts whose placeholders differ from the source, and reloads the bundle. Admin-edited files keep surviving plugin updates: only new keys are added from the jar.
- `/vadmin language reload` reloads all bundles and the `language.*` settings without a restart, refreshes `/vsettings` and tells plugins (event below). Saving Core's config in the config editor does the same.
- Width of Chinese text: new `lang.TextWidth` (display columns with full-width characters counting 2, approximate default-font pixels, CJK-aware wrapping that never starts a line with `，。！？` and keeps `“（` with the following text, centring, padding, truncating, colour codes carried over) and `Lang.wrappedLines(...)` for item lore; the language menu uses it. Command hints such as `<plugin>` after a full-width colon (`：/vadmin …`) are no longer reported as unfilled tags by the audit.
- API: `Localization.fallbackChain`, `fallbacks`, `coverage`, `selectableLanguages`, `serverLanguage`, `forced`, `minCoverage`, `configure(ConfigurationSection)`; `Lang.coverage`, `counts`, `keys`, `translation`, `bundledTranslation`, `folder`, `audit(lang)`, `wrappedLines`, `Audit.untranslated()`/`coverage()`; `PlayerLanguageChangeEvent` (after `/language`, `/vsettings`, admin change, a new Minecraft language while on "Automatic", reload; plugins redraw boards and menus there) and `CoreLanguage.setChoice(player, code, cause)`; capability `language-selection`. See docs/API.md §4.3.
- Fixed: YAML documents loaded through Core (data entries `core.data()`, `AtomicFiles.readYaml`, the config editor) kept every SnakeYAML node of the file in memory for as long as the document lived. Bukkit's `YamlConfiguration` builds its values node by node, and SnakeYAML's per-document node cache is never cleared on that path, so each loaded entry held two `ScalarNode`s with their `Mark`s and comment lists (about 500 bytes per entry), and every reload of the same object added another set. Core keeps its data entries for the whole server run: on a live server a plugin with 270,000 chunks in such entries held about 900 MB this way (heap summary 30.10.2026: 7.2 million `Mark`, 3.6 million `ScalarNode`). Core now empties that cache right after loading (`YamlNodes.release`, what SnakeYAML itself does at the end of a document). Nothing that was loaded changes; on a Bukkit/SnakeYAML without this cache it does nothing. Measured with the old WorldForge and 270,000 chunks: live heap 2.45 GB → 1.51 GB. Plugins that keep their own `YamlConfiguration` objects can call `YamlNodes.release(config)` after loading.
- Minecraft 26.3: client biomes (`ClientBiomes.snow`, `project`; Winter's packet snow, Portals' biome preview) now also run on Paper/Purpur 26.3. `ClientBiomes.supported()` accepted only 26.2, so on 26.3 Winter fell back to particles. 26.3 keeps the 26.2 biome packet format (checked against the 26.3 server jars: same `ChunkBiomeData` constructors, ChunkPos + byte array codec, unchanged palette encoding) and was tested with a protocol bot on Paper 26.3 #231 and Purpur 26.3 #2655. Other versions are still reported as unsupported.
- Tested on Paper 26.3 #231 and Purpur 26.3 #2655: client displays (`ClientDisplays`) pass their packet probe unchanged, see `COMPATIBILITY.md`.

## 0.44.0 (2026-10-30)

- New: server news board. Admins write short posts in game (`/vmenu news` → Write, a dialog with title and text) or with `/vadmin news post <title> | <text>` (also from the console); `/vadmin news [list|write|delete <number>]` manage them. Players who have not read a post get one clickable chat line when they join, online players get a line as soon as a post is published, and the main menu shows "News (2 new)". Clicking opens all posts in a dialog, writers also see Write and Delete buttons there. Players can switch the join line off in `/vsettings` → News. Posts are plain text (no colour codes), stored in `news.yml`, hidden and removed after `news.max-age-days` (30) and capped at `news.max-posts` (20). Settings under `news` (`enabled`, `notify-on-join`, `announce-new-posts`, `max-age-days`, `max-posts`); permissions `vystorm_core.news` (everyone) and `vystorm_core.news.write` (op, included in `vystorm_core.admin`).
- API: `news.News.post(plugin, title, text)`, `posts()`, `unread(player)`, `open(player)`, capability `news`, so plugins can announce events on the board.
- New: change history of the config editor with undo. Every value saved through `/vadmin config` (sections, drafts and settings profiles) is recorded in `config-history.yml` with admin, time, plugin, file and value before and after (secrets never). `/vadmin config history [plugin] [page]` lists the changes newest first, each with a clickable **[undo]**; the config menu has a "Change history" button. Undo puts the old values back with the usual backup and reload and is recorded itself. If a value was changed again since then, Core lists it and only overwrites it with "undo anyway" (`/vadmin config undo <number> force`). Only plugins you may configure (`vystorm_core.config.<plugin>`) are shown. Settings under `config-history` (`enabled`, `max-entries` = 200).
- API: `ConfigEditor.history(viewer, plugin, page)` and `ConfigEditor.undo(viewer, id, force)`, capability `config-history`.
- Update check fallback list now also knows Vystorm Rails (SpigotMC 140076).
- Loot now uses Vystorm Core weighted loot tables everywhere: one model of tables → categories → rolls (see `docs/LOOT.md`).
- New: basic vanilla loot lists in `loot-vanilla.yml`, standard Minecraft items only: tiers `vystorm_core:vanilla/common`, `uncommon`, `rare`, `epic`, `legendary` and themes `food`, `ores_ingots`, `tools_weapons` (with enchanted variants), `armor`, `building`, `redstone`, `magic` (books, potions, XP bottles), `nether`, `end`, `ocean`, `treasure` (discs, trims, sherds), `fishing`, plus mixes `vystorm_core:default/<situation>`.
- New: loot categories in `loot-categories.yml` (`category:dungeon_chest`, `boss_chest`, `mineshaft_cart`, `treasure_vault`, `cave_loot_room`, `sky_temple`, `sky_wreck`, `fishing`, `expedition`, `mob_drop_common`/`_elite`/`_boss`, `quest_reward`, `contract_reward`, `arena_reward`, `event_reward`, `farm_rare_find`, `gathering_find`, `shrine`, `meteor`). Vystorm plugins roll their category with their own list; by default nothing changes for them, and admins can retune e.g. all boss chests of all plugins in one place.
- Loot tables can be `mode: each` (every entry checked with its own `chance`, for classic drop lists). Loot entries can now reference other tables (`table: <id>`, `table: self`), enchant items (`enchant-levels`, `treasure-enchants`), set a `potion` type, have conditions (`min-y`, `max-y`, `environments`, `biomes`, `min-difficulty`, `max-difficulty`) and be a `fallback`. Rarity tiers: common 0-3999, uncommon 4000-6999, rare 7000-8999, epic 9000-9799, legendary 9800-10000.
- New admin commands: `/vadmin loot list [tables|categories]`, `show <id>`, `roll <id> [luck= tier= min-tier= pity= rolls= seed= source= self= difficulty= y=]` (preview, nothing is given) and `simulate <id> <n> [options]` (distribution per tier and item), `give <id> [player] [options]` (gives one roll for testing), `reload`.
- API: `loot.LootRoll` (luck, tier/weight filters, pity, unique, exclude, seed, context; pure `roll()` for async populators, `generate()`), `LootResult` with hit metadata, `LootTier`, `RollContext`, `LootLuck` (player luck = Luck attribute + plugin sources), `LootTables.choose/exists/owner`, `LootTableConfig.load(plugin, files...)`, `LootTable.ranksFor(...)` for converting old relative weights; capability `loot-rolls`. Plain tables roll exactly as before (same random sequence).
- Mob drops follow one rule in every Vystorm plugin: **what** drops is defined by the mob (its own loot table), **how good** only by the mob's level. New `loot-mobs.yml` with a bonus table per vanilla mob type (`vystorm_core:mob/zombie`, `skeleton`, `creeper`, `enderman`, `blaze`, `drowned`, `warden` ... 39 types, themed: zombies food/iron, skeletons bones/arrows/bows, creepers gunpowder/redstone, endermen pearls/end, blazes rods/nether, drowned ocean/copper with a rare trident; `vystorm_core:mob/default` for the rest). Luck of a mob roll = `min(max, level × per-level)` (`loot.mob-level-luck.per-level` 0.05, `max` 3.0 in `config.yml`); player luck (Luck attribute, item or pet bonuses) no longer changes mob drops, only chests, fishing and finds. Pity per rank: `loot.mob-pity.common/elite/boss` (default none/none/rare). `/vadmin loot roll|simulate ... level=40` previews a mob roll.
- New: client-only displays (`display.ClientDisplays`, `MapWall`, capability `client-displays`). Walls of map item frames that exist only as packets on nearby clients: no server entities, no entity ticking, no server maps and no server-side map rendering, without ProtocolLib or PacketEvents. Frames are spawned per viewer within a view distance (default 48 blocks, same world, chunk sent), again after chunk re-delivery, respawn and world change, and dropped on quit. Map colours go out as the changed rectangle per tile, limited per tick (8 map packets per player, 64 in total, 128 frame spawns per player; `ClientDisplays.budgets`). Shared and per-viewer pictures. Clicks come from Paper's `PlayerUseUnknownEntityEvent` (one per right click, left clicks too, vanilla reach rule) with tile and pixel. Entity ids come from the server's own counter, map ids from the reserved range 2130706432 up to `Integer.MAX_VALUE`. Walls of a disabled plugin are removed. One reflective adapter for Paper/Purpur 1.21.8 to 26.2, probed once by encoding each packet with the server's codecs; `supported()` is false (with `unsupportedReason()`) on anything else. `stats()` reports packets, bytes, ids in use and tick time. Measured with 200 boards (2757 tiles) and 5 protocol bots on Paper 1.21.8: 0 instead of 2757 entities, live heap 366 instead of 865 MB, MSPT 7-12 instead of 10-23 ms, about 36 instead of 57 KiB/s per viewer after compression (details in docs/API.md).
- API: `loot.MobLoot` (capability `mob-loot`): `luck(level)`, `table(EntityType)`, `Rank` (common/elite/boss category), `claim(plugin, entity)` / `owner` / `ownedByOther` (who rolls a kill), `markRolled` / `rolled` (never two rolls per kill), `levels(plugin, provider)` / `level(entity)`, `contribute(plugin, Contribution)` / `extras(...)` (extra drop parts, e.g. VystormItems gear, rolled inside the owner's kill roll) and `drop(...)` (the whole kill roll). `LootRoll.forMob(...)` and `LootRoll.mobLevel(level)`.

Fixed:

- Client biomes: players changing world or leaving made the biome sender throw ("Task #5 generated an exception", Paper has no chunk loader for them for a moment). Their sent-chunk queries now count as "nothing sent" and the player is simply handled in a later tick.
- Config history: undoing an undo marks the original change as active again (no more "undone by #N" and the undo button is back), since its values apply again.
- An unreadable `news.yml` or `config-history.yml` (for example after a hand edit with a YAML error) was loaded as empty and overwritten by the next post or config change, so all posts or the whole history were lost. The file is now moved aside as `<name>.broken-<date-time>`, the console explains how to restore it, and nothing is saved over it until the next restart. The same for `reviews.yml` (review reminder state).
- Generating the web gateway secret no longer rewrites an unreadable Core `config.yml` with only the secret in it, and writes the file atomically.
- XML data entries are now saved atomically (temp file + move) like YAML entries.
- API: `AtomicFiles.moveAside(path)` moves an unreadable data file aside as `<name>.broken-<date-time>`.
- `menu.yml` and `resource-pack.yml` were written directly (an interrupted write could empty them), and an unreadable one (for example after a hand edit with a YAML error) was silently reset to the defaults, losing the pause-menu or resource pack settings. They are now written atomically; an unreadable file is moved aside as `<name>.broken-<date-time>`, the console says so, the defaults are used in memory and the file is not written again until the next restart.
- API: `AtomicFiles.readYaml(path, logger)` loads a YAML file and moves it aside when it is unreadable (returns `null` then).

## 0.43.0 (2026-10-29)

- New player menu: sneak and right-click another player with an empty main hand to get a chest menu about that player, with their head and name at the top and the actions other Vystorm plugins add (invite to party or city, view stats, ...), so those no longer need commands with player names. The menu only opens when at least one action is available; otherwise the click is left alone. NPCs (Citizens `NPC` metadata or players that are not online), off-hand clicks, spectators and events another plugin cancelled are ignored; a short cooldown prevents double opens. `/menu player <name>` opens the same menu.
- Config `player-menu.enabled` (default `true`) and `player-menu.require-sneak` (default `true`); permission `vystorm_core.playermenu` (default everyone); players can switch the menu off for themselves in `/vsettings` → Player menu.
- API: `playermenu.PlayerMenu.register(plugin, PlayerMenuEntry)` with id, priority, icon and visibility per (viewer, target) and a click handler returning `CLOSE`, `REFRESH` or `KEEP`; `unregister`, `registered`, `hasEntries`, `open`. Entries are removed when their plugin is disabled, and an exception in one entry only hides that entry (logged via `ProviderFailures`). Capability `player-menu`.
- Saving Core's own `config.yml` in the config editor now also reloads Core's config, so settings such as `player-menu` and `update-check.notify-on-join` apply without a restart.

## 0.42.0 (2026-10-29)

- Update notices are clickable: the join message for admins now opens `/vadmin updates` (with the download links) on click, and `/vadmin updates` ends with how long ago the last check ran.
- New `update-check.ignore` list to silence updates you do not want yet: `"Plugin"` hides every version of a plugin, `"Plugin 1.4.0"` only that version, so the next release is announced again. Ignored updates no longer appear in the join message, the console log or the update count, but `/vadmin updates` still lists them, marked as ignored.

## 0.41.0 (2026-10-28)

- Review reminder: once a Vystorm plugin with a SpigotMC page has been installed for 7 days, the next admin (`vystorm_core.admin`) who joins gets one short message with a clickable review link. Every plugin asks only once; when several are due together, Core sends a single message listing them all. Settings under `review-reminder` (`enabled`, `after-days`); state in `reviews.yml`.
- Anonymous statistics through bStats, sent only by Core: which Vystorm plugins (every plugin depending on Core) run on how many servers and in which versions (charts `vystorm_plugins`, `vystorm_versions`, `vystorm_plugin_count`). Opt out with `metrics: false` or in `plugins/bStats/config.yml`. Plugin page: https://bstats.org/plugin/bukkit/Vystorm%20Core/34998.

## 0.40.2 (2026-10-28)

- Plugins register their own SpigotMC resource for the update check: `UpdateSources.spigot(plugin, id)` (capability `update-sources`), removed again when the plugin is disabled. The hard-coded list is now only a fallback for releases that do not register yet, and also covers Vystorm Gallery, Paddock, Snapshot, Boards and Estates.

## 0.40.1 (2026-10-28)

- Add the `item-anvil` capability (`ItemServices.prepareAnvil` / `commitAnvil`) and backward-compatible ItemProvider defaults for copy-based anvil preview and post-delivery commit, used by BlockIntegrations owned anvils. Disabled or missing providers and provider failures fail closed.

## 0.40.0 (2026-10-28)

- New: update check for Vystorm plugins. Every 6 hours Core asks SpigotMC's public version endpoint which version of each installed free Vystorm plugin (Core, CustomTrees, XPVaultEconomy, BlockIntegrations, VystormPortals, VystormVeins, Winter Is Coming, VystormInsomnia) is current. Admins (`vystorm_core.admin`) get one chat line on join when updates exist; `/vadmin updates` lists every plugin with installed and latest version and a clickable download button, `/vadmin updates check` checks right away. Only the version endpoint is contacted, no server data is sent. Settings under `update-check` (`enabled`, `interval-hours`, `notify-on-join`, `extra` for more plugins by SpigotMC resource id). Capability `update-check`.
- `/vadmin language audit` now also lists texts with `<name>` tags that MiniMessage does not know (kind `unfilled-tags`). Those are almost always placeholders written as `<date>` instead of `{date}`, which players see literally; Winter Is Coming had exactly this bug. Command usage lines and permission nodes are ignored. `Lang.Audit` gains `unfilledTags()`; the old six-argument constructor remains.
- Language files on disk now receive texts that a plugin update adds: on load, keys present in the jar but missing from `plugins/<Plugin>/lang/<code>.yml` are written into that file (existing, possibly customised texts stay untouched; the previous file is kept as `<code>.yml.before-update`). Before, new texts only worked through the bundled fallback and admins never saw them. Applies to every plugin that uses the Core language system.
- `/vadmin` usage now lists `updates [check]`.
- `/vadmin status` and the admin dashboard now also know VystormPortals, VystormVeins, Winter Is Coming and the new Vystorm Gallery, Paddock, Snapshot, Boards and Estates.
- Debug log: the writer now flushes as soon as its queue is three quarters full instead of only every 250 ms, so startup bursts with many plugins no longer drop detail messages ("Dropped debug messages: 306").

## 0.39.1

- World-tree roots now dig into the ground: after its visible arch every sapling-tree root sinks 4-14 blocks below the planting level and forks underground, root tips continue below the surface, and the trunk base gets steep sinker roots plus a taproot (mangrove: taproot below the prop-root lattice). Depth is 6..14 blocks (`round(height * 0.06 + 4)`, 14 at the default height 160); only wood (log/wood/mangrove roots) is planned below the planting level. Above-ground geometry is identical to 0.39.0 for the same seed (the underground pass has its own random stream); defaults add about 2,300-4,800 underground blocks.
- Add `WorldTreePlan.depth(Material, int height, int radius)` (0 for mushrooms and fungi) and the `underground-tree-roots` capability. Plans now satisfy `origin.y - depth <= y < origin.y + height`.
- `gradle worldTreePreview` adds a cut-away (`<species>-wurzeln.png`) through the trunk base showing the underground roots; other views show only what is above ground.

## 0.39.0

- Redesign the nine sapling world trees as Yggdrasil-scale giants (`GiantTree`): flared, braided and fluted hollow trunks with bark ridges and burls; 9-30 buttress, surface or arching prop roots reaching up to 34 blocks; space-colonisation crowns with pipe-model (da Vinci) limb thickness, clumped leaf pads, crown shyness, open undersides and species curtains. Species follow real giants: Major Oak, giant sequoia/Douglas fir, silver-birch cluster, kapok, umbrella thorn, banyan (aerial prop roots), mangrove (lifted on prop roots), Jindai-zakura cherry and a moss-hung pale oak. Branch end grain uses `*_WOOD`; vines, moss carpets, mangrove roots and pale hanging moss appear where fitting.
- Sapling world-tree radius now accepts 12..48 (fungi stay 12..40); `WorldTreePlan.MAX_BLOCKS` is 400,000 (default 160/44 trees: 50k-135k blocks; 192/48: up to ~190k, planned in < 0.1 s).
- Add `WorldTreePlan.footprint(Material, int height, int radius)` and the `giant-world-trees` capability. Blocks are never clipped by the old ±max(14, radius) clamp; they stay within the reported footprint (radius + 12 for trees).
- Mushroom and Nether-fungus world trees are unchanged (regression-tested against 0.38.0).
- Add `gradle worldTreePreview [-PpreviewDir=...]`: real-voxel PNG previews (isometric, side, trunk base, before/after comparison, overview) and an HTML gallery.

## 0.38.0

- Add size-aware fungus plans for red/brown mushrooms and crimson/warped fungi at widths 1, 2, 3, 5 and 10, with the additive `fungus-growth-plans` capability and default `StructureGeneratorInterface.fungus` method.


## 0.37.0

- Add bounded, deterministic 10x10 world-tree plans for all nine sapling species, red/brown mushrooms and crimson/warped fungi. Pure geometry can run off-thread and is limited to 120,000 blocks.
- Add `world-tree-plans` capability and default `StructureGeneratorInterface.worldTree` API.

## 0.36.0

- Add projected-biomes: bounded client-only quart-cell biome overlays, composed over snowy views and restored on release. No saved biome changes.
- Add BlockView.replacePrioritized: prioritize visible changes in caller order while reserving cleanup progress within the same budget. Existing replace behavior remains compatible.

## 0.35.0

- Add stat.StatSum with source replacement/removal, immutable contribution snapshots and ADD, MULTIPLY (relative fractions), MULTIPLY_TOTAL (final factors).
- Add the stat-sums capability and optional ItemProvider/ItemServices.equipmentStatContributions. Existing numeric equipmentStats queries remain compatible; older providers return an empty breakdown.

## 0.34.0

- Add shared Core loot tables for mobs and chests with 0..10000 rarity weights (trash to mythic+), per-source overrides, amount/roll ranges and an optional table drop chance. Each selected custom item is generated independently through ItemRefs.
- Add the loot-tables capability, owner-scoped registry/lifecycle cleanup and atomic loot-tables.yml loading through the Core config editor. Mobs and WorldForge consume the same lists.

## 0.33.1

- Fix Windows debug logging and managed-world visit snapshots: the JDK Windows filesystem cannot open directory channels. Keep file-content flushes and atomic renames; validate directories on Windows and retain directory fsync on Linux. Windows does not gain a directory-entry power-loss durability guarantee.
- Normalize generated pause-menu pack line endings so Windows checkouts produce the same bytes as Linux.
- Fix Windows class-path handling in the API compatibility test. Separate the symlink test from the oversized-input test; report unavailable symlink/POSIX test prerequisites explicitly.

## 0.33.0

- Add owner-bound `ClientBiomes` snowy biome packets for Paper/Purpur 26.2. Server biomes and blocks remain unchanged; original biome packets are restored on release. Vanilla snowy plains colours apply on the client.

## 0.32.0

- Add local `item.RewardPouch`: idempotent durable reward credits, all-or-nothing inventory delivery, persisted delivery intent and player-data receipts. Full inventories retain rewards; conflicting interrupted deliveries fail closed. No cross-server claim support.
- Add opt-in `VisitPolicy.isolateExperience(true)`; snapshot format 3 restores level, total XP and progress. Existing visit policies keep their previous XP behavior and formats 1/2 remain readable. Finish visits before downgrading Core.

## 0.31.0

- Add `API.events()` with owner-bound event domains, registration, execution callbacks, cooldown/conflict gates, atomic default persistence and external-store migration support.
- Retain the WorldEventServices facade and legacy external-source integration; clean up event owners through CoreLifecycle.
- Add Plugin overloads for ConfigMigrator and VersionedStore without removing existing JavaPlugin signatures.

## 0.30.0

- Add reusable upward-facing HorizontalFrame geometry and masked, loaded-chunk-only block projection.
- Re-send active block overlays after client chunk load/unload packets invalidate their sent state.
- Add HORIZONTAL_VISUALS capability; existing upright projection signatures remain unchanged.

# Changelog – Vystorm Core

## 0.29.0

- Add independent `visual` API: cardinal aperture transforms, sight clipping, per-player block overlays and bounded particle paths.
- Restore current world blocks on close; release owner views on disable and forget stale views on world changes, quit and respawn.
- No ProtocolLib dependency or portal-specific state in Core. Capability `visuals`.

## 0.28.1

- Rückwärtskompatibilität: gemeinsame API-Angabe ab 1.21.8, weiterhin Java 25. Gegen Paper 1.21.11 /
  Adventure 4 gebaut, damit Dialog-/Textaufrufe sowohl auf älteren als auch neueren Servern auflösbar sind.
- Weltordner: benannte 1.21.x-Welten und keyed 26.x-Dimensionen getrennt auflösen; bei Fehlern des
  modernen Level-Verzeichnisses niemals auf einen anderen Ordner ausweichen.
- Login-Erholung: modernes asynchrones Ereignis separat laden; auf 1.21.8 das ältere
  Spawn-Ereignis verwenden. Die übrigen Weltschutz-Listener bleiben auf beiden Wegen registriert.
- Pausenmenü: Datenpaketformat aus der laufenden Serverversion lesen, einschließlich alter Metadatenformate.
- Keine neue öffentliche API, keine Migration gespeicherter Spieler-/Weltzustände, keine Live-Auslieferung.

## 0.28.0

- Neu: Optionale `licensing.LicensedModules`-API für Kaufplugins: kundeneigener Schlüssel per HTTPS-POST,
  signierte und an Anfrage/Produkt/Version/Instanz gebundene Antworten, Modul-Klassen direkt aus dem RAM.
  Keine Modul-JARs, Schlüssel oder Sitzungen werden durch diese API auf die Platte geschrieben.
- Neu: kurz gültige, regelmäßig erneuerte Freigaben, monotone Ablaufprüfung vor geschützten Aktionen und
  Aufräumen beim Plugin-/Core-Stop. Capability `licensed-modules`. Core selbst bleibt vollständig lokal.
- Lizenzvorgabe: standardmäßig 10 gleichzeitig aktive Server pro Kundenschlüssel/Produkt für Netzwerknutzung;
  das individuelle Kontingent wird vom Lizenzserver durchgesetzt, nicht im Core fest verdrahtet.
- Voraussetzung: ein kompatibler Download-/Lease-Endpunkt mit atomarer Begrenzung pro Kunde/Produkt;
  der bisherige Shop-Endpunkt `/api/license/verify` reicht dafür nicht. Dieser Commit migriert keine Kaufplugins
  und aktiviert keinen Schutz auf einem Live-Server. Protokoll und Integrationsvertrag: `docs/LICENSED_MODULES.md`.

## 0.27.2

- Neu: Optionale MythicMobs-Drop-Anbindung über `ItemRefs`. `mmoitems{type=…;id=…}` nutzt ohne MMOItems den
  nativen Provider; `vystorm{id=…}` und `vystormitem{id=…}` lösen native Referenzen auf. Keine Item-Erzeugung beim
  Laden, keine Befehlsausführung und keine zusätzliche Drop-Chance. Unikate bleiben gemäß ItemRefs Einzelstücke;
  mehrere Unikate benötigen getrennte Drop-Einträge. Capability `mythic-item-drops`.

## 0.27.1

- Geändert: `ConfigEditor.register` ergänzt fehlende Default-Schlüssel nur noch in der `config.yml` automatisch. Andere
  registrierte Dateien (Spawn-, Loot-, Mob-, XP-Tabellen …) bleiben unangetastet, damit vom Admin gelöschte Einträge
  nicht bei jedem Start zurückkommen; fehlende Schlüssel zeigen im Editor weiter den Jar-Default, geschrieben wird erst
  beim Speichern. Neu: `ConfigEditor.register(Plugin, String, Runnable, boolean mergeDefaults)` zum ausdrücklichen
  Ein- oder Ausschalten (Capability `config-editor-merge-option`).
- Behoben: Speichern im Config-Editor schreibt nur noch die geänderten Schlüssel in die Datei, wie sie gerade auf der
  Platte liegt, statt die (evtl. veraltete) Konfiguration im Speicher mit `saveConfig()` darüberzuschreiben. Handänderungen,
  die das Plugin noch nicht neu geladen hat, gehen nicht mehr verloren; eine kaputte YAML-Datei wird nicht mehr durch
  eine fast leere ersetzt.
- Behoben: Vor dem ersten Speichern einer Datei je Start legt der Editor eine Sicherung `<datei>.bak-<datum>` an.
  Dateirechte bleiben beim Schreiben (Editor und `mergeDefaults`) erhalten. Speichern ohne echte Änderung schreibt nichts
  und lädt nichts neu.
- Behoben: Dateien mit Schlüsseln, die einen Punkt enthalten (z. B. `"1.5": x`), schreiben weder Editor noch
  `mergeDefaults` um (Bukkit hätte daraus verschachtelte Schlüssel gemacht); der Admin bekommt einen Hinweis.
- Behoben: Config-Editor-Werte sind nicht mehr „profilsicher“; mit allen Plugin-Configs scheiterte
  `/vsettings profile export` sonst immer an der Grenze von 96 Werten.
- Behoben: Das native Einstellungs-Panel (Client-Mod) zeigt die Plugin-Konfigurationen als einen Eintrag, der die
  Übersicht öffnet, statt Tausende Werte einzubetten (sprengte die Panel-Grenzen für Admins). Einzelne Abschnitte
  (`/vsettings plugin:cfg-…`) werden weiter direkt angezeigt.
- Behoben: Mehr Geheimnisse werden erkannt: `pass`, `pwd`, `uri`, `dsn`, `jdbc-url`, `webhook`, `private-key`,
  `access-key`, `secret-key`, `connection-string` und camelCase-Namen wie `dbPassword`, `botToken`, `secretKey`.

## 0.27.0

- Neu: `ConfigEditor` (Capability `config-editor`): Plugins melden ihre `config.yml` (oder weitere YAML-Dateien) mit
  `ConfigEditor.register(this, "config.yml", this::reload)` an, und jeder Wert ist ohne Handarbeit im nativen
  Admin-Einstellungsmenü bearbeitbar. Schema aus der Default-Datei im Jar (Typen, Reihenfolge, Kommentare als
  Beschreibung), Werte aus dem Datenordner; ein Abschnitt je Top-Level-Schlüssel, große Gruppen in Teile zu höchstens
  24 Werten. Ganzzahlen als Slider bei bekanntem kleinem Bereich (`range` oder Kommentar wie `(1-64)`), sonst geprüfte
  Texteingabe; Dezimalzahlen geprüft; String-Listen als Komma-Text; Listen von Maps nur als „in Datei bearbeiten“.
- Neu: Optionale Beschriftung `ConfigEditor.label/range/choices/hidden/activation`, `reloadHook`; Speichern schreibt die
  Datei einmal (Kommentare bleiben), führt den Reload-Hook isoliert aus (`ProviderFailures`, Meldung an den Admin) oder
  sagt „wirkt nach /<plugin> reload oder Neustart“.
- Neu: `ConfigEditor.mergeDefaults` ergänzt fehlende Default-Schlüssel samt Kommentaren in der Datei, bestehende Werte
  bleiben unangetastet, vorher Sicherung `config.yml.bak-<datum>`; läuft bei `register` einmal automatisch.
- Neu: Geheimnisse (`password`, `token`, `secret`, `api-key`, `url`, `host`, `user` …, alles unter `database.shared` und
  `storage.mysql`) werden nie angezeigt, nie über den Editor geschrieben und nie in Einstellungsprofilen exportiert.
- Neu: Rechte `vystorm_core.config.<plugin>` (zur Laufzeit registriert, Kind von `vystorm_core.config.*`, das Kind von
  `vystorm_core.admin` ist; Standard op). Registrierungen eines deaktivierten Plugins entfernt `CoreLifecycle`.
- Neu: Core meldet die eigene `config.yml` an (ohne `database.shared`, `web.gateway.secret`/`url`); Sprache wirkt sofort,
  Web-Einstellungen nach Neustart.
- Neu: `/vadmin config [plugin]`, Eintrag „Plugin-Konfiguration“ in den Server-Einstellungen von `/einstellungen`, im
  Admin-Dashboard und je Plugin-Modul. Die vielen Konfigurationsabschnitte erscheinen dort gebündelt statt einzeln.
- Neu: `/vadmin permissions [plugin] [seite]` (Capability `permission-list`) listet die Rechteknoten von Core und allen
  Plugins mit `depend`/`softdepend` auf vystorm_core: Knoten, Standard, Beschreibung, ob man ihn hat.
- API: `SettingsRegistry.unregister(Plugin, String)` entfernt einen einzelnen Abschnitt, `SettingsRegistry.group(String)`.
- Geändert: Alle Core-Befehle deklarieren ihr Recht in der `plugin.yml` (`/vadmin`, `/admin` → `vystorm_core.admin`;
  neu `vystorm_core.menu` und `vystorm_core.settings`, Standard alle). Neu deklariert mit deutscher Beschreibung:
  `vystorm_core.*`, `vystorm_core.config.*`, `vcore.gui.access.*`, `vgui.access.*`, `vcore.bookgui.*`; alle
  Beschreibungen auf Deutsch.
- Geändert: Tab-Vervollständigung deckt alle Unterbefehle und Argumente von `/vadmin` (inkl. `status`, `lang`,
  `diagnostics <spieler>`, `migration <id>`, `config <plugin>`, `permissions <plugin> <seite>`), `/vmenu` (`search`,
  `list`, `settings`, `access <spieler>`), `/einstellungen` (`profile export|import <name>`), `/nbt`, `/web link`,
  `/vregion`, `/vworlds` ab, gefiltert nach Präfix und Rechten.

## 0.26.6

- Add rich chat channel envelopes with vanilla fallback (`chat-channels` capability) for client tabs.
- Add `/vtrade`, send/receive permissions, a two-second cooldown and cancellable server-thread `ChannelChatEvent`.
- Existing signed global chat and ordinary plugin chat delivery remain unchanged.

Pro Version: **Neu**, **Geändert** (Verhalten), **Deprecated**. Entfernt wird nichts; die API bleibt abwärtskompatibel zu 0.10.0
(`ApiCompatibilityTest`). Versionen vor 0.8 siehe `PROJECT_CONTEXT.md` §11 und die Commit-Historie.

## 0.26.5
- Behoben: Große Zahlenbereiche registrierter Einstellungen (z. B. DeepInventory-Ausbaupreise) verhindern nicht mehr das Öffnen des gesamten nativen Einstellungsmenüs. Außerhalb der Protokollgrenzen wird eine serverseitig validierte Zahleneingabe verwendet; Schrittweite, Rechte und Speicherweg bleiben erhalten.
- API: `RewardEligibility.allowed(Entity)` und `PRACTICE_TAG` für gemeinsame Fortschrittssperren in Übungssitzungen; Capability `reward-eligibility`.
- Neu: additive Baum-Wuchsprofile (Höhen-/Kronenfaktor und Wuchsform) mit bestehenden Höhenobergrenzen; Capability `tree-growth-profiles`.
- Neu: Menüsuche über alle Gruppen, persönliche Favoriten und die letzten acht geöffneten Menüs. Zugriff wird beim Öffnen erneut geprüft.
- Neu: optionale, überspringbare Einführung mit gespeicherten Sprach- und Menü-Meilensteinen.
- Neu: expliziter Kanal für gebündelte Routinebenachrichtigungen (sofort, 15 oder 60 Sekunden); kritische Bestätigungen bleiben direkt.
- Neu: Einstellungsentwürfe mit Vorher/Nachher-Vorschau, Konfliktprüfung und Aktivierungsangaben. Speichern mehrerer Abschnitte ist keine atomare Transaktion.
- Neu: benannte, versionsgeprüfte Profile für ausdrücklich freigegebene Servereinstellungen; Import erstellt nur einen Entwurf.
- Neu: Menü-Zugriffsdiagnose, Sprachprüfung und providergebundene Funktionsdiagnosen; reine Migrationsprüfungen auf begrenzten Dateikopien ohne Schreibzugriff.
- API: additive Capabilities `menu-explorer`, `menu-access-diagnostics`, `settings-drafts`, `settings-profiles`, `language-audit`, `feature-diagnostics`, `migration-previews`, `onboarding`, `routine-messages` (jeweils seit 0.26.5).
- Onboarding: `CoreOnboarding.markFeature(owner, player)` bestätigt erfolgreiche Plugin-Aktionen auf dem Serverthread; XP-Sparziele sind angebunden. Reine Menüöffnungen schließen diese Aufgabe nicht ab.

## 0.26.4
- Behoben: `ClientElement.Marker.world(String)` wandelt einen Bukkit-Weltnamen (`"world"`) in den Dimensionsschlüssel
  (`minecraft:overworld`) um; der Client kennt nur Schlüssel, Marker mit Weltnamen erschienen nie.
- Neu: `ClientUi.clear(Player, String localPrefix)` entfernt alle eigenen Client-Elemente eines Spielers mit diesem
  ID-Präfix (z. B. alle Event-Marker), ohne dass das Plugin die IDs selbst verfolgen muss.
- Behoben: `TaskGroup` behielt gelaufene Einmal-Tasks (`later`, `now`, `async`) bis `cancel()`, weil Bukkit sie danach nicht
  als abgebrochen meldet; häufige Einmal-Tasks wuchsen so unbegrenzt. Sie tragen sich jetzt nach dem Lauf selbst aus.
- Behoben: `TaskGroup.cancel()`/`close()` gleichzeitig aus zwei Threads brachen denselben Task zweimal ab (harmlos), und
  ein während `cancel()` eingetragener Task konnte durch das abschließende `clear()` unabgebrochen aus der Gruppe fallen;
  ebenso ein Task, der parallel zu `close()` eingetragen wurde. Jetzt bricht genau der Aufruf ab, der den Task aus der
  Gruppe nimmt, und ein Eintrag nach `close()` wird sofort abgebrochen (`IllegalStateException` wie bisher).
- Neu: Capability `task-group-one-shots` für dieses Verhalten. `TaskGroup` sortiert abgebrochene Tasks nur noch bei jedem
  64. Eintrag aus statt bei jedem, damit viele kurze Tasks billig bleiben.
- Geändert: `TaskGroup` plant über `plugin.getServer().getScheduler()` (Rückfall `Bukkit.getScheduler()`), damit Plugins mit
  eingesetztem `Server` in Tests sie nutzen können; ein `null`-Task eines Test-Schedulers wird nicht mehr eingetragen.
- Neu: Capability-Flags für ältere Funktionen ohne Flag: `admin-modules`, `tutorials`, `atomic-files`, `mob-spawn-rules`
  (Flag seit 0.26.4; auf älteren Cores weiter per `atLeast`/`LinkageError` prüfen).
- Neu: `MmoItemsBridge.idOf(ItemStack)` liefert `TYPE:ID` eines MMOItems-Stacks (Capability `mmoitems-ids`), damit Plugins
  dafür keine eigene Reflection brauchen.
- Neu: `WebServices.openOrLink(Player, String, String, Component, Runnable)` mit eigener Beschriftung des Chat-Links
  (Capability `web-open-label`); `null` behält den Modultitel.
- Neu: `CombatRegistry.Kind` (`HARM`/`SUPPORT`) im `Context`, `evaluate(attacker, target, kind)` und `kindOf(effects)`
  (Capability `combat-kinds`). Policies können so Team-/Party-Schaden verbieten, ohne Heil- oder Buff-Tränke zu blockieren.
  Bisherige Aufrufe ohne Art gelten als `HARM`.
- Neu (Client-Mod 0.9.0, Capability `client-ui-extensions`): `ClientServices.CAP_UI_EXTENSIONS` (Bit 8). Damit:
  HUD-Liste `ClientElement.list`/`row` (≤ 12 Zeilen mit Symbol, Text, rechtem Text, kleinem Balken; PATCH ersetzt
  `rows`; Zeilenbalken laufen mit `row.rate`/`row.countdown`, der rechte Text kennt `{value}`/`{max}`/`{time}` des
  Zeilenbalkens – Restzeit je Weltereignis ohne PATCHes), `Bar.rate`/`countdown` und Label-Platzhalter `{value}`/`{max}`/`{time}`, `Hud.stack(true)` (Elemente
  desselben Ankers stapeln ohne Überlappung, auch über Plugins hinweg), `Marker.entity/yOffset/ground` (folgt einer
  Entity bzw. Geländehöhe beim Client), Tooltip-Platzhalter `ClientElement.data(path)` aus `minecraft:custom_data`,
  `matchCustomDataPresent`/`matchCustomDataViewer` (UUID des Betrachters, „an dich gebunden“) und UND mehrerer
  Bedingungen, `Icon.item(ItemStack)`, `ClientUi.visible(Player)`. Was ein älterer Client ablehnen oder falsch zeigen
  würde, schickt `ClientUi` nur mit dem Bit (sonst `false`); rein verbessernde Felder (`stack`, `rate`, Marker-`entity`/
  `yOffset`/`ground`) lässt Core für ältere Clients weg.
- Neu (Capability `client-toast-actions`): `ClientUi.toast(Player, Toast, Consumer<Player>)` und `Toast.action(label)`.
  Der Spieler löst die Aktion mit der Client-Taste „Benachrichtigung annehmen“ (Standard Y) aus; Core führt den Handler
  auf dem Server-Thread höchstens einmal, nur für diesen Spieler, binnen `3 × Dauer + 15 s` und ratenbegrenzt (2/s) aus,
  unbekannte/abgelaufene IDs werden ignoriert, höchstens 16 offene Aktionen je Spieler. Der Handler prüft alles selbst
  erneut (der Client schickt nur die von Core vergebene ID). Neue Nachricht `UI_ACTION` (0x1E) auf `vystorm:ui`.
- Neu (Capability `client-ui-testing`): `panel.PanelTesting` (`render(Panel)`, `render(PanelSpec, viewer, arg)`, `find`,
  `ids`, `plain`) und `web.ClientUiTesting` (`validate`, `needsExtensions`) für Unit-Tests von Plugins ohne Server und
  ohne Core-Interna.
- Neu (Capability `panel-extras`): `Result.pending()` + `PanelAction.complete(Result)` (jeder Thread, Zeitlimit 30 s →
  `panel.no-answer`), `PanelAction.closeAndThen(Consumer<Player>)` (schließt alle Panels mit einem P_CLOSE 0, `next`
  zwei Ticks später), `Ui.Progress.rate`, `Ui.Form.confirm`, `Ui.ListView.selected`/`Ui.Table.selected`/
  `ListItem.selected()`, `Ui.Button.openSettings("plugin:section")`, `Ui.item(ItemStack)`.
- Geändert: Die Kopie des Client-Protokolls (`web/internal/clientproto`) entspricht Client 0.9.0, inklusive
  `ui/TooltipData.java` (in `scripts/sync-clientproto.sh` ergänzt).
- Doku: API §15.5–15.7 (Erweiterungen, Toast-Aktionen samt Sicherheitsbegründung, Test-Hilfe), §15.2 (Taste H blendet
  nur aus; Elemente bleiben beim Client und werden weiter aktualisiert, nichts muss neu gesendet werden; Toasts während
  des Ausblendens verfallen), §18.4–18.6 und §18.11.
- Doku: API §10 beschreibt, dass ein registriertes Admin-Modul den eingebauten Katalogeintrag mit derselben ID ersetzt.
- Test: Der Wipe-Test mit schreibgeschütztem Ordner wird als root übersprungen (root ignoriert Ordnerrechte).

## 0.26.3
- Behoben: Asynchroner Besuchseintritt prüft nur Wertgegenstände und Identität erneut: Inventar inklusive Rüstung/Nebenhand,
  leerer Cursor/Handwerksfeld, Gesamt-XP/Level/Fortschritt und Spielmodus; Orts-, Lebens- und weitere Zulassungsprüfungen bleiben.
  Item- oder XP-Änderungen brechen ab, endliche Effekte, Regeneration und Hunger verhindern den Eintritt nicht mehr.
- Doku: Der dauerhafte Schnappschuss behält skalare Werte vom Anfragezeitpunkt. Rückgabe kann Effektdauer, Gesundheit,
  Nahrung oder Luft um das kurze Zulassungsfenster (wenige Sekunden) zurücksetzen. Kein neues Speicherformat.

## 0.26.2
- Neu: Capability `async-durable-visits`; Eintritt schreibt Datei/fsync/Rename/Verzeichnis-fsync auf einem Executor.
  Server-Thread erfasst/serialisiert und vergleicht vor Zulassung erneut. `OK` bestätigt die Anfrage; Änderungen,
  Quit/Tod/Teleport, 2,5-s-Timeout und Shutdown brechen ohne Besuchszustand ab. Langsame Writes (>50 ms) warnen.
- Behoben: Commands bei LOWEST und HIGHEST geprüft, pro Event höchstens eine Meldung; ENTERING-Admin-Restore meldet IN_PROGRESS.
- Behoben: Werkzeug-Erholung ohne Marker erhält kreative/Flug-/Schutzflags; Diagnosebegrenzung pro Spieler.
- Neu: `/vworlds legacy <Spieler|UUID> archive|retry`, kollisionsfreie Archive und manuelle Recovery-Anleitung.
- Behoben: Unerwarteter Weltpfad wird vor Fehler ungespeichert entladen; tote `.path`-Merkdateien nicht mehr geschrieben.
- Doku: `autoSave(false)` verhindert den finalen Server-Shutdown-Save nicht; asynchroner Eintritt vollständig beschrieben.

## 0.26.1
- Behoben: Review 2 der Plugin-Welten – Rückgabe dekodiert Items vor freiwilligen Teleports; fehlgeschlagene
  erzwungene Rückgabe entfernt Werkzeuge und Besuchsrechte, erhält aber Marker und Schnappschuss.
- Behoben: Namespace-Befehle brauchen identische registrierte Commands; Erlaubnisliste prüft bei HIGHEST.
- Behoben: Kein Bukkit-Unload beim Serverstopp; Weltpfad und Besitz vor dem ersten Laden geprüft.
- Neu: `RestoreOutcome` und additive Default-Methode `VisitService.restoreOutcome`, Capability `visit-restore-outcome`;
  `restore` bleibt deprecated erhalten, laufende Rückkehr meldet `in-progress`.
- Behoben: Cooldown-Einträge verfallen, Pfad-Dateinamen sind kollisionsfrei, Legacy-Werkzeuge immer gesperrt;
  offene Skills-1.16-Migrationen blockieren neue Besuche. Item-Sperrstatus nutzt ein eigenes Flag.
- Geändert: Recovery und Sichtbarkeit ausgelagert; bisher verschluckte Fehler werden begrenzt geloggt;
  Schnappschüsse fsyncen auch das Verzeichnis. Synchrone Durabilität bleibt erhalten (Messwerte im Review-2-Bericht).
- Doku: Autosave-/I/O-/Leaving-Verträge und eingefrorene Wert-Records klargestellt; API-Baseline nur ergänzt.

## 0.26.0
Neues Core-Modul „Plugin-Welten“ (`worlds`) für die Talentwelt von Vystorm Skills und später WorldForge/Arena.
- Neu: `worlds.ManagedWorlds` (`register`, `rebuild`, `reconfigure`, `unregister`, `release`, `get`, `of`, `managed`, `all`, `visits`),
  `WorldSpec` (Generator, Layout-Hash, Spielregeln, Zeit/Wetter/Grenze/Spawn, Weltprofil, `RegionSpec`, `Protection`,
  `Visibility`, `VisitPolicy`, `VisitHooks`, Admin-Recht, Legacy-Hash-Datei), `VisitService` (`enter`, `exit`,
  `inVisit`, `visit`, `visits`, `hasSnapshot`, `isTool`, `regiveTools`, `refreshVisibility`, `restore`), `Visit`,
  `ExitReason`, `VisitResult`, `ToolKit`, `SpawnPoint`, `ManagedWorldException`. Capability `managed-worlds`.
- Neu: Besuche sichern den vollen Zustand vor jeder Änderung in `plugins/vystorm_core/visits/<uuid>.yml` (Format 2,
  liest Format 1 der Skills-1.16-Talentwelt). Verlassen: erst Teleport, dann Rückgabe, dann Spielerdaten speichern,
  dann Schnappschuss löschen; scheitert der Teleport, bleibt der Besuch. Erholung beim Join entscheidet über den
  PDC-Marker `vystorm_core:visit`, ob die Spielerdaten im Besuchsstand sind (kein doppeltes Zurückspielen). Immer
  aktiver Listener (auch ohne Besitzer-Plugin), Befehls-Erlaubnisliste während des Besuchs, Items aus dem Besuch
  bleiben, Cursor/Handwerksfeld/Luft/Frost gesichert, Werkzeuge nach Respawn neu, fail-closed Regionsprüfung.
- Neu: Neuaufbau löscht `region/`, `entities/`, `poi/` nur mit Markierung `vystorm-world.yml`; schon geladene Welten
  und Ordner mit Chunk-Daten ohne Markierung werden nie angefasst; Löschfehler werden gemeldet.
- Neu: `item.ItemRestrictions` (Capability `item-restrictions`); `ItemServices.validate` meldet gesperrte Items
  (z. B. Besuchs-Werkzeuge) als `QUARANTINED` mit `restricted:<grund>`; `ItemValidation.restricted(...)`.
- Neu: `/vworlds list|visits|restore <spieler>|check <spieler>`, Recht `vystorm_core.worlds.bypass`, Texte `worlds.*`.
- Geändert: Core-Dialoge schließen nach einem Klick sofort (`DialogAfterAction.CLOSE` statt `WAIT_FOR_RESPONSE`);
  Vanilla 26.2 blieb sonst auf „Warte auf Server“ stehen.
- Geändert: `api-baseline.txt` neu aus 0.26.0 (nur Ergänzungen).

## 0.25.4
- Geändert: gebündelte Web-Oberfläche auf vystorm-web-ui 0.9.2 (lädt die Texte nach dem Login-Link neu, damit die Seite die Sprache des Spielers nutzt; Talentbaum-Korrekturen aus 0.9.1). Keine API-Änderung.

## 0.25.3
- Behoben: `/web <modul>` und `/web` schickten immer den Chat-Link „[Weboberfläche öffnen]“, auch wenn der Spieler den
  Vystorm-Client-Mod hat und dem Web-Ursprung des Servers schon vertraut. Sie gehen jetzt wie Plugin-Befehle über
  `WebServices.openOrLink`: Seite direkt über den Mod, sonst wie bisher der Chat-Link (Beschriftung = Modultitel). `/web
  link` in der Konsole ist unverändert.
- Geändert: Der Web-Knopf im `/vsettings`-Dialog (er nutzte schon `openOrLink`, jetzt per Test abgesichert) und der
  Web-Knopf automatischer Panel-Dialoge heißen „Webseite öffnen“ / „Open web page“ statt „Im Browser öffnen“, der Tooltip
  erklärt Mod-Weg und Chat-Link.

## 0.25.2
Re-Review der Panel-Fixes.
- Behoben (wichtig): Ein Refresh-Ergebnis, das off-thread fertig wurde, nachdem die Sitzung nicht mehr oben war, wurde
  trotzdem gesendet und übernommen (der Client zeigte dann das Dokument in einem anderen Panel bzw. doppelt im
  Zurück-Stapel). Jetzt wird es verworfen und nachgeholt, sobald die Sitzung wieder oben ist. Ein großes erstes OPEN,
  das beim Fertigpacken von einem späteren OPEN überholt wurde, wird nicht mehr nachgeschickt (die Sitzung wird
  vergessen).
- Geändert: `resync:` gilt für jede Sitzung im Stapel (nicht nur die oberste); erzwungene volle Renders höchstens 1/s je
  Sitzung. Jedes volle OPEN („Zustand behalten“) erhöht `rev`, damit ein verlorenes OPEN beim nächsten Patch als Lücke
  auffällt. Patches über 128 KiB gehen als volles OPEN (vorher verloren). Ist die Sende-Warteschlange voll oder scheitert
  das Packen, folgt ein volles OPEN.
- Behoben: Ein Error (nicht nur RuntimeException) im Off-Thread-Job ließ die Sitzung im Zustand „packt“ hängen.
- Geändert (intern): `UiBridge` hat kein öffentliches `install`/`get` mehr; der paket-private `UiBridges` erzeugt die
  Umsetzung. Protokoll-Kopie Stand Client 0.8.2.

## 0.25.1
Final Review der Native Panels (Phase 1). Die Panel-API war seit 0.25.0 nur Stunden alt und von keinem Plugin benutzt;
sie wurde vor dem Einfrieren bereinigt – Capability `panels` gilt deshalb ab 0.25.1.
- Behoben: Ein Server-`Panels.open`, das der Client verwarf (`allowServerOpen: false`, Limit), ließ das sichtbare Panel tot
  zurück (Core hatte es schon vergessen; Klicks → „veraltet“). Ersetzte Sitzungen bleiben jetzt im Stapel, bis der Client
  sie mit P_CLOSED schließt; ein P_CLOSED der neuen macht die alte wieder aktiv.
- Behoben: Ein feindlicher Client konnte mit erfundenen Element-IDs unbegrenzt Token-Buckets anlegen, und „veraltet“-
  Antworten kamen vor jedem Ratenlimit. Das Spieler-Limit gilt jetzt zuerst (Antworten darüber höchstens 2/s, Burst 10),
  Element-Buckets entstehen nur für Elemente des gesendeten Dokuments (≤ 512 je Sitzung).
- Behoben: Formulare mit gesperrtem/unsichtbarem Feld wurden immer abgelehnt („unknown form field“); solche Felder
  werden jetzt ignoriert, unbekannte weiter abgelehnt (gemeinsame `ActionCheck`, Client 0.8.1).
- Behoben: Positionsabhängige Element-IDs in Core-Panels – kam ein Einstellungs-Abschnitt oder Menüeintrag dazu, konnte
  ein Klick im Wettlauf mit dem Refresh eine andere Einstellung/einen anderen Menüeintrag treffen. IDs jetzt
  `s:<abschnitt>/<einstellung>` bzw. `m:<menüschlüssel>` (Hash, wenn sie nicht ins ID-Muster passen).
- Behoben: Im automatischen Paper-Dialog liefen Knöpfe mit `confirm` sofort; jetzt erst ein Bestätigungsdialog. Lazy
  Reiter rufen `onTab`, jeder Reiter-Block merkt sich seine Auswahl, „Schließen“ ist übersetzt (`panel.dialog.close`),
  gesperrte Eingaben erscheinen als Text, gesperrte Knöpfe nicht.
- Geändert (Leistung): Große Refreshs (≥ 32 KiB geschätzt) prüfen, diffen, serialisieren und packen außerhalb des
  Server-Threads (bis dahin gilt für Aktionen das alte Dokument); Größenvergleich im Diff ohne Serialisieren, jedes
  Dokument höchstens einmal serialisiert; P_REQUEST 2/s (Burst 6) statt 10/s (Burst 20); doppeltes `Panels.open`
  desselben Panels im selben Tick wird zusammengefasst. Die volle Prüfung bleibt (sie fängt Plugin-Fehler wie > 2000
  Zeilen ab, die der Schreiber nicht prüft).
- Neu: P_ERROR mit `detail` „resync: …“ (Client 0.8.1 bei `rev`-Lücke oder kaputtem Patch) → nächster Tick schickt das
  volle Dokument mit „Zustand behalten“.
- Geändert (API, Final Review Important 10): `panel.UiAccess` ist nicht mehr öffentlich (Brücke `panel.internal.UiBridge`,
  nur lesbare Listen). Getypte Builder statt `Ui.Simple`: `Ui.Heading`, `Ui.Text`, `Ui.Badge`, `Ui.Swatch`, `Ui.Divider`,
  `Ui.Spacer`, `Ui.Progress`, `Ui.Timer`, `Ui.Image`; `Ui.number` liefert `Ui.NumberField` (ohne `format`/`live`);
  `Ui.Table.columnWidth`/`columnWidthPercent` für die letzte Spalte, `width` ist wieder die Tabellenbreite;
  `Ui.Box.justify(Ui.Justify)`. `Panels.refresh/refreshAll/close` dürfen von jedem Thread kommen, `Panels.unregister`
  entfernt auch den `/vmenu`-Eintrag (neu `GuiInteface.unregisterMenu`, Default `false`).
- Geändert: `ApiCompatibilityTest` lässt `….internal.…`-Pakete aus; `api-baseline.txt` neu aus 0.25.1 (vorher 0.10.0,
  alle damaligen öffentlichen Elemente sind weiter enthalten).
- Geändert: Dialog-Teil des `PanelHub` in eigene Klasse `PanelDialogs` mit derselben Prüfkette wie native Aktionen
  (Element-Ratenlimit, „zu schnell“ statt „kein Recht“ bei Ratenlimit); ungenutzte Dialog-Map entfernt; schließt der
  Server die oberste Sitzung, wird die darunter frisch gerendert.
- Neu: `scripts/sync-clientproto.sh --check` und `ClientProtoSyncTest` (schlägt fehl, wenn die Kopie vom Client abweicht);
  das Skript löscht Dateien, die nicht mehr in der Liste stehen. Protokoll-Kopie Stand Client 0.8.1.
- Neu: Texte `panel.dialog.close`, `.yes`, `.no`, `.slow-down` (en/de).

## 0.25.0
- Neu: Kanal `vystorm:panel` für **native Panels** des Client-Mods (ab 0.8.0): Server-Menüs als echte Minecraft-Screens. Handshake P_HELLO/P_WELCOME (Schema 1, Features = Schnittmenge, Phase 1 nur `TIMER`), WELCOME auf `vystorm:web` setzt `SERVER_PANELS`, wenn Panels laufen und der Client `CAP_PANELS` meldet. Neue Einstellungen `web.client.panels` (`enabled`, `hotkey-panel`, `refresh-per-second`, `chunks-per-tick`, `actions-per-second`, `action-burst`).
- Neu: öffentliche API `panel.Panels` (`of`, `register`, `unregister`, `open`, `refresh`, `refreshAll`, `close`, `available`), `PanelSpec` (Builder mit `access`, `render`, `menu`, `web`, `dialogFallback`, `fallback`), `Panel`, Elementfabrik `Ui` (alle Elemente des Schemas v1 außer chart/graph, Handler `Ui.Click`/`Ui.Change<T>`), `PanelContext`, `PanelAction`, `PanelSession`, `Result`, `PanelOpenResult`; Events `VystormPanelOpenEvent`, `VystormPanelCloseEvent`, `VystormPanelErrorEvent`. Capability `panels` (0.25.0).
- Neu: `PanelHub` – Sitzungen mit Zurück-Stapel (≤ 5), jede Aktion wird mit derselben Klasse wie im Client (`ActionCheck`) gegen das zuletzt gesendete Dokument geprüft (Element, Art, Grenzen, Optionen, Zeichenklasse, Formularfelder, `disabled`/unsichtbar → „veraltet“), Recht bei jeder Aktion, Ratenlimits 20/s (Burst 40) pro Spieler und 10/s pro Element, doppelte `seq` ignoriert, immer P_RESULT; Diff nach IDs → PATCH (größer als das halbe Dokument → OPEN mit „Zustand behalten“), Refresh ≤ 10/s pro Sitzung und pro Tick gebündelt, Dokumente > 8 KiB gepackt und in Chunks (≤ 8 pro Tick und Spieler). Aufräumen beim Plugin-Disable über `CoreLifecycle` (Schritt `Panels`).
- Neu: Fallback-Kette von `Panels.open`: Recht → natives Panel → eigenes `fallback` des Plugins → automatischer **Paper-Dialog** aus derselben Panel-Beschreibung (`DialogDowngrade`; Aktionen durch dieselbe Prüfung und dieselben Handler) → Web-Link (`WebServices.openOrLink`) → Hinweis.
- Neu: `SettingsPanels` – Core-Einstellungen als Panel `vystorm_core:settings` aus demselben Modell wie Dialog und Webseite (Suchfeld, persönlich/Server-Reiter, Speichern über `SettingsRegistry.save(…, "panel")`, `changed(player)` aktualisiert offene Panels).
- Neu: `MenuPanels` – `/vmenu` als Panel `vystorm_core:menu` (Raster je Kategorie, Einträge mit eigenem Panel öffnen nativ). `GuiInteface.menuEntries(Player, String)` (Default leer) und Record `_uiMethods.autoGui.MenuEntryView`.
- Neu: `scripts/sync-clientproto.sh` kopiert den Protokoll-Code des Clients (30 Dateien inkl. `panel/*`, Stand 0.8.0) nach `web/internal/clientproto/`.
- Neu: Texte `panel.*`, `settings.panel.*`, `menu.panel.*` (en/de); `docs/API.md` §18 „Native panels“, README-Abschnitt.
- Geändert: Mit Client-Mod ≥ 0.8.0 öffnen `/vsettings` und `/vmenu` native Panels (ohne Mod unverändert die Dialoge); Taste K öffnet `/vmenu` (bzw. `web.client.panels.hotkey-panel`) statt des Browsers, Umschalt+K weiter die Webseite.

## 0.24.4
- Behoben: Das Server-Resource-Pack lud auf Minecraft 26.2 nicht richtig (Item-Texturen fehlten im Inventar). `pack.mcmeta` nennt ab Ressourcenformat 65 jetzt `min_format`/`max_format` (Bereich Einstellung bis 99) statt nur `pack_format`, wie der 26.2-Client es verlangt. Standard für `pack-format` in `resource-pack.yml` ist jetzt 88 (26.2); bestehende Einstellungen (z. B. 69) funktionieren weiter, weil 88 im gemeldeten Bereich liegt.

## 0.24.3
- Geändert: Dschungel, Akazie und Kirsche nutzen wieder die früheren CustomTrees-Formen (verzweigte Kronen mit Laubballen, Höhen wie vor 0.24.2), leicht erweitert: vollere Kronen (Laub bis 6 Blöcke vom Holz), Dschungel mit Stamm- und Laubranken sowie Kakao (1×1/2×2), Kirsche mit hängenden Blüten an den Kronenrändern, Akazie mit gelegentlicher kleiner Seitengabel samt flachem Schirm. Die übrigen sechs Arten bleiben im Vanilla-Stil von 0.24.2.
- Behoben: Zusatzäste der Wuchsformen konnten bei Akazie und Kirsche über dem Stammende frei in der Luft beginnen.

## 0.24.2
- Geändert: Baumformen (`structureGenerator().tree(...)`, genutzt von CustomTrees) folgen jetzt Minecrafts eigener Blocksprache. 1×1-Bäume wachsen nah am Vanilla-Baum (Blob- und Fancy-Eiche, Birke, Fichte/Kiefer, Dschungelbaum mit Ranken und Kakao, gegabelte Akazie, Dunkel- und Blasseiche, Mangrove auf Wurzeln, Kirsche); 2×2, 3×3 und 5×5 nutzen dieselben Laubregeln größer skaliert (Fancy-Eichen-Cluster, Mega-Fichten-Ringe, flache Akazien-Schirme, Dunkeleichen-Dach, Mega-Dschungel-Scheiben, Kirsch-Kuppeln mit hängendem Laub). Laub darf wie im Spiel bis 6 Blöcke vom Holz entfernt sein, die inneren Stützäste in den Kronen entfallen. Deko wie die Vanilla-Dekoratoren: Ranken (Dschungel, Mangrove, alte Eichen), Kakao, hängendes Blassmoos, hängende Mangroven-Keimlinge.
- Geändert: `TreeSpecies`-Höhen (minHeight/maxHeight) auf Vanilla-nahe Werte gesenkt (z. B. Eiche 5–11, Fichte 7–12); `heightBonus` unverändert. Keine API-Änderung.
- Neu (Test): `TreeRenderDump` schreibt mit `TREE_RENDER_DIR` Blockdumps aller Formen zum Rendern.

## 0.24.1
- Geändert: gebündelte Web-Oberfläche auf vystorm-web-ui 0.9.0 (Seiten für CustomTrees `trees`, XPVaultEconomy `economy`, OreVeins `oreveins`, BlockIntegrations `blocks`, BetterPortals `portals` sowie das Skills-Menü). Keine API-Änderung.

## 0.24.0
- Neu: Core-Jar bündelt die Web-Oberfläche (vystorm-web-ui 0.7.0, `webui/vystorm-web-ui-dist.zip` → `web/bundled-ui/`, ~70 KB gepackt). Reihenfolge: `uiDir` des Gateways → Build mit `index.html` in `web.ui-dir` → gebündelter Build → eingebaute Minimalseiten. Spigot-Admins brauchen nur Cores Jar und `web.enabled: true`. `scripts/update-web-ui.sh` baut die UI (inkl. CSP-Prüfung) und schreibt Zip und `webui/web-ui.properties`; der Gradle-Build braucht kein npm. `/web status` und das Startlog nennen die Quelle (`bundled vystorm-web-ui 0.7.0`).
- Neu: Eingebautes Web-Modul `settings` (`/app/settings`, `/web settings`, Rückfallseite `/m/settings/`): alle über `api.settings()` registrierten Abschnitte (persönlich für alle, Server-Abschnitte nur mit deren Recht bzw. `vystorm_core.admin`), Texte per `Lang.ref` in der Sprache der Anfrage. `GET /api/settings/sections`, `POST /api/settings/save` laufen auf dem Server-Thread durch denselben Speicherpfad wie der Dialog (Rechte, Typ/Bereich/Optionen/Länge, Setter-Fehlertext, `afterSave` einmal). Live in beide Richtungen: Speichern im Spiel oder im Web schickt `settings/changed`. Im Einstellungsdialog neuer Knopf „Im Browser öffnen“ (nur bei laufender Plattform).
- Neu: `SettingsInterface.changed(player)` (Default no-op), `SettingsRegistry.view/save/coerce/onChange` samt `SaveResult`, `Problem`, `SectionView`, `SettingView`, `ChangeListener`. Der Dialog nutzt intern denselben `commit` wie das Web (Verhalten unverändert).
- Neu: `WebServices.openOrLink(player, modul, unterpfad, fallback)` (Overlay des Client-Mods bei Zustimmung, sonst Chat-Link, sonst Fallback/Hinweis), `openOrElse` (Overlay, sonst Fallback, kein Link), `overlayPage`, `broadcast(modul, typ, json)`, Enum `WebOpenResult`.
- Neu: Capabilities `web-open` und `web-settings` (0.24.0). README: „Web pages for plugin authors“.
- Neu: Texte `web.ui.settings.*`, `web.ui.app.no-page-update`, `web.module.settings.*`, `web.error.settings-*`, `settings.web*` (en/de).

## 0.23.0
- Neu: Web-Plattform lokalisiert (Capability `web-localization`). Sprache einer Anfrage: Sprachwahl der Seite (Cookie `vystorm_lang`), für angemeldete Spieler dieselbe Auflösung wie im Spiel (eigene Wahl, fester `language.default`, zuletzt bekannte Client-Sprache, auch offline), sonst `Accept-Language`, sonst `en`.
- Neu: Alle Fehlermeldungen von `WebDispatcher`, `EmbeddedHttpServer`, Gateway-Tunnel und Live-Kanal als Schlüssel `web.error.*` in `lang/en.yml`/`de.yml` (deutsche Texte unverändert). `WebException`-Meldungen und Modul-Titel/-Beschreibungen dürfen `Lang.ref` sein.
- Neu: `WebContext.language()`, `GET /core/i18n[?ns=<plugin>]` (Texte unter `web.ui.*`), `{{…}}`-Platzhalter in HTML-Assets (`{{@lang}}`, `{{@texts}}`, `{{@languages}}`, `{{plugin:key}}`) mit ETag je Sprache; `/core/session` liefert `language` und `languages`.
- Neu: Eingebaute Seiten (Übersicht, Login, Profil) zweisprachig mit Sprachauswahl in der Kopfzeile; `VystormWeb.t`, `VystormWeb.setLanguage`.
- Neu: Texte für `vystorm-web-ui` ≥ 0.6.0 (Rahmen, Komponenten-Kit, Profil) unter `web.ui.app.*`/`web.ui.kit.*`; die Oberfläche lädt sie über `/core/i18n`. `vystorm-web-gateway` ≥ 0.2.2 antwortet selbst ebenfalls auf Englisch/Deutsch.
- Neu: `Localization.acceptLanguages`, `resolveIn`, `rememberClientLocale`/`clientLocale`, `Lang.languageFor(UUID, List)`, `Lang.texts(language, prefix)`.
- Geändert: Die persönliche Sprachwahl und die Client-Sprache bleiben nach dem Ausloggen im Speicher (für Web-Sitzungen offline).
- Geändert: Protokollfehler vor dem Lesen der Header (eingebetteter Server, Tunnel) nutzen den festen `language.default`, sonst Englisch.

## 0.22.0
- Neu: Lokalisierung (`lang.Localization`, `api.lang()`, Capability `localization`). Jedes Plugin registriert ein `lang.Lang`-Paket (`api.lang().register(plugin)`) aus `lang/<code>.yml` im Jar; fehlende Dateien werden nach `plugins/<Plugin>/lang/` kopiert (editierbar, weitere Sprachen möglich). Suche: Datei `L` → Jar `L` → Datei `en` → Jar `en` → Schlüssel; nie `null`, nie Exception, neue Schlüssel wirken ohne Dateiänderung. MiniMessage und alte `&`-Codes, benannte Platzhalter `{name}`, `component/text/plain/lines/send/actionBar`, Item-Name/Lore, `ref`/`translatable` für einmal registrierte Texte, threadsicher, gecacht, `reload()`.
- Neu: `config.yml` `language.default` (`auto` = Client-Sprache, `de_at` → `de`, unbekannt → `en`; Konsole `en`; oder ein fester Code). Spieler wählen unter Einstellungen → Sprache selbst (Spielerdaten `vystorm_core:pref.language`); eine alte Core-Spielereinstellung `language` ungleich `en_US` gilt ebenfalls.
- Neu: Core-Texte (Menüs, Einstellungen, Admin-Dashboard, Dialogknöpfe, Chat-Aktionen, Befehle, Resource-Pack-Hinweise, Tutorial `tutorial_en.yml`) auf Englisch und Deutsch in `lang/en.yml`/`lang/de.yml`; deutsche Texte unverändert. Deutsche Clients sehen mit `auto` alles wie bisher.
- Neu: `DefaultDialogHandler` rendert `Lang.translatable`-Komponenten in Titel, Knöpfen, Tooltips und Textkörpern je Spieler; Standard-Knöpfe „Abbrechen“/„Schließen“ folgen der Spielersprache. Menüeinträge, Einstellungen und Admin-Module lösen `Lang.ref`-Texte je Spieler auf.
- Neu: `GuiInteface.registerMenu(…, Function<Player,String> title, …, Function<Player,String> description)`, `MenuCategory.langKey/title/byGroup`, `MenuButtons.settings(String, Component, Component)`, `PauseMenuPack.sync(core, enabled, language)`, `/vadmin language [reload]`.
- Neu: `TutorialGuide` liest optionale Übersetzungen `tutorial_<code>.yml`.
- Geändert: Das Pause-Menü-Datenpaket nutzt `menu.yml` `pause-menu-language` (`auto`: fester `language.default`, sonst Sprache des bereits installierten Pakets, sonst Englisch); das bestehende deutsche Paket bleibt byte-gleich.
- Geändert: Der eingebaute Resource-Pack-HTTP-Server startet erst mit dem ersten Pack-Modul (vorher beim Serverstart, auch ohne Inhalt). Der Standard-Prompt erscheint in der Spielersprache.
- Geändert: Konsolenmeldungen (Resource-Pack, Pause-Menü, Provider-Warnungen, Kurven, Datenbankfehler) und `plugin.yml`-Beschreibungen auf Englisch; `RelativeStat`-Aufschlüsselung „capped“/„missing“ statt „gecappt“/„fehlt“.

## 0.21.0
- Geändert: Client-Protokoll Version 2 (vystorm-client 0.4.0; Protokoll-Kopie `web/internal/clientproto` auf 0.4.0). Der Client meldet im CLIENT_STATE das Bit `STATE_ORIGIN_TRUSTED` (Spieler hat dem Web-Ursprung zugestimmt, der Client würde SHOW befolgen) direkt nach dem WELCOME und bei jeder Änderung – ohne Token oder URL. Core wählt weiter die höchste gemeinsame Version; Clients mit Protokoll 1 funktionieren wie bisher.
- Geändert: `ClientServices.overlayAvailable`/`show` gelten für Clients mit Protokoll 2, sobald das Bit gesetzt ist (Overlay-Fähigkeit und `web.client.overlay` vorausgesetzt) – auch ohne vorgewärmten Browser (`preloadBrowser` aus); die Anmeldung passiert beim ersten SHOW mit einem frischen Einmal-Token. Ohne Bit (keine Zustimmung, Server-Öffnen im Client abgeschaltet, Browser nicht nutzbar) bleibt es beim normalen Menü, auch wenn der Client in der Verbindung schon ein Token geholt hat. Protokoll 1: unverändert erst nach dem ersten Token. Ein gefälschtes Bit bewirkt nur, dass Core SHOW schickt; der Client prüft die Zustimmung selbst und öffnet den Vertrauensdialog nie auf SHOW hin.
- Geändert: CLIENT_STATE wird immer übernommen; nur `VystormClientStateEvent` bleibt auf 2/s (Burst 6) begrenzt und meldet die Änderung gegenüber dem zuletzt gemeldeten Zustand (vorher ging der Zustand im Ratenlimit verloren).
- Neu: `ClientServices.STATE_ORIGIN_TRUSTED`, `ClientServices.originTrusted`, `VystormClientStateEvent.originTrusted`/`originTrustedChanged`.
- Neu: die Capability `web-client-trust` (0.21.0).

## 0.20.0
- Neu: Kanal `vystorm:ui` für native Elemente des Client-Mods (Protokoll `PROTOCOL.md` §4 im Repo `Vystorm/vystorm-client`): `web.ClientUi.of(plugin)` mit `put`, `patch`, `remove`, `clear`, `clearAll`, `toast`, `has`, `available` und typisierten Bausteinen `web.ClientElement` (`panel`, `bar`, `slots` mit Abklingzeit, `marker`, `tooltip`, `toast`, `Text`, `Icon`). IDs sind je Plugin im Namensraum `<plugin>:`; Core prüft jedes Element mit dem Schema des Clients (ungültig → `false` und einmalige Warnung), hält die Client-Grenzen (32 HUD, 64 Marker, 128 Tooltips), bündelt PATCHes je Element (`web.client.ui.patch-interval-ticks`, Standard 4×/s, unveränderte Felder werden nicht gesendet) und sendet ratenbegrenzt aus einer Warteschlange (30/s, Burst 100, ≤ 32 je Tick). Ohne Mod oder Fähigkeit passiert nichts. `CoreLifecycle` entfernt die Elemente eines deaktivierten Plugins bei allen Spielern; beim Quit wird der Zustand verworfen. WELCOME kündigt native Elemente jetzt an (`SERVER_UI`).
- Neu: Events `web.VystormClientReadyEvent` (Handshake angenommen, Fähigkeiten, Mod-Version), `web.VystormClientStateEvent` (Elemente ausgeblendet/Overlay offen, nur bei Änderung) und `web.VystormClientUiRejectEvent` (Ablehnung durch den Client an das Plugin des Namensraums; Log gedrosselt, einmal pro Minute je Plugin und Code).
- Neu: `ClientServices.uiHidden`, `ClientServices.uiAvailable`, `ClientServices.CAP_JS_BRIDGE`.
- Geändert: `ClientServices.overlayAvailable`/`show` gelten erst, wenn der Client in dieser Verbindung ein Login-Token geholt hat (Spieler hat dem Web-Ursprung zugestimmt: Vorwärmen nach dem Join oder Taste K). Der Client (ab 0.3.2) befolgt SHOW vorher nicht; so öffnen Plugins bis dahin ihr normales Menü statt nichts.
- Geändert: Protokoll-Kopie `web/internal/clientproto` auf vystorm-client 0.3.2 (Texte ohne Unicode-Formatzeichen; ein PATCH darf ein Element nicht über 16 KiB wachsen lassen – Core prüft das vor dem Senden genauso). `ClientElement`-Texte werden wie im Client bereinigt.
- Geändert (Sicherheit): Web-Sitzungen merken sich den Kanal ihres Login-Tokens (`chat`, `player`, `client`, …). Beim Quit enden die Sitzungen und offenen Tokens aus dem Client-Mod (`web.client.end-sessions-on-quit: client`, Standard; `all` beendet auch Browser-Tabs aus `/web`, `none` keine), mit Audit-Eintrag.
- Neu: `config.yml` `web.client.end-sessions-on-quit` und `web.client.ui` (`enabled`, `patch-interval-ticks`, `messages-per-second`, `burst`, `per-tick`, `queue-limit`).
- Neu: die Capability `web-client-ui` (0.20.0).

## 0.19.0
- Neu: Kanal `vystorm:web` für den optionalen Vystorm-Client-Mod (Fabric/NeoForge, Repo `Vystorm/vystorm-client`, Protokoll in dessen `PROTOCOL.md`). Der Mod meldet sich mit HELLO, Core antwortet mit WELCOME (Basis-URL der Web-Plattform, Overlay-Taste). Auf OPEN_REQUEST stellt Core über `WebServices.issueLogin(…, "client")` ein Einmal-Token aus (gleiche Prüfungen und gleiches Audit wie `/web`, kein Chat-Link), sonst DENIED mit Grund. Streng: ≤ 1 KiB, feste Richtung, HELLO einmal, OPEN_REQUEST ratenbegrenzt (`web.client.open-per-minute`, Burst 3), nach 20 kaputten Nachrichten wird der Spieler bis zum Rejoin ignoriert, Tokens nie im Log. Plugin-Nachrichten geben nichts frei.
- Neu: `web.ClientServices` – `hasClient`, `capabilities`, `clientState`, `overlayAvailable`, `show(spieler, modul, seite)` (Overlay statt Kisten-/Dialogmenü, `false` = normales Menü öffnen) und `close`.
- Neu: `config.yml` `web.client` (`enabled`, `overlay`, `preload`, `hotkey-module`, `open-per-minute`).
- Neu: die Capability `web-client` (0.19.0).

## 0.18.1
- Geändert (Sicherheit): Ein Login-Link, den ein Spieler im Spiel holt und erst nach dem Ausloggen einlöst, nutzt jetzt die beim Ausstellen gemessenen Rechte statt der Bukkit-Standardwerte (vorher wurde z. B. ein per LuckPerms negierter `default: true`-Knoten im Web erlaubt).
- Geändert (Sicherheit): Der eingebettete Server schließt Verbindungen, deren Schreibvorgang länger als `web.limits.write-timeout-seconds` (neu, Standard 30) hängt (Slow-Read-DoS).
- Geändert (Sicherheit): `max-concurrent-handlers` zählt einen Handler, bis er wirklich endet, auch nach dem 504 (vorher konnten Handler, die Interrupts ignorieren, unbegrenzt anwachsen).
- Geändert (Sicherheit): Ratenlimits und Verbindungen je IP zählen IPv6 je /64.
- Geändert (Sicherheit): Zu lange Ziele, zu viele Header oder zu große Körper aus dem Gateway-Tunnel beantwortet Core mit 414/431/413, statt den Tunnel abzubrechen.
- Geändert (Sicherheit): Abweisungen ohne Sitzung (falscher `Origin`) werden je Client höchstens 20-mal pro Minute auditiert. Nach dem automatischen Erzeugen des Gateway-Geheimnisses verlieren „andere“ Systembenutzer die Rechte an `config.yml`. Live-Verbindungen je Spieler werden atomar gezählt, ein Fehler beim Aufbau gibt den Platz frei.
- `WEB_SECURITY.md`: neuer Abschnitt „Restrisiken und Betrieb“.

## 0.18.0
- Neu: Live-Kanal Server → Browser. `WebServices.push(spieler, modul, typ, json)` schickt kleine, typisierte Ereignisse an alle offenen Tabs und Overlays des Spielers. Zugestellt wird per Server-Sent Events unter `GET /core/live` (Sitzungs-Cookie, `Sec-Fetch-Site`/`Origin`-Prüfung). Ereignisse mit gleichem Modul und Typ werden je Spieler innerhalb von `web.live.coalesce-ms` zusammengefasst. Die Ereignisgröße ist begrenzt, ebenso die Verbindungen je Spieler und insgesamt. Beim Verbinden liefert `WebModule.Builder.snapshot(WebSnapshot)` den aktuellen Zustand, danach kommen nur noch Änderungen (`{"kind":"ready"}` trennt beides). Logout, Entzug, Bann und Sitzungsende beenden den Kanal (`bye`). Im Gateway-Modus läuft der Stream durch den Tunnel (`res` mit `stream`, `chunk`, `end`, `cancel`).
- Neu: statische Spieldaten mit Version. `WebServices.publishData(owner, modul, name, json)` stellt `/data/<modul>/<name>.<hash>.json` (unveränderlich cachebar) und `/data/<modul>/<name>.json` (ETag) bereit. `/core/session` listet die URLs, offene Live-Kanäle bekommen `{"kind":"data"}` bei einer neuen Version. Für Module mit Berechtigung ist der Abruf nur mit Sitzung möglich (`private`).
- Neu: `web.ui-dir` (Standard `web/ui`). Liegt dort ein gebautes `vystorm-web-ui` (`index.html` und `assets/`), liefert Core es unter `/`, `/login`, `/app/…` und `/assets/…` aus (gehashte Dateien `immutable`), sonst gibt es die eingebaute Übersicht. Liefert das Gateway die Oberfläche, ist das nur der Rückfall.
- Neu: `WebContext` (Spieler, Sitzung, `sync`) als gemeinsame Basis von `WebRequest` und `WebSnapshot`. `/core/session` meldet je Modul `assets`, `live` und `data`, `/core/login` zusätzlich `module`.
- Neu: Das Profil-Modul `core` pusht den Typ `status` (online, Welt, Level) bei Join, Quit, Levelwechsel und Weltwechsel. `/core/web.js` kann `VystormWeb.live(...)`.
- Neu: die Capability `web-live` (0.18.0).

## 0.17.0
- Neu: Web-Plattform `web.WebServices` für Browser-Seiten aller Vystorm-Plugins (erster Nutzer: der Talentbaum von Vystorm Skills). Plugins registrieren `WebModule`e mit statischen Dateien aus ihrem Jar (`/m/<id>/`) und `WebRoute`s (`/api/<id>/…`, `WebHandler` → `WebResponse`, Bukkit-Zugriffe über `WebRequest.sync`). Core übernimmt Login, Sitzungen, CSRF/Origin, Berechtigungen (je Anfrage), `requiresOnline`, Ratenlimits, Körpergrenzen, Handler-Timeouts, Sicherheits-Header und ein Audit-Log (`plugins/vystorm_core/web/audit.log`). Browser und Client sind nie Autorität.
- Neu: `/web [modul|logout|status]` mit der Berechtigung `vystorm.web.use` (Standard: alle). Der Befehl schickt einen Einmal-Link (`<public-url>/login#<token>`, 256 Bit, 60 s, einmalig). Die Sitzung läuft per `HttpOnly`/`SameSite=Strict`-Cookie (`__Host-`/`Secure` bei HTTPS), gleitend 30 min, höchstens 12 h. Sitzungen enden bei `/web logout`, Bann und Serverstopp. Die Konsole kann mit `/web link <spieler|uuid> [modul]` Links ausgeben.
- Neu: `WebServices.issueLogin(player, modul, issuer)` → `WebLogin` (Token und Basis-URL ohne Chat-Nachricht, z. B. für einen Client-Mod), `loginLink`, `sendLink`, `publicUrl`, `revokeSessions`.
- Neu: zwei Betriebsarten in `config.yml` unter `web` (Standard `enabled: false`): `embedded` (eigener strikter HTTP/1.1-Server, Standard `127.0.0.1:8190`, für Reverse-Proxy mit TLS) und `gateway` (ausgehender, HMAC-authentifizierter WebSocket-Tunnel zum externen Node.js-Gateway, Repo `Vystorm/vystorm-web-gateway`). Beide nutzen denselben Dispatcher. Das Tunnel-Protokoll steht in `WEB_SECURITY.md`.
- Neu: eingebautes Modul `core` („Profil“) zum Testen von Anfang bis Ende. Dazu kommen die gemeinsame Oberfläche `/core/web.js` und `/core/web.css` sowie eine Modulübersicht unter `/`.
- Neu: `CoreLifecycle` entfernt beim Disable eines Plugins dessen Web-Module.
- Neu: die Capability `web` (0.17.0).

## 0.16.0
- Neu: `progression.CharacterServices` mit `CharacterSource` (`level`, `maxLevel`, `awardXp(player, amount, source, sourceLevel)`). Vystorm Skills stellt darüber das Charakterlevel (Hauptklasse, Talentpunkte) bereit; Vystorm-Mobs, Quests und Events vergeben Charakter-XP, ohne das Skills-Plugin zu kennen. Beim Disable des Anbieters räumt `CoreLifecycle` die Registrierung ab.
- Neu: die Capability `character` (0.16.0).

## 0.14.0
- Neu: gemeinsame MySQL/MariaDB-Datenbank für alle Vystorm-Plugins. Konfiguriert wird sie in Cores neuer `config.yml` unter `database.shared` (Standard `enabled: false`). Core baut den Hikari-Pool `Vystorm-Shared` asynchron auf, testet ihn und loggt das Ergebnis. Ein Fehler lässt Core weiterlaufen. Beim Disable wird der Pool geschlossen.
- Neu: `database.SharedDatabase` (`dataSource`, `available`, `status`, `failure`, `whenReady`, `tableName`, `ID`) und `database.SchemaMigrator` (versionierte Schema-Schritte je Plugin in `vystorm_schema_versions`). In `DataBaseInterface` gibt es die Default-Methoden `shared()`, `isSharedAvailable()`, `sharedReady()` und `sharedTableName(prefix, table)`. `DataBaseHandler.registerDataSource(ID, pool, type)` ist neu, der geteilte Pool steht dort unter der ID `vystorm`.
- Neu: die Capability `shared-database` (0.14.0).

## 0.13.0
- Neu: `spawn.SpawnPointService` mit `SpawnPoint` ist ein gemeinsames Register fest gesetzter Spawnpunkte je Region (`art:id`), mit Rolle, Thema, Level und Attributen. Das bauende Plugin meldet die Punkte (`sync`, `retain`), Spawner lesen sie (`inRegion`, `all`, `revision`). Wird ein Plugin deaktiviert, verliert es seine Punkte über `CoreLifecycle`.
- Neu: die Capability `spawn-points` (0.13.0).

## 0.12.0
- Neu: „Vystorm vor Mythic“. `ItemRefs.register(owner, namespace, priority, resolver)` mit `PRIORITY_VYSTORM`, `PRIORITY_DEFAULT` und `PRIORITY_BRIDGE` sowie `ItemRefs.providers(namespace)`. Ein Namespace darf jetzt mehrere Provider haben. Der erste aktive Provider, der die ID kennt, gewinnt. Die Reihenfolge ist: Priorität, dann der Owner des `ItemServices`-Providers, dann die jüngere Registrierung.
- Neu: eingebaute Rückfälle in `ItemRefs` für `mythic:<Item>` (MythicMobs) und `mmoitems:TYPE:ID` (MMOItems). Sie greifen erst hinter allen registrierten Providern und nur, wenn das Plugin aktiv ist. Dazu kommen `mythic.MmoItemsBridge`, `MythicMobsBridge.active()` und `MythicMobsBridge.item(id, amount)`.
- Neu: `mob.MobRefs` (`id`, `source`, `vystormKnows`, `spawn`) löst Mob-Referenzen zuerst über Vystorm-Mobs auf, MythicMobs ist nur Rückfall. Mit `vystorm:ID` gibt es keinen Rückfall.
- Neu: die Capabilities `item-ref-priority` und `mob-refs` (0.12.0).
- Geändert: Registrieren zwei Plugins denselben `ItemRefs`-Namespace, ersetzt das zweite das erste nicht mehr, und es kommt keine Warnung mehr. Beide bleiben registriert und werden nacheinander gefragt. Kennt der erste Provider die ID nicht oder wirft er, wird der nächste gefragt. Bisher war das Ergebnis dann leer.

## 0.11.5
- Geändert: Die Befehlsrechte (`PermissionCheck`) akzeptieren Wildcards entlang des Pfads (`a.*`, `a.b.*`).
- Doku: AGENTS.md, README und dieses Changelog sind aktualisiert. Neu sind die Tests für `TaskGroup`.

## 0.11.4
- Geändert: `NBTHandler` legt für abgefragte Blöcke ohne Tags keinen Cache-Eintrag mehr an. `PlayerActivityService` verlängert TRAVELLING höchstens etwa einmal pro Sekunde. `ItemView.is(null)`, `DefaultGuiHandler.getInventory(<unbekannt>)` und fehlerhafte Kurven-YAML werfen nicht mehr.
- Deprecated: `PlayerPickupItemListener` (wird nicht mehr registriert).

## 0.11.3
- Neu: `ApiCompatibilityTest` mit Baseline `src/test/resources/api-baseline.txt` (0.10.0).
- Geändert: Die GUI-, Dialog-, Vault- und Permission-Singletons sind synchronisiert. Thread-Verträge sind dokumentiert.

## 0.11.2
- Neu: `ChatActions.button(label, hover, permission, action)`, `ChatActions.buttonOnce(...)`, `DataEntryFiles`, `DefaultSaveSelector.DEFAULT_FORMAT`.
- Geändert: Dialoge rufen `cancel` auch beim Ablauf nach 60 s auf. Permission-Wildcards (`a.*`, `a.b.*`) greifen, offline-UUIDs liefern `false`. Daten-Einträge sind nach Datei statt nach Ordner geschlüsselt. Der Save-Selector fällt auf `file` zurück.
- Deprecated: `DataStorageInterface.convertDataEntry`/`exportDataEntry` (nicht implementiert), `DataBaseEntry`, `Saver.saveDataEntryExtern`.

## 0.11.1
- Neu: `VersionedStore.CorruptStoreException` und ein Konstruktor mit Quarantäne-Callback. `ThreatServices.purgeExpired()`, `DefaultThreatScoreService.purgeExpired()`.
- Geändert: `Saver.saveAll` speichert trotz Einzelfehlern alle Einträge (atomar, unveränderte Dateien bleiben unberührt) und wirft am Ende den ersten Fehler. `RessourceHandler` lädt aus dem Jar des aufrufenden Plugins und ergänzt fehlende Default-Schlüssel wirklich. `LanguageSystem` liest `language/language.yml`, `ColorCorrector` übersetzt `&`-Codes korrekt. Die DB-Pools werden beim Disable geschlossen.

## 0.11.0
- Neu: `CoreApi` (`VERSION`, `atLeast`, `has`, `since`, `capabilities`, `api`). `API` wird im Bukkit-`ServicesManager` registriert. In `ItemServices`: `repair`, `damage`, `encode`, `decode`, `templateExists`, `provenance`. Neu ist außerdem `lifecycle.ProviderFailures`.
- Geändert: Alle `ItemServices`-Aufrufe sind abgesichert (Fallback statt Exception). Registrierungen, die ein fremdes Plugin ersetzt, werden gewarnt. `RuntimeContextHub.query` überspringt deaktivierte Owner. `CombatRegistry.evaluate` läuft ohne globalen Lock.
- Deprecated: `main.onReload()` (wurde nie aufgerufen).

## 0.10.3
- Neu: `lifecycle.CoreLifecycle` (räumt beim Plugin-Disable alle Registries auf), `MobExtensions.sink(Plugin, TriggerSink)`, `MobExtensions.hasSink()`, `_coreMethods.dataStorage.AtomicFiles`.
- Geändert: Spielerdaten werden bei Enable/Disable geladen bzw. gespeichert, fehlende Daten werden lazy nachgeladen. Das Region-Tracking startet in `onEnable`.

## 0.10.2
- Geändert: `/nbt` erfordert `vystorm_core.admin`. HikariCP und PostgreSQL kommen über `libraries:` in `plugin.yml`.

## 0.10.1
- Neu: `ResourcePacks.initialize()`, `ResourcePacks.shutdown()`.
- Geändert: Resource-Pack wird auch nach dem ersten Build gesendet. Ältere Builds werden verworfen, nach einem Plugin-Disable wird neu gebaut. Die Konfiguration wird validiert, und es gibt eine Warnung bei localhost-URL. Der HTTP-Server erlaubt nur GET und HEAD.

## 0.10.0
- Neu: `item.ItemServices`/`ItemProvider`, `pack.ResourcePacks`/`AssetRegistry`, `progression.DisciplineServices`.

## 0.9.0
- Neu: Mob-Plattform (`mob.MobServices`, `RelationServices`, `MobContextServices`, `mob.extension.MobExtensions`) und `item.ItemRefs`.

## 0.8.0
- Neu: `_uiMethods.chat.ChatActions`.

- Add `party-activities`: immutable readiness snapshots and bounded provider-reported session progress through PartyServices, additive defaults and disabled-provider guards.
