package com.lodygames.rpgquest.discord.support;

import com.lodygames.rpgquest.discord.github.GitHubApi;
import com.lodygames.rpgquest.discord.github.Issue;
import com.lodygames.rpgquest.discord.http.RestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dépôt GitHub en mémoire pour les tests (issue #202).
 *
 * <p>Son intérêt principal est de pouvoir simuler une <strong>création au résultat ambigu</strong> :
 * {@link #failNextCreateAfterWriting} crée réellement l'issue puis lève une erreur ambiguë, ce qui
 * reproduit exactement le cas « la requête a abouti mais la réponse est perdue » — le scénario qui
 * produit des doublons quand il est mal traité.</p>
 */
public final class FakeGitHub implements GitHubApi {

    private final Map<Integer, Issue> issues = new LinkedHashMap<>();
    private final Set<String> labels = new LinkedHashSet<>();
    private int nextNumber = 100;

    public String visibility = "public";
    public boolean hasIssues = true;

    /** Nombre d'appels de création réellement reçus — sert à prouver l'absence de doublon. */
    public int createCalls;

    /** Crée l'issue puis lève une erreur ambiguë, une seule fois. */
    public boolean failNextCreateAfterWriting;

    /**
     * Numéros volontairement absents du relevé, pour reproduire le retard de cohérence
     * <strong>mesuré sur le vrai dépôt</strong> : {@code GET /issues?…} ignore une issue qui vient
     * d'être créée, alors que {@code GET /issues/{numéro}} la renvoie déjà.
     */
    public final Set<Integer> hiddenFromListing = new LinkedHashSet<>();

    /** Numéros rendus introuvables même en accès unitaire : l'issue n'existe réellement pas. */
    public final Set<Integer> hiddenEverywhere = new LinkedHashSet<>();

    /** ETag courant du relevé ; incrémenté à chaque modification. */
    private String etag = "\"v1\"";

    @Override
    public Repository repository() {
        return new Repository("ReC82/Minecraft-Quest-Plugin", visibility, hasIssues);
    }

    @Override
    public void ensureLabel(String name, String color, String description) {
        labels.add(name);
    }

    public Set<String> createdLabels() {
        return Set.copyOf(labels);
    }

    @Override
    public Issue createIssue(String title, String body, List<String> labelNames) {
        createCalls++;
        int number = nextNumber++;
        Issue issue = new Issue(number, title, body, "open", null, labelNames,
                "https://github.com/ReC82/Minecraft-Quest-Plugin/issues/" + number,
                "2026-10-05T12:00:00Z");
        issues.put(number, issue);
        bumpEtag();
        if (failNextCreateAfterWriting) {
            failNextCreateAfterWriting = false;
            throw new RestException("Réponse perdue après écriture (simulation).", 0, true);
        }
        return issue;
    }

    @Override
    public Issue updateIssue(int number, String title, String body) {
        Issue current = issues.get(number);
        if (current == null) {
            throw new RestException("Issue #" + number + " introuvable.", 404, false);
        }
        Issue updated = new Issue(number,
                title == null ? current.title() : title,
                body == null ? current.body() : body,
                current.state(), current.stateReason(), current.labels(), current.htmlUrl(),
                "2026-10-05T13:00:00Z");
        issues.put(number, updated);
        bumpEtag();
        return updated;
    }

    @Override
    public Optional<Issue> issue(int number) {
        if (hiddenEverywhere.contains(number)) {
            return Optional.empty();
        }
        return Optional.ofNullable(issues.get(number));
    }

    @Override
    public Listing listIssuesByLabel(String label, String requestEtag, int limit) {
        if (requestEtag != null && requestEtag.equals(etag)) {
            return new Listing(true, etag, List.of());
        }
        List<Issue> out = new ArrayList<>();
        for (Issue issue : issues.values()) {
            if (hiddenFromListing.contains(issue.number())
                    || hiddenEverywhere.contains(issue.number())) {
                continue;
            }
            if (issue.hasLabel(label) && out.size() < limit) {
                out.add(issue);
            }
        }
        return new Listing(false, etag, out);
    }

    // ---- Pilotage depuis les tests ---------------------------------------------------------

    /** Ferme une issue avec la raison GitHub donnée ({@code completed}, {@code not_planned}…). */
    public void close(int number, String reason) {
        Issue current = issues.get(number);
        issues.put(number, new Issue(number, current.title(), current.body(), "closed", reason,
                current.labels(), current.htmlUrl(), "2026-10-05T14:00:00Z"));
        bumpEtag();
    }

    public void reopen(int number) {
        Issue current = issues.get(number);
        issues.put(number, new Issue(number, current.title(), current.body(), "open", null,
                current.labels(), current.htmlUrl(), "2026-10-05T15:00:00Z"));
        bumpEtag();
    }

    public void addLabel(int number, String label) {
        Issue current = issues.get(number);
        List<String> merged = new ArrayList<>(current.labels());
        merged.add(label);
        issues.put(number, new Issue(number, current.title(), current.body(), current.state(),
                current.stateReason(), merged, current.htmlUrl(), "2026-10-05T16:00:00Z"));
        bumpEtag();
    }

    /** Remplace le corps, pour simuler des notes de triage ou des marqueurs retirés. */
    public void replaceBody(int number, String body) {
        Issue current = issues.get(number);
        issues.put(number, new Issue(number, current.title(), body, current.state(),
                current.stateReason(), current.labels(), current.htmlUrl(), current.updatedAt()));
        bumpEtag();
    }

    /** Issue créée hors de ce service : sert à vérifier qu'elle n'est jamais retouchée. */
    public int addForeignIssue(String title, String body, List<String> labelNames) {
        int number = nextNumber++;
        issues.put(number, new Issue(number, title, body, "open", null, labelNames,
                "https://github.com/ReC82/Minecraft-Quest-Plugin/issues/" + number,
                "2026-10-05T12:00:00Z"));
        bumpEtag();
        return number;
    }

    public int issueCount() {
        return issues.size();
    }

    public List<Issue> all() {
        return List.copyOf(issues.values());
    }

    private void bumpEtag() {
        etag = "\"v" + (issues.size() + createCalls + System.nanoTime() % 1000) + "\"";
    }
}
