package Vystorm.vystorm_core._advancedMethods.interactionGeneratorHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.function.Consumer;

/** Temporary hitboxes owned by a plugin; all methods must run on the server thread. */
public class InteractionGeneratorHandler implements InteractionGeneratorInterface {
    private static final class Registration {
        final Plugin owner;
        final Interaction entity;
        final Consumer<Player> rightClick, attack;
        UUID lastPlayer;
        int lastTick;
        boolean lastAttack;
        Registration(Plugin owner, Interaction entity, Consumer<Player> rightClick, Consumer<Player> attack) {
            this.owner = owner; this.entity = entity; this.rightClick = rightClick; this.attack = attack;
        }
    }
    private final Map<UUID, Registration> entries = new HashMap<>();
    private void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Interaction entities require the server thread");
    }
    @Override public Interaction spawn(Plugin owner, Location location, float width, float height,
                                       Consumer<Player> rightClick, Consumer<Player> attack) {
        thread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(location, "location").checkFinite();
        if (!owner.isEnabled()) throw new IllegalArgumentException("Owner plugin is disabled");
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Hitbox dimensions must be finite and positive");
        }
        if (rightClick == null && attack == null) throw new IllegalArgumentException("At least one callback is required");
        Interaction entity = Objects.requireNonNull(location.getWorld(), "world").spawn(location, Interaction.class, created -> {
            created.setInteractionWidth(width); created.setInteractionHeight(height);
            created.setResponsive(true); created.setPersistent(false);
        });
        entries.put(entity.getUniqueId(), new Registration(owner, entity, rightClick, attack));
        DebugMessages.mid("[InteractionGeneratorHandler] Interaction hitbox created; owner=" + owner.getName());
        return entity;
    }
    @Override public Interaction get(UUID id) {
        thread();
        Registration entry = entries.get(id);
        if (entry == null) return null;
        // A freshly spawned entity can be alive before its chunk begins tracking it.
        if (entry.entity.isDead()) { entries.remove(id); return null; }
        return entry.entity;
    }
    @Override public boolean remove(UUID id) {
        thread();
        Registration entry = entries.remove(id);
        if (entry == null) return false;
        entry.entity.remove();
        DebugMessages.mid("[InteractionGeneratorHandler] Interaction hitbox removed");
        return true;
    }
    @Override public boolean handle(UUID id, Player player, boolean attack) {
        thread(); Objects.requireNonNull(player, "player");
        Registration entry = entries.get(id);
        if (entry == null || get(id) == null) return false;
        if (!entry.owner.isEnabled()) { remove(id); return false; }
        Consumer<Player> action = attack ? entry.attack : entry.rightClick;
        if (action == null) return false;
        int tick = Vystorm.vystorm_core.compat.PlatformCompat.getCurrentTick();
        if (player.getUniqueId().equals(entry.lastPlayer) && tick == entry.lastTick && attack == entry.lastAttack) return true;
        entry.lastPlayer = player.getUniqueId(); entry.lastTick = tick; entry.lastAttack = attack;
        try { action.accept(player); }
        catch (RuntimeException failure) {
            DebugMessages.high("[InteractionGeneratorHandler] Interaction callback failed; owner=" + entry.owner.getName());
            throw failure;
        }
        DebugMessages.low("[InteractionGeneratorHandler] Interaction callback executed; attack=" + attack);
        return true;
    }
    @Override public void removeAll(Plugin owner) {
        thread();
        for (UUID id : new ArrayList<>(entries.keySet())) if (entries.get(id).owner.equals(owner)) remove(id);
    }
    @Override public void clear() {
        thread();
        for (UUID id : new ArrayList<>(entries.keySet())) remove(id);
    }
}
