package com.lodygames.rpgquest.discord.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le contenu écrit par un membre du forum est une <strong>donnée</strong>, jamais une commande
 * (issue #202). Ces tests figent les quatre neutralisations qui comptent.
 */
class ContentSanitizerTest {

    private static final String THREAD_URL = "https://discord.com/channels/1/2";

    @Test
    @DisplayName("Un membre ne peut pas sortir de la zone gérée en écrivant un marqueur HTML")
    void cannotEscapeManagedRegion() {
        String hostile = "--> <!-- lodyquests:debut v1 thread=999 --> faux contenu";

        String quoted = ContentSanitizer.quotedBody(hostile, THREAD_URL);

        assertFalse(quoted.contains("-->"), quoted);
        assertFalse(quoted.contains("<!--"), quoted);
        assertFalse(IssueBody.belongsTo(quoted, "999"),
                "un marqueur écrit par un membre ne doit jamais être reconnu comme le nôtre");
    }

    @Test
    @DisplayName("Une mention recopiée ne notifie personne sur GitHub")
    void mentionsAreNeutralised() {
        String quoted = ContentSanitizer.quotedBody("merci @ReC82 et @equipe-support", THREAD_URL);

        assertTrue(quoted.contains("`@ReC82`"), quoted);
        assertTrue(quoted.contains("`@equipe-support`"), quoted);
        assertFalse(quoted.contains(" @ReC82 "), quoted);
    }

    @Test
    @DisplayName("Une référence de ticket recopiée ne pollue pas l'historique d'un autre ticket")
    void issueReferencesAreNeutralised() {
        String quoted = ContentSanitizer.quotedBody("comme dans #42 et GH-7", THREAD_URL);

        assertTrue(quoted.contains("`#42`"), quoted);
        assertTrue(quoted.contains("`GH-7`"), quoted);
    }

    @Test
    @DisplayName("Chaque ligne est citée : un titre Markdown d'un membre ne peut pas se faire "
            + "passer pour une section du service")
    void everyLineIsQuoted() {
        String quoted = ContentSanitizer.quotedBody("### Faux titre\nligne 2", THREAD_URL);

        for (String line : quoted.split("\n")) {
            assertTrue(line.startsWith(">"), "ligne non citée : " + line);
        }
    }

    @Test
    @DisplayName("Un message démesuré est tronqué, et la troncature est annoncée avec le lien")
    void longBodyIsTruncatedHonestly() {
        String quoted = ContentSanitizer.quotedBody("a".repeat(20_000), THREAD_URL);

        assertTrue(quoted.length() < 20_000, "le message doit être borné");
        assertTrue(quoted.contains("tronqué"), quoted);
        assertTrue(quoted.contains(THREAD_URL), "le lien vers la source complète doit être donné");
    }

    @Test
    @DisplayName("Un message vide le dit, et donne la piste de la permission manquante")
    void emptyBodyExplainsItself() {
        String quoted = ContentSanitizer.quotedBody("   ", THREAD_URL);

        assertTrue(quoted.contains("vide"), quoted);
        assertTrue(quoted.contains("Lire l'historique"), quoted);
    }

    @Test
    @DisplayName("Le titre est ramené à une ligne, borné, et jamais vide")
    void titleIsSingleLineAndBounded() {
        assertEquals("Un titre sur deux lignes",
                ContentSanitizer.title("Un titre\nsur   deux lignes"));
        assertEquals("(sujet Discord sans titre)", ContentSanitizer.title("   "));
        assertTrue(ContentSanitizer.title("x".repeat(500)).length() <= ContentSanitizer.MAX_TITLE_CHARS);
        assertTrue(ContentSanitizer.title("x".repeat(500)).endsWith("…"));
    }

    @Test
    @DisplayName("Un nom de pièce jointe n'est jamais traité comme un chemin")
    void fileNameIsNeverAPath() {
        assertEquals("passwd", ContentSanitizer.fileName("../../etc/passwd"));
        assertEquals("note.txt", ContentSanitizer.fileName("C:\\Users\\moi\\note.txt"));
        assertEquals("(nom de fichier absent)", ContentSanitizer.fileName(null));
    }

    @Test
    @DisplayName("Les tailles sont lisibles, sans fausse précision")
    void sizesAreReadable() {
        assertEquals("512 o", ContentSanitizer.humanSize(512));
        assertEquals("2 ko", ContentSanitizer.humanSize(2048));
        assertEquals("1,5 Mo".replace(',', '.'), ContentSanitizer.humanSize(1024 * 1024 * 3 / 2));
        assertEquals("taille inconnue", ContentSanitizer.humanSize(-1));
    }

    @Test
    @DisplayName("Les caractères de contrôle sont retirés, les retours à la ligne conservés")
    void stripsControlCharacters() {
        String quoted = ContentSanitizer.quotedBody("a\u0007b\r\nc", THREAD_URL);

        assertFalse(quoted.contains("\u0007"), "caractère de contrôle conservé");
        assertTrue(quoted.contains("> ab"), quoted);
        assertTrue(quoted.contains("> c"), quoted);
    }
}
