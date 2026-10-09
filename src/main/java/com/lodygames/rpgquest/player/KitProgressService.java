package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.progress.DeliveryLine;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;

/**
 * Rassemble les faits nécessaires pour dire à un joueur où il en est dans la progression de son kit
 * de départ (issue #235) : palier actuel, palier suivant, et ce qu'il reste à rapporter.
 *
 * <h2>Rien n'est dupliqué, tout est dérivé</h2>
 *
 * <p>Le ticket l'exige et c'est aussi la seule façon que cela reste vrai dans six mois :</p>
 * <ul>
 *   <li>le <strong>palier</strong> vient de {@code STARTER_KIT_TIER} via
 *       {@link StarterToolKitService} — la source de vérité de #218, jamais une seconde logique ;</li>
 *   <li>les <strong>noms de paliers</strong> viennent de {@code config.yml} ;</li>
 *   <li>le <strong>lien palier → quête</strong> vient de {@code unlock-quest:} dans
 *       {@code config.yml} ;</li>
 *   <li>les <strong>matériaux et quantités</strong> viennent des objectifs
 *       {@code DELIVER_ITEM_TO_NPC} de cette quête — aucune quantité n'est écrite ici, donc
 *       modifier la quête modifie le discours du Guide, sans toucher au code ;</li>
 *   <li>ce qui est <strong>déjà remis</strong> vient de la progression réelle (#123).</li>
 * </ul>
 *
 * <p>Toutes les lectures sont <strong>synchrones et en mémoire</strong> : ce service est appelé au
 * moment de rendre un nœud de dialogue, sur le thread principal, où aucune requête SQL n'a le droit
 * d'arriver.</p>
 *
 * <p>Les dépendances sont passées en fonctions plutôt qu'en services concrets : le moteur de quêtes
 * n'est pas instanciable hors d'un serveur, et cette classe mérite d'être testée pour de vrai.</p>
 */
public final class KitProgressService {

    private final Supplier<StarterToolKitConfig> configSupplier;
    private final Function<UUID, Integer> unlockedTier;
    private final Function<NamespacedKey, Optional<QuestDefinition>> questLookup;
    private final BiFunction<UUID, NamespacedKey, Optional<QuestStepProgressView>> activeStep;

    public KitProgressService(Supplier<StarterToolKitConfig> configSupplier,
                               Function<UUID, Integer> unlockedTier,
                               Function<NamespacedKey, Optional<QuestDefinition>> questLookup,
                               BiFunction<UUID, NamespacedKey, Optional<QuestStepProgressView>> activeStep) {
        this.configSupplier = configSupplier;
        this.unlockedTier = unlockedTier;
        this.questLookup = questLookup;
        this.activeStep = activeStep;
    }

    /** {@code %kit_tier_current%} — « Palier 1 — Nouveau venu ». */
    public String currentTierText(UUID playerId) {
        return KitProgressText.current(configSupplier.get(), unlockedTier.apply(playerId));
    }

    /** {@code %kit_tier_next%} — « Palier 2 — … », ou la phrase du palier maximum. */
    public String nextTierText(UUID playerId) {
        return KitProgressText.next(configSupplier.get(), unlockedTier.apply(playerId));
    }

    /** {@code %kit_upgrade_requirements%} — la liste à rapporter, avec ce qui est déjà remis. */
    public String requirementsText(UUID playerId) {
        return KitProgressText.requirements(requirements(playerId));
    }

    /**
     * La quête qui débloque le palier suivant, si elle existe et est connue du moteur.
     *
     * <p>Un {@code unlock-quest:} qui ne correspond à aucune quête chargée renvoie vide : le Guide
     * n'annoncera alors pas une quête fantôme.</p>
     */
    public Optional<QuestDefinition> nextUnlockQuest(UUID playerId) {
        StarterToolKitConfig config = configSupplier.get();
        Optional<StarterKitTier> next = KitProgressText.nextTier(config, unlockedTier.apply(playerId));
        if (next.isEmpty() || next.get().unlockQuest() == null) {
            return Optional.empty();
        }
        NamespacedKey key = NamespacedKey.fromString(next.get().unlockQuest());
        return key == null ? Optional.empty() : questLookup.apply(key);
    }

    /**
     * Ce qu'il reste à rapporter pour le palier suivant, avec les quantités déjà remises.
     *
     * <p>La progression n'est superposée que si l'étape active est <strong>celle-là même</strong>
     * dont on lit les objectifs : les compteurs sont indexés par position dans l'étape, donc
     * mélanger deux étapes afficherait des chiffres faux. Hors de ce cas, on affiche la demande
     * complète avec 0 remis, ce qui est exactement vrai pour une quête non commencée.</p>
     */
    public List<DeliveryLine> requirements(UUID playerId) {
        Optional<QuestDefinition> questOpt = nextUnlockQuest(playerId);
        if (questOpt.isEmpty()) {
            return List.of();
        }
        QuestDefinition quest = questOpt.get();
        QuestStep step = quest.steps().get(0);
        Optional<QuestStepProgressView> view = activeStep.apply(playerId, quest.id())
                .filter(v -> v.stepId().equals(step.id()));

        List<DeliveryLine> lines = new ArrayList<>();
        for (int i = 0; i < step.objectives().size(); i++) {
            QuestObjective objective = step.objectives().get(i);
            if (!(objective instanceof DeliverItemToNpcObjective delivery)) {
                continue;
            }
            int index = i;
            int delivered = view
                    .filter(v -> index < v.objectives().size())
                    .map(v -> v.objectives().get(index).current())
                    .orElse(0);
            lines.add(new DeliveryLine(delivery.material(), Math.min(delivered, delivery.amount()),
                    delivery.amount()));
        }
        return List.copyOf(lines);
    }
}
