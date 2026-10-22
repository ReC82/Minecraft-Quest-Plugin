package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
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
    public record Report(List<ArchitecturalFinding> findings, Metrics metrics,
                         List<Staircase> staircases, List<Integer> clutteredLevels) {

        public Report {
            findings = List.copyOf(findings == null ? List.of() : findings);
            staircases = List.copyOf(staircases == null ? List.of() : staircases);
            clutteredLevels = List.copyOf(clutteredLevels == null ? List.of() : clutteredLevels);
        }

        public Report(List<ArchitecturalFinding> findings, Metrics metrics) {
            this(findings, metrics, List.of(), List.of());
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

    /**
     * D'où vient une cellule : le rang de l'instruction qui l'a écrite en dernier (issue #282).
     * Sans elle, l'audit ne peut dire que « une cellule est pleine » ; avec elle, il dit « posée
     * par l'instruction 17 », ce que le modèle et l'administrateur savent corriger.
     */
    public interface Provenance {

        Provenance NONE = new Provenance() {
            @Override
            public int stepAt(int x, int y, int z) {
                return 0;
            }

            @Override
            public String describe(int rank) {
                return "instruction " + rank;
            }
        };

        /** Rang à partir de 1, ou 0 si inconnu. */
        int stepAt(int x, int y, int z);

        String describe(int rank);

        /** #747 : rang du « clear » qui a vidé cette cellule après qu'une instruction l'eut posée, ou 0. */
        default int clearedAt(int x, int y, int z) {
            return 0;
        }

        /** #747 : ce que chaque « clear » a effacé des instructions antérieures. */
        default List<BuildingPlanExpander.Erasure> erasures() {
            return List.of();
        }

        /** #747 : la nature de l'instruction de ce rang, ou {@code null} si inconnue. */
        default PlanStep.Kind kindOf(int rank) {
            return null;
        }
    }

    /** Les mesures brutes, utiles pour l'écran, les tests et les futures coupes du lot E. */
    public record Metrics(int solidCells, int glazedCells, int glazedFacades, int lightSources,
                          int openEnvelopeCellsHigh, int floors, int reachableFloors,
                          int furnishingCells, int envelopeMaterials, double topMaterialShare,
                          String topMaterial, Map<String, Integer> palette) {

        public Metrics {
            palette = palette == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(palette));
        }

        public Metrics(int solidCells, int glazedCells, int glazedFacades, int lightSources,
                       int openEnvelopeCellsHigh, int floors, int reachableFloors, int furnishingCells,
                       int envelopeMaterials, double topMaterialShare, String topMaterial) {
            this(solidCells, glazedCells, glazedFacades, lightSources, openEnvelopeCellsHigh, floors,
                    reachableFloors, furnishingCells, envelopeMaterials, topMaterialShare, topMaterial, Map.of());
        }
    }

    /**
     * Auditer.
     *
     * @param blueprint la grille expansée — la seule source de vérité de cet audit
     * @param front     orientation de référence déclarée par le plan, qui désigne la façade d'entrée
     * @param rules     le profil de seuils, dépendant du type de structure
     */
    public static Report audit(Blueprint blueprint, Facing front, ArchitecturalRules rules) {
        return audit(blueprint, front, rules, Provenance.NONE);
    }

    /**
     * Auditer, en sachant d'où vient chaque cellule (issue #282) : les constats nomment alors
     * l'instruction fautive, et un étage inaccessible reçoit un escalier vérifié par simulation.
     */
    public static Report audit(Blueprint blueprint, Facing front, ArchitecturalRules rules,
                               Provenance provenance) {
        return audit(blueprint, front, rules, provenance, Integer.MIN_VALUE);
    }

    /**
     * Auditer, en connaissant le niveau du terrain (#291) : {@code groundY} est la couche où l'on
     * se tient dehors — l'{@code anchorY} du plan, puisque l'ancre d'un emplacement est la case
     * libre au-dessus du sol. {@link Integer#MIN_VALUE} : inconnu, le terrain est alors supposé
     * affleurer la base de la grille ({@code minY + 1}), la lecture historique.
     */
    public static Report audit(Blueprint blueprint, Facing front, ArchitecturalRules rules,
                               Provenance provenance, int groundY) {
        if (blueprint == null || blueprint.blocks().isEmpty()) {
            return new Report(List.of(ArchitecturalFinding.blocking("EMPTY_STRUCTURE",
                    "La structure ne contient aucun bloc : il n'y a rien à auditer.")),
                    new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""));
        }
        ArchitecturalRules r = rules == null ? ArchitecturalRules.habitable() : rules;
        AuditGrid grid = AuditGrid.of(blueprint);
        return run(grid, front, r, provenance == null ? Provenance.NONE : provenance, true,
                groundY == Integer.MIN_VALUE ? grid.minY() + 1 : groundY);
    }

    /** Codes qu'un escalier ajouté a le droit de faire apparaître : ils concernent l'étage qu'il ouvre. */
    private static final Set<String> NEWLY_VISIBLE = Set.of("DARK_AREA", "LEVEL_CLUTTERED");

    private static Report run(AuditGrid grid, Facing front, ArchitecturalRules r,
                              Provenance provenance, boolean plan, int groundY) {
        List<ArchitecturalFinding> findings = new ArrayList<>();
        Access.Report access = Access.analyse(grid, groundY);
        Set<Long> detachedGlass = new HashSet<>();
        if (access.hasInterior()) {
            for (int[] c : access.detachedGlazing()) {
                detachedGlass.add(AuditGrid.key(c[0], c[1], c[2]));
            }
        }
        int glazed = 0;
        Set<String> glazedFaces = new HashSet<>();
        int lights = 0;
        int furnishing = 0;
        for (var cell : grid.cells().entrySet()) {
            String state = cell.getValue();
            // Une vitre isolée hors des murs n'est pas une fenêtre (Maison de Lily) : elle ne
            // compte ni dans les vitrages, ni dans les façades vitrées.
            if (BlockSemantics.glazing(state) && !detachedGlass.contains(cell.getKey())) {
                glazed++;
                String face = glazedFace(grid, AuditGrid.keyX(cell.getKey()),
                        AuditGrid.keyY(cell.getKey()), AuditGrid.keyZ(cell.getKey()));
                if (face != null) {
                    glazedFaces.add(face);
                }
            }
            if (BlockSemantics.lightSource(state)) {
                lights++;
            }
            if (BlockSemantics.furnishing(state)) {
                furnishing++;
            }
        }

        Entrance entrance = findEntrance(grid, front, r, access);
        List<Opening> openings = openEnvelopeCellsAbove(grid, r.openingMaxHeight(), provenance);
        int openHigh = openings.size();
        Circulation.Analysis levels = Circulation.analyse(grid);
        Envelope envelope = envelopeMaterials(grid);

        // ---- Entrée --------------------------------------------------------------------------
        if (r.requireEntrance() && !entrance.found()) {
            findings.add(ArchitecturalFinding.blocking("NO_ENTRANCE", noEntranceMessage(grid,
                    front, entrance, access, provenance)));
        } else if (r.requireEntrance() && !access.stepFree()) {
            findings.add(ArchitecturalFinding.blocking("ENTRANCE_STEP",
                    "Entrée à sauter : on n'entre qu'en sautant une marche d'un bloc entier. Le "
                            + "terrain est en y=" + (groundY - 1) + " (on s'y tient en y=" + groundY
                            + ")" + (access.thresholds().isEmpty() ? "" : " ; "
                            + String.join(" ; ", access.thresholds())) + ". Pose devant la "
                            + "porte, DANS le volume, une marche d'escalier tournée vers la porte "
                            + "(ou un perron avec ses marches), ou abaisse le plancher."));
        }
        if (r.requireEntrance() && access.hasInterior()
                && (!access.detachedDoors().isEmpty() || !access.detachedGlazing().isEmpty())) {
            findings.add(ArchitecturalFinding.blocking("OPENING_DETACHED",
                    detachedMessage(access, provenance)));
        }
        if (r.requirePassage() && !entrance.anyPassage()) {
            findings.add(ArchitecturalFinding.blocking("NO_PASSAGE",
                    "Aucun passage traversant : cette structure doit pouvoir être franchie."));
        }

        // ---- Stabilité (#288) : ce que la physique détruirait à la pose ---------------------------
        List<Stability.Problem> broken = Stability.brokenAssemblies(grid);
        if (!broken.isEmpty()) {
            findings.add(ArchitecturalFinding.blocking("BROKEN_ASSEMBLY",
                    problems("Assemblages incomplets — détruits par la première mise à jour "
                            + "physique : ", broken, provenance)
                            + " Un lit se pose avec « op: bed », une porte avec « op: door » : le "
                            + "moteur pose alors les deux moitiés ensemble."));
        }
        List<Stability.Problem> loose = Stability.unsupported(grid);
        if (!loose.isEmpty()) {
            findings.add(ArchitecturalFinding.blocking("UNSUPPORTED_BLOCK",
                    problems("Blocs sans appui — ils tomberaient ou se détacheraient à la pose : ",
                            loose, provenance)
                            + " Une lanterne se pose SUR un bloc plein ou se suspend SOUS un "
                            + "plafond (ou une chaîne, minecraft:iron_chain) ; une torche murale, une "
                            + "échelle, un panneau mural s'adossent à un mur plein."));
        }

        if (r.requireEntrance() && access.hasInterior() && !access.blindGlazing().isEmpty()) {
            findings.add(ArchitecturalFinding.warning("WINDOW_OBSTRUCTED",
                    "Fenêtres bouchées : " + access.blindGlazing().size() + " vitrage(s) sont dans un "
                            + "mur mais un bloc plein les masque côté intérieur — "
                            + cellsLabel(access.blindGlazing(), provenance, 3) + "."));
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
                            + r.envelopeOpeningTolerance() + " au plus sont tolérées. "
                            + openingsLabel(openings, provenance)));
        }
        // #747 : un « clear » qui efface une porte, une fenêtre, une lumière, un lit ou une échelle
        // déjà posés détruit une intention que le moteur avait calculée — sans aucun autre signe.
        String erased = erasedArchitecture(provenance, true);
        if (!erased.isEmpty()) {
            findings.add(ArchitecturalFinding.blocking("CLEAR_ERASES", erased));
        }
        String nibbled = erasedArchitecture(provenance, false);
        if (!nibbled.isEmpty()) {
            findings.add(ArchitecturalFinding.warning("CLEAR_NIBBLES", nibbled));
        }

        // ---- Niveaux (refondu par #282) -------------------------------------------------------
        List<Staircase> staircases = new ArrayList<>();
        List<Integer> cluttered = new ArrayList<>();
        if (r.requireLevelAccess() && levels.levels().size() > 1) {
            List<Circulation.LevelState> unreached = levels.states().stream()
                    .filter(st -> !st.reached()).toList();
            if (!unreached.isEmpty()) {
                findings.add(ArchitecturalFinding.blocking("LEVEL_UNREACHABLE",
                        unreachableMessage(grid, levels, unreached, front, r, provenance, plan,
                                staircases, groundY)));
            }
        }
        if (r.requireLevelAccess() && r.minLevelFreeShare() > 0) {
            for (Circulation.LevelState st : levels.states()) {
                if (st.reached() && st.freeShare() < r.minLevelFreeShare()) {
                    cluttered.add(st.level().slabY());
                    List<String> causes = Circulation.clutter(grid, st, provenance);
                    findings.add(ArchitecturalFinding.blocking("LEVEL_CLUTTERED",
                            "Niveau encombré : sur le plancher y=" + st.level().slabY()
                                    + " (on s'y tient en y=" + st.level().standY() + "), un joueur "
                                    + "ne peut se tenir que sur " + st.usable() + " cellule(s) sur "
                                    + st.level().area() + " (" + Math.round(st.freeShare() * 100)
                                    + " %, " + Math.round(r.minLevelFreeShare() * 100)
                                    + " % au minimum). "
                                    + (causes.isEmpty() ? "" : "En cause : "
                                            + String.join(" ; ", causes) + ". ")
                                    + "Range le mobilier le long des murs, en rangées séparées par "
                                    + "des allées de 2 blocs : une « box » de meubles qui couvre "
                                    + "tout un plancher le rend impraticable."));
                }
            }
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

        return new Report(findings, new Metrics(grid.cells().size(), glazed,
                glazedFaces.size(), lights, openHigh, levels.levels().size(),
                levels.reachedCount(), furnishing, envelope.materials(), envelope.topShare(),
                envelope.topMaterial(), palette(grid)), staircases, cluttered);
    }

    /**
     * Le refus d'un étage inaccessible, avec la géométrie et — si le moteur en trouve une — la
     * réparation vérifiée.
     *
     * <p>Les escaliers proposés sont cherchés niveau par niveau, chacun sur la grille déjà
     * réparée par les précédents, et chacun vérifié en rejouant l'audit entier : un escalier qui
     * ouvrirait l'étage mais boucherait l'entrée n'est jamais proposé.</p>
     */
    private static String unreachableMessage(AuditGrid grid, Circulation.Analysis levels,
                                             List<Circulation.LevelState> unreached,
                                             Facing front, ArchitecturalRules r,
                                             Provenance provenance, boolean plan,
                                             List<Staircase> staircases, int groundY) {
        StringBuilder sb = new StringBuilder("Niveau inaccessible : la structure compte "
                + levels.levels().size() + " niveau(x) praticable(s) (sols en y="
                + levels.floorLabel() + ") mais seulement " + levels.reachedCount()
                + " est/sont atteignable(s) depuis le rez-de-chaussée. Un étage se rejoint par "
                + "une volée dont la dernière marche affleure le plancher, ET une trémie percée "
                + "DANS le plancher lui-même — vider l'air au-dessus du plancher ne le troue pas.");
        for (Circulation.LevelState st : unreached) {
            sb.append("\n     Étage non atteint (plancher y=").append(st.level().slabY())
                    .append(") :");
            for (String line : Circulation.diagnoseUnreached(grid, levels, st.level(), provenance)) {
                sb.append("\n       - ").append(line);
            }
        }
        if (!plan) {
            return sb.toString();
        }
        AuditGrid current = grid;
        Circulation.Analysis now = levels;
        String material = stairMaterial(grid);
        for (Circulation.LevelState st : unreached) {
            Circulation.Level target = st.level();
            Circulation.Level lower = null;
            for (Circulation.LevelState other : now.states()) {
                if (other.reached() && other.level().slabY() < target.slabY()) {
                    lower = other.level();
                }
            }
            if (lower == null) {
                break;
            }
            Set<String> before = Set.copyOf(
                    run(current, front, r, Provenance.NONE, false, groundY).blockingCodes());
            var proposal = CirculationPlanner.propose(current, lower, target, material,
                    candidate -> acceptable(candidate, front, r, before, target, groundY));
            if (proposal.isEmpty()) {
                break;
            }
            staircases.add(proposal.get());
            current = current.with(proposal.get());
            now = Circulation.analyse(current);
        }
        if (staircases.isEmpty()) {
            sb.append("\n     Le moteur n'a trouvé aucune position où un escalier tient à "
                    + "l'intérieur (sol au départ, plancher au palier, sans toucher murs, portes, "
                    + "fenêtres ni lumières) : libère une bande de 2 cellules de large le long d'un "
                    + "mur intérieur, du sol jusqu'au plancher y=")
                    .append(unreached.get(0).level().slabY()).append('.');
            return sb.toString();
        }
        sb.append("\n     RÉPARATION VÉRIFIÉE PAR LE MOTEUR — ajoute exactement ")
                .append(staircases.size() > 1 ? "ces instructions" : "cette instruction")
                .append(" (l'escalier calcule seul ses marches, sa trémie et son palier, et passe "
                        + "après le mobilier) et retire les escaliers, échelles et trémies qui ne "
                        + "mènent nulle part :\n");
        for (Staircase stair : staircases) {
            sb.append(stair.yaml("       "));
        }
        return sb.toString().stripTrailing();
    }

    /** La réparation simulée ouvre-t-elle l'étage, sans rien casser d'autre ? */
    private static boolean acceptable(AuditGrid candidate, Facing front, ArchitecturalRules r,
                                      Set<String> before, Circulation.Level target, int groundY) {
        Circulation.Analysis after = Circulation.analyse(candidate);
        boolean opened = after.states().stream().anyMatch(st -> st.reached()
                && st.level().slabY() == target.slabY());
        if (!opened) {
            return false;
        }
        for (String code : run(candidate, front, r, Provenance.NONE, false, groundY).blockingCodes()) {
            if (!before.contains(code) && !NEWLY_VISIBLE.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /** Le bloc d'escalier dominant à l'intérieur — l'escalier proposé garde le style du plan. */
    private static String stairMaterial(AuditGrid grid) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var e : grid.cells().entrySet()) {
            String type = BlockStates.bareType(e.getValue());
            if (type.endsWith("_stairs")
                    && grid.interior(AuditGrid.keyX(e.getKey()), AuditGrid.keyZ(e.getKey()))) {
                counts.merge(type, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(Staircase.DEFAULT_MATERIAL);
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
    private static Entrance findEntrance(AuditGrid grid, Facing front, ArchitecturalRules rules,
                                         Access.Report access) {
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
        // #288–#292 : une entrée d'un bâtiment CLOS se prouve en marchant — du terrain, par une
        // porte, jusqu'à un sol intérieur. Le passage sur la limite de la grille ne suffit plus :
        // c'était, sur la Maison de Lily, la porte isolée posée dans le jardin.
        if (access.hasInterior()) {
            found = access.entered();
        }
        return new Entrance(found, anyPassage, open, total);
    }

    /** La façade vers laquelle regarde un vitrage : du côté intérieur vers le côté extérieur. */
    private static String glazedFace(AuditGrid grid, int x, int y, int z) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        String[] names = {"EST", "OUEST", "SUD", "NORD"};
        for (int i = 0; i < dirs.length; i++) {
            if (!Access.interior(grid, x + dirs[i][0], y, z + dirs[i][1])
                    && Access.interior(grid, x - dirs[i][0], y, z - dirs[i][1])) {
                return names[i];
            }
        }
        return grid.faceOf(x, z);
    }

    private static String cellsLabel(List<int[]> cells, Provenance provenance, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, cells.size()); i++) {
            int[] c = cells.get(i);
            sb.append(i == 0 ? "" : ", ").append(c[0]).append('/').append(c[1]).append('/').append(c[2]);
            int rank = provenance.stepAt(c[0], c[1], c[2]);
            if (rank > 0) {
                sb.append(" (").append(provenance.describe(rank)).append(')');
            }
        }
        if (cells.size() > max) {
            sb.append(" et ").append(cells.size() - max).append(" autre(s)");
        }
        return sb.toString();
    }

    private static String problems(String head, List<Stability.Problem> list, Provenance provenance) {
        StringBuilder sb = new StringBuilder(head).append(list.size()).append(" bloc(s). ");
        for (int i = 0; i < Math.min(6, list.size()); i++) {
            Stability.Problem p = list.get(i);
            sb.append(i == 0 ? "" : " ; ").append(BlockState.type(p.state())).append(" en ")
                    .append(p.x()).append('/').append(p.y()).append('/').append(p.z())
                    .append(" : ").append(p.why());
            int rank = provenance.stepAt(p.x(), p.y(), p.z());
            if (rank > 0) {
                sb.append(" — posé par l'").append(provenance.describe(rank));
            }
        }
        if (list.size() > 6) {
            sb.append(" ; et ").append(list.size() - 6).append(" autre(s)");
        }
        return sb.append('.').toString();
    }

    private static String detachedMessage(Access.Report access, Provenance provenance) {
        StringBuilder sb = new StringBuilder("Ouvertures hors des murs : ");
        if (!access.detachedDoors().isEmpty()) {
            sb.append(access.detachedDoors().size()).append(" porte(s) ne donnent sur aucun intérieur — ")
                    .append(cellsLabel(access.detachedDoors(), provenance, 3)).append(". ");
        }
        if (!access.detachedGlazing().isEmpty()) {
            sb.append(access.detachedGlazing().size()).append(" vitrage(s) ne bordent aucun intérieur — ")
                    .append(cellsLabel(access.detachedGlazing(), provenance, 3)).append(". ");
        }
        return sb.append("Une porte ou une fenêtre se pose DANS un mur extérieur, entre le dehors et "
                + "une pièce couverte : vérifie que le mur visé par « facade » existe à cet endroit "
                + "(« offset » est la coordonnée le long de la façade, en coordonnées du plan).")
                .toString();
    }

    private static String noEntranceMessage(AuditGrid grid, Facing front, Entrance entrance,
                                            Access.Report access, Provenance provenance) {
        if (!access.hasInterior() || access.doors().isEmpty()) {
            return "Aucune entrée : il n'existe aucun passage de 2 blocs de haut traversant la "
                    + "façade " + faceName(front) + " (" + grid.facadeDescription(front)
                    + "). Dans la bande de hauteur où une entrée peut se trouver, "
                    + entrance.facadeOpenCells() + " cellule(s) sur "
                    + entrance.facadeCells() + " sont franchissables. "
                    + "Perce une ouverture de 2 blocs de haut au niveau du sol (y="
                    + (grid.minY() + 1) + " et y=" + (grid.minY() + 2)
                    + ") DANS LE PLAN DE LA FAÇADE, et vérifie que la cellule située juste "
                    + "derrière est également libre.";
        }
        StringBuilder sb = new StringBuilder("Aucune entrée praticable : un joueur venu du terrain "
                + "(on s'y tient en y=" + access.groundY() + ") n'atteint aucun sol intérieur.");
        for (String t : access.thresholds()) {
            sb.append("\n     - ").append(t);
        }
        return sb.append("\n     Une porte se pose dans le mur extérieur, au niveau du sol "
                + "intérieur, avec deux blocs libres devant et derrière elle, et un terrain ou des "
                + "marches qui y mènent.").toString();
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
    private static List<Opening> openEnvelopeCellsAbove(AuditGrid grid, int openingMaxHeight,
                                                        Provenance provenance) {
        int from = grid.minY() + openingMaxHeight;
        List<Opening> open = new ArrayList<>();
        for (int y = Math.max(grid.minY(), from); y <= grid.maxY(); y++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                    int[] inward = inwardOf(grid, x, z);
                    if (inward == null) {
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
                    if (!solidAbove(grid, x, y, z)) {
                        continue;
                    }
                    // #747 : un mur construit en retrait sous un toit qui déborde. La cellule de
                    // bord est alors un AVANT-TOIT, dehors : le vrai mur est juste derrière. L'Église
                    // du hub (murs de 1 à 17 dans un volume de 19, toit de 0 à 18) comptait ainsi
                    // 504 « ouvertures » qui n'en étaient pas — et le modèle empilait un second toit.
                    int[] wall = wallBehind(grid, x, y, z, inward);
                    if (wall != null) {
                        continue;
                    }
                    open.add(new Opening(x, y, z, grid.faceOf(x, z), causeOf(grid, x, y, z, inward,
                            provenance)));
                }
            }
        }
        return open;
    }

    /** Profondeur d'avant-toit admise : un mur plein à 1 ou 2 cellules derrière le bord ferme l'enveloppe. */
    static final int EAVE_DEPTH = 2;

    /** Une cellule ouverte de l'enveloppe, sa face et ce qui l'explique (#747). */
    record Opening(int x, int y, int z, String face, String cause) {
    }

    /**
     * La direction vers l'intérieur depuis une cellule de face latérale, ou {@code null} hors face.
     * Une cellule d'angle a deux composantes : elle regarde vers l'intérieur selon x ET selon z.
     */
    private static int[] inwardOf(AuditGrid grid, int x, int z) {
        int ix = x == grid.minX() ? 1 : x == grid.maxX() ? -1 : 0;
        int iz = z == grid.minZ() ? 1 : z == grid.maxZ() ? -1 : 0;
        return ix == 0 && iz == 0 ? null : new int[] {ix, iz};
    }

    /**
     * Le mur fermé derrière un avant-toit, dans la profondeur admise, ou {@code null}. Pour un angle,
     * le mur peut être derrière selon x, selon z, ou en diagonale (l'angle du mur en retrait).
     */
    private static int[] wallBehind(AuditGrid grid, int x, int y, int z, int[] inward) {
        for (int d = 1; d <= EAVE_DEPTH; d++) {
            int[][] candidates = {
                    {inward[0] * d, 0}, {0, inward[1] * d}, {inward[0] * d, inward[1] * d}};
            for (int[] c : candidates) {
                if (c[0] == 0 && c[1] == 0) {
                    continue;
                }
                String state = grid.at(x + c[0], y, z + c[1]);
                if (BlockSemantics.glazing(state) || !BlockSemantics.passable(state)) {
                    return new int[] {x + c[0], y, z + c[1]};
                }
            }
        }
        return null;
    }

    /** Pourquoi cette cellule est ouverte : un « clear » qui a vidé le mur, un pignon, ou rien de posé. */
    private static String causeOf(AuditGrid grid, int x, int y, int z, int[] inward, Provenance provenance) {
        for (int d = 0; d <= EAVE_DEPTH; d++) {
            int cleared = provenance.clearedAt(x + inward[0] * d, y, z + inward[1] * d);
            if (cleared > 0) {
                return "vidées par l'" + provenance.describe(cleared);
            }
        }
        for (int above = y + 1; above <= grid.maxY(); above++) {
            if (!BlockSemantics.passable(grid.at(x, above, z))) {
                int rank = provenance.stepAt(x, above, z);
                if (rank > 0 && provenance.kindOf(rank) == PlanStep.Kind.GABLE) {
                    return "sous le toit de l'" + provenance.describe(rank) + " : PIGNON non rempli — ferme "
                            + "le triangle vertical à cette extrémité avec des blocs pleins (« fill: »)";
                }
                break;
            }
        }
        return "aucune instruction n'y pose de mur alors qu'un bloc les couvre : le mur manque ou s'arrête "
                + "trop bas sous la couverture";
    }

    /** Les ouvertures regroupées par face et par cause, avec leur étendue : où agir, et pourquoi. */
    private static String openingsLabel(List<Opening> openings, Provenance provenance) {
        Map<String, List<Opening>> groups = new java.util.LinkedHashMap<>();
        for (Opening o : openings) {
            groups.computeIfAbsent(o.face() + "|" + o.cause(), k -> new ArrayList<>()).add(o);
        }
        StringBuilder sb = new StringBuilder("Où :");
        int shown = 0;
        for (var group : groups.values()) {
            if (shown++ == 4) {
                sb.append(" ; et ").append(groups.size() - 4).append(" autre(s) groupe(s)");
                break;
            }
            Opening first = group.get(0);
            int minX = group.stream().mapToInt(Opening::x).min().orElse(0);
            int maxX = group.stream().mapToInt(Opening::x).max().orElse(0);
            int minY = group.stream().mapToInt(Opening::y).min().orElse(0);
            int maxY = group.stream().mapToInt(Opening::y).max().orElse(0);
            int minZ = group.stream().mapToInt(Opening::z).min().orElse(0);
            int maxZ = group.stream().mapToInt(Opening::z).max().orElse(0);
            sb.append(shown == 1 ? " " : " ; ").append("face ").append(first.face()).append(", x ")
                    .append(range(minX, maxX)).append(", y ").append(range(minY, maxY)).append(", z ")
                    .append(range(minZ, maxZ)).append(" (").append(group.size()).append(" cellule(s)) — ")
                    .append(first.cause());
        }
        return sb.append(". Ne superpose PAS un second toit : il ne ferme pas ces cellules, il en crée "
                + "d'autres. Un mur construit en retrait (jusqu'à " + EAVE_DEPTH + " cellules) sous un toit "
                + "qui déborde n'est pas une ouverture.").toString();
    }

    private static String range(int min, int max) {
        return min == max ? String.valueOf(min) : min + "–" + max;
    }

    /**
     * Les « clear » qui effacent une instruction d'architecture déjà posée, ou une chaîne vide.
     *
     * @param major vrai : seulement les effacements graves (une porte, un lit, une échelle, ou au moins
     *              la moitié d'une fenêtre ou d'une lumière) ; faux : seulement les autres, simples
     *              avertissements — un « clear » qui grignote une cellule de vitrage ne refuse pas un
     *              plan déjà accepté (Boulangerie 35ce1e1a)
     */
    private static String erasedArchitecture(Provenance provenance, boolean major) {
        Map<Integer, List<String>> byClear = new java.util.TreeMap<>();
        for (BuildingPlanExpander.Erasure e : provenance.erasures()) {
            PlanStep.Kind kind = provenance.kindOf(e.erasedRank());
            boolean architecture = kind == PlanStep.Kind.DOOR || kind == PlanStep.Kind.WINDOW
                    || kind == PlanStep.Kind.LIGHT || kind == PlanStep.Kind.BED || kind == PlanStep.Kind.LADDER;
            if (!architecture) {
                continue;
            }
            boolean grave = kind == PlanStep.Kind.DOOR || kind == PlanStep.Kind.BED || kind == PlanStep.Kind.LADDER
                    || e.major();
            if (grave == major) {
                byClear.computeIfAbsent(e.clearRank(), k -> new ArrayList<>()).add(e.cells() + "/" + e.placed()
                        + " cellule(s) de l'" + provenance.describe(e.erasedRank()));
            }
        }
        if (byClear.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        byClear.forEach((clear, erased) -> parts.add("l'" + provenance.describe(clear) + " efface "
                + String.join(", ", erased)));
        return "« clear » destructif : " + String.join(" ; ", parts) + ". Les instructions s'appliquent "
                + "DANS L'ORDRE : un « clear » placé après une porte, une fenêtre, une lumière, un lit ou une "
                + "échelle les supprime. Place chaque « clear » AVANT les instructions d'architecture, et "
                + "limite sa zone à l'intérieur : un « clear » qui traverse un mur extérieur y perce un trou.";
    }

    /** La palette réellement construite : bloc (sans propriétés) → nombre de cellules (#747). */
    private static Map<String, Integer> palette(AuditGrid grid) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String state : grid.cells().values()) {
            out.merge(BlockStates.bareType(state), 1, Integer::sum);
        }
        return out;
    }

    /** Y a-t-il de la matière plus haut dans cette colonne ? */
    private static boolean solidAbove(AuditGrid grid, int x, int y, int z) {
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
    private static int uncoveredColumns(AuditGrid grid, Circulation.Analysis levels) {
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
    private static boolean coveredColumnExists(AuditGrid grid, int x, int z) {
        for (int y = grid.minY(); y <= grid.maxY(); y++) {
            if (!grid.passable(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private record Envelope(int materials, double topShare, String topMaterial) {
    }

    private static Envelope envelopeMaterials(AuditGrid grid) {
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

    // ---- Éclairage ------------------------------------------------------------------------------

    private record Dark(boolean found, int x, int y, int z, int distance) {
    }

    /**
     * La cellule accessible la plus éloignée d'une source, si elle dépasse la distance autorisée.
     *
     * <p>La distance est celle de Tchebychev — le nombre de pas en comptant les diagonales — parce
     * que la lumière de Minecraft se propage ainsi, et non par un chemin de couloir.</p>
     */
    private static Dark findDarkCell(AuditGrid grid, Circulation.Analysis levels, int maxDistance) {
        List<int[]> sources = new ArrayList<>();
        for (var e : grid.cells().entrySet()) {
            if (BlockSemantics.lightSource(e.getValue())) {
                long k = e.getKey();
                sources.add(new int[] {AuditGrid.keyX(k), AuditGrid.keyY(k), AuditGrid.keyZ(k)});
            }
        }
        if (sources.isEmpty()) {
            return new Dark(false, 0, 0, 0, 0);
        }
        int worst = -1;
        int[] worstCell = null;
        for (long k : levels.walkable()) {
            int z = AuditGrid.keyZ(k);
            int y = AuditGrid.keyY(k);
            int x = AuditGrid.keyX(k);
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
