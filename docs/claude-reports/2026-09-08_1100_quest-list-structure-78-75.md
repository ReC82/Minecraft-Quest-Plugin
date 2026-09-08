# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 11:00 (locale machine, UTC)
* Sujet : Protocole `quest.list` structuré (objectifs / récompenses / donneur) — issues #78 & #75
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `02642e9` (code `fb0986d` + docs `02642e9`) ; ce rapport ajoute un 3e commit
* Début de la tâche : 2026-09-08 10:29:43
* Fin de la tâche : 2026-09-08 11:09:30
* Durée totale : 00:39:47

## Demande

Faire évoluer proprement le protocole agent `quest.list` pour transporter des données
**structurées** (objectifs, récompenses) et permettre au Control Panel d'afficher les quêtes
sans reparser de chaînes métier (issue #78). Traiter en même temps #75 (donneur de quête) si
cela reste propre. Compatibilité descendante souhaitée. Aucune validation Minecraft manuelle.
Interdits : gameplay, Claims, onboarding, MariaDB #42, permissions `/rpgadmin`, merge vers `main`.

## Analyse

Chemin des données **avant** (audité) :

- `QuestDefinition` (record) — pas de champ donneur ; `steps[].objectives[]` = 7 records scellés
  (`Kill/Collect/Craft/Break/Place`-Objective, `TalkToNpcObjective`, `ReachLocationObjective`) ;
  `rewards[]` = 4 records (`Experience/Item/Variable/Command`-Reward).
- `BukkitAgentActions.questDefinitions()` **aplatit tout en chaînes** :
  `QuestObjective.describe(o) + " (x" + amount + ")"` → `"Tuer SPIDER (x5)"` ;
  `describeRewards()` → `"+100 XP"`, `"+1x DIAMOND_SWORD"`, `"variable CLAIM_TIER_1 = true"`,
  `"commande console : " + truncate(cmd, 60)` (**troncature à 60**).
- `AgentActions.QuestSummary(id, title, category, repeatable, prerequisites, steps, rewards)` +
  `QuestStepSummary(id, objectives:List<String>)`.
- `AgentActionExecutor.questList()` recopie ces champs en `Map`/`List` → `Json.write` (codec
  maison : records non sérialisables, on passe par des `LinkedHashMap`).
- Panel (aucune dépendance Paper) : `AgentPages.renderQuestCard` reparse via
  `MinecraftNames.humanizeTokens` (regex `\b[A-Z][A-Z0-9_]+\b` sur une phrase) et
  `RewardText.parse` (regex sur `"+Nx X"`, `"variable K = V"`, `"commande console : …"`).
- Donneur : `giver` absent de `QuestDefinition`, du parser YAML, du protocole, du panel. Les
  dialogues portent `START_QUEST:<id>` + un `speaker` — relation fragile, **écartée**
  (recommandation de la demande).

## Travail effectué

### 1. Protocole — champs structurés additifs (#78)

`AgentActions` :
- Nouveau record `ObjectiveSummary(String kind, String target, int amount, String raw)`.
  `kind` = `ObjectiveType.name()` ; `target` = jeton technique (entité / matériau / id PNJ /
  nom de monde) ; `raw` = description héritée (debug).
- Nouveau record `RewardSummary(String kind, int amount, String target, String value,
  String command, String raw)`. `kind` = `RewardType.name()` ; `command` = commande **complète**.
- `QuestStepSummary` : `+ List<ObjectiveSummary> objectiveDetails` (legacy `objectives` conservé).
- `QuestSummary` : `+ List<RewardSummary> rewardDetails`, `+ String giverId`, `+ String giverName`
  (legacy `rewards` conservé). `giverName` réservé, non rempli (pas de source fiable de nom
  d'affichage PNJ sans lookup Bukkit lourd — le panel prettifie l'id ; #75 exclut l'i18n).

`BukkitAgentActions` :
- `objectiveTarget(QuestObjective)` : `switch` scellé → `material().name()` / `entity().name()` /
  `npcId()` / `world()`.
- `rewardDetails(List<QuestReward>)` : `switch` scellé → `RewardSummary` typé, commande **NON
  tronquée**. `raw` = description héritée.
- `questDefinitions()` construit en parallèle les chaînes legacy et les structures ; passe
  `q.giver()`.

`AgentActionExecutor.questList()` :
- `objectiveRows()` / `rewardRows()` : sérialisent les records en `LinkedHashMap`.
- `steps[].objectiveDetails` + `rewardDetails` ajoutés à chaque ligne ; `giverId` (+ `giverName`
  si un jour rempli) ajouté **seulement si non vide**.

### 2. Donneur de quête (#75)

- `QuestDefinition` : nouveau composant `String giver` (nullable, dernier), aucune validation
  supplémentaire (optionnel).
- `QuestDefinitionParser.parseGiver()` : `giver:` optionnel ; présent mais blanc = **erreur de
  chargement** ; sinon `raw.trim()`.
- Contenu : `crystal_hunt.yml` → `giver: guard` (la quête a déjà l'étape `TALK_TO_NPC npc: guard`
  et parle du garde) ; `premiers_pas.yml` → `giver: libraire` ; `woodcutters_request.yml` →
  `giver: woodcutter_bob`.

### 3. Panel — consommer la structure (#78 / #75, cohérent #76 / #77)

- Nouveau `ObjectiveText.fromSummary(Map)` → `{label, rawTarget}`. Verbe FR par `kind`
  (`Tuer` / `Collecter` / `Fabriquer` / `Casser` / `Placer` / `Parler à` / `Se rendre dans`),
  nom via `MinecraftNames.humanize(target)` (matériau/entité) ou `MiniText.prettifyId` (PNJ/monde),
  quantité depuis `amount` (`(xN)` omis si N ≤ 1). Repli `raw` si `kind` inconnu. Jamais d'exception.
- `RewardText.fromSummary(Map)` : `EXPERIENCE` / `ITEM` / `VARIABLE` / `COMMAND` → libellé
  fonctionnel + valeur technique conservée ; `COMMAND` réutilise `fromCommand()` sur la commande
  **complète** ; `kind` inconnu → repli `parse(raw)`.
- `AgentPages.renderQuestCard` :
  - ligne « **Donneur** » (nom lisible `prettifyId` + id copiable `Ui.id`) si `giverId` présent ;
  - objectifs : `objectiveDetails` prioritaire (jeton `target` en `Ui.rawValue` secondaire) ;
    repli `MinecraftNames.humanizeTokens(join(objectives))` sinon ;
  - récompenses : `rewardDetails` prioritaire ; repli `RewardText.parse` sur les chaînes legacy sinon.
- `AgentActionCatalog` (whitelist miroir) : **inchangé** — `quest.list` déjà présent, aucun
  nouveau type d'action.

### 4. Compatibilité descendante

- Champs legacy (`steps[].objectives:String[]`, `rewards:String[]`) toujours produits et
  sérialisés **à l'identique** (troncature 60 incluse) → un agent VeryGames non redéployé et un
  panel non mis à jour continuent de fonctionner.
- Le panel neuf préfère la structure et retombe sur le legacy uniquement si `objectiveDetails` /
  `rewardDetails` sont absents.
- Legacy **déprécié** dans `AGENT.md` : à retirer quand tous les agents sont à jour.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ObjectiveText.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ObjectiveTextTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/AuthenticatedSmokeTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/QuestListPayloadTest.java`
- `docs/claude-reports/2026-09-08_1100_quest-list-structure-78-75.md` (ce rapport)

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
- `src/main/java/com/lodygames/rpgquest/quest/model/QuestDefinition.java`
- `src/main/java/com/lodygames/rpgquest/quest/QuestDefinitionParser.java`
- `src/main/resources/quests/crystal_hunt.yml`, `premiers_pas.yml`, `woodcutters_request.yml`
- `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java` (Fake mis à jour)
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/RewardText.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/RewardTextTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/QuestsCatalogTest.java`
- `QUEST_FORMAT.md`, `docs/RPGQUEST_BIBLE.md`, `docs-site/quests.html`
- `docs/control-panel/AGENT.md`, `docs/control-panel/ROADMAP.md`, `docs/current_state.md`
- `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Aucun schéma SQL touché (plugin ou `control-panel.db`).

## Configuration / données
- Nouveau champ **optionnel** `giver:` dans `plugins/RPGQuest/quests/*.yml`. Les YAML existants
  restent valides sans modification.
- Sur VeryGames DEV, `crystal_hunt.yml` a été mis à jour (voir Déploiement) pour démontrer le
  champ en conditions réelles.

## Payload `quest.list` — avant / après

**Avant** (par quête) :
```
{ id, title, category, repeatable, prerequisites,
  steps: [ { id, objectives: ["Tuer SPIDER (x5)"] } ],
  rewards: ["+100 XP", "commande console : customitem give %player% rpgquest:miner_picka…"] }
```

**Après** (additif — legacy conservé) :
```
{ id, title, category, repeatable, prerequisites,
  giverId: "guard",                                   // seulement si giver: dans le YAML
  steps: [ { id,
             objectives: ["Tuer SPIDER (x5)"],                       // legacy
             objectiveDetails: [ { kind:"KILL_ENTITY", target:"SPIDER", amount:5,
                                   raw:"Tuer SPIDER (x5)" } ] } ],
  rewards: ["+100 XP", "commande console : …<tronqué 60>"],          // legacy
  rewardDetails: [
     { kind:"EXPERIENCE", amount:100, target:null,  value:null,   command:null, raw:"+100 XP" },
     { kind:"COMMAND",    amount:0,   target:null,  value:null,
       command:"customitem give %player% rpgquest:miner_pickaxe 1", raw:"commande console : …" },
     { kind:"VARIABLE",   amount:0,   target:"CLAIM_TIER_1", value:"true", command:null, raw:"…" } ] }
```

`objectiveDetails.kind` ∈ `KILL_ENTITY` `COLLECT_ITEM` `CRAFT_ITEM` `BREAK_BLOCK` `PLACE_BLOCK`
`TALK_TO_NPC` `REACH_LOCATION`. `rewardDetails.kind` ∈ `EXPERIENCE` `ITEM` `VARIABLE` `COMMAND`.

## Tests automatiques

Exécutés sur la machine de build AWS (JVM de test bornée à 220 Mo, 1 worker) :

| Commande | Résultat |
|---|---|
| `./gradlew :test` (plugin) | **1086 tests, 0 échec, 0 erreur** (skips pré-existants : MariaDB/MockBukkit) |
| `./gradlew :control-panel:test` | **103 tests, 0 échec, 0 erreur** |
| `./gradlew build` | **BUILD SUCCESSFUL** |

Tests neufs / étendus :
- `web.agent.QuestListPayloadTest` (nouveau, 3) — **sérialisation JSON réelle** de `quest.list`
  (`com.lodygames.rpgquest.web.Json.write`) : objectifs kill/collect/craft/talk structurés,
  récompenses XP/item/variable/**commande longue non tronquée**, `giverId` absent vs présent,
  legacy conservé.
- `web.agent.AgentActionExecutorTest` (22) — Fake façade adaptée aux nouveaux records.
- `panel.web.ObjectiveTextTest` (nouveau, 8) — verbe FR par `kind`, nom FR par jeton, `(xN)`
  omis si N ≤ 1, PNJ/monde prettifiés, jeton technique conservé, repli `raw` sans exception.
- `panel.web.RewardTextTest` (11 ; +5) — `fromSummary` : XP / item FR / variable / commande
  **jamais tronquée** / `kind` inconnu → repli `parse(raw)`.
- `panel.web.QuestsCatalogTest` (3 ; +1) — payload structuré : « Donneur » rendu + id copiable,
  objectifs FR depuis la structure, jeton `SPIDER` conservé, commande longue visible, aucune
  régression #76/#77.
- `panel.web.AuthenticatedSmokeTest` (nouveau, 2) — **login owner de test → session →
  `/dashboard` `/players` `/quests` `/stories` réellement rendus** (HTTP+session, pas de
  navigateur) ; anonyme → 303 `/login`.

## Tests manuels à effectuer
- `PENDING MANUAL VALIDATION` : vue **navigateur authentifiée** du Control Panel AWS (login owner
  → `/quests`) — le mot de passe owner n'est pas connu de la session ; vérifier visuellement la
  ligne « Donnée par : Guard », les objectifs FR et la commande de récompense complète.
- `PENDING MANUAL VALIDATION` : scan complet de la console VeryGames DEV pour tout `ERROR` au
  démarrage (l'agent + `journalctl -u plugadmin` sont propres, RPGQuest chargé — vérifié).

## Résultat attendu
- `quest.list` renvoie objectifs + récompenses structurés + `giverId` (quand déclaré) ; commande
  de récompense jamais tronquée.
- Le panel `/quests` n'utilise plus `RewardText.parse` / `MinecraftNames.humanizeTokens` sur des
  chaînes libres quand la structure est là ; « Donneur » affiché ; aucune régression #76/#77.
- YAML de quêtes existants inchangés valides (`giver:` optionnel).

## Reset / retour à l'état initial
- Aucune donnée à réinitialiser. Retirer les champs `giver:` des 3 YAML bundlés annule la partie
  #75 côté dépôt. Sur VeryGames DEV, restaurer `crystal_hunt.yml` depuis le backup daté (voir
  Rollback) puis RCON `quest admin reload`.

## Déploiement VeryGames

### À transférer
- Le JAR RPGQuest (`rpgquest-0.1.0-SNAPSHOT.jar`).
- (Optionnel, pour #75 en conditions réelles) `crystal_hunt.yml` via la liste blanche `--also`.

### Ne PAS transférer/altérer
- `data.db`, `config.yml`, `messages.yml`, `spawn.yml`, mondes, `RPGQuest/Citizens/`, tout autre
  plugin, `plugadmin-agent.properties`.

### Redémarrage requis
- Oui pour le JAR (RCON `stop` → relance auto VeryGames).
- Pour un simple ajout de `giver:` à un YAML : RCON `quest admin reload` suffit (pas de restart).

### Migration automatique
- Aucune.

### Exécuté cette session (~11:00–11:04 UTC, `02642e9`)
1. **AWS** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
   `/opt/plugadmin/releases/20260908-105940`, `systemctl restart` → `active` (PID 124361,
   `event=panel_started port=8090`). JAR déployé sha256 `6493fc15…` == build de la branche ;
   classes `ObjectiveText`/`RewardText` présentes. `/health` local + public `ONLINE` (×3) ;
   `/dashboard` `/quests` `/players` `/stories` (anon) → 303 `/login` ; `/login` 200 ; autres
   vhosts (`dig.lodygames.com`, `lodylands.com`, `www.lodylands.com`) → 200 ; `nginx -t` OK ;
   nginx/secrets/TLS non touchés.
2. **VeryGames DEV** :
   - `scripts/deploy-verygames.sh -y` → `./gradlew test`+`build` OK, backup auto
     `rpgquest-20260908T110044Z-predeploy.jar` (sha256 `cd66574c…`), transfert FTP atomique du
     JAR **sha256 `4fbaa3456b00534c6309b4b1fcbf789fd9c2e0bdf43e6c51180817f6ddeb9c2f`**
     (1 261 384 o, distant == local).
   - `scripts/verygames-restart.sh` → `save-all` → `stop` RCON → OFFLINE → relance auto → **ONLINE**
     en < 1 min.
   - `scripts/deploy-verygames.sh -y --also src/main/resources/quests/crystal_hunt.yml:RPGQuest/quests/crystal_hunt.yml`
     → ancien `crystal_hunt.yml` sauvegardé sous
     `verygames-backups/extra-20260908T110340Z/…` (sha256 `06156d6d…`), nouveau transféré
     (sha256 `e8f2c8ce…`) ; puis RCON `quest admin reload` → « 10 quête(s) chargée(s), 0 erreur(s) ».

### Vérifications VeryGames (exécutées)
- `/plugins` (RCON) : **RPGQuest en vert** (+ Citizens, Multiverse-Core).
- `rpgquest version` (RCON) : `v0.1.0-SNAPSHOT`.
- Heartbeat agent reçu par PlugAdmin (`server_state=ONLINE`, `uptime_seconds` faible → restart
  pris en compte) ; **aucune** ligne `ERROR` dans `journalctl -u plugadmin`.
- **Validation `quest.list` réelle** : action `PENDING` injectée dans `agent_action`
  (`control-panel.db`), relevée + exécutée par l'agent DEV → **SUCCESS** « 10 quête(s) chargée(s). ».
  Le `details.quests` réellement reçu contient :
  - `steps[].objectiveDetails` : `{kind:"KILL_ENTITY",target:"SPIDER",amount:5}`,
    `{kind:"COLLECT_ITEM",target:"AMETHYST_SHARD",amount:2}`,
    `{kind:"CRAFT_ITEM",target:"DIAMOND_SWORD",amount:1}`,
    `{kind:"TALK_TO_NPC",target:"guard",amount:1}` ;
  - `rewardDetails` : `EXPERIENCE`(100), `COMMAND`
    (`command:"customitem give %player% rpgquest:miner_pickaxe 1"` — **complète**),
    `VARIABLE`(`CLAIM_TIER_1=true`) ;
  - `rpgquest:crystal_hunt.giverId == "guard"` ; `rpgquest:first_steps` **sans** clé `giverId` ;
  - champs legacy `objectives` / `rewards` toujours présents en parallèle.

## Rollback
- **VeryGames JAR** : `scripts/rollback-verygames.sh --latest` →
  `rpgquest-20260908T110044Z-predeploy.jar` (sha256 `cd66574c…`), puis `scripts/verygames-restart.sh`.
- **VeryGames `crystal_hunt.yml`** : `scripts/rollback-verygames.sh --also
  /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20260908T110340Z/RPGQuest/quests/crystal_hunt.yml:RPGQuest/quests/crystal_hunt.yml`
  puis RCON `quest admin reload` (voir `MANIFEST.txt` du dossier de backup).
- **AWS** : `scripts/plugadmin/rollback.sh app` → release `20260908-105940`.
- Aucune migration à défaire.

## Logs / diagnostic
- `journalctl -u plugadmin` : `event=panel_started port=8090` après swap, aucun `ERROR`.
- Heartbeat DEV post-restart : `server_state=ONLINE`, plugin `0.1.0-SNAPSHOT`.
- Actions de validation visibles dans `/agents` du panel (`created_by=claude-session-validation-*`).

## Documentation mise à jour
- `QUEST_FORMAT.md` — champ `giver:` (exemple + section dédiée + règle de validation).
- `docs/RPGQUEST_BIBLE.md` §3 — `giver` (informatif, exposé en `giverId`).
- `docs-site/quests.html` — bloc « Format » (`giver:`).
- `docs/control-panel/AGENT.md` — nouvelle sous-section « Payload `quest.list` », legacy déprécié.
- `docs/control-panel/ROADMAP.md` — Étape 3 « lecture structurée » : avancement partiel.
- `docs/current_state.md` — protocole `quest.list` structuré.
- `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-08 + section « Exécution réelle ».
- `docs/claude-reports/README.md` — ligne d'index.

## Limitations / travail restant
- `giverName` non rempli : le panel prettifie l'id (« guard » → « Guard »). Un vrai nom
  d'affichage FR de PNJ demanderait une source dédiée — hors scope #75 (pas d'i18n).
- Les chaînes legacy (`objectives:String[]`, `rewards:String[]`) restent dans le payload : à
  retirer dans un lot ultérieur, une fois **tous** les agents redéployés (dépréciation documentée).
- `quest.player.status` (`ObjectiveState.description`) reste une chaîne — hors périmètre #78 (qui
  vise `quest.list`).
- Table FR `MinecraftNames` toujours partielle (repli `prettifyId`) — i18n robuste = chantier séparé.
- Vue navigateur authentifiée du panel : `PENDING MANUAL VALIDATION` (mot de passe owner inconnu).

## Prochaine étape suggérée
- Faire confirmer la vue `/quests` authentifiée par l'owner ; si OK, fermer #75.
- Après redéploiement de tous les agents : supprimer les champs legacy de `quest.list` et le code
  de repli (`RewardText.parse` / `MinecraftNames.humanizeTokens` sur chaînes libres) — créer un
  ticket dédié.
- Étendre la même structuration à `quest.player.status`.
