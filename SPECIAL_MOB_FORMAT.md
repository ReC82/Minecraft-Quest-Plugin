# Format d'une variante de mob spécial

Un fichier par **variante**, dans `plugins/RPGQuest/mobs/*.yml` (quatre
exemples générés automatiquement : `red_creeper`, `golden_creeper`,
`creeper_pig`, `splitting_zombie`). Une variante habille une entité vanilla
existante (type d'entité, nom, attributs, particule/son, capacités, table de
drops) et remplace le mob vanilla lors d'un spawn naturel qui la tire au sort
(voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), section `mob`, pour le
détail complet).

```yaml
id: rpgquest:golden_creeper
entity-type: CREEPER          # tout EntityType vivant (EntityType#isAlive())
name: "<gold><bold>Creeper Doré</bold></gold>"   # MiniMessage

# Chance qu'un spawn naturel de ce type d'entité devienne cette variante.
spawn-chance: 0.005

# Listes vides = aucune restriction. Non résolues au chargement (simples
# chaînes comparées par nom au moment du spawn) : jamais de couplage à
# ZoneRegistry/Biome au chargement du fichier.
worlds: []
biomes: []
zones: []

# Tous optionnels : appliqués via Attribute (MAX_HEALTH/ATTACK_DAMAGE/
# MOVEMENT_SPEED/ARMOR), jamais getMaxHealth()/setMaxHealth() (dépréciés).
health: 40
damage: 6
speed: 1.0
armor: 2

particle: TOTEM_OF_UNDYING
sound: ENTITY_PLAYER_LEVELUP

abilities:
  - type: STRONGER_EXPLOSION
    radius-multiplier: 1.5

# Table de drops pondérée (même format que RESOURCE_NODE_FORMAT.md) : un
# seul tirage à la mort, remplace totalement les drops vanilla si présente.
drops:
  - material: GOLDEN_APPLE
    weight: 40
    min-amount: 1
    max-amount: 1

xp-reward: 50          # mappé sur EntityDeathEvent#setDroppedExp (pas de
                        # système d'XP RPG dédié pour l'instant)
max-population: 2      # limite le nombre d'individus vivants simultanément
```

## Capacités (`abilities`)

-   `STRONGER_EXPLOSION` (`radius-multiplier` > 0) — multiplie le rayon d'une
    explosion vanilla qui prime (`ExplosionPrimeEvent`).
-   `EXPLOSIVE_ON_ATTACK` (`power` > 0, `set-fire`, `trigger-range-blocks` >
    0) — rend agressive une entité normalement passive : un balayage
    périodique (1 s) détecte un joueur à portée et déclenche une explosion
    réelle (`World#createExplosion`, respecte les zones/claims comme toute
    explosion), puis l'entité meurt.
-   `SPLIT_ON_HIT` (`max-depth` ≥ 1, `max-children-per-hit` ≥ 1) — fait
    apparaître des enfants à chaque coup non mortel. La profondeur de
    génération est suivie en PDC (jamais dans le nom affiché) ; combinée à
    `max-children-per-hit` et à `max-population`, elle borne strictement
    toute chaîne de division.

## Commandes (`rpgquest.admin.world`)

-   `/rpgadmin mob spawn <id>` — invoque la variante à la position du joueur
    (contourne mondes/biomes/zones/population : outil de test, pas le chemin
    de spawn naturel).
-   `/rpgadmin mob list` — liste les variantes chargées avec leur population
    courante.
-   `/rpgadmin mob inspect <id>` — détail complet d'une variante.
-   `/rpgadmin mob reload` — recharge depuis le disque.
-   `/rpgadmin mob metrics` — compteurs de spawns et de déclenchements de
    capacités depuis le démarrage.

## Comportement en jeu

-   Identification **uniquement** par PersistentDataContainer, jamais par le
    nom affiché (qui peut être renommé) : une entité renommée reste
    reconnue.
-   Un spawn naturel déjà annulé (safe zone, claim) n'est jamais upgradé — le
    listener de mobs spéciaux s'exécute après les listeners de protection.
-   Une variante ne despawn jamais naturellement par éloignement
    (`setRemoveWhenFarAway(false)`) : la population ne diminue qu'à la mort,
    jamais silencieusement, jamais au déchargement d'un chunk.
-   Après un redémarrage ou un rechargement de chunk, les variantes déjà
    taguées sont redécouvertes (population reconstituée) sans être
    recomptées deux fois.

## Validation

-   `id`, `entity-type` (vivant), `name`, `spawn-chance` (0–1) sont
    obligatoires.
-   `health`/`speed` strictement positifs si présents ; `damage`/`armor`
    positifs ou nuls si présents.
-   Chaque capacité : champs requis selon son `type`, tous strictement
    positifs.
-   Drops : même validation que `RESOURCE_NODE_FORMAT.md`.
-   Un fichier invalide est rejeté seul ; un `id` de variante dupliqué entre
    fichiers rejette les deux fichiers concernés.
