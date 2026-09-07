# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 21:59 (heure locale du serveur AWS)
* Sujet : Outillage admin du Control Panel — rafraîchissement auto des actions (issue #65) + pages Joueurs / Quêtes / Stories + actions métier whitelistées (P0)
* Statut : PARTIAL (P0 livré et vert ; P1 « PNJ / Diagnostics / autocomplete #66 » et P2 « édition de dialogues » **non traités**, délibérément)
* Branche Git : `feat/control-panel-admin-tools` (créée depuis `origin/main` = `f1e4a39`)
* Commit actuel si disponible : `594d865` (+ commits de doc à suivre)
* Début de la tâche : 2026-09-07 21:31:06
* Fin de la tâche : 2026-09-07 22:20:00 (approx., voir commits)
* Durée totale : ~00:49:00

## Demande

Faire évoluer le RPGQuest Control Panel (« PlugAdmin ») pour qu'il devienne un vrai outil de
développement/test/admin du plugin, utilisable depuis navigateur et téléphone, en réutilisant les
services et commandes admin RPGQuest existants. Priorités :

* **P0** — (1) corriger le rafraîchissement des actions (besoin #65) ; (2) page **Joueurs** (roster,
  sélection, variables, reset preview/confirm) ; (3) **GIVE** d'objets structuré ; (4) page
  **Quêtes** (catalogue lisible, état joueur, start/complete/reset) ; (5) page **Stories** (catalogue,
  état, advance/complete) ; (10) sécurité / whitelist stricte à toutes les couches ; (11) tests.
* **P1** — PNJ, validation/autocomplete des IDs (#66), Diagnostics.
* **P2** — édition sûre des textes de dialogues.

Contraintes explicites : ne pas toucher #42 (SQLite→MariaDB), ne pas déployer, ne pas redémarrer la
prod, aucune console distante arbitraire, toutes les actions whitelistées et structurées.

## Analyse

### Existant audité

* **Control Panel** (`control-panel/`, module Gradle autonome, issues #37/#44/#51) : serveur
  `com.sun.net.httpserver`, rendu HTML côté serveur (pas de SPA), auth owner PBKDF2 + session
  signée + CSRF, audit append-only `control-panel.db`, CSP stricte
  (`default-src 'self'; style-src … 'unsafe-inline'` — **script inline interdit**).
* **Canal agent** (issue #51) : le plugin (`web.agent`) ouvre une connexion HTTPS **sortante** vers
  PlugAdmin (heartbeat + `GET /agent/v1/actions` + `POST …/result`). `AgentEndpoints` /
  `AgentStore` (`agent_heartbeat`, `agent_action`, idempotence, expiration) côté panel.
  `AgentActionExecutor` / `AgentActionType` : **une seule** action whitelistée
  (`player.variable.get`, lecture) adossée à un service métier. Page `/agents` : formulaire de
  preuve + tableau « Actions récentes » **sans rafraîchissement** (il fallait recharger la page).
* **Services métier réutilisables** : `QuestProgressEngine` (`accept(…, ignorePrerequisites)`,
  `forceComplete`, `resetQuest`, `allStates`, `activeStepView`), `StoryService` (`adminAdvance`,
  `adminComplete`, `info`, `stories`), `YamlQuestEngine` (`quests`, `find`), `StoryRegistry`,
  `YamlCustomItemRegistry` (`items`, `find`, `create(id, amount)`), `PlayerResetService`
  (`previewReset` = dry-run, `resetToNewPlayer`), `PlayerVariableRepository` (`get`, `set`).
  `RpgAdminCommand` fait déjà exactement ces appels pour `/rpgadmin quest|story|player …` : le
  panel devait faire **les mêmes appels**, pas dupliquer la logique.

### Décisions d'architecture

1. **Réutiliser le canal agent existant**, pas le bridge #37. Le bridge est en lecture seule
   (`/admin/v1/health`) et le flux entrant est fermé ; la file d'actions de #51 est déjà le bon
   véhicule structuré, idempotent et audité.
2. **Façade métier unique côté plugin** : `AgentActions` (types simples, aucun type Bukkit) +
   impl. `BukkitAgentActions`. L'exécuteur reste testable sans serveur ; l'ajout d'une action
   future = 1 valeur d'enum + 1 méthode de façade + 1 branche d'exécuteur.
3. **Mutations sur le thread principal** : l'agent poll depuis un thread async ; `BukkitAgentActions`
   fait le `runTask` puis relaie le futur — même patron que `RpgAdminCommand`.
4. **Double liste blanche** : `AgentActionType` (plugin) ↔ `AgentActionCatalog` (panel). Le panel
   valide permission + bornes des paramètres **avant** de créer l'action ; l'agent revalide ;
   le service métier revalide. Aucune « console libre ».
5. **Rafraîchissement auto (#65) sans script inline** (CSP) : fichier statique **même origine**
   `/assets/panel.js` + endpoint JSON `/agents/actions.json`. Polling **uniquement** s'il reste une
   action `PENDING`/`DELIVERED`, arrêt à `pending == 0`, garde-fou 5 min.
6. **Confirmation** obligatoire pour toute mutation (case à cocher `required` côté formulaire +
   `confirm=true` exigé par `AgentActionCatalog`) ; l'agent exige en plus `confirm=true` pour
   `player.resetnew.confirm`.
7. **P1/P2 reportés** : la consigne « termine P0 proprement avant P1/P2, ne sacrifie pas les tests
   ou la sécurité » prime. P0 est livré, testé et vert ; P1/P2 sont documentés comme non faits.

## Travail effectué

### 1. Rafraîchissement automatique des actions (issue #65) — commit `0eee145`

* `control-panel/src/main/resources/assets/panel.js` (nouveau) : repère les blocs
  `[data-actions-agent]`, interroge `/agents/actions.json?agent=<id>` toutes les 2 s, reconstruit
  le `<tbody>` du tableau, s'arrête dès `pending == 0` ; garde-fou 150 relevés (5 min) ; aucune
  dépendance externe.
* `PanelApp` : routes `GET /agents/actions.json` (session + `DIAGNOSTICS_READ` ; agent inconnu →
  404 ; JSON compact `{agent, pending, actions[]}`) et `GET /assets/panel.js`
  (`application/javascript`, même origine → conforme CSP). Tableau « Actions récentes » passé en
  `<thead>/<tbody>` + `data-actions-pending` + ligne `.poll-status` + `<script src=…>`.
* Après un `POST` d'action (redirection 303 → rechargement), la nouvelle action apparaît
  immédiatement en `PENDING` puis le script prend le relais.

### 2. Actions métier whitelistées côté plugin — commit `e1ddb8c`

* `web/agent/AgentActions.java` (nouveau) : façade métier, DTO en types simples.
* `web/agent/BukkitAgentActions.java` (nouveau) : impl. réelle, délègue aux services listés dans
  « Analyse », mutations sur le thread principal (`onMain`).
* `web/agent/PlayerVariableWriter.java` (nouveau) : `set(uuid, key, value)` pour
  `player.variable.set`.
* `web/agent/AgentActionType.java` : +15 types (8 lectures, 7 mutations).
* `web/agent/AgentActionExecutor.java` : constructeur `(PlayerDirectory, PlayerVariables,
  AgentActions)`, branches par type, validation stricte des paramètres (patterns bornés
  quête/story/variable, quantité 1–64), résolution du joueur via `PlayerDirectory`,
  `player.resetnew.confirm` exige `confirm=true`. Toute précondition métier non remplie (hors
  ligne, id inconnu, déjà terminé) → `FAILED` lisible ; aucune exception ne remonte.
* `bootstrap/RPGQuestBootstrap` : l'agent est démarré **après** `storyService` et
  `playerResetService` (dépendances des actions) — la ligne `registry.start(new PlugAdminAgent(…))`
  a été déplacée dans `start()` sans changer les autres services.

### 3. Pages Joueurs / Quêtes / Stories côté panel — commit `594d865`

* `panel/agent/AgentActionCatalog.java` (nouveau) : liste blanche miroir, `Spec` (type,
  permission, mutation ?, besoin joueur ?, libellé), `validate(type, form)` → `params` normalisés
  ou `error` ; suggestions `KNOWN_VARIABLE_KEYS` (`CLAIM_TIER_1`, `tutorial_started`,
  `crystal_hunt_started`, `RUNE_RAPPEL_GRANTED`).
* `panel/web/AgentPages.java` (nouveau) : rendu des 3 pages. Toujours **nom lisible d'abord, id
  technique en second**. Réaffiche le résultat de la dernière action réussie de chaque type via
  `AgentStore.latestActionOfType` (parse `details` du `result_json`). Tableau d'actions pollable
  réutilisé sur chaque page.
* `panel/web/PanelApp.java` : routes `/players`, `/quests`, `/stories` (plus des placeholders) ;
  `POST /agents/action` générique (CSRF, session, permission par type, whitelist, agent connu,
  `return` restreint à `/players|/quests|/stories|/agents`, audit `PENDING`/`DENIED`, la valeur
  d'une `variable.set` n'est jamais recopiée dans l'audit). L'ancien `POST /agents` passe
  maintenant par la même validation (type par défaut `player.variable.get`).
* `panel/agent/AgentStore.java` : `latestActionOfType(agentId, type)`.
* `panel/agent/AgentEndpoints.java` : borne du `result_json` conservé portée de 4 000 à 20 000
  caractères (listes de catalogue non secrètes, base **propre** au panel).
* `panel/authz/Permission.java` : +`ACTION_ITEM_GIVE` (`Role.OWNER` = `allOf`, inchangé).
* `panel/web/Layout.java` : nav « Joueurs / Quêtes / Stories » activée ; CSS responsive
  (formulaires d'action, badges d'état, confirmations, `[hidden]`).

## Fichiers créés

Plugin :
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/PlayerVariableWriter.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java`

Control Panel :
* `control-panel/src/main/resources/assets/panel.js`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/AgentActionsRefreshTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalogTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/BusinessPagesTest.java`

Docs :
* `docs/claude-reports/2026-09-07_2159_control-panel-admin-tools.md` (ce fichier)

## Fichiers modifiés

Plugin :
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
* `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/AgentLoopTest.java`

Control Panel :
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PanelApp.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Layout.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentStore.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentEndpoints.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/authz/Permission.java`

Docs :
* `docs/current_state.md`, `docs/control-panel/AGENT.md`, `docs/control-panel/ROADMAP.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md` (index)

## Base de données / migrations

* **Aucune migration de schéma** côté plugin (`data.db` intact, version 17).
* Côté panel (`control-panel.db`) : aucune migration ; les tables `agent_*` existantes suffisent.
  Le champ `result_json` de `agent_action` accepte désormais jusqu'à 20 000 caractères (borne
  applicative, pas de DDL).

## Configuration / données

* Aucune nouvelle clé de configuration.
* L'agent reste **inerte** sans `plugins/RPGQuest/plugadmin-agent.properties` (mécanisme #51
  inchangé). Avec l'agent actif, **aucune** action ne s'exécute tant que le panel n'en crée pas
  une.

## Tests automatiques

Commandes exécutées sur AWS :

* `./gradlew :control-panel:test` — **vert** (dont `AgentActionsRefreshTest`,
  `AgentActionCatalogTest`, `BusinessPagesTest` + suites existantes).
* `./gradlew :control-panel:build` — **vert**.
* `./gradlew :test` (plugin, suite complète) — **vert** (dont `web.agent.*` étendu).
* `./gradlew :build` (plugin, suite complète) — **vert**.

Couverture ajoutée :

* **Panel** — endpoint JSON reflète le cycle `PENDING → SUCCESS` sans rechargement ; script servi
  en `application/javascript` même origine ; endpoint protégé (session + agent connu) ; pages
  métier protégées par session ; formulaires whitelistés rendus ; `POST /agents/action` :
  création d'une lecture, **refus d'une mutation sans confirmation** (aucune action créée),
  création avec confirmation, **refus d'un type inconnu** (`console.run`), CSRF exigé, session
  exigée, `return` restreint (pas de redirection ouverte).
* **Agent** — whitelist stricte (type inconnu / `console.run` → `REJECTED`) ; validation des
  paramètres (quantité GIVE `0`/`999` → `REJECTED`, `item_id`/`quest_id`/`story_id` manquants ou
  invalides → `REJECTED`) ; délégation correcte (`force`, `confirm`, id transmis) ; rejet métier
  (`ALREADY_COMPLETED`) → `FAILED` lisible ; `player.resetnew.confirm` sans `confirm=true` →
  `REJECTED` et service jamais appelé.
* **Catalogue** — permissions mappées par type, bornes, normalisation `story_id`, confirmation.

## Tests manuels à effectuer (PENDING MANUAL VALIDATION)

Nécessitent un vrai serveur RPGQuest + un PlugAdmin joignant l'agent (non exécutés — pas de
déploiement dans cette session) :

1. Panel → **Joueurs** → « Rafraîchir la liste » : l'action passe `PENDING → SUCCESS` **sans
   recharger la page** et le roster (nom, UUID court, monde, position) s'affiche.
2. Sélectionner un joueur en ligne → **Lire** `CLAIM_TIER_1` → valeur affichée ; **Écrire**
   (debug, case cochée) `CLAIM_TIER_1 = true` → relire pour vérifier.
3. **Donner un objet** : « Rafraîchir la liste des objets », choisir p. ex. la Rune de rappel,
   quantité 1, cocher la confirmation → message « 1x rpgquest:rune_rappel donné à <joueur> » et
   objet présent en jeu ; quantité 0 ou 999 refusée côté formulaire.
4. **Aperçu reset** : lignes par catégorie affichées, **rien** n'est modifié en jeu ; puis
   `Confirmer le reset` (case cochée) sur un compte de test → état RPGQuest remis à « jamais joué ».
5. **Quêtes** → « Rafraîchir le catalogue » : titres lisibles + étapes/objectifs + récompenses ;
   sélectionner un joueur → « Rafraîchir l'état » → `NOT_STARTED/ACTIVE/COMPLETED` + étape +
   objectifs ; `Démarrer` (option « ignorer les prérequis »), `Compléter` (case cochée) →
   récompenses appliquées **une fois** ; recompléter → « déjà terminée, aucune récompense
   re-créditée » ; `Réinitialiser` → quête rejouable, message rappelant que les récompenses déjà
   données ne sont pas retirées.
6. **Stories** → catalogue ordonné + état joueur ; `Avancer d'une étape` puis `Compléter toute la
   story` (cases cochées) → suivre l'application de `CLAIM_TIER_1` et l'accès au monde `claims`.
7. Tenter un type d'action non prévu (`console.run`, `rcon`…) : impossible depuis l'UI ;
   un `POST` forgé est refusé (`DENIED` dans l'audit) et rien n'est exécuté.
8. Mobile : les pages restent lisibles et utilisables (formulaires, tableaux à défilement).

## Résultat attendu

Depuis un navigateur ou un téléphone, sans être OP, sans console VeryGames, sans connaître les IDs
par cœur, sans toucher SQLite : sélectionner un joueur, voir sa progression quêtes/stories, lire
`CLAIM_TIER_1`, lui donner un objet, démarrer/terminer/réinitialiser une quête, avancer/compléter
une story, prévisualiser puis (avec confirmation) exécuter un reset « nouveau joueur », et voir
immédiatement le succès/échec de chaque action.

## Reset / retour à l'état initial

* Purement additif. Revenir en arrière = `git checkout main` ou supprimer la branche
  `feat/control-panel-admin-tools`.
* Aucune donnée de production modifiée par cette session. Les actions du panel n'ont d'effet que
  si un opérateur les déclenche explicitement sur un serveur où l'agent est actif.

## Déploiement VeryGames

### À transférer

* Nouveau JAR RPGQuest (build de `feat/control-panel-admin-tools`).
* Redéploiement de l'app PlugAdmin sur AWS (`scripts/plugadmin/deploy.sh`) pour les pages et la
  liste blanche.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, autres plugins,
`plugadmin-agent.properties` existant.

### Redémarrage requis

Oui (remplacement de JAR). Côté AWS : `systemctl restart plugadmin` via `deploy.sh`.

### Migration automatique

Aucune.

## Rollback

* Panel : redéployer la version précédente de l'app (`scripts/plugadmin/rollback.sh app`).
* Plugin : `scripts/rollback-verygames.sh --latest`, redémarrer. Ou simplement `enabled=false`
  dans `plugadmin-agent.properties` → agent inerte, comportement identique à avant #51.
* Aucune migration à défaire.

## Logs / diagnostic

* Panel : `event=agent_action_created … type=<type>` à la création ; audit `agent.action.create`
  (`PENDING`/`DENIED`).
* Agent : `Agent PlugAdmin : action <id> exécutée → <SUCCESS|FAILED|REJECTED> envoyé.`
* Une action bloquée en `PENDING` = agent qui ne relève pas (voir `docs/control-panel/AGENT.md`
  §11).

## Documentation mise à jour

* `docs/control-panel/AGENT.md` §7 — tableau complet des actions whitelistées (lectures /
  mutations, service appelé, effet, validation 3 couches).
* `docs/current_state.md` — bullet « Outillage admin du Control Panel ».
* `docs/control-panel/ROADMAP.md` — Étape 1 (Joueurs/Quêtes/Stories = livrés ; PNJ/Diagnostics =
  P1 non livré) et Étape 2 (actions sûres = livrées via l'agent).
* `docs/deployment/SERVER_CHANGELOG.md` — entrée « Actions métier whitelistées pour l'outillage
  Control Panel » (2026-09-07).
* `docs/claude-reports/README.md` — ligne d'index.

## Limitations / travail restant

* **P1 non traité** :
  * **PNJ** — page bindings Citizens/RPGQuest, `npc info|tag|untag`, et surtout la
    validation/autocomplete des IDs canoniques (#66 : refuser un id inconnu, suggérer un id
    proche — « garde » → « guard »). Nécessite l'API `NpcIdentityService` + appels Citizens sur le
    thread principal.
  * **Diagnostics** — agrégat état DB engine / version de schéma / quêtes chargées / dialogues
    chargés + fichiers fautifs / bindings PNJ / mondes / claims. Faisable proprement en une action
    de lecture agrégée, non fait faute de temps de session.
* **P2 non traité** : **édition des textes de dialogues**. À faire avec l'audit préalable demandé
  (où sont les dialogues canoniques : JAR vs `plugins/RPGQuest/dialogues`, mécanisme de reload,
  divergence runtime/Git) **avant** toute écriture. À traiter comme une tâche à part.
* **`quest.reset` / `player.variable.set`** fonctionnent hors ligne (clé UUID) ; toutes les autres
  mutations exigent une cible **en ligne** — l'échec est explicite (`OFFLINE`).
* Le réaffichage du résultat d'une action de liste/état se fie à la **dernière** action de ce type
  pour l'agent (pas de filtre SQL par joueur) : suffisant pour un opérateur unique, à raffiner si
  usage multi-opérateur simultané.
* `result_json` reste borné (20 000 car.) : un catalogue de quêtes très volumineux pourrait être
  tronqué — acceptable pour la taille actuelle du contenu RPGQuest.
* Aucune validation manuelle en jeu effectuée (pas de déploiement).

## Prochaine étape suggérée

1. Valider P0 manuellement en DEV (checklist ci-dessus) après un déploiement JAR + redéploiement
   PlugAdmin.
2. P1 : action de lecture `diagnostics` agrégée + page Diagnostics ; puis page PNJ + `npc.*` avec
   la validation d'ID de #66 (dropdown + refus + suggestion par distance d'édition).
3. P2 : audit des dialogues puis édition limitée (speaker / text / choice.text) avec sauvegarde
   versionnée et validation du modèle avant écriture.
