package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.eventRegulator.PriorityHandler;
import Vystorm.vystorm_core._coreMethods.eventRegulator.PriorityInterface;

public class PrioritySingelton {

private  PriorityInterface instance;

public PrioritySingelton() {
	    // Private constructor to prevent instantiation
}

public  PriorityInterface getInstance() {
	    if (instance == null) {
			  instance = new PriorityHandler();
	    }
	    return instance;
}

public void override(PriorityInterface ovverride) {
	    this.instance = ovverride;
	    // Add methods to interact with Vault here
}
	    public void reset() {
			  this.instance = new PriorityHandler() {
			  };
	    }
}
