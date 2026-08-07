# RPGQuest — Roadmap d'avancement

> Ce fichier est un résumé opérationnel. Le dépôt Git, le code et les tests restent la source de vérité.
> Claude doit corriger ce fichier dès qu'un audit montre qu'il est obsolète.

## Statuts

- `DONE` : code + tests automatiques validés, documentation mise à jour.
- `IN_PROGRESS` : travail commencé mais critères encore incomplets.
- `TODO` : pas encore commencé.
- `PENDING MANUAL VALIDATION` : automatisation validée mais test en jeu encore requis.

## État actuel à vérifier au prochain démarrage

Confirmé par audit Git + build + tests le 2026-08-07 (session précédente) :
étapes 1 à 10 réellement `DONE` (218 tests verts, `./gradlew clean build`
vert, une branche `feature/NN-*` par étape, historique linéaire). Package
Java confirmé : `be.lloyd.rpgquest` (aucune trace de `com.lodygames.rpgquest`
nulle part dans le dépôt réel — le dépôt réel fait foi, voir règle de
priorité des sources).

| Étape | Fonction | Statut confirmé |
|---|---|---|
| 1 | Socle architectural | DONE |
| 2 | SQLite et profils joueurs | DONE |
| 3 | Définitions de quêtes | DONE |
| 4 | Progression de quêtes | DONE |
| 5 | Dialogues et embranchements | DONE |
| 6 | Journal de quêtes / UI | DONE |
| 7 | Registre d'objets personnalisés | DONE |
| 8 | Armes et outils personnalisés | DONE |
| 9 | Ressources personnalisées et récolte | DONE |
| 10 | Recettes, resource pack et intégration | DONE |
| 11 | Serveur local intégré au workspace | DONE |
| 12 | Outil admin d'aplatissement | DONE |
| 13 | Village central et safe zone | DONE |
| 14 | Économie et marchands PNJ | DONE |
| 15 | Marché entre joueurs | DONE |
| 16 | Portails et téléportation | DONE |
| 17 | Claims de terrain | DONE |
| 18 | Mobs spéciaux vanilla | DONE |
| 19 | XP RPG | DONE |
| 20 | Backpacks | DONE |
| 21 | API et site web read-only | DONE |
| 22 | Boutique web et livraison sécurisée | TODO |
| 23 | Prototype de mod client séparé | TODO |

## Étape en cours

### Étape 22 — Boutique web et livraison sécurisée

Branche attendue : `feature/22-web-shop` (à créer). Aucun cahier des
charges détaillé retrouvé dans le dépôt pour cette étape — à clarifier
avec l'utilisateur si le seul titre de la table ne suffit pas à démarrer
sans clarification (les étapes 14/15/17/18/19/20 ont dû être conçues au
moins en partie par ingénierie faute d'un tel cahier des charges reçu en
conversation ; les étapes 16/17/18/19/20/21 ont reçu le leur directement
dans le chat).

### Dernière observation (2026-08-07, étape 21 reçue en cahier des charges détaillé dans le chat)

Étape 21 (API et site web read-only) confirmée `DONE` : build vert sur les
deux modules Gradle (racine + `web-api`), 535 tests plugin (11 ignorés —
pas échoués, limitation MockBukkit pré-existante depuis l'étape 18) + 19
tests `web-api` (0 ignoré). Cahier des charges détaillé reçu directement
dans la conversation (module web séparé du gameplay, jamais d'accès direct
au fichier SQLite, API authentifiée — statut serveur, nombre de joueurs,
joueurs connectés si autorisé, classements, catalogue public d'objets,
actualités configurées —, lectures uniquement via snapshots/caches ou
async, authentification serveur-à-serveur, rate limiting + validation +
journalisation, aucun secret dans Git, site minimal — accueil, statut,
classements, wiki/catalogue —, mode dégradé si le serveur Minecraft est
arrêté, ni paiement ni login joueur ni modification de données à ce stade,
commit attendu `feat(web): add read-only server API and portal`) — suivi
à la lettre.

Portée réalisée, côté plugin (`be.lloyd.rpgquest.web`) : `WebExportConfig`
(`config.yml` → `web-export:`, désactivé par défaut) + `WebSnapshotWriter`
(`PluginService`, tick de housekeeping léger sur le thread principal,
calcul des classements via `ProgressionRepository.topPlayers` — nouvelle
requête en lecture seule, jamais appelée depuis un chemin de jeu synchrone
—, écriture disque déportée sur un exécuteur dédié, jamais sur le thread
principal ni sur celui de la continuation asynchrone), `JsonWriter`
(sérialiseur maison, écriture seule côté plugin), écriture atomique par
renommage (`snapshot.json.tmp` → `ATOMIC_MOVE`).

Portée réalisée, module séparé `web-api/` (nouveau projet Gradle,
`settings.gradle.kts` → `include("web-api")`, aucune dépendance vers Paper,
aucun accès à `data.db`) : `webapi.json.Json` (codec JSON maison, lecture
*et* écriture, aucune dépendance externe), `SnapshotStore` (cache en
mémoire, détection du mode dégradé — snapshot absent ou périmé, jamais une
erreur), `ServerState` (résolution du mode dégradé centralisée, jamais
réimplémentée par route), serveur HTTP via `com.sun.net.httpserver` (JDK)
avec `RequestPipeline` (rate limit par IP, authentification par jeton en
temps constant `MessageDigest.isEqual`, fail-closed si aucun jeton
configuré, journalisation sans jamais écrire de secret), routes API
authentifiées (`/api/status|players|leaderboards|catalog|announcements`)
et site public non authentifié (`/`, `/status`, `/leaderboards`, `/wiki`,
échappement HTML systématique), jeton exclusivement via la variable
d'environnement `RPGQUEST_WEB_API_TOKEN` (jamais dans un fichier versionné
— `web-api.properties.example` ne contient que des réglages non
sensibles), `WebApiMain` (point d'entrée, arrêt propre via shutdown hook).
Documentation : `docs/WEB_API.md` (déploiement local/production complet).

Limitation connue (héritée de l'étape 18, pas nouvelle, aucune occurrence
supplémentaire cette étape) : 11 tests plugin restent marqués **ignorés**
(pas échoués) — MockBukkit 4.110.0 (dernière version disponible) lève
délibérément `TestAbortedException` sur plusieurs méthodes non
implémentées ; le comportement réel n'est testable qu'en jeu. Limite
assumée propre à cette étape (documentée dans docs/WEB_API.md) : pas de
pagination sur le catalogue/wiki, rate limiting en mémoire par processus
(non partagé entre plusieurs instances), pas de TLS natif (reverse proxy
attendu en production).

Étapes 1 à 21 confirmées `DONE`. Aucun cahier des charges détaillé n'a été
retrouvé pour les étapes 22 à 23 dans le dépôt (`.ai/PROMPTS/` ne contient
qu'un `README.md` placeholder) ; les étapes 16 à 21 ont fait exception en
le recevant directement en conversation — pas de garantie que ça se
reproduise pour les étapes suivantes. Chaque étape devra être précisée par
l'utilisateur si le niveau de détail actuel (titre de la table ci-dessus)
ne suffit pas à démarrer sans clarification.

## Journal de session

À la fin de chaque session, ajouter une entrée concise :

```text
Date:
Branche de départ:
Étape de départ:
Étapes terminées:
Branche finale:
Dernier commit:
Build:
Tests:
Tests manuels en attente:
Blocages:
Première étape à reprendre:
```

```text
Date: 2026-08-07
Branche de départ: feature/13-safe-zone
Étape de départ: 14 (Économie et marchands PNJ), TODO
Étapes terminées: 14
Branche finale: feature/14-economy-merchants
Dernier commit: 23f604e feat(economy): add player wallet, payments, and NPC merchants
Build: vert (./gradlew clean build)
Tests: 320+ tests verts (nouveaux : WalletRepositoryTest,
  MerchantDefinitionParserTest, MerchantLoaderTest, MerchantTradeServiceTest,
  ajouts DialogueDefinitionParserTest/DialogueSessionEngineTest pour
  OPEN_MERCHANT ; SchemaMigratorTest mis à jour pour la migration V4)
Tests manuels en attente: ouverture d'une vitrine par clic sur un PNJ
  renommé, lisibilité du lore des offres, /money pay entre deux vrais
  joueurs, latence réseau sur un achat/vente (voir docs/ECONOMY.md)
Blocages: aucun
Première étape à reprendre: 15 (Marché entre joueurs) — aucun cahier des
  charges détaillé retrouvé dans le dépôt pour les étapes 15 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/14-economy-merchants
Étape de départ: 15 (Marché entre joueurs), TODO, aucun cahier des charges
  détaillé dans le dépôt — portée définie par ingénierie (utilisateur a
  dit "continue" sans préciser)
Étapes terminées: 15
Branche finale: feature/15-player-market
Dernier commit: 731a948 feat(economy): add player-to-player market
Build: vert (./gradlew clean build)
Tests: 333 tests verts (nouveaux : MarketRepositoryTest, MarketServiceTest ;
  SchemaMigratorTest mis à jour pour la migration V5)
Tests manuels en attente: navigation entre pages de /market avec beaucoup
  d'offres, deux vrais joueurs achetant simultanément la même offre,
  latence réseau (voir docs/ECONOMY.md)
Blocages: aucun
Première étape à reprendre: 16 (Portails et téléportation) — aucun cahier
  des charges détaillé retrouvé dans le dépôt pour les étapes 16 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/15-player-market
Étape de départ: 16 (Portails et téléportation), TODO — cahier des charges
  détaillé reçu en conversation pendant la session (canalisation, sécurité
  de destination, cooldown persisté, coût, commandes admin, commit
  attendu feat(travel): add safe configurable portals)
Étapes terminées: 16
Branche finale: feature/16-portals
Dernier commit: 600c74b feat(travel): add safe configurable portals
Build: vert (./gradlew clean build)
Tests: 368 tests verts (nouveaux : DestinationTest, PortalDefinitionTest,
  DestinationDefinitionParserTest, PortalDefinitionParserTest,
  DestinationLoaderTest, PortalLoaderTest, YamlDestinationRegistryTest,
  YamlPortalRegistryTest, PortalServiceTest ; SchemaMigratorTest mis à
  jour pour la migration V6)
Tests manuels en attente: portail vers un chunk réellement déchargé,
  déconnexion en pleine canalisation, reconnexion et persistance du
  cooldown, téléportation avec inventaire chargé et quête active, test
  depuis/vers une safe zone réelle (voir docs/TRAVEL.md)
Blocages: aucun
Première étape à reprendre: 17 (Claims de terrain) — aucun cahier des
  charges détaillé retrouvé dans le dépôt pour les étapes 17 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/16-portals
Étape de départ: 17 (Claims de terrain), TODO — cahier des charges détaillé
  reçu en conversation pendant la session (modèle persistant, sélection,
  commandes, refus à la création, protections, bypass, politique
  d'extension future, aucun avantage payant, commit attendu
  feat(claims): add persistent protected land claims)
Étapes terminées: 17
Branche finale: feature/17-land-claims
Dernier commit: dfbe0e5 feat(claims): add persistent protected land claims
Build: vert (./gradlew clean build)
Tests: 411 tests verts (nouveaux : ClaimTest, ClaimRepositoryTest,
  ClaimServiceTest, ClaimProtectionListenerTest, ajouts ConfigValidatorTest
  pour la section claims ; SchemaMigratorTest mis à jour pour la migration V7)
Tests manuels en attente: deux joueurs voisins, coffres/portes/animaux/
  redstone réels, TNT dedans/dehors, piston traversant la limite en jeu,
  redémarrage complet du serveur (voir docs/CLAIMS.md)
Blocages: aucun
Première étape à reprendre: 18 (Mobs spéciaux vanilla) — aucun cahier des
  charges détaillé retrouvé dans le dépôt pour les étapes 18 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/17-land-claims
Étape de départ: 18 (Mobs spéciaux vanilla), TODO — cahier des charges
  détaillé reçu en conversation pendant la session (registre PDC,
  définition configurable complète, quatre variantes obligatoires, respect
  safe zone/claims, respect des événements annulés, anti-boucle de
  duplication, commandes admin, métriques debug, identification jamais par
  le nom affiché, commit attendu feat(mobs): add configurable vanilla mob
  variants)
Étapes terminées: 18
Branche finale: feature/18-special-mobs
Dernier commit: 19c60b1 feat(mobs): add configurable vanilla mob variants
Build: vert (./gradlew clean build)
Tests: 475 tests (nouveaux : SpecialMobDefinitionParserTest,
  SpecialMobLoaderTest, SpecialMobServiceTest, SplitOnHitAbilityListenerTest ;
  2 tests ignorés — pas échoués — dans SpecialMobServiceTest à cause d'une
  limitation MockBukkit 4.110.0 (setRemoveWhenFarAway non implémenté), voir
  ci-dessus)
Tests manuels en attente: faire apparaître chaque variante, tuer le creeper
  doré plusieurs fois, tester l'explosion en safe zone et en claim, frapper
  le zombie fissible jusqu'à la limite, décharger/recharger le chunk,
  redémarrer avec des mobs spéciaux présents (voir SPECIAL_MOB_FORMAT.md)
Blocages: aucun
Première étape à reprendre: 19 (XP RPG) — aucun cahier des charges détaillé
  retrouvé dans le dépôt pour les étapes 19 à 23, à clarifier avec
  l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/18-special-mobs
Étape de départ: 19 (XP RPG), TODO — cahier des charges détaillé reçu en
  conversation pendant la session (six pistes dont GLOBAL, SQLite, courbe
  configurable et validée, service générique d'octroi avec id d'événement,
  déduplication, sources interceptées, anti-farm, commandes, affichage,
  XP vanilla conservée, hooks de déblocage, modèle d'équilibrage documenté,
  commit attendu feat(progression): add multi-skill RPG experience system)
Étapes terminées: 19
Branche finale: feature/19-rpg-experience
Dernier commit: 7260d11 feat(progression): add multi-skill RPG experience system
Build: vert (./gradlew clean build)
Tests: 503 tests (nouveaux : ProgressionCurveTest, ProgressionServiceTest,
  CombatXpListenerTest, MiningXpListenerTest ; SchemaMigratorTest mis à
  jour pour la migration V8 ; 9 tests ignorés — pas échoués, limitation
  MockBukkit héritée de l'étape 18, voir ci-dessus)
Tests manuels en attente: combat normal et mob spécial, miner un bloc
  naturel puis un bloc posé, récolter une culture mûre/non mûre, pêcher,
  terminer une quête, redémarrer et vérifier les niveaux (voir
  docs/PROGRESSION.md)
Blocages: aucun
Première étape à reprendre: 20 (Backpacks) — aucun cahier des charges
  détaillé retrouvé dans le dépôt pour les étapes 20 à 23, à clarifier avec
  l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/19-rpg-experience
Étape de départ: 20 (Backpacks), TODO — cahier des charges détaillé reçu en
  conversation pendant la session (inventaire virtuel persistant, tailles
  configurables, objet/commande d'ouverture, permission de secours +
  commandes admin, stockage sûr et versionné, interdictions (imbrication,
  objets interdits, ouverture simultanée, interaction non autorisée),
  sauvegarde atomique fermeture/déconnexion/arrêt, boîte de récupération,
  upgrade sans perte, interface EntitlementService générique, modèle
  documenté, commit attendu feat(storage): add secure persistent backpacks)
Étapes terminées: 20
Branche finale: feature/20-backpacks
Dernier commit: adf6828 feat(storage): add secure persistent backpacks
Build: vert (./gradlew clean build)
Tests: 523 tests (nouveaux : ItemArraySerializerTest, BackpackServiceTest,
  BackpackListenerTest ; SchemaMigratorTest mis à jour pour la migration V9 ;
  11 tests ignorés — pas échoués, limitation MockBukkit héritée de l'étape
  18 + deux occurrences supplémentaires cette étape, voir ci-dessus)
Tests manuels en attente: ouvrir/remplir/fermer/reconnecter, mourir avec le
  backpack, changer de monde, forcer l'arrêt avec le backpack ouvert,
  upgrade puis downgrade, deux clients sur le même compte si possible (voir
  docs/BACKPACKS.md)
Blocages: aucun
Première étape à reprendre: 21 (API et site web read-only) — aucun cahier
  des charges détaillé retrouvé dans le dépôt pour les étapes 21 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/20-backpacks
Étape de départ: 21 (API et site web read-only), TODO — cahier des charges
  détaillé reçu en conversation pendant la session (module web séparé du
  gameplay, jamais d'accès direct au fichier SQLite, API authentifiée —
  statut, joueurs, classements, catalogue public, annonces —, lectures via
  snapshots/caches ou async, authentification serveur-à-serveur, rate
  limiting + validation + journalisation, aucun secret dans Git, site
  minimal (accueil/statut/classements/wiki), mode dégradé si le serveur
  Minecraft est arrêté, ni paiement ni login joueur ni écriture à ce
  stade, commit attendu feat(web): add read-only server API and portal)
Étapes terminées: 21
Branche finale: feature/21-web-api
Dernier commit: 54a8147 feat(web): add read-only server API and portal
Build: vert (./gradlew clean build, deux modules : racine + web-api)
Tests: 535 tests plugin (11 ignorés — pas échoués, limitation MockBukkit
  héritée de l'étape 18, aucune occurrence supplémentaire cette étape) +
  19 tests web-api (0 ignoré) ; nouveaux : ProgressionRepositoryTest
  (topPlayers), WebSnapshotWriterTest, ConfigValidatorTest (web-export),
  JsonTest, HttpServerBootstrapTest (bout-en-bout, vrai HttpServer + vrai
  HttpClient)
Tests manuels en attente: lancer serveur et site localement, consulter les
  pages, arrêter Minecraft et vérifier le mode dégradé, vérifier qu'aucun
  secret n'apparaît dans les réponses ou logs (voir docs/WEB_API.md)
Blocages: aucun
Première étape à reprendre: 22 (Boutique web et livraison sécurisée) —
  aucun cahier des charges détaillé retrouvé dans le dépôt pour les étapes
  22 à 23, à clarifier avec l'utilisateur si besoin avant de démarrer
```
