package Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types;

import java.time.LocalDateTime;
import org.bukkit.Bukkit;

/** Normale Betriebsmeldungen (standardmäßig nicht in der Konsole, immer in der Debug-Datei). */
public class Mid {
    public void debug(String message, LocalDateTime date) {
        java.util.logging.Logger.getLogger("Minecraft").info("[MID] " + message);
    }
}
