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
| 15 | Marché entre joueurs | TODO |
| 16 | Portails et téléportation | TODO |
| 17 | Claims de terrain | TODO |
| 18 | Mobs spéciaux vanilla | TODO |
| 19 | XP RPG | TODO |
| 20 | Backpacks | TODO |
| 21 | API et site web read-only | TODO |
| 22 | Boutique web et livraison sécurisée | TODO |
| 23 | Prototype de mod client séparé | TODO |

## Étape en cours

### Étape 15 — Marché entre joueurs

Branche attendue : `feature/15-player-market`

Pas encore de cahier des charges détaillé reçu en session pour cette
étape ; auditer `docs/ECONOMY.md`/`economy.EconomyService` avant de
commencer (le marché entre joueurs doit très probablement réutiliser
`EconomyService`/`WalletRepository` plutôt que réinventer un mécanisme de
paiement séparé).

### Dernière observation (2026-08-07, reprise directe à l'étape 14)

Étape 14 (Économie et marchands PNJ) confirmée `DONE` : build vert, 320+
tests verts (dont `WalletRepositoryTest`, `MerchantDefinitionParserTest`,
`MerchantLoaderTest`, `MerchantTradeServiceTest`, et les ajouts à
`DialogueDefinitionParserTest`/`DialogueSessionEngineTest` pour l'action
`OPEN_MERCHANT`), démarrage réel vérifié via `runServer` (portefeuille,
marchand d'exemple chargé, ordre de service correct). Portée réalisée :
`database.WalletRepository` (migration V4, `wallets`/`transactions`,
opérations réellement atomiques), `economy.EconomyService`,
`/money`/`/money pay`/`/money admin`, `economy.merchant` (modèle YAML,
chargeur deux phases, `MerchantTradeService` avec vitrine en inventaire et
anti-duplication achat/vente asymétrique), nouvelle action de dialogue
`OPEN_MERCHANT` (aucun système d'identification de PNJ parallèle, comme
demandé), `/merchant reload|validate|list`, `docs/ECONOMY.md`,
`MERCHANT_FORMAT.md`, section `economy` de `docs/ARCHITECTURE.md`.
Intégration Vault volontairement **préparée mais non câblée** (voir
« Limites connues » de `docs/ARCHITECTURE.md` et le plan d'intégration dans
`docs/ECONOMY.md`) — pas de dépendance externe ajoutée sans besoin réel.

Étapes 1 à 14 confirmées `DONE`. Aucun cahier des charges détaillé n'a été
retrouvé pour les étapes 15 à 23 dans le dépôt (`.ai/PROMPTS/` ne contient
qu'un `README.md` placeholder) — une session « mode nuit » antérieure
(2026-08-07) mentionne l'avoir reçu en conversation, mais celle-ci n'a
laissé aucun `HANDOFF.md`/fichier de reprise, et le contenu exact n'est
donc plus disponible pour cette session. La prochaine étape (15, marché
entre joueurs) devra être précisée par l'utilisateur si le niveau de détail
actuel (titre de la table ci-dessus) ne suffit pas à démarrer sans
clarification.

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
Dernier commit: (voir git log — commit de l'étape 14 à suivre)
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
