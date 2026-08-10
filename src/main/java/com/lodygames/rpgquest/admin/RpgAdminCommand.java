package com.lodygames.rpgquest.admin;

import com.lodygames.rpgquest.mob.SpecialMobLoadIssue;
import com.lodygames.rpgquest.mob.SpecialMobLoadReport;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.travel.YamlDestinationRegistry;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.ZoneSelectionService;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("flatten", "zone", "portal", "mob", "npc");
    private static final List<String> FLATTEN_SUBCOMMANDS = List.of("confirm", "cancel", "undo");
    private static final List<String> ZONE_SUBCOMMANDS = List.of("create", "delete", "list", "info", "wand");
    private static final List<String> PORTAL_SUBCOMMANDS = List.of("create", "delete", "list", "info", "setdestination");
    private static final List<String> MOB_SUBCOMMANDS = List.of("spawn", "list", "inspect", "reload", "metrics");
    private static final List<String> NPC_SUBCOMMANDS = List.of("tag", "untag", "info");
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

    public RpgAdminCommand(FlattenService flattenService, ZoneRegistry zoneRegistry, ZoneSelectionService zoneSelectionService,
                            YamlPortalRegistry portalRegistry, YamlDestinationRegistry destinationRegistry,
                            SpecialMobRegistry mobRegistry, SpecialMobService mobService, NpcIdentityService npcIdentityService) {
        this.flattenService = flattenService;
        this.zoneRegistry = zoneRegistry;
        this.zoneSelectionService = zoneSelectionService;
        this.portalRegistry = portalRegistry;
        this.destinationRegistry = destinationRegistry;
        this.mobRegistry = mobRegistry;
        this.mobService = mobService;
        this.npcIdentityService = npcIdentityService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", PERMISSION)));
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
        } else {
            sendUsage(player);
        }
        return true;
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

        npcIdentityService.tag(target, requestedId).thenAccept(result -> player.sendMessage(MM.deserialize(
                        "<green>Entité identifiée :</green> <white><id></white>",
                        Placeholder.unparsed("id", result.npcId()))))
                .exceptionally(error -> {
                    player.sendMessage(MM.deserialize("<red>Échec de l'identification (voir la console).</red>"));
                    return null;
                });
    }

    private void handleNpcUntag(Player player) {
        Entity target = targetEntity(player);
        if (target == null) {
            sendNoEntityTarget(player);
            return;
        }
        if (npcIdentityService.untag(target)) {
            player.sendMessage(MM.deserialize("<green>Identifiant retiré.</green>"));
        } else {
            player.sendMessage(MM.deserialize("<gray>Cette entité n'est pas identifiée.</gray>"));
        }
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
                "<white>Identifiant :</white> <gray><id></gray>", Placeholder.unparsed("id", id.get())));
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
        return List.of();
    }
}
