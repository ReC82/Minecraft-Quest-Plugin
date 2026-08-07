package be.lloyd.rpgquest.mod.network;

/** État affiché par le HUD (mission étape 23, point 5 : petite indication client). */
public enum ConnectionStatus {
    NOT_CONNECTED,
    COMPATIBLE,
    WRONG_VERSION
}
