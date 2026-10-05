package com.lodygames.rpgquest.discord.github;

import java.util.List;

/**
 * Une issue GitHub, réduite à ce dont la synchronisation a besoin (issue #202).
 *
 * @param number      numéro de l'issue
 * @param title       titre actuel
 * @param body        corps actuel — contient la zone gérée par le service <em>et</em> les notes de
 *                    triage libres du propriétaire, qui ne doivent jamais être écrasées
 * @param state       {@code open} ou {@code closed}
 * @param stateReason {@code completed}, {@code not_planned}, {@code duplicate}, ou {@code null} —
 *                    c'est ce champ qui distingue honnêtement « résolu » de « refusé »
 * @param labels      étiquettes actuelles
 * @param htmlUrl     lien public de l'issue, renvoyé dans le sujet Discord
 * @param updatedAt   date ISO-8601 de dernière modification
 */
public record Issue(
        int number,
        String title,
        String body,
        String state,
        String stateReason,
        List<String> labels,
        String htmlUrl,
        String updatedAt) {

    public Issue {
        labels = labels == null ? List.of() : List.copyOf(labels);
        body = body == null ? "" : body;
        title = title == null ? "" : title;
    }

    public boolean closed() {
        return "closed".equalsIgnoreCase(state);
    }

    public boolean hasLabel(String name) {
        return labels.stream().anyMatch(l -> l.equalsIgnoreCase(name));
    }
}
