package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Localized root aliases of Core command trees count as their English command in visit allow-lists. */
class VisitCommandNameTest {
    private static Command entry(String name, Plugin owner) {
        Command c = mock(Command.class, withSettings().extraInterfaces(PluginIdentifiableCommand.class));
        when(c.getName()).thenReturn(name);
        when(((PluginIdentifiableCommand) c).getPlugin()).thenReturn(owner);
        return c;
    }

    private static CommandRegistration tree(Plugin owner, String label, String... active) {
        CommandRegistration r = mock(CommandRegistration.class);
        when(r.plugin()).thenReturn(owner);
        when(r.label()).thenReturn(label);
        when(r.activeAliases()).thenReturn(List.of(active));
        return r;
    }

    @Test
    void givenLocalizedAlias_whenResolved_thenEnglishLabelAndAllowed() {
        Plugin insomnia = mock(Plugin.class);
        CommandRegistration wake = tree(insomnia, "wake", "aufwachen", "醒来");
        assertEquals("wake", BukkitVisitEnv.primaryName("aufwachen", entry("aufwachen", insomnia), l -> Optional.of(wake)));
        assertEquals("wake", BukkitVisitEnv.primaryName("醒来", entry("醒来", insomnia), l -> Optional.of(wake)));
        assertTrue(CommandAllowList.allowed("/aufwachen", Set.of("wake"),
                l -> BukkitVisitEnv.primaryName(l, entry(l, insomnia), x -> Optional.of(wake))));
    }

    @Test
    void givenSameLabelOfAnotherPlugin_whenResolved_thenItsOwnName() {
        Plugin insomnia = mock(Plugin.class), other = mock(Plugin.class);
        CommandRegistration wake = tree(insomnia, "wake", "aufwachen");
        assertEquals("aufwachen", BukkitVisitEnv.primaryName("aufwachen", entry("aufwachen", other), l -> Optional.of(wake)));
        assertEquals("aufwachen", BukkitVisitEnv.primaryName("aufwachen", entry("aufwachen", insomnia), l -> Optional.of(tree(insomnia, "wake"))),
                "an alias Core did not activate stays unmapped");
        assertEquals("give", BukkitVisitEnv.primaryName("give", entry("give", other), l -> Optional.empty()));
        assertNull(BukkitVisitEnv.primaryName("nope", null, l -> Optional.empty()));
    }
}
