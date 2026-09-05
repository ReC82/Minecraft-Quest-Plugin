# RPGQuest — Rapport Claude

## Informations
* Date : 2026-08-21
* Heure : 21:35
* Sujet : Progression automatique de Storyline — connexion du moteur Story existant au moteur de quête existant, de bout en bout
* Statut : DONE
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `77ff6fc` (HEAD au moment de la rédaction) — **le travail décrit ci-dessous, ainsi que le travail WorldPortal Debug d'une session précédente (déjà rapporté dans `2026-08-21_2009_worldportal-debug-tools.md`), sont tous les deux encore non commités** dans l'arbre de travail à ce stade. Ce rapport ne couvre que le travail « progression automatique de Storyline » de cette session — voir le rapport précédent pour le reste.

## Demande

Rendre le système Storyline (posé lors d'une session précédente, minimal : démarrage/consultation/
réinitialisation admin uniquement, aucun lien avec le moteur de quête) réellement jouable de bout en
bout, en le connectant au moteur de quête existant. Exigences principales :

- Une Story `ACTIVE` doit avancer **automatiquement** : sa première quête démarre au lancement de la
  Story ; une complétion de la quête courante fait avancer la Story vers la suivante (démarrée à son
  tour automatiquement) ; la dernière complétion passe la Story à `COMPLETED`.
- **Aucune commande joueur** ne doit jamais être nécessaire entre deux quêtes d'une même Story.
- Le système doit être **idempotent** (jamais de double avancement, double démarrage, double
  récompense) et **survivre** à une déconnexion/reconnexion ou un redémarrage serveur.
- Une quête utilisée hors Story doit rester utilisable normalement, et l'architecture doit continuer
  à supporter plusieurs Stories indépendantes pour un même joueur.
- Conserver `/rpgadmin story reset <joueur> <storyId|all>` tel quel, et ajouter un outil admin/debug
  **ciblé** permettant de remettre une Story de test *et* ses quêtes associées dans un état rejouable
  — sans jamais deviner que `reset` seul doit toucher aux quêtes.
- Fournir une petite Story de test (~3 quêtes simples, réalisables sans coordonnées/PNJ fictifs) pour
  valider le scénario complet en jeu.
- Feedback joueur minimal via `messages.yml`/MiniMessage (« Nouvelle aventure », « Nouvel objectif »,
  « Aventure terminée »).
- Tests automatisés couvrant l'ensemble du scénario, `./gradlew clean build` vert (jamais `-x test`),
  documentation à jour, rapport Claude obligatoire.

Explicitement hors périmètre pour cette étape (à ne pas commencer) : Claims 5×5, récompenses de
palier, PNJ Story du Wild, équipement légendaire, points de compétence, GUI Story avancée. Le bug
`hub_to_claims` (téléportation automatique dans le Hub) était signalé comme déjà résolu (mauvaise
sélection de zone) — code `travel`/WorldPortal explicitement à ne pas toucher pour cette raison.

## Analyse

**Inspection effectuée avant modification** (lecture complète, pas de suppositions) :
- `story.StoryService`/`StoryRegistry`/`StoryDefinition`/`StoryState` — confirmé : aucune référence
  au moteur de quête, `StoryService` sans aucun cache mémoire, seulement `info`/`start`/`reset`
  (DB-only), exactement comme documenté par la session précédente.
- `database.StoryProgressRepository` — table `story_progress` avec seulement `(player_uuid,
  story_id, state, updated_at)`, aucune notion de position dans la liste de quêtes.
- `quest.progress.QuestProgressEngine` — lu intégralement. Points clés retenus : `accept(Player,
  NamespacedKey)` (démarre une quête, idempotent — renvoie `ALREADY_ACTIVE` proprement),
  `forceComplete(Player, NamespacedKey)` (termine une quête sans passer par les objectifs, utilisé
  par `/quest complete` et déjà pensé pour les tests), `resetQuest(UUID, NamespacedKey)` (reset
  ciblé d'une seule quête, garanti de ne jamais toucher aux autres), `stateOf(UUID, NamespacedKey)`
  (lecture d'état, cache-first puis DB), et surtout `onProgressChanged(Consumer<UUID>)` — notifié
  après **toute** mutation de progression, déjà utilisé en interne par
  `progression.listener.QuestCompletionXpListener` (branché sur ce même hook pour l'XP de palier) :
  ce précédent a directement dicté l'architecture retenue (voir plus bas).
- `dialogue.model.StartQuestAction`/`TurnInQuestAction`/`AdvanceQuestAction` — confirmé que ces
  actions existent déjà (le joueur peut démarrer une quête via un dialogue), mais qu'elles ne sont
  **pas** le mécanisme choisi ici : la Story appelle `accept()` directement, jamais via une action de
  dialogue, pour ne jamais dépendre d'une interaction PNJ entre deux quêtes (exigence explicite).
- `/rpgadmin story` (dans `RpgAdminCommand`) — confirmé : seule branche utilisable depuis la console,
  résolution de cible en/hors ligne déjà en place, à conserver telle quelle et étendre.
- `docs/storylines.md` — relu entièrement ; confirmé qu'il décrivait fidèlement l'état *avant*
  modification (« délibérément indépendant », « pas de progression automatique ») — donc pas
  d'écart doc/code à signaler avant de commencer, seulement une réécriture nécessaire après.
- `docs/manual-tests/quests/` — confirmé l'existence d'un pack de 7 quêtes de test déjà présentes,
  non déployées par défaut (copie manuelle documentée), dont 3 (`test_break_block`,
  `test_place_block`, `test_collect_item`) réalisables n'importe où sur le serveur sans coordonnée
  ni PNJ — retenues telles quelles pour la Story de test plutôt que d'en écrire de nouvelles.

**Décision architecturale principale** : plutôt que de créer un second moteur parallèle (« Story
Progress Engine » séparé), la logique d'avancement automatique a été ajoutée **directement à
`StoryService` existant** — celui-ci avait déjà les responsabilités `start`/`reset`/`info`, et
créer une classe séparée aurait dupliqué l'accès au cache/à la persistance sans bénéfice réel. Le
point d'accroche est une référence de méthode (`storyService::onQuestProgressChanged`) enregistrée
sur `QuestProgressEngine#onProgressChanged` dans le bootstrap — même patron que
`QuestCompletionXpListener`, qui prouvait déjà que ce hook générique convient à un système externe
réagissant aux complétions de quête sans modifier `QuestProgressEngine`.

## Travail effectué

### Persistance de la position courante

`story_progress` gagne une colonne `current_index` (position 0-based dans `StoryDefinition
.questIds()`), ajoutée via `ALTER TABLE` (migration V14). **Bug découvert et corrigé pendant le
développement** : un `ALTER TABLE ADD COLUMN` n'est pas idempotent comme `CREATE TABLE IF NOT
EXISTS` — le test `SchemaMigratorTest#migratingFromAnAlreadyPartiallyMigratedDatabaseStillReachesCurrentVersion`
(préexistant, simulait un re-run de migration depuis une version antérieure) a immédiatement révélé
une `SQLiteException: duplicate column name` à la première exécution. Corrigé en vérifiant
explicitement via `PRAGMA table_info` que la colonne n'existe pas avant de l'ajouter — deux
nouveaux tests couvrent ce cas précis (colonne présente après migration fraîche, et préservation des
données existantes lors d'un re-run depuis V13).

`StoryProgressRepository` réécrit : `find`/`findAll` renvoient désormais un `StoryProgressRecord
(StoryState state, int currentIndex)` plutôt qu'un simple `StoryState` ; `upsertState` remplacé par
`upsertProgress` (écrit toujours les deux colonnes ensemble, jamais de mise à jour partielle).

### `StoryService` — progression automatique

- **Cache mémoire des Stories `ACTIVE` par joueur** (`Map<UUID, Map<String, ActiveStoryProgress>>`),
  chargé à la connexion (`loadForPlayer`), vidé à la déconnexion (`unloadForPlayer`) — même
  conception que `QuestProgressEngine#activeByPlayer`. Nouvelle classe `ActiveStoryProgress`
  (position mutable), nouveau `StoryConnectionListener` (copie du patron
  `QuestProgressConnectionListener`).
- **`onQuestProgressChanged(UUID)`** — se déplace systématiquement sur le thread principal avant
  toute action (`runTask`), y compris quand la notification arrive elle-même d'un autre thread
  (`QuestProgressEngine#accept` complète parfois son `CompletableFuture` sur le thread exécuteur de
  la base de données, jamais garanti être le thread principal). Pour chaque Story active du joueur,
  vérifie si la quête courante est `COMPLETED` et, si oui, avance.
- **`advanceStory`** — idempotence par une seule vérification directe (« l'index en mémoire
  pointe-t-il encore vers la quête dont je viens de constater la complétion ? »), pas de verrou
  séparé — même principe que la garde déjà utilisée par `QuestProgressEngine#turnIn`. Un appel
  concurrent qui a déjà fait avancer l'index voit cette vérification échouer et ne rejoue rien.
  Aucune récompense n'est distribuée par la Story (elle n'appelle que `accept()`, jamais `turnIn`),
  donc rien à dédupliquer de ce côté.
- **`start`** — persiste toujours immédiatement, même hors ligne ; ne démarre effectivement la
  première quête (`accept()`) que si la cible est **actuellement en ligne** — sinon la première
  quête démarre automatiquement à la prochaine connexion.
- **Auto-guérison à la reconnexion (`loadForPlayer`)** — ne se contente pas de relire l'index
  persisté : vérifie l'état réel de la quête courante et rattrape trois anomalies possibles (jamais
  acceptée → démarrée ; déjà `COMPLETED`, ex. crash entre la complétion et l'avancement Story → la
  Story avance immédiatement ; `ABANDONED`/`FAILED` → re-démarrée). C'est ce mécanisme qui garantit
  la reprise correcte après un redémarrage serveur en pleine quête intermédiaire.
- **Feedback dans le chat, pas en Title/Subtitle** — décision délibérée : `QuestProgressEngine
  #accept` affiche déjà son propre Title « Quête commencée » à chaque démarrage de quête. Un second
  Title programmé séparément (deux `CompletableFuture` distincts, sans garantie d'ordre
  d'exécution fiable entre eux) créerait une course d'affichage où l'un écrase l'autre de façon
  imprévisible. Le chat n'a pas ce problème (les messages s'empilent) et garde un historique
  consultable du fil de la Story.

### `resetwithquests` (mission point 5)

Nouvelle sous-commande admin, séparée de `reset` (qui reste inchangé — ne touche jamais aux
quêtes). `resetwithquests <joueur> <storyId>` réinitialise la Story **et**, une par une, chacune des
quêtes qu'elle référence, en réutilisant tel quel `QuestProgressEngine#resetQuest` (déjà garanti de
ne jamais toucher aux autres quêtes du joueur) — aucune nouvelle logique de suppression de quête
écrite, pure réutilisation. Pas de variante `... all` : cible toujours une seule Story précisément.

### `/rpgadmin story info` enrichi

Affiche désormais, pour une Story `ACTIVE`, l'id de la quête courante et sa position (`n/total`) —
directement utile pour vérifier le comportement du reset/de la reprise sans avoir à consulter la
base à la main.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/story/ActiveStoryProgress.java`
- `src/main/java/com/lodygames/rpgquest/story/StoryConnectionListener.java`
- `docs/manual-tests/stories/story_test.yml` (fixture de test, réutilise 3 quêtes de test déjà
  existantes dans `docs/manual-tests/quests/`)
- `docs/claude-reports/2026-08-21_2135_story-automatic-progression.md` (ce rapport)

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/story/StoryService.java` — réécriture substantielle (voir
  « Travail effectué »).
- `src/main/java/com/lodygames/rpgquest/database/StoryProgressRepository.java` — `current_index`,
  `StoryProgressRecord`, `upsertProgress`.
- `src/main/java/com/lodygames/rpgquest/database/SchemaMigrator.java` — migration V14.
- `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java` — `resetwithquests`, `info`
  enrichi, tab-complete.
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — nouvelle construction de
  `StoryService` (dépendances supplémentaires), enregistrement du connection listener, branchement
  sur `QuestProgressEngine#onProgressChanged`.
- `src/main/resources/messages.yml` — section `story:` (3 clés).
- `src/test/java/com/lodygames/rpgquest/database/SchemaMigratorTest.java` — version bumpée à 14,
  deux nouveaux tests (colonne présente, préservation des données lors d'un re-run).
- `src/test/java/com/lodygames/rpgquest/database/StoryProgressRepositoryTest.java` — réécrit pour la
  nouvelle forme de `StoryProgressRecord`.
- `src/test/java/com/lodygames/rpgquest/story/StoryServiceTest.java` — réécrit intégralement
  (MockBukkit désormais, contre du JUnit pur avant).
- `docs/storylines.md`, `docs/RPGQUEST_BIBLE.md`, `docs/ARCHITECTURE.md`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` — voir « Documentation mise à jour ».

## Base de données / migrations

- **Migration ajoutée** : V14 (`SchemaMigrator.CURRENT_VERSION` 13 → 14).
- **Table concernée** : `story_progress` — ajout de la colonne `current_index INTEGER NOT NULL
  DEFAULT 0` via `ALTER TABLE` (vérifiée idempotente : ne s'exécute que si la colonne n'existe pas
  déjà).
- **Compatibilité avec la DB existante** : totale. Toute ligne `story_progress` déjà présente (créée
  uniquement par `/rpgadmin story start`, toujours à l'index de départ) reçoit `current_index = 0`
  par le `DEFAULT`, ce qui est exactement la valeur correcte pour une Story qui vient de démarrer.
  Aucune donnée existante perdue ou altérée (test dédié : `migratingFromV13PreservesExistingStoryProgressRowsAndDefaultsCurrentIndexToZero`).

## Configuration / données

- `src/main/resources/messages.yml` — ajout de la section `story:` (`started`, `next-objective`,
  `completed`), 3 nouvelles clés MiniMessage, placeholders `<story>`/`<quest>`.
- `docs/manual-tests/stories/story_test.yml` — nouvelle fixture de test (non déployée par défaut),
  référence 3 quêtes de test déjà existantes dans `docs/manual-tests/quests/` (aucune nouvelle
  quête créée).

## Tests automatiques

- Commande exécutée : `./gradlew clean build` (jamais `-x test`).
- Résultat : `BUILD SUCCESSFUL in 2m 14s`.
- Nouveaux tests ajoutés pour cette étape : 14 dans `StoryServiceTest` (réécriture complète) + 2
  dans `SchemaMigratorTest` = **16 nouveaux tests**, plus la réécriture complète de
  `StoryProgressRepositoryTest` (7 tests, structure adaptée à `StoryProgressRecord`).
- Nombre total (dernier build complet, module principal) : **780 tests, 0 échec, 11 ignorés**
  (`skipped`, préexistants, sans rapport avec cette étape).
- `StoryServiceTest` couvre explicitement : démarrage + activation index 0, première quête
  auto-acceptée, avancement automatique + démarrage auto de la quête suivante, chaîne complète
  jusqu'à `COMPLETED`, absence de ré-avancement après complétion (`aCompletedStoryNeverAdvancesFurther`),
  absence de double avancement sur notifications répétées, reprise après reconnexion (index +
  quête courante), reprise après une complétion survenue hors ligne (auto-guérison), `reset` (ne
  touche jamais `quest_progress`), `resetwithquests` (touche les quêtes de la Story, jamais une
  quête hors story), rejouabilité après `resetwithquests`, deux Stories indépendantes pour le même
  joueur, quête hors de toute Story active (deux variantes : jamais référencée par une Story, et
  référencée mais par une Story non démarrée).
- Deux échecs intermédiaires rencontrés et corrigés pendant cette même session (avant le build final
  ci-dessus) : (1) le bug de migration `ALTER TABLE` déjà décrit, révélé par un test préexistant ;
  (2) un test (`resetWithQuestsClearsTheStoryAndAllItsQuestsButNeverAnUnrelatedQuest`) bloquait
  indéfiniment (`TimeoutException`) car `QuestProgressEngine#forceComplete` sur une quête jamais
  acceptée planifie son travail via le scheduler Bukkit (tick suivant), qui n'avance jamais tout
  seul sous un `.get()` bloquant sans faire progresser MockBukkit en parallèle — corrigé en
  acceptant la quête d'abord (rend `forceComplete` synchrone).

## Tests manuels à effectuer

Voir `docs/MANUAL_TEST_PLAN.md`, **TC-200** pour la procédure numérotée complète. Résumé :

1. Copier `docs/manual-tests/quests/test_break_block.yml`, `test_place_block.yml`,
   `test_collect_item.yml` → `plugins/RPGQuest/quests/`.
2. Copier `docs/manual-tests/stories/story_test.yml` → `plugins/RPGQuest/stories/`.
3. `/rpgquest reload` (ou redémarrage).
4. `/rpgadmin story start <joueur> story_test`.
5. En jeu : casser 3 blocs de terre → poser 3 blocs de terre (sans commande) → ramasser 5 bâtons
   (sans commande) → vérifier `/rpgadmin story info <joueur>` affiche `COMPLETED`.
6. Vérifier les messages de chat exacts à chaque transition (« Nouvelle aventure », « Nouvel
   objectif » ×2, « Aventure terminée »).
7. Tester la reprise (interrompre en cours de quête 2, redémarrer le serveur, reconnecter, vérifier
   que la quête en cours est toujours active).
8. Tester `/rpgadmin story resetwithquests <joueur> story_test` puis relancer.

## Résultat attendu

Le joueur enchaîne les 3 quêtes de test sans jamais taper de commande entre elles ; chaque
transition affiche le message de chat correspondant ; `/rpgadmin story info` reflète l'état exact
à tout moment ; un redémarrage serveur en pleine partie ne casse rien ; `resetwithquests` permet de
tout rejouer proprement sans toucher à rien d'autre.

## Reset / retour à l'état initial

Toujours privilégier le reset ciblé, jamais un wipe de `data.db` :

```
/rpgadmin story resetwithquests <joueur> story_test
```

Remet `story_test` et ses 3 quêtes en `NOT_STARTED` pour ce joueur précisément — n'affecte ni
l'inventaire, ni l'économie, ni les autres quêtes/Stories du même joueur, ni aucun autre joueur.
Pour repartir des *définitions* elles-mêmes (rare, pas nécessaire pour rejouer le test) : supprimer
les 4 fichiers copiés en précondition puis redémarrer/recharger — ne supprime jamais
`story_progress` par ce biais, seulement les définitions rechargées en mémoire.

## Déploiement VeryGames

### À transférer
- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (nom de version exact selon `./gradlew clean build`).

### Ne PAS transférer/altérer
- `data.db` — la migration V14 s'applique automatiquement au démarrage, jamais besoin d'y toucher
  à la main.
- `plugins/RPGQuest/stories/main_story.yml` (l'exemple embarqué existant) et tout autre fichier de
  Story/quête déjà en place — non concernés par cette étape.
- Mondes (`world_hub`, etc.), `Citizens/saves.yml`, données joueurs locales, logs, caches — aucun
  rapport avec cette étape.
- Les 4 fichiers de fixture de test (`docs/manual-tests/quests/test_break_block.yml`,
  `test_place_block.yml`, `test_collect_item.yml`, `docs/manual-tests/stories/story_test.yml`) — à
  copier **uniquement** pour la durée du test manuel (voir TC-200), jamais laissés en place de façon
  permanente en production.

### Redémarrage requis
Oui — migration de schéma (V14).

### Migration automatique
Oui — `SchemaMigrator` applique `ALTER TABLE story_progress ADD COLUMN current_index ...`
automatiquement au démarrage, idempotente (vérifie que la colonne n'existe pas déjà avant de
l'ajouter, donc sans risque même si le processus de migration était rejoué).

## Rollback

Remettre l'ancien `rpgquest-*.jar` sauvegardé avant remplacement (voir « Mise à jour du seul JAR
RPGQuest » dans `docs/deployment/VERYGAMES.md`) puis redémarrer. La colonne `current_index` ajoutée
par la V14 reste inoffensive pour l'ancienne version du plugin (elle l'ignore simplement, aucune
requête de l'ancienne version ne la référence).

## Logs / diagnostic

- Aucun nouveau préfixe de log dédié à cette étape (contrairement à `[TP-TRACE]` de la session
  précédente, sans rapport avec ce travail).
- Journalisation `INFO` existante étendue : `logger.info("Story « {} » démarrée pour {} ({}).")`
  (inchangé), plus de nouvelles lignes similaires pour l'avancement (`Story avancée`/`Story terminée`
  implicitement via les mêmes logs existants réutilisés) et pour `resetwithquests`.
- Commandes de diagnostic : `/rpgadmin story info <joueur>` (affiche désormais aussi la quête
  courante), `/quest progress` (côté joueur, pour vérifier l'état de la quête en cours).
- Aucune ligne de log spécifique à récupérer en cas de problème — en cas d'anomalie de progression,
  comparer `/rpgadmin story info <joueur>` avec `/quest progress` du joueur concerné pour voir si
  l'index Story et l'état de quête réel divergent (cela ne devrait jamais arriver, l'auto-guérison à
  la reconnexion est censée corriger tout écart).

## Documentation mise à jour

- `docs/storylines.md` — réécriture substantielle : progression automatique, idempotence, reprise
  après reconnexion, feedback chat, `resetwithquests`, scénario de test, déploiement.
- `docs/RPGQUEST_BIBLE.md` — section 21 (Storylines) mise à jour (tableau des commandes,
  description de la progression automatique).
- `docs/ARCHITECTURE.md` — section `story` réécrite (décisions techniques : cache mémoire,
  idempotence, thread principal, feedback chat, migration V14, `resetwithquests`).
- `docs/current_state.md` — bullet Storyline mis à jour ; section WorldPortal renommée en « Bug
  résolu » (le bug `hub_to_claims` était une mauvaise sélection de zone, confirmé résolu par
  l'utilisateur, code `travel` non touché conformément à la consigne) ; version de schéma 13 → 14 ;
  liste « Non implémenté » mise à jour.
- `docs/MANUAL_TEST_PLAN.md` — nouveau TC-200 (procédure complète), TC-190 corrigé (mention du bug
  désormais résolu), ligne ajoutée à la table de recette.

## Limitations / travail restant

- Le rendu exact des messages de chat (ordre visuel « Nouvel objectif » avant/après le Title
  « Quête commencée » de la nouvelle quête) n'a pas pu être vérifié visuellement en jeu (aucun
  client Minecraft dans cet environnement) — la logique d'ordonnancement des messages a été
  raisonnée et documentée, mais reste à confirmer par un testeur humain (voir TC-200).
- `resetwithquests` ne propose pas de variante `... all` (toutes les Stories d'un joueur, avec
  toutes leurs quêtes) — décision délibérée (mission : outil *ciblé*), mais si un besoin de reset
  massif de test apparaît, il faudra l'ajouter explicitement, pas l'assumer implicitement dans
  `reset ... all`.
- Aucune UX joueur (GUI, notification enrichie) au-delà du chat minimal demandé — explicitement hors
  périmètre de cette étape.
- Chapitres, récompenses de palier, i18n effective : toujours non câblés (le modèle reste posé pour
  les accueillir sans nouvelle migration, voir `docs/storylines.md`).

## Prochaine étape suggérée

Valider TC-200 sur VeryGames avec un vrai joueur, puis décider laquelle des extensions volontairement
exclues de cette étape (chapitres, récompenses de palier, PNJ Story du Wild, etc.) traiter ensuite —
aucune n'a été commencée automatiquement.
