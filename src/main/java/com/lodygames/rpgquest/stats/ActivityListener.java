package com.lodygames.rpgquest.stats;

import com.lodygames.rpgquest.database.ActivityLogRepository;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.progression.listener.CombatXpListener;
import java.time.Clock;
import com.lodygames.rpgquest.progression.PlacedBlockTracker;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Le seul listener des événements de classement (issue #57) : il note dans
 * {@code stat_activity_log} chaque action comptable, quel que soit le concours. Un « Zombie
 * Weekend » ou un concours de pêche ne demande qu'une entrée de {@code statistics.yml}.
 *
 * <p>Écoute en {@link EventPriority#MONITOR} et ignore les événements annulés : n'est compté que ce
 * qui a réellement eu lieu. Exclus d'office, pour que l'admin et le debug ne faussent jamais une
 * compétition : joueur en créatif ou spectateur, mode bypass administrateur actif (#35), mob de test
 * ({@code /rpgadmin mob test}), mob de spawner ou issu d'une division (mêmes règles anti-farm que
 * l'XP de combat), victime joueur (PvP entre comptes). L'écriture est asynchrone ; la clé
 * d'événement (UUID de l'entité tuée, de l'hameçon) rend tout rejeu sans effet.</p>
 *
 * <p>Issue #734 : blocs cassés ({@code BLOCK_BREAK}) — jamais un bloc posé par un joueur (anti-farm,
 * même règle que l'XP de minage), jamais une culture pas encore mûre, jamais une casse refusée par
 * une protection ; quêtes terminées ({@code QUEST_COMPLETION}), clé = jeton de complétion ; animaux
 * élevés ({@code ANIMAL_BRED}), seulement quand c'est un joueur éligible qui les a nourris, clé = UUID
 * du petit.</p>
 */
public final class ActivityListener implements Listener {

    private final ActivityLogRepository journal;
    private final SpecialMobService mobs;
    private final AdminBypassMode adminBypass;
    private final NamespacedKey spawnerTagKey;
    private final Logger logger;
    private final Clock clock;
    private PlacedBlockTracker placedBlocks;
    /**
     * Casses vues avant l'XP de minage ({@link EventPriority#HIGH}) : celle-ci efface la marque « posé
     * par un joueur » en {@code HIGHEST}, avant le {@code MONITOR} qui confirme la casse. Fil principal seul.
     */
    private final Map<BlockBreakEvent, Boolean> pendingBreaks = new WeakHashMap<>();

    public ActivityListener(Plugin plugin, ActivityLogRepository journal, SpecialMobService mobs,
                            AdminBypassMode adminBypass, Logger logger) {
        this.journal = journal;
        this.mobs = mobs;
        this.adminBypass = adminBypass;
        this.spawnerTagKey = CombatXpListener.spawnerTagKey(plugin);
        this.logger = logger;
        this.clock = Clock.systemUTC();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || victim instanceof Player || !eligible(killer)) {
            return;
        }
        if (CombatXpListener.isSpawnerOrigin(victim, spawnerTagKey) || mobs.isSplitOffspring(victim)
                || mobs.isTestInstance(victim)) {
            return;
        }
        String variant = mobs.specialMobId(victim).map(NamespacedKey::toString).orElse(null);
        record(new ActivityLogRepository.Activity("kill:" + victim.getUniqueId(), killer.getUniqueId(),
                StatMetric.ENTITY_KILL, victim.getType().name(), variant, 1, clock.instant()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)
                || !eligible(event.getPlayer())) {
            return;
        }
        record(new ActivityLogRepository.Activity("fish:" + event.getHook().getUniqueId(),
                event.getPlayer().getUniqueId(), StatMetric.FISH_CAUGHT, caught.getItemStack().getType().name(),
                null, 1, clock.instant()));
    }

    /**
     * Élevage (#734) : un petit né parce qu'un joueur a nourri ses parents. Les naissances sans éleveur
     * (villageois, animaux apprivoisés laissés seuls) et les œufs d'apparition ne passent pas par ici.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player breeder) || !eligible(breeder)) {
            return;
        }
        LivingEntity child = event.getEntity();
        record(new ActivityLogRepository.Activity("breed:" + child.getUniqueId(), breeder.getUniqueId(),
                StatMetric.ANIMAL_BRED, child.getType().name(), null, 1, clock.instant()));
    }

    /** Issue #734 — sans lui, aucun bloc cassé n'est journalisé (un bloc posé ne doit jamais compter). */
    public void usePlacedBlocks(PlacedBlockTracker placedBlocks) {
        this.placedBlocks = placedBlocks;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void beforeBreak(BlockBreakEvent event) {
        if (placedBlocks != null) {
            pendingBreaks.put(event, placedBlocks.isPlayerPlaced(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Boolean placed = pendingBreaks.remove(event);
        Block block = event.getBlock();
        if (event.isCancelled() || placed == null || placed || !eligible(event.getPlayer())
                || (block.getBlockData() instanceof Ageable crop && crop.getAge() < crop.getMaximumAge())) {
            return;
        }
        record(new ActivityLogRepository.Activity("break:" + UUID.randomUUID(), event.getPlayer().getUniqueId(),
                StatMetric.BLOCK_BREAK, block.getType().name(), null, 1, clock.instant()));
    }

    /**
     * Quête terminée (#734), branché sur {@code QuestProgressEngine#onQuestCompleted} : l'éligibilité
     * est lue au moment de la remise (fil principal), l'écriture n'a lieu qu'une fois la complétion
     * enregistrée. Le jeton de complétion est unique : une remise rejouée ne compte qu'une fois.
     */
    public Runnable questCompleting(Player player, NamespacedKey questId, String completionToken) {
        if (!eligible(player)) {
            return null;
        }
        ActivityLogRepository.Activity activity = new ActivityLogRepository.Activity("quest:" + completionToken,
                player.getUniqueId(), StatMetric.QUEST_COMPLETION, questId.toString(), null, 1, clock.instant());
        return () -> record(activity);
    }

    /** Compétition publique : survie ou aventure, sans mode bypass administrateur. */
    boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !adminBypass.isActive(player);
    }

    private void record(ActivityLogRepository.Activity activity) {
        journal.record(activity).exceptionally(error -> {
            logger.warn("Événements : action {} non journalisée.", activity.action(), error);
            return false;
        });
    }
}
