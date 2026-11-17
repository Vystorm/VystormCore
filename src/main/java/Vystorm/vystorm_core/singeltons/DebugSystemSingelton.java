package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DefaultDebugger;
import java.util.Objects;

public class DebugSystemSingelton {
    private volatile DebugInterface instance;

    public DebugInterface getDebugger() {
        DebugInterface current = instance;
        if (current == null) {
            synchronized (this) {
                current = instance;
                if (current == null) instance = current = new DefaultDebugger();
            }
        }
        return current;
    }
    /** Lifecycle changes run on the server thread. Close outside the initialization lock. */
    public void override(DebugInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        DebugInterface previous;
        synchronized (this) { previous = instance; instance = replacement; }
        if (previous != null && previous != replacement) previous.close();
    }
    public void reset() { override(new DefaultDebugger()); }
    public void close() {
        DebugInterface current = instance;
        if (current != null) current.close();
    }
}
