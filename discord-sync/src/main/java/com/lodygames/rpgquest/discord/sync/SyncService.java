package com.lodygames.rpgquest.discord.sync;

import com.lodygames.rpgquest.discord.config.BotConfig;
import com.lodygames.rpgquest.discord.discord.DiscordApi;
import com.lodygames.rpgquest.discord.discord.ForumPost;
import com.lodygames.rpgquest.discord.github.GitHubApi;
import com.lodygames.rpgquest.discord.github.Issue;
import com.lodygames.rpgquest.discord.http.RestException;
import com.lodygames.rpgquest.discord.store.SyncStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronisation forum Discord ↔ issues GitHub (issue #202).
 *
 * <p><strong>Deux sens, deux autorités.</strong> Discord fait autorité sur le <em>contenu</em>
 * (titre, message initial) ; GitHub fait autorité sur le <em>statut</em>. Chaque tour de
 * synchronisation applique les deux sens, dans cet ordre.</p>
 *
 * <p><strong>Invariant principal : un sujet = une issue</strong>, y compris après redémarrage,
 * reconnexion, événement dupliqué ou réponse HTTP perdue. Il tient à un ordre d'écriture précis,
 * décrit dans {@link SyncStore} : l'intention de créer est enregistrée <em>avant</em> l'appel
 * réseau, donc une création engagée laisse toujours une trace. Après un résultat incertain, le
 * service <strong>réconcilie</strong> — il relit les issues et cherche son marqueur de sujet —
 * plutôt que de rejouer la création à l'aveugle.</p>
 *
 * <p><strong>Ce que ce service n'écrit jamais</strong> : les étiquettes de publication
 * {@code #news}/{@code #soon}, les origines inconnues (une issue sans marqueur de sujet n'est
 * jamais retouchée rétroactivement), les tags Bug/Suggestion d'un sujet, et les notes de triage
 * écrites hors de la zone gérée du corps d'issue.</p>
 */
public final class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class.getName());

    /** Étiquette d'origine : la marque que ce service a créé l'issue. */
    public static final String LABEL_SOURCE = "source:discord";

    /** Étiquette de tri initial. */
    public static final String LABEL_TRIAGE = "triage";

    /** Repère temporel sous lequel les sujets ne sont pas importés spontanément. */
    static final String STATE_WATERMARK = "watermark_thread_id";

    /** ETag du dernier relevé GitHub, pour des requêtes conditionnelles sans coût de quota. */
    static final String STATE_GITHUB_ETAG = "github_issues_etag";

    /**
     * Plus grand numéro d'issue jamais observé sur le dépôt. Sert de plancher au balayage de
     * réconciliation : sans lui, un service qui n'a encore rien apparié devrait sonder depuis le
     * numéro 1, et abandonnerait bien avant d'atteindre les numéros utiles.
     */
    static final String STATE_SCAN_FLOOR = "highest_issue_seen";

    /** Nombre d'issues relues par tour, et lors d'une réconciliation. */
    private static final int ISSUE_PAGE_LIMIT = 200;

    /** Discord n'accepte pas plus de 5 tags simultanés sur un sujet. */
    private static final int MAX_FORUM_TAGS = 5;

    /**
     * Délai de prudence avant de conclure qu'une création ambiguë n'a rien produit.
     *
     * <p>Mesuré sur le vrai dépôt : la liste des issues de GitHub ignore une issue qui vient
     * d'être créée pendant plusieurs dizaines de secondes. Tant que ce délai n'est pas écoulé,
     * « je ne trouve pas l'issue » ne veut pas dire « elle n'existe pas » : le service
     * <strong>attend</strong> plutôt que de recréer. Au pire le signalement arrive quelques
     * minutes plus tard ; jamais en double.</p>
     */
    private static final Duration RECONCILE_COOLDOWN = Duration.ofMinutes(3);

    /** Nombre de numéros d'issue sondés en avant lors d'une réconciliation. */
    private static final int FORWARD_SCAN_RANGE = 25;

    /** Trous consécutifs admis pendant le balayage (les numéros sont partagés avec les PR). */
    private static final int FORWARD_SCAN_MAX_MISSES = 5;

    private final BotConfig config;
    private final DiscordApi discord;
    private final GitHubApi github;
    private final SyncStore store;

    private DiscordApi.ForumChannel forum;
    private boolean repoPublic;

    public SyncService(BotConfig config, DiscordApi discord, GitHubApi github, SyncStore store) {
        this.config = config;
        this.discord = discord;
        this.github = github;
        this.store = store;
    }

    /** Bilan d'un tour, pour le journal et pour les tests. */
    public record Round(int created, int contentUpdated, int statusAnnounced, int reconciled,
                        int skipped, List<String> problems) {
    }

    /**
     * Prépare le service : vérifie le salon, la visibilité du dépôt, crée les étiquettes
     * manquantes et pose le repère temporel au premier démarrage.
     */
    public void prepare() {
        forum = discord.forumChannel(config.forumChannelId());
        if (!forum.isForum()) {
            throw new IllegalStateException("Le salon " + config.forumChannelId() + " n'est pas un "
                    + "salon de forum (type Discord " + forum.type() + ", attendu "
                    + DiscordApi.ForumChannel.TYPE_GUILD_FORUM + "). Vérifier "
                    + "DISCORD_FORUM_CHANNEL_ID.");
        }
        GitHubApi.Repository repository = github.repository();
        repoPublic = repository.isPublic();
        if (!repository.hasIssues()) {
            throw new IllegalStateException("Les issues sont désactivées sur "
                    + repository.fullName() + " : rien ne peut être créé.");
        }

        if (!config.dryRun()) {
            github.ensureLabel(LABEL_SOURCE, "5865F2",
                    "Signalement issu du forum communautaire Discord (issue #202)");
            github.ensureLabel(PostKind.BUG.githubLabel(), "D73A4A", "Défaut signalé");
            github.ensureLabel(PostKind.REQUEST.githubLabel(), "A2EEEF", "Demande ou idée");
            github.ensureLabel(LABEL_TRIAGE, "FBCA04", "À trier");
        }

        if (store.state(STATE_WATERMARK).isEmpty()) {
            String watermark = Snowflake.forInstant(Instant.now());
            store.putState(STATE_WATERMARK, watermark);
            LOG.log(Level.INFO, "Premier démarrage : repère posé à {0} ({1}). Les sujets "
                            + "antérieurs ne seront pas importés sans adoption explicite.",
                    new Object[] {watermark, Snowflake.instantOf(watermark)});
        }
    }

    /** Un tour complet : Discord → GitHub, puis GitHub → Discord. */
    public Round runOnce() {
        if (forum == null) {
            prepare();
        }
        List<String> problems = new ArrayList<>();
        Counters counters = new Counters();

        for (ForumPost post : candidatePosts(problems)) {
            try {
                syncPostToGitHub(post, counters, problems);
            } catch (RestException e) {
                // Une erreur sur un sujet ne doit pas arrêter le tour : les autres sujets, et
                // surtout le sens GitHub → Discord, doivent continuer.
                String message = "Sujet " + post.threadId() + " : " + e.getMessage();
                problems.add(message);
                store.recordError(post.threadId(), e.getMessage());
                LOG.log(Level.WARNING, message);
            }
        }

        try {
            announceStatuses(counters, problems);
        } catch (RestException e) {
            problems.add("Relevé des issues : " + e.getMessage());
            LOG.log(Level.WARNING, "Relevé des issues impossible ce tour : {0}", e.getMessage());
        }

        return new Round(counters.created, counters.contentUpdated, counters.statusAnnounced,
                counters.reconciled, counters.skipped, problems);
    }

    // ---- Discord → GitHub ------------------------------------------------------------------

    /**
     * Sujets à considérer : actifs, plus les récemment archivés (un sujet peut être archivé avant
     * le premier tour), dédoublonnés par identifiant.
     */
    private List<ForumPost> candidatePosts(List<String> problems) {
        Map<String, ForumPost> byId = new LinkedHashMap<>();
        try {
            for (ForumPost post : discord.activePosts(config.guildId(), config.forumChannelId())) {
                byId.put(post.threadId(), post);
            }
            for (ForumPost post : discord.recentlyArchivedPosts(config.forumChannelId(), 50)) {
                byId.putIfAbsent(post.threadId(), post);
            }
        } catch (RestException e) {
            problems.add("Lecture du forum : " + e.getMessage());
            LOG.log(Level.WARNING, "Lecture du forum impossible ce tour : {0}", e.getMessage());
        }

        // Les sujets adoptés explicitement sont relus même s'ils ne sont plus listés (archivés
        // anciens) : c'est le cas du sujet TEST.
        for (String threadId : store.adoptedThreads()) {
            if (!byId.containsKey(threadId)) {
                try {
                    byId.put(threadId, discord.post(threadId));
                } catch (RestException e) {
                    problems.add("Sujet adopté " + threadId + " illisible : " + e.getMessage());
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private void syncPostToGitHub(ForumPost listed, Counters counters,
                                  List<String> problems) {
        if (!eligible(listed)) {
            counters.skipped++;
            return;
        }
        // Les listes de sujets ne portent pas le message initial. On ne va le chercher que
        // maintenant, c'est-à-dire uniquement pour les sujets réellement concernés : un forum qui
        // grossit ne provoque donc pas un balayage complet à chaque tour.
        ForumPost post = listed.content().isEmpty() ? discord.post(listed.threadId()) : listed;

        PostKind kind = PostKind.fromTags(post.appliedTagIds(), forum.availableTags());
        String threadUrl = config.threadUrl(post.threadId());
        String title = ContentSanitizer.title(post.title());
        String titleHash = hash(title);
        String bodyHash = hash(ContentSanitizer.quotedBody(post.content(), threadUrl)
                + "|" + kind.name() + "|" + attachmentFingerprint(post));

        Optional<SyncStore.Link> existing = store.link(post.threadId());

        if (existing.isEmpty() || !existing.get().linked()) {
            // Réconciliation AVANT toute nouvelle création dès qu'une tentative a déjà été
            // engagée : la précédente a peut-être abouti sans que la réponse nous parvienne.
            if (existing.isPresent() && existing.get().creationAmbiguous()) {
                Optional<Issue> found = findIssueForThread(post.threadId(),
                        existing.get().scanFrom());
                if (found.isPresent()) {
                    store.confirmLink(post.threadId(), found.get().number(), titleHash, bodyHash);
                    counters.reconciled++;
                    LOG.log(Level.INFO, "Sujet {0} réconcilié avec l''issue #{1} (création "
                                    + "précédente aboutie malgré une réponse incertaine).",
                            new Object[] {post.threadId(), found.get().number()});
                    postLinkMessageOnce(post.threadId(), found.get());
                    return;
                }
                if (withinReconcileCooldown(existing.get())) {
                    // Ne rien faire est ici le comportement correct : la liste des issues de
                    // GitHub peut encore ignorer une création très récente. Recréer maintenant
                    // produirait un doublon.
                    String message = "Sujet " + post.threadId() + " : une création est engagée mais "
                            + "introuvable pour l'instant. Attente du délai de prudence ("
                            + RECONCILE_COOLDOWN.toMinutes() + " min) avant toute nouvelle "
                            + "tentative — aucune création rejouée à l'aveugle.";
                    problems.add(message);
                    LOG.log(Level.INFO, message);
                    counters.skipped++;
                    return;
                }
            }
            createIssueFor(post, kind, threadUrl, title, titleHash, bodyHash, counters);
            return;
        }

        SyncStore.Link link = existing.get();
        boolean titleChanged = !titleHash.equals(link.titleHash());
        boolean bodyChanged = !bodyHash.equals(link.bodyHash());
        if (!titleChanged && !bodyChanged) {
            return;
        }
        updateIssueContent(post, kind, threadUrl, title, titleHash, bodyHash, link,
                counters, problems);
    }

    /** Mémorise le plus grand numéro d'issue vu dans un relevé. Monotone : jamais rabaissé. */
    private void rememberHighestIssue(GitHubApi.Listing listing) {
        int highest = listing.issues().stream().mapToInt(Issue::number).max().orElse(0);
        if (highest > scanFloor()) {
            store.putState(STATE_SCAN_FLOOR, String.valueOf(highest));
        }
    }

    /** Plancher du balayage : le plus grand numéro connu, qu'il ait été apparié ou seulement vu. */
    private int scanFloor() {
        int fromState = store.state(STATE_SCAN_FLOOR).map(value -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }).orElse(0);
        return Math.max(fromState, store.maxKnownIssueNumber().orElse(0));
    }

    /**
     * La dernière tentative de création est-elle trop récente pour conclure à son échec ?
     * Un horodatage absent est traité comme « ancien » : mieux vaut créer le signalement
     * manquant que le bloquer indéfiniment sur une donnée illisible.
     */
    private static boolean withinReconcileCooldown(SyncStore.Link link) {
        Instant lastAttempt = link.lastAttemptAt();
        return lastAttempt != null
                && Instant.now().isBefore(lastAttempt.plus(RECONCILE_COOLDOWN));
    }

    /**
     * Un sujet est traité s'il a été créé après le repère temporel, ou s'il a été adopté
     * explicitement. C'est la règle « aucun import massif sans sélection ».
     */
    private boolean eligible(ForumPost post) {
        if (store.adopted(post.threadId())) {
            return true;
        }
        String watermark = store.state(STATE_WATERMARK).orElse(null);
        if (watermark == null) {
            return false;
        }
        try {
            return Snowflake.atOrAfter(post.threadId(), watermark);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void createIssueFor(ForumPost post, PostKind kind, String threadUrl, String title,
                                String titleHash, String bodyHash, Counters counters) {
        String body = IssueBody.initial(post, kind, threadUrl, repoPublic);
        if (config.dryRun()) {
            LOG.log(Level.INFO, "[simulation] Issue à créer pour le sujet {0} : « {1} » ({2})",
                    new Object[] {post.threadId(), title, kind.githubLabel()});
            counters.skipped++;
            return;
        }

        // Intention enregistrée et validée AVANT l'appel réseau : si le processus meurt
        // maintenant, le redémarrage réconciliera au lieu de recréer.
        store.beginCreation(config.guildId(), config.forumChannelId(), post.threadId(),
                scanFloor());

        Issue issue;
        try {
            issue = github.createIssue(title, body,
                    List.of(LABEL_SOURCE, kind.githubLabel(), LABEL_TRIAGE));
        } catch (RestException e) {
            if (e.ambiguous()) {
                // On ne rejoue PAS ici. La trace « CREATING, attempts > 0 » suffit : le prochain
                // tour réconciliera, et créera seulement si rien n'a abouti.
                store.recordError(post.threadId(), "création au résultat incertain : " + e.getMessage());
                LOG.log(Level.WARNING, "Création d''issue au résultat incertain pour le sujet {0} : "
                                + "{1}. Aucune nouvelle tentative immédiate ; réconciliation au "
                                + "prochain tour.",
                        new Object[] {post.threadId(), e.getMessage()});
            }
            throw e;
        }

        store.confirmLink(post.threadId(), issue.number(), titleHash, bodyHash);
        if (issue.number() > scanFloor()) {
            store.putState(STATE_SCAN_FLOOR, String.valueOf(issue.number()));
        }
        counters.created++;
        LOG.log(Level.INFO, "Sujet {0} → issue #{1} créée.",
                new Object[] {post.threadId(), issue.number()});
        postLinkMessageOnce(post.threadId(), issue);
    }

    private void updateIssueContent(ForumPost post, PostKind kind, String threadUrl, String title,
                                    String titleHash, String bodyHash, SyncStore.Link link,
                                    Counters counters, List<String> problems) {
        int number = link.issueNumber();
        Optional<Issue> current = github.issue(number);
        if (current.isEmpty()) {
            String message = "L'issue #" + number + " appariée au sujet " + post.threadId()
                    + " est introuvable (supprimée ou transférée). Aucune réécriture, aucune "
                    + "nouvelle création automatique.";
            store.recordError(post.threadId(), message);
            problems.add(message);
            LOG.log(Level.WARNING, message);
            return;
        }

        Optional<String> merged = IssueBody.withManagedBlockReplaced(
                current.get().body(), post, kind, threadUrl, repoPublic);
        if (merged.isEmpty()) {
            // Les marqueurs ont disparu : réécrire le corps écraserait des notes de triage.
            String message = "Issue #" + number + " : zone gérée introuvable dans le corps "
                    + "(marqueurs retirés ou modifiés). Le corps n'est PAS réécrit, pour ne pas "
                    + "écraser les notes de triage. Le titre reste synchronisé.";
            store.recordError(post.threadId(), message);
            problems.add(message);
            LOG.log(Level.WARNING, message);
            if (!config.dryRun() && !title.equals(current.get().title())) {
                github.updateIssue(number, title, null);
                store.recordContentHashes(post.threadId(), titleHash, link.bodyHash());
                counters.contentUpdated++;
            }
            return;
        }

        if (config.dryRun()) {
            LOG.log(Level.INFO, "[simulation] Issue #{0} à mettre à jour depuis le sujet {1}.",
                    new Object[] {number, post.threadId()});
            counters.skipped++;
            return;
        }
        github.updateIssue(number, title, merged.get());
        store.recordContentHashes(post.threadId(), titleHash, bodyHash);
        counters.contentUpdated++;
        LOG.log(Level.INFO, "Issue #{0} mise à jour depuis le sujet {1} (zone gérée seulement).",
                new Object[] {number, post.threadId()});
    }

    /**
     * Réconciliation : retrouve l'issue d'un sujet par le marqueur inscrit dans son corps.
     *
     * <p><strong>Pourquoi deux mécanismes et pas seulement la liste.</strong> Mesuré sur le vrai
     * dépôt : <em>aucune</em> variante de {@code GET /issues?…} ne renvoie une issue qui vient
     * d'être créée — ni filtrée par étiquette, ni triée par date de création, ni avec
     * {@code since}. Cinq lectures consécutives l'ont manquée, alors que
     * {@code GET /issues/{numéro}} la renvoyait immédiatement. Se fier à la liste seule
     * conduirait donc à conclure « aucune issue n'existe » juste après une création dont la
     * réponse a été perdue — et à créer exactement le doublon qu'on veut éviter.</p>
     *
     * <p>D'où : d'abord la liste (elle retrouve les cas anciens, en un appel), puis un
     * <strong>balayage borné des numéros voisins</strong> par l'accès unitaire, qui est le seul
     * dont la fraîcheur est garantie. Et si les deux échouent, l'appelant applique encore un
     * {@link #RECONCILE_COOLDOWN délai de prudence} avant d'autoriser une nouvelle création.</p>
     */
    private Optional<Issue> findIssueForThread(String threadId, Integer scanFrom) {
        GitHubApi.Listing listing = github.listIssuesByLabel(LABEL_SOURCE, null, ISSUE_PAGE_LIMIT);
        Optional<Issue> fromListing = listing.issues().stream()
                .filter(issue -> IssueBody.belongsTo(issue.body(), threadId))
                .findFirst();
        if (fromListing.isPresent()) {
            return fromListing;
        }

        rememberHighestIssue(listing);
        // Le plancher figé à la tentative est le seul sûr : un relevé postérieur a pu intégrer
        // l'issue cherchée, et repartir de ce relevé la sauterait.
        int start = (scanFrom == null ? scanFloor() : scanFrom) + 1;
        LOG.log(Level.INFO, "Réconciliation du sujet {0} : balayage des issues #{1} à #{2} "
                        + "(accès unitaire, seul accès dont GitHub garantit la fraîcheur).",
                new Object[] {threadId, start, start + FORWARD_SCAN_RANGE - 1});
        int consecutiveMisses = 0;
        for (int number = start; number < start + FORWARD_SCAN_RANGE; number++) {
            Optional<Issue> candidate = github.issue(number);
            if (candidate.isEmpty()) {
                // Les numéros sont partagés avec les pull requests : des trous sont normaux.
                if (++consecutiveMisses >= FORWARD_SCAN_MAX_MISSES) {
                    break;
                }
                continue;
            }
            consecutiveMisses = 0;
            if (IssueBody.belongsTo(candidate.get().body(), threadId)) {
                LOG.log(Level.INFO, "Issue #{0} retrouvée par balayage des numéros voisins : la "
                                + "liste des issues de GitHub ne la renvoyait pas encore.",
                        candidate.get().number());
                return candidate;
            }
        }
        return Optional.empty();
    }

    // ---- GitHub → Discord ------------------------------------------------------------------

    private void announceStatuses(Counters counters, List<String> problems) {
        String etag = store.state(STATE_GITHUB_ETAG).orElse(null);
        GitHubApi.Listing listing = github.listIssuesByLabel(LABEL_SOURCE, etag, ISSUE_PAGE_LIMIT);
        if (listing.notModified()) {
            return;
        }
        if (listing.etag() != null) {
            store.putState(STATE_GITHUB_ETAG, listing.etag());
        }
        rememberHighestIssue(listing);

        for (Issue issue : listing.issues()) {
            Optional<SyncStore.Link> link = store.linkByIssue(issue.number());
            if (link.isEmpty()) {
                // Origine inconnue de ce service : on n'y touche pas, ni étiquette, ni message.
                continue;
            }
            SyncStatus status = SyncStatus.of(issue);
            if (status.name().equals(link.get().lastStatus())) {
                continue;
            }
            String threadId = link.get().threadId();
            if (config.dryRun()) {
                LOG.log(Level.INFO, "[simulation] Sujet {0} : statut à annoncer « {1} ».",
                        new Object[] {threadId, status.label()});
                counters.skipped++;
                continue;
            }
            try {
                discord.postMessage(threadId, statusMessage(issue, status));
                applyStatusTag(threadId, status, problems);
                store.recordAnnouncedStatus(threadId, status.name());
                counters.statusAnnounced++;
                LOG.log(Level.INFO, "Sujet {0} : statut « {1} » annoncé (issue #{2}).",
                        new Object[] {threadId, status.label(), issue.number()});
            } catch (RestException e) {
                String message = "Annonce de statut impossible sur le sujet " + threadId + " : "
                        + e.getMessage();
                problems.add(message);
                store.recordError(threadId, message);
                LOG.log(Level.WARNING, message);
            }
        }
    }

    /** Message publié dans le sujet à la création du lien. Publié une seule fois. */
    private void postLinkMessageOnce(String threadId, Issue issue) {
        Optional<SyncStore.Link> link = store.link(threadId);
        if (link.isPresent() && link.get().linkPosted()) {
            return;
        }
        String message = """
                **Signalement enregistré.** Le suivi se fait désormais sur GitHub :
                %s

                Vous pouvez continuer à en discuter ici : ce sujet reste le lieu de la \
                conversation. Les changements de statut seront annoncés dans ce fil.
                *Le titre et le premier message sont recopiés sur GitHub%s.*"""
                .formatted(issue.htmlUrl(), repoPublic ? ", qui est public" : "");
        try {
            discord.postMessage(threadId, message);
            store.recordLinkPosted(threadId);
            SyncStatus status = SyncStatus.of(issue);
            store.recordAnnouncedStatus(threadId, status.name());
        } catch (RestException e) {
            // Le lien est fait côté GitHub : l'échec du message est signalé mais ne doit pas
            // provoquer une seconde création.
            store.recordError(threadId, "message de lien non publié : " + e.getMessage());
            LOG.log(Level.WARNING, "Message de lien non publié sur le sujet {0} : {1}. L''issue "
                    + "#{2} reste correctement appariée.",
                    new Object[] {threadId, e.getMessage(), issue.number()});
        }
    }

    private String statusMessage(Issue issue, SyncStatus status) {
        return """
                **Statut : %s**
                %s

                Suivi : %s""".formatted(status.label(), status.explanation(), issue.htmlUrl());
    }

    /**
     * Pose le tag de statut correspondant, <strong>sans jamais retirer</strong> les tags
     * Bug/Suggestion du sujet. Seuls les tags qui portent un nom de statut connu sont remplacés.
     * Si aucun tag de statut n'est configuré dans le salon, il n'y a rien à faire — c'est la
     * clause « et tags si disponibles » du ticket, pas une erreur.
     */
    private void applyStatusTag(String threadId, SyncStatus status, List<String> problems) {
        Optional<DiscordApi.ForumTag> target = forum.availableTags().stream()
                .filter(tag -> normalise(tag.name()).equals(normalise(status.label())))
                .findFirst();
        if (target.isEmpty()) {
            return;
        }
        Set<String> statusTagIds = new HashSet<>();
        for (DiscordApi.ForumTag tag : forum.availableTags()) {
            for (SyncStatus candidate : SyncStatus.values()) {
                if (normalise(tag.name()).equals(normalise(candidate.label()))) {
                    statusTagIds.add(tag.id());
                }
            }
        }

        ForumPost current;
        try {
            current = discord.post(threadId);
        } catch (RestException e) {
            problems.add("Tags non mis à jour sur le sujet " + threadId + " (relecture impossible) : "
                    + e.getMessage());
            return;
        }

        Set<String> desired = new LinkedHashSet<>();
        for (String tagId : current.appliedTagIds()) {
            if (!statusTagIds.contains(tagId)) {
                desired.add(tagId);  // Bug / Suggestion et tout autre tag : préservés
            }
        }
        desired.add(target.get().id());

        if (desired.size() > MAX_FORUM_TAGS) {
            problems.add("Sujet " + threadId + " : le tag de statut « " + status.label() + " » n'a "
                    + "pas été posé, le sujet porte déjà " + MAX_FORUM_TAGS + " tags (limite "
                    + "Discord). Aucun tag existant n'a été retiré.");
            return;
        }
        if (desired.equals(new LinkedHashSet<>(current.appliedTagIds()))) {
            return;
        }
        discord.setAppliedTags(threadId, new ArrayList<>(desired));
    }

    /** Comparaison de noms de tags tolérante aux accents, à la casse et aux espaces. */
    private static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String attachmentFingerprint(ForumPost post) {
        StringBuilder sb = new StringBuilder();
        for (ForumPost.Attachment attachment : post.attachments()) {
            sb.append(attachment.fileName()).append(':').append(attachment.sizeBytes()).append(';');
        }
        return sb.toString();
    }

    /** Empreinte stable d'un contenu, pour détecter une modification sans stocker le texte. */
    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 absent de la JVM", e);
        }
    }

    private static final class Counters {
        int created;
        int contentUpdated;
        int statusAnnounced;
        int reconciled;
        int skipped;
    }
}
