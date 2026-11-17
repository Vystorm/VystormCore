package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.eventRegulator.NBTHandler;
import Vystorm.vystorm_core._coreMethods.eventRegulator.NBTInterface;

public class NBTSingelton {
    private NBTInterface instance;

    public NBTSingelton() {
        // Initialize block interactor resources here if needed
    }

    public NBTInterface getInstance() {
        if (instance == null) {
            instance = new NBTHandler();
        }
        return instance;
    }

    public void override(NBTInterface newMethod) {
        this.instance = newMethod;
    }

    public void reset() {
        this.instance = new NBTHandler() {
        };
    }
}


