package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Session store and logic of Vystorm Screens (since 0.57.0). Bukkit events reach it through {@link ScreenListener};
 * entity spawning goes through {@link ScreenEntities} so the logic is testable without a server. Server thread only.
 */
public final class ScreenRuntime {
    /** Distance of PINNED panels and the pointer (blocks); panel units are blocks at this distance. */
    public static final double PINNED_DISTANCE = 1.0;
    /** Distance of FLOATING screens (blocks). */
    public static final double FLOATING_DISTANCE = 2.5;
    static final long COMBAT_MILLIS = 5_000;
    static final String PACK_MODULE = ScreenShader.MODULE;
    /** Text display line height in blocks at scale 1 (10 px of 1/40 block). */
    static final double LINE = 0.25;
    /** Tallest text line of a widget (panel units). */
    public static final double TEXT_MAX = 0.08;
    /** The panel box is this much wider and higher than its layout (panel units). */
    public static final double PANEL_BORDER = 0.06;
    /** One text pixel in blocks at scale 1, and the solid glyph's size (8 px). */
    static final double PIXEL = 1.0 / 40, GLYPH = 8 * PIXEL;
    /** PINNED backdrop: wide enough for ultrawide screens (about ±79° at distance 1), slightly behind the panel. */
    static final double BACKDROP_WIDTH = 14, BACKDROP_HEIGHT = 8, BACKDROP_DEPTH = 0.15;

    private static ScreenRuntime instance;

    /** Width of a line in the default Minecraft font, in text pixels (estimate: 6 px per glyph, narrow glyphs less). */
    public static int textWidth(String text) {
        int px = 2; // background padding
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ("il!.,:;'|`".indexOf(ch) >= 0) px += 2;
            else if (ch == 'I' || ch == 't' || ch == ' ') px += 4;
            else if (ch == 'f' || ch == 'k' || ch == '(' || ch == ')') px += 5;
            else px += 6;
        }
        return px;
    }

    private final Plugin core;
    private final ScreenEntities entities;
    private final LongSupplier clock;
    private final Predicate<Player> packLoaded;
    private final Predicate<Player> classic;
    private final NamespacedKey tag;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastDamage = new HashMap<>();
    private final java.util.function.Consumer<Runnable> later;
    /** Client-only frames for PINNED map widgets (since 0.59.0); none until {@link #useMaps}. */
    private ScreenMapFrames maps = ScreenMapFrames.NONE;

    /** Enables map widgets in PINNED screens with these frames (the live server passes {@link ScreenMapFrames.Packets}). */
    public void useMaps(ScreenMapFrames frames) { this.maps = frames == null ? ScreenMapFrames.NONE : frames; }

    public ScreenRuntime(Plugin core, ScreenEntities entities, LongSupplier clock, Predicate<Player> packLoaded, Predicate<Player> classic) {
        this(core, entities, clock, packLoaded, classic, Runnable::run);
    }

    /** {@code later} runs the visual build a few ticks after mounting, when the eye position is final. */
    public ScreenRuntime(Plugin core, ScreenEntities entities, LongSupplier clock, Predicate<Player> packLoaded, Predicate<Player> classic,
                         java.util.function.Consumer<Runnable> later) {
        this.later = later;
        this.core = core;
        this.entities = entities;
        this.clock = clock;
        this.packLoaded = packLoaded;
        this.classic = classic;
        this.tag = ScreenEntities.tag(core);
    }

    public static ScreenRuntime get() {
        if (instance == null) throw new IllegalStateException("Vystorm Screens are not running (Core disabled?)");
        return instance;
    }

    public static void install(ScreenRuntime runtime) { instance = runtime; }

    public static void uninstall() {
        if (instance != null) instance.closeAll(ScreenInput.Reason.PLUGIN);
        instance = null;
    }

    // ---- opening ---------------------------------------------------------------------------------------------------

    // ---- history (0.61.0): Back returns to the screen the player came from ---------------------------------------

    /** Widget id of the Back button; Core adds it to a screen that has a parent and handles its clicks. */
    public static final String BACK_ID = "vystorm:back";
    /** A screen closed by code counts as the parent of the next screen when that one opens within this time. */
    static final long LINK_MILLIS = 400;
    static final int MAX_HISTORY = 16;
    /** Height Core adds under a screen for its Back button. */
    static final double BACK_STRIP = 0.07;

    /**
     * Where a screen can be opened again. {@code key} names the place (the same key is the same place, so returning to
     * it by another route shortens the history instead of growing it); {@code button} false keeps Core's Back button
     * off this screen (it has its own back controls) while it can still be a parent.
     */
    public record Origin(String key, java.util.function.Consumer<Player> reopen, boolean button) {
        public Origin {
            java.util.Objects.requireNonNull(key, "key");
            java.util.Objects.requireNonNull(reopen, "reopen");
        }
    }

    private record Pending(Origin origin, long at) {}

    private final Map<UUID, java.util.ArrayDeque<Origin>> history = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> backing = new java.util.HashSet<>();
    /** Time of each player's last counted left click, across screens. */
    private final Map<UUID, Long> lastLeftClick = new HashMap<>();

    // ---- click guard (0.65.0): a click that closes or replaces a screen must not reach the world ------------------
    //
    // A click is handled when the button goes DOWN. When that click closes the screen, its click box is gone at once
    // while the player still holds the button: the client then aims at the block (or mob) behind and breaks, mines,
    // uses or hits it. So for a short time after such a click the player's actions on the world are cancelled.

    /** The guard lasts this long after the click that closed or replaced the screen (10 ticks: a held click plus the way to the client and back). */
    static final long GUARD_MILLIS = 500;
    /**
     * Every action the guard stops shows that the button is still down and keeps the guard up this much longer. A held
     * button repeats slowly: creative breaks again every 6 ticks (300 ms), a held right button uses every 5 ticks; the
     * time must be clearly longer than that (measured in the client: with 300 ms every third long hold got through) …
     */
    static final long GUARD_EXTEND_MILLIS = 450;
    /** … but never longer than this after the click, so the guard cannot block normal play. */
    static final long GUARD_MAX_MILLIS = 1_500;
    /** When the client reports that the button was let go (mining aborted), the guard ends this soon. */
    static final long GUARD_RELEASE_MILLIS = 60;
    /** A screen that closes within this time after a click on it was closed by that click (handlers that close one tick later). */
    static final long CLICK_CLOSES_MILLIS = 250;

    private record Guard(long start, long until) {}

    private final Map<UUID, Guard> guards = new HashMap<>();
    /** Time of each player's last counted screen click, either button; players inside a click handler right now. */
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Set<UUID> clicking = new HashSet<>();

    /** True while the click guard of this player is up. */
    boolean guarded(UUID id) {
        Guard guard = guards.get(id);
        if (guard == null) return false;
        if (clock.getAsLong() <= guard.until()) return true;
        guards.remove(id);
        return false;
    }

    /**
     * Whether an action of this player on the world (break, start mining, use, hit) must be cancelled: while a screen is
     * open (all clicks belong to the screen) and while the click guard is up. {@code buttonDown}: the action shows a
     * pressed button (not a mere arm swing), which keeps the guard up a little longer.
     */
    public boolean stopsWorldAction(Player player, boolean buttonDown) {
        UUID id = player.getUniqueId();
        if (guarded(id)) {
            if (buttonDown) {
                Guard guard = guards.get(id);
                long until = Math.min(guard.start() + GUARD_MAX_MILLIS, clock.getAsLong() + GUARD_EXTEND_MILLIS);
                if (until > guard.until()) guards.put(id, new Guard(guard.start(), until));
            }
            return true;
        }
        return sessions.containsKey(id);
    }

    /** The client let go of the button (it aborted mining): the guard has done its work. */
    public void onButtonReleased(Player player) {
        UUID id = player.getUniqueId();
        Guard guard = guards.get(id);
        if (guard == null) return;
        long soon = clock.getAsLong() + GUARD_RELEASE_MILLIS;
        if (soon < guard.until()) guards.put(id, new Guard(guard.start(), soon));
    }

    /** 0.62.0: screens switched off by the server ({@code screens.enabled}, {@code screens.off}); never blocks by default. */
    private volatile java.util.function.BiPredicate<String, java.util.List<String>> blocked = (owner, keys) -> false;
    /** Keys of the screens asked for since the start (newest last), with their plugin: what {@code screens.off} can name. */
    private final java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
    static final int MAX_SEEN = 300;
    /** After a switched-off screen its fallback is often a dialog or panel that would open as a screen again: for this long the player's next opens fall back too, down to the native menu. */
    static final long OFF_CHAIN_MILLIS = 250;
    private final Map<UUID, Long> offUntil = new HashMap<>();
    public static final String REFUSED_OFF = "screen.refused.disabled";

    /** Sets the rule that sends a screen to its fallback: {@code (plugin name, keys of the screen) -> switched off}. */
    public void blockWhen(java.util.function.BiPredicate<String, java.util.List<String>> rule) { blocked = rule == null ? (o, k) -> false : rule; }

    /** Screen keys seen since the start, mapped to the owning plugin's name. */
    public Map<String, String> seen() { synchronized (seen) { return new java.util.LinkedHashMap<>(seen); } }

    /** The names a screen answers to in {@code screens.off}: its id, the id's group ({@code panel}, {@code inventory}), the id without the group and its history key. */
    static java.util.List<String> keysOf(ScreenSpec spec, Origin origin) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (spec.id() != null) {
            keys.add(spec.id());
            int colon = spec.id().indexOf(':');
            if (colon > 0 && !spec.id().startsWith("vystorm_core:")) {
                keys.add(spec.id().substring(0, colon));
                // the name without its group too: "vystorm_core:menu" also switches off the panel of that name, "mobs-spawn" works alone
                String rest = spec.id().substring(colon + 1);
                if (!rest.isEmpty() && !keys.contains(rest)) keys.add(rest);
                // and the bare name of a plugin's panel: "panel:vystormmobs:mobs-spawn" answers to "mobs-spawn"
                String name = rest.substring(rest.lastIndexOf(':') + 1);
                if (!name.isEmpty() && !keys.contains(name)) keys.add(name);
            }
        }
        if (origin != null && origin.key() != null && !keys.contains(origin.key())) keys.add(origin.key());
        return keys;
    }

    /** True when {@code entries} (config list) names the plugin or one of the keys; case does not matter, an entry also matches every key that continues it after a colon ({@code panel} matches {@code panel:shop}). */
    public static boolean listed(java.util.List<String> entries, String owner, java.util.List<String> keys) {
        for (String raw : entries) {
            if (raw == null) continue;
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            if (entry.equalsIgnoreCase(owner)) return true;
            for (String key : keys) {
                if (key.equalsIgnoreCase(entry)) return true;
                if (key.length() > entry.length() && key.regionMatches(true, 0, entry, 0, entry.length())
                        && key.charAt(entry.length()) == ':') return true;
            }
        }
        return false;
    }

    /** True when Back would lead somewhere for this player. */
    public boolean hasBack(Player player) {
        java.util.ArrayDeque<Origin> stack = history.get(player.getUniqueId());
        return stack != null && !stack.isEmpty();
    }

    /** Closes the open screen and opens the one the player came from; false when there is none. */
    public boolean back(Player player) {
        UUID id = player.getUniqueId();
        java.util.ArrayDeque<Origin> stack = history.get(id);
        if (stack == null || stack.isEmpty()) return false;
        Origin target = stack.pop();
        backing.add(id);
        try {
            close(player, ScreenInput.Reason.PLUGIN);
            pending.remove(id);
            target.reopen().accept(player);
        } catch (RuntimeException failure) {
            core.getLogger().warning("[Screens] Back to " + target.key() + " failed: " + failure);
        } finally {
            backing.remove(id);
        }
        return true;
    }

    /** Works out the new screen's parents: the screen closed just before it, unless the player is going back. */
    private void link(UUID id, Origin origin) {
        java.util.ArrayDeque<Origin> stack = history.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
        Pending before = pending.remove(id);
        if (!backing.contains(id)) {
            if (before == null || clock.getAsLong() - before.at() > LINK_MILLIS) stack.clear();
            else if (before.origin() != null) stack.push(before.origin());
        }
        if (origin != null) {
            // back at a place that is already in the history: forget it and everything opened after it
            int index = 0, found = -1;
            for (Origin entry : stack) { if (entry.key().equals(origin.key())) found = index; index++; }
            for (int i = 0; i <= found; i++) stack.pop();
        }
        while (stack.size() > MAX_HISTORY) stack.removeLast();
    }

    public ScreenOpenResult open(Plugin owner, Player player, ScreenSpec spec) { return open(owner, player, spec, null); }

    /** {@link #open(Plugin, Player, ScreenSpec)} for a screen that can be returned to with Back ({@code origin}, or null). */
    public ScreenOpenResult open(Plugin owner, Player player, ScreenSpec spec, Origin origin) {
        // 0.60.0: a new screen replaces the one that is open (like opening another inventory). Before, the open
        // screen made every further open fall back to the classic menu without a word ("/vmenu opens the wrong menu").
        if (sessions.containsKey(player.getUniqueId())) close(player, ScreenInput.Reason.PLUGIN);
        // 0.62.0: the server can switch screens off, all of them or single ones; the fallback (dialog, chest) shows then
        java.util.List<String> keys = keysOf(spec, origin);
        String ownerName = owner == null ? "" : owner.getName();
        synchronized (seen) {
            for (String key : keys) { seen.remove(key); seen.put(key, ownerName); }
            while (seen.size() > MAX_SEEN) seen.remove(seen.keySet().iterator().next());
        }
        long now = clock.getAsLong();
        Long chain = offUntil.get(player.getUniqueId());
        boolean off = chain != null && now <= chain;
        if (!off) {
            offUntil.remove(player.getUniqueId());
            try { off = blocked.test(ownerName, keys); } catch (RuntimeException ruleFailed) { off = false; }
        }
        if (off) {
            offUntil.put(player.getUniqueId(), now + OFF_CHAIN_MILLIS);
            runFallback(spec, player);
            return ScreenOpenResult.refused(REFUSED_OFF);
        }
        String refused = refusal(player);
        if (refused != null) {
            runFallback(spec, player);
            return ScreenOpenResult.refused(refused);
        }
        link(player.getUniqueId(), origin);
        ScreenSpec.Mode mode = spec.preferred();
        if (mode == ScreenSpec.Mode.AUTO || mode == ScreenSpec.Mode.PINNED)
            mode = packLoaded.test(player) && !classic.test(player) ? ScreenSpec.Mode.PINNED : ScreenSpec.Mode.FLOATING;
        Session session = new Session(owner, player, spec, mode, origin);
        sessions.put(player.getUniqueId(), session);
        try {
            session.build();
        } catch (RuntimeException failure) {
            session.teardown(false);
            sessions.remove(player.getUniqueId());
            runFallback(spec, player);
            return ScreenOpenResult.refused("screen.refused.unsupported");
        }
        return ScreenOpenResult.opened(mode);
    }

    /** Lang key why this player may not open a screen now, or null. */
    String refusal(Player player) {
        if (sessions.containsKey(player.getUniqueId())) return "screen.refused.busy";
        if (player.isInsideVehicle()) return "screen.refused.riding";
        if (player.isGliding()) return "screen.refused.gliding";
        if (player.getFallDistance() > 1 && !((Entity) player).isOnGround()) return "screen.refused.falling";
        Material at = player.getLocation().getBlock().getType();
        if (player.isInWater() || at == Material.LAVA || at == Material.WATER) return "screen.refused.liquid";
        Long hit = lastDamage.get(player.getUniqueId());
        if (hit != null && clock.getAsLong() - hit < COMBAT_MILLIS) return "screen.refused.combat";
        return null;
    }

    private static void runFallback(ScreenSpec spec, Player player) {
        try { spec.fallback().accept(player); } catch (RuntimeException ignored) { }
    }

    public Optional<ScreenSession> session(Player player) { return Optional.ofNullable(sessions.get(player.getUniqueId())); }

    public void close(Player player, ScreenInput.Reason reason) {
        UUID id = player.getUniqueId();
        Session session = sessions.remove(id);
        if (session == null) {
            if (reason == ScreenInput.Reason.QUIT) { lastLeftClick.remove(id); lastClick.remove(id); guards.remove(id); }
            return;
        }
        // closed by code: the next screen (if one follows at once) gets this one as its parent; closed by the
        // player, damage, teleport and so on: the whole history ends
        if (reason == ScreenInput.Reason.PLUGIN) pending.put(id, new Pending(session.origin, clock.getAsLong()));
        else { pending.remove(id); history.remove(id); }
        // 0.65.0: closed by a click (inside its handler or right after it): the button is probably still down
        long now = clock.getAsLong();
        Long clicked = lastClick.get(id);
        if (clicking.contains(id) || (clicked != null && now - clicked <= CLICK_CLOSES_MILLIS)) guards.put(id, new Guard(now, now + GUARD_MILLIS));
        if (reason == ScreenInput.Reason.QUIT) { lastLeftClick.remove(id); lastClick.remove(id); guards.remove(id); }
        session.finish(reason);
    }

    /** Adds Core's Back button (a strip at the bottom, left) when the player came from another screen and the layout has none. */
    private ScreenLayout withBack(Player player, Origin origin, ScreenLayout layout) {
        if ((origin != null && !origin.button()) || !hasBack(player)) return layout;
        for (ScreenWidget widget : layout.widgets()) if (BACK_ID.equals(widget.id())) return layout;
        List<ScreenWidget> widgets = new java.util.ArrayList<>(layout.widgets());
        Object label;
        try { label = Vystorm.vystorm_core.lang.Localization.get().core().component(player, "menu.back"); }
        catch (RuntimeException | LinkageError noLang) { label = "← Back"; }
        // its own strip under the screen's content, so it never covers a hint line or footer buttons
        widgets.add(ScreenWidget.button(BACK_ID, 0.03, layout.height() + 0.006, 0.24, 0.056, label));
        return new ScreenLayout(layout.width(), layout.height() + BACK_STRIP, widgets);
    }

    public void closeAll(ScreenInput.Reason reason) {
        for (Session session : List.copyOf(sessions.values())) close(session.player, reason);
    }

    /** A plugin was disabled: close the screens it opened. */
    public void release(Plugin owner) {
        for (Session session : List.copyOf(sessions.values())) if (session.owner.equals(owner)) close(session.player, ScreenInput.Reason.PLUGIN);
    }

    // ---- events (called by ScreenListener) -------------------------------------------------------------------------

    void onDamage(Player player) {
        lastDamage.put(player.getUniqueId(), clock.getAsLong());
        close(player, ScreenInput.Reason.DAMAGE);
    }

    void onRotate(Player player, float yaw, float pitch) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) session.hover(yaw, pitch);
    }

    /** True when the clicked entity belongs to this player's screen (the event should then be cancelled). */
    boolean onClick(Player player, Entity clicked, boolean right) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || clicked == null || session.interaction == null || !session.interaction.getUniqueId().equals(clicked.getUniqueId())) return false;
        // 0.65.0: the click box is there before the screen is drawn; nothing can be clicked on a screen nobody sees yet
        if (!session.built) return true;
        // a right button that is still down from the click that led here repeats "use" several times a second
        if (right && stopsHeldRight(player.getUniqueId())) return true;
        session.click(right);
        return true;
    }

    private boolean stopsHeldRight(UUID id) {
        if (!guarded(id)) return false;
        Guard guard = guards.get(id);
        long until = Math.min(guard.start() + GUARD_MAX_MILLIS, clock.getAsLong() + GUARD_EXTEND_MILLIS);
        if (until > guard.until()) guards.put(id, new Guard(guard.start(), until));
        return true;
    }

    /**
     * Arm swing while a screen is open counts as a left click unless it directly follows a right click. Not while the
     * screen is still being built or the click guard is up: then the swing comes from the button of the click that
     * led here, held down on the block behind the new screen (0.65.0). A new click on the screen itself is an attack
     * on its click box and counts at once.
     */
    void onSwing(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.built || guarded(player.getUniqueId())) return;
        if (clock.getAsLong() - session.lastRight > 80) session.click(false);
    }

    void onScroll(Player player, int delta) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && delta != 0) session.deliver(new ScreenInput.Scroll(delta));
    }

    /** Sneak: closes unless the screen claims SNEAK. Returns true when a screen handled it. */
    boolean onSneak(Player player, boolean pressed) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        if (session.spec.claimedKeys().contains(ScreenInput.Key.SNEAK)) session.deliver(new ScreenInput.KeyInput(ScreenInput.Key.SNEAK, pressed));
        else if (pressed) close(player, ScreenInput.Reason.PLAYER);
        return true;
    }

    void onKey(Player player, ScreenInput.Key key, boolean pressed) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && key != ScreenInput.Key.SNEAK && session.spec.claimedKeys().contains(key)) session.deliver(new ScreenInput.KeyInput(key, pressed));
    }

    /** True when this player is mounted by an open screen and must not dismount. */
    boolean holdsMount(Player player, Entity vehicle) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && !session.closing && session.mount != null && vehicle != null && session.mount.getUniqueId().equals(vehicle.getUniqueId());
    }

    boolean hasSession(Player player) { return sessions.containsKey(player.getUniqueId()); }
    /** The layout as drawn (with Core's Back button when there is one); null without a screen. */
    ScreenLayout layoutOf(Player player) { Session session = sessions.get(player.getUniqueId()); return session == null ? null : session.layout; }

    /** Removes tagged screen entities that belong to no open session (crash leftovers). */
    public int sweep(Iterable<? extends Entity> loaded) {
        Set<UUID> live = new HashSet<>();
        for (Session session : sessions.values()) session.collect(live);
        int removed = 0;
        for (Entity entity : loaded) {
            if (!entity.getPersistentDataContainer().has(tag, PersistentDataType.BYTE) || live.contains(entity.getUniqueId())) continue;
            entities.remove(entity);
            removed++;
        }
        return removed;
    }

    // ---- session ---------------------------------------------------------------------------------------------------

    final class Session implements ScreenSession {
        final Plugin owner;
        final Player player;
        final ScreenSpec spec;
        final ScreenSpec.Mode mode;
        final Location saved;
        /** Camera position; measured again after mounting (sitting changes the eye height). */
        Location eye;
        /** Blocks shown as air to the viewer while an opaque backdrop is open (restored on close). */
        final List<Location> hidden = new java.util.ArrayList<>();
        final float anchorYaw, anchorPitch;
        final Map<String, ScreenWidget> widgets = new LinkedHashMap<>();
        final Map<String, TextDisplay> displays = new LinkedHashMap<>();
        final Map<String, Display> icons = new LinkedHashMap<>();
        /** Boxes behind widgets (buttons, inputs, filled labels, FILL rectangles), keyed by widget id. */
        final Map<String, TextDisplay> boxes = new LinkedHashMap<>();
        /** Shown map widgets (PINNED with frames) and the last pixels of every map widget, keyed by widget id. */
        final Map<String, ScreenMapFrames.Handle> mapFrames = new LinkedHashMap<>();
        final Map<String, byte[]> mapPixels = new HashMap<>();
        /** Current widgets' layout; starts as the spec's layout and changes with {@link #setLayout}. */
        ScreenLayout layout;
        Entity mount;
        Interaction interaction;
        TextDisplay pointer, background, backdrop;
        String hovered;
        double lastU, lastV;
        long lastRight = Long.MIN_VALUE / 2, lastLeft = Long.MIN_VALUE / 2;
        boolean closing, open = true;
        /** True once the screen is drawn ({@link #buildVisuals}); the click box exists before that. */
        boolean built;

        /** Player state before the screen hid the first-person hand; restored in {@link #showHand}. */
        boolean wasInvisible; int savedSlot = -1; boolean handHidden;

        /**
         * The client draws the empty arm (and a held item) over the bottom-right of every PINNED screen and clears depth
         * before it, so nothing can cover it. An invisible player draws no empty arm, and an empty hotbar slot no item;
         * clicks and head rotation stay as they are. Restored on close.
         */
        void hideHand() {
            if (mode != ScreenSpec.Mode.PINNED || handHidden) return;
            try {
                wasInvisible = player.isInvisible();
                var inventory = player.getInventory();
                if (inventory != null) {
                    int held = inventory.getHeldItemSlot();
                    org.bukkit.inventory.ItemStack item = inventory.getItem(held);
                    if (item != null && !item.getType().isAir()) {
                        for (int slot = 0; slot < 9; slot++) {
                            org.bukkit.inventory.ItemStack other = inventory.getItem(slot);
                            if (other == null || other.getType().isAir()) { savedSlot = held; inventory.setHeldItemSlot(slot); break; }
                        }
                    }
                }
                player.setInvisible(true);
                player.setPlayerWeather(org.bukkit.WeatherType.CLEAR);   // rain falls closer than the backdrop
                handHidden = true;
            } catch (RuntimeException | LinkageError failure) {
                handHidden = false;
            }
        }

        void showHand() {
            if (!handHidden) return;
            handHidden = false;
            try {
                player.setInvisible(wasInvisible);
                player.resetPlayerWeather();
                if (savedSlot >= 0 && player.getInventory() != null) player.getInventory().setHeldItemSlot(savedSlot);
            } catch (RuntimeException | LinkageError ignored) { }
            savedSlot = -1;
        }

        /** How to reopen this screen for Back, or null. */
        final Origin origin;

        Session(Plugin owner, Player player, ScreenSpec spec, ScreenSpec.Mode mode) { this(owner, player, spec, mode, null); }

        Session(Plugin owner, Player player, ScreenSpec spec, ScreenSpec.Mode mode, Origin origin) {
            this.origin = origin;
            this.owner = owner;
            this.player = player;
            this.spec = spec;
            this.mode = mode;
            this.saved = player.getLocation().clone();
            this.eye = player.getEyeLocation().clone();
            this.anchorYaw = eye.getYaw();
            this.anchorPitch = Math.max(-60f, Math.min(60f, eye.getPitch()));
            this.layout = withBack(player, origin, spec.layout());
            for (ScreenWidget widget : layout.widgets()) widgets.put(widget.id(), widget);
        }

        void build() {
            mount = entities.mount(player, saved);
            if (mount != null) mount.addPassenger(player);
            // 0.65.0: the click box is spawned at once, not with the visuals two ticks later. When this screen replaces
            // another one, the old box goes and the new one comes in the same tick, so the mouse button that is still
            // down from the click never finds the world behind the screens. The player is inside the box either way
            // (3 blocks high around the eye; sitting moves the eye by less than a block).
            interaction = entities.interaction(player, eye.clone().subtract(0, 1.5, 0), 3f, 3f);
            later.accept(() -> {
                if (!open) return;
                Location seated = player.getEyeLocation().clone();
                seated.setYaw(anchorYaw);
                seated.setPitch(eye.getPitch());
                eye = seated;
                buildVisuals();
            });
        }

        void buildVisuals() {
            hideHand();
            hideNearBlocks();
            centreClickBox();
            if (mode == ScreenSpec.Mode.PINNED && spec.backdrop() != ScreenSpec.Backdrop.NONE) backdrop = entities.text(player, panelLocation(), this::placeBackdrop);
            background = entities.text(player, panelLocation(), d -> placeBackground(d));
            spawnWidgets();
            if (mode == ScreenSpec.Mode.PINNED) pointer = entities.text(player, pointerLocation(), this::placePointer);
            for (ScreenWidget widget : widgets.values()) draw(widget);
            built = true;
        }

        /** Puts the click box around the seated eye, where it was spawned before 0.65.0. */
        private void centreClickBox() {
            if (interaction == null) { interaction = entities.interaction(player, eye.clone().subtract(0, 1.5, 0), 3f, 3f); return; }
            try {
                Location at = eye.clone().subtract(0, 1.5, 0);
                Location now = interaction.getLocation();
                if (now != null && now.distanceSquared(at) > 0.01) {
                    at.setYaw(now.getYaw());
                    at.setPitch(now.getPitch());
                    interaction.teleport(at);
                }
            } catch (RuntimeException | LinkageError keepsItsPlace) { }
        }

        // -- placement --

        private Location panelLocation() {
            if (mode == ScreenSpec.Mode.PINNED) return eye.clone();
            ScreenGeometry.Vec n = ScreenGeometry.direction(anchorYaw, anchorPitch);
            Location at = eye.clone().add(n.x() * FLOATING_DISTANCE, n.y() * FLOATING_DISTANCE, n.z() * FLOATING_DISTANCE);
            at.setYaw(anchorYaw + 180f);
            at.setPitch(-anchorPitch);
            return at;
        }

        private Location pointerLocation() {
            ScreenGeometry.Vec n = ScreenGeometry.direction(anchorYaw, anchorPitch);
            Location at = eye.clone().add(n.x() * PINNED_DISTANCE, n.y() * PINNED_DISTANCE, n.z() * PINNED_DISTANCE);
            at.setYaw(anchorYaw + 180f);
            at.setPitch(-anchorPitch);
            return at;
        }

        /** Panel units → offset in the display's local frame; FLOATING scales by distance so both modes look equal. */
        private Transformation transform(double x, double bottomY, double scale) {
            boolean pinned = mode == ScreenSpec.Mode.PINNED;
            double k = pinned ? 1.0 : FLOATING_DISTANCE;
            Vector3f translation = pinned
                    ? new Vector3f((float) x, (float) bottomY, (float) -PINNED_DISTANCE)
                    : new Vector3f((float) (x * k), (float) (bottomY * k), 0f);
            float s = (float) (scale * k);
            return new Transformation(translation, new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
        }

        private void common(TextDisplay d) {
            d.setBillboard(mode == ScreenSpec.Mode.PINNED ? Display.Billboard.CENTER : Display.Billboard.FIXED);
            d.setSeeThrough(mode == ScreenSpec.Mode.PINNED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(1000);
        }

        private void placeBackground(TextDisplay d) {
            common(d);
            solid(d, layout.width() + PANEL_BORDER, layout.height() + PANEL_BORDER, mode == ScreenSpec.Mode.PINNED ? 0 : 0.008, 0x1B1F27, 235);
        }

        /** PINNED only: a dim, screen-filling backdrop behind the panel, like the dark background of vanilla menus. */
        private void placeBackdrop(TextDisplay d) {
            common(d);
            // Depth-tested (not see-through) when opaque: the world behind it (clouds, sign texts, block outlines) is drawn
            // after see-through text and would otherwise show through. Nearer blocks are hidden by hideNearBlocks.
            if (spec.backdrop().opacity() >= 255) d.setSeeThrough(false);
            solid(d, BACKDROP_WIDTH, BACKDROP_HEIGHT, BACKDROP_DEPTH, spec.backdrop().rgb(), spec.backdrop().opacity());
        }

        /**
         * One stretched {@link ScreenShader#SOLID} glyph covering {@code width x height} panel units centred on the panel
         * centre, {@code depth} panel units behind it. The 8 px glyph sits 1 px above the text block's bottom edge.
         */
        private void solid(TextDisplay d, double width, double height, double depth, int rgb, int opacity) {
            solidAt(d, 0, 0, width, height, depth, rgb, opacity);
        }

        /** Like {@link #solid} but centred on {@code (cx, cy)} (panel units from the panel centre, x right, y up). */
        private void solidAt(TextDisplay d, double cx, double cy, double width, double height, double depth, int rgb, int opacity) {
            PlatformCompat.text(d, opacity <= 0 ? Component.empty() : Component.text(ScreenShader.SOLID).font(Key.key(ScreenShader.FONT)).color(TextColor.color(rgb & 0xFFFFFF)));
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setTextOpacity((byte) Math.max(26, Math.min(255, opacity)));
            boolean pinned = mode == ScreenSpec.Mode.PINNED;
            double k = pinned ? 1.0 : FLOATING_DISTANCE;
            float sx = (float) (width / GLYPH * k), sy = (float) (height / GLYPH * k);
            float y = (float) ((cy - height / 2) * k - sy * PIXEL);
            // A bitmap glyph advances one pixel more than it draws (9 vs 8 px), so the centred line puts the 8 px glyph half a
            // pixel right of centre: at this stretch that is width / 16. Measured in a real 26.2 client (2026-11-15).
            float x = (float) ((cx - width / 16) * k);
            Vector3f translation = pinned ? new Vector3f(x, y, (float) -(PINNED_DISTANCE + depth)) : new Vector3f(x, y, (float) (-depth * k));
            d.setTransformation(new Transformation(translation, new Quaternionf(), new Vector3f(sx, sy, 1f), new Quaternionf()));
        }


        /**
         * Vertical centring for a small text in a box (buttons, tabs): when a fixed text height is at least half the box,
         * the line is lifted to the middle; tall boxes (menu tiles with the label under the icon) keep it at the bottom.
         */
        private double textLift(ScreenWidget widget) {
            double th = widget.textHeight();
            if (th <= 0 || widget.height() > 2 * th) return 0;
            return Math.max(0, (widget.height() - th) / 2);
        }
        private void place(TextDisplay d, ScreenWidget widget) {
            common(d);
            // Text height is capped, so a tall button (an icon tile) keeps a normal label at its bottom edge.
            double scale = Math.max(0.05, (widget.textHeight() > 0 ? widget.textHeight() : Math.min(widget.height(), TEXT_MAX)) / LINE * 0.8);
            double x = widget.u() + widget.width() / 2 - layout.width() / 2;
            double bottom = layout.height() / 2 - (widget.v() + widget.height()) + textLift(widget);
            d.setTransformation(transform(x, bottom, scale));
        }

        private void placePointer(TextDisplay d) {
            d.setBillboard(Display.Billboard.FIXED);
            // Maps are drawn after all text (26.2 phase order: texts, then translucent custom geometry). A see-through
            // pointer writes no depth and would vanish under a map; a depth-tested one that the shader pulls close to
            // the camera stays on top. Without maps the pointer keeps the proven see-through setup.
            d.setSeeThrough(mapFrames.isEmpty());
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            Component glyph = Component.text(ScreenShader.POINTER).font(Key.key(ScreenShader.FONT)).color(TextColor.color(ScreenShader.MARKER_RGB));
            PlatformCompat.text(d, glyph);
            float s = 0.12f;
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        }

        // -- drawing --

        /** In drawing order: a widget's box first, then its text or icon, so later widgets lie on top. */
        private void spawnWidgets() {
            for (ScreenWidget widget : widgets.values()) {
                if (hasBox(widget)) boxes.put(widget.id(), entities.text(player, panelLocation(), d -> placeBox(d, widget)));
                if (widget.kind() == ScreenWidget.Kind.ICON) icons.put(widget.id(), spawnIcon(widget));
                else if (widget.kind() == ScreenWidget.Kind.MAP && showMap(widget)) continue;
                else if (widget.kind() != ScreenWidget.Kind.FILL) displays.put(widget.id(), entities.text(player, panelLocation(), d -> place(d, widget)));
            }
        }

        // -- maps (since 0.59.0) --

        /** Spawns the client-only frames of a PINNED map widget; false when the placeholder text must be shown instead. */
        private boolean showMap(ScreenWidget widget) {
            if (mode != ScreenSpec.Mode.PINNED || !maps.supported()) return false;
            ScreenMapCodec.Placement at = ScreenMapCodec.placement(eye.getX(), eye.getY(), eye.getZ());
            ScreenMapFrames.Handle handle = maps.show(player, at, mapTiles(widget));
            if (handle == null) return false;
            mapFrames.put(widget.id(), handle);
            return true;
        }

        /** The widget's tiles with their shader headers, from its last pixels (dark until the plugin sets some). */
        List<byte[]> mapTiles(ScreenWidget widget) {
            ScreenWidget.MapGrid grid = widget.mapGrid();
            byte[] canvas = mapPixels.get(widget.id());
            if (canvas == null) {
                canvas = new byte[grid.pixelWidth() * grid.pixelHeight()];
                java.util.Arrays.fill(canvas, ScreenMapCodec.BIT_ZERO);
            }
            double left = widget.u() - layout.width() / 2, top = layout.height() / 2 - widget.v();
            double tw = widget.width() / grid.columns(), th = widget.height() / grid.rows();
            List<byte[]> tiles = new java.util.ArrayList<>(grid.columns() * grid.rows());
            for (int ty = 0; ty < grid.rows(); ty++) for (int tx = 0; tx < grid.columns(); tx++) {
                byte[] header = ScreenMapCodec.header(left + (tx + 0.5) * tw, top - (ty + 0.5) * th, tw / 2, th / 2);
                tiles.add(ScreenMapCodec.tile(canvas, grid.pixelWidth(), tx, ty, header));
            }
            return tiles;
        }

        private void removeMaps() {
            for (ScreenMapFrames.Handle handle : mapFrames.values()) handle.remove();
            mapFrames.clear();
        }

        private static boolean hasBox(ScreenWidget widget) {
            return widget.kind() == ScreenWidget.Kind.FILL || widget.kind() == ScreenWidget.Kind.MAP || widget.kind() == ScreenWidget.Kind.BUTTON
                    || widget.kind() == ScreenWidget.Kind.INPUT || (widget.restingFill() >>> 24) > 0 || widget.selected();
        }

        private void placeBox(TextDisplay d, ScreenWidget widget) {
            common(d);
            drawBox(d, widget);
        }

        /** Gold when hovered or selected, else the widget's resting colour. */
        private void drawBox(TextDisplay d, ScreenWidget widget) {
            boolean lit = widget.selected() || (widget.clickable() && widget.id().equals(hovered));
            int argb = lit ? widget.litFill() : widget.restingFill();
            double cx = widget.u() + widget.width() / 2 - layout.width() / 2;
            double cy = layout.height() / 2 - (widget.v() + widget.height() / 2);
            solidAt(d, cx, cy, widget.width(), widget.height(), mode == ScreenSpec.Mode.PINNED ? 0 : 0.004, argb, argb >>> 24);
        }

        /** Item icon centred in the widget box, as large as the box's smaller side. */
        /**
         * Icons are texture sprites drawn as text (26.x {@code object} components), so they share the see-through text
         * layer and the backdrop cannot hide them. Servers whose API lacks object components get an item display.
         */
        private Display spawnIcon(ScreenWidget widget) {
            Object icon = widget.icon();
            String material = icon instanceof org.bukkit.inventory.ItemStack stack ? stack.getType().name() : String.valueOf(icon);
            try {
                Component glyph = spriteGlyph(ScreenSprites.of(material));
                return entities.text(player, panelLocation(), d -> placeSpriteIcon(d, widget, glyph));
            } catch (LinkageError olderApi) {
                return entities.item(player, panelLocation(), d -> placeIcon(d, widget));
            }
        }

        private static Component spriteGlyph(ScreenSprites.Sprite sprite) {
            return SpriteGlyphs.glyph(sprite.atlas(), sprite.sprite());
        }

        /** An 8 px sprite glyph scaled to the widget box's smaller side, centred in the box. */
        private void placeSpriteIcon(TextDisplay d, ScreenWidget widget, Component glyph) {
            common(d);
            PlatformCompat.text(d, glyph);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            boolean pinned = mode == ScreenSpec.Mode.PINNED;
            double k = pinned ? 1.0 : FLOATING_DISTANCE, size = Math.min(widget.width(), widget.height());
            float s = (float) (size / GLYPH * k);
            double x = widget.u() + widget.width() / 2 - layout.width() / 2;
            double bottom = layout.height() / 2 - (widget.v() + widget.height() / 2) - size / 2;
            float y = (float) (bottom * k - s * PIXEL);
            Vector3f translation = pinned ? new Vector3f((float) x, y, (float) -PINNED_DISTANCE) : new Vector3f((float) (x * k), y, 0f);
            d.setTransformation(new Transformation(translation, new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        }

        private void placeIcon(org.bukkit.entity.ItemDisplay d, ScreenWidget widget) {
            d.setBillboard(mode == ScreenSpec.Mode.PINNED ? Display.Billboard.CENTER : Display.Billboard.FIXED);
            Object icon = widget.icon();
            d.setItemStack(icon instanceof org.bukkit.inventory.ItemStack stack ? stack : new org.bukkit.inventory.ItemStack((Material) icon));
            double x = widget.u() + widget.width() / 2 - layout.width() / 2;
            double y = layout.height() / 2 - (widget.v() + widget.height() / 2);
            boolean pinned = mode == ScreenSpec.Mode.PINNED;
            double k = pinned ? 1.0 : FLOATING_DISTANCE;
            float s = (float) (Math.min(widget.width(), widget.height()) * k);
            Vector3f translation = pinned ? new Vector3f((float) x, (float) y, (float) -(PINNED_DISTANCE - 0.01)) : new Vector3f((float) (x * k), (float) (y * k), 0.01f);
            d.setTransformation(new Transformation(translation, new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        }

        private void draw(ScreenWidget widget) {
            TextDisplay box = boxes.get(widget.id());
            if (box != null && box.isValid()) drawBox(box, widget);
            if (widget.kind() == ScreenWidget.Kind.ICON || widget.kind() == ScreenWidget.Kind.FILL) return;
            TextDisplay d = displays.get(widget.id());
            if (d == null || !d.isValid()) return;
            Component text = widget.text() instanceof Component c ? c : Component.text(String.valueOf(widget.text()));
            boolean lit = widget.selected() || (widget.id().equals(hovered) && widget.clickable());
            if (lit && widget.litIsBright()) text = text.color(TextColor.color(0x252A33));
            else if (!widget.enabled() && widget.kind() != ScreenWidget.Kind.LABEL) text = text.color(TextColor.color(0x8A919C));
            PlatformCompat.text(d, text);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            fit(d, widget, net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text));
        }

        /** Shrinks the text so its estimated width stays inside the widget box (never above the line height). */
        private void fit(TextDisplay d, ScreenWidget widget, String plain) {
            double scale = Math.max(0.05, (widget.textHeight() > 0 ? widget.textHeight() : Math.min(widget.height(), TEXT_MAX)) / LINE * 0.8);
            double width = textWidth(plain) * PIXEL;
            double pad = widget.align() == ScreenWidget.Align.CENTER ? 0 : Math.min(0.02, widget.width() * 0.05);
            if (width > 0) scale = Math.min(scale, (widget.width() - 2 * pad) * 0.96 / width);
            double x = widget.u() + widget.width() / 2 - layout.width() / 2;
            double shown = width * scale;
            if (widget.align() == ScreenWidget.Align.START) x = widget.u() + pad + shown / 2 - layout.width() / 2;
            else if (widget.align() == ScreenWidget.Align.END) x = widget.u() + widget.width() - pad - shown / 2 - layout.width() / 2;
            double bottom = layout.height() / 2 - (widget.v() + widget.height()) + textLift(widget);
            d.setTransformation(transform(x, bottom, scale));
        }

        // -- input --

        Optional<double[]> point(float yaw, float pitch) {
            if (mode == ScreenSpec.Mode.PINNED) return ScreenGeometry.pinnedPoint(anchorYaw, anchorPitch, yaw, pitch, PINNED_DISTANCE);
            return ScreenGeometry.floatingPoint(anchorYaw, anchorPitch, yaw, pitch, FLOATING_DISTANCE)
                    .map(p -> new double[] { p[0] / FLOATING_DISTANCE, p[1] / FLOATING_DISTANCE });
        }

        void hover(float yaw, float pitch) {
            Optional<double[]> p = point(yaw, pitch);
            String now = null;
            if (p.isPresent()) {
                lastU = p.get()[0];
                lastV = p.get()[1];
                now = layout.hit(lastU, lastV).map(ScreenWidget::id).orElse(null);
            }
            if (java.util.Objects.equals(now, hovered)) return;
            String before = hovered;
            hovered = now;
            if (before != null && widgets.containsKey(before)) draw(widgets.get(before));
            if (now != null) draw(widgets.get(now));
            deliver(new ScreenInput.Hover(now));
        }

        void click(boolean right) {
            long now = clock.getAsLong();
            if (right) lastRight = now;
            else {
                // One left click arrives twice (attack on the click box and the arm swing): count it once. The time is
                // kept per player, not per screen: the second half of a click must not land on the screen that the
                // first half just opened (0.61.0: Back, then at once a tile of the parent).
                Long last = lastLeftClick.get(player.getUniqueId());
                if (last != null && now - last < 150) return;
                lastLeftClick.put(player.getUniqueId(), now);
                lastLeft = now;
            }
            UUID clicker = player.getUniqueId();
            lastClick.put(clicker, now);
            boolean outermost = clicking.add(clicker);
            try {
                hover(player.getLocation().getYaw(), player.getLocation().getPitch());
                ScreenWidget hit = hovered == null ? null : widgets.get(hovered);
                // Core's Back button: go to the parent screen; a screen with its own "back" of that id still gets the click
                // when there is no parent
                if (hit != null && hit.clickable() && BACK_ID.equals(hit.id()) && back(player)) return;
                deliver(new ScreenInput.Click(hit == null || !hit.clickable() ? null : hit.id(), lastU, lastV, right));
            } finally {
                // 0.65.0: the two halves of a click are told apart by time, and the second half waits in the server's
                // queue while the first is handled. A handler that takes longer than the window (a config save with a
                // reload took 3 s) let the second half through as a new click: one click toggled a switch twice. So
                // the window starts again when the handler is done.
                long done = clock.getAsLong();
                if (outermost) clicking.remove(clicker);
                lastClick.computeIfPresent(clicker, (id, at) -> Math.max(at, done));
                if (done > now) {
                    if (right) lastRight = done;
                    else {
                        lastLeft = done;
                        lastLeftClick.computeIfPresent(player.getUniqueId(), (id, at) -> Math.max(at, done));
                    }
                }
            }
        }

        void deliver(ScreenInput input) {
            try { spec.onInput().accept(this, input); }
            catch (RuntimeException failure) {
                core.getLogger().warning("[Screens] " + owner.getName() + " screen " + spec.id() + " failed on " + input.getClass().getSimpleName() + ": " + failure);
            }
        }

        void collect(Set<UUID> live) {
            for (Entity e : allEntities()) if (e != null) live.add(e.getUniqueId());
        }

        private List<Entity> allEntities() {
            List<Entity> all = new java.util.ArrayList<>(boxes.values());
            all.addAll(displays.values());
            all.addAll(icons.values());
            all.add(background);
            all.add(backdrop);
            all.add(pointer);
            all.add(interaction);
            all.add(mount);
            return all;
        }

        /**
         * Blocks closer to the eye than the PINNED backdrop would poke through it (a wall next to the player). With an
         * opaque backdrop they are sent as air to this player only; the world itself never changes.
         */
        private void hideNearBlocks() {
            if (mode != ScreenSpec.Mode.PINNED || spec.backdrop().opacity() < 255 || eye.getWorld() == null) return;
            // The backdrop is a flat plane: towards the screen corners it lies farther away (1 / cos of the angle), up to
            // 3 blocks on ultrawide screens. Hide everything nearer than the plane at the corners of the player's view, plus
            // 0.7 for resource packs whose leaves reach half a block outside the block (Better Leaves).
            ScreenProfile view;
            try { view = ScreenProfiles.load(player); } catch (RuntimeException | LinkageError noPrefs) { view = ScreenProfile.DEFAULT; }
            double corner = Math.sqrt(1 + Math.pow(view.viewWidth() / 2, 2) + Math.pow(view.viewHeight() / 2, 2));
            double reach = Math.min(6, (PINNED_DISTANCE + BACKDROP_DEPTH) * corner + 0.7);
            int r = (int) Math.ceil(reach);
            org.bukkit.block.data.BlockData air = null;
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                Location at = new Location(eye.getWorld(), eye.getBlockX() + dx, eye.getBlockY() + dy, eye.getBlockZ() + dz);
                if (nearest(at) > reach) continue;
                org.bukkit.block.Block block = at.getBlock();
                if (block == null) continue;
                Material type = block.getType();
                if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) continue;
                if (air == null) air = Material.AIR.createBlockData();
                hidden.add(at);
                player.sendBlockChange(at, air);
            }
        }

        /** Distance from the eye to the nearest point of the block at {@code at}. */
        private double nearest(Location at) {
            double x = Math.max(at.getBlockX(), Math.min(eye.getX(), at.getBlockX() + 1)) - eye.getX();
            double y = Math.max(at.getBlockY(), Math.min(eye.getY(), at.getBlockY() + 1)) - eye.getY();
            double z = Math.max(at.getBlockZ(), Math.min(eye.getZ(), at.getBlockZ() + 1)) - eye.getZ();
            return Math.sqrt(x * x + y * y + z * z);
        }

        private void restoreBlocks() {
            if (player.isOnline()) for (Location at : hidden) player.sendBlockChange(at, at.getBlock().getBlockData());
            hidden.clear();
        }

        void teardown(boolean returnPlayer) {
            showHand();
            closing = true;
            restoreBlocks();
            removeMaps();
            if (mount != null) mount.removePassenger(player);
            for (Entity e : allEntities()) if (e != null) entities.remove(e);
            displays.clear();
            if (returnPlayer && player.isOnline()) {
                Location back = saved.clone();
                back.setYaw(player.getLocation().getYaw());
                back.setPitch(player.getLocation().getPitch());
                player.teleport(back);
            }
        }

        void finish(ScreenInput.Reason reason) {
            if (!open) return;
            open = false;
            teardown(reason != ScreenInput.Reason.QUIT && reason != ScreenInput.Reason.DEATH);
            deliver(new ScreenInput.Closed(reason));
        }

        // -- ScreenSession --

        @Override public Player player() { return player; }
        @Override public ScreenSpec spec() { return spec; }
        @Override public ScreenSpec.Mode mode() { return mode; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { ScreenRuntime.this.close(player, ScreenInput.Reason.PLUGIN); }

        @Override public void setText(String widgetId, Object text) {
            ScreenWidget widget = widgets.get(widgetId);
            if (widget == null || !open) return;
            ScreenWidget changed = widget.withText(text);
            widgets.put(widgetId, changed);
            draw(changed);
        }

        @Override public void setLayout(ScreenLayout requested) {
            if (!open || requested == null) return;
            final ScreenLayout next = withBack(player, origin, requested);
            for (Entity e : displays.values()) entities.remove(e);
            for (Entity e : icons.values()) entities.remove(e);
            for (Entity e : boxes.values()) entities.remove(e);
            removeMaps();
            displays.clear();
            icons.clear();
            boxes.clear();
            widgets.clear();
            layout = next;
            mapPixels.keySet().removeIf(id -> next.widgets().stream().noneMatch(w -> w.id().equals(id) && w.kind() == ScreenWidget.Kind.MAP));
            for (ScreenWidget widget : next.widgets()) widgets.put(widget.id(), widget);
            hovered = null;
            if (!built) return; // visuals not built yet: buildVisuals will spawn them
            if (background != null && background.isValid()) placeBackground(background);
            spawnWidgets();
            // the pointer must stay the last (topmost) display, so it is spawned again after the new widgets
            if (pointer != null) {
                entities.remove(pointer);
                pointer = entities.text(player, pointerLocation(), this::placePointer);
            }
            for (ScreenWidget widget : widgets.values()) draw(widget);
            hover(player.getLocation().getYaw(), player.getLocation().getPitch());
        }

        @Override public void setMap(String widgetId, byte[] colours) {
            ScreenWidget widget = widgets.get(widgetId);
            if (widget == null || !open || widget.kind() != ScreenWidget.Kind.MAP || colours == null) return;
            ScreenWidget.MapGrid grid = widget.mapGrid();
            if (colours.length != grid.pixelWidth() * grid.pixelHeight())
                throw new IllegalArgumentException("Map " + widgetId + " needs " + grid.pixelWidth() + "x" + grid.pixelHeight() + " pixels, got " + colours.length);
            mapPixels.put(widgetId, colours);
            ScreenMapFrames.Handle handle = mapFrames.get(widgetId);
            if (handle == null) return; // placeholder, or visuals not built yet (they use the stored pixels)
            List<byte[]> tiles = mapTiles(widget);
            for (int i = 0; i < tiles.size(); i++) handle.pixels(i, tiles.get(i));
        }

        @Override public boolean showsMap(String widgetId) { return mapFrames.containsKey(widgetId); }

        @Override public void setEnabled(String widgetId, boolean enabled) {
            ScreenWidget widget = widgets.get(widgetId);
            if (widget == null || !open) return;
            ScreenWidget changed = widget.withEnabled(enabled);
            widgets.put(widgetId, changed);
            draw(changed);
        }
    }
}
