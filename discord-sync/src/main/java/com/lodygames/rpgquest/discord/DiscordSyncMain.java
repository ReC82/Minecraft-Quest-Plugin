package com.lodygames.rpgquest.discord;

import com.lodygames.rpgquest.discord.config.BotConfig;
import com.lodygames.rpgquest.discord.config.BotConfigException;
import com.lodygames.rpgquest.discord.config.BotConfigLoader;
import com.lodygames.rpgquest.discord.discord.DiscordApi;
import com.lodygames.rpgquest.discord.discord.HttpDiscordApi;
import com.lodygames.rpgquest.discord.github.GitHubApi;
import com.lodygames.rpgquest.discord.github.HttpGitHubApi;
import com.lodygames.rpgquest.discord.http.RestClient;
import com.lodygames.rpgquest.discord.http.RestException;
import com.lodygames.rpgquest.discord.store.SyncStore;
import com.lodygames.rpgquest.discord.sync.ForumNotice;
import com.lodygames.rpgquest.discord.sync.Snowflake;
import com.lodygames.rpgquest.discord.sync.SyncService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Point d'entrée du service de synchronisation forum Discord ↔ issues GitHub (issue #202).
 *
 * <p>Sous-commandes :</p>
 * <ul>
 *   <li>{@code run} — boucle de synchronisation (c'est ce que lance systemd) ;</li>
 *   <li>{@code check} — diagnostic <strong>en lecture seule</strong> : configuration, identité du
 *       bot, permissions effectives, salon, dépôt. N'écrit <strong>rien</strong>, ni sur Discord
 *       ni sur GitHub. C'est la commande à lancer après toute modification de configuration ;</li>
 *   <li>{@code adopt <idSujet> [raison]} — prise en charge <strong>explicite</strong> d'un sujet
 *       existant (le sujet TEST, par exemple). Sans cela, aucun sujet antérieur au premier
 *       démarrage n'est importé ;</li>
 *   <li>{@code once} — un seul tour de synchronisation, puis sortie (utile en diagnostic) ;</li>
 *   <li>{@code notice} — imprime le texte d'information à coller dans les consignes du forum,
 *       adapté à la visibilité réelle du dépôt ;</li>
 *   <li>{@code status} — état local : repère temporel, sujets adoptés, appariements connus.</li>
 * </ul>
 *
 * <p>Toute sous-commande qui écrit peut être neutralisée par {@code LODYQUESTS_DRY_RUN=true} :
 * le service journalise alors ce qu'il <em>aurait</em> fait.</p>
 */
public final class DiscordSyncMain {

    private static final Logger LOG = Logger.getLogger(DiscordSyncMain.class.getName());

    private static final String USER_AGENT =
            "LodyQuests-DiscordSync/1.0 (+https://github.com/ReC82/Minecraft-Quest-Plugin)";

    private DiscordSyncMain() {
    }

    public static void main(String[] args) {
        String command = args.length == 0 ? "run" : args[0];
        try {
            switch (command) {
                case "run" -> run(load(), false);
                case "once" -> run(load(), true);
                case "check" -> System.exit(checkOrExplain() ? 0 : 1);
                case "adopt" -> adopt(load(), args);
                case "notice" -> notice(load());
                case "status" -> status(load());
                case "help", "-h", "--help" -> usage();
                default -> {
                    System.err.println("Sous-commande inconnue : " + command);
                    usage();
                    System.exit(2);
                }
            }
        } catch (BotConfigException e) {
            // Configuration inutilisable : message explicite, sans jamais citer un secret.
            LOG.log(Level.SEVERE, "Configuration refusée — {0}", e.getMessage());
            System.exit(78);  // EX_CONFIG
        } catch (RestException e) {
            LOG.log(Level.SEVERE, "Appel distant en échec — {0}", e.getMessage());
            System.exit(69);  // EX_UNAVAILABLE
        }
    }

    private static BotConfig load() {
        BotConfig config = BotConfigLoader.fromEnvironment();
        LOG.log(Level.INFO, "Configuration chargée : {0}", config);
        return config;
    }

    private static void usage() {
        System.out.println("""
                Synchronisation forum Discord ↔ issues GitHub (issue #202)

                  run                       boucle de synchronisation (service systemd)
                  once                      un seul tour, puis sortie
                  check                     diagnostic en lecture seule, n'écrit rien
                  adopt <idSujet> [raison]  prendre en charge explicitement un sujet existant
                  notice                    texte d'information à coller dans les consignes du forum
                  status                    état local (repère, sujets adoptés, appariements)

                Configuration : ~/.config/lodyquests-discord/bot.env (jamais versionné).
                Variables facultatives : LODYQUESTS_SYNC_DB, LODYQUESTS_POLL_SECONDS,
                LODYQUESTS_DRY_RUN.""");
    }

    // ---- Diagnostic en lecture seule -------------------------------------------------------

    /**
     * {@code check} doit rester utile <strong>même</strong> quand la configuration est refusée :
     * c'est précisément le moment où on en a besoin. On intercepte donc l'erreur de configuration
     * pour l'afficher comme un diagnostic lisible, suivi de la marche à suivre, au lieu de laisser
     * le gestionnaire général sortir en 78 avec une seule ligne de journal.
     */
    private static boolean checkOrExplain() {
        BotConfig config;
        try {
            config = BotConfigLoader.fromEnvironment();
        } catch (BotConfigException e) {
            System.out.println("— Configuration —");
            System.out.println("  REFUSÉE : " + e.getMessage());
            System.out.println();
            System.out.println("Rien n'a été écrit, ni sur GitHub ni sur Discord. Corriger "
                    + BotConfigLoader.DEFAULT_ENV_FILE + " (voir "
                    + "scripts/lodyquests-discord/bot.env.example), puis relancer « check ».");
            System.out.println("Le service redémarre ensuite de lui-même en moins de deux "
                    + "minutes : aucune commande supplémentaire n'est nécessaire.");
            return false;
        }
        System.out.println("— Configuration —");
        System.out.println("  " + config);
        return check(config);
    }

    /**
     * Vérifie tout ce qui peut l'être <strong>sans rien écrire</strong>. Renvoie {@code false} au
     * premier obstacle bloquant, en ayant dit lequel.
     */
    private static boolean check(BotConfig config) {
        RestClient client = new RestClient(USER_AGENT);
        boolean ok = true;

        System.out.println("— Dépôt GitHub —");
        GitHubApi github = new HttpGitHubApi(client, config.githubToken(),
                config.githubOwner(), config.githubRepo());
        GitHubApi.Repository repository;
        try {
            repository = github.repository();
            System.out.println("  dépôt      : " + repository.fullName());
            System.out.println("  visibilité : " + repository.visibility()
                    + (repository.isPublic()
                    ? "  → les signalements seront VISIBLES DE TOUS"
                    : "  → dépôt privé, les signalements ne seront pas publics"));
            System.out.println("  issues     : " + (repository.hasIssues() ? "activées" : "DÉSACTIVÉES"));
            if (!repository.hasIssues()) {
                ok = false;
            }
        } catch (RestException e) {
            System.out.println("  ÉCHEC : " + e.getMessage());
            System.out.println("  → vérifier GITHUB_TOKEN (permission « Issues : Read and write » "
                    + "sur ce seul dépôt) et GITHUB_REPOSITORY.");
            ok = false;
        }

        System.out.println("— Bot Discord —");
        DiscordApi discord = new HttpDiscordApi(client, config.discordBotToken());
        try {
            DiscordApi.Identity identity = discord.self();
            System.out.println("  bot        : " + identity.username() + " (" + identity.id() + ")");
        } catch (RestException e) {
            System.out.println("  ÉCHEC : " + e.getMessage());
            System.out.println("  → DISCORD_BOT_TOKEN est refusé par Discord. Le jeton a-t-il été "
                    + "réinitialisé depuis sa copie ? (Onglet « Bot » → « Reset Token ».)");
            return false;  // sans jeton valide, le reste du diagnostic Discord n'a aucun sens
        }

        try {
            long permissions = discord.guildPermissions(config.guildId());
            boolean administrator = (permissions & HttpDiscordApi.PERMISSION_ADMINISTRATOR) != 0;
            System.out.println("  serveur    : permissions " + permissions
                    + (administrator ? "  → ADMINISTRATEUR : à retirer" : "  → pas administrateur, correct"));
            if (administrator) {
                System.out.println("  → ce service n'a aucun besoin d'Administrateur. Lui donner "
                        + "seulement : voir le salon, lire l'historique, écrire dans les fils, et "
                        + "éventuellement gérer les fils pour les tags de statut.");
                ok = false;
            }
        } catch (RestException e) {
            System.out.println("  ÉCHEC permissions : " + e.getMessage());
            ok = false;
        }

        try {
            DiscordApi.ForumChannel forum = discord.forumChannel(config.forumChannelId());
            System.out.println("  forum      : « " + forum.name() + " » (type " + forum.type() + ")"
                    + (forum.isForum() ? "" : "  → CE N'EST PAS UN SALON DE FORUM"));
            ok &= forum.isForum();
            System.out.println("  tags       : " + (forum.availableTags().isEmpty()
                    ? "aucun"
                    : forum.availableTags().stream().map(DiscordApi.ForumTag::name).toList()));
            List<String> statusTags = forum.availableTags().stream()
                    .map(DiscordApi.ForumTag::name)
                    .filter(name -> ForumNotice.STATUS_TAG_NAMES.stream()
                            .anyMatch(expected -> expected.equalsIgnoreCase(name)))
                    .toList();
            System.out.println("  tags de statut reconnus : " + (statusTags.isEmpty()
                    ? "aucun (le statut sera annoncé par message seulement — ce n'est pas une erreur)"
                    : statusTags));
        } catch (RestException e) {
            System.out.println("  ÉCHEC forum : " + e.getMessage());
            System.out.println("  → le bot voit-il ce salon ? Vérifier DISCORD_FORUM_CHANNEL_ID et "
                    + "la permission « Voir le salon ».");
            ok = false;
        }

        try {
            int active = discord.activePosts(config.guildId(), config.forumChannelId()).size();
            System.out.println("  sujets actifs lus : " + active);
        } catch (RestException e) {
            System.out.println("  ÉCHEC lecture des sujets : " + e.getMessage());
            ok = false;
        }

        System.out.println("— État local —");
        try (SyncStore store = new SyncStore(config.databasePath())) {
            System.out.println("  base       : " + config.databasePath());
            System.out.println("  repère     : " + store.state(SyncServiceStateKeys.WATERMARK)
                    .map(w -> w + " (" + Snowflake.instantOf(w) + ")")
                    .orElse("non posé — sera posé au premier démarrage"));
            System.out.println("  adoptés    : " + store.adoptedThreads());
            System.out.println("  appariements : " + store.allLinks().size());
        } catch (RuntimeException e) {
            System.out.println("  ÉCHEC base d'état : " + e.getMessage());
            ok = false;
        }

        System.out.println(ok
                ? "\nDiagnostic : configuration utilisable."
                : "\nDiagnostic : au moins un point bloquant ci-dessus. Rien n'a été écrit.");
        return ok;
    }

    // ---- Commandes utilitaires -------------------------------------------------------------

    private static void adopt(BotConfig config, String[] args) {
        if (args.length < 2) {
            System.err.println("Usage : adopt <idSujet> [raison]");
            System.exit(2);
            return;
        }
        String threadId = args[1];
        String reason = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length))
                : "adoption explicite";
        try (SyncStore store = new SyncStore(config.databasePath())) {
            store.adopt(threadId, reason);
            System.out.println("Sujet " + threadId + " adopté (" + reason + "). Il sera traité au "
                    + "prochain tour, même s'il est antérieur au repère temporel.");
        }
    }

    private static void notice(BotConfig config) {
        RestClient client = new RestClient(USER_AGENT);
        GitHubApi github = new HttpGitHubApi(client, config.githubToken(),
                config.githubOwner(), config.githubRepo());
        GitHubApi.Repository repository = github.repository();
        System.out.println(ForumNotice.text(repository));
    }

    private static void status(BotConfig config) {
        try (SyncStore store = new SyncStore(config.databasePath())) {
            System.out.println("base         : " + config.databasePath());
            System.out.println("repère       : " + store.state(SyncServiceStateKeys.WATERMARK)
                    .map(w -> w + " (" + Snowflake.instantOf(w) + ")")
                    .orElse("non posé"));
            System.out.println("sujets adoptés : " + store.adoptedThreads());
            System.out.println("appariements :");
            for (SyncStore.Link link : store.allLinks()) {
                System.out.printf("  sujet %s → issue %s | %s | statut annoncé %s | lien publié %s"
                                + " | tentatives %d%s%n",
                        link.threadId(),
                        link.issueNumber() == null ? "(aucune)" : "#" + link.issueNumber(),
                        link.state(),
                        link.lastStatus() == null ? "(aucun)" : link.lastStatus(),
                        link.linkPosted() ? "oui" : "non",
                        link.attempts(),
                        link.lastError() == null ? "" : " | dernière erreur : " + link.lastError());
            }
        }
    }

    // ---- Boucle de service -----------------------------------------------------------------

    private static void run(BotConfig config, boolean onlyOnce) {
        RestClient client = new RestClient(USER_AGENT);
        DiscordApi discord = new HttpDiscordApi(client, config.discordBotToken());
        GitHubApi github = new HttpGitHubApi(client, config.githubToken(),
                config.githubOwner(), config.githubRepo());

        try (SyncStore store = new SyncStore(config.databasePath())) {
            SyncService service = new SyncService(config, discord, github, store);
            service.prepare();

            DiscordApi.Identity identity = discord.self();
            LOG.log(Level.INFO, "Service prêt : bot « {0} », forum {1}, dépôt {2}, scrutation {3} s"
                            + "{4}.",
                    new Object[] {identity.username(), config.forumChannelId(),
                            config.githubRepository(), config.pollInterval().toSeconds(),
                            config.dryRun() ? " [SIMULATION : aucune écriture]" : ""});

            AtomicBoolean running = new AtomicBoolean(true);
            Thread main = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Arrêt demandé : fin du tour en cours, puis sortie.");
                running.set(false);
                main.interrupt();
            }, "discord-sync-shutdown"));

            do {
                try {
                    SyncService.Round round = service.runOnce();
                    if (round.created() > 0 || round.contentUpdated() > 0
                            || round.statusAnnounced() > 0 || round.reconciled() > 0) {
                        LOG.log(Level.INFO, "Tour terminé : {0} créée(s), {1} contenu(s) mis à "
                                        + "jour, {2} statut(s) annoncé(s), {3} réconciliée(s).",
                                new Object[] {round.created(), round.contentUpdated(),
                                        round.statusAnnounced(), round.reconciled()});
                    }
                    round.problems().forEach(problem ->
                            LOG.log(Level.WARNING, "Problème signalé : {0}", problem));
                } catch (RestException e) {
                    // Un tour en échec ne tue pas le service : le suivant réessaiera, et l'état
                    // persistant garantit qu'aucun doublon n'est créé entre-temps.
                    LOG.log(Level.WARNING, "Tour en échec, nouvelle tentative au tour suivant : {0}",
                            e.getMessage());
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "Erreur inattendue pendant le tour : " + e.getMessage(), e);
                }
                if (onlyOnce) {
                    return;
                }
            } while (sleep(config.pollInterval(), running));
            LOG.info("Service arrêté proprement.");
        }
    }

    /** @return vrai s'il faut continuer, faux si un arrêt a été demandé */
    private static boolean sleep(Duration duration, AtomicBoolean running) {
        if (!running.get()) {
            return false;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return running.get();
    }

    /** Clés d'état exposées pour le diagnostic, sans élargir l'API de {@link SyncService}. */
    private static final class SyncServiceStateKeys {
        static final String WATERMARK = "watermark_thread_id";

        private SyncServiceStateKeys() {
        }
    }
}
