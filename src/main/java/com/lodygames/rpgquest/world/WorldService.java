package com.lodygames.rpgquest.world;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Gestion minimale des mondes supplémentaires (voir docs-site/worlds.html) : création/chargement
 * via l'API Bukkit ({@link WorldCreator}, toujours environnement {@link World.Environment#NORMAL},
 * seed aléatoire tant qu'aucune n'est explicitement fixée — {@code WorldCreator} ne fixe rien
 * d'autre par défaut), aucune dépendance externe (pas de Multiverse-Core, pas de code de gestion
 * de mondes ailleurs dans le projet).
 *
 * <p>{@code Server#createWorld(WorldCreator)} charge silencieusement le monde depuis son dossier
 * disque s'il existe déjà (redémarrage), ou le génère entièrement s'il n'existe pas encore
 * (première création) — un seul et même appel couvre les deux cas, RPGQuest n'a pas besoin de
 * distinguer « créer » de « recharger ».</p>
 *
 * <p>Bukkit ne recharge jamais tout seul un monde supplémentaire au démarrage suivant : seul le
 * monde principal (déduit de {@code server.properties}) l'est automatiquement. La petite liste des
 * noms gérés par RPGQuest est donc mémorisée dans {@code plugins/RPGQuest/worlds.yml} (un simple
 * fichier plat, même conception que {@code spawn.SpawnService}), rejouée à chaque {@link #start()}
 * pour que ces mondes reviennent chargés après un redémarrage complet du serveur.</p>
 */
public final class WorldService implements PluginService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_-]+");

    private final RPGQuestPlugin plugin;
    private final Path worldsFile;
    private final Logger logger;

    private final Set<String> managedWorlds = new LinkedHashSet<>();

    public WorldService(RPGQuestPlugin plugin, Path worldsFile, Logger logger) {
        this.plugin = plugin;
        this.worldsFile = worldsFile;
        this.logger = logger;
    }

    @Override
    public void start() {
        managedWorlds.clear();
        managedWorlds.addAll(loadManagedNames());
        for (String name : managedWorlds) {
            if (plugin.getServer().getWorld(name) != null) {
                continue;
            }
            World loaded = tryCreateOrLoad(name);
            if (loaded == null) {
                logger.error("Impossible de recharger le monde géré « {} » au démarrage.", name);
            }
        }
    }

    @Override
    public void stop() {
        // Rien à libérer : un monde chargé reste la responsabilité de Bukkit, pas une ressource à
        // nous — le décharger au stop() du plugin romprait le monde pour les autres plugins/le
        // serveur lui-même, bien au-delà du cycle de vie de RPGQuest.
    }

    public enum CreateOutcome {
        CREATED, ALREADY_LOADED, INVALID_NAME, CREATION_FAILED
    }

    /**
     * Valide {@code rawName}, puis crée (première fois) ou recharge (dossier déjà présent sur
     * disque) le monde correspondant, et mémorise son nom pour les prochains démarrages. Idempotent
     * si le monde est déjà chargé en mémoire : ne le recrée jamais, se contente de le mémoriser.
     */
    public CreateOutcome createOrLoad(String rawName) {
        if (!isValidName(rawName)) {
            return CreateOutcome.INVALID_NAME;
        }
        String name = rawName.toLowerCase(Locale.ROOT);

        if (plugin.getServer().getWorld(name) != null) {
            rememberManaged(name);
            return CreateOutcome.ALREADY_LOADED;
        }

        World created = tryCreateOrLoad(name);
        if (created == null) {
            return CreateOutcome.CREATION_FAILED;
        }
        rememberManaged(name);
        return CreateOutcome.CREATED;
    }

    public Optional<World> find(String name) {
        return Optional.ofNullable(plugin.getServer().getWorld(name.toLowerCase(Locale.ROOT)));
    }

    /** {@code true} si RPGQuest a créé/chargé ce monde via {@link #createOrLoad(String)} (mémorisé dans worlds.yml). */
    public boolean isManaged(String name) {
        return managedWorlds.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Tous les mondes actuellement chargés sur le serveur, gérés par RPGQuest ou non. */
    public List<World> loadedWorlds() {
        return plugin.getServer().getWorlds();
    }

    public static boolean isValidName(String candidate) {
        return candidate != null && !candidate.isBlank()
                && NAME_PATTERN.matcher(candidate.toLowerCase(Locale.ROOT)).matches();
    }

    private World tryCreateOrLoad(String name) {
        try {
            return new WorldCreator(name).environment(World.Environment.NORMAL).createWorld();
        } catch (RuntimeException e) {
            logger.error("Échec de la création/du chargement du monde « {} ».", name, e);
            return null;
        }
    }

    private void rememberManaged(String name) {
        if (managedWorlds.add(name)) {
            persist();
        }
    }

    private Set<String> loadManagedNames() {
        if (!Files.exists(worldsFile)) {
            return Set.of();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(worldsFile.toFile());
        return new LinkedHashSet<>(yaml.getStringList("managed-worlds"));
    }

    private void persist() {
        try {
            Files.createDirectories(worldsFile.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("managed-worlds", List.copyOf(managedWorlds));
            yaml.save(worldsFile.toFile());
        } catch (IOException e) {
            logger.error("Impossible d'écrire {}.", worldsFile, e);
        }
    }
}
