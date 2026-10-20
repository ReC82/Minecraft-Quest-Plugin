package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Issue #543 — ce que PlugAdmin lit et envoie au sujet des capacités, dérivé du seul
 * {@link MobAbilityCatalog} : le panel ne code aucune capacité en dur.
 *
 * <ul>
 *   <li>{@link #catalogDetails()} : le catalogue des descripteurs (action {@code mob.ability.catalog},
 *       repris dans {@code mob.list}) — libellés, bornes, aides, préréglages, garanties.</li>
 *   <li>{@link #instanceView} : une capacité d'un profil, telle que les cartes du formulaire l'affichent.</li>
 *   <li>{@link #parseEntries} : la liste structurée envoyée par le formulaire (paramètre {@code abilities}),
 *       revalidée par le parseur réel ({@link MobAbilityCatalog#parse}) avec les mêmes messages qu'un
 *       fichier. Les valeurs arrivent en texte ; elles sont converties selon le type déclaré du
 *       paramètre, et une valeur non convertible est laissée telle quelle pour que le parseur la refuse.</li>
 * </ul>
 */
public final class MobAbilityExchange {

    /** Entrées acceptées au plus dans une liste envoyée (au-delà du plafond Q2, pour que la règle s'exprime). */
    static final int MAX_ENTRIES = MobAbilityRules.MAX_ABILITIES_PER_PROFILE * 2;
    private static final int MAX_VALUE_CHARS = 64;

    private MobAbilityExchange() {
    }

    /** Résultat de {@link #parseEntries} : capacités dans l'ordre reçu, ou erreurs (jamais les deux). */
    public record Parsed(List<MobAbility> abilities, List<String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    // ---- Catalogue -----------------------------------------------------------------------------

    /** Le catalogue complet, prêt à être sérialisé en JSON (ordre de {@link MobAbilityType}). */
    public static Map<String, Object> catalogDetails() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (AbilityDescriptor d : MobAbilityCatalog.descriptors()) {
            types.add(descriptorView(d));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxPerProfile", MobAbilityRules.MAX_ABILITIES_PER_PROFILE);
        out.put("types", types);
        return out;
    }

    static Map<String, Object> descriptorView(AbilityDescriptor d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", d.type().name());
        out.put("label", d.label());
        out.put("summary", d.summary());
        out.put("category", d.category().name());
        out.put("status", d.status().name());
        out.put("kind", d.kind().name());
        out.put("triggers", d.triggers().stream().map(Enum::name).sorted().toList());
        out.put("repeatable", d.repeatable());
        Set<String> effectiveOn = new TreeSet<>();
        d.effectiveOn().forEach(t -> effectiveOn.add(t.name()));
        out.put("effectiveOn", List.copyOf(effectiveOn));
        out.put("maxEntitiesPerActivation", d.maxEntitiesPerActivation());
        out.put("worldMutation", d.worldMutation().name());
        List<Map<String, Object>> params = new ArrayList<>();
        for (AbilityParam p : d.params()) {
            params.add(paramView(p));
        }
        out.put("params", params);
        Map<String, Object> presets = new LinkedHashMap<>();
        d.presets().forEach((name, values) -> presets.put(name, jsonValues(values)));
        out.put("presets", presets);
        return out;
    }

    private static Map<String, Object> paramView(AbilityParam p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", p.key());
        out.put("valueType", p.valueType().name());
        out.put("required", p.required());
        out.put("min", p.min());
        out.put("minExclusive", p.minExclusive());
        out.put("max", p.max());
        out.put("maxExclusive", p.maxExclusive());
        out.put("defaultValue", jsonValue(p.defaultValue()));
        out.put("example", p.example());
        out.put("label", p.label());
        out.put("help", p.help());
        // Champ commun géré par le moteur (non déclaré par le type) : rangé sous « Réglages avancés ».
        out.put("common", MobAbilityCatalog.COMMON_PARAMS.contains(p));
        out.put("bounds", p.min() == null && p.max() == null ? "" : p.boundsMessage());
        return out;
    }

    // ---- Instance d'un profil ------------------------------------------------------------------

    /** Une capacité d'un profil : type, libellé, activée, valeurs par clé YAML (champs communs compris). */
    public static Map<String, Object> instanceView(MobAbility ability) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", ability.type().name());
        out.put("label", MobAbilityCatalog.descriptor(ability.type()).label());
        out.put("enabled", ability.options().enabled());
        out.put("values", jsonValues(MobAbilityCatalog.values(ability)));
        return out;
    }

    private static Map<String, Object> jsonValues(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, jsonValue(v)));
        return out;
    }

    private static Object jsonValue(Object value) {
        return value instanceof Enum<?> e ? e.name() : value;
    }

    // ---- Liste envoyée par le formulaire -------------------------------------------------------

    /**
     * Lit la liste structurée du formulaire : chaque élément est un objet {@code {"type": …, "<clé>": …}}
     * aux clés YAML du descripteur. Clé inconnue, type inconnu ou valeur hors bornes : erreur, rien n'est
     * retenu. Les règles de composition (plafond, doublons, statut) restent celles de
     * {@link MobAbilityRules#violations}, appliquées sur le profil complet par l'appelant.
     */
    public static Parsed parseEntries(List<?> entries) {
        List<String> errors = new ArrayList<>();
        List<MobAbility> abilities = new ArrayList<>();
        if (entries.size() > MAX_ENTRIES) {
            return new Parsed(List.of(), List.of(entries.size() + " capacités envoyées : "
                    + MobAbilityRules.MAX_ABILITIES_PER_PROFILE + " au plus par profil."));
        }
        for (int i = 0; i < entries.size(); i++) {
            String context = "Capacité n°" + (i + 1);
            if (!(entries.get(i) instanceof Map<?, ?> raw)) {
                errors.add(context + " : entrée illisible.");
                continue;
            }
            MobAbilityType type = MobAbilityCatalog.typeOf(raw.get("type"));
            if (type == null) {
                errors.add(context + " : capacité inconnue « " + raw.get("type") + " ».");
                continue;
            }
            context += " « " + MobAbilityCatalog.descriptor(type).label() + " »";
            MemoryConfiguration section = new MemoryConfiguration();
            section.set("type", type.name());
            boolean entryOk = true;
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                String key = String.valueOf(e.getKey());
                if ("type".equals(key) || e.getValue() == null) {
                    continue;
                }
                if (!MobAbilityCatalog.acceptedKeys(type).contains(key)) {
                    errors.add(context + " : réglage inconnu « " + key + " ».");
                    entryOk = false;
                    continue;
                }
                String text = String.valueOf(e.getValue()).strip();
                if (text.length() > MAX_VALUE_CHARS) {
                    errors.add(context + " : valeur de « " + key + " » trop longue.");
                    entryOk = false;
                    continue;
                }
                if (text.isEmpty()) {
                    continue; // vide = absent : défaut du paramètre, ou « obligatoire » signalé par le parseur
                }
                AbilityParam param = MobAbilityCatalog.descriptor(type).param(key).orElseThrow();
                Object value = coerce(param, text);
                if (param.valueType() == AbilityParam.ValueType.BOOL && !(value instanceof Boolean)) {
                    // Le parseur de fichier ramène une valeur non booléenne au défaut ; ici on le dit.
                    errors.add(context + " : « " + key + " » doit valoir true ou false.");
                    entryOk = false;
                    continue;
                }
                section.set(key, value);
            }
            if (!entryOk) {
                continue;
            }
            MobAbility ability = MobAbilityCatalog.parse(section, context, errors);
            if (ability != null) {
                abilities.add(ability);
            }
        }
        // Messages du parseur au format « contexte: message » : même ponctuation que le reste de l'écran.
        List<String> shown = errors.stream().map(m -> m.replaceFirst("^(Capacité n°[^:]*?[^ :]): ", "$1 : ")).toList();
        return shown.isEmpty() ? new Parsed(List.copyOf(abilities), List.of()) : new Parsed(List.of(), shown);
    }

    private static Object coerce(AbilityParam param, String text) {
        try {
            return switch (param.valueType()) {
                case DECIMAL -> Double.parseDouble(text.replace(',', '.'));
                case INT -> Integer.parseInt(text);
                case BOOL -> switch (text.toLowerCase(Locale.ROOT)) {
                    case "true", "on", "oui" -> Boolean.TRUE;
                    case "false", "off", "non" -> Boolean.FALSE;
                    default -> text;
                };
                case ENTITY_TYPE -> text.toUpperCase(Locale.ROOT);
            };
        } catch (NumberFormatException e) {
            return text; // refusée par le parseur avec son message habituel (« doit être un nombre »…)
        }
    }
}
