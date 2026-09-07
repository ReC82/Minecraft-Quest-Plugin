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
