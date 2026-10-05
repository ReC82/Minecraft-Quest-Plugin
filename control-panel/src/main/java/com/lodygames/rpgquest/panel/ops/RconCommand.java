package com.lodygames.rpgquest.panel.ops;

/**
 * Les <strong>seules</strong> commandes RCON que PlugAdmin peut émettre (issue #95).
 *
 * <p>C'est une énumération, pas une chaîne, précisément pour que « aucune commande RCON arbitraire
 * fournie par le navigateur » soit une propriété <strong>du type</strong> et non une promesse en
 * commentaire : il n'existe aucun chemin de code permettant de transporter un texte libre jusqu'au
 * serveur. Le navigateur ne choisit jamais une commande — il déclenche une <em>opération</em>
 * (redémarrer), et c'est le service qui décide quelles commandes composent cette opération.</p>
 */
public enum RconCommand {

    /** Sonde de vivacité : réponse = le serveur accepte et traite des commandes. */
    LIST("list"),
    /** Sauvegarde best-effort avant un arrêt. Jamais présentée comme une sauvegarde restaurable. */
    SAVE_ALL("save-all"),
    /** Arrêt propre. VeryGames relance ensuite le processus automatiquement. */
    STOP("stop");

    private final String wire;

    RconCommand(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
