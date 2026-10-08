package com.lodygames.rpgquest.panel.npc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Quel dialogue une génération doit-elle viser, pour un PNJ donné (issue #225) ?
 *
 * <p><strong>Le problème réel.</strong> L'atelier IA affichait « l'identifiant du dialogue est celui
 * du PNJ : c'est la convention du moteur ». C'est vrai <em>par défaut</em>, et faux dès qu'une
 * définition déclare autre chose : {@code mira_cartographer} déclare
 * {@code rpgquest:mira_first_map}. Appliquer la convention aveuglément proposait donc un second
 * dialogue {@code rpgquest:mira_cartographer}, techniquement valide, que rien ne reliait au PNJ —
 * lequel restait pointé sur l'ancien. Deux dialogues, un orphelin, et aucun avertissement.</p>
 *
 * <p><strong>La réponse : demander, avant de dépenser des jetons.</strong> Quand le PNJ porte déjà un
 * dialogue qui ne s'appelle pas comme lui, il n'existe pas de bonne valeur par défaut — seulement
 * deux intentions légitimes, et l'administrateur est le seul à savoir laquelle est la sienne. La
 * décision est donc <strong>bloquante</strong> et arrive <em>avant</em> l'appel à l'IA.</p>
 *
 * <p>Un troisième cas — « créer un dialogue supplémentaire, non lié » — n'est volontairement pas
 * proposé : le moteur ne rattache un dialogue à un PNJ que par le champ {@code dialogueId} de sa
 * définition ou par la convention de nom. Un dialogue qui n'est ni l'un ni l'autre n'est joignable
 * par personne, et apparaît lui-même comme une entrée PNJ sans définition. Ce n'est pas un concept
 * supporté, c'est la panne qu'on est en train de réparer.</p>
 */
public final class DialogueTargetDecision {

    /** Les deux intentions réellement supportées par le moteur. */
    public enum Choice {
        /** Reprendre le dialogue déjà lié : la proposition le remplacera, via l'arbitrage d'import. */
        EDIT_EXISTING,
        /**
         * Écrire un nouveau dialogue nommé comme le PNJ, et <strong>changer le lien</strong>. Le
         * changement de lien n'est pas fait ici : l'atelier n'écrit rien, et la définition PNJ vit
         * sur le serveur. L'écran le dit, et renvoie vers la fiche du PNJ.
         */
        NEW_REPLACING_LINK;

        public static Optional<Choice> of(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            for (Choice c : values()) {
                if (c.name().equalsIgnoreCase(raw.trim())) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }
    }

    private DialogueTargetDecision() {
    }

    /**
     * Ce qu'il faut faire de la demande.
     *
     * @param dialogueKey   clé du dialogue à produire ; vide = laissée à l'IA
     * @param needsDecision la génération doit s'arrêter et poser la question ci-dessous
     * @param npc           la fiche du PNJ concerné, {@code null} si introuvable ou non demandé
     * @param existingKey   clé du dialogue déjà lié, vide s'il n'y en a pas
     * @param related       autres entrées du catalogue qui parlent du même personnage (#126)
     * @param note          ce qu'il faut dire à l'administrateur — jamais une surprise silencieuse
     */
    public record Outcome(String dialogueKey, boolean needsDecision, NpcView npc, String existingKey,
                          List<NpcView> related, String note) {

        public Outcome {
            dialogueKey = dialogueKey == null ? "" : dialogueKey;
            existingKey = existingKey == null ? "" : existingKey;
            related = List.copyOf(related == null ? List.of() : related);
        }

        public boolean hasNote() {
            return note != null && !note.isBlank();
        }
    }

    /**
     * Résout la cible.
     *
     * @param directory annuaire PNJ du serveur ; indisponible ⇒ on ne bloque pas, mais on le dit
     * @param rawNpc    identifiant du PNJ saisi dans le formulaire
     * @param rawChoice décision éventuellement déjà prise ({@link Choice})
     */
    public static Outcome resolve(NpcDirectory directory, String rawNpc, String rawChoice) {
        String npcKey = NpcView.plainKey(rawNpc);
        if (npcKey.isEmpty()) {
            // Aucun PNJ imposé : rien à arbitrer, et le formulaire dit déjà que laisser vide oblige
            // l'IA à deviner.
            return new Outcome("", false, null, "", List.of(), null);
        }
        if (directory == null || !directory.available()) {
            return new Outcome(npcKey, false, null, "", List.of(),
                    "Aucun relevé PNJ disponible : impossible de vérifier si « " + npcKey
                            + " » porte déjà un dialogue. Rafraîchissez le catalogue PNJ avant "
                            + "d'enregistrer la proposition, sinon vous risquez de créer un "
                            + "deuxième dialogue concurrent sans le voir.");
        }
        Optional<NpcView> maybe = directory.find(npcKey);
        if (maybe.isEmpty()) {
            return new Outcome(npcKey, false, null, "", List.of(),
                    "« " + npcKey + " » ne figure pas dans le dernier relevé PNJ du serveur. Le "
                            + "dialogue sera nommé comme lui — mais sans définition PNJ portant ce "
                            + "nom, personne ne pourra lui parler.");
        }
        NpcView npc = maybe.get();
        List<NpcView> related = directory.relatedEntries(npc);
        String existing = npc.linkedDialogueKey();

        if (existing.isEmpty()) {
            return new Outcome(npc.id().toLowerCase(Locale.ROOT), false, npc, "", related,
                    "« " + npc.id() + " » n'a aucun dialogue. Le nouveau dialogue portera son "
                            + "identifiant, ce qui suffit au moteur pour les rattacher.");
        }

        if (existing.equals(npc.id().toLowerCase(Locale.ROOT))) {
            // Le dialogue porte déjà le nom du PNJ : il n'y a qu'une lecture possible, remplacer
            // celui-là. L'arbitrage des collisions de l'import demandera la confirmation.
            return new Outcome(existing, false, npc, existing, related,
                    "« " + npc.id() + " » porte déjà le dialogue « " + npc.linkedDialogueId()
                            + " » (" + npc.dialogueNodes() + " nœud(s), " + npc.dialogueChoices()
                            + " choix). La proposition le remplacera : la page d'import affichera "
                            + "la collision et demandera une confirmation explicite.");
        }

        // Le dialogue lié ne porte PAS le nom du PNJ : c'est le cas Mira, et il n'a pas de réponse
        // par défaut.
        Optional<Choice> choice = Choice.of(rawChoice);
        if (choice.isEmpty()) {
            return new Outcome("", true, npc, existing, related, null);
        }
        return switch (choice.get()) {
            case EDIT_EXISTING -> new Outcome(existing, false, npc, existing, related,
                    "La proposition reprendra le dialogue existant « " + npc.linkedDialogueId()
                            + " ». Le lien du PNJ ne change pas, et aucun second dialogue n'est "
                            + "créé. La page d'import affichera la collision avec le fichier actuel "
                            + "et demandera une confirmation.");
            case NEW_REPLACING_LINK -> new Outcome(npc.id().toLowerCase(Locale.ROOT), false, npc,
                    existing, related,
                    "ATTENTION — la proposition créera un NOUVEAU dialogue « rpgquest:" + npc.id()
                            + " », distinct de « " + npc.linkedDialogueId() + " ». Tant que la "
                            + "définition de « " + npc.id() + " » n'est pas repointée sur le "
                            + "nouveau dialogue depuis la fiche PNJ, c'est l'ancien que les joueurs "
                            + "continueront d'entendre, et l'ancien n'est pas supprimé.");
        };
    }
}
