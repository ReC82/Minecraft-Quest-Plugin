package com.lodygames.rpgquest.dialogue.session;

import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferService;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.progress.CooldownText;
import java.util.List;

/**
 * Nœuds <strong>synthétiques</strong> de l'action {@code OFFER_CATALOG_QUEST} (issue #369) : ils
 * n'existent dans aucun fichier, leur identifiant commence par {@code __catalog_} (jamais un id de
 * nœud YAML valide pour un auteur) et leurs choix ne portent aucune action — c'est
 * {@link DialogueSessionEngine} qui interprète le bouton cliqué via {@link #choiceOf}. Aucune quête
 * n'est attribuée ailleurs que sur « Commencer (j'accepte) ».
 */
final class CatalogOfferNodes {

    static final String OFFER = "__catalog_offer";
    static final String ACTIVE = "__catalog_active";
    static final String NOTHING = "__catalog_nothing";

    static final String STALE_OFFER =
            "<gray>Cette proposition n'est plus disponible. Reparle au PNJ pour voir ce qu'il a pour toi.</gray>";
    static final String REFUSED = "<gray>Comme tu voudras. La proposition tient toujours.</gray>";

    enum Choice { ACCEPT, LATER, REFUSE, BACK, CLOSE, NONE }

    private static final List<String> OFFER_LABELS = List.of("Commencer (j'accepte)", "Plus tard", "Refuser");
    private static final List<String> INFO_LABELS = List.of("Retour", "Au revoir");

    private CatalogOfferNodes() {
    }

    static boolean isOfferNode(String nodeId) {
        return OFFER.equals(nodeId) || ACTIVE.equals(nodeId) || NOTHING.equals(nodeId);
    }

    static Choice choiceOf(String nodeId, int index) {
        if (OFFER.equals(nodeId)) {
            return switch (index) {
                case 0 -> Choice.ACCEPT;
                case 1 -> Choice.LATER;
                case 2 -> Choice.REFUSE;
                default -> Choice.NONE;
            };
        }
        if (isOfferNode(nodeId)) {
            return switch (index) {
                case 0 -> Choice.BACK;
                case 1 -> Choice.CLOSE;
                default -> Choice.NONE;
            };
        }
        return Choice.NONE;
    }

    static DialogueNode forOffer(String speaker, CatalogOfferService.Offer offer) {
        CatalogOfferSelector.Decision decision = offer.decision();
        QuestDefinition quest = offer.quest();
        if (!offer.configured()) {
            return node(NOTHING, speaker, "<gray>Je n'ai aucun travail à confier pour l'instant.</gray>", INFO_LABELS);
        }
        if (decision.kind() == CatalogOfferSelector.Kind.OFFER && quest != null) {
            StringBuilder text = new StringBuilder("<white>J'aurais une tâche pour toi : ")
                    .append(quest.title().base()).append("<white>.</white>");
            String description = quest.description() == null ? "" : quest.description().base();
            if (!description.isBlank()) {
                text.append("<newline>").append(description);
            }
            if (quest.repeatable() && !quest.cooldown().isZero()) {
                text.append("<newline><gray>Renouvelable : toutes les ")
                        .append(CooldownText.format(quest.cooldown())).append(".</gray>");
            }
            text.append("<newline><gray>Rien ne sera noté dans ton journal tant que tu n'auras pas accepté.</gray>");
            return node(OFFER, speaker, text.toString(), OFFER_LABELS);
        }
        if (decision.kind() == CatalogOfferSelector.Kind.ACTIVE) {
            String title = quest == null ? "ta tâche actuelle" : quest.title().base();
            return node(ACTIVE, speaker, "<white>Termine d'abord " + title
                    + "<white>.</white> <gray>Je n'ai rien d'autre à te confier en attendant.</gray>", INFO_LABELS);
        }
        String text = decision.retryAfter() == null
                ? "<gray>Rien pour le moment. Je n'ai plus de travail pour toi.</gray>"
                : "<gray>Rien pour le moment. Repasse d'ici " + CooldownText.format(decision.retryAfter()) + ".</gray>";
        return node(NOTHING, speaker, text, INFO_LABELS);
    }

    static DialogueNode unavailable(String speaker) {
        return node(NOTHING, speaker, "<gray>Rien pour le moment.</gray>", INFO_LABELS);
    }

    private static DialogueNode node(String id, String speaker, String text, List<String> labels) {
        List<DialogueChoice> choices = labels.stream()
                .map(label -> new DialogueChoice(LocalizedText.of(label), List.of(), List.of(), null))
                .toList();
        return new DialogueNode(id, speaker, LocalizedText.of(text), choices);
    }
}
