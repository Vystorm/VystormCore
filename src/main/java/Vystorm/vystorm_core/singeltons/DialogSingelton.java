package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._uiMethods.dialog.DefaultDialogHandler;
import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;

public class DialogSingelton {
    /** Prozessweit geteilt (alle API-Instanzen nutzen denselben Handler); Zugriffe sind synchronisiert. */
private static volatile DialogInterface instance;

    public static synchronized DialogInterface getInstance() {
        if (instance == null) instance = new Vystorm.vystorm_core.compat.internal.ScreenDialogs(   // dialogs without inputs open as screens (0.59.0)
                Vystorm.vystorm_core.compat.ServerCompat.hasDialogs() ? new DefaultDialogHandler() : new Vystorm.vystorm_core.compat.internal.LegacyDialogs());
        return instance;
    }

    /** The native or chat handler behind the screen wrapper. */
    public static DialogInterface base() {
        DialogInterface handler = getInstance();
        return handler instanceof Vystorm.vystorm_core.compat.internal.ScreenDialogs screens ? screens.delegate() : handler;
    }

    public void override(DialogInterface replacement) { synchronized (DialogSingelton.class) { override0(replacement); } }
private void override0(DialogInterface replacement) {
        java.util.Objects.requireNonNull(replacement, "replacement");
        if (instance != null && instance != replacement) instance.clear();
        instance = replacement;
    }
}
