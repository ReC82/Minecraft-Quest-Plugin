# `lodyquests-content-pack` — format d'échange de contenu déclaratif

Phase 1 du pipeline de contenus LodyQuests : **export versionné** (issue #108). Les phases
suivantes réutilisent le **même contrat** : import sécurisé + validation + diff (#109), puis
génération massive assistée par IA à partir d'un schéma officiel (#110).

> Ce document est conçu pour être **donné tel quel à une IA** (ChatGPT, Claude…) afin qu'elle
> produise un fichier importable. Il ne décrit que des champs réellement supportés par le moteur
> RPGQuest — aucune propriété inventée.

---

## 1. Enveloppe

```yaml
format: lodyquests-content-pack   # constante, obligatoire
schemaVersion: 1                  # entier, obligatoire

metadata:                         # informatif — un import NE DOIT PAS en dépendre
  exportedAt: "2026-09-09T21:40:00Z"   # ISO-8601 UTC
  pluginVersion: "0.1.0-SNAPSHOT"
  generator: "rpgquest-plugin"
  families: [quests, stories, dialogues, npcs]
  counts:
    quests: 2
    stories: 1
    dialogues: 1
    npcs: 1

content:
  quests: [ ... ]      # toujours les 4 clés, même vides (« [] »)
  stories: [ ... ]
  dialogues: [ ... ]
  npcs: [ ... ]
```

- **YAML** (pas JSON) : lisible et éditable à la main, cohérent avec le contenu RPGQuest existant,
  facilement généré par une IA. Un futur JSON Schema (#110) décrira la même structure.
- Seuls `format` + `schemaVersion` sont **contractuels** pour l'import. Tout `metadata` peut être
  absent ou faux sans empêcher un import.
- `content` contient **toujours** les 4 familles (`quests`, `stories`, `dialogues`, `npcs`), une
  liste vide si la famille n'est pas dans l'export — forme stable pour l'outillage.
- Sérialisation **déterministe** : ordre de clés fixe, familles triées par `id`, textes toujours
  entre guillemets doubles, champs nuls/vides omis, indentation 2 espaces, `\n`. Deux exports du
  même contenu (même `exportedAt`) sont octet-pour-octet identiques.

---

## 2. Identifiants et références

- Un `id` est un **identifiant métier stable**, jamais un chemin de fichier.
- Quêtes / dialogues : `namespace:clé` (ex. `rpgquest:first_steps`). Une clé sans `:` reçoit le
  namespace `rpgquest`.
- Stories / PNJ : clé simple minuscule (`main`, `guide`) — `[a-z0-9_-]` (stories) /
  `[a-z0-9._-]` (PNJ).
- Les **références entre contenus sont conservées par id** :

  | Depuis | Champ | Vers |
  |---|---|---|
  | story | `questIds[]` | quêtes |
  | quête | `prerequisites[]` | quêtes |
  | quête | `giver` | PNJ |
  | quête, objectif `TALK_TO_NPC` | `npc` | PNJ |
  | PNJ | `dialogue` | dialogue |
  | dialogue, action `START_/ADVANCE_/TURN_IN_QUEST` | `quest` | quête |
  | dialogue, condition `QUEST_STATE` | `quest` | quête |
  | dialogue, action `OPEN_DIALOGUE` | `dialogue` | dialogue |
  | dialogue, action `OPEN_MERCHANT` | `merchant` | marchand |
  | dialogue, condition `LACKS_CUSTOM_ITEM` | `item` | objet personnalisé |

  L'import (#109) distinguera : référence satisfaite par le pack, déjà présente sur le serveur, ou
  manquante.

---

## 3. Famille `quests`

```yaml
- id: rpgquest:first_steps
  title: "Premiers pas"              # chaîne, OU table { default: "...", en: "..." }
  description: "Fais connaissance avec le village."
  category: tutorial                 # obligatoire, non vide
  icon: BOOK                         # Material Bukkit (défaut BOOK)
  repeatable: false
  secret: false                      # true = masquée du joueur ; exportée quand même
  giver: guide                       # id de PNJ, optionnel
  prerequisites: [rpgquest:intro]    # optionnel
  steps:                             # >= 1 étape, ordonnées
    - id: talk
      objectives:                    # >= 1 objectif par étape, ordonnés
        - type: TALK_TO_NPC
          npc: guide
        - type: KILL_ENTITY
          entity: ZOMBIE
          amount: 5
  rewards:                           # optionnel
    - type: EXPERIENCE
      amount: 50
    - type: VARIABLE
      key: CLAIM_TIER_1
      value: "true"
  variables:                         # optionnel, table clé -> valeur (chaînes)
    started: "true"
```

### Types d'objectif (`steps[].objectives[].type`)

| type | champs |
|---|---|
| `BREAK_BLOCK` | `material` (Material), `amount` (> 0) |
| `PLACE_BLOCK` | `material`, `amount` |
| `COLLECT_ITEM` | `material`, `amount` |
| `CRAFT_ITEM` | `material`, `amount` |
| `KILL_ENTITY` | `entity` (EntityType), `amount` |
| `TALK_TO_NPC` | `npc` (id de PNJ) |
| `REACH_LOCATION` | `world`, `x`, `y`, `z` (nombres), `radius` (> 0, défaut 1) |

### Types de récompense (`rewards[].type`)

| type | champs |
|---|---|
| `EXPERIENCE` | `amount` (> 0) |
| `ITEM` | `material`, `amount` |
| `VARIABLE` | `key`, `value` (chaînes) |
| `COMMAND` | `command` (commande console éditoriale — jamais un secret) |

---

## 4. Famille `stories`

```yaml
- id: main
  name: "Histoire principale"        # chaîne OU table de traductions
  secret: false
  questIds: [rpgquest:first_steps, rpgquest:crystal_hunt]   # >= 1, ordonnées
```

Une story est un **conteneur ordonné de quêtes existantes** : `questIds` n'est jamais résolu au
chargement (les quêtes peuvent être dans le même pack ou déjà sur le serveur).

---

## 5. Famille `dialogues`

```yaml
- id: rpgquest:guide
  start: greeting                    # doit exister dans nodes
  nodes:
    greeting:
      speaker: "Le Guide"
      text: "Bienvenue !"            # chaîne OU table de traductions
      choices:                       # >= 1 par nœud, ordonnés
        - text: "Qui es-tu ?"
          next: about                # OU une action CLOSE
        - text: "Au revoir"
          actions:
            - type: CLOSE
    about:
      speaker: "Le Guide"
      text: "Je guide les nouveaux venus."
      choices:
        - text: "Merci"
          conditions:
            - type: QUEST_STATE
              quest: rpgquest:first_steps
              state: COMPLETED
              negate: true           # optionnel — inverse la condition
          actions:
            - type: START_QUEST
              quest: rpgquest:first_steps
          next: greeting
```

### Actions de choix (`choices[].actions[].type`)

| type | champs |
|---|---|
| `START_QUEST`, `ADVANCE_QUEST`, `TURN_IN_QUEST` | `quest` |
| `GIVE_ITEM`, `TAKE_ITEM` | `material`, `amount` |
| `SET_VARIABLE` | `key`, `value` |
| `RUN_SAFE_COMMAND` | `command` (soumis à la liste blanche de commandes du serveur) |
| `OPEN_DIALOGUE` | `dialogue` |
| `OPEN_MERCHANT` | `merchant` |
| `CLOSE` | — |

### Conditions de choix (`choices[].conditions[].type`)

| type | champs |
|---|---|
| `QUEST_STATE` | `quest`, `state` (`NOT_STARTED` / `ACTIVE` / `READY_TO_TURN_IN` / `COMPLETED`) |
| `HAS_ITEM` | `material`, `amount` |
| `HAS_PERMISSION` | `permission` |
| `VARIABLE_EQUALS` | `key`, `value` |
| `NO_MAIN_CLAIM`, `HAS_MAIN_CLAIM` | — |
| `LACKS_CUSTOM_ITEM` | `item` (id d'objet personnalisé) |

Toute condition accepte `negate: true`.

---

## 6. Famille `npcs`

```yaml
- id: guide
  displayName: "Le Guide"
  description: "PNJ d'accueil du village."   # optionnel
  dialogue: rpgquest:guide                   # optionnel — id de dialogue
  role: guide                                # optionnel — [a-z0-9_-], max 32
  enabled: true
```

**PNJ logique** uniquement : indépendant de Citizens, du monde et de toute position. Le pack ne
contient jamais de binding Citizens, de coordonnée, ni d'entité en jeu.

---

## 7. Exemple complet (mini-pack multi-familles cohérent)

```yaml
format: lodyquests-content-pack
schemaVersion: 1
metadata:
  exportedAt: "2026-09-09T21:40:00Z"
  pluginVersion: "0.1.0-SNAPSHOT"
  generator: "rpgquest-plugin"
  families: [quests, stories, dialogues, npcs]
  counts:
    quests: 1
    stories: 1
    dialogues: 1
    npcs: 1
content:
  quests:
    - id: rpgquest:mines_intro
      title: "Les mines oubliées"
      description: "Retrouve le vieux mineur et rapporte-lui 8 blocs de charbon."
      category: mines
      icon: IRON_PICKAXE
      repeatable: false
      secret: false
      giver: miner
      steps:
        - id: gather
          objectives:
            - type: TALK_TO_NPC
              npc: miner
            - type: COLLECT_ITEM
              material: COAL
              amount: 8
      rewards:
        - type: EXPERIENCE
          amount: 40
        - type: VARIABLE
          key: mines_intro_done
          value: "true"
  stories:
    - id: mines
      name: "La mine abandonnée"
      secret: false
      questIds: [rpgquest:mines_intro]
  dialogues:
    - id: rpgquest:miner
      start: hello
      nodes:
        hello:
          speaker: "Vieux mineur"
          text: "Tu viens pour le charbon ?"
          choices:
            - text: "Oui, je m'y mets."
              actions:
                - type: START_QUEST
                  quest: rpgquest:mines_intro
            - text: "Pas maintenant."
              actions:
                - type: CLOSE
  npcs:
    - id: miner
      displayName: "Vieux mineur"
      description: "Garde l'entrée des mines."
      dialogue: rpgquest:miner
      role: quest_giver
      enabled: true
```

---

## 8. Limites actuelles (phase 1)

- **Familles exportées** : `quests`, `stories`, `dialogues`, `npcs`. Les objets personnalisés, les
  recettes, les marchands, etc. ne sont **pas encore** dans le pack (modèles plus larges — famille
  ajoutable sans casser `schemaVersion: 1`). Les waypoints (#124) sont du contenu *runtime*, pas
  déclaratif : hors périmètre par nature.
- **Taille de transport** : l'export passe par l'agent PlugAdmin, dont le dépôt de résultat
  d'action est plafonné à 64 Kio. Un pack sérialisé > ~56 Kio est **refusé proprement** (jamais
  tronqué) — exporter alors par famille ou par élément. Le découpage / la compression du transport
  est une évolution liée à #109/#110.
- Le contenu `secret: true` **est** exporté (c'est du contenu éditorial, un backup ne doit pas le
  perdre) ; le flag est conservé pour un round-trip fidèle.

---

## 9. Ce que le pack ne contient JAMAIS

Par construction (les modèles source ne portent que du contenu éditorial) et vérifié par test :

- identifiants / mots de passe / tokens / variables d'environnement ;
- données ou progression joueur, UUID de joueur ;
- configuration ou secrets RCON / SMTP / MySQL / FTP ;
- chemins système AWS / VeryGames ;
- logs, état runtime, positions, bindings Citizens ;
- chemins de fichiers du dépôt (l'export est construit depuis les registres en mémoire).

---

## 10. Stratégie d'évolution de `schemaVersion`

- **Ajout rétrocompatible** (nouvelle famille, nouveau champ optionnel, nouveau type d'objectif /
  d'action) → **`schemaVersion` inchangé** (`1`). Un lecteur ancien ignore ce qu'il ne connaît pas ;
  un lecteur récent lit tout.
- **Changement cassant** (renommage/suppression de champ, sémantique modifiée, champ optionnel
  devenu obligatoire) → **`schemaVersion` incrémenté** (`2`, …), avec un migrateur explicite
  `v(n) -> v(n+1)` côté import.
- L'import (#109) : `schemaVersion` plus récente que celle supportée → **refus lisible**, jamais
  d'interprétation approximative. Plus ancienne → migration si un migrateur existe, sinon refus
  indiquant la version attendue.

---

## 11. Où ça vit dans le code

| Rôle | Emplacement |
|---|---|
| DTO du pack (sans Bukkit) | `com.lodygames.rpgquest.content.pack` (`ContentPack`, `*PackEntry`, `PackText`) |
| Familles | `ContentFamily` |
| Modèle runtime → DTO | `ContentPackMapper` |
| DTO → YAML canonique | `ContentPackSerializer` |
| Sélection / ordre / manifest | `ContentPackAssembler` (`exportAll` / `exportFamily` / `exportElement` / `exportSelection`) |
| Action agent lecture seule | `AgentActionType.CONTENT_EXPORT` (`content.export`) → `BukkitAgentActions.exportContent` |
| UI + téléchargement | Control Panel `/content/export` (`ContentExportPages`, `PanelApp`), `Permission.CONTENT_EXPORT` |

Ajouter une famille = une constante `ContentFamily` + un `*PackEntry` + un cas dans `ContentPackMapper`
/ `ContentPackSerializer` + un `Supplier` dans `BukkitAgentActions`. Rien d'autre dans le pipeline
ne connaît la liste des familles en dur.
