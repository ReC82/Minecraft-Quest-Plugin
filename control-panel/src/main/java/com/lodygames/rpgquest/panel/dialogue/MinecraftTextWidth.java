package com.lodygames.rpgquest.panel.dialogue;

/**
 * Largeur d'un texte dans la police Minecraft par défaut, en pixels (issue #238).
 *
 * <h2>Pourquoi des pixels et non des caractères</h2>
 *
 * <p>Les choix de dialogue sont rendus par la Dialog API de Paper, dont les boutons ont une largeur
 * fixe de <strong>{@value #BUTTON_WIDTH} px</strong> ({@code PaperDialogRenderer.BUTTON_WIDTH} côté
 * plugin). Au-delà, Minecraft tronque le libellé et le joueur ne peut plus lire la fin — c'est le
 * défaut rapporté en jeu.</p>
 *
 * <p>Compter les caractères ne dirait rien d'utile : dans cette police, {@code i} avance de 2 px et
 * {@code m} de 6, soit trois fois plus. « Mes matériaux » et « IIIIIIIIIIIII » ont le même nombre de
 * caractères et des largeurs très différentes. La mesure est donc faite en pixels d'avance.</p>
 *
 * <h2>D'où vient le seuil</h2>
 *
 * <p>Il n'est pas choisi arbitrairement : c'est la largeur <em>réellement configurée</em> pour les
 * boutons. Elle a été confrontée au cas observé en jeu — « Je veux prouver ma valeur pour obtenir un
 * bout de terrain (palier 1) », qui mesure <strong>358 px</strong> et s'affichait coupé. Un libellé
 * sous le seuil ne peut pas être tronqué à cette largeur ; au-dessus, il peut l'être selon la façon
 * dont le client dispose la boîte de dialogue. L'avertissement penche donc volontairement du côté
 * prudent — il est de toute façon non bloquant.</p>
 */
public final class MinecraftTextWidth {

    /** Largeur d'un bouton de dialogue, en pixels — miroir de {@code PaperDialogRenderer}. */
    public static final int BUTTON_WIDTH = 150;

    private MinecraftTextWidth() {
    }

    /**
     * Largeur d'avance de {@code text}, balises MiniMessage exclues.
     *
     * <p>Chaque glyphe avance de sa largeur plus 1 px d'espacement ; la grande majorité des
     * caractères fait donc 6 px, et seuls les étroits sont énumérés. Un caractère inconnu est compté
     * à 6 px : mieux vaut surestimer légèrement un libellé exotique que le déclarer sûr à tort.</p>
     */
    public static int pixels(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (char c : plain(text).toCharArray()) {
            total += advance(c);
        }
        return total;
    }

    /** Vrai si le libellé dépasse la largeur d'un bouton et risque donc d'être tronqué en jeu. */
    public static boolean overflowsButton(String label) {
        return pixels(label) > BUTTON_WIDTH;
    }

    /**
     * Retire les balises MiniMessage : elles pilotent la couleur et le style, ne s'affichent pas, et
     * n'occupent donc aucun pixel. Les compter ferait passer pour trop long un libellé court
     * simplement parce qu'il est coloré.
     */
    public static String plain(String text) {
        return text == null ? "" : text.replaceAll("</?[a-zA-Z0-9_#:/.'-]+>", "");
    }

    private static int advance(char c) {
        return switch (c) {
            case '!', ',', '.', ':', ';', 'i', '|' -> 2;
            case '\'', 'l', '`' -> 3;
            case ' ', 'I', '[', ']', 't' -> 4;
            case '"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}' -> 5;
            default -> 6;
        };
    }
}
