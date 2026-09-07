# Control Panel — roadmap modulaire

Chaque étape doit laisser `./gradlew build` **vert** et être testable. Aucune ne fabrique de faux
écran fonctionnel : un module non implémenté affiche « à venir ».

## Étape 0 — socle (issue #37, V1) — *doc livrée, code à faire*

- [ ] module Gradle `control-panel` + `web-common` extrait de `web-api` (HTTP/JSON/pipeline).
- [ ] `PanelMain`, config par environnement (`TargetRegistry`), fail-closed sur secrets manquants.
- [ ] auth mono-utilisateur : `/login`, `/logout`, session sûre, CSRF, rate limit login.
- [ ] `PermissionService` + énum `Permission`/`Role` (seul `owner` actif).
- [ ] `AuditLog` (table append-only `control-panel.db`) — écrit dès la 1re action.
- [ ] **Dashboard = health réel** : statut panel + statut bridge (par cible) + version plugin si
      disponible + timestamp du dernier check. Indisponibilité du bridge gérée proprement.
- [ ] plugin : endpoint `web-admin` **ou** `admin-snapshot.json` (mode dégradé) exposant
      `GET /admin/v1/health` + bindings PNJ + `content/issues`.
- [ ] navigation prête (Dashboard, Joueurs, PNJ, Quêtes, Stories, Diagnostics, Admin,
      Développement) — sections futures marquées comme telles.
- [ ] tests : accès anonyme refusé, login OK, logout invalide, session, health réel,
      bridge down géré, aucun secret dans HTML/API/logs, audit log fonctionnel, build vert.
- [ ] `docs/control-panel/*` (fait).

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
