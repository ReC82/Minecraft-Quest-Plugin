package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.ability.MobAbilityCatalog;
import com.lodygames.rpgquest.mob.model.BossMode;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.resource.model.CustomItemDrop;
import com.lodygames.rpgquest.resource.model.ResourceDrop;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Valide et construit une {@link SpecialMobDefinition} à partir d'un fichier
 * YAML déjà parsé. Même conception que {@code ItemDefinitionParser}/{@code
 * ResourceNodeDefinitionParser} : purement structurel (un seul fichier à la
 * fois), ne dépend que de {@link ConfigurationSection} (testable sans
 * MockBukkit — {@code EntityType.fromName}/{@code Material.matchMaterial}
 * sont de simples enums résolubles sans serveur, comme {@code Particle}/
 * {@code Sound}), accumule toutes les erreurs avant d'échouer.
 */
final class SpecialMobDefinitionParser {

    static final String DEFAULT_NAMESPACE = "rpgquest";

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        NamespacedKey id = parseId(section, errors);
        MobCategory category = parseCategory(section, errors);
        boolean enabled = section.getBoolean("enabled", true);
        EntityType entityType = parseEntityType(section, errors);
        String displayName = parseDisplayName(section, errors);
        double spawnChance = parseSpawnChance(section, errors);
        Set<String> worlds = lowercaseSet(section.getStringList("worlds"));
        Set<String> biomes = lowercaseSet(section.getStringList("biomes"));
        Set<String> zones = lowercaseSet(section.getStringList("zones"));
        Double health = parseOptionalPositiveDouble(section, "health", errors);
        Double damage = parseOptionalNonNegativeDouble(section, "damage", errors);
        Double speed = parseOptionalPositiveDouble(section, "speed", errors);
        Double armor = parseOptionalNonNegativeDouble(section, "armor", errors);
        Double knockbackResistance = parseOptionalFraction(section, "knockback-resistance", errors);
        Double scale = parseOptionalPositiveDouble(section, "scale", errors);
        Double creeperExplosionRadius = parseOptionalPositiveDouble(section, "creeper-explosion-radius", errors);
        if (creeperExplosionRadius != null && entityType != null && entityType != EntityType.CREEPER) {
            errors.add("« creeper-explosion-radius » n'a de sens que pour entity-type: CREEPER (trouvé : "
                    + entityType + ").");
        }
        Particle particle = parseOptionalParticle(section, errors);
        Sound sound = parseOptionalSound(section, errors);
        List<MobAbility> abilities = parseAbilities(section, errors);
        List<ResourceDrop> drops = parseDrops(section, errors);
        Integer xpReward = parseOptionalNonNegativeInt(section, "xp-reward", errors);
        Integer maxPopulation = parseOptionalNonNegativeInt(section, "max-population", errors);
        BossMode bossMode = parseBossMode(section, category, errors);
        int communityWeight = parseCommunityWeight(section, errors);
        List<String> participationRewards = parseParticipationRewards(section, category, errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new SpecialMobLoadIssue(fileName, m)).toList());
        }

        try {
            SpecialMobDefinition definition = new SpecialMobDefinition(id, category, enabled, entityType,
                    displayName, spawnChance, worlds, biomes, zones, health, damage, speed, armor,
                    knockbackResistance, scale, creeperExplosionRadius, particle, sound, abilities, drops,
                    xpReward, maxPopulation, bossMode, communityWeight, participationRewards);
            return ParseResult.success(definition);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new SpecialMobLoadIssue(fileName, e.getMessage())));
        }
    }

    /**
     * Optionnelle : les profils antérieurs à l'issue #172 n'ont pas de « category » et restent
     * valides en se comportant comme avant (éligibles au tirage aléatoire) -- défaut {@code SPECIAL}.
     */
    private MobCategory parseCategory(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("category");
        if (raw == null || raw.isBlank()) {
            return MobCategory.SPECIAL;
        }
        try {
            return MobCategory.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("« category » invalide : \"" + raw + "\" (valides : " + List.of(MobCategory.values()) + ").");
            return null;
        }
    }

    /**
     * Issue #175 : optionnel, défaut {@code QUEST} (profils antérieurs inchangés). {@code COMMUNITY}
     * est refusé sur un profil SPECIAL -- jamais une option silencieusement ignorée.
     */
    private BossMode parseBossMode(ConfigurationSection section, MobCategory category, List<String> errors) {
        String raw = section.getString("boss-mode");
        if (raw == null || raw.isBlank()) {
            return BossMode.QUEST;
        }
        BossMode mode;
        try {
            mode = BossMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("« boss-mode » invalide : \"" + raw + "\" (valides : " + List.of(BossMode.values()) + ").");
            return BossMode.QUEST;
        }
        if (mode == BossMode.COMMUNITY && category != null && category != MobCategory.BOSS) {
            errors.add("« boss-mode: COMMUNITY » n'a de sens que pour « category: BOSS ».");
        }
        return mode;
    }

    private int parseCommunityWeight(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("community-weight")) {
            return SpecialMobDefinition.DEFAULT_COMMUNITY_WEIGHT;
        }
        if (!section.isInt("community-weight") || section.getInt("community-weight") < 1) {
            errors.add("« community-weight » doit être un entier supérieur ou égal à 1.");
            return SpecialMobDefinition.DEFAULT_COMMUNITY_WEIGHT;
        }
        return section.getInt("community-weight");
    }

    /**
     * Issue #176 : liste facultative de clés du catalogue {@code entitlements.yml}, BOSS seulement.
     * L'existence de la clé n'est pas vérifiée ici (catalogue relu à chaud) : une clé inconnue laisse
     * le droit en attente avec l'erreur, visible dans PlugAdmin.
     */
    private List<String> parseParticipationRewards(ConfigurationSection section, MobCategory category,
                                                   List<String> errors) {
        if (!section.isSet("participation-rewards")) {
            return List.of();
        }
        if (!section.isList("participation-rewards")) {
            errors.add("« participation-rewards » doit être une liste de clés de entitlements.yml.");
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String raw : section.getStringList("participation-rewards")) {
            String key = raw == null ? "" : raw.trim();
            if (!SpecialMobDefinition.REWARD_KEY.matcher(key).matches()) {
                errors.add("« participation-rewards » : clé invalide \"" + raw + "\" (minuscules, chiffres, _ . -, ou table:<id>).");
            } else if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        if (keys.size() > SpecialMobDefinition.MAX_PARTICIPATION_REWARDS) {
            errors.add("« participation-rewards » : " + SpecialMobDefinition.MAX_PARTICIPATION_REWARDS
                    + " récompenses au plus.");
        }
        if (!keys.isEmpty() && category != null && category != MobCategory.BOSS) {
            errors.add("« participation-rewards » n'a de sens que pour « category: BOSS ».");
        }
        return keys;
    }

    private Double parseOptionalFraction(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!(section.isDouble(key) || section.isInt(key))) {
            errors.add("« " + key + " » doit être un nombre.");
            return null;
        }
        double value = section.getDouble(key);
        if (value < 0 || value > 1) {
            errors.add("« " + key + " » doit être compris entre 0 et 1, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private NamespacedKey parseId(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("id");
        if (raw == null || raw.isBlank()) {
            errors.add("« id » est obligatoire.");
            return null;
        }
        NamespacedKey id = toNamespacedKey(raw);
        if (id == null) {
            errors.add("« id » invalide : \"" + raw + "\".");
        }
        return id;
    }

    private EntityType parseEntityType(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("entity-type");
        if (raw == null || raw.isBlank()) {
            errors.add("« entity-type » est obligatoire.");
            return null;
        }
        EntityType entityType = EntityType.fromName(raw.toLowerCase(Locale.ROOT));
        if (entityType == null || !entityType.isAlive()) {
            errors.add("« entity-type » invalide ou non vivant : \"" + raw + "\".");
            return null;
        }
        return entityType;
    }

    private String parseDisplayName(ConfigurationSection section, List<String> errors) {
        String name = section.getString("name");
        if (name == null || name.isBlank()) {
            errors.add("« name » est obligatoire.");
            return null;
        }
        return name;
    }

    private double parseSpawnChance(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("spawn-chance")) {
            errors.add("« spawn-chance » est obligatoire.");
            return 0;
        }
        if (!(section.isDouble("spawn-chance") || section.isInt("spawn-chance"))) {
            errors.add("« spawn-chance » doit être un nombre.");
            return 0;
        }
        double value = section.getDouble("spawn-chance");
        if (value < 0 || value > 1) {
            errors.add("« spawn-chance » doit être compris entre 0 et 1, valeur trouvée : " + value);
            return 0;
        }
        return value;
    }

    private Set<String> lowercaseSet(List<String> raw) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                result.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private Double parseOptionalPositiveDouble(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!(section.isDouble(key) || section.isInt(key))) {
            errors.add("« " + key + " » doit être un nombre.");
            return null;
        }
        double value = section.getDouble(key);
        if (value <= 0) {
            errors.add("« " + key + " » doit être strictement positif si présent, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private Double parseOptionalNonNegativeDouble(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!(section.isDouble(key) || section.isInt(key))) {
            errors.add("« " + key + " » doit être un nombre.");
            return null;
        }
        double value = section.getDouble(key);
        if (value < 0) {
            errors.add("« " + key + " » ne peut pas être négatif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private Integer parseOptionalNonNegativeInt(ConfigurationSection section, String key, List<String> errors) {
        if (!section.isSet(key)) {
            return null;
        }
        if (!section.isInt(key)) {
            errors.add("« " + key + " » doit être un entier.");
            return null;
        }
        int value = section.getInt(key);
        if (value < 0) {
            errors.add("« " + key + " » ne peut pas être négatif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    private Particle parseOptionalParticle(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("particle");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("« particle » inconnue : \"" + raw + "\".");
            return null;
        }
    }

    // Sound#valueOf est marqué "removal" au profit de Registry.SOUNDS, mais ce
    // dernier ne s'indexe que par clé namespacée en points ("entity.creeper.primed"),
    // pas par le nom d'enum classique attendu dans le YAML ("ENTITY_CREEPER_PRIMED").
    // On garde valueOf tant que Paper ne fournit pas de résolution par nom d'enum
    // sur le registre.
    @SuppressWarnings("removal")
    private Sound parseOptionalSound(ConfigurationSection section, List<String> errors) {
        String raw = section.getString("sound");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("« sound » inconnu : \"" + raw + "\".");
            return null;
        }
    }

    // ---- Capacités ----------------------------------------------------------

    private List<MobAbility> parseAbilities(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("abilities")) {
            return List.of();
        }
        List<MobAbility> abilities = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("abilities");
        for (int i = 0; i < raw.size(); i++) {
            MobAbility ability = parseAbility(toSection(raw.get(i)), "abilities[" + i + "]", errors);
            if (ability != null) {
                abilities.add(ability);
            }
        }
        return abilities;
    }

    /** Issue #542 : le contrat de chaque capacité vit dans {@link MobAbilityCatalog}, plus ici. */
    private MobAbility parseAbility(ConfigurationSection section, String context, List<String> errors) {
        return MobAbilityCatalog.parse(section, context, errors);
    }

    private Integer parsePositiveIntRequired(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key) || !section.isInt(key)) {
            errors.add(context + ": « " + key + " » est obligatoire et doit être un entier.");
            return null;
        }
        int value = section.getInt(key);
        if (value <= 0) {
            errors.add(context + ": « " + key + " » doit être strictement positif, valeur trouvée : " + value);
            return null;
        }
        return value;
    }

    // ---- Drops (réutilise exactement la conception de ResourceNodeDefinitionParser) -----------

    private List<ResourceDrop> parseDrops(ConfigurationSection section, List<String> errors) {
        if (!section.isSet("drops")) {
            return List.of();
        }
        List<ResourceDrop> drops = new ArrayList<>();
        List<Map<?, ?>> raw = section.getMapList("drops");
        for (int i = 0; i < raw.size(); i++) {
            ResourceDrop drop = parseDrop(toSection(raw.get(i)), "drops[" + i + "]", errors);
            if (drop != null) {
                drops.add(drop);
            }
        }
        return drops;
    }

    private ResourceDrop parseDrop(ConfigurationSection section, String context, List<String> errors) {
        boolean hasCustomItem = section.isSet("custom-item");
        boolean hasMaterial = section.isSet("material");
        if (hasCustomItem == hasMaterial) {
            errors.add(context + ": exactement un de « custom-item » ou « material » est requis.");
            return null;
        }

        Integer weight = parsePositiveIntRequired(section, "weight", context, errors);
        Integer minAmount = parsePositiveIntRequired(section, "min-amount", context, errors);
        Integer maxAmount = parsePositiveIntRequired(section, "max-amount", context, errors);
        if (weight == null || minAmount == null || maxAmount == null) {
            return null;
        }
        if (maxAmount < minAmount) {
            errors.add(context + ": « max-amount » doit être supérieur ou égal à « min-amount ».");
            return null;
        }

        if (hasCustomItem) {
            String raw = section.getString("custom-item");
            NamespacedKey itemId = raw == null ? null : toNamespacedKey(raw);
            if (itemId == null) {
                errors.add(context + ": « custom-item » invalide « " + raw + " ».");
                return null;
            }
            return new CustomItemDrop(itemId, weight, minAmount, maxAmount);
        }

        String raw = section.getString("material");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null) {
            errors.add(context + ": matériau inconnu « " + raw + " ».");
            return null;
        }
        return new VanillaItemDrop(material, weight, minAmount, maxAmount);
    }

    private NamespacedKey toNamespacedKey(String raw) {
        try {
            return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration memory = new MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }

    record ParseResult(SpecialMobDefinition definition, List<SpecialMobLoadIssue> issues) {

        static ParseResult success(SpecialMobDefinition definition) {
            return new ParseResult(definition, List.of());
        }

        static ParseResult failure(List<SpecialMobLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return definition != null;
        }
    }
}
