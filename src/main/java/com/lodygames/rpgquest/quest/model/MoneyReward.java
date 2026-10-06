package com.lodygames.rpgquest.quest.model;

/**
 * Récompense monétaire d'une quête (issue #16) : crédite le <strong>portefeuille persistant</strong>
 * du joueur à la remise de la quête.
 *
 * <p>Le montant est un {@code int} et non un {@code long}, délibérément : c'est un nombre
 * <strong>conçu</strong> par un auteur de contenu (comme {@link ExperienceReward#amount()} ou
 * {@link ItemReward#amount()}), pas un solde <em>accumulé</em> — le solde, lui, est un {@code long}
 * dans {@code WalletRepository} et le reste. Cette séparation garde le format public des content
 * packs ({@code QuestPackEntry.Reward.amount}) inchangé.</p>
 *
 * <p>Aucune monnaie physique ici : cette récompense ne donne <strong>aucun objet</strong>. Le lien
 * entre un solde et un éventuel objet-monnaie est une décision de gameplay non prise à ce jour, et
 * volontairement hors de ce lot.</p>
 */
public record MoneyReward(int amount) implements QuestReward {

    public MoneyReward {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public RewardType type() {
        return RewardType.MONEY;
    }
}
