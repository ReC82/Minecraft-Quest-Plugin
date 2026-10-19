# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 10:26
* Sujet : #542 — [Mobs][02] Socle des capacités composables et compatibilité des profils existants (EPIC #540)
* Statut : DONE (livré et déployé sur DEV ; validation en jeu PENDING MANUAL VALIDATION, TC-361)
* Branche Git : `feature/542-socle-capacites` → fusionnée dans `chore/257-ci-github-actions` (PR #575, merge `e4f7432`)
* Commit actuel si disponible : `e4f7432`
* Début de la tâche : 2026-10-19 09:38:53
* Fin de la tâche : 2026-10-19 10:26:57
* Durée totale : 00:48:04

## Demande
Session autonome lancée par le superviseur `lqorch` sur le ticket #542. Livrer le socle des capacités de
mobs selon les décisions Q1–Q11 de #541 approuvées par le propriétaire le 19/10 (consignées sur `main`,
commit `e52e0eb`) : registre/descripteurs, moteur unique, état « en combat », politique de zones,
plafonds/délais/nettoyage pour les 5 capacités existantes, compatibilité stricte (test « golden »),
aucune modification permanente du terrain, doc `ADDING_MOB_ABILITY.md`. Déploiement DEV sans modifier
aucun profil, sans activer de spawn ni lancer de boss. Ne pas mettre #543+ en file.

## Analyse
- État réel au départ : aucune branche/PR #542, intégration `b23dbda` ; aucun changement de code depuis le
  JAR en ligne sur DEV (`f2be026`, #557) — seulement des PR de documentation.
- Code d'avant : 5 capacités (`STRONGER_EXPLOSION`, `EXPLOSIVE_ON_ATTACK`, `SPLIT_ON_HIT`, `ENRAGED`,
  `SUMMON_ON_DAMAGE`) définies à 8 endroits chacune ; 5 listeners/services et 2 tâches d'1 s ; aucune
  notion commune d'activation/chance/délai ; délais de renforts jamais purgés ; renforts et divisions
  apparaissant sans consulter Hub/claims/zones ; Explosif au contact visant aussi PNJ et créatifs.
- Avant toute modification, le rendu YAML **du code d'origine** a été capturé pour 4 profils livrés et 3
  profils de type DEV : c'est la référence du test golden.
- Profils réellement présents sur DEV (lecture FTP seule, avant déploiement, rien écrit) : 8 profils, et
  **aucun n'a de capacité** — les copies DEV de `creeper_pig`, `red_creeper`, `golden_creeper` et
  `splitting_zombie` (réécrites par PlugAdmin, `spawn-chance` relevées à 0,4–0,5) n'ont plus de bloc
  `abilities`. Constat préexistant, sans lien avec #542, laissé tel quel (consigne : ne modifier aucun
  profil DEV). Avec le nouveau code, les 8 se chargent sans avertissement ni violation ; 6 se réécrivent
  octet pour octet, les 2 autres différaient déjà avant #542 (voir Limitations). Conséquence : sur DEV,
  le comportement en jeu est inchangé par construction, et le test manuel passe par des profils de test.

## Travail effectué
1. **Modèle** : `AbilityOptions` (enabled / chance / cooldown-seconds, défauts = comportement d'avant) porté
   par chaque record ; `SummonOnDamageAbility` range sa chance et son délai dans ces options (mêmes clés,
   toujours obligatoires) et gagne `despawn-with-owner` (Q7, défaut `false` = les renforts restent).
2. **Registre `MobAbilityCatalog`** : un `AbilityDescriptor` par type (libellé, résumé, catégorie, statut,
   PASSIVE/ACTION, déclencheurs, répétable, créatures concernées, `AbilityParam` typés et bornés,
   préréglages Doux/Normal/Brutal, budget, `WorldMutation.NONE` imposé). Lecture et écriture génériques ;
   `SpecialMobDefinitionParser`, `SpecialMobDefinitionYaml` et `SpecialMobSchema` (#214 : modèle, clés
   acceptées, invite IA) en sont des vues. Messages d'erreur d'avant conservés.
3. **Règles `MobAbilityRules`** (Q2/Q3/Q8) : 5 capacités max, pas de doublon, statut « en développement »
   réservé aux profils hors tirage naturel. Refus à l'import (`SpecialMobImport`) et à l'enregistrement
   PlugAdmin (`INVALID_ABILITIES`) ; avertissement console au chargement d'un ancien fichier, première
   instance seule active.
4. **`AbilityEngine`** remplace les 5 listeners/services : un listener + une tâche d'1 s ; instances actives
   dans l'ordre du profil ; délai et chance tenus par le moteur ; nettoyage à la mort,
   `EntityRemoveFromWorldEvent` et `stop()`. Exécuteurs `*Executor` = logique d'avant reprise à
   l'identique (clés PDC inchangées : `special-mob-enraged`, `special-mob-summon-owner`, profondeur/parent).
5. **`CombatState`** (Q4 : cible joueur valide + coup échangé < 10 s + portée) et **`AbilityLimits`**
   (3 renforts/s/monde, 32 actions en cours, une action par mob — Q5) ; aucune capacité actuelle n'est une
   action, l'infrastructure sert à #544+.
6. **`AbilityWorldPolicy`** : apparitions refusées dans le monde du Hub, un claim, une zone RPGQuest qui
   interdit les hostiles ; cibles = joueurs survie/aventure, ni PNJ (Citizens ou RPGQuest), ni créatif,
   ni spectateur (Q9). Câblée au démarrage (Hub lu dans la config, claims lus à l'appel).
7. **PlugAdmin → plugin** (`BukkitAgentActions.mergeFormAbilities`) : à l'enregistrement du formulaire,
   Enragé et Renforts gardent leur place et leurs réglages non édités (avant : déplacés en fin de liste).
   Aucun changement côté panel ; pas d'UI « Capacités » (Q10 sans objet dans ce lot).
8. Documentation : `docs/development/ADDING_MOB_ABILITY.md` (nouveau), `SPECIAL_MOB_FORMAT.md`, Bible §11,
   `docs-site/admin-testing.html`, `docs/ARCHITECTURE.md`, `docs/MANUAL_TEST_PLAN.md` (TC-361),
   `docs/deployment/SERVER_CHANGELOG.md`, registre `docs/SUIVI_VALIDATIONS_DEV.md`.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/mob/model/AbilityOptions.java`
- `src/main/java/com/lodygames/rpgquest/mob/ability/` : `AbilityContext`, `AbilityDescriptor`, `AbilityEngine`,
  `AbilityExecutor`, `AbilityLimits`, `AbilityParam`, `AbilityTrigger`, `AbilityValues`, `AbilityWorldPolicy`,
  `CombatState`, `MobAbilityCatalog`, `MobAbilityRules`, `EnragedExecutor`, `ExplosiveOnAttackExecutor`,
  `SplitOnHitExecutor`, `StrongerExplosionExecutor`, `SummonOnDamageExecutor`
- Tests : `mob/MobAbilityGoldenTest`, `mob/ability/AbilityEngineTest`, `MobAbilityCatalogTest`,
  `MobAbilityRulesTest` ; ressources `src/test/resources/mob-golden/{input,rendered}/`
- `docs/development/ADDING_MOB_ABILITY.md`, ce rapport

## Fichiers modifiés
- Supprimés : `EnragedAbilityService`, `ExplosiveOnAttackAbilityService`, `SplitOnHitAbilityListener`,
  `StrongerExplosionAbilityListener`, `SummonOnDamageAbilityListener`
- `mob/model/*Ability.java`, `MobAbility.java` ; `mob/SpecialMobDefinitionParser`, `SpecialMobDefinitionYaml`,
  `SpecialMobSchema`, `SpecialMobImport`, `SpecialMobLoader`, `SpecialMobLoadReport`, `SpecialMobRegistry`,
  `SpecialMobService` (commentaire) ; `bootstrap/RPGQuestBootstrap` ; `web/agent/BukkitAgentActions`
- Tests renommés/adaptés : `ExplosiveOnAttackExecutorTest` (+ créatif, + PNJ), `SplitOnHitExecutorTest`
  (+ zone protégée), `SpecialMobImportTest`, `CommunityBossServiceTest` (commentaire)
- Docs : voir « Documentation mise à jour »

## Base de données / migrations
Aucune. Schéma inchangé (aucune migration jugée nécessaire, conforme à la consigne).

## Configuration / données
Aucune clé de `config.yml`. Plafonds serveur en constantes (`AbilityLimits`). Format de profil : nouvelles
clés **facultatives** (`enabled`, `chance`, `cooldown-seconds` sur toute capacité ; `despawn-with-owner` sur
Renforts), jamais écrites si elles valent leur défaut. Aucun profil DEV modifié.

## Tests automatiques
- Local ciblé (`com.lodygames.rpgquest.mob.*` + `*BukkitAgentActionsMob*`, `RPGQUEST_TEST_MAX_HEAP=768m`) :
  **214 tests, 0 échec** (5 ignorés : limites MockBukkit préexistantes `setRemoveWhenFarAway` / `getPathfinder`,
  déjà ignorés avant).
- Nouveaux : golden 4, catalogue 11, règles 5, moteur 11, + 3 cas d'exécuteurs.
- Vérification ponctuelle non commitée : les 8 profils téléchargés de DEV relus par le nouveau code (voir Analyse).
- CI GitHub PR #575 : Scripts, Tests plugin (9 min), Tests control-panel, Tests web-api, Artefacts — **verte** ;
  CI du commit fusionné `e4f7432` verte (exigée par `deploy-verygames.sh --wait-ci`, run 39221163722).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — **TC-361** (`docs/MANUAL_TEST_PLAN.md`) : rafraîchir /mobs (8 profils, 0
rejet), importer deux profils de test BOSS de quête `zz542_fissible` (Division + Enragé) et `zz542_cochon`
(Explosif au contact), vérifier division/rage en survie et l'absence de cible en créatif, doublon refusé,
puis désactiver les profils de test. Aucun test en jeu n'a été effectué par Claude.

## Résultat attendu
Aucun changement visible pour les profils actuels, sauf : Cochon Creeper ignore les joueurs en créatif et
les PNJ ; aucune division/aucun renfort dans le Hub, un claim ou une zone sans hostiles ; au plus 3 renforts
par seconde et par monde ; un doublon de capacité est refusé à l'import et à l'enregistrement.

## Reset / retour à l'état initial
Rien à réinitialiser : aucune donnée créée, aucun profil modifié.

## Déploiement VeryGames
**Effectué le 19/10/2026 à 10:24** depuis le worktree propre détaché sur `e4f7432` :
`RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` (CI verte → `assemble` seul) →
« JAR en ligne : 3675485 octets (== local) », « Déploiement terminé » ; sauvegarde
`rpgquest-20261019T082350Z-predeploy.jar` (3 636 472 o, version `f2be026`). Seul LoDyMcFly était connecté :
**un** `scripts/verygames-restart.sh --timeout 300` → ONLINE. Contrôles après redémarrage : JAR distant
re-téléchargé, SHA-256 `19cb5f01c98e0820bbc203856db821b89876c94ae8fdeb160a802eeb60aa7b55` = local ; battement
d'agent ONLINE (08:24:59 UTC) ; action lecture seule `mob.list` (créée par `claude-542`) : SUCCESS,
8 profils, `hasIssues: false`. PlugAdmin non redéployé (inchangé).

### À transférer
Le JAR RPGQuest seul (`e4f7432`). PlugAdmin inchangé (aucun fichier `control-panel/` modifié).

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `mobs/*.yml` (profils, `community-bosses.yml`, `spawn-settings.yml`),
`state/community-bosses.yml`, mondes, autres plugins.

### Redémarrage requis
Oui (le JAR n'est lu qu'au démarrage).

### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh` (ou JAR précédent depuis la sauvegarde datée créée par le script, version
`f2be026`) puis un redémarrage. Aucune donnée à reprendre : les profils restent lisibles par l'ancien JAR (son parseur ignore
les nouvelles clés facultatives — seule leur prise en compte serait perdue).

## Logs / diagnostic
- Au chargement : `[fichier] Avertissement : …` si un ancien profil contient une capacité en double.
- `/rpgadmin mob metrics` : compteurs de déclenchement par type, inchangés.
- Journal de déploiement local : `/srv/rpgquest/542-deploy.log`.

## Documentation mise à jour
`docs/development/ADDING_MOB_ABILITY.md` (nouveau), `SPECIAL_MOB_FORMAT.md`, `docs/RPGQUEST_BIBLE.md` §11,
`docs-site/admin-testing.html`, `docs/ARCHITECTURE.md` (section « Capacités composables »),
`docs/MANUAL_TEST_PLAN.md` (TC-361), `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`
(ligne #542 + report de la section « Autorisations propriétaire — 19/10/2026 » de `main`).

## Limitations / travail restant
- **Défaut préexistant découvert (hors périmètre, non corrigé)** : `worlds`/`biomes`/`zones` sont rangés dans
  un `Set` dont l'ordre d'itération varie d'une JVM à l'autre ; un profil à plusieurs biomes peut donc être
  réécrit avec un ordre différent (ex. `claude_diag_boss.yml` sur DEV). Sans effet sur le jeu, mais diff
  bruyant à l'enregistrement ; à traiter dans un ticket séparé (un `LinkedHashSet` suffirait).
- `STRONGER_EXPLOSION` en double ne se multiplie plus (Q3) ; aucun profil livré ni DEV n'en a deux.
- Les avertissements de chargement ne sont visibles que dans la console, pas encore dans PlugAdmin (#543).
- Pas d'action agent `mob.ability.catalog` ni de page « Capacités » : prévues avec #543 (non mis en file).
- `CombatState` et le créneau d'action ne sont utilisés par aucune capacité actuelle (prévu pour #544+).
- **Constat DEV à arbitrer** : les 4 profils d'exemple sur DEV n'ont plus leurs capacités (voir Analyse) ;
  les rétablir est une décision du propriétaire (import ou édition), pas faite ici.

## Prochaine étape suggérée
Le propriétaire joue TC-361 (5 minutes, étapes 1 et 3 suffisent pour la compatibilité). #543 (page
Capacités + cartes dérivées du descripteur) reste en attente de son accord.
