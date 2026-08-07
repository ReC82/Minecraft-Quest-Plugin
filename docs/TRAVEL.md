# Portails et téléportation

Portails configurables entre le village et les zones d'aventure —
canalisation à délai, sécurité de destination, cooldown persisté, coût
optionnel. Voir [docs/ARCHITECTURE.md](ARCHITECTURE.md) (section `travel`)
pour le détail d'implémentation.

## Concepts

-   **Destination** — une position nommée (monde, x, y, z, yaw, pitch),
    réutilisable par plusieurs portails (ex. plusieurs portails « retour au
    village » dans des zones d'aventure différentes, tous reliés à la même
    destination `village`).
-   **Portail** — une zone d'activation cuboïde (comme une zone protégée,
    mais un concept distinct) reliée à une destination, avec un délai de
    canalisation, un cooldown et des conditions d'accès optionnelles
    (permission, quête, niveau, coût).

Un portail **sans destination configurée** ne fait rien : entrer dans sa
zone d'activation affiche un message et ne déclenche aucune canalisation.

## Commandes (toutes `rpgquest.admin.world`)

-   `/rpgadmin zone wand` — même outil de sélection que les zones
    protégées, réutilisé pour délimiter la zone d'activation d'un portail.
-   `/rpgadmin portal create <id>` — crée un portail cuboïde à partir de la
    sélection courante (délai de canalisation 3 s, cooldown 5 s, aucune
    condition par défaut — modifiables ensuite en éditant directement le
    fichier YAML généré, même convention que les flags de zone). Rejeté si
    l'id existe déjà ou si la zone d'activation chevauche un portail
    existant dans ce monde.
-   `/rpgadmin portal delete <id>` — supprime un portail.
-   `/rpgadmin portal list` — liste les portails chargés.
-   `/rpgadmin portal info <id>` — bornes, destination et conditions
    détaillées d'un portail.
-   `/rpgadmin portal setdestination <id> <destinationId>` — crée (ou met à
    jour) la destination `<destinationId>` à la position **exacte** où se
    trouve l'administrateur au moment de l'appel, puis relie le portail à
    cette destination. Aucune commande dédiée pour créer une destination
    « à part » : c'est cette commande qui en tient lieu (capturer sa
    position en marchant dessus est plus fiable que de taper des
    coordonnées à la main).

Aucun exemple de portail/destination n'est généré automatiquement au
premier démarrage (contrairement à `central_village` pour les zones) : une
destination doit être une position réellement sûre sur le monde de
l'opérateur, et aucune coordonnée arbitraire ne peut être garantie sûre à
l'avance sur un monde généré procéduralement — bundler un exemple aurait
plus de chances de nuire (destination sous terre, dans le vide...) que
d'aider.

## Format YAML (édition manuelle avancée)

`plugins/RPGQuest/portals/<id>.yml` :

```yaml
id: forest_gate
world: world
min: {x: 10, y: 60, z: 10}
max: {x: 12, y: 63, z: 12}
destination: village
channel-seconds: 3
cooldown-seconds: 5
required-permission: rpgquest.portal.forest   # optionnel
required-quest: rpgquest:first_steps          # optionnel
required-quest-state: COMPLETED               # optionnel, COMPLETED par défaut si omis
required-level: 5                              # optionnel (niveau d'expérience vanilla)
cost: 10                                        # optionnel (pièces, voir docs/ECONOMY.md)
```

`plugins/RPGQuest/destinations/<id>.yml` :

```yaml
id: village
world: world
x: 0.5
y: 65.0
z: 0.5
yaw: 0.0
pitch: 0.0
```

Pas de rechargement à chaud d'un fichier édité à la main pour l'instant
(même limitation déjà documentée pour les zones protégées) : `create`/
`delete`/`setdestination` rechargent déjà automatiquement, ce qui couvre le
flux d'usage principal.

## Canalisation

Entrer dans la zone d'activation d'un portail configuré (dont toutes les
conditions sont remplies) démarre une canalisation de `channel-seconds`
secondes, avec une actionbar de progression. Elle est **annulée** dans
trois cas :

-   le joueur bouge au-delà d'une tolérance (~0,6 bloc) ;
-   le joueur subit des dégâts ;
-   le joueur se déconnecte.

Une entrée dans la zone qui échoue à une condition (permission, niveau,
quête, cooldown, fonds insuffisants) n'affiche qu'un message — aucune
canalisation ne démarre, et aucune n'est retentée tant que le joueur reste
dans la même zone sans en ressortir (évite le spam de messages à chaque
micro-mouvement).

## Sécurité de la destination

À la fin de la canalisation, avant toute téléportation :

1.  le monde de la destination doit exister (sinon message d'erreur,
    aucune téléportation, aucun débit) ;
2.  sa colonne de chunk est chargée **à la demande** (un accès ponctuel,
    jamais un chargement forcé permanent — aucun ticket de chunk posé) ;
3.  une position sûre est recherchée autour de la position enregistrée
    (balayage vertical alterné au-dessus/en-dessous, jusqu'à 5 blocs) :
    ni bloc solide aux pieds/à la tête, sol solide sous les pieds, aucun
    bloc dangereux (lave, feu, feu d'âme, magma, cactus). Aucune position
    sûre trouvée → message d'erreur, aucune téléportation, aucun débit.

**Un joueur n'est donc jamais téléporté dans le vide, la lave ou un bloc
solide** — soit la téléportation réussit vers une position vérifiée sûre,
soit elle échoue proprement sans aucun effet de bord.

## Coût et cooldown

-   Le coût (optionnel) n'est débité **qu'après** que la destination a été
    résolue et vérifiée sûre, juste avant la téléportation elle-même —
    conformément à la règle « aucun débit si la téléportation échoue ».
    Utilise `economy.EconomyService` (voir
    [docs/ECONOMY.md](ECONOMY.md)) : mêmes garanties d'atomicité qu'un
    achat chez un marchand.
-   Le cooldown (par joueur, par portail) est vérifié en mémoire (jamais de
    requête base depuis `PlayerMoveEvent`, bien trop fréquent) mais
    **persisté** en SQLite (`portal_cooldowns`, migration V6) : rechargé à
    la connexion, il survit donc à une reconnexion ou un redémarrage du
    serveur.

## Performance

-   Les portails sont indexés par monde (`YamlPortalRegistry#portalsInWorld`,
    une passe par rechargement), même patron que les zones protégées :
    aucun balayage de tous les portails de tous les mondes à chaque
    événement.
-   `PlayerMoveEvent` n'est traité qu'aux changements réels de position de
    bloc (jamais à chaque micro-mouvement/rotation de caméra), et
    seulement lors d'une vraie transition (entrée dans un nouveau portail,
    ou sortie) — jamais à chaque tick pour un joueur immobile dans une
    zone.
-   Aucun chargement forcé permanent de chunk (voir « Sécurité de la
    destination » ci-dessus).

## Tests

Automatisés : `DestinationTest`, `PortalDefinitionTest` (invariants),
`DestinationDefinitionParserTest`, `PortalDefinitionParserTest`,
`DestinationLoaderTest`, `PortalLoaderTest` (dont chevauchement de
portails), `YamlDestinationRegistryTest`, `YamlPortalRegistryTest`
(création/suppression/`setdestination`, persistance réelle sur disque),
`PortalServiceTest` : conditions non remplies (permission, niveau, quête),
cooldown, coût (fonds insuffisants et suffisants, débit uniquement au
succès), monde de destination absent, destination dangereuse (aucune
position sûre trouvée), annulation par mouvement, annulation par dégâts,
annulation par déconnexion, rechargement du registre.

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : portail vers
un chunk réellement déchargé, déconnexion en pleine canalisation,
reconnexion et persistance du cooldown, téléportation avec un inventaire
chargé et une quête active, test depuis/vers une safe zone.
