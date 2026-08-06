package be.lloyd.rpgquest.item.behavior;

import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.item.model.ConditionalEffect;
import be.lloyd.rpgquest.item.model.CustomItemDefinition;
import be.lloyd.rpgquest.item.model.WeaponBehavior;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.slf4j.Logger;

/**
 * Comportement de combat des armes personnalisées ({@code combat:} dans le
 * YAML). Chaque règle de la mission a un garde explicite ici :
 * <ol>
 *     <li>{@code @EventHandler(ignoreCancelled = true)} — un événement déjà
 *     annulé par un autre plugin (protection de zone, etc.) n'est jamais
 *     traité.</li>
 *     <li>{@link EntityDamageByEntityEvent#setDamage} n'est appelé
 *     <b>qu'une seule fois</b>, avec une valeur calculée localement — jamais
 *     d'appel supplémentaire à {@code entity.damage(...)}.</li>
 *     <li>{@link #sanitizeDamage} élimine NaN/infini/négatif avant l'écriture.</li>
 *     <li>Le cooldown de l'effet conditionnel passe par {@link CooldownManager},
 *     indexé par (UUID, abilityId).</li>
 *     <li>{@link CooldownManager#purgeExpired()} est appelé par
 *     {@code EquipmentBehaviorService}, pas ici.</li>
 *     <li>Main secondaire : seul {@link org.bukkit.inventory.PlayerInventory#getItemInMainHand()}
 *     est consulté. Porte-monnaie/armor stand : {@link #isValidTarget}
 *     rejette les {@link ArmorStand}. Projectile : {@code event.getDamager()}
 *     doit être un {@link Player} — un projectile (flèche, trident...) ne
 *     matche jamais, même tiré par un joueur.</li>
 *     <li>Faux objet : {@link YamlCustomItemRegistry#resolve} est la seule
 *     source de vérité (PersistentDataContainer), jamais le nom/lore.</li>
 * </ol>
 */
public final class WeaponBehaviorListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlCustomItemRegistry registry;
    private final CooldownManager cooldowns;
    private final BooleanSupplier debugEnabled;
    private final Logger logger;
    private final Random random;

    public WeaponBehaviorListener(YamlCustomItemRegistry registry, CooldownManager cooldowns,
                                   BooleanSupplier debugEnabled, Logger logger) {
        this(registry, cooldowns, debugEnabled, logger, new Random());
    }

    WeaponBehaviorListener(YamlCustomItemRegistry registry, CooldownManager cooldowns,
                            BooleanSupplier debugEnabled, Logger logger, Random random) {
        this.registry = registry;
        this.cooldowns = cooldowns;
        this.debugEnabled = debugEnabled;
        this.logger = logger;
        this.random = random;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMeleeAttack(EntityDamageByEntityEvent event) {
        // Redondant avec @EventHandler(ignoreCancelled = true) : explicite ici pour que la règle
        // « respecter les annulations » reste vraie même en appelant la méthode directement (tests),
        // pas seulement quand Bukkit se charge de filtrer avant dispatch.
        if (event.isCancelled()) {
            return;
        }
        if (!isValidTarget(event.getEntity())) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Optional<CustomItemDefinition> definitionOpt = registry.resolve(weapon);
        if (definitionOpt.isEmpty()) {
            return;
        }
        CustomItemDefinition definition = definitionOpt.get();
        WeaponBehavior behavior = definition.weaponBehavior();
        if (behavior == null) {
            return;
        }

        LivingEntity target = (LivingEntity) event.getEntity();

        double withBonus = event.getDamage() + (behavior.baseDamage() != null ? behavior.baseDamage() : 0.0);
        boolean critical = behavior.criticalChance() > 0 && random.nextDouble() < behavior.criticalChance();
        double finalDamage = sanitizeDamage(critical ? withBonus * behavior.criticalMultiplier() : withBonus);
        event.setDamage(finalDamage);

        boolean effectTriggered = tryTriggerConditionalEffect(attacker, target, definition, behavior);

        if (critical || effectTriggered) {
            notify(attacker, target, behavior, finalDamage, critical);
        }

        if (debugEnabled.getAsBoolean()) {
            logger.info("[debug][weapon] {} frappe {} avec {} : {} dégâts (critique={}, effet={})",
                    attacker.getName(), target.getType(), definition.id(),
                    String.format(Locale.ROOT, "%.2f", finalDamage), critical, effectTriggered);
        }
    }

    /** Rejette les cibles non pertinentes (armor stands) plutôt que de laisser passer une exploitation de farm. */
    private boolean isValidTarget(org.bukkit.entity.Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand);
    }

    private double sanitizeDamage(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }

    private boolean tryTriggerConditionalEffect(Player attacker, LivingEntity target,
                                                 CustomItemDefinition definition, WeaponBehavior behavior) {
        ConditionalEffect effect = behavior.conditionalEffect();
        if (effect == null) {
            return false;
        }
        String abilityId = definition.id() + ":" + effect.abilityId();
        if (!cooldowns.isReady(attacker.getUniqueId(), abilityId)) {
            return false;
        }
        if (random.nextDouble() >= effect.chance()) {
            return false;
        }
        cooldowns.start(attacker.getUniqueId(), abilityId, effect.cooldownMillis());
        target.addPotionEffect(new PotionEffect(effect.effectType(), effect.durationTicks(), effect.amplifier()));
        return true;
    }

    private void notify(Player attacker, LivingEntity target, WeaponBehavior behavior, double damage, boolean critical) {
        if (behavior.hitMessage() != null && !behavior.hitMessage().isBlank()) {
            attacker.sendMessage(MM.deserialize(behavior.hitMessage(),
                    Placeholder.unparsed("damage", String.format(Locale.ROOT, "%.1f", damage)),
                    Placeholder.unparsed("critical", critical ? "oui" : "non")));
        }
        if (behavior.particle() != null) {
            target.getWorld().spawnParticle(behavior.particle(),
                    target.getLocation().add(0, 1, 0), behavior.particleCount());
        }
    }
}
