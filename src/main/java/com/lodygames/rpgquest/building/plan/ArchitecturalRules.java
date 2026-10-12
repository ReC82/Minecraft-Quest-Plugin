package com.lodygames.rpgquest.building.plan;

/**
 * Les seuils de l'audit architectural, <strong>en un seul endroit</strong> (issue #94, lot A).
 *
 * <h2>Pourquoi un objet, et pas des constantes éparpillées</h2>
 *
 * <p>Deux raisons, et la seconde est la vraie. D'abord, ces valeurs sont des <em>choix de goût</em>
 * — « deux fenêtres », « huit blocs », « 70 % » — arbitrés avec le propriétaire, pas des vérités
 * techniques : elles doivent se relire ensemble et se modifier sans chasser dans le code. Ensuite,
 * et surtout, elles <strong>dépendent du type de structure</strong> : une fontaine n'a pas de porte,
 * une arche de portail n'a pas d'étage. Les porter dans un objet paramétré par
 * {@link StructureKind} est ce qui empêche d'appliquer les règles d'un logement à un décor.</p>
 *
 * <p>Valeurs de départ arrêtées avec le propriétaire le 2026-10-11.</p>
 *
 * @param kind                   le type auquel ce profil s'applique
 * @param requireEntrance        exiger une entrée traversable sur la façade attendue
 * @param requirePassage         exiger au moins un passage franchissable de part en part (portail)
 * @param minGlazedCells         nombre minimal de cellules vitrées ; 0 = pas d'exigence
 * @param minGlazedFacades       nombre minimal de façades portant au moins un vitrage
 * @param requireLight           exiger au moins une source de lumière utile
 * @param maxLightDistance       distance maximale d'une cellule accessible à une source, en blocs
 * @param requireClosedEnvelope  exiger couverture et pignons fermés
 * @param envelopeOpeningTolerance cellules ouvertes tolérées en haut de l'enveloppe (cheminée, lucarne)
 * @param openingMaxHeight       hauteur au-delà de laquelle une ouverture n'est plus une porte ou
 *                               une fenêtre de rez-de-chaussée mais un trou dans la couverture
 * @param requireLevelAccess     exiger que chaque niveau soit atteignable
 * @param minEnvelopeMaterials   nombre minimal de matériaux dans l'enveloppe (avertissement)
 * @param maxMaterialShare       part maximale d'un seul matériau dans l'enveloppe (avertissement)
 * @param minFurnishingCells     mobilier minimal attendu à l'intérieur (avertissement)
 */
public record ArchitecturalRules(StructureKind kind,
                                 boolean requireEntrance,
                                 boolean requirePassage,
                                 int minGlazedCells,
                                 int minGlazedFacades,
                                 boolean requireLight,
                                 int maxLightDistance,
                                 boolean requireClosedEnvelope,
                                 int envelopeOpeningTolerance,
                                 int openingMaxHeight,
                                 boolean requireLevelAccess,
                                 int minEnvelopeMaterials,
                                 double maxMaterialShare,
                                 int minFurnishingCells) {

    /** Le profil correspondant à un type de structure. */
    public static ArchitecturalRules forKind(StructureKind kind) {
        return switch (kind == null ? StructureKind.HABITABLE : kind) {
            case HABITABLE -> habitable();
            case DECORATION -> decoration();
            case PORTAL -> portal();
        };
    }

    /**
     * Bâtiment habitable : le profil complet.
     *
     * <p>« Au moins 2 ouvertures vitrées sur 2 façades » est lu comme : au moins deux cellules
     * vitrées, réparties sur au moins deux façades. Un mur entier de verre sur une seule face
     * échoue donc, et c'est voulu — un bâtiment éclairé d'un seul côté reste sombre.</p>
     *
     * <p>La tolérance d'ouverture en haut de l'enveloppe vaut 4 cellules : de quoi laisser passer
     * une cheminée ou une lucarne sans ouvrir la porte à un pignon béant. Le lot B permettra de
     * <em>déclarer</em> une ouverture, ce qui rendra cette tolérance inutile.</p>
     */
    public static ArchitecturalRules habitable() {
        return new ArchitecturalRules(StructureKind.HABITABLE,
                true,    // entrée exigée
                false,   // pas de passage de part en part exigé
                2,       // au moins 2 cellules vitrées
                2,       // sur au moins 2 façades
                true,    // éclairage exigé
                8,       // à 8 blocs au plus d'une source
                true,    // couverture et pignons fermés
                4,       // tolérance : cheminée, lucarne
                4,       // au-delà de 4 blocs de hauteur, une ouverture n'est plus une porte
                true,    // chaque niveau atteignable
                3,       // au moins 3 matériaux d'enveloppe
                0.70,    // avertissement au-delà de 70 % pour un seul matériau
                4);      // un intérieur totalement nu est signalé
    }

    /**
     * Décoration : aucun intérieur, donc aucune exigence d'entrée, de vitrage, d'éclairage ni de
     * niveaux. Le seul contrôle qui reste pertinent est la variété des matériaux, en avertissement.
     */
    public static ArchitecturalRules decoration() {
        return new ArchitecturalRules(StructureKind.DECORATION,
                false, false, 0, 0, false, 0, false, 0, 0, false,
                2,       // deux matériaux : une fontaine monomatière reste plate
                0.90,    // large, mais pas illimité
                0);
    }

    /**
     * Architecture de portail : traversable par construction, sans intérieur. On exige donc un
     * passage, et rien d'autre de ce qui concerne l'habitabilité.
     */
    public static ArchitecturalRules portal() {
        return new ArchitecturalRules(StructureKind.PORTAL,
                false,
                true,    // un portail qu'on ne peut pas franchir n'est pas un portail
                0, 0, false, 0, false, 0, 0, false,
                2, 0.90, 0);
    }
}
