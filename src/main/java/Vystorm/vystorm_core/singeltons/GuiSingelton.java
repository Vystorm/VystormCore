package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._uiMethods.autoGui.DefaultGuiHandler;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;

public class GuiSingelton {

/** Prozessweit geteilt (alle API-Instanzen nutzen denselben Handler); Zugriffe sind synchronisiert. */
private static volatile GuiInteface instance;

public GuiSingelton() {
	    // Private constructor to prevent instantiation
}

public static synchronized GuiInteface getInstance() {
	    if (instance == null) {
			  instance = new DefaultGuiHandler();
	    }
	    return instance;
}

public void override(GuiInteface override) { synchronized (GuiSingelton.class) { override0(override); } }
private void override0(GuiInteface override) {
	    java.util.Objects.requireNonNull(override);
        if (instance != null && instance != override) instance.clearManaged();
        this.instance = override;
	    // Add methods to interact with Vault here
}
	    public void reset() { synchronized (GuiSingelton.class) { reset0(); } }
private void reset0() {
			  if (instance != null) instance.clearManaged();
            this.instance = new DefaultGuiHandler() {
			  };
	    }
}
