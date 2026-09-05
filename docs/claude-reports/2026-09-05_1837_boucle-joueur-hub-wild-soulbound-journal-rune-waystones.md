# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-05
* Heure : 18:37
* Sujet : Prochain bloc MVP RPG — vraie boucle joueur *Hub → Journal de quêtes → Wild → Waystones / Rune de rappel → retour Hub* : système soulbound générique, item « Journal des quêtes », Rune de rappel (cooldown persistant), avertissement compact avant entrée dans le Wild, Waystones génératives dans le Wild, config `travel.*`, migrations V16/V17, commandes admin `/rpgadmin waystone`.
* Statut : DONE — `./gradlew clean build` **BUILD SUCCESSFUL** (911 tests, 0 échec, 17 `skipped` préexistants) ; re-confirmé après ajout de la complétion de tab pour `/rpgadmin waystone`.
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `83d4c4f` (HEAD) — ce travail reste dans l'arbre de travail (non commité), **par-dessus** les modifications non commitées de la session précédente (`2026-09-05_1729`, corrections UX claims), qui n'avaient pas encore été commitées non plus.
* Début de la tâche : 2026-09-05 18:01:14
* Fin de la tâche : 2026-09-05 18:52:49
* Durée totale : 00:51:35

## Demande

Créer une vraie boucle joueur, **sans qu'aucun gameplay normal ne nécessite de commande**, en
conservant intacts les systèmes déjà validés (claim 5×5, Jo, Acte, Pierre de retour, protections,
monde `claims` safe) :

1. **Acte de propriété soulbound** — jamais perdable (drop interdit, ne tombe pas à la mort,
   restauration sans duplication, Jo le redonne s'il manque) ; conserver contour 5×5 dans le claim /
   marqueur longue distance hors du claim, en rendant ce marqueur réellement visible de loin
   (proche d'un beam/beacon sans bloc permanent, fallback propre).
2. **Système soulbound générique** — plusieurs objets permanents (Acte, Pierre de retour, Journal,
   Rune) : généraliser l'anti-perte plutôt que dupliquer les listeners ; identification via
   custom item/PDC, jamais matériau seul.
3. **Journal des quêtes** — custom item permanent, donné/redonné gratuitement par le Libraire, clic
   droit → interface compacte (stories ACTIVE, progression Story, quête courante, autres quêtes
   ACTIVE, objectifs + progression, READY_TO_TURN_IN) ; ne jamais afficher les stories/quêtes
   secrètes non découvertes ; aucune commande `/quest` nécessaire.
4. **Rune de rappel** — soulbound, donnée au nouveau joueur (ou reprise gratuitement auprès du
   Guide), fonctionne uniquement dans `wild`, retour vers `SpawnService`/Hub, canalisation
   configurable (défaut 10 s), mouvement/dégâts → annulation, cooldown configurable (défaut 30 min)
   persistant, non consommée.
5. **Avertissement avant entrée dans le Wild** — quand un joueur sans Rune tente le portail
   Hub → Wild : avertissement compact (pas plein écran), dialogue léger / chat cliquable
   [Continuer]/[Annuler] ; avec Rune, aucun avertissement ; ne pas casser les autres WorldPortals ;
   ne pas spammer.
6. **Waystones Wild** — système générique : génération paresseuse (au chargement de chunks), monde
   configurable, grandes cellules configurables (~1000×1000), max 1 par cellule, décision
   déterministe seed + cellule, densité/chance configurable, distance minimale, surface sûre, pas de
   duplication au reload/restart, persistance ; petite structure identifiable avec abstraction
   `.schem`-compatible (aucun gameplay dépendant du matériau) ; découverte globale physiquement mais
   individuelle par joueur (premier clic → « Pierre de voyage découverte : <nom> », persistée
   player UUID + waystoneId + discoveredAt) ; utilisation → choix compact [Retourner au Hub] /
   [Annuler], canalisation ~3 s, mouvement/dégâts → annulation, pas de coût MVP ; persistance prête
   pour un futur « Hub → Waystones découvertes » sans l'implémenter.
7. **Admin/debug** — uniquement `list waystones`, `here`, `tp <id>`, `generatehere`,
   `reset discoveries <player>`. Pas de commandes joueur.
8. **Config** — via `ConfigFileCompleter` : `wild world`, `waystone cell-size`, `density/chance`,
   `minimum-spacing`, `safe attempts`, `waystone channel seconds`, `rune channel seconds`,
   `rune cooldown seconds`. Conserver les valeurs existantes.
9. **Persistence** — migrations additives/idempotentes : Waystones générées, découvertes joueur,
   cooldown Rune. Ne jamais remplacer `data.db`.
10. **Tests** — liste fournie (soulbound, journal, story secrète, Rune, avertissement portail,
    Waystones déterministes/idempotentes/spacing/safe/persistence/découverte, retour Hub, Acte
    soulbound + localisation longue distance) puis `./gradlew clean build`.
11. **Doc + rapport** — maintenir uniquement la doc concernée + ce rapport permanent.

Contraintes : efficience (docs + derniers rapports, inspecter uniquement les fichiers nécessaires,
pas d'audit global, pas de refactoring esthétique, pas de fonctionnalités hors scope). **STOP après
ce bloc** — ne pas commencer économie, skills, claim > 5×5, trust, 2ᵉ claim, Nether RPG, nouvelles
grandes Stories.

## Analyse

Code lu avant modification (uniquement le nécessaire) : `travel.ItemTravelService` /
`ItemTravelListener` / `model.ItemTravelDefinition` / `ReturnStoneGuardListener` ; `spawn.SpawnService` ;
`item.YamlCustomItemRegistry` + resources `items/*.yml` ; `bootstrap.RPGQuestBootstrap` ;
`config.ConfigService` / `ConfigValidator` / `PluginConfig` / `ClaimConfig` / `JournalConfig` /
`ConfigFileCompleter` + `resources/config.yml` ; `database.SchemaMigrator` / `DatabaseManager` /
`PortalCooldownRepository` / `StoryProgressRepository` ; `story.StoryService` + `model.StoryDefinition` /
`StoryDefinitionParser` ; `quest.model.QuestDefinition` / `QuestState` / `QuestDefinitionParser` /
`quest.progress.QuestProgressEngine` (surface publique) ; `ui.QuestJournalService` + `QuestJournalUi`
(journal GUI `/quests` existant) ; `travel.WorldPortalTeleportListener` / `WorldPortalRegistry` +
`model.WorldPortalDefinition` / `DestinationStrategy` ; `travel.RandomSafeLocationFinder` ;
`claim.ClaimBorderRenderer` / `DeedClaimListener` ; `player.PlayerConnectionListener` /
`PlayerProfileService` ; `dialogues/jo.yml` / `guide.yml` / `libraire.yml` ; `admin.RpgAdminCommand`
(structure de dispatch) ; `build.gradle.kts` + tests existants (`ItemTravelServiceTest`,
`ReturnStoneGuardListenerTest`, `QuestJournalServiceTest`, `StoryServiceTest`) pour le patron
MockBukkit.

Constats :

-   Un « Journal de quêtes » GUI existait déjà (`ui.QuestJournalService`, commande `/quests`), mais
    sans notion de Story ni de custom item déclencheur. La demande est un **objet** avec un résumé
    **compact** incluant les Stories → nouveau service dédié, pas une extension du GUI.
-   Aucun champ `secret` sur `QuestDefinition` / `StoryDefinition`.
-   `ItemTravelDefinition` n'avait aucune notion de cooldown ; `ItemTravelService` n'avait aucun
    écouteur de connexion (nécessaire pour recharger un cooldown persisté).
-   `ReturnStoneGuardListener` était un écouteur dédié à la seule Pierre de retour — exactement le
    cas que la demande veut généraliser.
-   `WorldPortalTeleportListener` téléporte immédiatement à l'entrée d'un portail, sans point
    d'extension pour intercaler une confirmation.
-   `RandomSafeLocationFinder#findAtColumn` est un utilitaire réutilisable de recherche de surface
    sûre sur une colonne connue — parfait pour les Waystones.

## Travail effectué

### 1 & 2 — Système soulbound générique

-   `item.RpgItemKeys` : source unique des id des 4 objets permanents.
-   `item.SoulboundItemService` (`PluginService`) : registre `Set<NamespacedKey>` + `isSoulbound`
    (identification PDC via `YamlCustomItemRegistry#identify`, jamais matériau).
-   `item.SoulboundItemListener` : un **seul** écouteur pour tous les objets enregistrés — drop
    volontaire annulé (`PlayerDropItemEvent`), retrait des drops à la mort (`PlayerDeathEvent`) puis
    restauration **du stack cloné tel quel** à la réapparition (`PlayerRespawnEvent`) ; jamais de
    duplication (seuls les exemplaires retirés de *cette* mort sont rendus).
-   `travel.ReturnStoneGuardListener` **supprimé** (remplacé) ; bootstrap enregistre
    `SoulboundItemService` avec les 4 id (Acte, Pierre de retour, Journal, Rune).
-   `claim.ClaimBorderRenderer#showBeacon` : colonne désormais **dense** (pas de 1 bloc, période
    5 ticks, ~13 s) superposant `Particle.DUST` teintée + `Particle.END_ROD` lumineuse — au plus
    proche d'un faisceau de balise sans bloc permanent. `DeedClaimListener` inchangé (le choix
    périmètre/faisceau selon la position réelle du joueur existait déjà).

### 3 — Journal des quêtes

-   Resource `items/journal_quetes.yml` (`BOOK`, `QUEST_ITEM`, non empilable) ajoutée à
    `YamlCustomItemRegistry.BUNDLED_EXAMPLES`.
-   `quest.model.QuestDefinition` + `story.model.StoryDefinition` : nouveau champ `boolean secret`
    (défaut `false`), parsé (`secret: true`) dans les deux parsers (helper `parseBoolean` factorisé
    côté quête).
-   `ui.QuestJournalBookService` (+ `QuestJournalBookListener`) : clic droit avec le Journal →
    résumé **chat** compact (pas plein écran) — Stories `ACTIVE` avec `pos/total` et quête courante,
    autres quêtes `ACTIVE`/`READY_TO_TURN_IN` avec objectifs + progression (`activeStepView`),
    `[Prête à remettre]`. Filtre : une story/quête `secret` non découverte (`NOT_STARTED`) n'apparaît
    jamais. `buildDigest(UUID, DigestData)` est pur et directement testable.
-   `dialogues/libraire.yml` : choix « Obtenir un journal des quêtes » (garde `LACKS_CUSTOM_ITEM`,
    `RUN_SAFE_COMMAND customitem give`).

### 4 — Rune de rappel

-   Resource `items/rune_rappel.yml` (`AMETHYST_SHARD`, `QUEST_ITEM`).
-   `config.TravelConfig` (record) + sous-records `RuneConfig` / `WaystoneConfig` ; ajouté à
    `PluginConfig` ; `ConfigValidator#validateTravel` ; nouvelles clés dans `resources/config.yml`
    sous `travel:` (valeurs existantes conservées, `ConfigFileCompleter` les ajoute automatiquement
    à un `config.yml` déjà déployé).
-   `ItemTravelDefinition` : nouveau champ `int cooldownSeconds` (0 = aucun) + `hasCooldown()` ;
    constructeurs de confort conservés pour les appelants existants.
-   `database.ItemTravelCooldownRepository` (table `item_travel_cooldowns`, **migration V16**) —
    même forme que `portal_cooldowns`.
-   `ItemTravelService` : cache mémoire des cooldowns par joueur, `handleJoin` (chargement depuis la
    base — `ItemTravelListener#onJoin` ajouté), refus bref si cooldown actif avant la canalisation,
    application + persistance du cooldown après un voyage réussi.
-   `player.StarterKitListener` : remet la Rune une seule fois à chaque joueur à sa 1ʳᵉ connexion
    (marqueur persistant `RUNE_RAPPEL_GRANTED` dans `player_variables`, jamais de duplication).
-   `dialogues/guide.yml` : choix « Récupérer une rune de rappel » (garde `LACKS_CUSTOM_ITEM`).
-   Bootstrap : enregistrement de la Rune (`requiredWorld = travel.wild-world`, `channelSeconds` /
    `cooldownSeconds` lus depuis `travel.rune` **au démarrage**).

### 5 — Avertissement avant entrée dans le Wild

-   `travel.WorldPortalEntryGuard` (interface fonctionnelle) + `travel.PortalTeleporter` (capacité
    minimale « téléporter via un portail sans contrôle »).
-   `WorldPortalTeleportListener implements PortalTeleporter` : champ `entryGuard` optionnel + setter
    (cycle de construction évité), méthode `teleportNow`, consultation du garde avant la
    téléportation dans `onMove`.
-   `travel.WildEntryWarningService implements WorldPortalEntryGuard` : si destination ==
    `travel.wild-world` **et** joueur sans Rune **et** pas de laissez-passer → message compact
    cliquable dans le chat (« Vous partez sans moyen de rappel… ») avec `[Continuer]` (callback
    Adventure → laissez-passer bref à usage unique + `teleportNow`) et `[Annuler]` (reste au Hub).
    Anti-spam 4 s/joueur ; portails hors Wild jamais concernés ; joueur avec Rune jamais averti.

### 6 & 7 — Waystones

-   Package `com.lodygames.rpgquest.waystone` :
    -   `model.Waystone` (record : id, monde, x/y/z du bloc interactif, cellX/cellZ, nom, date).
    -   `WaystoneCellPlanner` — pur, sans Bukkit : `planCell(worldSeed, cellX, cellZ, cfg)` mélange
        déterministe → `chance` puis position de bloc dans la cellule (marge de bord 24) + nom
        lisible ; `cellOf(blockCoord, cellSize)`.
    -   `WaystoneStructurePlacer` (interface, `.schem`-compatible) + `SimpleWaystoneStructurePlacer`
        (socle 3×3 pierre polie sombre, 4 montants éclairés, `LODESTONE` central — aucun gameplay
        ne dépend du matériau).
    -   `database.WaystoneRepository` (tables `waystones` + `waystone_discoveries`, **migration
        V17**) : `loadAll`, `insertIfAbsent` (`INSERT OR IGNORE`, index unique `(world, cell_x,
        cell_z)`), `discoveriesFor`, `recordDiscovery`, `deleteDiscoveries`.
    -   `WaystoneService` (`PluginService`) + `WaystoneListener` : au `ChunkLoadEvent` du monde
        configuré, les cellules touchées sont évaluées **une fois** (cache `resolvedCells`) ; si le
        candidat tombe dans le chunk, passe l'espacement (`travel.waystone.minimum-spacing`) et
        trouve une surface sûre (`RandomSafeLocationFinder#findAtColumn` + petite spirale bornée par
        `safe-attempts`), la structure est posée et persistée. Clic droit sur le bloc sommital :
        1ᵉʳ clic → découverte persistée + « Pierre de voyage découverte : <nom> » ; clic suivant →
        choix chat cliquable `[Retourner au Hub]` (canalisation `travel.waystone.channel-seconds`,
        annulée sur mouvement/dégâts → `SpawnService#resolve`) / `[Annuler]`. Aucun coût.
    -   API publique pour l'admin : `all`, `byId`, `waystoneInCellOf`, `generateAt` (force, ignore la
        probabilité, respecte l'unicité par cellule + surface sûre), `resetDiscoveries`.
-   `admin.RpgAdminCommand` : nouvelle branche `waystone` →
    `list | here | tp <id> | generatehere | reset discoveries <joueur>` (15ᵉ paramètre de
    constructeur `WaystoneService`). Aucune commande joueur ajoutée.

## Fichiers créés

-   `src/main/java/com/lodygames/rpgquest/item/RpgItemKeys.java`
-   `src/main/java/com/lodygames/rpgquest/item/SoulboundItemService.java`
-   `src/main/java/com/lodygames/rpgquest/item/SoulboundItemListener.java`
-   `src/main/java/com/lodygames/rpgquest/ui/QuestJournalBookService.java`
-   `src/main/java/com/lodygames/rpgquest/ui/QuestJournalBookListener.java`
-   `src/main/java/com/lodygames/rpgquest/config/TravelConfig.java`
-   `src/main/java/com/lodygames/rpgquest/database/ItemTravelCooldownRepository.java`
-   `src/main/java/com/lodygames/rpgquest/database/WaystoneRepository.java`
-   `src/main/java/com/lodygames/rpgquest/player/StarterKitListener.java`
-   `src/main/java/com/lodygames/rpgquest/travel/WorldPortalEntryGuard.java`
-   `src/main/java/com/lodygames/rpgquest/travel/PortalTeleporter.java`
-   `src/main/java/com/lodygames/rpgquest/travel/WildEntryWarningService.java`
-   `src/main/java/com/lodygames/rpgquest/waystone/model/Waystone.java`
-   `src/main/java/com/lodygames/rpgquest/waystone/WaystoneCellPlanner.java`
-   `src/main/java/com/lodygames/rpgquest/waystone/WaystoneStructurePlacer.java`
-   `src/main/java/com/lodygames/rpgquest/waystone/SimpleWaystoneStructurePlacer.java`
-   `src/main/java/com/lodygames/rpgquest/waystone/WaystoneService.java`
-   `src/main/java/com/lodygames/rpgquest/waystone/WaystoneListener.java`
-   `src/main/resources/items/journal_quetes.yml`
-   `src/main/resources/items/rune_rappel.yml`
-   `src/test/java/com/lodygames/rpgquest/item/SoulboundItemListenerTest.java`
-   `src/test/java/com/lodygames/rpgquest/ui/QuestJournalBookServiceTest.java`
-   `src/test/java/com/lodygames/rpgquest/travel/WildEntryWarningServiceTest.java`
-   `src/test/java/com/lodygames/rpgquest/waystone/WaystoneCellPlannerTest.java`
-   `src/test/java/com/lodygames/rpgquest/waystone/WaystoneServiceTest.java`
-   `docs/claude-reports/2026-09-05_1837_boucle-joueur-hub-wild-soulbound-journal-rune-waystones.md` (ce rapport)

## Fichiers supprimés

-   `src/main/java/com/lodygames/rpgquest/travel/ReturnStoneGuardListener.java` (remplacé par le
    système soulbound générique)
-   `src/test/java/com/lodygames/rpgquest/travel/ReturnStoneGuardListenerTest.java` (remplacé par
    `item.SoulboundItemListenerTest`)

## Fichiers modifiés

-   `bootstrap/RPGQuestBootstrap.java` — câblage soulbound + kit de départ + Rune + garde
    d'entrée Wild + Waystones + `RpgAdminCommand`.
-   `item/YamlCustomItemRegistry.java` — 2 exemples embarqués supplémentaires.
-   `quest/model/QuestDefinition.java`, `quest/QuestDefinitionParser.java`,
    `story/model/StoryDefinition.java`, `story/StoryDefinitionParser.java` — champ `secret`.
-   `config/PluginConfig.java`, `config/ConfigValidator.java`, `src/main/resources/config.yml` —
    section `travel` (wild-world, rune, waystone).
-   `database/SchemaMigrator.java` — `CURRENT_VERSION` 15 → 17, `applyV16` / `applyV17`.
-   `travel/model/ItemTravelDefinition.java` — champ `cooldownSeconds`.
-   `travel/ItemTravelService.java`, `travel/ItemTravelListener.java` — cooldown persistant + join.
-   `travel/WorldPortalTeleportListener.java` — `entryGuard` optionnel, `teleportNow`,
    `implements PortalTeleporter`.
-   `claim/ClaimBorderRenderer.java` — faisceau dense DUST + END_ROD.
-   `admin/RpgAdminCommand.java` — sous-commande `waystone`.
-   `src/main/resources/dialogues/guide.yml`, `dialogues/libraire.yml` — dons Rune / Journal.
-   `src/test/java/com/lodygames/rpgquest/travel/ItemTravelServiceTest.java` — `DatabaseManager` +
    `ItemTravelCooldownRepository` dans le harness + 2 tests cooldown/monde de la Rune.
-   `src/test/java/com/lodygames/rpgquest/database/SchemaMigratorTest.java` — version 15 → 17,
    +3 tests.
-   `src/test/java/com/lodygames/rpgquest/item/YamlCustomItemRegistryTest.java` — 6 → 8 exemples.
-   `src/test/java/com/lodygames/rpgquest/economy/market/MarketServiceTest.java` — isolation du
    don de Rune de départ dans `addPlayer()`.
-   `admin/RpgAdminCommand.java` — complétion de tab pour `/rpgadmin waystone` (déjà comptée
    ci-dessus).
-   `docs/TRAVEL.md`, `docs/CLAIMS.md`, `docs/current_state.md`, `docs/claude-reports/README.md`.

*(Note : `docs/CLAIMS.md`, `docs/TRAVEL.md`, `pierre_retour.yml`, `DeedClaimListenerTest.java` et
`ItemTravelServiceTest.java` apparaissent aussi comme modifiés du fait des changements non commités
de la session précédente `2026-09-05_1729`, présents dans l'arbre avant cette session.)*

## Base de données / migrations

-   **V16** — `item_travel_cooldowns (player_uuid, item_id, expires_at, PK(player_uuid, item_id),
    FK player_profiles ON DELETE CASCADE)`. `CREATE TABLE IF NOT EXISTS` (idempotent).
-   **V17** — `waystones (id PK, world, x, y, z, cell_x, cell_z, name, created_at)` + index unique
    `(world, cell_x, cell_z)` ; `waystone_discoveries (player_uuid, waystone_id, discovered_at,
    PK(player_uuid, waystone_id), FK player_profiles ON DELETE CASCADE)`. `CREATE TABLE/INDEX IF NOT
    EXISTS` (idempotent). Additif uniquement, aucune table existante modifiée, `data.db` jamais
    remplacé.

## Configuration / données

Nouvelle section `config.yml` :

```yaml
travel:
  wild-world: wild
  rune:
    channel-seconds: 10
    cooldown-seconds: 1800
  waystone:
    cell-size: 1000
    chance: 0.6
    minimum-spacing: 300
    safe-attempts: 16
    channel-seconds: 3
  random-safe-arrival: { ... }   # inchangé
```

`ConfigFileCompleter` ajoute automatiquement ces clés à un `config.yml` déjà présent sur disque au
prochain démarrage, sans toucher aux valeurs existantes.

## Tests automatiques

`./gradlew clean build` : **BUILD SUCCESSFUL** (911 tests, 0 échec, 0 erreur, 17 `skipped` —
limitations MockBukkit préexistantes). Trois classes de tests existantes ont dû être adaptées aux
changements de cette étape (voir aussi « Fichiers modifiés ») :

-   `SchemaMigratorTest` : version de schéma attendue 15 → 17 (5 assertions) + 3 nouveaux tests
    (tables V16/V17 créées, ré-exécution V16/V17 idempotente).
-   `YamlCustomItemRegistryTest` : nombre d'exemples embarqués 6 → 8 (les 2 nouveaux items).
-   `MarketServiceTest` : `addPlayer()` laisse désormais le `StarterKitListener` (chargé par
    `MockBukkit.load`) poser sa Rune de rappel **avant** que le test ne configure la main du joueur
    — sinon le don asynchrone atterrissait dans le slot vidé par la vente et faussait l'assertion
    « main vide après vente ». Aucun affaiblissement de couverture, simple isolation.

Nouveaux/adaptés :

-   `SoulboundItemListenerTest` (5) : tout objet soulbound intombable au drop / à la mort, restauré
    tel quel sans duplication, objet quelconque jamais concerné, réapparition sans mort ne donne
    rien.
-   `QuestJournalBookServiceTest` (2) : quête active + progression d'objectif visibles ; story
    secrète non découverte jamais affichée.
-   `WildEntryWarningServiceTest` (6) : bloqué + averti (2 boutons) sans Rune ; jamais averti avec
    Rune ; portail hors Wild non concerné ; anti-spam ; `[Continuer]` = laissez-passer à usage
    unique puis téléportation ; `[Annuler]` = reste au Hub.
-   `WaystoneCellPlannerTest` (4) : décision déterministe et idempotente (seed + cellule), cellules
    indépendantes, `chance` 0/1, candidat toujours dans sa cellule.
-   `WaystoneServiceTest` (7) : génération non dupliquée / unicité par cellule (1 ligne persistée),
    rechargement au démarrage d'un service neuf, espacement minimal, surface sûre solide, découverte
    individuelle par joueur, `reset discoveries`, canalisation de retour annulée par un déplacement.
-   `ItemTravelServiceTest` (+2) : voyage réussi de la Rune → cooldown persisté qui bloque l'usage
    suivant ; Rune refusée hors de son monde requis.

## Tests manuels à effectuer

Sur VeryGames (client Minecraft réel) :

1.  **Soulbound** — tenter de jeter (Q) l'Acte, la Pierre de retour, le Journal, la Rune : refusé.
    Mourir avec ces objets : aucun ne tombe, tous présents après réapparition, en un seul
    exemplaire.
2.  **Journal** — obtenir le Journal auprès du Libraire ; clic droit → résumé compact dans le chat
    avec Story active + quête courante + objectifs + progression ; aucune story/quête secrète non
    découverte visible.
3.  **Rune** — nouveau joueur : reçoit une Rune à la connexion. Clic droit dans `wild` : canalise
    ~10 s, annulée par mouvement/dégâts, arrive au spawn du Hub. Second usage immédiat : refusé
    (cooldown ~30 min). Se reconnecter : le cooldown est toujours actif. Clic droit hors de `wild` :
    refus bref, aucune canalisation.
4.  **Avertissement Wild** — sans Rune, entrer dans le portail Hub → wild : message compact
    cliquable [Continuer]/[Annuler]. [Annuler] → reste au Hub. [Continuer] → passe. Avec une Rune :
    aucun avertissement. Les autres WorldPortals fonctionnent normalement.
5.  **Waystones** — explorer le Wild jusqu'à en croiser une (structure identifiable). 1ᵉʳ clic
    droit → « Pierre de voyage découverte : <nom> ». 2ᵉ clic → choix [Retourner au Hub] / [Annuler] ;
    canalisation ~3 s, annulée par mouvement/dégâts, arrivée au Hub. Un autre joueur sur la même
    Waystone doit la « redécouvrir » lui-même. Redémarrer le serveur : la Waystone est toujours là,
    au même endroit, aucune nouvelle Waystone dupliquée à côté.
6.  **Admin** — `/rpgadmin waystone list | here | tp <id> | generatehere | reset discoveries <joueur>`.
7.  **Acte à distance** — dans le monde des claims, hors de son claim : faisceau désormais dense
    (DUST + END_ROD), visible de loin/de nuit.

## Résultat attendu

Les 6 blocs fonctionnels de la boucle joueur sont implémentés et couverts par des tests
automatiques pour tout ce qui ne dépend pas du client / de la génération de terrain réelle. Le
reste (rendu visuel, callbacks de chat cliquables, génération de Waystones à l'exploration de
chunks avec du vrai terrain) reste `PENDING MANUAL VALIDATION`, comme le reste des modules
`travel` / `claim`.

## Reset / retour à l'état initial

`git checkout -- <fichier>` / suppression des fichiers créés listés ci-dessus. Aucune donnée SQLite
existante n'est modifiée par ce travail (migrations purement additives) — pour revenir en arrière
côté base, il suffit de ne pas déployer le nouveau JAR ; les tables V16/V17 restées vides sont sans
effet.

## Déploiement VeryGames

### À transférer
-   Le nouveau JAR (`build/libs/`) après `./gradlew clean build`.
-   `plugins/RPGQuest/config.yml` : **ne pas écraser** — `ConfigFileCompleter` ajoutera la section
    `travel` au prochain démarrage. Vérifier après coup que `travel.wild-world` correspond bien au
    nom réel du monde d'exploration (défaut `wild`).
-   `plugins/RPGQuest/dialogues/guide.yml` et `libraire.yml` : régénérés uniquement s'ils sont
    **absents** (`YamlDialogueEngine`/`ensureExamplesExist`). Sur un serveur où ils existent déjà,
    éditer à la main pour ajouter les nouvelles options « Récupérer une rune de rappel » / « Obtenir
    un journal des quêtes », ou les supprimer avant redémarrage pour régénération depuis le jar.
-   `plugins/RPGQuest/items/journal_quetes.yml` et `rune_rappel.yml` : idem — régénérés seulement si
    absents ; les copier depuis le jar si le dossier `items/` existe déjà.

### Ne PAS transférer/altérer
`data.db` (migrations additives appliquées automatiquement au démarrage) ; `config.yml` existant
(complété automatiquement).

### Redémarrage requis
Oui (nouveau code Java + migrations V16/V17).

### Migration automatique
Oui : `SchemaMigrator` applique V16 puis V17 au premier démarrage (idempotent, `IF NOT EXISTS`).

## Rollback
Redéployer le JAR précédent ; les tables `item_travel_cooldowns` / `waystones` /
`waystone_discoveries` restent en base mais inertes.

## Logs / diagnostic
Aucune instrumentation temporaire ajoutée. `WaystoneService` journalise en `INFO` le nombre de
Waystones chargées au démarrage et chaque Waystone générée (monde + coordonnées). Les messages de
refus (cooldown, hors monde requis, drop soulbound) sont envoyés au joueur, jamais journalisés.

## Documentation mise à jour

-   `docs/TRAVEL.md` : `ItemTravelDefinition` (cooldown) ; remplacement du paragraphe
    `ReturnStoneGuardListener` par « Système soulbound générique » ; nouvelles sections « Rune de
    rappel », « Avertissement avant entrée dans le Wild », « Waystones » (génération, structure,
    découverte/utilisation, admin) ; section Tests complétée ; `PENDING MANUAL VALIDATION` complété.
-   `docs/CLAIMS.md` : faisceau dense (DUST + END_ROD), note « Acte soulbound » renvoyant vers
    `docs/TRAVEL.md` ; référence de test `ReturnStoneGuardListenerTest` → `SoulboundItemListenerTest`.
-   `docs/current_state.md` : nouvelle puce « Boucle joueur Hub ↔ Wild » ; version de schéma 14 → 17.
-   `docs/claude-reports/README.md` : ligne d'index ajoutée.
-   Javadoc de classe pour chaque nouveau service.

## Limitations / travail restant

-   `travel.rune.channel-seconds` / `cooldown-seconds` et `travel.waystone.*` sont lus **au
    démarrage** du plugin (comme le `3` littéral de la Pierre de retour) — un changement à chaud via
    `/rpgquest reload` ne les prend pas en compte ; redémarrage requis pour ces valeurs. Le monde
    requis (`travel.wild-world`) reste, lui, un fournisseur relu à chaque usage.
-   Génération de Waystones à l'exploration de chunks : **non couverte automatiquement** (MockBukkit
    ne simule pas le terrain). Seules la décision déterministe (`WaystoneCellPlanner`) et la pose
    forcée (`generatehere`) sont testées. À valider en jeu.
-   Le résumé du Journal est du **chat** (compact, non plein écran) et non un panneau graphique —
    choix délibéré (consultable en déplacement, aucune commande, testable). Un GUI dédié pourrait
    venir plus tard sans changer le modèle.
-   Callbacks de chat cliquables (`ClickEvent.callback`) : non exécutables sous MockBukkit ; les
    branches sous-jacentes (`onContinue`/`onCancel`, `beginReturnChannel`) sont testées directement.
-   Les Waystones ne sont pas régénérées si leur structure physique est cassée par un joueur (la
    base reste la source de vérité — l'entrée persiste, le bloc `LODESTONE` peut être reposé
    manuellement) ; protection de la structure hors périmètre MVP.
-   Report non concerné : 4 anciens rapports (`2026-08-21_2009`, `2026-08-21_2135`,
    `2026-08-23_1234`, `2026-08-23_1256`) restent marqués supprimés dans l'arbre de travail (état
    hérité de la session précédente) alors que `README.md` les référence toujours — non touché ici.

## Prochaine étape suggérée

Validation manuelle en jeu sur VeryGames des 7 scénarios ci-dessus, puis commit de ce lot
**avec** les corrections UX non commitées de la session `2026-09-05_1729` (elles font partie du même
arbre de travail).
