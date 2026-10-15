package com.lodygames.rpgquest.panel.content;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issue #430 — la copie d'un dialogue « Hors source » (présent seulement sur DEV) vers la source
 * éditable de PlugAdmin.
 *
 * <p><strong>Aucun second système.</strong> Le contenu vient de la lecture de fichier existante
 * {@code content.dev.read} (#47), et son empreinte est recoupée avec le dernier relevé
 * {@code content.dev.state}. La copie est écrite <em>octet pour octet</em> : jamais reconstruite
 * depuis l'éditeur, donc aucune réplique, condition, action, variante ni entrée #158 ne peut s'y
 * perdre. Elle ne touche pas DEV : le jeu continue d'utiliser son fichier tant que personne ne
 * publie.</p>
 *
 * <p>Classe pure : aucun accès disque ni réseau.</p>
 *
 * @param status       où en est la copie possible
 * @param devSha       empreinte SHA-256 du fichier DEV lu (vide tant qu'il n'est pas lu)
 * @param text         contenu exact du fichier DEV (vide sauf {@link Status#READY})
 * @param editorIssues ce que l'éditeur simple ne saurait pas relire sans perte (vide = modifiable
 *                     dans l'éditeur après la copie)
 */
public record DialogueServerCopy(Status status, String devSha, String text, List<String> editorIssues) {

    public enum Status {
        /** Le fichier n'a pas encore été lu sur DEV. */
        NOT_READ,
        /** Une lecture est en route vers le serveur. */
        PENDING,
        /** Lu, intègre, cohérent avec le relevé : copiable tel quel. */
        READY,
        /** Le serveur dit ne pas avoir ce fichier. */
        ABSENT,
        /** Présent, mais au-delà de la borne de lecture (#47). */
        TOO_LARGE,
        /** Le texte reçu ne correspond pas à l'empreinte annoncée : on ne copie rien. */
        CORRUPT,
        /** Le fichier DEV a changé depuis la lecture (le relevé d'état annonce une autre empreinte). */
        STALE
    }

    /**
     * @param read      détails du dernier {@code content.dev.read} de ce dialogue, s'il y en a un
     * @param pending   une lecture est encore en route
     * @param stateSha  empreinte DEV du dernier {@code content.dev.state}, vide si inconnue
     */
    public static DialogueServerCopy of(Optional<Map<String, Object>> read, boolean pending, String stateSha) {
        if (read.isEmpty()) {
            return new DialogueServerCopy(pending ? Status.PENDING : Status.NOT_READ, "", "", List.of());
        }
        Map<String, Object> d = read.get();
        String sha = str(d.get("sha256"));
        if (!Boolean.TRUE.equals(d.get("present"))) {
            return new DialogueServerCopy(pending ? Status.PENDING : Status.ABSENT, "", "", List.of());
        }
        if (Boolean.TRUE.equals(d.get("tooLarge"))) {
            return new DialogueServerCopy(Status.TOO_LARGE, sha, "", List.of());
        }
        String text = d.get("text") instanceof String s ? s : "";
        if (sha.isEmpty() || !sha.equals(ContentWorkspace.sha256(text.getBytes(StandardCharsets.UTF_8)))) {
            return new DialogueServerCopy(Status.CORRUPT, sha, "", List.of());
        }
        if (pending || stateSha != null && !stateSha.isEmpty() && !stateSha.equals(sha)) {
            // Une lecture plus récente arrive, ou DEV a bougé depuis : on ne copie pas une version dépassée.
            return new DialogueServerCopy(pending ? Status.PENDING : Status.STALE, sha, "", List.of());
        }
        DialogueYaml.ReadResult rr = DialogueYaml.read(text);
        List<String> issues = rr.draft() == null || !rr.ok()
                ? (rr.problems().isEmpty() ? List.of("format non reconnu") : List.copyOf(rr.problems()))
                : List.of();
        return new DialogueServerCopy(Status.READY, sha, text, issues);
    }

    public boolean ready() {
        return status == Status.READY;
    }

    /** Après la copie, l'éditeur simple pourra-t-il le modifier sans rien perdre ? */
    public boolean editableAfterCopy() {
        return ready() && editorIssues.isEmpty();
    }

    /** Les 12 premiers caractères de l'empreinte, pour l'affichage. */
    public String shortSha() {
        return devSha.length() > 12 ? devSha.substring(0, 12) : devSha;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
