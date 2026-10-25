package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import java.util.UUID;

/**
 * Issue #829, décision D2 du propriétaire (25/10/2026) : qui peut arriver <strong>directement par
 * téléportation</strong> (commande, plugin, spectateur) à l'intérieur d'un claim. La présence et
 * l'exploration à pied ne sont jamais concernées, ni la protection des blocs ({@link
 * ClaimProtectionListener}).
 *
 * <p>Point d'extension prévu pour un futur système d'amis / autorisations de visite : une autre
 * implémentation pourra accepter les visiteurs invités sans toucher au listener. Aujourd'hui seule
 * {@link #MEMBERS_ONLY} existe ; le bypass administrateur explicite (#35) reste vérifié en amont,
 * jamais ici.</p>
 */
@FunctionalInterface
public interface ClaimVisitPolicy {

    /** Règle actuelle : seuls le propriétaire et les membres ({@code /claim trust}) du claim. */
    ClaimVisitPolicy MEMBERS_ONLY = (visitor, claim) -> claim.isTrusted(visitor);

    /** {@code true} si {@code visitor} peut être téléporté directement dans {@code claim}. */
    boolean allowsDirectTeleport(UUID visitor, Claim claim);
}
