# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-24
* Heure : 18:55
* Sujet : #321 — validation technique : un OP ne contourne pas implicitement l'accès au monde Claims
* Statut : DONE (validation technique) — ticket laissé ouvert pour décision du propriétaire
* Branche Git : fix/321-claims-op-acces (PR #825, fusion `567035a7` dans chore/257-ci-github-actions) ; rapport : docs/321-rapport
* Commit actuel si disponible : 567035a7
* Début de la tâche : 2026-10-24 18:33:13
* Fin de la tâche : 2026-10-24 18:55:19
* Durée totale : 00:22:06

## Demande
Ticket #321 (lqorch) : validation technique du contrôle d'accès au monde Claims pour les OP, selon la spec propriétaire du 24/10 (`2026-10-24-321-claims-op-validation.md`). Auditer le portail et tous les autres chemins de téléportation, prouver qu'aucune permission héritée de l'OP ne contourne implicitement les règles, exécuter une matrice OP/non-OP × bypass ON/OFF × `CLAIM_TIER_1` absent/false/true/invalide × claim possédé, conserver les droits débloqués légitimement, corriger toute régression. Interdit : toute écriture touchant LoDyMcFly. Ne pas fermer #321.

## Analyse
- Le code de production concerné (`claim/`, `permission/`, `travel/`, `plugin.yml`) est **identique** entre le JAR DEV `e9b4376a` et la tête de `chore/257-ci-github-actions` (`git diff --stat` vide) : le correctif #35/#321 du 14/10 (`f60eb263` et suivants) est bien celui qui tourne sur DEV.
- Règle réelle (inchangée, rien d'inventé) : accès au monde Claims si `CLAIM_TIER_1 == "true"` exact **ou** si le joueur possède déjà un claim principal. Aucune obligation d'Acte permanent. Seul contournement : session `/rpgadmin bypass on` (`AdminBypassMode`) **et** nœud `rpgquest.bypass.claimworld` ou ombrelle `rpgquest.admin.world`.
- Défauts `plugin.yml` : `rpgquest.bypass.*` = `false` ; `rpgquest.admin.world` et `rpgquest.admin.bypass` = `op`. Un OP peut donc *ouvrir* une session, mais rien ne la lui ouvre implicitement (état en mémoire, fermé à la connexion et au redémarrage).
- **Audit des chemins d'entrée** (15 chemins relevés) :
  - Portail simple Hub → Claims (`WorldPortalTeleportListener`) : `ClaimWorldAccessGuard` contrôle avant de bouger, et le bypass passe uniquement par `bypass.allows(...)`. **Aucun contournement implicite OP.**
  - Action PlugAdmin `claim.teleport` (`ClaimAdminTeleport`) : `bypass.allows`, sinon claim ou `CLAIM_TIER_1`. Aucun contournement implicite OP.
  - Voyages par objet (Pierre de retour, rune), pierres de voyage (waystones), secours du Hub, réapparition (forcée au spawn du Hub), balise « Mon claim » (son propre claim seulement) : ces chemins ne mènent pas vers un claim d'autrui.
  - Commandes d'administration `/rpgadmin world tp claims`, `/rpgadmin spawn|waystone tp`, `/claim admin tp <joueur>` : elles sont autorisées par l'ombrelle `rpgquest.admin.world` (défaut OP). Ce sont des **gestes administratifs explicites** (secours, diagnostic), distincts du portail joueur, comme le demandait le commentaire du 13/10. À l'arrivée, `ClaimWorldSafetyListener` applique la règle gameplay : un OP sans session, sans claim et sans `CLAIM_TIER_1 = true` est **renvoyé au Hub**. Ce filet couvre aussi `/tp` vanilla, Multiverse, les portails du Nether ou de l'End et la connexion directe dans le monde.
  - Portails historiques (`PortalService`) : aucun contrôle avant le passage. Le filet d'arrivée s'applique ensuite, pour tout le monde, OP ou non. Ce n'est pas propre aux OP.
  - Aucune lecture directe d'un nœud de bypass en dehors d'`AdminBypassMode`/`RpgPermissions` (recherche dans tout le code + nouveau test de garde).
- **Conclusion** : aucun contournement implicite par l'OP, aucune régression. **Aucun changement de code de production.**

## Travail effectué
- `ClaimWorldAccessGuardTest` : matrice paramétrée de **40 cas** (OP/non-OP × bypass demandé ON/OFF × `CLAIM_TIER_1` absente/`false`/`true`/`TRUE`/`oui` × claim possédé ou non). Elle vérifie aussi qu'un non-OP ne peut pas ouvrir de session, qu'aucune session n'est implicite, et que les droits d'administration de l'OP restent intacts. Le cas « claim possédé » efface ensuite la variable : la propriété seule suffit (droit légitime conservé).
- `ClaimWorldSafetyListenerTest` : matrice d'arrivée de **20 cas** (OP × bypass × `CLAIM_TIER_1`). Elle couvre tous les chemins qui ne passent pas par le portail : un OP sans session ni déblocage est renvoyé au Hub, une présence légitime ne l'est jamais et reçoit sa Pierre de retour.
- `BypassOnlyThroughAdminModeTest` (nouveau) : garde architecturale. Le test échoue si du code lit `hasBypass(`, `hasPermission(BYPASS_…)` ou `hasPermission("rpgquest.bypass.…")` en dehors de `RpgPermissions`/`AdminBypassMode`.
- `docs/MANUAL_TEST_PLAN.md` TC-302 : volet #321 sans second compte OP, et couverture automatique mise à jour.
- Environnement Paper isolé : **non utilisé, jugé inutile**. Les tests exécutent le vrai code des gardes avec une vraie base SQLite, et MockBukkit applique les défauts `op` de `plugin.yml` (la matrice vérifie que l'OP porte bien `rpgquest.admin.world`). Ce qu'un vrai Paper ajouterait (ordre réel des événements, Multiverse), le filet d'arrivée le couvre déjà.

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/permission/BypassOnlyThroughAdminModeTest.java`

## Fichiers modifiés
- `src/test/java/com/lodygames/rpgquest/claim/ClaimWorldAccessGuardTest.java`
- `src/test/java/com/lodygames/rpgquest/claim/ClaimWorldSafetyListenerTest.java`
- `docs/MANUAL_TEST_PLAN.md`
- (PR de rapport) `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`, ce rapport

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Aucune écriture sur DEV ni sur le compte LoDyMcFly. Aucune lecture DEV n'a été nécessaire.

## Tests automatiques
- Local (tests ciblés, `RPGQUEST_TEST_MAX_HEAP=768m`) : `ClaimWorldAccessGuardTest` **60/60** OK (145 s), `ClaimWorldSafetyListenerTest` **29/29** OK (71 s), `BypassOnlyThroughAdminModeTest` OK.
- CI GitHub de la PR #825 (suite complète plugin, control-panel, web-api, scripts, artefacts) : **verte** sur la PR et intégralement (Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK), fusionnée en `567035a7`.

## Tests manuels à effectuer
**PENDING MANUAL VALIDATION**, mais aucun geste n'est requis du propriétaire pour la preuve technique :
- Déjà réussi en jeu, à ne pas refaire : `/rpgadmin bypass status` → « inactif » pour LoDyMcFly (24/10). Le comportement non-OP (Jo, `false`/`true`) a été constaté correct le 13/10.
- Le refus d'un OP **sans** claim ni déblocage ne se rejoue pas avec le compte du propriétaire tel qu'il est : celui-ci entre légitimement s'il possède déjà un claim ou `CLAIM_TIER_1 = true`. Ce cas est prouvé par les matrices automatiques. Il ne serait rejouable en jeu qu'avec un **futur** compte de test, jamais en modifiant LoDyMcFly.
- Rien n'a été testé en jeu par Claude.

## Résultat attendu
Même comportement qu'avant (code inchangé). Les matrices empêchent désormais toute régression OP sur le portail, sur les arrivées par tout autre chemin et sur la lecture des nœuds de bypass.

## Reset / retour à l'état initial
Rien à annuler (tests et documentation seulement).

## Déploiement VeryGames
### À transférer
Rien : aucun changement de code livré. Le JAR DEV `e9b4376a` contient déjà le comportement validé.
### Ne PAS transférer/altérer
Compte LoDyMcFly (OP, droits, claims, variables), `data.db`.
### Redémarrage requis
Non.
### Migration automatique
Non.

## Rollback
Sans objet.

## Logs / diagnostic
En jeu, les décisions sont tracées dans la console : `[claims-access] … REFUSÉE` / `AUTORISÉE par le mode bypass`, et `[claims-safety] … NON éligible … renvoi au Hub`.

## Documentation mise à jour
`docs/MANUAL_TEST_PLAN.md` (TC-302, volet #321), `docs/SUIVI_VALIDATIONS_DEV.md`, index des rapports.

## Limitations / travail restant
- Durcissements possibles, hors périmètre OP (aucun n'est un contournement implicite propre aux OP) :
  1. Les portails historiques (`PortalService`) n'appliquent pas `ClaimWorldAccessGuard` avant de téléporter. Seul le filet d'arrivée agit, après coup.
  2. Le filet d'arrivée laisse le joueur sur place, avec une Pierre de retour, si la lecture en base échoue ou si aucun point du Hub ne se résout.
  3. Une téléportation à l'intérieur du monde Claims (`/tp` vers le claim d'autrui) ne change pas de monde, donc aucun contrôle ne se déclenche.
  4. `/claim admin tp` emmène un administrateur dans n'importe quel claim sans session de bypass (geste d'administration explicite, cf. #637).
- Décision propriétaire : soumettre ou non les commandes d'administration de téléportation (`/rpgadmin world tp`, `/claim admin tp`) au mode bypass. Aujourd'hui, elles dépendent de l'ombrelle `rpgquest.admin.world` (défaut OP) et le filet d'arrivée réapplique ensuite la règle gameplay.

## Prochaine étape suggérée
Le propriétaire décide de fermer #321 sur la base des preuves automatiques, ou de le garder ouvert pour un futur compte de test (étape 1 de TC-302). Sur décision, ouvrir un ticket de durcissement pour les points 1 à 3.
