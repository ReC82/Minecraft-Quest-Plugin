package com.lodygames.rpgquest.mob.community;

import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * (docs/RPGQUEST_BIBLE.md §11) :</p>
 * <ol>
 *   <li>avoir porté au moins un coup <em>effectif</em> (dégâts finaux &gt; 0, événement non annulé) à
 *       cette instance précise, en direct ou par un projectile dont il est le tireur — pas de seuil,
 *       pas d'obligation du dernier coup ;</li>
 *   <li>être connecté et vivant à cet instant ;</li>
 *   <li>être dans le même monde et dans le même biome que la position réelle du boss à sa mort
 *       (comparaison des clés de biome, sans rayon supplémentaire).</li>
 * </ol>
 */
public final class BossParticipation {

    /** Plafond de participants mémorisés par boss (au-delà, les nouveaux ne sont plus inscrits). */
    public static final int MAX_PARTICIPANTS = 100;

    /** Issue d'un participant à la mort du boss. Seul {@link #ELIGIBLE} donne droit à récompense. */
    public enum Verdict {
        ELIGIBLE("éligible"),
        OFFLINE("hors ligne"),
        DEAD("mort au moment de la victoire"),
        OTHER_WORLD("dans un autre monde"),
        OTHER_BIOME("hors du biome du boss");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Situation d'un participant à l'instant de la mort ; {@code online=false} ignore le reste. */
    public record PlayerSituation(boolean online, boolean dead, String world, String biome) {

        public static final PlayerSituation OFFLINE = new PlayerSituation(false, false, null, null);
    }

    /** Participant évalué (le nom sert seulement à l'affichage et au journal). */
    public record Evaluated(UUID playerId, String name, Verdict verdict) {
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

    public static Verdict verdict(PlayerSituation situation, String bossWorld, String bossBiome) {
        if (situation == null || !situation.online()) {
            return Verdict.OFFLINE;
        }
        if (situation.dead()) {
            return Verdict.DEAD;
        }
        if (situation.world() == null || !situation.world().equals(bossWorld)) {
            return Verdict.OTHER_WORLD;
        }
        if (situation.biome() == null || !situation.biome().equals(bossBiome)) {
            return Verdict.OTHER_BIOME;
        }
        return Verdict.ELIGIBLE;
    }

    /** Évalue tous les participants, dans l'ordre de leur premier coup. */
    public static List<Evaluated> evaluate(Set<UUID> participants, String bossWorld, String bossBiome,
                                           Function<UUID, PlayerSituation> situations,
                                           Function<UUID, String> names) {
        List<Evaluated> evaluated = new ArrayList<>();
        for (UUID id : participants) {
            evaluated.add(new Evaluated(id, names.apply(id), verdict(situations.apply(id), bossWorld, bossBiome)));
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

    /** Lit la liste mémorisée en PDC (UUID séparés par des virgules) ; tolère une valeur abîmée. */
    public static Set<UUID> decode(String raw) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // fragment illisible : ignoré, jamais une exception dans un listener de combat
            }
        }
        return ids;
    }

    public static String encode(Set<UUID> ids) {
        return String.join(",", ids.stream().map(UUID::toString).toList());
    }
}
