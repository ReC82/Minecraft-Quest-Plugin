---
title: Faire parler un personnage
category: Contenu
tags: [dialogue, dialogues, pnj, réponse, réplique, quête, proposer, mettre en jeu, publier, aperçu]
order: 0
---

# Faire parler un personnage

Un **dialogue**, c'est ce que dit un personnage quand un joueur lui parle, et les réponses que le
joueur peut choisir. Tout se fait dans la page **« Dialogue de … »** (par exemple « Dialogue de
Musty ») : pas besoin d'identifiants techniques ni de fichiers.

## D'où vient le dialogue d'un personnage

- **PNJ → Créer un PNJ** : le personnage reçoit tout de suite un dialogue à lui qui dit
  « Salut ! », avec une réponse « Au revoir » qui termine la conversation.
- **PNJ → « Rattacher à RPGQuest »** (un personnage Citizens déjà présent en jeu) : s'il n'a pas
  encore de dialogue, il reçoit le même « Salut ! ». S'il en a déjà un, il le garde.
- **Dialogues → Créer un dialogue** : choisissez le personnage et écrivez sa première phrase.

Pour l'ouvrir : fiche du PNJ → **Dialogue et quêtes → Modifier le dialogue**, ou page
**Dialogues** → cliquez sur le dialogue.

## La page « Dialogue de … »

De haut en bas :

1. **Le bandeau d'état** : enregistré ou non, en jeu ou non (voir
   [Enregistrer, puis mettre en jeu](#enregistrer-puis-mettre-en-jeu)).
2. **Une carte par réplique.** La première s'appelle **Début de la conversation** : c'est ce que
   dit le personnage quand on lui parle. Les suivantes s'appellent **Réplique 2**, **Réplique 3**…
   Dans chaque carte :
   - **Le personnage dit…** : sa phrase (**Entrée** pour aller à la ligne, voir
     [Aller à la ligne dans une réplique](#aller-a-la-ligne-dans-une-replique)) ;
   - **Le joueur peut répondre…** : une ligne par réponse (**Réponse du joueur**). Pour chacune :
     - **Ensuite…** : *Terminer la conversation*, *Le personnage répond : réplique 2* (ou une
       autre réplique, ou le début), ou *Une nouvelle réplique…* qui en crée une juste pour
       cette réponse ;
     - **Et aussi…** : *Proposer une quête précise*, *Proposer une quête au hasard dans une
       liste*, *Faire avancer une quête* ou *Rendre une quête* (qui donne ses récompenses). La
       quête se choisit par son titre, avec une recherche ;
     - **Conditions et autres effets** (replié), voir plus bas ;
     - **Supprimer cette réponse**.
   - **Ajouter une réponse** : une réponse de plus pour le joueur ;
   - **Réglages de la réplique** (replié) : le nom affiché au-dessus de la phrase, et **Retirer
     cette réplique**.
3. **Ajouter une réplique** (sous la dernière carte) : une nouvelle réplique, en bas de la page.
4. **Réglages du dialogue** (replié) : la réplique par laquelle commence la conversation.
5. **Enregistrer** et **Enregistrer et mettre en jeu**.
6. **Aperçu de la conversation**.

### Exemple : ajouter une deuxième réplique

Le personnage dit « Salut ! ». On veut que le joueur puisse demander « Tu vends quoi ? » et que le
personnage réponde « Du pain frais ! ».

1. Sous **Début de la conversation**, cliquez **Ajouter une réponse** et écrivez « Tu vends quoi ? ».
2. Dans **Ensuite…** de cette réponse, choisissez **Une nouvelle réplique…**, puis
   **Enregistrer** : une carte **Réplique 2** apparaît.
3. Dans **Réplique 2 → Le personnage dit…**, écrivez « Du pain frais ! ».
4. Réplique 2 a déjà une réponse « Au revoir. » qui termine la conversation : gardez-la, ou
   changez son texte.
5. **Enregistrer et mettre en jeu**, puis attendez « À jour en jeu ».

Avec **Ajouter une réplique**, la nouvelle carte arrive en bas de la page, mais **aucune réponse
n'y mène encore** : choisissez-la dans **Ensuite…** d'une réponse, sinon le joueur ne la verra
jamais (voir [Une réplique que personne n'atteint](#une-replique-que-personne-natteint)).

## Aller à la ligne dans une réplique

Pour qu'une phrase commence sur une nouvelle ligne dans le jeu, appuyez sur **Entrée** dans
**Le personnage dit…**, comme dans un message :

```
Bienvenue dans ma boulangerie !
Tout est cuit ce matin.
```

L'aperçu juste sous le champ montre le résultat : une ligne par ligne, comme dans le jeu. Rien
d'autre à écrire.

- Une ligne trop longue passe aussi toute seule à la ligne dans le jeu : Entrée sert à choisir
  **où** couper.
- N'écrivez pas `</br>` ni `\n` : le jeu les afficherait tels quels. PlugAdmin vous prévient
  sous le champ si vous en avez écrit un.
- Dans un ancien texte, `<br>` ou `<newline>` veulent aussi dire « à la ligne » : PlugAdmin les
  garde tels quels et les montre comme un retour à la ligne.
- Les réponses du joueur sont des boutons : elles tiennent sur une seule ligne.

## Terminer une conversation

**Chaque réplique doit avoir au moins une réponse.** Le jeu refuse un dialogue dont une seule
réplique n'en a aucune : il ne se charge pas, et les joueurs gardent l'ancienne version.

Pour terminer la conversation après une réplique :

1. sous la réplique, cliquez **Ajouter une réponse** ;
2. écrivez ce que dit le joueur, par exemple « Au revoir. » ;
3. laissez **Ensuite… → Terminer la conversation** (c'est le choix proposé d'office).

Une réplique créée par « Une nouvelle réplique… » ou « Ajouter une réplique » reçoit déjà une
réponse « Au revoir. » qui termine la conversation : modifiez-la ou ajoutez-en d'autres.

Si une réplique n'a aucune réponse, PlugAdmin refuse **avant** d'écrire quoi que ce soit, avec un
message comme « Enregistrement impossible : la réplique 2 n'a pas de sortie. Ajoutez une réponse
qui termine la conversation. » La même vérification bloque **Mettre en jeu**, seul ou dans un lot
(Import contenu) : rien n'est envoyé au serveur. Vos modifications restent dans la page : ajoutez
la réponse, puis enregistrez à nouveau.

## Une réplique que personne n'atteint

Une carte marquée **« Aucune réponse n'y mène »** ne sera **jamais** montrée au joueur : aucune
réponse n'a « Le personnage répond : cette réplique » dans **Ensuite…**. Cela arrive après
**Ajouter une réplique**, ou quand on change la suite d'une réponse.

Pour la rendre visible, choisissez-la dans **Ensuite…** d'une réponse. Si elle ne sert à rien :
**Réglages de la réplique → Retirer cette réplique**. Retirer une réplique ne casse rien : les
réponses qui y menaient terminent alors la conversation (PlugAdmin le dit).

## Enregistrer, puis mettre en jeu

Deux étapes différentes :

- **Enregistrer** garde vos modifications dans PlugAdmin. **Les joueurs ne voient encore rien.**
- **Mettre en jeu** envoie la version enregistrée au serveur DEV, qui la charge tout de suite,
  sans redémarrage.
- **Enregistrer et mettre en jeu** fait les deux à la suite.

Le bandeau en haut de la page dit où vous en êtes. Pendant une mise en jeu, la page se met à jour
toute seule : pas besoin de F5.

| Bandeau | Ce que ça veut dire | Que faire |
|---|---|---|
| Modifications non enregistrées | Vous avez changé quelque chose dans la page. | Enregistrez. |
| Enregistré — pas encore en jeu | Les joueurs voient encore l'ancienne version. | **Mettre en jeu**. |
| Mise en jeu en cours… | Le serveur n'a pas encore répondu. | Attendez quelques secondes. |
| À jour en jeu | Les joueurs voient la version enregistrée. | Testez en jeu. |
| Mise en jeu échouée | Le serveur a refusé : le motif est affiché. | Corrigez, puis **Réessayer**. |
| Enregistré — on ne sait pas encore si le jeu a cette version | PlugAdmin n'a pas encore de nouvelles du serveur. | **Vérifier**. |
| Attention : … une ancienne version est toujours en jeu | Le fichier DEV est le bon, mais le jeu montre autre chose. | Voir [Recharger depuis DEV](#recharger-depuis-dev). |
| Le serveur a une autre version de ce dialogue (encadré rouge) | Quelqu'un a changé le dialogue ailleurs. | Suivez les étapes de l'encadré ([détails](dialogues-depannage#le-serveur-a-une-autre-version-de-ce-dialogue)). |

**« Enregistré » n'est pas « à jour en jeu ».** Ne testez en jeu qu'après avoir vu **À jour en
jeu**.

### Si la mise en jeu est refusée

Une mise en jeu refusée n'est **jamais** un succès :

- le bandeau passe à **Mise en jeu échouée**, avec le motif ;
- le serveur remet son fichier dans l'état d'avant : **les joueurs gardent l'ancienne version**,
  qui continue de fonctionner ;
- votre version reste enregistrée dans PlugAdmin : corrigez-la, enregistrez, puis **Réessayer**.
  Pas de conflit après une correction.

## Tester en jeu

1. Attendez **À jour en jeu** dans le bandeau.
2. Dans Minecraft, faites un clic droit sur le personnage : la fenêtre de dialogue s'ouvre sur
   **Début de la conversation**.
3. Cliquez chaque réponse et vérifiez la suite. Une réponse qui « termine la conversation » ferme
   la fenêtre.
4. Pour revoir le dialogue depuis le début, parlez de nouveau au personnage.

Si le jeu montre encore l'ancien texte alors que le bandeau dit **À jour en jeu**, cliquez
**Vérifier**, puis voir [Recharger depuis DEV](#recharger-depuis-dev).

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

## Recharger depuis DEV

Le bouton **Recharger depuis DEV**, dans le bandeau d'état en haut de l'éditeur, relit la version
**enregistrée sur le serveur DEV** — par exemple après un refus de mise en jeu, ou si quelqu'un a
changé le dialogue ailleurs. Pas besoin de F5 ni des détails techniques.

1. Cliquez **Recharger depuis DEV**. PlugAdmin lit le fichier du serveur : rien n'est encore
   remplacé, rien ne change en jeu.
2. Si vous avez des modifications **non enregistrées**, PlugAdmin le dit d'abord. **Annuler —
   garder mes modifications** ne perd rien. Pour recharger quand même, ouvrez « Recharger quand
   même », cochez la case puis confirmez : une copie de vos modifications est gardée dans les
   sauvegardes de PlugAdmin.
3. Quand la version DEV est lue, PlugAdmin dit si elle est identique à la vôtre ou différente. Si
   elle est différente : **Comparer les versions**, ou **Remplacer ma version par celle de DEV**
   (votre version est sauvegardée avant). Ensuite, l'éditeur montre la version DEV.

**Enregistré sur DEV ≠ en jeu.** Le fichier enregistré sur DEV n'est pas toujours celui que le jeu
utilise. PlugAdmin l'indique séparément : « c'est bien cette version que les joueurs voient »,
« une ancienne version est toujours en jeu », « le jeu ne l'a pas chargé », ou « on ne sait pas
encore » (cliquez **Vérifier**). Recharger ne publie jamais rien dans Minecraft.

## Un dialogue qui n'existe que sur le serveur

Un dialogue livré avec le plugin ou déposé à la main (« Hors source ») s'affiche en lecture
seule. Pour le modifier : **Rendre ce dialogue modifiable → Lire le dialogue sur le serveur**,
rechargez la page, vérifiez le fichier lu, puis **Copier dans PlugAdmin pour le modifier**. La
copie est **identique octet pour octet** (même empreinte) : rien ne se perd, et **rien ne change
en jeu** — le serveur garde son fichier tant que vous ne mettez pas une nouvelle version en jeu.
Si PlugAdmin a déjà un dialogue de ce nom, ou si le fichier a changé sur le serveur depuis la
lecture, rien n'est copié.

## Modifier un dialogue avec une IA

Pour une transformation qui touche beaucoup de conditions, d'effets et de textes (par exemple
passer d'une quête précise à « une quête au hasard dans une liste »), inutile de tout reprendre à
la main : **Modifier avec une IA…** (en haut d'un dialogue enregistré dans PlugAdmin).

1. **Copier le prompt** : il contient le dialogue tel qu'il est **enregistré**, les quêtes qu'il cite
   (avec leur titre), les listes de quêtes de ses PNJ récurrents, et les conditions/effets que le jeu
   sait vraiment exécuter. Les modifications non enregistrées de l'éditeur n'y sont pas.
2. **Le coller dans une IA** (ChatGPT, Claude…) en écrivant ce que vous voulez changer à la place de
   la ligne `<<< ÉCRIS ICI … >>>`. PlugAdmin n'appelle aucune IA.
3. **Importer** le bloc YAML rendu dans **Import contenu**. L'analyse montre **Changements dans le
   dialogue** : répliques, réponses, conditions et effets modifiés, ajoutés ou retirés, avec
   « avant / après ». Les notes `#` du fichier actuel ne sont pas conservées : c'est signalé.
4. **Remplacer l'existant**, puis confirmer : seul le dialogue de PlugAdmin change (une sauvegarde est
   faite avant), **rien n'est en jeu**. Revenir sur le dialogue, vérifier l'aperçu, **Mettre en jeu**.

Le réimport est **refusé** si l'IA a changé l'identifiant du dialogue (il en créerait un second), si
elle cite une quête ou une liste que le serveur ne connaît pas, ou si le dialogue a été modifié dans
PlugAdmin depuis l'export (réexporter alors). Sans relevé du serveur, les références sont dites
« non vérifiées », jamais bonnes.

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
