# TODO

## Environnement (fait)

-   [x] Projet Gradle Kotlin DSL + wrapper (Gradle 9.6.1)
-   [x] Paper API 1.21.11 + `runServer` (xyz.jpenilla.run-paper 3.0.2)
-   [x] JUnit 5 + MockBukkit (mockbukkit-v1.21)
-   [x] Classe principale `RPGQuestPlugin` + `plugin.yml`
-   [x] Commandes `/rpgquest version` et `/rpgquest help`

## Architecture modulaire (fait)

-   [x] `PluginService` (start/stop) + `PluginServiceRegistry` (ordre
    garanti au démarrage, LIFO à l'arrêt, rollback si un service échoue)
-   [x] `config.yml` (`debug`, `locale`, `database.file`, `resource-pack`)
    validé au démarrage avec des messages d'erreur lisibles
-   [x] `/rpgquest reload` (`rpgquest.admin`) : recharge sans recréer les
    services ni perdre de données ; config précédente conservée si invalide
-   [x] Interfaces des futurs moteurs : `QuestEngine`, `DialogueEngine`,
    `CustomItemRegistry`, `QuestJournalUi` (toutes `PluginService`) —
    `QuestEngine` et `DialogueEngine` sont désormais implémentées
    (`YamlQuestEngine`, `YamlDialogueEngine`), `CustomItemRegistry` et
    `QuestJournalUi` restent des interfaces marqueurs

## Définitions de quêtes (fait)

-   [x] Modèles immuables (`QuestDefinition`, étapes, objectifs, récompenses,
    textes localisables) avec identifiants namespacés (`NamespacedKey`)
-   [x] 7 types d'objectifs (BREAK_BLOCK, PLACE_BLOCK, KILL_ENTITY,
    COLLECT_ITEM, CRAFT_ITEM, TALK_TO_NPC, REACH_LOCATION) et 4 types de
    récompenses (EXPERIENCE, ITEM, VARIABLE, COMMAND)
-   [x] `QuestLoader` : validation par fichier (doublons de champs, valeurs
    négatives, types inconnus, champs obligatoires), un fichier invalide ne
    bloque pas les autres, doublons d'id et prérequis manquants détectés
    entre fichiers
-   [x] `/quest admin reload` et `/quest admin validate` (`rpgquest.admin`)
-   [x] Deux quêtes d'exemple générées automatiquement (`first_steps`,
    `woodcutters_request`)

## Progression des quêtes (fait)

-   [x] États `NOT_STARTED` (implicite, absence de ligne), `ACTIVE`,
    `READY_TO_TURN_IN`, `COMPLETED`, `FAILED` (état modélisé, non déclenché
    par le gameplay actuel), `ABANDONED`
-   [x] Acceptation (prérequis, doublon, répétition contrôlée), abandon
    (ré-acceptable ensuite), progression par événement, remise automatique
    à la fin de la dernière étape (octroi des récompenses)
-   [x] Écouteurs Bukkit enregistrés **uniquement** pour les types
    d'objectifs réellement utilisés par les quêtes chargées (recalculé à
    chaque `/quest admin reload`)
-   [x] `QuestObjectiveIndex` : aucune quête non concernée n'est parcourue
    par événement
-   [x] Anti double-incrément (compteur plafonné au requis) et anti
    double-remise (bascule mémoire synchrone avant toute persistance async)
-   [x] `quest_objective_progress` (migration V2) : étape courante, compteurs
    et état persistés, rechargés à la reconnexion
-   [x] Commandes `/quest list|accept|progress|abandon|complete`
-   [x] Permissions distinctes `rpgquest.quest` (joueur) / `rpgquest.admin`
-   [x] Messages MiniMessage configurables (`messages.yml`)

## Dialogues (fait)

-   [x] Modèles immuables (`DialogueDefinition`, nœuds, choix, conditions,
    actions) — réutilisent `LocalizedText`/`QuestState` de `quest.model`
-   [x] 4 types de conditions (QUEST_STATE, HAS_ITEM, HAS_PERMISSION,
    VARIABLE_EQUALS) et 9 types d'actions (START_QUEST, ADVANCE_QUEST,
    TURN_IN_QUEST, GIVE_ITEM, TAKE_ITEM, SET_VARIABLE, RUN_SAFE_COMMAND,
    OPEN_DIALOGUE, CLOSE)
-   [x] `DialogueLoader` : validation par fichier (dont liste blanche des
    commandes), un fichier invalide ne bloque pas les autres, doublons
    d'id et références `OPEN_DIALOGUE` manquantes détectés entre fichiers,
    **détection de boucles entre dialogues** (les boucles `next` internes à
    un même dialogue restent autorisées)
-   [x] `DialogueRenderer` derrière une interface : `ChatDialogueRenderer`
    (texte cliquable, par défaut) et `PaperDialogRenderer` (API Dialog
    native de Paper, marquée expérimentale — désactivée par défaut)
-   [x] `DialogueSessionEngine` : conditions/actions évaluées de façon
    asynchrone, sessions en mémoire uniquement (pas persistées)
-   [x] `/dialogue open <joueur> <dialogueId>` (`rpgquest.admin`) + clic sur
    une entité nommée
-   [x] Dialogue d'exemple du garde (propose/refuse/explique la récompense)

## Journal de quêtes (fait)

-   [x] `/quests` : inventaire paginé (54 slots, 1 ligne de chrome + 45 slots
    de contenu) avec onglets **Actives**/**Disponibles**/**Terminées**
-   [x] Icône configurable par quête (`icon:` dans le YAML, `BOOK` par
    défaut) ; lore MiniMessage : description, catégorie, état, étape et
    progression des objectifs, récompenses, prérequis (avec le nom des
    quêtes référencées)
-   [x] Clic gauche : ouvre une vue détail dédiée (icône, bouton retour,
    bouton suivre/ne plus suivre, bouton fermer)
-   [x] Clic droit : suit/ne suit plus la quête directement depuis la liste
    (étoile dans le titre de l'icône)
-   [x] Boutons précédent/suivant (liste), retour (détail), fermer (les deux)
-   [x] **Aucun** déplacement, vol ou duplication possible : tout clic ou
    drag touchant un inventaire du journal est annulé, quel que soit le
    type (gauche, droit, shift, double clic, touche numérique, drag)
-   [x] Bossbar Adventure optionnelle (`config.yml` → `journal.tracker-enabled`)
    pour la quête suivie, avec progression en direct
-   [x] Suivi persistant (stocké via `player_variables`, survit à une
    reconnexion) — indépendant du fait que la quête soit déjà acceptée
-   [x] Affichage rafraîchi uniquement en réaction à un changement réel de
    progression (`QuestProgressEngine.onProgressChanged`) : aucune tâche
    répétitive, aucun sondage
-   [x] Nettoyage à la déconnexion (session + bossbar) et à la désactivation
    du plugin (fermeture des inventaires ouverts, bossbars masquées)

## Objets personnalisés (fait)

-   [x] `CustomItemDefinition` immuable (id namespacé, type, matériau de
    base, nom + lore MiniMessage, rareté, model data et/ou item model,
    empilabilité, durabilité, attributs, enchantements, tags de gameplay,
    comportement spécial, restrictions de fabrication)
-   [x] 4 types (`WEAPON`, `TOOL`, `RESOURCE`, `QUEST_ITEM`)
-   [x] `ItemLoader` : validation par fichier, un fichier invalide ne
    bloque pas les autres, doublons d'id détectés entre fichiers
-   [x] `YamlCustomItemRegistry` : centralise la création (`create`) et
    l'identification (`identify`/`isCustomItem`/`resolve`) — l'id est
    stocké **uniquement** dans le PersistentDataContainer, jamais déduit
    du nom ou du lore
-   [x] 4 objets d'exemple générés automatiquement : `forest_blade`
    (arme), `miner_pickaxe` (outil), `spider_fang` (ressource),
    `refined_crystal` (objet de quête)
-   [x] `/customitem give|list` (`rpgquest.admin`) et `/customitem inspect`
    (`rpgquest.item`, identifie l'objet en main)
-   [x] Empilement autorisé/interdit réellement appliqué (`ItemMeta#setMaxStackSize`,
    quantité invalide refusée à la création plutôt que tronquée)

## Comportements d'armes et d'outils (fait)

-   [x] Armes (`combat:`) : dégâts et vitesse d'attaque en bonus additifs
    (compatibles Tranchant/attributs vanilla), chance de critique +
    multiplicateur, effet conditionnel à cooldown, message/particule
    configurables
-   [x] Outils (`tool:`) : bonus de vitesse de minage (attribut
    `MINING_EFFICIENCY`), blocs autorisés, consommation de durabilité
    personnalisée, bonus de récolte probabiliste, capacité spéciale à
    cooldown (clic droit)
-   [x] `CooldownManager` indexé par (UUID, id de capacité), purgé
    activement (tâche async 5 min) et à la déconnexion
-   [x] Toutes les valeurs validées au chargement : pas de NaN/infini, pas
    de valeurs négatives incohérentes (chance hors `[0,1]`, cooldown/durabilité
    négatifs, objet empilable + durable en même temps déjà rejeté)
-   [x] Anti-exploitation : main secondaire jamais consultée, armor stand
    exclu des cibles, dégâts de projectile jamais traités comme un coup
    d'arme, identification exclusivement PersistentDataContainer (jamais
    nom/lore)
-   [x] `@EventHandler(ignoreCancelled = true)` partout + vérification
    explicite `isCancelled()` (sauf `PlayerInteractEvent`, dont la méthode
    est dépréciée) : un événement annulé par un autre plugin n'est jamais traité
-   [x] Dégâts appliqués une seule fois (`event.setDamage` unique, jamais
    d'appel `entity.damage()` en plus)
-   [x] Logs debug activables via `config.yml` → `debug` (déjà existant,
    réutilisé), lus en direct donc appliqués immédiatement après
    `/rpgquest reload`

## Persistance (fait)

-   [x] `DatabaseManager` SQLite asynchrone (`plugins/RPGQuest/data.db`)
-   [x] Migration de schéma versionnée (`PRAGMA user_version`), idempotente
-   [x] Tables `player_profiles`, `player_variables`, `quest_progress`,
    `quest_objective_progress`
-   [x] Cache de profils limité aux joueurs connectés, invalidé à la déconnexion
-   [x] Commande `/rpgquest profile [joueur]`

## Ressources personnalisées et récolte (fait)

-   [x] `ResourceNodeDefinition` (`resource.model`) : id namespacé, blocs
    actif/épuisé vanilla, outils requis (vide = tout outil), temps de
    respawn, table de drops pondérée (`ResourceDrop` scellé :
    `CustomItemDrop`/`VanillaItemDrop`) — invariants validés par le record
    lui-même (blocs distincts, poids/quantités positifs)
-   [x] `ResourceNodeDefinitionParser`/`ResourceNodeLoader` : même
    conception à deux phases que `QuestLoader`/`ItemLoader` (un fichier
    invalide n'empêche pas les autres, id dupliqués rejetés entre fichiers)
-   [x] `ResourceNodeRegistry` (`PluginService`) : charge les types depuis
    `plugins/RPGQuest/resource-nodes/`, un exemple (`crystal_ore`, donnant
    `refined_crystal` ou du quartz brut selon une probabilité configurable)
    généré automatiquement au premier démarrage
-   [x] `/resourcenode create|remove|inspect` (`rpgquest.admin`) : agissent
    sur le bloc visé par le joueur
-   [x] Persistance des positions par monde (`resource_nodes`, migration
    V3, SQLite asynchrone, upsert par `(world, x, y, z)`)
-   [x] `ResourceNodeService` : récolte (vérifie outil requis, dépose un
    tirage pondéré, remplace le bloc par le bloc épuisé), respawn par
    balayage périodique **sans jamais charger de chunk de force** (respawn
    différé tant que le chunk n'est pas naturellement chargé, ou que le
    monde n'existe plus)
-   [x] Anti-exploitation : `@EventHandler(ignoreCancelled = true)` +
    vérification explicite `isCancelled()`, nœud marqué épuisé de façon
    synchrone avant tout traitement (anti double-cassage simultané), bloc
    physique modifié manuellement détecté et ignoré plutôt que deviné
-   [x] Tests : récolte valide, mauvais outil, bon outil, nœud en cooldown,
    événement annulé, double cassage simultané, chunk déchargé au respawn,
    monde supprimé au respawn, respawn différé après redémarrage simulé,
    création sur type inconnu / position déjà occupée, suppression

## Recettes personnalisées, resource pack et intégration RPG complète (fait)

-   [x] `crafting.model` (`RecipeDefinition` scellé : `ShapedRecipeDefinition`/
    `ShapelessRecipeDefinition`, `RecipeResult`/`RecipeIngredient` scellés)
    — invariants validés par les records (motif cohérent, total shapeless
    ≤ 9)
-   [x] `RecipeDefinitionParser`/`RecipeLoader` (deux phases, mêmes
    conventions que les autres chargeurs)
-   [x] `YamlCraftingRegistry` (`PluginService`) : résout les objets
    personnalisés référencés via le registre d'objets, enregistre de
    vraies recettes Bukkit (`Bukkit.addRecipe`), désenregistre proprement
    au `reload()`
-   [x] Ingrédients personnalisés vérifiés par PersistentDataContainer
    (`RecipeChoice.ExactChoice` sur l'objet canonique) + garde en
    profondeur (`RecipeCraftGuardListener` sur `PrepareItemCraftEvent`,
    couvre clic simple/shift-clic/recette automatique par construction)
-   [x] 3 recettes d'exemple : `forest_blade_recipe` (façonnée),
    `refined_crystal_recipe` (sans forme), `miner_pickaxe_recipe` (sans
    forme, amélioration)
-   [x] `resource-pack/` (pack.mcmeta + modèles JSON), tâches Gradle
    `buildResourcePack`/`resourcePackSha1` (zip reproductible + SHA-1)
-   [x] `config.yml` → `resource-pack.required` ; `ResourcePackListener`
    (envoi à la connexion, gestion accepté/refusé/échec/succès, jamais de
    déconnexion automatique)
-   [x] Parcours RPG complet jouable de bout en bout : dialogue (garde) →
    quête `crystal_hunt` → combat (araignées, `spider_fang` via
    `SpiderFangDropListener`) → récolte (`crystal_ore`) → fabrication
    (`forest_blade_recipe`) → remise (`TALK_TO_NPC`) → récompense
    (`miner_pickaxe` via `COMMAND`) — couvert par
    `CrystalHuntIntegrationTest`, qui exerce le plugin réellement démarré
-   [x] Tests : validation stricte des ingrédients personnalisés (inconnu
    → recette rejetée seule), craft normal, recette inconnue, resource
    pack absent (démarrage propre), configuration resource pack invalide
    (déjà couvert + étendu pour `required`), cycle métier complet
    simulable

## Serveur local intégré au workspace (fait)

-   [x] `runServer` (déjà présent depuis l'étape 1) confirmé sur la même
    version que `paper-api` (1.21.11)
-   [x] `.gitignore` : `run/` déjà ignoré dans son intégralité (mondes,
    logs, `data.db` locale, EULA, fichiers temporaires)
-   [x] `.vscode/tasks.json` (committé) : tâches « Gradle: clean build »,
    « Gradle: test », « Gradle: runServer »
-   [x] `docs/LOCAL_SERVER.md` : premier lancement, EULA, cycle dev,
    persistance, pourquoi jamais `/reload` (vanilla)
-   [x] Vérifié réellement dans cet environnement : démarrage complet sans
    stack trace, tous les services RPGQuest actifs, ressources embarquées
    chargées sur un vrai serveur Paper (voir `docs/LOCAL_SERVER.md`)

## Outil admin d'aplatissement de terrain (fait)

-   [x] `/rpgadmin flatten <rayon> [hauteur]|confirm|cancel|undo`
    (`rpgquest.admin.world`, joueur uniquement — jamais la console)
-   [x] `AdminFlattenConfig` (`config.yml` → `admin.flatten`) : rayon max,
    forme par défaut (carré/cercle), matériaux de surface/sous-couche,
    profondeur de sous-couche, hauteur de nettoyage au-dessus, délai
    d'expiration de confirmation, blocs par tick, mondes interdits
-   [x] `FlattenService` : aperçu pur (aucune écriture) avec estimation de
    colonnes/blocs, confirmation à expiration, traitement par lots via
    tâche répétée (jamais tout d'un coup, jamais de gel), un bloc déjà
    correct n'est jamais réécrit, annulation unique (`undo`) enregistrant
    l'état d'origine de chaque bloc réellement modifié
-   [x] Tests : rayon valide/invalide/hors limite, hauteur hors limites du
    monde, estimation carré vs cercle, aperçu expiré, opération déjà
    active, annulation (aperçu en attente et opération en cours),
    traitement multi-tick vérifié, undo disponible/indisponible/refusé
    pendant une opération, configuration invalide (rayon, forme,
    matériau non-bloc, valeurs négatives)
-   [x] `docs/ADMIN_FLATTEN.md`

## Village central et safe zone (fait)

-   [x] `zone.model` : `ZoneDefinition` (cuboïde, correct par construction)
    + `ZoneFlags` (7 permissions bloquées par défaut, 5 autorisées par
    défaut dont conteneurs publics bloqué — décision documentée)
-   [x] `ZoneDefinitionParser`/`ZoneLoader` (deux phases + rejet des
    chevauchements entre fichiers, même monde uniquement)
-   [x] `ZoneRegistry` (`PluginService`) : charge/persiste
    `plugins/RPGQuest/zones/*.yml`, `create()`/`delete()` écrivent
    directement sur disque puis rechargent, index par monde pour une
    vérification bon marché par événement
-   [x] `/rpgadmin zone wand|create|delete|list|info`
    (`rpgquest.admin.world`) — sélection par outil PDC (jamais par nom)
-   [x] `ZoneProtectionListener` : PvP (mêlée + projectiles), casse/pose de
    bloc, explosions (destruction de blocs seulement, pas l'événement
    entier), feu, lave, pistons traversant la frontière, spawn hostile
    naturel, portes/boutons/leviers/conteneurs, bypass admin sur l'acteur
    direct, affichage entrée/sortie sans sondage (`PlayerMoveEvent` filtré
    aux changements de bloc)
-   [x] Zone d'exemple `central_village` générée automatiquement
-   [x] Tests : intérieur/extérieur/frontière, zone invalide, chevauchement
    (fichiers et créations successives), pas de chevauchement inter-monde,
    id dupliqué, rechargement, persistance réelle sur disque, protection
    bloc/PvP/explosion à l'intérieur/extérieur, bypass, événement déjà
    annulé laissé intact, aucune exception sur un monde sans zone
-   [x] `docs/SAFE_ZONE.md`

## Économie et marchands PNJ (fait)

-   [x] `database.WalletRepository` (pure JDBC) : table `wallets`
    (portefeuille par joueur, créé paresseusement) et `transactions`
    (journal d'audit) — migration V4
-   [x] Débit/crédit/paiement/réglage admin réellement atomiques (une seule
    transaction JDBC par opération, `commit`/`rollback` explicites), montant
    invalide (≤ 0, ou négatif pour un réglage) rejeté avant la base,
    dépassement de capacité détecté (`Math.addExact`)
-   [x] `economy.EconomyService` : couche typée au-dessus du repository
    (`TransactionType`, `PayOutcome`), forme délibérément compatible avec
    une future intégration Vault (voir `docs/ECONOMY.md`)
-   [x] `/money` (solde), `/money pay <joueur> <montant>` (`rpgquest.money`,
    joueurs en ligne uniquement), `/money admin give|take|set`
    (`rpgquest.admin`)
-   [x] `economy.merchant.model` (`MerchantDefinition`/`MerchantOffer`,
    correct par construction) + `MerchantDefinitionParser`/`MerchantLoader`
    (deux phases, mêmes conventions que `ItemLoader`) + `YamlMerchantRegistry`
    (`plugins/RPGQuest/merchants/*.yml`, exemple `village_merchant` généré
    automatiquement)
-   [x] Offre : vente **ou** achat, objet vanilla **ou** personnalisé,
    quantité/prix, conditions cumulatives optionnelles (permission, quête +
    état, niveau d'expérience vanilla)
-   [x] Nouvelle action de dialogue `OPEN_MERCHANT` — seule porte d'entrée
    d'un marchand (pas de clic direct sur un PNJ, pour ne pas dupliquer le
    mécanisme d'identification déjà utilisé par les quêtes/dialogues) ;
    ferme la session de dialogue avant d'ouvrir la vitrine
-   [x] `MerchantTradeService` : vitrine en inventaire (même protection
    anti-vol/duplication que le journal de quêtes), achat = débit avant
    remise de l'objet, vente = retrait synchrone de l'objet avant le crédit
    asynchrone (aucune duplication possible sur double-clic)
-   [x] `/merchant reload|validate|list` (`rpgquest.admin`)
-   [x] Tests : transactions atomiques (dont double-débit concurrent),
    parsing/chargement des marchands, achat/vente (fonds/stock
    suffisants/insuffisants), permission/niveau/quête non satisfaits,
    marchand inconnu, action `OPEN_MERCHANT` (parsing + ouverture réelle
    depuis un choix de dialogue)
-   [x] `docs/ECONOMY.md`, `MERCHANT_FORMAT.md`

## Marché entre joueurs (fait)

-   [x] `database.MarketRepository` (pure JDBC) : table `market_listings`
    (offre = objet complet en dépôt, sérialisé via
    `ItemStack#serializeAsBytes()` — méta et PDC d'un objet personnalisé
    compris, aucune dépendance au registre d'objets) — migration V5
-   [x] Trois opérations atomiques (une seule transaction JDBC chacune,
    même discipline que `WalletRepository`) : `claim` (réservation
    `ACTIVE → SOLD`, jamais deux fois la même offre), `cancel` (annulation
    restreinte au vendeur), `reactivate` (remise à disposition après un
    débit refusé)
-   [x] `economy.market.MarketService` : achat en deux temps imposé par
    l'absence de prix connu à l'avance (réservation d'abord, débit
    ensuite, réactivation si le débit échoue) — jamais de double-vente ni
    d'argent perdu
-   [x] `/market` (vitrine paginée, toutes offres/tous vendeurs), clic sur
    l'offre d'un autre joueur = achat, clic sur sa propre offre =
    annulation + restitution de l'objet
-   [x] `/market sell <prix>` (vend la pile en main), `/market cancel <id>`
    (alternative texte), `/market admin list` (`rpgquest.admin`, lecture
    seule)
-   [x] Vendeur crédité même hors ligne (aucune dépendance à une session
    Bukkit active, contrairement à `/money pay`)
-   [x] Tests : réservation atomique (dont échec d'une seconde réservation
    concurrente), réactivation, annulation (vendeur/tiers/offre déjà
    vendue), vente réelle (retrait de l'objet en main), achat réel (fonds
    suffisants/insuffisants), clic sur sa propre offre
-   [x] Section « Marché entre joueurs » de `docs/ECONOMY.md`, sous-section
    `economy.market` de `docs/ARCHITECTURE.md`

## Portails et téléportation (fait)

-   [x] `travel.model` : `Destination` (position nommée réutilisable) et
    `PortalDefinition` (zone d'activation cuboïde + destination par id +
    conditions), correctes par construction
-   [x] Deux registres YAML indépendants (`DestinationLoader`/
    `YamlDestinationRegistry`, `PortalLoader`/`YamlPortalRegistry`) — même
    conception à deux phases que `ZoneLoader` ; `PortalLoader` rejette en
    plus les portails dont la zone d'activation se chevauche (même monde)
-   [x] `/rpgadmin portal create|delete|list|info` (réutilise l'outil de
    sélection `wand` déjà existant) et `setdestination <id> <destinationId>`
    (capture la position exacte de l'administrateur, crée ou met à jour la
    destination, puis relie le portail)
-   [x] `travel.PortalService` : détection d'entrée dans une zone
    d'activation (même filtrage `PlayerMoveEvent` que les zones protégées),
    canalisation à délai (actionbar de progression, même patron « tâche
    répétée + annulation » que `FlattenService`), annulée sur mouvement
    au-delà d'une tolérance, dégâts, ou déconnexion
-   [x] Conditions d'accès cumulatives optionnelles : permission, quête +
    état, niveau d'expérience vanilla, coût en pièces (`economy.EconomyService`)
-   [x] Sécurité de destination : monde absent détecté proprement, chunk
    chargé à la demande (jamais de force permanente), recherche de
    position sûre (aucun bloc solide aux pieds/tête, sol solide sous les
    pieds, aucun bloc dangereux) — aucun joueur ne peut être téléporté
    dans le vide, la lave ou un bloc solide
-   [x] Aucun débit tant que le succès n'est pas garanti : le coût n'est
    débité qu'après résolution et vérification de sécurité de la
    destination, juste avant la téléportation elle-même
-   [x] Cooldown par joueur/portail persisté (`portal_cooldowns`, migration
    V6), chargé en mémoire à la connexion (jamais de requête base depuis
    `PlayerMoveEvent`) — survit à une reconnexion
-   [x] Tests : conditions non remplies (permission/niveau/quête),
    cooldown, coût (fonds insuffisants/suffisants, débit uniquement au
    succès), monde de destination absent, destination dangereuse,
    annulation par mouvement/dégâts/déconnexion, rechargement du registre
-   [x] `docs/TRAVEL.md`

## Claims de terrain (fait)

-   [x] `claim.model.Claim` (correct par construction, propriétaire par
    UUID, membres `Set<UUID>`) + `ClaimFlags` (seule permission
    réellement configurable : `allowPublicRedstone`)
-   [x] Persistance SQLite (`claims`/`claim_members`, migration V7) plutôt
    que YAML — profil d'usage joueur (créé/modifié fréquemment,
    appartenance mutable), même choix que le marché entre joueurs ;
    `database.ClaimRepository` réutilise `Claim` directement (aucune
    dépendance Bukkit à séparer)
-   [x] Outil de sélection dédié (`ClaimSelectionService`/`ClaimWandListener`,
    clé PDC `rpgquest:claim_wand`, distinct de l'outil de zone)
-   [x] `/claim wand|create <id>|delete|info|trust <joueur>|untrust <joueur>|list|flag redstone <true|false>`
    (`rpgquest.claim`) — toutes les sous-commandes sauf `create`/`list`
    opèrent sur le claim où le joueur se trouve
-   [x] Refus à la création : chevauchement avec un autre claim, avec une
    zone protégée, trop près d'un portail, taille/nombre maximal dépassé
    (`config.yml` → `claims`) — aucun claim invalide n'est jamais persisté
-   [x] Seams `effectiveMaxWidth`/`effectiveMaxHeight`/`effectiveMaxClaims`
    (prennent déjà un `Player`) préparés pour une future politique liée à
    la progression, sans rien implémenter — aucun avantage payant à cette
    étape
-   [x] `ClaimProtectionListener` : blocs, conteneurs, animaux (`Animals`),
    armor stands (`PlayerArmorStandManipulateEvent`), redstone
    configurable (boutons/leviers/portes/dalles de pression), explosions,
    pistons traversant la frontière — protection par UUID (propriétaire/
    membres), bypass `rpgquest.admin.world` (même permission que les
    zones protégées)
-   [x] Tests : chevauchements (claim/zone/portail), taille, nombre
    maximal, suppression, confiance/retrait, monde absent, protection
    indépendante du statut en ligne du propriétaire, frontière incluse,
    membre autorisé/non autorisé, explosion externe, piston traversant la
    frontière
-   [x] `docs/CLAIMS.md`

## Mobs spéciaux (fait)

-   [x] `mob.model` : `SpecialMobDefinition` (correct par construction,
    réutilise `resource.model.ResourceDrop` pour la table de drops) +
    `MobAbility` scellée (`StrongerExplosionAbility`,
    `ExplosiveOnAttackAbility`, `SplitOnHitAbility`)
-   [x] `SpecialMobDefinitionParser`/`SpecialMobLoader`/`SpecialMobRegistry`
    (même patron à deux phases que `ResourceNodeRegistry`) + quatre
    variantes d'exemple embarquées (`red_creeper`, `golden_creeper`,
    `creeper_pig`, `splitting_zombie`)
-   [x] `SpecialMobService` : upgrade au spawn naturel (`CreatureSpawnEvent`
    priorité HIGH, après les listeners de protection de zone), identification
    PDC uniquement (jamais le nom affiché), population trackée par
    définition (décomptée uniquement à la mort — `setRemoveWhenFarAway(false)`
    empêche tout despawn silencieux), redécouverte au chargement de chunk
-   [x] Écouteurs de capacités : `StrongerExplosionAbilityListener`
    (`ExplosionPrimeEvent`), `ExplosiveOnAttackAbilityService` (balayage
    périodique borné à la population réelle des variantes, pas à
    `World#getLivingEntities()`), `SplitOnHitAbilityListener` (profondeur en
    PDC + `max-children-per-hit` + `max-population` : aucune chaîne de
    division infinie)
-   [x] `/rpgadmin mob spawn <id>|list|inspect <id>|reload|metrics`
    (`rpgquest.admin.world`)
-   [x] Tests : identification PDC, probabilités (générateur injecté), drop
    unique, profondeur maximale de division, zone interdite, événement
    annulé, reload, variante non reconnue
-   [x] `SPECIAL_MOB_FORMAT.md`

## Progression RPG (fait)

-   [x] `progression.model` : `SkillType` (GLOBAL/COMBAT/MINING/FARMING/
    FISHING/EXPLORATION), `ProgressionCurve` (courbe géométrique
    configurable et validée, niveau jamais stocké séparément de l'XP
    totale), `AwardOutcome`/`XpGrantResult`
-   [x] `ProgressionConfig` (`config.yml` → `progression:`) : courbe,
    ratio de mirroir GLOBAL, limite de fréquence anti-farm, mode
    d'affichage, bascule XP vanilla, montants par source
-   [x] SQLite (migration V8) : `player_skills`/`xp_grants` (déduplication
    par (joueur, compétence, id d'événement))/`player_placed_blocks`
    (anti-farm), `ProgressionRepository`/`PlacedBlockRepository` — même
    conception transactionnelle que `WalletRepository`
-   [x] `ProgressionService` : octroi dédupliqué et limité en fréquence,
    mirroir GLOBAL automatique, cache en mémoire chargé à la connexion,
    affichage transitoire (action bar/bossbar configurable)
-   [x] Écouteurs : `CombatXpListener` (mort par un joueur, exclut
    spawner/division), `MiningXpListener` (exclut blocs posés par un
    joueur), `FarmingXpListener` (cultures mûres uniquement),
    `FishingXpListener`, `ExplorationXpListener` (première découverte de
    zone), `QuestCompletionXpListener` (bonus une fois par quête, en plus
    de l'éventuelle récompense XP vanilla de la quête)
-   [x] Hook de déblocage générique (`ProgressionService#hasLevel`),
    câblé concrètement dans `claim.ClaimService#effectiveMaxClaims`
-   [x] `/profile`, `/skills`, `/skills admin grant|set`
-   [x] Tests : calcul de niveau, valeurs maximales, XP négative refusée,
    événement dupliqué, bloc placé, mob de spawner, montée de plusieurs
    niveaux, reconnexion, migration de schéma
-   [x] `docs/PROGRESSION.md`

## Backpacks (fait)

-   [x] `entitlement.EntitlementService` (interface générique, mission
    point 11) + `database.EntitlementRepository` (`player_entitlements`,
    avantage identifié par une clé libre — le backpack en est le premier
    consommateur concret, sans boutique)
-   [x] `backpack.model.BackpackSize` (SMALL/MEDIUM/LARGE) + `BackpackConfig`
    (`config.yml` → `backpacks:`, lignes par palier configurables,
    matériaux interdits, palier de secours)
-   [x] SQLite (migration V9) : `backpacks` (contenu versionné),
    `backpack_overflow` (boîte de récupération), `backpack_audit`
    (journal d'anomalies) — `BackpackRepository`, redimensionnement en une
    seule transaction JDBC (contenu + surplus ensemble, jamais l'un sans
    l'autre)
-   [x] `backpack.ItemArraySerializer` : sérialisation maison d'un
    `ItemStack[]` complet (aucun précédent dans le projet), versionnée
    indépendamment du schéma SQL
-   [x] `BackpackService` : une seule instance d'inventaire vivante par
    joueur (jamais d'ouverture simultanée divergente), sauvegarde à la
    fermeture/déconnexion/arrêt (LIFO des services, même mécanisme que
    `MarketService`/`MerchantTradeService`), upgrade/downgrade unifiés
    (compactage + surplus vers la récupération), objet d'ouverture
    identifié uniquement par PDC
-   [x] `BackpackListener` : lecture-écriture protégée (contrairement à la
    vitrine en lecture seule du marché) — imbrication et objets interdits
    bloqués sur tous les vecteurs classiques (pose, échange curseur,
    shift-clic, barre de raccourcis, glisser-déposer), jamais un clic
    légitime annulé
-   [x] `/backpack`, `/backpack recover [numéro]`,
    `/backpack admin grant|revoke`
-   [x] Tests : création, persistance, upgrade, downgrade, ouvertures
    simultanées, backpack imbriqué, crash simulé, inventaire plein, accès
    sans droit
-   [x] `docs/BACKPACKS.md`

## Portail web (fait)

-   [x] Module Gradle séparé `web-api/` (aucune dépendance vers Paper, aucun
    accès direct à `data.db` — mission points 1-2)
-   [x] `config.yml` → `web-export:` (désactivé par défaut) +
    `web.WebSnapshotWriter` : export périodique et atomique de
    `snapshot.json` (statut, joueurs, classements, catalogue, annonces),
    aucune lecture bloquante sur le thread principal (point 4)
-   [x] `ProgressionRepository.topPlayers` (classement par piste, lecture
    seule, jamais interrogé depuis un chemin de jeu synchrone)
-   [x] `webapi.json.Json` : codec JSON maison (lecture + écriture), aucune
    dépendance externe
-   [x] `SnapshotStore` : cache en mémoire, détection du mode dégradé
    (snapshot absent ou périmé — jamais une erreur, point 9)
-   [x] API authentifiée `/api/status|players|leaderboards|catalog|announcements`
    (jeton serveur-à-serveur, comparaison en temps constant — point 5) +
    rate limiting par IP et journalisation sans secret (point 6)
-   [x] Site public minimal (`/`, `/status`, `/leaderboards`, `/wiki`), sans
    authentification, sans paiement ni connexion joueur ni écriture (point
    11)
-   [x] Jeton exclusivement via la variable d'environnement
    `RPGQUEST_WEB_API_TOKEN`, jamais dans un fichier versionné (point 7)
-   [x] Tests : snapshot absent/périmé, jeton manquant/invalide, requête
    malformée, rate limit, route inconnue, données vides, caractères
    Unicode, page publique sans authentification
-   [x] `docs/WEB_API.md`

## Boutique web (fait)

-   [x] Catalogue produit (`web-api/products.json`) séparé de l'avantage
    technique (`plugins/RPGQuest/store-products/*.yml`) — point 1
-   [x] Produits initiaux : `small_backpack`, `upgrade_medium`,
    `upgrade_large`, `vip_pass_test`, `cape_aurora` (cosmétique) — point 2
-   [x] `store.SandboxPaymentProvider` : simulation auto-hébergée d'un
    prestataire externe en mode test (webhook signé HMAC-SHA256), aucune
    donnée de carte bancaire jamais demandée ni stockée — points 3-4, 14
-   [x] `store.db` (web-api, séparée de `data.db`) : `orders`, `deliveries`,
    `webhook_events` (dédup par id d'événement) — points 5-6
-   [x] Idempotence : webhook rejoué et livraison réacquittée ignorés sans
    erreur (jamais une seconde livraison) — point 7
-   [x] Sondage périodique des livraisons en attente
    (`store.StoreDeliveryService`), couvre nativement un redémarrage ou une
    reprise après crash — point 8
-   [x] Authentification serveur-à-serveur : jeton porteur (site ↔ serveur
    de jeu) + signature HMAC (prestataire ↔ web-api) — point 9
-   [x] Gestion : joueur hors ligne, UUID inconnu (profil auto-créé),
    produit déjà possédé, upgrade, remboursement, révocation, échec
    temporaire (jamais acquitté, réessayé au sondage suivant) — point 10
-   [x] `/store history [joueur|uuid]` (`rpgquest.admin`) — point 11
-   [x] Journalisation sans donnée sensible (jeton/signature jamais loggés) — point 12
-   [x] Politique pay-to-convenience documentée, types d'avantage limités
    par construction (`BACKPACK_SIZE`/`ENTITLEMENT`, jamais un attribut de
    combat) — point 13
-   [x] Tests : webhook répété/signature invalide, livraison répétée,
    produit inconnu, joueur inconnu, upgrade, déjà possédé, remboursement,
    reprise après redémarrage, web-api indisponible
-   [x] `docs/STORE.md`

## Mod client prototype (fait)

-   [x] `client-mod/` : projet Gradle Fabric entièrement séparé, propre
    wrapper, jamais un sous-module du build racine, jamais empaqueté dans
    le jar Paper — point 1-2
-   [x] Fabric choisi après vérification réelle de compatibilité avec
    `1.21.11` (Fabric et NeoForge tous deux disponibles, comparaison
    documentée) — point 3
-   [x] Protocole de handshake `rpgquest:handshake_hello` (magic + version
    de protocole, jamais de chaîne pour éviter tout désaccord de format) —
    point 4
-   [x] Prototype minimal : bloc/objet `crystal_display`, canal cosmétique
    `rpgquest:mob_variant_tag` (variante de mob, action bar), HUD de
    statut — point 5
-   [x] Aucune dépendance vers un service de jeu dans `ModCompatService` :
    structurellement impossible d'accorder progression/drops/économie/
    droits/achats depuis le mod — points 6-7
-   [x] Détection `COMPATIBLE`/`WRONG_VERSION`/`NO_MOD` (délai configurable)
    — point 8
-   [x] Politique `client-mod.require-mod` (false par défaut : vanilla
    autorisé avec repli ; true : mod obligatoire) — point 9
-   [x] `docs/CLIENT_MOD.md` (installation, mise à jour, compatibilité) —
    point 10
-   [x] Tests : client compatible, version incorrecte (avec/sans
    obligation), client vanilla après délai, paquet réseau invalide,
    reconnexion, tentative de falsification, diffusion cosmétique
    conditionnelle
-   [x] `client-mod/gradlew.bat build` compile et remape contre le vrai
    jar client Minecraft `1.21.11` (Fabric Loom)

## MVP

-   [x] Architecture
-   [x] SQLite
-   [x] Moteur de quêtes (définitions YAML + progression des joueurs)
-   [x] Dialogues
-   [x] Journal de quêtes
-   [x] Objets personnalisés
-   [x] Armes
-   [x] Outils
-   [x] Ressources
-   [x] Craft
-   [x] Resource pack
-   [x] Zones protégées
-   [x] Économie et marchands PNJ
-   [x] Marché entre joueurs
-   [x] Portails et téléportation
-   [x] Claims de terrain
-   [x] Mobs spéciaux
-   [x] Progression RPG
-   [x] Backpacks
-   [x] Portail web (API read-only + site minimal)
-   [x] Boutique web (achats sandbox, livraison idempotente)
-   [x] Mod client prototype (Fabric, séparé)

## Plus tard
-   [ ] Corriger `plugin.yml` : le texte d'usage de `/rpgadmin`
    (`commands.rpgadmin.usage`) omet la sous-commande `mob`
    (`spawn|list|inspect|reload|metrics`), qui existe pourtant réellement
    dans le code (`RpgAdminCommand`) — n'affecte que le message d'aide
    Bukkit affiché sur erreur de syntaxe, pas le comportement réel ; voir
    [docs/RPGQUEST_BIBLE.md](docs/RPGQUEST_BIBLE.md) section 2.
-   [ ] Prestataire de paiement réel (Stripe/PayPal en mode test)
-   [ ] Connexion joueur sur le portail web
-   [ ] Contenu client réel synchronisé serveur (nécessite un serveur Fabric/NeoForge compagnon ou un système de correspondance d'identifiants)
-   [ ] PNJ avancés
-   [ ] Métiers
-   [ ] Donjons
-   [ ] Boss
-   [ ] Factions
