package com.lodygames.rpgquest.discord.github;

import com.lodygames.rpgquest.discord.http.RestClient;
import com.lodygames.rpgquest.discord.http.RestException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implémentation réelle de {@link GitHubApi} sur l'API REST (issue #202).
 *
 * <p><strong>Scrutation soutenable.</strong> Le relevé des issues est fait en requête
 * conditionnelle ({@code If-None-Match} sur l'ETag du relevé précédent). Un {@code 304 Not
 * Modified} ne consomme pas de quota GitHub : scruter toutes les minutes reste donc largement
 * sous les limites, sans endpoint public ni webhook à exposer.</p>
 *
 * <p><strong>Périmètre volontairement étroit</strong> : issues seulement. Aucune écriture de code,
 * de branche ou de workflow, et aucune manipulation des étiquettes de publication
 * {@code #news}/{@code #soon}.</p>
 */
public final class HttpGitHubApi implements GitHubApi {

    private static final Logger LOG = Logger.getLogger(HttpGitHubApi.class.getName());
    private static final String BASE = "https://api.github.com";
    private static final int PER_PAGE = 100;

    private final RestClient client;
    private final String authorization;
    private final String owner;
    private final String repo;

    public HttpGitHubApi(RestClient client, String token, String owner, String repo) {
        this.client = client;
        this.authorization = "Bearer " + token;
        this.owner = owner;
        this.repo = repo;
    }

    private Map<String, String> headers() {
        return Map.of(
                "Authorization", authorization,
                "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28");
    }

    private String repoBase() {
        return BASE + "/repos/" + owner + "/" + repo;
    }

    @Override
    public Repository repository() {
        Map<String, Object> body = client.call("GET", URI.create(repoBase()), headers()).object();
        return new Repository(
                String.valueOf(body.getOrDefault("full_name", owner + "/" + repo)),
                String.valueOf(body.getOrDefault("visibility", "unknown")),
                Boolean.TRUE.equals(body.get("has_issues")));
    }

    @Override
    public void ensureLabel(String name, String color, String description) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        try {
            client.call("GET", URI.create(repoBase() + "/labels/" + encoded), headers());
            return;  // déjà présente : on ne touche ni à sa couleur ni à sa description
        } catch (RestException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("color", color);
        body.put("description", description);
        try {
            client.call("POST", URI.create(repoBase() + "/labels"), headers(), body);
            LOG.log(Level.INFO, "Étiquette « {0} » créée.", name);
        } catch (RestException e) {
            // 422 = créée entre-temps (deux démarrages concurrents) : rien à corriger.
            if (e.status() != 422) {
                throw e;
            }
        }
    }

    @Override
    public Issue createIssue(String title, String body, List<String> labels) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("labels", List.copyOf(labels));
        RestClient.Result result =
                client.call("POST", URI.create(repoBase() + "/issues"), headers(), payload);
        return toIssue(result.object());
    }

    @Override
    public Issue updateIssue(int number, String title, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (title != null) {
            payload.put("title", title);
        }
        if (body != null) {
            payload.put("body", body);
        }
        if (payload.isEmpty()) {
            return issue(number).orElseThrow(() -> new RestException(
                    "Issue #" + number + " introuvable.", 404, false));
        }
        RestClient.Result result = client.call("PATCH",
                URI.create(repoBase() + "/issues/" + number), headers(), payload);
        return toIssue(result.object());
    }

    @Override
    public Optional<Issue> issue(int number) {
        try {
            RestClient.Result result =
                    client.call("GET", URI.create(repoBase() + "/issues/" + number), headers());
            return Optional.of(toIssue(result.object()));
        } catch (RestException e) {
            if (e.status() == 404 || e.status() == 410) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Listing listIssuesByLabel(String label, String etag, int limit) {
        String encoded = URLEncoder.encode(label, StandardCharsets.UTF_8);
        List<Issue> issues = new ArrayList<>();
        String firstEtag = null;

        for (int page = 1; issues.size() < limit; page++) {
            URI uri = URI.create(repoBase() + "/issues?state=all&labels=" + encoded
                    + "&sort=updated&direction=desc&per_page=" + PER_PAGE + "&page=" + page);
            Map<String, String> requestHeaders = new LinkedHashMap<>(headers());
            if (page == 1 && etag != null && !etag.isBlank()) {
                requestHeaders.put("If-None-Match", etag);
            }
            RestClient.Result result = client.call("GET", uri, requestHeaders);
            if (result.notModified()) {
                // Rien n'a changé : surtout ne pas renvoyer une liste vide comme si les issues
                // avaient disparu — l'appelant doit pouvoir distinguer les deux cas.
                return new Listing(true, etag, List.of());
            }
            if (page == 1) {
                firstEtag = result.etag();
            }
            List<Object> array = result.array();
            for (Object entry : array) {
                if (entry instanceof Map<?, ?> map) {
                    // L'endpoint « issues » renvoie aussi les pull requests : on les écarte.
                    if (map.containsKey("pull_request")) {
                        continue;
                    }
                    issues.add(toIssue(map));
                }
            }
            if (array.size() < PER_PAGE) {
                break;
            }
        }
        return new Listing(false, firstEtag, issues);
    }

    @SuppressWarnings("unchecked")
    private static Issue toIssue(Map<?, ?> body) {
        List<String> labels = new ArrayList<>();
        if (body.get("labels") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> label) {
                    labels.add(String.valueOf(label.get("name")));
                } else if (entry != null) {
                    labels.add(String.valueOf(entry));
                }
            }
        }
        Object number = body.get("number");
        return new Issue(
                number instanceof Number n ? n.intValue() : 0,
                asString(body.get("title")),
                asString(body.get("body")),
                asString(body.get("state")),
                asString(body.get("state_reason")),
                labels,
                asString(body.get("html_url")),
                asString(body.get("updated_at")));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
