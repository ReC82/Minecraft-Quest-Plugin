# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 18:59
* Sujet : #567 (#228-P4) — portails : conditions d'accès vérifiées par le serveur, simulation AUTORISÉ / REFUSÉ, « Téléporter pour test »
* Statut : PARTIAL (livré, déployé et relu sur DEV en lecture seule ; validation en jeu soumise au feu vert du propriétaire)
* Branche Git : `feature/567-portails-conditions` (PR #706, fusionnée dans `chore/257-ci-github-actions` → `fc1fb943`) ; rapport : `docs/567-rapport`
* Commit actuel si disponible : `fc1fb943`
* Début de la tâche : 2026-10-21 17:57:56
* Fin de la tâche : 2026-10-21 18:59:51
* Durée totale : 01:01:55

## Demande
Ticket #567, lancé par le superviseur lqorch : extraire l'évaluation des conditions de `DialogueSessionEngine` dans un
évaluateur partagé (QUEST_STATE, HAS_ITEM, HAS_PERMISSION, VARIABLE_EQUALS, HAS_MAIN_CLAIM), ajouter le niveau RPG
(`RequiredLevelRule`, #532), réutiliser le délai `portal_cooldowns` ; un message de refus par condition (StyleField) ;
simulation AUTORISÉ / REFUSÉ sans téléportation ; « Téléporter pour test » séparé, avec confirmation forte et permission
`PORTAL_TEST` ; gardes Claims / Wild conservés en tête de chaîne.
Autorisation du propriétaire du 20/10 : **tests simulés uniquement**. Aucune condition ni configuration de portail réel,
aucune simulation sur un joueur connecté, aucune téléportation.

## Analyse
- Un seul moteur : les conditions vivent dans `world-portals/<id>.yml`, section `access:` (même registre que #565/#566).
  Lecture par le parseur des dialogues (`DialogueConditionYaml` → `DialogueDefinitionParser#parseCondition`) : aucune
  seconde syntaxe.
- Gardes en tête : les conditions sont évaluées dans `teleport()`, donc **après** les gardes Claims / Wild, y compris
  quand un garde relance le passage (« Continuer » du Wild → `teleportNow`). Inconvénient assumé : pour un portail vers le
  Wild avec conditions, la fenêtre d'avertissement s'affiche avant un éventuel refus.
- Non-régression : un portail sans condition garde exactement le chemin d'avant, synchrone et dans le même tick
  (`hub_to_wild` / `hub_to_claims` n'en ont pas). Sans condition, la section `access:` n'est ni écrite ni comptée
  dans l'empreinte.
- Sécurité : section illisible ou type non proposé → **fichier ignoré** (jamais chargé sans ses conditions) ; erreur de
  lecture ou vérificateur absent → passage refusé. Conditions modifiables seulement sur un portail **non actif**
  (`ACTIVE_ACCESS_LOCKED`), comme la zone et l'arrivée.
- Coût (débit de pièces) **non repris** : le débit « seulement après succès garanti » avec une téléportation asynchrone
  demande sa propre conception anti-duplication. Il reste dans `PortalService` pour les portails de zone.
- Rendu local (instance isolée, port 8097, données synthétiques) : deux défauts trouvés à l'écran puis corrigés. (1) La
  balise `<level>` faisait basculer le champ stylé en mode avancé ; les jetons deviennent `%level%` / `%seconds%`.
  (2) Le verdict apparaissait deux fois (« REFUSÉ REFUSÉ »).

## Travail effectué
Plugin :
- `dialogue.PlayerConditionEvaluator` : évaluateur unique, extrait de `DialogueSessionEngine` (commit refactor séparé,
  comportement identique).
- `travel.model.PortalAccess` (niveau, règles + messages, délai, limites : 8 conditions, niveau 1–1000, délai ≤ 86 400 s,
  message ≤ 200 caractères), `travel.PortalAccessYaml` (lecture/écriture/forme canonique), champ
  `WorldPortalDefinition.access`.
- `travel.PortalAccessChecker` : contrôles `LEVEL` / `CONDITION` / `COOLDOWN`, tous évalués (verdict complet) ;
  délai dans `portal_cooldowns`, clé `world:<id>`, avec un cache mémoire ; `recordPassage` seulement après une
  téléportation réussie.
- `WorldPortalTeleportListener` : contrôle au passage (asynchrone si conditions, un seul à la fois par joueur, refus =
  le joueur reste sur place avec le message de la première condition non remplie, ligne en console),
  `testTeleport` (chemin réel, sans délai, quel que soit l'état du portail).
- `WorldPortalAdminService.setAccess` (`Kind.ACCESS`, `UNKNOWN_QUEST`, `INVALID_VARIABLE` via `VariableTypes`,
  journal `ACCESS`), empreinte et résumé étendus.
- Agent : `portal.access`, `portal.simulate` (lecture seule, joueur connecté), `portal.test` (personnage imposé par
  le panel, `confirm_id` = identifiant recopié) ; `portal.list` expose `conditions` et `access`.
PlugAdmin :
- Fiche portail : formulaire « Conditions d'accès » (portail non actif ; niveau, délai, lignes de condition, case
  « Inverser », messages via StyleField), « Simuler un passage » (`PORTAL_READ`, dernier résultat détaillé),
  « Téléporter pour test » (`PORTAL_TEST`, personnage lié uniquement, case + identifiant recopié, dernier test 24 h).
- Permission `PORTAL_TEST` (propriétaire seul par défaut) ; `PanelApp` impose le personnage lié comme pour `claim.teleport`.

## Fichiers créés
`dialogue/PlayerConditionEvaluator.java`, `dialogue/DialogueConditionYaml.java`, `travel/model/PortalAccess.java`,
`travel/PortalAccessYaml.java`, `travel/PortalAccessChecker.java` ; tests `travel/PortalAccessCheckerTest.java`,
`travel/WorldPortalAccessListenerTest.java`.

## Fichiers modifiés
`DialogueSessionEngine`, `DialogueDefinitionParser`, `WorldPortalDefinition`, `WorldPortalRegistry`,
`WorldPortalAdminService`, `WorldPortalTeleportListener`, `PortalOverview`, `RPGQuestBootstrap`, `AgentActionType`,
`AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`, `PortalAdminParams`, `PortalListDetails` ; tests
`WorldPortalAdminServiceTest`, `PortalAdminParamsTest` ; panel `Permission`, `AgentActionCatalog`, `PanelApp`,
`AgentPages`, `PortalPages`, `PortalForms`, `docs/portails.md`, `PortalsPageTest` ; docs `RPGQUEST_BIBLE.md`,
`current_state.md`, `docs-site/worlds.html`, `MANUAL_TEST_PLAN.md` (TC-394), `deployment/SERVER_CHANGELOG.md`,
registre `docs/SUIVI_VALIDATIONS_DEV.md` (PR de rapport).

## Base de données / migrations
Aucune migration : la table existante `portal_cooldowns` est réutilisée (clé `world:<id>`).

## Configuration / données
Aucune clé de configuration. Section facultative `access:` (aucune sur DEV). Sur DEV, Claude n'a envoyé qu'un
`portal.list` (lecture). Aucun portail, fichier, joueur, délai ni progression touché.

## Tests automatiques
- Locaux ciblés : `travel.*`, `dialogue.*`, `PortalAdminParamsTest` → 477 tests, 3 échecs de préparation (profil
  joueur absent, clé étrangère) corrigés, puis `PortalAccessCheckerTest` 8/8 et `WorldPortalAccessListenerTest` 9/9 ;
  panel `PortalsPageTest`, `AgentActionCatalogTest`, `RolePermissionMatrixTest`, `DocsPagesTest`,
  `DiagnosticHelpTest` → verts.
- CI GitHub PR #706 : plugin, control-panel, web-api, scripts, artefacts → **verts** (12 contrôles) ; CI de la fusion
  `fc1fb943` → **verte**.

## Tests manuels à effectuer
TC-394 — **PENDING MANUAL VALIDATION**. Étape 1 en lecture seule ; étapes 2 à 7 (brouillon de test avec conditions,
simulation sur son propre compte, traversée, « Téléporter pour test ») **seulement sur feu vert du propriétaire**.
Claude n'a fait aucun test en jeu.

## Résultat attendu
`hub_to_wild` / `hub_to_claims` inchangés. Un portail à conditions refuse proprement un joueur qui ne les remplit pas
(il reste sur place, avec le message de la condition), laisse passer les autres et démarre le délai. La simulation ne
déplace personne.

## Reset / retour à l'état initial
Enregistrer le formulaire des conditions sans aucune ligne, ni niveau, ni délai : le fichier redevient identique à avant.
Supprimer un brouillon de test depuis sa fiche.

## Déploiement VeryGames
**Effectué le 21/10 vers 18:57.**
- JAR `fc1fb943` via `scripts/deploy-verygames.sh -y` (CI verte sur la fusion) : « JAR en ligne : 4139426 octets
  (== local) », SHA-256 `60f9f842c8e6…`, **retéléchargé et identique**. Sauvegarde
  `rpgquest-20261021T165646Z-predeploy.jar` (4098255 o, plus petite que le nouveau JAR).
- Aucun joueur connecté → `scripts/verygames-restart.sh --timeout 300` : arrêt puis retour ONLINE.
- PlugAdmin `fc1fb943` via `scripts/plugadmin/deploy.sh` : `/health` ONLINE ; l'aide servie contient « Simuler un
  passage » / « Téléporter pour test ».
- Relu sur DEV (`portal.list`, lecture seule) : 2 portails ACTIFS, aucune condition, zones et stratégies inchangées,
  **empreintes identiques** au relevé de 12:19 (`87276553…`, `abbf0fe0…`), aucun fichier ignoré.
### À transférer
JAR RPGQuest `fc1fb943` ; PlugAdmin `fc1fb943` (AWS).
### Ne PAS transférer/altérer
`world-portals/*.yml`, `config.yml`, `data.db`.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis redémarrage ; PlugAdmin : `scripts/plugadmin/rollback.sh`.
**Attention** : l'ancien JAR ignore `access:`. Un portail à conditions encore actif deviendrait libre d'accès : le
désactiver avant un rollback (aucun sur DEV aujourd'hui).

## Logs / diagnostic
Refus : `Portail simple <id> : passage refusé pour <joueur> — <condition>.` (INFO). Conditions illisibles :
`… conditions d'accès de <joueur> non vérifiables — passage refusé.` (ERROR). Test : `… téléportation de test de
<joueur> demandée depuis PlugAdmin.`

## Documentation mise à jour
Bible (section #567), `current_state.md`, `docs-site/worlds.html`, aide panel `portails.md`, TC-394,
`SERVER_CHANGELOG.md`, registre de validation.

## Limitations / travail restant
- Pas de coût en pièces pour les portails entre mondes (voir Analyse).
- Formulaire long sur mobile (chaque ligne garde tous ses champs : pas de JavaScript pour masquer ceux qui ne
  servent pas, à cause de la CSP).
- Gardes Claims / Wild non simulés ; pour un portail vers le Wild avec conditions, la fenêtre d'avertissement précède
  un éventuel refus.
- Saisie de quête et de matériau en texte libre (validée par le serveur), sans liste de choix.

## Prochaine étape suggérée
Propriétaire : TC-394 étape 1 (lecture seule), puis feu vert éventuel pour un brouillon de test. Superviseur : #569
(messages par portail) ou #568.
