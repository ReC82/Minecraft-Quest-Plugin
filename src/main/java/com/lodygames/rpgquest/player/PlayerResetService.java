package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.database.ItemTravelCooldownRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.travel.ItemTravelService;
import com.lodygames.rpgquest.travel.PortalService;
import com.lodygames.rpgquest.ui.QuestJournalService;
import com.lodygames.rpgquest.waystone.WaystoneService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Reset admin ciblé « nouveau joueur » ({@code /rpgadmin player resetnew &lt;joueur&gt;}) : remet
 * l'état <strong>RPGQuest</strong> d'<em>un seul</em> joueur dans l'équivalent fonctionnel d'un
 * joueur qui n'a jamais joué, pour pouvoir refaire tout le parcours d'onboarding
 * (Story → CLAIM_TIER_1 → Jo → Acte de propriété → claim → Wild → Waystones / Rune de rappel).
 *
 * <p><strong>Réutilise les resets déjà présents</strong> plutôt que de les réimplémenter :
 * {@link QuestProgressEngine#resetAllQuests}, {@link StoryService#reset} (mode {@code "all"}),
 * {@link WaystoneService#resetDiscoveries}, {@link ClaimService#resetTierOneClaimForTesting}
 * (supprime les claims du joueur + remet {@code CLAIM_TIER_1}, cascade {@code claim_members}).
 * S'y ajoutent uniquement les suppressions par joueur qui manquaient : variables, progression RPG
 * ({@code player_skills}/{@code xp_grants}), cooldowns persistants (portails + voyage par objet).</p>
 *
 * <p><strong>Jamais</strong> : {@code data.db} entier, un autre joueur, le profil/UUID, les mondes,
 * les PNJ Citizens, les définitions de quêtes/Stories, les portails, les Waystones globales déjà
 * générées, les blocs construits (le claim disparaît en tant que donnée de protection, la zone
 * physique reste). L'économie, les backpacks/entitlements et les annonces de marché sont
 * <strong>conservés volontairement</strong> (hors parcours d'onboarding, réinitialisables via leurs
 * propres commandes admin si besoin).</p>
 *
 * <p><strong>Inventaire</strong> : les objets personnalisés RPGQuest (identifiés par PDC via
 * {@link YamlCustomItemRegistry}, jamais par matériau) sont retirés si le joueur est en ligne ;
 * s'il est hors ligne, un marqueur {@link #PENDING_INVENTORY_KEY} est posé et
 * {@link NewPlayerResetJoinListener} fait le nettoyage à sa prochaine connexion, avant que le kit
 * de départ ne soit redistribué. L'inventaire vanilla n'est jamais vidé.</p>
 */
public final class PlayerResetService {

    /** Variable posée pour un joueur hors ligne : nettoyage d'inventaire différé au prochain login. */
    public static final String PENDING_INVENTORY_KEY = "__pending_new_player_reset__";

    private final RPGQuestPlugin plugin;
    private final QuestProgressEngine questProgressEngine;
    private final StoryService storyService;
    private final WaystoneService waystoneService;
    private final ClaimService claimService;
    private final ProgressionService progressionService;
    private final QuestJournalService questJournalService;
    private final PortalService portalService;
    private final ItemTravelService itemTravelService;
    private final PlayerVariableRepository variableRepository;
    private final ProgressionRepository progressionRepository;
    private final PortalCooldownRepository portalCooldownRepository;
    private final ItemTravelCooldownRepository itemTravelCooldownRepository;
    private final YamlCustomItemRegistry customItemRegistry;

    public PlayerResetService(RPGQuestPlugin plugin, QuestProgressEngine questProgressEngine, StoryService storyService,
                               WaystoneService waystoneService, ClaimService claimService,
                               ProgressionService progressionService, QuestJournalService questJournalService,
                               PortalService portalService, ItemTravelService itemTravelService,
                               PlayerVariableRepository variableRepository, ProgressionRepository progressionRepository,
                               PortalCooldownRepository portalCooldownRepository,
                               ItemTravelCooldownRepository itemTravelCooldownRepository,
                               YamlCustomItemRegistry customItemRegistry) {
        this.plugin = plugin;
        this.questProgressEngine = questProgressEngine;
        this.storyService = storyService;
        this.waystoneService = waystoneService;
        this.claimService = claimService;
        this.progressionService = progressionService;
        this.questJournalService = questJournalService;
        this.portalService = portalService;
        this.itemTravelService = itemTravelService;
        this.variableRepository = variableRepository;
        this.progressionRepository = progressionRepository;
        this.portalCooldownRepository = portalCooldownRepository;
        this.itemTravelCooldownRepository = itemTravelCooldownRepository;
        this.customItemRegistry = customItemRegistry;
    }

    /** Résumé concis de ce qui a été fait, pour l'affichage admin. */
    public record ResetSummary(boolean online, int inventoryItemsRemoved, boolean inventoryDeferred) {
    }

    /**
     * Exécute le reset complet. Toutes les suppressions en base sont faites quel que soit l'état de
     * connexion ; seuls le nettoyage d'inventaire et l'invalidation des caches mémoire dépendent de
     * la présence du joueur (voir Javadoc de classe).
     */
    public CompletableFuture<ResetSummary> resetToNewPlayer(UUID uuid, String name) {
        return CompletableFuture.allOf(
                        questProgressEngine.resetAllQuests(uuid),
                        storyService.reset(uuid, "all"),
                        waystoneService.resetDiscoveries(uuid),
                        progressionRepository.resetPlayer(uuid),
                        portalCooldownRepository.deleteAllForPlayer(uuid),
                        itemTravelCooldownRepository.deleteAllForPlayer(uuid))
                // Claims + CLAIM_TIER_1 d'abord (réutilise le reset existant, qui réécrit
                // CLAIM_TIER_1="false"), puis wipe TOUTES les variables (efface aussi ce "false",
                // la quête suivie et le marqueur de kit de départ) — état final : aucune ligne.
                .thenCompose(v -> claimService.resetTierOneClaimForTesting(uuid))
                .thenCompose(v -> variableRepository.deleteAllForPlayer(uuid))
                .thenCompose(deleted -> finishOnMainThread(uuid));
    }

    private CompletableFuture<ResetSummary> finishOnMainThread(UUID uuid) {
        CompletableFuture<ResetSummary> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) {
                int removed = removeRpgItems(online, customItemRegistry);
                progressionService.loadForPlayer(uuid);       // recharge → cache vide
                portalService.reloadCooldownsForPlayer(uuid);  // recharge → cache vide
                itemTravelService.reloadCooldownsForPlayer(uuid);
                questJournalService.clearTrackingFor(uuid);
                result.complete(new ResetSummary(true, removed, false));
            } else {
                progressionService.unloadForPlayer(uuid); // sans effet si non chargé, sûr
                variableRepository.set(uuid, PENDING_INVENTORY_KEY, "1").exceptionally(error -> {
                    plugin.getSLF4JLogger().error("Impossible de poser le marqueur de nettoyage d'inventaire différé pour {}", uuid, error);
                    return null;
                });
                result.complete(new ResetSummary(false, -1, true));
            }
        });
        return result;
    }

    /**
     * Retire de l'inventaire (36 cases + armure + main secondaire) tous les objets personnalisés
     * RPGQuest — identifiés par {@link YamlCustomItemRegistry#isCustomItem} (PDC), jamais par
     * matériau. Ne touche à rien d'autre. Retourne le nombre d'exemplaires retirés.
     */
    public static int removeRpgItems(Player player, YamlCustomItemRegistry customItemRegistry) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int removed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && customItemRegistry.isCustomItem(stack)) {
                removed += stack.getAmount();
                inventory.setItem(slot, null);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir() && customItemRegistry.isCustomItem(cursor)) {
            removed += cursor.getAmount();
            player.setItemOnCursor(null);
        }
        return removed;
    }
}
