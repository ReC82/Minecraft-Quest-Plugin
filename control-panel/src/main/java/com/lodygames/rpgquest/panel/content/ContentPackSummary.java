package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aperçu éditorial d'un pack, lisible par un auteur AVANT la confirmation (issue #211) : la story
 * et l'ordre de ses quêtes, puis pour chaque quête son donneur, ses déblocages, ses objectifs et
 * ses récompenses, et enfin les personnages et ce qu'il reste à faire pour eux (placement).
 *
 * <p>Présentation seulement : la validation reste celle de {@link ContentPackImport#analyze}. Le
 * texte est lu par le même {@link ContentPackImport#parseRoot} (JSON ou YAML), donc l'aperçu
 * montre exactement ce que l'analyse a lu ; un pack illisible donne un aperçu vide.</p>
 */
public final class ContentPackSummary {

    public record Quest(String id, String title, String giver, List<String> prerequisites,
                        List<String> objectives, List<String> rewards) {
    }

    public record Story(String id, String name, List<String> questIds) {
    }

    public record Npc(String id, String name, String dialogue, String description) {
    }

    public record Dialogue(String id, int nodes, List<String> startsQuests) {
    }

    public record Summary(List<Story> stories, Map<String, Quest> quests, List<Npc> npcs,
                          List<Dialogue> dialogues) {

        public boolean isEmpty() {
            return stories.isEmpty() && quests.isEmpty() && npcs.isEmpty() && dialogues.isEmpty();
        }

        /** Les quêtes qu'aucune story du pack ne cite : affichées à part, jamais perdues. */
        public List<Quest> standaloneQuests() {
            List<Quest> out = new ArrayList<>(quests.values());
            stories.forEach(s -> out.removeIf(q -> s.questIds().contains(q.id())));
            return out;
        }
    }

    private ContentPackSummary() {
    }

    public static Summary of(String pack) {
        Object root;
        try {
            root = ContentPackImport.parseRoot(pack);
        } catch (RuntimeException e) {
            return empty();
        }
        if (!(root instanceof Map<?, ?> m) || !(m.get("content") instanceof Map<?, ?> content)) {
            return empty();
        }
        Map<String, Quest> quests = new LinkedHashMap<>();
        for (Map<?, ?> q : maps(content.get("quests"))) {
            String id = QuestYaml.nsId(str(q.get("id")));
            List<String> prereqs = new ArrayList<>();
            list(q.get("prerequisites")).forEach(p -> prereqs.add(QuestYaml.nsId(str(p))));
            List<String> objectives = new ArrayList<>();
            int step = 1;
            for (Map<?, ?> s : maps(q.get("steps"))) {
                for (Map<?, ?> o : maps(s.get("objectives"))) {
                    objectives.add("Étape " + step + " — " + describe(o, true));
                }
                step++;
            }
            List<String> rewards = new ArrayList<>();
            maps(q.get("rewards")).forEach(r -> rewards.add(describe(r, false)));
            quests.put(id, new Quest(id, str(q.get("title")), str(q.get("giver")), prereqs, objectives, rewards));
        }
        List<Story> stories = new ArrayList<>();
        for (Map<?, ?> s : maps(content.get("stories"))) {
            List<String> ids = new ArrayList<>();
            list(s.get("quests") != null ? s.get("quests") : s.get("questIds"))
                    .forEach(q -> ids.add(QuestYaml.nsId(str(q))));
            stories.add(new Story(str(s.get("id")), str(s.get("name")), ids));
        }
        List<Npc> npcs = new ArrayList<>();
        maps(content.get("npcs")).forEach(n -> npcs.add(new Npc(str(n.get("id")), str(n.get("displayName")),
                str(n.get("dialogue")), str(n.get("description")))));
        List<Dialogue> dialogues = new ArrayList<>();
        for (Map<?, ?> d : maps(content.get("dialogues"))) {
            int nodes = d.get("nodes") instanceof Map<?, ?> n ? n.size() : 0;
            List<String> starts = new ArrayList<>();
            if (d.get("nodes") instanceof Map<?, ?> n) {
                for (Object node : n.values()) {
                    if (node instanceof Map<?, ?> nm) {
                        for (Map<?, ?> c : maps(nm.get("choices"))) {
                            for (Map<?, ?> a : maps(c.get("actions"))) {
                                String quest = QuestYaml.nsId(str(a.get("quest")));
                                if ("START_QUEST".equals(str(a.get("type"))) && !starts.contains(quest)) {
                                    starts.add(quest);
                                }
                            }
                        }
                    }
                }
            }
            dialogues.add(new Dialogue(str(d.get("id")), nodes, starts));
        }
        return new Summary(stories, quests, npcs, dialogues);
    }

    /**
     * « Rapporter des objets à un PNJ : 3 × BREAD → lily ». Le libellé vient du descripteur réel ;
     * un type inconnu reste affiché tel quel (l'analyse, elle, le refuse).
     */
    static String describe(Map<?, ?> m, boolean objective) {
        String type = str(m.get("type"));
        String label = (objective ? Descriptors.objective(type) : Descriptors.reward(type))
                .map(Descriptors.Descriptor::label).orElse(type);
        StringBuilder sb = new StringBuilder(label);
        String amount = str(m.get("amount"));
        String what = !str(m.get("material")).isEmpty() ? str(m.get("material")) : str(m.get("entity"));
        if (!what.isEmpty()) {
            sb.append(" : ").append(amount.isEmpty() ? "" : amount + " × ").append(what);
        } else if (!amount.isEmpty()) {
            sb.append(" : ").append(amount);
        }
        if (!str(m.get("key")).isEmpty()) {
            sb.append(" : ").append(str(m.get("key"))).append(" = ").append(str(m.get("value")));
        }
        if (!str(m.get("npc")).isEmpty()) {
            sb.append(" → ").append(str(m.get("npc")));
        }
        return sb.toString();
    }

    private static Summary empty() {
        return new Summary(List.of(), Map.of(), List.of(), List.of());
    }

    private static List<Map<?, ?>> maps(Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object o : list(raw)) {
            if (o instanceof Map<?, ?> m) {
                out.add(m);
            }
        }
        return out;
    }

    private static List<?> list(Object raw) {
        return raw instanceof List<?> l ? l : List.of();
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
