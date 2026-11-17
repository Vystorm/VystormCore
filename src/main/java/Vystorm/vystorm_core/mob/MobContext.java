package Vystorm.vystorm_core.mob;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Zusammengefasster Laufzeitkontext eines Mobs. Pro Art gilt die Ebene mit höchster Priorität; CombatRules
 * werden über alle Ebenen restriktiv kombiniert, AiRules kommen von der höchstpriorisierten Ebene mit Vorgabe.
 */
public final class MobContext {
    public static final MobContext EMPTY = new MobContext(List.of(), CombatRules.OPEN_WORLD);

    private final Map<ContextKind, ContextLayer> byKind = new EnumMap<>(ContextKind.class);
    private final List<ContextLayer> layers;
    private final CombatRules combatRules;
    private final AiRules aiRules;
    private final List<Objective> objectives;
    private final Set<String> tags;

    public MobContext(List<ContextLayer> input, CombatRules base) {
        List<ContextLayer> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt(ContextLayer::priority).reversed());
        this.layers = List.copyOf(sorted);
        CombatRules rules = null;
        AiRules ai = null;
        List<Objective> goals = new ArrayList<>();
        Set<String> allTags = new HashSet<>();
        for (ContextLayer layer : sorted) {
            byKind.putIfAbsent(layer.kind(), layer);
            if (layer.combatRules() != null) rules = rules == null ? layer.combatRules() : rules.restrict(layer.combatRules());
            if (ai == null && layer.aiRules() != null) ai = layer.aiRules();
            goals.addAll(layer.objectives());
            allTags.addAll(layer.tags());
            allTags.add(layer.kind().name());
        }
        goals.sort(Comparator.comparingInt(Objective::priority).reversed());
        this.combatRules = rules == null ? base : rules;
        this.aiRules = ai == null ? AiRules.DEFAULT : ai;
        this.objectives = List.copyOf(goals);
        this.tags = Set.copyOf(allTags);
    }

    public Optional<ContextLayer> layer(ContextKind kind) { return Optional.ofNullable(byKind.get(kind)); }

    public boolean has(ContextKind kind) { return byKind.containsKey(kind); }

    public List<ContextLayer> layers() { return layers; }

    public CombatRules combatRules() { return combatRules; }

    public AiRules aiRules() { return aiRules; }

    public List<Objective> objectives() { return objectives; }

    public Set<String> tags() { return tags; }

    public Optional<ContextLayer> arena() { return layer(ContextKind.ARENA); }

    public Optional<ContextLayer> city() { return layer(ContextKind.CITY); }

    public Optional<ContextLayer> event() { return layer(ContextKind.EVENT); }

    /** Team laut Arena-/Event-Kontext, sonst leer. */
    public Optional<String> team() {
        for (ContextLayer layer : layers) {
            String team = layer.attributes().get("team");
            if (team != null && !team.isBlank()) return Optional.of(team);
        }
        return Optional.empty();
    }
}
