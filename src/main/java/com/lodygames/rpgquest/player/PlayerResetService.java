package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.database.ItemTravelCooldownRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository.StoryProgressRecord;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.model.StoryState;
import com.lodygames.rpgquest.travel.ItemTravelService;
import com.lodygames.rpgquest.travel.PortalService;
import com.lodygames.rpgquest.ui.QuestJournalService;
import com.lodygames.rpgquest.waystone.WaystoneService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.NamespacedKey;
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
 *
 * <p><strong>Preview / dry-run</strong> : {@link #previewReset(UUID)} lit les mêmes catégories
 * ({@link ResetPreview}) sans effectuer <em>aucune</em> écriture — pour vérifier ce qui serait
 * effacé avant de confirmer un reset réel ({@code /rpgadmin player resetnew &lt;joueur&gt; preview}).</p>
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
     * Aperçu (dry-run) de ce qu'un reset réel effacerait pour un joueur, <strong>sans aucune
     * écriture</strong> : une entrée {@link ResetCategory} par grande catégorie couverte par
     * {@link #resetToNewPlayer}. Rendu par la couche commande ({@code /rpgadmin player resetnew
     * &lt;joueur&gt; preview}).
     */
    public record ResetPreview(boolean online, List<ResetCategory> categories) {
    }

    /**
     * Une catégorie de données inspectée par le preview. {@code count == -1} signale une catégorie
     * <em>non inspectable</em> dans le contexte courant (ex. inventaire d'un joueur hors ligne) ;
     * {@code count == 0} une catégorie inspectée mais déjà vide.
     */
    public record ResetCategory(String label, int count, String detail) {

        /** Sentinelle {@link #count()} d'une catégorie non inspectable dans le contexte courant. */
        public static final int NOT_INSPECTABLE = -1;

        public static ResetCategory notInspectable(String label, String detail) {
            return new ResetCategory(label, NOT_INSPECTABLE, detail);
        }

        /** {@code true} si la catégorie a pu être inspectée ({@link #count()} &ge; 0). */
        public boolean inspectable() {
            return count >= 0;
        }

        /** {@code true} si la catégorie est inspectable et ne contient rien à réinitialiser. */
        public boolean empty() {
            return count == 0;
        }
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

    /**
     * Construit un {@link ResetPreview} : lit les mêmes catégories que {@link #resetToNewPlayer} mais
     * n'écrit <strong>rien</strong> (aucune suppression, aucun marqueur, aucune invalidation de
     * cache). Fonctionne pour un joueur en ligne ou hors ligne ; l'inventaire n'est comptabilisé que
     * si le joueur est en ligne (sinon catégorie signalée « non inspectable »).
     */
    public CompletableFuture<ResetPreview> previewReset(UUID uuid) {
        CompletableFuture<Map<NamespacedKey, QuestState>> questStates = questProgressEngine.allStates(uuid);
        CompletableFuture<Map<String, StoryProgressRecord>> stories = storyService.progressRecords(uuid);
        CompletableFuture<Map<String, String>> variables = variableRepository.findAllForPlayer(uuid);
        CompletableFuture<Map<SkillType, Long>> progression = progressionRepository.findAll(uuid);
        CompletableFuture<Map<String, Instant>> portalCooldowns = portalCooldownRepository.allForPlayer(uuid);
        CompletableFuture<Map<String, Instant>> itemTravelCooldowns = itemTravelCooldownRepository.allForPlayer(uuid);
        CompletableFuture<Integer> waystoneDiscoveries = waystoneService.discoveryCount(uuid);
        CompletableFuture<Boolean> claimTierOne = claimService.hasClaimTierOne(uuid);

        return CompletableFuture.allOf(questStates, stories, variables, progression, portalCooldowns,
                        itemTravelCooldowns, waystoneDiscoveries, claimTierOne)
                .thenCompose(ignored -> assemblePreviewOnMainThread(uuid, questStates.join(), stories.join(),
                        variables.join(), progression.join(), portalCooldowns.join(), itemTravelCooldowns.join(),
                        waystoneDiscoveries.join(), claimTierOne.join()));
    }

    private CompletableFuture<ResetPreview> assemblePreviewOnMainThread(
            UUID uuid, Map<NamespacedKey, QuestState> questStates, Map<String, StoryProgressRecord> stories,
            Map<String, String> variables, Map<SkillType, Long> progression, Map<String, Instant> portalCooldowns,
            Map<String, Instant> itemTravelCooldowns, int waystoneDiscoveries, boolean claimTierOne) {
        CompletableFuture<ResetPreview> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<ResetCategory> categories = new ArrayList<>();

            long questsActive = questStates.values().stream()
                    .filter(state -> state == QuestState.ACTIVE || state == QuestState.READY_TO_TURN_IN).count();
            long questsCompleted = questStates.values().stream().filter(state -> state == QuestState.COMPLETED).count();
            int questsWithProgress = (int) questStates.values().stream().filter(state -> state != QuestState.NOT_STARTED).count();
            categories.add(new ResetCategory("Quêtes", questsWithProgress,
                    questsWithProgress == 0 ? "aucune progression enregistrée"
                            : questsActive + " active(s), " + questsCompleted + " terminée(s), progression d'objectifs et quête suivie"));

            long storiesActive = stories.values().stream().filter(record -> record.state() == StoryState.ACTIVE).count();
            long storiesCompleted = stories.values().stream().filter(record -> record.state() == StoryState.COMPLETED).count();
            int storiesWithProgress = stories.size();
            categories.add(new ResetCategory("Stories", storiesWithProgress,
                    storiesWithProgress == 0 ? "aucune progression enregistrée"
                            : storiesActive + " active(s), " + storiesCompleted + " terminée(s)"));

            categories.add(new ResetCategory("Variables / unlocks", variables.size(),
                    variables.isEmpty() ? "aucune variable" : String.join(", ", previewKeys(variables))));
            categories.add(new ResetCategory("Déblocage CLAIM_TIER_1", claimTierOne ? 1 : 0,
                    claimTierOne ? "débloqué — sera re-verrouillé" : "déjà verrouillé"));

            long globalXp = progression.getOrDefault(SkillType.GLOBAL, 0L);
            categories.add(new ResetCategory("Progression RPG", progression.size(),
                    progression.isEmpty() ? "aucun niveau / XP"
                            : progression.size() + " compétence(s), XP global : " + globalXp));

            categories.add(new ResetCategory("Découvertes de Waystones", waystoneDiscoveries,
                    waystoneDiscoveries == 0 ? "aucune Waystone découverte" : waystoneDiscoveries + " Waystone(s) découverte(s)"));

            categories.add(new ResetCategory("Cooldowns de portails", portalCooldowns.size(),
                    portalCooldowns.isEmpty() ? "aucun cooldown persistant" : String.join(", ", portalCooldowns.keySet())));
            categories.add(new ResetCategory("Cooldowns de voyage par objet (Rune…)", itemTravelCooldowns.size(),
                    itemTravelCooldowns.isEmpty() ? "aucun cooldown persistant" : String.join(", ", itemTravelCooldowns.keySet())));

            int claims = claimService.claimsOwnedBy(uuid).size();
            categories.add(new ResetCategory("Claim principal", claims,
                    claims == 0 ? "aucun claim" : claims + " claim(s) — données de protection uniquement, les blocs restent"));

            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) {
                int rpgItems = countRpgItems(online, customItemRegistry);
                categories.add(new ResetCategory("Inventaire (objets RPGQuest)", rpgItems,
                        rpgItems == 0 ? "aucun objet RPGQuest" : rpgItems + " objet(s) — retirés immédiatement (inventaire vanilla intact)"));
            } else {
                categories.add(ResetCategory.notInspectable("Inventaire (objets RPGQuest)",
                        "joueur hors ligne — non inspectable ici ; nettoyé automatiquement au prochain login"));
            }

            result.complete(new ResetPreview(online != null, List.copyOf(categories)));
        });
        return result;
    }

    private static List<String> previewKeys(Map<String, String> variables) {
        int limit = 6;
        List<String> keys = new ArrayList<>(variables.keySet());
        if (keys.size() <= limit) {
            return keys;
        }
        List<String> shortened = new ArrayList<>(keys.subList(0, limit));
        shortened.add("… (+" + (keys.size() - limit) + ")");
        return shortened;
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
     * Retire de l'inventaire (36 cases + armure + main secondaire + curseur) tous les objets
     * personnalisés RPGQuest — identifiés par {@link YamlCustomItemRegistry#isCustomItem} (PDC),
     * jamais par matériau. Ne touche à rien d'autre. Retourne le nombre d'exemplaires retirés.
     */
    public static int removeRpgItems(Player player, YamlCustomItemRegistry customItemRegistry) {
        return countOrRemoveRpgItems(player, customItemRegistry, true);
    }

    /**
     * Compte les objets personnalisés RPGQuest de l'inventaire <strong>sans rien retirer</strong>
     * (même portée et même identification PDC que {@link #removeRpgItems}) — utilisé par le preview
     * du reset admin.
     */
    public static int countRpgItems(Player player, YamlCustomItemRegistry customItemRegistry) {
        return countOrRemoveRpgItems(player, customItemRegistry, false);
    }

    private static int countOrRemoveRpgItems(Player player, YamlCustomItemRegistry customItemRegistry, boolean remove) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int count = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && customItemRegistry.isCustomItem(stack)) {
                count += stack.getAmount();
                if (remove) {
                    inventory.setItem(slot, null);
                }
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir() && customItemRegistry.isCustomItem(cursor)) {
            count += cursor.getAmount();
            if (remove) {
                player.setItemOnCursor(null);
            }
        }
        return count;
    }
}
