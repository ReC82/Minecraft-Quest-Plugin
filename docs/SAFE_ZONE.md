# Zones protégées (village central / safe zone)

Zones cuboïdes protégées, définies en YAML dans
`plugins/RPGQuest/zones/` (un exemple généré automatiquement :
`central_village`). Créées/supprimées en jeu via `/rpgadmin zone`, jamais
en éditant les fichiers à la main (les coordonnées viennent de l'outil de
sélection).

## Commandes (`rpgquest.admin.zone`, ou `rpgquest.admin.world`)

-   `/rpgadmin zone wand` — donne l'outil de sélection (tige de blaze
    marquée par PersistentDataContainer, jamais reconnue par son nom ni son
    matériau — délibérément pas une hache en bois, l'item de wand par
    défaut de WorldEdit). Clic gauche sur un bloc = position 1, clic droit
    = position 2.
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
| `explosions` | `false` | Aucune destruction de bloc **ni dégât aux joueurs** par une explosion (creeper, TNT, lit hors Overworld, cristal d'End...) |
| `fire` | `false` | Propagation/allumage du feu bloqués |
| `lava` | `false` | Pose de lave au seau bloquée |
| `pistons-across-border` | `false` | Un piston ne peut pas pousser/tirer un bloc à travers la frontière de la zone |
| `hostile-spawn` | `false` | Aucun monstre hostile ne spawn naturellement dans la zone |
| `hostile-damage` | `false` | Un mob hostile (mêlée ou projectile tiré par un mob hostile) ne peut infliger aucun dégât à un joueur dans la zone |
| `environmental-damage` | `false` | Chute, noyade, suffocation, lave, feu, faim, gel, contact (cactus...), vide, foudre, bloc qui tombe, camp de feu, écrasement, collision en vol bloqués — voir la liste exacte (`ZoneProtectionListener#ENVIRONMENTAL_CAUSES`) ; délibérément exclus : tout ce qui est déjà couvert par `pvp`/`hostile-damage`/`explosions`, et les causes trop exotiques pour une place de village (souffle du dragon, cri du warden, bordure du monde) |
| `npc-damage` | `false` | Un PNJ Citizens présent dans la zone ne peut subir aucun dégât |
| `doors` | `true` | Portes/trappes/portails en bois bloqués |
| `buttons` | `true` | Boutons bloqués |
| `levers` | `true` | Leviers bloqués |
| `npc-interact` | `true` | Interaction avec une entité nommée (dialogue) bloquée |
| `public-containers` | `false` | Ouverture de coffres/tonneaux/fourneaux... bloquée (désactivé par défaut : risque de vol dans une zone partagée) |
| `force-day` | `false` | Temps client figé à midi pour tout joueur présent dans la zone — voir « Cycle jour/nuit » ci-dessous. Cosmétique, pas une protection : ne fait pas partie des groupes « bloqué »/« autorisé » par défaut. |

Les onze premiers flags (jusqu'à `npc-damage` inclus) sont donc **bloqués
par défaut** (une zone neuve protège immédiatement), les cinq suivants
(`doors` à `public-containers`) sont **autorisés par défaut** (un village
doit rester utilisable — portes, boutons, PNJ) à l'exception des
conteneurs publics, décision documentée ci-dessus. `force-day` est un
réglage à part, cosmétique, `false` par défaut sur une zone neuve —
activé explicitement sur l'exemple embarqué `central_village`.

Avec les réglages par défaut d'une zone neuve (`pvp`, `explosions`,
`hostile-spawn`, `hostile-damage` et `environmental-damage` tous
bloqués), un joueur normal ne peut plus mourir dans la zone par aucun
moyen courant — seule une action volontaire hors zone, ou une commande
admin explicite, peut encore le tuer.

### Cycle jour/nuit dans la zone

Minecraft/Paper n'offre aucun mécanisme d'heure différente pour une seule
région d'un même monde : l'horloge d'un monde (`World#setTime`) est une
valeur globale par monde, affectant tous les joueurs de ce monde où qu'ils
soient — la figer casserait le cycle jour/nuit partout (pousse des
cultures, spawn de monstres ailleurs...) uniquement pour un confort
visuel local au village, ce qui est exclu.

Solution retenue : `Player#setPlayerTime` (API Bukkit stable, pas
expérimentale) fige le temps **côté client, par joueur**, uniquement
pendant que ce joueur est physiquement dans une zone `force-day: true` —
midi fixe, rétabli (`resetPlayerTime`) dès la sortie de zone. Purement
cosmétique : n'affecte ni l'horloge réelle du monde, ni le spawn de
monstres ailleurs (déjà gouverné indépendamment par `hostile-spawn`), ni
la pousse des cultures. Même mécanisme de suivi de zone que l'affichage
d'entrée/sortie (`PlayerMoveEvent` filtré + `PlayerJoinEvent` pour l'état
initial) : même limite résiduelle assumée (un joueur téléporté directement
dans la zone sans bouger ensuite peut voir l'heure se corriger avec un
tick de retard).

## Bypass administrateur / builder

Depuis l'issue #27, deux portes distinctes (voir [PERMISSIONS.md](PERMISSIONS.md)),
vérifiées sur l'acteur direct (jamais la victime) :

-   **casse / pose / interaction** — `rpgquest.admin.world`,
    `rpgquest.build.zone` (build/interaction dans **toute** zone protégée),
    ou le droit de construire dans le **monde** où se trouve la zone
    (`rpgquest.build.hub.<id>` pour une zone dans un Hub, `rpgquest.build.wild`
    pour une zone du Wild, etc.) — un `builder-hub-0` édite ainsi la zone du
    village de son Hub sans `op` ;
-   **dégâts PvP / PNJ** — `rpgquest.admin.world` seul : une permission de
    build n'exempte jamais des règles de combat de la zone.

Exception délibérée :
les dégâts environnementaux (`environmental-damage`, `hostile-damage`)
n'ont pas d'« acteur » distinct de la victime — ils s'appliquent donc à
**tout le monde**, administrateurs compris (rien ne justifierait qu'un
admin prenne des dégâts de chute dans le village alors que personne
d'autre n'en prend).

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
persistance réelle sur disque (relue par un second registre, y compris
les flags `hostile-damage`/`environmental-damage`/`npc-damage`/`force-day`),
suppression, protection bloc casse/pose/PvP à l'intérieur/extérieur/
frontière, bypass administrateur, événement déjà annulé laissé intact,
aucune exception sur un monde sans zone, exemple embarqué
`central_village` vérifié avec toutes les protections actives et
`force-day` activé. Ajoutés pour le village central : dégâts par mob
hostile (mêlée et flèche tirée par un mob hostile) bloqués/autorisés
selon la zone, dégâts environnementaux (chute, noyade, lave, faim...)
bloqués/autorisés selon la zone et **non exemptés pour un admin**, dégâts
d'explosion à un joueur (pas seulement destruction de bloc) bloqués dans
la zone, spawn naturel de mob hostile bloqué/autorisé selon la zone
(un spawn non-naturel, ex. `/rpgadmin mob spawn`, n'est jamais concerné),
temps client figé/rétabli (`Player#setPlayerTime`/`resetPlayerTime`) en
entrant/sortant d'une zone `force-day`, y compris à la connexion si le
joueur apparaît déjà dans la zone.

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : PvP réel,
projectiles, creeper, TNT, lit (hors Overworld), cristal d'End, feu, lave,
piston traversant une frontière, redstone, casse/pose exactement sur la
frontière, mort/reconnexion dans une zone, spawn hostile naturel observé
en jeu, **protection d'un PNJ Citizens contre les dégâts** (untestable en
JUnit dans ce dépôt : Citizens n'est jamais chargé par MockBukkit —
`compileOnly`, aucune dépendance de test — donc `NpcIdentityService#isCitizensNpc`
reste toujours `false` en test ; seul le chemin « entité non-PNJ, jamais
bloquée » est couvert automatiquement), rendu visuel du ciel figé à midi
en entrant dans une zone `force-day` et retour à l'heure réelle en
sortant.
