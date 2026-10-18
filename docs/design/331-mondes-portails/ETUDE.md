# Mondes (#331) et portails (#228) dans PlugAdmin — audit du lot 1

Demande du propriétaire du 18/10/2026 (texte complet transmis en session, non publié sur GitHub).
Audit **en lecture seule** : code de `chore/257-ci-github-actions` à `06bb715`, fichiers de DEV relus par
FTP (aucune écriture), JAR `multiverse-core-5.7.3.jar` de DEV et `paper-api-1.21.11-R0.1-SNAPSHOT`
inspectés avec `javap`. Aucun monde, portail, emplacement ni bâtiment créé, modifié ou supprimé ; aucun
joueur téléporté ; aucune génération IA.

Maquettes : `mondes.html`, `monde-fiche.html`, `monde-creer.html`, `portails.html`, `portail-fiche.html`,
`portail-creer.html` (statiques, styles réels de PlugAdmin), captures 1280/390 px dans
`docs/claude-reports/assets/331-mondes-portails/`.

---

## 1. Ce qui existe réellement

### 1.1 Mondes

| Élément | Où | Constat |
|---|---|---|
| Création / chargement | `world/WorldService.java` | `new WorldCreator(name).environment(NORMAL).createWorld()` uniquement. Pas de Nether/End, pas de plat, pas de graine, pas de structures, pas de déchargement. Noms gérés mémorisés dans `plugins/RPGQuest/worlds.yml` et rechargés à chaque démarrage. |
| Commandes | `admin/RpgAdminCommand.java` (`/rpgadmin world create\|tp\|list`) | Commandes admin seulement. |
| Rôles des mondes | `hub.world`, `travel.wild-world`, `claims.world`, `wild.worlds`, `encounters.worlds` (`config.yml`) | Pas de notion « rôle de monde » commune : chaque rôle est une clé séparée, comparée par **nom**. |
| Mondes vus par PlugAdmin | heartbeat `worlds: {hub, claims, wild}` (`HeartbeatPayload`, `BukkitHealthSource.essentialWorlds()`) ; `mob.catalogs` renvoie la liste des mondes chargés | **Aucune** action agent `world.*`, **aucune** page Mondes, aucune permission `WORLD_*`. Diagnostic `WORLD_NOT_LOADED` pour les 3 mondes essentiels. |
| Règles par monde | `HubWorldRulesService` (gamerules `ADVANCE_TIME`, `ADVANCE_WEATHER`), `ClaimsWorldRulesListener`, `EncounterWorldPolicy`, `SpecialMobDefinition.allowedWorlds` | Règles codées par rôle. Aucun réglage de difficulté, bordure, PvP par monde. |
| Politique waypoints (#136/#137) | `WaypointService` + `travel.waypoint.hub-enabled` | **Codée en dur** : wild + Hub si `hub-enabled`, claims exclu. Pas de liste allow/deny par monde. |

**DEV, relu le 18/10 :**
- Multiverse-Core **5.7.3** installé, `worlds.yml` Multiverse : `claims`, `world_hub`, le monde principal
  (clé `minecraft:overworld`) et ses `the_nether` / `the_end`. Tous `generator: ''`, difficulté `easy`,
  PvP `true`, `auto-load: true`.
- `plugins/RPGQuest/worlds.yml` : `managed-worlds: [wild]` → **`wild` est créé et rechargé par RPGQuest,
  pas par Multiverse**. Deux gestionnaires de mondes coexistent : c'est le point de conception n° 1.

### 1.2 Portails

Il existe **deux moteurs** de portails :

**A. Portails entre mondes « world-portals »** — ceux de DEV.
- Modèle `travel/model/WorldPortalDefinition` (id, monde, cuboïde min/max, `destination-world`, `enabled`,
  `destination-strategy`) ; un YAML par portail dans `plugins/RPGQuest/world-portals/`.
- Registre générique `travel/WorldPortalRegistry` (N portails, index par monde, `create` contrôle le
  chevauchement, `setEnabled`, `delete`). `reload()` ne contrôle ni doublon ni chevauchement (limite connue).
- Déclenchement `travel/WorldPortalTeleportListener` (PlayerMoveEvent au changement de bloc, entrée
  dehors→dedans seulement, grâce de 40 ticks après arrivée), puis chaîne de gardes
  `CompositeWorldPortalEntryGuard(claimWorldAccessGuard, wildEntryWarningService)`.
- Stratégies `DestinationStrategy` : `WORLD_SPAWN`, `RANDOM_SAFE`, `CLAIM_HOME`. **Pas de point fixe.**
- Téléportation **synchrone** (`player.teleport`) ; recherche RANDOM_SAFE avec chargement de chunks
  **synchrone** (`RandomSafeLocationFinder`, rayon 500–5000, 20 essais, repli sur le spawn).
- Messages « Téléportation réussie / échouée » codés en dur. Pas de condition, pas de cooldown.
- Création par `/rpgadmin worldportal create <id> <monde> [world_spawn|random_safe]` depuis la sélection
  pos1/pos2. DEV : `hub_to_wild` (world_hub 737,66,−673 → 740,69,−673, RANDOM_SAFE → wild) et
  `hub_to_claims` (730,66,−676 → 732,69,−676, CLAIM_HOME → claims).

**B. Portails de zone avec conditions** (`/rpgadmin portal …`) — **aucun sur DEV** (`portals/` et
`destinations/` vides).
- `travel/model/PortalDefinition`, `PortalDefinitionParser`, `PortalService`, `PortalListener`.
- Conditions déjà codées : permission, quête (état), **niveau RPG** (`RequiredLevelRule`, corrigé par
  #532), coût, cooldown persistant (`portal_cooldowns`), canalisation annulée au mouvement/dégât/départ.
- Destinations = points fixes nommés (`travel/model/Destination`, `destinations/`).

**Gardes spécifiques Wild / Claims** — déclenchés par **comparaison du nom du monde d'arrivée** :
- Wild (#161, #26) : `WildEntryWarningService` — fenêtre Paper Dialog (`PaperDialogWildEntryPromptPresenter`,
  repli chat), boutons Entrer / Entrer et ne plus afficher / Annuler, préférence persistée dans
  `player_variables` (`WILD_ENTRY_WARNING_HIDDEN`). **Textes codés en dur.**
- Claims (#21/#22) : `ClaimWorldAccessGuard` — palier `CLAIM_TIER_1` ou claim principal, garantie d'un
  moyen de retour (`ClaimReturnService`), bypass admin explicite.
- #24 « conditions via le Garde » : `WildConditionsService` est une **information** (`%wild_conditions%`),
  pas une condition d'accès.

**PlugAdmin** : aucune page, action agent ni permission « portail ». Seule la page `/travel` (Voyages :
waypoints, bornes) existe. Les portails n'apparaissent que dans la documentation (`docs-site/worlds.html`).

### 1.3 Emplacements, bâtiments, IA (#213, #234, #265, #94)

- **Outil d'emplacement #213** : houe en fer identifiée par PDC `rpgquest:building_site_tool`
  (`building/BuildingSiteTool`, `/rpgadmin buildsite tool`). Un clic = **un point** + orientation cardinale
  (regard du joueur) + nom saisi dans une enclume. Table `building_sites` (V28/V31), états `EMPTY` /
  `OCCUPIED`. Page `/buildings/sites`, actions `building.site.*`. **Aucun champ « usage »** : rien ne
  distingue un emplacement de portail d'un chantier. (L'outil PNJ #381 est un outil distinct.)
- **Pose et cycle de vie #234** : `BuildingPlacementService` — aperçu, refus (WorldEdit absent, occupé,
  hors hauteur, chevauchement d'une autre pose), sauvegarde du terrain avant pose
  (`building_placements.backup_schematic`, `building_baselines`), libérer, réorienter, remplacer,
  historique, capture (#246). Pas de contrôle claim / zone protégée.
- **Atelier IA #94/#265** : plan → `BuildingPlanExpander` → `.schem` Sponge v3 (WorldEdit) + YAML de
  bibliothèque. `StructureKind.PORTAL` existe **dans le plan** (règle : un passage traversable), mais la
  définition de bibliothèque (`BuildingDefinition`) **ne conserve pas ce type**. Coût confirmé avant appel.
- **Contrat d'ancrages** demandé par #228 (zone de déclenchement, volume traversable, entrée/sortie) :
  **inexistant**.
- **Import .schem** depuis PlugAdmin : inexistant (#245 ouvert).

### 1.4 Composants PlugAdmin

Navigation `web/Layout.java` (groupes, `NavItem`), éditeur de texte stylé `web/StyleField.java` (couleurs,
gras, italique, aperçu, MiniMessage masqué), accordéon #89, `DiagnosticHelp`, journal `audit_log`,
permissions `authz/Permission` + rôles + groupes (#199/#200), modèle d'action agent
(`AgentActionType` → `AgentActions` → `BukkitAgentActions` → `AgentActionExecutor` ; panel
`AgentActionCatalog` + `AgentPages` + route `PanelApp`).

## 2. Ce qu'il reste à développer

**Mondes** : actions agent `world.list` / `world.settings.*` / `world.create` / `world.load` /
`world.unload` / `world.import` ; page `/worlds` et fiche ; permissions `WORLD_READ`, `WORLD_WRITE`,
`WORLD_CREATE` ; protection des mondes essentiels ; éléments liés par monde ; diagnostics.

**Portails** : modèle unique et lecture (`portal.list`), page `/portals` ; fiche avec trois blocs
indépendants (emplacement, construction, téléportation) ; point fixe et orientation d'arrivée ;
comportement si monde absent / dangereux ; conditions administrables ; cooldown ; messages et
avertissement par portail ; vérification sans appliquer ; simulation ; activation ; audit ; permissions
`PORTAL_READ`, `PORTAL_EDIT`, `PORTAL_ENABLE`, `PORTAL_TEST`.

**Liaison bâtiments** : type « portail » conservé dans la bibliothèque + zone de passage relative à
l'ancre, transformée par la rotation ; liaison explicite portail ↔ emplacement ↔ pose.

## 3. Composants directement réutilisables

| Besoin | Réutiliser | À ne pas recréer |
|---|---|---|
| Liste et persistance des portails | `WorldPortalRegistry` + `world-portals/*.yml` (format étendu, rétrocompatible) | un 3ᵉ moteur |
| Détection d'entrée | `WorldPortalTeleportListener` (anti-boucle, grâce d'arrivée) | un autre listener |
| Arrivée aléatoire / claim | `RandomSafeLocationFinder`, `ClaimArrivalResolver` | une recherche de point sûr |
| Point fixe | `Destination` (monde, x, y, z, yaw, pitch) | un nouveau type de coordonnées |
| Niveau, quête, permission, coût, cooldown | pipeline de `PortalService` (`RequiredLevelRule`, `portal_cooldowns`) | un moteur de conditions parallèle |
| Conditions joueur | `DialogueCondition` / `ConditionType` (quête, objet, variable, permission, claim…) — évaluateur à **extraire** de `DialogueSessionEngine` pour être partagé | une syntaxe libre |
| Avertissement + « ne plus afficher » | `WildEntryWarningService` + `WildEntryPromptPresenter` (Paper Dialog / chat) + `player_variables` | une nouvelle fenêtre |
| Garde Claims | `ClaimWorldAccessGuard` (moyen de retour #22) | — |
| Emplacement | outil #213, `building_sites`, page Emplacements | **un nouvel outil** |
| Construction | Bibliothèque, Atelier IA, `BuildingPlacementService` (aperçu, orientation, sauvegarde, libération) | un moteur de pose |
| Création de monde | API Multiverse-Core 5.7.3 (voir §4) ou `WorldCreator` | — |
| UI | `StyleField`, accordéon, `DiagnosticHelp`, sélecteur de monde (`knownWorlds`), audit, permissions | — |

## 4. Blocages et limites techniques (vérifiés)

**Paper 1.21.11 (`paper-api` compilé)** — `WorldCreator` : `environment` NORMAL / NETHER / THE_END ;
`type` NORMAL / FLAT / LARGE_BIOMES / AMPLIFIED ; `seed` ; `generateStructures` ; `generatorSettings`
(chaîne JSON du monde plat : couches, biome, structures) ; `hardcore`, `bonusChest` ;
`keepSpawnLoaded(TriState)`. `Server#unloadWorld(world, save)` ; `WorldBorder` ; 60 constantes `GameRule`
(noms 1.21.11 : `ADVANCE_TIME`, `ADVANCE_WEATHER`… déjà utilisés par le Hub).

**Multiverse-Core 5.7.3 (JAR de DEV)** — `MultiverseCoreApi.get().getWorldManager()` :
`createWorld(CreateWorldOptions)` avec `environment`, `worldType`, `seed`, `generateStructures`,
`generator`, `generatorSettings`, `biome` (biome unique), `useSpawnAdjust`, `bonusChest`,
`doFolderCheck` ; `importWorld`, `loadWorld`, `unloadWorld(UnloadWorldOptions: save/unload Bukkit)`,
`removeWorld`, `deleteWorld`, `regenWorld`, `cloneWorld`, `getPotentialWorlds()` (dossiers importables),
`getWorlds()/getUnloadedWorlds()`. Par monde : difficulté, PvP, mode de jeu, apparitions par catégorie
(`EntitySpawnConfig`), météo, point d'apparition, `autoLoad`, `hidden`, limite de joueurs, monde de
réapparition. Tout est persistant dans le `worlds.yml` de Multiverse et rechargé au démarrage. Les
réponses sont des `Attempt` avec raison d'échec.

Limites et risques :
1. **Création synchrone sur le thread principal** (Paper et Multiverse) : le serveur se fige pendant la
   génération initiale (quelques secondes, plus en AMPLIFIED). Atténuation : une seule création à la fois,
   `keepSpawnLoaded(FALSE)`, bouton grisé pendant « En cours », annonce. Non contournable avec l'API.
2. **Deux gestionnaires** (RPGQuest `worlds.yml` pour `wild`, Multiverse pour le reste) : il faut une
   seule source de vérité pour les nouveaux mondes (décision Q1). Ne pas migrer `wild` sans accord.
3. **Monde plat** : couches et biome passent par `generatorSettings` (JSON vanilla) — les identifiants de
   blocs/biomes doivent être validés contre le registre au serveur ; à tester réellement sur DEV (création
   d'un monde de test = confirmation propriétaire). Structures dans un monde plat : seulement via le
   même JSON ; à proposer comme option « avancée » après test.
4. **Nether/End** : une seule génération possible ; « plat » et « amplifié » ne s'appliquent pas → refus
   en français. Multiverse nomme les dimensions liées `%overworld%_nether` / `_the_end`.
5. **Déchargement** : impossible pour le monde principal ; refusé si des joueurs s'y trouvent ; doit
   aussi être refusé si un portail **actif** y mène ou si c'est un monde à rôle (Hub, Wild, Claims).
6. **Gamerules** : noms changés en 1.21.11 → lister depuis l'API au runtime, jamais une liste figée.
   PvP / difficulté existent à la fois en gamerule et dans Multiverse : écrire par **un seul** chemin.
7. **Téléportation des world-portals synchrone** et chargement de chunks synchrone : acceptable pour
   wild/claims, risqué pour des mondes neufs. Lot 4 : passer à `teleportAsync` + `getChunkAtAsync`
   sans changer le comportement visible de `hub_to_wild` / `hub_to_claims`.
8. **Gardes liés au nom du monde** : l'avertissement Wild s'applique à *tout* portail vers `wild`, la
   garde Claims à *tout* portail vers `claims`. À conserver tel quel (aucune régression) puis exposer en
   lecture dans la fiche ; la généralisation se fait par des champs **ajoutés** au portail.
9. **Dépendance Multiverse** : ajouter `compileOnly` Multiverse 5.7.3 + `softdepend`, isoler tous les appels
   dans un adaptateur (comme `WorldEditSchematicGateway`). Si Multiverse est absent : page Mondes en
   lecture seule (API Bukkit), création désactivée avec explication.
10. **Pas de contrat d'ancrages** : sans lui, la zone d'activation d'une arche posée ne peut pas être
    déduite ; repli = dimensions saisies autour de l'emplacement (largeur × hauteur × profondeur,
    orientées par la face de l'emplacement).

## 5. Navigation et parcours proposés

Nouveau groupe de navigation **« Monde »** : *Mondes* (`/worlds`), *Portails* (`/portals`), *Voyages*
(`/travel`, déplacé). Le groupe *Bâtiments* reste : Atelier, Bibliothèque, Emplacements. Liens croisés,
aucune fonction dupliquée :

- **Mondes** (`mondes.html`) : cartes par monde — rôle LodyQuests (Hub/Wild/Claims ou « Aucun rôle »),
  gestionnaire (Multiverse / RPGQuest / serveur), dimension, génération, chargé, joueurs, éléments liés,
  badge « Essentiel ». Filtres.
- **Fiche monde** (`monde-fiche.html`) : « Fixé à la création » (lecture seule) ; « Réglages » relus après
  application ; « Éléments liés » (portails qui y mènent/partent, emplacements, waypoints #137, mobs, PNJ,
  villes) ; « État » charger/décharger. **Pas de suppression** dans ce chantier.
- **Créer un monde** (`monde-creer.html`) : nom → identifiant ; dimension ; terrain (combinaisons
  incompatibles masquées) ; graine ; structures ; plat : préréglages + couches + biome ; résumé +
  case de confirmation ; états En cours / Prêt / Échec.
- **Portails** (`portails.html`) : une ligne par portail — départ → arrivée, stratégie, gardes visibles
  (avertissement, palier claim, moyen de retour, niveau…), construction liée, état, problèmes.
- **Fiche portail** (`portail-fiche.html`) : trois blocs **indépendants** en tête (1 Emplacement,
  2 Construction, 3 Téléportation) puis Destination, Conditions, Messages et avertissement (aperçu de la
  fenêtre Minecraft), Vérification, Essai contrôlé, Activation. Toute modification de destination se fait
  sur cette seule page.
- **Créer un portail** (`portail-creer.html`) : étapes A→F de la demande ; étape A = choisir un
  emplacement **marqué avec l'outil #213** ; étape B = Bibliothèque / Atelier IA / .schem (#245) / aucune.

### Modèle de liaison (emplacement ↔ construction ↔ téléportation)

```
building_sites (#213)            buildings/ + building_placements (#234)     world-portals/<id>.yml (#228)
  id, monde, point, face  <──┐     pose = (site, bâtiment, rotation)   <──┐   id permanent, nom, enabled/brouillon
                             └──── portal.site: buildsite_0012            └── portal.placement (lu, jamais écrit)
                                                                              zone: dérivée des ancrages de la pose
                                                                                    OU dimensions autour du site
                                                                                    OU min/max historiques (Hub)
                                                                              destination, conditions, messages
```

- Le **lien est porté par le portail** (`site: <id>` facultatif) : pas de nouvelle colonne
  `building_sites.purpose`. Un emplacement « est un emplacement de portail » parce qu'un portail le
  référence ; la page Emplacements l'affiche (« utilisé par le portail X ») et filtre dessus. Extension
  minimale, sans migration (décision Q3).
- Supprimer un portail ne touche ni l'emplacement ni la pose ; libérer/démolir une pose (#234) ne
  supprime pas le portail : sa vérification signale « construction absente » et la zone retombe sur les
  dimensions saisies ; supprimer un emplacement référencé par un portail est refusé avec la liste des
  références.
- Les portails existants `hub_to_wild` / `hub_to_claims` gardent leur cuboïde `min/max` (aucune
  migration, aucun champ `site`), lisibles tels quels.

## 6. Découpage recommandé (lots 2 à 7)

Ordre : lecture d'abord, mutations sûres ensuite, opérations coûteuses/dangereuses en dernier. Chaque
ticket est testable seul depuis PlugAdmin et/ou Minecraft. Sous-tickets créés sur GitHub dans ce lot (#560–#570), **non mis
en file** tant que le propriétaire n'a pas validé l'ensemble.

| Lot | Ticket | Contenu | Dépend de |
|---|---|---|---|
| 2 | **#560** (W1) | Mondes en lecture : adaptateur Multiverse (softdepend) + `world.list`, page `/worlds` + fiche lecture, éléments liés, essentiels, diagnostics, `WORLD_READ` | — |
| 2 | **#561** (W2) | Réglages d'un monde existant (difficulté, PvP, mobs/animaux, météo/heure, gamerules lues au runtime, bordure, point d'arrivée), relecture, audit, refus sur Hub/Claims/Wild si conflit de protection | W1 |
| 2 | **#562** (W3) | Créer un monde (normal / Nether / End ; normal / plat / amplifié / grands biomes ; graine ; structures ; plat : couches + biome), résumé, idempotence, états, `WORLD_CREATE` — **test réel = confirmation propriétaire** | W1 |
| 2 | **#563** (W4) | Charger / décharger / importer un dossier existant (`getPotentialWorlds`), refus sûrs | W1 |
| 2 | #137 (existant) | Politique waypoints par monde, affichée dans la fiche monde | W1 |
| 3 | **#564** (P1) | Modèle unique de lecture des portails (world-portals + gardes Wild/Claims exposés), `portal.list`, page `/portals` + fiche lecture, diagnostics, `PORTAL_READ` | W1 |
| 3 | **#565** (P2) | Créer un portail en brouillon depuis un emplacement #213, zone d'activation (dimensions orientées / min-max), renommer, activer/désactiver, supprimer sans toucher site ni pose, audit, `PORTAL_EDIT` / `PORTAL_ENABLE` | P1 |
| 4 | **#566** (P3) | Destinations : point fixe + regard, point d'arrivée du monde, RANDOM_SAFE, CLAIM_HOME ; monde absent/déchargé/dangereux ; portail retour distinct ; téléportation asynchrone ; « Vérifier sans appliquer » | P2, W1 |
| 4 | **#567** (P4) | Conditions d'accès : extraction de l'évaluateur de conditions partagé (dialogues) + niveau RPG, quête, objet, variable, permission, palier claim, cooldown ; contrôle serveur ; simulation AUTORISÉ/REFUSÉ ; test de téléportation avec confirmation forte (`PORTAL_TEST`) | P3 |
| 5 | **#568** (P5) | Construction : type « portail » + zone de passage conservés dans la Bibliothèque (contrat d'ancrages), Atelier IA en mode portail, liaison portail ↔ pose, remplacement d'arche sans perte de destination ; import .schem via #245 | P2, (#245) |
| 6 | **#569** (P6) | Messages et avertissements par portail (StyleField, aperçu Paper Dialog, « ne plus afficher » par joueur et par portail, reprise de la préférence Wild existante) | P2 |
| 7 | **#570** (P7) | Recette complète DEV ordinateur/mobile, plan de test manuel, documentation joueur/admin | tous |

## 7. Décisions attendues du propriétaire

- **Q1 — Gestionnaire des nouveaux mondes** : *recommandé* Multiverse-Core (déjà installé, API complète,
  persistance et rechargement). `wild` reste géré par RPGQuest et simplement affiché. Alternative :
  étendre `WorldService` (plus de code, pas de réglages par monde).
- **Q2 — Moteur de portail unique** : *recommandé* étendre les world-portals (ceux de DEV) en y
  absorbant point fixe, conditions, cooldown, messages et le pipeline de `PortalService` ; les portails
  de zone `/rpgadmin portal` (aucun sur DEV) deviennent obsolètes après migration de lecture.
- **Q3 — Liaison emplacement** : *recommandé* lien porté par le portail, sans colonne « usage » sur les
  emplacements.
- **Q4 — Test réel de création de monde sur DEV** : nom du monde de test et moment (gel bref du serveur).
- **Q5 — Portails vanilla Nether/End** dans les nouveaux mondes : bloquer par défaut (comme `claims`) ?
- **Q6 — Suppression de fichiers de monde** : hors périmètre ; ticket séparé seulement sur demande.
- **Q7 — Rôles** : un nouveau monde n'a aucun rôle ; changer le monde Hub/Wild/Claims reste une
  modification de configuration (Paramètres généraux #335), pas une action de la page Mondes. D'accord ?
