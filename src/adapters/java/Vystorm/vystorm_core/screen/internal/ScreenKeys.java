package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.screen.ScreenInput;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Movement keys for games; registered only when the server has {@code PlayerInputEvent} (Paper 1.21.3+). */
public final class ScreenKeys implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void input(org.bukkit.event.player.PlayerInputEvent e) {
        ScreenRuntime runtime = ScreenRuntime.get();
        if (!runtime.hasSession(e.getPlayer())) return;
        var now = e.getInput();
        var before = e.getPlayer().getCurrentInput();
        Player p = e.getPlayer();
        if (now.isForward() != before.isForward()) runtime.onKey(p, ScreenInput.Key.FORWARD, now.isForward());
        if (now.isBackward() != before.isBackward()) runtime.onKey(p, ScreenInput.Key.BACK, now.isBackward());
        if (now.isLeft() != before.isLeft()) runtime.onKey(p, ScreenInput.Key.LEFT, now.isLeft());
        if (now.isRight() != before.isRight()) runtime.onKey(p, ScreenInput.Key.RIGHT, now.isRight());
        if (now.isJump() != before.isJump()) runtime.onKey(p, ScreenInput.Key.JUMP, now.isJump());
    }
}
