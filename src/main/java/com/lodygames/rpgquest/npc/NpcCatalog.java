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
 * <p><strong>Le dialogue d'un PNJ, et pourquoi deux règles au lieu d'une (issue #225).</strong> Le
 * rattachement se lit dans cet ordre :</p>
 * <ol>
 *   <li>le dialogue que la définition <em>déclare</em> par son champ {@code dialogueId} ;</li>
 *   <li>à défaut, le dialogue qui porte le <em>nom</em> du PNJ — la convention historique.</li>
 * </ol>
 *
 * <p>N'appliquer que la seconde produisait deux défauts visibles. Un PNJ qui déclare un dialogue
 * nommé autrement apparaissait <em>sans</em> dialogue, bien qu'il en ait un, chargé et entendu par
 * les joueurs. Et le dialogue déclaré, puisque personne ne le réclamait, fabriquait à son tour une
 * entrée de catalogue : un « PNJ » sans définition, portant le nom du dialogue, que rien ne
 * permettait de corriger puisqu'il n'était la faute de personne. C'est l'origine exacte des deux
 * entrées « Mira » — {@code mira_cartographer} (définition, Citizens #9) déclarant
 * {@code rpgquest:mira_first_map}, et {@code mira_first_map} « sans définition ». Un dialogue
 * revendiqué par une définition n'est donc plus déduit en PNJ ; un dialogue que personne ne
 * revendique l'est toujours, avec une anomalie qui dit désormais d'où elle vient.</p>
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

    /**
     * Les références d'une quête vers des PNJ : donneur éventuel, cibles {@code TALK_TO_NPC} et
     * destinataires {@code DELIVER_ITEM_TO_NPC}.
     *
     * <p>Les remises sont distinguées des conversations depuis #226 : les deux sont des références
     * bloquantes pour une suppression de PNJ, mais elles ne se corrigent pas de la même façon —
     * une remise qui perd son destinataire rend la quête <em>infinissable</em>, alors qu'un
     * « parler à » perdu se réaffecte. Les confondre à l'écran rendait l'une des deux invisible.</p>
     */
    public record QuestLink(String questId, String giverId, List<String> talkNpcIds,
                            List<String> deliverNpcIds) {

        /** Forme historique, sans remise. */
        public QuestLink(String questId, String giverId, List<String> talkNpcIds) {
            this(questId, giverId, talkNpcIds, List.of());
        }

        public QuestLink {
            talkNpcIds = List.copyOf(talkNpcIds == null ? List.of() : talkNpcIds);
            deliverNpcIds = List.copyOf(deliverNpcIds == null ? List.of() : deliverNpcIds);
        }
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
     * @param definedDialogueId dialogue que la <strong>définition déclare</strong>, tel qu'écrit
     * @param dialogueId        dialogue réellement <strong>chargé</strong> et rattaché à ce PNJ —
     *                          le déclaré s'il existe, sinon celui qui porte le nom du PNJ (#225)
     */
    public record NpcRow(String id, String displayName, boolean logicalDefinitionPresent,
                         boolean citizensBindingPresent, Integer citizensNumericId, int bindingCount,
                         boolean enabled, String description, String role, String definedDialogueId,
                         boolean hasDialogue, String dialogueId, int dialogueNodes, int dialogueChoices,
                         List<String> dialogueStartsQuests, List<String> questsGiven,
                         List<String> questsReferenced, List<String> questsDelivering,
                         List<String> sources, String state, List<Warning> warnings) {
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
        Map<String, DialogueLink> dialogueById = new LinkedHashMap<>();
        Set<String> loadedDialogueIds = new LinkedHashSet<>();
        for (DialogueLink d : dialogues) {
            if (d.dialogueId() != null) {
                loadedDialogueIds.add(d.dialogueId().toLowerCase(Locale.ROOT));
                dialogueById.putIfAbsent(d.dialogueId().toLowerCase(Locale.ROOT), d);
            }
            if (d.npcId() != null && !d.npcId().isBlank()) {
                dialogueByNpc.putIfAbsent(d.npcId(), d);
            }
        }

        // Issue #225 — les dialogues qu'une définition REVENDIQUE explicitement par son champ
        // « dialogueId ». Deux conséquences, et c'est tout le ticket :
        //
        //  1. le dialogue d'un PNJ est celui qu'il déclare, même s'il ne porte pas son nom. La
        //     convention « dialogueId == npcId » reste le défaut, elle n'est plus la seule règle ;
        //  2. un dialogue revendiqué ne fabrique plus une entrée PNJ fantôme. C'est l'origine
        //     exacte de la seconde entrée « Mira » : « mira_cartographer » déclare
        //     « rpgquest:mira_first_map », et le catalogue déduisait de ce dialogue un PNJ
        //     « mira_first_map » sans définition — une anomalie qui n'existait que dans le
        //     catalogue, et que personne ne pouvait corriger puisqu'elle n'était la faute de
        //     personne.
        Map<String, String> dialogueClaimedBy = new LinkedHashMap<>();
        for (LogicalDefinition d : defById.values()) {
            if (d.dialogueId() == null || d.dialogueId().isBlank()) {
                continue;
            }
            dialogueClaimedBy.putIfAbsent(d.dialogueId().trim().toLowerCase(Locale.ROOT), d.id());
        }

        Map<String, Set<String>> given = new LinkedHashMap<>();
        Map<String, Set<String>> referenced = new LinkedHashMap<>();
        Map<String, Set<String>> delivering = new LinkedHashMap<>();
        for (QuestLink q : quests) {
            if (q.giverId() != null && !q.giverId().isBlank()) {
                given.computeIfAbsent(q.giverId().trim(), k -> new LinkedHashSet<>()).add(q.questId());
            }
            for (String talk : q.talkNpcIds()) {
                if (talk != null && !talk.isBlank()) {
                    referenced.computeIfAbsent(talk.trim(), k -> new LinkedHashSet<>()).add(q.questId());
                }
            }
            for (String deliver : q.deliverNpcIds()) {
                if (deliver != null && !deliver.isBlank()) {
                    delivering.computeIfAbsent(deliver.trim(), k -> new LinkedHashSet<>()).add(q.questId());
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
        for (String npcId : dialogueByNpc.keySet()) {
            // Un dialogue déjà revendiqué par une AUTRE définition n'est pas un PNJ : c'est le
            // dialogue de ce PNJ-là. En déduire une entrée produisait le doublon « Mira » (#225).
            DialogueLink d = dialogueByNpc.get(npcId);
            String claimant = d.dialogueId() == null ? null
                    : dialogueClaimedBy.get(d.dialogueId().toLowerCase(Locale.ROOT));
            if (claimant != null && !claimant.equals(npcId)) {
                continue;
            }
            canonical.add(npcId);
        }
        canonical.addAll(given.keySet());
        canonical.addAll(referenced.keySet());
        canonical.addAll(delivering.keySet());

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(canonical);
        allIds.addAll(bindingCount.keySet());

        List<NpcRow> rows = new ArrayList<>();
        for (String id : allIds) {
            LogicalDefinition def = defById.get(id);
            // Issue #225 — le dialogue de ce PNJ : celui qu'il DÉCLARE d'abord, la convention de
            // nom ensuite. Dans l'autre ordre, « mira_cartographer » n'avait aucun dialogue (aucun
            // ne porte son nom) alors qu'elle en déclare un parfaitement chargé, et l'écran
            // affichait « pas encore chargé en jeu » pour un dialogue que les joueurs entendaient.
            DialogueLink declared = def == null || def.dialogueId() == null ? null
                    : dialogueById.get(def.dialogueId().trim().toLowerCase(Locale.ROOT));
            DialogueLink d = declared != null ? declared : dialogueByNpc.get(id);
            int binds = bindingCount.getOrDefault(id, 0);
            List<String> givenQ = sorted(given.get(id));
            List<String> refQ = sorted(referenced.get(id));
            List<String> deliverQ = sorted(delivering.get(id));
            boolean referencedByContent = !givenQ.isEmpty() || !refQ.isEmpty() || !deliverQ.isEmpty()
                    || d != null;

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
            if (!deliverQ.isEmpty()) {
                sources.add("QUEST_DELIVER");
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
                String by = referenceSummary(!givenQ.isEmpty(), !refQ.isEmpty(), !deliverQ.isEmpty(),
                        d != null, binds > 0);
                if (binds > 0) {
                    warnings.add(new Warning("BINDING_NO_DEFINITION", "error",
                            "PNJ Citizens tagué « " + id + " » sans définition logique RPGQuest."
                                    + (hint == null ? "" : " Id défini proche : « " + hint + " » ?")));
                } else if (d != null && givenQ.isEmpty() && refQ.isEmpty() && deliverQ.isEmpty()) {
                    // Issue #225 — l'entrée n'existe QUE parce qu'un dialogue porte ce nom, et que
                    // la convention en déduit un PNJ porteur. Dire « créer la définition » sans
                    // dire d'où vient l'entrée était la moitié du problème : on ne corrige pas une
                    // anomalie dont on ignore la cause. Les deux remèdes réels sont nommés.
                    warnings.add(new Warning("DIALOGUE_WITHOUT_NPC", "error",
                            "Aucune définition logique RPGQuest pour « " + id + " ». Cette entrée "
                                    + "n'existe que parce que le dialogue « " + d.dialogueId()
                                    + " » porte ce nom, et que le catalogue en déduit un PNJ "
                                    + "porteur : personne ne peut déclencher ce dialogue en l'état. "
                                    + "Deux remèdes — créer la définition « " + id + " », ou "
                                    + "rattacher ce dialogue à un PNJ existant via son champ "
                                    + "« dialogueId »."
                                    + (hint == null ? "" : " PNJ défini proche : « " + hint + " » ?")));
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
                    givenQ, refQ, deliverQ, List.copyOf(sources), state, List.copyOf(warnings)));
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

    private static String referenceSummary(boolean giver, boolean talk, boolean deliver,
                                           boolean dialogue, boolean binding) {
        List<String> parts = new ArrayList<>();
        if (giver) {
            parts.add("donneur de quête");
        }
        if (talk) {
            parts.add("objectif « parler à »");
        }
        if (deliver) {
            parts.add("objectif de remise");
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
