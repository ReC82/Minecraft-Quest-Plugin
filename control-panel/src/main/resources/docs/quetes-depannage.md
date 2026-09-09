---
title: Résoudre les problèmes de quêtes
category: Quêtes / Stories
tags: [quete, quest, diagnostic, depannage, prerequis, giver, donneur, warning, error]
order: 2
---

# Résoudre les problèmes de quêtes

Chaque alerte de la page **Quêtes** renvoie ici, à la section correspondant à son code
technique.

---

## Quête non chargée

### Ce que cela signifie

Un fichier `quests/*.yml` **contient une erreur** et n'a pas pu être chargé (le message
précise le champ fautif).

### Pourquoi il faut corriger

Cette quête est **absente du jeu** : elle n'apparaît pas dans le catalogue et ne peut pas
être démarrée.

### Comment corriger

1. Ouvrir le fichier de quête côté serveur (ou via l'éditeur guidé PlugAdmin).
2. Corriger le champ signalé par le message d'erreur.
3. Recharger (`/rpgquest reload` ou redémarrage).
4. Rafraîchir le catalogue depuis PlugAdmin.

### Vérification

La quête apparaît dans la liste et l'alerte disparaît.

### Référence technique

`QUEST_LOAD_ISSUE`

---

## Prérequis inconnu

### Ce que cela signifie

La quête exige une **quête prérequise** dont l'identifiant n'existe pas dans le catalogue.

### Pourquoi il faut corriger

Les joueurs ne pourront **jamais** remplir ce prérequis : la quête restera bloquée.

### Comment corriger

1. Ouvrir **Quêtes**, ouvrir la quête, section **Prérequis**.
2. Vérifier l'identifiant : s'il est erroné, le corriger (éditeur guidé → section *Prérequis*).
3. Si la quête prérequise devrait exister, la créer, puis recharger.

### Vérification

Le prérequis n'est plus marqué **« inconnu »**.

### Référence technique

`QUEST_PREREQ_UNKNOWN`

---

## Donneur de quête inconnu

### Ce que cela signifie

La quête désigne comme **donneur** un PNJ qui n'a pas de fiche RPGQuest.

### Pourquoi il faut corriger

La quête ne pourra pas être **proposée au joueur** au cours d'une conversation avec ce PNJ.

### Comment corriger

Deux options :

- Créer la fiche du PNJ donneur : page **PNJ** → ouvrir le PNJ → **« Créer la définition »**.
- Ou corriger l'identifiant du donneur dans la quête (éditeur guidé → section *Donneur*).

### Vérification

Le donneur n'est plus marqué **« inconnu »** dans le détail de la quête.

### Référence technique

`QUEST_GIVER_UNKNOWN`
