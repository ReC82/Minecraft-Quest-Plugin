package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.EncounterConfig;
import com.lodygames.rpgquest.database.EncounterSiteRepository;
import com.lodygames.rpgquest.encounter.model.EncounterSite;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import com.lodygames.rpgquest.npc.CitizensNpc;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.waypoint.WaypointGenerationPlanner;
import com.lodygames.rpgquest.waypoint.WaypointIdentityResolver;
import com.lodygames.rpgquest.waypoint.WaypointPlacementGuard;
import com.lodygames.rpgquest.waypoint.model.BiomeInstanceKey;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.slf4j.Logger;

/**
 * Moteur des PNJ récurrents par instance de biome (issue #13).
 *
 * <ul>
 *   <li><strong>Paresseux et borné</strong> : rien au démarrage, aucun chunk chargé pour cela. Une
 *       instance n'est évaluée que quand un joueur y entre (mouvement throttlé, comme les
 *       waypoints), et seuls les candidats situés dans des chunks <em>déjà chargés</em> sont
 *       testés.</li>
 *   <li><strong>Une rencontre par instance</strong> : verrou mono-vol en mémoire + index unique
 *       {@code (world, biome_instance)} en base. La ligne est écrite <em>avant</em> la création du
 *       PNJ Citizens, qui porte une marque persistante {@value #CITIZENS_MARK} = id d'occurrence :
 *       un arrêt brutal entre les deux ne laisse jamais un second PNJ, le suivant est réadopté.</li>
 *   <li><strong>Stable</strong> : la position est mémorisée ; un PNJ supprimé à la main est
 *       recréé au même endroit (réparation bornée par un délai croissant), jamais multiplié.</li>
 *   <li><strong>Isolé</strong> : aucune liaison {@code npc_citizens_bindings} ; l'identité
 *       ({@code npc_id} du modèle → dialogue {@code rpgquest:<npc_id>}) est servie à
 *       {@link NpcIdentityService} par {@link #npcIdOf(UUID)}.</li>
 * </ul>
 *
 * <p>Toutes les méthodes publiques non asynchrones s'exécutent sur le <strong>thread
 * principal</strong> (monde + API Citizens) ; seule la base est appelée en asynchrone.</p>
 */
public final class EncounterService implements PluginService {

    /** Clé de la marque persistante posée sur chaque PNJ Citizens d'une rencontre. */
    public static final String CITIZENS_MARK = "rpgquest-encounter";

    private static final long[] RETRY_BACKOFF_MILLIS = {60_000L, 300_000L, 1_800_000L, 3_600_000L};
    private static final int MAX_RECENT_EVENTS = 50;

    /** Distance (blocs) entre une rencontre et un waypoint : lecture seule du système de waypoints. */
    @FunctionalInterface
    public interface WaypointProximity {
        boolean hasWaypointWithin(String world, int x, int z, int radius);
    }

    /** Événement de diagnostic récent (apparition, échec, réparation), pour le panel et les logs. */
    public record Event(Instant at, String world, String instance, String outcome, String detail) {
    }

    /** Résultat d'une action administrateur. */
    public record ActionResult(boolean ok, String code, String message) {
    }

    private final RPGQuestPlugin plugin;
    private final EncounterSiteRepository repository;
    private final YamlEncounterEngine templates;
    private final NpcIdentityService npcs;
    private final WaypointIdentityResolver identityResolver;
    private final WaypointGenerationPlanner planner;
    private final WaypointPlacementGuard placementGuard;
    private final WaypointProximity waypoints;
    private final Supplier<EncounterConfig> config;
    private final Supplier<Long> regionSize;
    private final Supplier<String> wildWorld;
    private final Supplier<Set<String>> forbiddenWorlds;
    private final EncounterTemplateSelector selector = new EncounterTemplateSelector();
    private final Logger logger;

    private final ConcurrentHashMap<String, EncounterSite> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EncounterSite> byInstance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, EncounterSite> byCitizens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> siteByChunk = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> retryNotBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastInstanceByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastCheckByPlayer = new ConcurrentHashMap<>();
    private final Deque<Event> recentEvents = new ArrayDeque<>();
    private volatile boolean loaded;

    public EncounterService(RPGQuestPlugin plugin, EncounterSiteRepository repository, YamlEncounterEngine templates,
                            NpcIdentityService npcs, WaypointIdentityResolver identityResolver,
                            WaypointGenerationPlanner planner, WaypointPlacementGuard placementGuard,
                            WaypointProximity waypoints, Supplier<EncounterConfig> config,
                            Supplier<Long> regionSize, Supplier<String> wildWorld,
                            Supplier<Set<String>> forbiddenWorlds) {
        this.plugin = plugin;
        this.repository = repository;
        this.templates = templates;
        this.npcs = npcs;
        this.identityResolver = identityResolver;
        this.planner = planner;
        this.placementGuard = placementGuard;
        this.waypoints = waypoints;
        this.config = config;
        this.regionSize = regionSize;
        this.wildWorld = wildWorld;
        this.forbiddenWorlds = forbiddenWorlds;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        repository.loadAll().thenAccept(list -> runSync(() -> {
            list.forEach(this::index);
            loaded = true;
            logger.info("PNJ récurrents : {} occurrence(s) chargée(s).", list.size());
        })).exceptionally(error -> {
            logger.error("Impossible de charger les occurrences de PNJ récurrents.", error);
            return null;
        });
    }

    @Override
    public void stop() {
        loaded = false;
        byId.clear();
        byInstance.clear();
        byCitizens.clear();
        siteByChunk.clear();
        busy.clear();
        retryNotBefore.clear();
        retryCount.clear();
        lastInstanceByPlayer.clear();
        lastCheckByPlayer.clear();
    }

    public Listener listener() {
        return new EncounterListener(this);
    }

    // ---- Identité ------------------------------------------------------------------------------

    /** Identité RPGQuest d'un PNJ Citizens de rencontre active, ou vide. Thread quelconque. */
    public Optional<String> npcIdOf(UUID citizensUuid) {
        EncounterSite site = byCitizens.get(citizensUuid);
        return site == null || !site.active() ? Optional.empty() : Optional.of(site.npcId());
    }

    // ---- Lecture (panel, commandes) -------------------------------------------------------------

    public List<EncounterSite> sites() {
        return byId.values().stream()
                .sorted(Comparator.comparing(EncounterSite::world).thenComparing(EncounterSite::id))
                .toList();
    }

    public Optional<EncounterSite> site(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Event> recentEvents() {
        synchronized (recentEvents) {
            return List.copyOf(recentEvents);
        }
    }

    public YamlEncounterEngine templates() {
        return templates;
    }

    // ---- Cycle de vie joueur -------------------------------------------------------------------

    void forgetPlayer(UUID playerId) {
        lastInstanceByPlayer.remove(playerId);
        lastCheckByPlayer.remove(playerId);
    }

    // ---- Entrée dans une instance de biome -----------------------------------------------------

    void handleMovement(Player player, Location to) {
        EncounterConfig cfg = config.get();
        World world = to.getWorld();
        if (!cfg.enabled() || !loaded || world == null || !npcs.citizensAvailable()
                || !EncounterWorldPolicy.allowed(world.getName(), cfg.worlds(), wildWorld.get(), forbiddenWorlds.get())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastCheckByPlayer.get(playerId);
        if (last != null && now - last < cfg.moveThrottleMillis()) {
            return;
        }
        lastCheckByPlayer.put(playerId, now);

        String biomeKey = biomeKeyAt(world, to.getBlockX(), to.getBlockY(), to.getBlockZ());
        BiomeInstanceKey instance = identityResolver.resolve(regionSize.get(), world.getName(), biomeKey,
                to.getBlockX(), to.getBlockZ());
        String instanceKey = instanceKey(world.getName(), instance.serialize());
        if (instanceKey.equals(lastInstanceByPlayer.put(playerId, instanceKey))) {
            return;
        }

        EncounterSite existing = byInstance.get(instanceKey);
        if (existing != null) {
            ensurePresent(existing);
            return;
        }
        if (backingOff(instanceKey, now) || !busy.add(instanceKey)) {
            return;
        }
        try {
            tryCreate(world, instance, instanceKey, to, cfg);
        } catch (RuntimeException error) {
            busy.remove(instanceKey);
            logger.error("Échec inattendu de génération de PNJ récurrent pour {}", instanceKey, error);
        }
    }

    /** Un chunk vient d'être chargé : réparer la rencontre qu'il contient si son PNJ a disparu. */
    void handleChunkLoad(World world, int chunkX, int chunkZ) {
        String siteId = siteByChunk.get(chunkKey(world.getName(), chunkX, chunkZ));
        if (siteId == null || !config.get().enabled()) {
            return;
        }
        EncounterSite site = byId.get(siteId);
        if (site != null) {
            // Tick suivant : Citizens fait réapparaître ses propres PNJ pendant ce même chargement.
            plugin.getServer().getScheduler().runTask(plugin, () -> ensurePresent(site));
        }
    }

    private void tryCreate(World world, BiomeInstanceKey instance, String instanceKey, Location entry,
                           EncounterConfig cfg) {
        long sitesInWorld = byId.values().stream().filter(s -> s.world().equals(world.getName())).count();
        if (sitesInWorld >= cfg.maxPerWorld()) {
            busy.remove(instanceKey);
            return;
        }
        long seed = seedOf(instanceKey);
        Optional<EncounterTemplate> template = selector.select(templates.templates(), world.getName(),
                instance.biomeKey(), seed, this::countOf);
        if (template.isEmpty()) {
            busy.remove(instanceKey); // aucun modèle pour ce biome : normal, rien à journaliser.
            return;
        }
        Optional<Location> spot = findSpot(world, instance, entry, seed, cfg);
        if (spot.isEmpty()) {
            busy.remove(instanceKey);
            scheduleRetry(instanceKey);
            record(world.getName(), instance.serialize(), "NO_SAFE_SPOT",
                    "aucun emplacement sûr parmi " + cfg.candidateAttempts() + " candidats (modèle "
                            + template.get().id() + ")");
            return;
        }
        Location at = spot.get();
        Instant now = Instant.now();
        EncounterSite site = new EncounterSite(
                EncounterSite.idFor(world.getName(), instance.biomeKey(), instance.regionX(), instance.regionZ()),
                world.getName(), instance.serialize(), instance.biomeKey(), instance.regionX(), instance.regionZ(),
                template.get().id(), template.get().npcId(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                null, -1, EncounterSite.Status.ACTIVE, "", now, now);
        repository.insertIfAbsent(site).thenAccept(inserted -> runSync(() -> {
            busy.remove(instanceKey);
            if (inserted) {
                retryCount.remove(instanceKey);
                retryNotBefore.remove(instanceKey);
                index(site);
                record(site.world(), site.biomeInstance(), "CREATED",
                        site.id() + " (" + site.templateId() + ") en " + site.x() + "," + site.y() + "," + site.z());
                ensurePresent(site);
            } else {
                // Course perdue (ligne déjà là) : réaligner la mémoire, sans rien créer.
                repository.findByInstance(site.world(), site.biomeInstance())
                        .thenAccept(found -> runSync(() -> found.ifPresent(this::index)));
            }
        })).exceptionally(error -> {
            busy.remove(instanceKey);
            logger.error("Impossible de persister la rencontre {}", site.id(), error);
            return null;
        });
    }

    private Optional<Location> findSpot(World world, BiomeInstanceKey instance, Location entry, long seed,
                                        EncounterConfig cfg) {
        List<WaypointGenerationPlanner.Candidate> candidates = planner.candidates(entry.getBlockX(),
                entry.getBlockZ(), seed, cfg.minDistance(), cfg.maxDistance(), cfg.candidateAttempts());
        for (WaypointGenerationPlanner.Candidate candidate : candidates) {
            int cx = candidate.blockX();
            int cz = candidate.blockZ();
            // Jamais de chargement de chunk pour une rencontre : un candidat hors chunk chargé est ignoré.
            if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
                continue;
            }
            if (!biomeKeyAt(world, cx, world.getHighestBlockYAt(cx, cz), cz).equals(instance.biomeKey())
                    || !identityResolver.resolve(regionSize.get(), world.getName(), instance.biomeKey(), cx, cz)
                    .equals(instance)) {
                continue;
            }
            Optional<Location> safe = RandomSafeLocationFinder.findAccessibleColumn(world, cx, cz);
            if (safe.isEmpty()) {
                continue;
            }
            Location at = safe.get();
            if (!placementGuard.isBuildable(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())
                    || waypoints.hasWaypointWithin(world.getName(), at.getBlockX(), at.getBlockZ(),
                    cfg.waypointClearance())
                    || encounterWithin(world.getName(), at.getBlockX(), at.getBlockZ(), cfg.minimumSpacing())) {
                continue;
            }
            return Optional.of(at);
        }
        return Optional.empty();
    }

    // ---- Présence du PNJ (création, réadoption, réparation) ------------------------------------

    /**
     * Garantit qu'une rencontre active a exactement son PNJ Citizens, si son chunk est chargé.
     * Ordre : PNJ connu encore présent → rien ; sinon PNJ marqué à réadopter ; sinon création.
     */
    private void ensurePresent(EncounterSite site) {
        if (!site.active() || !config.get().enabled() || !npcs.citizensAvailable()) {
            return;
        }
        if (site.hasCitizens() && npcs.citizensExists(site.citizensUuid())) {
            return;
        }
        World world = plugin.getServer().getWorld(site.world());
        if (world == null || !world.isChunkLoaded(site.x() >> 4, site.z() >> 4)) {
            return; // réparé au prochain chargement du chunk (handleChunkLoad).
        }
        String key = instanceKey(site.world(), site.biomeInstance());
        if (backingOff(key, System.currentTimeMillis()) || !busy.add(key)) {
            return;
        }
        try {
            Optional<CitizensNpc> npc = adoptMarked(site);
            String outcome = npc.isPresent() ? "ADOPTED" : (site.hasCitizens() ? "REPAIRED" : "SPAWNED");
            if (npc.isEmpty()) {
                EncounterTemplate template = templates.find(site.templateId()).orElse(null);
                String name = template != null ? template.displayName() : site.npcId();
                Location at = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
                npc = npcs.createMarkedCitizensNpc(name, at, CITIZENS_MARK, site.id());
            }
            if (npc.isEmpty()) {
                busy.remove(key);
                scheduleRetry(key);
                fail(site, "Citizens a refusé de faire apparaître le PNJ");
                return;
            }
            CitizensNpc created = npc.get();
            npcs.setLookClose(created.uuid(), true, null);
            EncounterSite updated = site.withCitizens(created.uuid(), created.numericId(), Instant.now());
            index(updated);
            repository.updateCitizens(site.id(), created.uuid(), created.numericId(), updated.updatedAt())
                    .whenComplete((ok, error) -> {
                        busy.remove(key);
                        if (error != null) {
                            logger.error("Impossible d'enregistrer le PNJ de la rencontre {}", site.id(), error);
                        }
                    });
            retryCount.remove(key);
            retryNotBefore.remove(key);
            record(site.world(), site.biomeInstance(), outcome, site.id() + " → Citizens #" + created.numericId());
        } catch (RuntimeException error) {
            busy.remove(key);
            scheduleRetry(key);
            fail(site, error.getClass().getSimpleName() + " : " + error.getMessage());
            logger.error("Échec de matérialisation de la rencontre {}", site.id(), error);
        }
    }

    /** Un PNJ marqué pour cette occurrence existe déjà (arrêt brutal avant l'enregistrement) : on le garde. */
    private Optional<CitizensNpc> adoptMarked(EncounterSite site) {
        List<CitizensNpc> marked = npcs.findMarkedCitizensNpcs(CITIZENS_MARK, site.id());
        if (marked.isEmpty()) {
            return Optional.empty();
        }
        // Plusieurs PNJ marqués ne devraient jamais exister ; on n'en garde qu'un, les autres sont retirés.
        for (int i = 1; i < marked.size(); i++) {
            npcs.destroyCitizensNpc(marked.get(i).numericId(), marked.get(i).uuid());
        }
        return Optional.of(marked.get(0));
    }

    private void fail(EncounterSite site, String reason) {
        EncounterSite updated = site.withError(reason, Instant.now());
        index(updated);
        repository.updateError(site.id(), reason, updated.updatedAt());
        record(site.world(), site.biomeInstance(), "FAILED", site.id() + " : " + reason);
    }

    // ---- Actions administrateur (thread principal) ---------------------------------------------

    /**
     * Nouvelle tentative : réactive l'occurrence, efface son délai et la matérialise tout de suite
     * si son chunk est chargé (sinon au prochain chargement). N'affecte aucune autre occurrence.
     */
    public CompletableFuture<ActionResult> retry(String siteId) {
        EncounterSite site = byId.get(siteId);
        if (site == null) {
            return done(false, "UNKNOWN", "Occurrence inconnue : " + siteId);
        }
        String key = instanceKey(site.world(), site.biomeInstance());
        retryCount.remove(key);
        retryNotBefore.remove(key);
        EncounterSite active = site.withStatus(EncounterSite.Status.ACTIVE, Instant.now());
        index(active);
        return repository.updateStatus(siteId, EncounterSite.Status.ACTIVE, active.updatedAt())
                .thenCompose(ignored -> supplySync(() -> {
                    ensurePresent(byId.getOrDefault(siteId, active));
                    EncounterSite now = byId.get(siteId);
                    boolean present = now != null && now.hasCitizens() && npcs.citizensExists(now.citizensUuid());
                    return new ActionResult(true, present ? "PRESENT" : "PENDING", present
                            ? "PNJ présent pour " + siteId + "."
                            : "Réactivée ; le PNJ apparaîtra au prochain chargement de sa zone.");
                }));
    }

    /** Désactive une occurrence : son PNJ est retiré, l'instance ne génère plus rien. */
    public CompletableFuture<ActionResult> disable(String siteId) {
        EncounterSite site = byId.get(siteId);
        if (site == null) {
            return done(false, "UNKNOWN", "Occurrence inconnue : " + siteId);
        }
        boolean destroyed = destroyCitizensOf(site);
        EncounterSite disabled = site.withStatus(EncounterSite.Status.DISABLED, Instant.now())
                .withCitizens(null, -1, Instant.now());
        index(disabled);
        return repository.updateStatus(siteId, EncounterSite.Status.DISABLED, disabled.updatedAt())
                .thenCompose(ignored -> repository.updateCitizens(siteId, null, -1, disabled.updatedAt()))
                .thenApply(ignored -> {
                    record(site.world(), site.biomeInstance(), "DISABLED", siteId);
                    return new ActionResult(true, "DISABLED", "Occurrence " + siteId + " désactivée"
                            + (destroyed ? ", PNJ retiré." : "."));
                });
    }

    /**
     * Retire définitivement une occurrence (PNJ + ligne). L'instance pourra accueillir une nouvelle
     * rencontre à la prochaine visite. Les quêtes déjà acceptées ne sont pas touchées.
     */
    public CompletableFuture<ActionResult> remove(String siteId) {
        EncounterSite site = byId.get(siteId);
        if (site == null) {
            return done(false, "UNKNOWN", "Occurrence inconnue : " + siteId);
        }
        boolean destroyed = destroyCitizensOf(site);
        unindex(site);
        return repository.delete(siteId).thenApply(ignored -> {
            record(site.world(), site.biomeInstance(), "REMOVED", siteId);
            return new ActionResult(true, "REMOVED", "Occurrence " + siteId + " retirée"
                    + (destroyed ? " avec son PNJ." : "."));
        });
    }

    /** Efface tous les délais d'attente : les instances en échec seront retentées à la prochaine visite. */
    public int clearBackoffs() {
        int count = retryNotBefore.size();
        retryNotBefore.clear();
        retryCount.clear();
        return count;
    }

    private boolean destroyCitizensOf(EncounterSite site) {
        boolean destroyed = false;
        if (site.hasCitizens()) {
            destroyed = npcs.destroyCitizensNpc(site.citizensId(), site.citizensUuid());
        }
        for (CitizensNpc marked : npcs.findMarkedCitizensNpcs(CITIZENS_MARK, site.id())) {
            destroyed |= npcs.destroyCitizensNpc(marked.numericId(), marked.uuid());
        }
        return destroyed;
    }

    // ---- Index ---------------------------------------------------------------------------------

    private void index(EncounterSite site) {
        EncounterSite previous = byId.put(site.id(), site);
        if (previous != null && previous.citizensUuid() != null
                && !previous.citizensUuid().equals(site.citizensUuid())) {
            byCitizens.remove(previous.citizensUuid());
        }
        byInstance.put(instanceKey(site.world(), site.biomeInstance()), site);
        siteByChunk.put(chunkKey(site.world(), site.x() >> 4, site.z() >> 4), site.id());
        if (site.citizensUuid() != null) {
            byCitizens.put(site.citizensUuid(), site);
        }
    }

    private void unindex(EncounterSite site) {
        byId.remove(site.id());
        byInstance.remove(instanceKey(site.world(), site.biomeInstance()));
        siteByChunk.remove(chunkKey(site.world(), site.x() >> 4, site.z() >> 4));
        if (site.citizensUuid() != null) {
            byCitizens.remove(site.citizensUuid());
        }
    }

    private int countOf(String templateId) {
        return (int) byId.values().stream().filter(s -> s.templateId().equals(templateId)).count();
    }

    private boolean encounterWithin(String world, int x, int z, int radius) {
        if (radius <= 0) {
            return false;
        }
        long max = (long) radius * radius;
        for (EncounterSite site : byId.values()) {
            if (site.world().equals(world)) {
                long dx = site.x() - x;
                long dz = site.z() - z;
                if (dx * dx + dz * dz < max) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- Délais et journal ---------------------------------------------------------------------

    private boolean backingOff(String key, long now) {
        Long notBefore = retryNotBefore.get(key);
        return notBefore != null && now < notBefore;
    }

    private void scheduleRetry(String key) {
        int attempt = retryCount.merge(key, 1, Integer::sum);
        long backoff = RETRY_BACKOFF_MILLIS[Math.min(attempt - 1, RETRY_BACKOFF_MILLIS.length - 1)];
        retryNotBefore.put(key, System.currentTimeMillis() + backoff);
    }

    private void record(String world, String instance, String outcome, String detail) {
        logger.info("[PNJ récurrents] {} {} — {}", outcome, instance, detail);
        synchronized (recentEvents) {
            recentEvents.addFirst(new Event(Instant.now(), world, instance, outcome, detail));
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeLast();
            }
        }
    }

    // ---- Utilitaires ---------------------------------------------------------------------------

    /** Graine propre aux rencontres : jamais la même séquence de candidats que le waypoint de l'instance. */
    static long seedOf(String instanceKey) {
        return ((long) instanceKey.hashCode() << 32) ^ 0x3C0A7E5L;
    }

    private static String instanceKey(String world, String biomeInstance) {
        return world + "#" + biomeInstance;
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + "," + chunkZ;
    }

    private static String biomeKeyAt(World world, int x, int y, int z) {
        try {
            return world.getBiome(x, y, z).getKey().toString();
        } catch (RuntimeException error) {
            return "minecraft:the_void";
        }
    }

    private void runSync(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private <T> CompletableFuture<T> supplySync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runSync(() -> {
            try {
                future.complete(supplier.get());
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private static CompletableFuture<ActionResult> done(boolean ok, String code, String message) {
        return CompletableFuture.completedFuture(new ActionResult(ok, code, message));
    }
}
