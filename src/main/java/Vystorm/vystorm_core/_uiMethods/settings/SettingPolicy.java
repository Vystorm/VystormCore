package Vystorm.vystorm_core._uiMethods.settings;

/** Optional operational metadata, separate from the stable Setting constructor. @since 0.26.5 */
public record SettingPolicy(Activation activation, boolean profileSafe) {
    public enum Activation { IMMEDIATE, RELOAD, RESTART, UNKNOWN }
    public static final SettingPolicy DEFAULT = new SettingPolicy(Activation.UNKNOWN, false);
    public SettingPolicy { java.util.Objects.requireNonNull(activation); }
}
