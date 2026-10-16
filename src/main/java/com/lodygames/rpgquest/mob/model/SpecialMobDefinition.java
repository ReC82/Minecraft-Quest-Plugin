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
        Integer maxPopulation,
        /** Issue #175 : QUEST (défaut) ou COMMUNITY (scheduler du Wild) -- significatif pour BOSS seulement. */
        BossMode bossMode,
        /** Issue #175 : poids relatif dans le tirage pondéré des boss communautaires (≥ 1). */
        int communityWeight,
        /** Issue #176 : clés du catalogue {@code entitlements.yml} remises à chaque participant éligible
         * d'un boss communautaire vaincu -- BOSS seulement, vide = aucune récompense de participation. */
        List<String> participationRewards
) {

    public static final int DEFAULT_COMMUNITY_WEIGHT = 1;
    /** Issue #176 : même forme qu'une clé du catalogue {@code entitlements.yml}. */
    /** Clé du catalogue {@code entitlements.yml}, ou {@code table:<id>} pour un tirage aléatoire (issue #177). */
    public static final java.util.regex.Pattern REWARD_KEY =
            java.util.regex.Pattern.compile("table:[a-z0-9_-]{1,32}|[a-z0-9_.-]{1,64}");
    /** Préfixe d'une référence à une table de récompenses aléatoires (issue #177). */
    public static final String TABLE_PREFIX = "table:";
    public static final int MAX_PARTICIPATION_REWARDS = 5;

    /** Forme antérieure à l'issue #175 : boss de quête, poids par défaut. */
    public SpecialMobDefinition(NamespacedKey id, MobCategory category, boolean enabled, EntityType entityType,
                                String displayName, double spawnChance, Set<String> allowedWorlds,
                                Set<String> allowedBiomes, Set<String> allowedZones, Double health, Double damage,
                                Double speed, Double armor, Double knockbackResistance, Double scale,
                                Double creeperExplosionRadius, Particle particle, Sound sound,
                                List<MobAbility> abilities, List<ResourceDrop> drops, Integer xpReward,
                                Integer maxPopulation) {
        this(id, category, enabled, entityType, displayName, spawnChance, allowedWorlds, allowedBiomes,
                allowedZones, health, damage, speed, armor, knockbackResistance, scale, creeperExplosionRadius,
                particle, sound, abilities, drops, xpReward, maxPopulation, BossMode.QUEST, DEFAULT_COMMUNITY_WEIGHT);
    }

    /** Forme de l'issue #175 : sans récompense de participation. */
    public SpecialMobDefinition(NamespacedKey id, MobCategory category, boolean enabled, EntityType entityType,
                                String displayName, double spawnChance, Set<String> allowedWorlds,
                                Set<String> allowedBiomes, Set<String> allowedZones, Double health, Double damage,
                                Double speed, Double armor, Double knockbackResistance, Double scale,
                                Double creeperExplosionRadius, Particle particle, Sound sound,
                                List<MobAbility> abilities, List<ResourceDrop> drops, Integer xpReward,
                                Integer maxPopulation, BossMode bossMode, int communityWeight) {
        this(id, category, enabled, entityType, displayName, spawnChance, allowedWorlds, allowedBiomes,
                allowedZones, health, damage, speed, armor, knockbackResistance, scale, creeperExplosionRadius,
                particle, sound, abilities, drops, xpReward, maxPopulation, bossMode, communityWeight, List.of());
    }

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
        bossMode = bossMode == null ? BossMode.QUEST : bossMode;
        if (bossMode == BossMode.COMMUNITY && category != MobCategory.BOSS) {
            throw new IllegalArgumentException("boss-mode: COMMUNITY n'a de sens que pour category: BOSS.");
        }
        if (communityWeight < 1) {
            throw new IllegalArgumentException("communityWeight doit être au moins 1 : " + communityWeight);
        }
        participationRewards = participationRewards == null ? List.of()
                : List.copyOf(new java.util.LinkedHashSet<>(participationRewards));
        if (!participationRewards.isEmpty() && category != MobCategory.BOSS) {
            throw new IllegalArgumentException("participation-rewards n'a de sens que pour category: BOSS.");
        }
        if (participationRewards.size() > MAX_PARTICIPATION_REWARDS) {
            throw new IllegalArgumentException("participation-rewards : " + MAX_PARTICIPATION_REWARDS
                    + " récompenses au plus.");
        }
        for (String key : participationRewards) {
            if (key == null || !REWARD_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("participation-rewards : clé invalide « " + key
                        + " » (minuscules, chiffres, _ . -, ou table:<id>).");
            }
        }
    }

    /** Issue #175 : profil éligible au scheduler des boss communautaires (BOSS + COMMUNITY + activé). */
    public boolean isCommunityBoss() {
        return enabled && category == MobCategory.BOSS && bossMode == BossMode.COMMUNITY;
    }

    /** Issue #171 : profil utilisable comme boss de quête personnel (BOSS + QUEST + activé). */
    public boolean isQuestBoss() {
        return enabled && category == MobCategory.BOSS && bossMode == BossMode.QUEST;
    }

    /** Copie avec un autre état d'activation (bascule panel) -- préserve tous les autres champs. */
    public SpecialMobDefinition withEnabled(boolean value) {
        return new SpecialMobDefinition(id, category, value, entityType, displayName, spawnChance, allowedWorlds,
                allowedBiomes, allowedZones, health, damage, speed, armor, knockbackResistance, scale,
                creeperExplosionRadius, particle, sound, abilities, drops, xpReward, maxPopulation, bossMode,
                communityWeight, participationRewards);
    }

    public int totalDropWeight() {
        int total = 0;
        for (ResourceDrop drop : drops) {
            total += drop.weight();
        }
        return total;
    }
}
