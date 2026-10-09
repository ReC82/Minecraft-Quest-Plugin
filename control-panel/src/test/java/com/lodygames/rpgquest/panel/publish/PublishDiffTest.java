package com.lodygames.rpgquest.panel.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #47 — la comparaison source ↔ DEV.
 *
 * <p>Sens de lecture vérifié partout : DEV est l'<em>avant</em>, la source l'<em>après</em>. Une
 * ligne {@code +} est donc ce que la publication ajouterait. Si ce sens s'inversait, l'écran
 * dirait exactement le contraire de la vérité sans qu'aucun autre test ne s'en aperçoive.</p>
 */
class PublishDiffTest {

    private static List<String> signs(PublishDiff diff) {
        return diff.lines().stream().map(PublishDiff.Line::sign).toList();
    }

    private static List<String> texts(PublishDiff diff, PublishDiff.Kind kind) {
        return diff.lines().stream().filter(l -> l.kind() == kind)
                .map(PublishDiff.Line::text).toList();
    }

    // ---- Les cas du ticket ----------------------------------------------------------------------

    @Test
    void identicalContentReportsNoDifference() {
        String yaml = "id: rpgquest:a\ntitle: \"A\"\n";

        PublishDiff diff = PublishDiff.between(yaml, yaml);

        assertTrue(diff.comparable());
        assertTrue(diff.identical());
        assertFalse(diff.hasChanges());
        assertEquals("Aucune différence.", diff.summary());
        assertEquals(0, diff.added());
        assertEquals(0, diff.removed());
    }

    @Test
    void anAddedLineIsReportedAsAdded() {
        String dev = "a\nb\n";
        String source = "a\nnouvelle\nb\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        assertTrue(diff.hasChanges());
        assertEquals(1, diff.added());
        assertEquals(0, diff.removed());
        assertEquals(List.of("nouvelle"), texts(diff, PublishDiff.Kind.ADDED));
    }

    @Test
    void aRemovedLineIsReportedAsRemoved() {
        String dev = "a\nobsolete\nb\n";
        String source = "a\nb\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        assertEquals(0, diff.added());
        assertEquals(1, diff.removed());
        assertEquals(List.of("obsolete"), texts(diff, PublishDiff.Kind.REMOVED));
    }

    /** Une ligne modifiée se lit comme un retrait suivi d'un ajout — et c'est lisible ainsi. */
    @Test
    void aModifiedLineAppearsAsRemovedThenAdded() {
        String dev = "description: \"Ancienne description\"\n";
        String source = "description: \"Nouvelle description\"\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        assertEquals(1, diff.added());
        assertEquals(1, diff.removed());
        assertEquals(List.of("description: \"Ancienne description\""),
                texts(diff, PublishDiff.Kind.REMOVED));
        assertEquals(List.of("description: \"Nouvelle description\""),
                texts(diff, PublishDiff.Kind.ADDED));
        assertEquals("1 ligne(s) ajoutée(s), 1 retirée(s)", diff.summary());
    }

    /**
     * Le sens de lecture : {@code +} vient de la SOURCE, {@code -} vient de DEV.
     *
     * <p>Le test le plus important du fichier : une inversion ferait dire à l'écran l'exact
     * contraire de ce qui va se passer.</p>
     */
    @Test
    void plusComesFromTheSourceAndMinusFromDev() {
        PublishDiff diff = PublishDiff.between("seulement_sur_dev\n", "seulement_en_source\n");

        assertEquals(List.of("seulement_en_source"), texts(diff, PublishDiff.Kind.ADDED),
                "ce que la publication AJOUTERAIT vient de la source");
        assertEquals(List.of("seulement_sur_dev"), texts(diff, PublishDiff.Kind.REMOVED),
                "ce qu'elle RETIRERAIT est ce qui est sur DEV");
    }

    // ---- Changements éloignés : le défaut de TextDiff que ce diff évite ------------------------

    /**
     * Deux modifications éloignées restent <strong>deux</strong> modifications.
     *
     * <p>C'est la raison d'être de cette classe : un diff par préfixe/suffixe commun aurait marqué
     * tout le bloc central comme retiré puis réajouté, donc une vingtaine de lignes à relire pour
     * en trouver deux.</p>
     */
    @Test
    void twoDistantChangesStayTwoChangesAndDoNotSwallowTheMiddle() {
        StringBuilder dev = new StringBuilder("premier: ancien\n");
        StringBuilder source = new StringBuilder("premier: nouveau\n");
        for (int i = 0; i < 20; i++) {
            dev.append("milieu").append(i).append('\n');
            source.append("milieu").append(i).append('\n');
        }
        dev.append("dernier: ancien\n");
        source.append("dernier: nouveau\n");

        PublishDiff diff = PublishDiff.between(dev.toString(), source.toString());

        assertEquals(2, diff.added(), "une modification à chaque extrémité");
        assertEquals(2, diff.removed());
        // Et le milieu identique n'est PAS recopié en entier : une coupure le remplace.
        assertTrue(diff.lines().stream().anyMatch(l -> l.kind() == PublishDiff.Kind.GAP),
                "les 20 lignes inchangées doivent être élidées");
        assertTrue(diff.lines().size() < 20, "vue réduite : " + diff.lines().size() + " lignes");
    }

    @Test
    void contextLinesSurroundEachChange() {
        String dev = "a\nb\nc\nCIBLE\ne\nf\ng\n";
        String source = "a\nb\nc\nREMPLACE\ne\nf\ng\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        List<String> context = texts(diff, PublishDiff.Kind.CONTEXT);
        assertTrue(context.contains("c"), "contexte avant");
        assertTrue(context.contains("e"), "contexte après");
    }

    // ---- Numéros de ligne ----------------------------------------------------------------------

    @Test
    void lineNumbersPointToTheRealFiles() {
        String dev = "ligne1\nligne2\nligne3\n";
        String source = "ligne1\nMODIFIEE\nligne3\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        PublishDiff.Line removed = diff.lines().stream()
                .filter(l -> l.kind() == PublishDiff.Kind.REMOVED).findFirst().orElseThrow();
        PublishDiff.Line added = diff.lines().stream()
                .filter(l -> l.kind() == PublishDiff.Kind.ADDED).findFirst().orElseThrow();

        assertEquals(2, removed.devLine(), "la ligne 2 de DEV");
        assertEquals(0, removed.sourceLine(), "elle n'existe pas dans la source");
        assertEquals(2, added.sourceLine(), "la ligne 2 de la source");
        assertEquals(0, added.devLine(), "elle n'existe pas sur DEV");
    }

    // ---- Unicode et HTML -----------------------------------------------------------------------

    /** Accents, émojis et caractères larges traversent le diff sans dégât. */
    @Test
    void unicodeSurvivesTheComparison() {
        String dev = "title: \"Sécuriser les abords\"\n";
        String source = "title: \"Sécuriser les environs ☃ 🎯\"\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        assertEquals(List.of("title: \"Sécuriser les environs ☃ 🎯\""),
                texts(diff, PublishDiff.Kind.ADDED));
    }

    /**
     * Le diff ne décide pas de l'échappement, mais il ne doit pas le rendre impossible.
     *
     * <p>Le texte est restitué <strong>tel quel</strong> : c'est la page qui l'échappe
     * ({@code Http.esc}). Ce test verrouille le fait que le diff ne « nettoie » pas le contenu,
     * car un nettoyage silencieux ferait croire que le fichier publié est différent de ce qu'il
     * est.</p>
     */
    @Test
    void htmlLikeContentIsCarriedVerbatimAndNotSanitized() {
        String dev = "description: \"sûr\"\n";
        String source = "description: \"<script>alert('x')</script>\"\n";

        PublishDiff diff = PublishDiff.between(dev, source);

        assertEquals(List.of("description: \"<script>alert('x')</script>\""),
                texts(diff, PublishDiff.Kind.ADDED),
                "le contenu est rendu tel quel ; l'échappement est le travail de la page");
    }

    // ---- Fins de ligne : la seule normalisation autorisée ---------------------------------------

    /**
     * Deux fichiers qui ne diffèrent que par leurs fins de ligne sont « identiques », et on le dit.
     *
     * <p>Sans cela, un fichier enregistré sous Windows apparaîtrait <em>intégralement</em>
     * modifié — un diff inutilisable pour une différence invisible.</p>
     */
    @Test
    void onlyLineEndingsAreNormalizedAndTheDifferenceIsExplained() {
        PublishDiff diff = PublishDiff.between("a\r\nb\r\n", "a\nb\n");

        assertTrue(diff.identical());
        assertTrue(diff.note().contains("fins de ligne"), diff.note());
        assertEquals(0, diff.added());
    }

    /** Aucune autre normalisation : l'indentation et les espaces comptent. */
    @Test
    void indentationAndTrailingSpacesAreNotNormalizedAway() {
        PublishDiff diff = PublishDiff.between("  a\n", "    a\n");

        assertFalse(diff.identical(), "deux indentations différentes sont une différence réelle");
        assertEquals(1, diff.added());
        assertEquals(1, diff.removed());

        PublishDiff trailing = PublishDiff.between("a\n", "a \n");
        assertFalse(trailing.identical(), "un espace final est une différence réelle");
    }

    // ---- Absences --------------------------------------------------------------------------------

    @Test
    void aResourceAbsentFromDevIsNotComparedButExplained() {
        PublishDiff diff = PublishDiff.between(null, "id: a\n");

        assertFalse(diff.comparable());
        assertFalse(diff.hasChanges());
        assertTrue(diff.note().contains("pas encore sur DEV"), diff.note());
        assertTrue(diff.note().contains("tout le fichier sera créé"), diff.note());
    }

    @Test
    void aResourceAbsentFromSourceIsNotComparedButExplained() {
        PublishDiff diff = PublishDiff.between("id: a\n", null);

        assertFalse(diff.comparable());
        assertTrue(diff.note().contains("n'existe pas dans la source"), diff.note());
    }

    @Test
    void bothAbsentIsReportedRatherThanCrashing() {
        PublishDiff diff = PublishDiff.between(null, null);

        assertFalse(diff.comparable());
        assertFalse(diff.note().isBlank());
    }

    // ---- Bornes ---------------------------------------------------------------------------------

    @Test
    void anOversizedFileIsRefusedWithAReadableMessage() {
        String big = "x".repeat(PublishDiff.MAX_CHARS + 1);

        PublishDiff diff = PublishDiff.between(big, "a\n");

        assertFalse(diff.comparable());
        assertTrue(diff.note().contains("trop volumineux"), diff.note());
        assertTrue(diff.note().contains("empreintes restent comparables"), diff.note());
    }

    @Test
    void aFileWithTooManyLinesIsRefusedWithAReadableMessage() {
        String many = "a\n".repeat(PublishDiff.MAX_LINES + 1);

        PublishDiff diff = PublishDiff.between(many, many + "b\n");

        assertFalse(diff.comparable());
        assertTrue(diff.note().contains("trop long"), diff.note());
    }

    /** Un diff gigantesque est tronqué, et le dit — jamais une page illisible. */
    @Test
    void aHugeDiffIsTruncatedAndSaysSo() {
        StringBuilder dev = new StringBuilder();
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            dev.append("dev").append(i).append('\n');
            source.append("src").append(i).append('\n');
        }

        PublishDiff diff = PublishDiff.between(dev.toString(), source.toString());

        assertTrue(diff.truncated());
        assertEquals(PublishDiff.MAX_RENDERED, diff.lines().size());
        assertTrue(diff.note().contains("tronquée"), diff.note());
        // Les compteurs restent ceux du diff COMPLET : tronquer l'affichage ne doit pas mentir
        // sur l'ampleur du changement.
        assertEquals(600, diff.added());
        assertEquals(600, diff.removed());
    }

    @Test
    void anEmptyFileOnOneSideIsHandled() {
        PublishDiff added = PublishDiff.between("", "a\nb\n");
        assertTrue(added.hasChanges());
        assertEquals(2, added.added());

        PublishDiff emptied = PublishDiff.between("a\nb\n", "");
        assertTrue(emptied.hasChanges());
        assertEquals(2, emptied.removed());
    }

    @Test
    void theSignsAreTheExpectedCharacters() {
        PublishDiff diff = PublishDiff.between("a\nb\n", "a\nc\n");

        assertTrue(signs(diff).contains("-"));
        assertTrue(signs(diff).contains("+"));
        assertTrue(signs(diff).contains(" "), "le contexte porte un espace");
    }
}
