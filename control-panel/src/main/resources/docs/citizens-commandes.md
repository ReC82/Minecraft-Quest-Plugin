---
title: Commandes Citizens utiles
category: PNJ / Citizens
tags: [citizens, npc, skin, commandes, rpgadmin]
order: 2
---

# Commandes Citizens utiles

Serveur : Citizens `2.0.43` (build `b4232`). RPGQuest n'ajoute que `/rpgadmin npc tag|untag|info`
par-dessus ; tout le reste est du Citizens standard.

## Créer / sélectionner

```
/npc create <nom> --type player
/npc create <nom>                 # villageois par défaut
/npc select <id>
/npc list
```

## Apparence

```
/npc skin <pseudo>
/npc skin -c <pseudo>            # force le rafraîchissement du cache
/npc skin -t <url_texture>       # skin par URL (selon config Citizens)
/npc rename <nouveau nom>
/npc glowing                     # contour lumineux (utile pour repérer un PNJ)
```

## Position / orientation

```
/npc tphere                      # amène le PNJ à vous (placement précis)
/npc tp                          # vous téléporte au PNJ
/npc move                        # déplace le PNJ vers vous (revalider pour confirmer)
/npc rotate                      # oriente le PNJ selon votre regard
/npc lookclose true              # le PNJ regarde les joueurs proches
```

## Comportement

```
/npc pathfindingrange <n>
/npc behaviour ...               # traits Citizens (avancé)
```

## Supprimer

```
/npc remove                      # supprime le PNJ SÉLECTIONNÉ (définitif)
```

> [!WARNING]
> `/npc remove all` supprime **tous** les PNJ du serveur. Ne jamais l'utiliser sur un serveur
> avec du contenu. Avant un `/npc remove`, faire `/rpgadmin npc untag` pour retirer le mapping
> RPGQuest proprement.

## Lien avec RPGQuest

```
/rpgadmin npc tag <id>          # visé sur le PNJ, à moins de 6 blocs
/rpgadmin npc info
/rpgadmin npc untag
```

Détails et pièges : voir la fiche **Créer et configurer un PNJ**.
