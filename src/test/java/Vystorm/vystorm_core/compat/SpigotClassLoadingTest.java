package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Uses the genuine Spigot API in an isolated classloader: no Paper classes or fake API stubs. */
class SpigotClassLoadingTest {
    @Test void givenOnlySpigotApiWhenCommonListenersAndPublicServicesReflectThenNoPaperTypesAreRequired()throws Exception {
        ArrayList<URL> urls=new ArrayList<>();
        urls.add(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class.getProtectionDomain().getCodeSource().getLocation());
        for(String path:test.TestClasspath.property("vystorm.test.spigotClasspath").split(File.pathSeparator))urls.add(Path.of(path).toUri().toURL());
        for(String path:test.TestClasspath.property("vystorm.test.classpath").split(File.pathSeparator))
            if(!Path.of(path).getFileName().toString().startsWith("paper-api-"))urls.add(Path.of(path).toUri().toURL());
        try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class,()->loader.loadClass("io.papermc.paper.event.player.PlayerInventorySlotChangeEvent"));
            Class<?> player=loader.loadClass("org.bukkit.entity.Player");
            assertFalse(loader.loadClass("net.kyori.adventure.audience.Audience").isAssignableFrom(player));
            for(String name:new String[]{"main","API","compat.PlatformCompat","compat.ClientEvents","compat.ClickActions",
                    "compat.GameplayCompat","compat.MobNavigation","compat.StorageCompat","compat.WorldCompat",
                    "bundleclasses.baseListener.BukkitInventoryEventListener","worlds.internal.WorldsListener",
                    "display.ClientDisplays","packet.ClientBiomes","packet.ClientChunks","visual.Visuals"})
                assertDoesNotThrow(()->loader.loadClass("Vystorm.vystorm_core."+name).getDeclaredMethods(),name);
            assertEquals(false,loader.loadClass("Vystorm.vystorm_core.compat.ServerCompat").getMethod("hasDialogs").invoke(null));
        }
    }
}
