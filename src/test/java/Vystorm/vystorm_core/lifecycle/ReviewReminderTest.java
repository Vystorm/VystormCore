package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewReminderTest {
    private static final long DAY = ReviewReminder.DAY;

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }

    @Test void asksOncePerPluginAfterTheWaitingTime(@TempDir Path folder) {
        JavaPlugin core = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        when(core.getName()).thenReturn("vystorm_core");
        when(core.getDataFolder()).thenReturn(folder.toFile());
        when(core.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        Plugin trees = plugin("CustomTrees"), rails = plugin("ReviewTestRails");
        when(plugins.getPlugin(anyString())).thenReturn(null);
        when(plugins.getPlugin("vystorm_core")).thenReturn(core);
        when(plugins.getPlugin("CustomTrees")).thenReturn(trees);
        when(plugins.getPlugin("ReviewTestRails")).thenReturn(rails);
        UpdateSources.spigot(rails, 777);
        try {
            ReviewReminder reminder = new ReviewReminder(core, 7 * DAY);
            reminder.note(0);
            assertTrue(reminder.due(6 * DAY).isEmpty());
            var due = reminder.due(7 * DAY);
            assertEquals(List.of("vystorm_core", "CustomTrees", "ReviewTestRails"), List.copyOf(due.keySet()));
            assertEquals(777, due.get("ReviewTestRails"));
            reminder.asked(List.of("vystorm_core", "CustomTrees"));
            assertEquals(List.of("ReviewTestRails"), List.copyOf(reminder.due(8 * DAY).keySet()));

            // State survives a restart; a plugin installed later waits its own week.
            Plugin later = plugin("ReviewTestLater");
            when(plugins.getPlugin("ReviewTestLater")).thenReturn(later);
            UpdateSources.spigot(later, 778);
            ReviewReminder restarted = new ReviewReminder(core, 7 * DAY);
            restarted.note(3 * DAY);
            assertEquals(List.of("ReviewTestRails"), List.copyOf(restarted.due(9 * DAY).keySet()));
            assertTrue(restarted.due(10 * DAY).containsKey("ReviewTestLater"));
            UpdateSources.unregister(later);
        } finally {
            UpdateSources.unregister(rails);
        }
        assertEquals("https://www.spigotmc.org/resources/139848/reviews", ReviewReminder.reviewUrl(139848));
    }
}
