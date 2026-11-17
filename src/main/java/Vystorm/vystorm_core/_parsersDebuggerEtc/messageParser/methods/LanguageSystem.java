package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core.main;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprachdateien des Legacy-Nachrichtensystems. Standard ist {@code plugins/vystorm_core/language/language.yml}
 * (wird beim ersten Zugriff aus dem Jar angelegt; {@code languages/language.yml} wird als Altpfad weiter gelesen).
 * Weitere Sprachen liegen unter {@code languages/<sprache>/messages.yml}. Schlüssel werden direkt und unter
 * {@code default_language.<key>} gesucht. Fehlende Texte liefern {@code null}.
 */
public class LanguageSystem {
static final String RESOURCE = "language/language.yml";
static final String LEGACY = "languages/language.yml";
static final String DEFAULT_SECTION = "default_language.";
Map<String, DataEntry> messages = new ConcurrentHashMap<>();

public LanguageSystem() {
     DataEntry defaults = loadDefault();
     if (defaults != null) messages.put("default", defaults);
     else DebugMessages.high("[LanguageSystem] Default language file missing: " + RESOURCE);
}

@SuppressWarnings("deprecation")
private static DataEntry loadDefault() {
     Plugin core = main.getPlugin();
     if (core == null) return null;
     try {
          if (new File(core.getDataFolder(), LEGACY).isFile())
               return main.getApi().data().loader().loadSpecificEntryExtern(Path.of(LEGACY), core);
          if (!new File(core.getDataFolder(), RESOURCE).isFile() && core.getResource(RESOURCE) != null) core.saveResource(RESOURCE, false);
          return main.getApi().data().loader().loadSpecificEntryExtern(Path.of(RESOURCE), core);
     } catch (RuntimeException failure) {
          DebugMessages.high("[LanguageSystem] Default language file unreadable", failure);
          return null;
     }
}

/** Sucht den Text in einer Sprachdatei (direkter Schlüssel, dann {@code default_language.<key>}). */
static String lookup(DataEntry entry, String messageKey) {
     if (entry == null || messageKey == null) return null;
     FileConfiguration config = entry.getEntry();
     if (config == null) return null;
     String direct = config.getString(messageKey);
     if (direct != null && !direct.isEmpty()) return direct;
     String sectioned = config.getString(DEFAULT_SECTION + messageKey);
     return sectioned == null || sectioned.isEmpty() ? null : sectioned;
}

/**
 * @param messageKey saved under languages/<language>/messages.yml under the concrete plugin in its own folder every followuplangauge has to be added in messages.yml in language.yml under point createdLanguages
 * @param player
 * @return der Text in der Sprache des Spielers, sonst in der Standardsprache, sonst {@code null}
 */
@SuppressWarnings("deprecation")
public String handleMessage(String messageKey, Player player) {
     DebugMessages.low("[LanguageSystem] Resolving localized message; key=" + messageKey);
     String language = null;
     try { language = main.getApi().player().getSetting(player.getUniqueId(), "language"); }
     catch (RuntimeException failure) { DebugMessages.low("[LanguageSystem] No language setting; uuid=" + player.getUniqueId()); }
     if (language == null || language.isEmpty()) {
	  language = player.getLocale();
	  if (language == null || language.isEmpty()) {
	       language = "default";
               DebugMessages.low("[LanguageSystem] Falling back to default locale");
	  }
     }

     if (!language.equals("default") && !messages.containsKey(language)) {
	  DebugMessages.low("[LanguageSystem] Loading locale messages: " + language);
          try {
               DataEntry loaded = main.getApi().data().loader().loadSpecificEntryExtern(Path.of("languages/" + language + "/messages.yml"), main.getPlugin());
               if (loaded != null) messages.put(language, loaded);
          } catch (RuntimeException failure) {
               DebugMessages.high("[LanguageSystem] Locale messages unreadable: " + language, failure);
          }
     }
     String msg = lookup(messages.get(language), messageKey);
     if (msg == null) msg = lookup(messages.get("default"), messageKey);
     if (msg == null) DebugMessages.high("[LanguageSystem] " + messageKey + " in language " + language + " not found");
     return msg;
}
}
