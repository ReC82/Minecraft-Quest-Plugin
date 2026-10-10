package com.lodygames.rpgquest.travel.beacon;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.database.TravelBeaconRepository;
import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.travel.beacon.model.TravelBeacon;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import com.lodygames.rpgquest.waypoint.WaypointGenerationPlanner;
import com.lodygames.rpgquest.waypoint.WaypointIdentityResolver;
import com.lodygames.rpgquest.waypoint.WaypointService;
import com.lodygames.rpgquest.waypoint.model.BiomeInstanceKey;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Réseau de voyage (issues #132/#150) : borne physique (même support qu'un waypoint, bloc de
 * diamant + bouton en bois) ouvrant un menu graphique paginé vers les waypoints déjà découverts
 * par le joueur. Système <strong>strictement distinct</strong> de {@code waypoint}/{@code
 * waystone} : une borne n'est jamais une destination, et cette classe ne fait jamais la moindre
 * fusion d'identité/table avec ces systèmes — elle se contente de <em>lire</em>
 * {@link WaypointService#discoveredBy}/{@link WaypointService#hasActivelyDiscovered}.
 *
 * <p>Catégories « Mon claim » et « Villages » (issue #151) : « Mon claim » résout le claim courant
 * du joueur via {@link ClaimService#mainClaimOf} (jamais une coordonnée copiée), revalidé à chaque
 * départ ; « Villages » liste des centres administrés ({@link VillageCenter}, table dédiée
 * {@code village_centers}, identité indépendante du monde — plusieurs centres peuvent coexister
 * dans {@code world_hub}). Aucune des deux catégories n'ajoute de condition de découverte : un
 * claim existant ou un centre actif est toujours proposé tel quel.</p>
 *
 * <p><strong>Génération automatique Hub (issue #149)</strong> : à l'entrée d'un joueur dans une
 * instance de biome du Hub ({@code hub.world}) sans waypoint, {@link #handleHubMovement} demande
 * à {@link WaypointService#ensureGenerated} de le générer (même mécanisme exact que le Wild,
 * #124 — réutilisé, jamais réécrit), puis appaire une borne distincte à proximité
 * ({@link #ensureBeaconPaired}) une fois ce waypoint réellement présent. Jamais dans le Wild, où
 * seul {@link #placeAt(Player, Location)} (placement administré, {@code /rpgadmin travel beacon
 * set}) existe. Les deux structures restent protégées, versionnées et strictement séparées des
 * waypoints en base ({@code biome_instance} sur {@code travel_beacons} ne sert qu'à l'idempotence
 * de l'appariement, jamais une fusion de table).</p>
 */
public final class TravelBeaconService implements PluginService {

    static final int VERSION = 1;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 45;
    /** Même paliers que {@code waypoint.WaypointService} : réessai borné, jamais de boucle de scan coûteuse. */
    private static final long[] RETRY_BACKOFF_MILLIS = {30_000L, 120_000L, 600_000L, 3_600_000L};

    private final RPGQuestPlugin plugin;
    private final TravelBeaconRepository repository;
    private final WaypointService waypointService;
    private final ClaimService claimService;
    private final VillageCenterRepository villageCenterRepository;
    private final Supplier<TravelConfig> travelConfig;
    private final Supplier<String> hubWorld;
    private final WaypointIdentityResolver identityResolver = new WaypointIdentityResolver();
    private final WaypointGenerationPlanner planner = new WaypointGenerationPlanner();
    private final Logger logger;

    private final ConcurrentHashMap<String, TravelBeacon> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TravelBeacon> byInteractorBlock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TravelBeacon> byBiomeInstance = new ConcurrentHashMap<>();
    private final Set<String> protectedBlocks = ConcurrentHashMap.newKeySet();
    /**
     * État du menu actuellement ouvert, par joueur. <strong>Toujours</strong> {@code put} après
     * {@code player.openInventory(...)}, jamais avant : ouvrir une inventory ferme d'abord
     * l'ancienne de façon <em>synchrone</em>, ce qui déclenche {@link #handleClose} et effacerait
     * aussitôt une session déjà posée (même classe de bug que la navigation du journal de quêtes,
     * issue #11 — ne pas la réintroduire ici).
     */
    private final ConcurrentHashMap<UUID, BeaconMenuSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VillageCenter> villagesById = new ConcurrentHashMap<>();

    // ---- Appariement Hub (issue #149) : verrou mono-vol + réessai borné, par instance de biome.
    private final Set<String> pairing = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> pairRetryNotBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pairRetryCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastHubInstanceByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastHubCheckByPlayer = new ConcurrentHashMap<>();

    public TravelBeaconService(RPGQuestPlugin plugin, TravelBeaconRepository repository, WaypointService waypointService,
                                ClaimService claimService, VillageCenterRepository villageCenterRepository,
                                Supplier<TravelConfig> travelConfig, Supplier<String> hubWorld) {
        this.plugin = plugin;
        this.repository = repository;
        this.waypointService = waypointService;
        this.claimService = claimService;
        this.villageCenterRepository = villageCenterRepository;
        this.travelConfig = travelConfig;
        this.hubWorld = hubWorld;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        repository.loadAll().thenAccept(list -> runSync(() -> {
            for (TravelBeacon beacon : list) {
                index(beacon);
            }
            logger.info("Bornes de voyage chargées : {}.", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les bornes de voyage persistées.", error);
            return null;
        });
        villageCenterRepository.loadAll().thenAccept(list -> runSync(() -> {
            for (VillageCenter center : list) {
                villagesById.put(center.id(), center);
            }
            logger.info("Centres de village chargés : {}.", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les centres de village persistés.", error);
            return null;
        });
    }

    @Override
    public void stop() {
        byId.clear();
        byInteractorBlock.clear();
        byBiomeInstance.clear();
        protectedBlocks.clear();
        sessions.clear();
        villagesById.clear();
        pairing.clear();
        pairRetryNotBefore.clear();
        pairRetryCount.clear();
        lastHubInstanceByPlayer.clear();
        lastHubCheckByPlayer.clear();
    }

    public Listener listener() {
        return new TravelBeaconListener(this);
    }

    public Listener protectionListener(com.lodygames.rpgquest.travel.TravelMaintenanceMode maintenance) {
        return new TravelBeaconProtectionListener(this::isProtectedBlock, maintenance);
    }

    public boolean isProtectedBlock(String world, int x, int y, int z) {
        return protectedBlocks.contains(blockKey(world, x, y, z));
    }

    public List<TravelBeacon> all() {
        return List.copyOf(byId.values());
    }

    // ---- Diagnostic (issue #156) : waypoints Hub sans borne appariée --------------------------

    /**
     * Instances de biome du Hub qui ont déjà un waypoint mais <strong>aucune</strong> borne
     * appariée, à partir des données persistées/indexées — jamais une dépendance à la découverte
     * d'un joueur. Permet de distinguer « jamais essayé », « en attente de réessai » et « jamais
     * généré » sans parcours joueur par commandes.
     */
    public List<Waypoint> hubWaypointsWithoutBeacon() {
        String hub = hubWorld.get();
        if (hub == null || hub.isBlank()) {
            return List.of();
        }
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint waypoint : waypointService.all()) {
            if (!waypoint.world().equals(hub)) {
                continue;
            }
            if (!byBiomeInstance.containsKey(instanceKey(waypoint.world(), waypoint.biomeInstance()))) {
                result.add(waypoint);
            }
        }
        return result;
    }

    /**
     * État de réessai borné (en mémoire, remis à zéro à chaque redémarrage) pour une instance de
     * biome dont l'appariement a déjà échoué au moins une fois — {@link Optional#empty()} si jamais
     * tenté, en cours, ou en attente d'un prochain passage du joueur plutôt qu'un délai écoulé.
     */
    public Optional<Long> pairingRetryRemainingMillis(String world, String biomeInstance) {
        Long notBefore = pairRetryNotBefore.get(instanceKey(world, biomeInstance));
        if (notBefore == null) {
            return Optional.empty();
        }
        return Optional.of(Math.max(0L, notBefore - System.currentTimeMillis()));
    }

    /**
     * Issue #156 — état d'appariement d'une instance Hub encore sans borne. Savoir qu'« il manque des
     * bornes » n'est pas exploitable&nbsp;; la cause l'est : {@code attempts == 0} dénonce un
     * déclencheur qui n'est jamais allé jusqu'à l'appariement, un compteur qui monte dénonce le
     * terrain ou l'espacement, et {@code nearestBeaconDistance} dit si l'espacement minimal peut
     * seulement être en cause ({@code -1} = aucune autre borne dans ce monde).
     *
     * <p>Les compteurs vivent en mémoire : un redémarrage les remet à zéro, ce que l'affichage doit
     * dire plutôt que de laisser croire qu'aucun essai n'a jamais eu lieu.</p>
     */
    public record PairingGap(String waypointId, String biomeInstance, String biomeKey, int x, int z,
                             int attempts, Long nextRetryEpochMs, boolean inProgress,
                             int nearestBeaconDistance) {
    }

    /** Lecture seule, sans aucun accès monde ni base : uniquement les index déjà en mémoire. */
    public List<PairingGap> hubPairingGaps() {
        List<PairingGap> gaps = new ArrayList<>();
        for (Waypoint waypoint : hubWaypointsWithoutBeacon()) {
            String key = instanceKey(waypoint.world(), waypoint.biomeInstance());
            gaps.add(new PairingGap(waypoint.id(), waypoint.biomeInstance(), waypoint.biomeKey(),
                    waypoint.x(), waypoint.z(),
                    pairRetryCount.getOrDefault(key, 0),
                    pairRetryNotBefore.get(key),
                    pairing.contains(key),
                    nearestBeaconDistance(waypoint.world(), waypoint.x(), waypoint.z())));
        }
        return gaps;
    }

    /**
     * Issue #156 — rattrapage <strong>ciblé</strong> : tente d'apparier une borne à l'instance d'un
     * waypoint Hub nommé, et à elle seule.
     *
     * <p>Pourquoi ce point d'entrée existe : l'appariement n'est tenté que lorsqu'un joueur se
     * déplace dans l'instance. Une instance traversée une seule fois reste donc sans borne jusqu'à
     * ce qu'un joueur y revienne — et comme les compteurs d'essai vivent en mémoire, un redémarrage
     * efface jusqu'à la trace du manque. Rien, dans le jeu, ne pouvait combler ce retard.</p>
     *
     * <p>Ce que cette méthode ne fait <strong>pas</strong> : aucun balayage du monde, aucune autre
     * instance touchée, aucune pré-génération de chunk, aucun seuil de densité ou d'espacement
     * modifié. Elle réutilise {@link #attemptPairBeacon} tel quel, si bien que la borne posée est
     * une borne auto-générée ordinaire, appariée à son instance — contrairement à une pose manuelle,
     * qui crée une borne sans instance et laisse donc l'appariement ouvert.</p>
     *
     * <p>Le backoff de réessai est volontairement <strong>ignoré</strong> : la demande est explicite
     * et humaine, elle n'a pas à attendre un délai calculé pour un déclencheur automatique.</p>
     *
     * <p>À appeler sur le thread principal — la recherche d'emplacement lit le monde.</p>
     *
     * @return {@link Optional#empty()} si une borne a été posée, sinon la raison du refus
     */
    public Optional<String> pairHubInstance(String waypointId) {
        String hub = hubWorld.get();
        if (hub == null || hub.isBlank()) {
            return Optional.of("Aucun monde Hub configuré.");
        }
        TravelConfig cfg = travelConfig.get();
        if (!cfg.beacon().hubGenerationEnabled()) {
            return Optional.of("La génération de bornes du Hub est désactivée dans la configuration.");
        }
        Waypoint waypoint = waypointService.byId(waypointId).orElse(null);
        if (waypoint == null) {
            return Optional.of("Waypoint introuvable : " + waypointId);
        }
        if (!waypoint.world().equals(hub)) {
            // Par politique, les bornes n'existent que dans le Hub : un waypoint du Wild n'en
            // attend aucune, et en poser une ici créerait un réseau que rien d'autre ne gère.
            return Optional.of("Ce waypoint est dans « " + waypoint.world() + " », pas dans le Hub « "
                    + hub + " » : les bornes n'existent que dans le Hub.");
        }
        String instanceKey = instanceKey(waypoint.world(), waypoint.biomeInstance());
        if (byBiomeInstance.containsKey(instanceKey)) {
            return Optional.of("Cette instance a déjà une borne appariée : "
                    + byBiomeInstance.get(instanceKey).id());
        }
        World world = plugin.getServer().getWorld(waypoint.world());
        if (world == null) {
            return Optional.of("Monde non chargé : " + waypoint.world());
        }
        if (!pairing.add(instanceKey)) {
            return Optional.of("Un appariement est déjà en cours pour cette instance.");
        }
        BiomeInstanceKey instance = new BiomeInstanceKey(waypoint.world(), waypoint.biomeKey(),
                waypoint.regionX(), waypoint.regionZ());
        try {
            // La demande est explicite : on repart d'un état de réessai propre plutôt que d'être
            // refusé par un backoff destiné au déclencheur automatique.
            pairRetryNotBefore.remove(instanceKey);
            if (attemptPairBeacon(world, instance, instanceKey, waypoint, cfg.beacon())) {
                return Optional.empty();
            }
            return Optional.of("Aucun emplacement accessible trouvé entre "
                    + cfg.beacon().pairMinSpacing() + " et " + cfg.beacon().pairMaxSpacing()
                    + " blocs du waypoint, en respectant l'espacement minimal de "
                    + cfg.waypoint().minimumSpacing() + " blocs entre bornes.");
        } catch (RuntimeException error) {
            pairing.remove(instanceKey);
            logger.error("Échec inattendu de l'appariement ciblé de {}", instanceKey, error);
            return Optional.of("Échec inattendu : " + error.getClass().getSimpleName());
        }
    }

    /** Distance horizontale à la borne la plus proche du même monde, en blocs, ou -1 s'il n'y en a aucune. */
    private int nearestBeaconDistance(String world, int x, int z) {
        long bestSq = Long.MAX_VALUE;
        for (TravelBeacon existing : byId.values()) {
            if (!existing.world().equals(world)) {
                continue;
            }
            long dx = existing.x() - x;
            long dz = existing.z() - z;
            bestSq = Math.min(bestSq, dx * dx + dz * dz);
        }
        return bestSq == Long.MAX_VALUE ? -1 : (int) Math.round(Math.sqrt((double) bestSq));
    }

    // ---- Diagnostic et réparation (issue #153) : bornes inaccessibles -------------------------

    /** Bornes dont l'ancre actuelle échoue désormais le contrôle d'accessibilité (#153). */
    public List<TravelBeacon> inaccessible() {
        List<TravelBeacon> result = new ArrayList<>();
        for (TravelBeacon beacon : byId.values()) {
            World world = plugin.getServer().getWorld(beacon.world());
            if (world == null) {
                continue;
            }
            if (!RandomSafeLocationFinder.isAccessibleGround(world, beacon.x(), beacon.y() - 1, beacon.z())) {
                result.add(beacon);
            }
        }
        return result;
    }

    /**
     * Même principe que {@code WaypointService#repair} : cherche un nouvel emplacement accessible
     * autour de la position actuelle, déplace uniquement les blocs de la structure, conserve
     * {@code id}/{@code biomeInstance}.
     */
    public Optional<String> repairBeacon(String beaconId) {
        TravelBeacon existing = byId.get(beaconId);
        if (existing == null) {
            return Optional.of("Borne introuvable : " + beaconId);
        }
        World world = plugin.getServer().getWorld(existing.world());
        if (world == null) {
            return Optional.of("Monde non chargé : " + existing.world());
        }
        long seed = ("repair-beacon:" + beaconId).hashCode();
        TravelConfig.BeaconConfig bc = travelConfig.get().beacon();
        int ring = Math.max(16, bc.pairMaxSpacing());
        List<WaypointGenerationPlanner.Candidate> candidates = planner.candidates(
                existing.x(), existing.z(), seed, 4, ring, travelConfig.get().waypoint().candidateAttempts());

        for (WaypointGenerationPlanner.Candidate candidate : candidates) {
            Optional<Location> safe = RandomSafeLocationFinder.findAccessibleColumn(world, candidate.blockX(), candidate.blockZ());
            if (safe.isEmpty()) {
                continue;
            }
            int anchorX = safe.get().getBlockX();
            int anchorY = safe.get().getBlockY();
            int anchorZ = safe.get().getBlockZ();
            BlockFace facing = facingAwayFrom(existing.x(), existing.z(), anchorX, anchorZ);
            if (!areaFree(world, anchorX, anchorY, anchorZ, facing)
                    || waypointService.isProtectedBlock(world.getName(), anchorX, anchorY, anchorZ)
                    || violatesBeaconSpacingExcluding(beaconId, world.getName(), anchorX, anchorZ,
                            travelConfig.get().waypoint().minimumSpacing())) {
                continue;
            }

            deindexPositionalBlocks(existing);
            removeStructureBlocks(world, existing.x(), existing.y(), existing.z(), parseFacing(existing.facing()));
            place(world, anchorX, anchorY, anchorZ, facing);
            TravelBeacon repaired = new TravelBeacon(existing.id(), existing.world(), anchorX, anchorY, anchorZ,
                    facing.name(), existing.modelVersion(), existing.active(), existing.biomeInstance(), existing.createdAt());
            index(repaired);
            repository.updatePosition(repaired).exceptionally(error -> {
                logger.error("Impossible de persister la réparation de la borne {}", beaconId, error);
                return null;
            });
            logger.info("Borne « {} » réparée : {},{},{} -> {},{},{}.", beaconId,
                    existing.x(), existing.y(), existing.z(), anchorX, anchorY, anchorZ);
            return Optional.empty();
        }
        return Optional.of("Aucun emplacement accessible trouvé près de l'ancienne — réessaie plus tard.");
    }

    private void deindexPositionalBlocks(TravelBeacon beacon) {
        BlockFace facing = parseFacing(beacon.facing());
        int ix = beacon.x() + facing.getModX();
        int iz = beacon.z() + facing.getModZ();
        byInteractorBlock.remove(blockKey(beacon.world(), ix, beacon.y() + 1, iz));
        protectedBlocks.remove(blockKey(beacon.world(), beacon.x(), beacon.y() - 1, beacon.z()));
        protectedBlocks.remove(blockKey(beacon.world(), beacon.x(), beacon.y(), beacon.z()));
        protectedBlocks.remove(blockKey(beacon.world(), beacon.x(), beacon.y() + 1, beacon.z()));
        protectedBlocks.remove(blockKey(beacon.world(), ix, beacon.y() + 1, iz));
    }

    /** Ne retire jamais le sol — seulement le support, le bloc de diamant et le bouton. */
    private static void removeStructureBlocks(World world, int x, int y, int z, BlockFace facing) {
        world.getBlockAt(x, y, z).setType(Material.AIR, false);
        world.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        world.getBlockAt(x + facing.getModX(), y + 1, z + facing.getModZ()).setType(Material.AIR, false);
    }

    private boolean violatesBeaconSpacingExcluding(String excludeId, String world, int x, int z, int minSpacing) {
        if (minSpacing <= 0) {
            return false;
        }
        long minSq = (long) minSpacing * minSpacing;
        for (TravelBeacon existing : byId.values()) {
            if (existing.id().equals(excludeId) || !existing.world().equals(world)) {
                continue;
            }
            long dx = existing.x() - x;
            long dz = existing.z() - z;
            if (dx * dx + dz * dz < minSq) {
                return true;
            }
        }
        return false;
    }

    // ---- Placement administrateur ------------------------------------------------------------

    /**
     * Place une borne à la colonne (x, z) de {@code origin} (sans inventer de coordonnées : celles
     * du joueur qui exécute {@code /rpgadmin travel beacon set}), orientée selon son regard actuel.
     * Idempotent : rejouer exactement à la même ancre ne crée jamais de doublon.
     *
     * @return un message d'erreur si le placement a échoué, sinon {@link Optional#empty()} (succès).
     */
    public Optional<String> placeAt(Player admin, Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return Optional.of("Monde introuvable.");
        }
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        // Vérifié AVANT de recalculer une hauteur de sol : une fois la structure posée, la colonne
        // elle-même est plus haute (le bloc de diamant), donc rejouer la commande à la même colonne
        // recalculerait une ancre différente (un niveau plus haut) si on ne coupait pas court ici.
        for (TravelBeacon existing : byId.values()) {
            if (existing.world().equals(world.getName()) && existing.x() == originX && existing.z() == originZ) {
                return Optional.of("Une borne existe déjà à cet endroit.");
            }
        }
        Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, originX, originZ);
        if (safe.isEmpty()) {
            return Optional.of("Aucun emplacement sûr trouvé à cette colonne — déplace-toi légèrement et réessaie.");
        }
        int anchorX = safe.get().getBlockX();
        int anchorY = safe.get().getBlockY();
        int anchorZ = safe.get().getBlockZ();
        BlockFace facing = cardinalFacing(admin.getLocation().getYaw());

        String id = "beacon_" + world.getName().toLowerCase(Locale.ROOT) + "_" + anchorX + "_" + anchorY + "_" + anchorZ;
        if (!areaFree(world, anchorX, anchorY, anchorZ, facing)) {
            return Optional.of("Zone non libre (construction existante) — déplace-toi et réessaie.");
        }

        place(world, anchorX, anchorY, anchorZ, facing);
        TravelBeacon beacon = new TravelBeacon(id, world.getName(), anchorX, anchorY, anchorZ,
                facing.name(), VERSION, true, "", Instant.now());
        repository.insertIfAbsent(beacon).thenAccept(inserted -> runSync(() -> {
            if (inserted) {
                index(beacon);
                logger.info("Borne de voyage « {} » placée en {} ({},{},{}).",
                        beacon.id(), world.getName(), anchorX, anchorY, anchorZ);
            }
        })).exceptionally(error -> {
            logger.error("Impossible de persister la borne de voyage {}", id, error);
            return null;
        });
        return Optional.empty();
    }

    // ---- Génération automatique Hub (issue #149) : waypoint + borne appariés, emplacements distincts ----

    /**
     * Appelé par {@link TravelBeaconListener} sur {@code PlayerMoveEvent} (changement de bloc
     * horizontal seulement, comme {@code waypoint.WaypointListener}). Limité au monde Hub
     * configuré — le Wild continue de passer exclusivement par
     * {@code WaypointService#handleMovement}, jamais touché ici.
     */
    void handleHubMovement(Player player, Location to) {
        TravelConfig cfg = travelConfig.get();
        TravelConfig.WaypointConfig wc = cfg.waypoint();
        if (!wc.enabled() || !wc.hubEnabled() || to.getWorld() == null) {
            return;
        }
        String hub = hubWorld.get();
        if (hub == null || !to.getWorld().getName().equals(hub)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastHubCheckByPlayer.get(playerId);
        if (last != null && now - last < wc.moveThrottleMillis()) {
            return;
        }
        lastHubCheckByPlayer.put(playerId, now);

        World world = to.getWorld();
        int bx = to.getBlockX();
        int by = to.getBlockY();
        int bz = to.getBlockZ();
        String biomeKey = biomeKeyAt(world, bx, by, bz);
        BiomeInstanceKey instance = identityResolver.resolve(wc.regionSize(), hub, biomeKey, bx, bz);
        String instanceKey = instanceKey(hub, instance.serialize());
        // Issue #156 : on ne sort PAS simplement parce que le joueur est déjà dans cette instance.
        // La génération du waypoint (étape 1) persiste de façon asynchrone : au tout premier passage,
        // l'appariement de l'étape 2 ne trouve encore rien et abandonne. Avec une sortie anticipée sur
        // « même instance », l'instance ne pouvait plus JAMAIS être appariée avant que le joueur la
        // quitte puis y revienne — sur le Hub DEV, les 13 bornes existantes ont toutes été créées
        // 17 s à 11 h APRÈS leur waypoint, et 9 instances visitées une seule fois n'en avaient aucune.
        // Tant que l'instance n'a pas sa borne, on laisse donc passer : le coût reste borné par le
        // throttle ci-dessus, par le verrou « pairing » et par le backoff de pairRetryNotBefore.
        boolean sameInstance = instanceKey.equals(lastHubInstanceByPlayer.get(playerId));
        lastHubInstanceByPlayer.put(playerId, instanceKey);
        if (sameInstance && byBiomeInstance.containsKey(instanceKey)) {
            return;
        }

        // 1) même mécanisme exact que le Wild (#124), réutilisé tel quel — jamais réécrit ici.
        waypointService.ensureGenerated(world, to);

        // 2) une fois ce waypoint réellement présent (peut-être déjà le cas, ou généré à l'instant
        //    par l'appel ci-dessus — la persistance est asynchrone, donc souvent pas encore prêt au
        //    tout premier passage : l'appariement réessaiera au prochain déplacement du joueur dans
        //    cette instance, sans scan supplémentaire), apparier une borne distincte.
        Waypoint waypoint = waypointService.byId(instance.waypointId()).orElse(null);
        if (waypoint == null) {
            return;
        }
        ensureBeaconPaired(world, instance, instanceKey, waypoint, cfg.beacon());
    }

    private void ensureBeaconPaired(World world, BiomeInstanceKey instance, String instanceKey,
                                     Waypoint waypoint, TravelConfig.BeaconConfig bc) {
        if (!bc.hubGenerationEnabled() || byBiomeInstance.containsKey(instanceKey)) {
            return;
        }
        Long notBefore = pairRetryNotBefore.get(instanceKey);
        if (notBefore != null && System.currentTimeMillis() < notBefore) {
            return;
        }
        if (!pairing.add(instanceKey)) {
            return; // appariement déjà en cours pour cette instance.
        }
        try {
            attemptPairBeacon(world, instance, instanceKey, waypoint, bc);
        } catch (RuntimeException error) {
            pairing.remove(instanceKey);
            logger.error("Échec inattendu d'appariement de borne pour {}", instanceKey, error);
        }
    }

    /**
     * Recherche un emplacement <strong>distinct</strong> du waypoint (anneau min/max-spacing autour
     * de lui, jamais au même endroit).
     *
     * @return {@code true} si une borne a été posée — l'appariement ciblé de {@link #pairHubInstance}
     *         a besoin de distinguer « posée » de « aucun emplacement valable », les deux étant des
     *         issues normales qu'un administrateur doit pouvoir lire
     */
    private boolean attemptPairBeacon(World world, BiomeInstanceKey instance, String instanceKey,
                                    Waypoint waypoint, TravelConfig.BeaconConfig bc) {
        long seed = (instanceKey + "#beacon").hashCode();
        int attempts = travelConfig.get().waypoint().candidateAttempts();
        List<WaypointGenerationPlanner.Candidate> candidates = planner.candidates(
                waypoint.x(), waypoint.z(), seed, bc.pairMinSpacing(), bc.pairMaxSpacing(), attempts);

        for (WaypointGenerationPlanner.Candidate candidate : candidates) {
            // #153 : jamais une borne flottant au sommet d'un arbre ni sur un surplomb isolé.
            Optional<Location> safe = RandomSafeLocationFinder.findAccessibleColumn(world, candidate.blockX(), candidate.blockZ());
            if (safe.isEmpty()) {
                continue;
            }
            int anchorX = safe.get().getBlockX();
            int anchorY = safe.get().getBlockY();
            int anchorZ = safe.get().getBlockZ();
            BlockFace facing = facingAwayFrom(waypoint.x(), waypoint.z(), anchorX, anchorZ);

            if (!areaFree(world, anchorX, anchorY, anchorZ, facing)
                    || waypointService.isProtectedBlock(world.getName(), anchorX, anchorY, anchorZ)
                    || violatesBeaconSpacing(world.getName(), anchorX, anchorZ, travelConfig.get().waypoint().minimumSpacing())) {
                continue;
            }

            String id = "beacon_auto_" + sanitize(world.getName()) + "_"
                    + sanitize(stripNamespace(waypoint.biomeKey())) + "_" + waypoint.regionX() + "_" + waypoint.regionZ();
            place(world, anchorX, anchorY, anchorZ, facing);
            TravelBeacon beacon = new TravelBeacon(id, world.getName(), anchorX, anchorY, anchorZ,
                    facing.name(), VERSION, true, instance.serialize(), Instant.now());
            persistPairedBeacon(beacon, instanceKey);
            return true;
        }

        // Aucun candidat valable : retry borné, jamais de boucle lourde (même esprit que WaypointService).
        pairing.remove(instanceKey);
        int attempt = pairRetryCount.merge(instanceKey, 1, Integer::sum);
        long backoff = RETRY_BACKOFF_MILLIS[Math.min(attempt - 1, RETRY_BACKOFF_MILLIS.length - 1)];
        pairRetryNotBefore.put(instanceKey, System.currentTimeMillis() + backoff);
        logger.info("Aucun emplacement de borne trouvé pour {} (essai {}), nouvel essai dans {} s.",
                instanceKey, attempt, backoff / 1000);
        return false;
    }

    private void persistPairedBeacon(TravelBeacon beacon, String instanceKey) {
        repository.insertIfAbsent(beacon).thenAccept(inserted -> runSync(() -> {
            pairing.remove(instanceKey);
            pairRetryCount.remove(instanceKey);
            pairRetryNotBefore.remove(instanceKey);
            if (inserted) {
                index(beacon);
                logger.info("Borne de voyage « {} » appariée en {} ({},{},{}) [instance {}].",
                        beacon.id(), beacon.world(), beacon.x(), beacon.y(), beacon.z(), instanceKey);
            } else {
                // Course perdue (autre nœud/thread) : réaligner l'état mémoire depuis la base.
                repository.findByInstance(beacon.world(), beacon.biomeInstance())
                        .thenAccept(opt -> runSync(() -> opt.ifPresent(this::index)))
                        .exceptionally(error -> {
                            logger.error("Impossible de recharger la borne appariée {}", beacon.id(), error);
                            return null;
                        });
            }
        })).exceptionally(error -> {
            pairing.remove(instanceKey);
            logger.error("Impossible de persister la borne appariée {}", beacon.id(), error);
            return null;
        });
    }

    /** Même esprit que {@code WaypointService#violatesSpacing} — jamais deux bornes trop proches, toutes origines confondues. */
    private boolean violatesBeaconSpacing(String world, int x, int z, int minSpacing) {
        if (minSpacing <= 0) {
            return false;
        }
        long minSq = (long) minSpacing * minSpacing;
        for (TravelBeacon existing : byId.values()) {
            if (!existing.world().equals(world)) {
                continue;
            }
            long dx = existing.x() - x;
            long dz = existing.z() - z;
            if (dx * dx + dz * dz < minSq) {
                return true;
            }
        }
        return false;
    }

    private String biomeKeyAt(World world, int x, int y, int z) {
        try {
            return world.getBiome(x, y, z).getKey().toString();
        } catch (RuntimeException error) {
            return "minecraft:the_void";
        }
    }

    /** Oriente le bouton à l'opposé du waypoint (ne pointe jamais vers lui) — purement cosmétique. */
    private static BlockFace facingAwayFrom(int fromX, int fromZ, int anchorX, int anchorZ) {
        double dx = anchorX - fromX;
        double dz = anchorZ - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static String stripNamespace(String key) {
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(colon + 1) : key;
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9]", "_");
    }

    // ---- Interaction bouton -------------------------------------------------------------------

    /** @return {@code true} si {@code block} est l'interacteur d'une borne (l'événement doit alors être annulé). */
    boolean handleButtonInteract(Player player, Block block) {
        TravelBeacon beacon = byInteractorBlock.get(blockKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        if (beacon == null || !beacon.active()) {
            return false;
        }
        openRoot(player);
        return true;
    }

    // ---- Menu : racine --------------------------------------------------------------------

    void openRoot(Player player) {
        Inventory inventory = createMenu(BeaconMenuHolder.Kind.ROOT, 9, "Réseau de voyage");
        inventory.setItem(2, icon(Material.ENDER_EYE, "<gold>Waypoints découverts</gold>",
                "<gray>Tes repères découverts dans le Wild / le Hub.</gray>"));
        boolean hasClaim = claimService.mainClaimOf(player.getUniqueId()).isPresent();
        inventory.setItem(4, hasClaim
                ? icon(Material.GRASS_BLOCK, "<yellow>Mon claim</yellow>", "<gray>Clique pour y voyager.</gray>")
                : icon(Material.BARRIER, "<yellow>Mon claim</yellow>", "<gray>Tu n'as pas encore de claim.</gray>"));
        inventory.setItem(6, icon(Material.BELL, "<yellow>Villages</yellow>",
                "<gray>Centres de village configurés.</gray>"));
        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new BeaconMenuSession(0, "", ""));
    }

    /** Revalidation fraîche à chaque clic (jamais l'état de l'icône au moment de l'ouverture). */
    void handleRootCategoryClick(Player player, int slot) {
        switch (slot) {
            case 2 -> openWorldSelection(player);
            case 4 -> travelToClaim(player);
            case 6 -> openVillages(player, 0);
            default -> { }
        }
    }

    // ---- Menu/voyage : Mon claim (issue #151) ----------------------------------------------

    void travelToClaim(Player player) {
        Optional<Claim> claimOpt = claimService.mainClaimOf(player.getUniqueId());
        if (claimOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Tu n'as pas (ou plus) de claim.</red>"));
            return;
        }
        Claim claim = claimOpt.get();
        World world = plugin.getServer().getWorld(claim.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de ton claim n'est pas chargé.</red>"));
            return;
        }
        int centerX = (claim.minX() + claim.maxX()) / 2;
        int centerZ = (claim.minZ() + claim.maxZ()) / 2;
        Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, centerX, centerZ);
        if (safe.isEmpty() || !claim.contains(world.getName(), safe.get().getBlockX(),
                safe.get().getBlockY(), safe.get().getBlockZ())) {
            player.sendMessage(MM.deserialize(
                    "<red>Arrivée dangereuse ou indisponible sur ton claim — réessaie plus tard.</red>"));
            return;
        }
        player.closeInventory();
        player.teleportAsync(safe.get());
        player.sendMessage(MM.deserialize("<gold>Voyage vers ton claim.</gold>"));
    }

    // ---- Menu : Villages (issue #151) -----------------------------------------------------

    void openVillages(Player player, int page) {
        List<VillageCenter> sorted = villagesById.values().stream()
                .filter(VillageCenter::active)
                .sorted(Comparator.comparing(VillageCenter::name))
                .toList();

        int pageCount = Math.max(1, (int) Math.ceil(sorted.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, pageCount - 1));
        int from = Math.min(sorted.size(), clampedPage * PAGE_SIZE);
        int to = Math.min(sorted.size(), from + PAGE_SIZE);
        List<VillageCenter> pageItems = sorted.subList(from, to);

        Inventory inventory = createMenu(BeaconMenuHolder.Kind.VILLAGES, 54, "Villages");
        if (pageItems.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, "<red>Aucun village configuré</red>",
                    "<gray>Demande à un administrateur d'en poser un.</gray>"));
        } else {
            for (int i = 0; i < pageItems.size(); i++) {
                VillageCenter center = pageItems.get(i);
                inventory.setItem(i, icon(Material.BELL, "<gold>" + center.name() + "</gold>",
                        "<gray>Monde :</gray> <white>" + center.world() + "</white>",
                        "<gray>Clique pour voyager.</gray>"));
            }
        }
        inventory.setItem(45, icon(Material.ARROW, "<yellow>Retour</yellow>", ""));
        if (clampedPage > 0) {
            inventory.setItem(48, icon(Material.SPECTRAL_ARROW, "<yellow>Page précédente</yellow>", ""));
        }
        inventory.setItem(49, icon(Material.PAPER, "<white>Page " + (clampedPage + 1) + " / " + pageCount + "</white>", ""));
        if (clampedPage < pageCount - 1) {
            inventory.setItem(50, icon(Material.SPECTRAL_ARROW, "<yellow>Page suivante</yellow>", ""));
        }
        inventory.setItem(53, icon(Material.BARRIER, "<red>Fermer</red>", ""));

        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new BeaconMenuSession(clampedPage, "", ""));
    }

    void handleVillagesClick(Player player, int slot, BeaconMenuSession session) {
        switch (slot) {
            case 45 -> openRoot(player);
            case 48 -> openVillages(player, session.page() - 1);
            case 50 -> openVillages(player, session.page() + 1);
            case 53 -> player.closeInventory();
            default -> {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    selectVillageAtSlot(player, session, slot);
                }
            }
        }
    }

    private void selectVillageAtSlot(Player player, BeaconMenuSession session, int slot) {
        List<VillageCenter> sorted = villagesById.values().stream()
                .filter(VillageCenter::active)
                .sorted(Comparator.comparing(VillageCenter::name))
                .toList();
        int index = session.page() * PAGE_SIZE + slot;
        if (index < 0 || index >= sorted.size()) {
            return; // clic périmé (page changée entre-temps) : ignoré.
        }
        travelToVillage(player, sorted.get(index).id());
    }

    /** Revalidation stricte (existence + actif) avant tout déplacement. */
    void travelToVillage(Player player, String villageId) {
        VillageCenter center = villagesById.get(villageId);
        if (center == null || !center.active()) {
            player.sendMessage(MM.deserialize("<red>Ce village n'est plus disponible.</red>"));
            return;
        }
        World world = plugin.getServer().getWorld(center.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de ce village n'est pas chargé.</red>"));
            return;
        }
        player.closeInventory();
        player.teleportAsync(new Location(world, center.x(), center.y(), center.z(), center.yaw(), center.pitch()));
        player.sendMessage(MM.deserialize(
                "<gold>Voyage vers</gold> <white><name></white><gray>.</gray>",
                Placeholder.unparsed("name", center.name())));
    }

    // ---- Administration des centres de village (issue #151, /rpgadmin travel village) -----

    /** Crée ou déplace/renomme un centre (même {@code id} = jamais une nouvelle identité). */
    public void setVillage(String id, String name, Location location) {
        World world = location.getWorld();
        VillageCenter center = new VillageCenter(id, name, world.getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch(), true, Instant.now());
        villageCenterRepository.upsert(center).thenRun(() -> runSync(() -> villagesById.put(id, center)))
                .exceptionally(error -> {
                    logger.error("Impossible de persister le centre de village {}", id, error);
                    return null;
                });
    }

    public Optional<String> setVillageActive(String id, boolean active) {
        VillageCenter existing = villagesById.get(id);
        if (existing == null) {
            return Optional.of("Centre de village introuvable : " + id);
        }
        villageCenterRepository.setActive(id, active).thenRun(() -> runSync(() -> {
            VillageCenter current = villagesById.get(id);
            if (current != null) {
                villagesById.put(id, new VillageCenter(current.id(), current.name(), current.world(), current.x(),
                        current.y(), current.z(), current.yaw(), current.pitch(), active, current.createdAt()));
            }
        })).exceptionally(error -> {
            logger.error("Impossible de (dés)activer le centre de village {}", id, error);
            return null;
        });
        return Optional.empty();
    }

    public Optional<String> removeVillage(String id) {
        if (!villagesById.containsKey(id)) {
            return Optional.of("Centre de village introuvable : " + id);
        }
        villagesById.remove(id);
        villageCenterRepository.delete(id).exceptionally(error -> {
            logger.error("Impossible de supprimer le centre de village {}", id, error);
            return null;
        });
        return Optional.empty();
    }

    public List<VillageCenter> villages() {
        return List.copyOf(villagesById.values());
    }

    // ---- Menu : choix du monde (issue #149-suite) -----------------------------------------

    /**
     * Étape intermédiaire entre la racine et la liste des waypoints : le Hub et le Wild
     * ({@code travel.wild-world}) ont toujours leur propre page, même à 0 découverte — les autres
     * mondes sont <strong>extensibles</strong>, affichés uniquement s'il existe au moins une
     * découverte active du joueur courant dans ce monde. Ordre figé dans la session
     * ({@code worldsShown}) : jamais recalculé au clic, un clic sur un slot référence toujours le
     * même monde qu'au moment du rendu.
     */
    void openWorldSelection(Player player) {
        List<Waypoint> discovered = waypointService.discoveredBy(player.getUniqueId());
        LinkedHashSet<String> worlds = new LinkedHashSet<>();
        worlds.add(travelConfig.get().wildWorld());
        String hub = hubWorld.get();
        if (hub != null && !hub.isBlank()) {
            worlds.add(hub);
        }
        for (Waypoint waypoint : discovered) {
            worlds.add(waypoint.world());
        }
        List<String> ordered = List.copyOf(worlds);

        Inventory inventory = createMenu(BeaconMenuHolder.Kind.WORLDS, 54, "Choisir un monde");
        for (int i = 0; i < ordered.size() && i < PAGE_SIZE; i++) {
            String world = ordered.get(i);
            long count = discovered.stream().filter(w -> w.world().equals(world)).count();
            inventory.setItem(i, icon(Material.MAP, "<gold>" + prettyWorldName(world) + "</gold>",
                    "<gray>" + count + " waypoint(s) découvert(s)</gray>",
                    "<gray>Clique pour explorer.</gray>"));
        }
        inventory.setItem(45, icon(Material.ARROW, "<yellow>Retour</yellow>", ""));
        inventory.setItem(53, icon(Material.BARRIER, "<red>Fermer</red>", ""));

        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new BeaconMenuSession(0, "", "", ordered));
    }

    void handleWorldsClick(Player player, int slot, BeaconMenuSession session) {
        switch (slot) {
            case 45 -> openRoot(player);
            case 53 -> player.closeInventory();
            default -> {
                if (slot >= 0 && slot < session.worldsShown().size()) {
                    openWaypoints(player, 0, "", session.worldsShown().get(slot));
                }
                // sinon : clic périmé (slot hors de la liste affichée) — ignoré.
            }
        }
    }

    private static String prettyWorldName(String world) {
        return switch (world) {
            case "wild" -> "Wild";
            case "world_hub" -> "Hub";
            default -> world;
        };
    }

    // ---- Menu : waypoints découverts (d'un monde donné) -----------------------------------

    void openWaypoints(Player player, int page, String filter, String world) {
        List<Waypoint> inWorld = waypointService.discoveredBy(player.getUniqueId()).stream()
                .filter(w -> w.world().equals(world))
                .toList();
        List<Waypoint> filtered = filter.isEmpty() ? inWorld : inWorld.stream()
                .filter(w -> normalize(w.displayName() + " " + prettyBiome(w.biomeKey())).contains(filter))
                .toList();
        List<Waypoint> sorted = filtered.stream()
                .sorted(Comparator.comparing(Waypoint::displayName).thenComparing(Waypoint::id))
                .toList();

        int pageCount = Math.max(1, (int) Math.ceil(sorted.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, pageCount - 1));
        int from = Math.min(sorted.size(), clampedPage * PAGE_SIZE);
        int to = Math.min(sorted.size(), from + PAGE_SIZE);
        List<Waypoint> pageItems = sorted.subList(from, to);

        Inventory inventory = createMenu(BeaconMenuHolder.Kind.WAYPOINTS, 54, prettyWorldName(world));
        if (pageItems.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, "<red>Aucun waypoint</red>",
                    filter.isEmpty()
                            ? "<gray>Rien d'encore découvert dans ce monde.</gray>"
                            : "<gray>Aucun résultat pour « " + filter + " ».</gray>"));
        } else {
            for (int i = 0; i < pageItems.size(); i++) {
                Waypoint waypoint = pageItems.get(i);
                inventory.setItem(i, waypointIcon(waypoint));
            }
        }
        inventory.setItem(45, icon(Material.ARROW, "<yellow>Retour</yellow>", ""));
        // Indicateur de recherche active (retour joueur 2026-10-04) : rend le filtre et son nombre
        // de résultats visibles, avec une action dédiée pour l'effacer -- jusqu'ici, un filtre actif
        // était invisible une fois revenu sur la liste, donnant l'impression qu'il n'avait rien fait.
        if (!filter.isEmpty()) {
            inventory.setItem(46, icon(Material.COMPASS, "<yellow>Recherche active :</yellow> <white>« " + filter + " »</white>",
                    "<gray>" + sorted.size() + " résultat(s).</gray>", "<gray>Clique pour effacer le filtre.</gray>"));
        }
        inventory.setItem(47, icon(Material.NAME_TAG, "<aqua>Rechercher</aqua>",
                "<gray>Ouvre une saisie de nom (sans commande, sans coût).</gray>"));
        if (clampedPage > 0) {
            inventory.setItem(48, icon(Material.SPECTRAL_ARROW, "<yellow>Page précédente</yellow>", ""));
        }
        inventory.setItem(49, icon(Material.PAPER, "<white>Page " + (clampedPage + 1) + " / " + pageCount + "</white>", ""));
        if (clampedPage < pageCount - 1) {
            inventory.setItem(50, icon(Material.SPECTRAL_ARROW, "<yellow>Page suivante</yellow>", ""));
        }
        inventory.setItem(53, icon(Material.BARRIER, "<red>Fermer</red>", ""));

        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new BeaconMenuSession(clampedPage, filter, world));
    }

    void handleWaypointsClick(Player player, int slot, BeaconMenuSession session) {
        switch (slot) {
            case 45 -> openWorldSelection(player);
            case 46 -> {
                if (!session.filter().isEmpty()) {
                    openWaypoints(player, 0, "", session.world());
                }
            }
            case 47 -> openSearch(player, session.world());
            case 48 -> openWaypoints(player, session.page() - 1, session.filter(), session.world());
            case 50 -> openWaypoints(player, session.page() + 1, session.filter(), session.world());
            case 53 -> player.closeInventory();
            default -> {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    selectWaypointAtSlot(player, session, slot);
                }
            }
        }
    }

    private void selectWaypointAtSlot(Player player, BeaconMenuSession session, int slot) {
        List<Waypoint> inWorld = waypointService.discoveredBy(player.getUniqueId()).stream()
                .filter(w -> w.world().equals(session.world()))
                .toList();
        List<Waypoint> filtered = session.filter().isEmpty() ? inWorld : inWorld.stream()
                .filter(w -> normalize(w.displayName() + " " + prettyBiome(w.biomeKey())).contains(session.filter()))
                .toList();
        List<Waypoint> sorted = filtered.stream()
                .sorted(Comparator.comparing(Waypoint::displayName).thenComparing(Waypoint::id))
                .toList();
        int index = session.page() * PAGE_SIZE + slot;
        if (index < 0 || index >= sorted.size()) {
            return; // clic périmé (page changée entre-temps) : ignoré.
        }
        travelTo(player, sorted.get(index).id());
    }

    /** Revalidation stricte puis arrivée sûre — jamais via un clic périmé sur un waypoint non découvert. */
    void travelTo(Player player, String waypointId) {
        if (!waypointService.hasActivelyDiscovered(player.getUniqueId(), waypointId)) {
            player.sendMessage(MM.deserialize(
                    "<red>Ce waypoint n'est plus disponible (non découvert, désactivé ou supprimé).</red>"));
            return;
        }
        Waypoint waypoint = waypointService.byId(waypointId).orElse(null);
        if (waypoint == null) {
            player.sendMessage(MM.deserialize("<red>Ce waypoint n'existe plus.</red>"));
            return;
        }
        World world = plugin.getServer().getWorld(waypoint.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de cette destination n'est pas chargé.</red>"));
            return;
        }
        Optional<Location> safe = RandomSafeLocationFinder.findAtColumn(world, waypoint.x(), waypoint.z());
        if (safe.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Arrivée dangereuse ou terrain indisponible près de cette destination — réessaie plus tard.</red>"));
            return;
        }
        player.closeInventory();
        player.teleportAsync(safe.get());
        player.sendMessage(MM.deserialize(
                "<gold>Voyage vers</gold> <white><name></white><gray>.</gray>",
                Placeholder.unparsed("name", waypoint.displayName())));
    }

    // ---- Recherche graphique (enclume virtuelle) -------------------------------------------

    /** {@code world} : monde dans lequel le résultat de recherche rouvrira la liste — jamais perdu
     * pendant le détour par l'enclume (voir le commentaire de {@link #sessions}). */
    void openSearch(Player player, String world) {
        BeaconMenuHolder holder = new BeaconMenuHolder(BeaconMenuHolder.Kind.SEARCH);
        Inventory inventory = plugin.getServer().createInventory(holder, org.bukkit.event.inventory.InventoryType.ANVIL,
                MM.deserialize("<dark_gray>Rechercher un waypoint</dark_gray>"));
        holder.bind(inventory);
        ItemStack input = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = input.getItemMeta();
        meta.displayName(MM.deserialize("<white>Tape un nom...</white>"));
        // Enclume vanilla : ni la touche Entrée ni la fermeture ne valident une recherche, seul un
        // clic sur l'étiquette de résultat (à droite) le fait -- rendu explicite ici (retour joueur
        // 2026-10-04) plutôt que supposé compris, Minecraft ne l'indiquant pas lui-même.
        meta.lore(List.of(MM.deserialize("<gray>Clique sur l'étiquette à droite pour rechercher.</gray>")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        input.setItemMeta(meta);
        inventory.setItem(0, input);
        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new BeaconMenuSession(0, "", world));
    }

    /**
     * Appelé par {@link TravelBeaconListener} sur {@code PrepareAnvilEvent} : jamais de coût XP.
     * Zéro à la fois sur {@code repairCost} (le niveau affiché) <strong>et</strong> {@code
     * repairCostAmount} (compteur interne vanilla distinct, voir {@link
     * org.bukkit.inventory.AnvilInventory}) -- un retour joueur a observé un « Coût : 1 » résiduel
     * malgré {@code repairCost} déjà à 0, ce second champ étant la cause la plus documentée de ce
     * résidu visuel côté client.
     */
    void handlePrepareAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        event.getInventory().setRepairCost(0);
        event.getInventory().setRepairCostAmount(0);
        ItemStack first = event.getInventory().getItem(0);
        if (first == null) {
            return;
        }
        ItemStack result = first.clone();
        String renameText = event.getInventory().getRenameText();
        if (renameText != null && !renameText.isBlank()) {
            ItemMeta meta = result.getItemMeta();
            meta.displayName(Component.text(renameText));
            result.setItemMeta(meta);
        }
        event.setResult(result);

        // Retour joueur 2026-10-04 (issue #150) : mémorise le texte RÉELLEMENT tapé dans la session
        // à chaque frappe, plutôt que de ne le lire qu'au clic sur le résultat. Le slot « résultat »
        // d'une enclume suit un chemin vanilla spécial (consommation des entrées à la prise) qui ne
        // garantit pas que l'ItemStack lu par InventoryClickEvent#getCurrentItem() porte encore le
        // texte tapé au moment où notre écouteur s'exécute -- cause probable du filtrage resté vide
        // malgré un clic bien reçu. En gardant la dernière valeur connue dans la session, le clic
        // n'a plus besoin de relire quoi que ce soit sur l'objet cliqué (voir handleSearchResultClick).
        if (event.getView().getPlayer() instanceof Player typingPlayer) {
            BeaconMenuSession current = sessions.get(typingPlayer.getUniqueId());
            if (current != null) {
                String liveFilter = renameText == null ? "" : normalize(renameText);
                sessions.put(typingPlayer.getUniqueId(), new BeaconMenuSession(0, liveFilter, current.world()));
            }
        }
    }

    /**
     * Clic sur le résultat (slot 2) de l'enclume de recherche : jamais d'objet réellement donné.
     * Utilise en priorité le texte déjà mémorisé en continu par {@link #handlePrepareAnvil} ; ne
     * retombe sur une lecture de {@code resultItem} que si la session n'a mémorisé aucune frappe
     * (ex. appel direct sans simulation de saisie, comme certains tests) -- jamais l'inverse.
     */
    void handleSearchResultClick(Player player, ItemStack resultItem) {
        BeaconMenuSession session = sessionOf(player);
        String world = session != null && !session.world().isBlank() ? session.world() : travelConfig.get().wildWorld();
        String filter = session != null ? session.filter() : "";
        if (filter.isEmpty() && resultItem != null && resultItem.getItemMeta() != null
                && resultItem.getItemMeta().hasDisplayName()) {
            String typed = PlainTextComponentSerializer.plainText().serialize(resultItem.getItemMeta().displayName());
            filter = normalize(typed);
        }
        openWaypoints(player, 0, filter, world);
    }

    void handleClose(Player player) {
        sessions.remove(player.getUniqueId());
    }

    BeaconMenuSession sessionOf(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ---- Rendu --------------------------------------------------------------------------

    private ItemStack waypointIcon(Waypoint waypoint) {
        return icon(Material.FILLED_MAP, "<gold>" + waypoint.displayName() + "</gold>",
                "<gray>" + prettyBiome(waypoint.biomeKey()) + "</gray>",
                "<gray>Clique pour voyager.</gray>");
    }

    private static ItemStack icon(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> loreLines = new ArrayList<>();
        for (String line : lore) {
            if (!line.isEmpty()) {
                loreLines.add(MM.deserialize(line).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
        }
        meta.lore(loreLines);
        stack.setItemMeta(meta);
        return stack;
    }

    private Inventory createMenu(BeaconMenuHolder.Kind kind, int size, String title) {
        BeaconMenuHolder holder = new BeaconMenuHolder(kind);
        Inventory inventory = plugin.getServer().createInventory(holder, size, MM.deserialize("<dark_gray>" + title + "</dark_gray>"));
        holder.bind(inventory);
        return inventory;
    }

    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String prettyBiome(String biomeKey) {
        int colon = biomeKey.indexOf(':');
        String name = colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey;
        return name.replace('_', ' ');
    }

    // ---- Structure physique (support waypoint + diamant + bouton bois) --------------------

    private void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing) {
        Block ground = world.getBlockAt(anchorX, anchorY - 1, anchorZ);
        if (!ground.getType().isSolid()) {
            ground.setType(Material.STONE, false);
        }
        set(world, anchorX, anchorY, anchorZ, Material.COBBLESTONE_WALL);
        set(world, anchorX, anchorY + 1, anchorZ, Material.DIAMOND_BLOCK);
        set(world, anchorX, anchorY + 2, anchorZ, Material.AIR);

        int bx = anchorX + facing.getModX();
        int bz = anchorZ + facing.getModZ();
        Block button = world.getBlockAt(bx, anchorY + 1, bz);
        Material buttonMaterial = travelConfig.get().beacon().buttonMaterial();
        button.setType(buttonMaterial, false);
        try {
            BlockData data = button.getBlockData();
            if (data instanceof Switch sw) {
                sw.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                sw.setFacing(facing);
                button.setBlockData(sw, false);
            }
        } catch (RuntimeException ignored) {
            // BlockData non simulé (tests) : le type de bouton configuré suffit, l'identité de
            // l'interacteur est purement positionnelle côté service (comme WaypointModelV1).
        }
        set(world, bx, anchorY + 2, bz, Material.AIR);
    }

    private boolean areaFree(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing) {
        for (int[] offset : new int[][] {{0, -1, 0}, {0, 0, 0}, {0, 1, 0},
                {facing.getModX(), 1, facing.getModZ()}}) {
            int x = anchorX + offset[0];
            int y = anchorY + offset[1];
            int z = anchorZ + offset[2];
            Material type = world.getBlockAt(x, y, z).getType();
            boolean groundLevel = offset[1] == -1;
            if (groundLevel ? !type.isSolid() && !isReplaceable(type) : !isReplaceable(type)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReplaceable(Material material) {
        return material.isAir() || !material.isSolid();
    }

    private static void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }

    private static BlockFace cardinalFacing(float yaw) {
        float normalized = ((yaw % 360) + 360) % 360;
        if (normalized >= 315 || normalized < 45) {
            return BlockFace.SOUTH;
        } else if (normalized < 135) {
            return BlockFace.WEST;
        } else if (normalized < 225) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    private void index(TravelBeacon beacon) {
        byId.put(beacon.id(), beacon);
        if (beacon.isAutoGenerated()) {
            byBiomeInstance.put(instanceKey(beacon.world(), beacon.biomeInstance()), beacon);
        }
        BlockFace facing = parseFacing(beacon.facing());
        int ix = beacon.x() + facing.getModX();
        int iy = beacon.y() + 1;
        int iz = beacon.z() + facing.getModZ();
        byInteractorBlock.put(blockKey(beacon.world(), ix, iy, iz), beacon);
        protectedBlocks.add(blockKey(beacon.world(), beacon.x(), beacon.y() - 1, beacon.z()));
        protectedBlocks.add(blockKey(beacon.world(), beacon.x(), beacon.y(), beacon.z()));
        protectedBlocks.add(blockKey(beacon.world(), beacon.x(), beacon.y() + 1, beacon.z()));
        protectedBlocks.add(blockKey(beacon.world(), ix, iy, iz));
    }

    private static BlockFace parseFacing(String raw) {
        try {
            BlockFace face = BlockFace.valueOf(raw);
            return switch (face) {
                case NORTH, SOUTH, EAST, WEST -> face;
                default -> BlockFace.NORTH;
            };
        } catch (IllegalArgumentException error) {
            return BlockFace.NORTH;
        }
    }

    private static String instanceKey(String world, String biomeInstance) {
        return world + "#" + biomeInstance;
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    private void runSync(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
