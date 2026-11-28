package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PickFlow;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PlayerPick;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** One running player pick: the pick, where it returns to, the model and how it is shown. Server thread. Core-internal. */
public final class PlayerPickFlow implements PickFlow {
    /** How the picker is shown. */
    public enum Route { NONE, NATIVE, SCREEN, CHEST }

    final Plugin owner;
    final Player viewer;
    final PlayerPick pick;
    final PickReturn back;
    final PickerModel model;
    /** The panel argument of this flow; a render or a close with another argument belongs to an older flow. */
    final String token;
    final long startedAt;
    final Consumer<PlayerPickFlow> canceller;
    long lastActivity;
    Route route = Route.NONE;
    PickerChest chest;
    boolean ended;
    PickEnd end;
    /** The message of the last refused hand-over (chest info line). */
    Object error;
    /** onPick runs: a new pick started from it ends this flow as DONE. */
    boolean handingOver;
    /** Client-mod session id of the picker panel (0 = none yet). */
    int nativeSession;
    /** Last teleport while picking, epoch ms (Spigot chest closes carry no reason). */
    long teleportedAt;
    /** The chest's search prompt is open. */
    boolean prompting;

    PlayerPickFlow(Plugin owner, Player viewer, PlayerPick pick, PickReturn back, PickerModel model, String token, long now,
                   Consumer<PlayerPickFlow> canceller) {
        this.owner = owner;
        this.viewer = viewer;
        this.pick = pick;
        this.back = back;
        this.model = model;
        this.token = token;
        this.startedAt = now;
        this.lastActivity = now;
        this.canceller = canceller;
    }

    @Override public Plugin owner() { return owner; }
    @Override public Player viewer() { return viewer; }
    @Override public PickKind kind() { return PickKind.PLAYERS; }
    @Override public long startedAt() { return startedAt; }
    @Override public boolean active() { return !ended; }

    @Override public boolean cancel() {
        if (ended) return false;
        canceller.accept(this);
        return true;
    }

    public PickerModel model() { return model; }
    public PlayerPick pick() { return pick; }
    public Route route() { return route; }
    public PickEnd end() { return end; }
}
