package com.lodygames.rpgquest.content.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #108 : granularités d'export (élément / sélection / famille / tout), manifest et
 * déterminisme. Pur JUnit — l'assembleur ne dépend pas de Bukkit.
 */
class ContentPackAssemblerTest {

    private static final Instant AT = Instant.parse("2026-09-09T21:40:00Z");

    private QuestPackEntry q(String id) {
        return new QuestPackEntry(id, PackText.of(id), PackText.of("d"), "cat", "BOOK", false, false, null,
                List.of(), List.of(new QuestPackEntry.Step("s", List.of(QuestPackEntry.Objective.talk("guide")))),
                List.of(), Map.of());
    }

    private StoryPackEntry s(String id) {
        return new StoryPackEntry(id, PackText.of(id), false, List.of("rpgquest:x"));
    }

    private NpcPackEntry n(String id) {
        return new NpcPackEntry(id, id, null, null, null, true);
    }

    private ContentPackAssembler assembler() {
        return new ContentPackAssembler(
                () -> List.of(q("rpgquest:beta"), q("rpgquest:alpha")),
                () -> List.of(s("main"), s("side")),
                () -> List.of(),
                () -> List.of(n("guide"), n("jo")),
                () -> "9.9.9");
    }

    @Test
    void exportAllIncludesEveryFamilyAndCounts() {
        ContentPack pack = assembler().exportAll(AT);
        assertEquals(ContentPack.FORMAT, pack.format());
        assertEquals(1, pack.schemaVersion());
        assertEquals("9.9.9", pack.metadata().pluginVersion());
        assertEquals("2026-09-09T21:40:00Z", pack.metadata().exportedAt());
        assertEquals(List.of("quests", "stories", "dialogues", "npcs"), pack.metadata().families());
        assertEquals(Map.of("quests", 2, "stories", 2, "dialogues", 0, "npcs", 2), pack.metadata().counts());
        assertEquals(6, pack.totalElements());
        assertEquals("rpgquest:alpha", pack.quests().get(0).id(), "trié par id");
    }

    @Test
    void exportFamilyLeavesOtherSectionsEmpty() {
        ContentPack pack = assembler().exportFamily(ContentFamily.STORIES, AT);
        assertEquals(List.of("stories"), pack.metadata().families());
        assertTrue(pack.quests().isEmpty());
        assertTrue(pack.npcs().isEmpty());
        assertEquals(2, pack.stories().size());
        assertEquals(Map.of("quests", 0, "stories", 2, "dialogues", 0, "npcs", 0), pack.metadata().counts());
    }

    @Test
    void exportElementFiltersToASingleId() {
        ContentPack pack = assembler().exportElement(ContentFamily.QUESTS, "rpgquest:alpha", AT);
        assertEquals(1, pack.quests().size());
        assertEquals("rpgquest:alpha", pack.quests().get(0).id());
        assertEquals(Map.of("quests", 1, "stories", 0, "dialogues", 0, "npcs", 0), pack.metadata().counts());
    }

    @Test
    void exportSelectionKeepsOnlyRequestedIds() {
        ContentPack pack = assembler().exportSelection(ContentFamily.NPCS, Set.of("jo", "absent"), AT);
        assertEquals(1, pack.npcs().size());
        assertEquals("jo", pack.npcs().get(0).id());
    }

    @Test
    void unknownIdsProduceAnEmptyFamilyWithoutError() {
        ContentPack pack = assembler().exportElement(ContentFamily.QUESTS, "rpgquest:nope", AT);
        assertTrue(pack.quests().isEmpty());
    }

    @Test
    void serializationIsDeterministicForAFixedExportInstant() {
        assertEquals(
                ContentPackSerializer.toYaml(assembler().exportAll(AT)),
                ContentPackSerializer.toYaml(assembler().exportAll(AT)));
    }

    @Test
    void pluginVersionSupplierFailureDegradesToUnknown() {
        ContentPackAssembler a = new ContentPackAssembler(List::of, List::of, List::of, List::of,
                () -> {
                    throw new IllegalStateException("boom");
                });
        assertEquals("unknown", a.exportAll(AT).metadata().pluginVersion());
    }
}
