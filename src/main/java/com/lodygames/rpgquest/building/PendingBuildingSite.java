package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import java.time.Instant;

/**
 * Un emplacement <strong>en cours de création</strong>, pas encore enregistré (issue #227).
 *
 * <h2>Pourquoi cette étape existe</h2>
 *
 * <p>Avant, un clic droit écrivait immédiatement en base. Un clic de travers créait donc un
 * emplacement qu'il fallait ensuite aller nettoyer depuis le Control Panel — et la validation
 * manuelle a montré que c'est exactement ce qui arrive. Le clic ne fait plus que <em>préparer</em> :
 * il calcule l'ancre et l'orientation, les retient ici, et demande un nom. Rien n'est écrit tant
 * que le joueur n'a pas confirmé.</p>
 *
 * <p><strong>Volontairement non persistant.</strong> Une intention de création n'a aucune raison de
 * survivre à une déconnexion ou à un redémarrage : si le joueur n'est plus là pour la confirmer,
 * elle n'a plus de sens. C'est la même décision que pour les sélections de zone
 * ({@code ZoneSelectionService}), et elle garantit par construction qu'un pending abandonné
 * n'écrit jamais rien.</p>
 *
 * <p><strong>Aucun identifiant n'est consommé ici.</strong> L'allocation de {@code buildsite_000N}
 * n'a lieu qu'à la confirmation : annuler cent fois ne fait donc pas « sauter » cent numéros.</p>
 *
 * @param playerId  identité du joueur qui a cliqué, en texte (son UUID)
 * @param world     monde du clic
 * @param anchor    ancre résolue (case libre contre la face cliquée)
 * @param facing    orientation déduite du regard au moment du clic
 * @param clickedX  bloc réellement cliqué — conservé pour le message et le diagnostic
 * @param clickedY  idem
 * @param clickedZ  idem
 * @param openedAt  instant du clic
 * @param expiresAt instant au-delà duquel la demande n'est plus confirmable
 */
public record PendingBuildingSite(String playerId, String world, BuildingSiteAnchor anchor,
                                  Facing facing, int clickedX, int clickedY, int clickedZ,
                                  Instant openedAt, Instant expiresAt) {

    public PendingBuildingSite {
        playerId = playerId == null ? "" : playerId;
        world = world == null ? "" : world;
    }

    /** La demande est-elle encore confirmable à cet instant ? */
    public boolean expiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** Position lisible de l'ancre, dans l'ordre où un administrateur la lit en jeu. */
    public String positionLabel() {
        return anchor.x() + " / " + anchor.y() + " / " + anchor.z();
    }

    /** Le clic désignait-il bien ce bloc de ce monde ? Revérifié à la confirmation. */
    public boolean matches(String otherWorld, int x, int y, int z) {
        return world.equals(otherWorld) && anchor.x() == x && anchor.y() == y && anchor.z() == z;
    }
}
