# Format d'une recette

Un fichier par recette, dans `plugins/RPGQuest/recipes/*.yml` (trois
exemples générés automatiquement au premier démarrage : `forest_blade_recipe`,
`refined_crystal_recipe`, `miner_pickaxe_recipe`). Voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (section `crafting`) pour le
détail de la validation et des garanties anti-triche. Chargée au démarrage
et enregistrée comme une vraie recette Bukkit (visible dans le livre de
recettes vanilla).

Deux types : `SHAPED` (motif dans une grille jusqu'à 3x3) et `SHAPELESS`
(liste d'ingrédients, jusqu'à 9 exemplaires au total).

## Exemple façonné (`SHAPED`)

```yaml
id: rpgquest:forest_blade_recipe
type: SHAPED

result:
  custom-item: rpgquest:forest_blade   # ou "material: <MATERIAL_VANILLA>"
  amount: 1

pattern:
  - " F "
  - " F "
  - " S "

key:
  F:
    custom-item: rpgquest:spider_fang  # objet personnalisé (vérifié via PersistentDataContainer)
  S:
    material: STICK                    # matériau vanilla brut
```

## Exemple sans forme (`SHAPELESS`)

```yaml
id: rpgquest:refined_crystal_recipe
type: SHAPELESS

result:
  custom-item: rpgquest:refined_crystal
  amount: 1

ingredients:
  - material: QUARTZ
    amount: 4
```

## Ingrédients personnalisés — sécurité

Chaque ingrédient `custom-item:` est représenté par un
`RecipeChoice.ExactChoice` construit à partir de l'objet canonique du
registre (donc avec son PersistentDataContainer) : un objet vanilla qui
imite juste le nom/lore/matériau ne satisfait jamais la recette. Une
seconde vérification (`RecipeCraftGuardListener`, sur
`PrepareItemCraftEvent`) revalide l'ensemble de la grille à chaque
préparation — clic simple, shift-clic ou recette automatique du livre de
recettes déclenchent tous le même événement, donc la même vérification.

## Validation

-   `id`, `type`, `result` sont obligatoires.
-   `result`/chaque ingrédient : exactement un de `custom-item` ou
    `material`.
-   `SHAPED` : `pattern` (1 à 3 lignes de 1 à 3 caractères, toutes de même
    longueur) et `key` (une entrée par caractère non-espace du motif) sont
    obligatoires.
-   `SHAPELESS` : `ingredients` (≥ 1 entrée) est obligatoire ; le total des
    quantités ne peut pas dépasser 9 (taille de la grille 3x3).
-   Un id d'objet personnalisé référencé (ingrédient ou résultat) qui ne
    correspond à aucune définition chargée est rejeté avec un message
    explicite — la recette n'est alors pas enregistrée, sans bloquer les
    autres.
-   Un `id` de recette dupliqué entre fichiers rejette les deux fichiers
    concernés.
