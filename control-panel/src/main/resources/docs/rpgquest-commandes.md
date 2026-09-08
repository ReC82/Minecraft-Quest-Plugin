---
title: Commandes RPGQuest (référence rapide)
category: Administration serveur
tags: [rpgadmin, rpgquest, commandes, quest, customitem, story, spawn, world, reference]
order: 1
---

# Commandes RPGQuest — référence rapide

Toutes les sous-commandes `/rpgadmin` demandent la permission `rpgquest.admin.world`.
`quest`, `story`, `player` et `guide` sont **utilisables depuis la console**.

## `/rpgquest`

```
/rpgquest version
/rpgquest help
/rpgquest profile [joueur]
/rpgquest reload                 # recharge config.yml (rpgquest.admin) — PAS les dialogues
```

## `/rpgadmin` (admin monde/contenu)

Sous-commandes : `flatten`, `zone`, `portal`, `mob`, `npc`, `spawn`, `world`, `worldportal`,
`quest`, `story`, `waystone`, `player`, `guide`.

```
/rpgadmin npc tag [id] | untag | info
/rpgadmin spawn set | tp
/rpgadmin world create <name> | tp <name> | list
/rpgadmin worldportal create <id> <destinationWorld> | list | here | info <id> | enable <id> | disable <id> | delete <id> | debug showall|hideall
/rpgadmin quest start <joueur> <id> [force] | complete <joueur> <id> | reset <joueur> <id>
/rpgadmin story info|start|advance|complete|reset|resetwithquests <joueur> <id|all>
/rpgadmin player resetnew <joueur> [preview|confirm]
/rpgadmin player variable get|set <joueur> <clé> [valeur]
/rpgadmin waystone list | here | tp | generatehere | reset
/rpgadmin guide list | info <hub>
/rpgadmin flatten <rayon> [hauteur] | confirm | cancel | undo
```

## `/quest` (joueur + `/quest admin`)

```
/quest list | accept <id> | progress | abandon | complete <id>
/quest admin reload | validate | reset <joueur> <id|all>
```

## `/customitem`

```
/customitem give <joueur> <id> [quantité]
/customitem list
/customitem inspect
```

## Autres

```
/dialogue open <joueur> <dialogueId>     # ouvre un dialogue à distance (admin/test)
/money admin give|take|set <joueur> <montant>
/claim ...                               # voir la fiche Claims
/backpack recover [numéro] | admin grant|revoke <joueur> [taille]
/skills admin grant|set <joueur> <compétence> <montant>
```

> [!NOTE]
> `/rpgquest reload` ne recharge **que** `config.yml`. Il n'existe pas de rechargement à chaud du
> dossier `dialogues/` : un ajout/retrait de fichier de dialogue demande un redémarrage serveur
> (ou passe par `dialogue.definition.create` depuis le Control Panel, qui recharge en mémoire).
