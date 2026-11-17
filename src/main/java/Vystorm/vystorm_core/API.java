package Vystorm.vystorm_core;

import Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler.WorldGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.particleHandler.ParticleInterface;
import Vystorm.vystorm_core._advancedMethods.interactionGeneratorHandler.InteractionGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core._uiMethods.commands.CommandInterface;
import Vystorm.vystorm_core._uiMethods.admin.AdminInterface;
import Vystorm.vystorm_core._uiMethods.admin.AdminRegistry;
import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface;
import Vystorm.vystorm_core._coreMethods.dataStorage.DataStorageInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugInterface;
import Vystorm.vystorm_core._coreMethods.eventRegulator.PriorityInterface;
import Vystorm.vystorm_core._advancedMethods.inventoryInteractor.InteractorInterface;
import Vystorm.vystorm_core._integrations.luckpermsIntegration.LuckpermsInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.MessageInterface;
import Vystorm.vystorm_core._coreMethods.eventRegulator.NBTInterface;
import Vystorm.vystorm_core._coreMethods.inventoryEventHandler.InventoryEventInterface;
import Vystorm.vystorm_core._coreMethods.playerHandler.PlayerHandlerInterface;
import Vystorm.vystorm_core._coreMethods.ressourceLoader.RessourceInterface;
import Vystorm.vystorm_core._integrations.vault.VaultInterface;
import Vystorm.vystorm_core.singeltons.*;
import org.bukkit.plugin.Plugin;

import static Vystorm.vystorm_core.main.getPlugin;

/**
 * Central API class of the Vystorm Core plugin.
 * <p>
 * This class is the hub of Vystorm Core: every area is a manager reached through one accessor here. Each manager
 * is held by a holder and can be replaced at runtime through the {@code override...} methods.
 * </p>
 * <p>
 * Since 0.64.0 the features that used to be reachable only through free-standing static classes
 * ({@code PlatformCompat}, {@code AtomicFiles}, {@code ConfigEditor}, {@code ChatActions}, {@code Panels} ...) are
 * parts of these managers: folded into the manager they belong to ({@code data().files()},
 * {@code settings().config()}, {@code message().chat()}, {@code gui().panels()} ...) or, where no manager covered
 * the area, behind a new one ({@link #compat()}, {@link #tasks()}, {@link #loot()}, {@link #web()},
 * {@link #parties()}, {@link #items()}, {@link #mobs()}, {@link #runtime()}). The static classes remain and behave
 * as before. The table is in {@code docs/API.md}, section "The managers".
 * </p>
 *
 * <h2>Available subsystems</h2>
 * <ul>
 *     <li><b>Debug</b> - debugging and logging</li>
 *     <li><b>Data</b> - data storage and management</li>
 *     <li><b>Player</b> - player management and player data</li>
 *     <li><b>Command</b> - command registration and handling</li>
 *     <li><b>Luckperms</b> - permission checks with wildcards through the Bukkit permission API (no LuckPerms API)</li>
 *     <li><b>Vault</b> - Vault integration for economy and permissions</li>
 *     <li><b>GUI</b> - automatic GUI creation and management</li>
 *     <li><b>InventoryInteractor</b> - inventory interaction management</li>
 *     <li><b>Message</b> - message parsing and formatting</li>
 *     <li><b>Ressource</b> - resource loading and management</li>
 *     <li><b>Database</b> - database access (MySQL, MariaDB, etc.)</li>
 * </ul>
 *
 * <h2>Thread contract</h2>
 * The legacy handlers ({@code data()}, {@code nbt()}, {@code player()}, {@code ressource()}, {@code message()},
 * {@code gui()}) partly use unguarded maps and must only be called from the server thread. The newer services
 * ({@code ItemServices}, {@code MobServices}, {@code RuntimeContextHub} …) each document their own contract.
 * Version and capability query: {@link CoreApi}.
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * API api = CoreApi.api().orElseThrow();   // empty while Core is not enabled
 *
 * // write a debug message
 * api.debug().mid("[MyPlugin] Started", LocalDateTime.now());
 *
 * // read a player setting
 * String value = api.player().getSetting(player.getUniqueId(), "my-setting");
 *
 * // open the menu directory
 * api.gui().openDirectory(player, 0);
 * }</pre>
 *
 * @author Vystorm
 * @see DebugInterface
 * @see DataStorageInterface
 * @see DataBaseInterface
 */
public class API {
    /** Owner-bound event registration and execution, independent of WorldForge or a storyteller. */
    public Vystorm.vystorm_core.worldevent.WorldEvents events() { return Vystorm.vystorm_core.worldevent.WorldEvents.get(); }

    private final AreaSingelton areaSystem = new AreaSingelton();
    private final StructureGeneratorSingelton structureGeneratorSystem = new StructureGeneratorSingelton();
    private final WorldGeneratorSingelton worldGeneratorSystem = new WorldGeneratorSingelton();
    private final ParticleSingelton particleSystem = new ParticleSingelton();
    private final InteractionGeneratorSingelton interactionGeneratorSystem = new InteractionGeneratorSingelton();
    private final PackageSingelton packagesSystem = new PackageSingelton();


    /** The version of the API */
    private final String version;

    /** Reference to the Bukkit plugin */
    private final Plugin plugin;

    /** Singleton for the debug system */
    private final DebugSystemSingelton dbs;

    /** Singleton for the data storage system */
    private final DataSystemSingelton fss;

    /** Singleton for player data management */
    private final PlayerDataSingelton pds;

    /** Singleton for the command handler */
    private final CommandHandlerSingelton cmS;

    /** Singleton for the permission checks (named after LuckPerms, uses the Bukkit permission API) */
    private final LuckpermsSingelton lps;

    /** Singleton for the Vault integration */
    private final VaultSingelton vas;

    /** Singleton for the GUI system */
    private final GuiSingelton gui;
    private final DialogSingelton dialogs;
    private final AdminRegistry admin = new AdminRegistry();
    private Vystorm.vystorm_core._uiMethods.settings.SettingsInterface settings;

    /** Singleton for the inventory interactor */
    private final InventoryInteractorSingelton lis;

    /** Singleton for the message system */
    private final MessageSingelton mss;

    /** Singleton for the resource loader */
    private final RessourceSingelton ressource;

    /** Singleton for the database access */
    private final DataBaseSingelton dbase;

    private final NBTSingelton nbt;

    private final PrioritySingelton pry;

    /** Singleton for coalesced, post-operation inventory change notifications. */
    private final InventoryEventSingelton inventoryEvents;

    /**
     * Creates a new API instance with the given version.
     * <p>
     * Initializes all subsystems and their singletons. The plugin reference
     * is taken from {@link main#getPlugin()}.
     * </p>
     *
     * @param version the version label of the API (e.g. "1.0.0")
     */
    public API(String version) {
        this.version = version;
        this.plugin = getPlugin();
        this.dbs = new DebugSystemSingelton();
        this.fss = new DataSystemSingelton();
        this.pds = new PlayerDataSingelton();
        this.cmS = new CommandHandlerSingelton();
        this.lps = new LuckpermsSingelton();
        this.vas = new VaultSingelton();
        this.gui = new GuiSingelton();
        this.dialogs = new DialogSingelton();
         this.lis = new InventoryInteractorSingelton();
        this.mss = new MessageSingelton();
        this.ressource = new RessourceSingelton();
        this.dbase = new DataBaseSingelton();
        this.nbt = new NBTSingelton();
        this.pry = new PrioritySingelton();
        this.inventoryEvents = new InventoryEventSingelton();

    }

    /**
     * Returns the debug interface.
     * <p>
     * Gives access to debugging: logging, debug messages
     * and error records.
     * </p>
     *
     * @return the {@link DebugInterface} for debugging
     */
    public DebugInterface debug() {
        return dbs.getDebugger();
    }

    /**
     * Returns the data storage interface.
     * <p>
     * Gives access to the data storage system for saving and loading
     * configuration data and other persistent data.
     * </p>
     *
     * @return the {@link DataStorageInterface} for data storage
     */
    public DataStorageInterface data() {
        return fss.getInstance();
    }

    /**
     * Returns the player handler interface.
     * <p>
     * Gives access to player management: player data,
     * settings and statistics.
     * </p>
     *
     * @return the {@link PlayerHandlerInterface} for player operations
     */
    public PlayerHandlerInterface player() {
        return pds.getInstance();
    }

    public void overridePlayerHandler(PlayerHandlerInterface replacement) {
        pds.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overridePlayerHandler");
    }

    /**
     * Returns the command interface.
     * <p>
     * Registers and manages commands, including
     * tab completion and command handling.
     * </p>
     *
     * @return the {@link CommandInterface} for command operations
     */
    public CommandInterface command() {
        return cmS.getInstance();
    }

    /**
     * Returns the permission interface.
     * <p>
     * Permission checks with wildcards through the Bukkit permission API.
     * The name is historical; no LuckPerms API is used.
     * </p>
     *
     * @return the {@link LuckpermsInterface} for permission checks
     */
    public LuckpermsInterface luckperms() {
        return lps.getInstance();
    }

    /**
     * Returns the Vault interface.
     * <p>
     * Vault integration for economy and
     * permission functions.
     * </p>
     *
     * @return the {@link VaultInterface} for Vault operations
     */
    public VaultInterface vault() {
        return vas.getInstance();
    }

    /**
     * Returns the GUI interface.
     * <p>
     * Creates and manages automatic GUIs
     * for player interaction.
     * </p>
     *
     * @return the {@link GuiInteface} for GUI operations
     */
    public GuiInteface gui() {
        return gui.getInstance();
    }

    /** Runtime-only registry for curated third-party admin dashboard modules. */
    public AdminInterface admin() { return admin; }

    /**
     * Central settings registry: register PLAYER sections (personal preferences) and SERVER sections
     * (config, admins only); Core shows them as native dialogs under /vmenu → Einstellungen and the pause menu.
     */
    public Vystorm.vystorm_core._uiMethods.settings.SettingsInterface settings() {
        if (settings == null) settings = new Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry(
                Vystorm.vystorm_core.main.getPlugin(), this::dialogs, p -> gui().openDirectory(p, 0));
        return settings;
    }

    public void overrideSettings(Vystorm.vystorm_core._uiMethods.settings.SettingsInterface replacement) {
        java.util.Objects.requireNonNull(replacement, "replacement");
        if (settings != null && settings != replacement) settings.clear();
        settings = replacement;
        DebugMessages.mid("[API] Handler override applied: overrideSettings");
    }

    /**
     * Returns the inventory interactor interface.
     * <p>
     * Manages inventory interactions
     * and click events.
     * </p>
     *
     * @return the {@link InteractorInterface} for inventory interaction
     */
    public InteractorInterface inventoryInteractor() {
        return lis.getInstance();
    }

    /**
     * Returns post-operation inventory change notifications. Watch registrations and signals must
     * be made on the server thread; callbacks are coalesced and run on the following tick.
     */
    public InventoryEventInterface inventoryEvents() { return inventoryEvents.getInstance(); }

    public void overrideInventoryEventHandler(InventoryEventInterface replacement) {
        inventoryEvents.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideInventoryEventHandler");
    }

    /**
     * Returns the message interface.
     * <p>
     * Parses and formats messages
     * with colours and placeholders.
     * </p>
     *
     * @return the {@link MessageInterface} for message operations
     */
    public MessageInterface message() {
        return mss.getInstance();
    }

    /**
     * Returns the resource interface.
     * <p>
     * Loads and manages plugin resources
     * such as configuration and language files.
     * </p>
     *
     * @return the {@link RessourceInterface} for resource operations
     */
    public RessourceInterface ressource() {
        return ressource.getInstance();
    }

    /**
     * Returns the database interface.
     * <p>
     * Database operations for
     * MySQL, MariaDB and other database systems.
     * </p>
     *
     * @return the {@link DataBaseInterface} for database operations
     */
    public DataBaseInterface database() {
        return dbase.getInstance();
    }

    /**
     * Localization: per-plugin language bundles ({@code lang/<code>.yml}), language choice per viewer, MiniMessage
     * formatting with named placeholders. Thread-safe. Capability {@link CoreApi.Capability#LOCALIZATION}.
     *
     * @since 0.22.0
     */
    public Vystorm.vystorm_core.lang.Localization lang() {
        return Vystorm.vystorm_core.lang.Localization.get();
    }

    public NBTInterface nbt() {
        return nbt.getInstance();
    }

    public PriorityInterface priority() {
        return pry.getInstance();
    }

    public void overrideNBT(NBTInterface newMethod) {
        nbt.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideNBT");
    }

    /**
     * Replaces the database handler with your own implementation.
     * <p>
     * The default database handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link DataBaseInterface} implementation
     */
    public void overrideDatabase(DataBaseInterface newMethod) {
        dbase.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideDatabase");
    }

    /**
     * Replaces the data storage handler with your own implementation.
     * <p>
     * The default data storage handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link DataStorageInterface} implementation
     */
    public void overrideDataStorage(DataStorageInterface newMethod) {
        fss.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideDataStorage");
    }

    /**
     * Replaces the debug handler with your own implementation.
     * <p>
     * The default debug handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link DebugInterface} implementation
     */
    public void overrideDebugger(DebugInterface newMethod) {
        dbs.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideDebugger");
    }

    /**
     * Replaces the command handler with your own implementation.
     * <p>
     * The default command handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link CommandInterface} implementation
     */
    public void overrideCommandHandler(CommandInterface newMethod) {
        cmS.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideCommandHandler");
    }

    /**
     * Replaces the permission handler with your own implementation.
     * <p>
     * The default permission handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link LuckpermsInterface} implementation
     */
    public void overrideLuckpermsHandler(LuckpermsInterface newMethod) {
        lps.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideLuckpermsHandler");
    }

    /**
     * Replaces the Vault handler with your own implementation.
     * <p>
     * The default Vault handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link VaultInterface} implementation
     */
    public void overrideVaultHandler(VaultInterface newMethod) {
        vas.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideVaultHandler");
    }

    /**
     * Replaces the GUI handler with your own implementation.
     * <p>
     * The default GUI handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link GuiInteface} implementation
     */
    public void overrideGuiHandler(GuiInteface newMethod) {
        gui.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideGuiHandler");
    }

    /** Registers the owner's bundled tutorial.yml as a read-only native guide in /vmenu. */
    public void registerTutorial(org.bukkit.plugin.Plugin owner) {
        Vystorm.vystorm_core._uiMethods.tutorial.TutorialGuide.register(this, owner);
    }

    public DialogInterface dialogs() { return dialogs.getInstance(); }

    public void overrideDialogHandler(DialogInterface replacement) {
        dialogs.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideDialogHandler");
    }

    /**
     * Replaces the inventory interactor handler with your own implementation.
     * <p>
     * The default inventory interactor handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link InteractorInterface} implementation
     */
    public void overrideInventoryInteractorHandler(InteractorInterface newMethod) {
        lis.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideInventoryInteractorHandler");
    }

    /**
     * Replaces the message handler with your own implementation.
     * <p>
     * The default message handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link MessageInterface} implementation
     */
    public void overrideMessageHandler(MessageInterface newMethod) {
        mss.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideMessageHandler");
    }

    /**
     * Replaces the resource handler with your own implementation.
     * <p>
     * The default resource handler is replaced at runtime.
     * </p>
     *
     * @param newMethod the new {@link RessourceInterface} implementation
     */
    public void overrideRessourceHandler(RessourceInterface newMethod) {
        ressource.override(newMethod);
        DebugMessages.mid("[API] Handler override applied: overrideRessourceHandler");
    }

    public AreaInterface area() { return areaSystem.getInstance(); }
    public void overrideAreaHandler(AreaInterface replacement) {
        areaSystem.override(replacement);
        DebugMessages.mid("[API] Handler override applied: overrideAreaHandler");
    }

    public WorldGeneratorInterface worldGenerator() { return worldGeneratorSystem.getInstance(); }
    public void overrideWorldGeneratorHandler(WorldGeneratorInterface replacement) {
        worldGeneratorSystem.override(replacement);
        DebugMessages.mid("[API] Handler override applied: overrideWorldGeneratorHandler");
    }

    public ParticleInterface particle() { return particleSystem.getInstance(); }
    public void overrideParticleHandler(ParticleInterface replacement) {
        particleSystem.override(replacement);
        DebugMessages.mid("[API] Handler override applied: overrideParticleHandler");
    }

    public InteractionGeneratorInterface interactionGenerator() { return interactionGeneratorSystem.getInstance(); }
    public void overrideInteractionGeneratorHandler(InteractionGeneratorInterface replacement) {
        interactionGeneratorSystem.override(replacement);
        DebugMessages.mid("[API] Handler override applied: overrideInteractionGeneratorHandler");
    }

    public PackageInterface packages() { return packagesSystem.getInstance(); }
    public void overridePackageHandler(PackageInterface replacement) {
        packagesSystem.override(replacement);
        DebugMessages.mid("[API] Handler override applied: overridePackageHandler");
    }

    public StructureGeneratorInterface structureGenerator() { return structureGeneratorSystem.getInstance(); }
    public void overrideStructureGeneratorHandler(StructureGeneratorInterface replacement) {
        structureGeneratorSystem.override(replacement);
        DebugMessages.mid("[API] Handler override applied: overrideStructureGeneratorHandler");
    }

    // ---- Managers added in 0.64.0. Each one delegates to the static classes that were the only entry before. ----

    private final ManagerHolder<Vystorm.vystorm_core.compat.CompatInterface> compatSystem =
            new ManagerHolder<>(new Vystorm.vystorm_core.compat.CompatInterface() {});
    private final ManagerHolder<Vystorm.vystorm_core.schedule.TaskGroupInterface> taskSystem =
            new ManagerHolder<>(Vystorm.vystorm_core.schedule.TaskGroupInterface.DEFAULT);
    private final ManagerHolder<Vystorm.vystorm_core.loot.LootInterface> lootSystem =
            new ManagerHolder<>(new Vystorm.vystorm_core.loot.LootInterface() {});
    private final ManagerHolder<Vystorm.vystorm_core.web.WebInterface> webSystem =
            new ManagerHolder<>(new Vystorm.vystorm_core.web.WebInterface() {});
    private final ManagerHolder<Vystorm.vystorm_core.party.PartyServicesInterface> partySystem =
            new ManagerHolder<>(Vystorm.vystorm_core.party.PartyServicesInterface.DEFAULT);
    private final ManagerHolder<Vystorm.vystorm_core.item.ItemsInterface> itemSystem =
            new ManagerHolder<>(new Vystorm.vystorm_core.item.ItemsInterface() {});
    private final ManagerHolder<Vystorm.vystorm_core.mob.MobsInterface> mobSystem =
            new ManagerHolder<>(new Vystorm.vystorm_core.mob.MobsInterface() {});
    private final ManagerHolder<Vystorm.vystorm_core.runtime.RuntimeInterface> runtimeSystem =
            new ManagerHolder<>(new Vystorm.vystorm_core.runtime.RuntimeInterface() {});

    /**
     * Differences between Paper 1.20.6 to 26.x and Spigot: {@code platform()}, {@code server()}, {@code inventory()},
     * {@code gameplay()}, {@code world()}, {@code storage()}, {@code items()}, {@code navigation()} and smaller groups.
     * Capability {@link CoreApi.Capability#MANAGERS}.
     *
     * @since 0.64.0
     */
    public Vystorm.vystorm_core.compat.CompatInterface compat() { return compatSystem.getInstance(); }

    public void overrideCompatHandler(Vystorm.vystorm_core.compat.CompatInterface replacement) {
        compatSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideCompatHandler");
    }

    /** Task groups of a plugin part: {@code api.tasks().of(plugin, name)}. @since 0.64.0 */
    public Vystorm.vystorm_core.schedule.TaskGroupInterface tasks() { return taskSystem.getInstance(); }

    public void overrideTaskHandler(Vystorm.vystorm_core.schedule.TaskGroupInterface replacement) {
        taskSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideTaskHandler");
    }

    /** Shared loot tables, rolls, mob drops, luck and reward pouches. @since 0.64.0 */
    public Vystorm.vystorm_core.loot.LootInterface loot() { return lootSystem.getInstance(); }

    public void overrideLootHandler(Vystorm.vystorm_core.loot.LootInterface replacement) {
        lootSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideLootHandler");
    }

    /** Web modules, login links, live events and the optional client mod. @since 0.64.0 */
    public Vystorm.vystorm_core.web.WebInterface web() { return webSystem.getInstance(); }

    public void overrideWebHandler(Vystorm.vystorm_core.web.WebInterface replacement) {
        webSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideWebHandler");
    }

    /** Parties of players, whichever plugin provides them. @since 0.64.0 */
    public Vystorm.vystorm_core.party.PartyServicesInterface parties() { return partySystem.getInstance(); }

    public void overridePartyHandler(Vystorm.vystorm_core.party.PartyServicesInterface replacement) {
        partySystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overridePartyHandler");
    }

    /** Managed items, item references, item locks and MMOItems. @since 0.64.0 */
    public Vystorm.vystorm_core.item.ItemsInterface items() { return itemSystem.getInstance(); }

    public void overrideItemHandler(Vystorm.vystorm_core.item.ItemsInterface replacement) {
        itemSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideItemHandler");
    }

    /** Mob provider, mob extensions, mob references, relations and MythicMobs. @since 0.64.0 */
    public Vystorm.vystorm_core.mob.MobsInterface mobs() { return mobSystem.getInstance(); }

    public void overrideMobHandler(Vystorm.vystorm_core.mob.MobsInterface replacement) {
        mobSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideMobHandler");
    }

    /** Cross-plugin hubs: context, actions, combat policies, threat and curves. @since 0.64.0 */
    public Vystorm.vystorm_core.runtime.RuntimeInterface runtime() { return runtimeSystem.getInstance(); }

    public void overrideRuntimeHandler(Vystorm.vystorm_core.runtime.RuntimeInterface replacement) {
        runtimeSystem.override(java.util.Objects.requireNonNull(replacement, "replacement"));
        DebugMessages.mid("[API] Handler override applied: overrideRuntimeHandler");
    }

    public void releaseAdvancedResources(Plugin owner) {
        dialogs().removeAll(owner);
        gui().unregisterMenus(owner);
        if (settings != null) settings.unregister(owner);
        inventoryEvents.removeAll(owner);
        structureGeneratorSystem.removeAll(owner);
        interactionGeneratorSystem.removeAll(owner);
        packagesSystem.removeAll(owner);
    }

    public void shutdownAdvanced() {
        Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages.shared().clear();
        admin.clear();
        if (settings != null) settings.clear();
        dialogs().clear();
        gui().clearManaged();
        inventoryEvents.clear();
        try { interactionGeneratorSystem.clear(); }
        finally {
            try { packagesSystem.clear(); }
            finally {
                try { areaSystem.clear(); }
                finally { structureGeneratorSystem.clear(); }
            }
        }
    }

    /** Close after all other shutdown operations, which can still emit debug messages. */
    public void shutdownDebugger() { dbs.close(); }
}
