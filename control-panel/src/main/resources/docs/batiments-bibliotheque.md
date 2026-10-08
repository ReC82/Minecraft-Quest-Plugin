---
title: Bibliothèque de bâtiments et pose
category: Bâtiments
tags: [batiment, bibliotheque, schematic, placement, pose, rotation, emprise, worldedit, rollback]
order: 2
---

# Bibliothèque de bâtiments, et poser un bâtiment

Un **bâtiment** est ce qu'on peut poser : des dimensions, une ancre, une façade de référence, et un
fichier `.schem`. Un **emplacement** (voir [Emplacements de construction](batiments-emplacements.md))
est l'endroit où on le pose. Ce sont deux choses séparées, et c'est voulu : le même bâtiment peut
être posé sur plusieurs emplacements.

> [!NOTE]
> Un seul bâtiment existe aujourd'hui : la **Hutte de test**. Elle n'a pas à être belle — elle sert
> à vérifier visuellement l'orientation, les dimensions, l'ancre, la rotation et la hauteur.

---

## La bibliothèque est en lecture seule, volontairement

**Bâtiments → Bibliothèque**. Vous y voyez, pour chaque bâtiment : nom, identifiant, dimensions,
ancre, façade de référence, matériaux, et si le fichier `.schem` est **présent ou absent**.

Il n'y a **aucun bouton pour créer, téléverser ou modifier**. Un bâtiment est du contenu
versionné : il se modifie dans son fichier `plugins/RPGQuest/buildings/<id>.yml` sur le serveur,
puis `/rpgadmin building reload`. Un formulaire web qui l'écrirait contournerait la relecture — ce
qu'on ne veut pas pour quelque chose qui **écrase des blocs réels**.

### « Schematic absent »

La définition est correcte, c'est le **fichier** qui manque. Le plugin produit celui de la hutte de
test au démarrage s'il n'existe pas ; sinon, `/rpgadmin building generate` le réécrit.

> [!IMPORTANT]
> Le fichier de la hutte de test est **généré par le plugin lui-même**, à partir d'un plan écrit en
> Java. Aucun fichier n'a été importé de l'extérieur. C'est ce qui permet d'affirmer qu'il mesure
> bien 7 × 5 × 6 et que sa porte est centrée : un plan se relit, un fichier binaire non.

### « Moteur de schematics indisponible »

WorldEdit n'est pas installé, est désactivé, ou sa version ne correspond pas. La bibliothèque reste
consultable, mais **rien ne peut être posé** — et aucun bouton ne prétend le contraire.

---

## L'ancre, et la convention à connaître

L'ancre d'un bâtiment est le **centre de la porte, au niveau du sol**. C'est ce point qui tombe
exactement sur l'ancre de l'emplacement, et c'est le **point fixe de la rotation**.

Pour la hutte de test, l'ancre est `3 / 1 / 0`. Le `1` est important :

| Partie de la hutte | Où elle arrive |
|---|---|
| fondation en pierre | **un bloc sous** l'ancre de l'emplacement |
| seuil de la porte | exactement **sur** l'ancre de l'emplacement |
| faîte du toit | 4 blocs au-dessus |

C'est voulu : une fondation s'enfonce dans le sol, et l'ancre d'un emplacement est précisément la
case libre **au-dessus** du bloc que vous avez cliqué.

### La façade de référence

`front` déclare la direction que regarde la façade **quand la rotation est nulle**. Pour la hutte,
c'est **nord**, et cette déclaration est géométriquement vraie : la porte est sur la paroi du fond
du schematic, celle qui regarde les Z négatifs.

---

## Poser un bâtiment : deux étapes

Depuis **Bâtiments → Emplacements**, ouvrez la fiche d'un emplacement **vide**.

### 1. Choisir un bâtiment — rien n'est écrit

1. Sélectionnez le bâtiment, puis **Choisir un bâtiment**.
2. Un **aperçu** apparaît. À cette étape, **aucun bloc n'a été touché** et rien n'est enregistré.

L'aperçu vous donne :

- la **rotation appliquée** (0°, 90°, 180° ou 270°) pour amener la façade sur l'orientation de
  l'emplacement ;
- l'**emprise calculée**, en `x1..x2 / y1..y2 / z1..z2` — c'est exactement ce qui sera remplacé ;
- les **dimensions de l'emprise**, qui ne sont pas toujours celles du bâtiment (voir ci-dessous) ;
- le **nombre de blocs non-air déjà présents**, à titre indicatif ;
- tout ce qui **empêche** la pose, et tout ce qui **mérite attention**.

> [!TIP]
> **À 90° et 270°, la largeur et la profondeur s'échangent.** Une hutte de 7 × 5 occupe 5 × 7 une
> fois tournée d'un quart de tour. L'aperçu l'affiche et le dit — c'est l'erreur la plus facile à
> faire en estimant une emprise de tête.

### 2. Placer dans le monde — là, ça écrit

Cochez la case de confirmation, puis **Placer dans le monde**.

Ce qui se passe, dans cet ordre :

1. la zone de l'emprise est **sauvegardée** dans un fichier ;
2. le bâtiment est collé, tourné, ancré sur la porte ;
3. le placement est enregistré et l'emplacement passe à **occupé**.

> [!IMPORTANT]
> Si la sauvegarde échoue, **rien n'est collé**. Si le collage échoue, l'emplacement **reste vide**
> et aucun placement n'est enregistré. Il n'y a pas de « faux placement » possible.

Un **double clic** ne pose pas deux fois : le second est refusé, et la base elle-même n'accepte
qu'un seul bâtiment par emplacement.

---

## Ce que l'aperçu refuse

| Motif | Pourquoi |
|---|---|
| emplacement déjà occupé | un emplacement porte au plus un bâtiment — retirez d'abord |
| monde non chargé | on ne pose pas dans un monde qui dort |
| emprise hors des limites du monde | elle ne serait pas constructible |
| chevauchement avec un bâtiment **posé** | la matière ne peut pas se superposer |
| fichier `.schem` absent ou illisible | il n'y a rien à coller |
| dimensions du fichier ≠ définition | l'emprise annoncée serait fausse, et la sauvegarde incomplète |
| moteur indisponible | rien ne peut coller |

> [!NOTE]
> **Deux emplacements voisins restent permis** (c'est la règle de #213). Ce qui est refusé, c'est le
> chevauchement de deux bâtiments **réellement posés**.
>
> Et **du terrain naturel n'empêche rien** : de l'herbe, de la terre, des fleurs à l'endroit où vous
> bâtissez sont la situation normale. Au-delà de la moitié de l'emprise, vous êtes simplement
> **averti** — au cas où il s'agirait d'une construction existante.

---

## Annuler une pose

Fiche de l'emplacement occupé → **Annuler la pose** → cocher → **Restaurer la zone d'avant**.

La zone est remise **exactement** telle qu'elle était avant la pose, depuis la sauvegarde prise à ce
moment-là. Puis l'emplacement redevient vide.

> [!WARNING]
> **Tout ce qui a été construit dans l'emprise *après* la pose sera également écrasé.** La
> restauration repose un instantané ; elle ne fait pas la différence entre la hutte et ce que vous
> avez ajouté autour depuis.

### Quand le retour arrière est refusé

- **Aucune sauvegarde associée** au placement → refusé. Remettre de l'air dans l'emprise
  détruirait le terrain d'origine : ce serait une destruction déguisée en annulation, pas un retour
  arrière. Le bouton n'apparaît même pas.
- **Fichier de sauvegarde introuvable** → refusé, pour la même raison.
- Si la restauration échoue, l'emplacement **reste occupé** : la fiche ne mentira pas sur l'état du
  monde.

---

## Permissions

| Permission | Qui l'a | Ce qu'elle donne |
|---|---|---|
| **lecture** | Propriétaire, Administrateur, Builder, Testeur | voir la bibliothèque et les fiches |
| **poser** | Propriétaire, Administrateur | écrire dans le monde |
| **annuler** | Propriétaire, Administrateur | restaurer une zone |

Poser et annuler sont **deux permissions distinctes**, et distinctes de l'édition de fiche :
renommer un emplacement ne change rien dans le jeu, poser un bâtiment écrase des blocs réels, et
annuler écrase aussi ce qui a été ajouté depuis. Trois risques différents, trois droits différents.

Le **Builder** consulte mais ne pose pas : c'est volontaire pour cette première livraison.

---

## Ce qui n'existe pas encore

- aucune **génération IA** de bâtiment, aucune analyse d'image ;
- aucun **import** de schematic depuis le panel ;
- aucun **aperçu visuel en jeu** de l'emprise avant la pose ;
- aucun **versioning** de bâtiment, ni remplacement d'un bâtiment posé par un autre ;
- la **position** d'un emplacement reste non modifiable depuis le panel.
