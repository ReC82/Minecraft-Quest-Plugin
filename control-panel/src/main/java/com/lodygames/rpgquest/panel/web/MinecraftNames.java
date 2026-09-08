package com.lodygames.rpgquest.panel.web;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Noms lisibles (français) pour les matériaux et entités Minecraft cités dans les catalogues du
 * Control Panel (issue #76).
 *
 * <p>Le Control Panel n'a <strong>aucune dépendance Paper/Bukkit</strong> (choix d'isolation du
 * module) : impossible d'appeler {@code Material.translationKey()} ici. Les objectifs arrivent en
 * chaînes déjà formatées par l'agent ({@code "Tuer SPIDER (x5)"}, {@code "Collecter AMETHYST_SHARD
 * (x2)"}). On se contente donc d'une <strong>table de correspondance minimale et extensible</strong>
 * couvrant ce qui apparaît réellement dans le contenu RPGQuest, avec repli propre sur
 * {@link MiniText#prettifyId(String)} (« Amethyst Shard ») pour tout jeton inconnu.</p>
 *
 * <p>Une internationalisation robuste (source de vérité côté plugin, resource pack, toutes les
 * clés vanilla) est un chantier séparé — voir le ticket d'évolution.</p>
 */
public final class MinecraftNames {

    private MinecraftNames() {
    }

    private static final Pattern SCREAMING = Pattern.compile("\\b[A-Z][A-Z0-9]{2,}(?:_[A-Z0-9]+)*\\b");

    /** Table FR minimale — jetons réellement présents dans le contenu + basiques fréquents. */
    private static final Map<String, String> FR = Map.ofEntries(
            // --- entités / mobs ---
            Map.entry("SPIDER", "Araignée"),
            Map.entry("CAVE_SPIDER", "Araignée venimeuse"),
            Map.entry("ZOMBIE", "Zombie"),
            Map.entry("HUSK", "Zombie momifié"),
            Map.entry("DROWNED", "Noyé"),
            Map.entry("SKELETON", "Squelette"),
            Map.entry("STRAY", "Squelette égaré"),
            Map.entry("WITHER_SKELETON", "Squelette wither"),
            Map.entry("CREEPER", "Creeper"),
            Map.entry("ENDERMAN", "Enderman"),
            Map.entry("WITCH", "Sorcière"),
            Map.entry("SLIME", "Slime"),
            Map.entry("BLAZE", "Blaze"),
            Map.entry("SILVERFISH", "Poisson d'argent"),
            Map.entry("PHANTOM", "Fantôme"),
            Map.entry("PILLAGER", "Pillard"),
            Map.entry("COW", "Vache"),
            Map.entry("PIG", "Cochon"),
            Map.entry("SHEEP", "Mouton"),
            Map.entry("CHICKEN", "Poule"),
            Map.entry("RABBIT", "Lapin"),
            Map.entry("VILLAGER", "Villageois"),
            // --- blocs / minerais ---
            Map.entry("DIRT", "Terre"),
            Map.entry("GRASS_BLOCK", "Bloc d'herbe"),
            Map.entry("STONE", "Pierre"),
            Map.entry("COBBLESTONE", "Pierre taillée"),
            Map.entry("DEEPSLATE", "Ardoise des abîmes"),
            Map.entry("SAND", "Sable"),
            Map.entry("GRAVEL", "Gravier"),
            Map.entry("OAK_LOG", "Bûche de chêne"),
            Map.entry("BIRCH_LOG", "Bûche de bouleau"),
            Map.entry("SPRUCE_LOG", "Bûche de sapin"),
            Map.entry("OAK_PLANKS", "Planches de chêne"),
            Map.entry("COAL_ORE", "Minerai de charbon"),
            Map.entry("IRON_ORE", "Minerai de fer"),
            Map.entry("DIAMOND_ORE", "Minerai de diamant"),
            Map.entry("AMETHYST_CLUSTER", "Amas d'améthyste"),
            Map.entry("AMETHYST_BLOCK", "Bloc d'améthyste"),
            // --- objets / ressources ---
            Map.entry("AMETHYST_SHARD", "Éclat d'améthyste"),
            Map.entry("DIAMOND", "Diamant"),
            Map.entry("EMERALD", "Émeraude"),
            Map.entry("IRON_INGOT", "Lingot de fer"),
            Map.entry("GOLD_INGOT", "Lingot d'or"),
            Map.entry("COAL", "Charbon"),
            Map.entry("STICK", "Bâton"),
            Map.entry("WHEAT", "Blé"),
            Map.entry("BREAD", "Pain"),
            Map.entry("APPLE", "Pomme"),
            Map.entry("GOLDEN_APPLE", "Pomme dorée"),
            Map.entry("ENCHANTED_GOLDEN_APPLE", "Pomme dorée enchantée"),
            Map.entry("STRING", "Ficelle"),
            Map.entry("LEATHER", "Cuir"),
            Map.entry("BONE", "Os"),
            Map.entry("GUNPOWDER", "Poudre à canon"),
            Map.entry("ENDER_PEARL", "Perle de l'Ender"),
            Map.entry("TORCH", "Torche"),
            // --- outils / armes / armures ---
            Map.entry("WOODEN_SWORD", "Épée en bois"),
            Map.entry("STONE_SWORD", "Épée en pierre"),
            Map.entry("IRON_SWORD", "Épée en fer"),
            Map.entry("DIAMOND_SWORD", "Épée en diamant"),
            Map.entry("NETHERITE_SWORD", "Épée en netherite"),
            Map.entry("WOODEN_PICKAXE", "Pioche en bois"),
            Map.entry("STONE_PICKAXE", "Pioche en pierre"),
            Map.entry("IRON_PICKAXE", "Pioche en fer"),
            Map.entry("DIAMOND_PICKAXE", "Pioche en diamant"),
            Map.entry("IRON_AXE", "Hache en fer"),
            Map.entry("IRON_SHOVEL", "Pelle en fer"),
            Map.entry("SHIELD", "Bouclier"),
            Map.entry("BOW", "Arc"),
            Map.entry("ARROW", "Flèche"),
            Map.entry("IRON_HELMET", "Casque en fer"),
            Map.entry("IRON_CHESTPLATE", "Plastron en fer"),
            Map.entry("IRON_LEGGINGS", "Jambières en fer"),
            Map.entry("IRON_BOOTS", "Bottes en fer"));

    /** Nom FR d'un jeton d'énumération Minecraft, ou repli typographique si inconnu. */
    public static String humanize(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String key = token.contains(":") ? token.substring(token.lastIndexOf(':') + 1) : token;
        String up = key.toUpperCase(java.util.Locale.ROOT);
        String fr = FR.get(up);
        return fr != null ? fr : MiniText.prettifyId(token);
    }

    /** Y a-t-il une traduction FR dédiée (vs. simple prettify) ? Utile pour les tests / le rendu. */
    public static boolean isKnown(String token) {
        if (token == null) {
            return false;
        }
        String key = token.contains(":") ? token.substring(token.lastIndexOf(':') + 1) : token;
        return FR.containsKey(key.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Remplace, au fil d'un texte, chaque jeton en capitales par son nom FR (ou son prettify).
     * {@code "Collecter AMETHYST_SHARD (x2)"} → {@code "Collecter Éclat d'améthyste (x2)"}.
     * Le repli ne casse jamais l'affichage.
     */
    public static String humanizeTokens(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = SCREAMING.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String replacement = MiniText.keepUpper(token) ? token : humanize(token);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
