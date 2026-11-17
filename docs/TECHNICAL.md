# Vystorm Core: technical details

Goes with the [README](../README.md). This file covers how Core runs on a server and how it is built. The plugin API itself is in [API.md](API.md), the web security model and tunnel protocol in [WEB_SECURITY.md](../WEB_SECURITY.md).

## Package layout

The existing packages and the singleton/override access stay as they are:

- `_coreMethods`: data, players, resources, plus shared NBT and priority handling in `eventRegulator`.
- `_advancedMethods`: inventory interactor, area module and the other advanced handlers (structure and world generators, particles, interaction hitboxes, plugin messaging).
- `_integrations`: Vault and permission checks with wildcards (`api.luckperms()`, through the Bukkit permission API; there is no LuckPerms-specific API).
- `_parsersDebuggerEtc`: debugger and message parser.
- `_uiMethods`: GUIs, books, dialogs, settings, admin dashboard, commands.
- `bundleclasses`: registration and listeners; `main.getApi()` stays the legacy API entry point.

Service packages that plugins use without the providing plugin (runtime hubs, combat, regions, parties, mobs, items, web, lang, worlds and more) are listed in `AGENTS.md` and documented in [API.md](API.md).

Fixes made to the legacy parts over time, still relevant when reading old code:

- `api.vault()`: optional economy queries and deposits/withdrawals with a `Transaction` result, amount checks and the current provider.
- `api.player()`: `experience`, `withdrawExperience`, `depositExperience`; player settings are initialised and loaded with the existing defaults.
- `api.data().dataEncoder()`: item/inventory round trips through Paper's native serialisation, including meta, PDC and empty slots.
- YAML loading, saving and `saveAll()` were fixed; the newer `DataEntry` methods remain. XML reads the actual content and does not allow external entities.
- `api.nbt()`: typed item tags via `NamespacedKey`/`PersistentDataType`, shared string tags for items, entities and players, and persistent block tags. Removing your own tags leaves other namespaces alone. Historical invalid prefixes are normalised.
- Inventory interactor: current NBT overrides, priorities, `canProcess` and `blocksOthers`; separate shift clicks, drags without running the same action twice, and drop forwarding.
- Priority handling: unknown tags are skipped and identical actions run once. The block, entity and player event methods are wired through the existing listeners.

Still not implemented: parts of the CSV/cloud/database entries and the separate UI book editor. The old pickup listener has no processing.

## Build and tests

Java 25 toolchain with Java 21 bytecode, Gradle 9.4.1. The main source set compiles against the libraries of a Paper 1.20.6 server (see the build section of the [README](../README.md#for-developers)); the adapter and proxy source sets and the tests use Paper API `1.21.11-R0.1-SNAPSHOT`:

```sh
bash gradlew test build
```

JAR: `build/libs/Vystorm_Core-<version>-Vystorm-Core-26.2.jar` (version in `build.gradle`, changes in `CHANGELOG.md`).

The automated tests cover, among other things, debug switches and recursion, economy failure cases, XP limits, YAML/XML persistence, NBT block data, priorities and inventory interactions. The debugger tests also cover overload, write errors, separate size quotas with reduced test limits, and a hard-killed separate Java process. Before the particle budget was added, the build was also checked on an isolated Purpur 26.2 server (bootstrap, GUI, optional Vault, item/inventory persistence, NBT, player settings, debug files, the advanced world generators, interaction hitboxes, plugin messaging and particles). The particle budget itself is tested automatically (shared limits, tick changes, reduced geometry, thread checks); a new server load test is still open.

**Shared database integration test.** `SharedDatabaseIntegrationTest` only runs when `VYSTORM_TEST_DB_HOST` (only `127.0.0.1` or `localhost`), `VYSTORM_TEST_DB_PORT`, `VYSTORM_TEST_DB_NAME`, `VYSTORM_TEST_DB_USER` and `VYSTORM_TEST_DB_PASSWORD` are set; otherwise it is skipped. Never run it against the live database.

## Shared database

All Vystorm plugins can share one MySQL/MariaDB database. The credentials live only in `plugins/vystorm_core/config.yml` under `database.shared`, never in plugin configs. Each plugin creates its own tables with its own prefix (`<prefix>_<table>`).

```yaml
database:
  shared:
    enabled: true
    type: mysql            # mysql | mariadb (both via MySQL Connector/J, which ships with the server)
    host: db.example.com
    port: 3306
    database: vystorm
    username: "..."
    password: "..."
    ssl-mode: PREFERRED    # DISABLED | PREFERRED | REQUIRED | VERIFY_CA | VERIFY_IDENTITY
    allow-public-key-retrieval: false
    pool:                  # all plugins share these connections
      maximum-size: 8
      minimum-idle: 2
      connection-timeout-ms: 10000
      idle-timeout-ms: 300000
      max-lifetime-ms: 900000     # at least 30 s below the server's wait_timeout
      keepalive-time-ms: 60000
      leak-detection-threshold-ms: 0
    properties: {}         # extra Connector/J properties, override Core's defaults
```

The default is `enabled: false`. After enable, Core builds the pool in its own thread and tests it with one connection. The result goes to the console log (`[SharedDatabase] Shared database available: …` or `… NOT available …`). Core also logs the server's `wait_timeout` and `max_user_connections` and warns when `max-lifetime-ms`, `keepalive-time-ms` or `maximum-size` don't fit them. A failure leaves Core running; plugins then see the database as unavailable. There is no automatic retry, so a failed start needs a restart. Short outages while running are handled by Hikari, which rebuilds the connections itself. On disable Core closes the pool.

The pool is also registered under the id `vystorm` in the legacy handler (`api.database().getDataSource("vystorm")`). Calling `unregisterDataBase("vystorm")` would close it for every plugin. How plugins use the pool (`sharedReady()`, table names, `SchemaMigrator`, threading rules) is in [API.md §11](API.md#11-shared-database).

## Web platform

Core serves browser pages for all Vystorm plugins (the first one was the talent tree of Vystorm Skills). Browser and client are never authoritative: Core checks session, permissions, CSRF, rate limits and body sizes, and every change runs through a handler on the server. With `web.enabled: false` (the default) nothing starts.

### Modes

| `web.mode` | When | How |
| --- | --- | --- |
| `embedded` | own host, reverse proxy possible | Core opens its own strict HTTP/1.1 server (default `127.0.0.1:8190`). TLS is done by a proxy in front (Caddy, nginx). Put the proxy's IP into `web.embedded.trusted-proxies`. |
| `gateway` | server host without a free port (G-Portal) | Core connects **outbound** via `wss://` to the Node.js gateway ([Vystorm/vystorm-web-gateway](https://github.com/Vystorm/vystorm-web-gateway)) on a VPS or home PC. The gateway serves the browsers and passes each request through the HMAC-authenticated tunnel to Core. Core answers exactly as in embedded mode. |

Minimal gateway setup:

```yaml
web:
  enabled: true
  mode: gateway
  public-url: https://web.vystorm.de          # address players open (the gateway)
  gateway:
    url: wss://web.vystorm.de/_vystorm/tunnel
    secret: ""                                # empty: Core generates a secret on start and saves it here
```

The generated `web.gateway.secret` goes into the gateway as `VYSTORM_GATEWAY_SECRET`. Every key is documented with comments in the `config.yml` in the JAR.

### Login and sessions

`/web` sends a clickable one-time link `<public-url>/login#<token>`. The token is 256 bits, valid for 60 seconds and usable once. It sits in the URL fragment, so it never shows up in server or proxy logs or in the referrer. The login page trades it for a session cookie (`HttpOnly`, `SameSite=Strict`, with HTTPS also `Secure` and `__Host-`). A session lasts 30 minutes sliding, 12 hours at most. `/web <module>` opens a module directly. If the player has the client mod and trusts the server, `/web` and `/web <module>` open the page directly (since 0.25.3, like `WebServices.openOrLink`); otherwise the link is sent. `/web logout` ends all of the player's sessions, as do a ban and a server stop. Admins see the platform state with `/web status`. The console can print a link with `/web link <player|uuid> [module]` for support and testing; this lands in the audit log (`plugins/vystorm_core/web/audit.log`).

### Client mod

Players with the optional mod [Vystorm/vystorm-client](https://github.com/Vystorm/vystorm-client) get the web pages as an in-game overlay. The mod fetches a one-time token over the `vystorm:web` channel (same checks as `/web`, issuer `client` in the audit log) and loads the same URLs as the browser. Sessions the mod signs in end when the player quits (`web.client.end-sessions-on-quit`). HUD elements go over `vystorm:ui`, native screens over `vystorm:panel`. Settings are under `web.client` in `config.yml`.

### Which web UI is served

The frontend is its own app ([Vystorm/vystorm-web-ui](https://github.com/Vystorm/vystorm-web-ui), Preact and Vite). Order at runtime:

1. in gateway mode, the gateway's `uiDir` (UI updates without a Minecraft restart),
2. a build with `index.html` in `web.ui-dir` (`plugins/vystorm_core/web/ui/`),
3. the build bundled in Core's JAR (`webui/vystorm-web-ui-dist.zip`, since 0.24.0),
4. Core's minimal built-in pages.

`/web status` shows which one is active. Client routes: `/`, `/login`, `/app/<module>`. Core paths: `/core/…`, `/api/…`, `/data/…`, `/m/…`.

To ship a new UI build with Core, run `scripts/update-web-ui.sh` (builds the UI including the CSP check and writes `webui/vystorm-web-ui-dist.zip` plus `webui/web-ui.properties`), commit both, bump Core's version and release. Core's Gradle build needs no npm.

### Testing locally

Set `web.enabled: true` (embedded, no `public-url`), then use `/web` in game or `/web link <uuid>` in the console and open the link on the same machine. The `core` module ("Profile") shows name, status and Core version, and its "Send" button checks CSRF, rate limit and audit.

## Client protocol sync

The panel protocol classes are copied from the client repo into `web/internal/clientproto/` by `scripts/sync-clientproto.sh` (it deletes old files there). `scripts/sync-clientproto.sh --check` and `ClientProtoSyncTest` fail when the copy differs.

## Debug logger

The existing calls `low`, `mid`, `high`, `shutdown` and the format `[Class line]` remain. The debugger uses one background writer with a queue of 1024 entries. LOW and MID are normally written in batches every 250 ms and synced to the local disk; HIGH and SHUTDOWN wake the writer at once. Player access and requesting a server shutdown still happen on the server thread.

### Size limits

| Area under `plugins/vystorm_core/debug/` | Levels | Max log data |
| --- | --- | --- |
| `low-mid/` | LOW, MID | 512 MiB (536,870,912 bytes) |
| `high-shutdown/` | HIGH, SHUTDOWN | 512 MiB (536,870,912 bytes) |

Files rotate at 8 MiB or at the change of day. Before an area would exceed its limit, only its own oldest log files are removed; the active files count too. LOW/MID never pushes out critical files. The limits apply to the new `.log` files; old YAML logs stay untouched outside these areas. Switch files and small bookkeeping files don't count toward the limit.

### Switches and failure handling

`debug/states.yml` is the configuration, for example `DefaultInteractor.LOW: false`. Switches are read once at initialisation, then checked from memory and re-read in the background every two seconds. New class switches are saved through a temporary file and an atomic replace. Invalid YAML or invalid switch values never overwrite the last valid state. Messages accepted before a switch change are still processed.

Empty NBT priority passes produce no message. `PriorityHandler.LOW`, `ParticleHandler.LOW` and `ParticleHandler.MID` default to off for new switches. Values already set explicitly in `states.yml` stay valid; set them to `false` there for quiet operation. Errors (`HIGH`) stay visible.

When the queue is full, unconfirmed LOW messages are limited first; HIGH/SHUTDOWN may additionally push out unconfirmed MID messages. Confirmation jobs are never pushed out but are rejected outright when there is no room. The independent emergency logger reports write errors and dropped messages at most every five seconds. Normal messages are capped at 8192 characters and cut with a marker if needed. Over-long confirmed messages are rejected.

### Confirmed writes

```java
boolean stored = api.debug().highConfirmed(
        "[MyHandler 42] Essential diagnosis",
        java.time.LocalDateTime.now(),
        java.time.Duration.ofSeconds(2));
```

`true` confirms that this HIGH message was written completely and its log area synced to the local file system. `false` means HIGH is off for this class. The caller must handle `IOException`, `TimeoutException` and `InterruptedException`. A timeout can mean the message is written later; it neither confirms nor cancels the write. The call can block the calling thread, so use it sparingly.

`flush(Duration)` waits for the messages queued before it that are still pending and syncs both areas; earlier write errors are reported. Losses from overload show up separately in the emergency log. `reloadStates(Duration)` applies switch changes on demand. Custom debuggers without these features throw `UnsupportedOperationException` through the new default methods instead of pretending to store anything.

On normal plugin stop and on handler replacement the old writer is flushed and closed. The wait at stop is capped at five seconds, so a hanging disk can't block the server forever. A lock prevents two writers in the same area. On restart an incomplete tail of the last file written is trimmed back to the last complete line.

Messages accepted asynchronously but not yet synced can be lost in a hard crash. The 250 ms interval is not a guaranteed loss limit. A confirmed message is still subject to rotation later. The persistence path is designed for the local Linux file system; the tests simulate process kills and write errors, not a physical power cut.

## History of plugin integrations

Core grew by moving shared code out of the existing plugins. Each step kept the plugins' storage formats and business rules in the plugins.

- **XPVaultEconomy (Core 0.2.2).** Added `api.player().setExperience(player, points)` and `api.overridePlayerHandler(...)`. XPVaultEconomy 1.3.0 uses the shared XP access and the debugger; accounts and storage stay in the economy plugin. The setter needs a non-negative value and the server thread. The combination was checked on an isolated Purpur 26.2 server (legacy Vault/Vault 2, Core Vault access, initial XP import, setter, handler swap); report in the XPVaultEconomy project under `build/reports/xp-core-integration.log`.
- **VystormMarket (Core 0.2.3).** Added `api.vault().fractionalDigits()` and `api.vault().economySnapshot()`. Market 1.1.0 uses the Vault binding, shared XP access and the debugger. Checked together with XPVaultEconomy 1.3.0 on an isolated Purpur server (purchase, offline seller payout, item hand-over, YAML remainder, XP fallback, handler swap; player access simulated). Report: `build/reports/market-core-integration.log` in the Market project.
- **BlockIntegrations and DeepInventory (Core 0.2.4 and 0.2.5).** Added `api.player().giveExperience(player, points)` (keeps Bukkit's `giveExp` behaviour); `withdrawExperience` and `depositExperience` now respect an overridden XP setter. BlockIntegrations 2.9.0 uses this for towers, combinations and furnaces; mcMMO skill XP stays with mcMMO. The extra `particle().spawn` overload with X/Y/Z spread, speed and data keeps existing effects within the same tick budget. 0.2.5 added `api.vault().economyIdentity()`. DeepInventory 1.1.0 uses Core for XP, Vault and debug messages; offers and journal stay in the plugin, and a provider or XP handler change during the async preparation cancels a purchase before charging. Native XP is still only available without Vault installed.
- **CustomTrees (Core 0.2.6).** The tree building of CustomTrees 1.18.0 moved into `_advancedMethods/structureGeneratorHandler`; CustomTrees 1.19.0 keeps sapling events, config, permissions, Iris post-processing and the CoreProtect/SmoothTimber integration. Verified on an isolated Purpur server by comparing full block data and random state against the old 1.18.0 JAR for nine species and six growth modes (four seeds per tree size), plus custom generator, real block placement, registry cleanup, handler swap, glowing leaves and the `STARTUP` order. Vault, XPVaultEconomy, VystormMarket, BlockIntegrations with mcMMO and DeepInventory started with 0.2.6 without errors. Iris, CoreProtect, SmoothTimber and a 200-player load were not part of that run. Report: `build/reports/customtrees-core-acceptance.log` in the CustomTrees project.
- **Shared menus (Core 0.2.7).** `/vmenu` and `api.gui()` managed inventories. VystormMarket 1.2, VystormCityClaim 1.2, DeepInventory 1.2 and BlockIntegrations 2.10 moved their menus; purchase, storage and upgrade rules stay in the plugins. XPVaultEconomy and CustomTrees had no inventory menus to move.
- **Inventory changes (Core 0.2.8)** and **native dialogs (Core 0.2.9)** followed; `/vmenu` itself switched to dialogs, real item views stay managed inventories.
- **Admin dashboard (Core 0.2.12).** `/vadmin` started with a built-in catalogue of 18 modules for the known plugins; more modules register through `API.admin()` and are removed when their plugin is disabled.

The API details of all of these are in [API.md](API.md) (sections 6, 7, 10, 12 and 16).
