package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * {@link WorldPortalEntryGuard} du passage Hub → Wild (issue #161, qui couvre et remplace la
 * partie B de #26) : avant toute téléportation vers le monde d'exploration configuré, le joueur
 * reçoit un avertissement de danger <strong>générique</strong> et doit confirmer explicitement son
 * départ.
 *
 * <p><strong>Aucune inspection d'inventaire</strong> (décision du 2026-10-07, qui remplace la
 * conception initiale de #26 partie B et l'avertissement « sans Rune de rappel » livré
 * précédemment) : ni nourriture, ni arme, ni outil, ni Rune, ni kit de départ, ni « gear score » —
 * le texte ne prétend jamais juger la préparation du joueur, il énonce le risque de la zone. Un
 * seul chemin, le même pour tous.</p>
 *
 * <p>Déroulé :</p>
 * <ol>
 *   <li>un portail dont la destination n'est pas {@code travel.wild-world} n'est jamais concerné ;</li>
 *   <li>sinon le passage immédiat est toujours refusé ({@code allowEntry} renvoie {@code false}) et
 *       ce service devient propriétaire de la suite — la préférence « ne plus afficher » vit en base
 *       et se lit de façon asynchrone, donc aucune décision ne peut être rendue de façon
 *       synchrone ;</li>
 *   <li>préférence absente → avertissement avec trois actions explicites : partir, partir en
 *       masquant définitivement l'avertissement, annuler. Fermer la fenêtre n'exécute rien, donc
 *       équivaut à annuler ;</li>
 *   <li>préférence présente ({@link #WARNING_HIDDEN_KEY} = {@code "true"}) → départ direct, mais le
 *       retour visuel de préparation reste affiché : l'option masque l'avertissement, jamais le
 *       reste du parcours ni les contrôles de sécurité ;</li>
 *   <li>après confirmation : message de préparation immédiat, puis téléportation au tick suivant via
 *       {@link PortalTeleporter#teleportNow} (qui conserve RANDOM_SAFE, le répit d'arrivée et les
 *       contrôles de sécurité, et annonce lui-même la réussite ou l'échec).</li>
 * </ol>
 *
 * <p><strong>Une seule demande en vol par joueur</strong> ({@link #inFlight}) : plusieurs pas dans
 * la zone ou plusieurs clics ne lancent jamais deux lectures, deux recherches de point sûr ou deux
 * téléportations. Le jeton est relâché dès que l'avertissement est affiché (l'avertissement lui-même
 * tient alors le joueur) et seulement après la fin du départ dans le cas d'une confirmation ; une
 * déconnexion le relâche aussi ({@link #onQuit}), et le départ différé d'un joueur déconnecté est
 * abandonné — jamais de téléportation tardive parasite.</p>
 *
 * <p><strong>Pas de boucle de menu</strong> : après une annulation, {@code
 * WorldPortalTeleportListener} ne reconsulte aucun garde tant que le joueur reste dans la même zone
 * de portail (il ne réagit qu'à une véritable transition extérieur → intérieur). Il faut donc
 * ressortir et rentrer — une nouvelle intervention explicite — pour revoir l'avertissement. Aucun
 * minuteur anti-spam n'est nécessaire, et aucun n'est posé.</p>
 */
public final class WildEntryWarningService implements WorldPortalEntryGuard, Listener {

    /**
     * Clé de {@code player_variables} portant le choix « Ne plus afficher cet avertissement » :
     * {@code "true"} = masqué. Absence de ligne = avertissement affiché (comportement par défaut
     * d'un nouveau joueur) ; réutilise la table existante, donc aucune migration de schéma, et
     * {@code /rpgadmin player resetnew} rétablit l'avertissement sans code dédié puisqu'il efface
     * toutes les variables du joueur.
     */
    static final String WARNING_HIDDEN_KEY = "WILD_ENTRY_WARNING_HIDDEN";
    private static final String HIDDEN = "true";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    static final String WARNING_TEXT =
            "Le Wild est une zone dangereuse. Le PvP y est autorisé : d'autres joueurs peuvent vous "
                    + "attaquer. Vous pouvez mourir et perdre les objets de votre inventaire. Voulez-vous continuer ?";
    static final String GUARD_HINT = "Le Garde peut vous renseigner sur les conditions actuelles du Wild.";
    static final String PREPARING_TEXT = "Recherche d'un point d'arrivée sûr… Téléportation en préparation.";
    static final String ENTER_LABEL = "Entrer dans le Wild";
    static final String ENTER_AND_HIDE_LABEL = "Entrer et ne plus afficher cet avertissement";
    static final String CANCEL_LABEL = "Annuler";
    static final String CANCEL_TEXT = "Vous restez au Hub.";

    private final RPGQuestPlugin plugin;
    private final PlayerVariableRepository variableRepository;
    private final Supplier<String> wildWorld;
    private final PortalTeleporter teleporter;
    private final WildEntryPromptPresenter presenter;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public WildEntryWarningService(RPGQuestPlugin plugin, PlayerVariableRepository variableRepository,
                                    Supplier<String> wildWorld, PortalTeleporter teleporter,
                                    WildEntryPromptPresenter presenter) {
        this.plugin = plugin;
        this.variableRepository = variableRepository;
        this.wildWorld = wildWorld;
        this.teleporter = teleporter;
        this.presenter = presenter;
    }

    @Override
    public boolean allowEntry(Player player, WorldPortalDefinition portal) {
        if (!portal.destinationWorld().equals(wildWorld.get())) {
            return true; // pas un portail vers le Wild : jamais concerné.
        }
        UUID playerId = player.getUniqueId();
        if (!inFlight.add(playerId)) {
            return false; // une demande de ce joueur est déjà en vol : ni relecture, ni second départ.
        }
        variableRepository.get(playerId, WARNING_HIDDEN_KEY).whenComplete((stored, error) -> runOnMainThread(() -> {
            if (!player.isOnline()) {
                inFlight.remove(playerId);
                return;
            }
            if (error != null) {
                plugin.getSLF4JLogger().error(
                        "Impossible de lire la préférence d'avertissement d'entrée dans le Wild de {} : "
                                + "avertissement affiché par défaut.", playerId, error);
                showWarning(player, portal);
                return;
            }
            if (HIDDEN.equalsIgnoreCase(stored.orElse(""))) {
                beginTeleport(player, portal); // avertissement masqué : départ direct, préparation visible.
                return;
            }
            showWarning(player, portal);
        }));
        return false;
    }

    /** Relâche un jeton resté en vol si le joueur se déconnecte entre-temps. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inFlight.remove(event.getPlayer().getUniqueId());
    }

    private void showWarning(Player player, WorldPortalDefinition portal) {
        UUID playerId = player.getUniqueId();
        WildEntryPrompt prompt = new WildEntryPrompt(
                MM.deserialize("<gold>⚠ Entrée dans le Wild</gold>"),
                List.of(MM.deserialize("<white>" + WARNING_TEXT + "</white>"),
                        MM.deserialize("<gray>" + GUARD_HINT + "</gray>")),
                List.of(new WildEntryPromptButton(MM.deserialize("<green>" + ENTER_LABEL + "</green>"),
                                () -> onConfirm(playerId, portal, false)),
                        new WildEntryPromptButton(MM.deserialize("<yellow>" + ENTER_AND_HIDE_LABEL + "</yellow>"),
                                () -> onConfirm(playerId, portal, true)),
                        new WildEntryPromptButton(MM.deserialize("<red>" + CANCEL_LABEL + "</red>"),
                                () -> onCancel(playerId))));
        // Le jeton est relâché AVANT l'affichage : l'avertissement lui-même tient le joueur, et un
        // joueur qui ferme la fenêtre (= annulation silencieuse) ne doit jamais rester bloqué.
        inFlight.remove(playerId);
        presenter.present(player, prompt);
    }

    /**
     * Départ confirmé explicitement. {@code hideFutureWarnings} n'est enregistré qu'ici : le choix
     * « ne plus afficher » n'est mémorisé qu'avec une vraie confirmation de départ, jamais sur une
     * annulation ni sur une fermeture de fenêtre.
     */
    void onConfirm(UUID playerId, WorldPortalDefinition portal, boolean hideFutureWarnings) {
        Optional<Player> online = onlinePlayer(playerId);
        if (online.isEmpty()) {
            return;
        }
        if (!inFlight.add(playerId)) {
            return; // clic répété, ou départ déjà en cours : une seule téléportation.
        }
        if (hideFutureWarnings) {
            variableRepository.set(playerId, WARNING_HIDDEN_KEY, HIDDEN).exceptionally(error -> {
                plugin.getSLF4JLogger().error(
                        "Impossible d'enregistrer « ne plus afficher l'avertissement du Wild » pour {}", playerId, error);
                return null;
            });
        }
        beginTeleport(online.get(), portal);
    }

    /** Annulation explicite : le joueur reste où il est, rien n'est mémorisé. */
    void onCancel(UUID playerId) {
        onlinePlayer(playerId).ifPresent(player ->
                player.sendMessage(MM.deserialize("<gray>" + CANCEL_TEXT + "</gray>")));
    }

    /**
     * Retour visuel immédiat, puis téléportation au tick suivant. Le découpage en deux ticks est
     * volontaire : la recherche d'un point d'arrivée sûr charge/génère des chunks et peut donc durer
     * — le joueur doit avoir reçu le message d'attente <em>avant</em> que ce travail ne commence,
     * jamais après. Le jeton {@link #inFlight} n'est relâché qu'à la fin du départ, et un joueur
     * déconnecté entre-temps n'est jamais téléporté.
     */
    private void beginTeleport(Player player, WorldPortalDefinition portal) {
        UUID playerId = player.getUniqueId();
        player.sendMessage(MM.deserialize("<aqua>" + PREPARING_TEXT + "</aqua>"));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                onlinePlayer(playerId).ifPresent(online -> teleporter.teleportNow(online, portal));
            } finally {
                inFlight.remove(playerId);
            }
        });
    }

    private Optional<Player> onlinePlayer(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null && player.isOnline() ? Optional.of(player) : Optional.empty();
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** Visible pour les tests : une demande de ce joueur est-elle en vol ? */
    boolean hasRequestInFlight(UUID playerId) {
        return inFlight.contains(playerId);
    }
}
