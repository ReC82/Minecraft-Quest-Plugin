package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimTier;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * UX 100% sans commande (mission « premier claim 5×5 ») : clic droit sur un bloc avec l'Acte de
 * propriété ({@link #DEED_ITEM_ID}) dans le monde des claims ({@link ClaimConfig#world()}) calcule
 * un aperçu d'un futur claim {@link ClaimTier#TIER_1} centré sur le bloc visé, puis un second clic
 * droit sur (ou près de) la même cible confirme et crée réellement le claim via {@link
 * ClaimService#create(Player, String, Location, Location, Location, Location)} — jamais un second
 * chemin de création parallèle, uniquement un calcul de bornes autour de l'API existante.
 *
 * <p>Aperçu conservé en mémoire uniquement ({@link #pending}, jamais persisté) : perdu sur
 * redémarrage/déconnexion, sans conséquence — il ne représente qu'une intention, jamais un
 * engagement, tant que la confirmation n'a pas réussi.</p>
 *
 * <p><b>Double usage de l'Acte</b> (mission « visualisation des limites du claim ») : une fois un
 * claim principal posé, le même objet ({@link #DEED_ITEM_ID}) sert d'outil de visualisation
 * (délègue à {@link ClaimBorderRenderer}) plutôt que de tenter une seconde création — jamais
 * consommé dans ce cas, et redonné gratuitement par Jo à cette fin (voir {@code dialogues/jo.yml},
 * condition {@code LACKS_CUSTOM_ITEM}). Choix délibéré plutôt qu'un second objet dédié : le
 * comportement de l'Acte est déjà entièrement conditionné à l'existence d'un claim principal.</p>
 *
 * <p><b>Périmètre ou faisceau</b> (mission « retrouver visuellement son claim à distance ») : ce
 * même clic droit choisit selon la position réelle du joueur ({@link Claim#contains}, jamais le
 * bloc visé) — {@link ClaimBorderRenderer#show} depuis l'intérieur du claim, {@link
 * ClaimBorderRenderer#showBeacon} sinon (périmètre au sol peu repérable depuis ailleurs).</p>
 */
public final class DeedClaimListener implements Listener {

    public static final NamespacedKey DEED_ITEM_ID = new NamespacedKey("rpgquest", "acte_propriete");

    private static final long PREVIEW_TIMEOUT_MILLIS = 60_000L;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final ClaimBorderRenderer borderRenderer;
    private final Supplier<ClaimConfig> config;
    private final Map<UUID, PendingPreview> pending = new ConcurrentHashMap<>();

    public DeedClaimListener(RPGQuestPlugin plugin, ClaimService claimService,
                              YamlCustomItemRegistry customItemRegistry, ClaimBorderRenderer borderRenderer,
                              Supplier<ClaimConfig> config) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.customItemRegistry = customItemRegistry;
        this.borderRenderer = borderRenderer;
        this.config = config;
    }

    private record PendingPreview(String world, int centerX, int centerZ, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !customItemRegistry.identify(item).map(DEED_ITEM_ID::equals).orElse(false)) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();

        if (!block.getWorld().getName().equals(config.get().world())) {
            player.sendMessage(MM.deserialize(
                    "<red>L'Acte de propriété ne peut être utilisé que dans le monde</red> <white><world></white>.",
                    Placeholder.unparsed("world", config.get().world())));
            return;
        }

        UUID playerId = player.getUniqueId();

        // Mission « visualisation des limites du claim » : une fois un claim principal posé, l'Acte
        // devient un outil de visualisation non consommable — jamais une seconde création (déjà
        // refusée par ClaimService de toute façon), jamais besoin d'un second objet dédié.
        Optional<Claim> mainClaim = claimService.mainClaimOf(playerId);
        if (mainClaim.isPresent()) {
            Claim claim = mainClaim.get();
            Location playerLocation = player.getLocation();
            boolean insideOwnClaim = claim.contains(playerLocation.getWorld().getName(),
                    playerLocation.getBlockX(), playerLocation.getBlockY(), playerLocation.getBlockZ());
            if (insideOwnClaim) {
                borderRenderer.show(player, claim);
                player.sendMessage(MM.deserialize("<green>Voici les limites de ta propriété.</green>"));
            } else {
                // Hors de son claim (mission « retrouver visuellement son claim à distance ») : le
                // périmètre au sol ne serait pas repérable de là où il se trouve — un faisceau au
                // centre du claim l'aide à retrouver la direction, jamais le périmètre lui-même.
                borderRenderer.showBeacon(player, claim);
                player.sendMessage(MM.deserialize(
                        "<green>Un faisceau indique la position de ta propriété.</green>"));
            }
            return;
        }

        int centerX = block.getX();
        int centerZ = block.getZ();
        String world = block.getWorld().getName();

        PendingPreview existing = pending.get(playerId);
        if (existing != null && !existing.isExpired() && existing.world().equals(world)
                && existing.centerX() == centerX && existing.centerZ() == centerZ) {
            confirm(player, block.getWorld(), centerX, centerZ, item);
            return;
        }

        preview(player, block.getWorld(), centerX, centerZ);
    }

    private void preview(Player player, World world, int centerX, int centerZ) {
        claimService.hasClaimTierOne(player.getUniqueId()).thenAccept(hasTier -> runOnMainThread(() -> {
            if (!hasTier) {
                player.sendMessage(MM.deserialize(
                        "<red>Tu n'as pas encore le droit de poser un claim ici.</red>"));
                return;
            }
            if (!claimService.claimsOwnedBy(player.getUniqueId()).isEmpty()) {
                player.sendMessage(MM.deserialize("<red>Tu possèdes déjà un claim principal.</red>"));
                return;
            }

            int size = ClaimTier.TIER_1.activeSize();
            int minOffset = ClaimTier.minOffset(size);
            int maxOffset = ClaimTier.maxOffset(size);
            pending.put(player.getUniqueId(), new PendingPreview(
                    world.getName(), centerX, centerZ, System.currentTimeMillis() + PREVIEW_TIMEOUT_MILLIS));

            player.sendMessage(MM.deserialize(
                    "<gold>Aperçu du claim :</gold> <white><size>x<size></white> "
                            + "<gray>(<minx>,<minz>) → (<maxx>,<maxz>)</gray>",
                    Placeholder.unparsed("size", String.valueOf(size)),
                    Placeholder.unparsed("minx", String.valueOf(centerX + minOffset)),
                    Placeholder.unparsed("minz", String.valueOf(centerZ + minOffset)),
                    Placeholder.unparsed("maxx", String.valueOf(centerX + maxOffset)),
                    Placeholder.unparsed("maxz", String.valueOf(centerZ + maxOffset))));
            player.sendMessage(MM.deserialize(
                    "<gray>Réservation future :</gray> <white><size>x<size></white>",
                    Placeholder.unparsed("size", String.valueOf(ClaimTier.TIER_1.reservationSize()))));
            player.sendMessage(MM.deserialize(
                    "<yellow>Clique à nouveau au même endroit avec l'Acte pour confirmer.</yellow>"));
        })).exceptionally(error -> null);
    }

    private void confirm(Player player, World world, int centerX, int centerZ, ItemStack deed) {
        pending.remove(player.getUniqueId());

        int activeSize = ClaimTier.TIER_1.activeSize();
        int activeMin = ClaimTier.minOffset(activeSize);
        int activeMax = ClaimTier.maxOffset(activeSize);
        int reservedSize = ClaimTier.TIER_1.reservationSize();
        int reservedMin = ClaimTier.minOffset(reservedSize);
        int reservedMax = ClaimTier.maxOffset(reservedSize);
        int minY = world.getMinHeight();
        // Toujours borné par effectiveMaxHeight (config claims.max-height) : la hauteur totale du
        // monde peut dépasser cette limite, auquel cas ClaimService#create refuserait TOO_LARGE.
        int maxY = Math.min(world.getMaxHeight() - 1, minY + claimService.effectiveMaxHeight(player) - 1);

        Location activePos1 = new Location(world, centerX + activeMin, minY, centerZ + activeMin);
        Location activePos2 = new Location(world, centerX + activeMax, maxY, centerZ + activeMax);
        Location reservedPos1 = new Location(world, centerX + reservedMin, minY, centerZ + reservedMin);
        Location reservedPos2 = new Location(world, centerX + reservedMax, maxY, centerZ + reservedMax);

        String id = "main_" + player.getUniqueId();
        claimService.create(player, id, activePos1, activePos2, reservedPos1, reservedPos2)
                .thenAccept(outcome -> runOnMainThread(() -> {
                    if (outcome == ClaimService.CreateOutcome.CREATED) {
                        consumeOne(player, deed);
                        player.sendMessage(MM.deserialize(
                                "<green>Ton claim principal a été créé :</green> <white><id></white>",
                                Placeholder.unparsed("id", id)));
                    } else {
                        player.sendMessage(MM.deserialize(
                                "<red>Impossible de créer le claim ici :</red> <white><reason></white>",
                                Placeholder.unparsed("reason", outcome.name())));
                    }
                })).exceptionally(error -> null);
    }

    private void consumeOne(Player player, ItemStack deed) {
        if (deed.getAmount() <= 1) {
            player.getInventory().remove(deed);
        } else {
            deed.setAmount(deed.getAmount() - 1);
        }
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
