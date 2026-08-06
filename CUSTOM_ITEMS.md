# Format d'un objet personnalisé

Un fichier par objet, dans `plugins/RPGQuest/items/*.yml`. Voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (section `item`) pour le détail
de la validation. Exemple complet (arme) :

```yaml
id: rpgquest:forest_blade
type: WEAPON                       # WEAPON | TOOL | RESOURCE | QUEST_ITEM
material: DIAMOND_SWORD            # matériau vanilla de base
name: "<green>Lame de la forêt</green>"
lore:
  - "<gray>Forgée par les esprits de la forêt.</gray>"
rarity: RARE                       # COMMON | UNCOMMON | RARE | EPIC | LEGENDARY
stackable: false
max-durability: 1800

# Résolu depuis le resource pack optionnel s'il est installé (voir
# RESOURCE_PACK.md) ; apparence du matériau vanilla sinon.
item-model: rpgquest:forest_blade
# custom-model-data: 2001          # mécanisme alternatif/complémentaire (déprécié mais fonctionnel)

attributes:
  - attribute: attack_damage
    amount: 4.0
    operation: ADD_NUMBER           # ADD_NUMBER | ADD_SCALAR | MULTIPLY_SCALAR_1
    slot: mainhand

enchantments:
  - type: SHARPNESS
    level: 3                        # niveaux au-delà du maximum vanilla autorisés

gameplay-tags:
  - "starter_weapon"

crafting:
  craftable: false                  # donnée uniquement ; voir RECIPE_FORMAT.md pour de vraies recettes
  required-permissions: []
```

## Identification

Un objet créé porte son id namespacé dans son PersistentDataContainer — la
**seule** source de vérité pour l'identifier. Un objet vanilla renommé pour
en imiter un n'est jamais reconnu.

## Comportement de combat (`combat:`, type `WEAPON` typiquement)

```yaml
combat:
  base-damage: 1.5                  # bonus additif, jamais un remplacement
  attack-speed-bonus: 0.2
  critical-chance: 0.2              # [0,1]
  critical-multiplier: 1.5
  hit-message: "<red>Coup critique !</red>"
  particle: CRIT
  particle-count: 10
  effect:                           # optionnel : effet à cooldown sur coup notable
    ability-id: "leaf_trail_slow"
    type: SLOWNESS
    duration-ticks: 60
    amplifier: 1
    chance: 0.25
    cooldown-seconds: 8
```

## Comportement d'outil (`tool:`, type `TOOL` typiquement)

```yaml
tool:
  mining-speed-bonus: 2.0
  allowed-blocks: [IRON_ORE, DEEPSLATE_IRON_ORE]   # vide = tous les blocs
  durability-cost: 1
  harvest-bonus-chance: 0.15
  harvest-bonus-amount: 1
  special-ability:                  # optionnel : capacité à cooldown (clic droit)
    ability-id: "miner_rush"
    cooldown-seconds: 30
    activation-message: "<aqua>Vous sentez une ruée de minage !</aqua>"
```

## Validation

-   `id`, `type`, `material`, `name` sont obligatoires.
-   Taille de pile hors `[1, 99]`, durabilité sur un matériau qui n'en a pas
    en vanilla, ou objet à la fois empilable **et** doté d'une durabilité
    sont rejetés.
-   Un fichier invalide est rejeté seul ; les autres continuent de charger.
    Un `id` dupliqué entre fichiers rejette les deux fichiers concernés.

## Commandes

-   `/customitem give <joueur> <id> [quantité]` (`rpgquest.admin`)
-   `/customitem list` (`rpgquest.admin`)
-   `/customitem inspect` (`rpgquest.item`) — identifie l'objet en main
