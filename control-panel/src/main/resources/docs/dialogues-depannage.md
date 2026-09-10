---
title: Résoudre les problèmes de dialogues
category: Dialogues
tags: [dialogue, diagnostic, depannage, noeud, choix, npc, quete, warning, error]
order: 2
---

# Résoudre les problèmes de dialogues

Chaque alerte de la page **Dialogues** renvoie ici, à la section correspondant à son code
technique.

---

## Dialogue non associé à un PNJ

### Ce que cela signifie

Le dialogue existe, mais **aucun PNJ RPGQuest ne l'utilise** actuellement (aucune fiche de
PNJ ne le déclare comme son dialogue).

### Pourquoi il faut corriger

Les joueurs ne pourront pas ouvrir ce dialogue via un PNJ tant qu'aucune liaison n'est
définie.

### Comment corriger

1. Ouvrir **PNJ**.
2. Ouvrir la fiche du PNJ qui doit porter ce dialogue.
3. **Actions** → **« Modifier »**.
4. Dans **Dialogue**, choisir ce dialogue.
5. Enregistrer.

### Vérification

La ligne du dialogue affiche **« lié à … »** au lieu de l'alerte.

### Référence technique

`DIALOGUE_NO_NPC`

---

## Dialogue partagé par plusieurs PNJ

### Ce que cela signifie

Plusieurs PNJ RPGQuest déclarent le **même dialogue**.

### Pourquoi il faut corriger

Ce n'est pas forcément un défaut — mais toute modification du dialogue affectera **tous** ces
PNJ à la fois.

### Comment corriger

Si le partage est voulu, il n'y a rien à faire. Sinon, donnez à chaque PNJ son propre
dialogue (page **PNJ** → **Modifier** → champ **Dialogue**).

### Vérification

Rien à vérifier si le partage est volontaire.

### Référence technique

`MULTIPLE_NPCS`

---

## Le PNJ pointe vers un autre dialogue

### Ce que cela signifie

La fiche d'un PNJ et ce dialogue **ne portent pas le même identifiant**.

### Pourquoi il faut corriger

Le PNJ ouvrira le dialogue **déclaré dans sa fiche**, pas forcément celui-ci.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir la fiche du PNJ.
2. **Actions** → **« Modifier »**.
3. Aligner le champ **Dialogue** sur l'identifiant attendu.
4. Enregistrer.

### Vérification

L'alerte disparaît après rafraîchissement du catalogue.

### Référence technique

`DEFINITION_DIALOGUE_DIVERGES`

---

## Choix sans destination

### Ce que cela signifie

Un choix de ce dialogue **renvoie vers un nœud qui n'existe pas**.

### Pourquoi il faut corriger

En jeu, ce choix bloquera ou fermera la conversation de façon inattendue.

### Comment corriger

1. Ouvrir **Dialogues**, ouvrir le dialogue concerné.
2. Repérer le nœud et le choix fautif dans le graphe.
3. Corriger la destination du choix (nœud existant) **ou** supprimer le choix.

### Vérification

Le compteur **« … warning »** de la ligne diminue.

### Référence technique

`NEXT_MISSING`

---

## Quête inconnue référencée

### Ce que cela signifie

Ce dialogue fait référence à une **quête absente du catalogue**.

### Pourquoi il faut corriger

L'action liée à cette quête (démarrer / vérifier un état) **ne se déclenchera pas**.

### Comment corriger

1. Vérifier l'identifiant de la quête dans le dialogue.
2. S'il est erroné, le corriger.
3. Si la quête devrait exister, la créer depuis la page **Quêtes**, puis recharger.

### Vérification

L'alerte disparaît après rafraîchissement des catalogues Dialogues **et** Quêtes.

### Référence technique

`QUEST_REF_UNKNOWN`

---

## Nœud jamais atteint

### Ce que cela signifie

Un nœud du dialogue **n'est relié à aucun choix** : il est inaccessible.

### Pourquoi il faut corriger

Ce nœud ne sera **jamais affiché** en jeu.

### Comment corriger

Ouvrir le dialogue, puis soit ajouter un choix qui pointe vers ce nœud, soit le supprimer
s'il est inutile.

### Vérification

Le nœud n'est plus marqué **« inaccessible »** dans le graphe.

### Référence technique

`NODE_UNREACHABLE`

---

## Fichier de dialogue rejeté

### Ce que cela signifie

Un fichier `dialogues/*.yml` **n'a pas pu être chargé** (erreur de syntaxe ou de structure).

### Pourquoi il faut corriger

Ce dialogue est **totalement absent** du jeu.

### Comment corriger

Corriger le fichier YAML côté serveur (le message d'erreur précise la ligne / le champ),
puis recharger (`/rpgquest reload` ou redémarrage), puis rafraîchir le catalogue.

### Vérification

Le fichier n'apparaît plus dans **« fichiers rejetés »** et le dialogue apparaît dans la liste.

### Référence technique

`DIALOGUE_LOAD_ISSUE`

---

## Dialogue déclaré mais absent

### Ce que cela signifie

La fiche d'un PNJ **déclare un dialogue** qui n'existe pas.

### Pourquoi il faut corriger

Le PNJ n'ouvrira **aucune conversation**.

### Comment corriger

Soit créer le dialogue manquant (page **Dialogues**), soit retirer la référence dans la
fiche du PNJ (**PNJ** → **Modifier** → **Dialogue** → *Aucun*).

### Vérification

L'alerte disparaît après rafraîchissement des catalogues.

### Référence technique

`DIALOGUE_DECLARED_MISSING`

---

## Catalogue : « source » et « serveur » (badges d'origine)

La page **Dialogues** montre **deux origines fusionnées** :

- la **source éditable** — les fichiers `src/main/resources/dialogues/*.yml`, relus à chaque
  affichage de la page ;
- le **serveur DEV** — ce que le serveur a réellement chargé (dernier « Rafraîchir »).

| Badge | Sens | Ce qu'il faut faire |
|---|---|---|
| *(aucun)* | Le dialogue est dans la source **et** chargé par le serveur. | Rien. |
| **Source uniquement** | Enregistré dans la source (par ex. tout juste créé) mais **pas encore chargé en jeu**. | Il est sélectionnable sur une fiche PNJ dès maintenant. Pour le rendre actif en jeu : recharger le contenu RPGQuest côté serveur, puis « Rafraîchir ». |
| **Hors source** | Chargé par le serveur mais **introuvable** dans la source éditable (fichier absent, renommé, ou source non montée sur PlugAdmin). | Vérifier le fichier dans le dépôt ; si c'est normal, aucune action. |

« Source uniquement » **n'est pas une erreur**. Si une fiche PNJ pointe vers un dialogue « Source
uniquement », l'alerte « Dialogue déclaré mais absent » devient une simple **info** (« rechargement
en attente »).

---

## Comprendre l'éditeur de dialogues

### Créer un dialogue

Le bouton **« Créer un dialogue »** ouvre `/dialogues/new` (même mécanique que « Créer une
quête ») : identifiant, **PNJ à rattacher** (recherche par nom ou par id — optionnel), locuteur
affiché, couleur du texte et réplique de départ. « Enregistrer dans la source » écrit
`src/main/resources/dialogues/<id>.yml` — **aucun déploiement**, le serveur le validera au
prochain rechargement.

Si un PNJ est choisi, sa **définition** est mise à jour dans la foulée pour pointer vers ce
dialogue (une seconde action, visible dans les notifications). Si le PNJ n'est pas dans le dernier
relevé, le dialogue est quand même enregistré et un message invite à faire le rattachement depuis
la fiche du PNJ (**PNJ → Modifier → Dialogue**).

Depuis la fiche d'un PNJ, le bouton **« Créer un dialogue pour ce PNJ »** ouvre le même
formulaire avec le PNJ déjà sélectionné et le locuteur prérempli.

### Ce que l'éditeur guidé permet (dialogue déjà chargé par le serveur)

- modifier le **locuteur** et le **texte** d'un nœud ;
- ajouter un **nœud simple** ;
- ajouter, modifier ou supprimer un **choix simple** (sans condition ni action de quête).

Ces opérations ne sont proposées que pour un dialogue **chargé par le serveur** (pas « Source
uniquement »).

Les conditions, les actions de quête (`START_QUEST`, `QUEST_STATE`…) et le renommage de
nœud ne sont **pas encore éditables** : elles restent intactes dans le fichier, mais se
modifient à la main pour l'instant.

### Choisir une couleur sans écrire de balise

Le formulaire de création propose une **palette de couleurs** : cliquer une pastille suffit,
le panel génère le MiniMessage correct (`<yellow>…</yellow>`). Pour un rendu avancé
(dégradés, gras, plusieurs couleurs), écrire directement du MiniMessage dans le champ
texte : le panel **ne le modifie pas**.

### Format canonique

Chaque écriture réécrit le fichier `dialogues/<id>.yml` au **format canonique** du panel :
les commentaires et la mise en forme d'origine ne sont pas conservés. Le fichier est ensuite
re-lu et rechargé ; si quelque chose échoue, le contenu d'origine est **restauré**.

### « Enregistré » ne veut pas dire « chargé en jeu »

Le panel distingue trois choses :

1. le **fichier** écrit sur le serveur ;
2. le **catalogue** que le panel a relu (ce que montrent les pages) ;
3. ce que le serveur Minecraft a effectivement **rechargé en jeu**.

Après un **Enregistrer**, la fiche, la liste et les diagnostics se remettent à jour
**automatiquement** — inutile de cliquer « Rafraîchir le catalogue » ni de recharger la
page (F5). Un dialogue marqué **« pas encore chargé en jeu »** est bien écrit dans les
fichiers, mais le serveur ne l'a pas encore pris en compte pour les joueurs connectés.
