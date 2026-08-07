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
| 18 | Mobs spéciaux vanilla | TODO |
| 19 | XP RPG | TODO |
| 20 | Backpacks | TODO |
| 21 | API et site web read-only | TODO |
| 22 | Boutique web et livraison sécurisée | TODO |
| 23 | Prototype de mod client séparé | TODO |

## Étape en cours

### Étape 18 — Mobs spéciaux vanilla

Branche attendue : `feature/18-special-mobs`

Aucun cahier des charges détaillé retrouvé dans le dépôt pour cette étape —
à clarifier avec l'utilisateur si le seul titre de la table ne suffit pas
à démarrer sans clarification (les étapes 14/15/17 ont dû être conçues par
ingénierie faute d'un tel cahier des charges reçu en conversation ; l'étape
16 a reçu le sien directement dans le chat).

### Dernière observation (2026-08-07, étape 17 reçue en cahier des charges détaillé dans le chat)

Étape 17 (Claims de terrain) confirmée `DONE` : build vert, 411 tests
verts. Cahier des charges détaillé reçu directement dans la conversation
(modèle de claim persistant, outil de sélection, `/claim
create|delete|info|trust|untrust|list`, refus chevauchement/safe
zone/portails/taille/nombre, protections blocs/conteneurs/animaux/armor
stands/redstone configurable/explosions/pistons, bypass, politique
d'extension future liée à la progression, aucun avantage payant, commit
attendu `feat(claims): add persistent protected land claims`) — suivi à la
lettre. Portée réalisée : `claim.model.Claim` (correct par construction,
propriétaire par UUID, jamais par pseudo) persisté en SQLite plutôt qu'en
YAML (`claims`/`claim_members`, migration V7 — décision documentée : profil
d'usage joueur, comme le marché entre joueurs, contrairement aux zones
protégées curées par un admin), `ClaimRepository` réutilise `Claim`
directement (aucune dépendance Bukkit à séparer, contrairement à
`MarketListingRecord`), outil de sélection dédié (`rpgquest:claim_wand`,
distinct de celui des zones), `ClaimService` porte toute la validation
métier (chevauchement claim/zone protégée via `ZoneRegistry`/distance aux
portails via `YamlPortalRegistry`/taille/nombre, avec des seams
`effectiveMax*(Player)` préparés pour une future politique de progression),
`ClaimProtectionListener` (mêmes patrons que `ZoneProtectionListener`, plus
`Animals`/`PlayerArmorStandManipulateEvent`, nouveaux pour ce projet),
`/claim *` (toutes les sous-commandes sauf `create`/`list` opèrent sur le
claim où le joueur se trouve, lecture littérale du cahier des charges),
`docs/CLAIMS.md`.

Étapes 1 à 17 confirmées `DONE`. Aucun cahier des charges détaillé n'a été
retrouvé pour les étapes 18 à 23 dans le dépôt (`.ai/PROMPTS/` ne contient
qu'un `README.md` placeholder) ; les étapes 16 et 17 ont fait exception en
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
Dernier commit: (voir git log — commit de l'étape 17 à suivre)
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
