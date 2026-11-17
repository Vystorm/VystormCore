package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._integrations.luckpermsIntegration.DefaultluckpermsHandler;
import Vystorm.vystorm_core._integrations.luckpermsIntegration.LuckpermsInterface;

public class LuckpermsSingelton {


/** Prozessweit geteilt (alle API-Instanzen nutzen denselben Handler); Zugriffe sind synchronisiert. */
private static volatile LuckpermsInterface instance;

public LuckpermsSingelton() {
	    // Private constructor to prevent instantiation
}

public LuckpermsInterface getInstance() {
	    synchronized (LuckpermsSingelton.class) {
			  if (instance == null) instance = new DefaultluckpermsHandler();
			  return instance;
	    }
}

public void override(LuckpermsInterface ovverride) { synchronized (LuckpermsSingelton.class) { override0(ovverride); } }
private void override0(LuckpermsInterface ovverride) {
	    this.instance = ovverride;
	    // Add methods to interact with Vault here
}
public void reset() { synchronized (LuckpermsSingelton.class) { reset0(); } }
private void reset0() {
	    this.instance = new DefaultluckpermsHandler() {
	    };
}
}

