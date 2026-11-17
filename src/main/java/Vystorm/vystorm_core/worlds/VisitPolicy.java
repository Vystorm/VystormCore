package Vystorm.vystorm_core.worlds;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Regeln für Besucher einer Plugin-Welt. Unveränderlich; Bau über {@link #builder()}.
 *
 * <p><b>Befehle</b> sind während eines Besuchs nur aus der Erlaubnisliste möglich (Label ohne {@code /} und ohne
 * {@code plugin:}-Präfix, oder der Hauptname des aufgelösten Befehls). Das gilt auch für OPs; Konsole und RCON sind
 * nicht betroffen. <b>Inventar</b>: bei {@code lockInventory} sind Klicken, Ziehen, Wegwerfen, Aufheben, Essen, Setzen
 * und Entity-Interaktion für Besucher gesperrt.</p>
 *
 * @since 0.26.0
 */
public final class VisitPolicy {
    private final Set<String> allowedCommands;
    private final ToolKit toolKit;
    private final GameMode gameMode;
    private final boolean invulnerable, collidable, clearEffects, fullFood, lockInventory, portalExits;
    private final boolean isolateExperience;
    private final int cooldownSeconds;
    private final Set<String> blockedRegionKinds;
    private final BiFunction<Player, Location, Location> returnTo;

    private VisitPolicy(Builder b) {
        allowedCommands = Set.copyOf(b.allowedCommands);
        toolKit = b.toolKit;
        gameMode = b.gameMode;
        invulnerable = b.invulnerable;collidable = b.collidable;clearEffects = b.clearEffects;fullFood = b.fullFood;
        lockInventory = b.lockInventory;portalExits = b.portalExits;
        isolateExperience = b.isolateExperience;
        cooldownSeconds = Math.max(0, b.cooldownSeconds);
        blockedRegionKinds = Set.copyOf(b.blockedRegionKinds);
        returnTo = b.returnTo;
    }

    public static Builder builder() { return new Builder(); }

    /** Erlaubte Befehls-Labels (klein, ohne {@code /}). Leer = keine Befehle. */
    public Set<String> allowedCommands() { return allowedCommands; }
    public ToolKit toolKit() { return toolKit; }
    public GameMode gameMode() { return gameMode; }
    public boolean invulnerable() { return invulnerable; }
    public boolean collidable() { return collidable; }
    /** Effekte beim Betreten entfernen (sie laufen nicht weiter und kommen mit der Restdauer vom Eintritt zurück). */
    public boolean clearEffects() { return clearEffects; }
    public boolean fullFood() { return fullFood; }
    public boolean lockInventory() { return lockInventory; }
    /** Portale in der Welt beenden den Besuch ({@link ExitReason#PORTAL}). */
    public boolean portalExits() { return portalExits; }
    /** Save and restore XP instead of carrying visit XP into the original world. @since 0.32.0 */
    public boolean isolateExperience() { return isolateExperience; }
    public int cooldownSeconds() { return cooldownSeconds; }
    /** Regionsarten, in denen {@code enter} abgelehnt wird; scheitert die Regionsabfrage, wird ebenfalls abgelehnt. */
    public Set<String> blockedRegionKinds() { return blockedRegionKinds; }

    /** Rückkehrort aus dem Eintrittsort; Standard: der Eintrittsort selbst. */
    public Location returnLocation(Player player, Location origin) {
        Location result = returnTo.apply(player, origin);
        return result == null ? origin : result;
    }

    public static final class Builder {
        private final Set<String> allowedCommands = new LinkedHashSet<>();
        private ToolKit toolKit = ToolKit.NONE;
        private GameMode gameMode = GameMode.ADVENTURE;
        private boolean invulnerable = true, collidable = false, clearEffects = true, fullFood = true, lockInventory = true, portalExits = true;
        private boolean isolateExperience;
        private int cooldownSeconds = 5;
        private final Set<String> blockedRegionKinds = new LinkedHashSet<>();
        private BiFunction<Player, Location, Location> returnTo = (player, origin) -> origin;

        private Builder() {}

        /** Fügt erlaubte Befehle hinzu ({@code "talente"}, {@code "/msg"} …; Groß/klein egal). */
        public Builder allowCommands(Iterable<String> labels) {
            for (String label : labels) {
                if (label == null) continue;
                String clean = label.strip().toLowerCase(Locale.ROOT);
                if (clean.startsWith("/")) clean = clean.substring(1);
                if (!clean.isEmpty() && !clean.contains(" ")) allowedCommands.add(clean);
            }
            return this;
        }

        public Builder allowCommands(String... labels) { return allowCommands(java.util.Arrays.asList(labels)); }
        public Builder toolKit(ToolKit value) { toolKit = Objects.requireNonNull(value, "toolKit");return this; }
        public Builder gameMode(GameMode value) { gameMode = Objects.requireNonNull(value, "gameMode");return this; }
        public Builder invulnerable(boolean value) { invulnerable = value;return this; }
        public Builder collidable(boolean value) { collidable = value;return this; }
        public Builder clearEffects(boolean value) { clearEffects = value;return this; }
        public Builder fullFood(boolean value) { fullFood = value;return this; }
        public Builder lockInventory(boolean value) { lockInventory = value;return this; }
        public Builder portalExits(boolean value) { portalExits = value;return this; }
        public Builder isolateExperience(boolean value) { isolateExperience = value; return this; }
        public Builder cooldownSeconds(int value) { cooldownSeconds = value;return this; }

        public Builder blockRegionKinds(Iterable<String> kinds) {
            for (String kind : kinds) if (kind != null && !kind.isBlank()) blockedRegionKinds.add(kind.strip().toLowerCase(Locale.ROOT));
            return this;
        }

        public Builder returnTo(BiFunction<Player, Location, Location> value) { returnTo = Objects.requireNonNull(value, "returnTo");return this; }

        public VisitPolicy build() { return new VisitPolicy(this); }
    }
}
