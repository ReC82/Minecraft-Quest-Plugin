# Compatibilité Citizens

Ce document fixe **ce dont RPGQuest dépend chez Citizens**, où cette dépendance est isolée, et
comment le plugin se comporte si la build installée ne correspond pas.

## Versions

| Élément | Valeur |
|---|---|
| Build Citizens installée sur le serveur DEV | **2.0.43-SNAPSHOT build 4232** (relevée en jeu) |
| Artefact de compilation `citizensapi` | `net.citizensnpcs:citizensapi:2.0.43-SNAPSHOT` |
| Artefact de compilation `citizens-main` | `net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT` (snapshot Maven du 2026-09-11) |
| Portée Gradle | `compileOnly` pour les deux — **jamais empaquetés** dans `rpgquest-*.jar` |

> **Deux numérotations distinctes.** Le serveur affiche un numéro de build **Jenkins** (`4232`) ;
> le dépôt Maven publie des snapshots horodatés de la même version `2.0.43-SNAPSHOT`. Les deux ne
> se recoupent pas directement : on ne peut pas garantir par ces seuls numéros que le JAR compilé
> contre le snapshot Maven correspond octet pour octet à la build installée. C'est précisément
> pourquoi l'intégration est **isolée et gardée** (voir plus bas) plutôt que supposée exacte.

## Deux artefacts, deux rôles

- **`citizensapi`** — l'API publique et stable. Utilisée partout dans
  `com.lodygames.rpgquest.npc.CitizensNpcBridge` : registre, `createNPC`, `spawn`, `destroy`,
  `setName`, `teleport`, `getStoredLocation`, traits publics `MobType` et `Spawned`.
- **`citizens-main`** — le plugin Citizens lui-même. Nécessaire **uniquement** pour deux
  comportements qui n'existent pas dans l'API : le trait `LookClose` et le fournisseur de parcours
  `WanderWaypointProvider` du trait `Waypoints`. Confiné à
  `com.lodygames.rpgquest.npc.CitizensBehaviourBridge`.

### Aucune dépendance implicite ajoutée

Le POM de `citizens-main` déclare WorldGuard, Denizen, PlaceholderAPI, Vault, Spigot, packetevents,
phtree et mocha — **tous en scope `provided`**, que Gradle ne résout pas transitivement. La seule
dépendance de scope `compile` est `citizensapi`, déjà déclarée explicitement. La déclaration porte
en plus `isTransitive = false`, pour écarter `libby-bukkit` (le chargeur de bibliothèques
d'exécution de Citizens), absent de nos dépôts et sans rôle à la compilation.

Vérifiable à tout moment :

```bash
./gradlew dependencies --configuration compileClasspath
```

## Surface réellement utilisée dans `citizens-main`

Toutes ces méthodes sont **publiques** et appelées de façon **typée** — ni réflexion, ni clés de
persistance internes.

| Classe | Membres utilisés |
|---|---|
| `net.citizensnpcs.trait.LookClose` | `isEnabled()`, `lookClose(boolean)`, `getRange()`, `setRange(double)`, `useRealisticLooking()`, `disableWhileNavigating()`, `targetNPCs()` |
| `net.citizensnpcs.trait.waypoint.Waypoints` | `getCurrentProviderName()`, `getCurrentProvider()`, `setWaypointProvider(String)` |
| `net.citizensnpcs.trait.waypoint.WanderWaypointProvider` | `getXRange()`, `getYRange()`, `setXYRange(int,int)`, `getRegionCentres()`, `addRegionCentre(Location)`, `removeRegionCentres(Collection)`, `getDelay()`, `isPathfind()` |
| `net.citizensnpcs.trait.waypoint.WaypointProvider.EnumerableWaypointProvider` | `waypoints()` — pour compter les points d'une patrouille existante |
| `net.citizensnpcs.Settings.Setting` | `DEFAULT_LOOK_CLOSE`, `DEFAULT_LOOK_CLOSE_RANGE`, `DEFAULT_REALISTIC_LOOKING`, `DISABLE_LOOKCLOSE_WHILE_NAVIGATING` — pour rapporter les **défauts effectifs** du serveur plutôt que des constantes recopiées |

### Deux particularités de Citizens 2.0.43 dont le code dépend

1. **`WanderWaypointProvider#setXYRange` ne recalcule pas l'arbre de régions.** Seuls
   `addRegionCentre` et `removeRegionCentres` appellent `recalculateTree()`. Le pont règle donc la
   zone **avant** de poser l'ancre. Inverser l'ordre laisserait la zone effective à sa valeur
   précédente alors que la fiche afficherait la nouvelle.
2. **`Waypoints#setWaypointProvider` détruit le fournisseur précédent** (`onRemove()` puis
   remplacement). Activer la promenade sur un PNJ qui patrouille perd sa patrouille. D'où la
   confirmation obligatoire, décidée par `WanderChangePlanner`.

## Comportement en cas d'incompatibilité

Le plugin **démarre et fonctionne sans Citizens** : `NpcIdentityService` vérifie la présence du
plugin par l'API Bukkit seule, et n'instancie les ponts que si Citizens est actif.

Si Citizens est actif mais que sa build n'expose pas ce qui est attendu :

- l'instanciation de `CitizensBehaviourBridge` est protégée : un `LinkageError` est journalisé en
  `WARN` et le pont reste `null` ;
- chaque appel est en plus enveloppé dans une garde qui intercepte `LinkageError` ;
- les écritures renvoient alors le code **`CITIZENS_INCOMPATIBLE`** avec un message explicite ;
- les lectures renvoient « inconnu » — le panel affiche « inconnu », **jamais « désactivé »** ;
- **tout le reste de l'intégration PNJ continue de fonctionner** : liaison, apparition, renommage,
  skin, déplacement ne dépendent que de `citizensapi`.

C'est la même discipline que `com.lodygames.rpgquest.ops.ConsoleTap` pour Log4j.

## Limite de couverture de tests

`MockBukkit` n'embarque pas Citizens : **`CitizensBehaviourBridge` n'est couvert par aucun test
automatisé**. Ce qui est testé est tout ce qui a pu être extrait en logique pure ou en contrat :

- `WanderChangePlannerTest` — la règle de non-écrasement ;
- `NpcBehaviourActionTest` — validation et idempotence des deux actions agent ;
- `NpcsCatalogTest` — rendu panel, préremplissage, refus de confirmation, transmission des
  paramètres ;
- `NpcCitizensPayloadTest` — garde structurelle : tout composant de `CitizensNpcSummary` doit
  apparaître dans le relevé sérialisé.

La validation du comportement des traits réels est **manuelle** et décrite dans
`docs/MANUAL_TEST_PLAN.md`.

## Si Citizens est mis à jour sur le serveur

1. Relever la nouvelle version en jeu (`/version Citizens`).
2. Aligner `build.gradle.kts` si la version majeure/mineure change.
3. Recompiler : une signature disparue devient une **erreur de compilation**, pas une panne en
   production — c'est l'intérêt des appels typés.
4. Rejouer la section Citizens de `docs/MANUAL_TEST_PLAN.md`.
