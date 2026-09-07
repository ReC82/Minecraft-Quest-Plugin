package com.lodygames.rpgquest.web.agent;

/**
 * Configuration résolue de l'agent sortant PlugAdmin (issue #51). Construite par
 * {@link AgentConfigLoader} à partir d'un fichier <strong>local non versionné</strong>
 * ({@code plugins/RPGQuest/plugadmin-agent.properties}) éventuellement surchargé par des variables
 * d'environnement.
 *
 * <p><strong>Fail-closed</strong> : {@link #enabled()} n'est {@code true} que si l'activation est
 * explicite <em>et</em> que {@code base-url}, {@code agent-id} et {@code token} sont tous présents.
 * Sans secret, l'agent ne démarre pas (voir {@link AgentConfigLoader}).</p>
 *
 * @param enabled           l'agent doit-il tourner (activation explicite + secrets présents)
 * @param baseUrl           racine HTTPS de PlugAdmin, ex. {@code https://plugadmin.lodylands.com}
 * @param agentId           identifiant de cet agent/cible, ex. {@code rpgquest-dev}
 * @param environment       étiquette d'environnement, ex. {@code dev}
 * @param token             jeton porteur dédié à cette cible — <strong>jamais</strong> journalisé
 * @param heartbeatSeconds  intervalle entre deux heartbeats (borné 5..600)
 * @param pollSeconds       intervalle entre deux relevés de la file d'actions (borné 5..600)
 * @param connectTimeoutMs  délai d'établissement de connexion
 * @param requestTimeoutMs  délai total d'une requête
 * @param maxBackoffSeconds plafond du backoff exponentiel en cas de panne réseau
 * @param actionsEnabled    l'agent récupère-t-il et exécute-t-il des actions (heartbeat seul si {@code false})
 * @param maxResponseBytes  taille maximale d'un corps de réponse accepté de PlugAdmin
 */
public record AgentConfig(
        boolean enabled,
        String baseUrl,
        String agentId,
        String environment,
        String token,
        int heartbeatSeconds,
        int pollSeconds,
        int connectTimeoutMs,
        int requestTimeoutMs,
        int maxBackoffSeconds,
        boolean actionsEnabled,
        long maxResponseBytes) {

    /** Version du contrat agent parlé par le plugin. Incrémentée uniquement sur rupture. */
    public static final String PROTOCOL = "agent/v1";

    /** Base des routes agent, sans slash final : {@code <base-url>/agent/v1}. */
    public String agentBaseUrl() {
        return baseUrl.replaceAll("/+$", "") + "/agent/v1";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Redéfini pour ne <strong>jamais</strong> exposer {@link #token()} : un {@code record}
     * générerait sinon un {@code toString} contenant le secret, susceptible d'atterrir dans un log.</p>
     */
    @Override
    public String toString() {
        return "AgentConfig[enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", agentId=" + agentId
                + ", environment=" + environment
                + ", token=" + (token == null || token.isBlank() ? "<absent>" : "<défini>")
                + ", heartbeatSeconds=" + heartbeatSeconds
                + ", pollSeconds=" + pollSeconds
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", requestTimeoutMs=" + requestTimeoutMs
                + ", maxBackoffSeconds=" + maxBackoffSeconds
                + ", actionsEnabled=" + actionsEnabled
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }

    /** Une configuration inerte : l'agent ne fera rien (les {@code record} sont {@code final}). */
    public static AgentConfig disabled() {
        return new AgentConfig(false, "", "", "", "",
                20, 15, 5000, 10000, 300, true, 256 * 1024L);
    }
}
