package Vystorm.vystorm_core._coreMethods.SaveSelector;

import org.bukkit.plugin.Plugin;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public interface SaveSelectorInterface {
    public void refresh(Plugin plugin);

    public String getFormat(Plugin plugin, String folder, String fileName);

    public void editFormat(Plugin plugin, String folder, String fileName, String newFormat);
}
