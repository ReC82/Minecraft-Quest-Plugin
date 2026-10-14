package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.List;

/**
 * Rendu d'un {@link EncounterTemplate} en texte YAML déterministe (issue #13 étape 3) — même
 * conception que {@code NpcDefinitionYaml} : aucune dépendance Bukkit, aucun accès disque, et le
 * résultat se re-parse à l'identique avec {@link EncounterTemplateParser}.
 *
 * <p>PlugAdmin n'envoie <strong>jamais</strong> de YAML brut : seuls des champs validés arrivent
 * ici, et c'est cette classe qui produit le fichier.</p>
 */
public final class EncounterTemplateYaml {

    private EncounterTemplateYaml() {
    }

    public static String render(EncounterTemplate t) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Modèle de PNJ récurrent (issue #13) — édité via PlugAdmin. Format : docs/ENCOUNTERS.md.\n");
        sb.append("id: ").append(t.id()).append('\n');
        sb.append("display_name: ").append(quote(t.displayName())).append('\n');
        if (t.description() != null) {
            sb.append("description: ").append(quote(t.description())).append('\n');
        }
        sb.append("npc_id: ").append(t.npcId()).append('\n');
        if (t.role() != null) {
            sb.append("role: ").append(t.role()).append('\n');
        }
        if (t.family() != null) {
            sb.append("family: ").append(t.family()).append('\n');
        }
        sb.append("biomes:\n");
        t.biomes().stream().sorted().forEach(b -> sb.append("  - ").append(b).append('\n'));
        list(sb, "worlds", t.worlds());
        sb.append("weight: ").append(t.weight()).append('\n');
        sb.append("max_sites: ").append(t.maxSites()).append('\n');
        if (t.skinUrl() != null) {
            sb.append("skin_url: ").append(quote(t.skinUrl())).append('\n');
        }
        sb.append("enabled: ").append(t.enabled()).append('\n');
        if (!t.questCatalogs().isEmpty()) {
            list(sb, "quest_catalogs", t.questCatalogs());
        }
        return sb.toString();
    }

    private static void list(StringBuilder sb, String key, List<String> values) {
        if (values.isEmpty()) {
            sb.append(key).append(": []\n");
            return;
        }
        sb.append(key).append(":\n");
        values.forEach(v -> sb.append("  - ").append(quote(v)).append('\n'));
    }

    /** Toujours entre guillemets doubles : un nom peut contenir des espaces, « : », « # »… */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
