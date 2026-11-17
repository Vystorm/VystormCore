package Vystorm.vystorm_core.pack;

/** Client-Status des Vystorm-Resource-Packs (Spezifikation Items §20.4). */
public enum PackState {
    NOT_SENT, DOWNLOADING, LOADED, FAILED, DECLINED, OUTDATED;

    public boolean usable() { return this == LOADED; }
}
