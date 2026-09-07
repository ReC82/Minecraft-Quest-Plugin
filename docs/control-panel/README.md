# RPGQuest Control Panel — vision & index

> Issue **#37**. Cette étape pose l'**architecture** *et* livre un **premier flux vertical
> exécutable** : navigateur → login → dashboard → backend → bridge RPGQuest authentifié → **état
> réel du plugin**. Modules métier (Joueurs, PNJ, Quêtes…) = étapes suivantes (#38, #39, #45…).

## Ce que c'est

Un **outil d'administration séparé du jeu**, pour le propriétaire/développeur du serveur.
**Pas** une fonctionnalité de gameplay, **pas** le portail public (ça, c'est `web-api/`, voir
[docs/WEB_API.md](../WEB_API.md)).

## Livré par #37 (V1)

- module Gradle **`control-panel/`** — application démarrable ([`PanelMain`](../../control-panel/src/main/java/com/lodygames/rpgquest/panel/PanelMain.java)) ;
- **authentification owner** mono-utilisateur : login/logout, session signée (cookie `HttpOnly` +
  `SameSite=Lax` + `Secure` configurable), CSRF, hash PBKDF2-HMAC-SHA256, `PermissionService` +
  `Role`/`Permission` (RBAC minimal extensible) ;
- **configuration externe** (`control-panel.properties` + secrets par variables d'environnement),
  structure **multi-cibles** (`Target` = `{env, mode, bridge-url, token}`) ;
- **bridge RPGQuest** côté plugin — `GET /admin/v1/health`, authentifié par jeton porteur,
  **fail-closed**, écoute `127.0.0.1` par défaut ; renvoie l'**état réel** (version plugin, joueurs
  en ligne, uptime, mondes essentiels chargés, timestamp) — **jamais** de lecture de `data.db` ;
- **dashboard** sobre et responsive : Control Panel ONLINE, cible, RPGQuest ONLINE/OFFLINE,
  version plugin, joueurs, dernier check, **bannière claire si le bridge est indisponible** (pas
  un HTTP 500 opaque) ;
- **journal d'audit** append-only (`control-panel.db`, SQLite, séparée de `data.db`) — utilisé
  dès maintenant pour login/logout ;
- navigation prête pour Dashboard / Joueurs / PNJ / Quêtes / Stories / Diagnostics / Admin /
  Développement (modules non faits = « à venir », pas de faux écran).

## Démarrage local (5 min)

```bash
# 1. générer le hash du mot de passe owner
./gradlew :control-panel:installDist
./control-panel/build/install/control-panel/bin/control-panel hash-password
#   -> RPGQUEST_PANEL_OWNER_HASH=pbkdf2_sha256$210000$...

# 2. côté plugin : activer le bridge (variables d'env du process serveur Paper)
export RPGQUEST_WEB_ADMIN_ENABLED=true
export RPGQUEST_WEB_ADMIN_TOKEN="un-jeton-long-et-aleatoire"     # >= 32 caractères
export RPGQUEST_WEB_ADMIN_BIND=127.0.0.1
export RPGQUEST_WEB_ADMIN_PORT=8100
export RPGQUEST_WEB_ADMIN_ENV=DEV
#   redémarrer le serveur -> "Bridge d'administration web à l'écoute sur 127.0.0.1:8100"

# 3. côté panel
cp control-panel/control-panel.properties.example control-panel.properties   # ajuster si besoin
export RPGQUEST_PANEL_SECRET="autre-secret-long-et-aleatoire"
export RPGQUEST_PANEL_OWNER_HASH="pbkdf2_sha256$210000$...."                  # étape 1
export RPGQUEST_BRIDGE_TOKEN_DEV="un-jeton-long-et-aleatoire"                 # = RPGQUEST_WEB_ADMIN_TOKEN
export RPGQUEST_PANEL_COOKIE_SECURE=false                                     # http local uniquement
./control-panel/build/install/control-panel/bin/control-panel      # ou: ./gradlew :control-panel:run
#   -> http://127.0.0.1:8090
```

Ouvrir `http://127.0.0.1:8090`, se connecter avec `owner` + le mot de passe choisi → le dashboard
affiche l'état réel du bridge. Health du panel lui-même : `GET http://127.0.0.1:8090/health`.

## Principes non négociables

1. **SQLite n'est pas l'API du Control Panel.** Le panel ne touche jamais `data.db`. Le plugin
   reste la source de vérité métier.
2. **Le web ne réimplémente pas les règles métier.** Il appelle des opérations explicites du
   plugin (bridge), qui délèguent aux services existants.
3. **Aucun shell arbitraire** exposé. Actions **déclaratives et whitelistées** uniquement (aucune
   en V1 : seul `health`).
4. **Multi-environnement** dès le design : une cible = objet configurable.
5. **Aucun couplage FTP.**
6. **Sécurité de base dès la V1** : auth, secrets hors Git, CSRF, session signée, audit log,
   kill-switch, HTTPS documenté pour la prod.

## Documents

| Fichier | Contenu |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | modules réels, frontières, flux, stack, structure de code |
| [SECURITY.md](SECURITY.md) | auth, sessions, CSRF, secrets, audit log, kill-switch, menaces |
| [CONFIGURATION.md](CONFIGURATION.md) | clés `control-panel.properties` + variables d'environnement réelles |
| [RPGQUEST_BRIDGE.md](RPGQUEST_BRIDGE.md) | contrat `/admin/v1/*` réel + roadmap des actions |
| [AWS.md](AWS.md) | audit lecture seule de l'instance + ce qu'il faut pour le déploiement #44 |
| [ROADMAP.md](ROADMAP.md) | découpage modulaire, V1 / plus tard |
| [DECISIONS.md](DECISIONS.md) | ADR datées |

## Déploiement (issue #44) — **PlugAdmin en ligne**

Le Control Panel est déployé sous le nom **PlugAdmin** sur **https://plugadmin.lodylands.com**
(instance AWS, nginx dédié + TLS Let's Encrypt, service systemd `plugadmin`, backend
`127.0.0.1:8090` jamais exposé). Runbook complet : **[DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md)**.
Scripts reproductibles : [`scripts/plugadmin/`](../../scripts/plugadmin/).

## Relation avec les autres issues

- **#44** : déploiement AWS **fait** — reverse proxy nginx dédié + `plugadmin.lodylands.com` + TLS.
  Voir [DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md). Rien n'est déployé par #37.
- **#51** : agent sortant `RPGQuest VeryGames → PlugAdmin AWS` pour l'état live. Tant qu'il n'est
  pas là, le dashboard affiche « RPGQuest DEV indisponible » — c'est normal, pas un échec de #44.
- **#36** : les raccourcis admin de test deviendront des *actions du bridge* (#45), logique
  **dans le plugin**, jamais dupliquée côté web.
- **#29** : futur module « Développement », **même socle** — pas une 2e application.
