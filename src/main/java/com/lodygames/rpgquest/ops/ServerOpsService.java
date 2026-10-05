package com.lodygames.rpgquest.ops;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * Opérations d'exploitation exécutées <strong>par le serveur</strong> (issue #95, lot 1) : annonce
 * globale aux joueurs connectés, et lecture du tampon de console. Appelée uniquement par les actions
 * agent whitelistées {@code server.announce} et {@code server.logs.tail}.
 *
 * <p><strong>Un message n'est jamais une commande.</strong> Le texte fourni par l'administrateur est
 * envoyé en {@link Component#text(String) texte littéral} : il n'est ni passé à MiniMessage, ni
 * exécuté. Deux raisons, et la seconde est la vraie : un {@code <click:run_command:…>} glissé dans
 * une annonce ferait exécuter une commande à <em>tous</em> les joueurs qui cliquent. Seul le préfixe
 * du serveur est mis en forme, par nous.</p>
 *
 * <p><strong>Trois canaux, et seulement ceux qui existent.</strong> {@code chat},
 * {@code actionbar} et {@code title} correspondent chacun à une méthode publique Paper/Adventure
 * ({@code sendMessage}, {@code sendActionBar}, {@code showTitle}). Aucun autre canal n'est proposé
 * au panel : il n'y en a pas d'autre qui soit réellement supporté.</p>
 */
public final class ServerOpsService {

    /** Canaux réellement supportés — miroir strict de la validation côté panel. */
    public static final Set<String> CHANNELS = Set.of("chat", "actionbar", "title");
    /** Longueur maximale d'une annonce : au-delà, l'affichage en jeu n'est plus lisible. */
    public static final int MAX_MESSAGE_CHARS = 200;
    /** Un titre plein écran reste court : le reste irait au-delà de la zone lisible. */
    private static final int MAX_TITLE_CHARS = 120;

    /**
     * Résultat structuré, tel que le panel l'affiche.
     *
     * @param ok         {@code false} = refusé (message invalide / canal inconnu) ou échec
     * @param code       {@code SENT} / {@code NO_PLAYERS} / {@code INVALID_MESSAGE} /
     *                   {@code INVALID_CHANNEL} / {@code ERROR}
     * @param message    phrase lisible pour l'administrateur
     * @param channel    canal effectivement utilisé ({@code null} si refusé)
     * @param recipients nombre de joueurs qui ont réellement reçu l'annonce
     * @param online     nombre de joueurs connectés au moment de l'envoi
     */
    public record AnnounceOutcome(boolean ok, String code, String message, String channel,
                                  int recipients, int online) {

        static AnnounceOutcome reject(String code, String message) {
            return new AnnounceOutcome(false, code, message, null, 0, 0);
        }
    }

    private final RPGQuestPlugin plugin;
    private final ServerLogBuffer logBuffer;
    private final Supplier<Optional<String>> consoleLimitation;

    public ServerOpsService(RPGQuestPlugin plugin, ServerLogBuffer logBuffer,
                            Supplier<Optional<String>> consoleLimitation) {
        this.plugin = plugin;
        this.logBuffer = logBuffer;
        this.consoleLimitation = consoleLimitation;
    }

    /**
     * Diffuse {@code message} à tous les joueurs connectés. <strong>À appeler sur le thread
     * principal</strong> (envoi à des entités Bukkit).
     *
     * <p>Aucun joueur connecté n'est pas un échec : c'est un <em>fait</em>, renvoyé comme tel
     * ({@code NO_PLAYERS}). Prétendre « annonce envoyée » alors que personne ne l'a vue serait le
     * seul vrai défaut possible ici.</p>
     */
    public AnnounceOutcome announce(String message, String channel) {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE_CHARS) {
            return AnnounceOutcome.reject("INVALID_MESSAGE",
                    "Message vide ou trop long (max " + MAX_MESSAGE_CHARS + " caractères).");
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return AnnounceOutcome.reject("INVALID_MESSAGE", "Message multi-ligne refusé.");
        }
        if (text.startsWith("/")) {
            // Garde-fou explicite : une annonce est un texte. Un administrateur qui tape « /say … »
            // attend une commande — le lui refuser clairement vaut mieux que diffuser « /say … ».
            return AnnounceOutcome.reject("INVALID_MESSAGE",
                    "Une annonce est un texte, pas une commande : retirer le « / » initial.");
        }
        String chan = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (!CHANNELS.contains(chan)) {
            return AnnounceOutcome.reject("INVALID_CHANNEL",
                    "Canal inconnu. Canaux supportés : chat, actionbar, title.");
        }

        var players = plugin.getServer().getOnlinePlayers();
        int online = players.size();
        if (online == 0) {
            return new AnnounceOutcome(true, "NO_PLAYERS",
                    "Aucun joueur connecté : l'annonce n'a été affichée à personne.", chan, 0, 0);
        }
        Component body = Component.text(text, NamedTextColor.WHITE);
        Component prefixed = Component.text("[Serveur] ", NamedTextColor.GOLD).append(body);
        int recipients = 0;
        for (Player player : players) {
            try {
                switch (chan) {
                    case "chat" -> player.sendMessage(prefixed);
                    case "actionbar" -> player.sendActionBar(prefixed);
                    default -> player.showTitle(Title.title(
                            Component.text("Annonce", NamedTextColor.GOLD),
                            Component.text(shortenForTitle(text), NamedTextColor.WHITE),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                }
                recipients++;
            } catch (RuntimeException e) {
                // Un joueur qui se déconnecte pendant la boucle ne doit pas annuler l'annonce des
                // autres : on compte ce qui est réellement parti, jamais ce qu'on espérait envoyer.
                plugin.getSLF4JLogger().warn("[ops] Annonce non délivrée à {} : {}",
                        player.getName(), e.getClass().getSimpleName());
            }
        }
        String summary = recipients == online
                ? "Annonce envoyée à " + recipients + " joueur(s) via « " + chan + " »."
                : "Annonce envoyée à " + recipients + " joueur(s) sur " + online + " connecté(s) via « "
                        + chan + " » — voir la console pour les échecs.";
        plugin.getSLF4JLogger().info("[ops] Annonce « {} » canal={} destinataires={}/{}",
                text, chan, recipients, online);
        return new AnnounceOutcome(true, "SENT", summary, chan, recipients, online);
    }

    /** Extrait de console. Sans effet, appelable depuis n'importe quel thread. */
    public ServerLogBuffer.Snapshot logs(long afterSequence, int limit) {
        return logBuffer.tail(afterSequence, limit);
    }

    /**
     * Motif d'indisponibilité de la capture de console, s'il y en a un — à afficher tel quel par le
     * panel. {@link Optional#empty()} = la console est bien captée.
     */
    public Optional<String> consoleLimitation() {
        return consoleLimitation.get();
    }

    private static String shortenForTitle(String text) {
        return text.length() <= MAX_TITLE_CHARS ? text : text.substring(0, MAX_TITLE_CHARS) + "…";
    }
}
