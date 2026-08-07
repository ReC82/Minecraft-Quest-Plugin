package be.lloyd.rpgquest.travel;

import be.lloyd.rpgquest.travel.model.PortalDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Charge/persiste les portails depuis {@code plugins/RPGQuest/portals/}.
 * Même conception que {@code ZoneRegistry} : un portail est créé/supprimé à
 * l'exécution ({@code /rpgadmin portal create|delete}), chaque opération
 * écrit ou retire directement un fichier YAML puis recharge — le fichier
 * reste la seule source de vérité. Aucun exemple embarqué, voir
 * {@link YamlDestinationRegistry}.
 *
 * <p>{@link #portalsInWorld(String)} est indexé (une passe à chaque {@link
 * #reload()}) pour que {@code travel.PortalService} reste bon marché à
 * chaque {@code PlayerMoveEvent} — même patron que {@code
 * ZoneRegistry#zonesInWorld}.</p>
 */
public final class YamlPortalRegistry implements PortalRegistry {

    public static final int DEFAULT_CHANNEL_SECONDS = 3;
    public static final int DEFAULT_COOLDOWN_SECONDS = 5;

    private final Path portalsDirectory;
    private final Logger logger;
    private final PortalLoader loader = new PortalLoader();

    private volatile List<PortalDefinition> portals = List.of();
    private volatile Map<String, List<PortalDefinition>> byWorld = Map.of();
    private volatile PortalLoadReport lastReport = new PortalLoadReport(List.of(), List.of());

    public YamlPortalRegistry(Path portalsDirectory, Logger logger) {
        this.portalsDirectory = portalsDirectory;
        this.logger = logger;
    }

    @Override
    public void start() {
        try {
            Files.createDirectories(portalsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de portails {}.", portalsDirectory, e);
        }
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public PortalLoadReport reload() {
        PortalLoadReport report = loader.loadDirectory(portalsDirectory);
        this.portals = report.loaded();
        Map<String, List<PortalDefinition>> index = new HashMap<>();
        for (PortalDefinition portal : report.loaded()) {
            index.computeIfAbsent(portal.world(), w -> new ArrayList<>()).add(portal);
        }
        this.byWorld = Map.copyOf(index);
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    public List<PortalDefinition> portals() {
        return portals;
    }

    public List<PortalDefinition> portalsInWorld(String world) {
        return byWorld.getOrDefault(world, List.of());
    }

    public Optional<PortalDefinition> find(String id) {
        return portals.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Le premier portail (dans l'ordre chargé) dont la zone d'activation contient cette position, s'il y en a un. */
    public Optional<PortalDefinition> portalAt(String world, int x, int y, int z) {
        for (PortalDefinition portal : portalsInWorld(world)) {
            if (portal.contains(world, x, y, z)) {
                return Optional.of(portal);
            }
        }
        return Optional.empty();
    }

    public PortalLoadReport lastReport() {
        return lastReport;
    }

    public enum CreateOutcome {
        CREATED, DUPLICATE_ID, OVERLAPS, IO_ERROR
    }

    /** {@code portal} est construit par l'appelant avec une destination absente et les délais par défaut. */
    public CreateOutcome create(PortalDefinition portal) {
        if (find(portal.id()).isPresent()) {
            return CreateOutcome.DUPLICATE_ID;
        }
        for (PortalDefinition existing : portalsInWorld(portal.world())) {
            if (portal.overlaps(existing)) {
                return CreateOutcome.OVERLAPS;
            }
        }
        if (!write(portal)) {
            return CreateOutcome.IO_ERROR;
        }
        reload();
        return CreateOutcome.CREATED;
    }

    public boolean delete(String id) {
        if (find(id).isEmpty()) {
            return false;
        }
        Path target = portalsDirectory.resolve(id + ".yml");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            logger.error("Impossible de supprimer le fichier de portail {}.", target, e);
            return false;
        }
        reload();
        return true;
    }

    public enum SetDestinationOutcome {
        UPDATED, PORTAL_NOT_FOUND, IO_ERROR
    }

    /**
     * Relie le portail à une destination (par id, non validée ici contre
     * {@code DestinationRegistry} — l'appelant, {@code RpgAdminCommand},
     * s'assure que la destination existe déjà avant d'appeler cette
     * méthode, évitant une dépendance croisée entre les deux registres).
     */
    public SetDestinationOutcome setDestination(String portalId, String destinationId) {
        Optional<PortalDefinition> existing = find(portalId);
        if (existing.isEmpty()) {
            return SetDestinationOutcome.PORTAL_NOT_FOUND;
        }
        PortalDefinition current = existing.get();
        PortalDefinition updated = new PortalDefinition(
                current.id(), current.world(), current.minX(), current.minY(), current.minZ(),
                current.maxX(), current.maxY(), current.maxZ(), destinationId,
                current.channelSeconds(), current.cooldownSeconds(), current.requiredPermission(),
                current.requiredQuestId(), current.requiredQuestState(), current.requiredLevel(), current.cost());
        if (!write(updated)) {
            return SetDestinationOutcome.IO_ERROR;
        }
        reload();
        return SetDestinationOutcome.UPDATED;
    }

    private boolean write(PortalDefinition portal) {
        Path target = portalsDirectory.resolve(portal.id() + ".yml");
        try {
            Files.createDirectories(portalsDirectory);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("id", portal.id());
            yaml.set("world", portal.world());
            yaml.set("min.x", portal.minX());
            yaml.set("min.y", portal.minY());
            yaml.set("min.z", portal.minZ());
            yaml.set("max.x", portal.maxX());
            yaml.set("max.y", portal.maxY());
            yaml.set("max.z", portal.maxZ());
            if (portal.destinationId() != null) {
                yaml.set("destination", portal.destinationId());
            }
            yaml.set("channel-seconds", portal.channelSeconds());
            yaml.set("cooldown-seconds", portal.cooldownSeconds());
            if (portal.requiredPermission() != null) {
                yaml.set("required-permission", portal.requiredPermission());
            }
            if (portal.requiredQuestId() != null) {
                yaml.set("required-quest", portal.requiredQuestId().toString());
                yaml.set("required-quest-state", portal.requiredQuestState().name());
            }
            if (portal.requiredLevel() != null) {
                yaml.set("required-level", portal.requiredLevel());
            }
            if (portal.cost() != null) {
                yaml.set("cost", portal.cost());
            }
            yaml.save(target.toFile());
            return true;
        } catch (IOException e) {
            logger.error("Impossible d'écrire le portail {} dans {}.", portal.id(), target, e);
            return false;
        }
    }

    private void logReport(String action, PortalLoadReport report) {
        logger.info("{} des portails : {} chargé(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (PortalLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }
}
