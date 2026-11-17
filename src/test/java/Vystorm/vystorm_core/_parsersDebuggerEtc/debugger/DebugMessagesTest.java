package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugMessagesTest {
    @Test
    @SuppressWarnings("deprecation")
    void givenCustomDebuggerWhenLoggingThenForwardMessageAndLevel() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            DebugInterface debugger = mock(DebugInterface.class);
            mainMock.when(main::getApi).thenReturn(api);
            when(api.debug()).thenReturn(debugger);
            DebugMessages.mid("[Vault 12] Payment rejected");
            verify(debugger).mid(eq("[Vault 12] Payment rejected"), notNull());
            verifyNoMoreInteractions(debugger);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void givenReentrantCustomDebuggerWhenLoggingThenSuppressNestedMessage() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            DebugInterface debugger = mock(DebugInterface.class);
            mainMock.when(main::getApi).thenReturn(api);
            when(api.debug()).thenReturn(debugger);
            doAnswer(call -> { DebugMessages.low("[Nested 1] Should not recurse"); return null; })
                    .when(debugger).low(anyString(), any());
            DebugMessages.low("[Outer 1] Original");
            verify(debugger, times(1)).low(anyString(), any());
        }
    }

    @Test
    void givenNoApiWhenLoggingThenRemainUsableBeforeStartup() {
        try (var mainMock = mockStatic(main.class)) {
            assertDoesNotThrow(() -> DebugMessages.low("[Test 1] Before startup"));
        }
    }
}
