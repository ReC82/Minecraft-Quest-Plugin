# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-17
* Heure : 21:34
* Sujet : Issue #247 — EPIC quêtes communautaires (contributions globales, récompenses proportionnelles, construction déclenchée) : analyse de conception — analyse seulement
* Statut : DONE (analyse livrée ; ticket en attente de décisions du propriétaire)
* Branche Git : `docs/247-analyse-quetes-communautaires` (depuis `chore/257-ci-github-actions` `0478809`)
* Commit actuel si disponible : voir la PR de ce rapport
* Début de la tâche : 2026-10-17 21:29:00
* Fin de la tâche : 2026-10-17 21:35:16
* Durée totale : 00:06:16

## Demande

Session autonome `lqorch` sur #247, consigne du propriétaire (16/10) : **analyse seulement** —
auditer l'existant, rédiger options, dépendances, risques, découpage en lots et questions à
trancher, en commentaire sur le ticket et dans ce rapport. Aucune implémentation, aucune PR de
code, aucun déploiement.

## Analyse

Audit du code de la branche d'intégration (`0478809`, `SchemaMigrator.CURRENT_VERSION = 44`) :

* **Remise PNJ #123** : `DELIVER_QUEST_ITEMS` → `QuestProgressEngine.deliverTo`, verrou par
  joueur, `QuestItemWithdrawal.withdraw` (jamais plus que demandé). Tout est par joueur ; le
  retrait et `persistCounter` (asynchrone, erreur seulement journalisée) ne forment pas une
  transaction ; objets à identité PDC refusés.
* **Événements #57** : `EventRanking.COMMUNITY_GOAL` + `EventReward.Kind.COMMUNITY` = compteur
  global de kills existant, mais déclenché en fin de fenêtre, récompense identique, aucune
  métrique de don.
* **Kills légitimes** : `stats/ActivityListener` a les bons filtres (spawner, créatif, admin,
  mob de test, PvP) ; `QuestEntityDeathListener` n'en a aucun.
* **Entitlements #422** : idempotents, nouvelle source = valeur d'enum ; ne paient ni pièces ni
  XP. Pièces idempotentes seulement via `quest_reward_grants`.
* **Bâtiments #234** : `BuildingPlacementService.place(siteId, buildingId, placedBy)` —
  sauvegarde avant collage, historique, version/SHA enregistrés ; mais pas de version épinglée
  en entrée, contrôle de chevauchement limité aux autres poses (pas claims/zones), verrou
  `inFlight` en mémoire, et si l'enregistrement échoue après collage le bâtiment reste posé
  (message « retour arrière à la main »).

Conclusions (détail dans le commentaire GitHub) : option A recommandée (domaine `community`
dédié, tables campagne / journal de contributions à clé unique / paiements, réutilisation des
briques ci-dessus), B (étendre #57) et C (objectif partagé dans le moteur individuel) écartées ;
dons par intention journalisée + retrait exact + restitution sécurisée ; kill crédité une fois au
tueur (`kill:<entityUuid>`) ; parts proportionnelles calculées une fois sur le journal figé ;
effet bâtiment avec `effect_state` persistant, mode manuel par défaut ; 6 lots (socle, dons,
combat, proportionnel, bâtiment, création PlugAdmin/IA) ; 7 risques ; 10 questions.

## Travail effectué

* Commentaire d'analyse publié sur #247 :
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/247#issuecomment-6239676221
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne 28 (#247, « Bloqué — décisions
  propriétaire », rien à tester en jeu) et dépendance 14 (lot bâtiment ↔ #94/#472 et
  durcissement #234 ; entitlements sans pièces/XP). Les éditions de `main` sur le registre
  étaient déjà reportées dans la branche d'intégration (vérifié par diff).
* Ce rapport et son entrée d'index.

## Fichiers créés

* `docs/claude-reports/2026-10-17_2134_analyse-quetes-communautaires-247.md`

## Fichiers modifiés

* `docs/SUIVI_VALIDATIONS_DEV.md`
* `docs/claude-reports/README.md`

## Base de données / migrations

Aucune. (Proposition seulement : une migration V45+ au lot 1.)

## Configuration / données

Aucune.

## Tests automatiques

Aucun (documentation seule, PR sans CI selon le filtre `paths`).

## Tests manuels à effectuer

Aucun : rien n'est livré.

## Résultat attendu

Le propriétaire répond aux 10 questions du commentaire ; le lot 1 (socle) peut alors être
spécifié et mis en file.

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

* Les constats sur `BuildingPlacementService` (protection claims/zones, verrou persistant,
  fiche après collage) ne sont pas corrigés ici ; ils relèvent du lot bâtiment ou d'un ticket
  `fix` séparé sur #234.
* Aucune implémentation tant que les décisions ne sont pas prises.

## Prochaine étape suggérée

Décisions du propriétaire sur #247, puis lot 1 (socle : définitions, tables, machine à états,
PlugAdmin lecture/activation, diagnostics).
