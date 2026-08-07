package be.lloyd.rpgquest.travel;

import be.lloyd.rpgquest.travel.model.Destination;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Charge/persiste les destinations depuis {@code plugins/RPGQuest/destinations/}.
 * Même conception que {@code ZoneRegistry} : une destination est créée (ou
 * mise à jour) à l'exécution — {@code /rpgadmin portal setdestination}
 * écrit directement un fichier YAML capturant la position exacte de
 * l'administrateur, puis recharge — le fichier reste la seule source de
 * vérité. Aucun exemple embarqué (contrairement à {@code central_village}) :
 * une destination doit être une position réellement sûre sur le monde de
 * l'opérateur, bundler des coordonnées arbitraires serait plus nuisible
 * qu'utile (voir {@code docs/TRAVEL.md}).
 */
public final class YamlDestinationRegistry implements DestinationRegistry {

    private final Path destinationsDirectory;
    private final Logger logger;
    private final DestinationLoader loader = new DestinationLoader();

    private volatile List<Destination> destinations = List.of();
    private volatile DestinationLoadReport lastReport = new DestinationLoadReport(List.of(), List.of());

    public YamlDestinationRegistry(Path destinationsDirectory, Logger logger) {
        this.destinationsDirectory = destinationsDirectory;
        this.logger = logger;
    }

    @Override
    public void start() {
        try {
            Files.createDirectories(destinationsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de destinations {}.", destinationsDirectory, e);
        }
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public DestinationLoadReport reload() {
        DestinationLoadReport report = loader.loadDirectory(destinationsDirectory);
        this.destinations = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    public List<Destination> destinations() {
        return destinations;
    }

    public Optional<Destination> find(String id) {
        return destinations.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public DestinationLoadReport lastReport() {
        return lastReport;
    }

    /** Crée la destination si l'id est nouveau, ou remplace sa position si elle existe déjà (sémantique « set »). */
    public boolean createOrUpdate(Destination destination) {
        Path target = destinationsDirectory.resolve(destination.id() + ".yml");
        try {
            Files.createDirectories(destinationsDirectory);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("id", destination.id());
            yaml.set("world", destination.world());
            yaml.set("x", destination.x());
            yaml.set("y", destination.y());
            yaml.set("z", destination.z());
            yaml.set("yaw", (double) destination.yaw());
            yaml.set("pitch", (double) destination.pitch());
            yaml.save(target.toFile());
        } catch (IOException e) {
            logger.error("Impossible d'écrire la destination {} dans {}.", destination.id(), target, e);
            return false;
        }
        reload();
        return true;
    }

    private void logReport(String action, DestinationLoadReport report) {
        logger.info("{} des destinations : {} chargée(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (DestinationLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }
}
