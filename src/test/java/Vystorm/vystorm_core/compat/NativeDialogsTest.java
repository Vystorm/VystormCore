package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.internal.NativeDialogs;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class NativeDialogsTest {
    @Test void givenPortableRequestWhenConvertedThenNativeCallbackRetainsResponseAndRequestGuards() {
        AtomicReference<String> submitted = new AtomicReference<>();
        var request = new DialogRequest(Component.text("Title"), List.of(), List.of(),
                List.of(new DialogButton(Component.text("Apply"), (p, r) -> submitted.set(r.getText("value")))),
                Player::isOnline, () -> {}, "Exit", 2);
        var nativeRequest = NativeDialogs.convert(request);
        assertEquals(request.title(), nativeRequest.title());
        assertEquals(request.cancelLabel(), nativeRequest.cancelLabel());
        assertEquals(2, nativeRequest.columns());
        assertSame(request.valid(), nativeRequest.valid());
        assertSame(request.cancel(), nativeRequest.cancel());
        var response = mock(io.papermc.paper.dialog.DialogResponseView.class);
        when(response.getText("value")).thenReturn("submitted");
        nativeRequest.buttons().getFirst().action().accept(mock(Player.class), response);
        assertEquals("submitted", submitted.get());
    }
}
