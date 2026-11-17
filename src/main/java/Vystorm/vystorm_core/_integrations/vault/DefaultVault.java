package Vystorm.vystorm_core._integrations.vault;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;

public class DefaultVault implements VaultInterface {
    private final Economy boundEconomy;
    private final String boundIdentity;

    public DefaultVault() { this.boundEconomy = null; this.boundIdentity = null; }

    private DefaultVault(Economy economy, String identity) {
        this.boundEconomy = Objects.requireNonNull(economy);
        this.boundIdentity = Objects.requireNonNull(identity);
    }

    private Economy economy() {
        if (boundEconomy == null) return getEconomy();
        return vaultEnabled() && boundEconomy.isEnabled() ? boundEconomy : null;
    }

    @Override
    public VaultInterface economySnapshot() {
        Economy economy = requireEconomy();
        return new DefaultVault(economy, identity(economy));
    }

    private String identity(Economy economy) {
        if (boundIdentity != null) return boundIdentity;
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        String owner = registration != null && registration.getProvider() == economy
                ? registration.getPlugin().getName() : "Vault";
        return owner + ":" + economy.getName() + ":" + System.identityHashCode(economy);
    }

    @Override
    public String economyIdentity() { return identity(requireEconomy()); }

    @Override
    public int fractionalDigits() { return requireEconomy().fractionalDigits(); }

    // Resolve each time: providers can register late or be replaced at runtime.
    private static boolean vaultEnabled() {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        return vault != null && vault.isEnabled();
    }

    private static <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> registration = Bukkit.getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }

    public static Economy getEconomy() {
        if (!vaultEnabled()) return null;
        Economy economy = service(Economy.class);
        return economy != null && economy.isEnabled() ? economy : null;
    }

    public static Permission getPermissions() {
        return vaultEnabled() ? service(Permission.class) : null;
    }

    public static Chat getChat() {
        return vaultEnabled() ? service(Chat.class) : null;
    }

    @Override
    public boolean isEconomyAvailable() {
        return economy() != null;
    }

    private Economy requireEconomy() {
        Economy economy = economy();
        if (economy == null) {
            DebugMessages.mid("[DefaultVault] Economy operation rejected: Vault provider unavailable");
            throw new IllegalStateException("Vault economy is unavailable");
        }
        return economy;
    }

    private static void validateAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            DebugMessages.high("[DefaultVault] Rejected a negative or non-finite amount");
            throw new IllegalArgumentException("Amount must be finite and non-negative");
        }
    }

    @Override
    public String economyName() {
        return requireEconomy().getName();
    }

    @Override
    public double balance(OfflinePlayer player) {
        Objects.requireNonNull(player, "player");
        return requireEconomy().getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Objects.requireNonNull(player, "player");
        validateAmount(amount);
        Economy economy = economy();
        return economy != null && economy.has(player, amount);
    }

    @Override
    public String format(double amount) {
        validateAmount(amount);
        return requireEconomy().format(amount);
    }

    @Override
    public Transaction withdraw(OfflinePlayer player, double amount) {
        return transact(player, amount, false);
    }

    @Override
    public Transaction deposit(OfflinePlayer player, double amount) {
        return transact(player, amount, true);
    }

    private Transaction transact(OfflinePlayer player, double amount, boolean deposit) {
        Objects.requireNonNull(player, "player");
        validateAmount(amount);
        Economy economy = economy();
        if (economy == null) {
            DebugMessages.mid("[DefaultVault] Payment rejected: Vault provider unavailable");
            return new Transaction(false, 0, 0, "Vault economy is unavailable");
        }
        EconomyResponse result;
        try {
            result = deposit ? economy.depositPlayer(player, amount) : economy.withdrawPlayer(player, amount);
        } catch (RuntimeException failure) {
            DebugMessages.high("[DefaultVault] Provider threw during " + (deposit ? "deposit" : "withdrawal")
                    + "; outcome unknown; failure=" + failure.getClass().getSimpleName());
            throw failure;
        }
        if (result.transactionSuccess()) {
            DebugMessages.low("[DefaultVault] " + (deposit ? "Deposit" : "Withdrawal") + " completed; amount=" + amount);
        } else {
            DebugMessages.mid("[DefaultVault] " + (deposit ? "Deposit" : "Withdrawal") + " rejected by economy provider");
        }
        return new Transaction(result.transactionSuccess(), result.amount, result.balance, result.errorMessage);
    }
}
