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
| 22 | Boutique web et livraison sécurisée | DONE |
| 23 | Prototype de mod client séparé | TODO |

## Étape en cours

### Étape 23 — Prototype de mod client séparé

Branche attendue : `feature/23-client-mod` (à créer). Aucun cahier des
charges détaillé retrouvé dans le dépôt pour cette étape — à clarifier
avec l'utilisateur si le seul titre de la table ne suffit pas à démarrer
sans clarification (les étapes 14/15/17/18/19/20 ont dû être conçues au
moins en partie par ingénierie faute d'un tel cahier des charges reçu en
conversation ; les étapes 16 à 22 ont reçu le leur directement dans le
chat).

### Dernière observation (2026-08-07, étape 22 reçue en cahier des charges détaillé dans le chat)

Étape 22 (Boutique web et livraison sécurisée) confirmée `DONE` : build
vert sur les deux modules Gradle (racine + `web-api`), 544 tests plugin
(11 ignorés — pas échoués, limitation MockBukkit pré-existante depuis
l'étape 18, aucune occurrence supplémentaire) + 30 tests `web-api` (0
ignoré). Cahier des charges détaillé reçu directement dans la conversation
(catalogue produit séparé des avantages techniques, 5 produits initiaux —
Small Backpack, upgrades Medium/Large, pass VIP de test, cosmétique —,
prestataire de paiement externe en mode test, aucune donnée de carte
bancaire stockée, table de commandes + file de livraisons avec
identifiants uniques, livraison répétée ignorée sans erreur, récupération
des livraisons en attente après redémarrage, signature/authentification
site ↔ serveur, gestion joueur hors ligne/UUID inconnu/produit déjà
possédé/upgrade/remboursement/révocation/échec temporaire, historique
admin, logs d'audit sans données sensibles, philosophie
pay-to-convenience/limites anti-pay-to-win, mode sandbox d'abord, commit
attendu `feat(store): add secure idempotent web purchases`) — suivi à la
lettre. Branche demandée par l'utilisateur : `feature/22-web-store` (et non
`feature/22-web-shop` comme anticipé dans une observation précédente).

Portée réalisée, module `web-api/store` (nouveau, projet déjà séparé du
plugin depuis l'étape 21) : `store.db` propre à web-api (jamais `data.db`
— `org.xerial:sqlite-jdbc` ajouté ici en vraie dépendance de runtime,
exception documentée puisque web-api n'a pas le `LibraryLoader` de Paper),
`orders`/`deliveries`/`webhook_events` (dédup par id d'événement du
prestataire), `ProductCatalog` (`products.json`, commercial uniquement),
`SandboxPaymentProvider` (simulation auto-hébergée d'un vrai prestataire
en mode test — session hébergée `/store/pay/{id}`, webhook signé
HMAC-SHA256 vers `/store/webhook`, décision documentée en détail dans
docs/STORE.md faute d'accès à un vrai prestataire dans cet environnement),
`StoreService` (checkout, webhook idempotent, remboursement, jamais un
octroi direct), routes site (`/store`, `/store/pay/*`) et API authentifiée
(`/api/store/deliveries/pending|{id}/ack`, `/api/store/orders`,
`/api/store/orders/{id}/refund`).

Portée réalisée, plugin (`be.lloyd.rpgquest.store`) : `StoreConfig`
(`config.yml` → `store:`, désactivé par défaut), `StoreProductRegistry`
(YAML `store-products/*.yml`, cinq exemples, deux types d'octroi
seulement — `BACKPACK_SIZE`/`ENTITLEMENT`, aucun attribut de combat
possible par construction), migration SQLite V10
(`store_deliveries_processed`, filet d'idempotence local), `StoreClient`
(HTTP asynchrone, même jeton que l'étape 21 via `RPGQUEST_WEB_API_TOKEN`),
`StoreDeliveryService` (sondage périodique, offline-capable de bout en
bout via UUID, résolution "déjà possédé"/upgrade côté serveur de jeu
uniquement, création automatique du profil pour un UUID inconnu, échec
temporaire jamais acquitté — réessayé au sondage suivant), `/store history
[joueur|uuid]` (`rpgquest.admin`). Documentation : `docs/STORE.md`
(prestataire sandbox, idempotence, gestion des cas particuliers,
déploiement, limites).

Limitation connue (héritée de l'étape 18, pas nouvelle, aucune occurrence
supplémentaire cette étape) : 11 tests plugin restent marqués **ignorés**
(pas échoués) — MockBukkit 4.110.0 (dernière version disponible) lève
délibérément `TestAbortedException` sur plusieurs méthodes non
implémentées ; le comportement réel n'est testable qu'en jeu. Limites
assumées propres à cette étape (documentées dans docs/STORE.md) : le
remboursement d'un backpack retombe sur `fallback-size` plutôt qu'un
calcul de permission par-joueur (impossible hors ligne), le pass VIP et
les cosmétiques n'ont encore aucun effet de gameplay (plomberie
uniquement), pas d'interface web pour déclencher un remboursement (appel
direct de l'API), pas de TLS natif (reverse proxy
attendu en production).

Étapes 1 à 22 confirmées `DONE`. Aucun cahier des charges détaillé n'a été
retrouvé pour l'étape 23 dans le dépôt (`.ai/PROMPTS/` ne contient qu'un
`README.md` placeholder) ; les étapes 16 à 22 ont fait exception en le
recevant directement en conversation — pas de garantie que ça se
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

```text
Date: 2026-08-07
Branche de départ: feature/21-web-api
Étape de départ: 22 (Boutique web et livraison sécurisée), TODO — cahier
  des charges détaillé reçu en conversation pendant la session (catalogue
  produit séparé des avantages techniques, 5 produits initiaux, prestataire
  de paiement externe en mode test, aucune donnée de carte bancaire
  stockée, table de commandes + file de livraisons à identifiants uniques,
  livraison répétée ignorée sans erreur, récupération après redémarrage,
  signature/authentification site ↔ serveur, gestion joueur hors
  ligne/UUID inconnu/produit déjà possédé/upgrade/remboursement/
  révocation/échec temporaire, historique admin, logs d'audit sans donnée
  sensible, philosophie pay-to-convenience/anti-pay-to-win, mode sandbox
  d'abord, commit attendu feat(store): add secure idempotent web purchases)
  — branche demandée par l'utilisateur : feature/22-web-store
Étapes terminées: 22
Branche finale: feature/22-web-store
Dernier commit: 841f0cf feat(store): add secure idempotent web purchases
Build: vert (./gradlew clean build, deux modules : racine + web-api)
Tests: 544 tests plugin (11 ignorés — pas échoués, limitation MockBukkit
  héritée de l'étape 18, aucune occurrence supplémentaire) + 30 tests
  web-api (0 ignoré) ; nouveaux : StoreDeliveryServiceTest,
  SchemaMigratorTest (migration V10), StoreHttpTest (bout-en-bout : achat,
  webhook rejoué/signature invalide, livraison répétée, remboursement,
  historique, reprise après redémarrage)
Tests manuels en attente: achat sandbox d'un Small Backpack, serveur
  arrêté pendant l'achat puis redémarrage et livraison, upgrade vers
  Medium puis Large, rejouer le webhook, simuler un remboursement (voir
  docs/STORE.md)
Blocages: aucun
Première étape à reprendre: 23 (Prototype de mod client séparé) — aucun
  cahier des charges détaillé retrouvé dans le dépôt, à clarifier avec
  l'utilisateur si besoin avant de démarrer
```
