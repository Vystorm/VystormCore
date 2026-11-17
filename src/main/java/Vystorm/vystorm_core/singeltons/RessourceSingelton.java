package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.ressourceLoader.RessourceHandler;

public class RessourceSingelton {

// Singleton instance
private Vystorm.vystorm_core._coreMethods.ressourceLoader.RessourceInterface instance;


// Private constructor to prevent instantiation
public RessourceSingelton() {
}

// Method to get the singleton instance
public Vystorm.vystorm_core._coreMethods.ressourceLoader.RessourceInterface getInstance() {
     if (instance == null) {
	  instance = new RessourceHandler();
     }
     return instance;
}

// Add methods to manage player data here
public void override(Vystorm.vystorm_core._coreMethods.ressourceLoader.RessourceInterface newMessage) {
     this.instance = newMessage;
}

public void reset() {
     this.instance = new RessourceHandler() {
     };
}
}


