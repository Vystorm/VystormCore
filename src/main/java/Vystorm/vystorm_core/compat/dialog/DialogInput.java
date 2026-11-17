package Vystorm.vystorm_core.compat.dialog;

import java.util.List;
import net.kyori.adventure.text.Component;

/** Immutable input description, independent of Paper's native dialog classes. */
public record DialogInput(String key, Component label, Kind kind, Object initial, int width, int maxLength,
                          float start, float end, Float step, List<SingleOptionDialogInput.OptionEntry> entries,
                          TextDialogInput.MultilineOptions multiline, String labelFormat) {
    /** Retained constructor for consumers compiled before optional range label formats. */
    public DialogInput(String key,Component label,Kind kind,Object initial,int width,int maxLength,float start,float end,Float step,
                       List<SingleOptionDialogInput.OptionEntry> entries,TextDialogInput.MultilineOptions multiline) {
        this(key,label,kind,initial,width,maxLength,start,end,step,entries,multiline,null);
    }
    public enum Kind { TEXT, BOOL, RANGE, OPTIONS }
    public static Builder text(String key, Component label) { return new Builder(key, label, Kind.TEXT); }
    public static Builder bool(String key, Component label) { return new Builder(key, label, Kind.BOOL); }
    public static Builder numberRange(String key, Component label, float start, float end) {
        Builder b = new Builder(key, label, Kind.RANGE); b.start = start; b.end = end; return b;
    }
    public static Builder singleOption(String key, Component label, List<SingleOptionDialogInput.OptionEntry> entries) {
        Builder b = new Builder(key, label, Kind.OPTIONS); b.entries = List.copyOf(entries); return b;
    }
    public static final class Builder {
        private final String key; private final Component label; private final Kind kind;
        private Object initial; private int width = 200, maxLength = 32;
        private float start, end; private Float step;
        private List<SingleOptionDialogInput.OptionEntry> entries = List.of();
        private TextDialogInput.MultilineOptions multiline;
        private String labelFormat;
        private Builder(String key, Component label, Kind kind) { this.key = key; this.label = label; this.kind = kind; }
        public Builder initial(Object initial) { this.initial = initial; return this; }
        public Builder width(int width) { this.width = width; return this; }
        public Builder maxLength(int length) { this.maxLength = length; return this; }
        public Builder step(float step) { this.step = step; return this; }
        public Builder labelFormat(String format) { this.labelFormat = format; return this; }
        public Builder multiline(TextDialogInput.MultilineOptions value) { this.multiline = value; return this; }
        public DialogInput build() { return new DialogInput(key, label, kind, initial, width, maxLength, start, end, step, entries, multiline,labelFormat); }
    }
}
