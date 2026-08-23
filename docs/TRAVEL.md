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

-   `ItemTravelDefinition(itemId, channelSeconds, destinationSupplier)` — la destination reste un
    simple fournisseur (`Supplier<Optional<Location>>`), résolu à chaque téléportation réussie plutôt
    qu'une position figée (ex. `spawn.SpawnService#resolve`, toujours l'état courant du spawn).
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

Aucune restriction de monde dans le moteur générique lui-même (utilisable depuis n'importe où, pas
seulement `claims`) — **point non tranché explicitement par la mission**, signalé dans le rapport
Claude de cette étape comme décision à confirmer plus tard si une restriction s'avère nécessaire.

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
déconnexion nettoie l'état, objet jamais consommé quelle que soit l'issue) — voir aussi
[docs/CLAIMS.md](CLAIMS.md) pour `ClaimNetherTravelListenerTest`/`ClaimBorderGeometryTest`/
`ClaimBorderEntryListenerTest`, dans le même bloc de mission.

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : portail vers
un chunk réellement déchargé, déconnexion en pleine canalisation,
reconnexion et persistance du cooldown, téléportation avec un inventaire
chargé et une quête active, test depuis/vers une safe zone, rendu visuel
réel de `/rpgadmin worldportal debug` (particules/étiquette), Pierre de
retour en jeu (canalisation ~3 s, annulation par mouvement, arrivée
effective au spawn du Hub).
