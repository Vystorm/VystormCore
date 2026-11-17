# Shared weighted loot (0.34.0)

Capability `CoreApi.Capability.LOOT_TABLES` (`loot-tables`, since 0.34.0).

Since 0.44.0 the same tables also support categories, table references, luck, tier filters, pity, conditions and
the basic vanilla lists; see [LOOT.md](LOOT.md). Everything below still works unchanged.

`plugins/vystorm_core/loot-tables.yml` holds the central lists for Mobs and WorldForge.
Edit through Core's config editor (`/vadmin config vystorm_core`) or edit the file and restart.
The editor's reload atomically replaces valid definitions; invalid YAML/entries retain previous tables.
Existing files are never overwritten. The shipped `core:common` and `core:treasure` lists are examples;
consumers explicitly assign them instead of automatically replacing existing drops.

```yaml
tables:
  'server:weapons':
    min-rolls: 1
    max-rolls: 2
    chance: 0.75
    entries:
      - {item: 'minecraft:wooden_sword', weight: 0}
      - {item: 'minecraft:iron_sword', weight: 7500, chest-weight: 6500}
      - {item: 'vystorm:YOUR_EXISTING_TEMPLATE', weight: 10000}
```

Replace the template placeholder with a real installed template. A draw chooses one entry with replacement.
`weight` is an integer rarity rank **0..10000**, **0 = trash**, **10000 = mythic+**. Higher is rarer.
Each entry gets `10001 - weight` selection tickets. Its probability is tickets divided by the sum
of tickets for the current source. The endpoints always remain drawable. This ranking does not
change a template's intrinsic rarity, level or quality; selected custom items subsequently use
their provider's normal random stat/affix generation. Each unit is generated separately with its own identity.

`mob-weight` and `chest-weight` independently override `weight`. `amount` defaults to 1;
`min-amount` / `max-amount` optionally specify a range (1..64). Rolls default to 1, with `rolls`
or `min-rolls` / `max-rolls` (0..27). `chance` defaults to 1 and gates the whole table once;
it is separate from per-item rarity. An empty `item: ''` is an explicit empty draw.
Maximum 1024 entries per list; negative, non-finite, fractional ranks and out-of-range values are rejected.

## Public API

```java
LootTable(int minRolls, int maxRolls, double chance, List<LootTable.Entry> entries)
LootTable.Entry(String item, int minAmount, int maxAmount, int mobWeight, int chestWeight)
LootTable.Drop(String item, int amount)
LootTable.Source // MOB, CHEST
List<LootTable.Drop> LootTable.roll(LootTable.Source source, RandomGenerator random)
void LootTables.replace(Plugin owner, Map<String, LootTable> definitions)
void LootTables.release(Plugin owner)
Optional<LootTable> LootTables.find(String id)
Set<String> LootTables.ids()
List<ItemStack> LootTables.generate(String id, LootTable.Source source, RandomGenerator random)
Map<String, LootTable> LootTableConfig.parse(ConfigurationSection yaml)
void LootTableConfig.reload(JavaPlugin owner) throws Exception
```

IDs are `namespace:id`, case-insensitive. Replacement is atomic and owner-scoped; duplicate keys
belonging to another owner are rejected. Disabled providers are ignored and CoreLifecycle releases them.
Pure `LootTable.roll` is thread-safe; registry changes, generation and item/world delivery belong
on the server thread. `generate` resolves through `ItemRefs`, supporting Vanilla, VystormItems
and other registered namespaces, and grants nothing itself. Missing references fail explicitly;
callers must not substitute another roll. Provider failures are contained/logged by ItemRefs.

Mobs 0.8.2 uses `coreloot server:weapons 1 1` in `drops` (invocations, chance).
WorldForge 0.22.1 maps `dungeons.loot-tables.common/boss/core` and optional
`dungeons.loot-tables.themes.<theme>.<kind>`; plan metadata `loot.table.<kind>` wins.
Empty WorldForge mappings retain vanilla tables. Its existing wave marker prevents repeat fills,
including empty results. Already filled chests wait until the next wave. Only loaded chunks are touched.

Validation: deterministic rarity boundary/range tests, config validation, per-unit generation,
registry ownership and disable cleanup; consumer tests cover mob/practice drops and chest wave idempotence.
No live-server deployment or in-game balancing is implied by these tests.
