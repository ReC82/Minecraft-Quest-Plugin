package com.lodygames.rpgquest.mob.ability;

import static com.lodygames.rpgquest.mob.ability.AbilityParam.optional;
import static com.lodygames.rpgquest.mob.ability.AbilityParam.required;

import com.lodygames.rpgquest.mob.ability.AbilityDescriptor.Category;
import com.lodygames.rpgquest.mob.ability.AbilityDescriptor.Kind;
import com.lodygames.rpgquest.mob.ability.AbilityDescriptor.Status;
import com.lodygames.rpgquest.mob.ability.AbilityDescriptor.WorldMutation;
import com.lodygames.rpgquest.mob.ability.AbilityParam.ValueType;
import com.lodygames.rpgquest.mob.model.AbilityOptions;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/**
 * Issue #542 — registre unique des types de capacités de mobs : pour chacun, son
 * {@link AbilityDescriptor}, la construction du modèle à partir de valeurs validées et l'inverse.
 *
 * <p>Le parseur ({@code SpecialMobDefinitionParser}), le rendu disque ({@code SpecialMobDefinitionYaml})
 * et le contrat #214 ({@code SpecialMobSchema}) ne connaissent plus aucune capacité : ils lisent ce
 * registre. Ajouter une capacité = un record, une valeur de {@link MobAbilityType}, une entrée ici, un
 * exécuteur (voir {@code docs/development/ADDING_MOB_ABILITY.md}). Le {@code switch} de
 * {@link #entryFor} est exhaustif : une valeur d'enum sans entrée ne compile pas.</p>
 *
 * <p>Format « plat étendu » (décision Q1 de #541) : paramètres et champs communs au même niveau que
 * {@code type}. Un champ commun absent = {@link AbilityOptions#DEFAULTS}, et il n'est écrit que s'il
 * diffère du défaut : un profil existant se relit et se réécrit à l'identique (test « golden »).</p>
 */
public final class MobAbilityCatalog {

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_CHANCE = "chance";
    public static final String KEY_COOLDOWN = "cooldown-seconds";

    /** Champs communs gérés par le moteur, ajoutés à la fin des paramètres d'un type qui ne les déclare pas. */
    public static final List<AbilityParam> COMMON_PARAMS = List.of(
            optional(KEY_ENABLED, ValueType.BOOL, null, false, null, false, true, false, "true",
                    "Active", "false = capacité gardée avec ses réglages mais jamais déclenchée. Absent = true."),
            optional(KEY_CHANCE, ValueType.DECIMAL, 0.0, true, 1.0, false, 1.0, false, "1.0",
                    "Chance", "Chance de se déclencher à chaque occasion, de 0 (exclu) à 1. Absent = 1 (toujours)."),
            optional(KEY_COOLDOWN, ValueType.INT, 0.0, false, null, false, 0, false, "0",
                    "Délai (s)", "Secondes minimum entre deux déclenchements d'un même mob, entier ≥ 0. Absent = 0."));

    private static final Map<MobAbilityType, Entry<?>> ENTRIES = new EnumMap<>(MobAbilityType.class);

    static {
        for (MobAbilityType type : MobAbilityType.values()) {
            Entry<?> entry = entryFor(type);
            if (entry.descriptor().type() != type) {
                throw new IllegalStateException("descripteur incohérent pour " + type);
            }
            ENTRIES.put(type, entry);
        }
    }

    private MobAbilityCatalog() {
    }

    /** Un type de capacité : sa fiche, et la conversion valeurs validées ↔ modèle. */
    private record Entry<A extends MobAbility>(AbilityDescriptor descriptor, Class<A> modelClass,
                                               BiFunction<AbilityValues, AbilityOptions, A> create,
                                               Function<A, Map<String, Object>> values) {

        Map<String, Object> valuesOf(MobAbility ability) {
            return values.apply(modelClass.cast(ability));
        }
    }

    private static Entry<?> entryFor(MobAbilityType type) {
        return switch (type) {
            case STRONGER_EXPLOSION -> new Entry<>(descriptor(type, "Explosion renforcée",
                    "Explosion plus grande (creeper).", Category.EXPLOSION, Set.of(AbilityTrigger.EXPLOSION_PRIME),
                    Set.of(EntityType.CREEPER), 0,
                    List.of(required("radius-multiplier", ValueType.DECIMAL, 0.0, true, null, false, "1.5",
                            "Multiplicateur de rayon",
                            "Multiplie le rayon de SES explosions, > 0. N'a d'effet que si la créature explose (CREEPER).")),
                    presets(Map.of("radius-multiplier", 1.2), Map.of("radius-multiplier", 1.5),
                            Map.of("radius-multiplier", 2.0))),
                    StrongerExplosionAbility.class,
                    (v, o) -> new StrongerExplosionAbility(v.decimal("radius-multiplier"), o),
                    a -> ordered("radius-multiplier", a.radiusMultiplier()));
            case EXPLOSIVE_ON_ATTACK -> new Entry<>(descriptor(type, "Explosif au contact",
                    "Explose au contact de sa cible (s'auto-détruit).", Category.EXPLOSION,
                    Set.of(AbilityTrigger.TICK), Set.of(), 0,
                    List.of(required("power", ValueType.DECIMAL, 0.0, true, null, false, "2.0",
                                    "Puissance", "Puissance de l'explosion, > 0 (TNT = 4.0)."),
                            optional("set-fire", ValueType.BOOL, null, false, null, false, false, true, "false",
                                    "Met le feu", "true = l'explosion met le feu. Absent = false."),
                            required("trigger-range-blocks", ValueType.DECIMAL, 0.0, true, null, false, "2.0",
                                    "Portée (blocs)", "Distance (blocs, > 0) à laquelle elle explose près de sa cible.")),
                    presets(Map.of("power", 1.5, "trigger-range-blocks", 1.5),
                            Map.of("power", 2.0, "trigger-range-blocks", 2.0),
                            Map.of("power", 3.0, "trigger-range-blocks", 3.0))),
                    ExplosiveOnAttackAbility.class,
                    (v, o) -> new ExplosiveOnAttackAbility(v.decimal("power").floatValue(), v.bool("set-fire"),
                            v.decimal("trigger-range-blocks"), o),
                    // Float conservé tel quel : 2.3f s'écrit « 2.3 », comme avant #542.
                    a -> ordered("power", a.power(), "set-fire", a.setFire(),
                            "trigger-range-blocks", a.triggerRangeBlocks()));
            case SPLIT_ON_HIT -> new Entry<>(descriptor(type, "Division",
                    "Se divise en copies d'elle-même quand elle est touchée.", Category.REACTION,
                    Set.of(AbilityTrigger.DAMAGED), Set.of(), 0,
                    List.of(required("max-depth", ValueType.INT, 1.0, false, null, false, "2",
                                    "Générations", "Générations de divisions au plus, entier ≥ 1."),
                            required("max-children-per-hit", ValueType.INT, 1.0, false, null, false, "2",
                                    "Copies par coup", "Copies créées par coup reçu, entier ≥ 1."),
                            optional("max-alive-per-parent", ValueType.INT, 1.0, false, null, false, 2, true, "2",
                                    "Copies vivantes par parent",
                                    "Copies directes vivantes en même temps pour un même parent, entier ≥ 1. Absent = 2.")),
                    presets(Map.of("max-depth", 1, "max-children-per-hit", 1, "max-alive-per-parent", 1),
                            Map.of("max-depth", 2, "max-children-per-hit", 2, "max-alive-per-parent", 2),
                            Map.of("max-depth", 2, "max-children-per-hit", 3, "max-alive-per-parent", 3))),
                    SplitOnHitAbility.class,
                    (v, o) -> new SplitOnHitAbility(v.integer("max-depth"), v.integer("max-children-per-hit"),
                            v.integer("max-alive-per-parent"), o),
                    a -> ordered("max-depth", a.maxDepth(), "max-children-per-hit", a.maxChildrenPerHit(),
                            "max-alive-per-parent", a.maxAlivePerParent()));
            case ENRAGED -> new Entry<>(descriptor(type, "Enragé",
                    "Devient plus rapide et plus forte sous un seuil de vie (une seule fois).", Category.ENHANCEMENT,
                    Set.of(AbilityTrigger.TICK), Set.of(), 0,
                    List.of(required("health-fraction", ValueType.DECIMAL, 0.0, true, 1.0, true, "0.3",
                                    "Seuil de vie", "Seuil de vie qui déclenche la rage, strictement entre 0 et 1 (0.3 = sous 30 %)."),
                            required("speed-multiplier", ValueType.DECIMAL, 0.0, true, null, false, "1.3",
                                    "Vitesse en rage", "Multiplicateur de vitesse en rage, > 0."),
                            required("damage-multiplier", ValueType.DECIMAL, 0.0, true, null, false, "1.5",
                                    "Dégâts en rage", "Multiplicateur de dégâts en rage, > 0.")),
                    presets(Map.of("health-fraction", 0.25, "speed-multiplier", 1.1, "damage-multiplier", 1.2),
                            Map.of("health-fraction", 0.3, "speed-multiplier", 1.3, "damage-multiplier", 1.5),
                            Map.of("health-fraction", 0.4, "speed-multiplier", 1.5, "damage-multiplier", 2.0))),
                    EnragedAbility.class,
                    (v, o) -> new EnragedAbility(v.decimal("health-fraction"), v.decimal("speed-multiplier"),
                            v.decimal("damage-multiplier"), o),
                    a -> ordered("health-fraction", a.healthFraction(), "speed-multiplier", a.speedMultiplier(),
                            "damage-multiplier", a.damageMultiplier()));
            case SUMMON_ON_DAMAGE -> new Entry<>(descriptor(type, "Renforts",
                    "Invoque des renforts quand elle subit des dégâts.", Category.SUMMON,
                    Set.of(AbilityTrigger.DAMAGED, AbilityTrigger.DEATH), Set.of(), 0,
                    List.of(required("summon-entity-type", ValueType.ENTITY_TYPE, null, false, null, false, "ZOMBIE",
                                    "Créature invoquée", "Créature invoquée en renfort (vivante)."),
                            required("amount", ValueType.INT, 1.0, false, null, false, "2",
                                    "Renforts par invocation", "Renforts par invocation, entier ≥ 1."),
                            // Déclarés ici (obligatoires, toujours écrits) : même nom et même sens que les
                            // champs communs, qu'ils remplacent pour cette capacité.
                            required(KEY_CHANCE, ValueType.DECIMAL, 0.0, true, 1.0, false, "0.25",
                                    "Chance", "Chance d'invoquer à chaque coup reçu, de 0 (exclu) à 1."),
                            required(KEY_COOLDOWN, ValueType.INT, 0.0, false, null, false, "20",
                                    "Délai (s)", "Délai minimum entre deux invocations, entier ≥ 0."),
                            required("max-alive", ValueType.INT, 1.0, false, null, false, "4",
                                    "Renforts vivants au plus",
                                    "Renforts vivants au plus pour ce mob, entier ≥ 1 (plafond obligatoire, jamais d'invocation sans fin)."),
                            optional("despawn-with-owner", ValueType.BOOL, null, false, null, false, false, false, "false",
                                    "Disparaissent avec l'invocateur",
                                    "true = les renforts disparaissent à la mort de l'invocateur. Absent = false (ils restent).")),
                    presets(Map.of("amount", 1, KEY_CHANCE, 0.15, KEY_COOLDOWN, 30, "max-alive", 2),
                            Map.of("amount", 2, KEY_CHANCE, 0.25, KEY_COOLDOWN, 20, "max-alive", 4),
                            Map.of("amount", 3, KEY_CHANCE, 0.4, KEY_COOLDOWN, 15, "max-alive", 6))),
                    SummonOnDamageAbility.class,
                    (v, o) -> new SummonOnDamageAbility(v.entityType("summon-entity-type"), v.integer("amount"),
                            v.integer("max-alive"), v.bool("despawn-with-owner"), o),
                    a -> ordered("summon-entity-type", a.summonedEntityType(), "amount", a.amount(),
                            "max-alive", a.maxAlive(), "despawn-with-owner", a.despawnWithOwner()));
        };
    }

    // Tout projet de capacité actuel : passive, sans modification du terrain, disponible (Q8 : rien en développement).
    private static AbilityDescriptor descriptor(MobAbilityType type, String label, String summary, Category category,
                                                Set<AbilityTrigger> triggers, Set<EntityType> effectiveOn,
                                                int maxEntities, List<AbilityParam> own,
                                                Map<String, Map<String, Object>> presets) {
        List<AbilityParam> params = new ArrayList<>(own);
        for (AbilityParam common : COMMON_PARAMS) {
            if (own.stream().noneMatch(p -> p.key().equals(common.key()))) {
                params.add(common);
            }
        }
        return new AbilityDescriptor(type, label, summary, category, Status.AVAILABLE, Kind.PASSIVE, triggers, false,
                effectiveOn, params, presets, maxEntities, WorldMutation.NONE);
    }

    private static Map<String, Map<String, Object>> presets(Map<String, Object> soft, Map<String, Object> normal,
                                                           Map<String, Object> hard) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put("Doux", soft);
        out.put("Normal", normal);
        out.put("Brutal", hard);
        return out;
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    // ---- Lecture seule du registre -------------------------------------------------------------

    public static AbilityDescriptor descriptor(MobAbilityType type) {
        return ENTRIES.get(type).descriptor();
    }

    /** Tous les descripteurs, dans l'ordre de {@link MobAbilityType}. */
    public static List<AbilityDescriptor> descriptors() {
        return ENTRIES.values().stream().map(Entry::descriptor).toList();
    }

    /** Paramètres propres au type (sans les champs communs qu'il ne déclare pas lui-même). */
    public static List<AbilityParam> ownParams(MobAbilityType type) {
        return descriptor(type).params().stream()
                .filter(p -> !COMMON_PARAMS.contains(p))
                .toList();
    }

    /** Toutes les clés acceptées dans une entrée de ce type, {@code type} compris. */
    public static Set<String> acceptedKeys(MobAbilityType type) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add("type");
        descriptor(type).params().forEach(p -> keys.add(p.key()));
        return Set.copyOf(keys);
    }

    /** Résout la clé {@code type:} d'une entrée, ou {@code null} si elle est absente ou inconnue. */
    public static MobAbilityType typeOf(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return MobAbilityType.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- Lecture d'une entrée ------------------------------------------------------------------

    /**
     * Lit une entrée {@code abilities[i]}. Toutes les erreurs de l'entrée sont ajoutées à {@code errors}
     * (préfixées par {@code context}) ; {@code null} si l'une d'elles l'invalide.
     */
    public static MobAbility parse(ConfigurationSection section, String context, List<String> errors) {
        String rawType = section.getString("type");
        if (rawType == null || rawType.isBlank()) {
            errors.add(context + ": « type » est obligatoire.");
            return null;
        }
        MobAbilityType type = typeOf(rawType);
        if (type == null) {
            errors.add(context + ": capacité inconnue « " + rawType + " » (valides : "
                    + List.of(MobAbilityType.values()) + ").");
            return null;
        }
        Entry<?> entry = ENTRIES.get(type);
        int before = errors.size();
        Map<String, Object> values = new HashMap<>();
        for (AbilityParam param : entry.descriptor().params()) {
            values.put(param.key(), read(section, param, context, errors));
        }
        if (errors.size() > before) {
            return null;
        }
        try {
            AbilityOptions options = new AbilityOptions((Boolean) values.get(KEY_ENABLED),
                    (Double) values.get(KEY_CHANCE), (Integer) values.get(KEY_COOLDOWN));
            return entry.create().apply(new AbilityValues(values), options);
        } catch (IllegalArgumentException e) {
            errors.add(context + ": " + e.getMessage());
            return null;
        }
    }

    private static Object read(ConfigurationSection section, AbilityParam p, String context, List<String> errors) {
        String key = p.key();
        if (!section.isSet(key)) {
            if (p.required()) {
                errors.add(context + ": « " + key + " » est obligatoire" + switch (p.valueType()) {
                    case DECIMAL -> " et doit être un nombre.";
                    case INT -> " et doit être un entier.";
                    case BOOL -> " (true ou false).";
                    case ENTITY_TYPE -> ".";
                });
            }
            return p.defaultValue();
        }
        return switch (p.valueType()) {
            case DECIMAL -> {
                if (!(section.isDouble(key) || section.isInt(key))) {
                    errors.add(context + ": « " + key + (p.required() ? " » est obligatoire et doit être un nombre."
                            : " » doit être un nombre."));
                    yield null;
                }
                double value = section.getDouble(key);
                yield checkBounds(p, value, String.valueOf(value), context, errors) ? value : null;
            }
            case INT -> {
                if (!section.isInt(key)) {
                    errors.add(context + ": « " + key + (p.required() ? " » est obligatoire et doit être un entier."
                            : " » doit être un entier."));
                    yield null;
                }
                int value = section.getInt(key);
                yield checkBounds(p, value, String.valueOf(value), context, errors) ? value : null;
            }
            // Comme avant #542 (« set-fire ») : une valeur non booléenne vaut le défaut, sans erreur.
            case BOOL -> section.getBoolean(key, p.defaultValue() instanceof Boolean b && b);
            case ENTITY_TYPE -> {
                String raw = section.getString(key);
                if (raw == null || raw.isBlank()) {
                    errors.add(context + ": « " + key + " » est obligatoire.");
                    yield null;
                }
                EntityType entityType = EntityType.fromName(raw.toLowerCase(Locale.ROOT));
                if (entityType == null || !entityType.isAlive()) {
                    errors.add(context + ": « " + key + " » invalide ou non vivant : \"" + raw + "\".");
                    yield null;
                }
                yield entityType;
            }
        };
    }

    private static boolean checkBounds(AbilityParam p, double value, String shown, String context, List<String> errors) {
        if (p.inBounds(value)) {
            return true;
        }
        errors.add(context + ": « " + p.key() + " » " + p.boundsMessage() + ", valeur trouvée : " + shown);
        return false;
    }

    // ---- Écriture d'une entrée -----------------------------------------------------------------

    /** Écrit une entrée {@code abilities[i]} (indentation du fichier de profil), ordre des clés stable. */
    public static void render(MobAbility ability, StringBuilder sb) {
        Map<String, Object> values = values(ability);
        sb.append("  - type: ").append(ability.type().name()).append('\n');
        for (AbilityParam param : descriptor(ability.type()).params()) {
            Object value = values.get(param.key());
            if (param.shouldWrite(value)) {
                sb.append("    ").append(param.key()).append(": ").append(format(value)).append('\n');
            }
        }
    }

    /** Valeurs d'une instance, champs communs compris, par clé YAML. */
    public static Map<String, Object> values(MobAbility ability) {
        Map<String, Object> values = new LinkedHashMap<>(ENTRIES.get(ability.type()).valuesOf(ability));
        AbilityOptions options = ability.options();
        values.put(KEY_ENABLED, options.enabled());
        values.put(KEY_CHANCE, options.chance());
        values.put(KEY_COOLDOWN, options.cooldownSeconds());
        return values;
    }

    private static String format(Object value) {
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }
}
