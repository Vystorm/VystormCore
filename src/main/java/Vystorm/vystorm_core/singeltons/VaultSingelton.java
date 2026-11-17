package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._integrations.vault.DefaultVault;
import Vystorm.vystorm_core._integrations.vault.VaultInterface;

public class VaultSingelton {

/** Prozessweit geteilt (alle API-Instanzen nutzen denselben Handler); Zugriffe sind synchronisiert. */
private static volatile VaultInterface instance;

public VaultSingelton() {
	    // Private constructor to prevent instantiation
}

public static synchronized VaultInterface getInstance() {
	    if (instance == null) {
			  instance = new DefaultVault();
	    }
	    return instance;
}

public void override(VaultInterface ovverride) { synchronized (VaultSingelton.class) { override0(ovverride); } }
private void override0(VaultInterface ovverride) {
	    this.instance = ovverride;
	    // Add methods to interact with Vault here
}
	    public void reset() { synchronized (VaultSingelton.class) { reset0(); } }
private void reset0() {
			  this.instance = new DefaultVault() {
			  };
	    }
}
