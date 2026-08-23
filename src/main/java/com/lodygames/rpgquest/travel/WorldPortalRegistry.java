package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Charge/persiste les portails simples entre mondes (voir {@code
 * travel.model.WorldPortalDefinition}) depuis {@code plugins/RPGQuest/world-portals/} — même
 * conception que {@code zone.ZoneRegistry}/{@code travel.YamlPortalRegistry} (un fichier YAML par
 * id, le fichier reste la seule source de vérité, rechargé après chaque écriture). Délibérément
 * plus simple que {@link YamlPortalRegistry} : un fichier invalide est ignoré (et journalisé) plutôt
 * que rapporté via un objet de rapport dédié — cette variante minimale n'a pas d'exemple embarqué ni
 * de commande d'administration listant les erreurs de chargement en détail.
 *
 * <p>{@link #portalsInWorld(String)} est indexé (une passe par {@link #reload()}) pour que {@code
 * WorldPortalTeleportListener} reste bon marché à chaque {@code PlayerMoveEvent}.</p>
 *
 * <p><strong>Anomalie constatée (investigation « bug TP hub »), non corrigée ici</strong> :
 * contrairement à {@code zone.ZoneLoader}/{@code travel.PortalLoader}, {@link #reload()} ne fait
 * <em>aucune</em> validation croisée entre fichiers — ni id dupliqué, ni chevauchement de zone
 * d'activation. Seul {@link #create(WorldPortalDefinition)} (donc uniquement le chemin {@code
 * /rpgadmin worldportal create}) vérifie les chevauchements au moment de la création ; un fichier
 * ajouté ou édité à la main dans {@code plugins/RPGQuest/world-portals/} (chemin explicitement
 * supporté, {@link #reload()} le relit sans réserve) peut donc introduire silencieusement une
 * seconde zone superposée à une zone existante, ou un id dupliqué (auquel cas {@link #find} ne
 * renvoie jamais que la première occurrence rencontrée) — sans qu'aucune erreur ne soit journalisée.
 * {@link #portalAt} ne renvoie lui aussi que la première zone trouvée par ordre de fichier, jamais
 * un avertissement de chevauchement. Voir {@link #portalsContaining} pour l'outil de diagnostic qui
 * rend ce cas visible en jeu ({@code /rpgadmin worldportal here}).</p>
 */
public final class WorldPortalRegistry implements PluginService {

    private final Path portalsDirectory;
    private final Logger logger;

    private volatile List<WorldPortalDefinition> portals = List.of();
    private volatile Map<String, List<WorldPortalDefinition>> byWorld = Map.of();

    public WorldPortalRegistry(Path portalsDirectory, Logger logger) {
        this.portalsDirectory = portalsDirectory;
        this.logger = logger;
    }

    @Override
    public void start() {
        try {
            Files.createDirectories(portalsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de portails simples {}.", portalsDirectory, e);
        }
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public void reload() {
        List<WorldPortalDefinition> loaded = new ArrayList<>();
        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(portalsDirectory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            logger.error("Impossible de lister le dossier de portails simples {}.", portalsDirectory, e);
            files = List.of();
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path file : files) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                loaded.add(new WorldPortalDefinition(
                        requireString(yaml, "id"), requireString(yaml, "world"),
                        yaml.getInt("min.x"), yaml.getInt("min.y"), yaml.getInt("min.z"),
                        yaml.getInt("max.x"), yaml.getInt("max.y"), yaml.getInt("max.z"),
                        requireString(yaml, "destination-world"), yaml.getBoolean("enabled", true),
                        parseStrategy(yaml.getString("destination-strategy", DestinationStrategy.WORLD_SPAWN.name()))));
            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                logger.warn("Portail simple ignoré ({}) : {}", file.getFileName(), e.getMessage());
            }
        }

        this.portals = List.copyOf(loaded);
        Map<String, List<WorldPortalDefinition>> index = new HashMap<>();
        for (WorldPortalDefinition portal : loaded) {
            index.computeIfAbsent(portal.world(), w -> new ArrayList<>()).add(portal);
        }
        this.byWorld = Map.copyOf(index);
        logger.info("Chargement des portails simples : {} chargé(s).", loaded.size());
    }

    private DestinationStrategy parseStrategy(String raw) {
        try {
            return DestinationStrategy.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("« destination-strategy » invalide : \"" + raw + "\".");
        }
    }

    private String requireString(YamlConfiguration yaml, String key) {
        String value = yaml.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("« " + key + " » manquant ou vide.");
        }
        return value;
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    public List<WorldPortalDefinition> portals() {
        return portals;
    }

    public List<WorldPortalDefinition> portalsInWorld(String world) {
        return byWorld.getOrDefault(world, List.of());
    }

    public Optional<WorldPortalDefinition> find(String id) {
        return portals.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Le premier portail (dans l'ordre chargé) dont la zone d'activation contient cette position, s'il y en a un. */
    public Optional<WorldPortalDefinition> portalAt(String world, int x, int y, int z) {
        for (WorldPortalDefinition portal : portalsInWorld(world)) {
            if (portal.contains(world, x, y, z)) {
                return Optional.of(portal);
            }
        }
        return Optional.empty();
    }

    /**
     * TOUS les portails (activés ou non, dans l'ordre chargé) dont la zone d'activation contient
     * cette position — contrairement à {@link #portalAt}, qui ne renvoie que le premier trouvé et
     * est le seul consulté par {@code WorldPortalTeleportListener} en jeu. Outil de diagnostic
     * (voir {@code /rpgadmin worldportal here}) : {@link #reload()} ne rejette ni les id dupliqués
     * ni les chevauchements entre fichiers (contrairement à {@code zone.ZoneLoader}/{@code
     * travel.PortalLoader}, voir le Javadoc de la classe) — deux zones peuvent donc légitimement se
     * superposer sans qu'aucune erreur ne soit jamais journalisée ; cette méthode est la seule façon
     * de le voir depuis le jeu.
     */
    public List<WorldPortalDefinition> portalsContaining(String world, int x, int y, int z) {
        return portalsInWorld(world).stream().filter(p -> p.contains(world, x, y, z)).toList();
    }

    public enum CreateOutcome {
        CREATED, DUPLICATE_ID, OVERLAPS, IO_ERROR
    }

    public CreateOutcome create(WorldPortalDefinition portal) {
        if (find(portal.id()).isPresent()) {
            return CreateOutcome.DUPLICATE_ID;
        }
        for (WorldPortalDefinition existing : portalsInWorld(portal.world())) {
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

    public enum SetEnabledOutcome {
        UPDATED, UNCHANGED, NOT_FOUND, IO_ERROR
    }

    /** Bascule {@code enabled} et persiste immédiatement — le reste de la configuration est conservé à l'identique. */
    public SetEnabledOutcome setEnabled(String id, boolean enabled) {
        Optional<WorldPortalDefinition> existing = find(id);
        if (existing.isEmpty()) {
            return SetEnabledOutcome.NOT_FOUND;
        }
        WorldPortalDefinition current = existing.get();
        if (current.enabled() == enabled) {
            return SetEnabledOutcome.UNCHANGED;
        }
        WorldPortalDefinition updated = new WorldPortalDefinition(
                current.id(), current.world(), current.minX(), current.minY(), current.minZ(),
                current.maxX(), current.maxY(), current.maxZ(), current.destinationWorld(), enabled,
                current.destinationStrategy());
        if (!write(updated)) {
            return SetEnabledOutcome.IO_ERROR;
        }
        reload();
        return SetEnabledOutcome.UPDATED;
    }

    /** Supprime le fichier persisté et retire le portail de la mémoire (recharge) — ne touche ni au monde ni aux autres portails. */
    public boolean delete(String id) {
        if (find(id).isEmpty()) {
            return false;
        }
        Path target = portalsDirectory.resolve(id + ".yml");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            logger.error("Impossible de supprimer le fichier de portail simple {}.", target, e);
            return false;
        }
        reload();
        return true;
    }

    private boolean write(WorldPortalDefinition portal) {
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
            yaml.set("destination-world", portal.destinationWorld());
            yaml.set("enabled", portal.enabled());
            yaml.set("destination-strategy", portal.destinationStrategy().name());
            yaml.save(target.toFile());
            return true;
        } catch (IOException e) {
            logger.error("Impossible d'écrire le portail simple {} dans {}.", portal.id(), target, e);
            return false;
        }
    }
}
