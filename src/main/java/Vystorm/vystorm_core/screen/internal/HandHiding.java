package Vystorm.vystorm_core.screen.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 0.71.1: what Core changes on a player while a PINNED screen is open, kept once per PLAYER (not per screen) and undone
 * exactly once, when the last screen that needed it is gone. Server thread only.
 * <p>
 * The client draws the empty arm over every PINNED screen unless the player is invisible, so Core makes the player
 * invisible meanwhile, sets their weather to clear (rain falls closer than the backdrop) and holds an empty hotbar slot.
 * Bukkit's {@code setInvisible} is not a passing state: it also sets the entity's persistent invisibility (CraftEntity
 * sets {@code persistentInvisibility}, which the game's own effect update then never clears and which is saved with the
 * player as {@code Bukkit.invisible}). Up to 0.71.0 every screen remembered {@code isInvisible()} before it hid the hand
 * and wrote it back with {@code setInvisible}: a spectator (whom the game shows invisible) or a player under an
 * invisibility potion got {@code setInvisible(true)} written back and stayed invisible for good, for everyone and in
 * their own third-person view, after every rejoin.
 * <p>
 * Rules now:
 * <ul>
 * <li>Core sets the invisible flag only when the player is visible, and then clears it again; a player who is invisible
 * already (effect, spectator, another plugin) draws no arm anyway and is never touched.</li>
 * <li>While Core holds the flag, a marker in the player's data ({@code vystorm_core:screen-hidden}) says so. It is saved
 * together with the flag, so after a crash or a kill the next join finds both and makes the player visible again.
 * Without a marker Core never clears anyone's invisibility on its own (admins and other plugins may have set it).</li>
 * <li>Players who are invisible without an effect and without a marker (left over from 0.71.0 and older) are logged once
 * per start; admins among them are told how to fix it; {@code screens.repair-invisible-on-join} (default off) makes them
 * visible at join; {@code /vadmin visible <player>} always does.</li>
 * <li>Known limit: an invisibility another plugin sets with {@code setInvisible(true)} WHILE Core holds the flag cannot be
 * told apart from Core's own and is cleared when the screen closes (or at the next join after a crash).</li>
 * </ul>
 */
public final class HandHiding {
    /** Key of the marker in the player's persistent data. */
    static final String MARKER = "screen-hidden";

    /** What Core changed on one player, and the screens that still need it. */
    private static final class State {
        final Player player;
        final Set<Object> holders = new HashSet<>();
        boolean madeInvisible, weatherChanged;
        WeatherType weatherBefore;
        int slotBefore = -1;
        State(Player player) { this.player = player; }
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Set<UUID> reported = new HashSet<>();
    private final NamespacedKey key;
    private final Consumer<String> log;
    /** True for players whom the game itself shows invisible (spectators, an invisibility effect). */
    private volatile Predicate<Player> naturallyInvisible = p -> p.getGameMode() == GameMode.SPECTATOR;
    private volatile BooleanSupplier repairOnJoin = () -> false;
    private volatile Predicate<Player> admin = p -> false;
    /** {@code (player, lang key)}: lines to the player; Core fills {@code {player}} with the player's name. */
    private volatile BiConsumer<Player, String> notice = (p, k) -> { };
    /**
     * Makes the game work out the invisible flag again after Core cleared its own (the live server takes the player's
     * invisibility effect off and puts the same effect back on, which sets the game's "effects changed" mark). Without
     * it a player who got an invisibility potion while a screen was open would be visible until their effects change.
     */
    private volatile Consumer<Player> effectRefresh = p -> { };

    void effectRefresh(Consumer<Player> refresh) { if (refresh != null) effectRefresh = refresh; }

    private void clearInvisible(Player player) {
        try { player.setInvisible(false); } catch (RuntimeException | LinkageError ignored) { }
        try { effectRefresh.accept(player); } catch (RuntimeException | LinkageError ignored) { }
    }

    HandHiding(Consumer<String> log) {
        NamespacedKey k;
        // a fixed namespace like the screen entities' tag: the marker must be found again whatever the plugin is called
        try { k = NamespacedKey.fromString("vystorm_core:" + MARKER); } catch (RuntimeException | LinkageError noKey) { k = null; }
        this.key = k;
        this.log = log == null ? line -> { } : log;
    }

    void rules(Predicate<Player> naturallyInvisible, BooleanSupplier repairOnJoin, Predicate<Player> admin, BiConsumer<Player, String> notice) {
        if (naturallyInvisible != null) this.naturallyInvisible = naturallyInvisible;
        if (repairOnJoin != null) this.repairOnJoin = repairOnJoin;
        if (admin != null) this.admin = admin;
        if (notice != null) this.notice = notice;
    }

    /** True while Core keeps something changed on this player. */
    boolean holds(Player player) { return states.containsKey(player.getUniqueId()); }

    /**
     * {@code holder} (a screen) needs the hand hidden. The first holder records the state before and changes it; later
     * holders only join, so they never read Core's own changes as "before".
     */
    void hide(Player player, Object holder) {
        UUID id = player.getUniqueId();
        State state = states.get(id);
        if (state != null) { state.holders.add(holder); return; }
        state = new State(player);
        state.holders.add(holder);
        states.put(id, state);
        // each step on its own: whatever was changed is undone, also when a later step fails
        try {
            var inventory = player.getInventory();
            if (inventory != null) {
                int held = inventory.getHeldItemSlot();
                if (!emptyStack(inventory.getItem(held))) {
                    for (int slot = 0; slot < 9; slot++) {
                        if (!emptyStack(inventory.getItem(slot))) continue;
                        state.slotBefore = held;
                        inventory.setHeldItemSlot(slot);
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError slotFailed) { }
        try {
            if (!player.isInvisible()) {
                state.madeInvisible = true;
                writeMarker(player, state);   // before the flag: both reach the player file together
                player.setInvisible(true);
            } else if (state.slotBefore >= 0) {
                writeMarker(player, state);
            }
        } catch (RuntimeException | LinkageError invisibleFailed) { }
        try {
            WeatherType before = player.getPlayerWeather();
            state.weatherBefore = before;
            state.weatherChanged = true;
            player.setPlayerWeather(WeatherType.CLEAR);
        } catch (RuntimeException | LinkageError weatherFailed) { }
    }

    /** {@code holder} no longer needs it; the last one gives the player back what they had. */
    void show(Player player, Object holder) {
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        state.holders.remove(holder);
        if (state.holders.isEmpty()) restore(state);
    }

    /** Gives the player back everything at once, whoever still holds it (quit, plugin disable). */
    void release(Player player) {
        State state = states.get(player.getUniqueId());
        if (state != null) restore(state);
    }

    /** Every player Core still changes (plugin disable, server stop: before the players are saved). */
    void releaseAll() {
        for (State state : List.copyOf(states.values())) restore(state);
    }

    private void restore(State state) {
        Player player = state.player;
        states.remove(player.getUniqueId());
        if (state.madeInvisible) {
            clearInvisible(player);   // also when an effect or another plugin made them invisible meanwhile: see the class note
        }
        if (state.weatherChanged) {
            try {
                if (state.weatherBefore == null) player.resetPlayerWeather();
                else player.setPlayerWeather(state.weatherBefore);
            } catch (RuntimeException | LinkageError ignored) { }
        }
        if (state.slotBefore >= 0) {
            try { if (player.getInventory() != null) player.getInventory().setHeldItemSlot(state.slotBefore); }
            catch (RuntimeException | LinkageError ignored) { }
        }
        removeMarker(player);
    }

    /**
     * A player joined (or was online when Core started). A marker means Core had hidden them when the server stopped:
     * they are made visible. Without a marker an invisible player without an effect is only reported, unless
     * {@code screens.repair-invisible-on-join} is on. Returns what happened.
     */
    JoinCheck join(Player player) {
        if (holds(player)) return JoinCheck.NONE;
        String marker = readMarker(player);
        if (marker != null) {
            boolean madeInvisible = marker.startsWith("1");
            int slot = slotOf(marker);
            if (madeInvisible) { try { if (player.isInvisible()) clearInvisible(player); } catch (RuntimeException | LinkageError ignored) { } }
            try { if (slot >= 0 && player.getInventory() != null) player.getInventory().setHeldItemSlot(slot); } catch (RuntimeException | LinkageError ignored) { }
            removeMarker(player);
            if (madeInvisible) log.accept(player.getName() + " was still hidden by a Vystorm screen when the server stopped; made visible again.");
            if (madeInvisible) return JoinCheck.MARKER_RESTORED;   // a "0;slot" marker only held the slot: the stuck check still runs
        }
        boolean stuck;
        try { stuck = player.isInvisible() && !naturallyInvisible.test(player); } catch (RuntimeException | LinkageError unknown) { stuck = false; }
        if (!stuck) return JoinCheck.NONE;
        boolean repair;
        try { repair = repairOnJoin.getAsBoolean(); } catch (RuntimeException ignored) { repair = false; }
        if (repair) {
            clearInvisible(player);
            log.accept(player.getName() + " was invisible without an invisibility effect (screens.repair-invisible-on-join): made visible.");
            tell(player, "screen.visible.player");
            return JoinCheck.REPAIRED;
        }
        if (reported.add(player.getUniqueId()))
            log.accept(player.getName() + " is invisible without an invisibility effect. Vystorm Core 0.71.0 and older could leave players so"
                    + " (a screen opened in spectator mode or under an invisibility potion). /vadmin visible " + player.getName() + " makes them visible.");
        boolean isAdmin;
        try { isAdmin = admin.test(player); } catch (RuntimeException ignored) { isAdmin = false; }
        if (isAdmin) tell(player, "screen.visible.stuck-admin");
        return JoinCheck.REPORTED;
    }

    /** What {@link #join} did. */
    public enum JoinCheck { NONE, MARKER_RESTORED, REPAIRED, REPORTED }

    /**
     * {@code /vadmin visible}: clears the invisible flag (an invisibility effect is put back on so it still counts) and forgets any marker. Call after
     * the player's screen is closed. True when the player was invisible.
     */
    boolean makeVisible(Player player) {
        release(player);
        boolean was;
        try { was = player.isInvisible(); } catch (RuntimeException | LinkageError unknown) { was = true; }
        clearInvisible(player);
        removeMarker(player);
        reported.remove(player.getUniqueId());
        tell(player, "screen.visible.player");
        return was;
    }

    private void tell(Player player, String langKey) {
        try { notice.accept(player, langKey); } catch (RuntimeException | LinkageError noChat) { }
    }

    // ---- marker: "<1|0 invisible set by Core>;<held slot before or -1>" ------------------------------------------

    private void writeMarker(Player player, State state) {
        PersistentDataContainer data = data(player);
        if (data == null) return;
        try { data.set(key, PersistentDataType.STRING, (state.madeInvisible ? "1" : "0") + ";" + state.slotBefore); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private String readMarker(Player player) {
        PersistentDataContainer data = data(player);
        if (data == null) return null;
        try { return data.get(key, PersistentDataType.STRING); } catch (RuntimeException | LinkageError unreadable) { return null; }
    }

    private void removeMarker(Player player) {
        PersistentDataContainer data = data(player);
        if (data == null) return;
        try { data.remove(key); } catch (RuntimeException | LinkageError ignored) { }
    }

    private PersistentDataContainer data(Player player) {
        if (key == null) return null;
        try { return player.getPersistentDataContainer(); } catch (RuntimeException | LinkageError none) { return null; }
    }

    static int slotOf(String marker) {
        int semi = marker.indexOf(';');
        if (semi < 0) return -1;
        try {
            int slot = Integer.parseInt(marker.substring(semi + 1).trim());
            return slot >= 0 && slot <= 8 ? slot : -1;
        } catch (NumberFormatException broken) { return -1; }
    }

    /** No item: null or air (an item stack is never cave or void air). */
    static boolean emptyStack(org.bukkit.inventory.ItemStack item) {
        return item == null || item.getType() == org.bukkit.Material.AIR;
    }
}
