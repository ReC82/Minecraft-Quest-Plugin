---
title: Résoudre les problèmes de stories
category: Quêtes / Stories
tags: [story, storyline, diagnostic, depannage, chaine, quete, warning, error]
order: 3
---

# Résoudre les problèmes de stories

Chaque alerte de la page **Stories** renvoie ici, à la section correspondant à son code
technique.

---

## Story non chargée

### Ce que cela signifie

Un fichier `stories/*.yml` **contient une erreur** et n'a pas pu être chargé.

### Pourquoi il faut corriger

Cette story est **absente du jeu** : sa chaîne de quêtes n'est pas suivie.

### Comment corriger

1. Ouvrir le fichier de story côté serveur (ou via l'éditeur guidé PlugAdmin).
2. Corriger le champ signalé par le message d'erreur (souvent : `quests` vide ou identifiant
   mal formé).
3. Recharger (`/rpgquest reload` ou redémarrage).
4. Rafraîchir le catalogue depuis PlugAdmin.

### Vérification

La story apparaît dans la liste et l'alerte disparaît.

### Référence technique

`STORY_LOAD_ISSUE`

---

## Quête inconnue dans la chaîne

### Ce que cela signifie

La story enchaîne une **quête** dont l'identifiant n'existe pas dans le catalogue.

### Pourquoi il faut corriger

La progression de la story **s'arrêtera** à cette étape : les joueurs ne pourront pas la
terminer.

### Comment corriger

1. Ouvrir **Stories**, ouvrir la story, section **Chaîne de quêtes**.
2. Vérifier l'identifiant de la quête concernée.
3. S'il est erroné, le corriger (éditeur guidé → *Chaîne de quêtes*).
4. Si la quête devrait exister, la créer depuis **Quêtes**, puis recharger.

### Vérification

Aucune étape de la chaîne n'est plus marquée **« inconnue »**.

### Référence technique

`STORY_QUEST_UNKNOWN`
