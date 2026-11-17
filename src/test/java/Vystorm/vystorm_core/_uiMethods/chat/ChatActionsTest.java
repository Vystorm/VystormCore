package Vystorm.vystorm_core._uiMethods.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ChatActionsTest {
    private static Player player(boolean permitted) {
        Player player = mock(Player.class);
        when(player.hasPermission("test.node")).thenReturn(permitted);
        return player;
    }

    @Test
    void givenPermission_whenClickedWithoutIt_thenActionDoesNotRun() {
        AtomicInteger runs = new AtomicInteger();
        Consumer<Player> guarded = ChatActions.guarded("test.node", false, p -> runs.incrementAndGet());
        Player denied = player(false);
        guarded.accept(denied);
        assertEquals(0, runs.get());
        verify(denied).sendMessage(any(Component.class));
        guarded.accept(player(true));
        guarded.accept(player(true));
        assertEquals(2, runs.get());
    }

    @Test
    void givenOnceButton_whenClickedRepeatedly_thenActionRunsOnlyOnce() {
        AtomicInteger runs = new AtomicInteger();
        Consumer<Player> guarded = ChatActions.guarded(null, true, p -> runs.incrementAndGet());
        Player first = player(true), second = player(true);
        guarded.accept(first);
        guarded.accept(first);
        guarded.accept(second);
        assertEquals(1, runs.get());
        verify(second).sendMessage(any(Component.class));
    }

    @Test
    void givenOnceButtonWithPermission_whenDeniedPlayerClicksFirst_thenUseIsNotConsumed() {
        AtomicInteger runs = new AtomicInteger();
        Consumer<Player> guarded = ChatActions.guarded("test.node", true, p -> runs.incrementAndGet());
        guarded.accept(player(false));
        guarded.accept(player(true));
        assertEquals(1, runs.get());
    }
}
