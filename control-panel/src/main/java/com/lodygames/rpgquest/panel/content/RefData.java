package com.lodygames.rpgquest.panel.content;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Données de référence pour l'éditeur #46 : sources des {@code <select>} / {@code <datalist>} et
 * base des vérifications de cohérence de {@link QuestValidator} / {@link StoryValidator}.
 *
 * <p>Les listes de quêtes / PNJ / mondes proviennent du <strong>dernier relevé de l'agent</strong>
 * (actions {@code quest.list} / {@code npc.list} + mondes du heartbeat) : elles peuvent être vides
 * si aucun relevé n'a encore été fait — dans ce cas les vérifications de référence deviennent des
 * {@code INFO} (« impossible de vérifier ») plutôt que des {@code WARNING}.</p>
 *
 * <p>Les entités restent une liste <strong>curée</strong> des valeurs les plus courantes (le
 * module control-panel ne peut pas dépendre de Bukkit) ; la saisie reste libre et le vrai parser
 * RPGQuest tranchera au chargement côté serveur.</p>
 *
 * <p>Les <strong>matériaux</strong>, eux, viennent du relevé {@code item.catalogs} du serveur
 * depuis l'issue #196 : la liste curée qui servait avant ne contenait que 76 entrées, dont deux
 * épées sur les sept de la version réelle — chercher « sword » ne trouvait donc presque rien.
 * {@link #MATERIALS} ne subsiste que comme repli tant qu'aucun relevé n'a été fait, et l'interface
 * le dit alors explicitement au lieu de faire passer un extrait pour le catalogue.</p>
 */
public record RefData(List<String> quests, List<String> npcs, List<String> worlds,
                      boolean questsKnown, boolean npcsKnown, boolean worldsKnown,
                      Map<String, String> npcNames, Map<String, String> questNames,
                      Map<String, String> questOrigins, Map<String, List<String>> questPrereqs,
                      ItemCatalog itemCatalog) {

    /**
     * Catalogue des matériaux réellement exposés par la version installée (issue #196).
     *
     * @param items             matériaux utilisables comme icône <strong>et</strong> comme
     *                          récompense : le serveur garantit qu'ils peuvent exister en objet
     * @param blocksWithoutItem blocs réels <strong>sans</strong> forme d'objet (eau, feu,
     *                          portail…). Conservés exprès : ils permettent d'expliquer un refus
     *                          plutôt que de laisser croire que la valeur n'existe pas du tout
     * @param minecraftVersion  version Minecraft du relevé, affichée pour lever tout doute sur la
     *                          provenance de la liste
     */
    public record ItemCatalog(List<String> items, List<String> blocksWithoutItem,
                              String minecraftVersion) {

        public ItemCatalog {
            items = List.copyOf(items == null ? List.of() : items);
            blocksWithoutItem = List.copyOf(blocksWithoutItem == null ? List.of() : blocksWithoutItem);
            minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        }

        public static ItemCatalog empty() {
            return new ItemCatalog(List.of(), List.of(), "");
        }

        /** Un relevé a-t-il réellement eu lieu ? Une liste vide n'est jamais un catalogue. */
        public boolean known() {
            return !items.isEmpty();
        }

        public boolean isBlockWithoutItem(String value) {
            String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            return blocksWithoutItem.contains(v);
        }
    }

    /** Origines possibles d'une quête du catalogue fusionné (issues #163/#164). */
    public static final String ORIGIN_BOTH = "both";
    public static final String ORIGIN_SOURCE = "source";
    public static final String ORIGIN_RUNTIME = "runtime";

    /** Constructeur historique (6 composantes) : aucun libellé de PNJ ni de quête. */
    public RefData(List<String> quests, List<String> npcs, List<String> worlds,
                   boolean questsKnown, boolean npcsKnown, boolean worldsKnown) {
        this(quests, npcs, worlds, questsKnown, npcsKnown, worldsKnown, Map.of(), Map.of());
    }

    /** Constructeur historique (7 composantes) : libellés de PNJ, mais pas de quête. */
    public RefData(List<String> quests, List<String> npcs, List<String> worlds,
                   boolean questsKnown, boolean npcsKnown, boolean worldsKnown,
                   Map<String, String> npcNames) {
        this(quests, npcs, worlds, questsKnown, npcsKnown, worldsKnown, npcNames, Map.of());
    }

    /** Constructeur historique (8 composantes) : ni origine, ni graphe de prérequis (#163). */
    public RefData(List<String> quests, List<String> npcs, List<String> worlds,
                   boolean questsKnown, boolean npcsKnown, boolean worldsKnown,
                   Map<String, String> npcNames, Map<String, String> questNames) {
        this(quests, npcs, worlds, questsKnown, npcsKnown, worldsKnown, npcNames, questNames,
                Map.of(), Map.of());
    }

    /** Constructeur historique (10 composantes) : aucun catalogue de matériaux relevé (#196). */
    public RefData(List<String> quests, List<String> npcs, List<String> worlds,
                   boolean questsKnown, boolean npcsKnown, boolean worldsKnown,
                   Map<String, String> npcNames, Map<String, String> questNames,
                   Map<String, String> questOrigins, Map<String, List<String>> questPrereqs) {
        this(quests, npcs, worlds, questsKnown, npcsKnown, worldsKnown, npcNames, questNames,
                questOrigins, questPrereqs, ItemCatalog.empty());
    }

    public RefData {
        quests = List.copyOf(quests == null ? List.of() : quests);
        npcs = List.copyOf(npcs == null ? List.of() : npcs);
        worlds = List.copyOf(worlds == null ? List.of() : worlds);
        npcNames = Map.copyOf(npcNames == null ? Map.of() : npcNames);
        questNames = Map.copyOf(questNames == null ? Map.of() : questNames);
        questOrigins = Map.copyOf(questOrigins == null ? Map.of() : questOrigins);
        Map<String, List<String>> prereqCopy = new java.util.LinkedHashMap<>();
        if (questPrereqs != null) {
            questPrereqs.forEach((k, v) -> prereqCopy.put(k, List.copyOf(v == null ? List.of() : v)));
        }
        questPrereqs = Map.copyOf(prereqCopy);
        itemCatalog = itemCatalog == null ? ItemCatalog.empty() : itemCatalog;
    }

    public static RefData empty() {
        return new RefData(List.of(), List.of(), List.of(), false, false, false);
    }

    /** Nom d'affichage d'un PNJ (« Garde »), ou son id s'il n'en a pas / est inconnu. */
    public String npcLabel(String id) {
        String s = id == null ? "" : id.trim();
        String name = npcNames.get(s);
        return name == null || name.isBlank() || name.equals(s) ? s : name;
    }

    /**
     * Titre humain d'une quête (« Premiers pas ») à partir de son id technique, ou l'id lui-même
     * si aucun relevé {@code quest.list} ne le connaît. Comparaison sur l'id « nu » (namespace
     * {@code rpgquest:} retiré) — l'éditeur de story affiche le titre au-dessus de l'id (#46, §3/§5).
     */
    public String questLabel(String id) {
        String s = QuestYaml.plainId(id);
        String name = questNames.get(s);
        return name == null || name.isBlank() || name.equals(s) ? s : name;
    }

    public boolean isQuestKnown(String id) {
        String p = QuestYaml.plainId(id);
        return quests.stream().anyMatch(q -> QuestYaml.plainId(q).equals(p));
    }

    /**
     * Issue #163/#164 : d'où vient cette quête — {@link #ORIGIN_BOTH} (source éditable + chargée
     * par le serveur), {@link #ORIGIN_SOURCE} (enregistrée dans la source mais pas encore déployée
     * / pas encore relue par le serveur), {@link #ORIGIN_RUNTIME} (le serveur la connaît mais elle
     * n'est pas dans l'espace de travail source, ex. fichier posé à la main sur le serveur), ou
     * {@code ""} si l'id est inconnu. Jamais deviné : uniquement ce que les deux relevés disent.
     */
    public String questOrigin(String id) {
        String p = QuestYaml.plainId(id);
        String o = questOrigins.get(p);
        return o == null ? "" : o;
    }

    /** Libellé court et non ambigu de l'origine, pour une puce / un badge d'interface. */
    public String questOriginLabel(String id) {
        return switch (questOrigin(id)) {
            case ORIGIN_BOTH -> "source + serveur";
            case ORIGIN_SOURCE -> "source uniquement";
            case ORIGIN_RUNTIME -> "serveur uniquement";
            default -> "";
        };
    }

    /** Prérequis connus d'une quête (ids « nus »), tels que relevés dans la source ou le runtime. */
    public List<String> prereqsOf(String id) {
        List<String> l = questPrereqs.get(QuestYaml.plainId(id));
        return l == null ? List.of() : l;
    }

    /**
     * Issue #163 : cherche un cycle introduit en donnant {@code chosen} comme prérequis de
     * {@code selfId}. Renvoie le chemin lisible du cycle (ex. {@code b -> a -> b}) ou {@code null}
     * s'il n'y en a pas.
     *
     * <p>Parcours en profondeur depuis chaque prérequis choisi, en suivant le graphe
     * <em>déjà connu</em> ({@link #questPrereqs}) : si l'on retombe sur {@code selfId}, la
     * sélection fermerait la boucle. Le graphe connu ne contient pas encore la sélection en cours
     * d'édition — c'est précisément ce qu'on teste. Une quête absente du graphe est une feuille
     * (prudence : on ne peut pas inventer ses prérequis, donc on n'invente pas de cycle non
     * plus).</p>
     */
    public String findPrereqCycle(String selfId, List<String> chosen) {
        String self = QuestYaml.plainId(selfId);
        if (self.isEmpty() || chosen == null) {
            return null;
        }
        for (String raw : chosen) {
            String start = QuestYaml.plainId(raw);
            if (start.isEmpty() || start.equals(self)) {
                continue; // l'auto-référence a son propre diagnostic, plus explicite
            }
            List<String> path = walkTo(self, start, new LinkedHashSet<>());
            if (path != null) {
                // Le chemin rendu se lit dans le sens « dépend de » : self -> start -> … -> self.
                StringBuilder sb = new StringBuilder(self);
                for (String step : path) {
                    sb.append(" → ").append(step);
                }
                return sb.toString();
            }
        }
        return null;
    }

    /** DFS borné par {@code seen} : renvoie le chemin de {@code from} jusqu'à {@code target}, ou null. */
    private List<String> walkTo(String target, String from, Set<String> seen) {
        if (!seen.add(from)) {
            return null; // cycle préexistant dans le graphe connu, étranger à la sélection testée
        }
        if (from.equals(target)) {
            return new java.util.ArrayList<>(List.of(from));
        }
        for (String next : prereqsOf(from)) {
            String n = QuestYaml.plainId(next);
            if (n.isEmpty()) {
                continue;
            }
            List<String> sub = walkTo(target, n, seen);
            if (sub != null) {
                List<String> path = new java.util.ArrayList<>();
                path.add(from);
                path.addAll(sub);
                return path;
            }
        }
        return null;
    }

    public boolean isNpcKnown(String id) {
        String s = id == null ? "" : id.trim();
        return npcs.stream().anyMatch(n -> n.equalsIgnoreCase(s));
    }

    public boolean isWorldKnown(String name) {
        String s = name == null ? "" : name.trim();
        return worlds.stream().anyMatch(w -> w.equalsIgnoreCase(s));
    }

    /** Options d'un {@code selectSource} de {@link Descriptors.Field}, prêtes pour un {@code <datalist>}. */
    public List<String> options(String source) {
        return switch (source == null ? "" : source) {
            case "entity" -> ENTITIES;
            // #196 : le vrai catalogue du serveur dès qu'il est relevé ; sinon le repli curé,
            // signalé comme tel par l'interface.
            case "material", "icon" -> materials();
            case "category" -> CATEGORIES;
            case "npc" -> npcs;
            case "quest" -> quests;
            case "world" -> worlds;
            default -> List.of();
        };
    }

    public boolean sourceKnown(String source) {
        return switch (source == null ? "" : source) {
            case "entity" -> true;
            // #196 : on ne prétend « connaître » les matériaux que si le relevé serveur
            // existe. Avec le seul repli curé, un matériau absent de la liste est tout à
            // fait légitime : le dire WARNING serait un faux positif.
            case "material" -> itemCatalog.known();
            case "npc" -> npcsKnown;
            case "quest" -> questsKnown;
            case "world" -> worldsKnown;
            // « category » et « icon » : saisie libre légitime (nouvelle catégorie, n'importe quel
            // matériau vanilla) — on ne prétend jamais « connaître » la liste, donc jamais de
            // WARNING sur ces champs (au pire un INFO côté validateur).
            default -> false;
        };
    }

    public boolean isValueKnown(String source, String value) {
        return switch (source == null ? "" : source) {
            case "entity" -> contains(ENTITIES, value);
            case "material" -> contains(materials(), value);
            case "npc" -> isNpcKnown(value);
            case "quest" -> isQuestKnown(value);
            case "world" -> isWorldKnown(value);
            default -> true;
        };
    }

    /**
     * Matériaux proposés : le relevé serveur s'il existe, sinon le repli curé. Jamais la fusion
     * des deux — mélanger un extrait et un catalogue complet rendrait impossible de dire à
     * l'utilisateur ce qu'il regarde.
     */
    public List<String> materials() {
        return itemCatalog.known() ? itemCatalog.items() : MATERIALS;
    }

    /** Le catalogue affiché vient-il réellement du serveur ? */
    public boolean materialsFromServer() {
        return itemCatalog.known();
    }

    /** Ajoute (ou remplace) le catalogue de matériaux relevé, sans toucher au reste. */
    public RefData withItemCatalog(ItemCatalog catalog) {
        return new RefData(quests, npcs, worlds, questsKnown, npcsKnown, worldsKnown,
                npcNames, questNames, questOrigins, questPrereqs,
                catalog == null ? ItemCatalog.empty() : catalog);
    }

    private static boolean contains(List<String> list, String value) {
        String s = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return list.contains(s);
    }

    /** Fusionne des id de quête supplémentaires (ex. le brouillon courant) sans doublon. */
    public RefData withExtraQuests(List<String> extra) {
        Set<String> merged = new LinkedHashSet<>(quests);
        for (String e : extra) {
            if (e != null && !e.isBlank()) {
                merged.add(QuestYaml.plainId(e));
            }
        }
        return new RefData(List.copyOf(merged), npcs, worlds, questsKnown, npcsKnown, worldsKnown,
                npcNames, questNames, questOrigins, questPrereqs, itemCatalog);
    }

    // ---- listes curées (les plus courantes ; saisie libre toujours possible) ----------------

    /**
     * Catégories de quête <strong>proposées</strong> (le champ reste en saisie libre : le moteur
     * RPGQuest n'impose aucune énumération de catégories). Sert uniquement à alimenter la liste
     * déroulante — jamais à rejeter une catégorie inédite (#46, point 8).
     */
    public static final List<String> CATEGORIES = List.of(
            "tutorial", "combat", "crafting", "mining", "farming", "fishing", "exploration",
            "gathering", "story", "side", "event", "reputation");

    public static final List<String> ENTITIES = List.of(
            "ZOMBIE", "SKELETON", "SPIDER", "CAVE_SPIDER", "CREEPER", "ENDERMAN", "WITCH", "SLIME",
            "MAGMA_CUBE", "BLAZE", "GHAST", "HUSK", "STRAY", "DROWNED", "PHANTOM", "PILLAGER",
            "VINDICATOR", "EVOKER", "RAVAGER", "SILVERFISH", "ENDERMITE", "GUARDIAN", "SHULKER",
            "WITHER_SKELETON", "PIGLIN", "PIGLIN_BRUTE", "HOGLIN", "ZOGLIN", "ZOMBIFIED_PIGLIN",
            "WARDEN", "BREEZE", "COW", "PIG", "SHEEP", "CHICKEN", "RABBIT", "HORSE", "WOLF", "CAT",
            "FOX", "BEE", "TURTLE", "AXOLOTL", "GOAT", "FROG", "ALLAY", "VILLAGER", "IRON_GOLEM",
            "SQUID", "GLOW_SQUID", "DOLPHIN", "COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH");

    /**
     * Repli curé, utilisé <strong>uniquement</strong> tant que le relevé {@code item.catalogs}
     * n'a pas eu lieu (issue #196). Ce n'est pas un catalogue : c'est un dépannage, et l'interface
     * l'annonce comme tel. La vraie liste vient du serveur.
     */
    public static final List<String> MATERIALS = List.of(
            "OAK_LOG", "BIRCH_LOG", "SPRUCE_LOG", "DARK_OAK_LOG", "ACACIA_LOG", "JUNGLE_LOG",
            "OAK_PLANKS", "STICK", "COBBLESTONE", "STONE", "DIRT", "GRAVEL", "SAND", "GLASS",
            "COAL", "CHARCOAL", "RAW_IRON", "IRON_INGOT", "RAW_GOLD", "GOLD_INGOT", "RAW_COPPER",
            "COPPER_INGOT", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "REDSTONE", "QUARTZ", "NETHERITE_INGOT",
            "NETHERITE_SCRAP", "ANCIENT_DEBRIS", "AMETHYST_SHARD", "AMETHYST_CLUSTER", "WHEAT",
            "WHEAT_SEEDS", "CARROT", "POTATO", "BEETROOT", "APPLE", "BREAD", "SUGAR_CANE", "BAMBOO",
            "KELP", "STRING", "SPIDER_EYE", "BONE", "GUNPOWDER", "ROTTEN_FLESH", "ENDER_PEARL",
            "BLAZE_ROD", "GHAST_TEAR", "SLIME_BALL", "LEATHER", "FEATHER", "EGG", "MILK_BUCKET",
            "IRON_SWORD", "DIAMOND_SWORD", "IRON_PICKAXE", "DIAMOND_PICKAXE", "IRON_AXE", "SHIELD",
            "BOW", "ARROW", "TORCH", "CHEST", "FURNACE", "CRAFTING_TABLE", "OAK_SAPLING", "PAPER",
            "BOOK", "WRITABLE_BOOK", "MAP", "COMPASS", "CLOCK", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE");
}
