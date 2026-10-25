package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Issue #637 — téléportation <strong>de diagnostic</strong> d'un administrateur vers un claim existant,
 * demandée depuis PlugAdmin ({@code claim.teleport}). Même destination sûre que {@code /claim admin
 * tp} et le portail CLAIM_HOME ({@link ClaimTeleportService#findSafeArrivalInClaim}) : centre du
 * claim, sinon une autre colonne sûre <em>du claim</em>, jamais au-delà.
 *
 * <h2>Ce que la téléportation ne fait pas</h2>
 *
 * <p>Elle ne touche ni au claim (propriétaire, membres, bornes, drapeaux), ni au terrain, ni aux
 * variables du joueur (aucun {@code CLAIM_TIER_1} écrit, #321), ni au mode bypass (#35, jamais
 * activé). Être téléporté sur un claim ne donne <strong>aucun</strong> droit d'y construire : la
 * protection reste appliquée par {@link ClaimProtectionListener}.</p>
 *
 * <p>Issue #829, décision D2 : vers le claim d'un autre joueur, le mode bypass explicite (#35) est
 * exigé, comme pour {@code /claim admin tp} — sinon {@link Code#REFUSED} avant tout déplacement.</p>
 *
 * <h2>Pas de faux succès</h2>
 *
 * <p>Le monde des claims renvoie au Hub tout joueur non éligible ({@link ClaimWorldSafetyListener}).
 * Plutôt que d'annoncer un succès suivi d'un renvoi, l'éligibilité est contrôlée
 * <strong>avant</strong> (mêmes règles que ce filet), puis la position réelle est
 * <strong>relue</strong> après la téléportation : seul un joueur effectivement présent sur le claim
 * donne {@link Code#TELEPORTED}. Une seule téléportation à la fois par administrateur (double clic).</p>
 *
 * <p>À appeler sur le thread principal.</p>
 */
public final class ClaimAdminTeleport {

    /** Délai de relecture après l'arrivée : laisse le filet du monde des claims s'appliquer d'abord. */
    static final long VERIFY_DELAY_TICKS = 10L;

    public enum Code {
        TELEPORTED, UNKNOWN_CLAIM, CLAIM_CHANGED, OFFLINE, WORLD_UNAVAILABLE, NOT_ELIGIBLE, NO_SAFE_LOCATION,
        REFUSED, BOUNCED, BUSY
    }

    /** @param destination position d'arrivée relue ({@code null} sauf succès) */
    public record Result(Code code, String message, Location destination) {

        public boolean ok() {
            return code == Code.TELEPORTED;
        }

        static Result of(Code code, String message) {
            return new Result(code, message, null);
        }
    }

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final Supplier<ClaimConfig> config;
    private final AdminBypassMode bypass;
    /** Le déplacement lui-même : {@link Player#teleportAsync} en jeu, substituable en test. */
    private final BiFunction<Player, Location, CompletableFuture<Boolean>> mover;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public ClaimAdminTeleport(RPGQuestPlugin plugin, ClaimService claimService, Supplier<ClaimConfig> config,
                              AdminBypassMode bypass) {
        this(plugin, claimService, config, bypass, Player::teleportAsync);
    }

    ClaimAdminTeleport(RPGQuestPlugin plugin, ClaimService claimService, Supplier<ClaimConfig> config,
                       AdminBypassMode bypass, BiFunction<Player, Location, CompletableFuture<Boolean>> mover) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.config = config;
        this.bypass = bypass;
        this.mover = mover;
    }

    /**
     * @param travelerId    le personnage à déplacer — toujours celui de l'administrateur, jamais le
     *                      propriétaire par défaut (choisi par PlugAdmin)
     * @param expectedOwner propriétaire affiché à l'administrateur ; refus si le claim a changé de
     *                      main depuis ({@code null} = pas de contrôle)
     */
    public CompletableFuture<Result> teleport(UUID travelerId, String claimId, UUID expectedOwner) {
        Optional<Claim> found = claimService.find(claimId);
        if (found.isEmpty()) {
            return done(Result.of(Code.UNKNOWN_CLAIM, "Claim « " + claimId + " » introuvable : il a peut-être été "
                    + "supprimé. Rafraîchir la liste des claims."));
        }
        Claim claim = found.get();
        if (expectedOwner != null && !expectedOwner.equals(claim.owner())) {
            return done(Result.of(Code.CLAIM_CHANGED, "Le claim « " + claimId + " » n'appartient plus au joueur "
                    + "affiché : rafraîchir la liste des claims avant de réessayer."));
        }
        Player traveler = plugin.getServer().getPlayer(travelerId);
        if (traveler == null) {
            return done(Result.of(Code.OFFLINE, "Votre personnage n'est pas connecté au serveur : connectez-vous "
                    + "en jeu, puis relancez la téléportation."));
        }
        World world = plugin.getServer().getWorld(claim.world());
        if (world == null) {
            return done(Result.of(Code.WORLD_UNAVAILABLE, "Le monde « " + claim.world() + " » de ce claim n'est "
                    + "pas chargé : téléportation impossible."));
        }
        if (!inFlight.add(travelerId)) {
            return done(Result.of(Code.BUSY, "Une téléportation de votre personnage est déjà en cours : rien n'a "
                    + "été envoyé en double."));
        }
        CompletableFuture<Result> out = new CompletableFuture<>();
        out.whenComplete((r, e) -> inFlight.remove(travelerId));
        plugin.getSLF4JLogger().info("[claim-admin-tp] demande : {} vers le claim {} de {} ({}).",
                traveler.getName(), claim.id(), claim.owner(), claim.world());
        eligible(traveler, claim).whenComplete((ok, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                out.complete(Result.of(Code.REFUSED, "Contrôle d'accès au monde des claims impossible : "
                        + "téléportation annulée."));
            } else if (!Boolean.TRUE.equals(ok)) {
                out.complete(Result.of(Code.NOT_ELIGIBLE, "Votre personnage serait aussitôt renvoyé au Hub : le monde "
                        + "des claims exige d'avoir débloqué son premier terrain (CLAIM_TIER_1) ou d'y posséder un "
                        + "claim. Activez le mode administrateur en jeu (/rpgadmin bypass on) puis réessayez. "
                        + "Rien n'a été modifié."));
            } else if (!ClaimVisitPolicy.MEMBERS_ONLY.allowsDirectTeleport(travelerId, claim)
                    && !bypass.allows(traveler, RpgPermissions.BYPASS_CLAIM_WORLD)) {
                // Issue #829, décision D2 : même règle que ClaimWorldSafetyListener, annoncée avant d'essayer.
                out.complete(Result.of(Code.REFUSED, "Téléportation directe dans le claim d'un autre joueur refusée "
                        + "sans mode administrateur : activez-le en jeu (/rpgadmin bypass on) puis réessayez. Rien "
                        + "n'a été modifié."));
            } else {
                move(travelerId, claim, out);
            }
        }));
        return out.whenComplete((r, e) -> {
            if (r != null) {
                plugin.getSLF4JLogger().info("[claim-admin-tp] résultat : {} vers le claim {} — {}.",
                        traveler.getName(), claim.id(), r.code());
            }
        });
    }

    /** Mêmes règles que {@link ClaimWorldSafetyListener} : hors du monde des claims, rien à contrôler. */
    private CompletableFuture<Boolean> eligible(Player traveler, Claim claim) {
        if (!claim.world().equals(config.get().world())
                || bypass.allows(traveler, RpgPermissions.BYPASS_CLAIM_WORLD)
                || claimService.mainClaimOf(traveler.getUniqueId()).isPresent()) {
            return done(true);
        }
        return claimService.hasClaimTierOne(traveler.getUniqueId());
    }

    private void move(UUID travelerId, Claim claim, CompletableFuture<Result> out) {
        Player traveler = plugin.getServer().getPlayer(travelerId);
        World world = plugin.getServer().getWorld(claim.world());
        if (traveler == null || world == null) {
            out.complete(Result.of(traveler == null ? Code.OFFLINE : Code.WORLD_UNAVAILABLE,
                    "Le personnage ou le monde du claim a disparu pendant le contrôle : téléportation annulée."));
            return;
        }
        Optional<Location> safe = ClaimTeleportService.findSafeArrivalInClaim(world, claim);
        if (safe.isEmpty()) {
            out.complete(Result.of(Code.NO_SAFE_LOCATION, "Aucune position sûre (sol solide, deux blocs d'air, ni "
                    + "lave ni vide) dans le claim « " + claim.id() + " » : téléportation refusée, terrain inchangé."));
            return;
        }
        mover.apply(traveler, safe.get()).whenComplete((moved, error) -> plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> out.complete(verify(travelerId, claim, moved, error)), VERIFY_DELAY_TICKS));
    }

    /** Relecture : un succès n'est annoncé que si le personnage se trouve réellement sur le claim. */
    private Result verify(UUID travelerId, Claim claim, Boolean moved, Throwable error) {
        if (error != null || !Boolean.TRUE.equals(moved)) {
            return Result.of(Code.REFUSED, "Le serveur a refusé la téléportation (annulée par une autre règle) : "
                    + "votre personnage n'a pas bougé.");
        }
        Player traveler = plugin.getServer().getPlayer(travelerId);
        if (traveler == null) {
            return Result.of(Code.OFFLINE, "Votre personnage s'est déconnecté pendant la téléportation.");
        }
        Location at = traveler.getLocation();
        boolean onClaim = at.getWorld() != null && at.getWorld().getName().equals(claim.world())
                && at.getBlockX() >= claim.minX() && at.getBlockX() <= claim.maxX()
                && at.getBlockZ() >= claim.minZ() && at.getBlockZ() <= claim.maxZ();
        if (!onClaim) {
            return Result.of(Code.BOUNCED, "Votre personnage a été déplacé ailleurs juste après l'arrivée (filet "
                    + "de sécurité du monde des claims ?) : il n'est pas sur le claim.");
        }
        return new Result(Code.TELEPORTED, "Votre personnage est sur le claim « " + claim.id() + " » en "
                + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ".", at.clone());
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
