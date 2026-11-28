package Vystorm.vystorm_core.compat.gamerule;

import static Vystorm.vystorm_core.compat.gamerule.GameRuleCategory.*;
import static Vystorm.vystorm_core.compat.gamerule.GameRuleKind.BOOLEAN;
import static Vystorm.vystorm_core.compat.gamerule.GameRuleKind.INTEGER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Vystorm's table of the vanilla game rules of Minecraft 1.20.6 and 26.x (pure, no server needed). Each rule has one
 * stable id: the 26.x id, or {@code spawn_chunk_radius} for the rule 26.x removed. The table knows the 1.20.6 name, the
 * kind and range per version (Bukkit exposes no ranges; these come from the command trees of the vanilla data
 * generator), the category and whether the 1.20.6 rule meant the opposite ({@code disableRaids} -> {@code raids},
 * {@code disableElytraMovementCheck} -> {@code elytra_movement_check}). Rules a newer server adds are not in here and
 * are shown by their own id. @since 0.73.0
 */
public final class GameRuleCatalog {
    /**
     * One rule.
     *
     * @param id          stable id (26.x id)
     * @param legacyName  1.20.6 name, or {@code null} when 26.x added the rule
     * @param modern      whether 26.x has the rule (false only for {@code spawn_chunk_radius})
     * @param kind        kind on 26.x (the 1.20.6 kind differs only for {@code doFireTick})
     * @param legacyKind  kind on 1.20.6
     * @param min         lowest number (integer rules)
     * @param max         highest number (integer rules)
     * @param category    Vystorm category
     * @param invertedLegacy whether the 1.20.6 rule means the opposite
     */
    public record Entry(String id, String legacyName, boolean modern, GameRuleKind kind, GameRuleKind legacyKind,
                        int min, int max, GameRuleCategory category, boolean invertedLegacy) {
        /** Whether the 1.20.6 name differs from the 26.x id other than by spelling ({@code doInsomnia}). */
        public boolean renamed() { return legacyName != null && modern && !snake(legacyName).equals(id); }
    }

    private static final Map<String, Entry> BY_ID;
    private static final Map<String, Entry> BY_LEGACY;

    static {
        List<Entry> all = new ArrayList<>();
        // stable id, 1.20.6 name, category (bool unless stated)
        b(all, "advance_time", "doDaylightCycle", WORLD);
        b(all, "advance_weather", "doWeatherCycle", WORLD);
        b(all, "allow_entering_nether_using_portals", null, PLAYER);
        b(all, "block_drops", "doTileDrops", DROPS);
        b(all, "block_explosion_drop_decay", "blockExplosionDropDecay", DROPS);
        b(all, "command_block_output", "commandBlockOutput", CHAT);
        b(all, "command_blocks_work", null, OTHER);
        b(all, "drowning_damage", "drowningDamage", PLAYER);
        all.add(new Entry("elytra_movement_check", "disableElytraMovementCheck", true, BOOLEAN, BOOLEAN, 0, 1, PLAYER, true));
        b(all, "ender_pearls_vanish_on_death", "enderPearlsVanishOnDeath", PLAYER);
        b(all, "entity_drops", "doEntityDrops", DROPS);
        b(all, "fall_damage", "fallDamage", PLAYER);
        b(all, "fire_damage", "fireDamage", PLAYER);
        all.add(new Entry("fire_spread_radius_around_player", "doFireTick", true, INTEGER, BOOLEAN, -1, Integer.MAX_VALUE, WORLD, false));
        b(all, "forgive_dead_players", "forgiveDeadPlayers", MOBS);
        b(all, "freeze_damage", "freezeDamage", PLAYER);
        b(all, "global_sound_events", "globalSoundEvents", WORLD);
        b(all, "immediate_respawn", "doImmediateRespawn", PLAYER);
        b(all, "keep_inventory", "keepInventory", PLAYER);
        b(all, "lava_source_conversion", "lavaSourceConversion", WORLD);
        b(all, "limited_crafting", "doLimitedCrafting", PLAYER);
        b(all, "locator_bar", null, PLAYER);
        b(all, "log_admin_commands", "logAdminCommands", CHAT);
        i(all, "max_block_modifications", "commandModificationBlockLimit", 1, Integer.MAX_VALUE, OTHER);
        i(all, "max_command_forks", "maxCommandForkCount", 0, Integer.MAX_VALUE, OTHER);
        i(all, "max_command_sequence_length", "maxCommandChainLength", 0, Integer.MAX_VALUE, OTHER);
        i(all, "max_entity_cramming", "maxEntityCramming", 0, Integer.MAX_VALUE, MOBS);
        i(all, "max_minecart_speed", null, 1, 1000, WORLD);
        i(all, "max_snow_accumulation_height", "snowAccumulationHeight", 0, 8, WORLD);
        b(all, "mob_drops", "doMobLoot", DROPS);
        b(all, "mob_explosion_drop_decay", "mobExplosionDropDecay", DROPS);
        b(all, "mob_griefing", "mobGriefing", MOBS);
        b(all, "natural_health_regeneration", "naturalRegeneration", PLAYER);
        b(all, "player_movement_check", null, PLAYER);
        i(all, "players_nether_portal_creative_delay", "playersNetherPortalCreativeDelay", 0, Integer.MAX_VALUE, PLAYER);
        i(all, "players_nether_portal_default_delay", "playersNetherPortalDefaultDelay", 0, Integer.MAX_VALUE, PLAYER);
        i(all, "players_sleeping_percentage", "playersSleepingPercentage", 0, Integer.MAX_VALUE, PLAYER);
        b(all, "projectiles_can_break_blocks", "projectilesCanBreakBlocks", WORLD);
        b(all, "pvp", null, PLAYER);
        all.add(new Entry("raids", "disableRaids", true, BOOLEAN, BOOLEAN, 0, 1, MOBS, true));
        i(all, "random_tick_speed", "randomTickSpeed", 0, Integer.MAX_VALUE, WORLD);
        b(all, "reduced_debug_info", "reducedDebugInfo", PLAYER);
        i(all, "respawn_radius", "spawnRadius", 0, Integer.MAX_VALUE, PLAYER);
        b(all, "send_command_feedback", "sendCommandFeedback", CHAT);
        b(all, "show_advancement_messages", "announceAdvancements", CHAT);
        b(all, "show_death_messages", "showDeathMessages", CHAT);
        all.add(new Entry("spawn_chunk_radius", "spawnChunkRadius", false, INTEGER, INTEGER, 0, 32, WORLD, false));
        b(all, "spawn_mobs", "doMobSpawning", SPAWNING);
        b(all, "spawn_monsters", null, SPAWNING);
        b(all, "spawn_patrols", "doPatrolSpawning", SPAWNING);
        b(all, "spawn_phantoms", "doInsomnia", SPAWNING);
        b(all, "spawn_wandering_traders", "doTraderSpawning", SPAWNING);
        b(all, "spawn_wardens", "doWardenSpawning", SPAWNING);
        b(all, "spawner_blocks_work", null, SPAWNING);
        b(all, "spectators_generate_chunks", "spectatorsGenerateChunks", WORLD);
        b(all, "spread_vines", "doVinesSpread", WORLD);
        b(all, "tnt_explodes", null, WORLD);
        b(all, "tnt_explosion_drop_decay", "tntExplosionDropDecay", DROPS);
        b(all, "universal_anger", "universalAnger", MOBS);
        b(all, "water_source_conversion", "waterSourceConversion", WORLD);
        Map<String, Entry> byId = new LinkedHashMap<>(), byLegacy = new LinkedHashMap<>();
        for (Entry e : all) {
            if (byId.put(e.id(), e) != null) throw new IllegalStateException("duplicate rule " + e.id());
            if (e.legacyName() != null && byLegacy.put(e.legacyName().toLowerCase(Locale.ROOT), e) != null)
                throw new IllegalStateException("duplicate legacy rule " + e.legacyName());
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_LEGACY = Collections.unmodifiableMap(byLegacy);
    }

    private GameRuleCatalog() {}

    private static void b(List<Entry> all, String id, String legacy, GameRuleCategory category) {
        all.add(new Entry(id, legacy, true, BOOLEAN, BOOLEAN, 0, 1, category, false));
    }

    private static void i(List<Entry> all, String id, String legacy, int min, int max, GameRuleCategory category) {
        all.add(new Entry(id, legacy, true, INTEGER, INTEGER, min, max, category, false));
    }

    /** Every known rule, sorted by stable id. */
    public static List<Entry> all() { return List.copyOf(BY_ID.values()); }

    /** The rule with this stable id. */
    public static Optional<Entry> byId(String id) { return Optional.ofNullable(id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT))); }

    /**
     * The rule a server id belongs to: a 26.x id, a 1.20.6 name (any case) or a stable id; {@code minecraft:} is
     * ignored. Empty for unknown ids.
     */
    public static Optional<Entry> resolve(String anyId) {
        if (anyId == null) return Optional.empty();
        String s = anyId.trim();
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        Entry legacy = BY_LEGACY.get(s.toLowerCase(Locale.ROOT));
        if (legacy != null) return Optional.of(legacy);
        return byId(s);
    }

    /** Stable id for a server id: the catalog's id, else the id in lower snake case. */
    public static String stableId(String anyId) {
        return resolve(anyId).map(Entry::id).orElseGet(() -> snake(anyId == null ? "" : anyId.replace("minecraft:", "")));
    }

    /** {@code doDaylightCycle} -> {@code do_daylight_cycle}; snake-case input stays as it is. */
    public static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!out.isEmpty()) out.append('_');
                out.append(Character.toLowerCase(c));
            } else out.append(c);
        }
        return out.toString();
    }
}
