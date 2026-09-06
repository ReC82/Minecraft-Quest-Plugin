# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-06
* Heure : 20:28 (heure locale réelle de la machine)
* Sujet : Issue #36 — commandes admin pour accélérer les tests de quêtes et de stories
  (`/rpgadmin quest start|complete|reset`, `/rpgadmin story advance|complete`,
  `/rpgadmin player variable get|set`).
* Statut : DONE (validation manuelle Minecraft restante — voir « Tests manuels à effectuer » ;
  issue #36 **non fermée**).
* Branche Git : `feat/36-admin-test-shortcuts` (créée depuis `deploy/issue-21-claims-journey` @ `b83424d`).
* Commit(s) : voir « Commit(s) ».
* Début de la tâche : 2026-09-06 20:28:15
* Fin de la tâche : 2026-09-06 21:33:00
* Durée totale : 01:04:45

## Demande

Lire l'issue #36 en entier, auditer les commandes/services existants, puis implémenter un petit
jeu de commandes admin/DEV réutilisant **les services métier existants** (jamais d'écriture directe
dans `data.db`) pour :

- démarrer / compléter / réinitialiser une quête pour un joueur ;
- faire avancer une story d'une étape (**priorité**) et compléter une story entière ;
- lire (et, si propre, écrire) une variable joueur de test ;

avec permissions adaptées, tab-complétion, journalisation des opérations sensibles, messages
avant/après explicites, non-duplication des récompenses, et une limite clairement documentée pour
le reset ciblé. Puis tests automatisés, `./gradlew test` + `build` verts, documentation, rapport,
branche dédiée, pas de fusion, pas de fermeture d'issue, et — si sûr — déploiement DEV du JAR avec
backup.

## Analyse — audit de l'existant

### Commandes / services déjà en place (réutilisés tels quels)

- **`command.QuestCommand`** exposait déjà, pour le **joueur exécutant lui-même** :
  `/quest accept` → `QuestProgressEngine.accept(Player, questId)` ;
  `/quest complete` → `QuestProgressEngine.forceComplete(Player, questId)` ;
  `/quest admin reset <joueur> <id|all>` → `QuestProgressEngine.resetQuest(uuid, questId)` /
  `resetAllQuests`.
- **`quest.progress.QuestProgressEngine`** :
  - `accept` — respecte les prérequis (renvoie `MISSING_PREREQUISITES`), refuse une quête déjà
    active / non répétable déjà terminée. **Aucun paramètre pour ignorer les prérequis.**
  - `forceComplete` — **déjà idempotent** : si la quête est déjà `COMPLETED` (cache **ou** base),
    renvoie `ALREADY_COMPLETED` sans rien re-créditer. Sinon `turnIn` (garde mémoire synchrone
    anti double-remise) → `grantRewards` applique `EXPERIENCE` / `ITEM` / `VARIABLE`
    (`variableRepository.set`, ex. `CLAIM_TIER_1`) / `COMMAND`.
  - `resetQuest(uuid, questId)` — supprime la ligne `quest_progress` + les compteurs d'objectifs
    + la progression mémoire. **N'annule aucune récompense déjà accordée** (XP, objets, variables,
    effets de commande).
- **`story.StoryService`** :
  - `start(uuid, name, storyId)` — persiste `story_progress` `ACTIVE` index 0, crée le profil au
    besoin ; accepte la 1re quête si le joueur est en ligne.
  - Progression **automatique** : une story `ACTIVE` avance seule au fil des complétions de quêtes
    (`onQuestProgressChanged` → `evaluateStory` → `advanceStory`), avec une garde
    « `progress` pointe toujours vers la quête tout juste complétée » qui rend l'avance idempotente.
  - `reset` / `resetWithQuests` — remise à zéro ciblée (story seule, ou story + ses quêtes).
  - **Aucune commande pour « avancer d'une étape » à la demande.** À noter : pour un joueur
    normal, `main_story` n'est **jamais démarrée automatiquement** — la chaîne se joue via les PNJ.
- **`admin.RpgAdminCommand`** — permission unique `rpgquest.admin.world`. Les branches `story`,
  `player`, `guide` ciblent un joueur passé en argument et tournent **depuis la console** (via
  `resolveTargetPlayer`, online **ou** offline). Tab-complétion par sous-commande + joueurs en
  ligne + ids.
- **`database.PlayerVariableRepository`** — `get(uuid, key)` → `Optional<String>`,
  `set(uuid, key, value)` (UPSERT), `findAllForPlayer`, `deleteAllForPlayer`. **Pas** de
  suppression d'une seule clé (mais `set … false` suffit pour neutraliser `CLAIM_TIER_1`, cf.
  `ClaimService.hasClaimTierOne` qui teste `== "true"`).

### Conclusion de l'audit

Presque tout le socle existe déjà. Le travail consiste à : (1) exposer des **variantes
ciblant `<joueur>`** de `accept`/`forceComplete`/`resetQuest` sous `/rpgadmin quest` ; (2) ajouter
un `accept` avec bypass explicite des prérequis ; (3) écrire l'orchestration `story advance` /
`story complete` (déterministe, sans dépendre du timing de l'event-bus) ; (4) exposer
`variable get`/`set`. Rien ne nécessite d'écrire en base hors des repositories existants.

## Travail effectué

### 1. `QuestProgressEngine.accept(Player, questId, boolean ignorePrerequisites)`

Surcharge : `ignorePrerequisites = true` saute **uniquement** la vérification des prérequis (la
garde anti-doublon « déjà active » et le blocage « non répétable déjà terminée » restent). La
signature à 2 arguments délègue avec `false` — comportement inchangé pour dialogues, moteur de
Story et commande joueur.

### 2. `StoryService.adminAdvance(Player, storyId)` et `adminComplete(Player, storyId)`

`adminAdvance` fait progresser la story d'**exactement une étape**, dans un ordre déterministe
(ne dépend pas du listener réactif) :

1. story `NOT_STARTED` → profil + ligne `story_progress ACTIVE` index 0 persistés (sans passer par
   `start()`, pour éviter la course avec l'auto-`accept` de la quête 0 qu'on va de toute façon
   `forceComplete`) ;
2. sur le thread principal : `QuestProgressEngine.forceComplete(player, quêteCourante)` — garde
   anti double-récompense de `forceComplete` conservée ;
3. l'objet `ActiveStoryProgress` **partagé** est avancé d'un cran de façon synchrone : la garde du
   chemin réactif (`advanceStory`) voit alors que `progress` ne pointe plus vers la quête
   complétée et **s'annule d'elle-même** — pas de double avance ;
4. story terminée (dernière étape) → `story_progress COMPLETED` ; sinon la quête suivante est
   acceptée (`QuestProgressEngine.accept`, idempotent) et le `CompletableFuture` **ne se résout
   qu'après** cette acceptation — pour que `adminComplete`, qui boucle, ne parte jamais dans
   l'itération suivante avant stabilisation.

`adminComplete` = boucle `adminAdvance` jusqu'à `STORY_COMPLETED` / `ALREADY_COMPLETED`, bornée à
`nbÉtapes + 1` itérations (jamais de boucle infinie), en accumulant la liste ordonnée des quêtes
complétées pour le rapport.

Retours structurés : `StoryAdvanceReport` (`ADVANCED` / `STORY_COMPLETED` / `ALREADY_COMPLETED` /
`UNKNOWN_STORY` / `UNKNOWN_CURRENT_QUEST`, + `storyWasStarted`, `completedQuestId`,
`completedQuestWasAlreadyDone`, `nextQuestId`, `stepNumber`, `totalSteps`) ;
`StoryCompleteReport` (`COMPLETED` / `ALREADY_COMPLETED` / `UNKNOWN_STORY` / `BLOCKED`,
+ `completedQuests`, `blockedOnQuestId`).

### 3. `RpgAdminCommand` — nouvelles sous-commandes

- `/rpgadmin quest start <joueur> <quest-id> [force]` — `accept(target, questId, force)`. Cible en
  ligne. Id inconnu / prérequis manquants / déjà active / non répétable : messages dédiés.
- `/rpgadmin quest complete <joueur> <quest-id>` — `forceComplete`. Cible en ligne. `COMPLETED`
  (récompenses appliquées) vs `ALREADY_COMPLETED` (rien re-crédité) explicités.
- `/rpgadmin quest reset <joueur> <quest-id>` — `resetQuest`. Online **ou** offline. Chaque appel
  **rappelle la limite** : n'annule pas XP / objets / variables (`CLAIM_TIER_1`) / effets de
  commande ; renvoie vers `player resetnew`, `story resetwithquests`, `player variable set … false`.
- `/rpgadmin story advance|complete <joueur> <storyId>` — appellent les nouvelles méthodes de
  `StoryService` ; le message d'`advance` indique **l'étape à tester manuellement maintenant**
  (`➜ … est maintenant à l'étape n/t … : <quest-id> (ACTIVE) — à tester manuellement`).
- `/rpgadmin player variable get <joueur> <clé>` — lecture pure (`variableRepository.get`).
  Online **ou** offline. Clé absente signalée comme telle.
- `/rpgadmin player variable set <joueur> <clé> <valeur>` — UPSERT. **Exige `rpgquest.admin.debug`
  en plus** de `rpgquest.admin.world`. Avertissement affiché (« une variable seule ne reproduit
  pas une progression »), opération **journalisée** avec ancienne + nouvelle valeur.

Toutes journalisent l'exécutant, la cible et l'opération (`[admin] <exécutant> : /rpgadmin …`).
Tab-complétion ajoutée : sous-commandes `quest` / `story` / `player variable`, joueurs en ligne,
`quest-id`, `storyId`, `force`, clés de variables courantes (indicatif, saisie libre acceptée).

### 4. Permission

Nouvelle `rpgquest.admin.debug` (`default: op`) dans `plugin.yml` — **uniquement** pour
`/rpgadmin player variable set`. Tout le reste reste sous `rpgquest.admin.world`.

### 5. `RPGQuestBootstrap`

`variableRepository` promu en champ (comme `questProgressEngine`) pour être injectable dans
`RpgAdminCommand` (construit dans `registerCommands()`, méthode distincte de `configure()`).
`RpgAdminCommand` reçoit désormais `questProgressEngine`, `questEngine`, `variableRepository`.

## Fichiers créés

- `src/test/java/com/lodygames/rpgquest/admin/RpgAdminTestShortcutsCommandTest.java` — 11 tests
  MockBukkit + SQLite sur la couche commande.
- `docs/ADMIN_TEST_SHORTCUTS.md` — référence complète (commandes, permissions, récompenses,
  limites du reset, workflow de test rapide d'une story).
- `docs/claude-reports/2026-09-06_2028_admin-test-shortcuts-issue-36.md` (ce rapport).

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngine.java` — surcharge
  `accept(..., ignorePrerequisites)`.
- `src/main/java/com/lodygames/rpgquest/story/StoryService.java` — `adminAdvance` / `adminComplete`
  + records de rapport + helper `runOnMainThread`.
- `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java` — sous-commandes `quest`,
  `story advance|complete`, `player variable get|set` ; helpers ; tab-complétion ; usages.
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — `variableRepository`
  en champ ; nouveaux arguments de `RpgAdminCommand`.
- `src/main/resources/plugin.yml` — permission `rpgquest.admin.debug`.
- `src/test/java/com/lodygames/rpgquest/story/StoryServiceTest.java` — 8 tests `adminAdvance` /
  `adminComplete` + helper `pumpAwait`.
- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngineTest.java` — 2 tests
  `accept(..., ignorePrerequisites)`.
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md` — nouvelles commandes.

## Base de données / migrations

**Aucune migration, aucun changement de schéma.** Les commandes n'utilisent que des méthodes
existantes de `QuestProgressRepository` / `StoryProgressRepository` / `PlayerVariableRepository`.
`variable set` fait un UPSERT sur `player_variables` (table et opération déjà utilisées par le kit
de départ et les récompenses `VARIABLE`).

## Configuration / données

`config.yml` inchangé. `plugin.yml` : une permission ajoutée (`rpgquest.admin.debug`, `default: op`).

## Tests automatiques

### Nouveaux / adaptés

- `StoryServiceTest` (22 tests, +8) : `adminAdvance` sur story non démarrée (démarre + complète la
  1re quête, pointe vers la 2e) ; respect de l'ordre / jamais de saut d'étape ; dernière étape →
  story `COMPLETED` ; story déjà terminée → no-op ; story inconnue → rapport propre ;
  `adminComplete` complète toutes les quêtes **dans l'ordre, une fois chacune** ; `adminComplete`
  d'une story finie → no-op ; une quête hors story n'est jamais touchée.
- `RpgAdminTestShortcutsCommandTest` (11 tests) : permission `rpgquest.admin.world` exigée ;
  `variable set` exige en plus `rpgquest.admin.debug` ; `quest start` id inconnu refusé
  proprement ; prérequis respectés / `force` les ignore ; `quest complete` applique la récompense
  `VARIABLE` **et** `ITEM` **exactement une fois** (2e appel = « déjà terminée », aucun objet en
  double) ; `quest reset` rend la quête rejouable **mais conserve** `CLAIM_TIER_1` (limite) ;
  `story advance` via commande démarre + complète + pointe l'étape suivante ; `story complete` via
  commande finit la story et applique les récompenses ; `story advance` story inconnue → message
  propre ; `variable get` lit la valeur / signale l'absence.
- `QuestProgressEngineTest` (26 tests, +2) : `accept(..., true)` saute les prérequis manquants ;
  `accept(..., true)` refuse quand même une quête déjà active.

### Commandes exécutées

- `./gradlew test build` — **BUILD SUCCESSFUL in 13m 33s** (suite complète, module racine +
  `web-api`).
- `./gradlew build` (re-vérification après un ajustement de javadoc) — **BUILD SUCCESSFUL in
  15m 05s**.
- Tests des 3 classes concernées : `RpgAdminTestShortcutsCommandTest` 11/11,
  `StoryServiceTest` 22/22, `QuestProgressEngineTest` 26/26.

Note (machine DEV ~900 Mo de RAM) : plafonds mémoire locaux hors dépôt en place
(`~/.gradle/init.d/lowmem.gradle`, `~/.gradle/gradle.properties`) — suite lente mais verte.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (client Minecraft réel). Voir la fiche pratique en fin de rapport, et
le workflow détaillé dans `docs/ADMIN_TEST_SHORTCUTS.md`.

## Résultat attendu

Après `/rpgadmin player resetnew Rondoudou9000 confirm`, un admin peut :

- tester Guide/Libraire manuellement ;
- `/rpgadmin quest complete Rondoudou9000 rpgquest:first_steps` → le Garde propose ensuite
  « La chasse aux cristaux » ;
- `/rpgadmin quest complete Rondoudou9000 rpgquest:crystal_hunt` → `CLAIM_TIER_1=true`,
  vérifiable par `/rpgadmin player variable get Rondoudou9000 CLAIM_TIER_1` ;
- puis tester Jo / le portail Claims / la Pierre de retour.

Ou piloter par la story : `/rpgadmin story advance … main_story` (une étape à la fois, message
« étape n/3 à tester ») ou `/rpgadmin story complete … main_story` (tout, dans l'ordre).

Aucune récompense n'est créditée deux fois si une commande est répétée.

## Reset / retour à l'état initial

`git checkout deploy/issue-21-claims-journey` (ou supprimer la branche `feat/36-admin-test-shortcuts`).
Aucune donnée à nettoyer : aucun schéma modifié. Les commandes elles-mêmes écrivent via les
repositories existants ; leurs effets s'annulent avec les outils déjà documentés
(`player resetnew`, `story reset[withquests]`, `player variable set … false`).

## Déploiement VeryGames

### À transférer

- `rpgquest-0.1.0-SNAPSHOT.jar` (racine FTP = `plugins/`) **uniquement**.

Aucun fichier de contenu à transférer : `plugin.yml` est **embarqué dans le JAR** (la nouvelle
permission `rpgquest.admin.debug` arrive avec le JAR). Aucun `dialogues/` / `quests/` / `stories/`
modifié.

### Ne PAS transférer / altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `worlds.yml`, tout autre fichier de
`RPGQuest/`, les mondes, `plugins/Citizens/**`, `plugins/Multiverse-Core/**`, les autres plugins.

### Redémarrage requis

Oui (nouveau JAR + nouvelle permission dans `plugin.yml` embarqué).

### Migration automatique

Aucune.

## Rollback

1. Arrêter le serveur.
2. `scripts/rollback-verygames.sh --latest` — restaure le JAR précédent (backup daté fait par le
   script de déploiement).
3. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée.

## Logs / diagnostic

Chaque opération sensible logge (console serveur, niveau `INFO`) :

- `[admin] <exécutant> : /rpgadmin quest start <cible> <quest-id> [(force)]`
- `[admin] <exécutant> : /rpgadmin quest complete <cible> <quest-id>`
- `[admin] <exécutant> : /rpgadmin quest reset <cible> <quest-id>`
- `[admin] <exécutant> : /rpgadmin story advance|complete <cible> <storyId>`
- `[admin] story advance « <storyId> » : <quest-id> → étape n/t (<quest-id-suivant>) pour <uuid> …`
- `[admin] <exécutant> : /rpgadmin player variable set <cible> <clé> = <valeur> (ancienne : <…>)`

## Documentation mise à jour

`docs/ADMIN_TEST_SHORTCUTS.md` (nouveau), `docs/RPGQUEST_BIBLE.md` (section
`/rpgadmin` + section Storylines), `docs/current_state.md`.

## Limitations / travail restant

- **`quest start` / `quest complete` / `story advance` / `story complete` exigent une cible en
  ligne** (le moteur de quête n'agit que sur un `Player` connecté). `quest reset` et
  `player variable get|set` fonctionnent hors ligne.
- **`quest reset` n'inverse pas les récompenses** déjà accordées — c'est documenté et affiché à
  chaque appel, aucune fausse abstraction d'« annulation » n'a été créée (mission point 5).
- **Pas de couche `player teststate <état>`** : jugée superflue (mission point 6 : optionnel) —
  `quest complete` / `story advance` couvrent le besoin sans framework supplémentaire.
- **`player variable unset`** non ajouté : `set … false` neutralise `CLAIM_TIER_1` (équivalent à
  absente pour `ClaimService.hasClaimTierOne`).
- Validation manuelle Minecraft `PENDING`. Issue #36 **non fermée**.

## Prochaine étape suggérée

Dérouler la fiche pratique ci-dessous en jeu (compte de test **non opéré** pour la partie
portail Claims), confirmer les messages « étape à tester », puis décider de la fermeture de #36.

------------------------------------------------------------------------

## Fiche pratique — à tester en 5 minutes

> Prérequis : PNJ `guard` créé et lié en jeu (voir rapport
> `2026-09-06_1219_audit-parcours-principal-garde-crystal-hunt.md`), serveur redémarré sur le
> nouveau JAR. `<J>` = pseudo du joueur de test, **en ligne**.

```
# 1. Repartir de zéro
/rpgadmin player resetnew <J> confirm

# 2. Sauter directement à la fin de first_steps, sans tuer 10 araignées
/rpgadmin quest complete <J> rpgquest:first_steps
#   -> "Quête complétée (récompenses appliquées) …"
#   en jeu : clic droit sur le Garde -> il propose « La chasse aux cristaux »

# 3. Sauter crystal_hunt et débloquer le claim
/rpgadmin quest complete <J> rpgquest:crystal_hunt
/rpgadmin player variable get <J> CLAIM_TIER_1
#   -> "<J> : variable CLAIM_TIER_1 = true"
#   en jeu : Jo propose « Je viens réclamer mon acte de propriété » ; le portail Hub -> claims laisse passer

# 4. Vérifier la non-duplication
/rpgadmin quest complete <J> rpgquest:crystal_hunt
#   -> "Déjà terminée … — aucune récompense re-créditée"

# --- Variante « par la story » (repartir d'un resetnew) ---
/rpgadmin story advance <J> main_story      # premiers_pas complétée -> "étape 2/3 : first_steps"
/rpgadmin story advance <J> main_story      # first_steps complétée   -> "étape 3/3 : crystal_hunt"
/rpgadmin story advance <J> main_story      # crystal_hunt complétée  -> "Story main_story TERMINÉE"
#   ou tout d'un coup :
/rpgadmin story complete <J> main_story

# --- Débogage bas niveau (permission rpgquest.admin.debug requise) ---
/rpgadmin player variable set <J> CLAIM_TIER_1 false   # neutralise le déblocage
/rpgadmin quest reset <J> rpgquest:crystal_hunt        # rend la quête rejouable (ne retire PAS l'XP/objets/variables)
```
