package com.lodygames.rpgquest.encounter.catalog;

/**
 * Rendu déterministe d'un {@link QuestCatalog} en YAML (issue #369), relu à l'identique par
 * {@link QuestCatalogParser}. PlugAdmin n'envoie jamais de YAML : seuls des champs validés
 * arrivent ici.
 */
public final class QuestCatalogYaml {

    private QuestCatalogYaml() {
    }

    public static String render(QuestCatalog c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Catalogue de quêtes secondaires (issue #369) — édité via PlugAdmin. Format : docs/ENCOUNTERS.md.\n");
        sb.append("id: ").append(c.id()).append('\n');
        sb.append("display_name: ").append(quote(c.displayName())).append('\n');
        if (c.description() != null) {
            sb.append("description: ").append(quote(c.description())).append('\n');
        }
        sb.append("enabled: ").append(c.enabled()).append('\n');
        sb.append("avoid_recent: ").append(c.avoidRecent()).append('\n');
        if (c.entries().isEmpty()) {
            sb.append("entries: []\n");
            return sb.toString();
        }
        sb.append("entries:\n");
        for (QuestCatalog.Entry e : c.entries()) {
            sb.append("  - quest: ").append(e.questId()).append('\n');
            sb.append("    weight: ").append(e.weight()).append('\n');
            sb.append("    enabled: ").append(e.enabled()).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
