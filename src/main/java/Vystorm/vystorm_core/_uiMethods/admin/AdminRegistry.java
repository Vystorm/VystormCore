package Vystorm.vystorm_core._uiMethods.admin;

import org.bukkit.plugin.Plugin;
import java.util.*;

public final class AdminRegistry implements AdminInterface {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private record Entry(Plugin owner, AdminModule module) {}
    @Override public synchronized void register(Plugin owner, AdminModule module) {
        Objects.requireNonNull(owner); Objects.requireNonNull(module);
        if (!owner.getName().equals(module.plugin())) throw new IllegalArgumentException("module plugin must match owner");
        Entry previous=entries.get(module.id());
        if(previous!=null&&previous.owner!=owner) throw new IllegalArgumentException("module id belongs to another owner");
        if(previous==null&&entries.size()>=128) throw new IllegalStateException("too many admin modules");
        entries.put(module.id(), new Entry(owner, module));
    }
    @Override public synchronized void unregister(Plugin owner) { entries.entrySet().removeIf(e -> e.getValue().owner == owner); }
    @Override public synchronized List<AdminModule> modules() { return entries.values().stream().map(Entry::module).toList(); }
    @Override public synchronized Optional<Plugin> owner(String moduleId) { Entry e=entries.get(moduleId); return Optional.ofNullable(e).map(Entry::owner); }
    @Override public synchronized void clear() { entries.clear(); }
}
