---
title: Résoudre les problèmes de dialogues
category: Dialogues
tags: [dialogue, diagnostic, depannage, noeud, choix, npc, quete, warning, error]
order: 2
---

# Résoudre les problèmes de dialogues

Chaque alerte de la page **Dialogues** renvoie ici, à la section correspondant à son code
technique.

Les corrections se font dans la page **« Dialogue de … »** (l'éditeur guidé, voir
[Faire parler un personnage](/docs/dialogues)). Les mots techniques « nœud » et « choix » des
diagnostics désignent ce que l'éditeur appelle une **réplique** et une **réponse**.

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

Une réponse de ce dialogue **mène vers une réplique qui n'existe pas** (un choix dont le `next`
vise un nœud inconnu).

### Pourquoi il faut corriger

En jeu, ce choix bloquera ou fermera la conversation de façon inattendue.

### Comment corriger

1. Ouvrir **Dialogues**, puis le dialogue concerné.
2. Repérer la réponse dont **Ensuite…** affiche « Réplique introuvable — choisissez une suite ».
3. Choisir une autre suite (une réplique existante, *Une nouvelle réplique…* ou *Terminer la
   conversation*), **ou** **Supprimer cette réponse**.
4. **Enregistrer et mettre en jeu**.

### Vérification

Le bandeau indique « À jour en jeu » et l'alerte disparaît de la page **Dialogues**.

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

Une réplique du dialogue n'est atteinte par **aucune réponse** : dans l'éditeur, sa carte porte
l'étiquette **« Aucune réponse n'y mène »**. C'est souvent une réplique tout juste créée avec
**Ajouter une réplique**.

### Pourquoi il faut corriger

Cette réplique ne sera **jamais affichée** en jeu.

### Comment corriger

Ouvrir le dialogue, puis soit choisir cette réplique dans **Ensuite…** d'une réponse, soit
**Réglages de la réplique → Retirer cette réplique** si elle est inutile. Voir
[Une réplique que personne n'atteint](/docs/dialogues#une-replique-que-personne-natteint).

### Vérification

La carte n'affiche plus « Aucune réponse n'y mène ».

### Référence technique

`NODE_UNREACHABLE`

---

## Fichier de dialogue rejeté

### Ce que cela signifie

Un fichier `dialogues/*.yml` **n'a pas pu être chargé** (erreur de syntaxe ou de structure).

### Pourquoi il faut corriger

Ce dialogue est **totalement absent** du jeu.

### Comment corriger

Si le dialogue est modifiable dans PlugAdmin : ouvrir le dialogue, corriger ce que dit le message
(par exemple une réplique sans réponse, voir [Réplique sans réponse](#replique-sans-reponse)),
puis **Enregistrer et mettre en jeu**. Sinon (fichier déposé à la main sur le serveur) : corriger
le fichier YAML (le message précise la ligne ou le champ), puis `/rpgadmin content reload`.

### Vérification

Le fichier n'apparaît plus dans **« fichiers rejetés »** et le dialogue apparaît dans la liste.

### Référence technique

`DIALOGUE_LOAD_ISSUE`

---

## Réplique sans réponse

### Ce que cela signifie

Une réplique (un nœud du fichier) n'a **aucune réponse** (`choices` vide ou absent). Le serveur
refuse alors tout le dialogue : `nodes.r2: « choices » est obligatoire et doit contenir au moins un choix`.

### Pourquoi il faut corriger

Le dialogue ne se charge pas : les joueurs gardent l'ancienne version, ou n'en ont aucune.

### Comment corriger

Dans l'éditeur, sous la réplique signalée, cliquez **Ajouter une réponse** et laissez
**Ensuite… → Terminer la conversation**. Voir [Terminer une conversation](/docs/dialogues#terminer-une-conversation).
PlugAdmin refuse désormais d'enregistrer ou de mettre en jeu un tel dialogue, avant toute écriture.

### Vérification

L'éditeur enregistre sans message rouge, puis « Mettre en jeu » affiche « À jour en jeu ».

### Référence technique

Erreur `RELOAD_FAILED / INVALID_CONTENT` du rechargement ; contrôle panel `DialogueValidator` (#730).

---

## Le serveur a une autre version de ce dialogue

### Ce que cela signifie

Le fichier du dialogue sur le serveur DEV a été changé **en dehors de cet éditeur** depuis votre
dernière mise en jeu (édition directe, autre personne, autre outil). PlugAdmin refuse alors de
mettre votre version en jeu : il ne remplace jamais une version qu'il ne connaît pas sans vous
demander. Votre version enregistrée dans l'éditeur est **gardée**.

Une mise en jeu **refusée par le jeu** (par exemple une réplique sans réponse) ne provoque pas
ce message : le serveur remet son fichier dans l'état d'avant, rien n'est mis en jeu, et vous
pouvez corriger puis réessayer directement.

### Pourquoi il faut corriger

Tant que vous n'avez pas choisi quelle version garder, votre dialogue ne peut pas être mis en jeu.

### Comment corriger

1. Dans l'éditeur, cliquez **Comparer les deux versions** : la page technique du dialogue
   s'ouvre ; demandez la comparaison pour voir ce qui a changé.
2. **Pour garder votre version** : une fois la comparaison affichée, cliquez **Écraser la version
   DEV** et confirmez. L'autre version est sauvegardée sur le serveur avant d'être remplacée.
3. **Pour garder celle du serveur** : cliquez **Recharger depuis DEV**, puis **Remplacer ma version
   par celle de DEV**. Votre version est sauvegardée avant d'être remplacée, et la version reprise
   devient la référence : vous pouvez ensuite réappliquer votre correction et remettre en jeu.

Si la comparaison montre la même chose des deux côtés, cliquez **Vérifier à nouveau** : l'état du
serveur était peut-être simplement ancien.

### Vérification

L'éditeur n'affiche plus l'encadré rouge ; après la mise en jeu, il indique « À jour en jeu ».

### Référence technique

Refus `CONFLICT` de `content.publish` : l'empreinte DEV attendue ne correspond pas au fichier. La
référence du panel est la dernière empreinte laissée par **ses** opérations, échecs de
rechargement compris (#731) ; un refus `RELOAD_FAILED` remet le fichier DEV dans son état d'avant.
« Recharger depuis DEV » (#732) relit le fichier par `content.dev.read`, le copie octet pour octet
dans la source (sauvegarde `content-backups/reload-…`) et enregistre son empreinte comme nouvelle
référence attendue sur DEV.

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
| **Hors source** | Chargé par le serveur mais **introuvable** dans la source éditable (fichier absent, renommé, ou source non montée sur PlugAdmin). | Pour le modifier : ouvrir le dialogue → « Rendre ce dialogue modifiable » (copie identique, sans effet en jeu, #430). Sinon, aucune action. |

« Source uniquement » **n'est pas une erreur**. Si une fiche PNJ pointe vers un dialogue « Source
uniquement », l'alerte « Dialogue déclaré mais absent » devient une simple **info** (« rechargement
en attente »).

---

## Comprendre l'éditeur de dialogues

Le mode d'emploi complet de l'éditeur guidé est dans
[Faire parler un personnage](/docs/dialogues) : répliques, réponses, « Ensuite… »,
enregistrement, mise en jeu, conflits et « Recharger depuis DEV ». Ce qui suit résume les points
qui reviennent souvent en dépannage.

### Correspondance des mots

| Dans l'éditeur | Dans le fichier et les diagnostics |
|---|---|
| Début de la conversation | le nœud `start` |
| Réplique 2, Réplique 3… | un nœud de `nodes` (numéro = ordre d'affichage, pas l'identifiant) |
| Le personnage dit… | `text` du nœud |
| Réponse du joueur | un élément de `choices` |
| Ensuite… → Terminer la conversation | choix sans `next`, avec l'action `CLOSE` |
| Ensuite… → Le personnage répond : … | `next` du choix |
| Conditions et autres effets | `conditions` et `actions` du choix |

### Ce que l'éditeur guidé conserve

Tout ce que le fichier contient est gardé à l'enregistrement, même ce que l'éditeur ne sait pas
nommer : seules les notes `#` écrites à la main ne le sont pas (l'éditeur le signale).
**Enregistrer** sans rien changer ne réécrit pas le fichier. Un fichier que l'éditeur ne sait pas
relire en entier s'ouvre en lecture seule, avec la raison.

### Choisir une couleur sans écrire de balise

Le formulaire de création propose une **palette de couleurs** : cliquer une pastille suffit,
le panel génère le MiniMessage correct (`<yellow>…</yellow>`). Pour un rendu avancé
(dégradés, gras, plusieurs couleurs), écrire directement du MiniMessage dans le champ
texte : le panel **ne le modifie pas**.

### « Enregistré » ne veut pas dire « chargé en jeu »

Le panel distingue trois choses :

1. la version **enregistrée dans PlugAdmin** (bouton **Enregistrer**) ;
2. le **fichier sur le serveur DEV** (écrit par **Mettre en jeu**) ;
3. ce que le jeu a **réellement chargé** pour les joueurs.

Le bandeau de l'éditeur les distingue : « Enregistré — pas encore en jeu », « À jour en jeu »,
« une ancienne version est toujours en jeu »… Après une action, la page se met à jour
**automatiquement** : inutile de recharger la page (F5). Une mise en jeu refusée remet le fichier
DEV dans son état d'avant : les joueurs gardent l'ancienne version.

### Écrans techniques

**Détails techniques** (`/dialogues/diagnostic`, depuis la liste) garde l'ancien écran : relevé
du serveur, graphe des nœuds avec leurs identifiants, comparaison avec la version du serveur et
retour arrière d'une publication. Il sert au **diagnostic avancé** ; le parcours normal n'en a
pas besoin.
