package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
import com.lodygames.rpgquest.panel.json.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Diagnostics <strong>serveur / agent</strong> (issue #38, §19-§20) : dérivés du dernier heartbeat
 * de l'agent ({@code AgentLiveness}) — agent injoignable, heartbeat trop ancien, serveur hors ligne,
 * monde essentiel non chargé, version de plugin inconnue. Pas une page de monitoring : uniquement ce
 * qui est réellement détectable et bloquant/incohérent.
 */
public final class ServerDiagnosticProvider implements DiagnosticProvider {

    private final AgentLiveness.Thresholds thresholds;

    public ServerDiagnosticProvider(AgentLiveness.Thresholds thresholds) {
        this.thresholds = thresholds == null ? AgentLiveness.Thresholds.defaults() : thresholds;
    }

    @Override
    public Domain domain() {
        return Domain.SERVER;
    }

    @Override
    public List<DiagnosticEntry> collect(DiagnosticContext ctx) {
        List<DiagnosticEntry> out = new ArrayList<>();
        Instant now = Instant.now();

        if (!ctx.hasAgent()) {
            out.add(DiagnosticEntry.free("AGENT_NOT_CONFIGURED", Severity.INFO, Domain.AGENT, "Agent distant",
                    "Aucun agent distant configuré",
                    "Aucune cible RPGQuest n'a d'agent : PlugAdmin ne reçoit ni heartbeat ni catalogue.",
                    "Les diagnostics de contenu et de serveur ne peuvent pas être calculés.",
                    "Configurer l'agent sortant sur le serveur RPGQuest (voir la documentation de déploiement).",
                    "", "/docs/serveur-depannage#aucun-agent-distant-configure", "/agents", "agent", null));
            return out;
        }

        Optional<HeartbeatRecord> hb = ctx.heartbeat();
        if (hb.isEmpty()) {
            out.add(DiagnosticEntry.free("AGENT_NO_HEARTBEAT", Severity.ERROR, Domain.AGENT, "Agent distant",
                    "Aucun signal de l'agent",
                    "L'agent « " + ctx.agentId() + " » n'a jamais contacté PlugAdmin.",
                    "Impossible de connaître l'état du serveur ou d'exécuter la moindre action à distance.",
                    "Vérifier que le plugin RPGQuest tourne et que sa configuration d'agent sortant est correcte.",
                    "", "/docs/serveur-depannage#aucun-signal-de-l-agent", "/agents", "heartbeat", null));
            return out;
        }

        HeartbeatRecord h = hb.get();
        AgentLiveness live = AgentLiveness.of(hb, thresholds, now);
        String age = AgentLiveness.ageHuman(h.receivedAt(), now);

        if (live == AgentLiveness.OFFLINE) {
            out.add(DiagnosticEntry.free("AGENT_OFFLINE", Severity.ERROR, Domain.AGENT, "Agent distant",
                    "Agent hors ligne",
                    "Le dernier signal de l'agent remonte à " + age + " — au-delà du seuil hors ligne.",
                    "L'état affiché n'est plus fiable et aucune action à distance n'aboutira.",
                    "Vérifier que le serveur RPGQuest tourne et qu'il peut joindre PlugAdmin en HTTPS sortant.",
                    "reçu " + age, "/docs/serveur-depannage#agent-hors-ligne", "/agents", "heartbeat",
                    h.receivedAt()));
        } else if (live == AgentLiveness.STALE) {
            out.add(DiagnosticEntry.free("AGENT_HEARTBEAT_STALE", Severity.WARNING, Domain.AGENT, "Agent distant",
                    "Signal de l'agent vieillissant",
                    "Le dernier signal de l'agent remonte à " + age + ".",
                    "Les informations affichées peuvent être légèrement en retard.",
                    "Surveiller : si le retard s'aggrave, vérifier la connectivité sortante du serveur.",
                    "reçu " + age, "/docs/serveur-depannage#signal-de-l-agent-vieillissant", "/agents",
                    "heartbeat", h.receivedAt()));
        }

        String state = h.serverState() == null ? "" : h.serverState().trim().toUpperCase(Locale.ROOT);
        if (live == AgentLiveness.ONLINE && !state.isEmpty() && !"ONLINE".equals(state)) {
            out.add(DiagnosticEntry.free("SERVER_NOT_ONLINE", Severity.WARNING, Domain.SERVER, "Serveur RPGQuest",
                    "Serveur signalé « " + state + " »",
                    "L'agent répond, mais le serveur RPGQuest s'annonce dans l'état « " + state + " ».",
                    "Les joueurs peuvent ne pas pouvoir se connecter ou jouer normalement.",
                    "Vérifier la console du serveur : démarrage en cours, arrêt, ou erreur au chargement.",
                    "serverState=" + state, "/docs/serveur-depannage#serveur-non-online", "/dashboard",
                    "heartbeat", h.receivedAt()));
        }

        if (h.pluginVersion() == null || h.pluginVersion().isBlank() || "null".equalsIgnoreCase(h.pluginVersion())) {
            out.add(DiagnosticEntry.free("PLUGIN_VERSION_UNKNOWN", Severity.INFO, Domain.SERVER, "Serveur RPGQuest",
                    "Version du plugin inconnue",
                    "Le heartbeat n'indique pas la version du plugin RPGQuest.",
                    "Difficile de savoir si le serveur tourne bien la version attendue.",
                    "Vérifier la version déployée ; un heartbeat sans version peut aussi signaler un agent trop ancien.",
                    "", "/docs/serveur-depannage#version-du-plugin-inconnue", "/dashboard", "heartbeat",
                    h.receivedAt()));
        }

        out.addAll(worldDiagnostics(h, ctx));
        return out;
    }

    /** Mondes essentiels annoncés par le heartbeat mais non chargés. */
    private List<DiagnosticEntry> worldDiagnostics(HeartbeatRecord h, DiagnosticContext ctx) {
        String worldsJson = h.worldsJson();
        if (worldsJson == null || worldsJson.isBlank()) {
            return List.of();
        }
        List<DiagnosticEntry> out = new ArrayList<>();
        try {
            Map<String, Object> worlds = Json.parseObject(worldsJson);
            for (Map.Entry<String, Object> e : worlds.entrySet()) {
                Map<String, Object> w = DiagnosticContext.map(e.getValue());
                String name = DiagnosticContext.str(w.get("name"));
                boolean loaded = DiagnosticContext.isTrue(w.get("loaded"));
                if (!loaded) {
                    String role = e.getKey();
                    String shown = name.isBlank() || "null".equals(name) ? role : name;
                    out.add(DiagnosticEntry.free("WORLD_NOT_LOADED", Severity.WARNING, Domain.WORLDS,
                            shown, "Monde essentiel non chargé",
                            "Le monde « " + shown + " » (rôle « " + role + " ») est configuré mais n'est pas "
                                    + "chargé sur le serveur.",
                            "Les portails, spawns et contenus qui dépendent de ce monde ne fonctionneront pas.",
                            "Créer / recharger le monde en jeu : « /rpgadmin world create " + shown + " ».",
                            "role=" + role, "/docs/serveur-depannage#monde-essentiel-non-charge",
                            "/dashboard", "heartbeat", h.receivedAt()));
                }
            }
        } catch (RuntimeException ignore) {
            // heartbeat worlds_json illisible : rien à signaler ici, l'agent le journalise déjà.
        }
        return out;
    }
}
