package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Issue #425 — règles de mise en page partagées qui empêchaient les zones de texte d'utiliser la
 * largeur de leur carte. Le rendu réel est vérifié au navigateur (rapport #425) ; ce test garde
 * seulement les règles en place pour qu'une retouche ne réintroduise pas la cause commune.
 */
class SharedFormLayoutCssTest {

    private static String css() throws Exception {
        try (InputStream in = SharedFormLayoutCssTest.class.getResourceAsStream("/assets/plugadmin.css")) {
            assertNotNull(in, "plugadmin.css absent du classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void multilineFieldsFollowTheWidthOfTheirField() throws Exception {
        assertTrue(css().contains("textarea{min-height:80px;resize:vertical;max-width:100%}"),
                "une zone multiligne ne doit plus être bornée à 460px");
    }

    @Test
    void formGridColumnsCanShrinkBelowTheirContent() throws Exception {
        String css = css();
        assertTrue(css.contains(".form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))"));
        assertFalse(css.contains(".form-grid{display:grid;grid-template-columns:1fr 1fr"),
                "1fr = minmax(auto,1fr) : un contenu long élargirait la colonne au-delà de la carte");
    }

    @Test
    void codePreviewsScrollInsideTheirBlock() throws Exception {
        String css = css();
        assertTrue(css.contains(".codeblock{position:relative;margin:12px 0;min-width:0;max-width:100%}"));
        assertTrue(css.contains(".codeblock.bounded pre{max-height:50vh;overflow-y:auto}")
                || css.contains(",.codeblock.bounded pre{max-height:50vh;overflow-y:auto}"));
        assertTrue(css.contains("textarea.code-input{font-family:var(--mono)"));
    }
}
