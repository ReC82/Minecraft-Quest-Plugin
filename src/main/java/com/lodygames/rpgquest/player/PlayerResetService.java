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
 * Reset admin ciblé d'<em>un seul</em> joueur, en <strong>deux portées distinctes</strong>
 * ({@link ResetScope}, issue #235) : remet l'état RPGQuest dans l'équivalent fonctionnel d'un joueur
 * qui n'a jamais joué, pour pouvoir refaire tout le parcours d'onboarding
 * (Story → CLAIM_TIER_1 → Jo → Acte de propriété → claim → Wild → Waystones / Rune de rappel).
 *
 * <h2>Pourquoi deux portées et non un seul « reset »</h2>
 *
 * <p>Jusqu'à #235 il n'y en avait qu'une, et elle produisait un état <strong>incohérent</strong> :
 * elle effaçait toutes les variables du joueur — donc le droit au kit de départ et le palier — mais
 * ne retirait de l'inventaire que les objets <em>RPGQuest</em> (reconnus par PDC). Or le kit de
 * départ est fait d'objets <strong>vanilla</strong> ({@code WOODEN_PICKAXE}…) : ils restaient en
 * place pendant que le droit d'en redemander un était rétabli, et le joueur obtenait un second kit.
 * Voir {@code player.StarterToolKitService}.</p>
 *
 * <p>La réponse n'est pas de « reconnaître les anciens outils du kit » : une pioche en bois du kit
 * est <strong>indiscernable</strong> d'une pioche en bois fabriquée, et supprimer la seconde en
 * croyant retirer la première serait pire que le problème. La réponse est de rendre l'intention
 * explicite : {@link ResetScope#PROGRESSION} conserve l'inventaire et le <em>dit</em>,
 * {@link ResetScope#NEW_PLAYER} le vide et le <em>dit</em> aussi.</p>
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
 * <p><strong>Inventaire</strong> : selon la portée, soit seuls les objets personnalisés RPGQuest
 * (identifiés par PDC via {@link YamlCustomItemRegistry}, jamais par matériau) sont retirés, soit
 * tout est vidé. Si le joueur est hors ligne, un marqueur {@link #PENDING_INVENTORY_KEY} portant la
 * portée est posé et {@link NewPlayerResetJoinListener} fait le nettoyage à sa prochaine connexion,
 * avant que le kit de départ ne soit redistribué.</p>
 *
 * <p><strong>Preview / dry-run</strong> : {@link #previewReset(UUID, ResetScope)} lit les mêmes
 * catégories ({@link ResetPreview}) sans effectuer <em>aucune</em> écriture — pour vérifier ce qui
 * serait effacé avant de confirmer un reset réel.</p>
 */
public final class PlayerResetService {

    /**
     * Variable posée pour un joueur hors ligne : nettoyage d'inventaire différé au prochain login.
     * Sa <em>valeur</em> est le nom de la {@link ResetScope} à appliquer ; l'ancienne valeur
     * {@code "1"} (avant #235) est relue comme {@link ResetScope#PROGRESSION}, donc un marqueur déjà
     * posé en base avant la mise à jour garde exactement son ancien comportement.
     */
    public static final String PENDING_INVENTORY_KEY = "__pending_new_player_reset__";

    /**
     * Portée d'un reset — l'<strong>intention</strong>, pas un détail d'implémentation : c'est elle
     * que l'écran de confirmation annonce, et c'est elle qui décide du sort de l'inventaire.
     */
    public enum ResetScope {

        /**
         * Données RPGQuest seulement. <strong>L'inventaire Minecraft est conservé</strong> (seuls
         * les objets RPGQuest reconnus par PDC sont retirés).
         *
         * <p>Conséquence à annoncer, et c'est tout l'objet de #235 : le droit au kit de départ est
         * rétabli, donc un joueur qui possède déjà physiquement un kit pourra en demander un
         * second. C'est voulu pour cette portée — on ne touche pas aux affaires du joueur.</p>
         */
        PROGRESSION("Reset progression RPGQuest", false),

        /**
         * Tout ce que fait {@link #PROGRESSION}, <strong>plus</strong> l'inventaire, l'équipement,
         * la main secondaire, le curseur et le coffre de l'Ender — l'état réellement cohérent pour
         * rejouer l'onboarding depuis zéro. Réservé aux tests / à l'administration.
         */
        NEW_PLAYER("Reset nouveau joueur complet", true);

        private final String label;
        private final boolean wipesInventory;

        ResetScope(String label, boolean wipesInventory) {
            this.label = label;
            this.wipesInventory = wipesInventory;
        }

        public String label() {
            return label;
        }

        /** {@code true} si cette portée vide l'inventaire et l'équipement, pas seulement les données. */
        public boolean wipesInventory() {
            return wipesInventory;
        }

        /**
         * Portée enregistrée dans {@link #PENDING_INVENTORY_KEY}. Tolère {@code "1"} (format
         * d'avant #235) et toute valeur illisible, qui retombent sur la portée la <strong>moins
         * destructrice</strong> — un marqueur douteux ne doit jamais vider un inventaire.
         */
        public static ResetScope ofMarker(String stored) {
            if (stored == null) {
                return PROGRESSION;
            }
            for (ResetScope scope : values()) {
                if (scope.name().equalsIgnoreCase(stored.trim())) {
                    return scope;
                }
            }
            return PROGRESSION;
        }
    }

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
    private final StarterToolKitService starterToolKitService;

    public PlayerResetService(RPGQuestPlugin plugin, QuestProgressEngine questProgressEngine, StoryService storyService,
                               WaystoneService waystoneService, ClaimService claimService,
                               ProgressionService progressionService, QuestJournalService questJournalService,
                               PortalService portalService, ItemTravelService itemTravelService,
                               PlayerVariableRepository variableRepository, ProgressionRepository progressionRepository,
                               PortalCooldownRepository portalCooldownRepository,
                               ItemTravelCooldownRepository itemTravelCooldownRepository,
                               YamlCustomItemRegistry customItemRegistry,
                               StarterToolKitService starterToolKitService) {
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
        this.starterToolKitService = starterToolKitService;
    }

    /**
     * Résumé concis de ce qui a été fait, pour l'affichage admin. {@code inventoryItemsRemoved}
     * vaut {@code -1} quand le nettoyage a été différé (joueur hors ligne), jamais 0 — « rien
     * retiré » et « pas encore fait » ne doivent pas se ressembler.
     */
    public record ResetSummary(ResetScope scope, boolean online, int inventoryItemsRemoved,
                               boolean inventoryDeferred) {
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
     * Exécute le reset. Toutes les suppressions en base sont faites quel que soit l'état de
     * connexion <strong>et sont identiques dans les deux portées</strong> ; seuls le sort de
     * l'inventaire et l'invalidation des caches mémoire dépendent de la portée et de la présence du
     * joueur (voir Javadoc de classe).
     */
    public CompletableFuture<ResetSummary> reset(UUID uuid, String name, ResetScope scope) {
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
                .thenCompose(deleted -> finishOnMainThread(uuid, scope));
    }

    /**
     * Construit un {@link ResetPreview} : lit les mêmes catégories que {@link #resetToNewPlayer} mais
     * n'écrit <strong>rien</strong> (aucune suppression, aucun marqueur, aucune invalidation de
     * cache). Fonctionne pour un joueur en ligne ou hors ligne ; l'inventaire n'est comptabilisé que
     * si le joueur est en ligne (sinon catégorie signalée « non inspectable »).
     */
    public CompletableFuture<ResetPreview> previewReset(UUID uuid, ResetScope scope) {
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
                .thenCompose(ignored -> assemblePreviewOnMainThread(uuid, scope, questStates.join(), stories.join(),
                        variables.join(), progression.join(), portalCooldowns.join(), itemTravelCooldowns.join(),
                        waystoneDiscoveries.join(), claimTierOne.join()));
    }

    private CompletableFuture<ResetPreview> assemblePreviewOnMainThread(
            UUID uuid, ResetScope scope, Map<NamespacedKey, QuestState> questStates,
            Map<String, StoryProgressRecord> stories,
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

            // Issue #235 : ces deux lignes sortent du fourre-tout « variables » parce que ce sont
            // elles qui expliquent le double kit observé en jeu. Lues dans la carte déjà chargée :
            // aucune requête supplémentaire.
            boolean kitAlreadyTaken = StarterToolKitService.NOT_AVAILABLE.equalsIgnoreCase(
                    variables.getOrDefault(StarterToolKitService.AVAILABLE_KEY, ""));
            categories.add(new ResetCategory("Droit au kit de départ", kitAlreadyTaken ? 1 : 0,
                    kitAlreadyTaken
                            ? "kit déjà reçu — le droit sera RÉTABLI"
                                    + (scope.wipesInventory() ? "" : ", alors que les outils déjà"
                                            + " reçus resteront dans l'inventaire")
                            : "droit déjà disponible — inchangé"));
            String storedTier = variables.getOrDefault(StarterToolKitService.TIER_KEY, "");
            categories.add(new ResetCategory("Palier de kit", storedTier.isBlank() ? 0 : 1,
                    storedTier.isBlank() ? "déjà au palier 1" : "palier " + storedTier + " — retour au palier 1"));

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
            String inventoryLabel = scope.wipesInventory()
                    ? "Inventaire COMPLET (vanilla inclus)" : "Inventaire (objets RPGQuest)";
            if (online != null) {
                int affected = scope.wipesInventory()
                        ? countEverything(online) : countRpgItems(online, customItemRegistry);
                categories.add(new ResetCategory(inventoryLabel, affected, affected == 0
                        ? (scope.wipesInventory() ? "inventaire déjà vide" : "aucun objet RPGQuest")
                        : affected + " objet(s) — " + (scope.wipesInventory()
                                ? "TOUT sera vidé : inventaire, armure, main secondaire, curseur et "
                                        + "coffre de l'Ender"
                                : "objets RPGQuest retirés, inventaire vanilla INTACT (un kit de "
                                        + "départ déjà reçu reste donc en place)")));
            } else {
                categories.add(ResetCategory.notInspectable(inventoryLabel,
                        "joueur hors ligne — non inspectable ici ; appliqué automatiquement au prochain login"));
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

    private CompletableFuture<ResetSummary> finishOnMainThread(UUID uuid, ResetScope scope) {
        CompletableFuture<ResetSummary> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) {
                int removed = applyInventoryReset(online, scope, customItemRegistry);
                progressionService.loadForPlayer(uuid);       // recharge → cache vide
                portalService.reloadCooldownsForPlayer(uuid);  // recharge → cache vide
                itemTravelService.reloadCooldownsForPlayer(uuid);
                questJournalService.clearTrackingFor(uuid);
                // Issue #235 : le palier de kit est lu en mémoire pour l'affichage du Guide, donc il
                // doit suivre l'effacement des variables — sans cela le Guide annoncerait encore le
                // palier d'avant le reset.
                starterToolKitService.reloadForPlayer(uuid);
                result.complete(new ResetSummary(scope, true, removed, false));
            } else {
                progressionService.unloadForPlayer(uuid); // sans effet si non chargé, sûr
                starterToolKitService.forget(uuid);
                variableRepository.set(uuid, PENDING_INVENTORY_KEY, scope.name()).exceptionally(error -> {
                    plugin.getSLF4JLogger().error("Impossible de poser le marqueur de nettoyage d'inventaire différé pour {}", uuid, error);
                    return null;
                });
                result.complete(new ResetSummary(scope, false, -1, true));
            }
        });
        return result;
    }

    /**
     * Applique au joueur en ligne le sort de l'inventaire prévu par la portée, et renvoie le nombre
     * d'exemplaires effectivement retirés. Point d'entrée unique, partagé avec le nettoyage différé
     * de {@link NewPlayerResetJoinListener} : une seule définition de « ce que vide chaque portée ».
     */
    public static int applyInventoryReset(Player player, ResetScope scope,
                                           YamlCustomItemRegistry customItemRegistry) {
        return scope.wipesInventory() ? wipeEverything(player) : removeRpgItems(player, customItemRegistry);
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

    /**
     * Vide <strong>tout</strong> ce que porte le joueur : inventaire (y compris armure et main
     * secondaire, couverts par {@link PlayerInventory#clear()}), curseur, et coffre de l'Ender.
     * Renvoie le nombre d'exemplaires retirés.
     *
     * <p>Le coffre de l'Ender en fait partie <strong>volontairement</strong> : y laisser du matériel
     * rendrait « nouveau joueur » faux, et c'est précisément l'incohérence que #235 corrige. Cette
     * portée est annoncée mot pour mot avant confirmation — elle n'est jamais appliquée par
     * surprise.</p>
     */
    public static int wipeEverything(Player player) {
        int removed = countEverything(player);
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.getEnderChest().clear();
        return removed;
    }

    /** Compte, sans rien retirer, ce que {@link #wipeEverything} retirerait. */
    public static int countEverything(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                count += stack.getAmount();
            }
        }
        for (ItemStack stack : player.getEnderChest().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                count += stack.getAmount();
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir()) {
            count += cursor.getAmount();
        }
        return count;
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
