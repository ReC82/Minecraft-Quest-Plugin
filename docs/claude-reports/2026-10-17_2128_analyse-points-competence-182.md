# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-17
* Heure : 21:28
* Sujet : Issue #182 — PNJ de ville et points de compétence : analyse de conception — analyse seulement
* Statut : DONE (analyse livrée ; ticket en attente de décisions du propriétaire)
* Branche Git : `docs/182-analyse-points-competence` (depuis `chore/257-ci-github-actions` `3277564`)
* Commit actuel si disponible : voir la PR de ce rapport
* Début de la tâche : 2026-10-17 21:26:20
* Fin de la tâche : 2026-10-17 21:28:13
* Durée totale : 00:01:53

## Demande

Session autonome `lqorch` sur #182, avec consigne du propriétaire (16/10) : **analyse seulement** —
auditer l'existant, rédiger options, dépendances, risques, découpage en lots et questions à
trancher, en commentaire sur le ticket et dans ce rapport. Aucune implémentation, aucune PR de
code, aucun déploiement.

## Analyse

Audit du code de la branche d'intégration (`3277564`) :

* `SkillType` (GLOBAL, COMBAT, MINING, FARMING, FISHING, EXPLORATION) + `ProgressionService` :
  le code appelle déjà « skill » les pistes d'XP RPG (#183) → collision de vocabulaire avec les
  « points de compétence » de #182 ; un nom distinct est proposé (« points de talent »).
  `hasLevel` n'est utilisé nulle part.
* Cœurs de Stan (#166) : `HeartReward` à clé de droit stable (`city:<id>` possible),
  `player_heart_grants` (V36, `INSERT OR IGNORE`), `HeartBonusService` (un modificateur
  d'attribut à clé fixe recalculé depuis la base) → modèle direct pour la source de points et
  les effets permanents.
* Villes : `village_centers.central_npc_id` (V37, #322) → le mentor peut être le PNJ central,
  sans nouvelle table d'association.
* `RewardType` : un type `TALENT_POINT` de la même forme que `HEART` suffit comme source.
* Rôles de PNJ extensibles (#127), journal à onglets (`QuestJournalUi`) réutilisables pour une
  interface sans commande joueur.
* `reward_entitlements` (#422) jugé inadapté (remises externes), non retenu.
* **Constat annexe** : `PortalService` (l. 180) et `MerchantTradeService` (l. 416) contrôlent
  `required-level` avec `player.getLevel()` (niveau vanilla), figé depuis #183 → ces conditions
  ne peuvent plus être remplies par un nouveau joueur. Ticket `fix` séparé recommandé ; rien
  corrigé ici (hors périmètre, analyse seulement).

Conclusions (détail dans le commentaire GitHub) : option A recommandée (un mentor par ville,
1 point par ville via récompense idempotente, arbre commun en `talents.yml`), options B
(maîtres spécialisés) et C (points par niveau) en évolutions ; modèle de données
(`player_talent_grants`, `player_talent_spends`, solde recalculé) ; effets V1 limités aux
modificateurs d'attributs à clé fixe et aux multiplicateurs d'XP ; risques (cumul, cœurs,
double-clic, arène/boss, changement de définition, vocabulaire) ; 4 lots ; 8 questions.

## Travail effectué

* Commentaire d'analyse publié sur #182 :
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/182#issuecomment-6239550148
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne 27 (#182, « Bloqué — décisions
  propriétaire », rien à tester en jeu) et dépendance 13 (socle prévu + constat `required-level`).
  La version de `main` (18:54) est plus ancienne que celle de la branche d'intégration, ses
  éditions y étaient déjà reportées.
* Ce rapport et son entrée d'index.

## Fichiers créés

* `docs/claude-reports/2026-10-17_2128_analyse-points-competence-182.md`

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

Aucun : rien n'est livré en jeu. Prochaine étape = décisions du propriétaire sur #182.

## Résultat attendu

Le propriétaire dispose de l'état réel des briques réutilisables et des questions à trancher
avant tout code.

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

`docs/SUIVI_VALIDATIONS_DEV.md`, index des rapports. `docs/PROGRESSION.md` et la Bible ne
changent pas : aucun comportement nouveau.

## Limitations / travail restant

* Toute implémentation attend les réponses aux 8 questions de #182.
* Constat `required-level` vanilla (portails, offres marchandes) à transformer en ticket `fix`.

## Prochaine étape suggérée

Après décisions : lot 1 (récompense `TALENT_POINT`, tables, journal/HUD, champ PlugAdmin) sur
`feature/182-points-talent`.
