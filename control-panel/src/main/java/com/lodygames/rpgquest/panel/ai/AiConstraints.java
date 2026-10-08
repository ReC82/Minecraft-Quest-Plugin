package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.content.ContentId;
import com.lodygames.rpgquest.panel.content.DialogueDraft;
import com.lodygames.rpgquest.panel.content.DialogueYaml;
import com.lodygames.rpgquest.panel.content.MiniYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Les exigences du formulaire que le <strong>backend vérifie lui-même</strong> sur la proposition de
 * l'IA (issues #223 et #224).
 *
 * <p><strong>Pourquoi ne pas faire confiance au prompt.</strong> Le formulaire demandait « 5 nœuds »,
 * le prompt le disait, et le modèle en a produit 4 — une proposition par ailleurs valide, donc
 * acceptée. Une valeur saisie explicitement n'est pas une suggestion : si le panel ne la vérifie
 * pas, elle n'est pas respectée. Même raisonnement pour l'identifiant imposé, qu'un modèle a déjà
 * remplacé par un identifiant de son invention.</p>
 *
 * <p>Ces vérifications sont <strong>indépendantes</strong> des validateurs de contenu : un document
 * peut être parfaitement valide pour le moteur et ne pas être ce qui avait été demandé. Les deux
 * refus se cumulent, et les deux sont corrigeables par « Demander une correction » (#222) —
 * c'est d'ailleurs le cas d'usage pour lequel ce bouton existe.</p>
 *
 * <p><strong>On compte le contenu réel, jamais une annonce du modèle.</strong> Les nœuds sont
 * relus par {@link DialogueYaml#fromMap}, c'est-à-dire par le lecteur qui servira à l'import : la
 * mesure porte donc sur la vraie map {@code nodes}, pas sur des identifiants cités dans un
 * {@code next} ni sur un commentaire.</p>
 *
 * @param questId    identifiant canonique imposé pour une quête, ou vide
 * @param dialogueId clé imposée pour un dialogue (= id du PNJ porteur), ou vide
 * @param storyId    clé imposée pour une story, ou vide
 * @param nodeCount  nombre de nœuds impératif, {@code 0} = l'IA décide
 */
public record AiConstraints(String questId, String dialogueId, String storyId, int nodeCount) {

    public AiConstraints {
        questId = questId == null ? "" : questId;
        dialogueId = dialogueId == null ? "" : dialogueId;
        storyId = storyId == null ? "" : storyId;
        nodeCount = Math.max(0, nodeCount);
    }

    public static AiConstraints none() {
        return new AiConstraints("", "", "", 0);
    }

    public static AiConstraints forQuest(String canonicalQuestId) {
        return new AiConstraints(canonicalQuestId, "", "", 0);
    }

    public static AiConstraints forDialogue(String dialogueKey, int nodeCount) {
        return new AiConstraints("", dialogueKey, "", nodeCount);
    }

    public static AiConstraints forStory(String storyKey) {
        return new AiConstraints("", "", storyKey, 0);
    }

    /** Y a-t-il quelque chose à vérifier ? Sinon on ne parcourt même pas le document. */
    public boolean any() {
        return !questId.isEmpty() || !dialogueId.isEmpty() || !storyId.isEmpty() || nodeCount > 0;
    }

    /**
     * Les exigences non tenues, formulées pour l'écran <strong>et</strong> pour une relance de l'IA :
     * chaque message dit l'attendu et l'obtenu, donc le modèle a de quoi corriger sans deviner.
     *
     * <p>Liste vide = la proposition respecte la demande. Un document illisible ne produit rien
     * ici : son refus appartient à l'analyse d'import, et le dire deux fois n'aiderait personne.</p>
     */
    public List<String> verify(ContentPromptBuilder.Kind kind, String packYaml) {
        List<String> out = new ArrayList<>();
        if (!any() || packYaml == null || packYaml.isBlank()) {
            return out;
        }
        Object root;
        try {
            root = MiniYaml.parse(packYaml);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof Map<?, ?> pack) || !(pack.get("content") instanceof Map<?, ?> content)) {
            return out;
        }

        switch (kind) {
            case QUEST -> {
                Map<?, ?> quest = only(content.get("quests"));
                if (quest == null) {
                    return out;
                }
                checkId(out, "quête", questId, ContentId.plainKey(str(quest.get("id"))));
            }
            case DIALOGUE -> {
                Map<?, ?> dialogue = only(content.get("dialogues"));
                if (dialogue == null) {
                    return out;
                }
                checkId(out, "dialogue", dialogueId, ContentId.plainKey(str(dialogue.get("id"))));
                checkNodeCount(out, dialogue);
            }
            case STORY -> {
                Map<?, ?> story = only(content.get("stories"));
                if (story == null) {
                    return out;
                }
                checkId(out, "story", storyId, ContentId.plainKey(str(story.get("id"))));
            }
        }
        return out;
    }

    // ---- Vérifications ------------------------------------------------------------------------

    /**
     * L'identifiant imposé a-t-il été repris ? La comparaison porte sur la forme normalisée, donc
     * {@code ma_quete} et {@code rpgquest:ma_quete} sont la même réponse — ce qui est refusé, c'est
     * un <em>autre</em> identifiant, pas une autre écriture du même.
     */
    private void checkId(List<String> out, String what, String imposed, ContentId.Normalized got) {
        if (imposed.isEmpty()) {
            return;
        }
        String expectedKey = ContentId.plainKey(imposed).key();
        if (!got.ok()) {
            out.add("Identifiant de " + what + " imposé non respecté : « " + expectedKey
                    + " » était demandé, et l'identifiant produit n'est pas utilisable — "
                    + got.error() + " Reprends exactement l'identifiant demandé.");
            return;
        }
        if (!expectedKey.equals(got.key())) {
            out.add("Identifiant de " + what + " imposé non respecté : « " + expectedKey
                    + " » était demandé, « " + got.key() + " » a été produit. Reprends exactement "
                    + "l'identifiant demandé, sans le renommer et sans changer de namespace.");
        }
    }

    /**
     * Le nombre de nœuds demandé a-t-il été produit ? On relit le document avec le lecteur réel,
     * donc on compte les entrées de la map {@code nodes} et rien d'autre.
     */
    private void checkNodeCount(List<String> out, Map<?, ?> dialogue) {
        if (nodeCount <= 0) {
            return;
        }
        DialogueYaml.ReadResult read = DialogueYaml.fromMap(dialogue);
        DialogueDraft draft = read.draft();
        if (draft == null) {
            // Document inexploitable : l'analyse d'import le dira, avec de meilleurs mots.
            return;
        }
        int actual = draft.nodes.size();
        if (actual != nodeCount) {
            out.add(nodeCount + " nœuds demandés, " + actual + " généré"
                    + (actual > 1 ? "s" : "") + ". Ce nombre est impératif : produis exactement "
                    + nodeCount + " nœud" + (nodeCount > 1 ? "s" : "") + " dans la map « nodes », "
                    + "sans en ajouter ni en retirer, et garde un dialogue cohérent (tout « next » "
                    + "doit viser un nœud existant).");
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /**
     * Le premier élément d'une famille. L'atelier n'en demande qu'un seul par appel, et l'analyse
     * d'import rapporte de son côté <em>chaque</em> élément reçu : si le modèle en renvoyait
     * plusieurs, cela se verrait là, à sa place.
     */
    private static Map<?, ?> only(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.get(0) instanceof Map<?, ?> m ? m : null;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
    }
}
