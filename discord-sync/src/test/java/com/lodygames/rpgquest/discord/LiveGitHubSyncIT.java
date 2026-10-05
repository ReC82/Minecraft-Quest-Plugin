package com.lodygames.rpgquest.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.discord.config.BotConfig;
import com.lodygames.rpgquest.discord.discord.ForumPost;
import com.lodygames.rpgquest.discord.github.GitHubApi;
import com.lodygames.rpgquest.discord.github.HttpGitHubApi;
import com.lodygames.rpgquest.discord.github.Issue;
import com.lodygames.rpgquest.discord.http.RestClient;
import com.lodygames.rpgquest.discord.store.SyncStore;
import com.lodygames.rpgquest.discord.support.FakeDiscord;
import com.lodygames.rpgquest.discord.sync.IssueBody;
import com.lodygames.rpgquest.discord.sync.Snowflake;
import com.lodygames.rpgquest.discord.sync.SyncService;
import com.lodygames.rpgquest.discord.sync.SyncStatus;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test d'intégration <strong>réel</strong> du côté GitHub (issue #202).
 *
 * <p><strong>Désactivé par défaut</strong>, comme le smoke authentifié du Control Panel : il faut
 * la propriété {@code -DdiscordSyncLiveGitHub=true} pour l'exécuter. {@code ./gradlew build} ne
 * touche donc jamais le réseau, et personne ne crée d'issue par accident.</p>
 *
 * <p><strong>Ce qu'il prouve, et sur quoi il ne se prononce pas.</strong> Il exerce le vrai
 * {@link HttpGitHubApi} contre le vrai dépôt : création, étiquetage, relevé conditionnel par ETag,
 * mise à jour du contenu, détection de clôture et de réouverture, réconciliation, et absence de
 * doublon après redémarrage. Le transport <strong>Discord</strong> est, lui, remplacé par un double
 * en mémoire : ce test ne dit donc <strong>rien</strong> du fonctionnement réel côté Discord.</p>
 *
 * <p>Il n'agit que sur une issue qu'il crée lui-même, dont le titre commence par {@code TEST —},
 * et il la referme en fin de parcours. Aucun autre ticket n'est lu en écriture.</p>
 *
 * <pre>{@code
 * GITHUB_TOKEN=... ./gradlew :discord-sync:test --tests '*LiveGitHubSyncIT' \
 *     -DdiscordSyncLiveGitHub=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "discordSyncLiveGitHub", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveGitHubSyncIT {

    private static final String REPOSITORY =
            System.getenv().getOrDefault("GITHUB_REPOSITORY", "ReC82/Minecraft-Quest-Plugin");

    /** Préfixe obligatoire : rend l'issue de test identifiable d'un coup d'œil. */
    private static final String TEST_PREFIX = "TEST — synchronisation GitHub";

    @TempDir
    Path tempDir;

    private final RestClient client = new RestClient("LodyQuests-DiscordSync-IT/1.0");
    private final String token = System.getenv("GITHUB_TOKEN");

    private GitHubApi github() {
        String owner = REPOSITORY.substring(0, REPOSITORY.indexOf('/'));
        String repo = REPOSITORY.substring(REPOSITORY.indexOf('/') + 1);
        return new HttpGitHubApi(client, token, owner, repo);
    }

    private BotConfig config(Path db) {
        return new BotConfig("1445814722401144873", "1556300141164503190",
                "non-utilise-dans-ce-test", REPOSITORY, token, db, Duration.ofSeconds(60), false);
    }

    @Test
    @DisplayName("Parcours réel complet sur le dépôt GitHub : création, lien, édition, clôture, "
            + "réouverture, redémarrage sans doublon")
    void fullRealRoundTripAgainstGitHub() {
        org.junit.jupiter.api.Assumptions.assumeTrue(token != null && !token.isBlank(),
                "GITHUB_TOKEN absent : test réel non exécuté (et surtout pas déclaré réussi).");

        Path db = tempDir.resolve("live.db");
        FakeDiscord discord = new FakeDiscord();
        discord.addTag("1", "Bug");
        String threadId = Snowflake.forInstant(Instant.now().plusSeconds(60));
        String marker = "it-" + Instant.now().toEpochMilli();

        discord.addPost(new ForumPost(threadId,
                TEST_PREFIX + " (" + marker + ")",
                "0", "Test automatisé", "2026-10-05T12:00:00Z",
                "Sujet de test créé par le test d'intégration de l'issue #202. "
                        + "Il vérifie la création, l'édition et le suivi de statut. "
                        + "Mentions à neutraliser : @ReC82 et #1.",
                List.of(), List.of("1"), false));

        GitHubApi github = github();
        int issueNumber;

        // --- 1. Création -------------------------------------------------------------------
        try (SyncStore store = new SyncStore(db)) {
            SyncService service = new SyncService(config(db), discord, github, store);
            SyncService.Round round = service.runOnce();
            assertEquals(1, round.created(), "l'issue de test n'a pas été créée : " + round.problems());
            issueNumber = store.link(threadId).orElseThrow().issueNumber();
        }
        System.out.println("[IT] issue de test créée : #" + issueNumber);

        try {
            Issue created = github.issue(issueNumber).orElseThrow();
            assertTrue(created.title().startsWith(TEST_PREFIX), created.title());
            assertTrue(created.hasLabel(SyncService.LABEL_SOURCE), created.labels().toString());
            assertTrue(created.hasLabel("type:bug"), created.labels().toString());
            assertTrue(created.hasLabel(SyncService.LABEL_TRIAGE), created.labels().toString());
            assertTrue(IssueBody.belongsTo(created.body(), threadId),
                    "le marqueur de réconciliation est absent du corps réel");
            assertTrue(created.body().contains("`@ReC82`"),
                    "la mention n'a pas été neutralisée dans le corps réel");
            assertFalse(created.body().contains(" @ReC82 "), created.body());

            // Le lien a bien été renvoyé « dans le sujet » (ici, le double Discord).
            assertTrue(discord.messages.get(threadId).get(0).contains(created.htmlUrl()));

            // --- 2. Aucun doublon : nouveaux tours, puis redémarrage -----------------------
            try (SyncStore store = new SyncStore(db)) {
                new SyncService(config(db), discord, github, store).runOnce();
            }
            try (SyncStore store = new SyncStore(db)) {
                new SyncService(config(db), discord, github, store).runOnce();
                assertEquals(issueNumber, store.link(threadId).orElseThrow().issueNumber());
            }
            // La liste des issues de GitHub n'est PAS cohérente juste après une création (mesuré :
            // cinq lectures consécutives manquaient l'issue, alors que l'accès unitaire la
            // renvoyait déjà). On attend donc qu'elle apparaisse avant de compter — sinon le test
            // conclurait « aucun doublon » pour la mauvaise raison, c'est-à-dire sans rien voir.
            assertEquals(1, awaitMarkerCount(threadId),
                    "un doublon a été créé sur le vrai dépôt");

            // --- 3. Édition : les notes de triage réelles sont préservées ------------------
            String triageNote = "\n\n## Note de triage du test d'intégration\n"
                    + "Cette ligne doit survivre à la synchronisation (" + marker + ").\n";
            github.updateIssue(issueNumber, null, created.body() + triageNote);

            discord.addPost(new ForumPost(threadId,
                    TEST_PREFIX + " (" + marker + ", titre modifié)",
                    "0", "Test automatisé", "2026-10-05T12:00:00Z",
                    "Message initial MODIFIÉ par le test d'intégration.",
                    List.of(), List.of("1"), false));

            try (SyncStore store = new SyncStore(db)) {
                SyncService.Round round = new SyncService(config(db), discord, github, store).runOnce();
                assertEquals(1, round.contentUpdated(), round.problems().toString());
            }
            Issue edited = github.issue(issueNumber).orElseThrow();
            assertTrue(edited.title().endsWith("titre modifié)"), edited.title());
            assertTrue(edited.body().contains("> Message initial MODIFIÉ"), edited.body());
            assertTrue(edited.body().contains("Cette ligne doit survivre"),
                    "les notes de triage ont été écrasées sur le vrai dépôt");

            // --- 4. Clôture « completed » → Résolu ----------------------------------------
            setState(issueNumber, "closed", "completed");
            try (SyncStore store = new SyncStore(db)) {
                SyncService.Round round = new SyncService(config(db), discord, github, store).runOnce();
                assertEquals(1, round.statusAnnounced(), round.problems().toString());
            }
            String resolved = lastMessage(discord, threadId);
            assertTrue(resolved.contains(SyncStatus.RESOLVED.label()), resolved);
            assertTrue(resolved.contains("en ligne"),
                    "le message doit rappeler que « résolu » n'est pas « déployé »");

            // --- 5. Réouverture → À trier -------------------------------------------------
            setState(issueNumber, "open", null);
            try (SyncStore store = new SyncStore(db)) {
                SyncService.Round round = new SyncService(config(db), discord, github, store).runOnce();
                assertEquals(1, round.statusAnnounced(), round.problems().toString());
            }
            assertTrue(lastMessage(discord, threadId).contains(SyncStatus.TRIAGE.label()),
                    lastMessage(discord, threadId));

            // --- 6. Clôture « not_planned » → Refusé, jamais Résolu -----------------------
            setState(issueNumber, "closed", "not_planned");
            try (SyncStore store = new SyncStore(db)) {
                new SyncService(config(db), discord, github, store).runOnce();
            }
            String declined = lastMessage(discord, threadId);
            assertTrue(declined.contains(SyncStatus.DECLINED.label()), declined);
            assertFalse(declined.contains(SyncStatus.RESOLVED.label()), declined);

            System.out.println("[IT] parcours réel terminé sur l'issue #" + issueNumber);
        } finally {
            // Nettoyage : l'issue de test est refermée et étiquetée, jamais laissée ouverte.
            // GitHub ne permet pas de supprimer une issue par l'API : elle est donc documentée.
            try {
                setState(issueNumber, "closed", "not_planned");
                System.out.println("[IT] issue de test #" + issueNumber + " refermée (not_planned).");
            } catch (RuntimeException e) {
                System.out.println("[IT] fermeture de l'issue de test #" + issueNumber
                        + " impossible : " + e.getMessage());
            }
        }
    }

    /**
     * Attend que le relevé GitHub laisse voir au moins une issue portant le marqueur, puis renvoie
     * le compte. Borné : au bout du délai, renvoie ce qu'il voit, pour que l'échec soit lisible
     * plutôt que masqué par une attente infinie.
     */
    private int awaitMarkerCount(String threadId) {
        int count = 0;
        for (int attempt = 1; attempt <= 24; attempt++) {
            count = countTestIssuesWithMarker(threadId);
            if (count >= 1) {
                System.out.println("[IT] issue visible dans le relevé après " + (attempt * 5)
                        + " s au plus (retard de cohérence de la liste GitHub).");
                return count;
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[IT] l'issue n'est jamais apparue dans le relevé en 120 s.");
        return count;
    }

    /** Compte les issues réelles portant le marqueur de ce sujet : la preuve d'absence de doublon. */
    private int countTestIssuesWithMarker(String threadId) {
        GitHubApi.Listing listing =
                github().listIssuesByLabel(SyncService.LABEL_SOURCE, null, 200);
        return (int) listing.issues().stream()
                .filter(issue -> IssueBody.belongsTo(issue.body(), threadId))
                .count();
    }

    /**
     * Change l'état d'une issue. Volontairement hors de {@link GitHubApi} : le service de
     * synchronisation <strong>ne ferme ni ne réouvre jamais</strong> une issue — GitHub fait
     * autorité sur le statut. Seul ce test en a besoin, pour jouer le rôle du propriétaire.
     */
    private void setState(int number, String state, String stateReason) {
        Map<String, Object> payload = stateReason == null
                ? Map.of("state", state)
                : Map.of("state", state, "state_reason", stateReason);
        client.call("PATCH",
                URI.create("https://api.github.com/repos/" + REPOSITORY + "/issues/" + number),
                Map.of("Authorization", "Bearer " + token,
                        "Accept", "application/vnd.github+json",
                        "X-GitHub-Api-Version", "2022-11-28"),
                payload);
    }

    private static String lastMessage(FakeDiscord discord, String threadId) {
        List<String> messages = Optional.ofNullable(discord.messages.get(threadId)).orElseThrow();
        return messages.get(messages.size() - 1);
    }
}
