# Zones protégées (village central / safe zone)

Zones cuboïdes protégées, définies en YAML dans
`plugins/RPGQuest/zones/` (un exemple généré automatiquement :
`central_village`). Créées/supprimées en jeu via `/rpgadmin zone`, jamais
en éditant les fichiers à la main (les coordonnées viennent de l'outil de
sélection).

## Commandes (toutes `rpgquest.admin.world`)

-   `/rpgadmin zone wand` — donne l'outil de sélection (hache en bois
    marquée par PersistentDataContainer, jamais reconnue par son nom).
    Clic gauche sur un bloc = position 1, clic droit = position 2.
-   `/rpgadmin zone create <id>` — crée une zone cuboïde à partir de la
    sélection courante (les deux positions doivent être dans le même
    monde). Rejetée si l'id existe déjà ou si la zone chevauche une zone
    existante dans ce monde.
-   `/rpgadmin zone delete <id>` — supprime une zone (fichier + rechargement).
-   `/rpgadmin zone list` — liste les zones chargées.
-   `/rpgadmin zone info <id>` — bornes et permissions détaillées d'une zone.

## Permissions d'une zone (`flags:`)

Une zone toute neuve (créée via `/rpgadmin zone create`) a les valeurs par
défaut ci-dessous — modifiables ensuite en éditant directement son fichier
YAML puis `/rpgquest reload`-équivalent (`/rpgadmin zone` ne recharge pas
automatiquement les fichiers modifiés à la main pour l'instant ; supprimer
et recréer la zone, ou redémarrer, applique un changement).

| Flag | Défaut | Effet si `false` |
|---|---|---|
| `pvp` | `false` | Aucun dégât joueur-contre-joueur (mêlée et projectiles) |
| `block-break` | `false` | Casse de bloc bloquée |
| `block-place` | `false` | Pose de bloc bloquée |
| `explosions` | `false` | Aucune destruction de bloc par explosion (creeper, TNT, lit hors Overworld, cristal d'End...) |
| `fire` | `false` | Propagation/allumage du feu bloqués |
| `lava` | `false` | Pose de lave au seau bloquée |
| `pistons-across-border` | `false` | Un piston ne peut pas pousser/tirer un bloc à travers la frontière de la zone |
| `hostile-spawn` | `false` | Aucun monstre hostile ne spawn naturellement dans la zone |
| `doors` | `true` | Portes/trappes/portails en bois bloqués |
| `buttons` | `true` | Boutons bloqués |
| `levers` | `true` | Leviers bloqués |
| `npc-interact` | `true` | Interaction avec une entité nommée (dialogue) bloquée |
| `public-containers` | `false` | Ouverture de coffres/tonneaux/fourneaux... bloquée (désactivé par défaut : risque de vol dans une zone partagée) |

Les six premiers flags sont donc **bloqués par défaut** (une zone neuve
protège immédiatement), les cinq suivants sont **autorisés par défaut**
(un village doit rester utilisable — portes, boutons, PNJ) à l'exception
des conteneurs publics, décision documentée ci-dessus.

## Bypass administrateur

`rpgquest.admin.world` (même permission que `/rpgadmin flatten`) exempte
l'acteur direct d'une action (joueur qui casse/pose/interagit, ou
attaquant en cas de PvP) de la protection — jamais la victime : un
administrateur peut construire librement dans la zone, mais n'exempte
personne d'autre de sa protection.

## Performance

Aucun balayage coûteux : les zones sont indexées par monde une seule fois
par rechargement (`ZoneRegistry#zonesInWorld`), donc un événement dans un
monde sans zone ne coûte qu'un accès de map vide. L'affichage d'entrée/
sortie de zone (actionbar) ne réagit qu'aux changements réels de position
de bloc (`PlayerMoveEvent` filtré), jamais à chaque micro-mouvement, et
aucune tâche répétitive ne vérifie la position d'un joueur en continu.

## Tests

Automatisés (`ZoneDefinitionTest`, `ZoneDefinitionParserTest`,
`ZoneLoaderTest`, `ZoneRegistryTest`, `ZoneProtectionListenerTest`) :
intérieur/extérieur/frontière (bornes inclusives), zone invalide (bornes
inversées, id mal formé), chevauchement entre fichiers et entre créations
successives (même monde uniquement), pas de chevauchement dans un monde
différent, id dupliqué, rechargement (fichier ajouté à la main détecté),
persistance réelle sur disque (relue par un second registre), suppression,
protection bloc casse/pose/PvP à l'intérieur/extérieur/frontière,
bypass administrateur, événement déjà annulé laissé intact, aucune
exception sur un monde sans zone.

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : PvP réel,
projectiles, creeper, TNT, lit (hors Overworld), cristal d'End, feu, lave,
piston traversant une frontière, redstone, casse/pose exactement sur la
frontière, mort/reconnexion dans une zone, spawn hostile naturel observé
en jeu.
