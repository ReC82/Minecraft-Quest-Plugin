---
title: Résoudre les problèmes de PNJ
category: PNJ / Citizens
tags: [pnj, npc, diagnostic, depannage, binding, definition, citizens, warning, error]
order: 2
---

# Résoudre les problèmes de PNJ

Chaque alerte de la page **PNJ** renvoie ici, à la section correspondant à son code
technique. Le principe est toujours : *comprendre → corriger → vérifier*.

---

## Fiche RPGQuest manquante

### Ce que cela signifie

Le PNJ existe bien dans le jeu (plugin Citizens), mais il n'a pas encore de **fiche
RPGQuest** (`npcs/<id>.yml`). Le cas se présente aussi quand un contenu (quête, dialogue)
fait référence à un identifiant de PNJ qu'aucune fiche ne décrit.

### Pourquoi il faut corriger

Sans cette fiche, PlugAdmin ne peut pas gérer ses dialogues, ses quêtes ni son rôle. Le
contenu qui pointe vers ce PNJ ne fonctionnera pas correctement.

### Comment corriger

Depuis PlugAdmin :

1. Ouvrir **PNJ**.
2. Cliquer sur le PNJ concerné pour ouvrir son détail.
3. Section **Actions**, cliquer **« Créer la définition »**.
4. Vérifier l'**ID technique** (il est pré-rempli et non modifiable dans cette fiche).
5. Choisir son **dialogue** et son **rôle** si nécessaire.
6. Cliquer **« Créer la définition »**.

Si l'alerte venait d'un contenu (quête/dialogue) et non d'un PNJ du jeu, l'autre option est
de corriger l'identifiant dans ce contenu.

### Vérification

Le badge **« sans définition »** disparaît de la ligne du PNJ.

### Référence technique

`BINDING_NO_DEFINITION`, `NO_DEFINITION`

---

## Dialogue introuvable

### Ce que cela signifie

La fiche du PNJ pointe vers un dialogue (`rpgquest:<clé>`) qui n'existe pas, ou qui n'est
pas chargé par le serveur.

### Pourquoi il faut corriger

Le PNJ n'ouvrira **aucune conversation** en jeu.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir le PNJ concerné.
2. Section **Actions** → **« Modifier »**.
3. Dans **Dialogue**, choisir un dialogue existant dans la liste.
4. Enregistrer.

Si le dialogue devrait exister, le créer depuis la page **Dialogues** puis revenir ici.

### Vérification

L'alerte disparaît après un rafraîchissement du catalogue.

### Référence technique

`DIALOGUE_MISSING`

---

## Fiche RPGQuest en double

### Ce que cela signifie

Plusieurs fichiers `npcs/*.yml` décrivent le **même identifiant**.

### Pourquoi il faut corriger

PlugAdmin ne sait pas laquelle utiliser : le comportement en jeu devient imprévisible.

### Comment corriger

Côté serveur, dans `plugins/RPGQuest/npcs/`, ne garder **qu'un seul** fichier pour cet
identifiant et supprimer les autres, puis recharger (`/rpgquest reload` ou redémarrage).

### Vérification

Après rechargement du catalogue, l'alerte disparaît.

### Référence technique

`DUPLICATE_DEFINITION`

---

## Plusieurs PNJ pour le même identifiant

### Ce que cela signifie

Plusieurs PNJ du jeu (Citizens) sont **associés au même identifiant** RPGQuest.

### Pourquoi il faut corriger

En jeu, on ne sait pas lequel doit porter les dialogues et les quêtes.

### Comment corriger

En jeu, retirer l'association en trop : `/rpgadmin npc untag` sur le ou les PNJ Citizens qui
ne doivent pas porter cet identifiant. Un seul PNJ doit rester associé.

### Vérification

La ligne ne montre plus qu'un seul PNJ Citizens.

### Référence technique

`DUPLICATE_BINDING`

---

## Fiche désactivée

### Ce que cela signifie

La fiche RPGQuest du PNJ existe mais elle est marquée **inactive** (`enabled: false`).

### Pourquoi il faut corriger

Le PNJ est **ignoré** par RPGQuest tant que sa fiche reste inactive — utile pour préparer un
contenu sans l'exposer, mais à réactiver ensuite.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir le PNJ.
2. **Actions** → **« Modifier »**.
3. Activer l'interrupteur **« PNJ actif »**.
4. Enregistrer.

### Vérification

Le badge **« désactivé »** disparaît.

### Référence technique

`DISABLED`

---

## PNJ pas encore présent en jeu

### Ce que cela signifie

La fiche RPGQuest est prête, mais **aucun PNJ du jeu** ne lui est encore associé.

### Pourquoi il faut corriger

Les joueurs ne verront pas ce PNJ tant qu'il n'existe pas en jeu.

### Comment corriger

Deux options depuis PlugAdmin (**PNJ** → ouvrir le PNJ → **Actions**) :

- **« Lier un PNJ Citizens »** : associer un PNJ déjà présent en jeu.
- **« Créer le PNJ Citizens »** : le faire apparaître à des coordonnées précises et l'associer.

### Vérification

Le badge **« à lier »** est remplacé par **« Citizens #… »**.

### Référence technique

`NOT_LINKED`

---

## Donneur de quête sans conversation

### Ce que cela signifie

Le PNJ est déclaré **donneur de quête**, mais il n'a pas de dialogue associé.

### Pourquoi il faut corriger

La quête ne pourra pas être **proposée au joueur** au cours d'une conversation.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir le PNJ.
2. **Actions** → **« Modifier »**.
3. Choisir un **dialogue** dans la liste (le créer d'abord depuis **Dialogues** s'il n'existe pas).
4. Enregistrer.

### Vérification

L'alerte disparaît après rafraîchissement du catalogue.

### Référence technique

`GIVER_NO_DIALOGUE`

---

## Citizens inactif sur le serveur cible

### Ce que cela signifie

Le plugin **Citizens** n'est pas actif sur le serveur RPGQuest. Les liaisons entre les PNJ du
jeu et les fiches RPGQuest ne peuvent donc pas être vérifiées.

### Pourquoi il faut corriger

Les fiches RPGQuest restent créables et modifiables, mais PlugAdmin ne peut pas contrôler leur
présence réelle en jeu (aucun PNJ visible, aucune position).

### Comment corriger

1. Installer / activer **Citizens** sur le serveur cible (version supportée par RPGQuest).
2. Redémarrer le serveur.
3. Rafraîchir le catalogue PNJ depuis PlugAdmin.

### Vérification

La page **PNJ** ne montre plus le bandeau « Citizens inactif » ; les liaisons Citizens
s'affichent.

### Référence technique

`CITIZENS_UNAVAILABLE`

---

## PNJ du jeu sans fiche RPGQuest

### Ce que cela signifie

Le PNJ a été créé **directement dans Citizens** (par exemple `/npc create Stan`) et n'a jamais
été rattaché à RPGQuest. Il apparaît dans la liste **PNJ** avec son nom en jeu, le badge
**« sans fiche RPGQuest »** et le badge **« non lié »**, sous les filtres **Tous** et
**Non liés**.

C'est une **information**, pas une erreur : un PNJ purement décoratif (figurant, garde
d'ambiance…) n'a pas besoin de fiche.

### Les trois états possibles d'un PNJ

| Situation | Ce que RPGQuest en fait |
|---|---|
| **Citizens uniquement** (ce cas) | Le PNJ existe en jeu ; RPGQuest ne gère ni ses dialogues, ni ses quêtes, ni son rôle. |
| **Fiche RPGQuest uniquement** | La configuration est prête (`npcs/<id>.yml`) mais aucun PNJ du jeu ne la porte encore → badge « à lier ». |
| **Lié** | Un PNJ du jeu porte une fiche RPGQuest → dialogues, quêtes et rôle sont actifs. |

### Comment le rattacher (facultatif)

Depuis PlugAdmin : **PNJ** → ouvrir le PNJ (son nom en jeu) → section **Actions** :

- **« Créer une fiche RPGQuest »** : crée `npcs/<id>.yml` (identifiant pré-rempli à partir du
  nom, modifiable ensuite côté serveur). Le PNJ reste à lier après création.
- **« Lier à une fiche existante »** : associe ce PNJ Citizens à une fiche RPGQuest déjà prête
  et non liée (aucun spawn, aucun déplacement).

### Vérification

Après rafraîchissement du catalogue, le PNJ passe de **« non lié »** à **« Citizens #… »**
et affiche l'état **Lié** (ou **à lier** si seule la fiche a été créée).

### Référence technique

`CITIZENS_ONLY`
