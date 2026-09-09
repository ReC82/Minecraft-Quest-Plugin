package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Socle Bootstrap servi <strong>localement</strong> (#92, lot Bootstrap) : CSS + JS + Bootstrap
 * Icons + fontes servis par PlugAdmin, aucune dépendance CDN au runtime, CSP inchangée,
 * anti-traversal sur {@code /assets/}.
 */
class AssetsBootstrapTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void bootstrapAndIconsAreServedLocally() throws Exception {
        start();
        assertAsset("/assets/bootstrap/bootstrap.min.css", "text/css");
        assertAsset("/assets/bootstrap/bootstrap.bundle.min.js", "application/javascript");
        assertAsset("/assets/bootstrap-icons/bootstrap-icons.min.css", "text/css");
        assertAsset("/assets/bootstrap-icons/fonts/bootstrap-icons.woff2", "font/woff2");
        assertAsset("/assets/plugadmin.css", "text/css");
        assertAsset("/assets/panel.js", "application/javascript");
    }

    @Test
    void bootstrapBundleHasNoEvalAndNoRuntimeCdn() throws Exception {
        start();
        String js = get("/assets/bootstrap/bootstrap.bundle.min.js").body();
        assertFalse(js.contains("eval("), "pas d'eval dans le bundle");
        assertFalse(js.contains("new Function("), "pas de new Function()");
        String css = get("/assets/bootstrap-icons/bootstrap-icons.min.css").body();
        assertFalse(css.contains("http://") && !css.contains("w3.org"), "aucune URL http absolue (hors namespace SVG)");
        assertTrue(css.contains("url(\"fonts/bootstrap-icons.woff2"), "fontes en chemin relatif local");
    }

    @Test
    void renderedPagesLinkOnlyLocalAssetsAndKeepCsp() throws Exception {
        start();
        HttpResponse<String> login = get("/login");
        String html = login.body();
        assertTrue(html.contains("href=\"/assets/bootstrap/bootstrap.min.css\""));
        assertTrue(html.contains("href=\"/assets/bootstrap-icons/bootstrap-icons.min.css\""));
        assertTrue(html.contains("href=\"/assets/plugadmin.css\""));
        assertFalse(html.contains("cdn.jsdelivr"), "aucun CDN");
        assertFalse(html.contains("https://cdn"), "aucun CDN");
        assertFalse(html.contains("unpkg.com"));
        assertEquals("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                        + "form-action 'self'; frame-ancestors 'none'",
                login.headers().firstValue("Content-Security-Policy").orElse(""));
    }

    @Test
    void assetPathTraversalIsRefused() throws Exception {
        start();
        for (String p : new String[] {
                "/assets/../PanelApp.class",
                "/assets/..%2f..%2fetc%2fpasswd",
                "/assets/bootstrap/../../secret",
                "/assets/"}) {
            HttpResponse<String> res = get(p);
            assertTrue(res.statusCode() == 404 || res.statusCode() == 400, p + " -> " + res.statusCode());
            assertFalse(res.body().contains("root:"), p);
            assertFalse(res.body().contains("class PanelApp"), p);
        }
    }

    @Test
    void assetsAreCacheableAndSupportNotModified() throws Exception {
        start();
        HttpResponse<String> first = get("/assets/plugadmin.css");
        assertEquals(200, first.statusCode());
        assertTrue(first.headers().firstValue("Cache-Control").orElse("").contains("max-age"));
        String etag = first.headers().firstValue("ETag").orElse("");
        assertFalse(etag.isEmpty(), "ETag présent");
        HttpResponse<String> second = client.send(
                HttpRequest.newBuilder(uri("/assets/plugadmin.css")).header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, second.statusCode(), "revalidation -> 304");
    }

    // ---- infra ----------------------------------------------------------------------

    private void assertAsset(String path, String contentTypePrefix) throws Exception {
        HttpResponse<byte[]> res = client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode(), path);
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith(contentTypePrefix),
                path + " -> " + res.headers().firstValue("Content-Type").orElse(""));
        assertTrue(res.body().length > 100, path + " non vide");
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:2/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(p)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
