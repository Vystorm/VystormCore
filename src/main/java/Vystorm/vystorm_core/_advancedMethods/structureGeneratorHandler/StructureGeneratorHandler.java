package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Registry of synchronous, bounded structure callbacks. The caller owns block access,
 * bounds, thread selection, and any world lifecycle.
 */
public class StructureGeneratorHandler implements StructureGeneratorInterface {
    private record Registration(Plugin owner, StructureGenerator generator) { }

    private final Map<NamespacedKey, Registration> generators = new ConcurrentHashMap<>();

    @Override
    public TreeGenerator tree(StructureAccess access, Random random, TreeSpecies species) {
        return new TreeBuilder(Objects.requireNonNull(access, "access"),
                Objects.requireNonNull(random, "random"), Objects.requireNonNull(species, "species"));
    }

    @Override
    public TreeGenerator tree(StructureAccess access, Random random, TreeSpecies species, TreeGrowthProfile profile) {
        return new TreeBuilder(Objects.requireNonNull(access), Objects.requireNonNull(random),
                Objects.requireNonNull(species), Objects.requireNonNull(profile));
    }

    @Override
    public int heightBonus(TreeSpecies species, int width) {
        Objects.requireNonNull(species, "species");
        if (width < 1 || width > 5) {
            throw new IllegalArgumentException("Tree width must be between 1 and 5");
        }
        return TreeBuilder.heightBonus(species, width);
    }

    @Override
    public int decorateGlowstone(StructureAccess access, Set<StructurePos> changedPositions,
            TreeSpecies species, Random random, int leavesPerBlock, int maxBlocks, int darkLightLevel) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(changedPositions, "changedPositions");
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(random, "random");
        if (changedPositions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Changed positions cannot contain null");
        }
        if (leavesPerBlock < 16 || leavesPerBlock > 1024 || maxBlocks < 1 || maxBlocks > 128
                || darkLightLevel < 0 || darkLightLevel > 15) {
            throw new IllegalArgumentException("Glowstone settings are outside the supported range");
        }
        DebugMessages.low("[StructureGeneratorHandler] Glowstone decoration begin");
        int decorated = GlowstoneDecorator.decorate(access, changedPositions, species, random,
                leavesPerBlock, maxBlocks, darkLightLevel);
        DebugMessages.low("[StructureGeneratorHandler] Glowstone decoration end; blocks=" + decorated);
        return decorated;
    }

    @Override
    public void register(Plugin owner, NamespacedKey id, StructureGenerator generator) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(generator, "generator");
        if (!owner.isEnabled()) {
            DebugMessages.high("[StructureGeneratorHandler] Registration rejected; owner disabled; id=" + id);
            throw new IllegalArgumentException("Owner plugin is disabled");
        }
        Registration candidate = new Registration(owner, generator);
        generators.compute(id, (key, previous) -> {
            if (previous != null && !previous.owner().equals(owner)) {
                DebugMessages.high("[StructureGeneratorHandler] Registration rejected; id owned by another plugin; id=" + id);
                throw new IllegalArgumentException("Structure generator is owned by another plugin: " + id);
            }
            return candidate;
        });
        DebugMessages.low("[StructureGeneratorHandler] Registration stored; id=" + id);
    }

    @Override
    public boolean unregister(Plugin owner, NamespacedKey id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        AtomicBoolean removed = new AtomicBoolean();
        generators.computeIfPresent(id, (key, registration) -> {
            if (registration.owner().equals(owner)) {
                removed.set(true);
                return null;
            }
            return registration;
        });
        if (removed.get()) {
            DebugMessages.low("[StructureGeneratorHandler] Registration removed; id=" + id);
        }
        return removed.get();
    }

    @Override
    public boolean generate(NamespacedKey id, StructureAccess access, StructurePos origin, Random random) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(random, "random");
        Registration registration = generators.get(id);
        if (registration == null || !registration.owner().isEnabled()) {
            return false;
        }
        DebugMessages.low("[StructureGeneratorHandler] Structure generation begin; id=" + id);
        try {
            registration.generator().generate(access, origin, random);
        } catch (RuntimeException | Error failure) {
            DebugMessages.high("[StructureGeneratorHandler] Structure callback failed; id=" + id);
            throw failure;
        }
        DebugMessages.low("[StructureGeneratorHandler] Structure generation end; id=" + id);
        return true;
    }

    @Override
    public Set<NamespacedKey> registeredNames() {
        return Set.copyOf(generators.keySet());
    }

    @Override
    public void removeAll(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        generators.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        DebugMessages.low("[StructureGeneratorHandler] Owner registrations removed");
    }

    @Override
    public void clear() {
        generators.clear();
        DebugMessages.low("[StructureGeneratorHandler] Registry cleared");
    }
}
