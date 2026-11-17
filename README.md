# Vystorm Core

Vystorm Core is the shared base that every Vystorm plugin runs on. On its own it adds little gameplay. What it gives a server is one consistent way to use all Vystorm plugins: a main menu, a settings screen, an admin dashboard, translations, one database connection for everything and an optional web panel. Install it first, then add the plugins you want.

[Player guide](TUTORIAL.md) · in game: `/vmenu tutorials` · plugin authors: [docs/API.md](docs/API.md)

## Features

- **One main menu.** `/vmenu` lists every menu the installed plugins register, sorted into categories like Character, Economy or World. Players see only what they may open. There is a search, up to 20 favourites and the last eight menus opened. The Recent tab lets players clear their own history while keeping their favourites. Tutorials for each plugin live here too, plus an optional "First steps" guide for new players.
- **One settings screen.** `/vsettings` collects the personal options of all plugins (your language, for example) and, for admins, the server options. Admins can stage changes across several sections and apply them together after a preview, or save a set of server values as a profile and load it again later (`plugins/vystorm_core/settings-profiles/`).
- **Edit plugin configs in game.** `/vadmin config <plugin>` opens a plugin's `config.yml` as a settings page. See [Configuration in game](#configuration-in-game).
- **Config change history with undo.** Every change made in the in-game config editor is recorded: who changed which value of which plugin when, with the old and the new value. `/vadmin config history` lists the changes newest first (or press "Change history" in the config menu); one click on **[undo]** puts the old values back, saves the file with a backup and reloads the plugin. If a value was changed again in the meantime, Core warns you and only overwrites it when you click "undo anyway". Undos are recorded too, so they can be undone as well.
- **Admin dashboard.** `/vadmin` shows one module per plugin with its common admin actions as buttons. Actions that change something show the exact command and ask before running it. `/vadmin permissions` lists every permission node of Core and all Vystorm plugins, with default and description, and whether you have it.
- **Update check.** Core checks SpigotMC every 6 hours for newer versions of the installed Vystorm plugins. Admins see them on join (click the message for details) and with `/vadmin updates` (clickable download links). Hide updates you do not want yet with `update-check.ignore` (`"Plugin"` or `"Plugin 1.4.0"`). Switch off with `update-check.enabled: false`.
- **Diagnostics.** `/vadmin diagnostics <player>` shows which plugin features are active, running in a fallback mode or unavailable for that player. `/vmenu access <player>` explains why a menu is hidden from someone. `/vadmin migration <id>` runs a dry-run data conversion and reports record counts and problems without importing anything.
- **Translations.** English, German, French, Brazilian Portuguese, Italian, Russian and Simplified Chinese (`zh_cn`) ship with Core; plugins bring their own `lang/<code>.yml` files. By default every player gets their Minecraft language (Traditional Chinese clients get Simplified Chinese, `de_at`/`de_ch` get German, other missing texts fall back key by key along a configurable chain to English). Players pick another language with `/language` (a menu with flags and how much of each language is translated) or in `/vsettings`. Admins edit the texts in `plugins/<Plugin>/lang/`, add a language by dropping in a file such as `fr.yml`, see missing and untranslated texts with `/vadmin language audit`, and hand a file to a translator with `/vadmin language export` / `import`.
- **Player menu.** Sneak and right-click another player with an empty hand to see what you can do with them: the actions of your Vystorm plugins (invite to a party or city, view stats, ...) in one menu instead of commands with player names. It only opens when a plugin offers an action, otherwise the click works as before. `/vmenu player <name>` opens it by command. Players can switch it off for themselves in `/vsettings`.
- **Server news.** Admins write short news posts in game: open `/vmenu news` and press "Write", or use `/vadmin news post <title> | <text>`. Players who have not read a post yet get one clickable chat line when they join (and right away if they are online when it is published); the main menu shows "News (2 new)". Clicking opens all posts in a dialog. Players can turn the join line off in `/vsettings`, old posts disappear after 30 days. Writers can correct existing posts with an Edit button; corrections keep the author, date and read status without another announcement. Other Vystorm plugins can post too, e.g. to announce an event.
- **Menus as real dialogs.** Text input, confirmations and guides open as native Minecraft dialogs. Inventories with real items stay inventories.
- **Shared database.** All Vystorm plugins can use one MySQL or MariaDB database through a single connection pool. The credentials live only in Core's config.
- **Web panel.** With the web platform switched on, `/web` sends a player a one-time login link to the server's web pages (talent tree, companions, settings and whatever else plugins add). Hosts without a free port can use the external gateway.
- **Client mod support.** Players with the optional [Vystorm client mod](https://github.com/Vystorm/vystorm-client) get menus and settings as native screens (key K opens the main menu), HUD bars, toasts, world markers and the web pages as an in-game overlay. Players without the mod lose nothing: every feature has a vanilla menu, dialog or chat link.
- **Trade chat.** `/vtrade <message>` reaches everyone with the receive permission. Messages are capped at 240 characters with a two-second cooldown per player.
- **Plugin worlds and visits.** Plugins can run their own worlds (arenas, talent worlds). Core saves the player's full inventory and state before a visit and gives it back on exit, disconnect, server stop or after a crash.
- **Smaller things.** Clickable coordinates in chat (teleport for staff), a shared region register (`/vregion`), a shared budget of 4,096 particles per tick for effects plugins draw through Core, and debug logs in `plugins/vystorm_core/debug/` that stay under 512 MiB per area.

## Installation

- Paper/Purpur **1.20.6–26.x**. Core uses **Java 21 bytecode**; use Java 21 on 1.20.6 and the Java version required by your newer server (Java 25 for 26.x). See [compatibility checks](COMPATIBILITY.md).
- On servers without native dialogs, Core menus and forms use clickable chat. The pause-menu data pack is unavailable; use `/vmenu`. Pale oak uses oak/vines where its materials do not exist. Packet-only effects depend on runtime adapter support.
- Optional: Vault, for plugins that use an economy

Put the JAR into `plugins/` and start the server. Core loads early (`load: STARTUP`) so plugins can use it during world generation. On first start the server downloads the libraries Core declares (Adventure and its text serializers, HikariCP and the PostgreSQL driver), so the server needs internet access once. `config.yml` and the language files are created on first start.

Windows support in 0.33.1 fixes debug logging and managed-world visit snapshots.
Files are still flushed and renamed atomically. Linux additionally flushes directory
metadata; the JDK Windows filesystem does not support that operation, so Windows
cannot promise persistence of newly created/renamed directory entries after power loss.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/vmenu` (also `/menu`, `/vm`, `/m`) | open the main menu | `vystorm_core.menu` |
| `/vmenu search`, `/vmenu list`, `/vmenu <plugin:key>` | search menus, list menu keys, open one directly | `vystorm_core.menu` |
| `/vmenu settings` | open the settings overview | `vystorm_core.menu` |
| `/vmenu news` | read the server news (writers also get Write and Delete buttons) | `vystorm_core.news` |
| `/vmenu player <player>` | open the player menu about someone (same as sneak + right-click) | `vystorm_core.playermenu` |
| `/vmenu access <player>` | why a player can or can't see each menu | `vystorm_core.admin` |
| `/vsettings` (also `/einstellungen`, `/vystormsettings`) | personal settings; `server` for server settings, `<plugin:section>` for one section | `vystorm_core.settings` |
| `/vsettings draft` | change several server settings and apply them together | `vystorm_core.settings` + server section rights |
| `/vsettings profile export\|import <name>` | save or load server settings as a profile | `vystorm_core.admin` |
| `/web [module]` | open a web page (directly with the client mod, otherwise a login link) | `vystorm.web.use` |
| `/web logout` | end all of your web sessions | `vystorm.web.use` |
| `/web status` | state of the web platform | `vystorm_core.admin` |
| `/web link <player\|uuid> [module]` | console only: print a login link for support | console |
| `/vtrade <message>` | write in the trade channel | `vystorm_core.chat.trade.send` |
| `/vadmin` (also `/admin`, `/ad`) | admin dashboard; `list`/`status` print the modules | `vystorm_core.admin` |
| `/vadmin config [plugin]` | edit plugin configs in game | `vystorm_core.config.<plugin>` |
| `/vadmin config history [plugin] [page]` | changes made in the config editor, newest first, with undo links | `vystorm_core.config.<plugin>` (only those plugins) |
| `/vadmin config undo <number> [force]` | put the values of one change back | `vystorm_core.config.<plugin>` |
| `/vadmin news [list]`, `/vadmin news write`, `/vadmin news delete <number>` | list the news, write one in a dialog, delete one | `vystorm_core.admin` |
| `/vadmin news edit <number> [title \| text]` | correct a post; omit the text to open a prefilled editor (players only) | `vystorm_core.admin` + `vystorm_core.news.write` |
| `/vadmin news post <title> \| <text>` | post from chat or the console; `\n` in the text starts a new line | `vystorm_core.admin` |
| `/vadmin permissions [plugin] [page]` | list permission nodes | `vystorm_core.admin` |
| `/language` (also `/lang`, `/sprache`, `/语言`) | choose your language in a menu; `/language <code\|auto>` sets it directly, `/language list` lists them | `vystorm_core.language` |
| `/language <player> [code\|auto]` | show or set another player's language (also from the console) | `vystorm_core.language.others` |
| `/vadmin language [reload]` | show the language setup; reload all language files and settings without a restart | `vystorm_core.admin` |
| `/vadmin language audit [plugin\|*] [lang]` | missing and untranslated texts per plugin and language, with a clickable export | `vystorm_core.admin` |
| `/vadmin language export <plugin> <lang> [yml\|csv]`, `import <file> [plugin] [lang]` | write all texts with the English source to `translations/`; merge a reviewed file into `plugins/<Plugin>/lang/` (with backup) | `vystorm_core.admin` |
| `/vadmin diagnostics [player]`, `/vadmin migration <id>` | feature status, dry-run data migration | `vystorm_core.admin` |
| `/vregion at [player]`, `/vregion list [kind]` | show the shared region register | `vystorm_core.admin` |
| `/vworlds [list\|visits]` | plugin worlds and running visits | `vystorm_core.admin` |
| `/vworlds restore <player>`, `/vworlds check <player>` | give a player their saved state back, check the held item | `vystorm_core.admin` |
| `/vworlds legacy <player\|uuid> retry\|archive` | recover or set aside an old talent-world save | `vystorm_core.admin` |
| `/nbt <key> <value>` | put a Core tag on the held item | `vystorm_core.admin` |

Commands and tab completion only show what the sender may use.

## Permissions

| Permission | Default | What for |
| --- | --- | --- |
| `vystorm_core.menu` | everyone | `/vmenu` |
| `vystorm_core.settings` | everyone | `/vsettings` (server sections also need admin rights) |
| `vystorm_core.language` | everyone | `/language` |
| `vystorm_core.language.others` | OP | `/language <player> ...` (included in `vystorm_core.admin`) |
| `vystorm_core.playermenu` | everyone | player menu by sneak + right-click on a player, `/vmenu player` |
| `vystorm_core.news` | everyone | read the news and get the "new posts" line on join |
| `vystorm.web.use` | everyone | `/web` and the web panel |
| `vystorm_core.chat.trade.send`, `vystorm_core.chat.trade.receive` | everyone | write in and receive the trade channel |
| `vystorm_core.admin` | OP | dashboard, server settings, config editor, `/nbt`, `/vregion`, `/vworlds`, `/web status`, `/vmenu access` |
| `vystorm_core.config.*`, `vystorm_core.config.<plugin>` | OP | edit all or one plugin's config (included in `vystorm_core.admin`) |
| `vystorm_core.news.write` | OP | write, edit and delete news in game (included in `vystorm_core.admin`) |
| `vystorm_core.chat.teleport` | OP | clicking coordinates in chat teleports you there |
| `vystorm_core.worlds.bypass` | OP | enter and build in plugin worlds in creative or spectator mode without a visit |
| `vystormCore.debug` | OP | receive Core's HIGH debug warnings in chat |
| `vcore.gui.access.<menu>`, `vgui.access.<menu>` | OP | open old chest menus (`vgui` is the old name) |
| `vcore.bookgui.<book>` | OP | open Core books |
| `vystorm_core.*` | OP | everything above |

## Configuration

`plugins/vystorm_core/config.yml` has these parts. New keys from later versions are read from the JAR defaults even if your file doesn't have them yet.

- `language`: `default` = `auto` (each player's Minecraft language) or a fixed code for everyone; `server` (default `en`) = language of the console and of players whose language has no file; `force: true` gives everyone the server language; `min-coverage` (default 50) = how complete a language must be over all plugins to be offered in `/language` (languages Core ships are always offered); `fallback` = extra steps of the fallback chain (`zh_tw: zh_cn`, `de_at` → `de` needs no entry). Players can still choose their own unless `force` is on.
- `player-menu`: `enabled` (default `true`) switches the sneak + right-click player menu on or off for everyone, `require-sneak: false` lets a plain right-click with an empty hand open it.
- `news`: `enabled`, `notify-on-join` (the clickable line on join), `announce-new-posts` (line to online players when a post is published), `max-age-days` (default 30, 0 = keep), `max-posts` (default 20). Posts live in `news.yml`.
- `config-history`: `enabled` and `max-entries` (default 200) for the config change history in `config-history.yml`.
- `database.shared`: the shared database, off by default. See below.
- `web`: the web platform, off by default (`web.enabled: false`). `web.client` controls the client-mod features (overlay, HUD, panels) and is on by default. Setup for both modes is in [docs/TECHNICAL.md](docs/TECHNICAL.md#web-platform), the security model in [WEB_SECURITY.md](WEB_SECURITY.md).

### Configuration in game

Any Vystorm plugin that registers with the config editor can be edited from the game: `/vadmin config <plugin>`, or `/vsettings` → server settings → Plugin configuration, or the plugin's page in `/vadmin`. Each top-level key becomes a section. Comments from the default file become descriptions, numbers with a known range become sliders, everything else is a checked text field.

Saving writes only the keys you changed, into the file as it is on disk, and keeps comments. Before the first save of a file after each start Core makes a backup `<file>.bak-<date>`. Then the plugin reloads, or the editor tells you the change takes effect after a reload or restart. Passwords, tokens, hosts and everything under `database.shared` are never shown or written by the editor. Missing default keys are added to `config.yml` automatically (with a backup). Core's own config is in there too; language changes apply at once, web settings after a restart.

<a id="gemeinsame-datenbank"></a>
### Shared database

```yaml
database:
  shared:
    enabled: true
    type: mysql            # mysql or mariadb
    host: db.example.com
    port: 3306
    database: vystorm
    username: "..."
    password: "..."
```

The credentials go only here, never into the plugin configs. Each plugin creates its own tables with its own prefix. Core tests the connection right after start and logs `[SharedDatabase] Shared database available` or `NOT available`. If it fails, Core keeps running and the plugins treat the database as unavailable; after fixing it, restart the server (there is no automatic retry). Pool settings, TLS options and the warnings Core logs about `wait_timeout` are explained in [docs/TECHNICAL.md](docs/TECHNICAL.md#shared-database). Never point a test server at the live database.

## For developers

Build with the Java 25 toolchain (all source sets target Java 21). The main source set compiles against the libraries of a Paper 1.20.6 server, so the build needs the `libraries` directory that a Paper 1.20.6 server creates on its first start (it contains `paper-api-1.20.6-R0.1-SNAPSHOT.jar`). By default the build looks in `<user home>/source/tools/smoke-1206/libraries`; pass another location with `-Ppaper1206Libraries=<directory>`. The offline `compat1206` check is part of `check`:

```sh
bash gradlew test build
```

The JAR ends up in `build/libs/Vystorm_Core-<version>-Vystorm-Core-26.2.jar`. Changes per version are in [CHANGELOG.md](CHANGELOG.md).

- [docs/API.md](docs/API.md): the full plugin API with setup, an example plugin, threading rules, localization, settings, the config editor, menus, dialogs, web modules, client-mod elements, panels and plugin worlds. Written so it also works as context for AI coding assistants.
- [docs/TECHNICAL.md](docs/TECHNICAL.md): how Core runs on a server: package layout, debug logger, shared database pool, web platform modes, client protocol sync and the history of plugin integrations.
- [WEB_SECURITY.md](WEB_SECURITY.md): security model of the web platform and the gateway tunnel protocol.

## Server polls

`/vpoll start 120 Question | Answer A | Answer B` starts an anonymous single-choice poll. Players click an answer in chat or reopen the poll with `/vpoll`. Voting again changes the existing vote, including after reconnecting. Counts stay hidden until the poll ends; Core then announces all answer counts, including ties or zero votes. Joining players see the running poll. Polls and the last result are memory-only and cleared on restart.

| Command | Purpose | Permission |
| --- | --- | --- |
| `/vpoll` | Show the running poll and clickable answers | `vystorm_core.poll` (everyone) |
| `/vpoll results` | Show the last completed poll | `vystorm_core.poll` (everyone) |
| `/vpoll start <seconds> <question> \| <answer> \| <answer>` | Start a poll | `vystorm_core.admin` |
| `/vpoll close` | Finish early and publish results | `vystorm_core.admin` |
| `/vpoll cancel` | Cancel without publishing results | `vystorm_core.admin` |

| Config key | Default | Limits |
| --- | --- | --- |
| `polls.enabled` | `true` | Controls starting new polls |
| `polls.max-duration-seconds` | `600` | Clamped to 10–3600 |
| `polls.max-options` | `6` | Clamped to 2–10 |
| `polls.max-text-length` | `120` | Per question/answer, clamped to 10–240 |

Missing config keys are merged with a `config.yml.before-polls` backup; custom values stay unchanged. Edit with `/vadmin config vystorm_core` to apply to the next poll. Running polls finish normally.


## Free feature update 0.52.0

Add a guided setup wizard at `/vsettings setup` (also `/einstellungen setup`). It discovers installed plugins with registered, visible server settings, captures named shared presets from explicitly selected sections, lets administrators select the target plugins and previews every before/after value and activation policy before confirmation.

Presets use the existing `settings-profiles/<name>.yml` format, per-provider version checks and bounded settings drafts. Select section keys shown by the wizard; capture supports at most 96 scalar values. Config-editor sections are allowed only with explicit selection; broad legacy exports still exclude whole configs, and secrets, hidden values and unsupported structures stay excluded. Existing presets can be edited while the server is stopped. Missing selected settings, revoked permissions, version mismatches and stale baselines block application. Each provider retains its own save/reload hooks. Cross-plugin application is not a database transaction: a provider failure is reported as a partial result, and config-editor history/backups remain available for review.

API additions: `SetupWizard.open(Player, SettingsRegistry)`, `SetupWizard.plugins(SettingsRegistry, Player)`, `SettingsProfiles.exportProfile(..., Set<String> selectedSections)` and `SettingsProfiles.importProfile(..., Set<String> selectedPlugins)`; capability `setup-wizard`, since 0.52.0. Existing methods and provider lifecycles remain unchanged.

## License

Copyright (c) 2026 Esoren

Versions after 0.62.0 are licensed under the GNU Lesser General Public License v3.0 (LGPL-3.0), see [LICENSE](LICENSE) and [COPYING](COPYING). Plugins may link against Vystorm Core without becoming LGPL themselves; changes to Vystorm Core itself must stay under the LGPL.
Version 0.62.0 and earlier were released under the MIT License ([LICENSE-MIT-until-0.62.0.txt](LICENSE-MIT-until-0.62.0.txt)) and stay available under those terms.
