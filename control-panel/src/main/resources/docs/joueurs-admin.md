---
title: Gérer les joueurs
category: Joueurs
tags: [joueur, player, annuaire, online, offline, hors ligne, ban, unban, bannir, débannir, modération, build, reset, recherche]
order: 0
---

# Gérer les joueurs

La page **Joueurs** est un **annuaire d'administration** : elle liste les joueurs **connectés**
et les joueurs **hors ligne** ayant déjà rejoint le serveur au moins une fois. Elle répond
rapidement à : *qui est-ce ? est-il connecté ? quand est-il venu ? que puis-je faire sur lui
maintenant ?*

Ce n'est pas une console de commandes. Chaque action est **typée**, **soumise à permission**,
**auditée**, et indique clairement si elle fonctionne **hors ligne**.

## Source des joueurs

La liste vient du **serveur Paper** (fichiers `playerdata` + joueurs connectés), via l'action
agent `player.catalog`. PlugAdmin ne tient aucune base de joueurs à part. L'identité stable est
l'**UUID** ; le **pseudo** est l'identité d'affichage (dernier pseudo connu).

## En ligne / hors ligne

| Badge | Signification |
|---|---|
| **● En ligne** | Le joueur est connecté maintenant (monde + position affichés). |
| **○ Hors ligne** | Déjà venu ; « Dernière connexion » indique quand. |
| **Banni** | Un bannissement Paper est actif sur ce joueur. |

Ordre par défaut : connectés d'abord, puis hors ligne par **dernière connexion décroissante**.
Tri alternatif : **Nom A-Z**.

## Rechercher

Champ de recherche en haut de liste — sur le **pseudo** (insensible à la casse) ou l'**UUID**.
Filtres : **Tous**, **En ligne**, **Hors ligne**, **Bannis** (combinables avec la recherche).
La liste est paginée (50 par page).

## Ouvrir une fiche

Un clic sur un joueur ouvre **sa fiche**, sur une page à part. « ← Joueurs » ramène à la liste telle
que vous l'avez laissée (recherche, filtre, tri, page).

En haut : pseudo, état (en ligne, banni, OP Minecraft), première et dernière connexion à **votre**
heure locale (la date complète au survol), monde et position s'il est connecté, et l'UUID avec un
bouton **Copier**.

Juste en dessous, **un seul bandeau** donne le résultat de la dernière action faite sur ce joueur,
daté. Une action encore en cours est dite « en cours », jamais réussie d'avance.

La fiche a cinq onglets. Chaque action est un bouton : son formulaire ne s'ouvre qu'au clic, et un
seul à la fois.

| Onglet | Contenu |
|---|---|
| **Vue d'ensemble** | activité, liens vers ses quêtes, ses stories et son historique, résumé modération |
| **Progression** | quêtes, stories, historique, lire une variable, les deux remises à zéro |
| **Économie** | solde et journal, créditer, débiter, récompenses en attente |
| **Permissions et modération** | bannir / débannir, expulser, renvoyer au Hub, whitelist, OP, droits en jeu |
| **Outils avancés** | écrire une variable, donner un objet |

## Bannir / débannir

Disponible **en ligne comme hors ligne** (permission **modération joueur**) : onglet
**Permissions et modération** → **Bannir**, raison obligatoire, confirmation. S'il est connecté, le
joueur est expulsé tout de suite. Un joueur banni voit **Débannir** à la place.

## Actions qui demandent un joueur connecté

| Action | Hors ligne | En ligne |
|---|---|---|
| Bannir / débannir, whitelist, OP | ✔ | ✔ |
| Lire / écrire une variable | ✔ | ✔ |
| Remises à zéro | ✔ (appliquées à la prochaine connexion) | ✔ |
| Expulser, renvoyer au Hub, donner un objet | non proposées, avec la raison | ✔ |

## OP, whitelist et droits de construction

Ce sont trois choses distinctes : **OP Minecraft** ne change ni le rôle PlugAdmin ni les droits de
construction. La **whitelist** n'empêche d'entrer que si elle est activée sur le serveur : le
résultat d'un ajout le précise. Les **droits de construction** viennent des groupes PlugAdmin du
compte lié au joueur (page **Utilisateurs**) ; « Lire ses droits en jeu » montre ce que le serveur
applique réellement. Aucun faux interrupteur n'est affiché.

## Remises à zéro

Deux portées, chacune avec son **aperçu** (n'écrit rien), la liste de ce qui est **conservé** et de
ce qui est **effacé**, et une confirmation : **Remettre à zéro la progression** (garde
l'inventaire) et **Repartir comme un nouveau joueur** (vide l'inventaire). Voir *Reset d'un joueur*.

## Sécurité

- Authentification + permission backend + CSRF sur chaque mutation.
- Cible identifiée par **UUID**, jamais par le pseudo seul.
- Aucune route n'exécute de commande serveur arbitraire.
- Aucune édition directe des fichiers `playerdata`.
