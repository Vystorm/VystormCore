package Vystorm.vystorm_core._coreMethods.playerHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.playerHandler.playerData.PlayerData;
import Vystorm.vystorm_core._coreMethods.playerHandler.standardSettings.Settings;
import Vystorm.vystorm_core._coreMethods.playerHandler.standardSettings.SettingsData;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.main;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Spielerdaten des Legacy-Layers. Daten werden beim Join geladen und beim Quit bzw. im Core-{@code onDisable}
 * gespeichert. Zugriffe auf Spieler, deren Daten (z. B. nach einem Reload) noch nicht geladen sind, laden sie nach,
 * statt mit einer NullPointerException abzubrechen.
 */
public class DefaultPlayerHandler implements PlayerHandlerInterface {
Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
Map<Plugin, Settings> defaultSettings = new ConcurrentHashMap<>();
Map<Plugin, SettingsData> defaultDataMaps = new ConcurrentHashMap<>();

private PlayerData data(UUID playerUUID) {
     PlayerData data = playerDataMap.get(playerUUID);
     if (data != null) return data;
     loadPlayer(playerUUID);
     return playerDataMap.get(playerUUID);
}

@Override
@SuppressWarnings("deprecation")
public String getSetting(UUID playerUUID, String setting) {
     return data(playerUUID).settings.getString(setting);
}

@Override
@SuppressWarnings("deprecation")
public void setSetting(UUID playerUUID, String setting, String value) {
     data(playerUUID).settings.set(setting, value);
}

@Override
public DataEntry getPlayerDataEntry(UUID playerUUID, String Type) {
     return data(playerUUID).dataEntries.get(Type);
}

@Override
public void savePlayerDataEntry(UUID playerUUID, String Type, DataEntry entry) {
     data(playerUUID).dataEntries.put(Type, entry);
}

@Override
public String[] getPlayerData(UUID playerUUID, String Type) {
     return data(playerUUID).temporary.get(Type);
}

@Override
public void setPlayerData(UUID playerUUID, String Type, String[] data) {
     data(playerUUID).temporary.put(Type, data);
}

/** Ob Daten für den Spieler geladen sind. */
public boolean isLoaded(UUID playerUUID) { return playerDataMap.containsKey(playerUUID); }

@Override
public void loadPlayer(UUID playerUUID) {
     DebugMessages.low("[DefaultPlayerHandler] Loading player data; uuid=" + playerUUID);
     PlayerData data = new PlayerData();
     Plugin plugin = main.getPlugin();
     try {
          Path playerFolder = plugin.getDataFolder().toPath().resolve("playerData").resolve(playerUUID.toString());
          Files.createDirectories(playerFolder.resolve("dataEntries"));
          Path settingsFile = playerFolder.resolve("settings.yml");
          if (!Files.exists(settingsFile)) {
              Path defaults = plugin.getDataFolder().toPath().resolve("playerData/defaultSettings.yml");
              if (!Files.exists(defaults)) {
                  main.getApi().ressource().load(new ArrayList<>(List.of("playerData/defaultSettings.yml")), plugin);
              }
              // Player settings have their own lifecycle; do not register a stale copy with saveAll().
              if (Files.exists(defaults)) Files.copy(defaults, settingsFile);
          }
          if (Files.exists(settingsFile)) data.settings.load(settingsFile.toFile());
	  defaultSettings.values().forEach(settings -> {
	       settings.autoAddSettings(data.settings);
	  });
     } catch (Exception e) {
	  DebugMessages.high("[DefaultPlayerHandler] Player data IO failed; uuid=" + playerUUID + "; failure=" + e.getClass().getSimpleName());
          throw new RuntimeException(e);
     }
     playerDataMap.put(playerUUID, data);
     DebugMessages.mid("[DefaultPlayerHandler] Player data loaded; uuid=" + playerUUID);
}

/**
 * Speichert Einstellungen und Daten-Einträge atomar und entlädt den Spieler. Nicht geladene Spieler werden ignoriert.
 * Scheitert ein einzelner Eintrag, werden die übrigen trotzdem gespeichert; danach wird der erste Fehler geworfen.
 */
@Override
public void unloadPlayer(UUID playerUUID) throws IOException {
     PlayerData data = playerDataMap.get(playerUUID);
     if (data == null) return;
     Path folder = main.getPlugin().getDataFolder().toPath().resolve("playerData").resolve(playerUUID.toString());
     IOException first = null;
     try {
          AtomicFiles.write(folder.resolve("settings.yml"), data.settings);
     } catch (IOException e) {
          DebugMessages.high("[DefaultPlayerHandler] Player settings IO failed; uuid=" + playerUUID, e);
          first = e;
     }
     for (DataEntry dataEntry : data.dataEntries.values()) {
          if (dataEntry == null || dataEntry.getEntry() == null) continue;
          try {
               AtomicFiles.write(folder.resolve("dataEntries").resolve(dataEntry.getName()), dataEntry.getEntry());
          } catch (IOException | RuntimeException e) {
               DebugMessages.high("[DefaultPlayerHandler] Player data IO failed; uuid=" + playerUUID + "; entry=" + dataEntry.getName(), e);
               if (first == null) first = e instanceof IOException io ? io : new IOException(e);
          }
     }
     playerDataMap.remove(playerUUID);
     if (first != null) throw first;
     DebugMessages.mid("[DefaultPlayerHandler] Player data saved and unloaded; uuid=" + playerUUID);
}

@Override
@SuppressWarnings("deprecation")
public void addStandardSettings(Plugin plugin, Settings settings) {
     defaultSettings.put(plugin, settings);
     DebugMessages.mid("[DefaultPlayerHandler] Default settings registered for " + plugin.getName());
}

@Override
public void addStandardData(Plugin plugin, SettingsData settingsData) {
     defaultDataMaps.put(plugin, settingsData);
     DebugMessages.mid("[DefaultPlayerHandler] Default data registered for " + plugin.getName());

}
}
