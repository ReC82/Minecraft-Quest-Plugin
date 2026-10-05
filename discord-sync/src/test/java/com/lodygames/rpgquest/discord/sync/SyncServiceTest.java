package com.lodygames.rpgquest.discord.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.discord.config.BotConfig;
import com.lodygames.rpgquest.discord.discord.ForumPost;
import com.lodygames.rpgquest.discord.github.Issue;
import com.lodygames.rpgquest.discord.store.SyncStore;
import com.lodygames.rpgquest.discord.support.FakeDiscord;
import com.lodygames.rpgquest.discord.support.FakeGitHub;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comportement de bout en bout de la synchronisation (issue #202), transports remplacés par des
 * doubles en mémoire.
 *
 * <p>L'invariant central vérifié ici est <strong>« un sujet = une issue »</strong>, dans les quatre
 * situations qui le mettent réellement en danger : plusieurs tours de suite, redémarrage du
 * service, événement dupliqué, et <strong>création dont la réponse HTTP est perdue alors que
 * l'écriture a abouti</strong>. Ce dernier cas est le piège classique : rejouer la création
 * produirait un doublon.</p>
 */
class SyncServiceTest {

    private static final String GUILD = "1445814722401144873";
    private static final String FORUM = "1556300141164503190";
    private static final String TAG_BUG = "2001";
    private static final String TAG_SUGGESTION = "2002";
    private static final String TAG_RESOLVED = "2003";

    @TempDir
    Path tempDir;

    private FakeDiscord discord;
    private FakeGitHub github;
    private String recentThreadId;

    @BeforeEach
    void setUp() {
        discord = new FakeDiscord();
        github = new FakeGitHub();
        discord.addTag(TAG_BUG, "Bug");
        discord.addTag(TAG_SUGGESTION, "Suggestion");
        // Un identifiant postérieur au repère posé au démarrage : le sujet est donc « nouveau ».
        recentThreadId = Snowflake.forInstant(Instant.now().plusSeconds(120));
    }

    private BotConfig config(boolean dryRun) {
        return new BotConfig(GUILD, FORUM, "jeton-discord-de-test",
                "ReC82/Minecraft-Quest-Plugin", "jeton-github-de-test",
                tempDir.resolve("sync.db"), Duration.ofSeconds(60), dryRun);
    }

    /** Un service neuf sur la même base : c'est exactement ce qu'est un redémarrage. */
    private SyncStore openStore() {
        return new SyncStore(tempDir.resolve("sync.db"));
    }

    private ForumPost bugPost(String threadId, String title, String content) {
        return new ForumPost(threadId, title, "42", "Membre", "2026-10-05T10:00:00Z", content,
                List.of(), List.of(TAG_BUG), false);
    }

    // ---- Création -------------------------------------------------------------------------

    @Test
    @DisplayName("Un nouveau sujet crée UNE issue étiquetée, et le lien est renvoyé dans le sujet")
    void newThreadCreatesOneLabelledIssueAndPostsTheLink() {
        discord.addPost(bugPost(recentThreadId, "Le PNJ ne répond plus", "Depuis hier, Lily ne parle pas."));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            SyncService.Round round = service.runOnce();

            assertEquals(1, round.created());
            assertEquals(1, github.issueCount());
            assertEquals(1, github.createCalls);

            Issue issue = github.all().get(0);
            assertEquals("Le PNJ ne répond plus", issue.title());
            assertTrue(issue.hasLabel(SyncService.LABEL_SOURCE), issue.labels().toString());
            assertTrue(issue.hasLabel(PostKind.BUG.githubLabel()), issue.labels().toString());
            assertTrue(issue.hasLabel(SyncService.LABEL_TRIAGE), issue.labels().toString());
            assertTrue(issue.body().contains("> Depuis hier, Lily ne parle pas."), issue.body());

            List<String> posted = discord.messages.get(recentThreadId);
            assertNotNull(posted, "aucun message publié dans le sujet");
            assertTrue(posted.get(0).contains(issue.htmlUrl()), posted.get(0));
        }
    }

    @Test
    @DisplayName("Un sujet sans tag connu est traité comme une suggestion, jamais comme un bug")
    void unknownTagDefaultsToSuggestion() {
        discord.addPost(new ForumPost(recentThreadId, "Idée de monture", "42", "Membre", null,
                "Et si on pouvait monter les chevaux ?", List.of(), List.of(), false));

        try (SyncStore store = openStore()) {
            new SyncService(config(false), discord, github, store).runOnce();

            assertTrue(github.all().get(0).hasLabel(PostKind.REQUEST.githubLabel()),
                    "étiqueter à tort un message comme bug gonflerait le nombre de défauts");
        }
    }

    // ---- « Un sujet = une issue » ----------------------------------------------------------

    @Test
    @DisplayName("Plusieurs tours de suite ne créent jamais de deuxième issue")
    void repeatedRoundsNeverDuplicate() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            service.runOnce();
            service.runOnce();
        }
        assertEquals(1, github.issueCount());
        assertEquals(1, github.createCalls);
    }

    @Test
    @DisplayName("Un redémarrage du service ne recrée pas l'issue")
    void restartDoesNotDuplicate() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            new SyncService(config(false), discord, github, store).runOnce();
        }
        // Nouvelle instance, nouvelle connexion : un redémarrage, au sens strict.
        try (SyncStore store = openStore()) {
            new SyncService(config(false), discord, github, store).runOnce();
        }

        assertEquals(1, github.issueCount());
        assertEquals(1, github.createCalls);
    }

    @Test
    @DisplayName("Une création dont la réponse est perdue est RÉCONCILIÉE, jamais rejouée")
    void ambiguousCreationIsReconciledNotRetried() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));
        // L'issue est réellement écrite, puis la réponse est perdue : le pire cas.
        github.failNextCreateAfterWriting = true;

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);

            SyncService.Round first = service.runOnce();
            assertEquals(0, first.created(), "la création n'a pas été confirmée");
            assertFalse(first.problems().isEmpty(), "le problème doit être signalé, pas caché");

            SyncStore.Link pending = store.link(recentThreadId).orElseThrow();
            assertTrue(pending.creationAmbiguous(),
                    "l'état ambigu doit être tracé AVANT l'appel réseau, sinon rien ne le rattrape");

            SyncService.Round second = service.runOnce();
            assertEquals(1, second.reconciled());
            assertEquals(0, second.created());
        }

        assertEquals(1, github.issueCount(), "un doublon a été créé");
        assertEquals(1, github.createCalls, "la création a été rejouée alors qu'elle avait abouti");
    }

    @Test
    @DisplayName("Après réconciliation, le lien est bien publié dans le sujet")
    void reconciliationStillPostsTheLink() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));
        github.failNextCreateAfterWriting = true;

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            service.runOnce();

            assertEquals(1, discord.messages.getOrDefault(recentThreadId, List.of()).size());
            assertTrue(store.link(recentThreadId).orElseThrow().linked());
        }
    }

    @Test
    @DisplayName("Si le relevé GitHub est encore en retard, l'issue est retrouvée par balayage "
            + "des numéros voisins")
    void staleListingIsCompensatedByForwardScan() {
        // Un premier sujet déjà traité : le service connaît donc un numéro d'issue récent, qui
        // sert de plancher au balayage. C'est la situation normale d'un service en service.
        String firstThread = Snowflake.forInstant(Instant.now().plusSeconds(60));
        discord.addPost(bugPost(firstThread, "Premier sujet", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();

            // Deuxième sujet : la création aboutit mais la réponse est perdue, ET le relevé
            // ignore encore la nouvelle issue — retard mesuré sur le vrai dépôt GitHub.
            discord.addPost(bugPost(recentThreadId, "Deuxième sujet", "Contenu"));
            github.failNextCreateAfterWriting = true;
            service.runOnce();
            int hidden = github.all().get(1).number();
            github.hiddenFromListing.add(hidden);

            SyncService.Round round = service.runOnce();
            assertEquals(1, round.reconciled(), round.problems().toString());
            assertEquals(0, round.created());
            assertEquals(hidden, store.link(recentThreadId).orElseThrow().issueNumber());
        }
        assertEquals(2, github.createCalls, "la création a été rejouée alors qu'elle avait abouti");
        assertEquals(2, github.issueCount());
    }

    @Test
    @DisplayName("Si l'issue est introuvable PARTOUT, le service ATTEND au lieu de recréer")
    void unfindableCreationWaitsInsteadOfDuplicating() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));
        github.failNextCreateAfterWriting = true;

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();

            // Indiscernable de « l'issue n'existe pas » : c'est exactement le cas où recréer
            // produirait un doublon si la création avait en fait abouti.
            int created = github.all().get(0).number();
            github.hiddenFromListing.add(created);
            github.hiddenEverywhere.add(created);

            SyncService.Round round = service.runOnce();
            assertEquals(0, round.created(), "aucune création ne doit être rejouée à l'aveugle");
            assertEquals(0, round.reconciled());
            assertTrue(round.problems().stream().anyMatch(p -> p.contains("délai de prudence")),
                    round.problems().toString());
        }
        assertEquals(1, github.createCalls, "la création a été rejouée pendant le délai de prudence");
    }

    // ---- Aucun import massif ---------------------------------------------------------------

    @Test
    @DisplayName("Un sujet antérieur au premier démarrage est IGNORÉ tant qu'il n'est pas adopté")
    void oldThreadIsIgnoredUntilAdopted() {
        String oldThreadId = Snowflake.forInstant(Instant.now().minusSeconds(86_400));
        discord.addPost(bugPost(oldThreadId, "Ancien sujet", "Vieux contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            assertEquals(0, github.issueCount(), "aucun import massif ne doit avoir lieu");

            // Sélection explicite : c'est le geste prévu pour le sujet TEST.
            store.adopt(oldThreadId, "sujet TEST");
            service.runOnce();
            assertEquals(1, github.issueCount());
        }
    }

    @Test
    @DisplayName("Un sujet adopté mais archivé et non listé est tout de même relu")
    void adoptedThreadIsFetchedEvenIfNotListed() {
        String oldThreadId = Snowflake.forInstant(Instant.now().minusSeconds(86_400));
        ForumPost archived = new ForumPost(oldThreadId, "TEST — synchronisation GitHub", "42",
                "Membre", null, "sujet de test", List.of(), List.of(TAG_BUG), true);
        discord.addPost(archived);

        try (SyncStore store = openStore()) {
            store.adopt(oldThreadId, "sujet TEST");
            new SyncService(config(false), discord, github, store).runOnce();
        }
        assertEquals(1, github.issueCount());
    }

    // ---- Contenu : synchroniser sans écraser les notes -------------------------------------

    @Test
    @DisplayName("Modifier le titre et le message met l'issue à jour SANS toucher aux notes de triage")
    void contentEditPreservesTriageNotes() {
        discord.addPost(bugPost(recentThreadId, "Titre initial", "Message initial"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            int number = github.all().get(0).number();

            // Le propriétaire ajoute ses notes après la zone gérée.
            github.replaceBody(number, github.all().get(0).body()
                    + "\n\n## Analyse\nRégression introduite par le correctif #160.\n");

            discord.addPost(bugPost(recentThreadId, "Titre corrigé", "Message corrigé"));
            SyncService.Round round = service.runOnce();

            assertEquals(1, round.contentUpdated());
            Issue updated = github.issue(number).orElseThrow();
            assertEquals("Titre corrigé", updated.title());
            assertTrue(updated.body().contains("> Message corrigé"), updated.body());
            assertTrue(updated.body().contains("Régression introduite par le correctif"),
                    "les notes de triage ont été écrasées");
        }
    }

    @Test
    @DisplayName("Sans modification côté Discord, aucune écriture GitHub n'est faite")
    void unchangedContentWritesNothing() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            String bodyAfterCreation = github.all().get(0).body();

            SyncService.Round round = service.runOnce();

            assertEquals(0, round.contentUpdated());
            assertEquals(bodyAfterCreation, github.all().get(0).body());
        }
    }

    @Test
    @DisplayName("Si les marqueurs ont été retirés, le corps n'est PAS réécrit mais le titre suit")
    void missingMarkersBlockBodyRewriteOnly() {
        discord.addPost(bugPost(recentThreadId, "Titre initial", "Message initial"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            int number = github.all().get(0).number();
            github.replaceBody(number, "Corps entièrement réécrit à la main, sans marqueur.");

            discord.addPost(bugPost(recentThreadId, "Titre corrigé", "Message corrigé"));
            SyncService.Round round = service.runOnce();

            Issue updated = github.issue(number).orElseThrow();
            assertEquals("Titre corrigé", updated.title(), "le titre doit rester synchronisé");
            assertEquals("Corps entièrement réécrit à la main, sans marqueur.", updated.body(),
                    "le corps ne doit jamais être réécrit sans marqueur");
            assertFalse(round.problems().isEmpty(), "la situation doit être signalée, pas silencieuse");
            assertTrue(store.link(recentThreadId).orElseThrow().lastError().contains("zone gérée"),
                    "l'erreur doit être lisible dans l'état");
        }
    }

    // ---- Statuts : GitHub fait autorité ----------------------------------------------------

    @Test
    @DisplayName("Clôture « completed » annonce Résolu, puis la réouverture annonce À trier")
    void closureThenReopenAreBothAnnounced() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            int number = github.all().get(0).number();

            github.close(number, "completed");
            SyncService.Round closed = service.runOnce();
            assertEquals(1, closed.statusAnnounced());
            String resolvedMessage = lastMessage();
            assertTrue(resolvedMessage.contains(SyncStatus.RESOLVED.label()), resolvedMessage);
            assertTrue(resolvedMessage.contains("en ligne"),
                    "le message doit dire que « résolu » n'est pas « déployé »");

            github.reopen(number);
            SyncService.Round reopened = service.runOnce();
            assertEquals(1, reopened.statusAnnounced());
            assertTrue(lastMessage().contains(SyncStatus.TRIAGE.label()), lastMessage());
        }
    }

    @Test
    @DisplayName("Clôture « not_planned » annonce Refusé, et jamais Résolu")
    void notPlannedIsAnnouncedAsDeclined() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            github.close(github.all().get(0).number(), "not_planned");
            service.runOnce();

            assertTrue(lastMessage().contains(SyncStatus.DECLINED.label()), lastMessage());
            assertFalse(lastMessage().contains(SyncStatus.RESOLVED.label()), lastMessage());
        }
    }

    @Test
    @DisplayName("Un statut inchangé n'est pas réannoncé à chaque tour")
    void statusIsAnnouncedOnlyOnChange() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            github.close(github.all().get(0).number(), "completed");
            service.runOnce();
            int afterFirstAnnounce = discord.messages.get(recentThreadId).size();

            service.runOnce();
            service.runOnce();

            assertEquals(afterFirstAnnounce, discord.messages.get(recentThreadId).size());
        }
    }

    @Test
    @DisplayName("Une issue d'origine inconnue n'est jamais retouchée ni commentée")
    void foreignIssueIsNeverTouched() {
        int foreign = github.addForeignIssue("Ticket du propriétaire", "corps sans marqueur",
                List.of(SyncService.LABEL_SOURCE));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            github.close(foreign, "completed");
            SyncService.Round round = service.runOnce();

            assertEquals(0, round.statusAnnounced());
            assertTrue(discord.messages.isEmpty(), "aucun message ne doit être publié");
            assertEquals("Ticket du propriétaire", github.issue(foreign).orElseThrow().title());
        }
    }

    // ---- Tags du forum ---------------------------------------------------------------------

    @Test
    @DisplayName("Le tag de statut est posé SANS retirer le tag Bug du sujet")
    void statusTagPreservesBugTag() {
        discord.addTag(TAG_RESOLVED, "Résolu");
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            github.close(github.all().get(0).number(), "completed");
            service.runOnce();

            List<String> tags = discord.storedPost(recentThreadId).appliedTagIds();
            assertTrue(tags.contains(TAG_BUG), "le tag Bug du membre a été retiré : " + tags);
            assertTrue(tags.contains(TAG_RESOLVED), "le tag de statut n'a pas été posé : " + tags);
        }
    }

    @Test
    @DisplayName("Sans tag de statut configuré, l'annonce par message suffit — ce n'est pas une erreur")
    void missingStatusTagIsNotAnError() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            github.close(github.all().get(0).number(), "completed");
            SyncService.Round round = service.runOnce();

            assertEquals(1, round.statusAnnounced());
            assertTrue(round.problems().isEmpty(), round.problems().toString());
            assertEquals(List.of(TAG_BUG), discord.storedPost(recentThreadId).appliedTagIds());
        }
    }

    // ---- Simulation ------------------------------------------------------------------------

    @Test
    @DisplayName("En simulation, rien n'est écrit : ni issue, ni message, ni étiquette")
    void dryRunWritesNothing() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(true), discord, github, store);
            service.runOnce();
        }

        assertEquals(0, github.issueCount());
        assertEquals(0, github.createCalls);
        assertTrue(github.createdLabels().isEmpty());
        assertTrue(discord.messages.isEmpty());
    }

    // ---- Robustesse ------------------------------------------------------------------------

    @Test
    @DisplayName("Un message de lien non publié ne provoque pas une seconde création")
    void failedLinkMessageDoesNotCauseDuplicate() {
        discord.addPost(bugPost(recentThreadId, "Titre", "Contenu"));
        discord.failPostMessage =
                new com.lodygames.rpgquest.discord.http.RestException("Discord indisponible", 503, true);

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            service.runOnce();
            service.runOnce();

            Optional<SyncStore.Link> link = store.link(recentThreadId);
            assertTrue(link.orElseThrow().linked(), "l'appariement doit tenir malgré l'échec Discord");
            assertFalse(link.orElseThrow().linkPosted());
            assertNotNull(link.orElseThrow().lastError());
        }
        assertEquals(1, github.issueCount());
        assertEquals(1, github.createCalls);
    }

    @Test
    @DisplayName("Un salon qui n'est pas un forum est refusé au démarrage, avec la clé à corriger")
    void nonForumChannelIsRejected() {
        discord.forumType = 0;

        try (SyncStore store = openStore()) {
            SyncService service = new SyncService(config(false), discord, github, store);
            IllegalStateException error =
                    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                            service::prepare);
            assertTrue(error.getMessage().contains("DISCORD_FORUM_CHANNEL_ID"), error.getMessage());
        }
    }

    private String lastMessage() {
        List<String> messages = discord.messages.get(recentThreadId);
        return messages.get(messages.size() - 1);
    }
}
