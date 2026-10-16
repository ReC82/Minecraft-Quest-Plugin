package com.lodygames.rpgquest.mob.community;

import com.lodygames.rpgquest.entitlement.reward.RewardDefinition;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlementService;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import com.lodygames.rpgquest.entitlement.reward.RewardTable;
import com.lodygames.rpgquest.entitlement.reward.RewardTableDraw;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Issue #176 — participation et récompenses des boss communautaires.
 *
 * <ul>
 *   <li><b>Participation</b> : chaque coup effectif (dégâts finaux &gt; 0, événement non annulé,
 *       joueur direct ou tireur d'un projectile) inscrit le joueur et l'heure de son dernier coup sur
 *       l'entité elle-même (PDC) : la liste survit au déchargement du chunk et au redémarrage, et n'est
 *       jamais transférée à un autre boss ;</li>
 *   <li><b>Mort du joueur</b> : sa participation aux boss chargés est effacée sur-le-champ, et l'heure
 *       de sa mort est inscrite sur le joueur (PDC) pour les boss déchargés ou un redémarrage : seul un
 *       coup porté après cette mort compte. Reconnexion et changement de monde n'effacent rien ;</li>
 *   <li><b>Éligibilité</b> : figée à la mort, selon {@link BossParticipation} ;</li>
 *   <li><b>Récompenses</b> : un droit à récompense (#422, source {@code BOSS}) par participant
 *       éligible et par clé {@code participation-rewards} du profil. La remise (inventaire plein,
 *       reconnexion, redémarrage, au plus une fois) est celle de {@link RewardEntitlementService} ;</li>
 *   <li><b>Tirages</b> (#177) : une entrée {@code table:<id>} tire la table <em>une fois par
 *       participant éligible</em>, résultat individuel ; chaque gain devient un droit. Le tirage est
 *       déterministe (graine = entité du boss + joueur) : un nouvel essai retombe sur les mêmes gains.</li>
 * </ul>
 *
 * <p>Seuls les boss communautaires (PDC d'instance posée par {@link CommunityBossService}) sont
 * concernés : un boss de quête ou un mob spécial ne déclare jamais rien ici.</p>
 */
public final class CommunityBossRewards implements CommunityBossHooks, Listener {

    public static final String PARTICIPANTS_KEY_NAME = "community-boss-participants";
    public static final String LAST_DEATH_KEY_NAME = "community-boss-last-death";
    static final int RECENT_DEFEATS = 10;
    static final int DECLARE_ATTEMPTS = 3;
    private static final long RETRY_DELAY_TICKS = 20L * 5;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Ce dont la participation a besoin du service de droits à récompense (testable sans base). */
    public interface RewardSink {
        boolean enabled();

        /** Libellé du catalogue, ou {@code null} si la clé y est inconnue. */
        String label(String rewardKey);

        CompletableFuture<Integer> declare(List<RewardEntitlement.Declaration> declarations);

        void deliverSoon(List<UUID> players);

        /** Table de récompenses aléatoires (#177), vide si inconnue. */
        default Optional<RewardTable> table(String id) {
            return Optional.empty();
        }

        static RewardSink of(RewardEntitlementService service) {
            return new RewardSink() {
                @Override
                public boolean enabled() {
                    return service.catalog().enabled();
                }

                @Override
                public String label(String rewardKey) {
                    return service.catalog().find(rewardKey).map(RewardDefinition::label).orElse(null);
                }

                @Override
                public CompletableFuture<Integer> declare(List<RewardEntitlement.Declaration> declarations) {
                    return service.declareAll(declarations);
                }

                @Override
                public void deliverSoon(List<UUID> players) {
                    service.deliverSoon(players);
                }

                @Override
                public Optional<RewardTable> table(String id) {
                    return service.catalog().findTable(id);
                }
            };
        }
    }

    /** Une victoire récente, pour la traçabilité PlugAdmin (mémoire, depuis le dernier démarrage). */
    public record Defeat(UUID entityId, String profileId, String profileName, long defeatedAtMillis, String world,
                         String biome, int radius, String killerName, boolean test,
                         List<BossParticipation.Evaluated> participants, List<String> rewardKeys,
                         String rewardStatus, List<String> draws) {

        public Defeat {
            participants = List.copyOf(participants);
            rewardKeys = List.copyOf(rewardKeys);
            draws = List.copyOf(draws);
        }

        Defeat withStatus(String status) {
            return new Defeat(entityId, profileId, profileName, defeatedAtMillis, world, biome, radius, killerName,
                    test, participants, rewardKeys, status, draws);
        }
    }

    /** Ce que reçoit un participant éligible : libellés garantis et tirés, et tables sans gain. */
    private record Promised(List<String> labels, List<String> emptyDraws) {
    }

    private final Plugin plugin;
    private final Logger logger;
    private final NamespacedKey instanceKey;
    private final NamespacedKey participantsKey;
    private final NamespacedKey lastDeathKey;
    private final Function<String, Optional<SpecialMobDefinition>> profiles;
    private final RewardSink rewards;
    private final IntSupplier participationRadius;
    private final Supplier<Collection<UUID>> activeBosses;
    private final LongSupplier clock;
    private volatile List<Defeat> recent = List.of();

    /**
     * @param participationRadius distance de participation en vigueur (réglage PlugAdmin, lue à chaque mort)
     * @param activeBosses        entités des boss communautaires actifs (effacement à la mort d'un joueur)
     */
    public CommunityBossRewards(Plugin plugin, Logger logger, NamespacedKey instanceKey,
                                Function<String, Optional<SpecialMobDefinition>> profiles, RewardSink rewards,
                                IntSupplier participationRadius, Supplier<Collection<UUID>> activeBosses,
                                LongSupplier clock) {
        this.plugin = plugin;
        this.logger = logger;
        this.instanceKey = instanceKey;
        this.participantsKey = new NamespacedKey(plugin, PARTICIPANTS_KEY_NAME);
        this.lastDeathKey = new NamespacedKey(plugin, LAST_DEATH_KEY_NAME);
        this.profiles = profiles;
        this.rewards = rewards;
        this.participationRadius = participationRadius;
        this.activeBosses = activeBosses;
        this.clock = clock;
    }

    /** Les dernières victoires, la plus récente d'abord. */
    public List<Defeat> recentDefeats() {
        return recent;
    }

    // ---- participation ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss) || event.getFinalDamage() <= 0) {
            return;
        }
        PersistentDataContainer pdc = boss.getPersistentDataContainer();
        if (!pdc.has(instanceKey)) {
            return;
        }
        BossParticipation.attacker(event.getDamager()).ifPresent(player -> record(pdc, player.getUniqueId()));
    }

    /** Inscrit le joueur ou rafraîchit l'heure de son dernier coup (c'est elle que la mort invalide). */
    private void record(PersistentDataContainer pdc, UUID playerId) {
        Map<UUID, Long> hits = hits(pdc);
        if (!hits.containsKey(playerId) && hits.size() >= BossParticipation.MAX_PARTICIPANTS) {
            return;
        }
        hits.put(playerId, clock.getAsLong());
        pdc.set(participantsKey, PersistentDataType.STRING, BossParticipation.encode(hits));
    }

    private Map<UUID, Long> hits(PersistentDataContainer pdc) {
        return BossParticipation.decode(pdc.get(participantsKey, PersistentDataType.STRING));
    }

    /** Participants inscrits sur l'entité (tests, diagnostic). */
    public Set<UUID> participants(LivingEntity boss) {
        return hits(boss.getPersistentDataContainer()).keySet();
    }

    /**
     * Mort d'un joueur : ses coups passés cessent de compter. Effacement immédiat sur les boss chargés
     * (le coup qui précède sa mort dans la même milliseconde est donc bien perdu) et heure de mort sur le
     * joueur, persistante, pour les boss déchargés et après un redémarrage.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        player.getPersistentDataContainer().set(lastDeathKey, PersistentDataType.LONG, clock.getAsLong());
        for (UUID bossId : activeBosses.get()) {
            if (Bukkit.getEntity(bossId) instanceof LivingEntity boss) {
                PersistentDataContainer pdc = boss.getPersistentDataContainer();
                Map<UUID, Long> hits = hits(pdc);
                if (hits.remove(playerId) != null) {
                    pdc.set(participantsKey, PersistentDataType.STRING, BossParticipation.encode(hits));
                }
            }
        }
    }

    // ---- victoire --------------------------------------------------------------------------------

    @Override
    public void onDefeated(CommunityBossInstance instance, LivingEntity entity, Player killer) {
        Location at = entity.getLocation();
        String world = at.getWorld().getName();
        String biome = at.getBlock().getBiome().getKey().toString();
        int radius = participationRadius.getAsInt();
        BossParticipation.BossDeath death = new BossParticipation.BossDeath(world, at.getX(), at.getY(), at.getZ(),
                radius);
        List<BossParticipation.Evaluated> evaluated = BossParticipation.evaluate(
                hits(entity.getPersistentDataContainer()), death, this::situation, CommunityBossRewards::name);
        List<String> rewardKeys = profiles.apply(instance.profileId())
                .map(SpecialMobDefinition::participationRewards).orElse(List.of());
        List<BossParticipation.Evaluated> eligible = evaluated.stream()
                .filter(e -> e.verdict() == BossParticipation.Verdict.ELIGIBLE).toList();

        String status = initialStatus(evaluated, eligible, rewardKeys);
        List<String> fixedKeys = rewardKeys.stream().filter(k -> !k.startsWith(SpecialMobDefinition.TABLE_PREFIX))
                .toList();
        List<String> tableIds = rewardKeys.stream().filter(k -> k.startsWith(SpecialMobDefinition.TABLE_PREFIX))
                .map(k -> k.substring(SpecialMobDefinition.TABLE_PREFIX.length())).toList();
        List<RewardEntitlement.Declaration> declarations = new ArrayList<>(BossParticipation.declarations(instance,
                evaluated, fixedKeys, this::label));
        java.util.Map<UUID, Promised> promised = new java.util.LinkedHashMap<>();
        List<String> draws = new ArrayList<>();
        if (!eligible.isEmpty() && rewards.enabled()) {
            drawTables(instance, eligible, fixedKeys, tableIds, declarations, promised, draws);
        }
        remember(new Defeat(instance.entityId(), instance.profileId(), instance.profileName(),
                clock.getAsLong(), world, biome, radius, killer == null ? null : killer.getName(), instance.test(),
                evaluated, rewardKeys, status, draws));
        draws.forEach(line -> logger.info("  tirage : {}.", line));
        logger.info("Boss communautaire « {} » ({}) vaincu dans {} à {} {} {} (rayon {}) : {} participant(s), "
                        + "{} éligible(s) {} — {}.", instance.profileId(), instance.entityId(), world, at.getBlockX(),
                at.getBlockY(), at.getBlockZ(), radius, evaluated.size(), eligible.size(),
                eligible.stream().map(BossParticipation.Evaluated::name).toList(), status);
        evaluated.stream().filter(e -> e.verdict() != BossParticipation.Verdict.ELIGIBLE)
                .forEach(e -> logger.info("  participant {} ({}) exclu : {}.", e.name(), e.playerId(), e.reason(radius)));

        if (rewardKeys.isEmpty()) {
            return; // profil sans récompense de participation : rien n'est promis ni déclaré
        }
        notifyExcluded(instance, evaluated, radius);
        if (eligible.isEmpty()) {
            return;
        }
        if (!rewards.enabled()) {
            logger.warn("Boss communautaire « {} » : {} participant(s) éligible(s) mais entitlements.yml est désactivé "
                    + "— aucune récompense déclarée.", instance.profileId(), eligible.size());
            return;
        }
        declare(instance, eligible, promised, declarations, 1);
    }

    /**
     * Un tirage par participant éligible et par table (#177), résultat individuel. Table inconnue ou
     * désactivée : rien n'est tiré, c'est tracé (jamais un gain inventé).
     */
    private void drawTables(CommunityBossInstance instance, List<BossParticipation.Evaluated> eligible,
                            List<String> fixedKeys, List<String> tableIds,
                            List<RewardEntitlement.Declaration> declarations, java.util.Map<UUID, Promised> promised,
                            List<String> draws) {
        for (BossParticipation.Evaluated participant : eligible) {
            List<String> labels = new ArrayList<>(fixedKeys.stream().map(this::label).toList());
            List<String> empty = new ArrayList<>();
            for (String tableId : tableIds) {
                Optional<RewardTable> table = rewards.table(tableId);
                if (table.isEmpty() || !table.get().enabled()) {
                    draws.add(participant.name() + " : table « " + tableId + " » "
                            + (table.isEmpty() ? "inconnue" : "désactivée") + " — aucun tirage");
                    continue;
                }
                List<RewardTableDraw.Win> wins = RewardTableDraw.wins(table.get(), "boss:" + instance.entityId(),
                        participant.playerId(), RewardSource.BOSS, BossParticipation.sourceRef(instance), this::label);
                wins.forEach(win -> declarations.add(win.declaration()));
                List<String> won = wins.stream().map(win -> label(win.entry().rewardKey())).toList();
                labels.addAll(won);
                if (won.isEmpty()) {
                    empty.add(table.get().label());
                }
                draws.add(participant.name() + " : table « " + tableId + " » v" + table.get().version() + " → "
                        + (won.isEmpty() ? "aucun gain" : String.join(", ", won)));
            }
            promised.put(participant.playerId(), new Promised(labels, empty));
        }
    }

    private String label(String rewardKey) {
        return Optional.ofNullable(rewards.label(rewardKey)).orElse(rewardKey);
    }

    private String initialStatus(List<BossParticipation.Evaluated> evaluated, List<BossParticipation.Evaluated> eligible,
                                 List<String> rewardKeys) {
        if (rewardKeys.isEmpty()) {
            return "Aucune récompense de participation sur ce profil.";
        }
        if (evaluated.isEmpty() || eligible.isEmpty()) {
            return "Aucun participant éligible : rien à déclarer.";
        }
        if (!rewards.enabled()) {
            return "Droits à récompense désactivés (entitlements.yml) : rien n'a été déclaré.";
        }
        return "Déclaration des droits en cours…";
    }

    /** Déclaration idempotente (même clé = un seul droit) : un nouvel essai est donc sans risque. */
    private void declare(CommunityBossInstance instance, List<BossParticipation.Evaluated> eligible,
                         java.util.Map<UUID, Promised> promised, List<RewardEntitlement.Declaration> declarations,
                         int attempt) {
        rewards.declare(declarations).whenComplete((created, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error == null) {
                updateStatus(instance.entityId(), created + " droit(s) déclaré(s) pour " + eligible.size()
                        + " joueur(s) éligible(s).");
                notifyEligible(instance, eligible, promised);
                rewards.deliverSoon(eligible.stream().map(BossParticipation.Evaluated::playerId).toList());
                return;
            }
            if (attempt < DECLARE_ATTEMPTS) {
                logger.warn("Boss communautaire « {} » : déclaration des récompenses impossible (essai {}/{}), "
                        + "nouvel essai dans 5 s.", instance.profileId(), attempt, DECLARE_ATTEMPTS, error);
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> declare(instance, eligible, promised, declarations, attempt + 1), RETRY_DELAY_TICKS);
                return;
            }
            updateStatus(instance.entityId(), "ÉCHEC de la déclaration — à déclarer à la main (voir le journal).");
            logger.error("Boss communautaire « {} » : récompenses NON déclarées après {} essais. Droits à déclarer à "
                    + "la main : {}", instance.profileId(), DECLARE_ATTEMPTS,
                    declarations.stream().map(d -> d.playerId() + "→" + d.rewardKey()).toList(), error);
        }));
    }

    private void notifyEligible(CommunityBossInstance instance, List<BossParticipation.Evaluated> eligible,
                                java.util.Map<UUID, Promised> promised) {
        for (BossParticipation.Evaluated participant : eligible) {
            Player player = Bukkit.getPlayer(participant.playerId());
            Promised mine = promised.get(participant.playerId());
            if (player == null || mine == null) {
                continue;
            }
            if (!mine.labels().isEmpty()) {
                player.sendMessage(MM.deserialize("<gold>⚔ Participation reconnue contre</gold> <name><gold> !</gold> "
                                + "<gray>Récompense :</gray> <white><labels></white><gray>. Elle arrive dans ton "
                                + "inventaire (garde de la place libre).</gray>",
                        Placeholder.unparsed("name", instance.profileName()),
                        Placeholder.unparsed("labels", String.join(", ", mine.labels()))));
            }
            for (String table : mine.emptyDraws()) {
                player.sendMessage(MM.deserialize("<gray>Tirage « <table> » : pas de gain cette fois.</gray>",
                        Placeholder.unparsed("table", table)));
            }
        }
    }

    private void notifyExcluded(CommunityBossInstance instance, List<BossParticipation.Evaluated> evaluated,
                                int radius) {
        for (BossParticipation.Evaluated participant : evaluated) {
            if (participant.verdict() == BossParticipation.Verdict.ELIGIBLE) {
                continue;
            }
            Player player = Bukkit.getPlayer(participant.playerId());
            if (player != null) {
                player.sendMessage(MM.deserialize("<gray>Tu as combattu</gray> <name> <gray>mais tu n'as pas de "
                                + "récompense : tu étais</gray> <white><why></white><gray> au moment de sa défaite.</gray>",
                        Placeholder.unparsed("name", instance.profileName()),
                        Placeholder.unparsed("why", participant.reason(radius))));
            }
        }
    }

    private void remember(Defeat defeat) {
        List<Defeat> next = new ArrayList<>();
        next.add(defeat);
        for (Defeat d : recent) {
            if (next.size() >= RECENT_DEFEATS) {
                break;
            }
            if (!d.entityId().equals(defeat.entityId())) {
                next.add(d);
            }
        }
        recent = List.copyOf(next);
    }

    private void updateStatus(UUID entityId, String status) {
        recent = recent.stream().map(d -> d.entityId().equals(entityId) ? d.withStatus(status) : d).toList();
    }

    private BossParticipation.PlayerSituation situation(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return BossParticipation.PlayerSituation.OFFLINE;
        }
        Location l = player.getLocation();
        Long lastDeath = player.getPersistentDataContainer().get(lastDeathKey, PersistentDataType.LONG);
        return new BossParticipation.PlayerSituation(true, player.isDead() || player.getHealth() <= 0,
                l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), lastDeath == null ? 0L : lastDeath);
    }

    private static String name(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        return offline.getName() != null ? offline.getName() : playerId.toString().substring(0, 8);
    }
}
