package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseHandler;
import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface;

/**
 * Unused legacy API, kept for binary compatibility. Unlike the other deprecated legacy types it keeps its behaviour:
 * {@link #getInstance()} never returned null, and a caller may rely on that.
 */
@Deprecated(since = "0.63.0", forRemoval = true)
public class MapStorageSingelton {
    private DataBaseInterface instance;

    public MapStorageSingelton() {
    }

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
}
