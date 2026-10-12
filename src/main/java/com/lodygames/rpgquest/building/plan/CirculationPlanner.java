package com.lodygames.rpgquest.building.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Trouve, sur la grille réelle, un escalier qui relie deux niveaux (issue #282).
 *
 * <h2>Pourquoi le moteur propose, au lieu de seulement refuser</h2>
 *
 * <p>Trois plans successifs de la Bibliothèque du Hub ont été refusés pour un étage inaccessible.
 * À chaque correction, le modèle recevait « ajoute un escalier, perce la trémie » et rendait une
 * nouvelle variante du même défaut : il ne <em>voit</em> pas la grille. Le moteur, lui, la voit.
 * Il peut donc chercher une position où un escalier tient réellement — à l'intérieur, sur le sol,
 * avec un palier sur le plancher — et <strong>vérifier par simulation</strong> que l'étage devient
 * atteignable, avant de proposer quoi que ce soit.</p>
 *
 * <h2>Ce qu'il s'interdit</h2>
 *
 * <ul>
 *   <li>toucher l'anneau des murs extérieurs : toutes les colonnes de la volée, départ et palier
 *       compris, sont strictement intérieures ;</li>
 *   <li>détruire une porte, un vitrage ou une source de lumière ;</li>
 *   <li>proposer une position qui n'a pas été vérifiée : le {@code verifier} rejoue l'audit sur
 *       la grille simulée et refuse toute régression (entrée, vitrages, enveloppe…).</li>
 * </ul>
 *
 * <p>Il préfère, dans cet ordre : moins de mobilier et de structure détruits, une trémie qui
 * réutilise l'ouverture déjà percée par le plan, une volée de deux voies, et une volée longeant
 * un mur — c'est là qu'un architecte la mettrait, et c'est ce qui laisse la salle libre.</p>
 */
final class CirculationPlanner {

    private CirculationPlanner() {
    }

    /** Candidats vérifiés au plus : la vérification rejoue l'audit, elle n'est pas gratuite. */
    private static final int MAX_VERIFIED = 60;

    private record Candidate(Staircase stair, int cost) {
    }

    /**
     * Le meilleur escalier vérifié entre ces deux niveaux, ou rien.
     *
     * @param verifier rejoue l'audit sur la grille modifiée ; vrai si la réparation est acceptable
     */
    static Optional<Staircase> propose(AuditGrid grid, Circulation.Level lower,
                                       Circulation.Level upper, String material,
                                       Predicate<AuditGrid> verifier) {
        int y0 = lower.standY();
        int floor = upper.slabY();
        if (floor <= y0 - 1 || floor - y0 + 1 > Staircase.MAX_STEPS || !grid.hasInterior()) {
            return Optional.empty();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int width : new int[] {2, 1}) {
            for (BlockStates.Dir dir : BlockStates.Dir.values()) {
                for (int x = grid.minX() + 1; x < grid.maxX(); x++) {
                    for (int z = grid.minZ() + 1; z < grid.maxZ(); z++) {
                        Staircase stair = new Staircase(x, y0, z, dir, floor, width, material);
                        int cost = cost(grid, stair);
                        if (cost >= 0) {
                            candidates.add(new Candidate(stair, cost));
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::cost)
                .thenComparingInt(c -> c.stair().x())
                .thenComparingInt(c -> c.stair().z())
                .thenComparingInt(c -> c.stair().dir().ordinal())
                .thenComparingInt(c -> -c.stair().width()));
        int verified = 0;
        for (Candidate candidate : candidates) {
            if (verified++ >= MAX_VERIFIED) {
                break;
            }
            if (verifier.test(grid.with(candidate.stair()))) {
                return Optional.of(candidate.stair());
            }
        }
        return Optional.empty();
    }

    /** Le coût d'une position, ou -1 si elle est interdite. */
    private static int cost(AuditGrid grid, Staircase stair) {
        int cost = 0;
        for (Staircase.Placement p : stair.placements()) {
            if (!grid.interior(p.x(), p.z()) || !grid.inside(p.x(), p.y(), p.z())) {
                return -1;
            }
            String there = grid.at(p.x(), p.y(), p.z());
            if (there == null) {
                continue;
            }
            if (BlockSemantics.glazing(there) || BlockSemantics.lightSource(there)
                    || BlockState.type(there).endsWith("_door")) {
                return -1;
            }
            if (BlockSemantics.passable(there)) {
                continue;
            }
            if (BlockSemantics.furnishing(there)) {
                cost += 3;
            } else if (p.y() == stair.floor() && p.clears()) {
                // La trémie elle-même : le prix normal d'un escalier.
                cost += 1;
            } else if (p.y() != stair.floor()) {
                // Une cloison, un poteau, une marche existante : on préfère les épargner.
                cost += 8;
            }
        }
        for (int[] s : stair.supports()) {
            if (!grid.structural(s[0], s[1], s[2])) {
                return -1;
            }
        }
        // Longer un mur : distance de la voie la plus extérieure à l'anneau intérieur.
        int lanes = stair.width() - 1;
        int ex = stair.x() + (stair.dir().alongX() ? 0 : lanes);
        int ez = stair.z() + (stair.dir().alongX() ? lanes : 0);
        int toWall = stair.dir().alongX()
                ? Math.min(stair.z() - grid.minZ() - 1, grid.maxZ() - 1 - ez)
                : Math.min(stair.x() - grid.minX() - 1, grid.maxX() - 1 - ex);
        cost += 2 * Math.max(0, toWall);
        if (stair.width() == 1) {
            cost += 6;
        }
        return cost;
    }
}
