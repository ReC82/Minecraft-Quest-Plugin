package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sérialiseur / relecteur YAML <strong>ciblé</strong> pour les dialogues du Control Panel (issue
 * #145). L'écriture reproduit <strong>exactement</strong> le squelette produit par le moteur
 * ({@code DialogueDefinitionYaml.render} — {@code dialogue.definition.create}) : un dialogue créé
 * depuis {@code /dialogues/new} est donc identique octet pour octet à un dialogue créé via l'agent,
 * et se relit fidèlement (garde-fou round-trip).
 *
 * <p>La relecture est « best-effort », comme {@link QuestYaml#read} : elle comprend le format
 * canonique <em>et</em> les fichiers écrits à la main (nœuds sous forme de map, choix avec
 * {@code next} / {@code actions} / {@code conditions}). Un fichier qui utilise des constructions non
 * supportées par {@link MiniYaml} (scalaires repliés {@code >}, ancres…) ressort avec
 * {@code problems} non vide — jamais masqué, jamais réécrit à l'aveugle.</p>
 *
 * <p>Depuis #145, les conditions et les actions d'un choix font partie du modèle relu <em>et</em>
 * émis : réenregistrer un dialogue existant depuis {@code /dialogues/edit} ne les efface plus. Le
 * garde-fou round-trip couvre donc réellement le contenu du fichier, et un fichier dont la
 * relecture signale un problème n'est jamais réécrit (voir {@code ContentEditorPages}).</p>
 */
public final class DialogueYaml {

    private DialogueYaml() {
    }

    // ---- émission (format canonique, identique au moteur) --------------------------------------

    public static String write(DialogueDraft d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dialogue RPGQuest — généré via le Control Panel (squelette éditable).\n");
        sb.append("# L'édition fine (nœuds, choix conditionnels, actions) passera par l'éditeur dédié.\n");
        sb.append("id: ").append(nsId(d.id)).append('\n');
        sb.append("start: ").append(safeId(d.start)).append('\n');
        sb.append("nodes:\n");
        for (DialogueDraft.Node node : d.nodes) {
            sb.append("  ").append(safeId(node.id)).append(":\n");
            sb.append("    speaker: ").append(quote(node.speaker)).append('\n');
            sb.append("    text: ").append(quote(node.text)).append('\n');
            sb.append("    choices:\n");
            for (DialogueDraft.Choice choice : node.choices) {
                sb.append("      - text: ").append(quote(choice.text)).append('\n');
                writeEntries(sb, "conditions", choice.conditions);
                writeEntries(sb, "actions", choice.actions);
                if (choice.next != null && !choice.next.isBlank()) {
                    sb.append("        next: ").append(choice.next.trim()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * {@code conditions:} / {@code actions:} d'un choix, à l'indentation du moteur
     * ({@code DialogueDefinitionWriter}) : le {@code type} porte le tiret, les autres clés suivent.
     */
    private static void writeEntries(StringBuilder sb, String key, List<Map<String, String>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append("        ").append(key).append(":\n");
        for (Map<String, String> entry : entries) {
            boolean first = true;
            for (Map.Entry<String, String> e : entry.entrySet()) {
                sb.append(first ? "          - " : "            ")
                        .append(e.getKey()).append(": ").append(entryScalar(e.getKey(), e.getValue())).append('\n');
                first = false;
            }
        }
    }

    /**
     * Même règle de citation que le moteur : seuls les champs de texte libre
     * ({@code key} / {@code value} / {@code permission} / {@code command}) sont mis entre
     * guillemets ; les identifiants et les nombres restent nus.
     */
    private static String entryScalar(String field, String value) {
        return QUOTED_ENTRY_FIELDS.contains(field) ? quote(value) : (value == null ? "" : value.trim());
    }

    private static final java.util.Set<String> QUOTED_ENTRY_FIELDS =
            java.util.Set.of("key", "value", "permission", "command");

    // ---- relecture --------------------------------------------------------------------

    public record ReadResult(DialogueDraft draft, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** Garde-fou round-trip (voir {@code QuestYaml.roundTripProblems}). */
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
     * Construit le brouillon depuis une table déjà désérialisée (issue #109, même raison que
     * {@code QuestYaml.fromMap}).
     */
    public static ReadResult fromMap(Map<?, ?> m) {
        List<String> problems = new ArrayList<>();
        DialogueDraft d = new DialogueDraft();
        d.id = plainId(str(m.get("id")));
        d.start = str(m.get("start")).isBlank() ? "start" : str(m.get("start"));

        Object nodesRaw = m.get("nodes");
        if (nodesRaw instanceof Map<?, ?> nodesMap) {
            for (Map.Entry<?, ?> e : nodesMap.entrySet()) {
                d.nodes.add(readNode(String.valueOf(e.getKey()).trim(), e.getValue(), problems));
            }
        } else if (nodesRaw != null) {
            problems.add("« nodes » doit être une map de nœuds.");
        }
        return new ReadResult(d, problems);
    }

    private static DialogueDraft.Node readNode(String id, Object raw, List<String> problems) {
        DialogueDraft.Node node = new DialogueDraft.Node(id);
        if (!(raw instanceof Map<?, ?> nm)) {
            problems.add("nœud « " + id + " » mal formé.");
            return node;
        }
        node.speaker = str(nm.get("speaker"));
        Object text = nm.get("text");
        if (text instanceof Map<?, ?>) {
            problems.add("nœud « " + id + " » : texte localisé multi-locale non pris en charge par l'éditeur.");
            node.text = "";
        } else {
            node.text = str(text);
        }
        for (Object co : asList(nm.get("choices"))) {
            node.choices.add(readChoice(co, id, problems));
        }
        return node;
    }

    private static DialogueDraft.Choice readChoice(Object raw, String nodeId, List<String> problems) {
        DialogueDraft.Choice c = new DialogueDraft.Choice();
        if (!(raw instanceof Map<?, ?> cm)) {
            problems.add("choix mal formé (nœud « " + nodeId + " »).");
            return c;
        }
        Object text = cm.get("text");
        if (text instanceof Map<?, ?>) {
            problems.add("choix du nœud « " + nodeId + " » : texte localisé multi-locale non pris en charge "
                    + "par l'éditeur.");
            c.text = "";
        } else {
            c.text = str(text);
        }
        c.next = str(cm.get("next"));
        List<Object> conditions = asList(cm.get("conditions"));
        List<Object> actions = asList(cm.get("actions"));
        for (Object o : conditions) {
            c.conditions.add(readEntry(o, nodeId, "condition", problems));
        }
        for (Object o : actions) {
            c.actions.add(readEntry(o, nodeId, "action", problems));
            if (o instanceof Map<?, ?> am && "CLOSE".equalsIgnoreCase(str(am.get("type")))) {
                c.close = true;
            }
        }
        boolean onlyClose = actions.stream().allMatch(
                a -> a instanceof Map<?, ?> am && "CLOSE".equalsIgnoreCase(str(am.get("type"))));
        c.simple = conditions.isEmpty() && onlyClose;
        return c;
    }

    /** Une entrée {@code conditions[]} / {@code actions[]} relue telle quelle, ordre des clés préservé. */
    private static Map<String, String> readEntry(Object raw, String nodeId, String what, List<String> problems) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(what + " mal formée dans le nœud « " + nodeId + " ».");
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                problems.add(what + " du nœud « " + nodeId + " » : valeur imbriquée non prise en charge "
                        + "pour « " + e.getKey() + " ».");
                continue;
            }
            out.put(String.valueOf(e.getKey()).trim(), v == null ? "" : String.valueOf(v).trim());
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Id « nu » (sans préfixe {@code rpgquest:}) servant de nom de fichier et de clé de fusion. */
    public static String plainId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("rpgquest:") ? s.substring("rpgquest:".length()) : s;
    }

    static String nsId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "rpgquest:" + s;
    }

    private static String safeId(String raw) {
        String s = raw == null ? "" : raw.trim();
        return s.isBlank() ? "start" : s;
    }

    /** Guillemets doubles, échappe {@code \} et {@code "} (identique au writer du moteur). */
    static String quote(String value) {
        String s = value == null ? "" : value;
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
