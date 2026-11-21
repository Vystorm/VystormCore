package Vystorm.vystorm_core.compat.internal;

import static org.mockito.Mockito.*;

import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * {@code dialogs().prompt/confirm} on the screen wrapper must go to the wrapped handler. Before 0.66.2 the wrapper
 * inherited the native-dialog defaults, which build Paper dialog types: on servers without native dialogs (Spigot,
 * Paper 1.20.6) every prompt and confirmation failed with NoClassDefFoundError instead of reaching the chat handler.
 */
class ScreenDialogsDelegationTest {
    private final Vystorm.vystorm_core._uiMethods.dialog.DialogInterface wrapped = mock(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class);
    private final ScreenDialogs screens = new ScreenDialogs(wrapped);
    private final Plugin owner = mock(Plugin.class);
    private final Player player = mock(Player.class);

    @Test void givenTheScreenWrapper_whenAPromptOpens_thenTheWrappedHandlerTakesIt() {
        Consumer<String> submit = value -> { };
        Runnable cancel = () -> { };
        Predicate<Player> valid = p -> true;
        screens.prompt(owner, player, "title", "question", "start", submit, cancel, valid);
        verify(wrapped).prompt(owner, player, "title", "question", "start", submit, cancel, valid);
        verifyNoMoreInteractions(wrapped);
    }

    @Test void givenTheScreenWrapper_whenAConfirmationOpens_thenTheWrappedHandlerTakesIt() {
        Runnable submit = () -> { };
        Runnable cancel = () -> { };
        Predicate<Player> valid = p -> true;
        screens.confirm(owner, player, "title", "message", submit, cancel, valid);
        verify(wrapped).confirm(owner, player, "title", "message", submit, cancel, valid);
        verifyNoMoreInteractions(wrapped);
    }
}
