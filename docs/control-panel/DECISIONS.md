# Control Panel — décisions d'architecture (ADR)

Format léger : contexte → décision → conséquences. Daté. Immuable (une décision révisée =
nouvelle ADR qui référence l'ancienne).

---

## ADR-001 — Le Control Panel est un module séparé, pas un package de `web-api/`
**2026-09-07 · Accepté**

**Contexte.** `web-api/` est un portail **public anonyme** (site + boutique) qui lit un snapshot
read-only. Le Control Panel est **authentifié** et aura des **pouvoirs admin** (reset joueur,
avance de progression, reload, plus tard déploiement).

**Décision.** Nouveau module Gradle `control-panel/`. Le code d'infrastructure réutilisable
(serveur HTTP, codec JSON, `RequestPipeline`, `RateLimiter`, `AccessLogger`) est **extrait** de
`web-api/` vers un module `web-common/` dont dépendent les deux. La posture de sécurité (sessions,
CSRF, RBAC, audit) reste **propre au Control Panel**.

**Conséquences.** Un peu de refactor initial (`web-common`). Isolation nette : une faille du
portail public ne touche pas le panel admin et inversement. `web-api/` continue de fonctionner à
l'identique.

---

## ADR-002 — Stack HTTP : réutiliser `com.sun.net.httpserver`, réévaluer si le rendu HTML devient pénible
**2026-09-07 · Accepté (provisoire)**

**Contexte.** `web-api` utilise `com.sun.net.httpserver` (JDK, zéro dépendance). Le Control Panel
a besoin de sessions, CSRF, rendu HTML server-rendered, formulaires.

**Décision.** Démarrer sur `com.sun.net.httpserver` + `web-common`. Si sessions/CSRF/templating à
la main deviennent une source de bugs, adopter **un** micro-framework léger (ex. Javalin) — via
une ADR-002bis, pas en douce. **Pas de SPA** en V1 (progressive enhancement).

**Conséquences.** Cohérence avec l'existant, pas de nouvelle grosse dépendance imposée d'emblée.
Risque : réécrire un peu de plomberie web ; accepté pour la V1.

---

## ADR-003 — Le plugin reste la source de vérité ; intégration par un bridge HTTP admin, jamais par `data.db`
**2026-09-07 · Accepté**

**Contexte.** Exigence dure de l'issue #37 : « SQLite ne doit pas devenir l'API du Control
Panel ». Les règles métier vivent dans `QuestProgressEngine`, `StoryService`, `ClaimService`,
`PlayerResetService`, `NpcIdentityService`.

**Décision.** Nouveau package `com.lodygames.rpgquest.web.admin` **dans le plugin** : endpoint
HTTP `/admin/v1/*`, authentifié, **bind interne**, qui **délègue** aux services existants. Le
Control Panel ne connaît pas le schéma SQLite. Actions **déclaratives whitelistées** (une route =
une opération nommée), **jamais** de route `exec`.

**Conséquences.** Un peu de code plugin (léger : réutilise `RpgAdminCommand`/#36 refactoré en
service). Le panel reste découplé du stockage. Testable : stub du bridge côté panel, tests
d'intégration côté plugin.

---

## ADR-004 — Mode dégradé « admin-snapshot » tant que le bridge live n'est pas joignable sur VeryGames
**2026-09-07 · Accepté**

**Contexte.** Le plugin de prod tourne sur VeryGames sans port entrant exploitable. Un bridge
HTTP live nécessite une co-localisation, un tunnel ou un relais — pas disponible aujourd'hui.

**Décision.** Le plugin peut écrire un `admin-snapshot.json` étendu (health + bindings PNJ + PNJ
attendus + diagnostics de contenu), poussé par le même mécanisme d'export atomique que
`web-api`. Le Control Panel le lit et affiche un **dashboard + diagnostics en lecture seule** ;
les **actions** sont désactivées pour cette cible avec un message clair. Les DTO sont **communs**
aux deux modes : quand un canal live arrive, le frontend ne change pas.

**Conséquences.** Le Control Panel apporte de la valeur immédiate (diagnostics, « quel PNJ
manque ») sans attendre l'infra live. La bascule bridge live = config, pas réécriture.

---

## ADR-005 — Autorisation par `PermissionService` dès la V1, même avec un seul utilisateur
**2026-09-07 · Accepté**

**Contexte.** V1 = 1 owner. Tentation : `if user == owner`.

**Décision.** `PermissionService.can(session, Permission.X)` partout dès le début. Énum
`Permission` + `Role` + table `roles`/`user_roles` dans `control-panel.db` posées maintenant, un
seul rôle (`owner`) actif. Ajout de `tester`/`content-editor`/`builder`/`read-only` = données +
UI, pas de refactor.

**Conséquences.** Un peu de structure « inutile » en V1, mais l'ajout du RBAC ne touche pas les
handlers.

---

## ADR-006 — Cible = objet `{env, mode, url|file, token}`, jamais de constante globale
**2026-09-07 · Accepté**

**Contexte.** Exigence #37-A : multi-serveur/multi-environnement. Risque : `localhost` / `claims`
/ `world_hub` codés partout.

**Décision.** `TargetRegistry` chargé depuis la config. Chaque requête backend porte une cible.
Le token du bridge est **sélectionné côté backend selon `env`**, jamais transmis au navigateur.
Aucun nom de monde en dur dans le panel (ils viennent du bridge/snapshot).

**Conséquences.** Sélecteur de cible dans l'UI dès la V1 (même s'il n'y a qu'une entrée). Prêt
pour DEV/staging/prod et plusieurs serveurs.

---

## ADR-007 — Audit log append-only dès le socle
**2026-09-07 · Accepté**

**Contexte.** Exigence #37-5 : mécanisme central de journalisation des actions sensibles, même si
peu d'actions en V1.

**Décision.** Table `audit_log` (`control-panel.db`), écriture avant (intention) + après
(résultat) de chaque action, `request_id` corrélé avec les logs HTTP et le log `[web-admin]` du
plugin. Jamais de secret dans les `details`. Pas de purge auto en V1.

**Conséquences.** ~1 table + 1 DAO + 1 appel par action. Traçabilité totale dès la 1re action
réelle.
