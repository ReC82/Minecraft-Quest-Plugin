# Control Panel — roadmap modulaire

Chaque étape doit laisser `./gradlew build` **vert** et être testable. Aucune ne fabrique de faux
écran fonctionnel : un module non implémenté affiche « à venir ».

## Étape 0 — socle (issue #37, V1) — **LIVRÉ**

- [x] module Gradle `control-panel` autonome (pas de `web-common` en V1 — [ADR-009]).
- [x] `PanelMain` (+ `hash-password`), config par environnement (`Target` multi-cibles), fail-closed
      sur secrets manquants.
- [x] auth mono-utilisateur : `/login`, `/logout`, session signée HMAC, CSRF (double-submit login +
      synchroniseur authentifié), cookies `HttpOnly`/`SameSite`/`Secure` configurable.
- [x] `PermissionService` + énum `Permission`/`Role` (seul `owner` actif).
- [x] `AuditLog` append-only (`control-panel.db`) — utilisé par login/logout.
- [x] **Dashboard = health réel** : statut panel + statut bridge + version plugin + joueurs +
      uptime + mondes essentiels + dernier check. Indisponibilité du bridge → bannière claire,
      jamais un 500.
- [x] plugin : `WebAdminServer` — `GET /admin/v1/health` authentifié, fail-closed, bind interne.
- [x] navigation prête (Dashboard actif ; Joueurs/PNJ/Quêtes/Stories/Diagnostics/Admin/Dev = « à venir »).
- [x] tests : `PanelAppTest` (11), `BridgeClientTest` (4), `PasswordHasherTest` (4), `JsonTest` (3),
      `WebAdminServerTest` (4). `./gradlew test build` vert.
- [x] `docs/control-panel/*` alignés sur l'implémentation.
- [ ] `admin-snapshot.json` (mode dégradé) — reporté ([ADR-004]).

## Étape 0b — déploiement AWS (issue #44) — **LIVRÉ** (validation login owner par navigateur en attente)

- [x] **https://plugadmin.lodylands.com** en ligne : vhost nginx dédié + TLS Let's Encrypt ECDSA +
      redirection 80→443.
- [x] service systemd **`plugadmin`** (utilisateur système dédié, durci), app sous `/opt/plugadmin/app`
      (`installDist`), secrets `/etc/plugadmin/plugadmin.env` hors dépôt.
- [x] backend `127.0.0.1:8090` non exposé ; `/health` OK local **et** public.
- [x] scripts reproductibles `scripts/plugadmin/` (`install.sh` / `deploy.sh` / `rollback.sh`) +
      runbook [DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md).
- [x] **non-régression** `dig.lodygames.com` / `lodylands.com` (+ `www` / `beta`) : inchangés
      avant/après.
- [x] dashboard « RPGQuest DEV indisponible » rendu proprement (200, pas de 500) — état normal
      jusqu'à #51.
- [ ] login owner **réel** validé depuis un navigateur externe (l'owner ; tout le reste de la
      chaîne d'auth est vérifié : GET login, login invalide → 401, CSRF, `/dashboard` anonyme → 303,
      cookies `Secure`/`HttpOnly`/`SameSite`).

## Étape 1 — lectures

- [ ] module **PNJ** : bindings + « PNJ attendus mais non liés » (aurait signalé le `guard`
      manquant de la session #21). Comparaison contenu chargé ↔ bindings.
- [ ] module **Joueurs** : liste + détail lecture (quêtes/stories/variables/claims).
- [ ] module **Diagnostics** : contenu dépôt ↔ contenu chargé ↔ runtime (dépendances cassées,
      quête référencée inconnue, story sans PNJ de rendu…).

## Étape 2 — actions sûres (#36 via le panel)

- [ ] `quest complete|start|reset`, `story advance|complete`, `variable get|set`,
      `player resetnew` — **appels au bridge**, logique dans le plugin, audit log, `confirm` sur
      le destructif, permission `ACTION_*`.
- [ ] « préparer un état de test » = macro d'actions (ex. « claims: débloqué sans claim »).

## Étape 3 — contenu (lecture structurée)

- [ ] consultation des YAML (quêtes/dialogues/stories/marchands/items), détection de
      dépendances cassées, comparaison dépôt/serveur, **sans édition** encore.

## Étape 4 — développement (#29)

- [ ] module « Développement » : GitHub (issues/branches/commits), rapports Claude, jobs Claude,
      tests/build, déploiements avec backup, notifications — **même socle auth/backend**, pas une
      2e app.

## Étape 5 — édition guidée & multi-serveur

- [ ] édition guidée de contenu + validation avant déploiement + historique.
- [ ] plusieurs cibles RPGQuest simultanées, staging.
- [ ] RBAC complet (tester / content-editor / builder / read-only).

## Hors périmètre (durablement)

- terminal shell dans le navigateur ;
- lecture/écriture directe de `data.db` comme API live ;
- SPA lourde ;
- dépendance FTP dans le cœur.
