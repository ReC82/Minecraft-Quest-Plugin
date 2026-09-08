---
title: Lancer, compléter, réinitialiser une quête / story
category: Quêtes / Stories
tags: [quest, story, quete, complete, start, reset, advance, variable, ids]
order: 1
---

# Quêtes et stories — commandes admin

Deux familles :
- `/quest …` : commandes joueur + `/quest admin …` ;
- `/rpgadmin quest …` et `/rpgadmin story …` : outils de test, **utilisables depuis la console**,
  ciblant un joueur passé en argument.

## Quêtes

```
/rpgadmin quest start <joueur> <questId> [force]
/rpgadmin quest complete <joueur> <questId>
/rpgadmin quest reset <joueur> <questId>
```

Équivalents joueur / `/quest admin` :

```
/quest complete <questId>              # force la fin de SA quête (rpgquest.admin)
/quest admin reset <joueur> <questId|all>
/quest admin reload                    # recharge les définitions de quêtes
/quest admin validate
```

- `start … force` ignore les prérequis.
- `complete` applique les récompenses **une seule fois**.
- `reset` remet la quête à zéro (état + compteurs d'objectifs) ; **n'annule pas** les récompenses
  déjà données ; utilisable joueur hors ligne.

## Stories

```
/rpgadmin story info <joueur>
/rpgadmin story start <joueur> <storyId>
/rpgadmin story advance <joueur> <storyId>
/rpgadmin story complete <joueur> <storyId>
/rpgadmin story reset <joueur> <storyId|all>
/rpgadmin story resetwithquests <joueur> <storyId|all>
```

- Une story `ACTIVE` **avance toute seule** quand sa quête courante se complète : `advance` /
  `complete` sont des raccourcis de test, pas le fonctionnement normal.
- `resetwithquests` remet aussi à zéro les quêtes qui composent la story.

## Variables joueur

```
/rpgadmin player variable get <joueur> <clé>
/rpgadmin player variable set <joueur> <clé> <valeur>
```

Clés connues : `CLAIM_TIER_1`, `tutorial_started`, `crystal_hunt_started`,
`woodcutter_reputation`, `RUNE_RAPPEL_GRANTED`. `set` est un **outil de debug** : il écrit la
valeur brute, il **ne rejoue pas** une progression (pas de récompense, pas d'effet de bord).

## Identifiants techniques

- Un id de quête / story est de la forme `rpgquest:<clé>` (ex. `rpgquest:crystal_hunt`,
  `rpgquest:premiers_pas`). Le namespace `rpgquest:` est implicite pour la plupart des commandes.
- Les ids exacts sont visibles :
  - dans le dépôt : `src/main/resources/quests/*.yml`, `stories/*.yml` (champ `id:`) ;
  - dans le Control Panel : pages **Quêtes** et **Stories** (id copiable sur chaque carte).

## Depuis le Control Panel

Page **Quêtes** / **Stories** : `quest.list`, `quest.player.status`, `story.list`,
`story.player.status` (lectures) ; `quest.start` / `quest.complete` / `quest.reset`,
`story.advance` / `story.complete`, `player.variable.set` (mutations, confirmation obligatoire).
Le donneur de quête (`giver:`) se pose via `quest.giver.set` depuis la page **PNJ**.
