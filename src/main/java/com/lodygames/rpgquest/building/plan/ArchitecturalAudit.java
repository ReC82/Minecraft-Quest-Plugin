package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Audit architectural d'un bâtiment, sur la <strong>géométrie réellement expansée</strong>
 * (issue #94, lot A).
 *
 * <h2>Ce que cet audit corrige</h2>
 *
 * <p>La V1 de la Bibliothèque du Hub est passée en succès avec le message « prêt à être posé »
 * alors qu'elle était hermétiquement scellée — 532 cellules de mur, zéro ouverture —, que son étage
 * était inaccessible et que ses pignons étaient ouverts à 62 %. Le générateur validait la
 * géométrie (zone dans le volume, pas de doublon) et <em>rien</em> de sémantique.</p>
 *
 * <h2>Sur la grille, jamais sur les intentions</h2>
 *
 * <p>Un plan peut écrire une instruction nommée « porte » : seule la grille dit s'il y a un trou
 * dans le mur. La V1 l'a prouvé — son percement visait {@code x=5} alors que le mur était en
 * {@code x=0}, et il a vidé de l'air intérieur déjà vide. Cet audit ne lit donc <strong>aucune
 * instruction</strong> : il reçoit le {@link Blueprint} expansé et mesure.</p>
 *
 * <h2>Par franchissabilité, pas par nom de bloc</h2>
 *
 * <p>Le contrôle d'entrée ne cherche pas un bloc « porte ». Il cherche un <strong>passage</strong>
 * : deux cellules franchissables superposées qui traversent la façade. Un percement brut et une
 * vraie porte du lot B satisfont donc le même contrôle, sans qu'une ligne d'ici change — parce que
 * c'est {@link BlockSemantics} qui porte le sens, et non ce fichier.</p>
 */
public final class ArchitecturalAudit {

    private ArchitecturalAudit() {
    }

    /** Le verdict complet. */
    public record Report(List<ArchitecturalFinding> findings, Metrics metrics) {

        public Report {
            findings = List.copyOf(findings == null ? List.of() : findings);
        }

        /** Y a-t-il au moins un constat bloquant ? */
        public boolean refused() {
            return findings.stream().anyMatch(ArchitecturalFinding::blocking);
        }

        public List<ArchitecturalFinding> blocking() {
            return findings.stream().filter(ArchitecturalFinding::blocking).toList();
        }

        public List<ArchitecturalFinding> warnings() {
            return findings.stream().filter(f -> !f.blocking()).toList();
        }

        /** Les codes bloquants, pour les tests et l'écran. */
        public List<String> blockingCodes() {
            return blocking().stream().map(ArchitecturalFinding::code).toList();
        }

        /** Le refus, rédigé pour être renvoyé tel quel au modèle par la boucle de correction. */
        public String refusalMessage() {
            if (!refused()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Le bâtiment produit par ce plan ne respecte pas "
                    + "le contrat architectural. Corrige PRÉCISÉMENT les points suivants :");
            int n = 1;
            for (ArchitecturalFinding f : blocking()) {
                sb.append("\n  ").append(n++).append(". ").append(f.message());
            }
            return sb.toString();
        }
    }

    /** Les mesures brutes, utiles pour l'écran, les tests et les futures coupes du lot E. */
    public record Metrics(int solidCells, int glazedCells, int glazedFacades, int lightSources,
                          int openEnvelopeCellsHigh, int floors, int reachableFloors,
                          int furnishingCells, int envelopeMaterials, double topMaterialShare,
                          String topMaterial) {
    }

    /**
     * Auditer.
     *
     * @param blueprint la grille expansée — la seule source de vérité de cet audit
     * @param front     orientation de référence déclarée par le plan, qui désigne la façade d'entrée
     * @param rules     le profil de seuils, dépendant du type de structure
     */
    public static Report audit(Blueprint blueprint, Facing front, ArchitecturalRules rules) {
        List<ArchitecturalFinding> findings = new ArrayList<>();
        if (blueprint == null || blueprint.blocks().isEmpty()) {
            findings.add(ArchitecturalFinding.blocking("EMPTY_STRUCTURE",
                    "La structure ne contient aucun bloc : il n'y a rien à auditer."));
            return new Report(findings, new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""));
        }
        ArchitecturalRules r = rules == null ? ArchitecturalRules.habitable() : rules;
        Grid grid = Grid.of(blueprint);

        int glazed = 0;
        Set<String> glazedFaces = new HashSet<>();
        int lights = 0;
        int furnishing = 0;
        for (BlueprintBlock b : blueprint.blocks()) {
            if (BlockSemantics.glazing(b.state())) {
                glazed++;
                String face = grid.faceOf(b.x(), b.z());
                if (face != null) {
                    glazedFaces.add(face);
                }
            }
            if (BlockSemantics.lightSource(b.state())) {
                lights++;
            }
            if (BlockSemantics.furnishing(b.state())) {
                furnishing++;
            }
        }

        Entrance entrance = findEntrance(grid, front, r);
        int openHigh = openEnvelopeCellsAbove(grid, r.openingMaxHeight());
        Levels levels = analyseLevels(grid);
        Envelope envelope = envelopeMaterials(grid);

        // ---- Entrée --------------------------------------------------------------------------
        if (r.requireEntrance() && !entrance.found()) {
            findings.add(ArchitecturalFinding.blocking("NO_ENTRANCE",
                    "Aucune entrée : il n'existe aucun passage de 2 blocs de haut traversant la "
                            + "façade " + faceName(front) + " (" + grid.facadeDescription(front)
                            + "). Dans la bande de hauteur où une entrée peut se trouver, "
                            + entrance.facadeOpenCells() + " cellule(s) sur "
                            + entrance.facadeCells() + " sont franchissables. "
                            + "Perce une ouverture de 2 blocs de haut au niveau du sol (y="
                            + (grid.minY() + 1) + " et y=" + (grid.minY() + 2)
                            + ") DANS LE PLAN DE LA FAÇADE, et vérifie que la cellule située juste "
                            + "derrière est également libre."));
        }
        if (r.requirePassage() && !entrance.anyPassage()) {
            findings.add(ArchitecturalFinding.blocking("NO_PASSAGE",
                    "Aucun passage traversant : cette structure doit pouvoir être franchie."));
        }

        // ---- Vitrages ------------------------------------------------------------------------
        if (r.minGlazedCells() > 0 && glazed < r.minGlazedCells()) {
            findings.add(ArchitecturalFinding.blocking("GLAZING_MISSING",
                    "Vitrages insuffisants : " + glazed + " cellule(s) vitrée(s) trouvée(s), "
                            + r.minGlazedCells() + " au minimum. Pose du verre (par exemple "
                            + "minecraft:glass ou minecraft:glass_pane) DANS les murs, à la place "
                            + "de blocs pleins — un simple trou n'est pas une fenêtre."));
        } else if (r.minGlazedFacades() > 0 && glazedFaces.size() < r.minGlazedFacades()) {
            findings.add(ArchitecturalFinding.blocking("GLAZING_ONE_SIDED",
                    "Vitrages présents mais regroupés : " + glazed + " cellule(s) sur seulement "
                            + glazedFaces.size() + " façade(s) (" + String.join(", ", glazedFaces)
                            + "), alors qu'il en faut sur " + r.minGlazedFacades()
                            + ". Répartis les fenêtres sur plusieurs façades."));
        }

        // ---- Éclairage -----------------------------------------------------------------------
        if (r.requireLight()) {
            if (lights == 0) {
                findings.add(ArchitecturalFinding.blocking("NO_LIGHT",
                        "Aucune source de lumière : l'intérieur serait entièrement sombre. Ajoute "
                                + "des lanternes (minecraft:lantern), des torches "
                                + "(minecraft:torch) ou de la pierre lumineuse à l'intérieur. Les "
                                + "bougies ne comptent pas : leur niveau de lumière est trop faible."));
            } else {
                Dark dark = findDarkCell(grid, levels, r.maxLightDistance());
                if (dark.found()) {
                    findings.add(ArchitecturalFinding.blocking("DARK_AREA",
                            "Zone non éclairée : la cellule " + dark.x() + "/" + dark.y() + "/"
                                    + dark.z() + " est accessible mais se trouve à " + dark.distance()
                                    + " blocs de la source de lumière la plus proche, alors que la "
                                    + "distance maximale est de " + r.maxLightDistance()
                                    + ". Ajoute une source près de ce point."));
                }
            }
        }

        // ---- Enveloppe : couverture et pignons -----------------------------------------------
        int uncovered = r.requireClosedEnvelope() ? uncoveredColumns(grid, levels) : 0;
        if (r.requireClosedEnvelope() && uncovered > r.envelopeOpeningTolerance()) {
            findings.add(ArchitecturalFinding.blocking("ROOF_MISSING",
                    "Couverture incomplète : " + uncovered + " colonne(s) de l'emprise n'ont aucun "
                            + "bloc au-dessus du dernier niveau praticable — il y pleut. Pose une "
                            + "couverture sur toute l'emprise."));
        }
        if (r.requireClosedEnvelope() && openHigh > r.envelopeOpeningTolerance()) {
            findings.add(ArchitecturalFinding.blocking("ENVELOPE_OPEN",
                    "Enveloppe ouverte en hauteur : " + openHigh + " cellule(s) franchissable(s) "
                            + "sur la surface extérieure au-dessus de y="
                            + (grid.minY() + r.openingMaxHeight()) + ", alors que "
                            + r.envelopeOpeningTolerance() + " au plus sont tolérées. C'est le "
                            + "symptôme d'un toit dont les PIGNONS ne sont pas remplis : un toit en "
                            + "pente couvre par le dessus, mais il faut aussi fermer les deux "
                            + "triangles verticaux à ses extrémités avec des blocs pleins."));
        }

        // ---- Niveaux -------------------------------------------------------------------------
        if (r.requireLevelAccess() && levels.floors() > 1 && levels.reachable() < levels.floors()) {
            findings.add(ArchitecturalFinding.blocking("LEVEL_UNREACHABLE",
                    "Niveau inaccessible : la structure compte " + levels.floors()
                            + " niveau(x) praticable(s) (sols en y=" + levels.floorLabel()
                            + ") mais seulement " + levels.reachable()
                            + " est/sont atteignable(s) depuis le rez-de-chaussée. Ajoute un "
                            + "escalier ou une échelle, ET perce la trémie DANS le plancher "
                            + "lui-même — vider l'air au-dessus du plancher ne le troue pas."));
        }

        // ---- Avertissements ------------------------------------------------------------------
        if (r.minEnvelopeMaterials() > 0 && envelope.materials() < r.minEnvelopeMaterials()) {
            findings.add(ArchitecturalFinding.warning("ENVELOPE_MONOTONOUS",
                    "Façades peu travaillées : " + envelope.materials()
                            + " matériau(x) dans l'enveloppe, " + r.minEnvelopeMaterials()
                            + " attendus. Ajoute des pilastres, un soubassement ou un bandeau "
                            + "dans un second matériau."));
        }
        if (r.maxMaterialShare() > 0 && envelope.topShare() > r.maxMaterialShare()) {
            findings.add(ArchitecturalFinding.warning("MATERIAL_DOMINANT",
                    "Un seul matériau domine l'enveloppe : « " + envelope.topMaterial() + " » à "
                            + Math.round(envelope.topShare() * 100) + " %, au-delà des "
                            + Math.round(r.maxMaterialShare() * 100) + " % attendus."));
        }
        if (r.minFurnishingCells() > 0 && furnishing < r.minFurnishingCells()) {
            findings.add(ArchitecturalFinding.warning("FURNISHING_THIN",
                    "Intérieur peu aménagé : " + furnishing + " bloc(s) de mobilier. "
                            + "L'aménagement doit correspondre au brief — ce n'est qu'un "
                            + "avertissement, la géométrie seule ne permet pas d'en juger."));
        }

        return new Report(findings, new Metrics(blueprint.blocks().size(), glazed,
                glazedFaces.size(), lights, openHigh, levels.floors(), levels.reachable(),
                furnishing, envelope.materials(), envelope.topShare(), envelope.topMaterial()));
    }

    // ---- La grille ------------------------------------------------------------------------------

    /**
     * La grille expansée, plus les bornes réelles de la matière.
     *
     * <p>Les bornes viennent des blocs posés, pas des dimensions déclarées : un plan peut annoncer
     * 19 × 19 et ne remplir que 17 × 17, et c'est la surface réelle qui porte les façades.</p>
     */
    private record Grid(Map<Long, String> cells, int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ) {

        static Grid of(Blueprint blueprint) {
            Map<Long, String> cells = new HashMap<>(blueprint.blocks().size() * 2);
            int nx = Integer.MAX_VALUE;
            int ny = Integer.MAX_VALUE;
            int nz = Integer.MAX_VALUE;
            int xx = Integer.MIN_VALUE;
            int xy = Integer.MIN_VALUE;
            int xz = Integer.MIN_VALUE;
            for (BlueprintBlock b : blueprint.blocks()) {
                cells.put(key(b.x(), b.y(), b.z()), b.state());
                nx = Math.min(nx, b.x());
                ny = Math.min(ny, b.y());
                nz = Math.min(nz, b.z());
                xx = Math.max(xx, b.x());
                xy = Math.max(xy, b.y());
                xz = Math.max(xz, b.z());
            }
            return new Grid(cells, nx, ny, nz, xx, xy, xz);
        }

        String at(int x, int y, int z) {
            return cells.get(key(x, y, z));
        }

        boolean passable(int x, int y, int z) {
            return BlockSemantics.passable(at(x, y, z));
        }

        boolean inside(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** La façade à laquelle appartient cette colonne, ou {@code null} si elle est intérieure. */
        String faceOf(int x, int z) {
            if (x == minX) {
                return "OUEST";
            }
            if (x == maxX) {
                return "EST";
            }
            if (z == minZ) {
                return "NORD";
            }
            if (z == maxZ) {
                return "SUD";
            }
            return null;
        }

        String facadeDescription(Facing front) {
            return switch (front == null ? Facing.NORTH : front) {
                case NORTH -> "z=" + minZ;
                case SOUTH -> "z=" + maxZ;
                case WEST -> "x=" + minX;
                case EAST -> "x=" + maxX;
            };
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    // ---- Entrée ---------------------------------------------------------------------------------

    private record Entrance(boolean found, boolean anyPassage, int facadeOpenCells,
                            int facadeCells) {
    }

    /**
     * Cherche un passage de deux blocs de haut traversant la façade demandée, près du sol.
     *
     * <p>« Traversant » est vérifié : la cellule de façade et celle immédiatement en retrait
     * doivent toutes deux être franchissables, sur les deux hauteurs. C'est exactement ce qui
     * manquait à la V1 — son percement était cinq blocs en retrait du mur, donc dans de l'air déjà
     * vide, et ne traversait rien.</p>
     */
    private static Entrance findEntrance(Grid grid, Facing front, ArchitecturalRules rules) {
        Facing f = front == null ? Facing.NORTH : front;
        boolean onX = f == Facing.WEST || f == Facing.EAST;
        int plane = switch (f) {
            case WEST -> grid.minX();
            case EAST -> grid.maxX();
            case NORTH -> grid.minZ();
            case SOUTH -> grid.maxZ();
        };
        int inward = switch (f) {
            case WEST, NORTH -> 1;
            case EAST, SOUTH -> -1;
        };
        int fromGround = grid.minY() + 1;
        int maxY = Math.min(grid.maxY() - 1, grid.minY() + Math.max(2, rules.openingMaxHeight()));

        int open = 0;
        int total = 0;
        boolean found = false;
        boolean anyPassage = false;
        int alongMin = onX ? grid.minZ() : grid.minX();
        int alongMax = onX ? grid.maxZ() : grid.maxX();

        for (int along = alongMin; along <= alongMax; along++) {
            for (int y = grid.minY(); y <= grid.maxY(); y++) {
                int x = onX ? plane : along;
                int z = onX ? along : plane;
                boolean p = grid.passable(x, y, z);
                // On ne compte que la BANDE où une entrée peut se trouver. Compter toute la
                // hauteur mélangerait les cellules ouvertes d'un pignon au décompte, et le message
                // laisserait croire au modèle qu'il existe déjà une ouverture de façade.
                if (y >= fromGround && y <= maxY) {
                    total++;
                    if (p) {
                        open++;
                    }
                }
                // Un passage traversant, à n'importe quelle hauteur : sert au contrôle « portail ».
                if (p && grid.passable(onX ? plane + inward : x, y, onX ? z : plane + inward)) {
                    anyPassage = true;
                }
            }
            // Une entrée : deux cellules superposées, franchissantes, et près du sol.
            for (int y = fromGround; y <= maxY; y++) {
                int x = onX ? plane : along;
                int z = onX ? along : plane;
                int ix = onX ? plane + inward : x;
                int iz = onX ? z : plane + inward;
                if (grid.passable(x, y, z) && grid.passable(x, y + 1, z)
                        && grid.inside(ix, y, iz)
                        && grid.passable(ix, y, iz) && grid.passable(ix, y + 1, iz)) {
                    found = true;
                }
            }
        }
        return new Entrance(found, anyPassage, open, total);
    }

    // ---- Enveloppe ------------------------------------------------------------------------------

    /**
     * Cellules franchissables sur la surface extérieure, au-dessus d'une hauteur donnée.
     *
     * <p>C'est la signature d'un pignon non rempli : le toit en pente couvre par le dessus — la V1
     * n'avait aucune colonne à ciel ouvert — mais les deux triangles verticaux à ses extrémités
     * restaient béants, 59 cellules vides sur 95 par face.</p>
     *
     * <p>La hauteur de seuil écarte volontairement les ouvertures de rez-de-chaussée : une porte et
     * des fenêtres basses ne doivent pas être comptées comme des trous dans la couverture.</p>
     */
    private static int openEnvelopeCellsAbove(Grid grid, int openingMaxHeight) {
        int from = grid.minY() + openingMaxHeight;
        int open = 0;
        for (int y = Math.max(grid.minY(), from); y <= grid.maxY(); y++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                    boolean sideFace = x == grid.minX() || x == grid.maxX()
                            || z == grid.minZ() || z == grid.maxZ();
                    if (!sideFace) {
                        continue;
                    }
                    String state = grid.at(x, y, z);
                    // Un vitrage ferme l'enveloppe : une fenêtre n'est pas un trou.
                    if (BlockSemantics.glazing(state) || !BlockSemantics.passable(state)) {
                        continue;
                    }
                    // LE critère. Une cellule de face latérale vide n'est un trou que s'il y a de
                    // la matière AU-DESSUS d'elle dans sa colonne : elle est alors sous la
                    // couverture, donc elle aurait dû être murée.
                    //
                    // Sans cette condition, un toit à deux pans correct serait signalé à tort :
                    // au-dessus de ses avant-toits, les faces latérales sont du ciel ouvert, pas
                    // une fuite. C'est une faiblesse du lot A, corrigée ici.
                    if (solidAbove(grid, x, y, z)) {
                        open++;
                    }
                }
            }
        }
        return open;
    }

    /** Y a-t-il de la matière plus haut dans cette colonne ? */
    private static boolean solidAbove(Grid grid, int x, int y, int z) {
        for (int above = y + 1; above <= grid.maxY(); above++) {
            if (!BlockSemantics.passable(grid.at(x, above, z))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Colonnes de l'emprise qui n'ont aucune couverture au-dessus du dernier niveau praticable.
     *
     * <p>Contrôle distinct des faces latérales, et c'est nécessaire : un bâtiment sans toit du tout
     * n'a aucune face latérale « sous couverture », donc le contrôle précédent ne le verrait pas.
     * Ici on vérifie qu'il pleut nulle part.</p>
     */
    private static int uncoveredColumns(Grid grid, Levels levels) {
        if (levels.standingLevels().isEmpty()) {
            return 0;
        }
        int top = levels.standingLevels().get(levels.standingLevels().size() - 1);
        int bare = 0;
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                if (!coveredColumnExists(grid, x, z)) {
                    continue;
                }
                boolean covered = false;
                for (int y = top; y <= grid.maxY() && !covered; y++) {
                    if (!BlockSemantics.passable(grid.at(x, y, z))) {
                        covered = true;
                    }
                }
                if (!covered) {
                    bare++;
                }
            }
        }
        return bare;
    }

    /**
     * Cette colonne fait-elle partie du bâtiment ?
     *
     * <p>Sans ce filtre, un plan dont la matière ne remplit pas tout son rectangle verrait ses
     * coins vides comptés comme des trous d'enveloppe. On ne compte donc une cellule de surface que
     * si sa colonne porte réellement de la matière quelque part.</p>
     */
    private static boolean coveredColumnExists(Grid grid, int x, int z) {
        for (int y = grid.minY(); y <= grid.maxY(); y++) {
            if (!grid.passable(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private record Envelope(int materials, double topShare, String topMaterial) {
    }

    private static Envelope envelopeMaterials(Grid grid) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (int y = grid.minY(); y <= grid.maxY(); y++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                    boolean surface = x == grid.minX() || x == grid.maxX()
                            || z == grid.minZ() || z == grid.maxZ()
                            || y == grid.minY() || y == grid.maxY();
                    if (!surface) {
                        continue;
                    }
                    String state = grid.at(x, y, z);
                    if (state == null) {
                        continue;
                    }
                    counts.merge(BlockState.type(state), 1, Integer::sum);
                    total++;
                }
            }
        }
        if (total == 0) {
            return new Envelope(0, 0, "");
        }
        String top = "";
        int best = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                top = e.getKey();
            }
        }
        return new Envelope(counts.size(), (double) best / total, top);
    }

    // ---- Niveaux et circulation -----------------------------------------------------------------

    private record Levels(int floors, int reachable, List<Integer> standingLevels,
                          Set<Long> walkable) {

        String floorLabel() {
            return standingLevels.isEmpty() ? "aucun"
                    : standingLevels.stream().map(String::valueOf)
                            .reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    /**
     * Repère les niveaux praticables, puis vérifie lesquels sont atteignables depuis le plus bas.
     *
     * <p>Le départ est le <strong>rez-de-chaussée</strong>, et non l'entrée : cela sépare deux
     * défauts qui n'ont pas la même cause. Un bâtiment sans porte doit échouer sur l'entrée, et un
     * bâtiment dont l'étage n'a pas de trémie doit échouer sur les niveaux — même s'il a une porte.
     * La V1 cumulait les deux, et les deux doivent être dits.</p>
     *
     * <p>La circulation admise est celle d'un joueur : avancer à l'horizontale, monter d'un bloc
     * (marche ou escalier), ou grimper sur une cellule grimpable. Deux blocs de hauteur libre sont
     * exigés, parce qu'un joueur ne rampe pas.</p>
     */
    private static Levels analyseLevels(Grid grid) {
        int footprint = (grid.maxX() - grid.minX() + 1) * (grid.maxZ() - grid.minZ() + 1);
        List<Integer> standing = new ArrayList<>();
        for (int y = grid.minY(); y < grid.maxY(); y++) {
            int supported = 0;
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                    if (BlockSemantics.support(grid.at(x, y, z))
                            && grid.passable(x, y + 1, z)
                            && grid.passable(x, y + 2, z)) {
                        supported++;
                    }
                }
            }
            // Un quart de l'emprise : assez pour distinguer un plancher d'une marche isolée.
            if (supported * 4 >= footprint) {
                standing.add(y + 1);
            }
        }
        if (standing.isEmpty()) {
            return new Levels(0, 0, List.of(), Set.of());
        }

        Set<Long> walkable = walkFrom(grid, standing.get(0));
        int reachable = 0;
        for (int level : standing) {
            boolean any = false;
            for (int x = grid.minX(); x <= grid.maxX() && !any; x++) {
                for (int z = grid.minZ(); z <= grid.maxZ() && !any; z++) {
                    if (walkable.contains(key(x, level, z))) {
                        any = true;
                    }
                }
            }
            if (any) {
                reachable++;
            }
        }
        return new Levels(standing.size(), reachable, List.copyOf(standing), walkable);
    }

    /** Diffusion depuis le niveau de départ, avec les déplacements d'un joueur. */
    private static Set<Long> walkFrom(Grid grid, int startLevel) {
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                if (standable(grid, x, startLevel, z)) {
                    long k = key(x, startLevel, z);
                    if (seen.add(k)) {
                        queue.add(new int[] {x, startLevel, z});
                    }
                }
            }
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] s : steps) {
                // Plain-pied, montée d'un bloc, descente d'un bloc : les trois pas d'un joueur.
                for (int dy : new int[] {0, 1, -1}) {
                    int nx = c[0] + s[0];
                    int ny = c[1] + dy;
                    int nz = c[2] + s[2];
                    if (!grid.inside(nx, ny, nz) || !standable(grid, nx, ny, nz)) {
                        continue;
                    }
                    long k = key(nx, ny, nz);
                    if (seen.add(k)) {
                        queue.add(new int[] {nx, ny, nz});
                    }
                }
            }
            // Échelle : on monte et on descend dans la même colonne.
            for (int dy : new int[] {1, -1}) {
                int ny = c[1] + dy;
                if (grid.inside(c[0], ny, c[2]) && BlockSemantics.climbable(grid.at(c[0], ny, c[2]))) {
                    long k = key(c[0], ny, c[2]);
                    if (seen.add(k)) {
                        queue.add(new int[] {c[0], ny, c[2]});
                    }
                }
            }
        }
        return seen;
    }

    /** Peut-on se tenir debout ici : un support dessous, deux cellules libres. */
    private static boolean standable(Grid grid, int x, int y, int z) {
        if (!grid.inside(x, y, z)) {
            return false;
        }
        boolean climbing = BlockSemantics.climbable(grid.at(x, y, z));
        if (climbing) {
            return true;
        }
        return BlockSemantics.support(grid.at(x, y - 1, z))
                && grid.passable(x, y, z)
                && grid.passable(x, y + 1, z);
    }

    // ---- Éclairage ------------------------------------------------------------------------------

    private record Dark(boolean found, int x, int y, int z, int distance) {
    }

    /**
     * La cellule accessible la plus éloignée d'une source, si elle dépasse la distance autorisée.
     *
     * <p>La distance est celle de Tchebychev — le nombre de pas en comptant les diagonales — parce
     * que la lumière de Minecraft se propage ainsi, et non par un chemin de couloir.</p>
     */
    private static Dark findDarkCell(Grid grid, Levels levels, int maxDistance) {
        List<int[]> sources = new ArrayList<>();
        for (var e : grid.cells().entrySet()) {
            if (BlockSemantics.lightSource(e.getValue())) {
                long k = e.getKey();
                int z = (int) (k & 0xFFFFF) - 512;
                int y = (int) ((k >> 20) & 0xFFFFF) - 512;
                int x = (int) ((k >> 40) & 0xFFFFF) - 512;
                sources.add(new int[] {x, y, z});
            }
        }
        if (sources.isEmpty()) {
            return new Dark(false, 0, 0, 0, 0);
        }
        int worst = -1;
        int[] worstCell = null;
        for (long k : levels.walkable()) {
            int z = (int) (k & 0xFFFFF) - 512;
            int y = (int) ((k >> 20) & 0xFFFFF) - 512;
            int x = (int) ((k >> 40) & 0xFFFFF) - 512;
            int best = Integer.MAX_VALUE;
            for (int[] s : sources) {
                int d = Math.max(Math.abs(s[0] - x), Math.max(Math.abs(s[1] - y), Math.abs(s[2] - z)));
                best = Math.min(best, d);
            }
            if (best > worst) {
                worst = best;
                worstCell = new int[] {x, y, z};
            }
        }
        if (worstCell == null || worst <= maxDistance) {
            return new Dark(false, 0, 0, 0, 0);
        }
        return new Dark(true, worstCell[0], worstCell[1], worstCell[2], worst);
    }

    private static String faceName(Facing front) {
        return switch (front == null ? Facing.NORTH : front) {
            case NORTH -> "NORD";
            case SOUTH -> "SUD";
            case WEST -> "OUEST";
            case EAST -> "EST";
        };
    }
}
