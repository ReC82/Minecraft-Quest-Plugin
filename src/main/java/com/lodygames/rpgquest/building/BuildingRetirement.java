package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Retirer un bâtiment de la bibliothèque, <strong>sans rien perdre</strong> (issue #284).
 *
 * <h2>Ce que « retirer » veut dire ici</h2>
 *
 * <p>La définition {@code buildings/<fichier>.yml} et son {@code .schem} sont
 * <strong>déplacés</strong> dans {@code buildings/.retired/<id>_<horodatage>/}, avec une note
 * {@code retired.yml} (qui, quand, quelle version). La bibliothèque ne lit que les {@code *.yml} de
 * premier niveau : le bâtiment disparaît du catalogue et <em>ne revient pas</em> au redémarrage,
 * mais il reste restaurable à la main en remettant les deux fichiers en place. L'historique des
 * poses ({@code building_placement_history}) et les sauvegardes de terrain ne sont pas touchés.</p>
 *
 * <h2>Ce qui est refusé, et pourquoi</h2>
 *
 * <ul>
 *   <li><strong>un bâtiment posé</strong> sur au moins un emplacement : c'est sa définition que
 *       relisent « Réorienter » et « Remplacer ». Il faut d'abord le retirer de chaque emplacement
 *       (ce qui restaure le terrain), comme pour la suppression d'un emplacement occupé (#239) ;</li>
 *   <li><strong>un exemple embarqué</strong> ({@code test_hut_01}, {@code test_watchtower_01}) : le
 *       plugin le recrée à chaque démarrage — l'effacement ne tiendrait pas et l'écran mentirait.</li>
 * </ul>
 *
 * <h2>Pas de demi-retrait</h2>
 *
 * <p>La définition est d'abord retirée de l'ensemble actif (plus aucune pose ne peut la choisir),
 * puis l'usage est revérifié — une pose lancée juste avant serait vue. Si le déplacement du
 * schematic échoue, la définition est remise à sa place ; si la bibliothèque rechargée la porte
 * encore, tout est remis en place et l'échec est dit.</p>
 */
public final class BuildingRetirement {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public enum Code { RETIRED, NOT_FOUND, IN_USE, BUNDLED, FAILED }

    public record Outcome(Code code, String message, List<String> effects) {

        public Outcome {
            effects = List.copyOf(effects == null ? List.of() : effects);
        }

        public boolean ok() {
            return code == Code.RETIRED;
        }
    }

    private final BuildingLibrary library;
    private final Path schematics;
    private final Supplier<List<BuildingPlacement>> placements;
    private final Logger logger;
    private final Clock clock;

    public BuildingRetirement(BuildingLibrary library, Path schematics,
                              Supplier<List<BuildingPlacement>> placements, Logger logger, Clock clock) {
        this.library = library;
        this.schematics = schematics;
        this.placements = placements;
        this.logger = logger;
        this.clock = clock;
    }

    /** Les emplacements où ce bâtiment est posé. */
    public List<String> usedOn(String id) {
        List<String> sites = new ArrayList<>();
        for (BuildingPlacement p : placements.get()) {
            if (p.buildingId().equalsIgnoreCase(id)) {
                sites.add(p.siteId());
            }
        }
        return sites;
    }

    public synchronized Outcome retire(String id, String by) {
        Optional<BuildingDefinition> found = library.find(id);
        Optional<Path> source = library.source(id);
        if (found.isEmpty() || source.isEmpty()) {
            return new Outcome(Code.NOT_FOUND, "Aucun bâtiment « " + id + " » dans la bibliothèque : "
                    + "rien n'a été retiré.", List.of());
        }
        BuildingDefinition definition = found.get();
        if (library.bundled(definition.id())) {
            return new Outcome(Code.BUNDLED, "« " + definition.name() + " » est un exemple fourni avec "
                    + "le plugin : il serait recréé au prochain démarrage. Il ne peut pas être retiré. "
                    + "Rien n'a été modifié.", List.of());
        }
        if (!usedOn(definition.id()).isEmpty()) {
            return inUse(definition);
        }
        // Plus aucune pose ne peut choisir ce bâtiment à partir d'ici — puis on revérifie.
        library.withdraw(definition.id());
        if (!usedOn(definition.id()).isEmpty()) {
            library.reload();
            return inUse(definition);
        }

        Path archive = library.directory().resolve(".retired")
                .resolve(definition.id() + "_" + STAMP.format(clock.instant()));
        Path yml = source.get();
        Path archivedYml = archive.resolve(yml.getFileName().toString());
        Path schem = schematics.resolve(definition.schematic()).normalize();
        boolean schemShared = library.all().stream()
                .anyMatch(other -> other.schematic().equals(definition.schematic()));
        boolean schemInside = schem.startsWith(schematics.normalize());
        List<String> effects = new ArrayList<>();
        try {
            Files.createDirectories(archive);
            Files.move(yml, archivedYml, StandardCopyOption.ATOMIC_MOVE);
            effects.add("buildings/" + yml.getFileName() + " → buildings/.retired/" + archive.getFileName() + "/");
        } catch (IOException error) {
            library.reload();
            return new Outcome(Code.FAILED, "La définition n'a pas pu être déplacée ("
                    + error.getClass().getSimpleName() + ") : rien n'a été retiré.", List.of());
        }
        if (schemInside && !schemShared && Files.exists(schem)) {
            try {
                Files.move(schem, archive.resolve(schem.getFileName().toString()), StandardCopyOption.ATOMIC_MOVE);
                effects.add("schematics/" + schem.getFileName() + " → buildings/.retired/" + archive.getFileName() + "/");
            } catch (IOException error) {
                // Compensation : la définition revient, rien n'est à moitié retiré.
                try {
                    Files.move(archivedYml, yml, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ignored) {
                    logger.severe("[building] retrait de " + definition.id() + " : définition archivée mais "
                            + "schematic resté en place, et remise en place impossible — voir " + archive);
                }
                library.reload();
                return new Outcome(Code.FAILED, "Le schematic n'a pas pu être déplacé ("
                        + error.getClass().getSimpleName() + ") : la définition a été remise en place, "
                        + "rien n'a été retiré.", List.of());
            }
        } else if (schemShared) {
            effects.add("schematics/" + definition.schematic() + " conservé : utilisé par un autre bâtiment");
        }
        try {
            Files.writeString(archive.resolve("retired.yml"), "id: " + definition.id() + "\nname: \""
                    + definition.name().replace("\"", "'") + "\"\nversion: " + definition.version()
                    + "\nschematic: " + definition.schematic() + "\nretired_by: \"" + safe(by)
                    + "\"\nretired_at: " + clock.instant() + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // La note est un confort : son absence ne remet pas le retrait en cause.
        }
        library.reload();
        if (library.find(definition.id()).isPresent()) {
            return new Outcome(Code.FAILED, "La bibliothèque porte encore « " + definition.id()
                    + " » après rechargement (un autre fichier déclare-t-il le même identifiant ?). "
                    + "Les fichiers déplacés sont dans buildings/.retired/" + archive.getFileName() + ".",
                    effects);
        }
        logger.info("[building] « " + definition.id() + " » retiré de la bibliothèque par " + safe(by)
                + " — archivé dans " + archive);
        effects.add("historique des poses et sauvegardes de terrain conservés");
        return new Outcome(Code.RETIRED, "« " + definition.name() + " » (version " + definition.version()
                + ") a été retiré de la bibliothèque. Ses fichiers sont archivés et restaurables ; il ne "
                + "réapparaîtra pas au redémarrage.", effects);
    }

    private Outcome inUse(BuildingDefinition definition) {
        return new Outcome(Code.IN_USE, "« " + definition.name() + " » est posé sur "
                + String.join(", ", usedOn(definition.id())) + " : retirez-le d'abord de chaque "
                + "emplacement (« Retirer le bâtiment et restaurer le terrain »). Rien n'a été modifié.",
                List.of());
    }

    private static String safe(String by) {
        return by == null ? "?" : by.replaceAll("[\\p{Cntrl}\"]", "");
    }
}
