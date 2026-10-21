package com.lodygames.rpgquest.world.state;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Passerelle réelle (issue #563) : état Bukkit pour la validation, opérations déléguées à
 * Multiverse-Core ({@link MultiverseStatePort}), relecture Bukkit + Multiverse. N'écrit aucune
 * configuration RPGQuest (aucun rôle, waypoint, portail ni règle) et ne supprime aucun fichier.
 */
public final class BukkitWorldStateGateway implements WorldStateGateway {

    /** Comme Multiverse-Core : au plus 100 dossiers examinés. */
    static final int MAX_FOLDERS = 100;

    private final Server server;
    private final MultiverseStatePort multiverse;
    private final Supplier<Map<String, List<String>>> roles;
    private final Supplier<Collection<String>> rpgquestManaged;
    private final Supplier<List<WorldStatePlanner.PortalTarget>> portals;

    public BukkitWorldStateGateway(Server server, MultiverseStatePort multiverse,
                                   Supplier<Map<String, List<String>>> roles,
                                   Supplier<Collection<String>> rpgquestManaged,
                                   Supplier<List<WorldStatePlanner.PortalTarget>> portals) {
        this.server = server;
        this.multiverse = multiverse;
        this.roles = roles;
        this.rpgquestManaged = rpgquestManaged;
        this.portals = portals;
    }

    /**
     * Dossiers de monde du répertoire des mondes (ceux qui contiennent {@code level.dat}) et leur
     * dimension déduite du contenu. À appeler HORS du thread principal (accès disque).
     */
    public static List<WorldStatePlanner.Folder> folders(File worldContainer) {
        List<WorldStatePlanner.Folder> out = new ArrayList<>();
        File[] children = worldContainer == null ? null : worldContainer.listFiles(File::isDirectory);
        if (children == null) {
            return out;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File dir : Arrays.copyOf(children, Math.min(children.length, MAX_FOLDERS))) {
            if (new File(dir, "level.dat").isFile()) {
                out.add(new WorldStatePlanner.Folder(dir.getName(), dimension(dir)));
            }
        }
        return out;
    }

    /** Dossier d'un monde normal ({@code region}) en priorité : une sauvegarde solo contient aussi DIM-1 et DIM1. */
    static String dimension(File dir) {
        if (new File(dir, "region").isDirectory()) {
            return "NORMAL";
        }
        if (new File(dir, "DIM-1").isDirectory()) {
            return "NETHER";
        }
        if (new File(dir, "DIM1").isDirectory()) {
            return "THE_END";
        }
        return "";
    }

    @Override
    public WorldStatePlanner.Context context(List<WorldStatePlanner.Folder> folders) {
        List<World> worlds = server.getWorlds();
        Map<String, Integer> players = new LinkedHashMap<>();
        worlds.forEach(w -> players.put(w.getName(), w.getPlayerCount()));
        String reason = multiverse.unavailableReason();
        Map<String, WorldStatePlanner.Known> known = new LinkedHashMap<>();
        if (reason == null) {
            multiverse.knownWorlds().forEach((name, k) -> known.put(name.toLowerCase(Locale.ROOT), k));
        }
        return new WorldStatePlanner.Context(reason == null, reason, worlds.isEmpty() ? "" : worlds.get(0).getName(),
                roles.get(), new java.util.LinkedHashSet<>(rpgquestManaged.get()), players, known, folders,
                portals.get());
    }

    @Override
    public String load(String world) {
        return multiverse.load(world);
    }

    @Override
    public String unload(String world) {
        return multiverse.unload(world);
    }

    @Override
    public String importFolder(String world, String environment) {
        return multiverse.importFolder(world, environment);
    }

    @Override
    public String setAutoLoad(String world, boolean autoLoad) {
        return multiverse.setAutoLoad(world, autoLoad);
    }

    @Override
    public Optional<Reread> describe(String world) {
        World w = server.getWorld(world);
        WorldStatePlanner.Known k = multiverse.unavailableReason() == null
                ? multiverse.knownWorlds().get(world) : null;
        if (w == null && k == null) {
            return Optional.empty();
        }
        return Optional.of(new Reread(w != null, k != null, k == null ? null : k.autoLoad(),
                w != null ? w.getEnvironment().name() : k.environment()));
    }
}
