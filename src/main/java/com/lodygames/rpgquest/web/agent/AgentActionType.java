package com.lodygames.rpgquest.web.agent;

import java.util.Optional;

/**
 * Liste blanche des types d'action que l'agent RPGQuest accepte d'exécuter (issue #51). Tout type
 * absent de cette énumération est <strong>rejeté</strong> ({@code REJECTED}) sans exécution.
 *
 * <p>Le MVP #51 n'ouvre qu'une seule opération, <strong>non destructive</strong> :
 * {@link #PLAYER_VARIABLE_GET}. Les mutations (#36 / #45) viendront s'ajouter ici, chacune
 * explicitement whitelistée et adossée à un service métier — jamais une commande texte
 * {@code /rpgadmin …}.</p>
 */
public enum AgentActionType {

    /** Lecture d'une variable joueur ({@code player_variables}) — pure, hors ligne acceptée. */
    PLAYER_VARIABLE_GET("player.variable.get");

    private final String wire;

    AgentActionType(String wire) {
        this.wire = wire;
    }

    /** Nom transporté sur le fil (payload PlugAdmin). */
    public String wire() {
        return wire;
    }

    public static Optional<AgentActionType> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        for (AgentActionType type : values()) {
            if (type.wire.equalsIgnoreCase(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
