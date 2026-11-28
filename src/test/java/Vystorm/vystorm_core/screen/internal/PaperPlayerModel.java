package Vystorm.vystorm_core.screen.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * A stand-in player that keeps the invisibility state the way Paper/Purpur 26.3 does (read from the server code):
 * <ul>
 * <li>{@code CraftEntity.setInvisible(v)} sets {@code persistentInvisibility = v} and the shared flag 5 to {@code v};</li>
 * <li>{@code isInvisible()} reads the shared flag;</li>
 * <li>the game's own update ({@code LivingEntity.updateInvisibilityStatus}, after an effect or game mode change; for
 * spectators {@code ServerPlayer.updateInvisibilityStatus} sets it true) goes through {@code Entity.setInvisible},
 * which does nothing while {@code persistentInvisibility} is set ({@link #gameUpdate});</li>
 * <li>the player file keeps {@code Bukkit.invisible} when {@code persistentInvisibility} is set, and the persistent data
 * container; loading sets both back ({@link #save}, {@link #load}).</li>
 * </ul>
 * Also player weather (memory only, like the server) and the held slot.
 */
final class PaperPlayerModel {
    final UUID id;
    final String name;
    boolean persistent, shared, spectator, effect;
    WeatherType weather;
    int heldSlot;
    final Map<String, String> data = new HashMap<>();
    final Player player;
    RuntimeException weatherFailure;

    /** What the player file holds. */
    record Saved(UUID id, String name, boolean bukkitInvisible, Map<String, String> data, int heldSlot, boolean spectator, boolean effect) {}

    PaperPlayerModel(World world) { this(world, UUID.randomUUID(), "Tester"); }

    PaperPlayerModel(World world, UUID id, String name) {
        this.id = id;
        this.name = name;
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        Location feet = new Location(world, 10, 64, 10, 0, 0);
        when(p.getLocation()).thenReturn(feet);
        when(p.getEyeLocation()).thenReturn(new Location(world, 10, 65.62, 10, 0, 0));
        when(p.isOnGround()).thenReturn(true);
        when(p.isOnline()).thenReturn(true);
        when(p.isInvisible()).thenAnswer(i -> shared);
        doAnswer(i -> { boolean v = i.getArgument(0); persistent = v; shared = v; return null; }).when(p).setInvisible(anyBoolean());
        when(p.getGameMode()).thenAnswer(i -> spectator ? GameMode.SPECTATOR : GameMode.SURVIVAL);
        when(p.getPlayerWeather()).thenAnswer(i -> weather);
        doAnswer(i -> { if (weatherFailure != null) throw weatherFailure; weather = i.getArgument(0); return null; }).when(p).setPlayerWeather(any());
        doAnswer(i -> { weather = null; return null; }).when(p).resetPlayerWeather();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(i -> { data.put(((NamespacedKey) i.getArgument(0)).toString(), i.getArgument(2)); return null; })
                .when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), any());
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(i -> data.get(((NamespacedKey) i.getArgument(0)).toString()));
        doAnswer(i -> { data.remove(((NamespacedKey) i.getArgument(0)).toString()); return null; }).when(pdc).remove(any(NamespacedKey.class));
        when(p.getPersistentDataContainer()).thenReturn(pdc);
        this.player = p;
    }

    /** The game's own invisibility update (Entity.setInvisible, ignored while persistentInvisibility is set). */
    void gameUpdate() {
        if (!persistent) shared = spectator || effect;
    }

    void drinkInvisibility() { effect = true; gameUpdate(); }
    void effectEnds() { effect = false; gameUpdate(); }
    void gameMode(boolean toSpectator) { spectator = toSpectator; gameUpdate(); }

    /** A main hand holding a sword in slot 0, slots 1-8 empty; the held slot is tracked. */
    void sword() {
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        org.bukkit.inventory.ItemStack sword = mock(org.bukkit.inventory.ItemStack.class);
        when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(inventory.getHeldItemSlot()).thenAnswer(i -> heldSlot);
        doAnswer(i -> { heldSlot = i.getArgument(0); return null; }).when(inventory).setHeldItemSlot(anyInt());
        when(inventory.getItem(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == 0 ? sword : null);
        when(player.getInventory()).thenReturn(inventory);
    }

    /** The player file as the server writes it now (quit, autosave). */
    Saved save() { return new Saved(id, name, persistent, new HashMap<>(data), heldSlot, spectator, effect); }

    /** A player loaded from the file at the next join. */
    static PaperPlayerModel load(World world, Saved saved) {
        PaperPlayerModel model = new PaperPlayerModel(world, saved.id(), saved.name());
        model.data.putAll(saved.data());
        model.heldSlot = saved.heldSlot();
        model.spectator = saved.spectator();
        model.effect = saved.effect();
        if (saved.bukkitInvisible()) { model.persistent = true; model.shared = true; }
        else model.gameUpdate();
        return model;
    }

    /** True when other players (and the player's own third-person view) see nothing of the player. */
    boolean invisible() { return shared; }

    // ---- a minimal server around it ------------------------------------------------------------------------------

    static World world() {
        World world = mock(World.class);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        return world;
    }

    static Plugin core() {
        Plugin core = mock(Plugin.class);
        when(core.getName()).thenReturn("vystorm_core");
        when(core.getLogger()).thenReturn(Logger.getLogger("test"));
        return core;
    }

    /** Screen entities as mocks; {@code failRemove} makes every removal throw. */
    static final class Entities implements ScreenEntities {
        boolean failRemove;
        private <T extends Entity> T entity(Class<T> type) {
            T e = mock(type);
            UUID id = UUID.randomUUID();
            when(e.getUniqueId()).thenReturn(id);
            when(e.isValid()).thenReturn(true);
            return e;
        }
        @Override public Entity mount(Player viewer, Location at) { return entity(Entity.class); }
        @Override public TextDisplay text(Player viewer, Location at, Consumer<TextDisplay> setup) { TextDisplay d = entity(TextDisplay.class); setup.accept(d); return d; }
        @Override public Interaction interaction(Player viewer, Location at, float w, float h) { return entity(Interaction.class); }
        @Override public org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup) { return entity(org.bukkit.entity.ItemDisplay.class); }
        @Override public void remove(Entity entity) { if (failRemove) throw new IllegalStateException("entity gone"); }
    }

    static Vystorm.vystorm_core.screen.ScreenSpec spec(String id) {
        Vystorm.vystorm_core.screen.ScreenLayout layout = new Vystorm.vystorm_core.screen.ScreenLayout(0.9, 0.55, List.of(
                Vystorm.vystorm_core.screen.ScreenWidget.button("ok", 0.35, 0.235, 0.2, 0.08, "OK")));
        return Vystorm.vystorm_core.screen.ScreenSpec.builder(id, layout).onInput((s, i) -> { }).fallback(p -> { }).build();
    }
}
