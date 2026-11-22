package Vystorm.vystorm_core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The manager parts need no server class that their static twin does not need: on the genuine Spigot API and on
 * the Paper 1.20.6 libraries, every part whose static class loads there also loads, links (its default methods are
 * verified when {@code DEFAULT} is created) and reflects.
 */
class ManagerLoadingTest {
    private static final List<String> MANAGERS = List.of("API", "compat.CompatInterface", "loot.LootInterface", "web.WebInterface",
            "item.ItemsInterface", "mob.MobsInterface", "runtime.RuntimeInterface", "singeltons.ManagerHolder",
            "_coreMethods.eventRegulator.EventStage", "_coreMethods.eventRegulator.EventStages",
            "_coreMethods.eventRegulator.PriorityInterface", "_advancedMethods.placeholderHandler.PlaceholderInterface",
            "_advancedMethods.placeholderHandler.PlaceholderHandler", "_advancedMethods.placeholderHandler.PlaceholderTesting",
            "_advancedMethods.placeholderHandler.PlaceholderValue", "_advancedMethods.placeholderHandler.PlaceholderContext",
            "_advancedMethods.placeholderHandler.PlaceholderBridge", "_advancedMethods.placeholderHandler.internal.PlaceholderApiBridge",
            "_advancedMethods.placeholderHandler.internal.CorePlaceholders", "_advancedMethods.placeholderHandler.internal.PlaceholdersAdmin",
            "_advancedMethods.placeholderHandler.internal.PlaceholderRuntime",
            "_advancedMethods.placeholderHandler.RankingInfo", "_advancedMethods.placeholderHandler.RankingInfo$Format",
            "_advancedMethods.placeholderHandler.RankingSource", "_advancedMethods.placeholderHandler.RankingSource$Row",
            "_advancedMethods.placeholderHandler.PlaceholderRanking");

    private static void add(List<URL> urls, Class<?> anchor) {
        urls.add(anchor.getProtectionDomain().getCodeSource().getLocation());
    }

    private static void check(ClassLoader loader, int atLeast) throws Exception {
        List<String> skipped = new ArrayList<>();
        int checked = 0;
        for (ManagerParts.Part part : ManagerParts.ALL) {
            try {
                loader.loadClass(part.statics().getName()).getDeclaredMethods();
            } catch (ClassNotFoundException | LinkageError staticClassNeedsMore) {
                skipped.add(part.statics().getSimpleName());
                continue;
            }
            Class<?> manager = assertDoesNotThrow(() -> loader.loadClass(part.manager().getName()), part.access());
            assertTrue(manager.getClassLoader() == loader, "loaded in isolation");
            assertDoesNotThrow(manager::getDeclaredMethods, part.access());
            assertNotNull(assertDoesNotThrow(() -> manager.getField("DEFAULT").get(null), part.access()));
            checked++;
        }
        for (String name : MANAGERS) assertDoesNotThrow(() -> loader.loadClass("Vystorm.vystorm_core." + name).getDeclaredMethods(), name);
        assertTrue(checked >= atLeast, "only " + checked + " parts could be checked; static classes that do not load here: " + skipped);
    }

    @Test
    void givenOnlySpigotApi_whenEveryManagerPartIsLinked_thenNothingBeyondItsStaticClassIsNeeded() throws Exception {
        List<URL> urls = new ArrayList<>();
        add(urls, Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class);
        for (String path : test.TestClasspath.property("vystorm.test.spigotClasspath").split(File.pathSeparator)) urls.add(Path.of(path).toUri().toURL());
        for (String path : test.TestClasspath.property("vystorm.test.classpath").split(File.pathSeparator))
            if (!Path.of(path).getFileName().toString().startsWith("paper-api-")) urls.add(Path.of(path).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("io.papermc.paper.event.player.PlayerInventorySlotChangeEvent"));
            check(loader, ManagerParts.ALL.size());
        }
    }

    @Test
    void givenPaper1206Libraries_whenEveryManagerPartIsLinked_thenNothingBeyondItsStaticClassIsNeeded() throws Exception {
        List<URL> urls = new ArrayList<>();
        add(urls, CoreApi.class);
        add(urls, Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class);
        for (String path : test.TestClasspath.property("vystorm.test.baselineClasspath").split(File.pathSeparator)) urls.add(Path.of(path).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("io.papermc.paper.dialog.Dialog"));
            check(loader, ManagerParts.ALL.size());
        }
    }
}
