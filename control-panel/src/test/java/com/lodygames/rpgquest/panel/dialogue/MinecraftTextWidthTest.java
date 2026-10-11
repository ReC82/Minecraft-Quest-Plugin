package com.lodygames.rpgquest.panel.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #238 — la mesure qui décide si un libellé de bouton sera tronqué en jeu.
 *
 * <p>Les cas de référence sont les libellés <strong>réellement observés</strong> tronqués par le
 * propriétaire, et ceux qui les ont remplacés : le seuil n'est pas un nombre choisi au hasard, et
 * ces tests sont là pour qu'il reste vérifiable.</p>
 */
class MinecraftTextWidthTest {

    @Test
    @DisplayName("le libellé réellement vu tronqué en jeu dépasse largement le bouton")
    void theLabelActuallySeenTruncatedOverflowsTheButton() {
        String seen = "Je veux prouver ma valeur pour obtenir un bout de terrain (palier 1)";

        assertEquals(358, MinecraftTextWidth.pixels(seen),
                "mesure de référence : c'est ce libellé qui a ouvert le ticket");
        assertTrue(MinecraftTextWidth.overflowsButton(seen));
        assertTrue(MinecraftTextWidth.pixels(seen) > 2 * MinecraftTextWidth.BUTTON_WIDTH,
                "plus du double de la place disponible");
    }

    @Test
    @DisplayName("les libellés de remplacement tiennent tous dans un bouton")
    void theReplacementLabelsAllFit() {
        for (String label : new String[] {
                "Prouver ma valeur", "Agrandir mon terrain", "Premiers pas",
                "La chasse aux monstres", "Obtenir un terrain", "Terrain : palier 2",
                "Commencer : Premiers pas", "Commencer : Palier 1", "Commencer : La chasse",
                "Commencer l'amélioration", "Pas maintenant", "J'ai des matériaux",
                "Donner mes matériaux", "Comment est le Wild ?", "Les récompenses"}) {
            assertFalse(MinecraftTextWidth.overflowsButton(label),
                    () -> label + " mesure " + MinecraftTextWidth.pixels(label) + " px");
        }
    }

    /**
     * Le cœur de la justification : compter les caractères ne dirait rien. Ces deux libellés ont le
     * même nombre de caractères et des largeurs très différentes.
     */
    @Test
    @DisplayName("deux libellés de même longueur n'occupent pas la même place")
    void twoLabelsOfEqualLengthDoNotOccupyTheSameWidth() {
        String narrow = "iiiiiiiiiiiiiiiiiiiiiiii";
        String wide = "mmmmmmmmmmmmmmmmmmmmmmmm";

        assertEquals(narrow.length(), wide.length(), "même nombre de caractères");
        assertEquals(48, MinecraftTextWidth.pixels(narrow));
        assertEquals(144, MinecraftTextWidth.pixels(wide));
        assertFalse(MinecraftTextWidth.overflowsButton(narrow));
        assertFalse(MinecraftTextWidth.overflowsButton(wide), "144 px tient encore, de justesse");
        // La frontière exacte : 25 caractères larges font pile 150 px et tiennent ; le 26e déborde.
        assertFalse(MinecraftTextWidth.overflowsButton(wide + "m"), "150 px, pile la place dispo");
        assertTrue(MinecraftTextWidth.overflowsButton(wide + "mm"), "156 px : déborde");
    }

    @Test
    @DisplayName("les balises MiniMessage n'occupent aucun pixel")
    void miniMessageTagsTakeNoSpace() {
        assertEquals(MinecraftTextWidth.pixels("Premiers pas"),
                MinecraftTextWidth.pixels("<gold>Premiers pas</gold>"),
                "colorer un libellé ne le rallonge pas à l'écran");
        assertEquals("Premiers pas", MinecraftTextWidth.plain("<gold>Premiers pas</gold>"));
        // Un libellé long UNIQUEMENT à cause de ses balises ne doit pas être signalé.
        assertFalse(MinecraftTextWidth.overflowsButton(
                "<bold><italic><gradient:#ff0000:#00ff00>Court</gradient></italic></bold>"));
    }

    @Test
    @DisplayName("le seuil est la largeur réellement configurée pour les boutons")
    void theThresholdIsTheRealConfiguredButtonWidth() {
        assertEquals(150, MinecraftTextWidth.BUTTON_WIDTH,
                "miroir de PaperDialogRenderer.BUTTON_WIDTH : si l'un change, l'autre doit suivre");
        // Exactement à la limite : accepté. C'est la place disponible, pas une marge.
        assertEquals(150, MinecraftTextWidth.pixels("m".repeat(25)));
        assertFalse(MinecraftTextWidth.overflowsButton("m".repeat(25)));
    }

    @Test
    @DisplayName("un texte vide ou nul se mesure sans exploser")
    void emptyOrNullIsMeasuredWithoutBlowingUp() {
        assertEquals(0, MinecraftTextWidth.pixels(null));
        assertEquals(0, MinecraftTextWidth.pixels(""));
        assertEquals("", MinecraftTextWidth.plain(null));
        assertFalse(MinecraftTextWidth.overflowsButton(null));
    }

    @Test
    @DisplayName("les accents comptent comme leur lettre de base")
    void accentedCharactersCountAsTheirBaseLetter() {
        assertEquals(MinecraftTextWidth.pixels("materiaux"), MinecraftTextWidth.pixels("matériaux"));
    }
}
