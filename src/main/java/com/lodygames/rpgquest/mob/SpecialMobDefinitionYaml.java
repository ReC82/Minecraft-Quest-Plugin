package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import com.lodygames.rpgquest.resource.model.CustomItemDrop;
import com.lodygames.rpgquest.resource.model.ResourceDrop;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;

/**
 * Rendu d'une {@link SpecialMobDefinition} en texte YAML déterministe (un fichier par profil, même
 * discipline que {@link com.lodygames.rpgquest.npc.NpcDefinitionYaml}). Purement fonctionnel, son
 * résultat se re-parse à l'identique avec {@link SpecialMobDefinitionParser}. Le Control Panel
 * n'envoie jamais de YAML brut : seuls des champs métier validés arrivent ici.
 */
public final class SpecialMobDefinitionYaml {

    private SpecialMobDefinitionYaml() {
    }

    public static String render(SpecialMobDefinition d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Profil de mob spécial RPGQuest — généré / édité via le Control Panel (issue #172).\n");
        sb.append("id: ").append(d.id()).append('\n');
        sb.append("category: ").append(d.category()).append('\n');
        sb.append("enabled: ").append(d.enabled()).append('\n');
        sb.append("entity-type: ").append(d.entityType().name()).append('\n');
        sb.append("name: ").append(quote(d.displayName())).append('\n');
        sb.append("spawn-chance: ").append(d.spawnChance()).append('\n');
        appendList(sb, "worlds", d.allowedWorlds());
        appendList(sb, "biomes", d.allowedBiomes());
        appendList(sb, "zones", d.allowedZones());
        appendDouble(sb, "health", d.health());
        appendDouble(sb, "damage", d.damage());
        appendDouble(sb, "speed", d.speed());
        appendDouble(sb, "armor", d.armor());
        appendDouble(sb, "knockback-resistance", d.knockbackResistance());
        appendDouble(sb, "scale", d.scale());
        appendDouble(sb, "creeper-explosion-radius", d.creeperExplosionRadius());
        if (d.particle() != null) {
            sb.append("particle: ").append(d.particle().name()).append('\n');
        }
        if (d.sound() != null) {
            sb.append("sound: ").append(d.sound().name()).append('\n');
        }
        appendAbilities(sb, d.abilities());
        appendDrops(sb, d.drops());
        if (d.xpReward() != null) {
            sb.append("xp-reward: ").append(d.xpReward()).append('\n');
        }
        if (d.maxPopulation() != null) {
            sb.append("max-population: ").append(d.maxPopulation()).append('\n');
        }
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String key, java.util.Set<String> values) {
        if (values.isEmpty()) {
            sb.append(key).append(": []\n");
            return;
        }
        sb.append(key).append(":\n");
        for (String value : values) {
            sb.append("  - ").append(value).append('\n');
        }
    }

    private static void appendDouble(StringBuilder sb, String key, Double value) {
        if (value != null) {
            sb.append(key).append(": ").append(value).append('\n');
        }
    }

    private static void appendAbilities(StringBuilder sb, java.util.List<MobAbility> abilities) {
        if (abilities.isEmpty()) {
            return;
        }
        sb.append("abilities:\n");
        for (MobAbility ability : abilities) {
            switch (ability) {
                case StrongerExplosionAbility a -> {
                    sb.append("  - type: STRONGER_EXPLOSION\n");
                    sb.append("    radius-multiplier: ").append(a.radiusMultiplier()).append('\n');
                }
                case ExplosiveOnAttackAbility a -> {
                    sb.append("  - type: EXPLOSIVE_ON_ATTACK\n");
                    sb.append("    power: ").append(a.power()).append('\n');
                    sb.append("    set-fire: ").append(a.setFire()).append('\n');
                    sb.append("    trigger-range-blocks: ").append(a.triggerRangeBlocks()).append('\n');
                }
                case SplitOnHitAbility a -> {
                    sb.append("  - type: SPLIT_ON_HIT\n");
                    sb.append("    max-depth: ").append(a.maxDepth()).append('\n');
                    sb.append("    max-children-per-hit: ").append(a.maxChildrenPerHit()).append('\n');
                    sb.append("    max-alive-per-parent: ").append(a.maxAlivePerParent()).append('\n');
                }
                case EnragedAbility a -> {
                    sb.append("  - type: ENRAGED\n");
                    sb.append("    health-fraction: ").append(a.healthFraction()).append('\n');
                    sb.append("    speed-multiplier: ").append(a.speedMultiplier()).append('\n');
                    sb.append("    damage-multiplier: ").append(a.damageMultiplier()).append('\n');
                }
                case SummonOnDamageAbility a -> {
                    sb.append("  - type: SUMMON_ON_DAMAGE\n");
                    sb.append("    summon-entity-type: ").append(a.summonedEntityType().name()).append('\n');
                    sb.append("    amount: ").append(a.amount()).append('\n');
                    sb.append("    chance: ").append(a.chance()).append('\n');
                    sb.append("    cooldown-seconds: ").append(a.cooldownSeconds()).append('\n');
                    sb.append("    max-alive: ").append(a.maxAlive()).append('\n');
                }
            }
        }
    }

    private static void appendDrops(StringBuilder sb, java.util.List<ResourceDrop> drops) {
        if (drops.isEmpty()) {
            return;
        }
        sb.append("drops:\n");
        for (ResourceDrop drop : drops) {
            switch (drop) {
                case CustomItemDrop d -> sb.append("  - custom-item: ").append(d.itemId()).append('\n');
                case VanillaItemDrop d -> sb.append("  - material: ").append(d.material().name()).append('\n');
            }
            sb.append("    weight: ").append(drop.weight()).append('\n');
            sb.append("    min-amount: ").append(drop.minAmount()).append('\n');
            sb.append("    max-amount: ").append(drop.maxAmount()).append('\n');
        }
    }

    /** Toujours entre guillemets doubles : le nom d'affichage peut contenir des espaces, du MiniMessage, « : »… */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
