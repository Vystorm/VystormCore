package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.PanelSession;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What the viewer chose in a player picker (0.71.0). In single mode {@code chosen} has one entry. In multiple mode
 * {@code chosen} is every ticked row (rows the pick preselected included), {@code added} the ticked ones that were not
 * preselected and {@code removed} the preselected ones the viewer unticked.
 *
 * @param chosen  the ticked players, in the order they were ticked (preselected ones first)
 * @param added   chosen but not preselected
 * @param removed preselected but no longer chosen
 * @param session the carried session values of the calling panel ({@link PickReturn#session()}); write into it to
 *                hand values to the reopened panel
 * @since 0.71.0
 */
public record PlayerPicks(List<PickedPlayer> chosen, Set<UUID> added, Set<UUID> removed, PanelSession session) {
    public PlayerPicks {
        chosen = List.copyOf(chosen);
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
        Objects.requireNonNull(session, "session");
    }
}
