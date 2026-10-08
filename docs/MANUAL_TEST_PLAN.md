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
    3.  En jeu, sur un PNJ de test : `/rpgadmin npc tag tc12_pnj`. Puis créer un dialogue
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

-   **Fonctionnalité testée :** statut OP réel, OP/DEOP avec raison et confirmation d'identité,
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
    4.  Cliquer **Accorder OP**. **Attendu** : raison obligatoire **et** champ demandant de retaper
        le pseudo exact.
    5.  Retaper un **mauvais** pseudo et valider. **Attendu** : **refus** citant le pseudo attendu.
    6.  Retaper le bon pseudo, avec une raison, et valider. **Attendu** : succès ; le résultat
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
