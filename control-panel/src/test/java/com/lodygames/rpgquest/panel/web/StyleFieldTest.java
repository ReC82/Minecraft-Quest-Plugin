package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #195 — garanties du champ de texte stylé. Le point sensible n'est pas l'esthétique de la
 * palette : c'est que le champ <strong>réellement soumis</strong> reste identique à l'existant
 * (même nom, même valeur MiniMessage) et que la page reste utilisable sans JavaScript.
 */
class StyleFieldTest {

    @Test
    @DisplayName("Le champ soumis garde le nom et la valeur MiniMessage attendus par le serveur")
    void submittedFieldIsUnchanged() {
        String html = StyleField.render("display_name", "m-name", "Nom affiché",
                "<gold><bold>Roi des Marais</bold></gold>", true, null);

        assertTrue(html.contains("name=\"display_name\""), "le nom du champ ne doit pas changer");
        assertTrue(html.contains("value=\"&lt;gold&gt;&lt;bold&gt;Roi des Marais&lt;/bold&gt;&lt;/gold&gt;\""),
                "la valeur MiniMessage existante doit être rendue telle quelle (échappée)");
        assertTrue(html.contains("data-sf-store"), "panel.js doit pouvoir retrouver le champ soumis");
        assertTrue(html.contains(" required"), "le caractère obligatoire doit être conservé");
    }

    @Test
    @DisplayName("Sans JavaScript, le champ soumis reste un champ texte visible et l'éditeur guidé est masqué")
    void degradesWithoutJavaScript() {
        String html = StyleField.render("text", "dlg-x", "Texte", "Bonjour", false, null);

        // Le mode guidé est livré masqué : sans JS, pas de doublon de saisie à l'écran.
        assertTrue(html.contains("data-sf-guided hidden"), "l'éditeur guidé doit être masqué par défaut");
        // Le champ soumis n'est jamais rendu masqué côté serveur : c'est panel.js qui le replie.
        assertFalse(html.contains("sf-store-hidden"), "le champ soumis doit rester visible sans JavaScript");
        assertFalse(html.contains("type=\"hidden\""), "le champ soumis ne doit pas être un input caché");
    }

    @Test
    @DisplayName("La palette propose « aucune couleur » en plus des couleurs nommées")
    void paletteOffersNoColor() {
        String html = StyleField.render("text", "dlg-y", "Texte", "", false, null);

        assertTrue(html.contains("data-sf-color=\"\""), "« aucune couleur » doit être proposable");
        assertEquals(17, countOccurrences(html, "data-sf-color="),
                "16 couleurs nommées + « aucune couleur »");
        for (String deco : new String[] {"bold", "italic", "underlined", "strikethrough"}) {
            assertTrue(html.contains("data-sf-deco=\"" + deco + "\""), "décoration manquante : " + deco);
        }
    }

    @Test
    @DisplayName("Une valeur absente ou littéralement « null » devient une valeur vide, jamais le texte « null »")
    void missingValueIsEmpty() {
        assertTrue(StyleField.render("text", "a", "T", null, false, null).contains("value=\"\""));
        assertTrue(StyleField.render("text", "b", "T", "null", false, null).contains("value=\"\""));
    }

    @Test
    @DisplayName("Le libellé et la valeur sont échappés : un contenu malveillant ne peut pas sortir du champ")
    void escapesUntrustedContent() {
        String html = StyleField.render("text", "c", "<script>x</script>",
                "\"><img src=x onerror=alert(1)>", false, null);

        assertFalse(html.contains("<script>"), "le libellé doit être échappé");
        assertFalse(html.contains("<img src=x"), "la valeur doit être échappée");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
