package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.dataStorage.DataStorageInterface;
import Vystorm.vystorm_core._coreMethods.dataStorage.DefaultDataHandler;

public class DataSystemSingelton {
    // Singleton instance
    private DataStorageInterface instance;

    // Private constructor to prevent instantiation
    public DataSystemSingelton() {
	  // Initialize file system resources here if needed
    }

    // Method to get the singleton instance
    public DataStorageInterface getInstance() {
	  if (instance == null) {
		instance = new DefaultDataHandler();
	  }
	  return instance;
    }

public void override(DataStorageInterface newMethod) {
	    this.instance = newMethod;
}
public void reset() {
	    this.instance = new DefaultDataHandler() {
	    };
}
// Add methods to manage file system operations here
}
