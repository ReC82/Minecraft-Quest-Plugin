package com.lodygames.rpgquest.dialogue.model;

/**
 * Issue #369 — présente au joueur la quête que propose le catalogue de ce PNJ : une offre
 * (titre, but, délai) avec <em>Commencer (j'accepte)</em>, <em>Plus tard</em> et <em>Refuser</em>,
 * la tâche en cours, ou « rien pour le moment ». Rien n'est attribué avant l'acceptation.
 *
 * @param catalogId catalogue visé, ou {@code null} : les catalogues ({@code quest_catalogs}) des
 *                  modèles de PNJ récurrents dont le {@code npc_id} porte ce dialogue
 */
public record OfferCatalogQuestAction(String catalogId) implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.OFFER_CATALOG_QUEST;
    }
}
