package com.lodygames.rpgquest.mob.ability;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Issue #542 — moteur unique des capacités de mobs. Remplace les cinq listeners/services d'avant
 * (un par capacité, deux tâches répétitives) par un seul listener et une seule tâche d'une seconde.
 *
 * <ul>
 *   <li><b>Distribution</b> : chaque occasion ({@link AbilityTrigger}) est présentée aux instances
 *       <em>actives</em> du profil ({@link MobAbilityRules#activeAbilities}), <strong>dans l'ordre du
 *       profil</strong> (décision Q5).</li>
 *   <li><b>Champs communs</b> : délai et chance ({@link #passesGate}) sont tenus ici, jamais dans une
 *       capacité. Chance 1 = aucun tirage ; délai 0 = aucune attente : comportement d'avant #542.</li>
 *   <li><b>Population bornée</b> : la tâche ne parcourt que {@link SpecialMobService#aliveEntityIds}
 *       des profils qui ont une capacité {@link AbilityTrigger#TICK}, jamais toutes les entités d'un
 *       monde ; aucune tâche par entité ; aucun chunk chargé.</li>
 *   <li><b>Nettoyage</b> : délais et état de combat retirés à la mort, au retrait du monde
 *       (déchargement compris) et à l'arrêt — la fuite mémoire des délais de renforts d'avant #542
 *       disparaît.</li>
 * </ul>
 */
public final class AbilityEngine implements PluginService, Listener {

    static final long TICK_PERIOD_TICKS = 20L; // 1 s : la cadence des deux balayages d'avant #542.

    private final Plugin plugin;
    private final SpecialMobRegistry registry;
    private final SpecialMobService service;
    private final AbilityWorldPolicy policy;
    private final RandomGenerator random;
    private final LongSupplier clock;
    private final Map<MobAbilityType, AbilityExecutor<?>> executors = new EnumMap<>(MobAbilityType.class);
    private final Map<UUID, Map<MobAbilityType, Long>> lastActivationMillis = new ConcurrentHashMap<>();
    private final CombatState combat = new CombatState();
    private final AbilityLimits limits = new AbilityLimits();
    private BukkitTask task;

    public AbilityEngine(Plugin plugin, SpecialMobRegistry registry, SpecialMobService service,
                         AbilityWorldPolicy policy) {
        this(plugin, registry, service, policy, ThreadLocalRandom.current(), System::currentTimeMillis);
    }

    AbilityEngine(Plugin plugin, SpecialMobRegistry registry, SpecialMobService service, AbilityWorldPolicy policy,
                  RandomGenerator random, LongSupplier clock) {
        this.plugin = plugin;
        this.registry = registry;
        this.service = service;
        this.policy = policy;
        this.random = random;
        this.clock = clock;
        register(new StrongerExplosionExecutor());
        register(new ExplosiveOnAttackExecutor(policy));
        register(new SplitOnHitExecutor(service, policy));
        register(new EnragedExecutor(plugin));
        register(new SummonOnDamageExecutor(plugin, policy, limits, clock));
        for (MobAbilityType type : MobAbilityType.values()) {
            if (!executors.containsKey(type)) {
                throw new IllegalStateException("aucun exécuteur pour la capacité " + type);
            }
        }
    }

    private void register(AbilityExecutor<?> executor) {
        if (executors.putIfAbsent(executor.type(), executor) != null) {
            throw new IllegalStateException("deux exécuteurs pour la capacité " + executor.type());
        }
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastActivationMillis.clear();
        combat.clear();
        limits.clear();
    }

    // ---- Occasions -----------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        service.specialMobDefinition(living).ifPresent(def -> dispatch(AbilityTrigger.DAMAGED, living, def, event));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        service.specialMobDefinition(living).ifPresent(def -> dispatch(AbilityTrigger.EXPLOSION_PRIME, living, def, event));
    }

    /** Coup échangé entre un mob spécial et un joueur (mêlée ou projectile) : alimente {@link CombatState}. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        Entity victim = event.getEntity();
        if (victim instanceof Player player && service.specialMobId(damager).isPresent()) {
            combat.recordExchange(damager.getUniqueId(), player.getUniqueId(), clock.getAsLong());
        } else if (damager instanceof Player player && service.specialMobId(victim).isPresent()) {
            combat.recordExchange(victim.getUniqueId(), player.getUniqueId(), clock.getAsLong());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity living = event.getEntity();
        service.specialMobDefinition(living).ifPresent(def -> dispatch(AbilityTrigger.DEATH, living, def, event));
        forget(living.getUniqueId());
    }

    /** Retrait du monde (mort, {@code discard}, déchargement du chunk) : plus rien à garder en mémoire. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    /** Une seconde : capacités {@link AbilityTrigger#TICK} de la population connue (Enragé, Explosif au contact). */
    public void tick() {
        for (SpecialMobDefinition def : registry.definitions()) {
            if (activeFor(def, AbilityTrigger.TICK).isEmpty()) {
                continue;
            }
            for (UUID entityId : service.aliveEntityIds(def.id())) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity instanceof LivingEntity living && !living.isDead()) {
                    dispatch(AbilityTrigger.TICK, living, def, null);
                }
            }
        }
    }

    // ---- Distribution et champs communs --------------------------------------------------------

    void dispatch(AbilityTrigger trigger, LivingEntity mob, SpecialMobDefinition def, Event event) {
        for (MobAbility ability : activeFor(def, trigger)) {
            if (mob.isDead() && trigger != AbilityTrigger.DEATH) {
                return; // une capacité précédente l'a tué (explosion) : les suivantes n'ont plus d'objet.
            }
            run(executors.get(ability.type()), trigger, mob, def, ability, event);
        }
    }

    private <A extends MobAbility> void run(AbilityExecutor<A> executor, AbilityTrigger trigger, LivingEntity mob,
                                            SpecialMobDefinition def, MobAbility ability, Event event) {
        executor.execute(new AbilityContext<>(this, trigger, mob, def, executor.abilityClass().cast(ability), event));
    }

    private List<MobAbility> activeFor(SpecialMobDefinition def, AbilityTrigger trigger) {
        List<MobAbility> out = new ArrayList<>(2);
        for (MobAbility ability : MobAbilityRules.activeAbilities(def.abilities())) {
            if (MobAbilityCatalog.descriptor(ability.type()).triggers().contains(trigger)) {
                out.add(ability);
            }
        }
        return out;
    }

    boolean passesGate(LivingEntity mob, MobAbility ability) {
        int cooldownSeconds = ability.options().cooldownSeconds();
        if (cooldownSeconds > 0) {
            Long last = lastActivationMillis.getOrDefault(mob.getUniqueId(), Map.of()).get(ability.type());
            if (last != null && clock.getAsLong() - last < cooldownSeconds * 1000L) {
                return false;
            }
        }
        double chance = ability.options().chance();
        return chance >= 1.0 || random.nextDouble() < chance;
    }

    void markActivated(LivingEntity mob, MobAbility ability) {
        if (ability.options().cooldownSeconds() > 0) {
            lastActivationMillis.computeIfAbsent(mob.getUniqueId(), id -> new EnumMap<>(MobAbilityType.class))
                    .put(ability.type(), clock.getAsLong());
        }
        service.recordAbilityTrigger(ability.type().name());
    }

    private void forget(UUID entityId) {
        lastActivationMillis.remove(entityId);
        combat.forget(entityId);
        limits.endAction(entityId);
    }

    // ---- Lecture (diagnostic, tests, capacités suivantes) --------------------------------------

    public CombatState combat() {
        return combat;
    }

    public AbilityLimits limits() {
        return limits;
    }

    public AbilityWorldPolicy policy() {
        return policy;
    }

    /** Mobs dont un délai est en mémoire (test de nettoyage). */
    int trackedCooldowns() {
        return lastActivationMillis.size();
    }
}
