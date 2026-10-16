package com.lodygames.rpgquest.mob.quest;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.mob.MobNames;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.community.WaypointHint;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.quest.model.KillQuestBossObjective;
import com.lodygames.rpgquest.quest.progress.ObjectiveRef;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Issue #171 — boss de quête personnels : un objectif {@code KILL_QUEST_BOSS} fait apparaître, pour
 * le joueur qui l'a en cours, <strong>son</strong> boss (profil BOSS en mode QUEST), construit sur le
 * moteur de mobs spéciaux existant ({@link SpecialMobService#apply}) et placé avec les règles de
 * placement sûr des boss communautaires — jamais un second moteur.
 *
 * <h2>Politique retenue (à valider par le propriétaire, voir le rapport #171)</h2>
 * <ul>
 *   <li><b>Pour le joueur</b> = une instance <em>possédée</em> par joueur, quête, étape et objectif,
 *       dans le monde partagé (aucun monde instancié) : visible de tous, d'autres joueurs peuvent
 *       aider à le combattre.</li>
 *   <li><b>Attribution</b> : à la mort de l'instance, l'objectif est rempli pour son propriétaire s'il
 *       est connecté, vivant et à moins de {@link #CREDIT_RADIUS} blocs — quel que soit l'auteur du
 *       dernier coup. Tuer le boss d'un autre joueur, ou un mob du même profil, ne valide rien.</li>
 *   <li><b>Apparition</b> : dès que l'étape portant l'objectif devient active (à l'acceptation si
 *       c'est la première), et seulement si le propriétaire est connecté. Une seule instance par
 *       objectif ; une recherche à la fois par objectif : double-clic, nouvel essai et reconnexion ne
 *       créent jamais de doublon.</li>
 *   <li><b>Mort du joueur, déconnexion, déchargement de chunk, redémarrage</b> : le boss reste où il
 *       est (entité persistante + verrou {@code state/quest-bosses.yml}), retrouvable avec la même
 *       indication.</li>
 *   <li><b>Abandon, fin de quête, étape passée, quête supprimée</b> : le boss est retiré (sans butin),
 *       tout de suite s'il est chargé, sinon dès que son chunk se recharge.</li>
 *   <li><b>Boss mort sans attribution, disparu, ou apparition impossible</b> : le joueur est prévenu
 *       et un nouvel essai a lieu automatiquement après un délai — la quête n'est jamais
 *       silencieusement impossible.</li>
 * </ul>
 *
 * <h2>Coût</h2>
 * Réconciliation par joueur sur changement de progression (pas de sondage) ; une tâche toutes les
 * {@link #TICK_PERIOD_TICKS} ticks bornée au nombre de boss de quête actifs (≤ {@link #MAX_ACTIVE})
 * et aux joueurs connectés ayant un tel objectif. Aucun scan global d'entités, aucun chunk retenu.
 */
public final class QuestBossService implements PluginService, Listener {

    public static final String INSTANCE_KEY_NAME = "quest-boss-instance";
    /** Rayon d'attribution : le propriétaire doit être présent au combat, pas forcément le tueur. */
    public static final int CREDIT_RADIUS = 64;
    /** Plafond global de protection du serveur (tous joueurs confondus). */
    public static final int MAX_ACTIVE = 40;
    static final long TICK_PERIOD_TICKS = 20L * 30; // 30 s
    static final long RETRY_DELAY_MILLIS = 120_000L;
    static final long RESPAWN_DELAY_MILLIS = 60_000L;

    /** Côté quêtes : coutures sur {@code QuestProgressEngine}, remplaçables en test. */
    public interface QuestSide {
        boolean isLoaded(UUID playerId);

        List<ObjectiveRef> pendingQuestBosses(UUID playerId);

        boolean creditQuestBoss(Player player, NamespacedKey questId, String stepId, int objectiveIndex);
    }

    /** Recherche d'emplacement sûr ; résultat délivré sur le thread principal. */
    @FunctionalInterface
    public interface Placement {
        CompletableFuture<Optional<Location>> find(SpecialMobDefinition def, Player owner);
    }

    @FunctionalInterface
    interface ProfileApplier {
        void apply(LivingEntity entity, SpecialMobDefinition def);
    }

    private final Plugin plugin;
    private final Logger logger;
    private final SpecialMobRegistry registry;
    private final SpecialMobService mobService;
    private final QuestBossStateStore store;
    private final QuestSide quests;
    private final Placement placement;
    private final Supplier<Collection<Waypoint>> waypoints;
    private final BiPredicate<UUID, String> waypointDiscovered;
    private final LongSupplier clock;
    private final Executor io;
    private final NamespacedKey instanceKey;
    private ProfileApplier applier;

    // État possédé par le thread principal.
    private final Map<UUID, QuestBossInstance> instances = new LinkedHashMap<>();
    private final Map<String, UUID> byKey = new HashMap<>();
    private final Set<UUID> retired = new LinkedHashSet<>();
    private final Set<String> searching = new HashSet<>();
    private final Map<String, Long> notBefore = new HashMap<>();
    private final Map<String, String> problems = new HashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private BukkitTask task;

    public QuestBossService(Plugin plugin, Logger logger, SpecialMobRegistry registry, SpecialMobService mobService,
                            QuestBossStateStore store, QuestSide quests, Placement placement,
                            Supplier<Collection<Waypoint>> waypoints, BiPredicate<UUID, String> waypointDiscovered,
                            LongSupplier clock, Executor io) {
        this.plugin = plugin;
        this.logger = logger;
        this.registry = registry;
        this.mobService = mobService;
        this.store = store;
        this.quests = quests;
        this.placement = placement;
        this.waypoints = waypoints;
        this.waypointDiscovered = waypointDiscovered;
        this.clock = clock;
        this.io = io;
        this.instanceKey = new NamespacedKey(plugin, INSTANCE_KEY_NAME);
        this.applier = mobService::apply;
    }

    /** Tests : MockBukkit n'implémente pas tout ce que {@link SpecialMobService#apply} appelle. */
    void applyProfilesWith(ProfileApplier testApplier) {
        this.applier = testApplier;
    }

    @Override
    public void start() {
        QuestBossStateStore.State loaded = store.load();
        for (QuestBossInstance i : loaded.instances()) {
            if (byKey.containsKey(i.key())) {
                retired.add(i.entityId()); // doublon d'un état corrompu : jamais deux boss par objectif.
                continue;
            }
            instances.put(i.entityId(), i);
            byKey.put(i.key(), i.entityId());
        }
        retired.addAll(loaded.retired());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS);
        logger.info("Boss de quête : {} boss restauré(s).", instances.size());
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        if (task != null) {
            task.cancel();
            task = null;
        }
        try {
            store.save(buildState(), revision.incrementAndGet());
        } catch (IOException e) {
            logger.error("Sauvegarde finale des boss de quête impossible : {}", e.getMessage());
        }
    }

    public NamespacedKey instanceKey() {
        return instanceKey;
    }

    /** Instances actives (copie), pour le diagnostic et les tests. */
    public List<QuestBossInstance> activeInstances() {
        return List.copyOf(instances.values());
    }

    /** Abonnement au moteur de quêtes : toujours rejoué sur le thread principal. */
    public void requestReconcile(UUID playerId) {
        if (Bukkit.isPrimaryThread()) {
            reconcile(playerId);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> reconcile(playerId));
        }
    }

    // ---- Réconciliation ------------------------------------------------------------------------

    /**
     * Aligne les boss de ce joueur sur ses objectifs {@code KILL_QUEST_BOSS} réellement en cours :
     * retire ceux qui n'ont plus d'objet, fait apparaître ceux qui manquent (joueur connecté). Sans
     * effet si la progression du joueur n'est pas chargée — un joueur déconnecté garde son boss.
     */
    void reconcile(UUID playerId) {
        if (!quests.isLoaded(playerId)) {
            return;
        }
        Map<String, ObjectiveRef> wanted = new LinkedHashMap<>();
        for (ObjectiveRef ref : quests.pendingQuestBosses(playerId)) {
            wanted.put(QuestBossInstance.key(playerId, ref.questId().asString(), ref.stepId(), ref.objectiveIndex()), ref);
        }
        boolean dirty = false;
        for (QuestBossInstance i : List.copyOf(instances.values())) {
            if (i.owner().equals(playerId) && !wanted.containsKey(i.key())) {
                retire(i, "objectif terminé, abandonné ou retiré");
                dirty = true;
            }
        }
        for (String key : List.copyOf(problems.keySet())) {
            if (key.startsWith(playerId + "|") && !wanted.containsKey(key)) {
                problems.remove(key);
                notBefore.remove(key);
            }
        }
        if (dirty) {
            persist();
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        long now = clock.getAsLong();
        wanted.forEach((key, ref) -> {
            if (!byKey.containsKey(key) && !searching.contains(key) && notBefore.getOrDefault(key, 0L) <= now) {
                spawnFor(player, key, ref);
            }
        });
    }

    private void spawnFor(Player player, String key, ObjectiveRef ref) {
        NamespacedKey mob = ((KillQuestBossObjective) ref.objective()).mob();
        Optional<SpecialMobDefinition> def = registry.find(mob).filter(SpecialMobDefinition::isQuestBoss);
        if (def.isEmpty()) {
            fail(player, key, "le boss « " + MobNames.of(mob) + " » est indisponible (profil " + mob.asString()
                    + " absent, désactivé ou pas en mode « Boss de quête »). Prévenez un administrateur.");
            logger.warn("Boss de quête : profil {} introuvable ou pas BOSS/QUEST activé (quête {}, joueur {}).",
                    mob.asString(), ref.questId(), player.getName());
            return;
        }
        if (instances.size() >= MAX_ACTIVE) {
            fail(player, key, "trop de boss de quête sont actifs sur le serveur pour l'instant ; nouvel essai "
                    + "automatique dans " + RETRY_DELAY_MILLIS / 60_000 + " minutes.");
            return;
        }
        searching.add(key);
        CompletableFuture<Optional<Location>> search;
        try {
            search = placement.find(def.get(), player);
        } catch (RuntimeException e) {
            search = CompletableFuture.failedFuture(e);
        }
        search.whenComplete((spot, err) -> {
            searching.remove(key);
            // Tout a pu changer pendant la recherche (abandon, déconnexion, autre apparition).
            Player owner = Bukkit.getPlayer(player.getUniqueId());
            boolean stillWanted = owner != null && quests.pendingQuestBosses(owner.getUniqueId()).stream()
                    .anyMatch(r -> QuestBossInstance.key(owner.getUniqueId(), r.questId().asString(), r.stepId(),
                            r.objectiveIndex()).equals(key));
            if (!stillWanted || byKey.containsKey(key)) {
                return;
            }
            if (err != null || spot == null || spot.isEmpty()) {
                if (err != null) {
                    logger.warn("Boss de quête : recherche d'emplacement en échec : {}", err.toString());
                }
                fail(owner, key, "aucun emplacement sûr trouvé dans le Wild pour « " + plain(def.get().displayName())
                        + " » (biome, zone, ville, waypoint, terrain). Nouvel essai automatique dans "
                        + RETRY_DELAY_MILLIS / 60_000 + " minutes.");
                return;
            }
            place(owner, key, ref, def.get(), spot.get());
        });
    }

    private void place(Player owner, String key, ObjectiveRef ref, SpecialMobDefinition def, Location location) {
        Entity spawned = location.getWorld().spawnEntity(location, def.entityType());
        if (!(spawned instanceof LivingEntity living) || !spawned.isValid()) {
            if (spawned.isValid()) {
                spawned.remove();
            }
            fail(owner, key, "l'apparition a été refusée par une protection du serveur ; nouvel essai automatique "
                    + "dans " + RETRY_DELAY_MILLIS / 60_000 + " minutes.");
            return;
        }
        applier.apply(living, def);
        living.setPersistent(true);
        living.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, key);

        Location l = living.getLocation();
        Optional<WaypointHint> hint = WaypointHint.nearest(waypoints.get(), l.getWorld().getName(),
                l.getBlockX(), l.getBlockZ());
        QuestBossInstance instance = new QuestBossInstance(living.getUniqueId(), owner.getUniqueId(),
                ref.questId().asString(), ref.stepId(), ref.objectiveIndex(), def.id().asString(),
                plain(def.displayName()), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                l.getBlock().getBiome().getKey().toString(), hint.map(WaypointHint::waypointId).orElse(null),
                hint.map(WaypointHint::waypointName).orElse(null), hint.map(WaypointHint::distance).orElse(0),
                hint.map(WaypointHint::direction).orElse(null), clock.getAsLong());
        instances.put(instance.entityId(), instance);
        byKey.put(key, instance.entityId());
        notBefore.remove(key);
        problems.remove(key);
        persist();
        owner.sendMessage(QuestBossMessages.spawned(instance, def.displayName(), discovered(instance)));
        logger.info("Boss de quête « {} » apparu pour {} (quête {}) en {} {} {} {} (biome {}, waypoint {}).",
                def.id(), owner.getName(), instance.questId(), instance.world(), instance.x(), instance.y(),
                instance.z(), instance.biome(), instance.waypointName() == null ? "aucun à portée" : instance.waypointName());
    }

    /** Problème mémorisé pour le journal ; le joueur n'est prévenu qu'au premier essai ou s'il change. */
    private void fail(Player player, String key, String problem) {
        String previous = problems.put(key, problem);
        notBefore.put(key, clock.getAsLong() + RETRY_DELAY_MILLIS);
        if (!problem.equals(previous)) {
            player.sendMessage(QuestBossMessages.problem(problem));
        }
    }

    private boolean discovered(QuestBossInstance i) {
        return i.waypointId() != null && waypointDiscovered.test(i.owner(), i.waypointId());
    }

    // ---- Tâche périodique ----------------------------------------------------------------------

    void tick() {
        reconcileLoaded();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!quests.pendingQuestBosses(player.getUniqueId()).isEmpty()) {
                reconcile(player.getUniqueId());
            }
        }
    }

    /** Rafraîchit la position des boss chargés ; libère ceux absents de leur chunk chargé. */
    void reconcileLoaded() {
        boolean dirty = false;
        for (QuestBossInstance i : List.copyOf(instances.values())) {
            Entity entity = Bukkit.getEntity(i.entityId());
            if (entity != null && entity.isValid()) {
                Location l = entity.getLocation();
                if (l.getBlockX() != i.x() || l.getBlockY() != i.y() || l.getBlockZ() != i.z()
                        || !l.getWorld().getName().equals(i.world())) {
                    instances.put(i.entityId(), i.movedTo(l.getWorld().getName(), l.getBlockX(), l.getBlockY(),
                            l.getBlockZ()));
                    dirty |= (l.getBlockX() >> 4) != i.chunkX() || (l.getBlockZ() >> 4) != i.chunkZ();
                }
                continue;
            }
            World world = Bukkit.getWorld(i.world());
            if (world != null && world.isChunkLoaded(i.chunkX(), i.chunkZ())
                    && world.getChunkAt(i.chunkX(), i.chunkZ()).isEntitiesLoaded()) {
                lost(i, "introuvable dans son chunk chargé");
                dirty = true;
            }
        }
        if (dirty) {
            persist();
        }
    }

    // ---- Fin de vie ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        QuestBossInstance instance = instances.remove(event.getEntity().getUniqueId());
        if (instance == null) {
            return;
        }
        byKey.remove(instance.key());
        persist();
        Player owner = Bukkit.getPlayer(instance.owner());
        String name = displayName(instance);
        if (owner != null && present(owner, event.getEntity().getLocation())) {
            boolean credited = quests.creditQuestBoss(owner, NamespacedKey.fromString(instance.questId()),
                    instance.stepId(), instance.objectiveIndex());
            if (credited) {
                owner.sendMessage(QuestBossMessages.defeated(name));
            }
            logger.info("Boss de quête « {} » de {} vaincu (objectif {}).", instance.profileId(), owner.getName(),
                    credited ? "rempli" : "déjà plus en cours");
            return;
        }
        notBefore.put(instance.key(), clock.getAsLong() + RESPAWN_DELAY_MILLIS);
        if (owner != null) {
            owner.sendMessage(QuestBossMessages.lostWithoutOwner(name, CREDIT_RADIUS, RESPAWN_DELAY_MILLIS / 1000));
        }
        logger.info("Boss de quête « {} » mort hors de la présence de son propriétaire : objectif non rempli, "
                + "nouvelle apparition prévue.", instance.profileId());
    }

    static boolean present(Player owner, Location bossLocation) {
        return owner.isOnline() && !owner.isDead() && owner.getWorld().equals(bossLocation.getWorld())
                && owner.getLocation().distanceSquared(bossLocation) <= (double) CREDIT_RADIUS * CREDIT_RADIUS;
    }

    /** Objectif sans objet : boss retiré sans butin, tout de suite ou au rechargement de son chunk. */
    private void retire(QuestBossInstance i, String reason) {
        instances.remove(i.entityId());
        byKey.remove(i.key());
        Entity entity = Bukkit.getEntity(i.entityId());
        if (entity != null) {
            mobService.discard(entity);
        } else {
            retired.add(i.entityId());
        }
        logger.info("Boss de quête « {} » de {} retiré : {}.", i.profileId(), i.owner(), reason);
    }

    /** Boss disparu hors de notre contrôle : le verrou tombe, un nouvel essai suivra le délai. */
    private void lost(QuestBossInstance i, String reason) {
        instances.remove(i.entityId());
        byKey.remove(i.key());
        notBefore.put(i.key(), clock.getAsLong() + RESPAWN_DELAY_MILLIS);
        logger.info("Boss de quête « {} » de {} perdu : {} ; nouvelle apparition prévue.", i.profileId(), i.owner(),
                reason);
    }

    // ---- Chunks --------------------------------------------------------------------------------

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        boolean dirty = false;
        Set<UUID> present = new HashSet<>();
        for (Entity entity : event.getEntities()) {
            UUID id = entity.getUniqueId();
            present.add(id);
            if (retired.remove(id)) {
                mobService.discard(entity);
                dirty = true;
            } else if (!instances.containsKey(id) && entity.getPersistentDataContainer().has(instanceKey)) {
                // Marqué sans verrou (crash avant la sauvegarde, ancien cycle) : jamais réadopté — la
                // réconciliation recrée au besoin UNE instance propre, jamais un doublon.
                mobService.discard(entity);
                logger.warn("Boss de quête retrouvé sans verrou : retiré.");
                dirty = true;
            }
        }
        String world = event.getWorld().getName();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (QuestBossInstance i : List.copyOf(instances.values())) {
            if (i.inChunk(world, cx, cz) && !present.contains(i.entityId()) && Bukkit.getEntity(i.entityId()) == null) {
                lost(i, "absent de son chunk au rechargement");
                dirty = true;
            }
        }
        if (dirty) {
            persist();
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        boolean dirty = false;
        for (Entity entity : event.getEntities()) {
            QuestBossInstance i = instances.get(entity.getUniqueId());
            if (i != null) {
                Location l = entity.getLocation();
                instances.put(i.entityId(), i.movedTo(l.getWorld().getName(), l.getBlockX(), l.getBlockY(),
                        l.getBlockZ()));
                dirty = true;
            }
        }
        if (dirty) {
            persist();
        }
    }

    // ---- Journal -------------------------------------------------------------------------------

    /**
     * Ligne d'indication pour le journal, sous l'objectif : où est le boss, ou pourquoi il n'est pas
     * (encore) là. Vide si rien d'utile à dire. Lecture seule, thread principal.
     */
    public Optional<Component> journalHint(UUID playerId, NamespacedKey questId, String stepId, int objectiveIndex) {
        String key = QuestBossInstance.key(playerId, questId.asString(), stepId, objectiveIndex);
        UUID entityId = byKey.get(key);
        if (entityId != null) {
            QuestBossInstance i = instances.get(entityId);
            return Optional.of(QuestBossMessages.journalLine(i, discovered(i)));
        }
        if (searching.contains(key)) {
            return Optional.of(QuestBossMessages.searching());
        }
        return Optional.ofNullable(problems.get(key)).map(QuestBossMessages::problemLine);
    }

    // ---- Persistance ---------------------------------------------------------------------------

    private QuestBossStateStore.State buildState() {
        return new QuestBossStateStore.State(List.copyOf(instances.values()), Set.copyOf(retired));
    }

    private void persist() {
        QuestBossStateStore.State state = buildState();
        long rev = revision.incrementAndGet();
        io.execute(() -> {
            try {
                store.save(state, rev);
            } catch (IOException e) {
                logger.error("Écriture de l'état des boss de quête impossible : {}", e.getMessage());
            }
        });
    }

    private String displayName(QuestBossInstance i) {
        return Optional.ofNullable(NamespacedKey.fromString(i.profileId())).flatMap(registry::find)
                .map(SpecialMobDefinition::displayName).orElse(i.profileName());
    }

    private static String plain(String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage));
    }
}
