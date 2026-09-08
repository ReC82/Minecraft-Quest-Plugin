package com.lodygames.rpgquest.spawn;

import java.util.Locale;

/**
 * Décision <strong>pure</strong> prise à la connexion d'un joueur ({@code PlayerSpawnLocationEvent})
 * : faut-il rediriger sa position d'arrivée vers le spawn du village configuré, ou laisser la
 * position que Paper vient de restaurer depuis les données du joueur ? — issue #87.
 *
 * <h2>Pourquoi cette classe existe</h2>
 * <p>Le seul signal « nouveau joueur » de Bukkit, {@code Player#hasPlayedBefore()}, n'est
 * <strong>pas fiable</strong> : il renvoie {@code false} pour un joueur qui a pourtant déjà joué
 * dès que la métadonnée Bukkit {@code bukkit.firstPlayed} de son fichier {@code playerdata} est
 * absente ou pas encore peuplée — migration / transfert de serveur (cas VeryGames), bascule
 * hors-ligne ↔ en-ligne des UUID, restauration depuis une sauvegarde, ou simple aléa de timing
 * dans la séquence de login. Quand il se trompe pour un joueur qui était dans le Wild, l'ancien
 * code écrasait sa position réelle et le renvoyait au Hub — un échappatoire gratuit du Wild
 * (issue #87).</p>
 *
 * <h2>Règle</h2>
 * <p>On ne redirige vers le spawn du village que si <strong>toutes</strong> ces conditions sont
 * réunies :</p>
 * <ol>
 *   <li>un spawn de village est réellement configuré ({@code spawn.yml} présent et son monde
 *       chargé) ;</li>
 *   <li>{@code hasPlayedBefore()} vaut {@code false} (heuristique « jamais vu ») ;</li>
 *   <li><strong>et</strong> Paper place le joueur dans un monde où un tout nouveau joueur
 *       <em>apparaît légitimement</em> : le <strong>monde principal</strong> du serveur
 *       ({@code getServer().getWorlds().get(0)}) ou le <strong>monde Hub</strong> configuré
 *       ({@code hub.world}).</li>
 * </ol>
 * <p>Si Paper restaure déjà le joueur dans <em>n'importe quel autre monde chargé</em> (Wild,
 * monde des claims, …), c'est une session antérieure bien réelle : on ne touche jamais à sa
 * position, quel que soit le verdict de {@code hasPlayedBefore()}. Le repli Hub légitime
 * (monde précédent disparu) reste couvert : dans ce cas Paper renvoie lui-même le joueur au
 * monde principal, ce qui retombe sur la condition&nbsp;3.</p>
 *
 * <p>Point d'intégration futur (anti-combat-logging, issue #88) : cette décision est une fonction
 * pure ; un {@code KEEP_VANILLA_LOCATION} pourra plus tard être remplacé par une pénalité dédiée
 * si le {@code PlayerQuitEvent} précédent a marqué « déconnexion en combat ». Rien de tel n'est
 * implémenté ici.</p>
 */
public final class JoinSpawnPolicy {

    /** {@code REDIRECT_TO_CONFIGURED_SPAWN} : onboarding vers le village. {@code KEEP_VANILLA_LOCATION} : ne pas toucher. */
    public enum Decision {
        REDIRECT_TO_CONFIGURED_SPAWN,
        KEEP_VANILLA_LOCATION
    }

    private JoinSpawnPolicy() {
    }

    /**
     * @param spawnConfigured   un spawn de village est défini et résolvable
     * @param hasPlayedBefore   verdict (peu fiable) de {@code Player#hasPlayedBefore()}
     * @param incomingWorldName monde dans lequel Paper s'apprête à faire apparaître le joueur
     * @param primaryWorldName  nom du monde principal du serveur ({@code getWorlds().get(0)})
     * @param hubWorldName      nom du monde Hub configuré ({@code hub.world}), éventuellement {@code null}
     */
    public static Decision decide(boolean spawnConfigured, boolean hasPlayedBefore,
                                  String incomingWorldName, String primaryWorldName, String hubWorldName) {
        if (!spawnConfigured || hasPlayedBefore) {
            return Decision.KEEP_VANILLA_LOCATION;
        }
        String incoming = normalize(incomingWorldName);
        if (incoming == null) {
            // Monde d'arrivée inconnu : on ne prend pas le risque de déplacer le joueur.
            return Decision.KEEP_VANILLA_LOCATION;
        }
        boolean landingWhereNewPlayersAppear =
                incoming.equals(normalize(primaryWorldName)) || incoming.equals(normalize(hubWorldName));
        return landingWhereNewPlayersAppear
                ? Decision.REDIRECT_TO_CONFIGURED_SPAWN
                : Decision.KEEP_VANILLA_LOCATION;
    }

    private static String normalize(String worldName) {
        return worldName == null || worldName.isBlank() ? null : worldName.toLowerCase(Locale.ROOT);
    }
}
