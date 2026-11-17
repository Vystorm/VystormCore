package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelPatch;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class PanelDiffTest {
    static JsonObject doc(String children) {
        return PanelSchema.parseJson("{\"schema\":1,\"panel\":\"t:p\",\"root\":{\"type\":\"page\",\"title\":\"T\",\"children\":[" + children + "]}}",
                PanelLimits.MAX_DOCUMENT_BYTES);
    }

    /** refresh → minimaler Patch; der Client ergibt daraus exakt das neue Dokument. */
    static JsonObject roundTrip(JsonObject a, JsonObject b) {
        JsonObject patch = PanelDiff.diff(a, b, 2);
        assertNotNull(patch);
        assertEquals(b, PanelPatch.apply(a, patch, Deadline.NONE).document());
        PanelSchema.document(PanelPatch.apply(a, patch, Deadline.NONE).document(), Deadline.NONE);
        return patch;
    }

    static final String TABLE = "{\"type\":\"table\",\"id\":\"list\",\"columns\":[{\"id\":\"a\",\"label\":\"A\"}],\"rows\":[%s]}";

    @Test
    void valueChangeIsOneSet() {
        JsonObject p = roundTrip(doc("{\"type\":\"toggle\",\"id\":\"hud\",\"label\":\"H\",\"value\":true}"),
                doc("{\"type\":\"toggle\",\"id\":\"hud\",\"label\":\"H\",\"value\":false}"));
        assertEquals("[{\"op\":\"set\",\"id\":\"hud\",\"fields\":{\"value\":false}}]", p.get("ops").toString());
    }

    @Test
    void removedFieldBecomesNull() {
        roundTrip(doc("{\"type\":\"text\",\"id\":\"t\",\"text\":\"a\",\"style\":\"muted\"}"), doc("{\"type\":\"text\",\"id\":\"t\",\"text\":\"a\"}"));
    }

    @Test
    void rowsRemoveUpsertAndOrder() {
        JsonObject a = doc(TABLE.formatted("{\"key\":\"p17\",\"cells\":[\"Nord\"]},{\"key\":\"p20\",\"cells\":[\"Süd\"]}"));
        JsonObject p = roundTrip(a, doc(TABLE.formatted("{\"key\":\"p20\",\"cells\":[\"Süd\"]}")));
        assertEquals("[{\"op\":\"rows\",\"id\":\"list\",\"remove\":[\"p17\"]}]", p.get("ops").toString());
        roundTrip(a, doc(TABLE.formatted("{\"key\":\"p20\",\"cells\":[\"Süd 2\"]},{\"key\":\"p17\",\"cells\":[\"Nord\"]},{\"key\":\"p30\",\"cells\":[\"Ost\"]}")));
    }

    @Test
    void changedChildListReplacesParent() {
        JsonObject p = roundTrip(doc("{\"type\":\"section\",\"id\":\"s\",\"children\":[{\"type\":\"text\",\"id\":\"a\",\"text\":\"x\"}]},{\"type\":\"text\",\"id\":\"z\",\"text\":\"" + "z".repeat(400) + "\"}"),
                doc("{\"type\":\"section\",\"id\":\"s\",\"children\":[{\"type\":\"text\",\"id\":\"a\",\"text\":\"x\"},{\"type\":\"text\",\"id\":\"b\",\"text\":\"y\"}]},{\"type\":\"text\",\"id\":\"z\",\"text\":\"" + "z".repeat(400) + "\"}"));
        assertEquals("replace", p.getAsJsonArray("ops").get(0).getAsJsonObject().get("op").getAsString());
    }

    @Test
    void identicalGivesEmptyPatchAndRootChangeGivesFullOpen() {
        JsonObject a = doc("{\"type\":\"text\",\"id\":\"t\",\"text\":\"a\"}");
        assertEquals(0, PanelDiff.diff(a, a.deepCopy(), 2).getAsJsonArray("ops").size());
        assertNull(PanelDiff.diff(a, doc("{\"type\":\"text\",\"id\":\"u\",\"text\":\"a\"}"), 2), "root child list changed → OPEN");
    }
}
