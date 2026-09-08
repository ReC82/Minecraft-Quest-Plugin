# État actuel du projet

Snapshot de ce qui est **actuellement implémenté** dans RPGQuest — une vue d'ensemble rapide, pas
une référence détaillée (voir [RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md) et [INDEX.md](INDEX.md) pour
le détail par système). À mettre à jour à chaque étape livrée qui ajoute/change un système.

## Systèmes implémentés

- **Quêtes** — définitions YAML, 7 types d'objectifs, prérequis, récompenses, progression
  persistée par joueur (`quest.progress.QuestProgressEngine`).
- **Storyline** *(progression automatique ajoutée cette étape)* — conteneur logique ordonné de
  quêtes existantes, désormais connecté au moteur de quête : une Story `ACTIVE` avance toute seule
  (démarrage/avancement/fin automatiques, sans commande joueur), état `NOT_STARTED`/`ACTIVE`/
  `COMPLETED` + position courante par joueur, commandes admin/debug uniquement
  (`/rpgadmin story info|start|advance|complete|reset|resetwithquests`) — voir
  [storylines.md](storylines.md) et [ADMIN_TEST_SHORTCUTS.md](ADMIN_TEST_SHORTCUTS.md).
- **Dialogues** — graphes de nœuds PNJ (Citizens et entités vanilla), conditions et actions par
  choix, rendu via Paper Dialog.
- **NPC / Citizens** — identité logique RPGQuest découplée du nom affiché de l'entité.
- **Guide « centre d'aide » + journal du Libraire** *(issue #11)* — le dialogue `guide.yml` est un
  centre d'aide structuré (nœud `help_menu` + un nœud par mécanique : quêtes, journal, Wild, claims,
  marchands, « à qui parler »), orientation vers les PNJ **textuelle** (nom + rôle + explication).
  Structure multi-Hub en données : `hub-guides/*.yml` (`hub.HubGuideRegistry`, exemple
  `hub_depart.yml`) mappe chaque Hub → dialogue d'aide + accueil/spécialité/orientations ; diagnostic
  admin `/rpgadmin guide list|info <hub>` (lecture seule) — voir [HUB_GUIDE.md](HUB_GUIDE.md). Le
  Libraire remet `rpgquest:journal_quetes` (garde `LACKS_CUSTOM_ITEM` → jamais de doublon, soulbound
  → jamais perdu) ; **clic droit ouvre désormais la GUI** `QuestJournalService` (deux onglets
  « en cours » / « terminées »), plus le résumé chat (ancien `QuestJournalBookService` supprimé).
- **Zones protégées** — cuboïdes avec flags configurables (PvP, casse, explosions, etc.),
  outil de sélection dédié (indépendant de WorldEdit, voir la note ci-dessous).
- **Portails** — `/rpgadmin portal` (canalisation, coût, cooldown, conditions quête/niveau) et
  `/rpgadmin worldportal` (téléportation instantanée entre mondes, sans coût).
- **Mondes** — création/chargement de mondes supplémentaires, règles du monde Hub (jour/météo
  permanents), séparation Hub/wild.
- **Claims** — terrains protégés créés par les joueurs eux-mêmes, confiance par UUID ; monde
  résidentiel `claims` réellement pacifique (tout dégât joueur annulé, tout mob hostile empêché et
  nettoyé, Nether bloqué en sortie) ; frontière visualisée par particules (propriétaire uniquement,
  automatique à l'entrée ou volontaire via l'Acte réutilisé), faisceau dense (DUST + END_ROD)
  hors du claim ; retour au Hub sans commande via la Pierre de retour (mécanique générique de
  voyage par objet, `travel.ItemTravelService`).
- **Parcours Claims cohérent (issues #21/#22/#23)** — le portail Hub → `claims` est réservé aux
  joueurs qui ont réellement débloqué leur premier terrain (`CLAIM_TIER_1 == "true"` accordé par la
  dernière quête de l'histoire principale, ou claim déjà existant) : `claim.ClaimWorldAccessGuard`
  (composé avec l'avertissement Wild via `travel.CompositeWorldPortalEntryGuard`) refuse l'entrée
  sans téléporter et oriente vers Jo/le Guide ; seul le bypass explicite `rpgquest.admin.world`
  passe outre. `claim.ClaimWorldSafetyListener` garantit qu'aucun joueur ne reste coincé : Pierre
  de retour donnée automatiquement à l'arrivée si absente, joueur non éligible arrivé autrement
  (`/tp`, reconnexion) renvoyé au Hub. Les dialogues du Guide (`help_claims`) et de Jo reflètent
  exactement ce prérequis, et Jo adapte son texte aux 3 états (non débloqué / débloqué sans claim /
  claim existant) grâce à `negate: true` sur une condition de dialogue (`dialogue.model.NegatedCondition`).
  Prérequis **opérationnels** (à provisionner par serveur, non portés par le code) : les 4 PNJ Citizens
  liés `guide` / `libraire` / **`guard`** / `jo` doivent exister physiquement (`/rpgadmin npc tag <id>`),
  `dialogues/guard.yml` doit contenir la branche `crystal_hunt`, et un World-Portal `world_hub → claims`
  doit être configuré. Sans PNJ `guard`, `first_steps` et `crystal_hunt` sont indémarrables et
  `CLAIM_TIER_1` n'est jamais accordé. Toute validation #21/#22 se fait avec un compte **non opéré**
  (`rpgquest.admin.world`, défaut `op`, contourne les deux gardes — décisions journalisées
  `[claims-access]` / `[claims-safety]`). Voir `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1b.
- **Boucle joueur Hub ↔ Wild** — Journal des quêtes (`rpgquest:journal_quetes`, donné par le
  Libraire, clic droit → GUI deux onglets, voir ligne « Guide / journal » ci-dessus) ; Rune de
  rappel (`rpgquest:rune_rappel`,
  Wild → Hub, canalisation 10 s, cooldown 30 min persistant, remise à chaque nouveau joueur, filet
  via le Guide) ; avertissement compact cliquable [Continuer]/[Annuler] à l'entrée du Wild sans
  Rune ; Waystones générées paresseusement et de façon déterministe dans le Wild
  (`waystone.WaystoneService`), découverte individuelle par joueur, retour au Hub par canalisation
  courte. Système **soulbound générique** (`item.SoulboundItemService`) : un seul écouteur anti-perte
  pour tous les objets permanents (Acte, Pierre de retour, Journal, Rune).
- **Reset admin « nouveau joueur »** — `/rpgadmin player resetnew <joueur> confirm`
  (permission `rpgquest.admin.world`, console OK, online **ou** offline) : remet l'état RPGQuest
  d'un seul joueur à l'équivalent « jamais joué » (quêtes, Stories, variables/unlocks dont
  `CLAIM_TIER_1`, progression RPG, découvertes de Waystones, cooldowns persistants, claim principal
  + objets RPGQuest de l'inventaire). Ne touche jamais `data.db` entier, les autres joueurs, le
  profil/UUID, les mondes, les blocs, les Waystones globales. Variante **`preview`** *(issue #8)* :
  `/rpgadmin player resetnew <joueur> preview` — dry-run qui liste, catégorie par catégorie, ce qui
  serait effacé, **sans aucune écriture** (`PlayerResetService#previewReset`). Voir
  [ADMIN_PLAYER_RESET.md](ADMIN_PLAYER_RESET.md).
- **Raccourcis d'administration / test quêtes & stories** *(issue #36)* — atteindre rapidement une
  étape précise sans rejouer le gameplay, en réutilisant les services métier (jamais d'écriture
  directe en base). `/rpgadmin quest start|complete|reset <joueur> <quest-id>` (+ `force` pour
  ignorer les prérequis au `start`) ; `/rpgadmin story advance|complete <joueur> <storyId>`
  (`advance` = complète l'étape courante et accepte la suivante, en indiquant laquelle tester ;
  `complete` = toute la story, dans l'ordre) ; `/rpgadmin player variable get|set <joueur> <clé>
  [valeur]`. `complete`/`advance` appliquent les récompenses (dont `VARIABLE`, ex. `CLAIM_TIER_1`)
  **une seule fois** (garde de `QuestProgressEngine.forceComplete`). `quest reset` rend la quête
  rejouable mais **n'annule pas** les récompenses déjà accordées (limite documentée). Permission
  `rpgquest.admin.world` ; `variable set` exige en plus `rpgquest.admin.debug` (défaut `op`) et est
  journalisée. `quest start/complete` et `story advance/complete` exigent une cible **en ligne**.
  Voir [ADMIN_TEST_SHORTCUTS.md](ADMIN_TEST_SHORTCUTS.md).
- **Items / équipements personnalisés** — objets marqués PDC, comportements d'arme/outil,
  recettes de craft dédiées.
- **Ressources** — nœuds de ressources rechargeables.
- **Mobs spéciaux** — définitions avec capacités (explosion renforcée, division au coup...).
- **Économie / Marché / Marchands** — portefeuille, transactions, hôtel des ventes, offres PNJ.
- **Backpacks** — paliers via avantages (entitlements), boîte de récupération.
- **Progression** — compétences, XP, niveaux, courbe configurable.
- **Boutique web** — catalogue, commandes, livraisons idempotentes (voir `web-api/`).
- **Compatibilité mod client** — détection de handshake, politique configurable pour les clients
  vanilla.
- **Bridge d'administration** (`web.admin`, issue #37) — `GET /admin/v1/health` authentifié par
  jeton porteur, bind interne, fail-closed ; consommé par le Control Panel (« PlugAdmin »,
  déployé sur `https://plugadmin.lodylands.com`, issue #44).
- **Agent sortant PlugAdmin** (`web.agent`, issue #51) — le plugin ouvre une connexion **HTTPS
  sortante** vers PlugAdmin (heartbeat régulier réutilisant `HealthSource`, + file d'actions
  whitelistées). **Inerte par défaut** : nécessite `plugins/RPGQuest/plugadmin-agent.properties`
  (hors Git) avec `enabled=true` + `base-url` + `agent-id` + `token`. Asynchrone, backoff, aucun
  impact gameplay si PlugAdmin est down. Voir [control-panel/AGENT.md](control-panel/AGENT.md).
- **Outillage admin du Control Panel** *(branche `feat/control-panel-admin-tools`)* — l'agent
  expose désormais des **actions métier whitelistées** au-delà de la lecture : `player.list`,
  `quest.list` / `quest.player.status`, `story.list` / `story.player.status`, `item.list`,
  `player.resetnew.preview` (lectures) ; `player.item.give`, `quest.start|complete|reset`,
  `story.advance|complete`, `player.variable.set`, `player.resetnew.confirm` (mutations). Chacune
  est adossée à un **service métier existant** via `BukkitAgentActions` (`QuestProgressEngine`,
  `StoryService`, `YamlCustomItemRegistry`, `PlayerResetService`) — jamais une commande texte,
  jamais de SQL, mutations replacées sur le thread principal. Côté panel : pages **Joueurs /
  Quêtes / Stories** (catalogues avec titre lisible d'abord, état par joueur, raccourcis admin),
  liste blanche `AgentActionCatalog` + validation à 3 couches, confirmation obligatoire pour les
  mutations, audit. Le tableau des actions se rafraîchit tout seul (issue #65,
  `/assets/panel.js` + `/agents/actions.json`) : premier relevé immédiat après soumission,
  démarrage sur le compteur serveur **ou** sur un statut non terminal encore visible, arrêt dès
  qu'aucune action n'est en cours. Voir [control-panel/AGENT.md](control-panel/AGENT.md) §7.
- **Protocole `quest.list` structuré** *(branche `feat/control-panel-admin-tools`, issues #78 /
  #75)* — `quest.list` transporte désormais, **en plus** des chaînes legacy, des objectifs et
  récompenses **structurés** (`steps[].objectiveDetails` = `{kind, target, amount, raw}` ;
  `rewardDetails` = `{kind, amount, target, value, command, raw}`, commande **non tronquée**) et le
  PNJ donneur (`giverId`, si la quête déclare `giver:` dans son YAML). Le panel consomme la
  structure en priorité (`ObjectiveText`, `RewardText.fromSummary`) et retombe sur le reparse de
  chaînes uniquement pour un agent pas encore redéployé. Champs legacy conservés = dépréciés. Un
  nouveau champ optionnel `giver:` existe dans le format de quête (`QuestDefinition` / `QUEST_FORMAT.md`).
- **Page Control Panel `/npcs` (V1)** *(branche `feat/control-panel-admin-tools`)* — l'entrée « PNJ »
  du menu n'est plus « à venir ». Nouvelle action agent **`npc.list`** (lecture seule, whitelistée) :
  `NpcCatalog` (pur, `com.lodygames.rpgquest.npc`) croise les liaisons Citizens
  (`NpcBindingRepository`), les dialogues `rpgquest:<id>`, le `giver:` des quêtes et les objectifs
  `TALK_TO_NPC` pour produire un catalogue de PNJ RPGQuest avec : nom lisible, id RPGQuest/Citizens,
  dialogue associé, quêtes données/référencées, **ids canoniques** connus (préparation #66) et
  **anomalies de configuration** (id référencé sans PNJ tagué, tag orphelin avec suggestion
  `garde`→`guard`, doublon de liaison…). Aucune lecture du monde : position, monde et PNJ Citizens
  *non tagués* sont hors périmètre de cette V1. `/rpgadmin npc tag` (#66) reste inchangé.
- **Système PNJ V2 déclarative** *(branche `feat/control-panel-admin-tools`)* — introduit une
  vraie **définition logique** de PNJ RPGQuest, indépendante de Citizens et du monde : un fichier
  par PNJ sous `plugins/RPGQuest/npcs/*.yml` (`YamlNpcEngine`, `NpcDefinition` : `id`,
  `display_name`, `dialogue?`, `role?`, `enabled`). Voir `NPC_FORMAT.md`. `NpcCatalog` / `npc.list`
  distinguent maintenant `logicalDefinitionPresent` vs `citizensBindingPresent` et calculent un
  `state` (`LINKED` / `NOT_LINKED` / `DISABLED` / `CITIZENS_ORPHAN` / `UNDEFINED_REFERENCE` /
  `BROKEN`) ; `definedIds` devient la source canonique des ids (transition #66). Nouvelles actions
  agent d'**écriture de contenu** whitelistées + auditées : `npc.definition.create` /
  `npc.definition.update` (`NpcDefinitionStore`, jamais d'écrasement silencieux, jamais de YAML
  brut) et `quest.giver.set` (`QuestGiverEditor` — pose `giver:` sur le YAML d'une quête en
  préservant commentaires et format). Page `/npcs` : deux blocs (Définition RPGQuest / Binding
  Citizens), création + édition limitée (jamais l'id) + attribution de quête. **Non fait**
  (délibérément) : spawn / binding Citizens depuis le web, éditeur de dialogues, câblage
  `/rpgadmin npc tag` (#66), enrichissement live (position/monde, PNJ Citizens non tagués).
- **Liaison définition ↔ PNJ Citizens existant** *(branche `feat/control-panel-admin-tools`,
  issue #81 phase 1)* — nouvelles actions agent `npc.citizens.list` (lecture du registre Citizens
  sur le thread principal — `numericId`, `uuid`, `name`, `linkedNpcId`, `availableForBinding`,
  `spawned` ; jamais de scan d'entités/chunks) et `npc.citizens.link` (`npc_id` + `citizens_id` ;
  permission dédiée `NPC_BIND_WRITE`, `confirm` obligatoire, audit). `NpcIdentityService.bindCitizens`
  + `CitizensBindPlanner` (pur) : liaison identique → succès no-op ; PNJ Citizens ou `npc_id` déjà
  lié → refus lisible (jamais de rebind silencieux) ; écriture atomique
  `NpcBindingRepository.insertIfAbsent` + rafraîchissement du cache. Page `/npcs` : bouton
  « Rafraîchir les PNJ Citizens » + formulaire « Lier un PNJ Citizens existant » sur les cartes
  `NOT_LINKED` (seuls les Citizens libres sont sélectionnables) ; sur une carte `LINKED`, le
  binding est affiché sans bouton (rebind = phase ultérieure). **Toujours hors périmètre** :
  rebind/remplacement, suppression.
- **Spawn d'un PNJ Citizens depuis une définition** *(branche `feat/control-panel-admin-tools`,
  issue #81 phase 2)* — action agent `npc.citizens.create` (permission dédiée `NPC_SPAWN_WRITE`,
  `confirm` obligatoire, audit). Paramètres métier stricts : `npc_id` + `world` + `x`/`y`/`z` +
  `yaw`?/`pitch`? ; le nom vient de `NpcDefinition.displayName` (jamais du navigateur).
  `CitizensSpawnPlanner` (pur) valide toutes les préconditions logiques **avant** création
  (Citizens actif, définition présente + `enabled`, `npc_id` pas déjà lié, monde de la liste
  blanche RPGQuest = hub/claims/exploration de la config, position finie et bornée — jamais
  « corrigée »). `CitizensSpawnCoordinator` (pur) orchestre `create → bind → success`, et si la
  liaison échoue **après** création, détruit **le seul PNJ créé** (`BIND_FAILED_ROLLED_BACK`,
  jamais un PNJ préexistant). Threading : registre Citizens + monde sur le thread principal,
  persistance async. Page `/npcs` : bloc « Créer le PNJ Citizens » sur les cartes définies
  `NOT_LINKED` — preview (id, nom, « aucun Citizens lié »), monde en liste déroulante (mondes
  chargés du heartbeat), coordonnées à saisir (jamais devinées), confirmation obligatoire. **Non
  fait** (chantiers séparés) : suppression générale d'un PNJ Citizens, rebind/déplacement, choix
  de position depuis une carte, téléportation admin, câblage `/rpgadmin npc tag` (#66).

## Bugs connus et corrigés

- **Sélection RPGQuest vs WorldEdit** : l'outil `/rpgadmin zone wand` utilisait le même matériau
  que la wand par défaut de WorldEdit (`WOODEN_AXE`), causant une collision. Corrigé (matériau
  `BLAZE_ROD`, écoute robuste même si un autre plugin annule l'interaction en premier).
- **Fallback de message manquant affiché en jeu** : une clé `messages.yml` absente s'affichait
  littéralement (`[message manquant : ...]`) y compris en Title/Subtitle plein écran. Corrigé
  (fallback discret + log serveur, jamais le nom technique de la clé côté joueur).

## Bug résolu : téléportation automatique dans le Hub (`hub_to_claims`)

Un joueur était téléporté automatiquement hors de `world_hub` ~1-2 s après son arrivée. **Cause
confirmée : une zone `worldportal` (`hub_to_claims`) mal sélectionnée** (englobait le point
d'arrivée du joueur) — pas un bug de code. Corrigé côté configuration (zone resélectionnée), aucune
modification du code `travel`/`WorldPortalTeleportListener` nécessaire pour ce cas précis.

Les outils de diagnostic ajoutés pendant l'investigation restent en place (utilité permanente, pas
une instrumentation à retirer) — voir [docs/TRAVEL.md](TRAVEL.md) :
- `/rpgadmin worldportal here` — liste TOUS les portails simples à la position actuelle (pas
  seulement le premier, contrairement au comportement en jeu), révèle les chevauchements invisibles.
- `/rpgadmin worldportal debug show|hide|showall|hideall` — visualisation par particules + étiquette
  flottante du contour d'une zone (jamais de bloc modifié).
- `/rpgadmin worldportal info` enrichi (largeur/hauteur/profondeur, centre, répit d'arrivée global).

Les logs `[TP-TRACE]` (préfixe temporaire, `travel.TpTraceLogger`) et l'anomalie de validation
croisée manquante dans `WorldPortalRegistry#reload()` (documentée, jamais corrigée) restent
également en l'état — non concernés par cette session, non nécessaires à retirer pour l'instant.

Gap async corrigé lors de l'investigation dans `PortalService` (vérification `isOnline()` avant
`startChanneling`/`finishTeleport` dans les callbacks asynchrones) — reste en place.

## Persistance

**Couche abstraite (issue #40)** : le moteur SQL est un détail d'infrastructure choisi par
`config.yml` (`database.type: sqlite | mysql`). Le code de gameplay ne contient aucun SQL et ne
teste jamais le moteur ; le seul point de choix est `DatabaseEngineFactory`. Abstractions :
`DatabaseEngine`, `SqlDialect` (`rewrite`/`ddl` + upsert/insert-ignore/identité/`columnExists`),
`SchemaHistory` (`PragmaUserVersionHistory` SQLite / `MigrationTableHistory` portable),
`SchemaMigrationRunner` (application ordonnée, idempotente, échec nommé).

**Backend MySQL/MariaDB réel (issue #41)** : `MySqlDatabaseEngine` = driver *MariaDB Connector/J*
+ pool *HikariCP* (`plugin.yml` `libraries:`, jamais empaqueté). `MySqlDialect` traduit le DML des
repositories (`INSERT OR IGNORE` → `INSERT IGNORE`, `ON CONFLICT … DO UPDATE` → `ON DUPLICATE KEY
UPDATE`) et le DDL des migrations (`TEXT` clé → `VARCHAR(191)`, `INTEGER` → `BIGINT`,
`AUTOINCREMENT` → `AUTO_INCREMENT`, `BLOB` → `LONGBLOB`, InnoDB + `utf8mb4_bin`, `CREATE INDEX` →
`ALTER TABLE ADD INDEX`). Historique de schéma dans la table portable `rpgquest_schema_migrations`.
**Validé contre un vrai serveur MariaDB 10.11** (base de test VeryGames vide) : schéma créé depuis
zéro, historique V1..V17, CRUD/transactions/generated-keys des repos, health, reprise après
connexion cassée. **Non fait (issue #42)** : migration des données `data.db` → MariaDB et bascule
de production. Détail : [PERSISTENCE.md](PERSISTENCE.md).

**Backend actif en production** : **SQLite** (`data.db`) — comportement **strictement inchangé**
(DDL/DML passés par des dialectes-identité). Migrations via `SchemaMigrator.ALL` (version courante :
**17**). Le mot de passe MySQL n'est jamais dans `config.yml` (`database.mysql.password-env` = nom
d'une variable d'environnement). YAML pour tout ce qui est éditable à la main par un administrateur
(quêtes, zones, portails, stories, dialogues, items...).

## Non implémenté / hors périmètre à ce jour

- Chapitres et récompenses de palier pour les Stories (modèle prêt à l'extension — voir
  `current_index`, pas implémenté).
- Agrandissement de claim, équipement légendaire, points de compétence, PNJ Story du Wild, GUI
  Story avancée (explicitement hors périmètre de l'étape « progression automatique »).
- i18n effective (résolution par langue du joueur) — la donnée est prête (`LocalizedText`) mais
  la résolution n'est pas câblée.
- Commandes joueur pour les Stories (explicitement hors périmètre : UX finale prévue sans
  commande joueur).
