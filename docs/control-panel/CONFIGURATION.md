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

## Variables d'environnement

### Control Panel

| Variable | Obligatoire | Rôle |
|---|---|---|
| `RPGQUEST_PANEL_SECRET` | **oui** | secret de signature du cookie de session (≥ 32 car. aléatoires) |
| `RPGQUEST_PANEL_OWNER_HASH` | **oui** | hash du mot de passe owner — `.../bin/control-panel hash-password` (après `:control-panel:installDist`) |
| `RPGQUEST_BRIDGE_TOKEN_<ENV>` | par cible `bridge` | jeton partagé avec le bridge du plugin (ex. `RPGQUEST_BRIDGE_TOKEN_DEV`) |
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

## Prod (référence, mise en œuvre = #44)

- reverse proxy nginx : `panel.lodygames.com` (443, TLS Let's Encrypt) → `127.0.0.1:8090`,
  redirection 80→443, cookies `Secure` (`panel.cookie-secure=true`) ;
- bridge : joint depuis AWS soit en local (si co-localisé), soit via tunnel/relais — voir
  [AWS.md](AWS.md) et [DECISIONS.md](DECISIONS.md) ADR-004 ;
- secrets : variables d'environnement du service systemd, `EnvironmentFile=` `chmod 600` ;
- `RPGQUEST_PANEL_CONFIG` pointe un `control-panel.properties` hors dépôt.
