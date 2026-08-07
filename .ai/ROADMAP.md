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
| 16 | Portails et téléportation | TODO |
| 17 | Claims de terrain | TODO |
| 18 | Mobs spéciaux vanilla | TODO |
| 19 | XP RPG | TODO |
| 20 | Backpacks | TODO |
| 21 | API et site web read-only | TODO |
| 22 | Boutique web et livraison sécurisée | TODO |
| 23 | Prototype de mod client séparé | TODO |

## Étape en cours

### Étape 16 — Portails et téléportation

Branche attendue : `feature/16-portals`

Aucun cahier des charges détaillé retrouvé dans le dépôt pour cette étape
(voir « Dernière observation » ci-dessous) — à clarifier avec l'utilisateur
avant de démarrer si le seul titre de la table ne suffit pas. Auditer
`zone.ZoneRegistry`/`docs/SAFE_ZONE.md` avant de commencer : des portails
référencent probablement des positions à l'intérieur ou en dehors de zones
protégées, cohérence à vérifier avec le registre existant.

### Dernière observation (2026-08-07, étape 15 terminée sans cahier des charges détaillé)

Étape 15 (Marché entre joueurs) confirmée `DONE` : build vert, 333 tests
verts (dont `MarketRepositoryTest`, `MarketServiceTest`, et
`SchemaMigratorTest` mis à jour pour la migration V5). Aucun cahier des
charges n'existant dans le dépôt pour cette étape (voir historique), la
portée a été conçue par Claude, en cohérence avec l'économie/les
marchands déjà livrés à l'étape 14 : `database.MarketRepository`
(`market_listings`, migration V5, objet complet en dépôt via
`ItemStack#serializeAsBytes()` — aucune dépendance au registre d'objets
personnalisés), trois opérations atomiques (`claim`/`cancel`/`reactivate`,
même discipline transactionnelle que `WalletRepository`),
`economy.market.MarketService` (vitrine paginée unique, sans onglets —
clic sur l'offre d'un autre joueur = achat, sur la sienne = annulation),
achat en deux temps (réservation atomique d'abord, débit ensuite,
réactivation si le débit échoue — ordre imposé par le fait que le prix
n'est connu qu'après lecture en base, contrairement à un marchand YAML),
`/market`/`/market sell`/`/market cancel`/`/market admin list`, vendeur
crédité même hors ligne. Limitation assumée et documentée : pas
d'annulation admin avec restitution d'objet pour un vendeur hors ligne
(pas de système de boîte aux lettres — mirroir facile d'un futur système
de backpacks/livraison, étape 20, si besoin).

Étapes 1 à 15 confirmées `DONE`. Aucun cahier des charges détaillé n'a été
retrouvé pour les étapes 16 à 23 dans le dépôt (`.ai/PROMPTS/` ne contient
qu'un `README.md` placeholder) — une session « mode nuit » antérieure
(2026-08-07) mentionne l'avoir reçu en conversation, mais celle-ci n'a
laissé aucun `HANDOFF.md`/fichier de reprise, et le contenu exact n'est
donc plus disponible. Chaque étape suivante devra être précisée par
l'utilisateur si le niveau de détail actuel (titre de la table ci-dessus)
ne suffit pas à démarrer sans clarification — ce qui a été le cas pour
l'étape 15, dont la portée a donc été définie par ingénierie plutôt que
par un cahier des charges externe.

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
