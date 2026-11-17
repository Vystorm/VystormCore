package Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types;

import java.time.LocalDateTime;
import org.bukkit.Bukkit;

/** Routine-Details (standardmäßig nicht in der Konsole, immer in der Debug-Datei). */
public class Low {
    public void debug(String message, LocalDateTime now) {
        java.util.logging.Logger.getLogger("Minecraft").info("[LOW] " + message);
    }
}
