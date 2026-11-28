package Vystorm.vystorm_core.picker.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The confirm view of an area pick as a 27-slot chest (design 4.3), mirrored as a Vystorm Screen for pack players like
 * every Core chest: the summary in slot 4 (named with the size, so a mirrored screen, which shows no lore, still shows
 * it; the lore has from, to, size, blocks and the check's answer), the block count as the name of slot 5, then in the middle row
 * "Use this area" (10), "Redo corner 1" (12), "Redo corner 2" (13), "Full height" (14, only {@code AreaHeight.ASK})
 * and "Cancel" (16, the same slot as in the options chest). Texts are keys under {@code pickers.world.area.confirm.}.
 * Pure. Core-internal.
 */
public final class AreaConfirmLayout {
    public static final int SIZE = 27;

    public enum Action { INFO, BLOCKS, USE, REDO_FIRST, REDO_SECOND, HEIGHT, CANCEL }

    public record Slot(int slot, WorldOptionsLayout.Icon icon, String key, Action action) {}

    private AreaConfirmLayout() {}

    /** @param canExpand the pick allows "Full height" ({@code AreaHeight.ASK}) */
    public static List<Slot> layout(boolean canExpand) {
        List<Slot> out = new ArrayList<>();
        out.add(new Slot(4, WorldOptionsLayout.Icon.PAPER, "info", Action.INFO));
        out.add(new Slot(5, WorldOptionsLayout.Icon.MAP, "blocks-label", Action.BLOCKS));
        out.add(new Slot(10, WorldOptionsLayout.Icon.LIME_DYE, "use", Action.USE));
        out.add(new Slot(12, WorldOptionsLayout.Icon.ARROW, "redo-first", Action.REDO_FIRST));
        out.add(new Slot(13, WorldOptionsLayout.Icon.SPECTRAL_ARROW, "redo-second", Action.REDO_SECOND));
        if (canExpand) out.add(new Slot(14, WorldOptionsLayout.Icon.LADDER, "height", Action.HEIGHT));
        out.add(new Slot(16, WorldOptionsLayout.Icon.BARRIER, "cancel", Action.CANCEL));
        return List.copyOf(out);
    }
}
