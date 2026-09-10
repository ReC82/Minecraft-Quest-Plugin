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

## Éditeur guidé (créer / modifier une quête sans YAML)

Page **Quêtes** → « Créer une quête », ou « Modifier » sur une carte. Principe :

1. **Construire le brouillon d'abord.** Ajouter étapes, objectifs et récompenses ne demande
   *aucun* champ rempli au préalable : on pose la structure, on remplit ensuite. La page reste
   au niveau du bloc qu'on vient d'ajouter/supprimer.
2. **Choisir le type = voir seulement les bons champs.** Un objectif `KILL_ENTITY` demande une
   entité + une quantité ; `CRAFT_ITEM` un objet + une quantité ; `TALK_TO_NPC` un PNJ ; etc.
   Changer le type remplace les champs immédiatement et **efface** ce qui avait été saisi pour
   l'ancien type.
3. **Listes recherchables.** Entité, matériau, PNJ, icône, catégorie et quête prérequise se
   filtrent en tapant quelques lettres. La catégorie accepte aussi une valeur nouvelle.
4. **Valider à la fin.** « Vérifier » liste les anomalies (ERREUR bloquante / ATTENTION / INFO)
   et montre le YAML généré + le diff. « Enregistrer dans la source » écrit le fichier
   `src/main/resources/quests/<id>.yml` du dépôt — **aucun déploiement**, le serveur le validera
   à son prochain chargement.

## Catalogue : « source » et « serveur » (badges d'origine)

Les pages **Quêtes** et **Stories** montrent **deux origines fusionnées** :

- la **source éditable** — les fichiers `src/main/resources/quests/*.yml` et `stories/*.yml`,
  relus à chaque affichage de la page ;
- le **serveur DEV** — ce que le serveur a réellement chargé (dernier « Rafraîchir le catalogue »).

Chaque entrée porte un badge quand les deux ne coïncident pas :

| Badge | Sens | Ce qu'il faut faire |
|---|---|---|
| *(aucun)* | La quête est dans la source **et** chargée par le serveur. | Rien. |
| **Source uniquement** | Enregistrée dans la source (par ex. tout juste créée) mais **pas encore chargée en jeu**. | Elle est utilisable comme prérequis / étape de story dès maintenant. Pour la rendre active en jeu : recharger le contenu RPGQuest côté serveur (`/quest admin reload` ou redémarrage), puis « Rafraîchir le catalogue ». |
| **Hors source** | Chargée par le serveur mais **introuvable** dans la source éditable (fichier absent, renommé, ou source non montée sur PlugAdmin). | Vérifier le fichier dans le dépôt ; si c'est normal (quête livrée autrement), aucune action. |

« Source uniquement » **n'est pas une erreur** : c'est le fonctionnement normal du flux
« créer → enregistrer → utiliser ». La création dans l'éditeur et l'activation en jeu restent
**deux étapes séparées** — enregistrer ne redémarre jamais Minecraft.
