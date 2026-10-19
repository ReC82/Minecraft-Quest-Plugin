---
title: Mondes
category: Monde
tags: [monde, mondes, world, multiverse, hub, wild, claims, essentiel, portail, waypoint, diagnostic]
order: 1
---

# Mondes

La page **Monde → Mondes** (`/worlds`) liste tous les mondes du serveur et ce qui en dépend.
Elle est en **lecture seule** : rien n'y crée, ne charge, ne décharge ni ne modifie un monde.
Cliquer sur **Rafraîchir les mondes** relit le serveur (action `world.list`).

Pour chaque monde :

- **Chargé / Non chargé** et le nombre de joueurs présents (relevé au moment du rafraîchissement, pas en
  direct ; le haut de page rappelle le total des joueurs connectés au même instant et signale un écart) ;
- la **dimension** (monde normal, Nether, End) et la **génération** (normale, plate, amplifiée,
  grands biomes) ; la graine et les structures dans la fiche ;
- le **gestionnaire** : *Multiverse* (déclaré dans Multiverse-Core), *RPGQuest* (créé par
  `/rpgadmin world create`, listé dans `plugins/RPGQuest/worlds.yml` — c'est le cas de `wild`), ou
  *serveur* (monde principal et ses dimensions Nether/End) ;
- le **rôle LodyQuests** : Hub, Wild, Claims, ou *Aucun rôle*. Un monde n'a aucun rôle tant que la
  configuration (`hub.world`, `travel.wild-world`, `claims.world`) ne le désigne pas ;
- le badge **Essentiel** : monde principal ou monde à rôle. Il ne doit être ni déchargé ni supprimé ;
- les **éléments liés** : portails qui partent ou arrivent, emplacements de bâtiment, waypoints et
  génération automatique, profils de mob qui citent le monde, emplacements PNJ, villes.

Sans Multiverse-Core, la page reste utilisable : seuls les mondes **chargés** sont listés, d'après
le serveur.

« inconnu » s'affiche quand une donnée ne peut pas être relue (par exemple la génération d'un monde
non chargé que Multiverse ne décrit pas).

---

## Monde à rôle non chargé

Un monde désigné comme Hub, Wild ou Claims n'est pas chargé. Les joueurs ne peuvent pas le
rejoindre. Vérifier l'orthographe dans la configuration, que le dossier du monde existe sur le
serveur, puis redémarrer.

## Monde RPGQuest non rechargé

Le monde est listé dans `plugins/RPGQuest/worlds.yml` mais n'a pas été rechargé au démarrage.
Consulter la console : le dossier a peut-être été déplacé ou renommé.

## Monde déclaré deux fois

Le monde est à la fois dans `plugins/RPGQuest/worlds.yml` et dans Multiverse-Core. Ne rien changer
sans décision du propriétaire : un seul gestionnaire doit être conservé.

## Portail dans un monde non chargé

Un portail actif part d'un monde qui n'est pas chargé : aucun joueur ne peut l'atteindre.

## Portail vers un monde non chargé

Un portail actif mène à un monde qui n'est pas chargé : la téléportation échouera.

## Monde Multiverse non chargé

Multiverse doit charger ce monde au démarrage (auto-load) mais il ne l'est pas actuellement.
S'il reste non chargé après un redémarrage, consulter la console.

## Multiverse-Core non lu

Multiverse-Core est absent, désactivé ou d'une version incompatible. La page se limite aux mondes
chargés. Rien à faire si Multiverse-Core n'est pas utilisé.
