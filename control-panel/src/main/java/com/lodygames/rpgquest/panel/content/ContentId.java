package com.lodygames.rpgquest.panel.content;

import java.util.Locale;

/**
 * Normalisation d'un identifiant de contenu saisi à la main (issue #223).
 *
 * <p><strong>Le problème qu'elle résout.</strong> Le moteur manipule deux formes du même
 * identifiant : la forme <em>canonique</em> d'une quête ({@code rpgquest:ma_quete}) et la
 * <em>clé</em> qui sert de nom de fichier ({@code ma_quete}). Les formulaires, eux, n'en demandaient
 * qu'une sans le dire, et acceptaient silencieusement l'autre — jusqu'à ce qu'un validateur refuse
 * la proposition bien plus loin dans la chaîne, après un appel d'IA payant.</p>
 *
 * <p>Cette classe tranche une fois pour toutes : les deux formes sont acceptées à la saisie, et
 * ramenées à la forme attendue par le destinataire. Un namespace étranger ou un caractère interdit
 * est refusé <strong>avant</strong> tout appel externe, avec un message qui dit quoi corriger.</p>
 *
 * <p>Le namespace n'est jamais doublé : {@code rpgquest:x} et {@code x} donnent tous deux
 * {@code rpgquest:x}, jamais {@code rpgquest:rpgquest:x}.</p>
 */
public final class ContentId {

    /** Le seul namespace que le panel sait écrire — c'est celui des fichiers du plugin. */
    public static final String NAMESPACE = "rpgquest";

    /**
     * Clés acceptées comme nom de fichier. <strong>Même expression</strong> que celle dont
     * {@code ContentPackImport} dérive un slug : une clé acceptée ici ne peut donc pas être refusée
     * à l'import, et inversement.
     */
    public static final String KEY_PATTERN = "[a-z0-9][a-z0-9_-]{0,63}";

    private ContentId() {
    }

    /**
     * Résultat d'une normalisation.
     *
     * @param canonical forme à transmettre au destinataire ({@code rpgquest:clé} pour une quête,
     *                  la clé nue pour un dialogue / une story / un PNJ) ; vide si rien n'a été saisi
     * @param key       clé nue, qui sert de nom de fichier ; vide si rien n'a été saisi
     * @param error     refus lisible, ou {@code null} si la saisie est exploitable
     */
    public record Normalized(String canonical, String key, String error) {

        public boolean ok() {
            return error == null;
        }

        /** Rien n'a été saisi : le destinataire est libre de proposer un identifiant. */
        public boolean empty() {
            return error == null && key.isEmpty();
        }
    }

    /**
     * Identifiant de quête. La forme canonique porte le namespace, parce que c'est celle que le
     * moteur, le champ {@code id} du pack et les prérequis utilisent.
     */
    public static Normalized quest(String raw) {
        Normalized base = key(raw, "de quête");
        if (!base.ok() || base.empty()) {
            return base;
        }
        return new Normalized(NAMESPACE + ":" + base.key(), base.key(), null);
    }

    /**
     * Identifiant de dialogue — qui est aussi celui du PNJ porteur. La forme canonique est la clé
     * nue : c'est ce que {@code ContentPackImport} attend dans la famille {@code dialogues}, et un
     * {@code rpgquest:} laissé dans le champ {@code id} d'un dialogue en ferait un nom de fichier
     * invalide.
     */
    public static Normalized dialogue(String raw) {
        return key(raw, "de dialogue / de PNJ");
    }

    /** Identifiant de story. Clé nue, pour la même raison que le dialogue. */
    public static Normalized story(String raw) {
        return key(raw, "de story");
    }

    /**
     * La clé nue de n'importe quelle écriture du même identifiant, sans préjuger de la famille.
     * Utile pour <strong>comparer</strong> deux écritures : {@code rpgquest:x} et {@code x} donnent
     * la même clé, donc ce ne sont pas deux identifiants différents.
     */
    public static Normalized plainKey(String raw) {
        return key(raw, "");
    }

    /**
     * Le cœur commun : accepte {@code clé} comme {@code rpgquest:clé}, refuse tout autre namespace
     * et tout caractère hors du jeu autorisé.
     *
     * @param what complément de phrase pour le message de refus (« de quête », « de story »…)
     */
    private static Normalized key(String raw, String what) {
        String subject = "Identifiant" + (what == null || what.isEmpty() ? "" : " " + what);
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new Normalized("", "", null);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);

        String candidate = lower;
        int colon = lower.indexOf(':');
        if (colon >= 0) {
            String namespace = lower.substring(0, colon);
            candidate = lower.substring(colon + 1);
            if (!NAMESPACE.equals(namespace)) {
                return refused(subject + " « " + trimmed + " » refusé : le seul "
                        + "namespace accepté est « " + NAMESPACE + ": ». Écrivez « " + candidate
                        + " » — le namespace est ajouté automatiquement.");
            }
            if (candidate.indexOf(':') >= 0) {
                return refused(subject + " « " + trimmed + " » refusé : il contient "
                        + "plusieurs fois « : ». Un identifiant s'écrit « clé » ou « " + NAMESPACE
                        + ":clé ».");
            }
        }
        if (candidate.isEmpty()) {
            return refused(subject + " « " + trimmed + " » refusé : il n'y a aucune "
                    + "clé après « " + NAMESPACE + ": ».");
        }
        if (!candidate.matches(KEY_PATTERN)) {
            return refused(subject + " « " + trimmed + " » refusé : minuscules, "
                    + "chiffres, « _ » et « - » seulement, en commençant par une lettre ou un "
                    + "chiffre (64 caractères au plus).");
        }
        return new Normalized(candidate, candidate, null);
    }

    private static Normalized refused(String error) {
        return new Normalized("", "", error);
    }
}
