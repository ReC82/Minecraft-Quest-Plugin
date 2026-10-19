package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * Rend agressives des entités normalement passives (ex. {@code creeper_pig}) : aucun goal d'IA
 * « attaque » n'est disponible via l'API publique pour ces types, donc, à chaque seconde
 * ({@link AbilityTrigger#TICK}), l'entité se met en chemin vers le joueur valide le plus proche et
 * explose à portée. L'explosion est réelle ({@code World#createExplosion}) : son
 * {@code EntityExplodeEvent} passe par les protections de zone/claim existantes.
 *
 * <p><strong>Issue #190</strong> : la poursuite d'une base passive passe par
 * {@link Mob#getPathfinder()} (API publique Paper, sans NMS), recalculée à chaque seconde tant qu'un
 * joueur est dans {@link #SENSE_RANGE_BLOCKS} mais hors de {@code triggerRangeBlocks}.</p>
 *
 * <p><strong>Issue #542</strong> (décision Q9) : la cible passe par
 * {@link AbilityWorldPolicy#isValidTarget} — ni PNJ, ni joueur en créatif ou spectateur.</p>
 *
 * <p><strong>Retours TC-361 (#542)</strong> : l'explosion ne casse <em>aucun</em> bloc
 * ({@code breakBlocks = false}, terrain préservé, {@link AbilityDescriptor.WorldMutation#NONE}) ; la
 * poursuite passe à {@code Pathfinder#moveTo} un <em>multiplicateur</em> de vitesse
 * ({@link #CHASE_SPEED_MULTIPLIER}), pas la valeur de l'attribut {@code MOVEMENT_SPEED} (0,25 pour un
 * cochon, soit un quart de sa vitesse : il semblait ne pas poursuivre).</p>
 */
final class ExplosiveOnAttackExecutor implements AbilityExecutor<ExplosiveOnAttackAbility> {

    /** Rayon (blocs) au-delà de {@code triggerRangeBlocks} dans lequel l'entité se met en chemin vers le joueur. */
    static final double SENSE_RANGE_BLOCKS = 16.0;

    /** Multiplicateur de la vitesse de base pendant la poursuite (celui de la panique d'un cochon vanilla). */
    static final double CHASE_SPEED_MULTIPLIER = 1.25;

    /** Les deux effets Paper de la capacité, isolés pour être vérifiés en test (MockBukkit n'a pas de Pathfinder). */
    interface WorldActions {
        void explode(LivingEntity source, float power, boolean setFire, boolean breakBlocks);

        void moveTo(Mob mob, Player target, double speedMultiplier);
    }

    static final WorldActions PAPER = new WorldActions() {
        @Override
        public void explode(LivingEntity source, float power, boolean setFire, boolean breakBlocks) {
            source.getWorld().createExplosion(source, source.getLocation(), power, setFire, breakBlocks);
        }

        @Override
        public void moveTo(Mob mob, Player target, double speedMultiplier) {
            mob.getPathfinder().moveTo(target, speedMultiplier);
        }
    };

    private final AbilityWorldPolicy policy;
    private final WorldActions actions;

    ExplosiveOnAttackExecutor(AbilityWorldPolicy policy) {
        this(policy, PAPER);
    }

    ExplosiveOnAttackExecutor(AbilityWorldPolicy policy, WorldActions actions) {
        this.policy = policy;
        this.actions = actions;
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.EXPLOSIVE_ON_ATTACK;
    }

    @Override
    public Class<ExplosiveOnAttackAbility> abilityClass() {
        return ExplosiveOnAttackAbility.class;
    }

    @Override
    public void execute(AbilityContext<ExplosiveOnAttackAbility> context) {
        LivingEntity entity = context.mob();
        ExplosiveOnAttackAbility ability = context.ability();
        Player nearest = nearestValidPlayer(entity);
        if (nearest == null) {
            return;
        }
        double distanceSquared = nearest.getLocation().distanceSquared(entity.getLocation());
        if (distanceSquared <= ability.triggerRangeBlocks() * ability.triggerRangeBlocks()) {
            if (!context.passesGate()) {
                return;
            }
            actions.explode(entity, ability.power(), ability.setFire(), false);
            entity.damage(entity.getHealth() + 1.0);
            context.markActivated();
            return;
        }
        if (distanceSquared <= SENSE_RANGE_BLOCKS * SENSE_RANGE_BLOCKS && entity instanceof Mob mob) {
            actions.moveTo(mob, nearest, CHASE_SPEED_MULTIPLIER);
        }
    }

    private Player nearestValidPlayer(LivingEntity entity) {
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Player p : entity.getWorld().getPlayers()) {
            if (!policy.isValidTarget(p)) {
                continue;
            }
            double d = p.getLocation().distanceSquared(entity.getLocation());
            if (d < nearestDistanceSquared) {
                nearestDistanceSquared = d;
                nearest = p;
            }
        }
        return nearest;
    }
}
