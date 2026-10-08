package com.lodygames.rpgquest.panel.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Analyse des conséquences d'une suppression de PNJ (issue #226).
 *
 * <p><strong>Les mêmes trois règles de conduite que #194</strong>, appliquées à un objet qui vit sur
 * cinq couches au lieu d'une :</p>
 *
 * <ol>
 *   <li><strong>Jamais de cascade.</strong> Supprimer une définition ne détruit pas l'entité
 *       Citizens ; détruire l'entité ne supprime pas la définition ; et <em>rien</em> ne supprime un
 *       dialogue. Chaque couche est une décision distincte, et le plan les présente comme telles.</li>
 *   <li><strong>Jamais de référence orpheline.</strong> Si une quête désigne encore ce PNJ — donneur,
 *       « parler à », remise — la suppression de sa définition est <strong>bloquée</strong>, en
 *       nommant les quêtes, plutôt que de laisser un contenu injouable.</li>
 *   <li><strong>Jamais de progression joueur touchée.</strong> La suppression est éditoriale.</li>
 * </ol>
 *
 * <p><strong>Et une quatrième, propre aux PNJ : sans relevé, aucune proposition.</strong> Tout ce
 * qu'on sait d'un PNJ vient du dernier {@code npc.list}. Si aucun relevé n'a été fait, une liste de
 * dépendances vide ne veut pas dire « aucune dépendance » : elle veut dire « on n'a pas regardé ».
 * Le plan refuse alors toute opération, et le dit.</p>
 */
public final class NpcDeletionAnalyzer {

    private NpcDeletionAnalyzer() {
    }

    /**
     * Calcule le plan.
     *
     * @param directory annuaire issu du dernier relevé {@code npc.list}
     * @param rawNpcId  identifiant saisi ou cliqué, dans n'importe quelle écriture
     */
    public static NpcDeletionPlan analyze(NpcDirectory directory, String rawNpcId) {
        String npcId = NpcView.plainKey(rawNpcId);
        List<NpcDeletionPlan.Layer> layers = new ArrayList<>();
        List<NpcDeletionPlan.Operation> operations = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (directory == null || !directory.available()) {
            notes.add("Aucun relevé du catalogue PNJ n'a encore été fait : impossible de savoir ce "
                    + "qui dépend de « " + npcId + " ». Aucune suppression n'est proposée — "
                    + "rafraîchissez le catalogue PNJ depuis la page « PNJ », puis revenez. Une "
                    + "liste de dépendances vide ne veut pas dire « aucune dépendance » quand on "
                    + "n'a pas regardé.");
            return new NpcDeletionPlan(npcId, npcId, false, Optional.empty(), layers, operations,
                    notes);
        }

        Optional<NpcView> maybe = directory.find(npcId);
        if (maybe.isEmpty()) {
            notes.add("« " + npcId + " » ne figure pas dans le dernier relevé du serveur : il n'y a "
                    + "rien à supprimer. Si vous venez de le créer, rafraîchissez le catalogue PNJ.");
            return new NpcDeletionPlan(npcId, npcId, true, Optional.empty(), layers, operations,
                    notes);
        }

        NpcView npc = maybe.get();
        String label = npc.displayName().isEmpty() ? npcId : npc.displayName();
        List<NpcView> dialogueHolders = directory.npcsLinkedToDialogue(npc.linkedDialogueId());
        List<NpcView> related = directory.relatedEntries(npc);

        appendLayers(layers, npc, dialogueHolders);
        List<String> contentRefs = contentReferences(npc);
        appendOperations(operations, npc, contentRefs);
        appendNotes(notes, npc, dialogueHolders, related, contentRefs);

        return new NpcDeletionPlan(npcId, label, true, Optional.of(npc), layers, operations, notes);
    }

    // ---- Les couches --------------------------------------------------------------------------

    private static void appendLayers(List<NpcDeletionPlan.Layer> layers, NpcView npc,
                             List<NpcView> dialogueHolders) {
        layers.add(new NpcDeletionPlan.Layer("Définition logique RPGQuest", npc.definitionPresent(),
                npc.definitionPresent()
                        ? "npcs/" + npc.id() + ".yml sur le serveur"
                                + (npc.enabled() ? "" : " (désactivée)")
                        : "absente — " + npc.provenance()));

        layers.add(new NpcDeletionPlan.Layer("Liaison RPGQuest ↔ Citizens", npc.citizensBound(),
                npc.citizensBound()
                        ? "« " + npc.id() + " » ↔ Citizens #"
                                + (npc.citizensNumericId() == null ? "?" : npc.citizensNumericId())
                                + (npc.bindingCount() > 1
                                        ? " (ATTENTION : " + npc.bindingCount() + " PNJ Citizens "
                                                + "portent ce tag, le comportement en jeu est ambigu)"
                                        : "")
                        : "aucune"));

        layers.add(new NpcDeletionPlan.Layer("PNJ Citizens physique",
                npc.citizensBound() && npc.citizensNumericId() != null,
                npc.citizensBound() && npc.citizensNumericId() != null
                        ? "#" + npc.citizensNumericId() + " — entité réelle, visible en jeu"
                        : npc.citizensBound()
                                ? "lié, mais le relevé ne donne pas son identifiant numérique : la "
                                        + "destruction est impossible sans cible explicite"
                                : "aucun"));

        layers.add(new NpcDeletionPlan.Layer("Dialogue lié", npc.hasLinkedDialogue(),
                npc.hasLinkedDialogue()
                        ? npc.linkedDialogueId() + " (" + npc.dialogueNodes() + " nœud(s), "
                                + npc.dialogueChoices() + " choix)"
                                + (dialogueHolders.size() > 1
                                        ? " — PARTAGÉ avec " + (dialogueHolders.size() - 1)
                                                + " autre(s) PNJ"
                                        : "")
                                + " — jamais supprimé par cette page"
                        : "aucun"));

        layers.add(new NpcDeletionPlan.Layer("Quêtes dont il est le donneur", !npc.questsGiven().isEmpty(),
                npc.questsGiven().isEmpty() ? "aucune" : String.join(", ", npc.questsGiven())));
        layers.add(new NpcDeletionPlan.Layer("Objectifs « parler à » (TALK_TO_NPC)",
                !npc.questsReferenced().isEmpty(),
                npc.questsReferenced().isEmpty() ? "aucun"
                        : String.join(", ", npc.questsReferenced())));
        layers.add(new NpcDeletionPlan.Layer("Remises (DELIVER_ITEM_TO_NPC)",
                !npc.questsDelivering().isEmpty(),
                npc.questsDelivering().isEmpty() ? "aucune"
                        : String.join(", ", npc.questsDelivering())));
        layers.add(new NpcDeletionPlan.Layer("Quêtes démarrées par son dialogue",
                !npc.dialogueStartsQuests().isEmpty(),
                npc.dialogueStartsQuests().isEmpty() ? "aucune"
                        : String.join(", ", npc.dialogueStartsQuests())));
    }

    /**
     * Les références de contenu qui <strong>bloquent</strong> la suppression de la définition. Les
     * trois sont traitées ensemble parce qu'elles ont la même conséquence — une quête qui désigne un
     * PNJ inexistant — mais nommées séparément parce qu'elles ne se corrigent pas de la même façon.
     */
    private static List<String> contentReferences(NpcView npc) {
        List<String> out = new ArrayList<>();
        if (!npc.questsGiven().isEmpty()) {
            out.add("il est le donneur de " + String.join(", ", npc.questsGiven())
                    + " — ces quêtes ne seraient plus proposables");
        }
        if (!npc.questsReferenced().isEmpty()) {
            out.add("il est la cible d'un objectif « parler à » dans "
                    + String.join(", ", npc.questsReferenced())
                    + " — ces objectifs deviendraient infranchissables");
        }
        if (!npc.questsDelivering().isEmpty()) {
            out.add("il est le destinataire d'une remise dans "
                    + String.join(", ", npc.questsDelivering())
                    + " — ces quêtes deviendraient infinissables");
        }
        return out;
    }

    // ---- Les opérations -----------------------------------------------------------------------

    private static void appendOperations(List<NpcDeletionPlan.Operation> operations, NpcView npc,
                                 List<String> contentRefs) {
        boolean hasNumeric = npc.citizensBound() && npc.citizensNumericId() != null;

        // A — la définition seule.
        List<String> aBlockers = new ArrayList<>();
        if (!npc.definitionPresent()) {
            aBlockers.add("Ce PNJ n'a pas de définition logique : il n'y a pas de fichier à "
                    + "supprimer. " + npc.provenance());
        }
        aBlockers.addAll(contentRefs.stream()
                .map(r -> "Référence encore active : " + r
                        + ". Corrigez-la d'abord (page Quêtes), puis revenez.")
                .toList());
        List<String> aEffects = new ArrayList<>();
        aEffects.add("Supprime npcs/" + npc.id() + ".yml sur le serveur, après sauvegarde dans "
                + "npc-backups/.");
        if (npc.citizensBound()) {
            aEffects.add("NE touche PAS au PNJ Citizens #"
                    + (npc.citizensNumericId() == null ? "?" : npc.citizensNumericId())
                    + " ni à sa liaison : il restera en jeu et apparaîtra comme « orphelin "
                    + "Citizens » au prochain relevé. Pour l'éviter, déliez-le d'abord (B) ou "
                    + "utilisez le nettoyage complet (D).");
        }
        if (npc.hasLinkedDialogue()) {
            aEffects.add("NE supprime PAS le dialogue « " + npc.linkedDialogueId() + " ».");
        }
        aEffects.add("N'efface aucune progression de joueur.");
        operations.add(new NpcDeletionPlan.Operation(NpcDeletionPlan.Op.DEFINITION_ONLY,
                "Supprimer la définition logique seulement",
                "Retire l'identité RPGQuest du PNJ. Le PNJ Citizens, s'il existe, reste en jeu.",
                aBlockers, aEffects, false));

        // B — délier. Réversible : on peut relier.
        List<String> bBlockers = new ArrayList<>();
        if (!npc.citizensBound()) {
            bBlockers.add("Aucune liaison Citizens : il n'y a rien à délier.");
        } else if (npc.citizensNumericId() == null) {
            bBlockers.add("Le relevé ne donne pas l'identifiant numérique Citizens de cette "
                    + "liaison : délier sans cible explicite risquerait de retirer la mauvaise. "
                    + "Rafraîchissez le catalogue PNJ.");
        }
        operations.add(new NpcDeletionPlan.Operation(NpcDeletionPlan.Op.UNLINK_CITIZENS,
                "Délier le PNJ Citizens (sans le supprimer)",
                "Retire la liaison. Le PNJ Citizens reste en jeu, et la définition RPGQuest reste "
                        + "en place — elle apparaîtra simplement « à lier ».",
                bBlockers,
                List.of("Retire la liaison « " + npc.id() + " » ↔ Citizens #"
                                + (npc.citizensNumericId() == null ? "?" : npc.citizensNumericId())
                                + ".",
                        "CONSERVE le PNJ Citizens, la définition et le dialogue.",
                        "Réversible : la fiche proposera de relier."),
                true));

        // C — détruire l'entité Citizens.
        List<String> cBlockers = new ArrayList<>();
        if (!hasNumeric) {
            cBlockers.add(npc.citizensBound()
                    ? "Le relevé ne donne pas l'identifiant numérique Citizens : une destruction "
                            + "sans cible explicite pourrait détruire le mauvais PNJ. Rafraîchissez "
                            + "le catalogue PNJ."
                    : "Aucun PNJ Citizens lié : il n'y a rien à détruire en jeu.");
        }
        operations.add(new NpcDeletionPlan.Operation(NpcDeletionPlan.Op.DELETE_CITIZENS,
                "Supprimer le PNJ Citizens physique",
                "Détruit l'entité en jeu et retire sa liaison. La définition RPGQuest et le "
                        + "dialogue restent intacts.",
                cBlockers,
                List.of("Détruit définitivement le PNJ Citizens #"
                                + (npc.citizensNumericId() == null ? "?" : npc.citizensNumericId())
                                + " — seulement celui-là : le serveur revérifie son UUID avant "
                                + "d'agir, donc un identifiant recyclé ne peut pas faire détruire "
                                + "un voisin.",
                        "Retire la liaison devenue sans objet.",
                        "CONSERVE la définition « " + npc.id() + " » et le dialogue."),
                false));

        // D — nettoyage complet. Jamais le dialogue.
        List<String> dBlockers = new ArrayList<>(aBlockers);
        if (!npc.definitionPresent()) {
            dBlockers.clear();
            dBlockers.add("Sans définition logique, il n'y a pas de « nettoyage complet » à faire : "
                    + "traitez la couche réellement présente (ci-dessus).");
        }
        List<String> dEffects = new ArrayList<>();
        if (hasNumeric) {
            dEffects.add("Détruit le PNJ Citizens #" + npc.citizensNumericId()
                    + " et retire sa liaison.");
        } else if (npc.citizensBound()) {
            dEffects.add("Retire la liaison Citizens (l'entité n'a pas d'identifiant numérique "
                    + "connu et ne sera pas détruite).");
        }
        dEffects.add("Supprime npcs/" + npc.id() + ".yml, après sauvegarde.");
        if (npc.hasLinkedDialogue()) {
            dEffects.add("NE supprime PAS le dialogue « " + npc.linkedDialogueId()
                    + " » : un dialogue peut être partagé, et aucune suppression de PNJ ne doit "
                    + "l'emporter avec elle.");
        }
        dEffects.add("N'efface aucune progression de joueur.");
        operations.add(new NpcDeletionPlan.Operation(NpcDeletionPlan.Op.FULL_CLEANUP,
                "Nettoyage complet (définition + liaison + PNJ Citizens)",
                "Les trois couches d'un coup, dans l'ordre. À réserver à un PNJ de test dont on "
                        + "a lu les dépendances ci-dessus.",
                dBlockers, dEffects, false));
    }

    // ---- Les notes ----------------------------------------------------------------------------

    private static void appendNotes(List<String> notes, NpcView npc, List<NpcView> dialogueHolders,
                            List<NpcView> related, List<String> contentRefs) {
        notes.add("Aucune progression de joueur n'est effacée : la suppression est éditoriale. Les "
                + "lignes déjà enregistrées restent en base et deviennent sans objet.");
        notes.add("Aucune opération de cette page ne supprime un dialogue. Le panel ne sait pas "
                + "supprimer un dialogue, et c'est volontaire : un dialogue peut être porté par "
                + "plusieurs PNJ.");

        if (npc.hasLinkedDialogue() && dialogueHolders.size() > 1) {
            List<String> others = dialogueHolders.stream()
                    .filter(h -> !h.id().equalsIgnoreCase(npc.id()))
                    .map(NpcView::id)
                    .toList();
            notes.add("Le dialogue « " + npc.linkedDialogueId() + " » est PARTAGÉ : il est aussi "
                    + "déclaré par " + String.join(", ", others) + ". Il reste donc utilisé après "
                    + "la suppression de ce PNJ, et il ne faut surtout pas y toucher.");
        }
        if (npc.dialogueNamedDifferently()) {
            notes.add("Le dialogue lié ne porte pas le nom du PNJ : « " + npc.id()
                    + " » déclare « " + npc.linkedDialogueId() + " ». Après suppression de la "
                    + "définition, ce dialogue n'aura plus de porteur et réapparaîtra dans le "
                    + "catalogue comme une entrée sans définition — c'est explicable, et c'est "
                    + "exactement le cas qu'il faut comprendre avant de supprimer.");
        }
        if (!related.isEmpty()) {
            notes.add("Entrées apparentées dans le catalogue : " + related.stream()
                    .map(r -> r.id() + (r.orphan() ? " (sans définition)" : " (définie)"))
                    .reduce((a, b) -> a + ", " + b).orElse("")
                    + ". Vérifiez laquelle vous visez vraiment.");
        }
        if (npc.bindingCount() > 1) {
            notes.add("ATTENTION — " + npc.bindingCount() + " PNJ Citizens portent le tag « "
                    + npc.id() + " ». Les opérations ci-dessus ne traitent que la liaison dont "
                    + "l'identifiant numérique est affiché : les autres resteront, et resteront "
                    + "ambiguës en jeu.");
        }
        if (!contentRefs.isEmpty()) {
            notes.add("Les références de contenu listées ne sont pas corrigées automatiquement : "
                    + "retirer un donneur ou réaffecter un objectif est une décision éditoriale, "
                    + "pas un nettoyage mécanique.");
        }
        notes.add("Les suppressions passent par l'agent et sont donc ASYNCHRONES : le serveur "
                + "revérifie les dépendances au moment d'exécuter, et peut refuser même si cet "
                + "aperçu proposait l'opération — c'est le cas si quelque chose a changé entre "
                + "les deux. Le résultat se lit dans le journal d'actions.");
        if (npc.orphan()) {
            notes.add("Remédiations possibles pour une entrée sans définition : créer la définition "
                    + "depuis la fiche du PNJ, ou corriger la référence qui la fait exister (le "
                    + "champ « dialogueId » d'une définition existante, ou le « giver » d'une "
                    + "quête). " + npc.provenance());
        }
    }
}
