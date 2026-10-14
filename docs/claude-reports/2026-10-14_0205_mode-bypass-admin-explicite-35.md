# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 02:05
* Sujet : #35 — mode bypass administrateur explicite et visible (corrige #321, accès Claims en OP)
* Statut : DONE (validation en jeu : PENDING MANUAL VALIDATION)
* Branche Git : `feature/35-admin-bypass-mode` → PR #342 fusionnée dans `chore/257-ci-github-actions` ; rapport sur `docs/35-rapport`
* Commit actuel si disponible : fusion `507e4ee` (code `58b2d45`, docs `cc9fea3`)
* Début de la tâche : 2026-10-14 01:25:36
* Fin de la tâche : 2026-10-14 02:08:00
* Durée totale : 00:42:24

## Demande
Session autonome lqorch, ticket #35 : un OP ne doit plus contourner implicitement les règles de jeu ;
contournement par un mode explicite, OFF par défaut, visible, coupé à la déconnexion/au redémarrage ;
réglages globaux dans PlugAdmin > Paramètres généraux > Administration (#335) sans jamais accorder de
droit ; corriger et tester #321 (accès Claims en OP) ; matrice OP/DEOP × bypass ON/OFF × accès
Claims ; ne pas retirer l'OP du propriétaire.

## Analyse
Audit des contournements de gameplay (code de `chore/257` à `d3c0dea`) :

| Point | Avant | Catégorie |
|---|---|---|
| Accès monde Claims (`ClaimWorldAccessGuard`) | `RpgPermissions.hasBypass(BYPASS_CLAIM_WORLD)` → ombrelle `rpgquest.admin.world`, **défaut OP** | bypass gameplay — **cause de #321** |
| Filet monde Claims (`ClaimWorldSafetyListener`) | idem | bypass gameplay |
| Règles monde Claims (`ClaimsWorldRulesListener`) | idem | bypass gameplay |
| Claims d'autrui (`ClaimProtectionListener`) | `hasBypass(BYPASS_CLAIM)` → ombrelle OP | bypass gameplay |
| Zones protégées (`ZoneProtectionListener`) | `hasBypass(BYPASS_ZONE)` → ombrelle OP | bypass gameplay |
| Combat Hub (`HubWorldProtectionListener`) | `rpgquest.admin.hub.combat`, **défaut OP** | bypass gameplay |
| Construction Hub | `canBuildInHub` (ombrelle ou `rpgquest.build.hub.*`) | droit de **construction** (#27/#200) — laissé tel quel |
| Waypoints/bornes | `TravelMaintenanceMode` (#191), déjà explicite, `default: false` | inchangé |
| `/rpgadmin`, outils PNJ/villes/emplacements | permissions d'administration | inchangé |
| `PortalService.requiredPermission`, `MerchantTradeService`, condition de dialogue `hasPermission` | conditions de contenu définies par l'auteur | hors périmètre |

La règle CLAIM_TIER_1 elle-même était correcte (`"true"` exact, ou claim principal existant) : le
symptôme #321 venait uniquement du bypass implicite OP. Contrat d'accès conservé : déblocage valide
**ou** déjà propriétaire ; aucune obligation d'Acte inventée.

## Travail effectué
- `permission.AdminBypassMode` : session par joueur en mémoire (`ConcurrentHashMap`), ouverte par
  le joueur lui-même (`rpgquest.admin.bypass`) ; `allows(player, node)` = session valable **et**
  nœud de bypass (ou ombrelle). Le mode n'ajoute aucun droit. Fin : `off`, déconnexion, arrêt,
  durée maximale, mode retiré dans PlugAdmin, permission perdue.
- `permission.AdminBypassService` : barre d'action « ⚠ MODE ADMIN / BYPASS ACTIF » rafraîchie toutes
  les 2 s (ne parcourt que les sessions ouvertes), fin automatique annoncée au joueur avec sa raison,
  fermeture à `PlayerQuitEvent`, purge à l'arrêt. Journal `[admin-bypass]`.
- `/rpgadmin bypass on|off|status` (+ complétion) ; branche ouverte à `rpgquest.admin.bypass` sans
  exiger l'ombrelle.
- Les six écouteurs ci-dessus reçoivent le mode par constructeur et ne consultent plus jamais un
  nœud de bypass directement. Journal d'entrée Claims explicite (« par le mode bypass administrateur »).
- `plugin.yml` : nouveau nœud `rpgquest.admin.bypass` (défaut `op`), descriptions mises à jour.
- Paramètres généraux #335 : `admin_bypass_available` (défaut `true`) et `admin_bypass_max_minutes`
  (0–480, défaut 0) dans `GameSettings`, `settings.yml` (`admin.bypass.*`), `settings.get/set`,
  validation du catalogue PlugAdmin, rubrique **Administration** (formulaire ; lecture seule avec
  bandeau si le plugin installé ne connaît pas ces clés).
- Fusion PR #342 après CI verte (suite complète) ; déploiement DEV du panel puis du JAR ; redémarrage.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/permission/AdminBypassMode.java`
- `src/main/java/com/lodygames/rpgquest/permission/AdminBypassService.java`
- `src/test/java/com/lodygames/rpgquest/permission/AdminBypassModeTest.java`
- ce rapport.

## Fichiers modifiés
- Plugin : `admin/RpgAdminCommand.java`, `bootstrap/RPGQuestBootstrap.java`,
  `claim/{ClaimWorldAccessGuard,ClaimWorldSafetyListener,ClaimsWorldRulesListener,ClaimProtectionListener}.java`,
  `zone/ZoneProtectionListener.java`, `hub/HubWorldProtectionListener.java`,
  `settings/{GameSettings,GameSettingsService}.java`, `web/agent/BukkitAgentActions.java`,
  `src/main/resources/plugin.yml`.
- Panel : `panel/agent/AgentActionCatalog.java`, `panel/web/SettingsPages.java`,
  `resources/docs/parametres-generaux.md`.
- Tests : `ClaimWorldAccessGuardTest` (+8 cas), `ClaimWorldSafetyListenerTest`,
  `ClaimsWorldRulesListenerTest`, `ClaimProtectionListenerTest`, `ZoneProtectionListenerTest`,
  `WildWorldSeparationTest`, `HubWorldProtectionListenerTest`, `RpgPermissionsMatrixTest`,
  `GameSettingsTest`, `SettingsPageTest`.
- Docs : voir « Documentation mise à jour ».

## Base de données / migrations
Aucune migration, aucune donnée joueur modifiée. Le mode n'est **jamais** persisté (mémoire seule).
Panel : un compte jetable `ADMIN` inséré puis supprimé (vérifié : 0 ligne restante) ; deux actions
`settings.get` (lecture) dans `agent_action`. Aucun `settings.set`.

## Configuration / données
`settings.yml` (DEV) : inexistant pour l'instant ; les deux nouvelles clés ne seront écrites qu'au
premier enregistrement depuis PlugAdmin. Valeurs par défaut lues : mode proposé, durée 0.

## Tests automatiques
- Local ciblé plugin : `AdminBypassModeTest` 10, `ClaimWorldAccessGuardTest` 20,
  `ClaimWorldSafetyListenerTest` 9, `ClaimsWorldRulesListenerTest` 32, `ClaimProtectionListenerTest` 20,
  `ZoneProtectionListenerTest` 29, `WildWorldSeparationTest` 6, `HubWorldProtectionListenerTest` 28,
  `RpgPermissionsMatrixTest` 6, `GameSettingsTest` 6 → **166/166 OK**.
- Local ciblé panel : `SettingsPageTest` 6, `AgentActionCatalogTest` 46 → OK.
- CI GitHub (suite complète plugin + panel + web-api + artefacts) : verte sur la PR #342 et sur la
  fusion `507e4ee` (run 38487029463).
- Matrice couverte : OP sans session refusé ; OP session ON entre, OFF refusé de nouveau ; DEOP ne
  peut pas ouvrir et est refusé ; `CLAIM_TIER_1` = `false`/`TRUE`/`oui`/`1` refusés pour un OP ;
  OP avec `true` accepté sans bypass ; nœud sans session ; session sans nœud ; retrait PlugAdmin ;
  durée maximale ; permission perdue ; indicateur ; déconnexion ; arrêt ; droits de construction et
  d'administration intacts. (Cas « déjà propriétaire » : test existant inchangé et vert.)

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` **TC-302** (OP/DEOP × bypass ON/OFF ×
accès Claims, `CLAIM_TIER_1` absent/false/true/invalide, premier claim et déjà propriétaire,
reconnexion, redémarrage, durée maximale et retrait depuis PlugAdmin, combat/construction au Hub).
Aucun test en jeu n'a été fait dans cette session.

## Résultat attendu
Un OP qui joue sans `/rpgadmin bypass on` est traité comme un joueur normal : portail Claims refusé
sans déblocage, claims/zones/entités du Hub protégés. Avec le mode, la barre d'action rouge est
visible en permanence et les contournements prévus fonctionnent. Les commandes `/rpgadmin` et la
construction au Hub restent disponibles sans le mode.

## Reset / retour à l'état initial
`/rpgadmin bypass off` ou une déconnexion. Les réglages PlugAdmin reviennent aux défauts en
supprimant les clés `admin.bypass.*` de `settings.yml` (manuel, non fait).

## Déploiement VeryGames
Effectué le 2026-10-14 depuis `/srv/rpgquest/wt-orch` @ `507e4ee` (CI verte, aucun test local relancé
par les scripts).
- PlugAdmin : `scripts/plugadmin/deploy.sh --wait-ci` → service relancé 02:00:47, `DEPLOYED`
  `commit=507e4ee…` ; classe `SettingsPages` et fiche `parametres-generaux.md` vérifiées dans le JAR
  servi ; page `/settings?tab=administration` vérifiée en HTTPS (compte jetable) : valeurs relues,
  formulaire actif, « Proposé aux administrateurs autorisés », durée 0.
- JAR : `scripts/deploy-verygames.sh -y --wait-ci` → « JAR en ligne : 2645270 octets (== local) »,
  « Déploiement terminé ». JAR distant retéléchargé : SHA-256
  `ecf230103eab7131a30dedd7a40eb23aead2bfed9b8e6c5e7f4152a7cace5774` (identique). Sauvegarde
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20261014T000115Z-predeploy.jar`
  (2631636 o, SHA `b3205a17…48bb52`, plus petit que le nouveau : attendu).
- Redémarrage : 0 joueur → `verygames-restart.sh --timeout 300` → ONLINE ; `plugins` : RPGQuest
  activé ; `help rpgadmin 2` affiche `bypass <on|off|status>` ; `settings.get` SUCCESS avec
  `admin_bypass_available=true`, `admin_bypass_max_minutes=0`.

### À transférer
JAR RPGQuest et PlugAdmin (faits).
### Ne PAS transférer/altérer
`settings.yml`, `data.db`, `config.yml`, mondes, groupes LuckPerms. Ne pas retirer l'OP du propriétaire.
### Redémarrage requis
Oui pour le JAR (fait).
### Migration automatique
Aucune.

## Rollback
- Plugin : `scripts/rollback-verygames.sh` avec la sauvegarde
  `rpgquest-20261014T000115Z-predeploy.jar`, puis `scripts/verygames-restart.sh`. L'ancienne version
  ignore les clés `admin.bypass.*` et rétablit le bypass implicite OP.
- Panel : `scripts/plugadmin/rollback.sh app`.

## Logs / diagnostic
`[admin-bypass] <joueur> : mode bypass ACTIVÉ (…)`, `… désactivé`, `… DÉSACTIVÉ automatiquement
(EXPIRED|UNAVAILABLE|PERMISSION_LOST)`, `… désactivé (déconnexion)` ; `[claims-access] … AUTORISÉE par
le mode bypass administrateur` / `… REFUSÉE (CLAIM_TIER_1 non débloqué, aucun claim)`.

## Documentation mise à jour
`docs/PERMISSIONS.md` (section mode bypass, matrice, protocole avec rappel « bypass OFF »),
`docs/RPGQUEST_BIBLE.md` (commande + paragraphe OP/Claims réécrit), `docs/CLAIMS.md`,
`docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-302 + tableau),
`docs/deployment/SERVER_CHANGELOG.md`, `docs-site/admin-testing.html`, aide PlugAdmin
`parametres-generaux.md`.

## Limitations / travail restant
- Validation en jeu TC-302 ; #321 et #35 laissés **ouverts** jusqu'à cette validation.
- Un OP sans mode qui arrive dans le monde Claims autrement que par le portail (ex. `/tp`) et
  n'est pas éligible est désormais renvoyé au Hub comme un joueur : comportement voulu (activer le
  mode avant d'y aller en administrateur).
- Construction au Hub : reste accordée aux OP par l'ombrelle (droit de construction, pas un bypass
  de jeu) — à revoir avec #27 si le propriétaire veut aussi la soumettre au mode.
- `TravelMaintenanceMode` (#191) reste un mode distinct (déjà explicite, 5 min).

## Prochaine étape suggérée
Le propriétaire déroule TC-302 sur DEV (compte OP + compte de test), puis ferme #321 et #35.
