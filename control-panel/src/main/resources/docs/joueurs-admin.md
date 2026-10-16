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

## OP Minecraft : le joueur est prévenu

Après un « Accorder OP » ou « Retirer OP » **réellement appliqué**, le joueur connecté reçoit un
message privé (« Tes droits opérateur Minecraft (OP) ont été activés / retirés »). Hors ligne : le
résultat le dit, et à sa prochaine connexion il reçoit **un seul** résumé de l'état final, seulement
si cet état a changé. Une demande sans effet (déjà OP) n'envoie rien. Textes dans `messages.yml`
(`player.op-*`).

## Variables (outil de test)

« Valeur actuelle sur le serveur » est toujours une valeur **relue**, avec sa clé et sa date ;
« absente » n'est jamais confondue avec `false`. La « Nouvelle valeur » démarre vide. Après une
écriture, le serveur relit la variable et c'est cette valeur qui s'affiche. Pour `CLAIM_TIER_1`,
`CLAIM_TIER_2`… seules `true` et `false` sont acceptées ; pour `STARTER_KIT_TIER`, un nombre entier
(1, 2, 3…), vérifié dans le panel **et** sur le serveur.

## Remises à zéro

Deux portées, chacune avec son **aperçu** (n'écrit rien), la liste de ce qui est **conservé** et de
ce qui est **effacé**, et une confirmation : **Remettre à zéro la progression** (garde
l'inventaire) et **Repartir comme un nouveau joueur** (vide l'inventaire). Voir *Reset d'un joueur*.

Chaque portée indique la **dernière remise à zéro connue** pour ce joueur : date et heure locales,
auteur, résultat. Un aperçu porte sa propre date et ne modifie rien. Hors ligne, les données de
progression sont remises à zéro tout de suite ; seul le nettoyage de l'inventaire attend sa
prochaine connexion.

## Droits à récompense

Onglet **Monnaie** → **Droits à récompense** : ce que le joueur a gagné (événement, concours) et ce
qu'il en est advenu — *en attente* (avec le nombre d'essais et la dernière erreur, par exemple un
inventaire plein), *remis* (date) ou *révoqué* (motif). **Déclarer un droit** demande une récompense
du catalogue `entitlements.yml` et une **référence** (ex. `concours-noel-2026`) : la même référence
pour le même joueur ne crée jamais un second droit. Le serveur remet la récompense **une seule
fois**, au joueur connecté qui a la place ; rien n'est jamais jeté au sol. Seul un droit *en attente*
se révoque. Si la fonction est désactivée sur le serveur, la page le dit et ne propose pas de
déclaration.

## Sécurité

- Authentification + permission backend + CSRF sur chaque mutation.
- Cible identifiée par **UUID**, jamais par le pseudo seul.
- Aucune route n'exécute de commande serveur arbitraire.
- Aucune édition directe des fichiers `playerdata`.
