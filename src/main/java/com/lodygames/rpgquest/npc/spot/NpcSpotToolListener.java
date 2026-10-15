package com.lodygames.rpgquest.npc.spot;

import com.lodygames.rpgquest.npc.BukkitNpcPlacementProbe;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clic droit avec l'outil « Emplacements PNJ » : enregistre <strong>immédiatement</strong> un
 * emplacement libre au-dessus du bloc visé (issue #381, correction du 15/10).
 *
 * <h2>Différence volontaire avec l'outil de construction</h2>
 *
 * <p>L'outil de construction ouvre une enclume pour le nom (#227). Ici, le propriétaire demande
 * l'inverse : aucun nom, aucune saisie, aucune seconde sélection. L'emplacement naît avec un nom
 * provisoire numéroté (« Emplacement PNJ 12 ») et se renomme dans PlugAdmin. Un clic de travers se
 * supprime depuis la même page.</p>
 *
 * <h2>Ce que le clic calcule</h2>
 *
 * <ul>
 *   <li>Position : le centre de la case <em>au-dessus</em> du bloc visé, quelle que soit la face
 *       cliquée — c'est le bloc visé qui porte le PNJ, pas les pieds de l'administrateur.</li>
 *   <li>Orientation : le PNJ regardera l'administrateur au moment du clic ; elle se corrige
 *       ensuite dans PlugAdmin.</li>
 * </ul>
 *
 * <h2>Refus, avant toute écriture</h2>
 *
 * <p>Monde qui n'accepte pas de PNJ, hauteur hors limites, bloc sans sol plein ou sans deux cases
 * libres au-dessus (liquide, portail…), parcelle de joueur : rien n'est écrit et la raison est
 * dite. Ce sont les mêmes règles que le placement depuis PlugAdmin, qui les revérifie de toute
 * façon au moment de placer.</p>
 *
 * <h2>Anti-doublon</h2>
 *
 * <p>Trois couches : la main secondaire est ignorée (Paper émet un événement par main), un
 * anti-rebond par joueur absorbe le double événement d'un même clic, et le service refuse une
 * deuxième réservation du même bloc pendant que la première s'écrit, puis renvoie l'existant.</p>
 *
 * <h2>Pas d'interférence</h2>
 *
 * <p>L'objet n'est reconnu que par PDC ({@link NpcSpotTool#isTool}) : tout autre objet — hache
 * WorldEdit, bâton Citizens, outil de construction, porte-armure ordinaire — n'est jamais vu ici.
 * Les clics sur une entité (sélection Citizens) ne passent pas par cet événement.
 * {@code ignoreCancelled = false} comme les autres outils : une protection qui annule l'interaction
 * avant nous ne doit pas rendre l'outil muet.</p>
 */
public final class NpcSpotToolListener implements Listener {

    /** Fenêtre d'anti-rebond : un clic droit émet couramment deux événements rapprochés. */
    static final long DEBOUNCE_MILLIS = 350;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Zone où aucun PNJ ne sera placé (parcelle de joueur) : son identifiant, ou vide. */
    @FunctionalInterface
    public interface ForbiddenArea {
        Optional<String> at(String world, int x, int y, int z);
    }

    private final NpcSpotService service;
    private final Supplier<Set<String>> allowedWorlds;
    private final ForbiddenArea forbiddenArea;
    private final Function<World, NpcPlacementPlanner.Probe> probes;
    private final Executor mainThread;
    private final LongSupplier clockMillis;
    private final Logger logger;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public NpcSpotToolListener(NpcSpotService service, Supplier<Set<String>> allowedWorlds,
                               ForbiddenArea forbiddenArea, Executor mainThread, LongSupplier clockMillis,
                               Logger logger) {
        this(service, allowedWorlds, forbiddenArea, world -> new BukkitNpcPlacementProbe(world, Set.of()),
                mainThread, clockMillis, logger);
    }

    /** {@code probes} lit les blocs du monde ; remplaçable en test (MockBukkit n'implémente pas tout). */
    NpcSpotToolListener(NpcSpotService service, Supplier<Set<String>> allowedWorlds, ForbiddenArea forbiddenArea,
                        Function<World, NpcPlacementPlanner.Probe> probes, Executor mainThread,
                        LongSupplier clockMillis, Logger logger) {
        this.service = service;
        this.allowedWorlds = allowedWorlds;
        this.forbiddenArea = forbiddenArea == null ? (w, x, y, z) -> Optional.empty() : forbiddenArea;
        this.probes = probes;
        this.mainThread = mainThread;
        this.clockMillis = clockMillis;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !NpcSpotTool.isTool(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        // L'outil ne pose jamais de porte-armure et ne casse jamais de bloc, permission ou non.
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
        }
        if (!RpgPermissions.canRunNpcAction(player, RpgPermissions.ADMIN_NPC_SPOT)) {
            if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
                player.sendMessage(MM.deserialize("<red>Permission manquante :</red> <white><permission></white>",
                        Placeholder.unparsed("permission", RpgPermissions.ADMIN_NPC_SPOT)));
            }
            return;
        }
        if (action != Action.RIGHT_CLICK_BLOCK) {
            if (action != Action.PHYSICAL) {
                player.sendMessage(MM.deserialize("<gray>Visez un bloc et faites <white>clic droit</white> : "
                        + "un emplacement de PNJ est enregistré juste au-dessus.</gray>"));
            }
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !acceptClick(player.getUniqueId())) {
            return;
        }
        mark(player, clicked);
    }

    private void mark(Player player, Block clicked) {
        World world = clicked.getWorld();
        String worldName = world.getName();
        int x = clicked.getX();
        int y = clicked.getY() + 1;
        int z = clicked.getZ();

        String refusal = refusal(world, x, y, z);
        if (refusal != null) {
            player.sendMessage(MM.deserialize("<red>Aucun emplacement créé :</red> <gray><reason></gray>",
                    Placeholder.unparsed("reason", refusal)));
            return;
        }
        double sx = x + 0.5;
        double sz = z + 0.5;
        float yaw = yawTowards(sx, sz, player.getLocation());
        service.create(worldName, sx, y, sz, yaw, 0f, "", player.getName(), null)
                .thenAccept(result -> mainThread.execute(() -> report(player, result)))
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Enregistrement d'un emplacement de PNJ impossible", error);
                    mainThread.execute(() -> player.sendMessage(MM.deserialize(
                            "<red>Échec de l'enregistrement (voir la console). Rien n'a été créé.</red>")));
                    return null;
                });
    }

    /** Motif de refus lisible, ou {@code null} si un PNJ peut se tenir en (x, y, z). */
    private String refusal(World world, int x, int y, int z) {
        String worldName = world.getName();
        Set<String> allowed = allowedWorlds.get();
        if (allowed.stream().noneMatch(w -> w.equalsIgnoreCase(worldName))) {
            return "le monde « " + worldName + " » n'accepte pas de PNJ (mondes possibles : "
                    + String.join(", ", allowed) + ").";
        }
        if (y + NpcPlacementPlanner.REQUIRED_CLEARANCE > world.getMaxHeight()) {
            return "trop haut, il n'y a pas la place pour un PNJ au-dessus de ce bloc.";
        }
        if (!NpcPlacementPlanner.isAcceptable(x, y, z, probes.apply(world))) {
            return "un PNJ ne peut pas se tenir ici. Visez un bloc plein avec deux blocs d'air "
                    + "au-dessus (ni liquide ni portail).";
        }
        Optional<String> claim = forbiddenArea.at(worldName, x, y, z);
        if (claim.isPresent()) {
            return "ce bloc est dans la parcelle « " + claim.get() + " », où aucun PNJ n'est placé.";
        }
        return null;
    }

    private void report(Player player, NpcSpotService.CreateResult result) {
        switch (result.outcome()) {
            case CREATED -> {
                NpcSpot spot = result.spot();
                player.sendMessage(MM.deserialize("<green>Emplacement PNJ enregistré :</green> <white><name></white> "
                                + "<dark_gray>(<id>)</dark_gray> <gray>— <pos>.</gray><newline><gray>Renommez-le et "
                                + "placez-y un PNJ dans PlugAdmin › PNJ › Emplacements.</gray>",
                        Placeholder.unparsed("name", spot.name()),
                        Placeholder.unparsed("id", spot.id()),
                        Placeholder.unparsed("pos", position(spot))));
                World world = player.getServer().getWorld(spot.world());
                if (world != null) {
                    Location at = new Location(world, spot.x(), spot.y() + 1, spot.z());
                    world.spawnParticle(Particle.HAPPY_VILLAGER, at, 20, 0.3, 0.6, 0.3);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                }
            }
            case ALREADY_THERE -> player.sendMessage(MM.deserialize(
                    "<yellow>Un emplacement existe déjà ici :</yellow> <white><name></white> "
                            + "<dark_gray>(<id>)</dark_gray>. <gray>Rien n'a été créé.</gray>",
                    Placeholder.unparsed("name", result.spot().name()),
                    Placeholder.unparsed("id", result.spot().id())));
            case IN_PROGRESS -> {
                // Le premier clic sur ce bloc s'écrit encore : c'est lui qui répondra.
            }
            case INVALID -> player.sendMessage(MM.deserialize("<red><msg></red>",
                    Placeholder.unparsed("msg", result.error())));
        }
    }

    /** Anti-rebond par joueur. */
    private boolean acceptClick(UUID player) {
        long now = clockMillis.getAsLong();
        Long previous = lastClick.get(player);
        if (previous != null && now - previous < DEBOUNCE_MILLIS) {
            return false;
        }
        lastClick.put(player, now);
        return true;
    }

    /**
     * Orientation (yaw Minecraft) d'un PNJ placé en (sx, sz) qui regarde {@code viewer}. Le yaw 0
     * regarde vers +Z et 90 vers -X. Administrateur à la verticale exacte : on garde son propre
     * regard retourné.
     */
    static float yawTowards(double sx, double sz, Location viewer) {
        double dx = viewer.getX() - sx;
        double dz = viewer.getZ() - sz;
        if (Math.abs(dx) < 1.0e-6 && Math.abs(dz) < 1.0e-6) {
            return NpcSpot.normalizeYaw(viewer.getYaw() + 180f);
        }
        return NpcSpot.normalizeYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private static String position(NpcSpot spot) {
        return String.format(Locale.ROOT, "%s %.1f / %.1f / %.1f", spot.world(), spot.x(), spot.y(), spot.z());
    }
}
