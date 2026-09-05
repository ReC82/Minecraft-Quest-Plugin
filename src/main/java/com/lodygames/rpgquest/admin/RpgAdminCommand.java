package com.lodygames.rpgquest.admin;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.hub.HubGuideDefinition;
import com.lodygames.rpgquest.hub.HubGuideReferral;
import com.lodygames.rpgquest.hub.HubGuideRegistry;
import com.lodygames.rpgquest.mob.SpecialMobLoadIssue;
import com.lodygames.rpgquest.mob.SpecialMobLoadReport;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.player.PlayerResetService;
import com.lodygames.rpgquest.spawn.SpawnPoint;
import com.lodygames.rpgquest.spawn.SpawnService;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import com.lodygames.rpgquest.story.model.StoryState;
import com.lodygames.rpgquest.travel.WorldPortalDebugService;
import com.lodygames.rpgquest.travel.WorldPortalRegistry;
import com.lodygames.rpgquest.travel.WorldPortalTeleportListener;
import com.lodygames.rpgquest.travel.YamlDestinationRegistry;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.waystone.WaystoneService;
import com.lodygames.rpgquest.waystone.model.Waystone;
import com.lodygames.rpgquest.world.WorldService;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.ZoneSelectionService;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rpgadmin} — commande d'administration racine, point d'entrée pour
 * les sous-systèmes d'administration du monde : aplatissement de terrain,
 * zones protégées et portails à cette étape, mobs spéciaux dans une étape
 * ultérieure, ajoutés comme d'autres branches de {@link #onCommand}).
 * Toutes les sous-commandes exigent {@code rpgquest.admin.world} et un
 * joueur en jeu (jamais la console, qui n'a pas de position à centrer) —
 * aucune sous-commande ne prend de coordonnée explicite dans sa syntaxe :
 * {@code zone}/{@code portal} réutilisent tous deux l'outil de sélection
 * {@code wand} pour leur cuboïde (protection ou zone d'activation).
 */
public final class RpgAdminCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "rpgquest.admin.world";
    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS =
            List.of("flatten", "zone", "portal", "mob", "npc", "spawn", "world", "worldportal", "story", "waystone", "player", "guide");
    private static final List<String> GUIDE_SUBCOMMANDS = List.of("list", "info");
    private static final List<String> WAYSTONE_SUBCOMMANDS =
            List.of("list", "here", "tp", "generatehere", "reset");
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("resetnew");
    private static final List<String> FLATTEN_SUBCOMMANDS = List.of("confirm", "cancel", "undo");
    private static final List<String> ZONE_SUBCOMMANDS = List.of("create", "delete", "list", "info", "wand");
    private static final List<String> PORTAL_SUBCOMMANDS = List.of("create", "delete", "list", "info", "setdestination");
    private static final List<String> MOB_SUBCOMMANDS = List.of("spawn", "list", "inspect", "reload", "metrics");
    private static final List<String> NPC_SUBCOMMANDS = List.of("tag", "untag", "info");
    private static final List<String> SPAWN_SUBCOMMANDS = List.of("set", "tp");
    private static final List<String> WORLD_SUBCOMMANDS = List.of("create", "tp", "list");
    private static final List<String> WORLD_PORTAL_SUBCOMMANDS =
            List.of("create", "info", "list", "enable", "disable", "delete", "debug", "here");
    private static final List<String> WORLD_PORTAL_DEBUG_SUBCOMMANDS = List.of("show", "hide", "showall", "hideall");
    private static final List<String> STORY_SUBCOMMANDS = List.of("info", "start", "reset", "resetwithquests");
    private static final double NPC_REACH = 6.0;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final FlattenService flattenService;
    private final ZoneRegistry zoneRegistry;
    private final ZoneSelectionService zoneSelectionService;
    private final YamlPortalRegistry portalRegistry;
    private final YamlDestinationRegistry destinationRegistry;
    private final SpecialMobRegistry mobRegistry;
    private final SpecialMobService mobService;
    private final NpcIdentityService npcIdentityService;
    private final SpawnService spawnService;
    private final WorldService worldService;
    private final WorldPortalRegistry worldPortalRegistry;
    private final WorldPortalDebugService worldPortalDebugService;
    private final StoryService storyService;
    private final WaystoneService waystoneService;
    private final PlayerResetService playerResetService;
    private final HubGuideRegistry hubGuideRegistry;
    private final RPGQuestPlugin plugin;

    public RpgAdminCommand(FlattenService flattenService, ZoneRegistry zoneRegistry, ZoneSelectionService zoneSelectionService,
                            YamlPortalRegistry portalRegistry, YamlDestinationRegistry destinationRegistry,
                            SpecialMobRegistry mobRegistry, SpecialMobService mobService, NpcIdentityService npcIdentityService,
                            SpawnService spawnService, WorldService worldService, WorldPortalRegistry worldPortalRegistry,
                            WorldPortalDebugService worldPortalDebugService,
                            StoryService storyService, WaystoneService waystoneService,
                            PlayerResetService playerResetService, HubGuideRegistry hubGuideRegistry, RPGQuestPlugin plugin) {
        this.flattenService = flattenService;
        this.zoneRegistry = zoneRegistry;
        this.zoneSelectionService = zoneSelectionService;
        this.portalRegistry = portalRegistry;
        this.destinationRegistry = destinationRegistry;
        this.mobRegistry = mobRegistry;
        this.mobService = mobService;
        this.npcIdentityService = npcIdentityService;
        this.spawnService = spawnService;
        this.worldService = worldService;
        this.worldPortalRegistry = worldPortalRegistry;
        this.worldPortalDebugService = worldPortalDebugService;
        this.storyService = storyService;
        this.waystoneService = waystoneService;
        this.playerResetService = playerResetService;
        this.hubGuideRegistry = hubGuideRegistry;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", PERMISSION)));
            return true;
        }
        // "story" cible un joueur passé en argument (pas la position de l'exécutant, contrairement à
        // toutes les autres sous-commandes) : utilisable depuis la console, seul cas à échapper à la
        // contrainte "joueur en jeu" ci-dessous.
        if (args.length > 0 && args[0].equalsIgnoreCase("story")) {
            handleStory(sender, args);
            return true;
        }
        // "player" cible aussi un joueur passé en argument (en ligne OU hors ligne) : utilisable
        // depuis la console, même exception que "story" à la contrainte "joueur en jeu" ci-dessous.
        if (args.length > 0 && args[0].equalsIgnoreCase("player")) {
            handlePlayer(sender, args);
            return true;
        }
        // "guide" : diagnostic en lecture seule des Guides de Hub configurés (issue #11) — aucune
        // position requise, utilisable depuis la console comme "story"/"player".
        if (args.length > 0 && args[0].equalsIgnoreCase("guide")) {
            handleGuide(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Cette commande doit être exécutée par un joueur en jeu (position requise, non fournie en argument).</red>"));
            return true;
        }
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("flatten")) {
            handleFlatten(player, args);
        } else if (args[0].equalsIgnoreCase("zone")) {
            handleZone(player, args);
        } else if (args[0].equalsIgnoreCase("portal")) {
            handlePortal(player, args);
        } else if (args[0].equalsIgnoreCase("mob")) {
            handleMob(player, args);
        } else if (args[0].equalsIgnoreCase("npc")) {
            handleNpc(player, args);
        } else if (args[0].equalsIgnoreCase("spawn")) {
            handleSpawn(player, args);
        } else if (args[0].equalsIgnoreCase("world")) {
            handleWorld(player, args);
        } else if (args[0].equalsIgnoreCase("worldportal")) {
            handleWorldPortal(player, args);
        } else if (args[0].equalsIgnoreCase("waystone")) {
            handleWaystone(player, args);
        } else {
            sendUsage(player);
        }
        return true;
    }

    // ---- Waystones (mission « Waystones Wild » — outils de test uniquement) --------------------

    private void handleWaystone(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(MM.deserialize(
                    "<yellow>/rpgadmin waystone <list|here|tp <id>|generatehere|reset discoveries <joueur>></yellow>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> handleWaystoneList(player);
            case "here" -> handleWaystoneHere(player);
            case "tp" -> handleWaystoneTp(player, args);
            case "generatehere" -> handleWaystoneGenerateHere(player);
            case "reset" -> handleWaystoneResetDiscoveries(player, args);
            default -> player.sendMessage(MM.deserialize(
                    "<yellow>/rpgadmin waystone <list|here|tp <id>|generatehere|reset discoveries <joueur>></yellow>"));
        }
    }

    private void handleWaystoneList(Player player) {
        List<Waystone> all = waystoneService.all();
        if (all.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucune Waystone générée pour le moment.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize("<gold><bold>Waystones (<n>)</bold></gold>",
                Placeholder.unparsed("n", String.valueOf(all.size()))));
        for (Waystone w : all) {
            player.sendMessage(MM.deserialize(
                    "<white><id></white> <gray>— <name> @ <world> <x>,<y>,<z> (cellule <cx>,<cz>)</gray>",
                    Placeholder.unparsed("id", w.id()), Placeholder.parsed("name", w.name()),
                    Placeholder.unparsed("world", w.world()), Placeholder.unparsed("x", String.valueOf(w.x())),
                    Placeholder.unparsed("y", String.valueOf(w.y())), Placeholder.unparsed("z", String.valueOf(w.z())),
                    Placeholder.unparsed("cx", String.valueOf(w.cellX())), Placeholder.unparsed("cz", String.valueOf(w.cellZ()))));
        }
    }

    private void handleWaystoneHere(Player player) {
        Optional<Waystone> here = waystoneService.waystoneInCellOf(player.getLocation());
        if (here.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucune Waystone dans la cellule courante.</gray>"));
            return;
        }
        Waystone w = here.get();
        player.sendMessage(MM.deserialize(
                "<white><id></white> <gray>— <name> @ <x>,<y>,<z></gray>",
                Placeholder.unparsed("id", w.id()), Placeholder.parsed("name", w.name()),
                Placeholder.unparsed("x", String.valueOf(w.x())), Placeholder.unparsed("y", String.valueOf(w.y())),
                Placeholder.unparsed("z", String.valueOf(w.z()))));
    }

    private void handleWaystoneTp(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin waystone tp <id></yellow>"));
            return;
        }
        Optional<Waystone> waystone = waystoneService.byId(args[2]);
        if (waystone.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Waystone inconnue :</red> <white><id></white>",
                    Placeholder.unparsed("id", args[2])));
            return;
        }
        Waystone w = waystone.get();
        World world = plugin.getServer().getWorld(w.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de cette Waystone n'est pas chargé.</red>"));
            return;
        }
        player.teleport(new Location(world, w.x() + 0.5, w.y() + 1, w.z() + 0.5));
        player.sendMessage(MM.deserialize("<green>Téléporté à</green> <white><id></white>.",
                Placeholder.unparsed("id", w.id())));
    }

    private void handleWaystoneGenerateHere(Player player) {
        waystoneService.generateAt(player.getLocation()).thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (result.isPresent()) {
                Waystone w = result.get();
                player.sendMessage(MM.deserialize(
                        "<green>Waystone générée :</green> <white><id></white> <gray>@ <x>,<y>,<z></gray>",
                        Placeholder.unparsed("id", w.id()), Placeholder.unparsed("x", String.valueOf(w.x())),
                        Placeholder.unparsed("y", String.valueOf(w.y())), Placeholder.unparsed("z", String.valueOf(w.z()))));
            } else {
                player.sendMessage(MM.deserialize(
                        "<yellow>Aucune Waystone générée (cellule déjà occupée, ou aucune surface sûre trouvée).</yellow>"));
            }
        })).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Échec de /rpgadmin waystone generatehere", error);
            return null;
        });
    }

    private void handleWaystoneResetDiscoveries(Player player, String[] args) {
        if (args.length < 4 || !args[2].equalsIgnoreCase("discoveries")) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin waystone reset discoveries <joueur></yellow>"));
            return;
        }
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[3]);
        waystoneService.resetDiscoveries(target.getUniqueId()).thenAccept(count -> plugin.getServer().getScheduler().runTask(plugin, () ->
                player.sendMessage(MM.deserialize(
                        "<green>Découvertes de Waystones réinitialisées pour</green> <white><p></white> <gray>(<n> ligne(s)).</gray>",
                        Placeholder.unparsed("p", args[3]), Placeholder.unparsed("n", String.valueOf(count)))))).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Échec de /rpgadmin waystone reset discoveries", error);
            return null;
        });
    }

    // ---- Portails -----------------------------------------------------------

    private void handlePortal(Player player, String[] args) {
        if (args.length < 2) {
            sendPortalUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> handlePortalCreate(player, args);
            case "delete" -> handlePortalDelete(player, args);
            case "list" -> handlePortalList(player);
            case "info" -> handlePortalInfo(player, args);
            case "setdestination" -> handlePortalSetDestination(player, args);
            default -> sendPortalUsage(player);
        }
    }

    private void handlePortalCreate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin portal create <id></yellow>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);

        var pos1 = zoneSelectionService.pos1(player.getUniqueId());
        var pos2 = zoneSelectionService.pos2(player.getUniqueId());
        if (pos1.isEmpty() || pos2.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Sélectionnez d'abord deux positions avec</red> <yellow>/rpgadmin zone wand</yellow>."));
            return;
        }
        Location a = pos1.get();
        Location b = pos2.get();
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            player.sendMessage(MM.deserialize("<red>Les deux positions doivent être dans le même monde.</red>"));
            return;
        }

        PortalDefinition portal;
        try {
            portal = new PortalDefinition(id, a.getWorld().getName(),
                    Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                    Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()),
                    null, YamlPortalRegistry.DEFAULT_CHANNEL_SECONDS, YamlPortalRegistry.DEFAULT_COOLDOWN_SECONDS,
                    null, null, null, null, null);
        } catch (IllegalArgumentException e) {
            player.sendMessage(MM.deserialize(
                    "<red>Id de portail invalide :</red> <white><reason></white>", Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
            return;
        }

        switch (portalRegistry.create(portal)) {
            case CREATED -> {
                zoneSelectionService.clear(player.getUniqueId());
                player.sendMessage(MM.deserialize(
                        "<green>Portail créé :</green> <white><id></white> <gray>— configure sa destination avec</gray> "
                                + "<yellow>/rpgadmin portal setdestination <id> <destinationId></yellow>",
                        Placeholder.unparsed("id", id)));
            }
            case DUPLICATE_ID -> player.sendMessage(MM.deserialize(
                    "<red>Un portail porte déjà l'id</red> <white><id></white>.", Placeholder.unparsed("id", id)));
            case OVERLAPS -> player.sendMessage(MM.deserialize(
                    "<red>Cette zone d'activation chevauche un portail existant dans ce monde.</red>"));
            case IO_ERROR -> player.sendMessage(MM.deserialize(
                    "<red>Erreur d'écriture : voir la console.</red>"));
        }
    }

    private void handlePortalDelete(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin portal delete <id></yellow>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (portalRegistry.delete(id)) {
            player.sendMessage(MM.deserialize("<green>Portail supprimé :</green> <white><id></white>", Placeholder.unparsed("id", id)));
        } else {
            player.sendMessage(MM.deserialize("<red>Portail inconnu :</red> <white><id></white>", Placeholder.unparsed("id", id)));
        }
    }

    private void handlePortalList(Player player) {
        var portals = portalRegistry.portals();
        if (portals.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucun portail chargé.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>portail(s) :</gray>", Placeholder.unparsed("count", String.valueOf(portals.size()))));
        for (PortalDefinition portal : portals) {
            player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<world>, destination :</gray> <white><dest></white><gray>)</gray>",
                    Placeholder.unparsed("id", portal.id()), Placeholder.unparsed("world", portal.world()),
                    Placeholder.unparsed("dest", portal.destinationId() == null ? "aucune" : portal.destinationId())));
        }
    }

    private void handlePortalInfo(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin portal info <id></yellow>"));
            return;
        }
        Optional<PortalDefinition> portalOpt = portalRegistry.find(args[2].toLowerCase(Locale.ROOT));
        if (portalOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Portail inconnu.</red>"));
            return;
        }
        PortalDefinition portal = portalOpt.get();
        player.sendMessage(MM.deserialize("<gold>=== <id> ===</gold>", Placeholder.unparsed("id", portal.id())));
        player.sendMessage(MM.deserialize(
                "<white>Monde :</white> <gray><world></gray>", Placeholder.unparsed("world", portal.world())));
        player.sendMessage(MM.deserialize(
                "<white>Bornes :</white> <gray>(<minx>, <miny>, <minz>) → (<maxx>, <maxy>, <maxz>)</gray>",
                Placeholder.unparsed("minx", String.valueOf(portal.minX())), Placeholder.unparsed("miny", String.valueOf(portal.minY())),
                Placeholder.unparsed("minz", String.valueOf(portal.minZ())), Placeholder.unparsed("maxx", String.valueOf(portal.maxX())),
                Placeholder.unparsed("maxy", String.valueOf(portal.maxY())), Placeholder.unparsed("maxz", String.valueOf(portal.maxZ()))));
        player.sendMessage(MM.deserialize(
                "<white>Destination :</white> <gray><dest></gray>",
                Placeholder.unparsed("dest", portal.destinationId() == null ? "aucune (non configurée)" : portal.destinationId())));
        player.sendMessage(MM.deserialize(
                "<white>Canalisation :</white> <gray><ch>s</gray> <white>Cooldown :</white> <gray><cd>s</gray>",
                Placeholder.unparsed("ch", String.valueOf(portal.channelSeconds())), Placeholder.unparsed("cd", String.valueOf(portal.cooldownSeconds()))));
        if (portal.requiredPermission() != null) {
            player.sendMessage(MM.deserialize(
                    "<white>Permission requise :</white> <gray><perm></gray>", Placeholder.unparsed("perm", portal.requiredPermission())));
        }
        if (portal.requiredQuestId() != null) {
            player.sendMessage(MM.deserialize(
                    "<white>Quête requise :</white> <gray><quest> (<state>)</gray>",
                    Placeholder.unparsed("quest", portal.requiredQuestId().toString()), Placeholder.unparsed("state", portal.requiredQuestState().name())));
        }
        if (portal.requiredLevel() != null) {
            player.sendMessage(MM.deserialize(
                    "<white>Niveau requis :</white> <gray><lvl></gray>", Placeholder.unparsed("lvl", String.valueOf(portal.requiredLevel()))));
        }
        if (portal.cost() != null) {
            player.sendMessage(MM.deserialize(
                    "<white>Coût :</white> <gold><cost></gold>", Placeholder.unparsed("cost", String.valueOf(portal.cost()))));
        }
    }

    private void handlePortalSetDestination(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin portal setdestination <id> <destinationId></yellow>"));
            return;
        }
        String portalId = args[2].toLowerCase(Locale.ROOT);
        String destinationId = args[3].toLowerCase(Locale.ROOT);

        if (portalRegistry.find(portalId).isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Portail inconnu :</red> <white><id></white>", Placeholder.unparsed("id", portalId)));
            return;
        }

        Location here = player.getLocation();
        Destination destination;
        try {
            destination = new Destination(destinationId, here.getWorld().getName(),
                    here.getX(), here.getY(), here.getZ(), here.getYaw(), here.getPitch());
        } catch (IllegalArgumentException e) {
            player.sendMessage(MM.deserialize(
                    "<red>Id de destination invalide :</red> <white><reason></white>", Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
            return;
        }

        if (!destinationRegistry.createOrUpdate(destination)) {
            player.sendMessage(MM.deserialize("<red>Erreur d'écriture de la destination : voir la console.</red>"));
            return;
        }

        switch (portalRegistry.setDestination(portalId, destinationId)) {
            case UPDATED -> player.sendMessage(MM.deserialize(
                    "<green>Destination</green> <white><dest></white> <green>fixée à ta position actuelle et reliée au portail</green> <white><id></white>",
                    Placeholder.unparsed("dest", destinationId), Placeholder.unparsed("id", portalId)));
            case PORTAL_NOT_FOUND -> player.sendMessage(MM.deserialize(
                    "<red>Portail inconnu :</red> <white><id></white>", Placeholder.unparsed("id", portalId)));
            case IO_ERROR -> player.sendMessage(MM.deserialize(
                    "<red>Erreur d'écriture du portail : voir la console.</red>"));
        }
    }

    private void sendPortalUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin portal create <id></yellow> <gray>- crée un portail depuis la sélection</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin portal delete <id></yellow> <gray>- supprime un portail</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin portal list</yellow> <gray>- liste les portails</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin portal info <id></yellow> <gray>- détail d'un portail</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin portal setdestination <id> <destinationId></yellow> <gray>- fixe la destination à ta position actuelle</gray>"));
    }

    // ---- Mobs spéciaux --------------------------------------------------------

    private void handleMob(Player player, String[] args) {
        if (args.length < 2) {
            sendMobUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "spawn" -> handleMobSpawn(player, args);
            case "list" -> handleMobList(player);
            case "inspect" -> handleMobInspect(player, args);
            case "reload" -> handleMobReload(player);
            case "metrics" -> handleMobMetrics(player);
            default -> sendMobUsage(player);
        }
    }

    /**
     * Invoque directement à la position du joueur, en contournant les restrictions de spawn naturel
     * (mondes/biomes/zones, limite de population) : commande d'administration destinée aux tests
     * manuels (mission : "Faire apparaître chaque variante"), pas au spawn naturel du jeu.
     */
    private void handleMobSpawn(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin mob spawn <id></yellow>"));
            return;
        }
        NamespacedKey id = resolveMobId(args[2]);
        Optional<SpecialMobDefinition> defOpt = mobRegistry.find(id);
        if (defOpt.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Mob spécial inconnu :</red> <white><id></white>", Placeholder.unparsed("id", id.toString())));
            return;
        }
        SpecialMobDefinition def = defOpt.get();

        Location location = player.getLocation();
        Entity spawned = location.getWorld().spawnEntity(location, def.entityType());
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            player.sendMessage(MM.deserialize("<red>Type d'entité non vivant, impossible d'appliquer la variante.</red>"));
            return;
        }
        mobService.apply(living, def);
        player.sendMessage(MM.deserialize(
                "<green>Mob spécial invoqué :</green> <white><id></white>", Placeholder.unparsed("id", id.toString())));
    }

    private void handleMobList(Player player) {
        List<SpecialMobDefinition> definitions = mobRegistry.definitions();
        if (definitions.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucun mob spécial chargé.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>mob(s) spécial(aux) chargé(s) :</gray>",
                Placeholder.unparsed("count", String.valueOf(definitions.size()))));
        for (SpecialMobDefinition def : definitions) {
            player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<entity>, pop. <pop><popmax>)</gray>",
                    Placeholder.unparsed("id", def.id().toString()),
                    Placeholder.unparsed("entity", def.entityType().name()),
                    Placeholder.unparsed("pop", String.valueOf(mobService.populationOf(def.id()))),
                    Placeholder.unparsed("popmax", def.maxPopulation() == null ? "" : "/" + def.maxPopulation())));
        }
    }

    private void handleMobInspect(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin mob inspect <id></yellow>"));
            return;
        }
        NamespacedKey id = resolveMobId(args[2]);
        Optional<SpecialMobDefinition> defOpt = mobRegistry.find(id);
        if (defOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Mob spécial inconnu.</red>"));
            return;
        }
        SpecialMobDefinition def = defOpt.get();

        player.sendMessage(MM.deserialize("<gold>=== <id> ===</gold>", Placeholder.unparsed("id", def.id().toString())));
        player.sendMessage(MM.deserialize(
                "<white>Entité :</white> <gray><entity></gray> <white>Nom :</white> <reset><name></reset>",
                Placeholder.unparsed("entity", def.entityType().name()), Placeholder.parsed("name", def.displayName())));
        player.sendMessage(MM.deserialize(
                "<white>Chance de spawn :</white> <gray><chance>%</gray>",
                Placeholder.unparsed("chance", String.valueOf(def.spawnChance() * 100))));
        if (!def.allowedWorlds().isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<white>Mondes autorisés :</white> <gray><worlds></gray>",
                    Placeholder.unparsed("worlds", String.join(", ", def.allowedWorlds()))));
        }
        if (!def.allowedBiomes().isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<white>Biomes autorisés :</white> <gray><biomes></gray>",
                    Placeholder.unparsed("biomes", String.join(", ", def.allowedBiomes()))));
        }
        if (!def.allowedZones().isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<white>Zones autorisées :</white> <gray><zones></gray>",
                    Placeholder.unparsed("zones", String.join(", ", def.allowedZones()))));
        }
        player.sendMessage(MM.deserialize(
                "<white>Vie :</white> <gray><hp></gray> <white>Dégâts :</white> <gray><dmg></gray> "
                        + "<white>Vitesse :</white> <gray><spd></gray> <white>Armure :</white> <gray><arm></gray>",
                Placeholder.unparsed("hp", String.valueOf(def.health())), Placeholder.unparsed("dmg", String.valueOf(def.damage())),
                Placeholder.unparsed("spd", String.valueOf(def.speed())), Placeholder.unparsed("arm", String.valueOf(def.armor()))));
        if (!def.abilities().isEmpty()) {
            String abilities = def.abilities().stream().map(MobAbility::type).map(Enum::name)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            player.sendMessage(MM.deserialize(
                    "<white>Capacités :</white> <gray><abilities></gray>", Placeholder.unparsed("abilities", abilities)));
        }
        player.sendMessage(MM.deserialize(
                "<white>Drops :</white> <gray><drops> entrée(s)</gray> <white>XP :</white> <gray><xp></gray>",
                Placeholder.unparsed("drops", String.valueOf(def.drops().size())),
                Placeholder.unparsed("xp", def.xpReward() == null ? "aucun" : String.valueOf(def.xpReward()))));
        player.sendMessage(MM.deserialize(
                "<white>Population :</white> <gray><pop><popmax></gray>",
                Placeholder.unparsed("pop", String.valueOf(mobService.populationOf(def.id()))),
                Placeholder.unparsed("popmax", def.maxPopulation() == null ? " (illimitée)" : "/" + def.maxPopulation())));
    }

    private void handleMobReload(Player player) {
        SpecialMobLoadReport report = mobRegistry.reload();
        player.sendMessage(MM.deserialize(
                "<gold>Rechargement</gold> <gray>: <loaded> mob(s) chargé(s), <errors> erreur(s).</gray>",
                Placeholder.unparsed("loaded", String.valueOf(report.loaded().size())),
                Placeholder.unparsed("errors", String.valueOf(report.issues().size()))));
        for (SpecialMobLoadIssue issue : report.issues()) {
            player.sendMessage(MM.deserialize(
                    "<red>- [<file>]</red> <white><message></white>",
                    Placeholder.unparsed("file", issue.file()), Placeholder.unparsed("message", issue.message())));
        }
    }

    private void handleMobMetrics(Player player) {
        Map<NamespacedKey, Long> spawnCounts = mobService.spawnMetricsSnapshot();
        Map<String, Long> abilityCounts = mobService.abilityMetricsSnapshot();

        player.sendMessage(MM.deserialize("<gold>=== Métriques mobs spéciaux ===</gold>"));
        if (spawnCounts.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucun spawn enregistré.</gray>"));
        } else {
            spawnCounts.forEach((id, count) -> player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>: <count> spawn(s)</gray>",
                    Placeholder.unparsed("id", id.toString()), Placeholder.unparsed("count", String.valueOf(count)))));
        }
        if (!abilityCounts.isEmpty()) {
            abilityCounts.forEach((ability, count) -> player.sendMessage(MM.deserialize(
                    "<yellow>- <ability></yellow> <gray>: <count> déclenchement(s)</gray>",
                    Placeholder.unparsed("ability", ability), Placeholder.unparsed("count", String.valueOf(count)))));
        }
    }

    private NamespacedKey resolveMobId(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        NamespacedKey id = lower.contains(":") ? NamespacedKey.fromString(lower) : new NamespacedKey(DEFAULT_NAMESPACE, lower);
        return id == null ? new NamespacedKey(DEFAULT_NAMESPACE, "invalid") : id;
    }

    private void sendMobUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin mob spawn <id></yellow> <gray>- invoque une variante à ta position</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin mob list</yellow> <gray>- liste les variantes chargées</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin mob inspect <id></yellow> <gray>- détail d'une variante</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin mob reload</yellow> <gray>- recharge les variantes depuis le disque</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin mob metrics</yellow> <gray>- métriques de spawn et de capacités</gray>"));
    }

    // ---- PNJ (identité stable, indépendante du nom affiché) -------------------

    private void handleNpc(Player player, String[] args) {
        if (args.length < 2) {
            sendNpcUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "tag" -> handleNpcTag(player, args);
            case "untag" -> handleNpcUntag(player);
            case "info" -> handleNpcInfo(player);
            default -> sendNpcUsage(player);
        }
    }

    /**
     * Marque l'entité visée d'un identifiant stable, indépendant de son nom personnalisé (cosmétique).
     * {@code args[2]} (optionnel) est l'id explicite souhaité, validé avant toute écriture — jamais
     * dérivé du nom affiché de l'entité (c'est cette dérivation, côté dialogue, qui provoquait
     * autrefois une IllegalArgumentException au clic sur une entité renommée avec une majuscule).
     * Sans id explicite, un identifiant {@code npc_<n>} est généré automatiquement.
     */
    private void handleNpcTag(Player player, String[] args) {
        Entity target = targetEntity(player);
        if (target == null) {
            sendNoEntityTarget(player);
            return;
        }
        Optional<String> existing = npcIdentityService.currentId(target);
        if (existing.isPresent()) {
            player.sendMessage(MM.deserialize(
                    "<yellow>Cette entité est déjà identifiée :</yellow> <white><id></white> "
                            + "<gray>(utilisez d'abord</gray> <yellow>/rpgadmin npc untag</yellow> <gray>pour la ré-identifier).</gray>",
                    Placeholder.unparsed("id", existing.get())));
            return;
        }

        String requestedId = null;
        if (args.length >= 3) {
            requestedId = args[2].toLowerCase(Locale.ROOT);
            if (!NpcIdentityService.isValidId(requestedId)) {
                player.sendMessage(MM.deserialize(
                        "<red>Identifiant invalide :</red> <white><id></white> "
                                + "<gray>(minuscules, chiffres, ., _, - uniquement).</gray>",
                        Placeholder.unparsed("id", requestedId)));
                return;
            }
        }

        npcIdentityService.tag(target, requestedId)
                .thenAccept(result -> runOnMainThread(() -> player.sendMessage(MM.deserialize(
                        "<green>Entité identifiée :</green> <white><id></white>" + citizensSuffix(target),
                        Placeholder.unparsed("id", result.npcId())))))
                .exceptionally(error -> {
                    runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Échec de l'identification (voir la console).</red>")));
                    return null;
                });
    }

    private void handleNpcUntag(Player player) {
        Entity target = targetEntity(player);
        if (target == null) {
            sendNoEntityTarget(player);
            return;
        }
        npcIdentityService.untag(target)
                .thenAccept(removed -> runOnMainThread(() -> {
                    if (removed) {
                        player.sendMessage(MM.deserialize("<green>Identifiant retiré.</green>"));
                    } else {
                        player.sendMessage(MM.deserialize("<gray>Cette entité n'est pas identifiée.</gray>"));
                    }
                }))
                .exceptionally(error -> {
                    runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Échec (voir la console).</red>")));
                    return null;
                });
    }

    private void handleNpcInfo(Player player) {
        Entity target = targetEntity(player);
        if (target == null) {
            sendNoEntityTarget(player);
            return;
        }
        Optional<String> id = npcIdentityService.currentId(target);
        if (id.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Cette entité n'est pas identifiée.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<white>Identifiant :</white> <gray><id></gray>" + citizensSuffix(target),
                Placeholder.unparsed("id", id.get())));
    }

    /** Suffixe informatif « (Citizens NPC #n) » si l'entité est un PNJ Citizens, chaîne vide sinon. */
    private String citizensSuffix(Entity entity) {
        return npcIdentityService.citizensNumericId(entity)
                .map(citizensId -> " <dark_gray>(Citizens NPC #" + citizensId + ")</dark_gray>")
                .orElse("");
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private @Nullable Entity targetEntity(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), NPC_REACH, entity -> entity != player);
        return result == null ? null : result.getHitEntity();
    }

    private void sendNoEntityTarget(Player player) {
        player.sendMessage(MM.deserialize("<red>Aucune entité visée à portée.</red>"));
    }

    private void sendNpcUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin npc tag [id]</yellow> <gray>- identifie l'entité visée (id auto-généré si omis)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin npc untag</yellow> <gray>- retire l'identifiant de l'entité visée</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin npc info</yellow> <gray>- affiche l'identifiant de l'entité visée</gray>"));
    }

    // ---- Spawn du village central ---------------------------------------------

    private void handleSpawn(Player player, String[] args) {
        if (args.length < 2) {
            sendSpawnUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> handleSpawnSet(player);
            case "tp" -> handleSpawnTp(player);
            default -> sendSpawnUsage(player);
        }
    }

    /** Capture la position (et l'orientation) exacte du joueur comme nouveau spawn, remplaçant l'ancien s'il existait. */
    private void handleSpawnSet(Player player) {
        if (!spawnService.set(player.getLocation())) {
            player.sendMessage(MM.deserialize("<red>Erreur d'écriture du spawn : voir la console.</red>"));
            return;
        }
        SpawnPoint point = spawnService.current().orElseThrow();
        player.sendMessage(MM.deserialize(
                "<green>Spawn du village défini :</green> <gray>(<world> <x>, <y>, <z>, yaw=<yaw>)</gray>",
                Placeholder.unparsed("world", point.world()),
                Placeholder.unparsed("x", String.format(Locale.ROOT, "%.1f", point.x())),
                Placeholder.unparsed("y", String.format(Locale.ROOT, "%.1f", point.y())),
                Placeholder.unparsed("z", String.format(Locale.ROOT, "%.1f", point.z())),
                Placeholder.unparsed("yaw", String.format(Locale.ROOT, "%.1f", point.yaw()))));
    }

    private void handleSpawnTp(Player player) {
        var location = spawnService.resolve();
        if (location.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Aucun spawn défini. Utilisez d'abord</red> <yellow>/rpgadmin spawn set</yellow>."));
            return;
        }
        // Plus de log TP-TRACE ad hoc ici : teleportAsync() déclenche PlayerTeleportEvent, capté
        // génériquement par WorldPortalTeleportListener#onTeleport (event=external_teleport) —
        // superflu et incohérent de dupliquer un log spécifique à cette seule commande.
        player.teleportAsync(location.get());
        player.sendMessage(MM.deserialize("<green>Téléporté au spawn du village.</green>"));
    }

    private void sendSpawnUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin spawn set</yellow> <gray>- définit le spawn du village à ta position actuelle</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin spawn tp</yellow> <gray>- te téléporte au spawn du village</gray>"));
    }

    // ---- Mondes supplémentaires (gestion minimale, voir docs-site/worlds.html) ------------------

    private void handleWorld(Player player, String[] args) {
        if (args.length < 2) {
            sendWorldUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> handleWorldCreate(player, args);
            case "tp" -> handleWorldTp(player, args);
            case "list" -> handleWorldList(player);
            default -> sendWorldUsage(player);
        }
    }

    /** Crée (première fois) ou recharge (dossier déjà présent) le monde, toujours en environnement NORMAL, seed aléatoire. */
    private void handleWorldCreate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin world create <name></yellow>"));
            return;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        switch (worldService.createOrLoad(name)) {
            case CREATED -> player.sendMessage(MM.deserialize(
                    "<green>Monde créé et chargé :</green> <white><name></white>", Placeholder.unparsed("name", name)));
            case ALREADY_LOADED -> player.sendMessage(MM.deserialize(
                    "<yellow>Ce monde est déjà chargé :</yellow> <white><name></white>", Placeholder.unparsed("name", name)));
            case INVALID_NAME -> player.sendMessage(MM.deserialize(
                    "<red>Nom de monde invalide :</red> <white><name></white> "
                            + "<gray>(minuscules, chiffres, . _ - uniquement).</gray>",
                    Placeholder.unparsed("name", name)));
            case CREATION_FAILED -> player.sendMessage(MM.deserialize(
                    "<red>Échec de la création du monde :</red> <white><name></white> <gray>(voir la console).</gray>",
                    Placeholder.unparsed("name", name)));
        }
    }

    private void handleWorldTp(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin world tp <name></yellow>"));
            return;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        Optional<World> world = worldService.find(name);
        if (world.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Monde non chargé :</red> <white><name></white> <gray>(utilisez d'abord</gray> "
                            + "<yellow>/rpgadmin world create <name></yellow><gray> pour le créer/charger).</gray>",
                    Placeholder.unparsed("name", name)));
            return;
        }
        // Plus de log TP-TRACE ad hoc ici : teleportAsync() déclenche PlayerTeleportEvent, capté
        // génériquement par WorldPortalTeleportListener#onTeleport (event=external_teleport) —
        // superflu et incohérent de dupliquer un log spécifique à cette seule commande.
        player.teleportAsync(world.get().getSpawnLocation());
        player.sendMessage(MM.deserialize(
                "<green>Téléporté au spawn du monde</green> <white><name></white>.", Placeholder.unparsed("name", name)));
    }

    private void handleWorldList(Player player) {
        List<World> worlds = worldService.loadedWorlds();
        player.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>monde(s) actuellement chargé(s) :</gray>",
                Placeholder.unparsed("count", String.valueOf(worlds.size()))));
        for (World world : worlds) {
            String managedTag = worldService.isManaged(world.getName())
                    ? " <dark_gray>(géré par RPGQuest)</dark_gray>" : "";
            player.sendMessage(MM.deserialize(
                    "<yellow>- <name></yellow> <gray>(<env>)</gray>" + managedTag,
                    Placeholder.unparsed("name", world.getName()), Placeholder.unparsed("env", world.getEnvironment().name())));
        }
    }

    private void sendWorldUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin world create <name></yellow> <gray>- crée (ou recharge) un monde NORMAL et le charge</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin world tp <name></yellow> <gray>- téléporte au spawn de ce monde (ex. </gray>"
                        + "<white>world</white><gray> pour revenir au monde principal)</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin world list</yellow> <gray>- liste les mondes actuellement chargés</gray>"));
    }

    // ---- Portails simples entre mondes (zone d'entrée -> spawn du monde destination) --------------

    private void handleWorldPortal(Player player, String[] args) {
        if (args.length < 2) {
            sendWorldPortalUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> handleWorldPortalCreate(player, args);
            case "info" -> handleWorldPortalInfo(player, args);
            case "list" -> handleWorldPortalList(player);
            case "enable" -> handleWorldPortalSetEnabled(player, args, true);
            case "disable" -> handleWorldPortalSetEnabled(player, args, false);
            case "delete" -> handleWorldPortalDelete(player, args);
            case "here" -> handleWorldPortalHere(player);
            case "debug" -> handleWorldPortalDebug(player, args);
            default -> sendWorldPortalUsage(player);
        }
    }

    /**
     * TODO(debug bug TP hub) : outil de diagnostic — liste TOUS les portails simples dont la zone
     * d'activation contient la position actuelle du joueur (contrairement à ce que le jeu utilise
     * réellement, {@code WorldPortalRegistry#portalAt}, qui ne renvoie que le premier trouvé). Une
     * zone superposée invisible à {@code /rpgadmin worldportal info} devient donc visible ici — voir
     * le Javadoc de {@code WorldPortalRegistry} pour l'anomalie que cet outil rend observable.
     */
    private void handleWorldPortalHere(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        var matches = worldPortalRegistry.portalsContaining(world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (matches.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<gray>Aucun portail simple à la position actuelle</gray> <white>(<world> <x>, <y>, <z>)</white><gray>.</gray>",
                    Placeholder.unparsed("world", world.getName()), Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                    Placeholder.unparsed("y", String.valueOf(loc.getBlockY())), Placeholder.unparsed("z", String.valueOf(loc.getBlockZ()))));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gold>Portails simples à la position actuelle</gold> <white>(<world> <x>, <y>, <z>)</white> <gray>:</gray>",
                Placeholder.unparsed("world", world.getName()), Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(loc.getBlockY())), Placeholder.unparsed("z", String.valueOf(loc.getBlockZ()))));
        for (WorldPortalDefinition portal : matches) {
            player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>bornes : (<minx>,<miny>,<minz>) → (<maxx>,<maxy>,<maxz>)</gray>"
                            + " <white>inside=true</white><disabled>",
                    Placeholder.unparsed("id", portal.id()),
                    Placeholder.unparsed("minx", String.valueOf(portal.minX())), Placeholder.unparsed("miny", String.valueOf(portal.minY())),
                    Placeholder.unparsed("minz", String.valueOf(portal.minZ())), Placeholder.unparsed("maxx", String.valueOf(portal.maxX())),
                    Placeholder.unparsed("maxy", String.valueOf(portal.maxY())), Placeholder.unparsed("maxz", String.valueOf(portal.maxZ())),
                    Placeholder.unparsed("disabled", portal.enabled() ? "" : " <red>(désactivé)</red>")));
        }
        if (matches.size() > 1) {
            player.sendMessage(MM.deserialize(
                    "<red><bold><count> portails superposés à cette position — voir le premier de la liste</bold> "
                            + "<gray>(ordre alphabétique des fichiers)</gray> <bold>: c'est celui qui s'active réellement en jeu.</bold></red>",
                    Placeholder.unparsed("count", String.valueOf(matches.size()))));
        }
    }

    private void handleWorldPortalDebug(Player player, String[] args) {
        if (args.length < 3) {
            sendWorldPortalDebugUsage(player);
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "show" -> {
                if (args.length < 4) {
                    player.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal debug show <id></yellow>"));
                    return;
                }
                String id = args[3].toLowerCase(Locale.ROOT);
                switch (worldPortalDebugService.show(id)) {
                    case SHOWN -> player.sendMessage(MM.deserialize(
                            "<green>Zone affichée (particules) :</green> <white><id></white>", Placeholder.unparsed("id", id)));
                    case ALREADY_VISIBLE -> player.sendMessage(MM.deserialize(
                            "<yellow>Déjà affichée :</yellow> <white><id></white>", Placeholder.unparsed("id", id)));
                    case UNKNOWN_PORTAL -> player.sendMessage(MM.deserialize(
                            "<red>Portail simple inconnu :</red> <white><id></white>", Placeholder.unparsed("id", id)));
                }
            }
            case "hide" -> {
                if (args.length < 4) {
                    player.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal debug hide <id></yellow>"));
                    return;
                }
                String id = args[3].toLowerCase(Locale.ROOT);
                switch (worldPortalDebugService.hide(id)) {
                    case HIDDEN -> player.sendMessage(MM.deserialize(
                            "<green>Zone masquée :</green> <white><id></white>", Placeholder.unparsed("id", id)));
                    case NOT_VISIBLE -> player.sendMessage(MM.deserialize(
                            "<gray>Cette zone n'était pas affichée :</gray> <white><id></white>", Placeholder.unparsed("id", id)));
                }
            }
            case "showall" -> {
                int shown = worldPortalDebugService.showAll();
                player.sendMessage(MM.deserialize(
                        "<green><count> zone(s) affichée(s).</green>", Placeholder.unparsed("count", String.valueOf(shown))));
            }
            case "hideall" -> {
                int hidden = worldPortalDebugService.hideAll();
                player.sendMessage(MM.deserialize(
                        "<green><count> zone(s) masquée(s).</green>", Placeholder.unparsed("count", String.valueOf(hidden))));
            }
            default -> sendWorldPortalDebugUsage(player);
        }
    }

    private void sendWorldPortalDebugUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin worldportal debug show <id></yellow> <gray>- affiche le contour (particules) d'un portail</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin worldportal debug hide <id></yellow> <gray>- masque le contour d'un portail</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin worldportal debug showall</yellow> <gray>- affiche tous les portails simples chargés</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin worldportal debug hideall</yellow> <gray>- masque tous les portails simples affichés</gray>"));
    }

    /**
     * Crée le portail depuis la sélection courante ({@code /rpgadmin zone wand}, même outil que
     * {@code /rpgadmin zone create}/{@code portal create}) : pos1/pos2 définissent la zone
     * d'activation dans le monde où se trouve l'admin (le Hub, en pratique), {@code
     * destinationWorld} est simplement un nom de monde — aucune position n'est enregistrée, le
     * spawn du monde destination est résolu à chaque activation.
     */
    private void handleWorldPortalCreate(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(MM.deserialize(
                    "<yellow>/rpgadmin worldportal create <id> <destinationWorld> [world_spawn|random_safe]</yellow>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        String destinationWorld = args[3].toLowerCase(Locale.ROOT);

        DestinationStrategy strategy = DestinationStrategy.WORLD_SPAWN;
        if (args.length >= 5) {
            try {
                strategy = DestinationStrategy.valueOf(args[4].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                player.sendMessage(MM.deserialize(
                        "<red>Stratégie de destination invalide :</red> <white><value></white> "
                                + "<gray>(valides : world_spawn, random_safe).</gray>",
                        Placeholder.unparsed("value", args[4])));
                return;
            }
        }

        var pos1 = zoneSelectionService.pos1(player.getUniqueId());
        var pos2 = zoneSelectionService.pos2(player.getUniqueId());
        if (pos1.isEmpty() || pos2.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Sélectionnez d'abord deux positions avec</red> <yellow>/rpgadmin zone wand</yellow>."));
            return;
        }
        Location a = pos1.get();
        Location b = pos2.get();
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            player.sendMessage(MM.deserialize("<red>Les deux positions doivent être dans le même monde.</red>"));
            return;
        }

        WorldPortalDefinition portal;
        try {
            portal = new WorldPortalDefinition(id, a.getWorld().getName(),
                    Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                    Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()),
                    destinationWorld, true, strategy);
        } catch (IllegalArgumentException e) {
            player.sendMessage(MM.deserialize(
                    "<red>Portail invalide :</red> <white><reason></white>", Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
            return;
        }

        switch (worldPortalRegistry.create(portal)) {
            case CREATED -> {
                zoneSelectionService.clear(player.getUniqueId());
                player.sendMessage(MM.deserialize(
                        "<green>Portail simple créé :</green> <white><id></white> <gray>(</gray><white><src></white>"
                                + " <gray>→</gray> <white><dst></white><gray>,</gray> <white><strategy></white><gray>)</gray>",
                        Placeholder.unparsed("id", id), Placeholder.unparsed("src", a.getWorld().getName()),
                        Placeholder.unparsed("dst", destinationWorld), Placeholder.unparsed("strategy", strategy.name())));
            }
            case DUPLICATE_ID -> player.sendMessage(MM.deserialize(
                    "<red>Un portail simple porte déjà l'id</red> <white><id></white>.", Placeholder.unparsed("id", id)));
            case OVERLAPS -> player.sendMessage(MM.deserialize(
                    "<red>Cette zone d'activation chevauche un portail simple existant dans ce monde.</red>"));
            case IO_ERROR -> player.sendMessage(MM.deserialize(
                    "<red>Erreur d'écriture : voir la console.</red>"));
        }
    }

    private void handleWorldPortalInfo(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal info <id></yellow>"));
            return;
        }
        Optional<WorldPortalDefinition> portalOpt = worldPortalRegistry.find(args[2].toLowerCase(Locale.ROOT));
        if (portalOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Portail simple inconnu.</red>"));
            return;
        }
        WorldPortalDefinition portal = portalOpt.get();
        int width = portal.maxX() - portal.minX() + 1;
        int height = portal.maxY() - portal.minY() + 1;
        int depth = portal.maxZ() - portal.minZ() + 1;
        double centerX = (portal.minX() + portal.maxX() + 1) / 2.0;
        double centerY = (portal.minY() + portal.maxY() + 1) / 2.0;
        double centerZ = (portal.minZ() + portal.maxZ() + 1) / 2.0;

        player.sendMessage(MM.deserialize("<gold>=== <id> ===</gold>", Placeholder.unparsed("id", portal.id())));
        player.sendMessage(MM.deserialize(
                "<white>Actif :</white> <gray><enabled></gray>", Placeholder.unparsed("enabled", String.valueOf(portal.enabled()))));
        player.sendMessage(MM.deserialize(
                "<white>Monde source :</white> <gray><world></gray>", Placeholder.unparsed("world", portal.world())));
        player.sendMessage(MM.deserialize(
                "<white>Monde destination :</white> <gray><dst></gray>", Placeholder.unparsed("dst", portal.destinationWorld())));
        player.sendMessage(MM.deserialize(
                "<white>Mode de destination :</white> <gray><strategy></gray>",
                Placeholder.unparsed("strategy", portal.destinationStrategy().name())));
        player.sendMessage(MM.deserialize(
                "<white>Bornes X :</white> <gray><minx> → <maxx></gray> <white>Y :</white> <gray><miny> → <maxy></gray> "
                        + "<white>Z :</white> <gray><minz> → <maxz></gray>",
                Placeholder.unparsed("minx", String.valueOf(portal.minX())), Placeholder.unparsed("maxx", String.valueOf(portal.maxX())),
                Placeholder.unparsed("miny", String.valueOf(portal.minY())), Placeholder.unparsed("maxy", String.valueOf(portal.maxY())),
                Placeholder.unparsed("minz", String.valueOf(portal.minZ())), Placeholder.unparsed("maxz", String.valueOf(portal.maxZ()))));
        player.sendMessage(MM.deserialize(
                "<white>Largeur × hauteur × profondeur :</white> <gray><w> × <h> × <d> blocs</gray>",
                Placeholder.unparsed("w", String.valueOf(width)), Placeholder.unparsed("h", String.valueOf(height)),
                Placeholder.unparsed("d", String.valueOf(depth))));
        player.sendMessage(MM.deserialize(
                "<white>Centre :</white> <gray>(<cx>, <cy>, <cz>)</gray>",
                Placeholder.unparsed("cx", String.format(Locale.ROOT, "%.1f", centerX)),
                Placeholder.unparsed("cy", String.format(Locale.ROOT, "%.1f", centerY)),
                Placeholder.unparsed("cz", String.format(Locale.ROOT, "%.1f", centerZ))));
        player.sendMessage(MM.deserialize(
                "<white>Répit d'arrivée :</white> <gray><ticks> ticks (~<seconds>s) — global, s'applique à tous les portails simples, "
                        + "pas seulement celui-ci</gray>",
                Placeholder.unparsed("ticks", String.valueOf(WorldPortalTeleportListener.arrivalGraceTicks())),
                Placeholder.unparsed("seconds", String.valueOf(WorldPortalTeleportListener.arrivalGraceTicks() / 20))));
    }

    private void handleWorldPortalList(Player player) {
        var portals = worldPortalRegistry.portals();
        if (portals.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucun portail simple chargé.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>portail(s) simple(s) :</gray>", Placeholder.unparsed("count", String.valueOf(portals.size()))));
        for (WorldPortalDefinition portal : portals) {
            player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<src> → <dst>, <strategy><disabled>)</gray>",
                    Placeholder.unparsed("id", portal.id()), Placeholder.unparsed("src", portal.world()),
                    Placeholder.unparsed("dst", portal.destinationWorld()),
                    Placeholder.unparsed("strategy", portal.destinationStrategy().name()),
                    Placeholder.unparsed("disabled", portal.enabled() ? "" : ", désactivé")));
        }
    }

    private void handleWorldPortalSetEnabled(Player player, String[] args, boolean enabled) {
        String usageVerb = enabled ? "enable" : "disable";
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal <verb> <id></yellow>",
                    Placeholder.unparsed("verb", usageVerb)));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        switch (worldPortalRegistry.setEnabled(id, enabled)) {
            case UPDATED -> player.sendMessage(MM.deserialize(
                    enabled ? "<green>Portail simple activé :</green> <white><id></white>"
                            : "<green>Portail simple désactivé :</green> <white><id></white>",
                    Placeholder.unparsed("id", id)));
            case UNCHANGED -> player.sendMessage(MM.deserialize(
                    enabled ? "<yellow>Ce portail est déjà activé :</yellow> <white><id></white>"
                            : "<yellow>Ce portail est déjà désactivé :</yellow> <white><id></white>",
                    Placeholder.unparsed("id", id)));
            case NOT_FOUND -> player.sendMessage(MM.deserialize(
                    "<red>Portail simple inconnu :</red> <white><id></white>", Placeholder.unparsed("id", id)));
            case IO_ERROR -> player.sendMessage(MM.deserialize(
                    "<red>Erreur d'écriture : voir la console.</red>"));
        }
    }

    /** Retire le portail (fichier + mémoire) — ne touche jamais au monde ni aux blocs, ni aux autres portails. */
    private void handleWorldPortalDelete(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal delete <id></yellow>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (worldPortalRegistry.delete(id)) {
            player.sendMessage(MM.deserialize("<green>Portail simple supprimé :</green> <white><id></white>", Placeholder.unparsed("id", id)));
        } else {
            player.sendMessage(MM.deserialize("<red>Portail simple inconnu :</red> <white><id></white>", Placeholder.unparsed("id", id)));
        }
    }

    private void sendWorldPortalUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin worldportal create <id> <destinationWorld></yellow> <gray>- crée un portail simple depuis la sélection</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal info <id></yellow> <gray>- détail d'un portail simple</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal list</yellow> <gray>- liste les portails simples</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal enable <id></yellow> <gray>- réactive un portail simple</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal disable <id></yellow> <gray>- désactive un portail simple (le déclenchement est bloqué, la config est conservée)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin worldportal delete <id></yellow> <gray>- supprime définitivement un portail simple</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin worldportal here</yellow> <gray>- liste TOUS les portails simples à ta position actuelle (diagnostic)</gray>"));
        sendWorldPortalDebugUsage(sender);
    }

    // ---- Story (conteneur logique de quêtes existantes, indépendant du moteur de quête) ----------
    //
    // Seule branche de /rpgadmin utilisable par la console : "story" cible un joueur passé en
    // argument, jamais la position de l'exécutant (voir onCommand). Aucune sous-commande "create" :
    // les stories se chargent depuis plugins/RPGQuest/stories/ (voir story.StoryRegistry), pas
    // créées en jeu comme les zones/portails.

    private void handleStory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendStoryUsage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "info" -> handleStoryInfo(sender, args);
            case "start" -> handleStoryStart(sender, args);
            case "reset" -> handleStoryReset(sender, args);
            case "resetwithquests" -> handleStoryResetWithQuests(sender, args);
            default -> sendStoryUsage(sender);
        }
    }

    // ---- Reset « nouveau joueur » (mission « reset admin complet pour retester le parcours ») ----

    /**
     * {@code /rpgadmin player resetnew <joueur> <confirm|preview>} — remet l'état RPGQuest d'<strong>un
     * seul</strong> joueur (en ligne ou hors ligne) dans l'équivalent fonctionnel d'un joueur qui
     * n'a jamais joué : quêtes, Stories, variables/unlocks (dont {@code CLAIM_TIER_1}), progression
     * RPG, découvertes de Waystones, cooldowns persistants, claim principal (données de protection,
     * pas les blocs). Voir {@link PlayerResetService} pour la portée exacte et
     * {@code docs/ADMIN_PLAYER_RESET.md}. Protection anti-erreur : le mot {@code confirm} est
     * obligatoire (même esprit que {@code /rpgadmin flatten confirm}) ; {@code preview} affiche ce
     * qui serait effacé <strong>sans rien modifier</strong> (dry-run).
     */
    private void handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("resetnew")) {
            sendPlayerResetUsage(sender);
            return;
        }
        String rawName = args[2];
        String action = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        if (action.equals("preview")) {
            handlePlayerResetPreview(sender, rawName);
            return;
        }
        if (!action.equals("confirm")) {
            sender.sendMessage(MM.deserialize(
                    "<gold>⚠ Reset « nouveau joueur » pour</gold> <white><name></white> <gold>—</gold> "
                            + "<gray>efface ses quêtes, Stories, variables/unlocks, progression RPG, "
                            + "découvertes de Waystones, cooldowns et son claim principal.</gray>",
                    Placeholder.unparsed("name", rawName)));
            sender.sendMessage(MM.deserialize(
                    "<yellow>Aperçu sans rien modifier :</yellow> <white>/rpgadmin player resetnew <name> preview</white>",
                    Placeholder.unparsed("name", rawName)));
            sender.sendMessage(MM.deserialize(
                    "<yellow>Confirme avec :</yellow> <white>/rpgadmin player resetnew <name> confirm</white>",
                    Placeholder.unparsed("name", rawName)));
            return;
        }
        resolveTargetPlayer(sender, rawName, (uuid, name) -> playerResetService.resetToNewPlayer(uuid, name)
                .thenAccept(summary -> runOnMainThread(() -> {
                    sender.sendMessage(MM.deserialize(
                            "<green>Reset « nouveau joueur » effectué pour</green> <white><name></white> <gray>(<uuid>)</gray>",
                            Placeholder.unparsed("name", name), Placeholder.unparsed("uuid", uuid.toString())));
                    sender.sendMessage(MM.deserialize(
                            "<gray>Réinitialisés : quêtes (actives/progression/terminées/suivie), Stories, "
                                    + "variables & unlocks (dont CLAIM_TIER_1), progression RPG (niveaux/XP), "
                                    + "découvertes de Waystones, cooldowns portails + Rune, claim principal "
                                    + "(données de protection uniquement).</gray>"));
                    if (summary.online()) {
                        sender.sendMessage(MM.deserialize(
                                "<gray>Inventaire : <n> objet(s) RPGQuest retiré(s) maintenant (inventaire vanilla intact).</gray>",
                                Placeholder.unparsed("n", String.valueOf(summary.inventoryItemsRemoved()))));
                    } else {
                        sender.sendMessage(MM.deserialize(
                                "<yellow>Joueur hors ligne :</yellow> <gray>l'inventaire RPGQuest sera nettoyé "
                                        + "automatiquement à sa prochaine connexion (avant le kit de départ).</gray>"));
                    }
                    sender.sendMessage(MM.deserialize(
                            "<gray>Conservés volontairement : profil/UUID, économie, backpacks/entitlements, "
                                    + "annonces de marché, blocs construits, Waystones globales.</gray>"));
                }))
                .exceptionally(error -> {
                    plugin.getSLF4JLogger().error("Échec de /rpgadmin player resetnew pour {}", rawName, error);
                    runOnMainThread(() -> sender.sendMessage(MM.deserialize(
                            "<red>Échec du reset (voir la console).</red>")));
                    return null;
                }));
    }

    /**
     * {@code /rpgadmin player resetnew <joueur> preview} — dry-run : liste ce qu'un reset réel
     * effacerait, catégorie par catégorie, <strong>sans effectuer aucune écriture</strong>
     * (délègue à {@link PlayerResetService#previewReset(UUID)}, lecture seule).
     */
    private void handlePlayerResetPreview(CommandSender sender, String rawName) {
        resolveTargetPlayer(sender, rawName, (uuid, name) -> playerResetService.previewReset(uuid)
                .thenAccept(preview -> runOnMainThread(() -> {
                    sender.sendMessage(MM.deserialize(
                            "<gold><bold>Aperçu du reset « nouveau joueur »</bold></gold> <gray>— <white><name></white> "
                                    + "(<uuid>, <status>)</gray>",
                            Placeholder.unparsed("name", name), Placeholder.unparsed("uuid", uuid.toString()),
                            Placeholder.unparsed("status", preview.online() ? "en ligne" : "hors ligne")));
                    sender.sendMessage(MM.deserialize(
                            "<yellow>Dry-run : aucune donnée n'a été modifiée.</yellow>"));
                    for (PlayerResetService.ResetCategory category : preview.categories()) {
                        String line;
                        if (!category.inspectable()) {
                            line = "<gray>- <white><label></white> : <gray>non applicable</gray> <dark_gray>(<detail>)</dark_gray>";
                        } else if (category.empty()) {
                            line = "<gray>- <white><label></white> : <dark_gray>rien à réinitialiser (<detail>)</dark_gray>";
                        } else {
                            line = "<gray>- <white><label></white> : <yellow><count></yellow> <dark_gray>(<detail>)</dark_gray>";
                        }
                        sender.sendMessage(MM.deserialize(line,
                                Placeholder.unparsed("label", category.label()),
                                Placeholder.unparsed("count", String.valueOf(category.count())),
                                Placeholder.unparsed("detail", category.detail())));
                    }
                    sender.sendMessage(MM.deserialize(
                            "<gray>Conservés dans tous les cas : profil/UUID, économie, backpacks/entitlements, "
                                    + "annonces de marché, blocs construits, Waystones globales.</gray>"));
                    sender.sendMessage(MM.deserialize(
                            "<yellow>Pour exécuter réellement :</yellow> <white>/rpgadmin player resetnew <name> confirm</white>",
                            Placeholder.unparsed("name", name)));
                }))
                .exceptionally(error -> {
                    plugin.getSLF4JLogger().error("Échec de /rpgadmin player resetnew preview pour {}", rawName, error);
                    runOnMainThread(() -> sender.sendMessage(MM.deserialize(
                            "<red>Échec de l'aperçu (voir la console).</red>")));
                    return null;
                }));
    }

    private void sendPlayerResetUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin player resetnew <joueur></yellow> <gray>- avertissement, ne fait rien</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin player resetnew <joueur> preview</yellow> <gray>- dry-run : liste ce qui serait effacé, sans rien modifier</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin player resetnew <joueur> confirm</yellow> <gray>- exécute le reset</gray>"));
    }

    // ---- Guides de Hub (issue #11 — diagnostic en lecture seule de la structure d'aide multi-Hub) ----

    /**
     * {@code /rpgadmin guide list|info <hub>} — inspection en lecture seule des Guides de Hub
     * configurés ({@code plugins/RPGQuest/hub-guides/*.yml}, voir {@code hub.HubGuideRegistry} et
     * {@code docs/HUB_GUIDE.md}). N'ouvre aucun dialogue, ne modifie rien : outil pour vérifier la
     * configuration d'aide/orientation d'un Hub avant/pendant l'ajout d'un nouveau Hub.
     */
    private void handleGuide(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            List<HubGuideDefinition> guides = hubGuideRegistry.all();
            if (guides.isEmpty()) {
                sender.sendMessage(MM.deserialize("<gray>Aucun Guide de Hub chargé (dossier hub-guides/ vide ou invalide).</gray>"));
                return;
            }
            sender.sendMessage(MM.deserialize("<gold><bold>Guides de Hub (<n>)</bold></gold>",
                    Placeholder.unparsed("n", String.valueOf(guides.size()))));
            for (HubGuideDefinition guide : guides) {
                sender.sendMessage(MM.deserialize(
                        "<yellow>- <hub></yellow> <gray>— mondes : <worlds> ; dialogue : <dialogue> (<node>)</gray>",
                        Placeholder.unparsed("hub", guide.hubId()),
                        Placeholder.unparsed("worlds", guide.worlds().isEmpty() ? "(aucun)" : String.join(", ", guide.worlds())),
                        Placeholder.unparsed("dialogue", guide.guideDialogueId().toString()),
                        Placeholder.unparsed("node", guide.helpNodeId())));
            }
            return;
        }
        if (!args[1].equalsIgnoreCase("info") || args.length < 3) {
            sender.sendMessage(MM.deserialize("<yellow>/rpgadmin guide <list|info <hub>></yellow>"));
            return;
        }
        Optional<HubGuideDefinition> guideOpt = hubGuideRegistry.forHub(args[2].toLowerCase(Locale.ROOT));
        if (guideOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Guide de Hub inconnu :</red> <white><hub></white>",
                    Placeholder.unparsed("hub", args[2])));
            return;
        }
        HubGuideDefinition guide = guideOpt.get();
        sender.sendMessage(MM.deserialize("<gold>=== Guide du Hub <hub> ===</gold>",
                Placeholder.unparsed("hub", guide.hubId())));
        sender.sendMessage(MM.deserialize("<white>Mondes :</white> <gray><worlds></gray>",
                Placeholder.unparsed("worlds", guide.worlds().isEmpty() ? "(aucun)" : String.join(", ", guide.worlds()))));
        sender.sendMessage(MM.deserialize("<white>Dialogue d'aide :</white> <gray><dialogue> (nœud <node>)</gray>",
                Placeholder.unparsed("dialogue", guide.guideDialogueId().toString()),
                Placeholder.unparsed("node", guide.helpNodeId())));
        if (!guide.welcome().isBlank()) {
            sender.sendMessage(MM.deserialize("<white>Accueil :</white> <gray><text></gray>",
                    Placeholder.unparsed("text", guide.welcome())));
        }
        if (!guide.specialty().isBlank()) {
            sender.sendMessage(MM.deserialize("<white>Spécialité :</white> <gray><text></gray>",
                    Placeholder.unparsed("text", guide.specialty())));
        }
        if (guide.referrals().isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>Aucune orientation vers un PNJ configurée.</gray>"));
        } else {
            sender.sendMessage(MM.deserialize("<white>Orientations :</white>"));
            for (HubGuideReferral referral : guide.referrals()) {
                sender.sendMessage(MM.deserialize(
                        "<gray>- <role> → <npc> : <note></gray>",
                        Placeholder.unparsed("role", referral.role()),
                        Placeholder.unparsed("npc", referral.npcName()),
                        Placeholder.unparsed("note", referral.note())));
            }
        }
    }

    private void handleStoryInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<yellow>/rpgadmin story info <joueur></yellow>"));
            return;
        }
        resolveTargetPlayer(sender, args[2], (uuid, name) ->
                storyService.info(uuid).thenAccept(infos -> runOnMainThread(() -> {
                    if (infos.isEmpty()) {
                        sender.sendMessage(MM.deserialize("<gray>Aucune story chargée.</gray>"));
                        return;
                    }
                    sender.sendMessage(MM.deserialize(
                            "<gold>=== Stories de <name> ===</gold>", Placeholder.unparsed("name", name)));
                    for (StoryService.StoryInfo info : infos) {
                        // Quête courante affichée uniquement si ACTIVE : NOT_STARTED n'a pas encore de
                        // position significative, COMPLETED a dépassé la fin de la liste (currentIndex
                        // == questIds().size(), jamais un index valide à afficher comme "quête courante").
                        String questSuffix = info.state() != StoryState.ACTIVE
                                ? ""
                                : " <gray>— quête courante :</gray> <white>" + info.story().questIds().get(info.currentIndex())
                                        + "</white> <gray>(" + (info.currentIndex() + 1) + "/" + info.story().questIds().size() + ")</gray>";
                        sender.sendMessage(MM.deserialize(
                                "<yellow>- <id></yellow> <gray>(<title>) :</gray> <white><state></white>" + questSuffix,
                                Placeholder.unparsed("id", info.story().id()),
                                Placeholder.unparsed("title", info.story().name().base()),
                                Placeholder.unparsed("state", info.state().name())));
                    }
                })));
    }

    private void handleStoryStart(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(MM.deserialize("<yellow>/rpgadmin story start <joueur> <storyId></yellow>"));
            return;
        }
        String storyId = args[3].toLowerCase(Locale.ROOT);
        resolveTargetPlayer(sender, args[2], (uuid, name) ->
                storyService.start(uuid, name, storyId).thenAccept(outcome -> runOnMainThread(() -> {
                    switch (outcome) {
                        case STARTED -> sender.sendMessage(MM.deserialize(
                                "<green>Story démarrée :</green> <white><id></white> <gray>pour</gray> <white><name></white>",
                                Placeholder.unparsed("id", storyId), Placeholder.unparsed("name", name)));
                        case ALREADY_ACTIVE -> sender.sendMessage(MM.deserialize(
                                "<yellow>Story déjà active pour <name> :</yellow> <white><id></white>",
                                Placeholder.unparsed("name", name), Placeholder.unparsed("id", storyId)));
                        case ALREADY_COMPLETED -> sender.sendMessage(MM.deserialize(
                                "<yellow>Story déjà terminée pour <name> :</yellow> <white><id></white> "
                                        + "<gray>(</gray><yellow>/rpgadmin story reset</yellow><gray> pour recommencer).</gray>",
                                Placeholder.unparsed("name", name), Placeholder.unparsed("id", storyId)));
                        case UNKNOWN_STORY -> sender.sendMessage(MM.deserialize(
                                "<red>Story inconnue :</red> <white><id></white>", Placeholder.unparsed("id", storyId)));
                    }
                })));
    }

    private void handleStoryReset(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(MM.deserialize("<yellow>/rpgadmin story reset <joueur> <storyId|all></yellow>"));
            return;
        }
        String target = args[3].toLowerCase(Locale.ROOT);
        resolveTargetPlayer(sender, args[2], (uuid, name) ->
                storyService.reset(uuid, target).thenAccept(outcome -> runOnMainThread(() -> {
                    switch (outcome) {
                        case RESET_ALL -> sender.sendMessage(MM.deserialize(
                                "<green>Toute la progression Story de <name> a été réinitialisée.</green>",
                                Placeholder.unparsed("name", name)));
                        case RESET_ONE -> sender.sendMessage(MM.deserialize(
                                "<green>Story réinitialisée :</green> <white><id></white> <gray>pour</gray> <white><name></white>",
                                Placeholder.unparsed("id", target), Placeholder.unparsed("name", name)));
                        case UNKNOWN_STORY -> sender.sendMessage(MM.deserialize(
                                "<red>Story inconnue :</red> <white><id></white>", Placeholder.unparsed("id", target)));
                    }
                })));
    }

    /**
     * Outil ADMIN/DEBUG ciblé (mission point 5) : contrairement à {@code reset}, remet AUSSI les
     * quêtes de cette story dans un état rejouable — jamais {@code reset ... all}, jamais les autres
     * quêtes du joueur. Voir {@link StoryService#resetWithQuests}.
     */
    private void handleStoryResetWithQuests(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(MM.deserialize("<yellow>/rpgadmin story resetwithquests <joueur> <storyId></yellow>"));
            return;
        }
        String storyId = args[3].toLowerCase(Locale.ROOT);
        resolveTargetPlayer(sender, args[2], (uuid, name) ->
                storyService.resetWithQuests(uuid, storyId).thenAccept(outcome -> runOnMainThread(() -> {
                    switch (outcome) {
                        case RESET -> sender.sendMessage(MM.deserialize(
                                "<green>Story ET ses quêtes réinitialisées :</green> <white><id></white> <gray>pour</gray> <white><name></white>",
                                Placeholder.unparsed("id", storyId), Placeholder.unparsed("name", name)));
                        case UNKNOWN_STORY -> sender.sendMessage(MM.deserialize(
                                "<red>Story inconnue :</red> <white><id></white>", Placeholder.unparsed("id", storyId)));
                    }
                })));
    }

    private void sendStoryUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin story info <joueur></yellow> <gray>- état de toutes les stories pour ce joueur</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin story start <joueur> <storyId></yellow> <gray>- démarre une story</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin story reset <joueur> <storyId|all></yellow> <gray>- réinitialise une (ou toutes) story(ies), jamais ses quêtes</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin story resetwithquests <joueur> <storyId></yellow> <gray>- réinitialise UNE story ET ses quêtes associées (jamais les autres quêtes du joueur)</gray>"));
    }

    /**
     * Résout {@code rawName} (en ligne d'abord, sinon hors-ligne de façon asynchrone — jamais
     * bloquant sur le thread principal) puis exécute {@code action} avec son UUID et son nom
     * résolu, sur le thread principal dans les deux cas. Même patron que {@code
     * RPGQuestCommand#handleProfile} : {@code story} doit pouvoir cibler un joueur hors ligne
     * (outil admin/debug, pas une action en jeu).
     */
    private void resolveTargetPlayer(CommandSender sender, String rawName, BiConsumer<UUID, String> action) {
        Player online = plugin.getServer().getPlayerExact(rawName);
        if (online != null) {
            action.accept(online.getUniqueId(), online.getName());
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(rawName);
            String resolvedName = offline.getName() != null ? offline.getName() : rawName;
            plugin.getServer().getScheduler().runTask(plugin, () -> action.accept(offline.getUniqueId(), resolvedName));
        });
    }

    private void handleZone(Player player, String[] args) {
        if (args.length < 2) {
            sendZoneUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "wand" -> handleZoneWand(player);
            case "create" -> handleZoneCreate(player, args);
            case "delete" -> handleZoneDelete(player, args);
            case "list" -> handleZoneList(player);
            case "info" -> handleZoneInfo(player, args);
            default -> sendZoneUsage(player);
        }
    }

    private void handleZoneWand(Player player) {
        player.getInventory().addItem(zoneSelectionService.createWandItem());
        player.sendMessage(MM.deserialize(
                "<green>Outil de sélection reçu.</green> <gray>Clic gauche = position 1, clic droit = position 2.</gray>"));
    }

    private void handleZoneCreate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin zone create <id></yellow>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);

        var pos1 = zoneSelectionService.pos1(player.getUniqueId());
        var pos2 = zoneSelectionService.pos2(player.getUniqueId());
        if (pos1.isEmpty() || pos2.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Sélectionnez d'abord deux positions avec</red> <yellow>/rpgadmin zone wand</yellow>."));
            return;
        }
        Location a = pos1.get();
        Location b = pos2.get();
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            player.sendMessage(MM.deserialize("<red>Les deux positions doivent être dans le même monde.</red>"));
            return;
        }

        ZoneDefinition zone;
        try {
            zone = new ZoneDefinition(id, a.getWorld().getName(),
                    Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                    Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()),
                    ZoneFlags.defaults());
        } catch (IllegalArgumentException e) {
            player.sendMessage(MM.deserialize(
                    "<red>Id de zone invalide :</red> <white><reason></white>", Placeholder.unparsed("reason", String.valueOf(e.getMessage()))));
            return;
        }

        switch (zoneRegistry.create(zone)) {
            case CREATED -> {
                zoneSelectionService.clear(player.getUniqueId());
                player.sendMessage(MM.deserialize("<green>Zone créée :</green> <white><id></white>", Placeholder.unparsed("id", id)));
            }
            case DUPLICATE_ID -> player.sendMessage(MM.deserialize(
                    "<red>Une zone porte déjà l'id</red> <white><id></white>.", Placeholder.unparsed("id", id)));
            case OVERLAPS -> player.sendMessage(MM.deserialize(
                    "<red>Cette zone chevauche une zone existante dans ce monde.</red>"));
            case IO_ERROR -> player.sendMessage(MM.deserialize(
                    "<red>Erreur d'écriture : voir la console.</red>"));
        }
    }

    private void handleZoneDelete(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin zone delete <id></yellow>"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        if (zoneRegistry.delete(id)) {
            player.sendMessage(MM.deserialize("<green>Zone supprimée :</green> <white><id></white>", Placeholder.unparsed("id", id)));
        } else {
            player.sendMessage(MM.deserialize("<red>Zone inconnue :</red> <white><id></white>", Placeholder.unparsed("id", id)));
        }
    }

    private void handleZoneList(Player player) {
        var zones = zoneRegistry.zones();
        if (zones.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucune zone chargée.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>zone(s) :</gray>", Placeholder.unparsed("count", String.valueOf(zones.size()))));
        for (ZoneDefinition zone : zones) {
            player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<world>)</gray>",
                    Placeholder.unparsed("id", zone.id()), Placeholder.unparsed("world", zone.world())));
        }
    }

    private void handleZoneInfo(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MM.deserialize("<yellow>/rpgadmin zone info <id></yellow>"));
            return;
        }
        var zoneOpt = zoneRegistry.find(args[2].toLowerCase(Locale.ROOT));
        if (zoneOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Zone inconnue.</red>"));
            return;
        }
        ZoneDefinition zone = zoneOpt.get();
        player.sendMessage(MM.deserialize("<gold>=== <id> ===</gold>", Placeholder.unparsed("id", zone.id())));
        player.sendMessage(MM.deserialize(
                "<white>Monde :</white> <gray><world></gray>", Placeholder.unparsed("world", zone.world())));
        player.sendMessage(MM.deserialize(
                "<white>Bornes :</white> <gray>(<minx>, <miny>, <minz>) → (<maxx>, <maxy>, <maxz>)</gray>",
                Placeholder.unparsed("minx", String.valueOf(zone.minX())), Placeholder.unparsed("miny", String.valueOf(zone.minY())),
                Placeholder.unparsed("minz", String.valueOf(zone.minZ())), Placeholder.unparsed("maxx", String.valueOf(zone.maxX())),
                Placeholder.unparsed("maxy", String.valueOf(zone.maxY())), Placeholder.unparsed("maxz", String.valueOf(zone.maxZ()))));
        ZoneFlags f = zone.flags();
        player.sendMessage(MM.deserialize(
                "<white>Flags :</white> <gray>pvp=<pvp> break=<brk> place=<plc> explosions=<exp> feu=<fire> lave=<lava> pistons=<pst> spawn=<spw> "
                        + "portes=<drs> boutons=<btn> leviers=<lvr> pnj=<npc> conteneurs=<cnt></gray>",
                Placeholder.unparsed("pvp", String.valueOf(f.allowPvp())), Placeholder.unparsed("brk", String.valueOf(f.allowBlockBreak())),
                Placeholder.unparsed("plc", String.valueOf(f.allowBlockPlace())), Placeholder.unparsed("exp", String.valueOf(f.allowExplosions())),
                Placeholder.unparsed("fire", String.valueOf(f.allowFire())), Placeholder.unparsed("lava", String.valueOf(f.allowLava())),
                Placeholder.unparsed("pst", String.valueOf(f.allowPistonsAcrossBorder())), Placeholder.unparsed("spw", String.valueOf(f.allowHostileSpawn())),
                Placeholder.unparsed("drs", String.valueOf(f.allowDoors())), Placeholder.unparsed("btn", String.valueOf(f.allowButtons())),
                Placeholder.unparsed("lvr", String.valueOf(f.allowLevers())), Placeholder.unparsed("npc", String.valueOf(f.allowNpcInteract())),
                Placeholder.unparsed("cnt", String.valueOf(f.allowPublicContainers()))));
        player.sendMessage(MM.deserialize(
                "<white>Flags (sécurité) :</white> <gray>dégâts mobs=<hdmg> dégâts environnement=<edmg> dégâts PNJ=<ndmg> jour figé=<day></gray>",
                Placeholder.unparsed("hdmg", String.valueOf(f.allowHostileDamage())),
                Placeholder.unparsed("edmg", String.valueOf(f.allowEnvironmentalDamage())),
                Placeholder.unparsed("ndmg", String.valueOf(f.allowNpcDamage())),
                Placeholder.unparsed("day", String.valueOf(f.forceDay()))));
    }

    private void sendZoneUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin zone wand</yellow> <gray>- outil de sélection (clic gauche/droit)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin zone create <id></yellow> <gray>- crée une zone depuis la sélection</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin zone delete <id></yellow> <gray>- supprime une zone</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin zone list</yellow> <gray>- liste les zones</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin zone info <id></yellow> <gray>- détail d'une zone</gray>"));
    }

    private void handleFlatten(Player player, String[] args) {
        if (args.length < 2) {
            sendFlattenUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "confirm" -> handleConfirm(player);
            case "cancel" -> handleCancel(player);
            case "undo" -> handleUndo(player);
            default -> handlePreview(player, args);
        }
    }

    private void handlePreview(Player player, String[] args) {
        Integer radius = parseInt(args[1]);
        if (radius == null) {
            player.sendMessage(MM.deserialize(
                    "<red>Rayon invalide :</red> <white><value></white>", Placeholder.unparsed("value", args[1])));
            return;
        }
        Integer height = null;
        if (args.length >= 3) {
            height = parseInt(args[2]);
            if (height == null) {
                player.sendMessage(MM.deserialize(
                        "<red>Hauteur invalide :</red> <white><value></white>", Placeholder.unparsed("value", args[2])));
                return;
            }
        }

        FlattenService.PreviewResult result = flattenService.preview(player, radius, height);
        switch (result.outcome()) {
            case CREATED -> {
                FlattenEstimate estimate = result.estimate();
                player.sendMessage(MM.deserialize("""
                        <gold>=== Aperçu d'aplatissement ===</gold>""".stripIndent()));
                player.sendMessage(MM.deserialize(
                        "<white>Forme :</white> <gray><shape></gray> <white>Rayon :</white> <gray><radius></gray> <white>Hauteur :</white> <gray><y></gray>",
                        Placeholder.unparsed("shape", estimate.shape().name()),
                        Placeholder.unparsed("radius", String.valueOf(estimate.radius())),
                        Placeholder.unparsed("y", String.valueOf(estimate.y()))));
                player.sendMessage(MM.deserialize(
                        "<white>Colonnes :</white> <gray><columns></gray> <white>Blocs (estimation majorante) :</white> <gray><blocks></gray>",
                        Placeholder.unparsed("columns", String.valueOf(estimate.columnCount())),
                        Placeholder.unparsed("blocks", String.valueOf(estimate.blockEstimate()))));
                player.sendMessage(MM.deserialize(
                        "<yellow>/rpgadmin flatten confirm</yellow> <gray>pour exécuter, ou</gray> "
                                + "<yellow>/rpgadmin flatten cancel</yellow> <gray>pour annuler.</gray>"));
            }
            case INVALID_RADIUS -> player.sendMessage(MM.deserialize(
                    "<red>Rayon invalide (doit être entre 1 et le maximum configuré).</red>"));
            case INVALID_HEIGHT -> player.sendMessage(MM.deserialize(
                    "<red>Hauteur hors des limites du monde.</red>"));
            case FORBIDDEN_WORLD -> player.sendMessage(MM.deserialize(
                    "<red>L'aplatissement est désactivé dans ce monde.</red>"));
            case ALREADY_PENDING -> player.sendMessage(MM.deserialize(
                    "<yellow>Un aperçu est déjà en attente de confirmation.</yellow>"));
            case ALREADY_ACTIVE -> player.sendMessage(MM.deserialize(
                    "<red>Un aplatissement est déjà en cours ; attendez sa fin ou annulez-le.</red>"));
        }
    }

    private void handleConfirm(Player player) {
        switch (flattenService.confirm(player)) {
            case STARTED -> player.sendMessage(MM.deserialize("<green>Aplatissement lancé.</green>"));
            case NO_PENDING -> player.sendMessage(MM.deserialize(
                    "<red>Aucun aperçu en attente. Lancez d'abord</red> <yellow>/rpgadmin flatten <rayon></yellow>."));
            case EXPIRED -> player.sendMessage(MM.deserialize(
                    "<red>L'aperçu a expiré. Relancez</red> <yellow>/rpgadmin flatten <rayon></yellow>."));
            case ALREADY_ACTIVE -> player.sendMessage(MM.deserialize(
                    "<red>Un aplatissement est déjà en cours.</red>"));
        }
    }

    private void handleCancel(Player player) {
        switch (flattenService.cancel(player)) {
            case CANCELLED_PENDING -> player.sendMessage(MM.deserialize("<green>Aperçu annulé.</green>"));
            case CANCELLED_ACTIVE -> player.sendMessage(MM.deserialize(
                    "<green>Aplatissement en cours annulé</green> <gray>(le travail déjà effectué reste ; utilisez</gray> "
                            + "<yellow>/rpgadmin flatten undo</yellow> <gray>pour l'annuler).</gray>"));
            case NOTHING_TO_CANCEL -> player.sendMessage(MM.deserialize(
                    "<gray>Rien à annuler.</gray>"));
        }
    }

    private void handleUndo(Player player) {
        switch (flattenService.undo(player)) {
            case UNDONE -> player.sendMessage(MM.deserialize("<green>Dernier aplatissement annulé (undo).</green>"));
            case NOTHING_TO_UNDO -> player.sendMessage(MM.deserialize(
                    "<gray>Aucun aplatissement à annuler.</gray>"));
            case OPERATION_IN_PROGRESS -> player.sendMessage(MM.deserialize(
                    "<red>Attendez la fin (ou annulez) l'aplatissement en cours avant d'annuler.</red>"));
        }
    }

    private @Nullable Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendUsage(CommandSender sender) {
        sendFlattenUsage(sender);
        sendZoneUsage(sender);
        sendPortalUsage(sender);
        sendMobUsage(sender);
        sendNpcUsage(sender);
        sendSpawnUsage(sender);
        sendWorldUsage(sender);
        sendWorldPortalUsage(sender);
        sendStoryUsage(sender);
    }

    private void sendFlattenUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin flatten <rayon> [hauteur]</yellow> <gray>- aperçu d'aplatissement centré sur vous</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin flatten confirm</yellow> <gray>- exécute l'aperçu en attente</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin flatten cancel</yellow> <gray>- annule l'aperçu ou l'opération en cours</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin flatten undo</yellow> <gray>- annule le dernier aplatissement</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("flatten")) {
            return FLATTEN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zone")) {
            return ZONE_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("zone")
                && (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("info"))) {
            return zoneRegistry.zones().stream().map(ZoneDefinition::id)
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("portal")) {
            return PORTAL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("portal")
                && (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("info")
                        || args[1].equalsIgnoreCase("setdestination"))) {
            return portalRegistry.portals().stream().map(PortalDefinition::id)
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mob")) {
            return MOB_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mob")
                && (args[1].equalsIgnoreCase("spawn") || args[1].equalsIgnoreCase("inspect"))) {
            return mobRegistry.definitions().stream().map(SpecialMobDefinition::id).map(NamespacedKey::toString)
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            return NPC_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return SPAWN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            return WORLD_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("world") && args[1].equalsIgnoreCase("tp")) {
            return worldService.loadedWorlds().stream().map(World::getName)
                    .filter(name -> name.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("worldportal")) {
            return WORLD_PORTAL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("worldportal")
                && (args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("enable")
                        || args[1].equalsIgnoreCase("disable") || args[1].equalsIgnoreCase("delete"))) {
            return worldPortalRegistry.portals().stream().map(WorldPortalDefinition::id)
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("worldportal") && args[1].equalsIgnoreCase("debug")) {
            return WORLD_PORTAL_DEBUG_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("worldportal") && args[1].equalsIgnoreCase("debug")
                && (args[2].equalsIgnoreCase("show") || args[2].equalsIgnoreCase("hide"))) {
            return worldPortalRegistry.portals().stream().map(WorldPortalDefinition::id)
                    .filter(id -> id.startsWith(args[3].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("story")) {
            return STORY_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("waystone")) {
            return WAYSTONE_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("waystone") && args[1].equalsIgnoreCase("reset")) {
            return List.of("discoveries").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("waystone") && args[1].equalsIgnoreCase("tp")) {
            return waystoneService.all().stream().map(Waystone::id)
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return PLAYER_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("player") && args[1].equalsIgnoreCase("resetnew")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("player") && args[1].equalsIgnoreCase("resetnew")) {
            return List.of("confirm", "preview").stream().filter(s -> s.startsWith(args[3].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("guide")) {
            return GUIDE_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("guide") && args[1].equalsIgnoreCase("info")) {
            return hubGuideRegistry.all().stream().map(HubGuideDefinition::hubId)
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("story")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("story")
                && (args[1].equalsIgnoreCase("start") || args[1].equalsIgnoreCase("reset")
                        || args[1].equalsIgnoreCase("resetwithquests"))) {
            List<String> ids = new ArrayList<>(
                    storyService.stories().stream().map(StoryDefinition::id).toList());
            if (args[1].equalsIgnoreCase("reset")) {
                ids.add("all");
            }
            return ids.stream().filter(id -> id.startsWith(args[3].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
