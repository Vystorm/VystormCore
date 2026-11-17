package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface;
import Vystorm.vystorm_core._advancedMethods.areaHandler.AreaHandler;
import java.util.Objects;

public class AreaSingelton {
    private AreaInterface instance;
    public AreaInterface getInstance() {
        if (instance == null) instance = new AreaHandler();
        return instance;
    }
    @SuppressWarnings("deprecation")
    public void override(AreaInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (instance != null && instance != replacement) instance.clear();
        instance = replacement;
    }
    public void reset() { override(new AreaHandler()); }
    @SuppressWarnings("deprecation")
    public void clear() { if (instance != null) instance.clear(); }
}
