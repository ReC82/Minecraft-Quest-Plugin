package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #46 — un type d'objectif / récompense = une seule source de vérité ({@link Descriptors}). Ces
 * tests verrouillent la cohérence : les {@code kind} couvrent exactement le moteur, chaque champ
 * a un libellé + une aide, et aucun champ ne « déborde » d'un type sur un autre.
 */
class EditorDescriptorsTest {

    /** Types réels du moteur : {@code quest.model.ObjectiveType} / {@code quest.model.RewardType}. */
    private static final Set<String> ENGINE_OBJECTIVES = Set.of(
            "BREAK_BLOCK", "PLACE_BLOCK", "KILL_ENTITY", "COLLECT_ITEM", "CRAFT_ITEM",
            "TALK_TO_NPC", "REACH_LOCATION");
    private static final Set<String> ENGINE_REWARDS = Set.of("EXPERIENCE", "ITEM", "VARIABLE", "COMMAND");

    /** Noms de champ YAML réellement lus par {@code QuestDefinitionParser}. */
    private static final Set<String> ENGINE_FIELDS = Set.of(
            "entity", "material", "amount", "npc", "world", "x", "y", "z", "radius",
            "key", "value", "command");

    @Test
    void objectiveKindsMatchTheEngineExactly() {
        assertEquals(ENGINE_OBJECTIVES,
                Descriptors.OBJECTIVES.stream().map(Descriptors.Descriptor::kind).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void rewardKindsMatchTheEngineExactly() {
        assertEquals(ENGINE_REWARDS,
                Descriptors.REWARDS.stream().map(Descriptors.Descriptor::kind).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void everyDescriptorHasHumanLabelDescriptionAndFieldMetadata() {
        for (Descriptors.Descriptor d : concat()) {
            assertFalse(d.label() == null || d.label().isBlank(), d.kind() + " : libellé manquant");
            assertFalse(d.hint() == null || d.hint().isBlank(), d.kind() + " : description manquante");
            for (Descriptors.Field f : d.fields()) {
                assertFalse(f.label() == null || f.label().isBlank(), d.kind() + "/" + f.name() + " : label");
                assertFalse(f.help() == null || f.help().isBlank(), d.kind() + "/" + f.name() + " : aide");
                assertTrue(ENGINE_FIELDS.contains(f.name()),
                        d.kind() + " : champ « " + f.name() + " » inconnu du moteur");
            }
        }
    }

    @Test
    void killEntityUsesEntityAndCraftItemUsesMaterialNoCrossContamination() {
        assertEquals(List.of("entity", "amount"), fieldNames("KILL_ENTITY"));
        assertEquals(List.of("material", "amount"), fieldNames("CRAFT_ITEM"));
        assertEquals(List.of("material", "amount"), fieldNames("BREAK_BLOCK"));
        assertEquals(List.of("npc"), fieldNames("TALK_TO_NPC"));
        assertFalse(fieldNames("KILL_ENTITY").contains("material"));
        assertFalse(fieldNames("CRAFT_ITEM").contains("entity"));
    }

    @Test
    void rewardFieldsAreDistinctPerType() {
        assertEquals(List.of("amount"), fieldNames("EXPERIENCE"));
        assertEquals(List.of("material", "amount"), fieldNames("ITEM"));
        assertEquals(List.of("key", "value"), fieldNames("VARIABLE"));
        assertEquals(List.of("command"), fieldNames("COMMAND"));
        // le libellé de la quantité XP n'est pas « Montant » ambigu (#46, §19)
        assertEquals("Points d'expérience", field("EXPERIENCE", "amount").label());
    }

    @Test
    void fieldNamesHelperWhitelistsOnlyTheTypesOwnFields() {
        Set<String> allowed = Descriptors.fieldNames("KILL_ENTITY");
        assertTrue(allowed.contains("kind") && allowed.contains("entity") && allowed.contains("amount"));
        assertFalse(allowed.contains("material"));
        assertTrue(Descriptors.fieldNames("NOPE_UNKNOWN").equals(Set.of("kind")));
    }

    // ---- helpers ----

    private static List<Descriptors.Descriptor> concat() {
        return java.util.stream.Stream.concat(Descriptors.OBJECTIVES.stream(), Descriptors.REWARDS.stream()).toList();
    }

    private static List<String> fieldNames(String kind) {
        return Descriptors.any(kind).orElseThrow().fields().stream().map(Descriptors.Field::name).toList();
    }

    private static Descriptors.Field field(String kind, String name) {
        return Descriptors.any(kind).orElseThrow().fields().stream()
                .filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }
}
