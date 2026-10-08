package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Issue #146, phase 2 — le vocabulaire des actions et conditions de dialogue, déclaré côté panel.
 *
 * <p><strong>Ce test est la raison pour laquelle ce catalogue est utilisable.</strong> Un vocabulaire
 * recopié à la main dérive : le moteur gagne une action, le panel l'ignore, le schéma la refuse et
 * l'IA ne l'emploie jamais — ou pire, le panel garde un type que le moteur a retiré, et l'IA produit
 * du contenu que le serveur rejette. Les ensembles ci-dessous sont donc comparés <strong>à
 * l'identique</strong> aux énumérations réelles ; un ajout ou un retrait côté moteur fait échouer la
 * suite jusqu'à ce que quelqu'un décide quoi en faire.</p>
 *
 * <p>Le Control Panel ne dépend pas du module du plugin — c'est une isolation voulue —, donc les
 * ensembles sont écrits ici et non importés. C'est exactement la convention déjà retenue par
 * {@code EditorDescriptorsTest} pour les objectifs et les récompenses.</p>
 */
class DialogueDescriptorsTest {

    /** Réel : {@code dialogue.model.ActionType}, relu dans le code du moteur. */
    private static final Set<String> ENGINE_ACTIONS = Set.of(
            "START_QUEST", "ADVANCE_QUEST", "TURN_IN_QUEST", "GIVE_ITEM", "TAKE_ITEM",
            "SET_VARIABLE", "RUN_SAFE_COMMAND", "OPEN_DIALOGUE", "OPEN_MERCHANT",
            "GIVE_STARTER_KIT", "DELIVER_QUEST_ITEMS", "CLOSE");

    /** Réel : {@code dialogue.model.ConditionType}. */
    private static final Set<String> ENGINE_CONDITIONS = Set.of(
            "QUEST_STATE", "HAS_ITEM", "HAS_PERMISSION", "VARIABLE_EQUALS", "NO_MAIN_CLAIM",
            "HAS_MAIN_CLAIM", "LACKS_CUSTOM_ITEM", "HAS_PENDING_DELIVERY");

    /**
     * Clés YAML réellement lues par {@code DialogueDefinitionParser}, relevées cas par cas dans son
     * code. Un champ absent de cette liste serait inventé.
     */
    private static final Set<String> ENGINE_FIELDS = Set.of(
            "quest", "material", "amount", "key", "value", "command", "dialogue", "merchant",
            "npc", "permission", "item", "state", "negate");

    /**
     * États de quête acceptés par {@code parseQuestState} : {@code quest.model.QuestState}, qui en
     * compte <strong>six</strong>. {@code ABANDONED} est facile à oublier — il l'était dans la
     * première version de cette aide, et c'est précisément ce que ce test attrape.
     */
    private static final Set<String> ENGINE_QUEST_STATES = Set.of(
            "NOT_STARTED", "ACTIVE", "READY_TO_TURN_IN", "COMPLETED", "FAILED", "ABANDONED");

    private static Set<String> kinds(List<Descriptors.Descriptor> catalog) {
        return catalog.stream().map(Descriptors.Descriptor::kind).collect(Collectors.toSet());
    }

    // ---- Couverture exacte du moteur -----------------------------------------------------------

    @Test
    void actionKindsMatchTheEngineExactly() {
        assertEquals(ENGINE_ACTIONS, kinds(Descriptors.DIALOGUE_ACTIONS),
                "ni action en trop, ni action manquante");
    }

    @Test
    void conditionKindsMatchTheEngineExactly() {
        assertEquals(ENGINE_CONDITIONS, kinds(Descriptors.DIALOGUE_CONDITIONS));
    }

    @Test
    void theCatalogsHaveNoDuplicateKind() {
        assertEquals(Descriptors.DIALOGUE_ACTIONS.size(), kinds(Descriptors.DIALOGUE_ACTIONS).size());
        assertEquals(Descriptors.DIALOGUE_CONDITIONS.size(), kinds(Descriptors.DIALOGUE_CONDITIONS).size());
    }

    // ---- Aucun champ inventé -------------------------------------------------------------------

    @Test
    void everyFieldIsAKeyTheEngineActuallyReads() {
        for (Descriptors.Descriptor d : concat()) {
            for (Descriptors.Field f : d.fields()) {
                assertTrue(ENGINE_FIELDS.contains(f.name()),
                        d.kind() + " : champ « " + f.name() + " » inconnu du parseur du moteur");
            }
        }
        assertTrue(ENGINE_FIELDS.contains(Descriptors.NEGATE.name()));
    }

    @Test
    void everyDescriptorAndFieldCarriesHumanText() {
        for (Descriptors.Descriptor d : concat()) {
            assertFalse(d.label() == null || d.label().isBlank(), d.kind() + " : libellé");
            assertFalse(d.hint() == null || d.hint().isBlank(), d.kind() + " : description");
            for (Descriptors.Field f : d.fields()) {
                assertFalse(f.label() == null || f.label().isBlank(), d.kind() + "/" + f.name());
                assertFalse(f.help() == null || f.help().isBlank(), d.kind() + "/" + f.name());
            }
        }
    }

    @Test
    void everySelectFieldPointsAtAKnownSource() {
        Set<String> sources = Set.of("quest", "material", "npc", "dialogue", "merchant", "questState");
        for (Descriptors.Descriptor d : concat()) {
            for (Descriptors.Field f : d.fields()) {
                if (f.type() == Descriptors.FieldType.SELECT) {
                    assertTrue(sources.contains(f.selectSource()),
                            d.kind() + "/" + f.name() + " : source inconnue « " + f.selectSource() + " »");
                }
            }
        }
    }

    // ---- Champs exacts, type par type ----------------------------------------------------------

    /**
     * Les trois actions de quête ne prennent que {@code quest} : y ajouter un champ ferait croire à
     * une capacité qui n'existe pas.
     */
    @Test
    void questActionsTakeOnlyAQuestReference() {
        for (String kind : List.of("START_QUEST", "ADVANCE_QUEST", "TURN_IN_QUEST")) {
            assertEquals(List.of("quest"), fieldNames(kind), kind);
        }
    }

    @Test
    void itemActionsAndConditionsTakeAMaterialAndAnAmount() {
        for (String kind : List.of("GIVE_ITEM", "TAKE_ITEM")) {
            assertEquals(List.of("material", "amount"), fieldNames(kind), kind);
        }
        assertEquals(List.of("material", "amount"), conditionFieldNames("HAS_ITEM"));
    }

    /** Les actions sans paramètre n'en déclarent aucun — ni « npc », ni rien d'autre. */
    @Test
    void parameterlessActionsDeclareNoField() {
        assertEquals(List.of(), fieldNames("GIVE_STARTER_KIT"));
        assertEquals(List.of(), fieldNames("CLOSE"));
        assertEquals(List.of(), conditionFieldNames("NO_MAIN_CLAIM"));
        assertEquals(List.of(), conditionFieldNames("HAS_MAIN_CLAIM"));
    }

    /**
     * Issue #123 : le PNJ est <strong>facultatif</strong> sur ces deux types, et c'est ce qui rend
     * une branche de remise réutilisable telle quelle d'un PNJ à l'autre.
     */
    @Test
    void theNpcFieldIsOptionalOnDeliveryTypes() {
        assertFalse(required("DELIVER_QUEST_ITEMS", "npc"),
                "un npc obligatoire coderait la branche pour un PNJ précis");
        assertFalse(requiredCondition("HAS_PENDING_DELIVERY", "npc"));
    }

    /** La valeur d'une variable peut être vide : le moteur la remplace par une chaîne vide. */
    @Test
    void variableValueIsOptionalButItsKeyIsNot() {
        assertTrue(required("SET_VARIABLE", "key"));
        assertFalse(required("SET_VARIABLE", "value"));
        assertTrue(requiredCondition("VARIABLE_EQUALS", "key"));
        assertFalse(requiredCondition("VARIABLE_EQUALS", "value"));
    }

    @Test
    void questStateConditionTakesAQuestAndAState() {
        assertEquals(List.of("quest", "state"), conditionFieldNames("QUEST_STATE"));
        assertTrue(requiredCondition("QUEST_STATE", "state"));
    }

    /** L'aide du champ d'état doit nommer les six états réels : c'est elle que l'IA lit. */
    @Test
    void theQuestStateHelpNamesEveryRealState() {
        String help = Descriptors.dialogueCondition("QUEST_STATE").orElseThrow().fields().stream()
                .filter(f -> f.name().equals("state")).findFirst().orElseThrow().help();

        for (String state : ENGINE_QUEST_STATES) {
            assertTrue(help.contains(state), "état « " + state + " » absent de l'aide : " + help);
        }
    }

    /** La commande console est signalée comme sensible : le serveur la refuse hors liste blanche. */
    @Test
    void theSafeCommandSaysItIsWhitelisted() {
        Descriptors.Descriptor d = Descriptors.dialogueAction("RUN_SAFE_COMMAND").orElseThrow();

        assertTrue(d.hint().contains("liste blanche"), d.hint());
        assertEquals(List.of("command"), fieldNames("RUN_SAFE_COMMAND"));
    }

    // ---- Recherche -----------------------------------------------------------------------------

    @Test
    void lookupIsCaseInsensitiveAndRejectsUnknownKinds() {
        assertTrue(Descriptors.dialogueAction("close").isPresent());
        assertTrue(Descriptors.dialogueCondition("has_item").isPresent());
        assertTrue(Descriptors.dialogueAction("TELEPORTER_LE_JOUEUR").isEmpty());
        assertTrue(Descriptors.dialogueCondition("EST_RICHE").isEmpty());
    }

    /** Les deux catalogues sont bien distincts : une action n'est pas une condition. */
    @Test
    void actionsAndConditionsDoNotOverlap() {
        assertTrue(Descriptors.dialogueCondition("CLOSE").isEmpty());
        assertTrue(Descriptors.dialogueAction("HAS_ITEM").isEmpty());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static List<Descriptors.Descriptor> concat() {
        return java.util.stream.Stream
                .concat(Descriptors.DIALOGUE_ACTIONS.stream(), Descriptors.DIALOGUE_CONDITIONS.stream())
                .toList();
    }

    private static List<String> fieldNames(String kind) {
        return Descriptors.dialogueAction(kind).orElseThrow().fields().stream()
                .map(Descriptors.Field::name).toList();
    }

    private static List<String> conditionFieldNames(String kind) {
        return Descriptors.dialogueCondition(kind).orElseThrow().fields().stream()
                .map(Descriptors.Field::name).toList();
    }

    private static boolean required(String kind, String field) {
        return Descriptors.dialogueAction(kind).orElseThrow().fields().stream()
                .filter(f -> f.name().equals(field)).findFirst().orElseThrow().required();
    }

    private static boolean requiredCondition(String kind, String field) {
        return Descriptors.dialogueCondition(kind).orElseThrow().fields().stream()
                .filter(f -> f.name().equals(field)).findFirst().orElseThrow().required();
    }
}
