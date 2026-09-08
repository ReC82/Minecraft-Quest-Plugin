package com.lodygames.rpgquest.dialogue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dérivation <strong>pure</strong> (aucun type Bukkit, aucune I/O) du catalogue de dialogues pour
 * l'action agent {@code dialogue.list} et la page {@code /dialogues} — bases d'un futur éditeur
 * (issue « V1 /dialogues »).
 *
 * <p>Prend en entrée des types simples extraits en amont de {@code YamlDialogueEngine} /
 * {@code YamlNpcEngine} / {@code YamlQuestEngine}, et produit une vue structurée : nœuds, choix,
 * <strong>actions et conditions typées</strong>, relations PNJ, quêtes référencées, et des
 * <em>warnings</em> de cohérence. La page reste rendable même en présence d'erreurs : les erreurs
 * de <em>chargement</em> (dialogue sans nœud, {@code start} invalide, id dupliqué, cycle
 * {@code OPEN_DIALOGUE}…) — qui empêchent un fichier de devenir une {@code DialogueDefinition} —
 * sont remontées à part dans {@link View#loadIssues()}.</p>
 */
public final class DialogueCatalog {

    // ---- Entrées (types simples) ------------------------------------------------------------

    /** Un dialogue réellement chargé. {@code id} = forme {@code namespace:key} en minuscules. */
    public record LogicalDialogue(String id, String startNodeId, List<Node> nodes) {
    }

    public record Node(String id, String speaker, String text, List<Choice> choices) {
    }

    public record Choice(String text, String next, List<Action> actions, List<Condition> conditions) {
    }

    /** Action de choix déjà typée par {@code DialogueDefinitionParser} — jamais une chaîne libre. */
    public record Action(String kind, String target, String value, String raw) {
    }

    public record Condition(String kind, String target, String value, String raw, boolean negated) {
    }

    /** Définition PNJ pointant vers un dialogue déclaré ({@code NpcDefinition.dialogue}). */
    public record NpcDialogueDecl(String npcId, String declaredDialogueId) {
    }

    public record LoadIssue(String file, String message) {
    }

    // ---- Sortie ---------------------------------------------------------------------------

    public record View(List<DialogueSummary> dialogues, List<LoadIssueSummary> loadIssues,
                       List<MissingDeclared> declaredButMissing, int total, int withWarnings, int nodeTotal) {
    }

    public record DialogueSummary(String id, String key, String startNodeId, List<String> linkedNpcIds,
                                  int nodeCount, int choiceCount, List<String> referencedQuestIds,
                                  List<String> startsQuestIds, List<NodeSummary> nodes, List<Warning> warnings) {
    }

    public record NodeSummary(String id, String speaker, String text, boolean start, boolean reachable,
                              List<ChoiceSummary> choices) {
    }

    public record ChoiceSummary(String text, String nextNodeId, List<ActionSummary> actions,
                                List<ConditionSummary> conditions) {
    }

    public record ActionSummary(String kind, String target, String value, String raw) {
    }

    public record ConditionSummary(String kind, String target, String value, String raw, boolean negated) {
    }

    /** {@code severity} ∈ {@code error} / {@code warning} / {@code info}. */
    public record Warning(String code, String severity, String message) {
    }

    public record LoadIssueSummary(String file, String message) {
    }

    public record MissingDeclared(String npcId, String dialogueId) {
    }

    private DialogueCatalog() {
    }

    /**
     * @param dialogues        dialogues chargés
     * @param loadIssues       erreurs de chargement (fichiers rejetés) — jamais bloquantes pour le rendu
     * @param npcDecls         définitions PNJ déclarant un dialogue ({@code NpcDefinition.dialogue})
     * @param canonicalNpcIds  ids PNJ « attendus » (définition, giver, TALK_TO_NPC, dialogue de convention)
     * @param knownQuestIds    ids de quête chargés, forme {@code namespace:key} en minuscules
     */
    public static View build(List<LogicalDialogue> dialogues, List<LoadIssue> loadIssues,
                             List<NpcDialogueDecl> npcDecls, Set<String> canonicalNpcIds,
                             Set<String> knownQuestIds) {
        Set<String> quests = lower(knownQuestIds);
        Set<String> canonical = lower(canonicalNpcIds);

        // npcId -> dialogue déclaré (normalisé) ; et l'inverse dialogueId -> npcIds qui le déclarent.
        Map<String, String> declaredByNpc = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> declaringNpcsByDialogue = new java.util.LinkedHashMap<>();
        for (NpcDialogueDecl d : npcDecls) {
            if (d.npcId() == null || d.declaredDialogueId() == null) {
                continue;
            }
            String npc = d.npcId().toLowerCase(Locale.ROOT);
            String dlg = d.declaredDialogueId().toLowerCase(Locale.ROOT);
            declaredByNpc.put(npc, dlg);
            declaringNpcsByDialogue.computeIfAbsent(dlg, k -> new LinkedHashSet<>()).add(npc);
        }

        Set<String> loadedIds = new LinkedHashSet<>();
        for (LogicalDialogue d : dialogues) {
            loadedIds.add(d.id().toLowerCase(Locale.ROOT));
        }

        List<DialogueSummary> out = new ArrayList<>();
        int nodeTotal = 0;
        int withWarnings = 0;
        for (LogicalDialogue d : dialogues) {
            String id = d.id().toLowerCase(Locale.ROOT);
            String key = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;

            Map<String, Node> byId = new java.util.LinkedHashMap<>();
            for (Node n : d.nodes()) {
                byId.put(n.id(), n);
            }
            Set<String> reachable = reachableFrom(d.startNodeId(), byId);

            List<Warning> warnings = new ArrayList<>();
            List<String> referencedQuests = new ArrayList<>();
            List<String> startsQuests = new ArrayList<>();
            int choiceCount = 0;

            List<NodeSummary> nodeSummaries = new ArrayList<>();
            for (Node n : d.nodes()) {
                List<ChoiceSummary> choiceSummaries = new ArrayList<>();
                for (Choice c : n.choices()) {
                    choiceCount++;
                    if (c.next() != null && !byId.containsKey(c.next())) {
                        warnings.add(new Warning("NEXT_MISSING", "error",
                                "nœud « " + n.id() + " » : un choix pointe vers « " + c.next() + " » (inexistant)."));
                    }
                    List<ActionSummary> acts = new ArrayList<>();
                    for (Action a : c.actions()) {
                        acts.add(new ActionSummary(a.kind(), a.target(), a.value(), a.raw()));
                        String q = questRefOf(a.kind(), a.target());
                        if (q != null) {
                            addOnce(referencedQuests, q);
                            if ("START_QUEST".equals(a.kind())) {
                                addOnce(startsQuests, q);
                            }
                            if (!quests.isEmpty() && !quests.contains(q)) {
                                warnings.add(new Warning("QUEST_REF_UNKNOWN", "warning",
                                        "action " + a.kind() + " du nœud « " + n.id() + " » référence une quête inconnue « " + q + " »."));
                            }
                        }
                    }
                    List<ConditionSummary> conds = new ArrayList<>();
                    for (Condition cond : c.conditions()) {
                        conds.add(new ConditionSummary(cond.kind(), cond.target(), cond.value(), cond.raw(), cond.negated()));
                        if ("QUEST_STATE".equals(cond.kind()) && cond.target() != null) {
                            String q = cond.target().toLowerCase(Locale.ROOT);
                            addOnce(referencedQuests, q);
                            if (!quests.isEmpty() && !quests.contains(q)) {
                                warnings.add(new Warning("QUEST_REF_UNKNOWN", "warning",
                                        "condition QUEST_STATE du nœud « " + n.id() + " » référence une quête inconnue « " + q + " »."));
                            }
                        }
                    }
                    choiceSummaries.add(new ChoiceSummary(c.text(), c.next(), acts, conds));
                }
                boolean isStart = n.id().equals(d.startNodeId());
                boolean isReachable = isStart || reachable.contains(n.id());
                if (!isReachable) {
                    warnings.add(new Warning("NODE_UNREACHABLE", "info",
                            "le nœud « " + n.id() + " » n'est atteignable depuis aucun choix (« start » = « " + d.startNodeId() + " »)."));
                }
                nodeSummaries.add(new NodeSummary(n.id(), n.speaker(), n.text(), isStart, isReachable, choiceSummaries));
            }

            // Relations PNJ : la convention rpgquest:<key> + toute définition qui déclare ce dialogue.
            List<String> linkedNpcIds = new ArrayList<>();
            boolean keyIsCanonical = canonical.contains(key);
            boolean keyHasDecl = declaredByNpc.containsKey(key);
            if (keyIsCanonical || keyHasDecl) {
                addOnce(linkedNpcIds, key);
            }
            for (String npc : declaringNpcsByDialogue.getOrDefault(id, Set.of())) {
                addOnce(linkedNpcIds, npc);
            }
            if (linkedNpcIds.isEmpty()) {
                warnings.add(new Warning("DIALOGUE_NO_NPC", "info",
                        "dialogue « " + id + " » relié à aucun PNJ logique (ni définition, ni convention rpgquest:<id>)."));
            }
            if (linkedNpcIds.size() > 1) {
                warnings.add(new Warning("MULTIPLE_NPCS", "info",
                        "plusieurs PNJ logiques pointent vers ce dialogue : " + linkedNpcIds + "."));
            }
            String declForKey = declaredByNpc.get(key);
            if (declForKey != null && !declForKey.equals(id)) {
                warnings.add(new Warning("DEFINITION_DIALOGUE_DIVERGES", "warning",
                        "la définition « " + key + " » déclare le dialogue « " + declForKey
                                + " », différent du dialogue de convention « " + id + " »."));
            }

            if (!warnings.isEmpty()) {
                withWarnings++;
            }
            nodeTotal += d.nodes().size();
            out.add(new DialogueSummary(id, key, d.startNodeId(), List.copyOf(linkedNpcIds),
                    d.nodes().size(), choiceCount, List.copyOf(referencedQuests), List.copyOf(startsQuests),
                    List.copyOf(nodeSummaries), List.copyOf(warnings)));
        }
        out.sort((a, b) -> {
            int wa = a.warnings().isEmpty() ? 1 : 0;
            int wb = b.warnings().isEmpty() ? 1 : 0;
            return wa != wb ? Integer.compare(wa, wb) : a.id().compareTo(b.id());
        });

        List<MissingDeclared> missing = new ArrayList<>();
        for (NpcDialogueDecl d : npcDecls) {
            String dlg = d.declaredDialogueId() == null ? "" : d.declaredDialogueId().toLowerCase(Locale.ROOT);
            if (!dlg.isEmpty() && !loadedIds.contains(dlg)) {
                missing.add(new MissingDeclared(d.npcId(), d.declaredDialogueId()));
            }
        }

        List<LoadIssueSummary> issues = new ArrayList<>();
        for (LoadIssue li : loadIssues) {
            issues.add(new LoadIssueSummary(li.file(), li.message()));
        }

        return new View(List.copyOf(out), List.copyOf(issues), List.copyOf(missing),
                out.size(), withWarnings, nodeTotal);
    }

    private static Set<String> reachableFrom(String start, Map<String, Node> byId) {
        Set<String> seen = new LinkedHashSet<>();
        if (!byId.containsKey(start)) {
            return seen;
        }
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            Node n = byId.get(queue.poll());
            if (n == null) {
                continue;
            }
            for (Choice c : n.choices()) {
                String next = c.next();
                if (next != null && byId.containsKey(next) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    /** {@code namespace:key} (minuscules) de la quête ciblée par une action liée à une quête, ou {@code null}. */
    private static String questRefOf(String kind, String target) {
        if (target == null) {
            return null;
        }
        return switch (kind) {
            case "START_QUEST", "ADVANCE_QUEST", "TURN_IN_QUEST" -> target.toLowerCase(Locale.ROOT);
            default -> null;
        };
    }

    private static void addOnce(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }

    private static Set<String> lower(Set<String> in) {
        if (in == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
