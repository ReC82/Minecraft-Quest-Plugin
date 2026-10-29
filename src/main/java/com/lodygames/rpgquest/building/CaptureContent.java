package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.CaptureSurvey.ContentFate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Politique de contenu d'une capture et ses mots en français (issue #903). Aucune API serveur :
 * le relevé Bukkit fournit des faits, ce code décide et explique — il est donc testable.
 *
 * <h2>Ce que fait réellement la capture</h2>
 *
 * <p>WorldEdit copie chaque bloc avec <strong>toutes ses propriétés</strong> (orientation,
 * escaliers, dalles, emplacements de livres occupés d'une bibliothèque sculptée, livre posé sur un
 * pupitre…). Pour les blocs à contenu, la capture retire ensuite les <strong>données du
 * bloc</strong> — objets, livres et leur texte, motifs — pour ne jamais exporter un inventaire,
 * un livre écrit ou un objet spécial. D'où trois cas visibles :</p>
 *
 * <ul>
 *   <li>l'aspect est porté par les <em>propriétés</em> (livres d'une bibliothèque sculptée, livre
 *       d'un pupitre) : il est conservé comme décor ({@link ContentFate#VISUAL_KEPT}) ;</li>
 *   <li>l'aspect est porté par les <em>données</em> (objets d'une étagère, nourriture d'un feu de
 *       camp, motifs d'un pot décoré) : il est perdu ({@link ContentFate#VISUAL_LOST}) ;</li>
 *   <li>le contenu ne se voit pas (coffre, tonneau…) : retiré sans effet visuel
 *       ({@link ContentFate#HIDDEN_DROPPED}).</li>
 * </ul>
 */
public final class CaptureContent {

    private CaptureContent() {
    }

    /**
     * Le sort du contenu d'un bloc.
     *
     * @param type            identifiant Minecraft du bloc, sans espace de noms
     * @param hasItems        l'inventaire du bloc (ou l'objet qu'il porte) n'est pas vide
     * @param showsBooks      les propriétés du bloc affichent des livres (emplacements occupés
     *                        d'une bibliothèque sculptée, livre d'un pupitre)
     * @param customSherds    pot décoré portant au moins un motif autre que la brique
     */
    public static ContentFate fate(String type, boolean hasItems, boolean showsBooks,
                                   boolean customSherds) {
        String key = type == null ? "" : type;
        if (key.equals("chiseled_bookshelf") || key.equals("lectern")) {
            return showsBooks ? ContentFate.VISUAL_KEPT
                    : hasItems ? ContentFate.HIDDEN_DROPPED : ContentFate.EMPTY;
        }
        if (customSherds) {
            return ContentFate.VISUAL_LOST;
        }
        if (!hasItems) {
            return ContentFate.EMPTY;
        }
        return displaysItems(key) ? ContentFate.VISUAL_LOST : ContentFate.HIDDEN_DROPPED;
    }

    /** Blocs dont les objets sont dessinés par le jeu à partir de leurs données. */
    static boolean displaysItems(String key) {
        return key.endsWith("_shelf") || key.endsWith("campfire");
    }

    // ---- Noms en français ------------------------------------------------------------------------

    private static final Map<String, String> BLOCKS = Map.ofEntries(
            Map.entry("chiseled_bookshelf", "bibliothèque sculptée"),
            Map.entry("lectern", "pupitre"),
            Map.entry("chest", "coffre"),
            Map.entry("trapped_chest", "coffre piégé"),
            Map.entry("barrel", "tonneau"),
            Map.entry("furnace", "fourneau"),
            Map.entry("blast_furnace", "haut fourneau"),
            Map.entry("smoker", "fumoir"),
            Map.entry("hopper", "entonnoir"),
            Map.entry("dropper", "dropper"),
            Map.entry("dispenser", "distributeur"),
            Map.entry("brewing_stand", "alambic"),
            Map.entry("crafter", "fabricateur"),
            Map.entry("campfire", "feu de camp"),
            Map.entry("soul_campfire", "feu de camp des âmes"),
            Map.entry("jukebox", "juke-box"),
            Map.entry("decorated_pot", "pot décoré"),
            Map.entry("suspicious_sand", "sable suspect"),
            Map.entry("suspicious_gravel", "gravier suspect"));

    private static final Map<String, String> WOODS = Map.ofEntries(
            Map.entry("oak", "chêne"), Map.entry("spruce", "sapin"), Map.entry("birch", "bouleau"),
            Map.entry("jungle", "acajou"), Map.entry("acacia", "acacia"),
            Map.entry("dark_oak", "chêne noir"), Map.entry("pale_oak", "chêne pâle"),
            Map.entry("mangrove", "palétuvier"), Map.entry("cherry", "cerisier"),
            Map.entry("bamboo", "bambou"), Map.entry("crimson", "carmin"),
            Map.entry("warped", "biscornu"));

    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("item_frame", "cadre"),
            Map.entry("glow_item_frame", "cadre lumineux"),
            Map.entry("painting", "tableau"),
            Map.entry("armor_stand", "support d'armure"),
            Map.entry("item_display", "objet affiché"),
            Map.entry("block_display", "bloc affiché"),
            Map.entry("text_display", "texte affiché"),
            Map.entry("leash_knot", "nœud de laisse"),
            Map.entry("minecart", "wagonnet"),
            Map.entry("boat", "bateau"),
            Map.entry("villager", "villageois"),
            Map.entry("cat", "chat"),
            Map.entry("wolf", "loup"),
            Map.entry("chicken", "poule"),
            Map.entry("cow", "vache"),
            Map.entry("pig", "cochon"),
            Map.entry("sheep", "mouton"),
            Map.entry("horse", "cheval"),
            Map.entry("turtle", "tortue"),
            Map.entry("parrot", "perroquet"),
            Map.entry("axolotl", "axolotl"),
            Map.entry("item", "objet au sol"));

    /** Nom français d'un bloc à contenu ; l'identifiant brut s'il n'est pas connu. */
    public static String blockName(String key) {
        String known = BLOCKS.get(key);
        if (known != null) {
            return known;
        }
        if (key != null && key.endsWith("_shelf")) {
            String wood = WOODS.get(key.substring(0, key.length() - "_shelf".length()));
            if (wood != null) {
                return "étagère en " + wood;
            }
        }
        if (key != null && key.endsWith("shulker_box")) {
            return "boîte de Shulker";
        }
        return key == null ? "" : key;
    }

    /** Nom français d'une entité ; l'identifiant brut s'il n'est pas connu. */
    public static String entityName(String key) {
        return ENTITIES.getOrDefault(key, key == null ? "" : key);
    }

    /** « 14 × bibliothèque sculptée, 2 × pupitre » pour les blocs du sort donné, triés. */
    public static String blocksLabel(CaptureSurvey survey, ContentFate fate) {
        Map<String, Integer> byName = new TreeMap<>();
        for (CaptureSurvey.ContainerSpot spot : survey.containers()) {
            if (spot.fate() == fate) {
                byName.merge(blockName(spot.type()), 1, Integer::sum);
            }
        }
        return join(byName);
    }

    /** « 4 × cadre, 1 × tortue ». */
    public static String entitiesLabel(Map<String, Integer> counts) {
        Map<String, Integer> byName = new TreeMap<>();
        counts.forEach((type, count) -> byName.merge(entityName(type), count, Integer::sum));
        return join(byName);
    }

    private static String join(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((name, count) -> parts.add(count + " × " + name));
        return String.join(", ", parts);
    }

    // ---- Ce que l'aperçu dit -------------------------------------------------------------------

    /** Une ligne de la section « Blocs avec contenu » de l'aperçu. */
    public record Group(ContentFate fate, long count, String title, String text) {
    }

    /** Le premier message de l'aperçu, orienté résultat. */
    public static String headline(CaptureSurvey survey) {
        return survey.nonAirBlocks() + " bloc(s) seront enregistrés à leur position, avec leurs "
                + "propriétés de bloc (orientation, forme des escaliers et dalles, livres visibles…) "
                + "telles qu'elles sont dans le monde.";
    }

    /** Les lignes « Blocs avec contenu », de la plus importante à la moins importante. */
    public static List<Group> groups(CaptureSurvey survey) {
        List<Group> groups = new ArrayList<>();
        long lost = survey.count(ContentFate.VISUAL_LOST);
        if (lost > 0) {
            groups.add(new Group(ContentFate.VISUAL_LOST, lost, "Objets affichés perdus",
                    lost + " bloc(s) perdront les objets qu'ils montrent : "
                            + blocksLabel(survey, ContentFate.VISUAL_LOST) + ". Le bloc et son "
                            + "orientation sont conservés, mais les objets posés dessus (ou les motifs "
                            + "d'un pot décoré) ne sont pas copiés : la variante ne sera pas identique "
                            + "à l'œil."));
        }
        long kept = survey.count(ContentFate.VISUAL_KEPT);
        if (kept > 0) {
            groups.add(new Group(ContentFate.VISUAL_KEPT, kept, "Livres visibles conservés comme décor",
                    kept + " bloc(s) gardent leurs livres visibles : "
                            + blocksLabel(survey, ContentFate.VISUAL_KEPT) + ". Les livres eux-mêmes "
                            + "(titre, enchantement, texte écrit) ne sont pas copiés : dans la variante "
                            + "posée, ce sont des livres de décor : tenter de les prendre les fait "
                            + "disparaître sans rien donner."));
        }
        long hidden = survey.count(ContentFate.HIDDEN_DROPPED);
        if (hidden > 0) {
            groups.add(new Group(ContentFate.HIDDEN_DROPPED, hidden, "Contenu non copié",
                    hidden + " conteneur(s) seront enregistrés vides : "
                            + blocksLabel(survey, ContentFate.HIDDEN_DROPPED) + ". Leur contenu ne se "
                            + "voit pas de l'extérieur et n'est jamais copié (inventaires, livres, "
                            + "objets spéciaux) ; rien ne change à l'œil."));
        }
        long empty = survey.count(ContentFate.EMPTY);
        if (empty > 0) {
            groups.add(new Group(ContentFate.EMPTY, empty, "Déjà vides",
                    empty + " bloc(s) à contenu étaient vides : "
                            + blocksLabel(survey, ContentFate.EMPTY) + ". Ils sont enregistrés à "
                            + "l'identique."));
        }
        return groups;
    }

    /** La note discrète « Entités non incluses », vide s'il n'y en a pas. */
    public static String entityNote(CaptureSurvey survey) {
        if (survey.entityCount() == 0) {
            return "";
        }
        return entitiesLabel(survey.entities()) + ". Les entités (cadres, tableaux, animaux…) ne font "
                + "pas partie d'une capture : elles restent en place dans le monde et n'apparaîtront "
                + "pas dans la variante.";
    }

    /** Le texte de la case d'acquittement, vide si aucune perte visible n'est à accepter. */
    public static String acknowledgement(CaptureSurvey survey) {
        long lost = survey.visualLosses();
        return lost == 0 ? "" : "J'ai compris : " + lost + " bloc(s) perdront les objets qu'ils "
                + "affichent (" + blocksLabel(survey, ContentFate.VISUAL_LOST) + ").";
    }
}
