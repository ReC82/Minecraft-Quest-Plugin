# Control Panel — configuration (implémentée en #37)

**Aucun secret dans Git.** Configuration non secrète dans `control-panel.properties` (toutes les
clés ont un défaut) ; **secrets par variables d'environnement uniquement**. Précédence :
env > fichier > défauts du code. Le panel **refuse de démarrer** si un secret obligatoire manque
(`PanelConfigLoader`, fail-closed).

## `control-panel.properties` (non versionné)

Modèle : [`control-panel/control-panel.properties.example`](../../control-panel/control-panel.properties.example).

| Clé | Défaut | Rôle |
|---|---|---|
| `panel.port` | `8090` | port d'écoute local (reverse proxy TLS public devant) |
| `panel.bind` | `127.0.0.1` | interface d'écoute |
| `panel.base-url` | *(vide)* | URL publique (liens absolus futurs) |
| `panel.cookie-secure` | `true` | ajoute `Secure` aux cookies ; mettre `false` en http local |
| `panel.session-ttl-minutes` | `120` | durée de vie absolue d'une session |
| `panel.session-idle-minutes` | `30` | expiration sur inactivité |
| `panel.owner-username` | `owner` | identifiant de l'unique compte V1 |
| `panel.disabled` | `false` | kill-switch (voir `PANEL_DISABLED`) |
| `panel.db` | `control-panel.db` | base SQLite du panel (audit log) — **jamais** `data.db` |
| `targets` | `dev` | liste CSV des cibles RPGQuest |
| `targets.default` | 1re cible | cible sélectionnée par défaut |
| `target.<id>.label` | `RPGQuest <ID>` | libellé affiché |
| `target.<id>.mode` | `bridge` | `bridge` (live) ou `snapshot` (mode dégradé, futur) |
| `target.<id>.bridge-url` | `http://127.0.0.1:8100/admin/v1` | base des routes du bridge |
| `target.<id>.token-env` | `RPGQUEST_BRIDGE_TOKEN_<ID>` | **nom** de la variable d'env contenant le jeton |
| `target.<id>.agent` | *(auto si 1 agent)* | id de l'agent sortant #51 affiché sur le dashboard de cette cible |
| `agents` | *(vide)* | liste CSV des agents sortants #51 ; vide = canal agent désactivé |
| `agent.<id>.environment` | `unknown` | étiquette d'environnement attendue de l'agent |
| `agent.<id>.token-env` | `RPGQUEST_AGENT_TOKEN_<ID>` | **nom** de la variable d'env contenant le jeton de l'agent (`-`→`_`, majuscules) |
| `agent.stale-seconds` | `45` | âge du heartbeat au-delà duquel l'agent est `STALE` |
| `agent.offline-seconds` | `150` | âge du heartbeat au-delà duquel l'agent est `OFFLINE` |
| `agent.action-expiry-seconds` | `300` | délai sans résultat après lequel une action passe `EXPIRED` |

## Variables d'environnement

### Control Panel

| Variable | Obligatoire | Rôle |
|---|---|---|
| `RPGQUEST_PANEL_SECRET` | **oui** | secret de signature du cookie de session (≥ 32 car. aléatoires) |
| `RPGQUEST_PANEL_OWNER_HASH` | **oui** | hash du mot de passe owner — `.../bin/control-panel hash-password` (après `:control-panel:installDist`) |
| `RPGQUEST_BRIDGE_TOKEN_<ENV>` | par cible `bridge` | jeton partagé avec le bridge du plugin (ex. `RPGQUEST_BRIDGE_TOKEN_DEV`) |
| `RPGQUEST_AGENT_TOKEN_<ID>` | par agent #51 | jeton de l'agent sortant (ex. `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV`) = clé `token=` de `plugadmin-agent.properties` |
| `RPGQUEST_PANEL_OWNER_USERNAME` | non | surcharge `panel.owner-username` |
| `RPGQUEST_PANEL_PORT` | non | surcharge `panel.port` |
| `RPGQUEST_PANEL_COOKIE_SECURE` | non | surcharge `panel.cookie-secure` |
| `RPGQUEST_PANEL_BASE_URL` | non | surcharge `panel.base-url` |
| `RPGQUEST_PANEL_DB` | non | surcharge `panel.db` |
| `RPGQUEST_PANEL_CONFIG` | non | chemin d'un `control-panel.properties` alternatif |
| `PANEL_DISABLED` | non | `true` → kill-switch (prioritaire sur `panel.disabled`) |

### Plugin — bridge d'administration

**Env uniquement** (jamais `config.yml`) : le bridge est un composant sensible, sa clé et son
activation vivent hors du dépôt et hors des fichiers de contenu.

| Variable | Défaut | Rôle |
|---|---|---|
| `RPGQUEST_WEB_ADMIN_ENABLED` | `false` | `true` pour démarrer le bridge |
| `RPGQUEST_WEB_ADMIN_TOKEN` | *(aucun)* | jeton porteur attendu ; **absent → le bridge ne démarre pas** |
| `RPGQUEST_WEB_ADMIN_BIND` | `127.0.0.1` | interface d'écoute |
| `RPGQUEST_WEB_ADMIN_PORT` | `8100` | port d'écoute |
| `RPGQUEST_WEB_ADMIN_ENV` | `unknown` | étiquette d'environnement renvoyée dans `/health` |

Le jeton doit être **identique** entre `RPGQUEST_WEB_ADMIN_TOKEN` (plugin) et
`RPGQUEST_BRIDGE_TOKEN_<ENV>` (panel).

### Plugin — agent sortant PlugAdmin (#51)

Mécanisme de référence : fichier local **`plugins/RPGQuest/plugadmin-agent.properties`** (hors
Git, modèle [`scripts/plugadmin-agent.properties.example`](../../scripts/plugadmin-agent.properties.example)).
Clés : `enabled`, `base-url`, `agent-id`, `environment`, `token`, `heartbeat-seconds`,
`poll-seconds`, `actions-enabled`, `connect-timeout-ms`, `request-timeout-ms`,
`max-backoff-seconds`, `max-response-kib`. Fail-closed sans `enabled=true` + `base-url` +
`agent-id` + `token`.

Surcharge facultative par variables d'environnement (précédence env > fichier > défaut) :
`RPGQUEST_PLUGADMIN_ENABLED`, `_BASE_URL`, `_AGENT_ID`, `_ENVIRONMENT`, `_TOKEN`,
`_HEARTBEAT_SECONDS`, `_POLL_SECONDS`, `_ACTIONS_ENABLED`.

`token` (agent) = `RPGQUEST_AGENT_TOKEN_<ID>` (PlugAdmin). Détails : [AGENT.md](AGENT.md).

## Prod — déployé par #44 (voir [DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md))

- **URL publique : `https://plugadmin.lodylands.com`** (l'ancien exemple `panel.lodygames.com` est
  abandonné) ;
- reverse proxy nginx dédié `sites-available/plugadmin` (443, TLS Let's Encrypt ECDSA) →
  `127.0.0.1:8090`, redirection 80→443, cookies `Secure` (`RPGQUEST_PANEL_COOKIE_SECURE=true`) ;
- service systemd **`plugadmin`**, utilisateur système **`plugadmin`**, app sous
  **`/opt/plugadmin/app`** (sortie `installDist`, jamais lancée depuis le working tree Git) ;
- secrets : `EnvironmentFile=/etc/plugadmin/plugadmin.env` (`chmod 640 root:plugadmin`, hors dépôt).
  `RPGQUEST_PANEL_SECRET` et `RPGQUEST_BRIDGE_TOKEN_DEV` générés par `install.sh` ;
  `RPGQUEST_PANEL_OWNER_HASH` renseigné par l'owner (`hash-password`) ;
- `RPGQUEST_PANEL_CONFIG=/etc/plugadmin/control-panel.properties` (hors dépôt) ;
- `RPGQUEST_PANEL_DB=/var/lib/plugadmin/control-panel.db` ;
- cible `dev` en mode `bridge` sur `http://127.0.0.1:8100/admin/v1` : **rien n'écoute là sur AWS**
  aujourd'hui → dashboard « RPGQuest DEV indisponible » (attendu jusqu'à #51).
