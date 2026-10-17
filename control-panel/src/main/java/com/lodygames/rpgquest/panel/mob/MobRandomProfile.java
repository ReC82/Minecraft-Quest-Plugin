package com.lodygames.rpgquest.panel.mob;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Issue #214 — « Générer aléatoirement » : un profil complet, borné et cohérent, écrit dans le
 * format du modèle. Ce n'est qu'un <strong>brouillon</strong> : il s'ouvre dans l'import, et
 * n'existe qu'après « Vérifier » (parseur réel du plugin) puis « Créer ». Aucune IA, aucun appel
 * réseau, aucune apparition.
 *
 * <p>Combinaisons : les capacités explosives ne sont tirées que pour un creeper ; une invocation
 * porte toujours un plafond de renforts vivants ; un boss a {@code max-population: 1}. Les bornes
 * restent dans ce qu'un joueur affronte sans surprise (vitesse ≤ 0.32).</p>
 */
public final class MobRandomProfile {

    /** Créature de base, nom français, renfort cohérent. */
    private record Base(String type, String name, String summon) {
    }

    private static final List<Base> BASES = List.of(
            new Base("ZOMBIE", "Zombie", "ZOMBIE"),
            new Base("HUSK", "Momie", "HUSK"),
            new Base("DROWNED", "Noyé", "DROWNED"),
            new Base("SKELETON", "Squelette", "SKELETON"),
            new Base("STRAY", "Vagabond", "STRAY"),
            new Base("SPIDER", "Araignée", "CAVE_SPIDER"),
            new Base("CREEPER", "Creeper", ""),
            new Base("VINDICATOR", "Vindicateur", "PILLAGER"),
            new Base("WITHER_SKELETON", "Squelette wither", "SKELETON"));

    private static final List<String> SPECIAL_EPITHETS = List.of("des marais", "rôdeur", "des cendres",
            "enragé", "des brumes", "pilleur", "des ronces", "sournois");
    private static final List<String> BOSS_EPITHETS = List.of("le Colosse", "le Tyran", "l'Ancien",
            "le Dévoreur", "le Gardien", "le Fléau", "la Terreur", "le Seigneur");
    private static final List<String> COLORS = List.of("red", "gold", "dark_purple", "dark_green", "aqua", "yellow");
    private static final List<String> PARTICLES = List.of("FLAME", "SMOKE", "SOUL", "ENCHANT", "PORTAL");

    private MobRandomProfile() {
    }

    public static String generate(String kind, MobImportContext ctx, Random rnd) {
        boolean boss = !"special".equals(kind);
        List<Base> bases = BASES.stream()
                .filter(b -> ctx.entityTypes().isEmpty() || ctx.entityTypes().contains(b.type())).toList();
        Base base = pick(bases.isEmpty() ? BASES : bases, rnd);
        String id = uniqueId(boss ? "boss" : "mob", base, ctx, rnd);
        String epithet = pick(boss ? BOSS_EPITHETS : SPECIAL_EPITHETS, rnd);
        String color = pick(COLORS, rnd);
        String name = "<" + color + ">" + base.name() + " " + epithet + "</" + color + ">";

        StringBuilder sb = new StringBuilder();
        sb.append("# Profil généré aléatoirement par PlugAdmin (issue #214) — brouillon, rien n'est encore enregistré.\n");
        sb.append("# À relire et modifier librement, puis « Vérifier » : le serveur valide avant toute création.\n");
        sb.append("id: ").append(id).append('\n');
        sb.append("category: ").append(boss ? "BOSS" : "SPECIAL").append('\n');
        sb.append("enabled: true\n");
        if (boss) {
            boolean community = "boss-community".equals(kind);
            sb.append("boss-mode: ").append(community ? "COMMUNITY" : "QUEST").append('\n');
            sb.append("community-weight: 1\n");
            if (community) {
                sb.append("participation-rewards: []\n");
            }
        }
        sb.append("entity-type: ").append(base.type()).append('\n');
        sb.append("name: \"").append(name.replace("\"", "'")).append("\"\n");
        sb.append("spawn-chance: ").append(boss ? "0.0" : num(range(rnd, 0.02, 0.08))).append('\n');
        String wild = ctx.wildWorld().isBlank() ? "" : ctx.wildWorld();
        sb.append("worlds: ").append(wild.isEmpty() ? "[]" : "[" + wild + "]").append('\n');
        sb.append("biomes: []\n");
        sb.append("zones: []\n");
        sb.append("health: ").append(num(boss ? range(rnd, 60, 200) : range(rnd, 20, 40), 0)).append('\n');
        sb.append("damage: ").append(num(boss ? range(rnd, 4, 9) : range(rnd, 2, 5), 1)).append('\n');
        sb.append("speed: ").append(num(range(rnd, 0.20, boss ? 0.30 : 0.32))).append('\n');
        sb.append("armor: ").append(num(boss ? range(rnd, 0, 8) : range(rnd, 0, 4), 0)).append('\n');
        sb.append("knockback-resistance: ").append(num(boss ? range(rnd, 0.3, 0.8) : range(rnd, 0, 0.3), 1)).append('\n');
        sb.append("scale: ").append(num(boss ? range(rnd, 1.1, 1.5) : range(rnd, 0.9, 1.2), 1)).append('\n');
        if (boss || rnd.nextBoolean()) {
            sb.append("particle: ").append(pick(PARTICLES, rnd)).append('\n');
        }
        appendAbilities(sb, base, boss, rnd);
        sb.append("xp-reward: ").append(boss ? 30 + rnd.nextInt(71) : 5 + rnd.nextInt(11)).append('\n');
        sb.append("max-population: ").append(boss ? 1 : 2 + rnd.nextInt(3)).append('\n');
        return sb.toString();
    }

    private static void appendAbilities(StringBuilder sb, Base base, boolean boss, Random rnd) {
        List<String> blocks = new ArrayList<>();
        boolean creeper = base.type().equals("CREEPER");
        if (boss || rnd.nextInt(3) == 0) {
            blocks.add("  - type: ENRAGED\n    health-fraction: " + num(range(rnd, 0.25, 0.4), 2)
                    + "\n    speed-multiplier: " + num(range(rnd, 1.1, 1.3), 2)
                    + "\n    damage-multiplier: " + num(range(rnd, 1.2, 1.6), 2) + "\n");
        }
        if (boss && !base.summon().isEmpty()) {
            blocks.add("  - type: SUMMON_ON_DAMAGE\n    summon-entity-type: " + base.summon()
                    + "\n    amount: " + (1 + rnd.nextInt(2)) + "\n    chance: " + num(range(rnd, 0.15, 0.3), 2)
                    + "\n    cooldown-seconds: " + (15 + rnd.nextInt(16)) + "\n    max-alive: " + (2 + rnd.nextInt(3)) + "\n");
        }
        if (creeper) {
            blocks.add("  - type: STRONGER_EXPLOSION\n    radius-multiplier: " + num(range(rnd, 1.2, 1.6), 1) + "\n");
        } else if (!boss && rnd.nextInt(3) == 0) {
            blocks.add("  - type: SPLIT_ON_HIT\n    max-depth: 1\n    max-children-per-hit: " + (1 + rnd.nextInt(2))
                    + "\n    max-alive-per-parent: 2\n");
        }
        if (!blocks.isEmpty()) {
            sb.append("abilities:\n");
            blocks.forEach(sb::append);
        }
    }

    private static String uniqueId(String prefix, Base base, MobImportContext ctx, Random rnd) {
        List<String> taken = ctx.takenIds();
        String stem = prefix + "_" + base.type().toLowerCase(Locale.ROOT);
        for (int i = 0; i < 50; i++) {
            byte[] b = new byte[2];
            rnd.nextBytes(b);
            String id = stem + "_" + HexFormat.of().formatHex(b);
            if (!taken.contains(id)) {
                return id;
            }
        }
        return stem + "_" + Long.toHexString(System.nanoTime());
    }

    private static <T> T pick(List<T> values, Random rnd) {
        return values.get(rnd.nextInt(values.size()));
    }

    private static double range(Random rnd, double min, double max) {
        return min + rnd.nextDouble() * (max - min);
    }

    private static String num(double value) {
        return num(value, 2);
    }

    private static String num(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
