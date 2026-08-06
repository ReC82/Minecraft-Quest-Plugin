# Format d'un type de nœud de ressource

Un fichier par **type** de nœud, dans `plugins/RPGQuest/resource-nodes/*.yml`
(un exemple généré automatiquement : `crystal_ore`). Un type décrit une
recette de récolte réutilisable ; les **positions** concrètes se créent en
jeu via `/resourcenode create <typeId>` (voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), section `resource`, pour le
détail complet).

```yaml
id: rpgquest:crystal_ore

# Blocs vanilla (aucun bloc client personnalisé requis) : le bloc affiché
# quand le nœud est récoltable, et le bloc temporaire affiché une fois
# épuisé, jusqu'au respawn.
active-material: EMERALD_ORE
depleted-material: STONE

# Liste vide = n'importe quel outil (ou la main nue) permet de récolter.
required-tools:
  - IRON_PICKAXE
  - DIAMOND_PICKAXE
  - NETHERITE_PICKAXE

respawn-seconds: 300

# Table de drops pondérée : un seul tirage par récolte.
drops:
  - custom-item: rpgquest:refined_crystal
    weight: 30
    min-amount: 1
    max-amount: 1
  - material: QUARTZ
    weight: 70
    min-amount: 1
    max-amount: 3
```

## Commandes (toutes `rpgquest.admin`, agissent sur le bloc visé, portée 6 blocs)

-   `/resourcenode create <typeId>` — place un nœud.
-   `/resourcenode remove` — retire le suivi (ne touche pas au bloc
    physique).
-   `/resourcenode inspect` — type, état (actif/épuisé), temps de respawn
    restant.

## Comportement en jeu

-   Récolter avec le bon outil dépose le butin tiré au sort et pose le bloc
    épuisé ; la position redevient récoltable une fois `respawn-seconds`
    écoulées **et** le chunk naturellement chargé (aucun chargement forcé).
-   Un nœud dont le monde a été supprimé/renommé reste suivi mais son
    respawn est différé indéfiniment (jamais d'exception).
-   Positions persistées par monde (SQLite asynchrone), survivent à un
    redémarrage — un nœud encore en cooldown à l'arrêt le reste après
    redémarrage jusqu'à échéance réelle.

## Validation

-   `id`, `active-material`, `depleted-material` (doivent être des blocs et
    différents l'un de l'autre), `respawn-seconds` (> 0), `drops` (≥ 1
    entrée) sont obligatoires.
-   Chaque drop : exactement un de `custom-item` ou `material`, `weight` et
    `min-amount`/`max-amount` strictement positifs, `max-amount ≥
    min-amount`.
-   Un fichier invalide est rejeté seul ; un `id` de type dupliqué entre
    fichiers rejette les deux fichiers concernés.
