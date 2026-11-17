package Vystorm.vystorm_core._coreMethods.playerHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._coreMethods.playerHandler.standardSettings.Settings;
import Vystorm.vystorm_core._coreMethods.playerHandler.standardSettings.SettingsData;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;
import java.util.Objects;

import java.io.IOException;
import java.util.UUID;

public interface PlayerHandlerInterface {
	    /** @deprecated Use {@code api.settings().prefs().text(owner, player, id, fallback)}. */
	    @Deprecated(since = "0.64.0")
	    public String getSetting(UUID playerUUID, String setting);
	    /** @deprecated Use {@code api.settings().prefs().setText(owner, player, id, value)}. */
	    @Deprecated(since = "0.64.0")
	    public void setSetting(UUID playerUUID, String setting, String value);
	    public DataEntry getPlayerDataEntry(UUID playerUUID, String Type);
	    public void savePlayerDataEntry(UUID playerUUID, String Type, DataEntry entry);

	    /**
	     * Gives you the temporary data of a player that's saved as a string array
	     * @param playerUUID
	     * @param Type
	     * @return
	     */
	    public String[] getPlayerData(UUID playerUUID, String Type);
	    public void setPlayerData(UUID playerUUID, String Type, String[] data);
	    public void loadPlayer(UUID playerUUID);
	    public void unloadPlayer(UUID playerUUID) throws IOException;
	    /** @deprecated Use {@code api.settings().prefs().toggle(owner, id, label, description, fallback)}. */
	    @Deprecated(since = "0.64.0")
	    public void addStandardSettings(Plugin plugin, Settings settings);
	    public void addStandardData(Plugin plugin,SettingsData settingsData);
    /** Current spendable XP, including progress in the current level. Server thread only. */
    default int experience(Player player) {
        return Vystorm.vystorm_core.compat.PlatformCompat.calculateTotalExperiencePoints(Objects.requireNonNull(player, "player"));
    }

    /** Native XP award, preserving Bukkit's giveExp behavior. Server thread only. */
    default void giveExperience(Player player, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be non-negative");
        Objects.requireNonNull(player, "player").giveExp(amount);
        DebugMessages.low("[PlayerHandlerInterface] Native XP awarded; points=" + amount);
    }

    /** Set current spendable XP, updating level and progress together. Server thread only. */
    default void setExperience(Player player, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be non-negative");
        Vystorm.vystorm_core.compat.PlatformCompat.setExperienceLevelAndProgress(Objects.requireNonNull(player, "player"), amount);
        DebugMessages.low("[PlayerHandlerInterface] XP balance set; points=" + amount);
    }

    /** Insufficient funds leave XP unchanged. */
    default boolean withdrawExperience(Player player, int amount) {
        if (amount < 0) {
            DebugMessages.high("[PlayerHandlerInterface] Rejected negative XP amount");
            throw new IllegalArgumentException("Amount must be non-negative");
        }
        int current = experience(player);
        if (current < amount) {
            DebugMessages.mid("[PlayerHandlerInterface] XP withdrawal rejected: insufficient points");
            return false;
        }
        setExperience(player, current - amount);
        DebugMessages.low("[PlayerHandlerInterface] XP withdrawal completed; points=" + amount);
        return true;
    }

    /** Overflow throws before any XP is changed. */
    default void depositExperience(Player player, int amount) {
        if (amount < 0) {
            DebugMessages.high("[PlayerHandlerInterface] Rejected negative XP amount");
            throw new IllegalArgumentException("Amount must be non-negative");
        }
        int target;
        try {
            target = Math.addExact(experience(player), amount);
        } catch (ArithmeticException failure) {
            DebugMessages.high("[PlayerHandlerInterface] XP deposit rejected: balance overflow");
            throw failure;
        }
        setExperience(player, target);
        DebugMessages.low("[PlayerHandlerInterface] XP deposit completed; points=" + amount);
    }

    /** Character level and character XP ({@code progression.CharacterServices}). @since 0.64.0 */
    default Vystorm.vystorm_core.progression.CharacterServicesInterface character() { return Vystorm.vystorm_core.progression.CharacterServicesInterface.DEFAULT; }

    /** Disciplines: levels, unlocks, XP ({@code progression.DisciplineServices}). @since 0.64.0 */
    default Vystorm.vystorm_core.progression.DisciplineServicesInterface disciplines() { return Vystorm.vystorm_core.progression.DisciplineServicesInterface.DEFAULT; }
}
