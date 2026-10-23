package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BuildingVoxels;
import com.lodygames.rpgquest.panel.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #747, retour du 23/10/2026 (« le générateur doit produire un bâtiment utilisable ») — sans aucun appel
 * payant. Fixtures relues en LECTURE SEULE sur une copie de {@code control-panel.db} : le brief de
 * l'Église du hub, le plan V-2caffe63 (événement 91 du journal) et la grille compilée de l'aperçu de sa
 * réparation moteur V-fa3cf1de (même bâtiment sans les marches d'entrée).
 */
class Eglise747DesignTest {

    private static String resource(String name) {
        try (InputStream in = Eglise747DesignTest.class.getResourceAsStream("/ai/eglise747/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static BuildingVoxels realPreview() {
        Map<String, Object> result = Json.parseObject(resource("preview_fa3cf1de.json"));
        return BuildingVoxels.from((Map<String, Object>) result.get("details"));
    }

    private static final String BRIEF = resource("brief.txt");
    private static final String PLAN = resource("plan_2caffe63.yml");

    /** Une conception structurée telle que le prompt la demande, pour l'église du test. */
    private static final String DESIGN = """
            0. VOLUMES ET ÉLÉMENTS
            VOLUME nef | rôle: corps principal | x 3..13 | z 2..16 | y 0..8 | toit: deux pans axe z | relié à: clocher par arche
            VOLUME clocher | rôle: tour-clocher | x 6..10 | z 15..18 | y 0..15 | toit: pointu | relié à: nef par arche
            **ÉLÉMENT Cloche** | volume: clocher | position: x 8, y 13, z 16 | blocs: minecraft:bell
            ÉLÉMENT Bancs | volume: nef | position: x 4..7 et 9..12, z 5..12, y 1 | blocs: minecraft:spruce_stairs
            ÉLÉMENT Pupitre | volume: nef | position: x 11, y 2, z 3 | blocs: minecraft:lectern
            ÉLÉMENT Vitraux | volume: nef | position: façades LEFT et RIGHT | blocs: minecraft:blue_stained_glass_pane
            ÉLÉMENT Tapis rouge de l'allée | volume: nef | position: x 8, z 4..14 | blocs: minecraft:red_carpet
            ÉLÉMENT Lanternes | volume: nef | position: sous les poutres | blocs: minecraft:lantern
            ÉLÉMENT Bougies | volume: nef | position: autel | blocs: minecraft:white_candle
            ÉLÉMENT Compositions florales | volume: nef | position: x 5 et 11, z 3 | blocs: minecraft:potted_poppy
            ÉLÉMENT Autel en quartz | volume: nef | position: x 7..9, y 2, z 3 | blocs: minecraft:quartz_block
            ÉLÉMENT Estrade | volume: nef | position: x 4..12, y 1, z 2..4 | blocs: minecraft:smooth_quartz_slab
            ÉLÉMENT Allée centrale | volume: nef | position: x 8 | blocs: minecraft:red_carpet
            ÉLÉMENT Porche | volume: clocher | position: rez-de-chaussée | blocs: minecraft:dark_oak_door
            ÉLÉMENT Croix | volume: clocher | position: sommet | blocs: minecraft:quartz_pillar
            ÉLÉMENT Charpente apparente | volume: nef | position: y 9 | blocs: minecraft:dark_oak_log

            1. PROGRAMME ET ESPACES
            …
            """;

    // ---- La conception structurée ----------------------------------------------------------------

    @Test
    void theDesignPromptAsksForNamedLinkedVolumesAndAttachedElements() {
        BuildingPromptBuilder.Demand demand = new BuildingPromptBuilder.Demand("d770e76f", "Église du hub", "SOUTH",
                BRIEF, 19, 19, 23, "eglise_du_hub");
        String user = BuildingPromptBuilder.designUserPrompt(demand);

        assertTrue(user.contains("VOLUME <nom> | rôle:"), user);
        assertTrue(user.contains("ÉLÉMENT <exigence> | volume: <nom>"), user);
        assertTrue(user.contains("est un VOLUME À PART"), user);
        assertTrue(user.contains("N'ajoute AUCUN élément fonctionnel que la description ne demande pas"), user);
        // Essai réel du 23/10 (V-979a80e5) : sans repère, la tour d'entrée a été conçue au NORD (z=0).
        assertTrue(user.contains("SUD = z le plus grand (size.z - 1)"), user);
        assertTrue(user.contains("La façade d'entrée est donc la rangée z = size.z - 1 (côté sud, z le plus grand)"),
                user);
    }

    @Test
    void aStructuredDesignIsReadAndCheckedAgainstTheBrief() {
        ArchitecturalDesign design = ArchitecturalDesign.parse(DESIGN);

        assertEquals(2, design.volumes().size(), design.volumes().toString());
        assertEquals("clocher", design.volumes().get(1).name());
        assertEquals(15, design.volumes().get(1).y1());
        assertEquals("nef", design.main().orElseThrow().name());
        List<ArchitecturalDesign.Finding> gaps = design.gaps(BRIEF, 19, 19, 23);
        assertEquals(List.of(), gaps, "conception complète : aucune lacune");
        assertTrue(design.check(BRIEF, 19, 19, 23).stream().anyMatch(f -> f.ok()
                && f.text().startsWith("Tour distincte : « clocher »")));
    }

    @Test
    void aBellOnAColumnIsNotATowerInTheDesign() {
        String noTower = """
                VOLUME nef | rôle: corps principal | x 3..13 | z 0..18 | y 0..9 | toit: deux pans axe z
                ÉLÉMENT Cloche | volume: nef | position: colonne au-dessus du toit, x 8, y 17 | blocs: minecraft:bell
                """;
        List<ArchitecturalDesign.Finding> gaps = ArchitecturalDesign.parse(noTower).gaps(BRIEF, 19, 19, 23);

        assertTrue(gaps.stream().anyMatch(g -> g.text().startsWith("La description demande une tour")), gaps.toString());
        assertTrue(gaps.stream().anyMatch(g -> g.text().startsWith("Bancs / assises : aucune ligne")), gaps.toString());
    }

    @Test
    void aVolumeOutsideTheProjectLimitsIsAGap() {
        String tooTall = "VOLUME tour | rôle: tour | x 0..4 | z 0..4 | y 0..30 | toit: pointu\n";
        List<ArchitecturalDesign.Finding> gaps = ArchitecturalDesign.parse(tooTall).gaps("une tour", 19, 19, 23);

        assertTrue(gaps.stream().anyMatch(g -> g.text().contains("hors des limites du projet")), gaps.toString());
    }

    @Test
    void thePlanReceivesTheVolumeContract() {
        BuildingPromptBuilder.Demand demand = new BuildingPromptBuilder.Demand("d770e76f", "Église du hub", "SOUTH",
                BRIEF, 19, 19, 23, "eglise_du_hub", DESIGN);
        String user = BuildingPromptBuilder.userPrompt(demand);

        assertTrue(user.contains("CONTRAT DE VOLUMES"), user);
        assertTrue(user.contains("- clocher (tour-clocher) : x 6...10, z 15...18, y 0...15 ; toit : pointu"), user);
        assertTrue(user.contains("ÉLÉMENTS À PLACER"), user);
        assertFalse(user.contains("LACUNES DE LA CONCEPTION"), "aucune lacune à transmettre");
    }

    @Test
    void aDesignWithoutVolumesSendsNoContract() {
        BuildingPromptBuilder.Demand demand = new BuildingPromptBuilder.Demand("x", "Maison", "NORTH",
                "Une maison.", 9, 9, 9, "maison", "Texte libre sans volume.");

        assertFalse(BuildingPromptBuilder.userPrompt(demand).contains("CONTRAT DE VOLUMES"));
    }

    // ---- Les mesures sur le résultat compilé -------------------------------------------------------

    @Test
    void theRealPlanHasNoTowerItsBellIsNotInOneAndItsBedIsFlagged() {
        BuildingVoxels voxels = realPreview();
        assertTrue(voxels.present(), voxels.omittedReason());

        BriefRequirements.Report report = BriefRequirements.evaluate(BRIEF, PLAN, Map.of(), voxels);

        BriefRequirements.Status tower = status(report, "tower");
        assertEquals(Boolean.FALSE, tower.inResult(), tower.line());
        assertTrue(tower.missing() && tower.line().contains("ABSENTE du résultat compilé"), tower.line());
        BriefRequirements.Status bellInTower = status(report, "bell_tower");
        assertTrue(bellInTower.missing(), bellInTower.line());
        // La cloche elle-même est bien posée : « cloche présente » ne fait pas un clocher.
        assertFalse(status(report, "bell").missing(), status(report, "bell").line());
        // Le tapis est posé en travers (7 × 3 selon x) : ce n'est pas une allée vers le chœur.
        assertTrue(status(report, "aisle").missing(), status(report, "aisle").line());
        assertEquals(List.of("Lit(s)"), report.extras());
        assertTrue(report.promptBlock().contains("⚠ Lit(s) — AJOUT NON DEMANDÉ"), report.promptBlock());
        assertTrue(report.summary().contains("ajout(s) non demandé(s) : Lit(s)"), report.summary());
    }

    @Test
    void withoutAGridTheTowerIsNotMeasuredRatherThanPresumed() {
        BriefRequirements.Report report = BriefRequirements.evaluate(BRIEF, PLAN, Map.of());

        BriefRequirements.Status tower = status(report, "tower");
        assertTrue(tower.unverified() && !tower.missing(), tower.line());
        assertTrue(tower.line().startsWith("◐ Tour distincte du corps principal — non mesurée"), tower.line());
    }

    @Test
    void aRealTowerWithItsBellAndAnAisleAreMeasured() {
        BuildingVoxels voxels = church();
        String plan = "front: SOUTH\nsteps:\n  - op: box\n    block: minecraft:spruce_stairs\n";

        BriefRequirements.Report report = BriefRequirements.evaluate(BRIEF, plan, Map.of(), voxels, DESIGN);

        BriefRequirements.Status tower = status(report, "tower");
        assertEquals(Boolean.TRUE, tower.inResult(), tower.line());
        assertTrue(tower.line().contains("partie haute de 5 × 4 colonnes"), tower.line());
        assertEquals(Boolean.TRUE, status(report, "bell_tower").inResult(), status(report, "bell_tower").line());
        assertEquals(Boolean.TRUE, status(report, "aisle").inResult(), status(report, "aisle").line());
        List<BriefRequirements.Status> volumes = report.statuses().stream()
                .filter(s -> "volume".equals(s.requirement().id())).toList();
        assertEquals(2, volumes.size());
        assertTrue(volumes.stream().allMatch(s -> Boolean.TRUE.equals(s.inResult())), volumes.toString());
    }

    @Test
    void aDesignedVolumeThatWasNotBuiltIsMissing() {
        BuildingVoxels voxels = realPreview();

        BriefRequirements.Report report = BriefRequirements.evaluate(BRIEF, PLAN, Map.of(), voxels, DESIGN);

        BriefRequirements.Status clocher = report.statuses().stream()
                .filter(s -> "volume".equals(s.requirement().id()) && "clocher".equals(s.requirement().word()))
                .findFirst().orElseThrow();
        assertTrue(clocher.missing(), clocher.line());
        assertTrue(clocher.line().contains("Volume « clocher » de la conception — ABSENTE du résultat compilé"),
                clocher.line());
    }

    // ---- Les enseignements -----------------------------------------------------------------------

    @Test
    void lessonsNameTheFaultInsteadOfMaskedNumbers() {
        List<AttemptMemory.Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            attempts.add(new AttemptMemory.Attempt("v" + i + "000000", 1, false, List.of("DARK_AREA"),
                    List.of(new AttemptMemory.Issue("DARK_AREA", "8/1/18", AttemptMemory.cause(
                            "Zone non éclairée : la cellule 8/1/18 est accessible mais se trouve à 12 blocs"))), 0,
                    List.of()));
        }
        String lessons = AttemptMemory.of(attempts).lessonsBlock();

        assertTrue(lessons.contains("- DARK_AREA — Une partie de l'intérieur reste dans le noir. (3 versions ; dernier "
                + "endroit mesuré : 8/1/18)"), lessons);
        assertFalse(lessons.contains("#"), lessons);
    }

    private static BriefRequirements.Status status(BriefRequirements.Report report, String id) {
        return report.statuses().stream().filter(s -> id.equals(s.requirement().id())).findFirst()
                .orElseThrow(() -> new AssertionError("exigence absente : " + id + " dans " + report.statuses()));
    }

    /**
     * Une église minimale en grille : nef x 3..13, z 2..16, murs et toit plat jusqu'à y=8 ; clocher x 6..10, z 15..18
     * jusqu'à y=15, cloche en 8/13/16 ; tapis en file selon z, x=8, z 4..14, y=1.
     */
    private static BuildingVoxels church() {
        int sx = 17;
        int sy = 17;
        int sz = 19;
        char[][][] g = new char[sy][sz][sx];
        for (char[][] layer : g) {
            for (char[] row : layer) {
                java.util.Arrays.fill(row, BuildingVoxels.EMPTY);
            }
        }
        box(g, 3, 13, 0, 8, 2, 16, 'W', true);
        box(g, 6, 10, 0, 15, 15, 18, 'W', true);
        box(g, 3, 13, 8, 8, 2, 16, 'W', false);
        for (int z = 4; z <= 14; z++) {
            g[1][z][8] = 'C';
        }
        g[14][16][8] = 'W';
        g[13][16][8] = 'B';
        List<List<String>> layers = new ArrayList<>();
        for (char[][] layer : g) {
            List<String> rows = new ArrayList<>();
            for (char[] row : layer) {
                rows.add(new String(row));
            }
            layers.add(rows);
        }
        Map<Character, BuildingVoxels.Entry> palette = Map.of(
                'W', new BuildingVoxels.Entry('W', "white_concrete", '#', "full"),
                'C', new BuildingVoxels.Entry('C', "red_carpet", 'f', "full"),
                'B', new BuildingVoxels.Entry('B', "bell", 'f', "full"));
        return new BuildingVoxels(sx, sy, sz, palette, layers, "");
    }

    private static void box(char[][][] g, int x0, int x1, int y0, int y1, int z0, int z1, char c, boolean walls) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    boolean border = x == x0 || x == x1 || z == z0 || z == z1 || y == y0;
                    if (!walls || border) {
                        g[y][z][x] = c;
                    }
                }
            }
        }
    }
}
