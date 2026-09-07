package com.lodygames.rpgquest.panel.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Faux bridge plugin pour les tests : répond à {@code GET /admin/v1/health} si le jeton est bon. */
public final class StubBridge implements AutoCloseable {

    private final HttpServer server;
    private final String expectedToken;

    public StubBridge(String expectedToken) throws IOException {
        this.expectedToken = expectedToken;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/v1/health", exchange -> {
            try (exchange) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.equals("Bearer " + expectedToken)) {
                    send(exchange, 401, "{\"error\":{\"code\":\"unauthorized\"}}");
                    return;
                }
                send(exchange, 200, HEALTH_JSON);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/admin/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static final String HEALTH_JSON = """
            {
              "status": "ONLINE",
              "plugin": {"name": "RPGQuest", "version": "1.2.3-test"},
              "bridge_api_version": "v1",
              "target": {"env": "DEV", "mode": "bridge"},
              "server": {"players_online": 2, "max_players": 20, "uptime_seconds": 3720},
              "worlds": {
                "hub": {"name": "world_hub", "loaded": true},
                "claims": {"name": "claims", "loaded": true},
                "wild": {"name": "wild", "loaded": false}
              },
              "generated_at": "2026-09-07T09:00:00Z"
            }
            """;
}
