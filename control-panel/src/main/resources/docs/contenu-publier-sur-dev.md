---
title: Publier du contenu sur DEV
category: Contenu
tags: [publication, publier, dev, synchronise, source, conflit, rollback, reload, deploiement]
order: 1
---

# Publier du contenu sur DEV

**Enregistrer** et **publier** sont deux gestes différents, et c'est la chose la plus importante de
cette page.

| Geste | Ce qu'il fait | Ce qu'il ne fait pas |
|---|---|---|
| **Enregistrer** | écrit dans la **source éditable** (côté AWS) | ne change **rien** en jeu |
| **Publier sur DEV** | copie ce seul fichier sur le serveur, recharge, et **vérifie** | ne compile rien, ne redémarre rien |

> [!IMPORTANT]
> **Publier ne lance aucun build et ne redémarre pas le serveur.** C'était le verrou que
> l'issue #47 a supprimé : avant, une quête créée dans PlugAdmin restait inerte jusqu'à ce qu'un
> script de déploiement externe copie son YAML.

---

## Les états, et ce qu'ils veulent dire

Le même vocabulaire sur toutes les pages. Chaque fiche de quête, de story et de dialogue porte une
section **Publication sur DEV** qui l'affiche.

| État | Ce que ça veut dire | Ce qu'il faut faire |
|---|---|---|
| **Synchronisé** | fichier identique **et** le moteur la voit en jeu | rien |
| **Source uniquement** | enregistrée, mais absente du serveur : elle n'existe pas en jeu | **Publier sur DEV** |
| **Différent** | les deux existent, mais pas avec le même contenu : vos modifications ne sont pas en jeu | **Republier** |
| **Publié, non chargé** | le fichier est là, mais le moteur ne le charge pas | vérifier l'identifiant, puis republier |
| **Conflit** | le fichier DEV a été modifié **hors du panel** | regarder avant de décider |
| **Hors source** | chargée par le serveur, absente de la source : elle vient du JAR ou a été déposée à la main | rien à publier |
| **État inconnu** | aucun relevé du serveur | cliquer **« État DEV »** |

> [!NOTE]
> **« Synchronisé » se mérite.** Il n'est affiché qu'après une **relecture du runtime** confirmant
> que le moteur voit la ressource. Ni la copie du fichier, ni l'envoi de l'action, ni la demande de
> rechargement ne suffisent.
>
> C'est un changement réel : avant #47, « Synchronisé » voulait seulement dire « présente des deux
> côtés ». Une quête modifiée mais pas republiée s'affichait donc « Synchronisé », ce qui était faux.

### « État inconnu » au premier affichage

C'est normal, et c'est voulu. Le panel ne devine pas l'état du serveur : cliquez sur **« État
DEV »** dans la barre « Catalogue » pour le relever. Sans ce relevé, aucun bouton **Publier**
n'apparaît — afficher « Source uniquement » sans avoir interrogé le serveur serait une affirmation
gratuite.

---

## Publier, étape par étape

1. Ouvrez la fiche de la ressource (**Quêtes**, **Stories** ou **Dialogues**).
2. Section **Publication sur DEV** : lisez l'état, l'empreinte **Source** et l'empreinte
   **Serveur DEV**.
3. Cochez la case de confirmation, puis **Publier sur DEV**.
4. Attendez le résultat de l'action, puis **Rafraîchir** : l'état doit passer à **Synchronisé**.

Ce que le serveur fait, dans cet ordre :

1. il revérifie que le fichier DEV est bien celui que vous avez vu ;
2. il **sauvegarde** le fichier existant — s'il y en a un ;
3. il écrit le nouveau fichier ;
4. il recharge **la seule famille concernée** (quêtes, ou stories, ou dialogues) ;
5. il relit le runtime et confirme que le moteur porte la ressource.

> [!IMPORTANT]
> Si la **sauvegarde** échoue, **rien n'est publié**. Si le **collage** échoue, l'état précédent est
> remis en place. Si le **rechargement** échoue, le message vous dit que le fichier *est* écrit — et
> où se trouve la sauvegarde. On ne vous annonce jamais un succès qu'on n'a pas constaté.

---

## Les refus, et pourquoi ils sont là

### « Le fichier DEV a changé depuis votre dernière analyse »

Quelqu'un — ou quelque chose — a modifié le fichier sur le serveur entre le moment où vous avez
regardé et le moment où vous avez cliqué. Publier écraserait ce travail **en silence**, donc on
refuse. Relevez l'état DEV à nouveau, regardez ce qui a changé, puis décidez.

### « La source a changé depuis votre analyse »

La même protection, dans l'autre sens : la ressource a été réenregistrée entre l'affichage et
l'envoi. Publier enverrait une version que vous n'avez pas vue.

### « Le moteur ne voit pas … »

Le fichier est bien sur le serveur et la famille a été rechargée, mais le moteur ne porte pas
l'identifiant attendu. Presque toujours la même cause : **l'identifiant déclaré dans le YAML ne
correspond pas au nom du fichier**. L'état devient « Publié, non chargé ».

### « Identifiant de ressource invalide »

Un identifiant ne contient que des minuscules, des chiffres, `_` et `-`. Aucun séparateur de
chemin : le navigateur n'a pas le droit de désigner un fichier, il désigne une **ressource**.

---

## Revenir en arrière

Le bouton n'apparaît **que** si une publication a réellement eu lieu depuis le panel, et son
libellé dit ce qu'il va faire :

| Situation | Bouton | Effet |
|---|---|---|
| la ressource existait avant, une sauvegarde a été prise | **Restaurer la version précédente** | repose la sauvegarde, puis recharge |
| la ressource a été **créée** par la publication | **Retirer de DEV** | supprime le fichier, puis recharge |

> [!WARNING]
> On ne vous proposera **jamais** de « restaurer » une ressource qui n'avait pas de version
> précédente. Remettre de l'air ou un fichier vide à sa place ne serait pas un retour arrière, ce
> serait une destruction déguisée en annulation.

---

## Ce qui est publiable, et ce qui ne l'est pas

Trois familles seulement : **quêtes**, **stories**, **dialogues**. Ce sont celles dont la source de
vérité est un fichier YAML éditable depuis le panel.

| Famille | Pourquoi elle n'est pas dans la liste |
|---|---|
| **PNJ** | déjà appliqués au runtime par des actions dédiées (définition, lien Citizens) — les forcer à passer par une copie de fichier inventerait un second chemin pour un problème résolu |
| **Mobs spéciaux / Boss** | même raison : le serveur écrit déjà leurs définitions depuis le panel |
| **Objets / Recettes** | **rien ne les crée depuis le panel aujourd'hui** : seuls des exemples livrés avec le plugin existent. Il n'y a donc rien à publier — c'est une fonctionnalité manquante, pas un problème de publication |

> [!NOTE]
> Cette liste est une **liste blanche côté serveur**. `data.db`, les mondes, les secrets, les
> données Citizens brutes et le JAR du plugin sont hors d'atteinte par construction : le navigateur
> n'envoie jamais de chemin, seulement une famille et un identifiant, et c'est le serveur qui en
> déduit le fichier.

### Les dialogues et les scalaires repliés

Publier un dialogue copie le fichier **tel quel**. La publication ne réécrit pas le YAML, donc elle
ne peut pas reformater un bloc de texte replié au passage.

> [!WARNING]
> C'est l'**éditeur guidé** qui réécrit le YAML, et c'est lui qui porte la limitation connue sur les
> scalaires repliés (`guard.yml`). Publier un dialogue est sûr ; l'éditer depuis le panel puis le
> publier peut en revanche reformater le fichier.

---

## Qui a le droit

| Droit | Qui l'a |
|---|---|
| voir l'état | tous ceux qui lisent le contenu |
| **publier sur DEV** | Propriétaire, Administrateur |
| **revenir en arrière** | Propriétaire, Administrateur |

L'**Éditeur de contenu** peut écrire et valider la source, mais **ne peut pas publier**. C'est
volontaire : enregistrer est réversible et sans effet sur le jeu ; changer ce qui tourne sur le
serveur de test ne l'est pas de la même façon.

Chaque publication est **journalisée** : qui, quelle ressource, les empreintes avant et après, la
sauvegarde, le résultat du rechargement et la vérification du runtime.

---

## Et Git ?

Publier sur DEV n'est **ni** un commit, **ni** une fusion, **ni** une construction du JAR, **ni**
une release. C'est une copie contrôlée d'un fichier de contenu vers le serveur de test, pour pouvoir
l'essayer en jeu.

La source reste à committer normalement si vous voulez la conserver dans le dépôt.
