# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 11:47 (locale machine, UTC)
* Sujet : Action agent `npc.list` + page Control Panel `/npcs` (V1) — issues #66 & #75
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : code `c4b0d96` + docs `9fa57d1` ; ce rapport ajoute un 3e commit
* Début de la tâche : 2026-09-08 11:22:41
* Fin de la tâche : 2026-09-08 11:53:30
* Durée totale : 00:30:49

## Demande

Créer une **V1 utile** de `/npcs` dans le Control Panel : page d'administration **principalement
en lecture** qui montre les PNJ RPGQuest connus, leur identité technique, leurs relations avec
dialogues/quêtes, et les problèmes de configuration. Ne pas en faire un éditeur de PNJ. Ajouter
une action agent `npc.list` si aucune n'existe. Réutiliser les données structurées de `quest.list`.
Auditer #66 (validation/autocomplétion des ids RPGQuest) et rester séparé si c'est un chantier
indépendant. Aucune validation Minecraft manuelle. Hors scope : MariaDB #42, Claims, onboarding,
permissions `/rpgadmin` générales, progression joueur, gameplay des quêtes, portail public,
nginx/TLS, merge vers `main`.

## Analyse

### Où sont stockées les données PNJ

| Donnée | Source réelle | Lisible sans toucher au gameplay |
|---|---|---|
| Liaison **PNJ Citizens ↔ id RPGQuest** | Table SQLite `npc_citizens_bindings` (`citizens_uuid` PK, `citizens_numeric_id`, `npc_id`), migration V12. Chargée par `NpcBindingRepository.loadAll()`. | Oui — `SELECT` déjà asynchrone. |
| Tag d'une **entité vanilla** (Citizens absent) | `PersistentDataContainer` `rpgquest:npc_id` **sur l'entité** — dans aucune base. | Non — nécessiterait un scan d'entités de tous les mondes chargés (chunks déchargés = invisible). **Hors périmètre V1.** |
| **Dialogue ↔ PNJ** | *Convention* : cliquer une entité taguée `<id>` ouvre le dialogue dont l'id est `rpgquest:<id>` (`DialogueNpcInteractListener` / `DialogueCitizensNpcInteractListener`). Aucun champ de binding explicite dans `DialogueDefinition`. | Oui — `YamlDialogueEngine.dialogues()` en mémoire. |
| **Nom affiché** d'un PNJ | Pas de source fiable hors jeu. Le plus proche : `speaker` du nœud de départ du dialogue (ex. `guard.yml` → « Garde »). | Oui (dérivé du dialogue). |
| **Quête → PNJ** | `QuestDefinition.giver()` (#75) + `TalkToNpcObjective.npcId()` sur tous les steps. | Oui — `YamlQuestEngine.quests()` en mémoire. |
| Position / monde | `NPC.getStoredLocation()` (Citizens) ou entité live. | Non — lecture du monde. **Hors périmètre V1.** |

### Commandes / agent existants

- `/rpgadmin npc` = `tag` / `untag` / `info` uniquement — **tous ciblent l'entité regardée**, aucune
  liste globale. `RpgAdminCommand` ne reçoit **pas** `dialogueEngine` au bootstrap.
- Agent : `AgentActions` n'avait **aucune** opération PNJ. `BukkitAgentActions` n'avait ni
  `dialogueEngine`, ni `NpcIdentityService`, ni `NpcBindingRepository`.

### #66 (validation/autocomplétion `/rpgadmin npc tag`)

Bug d'origine : PNJ « Garde » tagué `garde` au lieu de l'id canonique `guard` → rien ne se
déclenchait. La proposition de #66 (autocomplétion + refus des ids inconnus + « vouliez-vous
dire ? ») demande de modifier `RpgAdminCommand` (tab-complétion, validation, `--force`), d'ajouter
`dialogueEngine` à son constructeur, et **exige une validation manuelle Minecraft** — donc un
chantier à part. **Décision : #66 reste séparée.** Ce qui est fait ici : la **dérivation du
registre d'ids canoniques** (union des ids de dialogues + quêtes) est implémentée dans
`NpcCatalog` (classe pure réutilisable), exposée dans le payload (`canonicalIds`) et affichée sur
`/npcs` ; la logique « id le plus proche » (Levenshtein ≤ 2) est réutilisée **en lecture** dans le
panel pour suggérer `garde` → `guard` sur un tag orphelin. Le câblage côté commande reste à faire.

## Travail effectué

### 1. `NpcCatalog` — dérivation pure (plugin, `com.lodygames.rpgquest.npc`)

Aucune dépendance Bukkit : entrées = listes de records simples (`DialogueLink`, `QuestLink`,
`CitizensBinding`) → `Result { List<NpcRow>, List<String> canonicalIds, boolean citizensAvailable,
int total/bound/unbound/withWarnings }`. Testable en JUnit pur.

Un id apparaît dans l'`allIds` = union {ids de dialogues `rpgquest:*`} ∪ {`giver:` des quêtes} ∪
{cibles `TALK_TO_NPC`} ∪ {ids des liaisons Citizens}. `canonicalIds` = les 3 premières seulement
(**jamais** un tag Citizens : c'est le principe même de la détection `garde` vs `guard`).

Anomalies (`Warning{code, severity, message}`) :

| Code | Sévérité | Condition |
|---|---|---|
| `DUPLICATE_BINDING` | `error` | ≥ 2 liaisons Citizens pour le même id |
| `QUEST_REF_NO_NPC` | `warning` | id référencé par ≥ 1 quête, **0 liaison Citizens** (dégradé en `info` si Citizens inactif) |
| `DIALOGUE_NO_NPC` | `info` | dialogue défini, 0 liaison, aucune quête ne le référence |
| `TAGGED_UNUSED` | `info` | ≥ 1 liaison, ni dialogue ni quête — + suggestion d'id canonique proche (Levenshtein ≤ 2) |
| `GIVER_NO_DIALOGUE` | `info` | ≥ 1 liaison, `giver:` d'une quête, mais pas de dialogue `rpgquest:<id>` |

Tri de sortie : erreurs, puis avertissements, puis PNJ sains (nom lisible en second critère).

**Cas « garde vs guard »** : `guard` → `QUEST_REF_NO_NPC` (référencé mais rien de tagué) ;
`garde` → `TAGGED_UNUSED` + « Id canonique proche : « guard » ? ». Les deux cartes côte à côte
rendent le typo évident, sans jamais lire le monde.

### 2. Protocole agent — `npc.list` (lecture)

- `AgentActionType.NPC_LIST("npc.list")` ; `AgentActionCatalog` (panel) : `add("npc.list",
  Permission.NPC_READ, false, false, …)` — miroir strict, aucune mutation.
- `AgentActions` : records `NpcSummary` / `NpcWarning` / `NpcCatalogView` +
  `CompletableFuture<NpcCatalogView> npcDefinitions()`.
- `BukkitAgentActions` : constructeur reçoit désormais `YamlDialogueEngine`, `NpcIdentityService`,
  `NpcBindingRepository` (wiring `RPGQuestBootstrap`). `npcDefinitions()` extrait les `DialogueLink`
  (nœuds, choix, `START_QUEST` du graphe, `speaker`) et `QuestLink` (giver + `TALK_TO_NPC`), lit
  les liaisons via `npcBindingRepository.loadAll()` (async, hors thread principal), appelle
  `NpcCatalog.build(...)`. **Aucun `onMain`, aucune lecture du monde.**
- `AgentActionExecutor.npcList()` : sérialise en `Map` imbriquées (records non sérialisables) —
  `npcs[]`, `canonicalIds`, `citizensAvailable`, compteurs.

### 3. Page `/npcs` (panel)

- `Layout.nav` : « PNJ » → `/npcs`, **activée** (plus « à venir ») ; `/npc` retiré du bloc
  placeholder ; `PanelApp` route `/npcs` (`handleBusinessPage`, `Permission.NPC_READ`) + `/npcs`
  ajouté à la whitelist de `return`.
- `Ui.severity(level)` : pastille `ERREUR` / `ATTENTION` / `INFO` (réutilise les styles de
  pastille existants, aucun CSS nouveau).
- `AgentPages.npcs()` + `renderNpcCard()` : mêmes composants que `/quests` / `/stories`
  (`entity-card`, `MiniText`, `Ui.id` copiable, `Ui.badge`, `meta-line`). Bouton « Rafraîchir »
  = action `npc.list`. Bandeau si Citizens inactif. Ligne « Résumé » (total / tagués / sans tag /
  avec avertissement). Cartes triées (anomalies d'abord). Chaque carte : nom lisible → id RPGQuest
  copiable, badge « Citizens #n » ou pastille « non tagué », badge « dialogue » ; liste des
  anomalies (pastille de sévérité + message + code copiable) ; `meta-line` Dialogue (id + nœuds/
  choix), « Le dialogue démarre », « Donne », « Objectif « parler à » » — titres de quête résolus
  depuis le `quest.list` local (repli `prettifyId`). `<details>` « IDs canoniques connus » (chips
  copiables + rappel #66). Responsive : cartes + `meta-line` compactes, aucun tableau large.

### 4. Ce qui n'est PAS fait (volontairement)

- Pas d'action de reload de contenu depuis le web (V1 = lecture ; `Permission.ACTION_CONTENT_RELOAD`
  existe mais reste non câblée).
- Pas de création / suppression / téléportation de PNJ.
- Pas de page `/dialogues` séparée (les dialogues sont seulement comptés ici).
- Pas de lecture du monde (position, monde, PNJ Citizens *non tagués*).
- `/rpgadmin npc tag` inchangé (#66).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/npc/NpcCatalog.java`
- `src/test/java/com/lodygames/rpgquest/npc/NpcCatalogTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/NpcListPayloadTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcsCatalogTest.java`
- `docs/claude-reports/2026-09-08_1147_npc-list-page-npcs-v1.md` (ce rapport)

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/{Layout,PanelApp,Ui,AgentPages}.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/{AuthenticatedSmokeTest,BusinessPagesTest}.java`
- `docs/control-panel/AGENT.md`, `docs/control-panel/ROADMAP.md`, `docs/current_state.md`
- `docs/RPGQUEST_BIBLE.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. `npc_citizens_bindings` (migration V12) est **lu** tel quel, jamais modifié.

## Configuration / données
Aucun nouveau fichier, aucune nouvelle clé, aucun contenu YAML modifié.

## Payload `npc.list` (nouveau)

`details` :
```
{
  "citizensAvailable": true,
  "total": N, "bound": N, "unbound": N, "withWarnings": N,
  "canonicalIds": ["guard", "libraire", ...],           // ids attendus par dialogues + quêtes
  "npcs": [
    { "id": "guard", "displayName": "Garde", "citizensNumericId": 7,
      "bindingCount": 1, "bound": true,
      "hasDialogue": true, "dialogueId": "rpgquest:guard",
      "dialogueNodes": 6, "dialogueChoices": 9,
      "dialogueStartsQuests": ["rpgquest:first_steps"],
      "questsGiven": ["rpgquest:crystal_hunt"],
      "questsReferenced": ["rpgquest:crystal_hunt"],
      "sources": ["BINDING","DIALOGUE","QUEST_GIVER","QUEST_TALK"],
      "warnings": [] },
    { "id": "garde", "displayName": null, "citizensNumericId": 3, "bindingCount": 1, "bound": true,
      "hasDialogue": false, "dialogueId": null, ...,
      "warnings": [ { "code": "TAGGED_UNUSED", "severity": "info",
        "message": "PNJ tagué « garde » … Id canonique proche : « guard » ?" } ] }
  ]
}
```
Données absentes = `null` / listes vides, jamais inventées.

## Tests automatiques

| Commande | Résultat |
|---|---|
| `./gradlew :test` (plugin) | **1099 tests, 0 échec, 0 erreur** |
| `./gradlew :control-panel:test` | **105 tests, 0 échec, 0 erreur** |
| `./gradlew build` | **BUILD SUCCESSFUL** |

Tests neufs / étendus :
- `npc.NpcCatalogTest` (9) — PNJ cohérent (aucune anomalie, `displayName` dérivé), `QUEST_REF_NO_NPC`,
  dégradation `info` si Citizens inactif, `TAGGED_UNUSED` + suggestion `garde`→`guard`,
  `DUPLICATE_BINDING`, `DIALOGUE_NO_NPC` + `GIVER_NO_DIALOGUE`, `canonicalIds` (exclut les tags,
  trié), compteurs + ordre de tri, `closestCanonical` (near-miss vs rien).
- `web.agent.NpcListPayloadTest` (3) — **JSON réel** de `npc.list` : whitelist, structure
  `details`, warnings sérialisés avec sévérité + suggestion, catalogue vide toujours `SUCCESS`,
  type inconnu (`npc.delete`) → `REJECTED`.
- `web.agent.AgentActionExecutorTest` (+1) — `npc.list` → catalogue + `canonicalIds` + `withWarnings`.
- `panel.web.NpcsCatalogTest` (2) — rendu : nom lisible, id RPGQuest/Citizens copiables, dialogue,
  « Donne » / « Objectif « parler à » » / « Le dialogue démarre », pastilles `ATTENTION`/`INFO`,
  code d'anomalie, « non tagué », résumé, bloc « IDs canoniques connus », ordre (anomalies
  d'abord), état vide avant tout `npc.list`.
- `panel.web.AuthenticatedSmokeTest` / `BusinessPagesTest` — `/npcs` ajouté (protection session,
  formulaire `npc.list`, rendu authentifié `<h1>PNJ</h1>`).

## Tests manuels à effectuer
- `PENDING MANUAL VALIDATION` : vue **navigateur authentifiée** du Control Panel AWS (login owner
  → `/npcs`) — le mot de passe owner n'est pas connu de la session.
- `PENDING MANUAL VALIDATION` : scan complet de la console VeryGames DEV (l'agent + `journalctl -u
  plugadmin` sont propres, RPGQuest chargé — vérifié).

## Résultat attendu
- `/npcs` actif, catalogue PNJ réel, ids canoniques visibles, relations dialogues/quêtes visibles,
  diagnostics de configuration utiles (dont le cas `garde`/`guard`), tests verts, AWS + agent DEV
  déployés, aucun changement gameplay.

## Reset / retour à l'état initial
Aucune donnée à réinitialiser. Rollback = restauration des JAR (voir ci-dessous).

## Déploiement VeryGames

### À transférer
- Le **seul** JAR RPGQuest (`rpgquest-0.1.0-SNAPSHOT.jar`) — pour que l'agent connaisse le type
  `npc.list`.

### Ne PAS transférer/altérer
- `data.db`, `config.yml`, `messages.yml`, mondes, `RPGQuest/Citizens/`, YAML de contenu,
  `plugadmin-agent.properties`.

### Redémarrage requis
- Oui (RCON `stop` → relance auto VeryGames).

### Migration automatique
- Aucune.

### Exécuté cette session (~11:46–11:50 UTC, `9fa57d1`)
1. **AWS** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
   `/opt/plugadmin/releases/20260908-114604`, `systemctl restart` → `active` (PID 146189,
   `event=panel_started port=8090`). JAR déployé sha256 `2bf90a25…` == build de la branche.
   `/health` local + public `ONLINE` ×3 ; `/npcs` (anon) → 303 `/login` (route **active**, plus un
   placeholder) ; autres vhosts (`dig.lodygames.com`, `lodylands.com`, `www.lodylands.com`) → 200 ;
   nginx/secrets/TLS non touchés.
2. **VeryGames DEV** : `scripts/deploy-verygames.sh -y` → `./gradlew test`+`build` OK, backup auto
   `rpgquest-20260908T114637Z-predeploy.jar` (sha256 `4fbaa345…`), transfert FTP atomique du JAR
   **sha256 `12786cb3fc0d997e55d3f7eba2f1432cdcdcaa479ae4b6100fcf8d83fa122ba3`** (1 282 340 o,
   distant == local) ; puis `scripts/verygames-restart.sh`.

### Vérifications VeryGames (exécutées)
- `/plugins` (RCON) : **RPGQuest en vert** (+ Citizens) ; `rpgquest version` → `v0.1.0-SNAPSHOT`.
- Heartbeat agent (`server_state=ONLINE`, `uptime_seconds=65` → restart pris en compte) ;
  aucune ligne `ERROR` dans `journalctl -u plugadmin`.

### Validation `npc.list` réelle (exécutée)
Action `PENDING` injectée dans `agent_action` (`control-panel.db`), relevée + exécutée par
l'agent DEV → **SUCCESS** « 8 PNJ RPGQuest (1 avec avertissement). ». `details` réellement reçu :

- `citizensAvailable=true`, `total=8`, `bound=7`, `unbound=1`, `withWarnings=1` ;
- `canonicalIds = [guard, guide, help, jeff, jo, junior, libraire, woodcutter_bob]` ;
- `rpgquest:guard` : `displayName:"Garde"`, `citizensNumericId:6`, `dialogueId:"rpgquest:guard"`
  (5 nœuds / 8 choix), `questsGiven:["rpgquest:crystal_hunt"]`,
  `questsReferenced:["rpgquest:crystal_hunt"]`,
  `dialogueStartsQuests:["rpgquest:first_steps","rpgquest:crystal_hunt"]`, `warnings:[]` ;
- **anomalie réelle détectée** : `woodcutter_bob` → `QUEST_REF_NO_NPC` (`warning`) — référencé
  par `woodcutters_request` (objectif `TALK_TO_NPC`) mais **aucun PNJ Citizens tagué** sur ce
  serveur, donc la quête n'est pas finissable en jeu ;
- les 6 autres PNJ (`guide`, `help`, `jeff`, `jo`, `junior`, `libraire`) sont sains, triés après
  `woodcutter_bob`.

## Rollback
- **VeryGames** : `scripts/rollback-verygames.sh --latest` →
  `rpgquest-20260908T114637Z-predeploy.jar` (sha256 `4fbaa345…`), puis `scripts/verygames-restart.sh`.
- **AWS** : `scripts/plugadmin/rollback.sh app` → release `20260908-114604`.
- Aucune migration à défaire.

## Logs / diagnostic
- `journalctl -u plugadmin` : `event=panel_started port=8090` après swap, aucun `ERROR`.
- Actions de validation visibles dans `/agents` du panel (`created_by=claude-validation-npclist`).

## Documentation mise à jour
- `docs/control-panel/AGENT.md` — `npc.list` dans le tableau des lectures + section « Payload
  `npc.list` » (npcs[], warnings, canonicalIds, citizensAvailable).
- `docs/control-panel/ROADMAP.md` — Étape 3 : `/npcs` V1 livrée.
- `docs/current_state.md` — page `/npcs` + `NpcCatalog`.
- `docs/RPGQUEST_BIBLE.md` §5 — renvoi vers `/npcs` ; rappel que #66 n'est pas câblée.
- `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-08 (`npc.list`).
- `docs/claude-reports/README.md` — ligne d'index.

## Limitations / travail restant
- **Pas de position / monde** dans le catalogue ; **pas de détection des PNJ Citizens *non
  tagués*** (nécessiterait un scan de la registry Citizens sur le thread principal). Ce sont les
  deux évolutions naturelles de la page — à faire dans un lot dédié si souhaité.
- **#66 non câblée** : la validation / autocomplétion de `/rpgadmin npc tag` (refus des ids
  inconnus, « vouliez-vous dire ? », `--force`) reste à implémenter dans `RpgAdminCommand`
  (+ `dialogueEngine` à son constructeur + validation manuelle Minecraft). `NpcCatalog` fournit
  déjà la dérivation des ids canoniques et `closestCanonical()` réutilisables.
- `displayName` n'est renseigné que si un dialogue `rpgquest:<id>` existe (le `speaker` de son
  nœud de départ). Sinon le panel prettifie l'id.
- Pas de page `/dialogues` : les dialogues sont seulement comptés dans `npc.list`. À ouvrir en
  ticket séparé si un vrai besoin apparaît.
- Vue navigateur authentifiée : `PENDING MANUAL VALIDATION`.

## Prochaine étape suggérée
- Enrichissement live optionnel de `npc.list` (position/monde, PNJ Citizens non tagués) via un
  `onMain` borné + une méthode « lister tous les PNJ Citizens » sur `CitizensNpcBridge`.
- Traiter #66 en réutilisant `NpcCatalog.canonicalIds` / `closestCanonical` comme source unique.
