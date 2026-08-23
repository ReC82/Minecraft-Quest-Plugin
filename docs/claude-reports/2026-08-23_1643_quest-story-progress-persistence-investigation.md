# RPGQuest — Rapport Claude

## Informations
* Date : 2026-08-23
* Heure : 16:43
* Sujet : Bug VeryGames « la progression Story/quête se réinitialise au death/respawn et à la déconnexion/reconnexion » — investigation, tests de reproduction, correctif
* Statut : PARTIAL — un bug réel et concret a été trouvé et corrigé (chaîne Story → Quête), mais **le symptôme exact rapporté (progression qui fonctionne puis se réinitialise) n'a pas pu être reproduit** dans le périmètre explicitement demandé, malgré des tests utilisant le vrai bootstrap et de vrais événements Bukkit
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `77ff6fc` (HEAD) — ce travail, comme celui des sessions précédentes, reste dans l'arbre de travail (rien commité)

## Demande

Bug confirmé sur VeryGames : « La progression Story/quête fonctionne normalement pendant la
session, mais elle se réinitialise lorsqu'un joueur : meurt/respawn ; se déconnecte puis se
reconnecte. » Comportement interdit — la progression persistante d'une quête et le `current_index`
d'une Story doivent survivre à : mort/respawn, changement de monde, déconnexion/reconnexion,
redémarrage serveur.

Périmètre d'investigation explicitement fixé par l'utilisateur (rien d'autre à inspecter) :
- `QuestProgressEngine` / `QuestProgressConnectionListener`
- `StoryService` / `StoryConnectionListener`
- Événements `PlayerQuit`, `PlayerJoin`, `PlayerDeath`, `PlayerRespawn`
- Écritures DB des compteurs d'objectifs

Chercher notamment une différence entre état mémoire et état SQLite, ou un `unload` qui
supprime/reset au lieu de seulement vider le cache. Ajouter 5 tests de reproduction précis (listés
ci-dessous), ne pas changer le gameplay, ne pas toucher Claims/Portails/Hub, ne rien refactoriser
hors sujet, `./gradlew clean build`, rapport Claude obligatoire avec cause exacte/correctif/tests/
migration éventuelle/déploiement VeryGames/procédure de reset-test, puis s'arrêter.

## Analyse

### Relecture statique complète du périmètre

- `QuestProgressEngine#loadForPlayer`/`unloadForPlayer` (`QuestProgressEngine.java:178-211`) :
  `unloadForPlayer` ne fait **que** `activeByPlayer.remove(playerId)` — aucune écriture DB, aucune
  suppression. `loadForPlayer` relit `quest_progress` + `quest_objective_progress` et reconstruit le
  cache en mémoire depuis la base, uniquement pour les états `ACTIVE`/`READY_TO_TURN_IN`. Rien
  d'anormal.
- `StoryService#loadForPlayer`/`unloadForPlayer` (`StoryService.java:116-145`) : même patron exact,
  `unloadForPlayer` ne vide que le cache. `loadForPlayer` relit `story_progress`, reconstruit le
  cache, puis s'auto-guérit (`ensureCurrentQuestsAreOnTrack`/`evaluateStory`) en comparant l'état réel
  de la quête courante à ce qu'elle devrait être. Rien d'anormal.
- `QuestProgressConnectionListener`/`StoryConnectionListener` : deux listeners `MONITOR` triviaux,
  chacun appelant strictement `loadForPlayer`/`unloadForPlayer` sur join/quit. Rien d'anormal.
- **Aucun listener n'existe pour `PlayerDeathEvent`** dans tout le code source (`grep` exhaustif) —
  seul `SpawnService#handleRespawn` écoute `PlayerRespawnEvent`, et uniquement pour rediriger la
  **position** de réapparition (`event.setRespawnLocation(...)`) ; il ne touche à aucune donnée de
  quête/story.
- `PlayerChangedWorldEvent` : seul `WorldPortalTeleportListener` (hors périmètre, Portails) l'écoute,
  uniquement pour du logging `TP-TRACE` — aucune interaction avec quête/story.
- `DatabaseManager` : connexion JDBC unique, exécuteur **mono-thread FIFO** — toute écriture
  (`setObjectiveProgress`, `upsertState`, `upsertProgress`) soumise à l'exécuteur **avant** une lecture
  ultérieure (ex. `loadForPlayer` après une reconnexion) s'exécute forcément avant elle : pas de
  course lecture/écriture possible à l'intérieur d'une même JVM.
- `PlayerConnectionListener`/`PlayerProfileService`/`PlayerProfileRepository` (piste explorée en plus,
  car `quest_progress`/`story_progress` ont une contrainte `FOREIGN KEY ... ON DELETE CASCADE` vers
  `player_profiles`) : `findOrCreate` ne fait jamais de `DELETE` — seulement `INSERT` (première
  visite) ou `UPDATE last_name` (visite suivante). Aucun code du projet ne supprime jamais une ligne
  `player_profiles` en dehors d'un reset admin explicite. Cette piste, bien que plausible sur le
  papier (un `DELETE` + `INSERT` aurait cascadé et tout effacé), est éliminée par lecture directe du
  code.

**Aucune anomalie de code n'a été trouvée par relecture seule** dans les quatre systèmes désignés.

### Tests de reproduction avec le vrai bootstrap

Les suites de tests existantes (`QuestProgressEngineTest`, `StoryServiceTest`) construisent chacune
leur propre `QuestProgressEngine`/`StoryService` à la main et **n'enregistrent jamais**
`QuestProgressConnectionListener` ni `StoryConnectionListener` auprès d'un vrai serveur — elles
appellent `loadForPlayer`/`unloadForPlayer` directement. Un bug qui ne se manifesterait que dans le
câblage réel (bootstrap complet, vrais événements Bukkit) aurait pu passer inaperçu.

Nouveau fichier `QuestStoryProgressPersistenceIntegrationTest` (voir « Tests automatiques ») : utilise
`MockBukkit.load(RPGQuestPlugin.class)` + `plugin.bootstrap()` (même patron que
`CrystalHuntIntegrationTest`), déclenche de **vrais** `PlayerQuitEvent`/`PlayerJoinEvent`,
`player.setHealth(0.0)`/`player.respawn()` (mort/réapparition réelles simulées par MockBukkit) et
`PlayerChangedWorldEvent`, plus une simulation de redémarrage serveur (vidage direct des deux caches
mémoire sans passer par un événement de déconnexion, puis rechargement — ne peut s'appuyer que sur la
base SQLite, exactement comme un vrai redémarrage JVM).

**Résultat : les 5 scénarios demandés passent tous**, y compris en écriture réelle via de vrais
`BlockBreakEvent`, contre le bootstrap réel et non une reconstruction simplifiée. La progression
(compteur d'objectif ET index de Story) survit correctement à chacun des 5 cas dans le code tel
qu'il existe aujourd'hui pour `QuestProgressEngine`/`StoryService`.

### Bug réel trouvé en construisant le test Story (et corrigé)

En écrivant le test de reproduction n°2 (Story à la 2e quête), la toute première étape a échoué :
`storyService.start(...)` sur `main_story` (qui référence `rpgquest:premiers_pas` en première
quête) persistait bien la Story en `ACTIVE`/`index=0`, **mais la quête `premiers_pas` elle-même
n'était jamais acceptée** (`QuestState.NOT_STARTED`, `AcceptOutcome.UNKNOWN_QUEST` en appel direct).

Cause exacte : `YamlQuestEngine.BUNDLED_EXAMPLES` (`YamlQuestEngine.java:21`, avant correctif) ne
listait que `{"first_steps.yml", "woodcutters_request.yml", "crystal_hunt.yml"}` —
**`premiers_pas.yml` en était absent**, alors que `main_story.yml` (lui bien dans
`StoryRegistry.BUNDLED_EXAMPLES`) le référence comme **première** quête de l'histoire principale.
`ensureExamplesExist()` ne copie dans `plugins/RPGQuest/quests/` que les fichiers listés dans
`BUNDLED_EXAMPLES` — sur un dossier de données neuf (ou n'ayant jamais eu ce fichier), `premiers_pas`
n'existait donc simplement jamais sur le disque, `YamlQuestEngine` ne le chargeait pas, et
`StoryService#startCurrentQuest` échouait silencieusement (un seul `logger.warn`, aucun message
joueur) :

```java
questEngine.find(questId).isEmpty() -> "référence une quête inconnue « {} » — quête ignorée"
```

Conséquence côté joueur : démarrer `main_story` persiste bien un état « en cours », mais aucune
quête concrète n'apparaît jamais — du point de vue du joueur, cela **ressemble** à une progression
qui ne « prend » jamais, ou qui semble s'être « réinitialisée » (rien de suivi malgré une Story
prétendument active). Ce n'est cependant pas exactement le symptôme rapporté (« fonctionne
normalement PENDANT la session, puis se réinitialise ») : ce bug bloque dès le départ, il ne fait
pas régresser une progression déjà acquise.

## Travail effectué

### Correctif appliqué

`src/main/java/com/lodygames/rpgquest/quest/YamlQuestEngine.java` — ajout de `"premiers_pas.yml"` à
`BUNDLED_EXAMPLES`. Comme `ensureExamplesExist()` ne copie que les fichiers **absents**, ce correctif
s'auto-applique tout seul au prochain démarrage sur tout serveur (y compris VeryGames) dont le
dossier `plugins/RPGQuest/quests/` ne contient pas déjà `premiers_pas.yml` — aucune action manuelle
nécessaire.

### Ce qui n'a PAS été trouvé/corrigé

Le symptôme exact rapporté — une progression qui fonctionne normalement puis se réinitialise après
mort/respawn ou déconnexion/reconnexion — **n'a pas pu être reproduit** dans
`QuestProgressEngine`/`StoryService`/leurs connection listeners/les événements Player*/les écritures
DB, malgré :
- une relecture complète et ligne à ligne des quatre systèmes désignés ;
- l'élimination explicite de la piste `player_profiles`/cascade FK ;
- 5 tests de reproduction fidèles utilisant le vrai bootstrap et de vrais événements Bukkit,
  tous verts.

Voir « Limitations / travail restant » pour les pistes hors périmètre qui restent à explorer.

## Fichiers créés

- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestStoryProgressPersistenceIntegrationTest.java`
  — les 5 tests de reproduction demandés (voir « Tests automatiques »).
- `docs/claude-reports/2026-08-23_1643_quest-story-progress-persistence-investigation.md` (ce rapport).

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/quest/YamlQuestEngine.java` — `premiers_pas.yml` ajouté à
  `BUNDLED_EXAMPLES` (seul changement fonctionnel de cette session).
- `src/test/java/com/lodygames/rpgquest/quest/YamlQuestEngineTest.java` —
  `startGeneratesBundledExamplesThatParseWithoutErrors` mis à jour (4 exemples au lieu de 3,
  `premiers_pas.yml` désormais attendu) : ce test préexistant a détecté correctement l'effet du
  correctif, bon signe de couverture.
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — ajout d'un getter public
  `storyService()` (même patron que `questProgressEngine()`), nécessaire pour que le nouveau test
  d'intégration accède au vrai `StoryService` construit par le bootstrap ; pas de changement de
  comportement.

## Base de données / migrations

Aucune migration de schéma. Le correctif `premiers_pas.yml` n'affecte que le système de fichiers
(`plugins/RPGQuest/quests/`), jamais `data.db`.

## Configuration / données

Aucune modification de configuration.

## Tests automatiques

- Commande exécutée : `./gradlew clean build` (jamais `-x test`).
- Résultat : `BUILD SUCCESSFUL in 2m 22s`.
- Total (module principal) : **788 tests, 0 échec, 11 ignorés** (`skipped`, préexistants, sans
  rapport avec cette session — 5 tests de plus qu'avant cette session, tous ajoutés ici).

Les 5 scénarios demandés, dans `QuestStoryProgressPersistenceIntegrationTest` :

1. `breakBlockProgressAt2Of3SurvivesDisconnectAndReconnect` — quête `BREAK_BLOCK` à 2/3 (via de vrais
   `BlockBreakEvent` dans un monde nommé `wild`), `PlayerQuitEvent` puis `PlayerJoinEvent` réels,
   compteur toujours 2/3 après reconnexion, un seul bloc de plus suffit à terminer. **PASS.**
2. `storyOnItsSecondQuestSurvivesDisconnectAndReconnect` — Story `main_story` avancée jusqu'à sa 2e
   quête (`first_steps`, 4/10 araignées tuées), déconnexion/reconnexion réelles, index de Story et
   compteur d'araignées inchangés, les 6 araignées restantes suffisent à terminer et à faire avancer
   la Story vers `crystal_hunt`. **PASS** (après le correctif `premiers_pas.yml` ci-dessus — ce test
   est celui qui a révélé le bug).
3. `progressSurvivesDeathAndRespawn` — `player.setHealth(0.0)` (déclenche une vraie
   `PlayerDeathEvent`) puis `player.respawn()` (déclenche une vraie `PlayerRespawnEvent`), compteur
   `BREAK_BLOCK` inchangé (2/3) avant/après. **PASS.**
4. `progressSurvivesWorldChange` — `PlayerChangedWorldEvent` réel, compteur inchangé. **PASS.**
5. `progressPersistedInDatabaseSurvivesAFullMemoryWipeSimulatingAServerRestart` — vidage direct des
   deux caches mémoire (`QuestProgressEngine`/`StoryService`) sans passer par une déconnexion, puis
   rechargement ne pouvant s'appuyer que sur SQLite (simule la perte totale de mémoire d'un
   redémarrage JVM) : quête et Story retrouvent exactement leur état persisté. **PASS.**

## Tests manuels à effectuer

Aucun test manuel de non-régression requis pour le correctif appliqué (changement purement additif
côté génération de fichiers d'exemple). En revanche, pour continuer l'investigation du symptôme
principal (non résolu), voir « Prochaine étape suggérée ».

## Résultat attendu

- `main_story` fonctionne désormais correctement de bout en bout dès un premier démarrage sur un
  dossier de données neuf (ou n'ayant jamais eu `premiers_pas.yml`) : `premiers_pas` est
  automatiquement générée, chargée, acceptée, et la Story progresse normalement jusqu'à
  `crystal_hunt`.
- Pour le symptôme principal rapporté : aucun changement de comportement, puisqu'aucune cause n'a pu
  être identifiée ni corrigée dans le périmètre demandé.

## Reset / retour à l'état initial

Sans objet pour le correctif (ajout d'un fichier d'exemple manquant, aucune donnée existante
touchée). Pour retester `main_story` depuis zéro sur un personnage donné :
`/rpgadmin story resetwithquests <joueur> main_story` (remet la Story **et** ses 3 quêtes à zéro),
ou `/rpgadmin story reset <joueur> main_story` (Story seule, quêtes intactes).

## Déploiement VeryGames

**JAR uniquement.**

### À transférer
- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (nom de version exact selon `./gradlew clean build`).

### Ne PAS transférer/altérer
- `data.db` — aucune migration, aucune modification de schéma.
- Le dossier `plugins/RPGQuest/quests/` existant : ne rien y toucher manuellement. Si
  `premiers_pas.yml` n'y existe pas déjà, il sera généré automatiquement au redémarrage grâce au
  correctif (`ensureExamplesExist()` ne copie que les fichiers absents, jamais un écrasement).
- Mondes, `Citizens/saves.yml`, données joueurs, configuration — rien de concerné par cette session.

### Redémarrage requis
Oui — remplacement de JAR standard (scénario 2, `docs/deployment/VERYGAMES.md`).

### Migration automatique
Non applicable côté base de données. Côté fichiers, `premiers_pas.yml` sera auto-généré au
redémarrage s'il est absent (comportement déjà existant de `ensureExamplesExist()`, juste étendu à
ce fichier).

## Rollback

Remettre l'ancien `rpgquest-*.jar` sauvegardé avant remplacement, puis redémarrer. Aucune donnée à
restaurer (le correctif n'ajoute qu'un fichier YAML d'exemple, sans effet destructif s'il est
retiré à nouveau).

## Logs / diagnostic

Aucun log spécifique ajouté cette fois. Pour continuer à investiguer le symptôme principal sur le
serveur réel, voir la procédure suggérée ci-dessous.

## Documentation mise à jour

Aucune mise à jour de documentation fonctionnelle nécessaire — `premiers_pas.yml` était déjà
documenté comme faisant partie de `main_story` (voir `docs/storylines.md`), seul le mécanisme de
génération d'exemples au premier démarrage était en défaut.

## Limitations / travail restant

- **Le symptôme exact rapporté par l'utilisateur (progression qui fonctionne puis se réinitialise
  après mort/respawn ou reconnexion) n'a pas été reproduit** malgré une relecture exhaustive et 5
  tests de reproduction fidèles (vrai bootstrap, vrais événements) qui passent tous. Le bug, s'il
  est toujours présent sur le serveur réel, se situe très probablement **hors du périmètre
  explicitement fixé pour cette session** (`QuestProgressEngine`/`StoryService`/leurs connection
  listeners/événements `Player*`/écritures DB des compteurs). Pistes concrètes à vérifier côté
  infrastructure, non exploré ici faute d'accès au serveur réel et car hors périmètre demandé :
  - **Topologie réseau** : si VeryGames fait tourner plusieurs serveurs Paper distincts (proxy
    BungeeCord/Velocity, ex. un serveur « hub » et un serveur « wild » séparés) partageant le même
    fichier `data.db`, chaque serveur a son **propre** cache mémoire `activeByPlayer` et sa **propre**
    connexion SQLite mono-thread — un aller-retour entre deux serveurs physiques distincts est un
    vrai quit/join au niveau réseau (ce qui correspondrait bien à « déconnexion/reconnexion »), et
    un accès concurrent multi-processus à un même fichier SQLite (sans WAL/`busy_timeout` configuré,
    voir `DatabaseManager.initialize()` qui n'active aujourd'hui que `PRAGMA foreign_keys = ON`) peut
    provoquer des pertes d'écriture ou des lectures obsolètes que rien dans le code applicatif ne
    peut détecter ni corriger. Une mort qui redirige vers un serveur différent (hub) expliquerait
    aussi pourquoi mort/respawn produit le même symptôme qu'une déconnexion — dans un tel montage, ce
    serait bien, techniquement, une reconnexion réseau.
  - Vérifier concrètement : le monde où le symptôme apparaît (`wild` notamment, déjà mentionné dans
    l'investigation précédente) est-il servi par le **même** processus Paper / la **même** copie de
    `data.db` que le reste du réseau, ou par un serveur physiquement séparé ?
- Cette session confirme (à nouveau, comme l'investigation `BREAK_BLOCK`/`QUEST-TRACE` précédente)
  que la logique applicative de persistance elle-même est saine sur les scénarios testés — la piste
  restante est très probablement d'ordre infrastructure/déploiement plutôt que code.

## Prochaine étape suggérée

Avant tout nouveau correctif de code, obtenir de VeryGames une réponse précise à : **le réseau
utilise-t-il plusieurs serveurs Paper (proxy BungeeCord/Velocity) partageant le même `data.db`, et
si oui, `wild` (ou le monde où le reset est observé) est-il l'un de ces serveurs séparés ?** Si oui,
la correction se situerait dans la configuration SQLite (WAL + `busy_timeout`) voire dans
l'architecture de déploiement (une seule base par serveur physique n'est pas compatible avec un
état partagé cohérent sans un vrai service réseau devant la base), un sujet hors périmètre de cette
session et nécessitant une nouvelle demande explicite. Arrêt de cette session ici, comme demandé.
