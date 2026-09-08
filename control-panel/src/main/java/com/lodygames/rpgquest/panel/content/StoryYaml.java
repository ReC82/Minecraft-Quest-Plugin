package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sérialiseur / relecteur YAML ciblé pour les <em>stories</em> de l'éditeur #46. Émet la forme
 * attendue par {@code StoryDefinitionParser} : {@code id} (sans namespace), {@code name} (texte),
 * {@code secret} (optionnel), {@code quests} (liste ordonnée d'id de quête, namespace
 * {@code rpgquest:} explicite). Sert aussi au garde-fou round-trip (émettre → relire → comparer).
 */
public final class StoryYaml {

    private StoryYaml() {
    }

    public static String write(StoryDraft s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Story RPGQuest — éditée via l'éditeur guidé du Control Panel (#46).\n");
        sb.append("id: ").append(plainId(s.id)).append('\n');
        sb.append("name: ").append(QuestYaml.qq(s.name)).append('\n');
        if (s.secret) {
            sb.append("secret: true\n");
        }
        sb.append("quests:\n");
        for (String q : s.questIds) {
            if (q != null && !q.isBlank()) {
                sb.append("  - ").append(QuestYaml.nsId(q)).append('\n');
            }
        }
        return sb.toString();
    }

    public record ReadResult(StoryDraft draft, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** Garde-fou round-trip (B12) — voir {@code QuestYaml.roundTripProblems}. */
    public static List<String> roundTripProblems(String yaml) {
        ReadResult r = read(yaml);
        if (r.draft == null) {
            return r.problems;
        }
        List<String> problems = new ArrayList<>(r.problems);
        if (!write(r.draft).equals(yaml)) {
            problems.add("Divergence de sérialisation : le YAML relu ne reproduit pas le YAML émis.");
        }
        return problems;
    }

    public static ReadResult read(String yaml) {
        Object root;
        try {
            root = MiniYaml.parse(yaml);
        } catch (RuntimeException e) {
            return new ReadResult(null, List.of("YAML illisible : " + e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> m)) {
            return new ReadResult(null, List.of("Racine YAML inattendue."));
        }
        StoryDraft d = new StoryDraft();
        d.id = plainId(str(m.get("id")));
        d.name = str(m.get("name"));
        d.secret = m.get("secret") instanceof Boolean b ? b : "true".equalsIgnoreCase(str(m.get("secret")));
        List<String> problems = new ArrayList<>();
        Object quests = m.get("quests");
        if (quests instanceof List<?> list) {
            for (Object o : list) {
                String q = QuestYaml.plainId(String.valueOf(o));
                if (!q.isBlank()) {
                    d.questIds.add(q);
                }
            }
        } else if (quests != null) {
            problems.add("« quests » doit être une liste.");
        }
        return new ReadResult(d, problems);
    }

    public static String plainId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
