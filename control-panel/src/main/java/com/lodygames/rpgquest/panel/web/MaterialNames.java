package com.lodygames.rpgquest.panel.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Libellés français des matériaux Minecraft, pour les listes d'icônes et de récompenses
 * (issue #196).
 *
 * <p><strong>Le problème.</strong> La version installée expose plus d'un millier d'objets. Les traduire
 * à la main est hors de question, et le Control Panel n'a <strong>aucune dépendance
 * Paper/Bukkit</strong> — il ne peut donc pas lire les fichiers de langue du jeu.</p>
 *
 * <p><strong>La solution retenue, et pourquoi elle marche.</strong> Les identifiants Minecraft
 * sont très régulièrement <em>composés</em> : {@code DIAMOND_SWORD}, {@code NETHERITE_HELMET},
 * {@code BIRCH_PLANKS}, {@code COPPER_ORE}. Un petit jeu de règles « matière + nature » couvre
 * donc des familles entières sans table exhaustive : {@code SWORD} donne « Épée en … » et une
 * dizaine de matières donnent les sept épées de la version réelle. Ce qui ne correspond à aucune
 * règle tombe sur une table nominative courte, puis sur l'anglais embelli
 * ({@code AMETHYST_SHARD} → « Amethyst Shard »).</p>
 *
 * <p><strong>Ce qui est volontairement évité :</strong> inventer une traduction approximative. Un
 * repli visiblement anglais est préférable à un faux ami. Et comme la recherche du panel filtre
 * sur l'identifiant <strong>et</strong> sur le libellé, taper « sword » ou « épée » fonctionne de
 * la même façon — un repli anglais ne rend donc jamais un objet introuvable.</p>
 */
public final class MaterialNames {

    private MaterialNames() {
    }

    /**
     * Natures d'objet reconnues, de la plus spécifique à la moins spécifique.
     *
     * <p>L'ordre compte : {@code PICKAXE} doit être essayé avant {@code AXE}, sans quoi
     * « DIAMOND_PICKAXE » deviendrait une hache. Même raison pour {@code HORSE_ARMOR} avant
     * {@code ARMOR}, et {@code WALL_SIGN} avant {@code SIGN}.</p>
     */
    private static final List<String[]> KINDS = List.of(
            // outils et armes
            new String[] {"PICKAXE", "Pioche en %s"},
            new String[] {"SWORD", "Épée en %s"},
            new String[] {"SHOVEL", "Pelle en %s"},
            new String[] {"AXE", "Hache en %s"},
            new String[] {"HOE", "Houe en %s"},
            // armures
            new String[] {"HORSE_ARMOR", "Armure de cheval en %s"},
            new String[] {"CHESTPLATE", "Plastron en %s"},
            new String[] {"LEGGINGS", "Jambières en %s"},
            new String[] {"HELMET", "Casque en %s"},
            new String[] {"BOOTS", "Bottes en %s"},
            // métallurgie et ressources
            new String[] {"INGOT", "Lingot de %s"},
            new String[] {"NUGGET", "Pépite de %s"},
            new String[] {"ORE", "Minerai de %s"},
            new String[] {"SHARD", "Éclat de %s"},
            new String[] {"DUST", "Poudre de %s"},
            // bois et construction
            new String[] {"PRESSURE_PLATE", "Plaque de pression en %s"},
            new String[] {"TRAPDOOR", "Trappe en %s"},
            new String[] {"FENCE_GATE", "Portillon en %s"},
            new String[] {"WALL_SIGN", "Panneau mural en %s"},
            new String[] {"HANGING_SIGN", "Panneau suspendu en %s"},
            new String[] {"PLANKS", "Planches de %s"},
            new String[] {"STAIRS", "Escalier en %s"},
            new String[] {"SLAB", "Dalle de %s"},
            new String[] {"FENCE", "Barrière en %s"},
            new String[] {"BUTTON", "Bouton en %s"},
            new String[] {"DOOR", "Porte en %s"},
            new String[] {"SIGN", "Panneau en %s"},
            new String[] {"BOAT", "Bateau en %s"},
            new String[] {"SAPLING", "Pousse de %s"},
            new String[] {"LEAVES", "Feuilles de %s"},
            new String[] {"LOG", "Bûche de %s"},
            new String[] {"WOOD", "Bois de %s"},
            new String[] {"WALL", "Mur de %s"},
            new String[] {"BRICKS", "Briques de %s"},
            new String[] {"BLOCK", "Bloc de %s"});

    /**
     * Matières reconnues en préfixe. Les valeurs sont écrites pour s'insérer après « en » ou
     * « de » selon la nature, d'où des formes comme « bois » plutôt que « en bois ».
     */
    private static final Map<String, String> MATTERS = matters();

    private static Map<String, String> matters() {
        Map<String, String> m = new LinkedHashMap<>();
        // métaux et matières d'équipement
        m.put("WOODEN", "bois");
        m.put("STONE", "pierre");
        m.put("COPPER", "cuivre");
        m.put("GOLDEN", "or");
        m.put("GOLD", "or");
        m.put("IRON", "fer");
        m.put("DIAMOND", "diamant");
        m.put("NETHERITE", "netherite");
        m.put("CHAINMAIL", "mailles");
        m.put("LEATHER", "cuir");
        m.put("TURTLE", "écaille de tortue");
        // essences de bois
        m.put("OAK", "chêne");
        m.put("DARK_OAK", "chêne noir");
        m.put("PALE_OAK", "chêne pâle");
        m.put("SPRUCE", "sapin");
        m.put("BIRCH", "bouleau");
        m.put("JUNGLE", "acajou");
        m.put("ACACIA", "acacia");
        m.put("MANGROVE", "palétuvier");
        m.put("CHERRY", "cerisier");
        m.put("BAMBOO", "bambou");
        m.put("CRIMSON", "écarlate");
        m.put("WARPED", "biscornu");
        // minerais et ressources
        m.put("COAL", "charbon");
        m.put("LAPIS", "lapis-lazuli");
        m.put("LAPIS_LAZULI", "lapis-lazuli");
        m.put("REDSTONE", "redstone");
        m.put("EMERALD", "émeraude");
        m.put("QUARTZ", "quartz");
        m.put("AMETHYST", "améthyste");
        m.put("RESIN", "résine");
        // pierres de construction
        m.put("DEEPSLATE", "ardoise des abîmes");
        m.put("NETHER", "Nether");
        m.put("END_STONE", "pierre de l'End");
        m.put("PRISMARINE", "prismarin");
        m.put("PURPUR", "purpur");
        m.put("SANDSTONE", "grès");
        m.put("MUD", "boue");
        m.put("TUFF", "tuf");
        return Map.copyOf(m);
    }

    /** Objets fréquents dont l'identifiant ne se décompose pas, ou mal. */
    private static final Map<String, String> EXACT = exact();

    private static Map<String, String> exact() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("STICK", "Bâton");
        m.put("STRING", "Ficelle");
        m.put("BONE", "Os");
        m.put("BONE_MEAL", "Poudre d'os");
        m.put("FEATHER", "Plume");
        m.put("FLINT", "Silex");
        m.put("CHARCOAL", "Charbon de bois");
        m.put("RAW_IRON", "Fer brut");
        m.put("RAW_GOLD", "Or brut");
        m.put("RAW_COPPER", "Cuivre brut");
        m.put("GUNPOWDER", "Poudre à canon");
        m.put("ROTTEN_FLESH", "Chair putréfiée");
        m.put("SPIDER_EYE", "Œil d'araignée");
        m.put("ENDER_PEARL", "Perle de l'Ender");
        m.put("ENDER_EYE", "Œil de l'Ender");
        m.put("BLAZE_ROD", "Bâton de Blaze");
        m.put("BLAZE_POWDER", "Poudre de Blaze");
        m.put("GHAST_TEAR", "Larme de Ghast");
        m.put("SLIME_BALL", "Boule de Slime");
        m.put("MAGMA_CREAM", "Crème de Magma");
        m.put("NETHER_STAR", "Étoile du Nether");
        m.put("DRAGON_BREATH", "Souffle du Dragon");
        m.put("PHANTOM_MEMBRANE", "Membrane de Fantôme");
        m.put("RABBIT_HIDE", "Peau de lapin");
        m.put("RABBIT_FOOT", "Patte de lapin");
        m.put("NAUTILUS_SHELL", "Coquille de nautile");
        m.put("HEART_OF_THE_SEA", "Cœur de la mer");
        m.put("TOTEM_OF_UNDYING", "Totem d'immortalité");
        m.put("ELYTRA", "Élytres");
        m.put("TRIDENT", "Trident");
        m.put("SHIELD", "Bouclier");
        m.put("BOW", "Arc");
        m.put("CROSSBOW", "Arbalète");
        m.put("ARROW", "Flèche");
        m.put("SPECTRAL_ARROW", "Flèche spectrale");
        m.put("FISHING_ROD", "Canne à pêche");
        m.put("SHEARS", "Cisailles");
        m.put("FLINT_AND_STEEL", "Briquet");
        m.put("COMPASS", "Boussole");
        m.put("CLOCK", "Horloge");
        m.put("SPYGLASS", "Longue-vue");
        m.put("MAP", "Carte vierge");
        m.put("FILLED_MAP", "Carte");
        m.put("PAPER", "Papier");
        m.put("BOOK", "Livre");
        m.put("WRITABLE_BOOK", "Livre et plume");
        m.put("WRITTEN_BOOK", "Livre écrit");
        m.put("ENCHANTED_BOOK", "Livre enchanté");
        m.put("NAME_TAG", "Étiquette");
        m.put("LEAD", "Laisse");
        m.put("SADDLE", "Selle");
        m.put("BUCKET", "Seau");
        m.put("WATER_BUCKET", "Seau d'eau");
        m.put("LAVA_BUCKET", "Seau de lave");
        m.put("MILK_BUCKET", "Seau de lait");
        m.put("BREAD", "Pain");
        m.put("APPLE", "Pomme");
        m.put("GOLDEN_APPLE", "Pomme dorée");
        m.put("ENCHANTED_GOLDEN_APPLE", "Pomme dorée enchantée");
        m.put("CARROT", "Carotte");
        m.put("GOLDEN_CARROT", "Carotte dorée");
        m.put("POTATO", "Pomme de terre");
        m.put("BAKED_POTATO", "Pomme de terre cuite");
        m.put("BEETROOT", "Betterave");
        m.put("WHEAT", "Blé");
        m.put("WHEAT_SEEDS", "Graines de blé");
        m.put("SUGAR", "Sucre");
        m.put("SUGAR_CANE", "Canne à sucre");
        m.put("CAKE", "Gâteau");
        m.put("COOKIE", "Cookie");
        m.put("EGG", "Œuf");
        m.put("KELP", "Varech");
        m.put("TORCH", "Torche");
        m.put("LANTERN", "Lanterne");
        m.put("CHEST", "Coffre");
        m.put("ENDER_CHEST", "Coffre de l'Ender");
        m.put("BARREL", "Tonneau");
        m.put("FURNACE", "Four");
        m.put("BLAST_FURNACE", "Haut fourneau");
        m.put("SMOKER", "Fumoir");
        m.put("CRAFTING_TABLE", "Table de craft");
        m.put("ANVIL", "Enclume");
        m.put("ENCHANTING_TABLE", "Table d'enchantement");
        m.put("BREWING_STAND", "Alambic");
        m.put("CAULDRON", "Chaudron");
        m.put("HOPPER", "Entonnoir");
        m.put("LADDER", "Échelle");
        m.put("DIRT", "Terre");
        m.put("GRASS_BLOCK", "Bloc d'herbe");
        m.put("COBBLESTONE", "Pierre taillée");
        m.put("SAND", "Sable");
        m.put("GRAVEL", "Gravier");
        m.put("GLASS", "Verre");
        m.put("OBSIDIAN", "Obsidienne");
        m.put("BEDROCK", "Bedrock");
        m.put("TNT", "TNT");
        m.put("BARRIER", "Barrière invisible");
        m.put("COMMAND_BLOCK", "Bloc de commande");
        m.put("SPAWNER", "Générateur de monstres");
        m.put("DEBUG_STICK", "Bâton de débogage");
        m.put("STRUCTURE_VOID", "Vide de structure");
        m.put("STRUCTURE_BLOCK", "Bloc de structure");
        m.put("JIGSAW", "Bloc de puzzle");
        m.put("LIGHT", "Bloc de lumière");
        m.put("KNOWLEDGE_BOOK", "Livre de connaissances");
        return Map.copyOf(m);
    }

    /**
     * Objets qui existent bel et bien comme objets, mais qu'un joueur ne peut pas obtenir en jeu
     * normal : les proposer en récompense est presque toujours une erreur.
     *
     * <p>Cette liste ne <strong>retire rien</strong> du catalogue — elle ne sert qu'à afficher un
     * avertissement. L'API Bukkit n'expose aucun indicateur « obtenable en survie », donc la seule
     * option honnête est une liste nommée, courte, et explicitement présentée comme indicative.</p>
     */
    private static final List<String> CREATIVE_ONLY = List.of(
            "BARRIER", "BEDROCK", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART", "DEBUG_STICK", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
            "LIGHT", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME", "KNOWLEDGE_BOOK",
            "PETRIFIED_OAK_SLAB", "FARMLAND", "DIRT_PATH", "BUDDING_AMETHYST", "REINFORCED_DEEPSLATE");

    /** Ce matériau est-il réservé au mode créatif ou au jeu technique ? */
    public static boolean creativeOnly(String materialId) {
        return materialId != null && CREATIVE_ONLY.contains(materialId.toUpperCase(Locale.ROOT));
    }

    /**
     * Libellé français d'un matériau, ou un repli anglais embelli quand aucune règle ne
     * s'applique. Ne renvoie jamais {@code null} ni une chaîne vide.
     */
    public static String french(String materialId) {
        if (materialId == null || materialId.isBlank()) {
            return "";
        }
        String id = materialId.trim().toUpperCase(Locale.ROOT);

        String exact = EXACT.get(id);
        if (exact != null) {
            return exact;
        }
        String composed = compose(id);
        if (composed != null) {
            return composed;
        }
        // Repli assumé et visible : de l'anglais lisible plutôt qu'une traduction inventée.
        return MiniText.prettifyId(id);
    }

    /**
     * Décompose {@code <MATIÈRE>_<NATURE>} quand c'est possible.
     *
     * @return le libellé composé, ou {@code null} si l'identifiant ne suit pas ce schéma
     */
    private static String compose(String id) {
        for (String[] kind : KINDS) {
            String suffix = "_" + kind[0];
            if (!id.endsWith(suffix)) {
                continue;
            }
            String matterId = id.substring(0, id.length() - suffix.length());
            String matter = MATTERS.get(matterId);
            if (matter == null) {
                // Matière inconnue : on ne devine pas. Mieux vaut le repli anglais, visiblement
                // non traduit, qu'un « Épée en Netherrack Brick » bancal.
                return null;
            }
            return elide(kind[1], matter);
        }
        return null;
    }

    /**
     * Compose le libellé en appliquant l'élision du français : « Lingot <strong>de</strong> or »
     * n'existe pas, c'est « Lingot <strong>d'</strong>or ». L'élision ne concerne que « de » —
     * « Pelle en or » reste correct.
     */
    private static String elide(String pattern, String matter) {
        if (pattern.contains("de %s") && startsWithVowel(matter)) {
            return String.format(pattern.replace("de %s", "d'%s"), matter);
        }
        return String.format(pattern, matter);
    }

    private static boolean startsWithVowel(String word) {
        if (word.isEmpty()) {
            return false;
        }
        // « h » muet inclus : aucun des mots de la table ne commence par un h aspiré.
        return "aeiouyàâäéèêëîïôöùûüh".indexOf(Character.toLowerCase(word.charAt(0))) >= 0;
    }
}
