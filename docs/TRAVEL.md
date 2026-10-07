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

## Portails simples entre mondes (`/rpgadmin worldportal`)

Système distinct du portail « classique » ci-dessus (voir le tableau comparatif dans
[docs/RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md), section 6) : une zone d'activation cuboïde
(`travel.model.WorldPortalDefinition`) qui téléporte **immédiatement** (aucune canalisation, aucun
coût, aucune condition) au spawn — ou à une position aléatoire sûre autour de lui — du monde
destination, dès l'entrée dans la zone (`travel.WorldPortalTeleportListener`, sur `PlayerMoveEvent`).

### Commandes

Voir le tableau complet dans [docs/RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md#6-portails). En bref :
`create`/`info`/`list`/`enable`/`disable`/`delete` pour la gestion courante, plus deux outils de
**diagnostic** ajoutés lors de l'investigation du bug de téléportation automatique dans le Hub
(voir plus bas) :

-   **`/rpgadmin worldportal here`** — liste TOUS les portails simples dont la zone d'activation
    contient la position actuelle du joueur, pas seulement le premier trouvé. En jeu, seul
    `WorldPortalRegistry#portalAt` est consulté (le premier match, par ordre alphabétique de
    fichier) — deux zones superposées restent donc invisibles à `worldportal info`/`list` tant
    qu'on ne se tient pas physiquement dedans avec `here`.
-   **`/rpgadmin worldportal debug show <id>` / `hide <id>` / `showall` / `hideall`** — affiche le
    contour d'une zone (les 12 arêtes du cuboïde, coins au sol et limites verticales incluses) par
    particules colorées (couleur dérivée de l'id, pour distinguer deux zones superposées) plus une
    étiquette flottante temporaire (`TextDisplay`, jamais sauvegardée, retirée à `hide`/redémarrage
    du plugin) — **jamais de bloc modifié dans le monde**. Rendu global (visible par tous les
    joueurs à proximité, pas ciblé par joueur), rafraîchi toutes les 0,5 s tant que la zone reste
    affichée.

### Anomalie connue : `reload()` ne valide jamais les doublons/chevauchements entre fichiers

Contrairement à `zone.ZoneLoader`/`travel.PortalLoader` (portail classique), le chargement des
portails simples (`WorldPortalRegistry#reload()`, appelé par `start()` et après chaque
`create()`/`enable`/`disable`/`delete`) **ne fait aucune validation croisée entre fichiers** : ni id
dupliqué, ni chevauchement de zone. Seul `create()` (donc uniquement `/rpgadmin worldportal
create`) vérifie les chevauchements, et seulement **au moment de la création**.

Concrètement : un fichier ajouté ou édité à la main dans `plugins/RPGQuest/world-portals/`
(explicitement supporté — `reload()` relit tout le dossier sans réserve) peut faire coexister deux
zones actives superposées, ou un id dupliqué, **sans qu'aucune erreur ne soit jamais journalisée**.
`portalAt` (utilisé en jeu) ne renvoie toujours que la première trouvée par ordre alphabétique de
fichier — la seconde reste invisible sauf avec `/rpgadmin worldportal here` ou `debug showall`.
Preuve exécutable : `WorldPortalRegistryTest#reloadNeverRejectsOverlappingZonesIntroducedByManuallyPlacedFiles`
et `#reloadNeverRejectsDuplicateIdsIntroducedByManuallyPlacedFiles`.

**Non corrigé pour l'instant** (mission : outils de diagnostic d'abord, pas de correctif silencieux)
— si un futur correctif est décidé, faire `reload()` valider en croisé comme `PortalLoader`/`ZoneLoader`
est le candidat naturel.

### Répit d'arrivée et bug de téléportation automatique dans le Hub — investigation en cours

Un joueur signalé comme téléporté automatiquement hors de `world_hub` ~1-2 s après son arrivée
(connexion, `/tp` admin), y compris à des positions rapportées comme hors de toute zone
`worldportal` visible, et de façon reproductible pour un joueur donné mais pas un autre à la même
position. `WorldPortalTeleportListener` accorde un **répit d'arrivée global de 40 ticks (2 s)**
après `PlayerJoinEvent`/`PlayerTeleportEvent` avant qu'un portail simple ne puisse se déclencher
automatiquement — ajouté lors d'une session précédente pour éviter qu'un joueur tout juste arrivé
(par connexion, `/tp`, ou l'atterrissage d'un autre portail) se retrouve téléporté sans avertissement
si sa position d'arrivée se trouve être dans une zone.

**Limite identifiée de ce répit, non encore corrigée** : il ne fait que *retarder* le déclenchement
de 2 s si la zone couvre réellement le point d'arrivée — `onMove` ne met jamais à jour l'état
« portail courant » pendant le répit (retour anticipé avant toute lecture du registre), donc le
premier `PlayerMoveEvent` de règlement après l'expiration du répit voit un état « jamais enregistré »
et déclenche une téléportation **neuve**, immédiate. Ce délai de ~2 s correspond exactement au « 1-2
secondes » rapporté depuis VeryGames — hypothèse à confirmer/infirmer avec les outils ci-dessous
avant tout correctif définitif (ne pas supposer qu'un simple portail à déclenchement immédiat en est
la cause : voir aussi le système de canalisation `/rpgadmin portal`, dont le délai `channel-seconds`
correspond au moins aussi bien au symptôme « immobile puis téléporté après un délai »).

Aucune tâche Bukkit répétée ne vérifie périodiquement « le joueur est-il dans une zone » dans l'un
ou l'autre système de portail (vérifié par lecture complète du code) — un déclenchement ne peut
provenir que d'un véritable `PlayerMoveEvent` (portails simples/classiques) ou d'un tick de
canalisation déjà en cours (portail classique, `PortalService#tickChannel`, qui ne fait que
surveiller la tolérance de mouvement d'une canalisation déjà démarrée, jamais en démarrer une).

### Logs `TP-TRACE`

Instrumentation temporaire (préfixe `[TP-TRACE]`, niveau `INFO`), à retirer une fois la cause
confirmée — chaque appel est marqué `TODO(debug bug TP hub)` dans le code, format centralisé dans
`travel.TpTraceLogger` (seul endroit à modifier/retirer). Format unique, quelle que soit la classe
d'origine :

```
[TP-TRACE] uuid=<uuid> player=<pseudo> event=<événement> portal=<id|-> world=<monde> x=<x> y=<y> z=<z> inside=<true|false|-> previousInside=<true|false|-> grace=<valeur|-> channel=<valeur|-> from=<monde:x,y,z|-> destination=<monde:x,y,z|->
```

Événements journalisés (uniquement sur transition réelle, jamais à chaque `PlayerMoveEvent` — voir
plus bas) :

| Événement | Émis par | Sens |
|---|---|---|
| `player_join` | `WorldPortalTeleportListener` | Connexion — `inside` indique si la position d'arrivée est déjà dans une zone. |
| `world_change` | `WorldPortalTeleportListener` (`PlayerChangedWorldEvent`) | Changement de monde effectif (après tout téléport/portail vanilla). |
| `external_teleport` | `WorldPortalTeleportListener` (`PlayerTeleportEvent`, priorité `MONITOR`) | Toute téléportation, RPGQuest ou non — peut aussi apparaître pour le propre `teleport()` de RPGQuest si la ré-entrance n'est pas détectable (voir le Javadoc de la méthode) ; corréler par `uuid=` avec la ligne `teleport_start`/`teleport_success` adjacente en cas de doute. |
| `portal_enter` / `portal_exit` | Les deux classes de portail | Transition OUTSIDE→INSIDE / INSIDE→OUTSIDE d'une zone. |
| `channel_start` | `PortalService` (portail classique) | Début d'une canalisation (`channel=` porte la durée configurée). |
| `channel_cancel` | `PortalService` | Annulation d'une canalisation (`channel=cancel:<mouvement\|dégâts\|déconnexion>`). |
| `teleport_start` | Les deux classes | Juste avant l'appel `teleport()`/`teleportAsync()`. |
| `teleport_success` / `teleport_failed` | Les deux classes | Résultat réel de la téléportation (`Player#teleport` renvoie un booléen ; `teleportAsync` un `CompletableFuture<Boolean>`) — un `teleport_failed` révèle qu'un autre plugin a probablement annulé la téléportation. |

**Filtrage anti-spam** : `portal_enter`/`portal_exit`/`channel_start` ne sont journalisés que sur une
vraie transition (même filtre que la logique de jeu elle-même, voir « Performance » plus haut) —
jamais à chaque micro-mouvement d'un joueur immobile dans/hors d'une zone.

### Logs `TP-LATENCY` (diagnostic de latence, issue #161)

Mesure **permanente** (contrairement à `TP-TRACE`, temporaire), émise par
`WorldPortalTeleportListener` une fois par téléportation de portail simple, niveau `INFO` :

```
[TP-LATENCY] uuid=<uuid> player=<pseudo> portal=<id> world=<monde destination> strategy=<WORLD_SPAWN|RANDOM_SAFE> attempts=<n> search_ms=<ms> chunks_ms=<ms> teleport_ms=<ms> total_ms=<ms>
```

Les trois étapes du passage sont **distinguées**, ce qui permet d'attribuer une latence observée
sans supposition :

| Champ | Sens |
|---|---|
| `attempts` | Nombre de colonnes candidates réellement tirées par `RandomSafeLocationFinder` (0 pour `WORLD_SPAWN`). |
| `chunks_ms` | Temps cumulé passé dans `World#getChunkAt` — c'est-à-dire le **chargement ou la génération** de terrain à la demande. En pratique le poste dominant pour une arrivée `RANDOM_SAFE` dans un Wild peu exploré. |
| `search_ms` | Temps d'**évaluation** des colonnes (bordure du monde, hauteur du sol, sécurité pieds/tête), hors chargement de chunk. |
| `teleport_ms` | L'appel `Player#teleport` lui-même. |
| `total_ms` | Somme réelle mesurée de bout en bout (inclut le reste : résolution du monde, journalisation). |

Aucun contrôle de sécurité n'a été allégé pour réduire cette latence : la mesure sert à décider, sur
données réelles, s'il faut agir (et où) plutôt qu'à justifier un raccourci.

### Procédure de diagnostic sur VeryGames

1.  `/rpgadmin worldportal list` puis `/rpgadmin worldportal here` à l'endroit signalé (le joueur
    affecté doit s'y tenir, ou un administrateur téléporté à la même position) — confirme ou
    infirme la présence d'une zone (éventuellement superposée/invisible) à cet endroit précis.
2.  `/rpgadmin worldportal debug showall` pour visualiser toutes les zones chargées du monde ;
    comparer visuellement avec la position réelle du bug.
3.  Reproduire le bug, puis extraire toutes les lignes `[TP-TRACE]` du joueur concerné (filtrer par
    `uuid=`) sur les ~30 dernières secondes entourant l'incident.

## Voyage par objet (mécanique générique)

Mission « MVP du monde claims » — quitter `claims` sans commande joueur ni déconnexion. Moteur
générique (`travel.ItemTravelService`), **volontairement séparé** de `PortalService` ci-dessus (même
patron de canalisation — annulation sur mouvement/dégâts/déconnexion — mais déclencheur différent :
clic droit sur un objet personnalisé, pas une entrée en zone ; aucune notion de coût/cooldown/quête à
ce stade) : ajouter une future pierre/destination n'est qu'un nouvel enregistrement
(`travel.model.ItemTravelDefinition`), jamais un changement du moteur lui-même.

-   `ItemTravelDefinition(itemId, channelSeconds, cooldownSeconds, destinationSupplier, requiredWorldSupplier)`
    — la destination reste un simple fournisseur (`Supplier<Optional<Location>>`), résolu à chaque
    téléportation réussie plutôt qu'une position figée (ex. `spawn.SpawnService#resolve`, toujours
    l'état courant du spawn). `cooldownSeconds` (0 = aucun) applique, après un voyage réussi, un
    délai de rechargement **persisté par joueur** (`database.ItemTravelCooldownRepository`, table
    `item_travel_cooldowns`, migration V16 — même forme que `portal_cooldowns`), chargé en mémoire à
    la connexion et vérifié avant chaque canalisation. Des constructeurs de confort couvrent « sans
    restriction de monde, sans cooldown » et « restriction de monde, sans cooldown ».
-   `ItemTravelService#register` associe un objet personnalisé (id namespacé, identifié par PDC via
    `item.YamlCustomItemRegistry#identify` — jamais par matériau) à une définition. Clic droit avec un
    objet non enregistré : ignoré silencieusement (aucun effet).
-   **Ne consomme jamais l'objet** — propriété de l'appelant/de l'objet, pas du moteur.

**Premier objet : Pierre de retour** (`rpgquest:pierre_retour`, `item.model.CustomItemDefinition`
type `QUEST_ITEM`, non empilable) — `claims` → spawn RPGQuest du Hub (`SpawnService#resolve`, même
notion que `/rpgadmin spawn tp`), canalisation ~3 s (feedback action bar + particules), permanent
(jamais consommé). Remise par Jo (choix « Obtenir une Pierre de retour », conditions
`HAS_MAIN_CLAIM` + `LACKS_CUSTOM_ITEM` — jamais de farm tant qu'un exemplaire est en poche, redonnée
gratuitement si perdue) — voir [docs/CLAIMS.md](CLAIMS.md), section « Retour à son claim ».

**Restriction de monde** (mission « Pierre de retour limitée à `claims` ») — `ItemTravelDefinition`
porte désormais un `requiredWorld` optionnel (`Supplier<Optional<String>>`, `Optional::empty` par
défaut via le constructeur de confort à 3 arguments) : `Optional.empty()` signifie « aucune
restriction », sinon le clic droit est ignoré (message bref, aucune canalisation) hors de ce monde
précis. La Pierre de retour est enregistrée avec `requiredWorld = () -> Optional.of(claims.world())`
(fournisseur, jamais une valeur figée — cohérent avec un `config.yml` potentiellement rechargé à
chaud) dans `RPGQuestBootstrap` — **la restriction appartient à l'enregistrement de cette pierre,
jamais à `travel.ItemTravelService`**, qui reste entièrement générique : une future pierre sans
restriction n'a qu'à omettre ce paramètre.

**Système soulbound générique** (mission « système soulbound générique ») — `item.SoulboundItemService`
+ `item.SoulboundItemListener` remplacent l'ancienne `travel.ReturnStoneGuardListener` (dédiée à la
seule Pierre de retour). Un **unique** écouteur applique les deux règles anti-perte à *tous* les
objets permanents enregistrés (`item.RpgItemKeys` : Acte de propriété, Pierre de retour, Journal des
quêtes, Rune de rappel) : drop volontaire annulé (`PlayerDropItemEvent`), et à la mort chaque
exemplaire soulbound est retiré des drops puis rendu **tel quel** (même méta/PDC) à la réapparition
— jamais de duplication (seuls les exemplaires réellement retirés de *cette* mort sont restaurés).
Identification toujours par PDC (`YamlCustomItemRegistry#identify`), jamais par matériau. Une fois
ces deux chemins fermés, un objet soulbound ne devient plus jamais une entité posée dans le monde
(lave/feu/cactus/explosion sans objet). Les PNJ (Jo pour l'Acte et la Pierre, le Libraire pour le
Journal, le Guide pour la Rune) restent le filet de secours indépendant si le joueur n'a plus
l'objet du tout.

**Indicateur de canalisation** — `ItemTravelService#tick` rapporte désormais la progression *avant*
de déclencher la complétion (jamais après) : le dernier tick affiche proprement 100% au lieu de
s'arrêter à un pourcentage tronqué (ex. 98% pour une canalisation de 3 s/60 ticks, `59/60`). L'action
bar est ensuite explicitement vidée (`Component.empty()`) à la complétion **et** à toute annulation
— plus aucun résidu de pourcentage affiché après la fin d'un voyage.

## Rune de rappel (`rpgquest:rune_rappel`)

Moyen de secours du Wild vers le Hub, remis à chaque joueur dès sa première connexion
(`player.StarterKitListener` — marqueur persistant `RUNE_RAPPEL_GRANTED` dans `player_variables`,
jamais de duplication ; le Guide la redonne gratuitement, `dialogues/guide.yml`, garde
`LACKS_CUSTOM_ITEM`). Enregistrée dans `ItemTravelService` comme une définition normale :
`requiredWorld = () -> travel.wild-world` (refus bref hors du Wild), `channelSeconds =
travel.rune.channel-seconds` (défaut 10), `cooldownSeconds = travel.rune.cooldown-seconds` (défaut
1800 = 30 min, persisté), destination `SpawnService#resolve` (Hub). Jamais consommée. Soulbound.
Les valeurs de canalisation/cooldown sont lues **au démarrage** du plugin (comme le `3` littéral de
la Pierre de retour) — un changement de config nécessite un redémarrage pour ces deux champs.

### Secours Hub (issue #154)

Un joueur peut rester coincé dans le Hub (trou sans sortie — pose/casse interdites). Décision
produit explicite : réutiliser **cette même Rune** comme point d'accès au secours, jamais un second
objet. `ItemTravelDefinition#freeRescueWorld` (nouveau champ, optionnel, vide pour tout autre objet
dont la Pierre de retour) déclare un monde où l'objet téléporte **immédiatement et gratuitement**
vers `destination` — **avant** toute évaluation de `requiredWorld`/cooldown, donc entièrement
indépendant de la restriction au Wild ci-dessus. La Rune est enregistrée avec `freeRescueWorld =
hub.world` : dans le Hub, clic droit → retour instantané au spawn configuré, sans canalisation (donc
jamais annulé par un petit dégât/chute — non pertinent puisqu'il n'y a plus de canalisation à
annuler), sans cooldown, sans consommation (déjà le cas partout). Hors Hub, le comportement normal
(restriction Wild + canalisation + cooldown) est strictement inchangé.

**Disponibilité garantie** : `player.StarterKitListener` ne marque plus `RUNE_RAPPEL_GRANTED` que si
la Rune a réellement pu être ajoutée à l'inventaire — avant ce correctif, un inventaire plein à la
toute première connexion faisait perdre l'objet pour toujours (le marqueur était posé même en cas
d'échec de `addItem`). `hub.HubRescueFallbackService` (balayage périodique, 5 s, coût négligeable)
redonne silencieusement la Rune à tout joueur du Hub qui en serait dépourvu dès qu'une place se
libère ; si l'inventaire reste plein, il ouvre un **accès de secours purement graphique** (inventaire
vanilla 1 bouton « Retour au spawn du Hub », aucun mod/resource pack) — jamais une commande requise
pour le parcours normal.

## Avertissement avant entrée dans le Wild (issue #161)

`travel.WildEntryWarningService` implémente `travel.WorldPortalEntryGuard`, une politique
**optionnelle** consultée par `WorldPortalTeleportListener` juste avant chaque téléportation de
portail simple (installée via `setEntryGuard`, jamais codée dans le listener — le portail simple
reste générique). Elle ne concerne que les portails dont la destination est `travel.wild-world` ;
les autres passent sans contrôle.

> **Décision du 2026-10-07 (issue #161, remplace la conception initiale de #26 partie B)** :
> l'avertissement est **générique** et ne fait **aucune inspection d'inventaire**. Ni nourriture, ni
> arme, ni outil, ni Rune de rappel, ni kit de départ, ni « gear score », ni estimation de
> préparation — la version précédente, qui n'avertissait qu'un joueur « sans Rune de rappel », a été
> retirée. Le texte énonce le risque de la zone, il ne juge jamais le joueur.

### Parcours

1. L'entrée immédiate est toujours refusée (`allowEntry` renvoie `false`) : la préférence « ne plus
   afficher » vit en base, aucune décision ne peut être rendue de façon synchrone. Le service devient
   propriétaire de la suite.
2. **Avertissement** (fenêtre Paper, ou chat cliquable — voir ci-dessous) :
   « Le Wild est une zone dangereuse. Le PvP y est autorisé : d'autres joueurs peuvent vous attaquer.
   Vous pouvez mourir et perdre les objets de votre inventaire. Voulez-vous continuer ? », suivi de
   « Le Garde peut vous renseigner sur les conditions actuelles du Wild. » (seul lien avec #24 :
   l'état jour/nuit/météo n'est **jamais** injecté ici, le joueur le demande au Garde).
3. Trois actions explicites : **Entrer dans le Wild**, **Entrer et ne plus afficher cet
   avertissement**, **Annuler**. Fermer la fenêtre n'exécute aucune action : c'est donc exactement
   une annulation (aucun départ, aucune préférence mémorisée).
4. Après confirmation — ou directement si l'avertissement est masqué — retour immédiat
   « Recherche d'un point d'arrivée sûr… Téléportation en préparation. », puis téléportation au tick
   suivant via `PortalTeleporter#teleportNow`, qui annonce lui-même la réussite (« Téléportation
   réussie. ») ou l'échec (« La téléportation a échoué : vous n'avez pas quitté cet endroit. Vous
   pouvez réessayer. »).

`RANDOM_SAFE`, les contrôles de sécurité de `RandomSafeLocationFinder` et le répit d'arrivée de
40 ticks sont **inchangés**.

### Option « Ne plus afficher cet avertissement »

Persistée par joueur dans `player_variables`, clé `WILD_ENTRY_WARNING_HIDDEN` = `"true"` (réutilise
la table existante : aucune migration de schéma). Absence de ligne = avertissement affiché. Elle
n'est écrite **que** sur une confirmation réelle de départ — jamais sur une annulation ni sur une
fermeture de fenêtre. Elle masque l'avertissement seul : le retour de préparation, les messages de
réussite/échec et tous les contrôles de sécurité restent en place. `/rpgadmin player resetnew`
rétablit l'avertissement sans code dédié (il efface toutes les variables du joueur).

### Pas de boucle de menu

Aucun minuteur anti-spam. `WorldPortalTeleportListener` ne consulte les gardes que sur une
**transition réelle** extérieur → intérieur d'une zone de portail : un joueur qui annule et reste
dans le portail ne revoit donc jamais la fenêtre, même en bougeant dedans. Il faut ressortir et
rentrer (nouvelle intervention explicite). Un seul jeton par joueur (`inFlight`) garantit qu'un
double-clic, plusieurs pas dans la zone ou une relance ne déclenchent jamais deux lectures, deux
recherches de point sûr ou deux téléportations ; une déconnexion le relâche et annule le départ
différé — jamais de téléportation tardive.

### Présentation

`travel.WildEntryPromptPresenter` suit exactement la même conception que
`dialogue.render.DialogueRenderer` : `PaperDialogWildEntryPromptPresenter` (fenêtre Paper native,
`canCloseWithEscape(true)`, boutons `uses(1)`) décoré par `FallbackWildEntryPromptPresenter`, avec
`ChatWildEntryPromptPresenter` comme repli stable. Le choix suit la préférence serveur
`dialogue.renderer` de `config.yml` : un serveur qui a volontairement choisi `chat` pour éviter
l'API expérimentale ne la voit pas réapparaître ici.

## État du Wild demandé au Garde (issue #24)

`travel.WildConditionsService` lit — et **rien que** lit — l'état réel du monde
`travel.wild-world` : jour/nuit depuis l'horloge du monde (`World#getTime()`, bornes de nuit
12300–23850, volontairement indépendantes de la météo pour qu'un orage de midi reste « il fait
jour ») et météo **globale** depuis `World#isThundering()` / `World#hasStorm()`. Ni l'heure, ni la
météo, ni le cycle jour/nuit du Wild ne sont jamais modifiés. Le monde est résolu à chaque appel :
un Wild déchargé donne « Je n'ai pas de nouvelles du Wild pour le moment. », jamais une valeur
périmée ou inventée.

La météo est un état **du monde** dans Minecraft, jamais du biome : la réponse annonce donc l'état
global, même si certains biomes rendent la précipitation différemment. Aucune prédiction de la météo
du point d'arrivée n'est tentée.

Réponses possibles :

- « Il fait jour dans le Wild. Le temps est clair. »
- « Il fait nuit dans le Wild. Il pleut. »
- « Il fait nuit dans le Wild. Un orage est en cours. »

**Accès sans commande** : le dialogue du Garde (`dialogues/guard.yml`) porte un choix **permanent**
(aucune condition) « Comment est le Wild actuellement ? » menant à un nœud dont le texte vaut
`%wild_conditions%`. Ce marqueur est substitué juste avant le rendu par
`dialogue.DialogueTextPlaceholders` (même convention que `%player%` de `RUN_SAFE_COMMAND`, jamais une
balise MiniMessage) : la donnée YAML reste statique, la réponse lue par le joueur non. Une clé non
enregistrée est laissée visible telle quelle plutôt qu'effacée, et une valeur contenant elle-même un
`%` n'est jamais re-substituée (une seule passe).

**Extension future** (niveau de danger, événements actifs, saison) : ajouter un champ à
`travel.model.WildConditions` et une phrase à `description()`. Ni le moteur de dialogue, ni le
dialogue du Garde, ni le portail n'ont à changer.

## Waystones (`waystone.WaystoneService`)

Système générique de « Pierres de voyage » dans `travel.wild-world`. Voir la section dédiée ci-dessous.

### Génération paresseuse et déterministe

`ChunkLoadEvent` (monde configuré uniquement) → les cellules carrées (`travel.waystone.cell-size`,
défaut 1000) que le chunk touche sont évaluées **une seule fois**. `WaystoneCellPlanner` (pur, sans
Bukkit) décide, à partir de `world.getSeed()` + coordonnées de cellule : `chance` (défaut 0.6) de
contenir une Waystone, et une position de bloc déterministe dans la cellule (marge de bord 24). Deux
appels identiques renvoient toujours le même résultat → **aucun doublon au reload/redémarrage**,
garanti aussi par l'index unique `(world, cell_x, cell_z)` en base. Si le point candidat tombe dans
un autre chunk de la cellule, la pose est différée au chargement de ce chunk. Avant la pose :
distance minimale respectée (`travel.waystone.minimum-spacing`, défaut 300) et recherche d'une
surface sûre autour du point (`travel.waystone.safe-attempts`, défaut 16, via
`RandomSafeLocationFinder#findAtColumn` réutilisé). Persistance : table `waystones` (migration V17).

### Structure

`WaystoneStructurePlacer` (interface — permet un `.schem` plus tard). `SimpleWaystoneStructurePlacer` :
socle 3×3 en pierre polie sombre, quatre montants d'angle éclairés, `LODESTONE` au centre. **Aucun
gameplay ne dépend du matériau** : l'interaction est décidée par la position persistée du bloc
sommital.

### Découverte et utilisation

La Waystone est globale physiquement mais **découverte individuellement** : premier clic droit →
« Pierre de voyage découverte : <nom> », persisté dans `waystone_discoveries` (player UUID +
waystoneId + discoveredAt). Sur une Waystone déjà découverte, un choix compact cliquable
`[Retourner au Hub]` / `[Annuler]` ; « Retourner au Hub » canalise `travel.waystone.channel-seconds`
(défaut 3, annulé sur mouvement/dégâts) puis téléporte à `SpawnService#resolve`. Aucun coût (MVP).
La persistance (monde + x/y/z) suffit déjà comme destination pour un futur « Hub → Waystone
découverte », non implémenté.

### Admin/debug (`rpgquest.admin.world`)

`/rpgadmin waystone list | here | tp <id> | generatehere | reset discoveries <joueur>`.
`generatehere` ignore le tirage de probabilité mais respecte l'unicité par cellule et la recherche
de surface sûre. Aucune commande joueur.

## Waypoints par instance de biome (`waypoint.WaypointService`, issue #124)

**À ne pas confondre avec les Waystones ci-dessus.** Les Waystones = réseau de voyage sur grille
fixe avec retour au Hub. Les waypoints = repères physiques persistants **par instance réelle de
biome**, sans téléportation dans ce MVP. Documentation complète et compromis de conception :
[docs/WAYPOINTS.md](WAYPOINTS.md).

En résumé :

- **Instance de biome** = `(monde, type de biome, tuile `travel.waypoint.region-size` blocs)` —
  `waypoint.WaypointIdentityResolver` / `BiomeInstanceKey`. Deux forêts éloignées → deux waypoints.
- **Génération** : à l'entrée d'un joueur (`PlayerMoveEvent` throttlé par
  `travel.waypoint.move-throttle-millis`) dans une instance sans waypoint, recherche **unique** d'un
  emplacement de surface à `min-distance..max-distance` blocs (jamais au pied du joueur), dans la
  même instance, hors claim, sans écraser de construction ; `RandomSafeLocationFinder#findAtColumn`
  réutilisé. Verrou mémoire + index unique `(world, biome_instance)` → jamais deux waypoints en
  concurrence. Échec → retry borné 30 s → 1 h, jamais de boucle. Persistance : table `waypoints`
  (migration V18).
- **Rendu versionné** : `waypoint.render.WaypointModelRegistry` ; la logique ne stocke qu'un
  `model_version`. V1 = `COBBLESTONE_WALL` (support) + `GOLD_BLOCK` + `STONE_BUTTON` latéral.
  L'identité du waypoint ne dépend jamais du rendu.
- **Découverte** : la proximité ne découvre rien ; **seul un clic droit sur le bouton** valide la
  découverte, persistée par UUID dans `waypoint_discoveries`. Message + son à la première découverte.
- **Protection MVP** (`WaypointProtectionListener`) : casse joueur, explosion, piston, feu, fluide
  entrant, entités (sable/gravier/enderman) ; bypass `rpgquest.admin.world`. Protection
  fonctionnelle anti-enfermement → issue #122.
- **Config** : `travel.waypoint.{enabled,region-size,min-distance,max-distance,candidate-attempts,move-throttle-millis,minimum-spacing,model-version,hub-enabled}`
  (`hub-enabled`, issue #149, étend ce même mécanisme au monde Hub configuré — voir
  « Réseau de voyage / bornes » ci-dessous).
- **Pas de commande** dédiée dans ce MVP (`WaypointService` expose `all()` / `byId()` /
  `discoveryCount()` pour une future lecture PlugAdmin).

## Réseau de voyage / bornes (`travel.beacon.TravelBeaconService`, issues #132/#150/#151/#149/#133/#135)

**Priorité gameplay après #26.** Parcours livré : un joueur découvre un waypoint dans le Wild,
meurt (ou se déplace simplement — rien dans ce système ne dépend de la mort elle-même, seules les
découvertes persistées comptent), revient au Hub, actionne une **borne de voyage**, choisit son
waypoint dans un menu graphique paginé/cherchable, et revient en sécurité exactement au même
repère. **Strictement distinct** de Waystones/Waypoints ci-dessus : une borne n'est **jamais** une
destination, jamais fusionnée dans leurs tables/identités — `TravelBeaconService` ne fait que
*lire* `WaypointService#discoveredBy`/`#hasActivelyDiscovered`, jamais écrire dedans.

- **Structure physique** : même support qu'un waypoint (`COBBLESTONE_WALL`), mais `DIAMOND_BLOCK`
  au lieu de `GOLD_BLOCK` et un bouton en bois (essence configurable, `OAK_BUTTON` par défaut) au
  lieu de `STONE_BUTTON`. Le bouton ouvre le menu — il ne découvre **jamais** le waypoint voisin.
  Table dédiée `travel_beacons` (migration V19, colonne `biome_instance` ajoutée en V20→V21),
  protection des blocs identique à celle des waypoints (bypass `rpgquest.admin.world`).
- **Placement administré** : `/rpgadmin travel beacon set` pose une borne à la position réelle de
  l'administrateur (jamais de coordonnée inventée), orientée selon son regard ; idempotent (rejouer
  à la même colonne est refusé, pas de doublon). Reste la **seule** voie de placement dans le
  **Wild** — jamais de génération automatique en dehors du Hub.
- **Génération automatique dans le Hub** (issue #149, réutilise le mécanisme exact de #124) :
  à l'entrée d'un joueur dans une instance de biome du Hub (`hub.world`) sans waypoint,
  `WaypointService#ensureGenerated` (même recherche de candidat/aire libre/espacement/verrou
  mono-vol/retry borné que le Wild, exposée publiquement pour cet appel externe — `handleMovement`
  du Wild reste strictement inchangé) génère d'abord le waypoint (modèle or, découverte par clic
  comme partout ailleurs) ; une fois ce waypoint réellement persisté, `TravelBeaconService`
  appaire une borne **distincte** (jamais à la même position) dans un anneau configurable autour de
  lui (`travel.beacon.hub-generation.pair-min-spacing`/`pair-max-spacing`, par défaut 6..16 blocs),
  en respectant les constructions et les blocs déjà protégés. Décision actée remplaçant
  l'exclusion historique du Hub (#136/#137) ; les claims restent exclus. Persistant et idempotent
  (verrou mono-vol + index unique par instance, même garantie qu'un waypoint du Wild : deux joueurs
  ou un redémarrage ne créent jamais de doublon) ; les waypoints et découvertes déjà existants du
  Wild ne sont jamais affectés. Gating : `travel.waypoint.hub-enabled` (coupe uniquement la
  génération Hub, jamais le Wild) et `travel.beacon.hub-generation.enabled` (coupe uniquement
  l'appariement de la borne, le waypoint continue de se générer seul).
- **Menu graphique** (`InventoryHolder` dédié `BeaconMenuHolder`, même patron anti-vol/duplication
  que `ui.QuestJournalService` — tout clic/drag dans le menu est systématiquement annulé) :
  - Racine : trois catégories, revalidées à **chaque clic** (jamais l'état figé au moment de
    l'ouverture) :
    - **« Waypoints découverts »** : ouvre d'abord un **choix du monde** (voir ci-dessous), jamais
      directement une liste globale.
    - **« Mon claim »** (issue #151) : résout le claim courant du joueur via
      `ClaimService#mainClaimOf` (jamais une coordonnée copiée) ; icône grisée + message si aucun
      claim. Arrivée au **centre** du claim (`RandomSafeLocationFinder#findAtColumn`, position
      vérifiée **dans** le cuboïde actif avant tout déplacement) ; claim disparu entre-temps →
      message propre, aucune téléportation. Jamais d'accès au claim d'un tiers (toujours
      `mainClaimOf(joueur courant)`).
    - **« Villages »** (issue #151) : liste des centres administrés (`VillageCenter`, table dédiée
      `village_centers`, migration V20), paginée comme les waypoints. Identité **indépendante du
      monde** : plusieurs centres peuvent coexister dans `world_hub`, distingués par `id`/nom.
      Arrivée à la position **et orientation exactes** enregistrées par l'administrateur (même
      confiance qu'un spawn — `spawn.SpawnService`) ; centre désactivé/supprimé entre l'ouverture
      du menu et le clic → revalidation stricte, aucune téléportation.
  - **Choix du monde** (`Kind.WORLDS`, issue #149-suite) : étape intermédiaire entre la racine et la
    liste des waypoints — `world_hub` (Hub) et `travel.wild-world` (Wild) ont **toujours** leur
    propre page, même à 0 découverte ; tout autre monde est **extensible**, affiché uniquement dès
    qu'au moins une découverte active du joueur courant y existe (jamais codé en dur). L'ordre
    affiché est figé dans la session au moment du rendu (`BeaconMenuSession#worldsShown`) : un clic
    référence toujours le même monde qu'au moment de l'affichage, jamais recalculé.
  - Liste des waypoints **actifs réellement découverts par ce joueur, filtrés au monde choisi**
    (jamais ceux d'un autre joueur, d'un autre monde, ni un waypoint désactivé), triée par
    **nom d'affichage** (voir « Noms de waypoints » ci-dessous), paginée (45/écran), navigation
    page précédente/suivante, retour (vers le choix du monde, jamais directement la racine),
    fermeture.
  - **Recherche graphique** (waypoints uniquement, **dans le monde choisi** — jamais un résultat
    d'un autre monde) : enclume virtuelle (`InventoryType.ANVIL` créée sans bloc réel, pattern
    standard des GUI Paper/Bukkit), coût de réparation forcé à **0** à chaque `PrepareAnvilEvent`,
    sur **`repairCost` et `repairCostAmount`** (un « Coût : 1 » résiduel avait été rapporté malgré
    `repairCost` déjà à 0 — `repairCostAmount` est un second champ vanilla distinct). Aucun objet du
    menu réellement récupérable (clic sur le résultat toujours annulé). L'objet de saisie porte une
    astuce explicite (« Clique sur l'étiquette à droite pour rechercher » — Entrée ne valide jamais
    une recherche dans cette interface Minecraft). Filtrage **insensible à la casse et aux accents**
    (`Normalizer` NFD + suppression des marques combinantes) sur le nom d'affichage et le biome.
    **Indicateur de recherche active** : une fois filtré, la liste affiche le texte recherché, le
    nombre de résultats, et un bouton dédié pour effacer le filtre (slot 46).
    **Cause racine du filtrage resté vide malgré un clic réel (retour joueur 2026-10-04, round 2)**
    : le slot « résultat » d'une enclume suit un chemin vanilla spécial (consommation des entrées à
    la prise) qui ne garantit pas que l'`ItemStack` lu par `InventoryClickEvent#getCurrentItem()`
    porte encore le texte tapé au moment où le clic est traité. `handlePrepareAnvil` mémorise
    désormais le texte tapé **dans la session**, à chaque frappe (chaque `PrepareAnvilEvent`), et
    `handleSearchResultClick` lit ce texte depuis la session plutôt que de le relire sur l'objet
    cliqué — repli sur l'ancienne lecture uniquement si la session n'a mémorisé aucune frappe
    (compatibilité directe, ex. appel sans simulation de saisie). Vérifié par un test routant un
    vrai `PrepareAnvilEvent` **et** un vrai `InventoryClickEvent` (`FakeAnvilView`, double de test
    pour l'`AnvilView` que MockBukkit ne fournit pas) qui vide en plus délibérément le slot 2 avant
    le clic, pour prouver que le filtrage ne dépend plus de l'objet cliqué ; ce même test confirme
    aussi que `repairCost`/`repairCostAmount` sont bien mis à 0 par le code. Seul le **rendu visuel
    réel côté client** (le coût affiché, la saisie clavier elle-même) reste `PENDING MANUAL
    VALIDATION` — MockBukkit ne simule pas le paquet client, seulement l'appel serveur. Le monde
    reste mémorisé pendant tout le détour par l'enclume (jamais perdu).
  - **Revalidation stricte au départ** (`hasActivelyDiscovered` pour les waypoints, résolution
    fraîche pour claim/village) : un clic périmé échoue proprement, sans téléportation. Arrivée sûre
    via `RandomSafeLocationFinder#findAtColumn` (waypoints/claim) ou position administrée exacte
    (villages) ; monde non chargé ou colonne dangereuse → message d'erreur, aucun déplacement.
  - Aucun chemin de ce service ne crée jamais de waypoint (jamais de génération pendant un voyage).
  - **Bug corrigé (validation en jeu) : tous les clics (destination, retour, recherche) restaient
    silencieux une fois le menu ouvert.** Cause : l'état du menu (`BeaconMenuSession`) était
    enregistré **avant** `player.openInventory(...)`, qui ferme d'abord l'ancien menu de façon
    **synchrone** — ce `InventoryCloseEvent` effaçait aussitôt la session tout juste posée (même
    classe de bug que la navigation du journal de quêtes, issue #11). Correctif : la session est
    désormais posée **après** l'ouverture, dans toutes les méthodes `open*` — couvert par des tests
    qui routent un vrai `InventoryClickEvent` à travers le listener réel (jamais un appel direct aux
    méthodes de gestion de clic, qui aurait masqué ce bug comme précédemment).

### Noms de waypoints (issues #133/#135)

Chaque waypoint a désormais un **nom d'affichage** (`waypoint.display_name`) humain, unique et
persistant — l'identité **lisible** principale pour le joueur. Le biome reste une **métadonnée
secondaire** (deux waypoints du même biome n'affichent donc plus jamais le même libellé, ex. deux
« beach » indiscernables avant ce correctif).

- **Réserve statique bundlée** (`src/main/resources/waypoint-names.txt`, un nom par ligne) chargée
  par `waypoint.model.WaypointNameCatalog` — **jamais un appel IA au runtime** ; l'issue #148
  enrichira cette réserve plus tard, sans toucher ce mécanisme.
- **Dédoublonnage à l'import** : lignes vides/commentaires (`#`) ignorés, doublons détectés
  insensibles à la casse/accents/espaces (`WaypointNameCatalog#normalize`), seule la première
  occurrence est gardée.
- **Attribution sans doublon** : un nom est réservé **de façon synchrone**, sur le thread principal,
  au moment même de la génération du waypoint (`WaypointService#attemptGeneration`) — Bukkit traite
  les événements séquentiellement, donc deux générations ne peuvent jamais se chevaucher dans ce
  processus ; un ensemble en mémoire (`usedDisplayNames`, rechargé au démarrage depuis tous les
  waypoints existants) empêche toute réutilisation. Index unique SQL (`idx_waypoints_display_name`)
  en défense supplémentaire.
- **Réserve épuisée** : nom de secours unique généré (`"Avant-poste N"`, N incrémenté jusqu'à
  trouver un nom libre) — jamais un échec de génération.
- **Noms lisibles** (retour joueur 2026-10-04) : la réserve bundlée (`waypoint-names.txt`) ne
  concatène plus deux mots sans séparateur (ex. l'ancien `Lacgivre`) — grammaire générée
  automatiquement `<nom> de <nom>` (ex. `Lac de Givre`, `Lac d'Or` avec élision devant voyelle) ou
  `<adjectif accordé en genre> <nom>` (ex. `Lac Gris`, `Haute Source`), sans jamais insérer un
  espace arbitraire au milieu d'un mot. Les noms déjà attribués par la migration V22 sont renommés
  par la migration **V23** via une table de correspondance figée ancien→nouveau nom
  (`SchemaMigrator#RENAMED_DISPLAY_NAMES`, comparaison normalisée insensible casse/accents) : `id`
  et découvertes **jamais** modifiés, unicité revérifiée par l'index SQL existant (aucune
  collision possible, la correspondance est injective sur les 220 entrées du catalogue).
- **Panneaux latéraux de nom (issue #167)** : `WaypointModelV1` pose désormais un
  `OAK_WALL_SIGN` sur **chacune** des deux faces latérales adjacentes à la face du bouton (jamais
  la face opposée) — affiche le nom canonique, réparti sur ses lignes par `WaypointModelV1#wrapSignLines`
  (découpage sur des frontières de mots, jamais une troncature ambiguë, 4 lignes max). Posés à la
  génération et à la réparation (`WaypointService#repair`) ; **mise à niveau idempotente** des
  waypoints déjà existants via `/rpgadmin travel signs upgrade [monde]` (rejoue `model.place(...)`
  à la position/orientation déjà persistées — id/position/découvertes **jamais** modifiés, les
  blocs déjà corrects du support ne sont jamais réécrits). Les deux offsets de panneau sont inclus
  dans `protectedBlocks()` : protection automatique (casse/édition/explosion) identique au reste de
  la structure, y compris pour les waypoints déjà en base dès cette version déployée. Rejouable
  après un renommage pour rafraîchir le texte affiché.
- **Waypoints déjà existants** : migration **V22** (voir plus bas) attribue un nom à chaud à chaque
  ligne déjà en base, dans un ordre stable (par `id`), sans jamais toucher l'`id` ni les découvertes
  joueurs déjà enregistrées.
- **Administration des centres de village** : `/rpgadmin travel village sethub <id> <nom...>` (pose
  le centre **sur le spawn du Hub déjà configuré**, `spawn.SpawnService#resolve` — jamais une
  coordonnée inventée) ; `/rpgadmin travel village set <id> <nom...>` (position réelle de
  l'administrateur, pour un centre secondaire ailleurs dans `world_hub`) ; `remove` / `enable` /
  `disable <id>` (déplacement/désactivation **sans jamais changer l'id**, donc sans casser une
  référence déjà exposée dans le menu) ; `list`.
- **Accessibilité à la génération (issue #153)** : `RandomSafeLocationFinder#findAccessibleColumn`
  rejette désormais un sol de feuillage (tout bloc `*_LEAVES`) et toute colonne isolée (surplomb,
  îlot) sans voisin praticable à ±1 bloc — utilisé par `WaypointService#attemptGeneration` et
  `TravelBeaconService#attemptPairBeacon`. Un waypoint/une borne ne peut donc plus se poser au
  sommet d'un arbre ou sur un surplomb inaccessible à pied, sans jamais autoriser la casse/pose
  joueur dans le Hub.
- **Diagnostic et réparation (issues #153/#156)** : `/rpgadmin travel diagnose [monde]` (lecture
  seule, à partir des données persistées/indexées) liste le nombre de waypoints/bornes, les
  instances de biome du Hub avec un waypoint mais **sans** borne appariée (jamais dépendant d'une
  découverte joueur), et les structures déjà posées devenues inaccessibles.
  `/rpgadmin travel repair waypoint|beacon <id> confirm` déplace la structure concernée vers un
  emplacement accessible proche de sa position actuelle — `id`/nom d'affichage/instance de
  biome/découvertes **jamais** modifiés, seuls les blocs ajoutés par la structure sont déplacés
  (jamais le sol/la végétation environnante).
- **Control Panel (issue #152, terminé)** : page `/travel` (PlugAdmin) en **lecture seule**, action
  agent `travel.catalog` (réutilise `WaypointService#all()`/`TravelBeaconService#all()`, jamais un
  scan monde). Listes séparées waypoints/bornes (id, nom, monde, biome/instance, coordonnées,
  actif, version de modèle), pairage waypoint↔borne visible pour chaque ligne (y compris les
  waypoints du Hub **sans** borne appariée), compteurs par monde, recherche + filtre par monde,
  pagination (20/page), horodatage de fraîcheur de la donnée, distinction explicite **enregistré**
  (dernier relevé agent) vs **vérifié physiquement** (voir `/rpgadmin travel diagnose` pour ça).
  Aucun éditeur (création/déplacement/désactivation) : hors périmètre explicite du ticket, qui
  demandait la consultation d'abord.

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

Portails simples et outils de diagnostic : `WorldPortalRegistryTest` (dont
les deux tests documentant l'anomalie de validation croisée ci-dessus),
`WorldPortalTeleportListenerTest` (dont le répit d'arrivée et son
expiration), `WorldPortalDebugGeometryTest` (calcul pur du contour,
aucune dépendance Bukkit), `WorldPortalDebugServiceTest` (état
affiché/masqué, rendu sans exception), `TpTraceLoggerTest` (le format ne
lève jamais malgré des champs optionnels absents).

Voyage par objet : `ItemTravelServiceTest` (clic droit avec l'objet enregistré démarre la
canalisation, objet non enregistré ignoré, mouvement/dégâts annulent proprement sans téléporter,
déconnexion nettoie l'état, objet jamais consommé quelle que soit l'issue, **hors du monde requis
par la définition aucune canalisation ne démarre**, **une définition sans restriction fonctionne
n'importe où**, **la progression atteint 100% puis l'actionbar est vidée après un succès**,
**l'actionbar est vidée après une annulation**) — voir aussi [docs/CLAIMS.md](CLAIMS.md) pour
`ClaimNetherTravelListenerTest`/`ClaimBorderGeometryTest`/`ClaimBorderEntryListenerTest`, dans le
même bloc de mission.

Voyage par objet, suite : `ItemTravelServiceTest` couvre aussi le **cooldown** de la Rune
(un voyage réussi bloque l'usage suivant, cooldown persisté) et le **refus hors du monde requis**.

Secours Hub (issue #154) : `ItemTravelServiceTest` — `freeRescueWorldTeleportsImmediatelyIgnoringCooldownAndRequiredWorld`
(le chemin de secours gratuit est bien atteint même avec un `requiredWorld` différent, preuve
indirecte via l'exception `UnimplementedOperationException` de `teleportAsync`, jamais présentée
comme une validation de la téléportation cliente elle-même) et
`outsideTheFreeRescueWorldTheNormalRequiredWorldRestrictionStillApplies` (comportement normal
inchangé partout ailleurs). `StarterKitListenerTest` (nouveau) : distribution à la première
connexion, aucune duplication si déjà possédée, et surtout **inventaire plein à la première
connexion ne marque plus jamais la Rune distribuée** (retenté à la connexion suivante).

Recherche de waypoints, round 2 (issue #150) : `TravelBeaconServiceTest` —
`realTypingThenClickingTheResultFindsLacDeGivreCaseAndAccentInsensitivelyWithActiveFilterIndicator`
(vraie saisie via un vrai `PrepareAnvilEvent`, construit avec `FakeAnvilView` -- double de test pour
l'`AnvilView` que MockBukkit ne fournit pas -- puis vrai clic sur le résultat) et
`searchStillFiltersEvenWhenTheResultSlotItemIsGoneByTheTimeTheClickIsHandled` (le slot résultat est
délibérément laissé vide avant le clic : le filtrage doit malgré tout fonctionner, preuve que la
session porte le filtre, jamais l'objet cliqué).

Panneaux de nom des waypoints (issue #167) : `WaypointModelV1Test` (répartition du nom sur les
lignes, pur, sans Bukkit -- jamais un mot coupé, jamais plus de 4 lignes, rejoindre les lignes
reconstruit exactement le nom d'origine) ; `WaypointServiceTest` —
`waypointGenerationPlacesTwoLateralNameSignsWithTheCanonicalNameAndKeepsTheButtonAccessible` (type,
orientation et texte réels des deux panneaux, bouton resté l'interacteur, panneaux protégés) et
`upgradeSignsRetrofitsAnExistingWaypointWithoutChangingItsIdentity` (mise à niveau idempotente d'un
waypoint simulé « pré-#167 », id/position/nom inchangés, rejouable sans risque).

Bossbar de suivi (issue #157) : `TrackedQuestDisplayTest` (nouveau, package `ui`) — vérifie la
`BossBar` réellement envoyée au joueur (MockBukkit), pas une fonction utilitaire isolée :
aucune balise résiduelle, libellé humain de l'objectif plutôt que l'id technique, repli sur l'id
uniquement si aucune description n'existe, une seule bossbar jamais dupliquée, retrait propre.

Système soulbound générique : `SoulboundItemListenerTest` (remplace `ReturnStoneGuardListenerTest`) —
tout objet soulbound enregistré est intombable au drop et à la mort, restauré tel quel à la
réapparition sans jamais dupliquer, un objet quelconque jamais concerné.

Avertissement Wild (issue #161) : `WildEntryWarningServiceTest` — première entrée avertie avec
inventaire vide (donc sans aucune inspection d'inventaire) et texte complet des trois actions,
aucune téléportation avant confirmation, annulation et fermeture silencieuse qui laissent le joueur
au Hub sans rien mémoriser, retour de préparation envoyé *avant* tout tick, « ne plus afficher »
persisté uniquement sur un départ réel et relu par un service neuf (≈ redémarrage), avertissement
masqué qui téléporte directement mais montre toujours la préparation, une seule demande en vol
(trois entrées rapprochées → un seul avertissement ; double-clic → une seule téléportation),
déconnexion sans téléportation tardive, portail hors Wild jamais concerné, et rendu réel du repli
chat. `WorldPortalTeleportListenerTest` — un garde qui refuse n'est pas reconsulté tant que le
joueur reste dans la même zone (pas de boucle de menu) mais l'est de nouveau après une sortie/entrée,
et `teleportNow` contourne volontairement le garde.

État du Wild (issue #24) : `WildConditionsServiceTest` — jour/nuit depuis l'horloge réelle, bornes de
nuit, pluie et orage distingués (l'orage n'est jamais annoncé comme une simple averse), météo qui ne
change jamais le verdict jour/nuit, lecture qui ne modifie ni l'heure ni la météo, Wild non chargé
jamais inventé. `DialogueTextPlaceholdersTest` + `DialogueSessionEngineTest` — substitution de
`%wild_conditions%` au moment du rendu, clé inconnue laissée visible, aucune récursion, toutes les
langues traitées. `BundledDialoguesValidityTest` — le Garde expose en permanence le choix « Comment
est le Wild actuellement ? » vers un nœud dynamique, sans condition ni action.

Latence de portail (issue #161) : `RandomSafeLocationFinderTest` (tentatives et temps de chargement
de chunk réellement comptés, résultat inchangé) et `TpTraceLoggerTest` (ligne `[TP-LATENCY]`,
conversion ns → ms).

Waystones : `WaystoneCellPlannerTest` (décision déterministe seed+cellule, idempotente, `chance`
0/1, candidat toujours dans sa cellule) ; `WaystoneServiceTest` (génération non dupliquée / unicité
par cellule, rechargement des Waystones persistées au démarrage, espacement minimal, surface sûre
solide, découverte individuelle par joueur, `reset discoveries`, canalisation de retour annulée par
un déplacement).

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : portail vers
un chunk réellement déchargé, déconnexion en pleine canalisation,
reconnexion et persistance du cooldown, téléportation avec un inventaire
chargé et une quête active, test depuis/vers une safe zone, rendu visuel
réel de `/rpgadmin worldportal debug` (particules/étiquette), Pierre de
génération réelle de Waystones à l'exploration de chunks (terrain non simulé par MockBukkit — seule
la décision `WaystoneCellPlanner` et la pose forcée `generatehere` sont couvertes automatiquement),
rendu visuel du faisceau/structure, callbacks de chat cliquables (`[Continuer]`/`[Retourner au
Hub]`), Rune de rappel en jeu (canalisation 10 s, cooldown 30 min persistant après reconnexion),
avertissement d'entrée dans le Wild sur le vrai portail Hub → wild. Pierre de
retour en jeu (canalisation ~3 s, annulation par mouvement, arrivée
effective au spawn du Hub, **actionbar propre — 100% puis disparition
immédiate, aucun résidu type « 98% »**, **refus propre et bref hors du
monde `claims`**, **impossible à jeter (touche Q)**, **jamais perdue à la
mort, redonnée automatiquement à la réapparition**).

**Validé en jeu le 2026-10-04** : secours Hub via la Rune de rappel (#154), noms lisibles avec
espaces (#133/#135), retour « déjà découvert » avec le nom (partiel, voir #160 ci-dessous), listes
et filtres de la page Control Panel `/travel` (#152), faim redevenue normale dans le Wild (#159).

`PENDING MANUAL VALIDATION`, retours joueur 2026-10-04, round 2 (issues #157/#150/#160/#167) :
- **Recherche de waypoints (#150)** : le joueur a confirmé que le filtrage restait vide malgré un
  clic réel sur le résultat. Cause identifiée : le clic sur le slot « résultat » d'une enclume suit
  un chemin vanilla spécial qui ne garantit pas que l'objet lu par le clic porte encore le texte
  tapé. Corrigé en mémorisant le texte tapé en continu dans la session (à chaque frappe, voir
  `TravelBeaconService#handlePrepareAnvil`), jamais relu sur l'objet cliqué. Reste à revalider :
  taper « lac » puis cliquer l'étiquette retrouve bien « Lac de Givre » ; l'indicateur de recherche
  active et son bouton d'effacement restent visibles ; **le coût XP affiché doit être absent** —
  le code zérote bien `repairCost`/`repairCostAmount` (vérifié par test), seul le rendu visuel
  client reste à confirmer en jeu.
- **Bossbar de suivi (#157)** : `</gray>` littéral + id technique brut (`kill_spiders`) corrigés
  (gabarit réparé, libellé humain de l'objectif affiché). À revérifier avec « Premiers pas » et un
  changement de progression.
- **Première découverte sans nom (#160)** : le message de première découverte affichait seulement
  le biome ; affiche désormais `Waypoint découvert : <nom> — <biome>`, comme le reclic. À revérifier
  sur une destination jamais découverte.
- **Panneaux latéraux de nom (#167, nouveau)** : deux panneaux (faces latérales du bouton) affichant
  le nom canonique, posés à la génération ; `/rpgadmin travel signs upgrade [monde]` pose/rafraîchit
  ceux des waypoints déjà existants (idempotent, id/position/découvertes inchangés). À valider : lisibilité
  réelle en jeu (répartition des noms longs sur les lignes), orientation des panneaux, bouton resté
  cliquable.
- **Libellés du panel Voyage** : « Apparié » remplacé par « Borne associée »/« Waypoint associé »,
  affichant l'id réel plutôt qu'un simple oui/non. À revérifier visuellement sur `/travel`.
