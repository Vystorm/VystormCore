package Vystorm.vystorm_core.worlds;

import Vystorm.vystorm_core.worlds.internal.WorldsPlatform;
import java.util.List;
import java.util.Optional;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Plugin-Welten (Core-Modul „Plugin-Welten“): eigene Welten mit Generator, deterministischem Neuaufbau bei geändertem
 * Layout-Hash, Spielregeln, Weltprofil, Schutz-Region, Besuchen mit Zustandssicherung und Sichtbarkeit.
 *
 * <pre>{@code
 * WorldSpec spec = WorldSpec.builder("myplugin:arena", "myplugin_arena", generator)
 *         .layoutHash(hash).gameRule(GameRules.ADVANCE_TIME, false).time(6000).protectAll()
 *         .visibility(Visibility.PARTY)
 *         .policy(VisitPolicy.builder().allowCommands("arena", "msg").toolKit(p -> Map.of(8, exitItem)).build())
 *         .hooks(myHooks).build();
 * ManagedWorld world = ManagedWorlds.register(this, spec);          // onEnable
 * ManagedWorlds.visits().enter(player, "myplugin:arena", arrival);  // Besuch
 * ManagedWorlds.release(this);                                      // onDisable (sonst CoreLifecycle)
 * }</pre>
 *
 * <p>Registrieren, Neuaufbauen und Entladen nur auf dem Server-Thread (sonst {@link ManagedWorldException}); {@link #get},
 * {@link #of} und {@link #managed} sind threadsicher. Vor {@code onEnable} von Core und nach {@code onDisable} werfen
 * alle Methoden {@link ManagedWorldException} ({@code ILLEGAL_CALL}); {@link #managed} liefert dann {@code false}.</p>
 *
 * <p>Preferred access: {@code api.worldGenerator().worlds()}.
 *
 * @since 0.26.0 (Capability {@code managed-worlds})
 */
public final class ManagedWorlds {
    private ManagedWorlds() {}

    /** Lädt oder legt die Welt an; baut sie neu auf, wenn der Layout-Hash abweicht. */
    public static ManagedWorld register(Plugin owner, WorldSpec spec) { return WorldsPlatform.get().registry().register(owner, spec); }

    /** Alle Besucher hinaus ({@link ExitReason#REBUILD}), Daten löschen, neu laden (z. B. nach geändertem Inhalt). */
    public static ManagedWorld rebuild(Plugin owner, WorldSpec spec) { return WorldsPlatform.get().registry().rebuild(owner, spec); }

    /**
     * Neue Einstellungen ohne Neuaufbau (z. B. nach {@code /reload} des Plugins): gleicher Weltname und Layout-Hash
     * Pflicht. Laufende Besuche bleiben; Befehlsliste, Werkzeuge, Sichtbarkeit und Hooks gelten sofort.
     */
    public static ManagedWorld reconfigure(Plugin owner, WorldSpec spec) {
        ManagedWorld world = WorldsPlatform.get().registry().reconfigure(owner, spec);
        WorldsPlatform.get().visits().refreshVisibility(spec.id());
        return world;
    }

    /** Besuche beenden ({@link ExitReason#SHUTDOWN}) und Welt entladen; {@code false}, wenn sie nicht registriert ist. */
    public static boolean unregister(Plugin owner, String id) { return WorldsPlatform.get().registry().unregister(owner, id); }

    /** Alle Welten des Plugins entladen (im eigenen {@code onDisable} rufen; Core tut es sonst beim Plugin-Disable). */
    public static void release(Plugin owner) {
        try { WorldsPlatform.get().registry().release(owner); } catch (ManagedWorldException coreStopped) { /* Core fährt schon herunter */ }
    }

    public static Optional<ManagedWorld> get(String id) { return Optional.ofNullable(WorldsPlatform.get().registry().byId(id)); }

    public static Optional<ManagedWorld> of(World world) { return Optional.ofNullable(WorldsPlatform.get().registry().byWorld(world)); }

    public static boolean managed(World world) {
        try { return WorldsPlatform.get().registry().byWorld(world) != null; } catch (ManagedWorldException coreStopped) { return false; }
    }

    public static List<ManagedWorld> all() { return List.copyOf(WorldsPlatform.get().registry().all()); }

    public static VisitService visits() { return WorldsPlatform.get().visits(); }
}
