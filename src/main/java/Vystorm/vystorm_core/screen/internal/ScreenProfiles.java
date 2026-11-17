package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core.screen.ScreenProfile;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Stores each player's {@link ScreenProfile} in Core's player preferences ({@code screen.aspect|fov|scale}, since 0.57.0). */
public final class ScreenProfiles {
    static final String ASPECT = "screen.aspect", FOV = "screen.fov", SCALE = "screen.scale";

    private ScreenProfiles() { }

    private static Plugin core() { return JavaPlugin.getProvidingPlugin(ScreenProfiles.class); }

    public static ScreenProfile load(Player player) {
        Plugin core = core();
        ScreenProfile d = ScreenProfile.DEFAULT;
        return new ScreenProfile(PlayerPrefs.number(core, player, ASPECT, d.aspect()),
                (int) Math.round(PlayerPrefs.number(core, player, FOV, d.fov())),
                PlayerPrefs.number(core, player, SCALE, d.scale()));
    }

    public static void save(Player player, ScreenProfile profile) {
        Plugin core = core();
        PlayerPrefs.setNumber(core, player, ASPECT, profile.aspect());
        PlayerPrefs.setNumber(core, player, FOV, profile.fov());
        PlayerPrefs.setNumber(core, player, SCALE, profile.scale());
    }
}
