# RPGQuest — Rapport Claude

## Informations
* Date : 2026-08-23
* Heure : 12:56
* Sujet : Instrumentation temporaire `[QUEST-TRACE]` pour diagnostiquer précisément où s'arrête la chaîne `BREAK_BLOCK` sur VeryGames (suite de l'investigation `2026-08-23_1234_break-block-wild-investigation.md`)
* Statut : DONE (instrumentation livrée, testée, build vert) — **la cause exacte du bug n'est toujours pas connue**, cette étape ne fait qu'ajouter les moyens de l'observer sur le serveur réel, aucun correctif fonctionnel n'a été appliqué (conformément à la consigne explicite)
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `77ff6fc` (HEAD) — le travail de cette session, comme celui des sessions précédentes, reste dans l'arbre de travail (aucune n'a encore été commitée)

## Demande

Suite au rapport précédent (`2026-08-23_1234_break-block-wild-investigation.md`, statut `BLOCKED`),
l'utilisateur a confirmé un fait déterminant : **le bloc `DIRT` est réellement cassé dans `wild`** —
donc `ZoneProtectionListener` (ou toute annulation de `BlockBreakEvent`) n'est pas en cause, et il
ne fallait plus investiguer cette piste.

Demande précise :
- Ajouter **uniquement** une instrumentation temporaire `QUEST-TRACE` pour diagnostiquer
  `BREAK_BLOCK` sur VeryGames.
- Quand un joueur ayant **au moins une quête `BREAK_BLOCK` active** casse un bloc, logguer en
  `INFO` au minimum : joueur, uuid, monde, matériau, quêtes actives, candidats `BREAK_BLOCK`
  trouvés, quête/étape/objectif correspondant, progression avant/après, et un signal explicite si
  aucun progrès n'est appliqué.
- Objectif : savoir précisément où la chaîne réelle s'arrête —
  `BlockBreakEvent → listener → candidates → active quest → objective → increment`.
- **Ne pas modifier le comportement fonctionnel** tant que la cause n'est pas connue (instrumentation
  strictement en lecture, jamais de mutation).
- Ne toucher ni aux Stories, ni aux Claims, ni aux Zones, ni aux WorldPortals, ni aux autres types
  d'objectifs (`PLACE_BLOCK`, `KILL_ENTITY`, etc.) — uniquement `BREAK_BLOCK`.
- Tests strictement nécessaires, `./gradlew clean build`, rapport Claude obligatoire, donner le
  `grep` exact à utiliser sur VeryGames, déploiement JAR uniquement si possible.

## Analyse

Relu la chaîne complète concernée avant toute modification :
- `QuestBlockBreakListener.onBreak(BlockBreakEvent)` → `engine.handleBreakBlock(player, material)`.
- `QuestProgressEngine.handleBreakBlock` → `handleCandidates(player, index.breakBlock(material))`.
- `QuestObjectiveIndex.breakBlock(Material)` — table `Map<Material, List<ObjectiveRef>>` construite
  une fois par rechargement de quêtes, aucune notion de monde (confirmé lors de l'investigation
  précédente : `BREAK_BLOCK` reste correctement global entre mondes au niveau de cet index).
- `QuestProgressEngine.handleCandidates` — pour chaque candidat : vérifie que le joueur a bien cette
  quête en mémoire (`activeByPlayer`), que son état est `ACTIVE`, que son étape courante correspond
  à celle du candidat, puis incrémente le compteur si sous le plafond requis.

Cette relecture a permis d'identifier **exactement quatre points de rupture possibles** dans la
chaîne, chacun correspondant à une branche distincte du code existant :
1. `index.breakBlock(material)` renvoie une liste **vide** — aucun objectif chargé pour ce matériau
   précis (ex. faute de frappe dans le YAML, ou rechargement qui n'a pas repris la bonne définition).
2. La quête candidate n'est **pas dans le cache mémoire** du joueur (`activeByPlayer`) — jamais
   acceptée pour de vrai côté `QuestProgressEngine`, malgré ce qu'affiche `/quest progress` ou
   `/rpgadmin story info`.
3. La quête est en cache mais **pas à la bonne étape courante** (`currentStepIndex` du candidat ne
   correspond pas à celui en mémoire).
4. La quête est active et à la bonne étape, mais **déjà au plafond** (`counter >= amount`) — ne
   devrait normalement pas se produire pour une quête tout juste acceptée, mais reste un cas à
   distinguer sans ambiguïté.

L'instrumentation ajoutée reproduit, **en lecture seule et sans jamais muter d'état**, exactement le
même test que `handleCandidates` s'apprête à faire pour chacun de ces quatre points, et journalise
le résultat de cette prédiction juste avant l'appel réel — elle ne fait donc jamais deviner : elle
observe la même logique que celle qui va réellement s'exécuter, dans le même ordre.

## Travail effectué

### `QuestTraceLogger` (nouvelle classe, formatteur uniquement)

`src/main/java/com/lodygames/rpgquest/quest/progress/QuestTraceLogger.java` — même conception que
`travel.TpTraceLogger` de l'investigation WorldPortal précédente : une seule méthode statique
formatant une ligne `[QUEST-TRACE]`, aucune logique de décision dedans (la décision reste dans
`QuestProgressEngine`, seul endroit qui a accès à `activeByPlayer`).

### `QuestProgressEngine#traceBreakBlockChain` (nouvelle méthode privée)

Appelée uniquement depuis `handleBreakBlock`, juste **avant** `handleCandidates` (jamais après :
l'instrumentation prédit ce qui va se passer, elle ne l'observe pas après coup, pour rester
totalement passive et ne jamais risquer d'interférer avec l'ordre réel des opérations) :

1. **Porte d'entrée (gate demandé)** : si le joueur n'a **aucune** quête `ACTIVE` dont l'étape
   courante contient un objectif `BREAK_BLOCK` (n'importe quel matériau), la méthode retourne
   immédiatement sans rien journaliser — jamais de spam pour un joueur sans quête `BREAK_BLOCK` en
   cours, quel que soit le nombre de blocs cassés.
2. Sinon, calcule et journalise en une seule ligne `INFO` :
   - `player`, `uuid`, `world`, `material`.
   - `active_break_block_quests` — les quêtes actives du joueur qui ont un objectif `BREAK_BLOCK` à
     leur étape courante (`<questId>:<stepId>`), indépendamment du matériau cassé cette fois-ci —
     donne le contexte complet, pas seulement ce qui matche.
   - `candidates` — exactement ce que `index.breakBlock(material)` a trouvé
     (`<questId>:<stepId>:obj<n>`) — **une liste vide ici signale sans ambiguïté que la chaîne
     s'arrête au point de rupture n°1** (l'index lui-même).
   - `evaluations` — pour **chaque** candidat, le verdict exact : `SKIP(quest_not_in_active_cache)`
     (point de rupture n°2), `SKIP(quest_state_<état>)`, `SKIP(step_mismatch_current_index_<n>)`
     (point de rupture n°3), `SKIP(already_at_cap_<avant>/<total>)` (point de rupture n°4), ou
     `WILL_INCREMENT(<avant>/<total>-><après>/<total>)` (progression avant/après demandée
     explicitement — la chaîne ne s'arrête nulle part, l'incrément va bien être appliqué).
   - `outcome` — résumé explicite : `no_candidates_from_index` / `no_active_match` / `progressed` —
     **c'est le signal demandé pour repérer immédiatement « aucun progrès appliqué »** sans avoir à
     relire le détail de `evaluations` à chaque fois.

### Aucune modification fonctionnelle

`handleCandidates` — la méthode qui mute réellement l'état — n'a **pas été touchée**. La seule
modification de `handleBreakBlock` est l'ajout de l'appel à `traceBreakBlockChain` juste avant
l'appel (inchangé) à `handleCandidates`. Aucun autre type d'objectif (`PLACE_BLOCK`, `KILL_ENTITY`,
`COLLECT_ITEM`, `CRAFT_ITEM`, `TALK_TO_NPC`, `REACH_LOCATION`) n'est concerné. Aucun fichier des
packages `story`, `claim`, `zone`, `travel` n'a été touché.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/quest/progress/QuestTraceLogger.java`
- `docs/claude-reports/2026-08-23_1256_quest-trace-break-block-instrumentation.md` (ce rapport)

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngine.java` — ajout de
  `traceBreakBlockChain` (méthode privée, lecture seule) et de son unique appel dans
  `handleBreakBlock` ; import `BreakBlockObjective` ajouté.
- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngineTest.java` — deux
  nouveaux tests (voir « Tests automatiques »), import `assertDoesNotThrow` ajouté.

## Base de données / migrations

Aucune modification.

## Configuration / données

Aucune.

## Tests automatiques

- Commande exécutée : `./gradlew clean build` (jamais `-x test`).
- Résultat : `BUILD SUCCESSFUL in 2m 14s`.
- Nouveaux tests ajoutés (strictement nécessaires — l'instrumentation étant un formateur de log
  privé sans état observable directement, les tests couvrent le **risque fonctionnel** qu'elle
  introduit, pas le contenu exact des lignes de log) :
  - `traceInstrumentationNeverAltersProgressionWithMixedActiveObjectiveTypes` — un joueur a
    simultanément une quête `KILL_ENTITY` active et une quête `BREAK_BLOCK` active ; casser des
    blocs progresse et termine normalement la quête `BREAK_BLOCK`, et la quête `KILL_ENTITY` en
    parallèle n'est jamais affectée par le passage en revue effectué par l'instrumentation.
  - `traceInstrumentationDoesNotThrowWhenTheBrokenMaterialMatchesNoLoadedObjective` — couvre la
    branche « candidats vides » (`no_candidates_from_index`) : casser un matériau qui ne correspond
    à aucun objectif chargé reste un no-op silencieux, jamais d'exception, jamais de fausse
    progression.
  - Le test déjà existant de l'étape précédente
    (`breakBlockObjectiveProgressesFromABlockBreakEventFiredInAnyNamedWorldIncludingWild`) sert
    aussi, sans modification, de garde-fou : il exerce désormais `traceBreakBlockChain` à chaque
    appel (puisqu'elle est appelée sans condition depuis `handleBreakBlock`) et continue de passer,
    ce qui prouve que l'instrumentation n'interfère pas avec le scénario déjà validé.
- Nombre total (dernier build complet, module principal) : **783 tests, 0 échec, 11 ignorés**
  (`skipped`, préexistants, sans rapport avec cette étape).

## Tests manuels à effectuer

Aucun test manuel de *validation fonctionnelle* requis (rien n'a changé côté gameplay). Le test
manuel pertinent est la **collecte des logs** sur VeryGames :

1. Déployer le nouveau JAR (voir « Déploiement VeryGames »).
2. Reproduire le scénario `story_test` dans `wild` (casser du `DIRT` avec `test_break_block`
   active) exactement comme précédemment.
3. Récupérer les lignes `[QUEST-TRACE]` du joueur concerné dans les logs serveur — voir le `grep`
   exact ci-dessous.

## Résultat attendu

Une ligne `[QUEST-TRACE]` par cassage de bloc pertinent (le joueur a une quête `BREAK_BLOCK` active,
quel que soit le matériau cassé). Le champ `outcome` indique directement où regarder :
- `no_candidates_from_index` → le problème est dans le chargement/l'indexation de la définition de
  quête (matériau mal configuré, quête pas rechargée) — comparer le `material` journalisé avec celui
  du YAML `test_break_block.yml`.
- `no_active_match` → regarder le détail de `evaluations` pour chaque candidat : `SKIP(quest_not_in_active_cache)`
  signale que la quête n'est en réalité pas suivie côté `QuestProgressEngine` malgré les apparences ;
  `SKIP(step_mismatch_current_index_N)` signale une étape désynchronisée ; `SKIP(already_at_cap_...)`
  signale un compteur déjà plein.
- `progressed` → l'incrément est bien appliqué en mémoire ; si le joueur ne voit toujours rien
  progresser malgré cette ligne, le problème serait alors en aval (persistance, notification), ce
  qui orienterait une toute prochaine investigation vers `repository.setObjectiveProgress`/l'affichage
  côté client plutôt que vers la logique d'objectif elle-même.

## Reset / retour à l'état initial

Sans objet — aucune donnée modifiée, aucun état persistant créé par cette instrumentation.

## Déploiement VeryGames

**JAR uniquement — rien d'autre à transférer.**

### À transférer
- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (nom de version exact selon `./gradlew clean build`).

### Ne PAS transférer/altérer
- `data.db`, tous les mondes (`world_hub`, `wild`, etc.), `Citizens/saves.yml`, données joueurs,
  configuration — rien de concerné par cette instrumentation.

### Redémarrage requis
Oui — remplacement de JAR standard (scénario 2, `docs/deployment/VERYGAMES.md`).

### Migration automatique
Non applicable — aucun changement de schéma.

## Rollback

Remettre l'ancien `rpgquest-*.jar` sauvegardé avant remplacement, puis redémarrer. Aucune donnée à
restaurer.

## Logs / diagnostic

- **Préfixe de log** : `[QUEST-TRACE]` (niveau `INFO`).
- **`grep` exact à utiliser sur VeryGames** (fichier de log courant du serveur, typiquement
  `logs/latest.log`) :

  ```
  grep "QUEST-TRACE" logs/latest.log
  ```

  Pour filtrer sur un joueur précis une fois son UUID connu :

  ```
  grep "QUEST-TRACE" logs/latest.log | grep "uuid=<uuid-du-joueur>"
  ```

- **Format exact d'une ligne** :

  ```
  [QUEST-TRACE] player=<pseudo> uuid=<uuid> world=<monde> material=<MATERIAU> active_break_block_quests=[<questId>:<stepId>, ...] candidates=[<questId>:<stepId>:obj<n>, ...] evaluations=[<questId>:<stepId>:obj<n>=SKIP(raison)|WILL_INCREMENT(avant/total->après/total>), ...] outcome=<no_candidates_from_index|no_active_match|progressed>
  ```

- **Lignes importantes à me renvoyer si le problème persiste** : toutes les lignes `[QUEST-TRACE]`
  du joueur concerné (filtrées par `uuid=`) couvrant au moins les 3 premiers cassages de bloc de
  test — le champ `outcome` de la toute première ligne suffira très probablement à lui seul à
  identifier le point de rupture exact.

## Documentation mise à jour

Aucune — instrumentation temporaire, purement diagnostique, sans changement de comportement à
documenter fonctionnellement. Le rapport lui-même constitue la documentation de cette étape.

## Limitations / travail restant

- La cause exacte du bug reste inconnue à ce stade — cette étape ajoute uniquement les moyens de
  l'observer directement sur le serveur réel, elle ne la corrige pas.
- Le contenu exact des lignes de log n'a pas pu être vérifié par un test d'assertion directe (le
  logger de `QuestProgressEngine` provient de `plugin.getSLF4JLogger()`, non injectable directement
  dans les tests existants sans un doublon complet de l'interface `org.slf4j.Logger` — jugé
  disproportionné pour une instrumentation temporaire, voir la même décision documentée dans le
  rapport WorldPortal précédent). Les tests ajoutés couvrent donc le risque fonctionnel réel
  (absence de mutation, absence d'exception) plutôt que le texte exact loggé.
- Cette instrumentation est volontairement scopée à `BREAK_BLOCK` uniquement, comme demandé — les
  autres types d'objectifs n'ont aucune trace équivalente si le même genre de problème s'y
  manifestait un jour.

## Prochaine étape suggérée

Déployer le JAR sur VeryGames, reproduire le scénario, et renvoyer les lignes `[QUEST-TRACE]`
obtenues (au minimum la valeur du champ `outcome` du tout premier cassage de bloc concerné) — cela
devrait suffire à identifier précisément le point de rupture réel et à préparer un correctif ciblé.
Rien commencé automatiquement au-delà de cette instrumentation.
