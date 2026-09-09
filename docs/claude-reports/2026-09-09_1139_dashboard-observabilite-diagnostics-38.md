# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 11:39 → 12:03 (heure locale machine, UTC)
* Sujet : Dashboard d'observabilité / page `/diagnostics` — service central `DiagnosticsService` + providers, agrégation multi-domaines, page opérationnelle « que dois-je corriger maintenant ? », Home + Dashboard, refresh coordonné (issue #38)
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `6428f82` (code `b45939b`, docs `6428f82`) ; poussé sur `origin/feat/control-panel-admin-tools`
* Début de la tâche : 2026-09-09 11:39:20
* Fin de la tâche : 2026-09-09 12:03:45
* Durée totale : 00:24:25

## Demande

Rendre enfin utile la tuile/page **Diagnostics**. PlugAdmin doit répondre vite à « qu'est-ce
qui ne va pas actuellement sur RPGQuest ? » sans ouvrir PNJ / Dialogues / Quêtes / Stories un
par un. Page **d'aide opérationnelle** (pas des logs, pas une console dev) répondant, pour
chaque problème : 1. quel est le problème ? 2. où ? 3. conséquence ? 4. comment corriger ?
5. puis-je ouvrir la ressource ?

Points structurants de l'énoncé : audit des diagnostics réels avant modification (§2) ; modèle
structuré commun `DiagnosticEntry` (§3) ; sévérités ERROR/WARNING/INFO mappées proprement (§4) ;
domaines humains, seulement ceux réellement supportés (§5) ; header + cartes synthétiques (§6) ;
filtres gravité + domaine + recherche, **combinables** (§7) ; carte compacte humain-d'abord,
code technique secondaire (§8) ; actions contextuelles `[Ouvrir]` / `[Corriger maintenant]`
(action existante et sûre, **jamais** de correction automatique générique) (§9) ;
`[Comment corriger ?]` vers une **ancre précise** (§10) ; terminologie sans jargon (§11) ;
regroupement lisible si volume (§12) ; tri ERROR → WARNING → INFO (§13) ; résumé sur la Home
(§14) et le Dashboard (§15) ; **service central** qui agrège les producteurs existants, pas de
logique dupliquée (§16) ; fraîcheur si snapshot (§17) ; bouton refresh compact, **coordonné**,
pas 10 actions agent (§18) ; diagnostics serveur/agent réellement détectables (§19) ; mondes /
portails via validateurs réels (§20) ; items / références de contenu via les validateurs #46
(§21) ; architecture extensible `DiagnosticProvider` sans gros `switch` (§22) ; permission
`DIAGNOSTICS_READ`, quick actions re-vérifiées côté backend (§23) ; UX mobile (§24) ;
accessibilité (§25) ; état vide positif (§26) ; pas d'historique (hors MVP, §27) ; tests (§28) ;
déploiement Control Panel uniquement (§29) ; ne pas fermer #38, ne rien merger (§31).

## Analyse

### Audit des diagnostics réellement produits (§2)

* **PNJ** — `com.lodygames.rpgquest.npc.NpcCatalog` produit `Warning{code, severity, message}` ;
  codes confirmés dans le code : `DUPLICATE_DEFINITION`(error), `DUPLICATE_BINDING`(error),
  `BINDING_NO_DEFINITION`(error), `NO_DEFINITION`(error), `DIALOGUE_MISSING`(error),
  `DISABLED`(info), `NOT_LINKED`(info), `GIVER_NO_DIALOGUE`(info). Exposés par l'action agent
  `npc.list` (`AgentActionExecutor`), avec `citizensAvailable`, `definedIds`, `canonicalIds`.
  `CITIZENS_ORPHAN` / `UNDEFINED_REFERENCE` / `BROKEN` sont des **états** (`state`), pas des
  codes.
* **Dialogues** — `dialogue.DialogueCatalog` : `NEXT_MISSING`(error), `QUEST_REF_UNKNOWN`(warning),
  `NODE_UNREACHABLE`(info), `DIALOGUE_NO_NPC`(info), `MULTIPLE_NPCS`(info),
  `DEFINITION_DIALOGUE_DIVERGES`(warning) ; plus `loadIssues[]{file,message}` (fichiers rejetés)
  et `declaredButMissing[]{npcId,dialogueId}`. Exposés par `dialogue.list`.
* **Quêtes / Stories** — `quest.list` / `story.list` ne portent **aucun code moteur**. Les
  vérifications de référence (`QUEST_PREREQ_UNKNOWN`, `QUEST_GIVER_UNKNOWN`, `STORY_QUEST_UNKNOWN`)
  sont déjà calculées côté panel dans `AgentPages` (#89). Elles réutilisent
  `idKeySet` / `known` / `npcKeySet`.
* **Serveur / Agent** — `AgentStore.latestHeartbeat` + `AgentLiveness` (ONLINE / STALE / OFFLINE /
  UNKNOWN, seuils configurables). Heartbeat porte `serverState`, `pluginVersion`, `worldsJson`
  (rôles hub/claims/wild + `loaded`).
* **Items / Portails / Claims** — `item.list` n'a **aucun diagnostic** ; il n'existe pas d'action
  agent ni de validateur exposé pour portails / claims. → domaines **non alimentés** dans ce
  MVP (respect de « n'afficher que ce qui est réellement supporté »).
* **Wording centralisé** — `web.DiagnosticHelp` (registre #89/#49) : 22 codes déjà mappés vers
  titre humain / conséquence / action / ancre `/docs`.

### Décisions

* **Ne pas dupliquer la logique** (§16) : les diagnostics PNJ et dialogues viennent
  **directement** des `warnings[]` déjà calculés par le moteur et transportés par l'agent — les
  providers ne re-détectent rien, ils **mappent**. Les vérifications quête/story réutilisent le
  **wording `DiagnosticHelp`** et la normalisation d'identifiants **extraite** dans
  `panel.diag.RefKeys` (les deux méthodes privées de `AgentPages` la déléguent maintenant →
  résultats identiques, tests #89 inchangés).
* **`DiagnosticEntry.free(...)`** pour serveur/agent/mondes : leurs messages contiennent des
  valeurs dynamiques (âge du heartbeat, nom de monde, état) — un gabarit figé n'a pas de sens.
  Chaque entrée pointe vers une **ancre précise** de `serveur-depannage.md`.
* **Refresh coordonné** (§18) : `POST /diagnostics/refresh` (nouveau route) enqueue en une seule
  requête serveur les **4** relevés `*.list` dont dépendent les diagnostics, pas dix ; redirige
  avec `?toast=<id>` (pipeline #93).
* **Filtres combinables** (§7) : `panel.js#initFilters` ne gérait qu'**un** groupe de puces par
  scope. Étendu pour gérer **plusieurs `[data-filter-chips]` du même scope**, combinés en ET —
  **rétro-compatible** (un seul groupe = comportement historique, les autres pages inchangées).
* **`[Ouvrir]` / `[Corriger maintenant]`** = **liens profonds** `/<page>?agent=…&focus=<id>`
  (+ `&fix=create|edit|link`). Nouvel attribut `data-res-id` sur les 4 items d'accordéon +
  `panel.js#initFocus()` qui déplie l'élément ciblé (et le bon formulaire) + scroll +
  surbrillance. **Aucun endpoint de correction automatique** créé.

## Travail effectué

### Nouveau paquet `com.lodygames.rpgquest.panel.diag`

| Fichier | Rôle |
|---|---|
| `Severity` | ERROR / WARNING / INFO (rank de tri, libellé, classe alerte, icône, `filterKey`, `of(raw)`). |
| `Domain` | PNJ / Dialogues / Quêtes / Stories / Items / Mondes / Portails / Claims / Serveur / Agent / Autres (label, icône, `filterKey`, `pagePath`). |
| `DiagnosticEntry` | Modèle unique. Factory `of(code, …)` = wording depuis `DiagnosticHelp` ; `free(…)` = entrée libre. `searchBlob()`, `dedupeKey()`. |
| `DiagnosticContext` | Lecture seule du dernier snapshot agent (`details(type)`, `freshnessOf(type)`, `heartbeat()`) — aucune requête. |
| `DiagnosticProvider` | Interface : `domain()`, `collect(ctx)`. |
| `RefKeys` | `idKeySet` / `known` extraits d'`AgentPages` (partagés). |
| `NpcDiagnosticProvider` | `warnings[]` de `npc.list` → entries ; `CITIZENS_UNAVAILABLE` ; quick actions `create` / `edit` / `link`. |
| `DialogueDiagnosticProvider` | `warnings[]` + `loadIssues[]` (`DIALOGUE_LOAD_ISSUE`) + `declaredButMissing[]` (`DIALOGUE_DECLARED_MISSING`). |
| `QuestDiagnosticProvider` | `QUEST_PREREQ_UNKNOWN` (prérequis ∉ catalogue), `QUEST_GIVER_UNKNOWN` (giver ∉ `npc.list`). |
| `StoryDiagnosticProvider` | `STORY_QUEST_UNKNOWN` (quête de chaîne ∉ catalogue). |
| `ServerDiagnosticProvider` | `AGENT_NOT_CONFIGURED`, `AGENT_NO_HEARTBEAT`, `AGENT_OFFLINE`, `AGENT_HEARTBEAT_STALE`, `SERVER_NOT_ONLINE`, `PLUGIN_VERSION_UNKNOWN`, `WORLD_NOT_LOADED`. |
| `DiagnosticsService` | Agrège les providers, dédoublonne (`dedupeKey`), trie (gravité → domaine → ressource → code), compteurs, `domainsPresent`, `oldestSnapshot`, `anyDataLoaded`. `REFRESH_TYPES` = les 4 `*.list`. Un provider qui échoue → entry `PROVIDER_ERROR`, la page ne casse pas. |
| `DiagnosticsReport` | Résultat agrégé (entries triées, compteurs, domaines, fraîcheur). |

### `panel.web`

* **`DiagnosticsPages`** (nouveau) — rend `/diagnostics` : en-tête + fraîcheur + cartes
  synthétiques (`Ui.statCard` Erreurs/Avertissements/Infos/Total) + **état vide positif**
  (« Aucun problème détecté ») + barre de filtres (recherche `input-group` + groupe gravité +
  groupe domaine, tous `data-filter-*="diag"`) + liste. Carte = `alert` colorée +
  `pa-diag-h`/`pa-diag-what`/`pa-diag-why`/`pa-diag-do`/`pa-diag-btns`/`pa-diag-code` (composant
  `#89` réutilisé) + ligne `domaine · ressource`. **Regroupement** au-delà de 4 diagnostics
  identiques (code + domaine) : carte « N PNJ concernés » + `<details>` « Voir les N ».
* **`PanelApp`** — champ `diagnostics` (`new DiagnosticsService(agentStore, thresholds)`) ;
  route `/diagnostics` (`handleDiagnostics`, `DIAGNOSTICS_READ`, `actionFeedback` pour les toasts)
  et `/diagnostics/refresh` (`handleDiagnosticsRefresh`, POST + CSRF + permission, enqueue les 4
  relevés, audit `diagnostics.refresh`, redirect `?toast=`) ; `safeReturnPath` += `/diagnostics`
  ; `handleHome` passe une `HomePages.DiagSummary` ; `handleDashboard` ajoute
  `diagnosticsSummarySection` (compteurs + « Voir les diagnostics »).
* **`HomePages`** — tuile `diagnostics` **activée** (`enabled=true`) ; `DiagSummary` record ;
  `metaFor` : « N erreurs · N avertissements » ou « Tout est en ordre » (ou rien si aucun
  relevé).
* **`Layout`** — entrée sidebar Diagnostics `enabled=true`.
* **`AgentPages`** — `data-res-id="<id>"` ajouté aux 4 items d'accordéon (`renderNpc/Quest/Story/
  DialogueAccordionItem`) pour l'ouverture ciblée depuis `/diagnostics`.

### `panel.js`

* `initFilters()` : plusieurs groupes de puces `[data-filter-chips="<scope>"]` pour un même
  scope, combinés en ET (`active[]` par groupe). Le chip « tous » (`data-filter-chip=""`) remet
  son groupe à zéro. Rétro-compatible.
* `initFocus()` (nouveau) : lit `?focus=` / `?fix=` de l'URL, déplie
  `[data-res-id="<focus>"]` (`.accordion-collapse.show` + bouton dé-`collapsed`), déplie
  `.collapse[id$="-f-<fix>"]`, scroll + `.res-focused`.

### CSS (`plugadmin.css`)

Bloc `#38` : `.diag-fresh`, `.diag-summary`, `.diag-clean` (état positif), `.diag-controls` /
`.diag-search` / `.diag-chips`, `.diag-list` / `.diag-card` / `.diag-loc` / `.diag-dom` /
`.diag-res`, `.diag-group` / `.diag-group-*`, `@media(max-width:575.98px)` (recherche pleine
largeur, puces scrollables, boutons empilés). `.res-focused` (surbrillance de l'élément ouvert).

### Documentation `/docs` (#49)

* **`serveur-depannage.md`** (nouveau, whitelist `_index.txt`) — 7 sections au format §15, une
  par code serveur/agent/monde, titres alignés sur les ancres des `DiagnosticEntry`.
* **`pnj-depannage.md`** — section « Citizens inactif sur le serveur cible » (`CITIZENS_UNAVAILABLE`).

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/diag/` — `Severity`, `Domain`,
  `DiagnosticEntry`, `DiagnosticContext`, `DiagnosticProvider`, `RefKeys`,
  `NpcDiagnosticProvider`, `DialogueDiagnosticProvider`, `QuestDiagnosticProvider`,
  `StoryDiagnosticProvider`, `ServerDiagnosticProvider`, `DiagnosticsReport`, `DiagnosticsService`.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/DiagnosticsPages.java`
* `control-panel/src/main/resources/docs/serveur-depannage.md`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/diag/DiagnosticsServiceTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/DiagnosticsPageTest.java`
* `docs/claude-reports/2026-09-09_1139_dashboard-observabilite-diagnostics-38.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/java/.../web/PanelApp.java` — routes `/diagnostics` + refresh, service,
  Home/Dashboard.
* `control-panel/src/main/java/.../web/HomePages.java` — tuile Diagnostics active + compteurs.
* `control-panel/src/main/java/.../web/Layout.java` — sidebar Diagnostics active.
* `control-panel/src/main/java/.../web/AgentPages.java` — `data-res-id` sur les 4 accordéons.
* `control-panel/src/main/resources/assets/panel.js` — filtres multi-groupes + `initFocus()`.
* `control-panel/src/main/resources/assets/plugadmin.css` — styles page diagnostics.
* `control-panel/src/main/resources/docs/_index.txt` — `serveur-depannage.md`.
* `control-panel/src/main/resources/docs/pnj-depannage.md` — section Citizens.
* `control-panel/src/test/java/.../web/HomeLauncherTest.java` — tuile Diagnostics = lien actif.
* `docs/control-panel/ROADMAP.md` (étape 3g), `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md`,
  `docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations

Aucune.

## Configuration / données

Aucune. Aucun nouveau paramètre. Permission `DIAGNOSTICS_READ` (déjà présente, accordée à
OWNER / TESTER / CONTENT_EDITOR / READ_ONLY). `serveur-depannage.md` embarquée dans le jar.

## Tests automatiques

* `./gradlew :control-panel:test` — **235 tests, 0 échec** (219 + `DiagnosticsServiceTest` (7)
  + `DiagnosticsPageTest` (9), `HomeLauncherTest` ajusté).
* `./gradlew test` — inchangé. `./gradlew build` — **SUCCESS**.

`DiagnosticsServiceTest` : agrégation des 4 domaines en un modèle trié ; un ERROR + un WARNING
+ un INFO ; tri par gravité ; codes couverts ; humain-d'abord + code secondaire + jargon absent
+ `observedAt` ; heartbeat manquant → `AGENT_NO_HEARTBEAT` ERROR ; monde non chargé →
`WORLD_NOT_LOADED` ; dédoublonnage ; aucun agent → `AGENT_NOT_CONFIGURED` + `anyDataLoaded`
false ; **chaque ancre doc d'un diagnostic existe dans une fiche whitelistée**.

`DiagnosticsPageTest` : anon → 303 ; état vide positif ; cartes humain-d'abord + `Conséquence` /
`À faire` + domaine + ressource + `Code technique : <code>` ; `[Ouvrir]` href + quick action
`Créer la définition` + `[Comment corriger ?]` → `/docs/pnj-depannage#fiche-rpgquest-manquante` ;
tri ERROR avant INFO ; recherche `input-group` + **2 groupes de puces** `data-filter-chips="diag"`
+ `data-filter-cat` combinée + panel.js multi-groupes ; regroupement au-delà de 4 ; refresh →
303 `&toast=` + **exactement 4** actions enqueue ; refresh sans CSRF → 403 + 0 action ; tuile
Home compteurs + Dashboard « État du contenu » ; sidebar active.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (mot de passe owner non détenu) — navigateur authentifié sur
`https://plugadmin.lodylands.com` :

1. `/home` : tuile Diagnostics **active**, compteur visible.
2. `/diagnostics` : header, cartes synthétiques, liste triée (erreurs d'abord).
3. Filtre **Erreurs** → seules les erreurs.
4. Filtre **PNJ** (combiné à la gravité) → seules les cartes PNJ de cette gravité.
5. Recherche « guide » → filtre sur titre / message / ressource / code.
6. Ouvrir un diagnostic PNJ → **[Ouvrir]** amène sur `/npcs` avec le bon PNJ **déplié**.
7. **[Corriger maintenant]** (`BINDING_NO_DEFINITION`) → `/npcs` avec le formulaire « Créer la
   définition » **ouvert**.
8. **[Comment corriger ?]** → la bonne **ancre** de la fiche `/docs`.
9. **[ ↻ Actualiser les diagnostics ]** → toast, puis les compteurs se mettent à jour au retour
   des relevés.
10. `/dashboard` : section « État du contenu » + bouton « Voir les diagnostics ».
11. Mobile : cartes pleine largeur, filtres qui s'enroulent, boutons empilés, pas de table.

## Résultat attendu

`/diagnostics` répond à « que dois-je corriger maintenant ? » — humain → conséquence → action →
documentation → technique en dernier.

## Reset / retour à l'état initial

* Code : `git revert 6428f82 b45939b`. Aucun état persistant (la page est en lecture seule ;
  le refresh crée des actions agent `*.list` inoffensives).
* Déploiement : `scripts/plugadmin/rollback.sh app` (release `20260909-120245`).

## Déploiement VeryGames

### À transférer
Rien. **Aucun changement plugin / agent / monde / donnée Minecraft.**

### Ne PAS transférer/altérer
Le serveur VeryGames. Ne pas redémarrer Minecraft.

### Redémarrage requis
Uniquement le service `plugadmin` sur AWS (fait par `deploy.sh`).

### Migration automatique
Aucune.

## Déploiement AWS (Control Panel) — effectué

* `scripts/plugadmin/deploy.sh` — release `/opt/plugadmin/releases/20260909-120245`,
  `systemctl restart plugadmin`, health local **ONLINE**.
* Vérifications :
  * `https://plugadmin.lodylands.com/health` → `{"panel":"ONLINE"}` ;
  * `/home` `/dashboard` `/diagnostics` `/npcs` `/dialogues` `/quests` `/stories` `/docs`
    `/docs/serveur-depannage` anonymes → **303** ; `POST /diagnostics/refresh` anon → **303** ;
  * En-tête **CSP inchangé** ;
  * `dig.lodygames.com` → **200**, `lodylands.com` → **200** ;
  * `plugadmin.service` `active`, `NRestarts=0` ; **aucun `ERROR`** au journal ;
  * jar déployé **byte-identique** au build (`SHA-256 c5897313…`) ; 17 classes `panel/diag/`
    + `serveur-depannage.md` embarqués.

## Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration.

## Logs / diagnostic

`journalctl -u plugadmin -f` — démarrage propre (`PanelApp start`, health `ONLINE`), aucun
`ERROR`. `event=diagnostics_refresh rid=… enqueued=4` à chaque refresh.

## Documentation mise à jour

* `docs/control-panel/ROADMAP.md` (étape 3g LIVRÉ), `docs/current_state.md`,
  `docs/RPGQUEST_BIBLE.md`, `docs/deployment/SERVER_CHANGELOG.md`.
* `/docs` : `serveur-depannage.md` (nouveau) + section Citizens dans `pnj-depannage.md`.

## Limitations / travail restant

* Validation navigateur authentifiée : `PENDING MANUAL VALIDATION` (11 points ci-dessus).
* **Domaines non alimentés** (aucune source de données côté panel aujourd'hui) : Items,
  Portails, Claims. À ajouter dès qu'une action agent ou un validateur exposé existe — un
  `DiagnosticProvider` suffira (architecture prévue pour).
* Les vérifications de contenu #46 (`custom item inconnu`, `objectif material/entity invalide`,
  `reward item invalide`) ne sont pas encore branchées : `quest.list` / `story.list` ne
  transportent pas ces informations. Idem « quête non chargée » / « story non chargée »
  (`QUEST_LOAD_ISSUE` / `STORY_LOAD_ISSUE` sont dans `DiagnosticHelp` + fiches, pas encore émis).
* Historique des diagnostics : **hors périmètre** (§27) — la page montre l'état courant.
* Le refresh crée 4 actions agent `*.list` ; si l'agent est hors ligne, elles restent PENDING
  et expirent normalement (aucun effet de bord).

## Prochaine étape suggérée

* Faire la validation navigateur authentifiée.
* Brancher les validateurs de contenu #46 dès que le protocole `quest.list` / `story.list`
  expose les references d'items / matériaux (ou via une action `*.validate` dédiée).
* Ajouter les providers Items / Portails quand une source existe.
