package Vystorm.vystorm_core.singeltons;
import Vystorm.vystorm_core._advancedMethods.inventoryInteractor.DefaultInteractor;
import Vystorm.vystorm_core._advancedMethods.inventoryInteractor.InteractorInterface;

public class InventoryInteractorSingelton {

    // Singleton instance
    private InteractorInterface instance;

    // Private constructor to prevent instantiation
    public InventoryInteractorSingelton() {
	  // Initialize player data here if needed
    }

    // Method to get the singleton instance
    public  InteractorInterface getInstance() {
	  if (instance == null) {
		instance = new DefaultInteractor();
	  }
	  return instance;
    }

    // Add methods to manage player data here
    public void override(InteractorInterface newPlayerData) {
		  this.instance = newPlayerData;
    }

	public void reset() {
	    this.instance = new DefaultInteractor() {
	    };
}
}


