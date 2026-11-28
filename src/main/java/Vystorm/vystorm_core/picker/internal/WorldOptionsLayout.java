package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickKind;
import java.util.ArrayList;
import java.util.List;

/**
 * The world pick's options menu as a 27-slot chest (design 4.3): info in slot 4, then in the middle row "Use where I
 * stand" (POSITION, BLOCK; AREA: the block below the feet), "Type coordinates" (all but ENTITY), for an AREA after its
 * first corner "Redo corner 1", "Back to choosing" and "Cancel". Texts are keys under {@code pickers.world.options.}.
 * Pure. Core-internal.
 */
public final class WorldOptionsLayout {
    public static final int SIZE = 27;

    public enum Action { INFO, STAND, TYPE, REDO_FIRST, RESUME, CANCEL }

    /** Icons of the options and the area confirm view (all have a flat item sprite, so mirrored screens draw them). */
    public enum Icon { PAPER, COMPASS, WRITABLE_BOOK, SPYGLASS, BARRIER, ARROW, SPECTRAL_ARROW, LADDER, LIME_DYE, GRAY_DYE, MAP }

    public record Slot(int slot, Icon icon, String key, Action action) {}

    private WorldOptionsLayout() {}

    public static boolean canStand(PickKind kind) { return kind == PickKind.POSITION || kind == PickKind.BLOCK || kind == PickKind.AREA; }

    public static boolean canType(PickKind kind) { return kind.world() && kind != PickKind.ENTITY; }

    public static List<Slot> layout(PickKind kind) { return layout(kind, false); }

    /** @param redoFirst an AREA pick has its first corner: offer "Redo corner 1" */
    public static List<Slot> layout(PickKind kind, boolean redoFirst) {
        List<Slot> out = new ArrayList<>();
        out.add(new Slot(4, Icon.PAPER, "info", Action.INFO));
        if (canStand(kind)) out.add(new Slot(10, Icon.COMPASS, kind == PickKind.POSITION ? "stand-position" : "stand-block", Action.STAND));
        if (canType(kind)) out.add(new Slot(12, Icon.WRITABLE_BOOK, "type", Action.TYPE));
        if (kind == PickKind.AREA && redoFirst) out.add(new Slot(13, Icon.ARROW, "redo-first", Action.REDO_FIRST));
        out.add(new Slot(14, Icon.SPYGLASS, "resume", Action.RESUME));
        out.add(new Slot(16, Icon.BARRIER, "cancel", Action.CANCEL));
        return List.copyOf(out);
    }
}
