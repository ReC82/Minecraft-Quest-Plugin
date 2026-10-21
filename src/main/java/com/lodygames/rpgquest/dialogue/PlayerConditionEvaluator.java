package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.HasInventorySpaceCondition;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.NoMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.PendingDeliveryCondition;
import com.lodygames.rpgquest.dialogue.model.QuestAvailableCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Évaluateur <strong>unique</strong> des conditions joueur ({@link DialogueCondition}) : extrait de
 * {@code DialogueSessionEngine} (issue #567) pour être partagé par les dialogues et les conditions
 * d'accès des portails, sans seconde implémentation.
 *
 * <p>À appeler sur le thread principal : l'inventaire, les permissions et les claims sont lus
 * directement sur le joueur ; l'état de quête et les variables sont lus en asynchrone (le future
 * peut donc se terminer hors du thread principal — à l'appelant de revenir dessus pour agir).</p>
 */
public final class PlayerConditionEvaluator {

    private final QuestProgressEngine questProgressEngine;
    private final PlayerVariableRepository variableRepository;
    private final ClaimService claimService;
    private final YamlCustomItemRegistry customItemRegistry;

    public PlayerConditionEvaluator(QuestProgressEngine questProgressEngine, PlayerVariableRepository variableRepository,
                                    ClaimService claimService, YamlCustomItemRegistry customItemRegistry) {
        this.questProgressEngine = questProgressEngine;
        this.variableRepository = variableRepository;
        this.claimService = claimService;
        this.customItemRegistry = customItemRegistry;
    }

    /**
     * @param dialogueId dialogue en cours, pour résoudre le PNJ d'une {@link PendingDeliveryCondition}
     *                   sans {@code npc} ({@code null} hors dialogue)
     */
    public CompletableFuture<Boolean> evaluate(Player player, @Nullable NamespacedKey dialogueId,
                                               DialogueCondition condition) {
        return switch (condition) {
            case QuestStateCondition c -> questProgressEngine.stateOf(player.getUniqueId(), c.questId())
                    .thenApply(state -> state == c.state());
            // Issue #13 : la règle d'acceptation elle-même (QuestProgressEngine.availability), jamais
            // une copie — une offre affichée est donc une offre réellement acceptable.
            case QuestAvailableCondition c -> questProgressEngine.availability(player.getUniqueId(), c.questId(), false)
                    .thenApply(QuestProgressEngine.Availability::available);
            case HasItemCondition c -> CompletableFuture.completedFuture(
                    player.getInventory().containsAtLeast(new ItemStack(c.material()), c.amount()));
            case HasPermissionCondition c -> CompletableFuture.completedFuture(player.hasPermission(c.permission()));
            case VariableEqualsCondition c -> variableRepository.get(player.getUniqueId(), c.key())
                    .thenApply(opt -> opt.map(v -> v.equals(c.value())).orElse(false));
            case NoMainClaimCondition ignored -> CompletableFuture.completedFuture(
                    claimService.claimsOwnedBy(player.getUniqueId()).isEmpty());
            case HasMainClaimCondition ignored -> CompletableFuture.completedFuture(
                    claimService.mainClaimOf(player.getUniqueId()).isPresent());
            case LacksCustomItemCondition c -> CompletableFuture.completedFuture(!hasCustomItem(player, c.itemId()));
            // Issue #85 : lecture de l'inventaire principal, sur le thread du clic — aucune requête.
            case HasInventorySpaceCondition c -> CompletableFuture.completedFuture(
                    freeStorageSlots(player) >= c.slots());
            // Issue #123 : lecture pure de l'état de remise, déjà en mémoire — aucune requête, donc
            // un future déjà complété, évalué à chaque ouverture de nœud sans coût.
            case PendingDeliveryCondition c -> CompletableFuture.completedFuture(
                    questProgressEngine.hasPendingDelivery(
                            player.getUniqueId(), DialogueNpcResolution.resolve(c.npcId(), dialogueId)));
            case NegatedCondition c -> evaluate(player, dialogueId, c.inner()).thenApply(result -> !result);
        };
    }

    /** {@code true} si toutes passent (liste vide : {@code true}) ; toutes sont évaluées en parallèle. */
    public CompletableFuture<Boolean> evaluateAll(Player player, @Nullable NamespacedKey dialogueId,
                                                  List<DialogueCondition> conditions) {
        if (conditions.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        List<CompletableFuture<Boolean>> checks = conditions.stream()
                .map(condition -> evaluate(player, dialogueId, condition))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(v -> checks.stream().allMatch(CompletableFuture::join));
    }

    /** Emplacements vides de l'inventaire principal (ni armure, ni seconde main). */
    public static int freeStorageSlots(Player player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    private boolean hasCustomItem(Player player, NamespacedKey itemId) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && customItemRegistry.identify(stack).map(itemId::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
