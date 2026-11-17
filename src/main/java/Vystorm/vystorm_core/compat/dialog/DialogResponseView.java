package Vystorm.vystorm_core.compat.dialog;

/** Values submitted by either the native dialog or the legacy chat form. */
public interface DialogResponseView {
    String getText(String key);
    Boolean getBoolean(String key);
    Float getFloat(String key);
}
