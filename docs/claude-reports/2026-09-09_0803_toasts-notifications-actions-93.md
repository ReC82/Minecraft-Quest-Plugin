# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 08:03 (locale / UTC sur la box AWS)
* Sujet : #93 — remplacer les blocs « Actions récentes » par des toasts + un centre de
  notifications + une page `/actions` filtrable ; déploiement AWS
* Statut : DONE (déployé et vérifié ; validation navigateur **authentifiée** = PENDING — mot de
  passe owner non détenu par l'assistant)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel : `a01b4d5` (feature : `ed43b9f`)
* Début de la tâche : 2026-09-09 07:42:39
* Fin de la tâche : 2026-09-09 08:04:30
* Durée totale : 00:21:51

---

## Demande

Les gros blocs « Actions récentes » sous les pages métier (`/players`, `/npcs`, `/quests`,
`/stories`, `/dialogues`) surchargent l'interface et mélangent contenu métier et historique
technique. Les remplacer par : (1) des **toasts** après chaque action ; (2) un **centre de
notifications** dans la topbar (cloche) ; (3) une **page dédiée `/actions`** — historique complet,
filtrable, recherchable, paginé. Réutiliser Bootstrap + Bootstrap Icons déjà présents (#92).
**Ne pas créer de 2e base** : réutiliser la source de vérité actuelle. Respecter les permissions
(pas de bypass OWNER). Contrôle-Panel uniquement, ne pas toucher VeryGames / Minecraft.

---

## Audit — système existant réutilisé

| Élément | Constat |
|---|---|
| Stockage | Table SQLite **`agent_action`** (`control-panel.db`), gérée par `AgentStore` (#51). Colonnes : `id`, `agent_id`, `type`, `params_json`, `status`, `created_at`, `created_by`, `delivered_at`, `deliver_count`, `completed_at`, `result_status`, `result_value`, `result_message`, `result_json`. |
| File d'actions | `AgentStore.createAction` → `PENDING` ; l'agent relève via `/agent/v1/actions` → `DELIVERED` ; résultat via `/agent/v1/actions/<id>/result` → `SUCCESS`/`FAILED`/`REJECTED` ; trop vieille sans résultat → `EXPIRED`. |
| Statuts réels | `AgentActionStatus` : `PENDING, DELIVERED, SUCCESS, FAILED, REJECTED, EXPIRED` (`terminal()` = les 4 derniers). |
| Résultats / timestamps / acteur / livraisons | Tous déjà dans `AgentActionRow` (`resultValue`, `resultMessage`, `resultJson`, `createdAt`, `deliveredAt`, `completedAt`, `createdBy`, `deliverCount`). |
| Polling déjà présent | `/agents/actions.json?agent=<id>` (endpoint #65) + `panel.js` `attach()` sur `[data-actions-agent]` — **remplacé** par le nouveau mécanisme. |
| Rendu actuel | `AgentPages.actionsPanel()` (7 appels) + un quasi-doublon dans `PanelApp.agentsContent()` — grosse `<table>` de 20 lignes. |
| Audit | `AuditLog` (`agent.action.create`, …) — inchangé, orthogonal. |

**Décision : aucune nouvelle table, aucun nouveau modèle.** Tout est présentation de
`agent_action`.

---

## Architecture retenue

```
  PAGE MÉTIER   = contenu métier uniquement
  TOAST         = feedback immédiat d'une mutation (redirection ...&toast=<id>)
  CLOCHE        = 8 dernières actions + badge d'attention  (topbar, tous écrans)
  /actions      = historique complet : recherche + filtres statut/domaine + pagination
  /actions/<id> = détail d'une action
```

* **`ActionView`** (pur, sans état) — le vocabulaire commun : mapping domaine, regroupement de
  statut, libellé humain, cible, résultat court, heure relative, badges Bootstrap, et les
  fragments HTML `toastHtml` / `notifItemHtml` / `errorToastHtml` **partagés** entre le rendu
  serveur et le JSON consommé par `panel.js` (même principe que `typeHtml`/`statusHtml` #65).
* **`NotificationCenter`** — agrège `AgentStore.recentActions` de tous les agents, calcule le
  badge, rend la cloche + le dropdown.
* **`ActionsPages`** (pur) — `list(rows, query, now)` et `detail(row, now)`.
* **`PanelApp`** — route `/actions` (+ `/actions/<id>`), placeholder `%NOTIF%` dans la topbar,
  `handleBusinessPage` rend un toast au lieu d'un bandeau, `createAgentAction` redirige avec
  `&toast=<id>`, `/agents` passe à une « Activité récente » compacte, `/agents/actions.json`
  enrichi.
* **`panel.js`** — `initToasts()` + `initNotifications()`.

---

## Source de données

**Unique** : table `agent_action` via `AgentStore` (`recentActions(agentId, limit)`,
`action(id)`). La page `/actions` charge au plus **500 actions par agent** (fusion + tri date
desc), filtre/pagine **en mémoire** (25/page). Pas de SQL dynamique (pas de risque d'injection
sur les filtres). Aucune écriture.

---

## Mapping des domaines (`ActionView.Domain`)

| Préfixe du type | Domaine | Icône (Bootstrap Icons) |
|---|---|---|
| `player.*` | **Joueurs** | `people` |
| `npc.*` | **PNJ** | `person-badge` |
| `quest.*` | **Quêtes** | `journal-check` |
| `story.*` | **Stories** | `book` |
| `dialogue.*` | **Dialogues** | `chat-dots` |
| `item.*` | **Items** | `gift` |
| `server.*`, `agent.*` | **Serveur / Agents** | `hdd-rack` |
| (autre) | **Autres** | `info-circle` |

Sur `/actions`, seules les puces des domaines **réellement présents** dans les données sont
affichées (+ « Tous »).

---

## Mapping des statuts (`ActionView.Group`)

| Groupe UX | Statuts techniques regroupés | Badge |
|---|---|---|
| **Succès** | `SUCCESS` | `text-bg-success` + `check-circle` |
| **En cours** | `PENDING`, `DELIVERED` | `text-bg-primary` + `clock` |
| **Échec** | `FAILED`, `REJECTED`, `EXPIRED` | `text-bg-danger` + `x-circle` |

Le badge conserve le **libellé du statut réel** (« En attente », « Transmise », « Refusée »,
« Expirée »…) ; seule la couleur/icône suit le groupe. Jamais couleur seule (icône + texte).

---

## Toasts

* **Déclenchement** : après une mutation, `createAgentAction` redirige vers
  `…?agent=…&toast=<actionId>` (remplace `&ok=1`). `handleBusinessPage.actionFeedback()` lit
  `toast` → `ActionView.toastHtml(agentStore.action(id))`, ou `err` → `errorToastHtml(...)`.
  **Plus de bandeau, plus de bloc « Actions récentes ».**
* **Rendu** : un `<div class="toast pa-toast" data-toast-action data-toast-group>` unique, placé
  dans le body de la page. `Layout` fournit `<div id="toast-root" class="toast-container
  position-fixed top-0 end-0 p-3" aria-live="polite">`. `panel.js initToasts()` déplace chaque
  `.pa-toast` dans `#toast-root` et appelle `bootstrap.Toast(...).show()`.
* **Contenu** : libellé humain (catalogue) + statut + cible si pertinente + résultat synthétique
  + lien **« Détails »** vers `/actions/<id>`. Les ids techniques ne dominent pas.
* **Comportement (§4)** :
  * `SUCCESS` → `data-bs-autohide="true"`, délai 6 s.
  * `PENDING` → `data-bs-autohide="false"` ; **mis à jour en place** par `panel.js` quand
    l'action se résout (icône + titre + corps + heure), puis auto-fermeture douce si succès,
    reste si échec. **Un seul toast par action logique**, pas de spam.
  * `FAILED` / erreur de validation → `data-bs-autohide="false"`.
* **Accessibilité (§5)** : `role="status"` (pending/success) ou `role="alert"` (erreur),
  `aria-live` (`polite` / `assertive`), `aria-atomic`, `<button class="btn-close"
  data-bs-dismiss="toast" aria-label="Fermer">`. Le toast est en `position-fixed` haut-droite,
  ne bloque pas l'interface.

---

## Polling

* Réutilise **`/agents/actions.json`** (enrichi : `label`, `domain`, `group`, `target`,
  `resultShort`, `age`, `notifHtml` par item + `badge` racine).
* `panel.js` : une seule boucle. Au repos **20 s** ; **3 s** tant qu'un toast « en cours » est
  présent ; garde-fou **400 ticks**. `same-origin`, `credentials: same-origin`. `401`/`403`
  arrêtent proprement. **Pas de WebSocket** (MVP). Le polling ne démarre que si la cloche existe
  ou si un toast en cours est là.

---

## Centre de notifications

* **Cloche** `bi-bell` dans `.topbar-r` (placeholder `%NOTIF%` dans `Layout`, rendu par
  `PanelApp.notifBell()` **seulement si `DIAGNOSTICS_READ`**). `data-bs-toggle="dropdown"`,
  `aria-label="Notifications"`, `aria-haspopup`.
* **Badge** (§7) : `actions non terminales + échecs (FAILED/REJECTED) de moins de 24 h`. Jamais
  le total des succès. Masqué si 0. `data-notif-badge`, rafraîchi par le polling.
* **Menu** (dropdown Bootstrap, `dropdown-menu-end`) : les **8 dernières actions**, chacune avec
  icône de domaine, `[Domaine] · libellé humain · cible`, `[statut] · heure relative · résultat
  court`. Lien vers `/actions/<id>`.
* **Pied** : « Voir toutes les actions » → **`/actions`** (§8).
* `NotificationCenter` agrège tous les agents ; le polling client suit l'agent par défaut
  (`data-notif-agent`) — MVP (un seul agent en pratique).

---

## Page `/actions`

* **En-tête** : « Historique des actions » + sous-titre.
* **Recherche** (`<form method="get">`, `input-group` Bootstrap) sur : type technique, libellé
  humain, libellé de domaine, cible, `result_message`, `result_value`, `created_by`, nom+libellé
  de statut, et toutes les valeurs de `params`. **Combinable** avec les filtres (les puces sont
  des liens qui conservent `q` et l'autre filtre).
* **Filtre statut** — `nav nav-pills` : `Tous / Succès / En cours / Échec`.
* **Filtre domaine** — puces : `Tous` + uniquement les domaines présents.
* **Compteur** « N action(s) ».
* **Desktop** : `<table class="table table-hover align-middle">` (`d-none d-md-block`) —
  Date (relative, `title`=ISO) / Domaine (badge) / Action (libellé + id technique discret) /
  Cible / Statut (badge) / Résultat / Acteur.
* **Mobile** : `<div class="d-md-none actions-cards">` — une carte cliquable par action (badges
  domaine+statut, titre, cible, résultat, heure+acteur). **Pas de table scrollable géante.**
* **Pagination** : `<ul class="pagination">` — Précédent / « Page X / N » / Suivant,
  **25 actions par page** (`ActionsPages.PAGE_SIZE`), les liens conservent statut+domaine+q.
* **Détail `/actions/<id>`** (§15) : type technique, paramètres (`value` masqué), cible, statut,
  timestamps (créée/transmise/terminée), livraisons, résultat (valeur + message), **corps brut
  du résultat** (`result_json`, diagnostic), acteur. Id validé `[0-9a-fA-F-]{8,36}` ; inconnu →
  `404`.

---

## Permissions (§19)

`/actions`, `/actions/<id>`, la cloche et `/agents/actions.json` sont **tous** gardés par
**`DIAGNOSTICS_READ`** via `permissions.can(session.role(), …)` — **aucun `role == OWNER` en
dur**. Anonyme → `303 /login` (JSON → `401`). Dans le modèle V1, tous les rôles (`OWNER`,
`TESTER`, `CONTENT_EDITOR`, `READ_ONLY`) ont `DIAGNOSTICS_READ` : la cloche et l'historique sont
donc visibles par tous les comptes actifs, ce qui est cohérent avec la sidebar actuelle.

---

## Suppression des blocs « Actions récentes »

* `AgentPages.actionsPanel()` **supprimé** + ses 7 appels (`/players`, `/npcs`, `/quests`,
  `/stories`, `/dialogues`, et 2 sous-vues). Méthodes mortes `renderParams` / `renderResult`
  retirées.
* `PanelApp.agentsContent()` : la grosse table par agent → **« Activité récente »** (3 dernières
  lignes : badge + libellé + cible + heure) + lien « Voir l'historique complet des actions ».
* Le Dashboard n'a pas été alourdi (il conserve son hero + cartes ; l'activité est sur la cloche
  et `/actions`).

---

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ActionView.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/NotificationCenter.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ActionsPages.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ActionsCenterTest.java`
* `docs/claude-reports/2026-09-09_0803_toasts-notifications-actions-93.md` (ce rapport)

## Fichiers modifiés

* `.../web/PanelApp.java` — route `/actions` + `handleActions` + `allRecentActions` ; `%NOTIF%` +
  `notifBell()` + `finishPage()` ; `actionFeedback()` (toasts) ; `createAgentAction` →
  `&toast=<id>` ; `agentsContent()` compact ; `handleAgentActionsJson` enrichi.
* `.../web/Layout.java` — `%NOTIF%` dans la topbar, `#toast-root`, nav « Historique ».
* `.../web/AgentPages.java` — `actionsPanel()` + 7 appels + 2 méthodes mortes supprimés.
* `.../web/Icons.java` — `bell`, `history`, `box`.
* `.../resources/assets/panel.js` — `initToasts()` + `initNotifications()` ; suppression de
  `attach`/`renderRows`/`tableHasPending`.
* `.../resources/assets/plugadmin.css` — toasts, dropdown notifications, page `/actions`
  (table desktop + cartes mobile), détail, `.mini-activity`.
* `.../test/.../AgentActionsRefreshTest.java` — réécrit (#65 → #93 : cycle de vie + JSON enrichi
  + `panel.js` pilote toasts/cloche).
* `docs/control-panel/ROADMAP.md`, `docs/control-panel/SECURITY.md`, `docs/current_state.md`.

---

## Base de données / migrations

Aucune. Réutilisation de la table `agent_action` existante.

## Configuration / données

Aucune nouvelle clé.

---

## Tests automatiques

```
./gradlew :control-panel:test   → BUILD SUCCESSFUL — 189 tests, 0 échec, 0 erreur
./gradlew test                  → :test UP-TO-DATE (aucun src/main du plugin modifié)
./gradlew build                 → BUILD SUCCESSFUL
```

* **`ActionsCenterTest`** (10) :
  * `/players` `/npcs` `/quests` `/stories` `/dialogues` : plus de « Actions récentes », plus de
    `data-actions-agent`, plus de `poll-status` ;
  * toast **SUCCESS** (auto-dismiss, `bi-check-circle`, libellé humain, `aria-live` + `btn-close`) ;
  * toast **FAILED** (persistant, `bi-x-circle`) ;
  * toast **PENDING** (`data-toast-action`, « En attente de confirmation de l'agent », `bi-clock`) ;
  * erreur de validation → **toast d'erreur** (pas un bandeau) ;
  * topbar : `#notif-center`, `notif-bell` + `bi-bell`, `data-bs-toggle="dropdown"`,
    `data-notif-list`, `aria-label`, au moins une `notif-item`, « Voir toutes les actions » →
    `/actions`, `data-notif-badge` ;
  * `/actions` : titre, recherche, `nav-pills`, puces des 3 domaines présents, compteur ;
    filtre **statut** (success/pending/failed), filtre **domaine** (npc), **recherche**,
    **recherche + filtre combinés**, table desktop + cartes mobile, badges Bootstrap ;
  * **pagination** (28 actions → « Page 1 / 2 », lien page 2) ;
  * **détail** `/actions/<id>` (libellé, type technique, livraisons, corps brut, retour) + `404` ;
  * `/actions` anonyme → `303 /login` ; garde par `DIAGNOSTICS_READ`, pas de bypass OWNER.
* **`AgentActionsRefreshTest`** réécrit (5) : la mutation redirige avec `&toast=` ; `/agents`
  n'a plus de tableau pollable mais « Activité récente » + lien ; `/agents/actions.json` porte
  `badge`, `label`, `domain=players`, `group=pending`, `target=LoDyMcFly`, `notifHtml`, puis
  `group=success` après résultat ; `panel.js` contient `initToasts`/`initNotifications`,
  `data-notif-badge`, `data-toast-action`, plus de `tableHasPending` ; endpoint auth + agent
  connu ; script servi même origine sans secret.

**Smoke local** de la distribution `installDist` : `/actions`, `/actions?status=failed`,
`/actions?domain=npc&q=x` → 200 ; `/players` sans « Actions récentes », avec `notif-center` +
`bi-bell` ; toast d'erreur rendu ; `panel.js` sans `tableHasPending` ; 0 erreur au log.

---

## Validation technique post-déploiement (réellement exécutée)

* **Jar déployé = HEAD** : SHA-256 `f4773ef40906…b99754e` — **byte-identique** au build frais
  (`ed43b9f`, arbre propre). Contient `web/ActionView`, `web/ActionsPages`,
  `web/NotificationCenter`.
* **Service** : `active (running)` depuis 2026-09-09 08:03:31 UTC, `NRestarts=0`, drop-in
  chargé, `event=panel_started port=8090`. **0 ERROR / 0 Exception** au journal.
* **`/health`** : local + public → `ONLINE`.
* **Routes anonymes** : `/actions`, `/actions/x`, `/actions?status=failed` → `303 /login` ;
  `/agents/actions.json` → `401` (présentes, protégées, ni 404 ni 500).
* **`/assets/panel.js`** servi : contient `initToasts`, `initNotifications`, `data-notif-badge` ;
  **plus** `tableHasPending`.
* **CSP** : `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
  form-action 'self'; frame-ancestors 'none'` — **inchangée**.
* **Autres vhosts AWS** : `dig.lodygames.com` 200, `lodylands.com` 200 ;
  `nginx` / `dig.service` / `lodyland.service` `active`.

---

## Tests manuels à effectuer (PENDING MANUAL VALIDATION)

Nécessitent l'authentification owner (mot de passe non détenu par l'assistant) :

1. Ouvrir `/players`, `/npcs`, `/quests`, `/stories`, `/dialogues` : **plus** de gros bloc
   « Actions récentes ».
2. Déclencher une action sans danger (« Rafraîchir le catalogue » sur `/npcs` ou `/quests`) :
   un **toast** apparaît en haut à droite, passe de « en cours » à « ✓ … » puis se ferme seul.
   Aucun spam (un seul toast).
3. Cloche de la topbar : badge si actions en cours / échecs récents ; le menu déroulant liste
   les dernières actions ; « Voir toutes les actions » ouvre `/actions`.
4. `/actions` : recherche (pseudo joueur, id de quête…), filtres statut, filtres domaine,
   combinaison recherche + filtre, pagination si assez d'historique, clic sur une ligne →
   `/actions/<id>`.
5. Rendu **mobile** : `/actions` en cartes (pas de table scrollable), toast lisible, cloche
   accessible, topbar compacte, pas d'overflow horizontal.
6. Accessibilité : focus visible sur les puces/pills, `btn-close` du toast au clavier,
   `aria-live` (lecteur d'écran annonce le toast).
7. Non-régression : `/quests/new`, `/stories/new`, `/docs`, mutations existantes (#46/#82).

---

## Résultat attendu

Page métier = contenu métier. Toast = feedback immédiat. Cloche = dernières actions.
`/actions` = historique complet, filtrable, recherchable. L'historique complet n'est plus
répété sous chaque page.

---

## Reset / retour à l'état initial

### Rollback applicatif

```
scripts/plugadmin/rollback.sh app
```
Restaure `/opt/plugadmin/releases/20260909-<horodatage précédent>/` (jar #92 « Home + Bootstrap »,
sans #93). Restart + `/health`.

### Code

`git reset --hard a01b4d5^^` (avant `ed43b9f`) sur la branche, ou abandon de la branche.
Aucune migration, aucun état persistant nouveau, aucune clé de config.

---

## Déploiement VeryGames

**Non applicable. VeryGames / Minecraft NON touché** — aucun changement plugin/agent, aucun
redémarrage, aucun `verygames-restart.sh`.

---

## Déploiement AWS (Control Panel uniquement)

* Effectué : `scripts/plugadmin/deploy.sh` (build `:control-panel:installDist` + sauvegarde de
  la release précédente + swap + `systemctl restart plugadmin` + `/health`).
* Aucune modification de config serveur, nginx, TLS, ni des autres vhosts.
* Rollback : `scripts/plugadmin/rollback.sh app`.

---

## Rollback

Voir ci-dessus. `scripts/plugadmin/rollback.sh app` restaure la release précédente
(`/opt/plugadmin/releases/`).

---

## Logs / diagnostic

`journalctl -u plugadmin` depuis le déploiement : `panel_started port=8090`, heartbeats agent OK,
**0 ERROR / 0 Exception**. Le handler `/actions` et le polling `/agents/actions.json` journalisent
chaque requête (`event=request … status=…`).

---

## Documentation mise à jour

* `docs/control-panel/ROADMAP.md` — étape « toasts + centre de notifications + /actions » (livré).
* `docs/control-panel/SECURITY.md` — section « Historique des actions & notifications » (garde
  `DIAGNOSTICS_READ`, aucune donnée nouvelle, fragments pré-échappés, polling same-origin).
* `docs/current_state.md` — #93.

---

## Limitations / travail restant

* **Validation navigateur authentifiée** : PENDING (mot de passe owner non détenu).
* La page `/actions` charge jusqu'à **500 actions par agent** en mémoire puis filtre/pagine
  côté Java. Suffisant pour l'usage actuel ; si l'historique explose, prévoir une requête SQL
  paginée + filtrée (l'architecture `ActionsPages.Query` est déjà prête pour cela).
* Le centre de notifications agrège tous les agents mais le **polling** ne suit que l'agent par
  défaut (un seul agent en pratique aujourd'hui).
* Pas de WebSocket (choix MVP). Latence max de mise à jour de la cloche = 20 s au repos.
* Les toasts et la cloche ne s'affichent que pour les rôles ayant `DIAGNOSTICS_READ` (tous en
  V1) ; un futur rôle plus restreint les masquerait automatiquement.

---

## Prochaine étape suggérée

1. Validation navigateur authentifiée (liste « Tests manuels »), desktop + mobile.
2. Si besoin : requête SQL paginée/filtrée pour `/actions` (remplacer le chargement en mémoire).
3. Envisager une pastille « nouveau » sur la cloche entre deux visites (dernier `created_at` vu
   en `localStorage`).
