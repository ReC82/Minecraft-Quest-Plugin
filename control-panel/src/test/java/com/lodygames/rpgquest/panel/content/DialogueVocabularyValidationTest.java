package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #146 phase 2 — le vocabulaire des actions et des conditions, vérifié à l'enregistrement.
 *
 * <p><strong>Le trou que cela ferme.</strong> Jusqu'ici, un dialogue citant une action inexistante
 * traversait l'éditeur et l'import sans un mot : l'erreur n'apparaissait qu'au chargement du serveur
 * Minecraft, c'est-à-dire longtemps après que quelqu'un ait cliqué « enregistrer », et dans un
 * journal que personne ne lit à ce moment-là. Avec l'atelier IA, cela devenait inacceptable : toute
 * la promesse du lot est que la proposition soit confrontée aux validateurs réels <em>avant</em>
 * confirmation.</p>
 *
 * <p>La seconde moitié de ce fichier est l'autre moitié du marché : <strong>un resserrement de
 * validation ne doit pas condamner le contenu existant</strong>. Chaque dialogue réellement embarqué
 * dans le dépôt est donc relu et validé ici. Si ce test tombe, ce n'est pas le contenu qui est
 * fautif par défaut — c'est peut-être le catalogue qui a oublié un type que le moteur accepte.</p>
 */
class DialogueVocabularyValidationTest {

    private static List<Diagnostic> validate(String yaml) {
        DialogueYaml.ReadResult read = DialogueYaml.read(yaml);
        assertTrue(read.problems().isEmpty(), "relecture : " + read.problems());
        return DialogueValidator.validate(read.draft());
    }

    private static List<Diagnostic> errors(String yaml) {
        return validate(yaml).stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList();
    }

    /** Un dialogue minimal valide, dont seul le bloc du choix change d'un test à l'autre. */
    private static String withChoice(String choiceBlock) {
        return """
                id: tc265_pnj
                start: accueil
                nodes:
                  accueil:
                    speaker: "Un PNJ"
                    text: "Bonjour."
                    choices:
                """ + choiceBlock;
    }

    // ---- Ce qui est désormais refusé -----------------------------------------------------------

    @Test
    void anInventedActionTypeIsRejected() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Téléporte-moi."
                        actions:
                          - type: TELEPORTER_LE_JOUEUR
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("TELEPORTER_LE_JOUEUR"), errors.toString());
        assertTrue(errors.get(0).message().contains("START_QUEST"),
                "le message doit lister les types acceptés, sinon il n'aide personne");
    }

    @Test
    void anInventedConditionTypeIsRejected() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Je suis riche."
                        conditions:
                          - type: EST_RICHE
                        actions:
                          - type: CLOSE
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("EST_RICHE"));
    }

    @Test
    void aMissingRequiredFieldIsRejected() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Commence."
                        actions:
                          - type: START_QUEST
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("quest"), errors.toString());
    }

    /**
     * Un champ étranger est presque toujours le signe que l'auteur — humain ou IA — a confondu deux
     * types. Le laisser passer silencieusement produit une action qui ne fait pas ce qu'il croit.
     */
    @Test
    void aFieldBelongingToAnotherTypeIsRejected() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Donne."
                        actions:
                          - type: GIVE_ITEM
                            material: BREAD
                            amount: 1
                            quest: rpgquest:inutile
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("quest"), errors.toString());
        assertTrue(errors.get(0).message().contains("inattendu"), errors.toString());
    }

    @Test
    void anUnknownQuestStateIsRejectedAndTheSixRealOnesAreNot() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Et alors ?"
                        conditions:
                          - type: QUEST_STATE
                            quest: rpgquest:test
                            state: PRESQUE_FINIE
                        actions:
                          - type: CLOSE
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("PRESQUE_FINIE"));

        for (String state : List.of("NOT_STARTED", "ACTIVE", "READY_TO_TURN_IN", "COMPLETED",
                "FAILED", "ABANDONED")) {
            assertTrue(errors(withChoice("""
                      - text: "Et alors ?"
                        conditions:
                          - type: QUEST_STATE
                            quest: rpgquest:test
                            state: """ + state + """

                        actions:
                          - type: CLOSE
                """)).isEmpty(), "l'état réel « " + state + " » doit être accepté");
        }
    }

    @Test
    void aNonPositiveAmountIsRejected() {
        assertFalse(errors(withChoice("""
                      - text: "Donne."
                        actions:
                          - type: GIVE_ITEM
                            material: BREAD
                            amount: 0
                """)).isEmpty(), "zéro exemplaire n'a pas de sens");

        assertFalse(errors(withChoice("""
                      - text: "Donne."
                        actions:
                          - type: GIVE_ITEM
                            material: BREAD
                            amount: beaucoup
                """)).isEmpty(), "une quantité en lettres non plus");
    }

    @Test
    void anEntryWithoutATypeIsRejected() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Rien."
                        actions:
                          - quest: rpgquest:test
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("type"));
    }

    // ---- Ce qui reste accepté ------------------------------------------------------------------

    /** {@code negate} est commun à toutes les conditions, donc à aucune action. */
    @Test
    void negateIsAcceptedOnAConditionAndRefusedOnAnAction() {
        assertTrue(errors(withChoice("""
                      - text: "Pas encore fini ?"
                        conditions:
                          - type: QUEST_STATE
                            quest: rpgquest:test
                            state: COMPLETED
                            negate: true
                        actions:
                          - type: CLOSE
                """)).isEmpty());

        assertFalse(errors(withChoice("""
                      - text: "Ferme."
                        actions:
                          - type: CLOSE
                            negate: true
                """)).isEmpty(), "inverser une action ne veut rien dire");
    }

    /**
     * Une valeur de {@code negate} qui n'est ni {@code true} ni {@code false} vaut
     * <strong>silencieusement</strong> false pour le moteur : la condition écrite pour être inversée
     * ne l'est pas, et rien ne le signale. C'est le pire cas possible — le dialogue fonctionne, mais
     * à l'envers de l'intention.
     */
    @Test
    void aNegateValueThatIsNotABooleanIsRejected() {
        List<Diagnostic> errors = errors(withChoice("""
                      - text: "Sauf si…"
                        conditions:
                          - type: NO_MAIN_CLAIM
                            negate: peut-etre
                        actions:
                          - type: CLOSE
                """));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("negate"), errors.toString());

        for (String value : List.of("true", "false", "TRUE", "False")) {
            assertTrue(errors(withChoice("""
                      - text: "Sauf si…"
                        conditions:
                          - type: NO_MAIN_CLAIM
                            negate: """ + value + """

                        actions:
                          - type: CLOSE
                """)).isEmpty(), "« " + value + " » doit être accepté");
        }
    }

    /** Issue #123 : le PNJ reste facultatif, c'est ce qui rend la branche de remise réutilisable. */
    @Test
    void anOptionalFieldMayBeOmittedOrGiven() {
        assertTrue(errors(withChoice("""
                      - text: "Voilà."
                        conditions:
                          - type: HAS_PENDING_DELIVERY
                        actions:
                          - type: DELIVER_QUEST_ITEMS
                          - type: CLOSE
                """)).isEmpty(), "sans npc");

        assertTrue(errors(withChoice("""
                      - text: "Voilà."
                        actions:
                          - type: DELIVER_QUEST_ITEMS
                            npc: jo
                          - type: CLOSE
                """)).isEmpty(), "avec npc");
    }

    @Test
    void everyActionAndConditionOfTheCatalogsCanBeWrittenAndPasses() {
        for (Descriptors.Descriptor d : Descriptors.DIALOGUE_ACTIONS) {
            assertTrue(errors(withChoice(choiceFor(d, "actions"))).isEmpty(),
                    "action « " + d.kind() + " » refusée alors qu'elle est au catalogue");
        }
        for (Descriptors.Descriptor d : Descriptors.DIALOGUE_CONDITIONS) {
            assertTrue(errors(withChoice(choiceFor(d, "conditions")
                            + "    actions:\n      - type: CLOSE\n")).isEmpty(),
                    "condition « " + d.kind() + " » refusée alors qu'elle est au catalogue");
        }
    }

    /** Une entrée remplie à partir du descripteur lui-même : chaque champ reçoit une valeur plausible. */
    private static String choiceFor(Descriptors.Descriptor d, String list) {
        StringBuilder sb = new StringBuilder("      - text: \"Un choix.\"\n");
        sb.append("        ").append(list).append(":\n");
        sb.append("          - type: ").append(d.kind()).append('\n');
        for (Descriptors.Field f : d.fields()) {
            sb.append("            ").append(f.name()).append(": ").append(switch (f.name()) {
                case "quest" -> "rpgquest:test";
                case "material" -> "BREAD";
                case "amount" -> "2";
                case "state" -> "ACTIVE";
                case "command" -> "spawn";
                case "permission" -> "rpgquest.test";
                default -> "valeur";
            }).append('\n');
        }
        return sb.toString();
    }

    // ---- Le contenu réel du dépôt reste valide -------------------------------------------------

    /**
     * Le resserrement ne doit condamner aucun dialogue embarqué. Ce test lit les fichiers réels du
     * plugin ; il s'abstient si les sources ne sont pas à côté, plutôt que d'échouer pour la
     * mauvaise raison — même convention que {@code BundledExamplesDriftTest}.
     *
     * <p>Seuls les diagnostics de <strong>vocabulaire</strong> sont examinés, parce que c'est le
     * périmètre de ce resserrement. {@code guard.yml} produit par ailleurs des erreurs de cible
     * {@code next} qui lui préexistent et n'ont rien à voir : {@code MiniYaml} ne sait pas lire les
     * scalaires repliés ({@code text: >}) et abandonne la suite de la map, si bien que six de ses
     * nœuds ne sont pas vus. La limitation est assumée et documentée sur {@code MiniYaml} ; le test
     * suivant vérifie qu'elle n'est au moins pas <em>silencieuse</em>.</p>
     */
    @Test
    @DisplayName("Aucun dialogue embarqué du dépôt n'enfreint le vocabulaire")
    void everyBundledDialogueOfTheRepositoryRespectsTheVocabulary() throws IOException {
        for (Path file : bundledDialogues()) {
            DialogueYaml.ReadResult read = DialogueYaml.read(Files.readString(file));
            assertTrue(read.problems().isEmpty(),
                    file.getFileName() + " : relecture impossible — " + read.problems());
            List<Diagnostic> vocabulary = DialogueValidator.validate(read.draft()).stream()
                    .filter(d -> d.level() == Diagnostic.Level.ERROR)
                    .filter(d -> d.field().contains(".actions[") || d.field().contains(".conditions["))
                    .toList();
            assertTrue(vocabulary.isEmpty(), file.getFileName() + " enfreint le vocabulaire : "
                    + vocabulary + ". Si le moteur accepte ce type ou ce champ, c'est le catalogue "
                    + "de Descriptors qu'il faut compléter, pas le fichier qu'il faut changer.");
        }
    }

    /**
     * Un fichier que {@link MiniYaml} ne sait pas relire intégralement doit être <strong>signalé</strong>,
     * jamais accepté en silence : sinon l'éditeur enregistrerait une version tronquée du dialogue et
     * détruirait les nœuds qu'il n'a pas vus. C'est exactement le cas de {@code guard.yml} et de ses
     * scalaires repliés, et c'est le garde-fou round-trip qui doit l'attraper.
     */
    @Test
    @DisplayName("Un dialogue que MiniYaml tronque est signalé, pas accepté en silence")
    void aDialogueTruncatedByTheReaderIsReportedRatherThanSilentlyAccepted() throws IOException {
        for (Path file : bundledDialogues()) {
            String yaml = Files.readString(file);
            int declared = (int) yaml.lines()
                    .filter(l -> l.matches("^ {2}[A-Za-z0-9_-]+:\\s*$")).count();
            int parsed = DialogueYaml.read(yaml).draft().nodes.size();
            if (declared > 0 && parsed < declared) {
                assertFalse(DialogueYaml.roundTripProblems(yaml).isEmpty(),
                        file.getFileName() + " : " + parsed + " nœuds relus sur " + declared
                                + " déclarés, et aucun problème signalé. Une sauvegarde depuis "
                                + "l'éditeur écraserait les nœuds manquants.");
            }
        }
    }

    private static List<Path> bundledDialogues() throws IOException {
        Path dir = Path.of("..", "src", "main", "resources", "dialogues");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "sources du plugin absentes : test de cohérence non applicable ici");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        Assumptions.assumeFalse(files.isEmpty(), "aucun dialogue embarqué");
        return files;
    }
}
