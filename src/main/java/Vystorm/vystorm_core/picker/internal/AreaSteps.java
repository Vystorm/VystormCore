package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.AreaHeight;
import Vystorm.vystorm_core.picker.AreaRules;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The two-corner state machine of an area pick (design 4.3): choose corner 1, then corner 2, then the confirm view.
 * From the confirm view either corner can be chosen again and the pick comes back to the confirm view with the other
 * corner kept; "Redo corner 1" while choosing corner 2 starts over. While a corner is chosen, the other one already
 * fixed is the {@link #anchor()} the preview box grows from. "Full height" can be switched only in the confirm view of
 * an {@link AreaHeight#ASK} pick and stays when a corner is redone. Pure. Server thread. Core-internal.
 */
public final class AreaSteps {
    public enum Step { FIRST, SECOND, CONFIRM }

    /** A chosen corner: its world and cell. */
    public record Corner(UUID world, BlockPosition cell) {
        public Corner { Objects.requireNonNull(world, "world"); Objects.requireNonNull(cell, "cell"); }
    }

    private Step step = Step.FIRST;
    private Corner first, second;
    private boolean expanded;

    public Step step() { return step; }

    /** Null until chosen (and again while it is redone). */
    public Corner first() { return first; }

    public Corner second() { return second; }

    /** The corner number being chosen: 1 or 2; 0 in the confirm view. */
    public int choosing() { return step == Step.FIRST ? 1 : step == Step.SECOND ? 2 : 0; }

    /** The corner already fixed while the other one is aimed (empty while corner 1 is chosen the first time). */
    public Optional<Corner> anchor() {
        return switch (step) {
            case FIRST -> Optional.ofNullable(second);
            case SECOND, CONFIRM -> Optional.ofNullable(first);
        };
    }

    /** The corner being chosen was picked: on to corner 2, or to the confirm view when the other corner is known. */
    public void set(Corner corner) {
        Objects.requireNonNull(corner, "corner");
        switch (step) {
            case FIRST -> {
                first = corner;
                step = second != null ? Step.CONFIRM : Step.SECOND;
            }
            case SECOND -> {
                second = corner;
                step = Step.CONFIRM;
            }
            case CONFIRM -> throw new IllegalStateException("both corners are chosen");
        }
    }

    public boolean canRedoFirst() { return step == Step.SECOND || step == Step.CONFIRM; }

    public boolean canRedoSecond() { return step == Step.CONFIRM; }

    /** Choose corner 1 again: from corner 2 it starts over, from the confirm view corner 2 is kept. False when there is nothing to redo. */
    public boolean redoFirst() {
        if (!canRedoFirst()) return false;
        if (step == Step.SECOND) second = null;
        first = null;
        step = Step.FIRST;
        return true;
    }

    /** Choose corner 2 again (confirm view only); corner 1 is kept. */
    public boolean redoSecond() {
        if (!canRedoSecond()) return false;
        second = null;
        step = Step.SECOND;
        return true;
    }

    /** "Full height" on or off: only in the confirm view of an ASK pick. */
    public boolean toggleHeight(AreaRules rules) {
        if (step != Step.CONFIRM || rules.height() != AreaHeight.ASK) return false;
        expanded = !expanded;
        return true;
    }

    /** Whether "Full height" was switched on (ASK). */
    public boolean expanded() { return expanded; }

    /** Whether the box spans the whole world height: FULL, or ASK with "Full height" on. */
    public boolean fullHeight(AreaRules rules) {
        return rules.height() == AreaHeight.FULL || (rules.height() == AreaHeight.ASK && expanded);
    }
}
