// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelDocument.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import java.util.List;
import java.util.Map;

/**
 * Geprüftes Dokument. {@code byId}: alle Elemente mit ID (echte und Pfad-IDs); {@code parents}: Identitäts-Map
 * Kind → Eltern (Wurzel fehlt); {@code problems}: nicht unterstützte Elemente (→ P_ERROR, Dokument bleibt gültig).
 */
public record PanelDocument(int schema, String panel, PanelNode.Page root, Map<String, PanelNode> byId,
                            Map<PanelNode, PanelNode> parents, List<Problem> problems) {
    public record Problem(String elementId, int code, String detail) {}

    public PanelNode find(String id) { return id == null ? null : byId.get(id); }

    public PanelNode parent(PanelNode node) { return parents.get(node); }

    /** Sichtbar und nicht {@code disabled} – einschließlich aller Vorfahren. */
    public boolean effectivelyEnabled(PanelNode node) {
        for (PanelNode n = node; n != null; n = parents.get(n)) {
            if (!n.common().visible() || n.common().disabled()) return false;
        }
        return true;
    }
}
