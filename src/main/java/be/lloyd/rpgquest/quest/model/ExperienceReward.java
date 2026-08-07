package be.lloyd.rpgquest.quest.model;

public record ExperienceReward(int amount) implements QuestReward {

    public ExperienceReward {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public RewardType type() {
        return RewardType.EXPERIENCE;
    }
}
