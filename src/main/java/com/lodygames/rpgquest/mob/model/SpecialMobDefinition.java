package com.lodygames.rpgquest.mob.model;

import com.lodygames.rpgquest.resource.model.ResourceDrop;
import java.util.List;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;

/**
 * Une variante d'entité vanilla entièrement pilotée par YAML. Correcte par
 * construction, même discipline que {@code CustomItemDefinition}/{@code
 * ZoneDefinition}. Réutilise {@code resource.model.ResourceDrop} tel quel
 * pour la table de drops (même besoin exact : un tirage pondéré, objet
 * personnalisé ou matériau vanilla — aucune raison de dupliquer ce type).
 *
 * <p>{@code allowedBiomes} est une simple liste de noms (ex. {@code
 * "plains"}), jamais résolue en objets {@code Biome} : la comparaison se
 * fait uniquement par nom de clé au moment du spawn ({@code
 * Biome#getKey().getKey()}), ce qui évite toute dépendance à la forme
 * exacte de l'API {@code Biome} dans cette version de Paper (enum ou
 * registre). Même choix pour {@code allowedZones} : de simples id de zone,
 * résolus paresseusement contre {@code ZoneRegistry} au moment du spawn,
 * jamais validés au chargement (même convention que {@code
 * economy.merchant} référençant un objet personnalisé).</p>
 */
public record SpecialMobDefinition(
        NamespacedKey id,
        /** Issue #172 : SPECIAL (tirage aléatoire Wild) ou BOSS (jamais tiré au hasard). */
        MobCategory category,
        /** Issue #172 : désactivé = ignoré par le tirage aléatoire ET par le spawn de test ; jamais supprimé. */
        boolean enabled,
        EntityType entityType,
        String displayName,
        double spawnChance,
        Set<String> allowedWorlds,
        Set<String> allowedBiomes,
        Set<String> allowedZones,
        Double health,
        Double damage,
        Double speed,
        Double armor,
        /** Issue #172 : résistance au recul (0..1, comme l'attribut vanilla {@code KNOCKBACK_RESISTANCE}). */
        Double knockbackResistance,
        /** Issue #172 : taille relative (attribut vanilla {@code SCALE}, 1.0 = taille normale). */
        Double scale,
        /** Issue #172 : rayon d'explosion si {@code entityType == CREEPER}, sinon doit rester {@code null}
         * (validé par le parseur -- jamais une option silencieusement ignorée pour un autre type). */
        Double creeperExplosionRadius,
        Particle particle,
        Sound sound,
        List<MobAbility> abilities,
        List<ResourceDrop> drops,
        Integer xpReward,
        Integer maxPopulation
) {

    public SpecialMobDefinition {
        if (id == null) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (category == null) {
            throw new IllegalArgumentException("category est obligatoire.");
        }
        if (entityType == null || !entityType.isAlive()) {
            throw new IllegalArgumentException("entityType doit être un type d'entité vivante valide.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName est obligatoire.");
        }
        if (spawnChance < 0 || spawnChance > 1) {
            throw new IllegalArgumentException("spawnChance doit être compris entre 0 et 1 : " + spawnChance);
        }
        allowedWorlds = allowedWorlds == null ? Set.of() : Set.copyOf(allowedWorlds);
        allowedBiomes = allowedBiomes == null ? Set.of() : Set.copyOf(allowedBiomes);
        allowedZones = allowedZones == null ? Set.of() : Set.copyOf(allowedZones);
        if (health != null && health <= 0) {
            throw new IllegalArgumentException("health doit être strictement positive si présente : " + health);
        }
        if (damage != null && damage < 0) {
            throw new IllegalArgumentException("damage ne peut pas être négative si présente : " + damage);
        }
        if (speed != null && speed <= 0) {
            throw new IllegalArgumentException("speed doit être strictement positive si présente : " + speed);
        }
        if (armor != null && armor < 0) {
            throw new IllegalArgumentException("armor ne peut pas être négative si présente : " + armor);
        }
        if (knockbackResistance != null && (knockbackResistance < 0 || knockbackResistance > 1)) {
            throw new IllegalArgumentException(
                    "knockbackResistance doit être compris entre 0 et 1 si présente : " + knockbackResistance);
        }
        if (scale != null && scale <= 0) {
            throw new IllegalArgumentException("scale doit être strictement positive si présente : " + scale);
        }
        if (creeperExplosionRadius != null) {
            if (entityType != EntityType.CREEPER) {
                throw new IllegalArgumentException(
                        "creeperExplosionRadius n'a de sens que pour entity-type: CREEPER (incompatible avec " + entityType + ").");
            }
            if (creeperExplosionRadius <= 0) {
                throw new IllegalArgumentException(
                        "creeperExplosionRadius doit être strictement positif si présent : " + creeperExplosionRadius);
            }
        }
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        drops = drops == null ? List.of() : List.copyOf(drops);
        if (xpReward != null && xpReward < 0) {
            throw new IllegalArgumentException("xpReward ne peut pas être négatif si présent : " + xpReward);
        }
        if (maxPopulation != null && maxPopulation < 0) {
            throw new IllegalArgumentException("maxPopulation ne peut pas être négatif si présent : " + maxPopulation);
        }
    }

    public int totalDropWeight() {
        int total = 0;
        for (ResourceDrop drop : drops) {
            total += drop.weight();
        }
        return total;
    }
}
