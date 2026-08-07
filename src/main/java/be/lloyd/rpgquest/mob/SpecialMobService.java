package be.lloyd.rpgquest.mob;

import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.mob.model.SpecialMobDefinition;
import be.lloyd.rpgquest.resource.model.CustomItemDrop;
import be.lloyd.rpgquest.resource.model.ResourceDrop;
import be.lloyd.rpgquest.resource.model.VanillaItemDrop;
import be.lloyd.rpgquest.zone.ZoneRegistry;
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
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final SpecialMobRegistry registry;
    private final ZoneRegistry zoneRegistry;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Logger logger;
    private final RandomGenerator random;
    private final NamespacedKey pdcKey;

    private final Map<NamespacedKey, Set<UUID>> population = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, LongAdder> spawnCounts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> abilityTriggerCounts = new ConcurrentHashMap<>();

    public SpecialMobService(Plugin plugin, SpecialMobRegistry registry, ZoneRegistry zoneRegistry,
                              YamlCustomItemRegistry customItemRegistry, Logger logger) {
        this(plugin, registry, zoneRegistry, customItemRegistry, logger, ThreadLocalRandom.current());
    }

    SpecialMobService(Plugin plugin, SpecialMobRegistry registry, ZoneRegistry zoneRegistry,
                       YamlCustomItemRegistry customItemRegistry, Logger logger, RandomGenerator random) {
        this.plugin = plugin;
        this.registry = registry;
        this.zoneRegistry = zoneRegistry;
        this.customItemRegistry = customItemRegistry;
        this.logger = logger;
        this.random = random;
        this.pdcKey = new NamespacedKey(plugin, PDC_KEY_NAME);
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        population.clear();
    }

    public NamespacedKey pdcKey() {
        return pdcKey;
    }

    // ---- Identification PDC ----------------------------------------------------------------

    public Optional<NamespacedKey> specialMobId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
        return raw == null ? Optional.empty() : Optional.ofNullable(NamespacedKey.fromString(raw));
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
            specialMobId(entity).ifPresent(id -> trackAlive(id, entity.getUniqueId()));
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

        if (!def.drops().isEmpty()) {
            event.getDrops().clear();
            rollDrop(def).ifPresent(event.getDrops()::add);
        }
        if (def.xpReward() != null) {
            event.setDroppedExp(def.xpReward());
        }
    }

    Optional<SpecialMobDefinition> rollDefinition(LivingEntity entity) {
        for (SpecialMobDefinition def : registry.definitions()) {
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
                return Optional.of(def);
            }
        }
        return Optional.empty();
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
