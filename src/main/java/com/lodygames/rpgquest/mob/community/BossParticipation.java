package com.lodygames.rpgquest.mob.community;

import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

/**
 * Issue #176 — règles de participation à un boss communautaire, sans état ni accès serveur
 * (testables sans MockBukkit pour l'essentiel).
 *
 * <p>Conditions cumulatives, évaluées <strong>au moment de la mort</strong> du boss
 * (docs/RPGQUEST_BIBLE.md §11, règles arbitrées par le propriétaire le 16/10/2026) :</p>
 * <ol>
 *   <li>avoir porté au moins un coup <em>effectif</em> (dégâts finaux &gt; 0, événement non annulé) à
 *       cette instance précise <em>depuis sa dernière mort</em>, en direct ou par un projectile dont il
 *       est le tireur — pas de seuil, pas d'obligation du dernier coup ;</li>
 *   <li>être connecté et vivant à cet instant, dans le même monde que le boss ;</li>
 *   <li>être à au plus {@code rayon} blocs (distance euclidienne 3D, réglage PlugAdmin, 100 par défaut)
 *       de la position réelle du boss à sa mort. Le biome ne compte pas.</li>
 * </ol>
 */
public final class BossParticipation {

    /** Plafond de participants mémorisés par boss (au-delà, les nouveaux ne sont plus inscrits). */
    public static final int MAX_PARTICIPANTS = 100;

    /** Heure de coup des inscriptions antérieures à la règle « mort = coups effacés » (format sans heure). */
    static final long UNKNOWN_HIT_TIME = 0L;

    /** Issue d'un participant à la mort du boss. Seul {@link #ELIGIBLE} donne droit à récompense. */
    public enum Verdict {
        ELIGIBLE("éligible"),
        OFFLINE("hors ligne"),
        DEAD("mort au moment de la victoire"),
        NO_HIT_SINCE_DEATH("mort pendant le combat, sans nouveau coup ensuite"),
        OTHER_WORLD("dans un autre monde"),
        TOO_FAR("trop loin du boss");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Position réelle du boss à sa mort et distance de participation en vigueur. */
    public record BossDeath(String world, double x, double y, double z, int radius) {
    }

    /**
     * Situation d'un participant à l'instant de la mort ; {@code online=false} ignore le reste.
     * {@code lastDeathMillis} : dernière mort connue du joueur (0 = aucune enregistrée).
     */
    public record PlayerSituation(boolean online, boolean dead, String world, double x, double y, double z,
                                  long lastDeathMillis) {

        public static final PlayerSituation OFFLINE = new PlayerSituation(false, false, null, 0, 0, 0, 0L);
    }

    /**
     * Participant évalué (le nom sert seulement à l'affichage et au journal). {@code distance} : en blocs,
     * négative quand elle n'a pas de sens (hors ligne, autre monde).
     */
    public record Evaluated(UUID playerId, String name, Verdict verdict, double distance) {

        /** Raison lisible du verdict, distance comprise quand le joueur était trop loin. */
        public String reason(int radius) {
            if (verdict == Verdict.TOO_FAR) {
                return verdict.label() + " (" + Math.round(distance) + " blocs, " + radius + " au plus)";
            }
            return verdict.label();
        }
    }

    private BossParticipation() {
    }

    /** Le joueur à créditer pour ce coup : le joueur lui-même ou le tireur d'un projectile. */
    public static Optional<Player> attacker(Entity damager) {
        if (damager instanceof Player player) {
            return Optional.of(player);
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return Optional.of(shooter);
        }
        return Optional.empty();
    }

    /**
     * Verdict d'un participant dont le dernier coup effectif date de {@code lastHitMillis}. Un coup
     * antérieur à la dernière mort du joueur ne compte plus ; un coup porté dans la même milliseconde
     * que la mort compte (la mort efface déjà les coups des boss chargés, voir
     * {@link CommunityBossRewards}).
     */
    public static Verdict verdict(PlayerSituation situation, long lastHitMillis, BossDeath boss) {
        if (situation == null || !situation.online()) {
            return Verdict.OFFLINE;
        }
        if (situation.dead()) {
            return Verdict.DEAD;
        }
        if (situation.lastDeathMillis() > lastHitMillis) {
            return Verdict.NO_HIT_SINCE_DEATH;
        }
        if (situation.world() == null || !situation.world().equals(boss.world())) {
            return Verdict.OTHER_WORLD;
        }
        if (distance(situation, boss) > boss.radius()) {
            return Verdict.TOO_FAR;
        }
        return Verdict.ELIGIBLE;
    }

    static double distance(PlayerSituation situation, BossDeath boss) {
        double dx = situation.x() - boss.x();
        double dy = situation.y() - boss.y();
        double dz = situation.z() - boss.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Évalue tous les participants (joueur → dernier coup effectif), dans l'ordre de leur premier coup. */
    public static List<Evaluated> evaluate(Map<UUID, Long> hits, BossDeath boss,
                                           Function<UUID, PlayerSituation> situations,
                                           Function<UUID, String> names) {
        List<Evaluated> evaluated = new ArrayList<>();
        for (Map.Entry<UUID, Long> hit : hits.entrySet()) {
            PlayerSituation situation = situations.apply(hit.getKey());
            boolean sameWorld = situation != null && situation.online() && situation.world() != null
                    && situation.world().equals(boss.world());
            evaluated.add(new Evaluated(hit.getKey(), names.apply(hit.getKey()),
                    verdict(situation, hit.getValue(), boss), sameWorld ? distance(situation, boss) : -1));
        }
        return evaluated;
    }

    /**
     * Un droit par participant éligible et par récompense du profil. Clé
     * {@code boss:<entité>:<récompense>:<joueur>} (≤ 143 caractères) : stable, donc une seconde mort
     * constatée ou un rejeu ne crée jamais de second droit.
     */
    public static List<RewardEntitlement.Declaration> declarations(CommunityBossInstance instance,
                                                                   List<Evaluated> evaluated,
                                                                   List<String> rewardKeys,
                                                                   Function<String, String> labels) {
        List<RewardEntitlement.Declaration> declarations = new ArrayList<>();
        for (Evaluated participant : evaluated) {
            if (participant.verdict() != Verdict.ELIGIBLE) {
                continue;
            }
            for (String rewardKey : rewardKeys) {
                declarations.add(new RewardEntitlement.Declaration(
                        idempotencyKey(instance.entityId(), rewardKey, participant.playerId()),
                        participant.playerId(), RewardSource.BOSS, sourceRef(instance), rewardKey,
                        labels.apply(rewardKey) + " — " + instance.profileName()));
            }
        }
        return declarations;
    }

    static String idempotencyKey(UUID bossEntity, String rewardKey, UUID player) {
        return "boss:" + bossEntity + ":" + rewardKey + ":" + player;
    }

    /** Profil et instance : lisible dans PlugAdmin, et unique par boss. */
    static String sourceRef(CommunityBossInstance instance) {
        return instance.profileId() + "@" + instance.entityId();
    }

    // ---- mémorisation sur l'entité (PDC) ----------------------------------------------------------

    /**
     * Lit la liste mémorisée en PDC ({@code uuid@millis} séparés par des virgules, millis = dernier coup
     * effectif). Tolère une valeur abîmée et l'ancien format sans heure ({@link #UNKNOWN_HIT_TIME}).
     */
    public static Map<UUID, Long> decode(String raw) {
        Map<UUID, Long> hits = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return hits;
        }
        for (String part : raw.split(",")) {
            String[] fields = part.trim().split("@", 2);
            try {
                UUID id = UUID.fromString(fields[0]);
                hits.put(id, fields.length > 1 ? Long.parseLong(fields[1]) : UNKNOWN_HIT_TIME);
            } catch (IllegalArgumentException ignored) {
                // fragment illisible : ignoré, jamais une exception dans un listener de combat
            }
        }
        return hits;
    }

    public static String encode(Map<UUID, Long> hits) {
        return String.join(",", hits.entrySet().stream().map(e -> e.getKey() + "@" + e.getValue()).toList());
    }
}
