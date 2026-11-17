package Vystorm.vystorm_core.compat;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit implementation of expiring, unguessable Core chat callbacks. @since 0.51.0
 * <p>Preferred access: {@code api.compat().clicks()}.
 */
public final class ClickActions implements Listener {
    private static final String PREFIX="/vcoreclick ";
    private static final Map<String, Entry> ACTIONS=new ConcurrentHashMap<>();
    private static Plugin owner;
    private record Entry(Plugin provider,Consumer<Player> action,long expires,int uses) {}
    private ClickActions() {}
    public static void initialize(Plugin core) { owner=core;Bukkit.getPluginManager().registerEvents(new ClickActions(),core);Bukkit.getScheduler().runTaskTimer(core,ClickActions::purge,1200,1200); }
    public static void shutdown() { ACTIONS.clear();owner=null; }
    public static ClickEvent callback(Consumer<Player> action,int uses,Duration lifetime) {
        Plugin provider=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass).map(ClickActions::providingPlugin)
                .filter(p -> p!=null && p!=owner).findFirst().orElse(owner));
        return callback(provider,action,uses,lifetime);
    }
    /** Owner-bound callback; disabled providers cannot execute and are released by CoreLifecycle. */
    public static ClickEvent callback(Plugin provider,Consumer<Player> action,int uses,Duration lifetime) {
        if (uses==0 || uses < -1) throw new IllegalArgumentException("uses must be positive or -1");
        if (lifetime.isNegative() || lifetime.isZero()) throw new IllegalArgumentException("lifetime must be positive");
        String token=UUID.randomUUID().toString();
        ACTIONS.put(token,new Entry(provider,Objects.requireNonNull(action),Math.addExact(System.currentTimeMillis(),lifetime.toMillis()),uses));
        return ClickEvent.runCommand(PREFIX+token);
    }
    private static Plugin providingPlugin(Class<?> type) {
        try{return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(type);}catch(IllegalArgumentException unavailable){return null;}
    }
    public static void release(Plugin provider) { ACTIONS.entrySet().removeIf(e -> e.getValue().provider==provider); }
    private static void purge() { long now=System.currentTimeMillis();ACTIONS.entrySet().removeIf(e->e.getValue().expires<now); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void command(PlayerCommandPreprocessEvent event) {
        if(!event.getMessage().startsWith(PREFIX))return;
        event.setCancelled(true);
        String token=event.getMessage().substring(PREFIX.length()).trim();
        Entry entry=ACTIONS.get(token);
        if(entry==null||entry.expires<System.currentTimeMillis()||owner==null||!owner.isEnabled()||entry.provider!=null&&!entry.provider.isEnabled()) { ACTIONS.remove(token);return; }
        if(entry.uses==1) { if(!ACTIONS.remove(token,entry))return; }
        else if(entry.uses>1 && !ACTIONS.replace(token,entry,new Entry(entry.provider,entry.action,entry.expires,entry.uses-1)))return;
        try { entry.action.accept(event.getPlayer()); }
        catch(RuntimeException failure) { Vystorm.vystorm_core.lifecycle.ProviderFailures.report("ChatActions",entry.provider==null?"unknown":entry.provider.getName(),"callback",failure); }
    }
}
