package com.lodygames.rpgquest.wild;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.WildConfig;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Issue #168 — règles hostiles des mondes d'exploration : créatures agressives <strong>de jour
 * comme de nuit</strong>, insensibles au soleil, araignées agressives en pleine lumière.
 *
 * <p><strong>Périmètre strict.</strong> Tout est conditionné à l'appartenance du monde à
 * {@link WildConfig#worlds()} (vide = le seul {@code travel.wild-world}). Le Hub garde son absence
 * de mobs hostiles, les Claims gardent leurs protections, et un monde non listé conserve
 * exactement les règles vanilla. Plusieurs mondes Wild sont supportés sans changement de code.</p>
 *
 * <p><strong>Trois mécanismes distincts</strong>, délibérément séparés car le ticket exige de ne
 * pas confondre « ne plus brûler » et « apparaître de jour » :</p>
 * <ol>
 *   <li><em>Immunité au soleil</em> — on annule uniquement la combustion <strong>spontanée</strong>
 *       ({@link EntityCombustEvent} qui n'est ni {@link EntityCombustByBlockEvent} ni
 *       {@link EntityCombustByEntityEvent}, c'est-à-dire l'allumage par la lumière du jour). Le feu,
 *       la lave, les flèches enflammées et tous les dégâts de combat restent strictement normaux :
 *       aucun {@code EntityDamageEvent} n'est touché.</li>
 *   <li><em>Apparitions diurnes</em> — le spawn vanilla refuse la surface éclairée ; on le
 *       <strong>complète</strong> de jour seulement, par une passe périodique bornée autour des
 *       joueurs. La nuit, le service ne fait rien : les apparitions nocturnes restent celles du jeu,
 *       jamais doublées. Aucune nuit permanente simulée, le cycle jour/nuit réel est intact.</li>
 *   <li><em>Araignées agressives</em> — en journée une araignée vanilla est neutre ; on lui
 *       réattribue une cible valide à portée, sans jamais toucher les joueurs exemptés
 *       (créatif/spectateur, invulnérables).</li>
 * </ol>
 *
 * <p><strong>Coût maîtrisé</strong> : une seule tâche périodique pour tout le serveur, bornée par
 * joueur et par monde ; aucune tâche par mob, aucun scan global d'entités hors des mondes Wild,
 * aucune génération de chunk (on ne tente une apparition que dans un chunk <em>déjà</em> chargé).
 * Les créatures ajoutées ne sont pas marquées persistantes : le despawn naturel s'applique, donc la
 * population ne gonfle pas indéfiniment.</p>
 */
public final class WildHostileRulesService implements PluginService, Listener {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final Supplier<WildConfig> config;
    private final Supplier<String> defaultWildWorld;
    private final Random random = new Random();
    private BukkitTask task;

    public WildHostileRulesService(org.bukkit.plugin.java.JavaPlugin plugin, Supplier<WildConfig> config,
                                    Supplier<String> defaultWildWorld) {
        this.plugin = plugin;
        this.config = config;
        this.defaultWildWorld = defaultWildWorld;
    }

    @Override
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        int period = Math.max(20, config.get().daylightSpawns().periodTicks());
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, period, period);
        plugin.getSLF4JLogger().info("Règles hostiles du Wild actives (mondes : {}).",
                String.join(", ", wildWorldNames()));
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---- Périmètre ---------------------------------------------------------------------------

    private List<String> wildWorldNames() {
        List<String> configured = config.get().worlds();
        if (!configured.isEmpty()) {
            return configured;
        }
        String fallback = defaultWildWorld.get();
        return fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
    }

    /** {@code true} uniquement pour un monde Wild configuré — jamais le Hub, jamais les Claims. */
    public boolean isWildWorld(World world) {
        if (world == null) {
            return false;
        }
        String name = world.getName();
        for (String candidate : wildWorldNames()) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Le jour, au sens du temps réel du monde — aucune simulation, aucune altération du cycle. */
    private static boolean isDaytime(World world) {
        long time = world.getTime();
        return time < 12300L || time > 23850L;
    }

    // ---- 1. Immunité au soleil ---------------------------------------------------------------

    /**
     * Annule la combustion déclenchée par la lumière du jour. On reconnaît ce cas au <em>type exact</em>
     * de l'événement : Bukkit n'expose pas de cause « soleil », mais l'allumage par le soleil est le
     * seul {@link EntityCombustEvent} qui ne soit pas l'une de ses deux sous-classes (bloc = feu /
     * lave, entité = attaquant enflammé). Ces deux-là passent donc intactes, de même que tous les
     * dégâts de feu et de lave, qui ne transitent pas par cet événement.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onCombust(EntityCombustEvent event) {
        if (event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent) {
            return;
        }
        if (!config.get().sunImmunity()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Monster) || !isWildWorld(entity.getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    // ---- 2 & 3. Passe périodique : apparitions diurnes + agressivité des araignées ------------

    private void tick() {
        WildConfig cfg = config.get();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            World world = player.getWorld();
            if (!isWildWorld(world) || isExempt(player)) {
                continue;
            }
            if (cfg.aggressiveSpiders()) {
                keepSpidersAggressive(player, cfg);
            }
            if (cfg.daylightSpawns().enabled() && isDaytime(world)) {
                attemptDaylightSpawns(player, cfg.daylightSpawns());
            }
        }
    }

    /** Joueurs que l'on ne cible jamais : règles de ciblage légitimes du jeu. */
    private static boolean isExempt(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.isInvulnerable()
                || player.isDead();
    }

    /**
     * Une araignée est neutre en pleine lumière côté vanilla. On lui redonne une cible à portée —
     * uniquement si elle n'en a pas déjà une (jamais de vol de cible) et uniquement pour un joueur
     * non exempté. {@code Spider} suffit : les araignées des cavernes héritent du même type Bukkit
     * de base mais sont déjà hostiles de jour, et les retaguer ne change rien.
     */
    private void keepSpidersAggressive(Player player, WildConfig cfg) {
        int radius = Math.max(8, cfg.daylightSpawns().minDistance());
        for (Entity entity : player.getNearbyEntities(radius, radius / 2.0 + 4, radius)) {
            if (!(entity instanceof Spider spider) || spider.isDead()) {
                continue;
            }
            LivingEntity current = spider.getTarget();
            if (current != null && !current.isDead()) {
                continue;
            }
            if (spider.hasLineOfSight(player)) {
                spider.setTarget(player);
            }
        }
    }

    /**
     * Complément diurne borné. Chaque tentative échoue silencieusement si la position n'est pas
     * valide — on ne force jamais : pas de chargement de chunk, pas de spawn dans un bloc, pas de
     * spawn collé au joueur, et jamais au-delà des plafonds de population.
     */
    private void attemptDaylightSpawns(Player player, WildConfig.DaylightSpawnConfig cfg) {
        World world = player.getWorld();
        if (countMonsters(world) >= cfg.maxPerWorld()) {
            return;
        }
        if (countMonstersNear(player, cfg.maxDistance()) >= cfg.maxPerPlayer()) {
            return;
        }
        List<EntityType> types = cfg.types();
        if (types.isEmpty()) {
            return;
        }
        for (int attempt = 0; attempt < cfg.attemptsPerPlayer(); attempt++) {
            Location target = randomSurfaceAround(player, cfg);
            if (target == null) {
                continue;
            }
            EntityType type = types.get(random.nextInt(types.size()));
            LivingEntity spawned = trySpawn(world, target, type);
            if (spawned != null) {
                // Jamais persistant : le despawn naturel garde la population bornée dans le temps,
                // en plus des plafonds ci-dessus.
                spawned.setRemoveWhenFarAway(true);
                return; // une apparition par passe et par joueur : montée en charge douce
            }
        }
    }

    private long countMonsters(World world) {
        return world.getEntities().stream().filter(e -> e instanceof Monster).count();
    }

    private long countMonstersNear(Player player, int radius) {
        return player.getNearbyEntities(radius, radius, radius).stream()
                .filter(e -> e instanceof Monster).count();
    }

    /**
     * Position candidate : anneau {@code [minDistance, maxDistance]} autour du joueur, dans un
     * chunk <strong>déjà chargé</strong> (jamais de génération), sur un sol solide avec deux blocs
     * d'air au-dessus, et exposée au ciel — le besoin porte sur la surface de jour, pas sur les
     * grottes, qui continuent de fonctionner en vanilla.
     */
    private Location randomSurfaceAround(Player player, WildConfig.DaylightSpawnConfig cfg) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        double angle = random.nextDouble() * Math.PI * 2;
        int span = cfg.maxDistance() - cfg.minDistance();
        double distance = cfg.minDistance() + random.nextInt(Math.max(1, span));
        int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
        int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        if (!chunk.isLoaded()) {
            return null;
        }
        Block highest = world.getHighestBlockAt(x, z);
        Block ground = highest.getType().isSolid() ? highest : highest.getRelative(0, -1, 0);
        if (!ground.getType().isSolid() || ground.isLiquid()) {
            return null;
        }
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        if (!feet.getType().isAir() || !head.getType().isAir()) {
            return null;
        }
        // Exposition au ciel : c'est précisément ce que le spawn vanilla refuse de jour.
        if (!world.getHighestBlockAt(x, z).getLocation().equals(ground.getLocation())) {
            return null;
        }
        return feet.getLocation().add(0.5, 0, 0.5);
    }

    /**
     * Tente l'apparition. On repasse par {@link World#spawnEntity} (API publique) : les autres
     * systèmes du plugin voient donc un {@code CreatureSpawnEvent} normal — les mobs spéciaux
     * (#169), les règles du Hub et les protections de Claims continuent de s'appliquer d'eux-mêmes
     * et peuvent annuler cette apparition, ce qui est voulu.
     */
    private LivingEntity trySpawn(World world, Location location, EntityType type) {
        try {
            Entity entity = world.spawnEntity(location, type);
            if (entity instanceof LivingEntity living) {
                return living;
            }
            entity.remove();
            return null;
        } catch (IllegalArgumentException e) {
            return null; // type non spawnable tel quel : on abandonne cette tentative, sans bruit
        }
    }

}
