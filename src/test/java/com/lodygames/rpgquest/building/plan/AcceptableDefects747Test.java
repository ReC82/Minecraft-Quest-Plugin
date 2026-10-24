package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #747 (23/10/2026) — « Si on a un aperçu on devrait pouvoir le construire avec un avertissement. »
 *
 * <p>Rejoués sans appel payant, les deux plans réels du projet de test « TEST #747c église » : la
 * meilleure version V-8776ad09 (grille complète, un seul défaut : une lanterne sous la cloche) et la
 * dernière V-007bcdcc ({@code PLAN_INVALID}). La première s'enregistre après confirmation exacte de
 * son défaut, et reste marquée ; la seconde reste bloquée quoi qu'on confirme.</p>
 */
class AcceptableDefects747Test {

    private static final PlanRepair.Limits LIMITS = new PlanRepair.Limits(19, 19, 23);

    @TempDir
    Path tempDir;

    private Path buildings;
    private BuildingGenerator generator;

    @BeforeEach
    void setUp() {
        buildings = tempDir.resolve("buildings");
        BuildingLibrary library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();
        generator = new BuildingGenerator(library,
                new SchematicWorkshop(new BuildingGeneratorTest.FakeGateway(), Logger.getLogger("test")),
                buildings, Logger.getLogger("test"));
    }

    private static String plan(String version) {
        return VerticalCirculation282Test.fixture("eglise_747_" + version + ".yml");
    }

    @Test
    void bestVersionDeclaresItsSingleDefectAcceptableWithItsEffect() {
        BuildingGenerator.Verdict v = generator.inspect(plan("8776ad09"), LIMITS);
        assertEquals("ARCHITECTURE_REFUSED", v.code());
        assertEquals(List.of("UNSUPPORTED_BLOCK"), v.codes());
        var acceptable = AcceptableDefects.of(v);
        assertEquals(Set.of("UNSUPPORTED_BLOCK"), acceptable.keySet());
        assertTrue(acceptable.get("UNSUPPORTED_BLOCK").contains("tomber"), acceptable.toString());
    }

    @Test
    void withoutConfirmationTheBestVersionIsStillRefusedAndNothingIsWritten() throws Exception {
        BuildingGenerator.Result r = generator.generate(plan("8776ad09"), false);
        assertFalse(r.ok());
        assertEquals("ARCHITECTURE_REFUSED", r.code());
        assertFalse(Files.exists(buildings.resolve("test_eglise_747c.yml")));
    }

    @Test
    void confirmedDefectSavesTheExactVersionMarkedWithWarnings() throws Exception {
        BuildingGenerator.Result r = generator.generate(plan("8776ad09"), false, 0, Set.of("UNSUPPORTED_BLOCK"));
        assertTrue(r.ok(), r.message());
        assertEquals(AcceptableDefects.GENERATED_WITH_WARNINGS, r.code());
        assertTrue(r.message().startsWith("AVEC AVERTISSEMENTS ACCEPTÉS (UNSUPPORTED_BLOCK)"), r.message());
        assertTrue(r.effects().stream().anyMatch(e -> e.startsWith("⚠ défaut accepté UNSUPPORTED_BLOCK")),
                r.effects().toString());
        BuildingLibrary reread = new BuildingLibrary(buildings, Logger.getLogger("test"));
        reread.reload();
        String description = reread.find(r.buildingId()).orElseThrow().description();
        assertTrue(description.endsWith(AcceptableDefects.MARK + " : UNSUPPORTED_BLOCK."), description);
    }

    @Test
    void confirmationOfOtherDefectsWritesNothing() throws Exception {
        BuildingGenerator.Result r = generator.generate(plan("8776ad09"), false, 0, Set.of("DARK_AREA"));
        assertFalse(r.ok());
        assertEquals(AcceptableDefects.WARNINGS_CHANGED, r.code());
        r = generator.generate(plan("8776ad09"), false, 0, Set.of("DARK_AREA", "UNSUPPORTED_BLOCK"));
        assertEquals(AcceptableDefects.WARNINGS_CHANGED, r.code());
        try (var files = Files.list(buildings)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".yml")));
        } catch (java.nio.file.NoSuchFileException absent) {
            // dossier jamais créé : rien n'a été écrit, c'est ce qui est attendu
        }
    }

    @Test
    void invalidLatestVersionStaysBlockedWhateverIsConfirmed() {
        BuildingGenerator.Verdict v = generator.inspect(plan("007bcdcc"), LIMITS);
        assertTrue(v.codes().contains("PLAN_INVALID"), v.codes().toString());
        assertTrue(AcceptableDefects.of(v).isEmpty());
        BuildingGenerator.Result r = generator.generate(plan("007bcdcc"), false, 0,
                Set.of("UNSUPPORTED_BLOCK", "PLAN_INVALID"));
        assertFalse(r.ok());
        assertEquals("PLAN_INVALID", r.code());
    }

    @Test
    void impossibilitiesAreNeverAcceptable() {
        assertFalse(AcceptableDefects.allAcceptable(List.of("UNSUPPORTED_BLOCK", "BROKEN_ASSEMBLY")));
        assertFalse(AcceptableDefects.allAcceptable(List.of("EMPTY_STRUCTURE")));
        assertFalse(AcceptableDefects.allAcceptable(List.of()));
        assertEquals(AcceptableDefects.NOT_ACCEPTABLE, AcceptableDefects.refusal(
                List.of("BROKEN_ASSEMBLY"), Set.of("BROKEN_ASSEMBLY")).orElseThrow()[0]);
        assertTrue(AcceptableDefects.of("PLAN_INVALID", 500, List.of("UNSUPPORTED_BLOCK")).isEmpty());
        assertTrue(AcceptableDefects.of("ARCHITECTURE_REFUSED", 0, List.of("UNSUPPORTED_BLOCK")).isEmpty());
    }
}
