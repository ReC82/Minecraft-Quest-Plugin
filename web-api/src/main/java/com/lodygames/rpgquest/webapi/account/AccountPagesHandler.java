package com.lodygames.rpgquest.webapi.account;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.lodygames.rpgquest.webapi.site.Html;
import com.lodygames.rpgquest.webapi.site.PageLayout;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pages {@code /compte} du portail (issue #53) : inscription, connexion, déconnexion, liaison et
 * dissociation du compte Minecraft, changement de mot de passe, export JSON et suppression
 * définitive du compte. Rendu serveur, aucun script.
 *
 * <p>Protections : tout {@code POST} est refusé si son {@code Origin} n'est pas le portail ou s'il
 * est marqué {@code Sec-Fetch-Site: cross-site} ; il doit porter un jeton anti-CSRF — celui de la
 * session une fois connecté, sinon un jeton en double soumission (cookie {@code lq_form}
 * {@code SameSite=Strict} + champ caché). Réponses {@code Cache-Control: no-store}. Les messages
 * affichés viennent d'une liste fixe, jamais de la requête.</p>
 */
public final class AccountPagesHandler implements ApiHandler {

    private static final int MAX_FORM_BYTES = 8 * 1024;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)
            .withZone(DISPLAY_ZONE);
    private static final Map<String, String> NOTICES = Map.of(
            "inscrit", "Compte créé. Connecte-toi pour continuer.",
            "inscrit-limite", "Compte créé, mais limité : l'accord d'un parent est nécessaire avant de relier ton "
                    + "compte Minecraft. Connecte-toi pour voir son état.",
            "delie", "Ton compte Minecraft a été délié. C'est noté dans l'historique ci-dessous.",
            "deconnecte", "Tu es déconnecté.",
            "motdepasse", "Mot de passe changé. Tes autres connexions ont été fermées.",
            "supprime", "Ton compte a été supprimé, avec sa liaison et ta progression. Il n'en reste rien sur ce site.");

    private final AccountService accounts;
    private final String siteTitle;
    private final String allowedOrigin;
    private final boolean secureCookies;
    private final Duration sessionTtl;
    private final Duration linkCodeTtl;

    public AccountPagesHandler(AccountService accounts, AccountSettings settings, String siteTitle, String publicBaseUrl) {
        this.accounts = accounts;
        this.siteTitle = siteTitle;
        this.allowedOrigin = originOf(publicBaseUrl);
        this.secureCookies = settings.secureCookies();
        this.sessionTtl = settings.sessionTtl();
        this.linkCodeTtl = settings.linkCodeTtl();
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        try {
            if (method.equals("GET") || method.equals("HEAD")) {
                if (!path.equals("/compte") && !path.equals("/compte/")) {
                    return page(exchange, 404, "<p>Page introuvable.</p>");
                }
                return show(exchange, notice(exchange));
            }
            if (!method.equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                return page(exchange, 405, "<p>Méthode non autorisée.</p>");
            }
            if (!sameOrigin(exchange)) {
                return page(exchange, 403, "<p>Formulaire refusé : il ne vient pas de ce site.</p>");
            }
            Map<String, String> form = readForm(exchange);
            return switch (path) {
                case "/compte/inscription" -> register(exchange, form);
                case "/compte/connexion" -> login(exchange, form);
                case "/compte/deconnexion" -> logout(exchange, form);
                case "/compte/lien" -> createChallenge(exchange, form);
                case "/compte/delier" -> unlink(exchange, form);
                case "/compte/motdepasse" -> changePassword(exchange, form);
                case "/compte/export" -> export(exchange, form);
                case "/compte/supprimer" -> deleteAccount(exchange, form);
                default -> page(exchange, 404, "<p>Page introuvable.</p>");
            };
        } catch (SQLException e) {
            throw new IOException("Base des comptes indisponible", e);
        }
    }

    // --- actions ---------------------------------------------------------------------------

    private int register(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        if (!anonymousCsrfValid(exchange, form)) {
            return csrfRefused(exchange);
        }
        int age;
        try {
            age = Integer.parseInt(form.getOrDefault("age", "").trim());
        } catch (NumberFormatException e) {
            return showAnonymous(exchange, 400, error("Indique ton âge en chiffres."));
        }
        Optional<String> region = Optional.ofNullable(form.get("pays")).map(String::trim).filter(p -> !p.isEmpty());
        AccountService.RegistrationOutcome outcome = accounts.register(
                form.getOrDefault("identifiant", "").trim(), form.getOrDefault("motdepasse", ""), region, age,
                clientKey(exchange));
        return switch (outcome) {
            case CREATED -> redirect(exchange, "/compte?m=inscrit");
            case CREATED_RESTRICTED -> redirect(exchange, "/compte?m=inscrit-limite");
            case CLOSED -> showAnonymous(exchange, 403, error("Les comptes ne sont pas encore ouverts."));
            case REFUSED_BY_AGE_POLICY -> showAnonymous(exchange, 403, error(
                    "Désolé, ce site ne peut pas encore ouvrir de compte pour ton âge ou ton pays. Rien n'a été enregistré."));
            case INVALID_LOGIN -> showAnonymous(exchange, 400, error(
                    "L'identifiant doit faire 3 à 32 caractères : lettres sans accent, chiffres, point, tiret ou tiret bas."));
            case WEAK_PASSWORD -> showAnonymous(exchange, 400, error("Le mot de passe doit faire au moins "
                    + AccountService.MIN_PASSWORD_LENGTH + " caractères et être différent de l'identifiant."));
            case LOGIN_TAKEN -> showAnonymous(exchange, 409, error("Cet identifiant est déjà pris, choisis-en un autre."));
            case TOO_MANY -> showAnonymous(exchange, 429, error("Trop d'inscriptions depuis ta connexion. Réessaie plus tard."));
        };
    }

    private int login(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        if (!anonymousCsrfValid(exchange, form)) {
            return csrfRefused(exchange);
        }
        Optional<AccountService.NewSession> session = accounts.login(
                form.getOrDefault("identifiant", "").trim(), form.getOrDefault("motdepasse", ""), clientKey(exchange));
        if (session.isEmpty()) {
            return showAnonymous(exchange, 401, error(
                    "Identifiant ou mot de passe incorrect, ou trop d'essais : attends quelques minutes."));
        }
        Cookies.set(exchange, Cookies.SESSION, session.get().token(), sessionTtl, "Lax", secureCookies);
        Cookies.clear(exchange, Cookies.FORM, secureCookies);
        return redirect(exchange, "/compte");
    }

    private int logout(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        Optional<String> token = Cookies.read(exchange, Cookies.SESSION);
        Optional<AccountStore.Session> session = currentSession(exchange);
        if (session.isPresent() && !SecureTokens.constantTimeEquals(form.get("csrf"), session.get().csrfToken())) {
            return csrfRefused(exchange);
        }
        if (token.isPresent()) {
            accounts.logout(token.get());
        }
        Cookies.clear(exchange, Cookies.SESSION, secureCookies);
        return redirect(exchange, "/compte?m=deconnecte");
    }

    private int createChallenge(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        Optional<AccountStore.Session> session = authenticated(exchange, form);
        if (session.isEmpty()) {
            return csrfRefused(exchange);
        }
        AccountService.Challenge challenge = accounts.createChallenge(session.get().accountId());
        String banner = switch (challenge.outcome()) {
            case CREATED -> """
                    <section class="code">
                    <p>Dans Minecraft, ouvre le chat et tape :</p>
                    <p><code>/lien %s</code></p>
                    <p class="note">Ce code marche une seule fois, pendant %d minutes (jusqu'à %s). Ne le donne à
                    personne : celui qui le tape relie <em>son</em> compte Minecraft au tien.</p>
                    </section>
                    """.formatted(Html.escape(challenge.code()), linkCodeTtl.toMinutes(),
                    Html.escape(DateTimeFormatter.ofPattern("HH:mm").withZone(DISPLAY_ZONE).format(challenge.expiresAt())));
            case ALREADY_LINKED -> error("Ton compte est déjà relié à un compte Minecraft. Délie-le d'abord.");
            case RESTRICTED -> error("Ton compte est limité : l'accord d'un parent est nécessaire avant la liaison.");
            case TOO_MANY -> error("Tu as demandé trop de codes en une heure. Réessaie plus tard.");
        };
        int status = switch (challenge.outcome()) {
            case CREATED -> 200;
            case ALREADY_LINKED -> 409;
            case RESTRICTED -> 403;
            case TOO_MANY -> 429;
        };
        return dashboard(exchange, status, session.get(), banner);
    }

    private int unlink(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        Optional<AccountStore.Session> session = authenticated(exchange, form);
        if (session.isEmpty()) {
            return csrfRefused(exchange);
        }
        if (!accounts.unlink(session.get().accountId(), form.getOrDefault("motdepasse", ""))) {
            return dashboard(exchange, 403, session.get(),
                    error("Mot de passe incorrect, ou aucun compte Minecraft n'était relié : rien n'a changé."));
        }
        return redirect(exchange, "/compte?m=delie");
    }

    private int changePassword(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        Optional<AccountStore.Session> session = authenticated(exchange, form);
        Optional<String> token = Cookies.read(exchange, Cookies.SESSION);
        if (session.isEmpty() || token.isEmpty()) {
            return csrfRefused(exchange);
        }
        String replacement = form.getOrDefault("nouveau", "");
        if (!replacement.equals(form.getOrDefault("confirmation", ""))) {
            return dashboard(exchange, 400, session.get(),
                    error("Les deux nouveaux mots de passe ne sont pas identiques : rien n'a changé."));
        }
        AccountService.PasswordChange outcome = accounts.changePassword(session.get().accountId(), token.get(),
                form.getOrDefault("actuel", ""), replacement);
        return switch (outcome) {
            case CHANGED -> redirect(exchange, "/compte?m=motdepasse");
            case WRONG_PASSWORD -> dashboard(exchange, 403, session.get(),
                    error("Mot de passe actuel incorrect : rien n'a changé."));
            case WEAK_PASSWORD -> dashboard(exchange, 400, session.get(), error("Le nouveau mot de passe doit faire "
                    + "au moins " + AccountService.MIN_PASSWORD_LENGTH
                    + " caractères, être différent de l'identifiant et de l'ancien."));
            case TOO_MANY -> dashboard(exchange, 429, session.get(),
                    error("Trop d'essais avec un mauvais mot de passe : attends quelques minutes."));
        };
    }

    /** Téléchargement JSON de toutes les données du compte ; {@code POST} + CSRF, jamais un lien. */
    private int export(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        Optional<AccountStore.Session> session = authenticated(exchange, form);
        if (session.isEmpty()) {
            return csrfRefused(exchange);
        }
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"lodyquests-compte.json\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        HttpResponses.sendJson(exchange, 200, accounts.export(session.get().accountId()));
        return 200;
    }

    private int deleteAccount(HttpExchange exchange, Map<String, String> form) throws IOException, SQLException {
        Optional<AccountStore.Session> session = authenticated(exchange, form);
        if (session.isEmpty()) {
            return csrfRefused(exchange);
        }
        if (!"oui".equals(form.get("confirmer"))) {
            return dashboard(exchange, 400, session.get(),
                    error("Coche la case de confirmation pour supprimer ton compte : rien n'a changé."));
        }
        if (!accounts.deleteAccount(session.get().accountId(), form.getOrDefault("motdepasse", ""))) {
            return dashboard(exchange, 403, session.get(),
                    error("Mot de passe incorrect, ou trop d'essais : ton compte n'a pas été supprimé."));
        }
        Cookies.clear(exchange, Cookies.SESSION, secureCookies);
        return redirect(exchange, "/compte?m=supprime");
    }

    // --- affichage -------------------------------------------------------------------------

    private int show(HttpExchange exchange, String banner) throws IOException, SQLException {
        Optional<AccountStore.Session> session = currentSession(exchange);
        return session.isPresent()
                ? dashboard(exchange, 200, session.get(), banner)
                : showAnonymous(exchange, 200, banner);
    }

    private int showAnonymous(HttpExchange exchange, int status, String banner) throws IOException {
        if (!accounts.open()) {
            return page(exchange, status, banner + """
                    <p>Les comptes du portail ne sont <strong>pas encore ouverts</strong>. Ils ouvriront quand les
                    règles de confidentialité et d'âge auront été validées (voir <a href="/confidentialite">Confidentialité</a>).</p>
                    """);
        }
        String formToken = Cookies.read(exchange, Cookies.FORM).orElse(null);
        if (formToken == null || formToken.length() < 32) {
            formToken = SecureTokens.newToken();
            Cookies.set(exchange, Cookies.FORM, formToken, Duration.ofHours(2), "Strict", secureCookies);
        }
        String csrf = "<input type=\"hidden\" name=\"csrf\" value=\"" + Html.escape(formToken) + "\">";
        StringBuilder regions = new StringBuilder();
        for (String region : accounts.agePolicy().thresholds().keySet().stream().sorted().toList()) {
            regions.append("<option value=\"").append(Html.escape(region)).append("\">").append(Html.escape(region))
                    .append("</option>");
        }
        return page(exchange, status, banner + """
                <p>Un compte du portail te permet de relier ton compte Minecraft, en prouvant depuis le jeu que c'est
                bien le tien. Il ne donne aucun pouvoir sur le serveur.</p>
                <h2>Se connecter</h2>
                <form method="post" action="/compte/connexion">%s
                <p><label>Identifiant<br><input name="identifiant" autocomplete="username" required maxlength="32"></label></p>
                <p><label>Mot de passe<br><input type="password" name="motdepasse" autocomplete="current-password" required maxlength="128"></label></p>
                <p><button type="submit">Se connecter</button></p>
                </form>
                <h2>Créer un compte</h2>
                <form method="post" action="/compte/inscription">%s
                <p><label>Identifiant (pas ton vrai nom, ni ton pseudo Minecraft)<br>
                <input name="identifiant" autocomplete="username" required minlength="3" maxlength="32" pattern="[A-Za-z0-9_.\\-]{3,32}"></label></p>
                <p><label>Mot de passe (au moins %d caractères)<br>
                <input type="password" name="motdepasse" autocomplete="new-password" required minlength="%d" maxlength="128"></label></p>
                <p><label>Ton pays<br><select name="pays"><option value="">Autre pays</option>%s</select></label></p>
                <p><label>Ton âge<br><input type="number" name="age" required min="1" max="120" inputmode="numeric"></label></p>
                <p class="note">Ton âge et ton pays servent seulement à appliquer les règles pour les mineurs : ils ne sont
                pas enregistrés. On ne demande ni e-mail, ni nom, ni adresse.</p>
                <p><button type="submit">Créer mon compte</button></p>
                </form>
                """.formatted(csrf, csrf, AccountService.MIN_PASSWORD_LENGTH, AccountService.MIN_PASSWORD_LENGTH, regions));
    }

    private int dashboard(HttpExchange exchange, int status, AccountStore.Session session, String banner)
            throws IOException, SQLException {
        AccountService.Overview overview = accounts.overview(session.accountId());
        String csrf = "<input type=\"hidden\" name=\"csrf\" value=\"" + Html.escape(session.csrfToken()) + "\">";
        StringBuilder body = new StringBuilder(banner);
        body.append("<p>Connecté en tant que <strong>").append(Html.escape(overview.account().login())).append("</strong>.</p>");
        body.append("<h2>Compte Minecraft</h2>");
        if (!overview.account().consent().allowsPersonalFeatures()) {
            body.append("<p><strong>Compte limité.</strong> L'accord d'un parent est nécessaire avant de relier un compte "
                    + "Minecraft. La demande d'accord parental n'est pas encore disponible sur ce site.</p>");
        } else if (overview.link().isPresent()) {
            AccountStore.Link link = overview.link().get();
            body.append("<p>Relié à <strong>").append(Html.escape(link.playerName())).append("</strong> depuis le ")
                    .append(Html.escape(DATE.format(link.linkedAt()))).append(".</p>")
                    .append("<p class=\"note\">Le skin n'est pas affiché : il faudrait le demander à un service extérieur, "
                            + "qui apprendrait alors que tu visites ce site.</p>")
                    .append(overview.progress()
                            .map(p -> LinkedProgressView.render(link.playerName(), p.progress(), p.receivedAt(), false))
                            .orElseGet(LinkedProgressView::pending))
                    .append("<h3>Délier</h3><form method=\"post\" action=\"/compte/delier\">").append(csrf)
                    .append("<p><label>Confirme avec ton mot de passe<br><input type=\"password\" name=\"motdepasse\" "
                            + "autocomplete=\"current-password\" required maxlength=\"128\"></label></p>"
                            + "<p><button type=\"submit\">Délier mon compte Minecraft</button></p></form>");
        } else {
            body.append("<p>Aucun compte Minecraft relié.</p>")
                    .append("<form method=\"post\" action=\"/compte/lien\">").append(csrf)
                    .append("<p><button type=\"submit\">Obtenir un code de liaison</button></p></form>")
                    .append("<p class=\"note\">Tu taperas ce code dans Minecraft : c'est la preuve que ce compte "
                            + "Minecraft est bien le tien. Un code faux, expiré ou déjà utilisé est refusé. Une fois "
                            + "relié, tu verras ici ta progression, tes niveaux et ta place dans les classements.</p>");
        }
        if (!overview.history().isEmpty()) {
            body.append("<h2>Historique</h2><ul>");
            for (AccountStore.AuditEntry entry : overview.history()) {
                body.append("<li>").append(Html.escape(DATE.format(entry.at()))).append(" — ")
                        .append(Html.escape(auditLabel(entry))).append("</li>");
            }
            body.append("</ul>");
        }
        appendAccountManagement(body, csrf);
        body.append("<form method=\"post\" action=\"/compte/deconnexion\">").append(csrf)
                .append("<p><button type=\"submit\">Se déconnecter</button></p></form>");
        return page(exchange, status, body.toString());
    }

    private static void appendAccountManagement(StringBuilder body, String csrf) {
        body.append("<h2>Mon compte du portail</h2>")
                .append("<h3>Changer de mot de passe</h3><form method=\"post\" action=\"/compte/motdepasse\">")
                .append(csrf)
                .append("<p><label>Mot de passe actuel<br><input type=\"password\" name=\"actuel\" "
                        + "autocomplete=\"current-password\" required maxlength=\"128\"></label></p>"
                        + "<p><label>Nouveau mot de passe (au moins " + AccountService.MIN_PASSWORD_LENGTH
                        + " caractères)<br><input type=\"password\" name=\"nouveau\" autocomplete=\"new-password\" "
                        + "required minlength=\"" + AccountService.MIN_PASSWORD_LENGTH + "\" maxlength=\"128\"></label></p>"
                        + "<p><label>Nouveau mot de passe, encore une fois<br><input type=\"password\" "
                        + "name=\"confirmation\" autocomplete=\"new-password\" required minlength=\""
                        + AccountService.MIN_PASSWORD_LENGTH + "\" maxlength=\"128\"></label></p>"
                        + "<p class=\"note\">Tes autres connexions (autre navigateur, autre appareil) seront fermées.</p>"
                        + "<p><button type=\"submit\">Changer mon mot de passe</button></p></form>")
                .append("<h3>Télécharger mes données</h3><form method=\"post\" action=\"/compte/export\">")
                .append(csrf)
                .append("<p class=\"note\">Un fichier JSON avec tout ce que ce site garde sur toi : ton compte, ta "
                        + "liaison Minecraft, son historique et ta progression reçue. Jamais ton mot de passe.</p>"
                        + "<p><button type=\"submit\">Télécharger mes données</button></p></form>")
                .append("<h3>Supprimer mon compte</h3><form method=\"post\" action=\"/compte/supprimer\">")
                .append(csrf)
                .append("<p class=\"note\">C'est définitif : ton compte, ta liaison Minecraft, son historique et ta "
                        + "progression sont effacés. Ta partie dans Minecraft ne change pas.</p>"
                        + "<p><label><input type=\"checkbox\" name=\"confirmer\" value=\"oui\" required> "
                        + "Je veux supprimer mon compte pour toujours</label></p>"
                        + "<p><label>Confirme avec ton mot de passe<br><input type=\"password\" name=\"motdepasse\" "
                        + "autocomplete=\"current-password\" required maxlength=\"128\"></label></p>"
                        + "<p><button type=\"submit\">Supprimer mon compte</button></p></form>");
    }

    static String auditLabel(AccountStore.AuditEntry entry) {
        String name = entry.playerName() == null ? "?" : entry.playerName();
        return switch (entry.action()) {
            case "LINKED" -> "liaison avec " + name;
            case "UNLINKED" -> "dissociation de " + name;
            case "REFUSED_UUID_TAKEN" -> "tentative refusée : " + name + " est déjà relié à un autre compte";
            case "REFUSED_RESTRICTED" -> "tentative refusée : compte limité";
            default -> entry.action();
        };
    }

    // --- sécurité --------------------------------------------------------------------------

    private Optional<AccountStore.Session> currentSession(HttpExchange exchange) throws SQLException {
        Optional<String> token = Cookies.read(exchange, Cookies.SESSION);
        return token.isEmpty() ? Optional.empty() : accounts.session(token.get());
    }

    /** Session valide <em>et</em> jeton CSRF de cette session dans le formulaire. */
    private Optional<AccountStore.Session> authenticated(HttpExchange exchange, Map<String, String> form)
            throws SQLException {
        return currentSession(exchange)
                .filter(session -> SecureTokens.constantTimeEquals(form.get("csrf"), session.csrfToken()));
    }

    private static boolean anonymousCsrfValid(HttpExchange exchange, Map<String, String> form) {
        return Cookies.read(exchange, Cookies.FORM)
                .map(cookie -> cookie.length() >= 32 && SecureTokens.constantTimeEquals(form.get("csrf"), cookie))
                .orElse(false);
    }

    private boolean sameOrigin(HttpExchange exchange) {
        if ("cross-site".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))) {
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equalsIgnoreCase(allowedOrigin);
    }

    private int csrfRefused(HttpExchange exchange) throws IOException {
        return page(exchange, 403, error("Formulaire expiré ou session terminée. Recharge la page et recommence.")
                + "<p><a href=\"/compte\">Retour à mon compte</a></p>");
    }

    private static String clientKey(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Real-IP");
        var remote = exchange.getRemoteAddress();
        String direct = remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
        return forwarded != null && remote != null && remote.getAddress() != null
                && remote.getAddress().isLoopbackAddress() ? forwarded.trim() : direct;
    }

    // --- utilitaires -----------------------------------------------------------------------

    private static String notice(HttpExchange exchange) {
        String key = QueryParams.parse(exchange.getRequestURI().getRawQuery()).get("m");
        String text = key == null ? null : NOTICES.get(key);
        return text == null ? "" : "<p class=\"notice\">" + Html.escape(text) + "</p>";
    }

    private static String error(String message) {
        return "<p class=\"offline\">" + Html.escape(message) + "</p>";
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_FORM_BYTES + 1);
        }
        if (bytes.length > MAX_FORM_BYTES) {
            throw new MalformedRequestException("Formulaire trop long.");
        }
        return QueryParams.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private int page(HttpExchange exchange, int status, String body) throws IOException {
        HttpResponses.sendHtml(exchange, status, PageLayout.render(siteTitle, "Mon compte", true, body));
        return status;
    }

    private static int redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        return 303;
    }

    static String originOf(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String origin = uri.getScheme() + "://" + uri.getHost();
        return uri.getPort() >= 0 ? origin + ":" + uri.getPort() : origin;
    }
}
