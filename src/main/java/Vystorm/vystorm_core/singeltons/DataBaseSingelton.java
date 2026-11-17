package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseHandler;
import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface;

public class DataBaseSingelton {
    // Singleton instance
    private DataBaseInterface instance;

    // Private constructor to prevent instantiation
    public DataBaseSingelton() {
	  // Initialize file system resources here if needed
    }

    // Method to get the singleton instance
    public DataBaseInterface getInstance() {
	  if (instance == null) {
		instance = new DataBaseHandler();
	  }
	  return instance;
    }

public void override(DataBaseInterface newMethod) {
	    this.instance = newMethod;
}

public void reset() {
	    this.instance = new DataBaseHandler() {
	    };
}
// Add methods to manage file system operations here
}
