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

Un clic sur un joueur déplie son détail : **Identité**, **Activité**, **RPGQuest** (liens vers
ses quêtes / stories / historique), **Droits**, **Modération**, **Actions**.

## Bannir / débannir

Disponible **en ligne comme hors ligne** (permission **modération joueur**).

1. Ouvrir la fiche du joueur → **Actions** → **Bannir**.
2. Saisir une **raison** (obligatoire).
3. Cocher la confirmation, puis **Bannir le joueur**.

Le joueur ne pourra plus se connecter ; s'il est en ligne il est **expulsé** immédiatement.
Pour lever la sanction : **Actions** → **Débannir**. Les bannissements **temporaires** ne sont
pas gérés ici.

Chaque ban / unban est journalisé (acteur PlugAdmin, UUID + pseudo cible, raison, résultat).

## Actions impossibles hors ligne

Certaines actions n'ont de sens que sur un joueur **connecté** — elles sont alors indisponibles
avec une explication, jamais un faux succès :

| Action | Hors ligne | En ligne |
|---|---|---|
| Bannir / débannir | ✔ | ✔ |
| Lire / écrire une variable (debug) | ✔ | ✔ |
| Reset « nouveau joueur » | ✔ | ✔ |
| Donner un objet | indisponible | ✔ |

## Droit de construction

**Non géré par PlugAdmin pour le moment.** Accorder un droit de construction *persistant* à un
joueur hors ligne suppose un gestionnaire de permissions persistant (permissions granulaires
*issue #27* + LuckPerms ou équivalent). Tant que ce socle n'est pas intégré à cette branche,
PlugAdmin **n'affiche pas d'interrupteur** qui ne contrôlerait rien. La fiche indique simplement
« non géré » et renvoie ici.

## Reset « nouveau joueur »

Le reset existe déjà (voir *Reset d'un joueur*). Depuis la fiche : **Actions** → **Reset
« nouveau joueur »** → **Aperçu** (ne modifie rien) puis confirmation explicite. Action
**irréversible** — l'aperçu liste précisément ce qui serait effacé.

## Sécurité

- Authentification + permission backend + CSRF sur chaque mutation.
- Cible identifiée par **UUID**, jamais par le pseudo seul.
- Aucune route n'exécute de commande serveur arbitraire.
- Aucune édition directe des fichiers `playerdata`.
