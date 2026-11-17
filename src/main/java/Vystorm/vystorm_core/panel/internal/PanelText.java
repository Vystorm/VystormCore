package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Text für Panels (Spec §3.1): String, {@code Lang.ref} oder Adventure-{@code Component} → UiText-JSON pro Spieler.
 * Farbe und Dekorationen bleiben, Klick/Hover/Insertion entfallen, {@code translatable} wird vorher gerendert. Über den
 * Client-Grenzen (64 Abschnitte, 2048 Zeichen) wird gekürzt (Plan-Entscheidung 19). Core-intern.
 */
public final class PanelText {
    private record Seg(String text, Style style) {}

    private PanelText() {}

    public static JsonElement of(Player viewer, Object text) {
        if (text == null) return new JsonPrimitive("");
        Component c;
        if (text instanceof Component component) {
            c = Localization.get().render(viewer, component);
        } else {
            String s = Localization.get().resolve(viewer, text.toString());
            if (s.indexOf('§') < 0) return new JsonPrimitive(shorten(UiText.sanitize(s)));
            c = LegacyComponentSerializer.legacySection().deserialize(s);
        }
        List<Seg> segs = new ArrayList<>();
        flatten(c, Style.empty(), segs);
        return toJson(segs);
    }

    private static void flatten(Component c, Style parent, List<Seg> out) {
        Style style = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);   // Kind gewinnt, Rest erbt
        String content = c instanceof TextComponent t ? t.content() : c.children().isEmpty() ? PlainTextComponentSerializer.plainText().serialize(c) : "";
        if (!content.isEmpty()) out.add(new Seg(UiText.sanitize(content), style));
        for (Component child : c.children()) flatten(child, style, out);
    }

    private static JsonElement toJson(List<Seg> segs) {
        if (segs.size() > PanelLimits.MAX_TEXT_SEGMENTS) {   // Rest in den letzten Abschnitt, ohne Stil
            StringBuilder rest = new StringBuilder();
            for (Seg s : segs.subList(PanelLimits.MAX_TEXT_SEGMENTS - 1, segs.size())) rest.append(s.text());
            segs = new ArrayList<>(segs.subList(0, PanelLimits.MAX_TEXT_SEGMENTS - 1));
            segs.add(new Seg(rest.toString(), Style.empty()));
        }
        int budget = PanelLimits.MAX_TEXT_CHARS;
        JsonArray out = new JsonArray();
        for (Seg s : segs) {
            if (budget <= 0) break;
            String text = s.text().length() > budget ? s.text().substring(0, Math.max(0, budget - 1)) + "…" : s.text();
            budget -= text.length();
            JsonObject o = new JsonObject();
            o.addProperty("text", text);
            if (s.style().color() != null) o.addProperty("color", s.style().color().asHexString());
            if (s.style().decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) o.addProperty("bold", true);
            if (s.style().decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) o.addProperty("italic", true);
            if (s.style().decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE) o.addProperty("underlined", true);
            if (s.style().decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE) o.addProperty("strikethrough", true);
            out.add(o);
        }
        return out.size() == 1 && out.get(0).getAsJsonObject().size() == 1 ? out.get(0).getAsJsonObject().get("text") : out;
    }

    private static String shorten(String s) {
        return s.length() <= PanelLimits.MAX_TEXT_CHARS ? s : s.substring(0, PanelLimits.MAX_TEXT_CHARS - 1) + "…";
    }
}
