package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.Objects;
import java.util.Optional;

/**
 * What a world pick's {@link WorldPick.Check}, {@link WorldPick.Outline} and {@link WorldPick.Handler} get (0.71.0):
 * the target, the corner of an area pick that is already fixed while the other one is aimed (always empty for single
 * targets and for a lone first corner; while corner 1 is redone from the confirm view it is corner 2) and the carried
 * session values of
 * the calling panel ({@link PickReturn#session()}; write into it in the handler to hand values to the reopened panel).
 *
 * @since 0.71.0
 */
public record PickCandidate(Picked target, Optional<BlockPosition> firstCorner, PanelSession session) {
    public PickCandidate {
        Objects.requireNonNull(target, "target");
        firstCorner = firstCorner == null ? Optional.empty() : firstCorner;
        Objects.requireNonNull(session, "session");
    }
}
