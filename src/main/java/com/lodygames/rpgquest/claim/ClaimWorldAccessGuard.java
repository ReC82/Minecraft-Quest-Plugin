package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.travel.PortalTeleporter;
import com.lodygames.rpgquest.travel.WorldPortalEntryGuard;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * {@link WorldPortalEntryGuard} pour l'entrée dans le monde des claims ({@link ClaimConfig#world()},
 * issues #21/#22/#23) : un portail simple vers ce monde ne laisse passer que les joueurs qui ont
 * <strong>réellement</strong> débloqué leur premier terrain — c'est-à-dire la même vérité que
 * {@code ClaimService.create(...)} exige pour un premier claim, jamais une copie :
 *
 * <ul>
 *   <li>variable de déblocage {@code CLAIM_TIER_1 == "true"} ({@link ClaimService#hasClaimTierOne})
 *       — accordée par la dernière quête de l'histoire principale (voir {@code
 *       quests/crystal_hunt.yml}), effacée par {@code /rpgadmin player resetnew} ; <em>ou</em></li>
 *   <li>le joueur possède déjà un claim principal ({@link ClaimService#mainClaimOf}) — il a déjà
 *       prouvé son droit et doit toujours pouvoir retourner chez lui, même si la variable a
 *       divergé.</li>
 * </ul>
 *
 * <p>Un joueur non éligible n'est <strong>pas</strong> téléporté : il reste où il est (au Hub) et
 * reçoit un message qui l'oriente vers le Guide / Jo. Aucune permission de build ou d'admin ne
 * contourne cette règle par accident — seul le bypass explicitement prévu {@code
 * rpgquest.admin.world} (le même que {@link ClaimsWorldRulesListener}) passe outre.</p>
 *
 * <p>La vérité {@code CLAIM_TIER_1} vit en base : {@link #allowEntry} refuse d'abord le passage
 * immédiat (retourne {@code false}), lance la lecture asynchrone, puis — si le joueur est éligible
 * — relance lui-même la téléportation via {@link PortalTeleporter#teleportNow} (qui ne repasse pas
 * par les gardes). Même patron que {@link com.lodygames.rpgquest.travel.WildEntryWarningService}
 * pour son bouton « Continuer ». {@link #pendingChecks} évite de relancer une lecture à chaque
 * {@code PlayerMoveEvent} tant qu'une est déjà en vol ; {@link #cleared} est un laissez-passer à
 * usage unique consommé par le passage relancé.</p>
 */
public final class ClaimWorldAccessGuard implements WorldPortalEntryGuard {

    /** Même nœud que {@link ClaimsWorldRulesListener} : le seul contournement explicitement prévu. */
    static final String BYPASS_PERMISSION = "rpgquest.admin.world";
    private static final long CLEARED_TTL_TICKS = 200L; // 10 s pour franchir le portail après un contrôle réussi.
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final Supplier<ClaimConfig> config;
    private final PortalTeleporter teleporter;

    private final Set<UUID> pendingChecks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> cleared = ConcurrentHashMap.newKeySet();

    public ClaimWorldAccessGuard(RPGQuestPlugin plugin, ClaimService claimService, Supplier<ClaimConfig> config,
                                  PortalTeleporter teleporter) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.config = config;
        this.teleporter = teleporter;
    }

    @Override
    public boolean allowEntry(Player player, WorldPortalDefinition portal) {
        if (!portal.destinationWorld().equals(config.get().world())) {
            return true; // pas un portail vers le monde des claims : jamais concerné.
        }
        UUID playerId = player.getUniqueId();
        if (cleared.remove(playerId)) {
            return true; // contrôle réussi tout juste effectué : ce passage-ci est le nôtre.
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return true; // bypass explicitement prévu (admin/build de monde).
        }
        if (claimService.mainClaimOf(playerId).isPresent()) {
            return true; // possède déjà un claim : retour chez lui toujours autorisé.
        }
        if (!pendingChecks.add(playerId)) {
            return false; // une lecture est déjà en vol : ne pas la relancer, ne pas spammer.
        }
        claimService.hasClaimTierOne(playerId).whenComplete((unlocked, error) -> runOnMainThread(() -> {
            pendingChecks.remove(playerId);
            if (error != null) {
                plugin.getSLF4JLogger().error("Contrôle d'accès au monde des claims impossible pour {}", playerId, error);
                if (player.isOnline()) {
                    player.sendMessage(MM.deserialize(
                            "<red>Impossible de vérifier ton accès au monde des claims, contacte un administrateur.</red>"));
                }
                return;
            }
            if (!player.isOnline()) {
                return;
            }
            if (Boolean.TRUE.equals(unlocked) || claimService.mainClaimOf(playerId).isPresent()) {
                cleared.add(playerId);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> cleared.remove(playerId), CLEARED_TTL_TICKS);
                teleporter.teleportNow(player, portal);
            } else {
                refuse(player);
            }
        }));
        return false;
    }

    private void refuse(Player player) {
        player.sendMessage(MM.deserialize(
                "<red>Le monde des claims est réservé aux joueurs qui ont débloqué leur premier terrain.</red>"));
        player.sendMessage(MM.deserialize(
                "<gray>Termine d'abord l'histoire principale du village, puis parle à <white>Jo</white> "
                        + "pour obtenir ton acte de propriété. Le <white>Guide</white> peut t'indiquer la marche à suivre.</gray>"));
    }

    private void runOnMainThread(Runnable task) {
        // Toujours re-planifié sur un tick (jamais d'exécution inline même déjà sur le thread
        // principal) — même patron que ClaimService/DeedClaimListener, comportement déterministe.
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
