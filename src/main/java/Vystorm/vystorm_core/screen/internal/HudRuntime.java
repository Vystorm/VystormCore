package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.screen.HudCard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Boss bars behind the HUD corner window (since 0.59.0): up to three WHITE rows per player. Server thread only. */
public final class HudRuntime implements Listener {
    private static HudRuntime instance;
    private final Plugin core;
    private final Map<UUID, Window> windows = new HashMap<>();

    private static final class Window {
        Plugin owner;
        final List<BossBar> rows = new ArrayList<>();
        BukkitTask hide;
    }

    private final java.util.function.Predicate<Player> packApplied;

    private HudRuntime(Plugin core, java.util.function.Predicate<Player> packApplied) { this.core = core; this.packApplied = packApplied; }

    public static void install(Plugin core) { install(core, p -> true); }

    /** 0.65.1: the window needs the pack's shader and font; a player whose client has not applied the pack gets none. */
    public static void install(Plugin core, java.util.function.Predicate<Player> packApplied) {
        instance = new HudRuntime(core, packApplied == null ? p -> true : packApplied);
        Bukkit.getPluginManager().registerEvents(instance, core);
    }

    public static HudRuntime get() {
        if (instance == null) throw new IllegalStateException("Vystorm Core HUD is not running");
        return instance;
    }

    public void show(Plugin owner, Player player, HudCard card, int ticks) {
        if (!packApplied.test(player)) { hide(owner, player); return; }
        Window window = windows.computeIfAbsent(player.getUniqueId(), id -> new Window());
        window.owner = owner;
        List<Component> rows = HudGlyphs.window(icon(card.icon()), card.title(), card.value(), card.progress(), barColour(card.accent()),
                card.barText(), card.footer(), card.highlight());
        while (window.rows.size() < rows.size()) window.rows.add(BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
        for (int i = 0; i < window.rows.size(); i++) {
            BossBar bar = window.rows.get(i);
            if (i < rows.size()) {
                bar.name(noShadow(rows.get(i)));
                PlatformCompat.showBossBar(player, bar);
            } else {
                PlatformCompat.hideBossBar(player, bar);
            }
        }
        if (window.hide != null) window.hide.cancel();
        window.hide = ticks > 0 ? Bukkit.getScheduler().runTaskLater(core, () -> hide(owner, player), ticks) : null;
    }

    public void hide(Plugin owner, Player player) {
        Window window = windows.get(player.getUniqueId());
        if (window == null || window.owner != owner) return;
        for (BossBar bar : window.rows) PlatformCompat.hideBossBar(player, bar);
        if (window.hide != null) window.hide.cancel();
        windows.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Window window = windows.remove(event.getPlayer().getUniqueId());
        if (window != null && window.hide != null) window.hide.cancel();
    }

    private static int barColour(HudCard.Accent accent) {
        return switch (accent) { case GREEN -> HudGlyphs.OK; case RED -> HudGlyphs.BAD; case PURPLE -> HudGlyphs.PURPLE; default -> HudGlyphs.GOLD; };
    }

    private static Component icon(Object icon) {
        if (!(icon instanceof Material material)) return null;
        try {
            ScreenSprites.Sprite sprite = ScreenSprites.of(material.name());
            return SpriteGlyphs.glyph(sprite.atlas(), sprite.sprite());
        } catch (RuntimeException | LinkageError olderApi) {
            return null;
        }
    }

    private static Component noShadow(Component row) {
        try { return HudShadow.none(row); } catch (LinkageError olderApi) { return row; }
    }
}
