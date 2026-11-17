package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ServerCompatTest {
    @Test void givenMissingFeatureWhenDetectedThenReturnsFalse() {
        assertFalse(ServerCompat.hasClass("no.such.ServerFeature"));
        assertTrue(ServerCompat.hasClass("java.lang.String"));
        ClassLoader broken = new ClassLoader(null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) { throw new NoClassDefFoundError(name); }
        };
        assertFalse(ServerCompat.hasClass("optional.Feature", broken));
    }

    @Test void givenUnknownMaterialWhenResolvedThenFallbackIsUsed() {
        assertEquals(Material.STONE, ServerCompat.material("minecraft:stone", Material.AIR));
        assertEquals(Material.OAK_LOG, ServerCompat.material("minecraft:unknown_future_wood", Material.OAK_LOG));
        assertNull(ServerCompat.material(null, null));
    }

    @Test void givenOnly1206LibrariesWhenLoadingCoreThenChatFallbackLoadsWithoutNativeDialogClasses() throws Exception {
        ArrayList<URL> urls = new ArrayList<>();
        urls.add(ServerCompat.class.getProtectionDomain().getCodeSource().getLocation());
        urls.add(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class.getProtectionDomain().getCodeSource().getLocation());
        for (String path : test.TestClasspath.property("vystorm.test.baselineClasspath").split(File.pathSeparator))
            urls.add(Path.of(path).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> compat = loader.loadClass(ServerCompat.class.getName());
            assertEquals(false, compat.getMethod("hasDialogs").invoke(null));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("io.papermc.paper.dialog.Dialog"));
            Class<?> singleton = loader.loadClass("Vystorm.vystorm_core.singeltons.DialogSingelton");
            Object wrapper = singleton.getMethod("getInstance").invoke(null);
            // dialogs without inputs open as screens; the wrapper loads without Paper dialog classes (0.59.0)
            assertEquals("Vystorm.vystorm_core.compat.internal.ScreenDialogs", wrapper.getClass().getName());
            Object handler = singleton.getMethod("base").invoke(null);
            assertEquals("Vystorm.vystorm_core.compat.internal.LegacyDialogs", handler.getClass().getName());
            // Bukkit reflects only this baseline listener, never the legacy public native-dialog bridge.
            Object listener = handler.getClass().getField("chat").get(handler);
            assertTrue(listener.getClass().getDeclaredMethods().length > 0);
            Class<?> plugin = loader.loadClass("Vystorm.vystorm_core.main");
            assertTrue(plugin.getDeclaredMethods().length > 0);
            Class<?> api = loader.loadClass("Vystorm.vystorm_core.API");
            assertTrue(api.getDeclaredMethods().length > 0);
            Class<?> inventories = loader.loadClass("Vystorm.vystorm_core.compat.InventoryCompat");
            var access = inventories.getDeclaredField("ACCESS");
            access.setAccessible(true);
            assertTrue(access.get(null).getClass().getName().endsWith("$LegacyAccess"));
            Class<?> json = loader.loadClass("Vystorm.vystorm_core.compat.JsonCompat");
            var reader = json.getMethod("reader", String.class);
            assertNotNull(reader.invoke(null, "{\"value\":true}"));
            for (String invalid : java.util.List.of("{\"v\":TRUE}", "{\"v\":\"raw\nline\"}", "{\"v\":\"\\'\"}", "{/* comment */}"))
                assertThrows(java.lang.reflect.InvocationTargetException.class, () -> reader.invoke(null, invalid));
        }
    }
}
