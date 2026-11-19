package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.web.internal.clientproto.panel.ActionCheck;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelNode;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class SettingsPanelsTest {
    MockedStatic<Bukkit> bukkit;
    SettingsRegistry registry;
    Player player;
    boolean hud = true, admin;
    double range = 16;
    String mode = "a", name = "Esoren";
    final AtomicInteger afterSave = new AtomicInteger();
    SettingsSection display;
    Plugin displayOwner;

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("Example");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenAnswer(i -> admin);
        registry = new SettingsRegistry(mock(Plugin.class), () -> mock(DialogInterface.class), p -> { });
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("a", "A");
        options.put("b", "B");
        displayOwner = owner;
        registry.register(owner, display = new SettingsSection("display", "Anzeige", "Wie es aussieht", Vystorm.vystorm_core._uiMethods.settings.SettingScope.PLAYER, "",
                List.of(Setting.toggle("hud", "HUD", "", p -> hud, (p, v) -> { hud = v; return null; }),
                        Setting.number("range", "Reichweite", "", 4, 64, 4, p -> range, (p, v) -> v > 32 ? "zu weit" : set(v)),
                        Setting.choice("mode", "Modus", "", options, p -> mode, (p, v) -> { mode = v; return null; }),
                        Setting.text("name", "Name", "", 16, p -> name, (p, v) -> { name = v; return null; })),
                p -> afterSave.incrementAndGet()));
        registry.register(owner, SettingsSection.server("server", "Server", "", "example.admin",
                List.of(Setting.toggle("pvp", "PvP", "", p -> false, (p, v) -> null)), p -> { }));
    }

    String set(double v) { range = v; return null; }

    @AfterEach void tearDown() { bukkit.close(); }

    PanelContext ctx(String arg, Map<String, Object> state) {
        return new PanelContext() {
            @Override public Player player() { return player; }
            @Override public String arg() { return arg; }
            @Override public String text(String key, Object... p) { return key; }
            @Override public Component component(String key, Object... p) { return Component.text(key); }
            @Override public PanelSession session() {
                return new PanelSession() {
                    @Override public int id() { return 1; }
                    @SuppressWarnings("unchecked") @Override public <T> T get(String k) { return (T) state.get(k); }
                    @Override public void put(String k, Object v) { state.put(k, v); }
                    @Override public void remove(String k) { state.remove(k); }
                };
            }
        };
    }

    Vystorm.vystorm_core.panel.PanelAction action() {
        return new Vystorm.vystorm_core.panel.PanelAction() {
            @Override public Player player() { return player; }
            @Override public Object value() { return null; }
            @Override public PanelSession session() { return null; }
            @Override public void refresh() { }
        };
    }

    PanelWriter.Written written(String arg, Map<String, Object> state) {
        Panel panel = SettingsPanels.render(ctx(arg, state), registry);
        return PanelWriter.write("vystorm_core:settings", panel, player, null, null);
    }

    @Test void givenSections_whenRendered_thenControlsMatchKindsAndSchemaAccepts() {
        PanelWriter.Written w = written("", new HashMap<>());
        PanelDocument d = PanelSchema.document(w.document(), Deadline.NONE);
        assertInstanceOf(PanelNode.Toggle.class, d.find("s:example:display/hud"));
        assertInstanceOf(PanelNode.Slider.class, d.find("s:example:display/range"));
        assertEquals(PanelNode.Select.Style.SEGMENTED, ((PanelNode.Select) d.find("s:example:display/mode")).style());
        assertEquals(16, ((PanelNode.Input) d.find("s:example:display/name")).maxLength());
        assertNull(d.find("scope"), "no server tab for normal players");
    }

    @Test void givenLargeNumericSettings_whenRendered_thenSchemaAcceptsTextFallbackWithoutChangingRange() {
        registry.register(displayOwner, new SettingsSection("large", "Large", "", Vystorm.vystorm_core._uiMethods.settings.SettingScope.PLAYER, "",
                List.of(Setting.number("amount", "Amount", "", 0, 1_000_000, 1, p -> range, (p, v) -> set(v)),
                        Setting.number("boundary", "Boundary", "", 0, 100_000, 1, p -> 10.0, (p, v) -> null),
                        Setting.number("wide", "Wide", "", 0, 1e13, 1e9, p -> 0.0, (p, v) -> null)), p -> { }));
        PanelWriter.Written w = written("", new HashMap<>());
        PanelDocument d = PanelSchema.document(w.document(), Deadline.NONE);
        assertInstanceOf(PanelNode.Input.class, d.find("s:example:large/amount"));
        assertInstanceOf(PanelNode.NumberField.class, d.find("s:example:large/boundary"));
        assertInstanceOf(PanelNode.Input.class, d.find("s:example:large/wide"));
        Object value = ActionCheck.check(d, "s:example:large/amount", PanelLimits.KIND_CHANGE, "\"123456\"");
        assertTrue(w.handlers().get("s:example:large/amount").handle(action(), value).success());
        assertEquals(123456, range);
        for (String invalid : List.of("NaN", "Infinity", "1000001", "-1", "not a number")) {
            assertFalse(w.handlers().get("s:example:large/amount").handle(action(), invalid).success(), invalid);
            assertEquals(123456, range, "invalid input never reaches the setter");
        }
    }

    /** Gleiches Ergebnis wie Dialog und Web: derselbe Setter, afterSave einmal, Fehlertext des Setters. */
    @Test void givenPanelChange_whenSaved_thenSamePathAsDialogAndWeb() {
        PanelWriter.Written w = written("", new HashMap<>());
        PanelDocument d = PanelSchema.document(w.document(), Deadline.NONE);
        Object v = ActionCheck.check(d, "s:example:display/hud", PanelLimits.KIND_CHANGE, "false");
        assertTrue(w.handlers().get("s:example:display/hud").handle(action(), v).success());
        assertFalse(hud);
        assertEquals(1, afterSave.get());
        var rejected = w.handlers().get("s:example:display/range").handle(action(), 40.0);
        assertFalse(rejected.success());
        assertTrue(rejected.message().contains("zu weit"));
        assertEquals(16, range);
        registry.save(player, "example:display", Map.of("hud", true), "web");
        assertTrue(hud);
        assertEquals(2, afterSave.get(), "web path behaves the same");
    }

    @Test void givenForgedValues_whenChecked_thenRejectedBeforeSetter() {
        PanelDocument d = PanelSchema.document(written("", new HashMap<>()).document(), Deadline.NONE);
        assertThrows(ActionCheck.Rejected.class, () -> ActionCheck.check(d, "s:example:display/mode", PanelLimits.KIND_CHANGE, "\"c\""));
        assertThrows(ActionCheck.Rejected.class, () -> ActionCheck.check(d, "s:example:display/name", PanelLimits.KIND_CHANGE, "\"" + "x".repeat(17) + "\""));
        assertThrows(ActionCheck.Rejected.class, () -> ActionCheck.check(d, "s:example:display/range", PanelLimits.KIND_CHANGE, "18"));
    }

    @Test void givenAdmin_whenRendered_thenServerTabAndArgSelectsIt() {
        admin = true;
        PanelDocument d = PanelSchema.document(written("server", new HashMap<>()).document(), Deadline.NONE);
        assertEquals("server", ((PanelNode.Tabs) d.find("scope")).selected());
    }

    /** 0.27.1: config-editor sections (all plugin configs) are one entry in the list, not thousands of inline controls. */
    @Test void givenConfigEditorSections_whenListRendered_thenOneEntryAndSingleSectionStillRenders(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        admin = true;
        Plugin cfg = mock(Plugin.class);
        when(cfg.getName()).thenReturn("Cfg");
        when(cfg.isEnabled()).thenReturn(true);
        when(cfg.getDataFolder()).thenReturn(folder.toFile());
        when(cfg.getLogger()).thenReturn(java.util.logging.Logger.getLogger("SettingsPanelsTest"));
        when(cfg.getResource("config.yml")).thenAnswer(i -> new java.io.ByteArrayInputStream("speed: 3\nfast: true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        org.bukkit.configuration.file.YamlConfiguration live = new org.bukkit.configuration.file.YamlConfiguration();
        when(cfg.getConfig()).thenReturn(live);
        Vystorm.vystorm_core.API api = mock(Vystorm.vystorm_core.API.class);
        when(api.settings()).thenReturn(registry);
        try (MockedStatic<Vystorm.vystorm_core.main> main = mockStatic(Vystorm.vystorm_core.main.class)) {
            main.when(Vystorm.vystorm_core.main::getApi).thenReturn(api);
            main.when(Vystorm.vystorm_core.main::getPlugin).thenReturn(mock(Plugin.class));
            assertTrue(Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.register(cfg, "config.yml", null));
            try {
                assertEquals("config", registry.group("cfg:cfg-general"));
                PanelDocument list = PanelSchema.document(written("server", new HashMap<>()).document(), Deadline.NONE);
                assertNotNull(list.find("configs"), "one entry for the plugin configuration");
                assertNull(list.find(SettingsPanels.settingId("cfg:cfg-general", "speed")), "config values are not inlined");
                assertNotNull(list.find("s:example:server/pvp"), "ordinary server sections stay");
                PanelDocument single = PanelSchema.document(written("cfg:cfg-general", new HashMap<>()).document(), Deadline.NONE);
                assertNotNull(single.find(SettingsPanels.settingId("cfg:cfg-general", "speed")));
            } finally {
                Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.unregister(cfg);
            }
        }
    }

    @Test void givenFilter_whenRendered_thenOnlyMatchingSections() {
        Map<String, Object> state = new HashMap<>();
        state.put("filter", "zzz");
        PanelDocument d = PanelSchema.document(written("", state).document(), Deadline.NONE);
        assertNull(d.find("s:example:display/hud"));
        assertNotNull(d.find("filter"));
    }

    /**
     * Final Review Important 7: IDs hängen am Abschnitt und an der Einstellung, nicht an der Position – kommt ein Abschnitt
     * davor dazu (Plugin geladen, Recht), zeigt dieselbe ID weiter auf dieselbe Einstellung.
     */
    @Test void givenSectionAddedBefore_whenRendered_thenIdsStayOnTheSameSetting() {
        PanelDocument before = PanelSchema.document(written("", new HashMap<>()).document(), Deadline.NONE);
        Plugin early = mock(Plugin.class);
        when(early.isEnabled()).thenReturn(true);
        when(early.getName()).thenReturn("Aaa");
        registry = new SettingsRegistry(mock(Plugin.class), () -> mock(DialogInterface.class), p -> { });
        registry.register(early, new SettingsSection("first", "Erster", "", Vystorm.vystorm_core._uiMethods.settings.SettingScope.PLAYER, "",
                List.of(Setting.toggle("x", "X", "", p -> true, (p, v) -> null)), p -> { }));
        registry.register(displayOwner, display);   // jetzt an zweiter Stelle
        PanelDocument after = PanelSchema.document(written("", new HashMap<>()).document(), Deadline.NONE);
        for (String id : List.of("s:example:display/hud", "s:example:display/range", "s:example:display/mode", "s:example:display/name"))
            assertEquals(before.find(id).getClass(), after.find(id).getClass(), id);
        assertEquals("HUD", ((PanelNode.Toggle) after.find("s:example:display/hud")).label().plainText());
    }

    /**
     * 0.66.0: a section another plugin registered (here with Core's player preferences, as Chat and Winter do) is a
     * settings list on screens without any change on the plugin side, and a click on its row saves through the same path.
     */
    @Test void givenContributedPrefsSection_whenLaidOutAsScreen_thenItIsAListAndARowClickSaves() {
        Plugin chat = mock(Plugin.class);
        when(chat.isEnabled()).thenReturn(true);
        when(chat.getName()).thenReturn("VystormChat");
        boolean[] bubbles = { true };
        LinkedHashMap<String, String> sounds = new LinkedHashMap<>();
        sounds.put("ping", "Ping");
        sounds.put("bell", "Bell");
        String[] sound = { "ping" };
        registry.register(chat, SettingsSection.player("chat", "Chat", "Your chat",
                List.of(Setting.toggle("bubbles", "Speech bubbles above your head", "Shows what you write above you for a few seconds", p -> bubbles[0], (p, v) -> { bubbles[0] = v; return null; }),
                        Setting.choice("sound", "Mention sound", "Sound when someone writes your name", sounds, p -> sound[0], (p, v) -> { sound[0] = v; return null; }))));
        PanelWriter.Written w = written("", new HashMap<>());
        PanelDocument d = PanelSchema.document(w.document(), Deadline.NONE);
        java.util.Set<String> lists = PanelScreenLayout.listIds(w.document());
        assertEquals(2, lists.size(), "every section is a list: " + lists);
        PanelScreenLayout.Result r = PanelScreenLayout.layout(d, new PanelScreenLayout.View(), PanelScreenLayout.metrics(d.root(), 2.49, 1.4, 1.0),
                PanelScreenLayoutTest.TEXTS, List.of(), lists);
        List<String> texts = r.layout().widgets().stream().map(x -> x.text() instanceof Component c ? PanelScreenLayout.plain(c) : String.valueOf(x.text())).toList();
        for (String s : List.of("Chat", "Speech bubbles above your head", "ON", "Shows what you write above you for a few seconds", "Mention sound", "Ping", "Sound when someone writes your name"))
            assertTrue(texts.contains(s), "shows " + s + " in " + texts);
        String id = SettingsPanels.settingId("vystormchat:chat", "bubbles");
        PanelScreenLayout.Act.Action click = r.acts().values().stream().filter(a -> a instanceof PanelScreenLayout.Act.Action x && x.elementId().equals(id))
                .map(PanelScreenLayout.Act.Action.class::cast).findFirst().orElseThrow();
        Object value = ActionCheck.check(d, click.elementId(), click.kind(), click.value());
        assertTrue(w.handlers().get(id).handle(action(), value).success());
        assertFalse(bubbles[0], "the row click switched it off through the plugin's own setter");
        String soundId = SettingsPanels.settingId("vystormchat:chat", "sound");
        PanelScreenLayout.Act.Action cycle = r.acts().values().stream().filter(a -> a instanceof PanelScreenLayout.Act.Action x && x.elementId().equals(soundId))
                .map(PanelScreenLayout.Act.Action.class::cast).findFirst().orElseThrow();
        assertTrue(w.handlers().get(soundId).handle(action(), ActionCheck.check(d, soundId, cycle.kind(), cycle.alt())).success());
        assertEquals("bell", sound[0], "right click steps back to the last option");
    }

    @Test void givenScreenOpener_whenSettingsOpenedThroughTheApi_thenTheScreenComesFirstAndDialogsOnlyWithout() {
        java.util.List<String> opened = new java.util.ArrayList<>();
        boolean[] screen = { true };
        registry.screenOpener((p, arg) -> { opened.add(arg); return screen[0]; });
        registry.openOverview(player);
        registry.openScope(player, Vystorm.vystorm_core._uiMethods.settings.SettingScope.SERVER);
        assertTrue(registry.openSection(player, "display"));
        assertEquals(List.of("", "server", "example:display"), opened);
        screen[0] = false;
        DialogInterface dialogs = mock(DialogInterface.class);
        SettingsRegistry withDialogs = new SettingsRegistry(mock(Plugin.class), () -> dialogs, p -> { });
        withDialogs.register(displayOwner, display);
        withDialogs.screenOpener((p, arg) -> false);
        assertTrue(withDialogs.openSection(player, "example:display"));
        verify(dialogs).open(any(Plugin.class), eq(player), any(Vystorm.vystorm_core.compat.dialog.DialogRequest.class));
    }

    @Test void givenUnfitKeys_whenIdBuilt_thenStableHashedIdWithinTheIdRules() {
        String a = SettingsPanels.settingId("Some Plugin:Weird Section", "camelCaseSetting");
        assertEquals(a, SettingsPanels.settingId("Some Plugin:Weird Section", "camelCaseSetting"));
        assertNotEquals(a, SettingsPanels.settingId("Some Plugin:Weird Section", "camelcasesetting"));
        assertTrue(Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds.element(a), a);
        assertTrue(Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds.element(SettingsPanels.settingId("k".repeat(80), "x")));
    }
}
