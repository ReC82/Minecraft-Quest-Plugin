# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-06
* Heure : 18:11 (heure locale, CEST)
* Sujet : Cohérence dialogues/quêtes ↔ édition PlugAdmin — diagnostic, puis édition d'un choix porteur d'actions/conditions, sélecteur de couleur, et suppression de toute simplification silencieuse à l'enregistrement
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `5f71819` (poussé) + le commit de ce rapport
* Début de la tâche : 2026-10-06 17:05:00
* Fin de la tâche : 2026-10-06 18:11:01
* Durée totale : 01:06:01

## Demande

Vérifier la cohérence entre les dialogues/quêtes existants et leur édition dans
PlugAdmin, à partir d'un constat utilisateur sur le dialogue de **Jeff** :

- l'aperçu interprète correctement les couleurs ;
- les champs d'édition exposent encore les balises (`<gray>Compris.</gray>`) ;
- le premier choix « Je vais m'en charger » démarre « Nettoyage des alentours »
  sous condition `NOT_STARTED`, et **n'est pas modifiable** (le panel annonce
  une édition avancée « prévue ultérieurement ») ;
- le second choix, qui ferme le dialogue, est modifiable.

Établir d'abord le diagnostic (modèle canonique du plugin, fichiers existants,
catalogue source/runtime, modèles de lecture/écriture du panel ; même schéma
pour l'ancien et le nouveau contenu ; distinguer limite d'éditeur et divergence
de format), puis corriger : modifier le texte d'un choix sans perdre ses
actions/conditions, éditer de façon structurée le démarrage de quête et la
condition d'état, réutiliser le sélecteur de couleur existant avec compatibilité
des balises, préserver intégralement les propriétés avancées en signalant les
limites dans l'UI, et ne jamais simplifier silencieusement un contenu lors d'une
sauvegarde.

Contraintes explicites : sans sous-agents ni Gradle parallèle ; tester, commit,
push et déployer sur DEV ; **ne pas modifier le contenu réel de Jeff** ; aucun
ticket fermé ni test utilisateur coché.

## Analyse

### État du dépôt au démarrage (signalé, non modifié)

Le working tree était **déjà sale** avant toute action : `quests/crystal_hunt.yml`
modifié et quatre fichiers non suivis (`quests/jeff_skeleton.yml`,
`quests/lily_pumpkin.yml`, `quests/st0_meet_people.yml`,
`stories/lily_memories.yml`), tous appartenant à l'utilisateur `plugadmin` —
c'est-à-dire écrits par l'éditeur du Control Panel dans une session antérieure
(`contentRepoDir` pointe sur `src/main/resources` de ce dépôt). **Aucun de ces
fichiers n'a été modifié, staché ni committé.**

### Chaîne réelle de l'édition

Deux chemins d'écriture coexistent, et c'est la clé du diagnostic :

1. **Éditeur guidé `/dialogues`** (#82) — agit sur un dialogue **chargé par le
   serveur**. Le panel n'écrit rien : il enfile une action agent
   (`dialogue.node.update`, `dialogue.choice.*`) que le **plugin** exécute via
   `DialogueDefinitionEditor`, qui relit le fichier, applique la mutation sur le
   modèle métier, re-sérialise **tout** le dialogue avec
   `DialogueDefinitionWriter`, re-parse en mémoire, exige l'égalité sémantique,
   écrit atomiquement, recharge, et **restaure l'octet près** en cas d'échec.
   C'est par là que passe le dialogue de Jeff (il vit sur le serveur DEV, pas
   dans la source du dépôt).
2. **Éditeur source `/dialogues/new` et `/dialogues/edit/<id>`** (#145) — écrit
   directement dans `src/main/resources/dialogues/*.yml` via `ContentWorkspace`,
   avec le modèle `DialogueDraft` / `DialogueYaml` **du panel**.

### Diagnostic des trois symptômes

| Constat | Verdict |
|---|---|
| Aperçu coloré, champ d'édition en balises brutes | **Limite d'éditeur.** Le graphe rend le texte avec `MiniText.html` ; le formulaire utilisait un `<input type="text">` nu. Le sélecteur de couleur (#118) n'existait que sur `/dialogues/new` — et uniquement sous sa forme `<select>` : le CSS `.dlg-swatch` et le module JS `initColorPalette` du dépôt n'étaient **rendus par aucun code Java** (composant mort). |
| Premier choix non modifiable | **Limite d'éditeur, pas une divergence de format.** `DialogueDefinitionWriter` sérialise déjà fidèlement les 10 types d'actions et 8 types de conditions (+ négation). Le blocage venait de `updateChoice`, qui reconstruisait le choix avec `List.of(), List.of()` : un garde-fou `isSimple()` le **refusait** (`UNSAFE_CHOICE`) précisément pour ne pas détruire la condition et l'action — sans offrir d'issue. |
| Second choix (CLOSE) modifiable | Cohérent avec le même garde-fou. |

### Schéma, identifiants, relations — ancien vs nouveau contenu

**Aucune divergence de schéma.** Vérifié sur le contenu réel :

- identité : `id: rpgquest:<clé>` partout (`first_steps.yml` historique comme
  `jeff_skeleton.yml` écrit par le panel) ;
- donneur : `giver: <id PNJ nu>` (`guard`, `jeff`) ;
- relation dialogue ↔ PNJ : convention `rpgquest:<id du PNJ>`, le champ
  `dialogue:` d'une `NpcDefinition` ne servant qu'aux diagnostics ;
- relation dialogue → quête : action `START_QUEST` / `ADVANCE_QUEST` /
  `TURN_IN_QUEST` et condition `QUEST_STATE`, avec `quest: rpgquest:<clé>` ;
- couverture des types : les descripteurs du panel couvrent **tous** les membres
  de `ObjectiveType` (7) et de `RewardType` (4) — `COMMAND` comprise, avec son
  champ `command`. Rien n'est perdu faute de descripteur.

Cette identité est désormais **testée**, pas supposée : un même texte YAML
canonique est affirmé identique des deux côtés —
`DialogueYamlTest#richDialogueRoundTripsByteForByte` (panel) et
`DialogueDefinitionEditorTest#canonicalPanelFormatLoadsAndIsRewrittenIdentically`
(moteur, qui le charge puis le réécrit à l'octet près).

Deux écarts réels ont tout de même été trouvés, et traités plus bas :

- le modèle **du panel** (`DialogueDraft`) ne portait **pas** les conditions ni
  les actions d'un choix ;
- `QuestYaml.read` aplatissait un `title` / `description` **localisé** (table de
  traductions, acceptée par le moteur) en la chaîne `{default=…, fr=…}`.

Limite du moteur constatée au passage, **laissée telle quelle** (hors périmètre,
aucun contenu concerné) : un texte de **choix** localisé n'est pas chargeable —
`DialogueDefinitionParser` lit les choix via `getMapList`, où une table de
traductions n'est pas reconnue, et le fichier est rejeté au chargement. Le texte
d'un **nœud**, lui, est bien localisable, et sa préservation est testée.

### Défaut majeur découvert hors du périmètre annoncé

`/dialogues/edit/<id>` relisait le fichier pour préremplir, puis — à
l'enregistrement — **reconstruisait un brouillon neuf** à partir des trois
champs du formulaire (identité, locuteur, réplique de départ) et l'écrivait.
Rééditer un dialogue à plusieurs nœuds le remplaçait donc par un **squelette à
un nœud** : tous les autres nœuds, tous les choix, toutes les conditions et
toutes les actions étaient perdus, **sans aucun avertissement**. Le garde-fou
round-trip ne pouvait rien voir, le modèle du panel ne portant pas ces
propriétés. C'est exactement ce que la demande interdit (« ne jamais simplifier
silencieusement un contenu lors d'une sauvegarde »), et cela concerne les
dialogues de la source (`guard`, `guide`, `jo`, `libraire`, `merchant`).

## Travail effectué

### 1. Moteur — éditer un choix riche sans rien perdre (#82)

`DialogueDefinitionEditor.updateChoice` repart désormais du **modèle métier relu
du fichier** : les conditions et les actions du choix sont reconduites à
l'identique, dans leur ordre d'origine. Le garde-fou `UNSAFE_CHOICE` disparaît
pour la mise à jour (il n'a plus d'objet).

Deux propriétés deviennent éditables de façon structurée, par des paramètres
**optionnels** :

- `quest_action` ∈ `keep` / `none` / `start_quest` / `advance_quest` /
  `turn_in_quest` (+ `quest_id`) ;
- `quest_condition` ∈ `keep` / `none` / un `QuestState` (+ `condition_quest_id`,
  `condition_negate`).

`keep` est le **défaut** : un paramètre absent ne touche à rien, donc un client
antérieur conserve exactement le comportement précédent. En `SET`, la nouvelle
action (ou condition) prend la **place** de l'existante sans réordonner le reste
et les doublons éventuels sont retirés ; la bascule « ferme le dialogue »
n'ajoute ou ne retire que le `CloseAction`, sans réordonner quand l'état ne
change pas.

`deleteChoice` **reste refusé** sur un choix porteur d'effets de jeu : supprimer
effacerait aussi son action et sa condition, ce qui doit rester un geste
explicite. Le message dit comment procéder (les retirer d'abord).

### 2. Control Panel — formulaire complet et sélecteur de couleur (#82)

La note « actions / conditions avancées : édition prévue dans une phase
ultérieure » est remplacée par un vrai formulaire **pour chaque choix** :

- texte et cible comme avant ;
- deux `<select>` **pré-remplis sur l'état réel du choix** : action de quête
  (aucune / démarrer / faire avancer / rendre, + quête) et condition d'état de
  quête (+ case « inverser la condition ») ;
- la **liste explicite** des actions et conditions que la page ne sait pas
  éditer, annoncées « conservé à l'identique par l'enregistrement » — le
  formulaire ne porte **aucun champ** pour elles, il ne peut donc pas les
  écraser ;
- si un choix porte **deux** actions de quête (ou deux conditions
  `QUEST_STATE`), le formulaire bascule en `keep` et le dit, plutôt que de
  réduire deux effets à un seul ;
- le `<select>` de quête inclut toujours la quête actuellement référencée, même
  inconnue du dernier relevé serveur (marquée « inconnue du serveur »), pour
  qu'un enregistrement ne puisse pas remplacer une référence par une autre.

Les champs de texte (choix **et** nœud) montrent la **source MiniMessage
brute** — rien n'est jamais réécrit à l'insu de l'utilisateur — accompagnée d'un
**aperçu rendu** et de la **palette de couleurs**. La palette ne réécrit que la
balise de couleur **englobante** (compatible avec un texte déjà balisé comme
`<gray>Compris.</gray>`, dont elle présélectionne la pastille) ; sur un texte à
balises composites, elle se **désactive** et l'annonce. Implémenté dans le
module `initMiniMessageFields` de `panel.js` : aucun JS inline, CSP inchangée.

### 3. Aucune simplification silencieuse à l'enregistrement (#145, #46)

- `DialogueDraft` / `DialogueYaml` portent désormais les conditions et les
  actions d'un choix (listes **ordonnées** de couples clé → valeur) et les
  réémettent dans la forme exacte du moteur, règle de citation comprise (seuls
  `key` / `value` / `permission` / `command` sont entre guillemets).
- `/dialogues/save` en édition **repart du fichier réel** et n'applique que les
  champs du formulaire ; la page annonce combien de nœuds et de choix sont
  conservés.
- Un fichier dont la **relecture** signale quelque chose de non représentable
  (table de traductions, YAML exotique) n'est **plus jamais réécrit** :
  l'enregistrement est refusé avec la raison, dite **dès l'ouverture** de la
  page, pas seulement au moment de sauver.
- Même garde-fou pour les quêtes : `QuestYaml.read` signale un `title` /
  `description` localisé au lieu de l'aplatir, et `/quests/save` — dont le
  formulaire reconstruit toute la quête — refuse d'écrire sur un fichier mal
  relu.

## Fichiers créés

* `docs/claude-reports/2026-10-06_1811_edition-choix-dialogue-actions-conditions.md` (ce rapport)

## Fichiers modifiés

Plugin :
* `src/main/java/com/lodygames/rpgquest/dialogue/DialogueDefinitionEditor.java`
* `src/main/java/com/lodygames/rpgquest/dialogue/DialogueDefinitionWriter.java` (commentaire de contrat seulement)
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
* `src/test/java/com/lodygames/rpgquest/dialogue/DialogueDefinitionEditorTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java`

Control Panel :
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/DialogueDraft.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/DialogueYaml.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/QuestYaml.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ContentEditorPages.java`
* `control-panel/src/main/resources/assets/panel.js`
* `control-panel/src/main/resources/assets/plugadmin.css`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/DialogueYamlTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/DialogueSourceMergeTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/DialoguesCatalogTest.java`

Documentation :
* `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md`

**Non modifiés, laissés tels quels** : `src/main/resources/quests/crystal_hunt.yml`
et les quatre fichiers de contenu non suivis de l'utilisateur.

## Base de données / migrations

Aucune. Aucun schéma SQLite touché, aucune migration ajoutée.

## Configuration / données

Aucun changement de configuration. **Aucun fichier de contenu transféré au
serveur** (aucun `--also`) : le dialogue de Jeff et tous les autres contenus du
serveur DEV sont inchangés — ils n'ont servi qu'à l'analyse, conformément à la
demande.

Contrat d'API agent étendu de façon **rétrocompatible** : les nouveaux
paramètres de `dialogue.choice.update` sont optionnels et valent `keep`.

## Tests automatiques

Exécutés sur un **worktree Git propre** (`/srv/rpgquest/wt-verify`, HEAD détaché
sur `5f71819`) avec `RPGQUEST_TEST_MAX_HEAP=768m` :

* `./gradlew test` + `./gradlew build` → **BUILD SUCCESSFUL** (18 min 55 s)
* **1404 tests plugin, 0 échec, 34 ignorés**
* **395 tests panel, 0 échec, 1 ignoré**

Tests ajoutés ou réécrits :

* `DialogueDefinitionEditorTest` : édition du texte d'un choix riche sans toucher
  ses conditions/actions ; `SET` / `REMOVE` / `KEEP` de l'action de quête et de
  la condition d'état (y compris négation) ; refus d'un `SET` sans quête ;
  bascule « ferme le dialogue » conservant l'action ; conservation d'une action
  `GIVE_ITEM` et d'une condition `HAS_PERMISSION` non éditables ; suppression
  toujours refusée sur un choix riche ; survie d'un texte de nœud **localisé** à
  une édition ailleurs dans le fichier ; **format canonique identique entre
  moteur et panel**.
* `AgentActionExecutorTest` : paramètres structurés transportés (clé simple
  préfixée `rpgquest:`), `keep` par défaut quand ils sont absents, rejets
  (quête manquante, type d'action hors des trois, état inconnu).
* `DialoguesCatalogTest` : disparition du cul-de-sac, sélecteurs pré-remplis,
  champ MiniMessage + palette + aperçu, propriétés conservées annoncées,
  `keep` forcé sur un choix à deux actions de quête.
* `DialogueYamlTest` : round-trip fidèle d'un dialogue riche, conditions et
  actions relues entrée par entrée, texte localisé signalé et jamais aplati.
* `DialogueSourceMergeTest` : **aller-retour lecture / modification / sauvegarde**
  d'un dialogue riche — seul le texte demandé change, second nœud, choix,
  condition, action et embranchement intacts ; et refus d'écrasement d'un
  fichier non relu fidèlement.

**Échec local attendu et expliqué** : sur le checkout principal (sale),
`CrystalHuntIntegrationTest#fullDialogueQuestCraftingLoopGrantsTheFinalReward`
échoue (`expected: <COMPLETED> but was: <ACTIVE>`). Cause **vérifiée**, sans
rapport avec cette session : le test charge le `quests/crystal_hunt.yml`
embarqué, que l'utilisateur a modifié sans le committer (objectifs passés de
araignées/cristaux/fabrication à zombies/squelettes/araignées). Le même test
passe sur le worktree propre, au même commit.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — **TC-231** ajouté à `docs/MANUAL_TEST_PLAN.md` :

1. `/dialogues` → dialogue de Jeff → graphe → sous « Je vais m'en charger »,
   « Modifier ce choix » ouvre bien un formulaire.
2. Les sélecteurs affichent **Démarrer la quête** + « Nettoyage des alentours »
   et **Pas encore commencée** sur la même quête.
3. Modifier **uniquement** le texte → enregistrer → rafraîchir : le texte change,
   l'action et la condition sont toujours là, la flèche aussi.
4. Changer l'état de la condition → seule la condition change.
5. Palette : une pastille réécrit la balise englobante, l'aperçu change de
   couleur ; sur un texte à balises multiples la palette est grisée avec sa note.
6. Non-régression : `/dialogues/edit/<id>` sur un dialogue source à plusieurs
   nœuds → modifier la réplique de départ → le fichier garde tout le reste.

Ne pas utiliser le dialogue réel de Jeff comme bac à sable.

## Résultat attendu

Un choix de dialogue qui démarre une quête sous condition est éditable depuis le
Control Panel : son texte se corrige, son action de quête et sa condition d'état
se changent par sélecteurs, et tout ce que la page n'affiche pas est reconduit à
l'identique et annoncé comme tel. Les champs de texte restent la source
MiniMessage, doublée d'un aperçu coloré et d'une palette compatible avec les
balises existantes. Aucun enregistrement, par aucun des deux chemins d'édition,
ne peut plus réduire un contenu en silence.

## Reset / retour à l'état initial

Aucun reset nécessaire : aucune donnée joueur, aucun contenu serveur modifié.
Revenir en arrière = rollback du JAR et de la release du panel (ci-dessous).

## Déploiement VeryGames

Déployé le 2026-10-06 (~18:00-18:15 CEST), sur demande explicite.

### À transférer

* **Plugin, VeryGames DEV** : `rpgquest-0.1.0-SNAPSHOT.jar` — 1 622 803 o,
  SHA-256 `1c42ac9dccfccaf4dfc0530271f5b96371763adeac41c3b3981b49cc5e93f961`.
  Transféré ; taille en ligne vérifiée identique au local.
* **Control Panel, AWS** : `scripts/plugadmin/deploy.sh` → release
  `/opt/plugadmin/releases/20261006-180938` sauvegardée, service redémarré,
  `/health` → `{"panel":"ONLINE","disabled":false,…}`.

Build et transfert faits depuis le **worktree propre** et non depuis le checkout
principal (qui portait le contenu non committé de l'utilisateur) : le JAR livré
correspond exactement au commit poussé.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les
mondes, et **tous les fichiers de contenu** (`dialogues/`, `quests/`,
`stories/`) — en particulier `dialogues/jeff.yml`. Aucun `--also` n'a été utilisé.

### Redémarrage requis

Oui, effectué : `scripts/verygames-restart.sh --timeout 240` — **0 joueur
connecté**, `save-all`, OFFLINE confirmé puis **ONLINE**. Vérifications :
`/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 5 plugins verts
(Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit).

### Migration automatique

Aucune.

## Rollback

* Plugin : `scripts/rollback-verygames.sh --latest` — restaure
  `rpgquest-20261006T160901Z-predeploy.jar` (1 850 699 o, SHA-256
  `ba6c4e3c714765414827b59bbc5828c2e33540c0394f0b45a3b5bee4a13d4a75`), puis
  redémarrer.
* Control Panel : `scripts/plugadmin/rollback.sh app` — restaure
  `/opt/plugadmin/releases/20261006-180938`.

**Constat brut** : le JAR qui était en ligne avant ce déploiement pèse
1 850 699 o, nettement plus que les JAR des entrées précédentes du changelog
(~1,6 Mo). Il n'a donc pas été produit par ce dépôt dans son état committé. Non
expliqué ici, signalé pour que le rollback soit fait en connaissance de cause.

## Logs / diagnostic

Aucune instrumentation ajoutée. Les mutations de dialogue restent auditées par le
Control Panel (action agent, `DIALOGUE_WRITE`), et chaque écriture du moteur
reste précédée d'un re-parse et suivie d'un rechargement avec restauration en cas
d'échec.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — périmètre réel de l'éditeur guidé (paramètres
  structurés, défaut `keep`, propriétés conservées, suppression toujours gardée),
  champ MiniMessage et palette, réédition d'un dialogue source sans perte.
* `docs/current_state.md` — deux entrées ajoutées, et correction de la
  description devenue fausse des mutations « choix simple ».
* `docs/deployment/SERVER_CHANGELOG.md` — entrée du 2026-10-06 + section
  « Déploiement / Exécution réelle » avec les SHA, backups et vérifications.
* `docs/MANUAL_TEST_PLAN.md` — TC-231 détaillé + ligne dans la table de recette.

## Limitations / travail restant

* **Validation humaine en navigateur et en jeu** non faite (TC-231). Rien n'est
  déclaré validé sur la seule base des tests automatisés.
* Hors périmètre, toujours non couvert par l'éditeur guidé : renommage,
  déplacement et suppression de **nœud**, réordonnancement des choix, édition
  des **autres** types d'actions et de conditions (`GIVE_ITEM`, `TAKE_ITEM`,
  `SET_VARIABLE`, `RUN_SAFE_COMMAND`, `OPEN_DIALOGUE`, `OPEN_MERCHANT`,
  `GIVE_STARTER_KIT`, `HAS_ITEM`, `HAS_PERMISSION`, `VARIABLE_EQUALS`,
  `NO_MAIN_CLAIM`, `HAS_MAIN_CLAIM`, `LACKS_CUSTOM_ITEM`). Elles sont
  **préservées** et **annoncées**, jamais éditables depuis la page.
* Un choix sans `next` et sans action terminale ferme le dialogue
  **implicitement** ; l'enregistrer depuis le formulaire y ajoute un `CLOSE`
  explicite. Comportement en jeu identique, fichier légèrement plus verbeux.
* Un texte de **choix** localisé reste non chargeable par le moteur (limite de
  `DialogueDefinitionParser` via `getMapList`), constatée et documentée, non
  corrigée — aucun contenu actuel n'est concerné.
* L'en-tête de provenance écrit par le panel et par l'agent
  (« squelette éditable… l'édition fine passera par l'éditeur dédié ») est
  devenu inexact, mais il est **identique octet pour octet des deux côtés** et
  cette identité est une propriété assumée : non touché pour ne pas casser cette
  garantie dans un changement déjà large.
* Le working tree de l'utilisateur reste sale (contenu en cours d'édition). Tant
  qu'il l'est, `./gradlew test` échoue localement sur
  `CrystalHuntIntegrationTest` et `scripts/deploy-verygames.sh` refuse de partir
  sans `--allow-dirty`.

## Prochaine étape suggérée

Valider TC-231 en navigateur sur `/dialogues` avec le dialogue de Jeff, puis
décider du sort des cinq fichiers de contenu non committés (les committer s'ils
sont voulus — ce qui demandera d'adapter `CrystalHuntIntegrationTest` au nouveau
`crystal_hunt.yml` — ou les écarter).
