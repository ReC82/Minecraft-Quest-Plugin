package com.lodygames.rpgquest.panel.diag;

/**
 * Domaine fonctionnel d'un diagnostic (issue #38) — un regroupement <strong>humain</strong>, pas
 * un nom de module. Seuls les domaines réellement alimentés par un {@link DiagnosticProvider} sont
 * affichés dans les filtres de la page.
 */
public enum Domain {
    PNJ("PNJ", "npc"),
    DIALOGUES("Dialogues", "dialogues"),
    QUESTS("Quêtes", "quests"),
    STORIES("Stories", "stories"),
    ITEMS("Items", "box"),
    WORLDS("Mondes", "world"),
    PORTALS("Portails", "link"),
    CLAIMS("Claims", "lock"),
    SERVER("Serveur", "server"),
    AGENT("Agent", "agents"),
    OTHER("Autres", "diagnostics");

    private final String label;
    private final String icon;

    Domain(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    /** Libellé humain (« Quêtes »). */
    public String label() {
        return label;
    }

    /** Nom d'icône ({@code Icons}). */
    public String icon() {
        return icon;
    }

    /** Valeur de filtre stable, en minuscules (« quests »). */
    public String filterKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Cible {@code Ouvrir} : page métier associée, ou {@code ""} si aucune. */
    public String pagePath() {
        return switch (this) {
            case PNJ -> "/npcs";
            case DIALOGUES -> "/dialogues";
            case QUESTS -> "/quests";
            case STORIES -> "/stories";
            default -> "";
        };
    }
}
