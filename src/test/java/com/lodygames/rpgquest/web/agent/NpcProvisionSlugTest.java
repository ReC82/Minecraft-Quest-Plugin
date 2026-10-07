package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Dérivation de l'identifiant logique depuis le nom affiché, lors d'une création de PNJ.
 *
 * <p>C'est cet identifiant qui sert de clé de fichier, de cible aux dialogues et aux quêtes, et de
 * <strong>garde-fou anti-doublon</strong> : un double clic produit le même identifiant, la seconde
 * tentative voit la définition déjà présente et refuse. Il doit donc être stable, ASCII, et ne
 * jamais dépendre du balisage de couleur du nom.</p>
 */
class NpcProvisionSlugTest {

    @Test
    void aPlainNameBecomesALowercaseIdentifier() {
        assertEquals("bob", BukkitAgentActions.slugify("Bob"));
        assertEquals("bob_le_bucheron", BukkitAgentActions.slugify("Bob le Bûcheron"));
    }

    @Test
    void accentsAreReducedToAscii() {
        assertEquals("tan", BukkitAgentActions.slugify("Tàn"));
        assertEquals("elodie", BukkitAgentActions.slugify("Élodie"));
        assertEquals("francois", BukkitAgentActions.slugify("François"));
    }

    @Test
    void miniMessageMarkupNeverLeaksIntoTheIdentifier() {
        // Le même nom, coloré ou non, doit donner le même identifiant : sinon un nom stylé
        // créerait un doublon invisible.
        assertEquals("roi_des_marais", BukkitAgentActions.slugify("<red>Roi des Marais</red>"));
        assertEquals("roi_des_marais", BukkitAgentActions.slugify("Roi des Marais"));
        assertEquals("roi", BukkitAgentActions.slugify("<red><bold>Roi</bold></red>"));
    }

    @Test
    void punctuationAndSpacesCollapseIntoSingleSeparators() {
        assertEquals("garde_du_village", BukkitAgentActions.slugify("Garde  --  du village !"));
        assertEquals("jo", BukkitAgentActions.slugify("  Jo  "));
    }

    @Test
    void anIdentifierNeverStartsOrEndsWithASeparator() {
        String slug = BukkitAgentActions.slugify("!!! Bob !!!");
        assertEquals("bob", slug);
    }

    @Test
    void aNameWithoutAnyLetterOrDigitYieldsAnEmptyIdentifier() {
        // L'appelant doit alors refuser explicitement, pas inventer un identifiant.
        assertEquals("", BukkitAgentActions.slugify("!!!"));
        assertEquals("", BukkitAgentActions.slugify("<red></red>"));
        assertEquals("", BukkitAgentActions.slugify(""));
        assertEquals("", BukkitAgentActions.slugify(null));
    }

    @Test
    void aVeryLongNameIsTruncatedToAUsableIdentifier() {
        String slug = BukkitAgentActions.slugify("A".repeat(200));
        assertTrue(slug.length() <= 48, "identifiant borné : " + slug.length());
        assertTrue(slug.matches("[a-z0-9._-]{1,64}"), "forme conforme au motif attendu");
    }

    @Test
    void aTruncatedIdentifierNeverEndsOnASeparator() {
        String slug = BukkitAgentActions.slugify("mot ".repeat(40));
        assertTrue(slug.length() <= 48);
        assertTrue(!slug.endsWith("_"), "pas de séparateur en fin après troncature : " + slug);
    }

    @Test
    void theDerivedIdentifierAlwaysMatchesTheLogicalIdPattern() {
        for (String name : new String[] {"Bob", "Bûcheron Bob", "<gold>Garde</gold>", "Jo-Jo",
            "Él.odie", "PNJ 42", "a".repeat(100)}) {
            String slug = BukkitAgentActions.slugify(name);
            assertTrue(slug.isEmpty() || slug.matches("[a-z0-9._-]{1,64}"),
                    "« " + name + " » -> « " + slug + " »");
        }
    }
}
