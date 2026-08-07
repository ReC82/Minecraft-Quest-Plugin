package be.lloyd.rpgquest.entitlement;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Contrat générique pour un avantage persistant lié à un joueur (mission
 * étape 20, point 11 : « interface générique pour les futurs avantages,
 * sans créer encore la boutique »). Un avantage est identifié par une clé
 * libre ({@code entitlementKey}, ex. {@code "backpack"}) et vaut un palier
 * libre ({@code tier}, ex. {@code "MEDIUM"}) — jamais typé plus précisément
 * ici pour que cette interface serve aussi bien les backpacks que de
 * futurs avantages sans lien avec eux (métiers, cosmétiques...), sans
 * dépendre de leurs enums respectifs.
 *
 * <p>{@code backpack.BackpackService} est le premier (et seul, à cette
 * étape) consommateur concret : il n'existe aucune boutique qui accorde des
 * avantages automatiquement, seulement {@code grant}/{@code revoke}
 * administratifs.</p>
 */
public interface EntitlementService {

    /** Le palier actuel d'un joueur pour cet avantage, s'il en a un. */
    CompletableFuture<Optional<String>> currentTier(UUID playerId, String entitlementKey);

    /** Accorde (ou remplace) le palier d'un joueur pour cet avantage. */
    CompletableFuture<Void> grant(UUID playerId, String entitlementKey, String tier, String reason);

    /** Retire l'avantage d'un joueur. Ne fait rien s'il n'en avait pas. */
    CompletableFuture<Void> revoke(UUID playerId, String entitlementKey, String reason);
}
