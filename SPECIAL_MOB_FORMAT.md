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

# Optionnelle, défaut SPECIAL (rétro-compatible) — issue #169 lot 1.
# SPECIAL : éligible au tirage aléatoire Wild. BOSS : jamais tiré au hasard
# (nom + particules colorées en continu + barre de vie), n'apparaît que par
# spawn de test admin ou futur objectif de quête (non implémenté).
category: SPECIAL

# Optionnelle, défaut true. false = ignoré par le tirage aléatoire ET par le
# spawn de test, jamais supprimé du disque.
enabled: true

entity-type: CREEPER          # tout EntityType vivant (EntityType#isAlive())
name: "<gold><bold>Creeper Doré</bold></gold>"   # MiniMessage

# Throttle individuel : n'est examiné qu'après le throttle global de
# mobs/spawn-settings.yml (voir docs/RPGQUEST_BIBLE.md section 11) — jamais
# le seul facteur de la chance réelle d'apparition.
spawn-chance: 0.005

# Listes vides = aucune restriction. Non résolues au chargement (simples
# chaînes comparées par nom au moment du spawn) : jamais de couplage à
# ZoneRegistry/Biome au chargement du fichier.
worlds: []
biomes: []
zones: []

# Tous optionnels : appliqués via Attribute (MAX_HEALTH/ATTACK_DAMAGE/
# MOVEMENT_SPEED/ARMOR/KNOCKBACK_RESISTANCE/SCALE), jamais
# getMaxHealth()/setMaxHealth() (dépréciés).
health: 40
damage: 6
speed: 1.0
armor: 2
knockback-resistance: 0.2     # 0 à 1
scale: 1.0                    # taille relative, 1.0 = normale

# Uniquement si entity-type: CREEPER — refusé (erreur de chargement) pour
# tout autre type, jamais une option silencieusement ignorée.
# creeper-explosion-radius: 5

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
    explosion), puis l'entité meurt. **Poursuite (issue #190)** : tant qu'un
    joueur éligible est à moins de 16 blocs (hors de `trigger-range-blocks`),
    l'entité se met en chemin vers lui via
    `org.bukkit.entity.Mob#getPathfinder()` (API publique Paper, aucun NMS) —
    changer les statistiques d'une base passive ne lui donne aucune IA de
    poursuite par elle-même, ce chemin est recalculé à chaque balayage.
    N'affecte jamais les animaux ordinaires (uniquement les entités taguées
    avec cette capacité).
-   `SPLIT_ON_HIT` (`max-depth` ≥ 1, `max-children-per-hit` ≥ 1,
    `max-alive-per-parent` ≥ 1 optionnel défaut 2) — fait apparaître des
    enfants à chaque coup non mortel. La profondeur de génération est suivie
    en PDC (jamais dans le nom affiché) ; `max-children-per-hit` borne le
    nombre créé par déclenchement, `max-population` borne la population
    globale, et **`max-alive-per-parent` (issue #190) borne séparément le
    nombre d'enfants vivants d'un même parent** — sans cette troisième borne,
    des coups répétés sur la même entité avant sa mort relançaient une
    division complète à chaque coup, produisant bien plus que
    `max-children-per-hit` descendants directs pour un seul parent.
-   `ENRAGED` (`health-fraction` strictement entre 0 et 1, `speed-multiplier`
    > 0, `damage-multiplier` > 0) — sous `health-fraction` de vie max, signal
    visuel (particule + son) puis bascule en rage (vitesse/dégâts
    multipliés). Binaire et définitif : marqué en PDC, jamais réévalué ni
    réappliqué/cumulé pour une même entité (issue #171).
-   `SUMMON_ON_DAMAGE` (`summon-entity-type` vivant, `amount` > 0, `chance`
    0 exclu à 1, `cooldown-seconds` ≥ 0, `max-alive` > 0) — sur dégâts
    *effectifs* (événement non annulé, dégâts finaux > 0), tire `chance`
    d'invoquer `amount` renforts, sous réserve du cooldown et du plafond de
    renforts vivants (compté via une PDC dédiée, pas de registre séparé).
    Aucune cascade possible : les renforts invoqués sont de simples entités
    vanilla, jamais upgradées, donc jamais elles-mêmes capables de
    déclencher cette capacité (issue #171).

## Tirage aléatoire Wild : throttle global (`mobs/spawn-settings.yml`)

Fichier séparé, édité depuis le Control Panel (jamais à la main) — voir
docs/RPGQUEST_BIBLE.md section 11 pour la sémantique à deux étages complète
(throttle global → tirage par profil → tirage pondéré explicite entre les
profils simultanément gagnants). Absent = valeurs par défaut rétro-
compatibles (`enabled: true`, `chance: 1.0`, aucun plafond).

```yaml
enabled: true
chance: 0.05                     # 0 à 1
max-simultaneous-special: 20     # optionnel, toutes définitions SPECIAL confondues
```

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
    obligatoires. `category` optionnelle (défaut `SPECIAL`).
-   `health`/`speed` strictement positifs si présents ; `damage`/`armor`
    positifs ou nuls si présents ; `knockback-resistance` entre 0 et 1 ;
    `scale` strictement positif ; `creeper-explosion-radius` strictement
    positif et refusé si `entity-type` n'est pas `CREEPER`.
-   Chaque capacité : champs requis selon son `type` (voir ci-dessus pour
    `ENRAGED`/`SUMMON_ON_DAMAGE`).
-   Drops : même validation que `RESOURCE_NODE_FORMAT.md`.
-   Un fichier invalide est rejeté seul ; un `id` de variante dupliqué entre
    fichiers rejette les deux fichiers concernés.
