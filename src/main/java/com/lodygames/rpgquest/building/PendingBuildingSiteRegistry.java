package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les demandes de création en attente de nom, <strong>en mémoire uniquement</strong> (issue #227).
 *
 * <h2>Une seule demande par joueur</h2>
 *
 * <p>Cliquer ailleurs <em>remplace</em> la demande en cours au lieu de s'empiler. C'est le
 * comportement attendu : un administrateur qui se rend compte qu'il a visé le mauvais bloc re-clique
 * au bon endroit, il ne veut pas se retrouver avec deux formulaires à traiter. Et puisqu'une demande
 * remplacée n'a rien écrit, l'abandonner ne coûte rien.</p>
 *
 * <h2>Expiration</h2>
 *
 * <p>Une demande non confirmée au bout de {@link #DEFAULT_TTL} n'est plus confirmable. Elle est
 * évaluée <strong>à la lecture</strong> (et non par une horloge qui court derrière) : c'est ce qui
 * rend la règle vraie même si la tâche de purge ne tourne pas, ou tourne en retard. La purge
 * périodique ne sert qu'à empêcher la table de grossir.</p>
 *
 * <p>Horloge injectable : l'expiration se teste sans attendre une minute.</p>
 */
public final class PendingBuildingSiteRegistry {

    /** Durée de vie d'une demande non confirmée. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, PendingBuildingSite> pending = new ConcurrentHashMap<>();

    public PendingBuildingSiteRegistry() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    public PendingBuildingSiteRegistry(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
    }

    /**
     * Enregistre une demande pour ce joueur, en remplaçant la précédente s'il y en avait une.
     *
     * @return la demande créée, avec son instant d'expiration déjà calculé
     */
    public PendingBuildingSite open(String playerId, String world, BuildingSiteAnchor anchor,
                                    Facing facing, int clickedX, int clickedY, int clickedZ) {
        Instant now = clock.instant();
        PendingBuildingSite site = new PendingBuildingSite(playerId, world, anchor, facing,
                clickedX, clickedY, clickedZ, now, now.plus(ttl));
        pending.put(playerId, site);
        return site;
    }

    /**
     * La demande en cours de ce joueur, <strong>si elle n'a pas expiré</strong>. Une demande expirée
     * est retirée au passage : la lire est le bon moment pour s'en débarrasser.
     */
    public Optional<PendingBuildingSite> find(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        PendingBuildingSite site = pending.get(playerId);
        if (site == null) {
            return Optional.empty();
        }
        if (site.expiredAt(clock.instant())) {
            pending.remove(playerId, site);
            return Optional.empty();
        }
        return Optional.of(site);
    }

    /**
     * Retire et renvoie la demande de ce joueur — <strong>atomiquement</strong>.
     *
     * <p>C'est l'opération à utiliser pour confirmer. Deux clics simultanés sur le bouton de
     * validation ne peuvent donc pas confirmer deux fois la même demande : le second ne trouve plus
     * rien. Une demande expirée est retirée aussi, mais renvoyée vide — elle ne doit rien créer, et
     * l'appelant doit pouvoir le dire au joueur.</p>
     */
    public Optional<PendingBuildingSite> take(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        PendingBuildingSite site = pending.remove(playerId);
        if (site == null || site.expiredAt(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(site);
    }

    /**
     * Y avait-il une demande, expirée ou non ? Sert à distinguer « vous n'avez rien demandé » de
     * « votre demande a expiré », deux messages très différents pour l'utilisateur.
     */
    public boolean hadPending(String playerId) {
        return playerId != null && pending.containsKey(playerId);
    }

    /** Abandonne la demande de ce joueur. Aucune écriture, aucun identifiant consommé. */
    public void cancel(String playerId) {
        if (playerId != null) {
            pending.remove(playerId);
        }
    }

    /**
     * Retire les demandes expirées. Appelée périodiquement pour borner la table ; la correction ne
     * dépend pas d'elle, puisque {@link #find} et {@link #take} vérifient déjà l'expiration.
     *
     * @return les joueurs dont la demande vient d'expirer, pour pouvoir les en informer
     */
    public List<String> purgeExpired() {
        Instant now = clock.instant();
        List<String> expired = pending.entrySet().stream()
                .filter(e -> e.getValue().expiredAt(now))
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(pending::remove);
        return expired;
    }

    /** Nombre de demandes en attente — diagnostic et tests. */
    public int size() {
        return pending.size();
    }
}
