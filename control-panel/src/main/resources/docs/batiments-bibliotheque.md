---
title: Bibliothèque de bâtiments et pose
category: Bâtiments
tags: [batiment, bibliotheque, schematic, placement, pose, rotation, emprise, worldedit, rollback,
       liberer, reorienter, remplacer, baseline, terrain, historique, tour, version]
order: 2
---

# Bibliothèque de bâtiments, et poser un bâtiment

Un **bâtiment** est ce qu'on peut poser : des dimensions, une ancre, une façade de référence, et un
fichier `.schem`. Un **emplacement** (voir [Emplacements de construction](batiments-emplacements.md))
est l'endroit où on le pose. Ce sont deux choses séparées, et c'est voulu : le même bâtiment peut
être posé sur plusieurs emplacements.

> [!NOTE]
> **Deux bâtiments existent aujourd'hui**, et aucun n'a à être beau :
>
> - la **Hutte de test** (7 × 5 × 6) vérifie l'orientation, les dimensions, l'ancre et la rotation ;
> - la **Tour de garde de test** (9 × 9 × 14) vérifie ce que la hutte ne testait pas : la hauteur,
>   des planchers percés, un escalier intérieur en spirale, des blocs orientés, et **quatre faces
>   franchement différentes** — porte et torches au nord, meurtrières à l'est, ouverture de guet au
>   sud, face aveugle en moellon brut à l'ouest. On doit voir l'orientation d'un coup d'œil.

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

## Un emplacement occupé n'est pas une impasse

Quatre actions, et **une seule** laisse l'emplacement vide :

| Action | Ce qu'elle fait | Ce qui reste après |
|---|---|---|
| **Calculer l'aperçu** | n'écrit **rien** — ni bloc, ni ligne en base | inchangé |
| **Réorienter** | rend le terrain d'origine, puis recolle le **même** bâtiment dans une autre orientation | un bâtiment |
| **Remplacer** | idem, avec un **autre** bâtiment | un bâtiment |
| **Restaurer le terrain et libérer** | rend le terrain d'origine et libère l'emplacement | rien — et l'emplacement est **immédiatement réutilisable** |

## L'orientation du site n'est pas celle du bâtiment

Un emplacement est une **intention**, un bâtiment posé est un **fait**. Changer l'orientation d'un
emplacement **ne déplace aucun bloc** — la fiche affiche alors les deux lignes et vous avertit :

```
Orientation souhaitée du site : NORTH
Orientation du bâtiment posé  : 180°
⚠ L'orientation de l'emplacement a changé après le placement.
  Le bâtiment physique n'a pas été modifié.
```

Pour aligner les deux, utilisez **Réorienter**. Rien ne bouge avant votre confirmation.

> [!NOTE]
> **Réorienter ne fait pas tourner les blocs déjà posés.** Le terrain d'origine est rendu, puis le
> bâtiment est recollé dans la nouvelle orientation. Le résultat est donc identique à une pose
> initiale dans cette orientation — c'est ce qui le rend reproductible, essai après essai.

## Le terrain d'origine, et pourquoi ce n'est pas « l'état d'avant »

C'est la distinction la plus importante de cette page.

- Chaque opération prend une **sauvegarde de compensation** : elle sert à remettre l'ancien état si
  un collage échoue en cours de route.
- Le **terrain d'origine** est conservé **à part**, et n'est jamais réécrit.

Après *hutte → tour → autre orientation*, la sauvegarde de la dernière opération ne contient plus le
terrain : elle contient le bâtiment précédent. « Restaurer le terrain et libérer » rend donc l'état
d'avant le **premier** bâtiment, quel que soit le nombre d'essais depuis.

Dans `plugins/RPGQuest/schematics/`, le nom dit le rôle :

| Préfixe | Rôle | À supprimer ? |
|---|---|---|
| `origine_` | terrain d'origine d'un emplacement | **jamais** tant que l'emplacement existe |
| `compens_` | compensation d'une transformation | après coup, sans risque |
| `backup_` | capture d'une pose dont l'origine est déjà conservée ailleurs | après coup, sans risque |

## Aperçu d'abord, confirmation ensuite

Le bouton qui écrit dans le monde **n'existe pas** avant d'avoir calculé un aperçu. L'aperçu montre
l'ancienne et la nouvelle emprise, leur recouvrement, et les blocs non-air déjà présents.

Il porte aussi un **jeton**. Si l'emplacement, le bâtiment posé ou la définition visée changent
entre l'aperçu et votre confirmation, l'opération est **refusée** — plutôt que d'appliquer une
décision prise sur un état qui n'existe plus. Relancez l'aperçu et recommencez.

## Si le collage échoue

L'ancien bâtiment est **remis en place** et l'opération n'a simplement pas eu lieu. La fiche n'est
écrite qu'après un collage réussi : il n'y a jamais d'emplacement annoncé transformé avec un monde
vide.

## Versions : le bâtiment posé ne change jamais tout seul

Un placement mémorise la **version** de la définition et l'**empreinte** du fichier réellement collé.
Si la définition change ensuite, la fiche l'annonce :

> ⚠ Une version plus récente de « Hutte de test » existe dans la bibliothèque (v2).

Et elle s'arrête là. Pour appliquer la nouvelle version, faites un **Remplacer** par le même
bâtiment : c'est un geste explicite, jamais un effet de bord d'une édition de fichier.

## Journal des opérations

Bouton **Relever le journal** sur la fiche. Il répond à une seule question — « qu'est-ce qui a été
posé ici, dans quel ordre, par qui, et est-ce que ça a marché ? ». Les **échecs y figurent** : un
journal qui ne garderait que les succès serait muet au moment exact où on le consulte.

> [!WARNING]
> **Tout ce qui a été construit dans l'emprise *après* la pose sera également écrasé** par une
> libération ou une transformation. La restauration repose un instantané ; elle ne fait pas la
> différence entre le bâtiment et ce que vous avez ajouté autour depuis.

### Quand une opération est refusée

- **Aucune sauvegarde du terrain** → refusé. Remettre de l'air dans l'emprise détruirait le terrain
  d'origine : ce serait une destruction déguisée en annulation. Le bouton n'apparaît même pas.
- **Fichier de sauvegarde introuvable** → refusé, pour la même raison : mieux vaut un refus qu'une
  restauration à moitié faite, qui ressemblerait à un succès.
- **Réorienter vers l'orientation déjà en place** → refusé : réécrire des blocs pour rien n'a pas
  de sens.
- **Emprise hors des limites du monde**, ou **chevauchant un autre bâtiment posé** → refusé, et
  l'aperçu le dit avant que vous ne confirmiez.
- Si une restauration échoue, l'emplacement **reste occupé** : la fiche ne mentira pas sur l'état du
  monde.

---

## Permissions

| Permission | Qui l'a | Ce qu'elle donne |
|---|---|---|
| **lecture** | Propriétaire, Administrateur, Builder, Testeur | voir la bibliothèque et les fiches |
| **poser** | Propriétaire, Administrateur | écrire dans le monde |
| **poser** | Propriétaire, Administrateur | poser, **réorienter** et **remplacer** |
| **libérer** | Propriétaire, Administrateur | rendre le terrain d'origine et **vider** l'emplacement |

La ligne entre les deux droits n'est pas la dangerosité mais le **résultat** : après une pose, une
réorientation ou un remplacement, il y a toujours un bâtiment ; après une libération, il n'y en a
plus.

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
