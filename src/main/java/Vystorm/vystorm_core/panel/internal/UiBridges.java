package Vystorm.vystorm_core.panel.internal;

/**
 * Paket-privater Halter der {@link UiBridge}: erzeugt die paket-private Umsetzung {@code panel.UiAccess} einmal per
 * Reflexion. Es gibt keinen öffentlichen Weg, die Brücke zu ersetzen oder von außen abzufragen (Re-Review Minor 8).
 */
final class UiBridges {
    static final UiBridge BRIDGE = create();

    private UiBridges() {}

    private static UiBridge create() {
        try {
            var constructor = Class.forName("Vystorm.vystorm_core.panel.UiAccess").getDeclaredConstructor();
            constructor.setAccessible(true);
            return (UiBridge) constructor.newInstance();
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException("panel UiAccess missing", broken);
        }
    }
}
