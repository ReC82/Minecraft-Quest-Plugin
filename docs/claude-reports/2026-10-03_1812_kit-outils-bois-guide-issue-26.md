# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-03
* Heure : 18:12
* Sujet : Issue #26 (partie « kit de départ » uniquement) — kit d'outils en bois demandé explicitement au Guide, remise tout ou rien, droit renouvelé à chaque mort
* Statut : DONE (partie A du ticket uniquement — partie B hors périmètre, voir « Limitations »)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `c3c88e7` (avant le(s) commit(s) de cette tâche — voir `git log` pour le(s) commit(s) réel(s) créé(s) après ce rapport)
* Début de la tâche : non mesurable (horodatage de début non capturé avant la première action)
* Fin de la tâche : 2026-10-03 18:12:57
* Durée totale : non mesurable

## Demande

Reprendre **uniquement** la partie « kit de départ » du ticket GitHub #26
(`ReC82/Minecraft-Quest-Plugin`), avec un cahier des charges détaillé reçu directement dans la
conversation, qui **remplace** un prompt précédent (règle de remise unique à vie) :

- Option de dialogue explicite « Demander mon kit de départ » chez le Guide.
- Aucune remise automatique (ni clic simple sur le PNJ, ni connexion, ni réapparition).
- Première demande autorisée ; après une remise réussie, une nouvelle **mort** rouvre le droit.
- Une remise réussie par période entre deux morts, sans limite totale de récupérations.
- Une mort avant toute première remise ne supprime pas le droit initial.
- Aucun délai/paiement/prérequis supplémentaire.
- Contenu exact : un exemplaire de `WOODEN_SWORD`/`WOODEN_PICKAXE`/`WOODEN_SHOVEL`/`WOODEN_AXE`,
  rien d'autre.
- Remise **tout ou rien** : vérifier 4 emplacements libres du stockage normal (hors armure/main
  secondaire) avant toute remise ; refus → aucun objet distribué, rien jeté/remplacé, droit
  conservé, message précis imposé ; anti double-clic.
- Réutiliser les mécanismes existants de dialogues/inventaire/persistance ; définition du kit
  configurable (pas codée en dur) ; persistance après reconnexion/redémarrage ; `resetnew`
  restaure le droit initial ; même comportement OP/non-OP.
- Périmètre strict : uniquement le kit (la partie B du ticket — avertissement avant le Wild —
  reste au backlog, ne pas fermer #26) ; préserver les fichiers locaux non suivis
  (`lily_pumpkin.yml`, `st0_meet_people.yml`, `lily_memories.yml`) ; ne pas modifier Lily.
- Tests + build requis par le dépôt, rapport Markdown, procédure de validation en jeu courte.
- Aucun merge/déploiement/redémarrage sans demande explicite.

## Analyse

Audit de l'existant avant codage :

- `dialogues/guide.yml` existait déjà avec un choix similaire (« Récupérer une rune de rappel »,
  action `RUN_SAFE_COMMAND` + condition `LACKS_CUSTOM_ITEM`) — mais ce patron (commande console +
  « en manque-t-il un ») ne convient pas ici : le kit n'est pas un objet personnalisé identifiable
  par PDC, la remise doit être **atomique sur 4 objets** avec vérification de place **avant**
  toute écriture, et la notion de droit est **temporelle** (entre deux morts), pas « en possède-t-il
  déjà un ».
- `player.StarterKitListener` existait déjà sous le nom « kit de départ » — mais c'est en réalité
  la **Rune de rappel**, remise automatiquement une seule fois à la connexion. Mécanisme et nom
  rentraient en collision avec la nouvelle fonctionnalité : nouvelle classe
  `player.StarterToolKitService`, bien distincte, créée plutôt que de réutiliser/renommer
  l'existant (renommer aurait été un changement hors périmètre).
- `dialogue.model.ActionType`/`DialogueAction` (interface scellée, 10 types) : architecture déjà
  en place pour ajouter un nouveau type d'action proprement (modèle immuable, parseur, moteur
  d'exécution `DialogueSessionEngine`). Repérage de **tous** les points qui font un `switch`
  exhaustif sur ce type scellé pour n'en oublier aucun (sealed interface → échec de compilation
  si un cas manque, donc risque limité, mais vérifié explicitement) :
  `DialogueDefinitionParser`, `DialogueDefinitionWriter`, `content.pack.ContentPackMapper`,
  `web.agent.BukkitAgentActions`. Le module `control-panel` (éditeur guidé de dialogues) n'a
  **aucun** switch exhaustif sur ce type (il ne gère que le squelette de création) : aucun
  changement nécessaire côté Control Panel.
- `database.PlayerVariableRepository` (table `player_variables`, clé/valeur par joueur) :
  mécanisme déjà utilisé pour des marqueurs similaires (`RUNE_RAPPEL_GRANTED`, `CLAIM_TIER_1`,
  quête suivie). Réutilisable **tel quel** pour le droit au kit — **zéro migration de schéma**.
  `/rpgadmin player resetnew` (`player.PlayerResetService`) efface déjà **toutes** les variables
  d'un joueur : le droit au kit est donc automatiquement restauré par `resetnew`, sans code dédié.
- Aucun mécanisme existant de vérification « espace libre avant remise multi-objets » (audité :
  `DialogueSessionEngine.giveItem`, récompenses de quête, `/customitem give` utilisent tous
  `Inventory#addItem` + dépôt au sol du surplus — jamais un refus atomique). Logique écrite en
  propre pour ce besoin précis, sur `PlayerInventory#getStorageContents()` (36 cases, hors armure
  et main secondaire par construction de cette API Paper).
- `feature/23-mod-prototype` (mention temporaire de `CLAUDE.md` comme « branche d'intégration »)
  est en réalité déjà un **ancêtre** de `main` (0 commit d'avance, 37 de retard) : intégrée depuis
  longtemps. Conformément à la règle du dépôt (code/Git prime sur la documentation), j'ai donc
  traité `main` comme référence normale pour une nouvelle branche de tâche gameplay — mais comme
  cette tâche est restée sur la branche courante (voir ci-dessous), la réconciliation de cette
  mention obsolète dans `CLAUDE.md` n'a pas été faite ici (hors périmètre de cette tâche, tâche à
  part entière selon ses propres règles).

## Travail effectué

**Dialogue** (`dialogues/guide.yml`) : ajout du choix « Demander mon kit de départ » dans le nœud
`greeting`, toujours affiché (aucune condition), actions `GIVE_STARTER_KIT` + `CLOSE`.

**Modèle de dialogue** (nouveau type d'action, sans paramètre — le contenu du kit vit en
config) :
- `dialogue.model.GiveStarterKitAction` (nouveau `record`, implémente `DialogueAction`).
- `ActionType.GIVE_STARTER_KIT` (nouvelle constante).
- `DialogueAction` (interface scellée) : ajout au `permits`.
- `DialogueDefinitionParser` : nouveau `case` de parsing.
- `DialogueDefinitionWriter` : nouveau `case` de rendu (round-trip éditeur guidé).
- `content.pack.ContentPackMapper` + `DialoguePackEntry.Action.giveStarterKit()` : export du
  content pack (#108).
- `web.agent.BukkitAgentActions` : résumé affiché par le catalogue de dialogues du Control Panel
  (lecture seule — **aucune action agent nouvelle**, **aucun changement fonctionnel Control
  Panel**).
- `dialogue.session.DialogueSessionEngine` : nouveau paramètre constructeur
  `StarterToolKitService`, nouveau `case` qui délègue à `requestKit(player)`.

**Service métier** (nouveau) : `player.StarterToolKitService` (implémente `Listener` pour
`PlayerDeathEvent`) :
- `requestKit(Player)` : garde anti double-clic en mémoire (`Set<UUID>`), lecture asynchrone de
  la variable `STARTER_TOOL_KIT_AVAILABLE` (`PlayerVariableRepository`), puis traitement sur le
  thread principal.
- Absence de ligne ou valeur ≠ `"false"` → droit disponible (un nouveau joueur l'a donc dès le
  début, sans écriture nécessaire).
- Droit indisponible → message « déjà reçu », aucune remise.
- Droit disponible : calcul des emplacements libres de `PlayerInventory#getStorageContents()`
  (hors armure/main secondaire) ; si strictement moins que le nombre d'objets du kit → message
  imposé par le ticket, **aucune** écriture d'inventaire ni de variable (droit conservé) ; sinon,
  remise atomique (un `ItemStack` par emplacement libre identifié **avant** toute écriture,
  jamais `addItem` qui pourrait fusionner/déborder de façon moins prévisible), message de
  réussite, puis écriture asynchrone de la variable à `"false"`.
- `onDeath(PlayerDeathEvent)` : réécrit la variable à `"true"` (idempotent — une mort avant toute
  première remise ne change rien puisque l'absence de ligne équivaut déjà à « disponible »).

**Configuration** : nouvelle section `config.yml` → `starter-tool-kit` (`enabled`, `items` —
liste de matériaux, défaut les 4 outils en bois) ; `config.StarterToolKitConfig` (record) ;
`ConfigValidator`/`PluginConfig` mis à jour (validation : liste non vide, matériaux reconnus).
Aucune migration de schéma nécessaire (`SchemaMigrator` non touché) ; `ConfigFileCompleter`
ajoutera automatiquement la section à un `config.yml` déployé existant, sans toucher aux clés déjà
présentes.

**Câblage** (`bootstrap.RPGQuestBootstrap`) : construction de `StarterToolKitService`, écouteur
`PlayerDeathEvent` enregistré via `PlayerListenerService`, injecté dans `DialogueSessionEngine`.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/config/StarterToolKitConfig.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/model/GiveStarterKitAction.java`
- `src/main/java/com/lodygames/rpgquest/player/StarterToolKitService.java`
- `src/test/java/com/lodygames/rpgquest/player/StarterToolKitServiceTest.java`
- `docs/claude-reports/2026-10-03_1812_kit-outils-bois-guide-issue-26.md` (ce rapport)

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/main/java/com/lodygames/rpgquest/config/ConfigValidator.java`
- `src/main/java/com/lodygames/rpgquest/config/PluginConfig.java`
- `src/main/java/com/lodygames/rpgquest/content/pack/ContentPackMapper.java`
- `src/main/java/com/lodygames/rpgquest/content/pack/DialoguePackEntry.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/DialogueDefinitionParser.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/DialogueDefinitionWriter.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/model/ActionType.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/model/DialogueAction.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/session/DialogueSessionEngine.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
- `src/main/resources/config.yml`
- `src/main/resources/dialogues/guide.yml`
- `src/test/java/com/lodygames/rpgquest/config/ConfigValidatorTest.java`
- `src/test/java/com/lodygames/rpgquest/content/pack/ContentPackMapperTest.java`
- `src/test/java/com/lodygames/rpgquest/dialogue/DialogueDefinitionParserTest.java`
- `src/test/java/com/lodygames/rpgquest/dialogue/session/DialogueSessionEngineTest.java`
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs-site/dialogues.html`,
  `docs/MANUAL_TEST_PLAN.md`, `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`,
  `docs/claude-reports/README.md` (index)

**Fichiers locaux non suivis préservés sans modification** (comme demandé) :
`src/main/resources/quests/lily_pumpkin.yml`, `src/main/resources/quests/st0_meet_people.yml`,
`src/main/resources/stories/lily_memories.yml`. Lily n'a été modifiée nulle part.

## Base de données / migrations

**Aucune.** Le droit au kit est stocké dans la table `player_variables` déjà existante
(clé `STARTER_TOOL_KIT_AVAILABLE`), via `PlayerVariableRepository` déjà existant. `SchemaMigrator`
n'a pas été touché.

## Configuration / données

Nouvelle section `config.yml` :

```yaml
starter-tool-kit:
  enabled: true
  items:
    - WOODEN_SWORD
    - WOODEN_PICKAXE
    - WOODEN_SHOVEL
    - WOODEN_AXE
```

Un `config.yml` déjà déployé en recevra automatiquement une copie au prochain démarrage
(`ConfigFileCompleter`, backup `.bak` automatique) — aucune action manuelle requise.

## Tests automatiques

- **Nouveau** `StarterToolKitServiceTest` (12 cas, `MockBukkit` + vraie base SQLite temporaire) :
  première demande → contenu exact (un exemplaire de chaque outil, rien d'autre) ; refus avec 0
  à 3 emplacements libres → aucun objet distribué, droit conservé, message imposé ; nouvel essai
  après libération d'un emplacement → réussite ; nouvelle demande sans mort → refus « déjà
  reçu » ; mort après succès → droit rouvert **sans** remise automatique, nouvelle demande →
  réussite (2 épées en tout, jamais plus) ; 3 cycles mort/remise → 3 haches, jamais plus ; mort
  **avant** toute première remise → droit initial conservé, demande suivante réussit ; clic
  rapide (deux appels immédiats) → une seule remise ; suppression de toutes les variables
  (mécanisme de `resetnew`) → droit restauré ; kit désactivé en config → aucune remise, aucun
  message.
- `DialogueDefinitionParserTest` (+1 : parsing `GIVE_STARTER_KIT`).
- `ContentPackMapperTest` (+ assertions sur le nouveau type d'action dans le mapping export).
- `ConfigValidatorTest` (+4 : défauts, config personnalisée, liste vide rejetée, matériau
  inconnu rejeté).
- `DialogueSessionEngineTest` (+1, bout en bout : le choix de dialogue délègue bien au service et
  remet les 4 outils configurés).
- `BundledDialoguesValidityTest` (déjà existant) : `guide.yml` modifié reste valide (vérifié via
  l'exécution complète de la suite).

**Résultats** : `./gradlew :test` (module racine, ciblé puis complet) et `./gradlew build`
(3 modules : racine + `control-panel` + `web-api`) **BUILD SUCCESSFUL**, aucune régression,
aucun test ignoré supplémentaire par rapport à l'état précédent (`RPGQUEST_TEST_MAX_HEAP=768m`
utilisé sur cette machine à RAM contrainte, voir `.ai/` pour ce réglage).

**Note de méthode** : la première version du test échouait de façon non évidente
(`SQLITE_CONSTRAINT_FOREIGNKEY`, puis des courses avec les écouteurs de connexion du **vrai**
plugin démarré par `MockBukkit.load(RPGQuestPlugin.class)`, notamment `StarterKitListener`/Rune
de rappel qui s'exécute aussi à la connexion du joueur de test). Corrigé par
`PlayerProfileRepository#findOrCreate` avant toute variable, et par un inventaire explicitement
vidé après un court délai de stabilisation avant chaque test d'espace libre.

## Tests manuels à effectuer

Voir **TC-220** dans `docs/MANUAL_TEST_PLAN.md` (procédure complète). Résumé :

1. Parler au Guide sans choisir l'option → rien reçu.
2. Choisir « Demander mon kit de départ », inventaire vide → épée/pioche/pelle/hache en bois
   (×1 chacun), rien d'autre.
3. Redemander aussitôt (sans mourir) → « déjà reçu », rien de plus.
4. Remplir l'inventaire pour ne laisser que 0 à 3 emplacements → refus, rien distribué/jeté.
5. Libérer 4 emplacements, redemander → réussite.
6. Mourir, redemander → réussite (nouveau cycle, sans redistribution automatique à la mort
   elle-même) ; répéter 2-3 fois.
7. (si compte neuf disponible) Mourir avant toute première demande → droit initial conservé.
8. Double-clic rapide sur le choix → une seule remise.
9. Déconnexion/reconnexion (ou redémarrage) après une remise → toujours « déjà reçu » jusqu'à la
   prochaine mort.
10. `/rpgadmin player resetnew <joueur>` (non-OP et OP) → droit initial restauré.

**PENDING MANUAL VALIDATION** — aucun test manuel n'a été effectué (ni prétendu) dans cette
session.

## Résultat attendu

Le joueur peut demander son kit d'outils en bois au Guide à tout moment où il y a droit ; jamais
de remise automatique ; le droit se renouvelle à chaque mort, sans limite de cycles ; une remise
est toujours complète (4 objets) ou nulle (0 objet), jamais partielle ; le droit survit à une
reconnexion/un redémarrage et peut être réinitialisé par `/rpgadmin player resetnew`.

## Reset / retour à l'état initial

`/rpgadmin player resetnew <joueur>` restaure le droit initial (variable
`STARTER_TOOL_KIT_AVAILABLE` effacée comme toute autre variable joueur — déjà couvert par le code
existant, aucun ajout nécessaire). Désactivation complète de la fonctionnalité :
`starter-tool-kit.enabled: false` + `/rpgquest reload` (ou redémarrage) — n'efface aucune donnée
déjà écrite.

## Déploiement VeryGames

Voir l'entrée correspondante dans `docs/deployment/SERVER_CHANGELOG.md`
(« 2026-10-03 - Kit d'outils en bois demandé au Guide (issue #26, partie A) ») pour le détail
complet. Résumé :

### À transférer

Le JAR RPGQuest recompilé uniquement.

### Ne PAS transférer/altérer

`config.yml` existant sur le serveur (la section `starter-tool-kit` est ajoutée automatiquement
par le plugin au démarrage, avec sauvegarde `.bak`) ; `data.db` (aucune migration) ; les 3
fichiers locaux non suivis (`lily_pumpkin.yml`, `st0_meet_people.yml`, `lily_memories.yml`,
absents du dépôt Git et donc non concernés par un déploiement JAR de toute façon).

### Redémarrage requis

Oui, redémarrage complet (le dialogue `guide.yml` et `config.yml` ne sont relus qu'au démarrage,
pas de rechargement à chaud du contenu de dialogue).

### Migration automatique

Aucune migration de base de données. Complétion automatique de `config.yml` (ajout de la
nouvelle section si absente) au démarrage.

## Rollback

Remettre l'ancien JAR. Le `.bak` de `config.yml` créé par la complétion automatique n'a pas
besoin d'être restauré : un ancien JAR ignore simplement la section `starter-tool-kit` qu'il ne
connaît pas.

## Logs / diagnostic

Aucun log applicatif dédié ajouté au-delà des erreurs déjà loggées par
`plugin.getSLF4JLogger().error(...)` en cas d'échec de lecture/écriture de la variable
(cohérent avec le reste du dépôt, ex. `StarterKitListener`). Aucune commande de diagnostic admin
n'était demandée par le ticket ; aucune n'a été ajoutée.

## Documentation mise à jour

- `docs/RPGQUEST_BIBLE.md` (§4 Dialogues : nouvelle action `GIVE_STARTER_KIT` + sous-section
  complète « Kit d'outils en bois »).
- `docs/current_state.md` (section « Boucle joueur Hub ↔ Wild »).
- `docs-site/dialogues.html` (table des actions disponibles).
- `docs/MANUAL_TEST_PLAN.md` (nouvelle section 21, TC-220, + ligne dans la table de recette).
- `docs/deployment/SERVER_CHANGELOG.md` (nouvelle entrée datée, sans section « Exécution
  réelle » — aucun déploiement effectué).
- `.ai/ROADMAP.md` (nouvelle entrée de journal de session).
- `docs/claude-reports/README.md` (nouvelle ligne d'index chronologique).

## Limitations / travail restant

- **Partie B du ticket #26** (avertissement non bloquant avant l'entrée dans le Wild, cohérent
  avec #24) **non traitée** — explicitement hors périmètre de cette tâche. **#26 reste ouvert.**
- Aucun test manuel en jeu effectué (voir TC-220, `PENDING MANUAL VALIDATION`).
- Aucun merge, déploiement ou redémarrage effectué, conformément à la consigne.

## Prochaine étape suggérée

Partie B de #26 (avertissement de préparation avant le Wild), ou validation manuelle en jeu de
cette partie A avant de clore ce volet du ticket.

---

## Addendum — push + déploiement VeryGames DEV (autorisation explicite ultérieure)

* Horodatage de cet addendum : 2026-10-03, ~18:12 à 19:02 (même session, suite directe de ce qui
  précède, après une instruction explicite de l'utilisateur autorisant le push et le déploiement).
* Statut de cet addendum : DONE (déploiement effectué et vérifié) — **#26 reste ouvert**, validation
  en jeu toujours à faire par l'utilisateur.

Instruction reçue : pousser les commits `191afe9`/`2a9972a`, déployer le kit sur **VeryGames DEV**
uniquement (aucun merge, aucune intervention PROD), en préservant les 3 fichiers locaux non
suivis, les données joueurs/mondes/PNJ Citizens, et en vérifiant le résultat — sans fermer #26.
Egalement demandé : corriger l'ordre du test manuel TC-220 (mourir avant de tester le refus
0-3 places, pour que le droit soit bien disponible à ce moment-là).

### Ce qui a été fait

1. **Vérification Git préalable** : branche `feat/control-panel-admin-tools`, en avance de 2
   commits sur `origin`, aucun autre changement non commité que les 3 fichiers Lily déjà connus.
2. **Push** : `git push origin feat/control-panel-admin-tools` → `c3c88e7..2a9972a`, réussi.
3. **Correction documentaire demandée** : `docs/MANUAL_TEST_PLAN.md` TC-220 — insertion d'une
   étape « mourir » entre « redemander sans mourir → déjà reçu » et « tester le refus 0-3 places »
   (renumérotation 1→11, note explicative ajoutée).
4. **Déploiement VeryGames DEV** (`scripts/deploy-verygames.sh -y --allow-dirty --also
   src/main/resources/dialogues/guide.yml:RPGQuest/dialogues/guide.yml`) :
   - 1er essai : échec `./gradlew test` (OOM Java heap, machine AWS contrainte en RAM — aucun
     rapport avec le code). Relancé avec `RPGQUEST_TEST_MAX_HEAP=768m` → `./gradlew test`+`build`
     **BUILD SUCCESSFUL** (9m46s).
   - JAR transféré atomiquement (sha256 `d5a8d7438e1b72ada010cfad7318197601b5b156aaacf8c710f2fc59ed2cec69`,
     1 528 065 o), ancien JAR sauvegardé (sha256 `27f427409a6d6879d4230f9efb0afc4f9b893eb6a829cd187fa048bc2ead1668`).
   - `dialogues/guide.yml` transféré (sha256 `4b86a69d7337806c07bc15a0e3f806f757712e0be52e362fa5b82749114df1b1`,
     6 571 o — **identique octet pour octet** au fichier local), ancien sauvegardé (sha256
     `6459eb899afcf334d14aedf7faaee77f28cfdc3a6bc9342054d4dd6610b7a03e`).
   - `config.yml` **non transféré** (comportement voulu — complété automatiquement par le plugin
     au démarrage, sans écraser la configuration serveur existante).
5. **Incident réseau transitoire** : le premier essai de `scripts/verygames-restart.sh` a échoué
   (`No route to host` vers `51.68.57.28`, confirmé au niveau IP par `ping` — pas seulement un port
   fermé). Diagnostic : routage local AWS normal (`ip route`/`iptables` sains), FTP (hôte différent)
   et heartbeat sortant du plugin vers PlugAdmin toujours fonctionnels pendant l'incident → le
   problème venait de VeryGames, pas de cette machine. **Cause confirmée par l'utilisateur** : le
   panel VeryGames avait changé l'hôte **et** les ports (`51.68.57.28:7469`/`:28257` →
   `54.37.115.223:5918`/`:22956`). Fichier de configuration local hors dépôt
   (`~/.config/rpgquest/verygames.env`) mis à jour avec les nouvelles valeurs ; `docs/deployment/VERYGAMES.md`
   mis à jour (valeurs actuelles + avertissement que VeryGames peut les rechanger). Aucun secret
   affiché dans les sorties de commande visibles de cette session.
6. **Redémarrage réussi** : `scripts/verygames-restart.sh` → `save-all` → `stop` RCON → OFFLINE
   confirmé → relance automatique VeryGames → **ONLINE**. Le joueur connecté (`LoDyMcFly`,
   l'utilisateur en test) a été déconnecté par le redémarrage, comme attendu et déjà autorisé.
7. **Vérifications post-redémarrage** (détail complet dans `docs/deployment/SERVER_CHANGELOG.md`,
   section « Exécution réelle » de l'entrée #26) :
   - `rpgquest version` (RCON) → `v0.1.0-SNAPSHOT`.
   - `plugins` (RCON) → Citizens, Multiverse-Core, RPGQuest, WorldEdit tous chargés.
   - Heartbeat agent PlugAdmin : `uptime_seconds` retombé à **5** (preuve d'un redémarrage réel),
     `world_hub`/`claims`/`wild` tous `loaded=true`.
   - `data.db`, `Citizens/saves.yml`, les autres mondes : jamais touchés (liste blanche du script
     de déploiement, aucune exception demandée).

### Limite assumée de cette vérification

Aucun accès direct aux logs serveur Minecraft (`logs/latest.log`) n'est possible depuis le compte
FTP utilisé (restreint à `plugins/`) ni via RCON : l'absence d'erreur **spécifique au chargement
de `guide.yml`** n'a donc pas pu être lue en direct dans la console. Confiance basée sur le fichier
déployé identique octet pour octet au fichier local déjà validé par `BundledDialoguesValidityTest`
(parseur déterministe, sans dépendance Bukkit), combinée aux 4 plugins chargés sans erreur visible.

### Ce qui reste à faire

- **Validation manuelle en jeu** du nouveau choix « Demander mon kit de départ » chez le Guide
  (TC-220) — **non effectuée dans cette session**, à faire par l'utilisateur. **#26 n'est pas
  fermé.**
- Aucun merge vers `main`, aucune intervention sur un environnement de production.

### Fichiers modifiés par cet addendum

`docs/MANUAL_TEST_PLAN.md` (ordre TC-220), `docs/deployment/VERYGAMES.md` (host/ports RCON à
jour), `docs/deployment/SERVER_CHANGELOG.md` (section « Exécution réelle » de l'entrée #26), ce
rapport (addendum). Fichier hors dépôt modifié : `~/.config/rpgquest/verygames.env` (host/port
RCON, aucun secret changé).
