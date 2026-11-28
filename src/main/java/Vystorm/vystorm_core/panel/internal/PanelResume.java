package Vystorm.vystorm_core.panel.internal;

import java.util.Map;
import java.util.Objects;

/**
 * Where a player came from (0.71.0, {@code picker.PickReturn}): the panel, its argument and a copy of the session
 * values at the moment of the action. {@code values} is mutable on purpose: a pick handler may write into it before
 * the panel opens again. Server thread. Core-internal.
 */
public record PanelResume(PanelRegistry.Registered reg, String arg, Map<String, Object> values) {
    public PanelResume {
        Objects.requireNonNull(reg, "reg");
        arg = arg == null ? "" : arg;
        Objects.requireNonNull(values, "values");
    }
}
