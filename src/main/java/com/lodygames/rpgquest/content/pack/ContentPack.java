package com.lodygames.rpgquest.content.pack;

import java.util.List;
import java.util.Map;

/**
 * Représentation en mémoire d'un <strong>content pack</strong> {@code lodyquests-content-pack}
 * (issue #108) : un manifest informatif + les sections de contenu déclaratif. Sans dépendance
 * Bukkit, sans donnée runtime/joueur/secret (les DTO ne portent que des champs éditoriaux).
 *
 * <p>Assemblé par {@code ContentPackAssembler}, sérialisé en YAML déterministe par
 * {@code ContentPackSerializer}. Le futur import #109 lira le même contrat.</p>
 */
public record ContentPack(
        String format,
        int schemaVersion,
        Manifest metadata,
        List<QuestPackEntry> quests,
        List<StoryPackEntry> stories,
        List<DialoguePackEntry> dialogues,
        List<NpcPackEntry> npcs) {

    public static final String FORMAT = "lodyquests-content-pack";
    public static final int SCHEMA_VERSION = 1;

    public ContentPack {
        quests = List.copyOf(quests);
        stories = List.copyOf(stories);
        dialogues = List.copyOf(dialogues);
        npcs = List.copyOf(npcs);
    }

    /**
     * Métadonnées <strong>informatives</strong> du pack (issue #108). L'import #109 ne doit dépendre
     * que de {@code format} + {@code schemaVersion} (champs racine), jamais de ce bloc.
     *
     * @param exportedAt    instant d'export, ISO-8601 UTC (injecté — déterministe en test)
     * @param pluginVersion version du plugin RPGQuest au moment de l'export ({@code "unknown"} si indispo)
     * @param generator     outil ayant produit le pack (ex. {@code "rpgquest-plugin-agent"})
     * @param families      familles réellement incluses, dans l'ordre canonique
     * @param counts        nombre d'éléments par famille (clés triées)
     */
    public record Manifest(String exportedAt, String pluginVersion, String generator,
                           List<String> families, Map<String, Integer> counts) {
        public Manifest {
            families = List.copyOf(families);
            counts = Map.copyOf(counts);
        }
    }

    public int totalElements() {
        return quests.size() + stories.size() + dialogues.size() + npcs.size();
    }
}
