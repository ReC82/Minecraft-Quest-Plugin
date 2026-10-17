package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #465 — différences <strong>de sens</strong> entre deux versions d'un dialogue : répliques,
 * réponses, conditions, effets, enchaînements et entrées, au lieu d'un diff de lignes YAML.
 *
 * <p>Sert à relire un dialogue réécrit par une IA avant de remplacer le brouillon : chaque
 * changement est nommé par la réplique et la réponse qu'il touche, et ce qui n'a pas bougé est
 * compté. Les réponses sont appariées par leur texte, puis, pour celles dont le texte a changé, par
 * leur rang parmi les restantes — une réponse reformulée apparaît donc « modifiée », pas
 * « supprimée + ajoutée ».</p>
 */
public final class DialogueSemanticDiff {

    public enum Kind { ADDED, REMOVED, CHANGED }

    /**
     * @param where  « Réplique « offer » », « Réplique « greeting », réponse « Du travail ? » »…
     * @param what   la nature du changement, en clair
     * @param before valeur avant (vide pour un ajout)
     * @param after  valeur après (vide pour une suppression)
     */
    public record Change(Kind kind, String where, String what, String before, String after) {
    }

    /** @param unchangedNodes répliques présentes des deux côtés et strictement identiques */
    public record Result(List<Change> changes, int unchangedNodes, int nodesBefore, int nodesAfter) {
        public Result {
            changes = List.copyOf(changes);
        }

        public boolean identical() {
            return changes.isEmpty();
        }

        public long count(Kind kind) {
            return changes.stream().filter(c -> c.kind() == kind).count();
        }
    }

    private DialogueSemanticDiff() {
    }

    public static Result compare(DialogueDraft before, DialogueDraft after) {
        List<Change> out = new ArrayList<>();
        if (!before.start.equals(after.start)) {
            out.add(new Change(Kind.CHANGED, "Dialogue", "Réplique de départ", before.start, after.start));
        }
        diffLists(out, "Dialogue", "Entrée conditionnelle", entries(before), entries(after));

        Map<String, DialogueDraft.Node> old = byId(before);
        Map<String, DialogueDraft.Node> neu = byId(after);
        int unchanged = 0;
        for (DialogueDraft.Node n : before.nodes) {
            if (!neu.containsKey(n.id)) {
                out.add(new Change(Kind.REMOVED, node(n.id), "Réplique supprimée", n.text, ""));
            }
        }
        for (DialogueDraft.Node n : after.nodes) {
            DialogueDraft.Node o = old.get(n.id);
            if (o == null) {
                out.add(new Change(Kind.ADDED, node(n.id), "Réplique ajoutée", "", n.text));
                continue;
            }
            int sizeBefore = out.size();
            compareNode(out, o, n);
            if (out.size() == sizeBefore) {
                unchanged++;
            }
        }
        return new Result(out, unchanged, before.nodes.size(), after.nodes.size());
    }

    private static void compareNode(List<Change> out, DialogueDraft.Node o, DialogueDraft.Node n) {
        String where = node(n.id);
        if (!o.speaker.equals(n.speaker)) {
            out.add(new Change(Kind.CHANGED, where, "Qui parle", o.speaker, n.speaker));
        }
        if (!o.text.equals(n.text)) {
            out.add(new Change(Kind.CHANGED, where, "Texte", o.text, n.text));
        }
        diffLists(out, where, "Variante", o.variants, n.variants);

        List<DialogueDraft.Choice> left = new ArrayList<>(o.choices);
        List<DialogueDraft.Choice> right = new ArrayList<>(n.choices);
        List<DialogueDraft.Choice[]> pairs = new ArrayList<>();
        for (DialogueDraft.Choice c : new ArrayList<>(right)) {
            DialogueDraft.Choice match = left.stream().filter(l -> l.text.equals(c.text)).findFirst().orElse(null);
            if (match != null) {
                pairs.add(new DialogueDraft.Choice[] {match, c});
                left.remove(match);
                right.remove(c);
            }
        }
        // Les réponses restantes : reformulées si elles occupent le même rang, sinon ajoutées/retirées.
        int common = Math.min(left.size(), right.size());
        for (int k = 0; k < common; k++) {
            pairs.add(new DialogueDraft.Choice[] {left.get(k), right.get(k)});
        }
        for (int k = common; k < left.size(); k++) {
            out.add(new Change(Kind.REMOVED, where, "Réponse supprimée", summary(left.get(k)), ""));
        }
        for (int k = common; k < right.size(); k++) {
            out.add(new Change(Kind.ADDED, where, "Réponse ajoutée", "", summary(right.get(k))));
        }
        for (DialogueDraft.Choice[] p : pairs) {
            compareChoice(out, where, p[0], p[1]);
        }
    }

    private static void compareChoice(List<Change> out, String nodeWhere, DialogueDraft.Choice o,
                                      DialogueDraft.Choice n) {
        String where = nodeWhere + ", réponse « " + n.text + " »";
        if (!o.text.equals(n.text)) {
            out.add(new Change(Kind.CHANGED, where, "Texte de la réponse", o.text,
                    n.text));
        }
        if (!o.next.trim().equals(n.next.trim())) {
            out.add(new Change(Kind.CHANGED, where, "Mène à", o.next.trim(), n.next.trim()));
        }
        diffLists(out, where, "Condition", formatted(o.conditions), formatted(n.conditions));
        diffLists(out, where, "Effet", formatted(o.actions), formatted(n.actions));
    }

    /** Ajouts / retraits d'une liste de valeurs comparées telles quelles (l'ordre seul n'est pas signalé). */
    private static void diffLists(List<Change> out, String where, String label, List<String> before,
                                  List<String> after) {
        String e = "Effet".equals(label) ? "" : "e";
        List<String> remaining = new ArrayList<>(after);
        for (String b : before) {
            if (!remaining.remove(b)) {
                out.add(new Change(Kind.REMOVED, where, label + " retiré" + e, b, ""));
            }
        }
        for (String a : remaining) {
            out.add(new Change(Kind.ADDED, where, label + " ajouté" + e, "", a));
        }
    }

    /** {@code QUEST_STATE quest=rpgquest:x state=ACTIVE} — le type d'abord, les paramètres dans l'ordre du fichier. */
    public static String format(Map<String, String> entry) {
        StringBuilder sb = new StringBuilder(entry.getOrDefault("type", "?"));
        for (Map.Entry<String, String> e : entry.entrySet()) {
            if (!"type".equals(e.getKey())) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static List<String> formatted(List<Map<String, String>> entries) {
        return entries.stream().map(DialogueSemanticDiff::format).toList();
    }

    private static List<String> entries(DialogueDraft d) {
        List<String> out = new ArrayList<>();
        for (DialogueDraft.Entry e : d.entries) {
            out.add("→ " + e.node + (e.conditions.isEmpty() ? "" : " si "
                    + String.join(" et ", formatted(e.conditions))));
        }
        return out;
    }

    private static String summary(DialogueDraft.Choice c) {
        StringBuilder sb = new StringBuilder("« " + c.text + " »");
        if (!c.next.isBlank()) {
            sb.append(" → ").append(c.next.trim());
        }
        for (Map<String, String> e : c.conditions) {
            sb.append(" ; si ").append(format(e));
        }
        for (Map<String, String> e : c.actions) {
            sb.append(" ; fait ").append(format(e));
        }
        return sb.toString();
    }

    private static Map<String, DialogueDraft.Node> byId(DialogueDraft d) {
        Map<String, DialogueDraft.Node> out = new LinkedHashMap<>();
        d.nodes.forEach(n -> out.putIfAbsent(n.id, n));
        return out;
    }

    private static String node(String id) {
        return "Réplique « " + id + " »";
    }
}
