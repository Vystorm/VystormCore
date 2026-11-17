package Vystorm.vystorm_core.compat.dialog;

public final class TextDialogInput {
    private TextDialogInput() {}
    public record MultilineOptions(int maxLines, int height) {
        public static MultilineOptions create(int maxLines, int height) { return new MultilineOptions(maxLines, height); }
    }
}
