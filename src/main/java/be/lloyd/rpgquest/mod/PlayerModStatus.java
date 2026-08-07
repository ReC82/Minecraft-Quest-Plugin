package be.lloyd.rpgquest.mod;

/** Résultat de la détection de compatibilité pour un joueur (mission étape 23, point 8). */
public enum PlayerModStatus {
    /** Handshake envoyé, réponse pas encore reçue (ou délai pas encore écoulé). */
    PENDING,
    /** Mod présent, protocole compatible. */
    COMPATIBLE,
    /** Mod présent, mais numéro de protocole différent de celui attendu par le serveur. */
    WRONG_VERSION,
    /** Aucune réponse reçue avant expiration du délai — client vanilla, ou mod non installé/désactivé. */
    NO_MOD
}
