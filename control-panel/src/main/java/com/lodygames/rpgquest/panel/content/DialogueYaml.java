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
                if (choice.close) {
                    sb.append("        actions:\n");
                    sb.append("          - type: CLOSE\n");
                } else if (choice.next != null && !choice.next.isBlank()) {
                    sb.append("        next: ").append(choice.next.trim()).append('\n');
                }
            }
        }
        return sb.toString();
    }

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
        c.text = str(cm.get("text"));
        c.next = str(cm.get("next"));
        List<Object> conditions = asList(cm.get("conditions"));
        List<Object> actions = asList(cm.get("actions"));
        for (Object a : actions) {
            if (a instanceof Map<?, ?> am && "CLOSE".equalsIgnoreCase(str(am.get("type")))) {
                c.close = true;
            }
        }
        boolean onlyClose = actions.stream().allMatch(
                a -> a instanceof Map<?, ?> am && "CLOSE".equalsIgnoreCase(str(am.get("type"))));
        c.simple = conditions.isEmpty() && onlyClose;
        return c;
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
