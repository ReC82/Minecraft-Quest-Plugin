# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 08:20 (heure locale réelle de la machine)
* Sujet : Issue #37 — socle exécutable du RPGQuest Control Panel : premier flux vertical
  navigateur → login → dashboard → backend → bridge RPGQuest authentifié → **état réel du plugin**.
  + audit AWS (lecture seule) pour préparer #44.
* Statut : DONE (socle #37 livré et testé ; validation manuelle en jeu non requise ; #37 **non
  fermée**). Aucune autre issue fermée, aucune branche fusionnée, aucun déploiement.
* Branche Git : `feat/37-control-panel` (créée depuis `feat/36-admin-test-shortcuts`).
* Commit(s) : voir « Commit(s) ».
* Début de la tâche : 2026-09-07 08:20:12
* Fin de la tâche : 2026-09-07 09:01:00
* Durée totale : 00:40:48

## Demande

Passer de l'audit d'architecture (déjà fait) à un **premier jalon fonctionnel** : socle applicatif
`control-panel/`, authentification owner V1 (login/logout/session/CSRF), configuration externe
multi-cibles, bridge admin HTTP minimal et authentifié côté plugin (`GET /admin/v1/health`
renvoyant des **données réelles**), dashboard minimal responsive avec health réel et gestion
propre de l'indisponibilité du bridge, journal d'audit central, observabilité, tests
(`./gradlew test` + `build` verts), audit **lecture seule** de l'AWS pour #44 (sans toucher aux
sites existants), documentation, rapport. Branche dédiée, ne rien fusionner, ne fermer aucune
issue, pas de déploiement.

## Analyse — décisions d'architecture confirmées / ajustées

L'audit `web-api` de la session précédente est confirmé. Les décisions documentées tiennent, avec
deux ajustements techniques justifiés (ADR ajoutées) :

| Décision | État | Note |
|---|---|---|
| Control Panel distinct du portail public `web-api` | **confirmé** | module Gradle `control-panel/` autonome (ADR-001) |
| Pas d'accès direct à `data.db` | **confirmé** | le panel n'a aucune dépendance JDBC vers `data.db` ; seul `control-panel.db` (audit log) lui appartient |
| Plugin = source de vérité métier | **confirmé** | `HealthSource`/`BukkitHealthSource` lisent l'API publique Paper, jamais la base |
| Bridge admin versionné | **confirmé** | routes `/admin/v1/*`, constante `WebAdminServer.API_VERSION` |
| Aucune console shell arbitraire | **confirmé** | pas de route `exec` ; interdiction inscrite dans `RPGQUEST_BRIDGE.md` |
| Multi-environnement | **confirmé** | `Target` = `{id, label, mode, bridge-url, token}` ; jeton résolu côté backend selon l'env, jamais envoyé au navigateur (ADR-006) |
| #29 / #36 réutilisés, pas dupliqués | **confirmé** | #29 = futur module « Développement », même socle ; #36 = futures actions du bridge, logique dans le plugin |
| **Extraction `web-common`** | **ajusté → non fait en V1** | ADR-009 : le panel n'a besoin que du HTTP JDK + d'un mini codec JSON ; extraire un module partagé toucherait `web-api` pour un gain marginal. À refaire quand ≥ 2 modules partageront de la vraie plomberie (#45). |
| **Config du bridge** | **ajusté → variables d'environnement uniquement** | ADR-008 : ajouter une section `web-admin:` à `config.yml` élargirait le record `PluginConfig` (16 champs) + `ConfigValidator` et mettrait l'activation d'un endpoint sensible dans un fichier versionné. Env only, fail-closed. Cohérent avec `RPGQUEST_WEB_API_TOKEN`. |

## Travail effectué

### 1. Bridge d'administration — côté plugin (`com.lodygames.rpgquest.web.admin`)

- **`WebAdminServer`** (`PluginService`) : serveur `com.sun.net.httpserver` démarré **seulement**
  si `RPGQUEST_WEB_ADMIN_ENABLED=true` **et** `RPGQUEST_WEB_ADMIN_TOKEN` non vide (fail-closed).
  Écoute `RPGQUEST_WEB_ADMIN_BIND` (défaut `127.0.0.1`) : `RPGQUEST_WEB_ADMIN_PORT` (défaut 8100).
  Route unique `GET /admin/v1/health` : Bearer obligatoire (comparaison temps constant), 401 sinon,
  405 si méthode ≠ GET, réponses JSON sans secret ni stacktrace. Env injectable (`UnaryOperator<String>`)
  pour les tests.
- **`HealthSource`** (interface) + **`BukkitHealthSource`** : état **réel** — `plugin.version`,
  `bridge_api_version`, `target.env` (`RPGQUEST_WEB_ADMIN_ENV`), `players_online`/`max_players`,
  `uptime_seconds`, `worlds` (hub/claims/wild : nom + chargé via `WorldService#find`), `generated_at`.
- **Bootstrap** : `registry.start(new WebAdminServer(new BukkitHealthSource(plugin, worldService,
  () -> configService.current()), plugin.getSLF4JLogger()))` — enregistré après `webSnapshotWriter`.
  **Aucun changement** de `config.yml`, `PluginConfig`, `ConfigValidator`.

### 2. Module `control-panel/` (nouveau module Gradle, `application`)

- **`PanelMain`** : `run` (défaut) démarre le panel ; `hash-password` génère le hash owner pour
  `RPGQUEST_PANEL_OWNER_HASH`.
- **`config/`** : `PanelConfigLoader` (fichier `control-panel.properties` + env ; **fail-closed**
  si `RPGQUEST_PANEL_SECRET` ou `RPGQUEST_PANEL_OWNER_HASH` manquent), `PanelConfig` (record
  immuable), `Target` (multi-cibles).
- **`security/`** : `PasswordHasher` (PBKDF2-HMAC-SHA256, 210 000 itérations, sel 16 o, format
  `pbkdf2_sha256$iter$salt$hash`) ; `AuthService` (un seul compte owner, coût de vérif payé même
  si le nom ne correspond pas) ; `SessionStore` (sessions mémoire, id 256 bits, **cookie signé
  HMAC** avec `RPGQUEST_PANEL_SECRET` — id forgé sans le secret rejeté avant lookup ; expiration
  absolue + inactivité).
- **`authz/`** : `Permission` (énum), `Role` (`OWNER` + `TESTER`/`CONTENT_EDITOR`/`READ_ONLY`
  déclarés), `PermissionService.can(role, permission)` — aucun test ad hoc du nom d'utilisateur.
- **`audit/`** : `AuditLog` (interface) + `SqliteAuditLog` (`control-panel.db`, table append-only,
  n'échoue jamais une action si l'écriture rate) + `InMemoryAuditLog` (tests). `record(actor,
  action, target, result, details, requestId)`.
- **`bridge/`** : `BridgeClient` (`java.net.http`, timeouts courts) — toute indisponibilité
  (connexion refusée, timeout, 401/403, JSON illisible) devient une `BridgeException` au **message
  affichable tel quel** (« RPGQuest DEV injoignable : … », « … jeton du bridge refusé … »).
  `BridgeHealth` : lecture **tolérante** de la réponse (champ absent → null / -1).
- **`http/Http`** : cookies, corps de formulaire, réponses HTML/texte/redirection, `Set-Cookie`
  (`HttpOnly`, `SameSite`, `Secure` configurable), en-têtes de sécurité (CSP, `X-Frame-Options:
  DENY`, `nosniff`, `no-store`), échappement HTML.
- **`web/`** : `Layout` (gabarit HTML unique, CSS en ligne sobre, responsive, nav Dashboard actif +
  7 modules « à venir ») ; `PanelApp` (assemblage + routing + handlers) :
  - `GET /health` — liveness du panel, jamais authentifié ;
  - `GET/POST /login` — CSRF **double-submit** (cookie court + champ caché) ; échec → 401 +
    « Identifiants invalides » + audit `login.failure` ; succès → session + cookie signé + audit
    `login.success` + 303 vers `/dashboard` ;
  - `POST /logout` — CSRF **synchroniseur** (jeton en session) ; invalide la session + audit `logout` ;
  - `GET /dashboard` — session requise (sinon 303 `/login`), permission `DASHBOARD_VIEW` ; appelle
    `BridgeClient.health(cible par défaut)` ; **bridge KO → page 200 avec bannière rouge « RPGQuest
    <cible> indisponible » + détail**, jamais un 500 ;
  - `/players` `/npc` `/quests` `/stories` `/diagnostics` `/admin` `/dev` — session requise,
    « Module à venir » (aucun faux écran).
  - kill-switch `panel.disabled` / `PANEL_DISABLED` → 503 partout sauf `/health`.
  - Logs structurés (`event=request rid=… method=… path=… status=… ms=…`, `event=bridge_unavailable …`).

### 3. Audit AWS (lecture seule) pour #44

Voir `docs/control-panel/AWS.md`. Résumé : nginx 1.24.0 géré par Certbot ; 2 vhosts
(`dig.lodygames.com`→:3000, `lodyland`/`lodylands.com`→:5000) ; ports libres 8090/8100/9000 ;
certs Let's Encrypt par domaine (pas de wildcard) ; zone DNS `lodygames.com`. **Point structurant** :
le bridge vit dans le plugin qui tourne sur **VeryGames** (aucun port entrant) → pour un accès
live depuis AWS, soit un DEV RPGQuest headless local sur l'instance, soit le mode dégradé
`admin-snapshot`. Le socle #37 supporte déjà l'option locale sans changement. **Aucune
configuration de site existante n'a été touchée.**

## Fichiers créés

Plugin :
- `src/main/java/com/lodygames/rpgquest/web/admin/WebAdminServer.java`
- `src/main/java/com/lodygames/rpgquest/web/admin/HealthSource.java`
- `src/main/java/com/lodygames/rpgquest/web/admin/BukkitHealthSource.java`
- `src/test/java/com/lodygames/rpgquest/web/admin/WebAdminServerTest.java`

Module `control-panel/` :
- `build.gradle.kts`, `control-panel.properties.example`
- `panel/PanelMain.java`
- `panel/config/{PanelConfig,PanelConfigLoader,PanelConfigException,Target}.java`
- `panel/json/{Json,JsonParseException}.java`
- `panel/security/{PasswordHasher,AuthService,Session,SessionStore}.java`
- `panel/authz/{Permission,Role,PermissionService}.java`
- `panel/audit/{AuditLog,AuditEntry,SqliteAuditLog,InMemoryAuditLog}.java`
- `panel/bridge/{BridgeClient,BridgeException,BridgeHealth}.java`
- `panel/http/Http.java`
- `panel/web/{PanelApp,Layout}.java`
- tests : `web/PanelAppTest`, `bridge/BridgeClientTest`, `security/PasswordHasherTest`,
  `json/JsonTest`, `support/{TestConfig,StubBridge}`

Docs :
- `docs/control-panel/AWS.md` (nouveau)
- `docs/control-panel/{README,ARCHITECTURE,SECURITY,CONFIGURATION,RPGQUEST_BRIDGE,ROADMAP,DECISIONS}.md`
  mis à jour (implémentation réelle, ADR-008/009/010)
- `docs/claude-reports/2026-09-07_0820_control-panel-socle-issue-37.md` (ce rapport)

## Fichiers modifiés

- `settings.gradle.kts` — `include("control-panel")`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — démarrage de
  `WebAdminServer` (imports + une ligne d'enregistrement)
- `docs/claude-reports/README.md` — index

## Base de données / migrations

**Aucune migration `data.db`.** Le module `control-panel` crée sa propre base `control-panel.db`
(SQLite, `CREATE TABLE IF NOT EXISTS audit_log …`) — totalement séparée de `data.db` et de
`store.db`. Le bridge ne lit **aucune** base.

## Configuration / données

- **Plugin** : nouvelles variables d'environnement `RPGQUEST_WEB_ADMIN_*` (bridge, désactivé par
  défaut). **Aucune** nouvelle clé `config.yml`, aucun changement de `PluginConfig`.
- **Panel** : `control-panel.properties` (non versionné, modèle `.example` fourni) + variables
  d'environnement pour les secrets (`RPGQUEST_PANEL_SECRET`, `RPGQUEST_PANEL_OWNER_HASH`,
  `RPGQUEST_BRIDGE_TOKEN_<ENV>`). Détail : `docs/control-panel/CONFIGURATION.md`.

## Tests automatiques

- `control-panel` : `PanelAppTest` (11), `BridgeClientTest` (4), `PasswordHasherTest` (4),
  `JsonTest` (3) = **22** — tous verts (exécutés isolément : `./gradlew :control-panel:test`
  BUILD SUCCESSFUL).
- plugin : `WebAdminServerTest` (4) — vert (`./gradlew :test --tests …WebAdminServerTest`
  BUILD SUCCESSFUL).
- Couverture des points demandés : application démarre ; login invalide refusé ; login valide ;
  dashboard protégé (302→/login) ; logout ; CSRF exigé (login double-submit + logout
  synchroniseur) ; secrets absents des réponses ; bridge sans jeton refusé ; jeton invalide refusé ;
  health authentifié OK ; le panel affiche les **vraies** données du bridge (version, joueurs,
  monde) ; bridge indisponible géré proprement (200 + bannière, pas 500) ; kill-switch ; audit
  (acteur/action/résultat/timestamp).

### Commandes exécutées

- `./gradlew test build` — **BUILD SUCCESSFUL** (suite complète : plugin + web-api + control-panel ; jamais de test en échec).
- Rebuild après ajustement `build.gradle.kts` (manifeste jar + distribution) — **BUILD SUCCESSFUL**.

Machine DEV ~900 Mo de RAM : plafonds mémoire locaux hors dépôt en place — suite lente mais verte.

## Tests manuels à effectuer

Aucun test Minecraft requis pour #37. Vérification locale (facultative, ~5 min) : suivre
`docs/control-panel/README.md` § « Démarrage local », se connecter, constater le health réel du
bridge sur le dashboard.

## Résultat attendu

`./control-panel/build/install/control-panel/bin/control-panel` (avec les 3 variables d'env) →
`http://127.0.0.1:8090`, login owner, dashboard affichant l'état réel du plugin RPGQuest via
`GET /admin/v1/health`. Si le bridge est arrêté : bannière « RPGQuest DEV indisponible : … »,
dashboard toujours accessible.

## Reset / retour à l'état initial

`git checkout feat/36-admin-test-shortcuts` (ou supprimer la branche). Aucune donnée : aucun
schéma `data.db` modifié. Le bridge du plugin reste **inerte** tant que `RPGQUEST_WEB_ADMIN_ENABLED`
n'est pas passé à `true` dans l'environnement du serveur.

## Déploiement VeryGames

**Aucun déploiement par cette session.** Le nouveau code plugin (bridge) est **inactif par
défaut** : sur le serveur DEV actuel, tant que `RPGQUEST_WEB_ADMIN_ENABLED`/`_TOKEN` ne sont pas
définis, `WebAdminServer` logge « désactivé » et n'ouvre aucun port. Un futur déploiement du JAR
n'a donc aucun effet observable sans action d'environnement explicite.

## Rollback

Sans objet (rien déployé). Côté code : `git revert` du/des commits ; le bridge n'ouvrira plus de
port même si les variables d'env sont présentes.

## Logs / diagnostic

- Panel : `event=panel_started port=… target=…`, `event=request rid=… status=… ms=…`,
  `event=bridge_unavailable target=… reason=…`, `event=handler_error rid=…`.
- Bridge plugin : `Bridge d'administration web à l'écoute sur 127.0.0.1:8100 …` (ou « désactivé » /
  « NON démarré : … TOKEN absent »).
- Audit : `control-panel.db` → `audit_log` (`login.success`, `login.failure`, `logout`).

## Documentation mise à jour

`docs/control-panel/` : `README` (vision + démarrage local réel), `ARCHITECTURE` (modules réels,
structure de code réelle), `SECURITY` (tableau « État #37 »), `CONFIGURATION` (clés + env réelles),
`RPGQUEST_BRIDGE` (contrat `/admin/v1/health` réel + roadmap), `AWS` (audit + prérequis #44),
`ROADMAP` (Étape 0 cochée), `DECISIONS` (ADR-008/009/010). `docs/claude-reports/README.md`.

## Limitations / travail restant avant #44

1. **Où tourne le bridge en prod ?** Le plugin est sur VeryGames (aucun port entrant). Options :
   DEV RPGQuest headless sur l'instance AWS (le socle le supporte déjà), ou mode dégradé
   `admin-snapshot` (ADR-004), ou tunnel. **Décision owner requise.**
2. **Infos DNS/AWS à fournir** (voir `docs/control-panel/AWS.md` §« Ce qu'il faut… ») :
   - nom exact du sous-domaine (`panel.lodygames.com` ?) ;
   - IP publique / Elastic IP de l'instance AWS pour l'enregistrement `A` ;
   - qui crée l'enregistrement DNS dans la zone `lodygames.com` ;
   - décision « où tourne le bridge » (point 1) ;
   - feu vert éventuel pour installer un Paper DEV headless sur l'instance ;
   - emplacement du service (`/home/ubuntu/rpgquest-panel/` ?) et utilisateur (`ubuntu` ?).
3. **Rate limiting login** : pas encore implémenté (le reverse proxy peut protéger entre-temps).
4. **RBAC multi-rôles** : énum posée, un seul rôle actif.
5. **Modules métier** (#38 dashboard/diagnostics, #39 PNJ, #45 Joueurs+actions, #46 éditeur,
   #47 diff/déploiement) — non commencés, c'est l'objet des issues suivantes.
6. `#37` **non fermée** (validation/design AWS à finaliser).

## Prochaine étape suggérée

1. Owner : fournir les infos DNS/AWS + trancher « où tourne le bridge ».
2. #44 : déploiement AWS (vhost nginx dédié + Certbot + service systemd), procédure esquissée dans
   `AWS.md`, sans toucher `dig`/`lodyland`.
3. #38 : enrichir `/admin/v1/*` (compteurs contenu + `content/issues`) et le dashboard.

---

## Fiche pratique (résultat attendu §14)

**Démarrer le Control Panel :**
```bash
./gradlew :control-panel:installDist
export RPGQUEST_PANEL_SECRET="<32+ car. aléatoires>"
export RPGQUEST_PANEL_OWNER_HASH="<sortie de hash-password>"
export RPGQUEST_BRIDGE_TOKEN_DEV="<même jeton que le plugin>"
export RPGQUEST_PANEL_COOKIE_SECURE=false        # http local
./control-panel/build/install/control-panel/bin/control-panel     # ou: ./gradlew :control-panel:run
```
**URL locale :** `http://127.0.0.1:8090` (login), `http://127.0.0.1:8090/health` (liveness JSON).

**Créer / configurer l'owner :**
```bash
./control-panel/build/install/control-panel/bin/control-panel hash-password
#   saisir le mot de passe -> imprime  RPGQUEST_PANEL_OWNER_HASH=pbkdf2_sha256$210000$...
#   -> mettre cette valeur dans l'environnement. Nom d'utilisateur : "owner" (panel.owner-username).
```

**Démarrer le bridge (côté serveur Paper) :**
```bash
export RPGQUEST_WEB_ADMIN_ENABLED=true
export RPGQUEST_WEB_ADMIN_TOKEN="<même jeton que RPGQUEST_BRIDGE_TOKEN_DEV>"
export RPGQUEST_WEB_ADMIN_BIND=127.0.0.1
export RPGQUEST_WEB_ADMIN_PORT=8100
export RPGQUEST_WEB_ADMIN_ENV=DEV
#   (re)démarrer le serveur -> log "Bridge d'administration web à l'écoute sur 127.0.0.1:8100"
```

**Exemple de health réel (`GET http://127.0.0.1:8100/admin/v1/health`, `Authorization: Bearer <token>`) :**
```json
{
  "status": "ONLINE",
  "plugin": { "name": "RPGQuest", "version": "0.1.0-SNAPSHOT" },
  "bridge_api_version": "v1",
  "target": { "env": "DEV", "mode": "bridge" },
  "server": { "players_online": 1, "max_players": 20, "uptime_seconds": 742 },
  "worlds": {
    "hub":    { "name": "world_hub", "loaded": true },
    "claims": { "name": "claims",    "loaded": true },
    "wild":   { "name": "wild",      "loaded": true }
  },
  "generated_at": "2026-09-07T20:15:00Z"
}
```

**Tests réalisés :** `control-panel` 22/22, plugin `WebAdminServerTest` 4/4, `./gradlew test build`.

**État de #37 :** socle livré (auth, config, bridge health réel, dashboard, audit, tests, docs).
Non fermée : dépend de #44 et d'une décision d'hébergement du bridge.

**Avant #44 :** voir « Limitations / travail restant » ci-dessus + `docs/control-panel/AWS.md`.
