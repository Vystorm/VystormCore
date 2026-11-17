package Vystorm.vystorm_core._uiMethods.notifications;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.schedule.TaskGroup;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Manager view of {@link RoutineMessages}, reached through {@code api.message().routine()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link RoutineMessages}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface RoutineMessagesInterface {
    /** The delegating default. Plugins use {@code api.message().routine()}. */
    RoutineMessagesInterface DEFAULT = new RoutineMessagesInterface() {};

    /** Remove all pending messages supplied by a plugin being released. */
    default void release(Plugin provider) { RoutineMessages.release(provider); }

    /** Send a routine-only notification. Must be called from the primary server thread. */
    default void send(Plugin provider, Player viewer, Component routineMessage) { RoutineMessages.send(provider, viewer, routineMessage); }
}
