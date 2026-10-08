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
            // Issue #185 : « a, b » saisi dans le formulaire devient la liste YAML [a, b], relue
            // telle quelle par getStringList côté plugin. Le round-trip reste exact.
            case LIST -> inlineList(v);
            default -> qq(v);
        };
    }

    /** {@code "world_hub, wild"} → {@code [world_hub, wild]}, en ignorant les entrées vides. */
    private static String inlineList(String raw) {
        List<String> parts = splitList(raw);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String p = parts.get(i);
            sb.append(looksTechnical(p) ? p : qq(p));
        }
        return sb.append(']').toString();
    }

    /** Découpage commun formulaire → liste (issue #185), sans entrée vide ni espace parasite. */
    public static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
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
        Object root;
        try {
            root = MiniYaml.parse(yaml);
        } catch (RuntimeException e) {
            return new ReadResult(null, List.of("YAML illisible : " + e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> m)) {
            return new ReadResult(null, List.of("Racine YAML inattendue."));
        }
        return fromMap(m);
    }

    /**
     * Construit le brouillon depuis une table déjà désérialisée. Issue #109 : l'import de content
     * pack passe par <strong>exactement</strong> ce code, puisqu'un pack est un seul document YAML
     * dont chaque élément est une table de la même forme qu'un fichier source. Il n'existe donc pas
     * de second lecteur susceptible de diverger.
     */
    public static ReadResult fromMap(Map<?, ?> m) {
        List<String> problems = new ArrayList<>();
        QuestDraft d = new QuestDraft();
        d.id = plainId(str(m.get("id")));
        // « title » / « description » acceptent aussi une table de traductions côté moteur
        // (LocalizedText). L'éditeur ne sait pas la représenter : on le signale plutôt que de la
        // réduire à sa forme texte — un enregistrement l'écraserait sinon silencieusement.
        d.title = localizable(m.get("title"), "title", problems);
        d.description = localizable(m.get("description"), "description", problems);
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

    /** Champ texte qui peut être une table de traductions : non pris en charge, jamais aplati en silence. */
    private static String localizable(Object raw, String field, List<String> problems) {
        if (raw instanceof Map<?, ?>) {
            problems.add("« " + field + " » est une table de traductions : non prise en charge par l'éditeur "
                    + "(modifier ce fichier à la main pour ne pas perdre les locales).");
            return "";
        }
        return str(raw);
    }

    private static Map<String, String> kvMap(Object o, List<String> problems, String what) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(o instanceof Map<?, ?> m)) {
            problems.add(what + " mal formé");
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            // Issue #185 : une liste YAML revient dans le formulaire sous forme « a, b », jamais sous
            // la forme « [a, b] » de List.toString() qui serait ensuite réécrite comme une chaîne.
            String v = e.getValue() == null ? ""
                    : e.getValue() instanceof List<?> list
                            ? list.stream().map(x -> x == null ? "" : String.valueOf(x).trim())
                                    .filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.joining(", "))
                            : String.valueOf(e.getValue());
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
