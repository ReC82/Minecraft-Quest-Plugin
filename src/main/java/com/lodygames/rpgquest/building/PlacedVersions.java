package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingPlacement;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Où se trouve, octet pour octet, la version <strong>réellement posée</strong> d'un bâtiment
 * (issue #644, exigence du 26/10 : « versions utilisées immuables »).
 *
 * <h2>Pourquoi l'empreinte, et pas le numéro de version</h2>
 *
 * <p>Un placement mémorise l'empreinte SHA-256 du fichier collé (#234). Republier un bâtiment dans
 * l'Atelier réécrit {@code schematics/<id>.schem} et {@code buildings/<id>.yml} après en avoir copié
 * la version remplacée dans {@code buildings/.history/<id>/v<N>_<horodatage>/} (#290). Ce service
 * vérifie que cette copie contient <em>exactement</em> ce qui a été posé — et refuse l'écrasement
 * quand il ne peut pas le prouver. Une version déclarée peut mentir ; un contenu, non.</p>
 *
 * <h2>Lecture seule</h2>
 *
 * <p>Rien n'est écrit, déplacé ni effacé ici, et aucun bloc du monde n'est lu : seuls les fichiers
 * de la bibliothèque, de son historique ({@code .history}) et de ses archives ({@code .retired})
 * sont hachés. Les empreintes sont mises en cache par (taille, date de modification) pour ne pas
 * relire un fichier inchangé à chaque relevé.</p>
 */
public final class PlacedVersions {

    /** Où la copie exacte de la version posée a été trouvée. */
    public enum Where {
        /** Le fichier actuel de la bibliothèque est la version posée. */
        CURRENT,
        /** Copie conservée dans {@code buildings/.history/<id>/}. */
        HISTORY,
        /** Copie conservée dans une archive {@code buildings/.retired/<id>_<horodatage>/} (#284). */
        RETIRED,
        /** Empreinte connue, mais aucun fichier conservé ne la porte. */
        MISSING,
        /** Placement antérieur à #234 : aucune empreinte, rien ne peut être prouvé. */
        UNKNOWN
    }

    /**
     * La version posée sur un emplacement et l'endroit où elle est conservée.
     *
     * @param path chemin relatif au dossier du plugin ({@code schematics/…} ou {@code buildings/…}),
     *             vide pour {@link Where#MISSING} et {@link Where#UNKNOWN}
     */
    public record Copy(String siteId, String buildingId, int version, String sha256, Where where,
                       String path) {

        /** Conservée hors du fichier courant : un écrasement du courant ne la perd pas. */
        public boolean preservedOutsideCurrent() {
            return where == Where.HISTORY || where == Where.RETIRED;
        }

        /** En clair, pour la fiche de l'emplacement. */
        public String label() {
            String v = version > 0 ? "v" + version : "version inconnue";
            return switch (where) {
                case CURRENT -> v + " — fichier actuel de la bibliothèque (" + path + ")";
                case HISTORY -> v + " — conservée dans l'historique (" + path + ")";
                case RETIRED -> v + " — conservée dans les archives (" + path + ")";
                case MISSING -> v + " — INTROUVABLE : aucun fichier conservé ne porte l'empreinte posée";
                case UNKNOWN -> v + " — empreinte non mémorisée (pose antérieure à #234), non vérifiable";
            };
        }
    }

    private final BuildingLibrary library;
    private final Path schematics;
    private final Supplier<List<BuildingPlacement>> placements;
    private final Map<Path, Stamp> cache = new ConcurrentHashMap<>();

    private record Stamp(long size, long modified, String sha256) {
    }

    public PlacedVersions(BuildingLibrary library, Path schematics,
                          Supplier<List<BuildingPlacement>> placements) {
        this.library = library;
        this.schematics = schematics;
        this.placements = placements;
    }

    /** Les versions posées de ce bâtiment, une par emplacement occupé. */
    public List<Copy> locate(String buildingId) {
        List<Copy> out = new ArrayList<>();
        for (BuildingPlacement placement : placements.get()) {
            if (placement.buildingId().equals(buildingId)) {
                out.add(locate(placement, true));
            }
        }
        return out;
    }

    /** Où est conservée la version posée sur cet emplacement. */
    public Copy locate(BuildingPlacement placement) {
        return locate(placement, true);
    }

    /**
     * Le motif du refus de remplacer {@code current} par une nouvelle version, AVANT toute écriture.
     *
     * <p>Refusé si une pose de ce bâtiment n'a pas d'empreinte (rien ne prouverait qu'elle survit)
     * ou si son empreinte n'est déjà plus portée par aucun fichier : l'écrasement rendrait le
     * diagnostic impossible. Vide si toutes les versions posées sont retrouvées.</p>
     */
    public Optional<String> refusalBeforeOverwrite(BuildingDefinition current) {
        for (BuildingPlacement placement : placements.get()) {
            if (!placement.buildingId().equals(current.id())) {
                continue;
            }
            Copy copy = locate(placement, true);
            if (copy.where() == Where.UNKNOWN || copy.where() == Where.MISSING) {
                return Optional.of(refusal(current, copy, copy.where() == Where.UNKNOWN
                        ? "la version posée n'a pas d'empreinte mémorisée : rien ne prouve qu'elle "
                                + "serait conservée."
                        : "la version posée (empreinte " + shortSha(copy.sha256()) + ") n'est portée par "
                                + "aucun fichier conservé — la remplacer effacerait la dernière trace "
                                + "exploitable."));
            }
        }
        return Optional.empty();
    }

    /**
     * Le motif du refus APRÈS l'archivage de la version courante : chaque version posée doit
     * désormais exister hors du fichier courant, avec la même empreinte. Vide si c'est le cas.
     */
    public Optional<String> refusalAfterArchive(BuildingDefinition current) {
        for (BuildingPlacement placement : placements.get()) {
            if (!placement.buildingId().equals(current.id())) {
                continue;
            }
            Copy copy = locate(placement, false);
            if (!copy.preservedOutsideCurrent()) {
                return Optional.of(refusal(current, copy, "la copie de la version posée (empreinte "
                        + shortSha(copy.sha256()) + ") n'a pas pu être vérifiée dans l'historique."));
            }
        }
        return Optional.empty();
    }

    private static String refusal(BuildingDefinition current, Copy copy, String why) {
        return "« " + current.name() + " » est posé sur l'emplacement « " + copy.siteId() + " » et "
                + why + " Rien n'a été écrit : la version utilisée en jeu reste intacte. Enregistrez "
                + "cette amélioration sous un NOUVEL identifiant, ou retirez d'abord la pose "
                + "(« Libérer », qui restaure le terrain) si vous voulez vraiment remplacer ce bâtiment.";
    }

    private Copy locate(BuildingPlacement placement, boolean includeCurrent) {
        String id = placement.buildingId();
        String sha = placement.schematicSha256() == null ? "" : placement.schematicSha256();
        if (sha.isBlank()) {
            return new Copy(placement.siteId(), id, placement.buildingVersion(), "", Where.UNKNOWN, "");
        }
        if (includeCurrent) {
            Optional<BuildingDefinition> definition = library.find(id);
            if (definition.isPresent() && schematics != null) {
                Path file = schematics.resolve(definition.get().schematic());
                if (sha.equals(fingerprint(file))) {
                    return new Copy(placement.siteId(), id, placement.buildingVersion(), sha, Where.CURRENT,
                            "schematics/" + file.getFileName());
                }
            }
        }
        Path buildings = library.directory();
        Optional<String> history = match(buildings.resolve(".history").resolve(id), null, sha);
        if (history.isPresent()) {
            return new Copy(placement.siteId(), id, placement.buildingVersion(), sha, Where.HISTORY,
                    "buildings/.history/" + id + "/" + history.get());
        }
        Pattern retired = Pattern.compile(Pattern.quote(id) + "_\\d{8}-\\d{6}");
        Optional<String> archive = match(buildings.resolve(".retired"), retired, sha);
        if (archive.isPresent()) {
            return new Copy(placement.siteId(), id, placement.buildingVersion(), sha, Where.RETIRED,
                    "buildings/.retired/" + archive.get());
        }
        return new Copy(placement.siteId(), id, placement.buildingVersion(), sha, Where.MISSING, "");
    }

    /**
     * Cherche, dans les sous-dossiers de {@code root} (filtrés par {@code names} s'il est donné), un
     * {@code .schem} d'empreinte {@code sha}. Renvoie {@code <dossier>/<fichier>}.
     */
    private Optional<String> match(Path root, Pattern names, String sha) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory)
                    .filter(d -> names == null || names.matcher(d.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".schem"))
                            .sorted().toList()) {
                        if (sha.equals(fingerprint(file))) {
                            return Optional.of(dir.getFileName() + "/" + file.getFileName());
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Illisible = non retrouvé : le comportement prudent (refuser l'écrasement) en découle.
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Empreinte SHA-256 du fichier, {@code ""} s'il est absent ou illisible. */
    String fingerprint(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            Stamp known = cache.get(file);
            if (known != null && known.size() == size && known.modified() == modified) {
                return known.sha256();
            }
            String sha = sha256(file);
            cache.put(file, new Stamp(size, modified, sha));
            return sha;
        } catch (IOException e) {
            return "";
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 12 ? String.valueOf(sha) : sha.substring(0, 12);
    }
}
