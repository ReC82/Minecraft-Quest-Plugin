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

Sept quêtes minimalistes, une par type d'`ObjectiveType` implémenté,
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

Toutes `repeatable: true` (rejouables sans `/quest admin reset`), avec une
récompense symbolique de 5 XP chacune (permet aussi de vérifier au passage
le résumé de récompenses de TC-014).

**Procédure d'activation (test manuel uniquement, à retirer ensuite) :**

1.  Copier les 7 fichiers de `docs/manual-tests/quests/` vers
    `plugins/RPGQuest/quests/` sur le serveur de test.
2.  `/quest admin reload` (ou redémarrer) → le rapport doit annoncer 7
    quêtes de plus chargées, 0 erreur.
3.  Pour chaque type : `/quest accept rpgquest:test_<type>`, réaliser
    l'action décrite ci-dessus, vérifier `/quest progress
    rpgquest:test_<type>` puis la remise automatique (Title + résumé chat,
    voir TC-014). Pour `test_talk_to_npc`, taguer l'entité **avant**
    d'accepter ou après, peu importe — seul l'ordre clic-après-tag compte.
4.  Une fois les 7 types validés, **supprimer les 7 fichiers** de
    `plugins/RPGQuest/quests/` puis `/quest admin reload` à nouveau (le
    rapport doit annoncer leur disparition, 0 erreur) — ces quêtes ne
    doivent **jamais** rester dans une installation VeryGames de
    production entre deux sessions de test.
5.  Optionnel : `/quest admin reset <joueur> all` (ou juste les 7 ids) pour
    nettoyer la progression de test avant de retirer les fichiers, si le
    même compte sert aussi à des tests de production.

-   **Couverture automatisée :** `ManualTestQuestPackTest` (le pack reste
    chargeable sans erreur, un id `test_*` par quête, les 7 types
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
