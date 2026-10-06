package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.travel.PortalTeleporter;
import com.lodygames.rpgquest.travel.WorldPortalEntryGuard;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.permission.RpgPermissions;
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
 * <p><strong>Deuxième condition, issue #22 :</strong> même éligible, on n'entre pas sans le moyen
 * d'en repartir. Avant toute téléportation, une <em>Pierre de retour</em> est garantie
 * ({@link ClaimReturnService}) et la destination de retour doit se résoudre. Si l'une des deux
 * manque — inventaire plein, ou spawn du village non résolu — l'entrée est refusée avec la raison
 * exacte, plutôt que de laisser le joueur découvrir le piège une fois sur place.</p>
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
    private final ClaimReturnService returnService;
    private final Supplier<ClaimConfig> config;
    private final PortalTeleporter teleporter;

    private final Set<UUID> pendingChecks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> cleared = ConcurrentHashMap.newKeySet();

    public ClaimWorldAccessGuard(RPGQuestPlugin plugin, ClaimService claimService,
                                  ClaimReturnService returnService, Supplier<ClaimConfig> config,
                                  PortalTeleporter teleporter) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.returnService = returnService;
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
        // Issue #200 : nœud dédié OU ombrelle historique. Le correctif #22 est intégralement
        // préservé — le bypass n'est jamais refusé, et il ne dispense jamais du moyen de repartir.
        if (RpgPermissions.hasBypass(player, RpgPermissions.BYPASS_CLAIM_WORLD)) {
            // Trace explicite (issues #21/#22) : un test « en jeu » d'un compte OP passe par ici et
            // *contourne* volontairement le contrôle — c'est la cause la plus fréquente d'un « je
            // peux entrer sans avoir débloqué le claim ». Le parcours d'un vrai nouveau joueur doit
            // être validé avec un compte NON opéré.
            plugin.getSLF4JLogger().info(
                    "[claims-access] {} : entrée dans « {} » via le portail {} AUTORISÉE par le bypass {} "
                            + "(compte OP ou permission explicite) — contrôle CLAIM_TIER_1 non appliqué.",
                    player.getName(), portal.destinationWorld(), portal.id(), BYPASS_PERMISSION);
            // Le bypass n'est jamais refusé — mais il ne dispense pas du moyen de repartir
            // (issue #22) : la Pierre de retour est garantie au mieux, sans bloquer le passage.
            ensureReturn(player, false);
            return true; // bypass explicitement prévu (admin/build de monde).
        }
        if (claimService.mainClaimOf(playerId).isPresent()) {
            if (!ensureReturn(player, true)) {
                return false; // sans retour possible, entrer serait un piège (issue #22).
            }
            plugin.getSLF4JLogger().info(
                    "[claims-access] {} : entrée dans « {} » autorisée (possède déjà un claim principal).",
                    player.getName(), portal.destinationWorld());
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
                if (!ensureReturn(player, true)) {
                    return; // éligible, mais sans retour possible : aucune téléportation (issue #22).
                }
                plugin.getSLF4JLogger().info(
                        "[claims-access] {} : entrée dans « {} » autorisée (CLAIM_TIER_1 débloqué) — téléportation relancée.",
                        player.getName(), portal.destinationWorld());
                cleared.add(playerId);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> cleared.remove(playerId), CLEARED_TTL_TICKS);
                teleporter.teleportNow(player, portal);
            } else {
                plugin.getSLF4JLogger().info(
                        "[claims-access] {} : entrée dans « {} » REFUSÉE (CLAIM_TIER_1 non débloqué, aucun claim) — aucune téléportation.",
                        player.getName(), portal.destinationWorld());
                refuse(player);
            }
        }));
        return false;
    }

    /**
     * Règle <strong>préventive</strong> d'issue #22 : on n'entre pas dans le monde des claims sans
     * le moyen d'en repartir. Le blocage signalé le 05/10 est né de l'inverse — l'entrée était
     * autorisée, et la Pierre de retour n'arrivait jamais. Le filet d'arrivée
     * ({@link ClaimWorldSafetyListener}) reste utile pour les autres façons d'arriver, mais il
     * agit trop tard : ici, rien n'est encore irréversible.
     *
     * <p>Deux raisons de refuser, et aucune autre :</p>
     * <ul>
     *   <li>la destination de retour ne se résout pas — la Pierre de retour ne mènerait nulle
     *       part, donc entrer est un aller simple ;</li>
     *   <li>le joueur n'en détient pas et son inventaire est plein — l'objet déposé au Hub, au
     *       point de départ, ne garantirait rien.</li>
     * </ul>
     *
     * @param enforce {@code false} pour un porteur du bypass : on garantit au mieux, on ne refuse
     *     jamais son passage — il est venu volontairement et dispose des moyens d'un
     *     administrateur.
     * @return {@code true} si l'entrée peut se poursuivre.
     */
    private boolean ensureReturn(Player player, boolean enforce) {
        if (!returnService.destinationResolvable()) {
            plugin.getSLF4JLogger().warn(
                    "[claims-access] aucune destination de retour au Hub résolue : une Pierre de retour ne "
                            + "mènerait nulle part pour {}. Vérifier le spawn du village.", player.getName());
            if (!enforce) {
                return true;
            }
            player.sendMessage(MM.deserialize(
                    "<red>Le retour depuis le monde des claims est indisponible pour le moment.</red>"));
            player.sendMessage(MM.deserialize(
                    "<gray>Tu n'es pas téléporté : tu ne pourrais pas revenir. Signale-le à un administrateur.</gray>"));
            return false;
        }
        ClaimReturnService.Outcome outcome = returnService.ensureReturnStone(player, false);
        if (outcome.canReturn()) {
            return true;
        }
        if (!enforce) {
            plugin.getSLF4JLogger().warn(
                    "[claims-access] {} entre dans le monde des claims via le bypass {} SANS Pierre de retour "
                            + "({}) — le filet d'arrivée réessaiera.",
                    player.getName(), BYPASS_PERMISSION, outcome);
            return true;
        }
        plugin.getSLF4JLogger().info(
                "[claims-access] {} : entrée REFUSÉE faute de moyen de retour ({}).", player.getName(), outcome);
        if (outcome == ClaimReturnService.Outcome.NO_ROOM) {
            player.sendMessage(MM.deserialize(
                    "<red>Ton inventaire est plein : impossible de te remettre ta Pierre de retour.</red>"));
            player.sendMessage(MM.deserialize(
                    "<gray>Libère un emplacement avant d'entrer — sans elle, tu resterais coincé dans le monde "
                            + "des claims.</gray>"));
        } else {
            player.sendMessage(MM.deserialize(
                    "<red>Le retour depuis le monde des claims est indisponible pour le moment.</red>"));
            player.sendMessage(MM.deserialize(
                    "<gray>Tu n'es pas téléporté : tu ne pourrais pas revenir. Signale-le à un administrateur.</gray>"));
        }
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
