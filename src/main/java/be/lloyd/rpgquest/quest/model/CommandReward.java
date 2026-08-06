package be.lloyd.rpgquest.quest.model;

/**
 * Commande exécutée en tant que console à l'octroi de la récompense
 * (convention {@code %player%} pour le pseudo du joueur). Non exécutée par
 * cette étape : seule la définition est validée et stockée.
 */
public record CommandReward(String command) implements QuestReward {

    public CommandReward {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command ne peut pas être vide.");
        }
    }

    @Override
    public RewardType type() {
        return RewardType.COMMAND;
    }
}
