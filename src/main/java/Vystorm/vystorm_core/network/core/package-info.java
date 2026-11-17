/**
 * Platform-neutral network hub core (frames, keys, secure channel, pairing, hub/client, routing).
 *
 * <p>Runs on Paper, Velocity and BungeeCord alike, so it may only use JDK classes. {@code NetworkArchitectureTest}
 * enforces that no Bukkit, Paper or non-network Core class is referenced from here or from the proxy adapters.
 */
package Vystorm.vystorm_core.network.core;
