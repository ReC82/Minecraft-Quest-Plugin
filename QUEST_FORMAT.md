# Format d'une quête

Un fichier par quête, dans `plugins/RPGQuest/quests/*.yml`. Voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (section `quest`) pour le
détail de la validation. Exemple complet :

```yaml
id: rpgquest:woodcutters_request   # namespacé ; sans ':', le namespace par défaut "rpgquest" est utilisé
title: "<gold>La requête du bûcheron</gold>"       # MiniMessage ; ou une table de traductions (voir plus bas)
description: "<gray>Récolte du bois...</gray>"
category: gathering
repeatable: true

prerequisites:
  - rpgquest:first_steps

steps:
  - id: chop_wood
    objectives:
      - type: BREAK_BLOCK          # BREAK_BLOCK | PLACE_BLOCK | KILL_ENTITY | COLLECT_ITEM
        material: OAK_LOG          # | CRAFT_ITEM | TALK_TO_NPC | REACH_LOCATION
        amount: 20
  - id: report_to_npc
    objectives:
      - type: TALK_TO_NPC
        npc: woodcutter_bob

rewards:
  - type: EXPERIENCE               # EXPERIENCE | ITEM | VARIABLE | COMMAND
    amount: 30
  - type: VARIABLE
    key: woodcutter_reputation
    value: "1"
  - type: COMMAND
    command: "give %player% oak_planks 16"

variables:
  wood_collected: "0"
```

## Objectif `TALK_TO_NPC`

`npc:` (ex. `woodcutter_bob` ci-dessus) est l'identifiant stable attribué à
une entité via `/rpgadmin npc tag <id>` (voir `docs/ARCHITECTURE.md`,
package `npc`) — **jamais** le nom personnalisé affiché au-dessus d'elle.
Le nom affiché reste purement cosmétique et peut être renommé librement sans
casser l'objectif : marquez d'abord l'entité (`/rpgadmin npc tag
woodcutter_bob`), puis nommez-la comme vous le souhaitez à l'enclume.

## Textes localisables

`title`/`description` acceptent soit un texte simple, soit une table avec
une clé `default` obligatoire :

```yaml
title:
  default: "<gold>Premiers pas</gold>"
  en: "<gold>First Steps</gold>"
```

## Validation

-   `id`, `title`, `description`, `category`, `steps` (≥ 1) sont obligatoires.
-   Chaque étape a un `id` unique dans la quête et ≥ 1 `objectives`.
-   Types d'objectif/récompense inconnus, matériaux/entités inconnus,
    nombres ≤ 0, et prérequis référençant la quête elle-même sont rejetés.
-   Un fichier invalide est rejeté seul ; les autres continuent de charger.
    `id` dupliqué entre fichiers ou prérequis introuvable après chargement
    de tous les fichiers → rejeté aussi (voir `/quest admin reload|validate`).
