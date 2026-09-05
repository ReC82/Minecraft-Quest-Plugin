package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
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
        Particle particle = parseOptionalParticle(section, errors);
        Sound sound = parseOptionalSound(section, errors);
        List<MobAbility> abilities = parseAbilities(section, errors);
        List<ResourceDrop> drops = parseDrops(section, errors);
        Integer xpReward = parseOptionalNonNegativeInt(section, "xp-reward", errors);
        Integer maxPopulation = parseOptionalNonNegativeInt(section, "max-population", errors);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new SpecialMobLoadIssue(fileName, m)).toList());
        }

        try {
            SpecialMobDefinition definition = new SpecialMobDefinition(id, entityType, displayName, spawnChance,
                    worlds, biomes, zones, health, damage, speed, armor, particle, sound, abilities, drops,
                    xpReward, maxPopulation);
            return ParseResult.success(definition);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new SpecialMobLoadIssue(fileName, e.getMessage())));
        }
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

    private MobAbility parseAbility(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }
        MobAbilityType type;
        try {
            type = MobAbilityType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(context + ": capacité inconnue « " + rawType + " » (valides : "
                    + List.of(MobAbilityType.values()) + ").");
            return null;
        }

        return switch (type) {
            case STRONGER_EXPLOSION -> {
                Double multiplier = parsePositiveDoubleRequired(section, "radius-multiplier", context, errors);
                yield multiplier == null ? null : new StrongerExplosionAbility(multiplier);
            }
            case EXPLOSIVE_ON_ATTACK -> {
                Double power = parsePositiveDoubleRequired(section, "power", context, errors);
                boolean setFire = section.getBoolean("set-fire", false);
                Double range = parsePositiveDoubleRequired(section, "trigger-range-blocks", context, errors);
                yield (power == null || range == null) ? null
                        : new ExplosiveOnAttackAbility(power.floatValue(), setFire, range);
            }
            case SPLIT_ON_HIT -> {
                Integer maxDepth = parsePositiveIntRequired(section, "max-depth", context, errors);
                Integer maxChildren = parsePositiveIntRequired(section, "max-children-per-hit", context, errors);
                yield (maxDepth == null || maxChildren == null) ? null : new SplitOnHitAbility(maxDepth, maxChildren);
            }
        };
    }

    private Double parsePositiveDoubleRequired(ConfigurationSection section, String key, String context, List<String> errors) {
        if (!section.isSet(key) || !(section.isDouble(key) || section.isInt(key))) {
            errors.add(context + ": « " + key + " » est obligatoire et doit être un nombre.");
            return null;
        }
        double value = section.getDouble(key);
        if (value <= 0) {
            errors.add(context + ": « " + key + " » doit être strictement positif, valeur trouvée : " + value);
            return null;
        }
        return value;
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
