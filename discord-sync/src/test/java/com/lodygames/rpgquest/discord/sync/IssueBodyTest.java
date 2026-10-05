package com.lodygames.rpgquest.discord.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.discord.discord.ForumPost;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La contrainte la plus fragile du ticket #202 : synchroniser le contenu venu de Discord
 * <strong>sans jamais écraser les notes de triage</strong> écrites sur GitHub.
 */
class IssueBodyTest {

    private static final String THREAD_URL = "https://discord.com/channels/1/777";

    private static ForumPost post(String title, String content) {
        return new ForumPost("777", title, "42", "Membre", "2026-10-05T10:00:00Z", content,
                List.of(), List.of(), false);
    }

    @Test
    @DisplayName("Le corps initial porte le marqueur du sujet, qui sert de clé de réconciliation")
    void initialBodyCarriesTheMarker() {
        String body = IssueBody.initial(post("Un bug", "ça plante"), PostKind.BUG, THREAD_URL, true);

        assertTrue(IssueBody.belongsTo(body, "777"));
        assertFalse(IssueBody.belongsTo(body, "778"));
        assertTrue(body.contains("ouvrir la discussion"), body);
        assertTrue(body.contains("> ça plante"), body);
    }

    @Test
    @DisplayName("Un dépôt public est annoncé comme tel dans le corps de l'issue")
    void mentionsPublicVisibility() {
        String publicBody = IssueBody.initial(post("t", "c"), PostKind.BUG, THREAD_URL, true);
        String privateBody = IssueBody.initial(post("t", "c"), PostKind.BUG, THREAD_URL, false);

        assertTrue(publicBody.contains("public"), publicBody);
        assertFalse(privateBody.contains("dépôt **public**"), privateBody);
    }

    @Test
    @DisplayName("Une clôture n'est jamais présentée comme un déploiement")
    void neverPromisesDeployment() {
        String body = IssueBody.initial(post("t", "c"), PostKind.BUG, THREAD_URL, true);

        assertTrue(body.contains("une clôture ne garantit pas un déploiement")
                || body.contains("ne garantit pas un **déploiement**")
                || body.toLowerCase(java.util.Locale.ROOT).contains("ne garantit pas un"), body);
    }

    @Test
    @DisplayName("Mettre à jour le contenu PRÉSERVE les notes de triage écrites après la zone gérée")
    void preservesTriageNotes() {
        String initial = IssueBody.initial(post("Titre", "message d'origine"), PostKind.BUG,
                THREAD_URL, true);
        String withNotes = initial + "\n\n## Analyse\nCause probable : cache obsolète.\n"
                + "Assigné à moi-même, à voir après la 1.21.\n";

        Optional<String> merged = IssueBody.withManagedBlockReplaced(
                withNotes, post("Titre", "message CORRIGÉ"), PostKind.BUG, THREAD_URL, true);

        assertTrue(merged.isPresent());
        assertTrue(merged.get().contains("Cause probable : cache obsolète."),
                "les notes de triage ont été perdues");
        assertTrue(merged.get().contains("Assigné à moi-même"), "les notes de triage ont été perdues");
        assertTrue(merged.get().contains("> message CORRIGÉ"), "le nouveau contenu doit être présent");
        assertFalse(merged.get().contains("message d'origine"), "l'ancien contenu doit être remplacé");
    }

    @Test
    @DisplayName("Des notes écrites AVANT la zone gérée sont également préservées")
    void preservesNotesWrittenBefore() {
        String initial = IssueBody.initial(post("Titre", "msg"), PostKind.BUG, THREAD_URL, true);
        String withPreamble = "**Priorité haute** — vu en jeu le 5 octobre.\n\n" + initial;

        Optional<String> merged = IssueBody.withManagedBlockReplaced(
                withPreamble, post("Titre", "msg 2"), PostKind.BUG, THREAD_URL, true);

        assertTrue(merged.isPresent());
        assertTrue(merged.get().startsWith("**Priorité haute**"), merged.get());
    }

    @Test
    @DisplayName("Si les marqueurs ont disparu, le service REFUSE de réécrire plutôt que de deviner")
    void refusesToRewriteWithoutMarkers() {
        String rewrittenByHand = "J'ai réécrit entièrement cette issue à la main.";

        Optional<String> merged = IssueBody.withManagedBlockReplaced(
                rewrittenByHand, post("Titre", "msg"), PostKind.BUG, THREAD_URL, true);

        assertTrue(merged.isEmpty(), "un corps sans marqueur ne doit jamais être réécrit");
    }

    @Test
    @DisplayName("Un corps portant le marqueur d'un AUTRE sujet n'est pas touché")
    void refusesToRewriteAnotherThreadsBody() {
        String otherThread = IssueBody.initial(
                new ForumPost("999", "Autre", "1", "X", null, "autre", List.of(), List.of(), false),
                PostKind.REQUEST, THREAD_URL, true);

        assertTrue(IssueBody.withManagedBlockReplaced(otherThread, post("Titre", "msg"),
                PostKind.BUG, THREAD_URL, true).isEmpty());
    }

    @Test
    @DisplayName("Les pièces jointes sont référencées, jamais réhébergées, et l'expiration est dite")
    void attachmentsAreReferencedWithCaveat() {
        ForumPost withFile = new ForumPost("777", "Bug", "42", "Membre", "2026-10-05T10:00:00Z",
                "voir capture",
                List.of(new ForumPost.Attachment("capture.png", 204_800, "https://cdn.example/x.png")),
                List.of(), false);

        String body = IssueBody.initial(withFile, PostKind.BUG, THREAD_URL, true);

        assertTrue(body.contains("`capture.png`"), body);
        assertTrue(body.contains("200 ko"), body);
        assertTrue(body.contains("expirent"), body);
    }
}
