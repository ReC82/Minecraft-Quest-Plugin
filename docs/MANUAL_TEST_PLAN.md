# Plan de recette manuelle — RPGQuest

Ce document couvre uniquement les fonctionnalités **réellement implémentées**
dans le code de cette branche (étapes 1 à 23, toutes `DONE` — voir
`.ai/ROADMAP.md`). Aucune commande ni aucun comportement n'est inventé :
chaque cas de test référence la classe (commande, listener, service) qui
l'implémente réellement. Quand un comportement n'est pas testable
manuellement de façon fiable (concurrence réseau réelle, TLS, etc.), le test
automatisé qui le couvre est indiqué à la place.

## Environnement de recette

-   **Version Java requise :** Java 21 (`java.toolchain.languageVersion` =
    21 dans `build.gradle.kts`, `options.release.set(21)`).
-   **Version Paper/Minecraft ciblée :** Paper `1.21.11`
    (`paper-api:1.21.11-R0.1-SNAPSHOT`, `runServer { minecraftVersion("1.21.11") }`).
-   **Branche Git testée :** `feature/23-mod-prototype`
-   **Commit Git testé :** `2a35c0d4ca9155f61ea4ef1cdfa9ef251f76f01f`
    (`refactor(namespace): replace personal Java package namespace`)
-   **Mod client (étape 23 uniquement) :** Fabric Loader `0.19.3`+, Fabric
    API `0.141.6+1.21.11`, Yarn `1.21.11+build.6`, Fabric Loom `1.17.19`
    (`client-mod/gradle.properties`) — projet Gradle totalement séparé,
    jamais construit par le build racine.

### Mise en place

1.  `gradlew.bat clean build` (racine) — doit se terminer `BUILD SUCCESSFUL`
    (compile le plugin **et** `web-api`, exécute les tests JUnit des deux
    modules).
2.  `.\launcher.ps1` (ou `gradlew.bat runServer`) — premier lancement :
    le serveur s'arrête en demandant d'accepter l'EULA. Ouvrir
    `run/eula.txt`, remplacer `eula=false` par `eula=true`.
3.  Relancer `.\launcher.ps1` — le serveur démarre, génère `run/world*` et
    `run/plugins/RPGQuest/` (config, quêtes, dialogues, objets, recettes,
    nœuds, `data.db`).
4.  Se connecter avec un client Minecraft `1.21.11` (`localhost`, port par
    défaut `25565`), en mode créatif/op pour l'administration
    (`/op <pseudo>` en console si nécessaire).

Deux joueurs (ou deux comptes) sont nécessaires pour certains tests
(paiement, marché, claim, marchand). Ils sont signalés explicitement.

---

## 1. Socle (`/rpgquest`) — étape 1

### TC-001 — Version et aide

-   **Fonctionnalité testée :** `RPGQuestCommand` (`/rpgquest version|help`).
-   **Préconditions :** serveur démarré, plugin activé.
-   **Commandes :** `/rpgquest version`, `/rpgquest help`.
-   **Actions en jeu :** taper les deux commandes en jeu.
-   **Résultat attendu :** `version` affiche `RPGQuest v0.1.0-SNAPSHOT` ; si
    `config.yml` → `debug: true`, une ligne supplémentaire
    `[debug] locale=... database.file=... resource-pack.enabled=...`
    apparaît. `help` liste `version`, `profile [joueur]`, `reload`, `help`.
-   **Cas nominal :** les deux commandes répondent sans erreur.
-   **Cas invalide :** `/rpgquest bidule` → message `Commande inconnue :
    bidule` suivi de l'aide.
-   **Couverture automatisée :** aucun test dédié (commande d'affichage
    pure) ; `ConfigValidatorTest` couvre la validation de `debug`/`locale`.

### TC-002 — Profil joueur et rechargement de configuration

-   **Fonctionnalité testée :** `RPGQuestCommand` (`/rpgquest profile
    [joueur]|reload`), `PlayerProfileService`, `ConfigService`.
-   **Préconditions :** un profil existe (le joueur s'est déjà connecté au
    moins une fois — `PlayerConnectionListener` crée le profil au join).
-   **Commandes :** `/rpgquest profile`, `/rpgquest profile <autre_joueur>`,
    `/rpgquest reload`.
-   **Actions en jeu :** exécuter `/rpgquest profile` sur soi-même, puis sur
    un joueur hors-ligne connu, puis un pseudo inconnu.
-   **Résultat attendu :** profil de soi-même → nom, UUID, dates de
    création/mise à jour. Joueur hors-ligne connu → même chose (résolu de
    façon asynchrone). Pseudo inconnu → `Aucun profil trouvé pour <nom>`.
    `/rpgquest reload` (en `op`) → `Configuration RPGQuest rechargée.`
-   **Résultat console :** aucune erreur ; si `config.yml` est rendu
    invalide avant `reload` (ex. `locale: ""`), le message d'erreur précis
    apparaît en console (`warn`) et **aucune** rupture de service.
-   **Données/persistance à contrôler :** `player_profiles` (SQLite,
    `data.db`) contient une ligne par joueur connecté au moins une fois.
-   **Cas sans permission :** `/rpgquest reload` exécuté par un non-op
    (`rpgquest.admin` retiré) → `Permission manquante : rpgquest.admin`, la
    configuration n'est pas rechargée.
-   **Après reconnexion :** le profil reste identique (mêmes dates) sauf
    `updatedAt` qui avance à chaque connexion/déconnexion traitée.
-   **Après redémarrage serveur :** `/rpgquest profile` sur un joueur
    hors-ligne renvoie toujours les mêmes données (persistance SQLite).
-   **Couverture automatisée :** `PlayerProfileServiceTest`,
    `PlayerProfileRepositoryTest`, `ConfigValidatorTest`.

---

## 2. Quêtes (`/quest`, `/quests`) — étapes 3-4, 6

### TC-010 — Cycle de vie complet d'une quête simple (`first_steps`)

-   **Fonctionnalité testée :** `QuestCommand`, `QuestProgressEngine`,
    `QuestObjectiveIndex` (objectif `KILL_ENTITY`).
-   **Préconditions :** quête d'exemple `rpgquest:first_steps` chargée
    (générée au premier démarrage, `steps: kill_spiders`, 10 araignées).
-   **Commandes :** `/quest list`, `/quest accept first_steps`, `/quest
    progress first_steps`, `/quest progress`.
-   **Actions en jeu :** `/quest accept first_steps`, tuer 10 `SPIDER` (via
    `/summon spider` en créatif ou en survie), consulter la progression
    entre chaque kill.
-   **Résultat attendu :** `accept` → message `quest.accepted`. Après chaque
    araignée tuée, le compteur de `/quest progress first_steps` augmente
    (`0/10` → `10/10`). Au 10ᵉ kill, la quête passe automatiquement
    `READY_TO_TURN_IN` → `COMPLETED` (aucune commande de remise) :
    réception immédiate de 50 XP vanilla et d'une `IRON_SWORD` dans
    l'inventaire (récompenses de `first_steps.yml`).
-   **Résultat console :** aucune exception lors des transitions d'état.
-   **Données/persistance à contrôler :** `quest_progress` et
    `quest_objective_progress` (SQLite) reflètent l'état `COMPLETED` et le
    compteur final `10`.
-   **Cas nominal :** ci-dessus.
-   **Cas invalide :** `/quest accept id_inexistant` → `quest.unknown` ;
    `/quest accept first_steps` une seconde fois pendant qu'elle est active
    → `quest.already-active` ; une fois `COMPLETED` (non répétable) →
    `quest.not-repeatable`.
-   **Cas sans permission :** retirer `rpgquest.quest` → toute sous-commande
    joueur répond `permission.denied`.
-   **Après reconnexion :** se déconnecter avec la quête `ACTIVE` à 5/10,
    se reconnecter, tuer 5 araignées de plus → la quête se termine
    normalement (compteur repris exactement où il était).
-   **Après redémarrage serveur :** redémarrer avec la quête `ACTIVE`,
    revérifier `/quest progress first_steps` → compteur identique à avant
    l'arrêt.
-   **Couverture automatisée :** `QuestProgressEngineTest`,
    `QuestDefinitionParserTest`, `QuestLoaderTest`, `YamlQuestEngineTest`.

### TC-011 — Prérequis, abandon, et admin (`crystal_hunt`)

-   **Fonctionnalité testée :** prérequis de quête, `/quest abandon`,
    `/quest complete` (admin), `/quest admin reload|validate`.
-   **Préconditions :** `rpgquest:crystal_hunt` requiert
    `rpgquest:first_steps` `COMPLETED`.
-   **Commandes :** `/quest accept crystal_hunt` (avant et après avoir
    terminé `first_steps`), `/quest abandon crystal_hunt`, `/quest complete
    crystal_hunt` (`rpgquest.admin`), `/quest admin reload`, `/quest admin
    validate`.
-   **Actions en jeu :** tenter `/quest accept crystal_hunt` sans avoir
    terminé `first_steps`, puis après.
-   **Résultat attendu :** avant prérequis → `quest.prerequisites-missing`
    listant `first_steps`. Après → `quest.accepted`. `/quest abandon
    crystal_hunt` pendant qu'elle est active → `quest.abandoned`, quête
    retirée du cache actif (mais réacceptable, `repeatable` n'a pas
    d'incidence sur l'abandon). `/quest complete crystal_hunt` force la
    complétion et les récompenses même sans objectifs remplis (outil admin
    de test). `/quest admin reload` recharge quêtes + `messages.yml` et
    rapporte `N quête(s) chargée(s), 0 erreur(s)`. `/quest admin validate`
    fait la même validation sans rien appliquer.
-   **Résultat console :** placer un fichier de quête volontairement
    invalide dans `run/plugins/RPGQuest/quests/` (ex. `steps` vide),
    `/quest admin reload` → le fichier invalide est rejeté seul (message
    `- [fichier] raison`), les autres quêtes restent chargées.
-   **Cas sans permission :** `/quest complete`, `/quest admin reload`,
    `/quest admin validate` par un non-admin → `permission.denied`.
-   **Couverture automatisée :** `QuestProgressEngineTest` (prérequis,
    abandon, `forceComplete`), `CrystalHuntIntegrationTest` (parcours
    complet dialogue → quête → combat → récolte → fabrication → remise).

### TC-012 — Dialogue → quête → combat → récolte → fabrication → remise (`crystal_hunt`, parcours intégral)

-   **Fonctionnalité testée :** enchaînement complet inter-systèmes
    (dialogue, quête, `SpiderFangDropListener`, `ResourceNodeService`,
    `RecipeCraftGuardListener`, `TALK_TO_NPC`).
-   **Préconditions :** `first_steps` `COMPLETED`. Une entité vivante
    (ex. villageois) renommée exactement `guard` (enclume) présente en jeu.
    Nœud de ressource `rpgquest:crystal_ore` placé via `/resourcenode create
    crystal_ore` sur un bloc visé (voir TC-050).
-   **Commandes/actions :** clic droit sur l'entité `guard` (ouvre
    `rpgquest:guard`, choisir « J'ai entendu dire... » pour démarrer
    `crystal_hunt`) ; tuer 5 `SPIDER`/`CAVE_SPIDER` (chaque mort donne 1
    `rpgquest:spider_fang`, `hunt_spiders` 5/5) ; récolter 2
    `AMETHYST_SHARD` (nœud `crystal_ore` ou tirage direct, `gather_crystals`
    2/2) ; fabriquer une épée en diamant (recette vanilla ou
    `forest_blade_recipe`, `forge_blade` 1/1 — limite connue : le type
    d'objectif `CRAFT_ITEM` ne distingue pas objet personnalisé/vanilla de
    même matériau) ; reclic sur `guard` (`report_to_guard`, `TALK_TO_NPC`).
-   **Résultat attendu :** la quête passe automatiquement `COMPLETED` à la
    remise, octroi de 100 XP vanilla + `rpgquest:miner_pickaxe` (récompense
    `COMMAND` : `customitem give %player% rpgquest:miner_pickaxe 1`).
-   **Données/persistance à contrôler :** `quest_progress`/
    `quest_objective_progress` à `COMPLETED` pour les 4 étapes.
-   **Couverture automatisée :** `CrystalHuntIntegrationTest` (le même
    parcours, en JUnit/MockBukkit).

### TC-013 — Journal de quêtes (`/quests`)

-   **Fonctionnalité testée :** `QuestsCommand`, `QuestJournalService`,
    `QuestJournalListener`, `TrackedQuestDisplay`.
-   **Préconditions :** au moins une quête `ACTIVE`, une `NOT_STARTED`, une
    `COMPLETED` (utiliser `first_steps`/`crystal_hunt`/`/quest complete`).
-   **Commandes :** `/quests`.
-   **Actions en jeu :** ouvrir `/quests` ; naviguer entre les 3 onglets
    (Actives/Disponibles/Terminées) ; clic gauche sur une quête (vue détail,
    27 slots) ; clic droit sur une quête dans la liste (bascule le suivi) ;
    tenter un shift-clic/double-clic/glisser sur un slot du menu ; tenter de
    déposer un objet dans le menu ; cliquer sur le bouton « Fermer » (barrière,
    dernier slot) depuis la liste **et** depuis la vue détail.
-   **Résultat attendu :** 3 onglets peuplés correctement selon l'état.
    Clic gauche → vue détail (icône, description, étape courante avec
    progression, récompenses, prérequis, boutons retour/suivre/fermer).
    Clic droit → active/désactive le suivi sans changer de vue. Toute
    tentative de vol/dépôt/échange dans le menu est bloquée
    (`event.setCancelled(true)`), aucun objet ne quitte ni n'entre dans le
    menu. Le bouton « Fermer » ferme réellement l'inventaire côté client (bug
    corrigé : la fermeture était auparavant appelée pendant le traitement du
    clic lui-même, ce qui pouvait laisser la fenêtre visuellement ouverte —
    elle est maintenant différée d'un tick serveur,
    `QuestJournalService#closeNextTick`).
-   **Résultat console :** aucune exception sur navigation rapide (page
    suivante/précédente, changement d'onglet en rafale).
-   **Données/persistance à contrôler :** le suivi (`__tracked_quest__`
    dans `player_variables`) persiste après déconnexion/reconnexion et
    après redémarrage. Une bossbar (`journal.tracker-enabled: true`) suit
    la quête suivie **si** elle est `ACTIVE`, avec la progression de
    l'étape courante ; désactiver `tracker-enabled` dans `config.yml` +
    `/rpgquest reload` ne supprime pas le suivi persisté, seulement
    l'affichage.
-   **Après reconnexion :** la bossbar réapparaît si la quête suivie est
    toujours `ACTIVE`.
-   **Après redémarrage serveur :** le suivi est toujours actif après
    redémarrage (persisté en base, pas en mémoire).
-   **Cas sans permission :** retirer `rpgquest.quest` → `/quests` répond
    `Permission manquante : rpgquest.quest`.
-   **Couverture automatisée :** `JournalPaginationTest`,
    `QuestJournalServiceTest` (dont
    `closeButtonInTheListViewDefersClosingToTheNextTick` et
    `closeButtonInTheDetailViewDefersClosingToTheNextTick` pour le bug
    corrigé ci-dessus).

### TC-014 — Feedback de récompenses à la remise

-   **Fonctionnalité testée :** `QuestProgressEngine#turnIn`/`#grantRewards`/
    `#showQuestCompleted`, clés `quest.reward-summary-header`/
    `reward-line-experience`/`reward-line-item`/`reward-line-special` de
    `messages.yml`.
-   **Préconditions :** aucune (utiliser `first_steps` ou `crystal_hunt`).
-   **Actions en jeu :** terminer `first_steps` (donne 50 XP + 1
    `IRON_SWORD`).
-   **Résultat attendu :** en plus du Title « Quête terminée » habituel, un
    message apparaît **dans le chat**, une seule fois : une ligne d'en-tête
    citant le nom de la quête, puis une ligne par récompense réellement
    accordée (`+ 50 XP`, `+ 1x IRON_SWORD`). Aucune récompense qui n'est pas
    réellement dans `first_steps.yml` ne doit apparaître. Pour
    `crystal_hunt` (récompense `COMMAND` donnant `rpgquest:miner_pickaxe`),
    la ligne correspondante affiche un texte générique (« Récompense
    spéciale ») plutôt qu'un nom d'objet, faute de pouvoir inspecter le
    contenu d'une commande arbitraire.
-   **Cas sans récompense :** forcer la complétion d'une quête sans
    `rewards` (`/quest complete <id>` sur une quête de test créée sans
    section `rewards`) → aucun message n'apparaît dans le chat (pas de
    résumé vide, pas de récompense inventée).
-   **Couverture automatisée :**
    `QuestProgressEngineTest#completingAQuestSendsAChatSummaryOfRewardsActuallyGranted`,
    `#rewardSummaryListsAnItemAndACommandRewardWithoutInventingDetails`,
    `#questWithNoRewardsSendsNoChatSummary`.

### Pack de quêtes de test manuel — un objectif de chaque type

Dix quêtes minimalistes, une par type d'`ObjectiveType` implémenté,
destinées **uniquement** au test manuel sur un serveur réel (y compris
VeryGames) — jamais à la production. Fichiers dans
`docs/manual-tests/quests/` (racine du dépôt, **jamais copiés
automatiquement** : `YamlQuestEngine#BUNDLED_EXAMPLES` ne les référence
pas, donc ils n'apparaissent jamais sur un serveur tant qu'on ne les colle
pas soi-même) :

| Fichier | Id | Type testé | Objectif |
|---|---|---|---|
| `test_break_block.yml` | `rpgquest:test_break_block` | `BREAK_BLOCK` | Casser 3 `DIRT`. |
| `test_place_block.yml` | `rpgquest:test_place_block` | `PLACE_BLOCK` | Poser 3 `DIRT`. |
| `test_kill_entity.yml` | `rpgquest:test_kill_entity` | `KILL_ENTITY` | Tuer 2 `ZOMBIE` (`/summon zombie` en créatif si besoin). |
| `test_collect_item.yml` | `rpgquest:test_collect_item` | `COLLECT_ITEM` | Ramasser 5 `STICK` au sol (les jeter puis marcher dessus — un pickup au sol est **obligatoire**, `/give` ne compte pas). |
| `test_craft_item.yml` | `rpgquest:test_craft_item` | `CRAFT_ITEM` | Fabriquer 1 `STICK` (grille 2×2 de l'inventaire, aucun établi requis). |
| `test_talk_to_npc.yml` | `rpgquest:test_talk_to_npc` | `TALK_TO_NPC` | Taguer une entité avec `/rpgadmin npc tag test_dummy` puis clic droit dessus. |
| `test_reach_location.yml` | `rpgquest:test_reach_location` | `REACH_LOCATION` | S'approcher à moins de 20 blocs de `world 0,64,0` (ajuster `x`/`y`/`z` dans le fichier avec ses propres coordonnées `F3` si le spawn réel est ailleurs). |
| `test_smelt_item.yml` | `rpgquest:test_smelt_item` | `SMELT_ITEM` | **Cuire** 2 cactus en teinture verte : four + combustible + cactus, puis **retirer soi-même** le résultat. Un `/give` de `GREEN_DYE` ou une extraction par entonnoir ne comptent pas. |
| `test_discover_waypoint.yml` | `rpgquest:test_discover_waypoint` | `DISCOVER_WAYPOINT` | **Découvrir** 2 waypoints encore inconnus en cliquant sur leur bouton. Passer à proximité, se téléporter depuis une borne, ou recliquer un waypoint déjà découvert ne comptent pas. Deux waypoints d'un même biome comptent séparément. Le libellé affiché doit énoncer la portée (« tous mondes ») et la règle (« nouvelles découvertes »). |
| `test_deliver_item_to_npc.yml` | `rpgquest:test_deliver_item_to_npc` | `DELIVER_ITEM_TO_NPC` | **Remettre** 1 `STICK`, 2 `COBBLESTONE`, 4 `LEATHER` et 3 `WHEAT_SEEDS` au PNJ `guard` : parler au Garde → « Donner les matériaux que j'ai ». Posséder ou ramasser ne compte **jamais** ; les objets sont consommés et les dépôts partiels restent acquis. Recette détaillée : **TC-257**. |

Toutes `repeatable: true` (rejouables sans `/quest admin reset`), avec une
récompense symbolique de 5 XP chacune (permet aussi de vérifier au passage
le résumé de récompenses de TC-014).

**Procédure d'activation (test manuel uniquement, à retirer ensuite) :**

1.  Copier les 10 fichiers de `docs/manual-tests/quests/` vers
    `plugins/RPGQuest/quests/` sur le serveur de test.
2.  `/quest admin reload` (ou redémarrer) → le rapport doit annoncer 10
    quêtes de plus chargées, 0 erreur.
3.  Pour chaque type : `/quest accept rpgquest:test_<type>`, réaliser
    l'action décrite ci-dessus, vérifier `/quest progress
    rpgquest:test_<type>` puis la remise automatique (Title + résumé chat,
    voir TC-014). Pour `test_talk_to_npc`, taguer l'entité **avant**
    d'accepter ou après, peu importe — seul l'ordre clic-après-tag compte.
4.  Une fois les 10 types validés, **supprimer les 10 fichiers** de
    `plugins/RPGQuest/quests/` puis `/quest admin reload` à nouveau (le
    rapport doit annoncer leur disparition, 0 erreur) — ces quêtes ne
    doivent **jamais** rester dans une installation VeryGames de
    production entre deux sessions de test.
5.  Optionnel : `/quest admin reset <joueur> all` (ou juste les 10 ids) pour
    nettoyer la progression de test avant de retirer les fichiers, si le
    même compte sert aussi à des tests de production.

-   **Couverture automatisée :** `ManualTestQuestPackTest` (le pack reste
    chargeable sans erreur, un id `test_*` par quête, les 10 types
    d'`ObjectiveType` sont couverts exactement une fois — échoue si le
    format de quête ou la liste des types change sans que ce pack soit mis
    à jour en conséquence).

### TC-015 — `CRAFT_ITEM` compte la quantité réellement fabriquée (issue #34)

-   **Fonctionnalité testée :** `QuestCraftItemListener` (quantité par
    type de clic, mesure au tick suivant pour le shift-clic).
-   **Préconditions :** une quête active avec un objectif
    `CRAFT_ITEM material: OAK_PLANKS amount: 20` (ex. publiée depuis
    PlugAdmin), des bûches de chêne.
-   **Actions et résultats attendus :**
    1.  1 bûche, clic simple sur le résultat → progression **+4** (4/20).
    2.  Second craft simple (curseur contenant déjà les planches) → **8/20**.
    3.  Curseur tenant un autre objet, clic sur le résultat → rien n'est
        fabriqué, progression inchangée.
    4.  3 bûches, shift-clic → **+12**.
    5.  Inventaire plein sauf une pile partielle de planches → shift-clic :
        seule la quantité rangée est comptée.
    6.  Objectif à 18/20, shift-clic de 4 bûches → l'objectif passe à 20/20,
        jamais au-delà, l'étape se termine une seule fois.
    7.  Déconnexion/reconnexion → le compteur n'est pas recompté.
-   **Couverture automatisée :** `QuestCraftObjectiveTest`.
-   **Statut :** `PENDING MANUAL VALIDATION`.

---

## 3. Dialogues (`/dialogue`) — étape 5

### TC-020 — Ouverture à distance et branchement conditionnel

-   **Fonctionnalité testée :** `DialogueCommand` (`/dialogue open <joueur>
    <dialogueId>`), `DialogueSessionEngine`, `ChatDialogueRenderer`
    (renderer par défaut, `config.yml` → `dialogue.renderer: chat`).
-   **Préconditions :** joueur en ligne, `rpgquest:guard` chargé.
-   **Commandes :** `/dialogue open <pseudo> guard`.
-   **Actions en jeu :** exécuter la commande en admin ; cliquer sur les
    choix proposés au joueur cible dans le chat (liens cliquables
    `ClickEvent.callback`).
-   **Résultat attendu :** le dialogue s'ouvre chez le joueur cible dans le
    chat, avec le texte du nœud `greeting` et les choix visibles ; les choix
    conditionnés par `QUEST_STATE` (`first_steps` `NOT_STARTED`/
    `COMPLETED`) n'apparaissent que si la condition est vraie au moment de
    l'affichage **et** revérifiée au clic (accepter la quête via une autre
    voie entre affichage et clic doit invalider silencieusement un ancien
    choix rejoué).
-   **Cas invalide :** `/dialogue open <pseudo> id_inexistant` → `Dialogue
    introuvable : rpgquest:id_inexistant`. `/dialogue open joueur_hors_ligne
    guard` → `Joueur introuvable ou hors-ligne`.
-   **Cas sans permission :** non-admin → `Permission manquante :
    rpgquest.admin`.
-   **Après reconnexion :** ouvrir un dialogue, se déconnecter avant de
    répondre, se reconnecter → la session est vide (jamais persistée), le
    dialogue doit être rouvert depuis le début.
-   **Couverture automatisée :** `DialogueDefinitionParserTest`,
    `DialogueLoaderTest`, `DialogueSessionEngineTest`,
    `ChatDialogueRendererTest`.

### TC-021 — PNJ cliquable et actions de dialogue

-   **Fonctionnalité testée :** `DialogueNpcInteractListener` (clic sur une
    entité renommée), actions `START_QUEST`/`CLOSE`/`OPEN_MERCHANT`.
-   **Préconditions :** une entité vivante renommée exactement `guard`
    (enclume) présente en jeu ; une autre renommée exactement `merchant`
    (pour `rpgquest:merchant` → `OPEN_MERCHANT`).
-   **Actions en jeu :** clic droit sur `guard` → dialogue `rpgquest:guard`
    s'ouvre. Clic droit sur `merchant` → dialogue `rpgquest:merchant`
    s'ouvre, choisir « Voir la boutique » → la vitrine `village_merchant`
    s'ouvre (voir TC-122).
-   **Résultat attendu :** identification uniquement par le nom personnalisé
    de l'entité, pas par son type ; renommer une autre entité `guard`
    (même un zombie) doit aussi ouvrir le dialogue.
-   **Cas invalide :** clic droit sur une entité non renommée → aucun effet.
-   **Couverture automatisée :** aucun test dédié au listener Bukkit
    (interaction événementielle réelle) ; la logique métier est couverte
    par `DialogueSessionEngineTest`.

---

## 4. Objets personnalisés et comportements (`/customitem`) — étapes 7-8

### TC-040 — Registre d'objets (give / list / inspect)

-   **Fonctionnalité testée :** `CustomItemCommand`,
    `YamlCustomItemRegistry`.
-   **Préconditions :** 4 objets d'exemple chargés (`forest_blade`,
    `miner_pickaxe`, `spider_fang`, `refined_crystal`).
-   **Commandes :** `/customitem give <pseudo> forest_blade`, `/customitem
    give <pseudo> spider_fang 64`, `/customitem list`, `/customitem
    inspect` (objet en main).
-   **Actions en jeu :** exécuter les commandes ; tenir successivement
    `forest_blade` puis un `DIAMOND_SWORD` vanilla renommé identiquement
    (nom + lore copiés) et exécuter `/customitem inspect` sur chacun.
-   **Résultat attendu :** `give` dépose l'objet dans l'inventaire (ou au
    sol si plein) avec nom/lore/rareté/attributs/enchantements corrects.
    `list` énumère les 4 objets (id, type, rareté). `inspect` sur
    `forest_blade` affiche tous ses détails ; **sur l'imitation vanilla
    renommée, `inspect` répond que ce n'est pas un objet personnalisé**
    (identification uniquement par `PersistentDataContainer`, jamais par
    nom/lore).
-   **Cas invalide :** `/customitem give <pseudo> id_inconnu` → `ID d'objet
    inconnu`. `/customitem give <pseudo> spider_fang abc` → `Quantité
    invalide (nombre attendu)`. `/customitem give <pseudo> forest_blade
    999` → `Quantité invalide ... (doit être entre 1 et 1)` (non
    empilable).
-   **Cas sans permission :** `give`/`list` par un non-admin →
    `Permission manquante : rpgquest.admin` ; `inspect` par un joueur sans
    `rpgquest.item` → `Permission manquante : rpgquest.item`.
-   **Couverture automatisée :** `ItemDefinitionParserTest`,
    `ItemLoaderTest`, `YamlCustomItemRegistryTest` (dont
    `renamedVanillaItemIsNotRecognized`).

### TC-041 — Comportement de combat (`forest_blade`)

-   **Fonctionnalité testée :** `WeaponBehaviorListener` (`combat:` de
    `forest_blade.yml` : `base-damage: 1.5`, `critical-chance: 0.2`,
    `critical-multiplier: 1.5`, effet `leaf_trail_slow`).
-   **Préconditions :** posséder `forest_blade` (TC-040), une cible
    (mob hostile ou joueur en PvP autorisé hors safe zone).
-   **Actions en jeu :** frapper la cible plusieurs fois (assez pour
    observer un critique, `critical-chance: 0.2`).
-   **Résultat attendu :** dégât appliqué = dégât vanilla (attribut +
    enchantement Tranchant III déjà intégrés par le jeu) **+ 1.5** bonus
    additif ; sur un coup critique (probabiliste), message
    `<red>Coup critique !</red> (X dégâts)`, particule `CRIT` (10),
    dégât multiplié par 1.5. Occasionnellement (25 % de chance sur un coup
    notable, cooldown 8 s par capacité), la cible reçoit `SLOWNESS`
    amplifier 1 pendant 3 s (60 ticks).
-   **Cas invalide (garde-fous) :** frapper un `ArmorStand` avec
    `forest_blade` → aucun bonus appliqué (exclu explicitement, bien que
    Bukkit le classe `LivingEntity`). Frapper via une flèche tirée avec
    l'arme équipée en main (n'a pas de sens ici, mais vérifier qu'un
    projectile ne déclenche jamais le bonus).
-   **Couverture automatisée :** `WeaponBehaviorListenerTest`,
    `CooldownManagerTest`.

### TC-042 — Comportement d'outil (`miner_pickaxe`)

-   **Fonctionnalité testée :** `ToolBehaviorListener` (`tool:` de
    `miner_pickaxe.yml` : bonus de minage restreint aux minerais,
    bonus de récolte, capacité spéciale `miner_rush`).
-   **Préconditions :** posséder `miner_pickaxe` (TC-040).
-   **Actions en jeu :** miner `IRON_ORE`/`DIAMOND_ORE` (dans
    `allowed-blocks`) puis `STONE` (hors liste) ; clic droit à vide (main
    principale) pour déclencher `miner_rush`, répéter avant/après le
    cooldown de 30 s.
-   **Résultat attendu :** minage plus rapide sur les blocs listés
    uniquement (attribut `MINING_EFFICIENCY`, vérifiable en comparant le
    temps de casse à une pioche en diamant vanilla) ; sur `STONE`, aucun
    bonus. Bonus de récolte occasionnel (15 % de chance, +1 item) observé
    sur plusieurs minages de blocs listés. Clic droit → message `<aqua>Vous
    sentez une ruée de minage !</aqua>`, particule `CRIT` (15) ; un second
    clic droit avant 30 s ne redéclenche rien (cooldown par (joueur,
    `miner_rush`)).
-   **Après reconnexion :** le cooldown de la capacité n'est **pas**
    persisté (`CooldownManager` en mémoire, nettoyé à la déconnexion) — se
    déconnecter puis reconnecter doit permettre de redéclencher
    immédiatement `miner_rush`.
-   **Couverture automatisée :** `ToolBehaviorListenerTest`.

### TC-043 — Drop garanti (`spider_fang`)

-   **Fonctionnalité testée :** `SpiderFangDropListener`.
-   **Actions en jeu :** tuer une `SPIDER` puis une `CAVE_SPIDER` en tant
    que joueur ; tuer une araignée via une source non-joueur (chute dans le
    vide, feu, ou un autre mob).
-   **Résultat attendu :** chaque araignée tuée **par un joueur** dépose
    exactement 1 `rpgquest:spider_fang` (drop garanti, pas probabiliste),
    en plus des drops vanilla normaux. Une mort **non provoquée par un
    joueur** (`killer == null`) ne dépose **aucun** `spider_fang`.
-   **Couverture automatisée :** aucun test JUnit direct sur le listener
    Bukkit (`shouldDrop` est le cœur testable, mais non couvert par un
    fichier de test dédié listé — vérifier `SpiderFangDropListenerTest` si
    présent) ; couvert en conditions réelles par `CrystalHuntIntegrationTest`
    (dépendance du parcours `hunt_spiders`).

---

## 5. Nœuds de ressource et recettes (`/resourcenode`) — étapes 9-10

### TC-050 — Cycle complet d'un nœud de ressource

-   **Fonctionnalité testée :** `ResourceNodeCommand`, `ResourceNodeService`,
    `ResourceNodeBreakListener`.
-   **Préconditions :** type `rpgquest:crystal_ore` chargé (actif =
    `EMERALD_ORE`, épuisé = `STONE`, respawn 300 s, outils requis
    `IRON_PICKAXE`/`DIAMOND_PICKAXE`/`NETHERITE_PICKAXE`).
-   **Commandes :** viser un bloc à ≤ 6 blocs, `/resourcenode create
    crystal_ore`, `/resourcenode inspect`, `/resourcenode remove`.
-   **Actions en jeu :** créer le nœud sur un bloc quelconque (devient
    `EMERALD_ORE`) ; le récolter avec une pioche en bois (hors liste
    autorisée) puis avec une pioche en fer (autorisée).
-   **Résultat attendu :** avec un outil non autorisé, le bloc ne donne pas
    le butin du nœud (comportement vanilla du bloc sous-jacent, à vérifier
    au cas par cas). Avec un outil autorisé : le butin pondéré est tiré
    (soit `rpgquest:refined_crystal`, poids 30, soit `QUARTZ` ×1-3, poids
    70) et le bloc devient `STONE` (épuisé). `/resourcenode inspect` sur le
    bloc épuisé affiche `État : épuisé (respawn dans Xs)`, décroissant.
    Après 300 s **et** rechargement naturel du chunk, le bloc redevient
    `EMERALD_ORE` (`/resourcenode inspect` → `actif`).
-   **Cas invalide :** `/resourcenode create id_inconnu` → `Type de nœud
    inconnu`. `/resourcenode create crystal_ore` une seconde fois sur le
    même bloc → `Il y a déjà un nœud à cette position`. `/resourcenode
    inspect`/`remove` sans viser de bloc à portée → `Aucun bloc visé à
    portée`.
-   **Données/persistance à contrôler :** position du nœud et compte à
    rebours de respawn persistés en SQLite par monde.
-   **Après redémarrage serveur :** épuiser un nœud, redémarrer avant la
    fin du respawn → `/resourcenode inspect` indique toujours `épuisé` avec
    un temps restant cohérent (pas remis à zéro, pas expiré prématurément).
-   **Couverture automatisée :** `ResourceNodeDefinitionParserTest`,
    `ResourceNodeLoaderTest`, `ResourceNodeServiceTest`.

### TC-051 — Recettes façonnées/sans forme et anti-triche

-   **Fonctionnalité testée :** `RecipeLoader`, `RecipeCraftGuardListener`
    (recettes générées : `forest_blade_recipe`, `refined_crystal_recipe`,
    `miner_pickaxe_recipe`).
-   **Préconditions :** ingrédients réels en inventaire : 2×
    `rpgquest:spider_fang` + 1 `STICK` (motif `forest_blade_recipe`), 4×
    `QUARTZ` (`refined_crystal_recipe`).
-   **Actions en jeu :** ouvrir une table de craft, reproduire le motif
    `forest_blade_recipe` (F=spider_fang au centre haut/milieu, S=stick en
    bas milieu, motif `" F "/" F "/" S "`) avec de vrais
    `rpgquest:spider_fang` obtenus via TC-043/TC-040 ; répéter avec des
    `BONE` vanilla renommés/lorés pour imiter `spider_fang`.
-   **Résultat attendu :** avec les vrais objets personnalisés, la recette
    est reconnue (résultat `forest_blade` disponible), y compris via le
    livre de recettes vanilla (clic auto) et le shift-clic. Avec
    l'imitation vanilla (même nom/lore, mais sans PDC), **la recette ne se
    valide jamais** (`RecipeChoice.ExactChoice` + `RecipeCraftGuardListener`
    sur `PrepareItemCraftEvent`).
-   **Résultat console :** aucune exception lors de la préparation/du craft.
-   **Cas invalide :** grille incomplète ou mal disposée → aucun résultat
    affiché (comportement vanilla standard).
-   **Couverture automatisée :** `RecipeDefinitionParserTest`,
    `RecipeLoaderTest`, `YamlCraftingRegistryTest`,
    `RecipeCraftGuardListenerTest`.

### TC-052 — Resource pack optionnel (désactivé par défaut)

-   **Fonctionnalité testée :** `ResourcePackConfig`, envoi à la connexion.
-   **Préconditions :** `config.yml` → `resource-pack.enabled: false`
    (défaut).
-   **Actions en jeu :** se connecter sans configuration particulière.
-   **Résultat attendu :** aucun resource pack proposé, tous les objets
    personnalisés gardent l'apparence de leur matériau vanilla de base.
-   **Cas activé (optionnel, nécessite un hébergement HTTP réel du zip) :**
    `gradlew.bat resourcePackSha1` (génère `build/resource-pack/
    RPGQuest-resource-pack.zip(.sha1)`), héberger le zip, renseigner
    `resource-pack.enabled: true`/`url`/`sha1` (40 caractères hex) dans
    `config.yml`, `/rpgquest reload`, se reconnecter → invite MiniMessage
    de téléchargement, `forest_blade`/`miner_pickaxe`/`spider_fang`/
    `refined_crystal` affichent leur modèle JSON (texture placeholder
    vanilla réutilisée, pas de texture propre au projet à ce stade).
    Refuser le pack → apparence vanilla conservée, avertissement affiché
    seulement si `required: true`, jamais de déconnexion automatique.
-   **Couverture automatisée :** `ConfigValidatorTest` (section
    `resource-pack`).

---

## 6. Serveur local et workflow de développement — étape 11

### TC-060 — `runServer` / `launcher.ps1` et persistance

-   **Fonctionnalité testée :** plugin Gradle `xyz.jpenilla.run-paper`,
    `launcher.ps1`.
-   **Actions en jeu :** `.\launcher.ps1` deux fois de suite ; modifier
    `run/plugins/RPGQuest/messages.yml`, arrêter (`stop` en console),
    relancer.
-   **Résultat attendu :** premier lancement télécharge Paper (mise en
    cache), démarre, charge RPGQuest (services dans l'ordre : config, base
    de données, moteur de quêtes, objets, recettes, nœuds, dialogues,
    journal). Le changement dans `messages.yml` persiste au redémarrage.
    Aucun `/reload` Bukkit ne doit être utilisé pour tester un changement
    de code (toujours `stop` puis relancer `runServer`) ; `/rpgquest
    reload` (config uniquement) reste sûr.
-   **Résultat console :** `Done (...)! For help, type "help"`, puis
    `RPGQuest 0.1.0-SNAPSHOT activé`.
-   **Après redémarrage serveur :** `run/plugins/RPGQuest/` (config,
    quêtes, dialogues, objets, recettes, nœuds, `data.db`) n'est jamais
    régénéré si déjà présent (seuls les fichiers d'exemple absents sont
    recréés).
-   **Couverture automatisée :** aucune (cycle manuel par construction) ;
    `gradlew test` couvre le comportement métier sous-jacent.

---

## 7. Aplatissement de terrain (`/rpgadmin flatten`) — étape 12

### TC-070 — Aperçu, confirmation, annulation, undo

-   **Fonctionnalité testée :** `RpgAdminCommand` (`flatten`),
    `FlattenService`.
-   **Préconditions :** `rpgquest.admin.world`, terrain non plat à
    proximité.
-   **Commandes :** `/rpgadmin flatten 10`, `/rpgadmin flatten 10 70`,
    `/rpgadmin flatten confirm`, `/rpgadmin flatten cancel`, `/rpgadmin
    flatten undo`.
-   **Actions en jeu :** lancer un aperçu (`/rpgadmin flatten 10`),
    attendre l'affichage forme/rayon/hauteur/colonnes/estimation de blocs,
    puis `confirm`.
-   **Résultat attendu :** l'aperçu ne modifie **aucun** bloc. `confirm`
    lance le chantier (traitement par lots, actionbar de progression
    ~1×/s), applique au-dessus la clairière (`clear-above-height: 10`),
    sous le niveau cible `DIRT` sur 3 blocs, puis `GRASS_BLOCK` en surface.
-   **Cas invalide :** `/rpgadmin flatten 999` (> `max-radius: 48`) →
    rayon invalide. `/rpgadmin flatten 10 99999` (hauteur hors monde) →
    hauteur invalide. `/rpgadmin flatten confirm` sans aperçu en attente →
    `Aucun aperçu en attente`. Attendre > 30 s (`confirmation-timeout-
    seconds`) avant `confirm` → `L'aperçu a expiré`.
-   **Cas d'annulation :** `cancel` pendant un chantier en cours → arrête le
    traitement (le travail déjà fait reste) ; `undo` juste après → restaure
    l'état exact d'avant (un seul niveau d'annulation, écrasé par
    l'aplatissement suivant).
-   **Résultat console :** aucun gel perceptible du serveur sur une grande
    zone (rayon proche de 48).
-   **Cas sans permission :** non-admin → `Permission manquante :
    rpgquest.admin.world`.
-   **Après reconnexion :** se déconnecter pendant un chantier en cours, se
    reconnecter → le chantier continue en arrière-plan (tâche serveur, pas
    liée à la session joueur) ; `/rpgadmin flatten undo` reste disponible
    une fois terminé.
-   **Couverture automatisée :** `FlattenServiceTest`, `ConfigValidatorTest`
    (section `admin.flatten`).

---

## 8. Zones protégées (`/rpgadmin zone`) — étape 13

### TC-080 — Création et protections par défaut

-   **Fonctionnalité testée :** `RpgAdminCommand` (`zone`),
    `ZoneSelectionService`, `ZoneProtectionListener`.
-   **Préconditions :** zone d'exemple `central_village` déjà chargée (voir
    `zones/central_village.yml`) ; ou créer une nouvelle zone de test.
-   **Commandes :** `/rpgadmin zone wand`, `/rpgadmin zone create
    <id>`, `/rpgadmin zone list`, `/rpgadmin zone info <id>`, `/rpgadmin
    zone delete <id>`.
-   **Actions en jeu :** `/rpgadmin zone wand`, clic gauche (position 1) /
    clic droit (position 2) avec l'outil reçu (tige de blaze) sur deux
    coins, `/rpgadmin zone create test_zone`. Si WorldEdit est installé,
    vérifier que ses propres messages de sélection (« Première/Seconde
    position définie ») n'apparaissent **pas** pendant cette manipulation —
    signe que les deux wands sont bien indépendantes.
-   **Résultat attendu :** `Zone créée : test_zone` ; `/rpgadmin zone info
    test_zone` affiche les bornes et les flags par défaut (`pvp=false
    break=false place=false explosions=false feu=false lave=false
    pistons=false spawn=false portes=true boutons=true leviers=true
    pnj=true conteneurs=false`).
-   **Cas invalide :** `create` sans sélection préalable → message d'erreur.
    `create` avec un id déjà pris → `Une zone porte déjà l'id`. `create`
    chevauchant `central_village` → `chevauche une zone existante`.
-   **Protections à vérifier dans `central_village` (ou `test_zone`) :**
    -   Non-membre casse/pose un bloc → annulé.
    -   PvP entre deux joueurs non-op → dégâts annulés.
    -   Creeper/TNT explose → aucune destruction de bloc.
    -   Feu (silex+acier, propagation) → bloqué.
    -   Seau de lave posé → bloqué.
    -   Piston poussant un bloc à travers la frontière → bloqué.
    -   Monstre hostile → aucun spawn naturel dans la zone.
    -   Porte/bouton/levier → utilisable par défaut (`true`).
    -   Clic droit sur une entité nommée (dialogue) → autorisé par défaut.
    -   Coffre/tonneau/fourneau → bloqué pour un non-membre
        (`public-containers: false`).
-   **Cas bypass admin :** avec `rpgquest.admin.world`, un joueur peut
    casser/poser/PvP dans la zone ; il **n'exempte pas** la victime (le
    dégât PvP reste annulé si c'est l'attaquant qui n'a pas la permission,
    même si la victime l'a).
-   **Après redémarrage serveur :** `/rpgadmin zone list` affiche toujours
    `central_village` + `test_zone` (fichiers YAML persistants).
-   **Couverture automatisée :** `ZoneDefinitionTest`,
    `ZoneDefinitionParserTest`, `ZoneLoaderTest`, `ZoneRegistryTest`,
    `ZoneProtectionListenerTest`.

---

## 9. Économie et marchands (`/money`, `/merchant`) — étape 14

### TC-120 — Portefeuille et paiement entre joueurs

-   **Fonctionnalité testée :** `MoneyCommand`, `EconomyService`.
-   **Préconditions :** deux joueurs en ligne (A, B).
-   **Commandes :** `/money` (A), `/money pay B 10` (A), `/money admin give
    A 100` (admin), `/money admin take A 50`, `/money admin set B 0`.
-   **Actions en jeu :** exécuter les commandes dans l'ordre : consulter le
    solde de A (doit être `0` à la première consultation, aucun solde de
    départ), `/money admin give A 100`, `/money pay B 10`.
-   **Résultat attendu :** premier `/money` → `Solde : 0 pièce(s)`. Après
    `give` → `100`. Après `pay B 10` → A a `90`, B a `10` ; les deux
    joueurs reçoivent un message (`Envoyé.../Reçu...`).
-   **Cas invalide :** `/money pay B 0` ou `-5` → `Montant invalide (doit
    être strictement positif)`. `/money pay B 100000` avec solde
    insuffisant → `Fonds insuffisants`. `/money pay A_lui_meme 10` (payer
    soi-même) → `Tu ne peux pas te payer toi-même`. `/money pay
    joueur_hors_ligne 10` → `Joueur introuvable ou hors-ligne` (limitation
    connue : la cible doit être en ligne).
-   **Cas sans permission :** `rpgquest.money` retiré → `permission
    manquante` sur `/money`/`pay` ; `/money admin ...` par un non-admin →
    `Permission manquante : rpgquest.admin`.
-   **Données/persistance à contrôler :** `wallets` et `transactions`
    (SQLite) reflètent chaque mouvement (type, montant, contexte).
-   **Après redémarrage serveur :** les soldes survivent au redémarrage.
-   **Couverture automatisée :** `WalletRepositoryTest` (dont double-débit
    concurrent).

### TC-121 — Vitrine marchande via dialogue

-   **Fonctionnalité testée :** `MerchantCommand`, `MerchantTradeService`,
    action de dialogue `OPEN_MERCHANT` (voir TC-021), offres conditionnelles
    de `village_merchant.yml`.
-   **Préconditions :** solde suffisant (≥ 3 pour le pain), entité `merchant`
    en jeu (TC-021).
-   **Commandes :** `/merchant list`, `/merchant reload`, `/merchant
    validate`.
-   **Actions en jeu :** ouvrir la vitrine (dialogue `merchant` → « Voir la
    boutique ») ; cliquer sur l'offre `BREAD ×4 pour 3` ; cliquer sur
    l'offre `forest_blade` **avant** d'avoir terminé `first_steps`, puis
    **après** ; vendre 4× `rpgquest:spider_fang` (offre `BUY_FROM_PLAYER`).
-   **Résultat attendu :** achat de pain → débit de 3, réception de 4 pains
    (débit tenté avant remise ; si fonds insuffisants, rien n'est donné).
    Offre `forest_blade` avant le prérequis → message expliquant la
    condition non remplie, aucun échange. Après `first_steps` `COMPLETED`
    → achat possible (250 pièces). Vente de `spider_fang` → objets retirés
    de l'inventaire **avant** que le crédit (asynchrone) ne parte (empêche
    un double-clic de vendre deux fois le même stock).
-   **Résultat console :** `/merchant reload`/`validate` rapportent `N
    marchand(s) chargé(s), 0 erreur(s)`.
-   **Cas sans permission :** `/merchant *` par un non-admin →
    `Permission manquante : rpgquest.admin` (aucune sous-commande joueur
    n'existe, cohérent avec la mission).
-   **Couverture automatisée :** `MerchantDefinitionParserTest`,
    `MerchantLoaderTest`, `MerchantTradeServiceTest`,
    `DialogueDefinitionParserTest`/`DialogueSessionEngineTest` (parsing et
    ouverture d'`OPEN_MERCHANT`).

---

## 10. Marché entre joueurs (`/market`) — étape 15

### TC-130 — Vente, achat, annulation

-   **Fonctionnalité testée :** `MarketCommand`, `MarketService`.
-   **Préconditions :** deux joueurs (A vend, B achète), A tient un objet
    en main.
-   **Commandes :** `/market`, `/market sell 50`, `/market cancel <id>`,
    `/market admin list`.
-   **Actions en jeu :** A exécute `/market sell 50` (met en vente la pile
    entière en main) ; B ouvre `/market`, clique sur l'offre de A ; A remet
    en vente un autre objet puis clique sur **sa propre** offre dans la
    vitrine (annulation).
-   **Résultat attendu :** `/market sell 50` retire l'objet de la main de A
    et l'affiche dans la vitrine partagée, triée par ancienneté, paginée.
    Clic de B sur l'offre → achat immédiat au prix fixe, B reçoit l'objet
    (sérialisé tel quel, PDC compris), A est crédité de 50 **même si A est
    hors ligne au moment de l'achat**. Clic de A sur sa propre offre →
    annulation, objet restitué à A. `/market cancel <id>` fait de même en
    commande.
-   **Cas invalide :** `/market sell 0` ou négatif → valeur invalide.
    `/market sell 50` la main vide → comportement à vérifier (aucun objet à
    vendre). `/market cancel <id_dune_autre_personne>` → refusé (annulation
    réservée au vendeur). B tente d'acheter une offre déjà vendue
    (simultanéité) → réservation atomique, un seul acheteur gagne ; voir
    TC-131 pour le test de concurrence réelle.
-   **Cas sans permission :** `rpgquest.market` retiré → refus ; `/market
    admin list` par un non-admin → `Permission manquante : rpgquest.admin`.
-   **Données/persistance à contrôler :** `market_listings` (SQLite) :
    offre `ACTIVE` → `SOLD` à l'achat, supprimée/retirée à l'annulation.
-   **Après redémarrage serveur :** une offre `ACTIVE` non vendue survit au
    redémarrage et reste achetable.
-   **Couverture automatisée :** `MarketRepositoryTest` (réservation
    atomique, réactivation après débit refusé, annulation par tiers
    refusée), `MarketServiceTest`.

### TC-131 — Achat concurrent réel (PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** anti-duplication en deux temps
    (`MarketRepository#claim`/`reactivate`).
-   **Préconditions :** deux joueurs B et C, une seule offre active de A.
-   **Actions en jeu :** B et C cliquent sur la **même** offre le plus
    simultanément possible (deux clients réels, deux joueurs humains).
-   **Résultat attendu :** un seul des deux reçoit l'objet et débite son
    compte ; l'autre voit l'offre disparaître sans effet (ou un message
    d'échec), aucun débit ni duplication d'objet.
-   **Note :** difficile à garantir en test manuel strict (dépend du
    timing réseau) — la garantie **automatisée** de non-duplication est
    dans `MarketRepositoryTest` (réservation atomique testée directement en
    JUnit, sans dépendre du timing réseau réel). Ce test manuel sert de
    confirmation qualitative, pas de preuve d'absence de race condition.

---

## 11. Portails et téléportation (`/rpgadmin portal`) — étape 16

### TC-090 — Création, destination, canalisation, sécurité

-   **Fonctionnalité testée :** `RpgAdminCommand` (`portal`), `PortalService`,
    `PortalListener`.
-   **Préconditions :** `rpgquest.admin.world`.
-   **Commandes :** `/rpgadmin zone wand` (réutilisé pour la sélection),
    `/rpgadmin portal create <id>`, `/rpgadmin portal setdestination <id>
    <destinationId>`, `/rpgadmin portal list`, `/rpgadmin portal info
    <id>`, `/rpgadmin portal delete <id>`.
-   **Actions en jeu :** sélectionner un cuboïde (wand), `/rpgadmin portal
    create test_portal` (canalisation 3 s, cooldown 5 s par défaut) ; se
    déplacer à l'endroit voulu comme destination, `/rpgadmin portal
    setdestination test_portal test_dest` ; entrer dans la zone du portail.
-   **Résultat attendu :** `create` avant `setdestination` → entrer dans la
    zone affiche un message, **aucune** canalisation ne démarre (portail
    sans destination). Après `setdestination` → entrer démarre une
    canalisation de 3 s avec actionbar de progression ; à la fin,
    téléportation vers `test_dest` (position exacte capturée par
    `setdestination`) **uniquement si** une position sûre y est trouvée
    (aucun bloc solide aux pieds/tête, sol solide, aucun bloc dangereux
    dans un rayon de balayage de 5 blocs) — sinon message d'erreur, aucune
    téléportation.
-   **Annulation de la canalisation :** bouger de plus de ~0,6 bloc,
    subir des dégâts, ou se déconnecter pendant la canalisation → annulée
    dans les trois cas, aucune téléportation.
-   **Cas invalide :** `create` sans sélection → message d'erreur. `create`
    avec id déjà pris → `Un portail porte déjà l'id`. Zone d'activation
    chevauchant un portail existant → `OVERLAPS`. `setdestination` sur un
    id de portail inconnu → `Portail inconnu`.
-   **Cas sans permission :** non-admin → `Permission manquante :
    rpgquest.admin.world`.
-   **Après reconnexion :** entrer dans la zone, cooldown déclenché,
    déconnexion, reconnexion avant la fin du cooldown (5 s par défaut) →
    le portail reste en cooldown (persisté en SQLite, pas remis à zéro).
-   **Après redémarrage serveur :** même vérification que ci-dessus après
    un redémarrage complet.
-   **Couverture automatisée :** `DestinationTest`, `PortalDefinitionTest`,
    `DestinationDefinitionParserTest`, `PortalDefinitionParserTest`,
    `DestinationLoaderTest`, `PortalLoaderTest`, `YamlDestinationRegistryTest`,
    `YamlPortalRegistryTest`, `PortalServiceTest`.

### TC-091 — Conditions et coût d'un portail

-   **Fonctionnalité testée :** `required-permission`/`required-quest`/
    `required-level`/`cost` (édition manuelle du fichier YAML du portail,
    `plugins/RPGQuest/portals/<id>.yml`).
-   **Préconditions :** éditer `test_portal.yml` pour ajouter
    `required-level: 5` et `cost: 10`, puis redémarrer (ou recréer le
    portail — pas de rechargement à chaud d'un fichier édité à la main).
-   **Actions en jeu :** entrer dans la zone avec un niveau d'expérience
    vanilla < 5, puis ≥ 5 mais solde < 10, puis solde ≥ 10.
-   **Résultat attendu :** niveau insuffisant → message, aucune
    canalisation. Niveau suffisant mais fonds insuffisants → message,
    aucune canalisation, **aucun débit**. Les deux conditions remplies →
    canalisation puis téléportation, débit de 10 **seulement après**
    résolution réussie de la destination (pas avant).
-   **Couverture automatisée :** `PortalServiceTest` (permission, niveau,
    quête, coût — fonds insuffisants/suffisants, débit uniquement au
    succès).

---

## 12. Claims de terrain (`/claim`) — étape 17

### TC-100 — Création et refus

-   **Fonctionnalité testée :** `ClaimCommand`, `ClaimService`.
-   **Préconditions :** joueur A, `rpgquest.claim` (défaut `true`).
-   **Commandes :** `/claim wand`, `/claim create <id>`, `/claim list`,
    `/claim info`, `/claim delete`.
-   **Actions en jeu :** `/claim wand`, sélectionner un cuboïde éloigné de
    `central_village` et de tout portail, `/claim create test_claim`.
-   **Résultat attendu :** `Claim créé : test_claim`. `/claim info` (en
    étant dans le claim) → propriétaire, monde, bornes, membres (0),
    redstone publique (`non`).
-   **Cas de refus (chacun à tester séparément) :**
    -   Sélection chevauchant un claim existant → `chevauche un claim
        existant`.
    -   Sélection chevauchant `central_village` → `chevauche une zone
        protégée`.
    -   Sélection à moins de 16 blocs (`portal-buffer-blocks`) d'un portail
        → `trop proche d'un portail`.
    -   Sélection > 64×384 (`max-width`/`max-height`) → dépassement de
        taille.
    -   4ᵉ claim du même joueur sans bonus de niveau (`max-claims-per-
        player: 3`) → `nombre maximal de claims`.
-   **Cas sans permission :** `rpgquest.claim` retiré → refus de toutes les
    sous-commandes.
-   **Après redémarrage serveur :** `/claim list` affiche toujours
    `test_claim` après redémarrage (SQLite).
-   **Couverture automatisée :** `ClaimTest`, `ClaimRepositoryTest`,
    `ClaimServiceTest`, `ConfigValidatorTest` (section `claims`).

### TC-101 — Confiance, flags, protections, bypass

-   **Fonctionnalité testée :** `/claim trust|untrust|flag redstone`,
    `ClaimProtectionListener`.
-   **Préconditions :** `test_claim` (A, propriétaire), joueur B en ligne.
-   **Commandes :** `/claim trust B`, `/claim untrust B`, `/claim flag
    redstone true`.
-   **Actions en jeu (dans `test_claim`) :** B tente de casser un bloc /
    ouvrir un coffre / attaquer un animal / manipuler un armor stand avant
    `trust`, puis après. B actionne un levier avant `flag redstone true`,
    puis après. Faire exploser un creeper dans le claim. Un piston du
    claim pousse un bloc vers l'extérieur.
-   **Résultat attendu :** avant `trust`, tout est bloqué pour B (blocs,
    conteneurs, animaux, armor stands — toujours bloqué, non configurable).
    Après `trust B`, B peut casser/poser/ouvrir des conteneurs. Redstone
    (boutons/leviers/portes/dalles) : bloqué pour B tant que `flag
    redstone` reste `false` (défaut), même sans confiance ; `true` l'ouvre
    à tous les non-membres. Explosion (creeper/TNT) → destruction de bloc
    toujours empêchée (l'entité se consume normalement). Piston traversant
    la frontière → toujours bloqué, configurable ou non.
-   **Cas bypass admin :** `rpgquest.admin.world` exempte l'acteur direct
    (celui qui casse/pose), jamais la victime.
-   **Cas invalide :** `/claim trust B` hors de tout claim → `Tu ne te
    trouves dans aucun claim`. `/claim flag redstone maybe` → `Valeur
    invalide (true/false attendu)`.
-   **Couverture automatisée :** `ClaimProtectionListenerTest` (frontière
    incluse, membre autorisé/non autorisé, conteneurs, redstone
    configurable, animaux, explosion externe, piston traversant la
    frontière).

### TC-102 — Limite de claims liée au niveau RPG

-   **Fonctionnalité testée :** `ClaimService#effectiveMaxClaims` +
    `ProgressionService#hasLevel` (+1 claim tous les 10 niveaux `GLOBAL`).
-   **Préconditions :** joueur au niveau `GLOBAL` ≥ 10 (voir TC-141 pour
    monter le niveau via `/skills admin grant`).
-   **Actions en jeu :** créer 3 claims (limite de base), tenter un 4ᵉ.
-   **Résultat attendu :** avant niveau 10 `GLOBAL` → refusé au 4ᵉ. Après
    avoir atteint le niveau 10 `GLOBAL` → un 4ᵉ claim devient possible
    (limite = 3 + 1).
-   **Couverture automatisée :** `ClaimServiceTest` (seam
    `effectiveMaxClaims`).

---

## 13. Mobs spéciaux (`/rpgadmin mob`) — étape 18

### TC-110 — Invocation et inspection des 4 variantes

-   **Fonctionnalité testée :** `RpgAdminCommand` (`mob`),
    `SpecialMobService`.
-   **Préconditions :** `red_creeper`, `golden_creeper`, `creeper_pig`,
    `splitting_zombie` chargés (exemples générés).
-   **Commandes :** `/rpgadmin mob spawn <id>`, `/rpgadmin mob list`,
    `/rpgadmin mob inspect <id>`, `/rpgadmin mob reload`, `/rpgadmin mob
    metrics`.
-   **Actions en jeu :** `/rpgadmin mob spawn red_creeper` (idem pour les 3
    autres) à sa position.
-   **Résultat attendu :** chaque variante apparaît avec son nom
    personnalisé coloré, ses attributs (vie/dégâts/vitesse/armure) et sa
    particule/son au spawn. `/rpgadmin mob list` affiche les 4 avec leur
    population courante. `/rpgadmin mob inspect <id>` détaille tout
    (chance de spawn, mondes/biomes/zones autorisés, capacités, drops, XP,
    population/max). `/rpgadmin mob metrics` incrémente le compteur de
    spawn de la variante invoquée.
-   **Résultat attendu (identification) :** renommer manuellement le mob
    invoqué (enclume) → toujours reconnu comme variante spéciale par
    `/rpgadmin mob inspect`-équivalent en jeu (PDC, pas le nom affiché).
-   **Cas invalide :** `/rpgadmin mob spawn id_inconnu` → `Mob spécial
    inconnu`.
-   **Cas sans permission :** non-admin → `Permission manquante :
    rpgquest.admin.world`.
-   **Couverture automatisée :** `SpecialMobDefinitionParserTest`,
    `SpecialMobLoaderTest`, `SpecialMobServiceTest` (2 tests ignorés,
    limitation MockBukkit `setRemoveWhenFarAway`, non un échec).

### TC-111 — Capacités spéciales

-   **Fonctionnalité testée :** `ExplosiveOnAttackAbilityService`,
    `SplitOnHitAbilityListener`, `StrongerExplosionAbilityListener`.
-   **Actions en jeu :**
    -   `golden_creeper` (`STRONGER_EXPLOSION`, radius-multiplier 1.5) :
        le faire exploser (approche + détonation) → rayon de destruction
        visiblement plus large qu'un creeper vanilla.
    -   `creeper_pig` (`EXPLOSIVE_ON_ATTACK`) : s'approcher à portée de
        déclenchement → après le balayage périodique (1 s), explosion
        réelle (`World#createExplosion`, respecte zones/claims), puis le
        mob meurt.
    -   `splitting_zombie` (`SPLIT_ON_HIT`, `max-depth`/`max-children-per-
        hit`) : le frapper sans le tuer → apparition d'enfants ; répéter
        jusqu'à la profondeur maximale → plus de division au-delà,
        `max-population` respectée globalement.
-   **Résultat attendu :** dans une safe zone/claim avec `explosions:
    false`, aucune destruction de bloc pour `golden_creeper`/`creeper_pig`
    (la protection s'applique comme à toute explosion).
-   **Résultat console :** `/rpgadmin mob metrics` incrémente le compteur
    de la capacité déclenchée (`STRONGER_EXPLOSION`,
    `EXPLOSIVE_ON_ATTACK`, `SPLIT_ON_HIT`).
-   **Cas limite :** décharger puis recharger le chunk contenant un mob
    spécial → population toujours comptée correctement (pas de double
    comptage, pas de despawn par éloignement —
    `setRemoveWhenFarAway(false)`).
-   **Couverture automatisée :** `SplitOnHitAbilityListenerTest`.

---

## 14. Progression RPG (`/profile`, `/skills`) — étape 19

### TC-140 — Résumé et détail de progression

-   **Fonctionnalité testée :** `ProfileCommand`, `SkillsCommand`,
    `ProgressionService`.
-   **Commandes :** `/profile`, `/skills`.
-   **Résultat attendu :** `/profile` → une ligne par piste (`Global,
    Combat, Minage, Agriculture, Pêche, Exploration`) avec le niveau.
    `/skills` → détail avec XP dans le niveau courant / XP requise pour le
    suivant, ou `(niveau maximal)` au niveau 100.
-   **Cas sans permission :** `rpgquest.progression` retiré → refus.
-   **Couverture automatisée :** `ProgressionServiceTest`,
    `ProgressionCurveTest`.

### TC-141 — Gain d'XP par source et anti-farm

-   **Fonctionnalité testée :** `CombatXpListener`, `MiningXpListener`,
    `FarmingXpListener`, `FishingXpListener`, `ExplorationXpListener`,
    `QuestCompletionXpListener`.
-   **Actions en jeu :**
    -   Combat : tuer un mob hostile naturel → +15 XP `COMBAT` (+ mirroir
        50 % sur `GLOBAL`). Tuer un mob issu d'un `CreatureSpawnEvent.
        SpawnReason.SPAWNER` → **aucune** XP combat. Tuer un enfant généré
        par `SPLIT_ON_HIT` → **aucune** XP combat.
    -   Minage : casser un minerai naturel → +5 XP `MINING`. Poser puis
        recasser le même bloc → **aucune** XP (bloc posé par un joueur,
        suivi en mémoire/persisté).
    -   Agriculture : récolter une culture **mûre** → +4 XP `FARMING`.
        Récolter une culture **non mûre** (replantée trop tôt) → aucune
        XP.
    -   Pêche : attraper un poisson → +10 XP `FISHING`.
    -   Exploration : entrer dans une zone nommée pour la première fois →
        +100 XP `EXPLORATION`, **une seule fois** (revisiter la même zone
        ne redonne rien).
    -   Quête : terminer une quête → +50 XP `GLOBAL` en plus de la
        récompense `EXPERIENCE` vanilla éventuelle de la quête.
-   **Anti-farm (répétition) :** déclencher > 60 gains/minute sur une même
    compétence (ex. casser rapidement de nombreux blocs identiques) → les
    octrois au-delà de 60 sont silencieusement ignorés jusqu'à la minute
    suivante.
-   **Résultat attendu :** `/skills` reflète chaque gain immédiatement
    (affichage `action_bar` par défaut, ou `boss_bar` selon `progression.
    display-mode`) ; en mode `boss_bar`, la barre disparaît 3 s après le
    dernier gain.
-   **Après redémarrage serveur :** couper puis redémarrer avec de l'XP en
    cours ; `total_xp` et l'historique de déduplication (`xp_grants`)
    survivent — une récompense « une fois » déjà accordée (ex. exploration
    d'une zone) ne se redéclenche jamais après redémarrage.
-   **Couverture automatisée :** `CombatXpListenerTest`,
    `MiningXpListenerTest`, `ProgressionServiceTest`.

### TC-142 — Commandes admin de progression

-   **Fonctionnalité testée :** `/skills admin grant|set <joueur>
    <compétence> <montant>`.
-   **Actions en jeu :** `/skills admin grant <joueur> COMBAT 1000`,
    `/skills admin set <joueur> COMBAT 0`.
-   **Résultat attendu :** `grant` passe par le pipeline normal (dédup +
    mirroir `GLOBAL` + affichage) — vérifier que `GLOBAL` gagne aussi ~500
    XP (50 % par défaut). `set` fixe l'XP totale directement, **sans**
    dédup ni mirroir (utile pour repositionner un joueur avant un test).
-   **Cas invalide :** compétence inconnue (`/skills admin grant <joueur>
    BIDULE 10`) → `Compétence invalide`. Montant négatif → `Montant
    invalide`.
-   **Cas sans permission :** non-admin → `Permission manquante :
    rpgquest.admin`.
-   **Couverture automatisée :** `ProgressionServiceTest`.

---

## 15. Backpacks (`/backpack`) — étape 20

### TC-150 — Accès, ouverture, anti-abus

-   **Fonctionnalité testée :** `BackpackCommand`, `BackpackService`,
    `BackpackListener`, `EntitlementService`.
-   **Préconditions :** joueur avec/sans avantage explicite.
-   **Commandes :** `/backpack`, `/backpack admin grant <joueur>
    <taille>`, `/backpack admin revoke <joueur>`.
-   **Actions en jeu :** joueur neuf (sans avantage) → `/backpack`. Puis
    `/backpack admin grant <joueur> MEDIUM`. Tenter de placer l'objet
    d'ouverture (`BUNDLE` marqué PDC) **dans** le backpack lui-même.
    Ouvrir le backpack deux fois rapidement (double `/backpack`).
-   **Résultat attendu :** sans avantage mais avec `rpgquest.backpack.free`
    (défaut `true`) → accès au palier `SMALL` (`fallback-size`). Sans
    aucun accès (retirer aussi `rpgquest.backpack.free`) → `Tu n'as accès
    à aucun backpack pour l'instant`. Après `admin grant MEDIUM` → 27 cases
    (3 lignes). L'objet d'ouverture est **refusé** à l'entrée du backpack
    quel que soit son matériau configuré (anti-imbrication). Une seconde
    ouverture simultanée réutilise la même instance (jamais deux copies
    chargées).
-   **Cas sans permission :** `rpgquest.backpack` retiré → refus de
    `/backpack`/`recover` ; `admin grant|revoke` par un non-admin →
    `Permission manquante : rpgquest.admin`.
-   **Couverture automatisée :** `BackpackServiceTest`,
    `BackpackListenerTest`, `ItemArraySerializerTest`.

### TC-151 — Sauvegarde, upgrade/downgrade, récupération

-   **Fonctionnalité testée :** sauvegarde à la fermeture/déconnexion/arrêt,
    `/backpack recover [numéro]`.
-   **Actions en jeu :** remplir le backpack `SMALL` (9 cases) avec des
    objets, fermer le GUI → réouvrir, vérifier le contenu. Remplir
    entièrement, puis `/backpack admin grant <joueur> SMALL` (rétrogradation
    depuis un palier supérieur si déjà upgradé) avec plus d'objets que 9
    cases ne peuvent contenir. Se déconnecter avec le backpack ouvert. Tuer
    le joueur avec le backpack ouvert (mort). Arrêter le serveur avec un
    backpack ouvert.
-   **Résultat attendu :** contenu identique après fermeture/réouverture.
    **Upgrade** (ex. `SMALL` → `MEDIUM`) : rien ne déborde. **Downgrade** :
    le contenu est compacté dans les cases restantes, le surplus part dans
    la boîte de récupération (`/backpack recover` liste les entrées avec
    raison/date ; `/backpack recover <numéro>` les rend, dépose au sol si
    l'inventaire est plein — jamais supprimé). Déconnexion/mort avec GUI
    ouvert → sauvegarde déclenchée (`PlayerQuitEvent`, filet de sécurité).
    Arrêt du plugin avec backpack ouvert → fermeture forcée et sauvegarde
    synchrone avant l'arrêt réel de la base.
-   **Cas invalide :** `/backpack recover 999` (numéro hors liste) →
    `Numéro invalide`. Réclamer deux fois la même entrée → `Cette entrée a
    déjà été réclamée`.
-   **Données/persistance à contrôler :** `backpacks`, `backpack_overflow`,
    `backpack_audit` (SQLite, migration V9).
-   **Après redémarrage serveur :** contenu du backpack identique après
    redémarrage (comparer avant/après un remplissage suivi d'un arrêt
    propre `stop`).
-   **Couverture automatisée :** `BackpackServiceTest` (upgrade/downgrade,
    récupération, sauvegarde).

---

## 16. Portail web (API + site) — étape 21

### TC-160 — Export du snapshot côté plugin

-   **Fonctionnalité testée :** `WebSnapshotWriter`, `config.yml` →
    `web-export`.
-   **Préconditions :** activer `web-export.enabled: true` dans
    `config.yml`, `/rpgquest reload` (ou redémarrer).
-   **Actions en jeu :** attendre `interval-seconds` (30 s par défaut),
    inspecter `run/plugins/RPGQuest/web-export/snapshot.json`.
-   **Résultat attendu :** fichier régénéré toutes les 30 s (écriture
    atomique, temp + renommage), contenant `generatedAt`, `server`
    (`online`, `playerCount`, `maxPlayers`), `players` (liste nominative
    **seulement** si `include-connected-players: true`), `leaderboards`,
    `catalog` (objets personnalisés publics), `announcements`.
-   **Couverture automatisée :** `WebSnapshotWriterTest`,
    `ProgressionRepositoryTest#topPlayers*`, `ConfigValidatorTest` (section
    `web-export`).

### TC-161 — API authentifiée et site public

-   **Fonctionnalité testée :** module `web-api` (`HttpServerBootstrap`),
    endpoints `/api/*`, site public.
-   **Préconditions :** `gradlew.bat :web-api:build` ; définir
    `RPGQUEST_WEB_API_TOKEN` (variable d'environnement, jamais en
    fichier) ; `java -jar web-api\build\libs\web-api.jar` (lit
    `web-api.properties` s'il existe, sinon valeurs par défaut : voir
    `web-api/web-api.properties.example`).
-   **Commandes (HTTP, via navigateur ou `curl`) :**
    -   `curl -H "Authorization: Bearer <token>" http://localhost:<port>/api/status`
    -   `curl http://localhost:<port>/api/status` (sans en-tête)
    -   `curl -H "Authorization: Bearer mauvais_token" http://localhost:<port>/api/players`
    -   `curl "http://localhost:<port>/api/leaderboards?skill=COMBAT&limit=5"`
    -   `curl -H "Authorization: Bearer <token>" http://localhost:<port>/api/route_inconnue`
    -   Navigateur : `http://localhost:<port>/`, `/status`, `/leaderboards`,
        `/wiki`.
-   **Résultat attendu :** jeton correct → `200` avec les données attendues
    par route (voir tableau `docs/WEB_API.md`). Jeton absent/invalide →
    `401`. Route `/api/...` inconnue → `404` (après vérification du
    jeton). Paramètre malformé (`skill` inconnu) → `400`. Site public
    (`/`, `/status`, `/leaderboards`, `/wiki`) accessible **sans**
    authentification, données échappées HTML.
-   **Résultat console :** le journal d'accès (`AccessLogger`) enregistre
    méthode/chemin/IP/statut/durée, **jamais** l'en-tête `Authorization` ni
    le jeton en clair.
-   **Cas limite (rate limit) :** dépasser `rate-limit-per-minute` requêtes
    en une minute sur une même IP → `429` au-delà du seuil, sur `/api/*`
    comme sur le site public.
-   **Mode dégradé :** arrêter le serveur Minecraft (ou renommer
    `snapshot.json`) → l'API et le site continuent de répondre `200` avec
    `online: false` et un bandeau « Serveur hors-ligne », jamais
    d'erreur `500`, y compris avec un JSON corrompu.
-   **Après redémarrage serveur (web-api) :** redémarrer le processus
    `web-api` → le rate limiting (en mémoire) est réinitialisé ; le
    snapshot est relu depuis le disque sans perte.
-   **Couverture automatisée :** `JsonTest`, `HttpServerBootstrapTest`
    (bout-en-bout : snapshot absent/périmé, jeton manquant/invalide,
    requête malformée, rate limit, route inconnue, Unicode, page publique).

---

## 17. Boutique web (`/store`) — étape 22

### TC-170 — Achat sandbox de bout en bout

-   **Fonctionnalité testée :** `SandboxPaymentProvider`, `StoreService`,
    `StoreDeliveryService`, `/store history`.
-   **Préconditions :** `RPGQUEST_WEB_API_TOKEN` et
    `RPGQUEST_STORE_WEBHOOK_SECRET` définis, `web-api` démarré,
    `config.yml` → `store.enabled: true`, même token côté plugin.
-   **Actions en jeu :** ouvrir `/store` (site), choisir `small_backpack`,
    saisir un UUID Minecraft valide (joueur de test), suivre le lien
    `/store/pay/{id}`, cliquer « Payer (sandbox) ».
-   **Résultat attendu :** la commande passe `PENDING` → `PAID` (webhook
    HMAC-SHA256 signé vers `/store/webhook`). Au sondage suivant
    (`poll-interval-seconds`, 30 s par défaut), le plugin acquitte la
    livraison (`GET /api/store/deliveries/pending` →
    `POST .../{id}/ack`) et applique l'avantage (backpack `SMALL` accordé
    via `EntitlementService`, vérifiable avec `/backpack`).
-   **Cas échec simulé :** cliquer « Simuler un échec » au lieu de
    « Payer » → commande `FAILED`, aucune livraison créée.
-   **Résultat console :** aucun jeton, signature, ni donnée de paiement
    dans les logs d'accès ou d'erreur.
-   **Cas offline/UUID inconnu :** saisir un UUID qui ne correspond à
    aucun profil existant → `PlayerProfileRepository#findOrCreate` crée le
    profil, l'octroi a lieu quand même, le pseudo se corrige à la
    prochaine vraie connexion.
-   **Cas déjà possédé / upgrade :** acheter `small_backpack` deux fois
    pour le même joueur → second octroi ignoré (déjà possédé, acquitté
    comme un succès sans changement). Acheter `upgrade_medium` après
    `small_backpack` → upgrade réel (backpack redimensionné) ; acheter
    `upgrade_large` puis re-tenter `upgrade_medium` → ignoré (palier
    inférieur).
-   **Après redémarrage serveur (plugin) :** arrêter le plugin juste après
    un paiement `PAID` (avant acquittement), redémarrer → la livraison
    `PENDING` est reprise au sondage suivant, appliquée normalement (aucune
    perte, aucun double octroi).
-   **Couverture automatisée :** `StoreDeliveryServiceTest`,
    `SchemaMigratorTest` (migration V10), `StoreHttpTest` (achat, webhook
    rejoué/signature invalide, livraison répétée, remboursement,
    historique, reprise après redémarrage).

### TC-171 — Webhook rejoué et signature invalide

-   **Fonctionnalité testée :** déduplication `webhook_events`,
    authentification HMAC.
-   **Actions en jeu :** rejouer manuellement (`curl -X POST
    http://localhost:<port>/store/webhook ...`) le même événement
    (même id) une seconde fois ; envoyer un webhook avec une signature
    incorrecte.
-   **Résultat attendu :** rejeu du même id d'événement → no-op silencieux
    (pas de double traitement, pas d'erreur). Signature invalide → `401`,
    refusé avant même de lire le corps comme un événement valide.
-   **Couverture automatisée :** `StoreHttpTest`.

### TC-172 — Historique admin et remboursement

-   **Commandes :** `/store history`, `/store history <joueur|uuid>`.
-   **Actions en jeu (remboursement, via `curl`, pas d'interface web) :**
    `curl -X POST -H "Authorization: Bearer <token>"
    http://localhost:<port>/api/store/orders/{id}/refund`.
-   **Résultat attendu :** `/store history` liste produit/joueur/statut
    (coloré : `PAID` vert, `REFUNDED` aqua, `FAILED` rouge)/date, triés,
    limités à 20 par défaut. Après remboursement, la commande passe
    `REFUNDED` et une livraison `REVOKE` est mise en file → au sondage
    suivant, l'avantage est retiré (pour un backpack, retombe sur
    `fallback-size`, jamais une permission par-joueur recalculée hors
    ligne).
-   **Cas invalide :** `/store history joueur_ou_uuid_inconnu` → `Joueur ou
    UUID introuvable`.
-   **Cas sans permission :** non-admin → `Permission manquante :
    rpgquest.admin`.
-   **Résultat console (côté web-api si `web-api` injoignable) :** `/store
    history` répond `Impossible de contacter web-api (voir la console)`,
    l'erreur détaillée est loguée côté plugin.
-   **Couverture automatisée :** `StoreHttpTest` (remboursement, historique).

---

## 18. Mod client prototype — étape 23

### TC-180 — Compilation et installation

-   **Fonctionnalité testée :** projet Gradle `client-mod/` (Fabric),
    séparation stricte du build racine.
-   **Actions :** depuis la racine, `gradlew.bat clean build` → vérifier
    que **rien** sous `client-mod/` n'est compilé (aucune tâche
    `client-mod:*` dans la sortie). Séparément : `cd client-mod &&
    gradlew.bat build` → `BUILD SUCCESSFUL`, récupérer
    `client-mod/build/libs/rpgquest-client-mod-<version>.jar` (le jar
    remappé, **pas** celui suffixé `-dev`).
-   **Résultat attendu :** le jar principal du plugin (`build/libs/
    RPGQuest-<version>.jar`) ne contient à aucun moment le mod. Placer le
    jar du mod dans `mods/` d'une installation client Fabric Loader
    `0.19.3`+ avec Fabric API `0.141.6+1.21.11` pour Minecraft `1.21.11`.
-   **Couverture automatisée :** aucun test JUnit possible côté mod (build
    Fabric Loom réel validé en session, pas un test automatisé au sens
    strict) — voir `docs/CLIENT_MOD.md`.

### TC-181 — Handshake de compatibilité (client moddé)

-   **Fonctionnalité testée :** `ModCompatService`, `HandshakeProtocol`
    (`rpgquest:handshake_hello`), `ModHud`.
-   **Préconditions :** `config.yml` → `client-mod.require-mod: false`
    (défaut), mod installé côté client (TC-180).
-   **Actions en jeu :** se connecter avec le client moddé.
-   **Résultat attendu :** à la connexion, échange des 5 octets
    (`magic=0x52504751` + version de protocole) dans les deux sens ; le
    `ModHud` (coin supérieur gauche) affiche « RPGQuest : connecté »
    (`COMPATIBLE`) ; aucune exclusion.
-   **Résultat console :** aucune exception réseau ; la classification
    `COMPATIBLE` est atteignable en observant l'état exposé côté plugin
    (log `debug` si activé).
-   **Après reconnexion :** se déconnecter et se reconnecter avec le même
    client → un **nouveau** handshake complet a lieu à chaque connexion
    (aucun état hérité d'une connexion précédente).
-   **Après redémarrage serveur :** redémarrer le serveur, reconnecter le
    même client → même vérification.
-   **Couverture automatisée :** `HandshakeProtocolTest` (encodage/
    décodage pur, Unicode), `ModCompatServiceTest` (client compatible,
    reconnexion, tentative de falsification).

### TC-182 — Client vanilla et mauvaise version

-   **Fonctionnalité testée :** classification `NO_MOD`/`WRONG_VERSION`,
    politique `require-mod`.
-   **Actions en jeu :**
    -   Se connecter avec un client **vanilla** (sans le mod),
        `require-mod: false` → doit jouer normalement, sans contenu
        cosmétique, `ModHud` absent (le client n'a pas le mod).
    -   `require-mod: true`, se connecter en vanilla → le serveur doit
        **exclure** le joueur (`Player#kick`) avec un message explicite,
        après expiration de `handshake-timeout-ticks` (60 par défaut, 3 s).
    -   Modifier volontairement `CLIENT_PROTOCOL_VERSION` côté mod (dans
        le code du mod, recompiler) pour qu'il diffère de
        `SERVER_PROTOCOL_VERSION` → se connecter avec ce mod modifié →
        `WRONG_VERSION` ; avec `require-mod: false`, le joueur joue quand
        même (repli vanilla) ; avec `require-mod: true`, il est exclu.
    -   Envoyer un paquet réseau invalide/tronqué sur le canal handshake
        (nécessite un client modifié ou un outil de test réseau) → classé
        `NO_MOD`, jamais d'exception côté serveur.
-   **Résultat attendu :** conforme à la politique ci-dessus dans tous les
    cas ; le contenu du canal `rpgquest:mob_variant_tag` (message d'action
    bar « ⚡ Variante détectée : *nom* ») n'apparaît que chez un client
    `COMPATIBLE` recevant un mob spécial visible.
-   **Couverture automatisée :** `ModCompatServiceTest` (version
    incorrecte avec/sans obligation, client vanilla après délai, paquet
    réseau invalide, diffusion cosmétique conditionnelle).

### TC-183 — Contenu client (bloc/objet, limite assumée)

-   **Fonctionnalité testée :** `ModContent`
    (`rpgquest_client:crystal_display`).
-   **Actions en jeu :** en jeu créatif avec le mod installé, ouvrir
    l'onglet créatif, chercher `crystal_display` (bloc et objet associé).
-   **Résultat attendu :** bloc/objet présents, modèle et texture visibles
    côté client. **Limite assumée et attendue** : ce bloc/objet n'est
    **jamais** posé ni donné par le serveur Paper (aucune synchronisation
    d'identifiant de bloc/objet possible sans NMS) — vérifier qu'aucune
    commande serveur (`/customitem give`, drops, etc.) ne peut le
    distribuer, seul l'onglet créatif local y donne accès.
-   **Couverture automatisée :** aucune (contenu purement client, hors
    portée d'un test JUnit serveur) ; limite documentée dans
    `docs/CLIENT_MOD.md`.

### TC-190 — Diagnostic WorldPortal (`/rpgadmin worldportal here`/`debug`, logs `TP-TRACE`)

-   **Fonctionnalité testée :** `WorldPortalRegistry#portalsContaining`,
    `WorldPortalDebugService`, `WorldPortalDebugGeometry`,
    instrumentation `[TP-TRACE]` — voir `docs/TRAVEL.md` pour le détail
    complet (contexte : ces outils ont servi à diagnostiquer le bug de
    téléportation automatique `hub_to_claims`, depuis résolu — une zone mal
    sélectionnée, voir `docs/current_state.md` — mais restent des outils de
    diagnostic permanents, pas une instrumentation à retirer).
-   **Préconditions :** au moins un portail simple chargé (ex.
    `hub_to_wild`, voir `docs-site/worlds.html`).
-   **Actions en jeu :**
    -   Se tenir dans la zone d'activation d'un portail simple, taper
        `/rpgadmin worldportal here`.
    -   Se tenir hors de toute zone, taper `/rpgadmin worldportal here`.
    -   `/rpgadmin worldportal debug show <id>` sur un portail existant,
        puis un id inconnu.
    -   `/rpgadmin worldportal debug showall`, attendre ~1 s,
        `/rpgadmin worldportal debug hideall`.
    -   `/rpgadmin worldportal info <id>` — vérifier la présence des
        nouveaux champs (largeur/hauteur/profondeur, centre, répit
        d'arrivée).
    -   Reproduire (si possible) le scénario du bug signalé (connexion ou
        `/tp` vers le Hub, joueur immobile) et relever les lignes
        `[TP-TRACE]` du joueur concerné dans les logs serveur.
-   **Résultat attendu :** `here` liste bien le(s) portail(s) présent(s)
    (avec `inside=true`) ou le message « aucun portail » selon le cas ;
    `debug show`/`showall` fait apparaître des particules colorées le
    long du contour de la (des) zone(s) plus une étiquette flottante avec
    l'id, sans qu'aucun bloc du monde ne soit modifié ; `hideall` fait
    disparaître particules et étiquettes ; `debug show` sur un id inconnu
    répond « Portail simple inconnu » sans planter ; les logs
    `[TP-TRACE]` apparaissent au format documenté, uniquement sur les
    transitions réelles (jamais un déluge à chaque tick pour un joueur
    immobile).
-   **Couverture automatisée :** `WorldPortalRegistryTest` (dont les deux
    tests documentant l'absence de validation croisée entre fichiers),
    `WorldPortalDebugGeometryTest`, `WorldPortalDebugServiceTest`,
    `TpTraceLoggerTest`, `WorldPortalTeleportListenerTest` (répit
    d'arrivée et son expiration).

### TC-200 — Storyline : progression automatique de bout en bout

-   **Fonctionnalité testée :** `story.StoryService` (démarrage/avancement/
    fin automatiques), `story_progress.current_index`,
    `/rpgadmin story info|start|reset|resetwithquests` — voir
    `docs/storylines.md` pour le détail complet.
-   **Préconditions (fixture de test, jamais en production permanente)** :
    1.  Copier `docs/manual-tests/quests/test_break_block.yml`,
        `test_place_block.yml` et `test_collect_item.yml` dans
        `plugins/RPGQuest/quests/`.
    2.  Copier `docs/manual-tests/stories/story_test.yml` dans
        `plugins/RPGQuest/stories/`.
    3.  `/rpgquest reload` (ou redémarrer) pour charger les deux.
-   **Actions ADMIN puis JOUEUR (aucune commande joueur entre les étapes) :**
    1.  `/rpgadmin story info <joueur>` → `story_test : NOT_STARTED`.
    2.  `/rpgadmin story start <joueur> story_test`.
    3.  **[JOUEUR]** Casser 3 blocs de terre (objectif `test_break_block`,
        déjà actif automatiquement — vérifier via `/quest progress` ou
        l'ActionBar).
    4.  **[JOUEUR]** Sans taper aucune commande : poser 3 blocs de terre
        (objectif `test_place_block`) → doit être devenu actif tout seul.
    5.  **[JOUEUR]** Sans taper aucune commande : jeter puis ramasser 5
        bâtons au sol (objectif `test_collect_item`) → doit être devenu
        actif tout seul.
    6.  `/rpgadmin story info <joueur>` → `story_test : COMPLETED`.
-   **Résultat attendu :**
    -   Après l'étape 2, message chat « Nouvelle aventure :
        \[TEST\] Histoire de test » puis Title « Quête commencée » /
        « [TEST] Casser des blocs ».
    -   Après chaque quête terminée (étapes 3 à 5), message chat
        « Nouvel objectif : *titre de la quête suivante* » **avant** que
        le Title « Quête commencée » de cette même quête apparaisse — la
        quête suivante devient active sans qu'aucune commande ni
        interaction PNJ ne soit nécessaire entre deux étapes.
    -   Après l'étape 5 (dernière quête), message chat « Aventure
        terminée : [TEST] Histoire de test » au lieu d'un « Nouvel
        objectif ».
    -   À aucun moment une même quête ne démarre deux fois, ni une
        récompense (5 XP par quête) n'est distribuée deux fois.
-   **Test de reprise (redémarrage/reconnexion) :** interrompre le test au
    milieu (ex. juste après l'étape 4, `test_place_block` en cours),
    déconnecter le joueur, redémarrer le serveur, reconnecter → la quête en
    cours doit toujours apparaître active (`/quest progress`), et terminer
    la story normalement en jouant la suite.
-   **Test de reset ciblé (mission point 5) :**
    -   `/rpgadmin story resetwithquests <joueur> story_test` →
        `story_test` redevient `NOT_STARTED`, les 3 quêtes de test
        redeviennent `NOT_STARTED` (`/quest progress` ne les liste plus).
    -   Vérifier qu'une autre quête en cours du joueur (ex. `first_steps`
        si testée en parallèle) **n'est pas affectée**.
    -   `/rpgadmin story start <joueur> story_test` relance proprement
        depuis le début (les 3 quêtes de test étant `repeatable: true`,
        aucun blocage « quête déjà terminée »).
-   **Nettoyage après test :** retirer les 4 fichiers copiés en
    précondition de `plugins/RPGQuest/quests/`/`plugins/RPGQuest/stories/`,
    puis `/rpgquest reload` (jamais nécessaire de vider `data.db`).
-   **Couverture automatisée :** `StoryServiceTest` (démarrage, première
    quête auto-acceptée, avancement automatique, chaîne complète jusqu'à
    `COMPLETED`, absence de double avancement, reprise après déconnexion/
    redémarrage y compris quête déjà terminée hors ligne, `reset`,
    `resetWithQuests` avec preuve qu'une quête hors story n'est jamais
    touchée, deux Stories indépendantes pour le même joueur, quête utilisée
    hors de toute Story active), `StoryProgressRepositoryTest`,
    `SchemaMigratorTest` (migration V14 et sa préservation des données
    existantes).

---

## 20. Waypoints par instance de biome (`travel.waypoint.*`) — issue #124

### TC-210 — Waypoint : génération, découverte au bouton, protection, persistance (PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `waypoint.WaypointService`, `WaypointListener`,
    `WaypointProtectionListener`, `waypoint.render.WaypointModelV1`, migration V18
    (`waypoints`, `waypoint_discoveries`), migration V22 (`waypoints.display_name`, issues
    #133/#135), section config `travel.waypoint.*`. Détail : `docs/WAYPOINTS.md`.
-   **Préconditions :**
    1.  JAR RPGQuest de cette session déployé sur DEV, serveur redémarré.
    2.  Un monde d'exploration nommé comme `travel.wild-world` (défaut `wild`) accessible.
    3.  Compte de test **non-op** + un compte `rpgquest.admin.world` pour l'étape protection.
    4.  Au journal de démarrage : `Waypoints chargés : N.` (N=0 au tout premier démarrage).
-   **Actions JOUEUR (non-op), dans `wild` :**
    1.  Rejoindre `wild` et **marcher 1 à 2 minutes en ligne droite** dans un biome jamais
        visité (traverser une frontière de biome aide). Au journal serveur doit apparaître
        `Waypoint « wp_wild_<biome>_<rx>_<rz> » généré en wild (x,y,z) [biome …, modèle v1]`.
    2.  Repérer la structure : **barrière de pierre (cobblestone wall) surmontée d'un bloc
        d'or, avec un bouton en pierre sur un côté**, posée en surface à **quelques dizaines
        de blocs** — jamais sous les pieds du joueur, jamais dans une construction existante.
    3.  **Passer à 1-3 blocs du waypoint sans rien cliquer** → aucun message, rien n'est
        enregistré (la proximité ne découvre rien).
    4.  **Clic droit sur le bouton** → message « Waypoint découvert — <biome> » + son.
        Re-cliquer le bouton → aucun nouveau message (déjà découvert). Dans le menu de la
        borne (issue #149-suite), ce waypoint doit apparaître avec un **nom propre unique**
        (ex. « Rochebrune »), jamais seulement son biome — vérifier qu'un **second** waypoint
        du **même biome** (deux forêts éloignées, étape 11) reçoit bien un nom **différent**,
        jamais « Forêt »/« Forêt » en double.
    5.  **Clic droit sur le bloc d'or ou sur la barrière** (pas le bouton) → aucune découverte.
    6.  Essayer de **casser** le bloc d'or / la barrière / le bouton → **refusé** (le bloc ne
        casse pas). Poser un bloc à la place d'un bloc du waypoint → refusé.
    7.  (si possible) amorcer une **TNT / un creeper** à côté → les blocs du waypoint ne sont
        pas détruits ; les autres blocs autour explosent normalement.
    8.  (si possible) pousser un bloc avec un **piston** vers un bloc du waypoint → piston
        bloqué. Poser de la **lave/eau** juste à côté pour qu'elle coule vers le waypoint →
        le fluide ne recouvre pas les blocs du waypoint.
-   **Actions ADMIN (`rpgquest.admin.world`) :**
    9.  Casser un bloc du waypoint → **autorisé** (bypass maintenance).
-   **Persistance / concurrence :**
    10. Avec un **second joueur**, entrer en même temps que le premier dans un **autre** biome
        neuf → **un seul** waypoint est créé pour cette zone (pas de doublon), visible par les
        deux.
    11. Deux zones **séparées** du même type de biome (par ex. deux forêts à plus de
        `region-size` blocs, défaut 256) → **deux** waypoints distincts.
    12. **Redémarrer le serveur** → au journal `Waypoints chargés : <n>` avec le bon compte ;
        les waypoints sont au même endroit, les découvertes du joueur sont conservées, **aucun
        doublon** n'est généré en repassant dessus.
-   **Reset :** `travel.waypoint.enabled: false` + `/rpgquest reload` désactive tout sans
    toucher aux données. Retirer une structure = casser ses blocs en `rpgquest.admin.world`
    (ou restaurer un backup `world_wild/`). Les tables `waypoints`/`waypoint_discoveries`
    peuvent être vidées à froid si besoin d'un test « from scratch ».
-   **Couverture automatisée :** `WaypointIdentityResolverTest`, `WaypointGenerationPlannerTest`,
    `WaypointModelRegistryTest`, `WaypointServiceTest` (11 cas : génération unique, biome
    correct, jamais au pied du joueur, concurrence 2 joueurs → 1 waypoint, `insertIfAbsent`
    idempotent, 2 zones même biome → 2 waypoints, proximité sans découverte, découverte bouton
    uniquement, découvertes A/B indépendantes, reload sans doublon, changement de version de
    rendu sans changement d'identité, échec propre + retry borné), `WaypointProtectionListenerTest`
    (casse joueur refusée, bypass admin, explosion, piston, feu), `SchemaMigratorTest` (V18 + V22 +
    idempotence, dont le backfill de noms uniques sur des waypoints déjà existants du même biome),
    `WaypointNameCatalogTest` (dédoublonnage à l'import, attribution sans doublon y compris sous la
    même seed, nom de secours une fois la réserve épuisée), `ConfigValidatorTest` (défauts + bornes
    `travel.waypoint.*`).
-   **Limites MockBukkit (à couvrir uniquement en jeu) :** distribution réelle des biomes du
    monde `wild` et emplacement effectivement dans le bon biome ; physique réelle des fluides,
    pistons et blocs à gravité contre la structure ; suppression réelle du signal redstone du
    bouton au clic ; rendu visuel ; comportement du throttle sous déplacement réel.

---

## 21. Kit d'outils en bois demandé au Guide (issue #26, partie A)

### TC-220 — Demande explicite, tout ou rien, droit renouvelé à chaque mort (PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `player.StarterToolKitService`, action de dialogue
    `GIVE_STARTER_KIT` (`dialogues/guide.yml`), section config `starter-tool-kit`. Détail :
    [RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md) §4 « Kit d'outils en bois ».
-   **Préconditions :** JAR de cette session déployé, compte de test **non-op**, PNJ Citizens
    `guide` existant (voir `docs/NPC_DIALOGUES_QUESTS_GUIDE.md`).
-   **Actions :**
    1.  Parler au Guide **sans** cliquer sur « Demander mon kit de départ » → aucun outil reçu
        (pas de remise au simple clic/connexion/réapparition).
    2.  Choisir « Demander mon kit de départ » avec l'inventaire principal vide → message de
        réussite, exactement 1 `WOODEN_SWORD` + 1 `WOODEN_PICKAXE` + 1 `WOODEN_SHOVEL` +
        1 `WOODEN_AXE`, rien d'autre (pas de nourriture/armure/rune).
    3.  Redemander immédiatement (sans mourir) → message « déjà reçu », aucun outil
        supplémentaire.
    4.  **Mourir** pour rouvrir le droit (sans redemander tout de suite) — nécessaire avant
        l'étape suivante : après une remise réussie, le droit reste indisponible tant que le
        joueur n'est pas mort, donc tester un refus « pas assez de place » juste après l'étape 3
        obtiendrait à tort le message « déjà reçu ».
    5.  Remplir l'inventaire principal pour ne laisser **0 à 3** emplacements libres, demander
        → message « pas assez de place », **aucun** objet donné, rien jeté au sol/remplacé (le
        droit rouvert par l'étape 4 reste intact — un refus ne le consomme jamais).
    6.  Libérer au moins 4 emplacements, redemander → réussite.
    7.  Mourir, puis redemander **sans** avoir encore rien reçu de nouveau → réussite (nouveau
        cycle). Répéter mort → demande 2-3 fois : chaque cycle redonne le kit complet, jamais
        plus d'une fois entre deux morts.
    8.  (si un compte neuf est disponible) Mourir **avant** toute première demande → le droit
        initial reste disponible (la demande suivante réussit normalement).
    9.  Double-cliquer très vite sur le choix (ou redemander deux fois de suite sans attendre)
        → une seule remise, jamais deux.
    10. Se déconnecter/reconnecter (ou redémarrer le serveur) après une remise réussie → le
        droit reste « déjà reçu » jusqu'à la prochaine mort (persistance).
    11. `/rpgadmin player resetnew <joueur>` → le droit initial est restauré (nouvelle demande
        immédiate possible), **non-op** testé identique à **op**.
-   **Reset :** `starter-tool-kit.enabled: false` + `/rpgquest reload` désactive l'option sans
    toucher aux données déjà écrites. `/rpgadmin player resetnew` efface aussi la variable
    `STARTER_TOOL_KIT_AVAILABLE` (comme toute autre variable joueur).
-   **Couverture automatisée :** `StarterToolKitServiceTest` (12 cas : première demande, contenu
    exact, refus 0-3 places avec droit conservé, réussite à 4 places, nouvel essai après
    libération, refus sans nouvelle mort, nouvelle demande après mort sans remise automatique,
    plusieurs cycles mort/remise, mort avant première remise, clics rapides, variable restaurée
    comme `resetnew`, kit désactivé), `DialogueDefinitionParserTest`/`DialogueSessionEngineTest`
    (câblage `GIVE_STARTER_KIT`), `ConfigValidatorTest` (section `starter-tool-kit`).
-   **Limites MockBukkit (à couvrir uniquement en jeu) :** rendu réel du choix de dialogue
    (Paper Dialog natif vs repli chat), ressenti des messages en jeu, un vrai redémarrage complet
    du serveur (le service ne garde aucun cache mémoire — seule la variable persistée fait foi —
    mais ceci reste à confirmer en conditions réelles), deux clients réels cliquant
    simultanément sur le même compte (le verrou anti-double-clic n'est testé qu'en mono-thread).

---

## 22. Réseau de voyage / bornes (issues #132/#150)

### TC-221 — Parcours complet découverte → mort → borne → menu → retour sûr (PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `travel.beacon.TravelBeaconService`, `/rpgadmin travel beacon set`.
    Détail : [TRAVEL.md](TRAVEL.md) §« Réseau de voyage / bornes ».
-   **Préconditions :** JAR de cette session déployé **et redémarré** (non fait dans cette session —
    aucune autorisation de déploiement reçue pour ce chantier), au moins un waypoint déjà généré et
    découvert dans `wild` (voir TC-210).
-   **Placement de la première borne (admin) :**
    1.  Se rendre près du village actuel du Hub, `/rpgadmin travel beacon set` → message de
        succès, structure visible (support pierre + **bloc de diamant** + **bouton en bois**).
    2.  Rejouer la commande exactement au même endroit → refusée (« une borne existe déjà »),
        aucune deuxième structure posée.
-   **Parcours joueur (non-op) :**
    3.  Dans `wild`, découvrir un waypoint (clic droit sur son bouton, voir TC-210).
    4.  Mourir (ou simplement revenir au Hub par un autre moyen) puis se rendre à la borne.
    5.  Clic droit sur le bouton de la borne → menu graphique, **sans aucune commande**.
    6.  Catégorie « Waypoints découverts » → un **choix du monde** s'affiche d'abord (« Hub » et
        « Wild » toujours proposés, même à 0 découverte) ; choisir le monde du waypoint découvert à
        l'étape 3 → la liste apparaît, avec un **nom lisible propre** (ex. « Rochebrune »), le biome
        affiché en information secondaire — jamais deux destinations avec le même nom.
    7.  Catégories « Mon claim » / « Villages » → voir **TC-222** (issue #151) pour leur test dédié.
    8.  Cliquer le waypoint découvert → fermeture du menu, téléportation **sûre** tout près de ce
        waypoint (même zone que la quête en cours).
    9.  Tenter la borne avec un **second compte non-op** n'ayant rien découvert → dans le monde
        choisi, catégorie Waypoints vide, message explicite, aucun accès aux waypoints du premier
        joueur.
-   **Recherche et pagination (si plusieurs waypoints découverts) :**
    10. Bouton « Rechercher » → une **enclume s'ouvre** (pas de commande, pas de saisie chat) ;
        taper un nom (ou un fragment, casse/accents quelconques) puis cliquer le résultat → liste
        filtrée en conséquence, **toujours dans le même monde** qu'au moment d'ouvrir la recherche ;
        aucun objet n'est récupérable depuis cette enclume, aucun coût d'expérience prélevé.
    11. Avec plus de 45 waypoints découverts (si atteignable) : pagination page suivante/précédente
        fonctionnelle.
    12. Bouton « Retour » depuis la liste de waypoints → ramène au **choix du monde** (pas
        directement la racine) ; « Retour » depuis le choix du monde → ramène à la racine.
-   **Revalidation / erreurs :**
    13. Si un waypoint affiché est supprimé/désactivé entre l'ouverture du menu et le clic (ou en
        rouvrant le menu après une désactivation côté admin) : message propre, aucune
        téléportation, jamais de crash.
    14. Couper/décharger le monde de destination (si testable) : message « monde non chargé »,
        aucun déplacement.
-   **Reset :** aucune donnée joueur à réinitialiser spécifiquement ; `travel_beacons` est une
    table purement administrative, sans impact sur `/rpgadmin player resetnew`.
-   **Couverture automatisée :** `TravelBeaconServiceTest` (26 cas au total, dont 9 sur le socle
    #132/#150 : placement + structure + idempotence, protection anti-casse, ouverture du menu
    racine au clic bouton, état vide sans découverte, isolation des découvertes entre deux joueurs,
    parcours complet découverte→borne→menu→retour sûr au même waypoint, clic périmé/destination
    inconnue sans téléportation, pagination au-delà de 45 entrées, recherche insensible
    casse/accents ; +3 cas dédiés à #151, voir TC-222 ; +6 cas dédiés à #149, voir TC-223 ; **+8 cas
    de régression routant un vrai `InventoryClickEvent` à travers le listener réel** — correction
    du bug « tous les clics restaient silencieux » (destination, retour, recherche, pagination,
    fermeture, à travers la nouvelle étape « choix du monde », Hub/Wild toujours proposés, monde
    tiers extensible uniquement dès une découverte réelle).
-   **Limites MockBukkit (à couvrir uniquement en jeu) :** rendu réel de l'enclume virtuelle côté
    client (apparence, clavier de saisie), ressenti de la pagination/recherche avec un très grand
    nombre réel de waypoints, physique réelle de protection des blocs (explosion/piston en
    conditions réelles), plusieurs joueurs réels cliquant la borne simultanément. `EntityMock
    #teleportAsync(Location)` n'étant pas implémenté par MockBukkit (`UnimplementedOperationException`),
    les tests automatisés qui atteignent réellement la téléportation finale sont **ignorés** (pas
    échoués) par JUnit — tout ce qui précède (découverte, revalidation, calcul de la position) est
    bien vérifié ; seule la téléportation elle-même reste `PENDING MANUAL VALIDATION`.

### TC-222 — « Mon claim » et « Villages » (issue #151, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** catégories « Mon claim » / « Villages » du menu de la borne de
    voyage, `/rpgadmin travel village sethub|set|remove|enable|disable|list`. Détail :
    [TRAVEL.md](TRAVEL.md) §« Réseau de voyage / bornes ».
-   **Préconditions :** JAR de cette session déployé **et redémarré**, borne de voyage accessible
    (voir TC-221), spawn du Hub déjà défini (`/rpgadmin spawn set`, voir TC-221 du Hub).
-   **Mon claim :**
    1.  Sans claim : borne → « Mon claim » → icône grisée + message clair (pas un bouton muet).
    2.  Obtenir un claim (parcours normal du jeu). Réessayer → téléportation **à l'intérieur** du
        claim (pas à l'extérieur, pas à une position aléatoire).
    3.  Supprimer son claim (ex. `/claim delete` depuis dessus) puis réessayer sans rouvrir le
        menu si possible, sinon rouvrir : message propre « tu n'as pas (ou plus) de claim »,
        aucune téléportation.
-   **Villages — création admin :**
    4.  `/rpgadmin spawn set` (si pas déjà fait), puis `/rpgadmin travel village sethub hub_main
        "Hub principal"` → confirmation.
    5.  Se déplacer ailleurs dans `world_hub`, `/rpgadmin travel village set hub_est "Quartier
        Est"` → confirmation.
    6.  `/rpgadmin travel village list` → les deux centres apparaissent, avec leur monde et leur
        état actif.
-   **Villages — parcours joueur :**
    7.  Borne → « Villages » → les deux centres apparaissent, noms distincts.
    8.  Cliquer chacun → arrivée à la position **et orientation** exactes de sa création (vérifier
        qu'on arrive bien aux deux endroits différents, pas toujours au même).
-   **Villages — administration :**
    9.  `/rpgadmin travel village disable hub_est` → ce centre disparaît du menu joueur ; cliquer
        dessus via un clic resté en mémoire (si possible) → refusé proprement.
    10. `/rpgadmin travel village enable hub_est` → réapparaît, accessible de nouveau.
    11. `/rpgadmin travel village set hub_est "Quartier Est (déplacé)"` (même id, nouvelle
        position) → l'ancienne référence continue de fonctionner, arrivée à la **nouvelle**
        position.
    12. `/rpgadmin travel village remove hub_est` → disparaît du menu et de `list`.
-   **Reset :** aucune donnée joueur (`village_centers` est purement administratif, sans lien avec
    `/rpgadmin player resetnew`).
-   **Couverture automatisée :** `TravelBeaconServiceTest` (3 cas dédiés : refus sans claim puis
    arrivée vérifiée **dans** le cuboïde une fois un claim réel créé ; deux centres distincts par
    id avec arrivée à la position exacte de chacun ; centre désactivé/supprimé rejeté sans
    téléportation).
-   **Limites MockBukkit :** mêmes limites que TC-221 (`teleportAsync` non implémenté — les cas qui
    atteignent la téléportation sont ignorés, pas échoués, par les tests automatisés).

### TC-223 — Génération automatique Hub : waypoint + borne appariée (issue #149, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** à l'entrée d'une instance de biome du Hub sans waypoint, un
    waypoint (modèle or) **et** une borne distincte s'y génèrent automatiquement. Détail :
    [TRAVEL.md](TRAVEL.md) §« Réseau de voyage / bornes ».
-   **Préconditions :** JAR de cette session déployé **et redémarré**, `travel.waypoint.hub-enabled`
    et `travel.beacon.hub-generation.enabled` à `true` dans `config.yml` (défauts de livraison).
-   **Génération progressive :**
    1.  Se déplacer dans `world_hub`, dans une zone de biome où aucun waypoint n'existe encore
        (loin de toute zone déjà explorée par une session précédente).
    2.  Attendre quelques secondes (génération asynchrone) : un waypoint (support +
        `GOLD_BLOCK` + bouton) apparaît à distance raisonnable, jamais au pied du joueur.
    3.  Continuer à se déplacer dans la même zone : une **borne** distincte (support +
        `DIAMOND_BLOCK` + bouton en bois) apparaît à proximité du waypoint, **jamais à la même
        position**, une fois le waypoint réellement généré.
    4.  Cliquer le bouton du waypoint → découverte (message + son). Cliquer le bouton de la borne →
        menu de voyage s'ouvre ; le waypoint tout juste découvert apparaît dans « Waypoints
        découverts ».
-   **Idempotence / plusieurs joueurs :**
    5.  Revenir dans la même instance (ou y faire entrer un second joueur) : aucun second waypoint
        ni seconde borne n'apparaît (même emplacement exact qu'à la première génération).
    6.  Redémarrer le serveur, revenir sur place : waypoint et borne toujours présents, à la même
        position (persistance, pas de régénération).
-   **Non-régression Wild :** les waypoints et découvertes déjà existants dans le monde Wild
    restent inchangés (positions, découvertes par joueur) ; `/rpgadmin travel beacon set` continue
    de fonctionner normalement dans le Wild (placement administré, jamais auto-généré).
-   **Protection :** tenter de casser le bloc d'or du waypoint et le bloc de diamant de la borne
    générés dans le Hub (sans bypass) → refusé, comme pour une structure administrée.
-   **Couverture automatisée :** `TravelBeaconServiceTest` (6 cas dédiés #149 — voir le rapport de
    session pour le détail exact : génération progressive puis appariement distinct avec plusieurs
    joueurs, deux instances distinctes du Hub chacune avec sa propre paire, gating
    `hub-enabled`/`hub-generation.enabled` désactivés séparément, séparation stricte Wild/Hub
    (placement administré jamais marqué auto-généré), protection des deux structures auto-générées).
-   **Limites MockBukkit :** aucun des 6 nouveaux cas #149 n'atteint `teleportAsync` (ils
    s'arrêtent à la génération/l'appariement/la protection) — **aucun n'est ignoré**, tous sont
    réellement exécutés et vérifiés par l'automatisation. Seule la **découverte en jeu du bouton
    et l'ouverture du menu** (étapes 4 et suivantes ci-dessus) restent `PENDING MANUAL VALIDATION` :
    le placement physique des blocs et la logique de génération/appariement sont couverts
    automatiquement, mais aucun trajet réel en jeu n'a été effectué dans cette session.

### TC-224 — Accessibilité des structures Hub + diagnostic/réparation (issues #153/#156, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `RandomSafeLocationFinder#findAccessibleColumn`, `/rpgadmin travel
    diagnose`, `/rpgadmin travel repair`. Détail : [TRAVEL.md](TRAVEL.md).
-   **Préconditions :** JAR de cette session déployé et redémarré.
-   **Actions :**
    1.  Explorer `world_hub` dans une zone boisée (canopée dense) suffisamment longtemps pour
        déclencher une génération de waypoint/borne — vérifier qu'**aucune** structure n'apparaît
        au sommet d'un arbre ; si une structure se pose, elle doit être atteignable à pied depuis
        le sol sans casser/poser de bloc.
    2.  `/rpgadmin travel diagnose` → vérifie le nombre de waypoints/bornes, liste les instances
        Hub sans borne appariée (si présentes) et les structures inaccessibles (si présentes).
    3.  Si une structure inaccessible est signalée : `/rpgadmin travel repair waypoint <id>
        confirm` (ou `beacon`) → la structure se déplace vers un emplacement proche accessible ;
        revérifier son id, son nom et ses découvertes inchangés, et qu'elle est désormais
        atteignable à pied.
-   **Couverture automatisée :** `RandomSafeLocationFinderTest` (+4 cas `findAccessibleColumn` :
    sol plat ordinaire, feuillage rejeté, colonne isolée sans voisin praticable rejetée, marche
    d'un bloc acceptée), `WaypointServiceTest` (+3 cas : détection d'un waypoint devenu
    inaccessible, réparation conservant id/nom/découvertes avec persistance réelle en base, échec
    propre si l'id est inconnu), `TravelBeaconServiceTest` (+3 cas : waypoints Hub sans borne
    appariée listés puis vidés une fois la paire créée, détection+réparation d'une borne
    inaccessible, échec propre si l'id est inconnu).
-   **Limites MockBukkit :** aucun des nouveaux cas n'atteint `teleportAsync` — tous réellement
    exécutés et vérifiés, aucun ignoré. Le rendu visuel réel du déplacement (le joueur voit-il la
    structure disparaître/réapparaître correctement) reste `PENDING MANUAL VALIDATION`.

### TC-225 — Hub sûr : faim/saturation, animaux protégés, aucun mob indésirable (issues #33/#30/#31/#121/#155, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `hub.HubComfortService`, `hub.HubWorldProtectionListener`
    (protection des entités + spawns). Détail : section 7 de [RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md).
-   **Préconditions :** JAR de cette session déployé et redémarré ; compte de test **non-op**.
-   **Faim/saturation (#33) :**
    1.  Dans `world_hub`, sprinter et sauter en continu pendant plusieurs minutes → la barre de
        faim et la saturation ne bougent jamais.
    2.  Entrer dans `world_hub` depuis `wild` avec de la vie/faim réduites (ou se reconnecter alors
        qu'on y est déjà) → vie, faim et saturation remises au maximum sans commande ni PNJ.
    3.  Dans `wild`, vérifier que la faim continue de baisser normalement (aucune régression).
-   **Protection des animaux (#30/#31) :**
    4.  En tant que joueur non-OP, attaquer un mouton/une vache dans `world_hub` (mêlée puis à
        l'arc) → aucun dégât, aucune mort, aucune laine/viande obtenue.
    5.  Répéter sur plusieurs biomes du Hub explorés.
    6.  (si un bypass `rpgquest.admin.hub.combat` est accordé explicitement à un compte de test) →
        le dégât doit alors passer ; sans ce bypass, même un OP avec seulement
        `rpgquest.admin.world` ne doit **pas** pouvoir tuer l'animal.
    7.  Dans `wild`, vérifier que le combat contre les animaux reste normal.
-   **Mobs indésirables (#121/#155) :**
    8.  Explorer plusieurs biomes/chunks de `world_hub`, y compris des zones déjà visitées avant ce
        correctif → aucun creeper, enderman, marchand ambulant ni lama de commerce ne doit être
        trouvé ni apparaître au fil de l'exploration.
    9.  Vérifier qu'un PNJ Citizens existant reste intact et fonctionnel après le redémarrage
        (le nettoyage ne doit jamais l'affecter).
    10. Dans `wild`, vérifier que les spawns hostiles restent normaux (aucune régression).
-   **Reset :** aucune donnée joueur à réinitialiser ; ces protections sont purement comportementales.
-   **Couverture automatisée :** `HubComfortServiceTest` (9 cas : épuisement annulé dans le Hub,
    augmentation jamais bloquée, Wild non affecté, restauration à la connexion/au changement de
    monde/à la réapparition dans le Hub, garde périodique anti-épuisement silencieux, Wild jamais
    touché par la garde), `HubWorldProtectionListenerTest` (22 cas, dont les nouveaux : dégât mêlée
    et projectile sur un animal annulé, bypass combat explicite fonctionnel, le seul droit de
    construire ne donne jamais le droit de tuer, spawn hostile annulé quelle que soit la raison
    (pas seulement `NATURAL`), enderman annulé même neutre, marchand ambulant/lama annulés,
    animal passif jamais concerné, nettoyage ciblé des entités déjà présentes préservant les
    animaux passifs et scoped au seul Hub).
-   **Limites MockBukkit :** aucun des nouveaux cas n'atteint `teleportAsync` — tous réellement
    exécutés et vérifiés, aucun ignoré. Le ressenti réel en jeu (fluidité de la barre de faim,
    disparition visible des mobs nettoyés) reste `PENDING MANUAL VALIDATION`.
-   **Addendum — signalement #159 : faim bloquée aussi dans le Wild.** `VALIDÉ EN JEU le
    2026-10-04` : la faim baisse normalement dans le Wild. Confirme l'audit (aucune cause
    code/config trouvée — la saturation posée au maximum en sortant du Hub retardait simplement la
    baisse de faim le temps qu'elle s'épuise). Trace temporaire `[HUNGER-TRACE]` retirée.

### TC-226 — Secours Hub via la Rune de rappel (issue #154, scénario principal VALIDÉ EN JEU 2026-10-04)

-   **Fonctionnalité testée :** `travel.ItemTravelService#performFreeRescue`,
    `hub.HubRescueFallbackService`, `player.StarterKitListener`.
-   **Préconditions :** JAR de cette session déployé et redémarré ; compte de test **non-op**,
    possédant sa Rune de rappel (remise automatique à la première connexion).
-   **Scénario principal :**
    1.  Dans `world_hub`, se mettre volontairement dans un trou (sans en sortir par la pose/casse,
        interdites) puis clic droit sur la Rune de rappel → retour **immédiat** au spawn configuré
        du Hub, sans message « Cet objet ne fonctionne pas ici », sans perte de la Rune.
    2.  Répéter plusieurs fois d'affilée (spam) → aucune erreur, aucun comportement différent.
    3.  Dans `wild`, vérifier que la Rune garde son comportement normal (canalisation ~10 s, annulée
        par un déplacement, cooldown 30 min après un usage réussi) — aucune régression.
-   **Disponibilité de la Rune :**
    4.  Remplir complètement son inventaire **avant** la toute première connexion d'un compte neuf →
        aucune Rune donnée immédiatement ; libérer une place puis se reconnecter → la Rune est
        donnée à cette connexion suivante (jamais perdue pour toujours).
    5.  (cas limite) Si un joueur du Hub se retrouve sans Rune avec l'inventaire plein, un menu
        « Secours du Hub » (1 bouton, inventaire vanilla) doit apparaître dans la minute ; cliquer
        le bouton téléporte au spawn exactement comme la Rune.
-   **Reset :** aucune donnée à réinitialiser spécifiquement ; `/rpgadmin player resetnew` restaure
    le droit à la Rune comme avant.
-   **Couverture automatisée :** `ItemTravelServiceTest` (secours gratuit atteint hors du monde
    requis, comportement normal inchangé ailleurs), `StarterKitListenerTest` (3 cas, dont
    l'inventaire plein).
-   **Limites MockBukkit :** la téléportation elle-même (`teleportAsync`) n'est jamais exécutée
    dans les tests automatisés (exception attendue et vérifiée) ; le menu graphique de secours et
    son déclenchement par balayage périodique ne sont pas couverts par un test automatisé dédié
    (service neuf, voir le rapport de session) — `PENDING MANUAL VALIDATION` pour ces deux points
    (point 4/5 ci-dessus), le scénario principal (points 1-3) est validé en jeu.

### TC-227 — Recherche de waypoints (issue #150, round 2), noms lisibles et retour « déjà découvert » (VALIDÉS 2026-10-04)

-   **Fonctionnalité testée :** `travel.beacon.TravelBeaconService` (recherche par enclume),
    `waypoint.model.WaypointNameCatalog` (noms lisibles), `waypoint.WaypointService#handleInteract`
    (retour déjà découvert).
-   **Noms lisibles et retour « déjà découvert » : `VALIDÉ EN JEU le 2026-10-04`** (noms avec
    espaces confirmés ; message « déjà découvert : <nom> » confirmé au reclic).
-   **Recherche (issue #150, round 2) : encore `PENDING MANUAL VALIDATION`** — un premier correctif
    (indicateur de filtre) n'avait pas résolu le signalement : le joueur a confirmé en jeu que
    taper « lac » puis cliquer l'étiquette de résultat revenait à la liste complète, sans filtrage.
    Cause identifiée et corrigée (voir TRAVEL.md « Recherche graphique ») : le texte tapé est
    maintenant mémorisé dans la session à chaque frappe, jamais relu sur l'objet cliqué. **À
    revalider précisément :**
    1.  Ouvrir une borne → « Waypoints découverts » → un monde → « Rechercher ». Taper un mot
        (ex. « lac ») en majuscules ou minuscules, avec ou sans accent.
    2.  Cliquer sur l'étiquette de résultat (**pas** la touche Entrée) → la liste filtrée ne
        contient que les destinations correspondantes, sans distinction de casse/accents.
    3.  Vérifier l'indicateur « Recherche active : « … » · N résultat(s) » et son bouton
        d'effacement (slot dédié) → cliquer l'efface et réaffiche tout.
    4.  **Vérifier qu'aucun coût XP (`Coût : …`) n'apparaît** sur l'enclume — point non couvert par
        les tests automatisés dans cet environnement, à confirmer en jeu.
    5.  Essayer un terme sans correspondance → retour clair (« Aucun résultat pour « … » »), jamais
        un écran vide sans explication.
-   **Reset :** aucun ; les découvertes/noms existants ne sont jamais réinitialisés par ce correctif.
-   **Couverture automatisée :** `TravelBeaconServiceTest` —
    `realTypingThenClickingTheResultFindsLacDeGivreCaseAndAccentInsensitivelyWithActiveFilterIndicator`
    (vraie saisie via un vrai `PrepareAnvilEvent`, construit avec `FakeAnvilView`, puis vrai clic)
    et `searchStillFiltersEvenWhenTheResultSlotItemIsGoneByTheTimeTheClickIsHandled` (slot résultat
    délibérément vide avant le clic — preuve que la session porte le filtre, pas l'objet cliqué) ;
    `WaypointServiceTest` (message « déjà découvert » et « découvert » avec le nom) ;
    `WaypointNameCatalogTest` (chaque nom bundlé contient un séparateur) ; `SchemaMigratorTest`
    (migration V23 renomme les noms existants en préservant id/découvertes).
-   **Limites MockBukkit :** le test vérifie que le code appelle bien `setRepairCost(0)` **et**
    `setRepairCostAmount(0)` sur l'inventaire enclume simulé — mais pas le rendu visuel réel côté
    client (paquet/affichage), que MockBukkit ne simule pas. Ne jamais présenter l'absence de coût
    visible en jeu comme validée sans un test en jeu réel.

### TC-228 — Control Panel : réseau de voyage en lecture seule (issue #152, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** page `/travel` du Control Panel (PlugAdmin), action agent
    `travel.catalog`.
-   **Préconditions :** Control Panel AWS déployé avec cette version ; agent plugin connecté et
    heartbeat actif ; au moins un waypoint et une borne existants côté serveur.
-   **Scénario principal :**
    1.  Se connecter au Control Panel avec un rôle ayant `TRAVEL_READ` (ADMIN/TESTER/BUILDER/
        READ_ONLY) → le lien « Réseau de voyage » est visible dans la navigation et sur `/home`.
    2.  Ouvrir `/travel`, cliquer « Rafraîchir » → les deux tableaux (waypoints, bornes) se
        remplissent avec id/nom/monde/biome/coordonnées/état, l'horodatage de fraîcheur s'affiche.
    3.  Vérifier qu'un waypoint du Hub sans borne appariée est visible avec un badge distinct, et
        qu'une borne auto-générée référence bien son waypoint apparié.
    4.  Filtrer par recherche et par monde → les deux tableaux se filtrent indépendamment ;
        pagination fonctionnelle au-delà de 20 lignes.
    5.  Avec un rôle sans `TRAVEL_READ` : le lien et la page ne doivent jamais être accessibles.
-   **Reset :** aucun ; page strictement en lecture seule.
-   **Couverture automatisée :** `TravelCatalogTest` (5 cas : état vide, listes + pairage +
    fraîcheur, recherche, filtre monde, accès lecture seule), `AgentActionExecutorTest`
    (`travel.catalog`), `RolePermissionMatrixTest` (générique sur toutes les permissions).
-   **Limites :** aucune (page HTML pure, entièrement exerçable par un test HTTP) — seul le rendu
    visuel final (CSS/alignement) reste `PENDING MANUAL VALIDATION`.
-   **Libellés clarifiés (retour joueur 2026-10-04)** : « Apparié » remplacé par « Borne
    associée »/« Waypoint associé », affichant l'id réel plutôt qu'un oui/non, avec une phrase
    explicative au-dessus de chaque tableau précisant que ce n'est pas un statut de découverte
    joueur. À revérifier visuellement.

### TC-229 — Bossbar de suivi de quête (issue #157, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `ui.TrackedQuestDisplay`.
-   **Préconditions :** JAR de cette session déployé et redémarré ; quête « Premiers pas » avec
    l'étape `kill_spiders` active et suivie.
-   **Scénario principal :**
    1.  Suivre la quête « Premiers pas » à l'étape `kill_spiders` → la bossbar affiche le titre et
        un libellé humain de l'objectif (ex. « Tuer SPIDER »), jamais `kill_spiders` ni une balise
        `</gray>` littérale.
    2.  Faire progresser l'objectif (tuer une araignée) → la barre de progression avance sans
        réapparition de balise résiduelle.
    3.  Suivre une autre quête/étape → même rendu propre.
-   **Reset :** aucun ; correction d'affichage uniquement, ids internes inchangés.
-   **Couverture automatisée :** `TrackedQuestDisplayTest` (5 cas : aucune balise résiduelle +
    libellé humain, mise à jour sans duplication de bossbar, retrait propre, repli sur l'id si
    aucune description, aucune bossbar si `tracker-enabled=false`).
-   **Limites MockBukkit :** aucune — la `BossBar` réellement envoyée au joueur est inspectée
    directement ; seul le rendu visuel final en jeu reste `PENDING MANUAL VALIDATION`.

### TC-230 — Panneaux latéraux de nom des waypoints (issue #167, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `waypoint.render.WaypointModelV1`, `WaypointService#upgradeSigns`,
    `/rpgadmin travel signs upgrade`.
-   **Préconditions :** JAR de cette session déployé et redémarré ; compte de test **non-op**.
-   **Scénario principal :**
    1.  Découvrir un nouveau waypoint dans `wild` → un panneau est visible sur chacune des deux
        faces latérales du bouton (jamais la face opposée), affichant le nom canonique (espaces et
        accents conservés), réparti lisiblement si le nom est long.
    2.  Le bouton reste cliquable normalement.
    3.  Essayer de casser/éditer un panneau sans le bypass → refusé, comme le reste de la structure.
    4.  Exécuter `/rpgadmin travel signs upgrade wild` sur un waypoint généré **avant** cette
        version → les panneaux apparaissent sans déplacer la structure ni toucher la découverte.
    5.  Réparer un waypoint inaccessible (`/rpgadmin travel repair`) → les panneaux réapparaissent
        à la nouvelle position avec le même nom.
-   **Reset :** aucun ; id/position/découvertes jamais modifiés par cette fonctionnalité.
-   **Couverture automatisée :** `WaypointModelV1Test` (répartition du nom sur les lignes, pur) ;
    `WaypointServiceTest` (type/orientation/texte réels des deux panneaux, bouton resté
    l'interacteur, protection, mise à niveau idempotente d'un waypoint simulé « pré-#167 »).
-   **Limites MockBukkit :** aucune constatée — type, orientation (`Directional#getFacing()`) et
    texte réel (`Sign#getSide(Side.FRONT).lines()`) sont tous vérifiés directement par les tests.
    Seule la lisibilité visuelle réelle en jeu reste `PENDING MANUAL VALIDATION`.

---

### TC-231 — Mobs spéciaux / boss : éditeur Control Panel, tirage Wild, capacités (issue #169 lot 1, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** page Control Panel « Mobs spéciaux & boss » (`/mobs`, actions
    `mob.list`/`mob.definition.create`/`mob.definition.update`/`mob.definition.toggle`/
    `mob.spawn-settings.set`/`mob.test.spawn`/`mob.test.clear`), `MobCategory`,
    `EnragedAbilityService`, `SummonOnDamageAbilityListener`, `SpecialMobService` (attributs
    `KNOCKBACK_RESISTANCE`/`SCALE`, rayon d'explosion creeper, barre de vie + aura de particules
    boss, tirage aléatoire à deux étages).
-   **Préconditions :** JAR + Control Panel de cette session déployés et redémarrés ; opérateur
    avec le rôle `ADMIN` (ou `OWNER`) ; un joueur de test connecté et présent dans le monde Wild
    configuré (`travel.wild-world`).
-   **Scénario principal :**
    1.  Depuis `/mobs`, créer un profil `SPECIAL` (ex. `ZOMBIE`, nom coloré, `spawn-chance` élevée
        pour le test) sans écrire de YAML ni de commande → apparaît dans la liste, fichier
        `mobs/<id>.yml` créé côté serveur.
    2.  Créer un profil `BOSS` (ex. `ZOMBIE`, vie élevée) → apparaît avec le badge BOSS.
    3.  Depuis la fiche du profil `BOSS`, utiliser « Apparaître (test, Wild) » en choisissant le
        joueur de test → une instance apparaît à une position sûre à proximité ; vérifier en jeu :
        nom coloré, barre de vie Adventure visible et qui diminue quand on la frappe, aura de
        particules visible en continu.
    4.  Vérifier que ce boss **n'apparaît jamais** via un spawn naturel, même en attendant / en
        multipliant les spawns de zombies dans le Wild (profil BOSS exclu du tirage automatique).
    5.  Sur le profil `SPECIAL`, activer la capacité **Enragé** (ex. seuil 50 %, x1.5 vitesse,
        x2 dégâts) et sauvegarder ; faire apparaître une instance de test, la frapper jusque sous
        50 % de vie → signal visuel (particule + son) puis vitesse/dégâts visiblement augmentés ;
        continuer à la frapper → pas de nouveau signal, pas de ré-augmentation (binaire, une seule
        fois).
    6.  Sur un autre profil, activer **Invocation de renforts** (ex. type `ZOMBIE`, montant 2,
        chance 100 %, cooldown 10 s, max vivants 2) ; faire apparaître une instance de test et la
        frapper → des renforts apparaissent (zombies ordinaires, jamais eux-mêmes des mobs
        spéciaux), puis plus aucun au-delà du plafond avant la mort d'un renfort, et pas de nouveau
        déclenchement avant la fin du cooldown.
    7.  Régler le throttle Wild (« Tirage aléatoire dans le Wild ») à une chance faible puis à 0 →
        constater que les transformations deviennent rares puis plus aucune, sans toucher aux
        instances de test déjà présentes ni aux PNJ/mobs de Hub/Claims.
    8.  « Nettoyer les instances de test » → seules les instances taguées test disparaissent,
        aucun mob ordinaire du Wild n'est affecté.
    9.  Désactiver un profil (bouton Activer/Désactiver) → il n'est plus tiré au hasard ni
        disponible pour un nouveau spawn de test, mais reste listé et réactivable.
    10. Redémarrer le serveur → les profils, leurs capacités et le throttle Wild sont identiques
        après rechargement (fichiers YAML relus, jamais régénérés par défaut).
-   **Reset :** supprimer manuellement les fichiers `mobs/<id>.yml` créés pour le test si besoin ;
    `mob.test.clear` suffit pour les instances vivantes.
-   **Couverture automatisée :** `SpecialMobDefinitionParserTest` (nouveaux champs + capacités),
    `SpecialMobDefinitionYamlTest`/`SpecialMobDefinitionStoreTest` (round-trip, création/modification
    atomique), `SpecialMobServiceTest` (exclusion BOSS du tirage, throttle global, tirage pondéré
    explicite entre profils simultanément gagnants, profil désactivé jamais tiré),
    `AgentActionCatalogTest`/`RolePermissionMatrixTest` (whitelisting, permissions, confirmation).
-   **Limites MockBukkit :** `setRemoveWhenFarAway` n'est pas implémenté par MockBukkit — tout test
    automatisé appelant `SpecialMobService#apply` (donc `EnragedAbilityService`,
    `SummonOnDamageAbilityListener`, la barre de vie/aura boss, les nouveaux attributs
    `KNOCKBACK_RESISTANCE`/`SCALE`/rayon d'explosion creeper) est **sans couverture automatisée
    exécutable** dans cet environnement — limitation préexistante (les trois capacités
    précédentes, `STRONGER_EXPLOSION`/`EXPLOSIVE_ON_ATTACK`/`SPLIT_ON_HIT`, ont la même limite ou
    n'ont aucun test dédié). Les points 3, 5, 6 de ce scénario restent donc entièrement
    `PENDING MANUAL VALIDATION`.

---

### TC-232 — Zombie fissile équilibré, poursuite Cochon Creeper, création de profil (issue #190, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `splitting_zombie.yml` (vitesse + `max-alive-per-parent`),
    `SplitOnHitAbilityListener` (plafond par parent), `ExplosiveOnAttackAbilityService` (poursuite
    via `Mob#getPathfinder()`), formulaire « Nouveau profil » de `/mobs` (bouton dupliqué,
    sections de capacités repliées).
-   **Préconditions :** JAR + Control Panel de cette session déployés et redémarrés ; un Zombie
    Fissible rencontré naturellement dans le Wild, OU une instance de test apparue via `/mobs`.
-   **Scénario principal :**
    1.  Rencontrer/faire apparaître un Zombie Fissible → sa vitesse doit paraître proche d'un
        zombie normal, jamais un sprint démesuré.
    2.  Le frapper à plusieurs reprises sans le tuer (coups non mortels successifs) → au plus 2
        enfants vivants directs doivent apparaître pour ce parent précis, jamais plus, même après
        de nombreux coups.
    3.  Faire apparaître une instance de test de `rpgquest:creeper_pig` (ou un profil équivalent) à
        proximité, puis s'éloigner à portée de perception (quelques blocs au-delà de la portée de
        déclenchement de l'explosion, dans un rayon d'environ 16 blocs) → le cochon doit se
        déplacer vers le joueur ; en se rapprochant à portée de déclenchement, l'explosion doit se
        produire normalement.
    4.  Vérifier qu'un animal ordinaire (cochon/poule/grenouille non tagué) ne poursuit jamais le
        joueur.
    5.  Depuis `/mobs`, cliquer « Nouveau profil », remplir ID technique/Type d'entité/Nom affiché,
        puis cliquer le bouton « Créer » situé juste après la section Identité (sans faire défiler
        plus bas) → le profil doit apparaître dans la liste après rafraîchissement.
    6.  En modification d'un profil existant disposant déjà d'Enragé ou d'Invocation de renforts,
        vérifier que la section correspondante s'ouvre automatiquement (pas besoin de cliquer pour
        la déplier).
-   **Reset :** aucun ; `mob.test.clear` pour les instances de test, suppression manuelle des
    fichiers `mobs/<id>.yml` créés pour le test si besoin.
-   **Couverture automatisée :** `SpecialMobDefinitionParserTest`/`SpecialMobDefinitionYamlTest`
    (nouveau champ `max-alive-per-parent`, round-trip) ; `SplitOnHitAbilityListenerTest` (2 tests
    supplémentaires exécutés réellement — identité posée en PDC plutôt que via `apply()` — dont un
    nouveau test reproduisant exactement 4 coups répétés sur le même parent, plafonné à 2 enfants) ;
    `ExplosiveOnAttackAbilityServiceTest` (nouveau, déclenchement de l'explosion et absence de
    déclenchement hors portée vérifiés réellement) ; `BukkitAgentActionsMobTest` (nouveau, teste
    directement `mobDefinitionCreate`/`Update`/`Toggle`, y compris sur bases PIG/CHICKEN/FROG —
    jamais seulement le double de test).
-   **Limites MockBukkit :** `Mob#getPathfinder()` n'est pas implémenté par cette version de
    MockBukkit (confirmé en écrivant le test, qui échoue précisément sur cet appel) : la poursuite
    elle-même (point 3 ci-dessus) est **sans couverture automatisée exécutable**, entièrement
    `PENDING MANUAL VALIDATION`. Le mécanisme de plafond par parent (point 2) est vérifié
    automatiquement avec certitude.

---

### TC-233 — Chaîne de paliers du Garde : claims TIER_1 à TIER_5 (issue #179, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `quests/guard_tier{1..5}.yml`, `dialogues/guard.yml` (nouvelles
    branches), `ClaimService#upgradeTier`/`#highestEntitledTier`, `/rpgadmin claim grant-tier`.
-   **Préconditions :** JAR de cette session déployé et redémarré ; `CLAIM_TIER_1` et tout claim du
    joueur de test remis à zéro (`/rpgadmin player resetnew <joueur> confirm`, ou
    `/claim admin resettier1 <joueur>` si seul le volet claim doit être rejoué).
-   **Scénario principal (sans grinder, via les raccourcis admin déjà documentés) :**
    1.  Parler au Garde → le choix « Je veux prouver ma valeur... (palier 1) » doit apparaître (si
        `crystal_hunt` n'a pas déjà accordé `CLAIM_TIER_1`, ou même si elle l'a déjà fait : le
        palier 1 du Garde reste un parcours indépendant).
    2.  Accepter → le nœud affiché doit énoncer les quatre menaces et leurs montants (5 de chaque).
    3.  Au lieu de tuer 5+5+5+5 mobs, exécuter `/rpgadmin quest complete <joueur> guard_tier1` →
        vérifier en jeu que l'Acte de propriété (ou le claim existant) correspond bien à `TIER_1`.
    4.  Répéter `/rpgadmin quest complete <joueur> guard_tierN` pour N=2..5 dans l'ordre → après
        chaque complétion, revérifier auprès du Garde que le palier suivant est bien proposé (texte
        cohérent, bons montants dans le nœud `guard_tierN_accepted`), et que le claim grandit
        réellement (`/claim info` ou `/rpgadmin claim grant-tier <joueur> TIER_n` pour vérifier
        l'idempotence : doit répondre « déjà à ce palier »).
    5.  Après `guard_tier5` complétée, reparler au Garde → doit proposer uniquement le message de
        clôture (« Tu as largement prouvé... »), **jamais** un rappel d'objectif ni une 6e offre.
    6.  Vérifier qu'un joueur n'ayant **pas encore posé** son Acte, mais ayant déjà complété
        `guard_tier1`..`guard_tier3` (ex. via les raccourcis admin ci-dessus), pose directement un
        claim `TIER_3` (20×20) à la première confirmation de l'Acte — jamais 5×5 par défaut.
    7.  (Optionnel, scénario réel) Refaire le parcours en tuant réellement quelques araignées pour
        confirmer que les compteurs de `/quest progress guard_tier1` avancent bien avec les 4
        objectifs simultanés, dans n'importe quel ordre.
-   **Reset :** `/rpgadmin quest reset <joueur> guard_tierN` (ne révoque pas les récompenses déjà
    données, y compris la taille de claim déjà accordée — limite documentée, cohérente avec
    `quest reset` en général) ; `/claim admin resettier1 <joueur>` pour repartir du claim lui-même.
-   **Couverture automatisée :** `BundledQuestsValidityTest` (chargement sans erreur, 4 objectifs
    `KILL_ENTITY` aux bons montants par palier, prérequis chaînés, entitlement `CLAIM_TIER_n`
    accordée), `BundledDialoguesValidityTest` (`guard.yml` toujours chargeable avec les nouvelles
    branches), `ClaimServiceTest` (`highestEntitledTier`, `upgradeTier` : succès centré sur le même
    point, idempotence déjà-au-palier, refus sans claim posé, refus de chevauchement),
    `DeedClaimListenerTest` (première pose directement au palier le plus haut déjà obtenu).
-   **Limites :** aucune limite MockBukkit connue pour ce volet (contrairement à #190) — tout le
    mécanisme de palier est testable automatiquement ; seul le parcours en jeu réel (dialogue visuel,
    vrais combats) reste `PENDING MANUAL VALIDATION`.

---

### TC-234 — Protection et réparation des structures de voyage (issue #191, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `TravelMaintenanceMode`, les deux écouteurs de protection
    (waypoint + borne diamant), `/rpgadmin travel maintenance`, `/rpgadmin travel diagnose`
    (section « structures abîmées »), `/rpgadmin travel restore waypoint`.
-   **Préconditions :** JAR de cette session déployé **et serveur redémarré** (la permission
    `rpgquest.admin.travel.maintenance` est déclarée dans `plugin.yml`). Un waypoint connu et une
    borne diamant accessibles. Prévoir **deux comptes** : un compte OP et un compte joueur ordinaire.
-   **Scénario principal — la protection (regrouper sur un seul waypoint) :**
    1.  Avec le **compte OP** (celui du signalement), tenter de casser successivement : le bouton,
        le bloc d'or, le support en pierre, puis un panneau latéral → **chaque casse doit être
        refusée**. C'est le cœur du correctif : avant, l'OP détruisait tout.
    2.  Même tentative avec le **compte joueur ordinaire** → refusée aussi.
    3.  Casser le **support** sous le bloc d'or → refusé (le bouton/panneau ne doit pas tomber).
    4.  Faire exploser un creeper / de la TNT à côté → aucun bloc de la structure ne disparaît.
    5.  Pousser la structure avec un piston → refusé.
    6.  Répéter les points 1 et 4 sur une **borne diamant** → même protection.
-   **Scénario maintenance explicite :**
    7.  Avec l'OP, `/rpgadmin travel maintenance on` → doit **refuser** (permission dédiée absente,
        volontairement non accordée aux OP).
    8.  Accorder `rpgquest.admin.travel.maintenance` au compte de test, puis **sans** activer le
        mode, retenter une casse → toujours refusée.
    9.  `/rpgadmin travel maintenance on` puis casser le bouton → **autorisé**.
    10. `/rpgadmin travel maintenance off` → la casse est de nouveau refusée immédiatement.
    11. Laisser passer 5 minutes après un `on` sans rien faire, puis retenter → refusée (expiration).
-   **Scénario réparation (enchaîner juste après le point 9, structure déjà cassée) :**
    12. `/rpgadmin travel diagnose` → le waypoint doit apparaître dans « structures abîmées » avec
        la liste des blocs manquants.
    13. Noter le **nom** du waypoint et vérifier qu'il est **déjà découvert** par le compte de test.
    14. `/rpgadmin travel restore waypoint <id> confirm` → structure reposée **à la même position**,
        avec le même nom sur les panneaux ; `/rpgadmin travel diagnose` ne le signale plus.
    15. Vérifier que la découverte du joueur est **conservée** (menu de voyage) et qu'**aucun second
        waypoint** n'est apparu dans le biome.
    16. Conflit : casser le bloc d'or, poser un bloc quelconque à sa place, puis
        `restore … confirm` → doit **refuser** en nommant le conflit, sans rien écraser ;
        `restore … confirm force` doit alors passer.
-   **Reset :** `/rpgadmin travel maintenance off`. Aucune donnée à réinitialiser (aucune
    suppression d'enregistrement n'intervient dans ce scénario).
-   **Couverture automatisée :** `WaypointProtectionListenerTest` (reproduit le défaut :
    `rpgquest.admin.world` seul ne casse plus rien ; permission dédiée seule non suffisante ;
    maintenance explicite autorisée ; OP incapable d'activer le mode ; désactivation immédiate),
    `WaypointServiceTest` (structure intacte, destruction détectée bloc par bloc, restauration sur
    place conservant id/nom/position/découvertes sans doublon, refus de conflit sauf `force`).
-   **Limites :** l'expiration au bout de 5 minutes (point 11) n'est pas couverte automatiquement
    (dépend de l'horloge réelle) ; la perte de support et les pistons sont vérifiés au niveau de
    l'événement Bukkit, leur comportement physique réel reste à constater en jeu.

### TC-235 — Hostiles de jour dans le Wild (issue #168, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `WildHostileRulesService` et la section `wild:` de `config.yml`.
-   **Préconditions :** JAR déployé et serveur redémarré. Être en **survie** dans le monde Wild
    (`travel.wild-world`), en surface dégagée. Les règles ne s'appliquent qu'aux mondes listés.
-   **Scénario principal :**
    1.  `/time set noon`, rester quelques minutes en surface exposée → des hostiles doivent
        **apparaître** à distance (24 à 48 blocs par défaut), pas uniquement dans les grottes.
    2.  Vérifier qu'un zombie/squelette **ne prend pas feu** au soleil.
    3.  Vérifier que le feu et la lave **blessent toujours** normalement ces mêmes créatures
        (les pousser dans du feu / de la lave) — l'immunité ne doit concerner que le soleil.
    4.  Observer une **araignée en plein jour** : elle doit acquérir une cible et attaquer.
        Tester aussi une araignée **déjà présente** au passage nuit → jour.
    5.  `/time set midnight` → les apparitions nocturnes restent normales, **sans surnombre
        anormal** (pas de double génération).
    6.  Rester sur place longtemps et vérifier que la population reste bornée (plafonds
        `max-per-player` / `max-per-world`), sans chute de TPS (`/tps`).
    7.  Aller dans le **Hub** : aucun mob hostile ne doit apparaître. Dans un **claim**, les
        protections restent inchangées.
    8.  Vérifier qu'un PNJ Citizens n'est pas ciblé/altéré, et qu'en créatif/spectateur les
        araignées ne prennent pas le joueur pour cible.
-   **Reset :** `/time set day`, ou `wild.daylight-spawns.enabled: false` + `/rpgquest reload`.
-   **Couverture automatisée :** `WildHostileRulesServiceTest` (périmètre : monde Wild uniquement,
    repli sur `travel.wild-world`, plusieurs mondes Wild ; soleil annulé pour un monstre dans le
    Wild ; **feu/lave et attaquant enflammé jamais annulés** ; hors Wild inchangé ;
    `sun-immunity: false` rend le comportement vanilla ; animal passif jamais protégé).
-   **Limites assumées :** MockBukkit ne simule ni le spawn naturel, ni la ligne de vue, ni l'IA de
    ciblage. **Les points 1, 4 et 6 n'ont aucune couverture automatisée exécutable** et sont
    entièrement `PENDING MANUAL VALIDATION` — un test vert ici ne vaut pas validation du spawn réel
    ni de l'agressivité réelle.

### TC-236 — Hache du kit utilisable dans le Wild (issue #192, configuration serveur)

-   **Fonctionnalité testée :** configuration **WorldEdit** (`wand-item`), pas de code RPGQuest.
-   **Préconditions :** `plugins/WorldEdit/config.yml` → `wand-item: minecraft:golden_axe` puis
    `/worldedit reload` (voir `docs/RPGQUEST_BIBLE.md` §14). Sans cette modification, le test
    échoue par conception — **ne pas** considérer `/toggleeditwand` comme un contournement : en
    WorldEdit 7.4.1 cette commande n'affiche qu'un rappel.
-   **Scénario principal :**
    1.  Avec le **compte administrateur** (celui du signalement), casser une bûche ordinaire dans le
        Wild avec la hache en bois du kit → le bloc casse, **aucun message « position définie »**.
    2.  Même test avec un **joueur ordinaire**.
    3.  Se déconnecter/reconnecter, puis redémarrer le serveur → le conflit ne revient pas.
    4.  `//wand` donne une hache **en or** ; la sélection WorldEdit fonctionne toujours avec elle.
    5.  Vérifier que les protections restent effectives : tenter de casser un bloc de waypoint
        (refusé, cf. TC-234) et un bloc du Hub.
-   **Couverture automatisée :** aucune — c'est une configuration d'un plugin tiers, hors dépôt.
    La version installée (`7.4.1`) et le comportement de `/toggleeditwand` ont été vérifiés par RCON
    sur le DEV ; le reste est manuel par nature.

---

### TC-237 — Journal : infobulle compacte et récupération sans doublon (PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** infobulles du journal de quêtes (liste vs détails), noms traduits,
    masquage des attributs vanilla, remise du journal par le Libraire.
-   **Préconditions :** JAR de cette session déployé et serveur redémarré. Avoir au moins une quête
    acceptée (par ex. `guard_tier1`, qui a quatre objectifs — idéal pour voir les quatre compteurs).
-   **Scénario principal :**
    1.  Ouvrir le journal (clic droit sur le journal, ou `/quests`). Survoler l'icône d'une quête
        active → l'infobulle doit tenir en quelques lignes : **état**, les **compteurs** des
        objectifs de l'étape (quatre pour une quête de palier du Garde), puis **Clic gauche :
        détails** / **Clic droit : suivre**.
    2.  Vérifier qu'il **n'y a plus** : la description, la catégorie, les récompenses, les
        prérequis, ni l'identifiant d'étape (`prove_worth`…).
    3.  Vérifier les **noms français** : « Araignée », « Zombie », « Squelette », « Creeper » —
        jamais `SPIDER` ni `Tuer SPIDER`.
    4.  Si l'icône de la quête est une arme (épée), vérifier qu'**aucune ligne de dégâts d'attaque**
        n'apparaît dans l'infobulle.
    5.  Clic gauche → vue détails : la description, la catégorie, les objectifs, les **récompenses**
        et les prérequis doivent y être. Une récompense « variable » interne (ex. `CLAIM_TIER_1`)
        ne doit **jamais** y figurer.
    6.  Avec plus de quatre objectifs sur une étape, vérifier la ligne « +N autre(s) — voir les
        détails » (si une telle quête existe ; sinon point non applicable).
-   **Récupération du journal (sans reset ni doublon) :**
    7.  Jeter / tenter de perdre le journal → impossible (objet soulbound) ; mourir et réapparaître
        → le journal est toujours là.
    8.  Parler au Libraire **en ayant déjà le journal** → l'option « Obtenir un journal des quêtes »
        ne doit **pas** être proposée (anti-doublon).
    9.  Se faire retirer le journal par un administrateur (`/clear` ciblé), reparler au Libraire →
        l'option réapparaît, un **seul** exemplaire est remis, et la progression des quêtes est
        **inchangée** (les mêmes quêtes, au même avancement, dans le journal rouvert).
-   **Couverture automatisée :** `QuestJournalServiceTest` (infobulle de liste sans identifiant
    technique ni description ni récompenses, compteurs présents, attributs masqués ; infobulle de
    détails avec description et récompenses mais **sans** la variable interne),
    `BundledDialoguesValidityTest` (un seul chemin de remise du journal, condition anti-doublon
    présente, action limitée à un `customitem give … 1`).
-   **Limites :** le rendu réel des noms traduits dépend de la langue du client — à constater en
    jeu ; les tests vérifient seulement qu'aucun identifiant technique n'est écrit.

### TC-238 — Nom en jeu et skin d'un PNJ depuis le panel (issue #165, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** actions `npc.citizens.rename` et `npc.citizens.skin`, formulaire
    « Nom en jeu & apparence » de la fiche PNJ.
-   **Préconditions :** Control Panel déployé, JAR déployé **et serveur redémarré**, un PNJ Citizens
    lié (Help, Citizens #2, id logique `help`). Un lien MineSkin valide, par ex.
    `https://minesk.in/de347dcfb215477db7ae52d780e68757`.
-   **Scénario principal (navigateur puis jeu) :**
    1.  Fiche de Help → « Nom en jeu & apparence » → changer le nom → **Renommer**.
    2.  Rafraîchir la fiche : le nom affiché doit avoir changé, et l'**id logique `help`** ainsi que
        l'id Citizens doivent être **inchangés**.
    3.  En jeu : le nom au-dessus du PNJ doit être le nouveau. Le dialogue et les quêtes liées
        doivent continuer de fonctionner exactement comme avant (liaisons intactes).
    4.  Coller le **lien** MineSkin → **Appliquer le skin** → message « demande transmise ».
    5.  En jeu : l'apparence change (une reconnexion du client peut être nécessaire).
    6.  Redémarrer le serveur → nom **et** skin doivent être conservés.
-   **Scénarios d'erreur :**
    7.  Coller la **commande** `/npc skin --url …` au lieu du lien → refus explicite, aucun
        changement.
    8.  Coller une URL d'un autre domaine → refus explicite.
    9.  Appliquer un skin sur un PNJ d'un type qui n'en accepte pas → message compréhensible, et le
        skin précédent **conservé**.
-   **Couverture automatisée :** `AgentActionCatalogTest` (seul un lien MineSkin strict est
    accepté ; la commande collée, un autre domaine, `http://`, une URL vide et l'absence de PNJ
    ciblé sont tous refusés ; nom borné à 48 caractères et PNJ toujours ciblé explicitement).
-   **Limites :** l'application visuelle du skin est **asynchrone côté Citizens** — le succès
    renvoyé signifie « demande transmise », jamais « skin confirmé ». Les points 5, 6 et 9 n'ont
    aucune couverture automatisée et sont entièrement à valider en jeu.

---

### TC-239 — Création d'un mob SPECIAL et d'un BOSS depuis le panel (issue #172)

-   **Fonctionnalité testée :** relevé `mob.catalogs`, listes recherchables et multisélections de
    l'éditeur `/mobs`, recherche et filtres du catalogue.
-   **Préconditions :** Control Panel et JAR de cette session déployés, serveur redémarré.
-   **Vérifiable immédiatement dans le navigateur (pas besoin de Minecraft) :**
    1.  `/mobs` → cliquer **« Catalogues Minecraft »**. Le bandeau d'avertissement doit disparaître
        et le bouton repasser en gris (il est orange tant que le relevé manque).
    2.  **« Nouveau profil »** → champ **Type d'entité** : taper `zomb` → la liste filtrée doit
        proposer `ZOMBIE`, `ZOMBIE_VILLAGER`, `ZOMBIFIED_PIGLIN`… Vérifier qu'on peut choisir à la
        souris **et** au clavier (flèches + Entrée).
    3.  Champ **Particule** : taper `dust` → les entrées colorables portent la mention
        « colorable ». Champ **Son** : taper `wither` → la liste filtre parmi ~1800 sons.
    4.  Champs **Mondes autorisés** / **Biomes autorisés** : ajouter plusieurs valeurs → elles
        apparaissent en puces supprimables. Laisser vide affiche « Aucune restriction ».
    5.  Remplir ID technique, Catégorie **Spécial**, Type d'entité, Nom affiché → **Créer**.
    6.  Cliquer **« Rafraîchir »** : le profil doit apparaître dans le catalogue avec le badge
        `SPECIAL`.
    7.  Refaire 5-6 avec Catégorie **Boss** → badge `BOSS`.
    8.  Utiliser la **recherche** du catalogue (nom ou identifiant) puis les filtres
        **Boss / Mob spécial / Désactivés**.
    9.  Cas d'erreur : créer avec un type d'entité inexistant (ex. `PAS_UN_MOB`) → un message
        d'erreur **lisible** doit s'afficher. Le point important : le bouton ne doit **jamais**
        rester sans réaction ni message.
-   **Contenu de test déjà présent** : deux profils `claude_diag_special` et `claude_diag_boss`
    créés pendant le diagnostic, **laissés désactivés** (ils n'influencent donc aucun spawn). À
    supprimer quand vous voulez — ce sont des contenus de test, pas du contenu de jeu.
-   **À vérifier en jeu (ce soir)** : qu'un profil créé depuis le panel apparaît réellement via le
    tirage Wild ou un spawn de test, et que le rendu BOSS (nom, particules, barre de vie) est correct.
-   **Couverture automatisée :** `AgentActionCatalogTest` (validation des actions).
    **Non couvert automatiquement** : le rendu HTML et le comportement JavaScript des listes — ils
    ont été vérifiés par requêtes HTTP authentifiées réelles sur l'instance déployée (datalists
    peuplées : 91 entités / 115 particules / 1838 sons / 65 biomes ; création SPECIAL **et** BOSS
    jusqu'à réapparition au catalogue), ce qui ne remplace pas un essai au navigateur.

---

### TC-240 — Couleurs et styles au clic, sans écrire de MiniMessage (issue #195)

-   **Fonctionnalité testée :** composant de texte stylé partagé — palette, cases de style,
    aperçu, et **préservation des textes déjà écrits avec plusieurs styles**.
-   **Préconditions :** Control Panel de cette session déployé. **Aucun Minecraft requis** : ce
    test se fait entièrement dans le navigateur.
-   **Parcours — nom d'un mob / boss :**
    1.  `/mobs` → déplier **« Nouveau profil »**. Le champ **Nom affiché** montre un champ texte
        simple, une rangée de pastilles de couleur et quatre cases de style.
    2.  Taper `Roi des Marais` → cliquer la pastille **or** → cocher **Gras**. L'**aperçu** doit
        s'afficher en or et en gras. Aucun code n'a été saisi.
    3.  Cliquer **« Modifier le code MiniMessage »** : le champ brut doit afficher exactement
        `<gold><bold>Roi des Marais</bold></gold>`. Recliquer le bouton pour revenir au mode guidé.
    4.  Cliquer la pastille **∅** (aucune couleur) → le code redevient `<bold>Roi des Marais</bold>`.
-   **Parcours — texte de dialogue :** `/dialogues` → ouvrir un dialogue → déplier un nœud. Même
    composant sur **Texte du nœud**. Enregistrer un nœud **sans rien changer** au style : le texte
    doit rester identique (vérifiable en rouvrant le nœud).
-   **Le point important — un texte multi-styles ne doit JAMAIS être aplati :**
    1.  Sur `/mobs`, ouvrir le profil de test **`claude_diag_special`** (désactivé). Son nom a été
        volontairement posé à `<red>Roi</red> <gold>des Marais</gold>` pendant la vérification.
    2.  Le composant doit s'ouvrir en **mode avancé** : le code brut est visible tel quel, un
        message explique que l'éditeur guidé le simplifierait, et le bouton propose de basculer
        **explicitement** en annonçant la simplification.
    3.  **Ne pas** cliquer ce bouton, enregistrer → le nom doit rester inchangé.
    4.  Même attendu pour un dégradé (`<gradient:red:blue>…</gradient>`), une couleur
        hexadécimale (`<#ff00ff>…</#ff00ff>`) ou une balise au milieu du texte
        (`Bonjour <red>joueur</red>`) : mode avancé, contenu intact.
-   **Sans JavaScript** (désactiver JS dans le navigateur) : les deux champs restent des champs
    texte MiniMessage ordinaires et l'enregistrement fonctionne. L'éditeur guidé est simplement
    absent — jamais un champ en double ni un formulaire bloqué.
-   **Couverture automatisée :** `StyleFieldTest` (contrat du champ soumis, repli sans JavaScript,
    échappement) ; `control-panel/src/test/js/stylefield-parse.test.js` exécuté avec
    `node control-panel/src/test/js/stylefield-parse.test.js` (règle d'uniformité et aller-retour).
    **Vérifié sur l'instance déployée** : aller-retour réel d'un nom multi-styles
    panel → plugin → YAML → relevé → formulaire, rendu à l'identique ; et les **42 textes de nœuds
    réels** du dépôt passés au parseur — 23 en mode guidé avec aller-retour exact, 19 laissés en
    mode avancé, **0 contenu altéré**. Cela ne remplace pas l'essai au navigateur ci-dessus.

---

### TC-242 — Catalogue complet des objets : icônes et récompenses (issue #196)

-   **Fonctionnalité testée :** relevé `item.catalogs`, recherche par nom français et par
    identifiant, résultats complets et paginés, refus expliqué d'un bloc sans forme d'objet.
-   **Préconditions :** Control Panel et JAR de cette session déployés, serveur redémarré.
-   **Vérifiable immédiatement dans le navigateur (pas besoin de Minecraft) :**
    1.  `/quests` → un bandeau orange doit signaler que le catalogue des objets n'est pas chargé,
        et le bouton **« Objets Minecraft »** doit être orange. Cliquer dessus.
    2.  Le bandeau disparaît, le bouton repasse en gris. Dans le journal d'actions, le résumé doit
        citer la version réelle — par exemple « … — Minecraft 1.21.11 ».
    3.  `/quests/new` (ou éditer une quête) → champ **Icône** : taper `sword`.
        **Les sept épées doivent apparaître** : bois, pierre, **cuivre**, or, fer, diamant,
        netherite. C'est l'épreuve exacte du ticket : avant, seules `IRON_SWORD` et
        `DIAMOND_SWORD` sortaient.
    4.  Même champ : taper **`épée`** → les mêmes sept entrées, trouvées par leur nom français.
        Puis `lingot` → les lingots ; `planches` → les planches de chaque essence.
    5.  Taper une recherche très large, par exemple `a` : la liste doit afficher
        **« 100 sur N affichés — afficher 100 de plus »**. Cliquer : 100 entrées de plus
        s'ajoutent, sans fermer la liste. **Aucun résultat ne doit disparaître en silence.**
    6.  L'aide sous le champ **Icône** doit annoncer le nombre d'objets **et** la version
        (« … N objets de la version installée (Minecraft 1.21.11) »), et non plus une liste de
        dépannage.
    7.  Section **Récompenses** → ajouter une récompense **Objet** → taper `épée en diamant` → la
        sélection doit se faire, et la **quantité** saisie doit être conservée.
    8.  Cas du bloc sans objet : dans **Icône**, saisir `WATER` puis **Vérifier**. Le message doit
        être explicite — « existe comme bloc mais n'a aucune forme d'objet dans cette version » —
        et **jamais** « matériau inconnu ». Idem pour une récompense `FIRE`.
    9.  Cas de l'objet technique : taper `command` → `COMMAND_BLOCK` doit apparaître, **marqué
        « créatif / technique »**, et rester sélectionnable.
    10. Enregistrer une quête avec une icône choisie dans la liste, recharger la page : la
        sélection et les quantités doivent être **inchangées**.
-   **À vérifier en jeu (plus tard) :** que l'icône choisie s'affiche bien dans le journal de
    quêtes, et qu'une récompense d'objet est réellement remise.
-   **Couverture automatisée :** `MaterialNamesTest` (11 cas : familles composées, élision
    « Lingot d'or », repli anglais assumé, objets de créatif signalés), `ItemCatalogTest` (9 cas :
    la liste codée en dur ne contenait que 2 épées, le relevé en trouve 7, le relevé remplace le
    repli sans fusion, bloc sans objet reconnu, catalogue conservé en dérivant la `RefData`),
    `MaterialPickerTest` (8 cas : libellés dans la datalist, provenance et version annoncées,
    bloc sans objet marqué, refus expliqué côté validateur, aucun faux positif sans relevé),
    `AgentActionExecutorTest` (+1 : le relevé rapporte bien objets, blocs sans objet et version).
    **Non couvert automatiquement** : le comportement JavaScript de la pagination de la liste —
    c'est l'objet du point 5 ci-dessus.

---

### TC-243 — Supprimer une quête et une story avec aperçu des conséquences (issue #194)

-   **Fonctionnalité testée :** bouton « Supprimer… », aperçu des dépendances, confirmation par
    identifiant retapé, nettoyage des références, blocages, sauvegarde, suppression côté serveur.
-   **Préconditions :** Control Panel et JAR de cette session déployés, serveur redémarré. Être
    connecté avec un rôle **OWNER** ou **ADMIN** (la permission est dédiée).
-   **IMPORTANT — n'utiliser que du contenu de test.** Les quêtes de test créées pour ce
    parcours portent le préfixe `tc243_`. **Ne pas** dérouler ce test sur `crystal_hunt`,
    `first_steps`, les quêtes du Garde ni `main_story`.

-   **A. Préparer le contenu de test (navigateur) :**
    1.  `/quests/new` → créer `tc243_base` (titre « TC243 base »), enregistrer.
    2.  `/quests/new` → créer `tc243_suite` avec **`tc243_base` en prérequis**, enregistrer.
    3.  `/stories/new` → créer `tc243_saga` enchaînant **`tc243_base` puis `tc243_suite`**,
        enregistrer.

-   **B. Aperçu et nettoyage des références :**
    4.  `/quests` → fiche `tc243_base` → **« Supprimer… »**. L'aperçu doit afficher :
        - « Où ce contenu existe » : source présente, et l'état côté serveur ;
        - « Références qui seront nettoyées » : le **prérequis** de `tc243_suite` **et** la
          **chaîne** de `tc243_saga`, en précisant qu'ils seront **réécrits, jamais supprimés** ;
        - « À savoir » : qu'**aucune progression de joueur n'est effacée**.
    5.  Taper une mauvaise confirmation (par exemple `oui`) → **refus**, l'aperçu revient, et
        **rien n'a changé** (vérifier que `tc243_base` est toujours au catalogue).
    6.  Taper `tc243_base` → confirmer. La page de résultat doit lister les fichiers réécrits, le
        fichier supprimé, et **le chemin de sauvegarde**.
    7.  Vérifier : `tc243_suite` existe **toujours** et n'a **plus** de prérequis ; `tc243_saga`
        existe **toujours** et n'enchaîne plus que `tc243_suite`. **Rien n'a été supprimé en
        cascade.**

-   **C. Blocage — story qui n'enchaîne plus qu'une quête :**
    8.  `/quests` → fiche `tc243_suite` → **« Supprimer… »**. L'aperçu doit **BLOQUER** : la story
        `tc243_saga` n'enchaîne plus que cette quête, et une chaîne vide est refusée par le moteur.
        Le message doit nommer le fichier et proposer quoi faire.
    9.  Vérifier qu'**aucun formulaire de confirmation n'est affiché** : il n'y a rien à cliquer.

-   **D. Blocage — dialogue qui référence la quête :**
    10. Prendre une quête de test référencée par un dialogue (ou ajouter dans un dialogue de test
        une action `START_QUEST` vers `tc243_suite`), puis rouvrir l'aperçu de suppression.
    11. Le blocage doit citer le **fichier du dialogue** et les **numéros de ligne**, et expliquer
        que retirer l'action laisserait un choix sans effet.

-   **E. Suppression d'une story :**
    12. `/stories` → fiche `tc243_saga` → **« Supprimer… »**. L'aperçu doit indiquer que les
        quêtes enchaînées **ne sont pas supprimées**, et ne lister aucune référence à nettoyer.
    13. Confirmer avec `tc243_saga`. Vérifier que `tc243_suite` est **toujours** au catalogue.

-   **F. Source et serveur :**
    14. Après chaque suppression d'un contenu que le serveur connaissait, la page de résultat doit
        annoncer que la suppression **côté serveur** a été demandée. Vérifier dans le **journal
        d'actions** que `content.definition.delete` est passée en **Succès**, avec le nom du
        fichier supprimé et sa sauvegarde.
    15. Cliquer **« Rafraîchir »** sur le catalogue : le contenu **ne doit pas réapparaître**.
    16. `/quests` → rouvrir l'aperçu d'une quête **embarquée** (par exemple `first_steps`) **sans
        confirmer** : l'aperçu doit prévenir qu'elle serait **recréée au démarrage** tant que le
        JAR déployé la contient. **Annuler** — ne pas supprimer une quête réelle.

-   **G. Annulation sans effet :**
    17. Ouvrir n'importe quel aperçu puis cliquer **« Annuler et revenir au catalogue »** :
        **aucune** modification ne doit avoir eu lieu.

-   **Nettoyage :** supprimer `tc243_suite` (après avoir retiré le blocage de l'étape C) et toute
    quête de test restante. Les sauvegardes restent dans
    `/var/lib/plugadmin/content-backups/` — elles sont hors du dépôt Git et du JAR, et peuvent
    être effacées à volonté.
-   **Couverture automatisée :** `ContentDeletionAnalyzerTest` (16 cas : nettoyage des prérequis et
    des chaînes, blocages story vide / dialogue / fichier illisible / lecture seule / sans
    sauvegarde, politiques annoncées), `ContentDeletionExecutorTest` (6 cas : ordre des écritures,
    sauvegardes hors des dossiers de contenu, plan bloqué qui n'écrit rien, conflit de version avec
    **rollback**, restauration fidèle), `ContentDeletionPagesTest` (9 cas : seule la saisie exacte
    confirme, aucun formulaire si blocage, permission dédiée refusée au rôle éditeur),
    `ContentDefinitionDeleterTest` (9 cas, plugin : fichier retrouvé par id déclaré et non par nom
    de fichier, sauvegarde avant suppression, types fermés), `BundledExamplesDriftTest` (3 cas :
    la copie des exemples embarqués est confrontée aux sources réelles du plugin),
    `AgentActionExecutorTest` (+3). **Non couvert automatiquement** : le parcours navigateur
    ci-dessus et l'exécution réelle de l'action agent sur le serveur.

---

### TC-244 — Signal visuel sur les PNJ : quête disponible et dialogue non lu (issue #12)

-   **Fonctionnalité testée :** particules discrètes propres à chaque joueur, réutilisation de la
    disponibilité réelle des quêtes, lecture persistante par nœud, limitation aux PNJ proches.
-   **Préconditions :** JAR de cette session déployé, serveur redémarré. `npc-hints.enabled: true`
    (défaut). **Deux joueurs** sont nécessaires pour l'étape D.
-   **IMPORTANT — contenu de test uniquement.** Tout ce qui est créé ici porte le préfixe
    `tc12_`. **Ne pas** dérouler ce test sur `first_steps`, `crystal_hunt`, les quêtes du Garde ni
    `main_story`, et ne pas réinitialiser de joueur.

-   **A. Préparer le contenu de test :**
    1.  Panel `/quests/new` → créer `tc12_dispo` (titre « TC12 disponible »), un objectif
        **Tuer une entité** `ZOMBIE` ×1, et dans **PNJ donneur** mettre l'identifiant du PNJ de
        test (voir 3). Enregistrer.
    2.  Panel `/quests/new` → créer `tc12_verrouillee` avec **`tc12_dispo` en prérequis** et le
        même PNJ donneur. Enregistrer.
    3.  En jeu, sur un PNJ de test : `/rpgadmin npc tag tc12_pnj --force` (id encore référencé
        nulle part, #66). Puis créer un dialogue
        `tc12_pnj` depuis `/dialogues` avec un nœud de départ et **au moins une branche**.
    4.  Déployer le contenu et redémarrer (les quêtes et dialogues ne se rechargent pas à chaud).

-   **B. Quête disponible, quête verrouillée :**
    5.  S'approcher du PNJ à moins de 16 blocs, en le regardant. **Attendu** : une particule verte
        discrète (`HAPPY_VILLAGER`) apparaît au-dessus de lui, environ une fois par seconde.
    6.  S'éloigner au-delà de 16 blocs. **Attendu** : plus aucune particule.
    7.  Se placer derrière un mur, à portée. **Attendu** : aucune particule (ligne de vue).
    8.  Accepter `tc12_dispo` auprès du PNJ, puis revenir devant lui. **Attendu** : le signal
        **quête disparaît** dans les ~5 s (la quête est active, et `tc12_verrouillee` est encore
        verrouillée par son prérequis). **C'est le point important** : une quête verrouillée ne
        doit jamais être signalée.
    9.  Terminer `tc12_dispo`. **Attendu** : le signal **quête revient**, puisque
        `tc12_verrouillee` devient réellement disponible.

-   **C. Dialogue non lu :**
    10. Avec un joueur n'ayant jamais parlé au PNJ et **aucune quête disponible** chez lui
        (accepter les deux quêtes d'abord) : **attendu** une particule de glyphes (`ENCHANT`).
    11. Parler au PNJ, lire **seulement** le nœud de départ, fermer. **Attendu** : le signal
        dialogue **persiste**, car une branche n'a pas été lue.
    12. Reparler et parcourir **toutes** les branches accessibles. **Attendu** : le signal
        dialogue **disparaît**.
    13. Se déconnecter, se reconnecter, revenir devant le PNJ. **Attendu** : toujours **aucun**
        signal dialogue — la lecture est persistante.
    14. Débloquer une branche jusque-là inaccessible (par exemple en remplissant sa condition).
        **Attendu** : le signal dialogue **réapparaît** tant que cette branche n'est pas lue.

-   **D. Deux joueurs, deux états (le cœur de l'exigence) :**
    15. Joueur A a tout lu et accepté ; joueur B n'a rien fait. Les deux se placent devant le même
        PNJ. **Attendu** : **B voit un signal, A n'en voit pas**. Le PNJ n'a pas changé de nom et
        n'a aucun effet visible pour les autres.

-   **E. Cas sans signal :**
    16. PNJ sans quête disponible et sans dialogue non lu : **aucune** particule.
    17. PNJ dans un autre monde, ou despawné : **aucune** particule, aucune erreur en console.
    18. `npc-hints.enabled: false` dans `config.yml` puis redémarrage : **aucune** particule, et la
        console indique « Signal visuel sur les PNJ désactivé ».

-   **F. Cadence et coût :**
    19. Observer `/tps` avec plusieurs PNJ à portée. **Attendu** : aucune dégradation manifeste.
        La cadence par défaut est **une passe par seconde et par joueur**, avec recalcul au plus
        toutes les 5 s — documenté dans `RPGQUEST_BIBLE.md` § « Signal visuel sur les PNJ ».
    20. Mettre `period-ticks: 1` dans la configuration puis redémarrer : la valeur doit être
        **ramenée à 10** (bornage), visible dans le log de démarrage.
    21. Mettre `quest-particle: PAS_UNE_PARTICULE` puis redémarrer : le serveur doit **refuser**
        la configuration avec un message nommant la clé. Remettre une valeur valide ensuite.

-   **Nettoyage :** supprimer `tc12_dispo`, `tc12_verrouillee` et le dialogue `tc12_pnj` (voir
    TC-243 pour la suppression), et retirer le tag du PNJ de test avec
    `/rpgadmin npc untag`. Aucun joueur à réinitialiser.
-   **Couverture automatisée :** `NpcHintConfigTest` (7 cas : bornage dans les deux sens, particule
    absente remplacée, interrupteurs intacts, bornage idempotent),
    `DialogueReadRepositoryTest` (9 cas : rien n'est lu par défaut, lecture par joueur et par nœud,
    idempotence, **survie à la fermeture/réouverture de la base**, reset d'un joueur sans effet sur
    un autre), `DialogueSessionEngineTest` (+6 cas : atteignabilité réelle selon les conditions,
    rétrécissement quand une condition cesse de passer, choix fermant non suivi, **ouvrir un PNJ ne
    marque que le nœud affiché**, parcourir une branche la marque aussi, absence d'observateur
    inoffensive), `QuestProgressEngineTest` (+6 cas : disponibilité d'une quête neuve, refus si
    active, quête inconnue, **prérequis manquants nommés**, aucune progression créée par une simple
    lecture, `ignorePrerequisites` ne saute que les prérequis), `SchemaMigratorTest` (version de
    schéma 24). **Non couvert automatiquement** : le rendu visuel réel des particules, la cadence
    en charge, et la différence entre deux joueurs en jeu — c'est l'objet des étapes B à F
    ci-dessus.

### TC-245 — Monde des claims : jamais d'entrée sans retour, jamais de blocage (issue #22)

-   **Fonctionnalité testée :** règle préventive d'entrée (destination de retour résolue + Pierre
    de retour réellement remise **avant** la téléportation), filet d'arrivée pour les autres
    chemins, bypass OP qui ne dispense plus de la Pierre, inventaire plein, choix de Jo accessible
    sans claim posé.
-   **Préconditions :** JAR de cette session déployé, serveur redémarré. World-Portal
    `world_hub → claims` en place. Jo lié (`/rpgadmin npc tag jo`) avec un `jo.yml` à jour — ce
    fichier **n'est pas** un exemple empaqueté, il doit être transféré explicitement.
-   **IMPORTANT :** aucune réinitialisation de joueur. Les étapes A et B se déroulent avec un
    **compte non opéré** ; l'étape C exige au contraire un compte **OP**.

-   **A. Entrée normale, retour garanti (compte non opéré, `CLAIM_TIER_1` débloqué) :**
    1.  Vider une place dans l'inventaire, puis franchir le portail Hub → claims.
        **Attendu** : la Pierre de retour est reçue **au Hub, avant le départ** (message
        « Tu reçois une Pierre de retour »), puis la téléportation a lieu.
    2.  Dans le monde des claims, clic droit avec la Pierre. **Attendu** : retour au village après
        la canalisation, l'objet **reste** dans l'inventaire.
    3.  Repasser le portail. **Attendu** : aucun second exemplaire (une seule Pierre).

-   **B. Inventaire plein = entrée refusée, pas un piège :**
    4.  Remplir **tous** les emplacements (36 cases) et franchir le portail.
        **Attendu** : **aucune téléportation**, message « Ton inventaire est plein : impossible de
        te remettre ta Pierre de retour » + « Libère un emplacement avant d'entrer ». **Aucun objet
        ne doit traîner au sol** au Hub.
    5.  Libérer une case, repasser le portail. **Attendu** : entrée normale comme en A.

-   **C. Compte OP : jamais bloqué, mais jamais sans retour :**
    6.  Avec un compte **OP** (`rpgquest.admin.world`), franchir le portail sans Pierre.
        **Attendu** : l'entrée est **autorisée** (le bypass n'est jamais refusé) **et** la Pierre
        de retour est reçue. Console : `[claims-access] … bypass … AUTORISÉE`.
    7.  Toujours en OP, se téléporter dans le monde des claims par `/mv tp claims` (hors portail),
        sans Pierre. **Attendu** : **aucun renvoi forcé** au Hub, mais une Pierre de retour est
        remise. Console : `[claims-safety] … bypass … aucun renvoi forcé, mais Pierre de retour
        garantie`. **C'est le correctif du blocage du 05/10** : avant, l'OP n'avait ni l'un ni
        l'autre.
    8.  Refaire l'étape 7 avec un inventaire plein. **Attendu** : la Pierre tombe **à ses pieds**
        et le message dit explicitement « est tombée à tes pieds — ton inventaire était plein ».

-   **D. Jo : recours accessible sans claim posé :**
    9.  Avec un joueur dont `CLAIM_TIER_1` est débloqué mais **qui n'a pas encore posé de claim**,
        jeter sa Pierre de retour puis parler à Jo. **Attendu** : le choix « Obtenir une Pierre de
        retour » est **visible** (il exigeait auparavant un claim déjà posé) et la remet.
    10. Reparler à Jo avec la Pierre en poche. **Attendu** : le choix a **disparu**
        (`LACKS_CUSTOM_ITEM`) — jamais de second exemplaire.

-   **E. Joueur non éligible (compte non opéré, `CLAIM_TIER_1` absent) :**
    11. Franchir le portail. **Attendu** : aucune téléportation, message d'orientation vers Jo /
        le Guide — comportement inchangé.

-   **Nettoyage :** aucun. Aucun contenu de test n'est créé.
-   **Couverture automatisée :** `ClaimWorldAccessGuardTest` (12 cas, dont les 7 de la règle de
    retour : Pierre remise avant la téléportation, jamais dupliquée, inventaire plein → refus avec
    motif et **aucun objet au sol au Hub**, propriétaire d'un claim soumis à la même règle sur sa
    branche synchrone, destination non résolue → refus, bypass jamais refusé même sans retour),
    `ClaimWorldSafetyListenerTest` (9 cas, dont bypass **jamais renvoyé mais toujours doté**, et
    les deux cas d'inventaire plein avec le message corrigé).
    **Non couvert automatiquement** : le comportement réel du World-Portal en jeu, la canalisation
    de la Pierre, le texte de Jo tel qu'affiché, et le fait qu'un compte OP du serveur de
    production porte bien `rpgquest.admin.world` — c'est l'objet des étapes A à E.

### TC-246 — Exploitation serveur : état, annonce, redémarrage vérifié, console (issue #95)

-   **Fonctionnalité testée :** page `/ops`, état réel et fraîcheur, annonce globale sur les trois
    canaux, redémarrage immédiat/différé avec annonces et annulation, suivi jusqu'au retour ONLINE,
    console récente avec recherche/filtres/pause, et affichage explicite des fonctions
    indisponibles.
-   **Préconditions :** panel déployé sur AWS, JAR de cette session déployé et serveur redémarré
    (la capture de console et les deux nouvelles actions arrivent avec le JAR). Accès RCON de la
    cible configuré (`ops.rcon.dev.host` + `RPGQUEST_RCON_PASSWORD_DEV`) — sinon l'étape C doit
    afficher « indisponible », ce qui est **aussi** un résultat valide à vérifier.
-   **IMPORTANT :** l'étape C **redémarre réellement le serveur** et déconnecte les joueurs. À
    faire quand personne ne joue. Aucun contenu n'est créé par ce test.

-   **A. État et fraîcheur :**
    1.  Ouvrir `/ops`. **Attendu** : quatre cartes — vivacité de l'agent (`ONLINE`), joueurs
        connectés, uptime du plugin, version — et la ligne « Fraîcheur » qui précise que ces
        valeurs datent du dernier heartbeat.
    2.  Comparer le nombre de joueurs avec `/list` en jeu. **Attendu** : même valeur (à un
        heartbeat près, ~20 s).
    3.  Arrêter l'agent (ou couper le réseau) et attendre ~3 min. **Attendu** : la carte passe
        `STALE` puis `OFFLINE`, et l'âge du relevé augmente. Rétablir ensuite.

-   **B. Annonce globale :**
    4.  Cliquer un **modèle rapide**. **Attendu** : le champ se remplit, le compteur de caractères
        et l'**aperçu** se mettent à jour.
    5.  Choisir **Chat**, envoyer. **Attendu** : en jeu, `[Serveur] <message>` dans le chat ; dans
        le panel, un résultat indiquant le nombre **réel** de destinataires.
    6.  Recommencer avec **Barre d'action**, puis **Titre plein écran**. **Attendu** : chaque canal
        s'affiche réellement comme annoncé.
    7.  Taper `/say coucou` et envoyer. **Attendu** : **refus** « Une annonce est un texte, pas une
        commande ». Rien ne part.
    8.  Taper `<click:run_command:/op moi>test</click>` et envoyer. **Attendu** : le texte s'affiche
        **tel quel** en jeu, non interprété, et **aucun** clic exécutable. C'est le point de
        sécurité du lot.
    9.  Coller 250 caractères. **Attendu** : le champ s'arrête à 200 ; en forçant côté serveur, le
        refus mentionne la longueur.
    10. Sans aucun joueur connecté, envoyer une annonce. **Attendu** : résultat « Aucun joueur
        connecté : l'annonce n'a été affichée à personne » — pas un succès trompeur.

-   **C. Redémarrage (déconnecte les joueurs) :**
    11. Si le bloc affiche « **Redémarrage indisponible** » : vérifier que le **motif** est affiché
        et qu'aucun bouton de redémarrage n'est proposé. Configurer alors l'accès RCON, recharger,
        et reprendre.
    12. Cocher la case et cliquer **Redémarrer dans 5 minutes**. **Attendu** : une carte
        d'opération apparaît (phase « Redémarrage programmé »), avec un bouton **Annuler**.
    13. Attendre l'annonce **T-1 min** en jeu. **Attendu** : « Redémarrage du serveur dans
        1 minute. » dans le chat.
    14. Cliquer **Annuler**. **Attendu** : phase « Redémarrage annulé », annonce « Redémarrage
        annulé. » en jeu, et **le serveur n'a pas été touché** (uptime inchangé sur `/ops`).
    15. Cliquer **Redémarrer maintenant** sans rien taper. **Attendu** : refus demandant de taper
        `REDEMARRER`.
    16. Taper `REDEMARRER` et valider. **Attendu** : la carte passe « Arrêt en cours » → « Attente
        du retour en ligne » → « **Serveur revenu en ligne (vérifié)** », sans recharger la page.
        Déplier les **étapes** : elles doivent mentionner « Serveur joignable avant l'arrêt »,
        « stop demandé », « Serveur hors ligne (arrêt effectif constaté) » puis « de nouveau en
        ligne ».
    17. Pendant la phase d'arrêt, essayer d'**annuler**. **Attendu** : refus « Trop tard : l'arrêt
        est déjà en cours ».
    18. Pendant l'opération, cliquer une seconde fois sur un bouton de redémarrage (ou rejouer le
        POST). **Attendu** : refus citant l'opération en cours — **jamais** un second arrêt.
    19. Vérifier l'**audit** (`/actions` ou le journal du panel) : l'opération, son auteur et son
        résultat y figurent.

-   **D. Console :**
    20. Faire apparaître des lignes (connexion d'un joueur, `/rpgquest version`, une commande
        Citizens). **Attendu** : elles apparaissent dans la console en moins d'une trentaine de
        secondes, préfixées par l'heure, le niveau et la source.
    21. Taper `Citizens` dans la recherche. **Attendu** : seules les lignes correspondantes
        restent, **immédiatement** (filtrage local, aucune requête).
    22. Décocher `INFO`. **Attendu** : seules `WARN` et `ERROR` restent.
    23. Cliquer **Pause**, provoquer des lignes, puis **Reprendre**. **Attendu** : rien ne bouge
        pendant la pause, puis les lignes accumulées apparaissent.
    24. Faire défiler vers le haut. **Attendu** : le **suivi auto** se désactive de lui-même (sinon
        la lecture serait impossible) ; **Aller en bas** le réactive.
    25. Vérifier qu'**aucune** zone de saisie n'existe dans la console.
    26. Si la console reste vide : vérifier que la page affiche un **motif** (capture indisponible,
        ou niveau de journalisation du serveur plus restrictif que `INFO`).

-   **E. Permissions (nécessite un second compte panel) :**
    27. Créer un compte `TESTER` (page `/users`). **Attendu** : il voit `/ops`, l'état et la
        console, mais **ni** le formulaire d'annonce **ni** les boutons de redémarrage.
    28. Créer un compte `READ_ONLY`. **Attendu** : il voit l'état, mais **pas** la console.
    29. Créer un compte `CONTENT_EDITOR`. **Attendu** : l'entrée « Exploitation » n'apparaît pas
        dans le menu, et `/ops` renvoie un **403** cohérent.
    30. Supprimer (désactiver) les comptes de test créés.

-   **F. Fonctions indisponibles :**
    31. Vérifier que la section « Non disponible dans ce lot » cite, avec leur **motif** :
        démarrage/arrêt explicite, console complète de l'hébergeur, sauvegarde restaurable, mode
        maintenance, et les tickets **#131** et **#210** comme lots suivants.

-   **Nettoyage :** désactiver les comptes panel créés à l'étape E. Aucun contenu de jeu n'est créé.
-   **Couverture automatisée :** `ServerLogBufferTest` (14 cas : capacité bornée, curseur, première
    lecture sur le **récent**, **trou de séquence signalé**, niveaux normalisés, troncature,
    concurrence), `ConsoleTapTest` (8 cas, dont **lignes d'autres plugins réellement captées**,
    message paramétré formaté, trace d'exception résumée, idempotence install/uninstall, et
    **niveau serveur restrictif signalé**), `ServerOpsServiceTest` (10 cas, dont **texte littéral
    jamais MiniMessage**, refus d'une commande, `NO_PLAYERS` honnête, les **trois canaux réellement
    délivrés**), `AgentActionExecutorTest` (+11 cas sur les deux actions),
    `RestartServiceTest` (24 cas : **serveur injoignable → aucun arrêt**, **jamais vu hors ligne →
    échec**, uptime décroissant comme preuve, annulation avant/pendant, single-flight, annonces non
    envoyées après annulation), `RconClientTest` (7 cas contre un **vrai serveur RCON** ouvert dans
    la JVM de test : protocole binaire, mot de passe refusé sans écho du secret, connexion coupée
    par le `stop`), `AgentActionCatalogTest` (+11 cas), `RolePermissionMatrixTest` (+5 cas),
    `OpsPageTest` (18 cas de bout en bout : auth, 401 sur les endpoints JSON, CSRF, état et
    fraîcheur, **indisponibilité affichée avec motif**, un seul relevé en vol à la fois).
    **Non couvert automatiquement** : le rendu réel des trois canaux en jeu, un vrai redémarrage de
    bout en bout contre VeryGames, l'ergonomie de la console sous charge, et les permissions vues
    depuis de vrais comptes — c'est l'objet des étapes A à F.

### TC-247 — Rechargement du contenu depuis PlugAdmin (issue #131)

-   **Fonctionnalité testée :** les trois états source/publié/chargé, l'aperçu, le rechargement par
    famille, le contenu lié, la conservation du runtime en cas d'erreur, et la préservation de la
    progression et des instances vivantes.
-   **Préconditions :** JAR de cette session déployé, serveur redémarré, panel déployé. Rôle
    `OWNER` ou `ADMIN`.
-   **IMPORTANT — contenu de test uniquement.** Tout ce qui est créé ici porte le préfixe
    `tc131_`. **Ne pas** toucher `first_steps`, `crystal_hunt`, les quêtes du Garde, `main_story`
    ni les contenus du propriétaire.

-   **A. Les trois états :**
    1.  Panel `/ops` → bloc **Rechargement du contenu**. **Attendu** : les trois états sont
        énoncés, et la phrase disant qu'un rechargement **ne transfère rien** depuis AWS.
    2.  Créer une quête `tc131_etat` depuis `/quests/new`. **Attendu** : badge « Source uniquement »
        sur `/quests`, et une **action contextuelle** apparaît avec l'explication des deux causes.
    3.  Cliquer **Aperçu** depuis cette action. **Attendu** : le résultat **ne contient pas**
        `rpgquest:tc131_etat` dans les identifiants de la famille `quests` — le fichier n'a jamais
        été publié sur VeryGames. C'est le cas « déploiement requis ».
    4.  Déployer le contenu sur le serveur, **sans** redémarrer.
    5.  Cliquer **Aperçu** de nouveau. **Attendu** : `rpgquest:tc131_etat` **apparaît** désormais —
        le fichier est publié mais pas chargé. C'est le cas « rechargement suffit ».
    6.  Cliquer **Recharger en jeu**. **Attendu** : succès, et le badge « Source uniquement »
        **disparaît sans F5 manuel** (les catalogues sont ré-enfilés automatiquement).
    7.  En jeu, `/rpgadmin quest list` (ou le PNJ donneur). **Attendu** : la quête est réellement
        utilisable.

-   **B. Erreur de contenu : le runtime précédent survit :**
    8.  Sur le serveur, rendre `tc131_etat.yml` volontairement invalide (par exemple supprimer la
        ligne `title:`).
    9.  Cliquer **Aperçu**. **Attendu** : erreur listée avec le **nom du fichier**, et rien
        d'appliqué.
    10. Cliquer **Recharger en jeu**. **Attendu** : **refus**, message « le runtime précédent est
        conservé », et en jeu la quête `tc131_etat` est **toujours chargée** (l'ancienne version).
        **C'est le point le plus important du lot** : un contenu cassé ne doit pas faire disparaître
        une définition déjà chargée.
    11. Réparer le fichier, recharger. **Attendu** : succès.

-   **C. Contenu lié :**
    12. Créer une quête `tc131_liee` et une story `tc131_story` qui la contient. Déployer les deux.
    13. Cocher **uniquement** « Stories » et cliquer **Recharger en jeu**. **Attendu** : **refus**
        « références croisées cassées », avec le message indiquant de recharger aussi **Quêtes**.
    14. Cocher « Stories » **et** « Quêtes », recharger. **Attendu** : succès.

-   **D. Préservation (le cœur de la sécurité) :**
    15. Avec un joueur de test, accepter `tc131_etat` et progresser partiellement.
    16. Recharger la famille **Quêtes**. **Attendu** : la progression du joueur est **inchangée**
        (`/quests` en jeu), aucune récompense n'a été redistribuée, et la quête reste active.
    17. Faire apparaître un mob spécial de test, puis recharger la famille **Mobs**. **Attendu** :
        l'instance vivante est **toujours là**, aucun despawn, aucun respawn.
    18. Ouvrir un dialogue avec un PNJ, laisser la fenêtre ouverte, et recharger **Dialogues**
        depuis le panel. **Attendu** : aucun crash ; au pire le dialogue se ferme proprement.

-   **E. Opérations concurrentes et commande en jeu :**
    19. Lancer deux rechargements très rapprochés. **Attendu** : le second est **refusé**
        (« déjà en cours »), jamais mis en file.
    20. En jeu : `/rpgadmin content preview`. **Attendu** : même résultat structuré que le panel.
    21. En jeu : `/rpgadmin mob reload`. **Attendu** : il passe désormais par le service central —
        donc un profil invalide **refuse** le rechargement au lieu de le retirer du runtime.
    22. Vérifier l'**audit** (`/actions`) : les rechargements et leur résultat y figurent.

-   **F. Paramètres :**
    23. Modifier une valeur de `config.yml` et tenter un rechargement. **Attendu** : la page indique
        clairement que les paramètres exigent un **redémarrage** (workflow #95), et ne prétend pas
        les avoir appliqués.

-   **Nettoyage :** supprimer `tc131_etat`, `tc131_liee` et `tc131_story` (voir TC-243). Aucun
    joueur à réinitialiser.
-   **Couverture automatisée :** `ContentReloadServiceTest` (23 cas, registres **réels** sur
    fichiers temporaires : aperçu qui ne touche rien, **fichier invalide qui ne retire jamais une
    définition déjà chargée**, erreur dans une famille qui bloque toute la demande, références
    croisées story→quête / dialogue→quête / PNJ→dialogue / quête→donneur, **familles liées qui
    réussissent ensemble**, famille non rechargée jugée sur son état runtime réel, ordre de
    dépendance indépendant de l'ordre de la demande, empreinte stable et insensible aux noms de
    fichiers, single-flight, aperçu autorisé pendant un rechargement, identifiants du **disque
    serveur**), `AgentActionExecutorTest` (+7 cas), `ContentReloadPageTest` (10 cas : trois états
    affichés, avertissement AWS, familles réordonnées, confirmation exigée, famille inconnue
    refusée, **ré-enfilement des six catalogues** au succès et **aucun** à l'échec).
    **Non couvert automatiquement** : le déploiement réel entre AWS et VeryGames, la préservation
    constatée en jeu, et le comportement sous dialogue ouvert — c'est l'objet des étapes A à F.

---

### TC-248 — OP/DEOP et actions de secours sur un joueur (issue #210)

-   **Fonctionnalité testée :** statut OP réel, OP/DEOP avec une seule case à cocher (#210, 13/10/2026),
    renvoi au Hub, expulsion, whitelist, frontière de permission, et comportement hors ligne.
-   **Préconditions :** JAR de cette session déployé, panel déployé.
-   **IMPORTANT — identité de test.** Utiliser un **compte de test dédié**. **Ne pas** modifier les
    droits du compte du propriétaire pour tester, et **restaurer** les droits du compte de test à la
    fin. Aucune élévation d'un joueur réel comme test implicite.

-   **A. Statut OP réel :**
    1.  Fiche du joueur de test sur `/players` → section **Modération**. **Attendu** : une ligne
        « OP Minecraft » reflétant l'état réel, et une ligne « Whitelist ».
    2.  En jeu, `/op <joueur_test>` depuis la console. Rafraîchir l'annuaire dans le panel.
        **Attendu** : la fiche affiche **Opérateur** — l'état vient du serveur, pas du panel.
    3.  `/deop <joueur_test>`, rafraîchir. **Attendu** : la fiche repasse à « non ».

-   **B. OP/DEOP depuis le panel :**
    4.  Ouvrir la fiche de **LoDyMcFly** → zone OP. **Attendu** : « Joueur ciblé : LoDyMcFly » (UUID en
        information), l'action en clair, une raison **facultative**, **une case** non cochée, aucun
        champ à retaper.
    5.  Cliquer **Accorder OP** sans cocher. **Attendu** : refus « Confirmation », rien d'envoyé.
    6.  Cocher la case et valider (double-cliquer). **Attendu** : un seul envoi ; succès ; le résultat
        rappelle que **seul OP Minecraft** a changé. Vérifier en jeu `/op`-status.
    7.  Recliquer **Accorder OP** (rejeu). **Attendu** : « était déjà OP », pas une erreur.
    8.  **Retirer OP**, puis vérifier en jeu et après un **redémarrage** que l'état a persisté.
    9.  Vérifier que le **rôle PlugAdmin** du compte de test est inchangé (`/users`), que son droit
        de construction est inchangé, et qu'aucun bypass de gameplay ne s'est activé.

-   **C. Frontière de permission (le point de sécurité) :**
    10. Créer un compte panel de rôle **ADMIN**. **Attendu** : il voit la fiche joueur mais
        **aucun** bouton OP/DEOP.
    11. Depuis ce compte ADMIN, poster directement la route `/agents/action` avec
        `type=player.op`. **Attendu** : **403** cohérent — masquer le bouton n'est pas la sécurité.
    12. Désactiver le compte de test créé.

-   **D. Renvoi au Hub :**
    13. Joueur de test connecté, dans le monde des claims ou le Wild. Cliquer **Renvoyer au Hub**.
        **Attendu** : il arrive à une position sûre du Hub ; **inventaire, Acte, claim et
        progression intacts**.
    14. Joueur de test **déconnecté**. **Attendu** : l'action est affichée **indisponible** avec son
        motif, et ne prétend pas avoir agi.
    15. Vérifier dans le résultat la mention de préservation.

-   **E. Expulsion :**
    16. Joueur de test connecté → **Expulser** avec une raison. **Attendu** : il est déconnecté et
        **voit la raison**. Il peut se reconnecter (une expulsion n'est pas un bannissement).
    17. Sans raison. **Attendu** : refus.
    18. Joueur déconnecté. **Attendu** : action indisponible avec motif.

-   **F. Whitelist :**
    19. **Ajouter à la whitelist** le joueur de test. **Attendu** : succès, et le résultat précise
        si la whitelist est **réellement appliquée** par le serveur.
    20. Si la whitelist est désactivée, vérifier que le message **avertit** que la liste n'a aucun
        effet en l'état.
    21. **Retirer de la whitelist**, puis recliquer. **Attendu** : « n'était pas sur la whitelist ».
    22. Vérifier que ces deux actions fonctionnent **joueur hors ligne**.

-   **G. Audit :**
    23. Vérifier `/actions` : chaque opération figure avec son auteur, sa cible, sa raison et son
        résultat.

-   **Nettoyage :** retirer OP et la whitelist du compte de test, désactiver les comptes panel
    créés à l'étape C. **Aucun droit du propriétaire ne doit avoir été modifié.**
-   **Couverture automatisée :** `PlayerAdminActionsTest` (17 cas : OP accordé/retiré et **relu**,
    idempotence dans les deux sens, UUID jamais vu **refusé**, **jamais de faux succès** quand
    l'écriture ne prend pas, renvoi au Hub qui déplace réellement et **préserve l'inventaire**,
    hors ligne refusé, destination non résolue refusée sans déplacer, expulsion avec raison et
    repli lisible, whitelist appliquée/relue/idempotente, et **avertissement quand la whitelist est
    désactivée**), `PlayerAdminPageTest` (11 cas : **OWNER seul peut élever**, ADMIN explicitement
    exclu, réutilisation de `PLAYER_MODERATE`, sensibilité et confirmation de chaque action,
    ré-enfilement de l'annuaire, raison obligatoire, **confirmation par l'identité exacte**).
    **Non couvert automatiquement** : l'élévation OP d'un joueur **hors ligne** (non reproductible
    sous MockBukkit — `setOp` n'y persiste pas après déconnexion ; sur un vrai serveur Bukkit écrit
    dans `ops.json`), la persistance après redémarrage, le 403 sur route directe depuis un vrai
    compte ADMIN, et le rendu en jeu — c'est l'objet des étapes A à G.

### TC-249 — Administration de la monnaie depuis PlugAdmin (issue #140, premier lot économie)

-   **Fonctionnalité testée :** lecture du solde réel et du journal des transactions, crédit et
    débit avec raison, permissions séparées lecture/écriture, refus d'un débit au-delà du
    disponible, et persistance après redémarrage.
-   **Préconditions :** JAR de cette session déployé, serveur redémarré, panel déployé.
-   **IMPORTANT — compte de test dédié.** **Ne créditez ni ne débitez jamais votre propre compte
    pour essayer.** Utilisez un joueur de test. Aucun solde existant n'est converti ni migré par ce
    lot.

-   **A. Lecture :**
    1.  Fiche du joueur de test sur `/players` → bouton **Monnaie**. **Attendu** : le bloc indique
        que le solde vient du **portefeuille persistant**, seule source de vérité, et qu'aucun objet
        d'inventaire n'est compté comme de la monnaie.
    2.  Cliquer **Lire le solde et le journal**. **Attendu** : le solde réel s'affiche, avec les
        dernières transactions.
    3.  Comparer avec `/money` en jeu. **Attendu** : même valeur.

-   **B. Crédit :**
    4.  Créditer 250 **sans** raison. **Attendu** : **refus** mentionnant le journal.
    5.  Créditer 250 avec la raison « test TC-249 ». **Attendu** : succès, et le message montre
        **solde avant → après**.
    6.  Relire le journal. **Attendu** : une ligne `ADMIN_GRANT`, montant **+250**, contexte
        contenant la raison.
    7.  Saisir un montant de `1000001`. **Attendu** : refus « montant trop élevé » — garde-fou de
        saisie, pas une règle d'équilibrage.
    8.  Saisir `0` puis `-5`. **Attendu** : refus dans les deux cas.

-   **C. Débit et plancher à zéro (le point de sécurité) :**
    9.  Débiter 100 avec une raison. **Attendu** : succès, solde avant → après cohérent, ligne
        `ADMIN_TAKE` **négative** au journal.
    10. Débiter un montant **supérieur au solde**. **Attendu** : **échec lisible** « fonds
        insuffisants », le solde réel est affiché, et **rien n'a changé**. Relire le journal :
        **aucune ligne** n'a été ajoutée pour cette tentative.
    11. Vérifier en jeu que le solde correspond.

-   **D. Permissions :**
    12. Avec un compte panel **TESTER** : **Attendu** : le bloc Monnaie s'ouvre et le solde est
        lisible, mais la page indique que le rôle ne permet pas de modifier — **aucun** formulaire
        de crédit ni de débit.
    13. Depuis ce compte TESTER, poster directement `type=economy.credit`. **Attendu** : **403**.
    14. Avec un compte **CONTENT_EDITOR** : aucun accès à la monnaie.
    15. Désactiver les comptes de test créés.

-   **E. Persistance et absence de duplication :**
    16. Noter le solde, puis **redémarrer** le serveur (workflow #95). **Attendu** : solde
        identique, journal intact, aucune ligne dupliquée.
    17. Déconnecter/reconnecter le joueur de test. **Attendu** : solde inchangé.

-   **F. Audit :**
    18. Vérifier `/actions` : chaque crédit et débit figure avec son auteur, sa cible, son montant
        et sa raison.

-   **Nettoyage :** remettre le solde du compte de test à sa valeur d'origine par une opération
    inverse (le journal gardera trace des deux — c'est voulu), et désactiver les comptes panel
    créés.
-   **Couverture automatisée :** `WalletRepositoryTest` (+6 cas sur le journal : journal vide au
    départ, **ligne signée** pour chaque crédit et débit, **aucune ligne pour un débit refusé**,
    bornes dures de la lecture, journal **par joueur** sans fuite, et **survie à la réouverture de
    la base**), `AgentActionExecutorTest` (+8 cas : montants signés transmis, limite de journal
    bornée avec défaut, raison obligatoire, montant invalide ou excessif refusé **avant** d'atteindre
    le serveur, fonds insuffisants rapportés comme **échec** et non comme succès),
    `EconomyAdminTest` (13 cas : **deux permissions distinctes**, TESTER qui lit sans créer,
    rôles de contenu sans aucun accès, sensibilité et confirmation, raison obligatoire, plafond de
    saisie, bornes du journal).
    **Non couvert automatiquement** : la cohérence avec `/money` en jeu, le 403 depuis un vrai
    compte TESTER, et la persistance après un vrai redémarrage — c'est l'objet des étapes A à F.

---

### TC-250 — Récompense monétaire de quête et solde dans l'interface joueur (issue #16)

-   **Fonctionnalité testée :** nouveau type de récompense `MONEY` (YAML, éditeur de quête du
    panel, validation backend), crédit **unique** à la remise, confirmation lisible, solde visible
    dans le journal de quêtes, et persistance après redémarrage.
-   **Préconditions :** JAR de cette session déployé, serveur redémarré, panel déployé.
-   **IMPORTANT — contenu et identités de test uniquement.**
    -   Ne posez **aucun** montant sur une quête réelle. Les deux quêtes de test sont fournies :
        `docs/manual-tests/rewards/test_money_reward.yml` et `test_money_repeatable.yml`.
    -   **Ne créditez ni ne débitez votre propre compte.** Utilisez un joueur de test.
    -   Les montants des fichiers de test sont des valeurs de **test**, pas des décisions
        d'équilibrage économique.

-   **A. Éditeur de quête du panel (le chemin que vous utiliserez vraiment) :**
    1.  `/quests` → **Nouvelle quête**. Dans « Récompenses », ouvrir la liste des types.
        **Attendu** : un type **« Pièces (monnaie) »** apparaît, avec une icône de pièce, et son
        aide dit que le crédit va au **portefeuille persistant**, qu'**aucun objet n'est donné** et
        qu'aucun objet d'inventaire n'est compté comme de la monnaie.
    2.  Ajouter cette récompense, saisir `250`, enregistrer. **Attendu** : enregistrement accepté,
        et la bannière dit explicitement **source → publié → rechargé** (et non « au prochain
        chargement du serveur »).
    3.  Rouvrir la quête. **Attendu** : la récompense monétaire est toujours là avec `250` — rien
        n'a été perdu ni transformé.
    4.  Ajouter aussi une récompense d'**XP** et une d'**objet**, enregistrer, rouvrir.
        **Attendu** : les trois récompenses sont présentes, **dans le même ordre**.
    5.  Mettre le montant à `0`, puis `-5`, puis `abc`. **Attendu** : à chaque fois une **erreur**
        de validation côté serveur, l'enregistrement est refusé.
    6.  Mettre `1000001`. **Attendu** : un **avertissement** « inhabituellement élevé » — mais
        l'enregistrement **reste possible** : l'équilibrage est votre décision, pas celle du panel.
    7.  Retirer la récompense monétaire, enregistrer, rouvrir. **Attendu** : elle a disparu, les
        autres récompenses sont intactes.
    8.  Supprimer la quête de test créée à l'étape 1 (bouton « Supprimer… », #194).

-   **B. États source / publié / chargé (workflow #131) :**
    9.  Copier `test_money_reward.yml` dans `plugins/RPGQuest/quests/` sur le serveur, **sans**
        redémarrer.
    10. Sur `/quests`, lancer **Aperçu** puis **Recharger en jeu**. **Attendu** : le rechargement
        réussit et la quête devient active sans redémarrage.
    11. **Point important** : vérifier qu'une quête enregistrée **uniquement** dans la source (non
        publiée sur le serveur) est bien signalée comme telle, et qu'un rechargement **ne la rend
        pas** active — un reload ne transporte aucun fichier.

-   **C. Crédit réel en jeu (le cœur du test) :**
    12. Avec le joueur de test, noter le solde (`/money`).
    13. Accepter puis terminer `rpgquest:test_money_reward` (casser 1 bloc de terre).
        **Attendu** : le Title « Quête terminée », puis **un court instant après**, un message de
        chat du type « Récompense de … : **+100 pièce(s)** — solde : <nouveau solde> ».
    14. **Attendu** : le solde cité est le solde **réel** (`/money` donne la même valeur), et il
        vaut exactement l'ancien **+100**.
    15. **Attendu** : le message arrive dans le **chat**, et l'affichage de progression des
        objectifs (ActionBar) n'a **pas** été écrasé durablement.
    16. Ouvrir le journal (`/quests`). **Attendu** : la **bourse** est visible en haut à droite de
        la liste (et aussi dans la vue détail d'une quête), avec le solde réel.
    17. Ouvrir les détails de la quête. **Attendu** : la récompense apparaît comme **« 100
        pièce(s) »**.

-   **D. Crédit unique — la garantie à ne pas manquer :**
    18. Retenter de terminer la même quête non répétable (recasser un bloc). **Attendu** : **aucun
        second crédit**, aucun second message de gain.
    19. Depuis le panel, forcer la complétion de cette quête (`/players` → action quête).
        **Attendu** : « déjà terminée — aucune récompense re-créditée », et le solde **ne bouge
        pas**.
    20. Cliquer **deux fois très vite** sur l'action de complétion forcée. **Attendu** : un seul
        crédit au maximum, jamais deux.
    21. Dans le panel, lire le **journal des transactions** du joueur de test. **Attendu** : **une
        seule** ligne `QUEST_REWARD`, dont le contexte cite la quête.

-   **E. Quête répétable :**
    22. Publier et recharger `test_money_repeatable.yml`.
    23. Terminer la quête, **reprendre** la quête, la terminer à nouveau. **Attendu** : **deux**
        crédits de 25, deux messages, **deux** lignes `QUEST_REWARD` au journal.
    24. **Attendu** : le solde final vaut l'ancien **+50**.

-   **F. Persistance et redémarrage :**
    25. Noter le solde, **redémarrer** le serveur (workflow #95). **Attendu** : solde identique,
        aucune ligne dupliquée au journal, aucune récompense re-créditée au redémarrage.
    26. Déconnecter/reconnecter le joueur de test, rouvrir le journal. **Attendu** : solde
        inchangé, bourse correcte.

-   **G. Erreur honnête (optionnel, à faire seulement si vous savez revenir en arrière) :**
    27. Si vous pouvez provoquer une indisponibilité de la base, terminer une quête monétaire.
        **Attendu** : un message **explicite** disant que la récompense n'a pas pu être créditée —
        et **jamais** un message de gain. Le joueur ne doit pas croire avoir été payé.

-   **Nettoyage :** retirer `test_money_reward.yml` et `test_money_repeatable.yml` de
    `plugins/RPGQuest/quests/`, recharger (#131), supprimer la quête créée à l'étape 1 si elle
    existe encore, et remettre le solde du joueur de test à sa valeur d'origine par une opération
    inverse depuis le panel avec une raison (le journal gardera trace des deux — c'est voulu).
-   **Couverture automatisée :** `WalletRepositoryTest` (+8 cas : crédit unique tracé au journal,
    **rejeu du même grant qui ne recrédite rien**, rejeu avec un montant différent qui rapporte le
    montant **réellement** payé, nouvelle occurrence payée pour une quête répétable, occurrences
    comptées par joueur **et** par quête, montant non positif et `grantId` manquant refusés,
    addition à un solde existant, **survie à un redémarrage sans second paiement**, et **20 rejeux
    concurrents qui créditent exactement une fois**) ; `SchemaMigratorTest` (+2 : table créée, et
    **la contrainte d'unicité refuse deux lignes pour la même occasion**) ;
    `QuestProgressEngineTest` (+7 : une seule demande de paiement par complétion, message de succès
    **après** confirmation et portant le solde du reçu, échec dit honnêtement, occasion déjà payée
    silencieuse, aucune ligne monétaire dans le résumé synchrone, occasions distinctes pour une
    quête répétable, aucune sollicitation de l'économie sans récompense monétaire) ;
    `QuestMoneyRewardIntegrationTest` (5 cas de bout en bout avec un **vrai** portefeuille SQLite) ;
    `QuestDefinitionParserTest` (+4), `QuestJournalServiceTest` (+5 : solde réel, bourse en vue
    détail, portefeuille vierge affiché `0` et non une panne, slot inerte, récompense prévue dans
    l'infobulle), `ManualTestQuestPackTest` (+3), `EditorDescriptorsTest` (+2),
    `ContentYamlRoundTripTest` (+4), `RewardTextTest` (+3).
    **Non couvert automatiquement** : le rendu visuel réel de la bourse et des infobulles en jeu,
    l'ordre perçu des messages, le parcours de l'éditeur dans un navigateur, et la persistance
    après un vrai redémarrage — c'est l'objet des étapes A à G.

---

### TC-251 — Récupération des récompenses monétaires après échec ou crash (issue #16, second lot)

-   **Fonctionnalité testée :** une récompense monétaire de quête qui n'a pas pu être créditée reste
    **enregistrée** (joueur, quête, occurrence, montant, état), est **reprise automatiquement** à la
    connexion suivante avec son **identité de paiement initiale**, et peut être reprise ou marquée
    « réglée à la main » depuis le panel — sans jamais pouvoir être payée deux fois.
-   **Préconditions :** JAR de cette session déployé, serveur redémarré, panel déployé.
-   **IMPORTANT — identités et contenus de test uniquement.**
    -   **Ne provoquez aucune panne sur la base DEV réelle.** La section E est la seule à simuler un
        échec, et elle se fait **hors du serveur de jeu** (voir son encadré) ; si vous ne pouvez pas
        la faire sans risque, **sautez-la** : elle est couverte automatiquement.
    -   Utilisez un joueur de test. **Ne créditez ni ne débitez votre propre compte.**
    -   Contenus de test : `docs/manual-tests/rewards/test_money_reward.yml` et
        `test_money_repeatable.yml`, plus la quête à **deux** récompenses décrite en section B.
    -   Compte panel de test : un compte **TESTER** (lecture) et un compte **ADMIN** ou **OWNER**
        (écriture). Désactivez-les à la fin.

-   **A. Le cas normal ne change pas (non-régression) :**
    1.  Publier et recharger `test_money_reward.yml`, puis terminer la quête avec le joueur de test.
        **Attendu** : message de gain `+100 pièce(s)` avec le solde réel, exactement comme avant.
    2.  Fiche joueur → **Récompenses en attente** → **Lire les récompenses en attente**.
        **Attendu** : « Aucune récompense monétaire en attente » — la dette a été créée **et** payée.
    3.  Lire le journal des transactions. **Attendu** : **une seule** ligne `QUEST_REWARD`, dont le
        contexte cite la quête **et** l'occasion.

-   **B. Plusieurs récompenses monétaires sur une même complétion (le défaut corrigé) :**
    4.  Créer depuis le panel une quête de test avec **deux** récompenses « Pièces » : `100` et `30`.
        La publier et la recharger.
    5.  La terminer avec le joueur de test. **Attendu** : le solde augmente de **130**, pas de 100.
    6.  **Attendu** : **deux** lignes `QUEST_REWARD` au journal, et **deux** messages de gain.
    7.  Relire les récompenses en attente. **Attendu** : aucune.
    8.  Supprimer la quête de test (bouton « Supprimer… »).

-   **C. Reprise automatique à la connexion :**
    9.  Avec le joueur de test, terminer `test_money_reward.yml` **puis se déconnecter
        immédiatement** (dans la seconde). **Attendu** : selon le moment, soit il a vu son message,
        soit non.
    10. Se reconnecter et lire les récompenses en attente dans le panel. **Attendu** : si la
        récompense avait été payée, rien n'apparaît et le solde est correct ; si elle ne l'avait pas
        été, elle a été **reprise à la connexion** et le solde est correct **sans double crédit**.
    11. **Point important** : dans les deux cas, le journal ne doit contenir **qu'une** ligne pour
        cette complétion. C'est la propriété à vérifier, plus que le message vu ou non.

-   **D. Reprise et règlement manuel depuis le panel :**
    12. Fiche joueur → **Récompenses en attente**. **Attendu** : la page distingue explicitement
        **« Reprendre »** (paie le montant enregistré, aucun montant à saisir) de **« Créditer
        manuellement »** (compensation libre qui ne règle aucune dette), et affiche la **limite
        historique** (rien d'antérieur à cette mise à jour ne peut apparaître).
    13. S'il existe une dette (créée en section E, ou aucune — dans ce cas passer en 16) : cliquer
        **Reprendre le paiement**. **Attendu** : succès, montant **exactement** celui enregistré,
        nouveau solde affiché, et la dette disparaît de la liste.
    14. Recliquer **Reprendre le paiement** sur la même dette (bouton encore visible avant
        actualisation). **Attendu** : **échec lisible** « Déjà réglée : aucun second crédit », et le
        solde **ne bouge pas**.
    15. Cliquer **deux fois très vite** sur une reprise. **Attendu** : un seul crédit au maximum.
    16. Sur une dette en attente : marquer **Réglée à la main** **sans** raison. **Attendu** :
        refus. Avec une raison. **Attendu** : succès, **aucun solde modifié**, et la dette n'est
        plus payable — recliquer « Reprendre » donne « Déjà réglée ».
    17. Avec un compte **TESTER** : **Attendu** : la liste est lisible, mais **aucun** bouton de
        reprise ni de règlement. Poster directement `type=economy.debt.retry` depuis ce compte :
        **403**.

-   **E. Échec réel de paiement (optionnelle — à ne faire que sur une instance jetable) :**
    > ⚠️ **Ne faites pas cette section sur la base DEV réelle.** Elle n'a de sens que sur une
    > instance de test dont la perte est acceptable. Elle est entièrement couverte par les tests
    > automatisés (base SQLite rouverte, échecs SQL, reprises concurrentes). **Sauter cette section
    > ne laisse aucun trou de validation.**
    18. Sur une instance jetable, rendre la base inaccessible en écriture juste après une
        complétion monétaire. **Attendu** : message d'échec **explicite** au joueur, **jamais** un
        message de gain.
    19. Rétablir la base, reconnecter le joueur. **Attendu** : la récompense est **reprise**, le
        montant est celui d'origine, et le journal ne contient qu'une ligne.
    20. Dans le panel, la dette affiche **« En échec (N tentative(s)) »** avec le **motif réel**, et
        non un simple « en attente ».

-   **F. Quête répétable et modification de définition :**
    21. Publier et recharger `test_money_repeatable.yml` (25 pièces). Terminer, reprendre, terminer
        à nouveau. **Attendu** : **deux** crédits de 25, deux occurrences distinctes.
    22. Modifier le montant de la quête à `999`, publier, recharger. **Attendu** : les complétions
        **déjà faites** ne changent pas de montant ; seules les suivantes valent 999.

-   **G. Redémarrage :**
    23. Noter le solde et la liste des récompenses en attente. **Redémarrer** le serveur (workflow
        #95). **Attendu** : solde identique, aucune ligne dupliquée, et toute dette encore en
        attente est **toujours là** avec son montant — elle n'a pas été inventée ni effacée.
    24. **Attendu** : aucune récompense d'une quête terminée **avant** cette mise à jour n'apparaît
        et aucun paiement rétroactif n'a eu lieu.

-   **Nettoyage :** retirer les fichiers de test de `plugins/RPGQuest/quests/` et recharger (#131) ;
    supprimer la quête à deux récompenses créée en section B ; régler ou reprendre toute dette de
    test restante pour ne pas laisser de ligne en attente ; remettre le solde du joueur de test à
    sa valeur d'origine par une opération inverse avec une raison ; désactiver les comptes panel de
    test.
-   **Couverture automatisée :** `WalletRepositoryTest` (**19 cas** sur ce mécanisme, avec une vraie
    base SQLite **rouverte** : dette créée avant tout paiement, complétion et dette écrites
    **ensemble**, paiement unique avec sa ligne de journal, rejeu sans second crédit, **plusieurs
    récompenses = plusieurs dettes distinctes**, **survie au redémarrage puis paiement unique**,
    dette payée qui reste payée après redémarrage, **montant pris sur la dette et non sur la
    définition**, répétable qui crée une occurrence neuve, ré-enregistrement sans doublon, **20
    reprises concurrentes qui créditent exactement une fois**, dette inconnue jamais payée, échec
    enregistré avec son motif qui **borne** les reprises, échec tardif sans effet sur une dette
    payée, **règlement manuel qui rend la dette non payable sans toucher au solde**, raison
    obligatoire, bornes de lecture par joueur et globales, complétion sans argent qui ne crée aucune
    dette, jeton manquant refusé) ; `QuestProgressEngineTest` (**12 cas** : une demande par
    complétion avec l'identité écrite en base, **deux identités distinctes pour deux récompenses**,
    message de succès seulement après confirmation, échec dit honnêtement, échec **enregistré** pour
    borner les reprises, occasion déjà payée silencieuse, aucune ligne monétaire dans le résumé
    synchrone, occasions distinctes pour une répétable, économie jamais sollicitée sans récompense
    monétaire, **reprise au chargement du joueur avec l'identité initiale**, **dette trop souvent en
    échec laissée au panel**, **reprise qui ne rejoue aucune récompense non monétaire**) ;
    `QuestMoneyRewardIntegrationTest` (6 cas de bout en bout, dont **100 + 30 = 130**) ;
    `SchemaMigratorTest`, `AgentActionExecutorTest` (**6 cas** : relevé complet, bornes, reprise qui
    **ignore tout montant soumis**, reprise sans paiement rapportée comme **échec**, identité
    obligatoire, raison obligatoire) ; `EconomyAdminTest` (**9 cas** de permissions et de validation).
    **Non couvert automatiquement** : l'ergonomie réelle de la page, la distinction perçue entre
    reprendre et compenser, et le comportement sur une vraie panne de base — c'est l'objet des
    sections A à G.

---

### TC-252 — Groupes multiples et permissions PlugAdmin (issue #199)

-   **Fonctionnalité testée :** création / modification / suppression de groupes, appartenance d'un
    compte à plusieurs groupes, attribution des permissions aux groupes, affichage des droits
    effectifs **et de leur provenance**, révocation prenant effet sur une **session active**,
    contrôle backend de chaque route, et protection du dernier propriétaire.
-   **Préconditions :** panel de cette session déployé. **Aucun redéploiement du plugin n'est
    nécessaire** : ce lot est entièrement côté panel.
-   **IMPORTANT — identités dédiées.**
    -   **Ne modifiez jamais les droits de votre propre compte propriétaire pour tester.** Créez
        deux comptes de test et désactivez-les à la fin.
    -   Les groupes PlugAdmin n'ont **aucun** rapport avec OP Minecraft ni avec les droits de
        construction en jeu : rien de ce test ne touche au serveur de jeu.
-   **Préparation :** créer `tc252-lecteur` (rôle **READ_ONLY**) et `tc252-admin` (rôle **ADMIN**),
    avec des mots de passe jetables.

-   **A. Migration — rien n'a changé pour l'existant :**
    1.  Ouvrir `/groups`. **Attendu** : « Aucun groupe », et deux encadrés expliquant la règle de
        l'**union** et l'interdiction d'accorder un droit qu'on ne détient pas.
    2.  Ouvrir la fiche de votre compte. **Attendu** : les droits effectifs listés correspondent
        exactement à ceux de votre rôle, chacun avec la provenance « rôle … ».
    3.  **Attendu** : votre accès de propriétaire est intact (la page `/users` répond).

-   **B. Cycle de vie d'un groupe :**
    4.  Créer un groupe `TC252 Console` en cochant `OPS_LOGS`. **Attendu** : création, puis la fiche
        du groupe montre 1 droit et 0 membre.
    5.  Créer un second groupe nommé `tc252 console` (casse différente). **Attendu** : **refus**
        pour nom déjà pris.
    6.  Renommer le groupe en `TC252 Exploitation`. **Attendu** : enregistré, permissions inchangées.
    7.  Tenter de supprimer en tapant un nom **incorrect**. **Attendu** : refus, le groupe est
        toujours là.
    8.  Créer un groupe `TC252 Rédaction` avec `QUEST_CONTENT_WRITE`.

-   **C. Plusieurs groupes et droits effectifs (le point central) :**
    9.  Fiche de `tc252-lecteur` → cocher **les deux** groupes → enregistrer. **Attendu** :
        confirmation mentionnant un effet immédiat.
    10. **Attendu** : la section « Droits effectifs » liste `OPS_LOGS` avec la provenance
        « groupe « TC252 Exploitation » », `QUEST_CONTENT_WRITE` avec « groupe « TC252 Rédaction » »,
        et les droits de `READ_ONLY` avec « rôle READ_ONLY ».
    11. Ajouter `OPS_LOGS` **aussi** au groupe Rédaction. Recharger la fiche. **Attendu** :
        `OPS_LOGS` affiche **deux** provenances — c'est l'information utile pour savoir qu'il faudra
        le retirer des deux endroits.
    12. Décocher le groupe Rédaction sur la fiche du compte. **Attendu** : `QUEST_CONTENT_WRITE`
        disparaît, `OPS_LOGS` **reste** (il vient encore de l'autre groupe), et les droits du rôle
        sont intacts.

-   **D. Conflits et absence de refus explicite :**
    13. Créer un groupe `TC252 Minimal` ne cochant **que** `DASHBOARD_VIEW`, et l'attribuer à
        `tc252-admin`. **Attendu** : `tc252-admin` **conserve tous** ses droits d'ADMIN. Un groupe
        **n'enlève rien** — c'est la règle de l'union, et la page le dit.
    14. Attribuer ce même groupe à **votre** compte propriétaire, puis le retirer. **Attendu** :
        votre accès reste complet à chaque étape.

-   **E. Révocation sur une session ACTIVE (à faire avec deux navigateurs ou deux fenêtres privées) :**
    15. Dans une **seconde** fenêtre, se connecter comme `tc252-lecteur`. Tenter d'ouvrir `/users`.
        **Attendu** : refus (READ_ONLY n'a pas `USER_MANAGE`).
    16. Depuis votre fenêtre de propriétaire : créer `TC252 Comptes` avec `USER_MANAGE` et
        l'attribuer à `tc252-lecteur`.
    17. Dans la fenêtre de `tc252-lecteur`, **sans se reconnecter**, recharger `/users`.
        **Attendu** : la page s'affiche. Le droit est arrivé par le groupe, immédiatement.
    18. Depuis votre fenêtre : retirer le groupe du compte. Dans l'autre fenêtre, recharger.
        **Attendu** : **refus immédiat**, toujours sans reconnexion. C'est la propriété clé du
        ticket.
    19. Refaire 16-17, puis cette fois **vider les permissions du groupe** (décocher tout).
        **Attendu** : refus immédiat dans l'autre fenêtre.
    20. Refaire 16-17, puis **supprimer le groupe**. **Attendu** : refus immédiat.

-   **F. Anti-élévation de privilège (le point de sécurité) :**
    21. Donner à `tc252-admin` le groupe `TC252 Comptes` (`USER_MANAGE`) pour qu'il atteigne les
        écrans de groupes. Se connecter comme `tc252-admin`.
    22. Ouvrir `/groups` → formulaire de création. **Attendu** : la case `PLAYER_OP_WRITE` est
        **désactivée** avec la mention « vous ne détenez pas ce droit » — montrée et non cachée.
    23. Depuis ce compte, tenter de créer un groupe avec `PLAYER_OP_WRITE` en **forçant** le
        formulaire (outil de développement du navigateur, ou requête POST directe).
        **Attendu** : **refus** explicite, et **aucun** groupe créé.
    24. Depuis votre compte propriétaire, créer `TC252 Pouvoirs` avec `PLAYER_OP_WRITE`. Puis depuis
        `tc252-admin` : tenter de le renommer, de le vider, de le supprimer, et de l'attribuer à
        quelqu'un. **Attendu** : **refus** dans les quatre cas, et la fiche du groupe affiche qu'il
        est consultable mais non modifiable.
    25. **Attendu** : `tc252-admin` peut en revanche créer un groupe avec `ECONOMY_WRITE` (droit
        qu'ADMIN détient) — l'anti-élévation ne bloque pas tout, elle bloque exactement ce qu'il
        faut.

-   **G. Routes atteintes directement :**
    26. Avec `tc252-lecteur` (sans `USER_MANAGE`), appeler directement `/groups` en GET.
        **Attendu** : **403**.
    27. Poster directement sur `/groups/create`, `/groups/rename`, `/groups/permissions`,
        `/groups/delete` et `/users/groups`. **Attendu** : **403** à chaque fois, et **aucun** effet.
    28. Déconnecté, appeler `/groups`. **Attendu** : redirection vers la connexion.

-   **H. Mobile :**
    29. Ouvrir `/groups` et une fiche de compte sur un téléphone. **Attendu** : les listes de cases
        à cocher passent sur une colonne, les libellés sont cliquables confortablement, les tables
        défilent horizontalement sans casser la page, et aucun bouton n'est hors écran.

-   **I. Audit :**
    30. Ouvrir la page d'audit. **Attendu** : chaque création, renommage, changement de permissions,
        suppression et changement d'appartenance figure avec son auteur — **et** les tentatives
        **refusées** de l'étape 23/24 apparaissent avec le résultat `DENIED`.

-   **Nettoyage :** supprimer les groupes `TC252 *`, puis **désactiver** les comptes `tc252-lecteur`
    et `tc252-admin`. Vérifier pour finir que votre compte propriétaire a toujours tous ses droits
    et que `/users` répond.
-   **Couverture automatisée :** `EffectivePermissionsTest` (9 cas : rôle seul = droits du rôle,
    groupes qui ajoutent sans retirer, union de plusieurs groupes, groupe vide sans effet,
    **un groupe ne peut pas retirer un droit du rôle**, provenances multiples, droit non accordé sans
    provenance, libellés de provenance, compte sans rôle) ; `GroupDirectoryTest` (20 cas, dont
    **8 sur l'anti-élévation** : création, ajout, retrait, renommage, suppression et attribution
    refusés sur un droit non détenu, acteur sans droit qui ne peut rien, et le pendant indispensable
    — un ADMIN peut gérer un groupe dans la limite de ses propres droits) ;
    `GroupAdminHttpTest` (14 cas de bout en bout en HTTP : migration, cycle de vie, doublon de nom,
    **révocation sur session active par les trois chemins** (retrait d'appartenance, vidage du
    groupe, suppression du groupe), **toutes les routes refusées côté backend**, non authentifié,
    CSRF absent, provenance affichée, appartenances multiples, dernier propriétaire, et **audit des
    refus**).
    **Non couvert automatiquement** : le rendu mobile réel, le forçage de formulaire depuis un
    navigateur, et le confort d'usage — c'est l'objet des sections F (23), G et H.

---

### TC-253 — Droits Minecraft par groupe et par monde, via LuckPerms (issue #200)

-   **Fonctionnalité testée :** découpage des permissions du plugin, liaison compte PlugAdmin ↔
    joueur par UUID, droits Minecraft d'un groupe par monde, provenance, synchronisation idempotente
    et **révocation ciblée** qui ne détruit aucun droit externe.
-   **Préconditions :** JAR et panel de cette session déployés, serveur redémarré, **LuckPerms
    installé** sur DEV.
-   **IMPORTANT — identités dédiées.**
    -   **Ne modifiez jamais vos propres droits pour tester.** Utilisez un compte Minecraft de test
        **non opéré** et un compte PlugAdmin de test ; restaurez-les à la fin.
    -   `rpgquest.admin.world` reste l'ombrelle des administrateurs existants : **ne la retirez à
        personne** pendant ce test.

-   **A. Builder de Hub sans OP (le scénario central) :**
    1.  Créer un groupe PlugAdmin `TC253 Builder Hub`, lui donner le droit Minecraft **« Construire
        dans le Hub « <votre monde de hub> » »**. **Attendu** : la case exige un monde, et la page
        indique qu'aucun droit de construction ne donne de bypass.
    2.  Lier le compte PlugAdmin de test au joueur de test par **UUID**. **Attendu** : la page dit
        que le pseudonyme n'est pas une preuve et qu'un joueur hors ligne peut être lié.
    3.  Mettre le compte dans le groupe, puis **Appliquer les droits de ce groupe**, puis
        **Synchroniser les droits en jeu**. **Attendu** : succès avec le détail « ajouté(s) ».
    4.  En jeu avec le joueur de test, **sans OP** : casser/poser un bloc **dans le Hub**.
        **Attendu** : **autorisé**.
    5.  Aller dans le **monde des claims** et tenter de casser un bloc dans le claim d'un autre.
        **Attendu** : **refusé**. Le droit de construire n'est pas un bypass de claim.
    6.  Tenter de casser un bloc dans une **zone protégée**. **Attendu** : **refusé**.
    7.  Tenter de casser un **waypoint / une borne de voyage**. **Attendu** : **refusé**.
    8.  Taper `/rpgadmin flatten`. **Attendu** : **refus de permission**. Aucune commande admin
        supplémentaire n'a été accordée.
    9.  Frapper une entité paisible du Hub. **Attendu** : **refusé** (le bypass combat est un autre
        nœud).
    10. Si vous avez un second monde de Hub : tenter d'y construire. **Attendu** : **refusé**, le
        droit est limité au monde nommé.

-   **B. Éditeur de PNJ sans build :**
    11. Créer un groupe `TC253 PNJ` avec **« Entrer dans /rpgadmin »** et **« Branche PNJ de
        /rpgadmin »**, et **aucun** droit de construction. L'appliquer et synchroniser.
    12. En jeu : `/rpgadmin npc info` en visant une entité. **Attendu** : **fonctionne**.
    13. `/rpgadmin player resetnew <joueur>`, `/rpgadmin flatten`, `/rpgadmin content reload`,
        `/rpgadmin quest complete …`. **Attendu** : **refus** à chaque fois — entrer dans la commande
        n'autorise aucune branche.
    14. Casser un bloc dans le Hub. **Attendu** : **refusé** (aucun droit de build).
    15. **Attendu** : aucun accès à l'économie ni aux resets.
    16. **Point à vérifier explicitement** : `/rpgadmin npc tag` **ne crée pas** un PNJ Citizens. La
        création passe par le panel (actions `npc.citizens.*`, permissions PlugAdmin) ou par les
        commandes de Citizens. Vérifier que ce groupe ne permet **pas** de créer un PNJ Citizens.

-   **C. Collision avec un droit externe identique (le point de sécurité) :**
    17. Dans LuckPerms, accorder **à la main** au joueur de test, directement sur l'utilisateur, le
        **même** nœud et le **même** monde que celui du groupe :
        `lp user <joueur> permission set rpgquest.build.hub.<monde> true world=<monde>`.
    18. **Attendu** : `lp user <joueur> info` montre **deux** sources — le groupe `rpgq-…` du pont
        et le nœud direct.
    19. Retirer le compte du groupe PlugAdmin, puis **Synchroniser**. **Attendu** : le pont retire
        **l'appartenance** `group.rpgq-…` et **laisse le nœud direct en place**. Le joueur construit
        **toujours** dans le Hub, par son droit externe.
    20. **Attendu** : le résultat de la synchronisation liste ce nœud direct dans « préservé ».
    21. **Dissocier** le compte, puis synchroniser à zéro groupe. **Attendu** : même conclusion — le
        droit externe survit.
    22. **Redémarrer le serveur**, puis `lp user <joueur> info`. **Attendu** : le nœud direct est
        **toujours là**. C'est la persistance demandée.
    23. Accorder aussi au joueur un droit **sans rapport** (ex. appartenance à un groupe LuckPerms
        `vip`, ou `essentials.fly`). Relancer une synchronisation. **Attendu** : **intact**.
    24. Nettoyage de cette section : retirer à la main le nœud direct et le droit sans rapport.

-   **D. Idempotence et état réel :**
    25. Relancer **deux fois** la même synchronisation sans rien changer. **Attendu** : la seconde
        dit « déjà conforme », 0 ajouté, 0 retiré.
    26. Cliquer **Lire l'état réel en jeu**. **Attendu** : la liste des droits gérés avec, pour
        chacun, le **groupe du pont** d'où il vient.
    27. Comparer avec la ligne « voulu par le panel ». **Attendu** : identiques après une
        synchronisation réussie, et le panel distingue clairement les deux.

-   **E. Pont indisponible — aucun faux succès :**
    28. Arrêter LuckPerms (ou tester **avant** son installation). Lancer une synchronisation.
        **Attendu** : résultat en **échec** avec le motif « LuckPerms n'est pas installé… », et
        **jamais** un succès. Le relevé d'état affiche « Pont indisponible » avec son motif.
    29. **Attendu** : le serveur fonctionne normalement par ailleurs — le plugin n'exige pas
        LuckPerms.

-   **F. Compatibilité des administrateurs existants :**
    30. Avec un compte **administrateur existant** (porteur de `rpgquest.admin.world`, ou OP) :
        construire dans le Hub, utiliser `/rpgadmin`, entrer dans le monde des claims.
        **Attendu** : **tout fonctionne comme avant**, sans aucune reconfiguration.
    31. **Attendu** : le correctif #22 est intact — entrer dans le monde des claims n'est jamais
        refusé à un porteur du bypass, et une Pierre de retour est garantie.

-   **G. Permissions du panel et audit :**
    32. Avec un compte panel **sans** `USER_MANAGE` : appeler directement les routes `/groups/mc`,
        `/users/mc/link`, `/users/mc/unlink`. **Attendu** : **403** à chaque fois.
    33. Tenter d'enfiler `type=mc.rights.sync` depuis ce compte. **Attendu** : **403**.
    34. Page d'audit. **Attendu** : `mc.link`, `mc.unlink`, `mc.group.nodes` et les actions
        `mc.*` figurent avec leur auteur, l'avant/après, **et les refus**.

-   **Nettoyage :** supprimer les groupes `TC253 *` (ce qui retire aussi leurs droits Minecraft),
    retirer le groupe LuckPerms du pont via **Retirer le groupe LuckPerms**, dissocier le compte de
    test, vérifier avec `lp user <joueur> info` qu'il ne reste **aucun** nœud `rpgq-`, et vérifier
    que vos propres droits d'administrateur sont intacts.
-   **Couverture automatisée :** côté plugin — `RpgAdminTestShortcutsCommandTest` (+6 : branche PNJ
    accessible, **8 autres branches refusées**, nœud d'entrée qui n'autorise rien, refus nommant le
    nœud manquant, nœud précis par sous-action, ombrelle qui autorise tout),
    `HubWorldProtectionListenerTest` (+6 : **builder sans OP** autorisé dans son Hub, refusé dans un
    autre Hub, joker explicite, aucun bypass combat, ombrelle, et `build.wild` qui n'autorise rien
    dans le Hub), `BridgeProvenanceTest` (16 : nommage stable des groupes du pont, **un groupe
    externe accordant les mêmes droits n'est jamais reconnu comme le nôtre**, ombrelle et
    `admin.debug` non distribuables, nœuds externes refusés avec motif, **le monde fait partie de
    l'identité du droit**, et absence de LuckPerms qui ne produit jamais un faux succès) ; côté
    panel — `McBridgeDirectoryTest` (22 : liaison par UUID, joueur hors ligne, UUID invalide,
    **conflit impossible**, modification, dissociation, droits par monde, **build sans monde
    refusé**, ombrelle et nœuds externes refusés, remplacement exact, même nœud dans deux mondes,
    union des groupes, **survie à la réouverture de la base**, catalogue sans monde deviné),
    `AgentActionExecutorTest` (+5 sur les actions du pont).
    **Non couvert automatiquement, et c'est l'objet de ce protocole** : la collision avec un droit
    externe réel, sa persistance après redémarrage, le comportement des listeners en jeu, et la
    création de PNJ Citizens — aucun de ces points n'est simulable sans un vrai LuckPerms et un vrai
    serveur.
### TC-254 — Édition d'un choix de dialogue porteur d'actions/conditions (issue #82, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `DialogueDefinitionEditor#updateChoice` (préservation + édition
    structurée), action agent `dialogue.choice.update` (`quest_action` / `quest_condition`),
    formulaire de choix et champ MiniMessage de `/dialogues`, garde-fou de `/dialogues/edit/<id>`.
-   **Préconditions :** JAR de cette session déployé sur DEV et redémarré ; Control Panel
    redéployé ; un dialogue chargé par le serveur dont un choix porte une action `START_QUEST` et
    une condition `QUEST_STATE` (le dialogue de **Jeff** en DEV).
-   **Scénario principal :**
    1.  `/dialogues` → déplier le dialogue de Jeff → graphe des nœuds → sous le choix
        « Je vais m'en charger », « Modifier ce choix » ouvre bien un formulaire (plus de note
        « édition prévue dans une phase ultérieure »).
    2.  Le sélecteur « Action de quête » affiche **Démarrer la quête** et la quête
        « Nettoyage des alentours » ; le sélecteur de condition affiche **Pas encore commencée**
        sur la même quête. Rien n'est vide ni « à choisir ».
    3.  Modifier **uniquement** le texte du choix → enregistrer → rafraîchir `dialogue.list` :
        le texte a changé, l'action `START_QUEST` et la condition `QUEST_STATE` sont toujours là,
        la flèche vers le nœud suivant aussi.
    4.  Changer l'état de la condition (ex. → *Terminée*), enregistrer, rafraîchir : seule la
        condition a changé ; l'action de quête n'a pas bougé.
    5.  Palette de couleurs : cliquer une pastille sur le texte d'un nœud réécrit la balise
        englobante, l'aperçu change de couleur, et le champ montre toujours la source. Sur un
        texte à plusieurs balises différentes, la palette est grisée avec sa note.
    6.  **Non-régression source :** `/dialogues/edit/<id>` sur un dialogue de la source à
        plusieurs nœuds → modifier la réplique de départ → enregistrer → le fichier garde tous
        ses autres nœuds, choix, conditions et actions.
-   **Reset :** aucun. Ne pas utiliser le dialogue réel de Jeff comme bac à sable : dupliquer le
    scénario sur un dialogue de test si le texte doit rester tel quel.
-   **Couverture automatisée :** `DialogueDefinitionEditorTest` (texte d'un choix riche sans
    toucher conditions/actions, `SET` / `REMOVE` / `KEEP` de l'action et de la condition,
    bascule « ferme le dialogue » sans perdre l'action, format canonique identique entre moteur et
    panel) ; `AgentActionExecutorTest` (paramètres structurés transportés, rejets) ;
    `DialoguesCatalogTest` (formulaire rendu, sélecteurs pré-remplis, propriétés conservées
    annoncées, `keep` forcé sur deux actions de quête) ; `DialogueYamlTest` et
    `DialogueSourceMergeTest` (round-trip fidèle, réédition sans perte, refus d'écrasement).
-   **Limites :** le rendu visuel réel de la palette et de l'aperçu, et le comportement en jeu du
    dialogue après édition, restent `PENDING MANUAL VALIDATION`.

---

### TC-255 — PNJ : « regarder les joueurs » et promenade depuis le panel (issue #165, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** actions agent `npc.citizens.lookclose` et `npc.citizens.wander`,
    pont `CitizensBehaviourBridge` (trait Citizens `LookClose`, fournisseur `wander` du trait
    `Waypoints`), affichage « Comportement » de la fiche PNJ.
-   **Préconditions :** Control Panel déployé, JAR déployé **et serveur redémarré**, Citizens
    2.0.43 actif. **Utiliser un PNJ de test** créé pour l'occasion depuis « Créer un PNJ », jamais
    Andy, Tania, Tan, Help ni le Guide. Le supprimer à la fin.
-   **Pourquoi c'est manuel :** `MockBukkit` n'embarque pas Citizens. Rien de ce qui touche aux
    traits réels n'est couvert par un test automatisé — seules la règle de non-écrasement, la
    validation des actions et le rendu du panel le sont.

**Regarder les joueurs**

1.  Fiche du PNJ de test → **« Regarder les joueurs »**. **Attendu** : l'état actuel est annoncé
    comme relu sur Citizens (« désactivé » par défaut), et la portée préremplie à 10.
2.  Mettre la portée à 6, cliquer **Activer**. Rafraîchir « Citizens ». **Attendu** : la fiche
    affiche « regarde les joueurs », « portée 6.0 bloc(s) ».
3.  En jeu, s'approcher à moins de 6 blocs. **Attendu** : le PNJ tourne la tête vers le joueur.
    S'éloigner au-delà de 6 blocs : il cesse de suivre.
4.  **Point clé — pas de bascule.** Recliquer **Activer** deux fois de suite. **Attendu** : le PNJ
    reste activé. Il ne doit **jamais** s'éteindre parce qu'on a recliqué.
5.  Cliquer **Désactiver**, puis **Désactiver** de nouveau. **Attendu** : reste désactivé.
6.  Redémarrer le serveur. **Attendu** : l'état et la portée sont conservés.

**Promenade**

7.  Fiche → **« Promenade »**. **Attendu** : rayon 12 et amplitude 2 proposés ; l'ancre repliée est
    préremplie depuis la **position actuelle** du PNJ, et le texte le dit.
8.  Cliquer **Activer la promenade**. Rafraîchir « Citizens ». **Attendu** : la fiche affiche
    « promenade », l'**ancre** avec ses coordonnées, et « ±12 bloc(s) horizontalement ».
9.  Observer le PNJ quelques minutes. **Attendu** : il se déplace au hasard et **ne sort jamais**
    d'environ 12 blocs autour de l'ancre, ni de son monde.
10. Lui parler pendant qu'il se promène. **Attendu** : le dialogue s'ouvre normalement ; une quête
    qu'il donne reste donnable.
11. **Point clé — ancre ≠ position.** Utiliser « Déplacer » pour l'emmener 30 blocs plus loin.
    Rafraîchir. **Attendu** : l'ancre affichée est **inchangée**, et le PNJ revient se promener
    autour de son ancre d'origine.
12. Réappliquer la promenade (bouton « Mettre à jour la promenade ») sans toucher à l'ancre.
    **Attendu** : aucune demande de confirmation — le PNJ est déjà en promenade.
13. Cliquer **Arrêter la promenade**. **Attendu** : le PNJ s'immobilise immédiatement (navigation
    interrompue), le message donne sa **position finale**, et la fiche affiche « immobile ».
14. Recliquer **Arrêter la promenade**. **Attendu** : message « la promenade n'est pas active »,
    rien n'est modifié.
15. Redémarrer le serveur avec la promenade active. **Attendu** : elle reprend, même ancre, même
    zone.

**Point clé — jamais d'écrasement silencieux**

16. En jeu, donner une patrouille au PNJ de test : `/npc select <id>` puis `/npc path`, poser
    2-3 points, quitter l'éditeur. Rafraîchir « Citizens ».
17. Fiche → **« Promenade »**. **Attendu** : un avertissement annonce la patrouille existante **et
    le nombre de points**, et une case « Je confirme le remplacement » est présente.
18. Cliquer **Activer la promenade** **sans** cocher la case. **Attendu** : l'action **échoue**
    (`WANDER_CONFLICT`), la patrouille est **intacte**, le PNJ patrouille toujours.
19. Cocher la case puis activer. **Attendu** : la promenade remplace la patrouille, et le message
    dit explicitement ce qui a été remplacé.
20. Supprimer le PNJ de test.

-   **Limites :** l'exactitude du bornage de zone s'observe statistiquement — quelques minutes
    d'observation ne prouvent pas une borne stricte, elles détectent une fuite grossière. La
    correspondance exacte entre le snapshot Maven `2.0.43` compilé et la build Jenkins `4232`
    installée n'est pas garantie par les numéros : si une incompatibilité existait, elle se
    manifesterait par un message `CITIZENS_INCOMPATIBLE` à l'étape 2 ou 8, **sans** affecter les
    autres fonctions PNJ. Le signaler tel quel plutôt que d'insister.

### TC-256 — Entrée dans le Wild : avertissement, préparation, état du Wild au Garde (issues #161/#26/#24, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** `travel.WildEntryWarningService` (avertissement générique,
    confirmation explicite, option persistante « ne plus afficher », retour d'attente),
    `travel.WildConditionsService` + marqueur `%wild_conditions%` du dialogue du Garde, et
    **non-régression** du kit du Guide (#26 partie A).
-   **Préconditions :** JAR déployé **et serveur redémarré**. Un World-Portal `world_hub → wild`
    existe et est activé (`/rpgadmin worldportal list`). Les PNJ **Garde** et **Guide** sont liés à
    leurs dialogues. `dialogues/guard.yml` du serveur doit contenir le nœud `wild_conditions` et son
    choix (un redéploiement de JAR **ne met jamais à jour** un `guard.yml` déjà présent : vérifier
    ou transférer le fichier). Faire le parcours principal avec un compte **non OP**.
-   **Pourquoi c'est manuel :** la fenêtre Paper, la fermeture au clavier, la latence réelle de
    génération de chunks et l'horloge/météo d'un vrai monde ne sont pas simulables par MockBukkit.
    Les règles elles-mêmes (blocage, persistance, single-flight, substitution dynamique) sont
    couvertes par des tests automatisés.

**Avertissement et annulation**

1.  Entrer dans le passage Hub → Wild. **Attendu** : une fenêtre (ou, si `dialogue.renderer: chat`,
    un message cliquable) annonce que le Wild est dangereux, que le **PvP y est autorisé** et que la
    mort fait perdre l'inventaire, puis demande « Voulez-vous continuer ? ». Une phrase indique que
    **le Garde** peut renseigner sur les conditions actuelles. **Aucune téléportation.**
2.  Vérifier que **jour/nuit et météo ne sont PAS affichés** dans cette fenêtre.
3.  Cliquer **Annuler**. **Attendu** : message « Vous restez au Hub. », aucune téléportation.
4.  **Point clé — pas de boucle.** Rester dans le portail et bouger dedans quelques secondes.
    **Attendu** : la fenêtre **ne se rouvre jamais** d'elle-même.
5.  Sortir du portail puis y rentrer. **Attendu** : l'avertissement réapparaît (nouvelle
    intervention explicite).
6.  Rouvrir l'avertissement et le **fermer au clavier** (Échap). **Attendu** : équivalent à annuler
    — aucun départ, aucun message de préparation, et l'avertissement réapparaîtra à la prochaine
    entrée (la fermeture ne mémorise rien).

**Départ confirmé**

7.  Entrer puis cliquer **Entrer dans le Wild**. **Attendu** : immédiatement « Recherche d'un point
    d'arrivée sûr… Téléportation en préparation. », **avant** l'attente.
8.  **Attendu** : arrivée dans le Wild sur un sol sûr, puis « Téléportation réussie. ».
9.  Dans la console serveur, relever la ligne `[TP-LATENCY]` correspondante. **Attendu** :
    `strategy=RANDOM_SAFE`, un `attempts=` plausible, et `chunks_ms` / `search_ms` / `teleport_ms`
    renseignés. **Noter ces valeurs dans le rapport** : c'est la mesure de latence demandée.
10. Revenir au Hub (Rune de rappel ou Waystone).

**Option « Ne plus afficher »**

11. Entrer dans le portail, cliquer **Entrer et ne plus afficher cet avertissement**. **Attendu** :
    départ normal avec le message de préparation.
12. Revenir au Hub et entrer de nouveau. **Attendu** : **aucun avertissement**, mais le message
    « Recherche d'un point d'arrivée sûr… » reste affiché, puis arrivée normale.
13. Se déconnecter/reconnecter, puis **redémarrer le serveur**, et refaire un passage.
    **Attendu** : l'avertissement reste masqué (préférence persistée).
14. `/rpgadmin player resetnew <joueur>` puis nouveau passage. **Attendu** : l'avertissement est de
    retour.

**Demander l'état du Wild au Garde**

15. Parler au **Garde** au Hub. **Attendu** : le choix « Comment est le Wild actuellement ? » est
    présent, **quelle que soit** la progression du joueur (quête acceptée ou non, paliers faits ou
    non).
16. Le choisir. **Attendu** : une phrase du type « Il fait jour dans le Wild. Le temps est clair. »
    — cohérente avec l'heure réelle du Wild.
17. **Point clé — l'état est réel.** Depuis la console : `time set night` **dans le monde wild**
    (ex. `execute in minecraft:wild run time set night`), puis redemander. **Attendu** : « Il fait
    nuit dans le Wild. ». Idem avec `weather rain` → « Il pleut. », puis `weather thunder` →
    « Un orage est en cours. », puis `weather clear` → « Le temps est clair. ».
18. **Point clé — rien n'est modifié par la lecture.** Noter l'heure du Wild
    (`execute in minecraft:wild run time query daytime`) avant et après plusieurs demandes au
    Garde. **Attendu** : seule la progression normale du cycle, aucun saut, et la météo inchangée.
    Le **Hub** reste en plein jour comme avant.
19. Si le monde `wild` est déchargé, redemander. **Attendu** : « Je n'ai pas de nouvelles du Wild
    pour le moment. » — jamais une météo inventée.

**Non-régression du kit du Guide (#26 partie A)**

20. Parler au **Guide**, choisir « Demander mon kit de départ ». **Attendu** : exactement une épée,
    une pioche, une pelle et une hache en bois, comme avant ce lot.
21. Redemander sans être mort. **Attendu** : refus expliqué. Mourir puis redemander. **Attendu** :
    nouvelle remise.
22. Remplir l'inventaire (moins de 4 emplacements libres) et demander. **Attendu** : refus complet,
    **aucun objet** distribué, droit conservé.

-   **Limites :** `time set` / `weather` sont des commandes **de test** : ne jamais les laisser
    appliquées sur le serveur de production après la recette (`weather clear` pour terminer, et
    laisser le cycle reprendre). La latence mesurée à l'étape 9 dépend de l'état de génération du
    Wild : une première exploration d'une zone neuve sera naturellement plus lente qu'un retour sur
    une zone déjà générée — comparer plusieurs passages avant de conclure.

### TC-257 — Rapporter des objets à un PNJ : dépôts partiels persistants (issue #123, PENDING MANUAL VALIDATION)

-   **Fonctionnalité testée :** objectif `DELIVER_ITEM_TO_NPC`, action de dialogue
    `DELIVER_QUEST_ITEMS`, condition `HAS_PENDING_DELIVERY`, marqueur `%delivery_status%`, et le type
    « Rapporter des objets à un PNJ » de l'éditeur guidé du Control Panel.
-   **Préconditions :** JAR déployé **et serveur redémarré**. Le PNJ **Garde** est lié à
    `rpgquest:guard` et son `dialogues/guard.yml` côté serveur doit contenir les nœuds `delivery`,
    `delivery_after` et `delivery_done` (un redéploiement de JAR **ne met jamais à jour** un dialogue
    déjà présent : vérifier ou transférer le fichier). Jouer avec un compte **non OP**.
-   **Pourquoi c'est manuel :** la fenêtre de dialogue réelle, le rendu des noms d'objets traduits
    par le client, et le comportement d'un véritable redémarrage ne sont pas simulables. Les règles
    (retrait exact, multi-piles, multi-objectifs, mauvais PNJ, double-clic, persistance) sont
    couvertes par des tests automatisés.

**Créer la quête depuis le Control Panel**

1.  `/quests` → **Créer une quête**. Id `test_remise`, titre et description libres, catégorie
    `test`. **Attendu** : dans la liste des types d'objectif, « **Rapporter des objets à un PNJ** »
    est proposé.
2.  Choisir ce type. **Attendu** : trois champs apparaissent — **PNJ destinataire**, **Objet à
    rapporter**, **Quantité à remettre** — chacun avec son aide ; l'aide dit explicitement que
    collecter ne suffit pas et que les dépôts partiels sont conservés.
3.  Renseigner PNJ = `guard`, objet = `LEATHER`, quantité = `4`. Ajouter **trois autres** objectifs
    de remise dans la **même étape** : `STICK` × 1, `COBBLESTONE` × 2, `WHEAT_SEEDS` × 3.
4.  Enregistrer. **Attendu** : aucune erreur de validation, et le résumé de l'étape affiche quatre
    lignes du type « **Rapporter Cuir (x4) à Guard** ».
5.  Rouvrir la quête en édition. **Attendu** : les quatre objectifs sont **relus intacts** (PNJ,
    objet, quantité) et restent éditables séparément — c'est l'aller-retour.
6.  Recharger le contenu (`/ops` → rechargement, ou redémarrage) puis `/quest accept rpgquest:test_remise`
    en jeu avec le compte de test.

**Posséder ne suffit pas**

7.  Se donner 64 cuirs, puis ouvrir le journal (`/quests` ou l'objet Journal). **Attendu** : la ligne
    affiche `Cuir (à remettre) 0/4` — **pas** de progression, bien que l'inventaire soit plein de
    cuir. Vérifier aussi que ramasser du cuir au sol ne change rien.

**Dépôt partiel**

8.  Ne garder que **2 cuirs** et rien d'autre d'utile. Parler au **Garde**. **Attendu** : le choix
    « J'ai des matériaux à te remettre » est présent.
9.  Le choisir. **Attendu** : l'état s'affiche, une ligne par matériau, avec les noms d'objets en
    français et les compteurs `0/1`, `0/2`, `0/4`, `0/3`.
10. Choisir « Donner les matériaux que j'ai ». **Attendu** : message « Matériaux remis : 2 × Cuir »
    puis « Il te manque encore : … » listant le reste. Les **2 cuirs ont disparu** de l'inventaire,
    et l'écran affiche maintenant `Cuir 2/4`.
11. Ouvrir le journal. **Attendu** : `Cuir (à remettre) 2/4`.

**Point clé — la progression est acquise**

12. **Mourir** (par exemple dans le Wild), perdre l'inventaire, puis reparler au Garde. **Attendu** :
    toujours `Cuir 2/4` — les 2 cuirs remis ne sont **jamais** perdus.
13. Se déconnecter, se reconnecter, reparler au Garde. **Attendu** : `Cuir 2/4`.
14. **Redémarrer le serveur**, se reconnecter, reparler au Garde. **Attendu** : `Cuir 2/4`.

**Point clé — une seule interaction pour tout**

15. Se procurer 1 bâton, 1 pierre, 2 cuirs et 3 graines, **plus** 10 cuirs en trop.
16. Parler au Garde → « Donner les matériaux que j'ai », **une seule fois**. **Attendu** : les quatre
    compteurs avancent ensemble ; le message liste les quatre matériaux. Il reste **1 pierre** à
    remettre (2 demandées, 1 donnée).
17. **Attendu — jamais plus que le besoin** : exactement 2 cuirs ont été pris, les **8 cuirs
    excédentaires sont toujours dans l'inventaire**.
18. Répartir les objets restants sur **plusieurs piles** séparées de l'inventaire avant de remettre
    la dernière pierre. **Attendu** : les piles sont additionnées correctement.
19. Donner la dernière pierre. **Attendu** : « Tu as remis tout ce qui était demandé. », le choix
    « Et voilà, tout est remis » apparaît et mène au nœud de remerciement ; la quête se termine si
    c'était sa dernière étape.

**Points clés — abus et mauvais PNJ**

20. Reprendre une quête de remise en cours et **spammer** le bouton « Donner les matériaux que j'ai »
    (clics très rapides). **Attendu** : la progression correspond **exactement** aux objets réellement
    possédés — jamais le double —, et aucun objet ne disparaît sans contrepartie.
21. Avec des objets utiles en poche, parler au **Guide** ou au **Libraire**. **Attendu** : aucun choix
    de remise n'apparaît chez eux, et rien n'est retiré. Un mauvais PNJ ne peut pas accepter.
22. Quand tout est remis, reparler au Garde avec encore des cuirs. **Attendu** : « Tu m'as déjà remis
    tout ce que je t'avais demandé. » et **aucun** objet consommé.
23. Placer un **objet personnalisé RPGQuest** fait du même matériau si disponible (ex. un objet
    `LEATHER` custom) à côté de cuirs ordinaires, puis remettre. **Attendu** : seul le cuir ordinaire
    part ; l'objet personnalisé reste en place.

**Nettoyage**

24. Supprimer la quête de test depuis le Control Panel (`/quests` → supprimer), et vérifier que le
    choix de remise disparaît du dialogue du Garde.

-   **Limites :** l'étape 20 (spam) dépend du rythme de clic réel ; elle détecte un double retrait
    grossier, pas une course de quelques millisecondes — celle-ci est couverte par le test automatisé
    d'appel réentrant. L'étape 23 n'est faisable que s'il existe un objet personnalisé partageant le
    matériau demandé ; sinon la garde reste couverte par les tests automatisés uniquement.

---

### TC-258 — Paliers du kit de départ (issue #218, PENDING MANUAL VALIDATION)

-   **But :** vérifier qu'un palier ne peut pas être sauté, que la remise reste limitée à une par
    vie, et que les emplacements exigés suivent le contenu **réel** du palier.
-   **Pré-requis :** JAR du lot de nuit déployé, serveur redémarré (la section
    `starter-tool-kit.tiers` est ajoutée au `config.yml` du serveur au démarrage).
-   **Actions en jeu :**
    1.  Joueur neuf → `/rpgadmin kit status <joueur>` doit annoncer le palier **1**.
    2.  Mourir, revenir, demander le kit au Guide → **4 outils en bois**. Redemander dans la même
        vie → **rien** (une remise par vie).
    3.  Accepter `rpgquest:kit_tier2` auprès du Guide. Le journal doit lister **quatre** remises :
        1 bâton, 2 pierres, 4 cuirs, 3 **graines de blé**.
    4.  Remettre les matériaux **en plusieurs visites**, avec une **mort** entre deux visites : le
        déjà-remis doit être conservé.
    5.  Avec tout en inventaire, **une seule** interaction doit tout remettre d'un coup.
    6.  Quête terminée → `/rpgadmin kit status` doit annoncer le palier **2**.
    7.  Mourir, demander le kit → **épée en pierre + pioche/pelle/hache en bois + bottes en cuir +
        1 pain** (six objets).
    8.  Recommencer avec **5 emplacements libres seulement** : le refus doit annoncer **6**
        emplacements nécessaires (pas 4).
    9.  `/rpgadmin kit grant-tier <joueur> 4` → refus pour **saut de palier**.
    10. `/rpgadmin kit grant-tier <joueur> 9` → refus pour **palier non défini**.
    11. Redémarrer le serveur → le palier est conservé.
-   **Limites :** les paliers 3 à 5 sont acceptés par l'architecture mais leur contenu n'est pas
    défini : rien à tester de ce côté.

---

### TC-259 — Objectif `SMELT_ITEM` : cuire, pas obtenir (issue #141, PENDING MANUAL VALIDATION)

-   **But :** vérifier que seule une **extraction réelle** d'un four fait progresser l'objectif, et
    que l'attribution multijoueur est correcte.
-   **Pré-requis :** copier `docs/manual-tests/quests/test_smelt_item.yml` dans
    `plugins/RPGQuest/quests/`, puis `/quest admin reload`.
-   **Actions en jeu :**
    1.  Accepter `rpgquest:test_smelt_item`.
    2.  Four + combustible + **cactus**, retirer soi-même la teinture verte → le compteur avance.
    3.  Shift-clic sur le slot de résultat (plusieurs objets d'un coup) → le compteur avance
        **d'autant**, sans jamais dépasser la quantité demandée.
    4.  Refaire avec un **haut fourneau** puis un **fumoir** → comptent aussi.
    5.  `/give <joueur> green_dye` → **aucune** progression.
    6.  Entonnoir sous le four qui aspire le résultat → **aucune** progression.
    7.  Deux joueurs, un seul four : seul celui qui **retire** progresse.
    8.  Se déconnecter puis revenir → compteur conservé.
    9.  **Supprimer** la quête de test et `/quest admin reload`.
-   **Limites connues et voulues :** un joueur qui vide le four d'un autre progresse — c'est la
    seule attribution que l'API publique garantisse.

---

### TC-260 — Objectif `DISCOVER_WAYPOINT` (issue #185, PENDING MANUAL VALIDATION)

-   **But :** vérifier que seule une **première découverte réelle** compte, que l'identité comptée
    est l'id du waypoint, et que la règle de comptage est **annoncée** au joueur.
-   **Pré-requis :** copier `docs/manual-tests/quests/test_discover_waypoint.yml` dans
    `plugins/RPGQuest/quests/`, puis `/quest admin reload`. Prévoir au moins deux waypoints encore
    **non découverts** par le compte de test.
-   **Actions en jeu :**
    1.  Accepter `rpgquest:test_discover_waypoint`. Le libellé du journal doit annoncer la
        **portée** (« tous mondes ») et la **règle** (« nouvelles découvertes »).
    2.  **Passer à côté** d'un waypoint inconnu, sans cliquer → aucune progression.
    3.  **Se téléporter** vers un waypoint inconnu depuis une borne → aucune progression.
    4.  Cliquer le bouton d'un waypoint **inconnu** → 1/2.
    5.  **Recliquer le même** → message « Waypoint déjà découvert » mais compteur **inchangé**.
    6.  Découvrir un **second** waypoint, si possible **du même biome** → 2/2, quête terminée.
    7.  Variante filtre : éditer la quête avec `worlds: [world_hub]`, recharger, et vérifier qu'une
        découverte dans le **Wild** ne fait rien avancer.
    8.  Variante cumulative : `count-mode: INCLUDE_EXISTING` sur un joueur ayant déjà des
        découvertes → la progression part du **total déjà acquis** dès l'acceptation.
    9.  Redémarrer le serveur → compteur conservé, **aucune** découverte perdue.
    10. **Supprimer** la quête de test et `/quest admin reload`.
-   **Limite assumée :** en mode `NEW_ONLY`, un joueur ayant déjà découvert tous les waypoints
    accessibles ne peut pas valider un nouveau cycle d'une quête répétable. C'est voulu : aucune
    découverte n'est supprimée ni aucun waypoint débloqué pour y remédier.

---

### TC-261 — Appariement des bornes du Hub (issue #156, PENDING MANUAL VALIDATION)

-   **But :** vérifier qu'une instance de biome du Hub obtient sa borne **pendant la visite en
    cours**, et que le diagnostic dit la **cause** de tout appariement manquant.
-   **Mesure de référence (serveur DEV, avant correction) :** 22 waypoints dans `world_hub` pour
    13 bornes ; les 9 instances sans borne étaient toutes à **88 blocs ou plus** de la borne la plus
    proche, et les 13 bornes existantes avaient été créées entre **17 s et 11 h 50 après** leur
    waypoint. Aucune borne dans le Wild : c'est voulu.
-   **Actions en jeu :**
    1.  `/rpgadmin travel diagnose world_hub` → noter les instances sans borne.
    2.  Entrer dans une instance de biome du Hub **jamais visitée** et y rester une dizaine de
        secondes → une borne doit apparaître **pendant la même visite**.
    3.  Repasser dans les instances connues sans borne → chacune doit s'apparier au fil des
        passages.
    4.  Vérifier qu'une borne apparue est **distincte** du waypoint (6 à 16 blocs), posée sur une
        colonne accessible, et que son bouton ouvre le menu de voyage.
    5.  Allers-retours rapides → **aucun doublon**.
    6.  `/rpgadmin travel diagnose` → pour toute instance restante, l'état doit dire *jamais tenté
        depuis le démarrage*, *appariement en cours*, ou *N essai(s), candidats refusés*. Le libellé
        ambigu « jamais tenté/en cours » ne doit plus apparaître.
    7.  Panel `/travel` → section « Appariements manquants dans le Hub » : cause, distance à la
        borne la plus proche, délai avant réessai, et la mention qu'un redémarrage remet les
        compteurs à zéro.
    8.  Parcourir le **Wild** → aucune borne générée.
-   **Limites :** les compteurs d'essai vivent en mémoire ; après un redémarrage, « jamais tenté »
    signifie « plus depuis le redémarrage ». Aucun ratio borne/waypoint n'a été introduit.

---

### TC-262 — Contrat de contenu machine-readable (issue #110, PENDING MANUAL VALIDATION)

-   **But :** vérifier que les trois documents décrivent le format réel et suffisent à faire
    produire un pack par une IA. **Au navigateur, sans Minecraft** — ces documents ne dépendent pas
    du serveur.
-   **Actions au navigateur :**
    1.  `/content/export` → la carte « Contrat de contenu » est présente, avec ses boutons.
    2.  Télécharger le **schéma JSON** → JSON valide ; `format` et `schemaVersion` sont des
        constantes ; **10** branches d'objectifs et **5** de récompenses.
    3.  Télécharger le **gabarit « pack complet »** → les quatre familles, et la liste commentée des
        10 objectifs et 5 récompenses en bas du fichier.
    4.  Télécharger un **gabarit par famille** → le nom du fichier correspond à la famille.
    5.  Forcer `?family=inventee` → le gabarit **complet** est servi, sous le nom
        `lodyquests-template-content.yml`.
    6.  Télécharger le **contrat rédigé** → identifiants, objectifs, récompenses, stories,
        références, dialogues, équilibrage, exemple minimal, exemple complet `tc110_`, et la
        **limite** sur le vocabulaire des dialogues énoncée explicitement.
    7.  **Essai réel avec une IA** : coller le contrat, demander une mini-campagne, puis relire le
        YAML produit — identifiants `namespace:clé`, aucun type inventé, références cohérentes.
    8.  Les trois routes **sans session** → redirection vers `/login`.
    9.  Centre d'aide `/docs` → la fiche « Content packs » apparaît et s'ouvre.
-   **Limites :** il n'existe **pas encore** d'import de pack (#109) ; un pack produit se recopie
    aujourd'hui élément par élément dans les éditeurs.

---

### TC-263 — Texte stylé sans MiniMessage, partout (issue #195, PENDING MANUAL VALIDATION)

-   **But :** vérifier qu'aucun champ de texte destiné aux joueurs n'impose plus d'écrire du
    MiniMessage, que les textes multi-styles ne sont **jamais** simplifiés en silence, et que les
    boutons de brouillon continuent de fonctionner. **Au navigateur.**
-   **Actions au navigateur :**
    1.  `/quests/new` → **Titre** et **Description** offrent palette, cases de style et aperçu.
    2.  Saisir un titre coloré, enregistrer, rouvrir → couleur conservée, mode guidé prérempli.
    3.  Ouvrir une quête dont le titre est **multi-styles** (deux couleurs, ou `<gradient>`) → mode
        avancé, texte **intact**, avertissement affiché ; la bascule guidée exige un geste explicite
        et annonce qu'elle simplifiera.
    4.  `/stories/new` → **Nom affiché** idem.
    5.  `/dialogues/new` → **Réplique de départ** idem ; le select « Couleur du texte » a
        **disparu**.
    6.  Enregistrer un dialogue depuis le nouveau formulaire, puis vérifier **en jeu** que la
        réplique s'affiche avec la bonne couleur.
    7.  Sur un formulaire **incomplet**, cliquer un bouton de brouillon (ajouter une étape, changer
        un type) → il fonctionne, sans bulle native bloquante, et le marqueur « * » reste affiché
        sur les champs obligatoires.
    8.  **JavaScript désactivé** → les champs restent visibles et éditables, la page reste
        utilisable.
-   **Limites :** le locuteur d'un dialogue et les valeurs techniques des descripteurs restent en
    texte simple — ils ne sont jamais colorés en jeu.

---

### TC-264 — Import sécurisé d'un content pack (issue #109, PENDING MANUAL VALIDATION)

-   **But :** vérifier qu'un pack s'importe réellement, qu'**aucun contenu existant n'est écrasé
    sans décision**, et que ce qui est enregistré est relisible par le serveur. **Au navigateur**,
    puis une vérification en jeu à la fin.
-   **Pré-requis :** Control Panel déployé, compte avec la permission d'import (Propriétaire,
    Administrateur ou Éditeur de contenu).
-   **Préparer un pack :** aller sur **Export de contenu**, exporter la famille **Quêtes**, ouvrir
    le fichier téléchargé et en garder le contenu sous la main. C'est le cas d'usage n°1 du ticket :
    réimporter un pack produit par l'export.
-   **Actions au navigateur :**
    1.  **Import contenu** (`/content/import`) → la page explique les trois étapes et dit que rien
        n'est écrit avant la confirmation.
    2.  Coller le pack exporté, **Analyser** → chaque quête doit apparaître **inchangé** (elle est
        déjà identique à la source), et le bouton d'enregistrement doit être **absent** : il n'y a
        rien à écrire.
    3.  Modifier le titre d'**une** quête dans le texte collé, relancer l'analyse → cette quête
        passe en **conflit**, avec deux boutons (*Remplacer l'existant* / *Garder l'existant*) et un
        diff dépliable montrant l'ancien et le nouveau titre. **Vérifier qu'aucun fichier n'a
        changé** à ce stade.
    4.  Choisir **Garder l'existant** → l'élément passe en « ignoré », plus rien à écrire.
    5.  Choisir **Remplacer l'existant**, puis **Enregistrer dans la source** → le tableau de
        résultat indique le fichier écrit. Ouvrir la quête dans l'éditeur : le nouveau titre est là.
    6.  Changer l'identifiant d'une quête du pack pour un id inexistant, analyser, confirmer → la
        quête est créée (**nouveau**), et le fichier apparaît dans l'éditeur.
    7.  **Collision concurrente** : analyser un pack qui modifie une quête, puis — *avant* de
        confirmer — modifier cette même quête dans l'éditeur dans un autre onglet. Revenir
        confirmer → le remplacement doit être **refusé** (conflit de version), et la modification
        faite dans l'éditeur doit être **intacte**.
    8.  **Erreurs métier** : remplacer un type d'objectif par `UTILISER_UN_OBJET` → l'élément est
        **inexploitable**, l'erreur nomme le type, et le bouton d'enregistrement disparaît.
    9.  **Version** : passer `schemaVersion` à `99` → refus explicite nommant la version attendue ;
        à `0` → refus mentionnant l'absence de migrateur.
    10. **Référence interne** : importer un pack contenant une story et la quête qu'elle cite → la
        story ne doit **pas** signaler de référence inconnue.
    11. **PNJ** : importer un pack contenant une section `npcs` → elle apparaît « ignorée » avec son
        motif, et le reste du pack s'importe normalement.
    12. **Permissions** : se connecter avec un compte **Testeur** ou **Lecture seule** →
        `/content/import` doit être refusé, et l'entrée de menu absente.
-   **Actions en jeu (après import) :**
    13. `/quest admin validate` puis `/quest admin reload` → le contenu importé se charge **sans
        erreur**. C'est la vérification qui compte : ce que l'import écrit doit être relisible par le
        plugin.
    14. Accepter une quête importée et vérifier qu'elle se comporte normalement.
-   **Nettoyage :** supprimer les quêtes d'essai créées, et `/quest admin reload`.
-   **Limites connues :** pas de renommage/copie à l'import (l'identifiant est à la fois le nom de
    fichier et une donnée du contenu) ; pas d'upload de fichier, le pack se colle en texte ; pas
    d'import de PNJ ; l'import **n'active rien** sur le serveur Minecraft.

---

### TC-265 — Atelier IA : créer une quête avec une IA (issue #146, PENDING MANUAL VALIDATION)

-   **But :** vérifier qu'une quête peut réellement être générée, que l'IA ne contourne aucune
    validation, que **rien** n'est enregistré sans confirmation, et que la **clé API ne fuit
    jamais**. Au navigateur, puis une vérification en jeu.
-   **Pré-requis :** Control Panel déployé, une **vraie clé API** chez l'un des trois fournisseurs,
    et un compte Administrateur (pour la configuration) ainsi qu'un compte Éditeur de contenu (pour
    les contrôles de permission).
-   ⚠️ **Ce test consomme des jetons facturés.** Garder le plafond de sortie bas (4000 suffit) et se
    limiter à quelques générations.

#### Configuration (compte Administrateur)

1.  `/ai/providers` → les trois fournisseurs apparaissent, tous « désactivé » et « aucune clé ».
2.  Coller la clé d'un fournisseur, cocher « Activer », **Enregistrer** → l'écran affiche « clé
    enregistrée », une **longueur** et une **empreinte**. Vérifier qu'**aucun caractère** de la clé
    n'est visible, et que le champ de saisie est **vide**.
3.  **Afficher le code source de la page** (Ctrl+U) et chercher la clé → elle ne doit apparaître
    **nulle part**, ni en clair, ni dans un champ caché.
4.  **Tester la connexion** → succès, avec le modèle et les jetons. Puis saisir une clé volontairement
    fausse et retester → échec annonçant une clé refusée.
5.  Changer le modèle en laissant le champ clé **vide**, enregistrer → la clé doit avoir **survécu**
    (l'écran montre toujours la même empreinte).
6.  Mettre une URL de base en `http://` et tester → refus mentionnant **HTTPS**.
7.  **Effacer la clé** → le fournisseur repasse « aucune clé » et « désactivé ».
8.  Journal d'audit (`/audit`) → les entrées `ai.provider.save`, `ai.provider.test` et
    `ai.provider.clearKey` existent, avec l'**empreinte** et **jamais** la clé.

#### Génération (compte Éditeur de contenu ou Administrateur)

9.  Avec aucun fournisseur utilisable : `/ai/studio` → la page le dit et renvoie vers
    `/ai/providers`, **sans** formulaire.
10. Après configuration : le formulaire apparaît, et seul le fournisseur activé est proposé.
11. Laisser le champ d'intention **vide** et soumettre → refus, **aucun appel** (vérifiable dans
    l'audit : aucune entrée `ai.generate`).
12. Décrire une quête, par exemple : *« une quête donnée par le Guide, où le joueur descend dans une
    mine abandonnée, casse 20 pierres, puis lui rapporte 3 lingots de fer. Récompense : un peu
    d'XP. »* Mettre le **titre** en forme avec la palette (couleur + gras) sans taper aucune balise.
13. **Demander une proposition** → au bout d'un moment, la proposition s'affiche avec le fournisseur,
    le modèle et les jetons consommés. Vérifier qu'elle est marquée **« nouveau »** et que le contenu
    proposé correspond à la demande, titre stylé compris.
14. Vérifier qu'**aucun fichier** n'a été créé : ouvrir `/quests` → la quête ne doit **pas** y être.
15. **Relire et enregistrer** → la page d'import s'ouvre avec le pack prérempli. Confirmer →
    le fichier est écrit. Ouvrir la quête dans l'éditeur : elle est éditable normalement, et le titre
    s'affiche en mode guidé avec sa couleur.
16. Relancer la **même** génération et enregistrer → la quête existante doit produire un **conflit**
    exigeant une décision, pas un écrasement.
17. **Correction** : demander volontairement quelque chose d'impossible (par exemple *« ajoute un
    objectif qui demande d'utiliser un objet »*) → la proposition doit être refusée par les
    validateurs, avec le type inconnu nommé, et le bouton **« Demander une correction »** présent.
    Cliquer dessus → l'IA renvoie une version corrigée.
18. **Permissions** : avec un compte **Testeur** puis **Lecture seule**, `/ai/studio` et
    `/ai/providers` doivent être refusés, et les entrées de menu absentes. Avec un compte **Éditeur
    de contenu**, `/ai/studio` fonctionne mais `/ai/providers` est **refusé**.
19. **Délai** : régler le délai maximal à 1 seconde et générer → échec « délai dépassé » annonçant
    que rien n'a été enregistré, et aucun fichier créé.
20. Journal d'audit → les entrées `ai.generate` / `ai.correct` portent le fournisseur, le modèle, les
    jetons et le verdict, et **jamais** le prompt complet ni la clé.

#### En jeu

21. Après l'enregistrement d'une quête générée : `/quest admin validate` puis `/quest admin reload`
    → elle se charge **sans erreur**. C'est la vérification qui compte : ce que l'IA a produit doit
    être réellement consommable par le serveur.
22. Accepter la quête et vérifier qu'elle se comporte normalement.

-   **Nettoyage :** supprimer les quêtes générées pendant le test, `/quest admin reload`, et effacer
    la clé API si elle était temporaire.
-   **Limites connues :** un seul élément par génération ; aucun coût monétaire estimé (les jetons
    sont affichés, pas convertis en euros) ; la qualité éditoriale de la proposition dépend du
    modèle et n'est pas un critère de ce test.

---

### TC-266 — Atelier IA : créer un dialogue et une story (issue #146, PENDING MANUAL VALIDATION)

-   **But :** vérifier que les deux autres familles de l'atelier produisent du contenu réellement
    chargeable par le serveur, que le vocabulaire des actions et conditions est respecté, et qu'une
    story n'invente aucune quête. TC-265 couvre déjà la sécurité de la clé et le fait que rien n'est
    enregistré sans confirmation : **ne pas les refaire ici**.
-   **Pré-requis :** TC-265 réussi (donc un fournisseur fonctionnel), au moins **deux quêtes
    existantes** sur le serveur, et un PNJ identifié existant.
-   ⚠️ **Ce test consomme des jetons facturés.**

#### Choix de la famille

1.  `/ai/studio` → la section « Que voulez-vous créer ? » propose **trois** liens : une quête, un
    dialogue, une story. Le lien actif est mis en évidence.
2.  Cliquer « Un dialogue » → l'URL devient `/ai/studio?kind=dialogue` et le formulaire change :
    champs **PNJ porteur**, **nom affiché du locuteur**, **ton**, **nombre de nœuds**, **quête
    concernée**. Les champs propres à une quête (identifiant de quête, étapes, récompense) ont
    disparu.
3.  Cliquer « Une story » → champs **quêtes à enchaîner**, **identifiant**, **titre**. Les quêtes
    réellement disponibles sont rappelées sous le champ. Aucun champ de récompense.
4.  Forcer une famille inconnue dans l'URL (`/ai/studio?kind=nimportequoi`) → la page retombe sur le
    formulaire de quête, sans erreur ni page vide.

#### Génération d'un dialogue

5.  Choisir « Un dialogue », indiquer un **PNJ existant** comme porteur, mettre en forme le **nom du
    locuteur** avec la palette (couleur + gras) — vérifier qu'aucune balise n'est à écrire —, et
    décrire une conversation avec **au moins un cas de figure** : « s'il a déjà terminé la quête X,
    le féliciter au lieu de la reproposer ».
6.  **Demander une proposition** → la proposition s'affiche, précédée de la famille (« dialogue »),
    du fournisseur, du modèle et des jetons.
7.  Vérifier dans le contenu proposé que : l'**id du dialogue est celui du PNJ** ; `nodes` est une
    **map** (`accueil:`, pas `- id: accueil`) ; le cas de figure demandé est devenu une
    **condition** ; une **sortie** existe (choix sans `next`, ou `CLOSE`).
8.  Déplier **« Réponse brute du modèle »** → la réponse complète est consultable.
9.  **Relire et enregistrer** → la page d'import s'ouvre, le dialogue apparaît en « nouveau »,
    confirmer.
10. En jeu : `/dialogue reload` puis parler au PNJ → le dialogue se charge **sans erreur dans la
    console** et les choix conditionnels n'apparaissent **que** dans l'état attendu. C'est la
    vérification qui compte : un vocabulaire respecté sur le papier mais refusé par le moteur serait
    un échec.
11. Refaire la conversation **après** avoir terminé la quête citée (`/rpgadmin quest complete`) →
    le choix conditionnel change comme demandé.

#### Génération d'une story

12. Choisir « Une story », saisir **deux quêtes existantes** dans l'ordre, décrire le fil, mettre en
    forme le **titre** avec la palette.
13. **Demander une proposition** → vérifier que la story cite **exactement** ces quêtes, dans
    l'ordre, et qu'elle ne contient **aucune** section `quests` (aucune quête inventée).
14. Recommencer en citant volontairement une quête **inexistante** → elle doit être signalée comme
    **dépendance manquante** ou absente de la proposition, **jamais** inventée comme nouvelle quête.
15. Enregistrer la story valide, puis en jeu : `/story reload` et `/story list` → elle apparaît et
    son enchaînement est celui demandé.

#### Vocabulaire refusé

16. Dans la page d'import, soumettre à la main un pack contenant une action inventée
    (`type: TELEPORTER_LE_JOUEUR`) → **refus** nommant le type inconnu **et listant les types
    acceptés**. Vérifier que **rien** n'est écrit.
17. Même essai avec `type: QUEST_STATE` et `state: PRESQUE_FINIE` → refus nommant l'état inconnu.
18. Demander une correction à l'IA depuis une proposition refusée → la nouvelle demande reste sur la
    **même famille** (elle ne repart pas sur une quête).

#### Audit

19. `/audit` → les entrées `ai.generate` portent la **famille** (`kind=DIALOGUE`, `kind=STORY`), le
    fournisseur, le modèle, les jetons et le verdict. Toujours **jamais** la clé ni le prompt
    complet.

-   **Nettoyage :** supprimer le dialogue et la story générés, `/dialogue reload`, `/story reload`.
-   **Limites connues :** les **PNJ** ne sont pas générables (famille non éditable depuis le panel) ;
    un seul élément par génération ; `dialogues/guard.yml` n'est pas éditable depuis le panel parce
    que le lecteur YAML du panel ne gère pas les scalaires repliés — la troncature est signalée, pas
    silencieuse, mais le fichier reste à éditer à la main.

---

## Table de recette

| ID | Test | PASS | FAIL | Notes |
|---|---|---|---|---|
| TC-001 | Version et aide (`/rpgquest`) | | | |
| TC-002 | Profil joueur et rechargement config | | | |
| TC-010 | Cycle complet quête simple (`first_steps`) | | | |
| TC-011 | Prérequis, abandon, admin quêtes | | | |
| TC-012 | Parcours intégral `crystal_hunt` | | | |
| TC-013 | Journal de quêtes (`/quests`) | | | |
| TC-015 | `CRAFT_ITEM` : quantité réellement fabriquée (#34) | | | |
| TC-020 | Dialogue à distance, branchement conditionnel | | | |
| TC-021 | PNJ cliquable et actions de dialogue | | | |
| TC-040 | Registre d'objets (give/list/inspect) | | | |
| TC-041 | Comportement de combat (`forest_blade`) | | | |
| TC-042 | Comportement d'outil (`miner_pickaxe`) | | | |
| TC-043 | Drop garanti (`spider_fang`) | | | |
| TC-050 | Cycle complet nœud de ressource | | | |
| TC-051 | Recettes et anti-triche | | | |
| TC-052 | Resource pack optionnel | | | |
| TC-060 | `runServer`/`launcher.ps1` et persistance | | | |
| TC-070 | Aplatissement : aperçu/confirm/cancel/undo | | | |
| TC-080 | Zones protégées : création et protections | | | |
| TC-090 | Portails : création, canalisation, sécurité | | | |
| TC-091 | Portails : conditions et coût | | | |
| TC-100 | Claims : création et refus | | | |
| TC-101 | Claims : confiance, flags, protections, bypass | | | |
| TC-102 | Claims : limite liée au niveau RPG | | | |
| TC-110 | Mobs spéciaux : invocation et inspection | | | |
| TC-111 | Mobs spéciaux : capacités | | | |
| TC-120 | Portefeuille et paiement entre joueurs | | | |
| TC-121 | Vitrine marchande via dialogue | | | |
| TC-130 | Marché : vente, achat, annulation | | | |
| TC-131 | Marché : achat concurrent réel (PENDING) | | | |
| TC-140 | Résumé et détail de progression | | | |
| TC-141 | Gain d'XP par source et anti-farm | | | |
| TC-142 | Commandes admin de progression | | | |
| TC-150 | Backpacks : accès, ouverture, anti-abus | | | |
| TC-151 | Backpacks : sauvegarde, upgrade, récupération | | | |
| TC-160 | Export snapshot (web) | | | |
| TC-161 | API authentifiée et site public | | | |
| TC-170 | Achat boutique sandbox de bout en bout | | | |
| TC-171 | Webhook rejoué et signature invalide | | | |
| TC-172 | Historique admin et remboursement | | | |
| TC-180 | Mod client : compilation et installation | | | |
| TC-181 | Mod client : handshake compatible | | | |
| TC-182 | Mod client : vanilla et mauvaise version | | | |
| TC-183 | Mod client : contenu (bloc/objet) | | | |
| TC-190 | Diagnostic WorldPortal (`here`/`debug`, TP-TRACE) | | | |
| TC-200 | Storyline : progression automatique de bout en bout | | | |
| TC-210 | Waypoints #124 : génération, découverte bouton, protection, persistance (PENDING) | | | |
| TC-220 | Kit d'outils en bois #26 : demande explicite, tout ou rien, droit par mort (PENDING) | | | |
| TC-221 | Réseau de voyage #132/#150 : découverte → mort → borne → menu → retour sûr (PENDING) | | | |
| TC-222 | Réseau de voyage #151 : Mon claim et Villages (PENDING) | | | |
| TC-223 | Réseau de voyage #149 : génération Hub waypoint + borne appariée (PENDING) | | | |
| TC-224 | Accessibilité Hub + diagnostic/réparation #153/#156 (PENDING) | | | |
| TC-225 | Hub sûr : faim/saturation, animaux protégés, mobs indésirables #33/#30/#121/#155 (PENDING) | | | |
| TC-226 | Secours Hub via la Rune de rappel #154 (PENDING) | | | |
| TC-227 | Recherche waypoints, noms lisibles, retour « déjà découvert » #133/#135/#156 (PENDING) | | | |
| TC-228 | Control Panel : réseau de voyage en lecture seule #152 (PENDING) | | | |
| TC-229 | Bossbar de suivi de quête, balise/id corrigés #157 (PENDING) | | | |
| TC-230 | Panneaux latéraux de nom des waypoints #167 (PENDING) | | | |
| TC-231 | Mobs spéciaux/boss : éditeur panel, tirage Wild, capacités #169/#171 (PENDING) | | | |
| TC-232 | Zombie fissile équilibré, poursuite Cochon Creeper, création de profil #190 (PENDING) | | | |
| TC-233 | Chaîne de paliers du Garde (claims TIER_1-5) #179 (PENDING) | | | |
| TC-234 | Protection + réparation des structures de voyage #191 (PENDING) | | | |
| TC-235 | Hostiles de jour dans le Wild, immunité soleil, araignées #168 (PENDING) | | | |
| TC-236 | Hache du kit vs WorldEdit #192 — config serveur appliquée (PENDING) | | | |
| TC-237 | Journal : infobulle compacte + récupération sans doublon (PENDING) | | | |
| TC-238 | PNJ : nom en jeu et skin MineSkin depuis le panel #165 (PENDING) | | | |
| TC-239 | Création mob SPECIAL + BOSS depuis le panel #172 (navigateur) | | | |
| TC-240 | Couleurs et styles au clic, textes multi-styles préservés #195 (navigateur) | | | |
| TC-242 | Catalogue complet des objets, recherche FR + id #196 (navigateur) | | | |
| TC-243 | Suppression quête/story : aperçu, confirmation, blocages #194 | | | |
| TC-244 | Signal visuel PNJ : quête dispo / dialogue non lu #12 (PENDING) | | | |
| TC-245 | Claims : pas d'entrée sans retour, bypass doté, inventaire plein #22 (PENDING) | | | |
| TC-246 | Exploitation serveur : état, annonce, redémarrage vérifié, console #95 (PENDING) | | | |
| TC-247 | Rechargement du contenu : 3 états, contenu lié, runtime préservé #131 (PENDING) | | | |
| TC-248 | OP/DEOP, renvoi Hub, kick, whitelist #210 (PENDING) | | | |
| TC-249 | Monnaie : solde, journal, crédit/débit, plancher à zéro #140 (PENDING) | | | |
| TC-250 | Récompense monétaire de quête, bourse dans le journal #16 (PENDING) | | | |
| TC-251 | Récupération d'une récompense monétaire non payée #16 (PENDING) | | | |
| TC-252 | Groupes multiples, droits effectifs et provenance #199 (PENDING) | | | |
| TC-253 | Droits Minecraft par groupe/monde, collision externe #200 (PENDING) | | | |
| TC-254 | Édition d'un choix de dialogue avec actions/conditions #82 (PENDING) | | | |
| TC-255 | PNJ : regarder les joueurs et promenade depuis le panel #165 (PENDING) | | | |
| TC-256 | Entrée dans le Wild : avertissement, préparation, état au Garde #161/#26/#24 (PENDING) | | | |
| TC-257 | Rapporter des objets à un PNJ : dépôts partiels persistants #123 (PENDING) | | | |
| TC-258 | Paliers du kit de départ #218 (PENDING) | | | |
| TC-259 | Objectif SMELT_ITEM : cuire, pas obtenir #141 (PENDING) | | | |
| TC-260 | Objectif DISCOVER_WAYPOINT #185 (PENDING) | | | |
| TC-261 | Appariement des bornes du Hub #156 (PENDING) | | | |
| TC-262 | Contrat de contenu machine-readable #110 (PENDING) | | | |
| TC-263 | Texte stylé sans MiniMessage, partout #195 (PENDING) | | | |
| TC-264 | Import sécurisé d'un content pack #109 (PENDING) | | | |
| TC-265 | Atelier IA : créer une quête avec une IA #146 (PENDING) | | | |
| TC-266 | Atelier IA : dialogue et story #146 (PENDING) | | | |
| TC-267 | Correction IA, identifiant, nœuds exacts, fiche Mira, suppression d'un PNJ de test #222/#223/#224/#225/#226 (PENDING) | | | |
| TC-268 | Emplacement de construction : marquer en jeu, gérer et supprimer depuis le panel #213 (PENDING) | | | |
| TC-269 | Annuler la création d'un emplacement, et retour du panel sur sa page #227 (PENDING) | | | |
| TC-270 | Poser la hutte de test sur un emplacement, deux orientations, et annuler #213 (PENDING) | | | |
| TC-271 | Publier une quête sur DEV depuis PlugAdmin, sans build ni redémarrage #47 (PENDING) | | | |
| TC-272 | Diff source↔DEV, page « Changements en attente » et publication groupée #47 (PENDING) | | | |
| TC-273 | Onboarding nouveau joueur : reset cohérent, quête nommée, progression du kit #235 (PENDING) | | | |
| TC-274 | Cycle de vie d'un bâtiment : libérer, réorienter, remplacer, tour de garde #234 (PENDING) | | | |
| TC-275 | Diagnostic du réseau de voyage dans PlugAdmin : les cinq sections #156 | ✅ | | **VALIDÉ 2026-10-11** par le propriétaire — 23 instances, 16 puis 17 bornes, couverture, fiche par borne, 7 manques visibles, Wild 7/0, masquage sous filtre |
| TC-276 | Rattrapage ciblé d'une instance du Hub restée sans borne #156 | ✅ | | **VALIDÉ 2026-10-11** par le propriétaire — `minecraft:swamp@0,0`, SUCCESS/PAIRED, 16→17 bornes, 7→6 manques, borne physique et bouton fonctionnels en jeu |
| TC-277 | Reprise après mort dans le Wild : Hub → borne → waypoint découvert #156 (PENDING — **dernier critère de #156**) | | | |
| TC-278 | Filtre des instances du Hub : états, recherche, mobile #236 (PENDING) | | | |
| TC-302 | Mode bypass administrateur explicite, OP/DEOP × ON/OFF × Claims #35/#321 (PENDING) | | | |

---

### TC-267 — Correction IA, identifiant, nœuds exacts, fiche Mira, suppression d'un PNJ de test (issues #222, #223, #224, #225, #226, PENDING MANUAL VALIDATION)

-   **But :** vérifier les cinq correctifs du lot de l'après-midi du 2026-10-08, dans l'ordre où ils
    se rencontrent réellement. TC-265 et TC-266 couvrent déjà la sécurité de la clé, le fait que rien
    n'est enregistré sans confirmation, et la qualité du contenu produit : **ne pas les refaire ici**.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré**, Control Panel redéployé,
    catalogue PNJ **rafraîchi** (`PNJ → Rafraîchir`), un fournisseur d'IA fonctionnel, un compte
    PlugAdmin `OWNER` ou `ADMIN`.
-   ⚠️ **Ce test consomme des jetons facturés** (étapes 1 à 3).
-   🚫 **Ne supprimer AUCUN PNJ de gameplay.** Mira, le Garde, le Guide, Jo, Lily, Jeff et tous les
    autres PNJ du jeu sont **hors périmètre** de la partie suppression : elle se fait exclusivement
    sur le PNJ de test créé à l'étape 5. Mira n'est **consultée** qu'à l'étape 4.

#### 1. #222 — « Demander une correction » fonctionne vraiment

1.  `/ai/studio?kind=quest`. Remplir l'intention (« une petite quête de test »), **imposer**
    l'identifiant `tc267_correction`, imposer un **titre** stylé avec la palette, et écrire une
    contrainte libre reconnaissable (« pas de combat »).
2.  Provoquer un refus : dans « Contraintes supplémentaires », ajouter une phrase qui pousse le modèle
    à inventer un type, par exemple « utilise un objectif de type UTILISER_UN_OBJET ».
3.  **Demander une proposition** → la proposition doit être **refusée**, et le bandeau d'erreur doit
    être **rouge** (plus « bleu information » : c'était un défaut d'affichage corrigé dans ce lot).
4.  Vérifier avant de cliquer : le formulaire au-dessus est **toujours rempli** — intention,
    identifiant, titre, contrainte.
5.  Cliquer **« Demander une correction à l'IA »**.
6.  Attendu :
    -   une **nouvelle** proposition revient, passée par extraction → parsing → validation → aperçu ;
    -   le formulaire est **encore rempli** (c'est ce qui manquait : il se vidait) ;
    -   l'identifiant de la proposition corrigée est **toujours** `rpgquest:tc267_correction`, et le
        titre imposé est **toujours** celui de l'étape 1 — la correction n'a pas « oublié » les
        consignes ;
    -   si elle est encore refusée, les diagnostics affichés sont les **nouveaux**, et le bouton de
        correction est **toujours là**.
7.  Déplier « Réponse brute du modèle » → consultable.
8.  Vérifier le **journal d'audit** : une ligne `ai.correct` avec le même fournisseur et le même
    modèle que la génération initiale.
9.  Erreur d'API visible : dans `/ai/providers`, mettre temporairement une clé invalide, refaire une
    correction → bandeau **rouge** « L'appel a échoué … Rien n'a été enregistré », et **la proposition
    précédente et son bouton sont conservés** (« Relancer la correction »). Remettre la vraie clé.
10. Vérifier qu'**aucun fichier** n'est apparu dans `src/main/resources/quests/` sur la machine de
    build pendant toute cette étape.

#### 2. #223 — l'identifiant, dans les deux écritures

11. Saisir `tc267_local` → **Demander une proposition** → la proposition porte
    `rpgquest:tc267_local`.
12. Saisir `rpgquest:tc267_namespace` → la proposition porte `rpgquest:tc267_namespace`, et **jamais**
    `rpgquest:rpgquest:…`.
13. Saisir `testia:securiser_environs` → **refusé immédiatement**, avant tout appel : le message doit
    nommer le seul namespace accepté et proposer la clé à écrire. Vérifier qu'**aucun jeton n'a été
    consommé** (pas de nouvelle ligne `ai.generate` dans l'audit, pas de section « 3. Proposition »).
14. Saisir `Securiser Environs` (majuscules + espace) → refusé de la même façon, avant appel.
15. Lire l'aide sous le champ : elle doit dire que le namespace est ajouté automatiquement, et le
    **placeholder ne doit plus contenir `rpgquest:`**.
16. Même vérification pour une **story** (`/ai/studio?kind=story`) : l'aide dit qu'une story n'a pas
    de namespace, et `rpgquest:premiers_pas` est accepté avec le préfixe retiré.

#### 3. #224 — le nombre de nœuds est impératif

17. `/ai/studio?kind=dialogue`. Lire l'aide du champ « Nombre de nœuds » : elle doit dire que la valeur
    est **impérative** et que le panel **recompte** les nœuds.
18. Mettre **0**, choisir un PNJ de test (ou laisser vide), générer → la proposition est acceptée quel
    que soit le nombre de nœuds produit.
19. Mettre **5**, générer → compter les nœuds de la proposition :
    -   s'il y en a exactement 5 → acceptée ;
    -   sinon → **refusée** avec un bandeau rouge « Demande non respectée — 5 nœuds demandés,
        *N* générés », et le bouton de correction disponible. **C'est le cas que le ticket décrit** :
        avant ce lot, 4 nœuds pour 5 demandés passaient.
20. Si refus, cliquer **« Demander une correction »** → la contrainte de 5 nœuds doit être **répétée**
    dans la nouvelle demande (la proposition corrigée doit en avoir 5).
21. Mettre **12** (le maximum) et vérifier que le champ ne laisse pas saisir plus.
22. Vérifier le contraste : le champ « Nombre d'étapes » d'une **quête** doit annoncer, lui, qu'il
    s'agit d'une **indication** non vérifiée.

#### 4. #225 — la fiche Mira, en lecture seule

> 🚫 **Ne rien modifier et ne rien supprimer à cette étape.** On vérifie seulement que l'affichage
> dit enfin la vérité.

23. `/npcs` → **Rafraîchir** → chercher « mira ».
24. Attendu : **une seule** entrée Mira — `mira_cartographer`. L'entrée `mira_first_map` « sans
    définition » doit avoir **disparu** : elle n'était pas un PNJ, c'était le dialogue, déduit en PNJ
    faute de porteur.
25. Ouvrir la fiche `mira_cartographer`. Vérifier :
    -   badge **« définition »** et badge **« Citizens #9 »** ;
    -   section **Contenu** → « Dialogue » affiche **`rpgquest:mira_first_map`** avec son nombre de
        nœuds et de choix (et non « aucun dialogue », ni « pas encore chargé en jeu ») ;
    -   bouton « Ouvrir le dialogue » → mène bien à `rpgquest:mira_first_map` ;
    -   section **Diagnostics** → **aucune anomalie**.
26. En jeu : parler à Mira → le dialogue s'ouvre normalement. **Rien n'a été perdu.**
27. `/ai/studio?kind=dialogue` → mettre `mira_cartographer` comme PNJ porteur, écrire une intention
    quelconque, **Demander une proposition**.
28. Attendu — **aucun appel à l'IA n'a lieu** : une carte « Ce PNJ a déjà un dialogue — que faut-il
    faire ? » s'affiche, montrant le dialogue lié `rpgquest:mira_first_map`, l'id du PNJ, Citizens #9,
    et deux choix explicites. Vérifier dans l'audit qu'**aucune ligne `ai.generate` n'a été ajoutée**.
29. **Ne pas continuer** : revenir en arrière. L'objet de l'étape est l'arrêt, pas la génération.

#### 5. Créer un PNJ de test complet

30. `/npcs` → créer une définition `pnj_de_test_267` (nom affiché « PNJ de test 267 »).
31. Créer son PNJ Citizens depuis la fiche (ou le lier à un Citizens libre). **Noter son identifiant
    numérique Citizens** — il servira aux confirmations.
32. Créer un dialogue `rpgquest:pnj_de_test_267` et le choisir dans le champ **Dialogue** de la fiche.
33. **Rafraîchir** → la fiche doit afficher : définition, Citizens #N, dialogue lié.
34. En jeu : le PNJ est visible et son dialogue s'ouvre.

#### 6. #226 — le supprimer, couche par couche

> Tout se fait sur `pnj_de_test_267`. Après **chaque** opération : rafraîchir le catalogue PNJ, et
> lire le verdict dans le **journal d'actions** (les suppressions sont asynchrones).

35. Fiche du PNJ → **Zone de danger** → **« Supprimer… »**. Attendu sur l'aperçu :
    -   les **cinq couches** listées, présentes **et** absentes ;
    -   l'identifiant Citizens affiché ;
    -   le dialogue lié affiché, avec la mention qu'il **n'est jamais supprimé** ;
    -   quatre opérations, de la moins à la plus destructrice.
36. **Confirmation forte** : tenter de valider « Supprimer la définition logique seulement » en tapant
    un identifiant **approximatif** → refusé, « Confirmation incorrecte », rien n'est fait.
37. Tenter « Supprimer le PNJ Citizens physique » en tapant le bon identifiant de PNJ mais un **autre**
    identifiant Citizens → refusé. **C'est le garde-fou contre la destruction du voisin.**
38. **Délier** (B) : confirmer avec l'identifiant du PNJ **et** l'identifiant Citizens. Attendu :
    -   l'action `npc.citizens.unlink` réussit dans le journal ;
    -   après rafraîchissement, la fiche garde sa **définition** et son **dialogue**, et affiche
        « à lier » ;
    -   **en jeu, le PNJ Citizens est toujours là** — il ne répond simplement plus comme PNJ RPGQuest.
39. **Relier** le Citizens depuis la fiche (l'opération est réversible : c'est le point de B).
40. **Supprimer le Citizens physique** (C) : confirmer avec les deux identifiants. Attendu :
    -   `npc.citizens.delete` réussit ;
    -   **en jeu, le PNJ a disparu** ;
    -   la fiche garde sa **définition** et son **dialogue**, et redevient « à lier ».
41. **Supprimer la définition seule** (A) : confirmer. Attendu :
    -   `npc.definition.delete` réussit ;
    -   `npcs/pnj_de_test_267.yml` a disparu du serveur, et une **copie datée** existe dans
        `plugins/RPGQuest/npc-backups/` (vérification FTP) ;
    -   le **dialogue** `rpgquest:pnj_de_test_267` est **toujours là** — et réapparaît donc comme une
        entrée « sans définition » dans le catalogue, avec l'anomalie **« Dialogue sans PNJ porteur »**
        qui dit d'où elle vient et comment la corriger. **C'est le comportement attendu**, et c'est
        exactement ce qui expliquait la seconde entrée Mira.
42. **Dépendance bloquante** : recréer un PNJ de test `pnj_de_test_267b`, en faire le **destinataire
    d'une remise** (`DELIVER_ITEM_TO_NPC`) d'une quête de test, puis rouvrir l'aperçu de suppression.
    Attendu : l'opération « définition seule » et le « nettoyage complet » sont **bloqués**, la quête
    est **nommée**, et il n'y a **aucun bouton** à cliquer pour elles. Délier reste possible.
43. **Nettoyage complet** (D) sur un PNJ de test sans dépendance et avec un Citizens lié : les deux
    actions partent (`npc.citizens.delete` puis `npc.definition.delete`), et le **dialogue survit**.
44. **Double clic** : relancer deux fois la même suppression. Attendu : la seconde renvoie « rien à
    supprimer » / « rien à délier » dans le journal, **jamais une erreur**, et rien d'autre n'est
    touché.
45. **Permissions** : se connecter avec un compte de rôle **Éditeur de contenu** → la **Zone de
    danger** ne doit pas apparaître, et `/npcs/delete?npc=…` saisi à la main doit renvoyer
    « Accès refusé ».

#### 7. Vérification finale — Mira est intacte

46. `/npcs` → **Rafraîchir** → ouvrir `mira_cartographer`. Attendu, identique à l'étape 25 :
    définition, Citizens #9, dialogue `rpgquest:mira_first_map`, **aucune anomalie**.
47. En jeu : parler à Mira → le dialogue s'ouvre. **Rien n'a été perdu par les suppressions de test.**
48. Vérifier qu'aucun autre PNJ de gameplay n'a bougé : le Garde, le Guide, Jo, Lily, Jeff gardent
    leur définition, leur Citizens et leur dialogue.

#### Critères de réussite

-   #222 : une proposition refusée se corrige **sans ressaisie**, en conservant l'identifiant et le
    titre imposés ; une erreur d'API est visible **et** ne fait pas perdre la proposition.
-   #223 : les deux écritures de l'identifiant fonctionnent, un namespace étranger est refusé **avant**
    l'appel, et `rpgquest:rpgquest:` n'apparaît jamais.
-   #224 : `0` laisse libre ; une valeur `N > 0` est soit respectée, soit refusée avec le compte exact.
-   #225 : une seule entrée Mira, son dialogue réel affiché, et aucune génération silencieuse d'un
    second dialogue.
-   #226 : un PNJ de test se supprime couche par couche depuis le panel, sans commande Minecraft, sans
    SQL, sans édition manuelle de YAML ; un dialogue n'est jamais emporté ; une dépendance bloque ; et
    aucun PNJ de gameplay n'est affecté.

---

### TC-268 — Emplacement de construction : marquer en jeu, gérer et supprimer depuis le panel (issue #213, PENDING MANUAL VALIDATION)

-   **But :** vérifier le socle « emplacement de construction » de bout en bout : un clic en jeu
    crée un point d'ancrage persistant, le Control Panel le montre et le gère, et le supprimer ne
    touche **aucun bloc du monde**.
-   **Durée :** environ 5 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré** (la migration V28 et l'outil
    vivent dans le plugin), Control Panel redéployé, un compte PlugAdmin `OWNER` ou `ADMIN`, et un
    compte en jeu OP (ou portant `rpgquest.admin.buildsite`).
-   🚫 **Ne construire AUCUN bâtiment pendant ce TC.** Ce lot ne sait rien poser ; il n'y a rien à
    placer, et tenter de le faire ne testerait rien.
-   ✅ Aucun contenu existant n'est concerné : ce TC ne touche ni quête, ni dialogue, ni story, ni
    PNJ.

#### 1. Obtenir l'outil

1.  En jeu : `/rpgadmin buildsite tool`. Attendu : message « Outil d'emplacement reçu » et une
    **houe en fer** nommée « Outil d'emplacement de construction » dans l'inventaire.
2.  Vérifier qu'elle **n'est pas** une hache en bois : la wand WorldEdit doit rester intacte et
    utilisable comme avant.

#### 2. Marquer un emplacement

3.  Se rendre dans `world_hub`, sur un bout de terrain plat et dégagé.
4.  **Se tourner vers l'est** (la boussole F3, ou simplement vérifier à l'étape 8).
5.  **Clic droit** sur le bloc de sol visé — noter ses coordonnées, visibles en F3.
6.  Attendu : une **enclume** s'ouvre, et le chat annonce « Emplacement préparé en `world_hub`
    X/Y/Z, orienté est ». **Rien n'est encore créé** (c'est #227).
7.  Taper **« Test hutte »** dans l'enclume, puis **cliquer l'objet de résultat** (case de droite).
    Attendu : la fenêtre se ferme et un message vert énonce le nom, l'identifiant `buildsite_000N`,
    le monde, la position et l'orientation. Le clic sur le résultat doit être possible **sans aucun
    niveau d'expérience**.
8.  **Vérifier l'ancrage** : le `Y` annoncé doit être **celui du bloc cliqué + 1** — l'ancre est la
    case libre au-dessus du sol, là où reposera le bâtiment. C'est la règle du ticket, et c'est le
    point le plus facile à casser.
9.  `/rpgadmin buildsite list` → l'emplacement apparaît avec son monde, sa position et son
    orientation. L'orientation doit bien être **est** si vous regardiez vers l'est.

#### 3. Anti-doublon

10. **Re-cliquer exactement le même bloc.** Attendu : message jaune « Un emplacement existe déjà
    ici : `buildsite_000N` », et **l'enclume ne s'ouvre pas** — il n'y a rien à nommer.
11. **Spammer le clic droit** une demi-douzaine de fois au même endroit, aussi vite que possible.
    Attendu : **une seule** enclume, pas une pile de fenêtres (anti-rebond de 500 ms). Fermer la
    fenêtre : aucun emplacement créé.
12. Cliquer un bloc **immédiatement voisin** de `buildsite_000N` : l'enclume s'ouvre, précédée d'un
    **avertissement** « un autre emplacement est à 1 bloc ». Ce n'est **pas** un refus. Valider un
    nom, puis vérifier que ce second emplacement voisin existe bien : aucune distance minimale n'est
    imposée. (Le supprimer ensuite à l'étape 14.)

#### 4. Le Control Panel

13. Ouvrir PlugAdmin → **Bâtiments → Emplacements** → **Rafraîchir**.
14. Attendu sur la fiche de `buildsite_000N` :
    -   **Monde** `world_hub` ;
    -   **Position** exactement les X/Y/Z de l'étape 6 ;
    -   **Orientation** « est » ;
    -   **État** « vide » ;
    -   **Créé par** votre pseudo, et la date ;
    -   **Nom** « Test hutte » — celui tapé dans l'enclume, **pas** « Nouvel emplacement ».
15. Vérifier qu'il n'y a **aucun bouton « Créer »**, et que la carte du bas explique où créer un
    emplacement (en jeu, avec l'outil). C'est volontaire.
16. **Renommer** en « Test hutte 2 » → la fiche affiche le nouveau nom, et l'identifiant
    `buildsite_000N` **n'a pas changé**.
17. Ajouter une **description** (« Test TC-268 »), enregistrer, puis **vider le champ** et
    réenregistrer → la description disparaît. Un champ vide est une valeur valide.
18. **Changer l'orientation** en `NORTH` → la fiche affiche « nord », et la **position est
    inchangée**. (Remettre `EAST` si vous voulez enchaîner avec l'étape 21.)
19. Si plusieurs mondes portent des emplacements : vérifier le **filtre par monde** et le retour
    « Tous les mondes ». Vérifier aussi la **recherche** (taper « hutte », puis une coordonnée).

> ⚠️ À chacune des étapes 16 à 19, vous devez **rester sur « Bâtiments → Emplacements »**. Être
> renvoyé sur la page « Agents » est exactement le défaut #227 — s'il réapparaît, le reste du TC
> devient illisible, car on ne peut plus constater les résultats.

#### 5. Persistance — l'étape qui compte

20. **Redémarrer le serveur Minecraft.**
21. Reconnecter, puis `/rpgadmin buildsite list` → `buildsite_000N` est **toujours là**, avec son
    nom « Test hutte 2 », sa position et son orientation.
22. Panel → **Rafraîchir** → la fiche est identique à l'étape 18. Rien n'a été perdu.

#### 6. Supprimer, sans toucher au monde

23. **Avant de supprimer** : se placer en jeu à l'emplacement et **noter précisément les blocs
    présents** (une capture d'écran suffit).
24. Panel → fiche → **Zone de danger** → cocher la case → **Supprimer l'emplacement**.
25. Attendu : l'action part, et après **Rafraîchir** l'emplacement a disparu de la liste.
26. `/rpgadmin buildsite list` → il n'y est plus non plus.
27. **Retourner sur place en jeu** : **aucun bloc n'a été modifié**. C'est le point le plus
    important de l'étape — un emplacement n'est qu'un repère.
28. **Double clic** : relancer la suppression du même identifiant (ou cliquer deux fois).
    Attendu : « rien à supprimer », **jamais une erreur**.
29. Créer un nouvel emplacement et vérifier que son identifiant **ne réutilise pas** celui qui vient
    d'être supprimé (si `buildsite_0003` a été supprimé, le suivant est `buildsite_0004`).

#### 7. Permissions

30. Avec un compte en jeu **sans** `rpgquest.admin.buildsite` ni `rpgquest.admin.world` :
    `/rpgadmin buildsite tool` → refusé avec le nom de la permission manquante.
31. Lui donner l'outil par un autre moyen (le jeter au sol et le ramasser avec l'autre compte), puis
    **clic droit** → refusé, avec le nom de la permission. **Ni enclume, ni emplacement.**
32. Dans PlugAdmin, avec un compte de rôle **Éditeur de contenu** : l'entrée « Bâtiments » ne doit
    pas apparaître, et `/buildings/sites` saisi à la main doit renvoyer « Accès refusé ».
33. Avec un rôle **Builder** : la page est **visible** (c'est voulu — un repère de construction est
    son métier), mais les formulaires de modification et la zone de danger n'apparaissent pas.

#### Critères de réussite

-   Un clic droit **prépare** un emplacement sans rien écrire ; le nom validé dans l'enclume le crée,
    persistant, à l'ancre attendue (**bloc cliqué + 1 en Y**) et avec l'orientation du regard.
-   Le spam de clics et le re-clic au même endroit ne créent pas de doublons.
-   Aucune action du panel ne renvoie sur la page « Agents » (#227).
-   Le Control Panel montre monde, position, orientation, état, date et auteur, et permet de
    renommer, décrire et réorienter — jamais de déplacer.
-   Tout survit à un redémarrage du serveur.
-   La suppression retire le repère, **ne modifie aucun bloc**, est idempotente, et ne recycle pas
    l'identifiant.
-   Un joueur sans la permission ne crée rien, même avec l'outil en main.

---

### TC-269 — Annuler la création d'un emplacement, et retour du panel sur sa page (issue #227, PENDING MANUAL VALIDATION)

-   **But :** vérifier les deux promesses de #227, celles qu'aucun test automatisé ne peut constater
    à votre place : **un clic de travers ne laisse rien**, et **aucune action du panel ne vous jette
    sur la page « Agents »**.
-   **Durée :** environ 6 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré**, Control Panel redéployé, un
    compte PlugAdmin `OWNER` ou `ADMIN`, un compte en jeu portant `rpgquest.admin.buildsite`.
-   **Aucune migration** dans ce lot : `V28` est déjà appliquée (TC-268).
-   🚫 **Ne construire AUCUN bâtiment, ne poser aucun schematic.** Ce lot ne place toujours rien.
-   ✅ Ce TC ne touche ni quête, ni dialogue, ni story, ni PNJ.

#### 1. Le compte de départ

1.  `/rpgadmin buildsite list` → **noter le nombre exact** d'emplacements, et le dernier
    identifiant attribué (par exemple `buildsite_0004`). Tout ce TC se juge par rapport à ce
    relevé.

#### 2. Les quatre façons d'annuler — aucune ne doit créer quoi que ce soit

2.  **Fermer la fenêtre.** Clic droit avec l'outil → l'enclume s'ouvre → appuyer sur **Échap**.
    Attendu : « Création annulée : aucun emplacement n'a été créé ».
3.  **Fermer après avoir tapé un nom.** Clic droit → taper « Jamais créé » → **Échap** sans cliquer
    le résultat. Attendu : le même message d'annulation.
4.  **Laisser expirer.** Clic droit → ne rien faire pendant **plus de 60 secondes** → puis cliquer
    le résultat. Attendu : « Demande expirée ou déjà traitée : rien n'a été enregistré ».
5.  **Se déconnecter pendant la saisie.** Clic droit → quitter le serveur fenêtre ouverte → se
    reconnecter.
6.  `/rpgadmin buildsite list` → **le nombre de l'étape 1, inchangé**. Aucun des quatre gestes n'a
    créé d'emplacement.
7.  Panel → **Bâtiments → Emplacements → Rafraîchir** → la liste est elle aussi identique.

#### 3. Aucun identifiant gaspillé

8.  Créer maintenant un emplacement **pour de vrai** : clic droit, taper « Test TC-269 », cliquer le
    résultat.
9.  Attendu : son identifiant est **celui qui suit immédiatement** le dernier noté à l'étape 1
    (`buildsite_0005` si vous étiez à `0004`). Les quatre annulations n'ont consommé **aucun**
    numéro.

#### 4. Un nom refusé laisse la fenêtre ouverte

10. Clic droit sur un bloc libre → **effacer le nom** (champ vide) → cliquer le résultat. Attendu :
    « Le nom est obligatoire… », et **la fenêtre reste ouverte**.
11. Taper un nom valide et valider : l'emplacement est créé. On ne doit **pas** avoir eu à retourner
    cliquer dans le monde.
12. Recommencer avec un nom de **plus de 64 caractères** : refusé, fenêtre ouverte, et le nom n'est
    **jamais tronqué en silence**.

#### 5. L'avertissement de voisinage — un avertissement, pas un refus

13. Cliquer un bloc **immédiatement adjacent** à l'emplacement créé à l'étape 8. Attendu : le chat
    affiche « Attention : un autre emplacement est à 1 bloc — … », **et l'enclume s'ouvre quand
    même**.
14. Fermer la fenêtre : rien n'est créé. Recliquer, valider un nom : l'emplacement voisin **est**
    créé. L'avertissement ne bloque pas.

#### 6. #227 — le panel revient sur sa page, à chaque action

15. Panel → **Bâtiments → Emplacements** → ouvrir la fiche de `Test TC-269`.
16. Pour **chacune** des cinq actions ci-dessous, vérifier après l'envoi que l'URL de la page est
    bien `…/buildings/sites` et que la liste est **toujours visible** :
    -   **Renommer** ;
    -   **Décrire** ;
    -   **Changer l'orientation** ;
    -   **Rafraîchir** ;
    -   **Supprimer** (zone de danger, case cochée).
17. Attendu à chaque fois : un bandeau de suivi d'action, **sur la page Emplacements**. Si l'URL
    devient `…/agents?agent=…`, c'est #227 qui est revenu — l'arrêter là et le signaler.
18. Provoquer un **refus** : renommer avec un champ **vide**. Attendu : retour sur
    `…/buildings/sites` avec un bandeau d'erreur **rouge** (« Un emplacement garde toujours un
    libellé »), et **aucune** action envoyée au serveur.
19. Après la suppression de l'étape 16, cliquer **Rafraîchir** : la liste reflète la suppression.
    C'est précisément ce que #227 rendait impossible à constater.

#### 7. Ce que le clic n'a pas touché

20. Retourner en jeu à l'endroit des étapes 2 à 5 et 13 : **aucun bloc n'a été posé, cassé ni
    labouré** — ni par l'outil, ni par les annulations. L'outil est une houe : vérifier en
    particulier que le sol n'a **pas** été labouré.
21. Vérifier que la **hache WorldEdit** fonctionne toujours normalement (sélectionner deux coins) :
    les deux outils ne se disputent pas le clic.

#### Critères de réussite

-   Les quatre façons d'annuler (fermeture, fermeture après saisie, expiration, déconnexion) ne
    créent **aucun** emplacement, et le disent.
-   Aucun identifiant n'est consommé par une annulation.
-   Un nom vide ou trop long est refusé **sans fermer la fenêtre**, et jamais tronqué.
-   L'emplacement créé porte le nom tapé dans l'enclume.
-   L'avertissement de voisinage s'affiche et **ne bloque pas**.
-   **Les cinq actions du panel reviennent sur « Bâtiments → Emplacements »**, succès comme refus.
-   Aucun bloc du monde n'est modifié, et WorldEdit reste intact.

---

### TC-270 — Poser la hutte de test sur un emplacement, deux orientations, et annuler (issue #213, PENDING MANUAL VALIDATION)

-   **But :** valider **toute la chaîne** `BuildingSite → BuildingDefinition → schematic → aperçu →
    rotation → collage`, avec une hutte volontairement simple, puis vérifier que le retour arrière
    restaure réellement la zone.
-   **Durée :** environ 12 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré** (la migration V29, la
    bibliothèque et la génération du schematic ont lieu au démarrage), Control Panel redéployé, un
    compte PlugAdmin `OWNER` ou `ADMIN`, un compte en jeu portant `rpgquest.admin.buildsite`, et
    **WorldEdit installé** (il l'est sur le DEV, 7.4.1).
-   ⚠️ **C'est le premier TC qui écrase des blocs réels.** Choisissez un terrain plat, **dégagé et
    sans valeur** dans `world_hub` — pas à côté d'un PNJ, d'un portail, d'une borne ni d'une
    construction existante.
-   ✅ Ce TC ne touche ni quête, ni dialogue, ni story, ni PNJ.

#### 1. Le bâtiment existe, et son fichier a bien été produit

1.  En jeu : `/rpgadmin building list`. Attendu : **une** entrée, `test_hut_01` « Hutte de test »,
    `7 × 5 × 6`, façade **nord**.
2.  Panel → **Bâtiments → Bibliothèque** → **Rafraîchir**. Attendu sur la fiche :
    -   dimensions `7 × 5 × 6` ;
    -   ancre `3 / 1 / 0` ;
    -   façade de référence **nord** ;
    -   badge vert **« schematic présent »**.
3.  Vérifier qu'il n'y a **aucun bouton** de création, de téléversement ni d'édition : la
    bibliothèque est en lecture seule, et la page le dit.
4.  Si le badge est rouge (« schematic absent ») : `/rpgadmin building generate` en jeu, puis
    **Rafraîchir**. Si cela échoue, le message nomme la cause — ne pas continuer sans fichier.

#### 2. Préparer un emplacement, orienté à l'est

5.  En jeu, sur le terrain dégagé : `/rpgadmin buildsite tool`, **regardez vers l'est**, puis
    **clic droit** sur un bloc de sol. Nommez-le **« Test hutte est »** dans l'enclume et validez.
6.  **Noter les coordonnées exactes** annoncées (l'ancre), et **prendre une capture d'écran du
    terrain** : c'est elle qui servira à juger la restauration à l'étape 20.
7.  Panel → **Bâtiments → Emplacements** → **Rafraîchir** → ouvrir la fiche. État : **vide**.

#### 3. L'aperçu — rien ne doit être écrit à cette étape

8.  Dans la fiche, section **Bâtiment** : choisir « Hutte de test », puis **Choisir un bâtiment**.
9.  Attendu dans l'**Aperçu de la pose** :
    -   **Rotation appliquée : 90°** (façade nord amenée vers l'est) ;
    -   **emprise** `x1..x2 / y1..y2 / z1..z2` ;
    -   **emprise (dimensions) : 5 × 7 × 6** — la largeur et la profondeur sont **échangées**, et la
        page l'explique. C'est le point le plus facile à casser de tout le lot ;
    -   **210 blocs** ;
    -   un nombre de **blocs non-air** déjà présents (de l'herbe, c'est normal).
10. **Vérifier l'emprise à la main** : `y1` doit être **l'ancre − 1** (la fondation s'enfonce d'un
    bloc), et `y2` l'ancre + 4.
11. **Retourner en jeu et vérifier qu'aucun bloc n'a changé.** L'aperçu ne doit **rien** avoir écrit.

#### 4. Poser

12. Panel → fiche → cocher la case → **Placer dans le monde**.
13. **Rafraîchir**. Attendu : l'emplacement est **occupé**, et la section **Bâtiment posé** indique
    le bâtiment, la rotation `90°`, l'emprise et la date.
14. **Aller en jeu sur place.** Vérifier, dans cet ordre :
    -   la hutte **existe** ;
    -   la **porte regarde l'est** (c'est l'orientation de l'emplacement) ;
    -   le **seuil de la porte est exactement à l'ancre notée à l'étape 6** ;
    -   la **fondation en pierre** est un bloc plus bas ;
    -   dimensions au sol **5 × 7** (tournée), hauteur **6** ;
    -   **intérieur vide** — aucun meuble ;
    -   **aucune décoration extérieure**, aucun arbre, aucune clôture, aucun terrain aplani autour.
15. `/rpgadmin buildsite list` → l'emplacement est toujours là.

#### 5. Persistance

16. **Redémarrer le serveur Minecraft.**
17. Panel → **Rafraîchir** → l'emplacement est **toujours occupé**, avec la même emprise et la même
    rotation. La hutte est toujours en jeu.

#### 6. La seconde orientation — le test qui attrape une erreur de signe

18. Créer un **second** emplacement, loin du premier (au moins 20 blocs), en **regardant vers le
    nord**, nommé « Test hutte nord ». Choisir « Hutte de test ».
19. Attendu : **rotation 0°** et **emprise 7 × 5 × 6** (aucun échange d'axes). Poser, puis vérifier
    en jeu que la **porte regarde le nord**.

> ⚠️ Si la porte de l'une des deux huttes regarde la mauvaise direction, **arrêter ici et le
> signaler** : c'est une erreur de convention de rotation, et elle rendrait tout placement futur
> faux. Le serveur refuse normalement un collage dont l'emprise ne correspond pas à celle annoncée,
> donc un refus explicite est aussi une information utile — notez le message.

#### 7. Annuler la pose

20. Fiche du **premier** emplacement → **Annuler la pose** → cocher → **Restaurer la zone d'avant**.
21. **Rafraîchir** : l'emplacement est redevenu **vide**, et la section « Bâtiment posé » a disparu.
22. **Aller en jeu** : la hutte a disparu, et le terrain est **tel qu'avant** — comparer avec la
    capture d'écran de l'étape 6. L'herbe, les dénivelés et les blocs d'origine doivent être revenus.
23. Relancer l'annulation sur le même emplacement : attendu **« Aucun bâtiment enregistré »**, jamais
    une erreur.

#### 8. Les refus

24. Sur l'emplacement **encore occupé** (le second) : vérifier qu'il **ne propose pas** de choisir un
    autre bâtiment — il faut d'abord annuler.
25. Créer un emplacement **à 2 blocs** du second (donc dans son emprise), et tenter un aperçu.
    Attendu : refus **« L'emprise chevauche … »**. Le repère voisin est permis, c'est la **matière
    posée** qui ne peut pas se superposer.
26. Avec un compte PlugAdmin de rôle **Builder** : la bibliothèque est **visible**, mais ni
    « Choisir un bâtiment », ni « Placer », ni « Annuler » n'apparaissent.
27. Avec un rôle **Éditeur de contenu** : l'entrée « Bâtiments » ne doit pas apparaître, et
    `/buildings/library` saisi à la main doit renvoyer « Accès refusé ».

#### 9. Nettoyage

28. Annuler la pose du second emplacement, puis supprimer les emplacements de test créés ici.
    **Ne pas supprimer** les emplacements `buildsite_0002` à `buildsite_0005` existants.

#### Critères de réussite

-   La bibliothèque montre la hutte avec ses dimensions, son ancre et son schematic présent.
-   L'aperçu annonce la bonne rotation et la bonne emprise, **sans rien écrire**.
-   À 90°, l'emprise est bien **5 × 7 × 6** et non 7 × 5 × 6.
-   La hutte apparaît **exactement à l'ancre**, porte face à l'orientation de l'emplacement, aux deux
    orientations testées.
-   Le seuil de la porte est à l'ancre, la fondation un bloc plus bas.
-   Intérieur vide, aucune décoration, aucun terrain modifié autour de l'emprise.
-   Tout survit à un redémarrage.
-   L'annulation restaure le terrain **tel qu'avant**, et une seconde annulation est inoffensive.
-   Un emplacement occupé et un chevauchement sont refusés, avec un motif lisible.
-   Un Builder consulte sans pouvoir poser.

---

### TC-271 — Publier une quête sur DEV depuis PlugAdmin, sans build ni redémarrage (issue #47, PENDING MANUAL VALIDATION)

-   **But :** vérifier la promesse de #47 — **créer/modifier du contenu et le rendre actif en jeu
    sans lancer de build, sans redémarrer le serveur, sans transfert manuel**.
-   **Durée :** environ 6 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré une fois** (pour installer le
    moteur), Control Panel redéployé, un compte PlugAdmin `OWNER` ou `ADMIN`.
-   ✅ Ce TC ne touche ni bâtiment, ni PNJ, ni monde. Il n'écrit qu'un fichier de contenu.
-   ⚠️ **Le redémarrage des pré-requis installe le moteur ; il ne fait pas partie du parcours
    utilisateur.** Tout l'intérêt du TC est qu'ensuite, publier n'en demande plus.

#### 1. L'état de départ

1.  PlugAdmin → **Quêtes** → **Rafraîchir** (bouton « Quêtes »), puis **« État DEV »**.
2.  Attendu : le bandeau orange « l'état du contenu sur le serveur DEV n'est pas encore relevé »
    **disparaît** après le clic sur « État DEV ».
3.  Ouvrir la fiche de **`tc265_ai_securiser_environs`**. Section **Publication sur DEV**. Attendu :
    -   badge **« Source uniquement »** ;
    -   l'explication « absente du serveur DEV : elle n'existe pas en jeu » ;
    -   **Source** : une empreinte ; **Serveur DEV** : « absente » ;
    -   **Chargée par le moteur** : non ;
    -   un bouton **« Publier sur DEV »**.

#### 2. Publier

4.  Cocher la case de confirmation, puis **Publier sur DEV**.
5.  Attendu : retour **sur la page Quêtes** (jamais sur « Agents » — c'était le défaut #227), avec un
    bandeau de suivi d'action.
6.  Attendre quelques secondes, puis **Rafraîchir** (« Quêtes » puis « État DEV »).
7.  Attendu sur la fiche :
    -   badge **« Synchronisé »** ;
    -   **Source** et **Serveur DEV** affichent la **même** empreinte ;
    -   **Chargée par le moteur** : **oui** ;
    -   une ligne **« Dernière publication »** avec l'heure et le code `PUBLISHED`.

> ⚠️ Si le badge affiche **« Publié, non chargé »**, le fichier est arrivé mais le moteur ne le voit
> pas : c'est presque toujours que l'identifiant déclaré dans le YAML ne correspond pas au nom du
> fichier. Le message le dit. Noter le cas et le signaler.

#### 3. La preuve en jeu — c'est l'étape qui compte

8.  En jeu : `/rpgadmin quest list` → **`tc265_ai_securiser_environs` doit apparaître**.
9.  **Vérifier qu'aucun build n'a été lancé et que le serveur n'a pas redémarré** : la console ne
    doit montrer qu'un rechargement de la famille « quêtes », pas un démarrage de plugin.

#### 4. Modifier puis republier

10. PlugAdmin → fiche de la quête → modifier quelque chose d'anodin (la description, par l'éditeur
    guidé) → **Enregistrer**.
11. **Rafraîchir** → **« État DEV »**. Attendu : badge **« Différent »**, et l'explication « vos
    dernières modifications ne sont pas en jeu ».
12. **Republier sur DEV** → rafraîchir → **« Synchronisé »**, avec une **nouvelle** empreinte des
    deux côtés.
13. En jeu : `/rpgadmin quest list` montre la quête, et la modification est visible dans le journal
    de quêtes.

#### 5. Revenir en arrière

14. Fiche → section **« Restaurer la version précédente »** (elle n'apparaît que parce qu'un
    remplacement a eu lieu, donc qu'une sauvegarde existe).
15. Cocher, puis **Restaurer la version précédente**.
16. **Rafraîchir** → attendu : l'empreinte **DEV** est revenue à celle d'avant l'étape 12, et l'état
    est **« Différent »** (la source, elle, porte toujours votre modification — c'est normal et
    c'est la bonne réponse : on a restauré DEV, pas la source).
17. En jeu : la quête existe toujours, dans sa version d'avant.

#### 6. Les refus

18. Ouvrir la fiche dans **deux onglets**. Dans l'onglet A, publier. Dans l'onglet B (dont
    l'affichage est désormais périmé), publier aussi.
19. Attendu dans l'onglet B : un **refus** expliquant que le fichier DEV ou la source a changé depuis
    l'analyse. **Rien n'est écrasé en silence.**
20. Avec un compte de rôle **Éditeur de contenu** : la section « Publication sur DEV » affiche
    l'état, mais **aucun bouton Publier** n'apparaît.

#### Critères de réussite

-   Une quête « Source uniquement » devient **active en jeu** depuis le panel, **sans build ni
    redémarrage**.
-   « Synchronisé » n'apparaît qu'après que le moteur a confirmé — et les deux empreintes concordent.
-   `/rpgadmin quest list` voit la quête.
-   Une modification non publiée s'affiche **« Différent »**, et non « Synchronisé ».
-   La restauration ramène la version précédente sur DEV.
-   Une publication concurrente est **refusée**, jamais appliquée en écrasant.
-   Un Éditeur de contenu voit l'état sans pouvoir publier.
-   Toutes les actions reviennent **sur la page Quêtes**.

---

### TC-272 — Diff source↔DEV, « Changements en attente » et publication groupée (issue #47, PENDING MANUAL VALIDATION)

-   **But :** vérifier ce qu'aucun test automatisé ne peut établir — que la comparaison est
    **lisible**, que la page d'ensemble est **utilisable**, et que tout cela tient **sur un
    téléphone**.
-   **Durée :** environ 8 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré une fois** (la lecture ciblée d'un
    fichier DEV est une nouvelle action côté plugin), Control Panel redéployé, compte `OWNER` ou
    `ADMIN`.
-   ✅ Ce TC ne touche ni bâtiment, ni PNJ, ni monde.
-   ⚠️ **Faites-le au moins une fois sur téléphone** : c'est le seul point du lot qui n'a aucune
    couverture automatique.

#### 1. Le diff, sur une ressource réellement différente

1.  PlugAdmin → **Quêtes** → **Rafraîchir** puis **« État DEV »**.
2.  Prenez une quête **Synchronisée**, et modifiez-la légèrement dans l'éditeur guidé (la
    description suffit) → **Enregistrer**. Ne publiez pas.
3.  **Rafraîchir** → **« État DEV »**. Attendu : badge **« Différent »**, et deux boutons :
    **« Voir les différences »** et **« Republier sur DEV »**.
4.  Cliquer **« Voir les différences »**, attendre le résultat, puis **rafraîchir la page**.
5.  Attendu dans le bloc **Différences** :
    -   un résumé chiffré (« 1 ligne(s) ajoutée(s), 1 retirée(s) ») ;
    -   une ligne **`-`** avec l'ancien texte, une ligne **`+`** avec le nouveau ;
    -   des **numéros de ligne** en tête (DEV puis source) ;
    -   du **contexte** autour du changement.
6.  **Vérifier le sens de lecture** : le `+` doit porter **votre** modification (la source), le `-`
    l'ancien texte (ce qui est encore sur DEV). Si c'est inversé, **arrêtez et signalez-le** : tout
    le reste en découle.
7.  Vérifier qu'**aucun chemin de fichier** n'apparaît dans le bloc.

#### 2. Accents et balisage

8.  Mettez dans la description un accent et des chevrons, par exemple
    `Sécuriser <les> environs ☃` → **Enregistrer** → **Voir les différences**.
9.  Attendu : le texte s'affiche **tel quel**, chevrons compris, et la page n'est pas cassée. Les
    `<les>` doivent apparaître **comme du texte**, pas disparaître.

#### 3. Changements en attente

10. **Contenu → Changements en attente**.
11. Attendu : la quête modifiée y figure, avec son type, son nom, son état **Différent**, les deux
    empreintes courtes, « chargée : oui », et un bouton **Ouvrir**.
12. Vérifier que les ressources **Synchronisées n'y sont pas** : cette page ne liste que ce qui
    demande une décision.
13. Essayer les filtres **Quêtes / Dialogues / Stories**, puis les filtres d'état, puis la
    **recherche** par un fragment d'identifiant. Attendu : l'URL change et reste **partageable**
    (copier-coller dans un autre onglet doit redonner la même vue).

#### 4. Publication groupée

14. Modifier une **deuxième** ressource (une story ou un dialogue) → **Enregistrer**.
15. **Changements en attente** → **« État DEV »** pour rafraîchir → les deux apparaissent.
16. **Cocher les deux**, puis **« Publier la sélection »**.
17. Attendu :
    -   retour **sur la page Changements en attente** ;
    -   un bandeau « 2 publication(s) demandée(s) » ;
    -   une section **« Dernières publications »** listant **les deux ressources séparément**,
        d'abord en **`○` en attente**.
18. Attendre quelques secondes, **rafraîchir**, puis **« État DEV »**.
19. Attendu : les deux passent en **`✓` Synchronisé**, et **disparaissent de la liste** (elles ne
    demandent plus de décision).
20. Vérifier qu'il n'existe **aucun bouton « publier tout »**.

#### 5. « Actualisation en cours »

21. Publier une ressource depuis sa fiche, puis **rafraîchir la page immédiatement**, sans cliquer
    « État DEV ».
22. Attendu : l'état affiche déjà **Synchronisé**, avec la mention **« Actualisation en cours »**
    expliquant que l'état vient du compte rendu de la publication. Il ne doit **jamais** afficher
    « Source uniquement » après une publication réussie.

#### 6. Conflit : pas de remplacement aveugle

23. Ce cas demande de modifier le fichier **sur le serveur** hors du panel. Si vous ne voulez pas le
    faire, **sautez cette section** — elle est couverte automatiquement.
24. Sinon : modifier à la main un fichier de quête déjà publié sur le serveur, puis dans le panel
    **« État DEV »**.
25. Attendu : badge **« Conflit »**, un bandeau rouge, **« Voir les différences »**, et **AUCUN**
    bouton de publication.
26. Cliquer **« Voir les différences »**, rafraîchir. Attendu : le bouton apparaît alors, nommé
    **« Écraser la version DEV »**, avec la mention que la modification sera perdue.

#### 7. Mobile — la partie qui n'a aucune couverture automatique

27. Ouvrir **Changements en attente** sur téléphone. Attendu : le tableau **défile
    horizontalement** sans casser la page, et les filtres passent à la ligne.
28. Ouvrir une fiche et déplier un **diff** sur téléphone. Attendu : bloc monospace à défilement
    horizontal, hauteur limitée (il ne doit pas repousser tout le reste hors de l'écran).
29. Vérifier que les boutons restent atteignables au pouce.

#### Critères de réussite

-   Le diff est **lisible**, avec numéros de ligne et contexte, et le `+` porte bien la source.
-   Accents et chevrons s'affichent tels quels, sans casser la page.
-   « Changements en attente » ne liste que ce qui demande une décision, et les filtres sont des
    URL partageables.
-   La publication groupée donne un résultat **par ressource**, jamais un succès global.
-   Aucun bouton « publier tout ».
-   Après une publication, l'état ne régresse **jamais** vers « Source uniquement ».
-   En conflit, aucun bouton de publication avant d'avoir vu la différence.
-   **Tout est utilisable sur téléphone.**

### TC-273 — Onboarding nouveau joueur : reset cohérent, quête nommée, progression du kit (issue #235, PENDING MANUAL VALIDATION)

-   **But :** refaire **exactement** le parcours qui a révélé les trois défauts, et constater qu'un
    nouveau joueur comprend quoi faire **sans explication extérieure**.
-   **Durée :** environ 10 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré une fois** (deux nouvelles actions
    agent + nouveaux messages), Control Panel redéployé, compte `OWNER` ou `ADMIN`.
-   ⚠️ **Le reset complet vide l'inventaire, l'équipement et le coffre de l'Ender.** À faire sur un
    compte de test, ou après avoir accepté la perte. L'écran l'annonce avant confirmation : lisez-le.
-   ✅ Ce TC ne touche ni bâtiment, ni PNJ, ni monde, ni `data.db` d'un autre joueur.

#### 1. Les deux resets sont distincts, et disent ce qu'ils font

1.  PlugAdmin → **Joueurs** → votre joueur → **Resets joueur (2 portées)**.
2.  Constatez **deux blocs** : « Reset progression RPGQuest <small>(inventaire conservé)</small> » et
    « Reset nouveau joueur complet <small>(DEV / vide l'inventaire)</small> ».
3.  Dans le premier, lisez l'avertissement : il doit dire que le droit au kit est rétabli mais qu'un
    kit **déjà reçu restera dans l'inventaire**, donc qu'un **second kit** sera possible — et
    pourquoi (outils vanilla indiscernables).
4.  Dans le second, vérifiez que « armure », « main secondaire », « curseur » et « coffre de l'Ender »
    sont **cités**.

> **Ce qui serait un échec :** un seul bouton « Reset », ou un bloc qui n'annonce pas le sort de
> l'inventaire.

#### 2. Aperçu avant de confirmer

1.  Cliquez **Aperçu (aucune écriture)** dans le bloc **complet**.
2.  La ligne « Inventaire COMPLET (vanilla inclus) » doit compter vos objets, **Ender compris**.
3.  Vérifiez en jeu que **rien n'a été retiré** : un aperçu n'écrit jamais.
4.  Vérifiez les deux lignes « Droit au kit de départ » et « Palier de kit ».

#### 3. Reset complet, puis reconnexion

1.  Confirmez le **reset nouveau joueur complet** (case à cocher + bouton).
2.  Reconnectez-vous.
3.  Inventaire, armure, main secondaire et coffre de l'Ender **vides**, conformément au résumé lu à
    l'étape 1. Une **Rune de rappel** peut être redonnée automatiquement : c'est normal (#26).

#### 4. Le Guide se présente, et le kit ne se dédouble plus

1.  Parlez au **Guide**. Son accueil doit expliquer son rôle : expliquer le monde, dire à qui parler,
    **et remettre l'équipement de secours**.
2.  « Demander mon kit de départ » → **exactement un** kit (4 outils en bois).
3.  Redemandez **sans mourir** → refus explicite (« Tu as déjà reçu ton kit de départ. »).

#### 5. La quête d'introduction est nommée avant de démarrer

1.  Choisissez « **Que dois-je faire pour commencer ?** ».
2.  Le Guide **explique** et **nomme** la quête (« Premiers pas »). À ce stade, **rien n'a démarré**.
3.  Choisissez « **Commencer la quête : Premiers pas** ».
4.  Vous devez voir le `Title` « Quête commencée » **et**, dans le chat, l'en-tête puis la ligne
    d'objectif — par exemple « • Parler à libraire (x1) » — avec le renvoi vers `/quests`.

> **Ce qui serait un échec :** « Très bien, j'y vais. » encore présent, ou un overlay sans aucune
> indication de ce qu'il faut faire.

#### 6. La progression du kit, lisible en jeu

1.  Reparlez au Guide → « **Comment améliorer mon kit ?** ».
2.  Vous devez lire :
    -   `Kit actuel : Palier 1 — Nouveau venu`
    -   `Prochaine amélioration : Palier 2 — Premiers pas dans le Wild`
    -   `À me rapporter :` puis **1 bâton, 2 pierres, 4 cuirs, 3 graines de blé**, chacun en `0/n`
    -   l'explication de la remise **progressive** et du fait que ce qui est remis est **sécurisé**.
3.  Choisissez « **Commencer la quête : Premiers pas dans le Wild** ».
4.  Les quatre objectifs doivent apparaître dans le chat au démarrage.

> **Ce qui serait un échec :** aucune option de kit, un palier affiché « inconnu », ou des quantités
> qui ne correspondent pas à `kit_tier2.yml`.

#### 7. Enchaînement direct sur TC-257 / #123

1.  Récoltez une partie des matériaux (par exemple 1 bâton et 1 pierre).
2.  Revenez au Guide → « **J'ai des matériaux à te remettre** » → « Donner les matériaux que j'ai ».
3.  Reparlez au Guide → « Comment améliorer mon kit ? » : les lignes doivent montrer le **déjà
    remis** (`✔ Bâton 1/1`, `• Pierre 1/2`…).
4.  À ce point, **TC-257 peut commencer sans intervention obscure**.

#### Ce qui n'est PAS couvert par ce TC

-   Le palier 2 réellement obtenu (il faut terminer la quête) — c'est TC-257/#123.
-   Les paliers 3 à 5, dont le contenu n'est pas décidé.

### TC-274 — Cycle de vie d'un bâtiment posé : libérer, réorienter, remplacer (issue #234, PENDING MANUAL VALIDATION)

-   **But :** constater qu'un emplacement occupé **n'est plus une impasse**, et que la tour de garde
    est réellement visitable. Rien ici ne peut être établi sans un client : la correction d'un
    collage, l'orientation d'un escalier et le fait de pouvoir monter se constatent avec ses jambes.
-   **Durée :** environ 20 minutes.
-   **Pré-requis :** JAR de ce lot déployé **et serveur redémarré une fois** (migration V30 +
    quatre nouvelles actions agent), Control Panel redéployé, compte `OWNER` ou `ADMIN`, WorldEdit
    actif.
-   ⚠️ **Ces opérations écrivent des blocs réels.** Faites-les sur un emplacement de test, pas sur
    une construction que vous tenez à garder.
-   ✅ Le terrain d'origine est sauvegardé avant la première pose : chaque étape est réversible par
    « Libérer l'emplacement ».

#### 0. Préparer un emplacement de test

1.  En jeu, avec la houe en fer : marquez un **nouvel** emplacement sur du terrain plat et dégagé,
    loin de toute construction. Nommez-le dans l'enclume (par exemple « Essais 234 »).
2.  PlugAdmin → **Bâtiments → Emplacements** → « Rafraîchir ». Ouvrez sa fiche.
3.  La **bibliothèque** doit proposer **deux** bâtiments : « Hutte de test » (7 × 5 × 6) et
    **« Tour de garde de test » (9 × 9 × 14)**.

#### A. LIBÉRER — la priorité du lot

1.  Posez la **Hutte de test** (aperçu, puis « Placer »).
2.  En jeu : **capturez l'état avant** — faites une capture d'écran du terrain autour, de face.
3.  Revenez sur la fiche. Dans « Bâtiment posé », vérifiez la ligne **« Terrain d'origine »** :
    elle doit dire « terrain d'origine (1 sauvegarde) ».
4.  Cliquez **« Restaurer le terrain et libérer »** (zone rouge, confirmation).
5.  En jeu : **le terrain est revenu** à ce qu'il était. Comparez avec la capture.
6.  Sur la fiche : l'emplacement est **vide**, et la bibliothèque est de nouveau proposée.
7.  **Reposez la hutte sur le même emplacement** : cela doit marcher sans rien recréer.

> **Ce qui serait un échec :** un emplacement annoncé vide avec une hutte encore debout, un terrain
> « presque » revenu, ou l'obligation de recréer un emplacement.

#### B. ROTATION — l'intention n'est pas le fait

1.  Libérez l'emplacement, puis **reposez la hutte** en notant son orientation en jeu.
2.  Sur la fiche, changez l'**orientation de l'emplacement** (« Corriger l'orientation ») vers une
    autre direction.
3.  En jeu : **la hutte n'a pas tourné**. C'est voulu.
4.  Sur la fiche, deux lignes doivent apparaître l'une sous l'autre :
    - `Orientation souhaitée du site : …`
    - `Orientation du bâtiment posé : …°`
    et un avertissement : « L'orientation de l'emplacement a changé après le placement. Le bâtiment
    physique n'a pas été modifié. »
5.  Dans « Réorienter ou remplacer » : laissez « Hutte de test », choisissez la **nouvelle**
    orientation, cliquez **« Calculer l'aperçu »**.
6.  L'aperçu doit montrer **l'ancienne et la nouvelle emprise**, et le recouvrement.
7.  Cliquez **« Réorienter le bâtiment »**.
8.  En jeu : la hutte est maintenant orientée comme l'emplacement. La **porte** doit regarder la
    bonne direction.
9.  Sur la fiche : l'avertissement de divergence a disparu.

> **Ce qui serait un échec :** une hutte qui tourne toute seule à l'étape 2, un aperçu qui donne la
> même emprise qu'avant, ou une porte qui regarde ailleurs après la réorientation.

#### C. REMPLACEMENT — et la tour, vraiment visitable

1.  Dans « Réorienter ou remplacer », choisissez **« Tour de garde de test »**, orientation
    **NORTH**, puis **« Calculer l'aperçu »**.
2.  L'aperçu doit annoncer une **emprise plus grande** (9 × 9 × 14) et un avertissement disant que
    la nouvelle emprise sort de l'ancienne.
3.  Cliquez **« Remplacer le bâtiment »**.
4.  **En jeu, visitez la tour :**
    - la **porte** est au nord, avec **deux torches** de part et d'autre à l'intérieur ;
    - **entrez** : le rez-de-chaussée est vide et praticable ;
    - **montez** l'escalier le long du mur **sud**, vers l'est — il doit se monter **sans sauter** ;
    - au premier étage, l'escalier suivant est le long du mur **est**, vers le nord ;
    - au deuxième, le long du mur **nord**, vers l'ouest ;
    - vous débouchez sur la **terrasse à créneaux**. On ne doit pas pouvoir tomber en marchant droit.
5.  **Faites le tour de la tour par l'extérieur** et vérifiez que les quatre faces sont
    reconnaissables :
    - **nord** : la porte et deux fenêtres ;
    - **est** : une meurtrière par niveau ;
    - **sud** : une large ouverture de guet, **au dernier niveau seulement** ;
    - **ouest** : aveugle, en **moellon brut** là où les autres sont en pierre taillée.

> **Ce qui serait un échec :** un escalier qu'il faut sauter pour monter, un plancher qui bloque la
> tête au milieu d'une volée, une face impossible à distinguer d'une autre, ou des blocs de tour
> restés en dehors de son emprise.

#### D. MULTI-ESSAIS — le terrain d'origine ne dérive pas

1.  Remplacez la tour par la **hutte** (aperçu, puis confirmer).
2.  Remplacez la hutte par la **tour**, dans une **autre orientation**.
3.  Réorientez la tour encore une fois.
4.  Sur la fiche, cliquez **« Relever le journal »** : vous devez voir **toutes** les opérations,
    de la plus récente à la plus ancienne, avec leur type, leur rotation et leur résultat.
5.  Cliquez **« Restaurer le terrain et libérer »**.
6.  **En jeu : le terrain doit être celui du tout début**, celui de la capture de l'étape A.2 — pas
    « le terrain avec la tour », pas « le terrain avec la hutte ».

> **C'est le test le plus important du lot.** Si le terrain restauré porte encore la trace d'un
> bâtiment, la baseline a dérivé, et toute la chaîne d'expérimentation est fausse.

#### E. Versions (facultatif, 2 minutes)

1.  Sur le serveur, ouvrez `plugins/RPGQuest/buildings/test_hut_01.yml` et changez `version: 1` en
    `version: 2`. Rechargez le contenu (ou redémarrez).
2.  Sur la fiche d'un emplacement portant la hutte : un bandeau doit annoncer qu'« une version plus
    récente existe », **sans que le bâtiment posé ait changé**.
3.  Remettez `version: 1`.

#### Ce qui n'est PAS couvert par ce TC

-   La génération IA de bâtiment, l'import de schematic externe, l'éditeur de plan : hors périmètre.
-   Les villages/villes : l'architecture les prépare (chaque emplacement reste indépendant), mais
    aucun moteur de regroupement n'existe encore.

---

### TC-275 — Diagnostic du réseau de voyage dans PlugAdmin (issue #156, ✅ VALIDÉ 2026-10-11)

**Pourquoi ce test existe** : la question « pourquoi seulement 13 bornes ? » est restée sans réponse
pendant des semaines parce que l'écran ne disait ni la politique (les bornes n'existent que dans le
Hub), ni le déclencheur (une borne n'est cherchée que lorsqu'un joueur traverse l'instance), ni les
chiffres réels. Ce test vérifie que la réponse est maintenant **lisible sans ouvrir le jeu**.

**Durée** : 5 minutes. **Aucune mutation** : tout est en lecture.

**Chiffres attendus au relevé du 2026-10-10** (ils augmentent si quelqu'un explore entre-temps — ce
qui compte est leur cohérence entre elles, pas leur valeur exacte) : 23 instances du Hub, 16 bornes,
16 paires complètes, 7 instances sans borne, 0 borne orpheline.

1.  PlugAdmin → **Réseau de voyage** → **Rafraîchir**.
2.  Section **« Réseau Hub — diagnostic »** : vérifier les cinq chiffres, la phrase « les bornes de
    voyage existent **uniquement** dans le monde Hub », et celle qui dit que le nombre de bornes
    mesure **l'exploration réelle du Hub** et qu'il n'existe **aucun balayage du monde**.
3.  Section **« Couverture du Hub »** : distance à la borne la plus proche min **99** / moyenne
    **131** / médiane **134** / max **165** blocs ; étendue X `25 → 885`, Z `-1194 → -13`.
4.  Section **« Bornes du Hub — fiche par borne »** : ouvrir une fiche. Elle doit nommer l'instance
    servie, et la distance à son waypoint doit tomber **entre 6 et 16 blocs** (l'anneau configuré,
    cité juste à côté).
5.  Section **« Instances connues du Hub »** : **7** lignes marquées « sans borne », chacune avec la
    cause **« jamais tenté depuis le dernier démarrage »**.
6.  Section **« Réseau du Wild — Waystones »** : **7 Waystones, 0 découverte**, avec l'avertissement
    disant que le réseau de voyage du Wild est en pratique inexistant pour les joueurs.
7.  Taper n'importe quoi dans la recherche, ou choisir un monde : **le diagnostic doit disparaître**,
    remplacé par la phrase « Filtre actif : le diagnostic du réseau complet est masqué ». Retirer le
    filtre le rétablit.

#### Résultat réel — 2026-10-11, par le propriétaire

| Point | Constaté |
|---|---|
| Diagnostic global | visible et **cohérent** |
| Instances du Hub | **23** |
| Bornes | **16**, puis **17** après le rattrapage de TC-276 |
| Métriques de couverture | OK |
| Fiche par borne | OK |
| Instances sans borne | **7** visibles |
| Réseau du Wild | **7 Waystones / 0 découverte** |
| Masquage sous filtre | **propre** |

**Ce que ce TC ne couvre pas** : il décrit ce qui est **enregistré en base** au dernier relevé, jamais
une vérification physique des blocs. Pour cela, `/rpgadmin travel diagnose` et `repair`/`restore`.

---

### TC-276 — Rattrapage ciblé d'une instance restée sans borne (issue #156, ✅ VALIDÉ 2026-10-11)

**Pourquoi ce test existe** : l'appariement n'est déclenché que par le déplacement d'un joueur dans
l'instance, et les compteurs d'essai vivent en mémoire. Les 7 instances en retard datent d'avant le
correctif du 8 octobre, n'ont jamais été revisitées, et **rien dans le jeu** ne pouvait les
débloquer. Ce test valide le seul remède : une instance désignée, un essai.

**Durée** : 5 minutes, dont un aller-retour en jeu. **Mutation réelle du monde** : une borne est
posée (≈ 12 blocs, sur un sol libre et accessible).

1.  Dans **« Instances connues du Hub »**, ouvrir la fiche de **`wp_world_hub_swamp_0_0`** (position
    `161, 27` — la plus proche du spawn, donc la plus rapide à vérifier en jeu).
2.  Lire le texte du bloc d'action : il doit annoncer **cette instance seulement**, aucun balayage du
    monde, aucune autre borne, aucune densité modifiée.
3.  Cocher la confirmation, cliquer **« Apparier cette instance »**, puis **Rafraîchir**.
4.  Attendu : l'instance passe **« borne présente »**, le compte des instances sans borne tombe de
    **7 à 6**, les paires complètes montent de **16 à 17**, et la nouvelle borne a sa fiche avec une
    distance à son waypoint **entre 6 et 16 blocs**.
5.  **En jeu** : se rendre sur place. La borne doit être posée sur un sol accessible (pas sur un
    feuillage, pas en surplomb) et son bouton doit ouvrir le menu de voyage.
6.  Relancer l'action sur la **même** instance : elle doit être **refusée** avec le message « Cette
    instance a déjà une borne appariée : … ».
7.  *(Facultatif)* Tenter l'action sur une instance d'un terrain très accidenté : un refus
    « Aucun emplacement accessible trouvé entre 6 et 16 blocs… » est une **issue normale**, pas une
    panne — le message doit citer les distances réellement configurées.

#### Résultat réel — 2026-10-11, par le propriétaire

| Point | Constaté |
|---|---|
| Instance visée | `minecraft:swamp@0,0`, waypoint `wp_world_hub_swamp_0_0` |
| Action depuis PlugAdmin | **`SUCCESS` / `PAIRED`** |
| Bornes après rafraîchissement | **16 → 17** |
| Instances sans borne | **7 → 6** |
| Fiche de l'instance (`swamp 161,27`) | passée à **« borne présente »** |
| En jeu | borne **physiquement présente** |
| Bouton de la borne | **fonctionnel**, ouvre le menu de voyage |
| Après appariement | l'action « Apparier cette instance » **n'est plus proposée** |

Ce dernier point est le comportement voulu, et le propriétaire l'a explicitement confirmé comme
correct : **l'interface ne propose pas une mutation devenue inutile.** C'est une conséquence directe
de la conception — le formulaire de rattrapage n'est rendu que dans la branche « instance sans
borne » de la fiche, jamais pour une instance équipée. Le refus côté serveur (« Cette instance a
déjà une borne appariée ») reste la seconde barrière, pour le cas d'un formulaire resoumis depuis
une page périmée.

**Ce que ce TC ne couvre pas** : il ne valide **aucun** changement d'équilibrage. La densité, les
distances et les probabilités sont inchangées ; les quatre scénarios chiffrés du rapport
`2026-10-10_1340_audit-reseau-voyage.md` sont proposés et **non activés**.

---

### TC-277 — Reprise après mort dans le Wild (issue #156, PENDING MANUAL VALIDATION)

**Pourquoi ce test existe** : c'est le **dernier critère d'acceptation de #156**, et c'est l'objectif
gameplay que le ticket désigne comme principal — « après une mort dans le Wild, le joueur doit
pouvoir retrouver rapidement une zone déjà explorée sans devoir recommencer une longue traversée
dangereuse depuis zéro ». Le parcours est mécaniquement en place et vérifié dans le code (une borne
liste les waypoints `discoveredBy` du joueur, groupés par monde, et le Wild compte 44 découvertes),
mais « praticable en conditions réelles » ne se déduit pas d'une lecture de code.

**Durée** : ~10 minutes, un client réel. **Aucune donnée à préparer.**

**À mesurer, et c'est le point du test** : le **temps réel** entre le respawn et le retour sur la
zone explorée. C'est ce chiffre qui dira si l'équilibrage est suffisant.

1.  Dans le Wild, se rendre sur une zone déjà explorée et **noter un waypoint déjà découvert**
    (bouton déjà cliqué une fois).
2.  Mourir dans le Wild — par un mob, une chute, peu importe. Noter l'heure.
3.  Au respawn : vérifier où le joueur réapparaît, et s'il comprend **sans aide** ce qu'il doit faire.
4.  Rejoindre une borne du Hub. **Noter la distance parcourue à pied** pour l'atteindre.
5.  Cliquer le bouton de la borne : le menu de voyage doit s'ouvrir, proposer le monde `wild`, et y
    lister le waypoint noté à l'étape 1.
6.  Voyager vers ce waypoint. Vérifier l'arrivée au bon endroit, dans la zone déjà explorée.
7.  **Noter le temps total** respawn → arrivée.

**Critère de réussite** : le parcours aboutit, sans commande admin, sans aide extérieure, et le temps
total reste raisonnable au regard du danger du Wild. Si le parcours échoue à une étape, c'est un
défaut fonctionnel de #156 et le ticket reste ouvert.

**Si le parcours aboutit mais est trop lent** : ce n'est pas un bug, c'est de l'équilibrage. Les
quatre scénarios chiffrés de `docs/claude-reports/2026-10-10_1340_audit-reseau-voyage.md` sont là
pour cette décision — le plus rentable étant de rendre les Waystones du Wild trouvables (0 découverte
sur 7 aujourd'hui), ce qui ne crée **aucune** structure.

**Ce que ce TC ne couvre pas** : il ne valide aucun changement d'équilibrage, et n'en active aucun.

---

### TC-278 — Filtre des instances du Hub (issue #236, PENDING MANUAL VALIDATION)

**Pourquoi ce test existe** : la demande vient du terrain, pendant TC-275 — « dommage qu'on ne puisse
pas filtrer pour n'afficher que les instances sans borne ». Le point à vérifier n'est pas seulement
que le filtre marche, mais qu'il **ne touche pas aux chiffres du réseau** : une moyenne calculée sur
une sélection, présentée comme la couverture réelle, serait pire que pas de filtre du tout.

**Durée** : 3 minutes. **Lecture seule**, aucune mutation.

1.  `/travel` → **Rafraîchir** → descendre à « Instances connues du Hub ».
2.  Cliquer **Sans borne**. Attendu : seules les instances en manque restent, le compteur passe à
    **« 6/23 »**, et — le point du test — les sections « Réseau Hub — diagnostic », « Couverture du
    Hub », « fiche par borne » et « Réseau du Wild » au-dessus sont **inchangées**.
3.  Cliquer **Avec borne** : **« 17/23 »**, et plus aucune ligne « sans borne ».
4.  Cliquer **Toutes** : retour à **« 23/23 »**.
5.  Taper `swamp` puis **Filtrer**. Changer d'état : la recherche doit être **conservée**. Cliquer
    **Effacer** : elle doit disparaître.
6.  Recopier des coordonnées lues à l'écran, par exemple **`161,27`** : l'instance correspondante doit
    sortir. Puis une valeur absurde (`zzz`) : un état vide explicite doit s'afficher, **en rappelant
    que les statistiques portent sur le réseau complet**.
7.  **Sur téléphone** : la barre de filtre et la recherche doivent passer à la ligne proprement, et un
    clic sur un état doit revenir **à la section**, pas en haut de page.

**Ce que ce TC ne couvre pas** : aucun changement de gameplay, aucune mutation, aucun réglage de
densité — c'est un filtre d'affichage.

### TC-279 — Reprendre le plan IA de la Bibliothèque du Hub, puis le prévisualiser (issue #256, PENDING MANUAL VALIDATION)

**Pourquoi ce test existe** : le plan de l'action `7a1ab38d-1b11-4e9d-901a-3e2749ca90ff` a été payé,
audité (`SUCCESS`), puis perdu de vue : « Auditer » quittait le parcours et la fiche d'action ne
ramenait qu'à `/actions`. Le test vérifie qu'on le **reprend** et qu'on le **prévisualise** sans
aucun nouvel appel à l'IA.

**Durée** : 5 minutes. **N'appelle pas l'IA, ne pose rien, n'écrit rien sur le serveur** (l'aperçu
est en lecture). Ne **pas** cliquer « Générer » pendant ce test.

1.  Ouvrir `/actions/7a1ab38d-1b11-4e9d-901a-3e2749ca90ff`. Attendu : un bandeau « Cette action
    transporte un plan de bâtiment complet », avec un choix d'emplacement et **« Reprendre ce
    plan »**.
2.  Choisir **Bibliothèque du Hub (buildsite_0009)** → **Reprendre ce plan**. Attendu : la page de
    génération, sur « Plan en cours — version 1 », origine **« Récupéré depuis une action »**.
3.  Le verdict est **déjà là** : « Plan conforme … — **mais en écart avec le brief** », avec la phrase
    « le brief demande 2 niveaux, mais la grille construite n'en compte que 1 ». Carte **orange**,
    pas verte.
4.  Au-dessus de « Générer », un bandeau dit que `bibliotheque_du_hub` **existe déjà** et propose
    `bibliotheque_du_hub_v2`.
5.  Cliquer **Prévisualiser**. Attendu : retour **sur le même brouillon** (pas sur `/actions`), avec
    « L'aperçu est en attente de la réponse du serveur ». Cliquer **Rafraîchir** après quelques
    secondes : les coupes s'affichent dans « Aperçu 2D ».
6.  Fermer l'onglet, se déconnecter, se reconnecter, rouvrir `/buildings/plan?site=buildsite_0009` :
    le plan est listé avec **« Reprendre ce plan »**, et le reprendre réaffiche verdict et aperçu.
7.  Revenir sur `/actions/7a1ab38d…` : le bandeau dit désormais **« Retour à ce brouillon »**.
8.  Dans `/ai/providers` ou les journaux du fournisseur : **aucune** consommation nouvelle depuis le
    début du test.

**Ce que ce TC ne couvre pas** : la génération et la pose (décision du propriétaire, et
l'identifiant `bibliotheque_du_hub` est pris par la V1 de `buildsite_0012`).

### TC-280 — Supprimer un dialogue depuis PlugAdmin, puis la quête qu'il démarrait (issue #248, PENDING MANUAL VALIDATION)

**Prérequis** : JAR **et** panel issus de #248 déployés sur DEV. Avec un JAR antérieur, l'étape 4
s'arrête sur un refus de l'agent (« Paramètre « kind » attendu ») sans rien toucher : c'est attendu.

**Durée** : 15 minutes. **Supprime réellement** `rpgquest:help` et `help_recolte` sur DEV (avec
sauvegarde) — à ne faire que si ce nettoyage est souhaité.

1.  `/dialogues` → **Rafraîchir**. Ouvrir la fiche de `help` : bouton **« Supprimer le dialogue… »**.
2.  Aperçu attendu : « Hors source », « Aucun PNJ RPGQuest lié », référence sortante
    « démarre la quête « help_recolte » » marquée **non supprimée**, mention des guides de Hub
    vérifiés par le serveur, formulaire de confirmation.
3.  **Refus sur relevé périmé** : attendre plus de 15 minutes sans rafraîchir, recharger l'aperçu →
    « Vérification incomplète », aucun formulaire, bouton « Rafraîchir le relevé des dialogues ».
    Rafraîchir, rouvrir l'aperçu.
4.  Retaper `help` → **Supprimer définitivement**. Page de résultat : lien « Suivre l'action ».
    Double-cliquer volontairement : une **seule** action `content.definition.delete` dans
    `/actions`.
5.  Ouvrir l'action : `SUCCESS`, message « Fichier … supprimé du serveur (sauvegarde : …) », nombre
    de dialogues rechargés, **0 erreur de chargement**. `/dialogues` → Rafraîchir : `help` a disparu.
6.  `/quests` → `help_recolte` → **Supprimer…** : plus aucune référence (l'aperçu aurait cité
    `rpgquest:help` « hors source » avant l'étape 4). Supprimer.
7.  **Refus PNJ lié** : aperçu de suppression de `jo` (ou `guide`) → bloqué, le PNJ est nommé, aucun
    formulaire. Aucun PNJ, aucune quête, aucune progression touchés.
8.  **Redémarrage** : redémarrer le serveur ; `help` ne revient pas. (Pour `guard`, exemple du JAR :
    ne PAS le supprimer pendant ce test — il est lié au Garde, l'aperçu le refuse d'ailleurs.)
9.  **Restauration** (si besoin) : recopier le fichier de `RPGQuest/content-backups/<horodatage>-dialogues/`
    dans `RPGQuest/dialogues/`, puis déclencher un rechargement du contenu depuis PlugAdmin (ou,
    pour un dialogue source, le republier depuis le panel).

### TC-281 — « Arrêter la promenade » d'un PNJ depuis PlugAdmin (issue #216, PENDING MANUAL VALIDATION)

**Prérequis** : JAR contenant #216 déployé (correctif côté plugin). **Durée** : 3 minutes.

1.  `/npcs` → un PNJ Citizens lié (ex. celui de TC-255) → section comportement → **Activer la
    promenade** (rayon 25, amplitude 3). Constater en jeu qu'il se déplace.
2.  Cliquer **Arrêter la promenade**. Attendu : action `SUCCESS`, message « Promenade désactivée,
    déplacement interrompu — position finale x / y / z ». Plus de « Paramètre « x_range » manquant ».
3.  En jeu : le PNJ s'immobilise. La fiche Citizens indique **immobile** après rafraîchissement.
4.  Recliquer **Arrêter** : message explicite « La promenade n'est pas active sur ce PNJ … rien n'a
    été modifié », sans erreur technique.
5.  Vérifier que Look Close, le dialogue et les quêtes du PNJ sont inchangés.

### TC-282 — Créer une quête DISCOVER_WAYPOINT depuis PlugAdmin (issue #240, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #240 déployé (panel seul, aucun JAR). **Durée** : 5 minutes. Débloque
TC-260 (#185).

1.  `/quests/new` → objectif **Découvrir des waypoints**. Le champ « Mondes » propose **une case par
    monde chargé** (ex. `world_hub`, `wild`) et un champ « Autre(s) ».
2.  Créer `rpgquest:test_discover_waypoints` : quantité 2, cocher `wild`, règle `NEW_ONLY` →
    **Vérifier** : aucune anomalie. **Enregistrer** : plus de « Divergence de sérialisation ».
3.  Rouvrir la quête : `wild` est coché, `world_hub` ne l'est pas, la règle est `NEW_ONLY`.
4.  Cocher aussi `world_hub`, passer en `INCLUDE_EXISTING`, enregistrer, rouvrir : les deux mondes et
    la règle sont conservés.
5.  Saisir un monde inconnu dans « Autre(s) » : **Vérifier** l'avertit (« hors des valeurs
    connues »), sans bloquer.
6.  Reprendre TC-260 à partir de cette quête.

### TC-283 — Agir sur la quête ouverte depuis sa fiche (issue #241, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #241 déployé (panel seul). **Durée** : 3 minutes.

1.  `/quests` → déplier la fiche de `test_smelt_item` → section **Agir sur un joueur** : « Choisir
    d'abord un joueur » avec un lien. Le suivre, saisir `LoDyMcFly`, **Sélectionner** : la fiche de
    `test_smelt_item` est **toujours dépliée**.
2.  La fiche affiche le joueur et la quête, et trois boutons : **Démarrer cette quête**, **Compléter
    cette quête**, **Réinitialiser cette quête**. Aucune mention de `crystal_hunt`.
3.  **Démarrer cette quête** (confirmer) : retour sur la même fiche dépliée, résultat de l'action
    affiché dans la fiche, quête démarrée en jeu.
4.  En bas, « Actions admin sur une autre quête » : le sélecteur part de `test_smelt_item`. Sans fiche
    ouverte (`/quests?player=LoDyMcFly`), il affiche « — choisir une quête — » et refuse d'envoyer
    sans choix.
5.  Sur téléphone : boutons lisibles, retour à la ligne correct.

### TC-284 — Ouvrir une action depuis sa ligne de l'historique (issue #266, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #266. **Durée** : 2 minutes. Sur PC **et** sur téléphone.

1.  `/actions` → filtrer « Réussies » et chercher « bâtiment ».
2.  Cliquer n'importe où sur une ligne : la fiche de l'action s'ouvre. Revenir, cliquer sur le
    libellé : même résultat. Revenir, cliquer **Voir le détail** : même résultat.
3.  Au clavier : Tab jusqu'au libellé, Entrée → la fiche s'ouvre.
4.  Sur la fiche, **Retour à l'historique** : la liste revient **avec le filtre et la recherche**.
5.  Ouvrir un audit de plan lié à un brouillon : le bandeau propose **Retour à ce brouillon**.

### TC-285 — « Retirer le bâtiment et restaurer le terrain » sur un emplacement occupé (issues #239, #265, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #239 (panel seul). **Durée** : 3 minutes. **N'exécutez pas** la
restauration pendant ce test, sauf décision explicite du propriétaire : on vérifie l'affichage et les
refus.

1.  `/buildings/sites` → **Rafraîchir** → fiche de `buildsite_0012` (occupé par la Bibliothèque du
    Hub V1). Juste sous « Bâtiment posé », un bloc **« Retirer le bâtiment et restaurer le terrain »**
    explique que le bâtiment est retiré, que le terrain revient tel qu'il était avant sa première pose
    et que **l'emplacement est conservé**.
2.  Le bouton n'est actif qu'après avoir tapé `buildsite_0012` et coché la case. Taper un autre
    identifiant puis valider : refus « retapez exactement l'identifiant », rien dans `/actions`.
3.  En bas de fiche, « **Supprimer l'emplacement (le repère seulement)** » : sur cet emplacement
    occupé, aucun bouton — un message explique qu'il faut d'abord retirer le bâtiment.
4.  Connecté avec un compte **Builder** : le bloc est visible avec « Votre rôle ne permet pas… », sans
    formulaire.
5.  (Seulement sur décision du propriétaire) Restaurer réellement : retour `SUCCESS`, puis
    **Rafraîchir** → l'emplacement est **libre**, le terrain d'origine est revenu en jeu.

### TC-286 — Créer un bâtiment dans l'Atelier, sans emplacement, et reprendre la Bibliothèque du Hub (issue #267, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #267. **Durée** : 10 minutes. Étape 4 = un appel IA facturé : à faire
**seulement** si le propriétaire le décide ; tout le reste est gratuit.

1.  Menu **Bâtiments** : trois entrées, dans l'ordre **Atelier de création**, **Bibliothèque**,
    **Emplacements**.
2.  **Atelier** : la section « Reprendre un travail commencé sur un emplacement » propose
    `buildsite_0009` (Bibliothèque du Hub) avec sa description et **2 plan(s)**. Cliquer **Reprendre
    dans l'Atelier** : le projet s'ouvre, description copiée, entrée « Ouest », plans rattachés. La
    fiche de `buildsite_0009` (Emplacements) n'a pas changé. Recliquer depuis l'Atelier : aucun second
    projet.
3.  Dans le projet repris : changer le **nom technique** en `bibliotheque_du_hub_v2`, enregistrer.
    Ouvrir « Versions du plan » : les deux brouillons sont là. Reprendre celui de l'audit `7a1ab38d` :
    l'étape « Enregistrer » est mise en avant, avec l'avertissement « ne correspond pas tout à fait à
    votre description » (2 niveaux demandés, 1 construit).
4.  (Facultatif, facturé) **Nouveau projet** « Tour de guet », décrire, **Créer un plan avec l'IA** ;
    recharger la page : aucun nouvel appel.
5.  **Vérifier le bâtiment** : retour sur le projet, « Vérification en cours », puis **Rafraîchir** :
    résultat en français, sans code dans la carte principale.
6.  **Détails techniques → Copier le diagnostic** : coller dans un éditeur — projet, version, résultat,
    plan ; aucune clé.
7.  Ancienne adresse `/buildings/plan?site=buildsite_0009` : on arrive sur le projet repris.
8.  Sur téléphone : étapes lisibles, un bouton principal par écran.

### TC-287 — Emplacements = des lieux, Bibliothèque → « Placer sur un emplacement » (issue #268, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #268. **Durée** : 5 minutes. Gratuit ; **aucune pose ni restauration** à
confirmer pendant ce test.

1.  `/buildings/sites` → **Rafraîchir** → fiche de `buildsite_0009` : le champ s'appelle **« Description
    du lieu »** ; aucun « Brief architectural », aucun « Générer un bâtiment avec l'IA ».
2.  Sur la même fiche, l'ancienne description du bâtiment est **repliée**, lisible (« Lire la description
    (488 caractères) »), sans champ d'édition. Si TC-286 a été fait : un lien vers le projet « Bibliothèque
    du Hub » de l'Atelier ; sinon : « Reprendre dans l'Atelier ».
3.  Cliquer le lien (ou la reprise) : on arrive sur le projet. Revenir : la fiche de l'emplacement est
    inchangée, `/actions` ne montre aucune action nouvelle sur `buildsite_0009`.
4.  `/buildings/library` → ouvrir un bâtiment → **Placer sur un emplacement** : la page Emplacements
    s'ouvre avec le bandeau « Poser « … » » et le nombre d'emplacements libres ; sur la fiche d'un
    emplacement libre, le bâtiment est **déjà choisi**. Ne pas confirmer la pose.
5.  `/buildings/sites?place=inexistant` : aucun bandeau, aucune présélection.
6.  Sur téléphone : fiche lisible, bouton « Placer sur un emplacement » accessible.

### TC-288 — Aperçu du bâtiment en 3D dans l'Atelier (issue #273, PENDING MANUAL VALIDATION)

**Prérequis** : **JAR et panel** issus de #273. **Durée** : 5 minutes. Gratuit ; rien n'est posé.

1.  Ouvrir le projet « Bibliothèque du Hub » (TC-286), une version qui a un plan → **Vérifier le
    bâtiment** (ou **Afficher l'aperçu**), puis **Rafraîchir**.
2.  La carte **« Aperçu du bâtiment »** montre le bâtiment en 3D. Comparer avec le plan : murs, fenêtres
    (translucides), porte, toit, étages. Sous l'image : « Vu depuis … » et le côté de l'entrée.
3.  **Tourner** : choisir chaque vue, « Afficher » — on fait le tour du bâtiment, sans effet miroir.
4.  **Voir l'intérieur** : « Couper au-dessus du niveau 2 », puis 5… — les étages et escaliers
    apparaissent. Vérifier `/actions` : aucune action nouvelle en tournant ou en coupant.
5.  Légende des matériaux et phrase sur les limites présentes. Sur téléphone : l'image tient dans
    l'écran, les sélecteurs s'empilent.
6.  (Si l'ancien plugin est encore en place) l'écran dit qu'il faut une version plus récente du plugin,
    sans image cassée.

### TC-289 — L'ancre décrite pour chaque bâtiment de la bibliothèque (issue #269, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de #269. **Durée** : 2 minutes. Lecture seule.

1.  `/buildings/library` → **Rafraîchir** → ouvrir la **hutte de test** : « Ancre » dit « Le repère de
    l'emplacement tombe au milieu de la façade d'entrée, 1 bloc au-dessus du bas du bâtiment… »,
    suivi de `3 / 1 / 0`.
2.  Ouvrir un bâtiment produit par l'IA (par exemple la Bibliothèque du Hub V1) : « … tombe au centre du
    bâtiment, au niveau du bas du bâtiment. »
3.  Nulle part la phrase « centre de la porte, au niveau du sol » n'est affichée pour tous.


### TC-290 — Réparation par le moteur et correction IA sans commentaire (issue #282, PENDING MANUAL VALIDATION)

**Prérequis** : **JAR et panel** issus de #282. **Durée** : 5 minutes. **Gratuit** jusqu'à l'étape 5
(1 appel IA facturé à l'étape 5).

1.  Atelier → nouveau projet de test (jamais un projet existant), gabarit 11 × 8. Dans « Reprendre
    un plan », reprendre l'action d'audit `077e207d` (Maison du bibliothécaire, toit de y=5 à y=8) —
    aucun appel IA —, puis, dans les détails techniques, **vérifier à nouveau** cette version.
2.  Sans autre clic, après **Rafraîchir** : une version « Réparé par le moteur » apparaît, la page
    s'ouvre dessus et dit « Instruction 18 (gable) : zone … ramenée … ». Sa vérification et son aperçu
    sont partis seuls ; le verdict est **conforme**. `/actions` : aucun appel IA.
3.  Recommencer avec le 3<sup>e</sup> plan de la Bibliothèque du Hub (audit `b88b887b`) : la version
    réparée ajoute un escalier de 6 marches, retire la volée et l'échelle mortes, range les
    bibliothèques le long des murs. Aperçu 3D, coupe au niveau 6 : la trémie et l'escalier sont visibles.
4.  Sur une version encore refusée : **Améliorer avec l'IA** montre « Ce que l'IA recevra
    automatiquement » (le diagnostic du serveur) et un champ « Vos consignes en plus (facultatif) ».
5.  Laisser le champ **vide** et envoyer : une nouvelle version arrive, déjà en vérification.

### TC-291 — Conception automatique de bout en bout avec OpenAI (issue #282, PENDING MANUAL VALIDATION — appels facturés)

**Prérequis** : JAR et panel issus de #282 sur DEV, **feu vert du propriétaire et enveloppe acceptée**.
**Durée** : 5 à 10 minutes par essai. **Coût** : selon l'enveloppe affichée (Soigné ≈ 8 appels).

1.  Nouveau projet « Bibliothèque essai #282 » (identifiant technique neuf), 19 × 14, façade ouest, la
    description française de la Bibliothèque du Hub. Ne réutiliser aucun projet existant.
2.  Carte **Conception automatique** → niveau **Soigné** → lire l'enveloppe → cocher l'acceptation →
    **Lancer**. La page se recharge seule et liste les passes (conception, vérification, réparation,
    correction, critique) avec leurs jetons.
3.  Critère de réussite : session « Terminée : plan conforme » ; dans l'aperçu 3D, **deux étages**,
    un escalier visible dans une trémie, l'étage praticable (allées), façades travaillées, toit,
    fenêtres aux deux étages, éclairage aux deux étages — **sans aucune édition du plan**.
4.  Noter : nombre d'appels, jetons, coût réel indiqué par le tableau de bord OpenAI, durée, et une
    appréciation esthétique (capture). Ne pas enregistrer dans la bibliothèque sans décision du
    propriétaire.

### TC-292 — Images de référence et nouvelle conception (issue #283, PENDING MANUAL VALIDATION — 1 appel facturé)

**Prérequis** : panel issu de #283. **Durée** : 5 minutes.

1.  Projet de test → « Images de référence » → ajouter une photo de bibliothèque médiévale (JPEG,
    < 4 Mio) : la vignette apparaît, le nom et la taille sont affichés.
2.  Renommer un fichier texte en `.png` et l'envoyer : refus « n'est pas une image », rien n'est ajouté.
3.  Ouvrir l'adresse de la vignette dans un autre navigateur non connecté : renvoi vers la connexion.
4.  « Recommencer depuis ma description » sans cocher : refus gratuit. Cocher, envoyer : une nouvelle
    version arrive (sans parent dans les détails techniques), les anciennes restent dans la liste.
5.  Comparer l'aperçu 3D avec la photo : le style (toit, matériaux, proportions) doit s'en inspirer,
    sans être une copie. « Retirer » l'image : elle disparaît de la liste.

### TC-293 — Retirer un bâtiment de la bibliothèque (issue #284, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel issus de #284. **Durée** : 5 minutes. **Ne retirer aucun bâtiment réel** :
créer d'abord un bâtiment d'essai (Atelier → « Enregistrer dans la bibliothèque » d'un projet de test).

1.  `/buildings/library` → Rafraîchir → ouvrir la **hutte de test** : « Exemple fourni avec le plugin »,
    aucun bouton de retrait.
2.  Poser le bâtiment d'essai sur un emplacement libre, puis ouvrir sa fiche dans la bibliothèque :
    « Posé sur buildsite_… », aucun bouton. Forcer l'envoi (ancien onglet) : refus avant la file.
3.  Retirer le bâtiment de l'emplacement (terrain restauré), Rafraîchir : le bouton apparaît, avec la
    version et le fichier `.schem`.
4.  Sans cocher : refus. Cocher, envoyer : succès ; Rafraîchir : le bâtiment a disparu.
5.  Sur le serveur : `buildings/.retired/<id>_<horodatage>/` contient le `.yml`, le `.schem` et
    `retired.yml` (auteur). Redémarrer : le bâtiment ne revient pas. L'historique de l'emplacement le
    mentionne toujours.

### TC-294 — Lit, lanternes et stabilité après la pose (issue #288, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel issus de #288–#292. **Durée** : 15 minutes. Projet d'essai uniquement,
emplacement libre, aucun bâtiment existant remplacé. **Gratuit** si le plan est saisi à la main.

1.  Atelier → nouveau projet « Essai stabilité » ; dans « Détails techniques », coller un plan qui
    contient `op: bed` (`from` le pied, `direction: east`, `block: minecraft:pink_bed`), une
    `op: light` sous le plafond **écrite avant le toit**, et une lanterne `light` en plein air à
    2 blocs sous le plafond. Vérifier : le serveur répare (chaîne `minecraft:iron_chain`), la version
    « Réparé par le moteur » liste « Lanternes flottantes suspendues à une chaîne ».
2.  Écrire volontairement `op: box` avec `block: minecraft:white_bed` : refus « un lit occupe DEUX
    cellules … op: bed », rien n'est écrit.
3.  Enregistrer la version conforme, la poser **avec une rotation de 90°** puis, sur un autre
    emplacement, **180°**.
4.  En jeu, sur chaque pose : le lit est rose, entier, la tête du côté annoncé ; s'y coucher la nuit
    fonctionne. Les lanternes sont suspendues (ou posées), aucune ne tombe.
5.  Provoquer des mises à jour physiques : casser puis replacer un bloc voisin de chaque lanterne et
    du lit, poser de l'eau à côté, attendre 2 minutes : rien ne se détache ni ne se ramasse.
6.  Retirer les bâtiments d'essai (terrain restauré).

### TC-295 — Maison de Lily : façades, entrée praticable et enregistrement (issues #288–#292, PENDING MANUAL VALIDATION — appels facturés)

**Prérequis** : JAR et panel issus de #288–#292. **Durée** : 20 minutes. Projet **Maison de Lily**
existant (lecture), ou une copie. **Coût** : celui de la conception automatique choisie (enveloppe
affichée avant consentement).

1.  Ouvrir le projet : la description (5 793 caractères) est entière ; le compteur dit le même nombre.
2.  Sur la version `aa8ff980` : « Vérifier » rend `PLAN_INVALID` avec les **deux** blocs inexistants
    (`minecraft:bed`, `minecraft:stained_glass_pane`), leurs instructions et les identifiants réels ;
    aucun « conforme ».
3.  « Améliorer avec l'IA » sans commentaire : la nouvelle version emploie des blocs colorés réels ;
    la porte et les fenêtres sont **dans les murs** de la maison (aperçu 3D), plus dans le jardin.
4.  Si le seuil dépasse le terrain : la version réparée ajoute une marche devant la porte (ou enterre
    la fondation) ; l'aperçu le montre.
5.  Enregistrer : succès. Si un échec survient, il s'affiche **dans la carte** avec son motif complet
    et l'action proposée (corriger / réessayer), pas seulement dans l'historique.
6.  Poser sur un emplacement d'essai, entrer **sans sauter** depuis le terrain, ouvrir la porte, se
    tenir dans la pièce ; les fenêtres donnent sur l'intérieur.

### TC-296 — Reprendre, retirer, réenregistrer, republier (issue #290, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel issus de #288–#292. **Durée** : 15 minutes. Bâtiment d'essai uniquement.

1.  Projet d'essai enregistré : la carte dit « Enregistré dans la bibliothèque — version 1 » et
    propose « Reprendre la conception ». Reprendre : nouvelle version « Reprise de la version
    enregistrée », vérification lancée d'elle-même, aucun appel IA, Améliorer et Conception
    automatique disponibles.
2.  Modifier la version (texte du plan), vérifier, puis « Publier une nouvelle version » : sans cocher,
    refus ; en cochant, succès, version 2. Sur le serveur, `buildings/.history/<id>/v1_<horodatage>/`
    contient l'ancienne définition et l'ancien schematic. Un exemplaire déjà posé n'a pas changé.
3.  Deux onglets sur la même version : publier dans l'un, puis dans l'autre → `VERSION_CONFLICT`,
    motif affiché, rien d'écrit.
4.  Bibliothèque → le bâtiment nomme son projet **et** son identifiant ; « Reprendre la conception »
    ouvre l'Atelier sur une nouvelle version.
5.  Retirer le bâtiment (non posé) de la bibliothèque. L'Atelier affiche « Retiré de la
    bibliothèque », **sans** « Choisir un emplacement » ; la liste des projets aussi. Redémarrer le
    panel : même état.
6.  « Réenregistrer dans la bibliothèque » : succès, le bâtiment revient (Rafraîchir).

### TC-297 — Conception détaillée (issue #289, PENDING MANUAL VALIDATION — appels facturés, validation esthétique par le propriétaire)

**Prérequis** : panel issu de #288–#292. **Durée** : 20 minutes. **Coût** : 1 appel pour la conception,
plus la conception automatique choisie.

1.  Projet avec une description riche (Maison de Lily) → « Conception détaillée » → « Rédiger la
    conception avec l'IA » : un appel ; le texte suit les huit titres, cite chaque exigence du brief
    dans « Correspondance avec le brief », et dit ce qui ne peut pas être posé (tableaux…).
2.  Modifier la conception à la main, enregistrer : version 2 ; dans un second onglet resté sur la
    version 1, enregistrer : refus, le texte saisi est réaffiché.
3.  « Créer un plan avec l'IA » : le plan reflète la conception (pièces, lit, four, tapis, fleurs,
    vitraux, porche) ; la légende de l'aperçu liste tous les matériaux avec leur nombre.
4.  Conception automatique « Soigné » sur un projet sans conception : le journal commence par
    « CONCEPTION DÉTAILLÉE », la critique note « OMISSIONS ET ÉCARTS ».
5.  Jugement esthétique et fidélité au brief : par le propriétaire, en jeu — l'audit ne les mesure pas.

### TC-298 — Histoire complète en JSON : « Tom reprend la route » (issue #211, PENDING MANUAL VALIDATION)

**Prérequis** : panel et JAR issus de la nuit du 12→13/10/2026 ; Tom n'existe pas encore (ni PNJ
`tom`, ni quêtes `rpgquest:tom_*`). **Durée** : 25 minutes. **Gratuit** (aucun appel IA).

1.  `/content/import` → « Fichier du pack » : déposer `docs/content-packs/tom_reprend_la_route.json`
    → **Analyser**. **Attendu** : 3 quêtes, 1 story, 1 dialogue « à créer », PNJ `tom` « créé sur le
    serveur à la confirmation », aucune erreur ni collision.
2.  **Confirmer**. **Attendu** : 5 fichiers écrits dans la source, bandeau « PNJ logique « tom » :
    création envoyée au serveur ». Rafraîchir `/npcs` : Tom existe, sans PNJ Citizens.
3.  Réimporter le même fichier. **Attendu** : tout « inchangé », Tom « existe déjà », rien à écrire.
4.  Ouvrir `rpgquest:tom_bache` dans l'éditeur de quêtes : changer la quantité de cuir (6 → 4),
    enregistrer, relire ; dans l'éditeur de dialogues, modifier la première réplique de Tom.
5.  Publier les cinq contenus (`/content`, publication habituelle).
6.  `/npcs` → Tom → lier un PNJ Citizens existant (ou en créer un), sans réimporter l'histoire.
7.  En jeu, compte de test : parler à Tom → « Je peux t'aider ? » → **Commencer la quête : Du bois
    pour le chariot**. Apporter 10 bûches → « J'apporte des matériaux » → **Donner** : 10/16 restent
    acquis ; revenir avec 6 → quête terminée une seule fois (40 XP).
8.  Revenir : « Et maintenant, la bâche ? » → accepter ; remettre le cuir ; puis « Il te manque des
    marchandises ? » → accepter, remettre 16 blés et 8 pommes → 80 XP + 3 émeraudes une seule fois.
9.  « Tu repars bientôt ? » → conclusion. Vérifier que `/quests` ne propose plus rien et qu'aucun
    double gain n'apparaît après reconnexion.
10. Refus : « Pas maintenant » ne démarre rien. Collision : modifier un fichier `tom_*` dans la
    source puis réimporter → collision à trancher, rien d'écrasé sans décision.

### TC-299 — Capturer une construction posée comme nouveau bâtiment (issue #246, PENDING MANUAL VALIDATION)

**Prérequis** : panel et JAR de la nuit du 12→13/10/2026, WorldEdit présent. Un emplacement de test
**isolé** (pas la Maison de Lily pour ce test). **Durée** : 20 minutes. **Gratuit** (aucun appel IA).

1.  Poser `test_hut_01` sur un emplacement de test. En jeu, ajouter dans la hutte un tapis, un pot de
    fleurs, une enseigne écrite, un coffre contenant un objet, un cadre et un tableau.
2.  Joueur à proximité (chunks chargés). `/buildings/sites` → fiche de l'emplacement → section
    **« Capturer la construction actuelle »**. Saisir l'identifiant `hutte_deco_test`, un nom, une
    catégorie → **Calculer l'aperçu de la capture**. **Attendu** : bornes = emprise posée,
    dimensions, volume, ancre locale, façade ; « 1 conteneur(s) capturé(s) VIDE(S), dont 1… » ;
    « 2 entité(s) NON capturée(s) — 1 × item_frame, 1 × painting ». Aucun bouton de capture tant que
    l'aperçu n'est pas calculé.
3.  Cocher la confirmation → **Capturer la construction actuelle**. **Attendu** : message « Construction
    capturée… » ; aucun bloc modifié en jeu ; le coffre en jeu garde son objet.
4.  `/buildings` (Bibliothèque) : `hutte_deco_test` apparaît, version 1 ; `test_hut_01` inchangé ;
    la fiche de l'emplacement source montre toujours `test_hut_01` et le journal porte « Capture vers
    la bibliothèque ».
5.  Créer un **autre** emplacement de test, **Poser un bâtiment** → `hutte_deco_test` → aperçu →
    poser. **Attendu** : tapis, pot, enseigne (texte compris) et coffre (vide) présents, même
    disposition par rapport à la porte ; cadre et tableau absents (annoncés à l'étape 2).
6.  Refus : relancer la capture avec le même identifiant → « déjà pris » ; marges +16 vers un
    bâtiment voisin → refus nommant l'autre emplacement ; double clic sur « Capturer » → une seule
    capture.
7.  Cas réel du propriétaire : Maison de Lily retouchée → capturer sous un nouvel identifiant, la
    maison posée restant intacte.

### TC-300 — Jo ne remet qu'un Acte de propriété, jamais au sol (issues #72 / #85, PENDING MANUAL VALIDATION)

**Prérequis** : JAR de la nuit du 12→13/10/2026 **et** `dialogues/jo.yml` du serveur mis à jour (un
JAR ne remplace jamais un dialogue existant). Compte de test avec `CLAIM_TIER_1 = true` et **aucun**
claim. **Durée** : 10 minutes.

1.  Inventaire avec de la place, sans acte → parler à Jo → **Réclamer mon acte**. **Attendu** : un
    seul acte reçu, nœud « Tiens, voici ton acte de propriété ».
2.  Reparler aussitôt à Jo → **Réclamer mon acte**. **Attendu** : « Tu as déjà ton acte de propriété
    dans ton inventaire » ; aucun second acte.
3.  Jeter l'acte, remplir les 36 cases de l'inventaire → **Réclamer mon acte**. **Attendu** : « Ton
    inventaire est plein… » ; rien au sol, aucun acte.
4.  Libérer une case → **Réclamer mon acte**. **Attendu** : acte reçu normalement.
5.  Sans acte, double-cliquer très vite sur **Réclamer mon acte**. **Attendu** : un seul acte.
6.  Après la pose du premier claim : « Revoir mes limites » n'apparaît que sans acte en poche et avec
    une place libre.


### TC-301 — Villes : définir, administrer, découvrir, objectif DISCOVER_CITY (issue #178, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel du lot #178 (migration V33 appliquée). Un compte administrateur avec
`rpgquest.admin.city` (ou l'ombrelle `rpgquest.admin.world`) et un **compte de test** qui n'a
encore découvert aucune ville. **Ne pas** placer la ville sur une construction existante à
préserver : le centre ne modifie aucun bloc, mais choisir un endroit neutre évite toute confusion.
**Durée** : 20 minutes.

1.  `/rpgadmin ville outil` → pelle en or « Outil de ville ». Clic droit sur un bloc → enclume →
    taper « Ville Test » → cliquer le résultat. **Attendu** : « Ville créée », identifiant
    `ville_ville_test`, centre, « s'étend de 50 blocs… (101 × 101 blocs) ». Fermer l'enclume au lieu
    de valider → « aucune ville n'a été créée ».
2.  PlugAdmin › **Villes** → **Rafraîchir les villes**. **Attendu** : la ville est listée, active,
    50 blocs, limites X/Z = centre ± 50, aperçu du carré, 0 découverte.
3.  **Montrer le périmètre en jeu** (votre pseudo). **Attendu** : contour orange et colonnes aux
    coins pendant ~15 s, carré et non cercle.
4.  Compte de test, hors de la ville : marcher vers le centre. **Attendu** : à la frontière (50
    blocs), titre plein écran « Bienvenue à Ville Test » une seule fois. Sortir, revenir : aucun titre.
    Se déconnecter dans la ville, se reconnecter : aucun titre.
5.  Second compte (ou après suppression de la découverte par une nouvelle ville) : se
    **téléporter** dans la ville → titre ; et se **connecter** déjà dans une autre ville jamais
    découverte → titre peu après l'arrivée.
6.  Panel : **Renommer** en « Port Test ». **Attendu** : identifiant inchangé, découvertes
    inchangées (compteur relu), aucun nouveau titre pour le compte de test. **Appliquer la taille**
    20 : limites relues ; un point à 30 blocs du centre n'est plus dans la ville.
7.  **Désactiver la ville** → un compte neuf qui y entre ne voit rien ; **Activer** alors qu'il est
    dedans → titre sans bouger.
8.  **Préparer le déplacement du centre** (votre pseudo) → message en jeu → clic droit avec l'outil
    ailleurs → message « Centre de la ville déplacé » ; **Rafraîchir** : nouveau centre et limites.
9.  Deux villes qui se chevauchent : se téléporter dans la zone commune → **un seul** titre (ville au
    centre le plus proche) ; avancer vers l'autre centre → son titre.
10. Éditeur de quête → objectif **Découvrir des villes** : (a) « Ville précise » cherchée par son nom ;
    (b) « Nombre de villes distinctes » = 2. Enregistrer, rouvrir (valeurs relues, résumé avec le
    nom de la ville), publier sur DEV sans redémarrer. Compte de test : accepter les deux quêtes,
    découvrir la ville précise → les deux progressent, l'action bar nomme chaque quête.
11. Quête `INCLUDE_EXISTING` avec une ville déjà découverte : acceptée → objectif rempli aussitôt.
    Même quête en `NEW_ONLY` → 0/1, et le journal énonce la règle.
12. **Supprimer** une ville de test (case cochée) : consequences listées avant ; la ville disparaît,
    ses découvertes aussi.

### TC-302 — Mode bypass administrateur explicite : OP/DEOP × bypass ON/OFF × accès Claims (issues #35 / #321, PENDING MANUAL VALIDATION)

**Prérequis** : JAR du lot #35. Le **compte OP du propriétaire reste OP** pendant tout le test (ne
jamais le dé-OP pour « simuler » un joueur). Un **compte de test** non OP, qu'on peut OP/DEOP depuis
PlugAdmin > Joueurs, sans claim et avec `CLAIM_TIER_1` absente (reset « nouveau joueur » si besoin).
**Rappel** : un test « comme un joueur » se fait **mode bypass OFF**, même en OP — la barre d'action
rouge « ⚠ MODE ADMIN / BYPASS ACTIF » ne doit pas être affichée. **Durée** : 20 minutes.

1.  Compte de test **OP**, `/rpgadmin bypass status` → « inactif ». Portail Hub → Claims.
    **Attendu** : refus « réservé aux joueurs… », aucune téléportation. Console :
    `[claims-access] … REFUSÉE`. `/rpgadmin` reste utilisable (ex. `/rpgadmin travel diagnose`).
2.  Même compte, `/rpgadmin bypass on`. **Attendu** : message rouge « Mode bypass administrateur
    ACTIVÉ », barre d'action permanente (la vérifier ~10 s), console `[admin-bypass] … ACTIVÉ`.
    Portail → **entrée autorisée**, Pierre de retour reçue ; console `[claims-access] … mode bypass`.
3.  `/rpgadmin bypass off` → message vert, barre d'action disparue. Revenir au Hub, reprendre le
    portail → **refusé** de nouveau.
4.  `/rpgadmin bypass on`, se déconnecter, se reconnecter → **aucune** barre d'action ;
    `status` → « inactif » ; portail → refusé.
5.  `/rpgadmin bypass on` puis redémarrage DEV → après reconnexion, `status` → « inactif ».
6.  Compte de test **DEOP** (PlugAdmin > Joueurs) : `/rpgadmin bypass on` → refusé (permission
    manquante ou `/rpgadmin` inaccessible) ; portail → refusé.
7.  Variable `CLAIM_TIER_1` (PlugAdmin, outil variables) : `false` → refusé ; `true` → entrée
    autorisée (OP comme DEOP, bypass OFF) ; une valeur invalide est refusée par l'outil (#319).
8.  Avec `CLAIM_TIER_1 = true`, poser le premier claim avec l'Acte, revenir au Hub, repasser le
    portail : **entrée immédiate** (déjà propriétaire), OP ou non, bypass OFF.
9.  PlugAdmin > Paramètres généraux > **Administration** : durée maximale **1** minute, enregistrer
    (valeurs relues). `/rpgadmin bypass on` → « encore 1 min au plus » ; après ~1 min, message
    « désactivé (durée maximale atteinte) » et barre d'action disparue. Remettre 0.
10. Bypass ON, puis dans PlugAdmin « **Désactivé pour tout le monde** » → en jeu, message
    « désactivé (mode retiré dans PlugAdmin…) » sous ~2 s ; `/rpgadmin bypass on` → refusé.
    Remettre « Proposé ».
11. Bypass OFF, compte OP : frapper une vache au Hub → **annulé** ; casser un bloc au Hub →
    **autorisé** (droit de construction, inchangé). Bypass ON : la vache peut être frappée.

**Couverture automatique** : `AdminBypassModeTest` (OFF par défaut, ON/OFF, DEOP, nœuds armés
seulement, retrait PlugAdmin, durée maximale, permission perdue, indicateur, déconnexion, arrêt),
`ClaimWorldAccessGuardTest` (OP sans bypass refusé, OP bypass ON puis OFF, DEOP, `CLAIM_TIER_1`
`false`/`TRUE`/`oui`/`1` refusés, OP éligible accepté, nœud sans session, session sans nœud,
retrait PlugAdmin), listeners claims/zones/Hub (OP sans session bloqué), `GameSettingsTest`,
`SettingsPageTest` (rubrique Administration).

### TC-303 — PNJ récurrents par instance de biome, étape 1 : apparition, unicité, réparation (issue #13, PENDING MANUAL VALIDATION)

**Prérequis** : JAR de l'étape 1 de #13 ; Citizens actif ; dialogues `encounter_bandit`,
`encounter_merchant`, `encounter_smith` publiés sur DEV ; `plugins/RPGQuest/encounters/` contient
les trois modèles livrés (copiés au premier démarrage). Compte OP du propriétaire, mode bypass OFF.
**Durée** : 25 minutes. Garder la console ouverte (`[PNJ récurrents]`).

1.  Dans le Wild, marcher jusqu'à entrer dans une **forêt** non encore visitée. **Attendu** : sous
    quelques secondes, console `[PNJ récurrents] CREATED … (forest_bandit)` puis `SPAWNED …` ; un PNJ
    « Brigand des bois » se tient à 24–64 blocs du point d'entrée, sur un sol praticable (pas dans
    un arbre, pas dans l'eau), et regarde le joueur qui s'approche.
2.  Clic droit sur le Brigand → dialogue d'accueil (« Qui es-tu ? » / « Au revoir »). Aucune quête
    ajoutée au journal.
3.  Ressortir de la forêt puis y revenir, se déconnecter/reconnecter : **toujours un seul** Brigand,
    au même endroit.
4.  Redémarrer DEV, revenir : même PNJ, même position, aucun second PNJ (vérifier `/npc list` :
    une seule entrée « Brigand des bois » pour cette forêt).
5.  Aller dans une **seconde forêt éloignée** (> 300 blocs) : un **autre** Brigand y apparaît.
6.  Désert (Marchand égaré) et montagne/collines (Forgeron perdu) : même comportement.
7.  Plaine, océan, Hub, monde des claims : **aucune** rencontre.
8.  Sélectionner un Brigand et le supprimer avec Citizens (`/npc remove`) puis sortir/rentrer dans
    l'instance (ou recharger la zone) : **un seul** Brigand réapparaît au même endroit, console
    `REPAIRED`.
9.  Performances : se déplacer en elytra/au galop sur plusieurs biomes, aucun lag notable ; aucune
    génération de rencontre au démarrage du serveur.

**Couverture automatisée** : `EncounterTemplateParserTest` (format, défauts, erreurs, modèles
livrés, doublons), `EncounterTemplateSelectorTest` (biome, monde, désactivation, plafond, tirage
pondéré déterministe), `EncounterWorldPolicyTest` (Wild par défaut, Hub/claims toujours exclus,
id d'occurrence), `EncounterSiteRepositoryTest` (une rencontre par instance, survie au redémarrage),
`ConfigValidatorTest` (section `encounters`, bornes), `BundledDialoguesValidityTest`.

### TC-304 — PNJ récurrents, étape 2 : page PlugAdmin et actions d'administration (issue #13, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel de l'étape 2 de #13 déployés ; au moins une rencontre apparue (TC-303
étapes 1 et 5). Compte propriétaire PlugAdmin. **Durée** : 15 minutes.

1.  PlugAdmin → PNJ : le bouton « PNJ récurrents » est présent ; après « Actualiser les PNJ », aucun
    Brigand / Marchand / Forgeron n'apparaît parmi les PNJ « à rattacher ».
2.  « PNJ récurrents » → « Actualiser » : vue d'ensemble (apparitions activées, monde `wild`),
    trois modèles avec leur nombre d'occurrences, occurrences du Wild avec position, PNJ
    « présent », état « active ».
3.  En jeu, `/tp @s x y z` copié depuis la page : le PNJ est là.
4.  « Gérer » → « Désactiver » sans cocher la case : refus. Avec la case : le PNJ disparaît en jeu ;
    après actualisation l'occurrence est « désactivée », journal `DISABLED`. Revenir dans la zone :
    aucun PNJ ne réapparaît.
5.  « Réactiver » (confirmé) : le PNJ réapparaît au même endroit si la zone est chargée (sinon à la
    prochaine visite), une seule fois (`/npc list`).
6.  « Retirer » (confirmé) sur une occurrence : PNJ et ligne disparaissent ; en ressortant puis
    revenant dans la zone, une **nouvelle** rencontre peut apparaître.
7.  Modifier `weight` d'un modèle sur le serveur puis « Relire les modèles » : nouvelle valeur
    affichée, aucun PNJ existant touché. Casser un modèle (supprimer `biomes`) puis relire : le
    fichier apparaît en « refusé » avec son motif ; le restaurer et relire.
8.  Compte sans `NPC_SPAWN_WRITE` : la page s'affiche en lecture seule.

### TC-305 — PNJ récurrents, étape 3 : édition des modèles et apparence (issue #13, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel de l'étape 3 déployés. Compte propriétaire PlugAdmin. Un marais
(`swamp`) non visité dans le Wild. **Durée** : 20 minutes.

1.  `/npcs/encounters` → « Actualiser » → **Nouveau modèle** : `swamp_alchemist`, « Alchimiste des
    marais », biomes `swamp` et `mangrove_swamp` (une ligne chacun), actif → créé ; avertissement
    « aucun dialogue rpgquest:swamp_alchemist ». Après actualisation, le modèle est listé.
    Sur le serveur, `plugins/RPGQuest/encounters/swamp_alchemist.yml` existe.
2.  Recréer le même identifiant : refus « existe déjà », le fichier n'a pas changé.
3.  Biome `pas_un_biome!` ou poids `0` : refus clair, rien d'écrit.
4.  En jeu, entrer dans un marais jamais visité : un « Alchimiste des marais » apparaît avec
    l'apparence par défaut (pas le skin d'un joueur inconnu) ; la page indique « apparence par
    défaut ».
5.  **Modifier** : coller un lien MineSkin valide dans « Paramètres avancés » → enregistré. Sur
    l'occurrence du marais : « Gérer » → « Retenter maintenant » (confirmé) → le PNJ prend le
    nouveau skin ; la page indique « apparence du modèle ». Un lien `https://minesk.in/inexistant0`
    puis « Retenter » : journal `SKIN_FAILED`, l'apparence précédente reste.
6.  **Dupliquer** `forest_bandit` : formulaire pré-rempli, identifiant vide ; saisir
    `forest_bandit_2`, enregistrer → deux modèles forêt.
7.  **Désactiver le modèle** `swamp_alchemist` : badge « désactivé » ; un nouveau marais ne génère
    plus rien ; l'alchimiste existant reste en place. Réactiver.
8.  **Supprimer** `swamp_alchemist` (une occurrence) sans cocher « Laisser ces occurrences » : refus.
    En cochant les deux cases : supprimé, sauvegarde dans `encounter-backups/`, l'alchimiste reste
    en jeu. Le retirer depuis « Occurrences » si souhaité.
9.  Compte sans `NPC_WRITE` : aucun formulaire de modèle ; sans `NPC_DELETE` : pas de « Supprimer ».

### TC-306 — PNJ récurrents, étape 4 : quêtes secondaires renouvelables (issue #13, PENDING MANUAL VALIDATION)

**Prérequis** : JAR, dialogues `encounter_*.yml` et panel de l'étape 4 déployés. Un Brigand des bois,
un Marchand égaré et un Forgeron perdu trouvés dans le Wild (`/npcs/encounters` donne leurs
positions). Un compte joueur sans mode admin. **Durée** : 30 minutes.

1.  Clic droit sur un Brigand des bois → « Du travail ? » → l'offre nomme « Les toiles du
    sous-bois », son but, l'objectif (6 araignées puis revenir) et la récompense.
2.  « Plus tard » → retour à l'accueil, `/quest list` : rien de démarré. « Refuser » → fermeture,
    rien de démarré. L'offre est toujours là au clic suivant.
3.  « Commencer (j'accepte) » → titre « Quête commencée » ; l'accueil ne propose plus « Du travail ? » mais « Où
    en est ma tâche ? ».
4.  Tuer 6 araignées, cliquer sur **un autre** Brigand des bois (autre instance de forêt) → quête
    terminée, 25 XP + 16 flèches, une seule fois.
5.  Recliquer : « Encore du travail ? » → « repasse d'ici un jour », aucune offre. `/quest accept
    rpgquest:encounter_bandit_spiders` → « Rien de nouveau pour l'instant… reviens dans 19 h 59 min ».
6.  Marchand égaré : « Commencer (j'accepte) », remettre 5 cactus (« J'ai des objets » → « Donner mes objets »),
    se déconnecter/reconnecter, remettre les 7 restants → quête terminée, 20 XP + 6 pains. Double-clic
    rapide sur « Donner » : jamais plus de 12 cactus retirés.
7.  Forgeron perdu : même parcours avec 6 fer brut + 8 charbons → 30 XP + 2 lingots.
8.  Un second joueur peut accepter la même quête pendant que le premier est en recharge
    (progression par joueur).
9.  PlugAdmin `/quests` : puce « Secondaires / Rencontres » → seules les 3 quêtes `encounter_*`,
    badges « rencontre » et « recharge 20 h », sans alerte « donneur inconnu ». Éditer une quête :
    le champ « Délai avant de pouvoir la refaire » vaut `20h` ; `vingt heures` → erreur claire.
10. `/rpgadmin quest reset <joueur> rpgquest:encounter_bandit_spiders` → l'offre réapparaît tout de
    suite.

**Revalidation ciblée après le correctif du 20/10/2026 (échec constaté à l'étape 4)** — sans
réinitialiser la quête de LoDyMcFly (6/6 conservé) :

R1. Se connecter après le déploiement : le chat annonce l'étape suivante (« Parler à Brigand des
    bois ») ; le journal montre l'étape de retour et non plus « Spider 6/6 ».
R2. Clic droit sur un Brigand des bois (n'importe lequel) → la quête se termine **au clic** :
    « Quête terminée », 25 XP + 16 flèches. Le dialogue qui s'ouvre propose « Encore du travail ? »
    (plus « Où en est ma tâche ? »).
R3. Recliquer, double-cliquer, se reconnecter : aucune seconde récompense ; le journal affiche
    « À refaire dans … » (recharge 20 h).

Cause : quête reprise le 15/10 avant le correctif `f322fd9e` (17/10) — les compteurs de la première
exécution étaient restés en base et l'étape de chasse rechargée pleine ne se fermait jamais.


### TC-307 — PNJ récurrents, étape 5 : journal des quêtes de rencontre (issue #13, PENDING MANUAL VALIDATION)

**Prérequis** : JAR de l'étape 5 déployé, TC-306 jouable (au moins une quête `encounter_*` terminée
par un compte joueur sans mode admin). **Durée** : 10 minutes.

1.  Juste après avoir terminé « Les toiles du sous-bois », `/quests` → onglet « Quêtes terminées » :
    l'icône porte « Rencontre — quête secondaire » et « À refaire dans 19 h 59 min » (≈ 20 h).
2.  Clic gauche sur l'icône → vue détail : mêmes lignes + « Renouvelable : toutes les 20 h ».
3.  Une quête terminée **non** répétable (ex. tutoriel) : aucune ligne « refaire / refaite ».
4.  `/rpgadmin quest reset <joueur> rpgquest:encounter_bandit_spiders` puis la terminer à nouveau
    (ou attendre la fin du délai) : tant que la ligne n'est pas en recharge, « Peut être refaite
    auprès d'un PNJ de rencontre » s'affiche ; accepter à nouveau → la quête passe dans « Quêtes
    en cours » avec l'étiquette « Rencontre ».
5.  Ouvrir le journal d'un **second** joueur : aucune recharge héritée du premier.
6.  PlugAdmin → export d'un content pack contenant `encounter_bandit_spiders` : la quête porte
    `cooldown: 20h` ; réimporter ce pack → aucun diagnostic sur `cooldown`.


### TC-308 — Remise sécurisée commune : inventaire plein, jamais au sol, jamais de faux succès (issue #318, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #318 déployé ; un compte joueur **sans** mode admin, `CLAIM_TIER_1=true`, sans
Pierre de retour ; de quoi remplir les 36 cases (ex. stacks de terre). **Durée** : 15 minutes.

1.  Inventaire **plein** → Jo → « Obtenir une Pierre de retour » : le dialogue se ferme, message
    « Ton inventaire est plein. Libère 1 emplacement(s) puis réessaie — rien n'a été remis ni
    perdu. ». **Aucune** Pierre au sol, **aucun** « Vous avez reçu ».
2.  Libérer une case, reparler à Jo → la Pierre arrive dans l'inventaire, « Vous avez reçu 1x
    rpgquest:pierre_retour ». Le choix disparaît ensuite (une seule Pierre).
3.  Acte de Jo, inventaire plein puis place libérée : comportement #72/#85 inchangé (nœud
    « inventaire plein », puis remise d'un seul acte).
4.  Quête à récompense `ITEM` (ex. une quête de rencontre) terminée **inventaire plein** : message
    « Inventaire plein : … est terminée, mais ses récompenses ne tiennent pas… » ; journal
    `/quests` → « Prête à remettre » ; ni XP, ni pièces, ni objet. Jeter un objet (Q) → la quête se
    termine aussitôt, récompenses complètes **une seule fois** (XP, objet, monnaie).
5.  Même cas, quête « Prête à remettre » : se déconnecter / reconnecter (ou redémarrage DEV) →
    toujours « Prête à remettre », rien perdu ; faire de la place → la quête se termine.
6.  Marchand, inventaire plein : achat refusé **sans débit** (solde inchangé). Une case libre :
    achat normal.
7.  PlugAdmin → joueur → « Donner un objet » à un joueur inventaire plein : résultat
    `INVENTORY_FULL` « Rien donné… », rien au sol ; idem « Compléter la quête » → `INVENTORY_FULL`.
8.  `/customitem give <joueur> rpgquest:refined_crystal 16` avec une pile partielle de 15 cristaux et
    aucune case vide → refusé (16 ne tiennent pas) ; avec `1` → empilé sur la pile existante.
9.  Non-régression : kit de départ (Rune) à la première connexion, Pierre de retour reçue à
    l'arrivée dans le monde des claims (au sol **uniquement** si inventaire plein, message « tombée
    à tes pieds » — exception volontaire #22), butin des monstres toujours au sol.

### TC-309 — Casse refusée hors claim : ni XP ni progrès de quête (issue #91, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #91 déployé ; un compte joueur **sans** mode admin, avec un claim dans le monde
Claims ; idéalement une quête active à objectif `BREAK_BLOCK` (ex. pierre). **Durée** : 5 minutes.

1.  Monde Claims, **hors** de tout claim : tenter de casser de la pierre → bloc intact, **aucun**
    overlay « +XP », XP de minage inchangée (`/skills`), compteur de quête inchangé.
2.  Même essai dans le claim d'un autre joueur (non trusté) → même résultat.
3.  Hub (sans mode admin) : tenter de casser un bloc → intact, aucun « +XP ».
4.  Hors claim, tenter de poser un bloc → refusé, objectif `PLACE_BLOCK` inchangé.
5.  Non-régression, dans **son** claim : casser de la pierre naturelle → bloc cassé, « +XP »
    affiché, XP créditée, objectif `BREAK_BLOCK` +1 ; récolter une culture mûre → XP agriculture.
6.  Non-régression Wild : nœud de ressource récolté normalement (drop, bloc épuisé).

### TC-310 — Publication d'une quête citant un PNJ : rechargement cohérent (issue #371, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #371 déployé sur DEV ; quête `rpgquest:test_lucien_artisanat` déjà sur DEV
(badge « Publié, non chargé ») ; PNJ `lucien` défini. **Durée** : 10 minutes.

1.  PlugAdmin → Quêtes → « Les travaux de Lucien » → **Republier sur DEV** → résultat
    « Synchronisé », « Chargée par le moteur : oui ». Le fichier n'est **pas** réécrit (même SHA
    `3bb0eff…`, aucune sauvegarde créée).
2.  Si un encadré « référence(s) déjà cassée(s) ailleurs » apparaît : il est replié, non bloquant,
    et nomme les quêtes concernées.
3.  En jeu : parler à Lucien, accepter la quête, fabriquer 12 planches de chêne, cuire 3 pommes de
    terre, livrer les 3 pommes de terre cuites à Lucien → récompenses 25 XP + 50 LodyCoins.
4.  Référence absente (copie de test, jamais sur une quête de joueur) : une quête de test livrant
    à un PNJ inexistant → publication refusée, fiche « Références introuvables » avec le nom exact
    du PNJ, aucun « Synchronisé ».

### TC-311 — Récompense « Cœur supplémentaire » : gain, idempotence, persistance (issue #166, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #166 déployé sur DEV (schéma V36) ; quêtes `docs/manual-tests/rewards/test_heart_*.yml`
publiées ; un joueur de test à 10 cœurs, sans autre bonus de vie. **Durée** : 15 minutes.

1.  Accepter « [TEST] Cœur, quête répétable », casser 1 terre → message « +1 cœur(s) — vie
    maximale : 11 cœurs » ; le HUD montre **11 cœurs** (une rangée + 1), le 11e plein, la barre
    n'est **pas** remplie si le joueur était blessé (seuls 2 PV ajoutés).
2.  Reprendre la même quête et la refinir → « Tu as déjà reçu le cœur… », toujours 11 cœurs.
3.  « [TEST] Cœur de ville (A) » → 12 cœurs ; « [TEST] Cœur de ville (B) » → « déjà reçu », 12.
4.  Se déconnecter / reconnecter → 12 cœurs, ni 13 ni 10. Mourir et réapparaître → 12 cœurs
    (la vie remonte au nouveau maximum). Redémarrer DEV → 12 cœurs.
5.  Un second joueur : toujours 10 cœurs.
6.  Équiper un objet ou un effet qui augmente la vie maximum (ex. Absorption ne compte pas ;
    utiliser un objet à attribut `max_health`) → il s'ajoute aux 12 cœurs, rien n'est écrasé.
7.  Panel → éditeur de quête → ajouter « Cœur supplémentaire », clé vide → enregistré ;
    nombre 0 ou 513 → refusé ; clé « Central City » → refusée ; 6 → avertissement seulement.
8.  Plafond : `progression.max-bonus-hearts: 1` puis recharger la config, finir une quête cœur
    → message « plafond … atteint », vie inchangée ; repasser à 0 + reconnexion → cœur actif.

**Non vérifiable par les tests automatisés** : rendu réel du HUD, réapparition réelle du serveur
Paper (le modificateur est réappliqué après `PlayerPostRespawnEvent`), affichage au-delà de 2
rangées de cœurs.


### TC-312 — Modèle d'histoire et pack « Les souvenirs de Lily » (issue #211, PENDING MANUAL VALIDATION)

**Prérequis** : panel issu de la branche `feature/211-lily-story-template`. Sur DEV, `lily` et
`julius` existent déjà comme PNJ logiques liés à Citizens (#10 et #11) et n'ont pas de dialogue.
**Durée** : 40 minutes. **Gratuit** (aucun appel IA par PlugAdmin).

1.  Téléphone puis bureau : **Import contenu** → carte « Créer une histoire avec une IA externe ».
    **Attendu** : les 6 étapes, le bouton **Télécharger un modèle d'histoire** (archive `.zip`), les
    six liens unitaires, le prompt dans une zone de texte sélectionnable.
2.  Ouvrir l'archive : `LISEZ-MOI.txt`, deux fiches `.md` (« n'est PAS importable » en tête), deux
    `.json`, le prompt, le guide (qui liste tous les objectifs, récompenses, actions et conditions).
3.  (Facultatif, chaîne réelle) Donner fiche Lily + guide + exemple + prompt à une IA externe ;
    enregistrer sa réponse en `.json` et la déposer. Noter dans le rapport ce que l'IA a produit.
4.  **Analyser l'exemple Lily**. **Attendu** : 5 quêtes, 1 story, 2 dialogues « nouveau » ; `lily` et
    `julius` « existe déjà sur le serveur » (réutilisés, non modifiés) ; aperçu : story dans l'ordre
    repas → fleurs → recette → tarte → Julius, objectifs avec quantités (3 × BREAD…), récompenses.
    Aucune collision. Rien n'est écrit (vérifier `git status` du dépôt source : inchangé).
5.  **Enregistrer dans la source** (une seule confirmation). **Attendu** : 8 fichiers écrits ; aucun
    PNJ créé. Réimporter : tout « inchangé », pas de bouton d'enregistrement.
6.  Éditeurs habituels : changer la quantité de pain de `rpgquest:lily_repas` (3 → 2) et la première
    réplique de Lily ; enregistrer, relire. **Attendu** : conservé, story toujours liée.
7.  **Changements en attente** : publier **uniquement** les 8 fichiers Lily (pas ceux du
    propriétaire). **Attendu** : « Mis à jour sur DEV, rechargé et confirmé par le moteur ».
    ⚠️ À partir de là, cliquer sur Lily ouvre le dialogue `rpgquest:lily`.
8.  En jeu, compte de test : Lily → « Tu as l'air affamée… » → accepter ; remettre pain et pommes
    (dépôt partiel conservé) → 20 XP une seule fois. Enchaîner fleurs, recette (citrouille, sucre,
    œufs, blé), tarte (4 charbons, puis reparler à Lily → 40 XP + 2 tartes) ; « Ce Julius… » →
    accepter. Aucune quête terminée ou verrouillée n'est proposée ; « Pas maintenant » ne démarre rien.
9.  Aller voir Julius (autre ville) : « Lily m'envoie » → récit → quête terminée (60 XP, variable
    `LILY_JULIUS_RETROUVE`). Revenir à Lily : « J'ai parlé à Julius » → conclusion ouverte.
10. Reconnexion : aucune double récompense. Collision : modifier `lily_fleurs` dans la source puis
    réanalyser → conflit à trancher, rien d'écrasé.

**Non vérifiable par les tests automatisés** : rendu mobile réel, comportement du clic Citizens,
production d'un pack par une IA externe réelle.

### TC-313 — Villes : PNJ central et point d'arrivée distinct (issue #322, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel du lot #322 (migration V37). Une ville existante (TC-301), un PNJ
défini et lié à Citizens (ex. `guide`), un compte administrateur connecté en jeu.
**Durée** : 10 minutes.

1. PlugAdmin › Villes : **Rafraîchir les villes** puis **PNJ**. Ouvrir la ville : la section
   « PNJ central et arrivée » indique « Aucun » et « Centre de la ville (par défaut) ».
2. Taper `gui` dans « PNJ central » : la liste propose le Guide (nom et identifiant). Enregistrer.
   Après rafraîchissement : badge « PNJ … », position relue du PNJ, message « est dans la ville, à
   N bloc(s) du centre » (ou « a quitté la ville » s'il est au-delà de la distance).
3. S'éloigner du PNJ jusqu'à ce que son chunk soit déchargé, rafraîchir : « dernière position
   enregistrée ». Le centre et les limites de la ville n'ont pas changé.
4. En jeu, se placer à l'entrée de la ville, debout, regard vers la place. Panel : **Relever
   l'arrivée sur ce joueur**. Le résultat donne les coordonnées ; la fiche affiche « Arrivée du
   voyage » avec le regard.
5. Essayer depuis un autre monde, puis en nageant : refus lisibles (« monde de la ville », « liquide »).
6. Ouvrir le menu de voyage › Villages › la ville : arrivée au point relevé, avec l'orientation
   relevée (pas au centre).
7. **Revenir à l'arrivée au centre** → le voyage arrive de nouveau au centre.
8. Vider « PNJ central » et enregistrer : « Aucun ». Les découvertes de la ville sont inchangées.
9. Cas réels attendus par le ticket : ville centrale du Hub avec `guide`, ville de Julius avec
   `julius`, arrivées relevées sur place.


### TC-314 — Catalogues de quêtes secondaires des PNJ récurrents (issue #369, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel #369 déployés ; deux comptes joueurs sans mode admin ; au moins deux
occurrences du Brigand des bois (`forest_bandit`) dans des forêts différentes. **Durée** : 30 minutes.

1.  PlugAdmin → Quêtes → **Catalogues de quêtes** → « Actualiser » : `encounter_side_quests` est
    listé avec ses 3 quêtes, titre, « recharge 20 h », « Attribué à : aucun modèle ».
2.  **Nouveau catalogue** `bandit_contracts` : ajouter 5 quêtes en tapant un **titre** dans la
    liste (dont au moins 3 renouvelables), régler un poids, enregistrer → la page relue montre les
    5 quêtes dans l'ordre choisi. Changer le numéro d'ordre d'une ligne, cocher « retirer » sur une
    autre, enregistrer → ordre et retrait visibles. « Dupliquer » → copie pré-remplie.
3.  PNJ → PNJ récurrents → `forest_bandit` → Modifier → **Catalogues de quêtes** =
    `bandit_contracts` → l'enregistrement signale « le dialogue … n'a aucune action
    OFFER_CATALOG_QUEST ». Dialogues → `encounter_bandit` : ajouter à l'accueil un choix « Du
    travail ? » avec l'action **Proposer une quête du catalogue** (catalogue vide), publier.
    L'avertissement disparaît de la page Catalogues après « Actualiser ». Aucune occurrence n'est
    recréée.
4.  Joueur A → Brigand n°1 → « Du travail ? » : écran « J'aurais une tâche pour toi : … » avec
    Commencer / Plus tard / Refuser. Journal `/quests` : **rien** de nouveau. Fermer, rouvrir,
    se reconnecter, aller voir le Brigand n°2 → **même** quête proposée.
5.  « Refuser » → rien dans le journal. « Plus tard » → retour à l'accueil. « Commencer » → une
    seule quête dans le journal (double-clic rapide : toujours une seule).
6.  Reparler : « Termine d'abord … ». Joueur B, même Brigand : sa propre proposition, sa quête à lui.
7.  Joueur A termine la quête (récompense une seule fois) puis « Du travail ? » : une **autre**
    quête ; la quête terminée n'est plus proposée pendant sa recharge (redémarrage DEV compris).
8.  Désactiver toutes les quêtes restantes du catalogue (ou les terminer) → « Rien pour le moment.
    Repasse d'ici … ».
9.  Supprimer une quête référencée (ou la retirer de la source) : la page Catalogues la marque
    « quête absente » ; en jeu, aucune erreur ; une progression déjà acceptée n'est pas effacée.
10. Supprimer `bandit_contracts` depuis la page : refusé sans la case « Retirer aussi le catalogue
    de ces modèles » ; avec la case, `forest_bandit` n'a plus de catalogue, la sauvegarde est dans
    `plugins/RPGQuest/quest-catalog-backups/`.
11. Téléphone (ou fenêtre étroite) : la page Catalogues reste lisible sans défilement horizontal.

### TC-315 — Emplacements de PNJ : réserver en jeu, placer et déplacer depuis PlugAdmin (issue #381, PENDING MANUAL VALIDATION)

**Prérequis** : JAR et panel #381 déployés (schéma V38) ; un compte avec `rpgquest.admin.npc.spot`
(ou `rpgquest.admin.npc`) ; un **PNJ de test** existant (ne pas utiliser le Guide ni un PNJ de
quête important), par ex. créé depuis PlugAdmin → PNJ → Créer un PNJ (`test_spot`). **Durée** :
20 minutes.

0.  `/rpgadmin npc spot tool` → l'outil « Emplacements PNJ » (porte-armure) arrive dans l'inventaire.
1.  Outil en main, viser un bloc A (sol plat, deux blocs d'air au-dessus) et faire **clic droit** →
    « Emplacement PNJ enregistré : Emplacement PNJ n (npcspot_000n) », particules au-dessus du bloc,
    **aucune commande ni saisie**. Recliquer le même bloc (y compris double-clic rapide) → au plus
    « Un emplacement existe déjà ici », jamais un second emplacement.
2.  Clic droit sur un autre bloc B (si possible dans un autre monde autorisé aux PNJ) → second
    emplacement. Clic gauche avec l'outil, clic droit avec un autre objet (porte-armure ordinaire,
    hache WorldEdit, outil de construction) → rien n'est créé, le porte-armure ordinaire se pose.
    Clic droit sur un bloc avec un bloc juste au-dessus, dans l'eau ou dans une parcelle de joueur →
    « Aucun emplacement créé » avec la raison. Retaper `/rpgadmin npc spot tool` → l'outil est rangé.
3.  PlugAdmin → PNJ → **Emplacements** → « Actualiser » : A et B listés, état *libre*, nom
    provisoire ; les **renommer** « Test A » / « Test B ».
4.  Sur A : choisir `test_spot`, cocher la confirmation, « Placer / téléporter ici » → le PNJ est
    en A, tourné vers l'endroit d'où l'administrateur a cliqué ; A passe *occupé*.
5.  Sur B : placer `test_spot` → le PNJ est en B ; A redevient *libre* ; en jeu il n'existe **qu'un
    seul** `test_spot` (même numéro Citizens qu'avant : `/npc select` ou la page PNJ).
6.  Parler au PNJ : son dialogue, son skin et ses quêtes sont inchangés. Redémarrer le serveur DEV :
    B est toujours *occupé* par `test_spot`, toujours au même endroit.
7.  Refus : placer un autre PNJ sur B → « Emplacement occupé ». Option avancée
    `/rpgadmin npc spot mark` dans le vide/l'eau → avertissement en jeu, placement refusé « ne laisse
    pas assez de place ».
8.  « Orienter » B vers le sud → le PNJ se tourne. « Renommer » B → nouveau nom visible.
9.  Supprimer B tant qu'occupé : le bouton n'est pas proposé ; « Libérer » (le PNJ ne bouge pas),
    puis « Supprimer l'emplacement » avec confirmation → B disparaît, le PNJ reste en place.
10. « Réserver la position actuelle d'un PNJ » (`test_spot`) → nouvel emplacement *occupé* à sa
    position. Déplacer le PNJ à la main (`/npc tphere`) puis « Actualiser » → état *conflit*
    « n'est plus à sa place » ; « Replacer ici » le ramène.
11. Téléphone (ou fenêtre étroite) : la page reste lisible sans défilement horizontal.
12. Se déconnecter / redémarrer le serveur avec l'outil dans l'inventaire : aucun emplacement n'est
    créé tout seul ; l'outil fonctionne toujours au retour. Un joueur sans `rpgquest.admin.npc.spot`
    qui tient l'outil → « Permission manquante », rien n'est créé.

### TC-316 — Passage d'étape : la prochaine action est annoncée (issue #70, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #70 déployé ; un joueur de test pouvant démarrer `crystal_hunt` (ou toute quête
à plusieurs étapes dont une `TALK_TO_NPC`). **Durée** : 10 minutes.

1.  Démarrer la quête : le chat affiche « Quête commencée : … » et les objectifs de la 1ʳᵉ étape,
    avec quantités (`(x5)`…), noms d'objets/créatures en français.
2.  Remplir le dernier objectif de l'étape : l'ActionBar affiche le compteur final, puis le chat
    affiche « Étape suivante : <quête> — à faire maintenant » suivi des objectifs de la nouvelle
    étape (et non de l'ancienne).
3.  Si la nouvelle étape est « parler au Garde » : la ligne lit « Parler à Garde », sans « (x1) »
    ni identifiant technique (`guard`).
4.  Terminer la dernière étape : **aucun** « Étape suivante », seulement le Title de fin et le
    résumé des récompenses.
5.  `/quests` : le journal montre bien l'étape annoncée.

### TC-317 — `/rpgadmin npc tag` : id validé et autocomplété (issue #66, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #66 déployé ; compte avec `rpgquest.admin.npc.tag` ; une entité (ou un PNJ
Citizens) **non identifiée** à moins de 6 blocs. **Durée** : 5 minutes.

1.  Taper `/rpgadmin npc tag ` puis Tab : la liste propose les ids connus, dont `guard` (et
    `guide`, `jo`, `libraire`…). `/rpgadmin npc tag gu` + Tab ne garde que `guard`, `guide`.
2.  En regardant l'entité : `/rpgadmin npc tag garde` → **refus** « ID inconnu : garde … Vouliez-vous
    dire guard ? » et rappel de `--force`. `/rpgadmin npc info` : l'entité n'est **pas** identifiée.
3.  `/rpgadmin npc tag zzz_inconnu` → refus **sans** suggestion.
4.  `/rpgadmin npc tag guard` → « Entité identifiée : guard — nom affiché : <nom> (Citizens NPC #n) ».
    Clic droit : le dialogue du Garde s'ouvre. `untag` ensuite si l'entité n'était qu'un essai.
5.  Sur une autre entité : `/rpgadmin npc tag essai_force --force` → identifiée, avec l'avertissement
    orange « Id hors registre (--force) ». `untag` pour nettoyer.
6.  `/rpgadmin npc tag` (sans id) → id auto-généré `npc_<n>`, comportement inchangé.

### TC-318 — PlugAdmin : identifiants d'étape de quête générés (issue #143, PENDING MANUAL VALIDATION)

**Prérequis** : panel #143 déployé ; compte `content-editor` ou `owner`. Aucun JAR ni redémarrage
Minecraft. **Durée** : 5 minutes. Ne **pas** enregistrer sur une quête jouée : utiliser une quête
d'essai, supprimée ensuite.

1.  `/quests/new` : l'étape 1 affiche `step_1` à côté de son numéro ; aucun champ obligatoire
    « Identifiant d'étape ». Le bloc replié « Identifiant technique (avancé) » contient le champ.
2.  « Ajouter une étape » deux fois → `step_2`, `step_3`. Supprimer l'étape 2, ajouter une étape →
    la nouvelle reçoit `step_4` (jamais un second `step_3`). Monter / descendre : chaque id suit
    son étape.
3.  Vider l'id d'une étape dans le bloc avancé puis « Actualiser » → un id `step_<n>` est régénéré.
4.  Enregistrer la quête d'essai (ses étapes `step_1`…) alors qu'une autre quête a déjà un
    `step_1` → enregistrement accepté.
5.  Rouvrir la quête d'essai, renommer `step_1` en `intro` dans le bloc avancé, « Vérifier » →
    avertissement « L'étape « step_1 » de la version enregistrée n'existe plus… reprendra à
    l'étape 1 » (ATTENTION, non bloquant). Supprimer ensuite la quête d'essai.

### TC-319 — PlugAdmin : renommer un waypoint (issue #134, PENDING MANUAL VALIDATION)

**Prérequis** : JAR + panel #134 déployés ; compte `owner` ou `admin`. **Durée** : 10 minutes.

1.  `/travel` → « Rafraîchir ». Dans le tableau « Waypoints », ouvrir « Renommer » sur un waypoint
    du Wild proche d'un joueur connecté : le nom actuel est pré-rempli.
2.  Saisir un nouveau nom (ex. `Col du Vent`) → « Renommer » : action réussie, effets « Panneaux
    réécrits en jeu » ; la page affiche le nouveau nom sans F5.
3.  En jeu : les deux panneaux affichent le nouveau nom, sans reste de l'ancien ; un clic sur le
    bouton annonce « Waypoint déjà découvert : Col du Vent » (découverte conservée) ; le menu de
    voyage montre le nouveau nom.
4.  Renommer un autre waypoint en `col du vent` → refus `DUPLICATE`, rien de modifié. Essayer
    `<red>X` → refus côté panel.
5.  Renommer un waypoint loin de tout joueur → effet « panneaux réécrits à son prochain
    chargement » ; s'y rendre : panneaux à jour.
6.  Redémarrer le serveur → les noms renommés sont conservés.

### TC-320 — PlugAdmin : progression structurée d'un joueur sur `/quests` (issue #79, PENDING MANUAL VALIDATION)

**Prérequis** : JAR + panel #79 déployés ; un joueur avec une quête en cours (ex. une quête de
casse ou de remise). **Durée** : 5 minutes.

1.  `/quests` → sélectionner le joueur dans « État des quêtes d'un joueur » → « Rafraîchir ».
2.  La quête en cours affiche chaque objectif en français (« Casser Bûche de chêne (x20) »),
    l'identifiant technique en second (`OAK_LOG`), puis la progression `4/20`.
3.  Terminer un objectif en jeu, rafraîchir : la ligne porte le badge vert « fait ».
4.  Une remise à un PNJ affiche l'objet **et** le PNJ (« Rapporter … à … »).

### TC-322 — Page « Voyages » simplifiée (issue #412, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de #412 sur DEV. Renommer **un seul** lieu de test, puis lui rendre son nom.

1.  PlugAdmin → **Voyages** : en moins de 10 secondes, on comprend la page (résumé « lieux · bornes ·
    à vérifier », date de mise à jour, onglets Lieux / Bornes / À vérifier / Avancé).
2.  Si le relevé a plus d'une heure, le bandeau orange le dit ; « Actualiser » le remet à jour.
3.  Lieux : chercher « foret » (sans accent) → les lieux « Forêt… » apparaissent ; filtre
    « Village » ; « Plus récents d'abord » ; page suivante.
4.  Cliquer **Renommer** sur un lieu → la fiche s'ouvre ; taper le nom d'un autre lieu →
    l'avertissement apparaît ; l'enregistrer quand même → refus « déjà pris », rien d'envoyé.
5.  Saisir un nouveau nom → « Enregistrer le nouveau nom » → toast de succès, la fiche affiche le
    nouveau nom après l'actualisation automatique ; **en jeu**, le panneau et le menu de voyage
    affichent le nouveau nom, la découverte du lieu est conservée. Rendre ensuite son ancien nom.
6.  Bornes : chaque borne porte le nom de son lieu ; « Voir » déplie les détails.
7.  À vérifier : les lieux du Village sans borne sont listés en phrases ; (facultatif) « Poser la
    borne maintenant » sur l'un d'eux → résultat affiché, retour sur l'onglet.
8.  Avancé : diagnostic du Hub, couverture, fiches de bornes, instances (filtre), Waystones,
    tableaux techniques — tous présents.
9.  Téléphone (≈ 390 px) : aucun défilement horizontal, boutons utilisables au doigt.
10. (Complément 19/10, PR #606) Sous la liste des lieux, « Voir aussi : Mondes · Portails » ouvre
    les deux autres pages du groupe Monde ; dans la fiche d'un lieu, « Informations techniques »
    → « Voir ce monde » ouvre la fiche de son monde dans la page Mondes.

### TC-321 — Catalogues de quêtes : parcours simplifié (issue #414, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #414 sur DEV. Ne supprimer **que** des catalogues de test.

1.  PlugAdmin → Quêtes → **Catalogues de quêtes** : la phrase d'explication et le bouton
    **Créer un catalogue** sont visibles sans défiler (ordinateur et téléphone).
2.  « Créer un catalogue » → nom « Quêtes de la forêt » → « Ajouter une quête » → taper « toiles »
    → cocher « Les toiles du sous-bois », puis une autre quête → cocher **Brigand des bois** →
    le résumé annonce 2 quêtes et le PNJ → « Créer le catalogue ».
3.  La fiche s'ouvre (après quelques secondes) : 2 quêtes par leur titre, « Brigand des bois »
    dans « PNJ qui proposent ces quêtes » ; s'il est marqué « ne sait pas encore proposer »,
    vérifier que le lien ouvre son dialogue — **aucun dialogue n'a été modifié**.
4.  Modifier → descendre une quête, en retirer une, enregistrer → la fiche reflète le changement.
5.  Dupliquer → « Créer la copie » → nouvelle fiche, mêmes quêtes, aucun PNJ.
6.  Supprimer la copie (confirmation obligatoire) → elle disparaît de l'accueil.
7.  Téléphone (≈ 390 px) : aucun défilement horizontal, boutons utilisables au doigt.
8.  (Correctif de validation) Fiche de « Quêtes des brigands des bois » (1 quête) : « Une seule
    quête : c'est toujours elle qui est proposée ». Rien n'évoque un ordre de proposition.
9.  Sur un catalogue de **test** à 2 quêtes : Modifier → la liste dit « au hasard … sert seulement à
    les ranger » ; « Comment choisir la prochaine quête ? » est sur « même chance pour toutes ».
10. Choisir « certaines plus souvent » → un menu Rare / Normale / Souvent apparaît à côté de chaque
    quête avec une part (« environ 67 % ») ; mettre l'une sur Souvent, enregistrer → la fiche
    affiche « Souvent · environ 67 % » et « Normale · environ 33 % ».
11. « Peut-on proposer deux fois de suite la même quête ? » → « Autoriser les répétitions »,
    enregistrer → la fiche le dit. Identifiant et note ne sont visibles qu'en ouvrant
    « Informations techniques » / « Notes ».

### TC-323 — Pack d'histoire mis en jeu en une seule action (issue #427, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #427 sur DEV. Les 5 quêtes Lily sont déjà sur DEV et
chargées (publication du 15/10) ; la story `lily_souvenirs` et les dialogues `lily`, `julius` ne le
sont pas. **Ne publier que des fichiers Lily**, jamais ceux du propriétaire en cours d'écriture.

1.  **Import contenu** → déposer le pack Lily → Analyser. **Attendu** : tout « inchangé » (déjà en
    source) et un encadré « Étape suivante » avec **Mettre l'histoire en jeu sur DEV**.
2.  Cliquer. **Attendu** : `/content/pending` en vue pack — seules les ressources Lily non à jour,
    déjà cochées ; « N déjà à jour sur DEV » ; aucun fichier du propriétaire.
3.  **Sans** cocher la confirmation → **Vérifier**. **Attendu** : on reste sur la **même vue pack**
    (adresse avec `pack=`), les ressources toujours cochées, « Vérification en cours… » puis
    « Vérification réussie » ; « Vérification d'un lot de N ressource(s) — vérification réussie,
    rien n'a été mis en jeu ». Lily en jeu n'a toujours pas de dialogue. (Correctif du 15/10 : la
    page renvoyait sur la liste générale, cases décochées, et exigeait la confirmation.)
4.  Cocher « Je confirme » → **Mettre l'histoire en jeu** (sans la case : le navigateur la réclame,
    rien n'est envoyé). **Attendu** : retour sur la vue pack, « Histoire mise en jeu », « M/M en jeu » ; « tout est en jeu », chaque ressource « créée et
    chargée en jeu ». Cliquer sur Lily ouvre son dialogue ; `/stories` liste « Les souvenirs de Lily ».
5.  Recliquer « Mettre l'histoire en jeu » (relancer l'analyse d'abord). **Attendu** : plus rien à
    publier, ou « déjà en jeu, rien à faire » — aucun doublon.
6.  (Diagnostic) Sur une quête de **test** à identifiant jetable : prérequis vers une quête
    inexistante → publier. **Attendu** : « refusée par le moteur », « Fichier quests/….yml —
    prérequis introuvable … Publier aussi cette quête, dans le même lot, ou retirer ce prérequis » ;
    la quête de test n'est **pas** restée sur DEV. Supprimer ensuite la quête de test de la source.
7.  Téléphone (≈ 390 px) : vue pack lisible, boutons utilisables au doigt.
8.  Puis TC-312 étapes 8 à 10 (parcours en jeu de Lily et Julius).

### TC-324 — Dialogue d'un PNJ récurrent : copie depuis DEV et quête au hasard dans une liste (issue #430, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de #430. **Ne pas republier le dialogue réel du brigand** pour ce test :
utiliser un dialogue et un modèle de **test**, ou s'arrêter avant « Mettre en jeu ».

1.  Quêtes → Catalogues de quêtes → fiche « Quêtes des brigands des bois » : chaque PNJ a
    **Configurer le dialogue de ce PNJ** ; le lien ouvre le dialogue `encounter_bandit`.
2.  L'en-tête dit « PNJ récurrent : Brigand des bois — liste : Quêtes des brigands des bois », et
    plus « Aucun personnage n'utilise ce dialogue ».
3.  Dialogue Hors source : encadré **Rendre ce dialogue modifiable** → « Lire le dialogue sur le
    serveur » → recharger : nombre de lignes, empreinte, « Voir le fichier lu » (fichier complet).
4.  « Copier dans PlugAdmin pour le modifier » → l'éditeur s'ouvre modifiable ; après actualisation, le
    bandeau dit « À jour en jeu » (même fichier) ; **en jeu, le brigand parle exactement comme avant**.
5.  Encadré « Ce PNJ a une liste de quêtes… » : il cite les réponses « Du travail ? », « Où en est ma
    tâche ? », « Encore du travail ? », l'effet « Démarrer une quête » et le texte de l'offre.
6.  Sur « Commencer (j'accepte) » ou « Du travail ? » : Et aussi… → **Proposer une quête au hasard
    dans une liste** → « Quelle liste ? » montre les listes par nom (une liste désactivée est
    marquée) ; l'aperçu dit « propose une quête au hasard dans la liste … ». Ne pas enregistrer sur
    le vrai dialogue (ou le faire sur une copie de test, puis la supprimer).
7.  Sur un PNJ récurrent **de test** : faire le passage complet, enregistrer, mettre en jeu, parler
    au PNJ → offre tirée de la liste, « Commencer (j'accepte) », suivi de la tâche, retour.
8.  Téléphone (≈ 390 px) : encadrés lisibles, aucun défilement horizontal.

### TC-325 — Profils web privés par défaut et page Confidentialité (issue #55, PENDING MANUAL VALIDATION)

Prérequis : plugin et portail issus de #55. Aucune donnée de joueur n'est modifiée par ce test,
sauf le réglage `/profile web` du testeur lui-même.

1.  Navigateur : https://lodyquests.lodylands.com/confidentialite. **Attendu** : « aucun pseudo
    n'est publié sur ce site », « Par défaut, ton profil est privé », les commandes
    `/profile web public` et `/profile web prive`, « ni compte, ni connexion, ni paiement ».
2.  Navigateur : `/joueurs` et `/leaderboards` → « pas encore publiée », aucun pseudo.
3.  Serveur DEV : `plugins/RPGQuest/config.yml` contient `web-export.profile-visibility-default:
    private` (ajouté par le compléteur au démarrage, aucune autre clé modifiée).
4.  En jeu, avec un joueur qui n'a jamais utilisé la commande : `/profile web`. **Attendu** :
    « Ton profil web est masqué (réglage par défaut du serveur) ».
5.  `/profile web public` → « Profil web public… « /profile web prive » pour revenir en arrière » ;
    `/profile web` → « public », sans la mention « réglage par défaut ».
6.  `/profile web prive` → « Profil web masqué » ; `/profile web` → « masqué ».
7.  (Seulement quand le propriétaire activera l'export, après sa décision #55) : seul un joueur
    ayant tapé `/profile web public` figure dans `snapshot.json` (classements, `profiles`,
    `players`).


### TC-326 — Comptes du portail et liaison Minecraft par `/lien` (issue #53, PENDING MANUAL VALIDATION)

Prérequis : plugin et portail issus de #53. Aucune donnée de jeu n'est modifiée par ce test.

**A. État livré (comptes désactivés)**

1.  Navigateur : https://lodyquests.lodylands.com/compte (menu « Mon compte »). **Attendu** :
    « Les comptes du portail ne sont pas encore ouverts », aucun formulaire.
2.  En jeu : `/lien ABCD2345`. **Attendu** : « La liaison avec le portail n'est pas activée sur ce
    serveur. » ; `/link` est un alias.

**B. Après ouverture par le propriétaire** (docs/WEB_PORTAL.md §2, « Mise en route » : comptes,
seuil d'âge, jeton des deux côtés, `portal-link.properties` sur DEV, redémarrages)

3.  `/compte` → créer un compte (pays configuré, âge au-dessus du seuil) → « Compte créé » ; se
    connecter → « Connecté en tant que … », « Aucun compte Minecraft relié ».
4.  « Obtenir un code de liaison » → la page montre `/lien XXXXXXXX` et l'heure limite.
5.  En jeu : `/lien XXXXXXXX` → « C'est fait : ton compte Minecraft est relié à ton compte du
    portail. » ; recharger `/compte` → « Relié à <pseudo> depuis le … », historique « liaison avec ».
6.  Retaper le même code, ou un code de plus de 10 minutes → « Code invalide ou expiré. »
7.  Second compte web, nouveau code tapé par le même joueur → « Ton compte Minecraft est déjà
    relié à un compte du portail. »
8.  « Délier » avec un mauvais mot de passe → refus ; avec le bon → « délié », historique
    « dissociation de <pseudo> ».
9.  « Se déconnecter » puis Précédent → plus connecté. Âge sous le seuil à l'inscription → compte
    « limité », pas de bouton de code.
10. `journalctl -u lodyquests-portal` et `latest.log` du serveur : aucun code ni jeton.
11. Téléphone (≈ 390 px) : formulaires lisibles, aucun défilement horizontal.

### TC-327 — Statut du serveur sur le portail et acheminement de l'instantané (issue #421, PENDING MANUAL VALIDATION)

Prérequis : portail et plugin issus de #421. Aucune donnée de jeu n'est modifiée par ce test ; aucun
pseudo n'est publié (`player-data-public=false`).

**A. État livré (canal inactif)**

1.  Navigateur : https://lodyquests.lodylands.com/ puis `/status`. **Attendu** : bandeau gris
    « État du serveur de jeu inconnu — aucune donnée reçue pour le moment » ; carte « Le serveur »
    « ● État inconnu » ; **jamais** de bandeau rouge ni « Hors ligne ».
2.  `curl -s https://lodyquests.lodylands.com/health` → `"snapshot":"MISSING"`, `"server":"UNKNOWN"`.
3.  `curl -s -o /dev/null -w '%{http_code}\n' -X POST https://lodyquests.lodylands.com/api/snapshot`
    → `401` ; même chose en `GET` → `403` (nginx).

**B. Après activation par le propriétaire** (jetons des deux côtés, `web-export.enabled: true`,
`portal-snapshot.properties` sur DEV, redémarrage — `docs/deployment/WEB_PORTAL_AWS.md`)

4.  Moins d'une minute après le démarrage du serveur : `/health` → `"FRESH"`, `"ONLINE"` ; `/` →
    « ● En ligne », nombre de joueurs connectés ; `/status` → « Dernière mise à jour : … ».
5.  Arrêter le serveur Minecraft proprement. **Attendu** : `/` → « ● Hors ligne », « Arrêt annoncé
    par le serveur le … » ; bandeau rouge.
6.  Serveur relancé, puis canal coupé (`enabled=false` + redémarrage, ou jeton retiré du portail).
    **Attendu**, après 2 minutes : bandeau gris « Informations non actualisées depuis le … », pas
    « hors ligne » ; `latest.log` : un seul avertissement « Envoi de l'instantané au portail … impossible »,
    sans jeton.
7.  `journalctl -u lodyquests-portal` et `latest.log` : aucun jeton ; `snapshot.json` sur AWS ne
    contient ni UUID, ni IP, ni position.
8.  Téléphone (≈ 390 px) : bandeaux lisibles, aucun défilement horizontal.

### TC-328 — Droits à récompense : remise unique en jeu (issue #422, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de #422 (migration V41), PlugAdmin à jour. Sur DEV seulement, avec un compte de
test. Créer `plugins/RPGQuest/entitlements.yml` :

```yaml
enabled: true
rewards:
  test_diamants:
    label: "Trois diamants de test"
    items: [{item: DIAMOND, amount: 3}]
```

1.  Console, sous une minute : `Récompenses : 1 récompense(s) chargée(s) depuis entitlements.yml, activé.`
2.  PlugAdmin → Joueurs → fiche du compte de test → **Monnaie** → **Droits à récompense** →
    « Lire les droits du joueur ». **Attendu** : « Aucun droit à récompense », formulaire de déclaration
    proposant « Trois diamants de test (test_diamants) ».
3.  Déclarer avec la référence `tc328-a`, joueur **connecté**, inventaire libre. **Attendu**, sous 30 s :
    « Récompense reçue : Trois diamants de test » et 3 diamants, une fois ; relevé → « Remis » daté.
4.  Redéclarer `tc328-a`. **Attendu** : « Ce droit existait déjà » ; aucun diamant de plus.
5.  Remplir les 36 cases, déclarer `tc328-b`. **Attendu** : « Une récompense t'attend » + cases à
    libérer, rien au sol, relevé « En attente ». Libérer une case : diamants reçus sous 30 s.
6.  Joueur **déconnecté**, déclarer `tc328-c`, se reconnecter. **Attendu** : remise ~3 s après la
    connexion.
7.  Déclarer `tc328-d` joueur déconnecté, puis **Révoquer** (raison « test »). Reconnexion : rien
    reçu ; relevé « Révoqué — test ». Aucun bouton « Révoquer » sur un droit remis.
8.  Deux connexions rapides (quitter/revenir pendant la remise) : jamais deux remises du même droit.
9.  Redémarrer le serveur avec un droit en attente : remise après le redémarrage, une fois.
10. Remettre `enabled: false` (ou supprimer le fichier) : plus aucune remise ; la fiche dit
    « Fonction désactivée ».

### TC-329 — Boss communautaires du Wild (issue #175, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #175, sur **DEV seulement**. Un compte de test connecté **dans le
Wild**, en survie, près de waypoints existants.

1.  PlugAdmin → Mobs spéciaux & boss → ouvrir un profil **Boss** jetable (ou en créer un) → Modifier →
    « Type de boss » = **Boss communautaire** → Enregistrer. Section « Boss communautaires du Wild » →
    « Rafraîchir l'état ». **Attendu** : le bandeau « Aucun profil n'est encore un boss communautaire »
    disparaît ; « Apparitions automatiques : en pause ».
2.  **Apparition de test** (boss au hasard, près du compte de test). **Attendu** : le boss apparaît à
    40–96 blocs, jamais dans une ville, une zone protégée ou collé à un waypoint ; tout le serveur lit
    « ⚔ Boss communautaire : <nom> vient d'apparaître dans le biome …, à environ N blocs au <direction> du
    waypoint <nom réel> (apparition de test) » ; barre de vie visible de près ; le prochain essai n'a pas
    bougé. Aucune découverte de waypoint, aucune téléportation.
3.  Se déconnecter puis se reconnecter. **Attendu** : rappel individuel « Un boss communautaire rôde… »
    ~3 s après la connexion ; un autre joueur déjà connecté ne reçoit rien.
4.  Réglages : activer, intervalle **1 min**, maximum 1 (DEV uniquement). Tuer le boss de test.
    **Attendu** : « … a été vaincu par <pseudo> » ; à l'échéance suivante, un boss apparaît tout seul ;
    les échéances suivantes ne créent pas de deuxième boss tant qu'il vit (« Plafond atteint »).
5.  S'éloigner pour décharger sa zone, redémarrer le serveur, revenir. **Attendu** : toujours un seul
    boss (verrou restauré), barre de vie présente, aucun doublon ; décocher « Apparitions automatiques »
    → plus rien n'apparaît. **Retirer ce boss** (case cochée) → disparaît sans drop.
    Restaurer ensuite les réglages (intervalle 15 min, en pause si souhaité).

### TC-339 — Lily : fin de la tarte → Julius à Nerana, PNJ nommés (issue #180, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de #180 sur **DEV** et `dialogues/lily.yml` corrigé publié (rapport #180). Aucun
reset de joueur : compte dont « La tarte de maman » est active, ou un second compte qui refait le
parcours Lily.

1.  Journal et bossbar de « La tarte de maman » (suivie) : les lignes nomment **Lily** (« Charbon (à
    remettre à Lily) », « Parler à Lily »), jamais « Parler au PNJ » ni l'identifiant `lily`.
2.  Remettre les 4 charbons dans le dialogue, « Retour », « Le four est chaud ? Goûtons la tarte. »,
    puis **« Parle-moi de Julius »**. **Attendu** : « Quête terminée : La tarte de maman » (2 tartes,
    40 XP, une seule fois), puis l'offre de Lily citant **Nerana** et une direction (« au nord, à
    environ 420 blocs » depuis Lily) — **jamais** l'intro « Oh… bonjour » ni « Goûtons la tarte ».
3.  Variante : remettre le charbon, **fermer**, recliquer Lily. **Attendu** : la tarte est validée au
    clic, l'accueil propose « Ce Julius dont tu te souviens… » (pas « Goûtons »).
4.  « Commencer la quête : Sur la piste de Julius ». Journal : « Parler à Julius ».
5.  Recliquer Lily : « Je cherche toujours Julius. » redonne Nerana et la direction, recalculée depuis
    la position actuelle. Se déconnecter / reconnecter : même réponse.
6.  Marcher vers Nerana, parler à Julius : récit, quête terminée, puis Lily répond « J'ai parlé à
    Julius. ».
7.  Renommer la ville Nerana dans PlugAdmin (Villes) puis reparler à Lily : le nouveau nom apparaît.
    Remettre le nom d'origine ensuite.

### TC-338 — Dialogues : un bouton par ligne, remise Lily cohérente, aucun /quests (issue #467, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de #467 sur **DEV**, renderer `paper-dialog` (défaut). Aucun reset de joueur : utiliser
un compte où Lily propose encore une quête, ou un second compte.

1.  Parler à **Lily**. **Attendu** : une fenêtre avec **un bouton par ligne**, tous de la même largeur ;
    « Tu as l'air affamée. Je peux t'aider ? » / « Je t'apporte ce que tu m'as demandé » lisibles en entier.
2.  Ouvrir l'offre puis **« Commencer la quête : Un repas pour Lily »** (ou « Des fleurs pour Lily ») : le
    libellé est entier. Dans le chat : « Quête commencée : … — à faire (détails dans ton journal de
    quêtes) : », **sans** `/quests`.
3.  Remise **partielle** (ex. 1 pain) : « Je t'apporte… » → « Donner ce que j'ai ». **Attendu** : la
    fenêtre suivante montre `1/3` et ce qui manque.
4.  Remise **complète** : « Donner ce que j'ai ». **Attendu** : « Quête terminée » à l'écran et, dans la
    fenêtre de Lily, `✔ Pain 3/3`, `✔ Pomme 4/4` puis « Merci, c'est tout ce qu'il me fallait ! » —
    **jamais** « Je n'attends aucun matériau de ta part ». Exactement 3 pains et 4 pommes retirés,
    même en double-cliquant.
5.  « Retour » : l'accueil propose le chapitre suivant (« Tu vas mieux ? … »).
6.  Échelles d'interface : refaire l'étape 1 en 854×480 (échelle auto) et avec une grande échelle
    d'interface. **Attendu** : boutons entièrement visibles, aucun chevauchement ; navigation au clavier
    (Tab/Entrée) possible.
7.  Invite d'entrée dans le Wild (portail du Hub) : boutons aussi un par ligne.

### TC-337 — Boss de quête personnel : apparition, localisation, attribution (issue #171, PENDING MANUAL VALIDATION)

Prérequis : un profil de mob **Boss**, type « Boss de quête », activé (page Mobs, ex. `ogre`) ; au moins
un waypoint actif dans le Wild ; une quête de test avec une étape « Vaincre son boss de quête »
(`KILL_QUEST_BOSS`, profil `ogre`) créée dans PlugAdmin. Deux comptes (A et B) si possible.

1. A accepte la quête (au Hub ou dans le Wild). Attendu : message « Votre boss de quête … vous attend
   dans le biome …, à environ N blocs <direction> du waypoint … (waypoint déjà découvert / pas encore
   découvert) » ; même ligne dans le journal (`/quests`, détails de la quête). Aucun waypoint débloqué.
2. Recliquer l'acceptation, se déconnecter/reconnecter, mourir : toujours **un seul** boss (vérifier
   sur place ou `/rpgadmin mob list`) et la même indication.
3. B tue un boss du même profil apparu pour lui, ou un zombie ordinaire : l'objectif de A ne bouge pas.
4. A s'éloigne à plus de 64 blocs, B tue le boss de A : A est prévenu « objectif non rempli », un
   nouveau boss apparaît ~1 min plus tard.
5. A à moins de 64 blocs, le boss meurt (tué par A ou par B) : « objectif rempli », l'étape avance.
6. Accepter de nouveau puis abandonner : le boss disparaît sans butin.
7. Redémarrer le serveur pendant qu'un boss est actif : même boss, même indication, aucun doublon.
8. Profil désactivé ou mis en « Boss communautaire » : à l'acceptation, message « indisponible …
   prévenez un administrateur » une fois, ligne rouge dans le journal, rien n'apparaît.

### TC-336 — Administrer le catalogue des récompenses sans YAML (issue #474, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #474 sur **DEV**, `entitlements.yml` **absent** (état DEV au 16/10).
Compte PlugAdmin avec « Économie — écriture ». Aucun accès fichier nécessaire.

1.  PlugAdmin → **Récompenses** → Rafraîchir. **Attendu** : « Aucun catalogue », aperçu du fichier qui sera
    créé (`enabled: false`, aucune récompense ni table), bouton **Créer le catalogue** et case à cocher.
2.  Cliquer sans cocher → refusé. Cocher → Créer. **Attendu** : succès ; la page montre « Récompenses sur ce
    serveur : désactivées », 0 récompense, « Nouvelle récompense ».
3.  **Nouvelle récompense** : « Gemme d'essai », objet « diam » → Diamant, quantité 2 → Créer. Puis
    « Épée d'essai » : Épée en diamant, enchantements `sharpness 3, unbreaking 2`. **Attendu** : deux fiches
    actives, contenu lisible (« 2 × DIAMOND », « DIAMOND_SWORD (sharpness 3, unbreaking 2) »).
4.  Erreurs lisibles, rien écrit : enchantement `pas_un_enchantement 1` ; potion sur une épée ; quantité 65.
5.  **Modifier** la gemme (quantité 3) → Enregistrer ; sans changement → « Aucune modification ».
    **Dupliquer** l'épée → copie **désactivée**.
6.  Tables de tirage → **Nouvelle table** avec les deux récompenses (ex. 30 % / 5 %). **Attendu** : chaque fiche
    de récompense affiche « Utilisée par : table … ». Associer la table au boss zombie d'entraînement
    (éditeur de boss, `Tirage : …`) puis Rafraîchir : la table affiche « Tirée par : boss … ».
7.  **Désactiver** la gemme. **Attendu** : message citant la table et le boss concernés ; badge « désactivée » ;
    dans la liste des tables, « (désactivée) ».
8.  Interrupteur général : cocher « J'ai compris » → **Activer les récompenses**. **Attendu** : « activées ».
9.  En jeu (TC-331) : boss d'entraînement vaincu → l'épée tirée est remise **une fois** (inventaire plein :
    elle attend) ; la gemme désactivée n'est jamais donnée. Puis **Désactiver les récompenses** → victoire →
    « les récompenses sont désactivées sur le serveur », aucun droit créé.

### TC-335 — Quête d'amélioration du kit dans le journal (issue #475, PENDING MANUAL VALIDATION)

> **17/10 — tests suspendus par le propriétaire puis repris par TC-348.** Le dépôt partiel (étapes 3-5)
> est **déjà validé** ; l'étape 4 « J'ai des matériaux » n'existe plus (action unique « Donner les
> matériaux ») et le Guide s'appelle désormais selon son nom affiché (« Elias »). Reprendre par TC-348.

Prérequis : JAR issu de #475 sur **DEV**, un compte au palier de kit N (variable `STARTER_KIT_TIER`), sans la
quête du palier N+1 acceptée. Ne jamais remettre à zéro un compte réel pour ce test.

1.  Ouvrir le journal (clic droit sur le Journal des quêtes). **Attendu** : la quête du palier suivant n'y
    est pas (non acceptée = invisible).
2.  Guide → « Comment améliorer mon kit ? » → « Commencer l'amélioration ». Rouvrir le journal.
    **Attendu** : onglet « Quêtes en cours », la quête (titre narratif, ex. « Explorateur ») porte la ligne
    « Amélioration du kit — palier N+1 » et une ligne par matériau, ex. « Bâton (à remettre à Guide) 0/4 ».
3.  Ramasser une partie des matériaux sans les remettre. **Attendu** : compteurs inchangés.
4.  Guide → « J'ai des matériaux ». **Attendu** : les compteurs du journal (ouvert ou rouvert) montrent les
    quantités remises, le reste visible d'un coup d'œil. Clic gauche = détails (tous les objectifs), clic
    droit = suivi (bossbar).
5.  Mourir, se déconnecter / reconnecter. **Attendu** : mêmes compteurs.
6.  Remettre le reste. **Attendu** : palier débloqué, la quête quitte « en cours » et apparaît dans
    « Quêtes terminées » ; le palier suivant devient proposable par le Guide.

### TC-334 — Boss communautaire sans joueur dans le Wild, contour, surface (issue #471, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #471, sur **DEV seulement**, au moins un waypoint actif dans le
Wild, un profil Boss jetable en *Boss communautaire*. **Ne pas activer le scheduler sans l'accord du
propriétaire** : les étapes 1–4 utilisent l'apparition de test.

1.  Tous les comptes au **Hub** (ou dans les Claims). PlugAdmin → Boss communautaires du Wild →
    Apparition de test, « Près de » = **N'importe où dans le Wild**. **Attendu** : SUCCESS/SPAWNED (plus
    jamais NO_PLAYER) ; au Hub, chacun lit « ⚔ Boss communautaire : <nom> vient d'apparaître dans le
    biome …, à environ N blocs au nord/à l'est… du waypoint <nom réel> (apparition de test) ».
2.  Aller au waypoint annoncé (voyage normal), suivre la direction. **Attendu** : boss trouvé à ~N blocs,
    debout sur un sol naturel à ciel ouvert (pas dans une grotte, l'eau, un arbre, un toit) ; à moins de
    48 blocs, **contour lumineux** visible à travers le relief, et barre de vie ; en s'éloignant au-delà
    de 48 blocs le contour s'éteint (≤ 2 s).
3.  Décocher « Contour lumineux », Enregistrer, revenir près du boss. **Attendu** : plus de contour. Le
    recocher. Laisser le boss loin de tout joueur, redémarrer le serveur, revenir. **Attendu** : un seul
    boss, toujours là, contour à l'approche.
4.  (Facultatif) Désactiver tous les waypoints du Wild ou tester un profil limité à un monde sans
    waypoint. **Attendu** : échec lisible « Aucun repère dans le Wild… », rien d'annoncé.
5.  Avec l'accord du propriétaire seulement : scheduler actif, tout le monde au Hub, attendre
    l'échéance. **Attendu** : boss apparu dans le Wild + annonce à tous. Restaurer ensuite les réglages.

### TC-330 — Participation et récompenses des boss communautaires (issue #176, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #176 (règles du 16/10 : rayon et mort pendant le combat), sur
**DEV seulement**. `plugins/RPGQuest/entitlements.yml` présent avec `enabled: true` et une récompense de test, par exemple :

```yaml
enabled: true
rewards:
  boss_test:
    label: "Butin de boss (test)"
    items:
      - {item: DIAMOND, amount: 1}
```

Deux comptes de test (A et B) dans le Wild, en survie. Un profil Boss jetable en *Boss communautaire*.

1.  PlugAdmin → Mobs spéciaux & boss → profil Boss → Modifier → « Récompenses de participation » =
    `boss_test` → Enregistrer, rouvrir. **Attendu** : la valeur est conservée ; sur un profil Spécial,
    le champ est ignoré.
2.  **Apparition de test**. A frappe **une seule fois** à l'épée ; B tue le boss (arc conseillé : le
    projectile doit compter). **Attendu** : A et B lisent « Participation reconnue contre … » puis
    « Récompense reçue : Butin de boss (test) » ; un diamant chacun ; « Victoires récentes et
    participants » (après « Rafraîchir l'état ») liste A et B **éligibles**, « 2 droit(s) déclaré(s) ».
3.  Nouveau boss de test. A frappe puis s'éloigne à **plus de 100 blocs** (F3 : coordonnées) ; B frappe
    puis **meurt** et attend sur l'écran de mort ; un 3ᵉ compte (ou A revenu) tue le boss. **Attendu** :
    A « trop loin du boss (… blocs, 100 au plus) », B « mort au moment de la victoire », aucun diamant
    pour eux, message d'explication.
3b. Frontière de biome : A frappe et se place **à quelques blocs** du boss mais dans un **autre biome**
    (F3 : biome) au moment de sa mort. **Attendu** : A **éligible**, diamant reçu.
3c. Mort pendant le combat : B frappe, **meurt**, réapparaît, revient près du boss **sans le toucher** ;
    le boss meurt. **Attendu** : B exclu « mort pendant le combat, sans nouveau coup ensuite ». Nouveau
    boss : B frappe, meurt, revient et **frappe de nouveau** (un coup suffit). **Attendu** : B éligible.
3d. Distance réglable : Réglages → « Distance pour être récompensé (blocs) » = 20 → Enregistrer, puis
    « Rafraîchir l'état » : 20 relu. A frappe et se tient à ~30 blocs à la mort. **Attendu** : exclu
    « trop loin du boss (30 blocs, 20 au plus) », la victoire affiche « récompenses à 20 blocs au plus ».
    Remettre **100** à la fin.
4.  Inventaire plein : remplir l'inventaire de A, frapper, faire tuer le boss. **Attendu** : « Une
    récompense t'attend » + « libère N emplacements », rien au sol ; libérer une case → diamant reçu
    sous 30 s ; jamais deux.
5.  A frappe puis **se déconnecte** avant la mort. **Attendu** : A exclu « hors ligne », aucun droit.
6.  Fiche joueur A → Monnaie → Droits à récompense → Lire. **Attendu** : origine « Boss communautaire —
    rpgquest:…@… », état Remis (ou En attente pour le cas 4 avant libération).
7.  Redémarrer avec un droit en attente (cas 4 non libéré). **Attendu** : remis à la reconnexion, une fois.

### TC-331 — Tables de récompenses aléatoires quêtes / boss (issue #177, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #177, sur **DEV seulement**. `plugins/RPGQuest/entitlements.yml`
avec `enabled: true`, par exemple :

```yaml
enabled: true
rewards:
  tc177_gem: {label: "Émeraude (test)", items: [{item: EMERALD, amount: 1}]}
  tc177_book: {label: "Livre Tranchant III (test)", items: [{item: ENCHANTED_BOOK, enchantments: {sharpness: 3}}]}
  tc177_potion: {label: "Potion de force (test)", items: [{item: POTION, potion: strength}]}
tables:
  tc177_sure:
    label: "Coffre certain (test)"
    mode: single
    entries:
      - {id: book, reward: tc177_book, chance: 100}
  tc177_mix:
    label: "Coffre mélangé (test)"
    mode: independent
    entries:
      - {id: gem, reward: tc177_gem, chance: 50}
      - {id: potion, reward: tc177_potion, chance: 50}
```

1.  Console après ≤ 1 min : « Récompenses : 3 récompense(s) chargée(s) … activé ». PlugAdmin → Mobs
    spéciaux & boss → Boss communautaires du Wild → Rafraîchir l'état. **Attendu** : section « Tables de
    récompenses aléatoires » avec les deux tables, leur mode, « 100 % » / « 50 % », l'explication (« aucun
    gain : 0 % » ; « Aucun gain : 25,00 % »).
2.  Fichier invalide : passer `tc177_sure` en `chance: 60` + une 2ᵉ entrée à 50 en mode `single`.
    **Attendu** : console « ne peut pas dépasser 100 % », fichier entier refusé (fonction désactivée) ;
    remettre le fichier correct.
3.  Quête jetable (éditeur de quête PlugAdmin) avec une récompense « Objet » (1 pain) et une récompense
    « Tirage au sort » `tc177_sure`, publiée sur DEV. La terminer. **Attendu** : résumé de fin « + Livre
    Tranchant III (test) (tirage « Coffre certain (test) ») », pain reçu, puis « Récompense reçue : … » et un
    **livre enchanté** Tranchant III (l'épée n'est pas enchantée). Fiche joueur → Monnaie → Droits à
    récompense : origine quête, libellé « … — table tc177_sure v… (100 %) », état Remis.
4.  Même quête, inventaire plein avant de la terminer avec la seule place pour le pain… **Attendu** : le
    livre attend (« libère N emplacements »), rien au sol, reçu après libération, une seule fois.
5.  Quête répétable avec `tc177_mix` terminée 4 fois. **Attendu** : selon le tirage, 0, 1 ou 2 gains par
    cycle, « pas de gain cette fois » quand rien n'est tiré ; jamais de doublon dans les droits.
6.  Récompense « Tirage au sort » vers une table inexistante (`tc177_absente`). **Attendu** : « Tirage
    « tc177_absente » indisponible — signale-le à un administrateur », aucun droit, avertissement console.
7.  Boss communautaire : profil Boss → « Récompenses de participation » = `table:tc177_sure` → apparition de
    test, deux joueurs frappent, boss tué. **Attendu** : chacun reçoit **son** livre (« Participation
    reconnue … Livre Tranchant III (test) ») ; « Victoires récentes » → ligne « Tirages : A : table
    « tc177_sure » v… → … ; B : … ».
8.  Redémarrer DEV avec un gain de tirage en attente (cas 4 non libéré). **Attendu** : remis à la
    reconnexion, une fois, sans nouveau tirage.

### TC-333 — Éditer les tables de récompenses depuis PlugAdmin (issue #177 tranche 2, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #177 tranche 2 sur **DEV**, catalogue avec au moins deux récompenses —
depuis #474, créé et rempli depuis PlugAdmin (TC-336), sans fichier à écrire. Compte PlugAdmin avec « Économie ».

1.  PlugAdmin → **Récompenses aléatoires** → Rafraîchir. **Attendu** : bandeau d'état (actif / désactivé),
    catalogue des récompenses en bas, tables existantes avec Modifier / Désactiver / Dupliquer.
2.  **Nouvelle table** : nom « Coffre du marais », identifiant vide, « Une récompense au plus », deux
    récompenses choisies dans la liste (taper « émer ») à 30 et 12,5. **Attendu** : pendant la saisie,
    « Total : 42,5 % · chance de ne rien gagner : 57,5 % » ; passer la 2ᵉ à 80 → « trop élevé » ; remettre
    12,5 → Créer → toast de succès, table `coffre_du_marais` listée, active, version affichée.
3.  Console / fichier : `entitlements.yml` contient la nouvelle table, le reste du fichier (récompenses,
    commentaires, autres tables) est inchangé.
4.  **Modifier** : changer une chance → Enregistrer. **Attendu** : message « Nouvelle version vXXXX (les gains
    déjà tirés gardent leur version …) ». Enregistrer sans rien changer → « Aucune modification ».
5.  **Dupliquer** → Créer la copie. **Attendu** : `coffre_du_marais_copie` désactivée, mêmes chances ;
    **Activer** → active.
6.  Éditeur de quête → récompense « Tirage au sort » → le champ propose les tables (recherche par nom) ;
    choisir la copie désactivée → avertissement « est désactivée ». Éditeur de boss → « Récompenses de
    participation » propose les récompenses et « Tirage : Coffre du marais ».
7.  Cohérence désactivé : `enabled: false` dans `entitlements.yml`, terminer une quête avec « Tirage au sort ».
    **Attendu** : résumé « Tirage « … » : récompenses aléatoires désactivées sur le serveur », aucun droit
    créé, garanties de la quête reçues. Boss communautaire de test avec `table:` tué : chaque participant
    éligible lit « les récompenses sont désactivées sur le serveur ». Remettre `enabled: true`.
8.  Désactiver la table depuis PlugAdmin puis terminer la quête / tuer le boss. **Attendu** : « Tirage « id »
    indisponible » côté quête **et** côté boss, aucun gain.

### TC-358 — Formulaire de table : bouton grisé, règle des 100 %, refus sans perte (issue #177, retour du 18/10, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de la PR #177 « formulaire de table » sur **DEV**, catalogue avec au moins deux
récompenses (TC-336). Rien n'est écrit tant que « Créer la table » n'est pas cliqué avec un formulaire valide.

1.  Récompenses → Rafraîchir → **Nouvelle table**. **Attendu** : « Créer la table » grisé, à côté « Donner un nom à
    la table. ». Saisir un nom → « Ajouter au moins une récompense. ».
2.  Ligne 1 : taper une partie du nom, choisir une récompense. **Attendu** : sous le champ, le **nom et la
    quantité** (« Gemme rare — 3 × Diamant ») puis l'identifiant en petit.
3.  Deux récompenses à 70 et 50 en « Une récompense au plus ». **Attendu** : bouton grisé, raison « le total fait
    120 %, 100 % au plus ». Passer à « Chaque récompense tentée séparément » → bouton **réactivé**, raison effacée.
    Mettre une chance à 101 → grisé (« entre 0,01 et 100 % ») ; remettre 50 → réactivé.
4.  Identifiant « yes » → grisé avec la raison ; le vider → réactivé.
5.  Refus du serveur : modifier une table, et pendant que la fiche est ouverte, la modifier depuis un autre onglet
    (formulaire périmé), puis Enregistrer dans le premier. **Attendu** : la page revient avec « Refusé, rien n'a été
    enregistré : … » et la fiche **rouverte avec vos saisies** (nom, mode, activation, récompenses, chances).
6.  Ne pas cliquer « Créer la table » pour de vrai si l'on ne veut pas de table de test sur DEV ; sinon la
    désactiver ensuite.

### TC-359 — Récompense : enchantements guidés, rafraîchissement automatique, envoi unique (issue #474, retour du 18/10, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de la PR #474 « retour UX » sur **DEV**, catalogue créé (TC-336). Ne pas
activer l'interrupteur général. Ne pas modifier les récompenses de test existantes (`test_pomme`,
`test_pomme_copie`, `test_livre_enchante`) : utiliser une nouvelle récompense **désactivée**.

1.  Récompenses → Rafraîchir → **Nouvelle récompense**. Objet 1 : taper « livre », choisir ENCHANTED_BOOK.
    **Attendu** : sous le champ, « Livre enchanté · ENCHANTED_BOOK ».
2.  Sous « Ajouter un enchantement… », taper « tranch ». **Attendu** : suggestion « Tranchant (max V) »
    pendant la saisie ; la choisir → niveaux I à V, objets acceptés, « incompatible avec : … ». Choisir III,
    « Ajouter » → puce « Tranchant III ». Taper « châtiment », ajouter → les deux puces signalent
    l'incompatibilité ; retirer Châtiment (×). Taper « sharp » : même suggestion (identifiant).
3.  Nom « Test — Livre 2 », activation décochée → **Créer la récompense**. **Attendu** : bouton grisé « Envoi en
    cours… », bannière « Opération en cours », puis la page se recharge **seule** : carte « Test — Livre 2 »
    « 1 × Livre enchanté (Tranchant III) » et compteur augmenté, **sans** cliquer « Rafraîchir ».
4.  Double clic rapide sur un bouton de création : **une seule** carte créée.
5.  Refus : nouvelle récompense avec un enchantement inconnu tapé à la main (désactiver JavaScript, ou saisir
    `inconnu 2` via un client sans JS) → « Refusé, rien n'a été enregistré » et le formulaire rouvert avec le nom,
    l'objet et la saisie d'enchantements.
6.  Désactivée par défaut pour l'essai ; la désactiver de nouveau si elle a été activée par erreur.

### TC-371 — Atelier : largeur X, longueur Z et hauteur Y indépendantes, emprise rectangulaire (issue #305, PENDING MANUAL VALIDATION)

**Prérequis** : JAR + PlugAdmin #305 déployés ; un compte PlugAdmin avec `AI_USE` ; un emplacement
rectangulaire libre (par ex. 15 × 11) pour l'étape 6. **Aucun appel IA payant n'est nécessaire** pour
les étapes 1, 2, 4 et 7 ; l'étape 3 en demande un (facultatif). **Durée** : 15 minutes.

1.  Atelier → Nouveau projet « Estrade 305 », ouvrir « Taille et nom technique » : trois champs
    **Largeur maximale — X**, **Longueur / profondeur maximale — Z**, **Hauteur maximale — Y**, avec
    l'explication des axes. Saisir 13 / 9 / 7, créer.
2.  Rouvrir la fiche (F5, puis après déconnexion/reconnexion) : « Le bâtiment souhaité » affiche 13, 9, 7.
    Modifier Z à 11, enregistrer, recharger → 13 / 11 / 7. Saisir Z = 3 → refus « Longueur maximale (Z)
    invalide… », rien n'est enregistré. Remettre 9.
3.  (Facultatif, 1 appel IA) « Créer un plan avec l'IA » → dans « Détails techniques », la version porte
    les limites ; la vérification part avec `max_depth = 9` (fiche de l'action `building.plan.audit`).
4.  Sans IA : dans le plan brut d'une version (ou en important un plan), mettre `size.z` à 11 en gardant
    `size.x` ≤ 13, enregistrer la modification → la vérification refuse avec « size.z = 11 pour une
    longueur maximale (Z) de 9 » ; X n'est pas cité. Remettre `size.z` ≤ 9 → le refus disparaît.
5.  Carte « Enregistrer » d'un plan conforme : « Produit : … de large (X) × … de long (Z) × … de haut (Y)
    — limites du projet : X ≤ 13, Z ≤ 9, Y ≤ 7 ». Bibliothèque : dimensions « (largeur × profondeur ×
    hauteur) » cohérentes après enregistrement.
6.  Poser le bâtiment rectangulaire sur un emplacement orienté de façon à imposer 90° puis 270° :
    l'aperçu de pose montre une emprise X/Z **échangée** dans le monde, le contrôle de collisions
    reste actif, aucun bloc coupé ; le projet garde 13 / 9 / 7.
7.  Non-régression : un projet créé avant #305 (ex. « Estrade d'Elias », 13) affiche X = 13 et Z = 13,
    ses versions, sa conception et son historique sont intacts ; aucun bâtiment posé n'a bougé.


### TC-375 — PlugAdmin : créer un monde (issue #562, PENDING MANUAL VALIDATION — création réelle en attente d'accord du propriétaire)

Prérequis : JAR **et** PlugAdmin issus de la PR #562 sur DEV, compte Propriétaire. **Claude n'a créé aucun
monde** et n'a jamais cliqué sur « Créer le monde ». Les étapes 1 à 5 ne créent rien. Les étapes 6 à 9
**créent réellement un monde** : à faire seulement après accord explicite du propriétaire (nom du monde de
test et moment, décision Q4 de l'étude #331 ; le serveur se fige quelques secondes).

1.  Mondes → bouton « Créer un monde » → « Lire les options du serveur ». **Attendu** : formulaire guidé ;
    « Noms déjà pris » liste `world_hub`, `wild`, `claims`, `world`… ; aucun débordement sur téléphone.
2.  Choisir Nether : le bloc Terrain et « Monde plat » disparaissent ; revenir à Monde normal + Plat : le
    bloc « Monde plat » (préréglage, biome, 6 couches) apparaît.
3.  Nom `Wild` → « Vérifier et voir le résumé ». **Attendu** : « Création impossible en l'état » (monde
    essentiel), aucun bouton « Créer le monde ».
4.  Nom `Terres gelées`, Plat, préréglage Neige, structures décochées → Vérifier. **Attendu** : identifiant
    `terres_gelees`, couches 1 × bedrock, 2 × dirt, 1 × snow_block, biome snowy_plains, « Aucun rôle… »,
    avertissement « fige le serveur ». **Ne pas cocher la confirmation** (ou annuler) : `/mv list` en jeu
    ne montre aucun nouveau monde.
5.  Compte Administrateur (sans `WORLD_CREATE`) : pas de bouton « Créer un monde » ; `/worlds/create`
    refusé.
6.  (Accord propriétaire) Monde normal, nom de test choisi par le propriétaire, graine `12345` → Vérifier →
    confirmer → « Créer le monde ». **Attendu** : En cours puis « Prêt : monde créé et relu conforme »,
    contrôles Confirmé (dimension NORMAL, terrain NORMAL, graine 12345, géré par Multiverse, rechargé au
    redémarrage, zone d'apparition non gardée en mémoire). En jeu : `/mv info <nom>`, `/seed` dans le monde.
7.  (Accord propriétaire) Recharger la page résultat et recliquer « Créer le monde » sur le même aperçu :
    « déjà été envoyée », rien en double.
8.  (Accord propriétaire) Monde plat (préréglage Neige ou couches personnalisées + biome) : relecture
    « Couches et biome » Confirmé ; en jeu, vérifier les couches au sol et le biome (F3).
9.  (Accord propriétaire) Redémarrer DEV : les mondes créés sont rechargés (`/mv list`), aucun rôle
    (Hub/Wild/Claims inchangés dans `/worlds`), aucun waypoint généré, aucun portail ; `world_hub`, `wild`,
    `claims` inchangés. Suppression éventuelle du monde de test : hors PlugAdmin, décision du propriétaire.

### TC-374 — PlugAdmin : réglages d'un monde existant, aperçu, confirmation et relecture (issue #561, PENDING MANUAL VALIDATION)

Prérequis : JAR **et** PlugAdmin issus de la PR #561 sur DEV, compte Propriétaire. Claude n'a appliqué
**aucun** réglage sur DEV. Les étapes 5 à 7 **modifient réellement un monde** : c'est le choix du
propriétaire ; choisir un réglage réversible (ex. règle `keep_inventory` de `wild`) et noter la valeur
de départ pour la remettre.

1.  Mondes → fiche `wild` → section **Réglages** → « Lire les réglages réels ». **Attendu** : tableau des
    valeurs réelles (difficulté, PvP, monstres, heure, météo, bordure, point d'arrivée…) avec « Écrit
    par » (`serveur` / `règle de jeu` pour `wild`) ; « Apparition des animaux » et « Mode de jeu » indiqués
    indisponibles avec la raison ; « Règles de jeu du monde » listées (noms 1.21.11) ; encadré
    « Protections LodyQuests » (Wild). Sur téléphone : aucun défilement horizontal.
2.  Même lecture sur `world_hub` : « Écrit par » = Multiverse-Core pour difficulté, PvP, monstres,
    animaux, mode de jeu, arrivée. Comparer 2 ou 3 valeurs avec `/mv info world_hub` en jeu.
3.  Hub → « Modifier des réglages » → Heure du monde = Minuit → « Prévisualiser ». **Attendu** : verdict
    **Refusé** (« Hub : jour et météo permanents… »), aucun bouton Appliquer. Rien n'a changé en jeu.
4.  `wild` → Difficulté = Paisible → Prévisualiser → **Refusé** (mobs spéciaux et boss). Aperçu avec une
    seule valeur choisie : seule cette ligne apparaît (pas de « groupe » implicite).
5.  (Choix du propriétaire) `wild` → Règles de jeu avancées → `keep_inventory` = Oui → Prévisualiser :
    actuel Non → proposé Oui, « règle de jeu ». Cocher la confirmation → « Appliquer et relire ».
    **Attendu** : « Appliqué et confirmé par relecture », ligne Confirmé (Avant Non, Relu après Oui) ; en
    jeu dans `wild` : `/gamerule keep_inventory` = true ; dans `world_hub` : inchangé.
6.  Revenir en arrière (navigateur) et recliquer « Appliquer et relire » sur le même aperçu → « déjà été
    envoyé », rien n'est renvoyé. « Dernières modifications » liste l'application (date, auteur, avant →
    après).
7.  Remettre `keep_inventory` à sa valeur de départ par le même parcours.
8.  Avec un compte Administrateur (sans WORLD_WRITE) : la section montre les valeurs mais aucun
    formulaire de modification.

### TC-373 — PlugAdmin : page « Capacités » et cartes de capacités d'un profil (issue #543, PENDING MANUAL VALIDATION)

Prérequis : JAR **et** PlugAdmin issus de la PR #543 sur DEV. Claude n'a modifié, créé, activé ni fait
apparaître aucun profil ou mob. **Ne pas modifier les profils existants pour ce test** (dont `zz542_*`) :
créer un profil de test jetable, de type **BOSS de quête** (jamais tiré au hasard), puis le supprimer ou le
désactiver.

1.  Menu **Capacités** (sous « Mobs spéciaux & boss »). **Attendu** : 5 capacités (Explosion renforcée,
    Explosif au contact, Division, Enragé, Renforts), chacune avec type, déclenchement, effet sur, profils
    qui l'utilisent. Filtre « Explosion » → 2 ; « Compatible avec » ZOMBIE → 4. Sur téléphone, aucun
    défilement horizontal.
2.  « Voir la fiche » de **Renforts** : réglages avec bornes et défauts, préréglages Doux/Normal/Brutal,
    garanties (aucun bloc, Hub/claims/zones), profils qui l'utilisent.
3.  Mobs spéciaux & boss → **Nouveau profil** `zz543_test`, BOSS, ZOMBIE, chance 0 → dans « Capacités »,
    **Ajouter une capacité** : cocher Enragé et Division. **Attendu** : leurs réglages s'ouvrent, déjà
    remplis (Normal) ; Explosion renforcée indique « Sans effet sur un ZOMBIE ». Créer.
4.  Modifier `zz543_test` : 2 cartes avec une phrase-résumé ; Enragé et Division sont grisées dans
    « Ajouter une capacité » (« Déjà dans ce profil »). Monter Division (↑), décocher « Active » sur
    Enragé, préréglage **Brutal** sur Division, enregistrer. **Attendu** : succès ; « Exporter ce profil »
    montre Division avant Enragé, `enabled: false` sous Enragé, ses valeurs gardées.
5.  Ajouter Renforts sans « Créature invoquée » → refus lisible « Capacité n°3 « Renforts » : … est
    obligatoire », rien n'est écrit. Saisir 2 dans « Seuil de vie » d'Enragé → refus avec la borne.
6.  Cocher « Retirer » sur Division, enregistrer → l'export n'a plus que Enragé.
7.  Non-régression : ouvrir **Modifier** sur un profil existant sans rien changer et **ne pas** enregistrer
    (aucune réécriture demandée par ce test) ; « Modèles, IA et import » fonctionne comme avant.
8.  Supprimer / désactiver `zz543_test`.

### TC-372 — Waypoints : mondes autorisés et interdits à la génération (issue #136, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #136 déployé et serveur redémarré. **Durée** : 10 minutes (15 avec l'étape 5).

1.  Console au démarrage : aucune erreur de configuration ; `config.yml` du serveur contient
    `travel.waypoint.worlds: {}` (ajouté par la complétion automatique).
2.  PlugAdmin → Monde → Mondes : génération automatique de waypoints **active** pour le Wild et le
    Hub, **désactivée** pour Claims et pour tout autre monde.
3.  Non-régression Wild : entrer dans une zone de biome sans waypoint → un waypoint apparaît comme avant.
4.  Non-régression Hub : entrer dans une zone de biome du Hub sans waypoint → waypoint + borne appariée.
5.  (Facultatif, modifie `config.yml` de DEV) mettre `travel.waypoint.worlds: { wild: { enabled: false } }`,
    `/rpgquest reload`, entrer dans une nouvelle zone du Wild → **aucun** nouveau waypoint ; un waypoint
    existant du Wild reste visible et son bouton répond ; `/worlds` affiche « désactivée » pour le Wild.
    Remettre `worlds: {}` puis `/rpgquest reload` → la génération reprend.
### TC-368 — Variables typées : CLAIM_TIER_1 = lasagne refusée partout, valeur hors type signalée (issue #320, PENDING MANUAL VALIDATION)

**Prérequis** : JAR + PlugAdmin #320 déployés ; un joueur d'essai (pas une progression réelle à conserver).
**Durée** : 10 minutes. Aucune quête à rejouer : c'est un outil de debug.

1.  PlugAdmin → Joueurs → fiche → Outils. **Attendu** : « Variable vrai/faux connue du jeu » avec une liste
    (`CLAIM_TIER_1`…) et un sélecteur `true` / `false` ; en dessous, « Autre variable (texte libre) » et la
    liste des types connus.
2.  Formulaire libre : `CLAIM_TIER_1` = `lasagne` → **refus** « « lasagne » n'est pas valide pour
    CLAIM_TIER_1 : true ou false. Rien n'a été écrit. » ; « Lire » rend la valeur antérieure inchangée.
3.  Sélecteur : `CLAIM_TIER_1` = `true` puis `false` → écrit puis relu ; une variable absente reste
    affichée « absente », jamais « false ».
4.  Formulaire libre : `met_lily` = `lasagne` → accepté (variable narrative libre).
5.  Console : `/rpgadmin player variable set <joueur> CLAIM_TIER_1 TRUE` → refus, rien écrit ;
    `/rpgadmin player variable get <joueur> CLAIM_TIER_1` → valeur antérieure.
6.  (Si une valeur hors type existe déjà en base, ex. ancien `lasagne`.) « Lire » ou `/rpgadmin player
    variable get` → la valeur réelle + un avertissement « le jeu la lit comme non débloquée » ; Jo ne propose
    pas l'acte, le portail des claims refuse ; rien n'est corrigé tant qu'on ne réécrit pas `true`/`false`.
7.  PlugAdmin → Dialogues ou Quêtes : une action `SET_VARIABLE` / récompense `VARIABLE` `CLAIM_TIER_1` =
    `lasagne` → erreur de validation, publication impossible.

### TC-370 — PlugAdmin : /dialogues à jour après rattachement / changement / retrait du dialogue d'une fiche PNJ (issue #129, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de la PR #614 sur **DEV** (plugin inchangé). Navigateur seulement ; durée : 5 minutes.
Travailler **uniquement sur un PNJ de test** et deux **dialogues de test** (jamais un PNJ ou dialogue joué) ; noter
l'état initial du PNJ pour le remettre à la fin.

1.  `/dialogues` : repérer les deux dialogues de test A et B, tous deux marqués **« sans PNJ »**.
2.  `/npcs` → PNJ de test → **Modifier** → champ **Dialogue** = A → **Enregistrer**. Ne rien toucher : la page
    se recharge seule (toast « Mise à jour de l'affichage en cours… »).
3.  **Attendu** : la fiche PNJ affiche le dialogue A. Aller sur `/dialogues` **sans F5 ni « Rafraîchir »** :
    A n'est plus « sans PNJ » (le PNJ de test est listé dans sa section PNJ) ; B l'est toujours.
4.  Remplacer A par B sur la fiche → `/dialogues` : A redevient « sans PNJ », B est lié au PNJ de test.
5.  Vider le champ Dialogue → `/dialogues` : B redevient « sans PNJ ». Les diagnostics de `/dialogues/diagnostic`
    et la page Diagnostics suivent les mêmes changements.
6.  Remettre le PNJ de test dans son état initial.

### TC-369 — PlugAdmin : un nœud ajouté apparaît dans le graphe sans F5 (issue #130, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de la PR de #130 sur **DEV** (plugin inchangé). Navigateur seulement ; durée : 3 minutes.
Travailler sur un **dialogue de test** (jamais un dialogue joué), par ex. un squelette créé pour l'occasion.

1.  Dialogues → **Détails techniques** (`/dialogues/diagnostic`) → déplier le dialogue de test → « Afficher le
    graphe — N nœud(s) » → noter N.
2.  « Ajouter un nœud à ce dialogue » : id `test_130`, locuteur quelconque, un texte → **Ajouter le nœud**.
3.  **Attendu** : on reste sur `/dialogues/diagnostic` (pas la liste simple), le dialogue de test est déjà déplié ;
    le toast suit l'action puis « Mise à jour de l'affichage en cours… » ; la page se recharge **seule**.
4.  Après rechargement : le même dialogue est déplié, le graphe ouvert affiche « N+1 nœud(s) », le nœud `test_130`
    apparaît marqué **inaccessible**, le diagnostic « Nœud jamais atteint » est visible. Aucun F5.
5.  Modifier le texte de `test_130` (« Modifier ce nœud ») → même retour sur le dialogue déplié avec le nouveau texte.
6.  Nettoyage : supprimer le dialogue de test (ou le nœud par l'éditeur simple), selon l'usage habituel.

### TC-367 — PlugAdmin : attente visible pendant la mise à jour automatique après une modification (issue #128, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de la PR de #128 sur **DEV** (plugin inchangé). Navigateur seulement ; durée : 3 minutes.
Choisir une modification **réversible** (ex. PNJ → renommer le nom affiché d'un PNJ de test puis remettre l'ancien).

1.  Enregistrer la modification. **Attendu** : le toast en haut à droite dit « En attente de confirmation de l'agent ».
2.  Quand le serveur confirme : le **même** toast affiche le résultat, puis la ligne « Modification enregistrée. Mise à
    jour de l'affichage en cours… » avec un petit sablier tournant. Il ne disparaît pas tout seul ; aucun second toast.
3.  Quelques secondes plus tard, la page se recharge **seule** (pas de F5) et montre la modification ; un toast
    « **Affichage à jour** » apparaît puis se ferme seul. La cloche ne montre pas de relevé technique en plus.
4.  Appuyer sur F5 : aucun toast « Affichage à jour » ne réapparaît.
5.  Téléphone (390 px) : refaire 1 à 3 (remettre l'ancien nom) → même suite de messages, lisible.
6.  (Rare, non provoqué) Si le serveur met plus de 30 s : après rechargement, toast orange « Mise à jour plus longue
    que prévu » avec **Réessayer** ; si le panel ne répond plus : le toast dit « Mise à jour de l'affichage
    interrompue » avec **Réessayer**. Ces deux cas sont couverts par `control-panel/src/test/js/resync-feedback.test.js`.

### TC-366 — Atelier : historique du projet isolé, import d'un plan d'ailleurs explicite (issue #308, PENDING MANUAL VALIDATION)

**Prérequis** : PlugAdmin #308 déployé (aucun JAR, aucune migration). Compte avec `AI_USE`. **Aucun
appel à l'IA** dans ce test ; **ne pas** cliquer « Enregistrer dans la bibliothèque ». **Durée** : 5 minutes.

1.  Bâtiments → Atelier → ouvrir **Estrade d'Elias** (`hub_estrade_elias`, 9 versions sur DEV au 19/10).
    Déplier **« Historique de ce projet »** → seulement ses versions, aucune `bibliotheque_du_hub`.
    (Optionnel) un projet tout juste créé affiche « Aucune version de plan pour ce projet : il est neuf. »
2.  La section **« Importer un plan d'un autre projet ou de l'historique global »** est repliée ; la
    déplier : `bibliotheque_du_hub` y figure, avec « Examiner avant import ». Taper `biblio` →
    **Rechercher** : la liste se restreint.
3.  **Examiner avant import** : la provenance, les dimensions, l'entrée et les incompatibilités avec les
    limites de l'Estrade s'affichent ; recharger la page → toujours aucune version dans l'historique.
4.  (Optionnel, crée une version dans l'Estrade) **Importer comme nouvelle version de ce projet** → la
    copie s'ouvre ; le brief de l'Estrade est inchangé ; le projet d'origine (s'il y en a un) garde ses
    versions. Si la copie n'est pas souhaitée : Détails techniques → « Retirer cette version de la liste ».
5.  Ouvrir **Maison de Lily** : son historique ne montre que ses propres versions.

### TC-365 — Recherche d'objet et de bloc dans l'éditeur de quêtes : « pump » → PUMPKIN, carottes plantées (issue #142, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de la PR de #142 sur **DEV** (plugin inchangé). Navigateur seulement, rien n'est
enregistré si l'on n'appuie pas sur « Enregistrer » ; durée : 3 minutes.

1.  `/quests` : le bouton **« Objets Minecraft »** doit être gris (catalogue déjà relevé). S'il est orange, cliquer
    dessus une fois.
2.  `/quests/new` → champ **Icône** : taper `Pump`, puis `pumpk`, puis `pu`. **Attendu** : `PUMPKIN` proposé à
    chaque fois (avec `CARVED_PUMPKIN`, `PUMPKIN_PIE`…), quelle que soit la casse.
3.  Ajouter un objectif **Collecter un objet** → champ **Objet** : taper `pu` → `PUMPKIN` proposé. Choisir
    `PUMPKIN`, cliquer **Vérifier** : **aucun** avertissement « hors des valeurs connues ».
4.  Changer l'objectif en **Casser des blocs** → champ **Bloc** : taper `carot` → `CARROTS` (carottes plantées)
    proposé **sans** la mention « aucune forme d'objet ». Choisir `CARROTS`, **Vérifier** : aucune erreur.
5.  Contre-épreuve : revenir sur **Collecter un objet** et saisir `CARROTS` → **Vérifier** : erreur expliquée
    « existe comme bloc mais n'a aucune forme d'objet » (on ne ramasse que `CARROT`).
6.  Icône `spi` : seuls des objets (`SPIDER_EYE`…) apparaissent, aucune entité (`SPIDER`, `CAVE_SPIDER`).
7.  Téléphone (390 px) : la liste s'affiche comme avant.
8.  En jeu (plus tard) : une quête « Casser 3 CARROTS » progresse en cassant des carottes plantées.

### TC-364 — Boss communautaires : « Près de » propose un joueur connecté dans le Wild (issue #169, retour du 19/10, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin issu de la PR #582 sur **DEV** (plugin inchangé). Les étapes 1–3 ne font que lire :
aucune apparition, aucun nettoyage.

1.  Se connecter en jeu et aller dans le **Wild**. PlugAdmin → **Mobs spéciaux & boss → Boss communautaires** →
    sous « Apparition de test », **Actualiser les joueurs connectés**. **Attendu** : la liste « Près de » propose
    ton pseudo en plus de « N'importe où dans le Wild », et la phrase sous le champ dit « 1 joueur(s) dans le Wild
    (monde « wild ») au relevé du … ».
2.  Revenir au Hub, **Actualiser les joueurs connectés**. **Attendu** : ton pseudo n'est plus proposé ; la phrase
    dit « Aucun joueur connecté dans le Wild » et « Hors du Wild, donc non proposés : <pseudo> (world_hub) ».
3.  La page **Joueurs** et ce sélecteur montrent le même état (même relevé : « Actualiser » sur l'une met l'autre
    à jour).
4.  Reprise du test **#177** (distribution des récompenses), **directement à l'étape d'apparition ciblée** : de
    retour dans le Wild, choisir ton pseudo dans « Près de » → **Apparition de test**. Les contrôles #177 déjà
    réussis ne sont pas à refaire.

### TC-363 — Page Portails en lecture seule : hub_to_wild, hub_to_claims, gardes, diagnostics (issue #564, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de la PR de #564 sur **DEV**. Aucune action de cette page ne modifie un
portail ni ne téléporte : on peut tout essayer.

1.  PlugAdmin → **Monde → Portails** → **Rafraîchir les portails**. **Attendu** : 2 cartes, `hub_to_claims` et
    `hub_to_wild`, « Actif », 0 problème, aucune carte « Ancien format » (aucun portail de zone sur DEV).
2.  `hub_to_wild` : départ `world_hub` 737, 66, -673 → 740, 69, -673 ; arrivée `wild` · point sûr aléatoire ;
    pastille **Avertissement Wild**. `hub_to_claims` : 730, 66, -676 → 732, 69, -676 ; `claims` · claim du joueur,
    sinon point sûr ; pastille **Accès Claims + retour**.
3.  Fiche `hub_to_wild` : rayon 500–5000 et 20 tentatives (ou les valeurs de `travel.random-safe-arrival` de DEV),
    textes de l'avertissement identiques à la fenêtre vue en jeu en entrant dans le portail ; le lien `wild`
    ouvre la fiche du monde dans **Mondes**.
4.  Fiche `hub_to_claims` : règles CLAIM_TIER_1 / claim principal, Pierre de retour, OP sans effet hors bypass.
5.  Non-régression en jeu : traverser `hub_to_wild` (avertissement, puis Wild) et `hub_to_claims` (refus sans
    palier, ou arrivée sur son claim) — comportement identique à avant.
6.  Vérifier qu'aucun bouton ne crée, ne modifie, n'active ni ne téléporte (carte et fiche) ; téléphone (390 px)
    lisible, sans défilement horizontal de la page.

### TC-363-bis — Retours sur la page Portails : guide nommé, conditions clarifiées, résultats vides (issue #564, PENDING MANUAL VALIDATION)

**Prérequis** : JAR + PlugAdmin `67a556c` (PR #591) sur DEV. **Revalidation CIBLÉE** : ces 3 points seulement ;
les acquis de TC-363 (liste, fiches, rafraîchissement, recherche « wild », filtre Désactivés, mobile) ne sont pas
à refaire. **Durée** : 3 minutes, PlugAdmin seul, aucune action en jeu.

1.  PlugAdmin → **Monde → Portails** → **Rafraîchir les portails** → fiche `hub_to_claims`, bloc **3 · Règles
    appliquées par le serveur**. **Attendu** : « … un message l'envoie vers Jo (acte de propriété) et vers
    **Elias** (PNJ guide « guide ») » — plus de « le Guide ». (Si le guide est renommé dans PNJ, le nouveau nom
    apparaît après Rafraîchir.)
2.  Même fiche, bloc **2 · Téléportation → Déclenchement**, dernière ligne. **Attendu** : « Aucune condition de
    niveau, de quête ni de permission n'est configurée sur le portail lui-même ; les règles du serveur (bloc
    « Règles appliquées par le serveur ») s'appliquent quand même. » Idem sur `hub_to_wild`.
3.  Retour à la liste : filtre **Désactivés · 0** → **Attendu** « Aucun portail désactivé. » ; puis taper
    `zzz` dans la recherche avec le filtre **Tous** → « Aucun portail ne correspond à « zzz ». » ; effacer la
    recherche → les 2 cartes reviennent, le message disparaît.

### TC-362 — Page Mondes en lecture seule : six mondes, gestionnaires, rôles, éléments liés (issue #560, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de la PR de #560 sur **DEV**. Aucune action de cette page ne modifie un
monde : on peut tout essayer.

1.  PlugAdmin → **Monde → Mondes** → **Rafraîchir les mondes**. **Attendu** : 6 cartes — `world`, `world_hub`,
    `wild`, `claims`, `world_nether`, `world_the_end` — toutes « Chargé ».
2.  `wild` : rôle **Wild**, « géré par RPGQuest (worlds.yml) », badge **Essentiel**, « arrivée de hub_to_wild ».
    `world_hub` : rôle **Hub**, « géré par Multiverse », « départ de 2 portail(s) ». `claims` : rôle **Claims**.
    `world` : « Aucun rôle », « géré par le serveur (monde principal) », **Essentiel**. Nether / End : aucun
    rôle, pas de badge.
3.  Ouvrir la fiche de `world_hub` : graine, structures, difficulté, PvP, point d'arrivée ; éléments liés
    `hub_to_wild` (point sûr aléatoire) et `hub_to_claims` (claim du joueur), emplacements de bâtiment et
    emplacements PNJ de DEV. Comparer la graine avec `/seed` en jeu dans `world_hub`.
4.  Se placer dans `world_hub` en jeu, Rafraîchir : « Joueurs 1 » sur la carte du Hub.
5.  Vérifier qu'aucun bouton ne crée, ne charge, ne décharge ni ne modifie un monde (carte et fiche).
6.  Téléphone (390 px) : liste et fiche lisibles, pas de défilement horizontal de la page.

### TC-362-bis — Revalidation ciblée : noms de mobs lisibles et compteur de joueurs (issue #560, retours du 19/10, PENDING MANUAL VALIDATION)

Prérequis : JAR **et** PlugAdmin issus de la PR `fix/560-mondes-noms-joueurs` sur **DEV**. Déjà acquis au 19/10
(ne pas refaire) : liste, recherche, filtre par rôle, fiches Wild/Hub/Nether, rafraîchissement, graine Wild =
`/seed`, fiche Hub sur téléphone.

1.  PlugAdmin → **Monde → Mondes** → **Rafraîchir les mondes** → fiche `wild` → éléments liés, ligne des mobs.
    **Attendu** : noms lisibles (« Zombie d'entraînement »…), **aucune** balise `<dark_green>`, `<bold>`… visible.
2.  Se connecter en jeu et se placer dans le **Wild**, puis **Rafraîchir les mondes**. **Attendu** : sous
    « Relevé du serveur », « 1 joueur(s) connecté(s) sur le serveur à ce relevé » **et** la carte `wild` affiche
    « Joueurs 1 », sans ligne « attention ».
3.  Si la carte `wild` affiche 0 alors que vous êtes dans le Wild : noter l'heure, la ligne affichée (avec ou
    sans « attention ») et l'état de la page **Joueurs** rafraîchie dans la même minute, et le signaler dans #560.

### TC-361-ter — Compteurs « en jeu » : zone chargée, zone non chargée, introuvables (issue #542, retour du 19/10, PENDING MANUAL VALIDATION)

Contexte : le 19/10, `zz542_cochon` affichait « 1 en jeu » alors que le résumé et l'encadré « Instances
de test » disaient 0. Les deux compteurs n'avaient pas le même périmètre (repérés depuis le démarrage,
zones déchargées comprises / instances de test en zone chargée) et rien ne le disait. Prérequis : JAR +
PlugAdmin du correctif `fix/542-compteurs-instances` sur **DEV**. **Revalidation ciblée des compteurs
seulement** ; ne pas refaire TC-361-bis ni TC-361. Ne pas réactiver `zz542_*`, ne pas toucher au boss
communautaire réel.

1.  PlugAdmin → Mobs spéciaux & boss → **Rafraîchir**. **Attendu** : chaque fiche affiche « X en zone
    chargée », suivi s'il y a lieu de « · Y en zone non chargée » / « · Z introuvable(s) » ; le résumé
    « instances de test » est égal à la somme des « dont T de test ». Après le redémarrage du déploiement,
    une créature dont la zone n'a pas été rechargée n'est comptée nulle part (normal, expliqué dans
    « Comment lire les compteurs »).
2.  En jeu, dans le Wild, aller là où se trouvent des mobs spéciaux (ou attendre une apparition naturelle),
    **Rafraîchir** : ils apparaissent « en zone chargée ». Partir loin (ou au Hub) jusqu'au déchargement de
    la zone, **Rafraîchir** : **Attendu** : « en zone non chargée » et, dans la fiche, « Hors zone
    chargée » avec UUID, monde, position et date.
3.  Si une fiche affiche « introuvable(s) » : le signaler (copier la fiche) — ne rien nettoyer.
4.  Encadré « Instances de test » : s'il reste une instance de test en zone non chargée, la phrase le dit
    (« hors de portée du nettoyage, position dans la fiche du profil »).

### TC-361-bis — Revalidation ciblée : poursuite créatif → survie, apparition en survie, explosion sans bloc détruit (issue #542, retours du 19/10, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR du correctif `fix/542-poursuite-explosion-sans-blocs` sur **DEV** ; PlugAdmin
inchangé. Déjà acquis au 19/10 (ne pas refaire) : `zz542_cochon` passif en créatif, explosion au contact en
survie, division bornée et rage. Claude n'a modifié ni `zz542_cochon` ni aucun profil, ni restauré les
4 blocs de sable détruits lors du premier essai (positions et état d'origine inconnus).

1.  Dans le Wild, sur du sable ou de la terre, loin de toute construction, **en créatif** : « Tester »
    `zz542_cochon`, s'éloigner de 6 à 10 blocs. **Attendu** : le cochon ne vous suit pas.
2.  Sur la **même instance**, passer en **survie** (`/gamemode survival`), rester immobile à 6–10 blocs.
    **Attendu** : dans les 1 à 2 secondes, le cochon marche vers vous d'un pas net (≈ vitesse d'un zombie),
    puis explose à 2 blocs.
3.  Après l'explosion : **aucun bloc détruit** (pas de cratère, sable intact) ; vous subissez des dégâts ;
    pas de feu.
4.  Déjà en survie : « Tester » à nouveau `zz542_cochon` à 6–10 blocs. **Attendu** : poursuite immédiate
    sans changer de mode, puis explosion sans bloc détruit.
5.  Nettoyer les instances de test.

### TC-361 — Socle des capacités de mobs : profils inchangés, capacités identiques, sécurité, doublons refusés (issue #542, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR #575 (`e4f7432`) sur **DEV** ; PlugAdmin inchangé. Claude n'a modifié aucun
profil, activé aucun spawn, lancé aucun boss. **À savoir** : sur DEV, aucun profil n'a aujourd'hui de
capacité (les copies DEV de Cochon Creeper, Creeper Rouge, Creeper Doré et Zombie Fissible les ont perdues
avant ce lot) ; les étapes 3 et 4 passent donc par deux profils de test `zz542_*`, de type **BOSS de quête**
(jamais tirés au hasard).

1.  PlugAdmin → Mobs spéciaux & boss → **Rafraîchir**. **Attendu** : les 8 mêmes profils, aucun « Profils
    rejetés » (vérifié par Claude après redémarrage : 8 profils, 0 problème).
2.  Importer ce profil (Modèles, IA et import → coller) :
    ```yaml
    id: zz542_fissible
    category: BOSS
    boss-mode: QUEST
    entity-type: ZOMBIE
    name: "Test 542 fissible"
    spawn-chance: 0.0
    worlds: []
    biomes: []
    zones: []
    health: 40
    abilities:
      - type: SPLIT_ON_HIT
        max-depth: 1
        max-children-per-hit: 2
      - type: ENRAGED
        health-fraction: 0.3
        speed-multiplier: 1.3
        damage-multiplier: 1.5
    ```
    **Attendu** : import accepté ; **Exporter** redonne exactement ces capacités, sans ligne `enabled`,
    `chance` ni `cooldown-seconds` ajoutée.
3.  Dans le Wild, **en survie** : « Tester » `zz542_fissible`, le frapper → 2 copies au premier coup, plus de
    nouvelles copies ensuite (plafond 2) ; sous 30 % de vie → particules + rugissement, plus rapide.
4.  Importer `zz542_cochon` : même en-tête, `entity-type: PIG`, `name: "Test 542 cochon"` et
    ```yaml
    abilities:
      - type: EXPLOSIVE_ON_ATTACK
        power: 1.0
        set-fire: false
        trigger-range-blocks: 2.0
    ```
    « Tester » : **en créatif**, le cochon ne vous poursuit pas et n'explose pas ; **en survie**, il vous
    poursuit et explose (puissance faible, loin de toute construction).
5.  Doublon refusé : importer un profil `zz542_doublon` avec **deux** entrées `- type: ENRAGED`. **Attendu** :
    refus « Capacité « Enragé » (ENRAGED) en double : une seule par profil. », rien n'est écrit.
6.  Après le test : nettoyer les instances de test et **désactiver** `zz542_fissible` et `zz542_cochon`.

### TC-360 — Page /mobs en trois vues, compteurs, nettoyage confirmé, refus dans l'historique (issue #169, retour du 18/10, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de la PR #557 sur **DEV**. N'active rien, ne fait apparaître aucun
boss. Déjà acquis (ne pas refaire) : Zombie Runner présent, nom coloré, attaque, vitesse accrue.

1.  PlugAdmin → Mobs spéciaux & boss → **Rafraîchir**. **Attendu** : résumé compact en haut (profils,
    tirage du Wild, instances de test, boss communautaires actifs), onglets Profils / Boss communautaires /
    Apparitions des mobs spéciaux, catalogue et recherche tout de suite visibles ; **plus de bandeau
    « Profils rejetés » citant `community-bosses.yml`**.
2.  Fiche Zombie Runner : « Modifier » mis en avant, Exporter / Modifier avec une IA / Désactiver en
    secondaire, bloc « Tester « Zombie Runner » » séparé. En-tête : « N en zone chargée (dont X de test) »
    (#542 ; « N en jeu » avec un plugin antérieur).
3.  Fiche Zombie d'entraînement : « 0 en zone chargée · 1 boss communautaire actif (zone non chargée) » tant que sa
    zone n'a pas été rechargée ; « Comment lire les compteurs » l'explique.
4.  Zone rouge « Instances de test » : nombre et profils concernés affichés **avant** clic ; le bouton ne part
    pas sans la case. (Le nettoyage lui-même n'est à lancer que si tu le souhaites : il ne supprime que les
    instances de test chargées.)
5.  Historique → **Refusées avant envoi** : une demande refusée (ex. ancienne version sans case) apparaît avec
    date, action, motif et acteur, distincte des actions exécutées. Recherche et bouton alignés.
6.  Onglets Boss communautaires et Apparitions : réglages et explications repliés, lien « Récompenses »,
    aucune mention de fichier YAML. Vérifier aussi sur téléphone (390 px).

### TC-332 — Modifier un boss à plusieurs biomes depuis PlugAdmin (issue #461, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de #461 sur **DEV**. Ne lance aucun boss.

1.  PlugAdmin → Mobs spéciaux & boss → **Diag Boss** : ouvrir la fiche, sans rien changer, **Enregistrer**.
    **Attendu** : action réussie, aucun avertissement « ne se recharge pas » ; la fiche montre toujours
    les deux biomes **swamp** et **plains**, le type Boss communautaire et « Profil actif ».
2.  (Facultatif) Retirer **plains**, enregistrer, le remettre, enregistrer. **Attendu** : la liste suit,
    jamais d'erreur YAML ; les autres profils sont inchangés.

### TC-340 — Modifier un dialogue existant avec une IA, réimport avec diff (issue #465, PENDING MANUAL VALIDATION)

**Préparation** : sur PlugAdmin (DEV), dupliquer un dialogue en copie jetable `zz465_*` (jamais le vrai
`encounter_bandit`), puis l'ouvrir dans Dialogues. « Actualiser » les dialogues et les catalogues.

1.  **Modifier avec une IA…** **Attendu** : prompt avec le dialogue enregistré, quêtes citées (titres),
    listes de quêtes du PNJ, conditions/effets ; « Télécharger le pack seul » donne un `.yaml`.
2.  Coller le prompt dans une IA externe, demander « passer à une quête au hasard dans la liste … ».
    Importer le YAML rendu dans Import contenu. **Attendu** : « Changements dans le dialogue » lisible
    sur téléphone (avant/après), conditions retirées et effet `OFFER_CATALOG_QUEST` ajouté listés ;
    collision « Remplacer l'existant / Garder l'existant » ; rien d'écrit avant confirmation.
3.  Changer l'`id` dans le YAML puis réimporter. **Attendu** : refus « contenu parallèle ».
4.  Modifier puis enregistrer le dialogue dans l'éditeur, puis réimporter l'ancien YAML. **Attendu** :
    refus « a changé depuis l'export ».
5.  Remplacer + confirmer. **Attendu** : le dialogue de PlugAdmin est remplacé, rien n'est en jeu ;
    « Mettre en jeu » depuis le dialogue, puis le tester en jeu (0, 1 ou plusieurs quêtes dans la liste,
    pas de double démarrage). Retirer ensuite la copie `zz465_*` (PlugAdmin et DEV).

### TC-341 — Profil de boss par modèle, IA et import (issue #214, PENDING MANUAL VALIDATION)

**Préparation** : PlugAdmin (DEV) → Mobs spéciaux & boss → « Rafraîchir », « Catalogues Minecraft »,
« Récompenses et tirages ». Ne jamais toucher `rpgquest:claude_diag_boss`.

1.  « Nouveau profil » : **Attendu** les liens « Modèle … » et « Importer un fichier / IA ». Télécharger le
    modèle « Boss communautaire » : fichier commenté en français, toutes les capacités en exemple.
2.  « Modèles, IA et import de fichier » → « Boss communautaire » : copier le prompt dans une IA, demander
    « Crée un boss zombie d'entraînement facile : id boss_zombie_entrainement, nom Zombie d'entraînement,
    20 PV, 2 dégâts, vitesse 0,20, monde wild, aucune capacité, aucune récompense ».
3.  Coller la réponse dans l'étape 3, « Vérifier ». **Attendu** (correctif du 19/10) : le bouton se grise
    (« Envoi en cours… »), « Vérification en cours » puis, **sans recharger ni cliquer**, le résultat
    s'affiche seul en quelques secondes ; un double clic ne crée qu'une vérification. Puis « Nouveau profil »,
    fichier exact affiché, aucune erreur ; « Créer ce profil » → toast réussi, profil dans la liste, **aucun boss apparu**.
4.  Réimporter le même fichier avec `health: 40`. **Attendu** : différences `health 20.0 → 40.0`, case
    « remplacer » obligatoire ; « Annuler » ne change rien ; confirmer remplace (sauvegarde `mob-backups/`).
5.  Ajouter `fly-speed: 3` au fichier. **Attendu** : refus lisible « Propriété inconnue », rien d'écrit.
6.  « Générer aléatoirement » ×2 : deux brouillons différents, rien de créé tant que non confirmé.
7.  Apparition manuelle (propriétaire seulement) : « Faire apparaître un boss communautaire de test ».

### TC-342 — Prix et coûts depuis PlugAdmin : vente, rachat, fabrication (issue #336, PENDING MANUAL VALIDATION)

**Préparation** : PlugAdmin (DEV) → « Prix et coûts » → « Rafraîchir ». Compte avec « Économie — écriture ».
En jeu, un compte administrateur avec quelques LodyCoins (`/money`), un inventaire avec de la place.

1.  Page sans prix : **Attendu** « Aucun prix défini sur ce serveur », tableau Marchands avec
    `rpgquest:atelier_demo` et « Indisponible en jeu : demo_vente, demo_rachat, demo_fabrication ».
2.  En jeu `/merchant open rpgquest:atelier_demo` : **Attendu** trois barrières « Offre indisponible » ; un clic
    ne débite rien.
3.  « Nouveau prix » : identifiant `demo_vente`, « Le PNJ vend », BREAD × 4, pièces **vides** →
    **Attendu** refus « aucun prix n'est jamais mis par défaut ». Puis 3 pièces → créé, badge
    « démonstration », « Cité par : marchand « Atelier de démonstration » ».
4.  `demo_rachat` : « Le PNJ rachète », BREAD × 1 pour 1 pièce → **Attendu** refus « Boucle
    d'enrichissement » (racheté plus cher que vendu), rien d'écrit. Puis COBBLESTONE × 8 pour 1 pièce → créé.
5.  `demo_fabrication` : « Le PNJ fabrique », IRON_SWORD × 1, 5 pièces, ingrédients IRON_INGOT × 2 et
    STICK × 1 → créé.
6.  En jeu, rouvrir la vitrine : pain (3 pièces), rachat de pierres, épée (ingrédients + coût affichés).
    Acheter le pain : −3, +4 pains. Vendre 8 pierres : +1, −8 pierres.
7.  Fabrication sans les ingrédients : **Attendu** « Il te manque des ingrédients », solde inchangé. Avec
    2 lingots + 1 bâton : −5 pièces, lingots et bâton retirés, +1 épée. Double-clic rapide avec les
    ingrédients pour une seule épée : **Attendu** une seule épée, une seule fabrication payée (le second
    débit est remboursé, message « Fabrication annulée et remboursée »).
8.  Inventaire plein (aucune case libérée par les ingrédients) : **Attendu** « Ton inventaire est plein »,
    rien de débité ni de retiré.
9.  Vitrine ouverte, modifier dans PlugAdmin le prix du pain (4 pièces), puis cliquer le pain : **Attendu**
    « Les prix viennent de changer », rien d'échangé, vitrine réaffichée avec 4 ; un second clic achète à 4.
10. « Désactiver » `demo_vente` : **Attendu** le message cite l'atelier ; en jeu, barrière « Offre
    indisponible ». « Dupliquer » : copie désactivée.

**Reset** : désactiver les trois prix depuis PlugAdmin (aucune suppression ; aucun solde à remettre au-delà
des essais faits sur son propre compte).


### TC-343 — Rôle métier affiché d'un PNJ en saisie libre (issue #127, PENDING MANUAL VALIDATION)

**Préparation** : PlugAdmin (DEV) → « PNJ » → « Rafraîchir ». Compte avec « PNJ — écriture ». Choisir un
PNJ de test qui a une définition (pas un PNJ joué par le propriétaire en ce moment).

1.  Ouvrir sa fiche → « Informations avancées » → « Réglages avancés ». **Attendu** : champ texte
    « Rôle affiché » (plus de liste « Aucun / Donneur de quête »), suggestions au clic (Cartographe,
    Forgeron, Téléporteur…), phrase « Libellé seulement : … aucun pouvoir ».
2.  Saisir « Cartographe », Enregistrer, puis « Rafraîchir » la liste. **Attendu** : « Cartographe » en
    italique à côté du nom dans la liste et ligne « Rôle : Cartographe » dans le résumé de la fiche ; la
    recherche « cartographe » trouve le PNJ.
3.  Saisir un rôle hors suggestions, « Maître d'armes ». **Attendu** : accepté, affiché « Maitre d armes »
    (enregistré `maitre_d_armes`).
4.  Saisir « ??? ». **Attendu** : refus « Rôle invalide », rien d'enregistré.
5.  En jeu : le PNJ propose exactement les mêmes quêtes et le même dialogue qu'avant (le rôle n'a aucun
    effet).
6.  Un PNJ existant en `role: quest_giver` s'affiche « Donneur de quête », sans erreur.

**Reset** : remettre le rôle d'origine (ou vider le champ) depuis les Réglages avancés.

### TC-346 — Pièces et niveau RPG à l'écran : panneau `/hud` et retours de gains (issue #184, PENDING MANUAL VALIDATION)

**Préparation** : DEV, un compte joueur avec une quête suivie (bossbar visible) et quelques LodyCoins ;
résolution et interface habituelles, sans resource pack.

1.  Sans rien taper : **Attendu** aucun panneau à droite (défaut `hud.default-mode: "off"`).
2.  `/hud` : **Attendu** panneau « LodyQuests » avec `Bourse : N LodyCoins` (= `/money`) et
    `Niveau RPG : N` (= niveau Global de `/skills`), sans chiffres rouges à droite. La bossbar de quête et
    la liste de discussion restent lisibles.
3.  Acheter / vendre chez un marchand, ou `/money admin give <toi> 5` : **Attendu** dans l'action bar
    `+5 LodyCoins · solde S` (ou `-N …`) et le panneau mis à jour aussitôt. Achat refusé (fonds
    insuffisants) : aucun message `-N`.
4.  Tuer un mob de quête : la progression de l'objectif reste lisible dans l'action bar (le retour de
    pièces s'ajoute, ne l'efface pas).
5.  Se déconnecter / reconnecter : **Attendu** panneau toujours affiché (choix mémorisé). `/hud off` :
    panneau retiré, les retours `+N` restent.
6.  Gagner un niveau Global (`/skills admin grant <toi> combat 500`) : **Attendu** `Niveau RPG` mis à jour.
7.  Donner un avis : lisibilité, emplacement, faut-il le panneau par défaut, autre surface ?

**Reset** : `/hud off` ; reprendre les pièces données au point 3 si besoin (`/money admin take`).

### TC-355 — Salles de préparation du Hub : coffre → craft → Wild (issue #107, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR #107 sur **DEV**. Un compte constructeur du Hub (OP ou
`rpgquest.build.hub.world_hub`) et un compte joueur de test `<joueur>` **sans** droit de construction
ni mode bypass. Un seau d'eau, un seau de lave et un briquet dans l'inventaire de `<joueur>`.

1.  Avec le constructeur, dans `world_hub` : poser un coffre **vide** et deux établis côte à côte, puis
    `/rpgadmin vault bind vault_01` sur le coffre. Ajouter un panneau « Coffre personnel ».
2.  Avec `<joueur>` : clic droit sur le coffre. **Attendu** : « Coffre personnel vault_01 » ; y prendre
    ou déposer un objet.
3.  Clic droit sur un établi. **Attendu** : la grille de craft vanilla 3×3 s'ouvre ; fabriquer une
    pioche en bois.
4.  Essayer de casser l'établi. **Attendu** : rien ne se casse. Vider le seau de lave puis le seau
    d'eau à côté de l'établi. **Attendu** : refusé, seaux toujours pleins. Briquet sur le sol à côté de
    l'établi. **Attendu** : aucun feu.
5.  Si une zone protégée couvre la salle (`/rpgadmin zone info`) : refaire l'étape 3. **Attendu** :
    l'établi s'ouvre toujours.
6.  Rejoindre le portail Wild. **Attendu** : téléportation normale ; l'objet du coffre est toujours
    dans `vault_01` au retour.
7.  Donner un avis : la salle se comprend-elle sans explication ? Faut-il plus de signalétique ?

**Reset** : avec le constructeur, `/rpgadmin vault unbind` sur le coffre de test puis casser coffre,
établis et panneau (ou les garder s'ils deviennent la vraie salle).

### TC-354 — Capacité progressive des coffres personnels (issue #106, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR #106 sur **DEV**. Un compte admin (OP, `rpgquest.admin.world` +
`rpgquest.admin.debug`) et un compte joueur de test `<joueur>`. Deux coffres **vides** posés dans le Hub
(ils figurent deux Hubs), plus un troisième.

1.  Vérifier `config.yml` sur DEV : section `personal-vaults` présente (`starter-vaults: [vault_01]`,
    `default-slots: 27`).
2.  Lier les deux premiers coffres à `vault_02` (`/rpgadmin vault bind vault_02`) et le troisième à
    `vault_01`. Avec `<joueur>`, clic droit sur un coffre `vault_02`. **Attendu** : aucune fenêtre, message
    « Ce coffre est encore verrouillé… progression dans LodyQuests » ; le coffre `vault_01` s'ouvre (27 cases).
3.  `/rpgadmin player variable set <joueur> VAULT_UNLOCK_VAULT_02 true`. Clic droit sur le premier coffre
    `vault_02`. **Attendu** : « Coffre personnel vault_02 » s'ouvre. Y déposer un objet nommé, fermer.
    Le second coffre `vault_02` (autre « Hub ») montre le même objet.
4.  Déconnexion / reconnexion de `<joueur>`, puis redémarrage DEV. **Attendu** : `vault_02` toujours ouvert,
    objet présent.
5.  `/rpgadmin player variable set <joueur> VAULT_UNLOCK_VAULT_02 false`. **Attendu** : `vault_02` refusé
    (message de verrouillage). Remettre `true` : **Attendu** : l'objet est toujours là.
6.  `/rpgadmin player variable set <joueur> VAULT_SLOTS_VAULT_01 54`, rouvrir `vault_01`. **Attendu** :
    54 cases. Poser un objet dans la dernière rangée, fermer, remettre `27` puis rouvrir. **Attendu** :
    la rangée occupée reste visible (45 cases), l'objet est intact ; une fois vidée, le coffre revient à 27.
7.  `/rpgadmin player variable set <joueur> VAULT_SLOTS_VAULT_01 40`. **Attendu** : refusé ou sans effet
    (taille inchangée).

**Reset** : remettre `VAULT_UNLOCK_VAULT_02 false` et `VAULT_SLOTS_VAULT_01 27` (ou laisser : compte de
test), `/rpgadmin vault unbind` sur les coffres de test puis les casser. Les contenus de test restent
dans les coffres du compte de test, sans effet de bord.

### TC-353 — Coffres physiques des Hubs liés aux coffres personnels (issue #105, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR #105 sur **DEV** (schéma V44). Un compte admin (OP, ombrelle
`rpgquest.admin.world`) et, pour l'étape 4, un second compte joueur. Poser deux coffres **vides** dans
le Hub, éloignés l'un de l'autre (ils figurent les salles de deux Hubs).

1.  Viser le premier coffre : `/rpgadmin vault bind`. **Attendu** : « Coffre lié à vault_01 » avec la
    position. Idem sur le second coffre. `/rpgadmin vault list` : **Attendu** deux lignes `vault_01`.
2.  Clic droit sur le premier coffre. **Attendu** : fenêtre « Coffre personnel vault_01 » (27 cases),
    vide la première fois. Y déposer quelques objets (dont un objet nommé/enchanté), fermer.
3.  Clic droit sur le second coffre. **Attendu** : exactement les mêmes objets, mêmes cases.
4.  Second joueur sur le même coffre (en même temps que le premier si possible). **Attendu** : son
    propre coffre, vide ; il ne voit jamais les objets du premier.
5.  Essayer de casser un coffre lié (même en OP, même en créatif). **Attendu** : refusé avec le
    message « Délie-le d'abord ». Essayer de poser un coffre accolé : refusé.
6.  Ouvrir un coffre **non** lié du Hub (si le droit de construire le permet) et un Ender Chest
    vanilla. **Attendu** : comportement vanilla inchangé.
7.  Déplacer : lier un troisième coffre vide, puis `/rpgadmin vault unbind` sur le premier. **Attendu** :
    le troisième coffre montre le contenu de l'étape 2 ; le premier redevient un coffre vanilla vide.
8.  Se déconnecter / reconnecter, puis redémarrer DEV. **Attendu** : liaisons et contenu conservés.
9.  Refus : `/rpgadmin vault bind` sur un coffre contenant un objet → refusé (« vide-le d'abord ») ;
    dans le Wild → refusé.

**Reset** : `/rpgadmin vault unbind` sur chaque coffre de test (le contenu des comptes de test reste
dans leur `vault_01`, sans effet de bord), puis casser les coffres posés.

### TC-352 — Socle des coffres personnels : démarrage en schéma V43 (issue #104, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR #104 sur **DEV**. Rien n'est ouvrable en jeu à ce stade (pas de
commande, pas de bloc) : l'ouverture d'un coffre personnel se testera avec #105. **Aucun reset.**

1.  Redémarrer DEV. **Attendu** : démarrage sans erreur ni avertissement lié à `personal_vaults` ;
    la console indique le schéma à jour (V43).
2.  Se connecter, ouvrir un Ender Chest vanilla. **Attendu** : comportement vanilla inchangé, contenu
    habituel (les coffres personnels LodyQuests sont un système séparé).
3.  `/backpack` (si accès). **Attendu** : sac à dos inchangé, contenu intact.

### TC-351 — Kit de départ déjà reçu expliqué dans la fenêtre d'Elias (issue #475, 3e retour du 17/10, PENDING MANUAL VALIDATION)

Prérequis : `dialogues/guide.yml` du correctif #475 « kit déjà reçu » republié sur **DEV** (voir le
rapport de session). Compte **LoDyMcFly tel quel**, kit de départ déjà reçu depuis la dernière mort.
**Aucun reset.** Acquis de TC-348/TC-349 à ne pas refaire.

1.  Parler à Elias → « Demander mon kit de départ ». **Attendu** : la fenêtre **reste ouverte** et
    Elias répond « Tu as déjà reçu ton kit de départ. Il te sera redonné après ta prochaine mort. »,
    avec « ← Autres questions » (retour à l'accueil) et « Fermer ». Aucun outil reçu.
2.  « ← Autres questions » → accueil d'Elias inchangé (court, sans remise).
3.  Mourir, réapparaître, reparler à Elias → « Demander mon kit de départ ». **Attendu** : un seul kit
    reçu (4 outils), fenêtre fermée, message de remise dans le chat comme avant.
4.  Redemander aussitôt (même après déconnexion/reconnexion). **Attendu** : à nouveau la réponse de
    l'étape 1, jamais un second kit.

### TC-377 — Quêtes périodiques : résumé lisible sans survol, terminées à part, onglets PlugAdmin (issue #15, lot 2, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin issus de la PR #15 lot 2 sur **DEV**. Compte **LoDyMcFly tel quel**.
**Acquis TC-350 à ne pas refaire** : progression « Bois du jour » 16/16, fin automatique, 20 XP et
+5 LodyCoins, solde 105, état « Terminée ». **Aucun reset, aucune réattribution.** Revalidation
ciblée :

1.  Journal → horloge « Quêtes quotidiennes ». **Attendu** : le **titre de la fenêtre** indique
    « Quotidiennes : N/2 terminée(s) » d'après tes quêtes du jour (ex. « 1/2 terminée » si seul
    « Bois du jour » est fini) ; l'objet en haut au centre (horloge) indique « … — N restante(s) ».
2.  Sans survoler : 1re rangée ouverte par une **vitre jaune** (« À faire ») avec les quêtes non
    finies ; rangée suivante ouverte par une **vitre verte** (« Terminées ») avec les quêtes finies,
    qui **brillent**. « Bois du jour » reste visible dans « Terminées » jusqu'à 04:00.
3.  Onglet « Quêtes terminées » (quêtes classiques). **Attendu** : aucune quête quotidienne.
4.  Boussole « Quêtes hebdomadaires ». **Attendu** : même présentation, « Hebdomadaires : 0/1 terminée »
    (ou 1/1 si l'expédition est finie).
5.  PlugAdmin → Quêtes, cliquer « Rafraîchir Quêtes », puis l'onglet **Quotidiennes**. **Attendu** :
    les 3 modèles de démonstration, réglages affichés « 2 par jour », « chaque jour à 04:00
    (Europe/Paris) », « expire, sans récompense », mention « Valeurs de démonstration » ; aucun
    champ pour les modifier. Onglet **Hebdomadaires** : « chaque lundi à 04:00 ». Bouton « Créer une
    quête quotidienne » → éditeur avec « Quotidienne » et « répétable » préréglés (ne pas enregistrer).
6.  Sur téléphone (ou fenêtre étroite) : les trois onglets restent visibles (retour à la ligne).

### TC-350 — Quêtes quotidiennes et hebdomadaires dans le journal (issue #15, lot 1, PENDING MANUAL VALIDATION)

Prérequis : JAR issu de la PR #15 lot 1 sur **DEV** (schéma V42 appliqué au démarrage), section
`periodic-quests` ajoutée à `config.yml` par le complément (défauts : 04:00 Europe/Paris, lundi,
2 quotidiennes, 1 hebdomadaire), 4 modèles `daily_demo_*` / `weekly_demo_expedition` présents
dans `quests/`. Un compte qui possède déjà le journal. **Aucun reset.**

1.  Ouvrir le journal (clic droit ou `/quests`). **Attendu** : barre du haut « Quêtes en cours »,
    « Quêtes terminées », **horloge** « Quêtes quotidiennes », **boussole** « Quêtes
    hebdomadaires », puis pages, bourse, fermer. Deux titres « Quête commencée » et un message
    « ✦ Nouvelles quêtes quotidiennes : ouvre ton journal » (et hebdomadaires).
2.  Onglet « Quêtes quotidiennes ». **Attendu** : exactement 2 quêtes parmi Bois du jour / Pierre du
    jour / Patrouille du jour, « À faire aujourd'hui », « Nouvelles quêtes dans … » (jusqu'à 04:00).
    Elles **n'apparaissent pas** dans « Quêtes en cours ». Hebdomadaire : « Expédition de la
    semaine ».
3.  Fermer et rouvrir le journal plusieurs fois, cliquer vite sur l'onglet, se déconnecter /
    reconnecter. **Attendu** : toujours les **mêmes** 2 quêtes, aucun nouveau « Quête commencée ».
4.  Terminer une quotidienne (ex. 16 bûches de chêne). **Attendu** : récompense habituelle une
    fois (XP + 5 pièces de démonstration), « ✔ Terminée aujourd'hui » dans l'onglet ; rien de plus
    en rouvrant le journal ou en se reconnectant.
5.  Parler à un PNJ / taper `/quest accept rpgquest:daily_demo_mineur`. **Attendu** : refus « est
    une quête quotidienne ou hebdomadaire : ouvre ton journal… », rien n'est démarré.
6.  Lendemain après 04:00 (ou, pour tester tout de suite : régler `periodic-quests.reset-time` à
    quelques minutes plus tard puis redémarrer le serveur). **Attendu** : la quotidienne non finie
    affiche « ⌛ Quête expirée, sans récompense », 2 nouvelles quotidiennes sont attribuées (de
    préférence différentes de la veille) et repartent de zéro.

**Reset** : remettre `reset-time` à `04:00` si modifié. Rien à nettoyer côté joueur.

### TC-349 — Accueil d'Elias allégé, écran du kit, fenêtres plus larges, détails du journal paginés (issue #475, 2e retour du 17/10, PENDING MANUAL VALIDATION)

Prérequis : JAR issu du correctif #475 « accueil léger » sur **DEV** et `dialogues/guide.yml` republié
(voir le rapport de session). Compte **LoDyMcFly tel quel** (« Explorateur » active, bâton 1/4 déjà
remis). **Aucun reset.** Acquis de TC-348 à ne **pas** refaire : nom Elias, remise en un clic, bilan,
bouton conditionnel, journal 1/4. Fenêtre de jeu à la taille habituelle (~854×480), sans changer
l'échelle de l'interface.

1.  Parler à Elias. **Attendu** : accueil court (bienvenue + rôle), **aucun** matériau, compteur,
    phrase « tu as / tu n'as pas » ni bouton « Donner les matériaux ». Texte nettement plus large
    qu'avant (plus seulement la colonne de la largeur des boutons), boutons toujours un par ligne.
2.  « Comment améliorer mon kit ? ». **Attendu** : kit actuel, prochaine amélioration, quête, puis
    « À me rapporter : » avec les compteurs réels (Bâton 1/4…) et, juste dessous, « Tu n'as sur toi
    aucun des matériaux qui manquent. » si c'est le cas — **sans** bouton de remise.
3.  Avec un matériau attendu en poche, rouvrir cet écran. **Attendu** : « Tu as sur toi des
    matériaux que j'attends. » et un seul bouton « Donner les matériaux » (le bilan après clic est
    celui déjà validé en TC-348, inutile de le refaire en détail).
4.  Juger la lisibilité de 1 et 2 : largeur, lignes de liste, défilement. Note : le client Minecraft
    **centre toujours** chaque ligne d'un texte de dialogue (limite native vérifiée, voir la Bible) —
    signaler si la présentation reste gênante malgré cela.
5.  Journal → « Explorateur » → clic gauche (détails). **Attendu** : une rangée d'objets — icône de
    la quête, Description, Objectifs, Récompenses (Prérequis s'il y en a). Survoler chacun : chaque
    infobulle tient **entièrement** dans la fenêtre (titre visible en haut), la description complète
    se lit (« Description (2/2) » si elle est longue), objectifs « à remettre à Elias » 1/4…

### TC-348 — Remise au Guide en une action, Guide renommé, infobulle du journal (issue #475, retour du 17/10, PENDING MANUAL VALIDATION)

> **17/10 15:16 — 2e retour propriétaire : UX non validée, reprendre par TC-349.** Acquis conservés (nom
> Elias, remise unique, bilan, bouton conditionnel, journal 1/4). Depuis le correctif suivant, l'étape 1
> ne s'observe plus à l'accueil mais dans « Comment améliorer mon kit ? », et l'étape 6 se lit sur
> plusieurs objets de la vue détail.

Prérequis : JAR issu du correctif #475 du 17/10 sur **DEV**, contenus `dialogues/guide.yml`,
`dialogues/libraire.yml` et `quests/kit_tier2..5.yml` republiés (voir le rapport de session). Compte
**LoDyMcFly tel quel** : « Explorateur » (palier 3) active, dépôts déjà faits conservés. **Aucun reset**,
aucune quête de remplacement. Le dépôt partiel est **déjà validé** par le propriétaire : ne pas le refaire
pour lui-même.

1.  Inventaire **sans** bâton, pierre, cuir ni blé. Parler au Guide. **Attendu** : titre de la fenêtre
    « Elias », « Je suis Elias, le guide du village », puis « Matériaux à me remettre : » avec les
    compteurs réels (ex. Bâton 0/4…) et « Tu n'as sur toi aucun des matériaux qui manquent. ». **Aucun**
    bouton « Donner les matériaux », « J'ai des matériaux » ni « Donner encore ».
2.  Prendre un matériau qu'aucun objectif restant n'attend (ex. de la terre) ou un matériau dont
    l'objectif est déjà rempli. **Attendu** : même chose qu'en 1, aucun bouton de remise.
3.  Prendre une partie d'un matériau attendu, reparler au Guide. **Attendu** : « Tu as sur toi des
    matériaux que j'attends. » et **un seul** bouton « Donner les matériaux ». Clic : **une** fenêtre de
    bilan — « Tu viens de me remettre : N × … », compteurs à jour, ce qui manque, et plus de bouton de
    remise s'il ne reste rien d'utile. Pas d'écran « Donner mes matériaux » ni « Donner encore ».
4.  (Facultatif) Ouvrir le Guide avec un matériau utile, le jeter avant de cliquer « Donner les
    matériaux ». **Attendu** : rien retiré, la fenêtre se réaffiche sans le bouton.
5.  Remettre le reste. **Attendu** : dans la fenêtre, « Merci, c'est tout ce qu'il me fallait ! » et
    « Amélioration du kit obtenue : palier 3… » ; la quête passe dans « Terminées ».
6.  Journal → « Explorateur » (ou la quête suivante) → clic gauche (détails). **Attendu** : description
    « Rapporte à Elias de quoi… » repliée sur plusieurs lignes, entièrement lisible, rien de coupé à droite ;
    objectifs « (à remettre à Elias) ».
7.  Libraire : « Ah, c'est Elias qui t'envoie ? ». (Si le Guide est renommé à nouveau dans PlugAdmin,
    les points 1, 6 et 7 suivent le nouveau nom sans republier aucun contenu.)

### TC-347 — XP RPG seule : plus de progression de niveaux Minecraft (issue #183, PENDING MANUAL VALIDATION)

**Préparation** : DEV après déploiement #183 ; `config.yml` du serveur contient
`progression.vanilla-xp-progression: false` et plus de `keep-vanilla-xp`. Un compte joueur en survie, noter son niveau vanilla (barre verte)
et son niveau RPG (`/skills`).

1.  Tuer un zombie, casser un minerai de charbon, cuire un objet au four et le retirer, pêcher un
    poisson, faire reproduire deux vaches : **Attendu** aucune orbe verte, barre vanilla inchangée ;
    l'action bar annonce `+N XP RPG (…)` pour combat / minage / pêche.
2.  Commercer avec un villageois, lancer une fiole d'expérience (créatif pour l'obtenir) : **Attendu**
    aucune XP vanilla gagnée.
3.  Terminer une quête avec une récompense `EXPERIENCE` (ex. Lily) : **Attendu** chat `+ N XP RPG`,
    `/skills` Global augmenté de N (+ `quest-completion-xp`), barre vanilla inchangée.
4.  Mourir : **Attendu** niveau RPG et XP partielle identiques dans `/skills` après réapparition ;
    aucune orbe lâchée. Se reconnecter, puis après un redémarrage DEV : mêmes valeurs, aucun gain en double.
5.  Joueur qui avait des niveaux vanilla : **Attendu** niveaux toujours là (ni convertis ni effacés),
    utilisables à la table d'enchantement / l'enclume ; aucune nouvelle orbe ne les recharge.
6.  Donner un avis : faut-il effacer les anciens niveaux ? que faire des enchantements ?

**Reset** : aucun ; rien n'a été modifié dans les données du joueur.

### TC-345 — Atelier d'artisan : recettes à ingrédients et/ou pièces (issue #18, PENDING MANUAL VALIDATION)

**Préparation** : PlugAdmin (DEV) → « Prix et coûts », compte avec « Économie — écriture ». Créer, nature
« Le PNJ fabrique », cochés « démonstration » (valeurs libres) :
`demo_recette_ingredients` = 1 × BREAD contre 3 × WHEAT, 0 pièce ; `demo_recette_pieces` = 1 × LANTERN,
**aucun ingrédient**, 12 pièces ; `demo_recette_mixte` = 1 × IRON_SWORD contre 2 × IRON_INGOT + 1 × STICK,
5 pièces. En jeu : un compte avec quelques LodyCoins, 3 blés, 1 lingot de fer, 1 bâton.

**Préparation proposée le 20/10 (commentaire #18, à appliquer seulement après accord)** : les trois prix
ci-dessus avec les libellés « Pain de l'artisan », « Lanterne de l'artisan », « Épée en fer de l'artisan »
(`prices.yml` validé par le vrai chargeur, 0 erreur) ; accès en jeu par **Lucien** (Citizens n°14,
`world_hub` ≈ 722 66 -719) : choix « Ouvrir l'atelier » → Ouvrir un marchand → `rpgquest:artisan_demo`,
ajouté en tête du nœud `start` de `rpgquest:lucien` et publié via PlugAdmin (aucun JAR, aucun FTP).
Pour l'étape 8, utiliser la **lanterne** (aucun ingrédient pris, donc aucune place libérée) avec les 36
emplacements du sac et de la barre occupés par des piles pleines.
**Préparation appliquée sur DEV le 21/10** (accord « Autorisation #18 ») : `prices.yml` (3 prix ci-dessus) et
`dialogues/lucien.yml` (choix « Ouvrir l'atelier ») transférés par `deploy-verygames.sh --also` — DEV était arrêté,
donc pas via PlugAdmin ; contenu identique à celui que produit l'éditeur de prix. Lus au prochain démarrage de DEV.

1.  Prix et coûts : une fabrication sans ingrédient **et** à 0 pièce → **Attendu** refus « des ingrédients,
    des pièces, ou les deux ».
2.  Marchands : **Attendu** `artisan_demo` dans « Atelier d'artisan », chaque recette décrite en clair
    (« 1 × LANTERN fabriqué contre 12 pièce(s) »). Créer un atelier avec une ligne « objet + prix » →
    **Attendu** refus « ne propose que des recettes ».
3.  `/merchant open rpgquest:artisan_demo` : **Attendu** pour l'épée « ✔ 1 × STICK », « ✘ 2 × IRON_INGOT
    (tu en as 1) », coût 5 et, si le solde est inférieur, « Il te manque N ». Lanterne : « Ingrédients :
    aucun ». Pain : « Coût : aucune pièce ».
4.  Cliquer l'épée : **Attendu** « Il te manque des ingrédients : 1 × IRON_INGOT (tu en as 1 sur 2). Rien
    n'a été pris. », solde et inventaire inchangés.
5.  Cliquer le pain : 3 blés pris, 1 pain reçu, solde inchangé.
6.  Avec moins de 12 pièces, cliquer la lanterne : **Attendu** « Fonds insuffisants : il te manque N … (coût
    12, solde S) ». Avec assez de pièces : double-clic rapide → une seule lanterne, 12 pièces débitées.
7.  Ajouter un lingot, cliquer l'épée : 2 lingots + 1 bâton + 5 pièces pris, épée reçue, vitrine mise à jour
    (✘ revenus).
8.  Inventaire plein : clic sur une recette → message de place, rien débité ni pris.
9.  Relier l'atelier à un PNJ (proposé : Lucien, choix « Ouvrir l'atelier » → Ouvrir un marchand → Atelier de
    l'artisan) et vérifier l'ouverture depuis le dialogue ; PlugAdmin → Marchands affiche alors « Ouvert par :
    dialogue « rpgquest:lucien » ».

**Reset** : désactiver les trois prix `demo_recette_*` ; fermer l'atelier si besoin. Les essais n'ont touché
que le solde et l'inventaire du compte de test.

### TC-344 — Marchands vendeurs / acheteurs administrés depuis PlugAdmin (issue #17, PENDING MANUAL VALIDATION)

**Préparation** : PlugAdmin (DEV) → « Marchands » → « Rafraîchir ». Compte avec « Économie — écriture ».
En jeu, un compte avec quelques LodyCoins (`/money`), de la place, 16 pierres taillées (COBBLESTONE).

1.  Page : **Attendu** `village_merchant` (Vente et rachat) et `atelier_demo`, badge « exemple fourni »,
    « Ouvert par : dialogue … » quand un dialogue les ouvre ; pas de bouton Supprimer pour eux.
2.  « Nouveau marchand » : nom « Boutique d'essai », « Boutique », offre 1 = BREAD × 4, prix **vide** →
    **Attendu** refus « aucun prix n'est jamais mis par défaut ». Prix 3 → créé, ouvert, « Aucun dialogue ne
    l'ouvre encore ».
3.  « Nouveau marchand » : « Comptoir d'essai », « Rachat », offre COBBLESTONE × 8 pour 2. Créé.
4.  Dialogues → un PNJ de test → deux choix : « Ouvrir la boutique » (Ouvrir un marchand → Boutique d'essai)
    et « Vendre des objets » (Ouvrir un marchand → Comptoir d'essai). **Attendu** : la liste déroulante
    montre les catalogues avec leur nature. Publier.
5.  En jeu, « Ouvrir la boutique » : **Attendu** pain 4 × pour 3, solde affiché en bas ; acheter → solde
    diminué de 3 dans la vitrine. Sans fonds : « Fonds insuffisants », rien reçu. Inventaire plein :
    message de place, rien débité.
6.  « Vendre des objets » : vendre 8 pierres → +2 ; sans pierres : refus clair ; un autre objet n'est
    jamais proposé ni racheté.
7.  Vitrine ouverte, modifier le prix dans PlugAdmin (3 → 5) puis cliquer : **Attendu** « Le marchand vient
    de changer son étal », rien échangé ; cliquer à nouveau → achat à 5.
8.  « Fermer la boutique » : **Attendu** le dialogue répond « Cette boutique est fermée pour le moment ».
9.  Double-clic rapide sur une offre de rachat avec exactement 8 pierres : une seule vente.
10. Supprimer « Boutique d'essai » encore citée par le dialogue : **Attendu** bouton absent, explication ;
    retirer l'action du dialogue, rafraîchir, supprimer (case de confirmation) → fichier retiré.

**Reset** : supprimer le PNJ / dialogue de test et les catalogues d'essai depuis PlugAdmin ; les échanges
n'ont touché que le solde et l'inventaire du compte de test.

### TC-356 — Claim : animaux, seaux, dalles de pression et cultures protégés des non-membres (issue #531, PENDING MANUAL VALIDATION)

**Prérequis** : JAR #531 déployé ; deux comptes **sans** mode admin : A propriétaire d'un claim
(redstone publique **désactivée**, réglage par défaut), B non-membre. Dans le claim de A : une dalle
de pression reliée à une lampe, une terre labourée semée, une vache, un mouton, un cadre avec un
objet, un villageois ordinaire, un cochon sellé, un PNJ RPGQuest (ex. un PNJ taggé). **Durée** : 10 minutes.

1.  B marche sur la dalle → la lampe ne s'allume pas, **aucun** message (pas de spam).
2.  B saute sur la terre labourée → elle reste labourée, la graine reste ; même essai à dos du
    cochon (B le monte hors du claim puis entre) → terre intacte.
3.  B clique droit la vache avec un seau → pas de lait ; tond le mouton → pas de laine ; nourrit /
    met en laisse / renomme un animal → refusé ; ouvre l'échange du villageois → refusé ; monte le
    cochon sellé du claim → refusé.
4.  B tourne ou retire l'objet du cadre, puis frappe le cadre → objet et cadre intacts.
5.  B vide un seau de lave et un seau d'eau dans le claim → rien n'est versé ; B remplit un seau
    dans un point d'eau du claim → l'eau reste, le seau reste vide.
6.  B parle au PNJ RPGQuest du claim → le dialogue s'ouvre normalement.
7.  Non-régression A (et un membre ajouté par `/claim trust`) : dalle, piétinement, traite, tonte,
    seaux, cadre, échange → comportement vanilla. `/claim flag redstone true` → B déclenche la dalle,
    mais ne piétine toujours pas la terre labourée.
8.  Hors claim : B fait les mêmes actions → comportement vanilla.

### TC-357 — required-level des portails et offres marchandes lit le niveau RPG (issue #532, PENDING MANUAL VALIDATION)

**Prérequis** : JAR + PlugAdmin #532 déployés ; `progression.vanilla-xp-progression: false` (défaut) ;
un compte joueur **sans** mode admin, niveau vanilla 0 (barre verte vide) ; `base-xp: 100` (défaut :
niveau RPG 2 = 100 XP `GLOBAL`). **Durée** : 10 minutes.

1.  PlugAdmin → Marchands : sur un marchand d'essai, ajouter une offre avec **Niveau RPG min.** = 2 ;
    la fiche affiche « niveau RPG 2 ». Ouvrir le marchand en jeu : la lore dit « Requiert le niveau RPG 2 ».
2.  Admin : `/skills admin set <joueur> GLOBAL 0` (niveau RPG 1). Le joueur clique l'offre → refus
    « Il te manque le niveau requis pour cette offre. », aucun débit.
3.  Admin : `/skills admin set <joueur> GLOBAL 100` (niveau RPG 2). Le joueur rouvre et clique l'offre
    → échange accepté alors que son niveau vanilla est toujours 0.
4.  Portail d'essai : ajouter `required-level: 2` dans `plugins/RPGQuest/portals/<id>.yml`, puis
    redémarrer DEV (les portails ne sont relus qu'au démarrage). `/rpgadmin portal info <id>` affiche « Niveau RPG requis : 2 ». Refaire 2 et 3 :
    refus « Il te manque le niveau requis pour ce portail. » au niveau RPG 1, téléportation au niveau 2.
5.  Non-régression (optionnel) : avec `vanilla-xp-progression: true` et redémarrage, la condition relit
    le niveau vanilla (`/xp set <joueur> 2 levels` requis). Remettre `false` ensuite.

### TC-376 — PlugAdmin : « Lire ses droits en jeu » sans NullPointerException (issue #27, PENDING MANUAL VALIDATION)

Prérequis : JAR et PlugAdmin `c92687f` (PR #635) sur DEV, LuckPerms présent. Action **en lecture seule** :
ne modifie ni groupe, ni OP, ni whitelist, ni lien de compte. Ne rien synchroniser pendant ce test.

1.  Joueurs → fiche de **Rondoudou9000** (hors ligne) → onglet Modération. **Attendu** : sous l'état actuel,
    texte « … Fonctionne aussi quand le joueur est déconnecté. Lecture seule : rien n'est modifié. »
2.  « Lire ses droits en jeu ». **Attendu** : résultat **réussi** (plus « Échec : NullPointerException »),
    par exemple « Rondoudou9000 : Aucun droit géré porté par ce joueur. », daté.
3.  Même chose sur **LoDyMcFly** connecté. **Attendu** : même type de résultat réussi.
4.  Utilisateurs → un compte PlugAdmin **lié** à un joueur → « Lire l'état réel en jeu » : relevé réussi.
    Un compte **non lié** : bannière « Aucun joueur lié », aucun bouton de lecture (comportement inchangé).
5.  (Optionnel) Historique → détail de l'action : en cas d'erreur inattendue, le message est désormais en
    français (« erreur interne de l'agent RPGQuest pendant « mc.rights.read » … ») avec des détails
    `code`, `action`, `error`, `errorMessage`, `where` — jamais un nom d'exception seul.

### TC-376-bis — PlugAdmin : le bloc « Droits en jeu » passe à l'état final avec la notification (issue #27, PENDING MANUAL VALIDATION)

Prérequis : PlugAdmin `7b8b4b1` (PR #641) sur DEV (JAR inchangé `c92687f`). Action **en lecture seule** : ne modifie ni
groupe, ni OP, ni whitelist, ni lien de compte.

1.  Joueurs → fiche de **LoDyMcFly** (connecté, OP) → onglet Modération → « Lire ses droits en jeu ».
    **Attendu** : pendant l'attente, le bloc « Droits en jeu » affiche « en cours… » ; dès la fin de l'action, la page se
    recharge seule et le bloc montre le résultat final (succès, daté) — il ne reste jamais « en cours » une fois l'action finie.
2.  Même chose sur **Rondoudou9000** (hors ligne). **Attendu** : même transition, résultat réussi.
3.  (Si une lecture échoue un jour, par exemple LuckPerms indisponible) **Attendu** : le bloc passe à « échec » avec le message,
    au même moment que la notification — plus de notification « échec » à côté d'un bloc « en cours ».
4.  Utilisateurs → compte lié → « Lire l'état réel en jeu » : « Dernier relevé » passe aussi seul de « en cours… » au résultat.

### TC-378 — Craft de planches compté +4 avec la quête de Tania `help_recolte` (issue #34, PENDING MANUAL VALIDATION)

Prérequis : `quests/help_recolte.yml` et `dialogues/help.yml` publiés sur DEV (#34, 20/10/2026), JAR ≥ `1ba3b89`
(correctif #34, déjà en ligne). Le choix « Ton stock de bois » de Tania est **temporaire** (voir le commentaire en tête de
`dialogues/help.yml`). Aucune progression n'est forcée : la quête se prend normalement.

1.  Hub → parler à **Tania** (PNJ Citizens n°2). **Attendu** : accueil « Bonjour ! Je suis Tania. … », choix
    « Ton stock de bois » et « Au revoir ». Le nom affiché est « Tania » (plus « Help »).
2.  « Ton stock de bois ». **Attendu** : offre (5 bûches puis des planches), « Quête : Le stock de Tania », boutons
    « Commencer (j'accepte) » et « Pas maintenant ». Rien n'est encore démarré.
3.  « Commencer (j'accepte) ». **Attendu** : message de quête commencée « Le stock de Tania » ; journal : quête active,
    description « Tania a besoin de bois pour refaire quelques caisses. ».
4.  Ramasser **5 bûches de chêne** (étape 1, `COLLECT_ITEM`). **Attendu** : 5/5, l'étape « fabriquer_planches »
    devient l'étape en cours. Ne pas fabriquer de planches avant : un craft fait pendant l'étape 1 ne compte pas.
5.  Établi ou grille 2×2 : **1 bûche**, **clic simple** sur le résultat (curseur vide). **Attendu** : le premier
    contrôle passe à **4/4 d'un coup** (+4, pas +1), l'étape se termine, la quête se termine **une seule fois** :
    +25 XP et 3 pains.
6.  Reparler à Tania pendant la quête (si l'étape 5 n'est pas encore faite) : « Ton stock de bois » mène au rappel
    « Merci de t'en occuper ! … », sans nouvelle offre.

**Reset** : rien à remettre — la quête est `repeatable: true` sans délai, elle se reprend auprès de Tania.

### TC-379 — Crafts multiples (shift-clic, plusieurs lots) sur un objectif `CRAFT_ITEM` (issue #34, PENDING MANUAL VALIDATION)

Distinct de TC-378 : **ne modifie pas** `help_recolte`. Reprend les cas de TC-015.

**A — avec `help_recolte` (objectif de 4, renouvelable)**, après avoir ramassé les 5 bûches à chaque reprise :
1.  **3 bûches**, **shift-clic** sur le résultat. **Attendu** : 4/4 (12 planches fabriquées, compteur plafonné à 4,
    jamais au-delà), étape et quête terminées une seule fois, récompenses reçues une seule fois.
2.  Reprise suivante : curseur tenant **un autre objet** (ex. une pierre), clic simple sur le résultat. **Attendu** :
    rien n'est fabriqué, compteur inchangé (0/4). Puis vider le curseur, clic simple → 4/4.
3.  Reprise suivante : inventaire **plein** (aucune case libre, aucune pile de planches incomplète), shift-clic.
    **Attendu** : rien n'est rangé, compteur inchangé. Libérer une case → shift-clic → 4/4.

**B — cumul au-delà d'un lot (+12, 18/20 → 20/20)** : il faut un objectif plus grand que 4. Créer une quête de test
**séparée** dans PlugAdmin (Quêtes → nouvelle quête, catégorie `test`, un objectif `CRAFT_ITEM` `OAK_PLANKS` × 20), la
rendre accessible comme le propriétaire le souhaite, puis dérouler les étapes 1 à 7 de **TC-015** :
1 bûche clic simple → 4/20 ; second craft → 8/20 ; 3 bûches shift-clic → +12 ; inventaire plein sauf une pile partielle
de planches → seule la quantité rangée compte ; à 18/20, shift-clic de 4 bûches → 20/20 sans dépassement ;
déconnexion/reconnexion → pas de recomptage.

**Couverture automatisée** : `QuestCraftObjectiveTest` (9 cas).

### TC-380 — Bossbar de suivi compacte, sans identifiant technique (issue #71, PENDING MANUAL VALIDATION)

Aucune quête à créer ni à réinitialiser : utiliser les quêtes **déjà actives** du compte de test.

1.  Journal → quête active « **Marchandise du désert** » → clic droit (Suivre). **Attendu** : bossbar
    `Marchandise du désert — Cactus (à remettre à <nom du marchand>) 0/12` (ou `Cactus (à remettre) 0/12`
    si le nom est long) ; jamais `CACTUS` ni `encounter_merchant` ; le nombre déjà remis est le même que dans le journal.
2.  Suivre « **Le Garde — Palier 2** » (4 objectifs). **Attendu** : `Le Garde — Palier 2 — k/4 objectifs`, une seule
    ligne qui tient dans l'écran ; aucune liste `Tuer SPIDER · …`.
3.  Tuer un mob compté par cette quête. **Attendu** : ActionBar `Araignée 3/5` (nom en français), la bossbar passe à
    `k+1/4 objectifs` seulement quand un objectif est terminé.
4.  **Petite fenêtre** : réduire la fenêtre Minecraft (≈ 854×480) et passer l'échelle d'interface au maximum
    (Options → Vidéo → Échelle de l'interface). **Attendu** : le texte de la bossbar reste entier à l'écran (titre
    éventuellement raccourci par « … », compteur toujours entier).
5.  Recharger le journal, se déconnecter/reconnecter. **Attendu** : bossbar identique, aucune progression modifiée.

**Couverture automatisée** : `TrackedQuestSummaryTest` (16 cas dont 1/4/10/20 objectifs et titre long),
`TrackedQuestDisplayTest` (5), `QuestProgressEngineTest#objectiveProgressIsShownViaActionBarNotChat`. Les cas
10 et 20 objectifs ne sont testés qu'en automatique (aucune quête réelle n'en a autant).

### TC-381 — Clic droit sur Bûcheron Bob → accepter « La requête du bûcheron » (issue #80, PENDING MANUAL VALIDATION)

Bob existe déjà (Citizens #49, Hub 743/67/-714) : ne pas le recréer, ne pas démarrer la quête par commande.
La quête `rpgquest:woodcutters_request` exige `rpgquest:first_steps` (Premiers pas, au Garde).

1.  Clic droit sur Bob. **Attendu** : le dialogue « Bûcheron Bob » s'ouvre (accueil + « Du travail ? », « Qui es-tu ? », « Au revoir »).
2.  Premiers pas **pas encore terminée** : « Du travail ? ». **Attendu** : Bob renvoie au Garde (« Termine d'abord Premiers pas… ») ; rien ne démarre.
3.  Premiers pas terminée : « Du travail ? ». **Attendu** : offre qui nomme « La requête du bûcheron » ; « Plus tard » / « Refuser » ne démarrent rien ;
    « Commencer (j'accepte) » démarre la quête (chat + journal, objectif bûches de chêne).
4.  Recliquer sur Bob pendant la coupe. **Attendu** : « Où en est ma tâche ? » visible, plus d'offre.
5.  Couper 20 bûches de chêne puis clic droit sur Bob. **Attendu** : quête terminée sur ce clic (30 XP, 16 planches de chêne), une seule fois ;
    la quête étant renouvelable, Bob propose de nouveau « Du travail ? ».

**Couverture automatisée** : `BundledDialoguesValidityTest#theWoodcutterOffersHisQuestFromTheDialogueTheClickOpens`
(giver de la quête = clé du dialogue ouvert au clic, START_QUEST gardé par QUEST_AVAILABLE, prérequis expliqué),
`BundledDialoguesValidityTest` (chargement, libellés ≤ largeur de bouton, démarrage toujours annoncé), `BundledExamplesDriftTest`.

### TC-382 — Noms affichés des mondes et coordonnées F3 décimales (issue #81, PENDING MANUAL VALIDATION)

Ne déplacer, renommer ni recréer aucun PNJ (Bob = Citizens #49) ; ne modifier aucun monde sauf le nom affiché choisi à l'étape 4.

1.  PlugAdmin → **PNJ** → fiche de Bob → « Déplacer ». **Attendu** : liste Monde « Hub (world_hub) » (plus « Village ») ; X/Y/Z préremplis à la précision F3.
2.  Saisir `743.222` dans X (ou coller `743.222 67 -713.699` copié de F3 dans X). **Attendu** : aucun refus du navigateur ; le collage remplit X, Y et Z.
    Ne PAS cocher la confirmation ni envoyer.
3.  **Mondes**. **Attendu** : cartes « Hub world_hub », « Claims claims », « Wild wild », « Monde principal world » ; fiche → « Nom affiché … (par défaut — à choisir dans Réglages) ».
4.  (Facultatif, si un nom est souhaité) fiche d'un monde → Réglages → « Lire les réglages réels » → « Nom affiché » = un nom → Prévisualiser.
    **Attendu** : aperçu « - → nom », écrit par Multiverse-Core (`world_hub`, `claims`…) ou RPGQuest (`wild`), effet « l'identifiant … ne change pas ».
    Confirmer → relecture « Confirmé » ; après « Rafraîchir les mondes », le nom apparaît dans PNJ, Mondes, Portails, Voyages. « - » revient au défaut.
5.  `<`, `&`, `§` ou plus de 32 caractères → refus motivé dans l'aperçu, rien d'écrit.

**Couverture automatisée** : `WorldSettingsPlannerTest` (normalisation, refus, empreinte), `BukkitWorldSettingsGatewayTest` (alias Multiverse vs
`world-names.yml`, relecture, autre monde intact, persistance), `WorldOverviewTest` (source de l'alias, alias = identifiant ignoré),
`WorldNamesTest`, `WorldsPageTest`, `WorldSettingsPageTest`, `NpcCoordinatesTest` (743.222 / négatifs acceptés, invalides refusés, `step="any"`).

### TC-383 — Atelier : Boulangerie de Central, orientation, réparations sûres et boucle bornée (issue #655, PENDING MANUAL VALIDATION)

Projet réel `96a24bba` (« Boulangerie de Central »). Aucune action n'a été faite sur ce projet par la livraison. Les étapes 1 à 3 sont **gratuites** ;
l'étape 5 est la seule payante, à faire seulement si vous le décidez.

1.  PlugAdmin → **Atelier** → « Boulangerie de Central » (sans paramètre de version dans l'URL).
    **Attendu** : la page s'ouvre sur la version **35ce1e1a** (« Plan conforme »), la version retenue par la conception « Exigeant » du 20/10,
    et non plus sur la dernière version refusée (f8540b38). Le journal de la conception est inchangé.
2.  **Attendu** : bandeau orange « Façade d'entrée contradictoire : les gestes payants sont suspendus » — la description place l'entrée
    à l'OUEST, la façade choisie est EST ; « Le bâtiment souhaité » est ouvert.
    Cliquer « Créer / Améliorer avec l'IA » ou lancer la conception automatique : **attendu** « … Rien n'a été demandé à l'IA. », aucun appel.
3.  Dans « Le bâtiment souhaité », trancher : soit Façade d'entrée = **Ouest** (ce que dit la description), soit corriger la description.
    Enregistrer. **Attendu** : le bandeau disparaît, les gestes payants redeviennent disponibles.
4.  Sur n'importe quelle version, « Vérifier le bâtiment ». **Attendu** : « Vérification en cours… » puis la page se recharge **seule**
    sur le résultat, réussi ou non (sans cliquer « Rafraîchir »).
5.  *(Payant, facultatif)* Conception automatique « Soigné » ou « Exigeant ». **Attendu** dans le journal : les fautes sûres (façade en point
    cardinal, `minecraft:brick`, toit à y=10, marches d'entrée inutiles, entrée à sauter, entrée absente) sont réparées par « Réparation par le
    moteur (gratuit) » ; une critique qui casse le plan conforme n'a droit qu'à une correction, puis « retour à la version conforme » ;
    jamais plus d'appels que l'enveloppe ; la page finit sur le plan retenu.
6.  Enregistrer le plan retenu dans la bibliothèque, puis le poser en jeu sur un emplacement de test. **Attendu** : on entre à pied par la
    façade d'entrée (2 blocs de haut, sans saut), toit fermé, intérieur éclairé.

**Couverture automatisée** : `Boulangerie655Test` (6 plans réels : d852629f, d54a87b1, 73affe8a, 14b2493d, 739ea04b, 35ce1e1a — sans aucun
appel payant), `BriefOrientationTest`, `BuildingPromptFrame655Test`, `WorkshopDefaultDraft655Test`, `BuildingDesignPipelineTest`
(critique ratée bornée à 5 appels au lieu de 12, diagnostic répété), `WorkshopFlowTest` (gestes payants suspendus, suivi terminal).

### TC-384 — Journal : consulter sa progression RPG sans commande (issue #183, retour du 20/10, PENDING MANUAL VALIDATION)

Lecture seule : aucune XP, aucun niveau, aucune récompense n'est modifié par cette page.

1.  En jeu, clic droit avec le **Journal des quêtes** (ou `/quests`). En haut à droite, survoler le **lingot d'or « Bourse »**.
    **Attendu** : sous le solde, « Niveau RPG N », une barre de 10 cases et un pourcentage, puis « Clic : voir ma progression ».
2.  Cliquer le lingot. **Attendu** : une page « Ma progression RPG » avec 6 objets en ligne : Global, Combat, Minage, Agriculture,
    Pêche, Exploration (jamais « GLOBAL », « MINING »…). Chaque infobulle : « Niveau N / max », barre + pourcentage,
    « XP : x / y pour le niveau N+1 », XP totale, et d'où vient l'XP de la piste. L'infobulle tient entière à l'écran.
3.  Comparer avec `/skills` (facultatif) : **attendu** les mêmes niveaux et les mêmes XP x / y.
4.  Tuer un zombie ou miner quelques blocs, rouvrir la page. **Attendu** : la piste concernée et Global ont avancé.
5.  « Retour » ramène à l'onglet d'où l'on venait ; « Fermer » ferme ; essayer de prendre un objet (clic, shift-clic, touche 1-9) :
    rien ne bouge. Dans la vue détail d'une quête, la bourse reste un simple indicateur.

**Couverture automatisée** : `ProgressionGaugeTest` (0 %, intermédiaire, 99 % jamais arrondi à 100, passage de niveau, niveau maximal,
largeur fixe de la barre, aucun identifiant technique), `QuestJournalServiceTest` (résumé dans la bourse, 6 pistes dans l'ordre, joueur
sans progression, page en lecture seule + clics annulés + Retour vers le même onglet, Fermer au tick suivant).

### TC-385 — PlugAdmin : consulter les claims d'un joueur et s'y téléporter pour diagnostic (issue #637, PENDING MANUAL VALIDATION)

Aucun claim n'est créé, supprimé, transféré ni déplacé pour ce test ; ne casser aucune construction. La téléportation
n'est faite que par le propriétaire du serveur, avec son personnage lié.

1.  PlugAdmin → **RPGQuest → Claims** → « Rafraîchir les claims ». **Attendu** : une carte par claim réel, avec le pseudo
    du propriétaire (ou « joueur 1234abcd… » s'il est inconnu), « Principal », le monde, le centre (x, z), la taille et
    le palier. Comparer avec `/claim list` / `/claim admin tp <joueur>` en jeu : mêmes identifiants et positions.
2.  Rechercher un pseudo, puis ouvrir la fiche joueur (Joueurs → le joueur) : **attendu** une carte « Claims » listant
    ses claims ; pour un joueur sans claim, « Aucun claim pour ce joueur ». Essayer un joueur hors ligne : même résultat.
3.  Ouvrir la fiche d'un claim **d'un autre joueur** (avec son accord). **Attendu** : « Personnage déplacé » = votre
    personnage lié, « Destination » = ce claim ; aucune téléportation tant que rien n'est confirmé (rafraîchir la page :
    rien ne bouge en jeu).
4.  En jeu, mode bypass **désactivé**, personnage sans claim ni `CLAIM_TIER_1` (si c'est le cas) : confirmer « M'y
    téléporter ». **Attendu** : refus expliqué (« serait aussitôt renvoyé au Hub… »), vous ne bougez pas. Sinon passer à 5.
5.  Avec un personnage éligible (claim ou `CLAIM_TIER_1`, ou `/rpgadmin bypass on` puis `off` une fois arrivé) :
    confirmer « M'y téléporter » (double-cliquer le bouton). **Attendu** : une seule téléportation, arrivée sur le claim
    sur un sol sûr, « Dernière demande » en cours puis « Votre personnage est sur le claim … en x, y, z ».
6.  Une fois sur place, mode bypass **OFF** : tenter de casser un bloc du claim d'autrui. **Attendu** : refusé (la
    téléportation ne donne aucun droit). Ne rien casser sur votre propre claim pour ce test.
7.  Avec un compte PlugAdmin Testeur (s'il existe) : la page Claims se lit, mais la fiche dit que le rôle ne permet pas
    de s'y téléporter.

**Couverture automatisée** : `ClaimOverviewTest` (principal, palier déduit, pseudo inconnu, relevé vide vs indisponible),
`ClaimAdminTeleportTest` (MockBukkit : seul l'administrateur bouge, claim inchangé, aucune appartenance, refus non éligible
sans écrire `CLAIM_TIER_1`, aucune position sûre, claim inconnu / changé de propriétaire, hors ligne, monde non chargé,
double demande), `ClaimActionsTest` (contrat JSON, refus non succès, paramètres invalides), `ClaimsPageTest` (page sans
relevé, liste et filtre par joueur, fiche, rien envoyé à l'ouverture, personnage lié imposé même si le formulaire désigne
le propriétaire, confirmation obligatoire, double clic, droits lecture / téléportation distincts).

### TC-386 — Waypoints : les panneaux de nom ne s'éditent plus (issue #654, PENDING MANUAL VALIDATION)

Test réservé au propriétaire. Ne rien casser ni renommer ; ne pas utiliser de panneau de waypoint réel pour autre chose
que les clics décrits. Prévoir un panneau ordinaire posé par vous-même hors de toute structure (étape 5).

1.  Joueur normal (non OP), face à un waypoint existant (par exemple « Avant-poste 12 ») : clic droit sur chacun des
    deux panneaux latéraux, puis en visant le **verso** si accessible. **Attendu** : aucun écran « Edit Sign Message »,
    le texte reste le nom canonique.
2.  Même joueur, une teinture, une encre lumineuse puis un rayon de miel en main : clic droit sur un panneau.
    **Attendu** : ni couleur, ni texte lumineux, ni cire ; l'objet n'est pas consommé.
3.  Compte OP, puis (si vous avez la permission dédiée) `/rpgadmin travel maintenance on` : refaire 1.
    **Attendu** : toujours aucun éditeur. Terminer par `/rpgadmin travel maintenance off`.
4.  Clic droit sur le **bouton** du waypoint. **Attendu** : message de découverte ou « Waypoint déjà découvert : … ».
5.  Clic droit sur votre panneau ordinaire hors structure. **Attendu** : l'éditeur s'ouvre et la modification est
    enregistrée (comportement vanilla inchangé).
6.  Après un redémarrage du serveur ou en revenant d'assez loin pour recharger le chunk : refaire 1. **Attendu** :
    identique.

**Couverture automatisée** : `WaypointProtectionListenerTest` (clic main vide et teinture/encre/cire refusés, OP +
maintenance sans éditeur, ouverture refusée recto/verso pour toutes les causes, écriture refusée recto/verso, bouton
inchangé, panneau ordinaire éditable), `WaypointServiceTest#persistedWaypointSignsStayUneditableAfterReloadWhileTheButtonStillWorks`
(waypoint généré puis rechargé depuis la base : les deux panneaux protégés, nom canonique intact, bouton cliquable).

### TC-387 — PlugAdmin : portail en brouillon, zone, activation/désactivation (issue #565, PENDING MANUAL VALIDATION)

**Feu vert séparé du propriétaire requis avant l'étape 2** : l'autorisation du 20/10 interdit toute création,
activation, désactivation, modification ou suppression de portail réel, brouillon compris. Les étapes 1 et 7 sont
en lecture seule et peuvent être faites dès le déploiement. Ne jamais toucher `hub_to_wild` ni `hub_to_claims`.

1.  Lecture seule : PlugAdmin → Monde → Portails → « Rafraîchir les portails ». **Attendu** : `hub_to_wild` et
    `hub_to_claims` toujours **Actifs**, mêmes coins qu'avant ; leur fiche montre « Portail essentiel », une case
    « Désactiver », la zone verrouillée (« Désactiver le portail avant de déplacer… ») et pas de suppression.
    En bas de la liste : « Créer un portail en brouillon ».
2.  (Feu vert requis) Marquer en jeu un emplacement de test avec l'outil d'emplacement, loin des portails du Hub ;
    rafraîchir Emplacements puis Portails. Créer un brouillon `test_portail_565`, zone autour de cet emplacement
    3 × 4 × 1, arrivée `wild` ou un monde de test, case cochée. **Attendu** : carte « Brouillon » ; en jeu, traverser
    la zone ne téléporte pas.
3.  Créer un second brouillon dont la zone recouvre la première. **Attendu** : refus « recouvre … », rien d'écrit.
4.  Fiche du brouillon : renommer, puis passer la zone à 5 × 5 × 1. **Attendu** : nom et coins mis à jour, journal
    « Modifications depuis PlugAdmin » avec avant / après. Page Emplacements : « Utilisé par le portail
    test_portail_565 » ; « Supprimer l'emplacement » refusé.
5.  Activer (case cochée). **Attendu** : carte « Actif » ; en jeu, traverser la zone téléporte vers le monde choisi
    (avec l'avertissement Wild si `wild`). La zone n'est plus modifiable, la suppression non proposée.
6.  Désactiver. **Attendu** : traverser ne téléporte plus ; nom, zone et emplacement conservés. Supprimer le
    portail. **Attendu** : carte disparue, emplacement intact, aucun bloc changé ; `hub_to_wild` / `hub_to_claims`
    inchangés et toujours fonctionnels.
7.  (Lecture seule) Après un redémarrage du serveur : les deux portails du Hub sont toujours chargés et actifs.

**Couverture automatisée** : `WorldPortalAdminServiceTest` (copie isolée de `world-portals/` avec les deux portails de
DEV : brouillon orienté, refus de superposition y compris ancien format, essentiels intacts sans confirmation, empreinte
périmée, zone verrouillée si actif, suppression du fichier seul), `WorldPortalRegistryTest` (refus au rechargement,
priorité au portail chargé, aller-retour des champs, format historique conservé), `PortalZoneTest`,
`PortalAdminParamsTest`, `PortalOverviewTest`, `PortalsPageTest` (formulaires, catalogue, auteur = session,
emplacement cité non supprimable, rôles).

### TC-388 — PlugAdmin : charger, décharger, importer un dossier existant (issue #563, PENDING MANUAL VALIDATION)

**Feu vert séparé du propriétaire requis avant l'étape 3** : l'autorisation du 20/10 interdit tout chargement,
déchargement ou import d'un monde réel par Claude, même « test ». Les étapes 1 et 2 sont en lecture seule. Ne jamais
toucher le monde principal, `world_hub`, `wild`, `claims`.

1.  Lecture seule : Monde → Mondes → « Rafraîchir les mondes », ouvrir la fiche du Hub, « Relever le chargement ».
    **Attendu** : section « Chargement » sans bouton, raisons affichées (« a le rôle HUB », joueurs présents s'il y en
    a) ; même chose pour `wild` (rôle WILD + `worlds.yml`), `claims` et le monde principal.
2.  Lecture seule : sous la liste, « Importer un dossier existant ». **Attendu** : uniquement des dossiers que
    Multiverse-Core ne connaît pas, avec la dimension lue dans le dossier ; « Aucun dossier importable » sinon.
3.  (Feu vert requis) Créer un monde de test vide (TC-375) ou en choisir un sans rôle ni portail, s'y placer en jeu,
    noter un bloc posé à un endroit précis. Fiche → « Enregistrer puis décharger » coché. **Attendu** : refus « 1
    joueur(s) se trouvent dans … », rien n'est fait.
4.  Quitter le monde, relever à nouveau, décharger (case « Charger au démarrage » cochée), confirmer. **Attendu** :
    message « enregistré puis déchargé ; fichiers conservés », contrôles OK ; la carte passe à « Non chargé » ; le monde
    reste listé (Multiverse).
5.  « Charger », confirmer. **Attendu** : « chargé et relu conforme » ; en jeu, le bloc posé à l'étape 3 est toujours là
    (données intactes).
6.  Créer un portail en brouillon vers ce monde puis l'activer (TC-387, feu vert requis). Tenter de décharger.
    **Attendu** : refus citant le portail actif. Le désactiver : le déchargement redevient possible.
7.  « Ne plus charger au démarrage », redémarrer le serveur. **Attendu** : le monde n'est pas chargé après le
    redémarrage ; `hub_to_wild` / `hub_to_claims`, Hub, Wild et Claims inchangés.
8.  (Feu vert requis, facultatif) Copier par FTP un dossier de monde de test, relever, l'importer avec une dimension
    différente de celle détectée. **Attendu** : refus. Avec la bonne dimension : importé, chargé, sans rôle.

**Couverture automatisée** : `WorldStatePlannerTest` (tous les refus : monde principal et dimensions, rôles,
`worlds.yml`, joueurs, portail actif / inactif / sortant, non chargé, hors Multiverse, import : dossier, réservé,
dimension, noms de chemin ; Multiverse absent), `WorldStateServiceTest` (décharger puis recharger : données intactes,
refus sans appel serveur, état revérifié à l'exécution, jeton jamais rejoué, chargement au démarrage, écart relu,
échec Multiverse), `WorldStateFoldersTest`, `NoWorldDeletionTest`, `WorldStateActionTest`, `WorldStatePageTest`
(boutons selon le relevé, raisons, confirmation, auteur = session, double clic, WORLD_WRITE côté serveur).

### TC-389 — Testeur non-OP : /rpgadmin player, quest et story sans OP (issue #64, PENDING MANUAL VALIDATION)

**Accord explicite du propriétaire requis avant l'étape 2** : l'autorisation du 20/10 interdit à Claude d'attribuer
ou de retirer un droit réel. Utiliser de préférence un **second compte de test** ; ne lancer `resetnew` / `quest` /
`story` que sur ce compte de test, jamais sur un vrai joueur.

1.  Compte de test **non-OP**, sans droit : `/rpgadmin quest complete <test> <quête>`. **Attendu** : « Permission
    manquante : rpgquest.admin.command », rien ne change.
2.  (Accord requis) Console : `/lp user <test> permission set rpgquest.admin.testing true` (ou PlugAdmin > Groupes,
    famille « Raccourcis de test », groupe rattaché au compte). Le compte reste non-OP (`/op` jamais utilisé).
3.  `/rpgadmin player resetnew <test> preview` puis `confirm`. **Attendu** : aperçu puis reset du seul compte de test.
4.  `/rpgadmin quest start <test> <quête>`, `/rpgadmin quest complete …`, `/rpgadmin story start <test> <story>`.
    **Attendu** : exécutés, journal et messages identiques à un OP.
5.  `/rpgadmin player variable set <test> CLAIM_TIER_1 true`. **Attendu** : refus nommant `rpgquest.admin.debug`.
6.  Sans rien changer : casser un bloc au Hub → **refusé** ; frapper une entité protégée du Hub → **refusé** ; entrer
    dans Claims sans palier → **refusé** ; casser dans le claim d'un autre → **refusé** ; `/rpgadmin bypass on` →
    refusé (`rpgquest.admin.bypass`) ; `/rpgadmin zone list`, `/rpgadmin flatten` → refus `rpgquest.admin.world`.
7.  Se reconnecter, puis après un redémarrage DEV : refaire l'étape 4. **Attendu** : toujours autorisé (LuckPerms).
8.  `/lp user <test> permission unset rpgquest.admin.testing`. **Attendu** : étape 4 refusée immédiatement.

**Couverture automatisée** : `RpgPermissionsMatrixTest` (paquet testeur : trois branches, aucune construction Hub,
aucun bypass, ni bypass/debug/hub.combat, autres branches fermées ; joueur ordinaire refusé ; seules
player/quest/story sont des branches de test), `RpgAdminTestShortcutsCommandTest` (testeur non-OP : quest complete,
story start, variable get exécutés ; zone refusée ; variable set refusé sans debug ; un nœud de branche n'ouvre que
sa branche), `BridgeProvenanceTest` (nouveaux nœuds gérables par le pont, ombrelle et debug toujours exclus),
`McBridgeDirectoryTest` (famille « Raccourcis de test », sans monde).
