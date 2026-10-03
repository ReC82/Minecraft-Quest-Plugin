package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Kit d'outils en bois demandé explicitement au Guide (issue #26, partie A) — distinct du kit
 * "Rune de rappel" de {@link StarterKitListener} (remis une seule fois à vie, sans rapport). Règles :
 *
 * <ul>
 *   <li>aucune remise automatique : ni clic simple sur le PNJ, ni connexion, ni réapparition — seul
 *       {@link #requestKit(Player)} (appelé par l'action de dialogue {@code GIVE_STARTER_KIT})
 *       déclenche quoi que ce soit ;</li>
 *   <li>droit persistant par joueur ({@link PlayerVariableRepository}, clé {@link #AVAILABLE_KEY}) :
 *       {@code "false"} = déjà reçu depuis la dernière mort, absent/toute autre valeur = droit
 *       disponible (un nouveau joueur, sans ligne, a donc le droit dès le début) ;</li>
 *   <li>{@link #onDeath} restaure le droit ({@code "true"}) à chaque mort, y compris avant toute
 *       première remise (idempotent : remettre un droit déjà présent ne change rien) ;</li>
 *   <li>remise tout ou rien : les emplacements libres du stockage normal (hors armure et main
 *       secondaire, voir {@link PlayerInventory#getStorageContents()}) sont comptés <strong>avant</strong>
 *       toute écriture d'inventaire ; en dessous du nombre d'objets du kit, aucun objet n'est donné
 *       et le droit n'est jamais consommé ;</li>
 *   <li>anti double-clic : {@link #pendingRequests} empêche deux demandes concurrentes du même
 *       joueur de passer toutes les deux la vérification du droit avant que l'une des deux ne l'ait
 *       consommé.</li>
 * </ul>
 *
 * <p>{@code /rpgadmin player resetnew} restaure le droit initial gratuitement : il efface
 * <strong>toutes</strong> les variables du joueur ({@link PlayerVariableRepository#deleteAllForPlayer}),
 * donc aussi {@link #AVAILABLE_KEY} — absence de ligne = droit disponible, sans code dédié ici.</p>
 */
public final class StarterToolKitService implements Listener {

    static final String AVAILABLE_KEY = "STARTER_TOOL_KIT_AVAILABLE";
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

    /** Déclenché uniquement par le choix de dialogue « Demander mon kit de départ » du Guide. */
    public void requestKit(Player player) {
        UUID playerId = player.getUniqueId();
        if (!pendingRequests.add(playerId)) {
            // Clic rapide : une demande de ce joueur est déjà en cours de vérification/remise, ignorée.
            return;
        }

        StarterToolKitConfig config = configSupplier.get();
        if (!config.enabled() || config.items().isEmpty()) {
            pendingRequests.remove(playerId);
            return;
        }

        variableRepository.get(playerId, AVAILABLE_KEY).whenComplete((storedValue, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Impossible de vérifier le droit au kit de départ de {}", playerId, error);
                pendingRequests.remove(playerId);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    pendingRequests.remove(playerId);
                    return;
                }
                handleRequest(player, config, storedValue.orElse(""));
            });
        });
    }

    private void handleRequest(Player player, StarterToolKitConfig config, String storedValue) {
        UUID playerId = player.getUniqueId();
        boolean available = !NOT_AVAILABLE.equalsIgnoreCase(storedValue);
        if (!available) {
            player.sendMessage(MM.deserialize(
                    "<gray>Tu as déjà reçu ton kit de départ. Il te sera redonné après ta prochaine mort.</gray>"));
            pendingRequests.remove(playerId);
            return;
        }

        List<Material> items = config.items();
        PlayerInventory inventory = player.getInventory();
        int[] freeSlots = findFreeStorageSlots(inventory, items.size());
        if (freeSlots.length < items.size()) {
            player.sendMessage(MM.deserialize(
                    "<red>Tu n'as pas assez de place dans ton inventaire. Libère 4 emplacements pour recevoir ton kit de départ.</red>"));
            pendingRequests.remove(playerId);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            inventory.setItem(freeSlots[i], new ItemStack(items.get(i), 1));
        }
        player.sendMessage(MM.deserialize(
                "<aqua>Tu reçois ton kit de départ :</aqua> <gray>une épée, une pioche, une pelle et une hache en bois.</gray>"));

        variableRepository.set(playerId, AVAILABLE_KEY, NOT_AVAILABLE).whenComplete((v, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Impossible de marquer le kit de départ de {} comme reçu", playerId, error);
            }
            pendingRequests.remove(playerId);
        });
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
