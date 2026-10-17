package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.BossMode;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issue #214 — le contrat d'un fichier {@code mobs/<id>.yml}, tel que {@link SpecialMobDefinitionParser}
 * le lit réellement : chaque clé, son rôle, ses bornes, et ce qui se passe si elle est absente.
 *
 * <p>Deux usages, une seule source : le <strong>modèle à remplir</strong> téléchargé depuis PlugAdmin
 * ({@link #template}) et la détection des <strong>propriétés inconnues</strong> à l'import
 * ({@link #topLevelKeys}, {@link #abilityKeys}, {@link #DROP_KEYS}). Un test confronte ces clés à
 * celles que le parseur lit (lecture de son source) : une clé ajoutée au parseur sans être décrite
 * ici fait échouer la construction, si bien que le modèle ne peut pas devenir plus pauvre que le
 * moteur. Le switch de {@link #abilityFields} est exhaustif : une nouvelle capacité ne compile pas
 * tant qu'elle n'y est pas décrite.</p>
 */
public final class SpecialMobSchema {

    /** Version du format de fichier de profil, citée dans l'en-tête des modèles. */
    public static final int FORMAT_VERSION = 1;

    /** Où une clé a un sens. */
    public enum Scope { ALL, BOSS, CREEPER }

    /**
     * Une clé du fichier.
     *
     * @param required obligatoire : écrite active dans le modèle (sinon commentée, à décommenter)
     * @param value    valeur écrite dans le modèle (active ou en exemple commenté)
     * @param doc      explication en français (une ou plusieurs lignes)
     */
    public record Field(String key, boolean required, Scope scope, String value, List<String> doc) {
    }

    /** Clés d'une entrée de {@code drops}. */
    public static final Set<String> DROP_KEYS = Set.of("custom-item", "material", "weight", "min-amount", "max-amount");

    private SpecialMobSchema() {
    }

    /** Les clés de premier niveau, dans l'ordre du fichier canonique. */
    public static List<Field> topLevelFields(MobCategory category, BossMode mode) {
        boolean boss = category == MobCategory.BOSS;
        List<Field> f = new ArrayList<>();
        f.add(new Field("id", true, Scope.ALL, "\"\"", List.of(
                "Identifiant stable, À REMPLIR : minuscules, chiffres, « _ », « . » ou « - » (ex. boss_zombie_entrainement).",
                "Il ne change plus jamais : quêtes et réglages le citent. « rpgquest: » est ajouté s'il manque.")));
        f.add(new Field("category", true, Scope.ALL, category.name(), List.of(
                "SPECIAL = variante tirée au hasard sur les apparitions naturelles du Wild ;",
                "BOSS = jamais tiré au hasard (boss de quête ou boss communautaire, voir boss-mode).")));
        f.add(new Field("enabled", true, Scope.ALL, "true", List.of(
                "false = profil conservé mais ignoré partout (tirage, scheduler, apparition de test).")));
        f.add(new Field("boss-mode", false, Scope.BOSS, mode.name(), List.of(
                "BOSS seulement. QUEST = n'apparaît que pour une quête ou un test ;",
                "COMMUNITY = candidat du scheduler des boss communautaires du Wild. Absent = QUEST.")));
        f.add(new Field("community-weight", false, Scope.BOSS, String.valueOf(SpecialMobDefinition.DEFAULT_COMMUNITY_WEIGHT), List.of(
                "BOSS COMMUNITY : poids relatif dans le tirage des boss communautaires, entier ≥ 1. Absent = "
                        + SpecialMobDefinition.DEFAULT_COMMUNITY_WEIGHT + ".")));
        f.add(new Field("participation-rewards", false, Scope.BOSS, "[]", List.of(
                "BOSS : récompenses remises à chaque participant d'un boss communautaire vaincu, "
                        + SpecialMobDefinition.MAX_PARTICIPATION_REWARDS + " au plus.",
                "Chaque entrée = une clé du catalogue des récompenses (entitlements.yml) ou « table:<id> » pour un tirage.",
                "N'utiliser QUE des clés qui existent sur le serveur (liste fournie avec le prompt). [] = aucune.")));
        f.add(new Field("entity-type", true, Scope.ALL, "ZOMBIE", List.of(
                "Créature vanilla de base, vivante (ZOMBIE, SKELETON, SPIDER, CREEPER, HUSK…).")));
        f.add(new Field("name", true, Scope.ALL, "\"\"", List.of(
                "Nom affiché au-dessus de la créature, À REMPLIR. MiniMessage accepté (ex. \"<red>Zombie d'entraînement</red>\").")));
        f.add(new Field("spawn-chance", true, Scope.ALL, boss ? "0.0" : "0.05", List.of(
                "Nombre de 0 à 1. SPECIAL : chance par apparition naturelle éligible (0.05 = 5 %).",
                "BOSS : non utilisé par le tirage du Wild, laisser 0.0.")));
        f.add(new Field("worlds", true, Scope.ALL, "[]", List.of(
                "Mondes où le profil peut exister, ex. [wild]. [] = tous les mondes. Noms exacts des mondes du serveur.")));
        f.add(new Field("biomes", true, Scope.ALL, "[]", List.of(
                "Biomes autorisés (clé Minecraft en minuscules : plains, desert, dark_forest…). [] = sans restriction.")));
        f.add(new Field("zones", true, Scope.ALL, "[]", List.of(
                "Identifiants de zones RPGQuest autorisées. [] = sans restriction (recommandé).")));
        f.add(new Field("health", false, Scope.ALL, "20.0", List.of(
                "Points de vie maximum, nombre > 0 (20.0 = 10 cœurs). Absent = valeur vanilla de la créature.")));
        f.add(new Field("damage", false, Scope.ALL, "2.0", List.of(
                "Dégâts d'attaque, nombre ≥ 0 (2.0 = 1 cœur). Absent = valeur vanilla.")));
        f.add(new Field("speed", false, Scope.ALL, "0.23", List.of(
                "Vitesse de déplacement, nombre > 0 (zombie vanilla ≈ 0.23 ; au-delà de 0.4, très rapide). Absent = vanilla.")));
        f.add(new Field("armor", false, Scope.ALL, "0.0", List.of(
                "Points d'armure, nombre ≥ 0 (vanilla : 0 à 30). Absent = vanilla.")));
        f.add(new Field("knockback-resistance", false, Scope.ALL, "0.0", List.of(
                "Résistance au recul, de 0 (aucune) à 1 (insensible). Absent = vanilla.")));
        f.add(new Field("scale", false, Scope.ALL, "1.0", List.of(
                "Taille relative, nombre > 0 (1.0 = normale, 1.5 = plus grand). Absent = vanilla.")));
        f.add(new Field("creeper-explosion-radius", false, Scope.CREEPER, "3", List.of(
                "CREEPER SEULEMENT (refusé pour toute autre créature) : rayon d'explosion, nombre > 0 (vanilla 3).")));
        f.add(new Field("particle", false, Scope.ALL, "FLAME", List.of(
                "Particule d'ambiance autour de la créature (nom Minecraft : FLAME, SMOKE, SOUL…). Absent = aucune.")));
        f.add(new Field("sound", false, Scope.ALL, "ENTITY_ZOMBIE_AMBIENT", List.of(
                "Son joué à l'apparition (nom Minecraft : ENTITY_ZOMBIE_AMBIENT…). Absent = aucun.")));
        f.add(new Field("abilities", false, Scope.ALL, "", List.of(
                "Capacités spéciales, liste (voir les modèles commentés plus bas). Absent = aucune.")));
        f.add(new Field("drops", false, Scope.ALL, "", List.of(
                "Butin à la mort, tirage pondéré (voir le modèle commenté plus bas). Absent = butin vanilla.")));
        f.add(new Field("xp-reward", false, Scope.ALL, "10", List.of(
                "Expérience Minecraft lâchée à la mort, entier ≥ 0. Absent = vanilla.")));
        f.add(new Field("max-population", false, Scope.ALL, "1", List.of(
                "Nombre maximum d'exemplaires vivants en même temps (enfants et renforts compris), entier ≥ 0.",
                "Absent = pas de plafond propre au profil.")));
        return f;
    }

    /** Toutes les clés de premier niveau acceptées, toutes catégories confondues. */
    public static Set<String> topLevelKeys() {
        Set<String> keys = new LinkedHashSet<>();
        topLevelFields(MobCategory.BOSS, BossMode.COMMUNITY).forEach(f -> keys.add(f.key()));
        return keys;
    }

    /** Les paramètres d'une capacité, sans la clé {@code type}. */
    public static List<Field> abilityFields(MobAbilityType type) {
        return switch (type) {
            case STRONGER_EXPLOSION -> List.of(
                    new Field("radius-multiplier", true, Scope.ALL, "1.5", List.of(
                            "Multiplie le rayon de SES explosions, > 0. N'a d'effet que si la créature explose (CREEPER).")));
            case EXPLOSIVE_ON_ATTACK -> List.of(
                    new Field("power", true, Scope.ALL, "2.0", List.of("Puissance de l'explosion, > 0 (TNT = 4.0).")),
                    new Field("set-fire", false, Scope.ALL, "false", List.of("true = l'explosion met le feu. Absent = false.")),
                    new Field("trigger-range-blocks", true, Scope.ALL, "2.0", List.of(
                            "Distance (blocs, > 0) à laquelle elle explose près de sa cible.")));
            case SPLIT_ON_HIT -> List.of(
                    new Field("max-depth", true, Scope.ALL, "2", List.of("Générations de divisions au plus, entier ≥ 1.")),
                    new Field("max-children-per-hit", true, Scope.ALL, "2", List.of("Copies créées par coup reçu, entier ≥ 1.")),
                    new Field("max-alive-per-parent", false, Scope.ALL, "2", List.of(
                            "Copies directes vivantes en même temps pour un même parent, entier ≥ 1. Absent = 2.")));
            case ENRAGED -> List.of(
                    new Field("health-fraction", true, Scope.ALL, "0.3", List.of(
                            "Seuil de vie qui déclenche la rage, strictement entre 0 et 1 (0.3 = sous 30 %).")),
                    new Field("speed-multiplier", true, Scope.ALL, "1.3", List.of("Multiplicateur de vitesse en rage, > 0.")),
                    new Field("damage-multiplier", true, Scope.ALL, "1.5", List.of("Multiplicateur de dégâts en rage, > 0.")));
            case SUMMON_ON_DAMAGE -> List.of(
                    new Field("summon-entity-type", true, Scope.ALL, "ZOMBIE", List.of("Créature invoquée en renfort (vivante).")),
                    new Field("amount", true, Scope.ALL, "2", List.of("Renforts par invocation, entier ≥ 1.")),
                    new Field("chance", true, Scope.ALL, "0.25", List.of("Chance d'invoquer à chaque coup reçu, de 0 (exclu) à 1.")),
                    new Field("cooldown-seconds", true, Scope.ALL, "20", List.of("Délai minimum entre deux invocations, entier ≥ 0.")),
                    new Field("max-alive", true, Scope.ALL, "4", List.of(
                            "Renforts vivants au plus pour ce mob, entier ≥ 1 (plafond obligatoire, jamais d'invocation sans fin).")));
        };
    }

    /** Explication d'une capacité, une ligne. */
    public static String abilityDoc(MobAbilityType type) {
        return switch (type) {
            case STRONGER_EXPLOSION -> "Explosion plus grande (creeper).";
            case EXPLOSIVE_ON_ATTACK -> "Explose au contact de sa cible (s'auto-détruit).";
            case SPLIT_ON_HIT -> "Se divise en copies d'elle-même quand elle est touchée.";
            case ENRAGED -> "Devient plus rapide et plus forte sous un seuil de vie (une seule fois).";
            case SUMMON_ON_DAMAGE -> "Invoque des renforts quand elle subit des dégâts.";
        };
    }

    /** Clés acceptées par capacité, {@code type} compris. */
    public static Map<MobAbilityType, Set<String>> abilityKeys() {
        Map<MobAbilityType, Set<String>> out = new LinkedHashMap<>();
        for (MobAbilityType type : MobAbilityType.values()) {
            Set<String> keys = new LinkedHashSet<>();
            keys.add("type");
            abilityFields(type).forEach(f -> keys.add(f.key()));
            out.put(type, Set.copyOf(keys));
        }
        return out;
    }

    /**
     * Le modèle à remplir, commenté en français. Tel quel, il est refusé (id et nom vides) : une
     * fois ces deux champs remplis, il s'importe tel quel ; chaque champ facultatif est en exemple
     * commenté, à décommenter pour l'utiliser.
     */
    public static String template(MobCategory category, BossMode mode) {
        StringBuilder sb = new StringBuilder();
        String title = category == MobCategory.SPECIAL ? "mob SPÉCIAL (tirage aléatoire du Wild)"
                : mode == BossMode.COMMUNITY ? "BOSS communautaire (scheduler du Wild)" : "BOSS de quête";
        sb.append("# =====================================================================\n");
        sb.append("# LodyQuests — modèle de profil : ").append(title).append('\n');
        sb.append("# Format : lodyquests-mob-profile, version ").append(FORMAT_VERSION)
                .append(" (un profil par fichier, YAML).\n");
        sb.append("# Toutes les clés ci-dessous sont celles que le moteur lit réellement ; aucune autre n'est acceptée.\n");
        sb.append("# - Lignes actives : à garder (remplir id et name).\n");
        sb.append("# - Lignes « # clé: valeur » : facultatives ; retirer le « # » pour les utiliser.\n");
        sb.append("# - Une clé absente garde la valeur vanilla de la créature.\n");
        sb.append("# Import : PlugAdmin → Mobs spéciaux & boss → Importer un fichier. Rien n'apparaît en jeu à l'import.\n");
        sb.append("# =====================================================================\n");
        for (Field field : topLevelFields(category, mode)) {
            if (field.scope() == Scope.BOSS && category != MobCategory.BOSS) {
                continue;
            }
            if (field.key().equals("community-weight") && mode != BossMode.COMMUNITY) {
                continue;
            }
            if (field.key().equals("participation-rewards") && mode != BossMode.COMMUNITY) {
                continue;
            }
            sb.append('\n');
            field.doc().forEach(line -> sb.append("# ").append(line).append('\n'));
            switch (field.key()) {
                case "abilities" -> appendAbilities(sb);
                case "drops" -> appendDrops(sb);
                default -> {
                    boolean active = field.required() || (category == MobCategory.BOSS
                            && (field.key().equals("boss-mode") || field.key().equals("community-weight")
                            || field.key().equals("participation-rewards")));
                    sb.append(active ? "" : "# ").append(field.key()).append(": ").append(field.value()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static void appendAbilities(StringBuilder sb) {
        sb.append("# Pour en utiliser : décommenter « abilities: » puis une ou plusieurs entrées complètes.\n");
        sb.append("# abilities:\n");
        for (MobAbilityType type : MobAbilityType.values()) {
            sb.append("#   # ").append(abilityDoc(type)).append('\n');
            sb.append("#   - type: ").append(type.name()).append('\n');
            for (Field f : abilityFields(type)) {
                sb.append("#     ").append(f.key()).append(": ").append(f.value())
                        .append("   # ").append(String.join(" ", f.doc())).append('\n');
            }
        }
    }

    private static void appendDrops(StringBuilder sb) {
        sb.append("# Chaque entrée : exactement UN de « material » (objet vanilla) ou « custom-item » (objet RPGQuest existant).\n");
        sb.append("# drops:\n");
        sb.append("#   - material: ROTTEN_FLESH   # nom Minecraft de l'objet\n");
        sb.append("#     weight: 3                # poids du tirage, entier ≥ 1\n");
        sb.append("#     min-amount: 1            # entier ≥ 1\n");
        sb.append("#     max-amount: 2            # entier ≥ min-amount\n");
        sb.append("#   - custom-item: rpgquest:mon_objet   # identifiant d'un objet custom du serveur\n");
        sb.append("#     weight: 1\n");
        sb.append("#     min-amount: 1\n");
        sb.append("#     max-amount: 1\n");
    }

    /** Les trois modèles, par clé stable ({@code special}, {@code boss-quest}, {@code boss-community}). */
    public static Map<String, String> templates() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("special", template(MobCategory.SPECIAL, BossMode.QUEST));
        out.put("boss-quest", template(MobCategory.BOSS, BossMode.QUEST));
        out.put("boss-community", template(MobCategory.BOSS, BossMode.COMMUNITY));
        return out;
    }
}
