// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelIcon.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import Vystorm.vystorm_core.web.internal.clientproto.ui.Icon;

/**
 * Symbol eines Panels: bestehendes {@link Icon} ({@code item}+{@code model}, {@code sprite}, {@code image}) oder
 * {@code {"blob":"<sha256hex>"}}; bei Items zusätzlich {@code count} (0..999) und {@code glint}.
 */
public record PanelIcon(Icon base, String blob, int count, boolean glint) {
    public boolean isBlob() { return blob != null; }
}
