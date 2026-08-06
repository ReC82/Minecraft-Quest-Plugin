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
| 14 | Économie et marchands PNJ | TODO |
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

### Étape 14 — Économie et marchands PNJ

Branche attendue : `feature/14-economy-merchants`

Monnaie principale SQLite + table `transactions` (id, joueur, type,
montant, contexte, timestamp), service économique indépendant de Paper,
opérations débit/crédit atomiques, `/money`/`/money pay <joueur> <montant>`,
marchands PNJ (vendre/acheter vanilla et custom, prix, quantité,
permission, quête, niveau, UI sécurisée) reliés aux dialogues existants,
anti-duplication (montant négatif, dépassement, double-clic), interface
Vault optionnelle préparée, `docs/ECONOMY.md`. Voir cahier des charges
complet reçu en session (mode nuit, 2026-08-07) pour le détail complet.

### Dernière observation (session mode nuit, 2026-08-07)

Étapes 1 à 13 confirmées `DONE` (build vert, 282 tests verts, docs à jour,
commit par étape). Cahier des charges détaillé reçu pour les étapes 11 à 23
(voir historique de conversation — non dupliqué ici pour éviter deux
sources concurrentes, conformément à la règle « Git, code et tests priment
sur ROADMAP »). Progression dans l'ordre, une branche par étape.

Note pour l'étape 14 : les marchands PNJ doivent se relier au système de
dialogues existant (`dialogue.session.DialogueSessionEngine`) plutôt que
créer un système d'interaction PNJ parallèle — voir `docs/ARCHITECTURE.md`,
section `dialogue`, pour la convention déjà établie (nom personnalisé
d'entité = identification).

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
