package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sérialiseur / relecteur YAML <strong>ciblé</strong> pour les quêtes de l'éditeur #46. Émet
 * exactement la forme attendue par {@code QuestDefinitionParser} du moteur RPGQuest (indentation
 * 2 espaces, listes {@code - }, scalaires entre guillemets doubles pour le texte). Le relecteur
 * comprend cette même forme — il sert au <strong>garde-fou round-trip</strong> (émettre → relire
 * → comparer) et au pré-remplissage à l'édition.
 *
 * <p>Ce n'est pas un parseur YAML général : les fichiers écrits ailleurs avec des styles exotiques
 * (scalaires repliés {@code >}, ancres…) peuvent ne pas se relire à l'identique — dans ce cas
 * l'éditeur le signale et propose de repartir d'un brouillon normalisé plutôt que d'écraser à
 * l'aveugle.</p>
 */
public final class QuestYaml {

    private QuestYaml() {
    }

    // ---- émission -----------------------------------------------------------------------

    public static String write(QuestDraft q) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Quête RPGQuest — éditée via l'éditeur guidé du Control Panel (#46).\n");
        sb.append("id: ").append(nsId(q.id)).append('\n');
        sb.append("title: ").append(qq(q.title)).append('\n');
        sb.append("description: ").append(qq(q.description)).append('\n');
        sb.append("category: ").append(qq(q.category)).append('\n');
        sb.append("icon: ").append(up(q.icon.isBlank() ? "BOOK" : q.icon)).append('\n');
        sb.append("repeatable: ").append(q.repeatable).append('\n');
        if (q.secret) {
            sb.append("secret: true\n");
        }
        if (q.giver != null && !q.giver.isBlank()) {
            sb.append("giver: ").append(q.giver.trim()).append('\n');
        }
        List<String> prereq = clean(q.prerequisites);
        if (!prereq.isEmpty()) {
            sb.append("prerequisites:\n");
            for (String p : prereq) {
                sb.append("  - ").append(nsId(p)).append('\n');
            }
        }

        sb.append("\nsteps:\n");
        for (QuestDraft.Step s : q.steps) {
            sb.append("  - id: ").append(safeId(s.id)).append('\n');
            sb.append("    objectives:\n");
            for (Map<String, String> o : s.objectives) {
                writeKv(sb, "      - ", "        ", o, "kind");
            }
        }

        List<Map<String, String>> rewards = q.rewards;
        if (!rewards.isEmpty()) {
            sb.append("\nrewards:\n");
            for (Map<String, String> r : rewards) {
                writeKv(sb, "  - ", "    ", r, "kind");
            }
        }

        Map<String, String> vars = q.variables;
        if (!vars.isEmpty()) {
            sb.append("\nvariables:\n");
            for (Map.Entry<String, String> e : vars.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(qq(e.getValue())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Écrit une entrée de liste {@code {kind: X, champ: v, …}} sous forme {@code - type: X\n  champ: v}. */
    private static void writeKv(StringBuilder sb, String bullet, String indent, Map<String, String> map, String kindKey) {
        String kind = up(map.getOrDefault(kindKey, ""));
        sb.append(bullet).append("type: ").append(kind).append('\n');
        Descriptors.objective(kind).or(() -> Descriptors.reward(kind)).ifPresentOrElse(desc -> {
            for (Descriptors.Field f : desc.fields()) {
                String v = map.get(f.name());
                if (v == null || v.isBlank()) {
                    continue;
                }
                sb.append(indent).append(f.name()).append(": ").append(scalar(f.type(), v)).append('\n');
            }
        }, () -> {
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey().equals(kindKey) || e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                sb.append(indent).append(e.getKey()).append(": ").append(qq(e.getValue())).append('\n');
            }
        });
    }

    private static String scalar(Descriptors.FieldType type, String v) {
        return switch (type) {
            case INT, DOUBLE -> v.trim();
            case SELECT -> looksTechnical(v) ? v.trim() : qq(v);
            default -> qq(v);
        };
    }

    private static boolean looksTechnical(String v) {
        return v.trim().matches("[A-Za-z0-9_:./-]+");
    }

    // ---- relecture -------------------------------------------------------------------

    public record ReadResult(QuestDraft draft, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * Garde-fou round-trip (B12) : relit le YAML émis puis le ré-émet, et vérifie l'égalité
     * structurelle. Une liste non vide = le fichier ne se relit pas fidèlement (à ne jamais
     * écrire dans la source en l'état). Même principe que {@code DialogueDefinitionEditor} (#82).
     */
    public static List<String> roundTripProblems(String yaml) {
        ReadResult r = read(yaml);
        if (r.draft == null) {
            return r.problems;
        }
        List<String> problems = new ArrayList<>(r.problems);
        if (!write(r.draft).equals(yaml)) {
            problems.add("Divergence de sérialisation : le YAML relu ne reproduit pas le YAML émis "
                    + "(construction non supportée pour l'écriture dans la source).");
        }
        return problems;
    }

    /** Relit le YAML d'une quête (forme émise par {@link #write}). Best-effort ; {@code problems} non vide = divergence. */
    public static ReadResult read(String yaml) {
        List<String> problems = new ArrayList<>();
        Object root;
        try {
            root = MiniYaml.parse(yaml);
        } catch (RuntimeException e) {
            return new ReadResult(null, List.of("YAML illisible : " + e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> m)) {
            return new ReadResult(null, List.of("Racine YAML inattendue."));
        }
        QuestDraft d = new QuestDraft();
        d.id = plainId(str(m.get("id")));
        d.title = str(m.get("title"));
        d.description = str(m.get("description"));
        d.category = str(m.get("category"));
        d.icon = str(m.get("icon")).isBlank() ? "BOOK" : str(m.get("icon"));
        d.repeatable = bool(m.get("repeatable"));
        d.secret = bool(m.get("secret"));
        d.giver = str(m.get("giver"));
        for (Object p : asList(m.get("prerequisites"))) {
            String s = plainId(String.valueOf(p));
            if (!s.isBlank()) {
                d.prerequisites.add(s);
            }
        }
        for (Object so : asList(m.get("steps"))) {
            if (!(so instanceof Map<?, ?> sm)) {
                problems.add("étape mal formée");
                continue;
            }
            QuestDraft.Step step = new QuestDraft.Step(str(sm.get("id")));
            for (Object oo : asList(sm.get("objectives"))) {
                step.objectives.add(kvMap(oo, problems, "objectif"));
            }
            d.steps.add(step);
        }
        for (Object ro : asList(m.get("rewards"))) {
            d.rewards.add(kvMap(ro, problems, "récompense"));
        }
        if (m.get("variables") instanceof Map<?, ?> vm) {
            for (Map.Entry<?, ?> e : vm.entrySet()) {
                d.variables.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return new ReadResult(d, problems);
    }

    private static Map<String, String> kvMap(Object o, List<String> problems, String what) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(o instanceof Map<?, ?> m)) {
            problems.add(what + " mal formé");
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out.put(k.equals("type") ? "kind" : k, k.equals("type") ? v.toUpperCase(Locale.ROOT) : v);
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------

    static String nsId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "rpgquest:" + s;
    }

    public static String plainId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("rpgquest:") ? s.substring("rpgquest:".length()) : s;
    }

    private static String safeId(String raw) {
        String s = raw == null ? "" : raw.trim();
        return s.isBlank() ? "step" : s;
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** Guillemets doubles YAML, échappe {@code \ " \n}. */
    static String qq(String v) {
        String s = v == null ? "" : v;
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + '"';
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(str(o));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
