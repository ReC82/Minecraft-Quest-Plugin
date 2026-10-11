package com.lodygames.rpgquest.building.plan;

import java.util.Locale;

/**
 * Les états de bloc <strong>corrects</strong>, calculés par le moteur (issue #94, lot B).
 *
 * <h2>D'où viennent ces états</h2>
 *
 * <p>Ils ne sont pas inventés : ils sont <strong>extraits</strong> de
 * {@link com.lodygames.rpgquest.building.TestHutBlueprint} et
 * {@link com.lodygames.rpgquest.building.TestWatchtowerBlueprint}, les deux bâtiments écrits à la
 * main qui fonctionnent réellement en jeu depuis l'issue #213. C'est la consigne : examiner ce qui
 * marche avant d'inventer.</p>
 *
 * <p>Comparaison instructive. La hutte écrit ses escaliers ainsi :</p>
 *
 * <pre>minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]</pre>
 *
 * <p>Le modèle de langue, lui, avait écrit {@code minecraft:oak_stairs[face=bottom,shape=straight]}.
 * La propriété s'appelle {@code half}, pas {@code face} — {@code face} appartient aux boutons. Un
 * seul mot, et l'escalier devient un état invalide que notre contrôle de forme laissait passer.
 * C'est exactement le genre de détail qu'un modèle ne doit plus avoir à produire.</p>
 *
 * <h2>Pourquoi un point de passage unique</h2>
 *
 * <p>Ces méthodes sont la <strong>seule</strong> façon dont le moteur fabrique un état à
 * propriétés. Conséquence directe : la liste blanche du lot D (#252) n'a qu'un endroit à décrire, et
 * un état produit ici est correct par construction. Le modèle n'écrit plus jamais
 * {@code [facing=...]} à la main pour une porte, une vitre, un escalier ou une lanterne.</p>
 */
public final class BlockStates {

    private BlockStates() {
    }

    /** Les quatre orientations cardinales, dans la notation de Minecraft. */
    public enum Dir {
        NORTH, SOUTH, EAST, WEST;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** L'opposé — l'intérieur d'une façade regarde dans l'autre sens. */
        public Dir opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        /** Le pas en X de cette direction. */
        public int dx() {
            return switch (this) {
                case EAST -> 1;
                case WEST -> -1;
                default -> 0;
            };
        }

        /** Le pas en Z. Minecraft : le nord est en {@code -Z}. */
        public int dz() {
            return switch (this) {
                case SOUTH -> 1;
                case NORTH -> -1;
                default -> 0;
            };
        }

        /** Cette direction court-elle selon l'axe X ? */
        public boolean alongX() {
            return this == EAST || this == WEST;
        }
    }

    /**
     * Une moitié de porte.
     *
     * <p>Une porte Minecraft est <strong>deux blocs</strong>, et les deux doivent porter le même
     * {@code facing} et la même {@code hinge}, sinon la porte se rend invisible ou s'ouvre de
     * travers. Le moteur pose donc toujours la paire : c'est la primitive {@code door} qui appelle
     * cette méthode deux fois, jamais le modèle.</p>
     *
     * @param wood   le type complet, par exemple {@code minecraft:oak_door}
     * @param facing la direction vers laquelle la porte regarde, c'est-à-dire l'extérieur
     * @param upper  moitié haute plutôt que basse
     * @param right  charnière à droite plutôt qu'à gauche
     */
    public static String door(String wood, Dir facing, boolean upper, boolean right) {
        return wood + "[facing=" + facing.value()
                + ",half=" + (upper ? "upper" : "lower")
                + ",hinge=" + (right ? "right" : "left")
                + ",open=false,powered=false]";
    }

    /**
     * Un panneau de verre, connecté le long du mur qui le porte.
     *
     * <p>Les quatre propriétés de connexion décident de la forme affichée. Un panneau dont les
     * quatre valent {@code false} apparaît comme un poteau isolé au milieu du mur — c'est le défaut
     * visuel classique. Le moteur les calcule donc depuis l'axe du mur : un mur qui court selon X
     * connecte est-ouest.</p>
     */
    public static String pane(String glass, boolean wallAlongX) {
        return glass + "[east=" + wallAlongX + ",north=" + !wallAlongX
                + ",south=" + !wallAlongX + ",west=" + wallAlongX + ",waterlogged=false]";
    }

    /**
     * Une marche d'escalier.
     *
     * <p>{@code facing} désigne le sens dans lequel on <strong>monte</strong> : une volée qui monte
     * vers l'est porte {@code facing=east}, comme dans la tour de garde. {@code half=bottom} est la
     * marche normale ; {@code top} sert aux dessous de toiture.</p>
     */
    public static String stairs(String material, Dir facing, boolean top) {
        return material + "[facing=" + facing.value()
                + ",half=" + (top ? "top" : "bottom")
                + ",shape=straight,waterlogged=false]";
    }

    /** Une dalle, basse par défaut — le faîte d'un toit à deux pans en utilise. */
    public static String slab(String material, boolean top) {
        return material + "[type=" + (top ? "top" : "bottom") + ",waterlogged=false]";
    }

    /** Un tronc orienté. {@code axis=y} est le poteau vertical des angles de la hutte. */
    public static String log(String material, String axis) {
        String a = axis == null || axis.isBlank() ? "y" : axis.trim().toLowerCase(Locale.ROOT);
        return material + "[axis=" + a + "]";
    }

    /** Une lanterne, suspendue ou posée. Une lanterne suspendue a besoin d'un support au-dessus. */
    public static String lantern(String material, boolean hanging) {
        return material + "[hanging=" + hanging + ",waterlogged=false]";
    }

    /** Une torche murale. {@code facing} est la direction vers laquelle la torche pointe. */
    public static String wallTorch(String material, Dir facing) {
        return material + "[facing=" + facing.value() + "]";
    }

    /**
     * Une échelle, adossée à un mur.
     *
     * <p>{@code facing} d'une échelle désigne la direction vers laquelle elle <strong>regarde</strong>,
     * donc l'opposé du mur qui la porte : une échelle plaquée contre un mur au nord regarde le sud.
     * C'est une inversion que les modèles se trompent à faire, et c'est pourquoi la primitive prend
     * le mur et calcule le reste.</p>
     */
    public static String ladder(String material, Dir facing) {
        return material + "[facing=" + facing.value() + ",waterlogged=false]";
    }

    /** Une trappe, utile en haut d'une échelle. */
    public static String trapdoor(String material, Dir facing, boolean top) {
        return material + "[facing=" + facing.value()
                + ",half=" + (top ? "top" : "bottom")
                + ",open=false,powered=false,waterlogged=false]";
    }

    /**
     * Le type nu d'un état, si l'appelant en a fourni un avec des propriétés.
     *
     * <p>Le modèle a le droit d'écrire {@code minecraft:oak_door} ou, par inadvertance,
     * {@code minecraft:oak_door[facing=west]}. Dans les deux cas le moteur ne garde que le type et
     * recalcule les propriétés : c'est lui qui sait, et accepter les propriétés du modèle
     * annulerait tout le bénéfice des primitives.</p>
     */
    public static String bareType(String state) {
        if (state == null) {
            return "";
        }
        String s = state.trim();
        int bracket = s.indexOf('[');
        return bracket < 0 ? s : s.substring(0, bracket);
    }

    /** Le type nu, ou un défaut si l'appelant n'a rien fourni. */
    public static String bareTypeOr(String state, String fallback) {
        String bare = bareType(state);
        return bare.isEmpty() ? fallback : bare;
    }
}
