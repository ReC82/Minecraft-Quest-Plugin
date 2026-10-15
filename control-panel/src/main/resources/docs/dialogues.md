---
title: Faire parler un personnage
category: Contenu
tags: [dialogue, dialogues, pnj, réponse, réplique, quête, proposer, mettre en jeu, publier, aperçu]
order: 0
---

# Faire parler un personnage

Un **dialogue**, c'est ce que dit un personnage quand un joueur lui parle, et les réponses que le
joueur peut choisir.

## Le parcours

1. **Dialogues → Créer un dialogue** : choisissez le personnage et écrivez sa première phrase.
   Depuis la fiche d'un PNJ, « Modifier le dialogue » ouvre directement le sien.
2. **Le personnage dit…** : sa réplique.
3. **Le joueur peut répondre…** : une ligne par réponse. Pour chacune :
   - **Ensuite…** : soit le personnage répond (une autre réplique, ou « Une nouvelle réplique… »),
     soit la conversation se termine ;
   - **Et aussi…** : *Proposer une quête précise*, *Proposer une quête au hasard dans une liste*,
     *Faire avancer une quête* ou *Rendre une quête* (qui donne ses récompenses). La quête se
     choisit par son titre, avec une recherche ; la liste, par son nom.
4. **Enregistrer** garde vos modifications dans PlugAdmin.
5. **Mettre en jeu** les envoie au serveur. Le bandeau en haut dit où vous en êtes :

| Bandeau | Ce que ça veut dire |
|---|---|
| Modifications non enregistrées | Vous avez changé quelque chose : enregistrez. |
| Enregistré — pas encore en jeu | Les joueurs voient encore l'ancienne version. |
| Mise en jeu en cours… | Le serveur n'a pas encore répondu. |
| À jour en jeu | Les joueurs voient la version enregistrée. |
| Mise en jeu échouée | Le serveur a refusé : le motif est affiché, corrigez puis réessayez. |

« Enregistrer et mettre en jeu » fait les deux à la suite.

## L'aperçu

**Aperçu de la conversation** rejoue le dialogue : cliquez une réponse pour voir la suite. Une quête
proposée y est indiquée. Rien n'est donné ni démarré pour de vrai.

## Proposer une quête au hasard dans une liste

Pour un **PNJ récurrent** (Brigand des bois…) qui a une liste de quêtes (**Quêtes → Catalogues de
quêtes**) : sur la réponse « Du travail ? », **Et aussi… → Proposer une quête au hasard dans une
liste**, puis **Quelle liste ?**. « Les listes de ce PNJ récurrent » (le choix par défaut) prend
celles qui sont associées au PNJ sur la fiche du catalogue. Le personnage présente lui-même la
quête tirée (titre, but, délai), rappelle une tâche en cours ou dit qu'il n'a rien pour le moment ;
rien n'est donné avant « Commencer (j'accepte) ». Une liste désactivée, vide, introuvable, ou un
PNJ sans liste est signalé sous le choix.

**À vérifier** : quand un PNJ a une liste, l'éditeur liste en haut les passages qui citent encore
**une quête précise** de cette liste — conditions « Quête acceptable maintenant » ou « État d'une
quête », effet « Démarrer une quête », texte qui nomme la quête, entrée d'accueil. Le jeu ne sait
pas tester « la quête tirée dans la liste » : à vous de décider, passage par passage, s'il faut
retirer la condition ou réécrire le texte. **Rien n'est modifié automatiquement**, et rien ne
change en jeu avant « Mettre en jeu ».

Depuis la fiche d'un catalogue, **Configurer le dialogue de ce PNJ** ouvre directement le dialogue
de chaque PNJ récurrent. L'en-tête du dialogue nomme ces PNJ récurrents et leurs listes.

## Un dialogue qui n'existe que sur le serveur

Un dialogue livré avec le plugin ou déposé à la main (« Hors source ») s'affiche en lecture
seule. Pour le modifier : **Rendre ce dialogue modifiable → Lire le dialogue sur le serveur**,
rechargez la page, vérifiez le fichier lu, puis **Copier dans PlugAdmin pour le modifier**. La
copie est **identique octet pour octet** (même empreinte) : rien ne se perd, et **rien ne change
en jeu** — le serveur garde son fichier tant que vous ne mettez pas une nouvelle version en jeu.
Si PlugAdmin a déjà un dialogue de ce nom, ou si le fichier a changé sur le serveur depuis la
lecture, rien n'est copié.

## Conditions et autres effets

Sous chaque réponse, **Conditions et autres effets** (replié) :

- **Cette réponse n'apparaît que si…** : par exemple « État d'une quête : pas encore commencée ».
  « Inverser » veut dire « seulement si ce n'est pas le cas ».
- **Il se passe aussi…** : donner un objet, poser une variable, ouvrir un marchand…

Tout ce qu'un dialogue contient est conservé quand vous l'enregistrez depuis PlugAdmin, même ce que
l'éditeur ne sait pas nommer. Seules les notes écrites à la main dans le fichier (lignes `#`) ne
sont pas gardées ; l'éditeur le signale. **Enregistrer sans avoir rien changé ne réécrit pas le
fichier** : notes et mise en forme restent telles quelles. Les textes écrits sur plusieurs lignes
(`text: >` ou `text: |`) sont relus comme le serveur les lit. Un fichier qu'il ne sait pas relire
entièrement s'ouvre en lecture seule, avec la raison — jamais avec des répliques en moins.

## Supprimer un dialogue

**Supprimer ce dialogue…** (en bas de l'éditeur) ouvre une page courte : quels personnages
l'utilisent, ce qui va se passer, puis « Supprimer » ou « Annuler ». Un dialogue utilisé par un
personnage, ou vers lequel un autre dialogue renvoie, ne peut pas être supprimé : la page donne le
lien pour corriger d'abord.

## Détails techniques

« Détails techniques » (sur la liste) garde l'ancien écran : relevé du serveur, diagnostics,
comparaison avec la version du serveur, retour arrière d'une publication, et l'édition directe des
dialogues livrés avec le plugin. Voir aussi [Dépannage des dialogues](dialogues-depannage).
