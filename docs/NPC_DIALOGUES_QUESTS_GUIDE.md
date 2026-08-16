# Guide pratique — PNJ, dialogues et quêtes

Ce guide décrit **uniquement ce qui existe réellement dans le code actuel**
de cette branche. Chaque mécanisme cité renvoie à la classe qui l'implémente.
Aucune commande ni aucun champ YAML n'est inventé : si quelque chose n'est
pas possible aujourd'hui, c'est dit explicitement en section 8.

Référence complémentaire : [QUEST_FORMAT.md](../QUEST_FORMAT.md) et
[DIALOGUE_FORMAT.md](../DIALOGUE_FORMAT.md) (fiches de champs courtes),
[docs/ARCHITECTURE.md](ARCHITECTURE.md) (rationale d'implémentation).

---

## 1. Comment un PNJ Citizens est relié à RPGQuest

**Citizens est désormais le système de PNJ pris en charge en priorité.**
RPGQuest dépend de `net.citizensnpcs:citizensapi` (compile-only ;
`softdepend: Citizens` dans `plugin.yml`) : si le plugin Citizens est
installé et actif, RPGQuest l'utilise automatiquement — rien à activer.
S'il est absent, RPGQuest continue de fonctionner avec de simples entités
vanilla marquées (voir « Entité vanilla ordinaire » plus bas) : aucune des
deux voies n'est requise pour que le plugin démarre.

### Quel identifiant RPGQuest utilise-t-il ?

Ni le nom du PNJ, ni son id numérique Citizens (`#3`, `#4`...) directement.
RPGQuest utilise **`NPC#getUniqueId()`** — l'UUID interne que Citizens
garantit lui-même stable pour un PNJ donné (contrairement à
`NPC#getId()`, dont la javadoc CitizensAPI précise explicitement qu'il
« n'est pas garanti unique entre sessions »). Cet UUID est mappé, dans la
base de données **de RPGQuest** (`data.db`, table `npc_citizens_bindings`),
vers l'identifiant logique que vous choisissez (`guide`, `libraire`...).

**Pourquoi pas directement sur l'entité Bukkit du PNJ, comme pour une
entité vanilla ?** Parce que Citizens **recrée une nouvelle entité Bukkit**
à chaque (re)spawn — y compris au redémarrage du serveur. Tout ce qui est
posé directement sur cette entité (comme un `PersistentDataContainer`) est
donc perdu au redémarrage suivant. C'était exactement le bug initial :
`/rpgadmin npc tag` fonctionnait dans la session en cours, puis
`/rpgadmin npc info` ne reconnaissait plus le PNJ après un redémarrage. Le
mapping vit maintenant dans les données de RPGQuest lui-même, jamais
seulement sur l'entité temporaire.

Implémentation : `com.lodygames.rpgquest.npc.NpcIdentityService` (façade
unique, comportement identique quel que soit le type de PNJ),
`com.lodygames.rpgquest.npc.CitizensNpcBridge` (seul point de contact avec
l'API Citizens), `com.lodygames.rpgquest.database.NpcBindingRepository`
(persistance SQLite du mapping).

### Commande : `/rpgadmin npc tag|untag|info`

**Syntaxe strictement inchangée** que le PNJ visé soit un PNJ Citizens ou
une entité vanilla — RPGQuest détecte automatiquement lequel des deux vous
regardez. Permission : `rpgquest.admin.world`. Cible toujours **l'entité
que vous regardez directement, à 6 blocs maximum**.

| Commande | Effet |
|---|---|
| `/rpgadmin npc tag` | Identifie le PNJ visé avec un id **auto-généré** (`npc_<n>`, séquentiel, jamais réutilisé). |
| `/rpgadmin npc tag <id>` | Identifie le PNJ visé avec l'id **de votre choix** (minuscules, chiffres, `.` `_` `-` uniquement). |
| `/rpgadmin npc untag` | Retire l'identifiant du PNJ visé (le PNJ Citizens lui-même n'est jamais supprimé, seul le mapping RPGQuest l'est). |
| `/rpgadmin npc info` | Affiche l'identifiant actuel du PNJ visé — pour un PNJ Citizens, affiche en plus son id numérique entre parenthèses, ex. `guide (Citizens NPC #3)`. |

- `tag` est **idempotent** : si le PNJ est déjà identifié, la commande ne
  change rien et vous rappelle l'id existant — il faut d'abord `untag` pour
  en choisir un autre.
- Aucune liste globale des PNJ identifiés n'existe : il faut viser
  physiquement le PNJ pour connaître/gérer son id (voir section 8).

### Comment retrouver l'identifiant d'un PNJ Citizens existant

Sélectionnez-le comme d'habitude avec les commandes Citizens (`/npc select
<id>` ou en le regardant selon votre configuration Citizens), placez-vous
à moins de 6 blocs, regardez-le, tapez `/rpgadmin npc info`.

### Comment associer un PNJ Citizens existant à un dialogue ou une quête

Identique à une entité vanilla, aucune différence de syntaxe YAML :
- **Quête** (objectif `TALK_TO_NPC`) : le champ `npc:` de l'objectif doit
  contenir **exactement** l'id attribué par `/rpgadmin npc tag` (voir
  section 3/4).
- **Dialogue** : cliquer sur le PNJ Citizens identifié ouvre automatiquement
  le dialogue dont l'`id:` (sans namespace) correspond **exactement** à son
  identifiant (namespace par défaut `rpgquest`). Un PNJ identifié `guide`
  ouvre donc le dialogue `id: rpgquest:guide` s'il existe (voir section 2).

### Quelles commandes Citizens sont utiles ?

RPGQuest ne fournit ni ne modifie **aucune** commande Citizens — utilisez
les commandes Citizens normales (`/npc create`, `/npc select`, `/npc
rename`, `/npc remove`...) pour créer/gérer vos PNJ. `/rpgadmin npc
tag|untag|info` est la seule commande **RPGQuest** à connaître ; elle
s'ajoute par-dessus, elle ne remplace rien côté Citizens.

### Entité vanilla ordinaire (non-régression, Citizens absent ou non utilisé)

Si Citizens n'est pas installé, ou pour une entité que Citizens ne gère
pas, le mécanisme d'origine reste disponible tel quel : l'identifiant est
stocké dans le `PersistentDataContainer` de l'entité elle-même (clé
`rpgquest:npc_id`), qui survit nativement aux redémarrages car cette
entité-là n'est jamais recréée par un tiers. Le nom affiché
(`Entity#customName()`) reste purement cosmétique dans les deux cas.

---

## 2. Comment créer un dialogue

### Fichier

Un fichier par dialogue, dans `plugins/RPGQuest/dialogues/*.yml` (en
développement local : `run/plugins/RPGQuest/dialogues/*.yml`). Généré
automatiquement au premier démarrage : `guard.yml` et `merchant.yml` sont
deux dialogues d'exemple déjà présents et fonctionnels — vous pouvez vous
en inspirer directement.

Implémentation : `com.lodygames.rpgquest.dialogue.DialogueDefinitionParser`
(validation), `com.lodygames.rpgquest.dialogue.session.DialogueSessionEngine`
(exécution).

### Format complet

```yaml
id: rpgquest:guide          # namespacé ; sans ":", le namespace par défaut "rpgquest" est utilisé
start: greeting              # id du nœud de départ (doit exister dans "nodes")

nodes:
  greeting:                  # clé = id du nœud, utilisé par "start" et "next"
    speaker: "Guide"          # nom affiché du locuteur dans le message
    text: "<white>Bienvenue au village !</white>"   # MiniMessage, ou table de traductions (voir plus bas)
    choices:                  # au moins 1 choix obligatoire
      - text: "Très bien, j'y vais."
        conditions: []        # optionnel, voir la liste des conditions plus bas
        actions:               # optionnel, voir la liste des actions plus bas
          - type: START_QUEST
            quest: rpgquest:premiers_pas
        next: null             # optionnel ; absent ici -> le dialogue se ferme après les actions
      - text: "D'accord !"
        actions:
          - type: CLOSE
```

### Champs disponibles

| Champ | Obligatoire | Description |
|---|---|---|
| `id` | oui | Identifiant namespacé du dialogue. |
| `start` | oui | Id du nœud affiché à l'ouverture. |
| `nodes` | oui, ≥ 1 | Table de nœuds, clé = id du nœud. |
| `nodes.<id>.speaker` | oui | Nom du locuteur (texte brut). |
| `nodes.<id>.text` | oui | Texte du nœud (MiniMessage ou table de traductions). |
| `nodes.<id>.choices` | oui, ≥ 1 | Liste de choix proposés au joueur. |
| `choices[].text` | oui | Libellé du choix (MiniMessage ou table de traductions). |
| `choices[].conditions` | non | Liste de conditions (voir plus bas) ; toutes doivent être vraies pour que le choix soit visible. |
| `choices[].actions` | non | Liste d'actions exécutées dans l'ordre au clic (voir plus bas). |
| `choices[].next` | non | Id d'un autre nœud **du même dialogue**, affiché après les actions. |

`text` (nœud ou choix) accepte aussi une table de traductions, comme les
quêtes :

```yaml
text:
  default: "<white>Bienvenue au village !</white>"
  en: "<white>Welcome to the village!</white>"
```
(la clé `default` est obligatoire ; la résolution automatique selon la
langue du joueur n'est pas câblée, voir section 8).

### Plusieurs lignes / plusieurs nœuds

Chaque nœud peut rediriger vers un autre via `next` (redirection après
exécution des actions du choix cliqué) :

```yaml
nodes:
  greeting:
    speaker: "Guide"
    text: "Bienvenue ! Que veux-tu savoir ?"
    choices:
      - text: "Parle-moi du village."
        next: about_village
      - text: "Rien, merci."
        actions: [{type: CLOSE}]
  about_village:
    speaker: "Guide"
    text: "Ce village vit de son commerce."
    choices:
      - text: "Retour."
        next: greeting
```
Une boucle `next` entre nœuds d'un **même** dialogue (comme `about_village`
→ `greeting` ci-dessus) est un usage normal (menu « hub »), pas une erreur.
Sans `next` ni action `CLOSE`/`OPEN_DIALOGUE` dans un choix, le dialogue se
ferme simplement après l'exécution des actions.

### Réponses / choix

Oui, supportés nativement (`choices`, voir ci-dessus). Chaque choix peut
avoir ses propres conditions de visibilité et ses propres actions. Rendu :
liens cliquables dans le chat (`ChatDialogueRenderer`, par défaut,
`config.yml` → `dialogue.renderer: chat`) ou l'API Dialog native de Paper
(`paper-dialog`, marquée expérimentale par Paper lui-même — voir
`docs/ARCHITECTURE.md`).

### Conditions disponibles (`choices[].conditions[].type`)

| Type | Champs | Effet |
|---|---|---|
| `QUEST_STATE` | `quest` (id), `state` (`NOT_STARTED`\|`ACTIVE`\|`READY_TO_TURN_IN`\|`COMPLETED`\|`FAILED`\|`ABANDONED`) | Vrai si la quête du joueur est dans cet état. |
| `HAS_ITEM` | `material`, `amount` | Vrai si l'inventaire du joueur contient au moins `amount` de `material`. |
| `HAS_PERMISSION` | `permission` | Vrai si le joueur a cette permission Bukkit. |
| `VARIABLE_EQUALS` | `key`, `value` | Vrai si la variable joueur `key` (voir `SET_VARIABLE`/récompense `VARIABLE`) vaut `value`. |

Un choix sans `conditions` est toujours visible. Les conditions sont
revérifiées **au clic**, pas seulement à l'affichage.

### Actions disponibles (`choices[].actions[].type`)

| Type | Champs | Effet |
|---|---|---|
| `START_QUEST` | `quest` (id) | Fait accepter la quête au joueur (équivalent `/quest accept`). |
| `ADVANCE_QUEST` | `quest` (id) | Satisfait immédiatement tous les objectifs de l'étape courante (avance d'une étape). |
| `TURN_IN_QUEST` | `quest` (id) | Force la remise/complétion de la quête (récompenses accordées). |
| `GIVE_ITEM` | `material`, `amount` | Donne l'objet au joueur (surplus déposé au sol si l'inventaire est plein). |
| `TAKE_ITEM` | `material`, `amount` | Retire l'objet de l'inventaire du joueur. |
| `SET_VARIABLE` | `key`, `value` | Écrit une variable joueur persistante (table `player_variables`). |
| `RUN_SAFE_COMMAND` | `command` | Exécute une commande console. Le **nom** de la commande doit figurer dans `config.yml` → `dialogue.allowed-commands` (par défaut : `give`, `xp`), vérifié **au chargement**, pas à l'exécution. Seule substitution : `%player%`. |
| `OPEN_DIALOGUE` | `dialogue` (id) | Ferme le nœud courant et ouvre un autre dialogue. Prend le pas sur `next`. |
| `OPEN_MERCHANT` | `merchant` (id) | Ferme le dialogue et ouvre la vitrine du marchand désigné (voir `docs/ECONOMY.md`). |
| `CLOSE` | — | Ferme le dialogue immédiatement. Prend le pas sur `next`. |

### Démarrer une quête depuis un dialogue

Oui, supporté : action `START_QUEST` (voir tableau ci-dessus et l'exemple
de la section 5). C'est exactement ce qui se passe dans `guard.yml` fourni
avec le projet.

### Exemple complet demandé — PNJ « Guide »

```yaml
# plugins/RPGQuest/dialogues/guide.yml
id: rpgquest:guide
start: greeting

nodes:
  greeting:
    speaker: "Guide"
    text: "<white>Bienvenue au village !</white> <gray>Va donc voir notre libraire, il pourra t'aider.</gray>"
    choices:
      - text: "Très bien, j'y vais."
        conditions:
          - type: QUEST_STATE
            quest: rpgquest:premiers_pas
            state: NOT_STARTED
        actions:
          - type: START_QUEST
            quest: rpgquest:premiers_pas
      - text: "D'accord !"
        actions:
          - type: CLOSE
```
Ce fichier est directement copiable. Voir section 5 pour le scénario
complet et section 6 pour la procédure en jeu.

---

## 3. Comment créer une quête

### Fichier

Un fichier par quête, dans `plugins/RPGQuest/quests/*.yml` (développement
local : `run/plugins/RPGQuest/quests/*.yml`). Trois quêtes d'exemple sont
déjà générées : `first_steps.yml`, `woodcutters_request.yml`,
`crystal_hunt.yml` — utilisables comme référence.

Implémentation : `com.lodygames.rpgquest.quest.QuestDefinitionParser`
(validation), `com.lodygames.rpgquest.quest.progress.QuestProgressEngine`
(progression en jeu).

### Format complet

```yaml
id: rpgquest:premiers_pas          # namespacé ; sans ":", namespace par défaut "rpgquest"
title: "<gold>Premiers pas</gold>" # MiniMessage, ou table de traductions
description: "<gray>Fais connaissance avec le village.</gray>"
category: intro                    # texte libre, affiché dans /quest list
icon: BOOK                         # optionnel, matériau vanilla ; BOOK par défaut si absent
repeatable: false                  # optionnel, false par défaut

prerequisites:                     # optionnel, liste d'ids de quêtes déjà terminées requises
  - rpgquest:autre_quete

steps:                             # obligatoire, ≥ 1 étape
  - id: parler_au_libraire         # id unique dans la quête
    objectives:                    # obligatoire, ≥ 1 objectif par étape
      - type: TALK_TO_NPC
        npc: libraire

rewards:                           # optionnel
  - type: EXPERIENCE
    amount: 20

variables:                         # optionnel, stocké mais jamais lu ailleurs dans le code actuel (aucun effet en jeu)
  exemple: "0"
```

### Champs obligatoires / optionnels

| Champ | Obligatoire | Notes |
|---|---|---|
| `id` | oui | Namespacé, minuscules. |
| `title` | oui | MiniMessage ou table de traductions (`default` obligatoire). |
| `description` | oui | Idem. |
| `category` | oui | Texte libre. |
| `icon` | non | `BOOK` par défaut. |
| `repeatable` | non | `false` par défaut. |
| `prerequisites` | non | Liste vide par défaut. |
| `steps` | oui, ≥ 1 | Voir objectifs, section 4. |
| `rewards` | non | Voir récompenses ci-dessous. |
| `variables` | non | Table libre `clé: valeur`, validée et stockée dans la définition mais **jamais lue nulle part ailleurs dans le code actuel** — aucun effet en jeu (donnée morte à ce stade). |

### Nom et description

`title`/`description` : texte MiniMessage simple, ou table de traductions
identique au mécanisme des dialogues (`default` obligatoire).

### Objectifs

Voir la liste exhaustive en section 4 — un objectif = un élément de
`steps[].objectives[]`. Une étape peut avoir plusieurs objectifs (tous
requis pour terminer l'étape) ; une quête peut avoir plusieurs étapes
(complétées dans l'ordre).

### Récompenses (`rewards[].type`)

| Type | Champs | Effet |
|---|---|---|
| `EXPERIENCE` | `amount` | XP **vanilla** (`Player#giveExp`), pas l'XP RPG de `docs/PROGRESSION.md`. |
| `ITEM` | `material`, `amount` | Objet vanilla donné (au sol si inventaire plein). |
| `VARIABLE` | `key`, `value` | Écrit une variable joueur persistante. |
| `COMMAND` | `command` | Commande console exécutée à la remise, `%player%` substitué. **Aucune liste blanche** ici (contrairement à `RUN_SAFE_COMMAND` des dialogues) — à utiliser avec prudence. |

**Note indépendante du système de récompenses :** terminer *n'importe
quelle* quête accorde aussi automatiquement un bonus fixe d'XP RPG (piste
`GLOBAL`, valeur `progression.quest-completion-xp` dans `config.yml`) —
mécanisme séparé (`QuestCompletionXpListener`), pas configurable par
quête.

### Comment démarrer la quête

Deux façons, toutes deux réellement câblées :
- Joueur : `/quest accept <id>` (permission `rpgquest.quest`, par défaut
  accordée à tous).
- Depuis un dialogue : action `START_QUEST` (voir section 2).

### Comment suivre sa progression

- `/quest progress` — liste toutes les quêtes actives avec leur état.
- `/quest progress <id>` — détail objectif par objectif de l'étape en
  cours (`current`/`total`).
- `/quest list` — toutes les quêtes connues avec leur état.
- `/quests` — journal graphique (menu paginé, 3 onglets Actives/
  Disponibles/Terminées, voir `docs/ARCHITECTURE.md` section `ui`).

### Comment la terminer

- **Automatiquement** : dès que le dernier objectif de la dernière étape
  est satisfait en jeu (aucune commande de remise n'existe pour un
  joueur). Les récompenses sont accordées immédiatement.
- **Manuellement (admin, outil de test)** : `/quest complete <id>` force
  la complétion et les récompenses même sans objectif rempli — n'agit
  **que sur le joueur qui exécute la commande** (voir section 8).
- **Depuis un dialogue** : action `TURN_IN_QUEST`.

---

## 4. Tous les types d'objectifs actuellement disponibles

Source : `ObjectiveType` (enum) + `QuestDefinitionParser#parseObjective`.
Sept types, tous réellement implémentés et fonctionnels.

### `BREAK_BLOCK` — casser un bloc
```yaml
- type: BREAK_BLOCK
  material: OAK_LOG   # nom de Material vanilla (résolution tolérante)
  amount: 20
```
Déclenché par `BlockBreakEvent`. Compte tout bloc du matériau visé cassé
par le joueur.

### `PLACE_BLOCK` — poser un bloc
```yaml
- type: PLACE_BLOCK
  material: COBBLESTONE
  amount: 10
```
Déclenché par un événement de pose de bloc.

### `KILL_ENTITY` — tuer une entité
```yaml
- type: KILL_ENTITY
  entity: ZOMBIE   # nom d'EntityType vanilla
  amount: 5
```
Déclenché par la mort de l'entité, tuée par le joueur.

### `COLLECT_ITEM` — récupérer un objet
```yaml
- type: COLLECT_ITEM
  material: AMETHYST_SHARD
  amount: 3
```
Déclenché au ramassage de l'objet au sol par le joueur.

### `CRAFT_ITEM` — fabriquer un objet
```yaml
- type: CRAFT_ITEM
  material: DIAMOND_SWORD
  amount: 1
```
Déclenché par la fabrication en table de craft. **Limite connue** : ne
distingue pas un objet personnalisé RPGQuest d'un objet vanilla du même
matériau de base.

### `TALK_TO_NPC` — parler à un PNJ
```yaml
- type: TALK_TO_NPC
  npc: libraire   # identifiant attribué via /rpgadmin npc tag (voir section 1)
```
Déclenché par un clic droit sur **n'importe quelle** entité identifiée
avec cet id (voir section 1). Toujours `1/1` (objectif binaire, satisfait
en un seul clic).

### `REACH_LOCATION` — se rendre à un endroit
```yaml
- type: REACH_LOCATION
  world: world
  x: 100.0
  y: 64.0
  z: -50.0
  radius: 5.0   # optionnel, 1.0 par défaut
```
Déclenché par le déplacement du joueur à moins de `radius` blocs des
coordonnées données, dans le monde nommé.

### Non documentés car inexistants

`USE_ITEM` n'existe pas comme type d'objectif dans ce projet — seul
`COLLECT_ITEM` (ramasser) est disponible pour les objets. Il n'y a pas non
plus d'objectif « interagir avec un bloc » générique en dehors de
`BREAK_BLOCK`/`PLACE_BLOCK`.

---

## 5. Exemple complet : « Premiers pas » (Guide → Libraire)

Scénario demandé, entièrement réalisable avec le code actuel.

### Fichier 1 — la quête

```yaml
# plugins/RPGQuest/quests/premiers_pas.yml
id: rpgquest:premiers_pas
title: "<gold>Premiers pas</gold>"
description: "<gray>Fais connaissance avec le village.</gray>"
category: intro
icon: BOOK
repeatable: true   # true pendant vos tests, voir section 6 point 9

steps:
  - id: parler_au_libraire
    objectives:
      - type: TALK_TO_NPC
        npc: libraire

rewards:
  - type: EXPERIENCE
    amount: 20
```

### Fichier 2 — le dialogue de Guide

```yaml
# plugins/RPGQuest/dialogues/guide.yml
id: rpgquest:guide
start: greeting

nodes:
  greeting:
    speaker: "Guide"
    text: "<white>Bienvenue au village !</white> <gray>Va donc voir notre libraire, il pourra t'aider.</gray>"
    choices:
      - text: "Très bien, j'y vais."
        conditions:
          - type: QUEST_STATE
            quest: rpgquest:premiers_pas
            state: NOT_STARTED
        actions:
          - type: START_QUEST
            quest: rpgquest:premiers_pas
      - text: "D'accord !"
        actions:
          - type: CLOSE
```

### Fichier 3 — le dialogue du Libraire

```yaml
# plugins/RPGQuest/dialogues/libraire.yml
id: rpgquest:libraire
start: greeting

nodes:
  greeting:
    speaker: "Libraire"
    text: "<white>Ah, le Guide t'envoie ?</white> <gray>Tiens, prends ce vieux grimoire.</gray>"
    choices:
      - text: "Merci !"
        actions:
          - type: CLOSE
```

### Comment ça s'enchaîne réellement

1. Clic droit sur l'entité identifiée `guide` → dialogue `rpgquest:guide`
   s'ouvre (convention id dialogue = id PNJ, section 1) → choix « Très
   bien, j'y vais » → action `START_QUEST` → quête `premiers_pas`
   acceptée (`0/1` sur l'objectif `parler_au_libraire`).
2. Clic droit sur l'entité identifiée `libraire` déclenche **deux choses
   indépendantes en même temps**, sur le même clic :
   - `QuestNpcInteractListener` reconnaît l'id `libraire` et fait passer
     l'objectif `TALK_TO_NPC` à `1/1` → dernier objectif de la dernière
     étape satisfait → la quête passe automatiquement à `COMPLETED` et la
     récompense (20 XP) est accordée, **dans le même instant**.
   - `DialogueNpcInteractListener` reconnaît le même id et ouvre en plus
     le dialogue `rpgquest:libraire` (texte de bienvenue du Libraire).

   Ces deux systèmes sont totalement indépendants : le dialogue du
   Libraire n'a besoin **d'aucune action spéciale** pour que la quête se
   termine — c'est le simple fait que le PNJ visé porte l'id `libraire`,
   partagé par l'objectif de quête, qui fait tout le travail.

Le scénario demandé (« un dialogue s'affiche, l'objectif passe à 1/1, la
quête est terminée ») est donc **exactement** ce que ces trois fichiers
produisent.

---

## 6. Procédure complète en jeu

Toutes les commandes ci-dessous sont réelles et vérifiées dans le code.

1. **Créer le PNJ Guide** : avec Citizens installé, `/npc create Guide`
   (ou votre commande Citizens habituelle) là où vous voulez le placer. Sans
   Citizens, faites apparaître n'importe quelle entité vivante (ex.
   `/summon minecraft:villager`) et donnez-lui un nom via une **Étiquette**
   (Name Tag) nommée à l'enclume, clic droit sur l'entité — purement
   cosmétique dans les deux cas.
2. **Créer le PNJ Libraire** : identique, un deuxième PNJ/entité, « Libraire ».
3. **Récupérer/attribuer leurs identifiants** : regardez le PNJ « Guide »
   (≤ 6 blocs) et tapez `/rpgadmin npc tag guide` (message :
   `Entité identifiée : guide (Citizens NPC #<n>)` s'il s'agit d'un PNJ
   Citizens). Idem sur l'autre PNJ : `/rpgadmin npc tag libraire`. Pour
   re-vérifier plus tard : `/rpgadmin npc info` en le regardant.
4. **Créer/configurer les dialogues** : créez les fichiers
   `plugins/RPGQuest/dialogues/guide.yml` et
   `plugins/RPGQuest/dialogues/libraire.yml` avec le contenu de la
   section 5.
5. **Créer la quête** : créez
   `plugins/RPGQuest/quests/premiers_pas.yml` avec le contenu de la
   section 5.
6. **Recharger/redémarrer** : cette fois-ci, comme vous ajoutez à la fois
   des **dialogues** (jamais rechargeables à chaud, voir section 7) et une
   **quête**, redémarrez le serveur complètement (`stop` en console, puis
   relancer). Pour de futures modifications **uniquement sur des
   fichiers de quête**, `/quest admin reload` suffit et évite un
   redémarrage.
7. **Tester avec un joueur** : connectez-vous, cliquez droit sur « Guide »,
   choisissez « Très bien, j'y vais », vérifiez le message
   `Quête acceptée : Premiers pas`. Cliquez droit sur « Libraire ».
8. **Vérifier la progression** :
   - Avant de parler au Libraire : `/quest progress premiers_pas` →
     `Parler à libraire : 0/1`.
   - Après : `/quest progress premiers_pas` → `Aucune quête en cours`
     (la quête n'est plus active, elle est `COMPLETED`) ; confirmez avec
     `/quest list` → état `COMPLETED` en face de `Premiers pas`.
9. **Réinitialiser la quête pour retester** : voir section 7 — avec
   `repeatable: true` (déjà mis dans l'exemple ci-dessus), il suffit de
   refaire `/quest accept premiers_pas` après complétion.
10. **Vérifier la persistance après redémarrage (spécifique PNJ Citizens)** :
    arrêtez le serveur (`stop`), relancez-le. Regardez à nouveau « Guide »,
    tapez `/rpgadmin npc info` → doit toujours répondre `guide` (le mapping
    vit dans `data.db`, pas sur l'entité Bukkit temporaire que Citizens
    recrée à chaque démarrage — voir section 1). Cliquez droit sur « Guide »
    → le dialogue doit toujours s'ouvrir normalement.

---

## 7. Rechargement et debug

| Besoin | Commande / méthode | Notes |
|---|---|---|
| Recharger les **quêtes** sans redémarrer | `/quest admin reload` (`rpgquest.admin`) | Recharge aussi `messages.yml`. Rapport : nombre chargé + erreurs par fichier. |
| Valider les quêtes sans les appliquer | `/quest admin validate` (`rpgquest.admin`) | Dry-run, n'affecte pas les quêtes actives des joueurs. |
| Recharger les **dialogues** sans redémarrer | **Impossible actuellement.** | `YamlDialogueEngine.reload()` existe dans le code mais n'est appelé qu'au démarrage du plugin — aucune commande ne l'expose. Un fichier de dialogue modifié/ajouté nécessite un redémarrage complet du serveur. |
| Afficher les quêtes actives | `/quest list`, `/quest progress` (sans id), ou `/quests` (UI graphique) | |
| Voir la progression détaillée | `/quest progress <id>` | Objectif par objectif de l'étape courante. |
| Démarrer une quête manuellement | `/quest accept <id>` (soi-même) ou une action `START_QUEST` dans un dialogue ouvert via `/dialogue open <joueur> <dialogueId>` (pour agir sur un **autre** joueur, voir limite ci-dessous) | |
| Terminer une quête manuellement | `/quest complete <id>` (`rpgquest.admin`, **soi-même uniquement**) ou une action `TURN_IN_QUEST` dans un dialogue ouvert via `/dialogue open` pour un autre joueur | |
| Réinitialiser une quête | Pas de commande dédiée. Si `repeatable: true`, ré-accepter suffit (`/quest accept`, compteurs repartis à zéro). Si `repeatable: false`, aucun moyen en jeu — éditer `data.db` (table `quest_progress`/`quest_objective_progress`) serveur arrêté, ou utiliser un id de quête de test dédié. | |
| Ouvrir un dialogue à distance (test) | `/dialogue open <joueur> <dialogueId>` (`rpgquest.admin`) | Le joueur ciblé n'a pas besoin d'être devant le PNJ. |
| Identifier/gérer un PNJ | `/rpgadmin npc tag [id]` \| `untag` \| `info` (`rpgquest.admin.world`) | Voir section 1. |

---

## 8. Ce qui n'est pas encore possible

- **Aucune fonctionnalité Citizens avancée exposée** : RPGQuest ne fait
  qu'associer un id logique à un PNJ Citizens existant. Chemins de
  patrouille, hologrammes, apparence, traits Citizens... restent gérés
  entièrement par les commandes Citizens elles-mêmes, RPGQuest n'y touche
  jamais.
- **Suppression d'un PNJ Citizens (`/npc remove`) ne nettoie pas
  automatiquement le mapping RPGQuest** : la ligne dans
  `npc_citizens_bindings` reste (inoffensive — elle ne peut plus jamais
  correspondre à un clic, puisque le PNJ n'existe plus — mais jamais
  supprimée automatiquement). Faites `/rpgadmin npc untag` **avant** de
  supprimer un PNJ si vous voulez garder la base propre.
- **Pas de test automatisé de bout en bout avec un vrai PNJ Citizens** : la
  persistance du mapping (`NpcBindingRepository`) et le comportement en
  l'absence de Citizens sont couverts par des tests JUnit réels ; le clic
  effectif sur un PNJ Citizens et l'événement `NPCRightClickEvent`
  nécessitent le plugin Citizens réel et n'ont été vérifiés qu'en jeu, pas
  par une suite automatisée (Citizens n'est pas disponible dans
  l'environnement de test de ce projet).
- **Pas de rechargement à chaud des dialogues** : toute modification d'un
  fichier `dialogues/*.yml` exige un redémarrage complet du serveur (voir
  section 7). Les quêtes, elles, se rechargent à chaud.
- **Pas de commande admin pour démarrer/terminer une quête au nom d'un
  autre joueur directement** : `/quest accept` et `/quest complete`
  n'agissent que sur le joueur qui les exécute. Le seul détour possible
  est un dialogue contenant `START_QUEST`/`TURN_IN_QUEST`, ouvert à
  distance sur ce joueur via `/dialogue open <joueur> <dialogueId>`.
- **Pas de commande de réinitialisation de quête** (`/quest reset`
  n'existe pas). Voir le tableau de la section 7 pour les contournements.
- **Pas de liste globale des PNJ identifiés** : `/rpgadmin npc` exige
  toujours de viser physiquement l'entité (aucune commande `list`).
- **Aucune protection/persistance automatique du PNJ** : une entité
  identifiée n'est ni invulnérable, ni protégée du despawn naturel, ni
  ressuscitée si elle meurt. Il faut la protéger vous-même (zone protégée,
  claim, ou choisir un type d'entité adapté) — RPGQuest ne gère aucun
  cycle de vie de PNJ.
- **`CRAFT_ITEM` ne distingue pas objet personnalisé et objet vanilla** du
  même matériau de base (limite déjà documentée dans
  `docs/ARCHITECTURE.md`).
- **Pas de liste blanche sur les récompenses `COMMAND`** des quêtes
  (contrairement à `RUN_SAFE_COMMAND` des dialogues, qui est filtré par
  `config.yml` → `dialogue.allowed-commands`).
- **Pas de résolution automatique de langue** : les tables de traductions
  (`title`/`description`/`text` avec plusieurs clés de langue) sont
  acceptées et stockées, mais toujours affichées via `default` — aucune
  détection de la langue du joueur n'est câblée.
- **Aucun objectif « utiliser un objet »** (clic droit sur un item sans le
  consommer/casser un bloc) n'existe — seuls les sept types listés en
  section 4 sont implémentés.
