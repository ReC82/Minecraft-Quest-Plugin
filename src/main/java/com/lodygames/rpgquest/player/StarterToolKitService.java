package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Kit de départ demandé explicitement au Guide (issue #26, partie A), à <strong>paliers
 * progressifs</strong> (issue #218). Règles conservées à l'identique depuis #26 :
 *
 * <ul>
 *   <li>aucune remise automatique : ni clic simple sur un PNJ, ni connexion, ni réapparition — seul
 *       {@link #requestKit(Player)} (action de dialogue {@code GIVE_STARTER_KIT}) déclenche quoi que
 *       ce soit ;</li>
 *   <li>droit persistant par joueur ({@link #AVAILABLE_KEY}) : {@code "false"} = déjà reçu depuis la
 *       dernière mort, absent/autre valeur = droit disponible ;</li>
 *   <li>{@link #onDeath} rouvre le droit à chaque mort, y compris avant toute première remise ;</li>
 *   <li>remise <strong>tout ou rien</strong> : les emplacements libres du stockage normal (hors
 *       armure et main secondaire) sont comptés avant toute écriture ;</li>
 *   <li>anti double-clic via {@link #pendingRequests}.</li>
 * </ul>
 *
 * <p><strong>Ce que #218 ajoute</strong> : le contenu remis dépend du <em>meilleur palier
 * débloqué</em>, persisté dans {@link #TIER_KEY} (absent = palier 1, acquis d'office à l'arrivée).
 * Le nombre d'emplacements requis est donc calculé sur le contenu réel de ce palier, jamais sur une
 * constante. Un palier se débloque par {@link #grantTier}, appelé en récompense de la quête du
 * palier — et <strong>jamais</strong> en écrivant la variable directement, parce que c'est cette
 * méthode qui refuse de sauter un palier.</p>
 *
 * <p>Les matériaux déjà remis à un PNJ pour la quête du palier suivant ne passent pas par ici : ils
 * sont sécurisés par {@code DELIVER_ITEM_TO_NPC} (issue #123) et survivent à la mort indépendamment
 * du kit.</p>
 *
 * <p>{@code /rpgadmin player resetnew} efface <strong>toutes</strong> les variables du joueur, donc
 * aussi {@link #TIER_KEY} : un joueur réinitialisé repart au palier 1, sans code dédié ici.</p>
 */
public final class StarterToolKitService implements Listener {

    static final String AVAILABLE_KEY = "STARTER_TOOL_KIT_AVAILABLE";
    /** Meilleur palier débloqué (issue #218). Absent = palier 1, acquis automatiquement. */
    public static final String TIER_KEY = "STARTER_KIT_TIER";
    private static final String NOT_AVAILABLE = "false";
    private static final String AVAILABLE_AGAIN = "true";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final PlayerVariableRepository variableRepository;
    private final Supplier<StarterToolKitConfig> configSupplier;
    private final Set<UUID> pendingRequests = ConcurrentHashMap.newKeySet();

    public StarterToolKitService(RPGQuestPlugin plugin, PlayerVariableRepository variableRepository,
                                  Supplier<StarterToolKitConfig> configSupplier) {
        this.plugin = plugin;
        this.variableRepository = variableRepository;
        this.configSupplier = configSupplier;
    }

    /** Restaure le droit à chaque mort — y compris une mort avant toute première remise (idempotent). */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        variableRepository.set(playerId, AVAILABLE_KEY, AVAILABLE_AGAIN).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Impossible de restaurer le droit au kit de départ de {} après sa mort",
                    playerId, error);
            return null;
        });
    }

    // ---- Paliers (issue #218) -------------------------------------------

    /** Palier actuellement débloqué, lu en base. 1 si aucune valeur (ou valeur illisible). */
    public CompletableFuture<Integer> unlockedTier(UUID playerId) {
        return variableRepository.get(playerId, TIER_KEY).thenApply(StarterToolKitService::parseTier);
    }

    /**
     * Résultat d'une tentative de déblocage de palier. {@code SKIPPED} est le refus qui compte :
     * c'est lui qui rend un saut de palier impossible, même si une quête mal écrite demandait le
     * palier 4 à un joueur encore au palier 1.
     */
    public enum GrantOutcome { GRANTED, ALREADY_AT_LEAST, SKIPPED, UNKNOWN_TIER, DISABLED }

    /** Résultat d'un déblocage, avec le palier effectif après l'opération. */
    public record GrantResult(GrantOutcome outcome, int tier) {
    }

    /**
     * Débloque {@code level} pour ce joueur. Appelé en récompense de la quête du palier (via
     * {@code /rpgadmin kit grant-tier}), jamais par une écriture de variable directe.
     *
     * <ul>
     *   <li>palier déjà atteint ou dépassé → {@code ALREADY_AT_LEAST}, aucune écriture (idempotent,
     *       donc une quête répétable ne « redonne » rien) ;</li>
     *   <li>palier non contigu (plus de +1) → {@code SKIPPED}, refusé ;</li>
     *   <li>palier non défini en configuration → {@code UNKNOWN_TIER}.</li>
     * </ul>
     */
    public CompletableFuture<GrantResult> grantTier(UUID playerId, int level) {
        StarterToolKitConfig config = configSupplier.get();
        if (!config.enabled()) {
            return CompletableFuture.completedFuture(new GrantResult(GrantOutcome.DISABLED, 1));
        }
        if (config.tier(level).isEmpty()) {
            return unlockedTier(playerId)
                    .thenApply(current -> new GrantResult(GrantOutcome.UNKNOWN_TIER, current));
        }
        return unlockedTier(playerId).thenCompose(current -> {
            if (level <= current) {
                return CompletableFuture.completedFuture(new GrantResult(GrantOutcome.ALREADY_AT_LEAST, current));
            }
            if (level > current + 1) {
                return CompletableFuture.completedFuture(new GrantResult(GrantOutcome.SKIPPED, current));
            }
            return variableRepository.set(playerId, TIER_KEY, Integer.toString(level))
                    .thenApply(ignored -> new GrantResult(GrantOutcome.GRANTED, level));
        });
    }

    private static int parseTier(Optional<String> stored) {
        try {
            return Math.max(1, Integer.parseInt(stored.orElse("1").trim()));
        } catch (NumberFormatException e) {
            return 1; // Valeur illisible : jamais une absence de kit, toujours le palier de base.
        }
    }

    // ---- Remise ---------------------------------------------------------

    /** Déclenché uniquement par le choix de dialogue « Demander mon kit de départ » du Guide. */
    public void requestKit(Player player) {
        UUID playerId = player.getUniqueId();
        if (!pendingRequests.add(playerId)) {
            // Clic rapide : une demande de ce joueur est déjà en cours de vérification/remise, ignorée.
            return;
        }

        StarterToolKitConfig config = configSupplier.get();
        if (!config.enabled()) {
            pendingRequests.remove(playerId);
            return;
        }

        variableRepository.get(playerId, AVAILABLE_KEY)
                .thenCombine(unlockedTier(playerId), KitRequestState::new)
                .whenComplete((state, error) -> {
                    if (error != null) {
                        plugin.getSLF4JLogger().error("Impossible de vérifier le droit au kit de départ de {}",
                                playerId, error);
                        pendingRequests.remove(playerId);
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            pendingRequests.remove(playerId);
                            return;
                        }
                        handleRequest(player, config, state);
                    });
                });
    }

    /** Les deux lectures nécessaires à une demande : le droit, et le palier débloqué. */
    private record KitRequestState(Optional<String> available, int tier) {
    }

    private void handleRequest(Player player, StarterToolKitConfig config, KitRequestState state) {
        UUID playerId = player.getUniqueId();
        boolean available = !NOT_AVAILABLE.equalsIgnoreCase(state.available().orElse(""));
        if (!available) {
            player.sendMessage(MM.deserialize(
                    "<gray>Tu as déjà reçu ton kit de départ. Il te sera redonné après ta prochaine mort.</gray>"));
            pendingRequests.remove(playerId);
            return;
        }

        Optional<StarterKitTier> tierOpt = config.effectiveTier(state.tier());
        if (tierOpt.isEmpty()) {
            plugin.getSLF4JLogger().error(
                    "Aucun palier de kit applicable pour {} (palier débloqué {}) — configuration incohérente.",
                    playerId, state.tier());
            pendingRequests.remove(playerId);
            return;
        }
        StarterKitTier tier = tierOpt.get();

        List<Material> items = tier.items();
        PlayerInventory inventory = player.getInventory();
        int[] freeSlots = findFreeStorageSlots(inventory, items.size());
        if (freeSlots.length < items.size()) {
            // Le nombre d'emplacements requis vient du contenu RÉEL du palier : un palier plus
            // généreux exige plus de place, et le message doit dire le bon chiffre.
            player.sendMessage(MM.deserialize(
                    "<red>Tu n'as pas assez de place dans ton inventaire. Libère <slots> emplacements pour "
                            + "recevoir ton kit de départ.</red>",
                    Placeholder.unparsed("slots", Integer.toString(items.size()))));
            pendingRequests.remove(playerId);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            inventory.setItem(freeSlots[i], new ItemStack(items.get(i), 1));
        }
        player.sendMessage(MM.deserialize(
                "<aqua>Tu reçois ton kit de départ</aqua> <gray>(<tier>) :</gray> <white><items></white>",
                Placeholder.unparsed("tier", tier.name()),
                Placeholder.component("items", describeItems(items))));

        variableRepository.set(playerId, AVAILABLE_KEY, NOT_AVAILABLE).whenComplete((v, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Impossible de marquer le kit de départ de {} comme reçu", playerId, error);
            }
            pendingRequests.remove(playerId);
        });
    }

    /**
     * « Épée en pierre, Pioche en bois, … » — noms traduits par le client via la clé de traduction
     * vanilla, pour que le message suive le contenu réel du palier sans table à maintenir.
     */
    private static Component describeItems(List<Material> items) {
        Component joined = Component.empty();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                joined = joined.append(Component.text(", "));
            }
            joined = joined.append(Component.translatable(items.get(i)));
        }
        return joined;
    }

    /**
     * Indices (dans {@link PlayerInventory#getStorageContents()}, donc hors armure/main secondaire)
     * des premiers emplacements libres trouvés, jusqu'à {@code needed} — peut renvoyer moins si le
     * stockage n'en contient pas assez (l'appelant doit alors refuser toute la remise).
     */
    private int[] findFreeStorageSlots(PlayerInventory inventory, int needed) {
        ItemStack[] storage = inventory.getStorageContents();
        int[] found = new int[Math.min(needed, storage.length)];
        int count = 0;
        for (int slot = 0; slot < storage.length && count < needed; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.getType().isAir()) {
                found[count++] = slot;
            }
        }
        return count == found.length ? found : java.util.Arrays.copyOf(found, count);
    }
}
