---
title: Portails
category: Monde
tags: [portail, portails, portal, hub_to_wild, hub_to_claims, wild, claims, random_safe, claim_home, fixed_point, point fixe, retour, vérifier, avertissement, diagnostic, brouillon, emplacement, activer, désactiver, conditions, accès, niveau, délai, simuler, simulation, tester, portal_test]
order: 2
---

# Portails

La page **Monde → Portails** (`/portals`) liste tous les portails du serveur. Cliquer sur
**Rafraîchir les portails** relit le serveur (action `portal.list`). Avec les permissions
`PORTAL_EDIT` et `PORTAL_ENABLE` (propriétaire par défaut), on y crée et gère les portails entre
mondes — voir « Créer et gérer un portail » plus bas — et leurs **conditions d'accès** (« Conditions
d'accès, simulation et test »).

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
   maximum, tentatives), *claim du joueur, sinon point sûr* (`claims.arrival`) ou *point fixe*
   (position et regard enregistrés) ; et l'aller-retour éventuel (« Retour de » / « Portail retour ») ;
3. **Règles appliquées par le serveur** :
   - **Avertissement Wild** pour tout portail qui mène au monde `travel.wild-world` : fenêtre de
     confirmation, choix « ne plus afficher », textes actuels montrés au joueur ;
   - **Accès Claims + retour** pour tout portail qui mène au monde `claims.world` : premier terrain
     débloqué ou claim déjà possédé, Pierre de retour garantie, OP sans effet hors mode bypass. Un
     joueur refusé est orienté vers Jo et vers le PNJ guide du village (`hub.guide-npc-id`), cité
     sous son **nom affiché actuel** (ex. Elias) ; si ce nom ne se résout pas, la fiche l'indique ;
   - les **conditions** d'un portail de zone.

Un portail entre mondes peut avoir des **conditions d'accès** (niveau RPG, quête, objet, permission,
variable, claim principal, attente entre deux passages) : elles apparaissent dans le bloc 3, sous les
règles Wild / Claims, qui restent appliquées **avant** elles. Sans condition, la ligne
« Déclenchement » le précise. `hub_to_wild` et `hub_to_claims` n'en ont aucune.

La recherche et les filtres (Actifs, Brouillons, Désactivés, Ancien format, Avec problème) affichent
un message explicite quand aucun portail ne correspond (ex. « Aucun portail désactivé. »).

Le **nom** affiché est celui enregistré dans le fichier (`name:`) ; sans nom, il est déduit de la
destination (« Portail vers le Wild »).

## Créer et gérer un portail

**Créer un portail en brouillon** (bas de la liste, `PORTAL_EDIT`) : identifiant définitif, nom
affiché facultatif, zone d'activation, monde d'arrivée et type d'arrivée. Le brouillon est écrit
dans `world-portals/<id>.yml` avec `enabled: false` et `draft: true` : **il ne téléporte personne**.

La **zone d'activation** se règle de deux façons :

- **autour d'un emplacement** marqué en jeu avec l'outil d'emplacement (page **Emplacements**) :
  largeur (en travers de la face de l'emplacement), hauteur (vers le haut depuis le bloc
  d'ancrage), profondeur (le long de la face), centrées sur l'emplacement. Exemple : une arche
  3 × 4 × 1 face au nord couvre 3 blocs en X, 4 en Y, 1 en Z. Le fichier garde `site:` et
  `size:` ; la page Emplacements affiche alors « Utilisé par le portail … » ;
- **en coordonnées** : deux coins, comme `hub_to_wild` et `hub_to_claims`.

32 blocs au plus de chaque côté. Une zone qui recouvre celle d'un autre portail est **refusée** à
l'enregistrement et **au rechargement** du serveur (le portail déjà chargé garde sa zone, le fichier
ajouté est ignoré et signalé).

Dans la **fiche** :

- **Activer / Désactiver** (`PORTAL_ENABLE`) : activer vérifie que les deux mondes sont chargés, que
  l'emplacement existe encore et que la zone ne recouvre aucun portail. Désactiver garde toute la
  configuration ;
- **Nom affiché** (`PORTAL_EDIT`) : l'identifiant ne change jamais ;
- **Zone d'activation** (`PORTAL_EDIT`) : seulement quand le portail n'est pas actif, sans changer de
  monde ;
- **Supprimer le portail** (`PORTAL_EDIT`) : seulement s'il n'est pas actif ; supprime le fichier, ne
  touche ni l'emplacement ni un bâtiment posé, ni aucun bloc.

Chaque formulaire demande une case de confirmation et porte l'**empreinte** du dernier relevé : si
le portail a changé entre-temps, le serveur refuse (« rafraîchir puis recommencer »). Un portail
**essentiel** — il mène au Hub, au Wild ou aux Claims, comme `hub_to_wild` et `hub_to_claims` —
demande une seconde case. Toute modification est journalisée avec l'état avant / après dans
`plugins/RPGQuest/portal-audit.yml` (bloc « Modifications depuis PlugAdmin » de la fiche).

Un emplacement cité par un portail ne peut pas être supprimé : supprimer d'abord le portail, ou
changer sa zone.

## Arrivée, point fixe et portail retour

**Arrivée** (fiche, `PORTAL_EDIT`, portail non actif) : monde d'arrivée (un monde **chargé**) et type
d'arrivée :

- *point d'arrivée du monde*, *point sûr aléatoire*, *claim du joueur* : inchangés (Hub → Wild et
  Hub → Claims fonctionnent comme avant) ; *claim du joueur* n'est accepté que vers le monde des
  claims ;
- *point fixe* : X, Y (les pieds du joueur), Z, et le **regard** — horizontal (0 = sud, 90 = ouest,
  180 = nord, -90 = est, comme l'écran F3) et vertical (-90 ciel, 0 horizon, 90 sol). Écrit dans
  `destination-point:` du fichier du portail.

Le serveur **refuse** un point fixe hors des hauteurs du monde, ou dans la zone d'un portail (le
joueur y serait renvoyé aussitôt). À chaque passage, il vérifie la position : si elle n'est pas
sûre (lave, feu, vide sous les pieds, bloc plein à la tête), il prend la position sûre la plus
proche **à 2 blocs au plus** (3 en hauteur) ; s'il n'y en a pas, **le passage est refusé** et le
joueur reste où il est, avec un message. Monde d'arrivée non chargé : passage refusé avec un
message, le portail ne charge jamais un monde. Pour un point fixe, le chargement des chunks et la
téléportation sont **asynchrones** (le serveur ne se fige pas).

**Portail retour** : bouton « Préparer le portail retour » de la fiche de l'aller. Le formulaire de
création est prérempli (départ = monde d'arrivée de l'aller, arrivée = son monde de départ, point
fixe) ; il crée un **second portail distinct, en brouillon** (`return-of:` dans son fichier).
L'aller n'est pas modifié. Un seul retour par portail.

**Vérifier sans appliquer** (fiche, `PORTAL_READ`, action `portal.verify`) : contrôle le départ, la
zone, le monde d'arrivée, l'arrivée et sa sécurité, et l'aller-retour, puis affiche chaque point :
**OK**, **À regarder**, **Non vérifiable** ou **Bloquant**. **Rien n'est modifié** : aucun fichier
écrit, aucun monde ni chunk chargé, personne n'est téléporté. La sécurité d'un point fixe n'est lue
que si personne n'a besoin de charger le terrain (un joueur est à proximité) ; sinon « Non
vérifiable maintenant » — elle reste contrôlée à chaque passage. Si le portail est modifié après la
vérification, la fiche le signale : vérifier à nouveau.

## Conditions d'accès, simulation et test

**Conditions d'accès** (fiche, `PORTAL_EDIT`, portail **non actif** — le désactiver d'abord) :

- **Niveau RPG minimum** (le niveau RPG du joueur, comme les offres marchandes) et son message ;
- **Attente entre deux passages** (secondes, par joueur, démarrée après un passage réussi) et son
  message ;
- jusqu'à **8 conditions**, toutes exigées, choisies parmi les conditions déjà utilisées par les
  dialogues : *quête dans un état*, *avoir un objet* (vérifie l'inventaire, ne retire rien), *avoir
  une permission*, *variable joueur égale à*, *avoir un claim principal* ; la case **Inverser** exige
  au contraire que le joueur ne remplisse **pas** la condition. Aucune condition en texte libre.

Chaque condition a son **message de refus** (couleurs et styles au clic, comme les répliques ; vide =
message par défaut). Dans les messages, `<level>` est remplacé par le niveau exigé et `<seconds>` par
les secondes restantes. Le formulaire montre les conditions actuelles et deux lignes vides ; choisir
« — aucune — » retire une condition ; enregistrer sans aucune ligne ni niveau ni délai retire toutes
les conditions (le fichier redevient identique à avant). Une quête citée doit exister sur le serveur ;
une variable connue (ex. `CLAIM_TIER_1`) doit recevoir une valeur de son type.

C'est **le serveur** qui vérifie, à chaque passage, après les règles Wild / Claims : un joueur refusé
**reste sur place** et reçoit le message de la première condition non remplie ; le refus est noté
dans la console du serveur. Le fichier du portail garde ces réglages dans `access:`. Un fichier dont
la section `access:` est illisible est **ignoré** (« Fichier de portail ignoré ») : un portail n'est
jamais chargé sans ses conditions.

**Simuler un passage** (fiche, `PORTAL_READ`, action `portal.simulate`) : choisir un joueur
**connecté** ; le serveur évalue les conditions et répond **AUTORISÉ** ou **REFUSÉ**, avec chaque
contrôle (OK / Non) et le message que le joueur recevrait. **Personne n'est téléporté**, aucune
attente n'est démarrée, rien n'est écrit. Les règles Wild / Claims ne sont pas simulées.

**Téléporter pour test** (fiche, `PORTAL_TEST`, propriétaire par défaut, action `portal.test`) :
déplace **réellement votre personnage lié** (jamais un autre joueur), connecté, par le chemin réel du
portail, même en brouillon ou désactivé : conditions (sans l'attente, ni lue ni démarrée), puis
règles Wild / Claims (fenêtre d'avertissement, accès aux Claims), puis téléportation. Confirmation
forte : cocher la case **et** recopier l'identifiant du portail. Sans personnage lié au compte
PlugAdmin, le bouton n'est pas proposé.

---

## Fichier de portail ignoré

Un fichier de `world-portals/` ou `portals/` n'a pas pu être lu au chargement : le portail n'existe
pas en jeu. Le message indique le fichier et la raison (champ manquant, coordonnées inversées,
stratégie inconnue, condition d'accès invalide).

## Monde de destination non chargé

Le portail mène à un monde qui n'est pas chargé : le joueur n'est pas téléporté. Charger le monde
(voir la page **Mondes**) ou désactiver le portail.

## Monde de départ non chargé

Le portail se trouve dans un monde non chargé : personne ne peut l'atteindre.

## Identifiant de portail en double

Deux fichiers déclarent le même identifiant. Depuis #565, le serveur ne garde que le premier (le
portail déjà chargé, sinon l'ordre alphabétique) et ignore l'autre. Ne rien supprimer sans décision
du propriétaire.

## Zones de portail superposées

Deux zones d'activation se recouvrent. Depuis #565, le serveur refuse ce cas au chargement : le
portail déjà chargé (sinon le premier fichier par ordre alphabétique) garde sa zone, l'autre fichier
est ignoré (« Fichier de portail ignoré »).

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

## Portail en brouillon

Créé depuis PlugAdmin, jamais activé (`draft: true`, `enabled: false`) : entrer dans la zone ne fait
rien. L'activer depuis la fiche.

## Emplacement du portail introuvable

Le portail cite un emplacement (`site:`) qui n'existe plus. Sa zone enregistrée ne change pas, mais
il ne peut plus être activé : choisir un autre emplacement ou des coordonnées.

## Portail aller introuvable

Ce portail se déclare le retour (`return-of:`) d'un portail qui n'existe plus. Il fonctionne quand
même seul ; recréer l'aller ou supprimer ce retour si ce n'est pas voulu.

## Portail désactivé

`enabled: false` : entrer dans la zone ne fait rien.

## Portail vers son propre monde

Le portail mène au monde où il se trouve.

## Portail de zone (ancien format)

Portail `/rpgadmin portal`. Il fonctionne ; il rejoindra le format unique des portails dans un lot
ultérieur (étude #331 / #228, décision Q2).
