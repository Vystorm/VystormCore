# Vystorm loot system (0.44.0)

Capabilities: `loot-tables` (0.34.0, tables and registry), `loot-rolls` (0.44.0, everything on this page) and
`mob-loot` (0.44.0, mob drops: see below).
The 0.34 API ([SHARED-LOOT.md](SHARED-LOOT.md)) keeps working unchanged.

## One model: tables → categories → rolls

1. **Tables** (`namespace:path`) are weighted lists. `weight` is a **rarity rank 0..10000** (0 = commonest,
   10000 = rarest); each entry gets `10001 - weight` tickets. An entry is an item (`minecraft:diamond`,
   `vystorm:<template>`, any ItemRefs namespace), an empty draw (`item: ''`) or a reference to another table
   (`table: 'vystorm_core:vanilla/rare'`, which then rolls that table with its own draws).
2. **Categories** (`category:<name>`) are tables for one loot situation (boss chest, fishing, expedition ...).
   Every Vystorm plugin rolls a category and passes its own list for that source as `self`. By default a category
   only draws `self`, so every plugin keeps its own contents and odds; when there is no own list the `fallback`
   entries (vanilla mixes) are used. Admins tune all plugins at once by adding entries to a category.
3. **Rolls** (`LootRoll`) draw from a table or category with options: luck, tier filters, pity, unique,
   exclude, seed and a context for conditions.

**Tiers** are bands of the rarity rank:

| Tier | Weight | Tickets | vs. a weight-0 entry |
|---|---|---|---|
| common | 0-3999 | 10001-6002 | 1x - 0.6x |
| uncommon | 4000-6999 | 6001-3002 | 0.6x - 0.3x |
| rare | 7000-8999 | 3001-1002 | 0.3x - 0.1x |
| epic | 9000-9799 | 1001-202 | 0.1x - 0.02x |
| legendary | 9800-10000 | 201-1 | ≤ 0.02x |

## Files (plugins/vystorm_core, editable with `/vadmin config vystorm_core`, then `/vadmin loot reload`)

- `loot-tables.yml`: the 0.34 file (`core:common`, `core:treasure`) and your own tables.
- `loot-vanilla.yml`: the basic vanilla lists (`vystorm_core:vanilla/*`, tiers and themes) and the situation mixes
  (`vystorm_core:default/*`). Standard Minecraft items only, so plugin items appear only where a plugin or admin adds them.
- `loot-mobs.yml`: one bonus table per vanilla mob type (`vystorm_core:mob/zombie`, `.../skeleton` ...), see "Mob drops".
- `loot-categories.yml`: the categories (`categories:` section; keys without namespace become `category:<key>`).

Files are created once and never overwritten. One invalid file keeps all previous tables. A table id may exist only once.

### Entry keys

| Key | Meaning |
|---|---|
| `item` / `table` | item ref, `''` for an empty draw, or a table id (`self` = the caller's own table) |
| `weight`, `mob-weight`, `chest-weight` | rarity rank 0..10000 (per source override) |
| `amount`, `min-amount`, `max-amount` | stack size 1..64 (for `table:` the number of times that table is rolled) |
| `enchant-levels` | `'5-20'` or `[5, 20]`, 1..30: enchants like an enchanting table (books become enchanted books) |
| `treasure-enchants` | `true` allows treasure enchantments (mending, frost walker ...) |
| `potion` | potion type (`healing`, `strong_swiftness` ...) for potion, splash/lingering potion, tipped arrow |
| `min-y`, `max-y` | block height of the roll location |
| `environments` | `[normal, nether, the_end]` |
| `biomes` | `[minecraft:lush_caves, ...]` |
| `min-difficulty`, `max-difficulty` | plugin-defined difficulty (dungeon level, sky layer, threat) |
| `fallback` | `true`: only used when no other entry of the list can be drawn |
| `chance` | 0..1, only in `mode: each` tables: own chance of this entry per roll |

A condition is ignored when the roll does not know the value (a roll without location keeps all height entries).
Table keys: `rolls` or `min-rolls`/`max-rolls` (0..27), `chance` (0..1, gates the whole table once),
`mode: weighted` (default: each roll draws one entry by weight) or `mode: each` (every entry is checked on each roll
with its own `chance`, like classic mob drop lists; weights then only set the tier, luck raises the chance of rarer
entries, pity uses a weighted draw).

## Categories

| Category | Used for | Fallback mix |
|---|---|---|
| `category:dungeon_chest` | dungeon/structure chests | `default/dungeon_chest` (3-6 draws, all tiers) |
| `category:boss_chest` | boss chests | `default/boss_chest` (4-7 draws, uncommon..legendary) |
| `category:mineshaft_cart` | mine carts, mines | `default/mineshaft_cart` (2-4, up to rare) |
| `category:treasure_vault` | vaults, treasure rooms, crystals | `default/treasure_vault` (3-6, treasure heavy) |
| `category:cave_loot_room` | cave loot rooms (gold/ice/moss/lava variants are the plugin's own lists) | `default/cave_loot_room` (epic only below y 0) |
| `category:sky_temple` | sky temples | `default/sky_temple` |
| `category:sky_wreck` | sky wrecks, ruins | `default/sky_wreck` |
| `category:fishing` | fishing (normal/cave/sky are the plugin's own lists) | `default/fishing` (about vanilla odds) |
| `category:expedition` | pet expeditions (walk/forest/cave) | `default/expedition` (up to rare) |
| `category:mob_drop_common` / `_elite` / `_boss` | mob drops | `default/mob_drop_*` |
| `category:quest_reward`, `contract_reward`, `arena_reward`, `event_reward` | rewards | `default/reward` |
| `category:farm_rare_find` | rare farm finds | `default/farm_rare_find` |
| `category:gathering_find` | tree shaking, gathering bonus finds | `default/gathering_find` |
| `category:shrine` | shrines | `default/shrine` |
| `category:meteor` | meteors | `default/meteor` |

Early sources (expedition, common mob drops, farm and gathering finds, mine carts, fishing) never reach the
legendary list in the defaults; boss chests and vaults do.

Tuning examples in `loot-categories.yml`:

```yaml
categories:
  boss_chest:
    rolls: 2                      # two draws: own list + maybe the legendary list
    entries:
      - {table: self, weight: 0}
      - {table: 'vystorm_core:vanilla/legendary', weight: 9000}
      - {table: 'vystorm_core:default/boss_chest', weight: 0, fallback: true}
  fishing:
    chance: 0                     # no Vystorm fishing loot at all
    entries: [{table: self, weight: 0}]
```

**Precedence** for one source: the plugin's per-source config value (any table or category id) → the plugin's
default category → inside the category `self` (the plugin's own list) → `fallback` entries.
`LootTables.choose(configured, "category:x")` implements the first step (unknown or blank ids use the default).

## API

```java
// plugin enable: register own lists (one replace per owner: put everything into one map)
Map<String, LootTable> own = LootTableConfig.load(this, "loot-tables.yml");          // files from the jar/data folder
// or build tables in code, e.g. converted from an old config:
int[] ranks = LootTable.ranksFor(60, 30, 10);   // relative weights -> rarity ranks with the same odds
LootTables.replace(this, Map.of("myplugin:boss/warden", new LootTable(2, 4, 1, entries)));

// roll (server thread for RollContext.of(player); roll() itself is pure and thread-safe)
String id = LootTables.choose(getConfig().getString("loot.boss-chest"), "category:boss_chest");
LootResult loot = LootRoll.table(id)
        .context(RollContext.of(player).withSelf("myplugin:boss/warden").withSource(getName()).withDifficulty(level))
        .guarantee(LootTier.RARE)       // pity
        .generate();                    // items created; roll() only draws
loot.items().forEach(chest.getInventory()::addItem);
loot.bestTier().filter(t -> t.compareTo(LootTier.EPIC) >= 0).ifPresent(t -> player.sendMessage("Epic drop!"));
loot.problems().forEach(getLogger()::warning);
```

`LootRoll` options: `source(MOB|CHEST)` (default CHEST), `rolls(n)` (top level), `luck(points)`,
`weights(min,max)`, `tier(t)` (exactly), `minTier(t)` (t or rarer), `guarantee(t)` (pity: if no hit reached t,
the lowest hit is replaced by one draw of t or rarer; ignores the table chance), `exclude(refs)`, `unique(true)`
(no item ref twice), `seed(long)` / `random(RandomGenerator)`, `context(RollContext)`.

`LootResult`: `hits()` (item, amount, rarity, tier, per-draw probability, source table, entry, pity flag),
`items()` (after `generate()`; vanilla items stacked, custom items created one by one), `problems()` (unknown
tables, cycles, unresolvable items: skipped, never replaced by another roll), `best()`, `bestTier()`.

`LootTable.ranksFor(double...)` converts old relative weights (bigger = likelier) into ranks with the same odds
(rounded to 1/10001 of the largest weight). Leave out entries with weight 0: a rank always keeps at least one ticket.

**Luck.** Effective tickets = `tickets × (1 + 0.25 × luck)^(weight/10000)`, luck clamped to -3..20.
One luck point makes the rarest entries up to 25 % likelier relative to trash; negative luck favours common entries;
luck 0 changes nothing. `RollContext.of(player)` adds the vanilla Luck attribute and every `LootLuck` source:
`LootLuck.register(plugin, player -> points)` (each source clamped to ±10, removed on disable).

**Threads.** `roll()` uses no Bukkit API: call it from async populators. `generate()` creates items: vanilla items
are fine on any thread; custom item providers (VystormItems) belong on the server thread. `RollContext.of(...)`
reads the world/attributes: server thread (in populators build the context from your own values:
`RollContext.NONE.withY(y).withEnvironment("normal").withDifficulty(d)`).

**Speed.** Candidate weights are built once per table and roll into a cumulative array; each draw is a binary
search. Plain tables draw exactly like the 0.34 `LootTable.roll` (same random sequence), so seeded worlds keep
their chests. At most 8 nested references and 256 hits per roll; cycles are reported, not followed.
`LootTable.roll` and `LootTables.generate` (0.34) also follow references, but do not apply enchantments or potions:
use `LootRoll.generate()` for that.

## Mob drops: the mob decides what, its level how good (capability `mob-loot`)

One rule for every Vystorm plugin:

1. **What drops is defined by the mob.** Every mob has its own table: a custom mob's table (VystormMobs drop tables,
   BloodMoon/Insomnia tiers, Underground/Sky bosses ...) or, for vanilla mobs, `vystorm_core:mob/<entity type>` from
   `loot-mobs.yml` (`vystorm_core:mob/default` for types without one). The mob drop categories
   `category:mob_drop_common` / `_elite` / `_boss` stay as admin wrappers; the mob's own table is their `self`.
2. **How good it is depends only on the mob's level:** `luck = min(max, level × per-level)` (`config.yml`
   `loot.mob-level-luck.per-level` 0.05, `.max` 3.0: level 20 = +1, level 60+ = +3). Player luck (Luck attribute,
   `LootLuck` sources such as item `loot_luck` or pets) does **not** affect mob drops; it still counts for chests,
   fishing, expeditions and finds.
3. **Ranks may add pity:** `loot.mob-pity.common|elite|boss` (default none / none / `rare`: every boss drops at least
   one rare-or-better item when its table has one).

**Level sources:** VystormMobs level (Core mob service), SpawnDirector monster level (threat), BloodMoon/Insomnia tiers
mapped to levels in their configs, Underground/Sky bosses fixed levels in their configs. Vanilla mobs without a level
are level 0. `MobLoot.level(entity)` asks the Core mob service first, then every `MobLoot.levels(plugin, ...)` source.

**Precedence (one roll per kill):**

| Kill | Rolled by | Table (`self`) |
|---|---|---|
| VystormMobs mob | VystormMobs | `vystormmobs:mob/<id>` (its drops + drop table) |
| BloodMoon / Insomnia / Underground / Sky mob | that plugin (`MobLoot.claim` at spawn) | its own tier/boss table |
| any other mob killed by a player (vanilla, director) | SpawnDirector (`rewards.items.core-loot: true`) | `vystorm_core:mob/<type>` |
| VystormItems monster drop tables | added as an **extra part** to whichever roll above happens (`MobLoot.contribute`); Items only rolls itself when nobody else rolled the kill | `vystormitems:monsters/<type>` |

SpawnDirector and VystormItems skip mobs that another enabled plugin owns (`MobLoot.ownedByOther`), and every roller
marks the kill (`MobLoot.markRolled`), so a second plugin never rolls it again.

```java
// owner of a mob, at spawn:
MobLoot.claim(this, entity);
// at death (server thread): own table, level luck, rank pity, extra parts of other plugins
MobLoot.Drop drop = MobLoot.drop(this, entity, getConfig().getString("loot.boss-table"), "myplugin:boss/golem",
        40, MobLoot.Rank.BOSS, entity.getKiller());
event.getDrops().addAll(drop.items());
// or only the builder (roll() for async, hits post-processing):
LootRoll.forMob("category:mob_drop_elite", RollContext.of(loc).withSelf("myplugin:mob/x"), level, MobLoot.Rank.ELITE).roll();
LootRoll.table(id).mobLevel(level);   // any roll as a mob roll: luck from the level, player luck ignored
```

Preview: `/vadmin loot simulate category:mob_drop_common 10000 self=vystorm_core:mob/zombie level=40`.

## Admin commands (`vystorm_core.admin`)

- `/vadmin loot list [tables|categories]` – all ids with owner, entries and draws.
- `/vadmin loot show <id>` – entries with weight, tier and chance per draw.
- `/vadmin loot roll <id> [luck=1] [tier=rare] [min-tier=rare] [pity=epic] [rolls=3] [seed=42] [source=mob] [self=<table>] [difficulty=2] [y=-30] [level=40]` – preview, nothing is given.
- `/vadmin loot simulate <id> <n> [options]` – n rolls (max 20000): hits per roll, empty share, share per tier and per item.
- `/vadmin loot give <id> [player] [options]` – gives the items of one roll (testing enchantments, potions, custom items).
- `/vadmin loot reload` – reloads Core's loot files atomically (and the `loot.mob-*` settings of `config.yml`).

## Deutsch (Kurzfassung)

- **Ein Modell:** Tabellen (gewichtete Listen, Gewicht = Seltenheit 0..10000, höher = seltener) → Kategorien
  (`category:boss_chest` usw., eine pro Loot-Situation) → Würfe (`LootRoll` mit Glück, Stufenfilter, Pity, Seed).
- **Stufen:** common 0-3999, uncommon 4000-6999, rare 7000-8999, epic 9000-9799, legendary 9800-10000.
- **Basis-Listen** (`loot-vanilla.yml`): nur Standard-Minecraft-Items, `vystorm_core:vanilla/common` … `/legendary`
  und Themen (food, ores_ingots, tools_weapons, armor, building, redstone, magic, nether, end, ocean, treasure, fishing),
  dazu Mischungen `vystorm_core:default/<situation>`.
- **Kategorien** (`loot-categories.yml`): Jedes Plugin würfelt seine Kategorie und gibt seine eigene Liste als `self` mit.
  Standard: nur die eigene Liste (gleiche Inhalte und Chancen wie bisher). Fehlt sie, gelten die Vanilla-Mischungen.
  Wer z. B. alle Boss-Truhen aller Plugins ändern will, ergänzt `category:boss_chest`. Pro Quelle kann die Plugin-Config
  eine andere Tabelle oder Kategorie eintragen.
- **Glück:** Luck-Attribut + Plugin-Quellen (`LootLuck`); jeder Punkt macht die seltensten Einträge bis zu 25 % wahrscheinlicher.
  Gilt für Truhen, Angeln, Funde – **nicht** für Mob-Drops.
- **Mob-Drops:** WAS fällt, bestimmt der Mob (eigene Tabelle; Vanilla-Mobs `vystorm_core:mob/<typ>` aus `loot-mobs.yml`),
  WIE GUT, nur sein Level (`loot.mob-level-luck`: 0,05 Glück je Level, max. 3). Bosse: mindestens ein „rare“ (`loot.mob-pity`).
  Jeder Kill wird nur einmal gewürfelt: Mobs-Plugin > Event-/Dungeon-Plugins > SpawnDirector; VystormItems hängt seine
  Monster-Tabellen als Zusatzteil an.
- **Befehle:** `/vadmin loot list|show|roll|simulate|reload` zum Ansehen und Ausbalancieren.

## Basic vanilla lists (shipped contents)

"Per draw" is the chance of the entry on one draw of that list, with every condition met.

#### `vystorm_core:vanilla/common`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| rotten_flesh | 0 | common | 1-4 | 6.7 % |
| bone | 500 | common | 1-4 | 6.3 % |
| string | 800 | common | 1-4 | 6.1 % |
| stick | 0 | common | 2-6 | 6.7 % |
| coal | 800 | common | 2-6 | 6.1 % |
| torch | 1500 | common | 2-8 | 5.7 % |
| wheat | 1000 | common | 2-6 | 6.0 % |
| bread | 1500 | common | 1-3 | 5.7 % |
| apple | 2000 | common | 1-3 | 5.3 % |
| arrow | 2000 | common | 2-8 | 5.3 % |
| leather | 2500 | common | 1-3 | 5.0 % |
| paper | 2500 | common | 1-4 | 5.0 % |
| feather | 2000 | common | 1-4 | 5.3 % |
| gunpowder | 3000 | common | 1-3 | 4.7 % |
| flint | 2500 | common | 1-3 | 5.0 % |
| iron_nugget | 3000 | common | 2-8 | 4.7 % |
| gold_nugget | 3500 | common | 2-8 | 4.3 % |
| wheat_seeds | 1000 | common | 2-6 | 6.0 % |

#### `vystorm_core:vanilla/uncommon`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| iron_ingot | 4000 | uncommon | 1-4 | 8.2 % |
| copper_ingot | 4000 | uncommon | 2-6 | 8.2 % |
| gold_ingot | 5000 | uncommon | 1-3 | 6.8 % |
| redstone | 4500 | uncommon | 2-8 | 7.5 % |
| lapis_lazuli | 5000 | uncommon | 2-8 | 6.8 % |
| cooked_beef | 4500 | uncommon | 2-5 | 7.5 % |
| golden_carrot | 6000 | uncommon | 1-4 | 5.4 % |
| experience_bottle | 6000 | uncommon | 1-4 | 5.4 % |
| book | 5000 | uncommon | 1-3 | 6.8 % |
| bucket | 5500 | uncommon | 1 | 6.1 % |
| amethyst_shard | 5500 | uncommon | 1-4 | 6.1 % |
| iron_pickaxe | 6500 | uncommon | 1 | 4.8 % |
| iron_sword | 6500 | uncommon | 1 | 4.8 % |
| shield | 6000 | uncommon | 1 | 5.4 % |
| potion (healing) | 6000 | uncommon | 1 | 5.4 % |
| lead | 6500 | uncommon | 1 | 4.8 % |

#### `vystorm_core:vanilla/rare`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| diamond | 8000 | rare | 1-2 | 6.5 % |
| emerald | 7000 | rare | 1-4 | 9.8 % |
| golden_apple | 8000 | rare | 1 | 6.5 % |
| book (enchanted 10-20) | 7500 | rare | 1 | 8.1 % |
| iron_sword (enchanted 8-18) | 7500 | rare | 1 | 8.1 % |
| iron_chestplate (enchanted 8-18) | 8000 | rare | 1 | 6.5 % |
| bow (enchanted 8-18) | 7500 | rare | 1 | 8.1 % |
| ender_pearl | 7000 | rare | 1-3 | 9.8 % |
| saddle | 7500 | rare | 1 | 8.1 % |
| name_tag | 8000 | rare | 1 | 6.5 % |
| spyglass | 7500 | rare | 1 | 8.1 % |
| diamond_pickaxe | 8800 | rare | 1 | 3.9 % |
| music_disc_13 | 8500 | rare | 1 | 4.9 % |
| music_disc_cat | 8500 | rare | 1 | 4.9 % |

#### `vystorm_core:vanilla/epic`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| diamond | 9000 | epic | 2-5 | 11.5 % |
| diamond_sword (enchanted 20-30) | 9300 | epic | 1 | 8.0 % |
| diamond_pickaxe (enchanted 20-30) | 9300 | epic | 1 | 8.0 % |
| diamond_chestplate (enchanted 20-30) | 9400 | epic | 1 | 6.9 % |
| book (enchanted 25-30, treasure) | 9200 | epic | 1 | 9.2 % |
| netherite_scrap | 9500 | epic | 1 | 5.8 % |
| totem_of_undying | 9600 | epic | 1 | 4.6 % |
| heart_of_the_sea | 9500 | epic | 1 | 5.8 % |
| trident | 9600 | epic | 1 | 4.6 % |
| golden_apple | 9000 | epic | 2-3 | 11.5 % |
| sentry_armor_trim_smithing_template | 9200 | epic | 1 | 9.2 % |
| wild_armor_trim_smithing_template | 9200 | epic | 1 | 9.2 % |
| music_disc_pigstep | 9500 | epic | 1 | 5.8 % |

#### `vystorm_core:vanilla/legendary`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| enchanted_golden_apple | 9850 | legendary | 1 | 15.8 % |
| netherite_ingot | 9950 | legendary | 1 | 5.3 % |
| netherite_upgrade_smithing_template | 9900 | legendary | 1 | 10.5 % |
| netherite_scrap | 9800 | legendary | 2-3 | 21.0 % |
| silence_armor_trim_smithing_template | 9950 | legendary | 1 | 5.3 % |
| eye_armor_trim_smithing_template | 9900 | legendary | 1 | 10.5 % |
| diamond_sword (enchanted 30-30, treasure) | 9850 | legendary | 1 | 15.8 % |
| music_disc_otherside | 9850 | legendary | 1 | 15.8 % |

#### `vystorm_core:vanilla/food`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| bread | 0 | common | 2-5 | 12.9 % |
| apple | 1000 | common | 1-4 | 11.6 % |
| baked_potato | 1500 | common | 2-5 | 11.0 % |
| carrot | 1500 | common | 2-5 | 11.0 % |
| cooked_beef | 3000 | common | 2-4 | 9.1 % |
| cooked_porkchop | 3000 | common | 2-4 | 9.1 % |
| cooked_salmon | 3500 | common | 1-4 | 8.4 % |
| pumpkin_pie | 4500 | uncommon | 1-3 | 7.1 % |
| cookie | 4000 | uncommon | 2-6 | 7.8 % |
| honey_bottle | 5500 | uncommon | 1-2 | 5.8 % |
| golden_carrot | 6500 | uncommon | 1-3 | 4.5 % |
| golden_apple | 8800 | rare | 1 | 1.6 % |
| enchanted_golden_apple | 9950 | legendary | 1 | 0.1 % |

#### `vystorm_core:vanilla/ores_ingots`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| coal | 0 | common | 2-8 | 15.4 % |
| raw_copper | 1500 | common | 2-6 | 13.1 % |
| raw_iron | 3000 | common | 1-4 | 10.8 % |
| iron_ingot | 4000 | uncommon | 1-4 | 9.3 % |
| raw_gold | 5000 | uncommon | 1-3 | 7.7 % |
| gold_ingot | 5500 | uncommon | 1-3 | 6.9 % |
| redstone | 4000 | uncommon | 2-8 | 9.3 % |
| lapis_lazuli | 4500 | uncommon | 2-8 | 8.5 % |
| quartz ([nether]) | 4000 | uncommon | 2-6 | 9.3 % |
| emerald | 7500 | rare | 1-2 | 3.9 % |
| diamond | 8500 | rare | 1 | 2.3 % |
| diamond (y ≤ 0) | 8000 | rare | 1-2 | 3.1 % |
| netherite_scrap ([nether]) | 9700 | epic | 1 | 0.5 % |

#### `vystorm_core:vanilla/tools_weapons`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| stone_pickaxe | 0 | common | 1 | 10.5 % |
| stone_sword | 0 | common | 1 | 10.5 % |
| stone_axe | 500 | common | 1 | 9.9 % |
| bow | 2500 | common | 1 | 7.8 % |
| arrow | 1000 | common | 4-16 | 9.4 % |
| iron_pickaxe | 4500 | uncommon | 1 | 5.8 % |
| iron_sword | 4500 | uncommon | 1 | 5.8 % |
| iron_axe | 4500 | uncommon | 1 | 5.8 % |
| iron_shovel | 4000 | uncommon | 1 | 6.3 % |
| crossbow | 5000 | uncommon | 1 | 5.2 % |
| shield | 5000 | uncommon | 1 | 5.2 % |
| iron_sword (enchanted 5-15) | 7000 | rare | 1 | 3.1 % |
| iron_pickaxe (enchanted 5-15) | 7000 | rare | 1 | 3.1 % |
| bow (enchanted 5-20) | 7000 | rare | 1 | 3.1 % |
| crossbow (enchanted 5-20) | 7500 | rare | 1 | 2.6 % |
| diamond_sword | 8500 | rare | 1 | 1.6 % |
| diamond_pickaxe | 8500 | rare | 1 | 1.6 % |
| diamond_sword (enchanted 15-30) | 9300 | epic | 1 | 0.7 % |
| diamond_pickaxe (enchanted 15-30) | 9300 | epic | 1 | 0.7 % |
| diamond_axe (enchanted 15-30) | 9300 | epic | 1 | 0.7 % |
| trident | 9600 | epic | 1 | 0.4 % |
| mace | 9900 | legendary | 1 | 0.1 % |

#### `vystorm_core:vanilla/armor`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| leather_helmet | 0 | common | 1 | 9.4 % |
| leather_chestplate | 0 | common | 1 | 9.4 % |
| leather_leggings | 0 | common | 1 | 9.4 % |
| leather_boots | 0 | common | 1 | 9.4 % |
| chainmail_helmet | 3500 | common | 1 | 6.1 % |
| chainmail_chestplate | 3500 | common | 1 | 6.1 % |
| golden_helmet | 3000 | common | 1 | 6.6 % |
| golden_boots | 3000 | common | 1 | 6.6 % |
| iron_helmet | 4500 | uncommon | 1 | 5.1 % |
| iron_chestplate | 5000 | uncommon | 1 | 4.7 % |
| iron_leggings | 5000 | uncommon | 1 | 4.7 % |
| iron_boots | 4500 | uncommon | 1 | 5.1 % |
| shield | 4000 | uncommon | 1 | 5.6 % |
| turtle_helmet | 7500 | rare | 1 | 2.3 % |
| iron_chestplate (enchanted 5-15) | 7000 | rare | 1 | 2.8 % |
| iron_boots (enchanted 5-15) | 7000 | rare | 1 | 2.8 % |
| diamond_helmet | 8500 | rare | 1 | 1.4 % |
| diamond_boots | 8500 | rare | 1 | 1.4 % |
| diamond_chestplate (enchanted 15-30) | 9400 | epic | 1 | 0.6 % |
| diamond_leggings (enchanted 15-30) | 9400 | epic | 1 | 0.6 % |
| netherite_helmet | 9900 | legendary | 1 | 0.1 % |

#### `vystorm_core:vanilla/building`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| cobblestone | 0 | common | 8-32 | 11.1 % |
| oak_log | 500 | common | 4-16 | 10.6 % |
| oak_planks | 500 | common | 8-32 | 10.6 % |
| stone_bricks | 1500 | common | 8-24 | 9.4 % |
| bricks | 2500 | common | 4-16 | 8.3 % |
| glass | 2500 | common | 4-16 | 8.3 % |
| deepslate_tiles | 3000 | common | 8-24 | 7.8 % |
| lantern | 4000 | uncommon | 1-4 | 6.7 % |
| terracotta | 3500 | common | 8-24 | 7.2 % |
| white_wool | 2500 | common | 4-12 | 8.3 % |
| quartz_block | 6000 | uncommon | 4-12 | 4.4 % |
| copper_block | 6500 | uncommon | 1-4 | 3.9 % |
| obsidian | 7000 | rare | 1-4 | 3.3 % |

#### `vystorm_core:vanilla/redstone`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| redstone | 0 | common | 4-16 | 11.9 % |
| redstone_torch | 1000 | common | 2-8 | 10.7 % |
| lever | 1500 | common | 1-4 | 10.1 % |
| tripwire_hook | 2500 | common | 1-3 | 8.9 % |
| repeater | 3500 | common | 1-4 | 7.7 % |
| comparator | 5000 | uncommon | 1-2 | 6.0 % |
| piston | 4500 | uncommon | 1-3 | 6.5 % |
| sticky_piston | 5500 | uncommon | 1-2 | 5.4 % |
| observer | 5500 | uncommon | 1-2 | 5.4 % |
| hopper | 6500 | uncommon | 1 | 4.2 % |
| dispenser | 5500 | uncommon | 1 | 5.4 % |
| daylight_detector | 6000 | uncommon | 1 | 4.8 % |
| slime_ball | 5000 | uncommon | 1-4 | 6.0 % |
| target | 4000 | uncommon | 1 | 7.1 % |

#### `vystorm_core:vanilla/magic`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| lapis_lazuli | 0 | common | 2-8 | 11.8 % |
| glass_bottle | 500 | common | 1-4 | 11.2 % |
| experience_bottle | 3000 | common | 1-6 | 8.3 % |
| book | 2000 | common | 1-3 | 9.5 % |
| glowstone_dust | 3500 | common | 1-4 | 7.7 % |
| potion (healing) | 4000 | uncommon | 1 | 7.1 % |
| potion (swiftness) | 4500 | uncommon | 1 | 6.5 % |
| potion (night_vision) | 4500 | uncommon | 1 | 6.5 % |
| potion (fire_resistance) | 5500 | uncommon | 1 | 5.3 % |
| potion (regeneration) | 6000 | uncommon | 1 | 4.7 % |
| splash_potion (strong_healing) | 6500 | uncommon | 1 | 4.1 % |
| potion (strength) | 6500 | uncommon | 1 | 4.1 % |
| book (enchanted 5-15) | 6000 | uncommon | 1 | 4.7 % |
| book (enchanted 15-30) | 8000 | rare | 1 | 2.4 % |
| ender_eye | 7500 | rare | 1 | 3.0 % |
| potion (luck) | 8500 | rare | 1 | 1.8 % |
| book (enchanted 25-30, treasure) | 9300 | epic | 1 | 0.8 % |
| totem_of_undying | 9700 | epic | 1 | 0.4 % |

#### `vystorm_core:vanilla/nether`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| gold_nugget | 0 | common | 3-12 | 13.2 % |
| quartz | 1000 | common | 2-8 | 11.9 % |
| nether_wart | 2000 | common | 1-4 | 10.6 % |
| soul_sand | 1500 | common | 2-8 | 11.3 % |
| glowstone_dust | 2500 | common | 2-6 | 9.9 % |
| magma_cream | 4500 | uncommon | 1-3 | 7.3 % |
| blaze_powder | 5000 | uncommon | 1-3 | 6.6 % |
| gold_ingot | 4500 | uncommon | 1-4 | 7.3 % |
| obsidian | 5500 | uncommon | 1-3 | 6.0 % |
| crying_obsidian | 6000 | uncommon | 1-3 | 5.3 % |
| blaze_rod | 6500 | uncommon | 1-2 | 4.6 % |
| ghast_tear | 7500 | rare | 1 | 3.3 % |
| snout_armor_trim_smithing_template | 9000 | epic | 1 | 1.3 % |
| netherite_scrap | 9500 | epic | 1 | 0.7 % |
| wither_skeleton_skull | 9700 | epic | 1 | 0.4 % |
| ancient_debris | 9800 | legendary | 1 | 0.3 % |

#### `vystorm_core:vanilla/end`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| end_stone | 0 | common | 4-16 | 20.8 % |
| chorus_fruit | 1000 | common | 2-6 | 18.7 % |
| purpur_block | 2000 | common | 4-12 | 16.6 % |
| popped_chorus_fruit | 3500 | common | 2-6 | 13.5 % |
| ender_pearl | 5000 | uncommon | 1-3 | 10.4 % |
| end_rod | 4500 | uncommon | 1-4 | 11.4 % |
| shulker_shell | 8000 | rare | 1-2 | 4.2 % |
| dragon_breath | 8500 | rare | 1 | 3.1 % |
| spire_armor_trim_smithing_template | 9500 | epic | 1 | 1.0 % |
| elytra | 9950 | legendary | 1 | 0.1 % |

#### `vystorm_core:vanilla/ocean`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| kelp | 0 | common | 2-8 | 17.1 % |
| cod | 500 | common | 1-4 | 16.2 % |
| salmon | 1500 | common | 1-3 | 14.5 % |
| prismarine_shard | 3000 | common | 1-4 | 12.0 % |
| prismarine_crystals | 4000 | uncommon | 1-4 | 10.3 % |
| ink_sac | 2500 | common | 1-3 | 12.8 % |
| turtle_scute | 6500 | uncommon | 1 | 6.0 % |
| nautilus_shell | 7500 | rare | 1 | 4.3 % |
| wet_sponge | 7500 | rare | 1 | 4.3 % |
| tide_armor_trim_smithing_template | 9300 | epic | 1 | 1.2 % |
| heart_of_the_sea | 9500 | epic | 1 | 0.9 % |
| trident | 9700 | epic | 1 | 0.5 % |

#### `vystorm_core:vanilla/treasure`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| gold_ingot | 4000 | uncommon | 2-5 | 17.1 % |
| emerald | 5500 | uncommon | 1-4 | 12.8 % |
| diamond | 7500 | rare | 1-3 | 7.1 % |
| golden_apple | 7500 | rare | 1-2 | 7.1 % |
| book (enchanted 15-30, treasure) | 8000 | rare | 1 | 5.7 % |
| name_tag | 7000 | rare | 1 | 8.5 % |
| music_disc_mellohi | 8500 | rare | 1 | 4.3 % |
| music_disc_relic | 9000 | epic | 1 | 2.9 % |
| music_disc_otherside | 9500 | epic | 1 | 1.4 % |
| archer_pottery_sherd | 7500 | rare | 1 | 7.1 % |
| prize_pottery_sherd | 7500 | rare | 1 | 7.1 % |
| skull_pottery_sherd | 8000 | rare | 1 | 5.7 % |
| heart_pottery_sherd | 8500 | rare | 1 | 4.3 % |
| coast_armor_trim_smithing_template | 8500 | rare | 1 | 4.3 % |
| wayfinder_armor_trim_smithing_template | 9000 | epic | 1 | 2.9 % |
| vex_armor_trim_smithing_template | 9500 | epic | 1 | 1.4 % |
| enchanted_golden_apple | 9900 | legendary | 1 | 0.3 % |

#### `vystorm_core:vanilla/fishing`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/fishing_fish | 0 | common | 1 | 85.0 % |
| → vystorm_core:vanilla/fishing_junk | 8824 | rare | 1 | 10.0 % |
| → vystorm_core:vanilla/fishing_treasure | 9413 | epic | 1 | 5.0 % |

#### `vystorm_core:vanilla/fishing_fish`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| cod | 0 | common | 1 | 52.6 % |
| salmon | 4000 | uncommon | 1 | 31.6 % |
| pufferfish | 8000 | rare | 1 | 10.5 % |
| tropical_fish | 9000 | epic | 1 | 5.3 % |

#### `vystorm_core:vanilla/fishing_junk`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| lily_pad | 0 | common | 1 | 12.7 % |
| bowl | 0 | common | 1 | 12.7 % |
| string | 1000 | common | 1 | 11.4 % |
| stick | 1000 | common | 1 | 11.4 % |
| leather_boots | 1000 | common | 1 | 11.4 % |
| rotten_flesh | 1000 | common | 1 | 11.4 % |
| bone | 1000 | common | 1 | 11.4 % |
| ink_sac | 3000 | common | 1 | 8.9 % |
| tripwire_hook | 3000 | common | 1 | 8.9 % |

#### `vystorm_core:vanilla/fishing_treasure`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| name_tag | 7000 | rare | 1 | 25.0 % |
| saddle | 7000 | rare | 1 | 25.0 % |
| nautilus_shell | 7000 | rare | 1 | 25.0 % |
| book (enchanted 30-30, treasure) | 9000 | epic | 1 | 8.3 % |
| fishing_rod (enchanted 22-30, treasure) | 9000 | epic | 1 | 8.3 % |
| bow (enchanted 22-30) | 9000 | epic | 1 | 8.3 % |

#### `vystorm_core:default/dungeon_chest` (draws 3-6)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 31.9 % |
| → vystorm_core:vanilla/food | 3000 | common | 1 | 22.4 % |
| → vystorm_core:vanilla/uncommon | 3000 | common | 1 | 22.4 % |
| → vystorm_core:vanilla/tools_weapons | 6000 | uncommon | 1 | 12.8 % |
| → vystorm_core:vanilla/rare | 7500 | rare | 1 | 8.0 % |
| → vystorm_core:vanilla/epic | 9300 | epic | 1 | 2.2 % |
| → vystorm_core:vanilla/legendary | 9900 | legendary | 1 | 0.3 % |

#### `vystorm_core:default/boss_chest` (draws 4-7)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/uncommon | 0 | common | 1 | 37.0 % |
| → vystorm_core:vanilla/rare | 2000 | common | 1 | 29.6 % |
| → vystorm_core:vanilla/armor | 5000 | uncommon | 1 | 18.5 % |
| → vystorm_core:vanilla/epic | 7000 | rare | 1 | 11.1 % |
| → vystorm_core:vanilla/legendary | 9000 | epic | 1 | 3.7 % |

#### `vystorm_core:default/mineshaft_cart` (draws 2-4)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 31.2 % |
| → vystorm_core:vanilla/ores_ingots | 0 | common | 1 | 31.2 % |
| → vystorm_core:vanilla/food | 4000 | uncommon | 1 | 18.8 % |
| → vystorm_core:vanilla/uncommon | 5000 | uncommon | 1 | 15.6 % |
| → vystorm_core:vanilla/rare | 9000 | epic | 1 | 3.1 % |

#### `vystorm_core:default/treasure_vault` (draws 3-6)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/treasure | 0 | common | 1 | 40.3 % |
| → vystorm_core:vanilla/uncommon | 2000 | common | 1 | 32.3 % |
| → vystorm_core:vanilla/rare | 5000 | uncommon | 1 | 20.2 % |
| → vystorm_core:vanilla/epic | 8500 | rare | 1 | 6.1 % |
| → vystorm_core:vanilla/legendary | 9700 | epic | 1 | 1.2 % |

#### `vystorm_core:default/sky_temple` (draws 3-5)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/uncommon | 0 | common | 1 | 37.4 % |
| → vystorm_core:vanilla/magic | 2000 | common | 1 | 30.0 % |
| → vystorm_core:vanilla/end | 6000 | uncommon | 1 | 15.0 % |
| → vystorm_core:vanilla/rare | 6000 | uncommon | 1 | 15.0 % |
| → vystorm_core:vanilla/epic | 9300 | epic | 1 | 2.6 % |

#### `vystorm_core:default/sky_wreck` (draws 2-4)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 37.7 % |
| → vystorm_core:vanilla/building | 2000 | common | 1 | 30.2 % |
| → vystorm_core:vanilla/uncommon | 3000 | common | 1 | 26.4 % |
| → vystorm_core:vanilla/rare | 8500 | rare | 1 | 5.7 % |

#### `vystorm_core:default/cave_loot_room` (draws 2-5)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 28.6 % |
| → vystorm_core:vanilla/ores_ingots | 0 | common | 1 | 28.6 % |
| → vystorm_core:vanilla/uncommon | 3000 | common | 1 | 20.0 % |
| → vystorm_core:vanilla/magic | 5000 | uncommon | 1 | 14.3 % |
| → vystorm_core:vanilla/rare | 7500 | rare | 1 | 7.1 % |
| → vystorm_core:vanilla/epic (y ≤ 0) | 9500 | epic | 1 | 1.4 % |

#### `vystorm_core:default/fishing`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/fishing | 0 | common | 1 | 100.0 % |

#### `vystorm_core:default/expedition` (draws 1-3)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 44.4 % |
| → vystorm_core:vanilla/food | 2000 | common | 1 | 35.6 % |
| → vystorm_core:vanilla/uncommon | 6000 | uncommon | 1 | 17.8 % |
| → vystorm_core:vanilla/rare | 9500 | epic | 1 | 2.2 % |

#### `vystorm_core:default/mob_drop_common` (chance 0.25)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 83.3 % |
| → vystorm_core:vanilla/uncommon | 8000 | rare | 1 | 16.7 % |

#### `vystorm_core:default/mob_drop_elite` (draws 1-2)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/common | 0 | common | 1 | 49.5 % |
| → vystorm_core:vanilla/uncommon | 2000 | common | 1 | 39.6 % |
| → vystorm_core:vanilla/rare | 8000 | rare | 1 | 9.9 % |
| → vystorm_core:vanilla/epic | 9800 | legendary | 1 | 1.0 % |

#### `vystorm_core:default/mob_drop_boss` (draws 2-4)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/uncommon | 0 | common | 1 | 49.5 % |
| → vystorm_core:vanilla/rare | 3000 | common | 1 | 34.7 % |
| → vystorm_core:vanilla/epic | 7500 | rare | 1 | 12.4 % |
| → vystorm_core:vanilla/legendary | 9300 | epic | 1 | 3.5 % |

#### `vystorm_core:default/reward`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/uncommon | 0 | common | 1 | 48.8 % |
| → vystorm_core:vanilla/food | 3000 | common | 1 | 34.1 % |
| → vystorm_core:vanilla/rare | 7000 | rare | 1 | 14.6 % |
| → vystorm_core:vanilla/epic | 9500 | epic | 1 | 2.4 % |

#### `vystorm_core:default/farm_rare_find`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| bone_meal | 0 | common | 2-6 | 20.5 % |
| melon_seeds | 3000 | common | 1-3 | 14.4 % |
| pumpkin_seeds | 3000 | common | 1-3 | 14.4 % |
| beetroot_seeds | 3000 | common | 1-3 | 14.4 % |
| sweet_berries | 3500 | common | 1-4 | 13.3 % |
| glow_berries | 5000 | uncommon | 1-3 | 10.3 % |
| golden_carrot | 7000 | rare | 1 | 6.2 % |
| glistering_melon_slice | 7500 | rare | 1 | 5.1 % |
| golden_apple | 9300 | epic | 1 | 1.4 % |

#### `vystorm_core:default/shrine` (draws 1-2)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| → vystorm_core:vanilla/magic | 0 | common | 1 | 50.0 % |
| → vystorm_core:vanilla/uncommon | 3000 | common | 1 | 35.0 % |
| → vystorm_core:vanilla/rare | 7000 | rare | 1 | 15.0 % |

#### `vystorm_core:default/meteor` (draws 2-4)

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| raw_iron | 0 | common | 2-6 | 30.6 % |
| magma_block | 2000 | common | 2-6 | 24.5 % |
| obsidian | 4000 | uncommon | 1-4 | 18.3 % |
| → vystorm_core:vanilla/ores_ingots | 2000 | common | 1 | 24.5 % |
| netherite_scrap | 9500 | epic | 1 | 1.5 % |
| ancient_debris | 9800 | legendary | 1 | 0.6 % |

#### `vystorm_core:default/gathering_find`

| Entry | Weight | Tier | Amount | Per draw |
|---|---|---|---|---|
| stick | 0 | common | 1-3 | 24.4 % |
| apple | 2000 | common | 1 | 19.6 % |
| coal | 3000 | common | 1-2 | 17.1 % |
| iron_nugget | 4000 | uncommon | 1-4 | 14.7 % |
| gold_nugget | 5000 | uncommon | 1-3 | 12.2 % |
| experience_bottle | 7000 | rare | 1 | 7.3 % |
| emerald | 8500 | rare | 1 | 3.7 % |
| golden_apple | 9600 | epic | 1 | 1.0 % |
