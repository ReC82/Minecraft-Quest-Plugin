# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 08:33 (locale / UTC sur la box AWS)
* Sujet : #93 — correction de deux régressions visuelles (panneau « Dernières actions »
  illisible ; toast non affiché après action) + déploiement AWS
* Statut : DONE côté code + build + déploiement ; **validation navigateur authentifiée = PENDING**
  (mot de passe owner non détenu par l'assistant)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel : `b19ea0d`
* Début de la tâche : 2026-09-09 08:19:31
* Fin de la tâche : 2026-09-09 08:34:00
* Durée totale : 00:14:29

---

## Demande

Deux bugs confirmés visuellement après #93 — **corriger uniquement ces deux régressions**, pas de
nouvelle fonctionnalité, puis déployer.

1. **Panneau « Dernières actions » illisible** : ouvert depuis la cloche, le dropdown est trop
   étroit sur desktop → texte cassé mot par mot, résultats illisibles. Il faut une largeur
   confortable (≈ 380–460 px desktop, presque pleine largeur mobile), scroll vertical, bouton
   « Voir toutes les actions » visible.
2. **Toast non affiché** : `/dialogues` → « Rafraîchir le catalogue » → l'URL contient bien
   `toast=<uuid>` mais aucun toast n'apparaît. Auditer toute la chaîne.

---

## BUG 2 — audit complet de la chaîne du toast

| Étape | Constat |
|---|---|
| 1. Création de l'action | OK — `createAgentAction` insère dans `agent_action`. |
| 2. Redirect | OK — `303` vers `…?agent=…&toast=<id>` (whitelist `safeReturnPath` contient `/dialogues`). |
| 3. Paramètre `toast` | OK — `Http.query` décode ; un UUID (`[0-9a-f-]`) n'a pas de caractère à ré-encoder. |
| 4. Lookup de l'action | OK — `agentStore.action(id.trim())` trouve la ligne (INSERT SQLite auto-commit avant le GET suivant). |
| 5. Rendu HTML du toast | **OK — vérifié** : `handleBusinessPage.actionFeedback` insère `<div class="toast pa-toast" role="status" aria-live="polite" data-toast-action data-toast-group="pending" data-bs-autohide="false">…</div>` dans le corps, `#toast-root` est dans `Layout`. Reproduit en local sur `/dialogues` : le markup **est présent**. |
| 6. Bootstrap JS | Servi (le off-canvas et les dropdowns fonctionnent). |
| 7. Init du Toast (`panel.js` `initToasts`) | `document.getElementById("toast-root")` → OK ; `querySelectorAll(".pa-toast")` → OK ; `root.appendChild(el)` → OK. |
| 8. `.show()` | **Point faible** : `bsToast()` renvoyait `null` si `window.bootstrap.Toast` était absent → **aucun `.show()`**, toast silencieusement caché (`.toast:not(.show){display:none}`). |
| 9. Auto-dismiss | Sans objet tant que 8 échoue. |

### Cause racine la plus probable — **cache navigateur d'un `panel.js` pré-#93**

`panel.js` est le **seul** asset qui existait avant #93. Le handler d'asset le sert avec
`Cache-Control: public, max-age=3600` **sans versionnage d'URL**. Juste après le déploiement de
#93, un navigateur qui avait déjà `panel.js` en cache continue de servir **l'ancienne version
(sans `initToasts`)** pendant jusqu'à une heure, sans même revalider. Résultat : le nouveau
markup du toast est là, mais le JS qui l'affiche ne l'est pas. Le off-canvas, lui, marche : son
markup et `bootstrap.bundle.min.js` sont des assets **neufs** (jamais mis en cache avant).

### Correctifs appliqués (défense en profondeur)

1. **Cache-busting des assets locaux** (`web/Assets.java`, nouveau) : `Layout` construit les URLs
   avec `?v=<8 hex du SHA-256 du contenu>` — `bootstrap.min.css`, `bootstrap.bundle.min.js`,
   `bootstrap-icons.min.css`, `plugadmin.css`, `panel.js`. Un fichier modifié = **nouvelle URL**
   → le navigateur ne peut plus servir l'ancienne version depuis son cache. Le handler d'asset
   ignorait déjà la query string (`getRequestURI().getPath()`), donc `/assets/panel.js?v=…` sert
   bien le fichier et garde `ETag` + `304`.
2. **`panel.js` — `showToast()` robuste** : utilise `bootstrap.Toast.getOrCreateInstance(el).show()`
   si Bootstrap est là ; **sinon repli manuel** (`el.classList.add("show")` + auto-dismiss par
   `setTimeout` selon `data-bs-autohide`). Le feedback n'est **jamais** avalé, même si
   `bootstrap` charge en retard. `data-toast-ready` empêche un double affichage.
   `upgradeToasts()` au `window.load` « promeut » les toasts affichés manuellement en vraies
   instances Bootstrap.
3. **CSS `#toast-root`** : `top: 64px` (sous la topbar de 56 px, plus ne la recouvre pas) et
   `z-index: 1200` (au-dessus de la topbar `z-index:30` et des `.toast-container` Bootstrap).

---

## BUG 1 — panneau de notifications : dropdown → **off-canvas**

Le composant Bootstrap est changé : `.dropdown-menu` (largeur capricieuse, contrainte par la
topbar en flex) → **`.offcanvas offcanvas-end`** (`NotificationCenter.bellHtml`).

* Le bouton cloche : `data-bs-toggle="offcanvas" data-bs-target="#notif-panel"` (au lieu de
  `data-bs-toggle="dropdown"`).
* Le panneau : `<aside class="offcanvas offcanvas-end notif-panel">` avec `offcanvas-header`
  (titre + `btn-close` `data-bs-dismiss="offcanvas"`), `offcanvas-body` **scrollable**
  (`notif-body-wrap` → `overflow-y:auto`), et un **pied fixe** `notif-foot-wrap` avec le lien
  **« Voir toutes les actions »** toujours visible.
* **Largeur** (`plugadmin.css`) : `.notif-panel{ --bs-offcanvas-width: 420px }` sur desktop ;
  `--bs-offcanvas-width: 92vw` sous 576 px. Plus jamais une colonne de ~200 px.
* Les lignes de notif : wrapping **normal** (`overflow-wrap: break-word`, plus `anywhere` — donc
  plus de casse mot par mot), icône de domaine, `[Domaine] · action humaine · cible`,
  `[statut] · heure relative · résultat`. Le **résultat est borné à 90 caractères** (`…`) — le
  détail complet appartient à `/actions`.
* `panel.js initNotifications()` : inchangé, il cible toujours `#notif-center[data-notif-agent]`,
  `[data-notif-list]`, `[data-notif-badge]`.

---

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Assets.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NotificationsBugfixTest.java`
* `docs/claude-reports/2026-09-09_0833_fix-93-toast-invisible-notif-panel.md` (ce rapport)

## Fichiers modifiés

* `.../web/Layout.java` — `HEAD_LINKS` / `SCRIPTS` via `Assets.v(...)` (URLs `?v=<hash>`).
* `.../web/NotificationCenter.java` — off-canvas au lieu du dropdown.
* `.../web/ActionView.java` — résultat de la ligne de notif borné à 90 caractères (`clamp`).
* `.../resources/assets/panel.js` — `showToast()` + repli manuel + `data-toast-ready` +
  `upgradeToasts()`.
* `.../resources/assets/plugadmin.css` — off-canvas `.notif-panel` (largeurs responsive),
  `.notif-*` retravaillés (wrapping normal), `#toast-root` sous la topbar.
* `.../test/.../AssetsBootstrapTest.java` — assert `?v=` sur les liens.
* `.../test/.../ActionsCenterTest.java` — assert `data-bs-toggle="offcanvas"` + `#notif-panel`.

---

## Base de données / migrations

Aucune. Aucune nouvelle clé de configuration.

---

## Tests automatiques

```
./gradlew :control-panel:test   → BUILD SUCCESSFUL — 195 tests, 0 échec, 0 erreur
./gradlew test                  → :test UP-TO-DATE (aucun src/main du plugin modifié)
./gradlew build                 → BUILD SUCCESSFUL
```

**`NotificationsBugfixTest`** (6) :
* off-canvas : `data-bs-toggle="offcanvas"` + `#notif-panel` + `offcanvas offcanvas-end
  notif-panel` + `offcanvas-header`/`offcanvas-body`/`notif-foot-wrap` + `btn-close` ; **plus**
  de `dropdown-menu … notif-menu` ; CSS `--bs-offcanvas-width:420px` et `92vw`, ancien
  `.notif-menu{width:380px}` retiré ;
* assets versionnés : `<script src="/assets/bootstrap/bootstrap.bundle.min.js?v=<8hex>" defer>`
  et `…/panel.js?v=<8hex>`, **bundle avant panel.js**, `plugadmin.css?v=` ; `/assets/panel.js?v=x`
  servi 200 `application/javascript` (query ignorée) ;
* `panel.js` : `function showToast`, `bootstrap.Toast.getOrCreateInstance`, repli
  `el.classList.add("show")`, `upgradeToasts`, `data-toast-ready` ;
* **toast rendu pour CHAQUE action de rafraîchissement** — `dialogue.list` (repro), `npc.list`,
  `player.list`, `quest.list`, `story.list`, `item.list` : `#toast-root` + `class="toast
  pa-toast"` + `data-toast-action` + `data-toast-group="pending"` + `aria-live` + `btn-close` +
  script bundle versionné ;
* toast résolu : `SUCCESS` → `data-toast-group="success"` + `data-bs-autohide="true"` +
  `bi-check-circle` + message ; `FAILED` → `failed` + `autohide="false"` + `bi-x-circle` ;
* non-régression `/actions` : titre, `nav-pills`, table desktop + cartes mobile, filtres
  statut/domaine + recherche combinée → 200.

**Smoke local** (distribution `installDist`, agent configuré) — reproduction exacte du bug :
`/dialogues` → POST `dialogue.list` → `303 /dialogues?agent=…&toast=<uuid>` → la réponse contient
`id="toast-root"`, `class="toast pa-toast"`, `data-toast-group="pending"`, « En attente de
confirmation », `bootstrap.bundle.min.js?v=…`. `/home` : off-canvas (`data-bs-toggle="offcanvas"`,
`#notif-panel`, `offcanvas-end notif-panel`), **aucun `notif-menu`**, 3 assets `?v=<8hex>`. Aucun
asset en 404, aucune erreur serveur.

---

## Validation technique post-déploiement (réellement exécutée)

* **Jar déployé = HEAD** : SHA-256 `7ca574d47fff…095a267` — **byte-identique** au build frais
  (`b19ea0d`). `web/Assets.class` présent.
* **Service** : `active (running)` depuis 2026-09-09 08:32:44 UTC, `NRestarts=0`,
  `event=panel_started port=8090`, **0 ERROR / 0 Exception** au journal.
* **`/health`** : local + public → `ONLINE`.
* **Cache-busting live** : `GET /login` → `href="/assets/plugadmin.css?v=2d6b43dd"`. Les 6 assets
  (`bootstrap.bundle.min.js`, `bootstrap.min.css`, `bootstrap-icons.min.css`,
  `bootstrap-icons.woff2`, `plugadmin.css`, `panel.js`) → **200** avec le bon `Content-Type`, y
  compris avec une query `?v=test`.
* **`panel.js` servi** : contient `function showToast`, `classList.add("show")`, `upgradeToasts`,
  `data-toast-ready`.
* **Routes** : `/` → 303 `/home` ; `/home`, `/dialogues`, `/npcs`, `/actions` → 303 `/login`
  (protégées, ni 404 ni 500).
* **CSP** : `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
  form-action 'self'; frame-ancestors 'none'` — **inchangée**.
* **Autres vhosts AWS** : `dig.lodygames.com` 200, `lodylands.com` 200 ;
  `nginx` / `dig.service` / `lodyland.service` `active`.

---

## Validation finale obligatoire — état

Les 7 points de la « validation finale obligatoire » demandent un **navigateur authentifié** :
l'assistant **ne détient pas le mot de passe owner** de production. Ce qui a pu être fait
automatiquement l'a été (195 tests dont 6 ciblant précisément les deux bugs + le smoke local
reproduisant `/dialogues`). Restent à confirmer **par l'owner en session** :

1. `/dialogues` → « Rafraîchir le catalogue » → **toast visible** en haut à droite.
2. Une 2e page métier (`/npcs` → « Rafraîchir le catalogue ») → **toast visible**.
3. Cloche → **panneau off-canvas lisible** sur desktop (≈ 420 px, hiérarchie icône/titre/statut/
   temps/résultat, 3–5 notifications, scroll, bouton « Voir toutes les actions »).
4. Mobile → panneau presque pleine largeur, lisible et utilisable.
5. Aucun texte de notification cassé mot par mot.
6. Onglet réseau : **aucun asset Bootstrap/JS en 404** (les URLs portent `?v=…`).
7. Console : **aucune erreur JS** ; aucune erreur serveur liée aux notifications.

> Astuce de vérification rapide pour le point 6/7 : recharger avec le cache désactivé une fois
> (`Ctrl+Shift+R`) ; grâce au `?v=<hash>` ce ne sera plus nécessaire aux visites suivantes après
> un déploiement.

---

## Résultat attendu

Au clic sur « Rafraîchir le catalogue » : un toast apparaît immédiatement
(« ⏳ Rafraîchir le catalogue des dialogues — En attente de confirmation de l'agent »), puis il
évolue en place (« ✓ … » / « ✕ … ») quand l'agent répond, avec auto-fermeture douce sur succès.
La cloche ouvre un panneau large et lisible.

---

## Reset / retour à l'état initial

### Rollback applicatif
```
scripts/plugadmin/rollback.sh app
```
Restaure `/opt/plugadmin/releases/20260909-083242/` (release #93 d'avant ce correctif). Restart +
`/health`.

### Code
`git reset --hard b04ee70` sur la branche (avant `b19ea0d`). Aucune migration, aucun état
persistant, aucune clé de config.

---

## Déploiement VeryGames

**Non applicable. VeryGames / Minecraft NON touché** — aucun changement plugin/agent, aucun
redémarrage, aucun `verygames-restart.sh`.

---

## Déploiement AWS (Control Panel uniquement)

Effectué : `scripts/plugadmin/deploy.sh` (build `:control-panel:installDist` + sauvegarde
`/opt/plugadmin/releases/20260909-083242` + swap + `systemctl restart plugadmin` + `/health`).
Aucune modification de config serveur, nginx, TLS, ni des autres vhosts.
Rollback : `scripts/plugadmin/rollback.sh app`.

---

## Logs / diagnostic

`journalctl -u plugadmin` depuis le déploiement : `panel_started port=8090`, heartbeats agent OK,
**0 ERROR / 0 Exception**.

---

## Documentation mise à jour

Ce rapport. `docs/control-panel/ROADMAP.md` et `SECURITY.md` décrivent déjà le centre de
notifications (#93) ; le composant (off-canvas au lieu de dropdown) et le cache-busting des assets
sont des corrections d'implémentation qui ne changent ni le contrat de sécurité ni les routes —
un ajustement de la ligne « centre de notifications » de la ROADMAP est fait ci-après.

---

## Limitations / travail restant

* **Validation navigateur authentifiée** : PENDING (mot de passe owner non détenu).
* Le cache-busting couvre les assets **référencés par `Layout`**. Les fontes de Bootstrap Icons
  sont chargées par `bootstrap-icons.min.css` (chemin relatif `fonts/…`) : elles ne sont pas
  versionnées, mais elles ne changent qu'avec une montée de version de Bootstrap Icons (rare) et
  restent servies correctement.
* Le repli manuel de `showToast()` affiche le toast sans l'animation Bootstrap et sans
  auto-fermeture gérée par Bootstrap (un `setTimeout` maison prend le relais) — cas de secours
  uniquement ; en fonctionnement normal c'est le vrai composant Bootstrap Toast.

---

## Prochaine étape suggérée

1. Validation navigateur authentifiée des 7 points ci-dessus (desktop + mobile).
2. Si tout est bon : #93 peut être considéré validé par l'owner (l'assistant ne ferme pas
   l'issue).
