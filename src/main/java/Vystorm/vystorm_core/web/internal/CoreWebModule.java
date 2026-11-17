package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Eingebautes Diagnose-Modul {@code core} („Profil“): zeigt Name, Online-Status, Welt und Core-Version und bietet mit
 * {@code POST /api/core/echo} eine harmlose ändernde Route, an der sich Login, CSRF, Ratenlimit und Audit Ende-zu-Ende
 * prüfen lassen. Core-intern.
 *
 * @since 0.17.0
 */
public final class CoreWebModule {
    private CoreWebModule() {}

    /** Live-Typ {@code status} des Profil-Moduls. Nur Server-Thread (bei {@code player != null}). */
    public static JsonObject status(Player player) {
        JsonObject o = new JsonObject();
        o.addProperty("online", player != null && player.isOnline());
        if (player != null && player.isOnline()) {
            o.addProperty("world", player.getWorld().getName());
            o.addProperty("level", player.getLevel());
        }
        return o;
    }

    public static WebModule create() {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        return WebModule.builder(ModuleRegistry.CORE_MODULE, lang.ref("web.module.profile.title"))
                .description(lang.ref("web.module.profile.description"))
                .assets("web/profile/")
                // Live-Demo: Zustand beim Verbinden, danach Pushes bei Join/Quit/Level/Weltwechsel (siehe WebPlatform).
                .snapshot(context -> java.util.Map.of("status", context.online()
                        ? context.sync(() -> status(Bukkit.getPlayer(context.playerId())))
                        : status(null)))
                .route(WebRoute.get("/profile", request -> {
                    JsonObject out = new JsonObject();
                    out.addProperty("name", request.playerName());
                    out.addProperty("uuid", request.playerId().toString());
                    out.addProperty("core", CoreApi.VERSION);
                    out.addProperty("online", request.online());
                    if (request.online()) {
                        JsonObject live = request.sync(() -> {
                            Player player = Bukkit.getPlayer(request.playerId());
                            JsonObject o = new JsonObject();
                            if (player == null) return o;
                            o.addProperty("world", player.getWorld().getName());
                            o.addProperty("level", player.getLevel());
                            o.addProperty("health", Math.round(player.getHealth() * 10) / 10.0);
                            return o;
                        });
                        for (var e : live.entrySet()) out.add(e.getKey(), e.getValue());
                    }
                    return WebResponse.json(out);
                }).rateLimit(60).build())
                .route(WebRoute.post("/echo", request -> {
                    JsonElement body = request.body();
                    if (!body.isJsonObject() || !(body.getAsJsonObject().get("message") instanceof JsonElement m)
                            || !m.isJsonPrimitive() || !m.getAsJsonPrimitive().isString())
                        throw WebException.badRequest(Vystorm.vystorm_core.web.internal.WebText.error("echo-missing"));
                    String message = m.getAsString();
                    if (message.length() > 200) throw WebException.badRequest(Vystorm.vystorm_core.web.internal.WebText.error("echo-too-long"));
                    JsonObject out = new JsonObject();
                    out.addProperty("echo", message);
                    out.addProperty("at", Instant.now().toString());
                    return WebResponse.json(out);
                }).rateLimit(10).maxBody(1024).build())
                .build();
    }
}
