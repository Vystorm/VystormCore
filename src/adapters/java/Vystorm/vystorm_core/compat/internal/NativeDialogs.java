package Vystorm.vystorm_core.compat.internal;

import Vystorm.vystorm_core._uiMethods.dialog.DialogButton;
import Vystorm.vystorm_core._uiMethods.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import io.papermc.paper.registry.data.dialog.body.DialogBody;

public final class NativeDialogs {
    private NativeDialogs() {}
    public static DialogRequest convert(Vystorm.vystorm_core.compat.dialog.DialogRequest request) {
        return new DialogRequest(request.title(), request.body().stream().map(b -> b.item() == null
                ? DialogBody.plainMessage(b.contents(), b.width()) : DialogBody.item(b.item()).build()).toList(),
                request.inputs().stream().map(NativeDialogs::input).toList(),
                request.buttons().stream().map(b -> new DialogButton(b.label(), b.tooltip(), (p, r) -> b.action().accept(p,
                        r == null ? null : new Vystorm.vystorm_core.compat.dialog.DialogResponseView() {
                            public String getText(String key) { return r.getText(key); }
                            public Boolean getBoolean(String key) { return r.getBoolean(key); }
                            public Float getFloat(String key) { return r.getFloat(key); }
                        }))).toList(), request.valid(), request.cancel(), request.cancelLabel(), request.columns());
    }
    private static io.papermc.paper.registry.data.dialog.input.DialogInput input(DialogInput in) {
        return switch (in.kind()) {
            case TEXT -> {
                var b = io.papermc.paper.registry.data.dialog.input.DialogInput.text(in.key(), in.label())
                        .initial(in.initial() == null ? "" : in.initial().toString()).width(in.width()).maxLength(in.maxLength());
                if (in.multiline() != null) b.multiline(io.papermc.paper.registry.data.dialog.input.TextDialogInput.MultilineOptions
                        .create(in.multiline().maxLines(), in.multiline().height()));
                yield b.build();
            }
            case BOOL -> io.papermc.paper.registry.data.dialog.input.DialogInput.bool(in.key(), in.label())
                    .initial(Boolean.TRUE.equals(in.initial())).build();
            case RANGE -> {
                var b = io.papermc.paper.registry.data.dialog.input.DialogInput.numberRange(in.key(), in.label(), in.start(), in.end())
                        .width(in.width());
                if (in.initial() instanceof Number n) b.initial(n.floatValue());
                if (in.step() != null) b.step(in.step());
                if (in.labelFormat() != null) b.labelFormat(in.labelFormat());
                yield b.build();
            }
            case OPTIONS -> io.papermc.paper.registry.data.dialog.input.DialogInput.singleOption(in.key(), in.label(),
                    in.entries().stream().map(e -> io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry
                            .create(e.id(), e.display(), e.initial())).toList()).width(in.width()).build();
        };
    }
}
