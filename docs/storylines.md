# Storylines (moteur de Storyline)

Un conteneur logique **ordonné** de quêtes existantes, avec sa propre progression par joueur.
Depuis cette étape, une Story `ACTIVE` **avance toute seule** : sa quête courante démarre
automatiquement, une complétion fait avancer la Story vers la quête suivante (démarrée
automatiquement à son tour), et la dernière complétion passe la Story à `COMPLETED` — le tout
**sans aucune commande joueur ni interaction PNJ entre deux quêtes** (mission « Storyline jouable
de bout en bout »).

## Ce qui a changé par rapport à l'étape précédente

L'étape précédente décrivait `StoryService` comme « délibérément indépendant du moteur de quête ».
Ce n'est plus le cas : connecter les deux moteurs est précisément l'objet de cette étape.
`StoryDefinition.questIds()` reste une simple liste de références jamais **validée au
chargement** (voir `StoryDefinitionParser` — une Story peut toujours référencer un id de quête pas
encore chargé, sans erreur), mais elle est désormais **résolue à l'exécution** contre
`quest.progress.QuestProgressEngine`/`quest.YamlQuestEngine`.

Toujours **pas implémenté** à cette étape (mission, section 4) : chapitres, récompenses de palier
**natives à la Story**, agrandissement de claim au-delà du premier (TIER_1), équipement légendaire,
points de compétence, PNJ Story du Wild, GUI Story.

Un premier exemple concret de « déblocage » via une Story existe désormais (mission « premier claim
5×5 ») : `crystal_hunt` (dernière quête de `main_story`) porte une récompense `VARIABLE` qui
débloque `CLAIM_TIER_1`, prérequis vérifié par `claim.ClaimService` pour le premier claim d'un
joueur — voir [docs/CLAIMS.md](CLAIMS.md). Ce n'est pas une récompense **native** de `StoryService`
(toujours accrochée à la dernière quête, comme n'importe quelle récompense `VARIABLE` de quête),
mais démontre que le mécanisme de déblocage fonctionne de bout en bout.

## Fichiers de définition

Inchangé — un fichier YAML par Story, dans `plugins/RPGQuest/stories/` (créé automatiquement au
premier démarrage, avec un exemple `main_story.yml` généré si absent — jamais réécrit ensuite).

```yaml
id: main_story
name: "Histoire principale"
quests:
  - rpgquest:premiers_pas
  - rpgquest:first_steps
  - rpgquest:crystal_hunt
```

- `id` — stable, sert de clé de persistance (`story_progress.story_id`). Ne jamais renommer un id
  de Story déjà utilisé en production (voir « Renommer un id » plus bas).
- `name` — texte affiché par `/rpgadmin story info` **et** dans le message de chat « Nouvelle
  aventure » (via `LocalizedText`, la même brique i18n que les titres de quête — supporte le
  MiniMessage, ex. `"<red>[TEST]</red> Histoire de test"`).
- `quests` — liste **ordonnée** d'id de quête, c'est cet ordre qui détermine la progression
  automatique. Un id sans `:` est complété avec le namespace `rpgquest:` par défaut.

## État de progression (par joueur, par Story)

```
NOT_STARTED  →  ACTIVE  →  COMPLETED
```

Persisté dans `story_progress` (`state`, et depuis cette étape `current_index` — la position 0-based
dans `questIds()` de la quête actuellement suivie). `NOT_STARTED` n'est jamais persisté (absence de
ligne). `current_index` n'a de sens que pendant que `state = ACTIVE` — une fois `COMPLETED`, sa
valeur (= `questIds().size()`) n'est plus jamais relue comme un index de quête.

## Progression automatique

### Démarrage

`/rpgadmin story start <joueur> <storyId>` (ou toute future UX qui appellera `StoryService#start`)
persiste immédiatement `ACTIVE`/`current_index=0`, **même si le joueur est hors ligne**. La
première quête ne démarre effectivement (`QuestProgressEngine#accept`) que si le joueur est
**actuellement en ligne** — sinon elle démarre automatiquement à sa prochaine connexion (voir
« Reprise après reconnexion/redémarrage »). Aucune commande joueur n'est jamais nécessaire, dans
un cas comme dans l'autre.

### Avancement

`StoryService#onQuestProgressChanged` est branché sur `QuestProgressEngine#onProgressChanged`
(même patron que `progression.listener.QuestCompletionXpListener`, déjà existant pour l'XP de
palier) — notifié après **toute** mutation de progression de quête d'un joueur, pas seulement une
fin. À chaque appel, pour chaque Story `ACTIVE` de ce joueur : si la quête courante vient de passer
`COMPLETED`, la Story avance d'une position et démarre automatiquement la quête suivante (message
de chat « Nouvel objectif »), ou passe à `COMPLETED` si c'était la dernière (message « Aventure
terminée »).

### Idempotence (mission, section 3)

Aucun verrou explicite n'est nécessaire : la garde tient dans une seule vérification, exécutée sur
le thread principal juste avant d'avancer — *« l'index en mémoire pointe-t-il encore vers la quête
que je viens de voir terminée ? »*. Si un appel concurrent (deux notifications pour la même
complétion, ou l'auto-guérison au rechargement qui arrive au même moment) a déjà fait avancer
l'index entre-temps, cette vérification échoue et rien n'est refait — même principe que la garde
`if (progress.state() == QuestState.COMPLETED) return;` déjà utilisée par
`QuestProgressEngine#turnIn`. Aucune récompense n'est distribuée par la Story elle-même : elle ne
fait qu'appeler `QuestProgressEngine#accept`, qui ne distribue jamais de récompense (uniquement à
la remise, déjà protégée par sa propre garde) — rien à dédupliquer côté Story de ce point de vue.

### Reprise après reconnexion/redémarrage (mission, section 3)

`StoryService#loadForPlayer` (appelé à la connexion, voir `StoryConnectionListener`) recharge les
Stories `ACTIVE` depuis la base puis vérifie l'état réel de la quête courante de chacune :

| État constaté de la quête courante | Action |
|---|---|
| Jamais acceptée (`NOT_STARTED`) | Démarrée automatiquement (`accept`). |
| `ACTIVE`/`READY_TO_TURN_IN` | Rien à faire, déjà en cours. |
| `COMPLETED` (ex. crash entre la complétion et l'avancement Story) | La Story avance immédiatement, comme si la notification venait d'arriver. |
| `ABANDONED`/`FAILED` (ex. `/quest abandon` manuel sur une quête suivie par une Story) | Traité comme jamais acceptée, re-démarrée. |

Une Story `ACTIVE` reprend donc toujours correctement, y compris après un redémarrage complet du
serveur en pleine partie d'une quête intermédiaire.

### Une quête reste utilisable indépendamment d'une Story

Rien dans `QuestProgressEngine` n'est modifié par cette étape : `/quest accept`, les objectifs, les
récompenses, `/quest complete` fonctionnent exactement comme avant, qu'une quête soit référencée
par une Story ou non, qu'une Story la suivant soit active ou non. Une Story ne réagit **qu'aux**
quêtes qu'elle référence, et **uniquement** si elle est `ACTIVE` (le cache mémoire ne contient que
les Stories actives d'un joueur — une quête terminée hors du champ d'une Story `NOT_STARTED` ne
déclenche jamais rien).

## Feedback joueur

Envoyé dans le **chat** (jamais en Title/Subtitle comme les messages de quête) — voir
`messages.yml`, section `story:` :

| Événement | Message (`messages.yml`) |
|---|---|
| Story démarrée | `story.started` → « Nouvelle aventure : *nom* » |
| Avancement vers la quête suivante | `story.next-objective` → « Nouvel objectif : *titre de la quête* » |
| Story terminée | `story.completed` → « Aventure terminée : *nom* » |

**Pourquoi le chat et pas un Title**, alors que les quêtes utilisent un Title/Subtitle ? —
`QuestProgressEngine#accept` affiche déjà son propre Title « Quête commencée » à **chaque**
démarrage de quête (via une Story ou non). Empiler un second Title juste avant/après créerait une
course d'affichage : le second appel à `Player#showTitle` écrase toujours le premier, sans garantie
fiable de l'ordre entre deux `CompletableFuture` distincts complétés indépendamment. Le chat n'a pas
ce problème (les messages s'empilent, jamais ne s'écrasent) et garde un historique consultable du
fil de la Story dans la fenêtre de discussion du joueur.

## Persistance

Table SQLite `story_progress` (migration `SchemaMigrator` V13, colonne `current_index` ajoutée en
V14) :

```sql
CREATE TABLE story_progress (
    player_uuid TEXT NOT NULL,
    story_id TEXT NOT NULL,
    state TEXT NOT NULL,
    current_index INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (player_uuid, story_id),
    FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
)
```

`database.StoryProgressRepository` reste un JDBC pur, sans dépendance Bukkit — testable sans
MockBukkit. `story.StoryService` a désormais un **cache mémoire** des Stories `ACTIVE` par joueur
(`Map<UUID, Map<String, ActiveStoryProgress>>`), chargé à la connexion et vidé à la déconnexion —
même conception que `QuestProgressEngine#activeByPlayer` : c'est ce cache, pas la base, qui sert de
verrou anti-double-avance (voir « Idempotence » plus haut).

## Commandes (admin/debug uniquement)

Permission : `rpgquest.admin.world` (même permission que le reste de `/rpgadmin`).

**`/rpgadmin story` reste la seule branche de `/rpgadmin` utilisable depuis la console** — elle
cible un joueur passé en argument, jamais la position de l'exécutant. Le joueur ciblé peut être
**hors ligne** pour `info`/`start`/`reset`/`resetwithquests` (résolution asynchrone, jamais
bloquante sur le thread principal) — seul le déclenchement effectif de la première quête (via
`accept`) attend que le joueur soit réellement connecté (voir « Démarrage » plus haut).

| Commande | Effet |
|---|---|
| `/rpgadmin story info <joueur>` | Liste toutes les Stories connues, leur état, et — si `ACTIVE` — la quête courante (id + position `n/total`). |
| `/rpgadmin story start <joueur> <storyId>` | Passe la Story en `ACTIVE`, démarre sa première quête. Refusé si id inconnu, déjà `ACTIVE`, ou déjà `COMPLETED` (réinitialiser d'abord). |
| `/rpgadmin story reset <joueur> <storyId\|all>` | Supprime la progression d'**une** Story, ou de **toutes** (`all`). Ne touche **jamais** `quest_progress` — voir la section suivante. |
| `/rpgadmin story resetwithquests <joueur> <storyId>` | Comme `reset`, **et** réinitialise chacune des quêtes de cette Story via `QuestProgressEngine#resetQuest` — jamais les autres quêtes du joueur, jamais `reset ... all` en une fois. |

**Important — l'UX finale ne repose pas sur ces commandes** : elles sont strictement admin/debug
(tests, support, scripts de démonstration, préparation d'une démo). Aucune commande joueur n'existe
ni n'est prévue.

### `reset` vs `resetwithquests` (mission, section 5)

`/rpgadmin story reset` **ne devine jamais** qu'un reset Story doit aussi remettre les quêtes à
zéro — il ne touche que `story_progress` de la Story ciblée (ou de toutes, avec `all`), jamais
`quest_progress`, jamais l'inventaire, jamais l'économie. Une Story réinitialisée avec `reset` seul
redevient `NOT_STARTED`, mais ses quêtes gardent leur état réel (ex. `quest_one` reste `COMPLETED`
si le joueur l'avait terminée) — redémarrer la Story avec `start` réévaluera alors sa première
quête, qui pourrait déjà être marquée terminée si non répétable (voir plus bas).

`/rpgadmin story resetwithquests <joueur> <storyId>` est l'outil ciblé demandé pour permettre de
**rejouer un scénario de test** proprement : il réinitialise la Story ET, une par une, exactement
les quêtes qu'elle référence — via `QuestProgressEngine#resetQuest` (déjà garanti de ne jamais
toucher aux autres quêtes du joueur). Aucune variante `... all` pour cette commande : elle cible
toujours une seule Story précisément, jamais un wipe global de quêtes.

**Piège à connaître** : si une quête référencée par une Story a `repeatable: false` et a déjà été
terminée par le joueur **avant même que la Story n'existe/la référence** (progression legacy), un
simple `reset`/`resetwithquests` de la Story ne débloque rien tant que la quête reste
`repeatable: false` — `QuestProgressEngine#accept` refusera toujours avec `NOT_REPEATABLE`. Toutes
les quêtes du fixture de test fourni (voir plus bas) sont `repeatable: true` précisément pour éviter
ce piège lors des tests répétés.

## Extensibilité prévue (toujours pas implémentée)

- **Chapitres** — `current_index` pose déjà la brique nécessaire (position dans une liste ordonnée) ;
  une notion de chapitre grouperait des sous-séquences de `questIds()` sans nouvelle migration.
- **Récompenses de palier / déblocages** — un futur point d'accroche naturel est `advanceStory`
  (dans `StoryService`), au moment précis où l'état passe à `COMPLETED`.
- **i18n** — toujours prête (`LocalizedText`), toujours pas câblée.

## Renommer un id de Story

Inchangé : éditer `id:` ne migre jamais les lignes `story_progress` existantes. Prévoir un
`/rpgadmin story reset <joueur> all` pour les joueurs concernés, ou une migration manuelle SQL.

## Scénario de test manuel (fixtures, mission section 6)

Une petite Story de test est fournie, **non déployée par défaut** — même conception que le pack de
quêtes de test manuel déjà existant (`docs/manual-tests/quests/`, voir `docs/MANUAL_TEST_PLAN.md`).
Elle réutilise volontairement 3 des quêtes de test déjà présentes (aucune coordonnée ni PNJ fixe,
réalisable n'importe où sur le serveur) :

- `docs/manual-tests/stories/story_test.yml` — id `story_test`, référence dans l'ordre
  `rpgquest:test_break_block` → `rpgquest:test_place_block` → `rpgquest:test_collect_item`.
- `docs/manual-tests/quests/test_break_block.yml`, `test_place_block.yml`, `test_collect_item.yml`
  — déjà existants, `repeatable: true`, aucune modification nécessaire.

**Fichiers à copier pour tester** (jamais en production permanente) :
1. Les 3 fichiers de quête ci-dessus → `plugins/RPGQuest/quests/`.
2. `docs/manual-tests/stories/story_test.yml` → `plugins/RPGQuest/stories/`.
3. `/rpgquest reload` (ou redémarrage) pour charger les deux.

Voir `docs/MANUAL_TEST_PLAN.md`, TC-200, pour la procédure de test complète (admin démarre/reset,
joueur enchaîne les 3 quêtes sans aucune commande entre elles).

## Déploiement VeryGames — fichiers exacts

- Le nouveau JAR RPGQuest (contient l'exemple embarqué `stories/main_story.yml`, généré
  automatiquement au premier démarrage si absent).
- **Rien d'autre à copier pour le fonctionnement normal** : la colonne `current_index` est ajoutée
  automatiquement au démarrage par `SchemaMigrator` (migration V14, idempotente — un `ALTER TABLE`
  qui vérifie d'abord que la colonne n'existe pas avant de l'ajouter, donc sans risque même si la
  migration est rejouée).
- **Pour le scénario de test manuel uniquement** (jamais en usage permanent) : les 4 fichiers listés
  ci-dessus (« Scénario de test manuel »), à retirer après le test.
- Redémarrage complet du serveur requis (migration de schéma).

### Reset de test (à ne PAS faire en production sans le vouloir)

Pour rejouer le scénario de test depuis zéro :

```
/rpgadmin story resetwithquests <joueur> story_test
```

Une seule commande, ciblée : remet la Story `story_test` en `NOT_STARTED` **et** les 3 quêtes
qu'elle référence en `NOT_STARTED`, sans toucher à l'inventaire, l'économie, ni aux autres quêtes du
joueur (ex. `first_steps`/`crystal_hunt` en cours restent intactes). Privilégier systématiquement
cette commande à un wipe de `data.db` — jamais nécessaire pour ce scénario.

Pour repartir des définitions elles-mêmes (rare) : supprimer
`plugins/RPGQuest/stories/story_test.yml` et les 3 fichiers de quête correspondants, redémarrer.
