package com.lodygames.rpgquest.stats;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boss;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Warden;

/**
 * Dimensions des classements (issue #734) : quelles cibles un classement peut filtrer, et par
 * quelles familles.
 *
 * <p>Le catalogue des entités est <strong>généré depuis l'énumération Paper</strong>
 * ({@link EntityType}) : chaque type vivant reçoit une catégorie déduite de sa classe Paper
 * (boss, hostile, non hostile), les autres sont exposés comme exclus avec leur raison (projectiles,
 * véhicules, joueurs — le PvP n'est pas du PvE). Les familles nommées ({@code zombies},
 * {@code ores}…) viennent de {@code stats/families.yml}, lu dans le JAR ; leurs noms sont vérifiés
 * contre Paper, un nom inconnu est signalé par {@link #unknownNames()} et ignoré.</p>
 *
 * <p>Rien n'est lu dans le monde ni dans le serveur : utilisable au chargement de
 * {@code statistics.yml}, dans n'importe quel fil.</p>
 */
public final class StatDimensions {

    /** Familles filtrables : {@code entity-family} (types d'entités) et {@code block-family} (matériaux). */
    public enum Kind {
        ENTITY("entity-family"),
        BLOCK("block-family");

        private final String filterKey;

        Kind(String filterKey) {
            this.filterKey = filterKey;
        }

        public String filterKey() {
            return filterKey;
        }

        public static Optional<Kind> ofFilterKey(String key) {
            for (Kind kind : values()) {
                if (kind.filterKey.equals(key)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** Catégorie publique d'un type d'entité, déduite de sa classe Paper. */
    public enum EntityCategory {
        BOSS,
        HOSTILE,
        PASSIVE,
        /** Jamais compté : non vivant, joueur (PvP) ou décor. */
        EXCLUDED
    }

    /** Une famille : identifiant, libellé français, membres (noms d'énumération Paper). */
    public record Family(Kind kind, String id, String label, Set<String> members, boolean generated) {
        public Family {
            members = Set.copyOf(members);
        }
    }

    /** Un type d'entité du catalogue généré, avec sa catégorie et, s'il est exclu, la raison. */
    public record EntityEntry(String type, EntityCategory category, String reason) {
        public boolean countable() {
            return category != EntityCategory.EXCLUDED;
        }
    }

    private static final String RESOURCE = "/stats/families.yml";
    /** Types vivants qui ne sont pas des adversaires : décor, joueur. */
    private static final Map<String, String> EXCLUDED_LIVING = Map.of(
            "PLAYER", "un joueur n'est pas un monstre : le PvP ne compte pas comme PvE",
            "ARMOR_STAND", "décor, pas une créature",
            "MANNEQUIN", "décor, pas une créature");
    private static final Map<EntityCategory, String[]> GENERATED = new EnumMap<>(Map.of(
            EntityCategory.BOSS, new String[] {"boss", "Boss"},
            EntityCategory.HOSTILE, new String[] {"hostile", "Monstres hostiles"},
            EntityCategory.PASSIVE, new String[] {"passive", "Créatures non hostiles"}));

    private static volatile StatDimensions bundled;

    private final List<EntityEntry> entities;
    private final Map<Kind, Map<String, Family>> families;
    private final List<String> unknownNames;
    private final Map<String, String> entityLabels;
    private final Map<String, EntityCategory> categories = new java.util.HashMap<>();

    private StatDimensions(List<EntityEntry> entities, Map<Kind, Map<String, Family>> families,
                           List<String> unknownNames, Map<String, String> entityLabels) {
        this.entities = List.copyOf(entities);
        this.families = families;
        this.unknownNames = List.copyOf(unknownNames);
        this.entityLabels = Map.copyOf(entityLabels);
        entities.forEach(entry -> categories.put(entry.type(), entry.category()));
    }

    /** Dimensions du JAR (familles de {@code stats/families.yml}), chargées une fois. */
    public static StatDimensions bundled() {
        StatDimensions current = bundled;
        if (current == null) {
            synchronized (StatDimensions.class) {
                current = bundled;
                if (current == null) {
                    current = fromYaml(readResource());
                    bundled = current;
                }
            }
        }
        return current;
    }

    /** Dimensions décrites par un YAML au format de {@code stats/families.yml} (tests). */
    static StatDimensions fromYaml(String yaml) {
        YamlConfiguration root = new YamlConfiguration();
        try {
            root.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException("stats/families.yml illisible", e);
        }
        List<EntityEntry> entities = generateEntities();
        List<String> unknown = new ArrayList<>();
        Map<Kind, Map<String, Family>> families = new EnumMap<>(Kind.class);
        Map<String, Family> entityFamilies = new LinkedHashMap<>();
        for (EntityCategory category : List.of(EntityCategory.BOSS, EntityCategory.HOSTILE, EntityCategory.PASSIVE)) {
            String[] id = GENERATED.get(category);
            Set<String> members = new TreeSet<>();
            entities.stream().filter(entry -> entry.category() == category).forEach(entry -> members.add(entry.type()));
            entityFamilies.put(id[0], new Family(Kind.ENTITY, id[0], id[1], members, true));
        }
        Set<String> entityNames = names(EntityType.values());
        readFamilies(root.getConfigurationSection("entity"), Kind.ENTITY, entityNames, entityFamilies, unknown);
        families.put(Kind.ENTITY, entityFamilies);
        Map<String, Family> blockFamilies = new LinkedHashMap<>();
        readFamilies(root.getConfigurationSection("block"), Kind.BLOCK, materialNames(), blockFamilies, unknown);
        families.put(Kind.BLOCK, blockFamilies);
        Map<String, String> labels = new LinkedHashMap<>();
        ConfigurationSection labelSection = root.getConfigurationSection("entity-labels");
        if (labelSection != null) {
            for (String name : labelSection.getKeys(false)) {
                if (entityNames.contains(name)) {
                    labels.put(name, labelSection.getString(name, name));
                } else {
                    unknown.add("entity-labels : " + name);
                }
            }
        }
        return new StatDimensions(entities, families, unknown, labels);
    }

    /** Tous les types d'entités Paper, dans l'ordre de l'énumération, avec leur catégorie. */
    public List<EntityEntry> entities() {
        return entities;
    }

    public List<Family> families(Kind kind) {
        return List.copyOf(families.get(kind).values());
    }

    public Optional<Family> family(Kind kind, String id) {
        return Optional.ofNullable(families.get(kind).get(id));
    }

    /**
     * Nom français d'un type d'entité ({@code entity-labels} de {@code families.yml}) ; à défaut son
     * nom technique lisible ({@code ZOMBIE_HORSE} → « Zombie horse ») — jamais masqué.
     */
    public String entityLabel(String type) {
        String label = entityLabels.get(type);
        if (label != null) {
            return label;
        }
        String plain = type.replace('_', ' ').toLowerCase(Locale.ROOT);
        return plain.isEmpty() ? plain : Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
    }

    /** Catégorie d'un type d'entité par son nom ; {@link EntityCategory#EXCLUDED} s'il est inconnu. */
    public EntityCategory categoryOf(String type) {
        return categories.getOrDefault(type, EntityCategory.EXCLUDED);
    }

    /** Types nommés dans {@code entity-labels}. */
    public Set<String> labelledEntities() {
        return entityLabels.keySet();
    }

    /** Noms de {@code families.yml} inconnus de Paper (faute de frappe, type retiré) : à corriger. */
    public List<String> unknownNames() {
        return unknownNames;
    }

    /** Catégorie d'un type d'entité, déduite uniquement de sa classe Paper. */
    public static EntityCategory category(EntityType type) {
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !type.isAlive() || !LivingEntity.class.isAssignableFrom(entityClass)
                || EXCLUDED_LIVING.containsKey(type.name())) {
            return EntityCategory.EXCLUDED;
        }
        if (Boss.class.isAssignableFrom(entityClass) || ElderGuardian.class.isAssignableFrom(entityClass)
                || Warden.class.isAssignableFrom(entityClass)) {
            return EntityCategory.BOSS;
        }
        if (Enemy.class.isAssignableFrom(entityClass)) {
            return EntityCategory.HOSTILE;
        }
        return EntityCategory.PASSIVE;
    }

    private static List<EntityEntry> generateEntities() {
        List<EntityEntry> entries = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            EntityCategory category = category(type);
            String reason = category != EntityCategory.EXCLUDED ? null
                    : EXCLUDED_LIVING.getOrDefault(type.name(), "pas une créature (objet, projectile, véhicule…)");
            entries.add(new EntityEntry(type.name(), category, reason));
        }
        return entries;
    }

    private static void readFamilies(ConfigurationSection section, Kind kind, Set<String> known,
                                     Map<String, Family> target, List<String> unknown) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection family = section.getConfigurationSection(id);
            if (family == null) {
                continue;
            }
            Set<String> members = new TreeSet<>();
            for (String name : family.getStringList("members")) {
                String constant = name.trim().toUpperCase(Locale.ROOT);
                if (known.contains(constant)) {
                    members.add(constant);
                } else {
                    unknown.add(kind.filterKey() + " « " + id + " » : " + name);
                }
            }
            for (String suffix : family.getStringList("suffix")) {
                known.stream().filter(name -> name.endsWith(suffix)).forEach(members::add);
            }
            target.put(id, new Family(kind, id, family.getString("label", id), members, false));
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new TreeSet<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    /** Matériaux actuels (sans les anciens {@code LEGACY_*}) ; jamais {@code isBlock()}, qui demande un serveur. */
    @SuppressWarnings("deprecation")
    private static Set<String> materialNames() {
        Set<String> names = new TreeSet<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy()) {
                names.add(material.name());
            }
        }
        return names;
    }

    private static String readResource() {
        try (InputStream in = StatDimensions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " absent du JAR");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + " illisible", e);
        }
    }
}
