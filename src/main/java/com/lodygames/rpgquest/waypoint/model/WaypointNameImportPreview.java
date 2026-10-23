package com.lodygames.rpgquest.waypoint.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aperçu (simulation) d'un import de noms de waypoints — issue #135, préparation du 23/10/2026.
 *
 * <p><strong>Code inerte</strong> : aucune classe du plugin ni du panel ne l'appelle. Il ne lit ni
 * n'écrit aucune base, aucun fichier, aucun waypoint ; il classe seulement des lignes candidates
 * par rapport à une réserve et à un ensemble de noms déjà attribués fournis par l'appelant. Le
 * branchement (action agent, page PlugAdmin, stockage des noms importés) attend la décision du
 * propriétaire — voir {@code docs/waypoints/preparation-import-lot1.md}.</p>
 *
 * <p>Mêmes règles que le reste du nommage : normalisation {@link WaypointNameCatalog#normalize}
 * (casse, accents, espaces de bord), format {@link WaypointNameCatalog#validateDisplayName} (#134).
 * Sans état, sans Bukkit — testable en JUnit pur.</p>
 */
public final class WaypointNameImportPreview {

    /** Seuil d'alerte proposé (#135) : moins de 50 noms libres. Non branché tant que non validé. */
    public static final int PROPOSED_LOW_STOCK_THRESHOLD = 50;

    /** Forme exacte des noms de secours : un nom importé de cette forme masquerait la suite. */
    private static final Pattern FALLBACK_NAME = Pattern.compile("(?iu)avant-poste\\s+\\d+");

    /** Motif de rejet d'une ligne candidate. */
    public enum Rejection {
        INVALID_FORMAT,
        DUPLICATE_IN_FILE,
        ALREADY_IN_RESERVE,
        ALREADY_ASSIGNED,
        FALLBACK_NAME
    }

    /** Ligne refusée : numéro de ligne (1-based) dans le fichier candidat, texte nettoyé, motif. */
    public record RejectedLine(int lineNumber, String name, Rejection reason, String detail) {
    }

    /**
     * Résultat de la simulation.
     *
     * @param accepted        noms qui seraient ajoutés, dans l'ordre du fichier
     * @param rejected        lignes refusées, avec leur motif
     * @param reserveBefore   taille de la réserve active
     * @param freeBefore      noms libres avant import (réserve moins noms attribués)
     * @param freeAfter       noms libres si l'import était appliqué
     */
    public record Result(List<String> accepted, List<RejectedLine> rejected,
                         int reserveBefore, int freeBefore, int freeAfter) {

        public Result {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }

        public long rejectedCount(Rejection reason) {
            return rejected.stream().filter(line -> line.reason() == reason).count();
        }

        /** Vrai si l'import ferait passer le stock libre au-dessus du seuil proposé. */
        public boolean clearsLowStock() {
            return freeAfter >= PROPOSED_LOW_STOCK_THRESHOLD;
        }
    }

    private WaypointNameImportPreview() {
    }

    /**
     * Classe chaque ligne de {@code candidateLines} (lignes vides et commentaires {@code #}
     * ignorés, comme {@link WaypointNameCatalog#load}). Ne modifie aucun argument.
     *
     * @param assignedNormalized noms d'affichage actuellement attribués, déjà normalisés
     */
    public static Result preview(List<String> candidateLines, WaypointNameCatalog activeReserve,
                                 Set<String> assignedNormalized) {
        Set<String> reserveNormalized = new HashSet<>();
        activeReserve.reserve().forEach(name -> reserveNormalized.add(WaypointNameCatalog.normalize(name)));
        Set<String> seenInFile = new HashSet<>();
        List<String> accepted = new ArrayList<>();
        List<RejectedLine> rejected = new ArrayList<>();

        for (int i = 0; i < candidateLines.size(); i++) {
            String raw = candidateLines.get(i);
            if (raw == null || raw.isBlank() || raw.trim().startsWith("#")) {
                continue;
            }
            String name = WaypointNameCatalog.cleanDisplayName(raw);
            String normalized = WaypointNameCatalog.normalize(name);
            int lineNumber = i + 1;
            var invalid = WaypointNameCatalog.validateDisplayName(name);
            if (invalid.isPresent()) {
                rejected.add(new RejectedLine(lineNumber, name, Rejection.INVALID_FORMAT, invalid.get()));
            } else if (FALLBACK_NAME.matcher(name).matches()) {
                rejected.add(new RejectedLine(lineNumber, name, Rejection.FALLBACK_NAME,
                        "Forme réservée aux noms de secours."));
            } else if (!seenInFile.add(normalized)) {
                rejected.add(new RejectedLine(lineNumber, name, Rejection.DUPLICATE_IN_FILE,
                        "Déjà présent plus haut dans le fichier."));
            } else if (reserveNormalized.contains(normalized)) {
                rejected.add(new RejectedLine(lineNumber, name, Rejection.ALREADY_IN_RESERVE,
                        "Déjà dans la réserve active."));
            } else if (assignedNormalized.contains(normalized)) {
                rejected.add(new RejectedLine(lineNumber, name, Rejection.ALREADY_ASSIGNED,
                        "Déjà porté par un waypoint."));
            } else {
                accepted.add(name);
            }
        }

        int freeBefore = freeCount(reserveNormalized, assignedNormalized);
        return new Result(accepted, rejected, activeReserve.size(), freeBefore, freeBefore + accepted.size());
    }

    /** Noms de la réserve qui ne sont portés par aucun waypoint. */
    public static int freeCount(WaypointNameCatalog reserve, Set<String> assignedNormalized) {
        Set<String> reserveNormalized = new HashSet<>();
        reserve.reserve().forEach(name -> reserveNormalized.add(WaypointNameCatalog.normalize(name)));
        return freeCount(reserveNormalized, assignedNormalized);
    }

    private static int freeCount(Set<String> reserveNormalized, Set<String> assignedNormalized) {
        int free = 0;
        for (String name : reserveNormalized) {
            if (!assignedNormalized.contains(name)) {
                free++;
            }
        }
        return free;
    }
}
