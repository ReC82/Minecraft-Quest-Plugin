# Claims de terrain

Permet à un joueur de réclamer et protéger un terrain cuboïde éloigné du
village, sans dépendre d'un administrateur. Voir
[docs/ARCHITECTURE.md](ARCHITECTURE.md) (section `claim`) pour le détail
d'implémentation.

Deux façons d'obtenir un claim, qui **partagent le même moteur**
(`ClaimService#create`, mêmes vérifications, même stockage) :

1. **`/claim wand` + `/claim create <id>`** (ouvert à tout joueur avec
   `rpgquest.claim`) — sélection arbitraire, décrit dans ce document depuis
   l'origine.
2. **Premier claim principal via l'Acte de propriété** (mission « premier
   claim 5×5 débloqué par une Story ») — voir « Premier claim (Acte de
   propriété) » ci-dessous : UX 100% sans commande, taille fixe (`TIER_1`,
   5×5), débloquée par une récompense de Story/quête.

## Commandes (`rpgquest.claim`, ouvert à tout joueur)

-   `/claim wand` — outil de sélection (hache en bois marquée par
    PersistentDataContainer, jamais reconnue par son nom — dédiée, distincte
    de l'outil de sélection de zone protégée). Clic gauche = position 1,
    clic droit = position 2.
-   `/claim create <id>` — crée un claim cuboïde depuis la sélection
    courante. Seule sous-commande qui prend un identifiant explicite (rien
    n'existe encore à « ta position actuelle »).
-   `/claim delete` — supprime le claim où tu te trouves (propriétaire
    uniquement).
-   `/claim info` — détail du claim où tu te trouves (propriétaire, monde,
    bornes, nombre de membres, redstone publique).
-   `/claim trust <joueur>` — ajoute un membre de confiance au claim où tu
    te trouves (propriétaire uniquement, joueur cible en ligne).
-   `/claim untrust <joueur>` — retire un membre de confiance.
-   `/claim list` — liste **tes** claims (id, monde), où que tu sois.
-   `/claim flag redstone <true|false>` — autorise ou non les non-membres à
    utiliser boutons/leviers/portes/dalles de pression du claim où tu te
    trouves (propriétaire uniquement). Seule permission réellement
    configurable, voir plus bas.
-   `/claim admin resettier1 <joueur>` — **admin uniquement**
    (`rpgquest.claim.admin` ou `rpgquest.admin.world`, pas `rpgquest.claim`),
    voir « Réinitialisation admin (test/QA) » plus bas.
-   `/claim admin tp <joueur>` — **admin/debug uniquement**
    (`rpgquest.claim.admin` ou `rpgquest.admin.world`), voir « Retour à son
    claim (PNJ Jo / commande admin) » plus bas.

Toutes les sous-commandes qui agissent sur un claim précis (`delete`,
`info`, `trust`, `untrust`, `flag`) opèrent sur **le claim où tu te
trouves**, jamais sur un id tapé à la main — délibéré, cohérent avec la
mission (qui ne montre un argument que pour `trust`/`untrust <joueur>`).

## Un claim contient

-   un identifiant (choisi à la création, `/claim create <id>`) ;
-   un propriétaire, identifié **par UUID**, jamais par pseudo (un pseudo
    peut changer ; toutes les vérifications de propriété/confiance
    utilisent l'UUID stocké) ;
-   un monde et des bornes cuboïdes ;
-   une liste de membres de confiance (UUID) ;
-   des paramètres de permissions (aujourd'hui : redstone publique
    uniquement).

Persisté en SQLite (`claims`/`claim_members`, migration V7) — contrairement
aux zones protégées (YAML, curées par un administrateur), un claim est créé
et modifié fréquemment par n'importe quel joueur, ce qui correspond mieux à
une base de données qu'à des fichiers à éditer à la main.

## Refus à la création

`/claim create <id>` est rejeté si la sélection :

-   chevauche un claim existant (même monde) ;
-   chevauche une zone protégée (village/safe zone, voir
    [docs/SAFE_ZONE.md](SAFE_ZONE.md)) ;
-   se trouve à moins de `claims.portal-buffer-blocks` (16 par défaut) d'un
    portail existant (voir [docs/TRAVEL.md](TRAVEL.md)) ;
-   dépasse `claims.max-width`/`claims.max-height` (64×384 par défaut) ;
-   ferait dépasser `claims.max-claims-per-player` (3 par défaut) au
    propriétaire ;
-   **chevauche la réservation** d'un autre claim (`OVERLAPS_RESERVATION`,
    voir « Modèle de palier / réservation » ci-dessous) — même si le
    cuboïde actif de ce dernier est aujourd'hui bien plus petit ;
-   serait le **premier claim** du joueur sans que celui-ci possède
    `CLAIM_TIER_1` (`MISSING_PREREQUISITE`, voir « Premier claim » ci-dessous) —
    un 2e/3e claim ne revérifie jamais ce prérequis.

**Aucun claim invalide ne peut être créé** : toutes ces vérifications sont
faites avant toute écriture en base ; un refus ne modifie rien.

## Protections

| Catégorie | Configurable ? | Comportement |
|---|---|---|
| Blocs (casse/pose) | non | Bloqué pour tout non-membre |
| Conteneurs (coffres, tonneaux, fourneaux...) | non | Bloqué pour tout non-membre |
| Animaux (dégâts) | non | Bloqué pour tout non-membre |
| Armor stands (dégâts et manipulation d'équipement) | non | Bloqué pour tout non-membre |
| Redstone (boutons, leviers, portes/trappes/portails en bois, dalles de pression) | **oui** (`/claim flag redstone`) | Membre uniquement par défaut ; public si activé |
| Explosions | non | Toujours bloquées (creeper, TNT... l'entité se consume normalement, seule la destruction de bloc est empêchée) |
| Pistons traversant la frontière | non | Toujours bloqué dès qu'un claim est concerné d'un côté ou de l'autre |

Seule la redstone est réellement configurable par le propriétaire — toutes
les autres protections sont fixes, conformément à la mission.

## Bypass administrateur

`rpgquest.claim.bypass` (ou `rpgquest.admin.world`, qui la porte en enfant —
`default: op`) exempte l'acteur direct d'une action de la protection d'un
claim — jamais la victime.

> **Issue #27 — garantie centrale :** **aucune** permission de build
> (`rpgquest.build.*`, `rpgquest.build.hub.*`, `rpgquest.build.wild`,
> `rpgquest.build.zone`…) n'accorde, à aucun niveau, le contournement d'un
> claim joueur. Construire dans un Hub ou le Wild ne donne jamais le droit
> de toucher au terrain protégé d'un joueur. Voir
> [PERMISSIONS.md](PERMISSIONS.md).

La construction **hors de tout claim** dans le monde des claims
(`ClaimsWorldRulesListener`) est régie séparément par les permissions de
build de ce monde (`rpgquest.build.world.claims`, `rpgquest.build.*` ou
`rpgquest.admin.world`) — voir ci-dessous.

## Premier claim (Acte de propriété)

Mission « premier claim 5×5 débloqué par une Story » : un joueur peut
obtenir son tout premier claim (« claim principal ») sans jamais taper de
commande, via un objet spécial remis par un PNJ.

1. **`CLAIM_TIER_1`** — variable joueur persistante (`player_variables`,
   voir `PlayerVariableRepository`), valeur attendue exactement `"true"`
   (`ClaimService#CLAIM_TIER_1_KEY`/`CLAIM_TIER_1_VALUE`). Débloquée
   aujourd'hui par la récompense `VARIABLE` de la dernière quête de
   `main_story` (`crystal_hunt.yml`) — n'importe quelle quête/Story future
   peut l'accorder de la même façon (`type: VARIABLE`, `key: CLAIM_TIER_1`,
   `value: "true"`), aucun couplage en dur à `crystal_hunt`.
2. **PNJ Jo** — créé/configuré **manuellement** par un administrateur
   (Citizens), jamais par RPGQuest (voir
   [docs/NPC_DIALOGUES_QUESTS_GUIDE.md](NPC_DIALOGUES_QUESTS_GUIDE.md) pour
   la procédure `/rpgadmin npc tag jo` + `plugins/RPGQuest/dialogues/jo.yml`,
   fourni en exemple dans le jar mais **jamais copié automatiquement** —
   RPGQuest ne prévoit que l'intégration générique id logique/dialogue).
   Son option « Je viens réclamer mon acte de propriété » n'est visible que
   si :
   -   `CLAIM_TIER_1` est débloqué (condition `VARIABLE_EQUALS`) ;
   -   le joueur ne possède **encore aucun claim** (condition
       `NO_MAIN_CLAIM`, nouvelle : voir `dialogue.model.NoMainClaimCondition`,
       source de vérité = `ClaimService#claimsOwnedBy`, jamais une variable
       dupliquée).
   Tant que ces deux conditions tiennent, Jo redonne l'Acte **gratuitement**
   à chaque fois (aucune condition sur l'inventaire : le perdre n'empêche
   jamais de le redemander). Dès qu'un claim existe, l'option disparaît
   d'elle-même.
3. **Acte de propriété** (`rpgquest:acte_propriete`, objet personnalisé
   `QUEST_ITEM`, non empilable) — remis via l'action de dialogue
   `RUN_SAFE_COMMAND` (`customitem give %player% rpgquest:acte_propriete 1`,
   `customitem` whitelisté dans `dialogue.allowed-commands`), même
   mécanisme déjà utilisé par la récompense `COMMAND` de `crystal_hunt`
   pour `miner_pickaxe`.
4. **Pose du claim** — clic droit sur un bloc avec l'Acte, **dans le monde
   des claims** (`claims.world`, voir Configuration) :
   -   1er clic droit sur une cible : **aperçu** (message chat, bornes
       actives 5×5 et réservation 100×100 centrées sur le bloc visé) —
       aucune écriture, aucun claim créé.
   -   2e clic droit sur (ou près de) la **même** cible : **confirmation**
       — crée réellement le claim via `ClaimService#create` (mêmes
       vérifications que `/claim create`, voir « Refus à la création »),
       consomme un exemplaire de l'Acte si le claim est créé.
   -   hors du monde des claims : refus propre (message), jamais
       d'exception, jamais de claim créé.
   -   aperçu conservé en mémoire uniquement (`DeedClaimListener`, jamais
       persisté) — perdu sur redémarrage/déconnexion, sans conséquence (ce
       n'est qu'une intention, jamais un engagement).

Voir `claim.DeedClaimListener` pour l'implémentation complète.

## Retour à son claim (PNJ Jo / commande admin)

Mission « Jo : retourner à son claim » — une fois le claim principal posé
(voir « Premier claim » ci-dessus), le joueur doit pouvoir le retrouver
facilement après reconnexion/mort/retour au Hub, **sans jamais dépendre de
coordonnées codées en dur** : tout est résolu à chaque appel depuis le claim
persistant lui-même (`ClaimService#mainClaimOf`), donc valide après
redémarrage du serveur.

-   **`ClaimService#mainClaimOf(UUID)`** — unique point d'accroche pour un
    futur choix multi-claims (mission : « préparer l'architecture, ne pas
    développer la sélection maintenant ») : résout aujourd'hui simplement le
    premier (et unique) claim possédé par le joueur. Quand plusieurs claims
    deviendront possibles, seule cette méthode devra changer.
-   **`claim.ClaimTeleportService`** — cherche une position sûre (suffocation,
    chute, lave, bloc solide... écartés) et téléporte : centre du claim
    d'abord (`travel.RandomSafeLocationFinder#findAtColumn`, nouvelle
    variante à colonne fixe de l'utilitaire déjà utilisé par les portails
    aléatoires — aucune seconde implémentation de recherche de position
    sûre), puis, si le centre est obstrué/dangereux, balaie toutes les
    colonnes du cuboïde **actif** du claim — **jamais au-delà** : la
    destination reste toujours strictement sur la propriété du joueur.
    Quatre issues : `TELEPORTED`, `NO_MAIN_CLAIM`, `WORLD_UNAVAILABLE`,
    `NO_SAFE_LOCATION` (message clair au joueur/à l'admin dans chaque cas).
-   **PNJ Jo** — nouvelle option « Me rendre sur ma propriété », visible
    uniquement si le joueur possède déjà un claim principal (condition
    `HAS_MAIN_CLAIM`, nouvelle : strict opposé de `NO_MAIN_CLAIM`, même
    source de vérité). Déclenche `RUN_SAFE_COMMAND` → `/claim admin sendhome
    %player%` (« claim » ajouté à `dialogue.allowed-commands`) — **jamais**
    une alternative au portail Hub → claims, qui reste le seul moyen
    d'explorer avant de poser un premier claim ; Jo n'offre qu'un accès
    rapide à une propriété déjà existante.
-   **`/claim admin sendhome <joueur>`** — jamais tapé par un joueur, sous-
    commande **volontairement admin-only** (`rpgquest.claim.admin` ou
    `rpgquest.admin.world`, toujours accordée à la console) uniquement dispatchée par la console via
    `RUN_SAFE_COMMAND` depuis le dialogue de Jo (même mécanisme déjà utilisé
    pour donner l'Acte de propriété via `customitem give`). Téléporte le
    joueur **ciblé** (celui qui vient de parler à Jo, donc forcément en
    ligne) vers son propre claim principal.
-   **`/claim admin tp <joueur>`** — outil **admin/debug** distinct (mission
    « commande admin pour retrouver un claim ») : téléporte
    l'**administrateur qui exécute la commande** (jamais le joueur ciblé)
    vers une position sûre du claim principal de `<joueur>`, et affiche
    claimId/monde/centre/taille active. Fonctionne même si `<joueur>` est
    **hors ligne** (résolution asynchrone, même patron que
    `RpgAdminCommand#resolveTargetPlayer`). Ne remplace jamais l'UX de Jo —
    outils strictement séparés, pas seulement par permission mais par
    comportement (qui est téléporté diffère).

## Visualisation des limites du claim

Mission « MVP du monde claims » : le cuboïde **actif** (jamais la
réservation) est affiché par particules, uniquement au propriétaire, sans
jamais modifier de bloc.

-   **`claim.ClaimBorderGeometry`** (pur, sans dépendance Bukkit) — calcule
    un périmètre (4 côtés) à une seule hauteur (celle du joueur au moment du
    rendu — un claim peut s'étendre sur toute la hauteur du monde, dessiner
    les 12 arêtes complètes comme `travel.WorldPortalDebugGeometry` serait
    illisible) plus une courte colonne verticale à chacun des 4 coins pour
    rester repérable.
-   **`claim.ClaimBorderRenderer`** — envoie les particules via
    `Player#spawnParticle` (jamais `World#spawnParticle`) : le paquet est
    livré **uniquement au client de ce joueur**, aucun visiteur ne les reçoit
    jamais, aucun filtrage par distance nécessaire. ~5 secondes par appel
    (tâche répétée auto-annulée) ; un second appel pour le même joueur
    redémarre proprement depuis zéro (pas d'accumulation de tâches).
-   **`claim.ClaimBorderEntryListener`** — détection d'entrée, **volontairement
    séparée du renderer** (mission : pouvoir changer l'effet sans toucher à
    la détection) : édge-triggered comme `travel.PortalService#handleMove`
    (bloc par bloc, jamais à chaque micro-mouvement), déclenche le rendu
    uniquement quand le propriétaire entre dans **son propre** claim depuis
    l'extérieur — jamais pour un visiteur, jamais de spam en marchant à
    l'intérieur, ressort puis rentre réarme la détection.
-   **Revoir volontairement ses limites** — réutilise l'Acte de propriété
    (mission : « pas une nouvelle mécanique complexe si l'Acte peut remplir
    ce rôle proprement ») : une fois un claim principal posé, un clic droit
    avec l'Acte dans le monde des claims affiche ses limites **au lieu**
    de tenter une seconde création — l'objet n'est **jamais consommé** dans
    ce cas (voir `claim.DeedClaimListener`). Jo peut (re)donner l'Acte à
    cette seule fin via le choix « Revoir les limites de ma propriété »
    (condition `HAS_MAIN_CLAIM` + `LACKS_CUSTOM_ITEM`, voir
    [docs/RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md)) — jamais de duplication
    tant qu'il en reste un exemplaire en poche.
-   **Retrouver son claim à distance** (mission « retrouver visuellement son
    claim à distance ») : ce même clic droit avec l'Acte choisit désormais
    **selon la position réelle du joueur** au moment du clic (`Claim#contains`,
    jamais le bloc visé) entre deux rendus, tous deux privés au propriétaire
    et sans aucun bloc modifié :
    -   **à l'intérieur** de son claim : le périmètre habituel
        (`ClaimBorderRenderer#show`, ~5 s) ;
    -   **ailleurs dans le monde des claims** : `ClaimBorderRenderer#showBeacon`
        — une colonne de particules du sol du monde jusqu'à sa limite de
        construction, au centre du claim (~13 s). Depuis la révision « boucle
        joueur », la colonne est **dense** (pas de 1 bloc) et superpose une
        particule `DUST` teintée (couleur du claim) et une `END_ROD` très
        lumineuse — le plus proche possible d'un faisceau de balise, sans
        poser le moindre bloc réel (fallback propre : un vrai beam vanilla
        exigerait un bloc permanent). Jamais la réservation 100×100, jamais
        les deux rendus en même temps (états/tâches strictement séparés).

L'Acte de propriété est par ailleurs **soulbound** (mission « système soulbound
générique ») — voir [docs/TRAVEL.md](TRAVEL.md), section « Système soulbound
générique » : `item.SoulboundItemService` remplace les écouteurs dédiés par
objet ; l'Acte (comme la Pierre de retour, le Journal des quêtes et la Rune de
rappel) ne peut plus être jeté ni tomber à la mort, et Jo continue de le
redonner gratuitement s'il manque.

## Modèle de palier / réservation

Chaque claim a désormais **deux cuboïdes** (`claim.model.Claim`) :

-   le cuboïde **actif** (`minX/Y/Z`, `maxX/Y/Z`) — réellement protégé et
    constructible, exactement comme avant ;
-   le cuboïde de **réservation** (`reservedMinX/Y/Z`, `reservedMaxX/Y/Z`,
    toujours ⊇ au cuboïde actif) — met de côté l'espace nécessaire à une
    future extension du claim, **sans qu'aucun autre claim ne puisse s'y
    installer entre-temps** (`Claim#overlapsReservation`, vérifié à chaque
    création, voir « Refus à la création »).

`claim.model.ClaimTier` centralise les tailles par palier — **seul
`TIER_1` (5×5 actif / 100×100 de réservation) est réellement atteignable
aujourd'hui** ; l'énumération existe pour que le modèle (`Claim`,
`ClaimRepository`, migration V15) n'ait pas besoin d'être retouché quand un
futur palier (10×10, 20×20...) sera implémenté — **aucune logique
d'amélioration/upgrade n'existe encore**, volontairement hors périmètre.

Un claim créé via `/claim create` (baguette, sélection arbitraire) n'a
**aucune réservation supplémentaire** : sa réservation vaut exactement son
cuboïde actif — comportement strictement identique à avant l'introduction
de ce modèle (voir le constructeur `Claim` à 9 bornes, conservé tel quel).

Persisté via la migration V15 (`reserved_min_x/y/z`, `reserved_max_x/y/z`
sur `claims`) — les claims déjà existants avant cette migration voient leur
réservation automatiquement initialisée à leur propre cuboïde actif
(aucune régression, aucune action manuelle requise).

## Règles du monde des claims (`ClaimsWorldRulesListener`)

Distinctes de la protection par claim ci-dessus (`ClaimProtectionListener`,
qui protège un cuboïde précis dans **n'importe quel** monde) — trois règles
s'appliquent au **monde entier** désigné par `claims.world` :

| Règle | Comportement |
|---|---|
| Dégâts joueur | **Tous** annulés, toute `DamageCause` (chute, feu, lave, noyade, suffocation, explosion, projectile, attaque d'entité — PvP inclus, faim, vide, etc. — mission « monde claims = réellement pacifique »/zone résidentielle safe) ; jamais un effet persistant posé sur le joueur (aucune résistance/invulnérabilité) — seul l'événement est annulé, le comportement normal revient **instantanément** dès la sortie de ce monde ; les mobs/animaux ne sont jamais concernés (peuvent toujours se blesser normalement) |
| Mobs hostiles | Spawn **toujours** empêché, quelle que soit la cause (naturel, spawner, renforts de zombie, invasion de patrouille, invocation par commande...) — pas seulement le spawn naturel (mission « monstres hostiles dans claims ») ; animaux/passifs jamais concernés |
| Construction hors de tout claim | Casse/pose de bloc refusée **en dehors** de tout claim (autorisée pour `rpgquest.build.world.claims` / `rpgquest.build.*` / `rpgquest.admin.world` — jamais une permission de Hub ou de Wild, et jamais un contournement de la protection d'un claim existant) — à l'intérieur d'un claim, la protection revient entièrement à `ClaimProtectionListener` |
| Voyage vers le Nether | Portail Nether activé **depuis** ce monde refusé (`claim.ClaimNetherTravelListener`, bascule `claims.block-nether-travel`) — construire un cadre en obsidienne reste autorisé, seul le voyage est bloqué ; un portail utilisé pour **revenir** vers `claims`, un portail dans un autre monde, ou un worldportal RPGQuest ne sont jamais concernés |

**Nettoyage des mobs hostiles déjà présents** (mission « monstres hostiles
dans claims ») : en plus d'empêcher tout nouveau spawn, `ClaimsWorldRulesListener`
supprime les mobs hostiles déjà présents — à chaque chargement de
monde/chunk (`WorldLoadEvent`/`ChunkLoadEvent`) et via une purge explicite
unique au démarrage du plugin (`purgeAlreadyLoadedWorld()`, appelée par le
bootstrap : les mondes se chargent avant les plugins, donc `WorldLoadEvent`
ne se déclenche jamais pour un monde déjà chargé à ce moment-là). Ne touche
jamais aux animaux/passifs.

**Volontairement absent** (contrairement au Hub, voir
[docs/ARCHITECTURE.md](ARCHITECTURE.md) section `hub`) : aucun verrouillage
du jour/nuit ni de la météo — le monde des claims garde un cycle et une
météo normaux.

## Réinitialisation admin (test/QA)

`/claim admin resettier1 <joueur>` (permission `rpgquest.claim.admin` ou
`rpgquest.admin.world`) — supprime **tous** les claims du
joueur ciblé et remet `CLAIM_TIER_1` à `false`, pour rejouer le scénario
Story → CLAIM_TIER_1 → Acte de propriété → claim depuis zéro sans toucher à
aucun autre joueur ni système. Voir `ClaimService#resetTierOneClaimForTesting`.

## Politique liée à la progression

`ClaimService#effectiveMaxWidth`/`effectiveMaxHeight`/`effectiveMaxClaims`
prennent un `Player` en paramètre — seam préparée en étape 17, remplie
depuis l'étape 19 (XP RPG) : `effectiveMaxClaims` accorde désormais +1
claim tous les 10 niveaux de la piste `GLOBAL`
(`progression.ProgressionService#hasLevel`/`level`), en plus de la limite
globale de `config.yml`. Largeur/hauteur restent aujourd'hui uniquement la
valeur de `config.yml`, identique pour tous les joueurs — voir
[docs/PROGRESSION.md](PROGRESSION.md) pour le détail. Aucun avantage payant
n'est prévu (mission étape 17, point 9) : ces limites ne dépendent d'aucune
monnaie ni d'aucun achat, uniquement du niveau RPG.

## Performance

Les claims sont indexés par monde (`ClaimService#claimsInWorld`, reconstruit
à chaque mutation) — même patron que les zones protégées/portails : aucun
balayage de tous les claims de tous les mondes à chaque événement protégé.

## Configuration (`config.yml` → `claims`)

```yaml
claims:
  max-width: 64
  max-height: 384
  max-claims-per-player: 3
  portal-buffer-blocks: 16
  world: claims
  block-nether-travel: true
```

`world` : nom du monde dédié au premier claim via l'Acte de propriété (voir
« Premier claim » et « Règles du monde des claims » ci-dessus). N'affecte
pas `/claim create` à la baguette, utilisable dans n'importe quel monde
autre que le Hub.

`block-nether-travel` : voir « Règles du monde des claims » ci-dessus —
`false` réautorise le voyage vers le Nether depuis `claims` instantanément,
sans changement de code (mission : « rôle futur du Nether pas encore décidé »).

Validée au démarrage et à `/rpgquest reload` ; une section absente vaut les
valeurs par défaut ci-dessus.

### Complétion automatique de `config.yml` (mission « cohérence du config.yml »)

Un `config.yml` historique (ex. VeryGames, ne contenant que `debug`/
`locale`/`database`/`resource-pack`) reçoit automatiquement, **au démarrage
uniquement** (jamais à `/rpgquest reload`), les sections/clés apparues
depuis avec leurs valeurs par défaut actuelles — `config.ConfigFileCompleter`,
appelé par `ConfigService#start()` juste après `saveDefaultConfig()` :

-   **jamais** une valeur déjà présente n'est modifiée, quelle qu'elle soit ;
-   une section entièrement absente (ex. `claims`) est copiée d'un bloc
    depuis le `config.yml` embarqué dans le jar (source de vérité) ; une
    section déjà partiellement présente est explorée récursivement pour
    n'ajouter que ce qui lui manque encore ;
-   les **listes connues** (aujourd'hui : `dialogue.allowed-commands`) sont
    fusionnées de façon additive — les entrées déjà présentes restent en
    tête, les entrées manquantes du gabarit sont ajoutées, jamais de
    doublon ;
-   `config-version` (marqueur informatif uniquement, jamais une notion de
    migration séquentielle comme `database.SchemaMigrator`) est posé/mis à
    jour ;
-   une sauvegarde `config.yml.bak` est créée **uniquement** si une vraie
    modification a lieu (jamais sur un fichier déjà à jour — idempotent,
    aucune écriture à chaque redémarrage une fois complet) ;
-   un message clair est journalisé quand une mise à jour automatique a
    réellement eu lieu.

`data.db` n'est jamais concerné par cette complétion — voir
[docs/deployment/VERYGAMES.md](deployment/VERYGAMES.md) pour le
comportement exact lors d'une mise à jour du seul JAR.

## Tests

Automatisés : `ClaimTest` (invariants du modèle, y compris la réservation),
`ClaimRepositoryTest` (persistance, propriétaire/non-propriétaire, cascade
de suppression des membres, réservation distincte du cuboïde actif),
`SchemaMigratorTest` (colonnes de réservation V15, rétro-remplissage des
claims déjà existants), `ConfigValidatorTest` (section `claims`, y compris
`world`), `ClaimServiceTest` (chevauchement claim/zone protégée/portail,
taille, nombre maximal, suppression, confiance/retrait de confiance, monde
absent, protection indépendante du statut en ligne du propriétaire,
**prérequis `CLAIM_TIER_1` sur le premier claim uniquement**, **chevauchement
de réservation**), `ClaimProtectionListenerTest` (frontière incluse, membre
autorisé/non autorisé, conteneurs, redstone configurable, animaux,
explosion externe, piston traversant la frontière, monde sans claim,
suppression), `ClaimsWorldRulesListenerTest` (PvP, mobs hostiles,
construction hors de tout claim, bypass admin, jour/nuit et météo jamais
verrouillés), `DeedClaimListenerTest` (refus sans `CLAIM_TIER_1`, refus
hors du monde des claims, aperçu sans création, confirmation créant un
claim 5×5 avec réservation 100×100 centrée sur la cible, consommation de
l'Acte, refus d'un second claim principal), `DialogueSessionEngineTest`
(condition `NO_MAIN_CLAIM`, visible sans claim puis masquée une fois le
claim créé ; condition `HAS_MAIN_CLAIM`, invisible sans claim puis visible
une fois le claim créé — strict opposé), `ClaimTeleportServiceTest`
(`NO_MAIN_CLAIM`/`WORLD_UNAVAILABLE`/`NO_SAFE_LOCATION`/`TELEPORTED`, centre
sûr, repli sur une autre colonne du claim quand le centre est obstrué,
destination toujours dans les bornes actives du claim), `RandomSafeLocationFinderTest`
(nouvelle variante `findAtColumn` : colonne sûre, lave, colonne vide, hors
bordure du monde), `ClaimsWorldRulesListenerTest` (spawn hostile bloqué
quelle que soit la cause — pas seulement `NATURAL` —, animal passif jamais
bloqué, purge explicite/au chargement de monde/au chargement de chunk,
jamais hors du monde des claims, **tout dégât joueur annulé** — chute, feu,
feu prolongé, lave, noyade, suffocation, faim, vide, explosion, projectile,
PvP —, jamais pour un non-joueur, comportement normal hors `claims`),
`ClaimServiceTest` (`mainClaimOf`), `ClaimBorderGeometryTest` (contour =
bornes actives, jamais la réservation, 4 côtés + 4 colonnes de coin),
`ClaimBorderEntryListenerTest` (entrée déclenche le rendu, déplacement
interne sans spam, sortie/nouvelle entrée réarme, visiteur jamais concerné,
simple changement de vue jamais une transition), `DeedClaimListenerTest`
(clic droit avec l'Acte une fois le claim posé → affiche les limites sans
tenter une création, Acte jamais consommé dans ce cas, **depuis l'intérieur
du claim affiche le périmètre jamais le faisceau, depuis l'extérieur affiche
le faisceau jamais le périmètre**), `ItemTravelServiceTest`
(clic droit avec l'objet enregistré démarre la canalisation, objet
non enregistré ignoré, mouvement/dégâts annulent proprement sans téléporter,
déconnexion nettoie l'état, objet jamais consommé quelle que soit l'issue,
**hors du monde requis par la définition aucune canalisation ne démarre,
une définition sans restriction fonctionne n'importe où, la progression
atteint 100% puis l'actionbar est vidée après un succès/une annulation**),
`SoulboundItemListenerTest` (remplace `ReturnStoneGuardListenerTest` — tout
objet soulbound enregistré, l'Acte de propriété inclus : drop annulé, retiré
des drops à la mort puis restauré tel quel à la réapparition sans jamais
dupliquer, objet quelconque jamais concerné),
`ClaimNetherTravelListenerTest` (portail Nether depuis `claims` refusé,
jamais depuis un autre monde y compris un retour, seule la cause
`NETHER_PORTAL` concernée, bascule `block-nether-travel=false` réautorise),
`ConfigFileCompleterTest` (clés manquantes ajoutées, valeurs personnalisées
jamais écrasées, listes fusionnées sans doublon, idempotent, sauvegarde
uniquement sur vraie modification, `config-version` posé),
`DialogueSessionEngineTest` (`LACKS_CUSTOM_ITEM` : visible sans l'objet,
masqué dès qu'un exemplaire est en poche).

`PENDING MANUAL VALIDATION` (client Minecraft réel + PNJ Jo créé via
Citizens requis) : deux joueurs voisins, coffres/portes/animaux/redstone
réels, TNT dedans/dehors, piston traversant la limite en jeu, redémarrage
complet du serveur (persistance réelle), scénario complet Story → Jo →
Acte de propriété → pose du claim dans le monde des claims en jeu, « Me
rendre sur ma propriété » après reconnexion/redémarrage, `/claim admin tp`
sur un joueur hors ligne, absence réelle de monstre hostile en jeu,
**affichage réel des particules de frontière** (jamais vu par un visiteur),
**chute d'une grande hauteur sans dégât réel**, **Pierre de retour en jeu**
(canalisation, annulation par mouvement, arrivée au Hub, **actionbar propre
— 100% puis disparition immédiate**, **refus hors du monde `claims`**,
**impossible à jeter, jamais perdue à la mort**), **portail Nether réel
refusé depuis `claims`**, **faisceau de retrouvaille du claim visible à
distance/de nuit** (voir le rapport Claude de cette étape pour la
procédure exacte).

Limitation connue (MockBukkit, sans rapport avec cette fonctionnalité) :
`Player#teleportAsync` n'est pas implémenté par cette version de MockBukkit
— les 3 tests `ClaimTeleportServiceTest` (et 2 tests `ItemTravelServiceTest`)
qui vérifient une téléportation réellement effectuée sont marqués `skipped`
(jamais `failed`) plutôt que vérifiés bout en bout ; même limitation déjà
présente pour `PortalServiceTest` (voir ce fichier), pas une régression
introduite ici. De même, `Player#spawnParticle` n'est pas simulé par
MockBukkit (no-op) : `ClaimBorderRenderer` est testé via une sous-classe
d'enregistrement (`RecordingBorderRenderer`, tests dédiés), jamais via une
assertion sur des particules réellement envoyées.
