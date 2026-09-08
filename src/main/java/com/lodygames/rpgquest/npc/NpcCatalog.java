package com.lodygames.rpgquest.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Vue <strong>catalogue</strong> des PNJ RPGQuest (V2 déclarative), dérivée sans aucune dépendance
 * Bukkit/Citizens — testable en JUnit pur, réutilisée côté agent
 * ({@code BukkitAgentActions#npcDefinitions}) et, à terme, pour la validation de
 * {@code /rpgadmin npc tag} (issue #66).
 *
 * <p>Le modèle distingue désormais deux choses :</p>
 * <ol>
 *   <li>la <strong>définition logique</strong> RPGQuest du PNJ ({@link LogicalDefinition},
 *       fichier {@code npcs/*.yml}) — indépendante du monde et de Citizens ;</li>
 *   <li>le <strong>binding physique Citizens</strong> éventuel ({@link CitizensBinding}).</li>
 * </ol>
 *
 * <p>La <strong>source canonique</strong> des ids devient la définition logique. Les autres
 * systèmes (dialogue {@code rpgquest:<id>}, {@code giver:} d'une quête, objectif {@code TALK_TO_NPC})
 * référencent cet id ; pendant la transition, un id référencé <em>sans</em> définition est signalé
 * comme <strong>erreur de contenu</strong> mais ne casse rien.</p>
 *
 * <p>Ne lit jamais l'état du monde : position, monde et détection des PNJ Citizens <em>non
 * tagués</em> restent hors périmètre.</p>
 */
public final class NpcCatalog {

    private NpcCatalog() {
    }

    /** Définition logique d'un PNJ (projection sans Bukkit de {@code NpcDefinition}). */
    public record LogicalDefinition(String id, String displayName, String description, String dialogueId,
                                    String role, boolean enabled) {
    }

    /** Un dialogue {@code rpgquest:<npcId>} et ses métadonnées utiles à l'admin. */
    public record DialogueLink(String npcId, String dialogueId, int nodeCount, int choiceCount,
                               List<String> startsQuestIds, String speaker) {
    }

    /** Les références d'une quête vers des PNJ : donneur éventuel + cibles {@code TALK_TO_NPC}. */
    public record QuestLink(String questId, String giverId, List<String> talkNpcIds) {
    }

    /** Une liaison Citizens ↔ id RPGQuest (id numérique Citizens pour l'affichage admin). */
    public record CitizensBinding(String npcId, Integer citizensNumericId) {
    }

    /** Anomalie de configuration. {@code severity} ∈ {@code error|warning|info}. */
    public record Warning(String code, String severity, String message) {
    }

    /**
     * Une entrée du catalogue. Données absentes = {@code null} / listes vides.
     *
     * @param state {@code LINKED} (définition + binding) / {@code NOT_LINKED} (définition prête, pas
     *              de binding) / {@code DISABLED} / {@code CITIZENS_ORPHAN} (binding sans définition)
     *              / {@code UNDEFINED_REFERENCE} (référencé par du contenu, aucune définition) /
     *              {@code BROKEN} (au moins une erreur : dialogue manquant, doublon…)
     */
    public record NpcRow(String id, String displayName, boolean logicalDefinitionPresent,
                         boolean citizensBindingPresent, Integer citizensNumericId, int bindingCount,
                         boolean enabled, String description, String role, String definedDialogueId,
                         boolean hasDialogue, String dialogueId, int dialogueNodes, int dialogueChoices,
                         List<String> dialogueStartsQuests, List<String> questsGiven,
                         List<String> questsReferenced, List<String> sources, String state,
                         List<Warning> warnings) {
    }

    public record Result(List<NpcRow> npcs, List<String> canonicalIds, List<String> definedIds,
                         boolean citizensAvailable, int total, int withDefinition, int withoutDefinition,
                         int bound, int withWarnings) {
    }

    public static Result build(List<LogicalDefinition> definitions, List<DialogueLink> dialogues,
                               List<QuestLink> quests, List<CitizensBinding> bindings, boolean citizensAvailable) {

        Map<String, LogicalDefinition> defById = new LinkedHashMap<>();
        Map<String, Integer> defCount = new LinkedHashMap<>();
        for (LogicalDefinition d : definitions) {
            if (d.id() == null || d.id().isBlank()) {
                continue;
            }
            defById.putIfAbsent(d.id(), d);
            defCount.merge(d.id(), 1, Integer::sum);
        }

        Map<String, DialogueLink> dialogueByNpc = new LinkedHashMap<>();
        Set<String> loadedDialogueIds = new LinkedHashSet<>();
        for (DialogueLink d : dialogues) {
            if (d.dialogueId() != null) {
                loadedDialogueIds.add(d.dialogueId().toLowerCase(Locale.ROOT));
            }
            if (d.npcId() != null && !d.npcId().isBlank()) {
                dialogueByNpc.putIfAbsent(d.npcId(), d);
            }
        }

        Map<String, Set<String>> given = new LinkedHashMap<>();
        Map<String, Set<String>> referenced = new LinkedHashMap<>();
        for (QuestLink q : quests) {
            if (q.giverId() != null && !q.giverId().isBlank()) {
                given.computeIfAbsent(q.giverId().trim(), k -> new LinkedHashSet<>()).add(q.questId());
            }
            for (String talk : q.talkNpcIds() == null ? List.<String>of() : q.talkNpcIds()) {
                if (talk != null && !talk.isBlank()) {
                    referenced.computeIfAbsent(talk.trim(), k -> new LinkedHashSet<>()).add(q.questId());
                }
            }
        }

        Map<String, Integer> bindingCount = new LinkedHashMap<>();
        Map<String, Integer> numericId = new LinkedHashMap<>();
        for (CitizensBinding b : bindings) {
            if (b.npcId() == null || b.npcId().isBlank()) {
                continue;
            }
            bindingCount.merge(b.npcId(), 1, Integer::sum);
            if (b.citizensNumericId() != null) {
                numericId.putIfAbsent(b.npcId(), b.citizensNumericId());
            }
        }

        // Registre canonique : la définition logique d'abord, puis (transition) les ids référencés.
        Set<String> definedIds = new TreeSet<>(defById.keySet());
        Set<String> canonical = new TreeSet<>(definedIds);
        canonical.addAll(dialogueByNpc.keySet());
        canonical.addAll(given.keySet());
        canonical.addAll(referenced.keySet());

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(canonical);
        allIds.addAll(bindingCount.keySet());

        List<NpcRow> rows = new ArrayList<>();
        for (String id : allIds) {
            LogicalDefinition def = defById.get(id);
            DialogueLink d = dialogueByNpc.get(id);
            int binds = bindingCount.getOrDefault(id, 0);
            List<String> givenQ = sorted(given.get(id));
            List<String> refQ = sorted(referenced.get(id));
            boolean referencedByContent = !givenQ.isEmpty() || !refQ.isEmpty() || d != null;

            List<String> sources = new ArrayList<>();
            if (def != null) {
                sources.add("DEFINITION");
            }
            if (binds > 0) {
                sources.add("BINDING");
            }
            if (d != null) {
                sources.add("DIALOGUE");
            }
            if (!givenQ.isEmpty()) {
                sources.add("QUEST_GIVER");
            }
            if (!refQ.isEmpty()) {
                sources.add("QUEST_TALK");
            }

            List<Warning> warnings = new ArrayList<>();
            if (defCount.getOrDefault(id, 0) > 1) {
                warnings.add(new Warning("DUPLICATE_DEFINITION", "error",
                        "Plusieurs définitions logiques pour « " + id + " »."));
            }
            if (binds > 1) {
                warnings.add(new Warning("DUPLICATE_BINDING", "error",
                        binds + " PNJ Citizens sont tagués avec « " + id + " » — comportement ambigu en jeu."));
            }
            if (def == null && (referencedByContent || binds > 0)) {
                String hint = closestCanonical(id, definedIds);
                String by = referenceSummary(!givenQ.isEmpty(), !refQ.isEmpty(), d != null, binds > 0);
                if (binds > 0) {
                    warnings.add(new Warning("BINDING_NO_DEFINITION", "error",
                            "PNJ Citizens tagué « " + id + " » sans définition logique RPGQuest."
                                    + (hint == null ? "" : " Id défini proche : « " + hint + " » ?")));
                } else {
                    warnings.add(new Warning("NO_DEFINITION", "error",
                            "Aucune définition logique RPGQuest pour « " + id + " » (référencé par " + by
                                    + "). À migrer : créer la définition."
                                    + (hint == null ? "" : " Id défini proche : « " + hint + " » ?")));
                }
            }
            if (def != null) {
                if (def.dialogueId() != null && !loadedDialogueIds.contains(def.dialogueId().toLowerCase(Locale.ROOT))) {
                    warnings.add(new Warning("DIALOGUE_MISSING", "error",
                            "La définition référence le dialogue « " + def.dialogueId() + " » qui n'est pas chargé."));
                }
                if (!def.enabled()) {
                    warnings.add(new Warning("DISABLED", "info", "Définition désactivée (enabled: false)."));
                } else if (binds == 0) {
                    warnings.add(new Warning("NOT_LINKED", "info",
                            "Définition prête — aucun PNJ Citizens tagué « " + id + " » (à créer / lier en jeu)."));
                }
                if (!givenQ.isEmpty() && d == null) {
                    warnings.add(new Warning("GIVER_NO_DIALOGUE", "info",
                            "Donneur de quête sans dialogue « rpgquest:" + id + " » — la quête ne peut pas être "
                                    + "proposée en conversation."));
                }
            }

            String displayName = def != null ? def.displayName()
                    : (d != null && d.speaker() != null && !d.speaker().isBlank() ? d.speaker() : null);
            String state = state(def, binds, warnings);

            rows.add(new NpcRow(
                    id, displayName,
                    def != null, binds > 0, numericId.get(id), binds,
                    def == null || def.enabled(),
                    def == null ? null : def.description(),
                    def == null ? null : def.role(),
                    def == null ? null : def.dialogueId(),
                    d != null, d == null ? null : d.dialogueId(),
                    d == null ? 0 : d.nodeCount(), d == null ? 0 : d.choiceCount(),
                    d == null ? List.of() : List.copyOf(d.startsQuestIds() == null ? List.of() : d.startsQuestIds()),
                    givenQ, refQ, List.copyOf(sources), state, List.copyOf(warnings)));
        }

        rows.sort((a, b) -> {
            int ra = rank(a.warnings());
            int rb = rank(b.warnings());
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return label(a).compareToIgnoreCase(label(b));
        });

        int withDefinition = 0;
        int bound = 0;
        int withWarnings = 0;
        for (NpcRow r : rows) {
            if (r.logicalDefinitionPresent()) {
                withDefinition++;
            }
            if (r.citizensBindingPresent()) {
                bound++;
            }
            if (!r.warnings().isEmpty()) {
                withWarnings++;
            }
        }
        return new Result(rows, List.copyOf(canonical), List.copyOf(definedIds), citizensAvailable,
                rows.size(), withDefinition, rows.size() - withDefinition, bound, withWarnings);
    }

    // ---- interne -----------------------------------------------------------------------------

    private static String state(LogicalDefinition def, int binds, List<Warning> warnings) {
        boolean hasError = warnings.stream().anyMatch(w -> "error".equals(w.severity()));
        if (def == null) {
            return binds > 0 ? "CITIZENS_ORPHAN" : "UNDEFINED_REFERENCE";
        }
        boolean dialogueMissing = warnings.stream().anyMatch(w -> "DIALOGUE_MISSING".equals(w.code()));
        if (dialogueMissing) {
            return "BROKEN";
        }
        if (!def.enabled()) {
            return "DISABLED";
        }
        return binds > 0 ? "LINKED" : "NOT_LINKED";
    }

    private static String referenceSummary(boolean giver, boolean talk, boolean dialogue, boolean binding) {
        List<String> parts = new ArrayList<>();
        if (giver) {
            parts.add("donneur de quête");
        }
        if (talk) {
            parts.add("objectif « parler à »");
        }
        if (dialogue) {
            parts.add("dialogue");
        }
        if (binding) {
            parts.add("binding Citizens");
        }
        return parts.isEmpty() ? "—" : String.join(" / ", parts);
    }

    private static int rank(List<Warning> warnings) {
        boolean error = warnings.stream().anyMatch(w -> "error".equals(w.severity()));
        boolean warn = warnings.stream().anyMatch(w -> "warning".equals(w.severity()));
        if (error) {
            return 0;
        }
        if (warn) {
            return 1;
        }
        return warnings.isEmpty() ? 3 : 2;
    }

    private static String label(NpcRow r) {
        return r.displayName() != null ? r.displayName() : r.id();
    }

    private static List<String> sorted(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    /**
     * Id canonique le plus proche de {@code id} (distance de Levenshtein ≤ 2), pour suggérer une
     * correction du type « garde » → « guard ». {@code null} si rien d'assez proche ou si
     * {@code id} figure déjà dans {@code canonical}.
     */
    public static String closestCanonical(String id, Set<String> canonical) {
        if (id == null || canonical.contains(id)) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : canonical) {
            int distance = levenshtein(lower, candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= 2 && best != null && !best.equalsIgnoreCase(id) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}
