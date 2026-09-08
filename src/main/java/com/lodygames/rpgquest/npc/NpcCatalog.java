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
 * Vue <strong>catalogue</strong> des PNJ RPGQuest, dérivée sans aucune dépendance Bukkit/Citizens
 * (types simples uniquement) — testable en JUnit pur et réutilisable côté agent
 * ({@code BukkitAgentActions#npcDefinitions}) comme, à terme, pour la validation de
 * {@code /rpgadmin npc tag} (issue #66).
 *
 * <p>Un « PNJ RPGQuest » n'est pas une entité : c'est un <strong>identifiant logique</strong>
 * (fragment de {@code NamespacedKey}, ex. {@code guard}) qui peut apparaître dans plusieurs
 * sources indépendantes :</p>
 * <ul>
 *   <li>une <strong>liaison Citizens</strong> ({@code npc_citizens_bindings}) — le PNJ est
 *       réellement tagué en jeu ;</li>
 *   <li>un <strong>dialogue</strong> {@code rpgquest:<id>} — cliquer sur une entité taguée
 *       {@code <id>} ouvre ce dialogue (convention des listeners d'interaction) ;</li>
 *   <li>une quête qui déclare {@code giver: <id>} (#75) ;</li>
 *   <li>un objectif {@code TALK_TO_NPC} qui cible {@code <id>}.</li>
 * </ul>
 *
 * <p>Le catalogue croise ces sources et signale les incohérences de configuration les plus
 * courantes (référence sans PNJ, tag inutilisé, doublon…). Il ne lit jamais l'état du monde :
 * position, monde et détection des PNJ Citizens <em>non tagués</em> sont hors périmètre de cette
 * V1 (voir le rapport de session).</p>
 */
public final class NpcCatalog {

    private NpcCatalog() {
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

    /** Anomalie de configuration détectée pour un PNJ. {@code severity} ∈ {@code error|warning|info}. */
    public record Warning(String code, String severity, String message) {
    }

    /** Une entrée du catalogue : tout ce qu'on sait d'un id RPGQuest, données absentes = {@code null}. */
    public record NpcRow(String id, String displayName, Integer citizensNumericId, int bindingCount,
                         boolean bound, boolean hasDialogue, String dialogueId, int dialogueNodes,
                         int dialogueChoices, List<String> dialogueStartsQuests, List<String> questsGiven,
                         List<String> questsReferenced, List<String> sources, List<Warning> warnings) {
    }

    public record Result(List<NpcRow> npcs, List<String> canonicalIds, boolean citizensAvailable,
                         int total, int bound, int unbound, int withWarnings) {
    }

    /**
     * Construit le catalogue. Aucune des listes n'est modifiée ; l'ordre de sortie place les PNJ
     * en anomalie d'abord (erreur, puis avertissement), le reste par nom lisible.
     *
     * @param citizensAvailable Citizens actif sur le serveur — si {@code false}, les avertissements
     *                          « aucun PNJ tagué » sont dégradés en {@code info} (impossible de vérifier).
     */
    public static Result build(List<DialogueLink> dialogues, List<QuestLink> quests,
                               List<CitizensBinding> bindings, boolean citizensAvailable) {

        Map<String, DialogueLink> dialogueByNpc = new LinkedHashMap<>();
        for (DialogueLink d : dialogues) {
            if (d.npcId() != null && !d.npcId().isBlank()) {
                dialogueByNpc.putIfAbsent(d.npcId(), d);
            }
        }

        // id -> quêtes données / référencées
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

        // Ids « canoniques » = attendus par le contenu (dialogues + quêtes), jamais les tags eux-mêmes.
        Set<String> canonical = new TreeSet<>();
        canonical.addAll(dialogueByNpc.keySet());
        canonical.addAll(given.keySet());
        canonical.addAll(referenced.keySet());

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(canonical);
        allIds.addAll(bindingCount.keySet());

        List<NpcRow> rows = new ArrayList<>();
        for (String id : allIds) {
            DialogueLink d = dialogueByNpc.get(id);
            int count = bindingCount.getOrDefault(id, 0);
            List<String> givenQ = sorted(given.get(id));
            List<String> refQ = sorted(referenced.get(id));
            boolean referencedByQuest = !givenQ.isEmpty() || !refQ.isEmpty();

            List<String> sources = new ArrayList<>();
            if (count > 0) {
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
            if (count > 1) {
                warnings.add(new Warning("DUPLICATE_BINDING", "error",
                        count + " PNJ Citizens sont tagués avec « " + id + " » — comportement ambigu en jeu."));
            }
            if (count == 0) {
                if (referencedByQuest) {
                    String sev = citizensAvailable ? "warning" : "info";
                    String msg = "Référencé par " + questWord(givenQ.size() + refQ.size())
                            + " mais aucun PNJ Citizens n'est tagué « " + id + " »"
                            + (citizensAvailable ? " — dialogue et objectifs ne se déclencheront pas en jeu."
                                                 : " (Citizens inactif : vérification impossible).");
                    warnings.add(new Warning("QUEST_REF_NO_NPC", sev, msg));
                } else if (d != null) {
                    warnings.add(new Warning("DIALOGUE_NO_NPC", "info",
                            "Dialogue « " + d.dialogueId() + " » défini mais aucun PNJ tagué « " + id + " »."));
                }
            }
            if (count >= 1 && d == null && !referencedByQuest) {
                String hint = closestCanonical(id, canonical);
                warnings.add(new Warning("TAGGED_UNUSED", "info",
                        "PNJ tagué « " + id + " » mais aucun dialogue ni quête ne l'utilise."
                                + (hint == null ? "" : " Id canonique proche : « " + hint + " » ?")));
            }
            if (count >= 1 && !givenQ.isEmpty() && d == null) {
                warnings.add(new Warning("GIVER_NO_DIALOGUE", "info",
                        "Donneur de quête sans dialogue « rpgquest:" + id + " » — la quête ne peut pas être "
                                + "proposée en conversation."));
            }

            rows.add(new NpcRow(
                    id,
                    d == null || d.speaker() == null || d.speaker().isBlank() ? null : d.speaker(),
                    numericId.get(id),
                    count,
                    count >= 1,
                    d != null,
                    d == null ? null : d.dialogueId(),
                    d == null ? 0 : d.nodeCount(),
                    d == null ? 0 : d.choiceCount(),
                    d == null ? List.of() : List.copyOf(d.startsQuestIds() == null ? List.of() : d.startsQuestIds()),
                    givenQ,
                    refQ,
                    List.copyOf(sources),
                    List.copyOf(warnings)));
        }

        rows.sort((a, b) -> {
            int ra = rank(a.warnings());
            int rb = rank(b.warnings());
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return label(a).compareToIgnoreCase(label(b));
        });

        int bound = 0;
        int withWarnings = 0;
        for (NpcRow r : rows) {
            if (r.bound()) {
                bound++;
            }
            if (!r.warnings().isEmpty()) {
                withWarnings++;
            }
        }
        return new Result(rows, List.copyOf(canonical), citizensAvailable,
                rows.size(), bound, rows.size() - bound, withWarnings);
    }

    // ---- interne -----------------------------------------------------------------------------

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

    private static String questWord(int n) {
        return n <= 1 ? "une quête" : n + " quêtes";
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
     * correction du type « garde » → « guard ». {@code null} si rien d'assez proche ou si {@code id}
     * est déjà canonique.
     */
    static String closestCanonical(String id, Set<String> canonical) {
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
