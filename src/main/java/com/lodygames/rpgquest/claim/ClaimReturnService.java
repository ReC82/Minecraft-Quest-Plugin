package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Seule source de vérité du <strong>moyen de repartir</strong> du monde des claims (issue #22) : la
 * <em>Pierre de retour</em> ({@link RpgItemKeys#PIERRE_RETOUR}), objet de voyage claims → Hub géré
 * par {@code travel.ItemTravelService} — permanent, jamais consommé, clic droit, sans commande.
 *
 * <p>Deux appelants partagent cette logique, et c'est volontairement <em>un seul</em> endroit :</p>
 * <ul>
 *   <li>{@link ClaimWorldAccessGuard} l'interroge <strong>avant</strong> la téléportation : entrer
 *       sans moyen de repartir est un piège, donc l'entrée est refusée ;</li>
 *   <li>{@link ClaimWorldSafetyListener} s'en sert <strong>à l'arrivée</strong>, pour les joueurs
 *       arrivés autrement que par le portail ({@code /tp}, reconnexion, présence antérieure).</li>
 * </ul>
 *
 * <p>{@link #destination()} est exactement la cible utilisée par l'objet lui-même (le spawn du
 * village résolu, jamais une position figée) : si elle ne se résout pas, la Pierre de retour ne fait
 * rien et {@link Outcome#canReturn()} ne doit pas mentir à l'appelant.</p>
 */
public final class ClaimReturnService {

    /** Issue de la garantie d'une Pierre de retour. */
    public enum Outcome {
        /** Le joueur en détenait déjà une : rien à faire. */
        ALREADY_HELD(true),
        /** Remise dans l'inventaire du joueur. */
        GIVEN(true),
        /** Inventaire plein : déposée aux pieds du joueur (uniquement si le dépôt est autorisé). */
        DROPPED(true),
        /** Inventaire plein et dépôt au sol non autorisé : le joueur n'a <em>rien</em>. */
        NO_ROOM(false),
        /** L'objet n'est pas défini ({@code items/pierre_retour.yml} absent ou invalide). */
        UNAVAILABLE(false);

        private final boolean canReturn;

        Outcome(boolean canReturn) {
            this.canReturn = canReturn;
        }

        /** {@code true} si le joueur dispose, à l'issue de l'appel, d'un moyen de repartir. */
        public boolean canReturn() {
            return canReturn;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Supplier<Optional<Location>> destination;

    public ClaimReturnService(RPGQuestPlugin plugin, YamlCustomItemRegistry customItemRegistry,
                               Supplier<Optional<Location>> destination) {
        this.plugin = plugin;
        this.customItemRegistry = customItemRegistry;
        this.destination = destination;
    }

    /** Cible réelle du retour au Hub ; vide = la Pierre de retour ne mènerait nulle part. */
    public Optional<Location> destination() {
        return destination.get();
    }

    /** {@code true} si une Pierre de retour mènerait effectivement quelque part. */
    public boolean destinationResolvable() {
        return destination().isPresent();
    }

    /** {@code true} si le joueur détient déjà une Pierre de retour. */
    public boolean holdsReturnStone(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(stack -> customItemRegistry.identify(stack).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false));
    }

    /**
     * Garantit que le joueur détient une Pierre de retour. Idempotent : jamais de second exemplaire.
     *
     * @param dropIfInventoryFull {@code true} à l'arrivée dans le monde des claims (le joueur est
     *     déjà sur place : mieux vaut l'objet au sol que rien) ; {@code false} pour un contrôle
     *     <em>préventif</em> avant téléportation, où déposer l'objet au point de départ ne garantit
     *     rien et ne ferait que joncher le Hub.
     * @return l'issue réelle, jamais une supposition.
     */
    public Outcome ensureReturnStone(Player player, boolean dropIfInventoryFull) {
        if (holdsReturnStone(player)) {
            return Outcome.ALREADY_HELD;
        }
        Optional<ItemStack> created = customItemRegistry.create(RpgItemKeys.PIERRE_RETOUR, 1);
        if (created.isEmpty()) {
            plugin.getSLF4JLogger().error(
                    "[claims-retour] objet « {} » introuvable dans le registre : impossible de garantir un retour "
                            + "au Hub pour {}. Vérifier items/pierre_retour.yml.",
                    RpgItemKeys.PIERRE_RETOUR, player.getName());
            return Outcome.UNAVAILABLE;
        }
        ItemStack stone = created.get();
        if (!dropIfInventoryFull && player.getInventory().firstEmpty() < 0) {
            return Outcome.NO_ROOM; // rien n'est remis : l'appelant décide quoi faire.
        }
        boolean dropped = false;
        for (ItemStack leftover : player.getInventory().addItem(stone).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            dropped = true;
        }
        if (dropped) {
            // Issue #22 : l'objet tombait déjà au sol, mais le message annonçait « tu reçois » —
            // un joueur à l'inventaire plein cherchait donc un objet absent de ses poches.
            plugin.getSLF4JLogger().info(
                    "[claims-retour] inventaire plein pour {} : Pierre de retour déposée à ses pieds.",
                    player.getName());
            player.sendMessage(MM.deserialize(
                    "<aqua>Une Pierre de retour est tombée à tes pieds</aqua> <gray>— ton inventaire "
                            + "était plein. Ramasse-la, puis clic droit avec pour revenir au village.</gray>"));
            return Outcome.DROPPED;
        }
        player.sendMessage(MM.deserialize(
                "<aqua>Tu reçois une Pierre de retour.</aqua> <gray>Clic droit avec pour revenir au village "
                        + "depuis le monde des claims.</gray>"));
        return Outcome.GIVEN;
    }
}
