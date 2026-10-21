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
 * <p><strong>Configurations ambiguës refusées (issue #565)</strong> : {@link #reload()} refuse
 * désormais un id déjà chargé et une zone d'activation qui recouvre celle d'un portail déjà accepté
 * dans le même monde (brouillons et portails désactivés compris : ils peuvent être activés). Les
 * portails déjà en mémoire avant le rechargement passent <em>en premier</em>, dans leur ordre : un
 * fichier ajouté à la main qui recouvre {@code hub_to_wild} est donc ignoré (et listé dans {@link
 * #skippedFiles()}) au lieu de lui voler sa zone. Au démarrage, l'ordre est celui des noms de
 * fichier. {@link #create} et {@link #save} appliquent la même règle avant d'écrire.</p>
 *
 * <p>Les écritures sont sérialisées ({@code synchronized}) : l'agent PlugAdmin et {@code /rpgadmin
 * worldportal} peuvent écrire depuis deux threads. Les lectures ({@link #portalsInWorld}, à chaque
 * {@code PlayerMoveEvent}) restent des instantanés {@code volatile} sans verrou.</p>
 */
public final class WorldPortalRegistry implements PluginService {

    private final Path portalsDirectory;
    private final Logger logger;

    private volatile List<WorldPortalDefinition> portals = List.of();
    private volatile Map<String, List<WorldPortalDefinition>> byWorld = Map.of();
    /** Issue #564 : fichier d'origine de chaque portail, même ordre que {@link #portals}. */
    private volatile List<String> sourceFiles = List.of();
    /** Issue #564 : fichiers ignorés au dernier {@link #reload()} et la raison — lecture seule. */
    private volatile List<PortalLoadIssue> skippedFiles = List.of();

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

    public synchronized void reload() {
        List<WorldPortalDefinition> parsed = new ArrayList<>();
        List<String> parsedFiles = new ArrayList<>();
        List<PortalLoadIssue> skipped = new ArrayList<>();
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
                parsed.add(parse(yaml));
                parsedFiles.add(file.getFileName().toString());
            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                logger.warn("Portail simple ignoré ({}) : {}", file.getFileName(), e.getMessage());
                skipped.add(new PortalLoadIssue(file.getFileName().toString(), String.valueOf(e.getMessage())));
            }
        }

        List<WorldPortalDefinition> loaded = new ArrayList<>();
        List<String> origins = new ArrayList<>();
        for (int i : acceptanceOrder(parsed, parsedFiles)) {
            WorldPortalDefinition candidate = parsed.get(i);
            String conflict = conflictWith(candidate, loaded);
            if (conflict != null) {
                logger.warn("Portail simple ignoré ({}) : {}", parsedFiles.get(i), conflict);
                skipped.add(new PortalLoadIssue(parsedFiles.get(i), conflict));
                continue;
            }
            loaded.add(candidate);
            origins.add(parsedFiles.get(i));
        }
        // Affichage et ordre de consultation : celui des noms de fichier, comme avant #565.
        List<Integer> byFile = new ArrayList<>();
        for (int i = 0; i < origins.size(); i++) {
            byFile.add(i);
        }
        byFile.sort(Comparator.comparing(origins::get));
        List<WorldPortalDefinition> sorted = byFile.stream().map(loaded::get).toList();
        List<String> sortedFiles = byFile.stream().map(origins::get).toList();

        this.portals = List.copyOf(sorted);
        this.sourceFiles = List.copyOf(sortedFiles);
        this.skippedFiles = List.copyOf(skipped);
        Map<String, List<WorldPortalDefinition>> index = new HashMap<>();
        for (WorldPortalDefinition portal : sorted) {
            index.computeIfAbsent(portal.world(), w -> new ArrayList<>()).add(portal);
        }
        this.byWorld = Map.copyOf(index);
        logger.info("Chargement des portails simples : {} chargé(s).", sorted.size());
    }

    /** Indices de {@code parsed} : les portails déjà en mémoire (même id, même fichier) d'abord. */
    private List<Integer> acceptanceOrder(List<WorldPortalDefinition> parsed, List<String> files) {
        List<Integer> first = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        List<WorldPortalDefinition> previous = this.portals;
        List<String> previousFiles = this.sourceFiles;
        for (int i = 0; i < parsed.size(); i++) {
            boolean known = false;
            for (int j = 0; j < previous.size() && !known; j++) {
                known = previous.get(j).id().equals(parsed.get(i).id())
                        && j < previousFiles.size() && previousFiles.get(j).equals(files.get(i));
            }
            (known ? first : rest).add(i);
        }
        List<Integer> order = new ArrayList<>(first);
        order.addAll(rest);
        return order;
    }

    /** {@code null} si {@code candidate} peut cohabiter avec {@code accepted}, sinon la raison lisible. */
    static String conflictWith(WorldPortalDefinition candidate, List<WorldPortalDefinition> accepted) {
        for (WorldPortalDefinition existing : accepted) {
            if (existing.id().equals(candidate.id())) {
                return "id « " + candidate.id() + " » déjà déclaré par un autre fichier.";
            }
        }
        for (WorldPortalDefinition existing : accepted) {
            if (candidate.overlaps(existing)) {
                return "zone d'activation qui recouvre celle du portail « " + existing.id() + " ».";
            }
        }
        return null;
    }

    private WorldPortalDefinition parse(YamlConfiguration yaml) {
        return new WorldPortalDefinition(
                requireString(yaml, "id"), requireString(yaml, "world"),
                yaml.getInt("min.x"), yaml.getInt("min.y"), yaml.getInt("min.z"),
                yaml.getInt("max.x"), yaml.getInt("max.y"), yaml.getInt("max.z"),
                requireString(yaml, "destination-world"), yaml.getBoolean("enabled", true),
                parseStrategy(yaml.getString("destination-strategy", DestinationStrategy.WORLD_SPAWN.name())),
                new WorldPortalDefinition.Details(yaml.getString("name", ""), yaml.getString("site", ""),
                        yaml.getBoolean("draft", false), yaml.getInt("size.width"), yaml.getInt("size.height"),
                        yaml.getInt("size.depth"), parseBuilding(yaml)),
                parsePoint(yaml), yaml.getString("return-of", ""), PortalAccessYaml.read(yaml),
                PortalMessagesYaml.read(yaml));
    }

    /** Issue #568 : une construction liée n'a de sens qu'avec un emplacement — sinon ignorée. */
    private static String parseBuilding(YamlConfiguration yaml) {
        return yaml.getString("site", "").isBlank() ? "" : yaml.getString("building", "");
    }

    /** Issue #566 : {@code destination-point} absent = aucun point ; présent, x, y et z sont obligatoires. */
    private WorldPortalDefinition.FixedPoint parsePoint(YamlConfiguration yaml) {
        if (!yaml.isConfigurationSection("destination-point")) {
            return null;
        }
        for (String axis : new String[] {"x", "y", "z"}) {
            if (!yaml.isDouble("destination-point." + axis) && !yaml.isInt("destination-point." + axis)) {
                throw new IllegalArgumentException("« destination-point." + axis + " » manquant ou non numérique.");
            }
        }
        return new WorldPortalDefinition.FixedPoint(yaml.getDouble("destination-point.x"),
                yaml.getDouble("destination-point.y"), yaml.getDouble("destination-point.z"),
                (float) yaml.getDouble("destination-point.yaw"), (float) yaml.getDouble("destination-point.pitch"));
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

    /** Nom du fichier d'où vient chaque portail de {@link #portals()}, au même rang (issue #564). */
    public List<String> sourceFiles() {
        return sourceFiles;
    }

    /** Fichiers de {@code world-portals/} ignorés au dernier chargement, avec la raison (issue #564). */
    public List<PortalLoadIssue> skippedFiles() {
        return skippedFiles;
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

    /** Fichier d'où vient le portail {@code id} ({@code <id>.yml} s'il n'est pas chargé). */
    public String fileOf(String id) {
        List<WorldPortalDefinition> all = portals;
        List<String> files = sourceFiles;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id) && i < files.size()) {
                return files.get(i);
            }
        }
        return id + ".yml";
    }

    public enum CreateOutcome {
        CREATED, DUPLICATE_ID, OVERLAPS, IO_ERROR
    }

    public synchronized CreateOutcome create(WorldPortalDefinition portal) {
        // Un fichier <id>.yml ignoré au chargement (invalide, en conflit) compte aussi : l'écraser
        // effacerait la configuration de quelqu'un d'autre.
        if (find(portal.id()).isPresent() || Files.exists(portalsDirectory.resolve(portal.id() + ".yml"))) {
            return CreateOutcome.DUPLICATE_ID;
        }
        for (WorldPortalDefinition existing : portalsInWorld(portal.world())) {
            if (portal.overlaps(existing)) {
                return CreateOutcome.OVERLAPS;
            }
        }
        if (!write(portal, portal.id() + ".yml")) {
            return CreateOutcome.IO_ERROR;
        }
        reload();
        return CreateOutcome.CREATED;
    }

    public enum SaveOutcome {
        SAVED, NOT_FOUND, OVERLAPS, IO_ERROR
    }

    /**
     * Remplace un portail existant (même id) dans <em>son</em> fichier d'origine (issue #565) : la
     * nouvelle zone ne doit recouvrir aucun autre portail du monde.
     */
    public synchronized SaveOutcome save(WorldPortalDefinition updated) {
        if (find(updated.id()).isEmpty()) {
            return SaveOutcome.NOT_FOUND;
        }
        List<WorldPortalDefinition> others = portals.stream().filter(p -> !p.id().equals(updated.id())).toList();
        if (others.stream().anyMatch(updated::overlaps)) {
            return SaveOutcome.OVERLAPS;
        }
        if (!write(updated, fileOf(updated.id()))) {
            return SaveOutcome.IO_ERROR;
        }
        reload();
        return find(updated.id()).isPresent() ? SaveOutcome.SAVED : SaveOutcome.IO_ERROR;
    }

    public enum SetEnabledOutcome {
        UPDATED, UNCHANGED, NOT_FOUND, IO_ERROR
    }

    /** Bascule {@code enabled} et persiste immédiatement — le reste de la configuration est conservé à l'identique. */
    public synchronized SetEnabledOutcome setEnabled(String id, boolean enabled) {
        Optional<WorldPortalDefinition> existing = find(id);
        if (existing.isEmpty()) {
            return SetEnabledOutcome.NOT_FOUND;
        }
        WorldPortalDefinition current = existing.get();
        if (current.enabled() == enabled) {
            return SetEnabledOutcome.UNCHANGED;
        }
        if (!write(current.withEnabled(enabled), fileOf(id))) {
            return SetEnabledOutcome.IO_ERROR;
        }
        reload();
        return SetEnabledOutcome.UPDATED;
    }

    /** Supprime le fichier persisté et retire le portail de la mémoire (recharge) — ne touche ni au monde ni aux autres portails. */
    public synchronized boolean delete(String id) {
        if (find(id).isEmpty()) {
            return false;
        }
        Path target = portalsDirectory.resolve(fileOf(id));
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            logger.error("Impossible de supprimer le fichier de portail simple {}.", target, e);
            return false;
        }
        reload();
        return true;
    }

    private boolean write(WorldPortalDefinition portal, String fileName) {
        Path target = portalsDirectory.resolve(fileName);
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
            // #565 : champs ajoutés écrits seulement s'ils sont renseignés — un portail historique
            // garde un fichier identique quand on le (dés)active.
            WorldPortalDefinition.Details d = portal.details();
            if (!d.name().isEmpty()) {
                yaml.set("name", d.name());
            }
            if (!d.site().isEmpty()) {
                yaml.set("site", d.site());
            }
            if (d.linked()) {
                yaml.set("building", d.building());
            }
            if (d.draft()) {
                yaml.set("draft", true);
            }
            if (d.hasSize()) {
                yaml.set("size.width", d.width());
                yaml.set("size.height", d.height());
                yaml.set("size.depth", d.depth());
            }
            // #566 : même règle — seulement si renseignés.
            WorldPortalDefinition.FixedPoint point = portal.fixedPoint();
            if (point != null) {
                yaml.set("destination-point.x", point.x());
                yaml.set("destination-point.y", point.y());
                yaml.set("destination-point.z", point.z());
                yaml.set("destination-point.yaw", (double) point.yaw());
                yaml.set("destination-point.pitch", (double) point.pitch());
            }
            if (!portal.returnOf().isEmpty()) {
                yaml.set("return-of", portal.returnOf());
            }
            // #567 : conditions d'accès, seulement si le portail en a.
            PortalAccessYaml.write(yaml, portal.access());
            // #569 : messages et avertissement, seulement les valeurs personnalisées.
            PortalMessagesYaml.write(yaml, portal.messages());
            Path tmp = target.resolveSibling(fileName + ".tmp");
            yaml.save(tmp.toFile());
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            logger.error("Impossible d'écrire le portail simple {} dans {}.", portal.id(), target, e);
            return false;
        }
    }
}
