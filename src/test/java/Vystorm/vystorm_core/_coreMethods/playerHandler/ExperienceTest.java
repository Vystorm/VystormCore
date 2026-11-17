package Vystorm.vystorm_core._coreMethods.playerHandler;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperienceTest {
    @Test void givenNativeAwardWhenGivingThenPreserveBukkitGiveExpCall() {
        handler.giveExperience(player, 25);
        verify(player).giveExp(25);
        verify(player, never()).setExperienceLevelAndProgress(anyInt());
        assertThrows(IllegalArgumentException.class, () -> handler.giveExperience(player, -1));
        verifyNoMoreInteractions(player);
    }

    @Test void givenOverriddenSetterWhenPayingThenUseReplacementForBothDirections() {
        var replacement = spy(new DefaultPlayerHandler());
        doReturn(100).when(replacement).experience(player);
        doNothing().when(replacement).setExperience(any(), anyInt());
        assertTrue(replacement.withdrawExperience(player, 30));
        replacement.depositExperience(player, 20);
        verify(replacement).setExperience(player, 70);
        verify(replacement).setExperience(player, 120);
        verifyNoInteractions(player);
    }
    private final PlayerHandlerInterface handler = new DefaultPlayerHandler();
    private final Player player = mock(Player.class);

    @Test
    void givenExplicitBalanceWhenSettingThenUpdateBothLevelAndProgress() {
        handler.setExperience(player, 1259);
        verify(player).setExperienceLevelAndProgress(1259);
        handler.setExperience(player, 0);
        verify(player).setExperienceLevelAndProgress(0);
        verify(player, never()).calculateTotalExperiencePoints();
    }

    @Test
    void givenNegativeBalanceWhenSettingThenLeavePlayerUntouched() {
        assertThrows(IllegalArgumentException.class, () -> handler.setExperience(player, -1));
        verifyNoInteractions(player);
    }

    @Test
    void givenPartialLevelWhenWithdrawingThenUseCurrentPoints() {
        when(player.calculateTotalExperiencePoints()).thenReturn(139);
        assertTrue(handler.withdrawExperience(player, 40));
        verify(player).setExperienceLevelAndProgress(99);
    }

    @Test
    void givenInsufficientExperienceWhenWithdrawingThenLeaveBalanceUnchanged() {
        when(player.calculateTotalExperiencePoints()).thenReturn(9);
        assertFalse(handler.withdrawExperience(player, 10));
        verify(player, never()).setExperienceLevelAndProgress(anyInt());
    }

    @Test
    void givenExactBalanceWhenWithdrawingThenAllowZero() {
        when(player.calculateTotalExperiencePoints()).thenReturn(10);
        assertTrue(handler.withdrawExperience(player, 10));
        verify(player).setExperienceLevelAndProgress(0);
    }

    @Test
    void givenNegativeAmountWhenPayingThenRejectBeforeMutation() {
        assertThrows(IllegalArgumentException.class, () -> handler.depositExperience(player, -1));
        assertThrows(IllegalArgumentException.class, () -> handler.withdrawExperience(player, -1));
        verifyNoInteractions(player);
    }

    @Test
    void givenOverflowWhenDepositingThenLeaveBalanceUnchanged() {
        when(player.calculateTotalExperiencePoints()).thenReturn(Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> handler.depositExperience(player, 1));
        verify(player, never()).setExperienceLevelAndProgress(anyInt());
    }

    @Test
    void givenExistingExperienceWhenDepositingThenUpdateLevelAndProgressTogether() {
        when(player.calculateTotalExperiencePoints()).thenReturn(130);
        handler.depositExperience(player, 40);
        verify(player).setExperienceLevelAndProgress(170);
    }
}
