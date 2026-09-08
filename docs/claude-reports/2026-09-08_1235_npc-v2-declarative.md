# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 12:35 (locale machine, UTC)
* Sujet : Système PNJ **V2 déclarative** — définition logique + écritures de contenu (issues #66 & #75)
* Statut : DONE (code + tests + doc + déploiement AWS) — **déploiement VeryGames DEV en attente**
  (panne FTP VeryGames au moment de la tâche, voir « Déploiement VeryGames »)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : code `5db7ada` + docs `1340dda` ; ce rapport ajoute un 3e commit
* Début de la tâche : 2026-09-08 12:04:46
* Fin de la tâche : 2026-09-08 12:46:00
* Durée totale : 00:41:14

## Demande

Poser une **V2 déclarative du système PNJ** : introduire une vraie définition logique de PNJ
RPGQuest (indépendante de Citizens, du monde, du binding), permettre au Control Panel de la
créer / éditer, y associer quêtes / dialogues, garder le binding Citizens séparé et optionnel, et
faire évoluer `/npcs` pour afficher clairement définition logique vs binding + état de cohérence.
Ne **pas** créer d'opération de spawn Citizens depuis le web. Si aucune couche d'écriture YAML sûre
n'existe : ne pas bricoler (implémenter proprement, ou preview + ticket). Aucune validation
Minecraft manuelle. Hors scope : MariaDB #42, Claims, onboarding, progression joueur, économie,
portail public, nginx/TLS, merge vers `main`.

## Analyse (audit)

| Élément | Constat |
|---|---|
| `NpcCatalog` (V1) | Croisait binding Citizens ↔ dialogue `rpgquest:<id>` ↔ `giver:` ↔ `TALK_TO_NPC` ; `canonicalIds` = **union dérivée** des références, aucune notion de définition. |
| `npc_citizens_bindings` (migration V12) | Table `citizens_uuid` PK / `citizens_numeric_id` / `npc_id`. Lecture via `NpcBindingRepository.loadAll()`. |
| `NpcIdentityService` / `NpcBindingRepository` | Binding physique uniquement — aucune notion de « définition ». |
| `DialogueDefinition` | `id` (NamespacedKey), `startNodeId`, `nodes`. Pas de champ de binding NPC : la relation dialogue↔PNJ est **conventionnelle** (`dialogueId == rpgquest:<npcId>`). |
| `QuestDefinition.giver` / `TalkToNpcObjective.npcId` | Chaînes brutes (id logique). |
| Format YAML quêtes / dialogues | `QuestLoader` / `DialogueLoader` : un fichier par entité, `YamlConfiguration.load`, cross-validation (id dupliqués, prérequis…). Pattern **loader + parser + report + `YamlXxxEngine` (`PluginService`)** homogène. |
| `/rpgadmin npc` | `tag` / `untag` / `info` — ciblent l'entité regardée ; **aucune liste**. `RpgAdminCommand` n'a pas `dialogueEngine`. |
| **Couche d'écriture YAML** | `YamlConfiguration.save()` utilisé par `WorldPortalRegistry` / `ZoneRegistry` / `SpawnService`… pour du contenu **admin-généré**. Mais `.save()` **réécrit tout et perd les commentaires** → inadapté pour éditer `giver:` d'une quête riche en commentaires. |

**Conclusion** : la définition logique doit vivre en **YAML, un fichier par PNJ**
(`plugins/RPGQuest/npcs/*.yml`), comme tout le contenu RPGQuest — pas en base. Pour `giver:`, une
**édition de texte minimale** (une seule ligne racine touchée) est plus sûre qu'un `save()`
destructif et reste testable sans Bukkit.

## Travail effectué

### 1. Modèle + chargement (plugin, `com.lodygames.rpgquest.npc`)

- **`model/NpcDefinition`** — `record {id, displayName, description?, dialogueId?, role?, enabled}`.
  Aucune dépendance Bukkit (`dialogueId` = chaîne). `id` validé `[a-z0-9._-]{1,64}`, jamais
  renommable. MVP + `role` (classification libre, sans effet) + `description`.
- **`NpcDefinitionParser`** (section YAML → `NpcDefinition`, accumule les erreurs) ;
  **`NpcDefinitionLoader`** (dossier, fichier invalide isolé, id dupliqué rejeté) ;
  **`NpcLoadReport` / `NpcLoadIssue`**. Même conception que `QuestLoader`.
- **`YamlNpcEngine`** (`PluginService`) — charge `npcs/*.yml`, `reload()` **sûr hors thread
  principal** (pures données, aucun listener). Exemple **`npcs/guard.yml`** livré (auto-copié au
  premier démarrage comme `dialogues/guard.yml`).
- **`NpcDefinitionYaml.render()`** — texte YAML déterministe, re-parsable à l'identique.
- **`NpcDefinitionStore`** — `create` / `update` : écriture **atomique** (tmp + `move`), **refuse
  l'écrasement** (`EXISTS`), refuse l'update d'un id absent (`NOT_FOUND`), recharge et vérifie que
  la définition se re-lit. Nom de fichier toujours `<id>.yml` — jamais un chemin fourni.
- **`QuestGiverEditor.setGiver(content, npcId)`** — transformation **texte pure** : remplace la
  ligne racine `giver:` si présente, sinon l'insère juste après `category:` (à défaut `id:`).
  Préserve commentaires, ordre, indentation, style de fin de ligne (LF/CRLF). Idempotent.
- **`QuestGiverStore`** — localise le fichier de la quête **par son `id:`** (jamais par un chemin),
  applique `QuestGiverEditor`, écrit atomiquement.

### 2. `NpcCatalog` V2

- Nouvelle entrée `LogicalDefinition` (projection sans Bukkit de `NpcDefinition`).
- `NpcRow` gagne `logicalDefinitionPresent`, `citizensBindingPresent`, `enabled`, `description`,
  `role`, `definedDialogueId`, **`state`** (`LINKED` / `NOT_LINKED` / `DISABLED` /
  `CITIZENS_ORPHAN` / `UNDEFINED_REFERENCE` / `BROKEN`).
- `displayName` privilégie la définition, puis le `speaker` du dialogue.
- **Registre canonique** : `definedIds` (ids **avec** définition — la source de vérité) exposé à
  part ; `canonicalIds` = union avec les ids encore seulement référencés (transition, #4 du brief).
- Anomalies V2 : `DUPLICATE_DEFINITION` / `DUPLICATE_BINDING` / `DIALOGUE_MISSING` (`error`) ;
  `NO_DEFINITION` (`error` — id référencé par quête/dialogue/binding **sans** définition, « à
  migrer ») ; `BINDING_NO_DEFINITION` (`error` — binding sans définition, + suggestion d'id défini
  proche par Levenshtein ≤ 2, cas `garde`→`guard`) ; `NOT_LINKED` / `DISABLED` /
  `GIVER_NO_DIALOGUE` (`info`).

### 3. Protocole agent

- **`npc.list`** : payload enrichi (nouveaux champs `NpcSummary`, `definedIds`, compteurs
  `withDefinition` / `withoutDefinition`).
- **`npc.definition.create` / `npc.definition.update` / `quest.giver.set`** — nouvelles mutations :
  `AgentActionType` ↔ `AgentActionCatalog` (miroir strict), **permission dédiée**
  (`NPC_WRITE` / `QUEST_GIVER_WRITE`), `confirm=true` obligatoire côté panel, **auditées**,
  validation **3 couches** (catalogue panel → `AgentActionExecutor` patterns bornés → store).
  **Jamais** de YAML brut, jamais de chemin arbitraire, jamais de commande console.
- Exécution : l'IO fichier tourne sur le **thread poll async** (pas le thread principal, conforme
  CLAUDE.md) ; `quest.giver.set` rebascule sur le **thread principal** uniquement pour
  `QuestProgressEngine.reloadQuestDefinitions()` (qui (dé)branche des listeners).
- `BukkitAgentActions` : constructeur reçoit `YamlNpcEngine`, `NpcDefinitionStore`,
  `QuestGiverStore` (wiring `RPGQuestBootstrap`).

### 4. Control Panel `/npcs`

- `AgentPages` reçoit `PermissionService`.
- Page : résumé (`total` / avec définition / sans définition / liés Citizens / avec
  avertissement) ; **formulaire « Créer une définition PNJ »** (id libre) ; par carte,
  **deux blocs distincts** « Définition RPGQuest » et « Binding Citizens » ; badge d'**état**
  (`npcStateBadge`) ; anomalies (pastille de sévérité) ; relations (dialogue en jeu, dialogue
  déclaré, donne, objectif « parler à ») ; **actions** en `<details>` :
  - carte sans définition → « Créer la définition « `<id>` » » (pré-remplie) ;
  - carte avec définition → « Éditer la définition » (`display_name` / `dialogue` / `role` /
    `enabled` — **jamais l'id**) ;
  - « Attribuer une quête (giver) » → select d'une quête (catalogue `quest.list` local) + confirm
    → `quest.giver.set`.
  - Bloc `<details>` « Registre canonique » : **Définis** vs **Tous**.
- Réutilise strictement les composants existants (`entity-card`, `MiniText`, `Ui`, `formStart`,
  `confirmBox`, `idSelect`). Responsive (cartes + `meta-line`, aucun tableau large).

### 5. Ce qui n'est **pas** fait (délibérément — voir « Limitations »)

- Aucun **spawn / binding Citizens depuis le web** (nécessiterait une validation en jeu).
- Aucun **éditeur de dialogues** (les dialogues sont référencés / comptés, pas édités).
- **#66** : le câblage de `/rpgadmin npc tag` (validation + autocomplétion + « vouliez-vous
  dire ? ») n'est **pas** fait (touche `RpgAdminCommand`, exige une validation manuelle). La
  logique métier pure réutilisable (`NpcCatalog.closestCanonical`, `definedIds`) est en place.
- Pas de **suppression** de définition (pas d'action destructive dans cette V2).
- Pas d'enrichissement live (position / monde / PNJ Citizens non tagués).

## Fichiers créés
Plugin : `npc/model/NpcDefinition.java`, `npc/NpcLoadIssue.java`, `npc/NpcLoadReport.java`,
`npc/NpcDefinitionParser.java`, `npc/NpcDefinitionLoader.java`, `npc/NpcDefinitionYaml.java`,
`npc/YamlNpcEngine.java`, `npc/NpcDefinitionStore.java`, `npc/QuestGiverEditor.java`,
`npc/QuestGiverStore.java`, `src/main/resources/npcs/guard.yml`.
Tests : `npc/NpcDefinitionParserTest`, `NpcDefinitionLoaderTest`, `NpcDefinitionYamlTest`,
`NpcDefinitionStoreTest`, `QuestGiverEditorTest`, `QuestGiverStoreTest`.
Docs : `NPC_FORMAT.md`, `docs/claude-reports/2026-09-08_1235_npc-v2-declarative.md` (ce rapport).

## Fichiers modifiés
Plugin : `npc/NpcCatalog.java`, `web/agent/AgentActions.java`, `web/agent/AgentActionType.java`,
`web/agent/BukkitAgentActions.java`, `web/agent/AgentActionExecutor.java`,
`bootstrap/RPGQuestBootstrap.java`, `web/agent/StubAgentActions.java` (test),
`web/agent/AgentActionExecutorTest.java` (test), `web/agent/NpcListPayloadTest.java` (test),
`npc/NpcCatalogTest.java` (test).
Panel : `authz/Permission.java`, `agent/AgentActionCatalog.java`, `web/AgentPages.java`,
`web/PanelApp.java`, `web/NpcsCatalogTest.java` (test).
Docs : `docs/control-panel/AGENT.md`, `docs/control-panel/ROADMAP.md`, `docs/current_state.md`,
`docs/RPGQUEST_BIBLE.md`, `docs/deployment/SERVER_CHANGELOG.md`, `README.md`,
`docs/claude-reports/README.md`.

## Base de données / migrations
Aucune. `npc_citizens_bindings` est **lu** tel quel.

## Configuration / données
- Nouveau dossier `plugins/RPGQuest/npcs/` + `npcs/guard.yml`, **créés automatiquement** au
  démarrage. Aucun fichier existant modifié par le déploiement.
- Les créations / éditions faites via le Control Panel écrivent dans `npcs/` (agent, sur le
  serveur) ; `quest.giver.set` édite **une ligne** d'un fichier de `quests/`.

## Payload `npc.list` — avant / après

**Avant (V1)** : `{id, displayName?, citizensNumericId?, bindingCount, bound, hasDialogue,
dialogueId?, …, sources[], warnings[]}` + `canonicalIds` + `total/bound/unbound/withWarnings`.

**Après (V2)** : par PNJ `+ logicalDefinitionPresent, citizensBindingPresent, enabled,
description?, role?, definedDialogueId?, state` ; warnings V2 (`NO_DEFINITION` /
`BINDING_NO_DEFINITION` / `DIALOGUE_MISSING` / `NOT_LINKED` / `DISABLED` …). Top-niveau
`+ definedIds[]` (source canonique) `+ withDefinition / withoutDefinition`.

Nouvelles actions : `npc.definition.create` / `npc.definition.update`
(params `npc_id`, `display_name`, `dialogue_id?`, `role?`, `enabled`) ; `quest.giver.set`
(params `quest_id`, `npc_id`). Résultat = `MutationResult` standard (`ok`, `code`, `message`,
`effects`).

## Tests automatiques

| Commande | Résultat |
|---|---|
| `./gradlew :test` (plugin) | **1127 tests, 0 échec, 0 erreur** |
| `./gradlew :control-panel:test` | **107 tests, 0 échec, 0 erreur** |
| `./gradlew build` | **BUILD SUCCESSFUL** |

Nouveaux tests couvrant la demande §15 :
- `NpcDefinitionParserTest` (6) — définition minimale / complète, id invalide, doublon de champs,
  normalisation `dialogue` (clé simple → `rpgquest:clé`), `enabled` strict.
- `NpcDefinitionLoaderTest` (3) — fichier invalide isolé, id dupliqué rejeté, dossier absent = 0.
- `NpcDefinitionYamlTest` (3) — round-trip render → parse.
- `NpcDefinitionStoreTest` (4) — création + reload identique, **refus d'écrasement**, update
  (remplace / échoue si absent), remontée des erreurs de chargement.
- `QuestGiverEditorTest` (5) — insertion après `category:`, remplacement en place, idempotence,
  repli après `id:`, préservation CRLF.
- `QuestGiverStoreTest` (4) — bon fichier ciblé + commentaires préservés + autre fichier intact,
  id nu normalisé, quête inconnue = échec lisible, no-op si déjà en place.
- `NpcCatalogTest` (10, réécrit V2) — `LINKED` propre, `NOT_LINKED` info, `NO_DEFINITION` erreur
  de contenu, `BINDING_NO_DEFINITION` + suggestion `garde`→`guard`, `DIALOGUE_MISSING` → `BROKEN`,
  `DISABLED`, `DUPLICATE_BINDING`, `canonicalIds` vs `definedIds`, compteurs + ordre.
- `web.agent.NpcListPayloadTest` (3, réécrit) — **JSON réel** : séparation définition / binding,
  `definedIds`, `state`, `NO_DEFINITION` sérialisé.
- `web.agent.AgentActionExecutorTest` (+2) — `npc.definition.create` (id invalide / display_name
  manquant → `REJECTED` ; ok → délégué) ; `quest.giver.set` (npc_id manquant → `REJECTED` ; ok).
- `panel.web.NpcsCatalogTest` (5, réécrit) — deux blocs, badges d'état, « sans définition »,
  formulaires create/update/giver présents, ordre (erreur d'abord), registre canonique, création
  validée + mise en file, refus sans `confirm` / id invalide, `quest.giver.set` mis en file.

## Tests manuels à effectuer
- `PENDING MANUAL VALIDATION` : vue **navigateur authentifiée** du Control Panel AWS (login owner
  → `/npcs`) — mot de passe owner inconnu de la session.
- `PENDING MANUAL VALIDATION` : scan console VeryGames DEV.

## Résultat attendu
Vraie notion de **PNJ logique** ; `/npcs` affiche définition vs Citizens + état ; création +
édition limitée depuis le panel ; attribution de quête sûre (édition minimale du YAML) ;
diagnostics de références ; source canonique des ids ; compatibilité avec l'existant ; tests
verts ; AWS + agent DEV déployés.

## Reset / retour à l'état initial
- Supprimer les fichiers `npcs/*.yml` créés (inertes avec l'ancien JAR).
- Un `quest.giver.set` se défait en éditant / restaurant le YAML de la quête.
- Aucune donnée SQL touchée.

## Déploiement VeryGames

### À transférer
- Le **seul** JAR RPGQuest.

### Ne PAS transférer/altérer
- `data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, `plugadmin-agent.properties`.
  Les YAML `quests/` ne sont **pas** transférés par le déploiement (seul l'agent, en place, les
  édite sur demande via `quest.giver.set`).

### Redémarrage requis
- Oui (RCON `stop` → relance auto). Le dossier `npcs/` + `guard.yml` sont créés au démarrage.

### Migration automatique
- Aucune.

### Exécuté cette session (~12:35–12:40 UTC, `1340dda`)
1. **AWS Control Panel — DÉPLOYÉ** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
   `/opt/plugadmin/releases/20260908-123552`, `systemctl restart` → `active` (PID 166440). JAR
   déployé sha256 `01f14c6071584261f932eca36cc38ff11769999bc3c1ff3d60be1c3819dd51a5` == build de
   la branche. `/health` local + public `ONLINE` ×3 ; `/npcs` + `/dashboard` (anon) → 303 ;
   `dig.lodygames.com` / `lodylands.com` → 200 ; nginx/secrets/TLS non touchés.
   Rollback : `scripts/plugadmin/rollback.sh app` → release `20260908-123552`.
2. **VeryGames DEV — EN ATTENTE (panne FTP externe)** : `scripts/deploy-verygames.sh -y` a
   construit le JAR (`./gradlew test`+`build` verts, sha256
   `261d7a378c58f714a35c1bea861b2cdf69727ad73b94b2f73cf9e9c8a30c3549`, 1 317 563 o) puis a
   **échoué à la connexion FTP** (`curl (28) Operation timed out after 20002 ms`). Le script
   **abandonne avant toute écriture** : le serveur DEV est **inchangé**, il tourne toujours le JAR
   de la session précédente (`12786cb3…`, `npc.list` V1). `--check` répété : FTP `si-16041.dg.vg`
   injoignable (il répondait ~15 min plus tôt). **Aucune donnée touchée, aucun rollback nécessaire.**

   À exécuter dès que le FTP VeryGames répond à nouveau (arbre Git propre requis) :
   ```
   scripts/deploy-verygames.sh -y            # JAR seul ; npcs/ + guard.yml créés au (re)démarrage
   scripts/verygames-restart.sh
   ```
   puis la validation ci-dessous.

### Validation réelle (VeryGames DEV) — `PENDING` (dépend du déploiement ci-dessus)
À vérifier une fois le JAR déployé + serveur redémarré :
- `/plugins` (RCON) → RPGQuest en vert ; log `Chargement des PNJ : N définition(s), 0 erreur(s).`
- heartbeat agent OK ; aucun `ERROR` `journalctl -u plugadmin`.
- action `npc.list` (injectée dans `control-panel.db`) → **SUCCESS** ; `details.npcs[]` porte
  `logicalDefinitionPresent` / `state` ; `definedIds` contient `guard` ; les ids non migrés
  (`guide`/`help`/`jeff`/`jo`/`junior`/`libraire`/`woodcutter_bob`) sont en `NO_DEFINITION` (erreur
  de contenu attendue).
- action `npc.definition.create` (`npc_id=woodcutter_bob`, `display_name=Bûcheron Bob`,
  `dialogue_id=rpgquest:woodcutter_bob`) → **SUCCESS** ; fichier `npcs/woodcutter_bob.yml` créé ;
  `npc.list` suivant → `woodcutter_bob` `state: NOT_LINKED` (plus `NO_DEFINITION`).
- action `quest.giver.set` (`quest_id=rpgquest:woodcutters_request`, `npc_id=woodcutter_bob`) →
  **SUCCESS** ; le YAML de la quête contient `giver: woodcutter_bob`, commentaires préservés.

## Rollback
- **VeryGames** : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Supprimer les `npcs/*.yml` créés si besoin ; restaurer un YAML de quête édité par
  `quest.giver.set`.
- **AWS** : `scripts/plugadmin/rollback.sh app` → release `20260908-123552`.
- Aucune migration à défaire.

## Logs / diagnostic
- Démarrage plugin : `Chargement des PNJ : N définition(s), 0 erreur(s).`
- `journalctl -u plugadmin` : `event=panel_started port=8090`, aucun `ERROR`.

## Documentation mise à jour
- `NPC_FORMAT.md` (nouveau) ; `docs/control-panel/AGENT.md` (payload `npc.list` V2 + « Écritures de
  contenu PNJ ») ; `docs/RPGQUEST_BIBLE.md` §5 ; `docs/current_state.md` ;
  `docs/control-panel/ROADMAP.md` ; `docs/deployment/SERVER_CHANGELOG.md` ; `README.md` ;
  `docs/claude-reports/README.md`.

## Limitations / travail restant
- **`NO_DEFINITION` en erreur** : sur un serveur non encore migré (DEV compris), les ids
  `guide` / `help` / `jeff` / `jo` / `junior` / `libraire` / `woodcutter_bob` apparaîtront en
  erreur de contenu tant qu'aucune définition `npcs/<id>.yml` n'existe. C'est **le diagnostic
  attendu** (§5 du brief) et il motive la migration — non bloquant. Migration = créer les
  définitions (fichier ou bouton du panel).
- **Spawn / binding Citizens depuis le web** : non fait (validation en jeu requise). Prochaine
  étape naturelle : une action `npc.citizens.link` prenant un id Citizens existant, ou un guide
  pas-à-pas vers `/rpgadmin npc tag`.
- **Éditeur de dialogues** : non fait. Si besoin réel → page `/dialogues` + actions dédiées
  (ticket séparé).
- **#66** (`/rpgadmin npc tag` validé/autocomplété) : logique métier prête (`definedIds`,
  `closestCanonical`) ; câblage `RpgAdminCommand` + validation manuelle Minecraft = chantier à part.
- **Suppression** d'une définition : volontairement absente de cette V2 (action destructive).
- **`quest.giver.set` et le format YAML** : l'éditeur touche **une seule ligne racine** ; il
  suppose `giver:` au niveau racine (jamais indenté). Vérifié par tests ; documenté.

## Prochaine étape suggérée
- Migrer le contenu DEV : créer `npcs/*.yml` pour les ids référencés (via le panel), puis
  `quest.giver.set` pour recâbler les donneurs.
- `npc.citizens.link` (id Citizens existant) — première brique vers la création physique.
- Traiter #66 en réutilisant `definedIds` comme source unique de la tab-complétion.
