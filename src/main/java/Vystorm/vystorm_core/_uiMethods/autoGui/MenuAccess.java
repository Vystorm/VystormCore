package Vystorm.vystorm_core._uiMethods.autoGui;

/** Read-only result of checking a menu for a target player. Server thread only. @since 0.26.5 */
public record MenuAccess(String key, String owner, boolean enabled, boolean allowed, String reason) {}
