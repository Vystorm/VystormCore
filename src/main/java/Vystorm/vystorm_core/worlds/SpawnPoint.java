package Vystorm.vystorm_core.worlds;

/**
 * Weltspawn einer Plugin-Welt.
 *
 * Fixed-shape value record: constructor and components remain frozen for compatibility.
 * Add extensible options to WorldSpec or a separate type.
 *
 * @since 0.26.0
 */
public record SpawnPoint(double x, double y, double z, float yaw, float pitch) {}
