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
    record Request(String systemPrompt, String userPrompt, int maxOutputTokens, Duration timeout) {
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
                  Integer inputTokens, Integer outputTokens) {

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
     * Vérifie que la clé et l'URL fonctionnent réellement, par un appel minimal. C'est un vrai
     * aller-retour réseau : « configuration enregistrée » ne prouve rien, et le ticket demande un
     * bouton de test.
     */
    Result testConnection(AiProviderSettings settings);

    /** Génère du texte. Ne lance jamais : voir {@link Result}. */
    Result generate(Request request, AiProviderSettings settings);
}
