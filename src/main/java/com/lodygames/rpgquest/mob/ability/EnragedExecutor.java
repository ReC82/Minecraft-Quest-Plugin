package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Issue #173 : à chaque seconde ({@link AbilityTrigger#TICK}), bascule en rage toute entité dont la
 * vie tombe à/sous {@code healthFraction}. Le passage est <b>binaire et définitif</b> : marqué en PDC
 * (clé {@value #PDC_KEY_NAME}, inchangée depuis #173), jamais réappliqué ni cumulé ; il survit au
 * redémarrage avec les attributs de base multipliés.
 */
final class EnragedExecutor implements AbilityExecutor<EnragedAbility> {

    static final String PDC_KEY_NAME = "special-mob-enraged";

    private final NamespacedKey enragedKey;

    EnragedExecutor(Plugin plugin) {
        this.enragedKey = new NamespacedKey(plugin, PDC_KEY_NAME);
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.ENRAGED;
    }

    @Override
    public Class<EnragedAbility> abilityClass() {
        return EnragedAbility.class;
    }

    @Override
    public void execute(AbilityContext<EnragedAbility> context) {
        LivingEntity entity = context.mob();
        EnragedAbility ability = context.ability();
        if (isAlreadyEnraged(entity)) {
            return;
        }
        AttributeInstance maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : entity.getHealth();
        if (maxHealth <= 0 || entity.getHealth() / maxHealth > ability.healthFraction() || !context.passesGate()) {
            return;
        }

        entity.getPersistentDataContainer().set(enragedKey, PersistentDataType.BYTE, (byte) 1);
        multiplyBaseValue(entity, Attribute.MOVEMENT_SPEED, ability.speedMultiplier());
        multiplyBaseValue(entity, Attribute.ATTACK_DAMAGE, ability.damageMultiplier());

        entity.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, entity.getLocation().add(0, entity.getHeight() + 0.3, 0), 6);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0f, 0.8f);
        context.markActivated();
    }

    private boolean isAlreadyEnraged(LivingEntity entity) {
        Byte flag = entity.getPersistentDataContainer().get(enragedKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private void multiplyBaseValue(LivingEntity entity, Attribute attribute, double multiplier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(instance.getBaseValue() * multiplier);
        }
    }
}
