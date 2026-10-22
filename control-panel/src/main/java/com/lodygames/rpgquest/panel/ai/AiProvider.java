package com.lodygames.rpgquest.panel.ai;

import java.time.Duration;

/**
 * Abstraction d'un fournisseur d'IA (issue #146) : le domaine ne connaît <strong>aucune</strong> API
 * précise.
 *
 * <p>Trois opérations, et rien de plus : un identifiant stable, un test de connexion, et une
 * génération de texte. Tout ce qui est propre à OpenAI, Anthropic ou Gemini — forme du corps JSON,
 * nom de l'en-tête d'authentification, emplacement du texte dans la réponse — reste enfermé dans
 * l'implémentation. Ajouter un fournisseur = une classe, inscrite dans {@code AiProviderRegistry} ;
 * aucun autre fichier ne connaît la liste.</p>
 *
 * <p><strong>La clé API ne traverse jamais cette interface depuis le navigateur.</strong> Elle est
 * lue côté serveur dans {@link AiProviderSettings}, qui vient du stockage local du panel
 * (SQLite en mode 600, hors dépôt Git). Aucune implémentation ne doit journaliser la clé, ni la
 * renvoyer dans un message d'erreur : les messages d'erreur remontent tels quels à l'écran.</p>
 */
public interface AiProvider {

    /** Identifiant technique stable, utilisé comme clé de stockage et dans les URL. */
    String id();

    /** Libellé humain affiché dans le panel. */
    String label();

    /** Modèle proposé par défaut quand l'administrateur n'en a pas choisi. */
    String defaultModel();

    /** URL de base par défaut, surchargeable (passerelle d'entreprise, proxy, région). */
    String defaultBaseUrl();

    /** Où l'administrateur trouve sa clé — affiché à côté du champ, jamais deviné. */
    String keyHelp();

    /**
     * Une demande de génération. {@code systemPrompt} porte les règles et le contrat de format,
     * {@code userPrompt} la demande de l'administrateur : les fournisseurs qui distinguent les deux
     * rôles le font, les autres les concatènent.
     */
    record Request(String systemPrompt, String userPrompt, int maxOutputTokens, Duration timeout,
                   java.util.List<Image> images) {

        public Request {
            images = java.util.List.copyOf(images == null ? java.util.List.of() : images);
        }

        /** Requête texte seule — la forme de toutes les demandes antérieures à #283. */
        public Request(String systemPrompt, String userPrompt, int maxOutputTokens, Duration timeout) {
            this(systemPrompt, userPrompt, maxOutputTokens, timeout, java.util.List.of());
        }
    }

    /**
     * Une image jointe à la demande (issue #283) : ses <strong>pixels</strong>, pas une adresse.
     *
     * <p>Envoyer une URL du panel ne donnerait rien au modèle — elle n'est pas joignable depuis
     * le fournisseur — et envoyer une description textuelle n'est pas « montrer l'image ». Chaque
     * fournisseur reçoit donc les octets, encodés en base64 dans le format que son API documente.</p>
     *
     * @param mime type détecté sur les octets eux-mêmes (jamais sur le nom ni sur l'en-tête HTTP)
     */
    record Image(String mime, byte[] data) {

        public Image {
            data = data == null ? new byte[0] : data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        public String base64() {
            return java.util.Base64.getEncoder().encodeToString(data);
        }
    }

    /**
     * Résultat d'un appel. Jamais une exception vers l'appelant : une panne réseau, un 401, un quota
     * dépassé ou une réponse illisible sont des échecs <em>lisibles</em>, parce que l'écran doit
     * pouvoir les afficher sans que rien ne soit écrit.
     *
     * @param text         texte brut renvoyé par le modèle ({@code null} si échec)
     * @param model        modèle réellement utilisé, tel que le fournisseur le rapporte
     * @param inputTokens  jetons d'entrée facturés, {@code null} si le fournisseur ne les donne pas
     * @param outputTokens jetons de sortie facturés, {@code null} si non fourni
     */
    record Result(boolean ok, String text, String error, String model,
                  Integer inputTokens, Integer outputTokens, Exchange exchange) {

        /** Forme antérieure à #739 : sans trace de l'échange réel. */
        public Result(boolean ok, String text, String error, String model,
                      Integer inputTokens, Integer outputTokens) {
            this(ok, text, error, model, inputTokens, outputTokens, null);
        }

        /** Le même résultat, accompagné de ce qui a réellement circulé (#739). */
        public Result withExchange(Exchange trace) {
            return new Result(ok, text, error, model, inputTokens, outputTokens, trace);
        }

        public static Result failure(String error) {
            return new Result(false, null, error, null, null, null);
        }

        public static Result success(String text, String model, Integer in, Integer out) {
            return new Result(true, text, null, model, in, out);
        }

        /** Résumé des jetons pour l'audit et l'affichage, sans jamais inventer de coût monétaire. */
        public String usage() {
            if (inputTokens == null && outputTokens == null) {
                return "jetons non rapportés par le fournisseur";
            }
            return (inputTokens == null ? "?" : inputTokens) + " entrée / "
                    + (outputTokens == null ? "?" : outputTokens) + " sortie";
        }
    }

    /**
     * Ce qui a <strong>réellement</strong> circulé sur le réseau pour un appel (issue #739), déjà
     * expurgé : c'est la matière du journal IA, pas un résumé reconstruit.
     *
     * <p>Les longs textes (consignes, messages, plan renvoyé) sont sortis du corps JSON et rangés
     * dans {@code requestTexts} / {@code responseTexts} avec leur rôle et leur chemin ; le corps
     * restant ({@code *Skeleton}) garde les paramètres tels qu'envoyés. Les pixels des images sont
     * remplacés par leur type, leur taille et leur empreinte. Les clés et en-têtes d'authentification
     * sont masqués, et chaque masquage est écrit dans {@code redactions}.</p>
     *
     * @param httpStatus      -1 si aucune réponse HTTP n'a été reçue (panne réseau, délai)
     * @param transportError  la panne réseau, s'il y en a eu une ; vide sinon
     */
    record Exchange(String endpoint, java.util.Map<String, String> headers, String requestSkeleton,
                    java.util.List<AiTrace.Text> requestTexts, int httpStatus, String responseSkeleton,
                    java.util.List<AiTrace.Text> responseTexts, String providerRequestId, String finishReason,
                    java.util.List<String> redactions, java.time.Instant startedAt,
                    java.time.Instant finishedAt, String transportError) {

        public Exchange {
            headers = java.util.Map.copyOf(headers == null ? java.util.Map.of() : headers);
            requestTexts = java.util.List.copyOf(requestTexts == null ? java.util.List.of() : requestTexts);
            responseTexts = java.util.List.copyOf(responseTexts == null ? java.util.List.of() : responseTexts);
            redactions = java.util.List.copyOf(redactions == null ? java.util.List.of() : redactions);
            endpoint = endpoint == null ? "" : endpoint;
            requestSkeleton = requestSkeleton == null ? "" : requestSkeleton;
            responseSkeleton = responseSkeleton == null ? "" : responseSkeleton;
            providerRequestId = providerRequestId == null ? "" : providerRequestId;
            finishReason = finishReason == null ? "" : finishReason;
            transportError = transportError == null ? "" : transportError;
        }

        public long durationMillis() {
            return startedAt == null || finishedAt == null ? -1
                    : java.time.Duration.between(startedAt, finishedAt).toMillis();
        }
    }

    /**
     * Vérifie que la clé et l'URL fonctionnent réellement, par un appel minimal. C'est un vrai
     * aller-retour réseau : « configuration enregistrée » ne prouve rien, et le ticket demande un
     * bouton de test.
     */
    Result testConnection(AiProviderSettings settings);

    /** Génère du texte. Ne lance jamais : voir {@link Result}. */
    Result generate(Request request, AiProviderSettings settings);
}
