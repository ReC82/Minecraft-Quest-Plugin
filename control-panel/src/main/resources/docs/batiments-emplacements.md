---
title: Emplacements de construction
category: Bâtiments
tags: [batiment, construction, emplacement, buildsite, ancre, orientation, outil, schematic]
order: 1
related: [batiments-bibliotheque, villes]
---

# Emplacements de construction

Un **emplacement** est un point d'ancrage nommé dans un monde : « ici, orienté comme ça, on posera
quelque chose ». C'est la première brique du système de bâtiments.

> [!NOTE]
> Un emplacement ne contient **encore aucun bâtiment**. Affecter un schematic et le poser
> appartiennent au lot suivant. Ce socle sert à situer, nommer et retrouver les futurs chantiers.

---

## Créer un emplacement : en jeu, pas dans le panel

Il n'y a **volontairement aucun bouton « Créer »** dans PlugAdmin. Un point d'ancrage se désigne du
doigt ; un formulaire web devrait inventer des coordonnées.

1. En jeu : `/rpgadmin buildsite tool` → vous recevez l'**outil d'emplacement de construction**
   (une houe en fer).
2. Placez-vous, puis **regardez dans la direction que devra avoir la façade**.
3. **Clic droit** sur le bloc visé. Une **enclume** s'ouvre : rien n'est encore enregistré.
4. **Tapez le nom** de l'emplacement, puis **cliquez l'objet de résultat** (à droite dans l'enclume).
5. Message de confirmation avec le nom, l'identifiant attribué (par exemple `buildsite_0001`), le
   monde, la position et l'orientation.
6. Dans PlugAdmin : **Bâtiments → Emplacements → Rafraîchir**.

Le **clic gauche ne crée rien** : il est seulement neutralisé, pour que l'outil ne casse pas de bloc.

### Le clic seul n'enregistre rien

C'est la protection contre le clic de travers : tant que vous n'avez pas validé le nom, **aucun
emplacement n'existe**.

| Ce que vous faites | Ce qui est enregistré |
|---|---|
| clic droit sur un bloc | **rien** |
| nom tapé, puis clic sur le résultat | l'emplacement, avec son nom |
| vous fermez l'enclume (Échap) | **rien**, et le jeu vous le confirme |
| vous attendez plus de **60 secondes** | **rien** — la demande expire |
| vous vous déconnectez pendant la saisie | **rien** |

Aucun identifiant n'est « consommé » par une demande abandonnée : vingt clics annulés ne font pas
sauter vingt numéros.

> [!NOTE]
> Le nom est **obligatoire** : 1 à 64 caractères, accents et émojis acceptés. Un nom vide ou trop
> long est refusé **sans fermer la fenêtre** — vous corrigez sur place. Il est toujours modifiable
> ensuite depuis le panel, et il n'a **aucun lien** avec l'identifiant technique.

> [!TIP]
> Si un emplacement se trouve **à un bloc** de celui que vous préparez, le jeu vous avertit avant que
> vous validiez. Ce n'est pas un refus — deux emplacements voisins sont permis — mais à un bloc près,
> c'est bien plus souvent un clic de travers. Fermez la fenêtre et rien n'aura été créé.

### Où l'emplacement est enregistré, exactement

**Sur la case libre contre la face cliquée.** Cliquer le dessus du sol enregistre la case **juste
au-dessus** — celle où vous vous tiendriez, et où reposera le premier niveau du bâtiment.

| Vous cliquez | L'ancre est |
|---|---|
| le **dessus** d'un bloc de sol en `y=66` | `y=67`, au-dessus |
| la face **nord** d'un mur | un bloc au nord du mur |
| le **dessous** d'un plafond | un bloc en dessous |

C'est voulu : un bâtiment ne s'enfonce pas d'un bloc dans le terrain. Si l'ancre était le bloc
cliqué, chaque placement devrait ajouter « +1 en hauteur » de son côté — et un jour l'un l'oublierait.

Cliquer le dessus du bloc le plus haut d'un monde est **refusé** : l'ancre serait hors limites.

### L'orientation

Elle vient de **votre regard** au moment du clic, ramené aux quatre points cardinaux : nord, est,
sud, ouest. Rien de plus fin n'est conservé — un bâtiment se pose aligné sur la grille du monde.

Si vous vous trompez de façade, ce n'est pas grave : **l'orientation se corrige depuis le panel**.
La **position**, elle, ne se corrige pas depuis un écran — il faut retourner marquer l'emplacement
en jeu, puis supprimer l'ancien.

---

## L'identifiant, le nom, et pourquoi ce n'est pas la même chose

| | Exemple | Rôle |
|---|---|---|
| **Identifiant** | `buildsite_0001` | attribué par le serveur, **ne change jamais**, **jamais réattribué**. C'est lui qu'un futur bâtiment citera. |
| **Nom** | « Taverne du village » | libellé humain, **saisi dans l'enclume à la création**, modifiable à volonté. L'identifiant n'en est **jamais** dérivé. |

Un emplacement supprimé ne rend **pas** son identifiant : le suivant prend le numéro d'après. C'est
ce qui garantit qu'un futur placement ne pointera jamais sur le mauvais emplacement.

---

## Gérer un emplacement depuis PlugAdmin

**Bâtiments → Emplacements**. Un emplacement est un **lieu** : la page montre, pour chacun,
identifiant, nom, monde, position, orientation, état, date de création et auteur.

Depuis la fiche, vous pouvez :

- **renommer** — libellé humain uniquement ;
- écrire la **description du lieu** — une ou deux phrases : où il se trouve, à quoi il sert
  (500 caractères, compteur affiché ; au-delà, l'enregistrement est refusé, jamais coupé) ;
- **corriger l'orientation** — la position ne bouge pas ;
- **poser un bâtiment de la bibliothèque**, puis le **retirer en restaurant le terrain** (voir la
  page Bibliothèque) ;
- **supprimer** le repère (voir plus bas).

> [!NOTE]
> **On ne conçoit plus un bâtiment depuis un emplacement** (issues #265, #268). Un bâtiment se
> conçoit une fois, dans **Bâtiments → Atelier de création**, puis se pose sur autant
> d'emplacements que vous voulez. Depuis la Bibliothèque, **« Placer sur un emplacement »** vous
> ramène ici avec le bâtiment déjà choisi.

### Et la description de bâtiment écrite autrefois sur l'emplacement ?

Elle n'a été **ni effacée ni déplacée**. La fiche l'affiche, repliée et en lecture seule, avec ce
qu'elle est devenue :

- **« Reprendre dans l'Atelier »** : la copie dans un nouveau projet de l'Atelier, avec les plans
  déjà demandés pour cet emplacement. L'emplacement lui-même n'est pas modifié, et rien n'est
  envoyé au serveur Minecraft. Une seconde reprise ouvre le même projet.
- une fois reprise, la fiche affiche **le lien vers le projet** : c'est là qu'elle se modifie.

Recherche et **filtre par monde** sont disponibles au-dessus de la liste.

### « Aucun relevé chargé »

Le panel ne devine rien : il affiche le dernier relevé du serveur. Cliquez sur **Rafraîchir** après
avoir marqué un emplacement en jeu, sinon il n'apparaîtra pas.

### « Monde non chargé »

Un emplacement dont le monde n'est pas chargé en ce moment reste **parfaitement valide**. Il est
simplement signalé, parce qu'il n'est pas visitable tant que le monde dort.

---

## Deux emplacements créés par erreur ?

C'est prévu, et c'est normal : les premiers essais tombent rarement au bon endroit.

- **Re-cliquer exactement le même bloc** n'ouvre même pas la fenêtre de nom : le jeu vous rappelle
  l'emplacement qui existe déjà.
- **Un spam de clics** est absorbé : un clic droit Minecraft émet souvent deux événements, et une
  courte fenêtre d'anti-rebond les regroupe.
- Depuis #227, **le clic seul ne crée rien** : la plupart des erreurs se referment d'elles-mêmes, et
  il n'y a plus de fiche à aller supprimer dans le panel.
- Deux emplacements **voisins** sont en revanche autorisés — une maison et son puits sont deux
  points d'ancrage légitimes. Aucune distance minimale n'est imposée.

---

## Supprimer un emplacement

Fiche → en bas, **« Supprimer l'emplacement (le repère seulement) »** → cocher la case → confirmer.

> [!IMPORTANT]
> **Aucun bloc du monde n'est modifié.** Seul le repère disparaît.

- Sur un emplacement **occupé**, la suppression est **refusée** : elle laisserait un bâtiment sans
  moyen de restaurer le terrain. Retirez d'abord le bâtiment (**« Retirer le bâtiment et restaurer
  le terrain »**, en tête de la section « Bâtiment posé »).
- L'opération est **idempotente** : supprimer deux fois le même emplacement dit simplement « rien à
  supprimer », jamais une erreur.
- L'identifiant supprimé **n'est jamais réattribué**.

---

## Permissions

| Qui | Ce qu'il peut |
|---|---|
| En jeu : `rpgquest.admin.buildsite` (ou l'ombrelle `rpgquest.admin.world`) | obtenir l'outil, créer un emplacement, lister |
| Panel, **lecture** | Propriétaire, Administrateur, **Builder**, Testeur |
| Panel, **modifier la fiche** | Propriétaire, Administrateur |
| Panel, **supprimer** | Propriétaire, Administrateur |

Le nœud en jeu et les permissions du panel sont **indépendants** : l'un gouverne un clic dans le
monde, les autres un formulaire web. Aucune permission du panel ne permet de créer un emplacement.

La permission en jeu n'a **aucun rapport avec WorldEdit** : l'outil ne sélectionne aucune région et
ne modifie aucun bloc. Et il ne perturbe pas la hache WorldEdit — il n'utilise pas le même objet, et
il n'est reconnu que par son marquage interne, jamais par son nom.

> [!WARNING]
> Un joueur qui récupérerait l'outil (coffre, mort d'un administrateur) **ne peut rien créer** : la
> permission est vérifiée à chaque clic, et il reçoit le nom de la permission manquante.

---

## Voir aussi

- **Atelier de création** : concevoir un bâtiment, avec l'aide de l'IA.
- **Bibliothèque** : les bâtiments prêts à poser, la pose et la restauration du terrain.
