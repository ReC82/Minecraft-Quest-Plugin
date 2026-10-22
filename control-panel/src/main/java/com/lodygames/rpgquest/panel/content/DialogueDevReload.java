package com.lodygames.rpgquest.panel.content;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issue #732 — « Recharger depuis DEV » : la lecture à la demande du fichier DEV d'un dialogue que
 * PlugAdmin a déjà, pour remplacer la version de l'éditeur par celle du serveur.
 *
 * <p><strong>Aucun second système.</strong> La lecture est {@code content.dev.read} (#47), déjà
 * utilisée par la copie d'un dialogue « Hors source » ({@link DialogueServerCopy}, #430). Le texte
 * lu est copié <em>octet pour octet</em>, jamais reconstruit par l'éditeur.</p>
 *
 * <p><strong>Deux vérités distinctes.</strong> Le fichier enregistré sur DEV n'est pas forcément ce
 * que le jeu a chargé (fichier déposé à la main, ancienne mise en jeu refusée…). {@link InGame} dit
 * ce qu'on en sait, sans jamais deviner : sans relevé assez récent, c'est « on ne sait pas ».</p>
 *
 * <p>Classe pure : aucun accès disque ni réseau.</p>
 *
 * @param status      où en est la lecture demandée
 * @param devSha      empreinte SHA-256 du fichier DEV lu (vide tant qu'il n'est pas lu)
 * @param text        contenu exact du fichier DEV (vide sauf {@link Status#READY})
 * @param message     motif d'un échec de lecture, en clair (vide sinon)
 * @param editorIssues ce que l'éditeur simple ne saurait pas relire sans perte (vide = modifiable)
 */
public record DialogueDevReload(Status status, String devSha, String text, String message,
                                List<String> editorIssues) {

    public enum Status {
        /** La lecture est en route vers le serveur. */
        PENDING,
        /** Le serveur n'a pas pu lire le fichier (agent arrêté, refus…). */
        FAILED,
        /** Le serveur n'a pas ce fichier. */
        ABSENT,
        /** Présent, mais au-delà de la borne de lecture (#47). */
        TOO_LARGE,
        /** Le texte reçu ne correspond pas à l'empreinte annoncée. */
        CORRUPT,
        /** Le fichier DEV a changé depuis la lecture (un relevé plus récent annonce une autre empreinte). */
        STALE,
        /** Lu, intègre, à jour : peut remplacer la version de l'éditeur. */
        READY
    }

    /** Ce que le jeu utilise réellement, par rapport au fichier DEV lu. */
    public enum InGame {
        /** Le jeu utilise ce que contient le fichier DEV. */
        SAME,
        /** Le jeu utilise encore une autre version (le fichier n'a pas été rechargé). */
        OLDER,
        /** Le jeu n'a pas chargé ce dialogue. */
        NOT_LOADED,
        /** Pas de relevé assez récent pour le dire. */
        UNKNOWN
    }

    /**
     * @param pending      la lecture n'a pas encore de réponse
     * @param failedReason motif d'un échec de l'action, ou {@code null} si elle a abouti
     * @param details      détails rendus par {@code content.dev.read}, s'il y en a
     * @param newerStateSha empreinte DEV d'un relevé {@code content.dev.state} <em>postérieur</em> à la
     *                     lecture, vide s'il n'y en a pas
     */
    public static DialogueDevReload of(boolean pending, String failedReason, Optional<Map<String, Object>> details,
                                       String newerStateSha) {
        if (pending) {
            return new DialogueDevReload(Status.PENDING, "", "", "", List.of());
        }
        if (failedReason != null) {
            return new DialogueDevReload(Status.FAILED, "", "", failedReason.isBlank()
                    ? "le serveur n'a pas répondu" : failedReason.strip(), List.of());
        }
        if (details.isEmpty()) {
            return new DialogueDevReload(Status.FAILED, "", "", "la réponse du serveur est illisible", List.of());
        }
        Map<String, Object> d = details.get();
        String sha = str(d.get("sha256"));
        if (!Boolean.TRUE.equals(d.get("present"))) {
            return new DialogueDevReload(Status.ABSENT, "", "", "", List.of());
        }
        if (Boolean.TRUE.equals(d.get("tooLarge"))) {
            return new DialogueDevReload(Status.TOO_LARGE, sha, "", "", List.of());
        }
        String text = d.get("text") instanceof String s ? s : "";
        if (sha.isEmpty() || !sha.equals(ContentWorkspace.sha256(text.getBytes(StandardCharsets.UTF_8)))) {
            return new DialogueDevReload(Status.CORRUPT, sha, "", "", List.of());
        }
        if (newerStateSha != null && !newerStateSha.isEmpty() && !newerStateSha.equals(sha)) {
            return new DialogueDevReload(Status.STALE, sha, "", "", List.of());
        }
        DialogueYaml.ReadResult rr = DialogueYaml.read(text);
        List<String> issues = rr.draft() == null || !rr.ok()
                ? (rr.problems().isEmpty() ? List.of("format non reconnu") : List.copyOf(rr.problems()))
                : List.of();
        return new DialogueDevReload(Status.READY, sha, text, "", issues);
    }

    public boolean ready() {
        return status == Status.READY;
    }

    /** Le texte lu, s'il peut remplacer la version de l'éditeur. */
    public Optional<String> replacement() {
        return ready() ? Optional.of(text) : Optional.empty();
    }

    /** Les 12 premiers caractères de l'empreinte, pour l'affichage. */
    public String shortSha() {
        return devSha.length() > 12 ? devSha.substring(0, 12) : devSha;
    }

    /** Nombre de répliques du fichier lu (0 s'il n'est pas relisible). */
    public int lineCount() {
        DialogueYaml.ReadResult rr = ready() ? DialogueYaml.read(text) : null;
        return rr == null || rr.draft() == null ? 0 : rr.draft().nodes.size();
    }

    /**
     * Ce que le jeu utilise, comparé au fichier DEV.
     *
     * @param devText      le fichier DEV lu
     * @param devSha       son empreinte
     * @param confirmedSha empreinte qu'une mise en jeu réussie a confirmée chargée en jeu (vide sinon)
     * @param runtime      le dialogue tel que le dernier relevé {@code dialogue.list} le décrit, absent
     *                     s'il n'y figure pas
     * @param surveyFresh  ce relevé est postérieur à notre dernière écriture sur DEV (sinon il ne
     *                     prouve rien)
     */
    public static InGame inGame(String devText, String devSha, String confirmedSha, Optional<DialogueDraft> runtime,
                                boolean surveyFresh) {
        if (confirmedSha != null && !confirmedSha.isEmpty() && confirmedSha.equals(devSha)) {
            return InGame.SAME;
        }
        if (!surveyFresh) {
            return InGame.UNKNOWN;
        }
        if (runtime.isEmpty()) {
            return InGame.NOT_LOADED;
        }
        DialogueYaml.ReadResult rr = DialogueYaml.read(devText == null ? "" : devText);
        if (rr.draft() == null) {
            return InGame.UNKNOWN;
        }
        return fingerprint(rr.draft()).equals(fingerprint(runtime.get())) ? InGame.SAME : InGame.OLDER;
    }

    /**
     * Ce que le joueur voit d'un dialogue : début, répliques (texte) et réponses (texte, suite).
     * Conditions et effets ne figurent pas dans le relevé sous une forme comparable : on ne les
     * compare pas, plutôt que d'annoncer une différence qui n'en est pas une.
     */
    static List<String> fingerprint(DialogueDraft d) {
        List<String> out = new ArrayList<>();
        out.add("start=" + d.start);
        List<DialogueDraft.Node> nodes = new ArrayList<>(d.nodes);
        nodes.sort(Comparator.comparing(n -> n.id));
        for (DialogueDraft.Node n : nodes) {
            out.add("node=" + n.id + "|" + norm(n.text));
            for (DialogueDraft.Choice c : n.choices) {
                out.add("choice=" + norm(c.text) + "|" + (c.next == null ? "" : c.next.strip()));
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
