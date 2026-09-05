package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimActionOutcome;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Charge tous les claims en mémoire au démarrage (comme {@code
 * ResourceNodeService}, chargement asynchrone puis application sur le
 * thread principal) et les indexe par monde ({@link #claimsInWorld}) pour
 * que la vérification de claim à chaque événement protégé reste bon
 * marché — même patron que {@code ZoneRegistry#zonesInWorld}/{@code
 * YamlPortalRegistry#portalsInWorld}. Toute mutation réussie (création,
 * suppression, confiance, permission) recharge intégralement depuis la
 * base plutôt que de corriger le cache en mémoire à la main — plus simple
 * et sans risque de désynchronisation, les claims ne changeant pas à une
 * fréquence justifiant une optimisation plus fine.
 *
 * <p>Toute la validation métier (chevauchement, taille, nombre, distance
 * aux portails, safe zone) vit ici plutôt que dans {@code ClaimRepository}
 * (pure JDBC) — c'est ce qui crée la dépendance de {@code claim} vers
 * {@code zone}/{@code travel}, délibérée et documentée dans {@code
 * docs/CLAIMS.md}.</p>
 */
public final class ClaimService implements PluginService {

    private static final int BONUS_CLAIM_LEVEL_INTERVAL = 10;

    /**
     * Variable joueur (voir {@code quest.model.VariableReward}/{@code
     * dialogue.model.VariableEqualsCondition}) débloquant le droit de créer un <strong>premier</strong>
     * claim (mission « premier claim 5×5 débloqué par une Story ») — jamais vérifiée pour un 2e/3e
     * claim, seulement quand {@link #claimsOwnedBy} est encore vide au moment de l'appel. Valeur
     * attendue : la chaîne exacte {@link #CLAIM_TIER_1_VALUE}, jamais un booléen SQLite (voir {@code
     * PlayerVariableRepository}, colonne texte).
     */
    public static final String CLAIM_TIER_1_KEY = "CLAIM_TIER_1";
    public static final String CLAIM_TIER_1_VALUE = "true";

    private final RPGQuestPlugin plugin;
    private final ClaimRepository claimRepository;
    private final ZoneRegistry zoneRegistry;
    private final YamlPortalRegistry portalRegistry;
    private final ConfigService configService;
    private final ProgressionService progressionService;
    private final PlayerVariableRepository variableRepository;
    private final Logger logger;

    private volatile List<Claim> claims = List.of();
    private volatile Map<String, List<Claim>> byWorld = Map.of();

    public ClaimService(RPGQuestPlugin plugin, ClaimRepository claimRepository, ZoneRegistry zoneRegistry,
                         YamlPortalRegistry portalRegistry, ConfigService configService, ProgressionService progressionService,
                         PlayerVariableRepository variableRepository) {
        this.plugin = plugin;
        this.claimRepository = claimRepository;
        this.zoneRegistry = zoneRegistry;
        this.portalRegistry = portalRegistry;
        this.configService = configService;
        this.progressionService = progressionService;
        this.variableRepository = variableRepository;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        claimRepository.allClaims().thenAccept(list -> runOnMainThread(() -> applyLoaded(list)))
                .exceptionally(error -> {
                    logger.error("Impossible de charger les claims depuis la base.", error);
                    return null;
                });
    }

    @Override
    public void stop() {
        claims = List.of();
        byWorld = Map.of();
    }

    public List<Claim> claims() {
        return claims;
    }

    public List<Claim> claimsInWorld(String world) {
        return byWorld.getOrDefault(world, List.of());
    }

    public List<Claim> claimsOwnedBy(UUID owner) {
        return claims.stream().filter(claim -> claim.owner().equals(owner)).toList();
    }

    /**
     * Le claim "principal" actuel de {@code owner} (mission « Jo : retourner à son claim ») —
     * unique point d'accroche pour une future sélection multi-claims : aujourd'hui résolu simplement
     * comme le premier (et unique) claim possédé, car un joueur ne peut obtenir un claim principal
     * que par ce chemin (voir {@code DeedClaimListener}, id toujours {@code "main_" + owner}).
     * Lorsque plusieurs claims deviendront possibles, seule cette méthode devra changer (ex.
     * variable joueur "claim principal désigné") — {@code ClaimTeleportService} et les appelants ne
     * dépendent jamais de l'id ni du nombre de claims directement.
     */
    public Optional<Claim> mainClaimOf(UUID owner) {
        return claimsOwnedBy(owner).stream().findFirst();
    }

    public Optional<Claim> find(String id) {
        return claims.stream().filter(claim -> claim.id().equals(id)).findFirst();
    }

    /** Le premier claim (dans l'ordre chargé) dont le cuboïde contient cette position, s'il y en a un. */
    public Optional<Claim> claimAt(String world, int x, int y, int z) {
        for (Claim claim : claimsInWorld(world)) {
            if (claim.contains(world, x, y, z)) {
                return Optional.of(claim);
            }
        }
        return Optional.empty();
    }

    /**
     * Seams pour une politique liée à la progression du joueur (mission
     * étape 17, point 8), effectivement branchées sur {@link
     * ProgressionService} depuis l'étape 19 (mission point 11, « hooks de
     * déblocage : ... claim »). Largeur/hauteur restent aujourd'hui
     * uniquement la valeur de {@code config.yml}, identique pour tous les
     * joueurs — étendre la surface au-delà d'une simple limite de nombre
     * change la géométrie de claims déjà posés, hors de portée d'un hook de
     * démonstration. Voir docs/CLAIMS.md et docs/PROGRESSION.md.
     */
    public int effectiveMaxWidth(Player player) {
        return configService.current().claims().maxWidth();
    }

    public int effectiveMaxHeight(Player player) {
        return configService.current().claims().maxHeight();
    }

    /** +1 claim tous les {@value #BONUS_CLAIM_LEVEL_INTERVAL} niveaux de la piste {@code GLOBAL}. */
    public int effectiveMaxClaims(Player player) {
        int base = configService.current().claims().maxClaimsPerPlayer();
        int globalLevel = progressionService.level(player.getUniqueId(), SkillType.GLOBAL);
        int bonus = globalLevel / BONUS_CLAIM_LEVEL_INTERVAL;
        return base + bonus;
    }

    public enum CreateOutcome {
        CREATED, INVALID_ID, DIFFERENT_WORLDS, DUPLICATE_ID, TOO_LARGE, TOO_MANY_CLAIMS,
        OVERLAPS_CLAIM, OVERLAPS_RESERVATION, OVERLAPS_PROTECTED_ZONE, TOO_CLOSE_TO_PORTAL, FORBIDDEN_WORLD,
        MISSING_PREREQUISITE
    }

    /** Équivalent de {@link #create(Player, String, Location, Location, Location, Location)} sans réservation supplémentaire (réservation = cuboïde actif — voir {@link Claim#Claim(String, UUID, String, int, int, int, int, int, int, Set, ClaimFlags)}). */
    public CompletableFuture<CreateOutcome> create(Player owner, String id, Location pos1, Location pos2) {
        return create(owner, id, pos1, pos2, pos1, pos2);
    }

    /**
     * {@code reservedPos1}/{@code reservedPos2} doivent englober {@code activePos1}/{@code
     * activePos2} (jamais l'inverse) — voir {@link Claim} pour la validation exacte. Le prérequis
     * {@link #CLAIM_TIER_1_KEY} n'est vérifié que pour le <strong>premier</strong> claim d'un joueur
     * ({@link #claimsOwnedBy} encore vide au moment de l'appel) : un joueur qui possède déjà un claim
     * a déjà prouvé son droit, un 2e/3e claim (jusqu'à {@link #effectiveMaxClaims}) n'est jamais
     * bloqué par ce prérequis.
     */
    public CompletableFuture<CreateOutcome> create(Player owner, String id, Location activePos1, Location activePos2,
                                                    Location reservedPos1, Location reservedPos2) {
        if (activePos1.getWorld() == null || activePos2.getWorld() == null
                || !activePos1.getWorld().equals(activePos2.getWorld())
                || reservedPos1.getWorld() == null || reservedPos2.getWorld() == null
                || !reservedPos1.getWorld().equals(activePos1.getWorld()) || !reservedPos2.getWorld().equals(activePos1.getWorld())) {
            return CompletableFuture.completedFuture(CreateOutcome.DIFFERENT_WORLDS);
        }
        String world = activePos1.getWorld().getName();
        if (world.equals(configService.current().hub().world())) {
            return CompletableFuture.completedFuture(CreateOutcome.FORBIDDEN_WORLD);
        }
        int minX = Math.min(activePos1.getBlockX(), activePos2.getBlockX());
        int minY = Math.min(activePos1.getBlockY(), activePos2.getBlockY());
        int minZ = Math.min(activePos1.getBlockZ(), activePos2.getBlockZ());
        int maxX = Math.max(activePos1.getBlockX(), activePos2.getBlockX());
        int maxY = Math.max(activePos1.getBlockY(), activePos2.getBlockY());
        int maxZ = Math.max(activePos1.getBlockZ(), activePos2.getBlockZ());
        int reservedMinX = Math.min(reservedPos1.getBlockX(), reservedPos2.getBlockX());
        int reservedMinY = Math.min(reservedPos1.getBlockY(), reservedPos2.getBlockY());
        int reservedMinZ = Math.min(reservedPos1.getBlockZ(), reservedPos2.getBlockZ());
        int reservedMaxX = Math.max(reservedPos1.getBlockX(), reservedPos2.getBlockX());
        int reservedMaxY = Math.max(reservedPos1.getBlockY(), reservedPos2.getBlockY());
        int reservedMaxZ = Math.max(reservedPos1.getBlockZ(), reservedPos2.getBlockZ());

        Claim candidate;
        try {
            candidate = new Claim(id, owner.getUniqueId(), world, minX, minY, minZ, maxX, maxY, maxZ,
                    reservedMinX, reservedMinY, reservedMinZ, reservedMaxX, reservedMaxY, reservedMaxZ,
                    Set.of(), ClaimFlags.defaults());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(CreateOutcome.INVALID_ID);
        }

        if (find(id).isPresent()) {
            return CompletableFuture.completedFuture(CreateOutcome.DUPLICATE_ID);
        }
        if (candidate.width() > effectiveMaxWidth(owner) || candidate.depth() > effectiveMaxWidth(owner)
                || candidate.height() > effectiveMaxHeight(owner)) {
            return CompletableFuture.completedFuture(CreateOutcome.TOO_LARGE);
        }
        if (claimsOwnedBy(owner.getUniqueId()).size() >= effectiveMaxClaims(owner)) {
            return CompletableFuture.completedFuture(CreateOutcome.TOO_MANY_CLAIMS);
        }
        for (Claim existing : claimsInWorld(world)) {
            if (candidate.overlaps(existing)) {
                return CompletableFuture.completedFuture(CreateOutcome.OVERLAPS_CLAIM);
            }
            if (candidate.overlapsReservation(existing)) {
                return CompletableFuture.completedFuture(CreateOutcome.OVERLAPS_RESERVATION);
            }
        }
        for (ZoneDefinition zone : zoneRegistry.zonesInWorld(world)) {
            if (overlapsBounds(world, minX, minY, minZ, maxX, maxY, maxZ,
                    zone.world(), zone.minX(), zone.minY(), zone.minZ(), zone.maxX(), zone.maxY(), zone.maxZ())) {
                return CompletableFuture.completedFuture(CreateOutcome.OVERLAPS_PROTECTED_ZONE);
            }
        }
        int buffer = configService.current().claims().portalBufferBlocks();
        for (PortalDefinition portal : portalRegistry.portalsInWorld(world)) {
            if (overlapsBounds(world, minX - buffer, minY - buffer, minZ - buffer, maxX + buffer, maxY + buffer, maxZ + buffer,
                    portal.world(), portal.minX(), portal.minY(), portal.minZ(), portal.maxX(), portal.maxY(), portal.maxZ())) {
                return CompletableFuture.completedFuture(CreateOutcome.TOO_CLOSE_TO_PORTAL);
            }
        }

        boolean isFirstClaim = claimsOwnedBy(owner.getUniqueId()).isEmpty();
        CompletableFuture<Boolean> prerequisiteCheck = isFirstClaim
                ? hasClaimTierOne(owner.getUniqueId())
                : CompletableFuture.completedFuture(true);

        return prerequisiteCheck.thenCompose(hasPrerequisite -> {
            if (!hasPrerequisite) {
                return CompletableFuture.completedFuture(CreateOutcome.MISSING_PREREQUISITE);
            }
            return claimRepository.create(candidate).thenCompose(success -> {
                if (!success) {
                    return CompletableFuture.completedFuture(CreateOutcome.DUPLICATE_ID);
                }
                return claimRepository.allClaims().thenApply(list -> {
                    runOnMainThread(() -> applyLoaded(list));
                    return CreateOutcome.CREATED;
                });
            });
        });
    }

    /** {@code true} si {@code playerId} possède la variable {@link #CLAIM_TIER_1_KEY} = {@link #CLAIM_TIER_1_VALUE}. */
    public CompletableFuture<Boolean> hasClaimTierOne(UUID playerId) {
        return variableRepository.get(playerId, CLAIM_TIER_1_KEY)
                .thenApply(opt -> opt.map(CLAIM_TIER_1_VALUE::equals).orElse(false));
    }

    /**
     * Outil ADMIN/DEBUG ciblé (mission « reset pour retester le scénario ») : supprime tous les
     * claims de {@code target} et remet {@link #CLAIM_TIER_1_KEY} à {@code "false"} — permet de
     * rejouer Story → CLAIM_TIER_1 → Acte de propriété → claim depuis zéro. Ne touche à aucune autre
     * quête, variable, claim ou joueur (voir {@code command.ClaimCommand#handleAdminResetTierOne}).
     */
    public CompletableFuture<Void> resetTierOneClaimForTesting(UUID target) {
        List<CompletableFuture<ClaimActionOutcome>> deletions = claimsOwnedBy(target).stream()
                .map(claim -> claimRepository.delete(claim.id(), target))
                .toList();
        return CompletableFuture.allOf(deletions.toArray(CompletableFuture[]::new))
                .thenCompose(v -> claimRepository.allClaims())
                .thenCompose(list -> {
                    runOnMainThread(() -> applyLoaded(list));
                    return variableRepository.set(target, CLAIM_TIER_1_KEY, "false");
                });
    }

    public CompletableFuture<ClaimActionOutcome> delete(UUID requester, String id) {
        return refreshIfSuccess(claimRepository.delete(id, requester));
    }

    public CompletableFuture<ClaimActionOutcome> trust(UUID requester, String id, UUID member) {
        return refreshIfSuccess(claimRepository.addMember(id, requester, member));
    }

    public CompletableFuture<ClaimActionOutcome> untrust(UUID requester, String id, UUID member) {
        return refreshIfSuccess(claimRepository.removeMember(id, requester, member));
    }

    public CompletableFuture<ClaimActionOutcome> setAllowPublicRedstone(UUID requester, String id, boolean value) {
        return refreshIfSuccess(claimRepository.setAllowPublicRedstone(id, requester, value));
    }

    private CompletableFuture<ClaimActionOutcome> refreshIfSuccess(CompletableFuture<ClaimActionOutcome> action) {
        return action.thenCompose(outcome -> {
            if (outcome != ClaimActionOutcome.SUCCESS) {
                return CompletableFuture.completedFuture(outcome);
            }
            return claimRepository.allClaims().thenApply(list -> {
                runOnMainThread(() -> applyLoaded(list));
                return outcome;
            });
        });
    }

    private void applyLoaded(List<Claim> loaded) {
        this.claims = List.copyOf(loaded);
        Map<String, List<Claim>> index = new HashMap<>();
        for (Claim claim : loaded) {
            index.computeIfAbsent(claim.world(), w -> new ArrayList<>()).add(claim);
        }
        this.byWorld = Map.copyOf(index);
    }

    private static boolean overlapsBounds(String worldA, int aMinX, int aMinY, int aMinZ, int aMaxX, int aMaxY, int aMaxZ,
                                           String worldB, int bMinX, int bMinY, int bMinZ, int bMaxX, int bMaxY, int bMaxZ) {
        if (!worldA.equals(worldB)) {
            return false;
        }
        return aMinX <= bMaxX && aMaxX >= bMinX
                && aMinY <= bMaxY && aMaxY >= bMinY
                && aMinZ <= bMaxZ && aMaxZ >= bMinZ;
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
