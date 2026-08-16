# Storylines (moteur de Storyline)

Un conteneur logique **ordonné** de quêtes existantes, avec sa propre progression par joueur —
délibérément **indépendant** du moteur de quête (`quest.progress.QuestProgressEngine`) : le moteur
de Storyline ne connaît jamais l'état interne d'une quête, il se contente de référencer des id de
quête comme de simples chaînes/`NamespacedKey`, jamais résolues contre `YamlQuestEngine` au
chargement. Aucun couplage dans l'autre sens non plus : le moteur de quête ignore totalement
l'existence des Stories.

Cette étape est **minimale** : chargement des définitions, état par joueur
(`NOT_STARTED`/`ACTIVE`/`COMPLETED`), et trois commandes admin/debug. Il n'y a **pas** encore de
progression automatique (une quête terminée ne fait pas avancer une Story toute seule), pas de
chapitres, pas de récompenses de palier — ces points sont explicitement prévus comme extensions
futures (voir « Extensibilité prévue » plus bas), pas implémentés ici.

## Fichiers de définition

Un fichier YAML par Story, dans `plugins/RPGQuest/stories/` (créé automatiquement au premier
démarrage, avec un exemple `main_story.yml` généré si absent — jamais réécrit ensuite, comme les
zones d'exemple).

```yaml
id: main_story
name: "Histoire principale"
quests:
  - rpgquest:premiers_pas
  - rpgquest:first_steps
  - rpgquest:crystal_hunt
```

- `id` — stable, sert de clé de persistance (`story_progress.story_id`). Minuscules, chiffres,
  `_` et `-` uniquement (même contrainte que les zones/portails). Ne jamais renommer un id de Story
  déjà utilisé en production : les lignes de progression existantes resteraient orphelines (état
  invisible, pas supprimé — voir « Renommer un id » plus bas).
- `name` — texte affiché par `/rpgadmin story info` (via `LocalizedText`, la même brique i18n que
  les titres de quête — prête pour la résolution par langue quand elle sera câblée, pas encore
  utilisée pour choisir une langue à cette étape).
- `quests` — liste ordonnée d'id de quête. Un id sans `:` est complété avec le namespace
  `rpgquest:` par défaut (même règle que les prérequis de quête et `required-quest` d'un portail).
  **Jamais validé contre les quêtes réellement chargées** au moment du parsing — une Story peut
  référencer un id de quête qui n'existe pas (encore), sans erreur de chargement. C'est un choix
  délibéré pour ne dépendre d'aucun ordre de démarrage entre les deux moteurs.

Un id de Story dupliqué entre deux fichiers rejette les deux (comme les zones/portails). Un fichier
invalide est journalisé et ignoré, sans bloquer le chargement des autres.

**Aucune commande `/rpgadmin story create`/`delete`** à cette étape : une Story se crée/supprime en
éditant les fichiers YAML puis en redémarrant (ou via `/rpgadmin story` — le rechargement à chaud
n'est pas exposé non plus, pour rester minimal).

## État de progression (par joueur, par Story)

```
NOT_STARTED  →  ACTIVE  →  COMPLETED
```

- `NOT_STARTED` n'est **jamais persisté** : l'absence de ligne dans `story_progress` pour un couple
  joueur+story en tient lieu (même convention que `quest_progress`/`QuestState`).
- `ACTIVE` est atteint uniquement par `/rpgadmin story start`.
- `COMPLETED` n'est pas encore atteignable par aucune commande ni par aucun événement de jeu à
  cette étape (`StoryService#markCompleted` existe déjà côté code, pour une étape future qui
  câblera l'auto-complétion, mais rien ne l'appelle pour l'instant).
- Chaque joueur a une progression **strictement indépendante** par Story (clé de persistance
  `(player_uuid, story_id)`) — démarrer/terminer/réinitialiser une Story n'affecte jamais les
  autres Stories du même joueur, ni la progression d'un autre joueur sur la même Story.

## Persistance

Table SQLite `story_progress` (migration `SchemaMigrator` V13) :

```sql
CREATE TABLE story_progress (
    player_uuid TEXT NOT NULL,
    story_id TEXT NOT NULL,
    state TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (player_uuid, story_id),
    FOREIGN KEY (player_uuid) REFERENCES player_profiles (uuid) ON DELETE CASCADE
)
```

`database.StoryProgressRepository` est un JDBC pur, sans dépendance Bukkit — testable sans
MockBukkit (voir `StoryProgressRepositoryTest`). `story.StoryService` n'a **aucun cache mémoire** :
chaque commande admin lit/écrit directement la base — un choix délibéré, cet outil n'étant pas un
chemin chaud comme `QuestProgressEngine` (consulté à chaque `PlayerMoveEvent`/événement de jeu).

## Commandes (admin/debug uniquement)

Permission : `rpgquest.admin.world` (même permission que le reste de `/rpgadmin`).

**`/rpgadmin story` est la seule branche de `/rpgadmin` utilisable depuis la console** — elle cible
un joueur passé en argument, jamais la position de l'exécutant, donc aucune raison d'exiger un
joueur en jeu. Le joueur ciblé peut être **hors ligne** (résolution asynchrone, jamais bloquante sur
le thread principal, même patron que `/rpgquest profile <joueur>`) ; son profil (`player_profiles`)
est créé au besoin par `start`, condition requise par la clé étrangère de `story_progress`.

| Commande | Effet |
|---|---|
| `/rpgadmin story info <joueur>` | Liste toutes les Stories connues et leur état pour ce joueur. |
| `/rpgadmin story start <joueur> <storyId>` | Passe la Story en `ACTIVE`. Refusé si id inconnu, si déjà `ACTIVE`, ou si déjà `COMPLETED` (réinitialiser d'abord). |
| `/rpgadmin story reset <joueur> <storyId\|all>` | Supprime la progression d'**une** Story, ou de **toutes** (`all`, insensible à la casse). |

**Important — l'UX finale ne repose pas sur ces commandes** : elles sont strictement admin/debug
(tests, support, scripts de démonstration). Aucune commande joueur n'existe ni n'est prévue par
cette étape.

### Le reset est ciblé (mission, point 8)

`/rpgadmin story reset <joueur> <storyId>` supprime **uniquement** la ligne `story_progress`
correspondant à ce couple joueur+story — jamais les autres Stories du même joueur, jamais
`quest_progress`, jamais l'inventaire ni l'économie. `all` supprime toutes les lignes
`story_progress` de ce joueur (toujours seulement `story_progress`, rien d'autre) — voir
`StoryProgressRepository#deleteAllForPlayer`. Après un reset, la Story redevient `NOT_STARTED` et
peut être redémarrée proprement avec `/rpgadmin story start`.

`reset` sur un id de Story inconnu du registre est refusé (pas de suppression silencieuse par
faute de frappe) — seul le mot-clé réservé `all` échappe à cette vérification.

### Procédure de test manuel

```
/rpgadmin story info Steve
# -> "main_story (Histoire principale) : NOT_STARTED"

/rpgadmin story start Steve main_story
# -> "Story démarrée : main_story pour Steve"

/rpgadmin story start Steve main_story
# -> "Story déjà active pour Steve : main_story"

/rpgadmin story info Steve
# -> "main_story (Histoire principale) : ACTIVE"

/rpgadmin story reset Steve main_story
# -> "Story réinitialisée : main_story pour Steve"

/rpgadmin story info Steve
# -> "main_story (Histoire principale) : NOT_STARTED"

/rpgadmin story start Steve does_not_exist
# -> "Story inconnue : does_not_exist"

/rpgadmin story reset Steve all
# -> "Toute la progression Story de Steve a été réinitialisée."
```

Fonctionne aussi depuis la console (`Steve` hors ligne y compris — son profil est créé au premier
`start`).

## Extensibilité prévue (pas implémentée ici)

Le modèle est délibérément posé pour grandir sans migration de rupture :

- **Chapitres** — `StoryDefinition.questIds()` est déjà une liste *ordonnée* ; une notion de
  chapitre viendrait grouper des sous-séquences de cette liste, sans changer la forme de
  persistance (`story_progress` garde un état simple par story, pas encore de position/chapitre
  courant — une future étape pourrait ajouter une colonne `current_index` ou une table dédiée).
- **Récompenses de palier / déblocages** — `StoryService#markCompleted` existe déjà (état
  `COMPLETED` atteignable), prêt à être appelé par un futur écouteur de complétion de quête (ou
  autre déclencheur) sans changement de schéma.
- **i18n** — `StoryDefinition.name()` utilise déjà `quest.model.LocalizedText` (la même brique que
  les titres de quête) : aucun texte métier n'est codé en dur dans `StoryService`, tout le texte
  affiché au joueur vient soit de `StoryDefinition` (chargée depuis YAML), soit du niveau commande
  (`RpgAdminCommand`, jamais du service).

## Renommer un id de Story

Éditer `id:` dans un fichier YAML ne migre **jamais** les lignes `story_progress` existantes
(même limitation que renommer un id de quête/zone/portail) : les joueurs ayant démarré l'ancien id
resteraient bloqués dessus (invisible dans `/rpgadmin story info`, qui n'affiche que les id
actuellement chargés). Si un renommage est nécessaire en production, prévoir un
`/rpgadmin story reset <joueur> all` pour les joueurs concernés, ou une migration manuelle SQL sur
`story_progress.story_id`.

## Déploiement VeryGames — fichiers exacts

Ce qui doit être transféré sur le serveur de production pour cette étape (voir aussi la section
correspondante de `docs/current_state.md`) :

- Le nouveau JAR RPGQuest (contient l'exemple embarqué `stories/main_story.yml`, généré
  automatiquement dans `plugins/RPGQuest/stories/` au premier démarrage si absent — rien à copier à
  la main pour ça).
- **Rien d'autre à copier manuellement** : la table `story_progress` est créée automatiquement au
  démarrage par `SchemaMigrator` (migration V13, idempotente — un redémarrage sur une base déjà à
  jour ne fait rien).
- Redémarrage complet du serveur requis (comme pour toute mise à jour de JAR touchant le schéma de
  base de données).

### Reset de test (à ne PAS faire en production sans le vouloir)

Pour repartir d'un état Storyline totalement vierge en test (ex. avant une démo) :

1. `/rpgadmin story reset <joueur> all` pour chaque joueur de test concerné — targeted, ne touche
   à rien d'autre que `story_progress` de ce joueur.
2. Pour repartir des définitions elles-mêmes (pas seulement de la progression) : arrêter le
   serveur, supprimer `plugins/RPGQuest/stories/main_story.yml`, redémarrer (régénère l'exemple
   embarqué). Ne supprime **jamais** `story_progress` (données de progression et de définition sont
   deux choses distinctes).
