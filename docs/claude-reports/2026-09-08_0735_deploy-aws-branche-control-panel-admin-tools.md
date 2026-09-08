# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 07:35 (heure locale du serveur AWS)
* Sujet : Déploiement sur AWS de la branche `feat/control-panel-admin-tools` pour que `https://plugadmin.lodylands.com` serve les fonctions présentes dans cette branche (page `/players` active, rafraîchissement #65, etc.)
* Statut : DONE — déploiement effectué et vérifié côté serveur ; validation authentifiée par navigateur (login owner) reste à faire par l'owner (`PENDING MANUAL VALIDATION`)
* Branche Git : `feat/control-panel-admin-tools`
* Commit déployé : `dce3bf3` (`dce3bf35e8ae5a387728b31ca07b02b5e0344418`)
* Début de la tâche : 2026-09-08 07:31 (approx.)
* Fin de la tâche : 2026-09-08 07:40:00
* Durée totale : ~00:09:00 (approx.)

## Demande

Déployer sur AWS la version actuelle de la branche `feat/control-panel-admin-tools` afin que
`https://plugadmin.lodylands.com` serve ses fonctions (page `/players` active). Contraintes :
aucun code fonctionnel modifié, aucune action VeryGames, aucun JAR RPGQuest déployé, ne pas
toucher nginx ni les secrets sauf nécessité absolue, pas de merge, utiliser **uniquement** les
scripts PlugAdmin existants (`scripts/plugadmin/deploy.sh`).

## Pré-vol

| Contrôle | Résultat |
|---|---|
| Branche courante | `feat/control-panel-admin-tools` |
| Commit courant | `dce3bf3` |
| Arbre Git | **propre** (`git status --porcelain` vide) |
| Sync origin | `0 0` (ni en avance ni en retard sur `origin/feat/control-panel-admin-tools`) |
| `./gradlew :control-panel:test` | 66 tests, **0 échec** (UP-TO-DATE, exécuté dans cette session sur arbre propre) |
| `./gradlew :control-panel:build` | **BUILD SUCCESSFUL** |

### État déployé AVANT intervention (baseline)

| Élément | Valeur |
|---|---|
| `plugadmin.service` | `active (running)` depuis 2026-09-07 18:41 UTC, PID 563 |
| `/opt/plugadmin/app` | daté 2026-09-07 11:46 (déploiement socle #44 — **antérieur** au commit `594d865` qui active la page Joueurs) |
| `/health` local | `{"panel":"ONLINE","disabled":false}` |
| `/health` public | `{"panel":"ONLINE","disabled":false}` |
| nginx `sites-enabled` | `dig`, `lodyland`, `plugadmin` (3 symlinks) |
| `nginx -t` | OK |
| `https://dig.lodygames.com/` | 200 |
| `https://lodylands.com/` | 200 |
| `https://www.lodylands.com/` | 200 |
| `https://plugadmin.lodylands.com/login` | 200 |
| `https://plugadmin.lodylands.com/dashboard` (anon) | 303 |

## Déploiement

Commande unique, script existant, sans argument :

```
scripts/plugadmin/deploy.sh
```

Déroulé observé :

1. `./gradlew :control-panel:installDist` → BUILD SUCCESSFUL.
2. Sauvegarde `/opt/plugadmin/app` → **`/opt/plugadmin/releases/20260908-073415`** (point de rollback).
3. Copie de la nouvelle distribution dans `/opt/plugadmin/app` (`chown root:root`, `bin/control-panel` en `0755`).
4. Rétention : 5 releases max (3 présentes après coup).
5. `systemctl restart plugadmin.service` → `active (running)` depuis 2026-09-08 07:34:17 UTC, PID 26613, `NRestarts=0`.
6. Log de démarrage : `event=panel_started port=8090 target=dev disabled=false`.
7. `/health` local → `ONLINE`. Script : « OK — déploiement terminé. »

**Non touché** : nginx (aucun `reload`/`restart`, `sites-enabled` inchangé), TLS/Certbot,
`/etc/plugadmin/plugadmin.env` et `control-panel.properties` (mtime inchangé, 2026-09-07
11:19), l'unit systemd, tout autre service. Aucune action VeryGames. Aucun JAR RPGQuest.

## Vérifications post-déploiement

| Contrôle | Résultat |
|---|---|
| `systemctl is-active plugadmin` | `active` |
| `NRestarts` | `0` |
| `/health` local | `ONLINE` |
| `/health` public | `ONLINE` (stable sur 3 relevés consécutifs) |
| `GET /` (public) | 303 → `/dashboard` |
| `GET /login` (public) | 200 |
| `GET /dashboard` (public, anon) | 303 → `/login` |
| `GET /players` (public, anon) | 303 → `/login` (route **active** et protégée par session — n'est plus un placeholder) |
| Distribution déployée vs build frais de `dce3bf3` | **identique** (`diff -rq` → « IDENTIQUE ») |
| SHA-256 du jar (déployé / install / libs) | identiques : `90cf7b57…b684084` |
| Classes présentes dans le jar déployé | `web/AgentPages.class`, `web/Layout.class`, `agent/AgentActionCatalog.class` |
| `assets/panel.js` déployé | **byte-identique** à la source de la branche, contient le durcissement #65 (`tableHasPending`, « premier relevé immédiat ») |
| `nginx -t` | OK |
| nginx `sites-enabled` | inchangé (`dig`, `lodyland`, `plugadmin`) |
| `https://dig.lodygames.com/` | 200 (== baseline) |
| `https://lodylands.com/` | 200 (== baseline) |
| `https://www.lodylands.com/` | 200 (== baseline) |
| `journalctl -u plugadmin` depuis le restart | aucune erreur ; heartbeats agent `rpgquest-dev` (`env=dev version=0.1.0-SNAPSHOT players=1/999`) et polls d'actions en `200` |

Remarque : l'agent RPGQuest de VeryGames se reconnecte **de lui-même** (canal HTTPS sortant,
issue #51) au panel fraîchement redémarré — aucune action n'a été faite côté VeryGames.

## Validation attendue — état

| Attendu | État |
|---|---|
| `/dashboard` fonctionne | Anonyme → 303 `/login` (comportement correct). Rendu authentifié : `PENDING MANUAL VALIDATION` (mot de passe owner non disponible en session). |
| `/players` répond correctement après login | Route active, protégée par session (anon → 303 `/login`). Le code déployé (`AgentPages.players()`) affiche le roster réel via l'action existante `player.list`. Rendu authentifié : `PENDING MANUAL VALIDATION`. |
| Le menu ne montre plus « Joueurs — à venir » | Jar déployé = build de `dce3bf3`, où `Layout.nav()` a `item("Joueurs", "/players", true, …)`. Confirmation visuelle authentifiée : `PENDING MANUAL VALIDATION`. |
| Aucune action VeryGames | **Respecté** — aucune commande/connexion vers VeryGames. |

## Tests manuels restants — `PENDING MANUAL VALIDATION`

À faire par l'owner sur `https://plugadmin.lodylands.com` (nécessite le mot de passe owner) :

1. `/login` → se connecter.
2. `/dashboard` s'affiche (dashboard « AGENT DISTANT » ONLINE, un joueur en ligne sur DEV au moment du déploiement).
3. Menu : « Joueurs » est un lien actif — **pas** « Joueurs — à venir ».
4. `/players` → « Rafraîchir la liste » → la liste réelle des joueurs connectés apparaît (nom, UUID, monde), sur desktop et sur mobile.

## Rollback

En cas de problème :

```
scripts/plugadmin/rollback.sh app     # restaure /opt/plugadmin/releases/20260908-073415 + restart
```

Ne touche qu'à PlugAdmin (ni nginx, ni autres sites, ni VeryGames).

## Fichiers modifiés (dépôt)

| Fichier | Nature |
|---|---|
| `docs/claude-reports/2026-09-08_0735_deploy-aws-branche-control-panel-admin-tools.md` | ce rapport (nouveau) |
| `docs/claude-reports/README.md` | ligne d'index |

Aucun fichier sous `control-panel/src/`, `scripts/`, `docs/deployment/` modifié. Aucun code
fonctionnel touché. `docs/deployment/SERVER_CHANGELOG.md` : non modifié ici (déploiement d'une
branche de travail non fusionnée, sans changement de code serveur ni de procédure ; à
consigner lors de la fusion de la branche vers `main`).

## Résumé

* **Déployé** : `feat/control-panel-admin-tools` @ `dce3bf3` sur `/opt/plugadmin/app` (AWS) via `scripts/plugadmin/deploy.sh`.
* **Service** : `plugadmin.service` `active (running)`, `NRestarts=0`, `/health` local + public `ONLINE`.
* **Non-régression** : `dig` / `lodylands` / `www.lodylands` inchangés (200) ; nginx, secrets, unit systemd, TLS non touchés ; aucune action VeryGames ; aucun JAR RPGQuest.
* **Tests** : `:control-panel:test` 66/0 échec, `:control-panel:build` vert.
* **Rollback** : `scripts/plugadmin/rollback.sh app` → `releases/20260908-073415`.
* **Commit / branche** : commit docs unique sur `feat/control-panel-admin-tools`, poussé. **Pas de merge.**
* **Restant** : validation authentifiée par navigateur (owner) — `/dashboard`, `/players`, menu.
