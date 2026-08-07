# `/rpgadmin flatten` — aplatissement de terrain

Outil d'administration (`rpgquest.admin.world`) pour aplatir rapidement une
zone autour d'un joueur — utile pour préparer le terrain d'un village ou
d'une construction, sans WorldEdit ni autre dépendance externe.

## Commandes

-   `/rpgadmin flatten <rayon> [hauteur]` — calcule un **aperçu** (ne touche
    à aucun bloc) centré sur la position du joueur. `hauteur` optionnelle
    (niveau Y du bloc final) ; par défaut, le bloc sur lequel le joueur se
    tient.
-   `/rpgadmin flatten confirm` — exécute l'aperçu en attente.
-   `/rpgadmin flatten cancel` — annule l'aperçu en attente, ou arrête une
    exécution en cours (le travail déjà fait à cet instant reste, `undo`
    permet de le défaire).
-   `/rpgadmin flatten undo` — annule le **dernier** aplatissement terminé
    ou interrompu (un seul niveau d'annulation, écrasé par le suivant).

Toujours exécutée par un joueur en jeu — jamais la console, qui n'a pas de
position à centrer et la syntaxe ne prend aucune coordonnée explicite.

## Comportement

Pour chaque colonne (x, z) de la zone :

1.  tout ce qui est **au-dessus** du niveau cible jusqu'à
    `clear-above-height` blocs plus haut est remplacé par de l'air ;
2.  les `sub-layer-depth` blocs **sous** le niveau cible sont remplacés par
    `sub-layer-material` (`DIRT` par défaut) ;
3.  le bloc au niveau cible devient `top-layer-material` (`GRASS_BLOCK` par
    défaut).

Un bloc déjà correct n'est jamais réécrit (ni compté dans le budget par
tick, ni dans l'annulation).

## Sécurité et performance

-   **Aperçu obligatoire avant exécution** : `/rpgadmin flatten <rayon>`
    calcule le nombre de colonnes et une estimation majorante du nombre de
    blocs, sans rien modifier. Expire après `confirmation-timeout-seconds`
    (30 s par défaut) si `confirm` n'est pas appelé à temps.
-   **Traitement par lots, jamais tout d'un coup** : une tâche répétée
    (1 tick) applique au plus `blocks-per-tick` écritures de bloc par tick
    (4000 par défaut), pour ne jamais geler le serveur sur une grande zone.
-   **Progression sans spam** : une actionbar mise à jour environ une fois
    par seconde, jamais à chaque tick.
-   **Annulation unique** : chaque aplatissement (terminé ou interrompu par
    `cancel`) enregistre l'état précédent de chaque bloc réellement modifié ;
    `undo` restaure cet état. Un nouvel aplatissement remplace
    l'enregistrement précédent (un seul niveau, pas une pile).
-   **Rayon maximal configurable** (`max-radius`, 48 par défaut) et
    **mondes interdits** (`forbidden-worlds`, vide par défaut — pensé pour
    être recroisé avec le registre de zones de l'étape 13 une fois
    disponible, voir `docs/ARCHITECTURE.md`).
-   Aucune I/O bloquante : uniquement des écritures de bloc via l'API
    Bukkit publique sur le thread principal (obligatoire pour cette API),
    jamais de scan global ni de chargement de chunk forcé au-delà de ce
    que l'accès normal aux blocs entraîne déjà.

## Configuration (`config.yml` → `admin.flatten`)

```yaml
admin:
  flatten:
    max-radius: 48
    default-shape: SQUARE          # SQUARE | CIRCLE
    top-layer-material: GRASS_BLOCK
    sub-layer-material: DIRT
    sub-layer-depth: 3
    clear-above-height: 10
    confirmation-timeout-seconds: 30
    blocks-per-tick: 4000
    forbidden-worlds: []
```

Validée au démarrage et à `/rpgquest reload` ; une section absente vaut les
valeurs par défaut ci-dessus (compatible avec un `config.yml` généré avant
cette étape).

## Tests

Automatisés (`FlattenServiceTest`, `ConfigValidatorTest`) : rayon
positif/hors limite, hauteur hors des limites du monde, estimation
carré/cercle, aperçu expiré, annulation (aperçu en attente et opération en
cours), traitement par lots sur plusieurs ticks, annulation (`undo`)
disponible/indisponible/refusée pendant une opération en cours,
configuration invalide (rayon, forme, matériau non-bloc, valeurs
négatives).

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : petite zone,
grande zone, terrain vallonné, arbres, eau, cavités, `cancel` puis `undo`
en jeu, reconnexion pendant une opération en cours, absence de gel
perceptible du serveur sur une grande zone.
