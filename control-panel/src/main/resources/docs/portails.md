---
title: Portails
category: Monde
tags: [portail, portails, portal, hub_to_wild, hub_to_claims, wild, claims, random_safe, claim_home, avertissement, diagnostic]
order: 2
---

# Portails

La page **Monde → Portails** (`/portals`) liste tous les portails du serveur. Elle est en
**lecture seule** : rien n'y crée, ne modifie, n'active ni ne téléporte. Cliquer sur
**Rafraîchir les portails** relit le serveur (action `portal.list`).

Deux sortes de portails sont réunies dans une seule liste :

- les **portails entre mondes** (`plugins/RPGQuest/world-portals/`, `/rpgadmin worldportal`) — ceux
  de DEV : `hub_to_wild` et `hub_to_claims`. Entrer dans la zone téléporte immédiatement ;
- les **portails de zone** (`plugins/RPGQuest/portals/`, `/rpgadmin portal`), marqués
  **Ancien format** : canalisation, conditions (permission, quête, niveau RPG, coût, attente) et
  destination nommée. Aucun n'existe sur DEV aujourd'hui.

Pour chaque portail, la fiche montre :

1. **Emplacement** : monde de départ, coins minimum et maximum de la zone d'activation, taille,
   fichier ;
2. **Téléportation** : monde d'arrivée (lien vers la page Mondes), type d'arrivée et ses réglages —
   *point d'arrivée du monde*, *point sûr aléatoire* (`travel.random-safe-arrival` : rayon minimum,
   maximum, tentatives) ou *claim du joueur, sinon point sûr* (`claims.arrival`) ;
3. **Règles appliquées par le serveur** :
   - **Avertissement Wild** pour tout portail qui mène au monde `travel.wild-world` : fenêtre de
     confirmation, choix « ne plus afficher », textes actuels montrés au joueur ;
   - **Accès Claims + retour** pour tout portail qui mène au monde `claims.world` : premier terrain
     débloqué ou claim déjà possédé, Pierre de retour garantie, OP sans effet hors mode bypass. Un
     joueur refusé est orienté vers Jo et vers le PNJ guide du village (`hub.guide-npc-id`), cité
     sous son **nom affiché actuel** (ex. Elias) ; si ce nom ne se résout pas, la fiche l'indique ;
   - les **conditions** d'un portail de zone.

Un portail entre mondes n'a **aucune condition propre** (niveau, quête, permission) : la ligne
« Déclenchement » le précise, mais les règles du bloc 3 (Wild, Claims) restent appliquées.

La recherche et les filtres (Actifs, Désactivés, Ancien format, Avec problème) affichent un message
explicite quand aucun portail ne correspond (ex. « Aucun portail désactivé. »).

Le **nom** affiché (« Portail vers le Wild ») est déduit de la destination : aucun nom n'est encore
enregistré dans les fichiers.

---

## Fichier de portail ignoré

Un fichier de `world-portals/` ou `portals/` n'a pas pu être lu au chargement : le portail n'existe
pas en jeu. Le message indique le fichier et la raison (champ manquant, coordonnées inversées,
stratégie inconnue).

## Monde de destination non chargé

Le portail mène à un monde qui n'est pas chargé : le joueur n'est pas téléporté. Charger le monde
(voir la page **Mondes**) ou désactiver le portail.

## Monde de départ non chargé

Le portail se trouve dans un monde non chargé : personne ne peut l'atteindre.

## Identifiant de portail en double

Deux fichiers déclarent le même identifiant. Les commandes n'atteignent que le premier, mais les
deux zones restent actives. Ne rien supprimer sans décision du propriétaire.

## Zones de portail superposées

Deux zones d'activation se recouvrent. Là où elles se recouvrent, le serveur ne consulte que le
portail du premier fichier (ordre alphabétique), même s'il est désactivé.

## Portail de zone superposé

Un portail entre mondes et un portail de zone se recouvrent : un joueur peut déclencher les deux.

## Nom de fichier et identifiant différents

Le fichier ne s'appelle pas `<identifiant>.yml`. Les commandes d'activation, de désactivation ou de
suppression viseraient un autre fichier.

## Arrivée sur claim hors du monde des claims

Le portail utilise l'arrivée « claim du joueur » vers un monde qui n'est pas `claims.world` : le
garde d'accès aux Claims ne s'y applique pas.

## Destination de portail inconnue

Le portail de zone cite une destination absente de `destinations/` : la téléportation échoue.

## Portail sans destination

Le portail de zone n'a aucune destination : il ne téléporte personne.

## Portail désactivé

`enabled: false` : entrer dans la zone ne fait rien.

## Portail vers son propre monde

Le portail mène au monde où il se trouve.

## Portail de zone (ancien format)

Portail `/rpgadmin portal`. Il fonctionne ; il rejoindra le format unique des portails dans un lot
ultérieur (étude #331 / #228, décision Q2).
