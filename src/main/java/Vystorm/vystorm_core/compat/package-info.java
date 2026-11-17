/**
 * Compatibility helpers for Paper 1.20.6 and later. Feature detection does not parse server versions.
 * Registry lookups require an initialized Bukkit server; portable dialog operations require the server thread.
 * Optional native implementations live in the adapter source set and are selected by runtime API shape.
 * @since 0.48.0
 */
package Vystorm.vystorm_core.compat;
