# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 07:02 (locale / UTC sur la box AWS)
* Sujet : #92 — Home à tuiles (launcher) + introduction de Bootstrap 5 comme socle frontend,
  servi localement ; déploiement AWS
* Statut : DONE (déployé et vérifié ; validation navigateur **authentifiée** = PENDING — mot de
  passe owner non détenu par l'assistant)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel : `ab2f4e5` (feature : `a50f87d`)
* Début de la tâche : 2026-09-09 06:44:02
* Fin de la tâche : 2026-09-09 07:02:40
* Durée totale : 00:18:38

---

## Demande

Faire de la page d'arrivée de PlugAdmin un vrai **launcher / control panel à tuiles**
(esprit DirectAdmin) : le Dashboard n'est plus la page d'accueil, il devient **une tuile** et
reste sur sa page dédiée. Après connexion → **Home** dédiée à grandes cartes fonctionnelles.
Introduire **Bootstrap** (CSS + JS) et **Bootstrap Icons** comme socle UI réutilisable, **servis
localement** (aucun CDN au runtime, aucune dépendance à Internet). Bootstrap est une **fondation**
— conserver l'identité et les tokens #92 via une couche `plugadmin.css` par-dessus. Ne pas casser
la CSP (#49), ni l'éditeur #46 (`/quests/new` …), ni la Documentation #49. Ne pas toucher
VeryGames / Minecraft. Déployer uniquement le Control Panel. Ne rien merger, ne pas fermer #92.

---

## Analyse (audit du frontend actuel)

| Aspect | État avant | Décision |
|---|---|---|
| CSS | un gros bloc `Layout.CSS` **inline** dans chaque page (`<style>…</style>`) | extrait **verbatim** dans `assets/plugadmin.css`, servi en `<link>` |
| JS | `panel.js` unique, ajouté **par page** (`<script src="/assets/panel.js">`) | déplacé dans `Layout` (chargé globalement) ; suppression des 3 ajouts par page |
| Assets | un seul handler, `handleAssetPanelJs` (chemin en dur) | handler **générique** `handleAsset` sur le contexte `/assets/` |
| Icônes | sprite SVG local `Icons.java` (`<svg><use href="#i-…">`) + `Icons.sprite()` par page | `Icons.icon()` rend `<i class="bi bi-…">` (Bootstrap Icons) — **signature inchangée** |
| CSP | `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'` | **inchangée** : `default-src 'self'` couvre déjà script/style/police de même origine |
| Responsive | media queries maison (drawer mobile sans JS) | conservé ; complété par la grille Bootstrap `row-cols-*` sur la Home |
| Redirection post-login | `/dashboard` | `/home` |

**Points de vigilance vérifiés** :
- Le bundle `bootstrap.bundle.min.js` (Popper inclus) ne contient **ni `eval` ni `new Function`**
  → compatible `script-src 'self'` sans assouplissement. Popper manipule le CSSOM (`.style`),
  non restreint par la CSP.
- `bootstrap.min.css` référence des `url("data:image/svg+xml,…")` → couverts par
  `img-src 'self' data:` déjà présent.
- `bootstrap-icons.min.css` référence ses fontes en **chemin relatif** `fonts/bootstrap-icons.woff2`
  → servies sous `/assets/bootstrap-icons/fonts/…` (le handler ignore la *query string* `?hash`).

---

## Travail effectué

### 1. Socle Bootstrap local

* **Assets vendored** (téléchargés depuis npm/jsdelivr, non modifiés) sous
  `control-panel/src/main/resources/assets/` :
  * `bootstrap/bootstrap.min.css` (Bootstrap **5.3.8**, 232 111 o)
  * `bootstrap/bootstrap.bundle.min.js` (Bootstrap 5.3.8 + Popper, 80 496 o)
  * `bootstrap-icons/bootstrap-icons.min.css` (Bootstrap Icons **1.13.1**, 87 008 o)
  * `bootstrap-icons/fonts/bootstrap-icons.woff2` (134 044 o) + `.woff` (180 288 o)
  * Note d'attribution : `docs/control-panel/VENDOR_ASSETS.md` (MIT).
* **`PanelApp#handleAsset`** (contexte `/assets/`) : `GET` seul ; chemin relatif validé par regex
  stricte (`[A-Za-z0-9][A-Za-z0-9._-]*(/…)*`, refus de `..`, d'un chemin absolu, d'un segment
  vide) ; lecture **uniquement** sous `classpath:/assets/` ; content-type par extension
  (`css`/`js`/`woff2`/`woff`/`svg`/…) ; `ETag` (SHA-256 tronqué) + `304` sur `If-None-Match` ;
  `Cache-Control: public, max-age=3600`. Remplace `handleAssetPanelJs` (route `/assets/panel.js`
  → servie par le handler générique).
* **`assets/plugadmin.css`** = `Layout.CSS` #92 **verbatim** (dimensionnement des icônes adapté :
  `width/height` SVG → `font-size` pour la police `.bi`) **+** :
  * un **pont `--bs-*`** : `--bs-primary`, `--bs-body-bg`, `--bs-border-radius`,
    `--bs-body-font-family`, `--bs-success|warning|danger|info`, `--bs-link-color`… mappés sur les
    tokens PlugAdmin, + surcharges ciblées (`.btn-primary`, `.card`, `.form-control:focus`,
    `.offcanvas`, `.modal`, `.dropdown-menu`, `.nav-pills`, `.alert`, `.tooltip`) → Bootstrap
    adopte l'identité PlugAdmin, pas de bleu/blanc par défaut ;
  * les styles de la **Home** (`.home-head`, `.home-search`, `.home-group`, `.home-tile`,
    `.home-tile-ic`, `.home-pill`, états `is-disabled` / hover / focus).
* **`Layout`** : `<head>` charge `bootstrap.min.css` + `bootstrap-icons.min.css` +
  `plugadmin.css` ; `<body>` charge `bootstrap.bundle.min.js` + `panel.js` en `defer`, une seule
  fois (`Layout.SCRIPTS`). Le bloc `CSS` inline (≈ 410 lignes) et `%SPRITE%` sont supprimés.
* **`Icons.java`** : `icon(name)` / `icon(name, cls)` → `<i class="bi bi-<name> <cls>"
  aria-hidden="true"></i>` via une table nom-logique → nom Bootstrap Icons (41 entrées :
  `dashboard→speedometer2`, `players→people`, `npc→person-badge`, `quests→journal-check`,
  `stories→book`, `dialogues→chat-dots`, `docs→file-earmark-text`, `agents→hdd-network`,
  `warning→exclamation-triangle`, `error→x-circle`, `check→check-circle`, `info→info-circle`,
  `copy→clipboard`, `edit→pencil`, `plus→plus-lg`, `trash→trash`, `search→search`,
  `home→grid-1x2`…). Signature **inchangée** → les ~40 appels existants (Layout, `Ui`,
  `AgentPages`, `DocsPages`, `ContentEditorPages`, `Markdown`) fonctionnent sans modification.
  `sprite()` neutralisé (retourne `""`).

### 2. Home — launcher à tuiles

* **`HomePages.java`** : rend l'accueil.
  * En-tête : titre **PlugAdmin**, sous-titre « Administration de LodyQuests », phrase courte,
    et un champ de **recherche globale désactivé** (place réservée, future-proof).
  * **4 groupes** de tuiles : *Vue d'ensemble* (Dashboard, Agents, Diagnostics · à venir),
    *Gestion du jeu* (Joueurs, PNJ, Quêtes, Stories, Dialogues), *Ressources* (Documentation),
    *Administration* (Administration · à venir, Développement · à venir).
  * Grille Bootstrap `row row-cols-1 row-cols-md-2 row-cols-lg-3 row-cols-xl-4 g-3` (1 col
    mobile → 2 tablette → 3 → 4 selon largeur).
  * Chaque tuile activée = **vrai lien `<a class="home-tile" href="…">`** : toute la carte
    cliquable, focus clavier visible, grande icône Bootstrap Icons, titre, description courte,
    badge synthétique, chevron au survol. Tuile « à venir » = `<div class="home-tile is-disabled"
    aria-disabled="true">` **non cliquable**, badge « À venir ».
  * **Permissions** : chaque tuile porte sa permission (`DASHBOARD_VIEW`, `PLAYERS_READ`,
    `NPC_READ`, `CONTENT_READ`, `DIALOGUE_READ`, `DOCS_READ`, `DIAGNOSTICS_READ`, `AUDIT_READ`,
    `DEV_MODULE`) ; une tuile non autorisée est **masquée** (cohérent avec la sidebar). Un groupe
    entièrement masqué disparaît.
  * **Badges synthétiques** — depuis le **dernier relevé agent uniquement**, aucune requête
    déclenchée pour la décoration (`AgentPages#homeSummary` : lecture des caches
    `player.list` / `npc.list` / `quest.list` / `story.list` / `dialogue.list`) :
    Dashboard & Agents → `ONLINE` / `OFFLINE` / `STALE` (liveness heartbeat) ; Joueurs →
    « N en ligne » ; PNJ → « N PNJ » + « N alertes » si `withWarnings` ; Quêtes → « N quêtes » ;
    Stories → « N stories » ; Dialogues → « N dialogues » + alertes (`withWarnings` + `loadIssues`).
    Donnée absente → pas de badge.
* **`PanelApp`** : route `/home` (session requise, tuiles filtrées par permission) ; `handleRoot`
  et le succès de login redirigent vers `/home` ; login GET déjà authentifié → `/home`. Le
  **Dashboard reste** `/dashboard` (200, inchangé). `handleHome` calcule l'état serveur via
  `AgentLiveness.of(latestHeartbeat, thresholds, now)` (léger, pas de construction du hero).
* **Sidebar** : nouvel item **« Accueil »** (`/home`, icône `bi-grid-1x2`) **en tête** du groupe
  « Vue d'ensemble », avant Dashboard.

### 3. Non-régression #46 / #49

* `/quests/new`, `/quests/edit/…`, `/stories/new`, `/stories/edit/…` : **non touchés**
  (héritent seulement du nouveau CSS via `plugadmin.css` + Bootstrap).
* `/docs` (recherche, Markdown sûr, bouton Copier, auth, whitelist, slugs) : **non touché** ;
  `DocsPages.assetScript()` retourne `""` (le script est chargé globalement par `Layout`).

---

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/HomePages.java`
* `control-panel/src/main/resources/assets/plugadmin.css`
* `control-panel/src/main/resources/assets/bootstrap/bootstrap.min.css`
* `control-panel/src/main/resources/assets/bootstrap/bootstrap.bundle.min.js`
* `control-panel/src/main/resources/assets/bootstrap-icons/bootstrap-icons.min.css`
* `control-panel/src/main/resources/assets/bootstrap-icons/fonts/bootstrap-icons.woff2`
* `control-panel/src/main/resources/assets/bootstrap-icons/fonts/bootstrap-icons.woff`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/HomeLauncherTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/AssetsBootstrapTest.java`
* `docs/control-panel/VENDOR_ASSETS.md`
* `docs/claude-reports/2026-09-09_0702_home-tuiles-socle-bootstrap-92.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Layout.java` — `<head>` liens,
  `SCRIPTS`, item « Accueil », suppression du bloc `CSS` inline et du sprite.
* `.../web/Icons.java` — Bootstrap Icons.
* `.../web/PanelApp.java` — `handleAsset`, route `/assets/` + `/home` + `handleHome`, redirections
  `→ /home`, champ `homePages`.
* `.../web/AgentPages.java` — `homeSummary(agentId)` + record `HomeSummary`.
* `.../web/DocsPages.java` — `assetScript()` → `""`.
* `control-panel/src/test/java/.../PanelAppTest.java`,
  `.../AuthenticatedSmokeTest.java` — assertion post-login `/dashboard` → `/home`.
* `docs/control-panel/ROADMAP.md`, `SECURITY.md`, `docs/current_state.md`.

---

## Base de données / migrations

Aucune.

## Configuration / données

**Aucune nouvelle clé.** Les assets sont embarqués dans le jar. `PLUGADMIN_CONTENT_DIR` + ACL de
la tâche précédente (#46) restent inchangés.

---

## Impact CSP

**Nul.** L'en-tête reste :
`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self';
frame-ancestors 'none'`. Vérifié sur `/login` en production après déploiement. Aucun `'unsafe-eval'`,
aucun hôte tiers, aucun script inline.

---

## Tests automatiques

```
./gradlew :control-panel:test   → BUILD SUCCESSFUL — 179 tests, 0 échec, 0 erreur
                                  (169 avant + HomeLauncherTest 5 + AssetsBootstrapTest 5 ;
                                   2 assertions post-login mises à /home)
./gradlew test                  → :test UP-TO-DATE (aucun src/main du plugin modifié)
./gradlew build                 → BUILD SUCCESSFUL
```

Nouveaux tests :
* **`AssetsBootstrapTest`** (5) — Bootstrap CSS/JS + Bootstrap Icons + fontes + `plugadmin.css`
  + `panel.js` servis en 200 avec le bon content-type ; bundle sans `eval` / `new Function` ;
  fontes en chemin relatif local ; pages rendues ne lient **que** `/assets/…` (aucun `cdn`,
  `jsdelivr`, `unpkg`) ; **CSP identique** à la chaîne attendue ; path traversal
  (`/assets/../…`, `/assets/..%2f..%2fetc%2fpasswd`, `/assets/`) → `400`/`404`, jamais de contenu ;
  `ETag` + `304` sur revalidation ; `Cache-Control` avec `max-age`.
* **`HomeLauncherTest`** (5) — login → `303 /home` et `/` → `/home` ; `/home` anonyme →
  `303 /login` ; `/home` authentifié 200 avec en-tête launcher, grille Bootstrap `row-cols-*`,
  les 8 tuiles principales (Dashboard, Joueurs, PNJ, Quêtes, Stories, Dialogues, Documentation,
  Agents) comme liens vers la bonne route, groupes « Vue d'ensemble » / « Gestion du jeu »,
  tuile Diagnostics **« à venir » non cliquable**, champ de recherche présent **et désactivé** ;
  sidebar : « Accueil » **avant** « Dashboard », `navlink active` sur `/home` ; `/dashboard`
  reste 200 avec `<h1>Dashboard</h1>`.

**Smoke local** de la distribution `installDist` (jar identique à celui déployé) : login →
`/home` ; `/home` rend les 4 groupes + 8 tuiles-liens + tuiles `is-disabled` ; `/dashboard`,
`/players`, `/npcs`, `/quests`, `/stories`, `/dialogues`, `/docs`, `/agents`, `/quests/new`,
`/stories/new` → **tous 200** ; 6 assets 200 ; **0 erreur** au log.

---

## Validation technique post-déploiement (réellement exécutée)

* **Jar déployé = HEAD** : `/opt/plugadmin/app/lib/control-panel-0.1.0-SNAPSHOT.jar` SHA-256
  `faa6defc6d3f7ea6…4017487` — **byte-identique** au jar fraîchement rebâti (`a50f87d`, arbre
  propre). Contient `web/HomePages`, `assets/bootstrap/bootstrap.min.css`,
  `assets/bootstrap/bootstrap.bundle.min.js`, `assets/bootstrap-icons/bootstrap-icons.min.css`,
  `assets/bootstrap-icons/fonts/bootstrap-icons.woff2`, `assets/plugadmin.css`.
* **Service** : `active (running)` depuis 2026-09-09 07:01:48 UTC, `NRestarts=0`, drop-in
  `10-content-workspace.conf` chargé, `event=panel_started port=8090`. **0 ERROR / 0 Exception**
  au journal depuis le déploiement.
* **`/health`** : local + public → `{"panel":"ONLINE","disabled":false,…}`.
* **Redirections** (public, non authentifié) : `/` → `303 /home` ; `/home` → `303 /login`.
* **Assets publics** (aucun 404) :

  | Chemin | Code | Content-Type | Taille |
  |---|---|---|---|
  | `/assets/bootstrap/bootstrap.min.css` | 200 | `text/css` | 232 111 o |
  | `/assets/bootstrap/bootstrap.bundle.min.js` | 200 | `application/javascript` | 80 496 o |
  | `/assets/bootstrap-icons/bootstrap-icons.min.css` | 200 | `text/css` | 87 008 o |
  | `/assets/bootstrap-icons/fonts/bootstrap-icons.woff2` | 200 | `font/woff2` | 134 044 o |
  | `/assets/bootstrap-icons/fonts/bootstrap-icons.woff` | 200 | `font/woff` | 180 288 o |
  | `/assets/plugadmin.css` | 200 | `text/css` | 33 720 o |
  | `/assets/panel.js` | 200 | `application/javascript` | 10 719 o |

* **`/login` (public)** : lie **uniquement** `/assets/bootstrap/bootstrap.min.css`,
  `/assets/bootstrap-icons/bootstrap-icons.min.css`, `/assets/plugadmin.css` — **0** occurrence
  de `cdn` / `jsdelivr` / `unpkg`. **CSP identique** à l'avant-déploiement.
* **Cache / revalidation** : `/assets/plugadmin.css` → `ETag "0e6b18cba7167646"`,
  `Cache-Control: public, max-age=3600` ; `If-None-Match` → **304**.
* **Path traversal** : `/assets/../PanelApp.class` → `404` ; `/assets/..%2f..%2fetc%2fpasswd` →
  `400` (rejet nginx) — aucun contenu système renvoyé.
* **Autres vhosts AWS** : `dig.lodygames.com` → 200, `lodylands.com` → 200,
  `nginx` / `dig.service` / `lodyland.service` → `active`.

---

## Tests manuels à effectuer (PENDING MANUAL VALIDATION)

Nécessitent l'authentification owner (mot de passe non détenu par l'assistant) :

1. Se connecter → arriver sur `/home` : grandes tuiles groupées, icônes Bootstrap Icons visibles,
   thème PlugAdmin (pas de bleu/blanc Bootstrap), sidebar avec « Accueil » en tête.
2. Cliquer chaque tuile : Dashboard, Joueurs, PNJ, Quêtes, Stories, Dialogues, Documentation,
   Agents → la bonne page s'ouvre. Tuiles « à venir » : non cliquables.
3. Vérifier les badges synthétiques (après un « Rafraîchir le catalogue » sur `/players`,
   `/npcs`, etc., la Home affiche les comptes).
4. Rendu mobile : tuiles 1 colonne, topbar compacte, sidebar en tiroir, pas d'overflow
   horizontal, boutons/tuiles cliquables au doigt ; tablette : 2 colonnes ; desktop large :
   3–4 colonnes.
5. `/docs` (recherche, Markdown, Copier) et `/quests/new` / `/stories/new` : aucune régression
   visuelle ou fonctionnelle.
6. Onglet réseau : aucun 404 d'asset, aucune requête sortante vers un CDN.

---

## Résultat attendu

En ouvrant PlugAdmin : arrivée sur une page claire à grandes tuiles ; on identifie immédiatement
où voir le Dashboard, gérer Joueurs / PNJ / Quêtes / Stories / Dialogues, ouvrir la
Documentation, voir les Agents, aller aux Diagnostics. Bootstrap sert de base réutilisable pour
les prochaines évolutions ; le Dashboard reste une page dédiée (statut, mondes, santé, détails
techniques).

---

## Reset / retour à l'état initial

### Rollback applicatif

```
scripts/plugadmin/rollback.sh app
```
Restaure `/opt/plugadmin/releases/20260909-070146/` (jar précédent SHA-256 `8ed58f74…`,
**sans** Home ni Bootstrap — c'est la release #92 + #46 de 06:05). Restart + `/health`.

### Code

`git reset --hard b028b11` sur la branche (ou abandon de la branche). Aucune migration, aucun
état persistant, aucune clé de config ajoutée.

---

## Déploiement VeryGames

**Non applicable. VeryGames / Minecraft NON touché** — aucun changement plugin/agent, aucun
redémarrage, aucun `verygames-restart.sh`.

---

## Déploiement AWS (Control Panel uniquement)

* Effectué : `scripts/plugadmin/deploy.sh` (build `:control-panel:installDist` + sauvegarde
  `/opt/plugadmin/releases/20260909-070146` + swap + `systemctl restart plugadmin` + `/health`).
* Aucune modification de config serveur, de nginx, de TLS, ni des autres vhosts.
* Rollback : `scripts/plugadmin/rollback.sh app`.

---

## Rollback

Voir ci-dessus. Point de rollback : `/opt/plugadmin/releases/20260909-070146/` via
`scripts/plugadmin/rollback.sh app`.

---

## Logs / diagnostic

`journalctl -u plugadmin` depuis 07:01:40 : `panel_started port=8090`, heartbeats agent
`rpgquest-dev` OK, **0 ERROR / 0 Exception**. Le handler `/assets/` journalise chaque requête
(`event=request path=/assets/… status=200|304`).

---

## Documentation mise à jour

* `docs/control-panel/ROADMAP.md` — étape « socle Bootstrap + Home à tuiles » (livré) + reste
  (migration progressive des composants).
* `docs/control-panel/SECURITY.md` — section « Assets statiques & Bootstrap » (CSP inchangée,
  handler `/assets/` validé, assets publics sans donnée sensible).
* `docs/control-panel/VENDOR_ASSETS.md` — Bootstrap 5.3.8 / Bootstrap Icons 1.13.1, MIT, servis
  localement, procédure de mise à jour.
* `docs/current_state.md` — Home `/home` + Bootstrap local.

---

## Limitations / travail restant

* **Validation navigateur authentifiée** : PENDING (mot de passe owner non détenu).
* Le bloc CSS #92 est désormais dans `plugadmin.css` (servi) et **non plus inline** : une page
  rendue ne contient plus les tokens en clair dans son HTML (ils sont dans la feuille liée).
* Bootstrap est un socle **disponible** : les formulaires (#46), tableaux et la sidebar mobile
  n'ont **pas** été réécrits en composants Bootstrap (offcanvas, modales, `nav-pills`,
  `input-group`, tooltips) — migration progressive prévue, hors périmètre de cette tâche.
* La recherche globale de la Home est une **place réservée** (`input` désactivé) — aucun moteur.
* Jar : +~715 Ko (assets Bootstrap) → ~1,0 Mo. Acceptable pour un panel d'admin.
* Version Bootstrap Icons `.woff` (fallback) embarquée en plus du `.woff2` (tous les navigateurs
  cibles supportent woff2 — le `.woff` pourra être retiré plus tard).

---

## Prochaine étape suggérée

1. Validation navigateur authentifiée (liste « Tests manuels »), desktop + mobile.
2. Migrer progressivement vers les composants Bootstrap là où c'est utile : offcanvas pour la
   sidebar mobile, modale de confirmation pour les actions destructives, `nav-pills` pour les
   sous-navigations, `input-group` pour les barres de recherche.
3. Câbler la recherche globale de la Home quand un index transversal existera.
4. Finir #92 : refonte de contenu de la page Agents et des placeholders.
