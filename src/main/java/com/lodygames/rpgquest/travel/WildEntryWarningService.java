package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.travel.model.PortalMessages;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
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
 *
 * <p><strong>Issue #569 (#228-P6) — avertissement par portail.</strong> Le même garde sert tout
 * portail entre mondes : {@code warning.mode} ({@code DEFAULT} = vers le Wild seulement, {@code ALWAYS},
 * {@code NEVER}), textes et libellés propres au portail (vides = textes ci-dessous, inchangés pour
 * {@code hub_to_wild}), option « ne plus afficher » activable. La préférence est mémorisée <em>par
 * joueur et par portail</em> ({@link #hiddenKey}) ; l'ancienne clé {@link #WARNING_HIDDEN_KEY} reste
 * lue pour les portails vers le Wild, donc aucun joueur ne revoit un avertissement qu'il avait masqué.</p>
 */
public final class WildEntryWarningService implements WorldPortalEntryGuard, Listener {

    /**
     * Clé de {@code player_variables} portant le choix « Ne plus afficher cet avertissement » d'avant
     * #569 : {@code "true"} = masqué, pour <strong>tout</strong> portail vers le Wild. Toujours lue
     * pour un portail vers le Wild (reprise : un joueur qui l'a masqué ne le revoit pas), plus jamais
     * écrite — {@link #hiddenKey} la remplace. Absence de ligne = avertissement affiché ; {@code
     * /rpgadmin player resetnew} efface toutes les variables du joueur, donc les deux clés.
     */
    static final String WARNING_HIDDEN_KEY = "WILD_ENTRY_WARNING_HIDDEN";
    /** Préfixe de la préférence par portail (issue #569) : {@code PORTAL_WARNING_HIDDEN_<ID>}. */
    public static final String PORTAL_HIDDEN_PREFIX = "PORTAL_WARNING_HIDDEN_";
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

    /** Textes par défaut d'un portail vers le Wild : ceux d'avant #569, mise en forme comprise. */
    static final Texts WILD_DEFAULTS = new Texts("<gold>⚠ Entrée dans le Wild</gold>",
            "<white>" + WARNING_TEXT + "</white>", "<gray>" + GUARD_HINT + "</gray>",
            "<green>" + ENTER_LABEL + "</green>", "<yellow>" + ENTER_AND_HIDE_LABEL + "</yellow>",
            "<red>" + CANCEL_LABEL + "</red>", "<gray>" + CANCEL_TEXT + "</gray>",
            "<aqua>" + PREPARING_TEXT + "</aqua>", true);
    /** Textes par défaut d'un autre portail dont l'avertissement est activé (issue #569). */
    static final Texts GENERIC_DEFAULTS = new Texts("<gold>⚠ Avertissement</gold>",
            "<white>Ce portail mène vers une zone dangereuse. Voulez-vous continuer ?</white>", "",
            "<green>Entrer</green>", "<yellow>" + ENTER_AND_HIDE_LABEL + "</yellow>",
            "<red>" + CANCEL_LABEL + "</red>", "<gray>Vous restez ici.</gray>",
            "<aqua>" + PREPARING_TEXT + "</aqua>", true);

    /**
     * Textes effectifs d'un avertissement (MiniMessage), personnalisés ou par défaut : une seule
     * source pour le jeu et pour la fiche PlugAdmin. {@code hint} vide = pas de ligne d'indice.
     */
    public record Texts(String title, String text, String hint, String enter, String enterHide, String cancel,
                        String cancelText, String preparing, boolean allowHide) {
    }

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

    /**
     * Ce garde s'applique-t-il à ce portail ? Même règle en jeu et dans la page Portails de
     * PlugAdmin (issue #564) : par défaut, la destination est exactement {@code travel.wild-world} ;
     * depuis #569, l'avertissement du portail peut être forcé ({@code ALWAYS}) ou retiré ({@code NEVER}).
     */
    public static boolean appliesTo(WorldPortalDefinition portal, String wildWorld) {
        return portal.messages().warns(towardsWild(portal, wildWorld));
    }

    static boolean towardsWild(WorldPortalDefinition portal, String wildWorld) {
        return portal.destinationWorld().equals(wildWorld);
    }

    /** Préférence « ne plus afficher » de ce portail (issue #569), dans {@code player_variables}. */
    public static String hiddenKey(String portalId) {
        return PORTAL_HIDDEN_PREFIX + portalId.toUpperCase(Locale.ROOT);
    }

    /** Textes effectifs : ceux du portail, sinon ceux par défaut (Wild ou génériques). */
    public static Texts texts(WorldPortalDefinition portal, String wildWorld) {
        Texts d = towardsWild(portal, wildWorld) ? WILD_DEFAULTS : GENERIC_DEFAULTS;
        PortalMessages.Warning w = portal.messages().warning();
        return new Texts(or(w.title(), d.title()), or(w.text(), d.text()), or(w.hint(), d.hint()),
                or(w.enter(), d.enter()), or(w.enterHide(), d.enterHide()), or(w.cancel(), d.cancel()),
                or(w.cancelText(), d.cancelText()), or(portal.messages().preparing(), d.preparing()), w.allowHide());
    }

    private static String or(String custom, String fallback) {
        return custom.isEmpty() ? fallback : custom;
    }

    @Override
    public boolean allowEntry(Player player, WorldPortalDefinition portal) {
        String wild = wildWorld.get();
        if (!appliesTo(portal, wild)) {
            return true; // aucun avertissement pour ce portail.
        }
        UUID playerId = player.getUniqueId();
        if (!inFlight.add(playerId)) {
            return false; // une demande de ce joueur est déjà en vol : ni relecture, ni second départ.
        }
        Texts texts = texts(portal, wild);
        if (!texts.allowHide()) {
            // « Ne plus afficher » non proposé : avertissement toujours affiché, préférence ni lue ni effacée.
            runOnMainThread(() -> {
                if (player.isOnline()) {
                    showWarning(player, portal, texts);
                } else {
                    inFlight.remove(playerId);
                }
            });
            return false;
        }
        readHidden(playerId, portal, wild).whenComplete((hidden, error) -> runOnMainThread(() -> {
            if (!player.isOnline()) {
                inFlight.remove(playerId);
                return;
            }
            if (error != null) {
                plugin.getSLF4JLogger().error(
                        "Impossible de lire la préférence d'avertissement du portail {} de {} : "
                                + "avertissement affiché par défaut.", portal.id(), playerId, error);
                showWarning(player, portal, texts);
                return;
            }
            if (hidden) {
                beginTeleport(player, portal, texts); // avertissement masqué : départ direct, préparation visible.
                return;
            }
            showWarning(player, portal, texts);
        }));
        return false;
    }

    /**
     * Préférence masquée ? Clé du portail ; pour un portail vers le Wild, aussi l'ancienne clé
     * {@link #WARNING_HIDDEN_KEY} (reprise, issue #569).
     */
    private CompletableFuture<Boolean> readHidden(UUID playerId, WorldPortalDefinition portal, String wild) {
        CompletableFuture<Boolean> own = variableRepository.get(playerId, hiddenKey(portal.id()))
                .thenApply(WildEntryWarningService::isHidden);
        if (!towardsWild(portal, wild)) {
            return own;
        }
        return own.thenCompose(hidden -> hidden ? CompletableFuture.completedFuture(true)
                : variableRepository.get(playerId, WARNING_HIDDEN_KEY).thenApply(WildEntryWarningService::isHidden));
    }

    private static boolean isHidden(Optional<String> stored) {
        return HIDDEN.equalsIgnoreCase(stored.orElse(""));
    }

    /** Relâche un jeton resté en vol si le joueur se déconnecte entre-temps. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inFlight.remove(event.getPlayer().getUniqueId());
    }

    private void showWarning(Player player, WorldPortalDefinition portal, Texts texts) {
        UUID playerId = player.getUniqueId();
        List<Component> body = new ArrayList<>();
        body.add(MM.deserialize(texts.text()));
        if (!texts.hint().isEmpty()) {
            body.add(MM.deserialize(texts.hint()));
        }
        List<WildEntryPromptButton> buttons = new ArrayList<>();
        buttons.add(new WildEntryPromptButton(MM.deserialize(texts.enter()),
                () -> onConfirm(playerId, portal, false)));
        if (texts.allowHide()) {
            buttons.add(new WildEntryPromptButton(MM.deserialize(texts.enterHide()),
                    () -> onConfirm(playerId, portal, true)));
        }
        buttons.add(new WildEntryPromptButton(MM.deserialize(texts.cancel()), () -> onCancel(playerId, portal)));
        WildEntryPrompt prompt = new WildEntryPrompt(MM.deserialize(texts.title()), body, buttons);
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
            variableRepository.set(playerId, hiddenKey(portal.id()), HIDDEN).exceptionally(error -> {
                plugin.getSLF4JLogger().error(
                        "Impossible d'enregistrer « ne plus afficher l'avertissement » du portail {} pour {}",
                        portal.id(), playerId, error);
                return null;
            });
        }
        beginTeleport(online.get(), portal, texts(portal, wildWorld.get()));
    }

    /** Annulation explicite : le joueur reste où il est, rien n'est mémorisé. */
    void onCancel(UUID playerId, WorldPortalDefinition portal) {
        onlinePlayer(playerId).ifPresent(player ->
                player.sendMessage(MM.deserialize(texts(portal, wildWorld.get()).cancelText())));
    }

    /**
     * Retour visuel immédiat, puis téléportation au tick suivant. Le découpage en deux ticks est
     * volontaire : la recherche d'un point d'arrivée sûr charge/génère des chunks et peut donc durer
     * — le joueur doit avoir reçu le message d'attente <em>avant</em> que ce travail ne commence,
     * jamais après. Le jeton {@link #inFlight} n'est relâché qu'à la fin du départ, et un joueur
     * déconnecté entre-temps n'est jamais téléporté.
     */
    private void beginTeleport(Player player, WorldPortalDefinition portal, Texts texts) {
        UUID playerId = player.getUniqueId();
        player.sendMessage(MM.deserialize(texts.preparing()));
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
