package com.lodygames.rpgquest.content.pack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Assemble un {@link ContentPack} à partir des <strong>DTO déjà mappés</strong> (issue #108).
 * Volontairement <strong>sans dépendance Bukkit</strong> : le mapping modèle → DTO est fait en amont
 * par {@code ContentPackMapper}, injecté ici sous forme de {@link Supplier}. Cette classe ne fait
 * que sélectionner, ordonner de façon déterministe et emballer.
 *
 * <p>Prépare toutes les granularités demandées par #108 sans code spécifique par écran :
 * {@link #exportAll}, {@link #exportFamily}, {@link #exportElement}, {@link #exportSelection}, et le
 * cas général {@link #export(Set, Map, Instant)}. Ajouter une famille = enrichir
 * {@link ContentFamily} + fournir son {@link Supplier}.</p>
 */
public final class ContentPackAssembler {

    /** Outil générateur inscrit dans le manifest (informatif). */
    public static final String GENERATOR = "rpgquest-plugin";

    private final Supplier<List<QuestPackEntry>> quests;
    private final Supplier<List<StoryPackEntry>> stories;
    private final Supplier<List<DialoguePackEntry>> dialogues;
    private final Supplier<List<NpcPackEntry>> npcs;
    private final Supplier<String> pluginVersion;

    public ContentPackAssembler(Supplier<List<QuestPackEntry>> quests,
                                Supplier<List<StoryPackEntry>> stories,
                                Supplier<List<DialoguePackEntry>> dialogues,
                                Supplier<List<NpcPackEntry>> npcs,
                                Supplier<String> pluginVersion) {
        this.quests = quests;
        this.stories = stories;
        this.dialogues = dialogues;
        this.npcs = npcs;
        this.pluginVersion = pluginVersion;
    }

    // ---- Granularités -----------------------------------------------------------------------

    public ContentPack exportAll(Instant exportedAt) {
        return export(EnumSet.allOf(ContentFamily.class), Map.of(), exportedAt);
    }

    public ContentPack exportFamily(ContentFamily family, Instant exportedAt) {
        return export(EnumSet.of(family), Map.of(), exportedAt);
    }

    public ContentPack exportElement(ContentFamily family, String id, Instant exportedAt) {
        return export(EnumSet.of(family), Map.of(family, Set.of(id)), exportedAt);
    }

    public ContentPack exportSelection(ContentFamily family, Set<String> ids, Instant exportedAt) {
        return export(EnumSet.of(family), Map.of(family, Set.copyOf(ids)), exportedAt);
    }

    /**
     * Cas général.
     *
     * @param families   familles à inclure ; les sections des familles absentes restent vides
     * @param idFilter   pour une famille donnée, restreint aux ids demandés (absent / vide = tout)
     * @param exportedAt instant d'export (injecté pour un rendu déterministe en test)
     */
    public ContentPack export(Set<ContentFamily> families, Map<ContentFamily, Set<String>> idFilter,
                              Instant exportedAt) {
        List<QuestPackEntry> q = pick(ContentFamily.QUESTS, families, idFilter, quests, QuestPackEntry::id);
        List<StoryPackEntry> s = pick(ContentFamily.STORIES, families, idFilter, stories, StoryPackEntry::id);
        List<DialoguePackEntry> d = pick(ContentFamily.DIALOGUES, families, idFilter, dialogues, DialoguePackEntry::id);
        List<NpcPackEntry> n = pick(ContentFamily.NPCS, families, idFilter, npcs, NpcPackEntry::id);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(ContentFamily.QUESTS.wire(), q.size());
        counts.put(ContentFamily.STORIES.wire(), s.size());
        counts.put(ContentFamily.DIALOGUES.wire(), d.size());
        counts.put(ContentFamily.NPCS.wire(), n.size());

        List<String> includedFamilies = new ArrayList<>();
        for (ContentFamily f : ContentFamily.values()) {
            if (families.contains(f)) {
                includedFamilies.add(f.wire());
            }
        }

        String version = safeVersion();
        ContentPack.Manifest manifest = new ContentPack.Manifest(
                exportedAt.toString(), version, GENERATOR, includedFamilies, counts);
        return new ContentPack(ContentPack.FORMAT, ContentPack.SCHEMA_VERSION, manifest, q, s, d, n);
    }

    private <T> List<T> pick(ContentFamily family, Set<ContentFamily> families,
                             Map<ContentFamily, Set<String>> idFilter, Supplier<List<T>> source,
                             java.util.function.Function<T, String> idOf) {
        if (!families.contains(family)) {
            return List.of();
        }
        Set<String> ids = idFilter.get(family);
        List<T> all = source.get();
        List<T> out = new ArrayList<>();
        for (T item : all) {
            if (ids == null || ids.isEmpty() || ids.contains(idOf.apply(item))) {
                out.add(item);
            }
        }
        out.sort(java.util.Comparator.comparing(idOf));
        return out;
    }

    private String safeVersion() {
        try {
            String v = pluginVersion.get();
            return v == null || v.isBlank() ? "unknown" : v;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
