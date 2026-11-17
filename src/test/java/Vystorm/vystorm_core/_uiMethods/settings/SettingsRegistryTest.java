package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsRegistryTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<DialogBody> bodies;
    private MockedStatic<DialogInput> inputs;
    private Plugin core, owner;
    private Player player;
    private DialogInterface dialogs;
    private SettingsRegistry registry;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bodies = mockStatic(DialogBody.class, RETURNS_DEEP_STUBS);
        inputs = mockStatic(DialogInput.class, RETURNS_DEEP_STUBS);
        core = mock(Plugin.class); owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true); when(owner.getName()).thenReturn("Example");
        player = mock(Player.class); when(player.isOnline()).thenReturn(true);
        dialogs = mock(DialogInterface.class);
        registry = new SettingsRegistry(core, () -> dialogs, p -> {});
    }
    @AfterEach void close() { inputs.close(); bodies.close(); bukkit.close(); }

    private static Setting number() {
        return Setting.number("range", "Reichweite", "", 4, 64, 4, p -> 16d, (p, v) -> null);
    }

    @Test void givenSliderValueBetweenStepsWhenReadThenSnapsToStepAndClamps() {
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getFloat("v")).thenReturn(17.9F);
        assertEquals(16d, SettingsRegistry.read("v", number(), response));
        when(response.getFloat("v")).thenReturn(999F);
        assertEquals(64d, SettingsRegistry.read("v", number(), response));
        when(response.getFloat("v")).thenReturn(Float.NaN);
        assertNull(SettingsRegistry.read("v", number(), response));
    }

    @Test void givenForgedChoiceOrOverlongTextWhenReadThenRejected() {
        LinkedHashMap<String, String> options = new LinkedHashMap<>(); options.put("a", "A"); options.put("b", "B");
        Setting choice = Setting.choice("mode", "Modus", "", options, p -> "a", (p, v) -> null);
        Setting text = Setting.text("name", "Name", "", 3, p -> "", (p, v) -> null);
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getText("c")).thenReturn("forged");
        when(response.getText("t")).thenReturn("toolong");
        assertNull(SettingsRegistry.read("c", choice, response));
        assertNull(SettingsRegistry.read("t", text, response));
        when(response.getText("c")).thenReturn("b");
        assertEquals("b", SettingsRegistry.read("c", choice, response));
    }

    @Test void givenServerSectionThenHiddenForPlayersWithoutPermission() {
        registry.register(owner, SettingsSection.server("server", "Example", "", "example.admin",
                List.of(number()), p -> {}));
        registry.register(owner, SettingsSection.player("personal", "Example", "", List.of(number())));
        assertTrue(registry.sections(player, SettingScope.SERVER).isEmpty());
        assertEquals(List.of("example:personal"), registry.keys(player));
        when(player.hasPermission("example.admin")).thenReturn(true);
        assertEquals(1, registry.sections(player, SettingScope.SERVER).size());
        assertFalse(registry.openSection(player, "missing"));
        when(owner.isEnabled()).thenReturn(false);
        assertTrue(registry.keys(player).isEmpty());
    }

    @Test void givenSaveThenOnlyChangedValuesAreWrittenAndAfterSaveRunsOnce() {
        Map<String, Object> store = new HashMap<>(Map.of("on", true, "range", 16d));
        AtomicInteger writes = new AtomicInteger(), saves = new AtomicInteger();
        Setting toggle = Setting.toggle("on", "Aktiv", "", p -> (Boolean) store.get("on"), (p, v) -> { writes.incrementAndGet(); store.put("on", v); return null; });
        Setting range = Setting.number("range", "Reichweite", "", 4, 64, 4, p -> (Double) store.get("range"),
                (p, v) -> { writes.incrementAndGet(); store.put("range", v); return null; });
        registry.register(owner, SettingsSection.server("server", "Example", "", "", List.of(toggle, range), p -> saves.incrementAndGet()));
        when(player.hasPermission("vystorm_core.admin")).thenReturn(true);
        assertTrue(registry.openSection(player, "example:server"));
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getBoolean("v0")).thenReturn(true);   // unchanged
        when(response.getFloat("v1")).thenReturn(32F);      // changed
        registry.apply(player, registry.find("example:server"), List.of(toggle, range), response);
        assertEquals(1, writes.get()); assertEquals(1, saves.get()); assertEquals(32d, store.get("range"));
        ArgumentCaptor<DialogRequest> requests = ArgumentCaptor.forClass(DialogRequest.class);
        verify(dialogs, times(2)).open(eq(core), eq(player), requests.capture());
        assertEquals(2, requests.getValue().inputs().size()); // Reopened with the saved values.
        registry.apply(player, registry.find("example:server"), List.of(toggle, range), response);
        assertEquals(1, saves.get()); // No change, no reload.
    }

    @Test void givenWebPlatformWhenWebButtonClickedThenOpenOrLinkOpensTheSettingsPage() {
        registry.register(owner, SettingsSection.player("personal", "Example", "", List.of(number())));
        try (MockedStatic<Vystorm.vystorm_core.web.WebServices> web = mockStatic(Vystorm.vystorm_core.web.WebServices.class)) {
            web.when(Vystorm.vystorm_core.web.WebServices::available).thenReturn(true);
            web.when(() -> Vystorm.vystorm_core.web.WebServices.registered("settings")).thenReturn(true);
            registry.openScope(player, SettingScope.PLAYER);
            ArgumentCaptor<DialogRequest> requests = ArgumentCaptor.forClass(DialogRequest.class);
            verify(dialogs).open(eq(core), eq(player), requests.capture());
            var label = Vystorm.vystorm_core.lang.Localization.get().core().component(player, "settings.web");
            var button = requests.getValue().buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow();
            button.action().accept(player, mock(DialogResponseView.class));
            // overlay/browser via the client mod when present and trusted, chat link otherwise – never a bare sendLink
            web.verify(() -> Vystorm.vystorm_core.web.WebServices.openOrLink(player, "settings", "", null));
            web.verify(() -> Vystorm.vystorm_core.web.WebServices.sendLink(any(), any(), any()), never());
        }
    }

    @Test void givenSetterRejectsValueThenOtherValuesStillApplyAndAfterSaveRuns() {
        AtomicInteger saves = new AtomicInteger();
        Setting bad = Setting.number("bad", "Wert", "", 0, 10, 1, p -> 1d, (p, v) -> "zu hoch");
        Setting good = Setting.toggle("good", "Aktiv", "", p -> false, (p, v) -> null);
        registry.register(owner, SettingsSection.player("p", "Example", "", List.of(bad, good)));
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getFloat("v0")).thenReturn(9F); when(response.getBoolean("v1")).thenReturn(true);
        SettingsSection section = new SettingsSection("q", "Example", "", SettingScope.PLAYER, "", List.of(bad, good), p -> saves.incrementAndGet());
        registry.register(owner, section);
        registry.apply(player, registry.find("example:q"), List.of(bad, good), response);
        assertEquals(1, saves.get());
    }

    @Test void givenInvalidDefinitionsThenRejectedAtRegistration() {
        assertThrows(IllegalArgumentException.class, () -> Setting.number("x", "X", "", 5, 5, 1, p -> 0d, (p, v) -> null));
        assertThrows(IllegalArgumentException.class, () -> Setting.choice("x", "X", "", new LinkedHashMap<>(), p -> "", (p, v) -> null));
        assertThrows(IllegalArgumentException.class, () -> SettingsSection.player("x", "X", "", List.of()));
        registry.register(owner, SettingsSection.player("dup", "X", "", List.of(number())));
        assertThrows(IllegalArgumentException.class, () -> registry.register(owner, SettingsSection.player("dup", "X", "", List.of(number()))));
    }

    @Test void givenOffGridValueAndUntouchedSliderThenValueIsNotRewritten() {
        AtomicInteger writes = new AtomicInteger();
        Setting range = Setting.number("range", "Reichweite", "", 4, 64, 4, p -> 17d, (p, v) -> { writes.incrementAndGet(); return null; });
        registry.register(owner, SettingsSection.player("p", "Example", "", List.of(range)));
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getFloat("v0")).thenReturn(16F); // the client snapped 17 onto the grid
        registry.apply(player, registry.find("example:p"), List.of(range), response);
        assertEquals(0, writes.get());
        when(response.getFloat("v0")).thenReturn(20F);
        registry.apply(player, registry.find("example:p"), List.of(range), response);
        assertEquals(1, writes.get());
    }

    @Test void givenMissingConfigKeyReadAsZeroBelowMinAndUntouchedSliderThenNothingIsWritten() {
        AtomicInteger writes = new AtomicInteger();
        // ConfigSettings liefert für fehlende Schlüssel 0; der Dialog klemmt den Startwert auf min.
        Setting missing = Setting.number("missing", "Fehlt", "", 5, 10, 1, p -> 0d, (p, v) -> { writes.incrementAndGet(); return null; });
        registry.register(owner, SettingsSection.player("m", "Example", "", List.of(missing)));
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getFloat("v0")).thenReturn(5F);
        registry.apply(player, registry.find("example:m"), List.of(missing), response);
        assertEquals(0, writes.get());
    }
}
