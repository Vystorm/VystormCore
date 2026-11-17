package Vystorm.vystorm_core._integrations.vault;

import org.bukkit.OfflinePlayer;

/** Optional Vault economy access. Invoke payments on the server thread. */
public interface VaultInterface {
    record Transaction(boolean successful, double amount, double balance, String error) {}

    default boolean isEconomyAvailable() { return false; }

    default String economyName() { throw new IllegalStateException("Vault economy is unavailable"); }

    /** Process-local quote identity. Mutable provider handlers must change it when the provider changes. */
    default String economyIdentity() {
        return getClass().getName() + ":" + economyName() + ":" + System.identityHashCode(this);
    }

    /** Provider precision; -1 means the provider does not impose a fixed precision. */
    default int fractionalDigits() { return -1; }

    /**
     * Bind a short synchronous operation (including its refund) to the current provider.
     * Implementations with mutable provider selection must return a bound view here.
     * A bound view must reject a disabled provider, never silently switch providers.
     * This does not provide atomicity or durable transaction recovery.
     */
    default VaultInterface economySnapshot() { return this; }

    default double balance(OfflinePlayer player) { throw new IllegalStateException("Vault economy is unavailable"); }

    default boolean has(OfflinePlayer player, double amount) { return false; }

    default String format(double amount) { throw new IllegalStateException("Vault economy is unavailable"); }

    default Transaction withdraw(OfflinePlayer player, double amount) {
        return new Transaction(false, 0, 0, "Vault economy is unavailable");
    }

    default Transaction deposit(OfflinePlayer player, double amount) {
        return new Transaction(false, 0, 0, "Vault economy is unavailable");
    }
}
