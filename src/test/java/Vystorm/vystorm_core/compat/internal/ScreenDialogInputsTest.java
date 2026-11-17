package Vystorm.vystorm_core.compat.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.SingleOptionDialogInput;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class ScreenDialogInputsTest {
    private static DialogRequest request(DialogInput... inputs) {
        return new DialogRequest(Component.text("Pay"), List.of(), List.of(inputs), List.of(), p -> true, () -> { });
    }

    @Test void givenInputs_whenReadingStartValues_thenEachKindGetsItsType() {
        var options = List.of(SingleOptionDialogInput.OptionEntry.create("a", Component.text("A"), false),
                SingleOptionDialogInput.OptionEntry.create("b", Component.text("B"), true));
        Map<String, Object> values = ScreenDialogs.initialValues(request(
                DialogInput.text("name", Component.text("Name")).initial("Steve").build(),
                DialogInput.text("note", Component.text("Note")).build(),
                DialogInput.bool("public", Component.text("Public")).initial(true).build(),
                DialogInput.numberRange("amount", Component.text("Amount"), 1, 64).initial(200f).build(),
                DialogInput.singleOption("mode", Component.text("Mode"), options).build()));
        assertEquals("Steve", values.get("name"));
        assertEquals("", values.get("note"));
        assertEquals(Boolean.TRUE, values.get("public"));
        assertEquals(64f, values.get("amount"), "start value is kept inside the range");
        assertEquals("b", values.get("mode"), "the entry marked initial");
        assertEquals(List.of("name", "note", "public", "amount", "mode"), List.copyOf(values.keySet()), "field order");
    }

    @Test void givenRanges_whenStepping_thenStepAndLimitsFit() {
        DialogInput whole = DialogInput.numberRange("n", Component.text("N"), 0, 100).build();
        DialogInput small = DialogInput.numberRange("n", Component.text("N"), 0, 1).build();
        DialogInput own = DialogInput.numberRange("n", Component.text("N"), 0, 100).step(2.5f).build();
        assertEquals(5f, ScreenDialogs.step(whole));
        assertEquals(0.05f, ScreenDialogs.step(small), 1e-6);
        assertEquals(2.5f, ScreenDialogs.step(own));
        assertEquals(100f, ScreenDialogs.clamp(whole, 140));
        assertEquals(0f, ScreenDialogs.clamp(whole, -3));
    }

    @Test void givenNumbers_whenShown_thenWholeOnesHaveNoDecimals() {
        assertEquals("12", ScreenDialogs.number(12f));
        assertEquals("0.25", ScreenDialogs.number(0.25f));
        assertEquals("-3", ScreenDialogs.number(-3f));
    }
}
