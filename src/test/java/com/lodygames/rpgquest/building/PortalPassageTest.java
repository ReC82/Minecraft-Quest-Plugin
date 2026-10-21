package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.PortalPassage;
import com.lodygames.rpgquest.building.plan.StructureKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #568 : type « portail » et zone de passage conservés dans la bibliothèque, relus après rotation. */
class PortalPassageTest {

    /** Arche 7 × 6 × 3, ancre décentrée en (2, 1, 0) : le cas où une rotation naïve se trompe. */
    private static final PortalPassage PASSAGE = new PortalPassage(1, 1, 0, 3, 4, 2);
    private static final BuildingDefinition ARCH = new BuildingDefinition("arche", "Arche", "", "arche.schem",
            7, 6, 3, 2, 1, 0, Facing.NORTH, List.of(), 1, StructureKind.PORTAL, PASSAGE);

    @Test
    void thePassageStaysInsideTheFootprintAndKeepsItsSizeAtEveryRotation() {
        for (int degrees = 0; degrees < 360; degrees += 90) {
            BuildingFootprint building = ARCH.footprintAt("w", 100, 64, 200, degrees);
            BuildingFootprint passage = PASSAGE.inWorld(ARCH, "w", 100, 64, 200, degrees);
            assertTrue(building.covers(passage), "rotation " + degrees);
            boolean swapped = degrees % 180 != 0;
            assertEquals(swapped ? 3 : 3, swapped ? passage.sizeZ() : passage.sizeX());
            assertEquals(4, passage.sizeY());
            assertEquals(64, passage.minY(), "le passage commence au niveau de l'ancre");
            assertTrue(passage.contains("w", 100, 64, 200), "l'ancre est dans le passage, rotation " + degrees);
        }
    }

    @Test
    void ninetyDegreesTurnsTheLocalXAxisToTheEast() {
        // Local : x 1..3 (dx -1..1), z 0..2 (dz 0..2). À 90° : (dx, dz) → (-dz, dx).
        BuildingFootprint p = PASSAGE.inWorld(ARCH, "w", 100, 64, 200, 90);
        assertEquals(new BuildingFootprint("w", 98, 64, 199, 100, 67, 201), p);
    }

    @Test
    void onlyAPortalMayDeclareAPassageAndItMustFitTheSchematic() {
        assertTrue(ARCH.valid(), ARCH.validate().orElse(""));
        assertTrue(ARCH.portal());
        BuildingDefinition house = new BuildingDefinition("maison", "Maison", "", "maison.schem", 7, 6, 3, 2, 1, 0,
                Facing.NORTH, List.of(), 1, StructureKind.HABITABLE, PASSAGE);
        assertFalse(house.valid());
        BuildingDefinition outside = new BuildingDefinition("arche", "Arche", "", "arche.schem", 7, 6, 3, 2, 1, 0,
                Facing.NORTH, List.of(), 1, StructureKind.PORTAL, new PortalPassage(1, 1, 0, 3, 6, 2));
        assertTrue(outside.validate().orElse("").contains("hors du bâtiment"));
        BuildingDefinition legacy = new BuildingDefinition("hutte", "Hutte", "", "hutte.schem", 7, 6, 3, 2, 1, 0,
                Facing.NORTH, List.of(), 1);
        assertEquals(StructureKind.HABITABLE, legacy.kind());
    }

    @Test
    void kindAndPassageSurviveAYamlRoundTripAndAnOldFileIsUnchanged() throws Exception {
        YamlConfiguration yaml = base();
        BuildingDefinitionYaml.writeKind(yaml, ARCH);
        List<String> problems = new ArrayList<>();
        Optional<BuildingDefinition> read = BuildingDefinitionYaml.read(
                reparse(yaml), problems);
        assertTrue(read.isPresent(), problems.toString());
        assertEquals(StructureKind.PORTAL, read.get().kind());
        assertEquals(PASSAGE, read.get().passage());

        YamlConfiguration old = base();
        String before = old.saveToString();
        BuildingDefinitionYaml.writeKind(old, new BuildingDefinition("arche", "Arche", "", "arche.schem", 7, 6, 3, 2,
                1, 0, Facing.NORTH, List.of(), 1));
        assertEquals(before, old.saveToString(), "un bâtiment habitable sans passage : fichier identique");
    }

    @Test
    void anUnknownKindOrAnIncompletePassageIsRefusedWithAReadableReason() throws Exception {
        YamlConfiguration yaml = base();
        yaml.set("kind", "fontaine");
        List<String> problems = new ArrayList<>();
        assertTrue(BuildingDefinitionYaml.read(yaml, problems).isEmpty());
        assertTrue(problems.get(0).contains("Type inconnu"));

        yaml = base();
        yaml.set("kind", "portal");
        yaml.set("passage.min.x", 1);
        problems.clear();
        assertTrue(BuildingDefinitionYaml.read(yaml, problems).isEmpty());
        assertTrue(problems.get(0).contains("incomplète"));
    }

    private static YamlConfiguration base() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", "arche");
        yaml.set("name", "Arche");
        yaml.set("schematic", "arche.schem");
        yaml.set("size.x", 7);
        yaml.set("size.y", 6);
        yaml.set("size.z", 3);
        yaml.set("anchor.x", 2);
        yaml.set("anchor.y", 1);
        yaml.set("anchor.z", 0);
        yaml.set("front", "NORTH");
        return yaml;
    }

    private static YamlConfiguration reparse(YamlConfiguration yaml) throws Exception {
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(yaml.saveToString());
        return copy;
    }
}
