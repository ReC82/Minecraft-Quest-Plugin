package com.lodygames.rpgquest.dialogue.model;

/**
 * {@code command} est un modèle de commande console (ex. {@code "give %player% diamond 1"}),
 * dont le nom de commande (premier mot) a déjà été vérifié contre la liste
 * blanche de {@code config.yml} au moment du chargement (voir
 * {@code DialogueDefinitionParser}) — ce record ne porte que la donnée déjà
 * validée. Seule substitution effectuée à l'exécution : {@code %player%}
 * (nom du joueur, jamais de texte libre concaténé).
 */
public record RunSafeCommandAction(String command) implements DialogueAction {

    public RunSafeCommandAction {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command ne peut pas être vide.");
        }
    }

    @Override
    public ActionType type() {
        return ActionType.RUN_SAFE_COMMAND;
    }
}
