package Vystorm.vystorm_core.singeltons;
import Vystorm.vystorm_core._coreMethods.playerHandler.DefaultPlayerHandler;
import Vystorm.vystorm_core._coreMethods.playerHandler.PlayerHandlerInterface;

public class PlayerDataSingelton {

    // Singleton instance
    private  PlayerHandlerInterface instance;

    // Private constructor to prevent instantiation
    public PlayerDataSingelton() {
	  // Initialize player data here if needed
    }

    // Method to get the singleton instance
    public  PlayerHandlerInterface getInstance() {
	  if (instance == null) {
		instance = new DefaultPlayerHandler();
	  }
	  return instance;
    }

    // Add methods to manage player data here
    public void override(PlayerHandlerInterface newPlayerData) {
		  this.instance = newPlayerData;
    }
public void reset() {
	    this.instance = new DefaultPlayerHandler() {
	    };
}
}


