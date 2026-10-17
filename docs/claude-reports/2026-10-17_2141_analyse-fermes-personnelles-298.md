# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-17
* Heure : 21:41
* Sujet : Issue #298 — monde de fermes personnelles évolutives (zones à débloquer, élevages, inspiration Sunflower Land) : analyse de conception — analyse seulement
* Statut : DONE (analyse livrée ; ticket en attente de décisions du propriétaire)
* Branche Git : `docs/298-analyse-fermes-personnelles` (depuis `chore/257-ci-github-actions` `09f37c0`)
* Commit actuel si disponible : voir la PR de ce rapport
* Début de la tâche : 2026-10-17 21:36:10
* Fin de la tâche : 2026-10-17 21:40:40
* Durée totale : 00:04:30

## Demande

Session autonome `lqorch` sur #298, consigne du propriétaire (16/10) : **analyse seulement** —
auditer l'existant, rédiger options, dépendances, risques, découpage en lots et questions à
trancher, en commentaire sur le ticket et dans ce rapport. Aucune implémentation, aucune PR de
code, aucun déploiement.

## Analyse

Audit de la branche d'intégration (`09f37c0`, `SchemaMigrator.CURRENT_VERSION = 44`). Aucun
code ni document « ferme » existant. Briques réutilisables :

* **Monde `claims`** (`ClaimsWorldRulesListener`) : sans dégâts joueur ni hostiles, construction
  interdite hors claim ; **`ClaimArrivalResolver`** : attribution d'emplacement sûr ;
  **`ClaimTier`** : réserve fixe 100×100 ouverte par paliers idempotents (modèle des zones
  verrouillées) ; **`VaultEntitlements`** (#106) : déblocage par variables joueur.
* **Bâtiments** `BuildingPlacementService` (#213/#234) et générateur V2 (#94), avec les limites
  déjà relevées sur #247 (pas de contrôle claims/zones, verrou en mémoire, pose admin seulement).
* **Économie** : `EconomyService.debit` (transaction SQL, pas de clé d'opération), catalogues
  marchands `BUY` (#17), prix #336, remise sûre `ItemDelivery` (#318), quêtes périodiques #15.
* **Progression** : `SkillType.FARMING` et `FarmingXpListener` (cultures mûres seulement) ;
  aucun objectif HARVEST/BREED/SHEAR/MILK.
* **Minuteur persistant** `ResourceNodeService` (échéances en base, chunk chargé seulement).
* Absents : simulation hors ligne, plafonds d'animaux, règles entonnoirs/redstone, modèle
  générique de politique de monde, actions PlugAdmin claims/mondes.

**Constat de protection** (indépendant de #298) : `ClaimProtectionListener` n'a pas de
`PlayerInteractEntityEvent` (un non-membre peut traire/tondre/faire se reproduire/attacher les
animaux d'un claim), pas de gestion des seaux ni du piétinement ; `onInteract` sort dès que
`getHand() != HAND`, ce qui semble rendre la branche `PHYSICAL` (plaques de pression)
inopérante — **non vérifié par test**, ticket `fix(claims)` séparé recommandé (lot 0).

Conclusions (détail dans le commentaire GitHub) : hébergement **B** recommandé (monde `farming`
dédié, domaines sur grille déterministe à réserve fixe, enregistrement `farm_domains` distinct
des claims mais réutilisant leur code), A (dans le monde `claims`) viable, C (monde par joueur)
écartée ; production **hybride** (animaux réels décoratifs marqués PDC et plafonnés, production
métier calculée à la collecte depuis un horodatage en base → juste hors ligne, sans tick ni chunk
forcé, objets métier PDC remis par `ItemDelivery` avec clé de collecte unique) ; déblocage =
ligne à clé unique + débit dans la même transaction ; 7 lots (0 fix claims, 1 socle, 2 potager +
zones, 3 poulailler = MVP, 4 vaches + commandes, 5 PlugAdmin, 6+ autres filières/social) ;
protocole d'évaluation du fun ; 5 risques ; 10 questions.

## Travail effectué

* Commentaire d'analyse publié sur #298 :
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/298#issuecomment-6239774274
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne 29 (#298, « Bloqué — décisions
  propriétaire », rien à tester en jeu) et dépendance 15 (constat de protection des claims).
  Les éditions de `main` sur le registre étaient déjà reportées dans la branche d'intégration
  (vérifié : TC-351 et #15 présents, dernière édition d'intégration plus récente).
* Ce rapport et son entrée d'index.

## Fichiers créés

* `docs/claude-reports/2026-10-17_2141_analyse-fermes-personnelles-298.md`

## Fichiers modifiés

* `docs/SUIVI_VALIDATIONS_DEV.md`
* `docs/claude-reports/README.md`

## Base de données / migrations

Aucune. (Proposition seulement : migration V45+ `farm_domains` / `farm_unlocks` au lot 1.)

## Configuration / données

Aucune.

## Tests automatiques

Aucun (documentation seule, PR sans CI selon le filtre `paths`).

## Tests manuels à effectuer

Aucun : rien n'est livré.

## Résultat attendu

Le propriétaire répond aux 10 questions du commentaire ; le lot 0 (`fix(claims)`) peut être mis
en file indépendamment, le lot 1 (socle domaine) ensuite.

## Reset / retour à l'état initial

Sans objet.

## Déploiement VeryGames

### À transférer

Rien.

### Ne PAS transférer/altérer

Sans objet.

### Redémarrage requis

Non.

### Migration automatique

Non.

## Rollback

Sans objet (documentation seule).

## Logs / diagnostic

Sans objet.

## Documentation mise à jour

Registre de validation DEV, index des rapports.

## Limitations / travail restant

* Le constat sur `ClaimProtectionListener` n'est ni corrigé ni prouvé par un test ici.
* Aucune implémentation tant que les décisions ne sont pas prises.

## Prochaine étape suggérée

Décisions du propriétaire sur #298 ; ouvrir un ticket `fix(claims)` pour la protection des
animaux, seaux et piétinement.
