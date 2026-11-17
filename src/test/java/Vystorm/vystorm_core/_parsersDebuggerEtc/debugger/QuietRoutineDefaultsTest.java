package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class QuietRoutineDefaultsTest {
 @TempDir Path directory;
 @Test void routineDefaultsQuietAndErrorsRemainVisible()throws Exception{
  var store=new DebugStateStore(directory,message->fail(message));
  assertFalse(store.enabled("PriorityHandler","LOW"));assertFalse(store.enabled("ParticleHandler","LOW"));assertFalse(store.enabled("ParticleHandler","MID"));assertTrue(store.enabled("ParticleHandler","HIGH"));store.reload();
  var restored=new DebugStateStore(directory,message->fail(message));assertFalse(restored.enabled("PriorityHandler","LOW"));assertTrue(restored.enabled("ParticleHandler","HIGH"));
 }
 @Test void explicitDebugSwitchCanEnableRoutineDiagnostics()throws Exception{
  Files.writeString(directory.resolve("states.yml"),"PriorityHandler:\n  LOW: true\nParticleHandler:\n  LOW: true\n");var store=new DebugStateStore(directory,message->fail(message));assertTrue(store.enabled("PriorityHandler","LOW"));assertTrue(store.enabled("ParticleHandler","LOW"));
 }
}
