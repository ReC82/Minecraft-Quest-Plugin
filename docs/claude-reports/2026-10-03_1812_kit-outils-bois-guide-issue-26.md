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
