package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.resource.model.CustomItemDrop;
import com.lodygames.rpgquest.resource.model.ResourceDrop;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Possède l'état runtime des mobs spéciaux : décide quelle entité vanilla
 * fraîchement apparue devient une variante, applique cette variante
 * (attributs, nom, PDC), et suit la population vivante par définition.
 *
 * <p>Une entité upgradée est identifiée <b>uniquement</b> par PDC (jamais par
 * son nom affiché, qui peut être renommé/traduit — mission point 10) : la clé
 * {@link #PDC_KEY_NAME} stocke l'id de la définition sous forme de chaîne.</p>
 *
 * <p>La population n'est décomptée que sur {@link EntityDeathEvent}, jamais
 * sur un déchargement de chunk : {@code setRemoveWhenFarAway(false)} est posé
 * à l'application pour qu'une variante ne disparaisse jamais silencieusement
 * par despawn naturel vanilla, ce qui garantit qu'une entité comptée dans la
 * population ne peut la quitter que par la mort (correspond au test manuel
 * "Décharger/recharger le chunk" : le compte doit être stable). Après un
 * redémarrage, la population en mémoire repart à zéro ; elle est reconstruite
 * au fil de l'eau via {@link #onChunkLoad} qui redécouvre les entités déjà
 * taguées PDC dans les chunks qui se chargent.</p>
 */
public final class SpecialMobService implements PluginService, Listener {

    public static final String PDC_KEY_NAME = "special-mob-id";
    public static final String SPLIT_DEPTH_KEY_NAME = "special-mob-split-depth";
    /** Issue #190 : UUID du parent direct, posé sur chaque enfant {@code SPLIT_ON_HIT} pour compter
     * ses enfants vivants indépendamment du nombre de coups reçus (voir {@code SplitOnHitAbilityListener}). */
    public static final String SPLIT_PARENT_KEY_NAME = "special-mob-split-parent";
    public static final String TEST_INSTANCE_KEY_NAME = "special-mob-test-instance";

    private static final long BOSS_BAR_PERIOD_TICKS = 20L; // 1 s : aura + rafraîchissement de la barre de vie.
    private static final double BOSS_BAR_RANGE_SQUARED = 48.0 * 48.0;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final SpecialMobRegistry registry;
    private final ZoneRegistry zoneRegistry;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Logger logger;
    private final RandomGenerator random;
    private final MobSpawnSettingsStore spawnSettingsStore;
    private final NamespacedKey pdcKey;
    private final NamespacedKey splitDepthKey;
    private final NamespacedKey splitParentKey;
    private final NamespacedKey testInstanceKey;

    private final Map<NamespacedKey, Set<UUID>> population = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, LongAdder> spawnCounts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> abilityTriggerCounts = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> bossBarViewers = new ConcurrentHashMap<>();
    private BukkitTask bossBarTask;

    public SpecialMobService(Plugin plugin, SpecialMobRegistry registry, ZoneRegistry zoneRegistry,
                              YamlCustomItemRegistry customItemRegistry, Logger logger,
                              MobSpawnSettingsStore spawnSettingsStore) {
        this(plugin, registry, zoneRegistry, customItemRegistry, logger, ThreadLocalRandom.current(), spawnSettingsStore);
    }

    SpecialMobService(Plugin plugin, SpecialMobRegistry registry, ZoneRegistry zoneRegistry,
                       YamlCustomItemRegistry customItemRegistry, Logger logger, RandomGenerator random,
                       MobSpawnSettingsStore spawnSettingsStore) {
        this.plugin = plugin;
        this.registry = registry;
        this.zoneRegistry = zoneRegistry;
        this.customItemRegistry = customItemRegistry;
        this.logger = logger;
        this.random = random;
        this.spawnSettingsStore = spawnSettingsStore;
        this.pdcKey = new NamespacedKey(plugin, PDC_KEY_NAME);
        this.splitDepthKey = new NamespacedKey(plugin, SPLIT_DEPTH_KEY_NAME);
        this.splitParentKey = new NamespacedKey(plugin, SPLIT_PARENT_KEY_NAME);
        this.testInstanceKey = new NamespacedKey(plugin, TEST_INSTANCE_KEY_NAME);
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bossBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBossBars, BOSS_BAR_PERIOD_TICKS, BOSS_BAR_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        if (bossBarTask != null) {
            bossBarTask.cancel();
            bossBarTask = null;
        }
        for (var entry : bossBarViewers.entrySet()) {
            BossBar bar = bossBars.get(entry.getKey());
            if (bar == null) {
                continue;
            }
            for (UUID viewerId : entry.getValue()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    viewer.hideBossBar(bar);
                }
            }
        }
        bossBars.clear();
        bossBarViewers.clear();
        population.clear();
    }

    public NamespacedKey pdcKey() {
        return pdcKey;
    }

    public NamespacedKey splitDepthKey() {
        return splitDepthKey;
    }

    public NamespacedKey splitParentKey() {
        return splitParentKey;
    }

    // ---- Identification PDC ----------------------------------------------------------------

    public Optional<NamespacedKey> specialMobId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
        return raw == null ? Optional.empty() : Optional.ofNullable(NamespacedKey.fromString(raw));
    }

    /** Vrai si {@code entity} est née d'une division ({@link com.lodygames.rpgquest.mob.model.SplitOnHitAbility}), jamais un spawn racine. */
    public boolean isSplitOffspring(Entity entity) {
        Integer depth = entity.getPersistentDataContainer().get(splitDepthKey, PersistentDataType.INTEGER);
        return depth != null && depth > 0;
    }

    public Optional<SpecialMobDefinition> specialMobDefinition(Entity entity) {
        return specialMobId(entity).flatMap(registry::find);
    }

    // ---- Spawn naturel -----------------------------------------------------------------------

    /**
     * Priorité HIGH + {@code ignoreCancelled} : s'exécute après les listeners de protection de zone
     * (priorité par défaut) — un spawn déjà annulé par la safe zone n'est jamais upgradé (mission
     * point 5, "respecte la safe zone").
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        rollDefinition(living).ifPresent(def -> apply(living, def));
    }

    /** Redécouvre les variantes déjà présentes (rechargement de chunk ou redémarrage) sans les recompter. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            specialMobId(entity).ifPresent(id -> {
                trackAlive(id, entity.getUniqueId());
                registry.find(id)
                        .filter(def -> def.category() == MobCategory.BOSS)
                        .ifPresent(def -> ensureBossBar((LivingEntity) entity, def));
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Optional<SpecialMobDefinition> defOpt = specialMobDefinition(event.getEntity());
        if (defOpt.isEmpty()) {
            return;
        }
        SpecialMobDefinition def = defOpt.get();
        untrack(def.id(), event.getEntity().getUniqueId());
        removeBossBar(event.getEntity().getUniqueId());

        if (!def.drops().isEmpty()) {
            event.getDrops().clear();
            rollDrop(def).ifPresent(event.getDrops()::add);
        }
        if (def.xpReward() != null) {
            event.setDroppedExp(def.xpReward());
        }
    }

    /**
     * Résout la ou les transformations possibles d'un spawn naturel éligible (issue #174) :
     *
     * <ol>
     *   <li>throttle global ({@link MobSpawnSettings#chance()}) -- un seul tirage, évalué une fois,
     *       <i>avant</i> d'examiner les définitions individuelles ;</li>
     *   <li>plafond global de {@code SPECIAL} vivants simultanés ({@link MobSpawnSettings#maxSimultaneousSpecial()}) ;</li>
     *   <li>pour chaque définition {@code SPECIAL} (jamais {@code BOSS} -- exclues du tirage
     *       automatique), activée, compatible (type/monde/biome/zone) et sous son propre plafond,
     *       tirage indépendant de son {@code spawnChance} ;</li>
     *   <li>zéro résultat -> aucune transformation ; un résultat -> appliqué ; plusieurs résultats
     *       simultanés -> tirage pondéré explicite entre eux (poids = {@code spawnChance} de chacune),
     *       jamais la première trouvée dans le registre.</li>
     * </ol>
     */
    Optional<SpecialMobDefinition> rollDefinition(LivingEntity entity) {
        MobSpawnSettings settings = spawnSettingsStore.current();
        if (!settings.enabled()) {
            return Optional.empty();
        }
        if (random.nextDouble() >= settings.chance()) {
            return Optional.empty();
        }
        if (atGlobalSpecialCap(settings)) {
            return Optional.empty();
        }

        List<SpecialMobDefinition> hits = new ArrayList<>();
        for (SpecialMobDefinition def : registry.definitions()) {
            if (def.category() != MobCategory.SPECIAL || !def.enabled()) {
                continue;
            }
            if (def.entityType() != entity.getType()) {
                continue;
            }
            if (!locationAllowed(def, entity.getLocation())) {
                continue;
            }
            if (atPopulationLimit(def)) {
                continue;
            }
            if (random.nextDouble() < def.spawnChance()) {
                hits.add(def);
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() == 1) {
            return Optional.of(hits.get(0));
        }
        return Optional.of(weightedPick(hits));
    }

    private SpecialMobDefinition weightedPick(List<SpecialMobDefinition> hits) {
        double totalWeight = hits.stream().mapToDouble(SpecialMobDefinition::spawnChance).sum();
        if (totalWeight <= 0) {
            return hits.get(random.nextInt(hits.size()));
        }
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (SpecialMobDefinition def : hits) {
            cumulative += def.spawnChance();
            if (roll < cumulative) {
                return def;
            }
        }
        return hits.get(hits.size() - 1);
    }

    private boolean atGlobalSpecialCap(MobSpawnSettings settings) {
        if (settings.maxSimultaneousSpecial() == null) {
            return false;
        }
        int aliveSpecial = 0;
        for (SpecialMobDefinition def : registry.definitions()) {
            if (def.category() == MobCategory.SPECIAL) {
                aliveSpecial += populationOf(def.id());
            }
        }
        return aliveSpecial >= settings.maxSimultaneousSpecial();
    }

    private boolean locationAllowed(SpecialMobDefinition def, Location location) {
        if (!def.allowedWorlds().isEmpty()
                && !def.allowedWorlds().contains(location.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!def.allowedBiomes().isEmpty()) {
            String biome = location.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
            if (!def.allowedBiomes().contains(biome)) {
                return false;
            }
        }
        if (!def.allowedZones().isEmpty()) {
            boolean inAllowedZone = zoneRegistry
                    .zoneAt(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    .map(zone -> def.allowedZones().contains(zone.id().toLowerCase(Locale.ROOT)))
                    .orElse(false);
            if (!inAllowedZone) {
                return false;
            }
        }
        return true;
    }

    // ---- Spawn / nettoyage de test depuis le panel (issue #172) ----------------------

    /**
     * Fait apparaître une instance de test de {@code def} à une position sûre à proximité de
     * {@code player} (voir {@link com.lodygames.rpgquest.travel.RandomSafeLocationFinder}),
     * marquée distinctement (PDC {@link #TEST_INSTANCE_KEY_NAME}) pour que {@link #clearTestInstances}
     * ne supprime jamais un mob ordinaire. Le monde/la zone Wild sont du ressort de l'appelant
     * (résolution du joueur choisi) ; ici on ne fait que chercher une position sûre autour de lui.
     */
    public Optional<Location> findTestSpawnLocation(Player player) {
        return new com.lodygames.rpgquest.travel.RandomSafeLocationFinder(3, 10, 20)
                .find(player.getWorld(), player.getLocation());
    }

    public void applyTestInstance(LivingEntity entity, SpecialMobDefinition def) {
        apply(entity, def);
        entity.getPersistentDataContainer().set(testInstanceKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isTestInstance(Entity entity) {
        Byte flag = entity.getPersistentDataContainer().get(testInstanceKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /** Supprime uniquement les instances de test encore vivantes (toutes définitions). Jamais un mob ordinaire. */
    public int clearTestInstances() {
        int removed = 0;
        for (SpecialMobDefinition def : registry.definitions()) {
            for (UUID entityId : aliveEntityIds(def.id())) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity == null || !isTestInstance(entity)) {
                    continue;
                }
                untrack(def.id(), entityId);
                removeBossBar(entityId);
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public boolean atPopulationLimit(SpecialMobDefinition def) {
        if (def.maxPopulation() == null) {
            return false;
        }
        return population.getOrDefault(def.id(), Set.of()).size() >= def.maxPopulation();
    }

    /** Applique la variante à une entité déjà apparue (spawn naturel upgradé ou spawn admin). */
    public void apply(LivingEntity entity, SpecialMobDefinition def) {
        entity.customName(MM.deserialize(def.displayName()));
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);

        applyAttribute(entity, Attribute.MAX_HEALTH, def.health());
        if (def.health() != null) {
            // Application au moment du spawn : la vie courante vaut toujours la vie vanilla d'origine
            // (pleine vie), donc la fixer à la nouvelle valeur max ne peut pas dépasser cette dernière.
            entity.setHealth(def.health());
        }
        applyAttribute(entity, Attribute.ATTACK_DAMAGE, def.damage());
        applyAttribute(entity, Attribute.MOVEMENT_SPEED, def.speed());
        applyAttribute(entity, Attribute.ARMOR, def.armor());
        applyAttribute(entity, Attribute.KNOCKBACK_RESISTANCE, def.knockbackResistance());
        applyAttribute(entity, Attribute.SCALE, def.scale());

        if (def.creeperExplosionRadius() != null && entity instanceof Creeper creeper) {
            creeper.setExplosionRadius(def.creeperExplosionRadius().intValue());
        }

        if (def.particle() != null) {
            entity.getWorld().spawnParticle(def.particle(), entity.getLocation().add(0, 1, 0), 10);
        }
        if (def.sound() != null) {
            entity.getWorld().playSound(entity.getLocation(), def.sound(), 1.0f, 1.0f);
        }

        entity.getPersistentDataContainer().set(pdcKey, PersistentDataType.STRING, def.id().asString());

        trackAlive(def.id(), entity.getUniqueId());
        spawnCounts.computeIfAbsent(def.id(), k -> new LongAdder()).increment();
        logger.debug("Mob spécial « {} » appliqué à {} en {}.", def.id(), entity.getType(), entity.getLocation());

        if (def.category() == MobCategory.BOSS) {
            ensureBossBar(entity, def);
        }
    }

    // ---- Visuels de boss (issue #172) -------------------------------------------------

    private void ensureBossBar(LivingEntity entity, SpecialMobDefinition def) {
        bossBars.computeIfAbsent(entity.getUniqueId(), id -> {
            BossBar bar = BossBar.bossBar(MM.deserialize(def.displayName()), 1.0f,
                    BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            bossBarViewers.put(id, ConcurrentHashMap.newKeySet());
            return bar;
        });
    }

    private void removeBossBar(UUID entityId) {
        BossBar bar = bossBars.remove(entityId);
        Set<UUID> viewers = bossBarViewers.remove(entityId);
        if (bar == null || viewers == null) {
            return;
        }
        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                viewer.hideBossBar(bar);
            }
        }
    }

    /** Rafraîchit la barre de vie + l'aura de particules de chaque boss vivant, borné à la population BOSS réelle. */
    private void tickBossBars() {
        for (var entry : bossBars.entrySet()) {
            UUID entityId = entry.getKey();
            Entity entity = Bukkit.getEntity(entityId);
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue; // nettoyage réel délégué à onDeath ; ignoré ici en défense.
            }
            BossBar bar = entry.getValue();
            AttributeInstance maxHealthAttr = living.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : living.getHealth();
            float progress = maxHealth <= 0 ? 0f : (float) Math.max(0, Math.min(1, living.getHealth() / maxHealth));
            bar.progress(progress);

            living.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, living.getLocation().add(0, living.getHeight() + 0.5, 0), 4);

            Set<UUID> viewers = bossBarViewers.computeIfAbsent(entityId, id -> ConcurrentHashMap.newKeySet());
            Set<UUID> inRange = new java.util.HashSet<>();
            for (Player player : living.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(living.getLocation()) <= BOSS_BAR_RANGE_SQUARED) {
                    inRange.add(player.getUniqueId());
                    if (viewers.add(player.getUniqueId())) {
                        player.showBossBar(bar);
                    }
                }
            }
            viewers.removeIf(viewerId -> {
                if (inRange.contains(viewerId)) {
                    return false;
                }
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    viewer.hideBossBar(bar);
                }
                return true;
            });
        }
    }

    private void applyAttribute(LivingEntity entity, Attribute attribute, Double value) {
        if (value == null) {
            return;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private Optional<ItemStack> rollDrop(SpecialMobDefinition def) {
        List<ResourceDrop> drops = def.drops();
        int totalWeight = def.totalDropWeight();
        if (totalWeight <= 0) {
            return Optional.empty();
        }
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        ResourceDrop chosen = drops.get(drops.size() - 1);
        for (ResourceDrop drop : drops) {
            cumulative += drop.weight();
            if (roll < cumulative) {
                chosen = drop;
                break;
            }
        }

        int amount = chosen.minAmount() == chosen.maxAmount()
                ? chosen.minAmount()
                : ThreadLocalRandom.current().nextInt(chosen.minAmount(), chosen.maxAmount() + 1);

        return switch (chosen) {
            case CustomItemDrop customItemDrop -> customItemRegistry.create(customItemDrop.itemId(), amount);
            case VanillaItemDrop vanillaItemDrop -> Optional.of(new ItemStack(vanillaItemDrop.material(), amount));
        };
    }

    // ---- Population / métriques -------------------------------------------------------------

    private void trackAlive(NamespacedKey definitionId, UUID entityId) {
        population.computeIfAbsent(definitionId, k -> ConcurrentHashMap.newKeySet()).add(entityId);
    }

    private void untrack(NamespacedKey definitionId, UUID entityId) {
        Set<UUID> alive = population.get(definitionId);
        if (alive != null) {
            alive.remove(entityId);
        }
    }

    public int populationOf(NamespacedKey definitionId) {
        return population.getOrDefault(definitionId, Set.of()).size();
    }

    /** Copie défensive des identifiants d'entités vivantes pour une définition (utilisé par les sweeps de capacités). */
    public Set<UUID> aliveEntityIds(NamespacedKey definitionId) {
        return Set.copyOf(population.getOrDefault(definitionId, Set.of()));
    }

    public void recordAbilityTrigger(String abilityLabel) {
        abilityTriggerCounts.computeIfAbsent(abilityLabel, k -> new LongAdder()).increment();
    }

    public Map<NamespacedKey, Long> spawnMetricsSnapshot() {
        Map<NamespacedKey, Long> snapshot = new LinkedHashMap<>();
        spawnCounts.forEach((id, adder) -> snapshot.put(id, adder.sum()));
        return snapshot;
    }

    public Map<String, Long> abilityMetricsSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        abilityTriggerCounts.forEach((label, adder) -> snapshot.put(label, adder.sum()));
        return snapshot;
    }
}
