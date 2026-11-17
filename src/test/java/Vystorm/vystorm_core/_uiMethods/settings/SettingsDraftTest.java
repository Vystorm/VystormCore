package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class SettingsDraftTest {
    @Test void givenChangedValueOrPermission_whenApplying_thenNothingWritten() {
        try (var b = mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            Plugin owner = mock(Plugin.class); when(owner.getName()).thenReturn("Example"); when(owner.isEnabled()).thenReturn(true);
            Player player = mock(Player.class); when(player.hasPermission("example.admin")).thenReturn(true);
            AtomicInteger value = new AtomicInteger(1), saved = new AtomicInteger();
            SettingsRegistry registry = new SettingsRegistry(owner, () -> null, p -> {});
            registry.register(owner, SettingsSection.server("main", "Main", "", "example.admin", List.of(
                    Setting.number("n", "N", "", 0, 20, 1, p -> (double) value.get(), (p, n) -> { value.set(n.intValue()); return null; })), p -> saved.incrementAndGet()));
            SettingsDraft draft = new SettingsDraft(); draft.stage("example:main", "n", 1, 5d);
            value.set(2);
            assertThrows(IllegalStateException.class, () -> draft.apply(registry, player));
            assertEquals(2, value.get()); assertEquals(0, saved.get());
            value.set(1); when(player.hasPermission("example.admin")).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> draft.apply(registry, player));
            when(player.hasPermission("example.admin")).thenReturn(true);
            assertEquals(1, draft.apply(registry, player).getFirst().changed());
            assertEquals(5, value.get()); assertEquals(1, saved.get());
            assertTrue(draft.apply(registry, player).isEmpty(), "a consumed draft must not invoke setters twice");
        }
    }
    @Test void givenRepeatedStaging_whenReturningToBaseline_thenRemovesChange() {
        SettingsDraft draft = new SettingsDraft(); draft.stage("a:b", "n", 1, 2);
        draft.stage("a:b", "n", 99, 3);
        assertEquals(1, draft.changes().getFirst().before());
        draft.stage("a:b", "n", 99, 1d); assertTrue(draft.changes().isEmpty());
    }
}
