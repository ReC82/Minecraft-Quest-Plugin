package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.ai.AiProvider;
import com.lodygames.rpgquest.panel.ai.AiProviderRegistry;
import com.lodygames.rpgquest.panel.ai.AiProviderSettings;
import com.lodygames.rpgquest.panel.ai.AiSettingsStore;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.Map;

/**
 * Configuration des fournisseurs d'IA (issue #146).
 *
 * <p><strong>La clé API n'est jamais réaffichée.</strong> Le champ de saisie est toujours vide :
 * il sert à <em>poser</em> une clé, jamais à la relire. Ce qui est montré est la longueur et une
 * empreinte SHA-256 tronquée — assez pour reconnaître quelle clé est en place après une rotation,
 * jamais assez pour la reconstituer. Le formulaire n'a donc aucun champ caché contenant le secret,
 * et aucune réponse HTTP du panel ne le transporte.</p>
 *
 * <p>Un champ clé laissé vide signifie « ne pas y toucher », pas « effacer » : sans cela, changer
 * de modèle effacerait la clé. Effacer est un bouton distinct.</p>
 */
public final class AiProviderPages {

    private AiProviderPages() {
    }

    public static String render(AiProviderRegistry registry, AiSettingsStore store,
                                String notice, String error, String testedProvider,
                                AiProvider.Result testResult) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("admin", "Fournisseurs d'IA",
                "Clés API, modèles et plafonds. Les clés restent sur ce serveur : elles ne sont "
                        + "jamais renvoyées au navigateur, jamais journalisées, jamais dans Git.", ""));

        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        if (notice != null && !notice.isBlank()) {
            sb.append(Ui.banner("success", Http.esc(notice)));
        }

        for (AiProvider provider : registry.all()) {
            AiProviderSettings settings = store.get(provider.id());
            sb.append(card(provider, settings, store.lastChange(provider.id()),
                    provider.id().equals(testedProvider) ? testResult : null));
        }
        sb.append(aboutCard());
        return sb.toString();
    }

    private static String card(AiProvider provider, AiProviderSettings settings, String lastChange,
                               AiProvider.Result testResult) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("admin", provider.label()));

        sb.append("<p class=\"meta-line\">");
        sb.append(settings.enabled()
                ? "<span class=\"badge text-bg-success\">activé</span>"
                : "<span class=\"badge text-bg-secondary\">désactivé</span>");
        sb.append(settings.hasKey()
                ? " <span class=\"badge text-bg-info\">clé enregistrée</span>"
                : " <span class=\"badge text-bg-warning\">aucune clé</span>");
        sb.append(" <code class=\"tid\">").append(Http.esc(provider.id())).append("</code></p>");

        sb.append("<p class=\"muted\">Clé : ").append(Http.esc(settings.masked()));
        if (settings.hasKey()) {
            sb.append(" &middot; empreinte <code>").append(Http.esc(settings.fingerprint()))
                    .append("</code> <em>(SHA-256 tronquée : permet de reconnaître la clé, jamais "
                            + "de la reconstituer)</em>");
        }
        sb.append("</p>");
        if (!lastChange.isBlank()) {
            sb.append("<p class=\"muted\">Dernière modification : ").append(Http.esc(lastChange))
                    .append("</p>");
        }

        if (testResult != null) {
            sb.append(Ui.banner(testResult.ok() ? "success" : "error", testResult.ok()
                    ? "Connexion réussie — modèle « " + Http.esc(String.valueOf(testResult.model()))
                      + " », " + Http.esc(testResult.usage()) + "."
                    : "Échec : " + Http.esc(testResult.error())));
        }

        String uid = provider.id();
        sb.append("<form method=\"post\" action=\"/ai/providers\" class=\"actform\" autocomplete=\"off\">%CSRF%");
        sb.append("<input type=\"hidden\" name=\"provider\" value=\"").append(Http.esc(uid)).append("\">");
        sb.append("<div class=\"form-grid\">");

        // autocomplete=new-password : empêche le gestionnaire de mots de passe du navigateur de
        // remplir — ou d'enregistrer — une clé d'API dans un champ qui doit rester vide.
        sb.append("<div class=\"field full\"><label for=\"k-").append(uid).append("\">Clé API</label>");
        sb.append("<input id=\"k-").append(uid).append("\" type=\"password\" name=\"apiKey\" value=\"\" ")
                .append("autocomplete=\"new-password\" spellcheck=\"false\" placeholder=\"")
                .append(settings.hasKey() ? "laisser vide pour conserver la clé actuelle"
                        : "coller la clé ici").append("\">");
        sb.append("<p class=\"field-help\">").append(Http.esc(provider.keyHelp()))
                .append(" Le champ reste vide à l'affichage : une clé enregistrée n'est jamais "
                        + "renvoyée au navigateur.</p></div>");

        sb.append(field("m-" + uid, "model", "Modèle", settings.model(), provider.defaultModel(),
                "Vide = « " + provider.defaultModel() + " »."));
        sb.append(field("u-" + uid, "baseUrl", "URL de base", settings.baseUrl(),
                provider.defaultBaseUrl(),
                "Vide = « " + provider.defaultBaseUrl() + " ». HTTPS obligatoire (une adresse "
                        + "locale est tolérée pour un mandataire)."));
        sb.append(number("t-" + uid, "maxOutputTokens", "Plafond de jetons en sortie",
                settings.maxOutputTokens(), "Garde-fou de budget : borne la taille d'une réponse."));
        sb.append(number("d-" + uid, "timeoutSeconds", "Délai maximal (secondes)",
                settings.timeoutSeconds(), "Au-delà, l'appel est abandonné et rien n'est écrit."));

        sb.append("<div class=\"full\"><label class=\"inline\">")
                .append("<input type=\"checkbox\" name=\"enabled\" value=\"on\"")
                .append(settings.enabled() ? " checked" : "").append("> Activer ce fournisseur")
                .append("</label></div>");
        sb.append("</div>");

        sb.append("<div class=\"btnrow\">");
        sb.append("<button class=\"btn\" type=\"submit\" name=\"_action\" value=\"save\">")
                .append(Icons.icon("save")).append("Enregistrer</button>");
        sb.append("<button class=\"btn secondary\" type=\"submit\" name=\"_action\" value=\"test\"")
                .append(settings.hasKey() ? "" : " disabled").append('>')
                .append(Icons.icon("check")).append("Tester la connexion</button>");
        sb.append("<button class=\"btn secondary\" type=\"submit\" name=\"_action\" value=\"clear\"")
                .append(settings.hasKey() ? "" : " disabled").append('>')
                .append(Icons.icon("admin")).append("Effacer la clé</button>");
        sb.append("</div>");
        sb.append("<p class=\"field-help\">« Tester la connexion » fait un vrai appel minimal : "
                + "« enregistré » ne prouve pas qu'une clé fonctionne.</p>");
        sb.append("</form>");
        return sb.append("</section>").toString();
    }

    private static String field(String id, String name, String label, String value,
                                String placeholder, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"text\" name=\"" + name + "\" value=\""
                + Http.esc(value) + "\" placeholder=\"" + Http.esc(placeholder)
                + "\" spellcheck=\"false\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String number(String id, String name, String label, int value, String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"number\" min=\"1\" name=\"" + name + "\" value=\""
                + value + "\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }

    private static String aboutCard() {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "Ce qui est fait de vos clés")
                + "<ul class=\"muted\">"
                + "<li>Elles sont enregistrées dans la base locale du panel, <strong>hors du dépôt "
                + "Git</strong>, dans un fichier lisible par le seul compte du service.</li>"
                + "<li>Elles ne sont <strong>jamais renvoyées au navigateur</strong> : le champ de "
                + "saisie reste vide, et aucun champ caché ne les transporte.</li>"
                + "<li>Elles ne sont <strong>jamais journalisées</strong>. Le journal d'audit "
                + "enregistre qu'une configuration a changé, par qui, et l'empreinte de la clé — "
                + "jamais la clé.</li>"
                + "<li>Tous les appels partent du <strong>serveur du panel</strong>. Aucun appel "
                + "n'est fait depuis le navigateur, ni depuis le serveur Minecraft.</li>"
                + "<li>Le plafond de jetons et le délai sont des <strong>garde-fous de budget</strong> : "
                + "un appel ne peut ni s'éterniser, ni produire une réponse démesurée.</li>"
                + "</ul></section>";
    }
}
