package com.lodygames.rpgquest.panel.config;

import java.util.List;
import java.util.Optional;

/**
 * Configuration résolue du Control Panel. Construite par {@link PanelConfigLoader} (fichier
 * {@code control-panel.properties} + variables d'environnement pour les secrets). Immuable.
 *
 * @param httpPort            port d'écoute local (reverse proxy public devant — voir docs/control-panel)
 * @param bind               interface d'écoute (défaut {@code 127.0.0.1})
 * @param baseUrl            URL publique du panel (liens absolus, cookies) — vide accepté en local
 * @param disabled           kill-switch : toute page sauf {@code /health} renvoie 503
 * @param cookieSecure       ajoute {@code Secure} aux cookies (défaut {@code true} ; {@code false} en dev http local)
 * @param sessionTtlMinutes  durée de vie absolue d'une session
 * @param sessionIdleMinutes expiration sur inactivité
 * @param panelDbPath        base SQLite du panel (audit log ; jamais data.db)
 * @param ownerUsername      identifiant de l'unique compte V1
 * @param ownerPasswordHash  hash du mot de passe owner ({@code pbkdf2_sha256$…}) — depuis l'environnement
 * @param sessionSecret      secret de signature du cookie de session — depuis l'environnement
 * @param targets            cibles RPGQuest connues (au moins une)
 * @param defaultTargetId    cible sélectionnée par défaut
 */
public record PanelConfig(
        int httpPort,
        String bind,
        String baseUrl,
        boolean disabled,
        boolean cookieSecure,
        int sessionTtlMinutes,
        int sessionIdleMinutes,
        String panelDbPath,
        String ownerUsername,
        String ownerPasswordHash,
        String sessionSecret,
        List<Target> targets,
        String defaultTargetId) {

    public PanelConfig {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public Target defaultTarget() {
        return target(defaultTargetId).orElseThrow(() ->
                new IllegalStateException("Cible par défaut « " + defaultTargetId + " » introuvable dans la configuration."));
    }

    public Optional<Target> target(String id) {
        return targets.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
