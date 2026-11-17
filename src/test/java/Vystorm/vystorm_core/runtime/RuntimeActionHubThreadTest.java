package Vystorm.vystorm_core.runtime;
import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RuntimeActionHubThreadTest {
 @Test void asyncDispatchDefersHandlerUntilServerThreadExecutesTask() {
  Plugin owner=mock(Plugin.class);when(owner.isEnabled()).thenReturn(true);
  BukkitScheduler scheduler=mock(BukkitScheduler.class);
  AtomicReference<Runnable> pending=new AtomicReference<>();
  AtomicBoolean primary=new AtomicBoolean(false),called=new AtomicBoolean(false);
  doAnswer(call->{pending.set(call.getArgument(1,Runnable.class));return null;}).when(scheduler).runTask(eq(owner),any(Runnable.class));
  var hub=RuntimeActionHub.get();String id="thread-test-"+UUID.randomUUID();
  hub.register(owner,new RuntimeActionHub.Handler(){
   public String id(){return id;} public Set<String> actions(){return Set.of("test.thread");}
   public CompletionStage<RuntimeActionHub.Response> handle(RuntimeActionHub.Request request){assertTrue(primary.get());called.set(true);return CompletableFuture.completedFuture(RuntimeActionHub.Response.accepted(id,Map.of()));}
  });
  try(var bukkit=mockStatic(Bukkit.class);var entry=mockStatic(main.class)) {
   bukkit.when(Bukkit::isPrimaryThread).thenAnswer(i->primary.get());bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);entry.when(main::getPlugin).thenReturn(owner);
   var result=hub.dispatch(RuntimeActionHub.Request.world("test.thread",UUID.randomUUID(),0,64,0)).toCompletableFuture();
   assertFalse(called.get());assertFalse(result.isDone());assertNotNull(pending.get());primary.set(true);pending.get().run();assertTrue(result.join().accepted());assertTrue(called.get());
  } finally {hub.unregisterOwner(owner);}
 }
}
