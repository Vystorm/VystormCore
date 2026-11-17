## Core 0.51.0: Bukkit/Spigot verification ? 2026-11-07

Core now supplies Adventure through Bukkit's library loader and isolates Paper-only listeners. The common paths have real Bukkit/native equivalents; no replacement Bukkit or Paper API classes are bundled.

Verified so far: official BuildTools Spigot 26.2, Java 25, isolated fresh test server, Core 0.51.0 API snapshot v3. Core starts and `/vadmin diagnostics`, `/vhelp`, and `/vregion list` dispatch and respond. This is a startup/command smoke test, not certification of every in-game feature. Native item/entity persistence was separately exercised on real Spigot 26.2 and Purpur 26.2 in both directions: item components/PDC, stored horse inventory/equipment/owner, new UUID, single spawn, and malformed-data rejection.

The unit suite additionally loads common services and registered listeners in an isolated classloader containing the genuine Spigot API and Adventure, with no Paper API classes. Focused tests cover Bukkit fallbacks, experience boundaries, callback owner cleanup, inventory-frame validation and passenger rollback. `-PspigotApiJar=<official shaded API jar>` supplies an already downloaded API; otherwise the test resolves the official Spigot snapshot repository.

The native helper probe also passed on real Spigot 26.2: scoreboard custom names and blank numbers, deterministic vanilla enchanting, item hover data including PDC, inventory-array roundtrip, and live GameProfile skin properties. Reproducible probe source: `scripts/compat/CoreNativeSmoke.java`.

The final full Core check completed with 1,160 tests, zero failures, and 12 database skips. Subsequent targeted checks cover the split 26.x attack/interact packets and the 26.3 chunk-record coordinates, retaining negative chunk coordinates correctly.

A connected offline test client additionally passed on official Spigot 26.3 and CraftBukkit 26.3: full component chat/item hover, actionbar, title, sound, preserved profile, 49 observed sent chunks, a virtual-entity attack, and an owner-bound chat callback that executed once despite two attempts. French-locale commands (`/vhelp`, Winter status/calendar) responded. The final CraftBukkit path uses the existing LOWEST-priority join recovery because plain Bukkit has no Spigot pre-spawn event. No plugin errors occurred in these client runs.

Camera rendering, world generation, custom dimensions and the full dependent-plugin collection require their separate server checks. The historic Paper 1.20.6 compile baseline remains; it does not certify Spigot/CraftBukkit 1.20.6. Do not hot-reload. No Folia claim.

## Core 0.48.0: Paper 1.20.6–26.x

Core now targets Java 21 bytecode and Paper API 1.20.6. Use the Java version required by the server itself on newer releases. `compat1206` compiles main against the maintainer's local 1.20.6 libraries, without a newer Paper API; native adapters are compiled separately and packaged in the same JAR. Public API compatibility is checked against the existing baseline.

Without native dialogs, Core uses clickable chat forms (one input at a time, then action buttons, 60-second expiry). `/vmenu` replaces the pause-menu entry. Native Paper dialog objects supplied by another plugin still require a dialog-capable server; use the portable `compat.dialog` API for shared code. Missing pale-oak materials use oak/vines, and item-model hints are omitted. Packet-only biome/display/chunk features retain their own runtime restrictions. No server has been started for this change; the historical server validations below do not constitute a 1.20.6 runtime smoke test.
# Minecraft 26.3 — 2026-10-30

Builds: **Paper 26.3 #231** (8a170a3, stable) and **Purpur 26.3 #2655**, Java 25 (Temurin 25.0.4.1), Windows 11, offline mode, fresh worlds, shared database on MariaDB 11.4.8. 26.3 is protocol 777, world version 5023, resource pack format 97.1, data pack format 121; Java 25 is enough.

Tested together on both servers: all 33 Vystorm plugins (16 free incl. Chat and Worlds, 17 paid incl. WorldForge, which Factions depends on) plus BloodMoon and VaultUnlocked 2.20.1, first with the released jars (Core 0.44.0) and then with the next jars swapped in on the same worlds (Core with client displays and the ClientBiomes fix below, Boards, Estates, Insomnia, Worlds, SpawnDirector, Dangerous Sky/Underground, Items, Mobs).

- Every plugin enables on Paper and Purpur, `/vadmin status` lists 31 active modules, `/vadmin diagnostics` and `/vadmin loot` answer, and every plugin's main command answers from the console.
- Worldgen runs with force-loaded chunks in the overworld, nether, `world_sky` and `world_depths`: Veins, CustomTrees, Winter (forced winter), Dangerous Sky (islands, temples, wrecks, clouds), Dangerous Underground (cave biomes, loot rooms, traps, mines), WorldForge/Factions (capital rebuilt via `/vfactions retry`).
- Restart cycles (4 per server): data reloads, no `.broken` files.
- Client displays: the packet probe passes unchanged ("Client displays ready on Paper/Purpur 26.3"), `/boards render` reports packet mode.
- ClientBiomes: `supported()` accepted only "26.2", so Winter fell back to particles on 26.3. 26.3 keeps the 26.2 biome packet format (bytecode compared against the 26.2 server jar), so 26.3 is now accepted (next Core, 0.45.0). With a protocol-777 bot in a winter area the server sent chunks_biomes packets on both servers without errors.
- Client-only chunks (`ClientChunks`, next Core 0.45.0): `supported()` and `prepare` (a test chunk built and encoded with the server codec) pass on Paper 1.21.8, 1.21.11, 26.1.2, 26.3 and Purpur 26.2, 26.3; a protocol-776 bot on Purpur 26.2 decoded the preview chunks and later received the real chunks over them.
- Version strings: no Vystorm code parses "1.x" Minecraft versions. Remaining hard-coded checks: Insomnia's day-nap wake check (fixed for 26.3), WorldForge's schematic reader (DataVersion 4903 only, by design for its bundled schematics, which still load), BloodMoon's data pack (declares format 107 but loads on 26.3 and its clock works).

Not caused by 26.3 (also seen on 26.2): CityClaim needs `database.shared` (MySQL/MariaDB) and disables itself without it; Factions marks capitals FAILED (`NoClassDefFoundError: VystormCityApi`) while CityClaim is installed but disabled, `/vfactions retry` fixes them once CityClaim runs; DeepInventory's flatfile store was locked on Windows hosts (fixed in the next DeepInventory). Note: a fresh 26.3 `server.properties` has `white-list=true`.

Not tested: real 26.3 clients (rendering), proxies, payments, web UI, Folia.

# Compatibility checks — 2026-10-22

Minimum for the current six-plugin collection: **Paper/Purpur 1.21.8, Java 25**. Use Core 0.28.1 wherever Core is installed. The historic `-26.2` filename suffix does not express the new minimum.

Validated builds: Paper 1.21.8 #60, 1.21.11 #132, 26.1.2 #74, 26.2 #134 and 26.3 #211; Purpur 1.21.8 #2497 and 26.3 #2654. On each: 17 XP checks, 321 block/dialog checks, 162 tree generation cases / 500 checks including actual world storage/reload/rebuild and listener registration. Seven restarts confirmed all six plugins/dependencies and the enabled pause-menu data pack. Current collection unit results: 1,265 passed, eight database tests skipped, zero failures; the unchanged BetterPortals result is reused.

Paper 1.21.7 is excluded because Player.closeDialog() is absent. 1.21.6 lacks the required native dialog API. The new API declaration is supported by functional tests, not by startup alone. Compile against Paper 1.21.11 / Adventure 4 for this range; newer Adventure builder call descriptors are not backward compatible.

Tested dependencies: VaultUnlocked 2.20.1 and ProtocolLib 5.5.0-SNAPSHOT-f0e959a. Automatic ProtocolLib update checks were disabled in test fixtures after an updater-only shutdown race. ProtocolLib warns that its author has not tested 26.3. Real-client portal rendering/teleportation, proxy/payment crash recovery, database integration, optional web and client/resource-pack combinations were not certified. Resource-pack formats must match the actual server/client version. No Folia/plain-Spigot or paid-plugin compatibility claim. Do not hot-reload.

Full local evidence is in `~/Documents/Codex/2026-10-22/free-plugin-compat/`; the sanitized report and reproducible probe sources are versioned in the vystorm-site repository under `tools/marketplace/reports/2026-10-22-backport-*` and `tools/marketplace/compatibility/`. No live deployment was performed.
