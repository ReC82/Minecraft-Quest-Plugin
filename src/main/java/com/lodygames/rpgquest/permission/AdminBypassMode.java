package com.lodygames.rpgquest.permission;

import com.lodygames.rpgquest.settings.GameSettings;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Issue #35 (avec #321) — mode bypass administrateur <strong>explicite</strong> : la seule porte par
 * laquelle un compte peut contourner une règle de gameplay RPGQuest (accès et règles du monde des
 * claims, claims d'autrui, zones protégées, combat dans le Hub).
 *
 * <p><strong>Ce qui ne marchait pas.</strong> Les nœuds de bypass étaient consultés directement par
 * chaque écouteur. Or l'ombrelle {@link RpgPermissions#LEGACY_ADMIN_WORLD} (et
 * {@code rpgquest.admin.hub.combat}) ont {@code default: op} : tout opérateur contournait donc ces
 * règles en silence, sans aucun geste ni indication. Un OP qui testait le parcours d'un joueur entrait
 * dans le monde des claims sans {@code CLAIM_TIER_1} (#321) et croyait la protection cassée.</p>
 *
 * <p><strong>Règle désormais appliquée.</strong> Un contournement exige, cumulativement :
 * <ol>
 *   <li>une session ouverte par le joueur lui-même ({@code /rpgadmin bypass on}), qui exige
 *       {@link #PERMISSION} ;</li>
 *   <li>le nœud de bypass propre à la règle ({@link RpgPermissions#hasBypass}) — le découpage #200
 *       reste entier : activer le mode n'ajoute aucun droit, il « arme » ceux que le compte a déjà ;</li>
 *   <li>le mécanisme proposé sur ce serveur (PlugAdmin > Paramètres généraux > Administration,
 *       issue #335) et une session non expirée.</li>
 * </ol>
 * Être OP ne suffit plus. Les droits d'administration (commandes, outils) et de construction ne
 * passent jamais par ici et restent inchangés.</p>
 *
 * <p>État en mémoire seule : une déconnexion ou un redémarrage remet tout le monde en mode normal,
 * ce qui est le défaut sûr. La visibilité (barre d'action permanente) et le nettoyage sont assurés
 * par {@link AdminBypassService}.</p>
 */
public final class AdminBypassMode {

    /** Permission d'ouvrir une session de bypass ({@code default: op}). N'accorde aucun bypass par elle-même. */
    public static final String PERMISSION = "rpgquest.admin.bypass";

    /** Pourquoi une activation a été refusée ou une session close. */
    public enum Outcome { ENABLED, NO_PERMISSION, UNAVAILABLE }

    /** Raison de la fin automatique d'une session. */
    public enum EndReason { EXPIRED, UNAVAILABLE, PERMISSION_LOST }

    /** État d'une session : depuis quand, et combien il reste ({@code -1} = jusqu'à la déconnexion). */
    public record Status(boolean active, long sinceMillis, long remainingMillis) {
        static final Status INACTIVE = new Status(false, 0L, 0L);
    }

    private record Session(long since, long until) {
    }

    private final Supplier<GameSettings> settings;
    private final LongSupplier clock;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AdminBypassMode(Supplier<GameSettings> settings) {
        this(settings, System::currentTimeMillis);
    }

    AdminBypassMode(Supplier<GameSettings> settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** Ouvre (ou rouvre, en repartant de zéro) la session du joueur. */
    public Outcome enable(Player player) {
        if (player == null || !canOpen(player)) {
            return Outcome.NO_PERMISSION;
        }
        GameSettings s = settings();
        if (!s.adminBypassAvailable()) {
            return Outcome.UNAVAILABLE;
        }
        long now = clock.getAsLong();
        long until = s.adminBypassMaxMinutes() > 0 ? now + s.adminBypassMaxMinutes() * 60_000L : Long.MAX_VALUE;
        sessions.put(player.getUniqueId(), new Session(now, until));
        return Outcome.ENABLED;
    }

    /** Ferme la session ; {@code true} si elle était ouverte. */
    public boolean disable(UUID playerId) {
        return playerId != null && sessions.remove(playerId) != null;
    }

    /**
     * Session ouverte et toujours valable ? Lecture seule : une session devenue invalide est
     * considérée inactive tout de suite, mais n'est close que par {@link #endReason}, pour que le
     * joueur soit prévenu de la raison.
     */
    public boolean isActive(Player player) {
        if (player == null) {
            return false;
        }
        Session s = sessions.get(player.getUniqueId());
        return s != null && reasonToEnd(player, s) == null;
    }

    /**
     * Le joueur peut-il, <em>à cet instant</em>, contourner la règle protégée par {@code bypassNode} ?
     * Session active <strong>et</strong> nœud dédié (ou ombrelle historique).
     */
    public boolean allows(Player player, String bypassNode) {
        return isActive(player) && RpgPermissions.hasBypass(player, bypassNode);
    }

    /** Variante pour un nœud sans ombrelle (ex. {@code rpgquest.admin.hub.combat}). */
    public boolean allowsExact(Player player, String permission) {
        return isActive(player) && player.hasPermission(permission);
    }

    public Status status(Player player) {
        if (!isActive(player)) {
            return Status.INACTIVE;
        }
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return Status.INACTIVE;
        }
        long now = clock.getAsLong();
        return new Status(true, s.since(), s.until() == Long.MAX_VALUE ? -1L : Math.max(0L, s.until() - now));
    }

    /** Joueurs ayant une session ouverte (petit ensemble : seulement les administrateurs actifs). */
    public List<UUID> activePlayers() {
        return List.copyOf(sessions.keySet());
    }

    /**
     * Raison pour laquelle la session de {@code player} doit se terminer maintenant, ou {@code null}
     * si elle reste valable (ou n'existe pas). Une session invalide est retirée.
     */
    public EndReason endReason(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return null;
        }
        EndReason reason = reasonToEnd(player, s);
        if (reason != null) {
            sessions.remove(player.getUniqueId());
        }
        return reason;
    }

    private EndReason reasonToEnd(Player player, Session s) {
        return !settings().adminBypassAvailable() ? EndReason.UNAVAILABLE
                : !canOpen(player) ? EndReason.PERMISSION_LOST
                : clock.getAsLong() >= s.until() ? EndReason.EXPIRED
                : null;
    }

    /**
     * Nœud dédié, ou l'ombrelle historique qui « implique tout » : depuis #35 (réouverture du 23/10),
     * un administrateur non OP portant seulement {@link RpgPermissions#LEGACY_ADMIN_WORLD} construit
     * au Hub via une session — il doit donc pouvoir l'ouvrir.
     */
    private static boolean canOpen(Player player) {
        return RpgPermissions.hasBypass(player, PERMISSION);
    }

    /** Arrêt du plugin : aucune session ne survit. */
    public void clear() {
        sessions.clear();
    }

    private GameSettings settings() {
        GameSettings s = settings.get();
        return s == null ? GameSettings.DEFAULTS : s;
    }
}
