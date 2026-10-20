package com.lodygames.rpgquest.world.create;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Passerelle réelle de création de monde (issue #562) : état Bukkit pour la validation, création
 * déléguée à Multiverse-Core ({@link MultiverseCreationPort}), relecture Bukkit + Multiverse.
 * Ne touche à aucun monde existant ; n'écrit aucune configuration RPGQuest (aucun rôle, waypoint,
 * portail ni règle).
 */
public final class BukkitWorldCreationGateway implements WorldCreationGateway {

    private final Server server;
    private final MultiverseCreationPort multiverse;
    private final Supplier<Set<String>> roleWorlds;

    public BukkitWorldCreationGateway(Server server, MultiverseCreationPort multiverse,
                                      Supplier<Set<String>> roleWorlds) {
        this.server = server;
        this.multiverse = multiverse;
        this.roleWorlds = roleWorlds;
    }

    /** Dossiers du répertoire des mondes : à appeler HORS du thread principal (accès disque). */
    public static Set<String> folders(File worldContainer) {
        Set<String> out = new LinkedHashSet<>();
        File[] children = worldContainer == null ? null : worldContainer.listFiles(File::isDirectory);
        if (children != null) {
            for (File f : children) {
                out.add(f.getName());
            }
        }
        return out;
    }

    @Override
    public WorldCreationPlanner.Context context(Set<String> folders) {
        List<World> worlds = server.getWorlds();
        Set<String> loaded = new LinkedHashSet<>();
        worlds.forEach(w -> loaded.add(w.getName()));
        String main = worlds.isEmpty() ? "" : worlds.get(0).getName();
        Set<String> essential = new LinkedHashSet<>(roleWorlds.get());
        essential.add(main);
        String reason = multiverse.unavailableReason();
        return new WorldCreationPlanner.Context(reason == null, reason, main, loaded,
                reason == null ? multiverse.knownWorlds() : Set.of(), folders, essential,
                BukkitWorldCreationGateway::validBlock, BukkitWorldCreationGateway::validBiome);
    }

    @Override
    public List<String> biomes() {
        List<String> out = new ArrayList<>();
        Registry.BIOME.forEach(b -> out.add(b.getKey().toString()));
        out.sort(null);
        return out;
    }

    @Override
    public List<String> blocks() {
        List<String> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isBlock() && !m.isLegacy()) {
                out.add(m.getKey().toString());
            }
        }
        out.sort(null);
        return out;
    }

    @Override
    public String create(WorldCreationPlanner.Plan plan) {
        return multiverse.create(plan);
    }

    @Override
    public List<String> configureCreated(String world) {
        List<String> notes = new ArrayList<>();
        String error = multiverse.disableKeepSpawnAndSave(world);
        if (error != null) {
            notes.add("Réglage « zone d'apparition non gardée en mémoire » ou enregistrement Multiverse-Core impossible : "
                    + error + ".");
        }
        return notes;
    }

    @Override
    public Optional<Created> describe(String world) {
        World w = server.getWorld(world);
        if (w == null) {
            return Optional.empty();
        }
        Optional<Map<String, String>> mv = multiverse.describe(world);
        return Optional.of(new Created(w.getName(), w.getEnvironment().name(), worldType(w), w.getSeed(),
                w.canGenerateStructures(), mv.map(m -> m.get("generatorSettings")).orElse(null),
                mv.map(m -> m.get("keepSpawnLoaded")).map(Boolean::parseBoolean).orElse(null), mv.isPresent(),
                mv.map(m -> Boolean.parseBoolean(m.getOrDefault("autoLoad", "false"))).orElse(false)));
    }

    @SuppressWarnings("deprecation") // seule lecture du type de terrain exposée par l'API Paper 1.21.11
    private static String worldType(World w) {
        org.bukkit.WorldType type = w.getWorldType();
        return type == null ? null : type.name();
    }

    static boolean validBlock(String id) {
        Material m = Material.matchMaterial(id);
        return m != null && m.isBlock() && !m.isLegacy();
    }

    static boolean validBiome(String id) {
        NamespacedKey key = NamespacedKey.fromString(id);
        return key != null && Registry.BIOME.get(key) != null;
    }
}
