package com.lodygames.rpgquest.building.model;

/**
 * Ce qui a été tenté sur un emplacement (issue #234).
 *
 * <h2>Pourquoi un type et non une chaîne libre</h2>
 *
 * <p>L'historique sert à répondre à une question précise — « qu'est-ce qui a été posé ici, et dans
 * quel ordre ? ». Une colonne texte libre s'écrirait un jour {@code "replace"}, un autre
 * {@code "REPLACEMENT"}, et la réponse deviendrait un exercice de devinette. L'énumération est donc
 * la liste fermée des gestes que le moteur sait faire, et le journal ne peut contenir rien d'autre.</p>
 */
public enum BuildingOperation {

    /** Première pose sur un emplacement vide. */
    PLACE("Pose"),

    /**
     * Même bâtiment, autre orientation.
     *
     * <p>Distinct de {@link #REPLACE} même si le moteur exécute la même séquence : à la lecture de
     * l'historique, « on a tourné la hutte » et « on a remplacé la hutte par une tour » ne racontent
     * pas la même histoire.</p>
     */
    ROTATE("Réorientation"),

    /** Autre bâtiment à la place du précédent. */
    REPLACE("Remplacement"),

    /**
     * Retour au terrain d'origine, et libération de l'emplacement.
     *
     * <p>C'est la seule opération qui laisse l'emplacement {@code EMPTY} : les trois autres le
     * laissent {@code OCCUPIED}.</p>
     */
    RESTORE("Libération");

    private final String label;

    BuildingOperation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Vrai si l'opération laisse un bâtiment posé derrière elle. */
    public boolean leavesBuilding() {
        return this != RESTORE;
    }

    /**
     * Lecture tolérante d'une valeur venue de la base.
     *
     * <p>Une valeur inconnue — colonne écrite par une version future, ou éditée à la main — ne doit
     * pas faire échouer la lecture de tout l'historique : l'historique est un outil de diagnostic,
     * et un diagnostic qui refuse de s'afficher ne diagnostique rien. On renvoie {@code null}, et
     * l'appelant décide.</p>
     */
    public static BuildingOperation ofWire(String stored) {
        if (stored == null) {
            return null;
        }
        for (BuildingOperation operation : values()) {
            if (operation.name().equalsIgnoreCase(stored.trim())) {
                return operation;
            }
        }
        return null;
    }
}
