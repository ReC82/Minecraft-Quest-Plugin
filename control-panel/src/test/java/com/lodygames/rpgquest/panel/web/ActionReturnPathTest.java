package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #227 — après une action agent, l'utilisateur revient-il sur <strong>sa</strong> page ?
 *
 * <h2>Le défaut que ces tests verrouillent</h2>
 *
 * <p>La liste des chemins de retour acceptés était écrite à la main, et {@code /buildings/sites}
 * (issue #213) n'y figurait pas. Conséquence : <em>toutes</em> les actions de cette page
 * redirigeaient vers {@code /agents}. Les mutations partaient pourtant et s'appliquaient — le
 * journal d'actions du serveur de validation le montre, chaque {@code building.site.*} y est en
 * {@code SUCCESS} — mais l'utilisateur ne revoyait jamais sa page, et le bouton « Rafraîchir »
 * souffrant du même défaut, il ne pouvait pas non plus y revenir constater le résultat. D'où sa
 * conclusion, logique et pourtant fausse, que rien n'était enregistré.</p>
 *
 * <p>Le test qui compte est {@link #everyNavigablePageIsAnAcceptedReturnPath()} : il échoue dès
 * qu'une page est ajoutée à la navigation sans être acceptée en retour. C'est celui qui aurait
 * attrapé #227 au moment de l'écrire.</p>
 */
class ActionReturnPathTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    /**
     * Le garde-fou structurel : toute page de la navigation doit être un retour accepté. Une page
     * qu'on peut atteindre est une page vers laquelle une action peut vouloir revenir.
     */
    @Test
    void everyNavigablePageIsAnAcceptedReturnPath() throws Exception {
        start();

        for (Layout.NavGroup group : Layout.nav()) {
            for (Layout.NavItem item : group.items()) {
                String href = item.href();
                if (href == null || !href.startsWith("/")) {
                    continue;
                }
                // On provoque un refus de validation : peu importe, seule la cible du 303 compte.
                HttpResponse<String> res = post("/agents/action",
                        "_csrf=" + csrf(get("/agents").body()) + "&agent=" + TestConfig.AGENT_ID
                                + "&type=building.site.delete&return=" + enc(href)
                                + "&id=pas-un-id&confirm=true");

                assertEquals(303, res.statusCode(), href);
                String location = res.headers().firstValue("Location").orElse("");
                assertTrue(location.startsWith(href + "?"),
                        "la page « " + item.label() + " » (" + href + ") doit être un retour "
                                + "accepté, or l'action renvoie vers : " + location);
            }
        }
    }

    /** Le cas précis de #227, nommé pour qu'une régression soit lisible dans le rapport de test. */
    @Test
    void buildingSitesIsAnAcceptedReturnPath() throws Exception {
        start();

        String location = redirectFor("/buildings/sites");

        assertTrue(location.startsWith("/buildings/sites?"), location);
        assertFalse(location.startsWith("/agents"),
                "c'était exactement le symptôme de #227 : retour sur la page générique Agents");
    }

    /**
     * Le filtre doit toujours refuser ce qui n'est pas une page du panel : son rôle est d'empêcher
     * une redirection ouverte, et ce rôle ne doit pas être perdu en le rendant dérivé.
     */
    @Test
    void anythingThatIsNotAPanelPageStillFallsBackSafely() throws Exception {
        start();

        for (String hostile : new String[] {"https://example.invalid/phishing", "//example.invalid",
                "/../etc/passwd", "/inconnu", "javascript:alert(1)", ""}) {
            String location = redirectFor(hostile);

            assertTrue(location.startsWith("/agents?"),
                    "« " + hostile + " » ne doit jamais être une cible de retour, or : " + location);
        }
    }

    /** Un retour accepté garde le contexte d'agent : sinon la page retombe sur un autre agent. */
    @Test
    void theAgentContextTravelsWithTheReturnPath() throws Exception {
        start();

        String location = redirectFor("/buildings/sites");

        assertTrue(location.contains("agent=" + TestConfig.AGENT_ID), location);
    }

    // ---- Harnais -------------------------------------------------------------------------------

    /** La cible du 303 pour un {@code return} donné. L'action est volontairement refusée. */
    private String redirectFor(String returnPath) throws Exception {
        HttpResponse<String> res = post("/agents/action",
                "_csrf=" + csrf(get("/agents").body()) + "&agent=" + TestConfig.AGENT_ID
                        + "&type=building.site.delete&return=" + enc(returnPath)
                        + "&id=pas-un-id&confirm=true");
        assertEquals(303, res.statusCode());
        return res.headers().firstValue("Location").orElse("");
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("aucun jeton CSRF dans la page");
        }
        return m.group(1);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((x, y) -> x + "; " + y).orElse(""));
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : res.headers().allValues("Set-Cookie")) {
            String[] f = sc.split(";", 2);
            int eq = f[0].indexOf('=');
            String n = f[0].substring(0, eq).trim();
            String v = f[0].substring(eq + 1).trim();
            if (sc.toLowerCase().contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }
}
