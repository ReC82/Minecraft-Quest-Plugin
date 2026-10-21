package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Supprimer <strong>définitivement</strong> un bâtiment et toutes ses versions (issue #644).
 *
 * <h2>La famille d'un bâtiment</h2>
 *
 * <p>Tout ce que le plugin a écrit pour un identifiant de bibliothèque, et seulement cela :</p>
 * <ul>
 *   <li>la version <strong>courante</strong> : {@code buildings/<fichier>.yml} et son
 *       {@code schematics/<id>.schem} — le schematic est conservé s'il sert à un autre bâtiment ;</li>
 *   <li>les versions <strong>remplacées</strong> : {@code buildings/.history/<id>/v<N>_<horodatage>/}
 *       (copie faite par la régénération, #94) ;</li>
 *   <li>les versions <strong>archivées</strong> : {@code buildings/.retired/<id>_<horodatage>/}
 *       (« Retirer de la bibliothèque », #284) ;</li>
 *   <li>un effacement resté <strong>inachevé</strong> : {@code buildings/.purging/<id>_<horodatage>/}.</li>
 * </ul>
 * <p>Un dossier dont le nom ne suit pas exactement ces formes n'est pas reconnu : il est montré et
 * <em>conservé</em>. Les sauvegardes de terrain ({@code backup_}, {@code baseline_}), l'historique des
 * poses, les emplacements, les portails, les projets de l'Atelier et les données de joueurs ne font
 * jamais partie de la famille.</p>
 *
 * <h2>Refus</h2>
 *
 * <p>Exemple embarqué (recréé au démarrage), bâtiment posé sur un emplacement, portail lié (même en
 * brouillon), fichier de portail illisible qui le mentionne, autre fichier de bibliothèque déclarant
 * le même identifiant : rien n'est supprimé, le motif et la marche à suivre sont dits. Aucun bloc du
 * monde n'est jamais touché.</p>
 *
 * <h2>Pas de demi-suppression</h2>
 *
 * <p>Tout est d'abord <em>déplacé</em> (déplacements atomiques) dans
 * {@code buildings/.purging/<id>_<horodatage>/}, hors de la bibliothèque. Si un déplacement échoue,
 * ou si la bibliothèque rechargée porte encore le bâtiment, tout est remis en place. Ce n'est
 * qu'ensuite que le dossier d'attente est effacé ; s'il ne peut pas l'être entièrement, le résultat le
 * dit et un nouvel effacement le reprend. Rien n'est effacé au démarrage. Chaque suppression est
 * consignée dans {@code buildings/.audit/purges.log}.</p>
 */
public final class BuildingFamilyPurge {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{3,48}");
    private static final Pattern HISTORY_ENTRY = Pattern.compile("v(\\d{1,9})_\\d{8}-\\d{6}");
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^version:\\s*(\\d{1,9})\\s*$");
    private static final Pattern ID_LINE = Pattern.compile("(?m)^id:\\s*[\"']?([a-z0-9_]+)[\"']?\\s*$");

    public enum Code { PURGED, PURGED_PENDING, NOT_FOUND, BUNDLED, BLOCKED, CONFIRMATION_MISMATCH, FAILED }

    /** Où vit une version de la famille. */
    public enum Kind { CURRENT, HISTORY, RETIRED, PENDING }

    /**
     * Une version (ou un reste) de la famille.
     *
     * @param version numéro de version connu, 0 s'il ne l'est pas
     * @param paths   chemins relatifs au dossier du plugin qui seront supprimés
     */
    public record Version(Kind kind, int version, String label, List<String> paths) {

        public Version {
            paths = List.copyOf(paths);
        }
    }

    /**
     * Aperçu en lecture seule : rien n'est modifié pour le produire.
     *
     * @param kept     ce qui reste en place, et pourquoi
     * @param blockers motifs de refus ; vide = la suppression est possible
     */
    public record Preview(String id, String name, boolean inLibrary, boolean bundled, int currentVersion,
                          List<Version> versions, List<String> kept, List<String> blockers) {

        public Preview {
            versions = List.copyOf(versions);
            kept = List.copyOf(kept);
            blockers = List.copyOf(blockers);
        }

        public boolean exists() {
            return inLibrary || !versions.isEmpty();
        }

        public boolean purgeable() {
            return exists() && !bundled && blockers.isEmpty();
        }

        public int fileCount() {
            return versions.stream().mapToInt(v -> v.paths().size()).sum();
        }
    }

    public record Outcome(Code code, String message, List<String> effects) {

        public Outcome {
            effects = List.copyOf(effects == null ? List.of() : effects);
        }

        public boolean ok() {
            return code == Code.PURGED || code == Code.PURGED_PENDING;
        }
    }

    /** Un élément à supprimer : chemin réel et chemin affiché. */
    private record Target(Path path, String shown) {
    }

    private final BuildingLibrary library;
    private final Path schematics;
    private final Supplier<List<BuildingPlacement>> placements;
    private final Function<String, List<String>> portalReferences;
    private final Logger logger;
    private final Clock clock;

    /**
     * @param portalReferences pour un identifiant, les portails qui le citent (description lisible) —
     *                         y compris un fichier de portail illisible qui le mentionne
     */
    public BuildingFamilyPurge(BuildingLibrary library, Path schematics, Supplier<List<BuildingPlacement>> placements,
                               Function<String, List<String>> portalReferences, Logger logger, Clock clock) {
        this.library = library;
        this.schematics = schematics.normalize();
        this.placements = placements;
        this.portalReferences = portalReferences == null ? id -> List.of() : portalReferences;
        this.logger = logger;
        this.clock = clock;
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Ce que supprimerait {@link #purge}, ce qui serait conservé, et ce qui l'empêche. */
    public Preview preview(String id) {
        return scan(id).preview();
    }

    /**
     * Supprime toute la famille {@code id}.
     *
     * @param confirmation l'identifiant ressaisi par l'administrateur — doit être exactement {@code id}
     */
    public synchronized Outcome purge(String id, String confirmation, String by) {
        if (!validId(id)) {
            return new Outcome(Code.NOT_FOUND, "Identifiant de bâtiment invalide : rien n'a été supprimé.", List.of());
        }
        if (!id.equals(confirmation == null ? "" : confirmation.trim())) {
            return new Outcome(Code.CONFIRMATION_MISMATCH, "L'identifiant ressaisi ne correspond pas à « " + id
                    + " » : rien n'a été supprimé.", List.of());
        }
        Scan scan = scan(id);
        Preview preview = scan.preview();
        Optional<Outcome> refusal = refusal(preview);
        if (refusal.isPresent()) {
            return refusal.get();
        }
        if (preview.inLibrary()) {
            // Plus aucune pose ne peut choisir ce bâtiment à partir d'ici — puis on revérifie.
            library.withdraw(id);
            List<String> sites = usedOn(id);
            if (!sites.isEmpty()) {
                library.reload();
                return blocked(List.of("posé sur " + String.join(", ", sites)));
            }
        }

        Path staging = library.directory().resolve(".purging").resolve(id + "_" + STAMP.format(clock.instant()));
        List<Path[]> moved = new ArrayList<>();
        try {
            Files.createDirectories(staging);
            int n = 0;
            for (Target target : scan.targets()) {
                Path destination = staging.resolve((n++) + "_" + target.path().getFileName());
                Files.move(target.path(), destination, StandardCopyOption.ATOMIC_MOVE);
                moved.add(new Path[] {target.path(), destination});
            }
        } catch (IOException error) {
            String restored = putBack(moved, staging);
            library.reload();
            return new Outcome(Code.FAILED, "Un fichier n'a pas pu être déplacé (" + error.getClass().getSimpleName()
                    + ") : " + restored + " Rien n'a été supprimé.", List.of());
        }
        library.reload();
        if (library.find(id).isPresent()) {
            String restored = putBack(moved, staging);
            library.reload();
            return new Outcome(Code.FAILED, "La bibliothèque porte encore « " + id + " » après rechargement "
                    + "(un autre fichier déclare-t-il le même identifiant ?) : " + restored
                    + " Rien n'a été supprimé.", List.of());
        }
        deleteIfEmpty(library.directory().resolve(".history").resolve(id));

        List<String> effects = new ArrayList<>();
        for (Version v : preview.versions()) {
            effects.add(v.label() + " supprimée : " + String.join(", ", v.paths()));
        }
        effects.addAll(preview.kept().stream().map(k -> "conservé : " + k).toList());
        boolean erased = deleteTree(staging);
        deleteIfEmpty(staging.getParent());
        String pendingNote = erased ? "" : " Une partie des fichiers n'a pas pu être effacée : ils sont hors de "
                + "la bibliothèque, dans buildings/.purging/" + staging.getFileName() + " — relancer la suppression "
                + "de « " + id + " » les effacera.";
        audit(id, preview, by, erased);
        logger.info("[building] « " + id + " » supprimé avec ses " + preview.versions().size() + " version(s) par "
                + safe(by) + (erased ? "" : " — effacement inachevé : " + staging));
        String name = preview.name().isEmpty() ? id : preview.name();
        return new Outcome(erased ? Code.PURGED : Code.PURGED_PENDING, "« " + name + " » et ses "
                + preview.versions().size() + " version(s) ont été supprimés définitivement (" + preview.fileCount()
                + " élément(s)). Il ne réapparaîtra pas au redémarrage ; aucun bloc du monde n'a été touché." + pendingNote,
                effects);
    }

    private Optional<Outcome> refusal(Preview preview) {
        if (!preview.exists()) {
            return Optional.of(new Outcome(Code.NOT_FOUND, "Aucun bâtiment ni version « " + preview.id()
                    + " » sur le serveur : rien n'a été supprimé.", List.of()));
        }
        if (preview.bundled()) {
            return Optional.of(new Outcome(Code.BUNDLED, "« " + preview.name() + " » est un exemple fourni avec le "
                    + "plugin : il serait recréé au prochain démarrage. Rien n'a été supprimé.", List.of()));
        }
        if (!preview.blockers().isEmpty()) {
            return Optional.of(blocked(preview.blockers()));
        }
        return Optional.empty();
    }

    private static Outcome blocked(List<String> blockers) {
        return new Outcome(Code.BLOCKED, "Suppression refusée : " + String.join(" ; ", blockers)
                + ". Rien n'a été supprimé, aucun bloc du monde n'a été touché.", blockers);
    }

    // ---------------------------------------------------------------- inventaire

    private record Scan(Preview preview, List<Target> targets) {
    }

    private Scan scan(String id) {
        if (!validId(id)) {
            return new Scan(new Preview(id == null ? "" : id, "", false, false, 0, List.of(), List.of(),
                    List.of("identifiant invalide")), List.of());
        }
        Path data = library.directory().getParent();
        List<Version> versions = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        Optional<BuildingDefinition> current = library.find(id);
        Optional<Path> source = library.source(id);
        String name = current.map(BuildingDefinition::name).orElse("");
        int currentVersion = current.map(BuildingDefinition::version).orElse(0);
        boolean bundled = current.isPresent() && library.bundled(id);
        if (current.isPresent() && source.isPresent()) {
            List<Target> files = new ArrayList<>(List.of(target(data, source.get())));
            currentSchematic(current.get(), placements.get()).ifPresentOrElse(
                    schem -> files.add(target(data, schem)),
                    () -> kept.add("schematics/" + current.get().schematic() + " — utilisé ailleurs, absent ou hors du "
                            + "dossier des schematics"));
            versions.add(version(Kind.CURRENT, currentVersion, "version courante " + currentVersion, files));
            targets.addAll(files);
        }

        Path history = library.directory().resolve(".history").resolve(id);
        for (Path entry : children(history)) {
            Matcher m = HISTORY_ENTRY.matcher(entry.getFileName().toString());
            if (Files.isDirectory(entry) && m.matches()) {
                Target t = target(data, entry);
                versions.add(version(Kind.HISTORY, Integer.parseInt(m.group(1)), "version remplacée " + m.group(1), List.of(t)));
                targets.add(t);
            } else {
                kept.add(target(data, entry).shown() + " — non reconnu, laissé en place");
            }
        }
        Pattern archived = Pattern.compile(Pattern.quote(id) + "_\\d{8}-\\d{6}");
        for (Path entry : children(library.directory().resolve(".retired"))) {
            if (Files.isDirectory(entry) && archived.matcher(entry.getFileName().toString()).matches()) {
                String note = read(entry.resolve("retired.yml"));
                Matcher declared = ID_LINE.matcher(note);
                if (!note.isEmpty() && declared.find() && !declared.group(1).equals(id)) {
                    kept.add(target(data, entry).shown() + " — sa note désigne « " + declared.group(1) + " », laissé en place");
                    continue;
                }
                Matcher v = VERSION_LINE.matcher(note);
                int number = v.find() ? Integer.parseInt(v.group(1)) : 0;
                Target t = target(data, entry);
                versions.add(version(Kind.RETIRED, number, "version archivée" + (number > 0 ? " " + number : ""), List.of(t)));
                targets.add(t);
            }
        }
        for (Path entry : children(library.directory().resolve(".purging"))) {
            if (Files.isDirectory(entry) && archived.matcher(entry.getFileName().toString()).matches()) {
                Target t = target(data, entry);
                versions.add(version(Kind.PENDING, 0, "effacement inachevé", List.of(t)));
                targets.add(t);
            }
        }
        versions.sort(Comparator.comparing(Version::kind).thenComparing(Comparator.comparingInt(Version::version).reversed()));

        List<String> sites = usedOn(id);
        if (!sites.isEmpty()) {
            blockers.add("posé sur " + String.join(", ", sites) + " — le retirer d'abord de chaque emplacement "
                    + "(« Retirer le bâtiment et restaurer le terrain »)");
        }
        for (String portal : portalReferences.apply(id)) {
            blockers.add("lié au portail " + portal + " — délier la construction du portail d'abord");
        }
        for (Path other : otherDeclarations(id, source.orElse(null))) {
            blockers.add("le fichier buildings/" + other.getFileName() + " déclare aussi « " + id
                    + " » — le corriger d'abord, il le ferait réapparaître");
        }
        kept.add("historique des poses, sauvegardes de terrain, emplacements, portails, projets de l'Atelier");
        return new Scan(new Preview(id, name, current.isPresent(), bundled, currentVersion, versions, kept, blockers),
                targets);
    }

    /** Le schematic de la version courante, s'il appartient à elle seule. */
    private Optional<Path> currentSchematic(BuildingDefinition definition, List<BuildingPlacement> poses) {
        String file = definition.schematic();
        if (file == null || file.isBlank() || file.startsWith("backup_") || file.startsWith("baseline_")) {
            return Optional.empty();
        }
        Path schem = schematics.resolve(file).normalize();
        boolean shared = library.all().stream()
                .anyMatch(other -> !other.id().equals(definition.id()) && file.equals(other.schematic()));
        boolean snapshot = poses.stream().anyMatch(p -> file.equals(p.backupSchematic()));
        if (!schem.startsWith(schematics) || shared || snapshot || !Files.isRegularFile(schem)) {
            return Optional.empty();
        }
        return Optional.of(schem);
    }

    /** Les autres {@code buildings/*.yml} qui déclarent le même identifiant (chargés ou non). */
    private List<Path> otherDeclarations(String id, Path source) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(library.directory(), "*.yml")) {
            for (Path file : files) {
                if (source != null && file.normalize().equals(source.normalize())) {
                    continue;
                }
                Matcher m = ID_LINE.matcher(read(file));
                if (m.find() && m.group(1).equals(id)) {
                    found.add(file);
                }
            }
        } catch (IOException ignored) {
            // Dossier absent : aucune autre déclaration.
        }
        return found;
    }

    private List<String> usedOn(String id) {
        List<String> sites = new ArrayList<>();
        for (BuildingPlacement p : placements.get()) {
            if (p.buildingId().equalsIgnoreCase(id)) {
                sites.add(p.siteId());
            }
        }
        return sites;
    }

    private static Version version(Kind kind, int number, String label, List<Target> files) {
        return new Version(kind, number, label, files.stream().map(Target::shown).toList());
    }

    private static Target target(Path data, Path path) {
        String shown = data == null ? path.toString() : data.relativize(path).toString().replace('\\', '/');
        return new Target(path, Files.isDirectory(path) ? shown + "/" : shown);
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.sorted().toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException error) {
            return "";
        }
    }

    // ---------------------------------------------------------------- écriture

    /** Remet chaque élément déplacé à sa place, du dernier au premier. */
    private String putBack(List<Path[]> moved, Path staging) {
        List<String> stuck = new ArrayList<>();
        for (int i = moved.size() - 1; i >= 0; i--) {
            try {
                Files.move(moved.get(i)[1], moved.get(i)[0], StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException error) {
                stuck.add(moved.get(i)[0].getFileName().toString());
            }
        }
        deleteIfEmpty(staging);
        deleteIfEmpty(staging.getParent());
        if (stuck.isEmpty()) {
            return "tout a été remis en place.";
        }
        logger.severe("[building] suppression interrompue : " + stuck + " n'ont pas pu être remis en place depuis "
                + staging);
        return "ATTENTION, " + stuck.size() + " élément(s) sont restés dans buildings/.purging/" + staging.getFileName()
                + " (" + String.join(", ", stuck) + ") — à remettre à la main.";
    }

    private static boolean deleteTree(Path root) {
        if (!Files.exists(root)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static void deleteIfEmpty(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            if (list.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignored) {
            // Un dossier vide restant n'est pas un problème.
        }
    }

    private void audit(String id, Preview preview, String by, boolean erased) {
        StringBuilder line = new StringBuilder().append(clock.instant()).append(" purge id=").append(id)
                .append(" by=").append(safe(by)).append(" versions=").append(preview.versions().size())
                .append(" erased=").append(erased).append(" removed=[");
        line.append(String.join(", ", preview.versions().stream().flatMap(v -> v.paths().stream()).toList()));
        line.append("]\n");
        try {
            Path log = library.directory().resolve(".audit").resolve("purges.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            logger.warning("[building] journal des suppressions non écrit (" + error.getClass().getSimpleName()
                    + ") : " + line.toString().trim());
        }
    }

    private static String safe(String by) {
        return by == null ? "?" : by.replaceAll("[\\p{Cntrl}\"]", "");
    }
}
