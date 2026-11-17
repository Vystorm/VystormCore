package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Core's web settings page: the same registry, permissions, validation and setters as the in-game dialog; live events
 * after saves (web and dialog) and {@code changed(player)}.
 */
class SettingsWebModuleTest {
    @TempDir Path dir;

    private final UUID alex = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private final Map<String, Object> store = new HashMap<>(Map.of("sound", true, "range", 16d, "mode", "a", "name", "x"));
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private final List<Integer> saves = new ArrayList<>();
    private WebFixture web;
    private ExecutorService serverThread;
    private SettingsRegistry registry;
    private Player player;
    private Plugin owner;

    @BeforeEach void setUp() throws Exception {
        web = new WebFixture(dir);
        // The "server thread": Bukkit.isPrimaryThread() is true only there (static mocks are thread-local).
        serverThread = Executors.newSingleThreadExecutor();
        serverThread.submit(() -> {
            MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            return bukkit;
        }).get();
        web.players.serverThread = serverThread;
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(alex);
        when(player.getName()).thenReturn("Alex");
        when(player.isOnline()).thenReturn(true);
        owner = WebFixture.plugin("Example");
        registry = new SettingsRegistry(WebFixture.plugin("vystorm_core"), () -> mock(DialogInterface.class), p -> {});
        LinkedHashMap<String, String> modes = new LinkedHashMap<>();
        modes.put("a", "Mode A");
        modes.put("b", "§aMode B");
        onServer(() -> {
            registry.register(owner, SettingsSection.player("personal", "Example", "Personal things", List.of(
                    Setting.toggle("sound", "Sounds", "Plays a sound", p -> (Boolean) store.get("sound"), (p, v) -> { store.put("sound", v); return null; }),
                    Setting.number("range", "Range", "", 4, 64, 4, p -> (Double) store.get("range"),
                            (p, v) -> v > 48 ? "too far" : put("range", v)),
                    Setting.choice("mode", "Mode", "", modes, p -> (String) store.get("mode"), (p, v) -> put("mode", v)),
                    Setting.text("name", "Name", "", 5, p -> (String) store.get("name"), (p, v) -> put("name", v)),
                    Setting.toggle("secret", "Secret", "", p -> true, (p, v) -> null).permission("example.secret"))));
            registry.register(owner, new SettingsSection("server", "Example server", "", Vystorm.vystorm_core._uiMethods.settings.SettingScope.SERVER,
                    "example.admin", List.of(Setting.toggle("pvp", "PvP", "", p -> false, (p, v) -> put("pvp", v))), p -> saves.add(1)));
            return null;
        });
        web.modules.register(WebFixture.plugin("vystorm_core"), SettingsWebModule.create(() -> registry,
                id -> id.equals(alex) ? player : null,
                (id, data) -> pushes.add((id == null ? "all" : id.toString()) + " " + data)));
    }

    private String put(String key, Object value) { store.put(key, value); return null; }

    private <T> T onServer(java.util.concurrent.Callable<T> task) throws Exception { return serverThread.submit(task).get(5, TimeUnit.SECONDS); }

    @AfterEach void tearDown() throws Exception {
        serverThread.shutdownNow();
        web.close();
    }

    private JsonObject json(HttpResponseData r) { return JsonParser.parseString(r.bodyText()).getAsJsonObject(); }

    private HttpResponseData save(WebFixture.Login login, String section, String values) {
        return web.dispatcher.handle(WebFixture.post("/api/settings/save", "{\"section\":\"" + section + "\",\"values\":" + values + "}", login.cookie(), login.csrf()));
    }

    @Test void givenOfflinePlayerWhenReadingSettingsThenNoValuesButNoError() {
        WebFixture.Login login = web.login(alex, "Alex");
        HttpResponseData r = web.dispatcher.handle(WebFixture.get("/api/settings/sections", login.cookie()));
        assertEquals(200, r.status());
        assertFalse(json(r).get("online").getAsBoolean());
        assertTrue(json(r).get("supported").getAsBoolean());
        assertEquals(0, json(r).getAsJsonArray("sections").size());
        assertEquals(409, save(login, "example:personal", "{\"sound\":false}").status(), "saving needs the player in game");
    }

    @Test void givenPlayerWithoutAdminPermissionThenOnlyPersonalSettingsAreShownAndServerSectionIs404() throws Exception {
        web.players.online.add(alex);
        WebFixture.Login login = web.login(alex, "Alex");
        HttpResponseData r = web.dispatcher.handle(WebFixture.get("/api/settings/sections", login.cookie()));
        assertEquals(200, r.status());
        JsonArray sections = json(r).getAsJsonArray("sections");
        assertEquals(1, sections.size());
        JsonObject personal = sections.get(0).getAsJsonObject();
        assertEquals("example:personal", personal.get("key").getAsString());
        assertEquals("player", personal.get("scope").getAsString());
        JsonArray settings = personal.getAsJsonArray("settings");
        assertEquals(4, settings.size(), "a setting with a missing permission is hidden");
        JsonObject range = settings.get(1).getAsJsonObject();
        assertEquals("number", range.get("kind").getAsString());
        assertEquals(16d, range.get("value").getAsDouble());
        assertEquals(4d, range.get("step").getAsDouble());
        assertEquals("Mode B", settings.get(2).getAsJsonObject().getAsJsonArray("options").get(1).getAsJsonObject().get("label").getAsString(),
                "legacy colour codes are removed");
        assertEquals(404, save(login, "example:server", "{\"pvp\":true}").status());
        assertFalse(store.containsKey("pvp"));
        // Hidden single setting: not written, reported as invalid.
        JsonObject answer = json(save(login, "example:personal", "{\"secret\":false}"));
        assertEquals(0, answer.get("changed").getAsInt());
        assertEquals("invalid", answer.getAsJsonArray("problems").get(0).getAsJsonObject().get("code").getAsString());

        when(player.hasPermission("example.admin")).thenReturn(true);
        r = web.dispatcher.handle(WebFixture.get("/api/settings/sections", login.cookie()));
        assertEquals(2, json(r).getAsJsonArray("sections").size());
        answer = json(save(login, "example:server", "{\"pvp\":true}"));
        assertEquals(1, answer.get("changed").getAsInt());
        assertEquals(true, store.get("pvp"));
        assertEquals(List.of(1), saves, "afterSave ran once");
        assertTrue(pushes.getLast().startsWith("all "), "server values changed: every open page reloads");
    }

    @Test void givenWebSaveThenSameValidationAsTheDialogAndLiveEventForThePlayer() throws Exception {
        web.players.online.add(alex);
        WebFixture.Login login = web.login(alex, "Alex");
        JsonObject answer = json(save(login, "example:personal", "{\"sound\":false,\"range\":21.9,\"mode\":\"b\",\"name\":\"abc\"}"));
        assertEquals(4, answer.get("changed").getAsInt());
        assertEquals(false, store.get("sound"));
        assertEquals(20d, store.get("range"), "snapped to the step grid like the slider");
        assertEquals("b", store.get("mode"));
        assertEquals("abc", store.get("name"));
        assertEquals(0, answer.getAsJsonArray("problems").size());
        assertEquals("example:personal", answer.getAsJsonObject("section").get("key").getAsString());
        assertEquals(1, pushes.size());
        assertTrue(pushes.getFirst().startsWith(alex + " "), pushes.toString());
        assertTrue(pushes.getFirst().contains("\"section\":\"example:personal\""));

        answer = json(save(login, "example:personal", "{\"sound\":\"yes\",\"range\":60,\"mode\":\"forged\",\"name\":\"toolong\",\"nope\":1}"));
        assertEquals(0, answer.get("changed").getAsInt());
        Map<String, String> codes = new HashMap<>();
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement p : answer.getAsJsonArray("problems")) {
            codes.put(p.getAsJsonObject().get("id").getAsString(), p.getAsJsonObject().get("code").getAsString());
            byId.put(p.getAsJsonObject().get("id").getAsString(), p.getAsJsonObject());
        }
        assertEquals(Map.of("sound", "invalid", "range", "rejected", "mode", "invalid", "name", "invalid", "nope", "invalid"), codes);
        assertEquals("too far", byId.get("range").get("message").getAsString(), "the owner's setter decides and its message reaches the page");
        assertEquals(20d, store.get("range"));
        assertEquals(1, pushes.size(), "nothing changed, no event");

        // Unchanged value: no write, no event (like an untouched dialog).
        answer = json(save(login, "example:personal", "{\"sound\":false}"));
        assertEquals(0, answer.get("changed").getAsInt());
        assertEquals(1, pushes.size());
    }

    @Test void givenChangeOutsideTheWebThenPageIsToldAndRequestsNeedCsrfAndValidBodies() throws Exception {
        web.players.online.add(alex);
        WebFixture.Login login = web.login(alex, "Alex");
        registry.changed(player);
        registry.changed(null);
        assertEquals(List.of("player", "server"), pushes.stream().map(p -> p.contains("\"scope\":\"server\"") ? "server" : "player").toList());
        HttpResponseData noCsrf = web.dispatcher.handle(WebFixture.post("/api/settings/save", "{\"section\":\"example:personal\",\"values\":{\"sound\":false}}", login.cookie(), null));
        assertEquals(403, noCsrf.status());
        assertEquals(true, store.get("sound"));
        assertEquals(400, save(login, "example:personal", "[]").status());
        assertEquals(400, web.dispatcher.handle(WebFixture.post("/api/settings/save", "{\"values\":{\"sound\":false}}", login.cookie(), login.csrf())).status());
        assertEquals(404, save(login, "missing:section", "{\"sound\":false}").status());
        assertEquals(401, web.dispatcher.handle(WebFixture.get("/api/settings/sections", null)).status());
    }
}
