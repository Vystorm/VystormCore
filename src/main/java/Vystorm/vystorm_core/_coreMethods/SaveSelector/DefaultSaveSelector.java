package Vystorm.vystorm_core._coreMethods.SaveSelector;

import org.bukkit.plugin.Plugin;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
@SuppressWarnings("removal")
public class DefaultSaveSelector implements SaveSelectorInterface {
    public static final String DEFAULT_FORMAT = "file";

    @Override
    public void refresh(Plugin plugin) {
    }

    @Override
    public String getFormat(Plugin plugin, String folder, String fileName) {
        return DEFAULT_FORMAT;
    }

    @Override
    public void editFormat(Plugin plugin, String folder, String fileName, String newFormat) {
    }
}
