package com.lodygames.rpgquest.panel.npc;

import java.util.List;
import java.util.Locale;

/**
 * Une ligne du catalogue PNJ du serveur, projetée en type du panel (issues #225 et #226).
 *
 * <p><strong>Pourquoi une projection.</strong> Le relevé {@code npc.list} arrive en
 * {@code Map<String, Object>} : praticable pour afficher une liste, impossible à raisonner dessus.
 * Or les deux tickets demandent précisément de <em>raisonner</em> — savoir quel dialogue est
 * réellement lié à quel PNJ, d'où vient une entrée sans définition, ce qu'une suppression
 * casserait. Un type nommé rend ces questions lisibles, et testables sans serveur.</p>
 *
 * <p><strong>Deux identifiants de dialogue, et c'est le cœur du cas Mira.</strong> Une définition
 * <em>déclare</em> un dialogue ({@link #declaredDialogueId()}, champ {@code dialogueId} de
 * {@code npcs/<id>.yml}) et le serveur en <em>charge</em> un ({@link #loadedDialogueId()}). Les
 * deux ne portent pas forcément le même nom que le PNJ : {@code mira_cartographer} déclare
 * {@code rpgquest:mira_first_map}. Confondre les trois — id du PNJ, dialogue déclaré, dialogue
 * chargé — est exactement ce qui faisait proposer un second dialogue concurrent.</p>
 *
 * @param id                   identifiant logique RPGQuest
 * @param displayName          nom affiché, ou vide
 * @param definitionPresent    un fichier {@code npcs/<id>.yml} existe réellement
 * @param citizensBound        un PNJ Citizens est tagué avec cet identifiant
 * @param citizensNumericId    identifiant numérique Citizens, ou {@code null}
 * @param bindingCount         nombre de PNJ Citizens tagués (plus de 1 = ambigu en jeu)
 * @param enabled              définition active
 * @param declaredDialogueId   dialogue déclaré par la définition, ou vide
 * @param loadedDialogueId     dialogue réellement chargé et rattaché à ce PNJ, ou vide
 * @param questsGiven          quêtes dont ce PNJ est le donneur
 * @param questsReferenced     quêtes qui le citent (objectif « parler à », remise…)
 * @param sources              ce qui fait exister cette entrée : {@code DEFINITION}, {@code BINDING},
 *                             {@code DIALOGUE}, {@code QUEST_GIVER}, {@code QUEST_TALK}
 * @param state                état calculé par le moteur ({@code LINKED}, {@code UNDEFINED_REFERENCE}…)
 */
public record NpcView(String id, String displayName, boolean definitionPresent,
                      boolean citizensBound, Integer citizensNumericId, int bindingCount,
                      boolean enabled, String declaredDialogueId, String loadedDialogueId,
                      int dialogueNodes, int dialogueChoices, List<String> dialogueStartsQuests,
                      List<String> questsGiven, List<String> questsReferenced,
                      List<String> sources, String state, List<Warning> warnings) {

    /** Anomalie relevée par le moteur. {@code severity} ∈ {@code error|warning|info}. */
    public record Warning(String code, String severity, String message) {
    }

    public NpcView {
        id = id == null ? "" : id.trim();
        displayName = displayName == null ? "" : displayName;
        declaredDialogueId = clean(declaredDialogueId);
        loadedDialogueId = clean(loadedDialogueId);
        dialogueStartsQuests = List.copyOf(dialogueStartsQuests == null ? List.of() : dialogueStartsQuests);
        questsGiven = List.copyOf(questsGiven == null ? List.of() : questsGiven);
        questsReferenced = List.copyOf(questsReferenced == null ? List.of() : questsReferenced);
        sources = List.copyOf(sources == null ? List.of() : sources);
        state = state == null ? "" : state;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /**
     * Le dialogue réellement lié à ce PNJ, forme namespacée. Le dialogue <em>chargé</em> d'abord —
     * c'est ce que les joueurs voient ; à défaut celui que la définition déclare, qui peut n'être pas
     * encore chargé. Vide si le PNJ n'a aucun dialogue.
     */
    public String linkedDialogueId() {
        if (!loadedDialogueId.isEmpty()) {
            return loadedDialogueId;
        }
        return declaredDialogueId;
    }

    public boolean hasLinkedDialogue() {
        return !linkedDialogueId().isEmpty();
    }

    /** La clé nue du dialogue lié — c'est elle qui sert de nom de fichier. */
    public String linkedDialogueKey() {
        return plainKey(linkedDialogueId());
    }

    /**
     * Le dialogue lié porte-t-il un autre nom que le PNJ ? C'est le cas Mira, et c'est précisément
     * la situation où imposer {@code dialogueId = npcId} créerait un doublon.
     */
    public boolean dialogueNamedDifferently() {
        String key = linkedDialogueKey();
        return !key.isEmpty() && !key.equals(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Entrée <strong>sans définition logique</strong> : elle n'existe dans le catalogue que parce
     * qu'autre chose la référence. C'est le statut de la seconde entrée Mira.
     */
    public boolean orphan() {
        return !definitionPresent;
    }

    public boolean declaresSource(String source) {
        return sources.contains(source);
    }

    /**
     * D'où vient cette entrée, en français (issues #225 et #126).
     *
     * <p>Une entrée sans définition n'est pas une erreur mystérieuse : c'est la trace d'une
     * référence. Dire laquelle est la moitié du travail de nettoyage — on ne supprime pas une entrée
     * de catalogue, on supprime ou on corrige <em>ce qui la crée</em>.</p>
     */
    public String provenance() {
        if (definitionPresent) {
            return "Définition logique « npcs/" + id + ".yml » sur le serveur"
                    + (citizensBound ? ", liée à Citizens"
                            + (citizensNumericId == null ? "" : " #" + citizensNumericId) : "")
                    + ".";
        }
        StringBuilder sb = new StringBuilder("Aucune définition logique : cette entrée n'existe que "
                + "parce qu'elle est référencée — ");
        List<String> parts = new java.util.ArrayList<>();
        if (declaresSource("DIALOGUE")) {
            parts.add("par le dialogue « " + (loadedDialogueId.isEmpty()
                    ? "rpgquest:" + id : loadedDialogueId) + " », dont le catalogue déduit un PNJ "
                    + "porteur du même nom");
        }
        if (declaresSource("BINDING")) {
            parts.add("par un PNJ Citizens"
                    + (citizensNumericId == null ? "" : " #" + citizensNumericId)
                    + " tagué « " + id + " »");
        }
        if (declaresSource("QUEST_GIVER")) {
            parts.add("comme donneur de " + joinQuoted(questsGiven));
        }
        if (declaresSource("QUEST_TALK")) {
            parts.add("comme cible d'objectif de " + joinQuoted(questsReferenced));
        }
        if (parts.isEmpty()) {
            parts.add("par une source que le relevé ne précise pas");
        }
        return sb.append(String.join(" ; ", parts)).append('.').toString();
    }

    public boolean hasError() {
        return warnings.stream().anyMatch(w -> "error".equals(w.severity()));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Le relevé transporte parfois la chaîne {@code "null"} : elle ne veut dire que « absent ». */
    private static String clean(String raw) {
        String s = raw == null ? "" : raw.trim();
        return s.isEmpty() || "null".equals(s) ? "" : s;
    }

    static String plainKey(String id) {
        String s = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    private static String joinQuoted(List<String> values) {
        if (values.isEmpty()) {
            return "quête(s) non précisée(s)";
        }
        return (values.size() == 1 ? "la quête « " : "des quêtes « ")
                + String.join(" », « ", values) + " »";
    }
}
