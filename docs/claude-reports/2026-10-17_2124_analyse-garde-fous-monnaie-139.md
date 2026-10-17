# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-17
* Heure : 21:24
* Sujet : Issue #139 — analyse des garde-fous de la monnaie physique (duplication, conversion, usages vanilla) — analyse seulement
* Statut : DONE (analyse livrée ; ticket en attente de décisions du propriétaire et de #138)
* Branche Git : `docs/139-analyse-garde-fous-monnaie` (depuis `chore/257-ci-github-actions` `20738f9`)
* Commit actuel si disponible : voir la PR de ce rapport
* Début de la tâche : 2026-10-17 21:22:38
* Fin de la tâche : 2026-10-17 21:26:30
* Durée totale : 00:03:52

## Demande

Session autonome `lqorch` sur #139, avec consigne du propriétaire (16/10) : **analyse seulement** —
auditer l'existant, rédiger options, dépendances, risques, découpage en lots et questions à
trancher, en commentaire sur le ticket et dans ce rapport. Aucune implémentation, aucune PR de
code, aucun déploiement.

## Analyse

Audit du code de la branche d'intégration (`20738f9`), complémentaire de l'analyse #138
(rapport 2026-10-17_2121) :

* Identité des objets personnalisés par PDC seul (`YamlCustomItemRegistry.identify`) : un objet
  vanilla renommé n'est jamais reconnu ; une vraie monnaie renommée reste valide mais n'est plus
  `isSimilar` (refusée par un rachat marchand `CUSTOM`).
* `RecipeCraftGuardListener` protège les recettes RPGQuest, **pas les recettes vanilla** (sortie
  immédiate) ; le Crafter 1.21 n'est pas gardé.
* Aucun listener enclume / meule / forge / four / composteur / alambic dans le plugin.
* `SoulboundItemService` : drop refusé, retrait des drops et restitution au respawn — liste de
  restitution **en mémoire** seulement ; ne couvre ni coffres, ni entonnoirs, ni marché.
* `MarketService.sell` sérialise l'objet tenu sans aucun filtre : monnaie physique vendable ;
  constat secondaire : les objets soulbound y sont déjà vendables (fuite existante, hors #139).
* Rachat marchand : retrait synchrone avant crédit asynchrone, restitution si échec (double-clic
  sûr) ; une offre vanilla du même matériau ne prend pas la monnaie (meta différente).
* `EconomyService.credit/debit` sans identifiant d'opération : seul `payQuestReward` est
  idempotent (`grantId`) — le retrait/dépôt devra apporter sa clé d'opération.
* Journal `transactions` réutilisable (nouveaux types `CURRENCY_*` + `currency_id`, quantité,
  correlation id).
* Clonage externe (créatif, `/give`, plugins) impossible à empêcher : seule parade = registre
  d'émission et alerte d'écart.

Conclusions (détail dans le commentaire GitHub) : un `CurrencyIntegrityListener` unique branché
sur `PhysicalCurrencyService.isCurrency` (lot 1 de #138, prérequis) ; 7 garde-fous (craft vanilla
+ Crafter, enclume/meule/forge, four/composteur/placement génériques, marché, politique
drop/mort/stockage, journal, surveillance d'écart) ; tests MockBukkit listés ; découpage 2a
(gardes indépendantes de la politique) / 2b (politique) / 2c (surveillance + Diagnostics) ;
7 questions au propriétaire (drop, mort — redépôt automatique recommandé —, stockage, marché,
destruction, P2P vs `/money pay`, source de vérité après rollback).

## Travail effectué

* Commentaire d'analyse publié sur #139 :
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/139#issuecomment-6239500272
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne 26 (#138 + #139) complétée avec l'analyse
  #139 (toujours « Bloqué — décisions propriétaire », rien à tester en jeu) et dépendance
  économie complétée. Les éditions de `main` (#475 TC-351, #15 TC-350) étaient déjà reportées.
* Ce rapport et son entrée d'index.

## Fichiers créés

* `docs/claude-reports/2026-10-17_2124_analyse-garde-fous-monnaie-139.md`

## Fichiers modifiés

* `docs/claude-reports/README.md` (index)
* `docs/SUIVI_VALIDATIONS_DEV.md`

## Base de données / migrations

Aucune.

## Configuration / données

Aucune.

## Tests automatiques

Aucun : aucun code modifié (PR documentaire, pas de CI déclenchée).

## Tests manuels à effectuer

Aucun : rien n'est livré en jeu. Prochaine étape = décisions du propriétaire sur #138 puis #139.

## Résultat attendu

Le propriétaire dispose de la liste précise des protections existantes/manquantes et des
questions de politique à trancher avant tout code.

## Reset / retour à l'état initial

Sans objet.

## Déploiement VeryGames

### À transférer

Rien.

### Ne PAS transférer/altérer

Tout : aucun changement serveur.

### Redémarrage requis

Non.

### Migration automatique

Non.

## Rollback

Sans objet (documentation seulement).

## Logs / diagnostic

Sans objet.

## Documentation mise à jour

`docs/SUIVI_VALIDATIONS_DEV.md`, index des rapports. `docs/ECONOMY.md` et la Bible ne changent pas :
aucun comportement nouveau.

## Limitations / travail restant

* Toute implémentation attend les réponses aux questions de #138 et #139, puis le lot 1 de #138.
* Fuite existante hors périmètre signalée : objets soulbound vendables au `/market`.

## Prochaine étape suggérée

Après décisions : lot 1 de #138, puis lot 2a de #139 (gardes craft vanilla/Crafter,
enclume/meule/forge, marché, journal) sur `feature/139-garde-fous-monnaie`.
