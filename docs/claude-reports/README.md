# Rapports Claude

Historique chronologique, **immuable**, de chaque session de développement effectuée par Claude
dans ce dépôt — voir `CLAUDE.md`, section « Session Reports », pour la règle qui impose leur
création. Un rapport est créé après **chaque** demande (développement, correction de bug,
diagnostic, refactoring, migration, ou modification de configuration/documentation), même si la
tâche est petite, partielle, bloquée, ou n'a finalement modifié aucun code.

## Pourquoi ce dossier existe

Ces rapports ne remplacent **jamais** la documentation fonctionnelle du projet
(`docs/current_state.md`, `docs/ARCHITECTURE.md`, `docs/RPGQUEST_BIBLE.md`, etc.), qui continue
d'être maintenue à jour normalement. Ils servent un besoin différent : pouvoir être envoyés
**tels quels** à un autre assistant (ou humain) qui n'a suivi aucune des sessions précédentes, et
qui doit pouvoir comprendre, sans contexte supplémentaire :

- ce qui était demandé ;
- ce qui existait avant l'intervention ;
- ce qui a changé, et pourquoi (décisions techniques incluses) ;
- comment cela a été testé ;
- comment le déployer ;
- ce qui reste éventuellement problématique ou non traité.

## Règles

- **Un fichier par demande, jamais réutilisé** — un rapport n'écrase jamais un rapport précédent.
- **Immuable après création** — ne jamais modifier un ancien rapport pour refléter l'état actuel
  du projet. Un rapport documente l'état des choses *au moment où il a été écrit*, pas l'état
  actuel (voir `docs/current_state.md` pour ça).
- **Toujours créé, même en échec** — un statut `PARTIAL`/`BLOCKED`, des tests qui échouent, ou une
  demande purement diagnostique n'exemptent jamais de la création du rapport.
- **Reflète la réalité, pas la demande initiale** — le rapport décrit ce qui a été *réellement*
  fait, qui peut différer de ce qui était prévu au départ.
- **Mesure du temps réel, jamais inventée** — voir `CLAUDE.md`, section « Session Reports — Time
  Tracking » : début/fin/durée réels de la tâche, ou `non mesurable` explicitement si le début
  n'a pas pu être capturé.

## Nom de fichier

```
YYYY-MM-DD_HHMM_<description-courte>.md
```

Date et heure **locales réelles** au moment de la création du rapport (pas celles de la tâche
elle-même si le rapport est rédigé après coup). Exemples :

- `2026-08-21_2006_worldportal-debug-tools.md`
- `2026-08-21_2142_fix-hub-auto-teleport.md`
- `2026-08-22_1015_story-npc-integration.md`

## Gabarit obligatoire

Chaque rapport contient au minimum les sections suivantes, dans cet ordre :

```markdown
# RPGQuest — Rapport Claude

## Informations
* Date :
* Heure :
* Sujet :
* Statut : DONE / PARTIAL / BLOCKED
* Branche Git :
* Commit actuel si disponible :
* Début de la tâche : YYYY-MM-DD HH:MM:SS (heure locale réelle, ou « non mesurable »)
* Fin de la tâche : YYYY-MM-DD HH:MM:SS (heure locale réelle)
* Durée totale : HH:MM:SS (calculée entre les deux, jamais estimée)

## Demande
## Analyse
## Travail effectué
## Fichiers créés
## Fichiers modifiés
## Base de données / migrations
## Configuration / données
## Tests automatiques
## Tests manuels à effectuer
## Résultat attendu
## Reset / retour à l'état initial
## Déploiement VeryGames
### À transférer
### Ne PAS transférer/altérer
### Redémarrage requis
### Migration automatique
## Rollback
## Logs / diagnostic
## Documentation mise à jour
## Limitations / travail restant
## Prochaine étape suggérée
```

Voir n'importe quel rapport existant ci-dessous pour un exemple rempli.

## Index chronologique

| Date | Sujet | Statut | Fichier |
|---|---|---|---|
| 2026-08-21 | Outils de diagnostic WorldPortal (`here`/`debug`, TP-TRACE) | DONE | [2026-08-21_2009_worldportal-debug-tools.md](2026-08-21_2009_worldportal-debug-tools.md) |
| 2026-08-21 | Progression automatique de Storyline (connexion au moteur de quête) | DONE | [2026-08-21_2135_story-automatic-progression.md](2026-08-21_2135_story-automatic-progression.md) |
| 2026-08-23 | Investigation BREAK_BLOCK ne progresse pas dans `wild` (story_test) | BLOCKED | [2026-08-23_1234_break-block-wild-investigation.md](2026-08-23_1234_break-block-wild-investigation.md) |
| 2026-08-23 | Instrumentation `[QUEST-TRACE]` pour diagnostiquer la chaîne BREAK_BLOCK | DONE | [2026-08-23_1256_quest-trace-break-block-instrumentation.md](2026-08-23_1256_quest-trace-break-block-instrumentation.md) |
| 2026-08-23 | Investigation reset progression Story/quête (death/respawn, reconnexion) + correctif `premiers_pas.yml` manquant | PARTIAL | [2026-08-23_1643_quest-story-progress-persistence-investigation.md](2026-08-23_1643_quest-story-progress-persistence-investigation.md) |
| 2026-08-23 | Premier claim joueur 5×5 débloqué par une Story (CLAIM_TIER_1 → PNJ Jo → Acte de propriété → pose sans commande) | DONE | [2026-08-23_1758_premier-claim-5x5-story-deed.md](2026-08-23_1758_premier-claim-5x5-story-deed.md) |
| 2026-08-23 | 3 améliorations Claim : Jo « retourner à son claim », `/claim admin tp`, monde `claims` réellement pacifique | DONE | [2026-08-23_1857_claim-gohome-mobs-pacifiques.md](2026-08-23_1857_claim-gohome-mobs-pacifiques.md) |
| 2026-08-23 | MVP monde `claims` : frontière par particules, zone résidentielle safe, Pierre de retour, blocage Nether, complétion config.yml | DONE | [2026-08-23_2003_claims-mvp-frontiere-pacifique-pierre-retour.md](2026-08-23_2003_claims-mvp-frontiere-pacifique-pierre-retour.md) |
| 2026-09-05 | 4 corrections UX post-validation VeryGames : indicateur de canalisation, Pierre de retour limitée à `claims` + anti-perte, faisceau de retrouvaille du claim à distance | DONE | [2026-09-05_1729_claims-mvp-corrections-ux-pierre-retour-faisceau.md](2026-09-05_1729_claims-mvp-corrections-ux-pierre-retour-faisceau.md) |
| 2026-09-05 | Boucle joueur Hub ↔ Wild : système soulbound générique, item Journal des quêtes, Rune de rappel (cooldown persistant), avertissement compact avant entrée Wild, Waystones génératives, config `travel.*`, migrations V16/V17, `/rpgadmin waystone` | DONE | [2026-09-05_1837_boucle-joueur-hub-wild-soulbound-journal-rune-waystones.md](2026-09-05_1837_boucle-joueur-hub-wild-soulbound-journal-rune-waystones.md) |
| 2026-09-05 | Reset admin « nouveau joueur » : `/rpgadmin player resetnew <joueur> confirm` — remet l'état RPGQuest d'un seul joueur (online/offline) à l'équivalent « jamais joué » (quêtes, Stories, variables/unlocks, progression RPG, découvertes Waystones, cooldowns, claim principal, objets RPGQuest de l'inventaire). Voir la section « Reset complet "nouveau joueur" » du rapport et [docs/ADMIN_PLAYER_RESET.md](../ADMIN_PLAYER_RESET.md). | DONE | [2026-09-05_2002_reset-admin-nouveau-joueur.md](2026-09-05_2002_reset-admin-nouveau-joueur.md) |
| 2026-09-05 | Refonte de `CLAUDE.md` : cadre unique des règles pour toutes les futures sessions Claude Code (bootstrap `.ai/`, GIT/source de vérité, politique de push vers la branche de travail, branches sans `develop` permanente, TESTS/BUILD, DOCUMENTATION, SÉCURITÉ, DÉPLOIEMENT, GitHub Issues, mode de travail). Aucun code fonctionnel modifié. | DONE | [2026-09-05_2032_claude-md-refonte-regles-sessions.md](2026-09-05_2032_claude-md-refonte-regles-sessions.md) |
| 2026-09-05 | Issue #8 — mode preview/dry-run du reset joueur admin : `/rpgadmin player resetnew <joueur> preview` liste, catégorie par catégorie, ce qu'un reset réel effacerait, **sans aucune écriture**. Nouveaux points de lecture seule (`PlayerVariableRepository#findAllForPlayer`, `WaystoneService#discoveryCount`, `StoryService#progressRecords`), `PlayerResetService#previewReset`, refactor interne `countOrRemoveRpgItems`. 4 tests ajoutés. Comportement du reset réel inchangé. | DONE | [2026-09-05_2101_player-reset-preview-issue-8.md](2026-09-05_2101_player-reset-preview-issue-8.md) |
| 2026-09-05 | Issue #11 — Guide « centre d'aide » + journal de quêtes en GUI via le Libraire : `guide.yml` réécrit en menu d'aide structuré (nœud `help_menu` + sujets, orientations textuelles) ; nouveau registre `hub.HubGuideRegistry` (`hub-guides/*.yml`) pour la structure multi-Hub + diagnostic `/rpgadmin guide list\|info`. Le journal (`rpgquest:journal_quetes`) ouvre désormais la GUI `QuestJournalService` au clic droit ; GUI passée de 3 à **2 onglets** (« en cours » / « terminées »), onglet catalogue « Disponibles » supprimé ; `QuestJournalBookService` (résumé chat) supprimé. Tests : `hub/*` (3), `BundledDialoguesValidityTest`, `QuestJournalServiceTest` réécrit. | DONE | [2026-09-05_2233_guide-help-center-quest-journal-issue-11.md](2026-09-05_2233_guide-help-center-quest-journal-issue-11.md) |
| 2026-09-06 | Issue #11 — correction du bug de navigation entre onglets du journal : `COMPLETED → IN_PROGRESS` ne rafraîchissait plus la vue et le bouton « Fermer » devenait inerte après le 1er changement d'onglet. Cause : `showList`/`showDetail` enregistraient la session AVANT `player.openInventory()`, dont le `InventoryCloseEvent` synchrone (menu précédent) l'effaçait aussitôt via `handleClose`. Correctif : `openSessions.put(...)` déplacé APRÈS `openInventory`. Test de régression bidirectionnel `IN_PROGRESS → COMPLETED → IN_PROGRESS` (vrais clics, listener enregistré, sans fermer) + vérif du bouton Fermer. 931 tests verts. Redéployé DEV VeryGames (JAR seul). #11 non fermée. | DONE | [2026-09-06_1014_fix-journal-navigation-onglets-issue-11.md](2026-09-06_1014_fix-journal-navigation-onglets-issue-11.md) |
| 2026-09-06 | Issues #21/#22/#23 — parcours Claims cohérent de bout en bout. Audit : l'unlock du 1er claim = variable `CLAIM_TIER_1 == "true"`, accordée uniquement par `crystal_hunt` (fin de l'histoire principale, au Garde) ; le Guide omettait ce prérequis, d'où « Jo ne propose rien » après `resetnew`. Ajouts : `ClaimWorldAccessGuard` (portail Hub→claims fermé sans unlock, aucune téléportation, bypass `rpgquest.admin.world` seul) composé via `CompositeWorldPortalEntryGuard` ; `ClaimWorldSafetyListener` (Pierre de retour donnée à l'arrivée, joueur non éligible renvoyé au Hub — personne coincé, retour sans commande) ; modificateur de dialogue `negate: true` (`NegatedCondition`) ; `guide.yml`/`jo.yml` réécrits, Jo adapté aux 3 états. Tests : `ClaimWorldAccessGuardTest`, `ClaimWorldSafetyListenerTest`, `CompositeWorldPortalEntryGuardTest`, + `DialogueDefinitionParserTest`/`DialogueSessionEngineTest`. #21/#22/#23 non fermées (validation manuelle). | DONE | [2026-09-06_1116_parcours-claims-coherent-issues-21-22-23.md](2026-09-06_1116_parcours-claims-coherent-issues-21-22-23.md) |
| 2026-09-06 | Audit du parcours principal (nouveau joueur → `crystal_hunt` → `CLAIM_TIER_1`) + ré-audit #21/#22. Causes trouvées via le contenu réel du serveur DEV : (1) aucun PNJ Citizens lié `guard` (`npc_citizens_bindings` : guide, libraire, help, jeff, junior, jo) → `first_steps` ET `crystal_hunt` indémarrables ; (2) `dialogues/guard.yml` déployé périmé, sans branche `crystal_hunt`. #21/#22 : le code refuse bien l'entrée / renvoie au Hub pour un compte non-OP — les symptômes correspondent au bypass `rpgquest.admin.world` (défaut op) déclenché par un test OP. Ajouts : journalisation de décision `[claims-access]` / `[claims-safety]` ; redéploiement DEV du JAR + `dialogues/guard.yml`. Doc : §1b PNJ obligatoires (NPC_DIALOGUES_QUESTS_GUIDE), BIBLE, current_state, VERYGAMES. #21/#22/#23 non fermées ; création du PNJ Garde + validation en jeu restantes. | PARTIAL | [2026-09-06_1219_audit-parcours-principal-garde-crystal-hunt.md](2026-09-06_1219_audit-parcours-principal-garde-crystal-hunt.md) |
| 2026-09-06 | Issue #36 — raccourcis admin/DEV pour tester quêtes & stories sans rejouer le gameplay. Réutilise les services métier (jamais d'écriture directe en base). `QuestProgressEngine.accept(..., ignorePrerequisites)` ; `StoryService.adminAdvance`/`adminComplete` (avance déterministe, garde anti double-récompense conservée via `forceComplete`, ordre respecté, bornée) ; `/rpgadmin quest start\|complete\|reset <joueur> <id>` (+ `force`), `/rpgadmin story advance\|complete <joueur> <storyId>` (message « étape à tester »), `/rpgadmin player variable get\|set <joueur> <clé>`. Permission `rpgquest.admin.world` ; `variable set` exige en plus `rpgquest.admin.debug`. Tab-complétion + journalisation. Tests : `StoryServiceTest` +8, `RpgAdminTestShortcutsCommandTest` (11), `QuestProgressEngineTest` +2. Doc : `ADMIN_TEST_SHORTCUTS.md` (nouveau), BIBLE, current_state. Branche `feat/36-admin-test-shortcuts`, non fusionnée, #36 non fermée. | DONE | [2026-09-06_2028_admin-test-shortcuts-issue-36.md](2026-09-06_2028_admin-test-shortcuts-issue-36.md) |
| 2026-09-07 | Préparation d'une session de validation manuelle DEV : audit de tout le backlog `needs-manual-test` (15 issues) → **seules #21/#22/#23/#36 sont implémentées et déployées** (JAR `bcc3a6ec…`) ; #27 sur une branche divergente non déployée ; #24/#25/#26/#28/#30/#31/#32/#33/#34/#35 **non implémentées**. Checklist unique séquentielle PHASE 0→7 (redémarrage + création du PNJ **Garde** = blocant ; parcours Claims non-OP couvrant #21+#22+#23 ; fiche #36 en 5 min ; commentaires de validation pré-rédigés). Audit du serveur DEV : Garde toujours absent, contenu YAML/portails/items OK, `Rondoudou9000` = compte de test neuf non-OP. + Audit du module `web-api` et **`docs/control-panel/`** (vision, architecture, sécurité, config, bridge RPGQuest, roadmap, 7 ADR) pour l'issue #37 — documentation seule, aucun code. Aucune issue fermée, aucune branche fusionnée, aucun déploiement. | DONE | [2026-09-07_0744_preparation-session-validation-manuelle-backlog.md](2026-09-07_0744_preparation-session-validation-manuelle-backlog.md) |
| 2026-09-07 | Issue #37 — socle exécutable du RPGQuest Control Panel : premier flux vertical navigateur → login → dashboard → bridge → **état réel du plugin**. Nouveau module Gradle **`control-panel/`** autonome (auth owner PBKDF2 + session signée HMAC + CSRF + logout, config externe multi-cibles `Target`, `PermissionService`/`Role`/`Permission`, audit log append-only `control-panel.db`, dashboard sobre responsive, gestion propre de l'indisponibilité du bridge). Côté plugin : package **`web.admin`** — `WebAdminServer` + `HealthSource`/`BukkitHealthSource`, route `GET /admin/v1/health` authentifiée Bearer, **fail-closed**, config **par variables d'environnement uniquement** (ADR-008, aucun changement `config.yml`/`PluginConfig`). Tests : `PanelAppTest` (11), `BridgeClientTest` (4), `PasswordHasherTest` (4), `JsonTest` (3), plugin `WebAdminServerTest` (4) — `./gradlew test build` vert. Audit AWS **lecture seule** pour #44 (nginx + 2 vhosts + ports libres + TLS Certbot ; aucune conf touchée) → `docs/control-panel/AWS.md`. Doc `docs/control-panel/` alignée + ADR-008/009/010. Branche `feat/37-control-panel`, non fusionnée, #37 **non fermée** (dépend de #44 + décision d'hébergement du bridge). | DONE | [2026-09-07_0820_control-panel-socle-issue-37.md](2026-09-07_0820_control-panel-socle-issue-37.md) |
| 2026-09-07 | Issue #44 — déploiement de **PlugAdmin** (socle #37) sur AWS via **https://plugadmin.lodylands.com**, sans perturber `dig.lodygames.com` / `lodylands.com`. Audit AWS avant mutation (nginx 1.24, 2 vhosts, port 8090 libre, IP `3.226.216.90`) ; **DNS déjà résolu** → aucune action DNS. Scripts reproductibles `scripts/plugadmin/` (`install.sh` idempotent, `deploy.sh`, `rollback.sh` + templates). Installé : utilisateur système dédié `plugadmin`, app sous `/opt/plugadmin/app` (`installDist`), secrets `/etc/plugadmin/plugadmin.env` (`0640`, hors Git ; `RPGQUEST_PANEL_SECRET` + `RPGQUEST_BRIDGE_TOKEN_DEV` générés, `OWNER_HASH` fourni par l'owner), `control-panel.db` sous `/var/lib/plugadmin`, service systemd `plugadmin` durci (`enable`, `Restart=on-failure`), vhost nginx **dédié** `sites-available/plugadmin` (`nginx -t` + `reload`, jamais `restart`), certificat Certbot **ECDSA** `plugadmin.lodylands.com` (exp. 2026-12-06) + redirection 80→443. Vérifs live : `/health` local **et** public 200 ; backend non exposé sur `:8090` ; `GET /login` 200 + en-têtes sécurité + cookie CSRF `Secure`/`HttpOnly`/`SameSite` ; `/dashboard` anonyme → 303 ; login invalide → 401 ; CSRF manquant/faux → 403 ; systemd start/stop/restart + reprise après `kill -9` ; dashboard « RPGQuest DEV indisponible » **sans 500** (validé sur instance jetable isolée). **Non-régression autres sites : 200/301 inchangés avant/après**, 3 certificats intacts. Aucun code Java modifié. Aucune issue fermée, aucune branche fusionnée. **PARTIAL** : login owner réel par navigateur externe reste à faire par l'owner (mot de passe non communiqué). | PARTIAL | [2026-09-07_0935_plugadmin-aws-deploy-issue-44.md](2026-09-07_0935_plugadmin-aws-deploy-issue-44.md) |
| 2026-09-07 | Issue #51 — **agent sortant** RPGQuest/VeryGames → PlugAdmin/AWS : inversion du flux du bridge #37 (RPGQuest initie l'HTTPS sortant). Plugin `com.lodygames.rpgquest.web.agent` : `AgentConfigLoader` (fichier local hors Git `plugadmin-agent.properties`, fail-closed), `HeartbeatPayload` (réutilise `HealthSource` #37, zéro duplication), `PlugAdminClient` (`java.net.http`, cert normal, corps borné), `AgentLoop` (heartbeat + file d'actions, backoff, idempotence via `ProcessedActionCache`), `AgentActionExecutor` (whitelist `AgentActionType` → services métier ; MVP = `player.variable.get` seul, jamais `/rpgadmin` texte), `PlugAdminAgent` (PluginService async). Control Panel `panel.agent` : `AgentEndpoints` (`/agent/v1/{heartbeat,actions,actions/{id}/result}`, auth par agent temps constant, 413/503), `AgentStore` (`control-panel.db` : `agent_heartbeat` + `agent_action`, idempotent, expiration), `AgentLiveness` ONLINE/STALE/OFFLINE, dashboard « AGENT DISTANT » prioritaire + page `/agents`. Tests : `web.agent.*` 30, `panel.agent.*` 21, `PanelAppTest` +1 — `./gradlew clean build` vert (plugin 1005). **Déployé sur VeryGames DEV** (JAR `5e9d9a5e…` + fichier agent) via `deploy-verygames.sh` ; **RCON VeryGames validé** (mention « pas de RCON » corrigée) + nouveaux `scripts/verygames-rcon.py` / `verygames-restart.sh` / `plugadmin/send-mail.py` ; redémarrage RCON exécuté. **Validation live** : heartbeat réel reçu, dashboard ONLINE (données), `player.variable.get` → SUCCESS, type inconnu → REJECTED, idempotence OK, panne PlugAdmin n'affecte pas Minecraft + reconnexion OK. Email SMTP de fin de tests envoyé. ADR-011 (remplace ADR-004). Branche `feat/51-plugadmin-outbound-agent`, non fusionnée, #51 non fermée. | DONE | [2026-09-07_1127_plugadmin-outbound-agent-issue-51.md](2026-09-07_1127_plugadmin-outbound-agent-issue-51.md) |
| 2026-09-07 | Issue #40 — **abstraction de la persistance** + moteur de base configurable (socle SQLite → MySQL, hors #41/#42). Audit : tout le SQL est déjà confiné à `com.lodygames.rpgquest.database` (aucun SQL dans les services de gameplay) ; couplage réel = URL JDBC + `PRAGMA` en dur de `DatabaseManager`, `PRAGMA user_version` de `SchemaMigrator`, syntaxe SQLite (`ON CONFLICT`/`INSERT OR IGNORE`/`AUTOINCREMENT`) dans 20 repositories + migrations. Nouveau : `DatabaseSettings`/`DatabaseType` (config `database.type: sqlite\|mysql`, mot de passe via `password-env` uniquement), `DatabaseEngine` (`SqliteDatabaseEngine` câblé / `MySqlDatabaseEngine` reconnu — corps réel = #41) + `DatabaseEngineFactory` (unique `switch`), `SqlDialect` (`SqliteDialect`/`MySqlDialect` : upsert, insert-ignore, identité, `columnExists`), `SchemaHistory` (`PragmaUserVersionHistory` SQLite natif / `MigrationTableHistory` portable), `SchemaMigration`+`SchemaMigrationRunner` (application ordonnée, idempotente, échec nommé, `targetVersion`). `SchemaMigrator` → catalogue `ALL` (SQL des 17 migrations **inchangé**). `DatabaseManager` : ctor `Path` conservé (SQLite, inchangé) + ctor `DatabaseEngine` + `healthCheck()` non bloquant. `PluginConfig.databaseFile`→`DatabaseSettings database` (+ `databaseFile()` raccourci). Tests : +25 `database.*` + 8 `ConfigValidatorTest` ; `SchemaMigratorTest` (21) et tous les `*RepositoryTest` **inchangés et verts**. `./gradlew test build` vert. Doc : `docs/PERSISTENCE.md` (nouveau) + ARCHITECTURE / current_state / BIBLE / INDEX / CONTEXT. Branche `feat/40-persistence-abstraction`, non fusionnée, #40 non fermée (backend MySQL réel = #41). | DONE | [2026-09-07_1315_persistence-abstraction-issue-40.md](2026-09-07_1315_persistence-abstraction-issue-40.md) |
