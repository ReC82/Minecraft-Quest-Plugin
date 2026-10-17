package com.lodygames.rpgquest.ui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Mise en page commune des boutons d'une fenêtre Paper Dialog (issue #467) : dialogues de PNJ et
 * invite d'entrée dans le Wild. Le type natif {@code multi_action} range par défaut les boutons sur
 * <strong>deux colonnes de 150 px</strong> et le client coupe (ou fait défiler) tout libellé plus
 * large que son bouton — « Commencer la quête : Des fleurs pour Lily » n'était jamais lisible.
 *
 * <p>Règles, identiques pour toutes les fenêtres :</p>
 * <ul>
 *   <li>{@link #COLUMNS} = 1 : un bouton par ligne, quel que soit le nombre de choix ;</li>
 *   <li>tous les boutons d'une fenêtre ont la même largeur : celle du plus long libellé, bornée
 *       entre {@link #MIN_WIDTH} et {@link #MAX_WIDTH}. {@code MAX_WIDTH} tient dans la plus petite
 *       surface réaliste — 854×480 en échelle d'interface automatique, ou une grande échelle sur
 *       un écran 720p : 427 px d'interface de large ;</li>
 *   <li>un libellé qui ne tiendrait pas même à {@code MAX_WIDTH} n'est jamais coupé en silence :
 *       le bouton affiche un libellé court terminé par « … », l'infobulle (survol ou focus
 *       clavier/manette) porte le texte complet, et {@link #fullLabelsBody} le répète en clair
 *       dans le corps de la fenêtre. Le choix et son action restent les mêmes.</li>
 * </ul>
 *
 * <p>La largeur est une <em>estimation</em> à partir des avances de la police par défaut du jeu
 * (6 px pour la plupart des glyphes, moins pour les glyphes étroits, +1 px en gras). Aucune
 * ressource client n'est lue : rien ne dépend d'un resource pack.</p>
 */
public final class DialogButtonLayout {

    /** Un bouton par ligne. */
    public static final int COLUMNS = 1;
    public static final int MIN_WIDTH = 200;
    public static final int MAX_WIDTH = 310;
    /** Marge intérieure gauche + droite du texte dans un bouton. */
    static final int PADDING = 16;
    private static final String ELLIPSIS = "…";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** Un bouton prêt à afficher : libellé (éventuellement raccourci) et infobulle ({@code null} si inutile). */
    public record Button(Component label, Component tooltip, boolean shortened, String fullText) {
    }

    private final int width;
    private final List<Button> buttons;

    private DialogButtonLayout(int width, List<Button> buttons) {
        this.width = width;
        this.buttons = List.copyOf(buttons);
    }

    public static DialogButtonLayout of(List<Component> labels) {
        int widest = 0;
        for (Component label : labels) {
            widest = Math.max(widest, textWidth(PLAIN.serialize(label), isBold(label)));
        }
        int width = Math.clamp(widest + PADDING, MIN_WIDTH, MAX_WIDTH);
        List<Button> buttons = new ArrayList<>(labels.size());
        for (Component label : labels) {
            String plain = PLAIN.serialize(label);
            boolean bold = isBold(label);
            if (textWidth(plain, bold) + PADDING <= width) {
                buttons.add(new Button(label, null, false, plain));
            } else {
                Component shortLabel = Component.text(shorten(plain, width - PADDING, bold), label.style());
                buttons.add(new Button(shortLabel, label, true, plain));
            }
        }
        return new DialogButtonLayout(width, buttons);
    }

    /** Largeur commune des boutons de cette fenêtre — réutilisée pour le corps, aligné dessus. */
    public int width() {
        return width;
    }

    public List<Button> buttons() {
        return buttons;
    }

    /**
     * Texte complet des libellés raccourcis, à ajouter au corps de la fenêtre ; vide si aucun ne
     * l'a été (cas normal), pour ne rien ajouter à l'écran sans raison.
     */
    public List<Component> fullLabelsBody() {
        List<Component> lines = new ArrayList<>();
        for (Button button : buttons) {
            if (button.shortened()) {
                lines.add(Component.text("» " + button.fullText(), NamedTextColor.GRAY));
            }
        }
        return lines;
    }

    /** Coupe {@code plain} à un espace si possible, pour tenir avec « … » dans {@code maxWidth}. */
    static String shorten(String plain, int maxWidth, boolean bold) {
        int budget = maxWidth - textWidth(ELLIPSIS, bold);
        int used = 0;
        int end = 0;
        while (end < plain.length()) {
            int advance = advance(plain.charAt(end)) + (bold ? 1 : 0);
            if (used + advance > budget) {
                break;
            }
            used += advance;
            end++;
        }
        int space = plain.lastIndexOf(' ', end);
        if (space > end / 2) {
            end = space;
        }
        return plain.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /** Largeur estimée en pixels d'interface ; le gras ajoute 1 px par glyphe. */
    static int textWidth(String plain, boolean bold) {
        int total = 0;
        for (int i = 0; i < plain.length(); i++) {
            total += advance(plain.charAt(i)) + (bold ? 1 : 0);
        }
        return total;
    }

    /** {@code true} si une partie du libellé est en gras — estimation pessimiste pour tout le libellé. */
    static boolean isBold(Component label) {
        if (label.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            return true;
        }
        for (Component child : label.children()) {
            if (isBold(child)) {
                return true;
            }
        }
        return false;
    }

    /** Avance d'un glyphe de la police par défaut (glyphe + 1 px d'espacement). */
    static int advance(char c) {
        return switch (c) {
            case '!', '\'', ',', '.', ':', ';', 'i', '|', 'í', 'ì', 'ï', 'î' -> 2;
            case '`', 'l' -> 3;
            case ' ', 'I', 't', '[', ']', '"', '*', '(', ')', '{', '}', '<', '>' -> 4;
            case 'f', 'k' -> 5;
            case '@', '~', '…' -> 7;
            default -> 6;
        };
    }
}
