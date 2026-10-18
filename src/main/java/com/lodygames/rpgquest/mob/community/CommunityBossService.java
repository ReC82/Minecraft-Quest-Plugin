package com.lodygames.rpgquest.mob.community;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.city.CityIndex;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Issue #175 — boss communautaires du Wild : un scheduler unique, persisté et borné, construit
 * <strong>sur</strong> le moteur de mobs spéciaux existant ({@link SpecialMobService#apply} pose les
 * attributs, le nom, la PDC, la barre de vie et applique les drops du profil à la mort) — jamais un
 * second moteur de mobs.
 *
 * <h2>Verrou d'instance</h2>
 * Chaque boss actif a un verrou persistant ({@link CommunityBossInstance}, fichier de données YAML,
 * aucune table) et une PDC dédiée sur l'entité ({@link #INSTANCE_KEY_NAME}). Le plafond compte les
 * verrous, donc aussi les boss restés dans un chunk déchargé ou d'avant un redémarrage — jamais les
 * mobs vanilla. Un verrou n'est libéré que sur preuve :
 * <ul>
 *   <li>mort de l'entité ({@link EntityDeathEvent}) ;</li>
 *   <li>retrait depuis PlugAdmin ;</li>
 *   <li>entité absente alors que le chunk de sa dernière position connue a ses entités chargées
 *       (supprimée hors de notre contrôle) — vérifié à la réconciliation périodique et au
 *       chargement des entités, <em>sans jamais charger de chunk</em>.</li>
 * </ul>
 * Une entité marquée retrouvée sans verrou (crash entre un déplacement et la sauvegarde) est
 * réadoptée sous le plafond, sinon supprimée : jamais de doublon.
 *
 * <h2>Choix de l'emplacement (issue #471)</h2>
 * Le boss apparaît dans le Wild <strong>même si aucun joueur n'y est</strong> (tous au Hub ou dans les
 * Claims) : les repères de recherche sont les <em>waypoints actifs</em> des mondes Wild autorisés par le
 * profil, plus les joueurs présents dans le Wild. Autour d'un repère tiré au hasard, quelques colonnes
 * sont essayées ; un chunk non chargé est chargé <em>ponctuellement et de façon asynchrone</em>
 * ({@code World#getChunkAtAsync}, sans génération de terrain neuf, au plus
 * {@link #MAX_CHUNK_LOADS_PER_ANCHOR} par repère), jamais retenu : il se décharge normalement ensuite,
 * avec le boss persistant dedans. Sans aucun repère, rien n'apparaît et la raison est expliquée.
 *
 * <h2>Coût</h2>
 * Une seule tâche toutes les {@link #TICK_PERIOD_TICKS} ticks : réconciliation bornée au nombre de
 * verrous (≤ 10) et, à échéance seulement, une recherche bornée ({@link #ANCHORS_TRIED} repères ×
 * {@link #COLUMNS_TRIED_PER_ANCHOR} colonnes). Une tâche de contour toutes les
 * {@link #GLOW_PERIOD_TICKS} ticks, bornée aux boss chargés. Aucun parcours global d'entités, aucun
 * chargement forcé permanent.
 */
public final class CommunityBossService implements PluginService, Listener {

    public static final String INSTANCE_KEY_NAME = "community-boss-instance";

    static final long TICK_PERIOD_TICKS = 20L * 20; // 20 s
    private static final long JOIN_REMINDER_DELAY_TICKS = 60L;
    static final int MIN_DISTANCE_FROM_PLAYER = 40;
    static final int MAX_DISTANCE_FROM_PLAYER = 96;
    static final int MIN_CLEARANCE_FROM_ANY_PLAYER = 24;
    static final int MIN_CLEARANCE_FROM_WAYPOINT = 16;
    static final int ANCHORS_TRIED = 3;
    static final int COLUMNS_TRIED_PER_ANCHOR = 16;
    static final int MAX_CHUNK_LOADS_PER_ANCHOR = 4;
    static final long GLOW_PERIOD_TICKS = 40L; // 2 s

    /**
     * Recherche d'une colonne sûre autour d'un centre ; injectable pour les tests. Le résultat est
     * toujours délivré sur le thread principal (chargement de chunk asynchrone de Paper).
     */
    @FunctionalInterface
    public interface SpotFinder {
        CompletableFuture<Optional<Location>> find(World world, Location center, Predicate<Location> accept,
                                                   RandomGenerator random, boolean surfaceOnly);
    }

    /** Application du profil par le moteur de mobs spéciaux ; remplaçable en test seulement. */
    @FunctionalInterface
    interface ProfileApplier {
        void apply(LivingEntity entity, SpecialMobDefinition def, boolean test);
    }

    /** Résultat lisible d'une tentative (scheduler, test ou retrait), relayé tel quel à PlugAdmin. */
    public record Outcome(boolean ok, String code, String message, CommunityBossInstance instance) {
        static Outcome fail(String code, String message) {
            return new Outcome(false, code, message, null);
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final SpecialMobRegistry registry;
    private final SpecialMobService mobService;
    private final CommunityBossSettingsStore settingsStore;
    private final CommunityBossStateStore stateStore;
    private final Predicate<World> wildWorld;
    private final Supplier<Set<String>> protectedWorlds;
    private final ZoneRegistry zoneRegistry;
    private final Supplier<CityIndex> cities;
    private final Supplier<Collection<Waypoint>> waypoints;
    private final RandomGenerator random;
    private final LongSupplier clock;
    private final Executor io;
    private final SpotFinder spotFinder;
    private final NamespacedKey instanceKey;
    private final List<CommunityBossHooks> hooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    // État possédé par le thread principal ; publié en instantané immuable pour les lectures panel.
    private final Map<UUID, CommunityBossInstance> instances = new LinkedHashMap<>();
    private final Set<UUID> retired = new LinkedHashSet<>();
    private Long nextDue;
    private long lastSpawn;
    private long lastAttempt;
    private String lastOutcome = "";
    private final AtomicLong revision = new AtomicLong();
    private volatile CommunityBossState published = CommunityBossState.empty();
    private BukkitTask task;
    private BukkitTask glowTask;
    private boolean searching;
    private ProfileApplier applier;

    public CommunityBossService(Plugin plugin, Logger logger, SpecialMobRegistry registry,
                                SpecialMobService mobService, CommunityBossSettingsStore settingsStore,
                                CommunityBossStateStore stateStore, Predicate<World> wildWorld,
                                Supplier<Set<String>> protectedWorlds, ZoneRegistry zoneRegistry,
                                Supplier<CityIndex> cities, Supplier<Collection<Waypoint>> waypoints,
                                RandomGenerator random, LongSupplier clock, Executor io, SpotFinder spotFinder) {
        this.plugin = plugin;
        this.logger = logger;
        this.registry = registry;
        this.mobService = mobService;
        this.settingsStore = settingsStore;
        this.stateStore = stateStore;
        this.wildWorld = wildWorld;
        this.protectedWorlds = protectedWorlds;
        this.zoneRegistry = zoneRegistry;
        this.cities = cities;
        this.waypoints = waypoints;
        this.random = random;
        this.clock = clock;
        this.io = io;
        this.spotFinder = spotFinder;
        this.instanceKey = new NamespacedKey(plugin, INSTANCE_KEY_NAME);
        this.applier = (entity, def, test) -> {
            if (test) {
                mobService.applyTestInstance(entity, def);
            } else {
                mobService.apply(entity, def);
            }
        };
    }

    /** Tests : MockBukkit n'implémente pas tout ce que {@link SpecialMobService#apply} appelle. */
    void applyProfilesWith(ProfileApplier testApplier) {
        this.applier = testApplier;
    }

    /**
     * Recherche par défaut (#471) : colonnes tirées autour du repère ; un chunk déjà chargé est lu
     * directement, un chunk non chargé est chargé ponctuellement en asynchrone, <em>sans génération</em>
     * ({@code getChunkAtAsync(x, z, false)} : un chunk jamais généré est simplement écarté), au plus
     * {@link #MAX_CHUNK_LOADS_PER_ANCHOR} fois. Aucun ticket n'est conservé.
     */
    public static SpotFinder targetedChunkLoads() {
        return (world, center, accept, random, surfaceOnly) ->
                tryColumns(world, center, accept, random, surfaceOnly, 0, 0);
    }

    private static CompletableFuture<Optional<Location>> tryColumns(World world, Location center,
                                                                    Predicate<Location> accept, RandomGenerator random,
                                                                    boolean surfaceOnly, int attempt, int loads) {
        for (int next = attempt + 1; next <= COLUMNS_TRIED_PER_ANCHOR; next++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = MIN_DISTANCE_FROM_PLAYER
                    + random.nextDouble() * (MAX_DISTANCE_FROM_PLAYER - MIN_DISTANCE_FROM_PLAYER);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            // Les voisins lus par la règle de terrain doivent rester dans le même chunk : jamais de
            // lecture qui forcerait le chargement d'un chunk voisin.
            if (!SurfaceSpot.fitsInChunk(x, z)
                    || !world.getWorldBorder().isInside(new Location(world, x + 0.5, 64, z + 0.5))) {
                continue;
            }
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                Optional<Location> spot = evaluate(world, x, z, surfaceOnly, accept);
                if (spot.isPresent()) {
                    return CompletableFuture.completedFuture(spot);
                }
                continue;
            }
            if (loads >= MAX_CHUNK_LOADS_PER_ANCHOR) {
                continue;
            }
            final int done = next;
            return world.getChunkAtAsync(x >> 4, z >> 4, false).thenCompose(chunk -> {
                if (chunk != null) {
                    Optional<Location> spot = evaluate(world, x, z, surfaceOnly, accept);
                    if (spot.isPresent()) {
                        return CompletableFuture.completedFuture(spot);
                    }
                }
                return tryColumns(world, center, accept, random, surfaceOnly, done, loads + 1);
            });
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /** Règle de terrain puis règles de placement ; le chunk de (x, z) est chargé. */
    static Optional<Location> evaluate(World world, int x, int z, boolean surfaceOnly, Predicate<Location> accept) {
        Optional<Location> spot = surfaceOnly ? SurfaceSpot.find(world, x, z)
                : RandomSafeLocationFinder.findAccessibleColumn(world, x, z);
        return spot.filter(accept);
    }

    // ---- Cycle de vie -------------------------------------------------------------------------

    @Override
    public void start() {
        CommunityBossState loaded = stateStore.load();
        for (CommunityBossInstance i : loaded.instances()) {
            instances.put(i.entityId(), i);
        }
        retired.addAll(loaded.retired());
        lastSpawn = loaded.lastSpawnMillis();
        lastAttempt = loaded.lastAttemptMillis();
        lastOutcome = loaded.lastOutcome();
        long now = clock.getAsLong();
        nextDue = CommunityBossSchedule.armOnStart(now, loaded.nextDueMillis(), settings().intervalMillis());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS);
        glowTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshGlow, GLOW_PERIOD_TICKS, GLOW_PERIOD_TICKS);
        persist();
        logger.info("Boss communautaires : scheduler {} ({} verrou(s) restauré(s), prochaine échéance dans {} s).",
                settings().enabled() ? "actif" : "en pause", instances.size(), (nextDue - now) / 1000);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (glowTask != null) {
            glowTask.cancel();
            glowTask = null;
        }
        // Plugin arrêté ou retiré : aucun boss ne doit rester allumé sans plus personne pour l'éteindre.
        for (CommunityBossInstance i : instances.values()) {
            Entity entity = Bukkit.getEntity(i.entityId());
            if (entity != null && entity.isValid()) {
                entity.setGlowing(false);
            }
        }
        // Arrêt du plugin : plus de tâche asynchrone possible, dernière écriture synchrone.
        try {
            stateStore.save(buildState(), revision.incrementAndGet());
        } catch (IOException e) {
            logger.error("Sauvegarde finale de l'état des boss communautaires impossible : {}", e.getMessage());
        }
    }

    public void addHooks(CommunityBossHooks hook) {
        hooks.add(hook);
    }

    public NamespacedKey instanceKey() {
        return instanceKey;
    }

    public CommunityBossSettings settings() {
        return settingsStore.current();
    }

    /** Instantané immuable, lisible depuis n'importe quel thread (PlugAdmin). */
    public CommunityBossState snapshot() {
        return published;
    }

    // ---- Tâche périodique -----------------------------------------------------------------------

    void tick() {
        reconcileLoaded();
        CommunityBossSettings settings = settings();
        long now = clock.getAsLong();
        if (settings.enabled() && !searching && CommunityBossSchedule.isDue(now, nextDue)) {
            nextDue = CommunityBossSchedule.afterAttempt(now, settings.intervalMillis());
            spawn(Optional.empty(), Optional.empty(), false).thenAccept(outcome -> {
                lastAttempt = now;
                lastOutcome = outcome.message();
                if (outcome.ok()) {
                    lastSpawn = now;
                } else {
                    logger.info("Boss communautaire : pas d'apparition à cette échéance — {} ({})",
                            outcome.message(), outcome.code());
                }
                persist();
            });
        }
    }

    /**
     * Contour lumineux (#471) : allumé seulement quand un joueur du même monde est à moins de
     * {@link CommunityBossSettings#glowRadius()} blocs, éteint sinon ou si l'option est désactivée.
     * Borné aux boss chargés (≤ plafond), jamais un chargement de chunk.
     */
    void refreshGlow() {
        CommunityBossSettings settings = settings();
        double radiusSq = (double) settings.glowRadius() * settings.glowRadius();
        for (CommunityBossInstance i : instances.values()) {
            Entity entity = Bukkit.getEntity(i.entityId());
            if (entity == null || !entity.isValid()) {
                continue;
            }
            boolean near = false;
            if (settings.glow()) {
                Location l = entity.getLocation();
                for (Player p : entity.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(l) <= radiusSq) {
                        near = true;
                        break;
                    }
                }
            }
            if (entity.isGlowing() != near) {
                entity.setGlowing(near);
            }
        }
    }

    /**
     * Rafraîchit la position connue des boss chargés et libère les verrous dont l'entité a disparu
     * d'un chunk chargé. Borné au nombre de verrous ; ne charge jamais de chunk.
     */
    void reconcileLoaded() {
        boolean dirty = false;
        for (CommunityBossInstance i : List.copyOf(instances.values())) {
            Entity entity = Bukkit.getEntity(i.entityId());
            if (entity != null && entity.isValid()) {
                Location l = entity.getLocation();
                if (l.getBlockX() != i.x() || l.getBlockY() != i.y() || l.getBlockZ() != i.z()
                        || !l.getWorld().getName().equals(i.world())) {
                    instances.put(i.entityId(), i.movedTo(l.getWorld().getName(),
                            l.getBlockX(), l.getBlockY(), l.getBlockZ()));
                    dirty |= (l.getBlockX() >> 4) != i.chunkX() || (l.getBlockZ() >> 4) != i.chunkZ();
                }
                continue;
            }
            World world = Bukkit.getWorld(i.world());
            if (world != null && world.isChunkLoaded(i.chunkX(), i.chunkZ())
                    && world.getChunkAt(i.chunkX(), i.chunkZ()).isEntitiesLoaded()) {
                release(i, "entité introuvable dans son chunk chargé");
                dirty = true;
            }
        }
        if (dirty) {
            persist();
        }
    }

    // ---- Apparition -------------------------------------------------------------------------------

    /**
     * Apparition de test depuis PlugAdmin : même chemin que le scheduler (placement sûr, annonce,
     * verrou), marquée « test », hors plafond, sans toucher à l'échéance ni à « dernière apparition ».
     */
    public CompletableFuture<Outcome> testSpawn(String profileId, String playerName) {
        Optional<NamespacedKey> profile = Optional.empty();
        if (profileId != null && !profileId.isBlank()) {
            String raw = profileId.trim().toLowerCase(Locale.ROOT);
            // Forme courte « ogre » = « rpgquest:ogre », comme partout ailleurs (jamais minecraft:ogre).
            NamespacedKey key = NamespacedKey.fromString(raw.contains(":") ? raw : "rpgquest:" + raw);
            if (key == null) {
                return done(Outcome.fail("INVALID", "Identifiant de profil invalide."));
            }
            profile = Optional.of(key);
        }
        Optional<Player> near = Optional.empty();
        if (playerName != null && !playerName.isBlank()) {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) {
                return done(Outcome.fail("OFFLINE", "Le joueur « " + playerName + " » n'est pas connecté."));
            }
            near = Optional.of(player);
        }
        return spawn(profile, near, true);
    }

    /**
     * Apparition complète (scheduler, test au hasard ou près d'un joueur). Ne complète jamais en
     * erreur : toute défaillance devient un {@link Outcome} lisible. Une seule recherche à la fois.
     */
    CompletableFuture<Outcome> spawn(Optional<NamespacedKey> forcedProfile, Optional<Player> near, boolean test) {
        if (searching) {
            return done(Outcome.fail("BUSY", "Une recherche d'emplacement est déjà en cours : réessayez dans "
                    + "quelques secondes."));
        }
        CommunityBossSettings settings = settings();
        if (!test && instances.size() >= settings.maxActive()) {
            return done(capReached(settings));
        }
        List<SpecialMobDefinition> eligible;
        if (forcedProfile.isPresent()) {
            Optional<SpecialMobDefinition> def = registry.find(forcedProfile.get());
            if (def.isEmpty()) {
                return done(Outcome.fail("UNKNOWN_MOB", "Profil introuvable : " + forcedProfile.get() + "."));
            }
            if (!def.get().isCommunityBoss()) {
                return done(Outcome.fail("NOT_COMMUNITY", "« " + plain(def.get().displayName()) + " » n'est pas un "
                        + "boss communautaire activé (catégorie Boss, mode « Boss communautaire », profil activé)."));
            }
            eligible = List.of(def.get());
        } else {
            eligible = registry.definitions().stream().filter(SpecialMobDefinition::isCommunityBoss).toList();
            if (eligible.isEmpty()) {
                return done(Outcome.fail("NO_PROFILE", "Aucun profil Boss en mode « Boss communautaire » activé."));
            }
        }

        final List<Location> anchors;
        if (near.isPresent()) {
            // Apparition « près de ce joueur » : la seule méthode qui exige un joueur dans le Wild.
            if (!wildPlayers().contains(near.get())) {
                return done(Outcome.fail("NOT_IN_WILD", near.get().getName() + " doit être dans un monde Wild "
                        + "(ni le Hub, ni les Claims) et en mode survie ou aventure."));
            }
            anchors = List.of(near.get().getLocation());
        } else {
            anchors = anchors();
        }
        List<SpecialMobDefinition> placeable = eligible.stream()
                .filter(def -> anchors.stream().anyMatch(a -> worldAllowed(def, a.getWorld()))).toList();
        if (placeable.isEmpty()) {
            return done(Outcome.fail("NO_ANCHOR", anchors.isEmpty()
                    ? "Aucun repère dans le Wild : ni waypoint actif dans un monde Wild, ni joueur dans le Wild. "
                    + "Créez au moins un waypoint dans le Wild : rien n'a été annoncé."
                    : "Aucun waypoint actif (ni joueur) dans un monde autorisé par ce(s) profil(s) : vérifiez les "
                    + "mondes du profil ou créez un waypoint dans ce monde. Rien n'a été annoncé."));
        }
        SpecialMobDefinition def = CommunityBossPicker.pick(placeable, random).orElseThrow();
        List<Location> usable = new ArrayList<>(anchors.stream().filter(a -> worldAllowed(def, a.getWorld())).toList());
        java.util.Collections.shuffle(usable, new java.util.Random(random.nextLong()));

        searching = true;
        CompletableFuture<Optional<Location>> search;
        try {
            search = findSpot(def, usable.subList(0, Math.min(ANCHORS_TRIED, usable.size())), 0,
                    settings.surfaceOnly());
        } catch (RuntimeException e) {
            search = CompletableFuture.failedFuture(e);
        }
        return search.handle((spot, err) -> {
            searching = false;
            if (err != null) {
                logger.warn("Boss communautaire : recherche d'emplacement en échec : {}", err.toString());
                return Outcome.fail("SEARCH_FAILED", "La recherche d'emplacement a échoué (" + err.getClass()
                        .getSimpleName() + ") : rien n'a été annoncé, nouvel essai à la prochaine échéance.");
            }
            if (spot.isEmpty()) {
                return Outcome.fail("NO_SAFE_LOCATION", "Aucun emplacement sûr et autorisé trouvé autour des "
                        + "repères essayés (biome, zone protégée, ville, waypoint, terrain"
                        + (settings.surfaceOnly() ? ", surface naturelle à ciel ouvert" : "")
                        + ") : rien n'a été annoncé, nouvel essai à la prochaine échéance.");
            }
            // Le plafond a pu être atteint pendant le chargement asynchrone : jamais de dépassement.
            if (!test && instances.size() >= settings().maxActive()) {
                return capReached(settings());
            }
            return place(def, spot.get(), test);
        });
    }

    /**
     * Issue #171 — emplacement d'un boss de <strong>quête</strong> : exactement les règles de placement
     * sûr des boss communautaires ({@link #acceptable} : monde Wild autorisé par le profil, biome, zone,
     * ville, distance aux waypoints et aux joueurs ; surface naturelle si le réglage l'exige), avec la
     * même recherche bornée et ses chargements de chunk ponctuels asynchrones. Repères : le joueur
     * propriétaire d'abord s'il est dans le Wild, puis les waypoints actifs du Wild au hasard.
     *
     * <p>Ne touche ni aux verrous, ni au plafond, ni à l'échéance des boss communautaires : seul le
     * placement est partagé, jamais un second moteur de recherche. Résultat délivré sur le thread
     * principal ; vide si aucun repère ou aucun emplacement sûr.</p>
     */
    public CompletableFuture<Optional<Location>> findQuestBossSpot(SpecialMobDefinition def, Player owner) {
        List<Location> usable = new ArrayList<>();
        for (Waypoint w : waypoints.get()) {
            World world = w.active() ? Bukkit.getWorld(w.world()) : null;
            if (world != null && isWildWorld(world) && worldAllowed(def, world)) {
                usable.add(new Location(world, w.x() + 0.5, w.y(), w.z() + 0.5));
            }
        }
        java.util.Collections.shuffle(usable, new java.util.Random(random.nextLong()));
        if (owner != null && wildPlayers().contains(owner) && worldAllowed(def, owner.getWorld())) {
            usable.addFirst(owner.getLocation());
        }
        try {
            return findSpot(def, usable.subList(0, Math.min(ANCHORS_TRIED + 1, usable.size())), 0,
                    settings().surfaceOnly());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Outcome capReached(CommunityBossSettings settings) {
        return Outcome.fail("CAP_REACHED", "Plafond atteint (" + instances.size() + "/" + settings.maxActive()
                + " boss actif(s)) : aucune nouvelle apparition.");
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    /**
     * Repères de recherche (#471) : waypoints actifs des mondes Wild chargés, puis joueurs du Wild.
     * Ne dépend jamais de la présence d'un joueur : les waypoints suffisent.
     */
    private List<Location> anchors() {
        List<Location> result = new ArrayList<>();
        for (Waypoint w : waypoints.get()) {
            World world = w.active() ? Bukkit.getWorld(w.world()) : null;
            if (world != null && isWildWorld(world)) {
                result.add(new Location(world, w.x() + 0.5, w.y(), w.z() + 0.5));
            }
        }
        for (Player p : wildPlayers()) {
            result.add(p.getLocation());
        }
        return result;
    }

    private Outcome place(SpecialMobDefinition def, Location location, boolean test) {
        Entity spawned = location.getWorld().spawnEntity(location, def.entityType());
        if (!(spawned instanceof LivingEntity living) || !spawned.isValid()) {
            if (spawned.isValid()) {
                spawned.remove();
            }
            return Outcome.fail("SPAWN_REFUSED", "L'apparition a été refusée par une protection du serveur : rien "
                    + "n'a été annoncé.");
        }
        applier.apply(living, def, test);
        living.setPersistent(true);
        living.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, living.getUniqueId().toString());

        CommunityBossInstance instance = describe(living, def, test);
        instances.put(instance.entityId(), instance);
        persist();
        Bukkit.getServer().broadcast(CommunityBossMessages.spawned(instance, def.displayName()));
        logger.info("Boss communautaire « {} » apparu{} en {} {} {} {} (biome {}, waypoint {}).", def.id(),
                test ? " (test)" : "", instance.world(), instance.x(), instance.y(), instance.z(), instance.biome(),
                instance.waypointName() == null ? "aucun à portée" : instance.waypointName());
        fire(h -> h.onSpawned(instance));
        return new Outcome(true, "SPAWNED", plain(def.displayName()) + " est apparu" + (test ? " (test)" : "")
                + " dans le biome " + prettyBiome(instance.biome())
                + (instance.waypointName() == null ? ", loin de tout waypoint." : ", près du waypoint "
                + instance.waypointName() + "."), instance);
    }

    private CommunityBossInstance describe(LivingEntity living, SpecialMobDefinition def, boolean test) {
        Location l = living.getLocation();
        String biome = l.getBlock().getBiome().getKey().toString();
        Optional<WaypointHint> hint = WaypointHint.nearest(waypoints.get(), l.getWorld().getName(),
                l.getBlockX(), l.getBlockZ());
        return new CommunityBossInstance(living.getUniqueId(), def.id().asString(), plain(def.displayName()),
                l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), biome,
                hint.map(WaypointHint::waypointId).orElse(null), hint.map(WaypointHint::waypointName).orElse(null),
                hint.map(WaypointHint::distance).orElse(0), hint.map(WaypointHint::direction).orElse(null),
                clock.getAsLong(), test);
    }

    /** Repères essayés l'un après l'autre (jamais deux chargements de chunk en parallèle). */
    private CompletableFuture<Optional<Location>> findSpot(SpecialMobDefinition def, List<Location> anchors, int index,
                                                           boolean surfaceOnly) {
        if (index >= anchors.size()) {
            return done(Optional.empty());
        }
        Location anchor = anchors.get(index);
        return spotFinder.find(anchor.getWorld(), anchor, candidate -> acceptable(def, candidate), random, surfaceOnly)
                .thenCompose(spot -> spot.isPresent() ? done(spot) : findSpot(def, anchors, index + 1, surfaceOnly));
    }

    /** Toutes les règles de placement sûr (hors terrain, déjà vérifié par le {@link SpotFinder}). */
    boolean acceptable(SpecialMobDefinition def, Location l) {
        World world = l.getWorld();
        if (world == null || !isWildWorld(world) || !worldAllowed(def, world)) {
            return false;
        }
        if (!def.allowedBiomes().isEmpty()) {
            String biome = l.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
            if (!def.allowedBiomes().contains(biome)) {
                return false;
            }
        }
        Optional<ZoneDefinition> zone = zoneRegistry.zoneAt(world.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (zone.isPresent() && !zone.get().flags().allowHostileSpawn()) {
            return false;
        }
        if (!def.allowedZones().isEmpty()
                && zone.map(z -> !def.allowedZones().contains(z.id().toLowerCase(Locale.ROOT))).orElse(true)) {
            return false;
        }
        CityIndex index = cities.get();
        if (index != null && index.primaryAt(world.getName(), l.getBlockX(), l.getBlockZ()).isPresent()) {
            return false;
        }
        long wpSq = (long) MIN_CLEARANCE_FROM_WAYPOINT * MIN_CLEARANCE_FROM_WAYPOINT;
        for (Waypoint w : waypoints.get()) {
            long dx = (long) w.x() - l.getBlockX();
            long dz = (long) w.z() - l.getBlockZ();
            if (w.world().equals(world.getName()) && dx * dx + dz * dz < wpSq) {
                return false;
            }
        }
        double playerSq = (double) MIN_CLEARANCE_FROM_ANY_PLAYER * MIN_CLEARANCE_FROM_ANY_PLAYER;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(l) < playerSq) {
                return false;
            }
        }
        return true;
    }

    private List<Player> wildPlayers() {
        List<Player> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                    && isWildWorld(p.getWorld())) {
                result.add(p);
            }
        }
        return result;
    }

    private boolean isWildWorld(World world) {
        String name = world.getName();
        for (String forbidden : protectedWorlds.get()) {
            if (forbidden != null && forbidden.equalsIgnoreCase(name)) {
                return false; // Hub et Claims : jamais, même si mal déclarés comme Wild.
            }
        }
        return wildWorld.test(world);
    }

    private static boolean worldAllowed(SpecialMobDefinition def, World world) {
        return def.allowedWorlds().isEmpty()
                || def.allowedWorlds().contains(world.getName().toLowerCase(Locale.ROOT));
    }

    // ---- Fin de vie ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        CommunityBossInstance instance = instances.remove(event.getEntity().getUniqueId());
        if (instance == null) {
            return;
        }
        persist();
        Player killer = event.getEntity().getKiller();
        String name = registry.find(NamespacedKey.fromString(instance.profileId()))
                .map(SpecialMobDefinition::displayName).orElse(instance.profileName());
        Bukkit.getServer().broadcast(CommunityBossMessages.defeated(name,
                killer == null ? null : killer.getName(), instance.test()));
        logger.info("Boss communautaire « {} » vaincu{}.", instance.profileId(),
                killer == null ? "" : " par " + killer.getName());
        fire(h -> h.onDefeated(instance, event.getEntity(), killer));
    }

    /** Retrait admin d'un boss précis (test ou non). Ne donne jamais de récompense. */
    public Outcome remove(UUID entityId) {
        CommunityBossInstance instance = instances.get(entityId);
        if (instance == null) {
            return Outcome.fail("NOT_FOUND", "Ce boss n'est plus actif (déjà vaincu ou retiré).");
        }
        Entity entity = Bukkit.getEntity(entityId);
        boolean deferred = entity == null;
        if (deferred) {
            retired.add(entityId); // supprimé dès que son chunk se rechargera, jamais réadopté.
        } else {
            mobService.discard(entity);
        }
        release(instance, "retiré depuis PlugAdmin");
        persist();
        return new Outcome(true, "REMOVED", instance.profileName() + " a été retiré"
                + (deferred ? " (sa zone n'est pas chargée : il disparaîtra dès qu'elle le sera)." : "."), instance);
    }

    /** Issue #169 : vrai si cette entité est un boss communautaire actif (verrou tenu, zone chargée ou non). */
    public boolean isActiveInstance(UUID entityId) {
        return instances.containsKey(entityId);
    }

    /** Issue #169 : boss communautaires actifs de ce profil, d'après l'état enregistré (zones non chargées comprises). */
    public int activeInstancesOf(String profileId) {
        return (int) instances.values().stream().filter(i -> i.profileId().equals(profileId)).count();
    }

    /** Retire toutes les apparitions de test encore actives. */
    public Outcome removeTestInstances() {
        int count = 0;
        for (CommunityBossInstance i : List.copyOf(instances.values())) {
            if (i.test() && remove(i.entityId()).ok()) {
                count++;
            }
        }
        return new Outcome(true, "REMOVED", count == 0 ? "Aucune apparition de test active."
                : count + " apparition(s) de test retirée(s).", null);
    }

    private void release(CommunityBossInstance instance, String reason) {
        instances.remove(instance.entityId());
        logger.info("Verrou du boss communautaire « {} » libéré : {}.", instance.profileId(), reason);
        fire(h -> h.onReleased(instance, reason));
    }

    // ---- Chunks / connexion ------------------------------------------------------------------------

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        boolean dirty = false;
        Set<UUID> present = new java.util.HashSet<>();
        for (Entity entity : event.getEntities()) {
            UUID id = entity.getUniqueId();
            present.add(id);
            if (retired.remove(id)) {
                mobService.discard(entity);
                dirty = true;
                continue;
            }
            if (!instances.containsKey(id) && entity.getPersistentDataContainer().has(instanceKey)
                    && entity instanceof LivingEntity living) {
                dirty |= adoptOrphan(living);
            }
        }
        String world = event.getWorld().getName();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (CommunityBossInstance i : List.copyOf(instances.values())) {
            if (i.inChunk(world, cx, cz) && !present.contains(i.entityId()) && Bukkit.getEntity(i.entityId()) == null) {
                release(i, "absent de son chunk au rechargement");
                dirty = true;
            }
        }
        if (dirty) {
            persist();
        }
    }

    /** Entité marquée sans verrou : réadoptée sous le plafond, sinon supprimée (jamais de doublon). */
    private boolean adoptOrphan(LivingEntity living) {
        Optional<SpecialMobDefinition> def = mobService.specialMobDefinition(living)
                .filter(d -> d.category() == MobCategory.BOSS);
        if (def.isPresent() && instances.size() < settings().maxActive()) {
            CommunityBossInstance instance = describe(living, def.get(), mobService.isTestInstance(living));
            instances.put(instance.entityId(), instance);
            logger.warn("Boss communautaire « {} » retrouvé sans verrou : réadopté.", instance.profileId());
        } else {
            mobService.discard(living);
            logger.warn("Boss communautaire retrouvé sans verrou au-delà du plafond (ou profil supprimé) : retiré.");
        }
        return true;
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        boolean dirty = false;
        for (Entity entity : event.getEntities()) {
            CommunityBossInstance i = instances.get(entity.getUniqueId());
            if (i == null) {
                continue;
            }
            Location l = entity.getLocation();
            instances.put(i.entityId(), i.movedTo(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            dirty = true;
        }
        if (dirty) {
            persist();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                sendReminder(player);
            }
        }, JOIN_REMINDER_DELAY_TICKS);
    }

    /** Rappel individuel des boss communautaires actifs ; rien si aucun. Jamais diffusé à tous. */
    void sendReminder(Player player) {
        List<CommunityBossInstance> active = List.copyOf(instances.values());
        for (Component line : CommunityBossMessages.joinReminder(active, i ->
                registry.find(NamespacedKey.fromString(i.profileId()))
                        .map(SpecialMobDefinition::displayName).orElse(i.profileName()))) {
            player.sendMessage(line);
        }
    }

    // ---- Réglages ----------------------------------------------------------------------------------

    /** À appeler sur le thread principal après l'enregistrement de nouveaux réglages. */
    public void settingsChanged(CommunityBossSettings previous, CommunityBossSettings current) {
        if (current.enabled() && (!previous.enabled() || previous.intervalMinutes() != current.intervalMinutes())) {
            nextDue = CommunityBossSchedule.rearm(clock.getAsLong(), nextDue, previous.enabled(),
                    current.intervalMillis());
        }
        persist();
    }

    // ---- Persistance -------------------------------------------------------------------------------

    private CommunityBossState buildState() {
        return new CommunityBossState(nextDue, lastSpawn, lastAttempt, lastOutcome,
                List.copyOf(instances.values()), Set.copyOf(retired));
    }

    /** Publie l'instantané et l'écrit hors du thread principal (révisions ordonnées). */
    private void persist() {
        CommunityBossState state = buildState();
        published = state;
        long rev = revision.incrementAndGet();
        io.execute(() -> {
            try {
                stateStore.save(state, rev);
            } catch (IOException e) {
                logger.error("Écriture de l'état des boss communautaires impossible : {}", e.getMessage());
            }
        });
    }

    private void fire(java.util.function.Consumer<CommunityBossHooks> call) {
        for (CommunityBossHooks hook : hooks) {
            try {
                call.accept(hook);
            } catch (RuntimeException e) {
                logger.error("Extension de boss communautaire en échec : {}", e.toString());
            }
        }
    }

    static String plain(String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage));
    }

    public static String prettyBiome(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return "inconnu";
        }
        int colon = biomeKey.indexOf(':');
        return (colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey).replace('_', ' ');
    }
}
