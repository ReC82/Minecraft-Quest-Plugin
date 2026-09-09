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
- **Page `/dialogues` V1** *(branche `feat/control-panel-admin-tools`)* — lecture structurée des
  dialogues à embranchements + bases d'un futur éditeur. Nouvelle action agent `dialogue.list`
  (lecture, permission dédiée `DIALOGUE_READ`) : `DialogueCatalog` (pur, sans Bukkit) dérive
  depuis `YamlDialogueEngine` + `YamlNpcEngine` + `YamlQuestEngine` un catalogue par dialogue
  (`id`, `startNodeId`, `linkedNpcIds`, `nodeCount`/`choiceCount`, quêtes référencées/démarrées,
  `nodes[]` ordonnés — départ d'abord — avec `reachable`, `choices[]` dont **actions et
  conditions typées** `{kind, target, value, raw}`). Warnings de cohérence : `NODE_UNREACHABLE`,
  `QUEST_REF_UNKNOWN`, `DIALOGUE_NO_NPC`, `MULTIPLE_NPCS`, `DEFINITION_DIALOGUE_DIVERGES`,
  `NEXT_MISSING`. Les erreurs de chargement (dialogue sans nœud, `start` invalide, cycle
  `OPEN_DIALOGUE`, id dupliqué) — qui empêchent un fichier de devenir une `DialogueDefinition` —
  sont remontées à part (`loadIssues[]`). L'ouverture en jeu reste **toujours** par convention
  `rpgquest:<npcId>` (jamais via `NpcDefinition.dialogue`, qui ne sert qu'aux diagnostics).
  Action mutation `dialogue.definition.create` (permission dédiée `DIALOGUE_WRITE`, `confirm`
  obligatoire) : crée un **squelette** `dialogues/<key>.yml` (`DialogueDraft.skeleton` →
  `DialogueDefinitionYaml` déterministe → `DialogueDefinitionStore` : écriture atomique, refus
  d'écrasement, re-parsé après écriture ; jamais de YAML brut ni de chemin). Page `/dialogues`
  activée : cartes par dialogue (graphe lisible, nœud de départ mis en avant, nœuds inaccessibles
  marqués, MiniMessage rendu), bannières pour les fichiers rejetés et les définitions pointant
  vers un dialogue absent, formulaire de création de squelette.
- **Éditeur guidé `/dialogues` — phase 1 de #82** *(branche `feat/control-panel-admin-tools`)* —
  refonte de la page en quatre blocs (en-tête identité+état · résumé départ/PNJ/quêtes ·
  diagnostics hiérarchisés erreur→attention→info · graphe de cartes nœud) + première édition
  guidée réellement utilisable. Cinq mutations agent (`DIALOGUE_WRITE`, `confirm`, audit) via
  `DialogueDefinitionEditor` : `dialogue.node.update` (locuteur/texte d'un nœud, choix conservés),
  `dialogue.node.create` (nœud simple orphelin + choix « fermer »), `dialogue.choice.add` /
  `dialogue.choice.update` / `dialogue.choice.delete` (**choix simple** uniquement : ni condition
  ni action hors « fermer » ; redirige vers un nœud existant *ou* termine le dialogue ; refuse le
  dernier choix d'un nœud). Écriture sûre : localisation du fichier par `id`, refus d'un fichier
  déjà invalide, **sérialisation fidèle du dialogue complet** (`DialogueDefinitionWriter` — 10
  actions + 8 conditions + négation, aucune perte), **garde-fou round-trip** (re-parse en mémoire
  + égalité sémantique) avant écriture atomique, **rechargement** puis **restauration du contenu
  d'origine** si le fichier ne recharge pas. Le fichier édité adopte le **format canonique** du
  panel (commentaires / mise en forme d'origine non conservés — choix assumé). Cibles de choix =
  `<select>` des nœuds du dialogue (jamais un champ libre). Formulaires `<details>` semi-inline
  par nœud, aucun JS (conforme CSP `default-src 'self'`). **Non fait** (suite de #82) : édition
  des actions/conditions riches, renommage / déplacement / suppression de nœud, réordonnancement
  des choix, rendu graphe interactif.
- **Centre de documentation `/docs` — MVP (issue #49)** *(branche `feat/control-panel-admin-tools`)*
  — wiki d'administration **privé** dans le Control Panel : entrée « Documentation » dans le menu,
  page d'accueil (gros champ de recherche + catégories + raccourcis « Comment faire ? »),
  **recherche plein texte** en mémoire (titre / tags / catégorie / commandes / corps, ET des
  termes, extraits contextualisés), rendu **Markdown sûr** (tout échappé, aucune balise HTML
  brute, aucun JS, liens limités à `/docs/…` / ancre / `https://` ; titres, listes, tableaux,
  blocs de code avec bouton **Copier**, callouts). Source de vérité = fichiers Markdown
  **versionnés** dans `control-panel/src/main/resources/docs/`, listés dans un manifeste
  `_index.txt` qui **est** la liste blanche — **aucun contenu en base**, **aucun chemin du
  navigateur ouvert** : les fiches sont adressées par un `slug` interne (`[a-z0-9-]`) résolu par
  un lookup en mémoire, zéro accès disque à la requête (path traversal impossible par
  construction). 9 fiches opérationnelles livrées : Créer/configurer un PNJ (détaillée : nom vs
  id, tag, skin, déplacement, suppression, lien contenu, diagnostic), commandes Citizens, reset
  d'un joueur, quêtes/stories, Claims, Wild, déploiement VeryGames (+ règle anti-auto-reboot),
  déploiement AWS Control Panel, référence `/rpgadmin`. Liens contextuels depuis les pages PNJ /
  Quêtes / Joueurs / Dialogues. Accès : authentifié, permission `DOCS_READ` (tous les rôles).
  **V2** : édition depuis le navigateur, permissions fines, indexation des `docs/` du dépôt,
  historique Git par fiche.
- **Refonte graphique du Control Panel (issue #92)** *(branche `feat/control-panel-admin-tools`)* —
  sortie du thème « tout noir » vers un **thème clair** : design system interne en *custom
  properties* (`Layout.CSS`), iconographie **SVG locale** (`Icons.java`, sprite `<symbol>`/`<use>` —
  aucun emoji comme système, aucun CDN), shell refait (topbar identité + chip environnement + chip
  d'état serveur, sidebar en 4 groupes, **drawer mobile sans JS**), composants `Ui` réutilisables
  (`pageHeader` / `sectionTitle` / `statCard` / `banner` / `searchToolbar` / `filterChip` / …),
  dashboard en cartes, `/docs` restylée, recherche + filtres `data-filter-*` sur les pages
  PNJ / Quêtes / Stories / Dialogues. Tous les noms de classes CSS et sous-chaînes HTML testées
  conservés (migration sans casse). **Restant** : refonte de contenu des pages Agents /
  placeholders, validation navigateur du rendu réel et du mobile.
- **Socle Bootstrap 5 + Home à tuiles (issue #92)** *(branche `feat/control-panel-admin-tools`)* —
  Bootstrap 5.3.8 et Bootstrap Icons 1.13.1 **embarqués et servis localement** par PlugAdmin
  (`/assets/bootstrap/`, `/assets/bootstrap-icons/` ; aucun CDN, CSP inchangée). Couche
  `plugadmin.css` = design system #92 + pont `--bs-*` vers les tokens PlugAdmin (Bootstrap prend
  l'identité du panel, pas de bleu/blanc par défaut). `Icons.icon()` rend maintenant un
  `<i class="bi bi-…">` (signature inchangée). **Nouvelle page d'accueil `/home`** : un *launcher*
  à grandes tuiles groupées (Vue d'ensemble / Gestion du jeu / Ressources / Administration),
  grille Bootstrap responsive, chaque tuile activée = vrai lien `<a>` (toute la carte cliquable,
  focus clavier), tuiles « à venir » non cliquables ; badges synthétiques (joueurs en ligne,
  nombres PNJ/quêtes/stories/dialogues, état serveur) tirés du **dernier relevé agent** sans
  requête déclenchée ; place réservée pour une recherche globale future. Connexion et `/`
  redirigent vers `/home` ; le **Dashboard** reste sur `/dashboard` et devient une tuile ; la
  sidebar gagne une entrée « Accueil » en tête. Éditeur #46 et Documentation #49 non touchés.
- **Toasts + centre de notifications + page `/actions` (issue #93)** *(branche
  `feat/control-panel-admin-tools`)* — les gros blocs « Actions récentes » **disparaissent** des
  pages métier (`/players`, `/npcs`, `/quests`, `/stories`, `/dialogues`) ; `/agents` garde une
  « Activité récente » compacte + lien. Après une mutation : **toast Bootstrap** (redirection
  `…&toast=<id>`) — SUCCESS auto-dismiss, PENDING/FAILED persistants, un toast PENDING mis à jour
  en place par `panel.js` quand l'action se résout. **Cloche `bi-bell`** dans la topbar (visible
  avec `DIAGNOSTICS_READ`) : dropdown des 8 dernières actions + badge (actions en cours + échecs
  < 24 h) + lien « Voir toutes les actions ». **Page `/actions`** : recherche + filtres statut
  (Tous/Succès/En cours/Échec) + domaine (Joueurs/PNJ/Quêtes/Stories/Dialogues/Items/Serveur/
  Autres), table desktop + cartes mobile, pagination 25/page ; détail `/actions/<id>`. Source de
  vérité inchangée : table `agent_action`. Polling léger via `/agents/actions.json` (enrichi),
  pas de WebSocket.
- **Refonte ciblée de la page `/npcs` (issue #89)** *(branche `feat/control-panel-admin-tools`)* —
  principe : liste = synthèse rapide, clic sur un PNJ = détail, clic sur une action = formulaire.
  Toolbar catalogue **compacte** (boutons `btn-sm`, plus de grandes cartes vides) ; recherche en
  vrai `input-group` Bootstrap (icône dans sa propre zone) alignée aux filtres. La liste est un
  **accordion** Bootstrap (un seul PNJ ouvert à la fois) : en-tête = nom + id logique discret +
  2-3 badges d'état. Le détail est structuré en sections **Identité / Citizens / Contenu /
  Diagnostics / Actions** ; labels humains (« Donneur de quête », `quest_giver` en secondaire) ;
  diagnostics dans des `alert` différenciées avec code technique discret. Les actions sont des
  **boutons** qui déplient chacun un `collapse` avec leur formulaire (rien affiché d'emblée). Le
  formulaire « Créer / Modifier la définition » est repensé (sections, `form-label`/`form-text`,
  select Dialogue depuis `dialogue.list`, select Rôle, `form-switch`). **Bug de contexte corrigé**
  (une fiche PNJ ne peut plus muter un autre PNJ à cause d'un état de formulaire périmé) :
  `npc_id` + `npc_ctx` en champs cachés = l'id de la fiche, `autocomplete="off"`, + garde-fou
  serveur qui refuse `npc_ctx` ≠ `npc_id`. Toasts #93 conservés.
- **Généralisation de la refonte UX + diagnostics humains (issues #89 / #49)** *(branche
  `feat/control-panel-admin-tools`)* — la philosophie `/npcs` (liste = synthèse, clic = détail,
  clic action = formulaire) est appliquée à `/quests`, `/stories`, `/dialogues` : chaque liste est
  un **accordion** Bootstrap (helpers partagés `listCatbar` / `compactRefresh` / `listControls`),
  détail en sections repliées (Quêtes : Général / Donneur / Prérequis / Objectifs / Récompenses /
  Diagnostics / Actions ; Stories : Identité / Chaîne de quêtes / Diagnostics / Actions ;
  Dialogues : Résumé / PNJ / Quêtes / Diagnostics / **Graphe replié** / Actions). `/players` (peu
  dense) : toolbar compacte + recherche `input-group` au-delà de 6 joueurs. Toutes les grandes
  cartes « Rafraîchir le catalogue » sont remplacées par des boutons `btn-sm`. **`DiagnosticHelp`**
  (`panel.web`) est un **registre unique** : à chaque code moteur (PNJ, dialogues) et à chaque
  vérification de référence calculée côté panel (`QUEST_PREREQ_UNKNOWN`, `QUEST_GIVER_UNKNOWN`,
  `STORY_QUEST_UNKNOWN`, `DIALOGUE_LOAD_ISSUE`, `DIALOGUE_DECLARED_MISSING`) correspond un message
  **français clair → conséquence → action → lien vers une ancre précise de `/docs` → code
  technique en secondaire**, rendu dans une `alert` colorée selon la sévérité avec un bouton
  « Comment corriger ? » (et « Corriger maintenant » côté PNJ quand une action immédiate existe).
  La terminologie technique interdite (`tagué`, `binding`, `giver`, `orphan`, `raw`, `namespaced`)
  ne subsiste que dans le code technique secondaire. `Markdown.slug` replie les accents pour des
  ancres propres et cohérentes. **#49** : 4 fiches de dépannage contextuelles livrées
  (`pnj-depannage`, `dialogues-depannage`, `quetes-depannage`, `stories-depannage`, whitelist
  `_index.txt`), au format *Ce que cela signifie / Pourquoi il faut corriger / Comment corriger /
  Vérification / Référence technique*, titres de section alignés sur `DiagnosticHelp`.
- **Dashboard d'observabilité — page `/diagnostics` (issue #38)** *(branche
  `feat/control-panel-admin-tools`)* — répond à « qu'est-ce qui ne va pas actuellement sur
  RPGQuest ? » sans ouvrir PNJ / Dialogues / Quêtes / Stories une par une. Nouveau paquet
  `panel.diag` : modèle unique `DiagnosticEntry` (code stable, `Severity` ERROR/WARNING/INFO,
  `Domain` humain, ressource, titre/message/conséquence/action humains, ancre `/docs` précise,
  lien « Ouvrir », `QuickAction` optionnelle, `source`, `observedAt`), interface
  `DiagnosticProvider` (Npc / Dialogue / Quest / Story / Server) + `DiagnosticContext` **lecture
  seule du dernier snapshot agent (aucune requête déclenchée)**, `DiagnosticsService` qui agrège,
  dédoublonne (code + ressource) et trie (ERROR → WARNING → INFO puis domaine). Le wording humain
  vient de `DiagnosticHelp` — jamais dupliqué ; `RefKeys` partagé avec `/quests` `/stories` pour
  les vérifications de référence. La page `DiagnosticsPages` : cartes synthétiques, filtres
  **combinables** gravité + domaine + recherche (`panel.js` gère maintenant plusieurs groupes de
  puces pour un même scope, rétro-compatible), cartes compactes humain-d'abord, regroupement
  au-delà de 4 diagnostics identiques, état vide positif, fraîcheur. « Ouvrir » = lien profond
  `?focus=<id>` que `panel.js#initFocus()` déplie sur les 4 pages (`data-res-id`) ; « Corriger
  maintenant » ajoute `&fix=` pour déplier le bon formulaire — aucune correction automatique.
  Refresh **coordonné** : `POST /diagnostics/refresh` enqueue les 4 relevés `*.list` en une
  action, feedback toast (#93). Home : tuile Diagnostics active avec compteurs ou « Tout est en
  ordre » ; Dashboard : section « État du contenu » (compteurs + lien). Permission
  `DIAGNOSTICS_READ` (tous rôles la possèdent). **#49** : fiche `serveur-depannage.md` +
  section Citizens dans `pnj-depannage.md`.
- **Éditeur guidé de quêtes et de stories — chemin principal (issue #46)** *(branche
  `feat/control-panel-admin-tools`)* — depuis `/quests` (« Créer une quête ») et `/stories`
  (« Créer une story »), ou « Modifier » sur une carte : formulaire guidé multi-sections
  (Général / Prérequis / Objectifs / Récompenses / Variables ; Général / Chaîne de quêtes),
  **sans JavaScript** (aller-retour serveur, boutons `_action` pour ajouter / supprimer /
  réordonner). Les 7 types d'objectifs et 4 récompenses **réels** du moteur sont décrits par des
  descripteurs (`Descriptors`) avec champs adaptés ; les valeurs (entité / matériau / PNJ /
  quête / monde) sont proposées par `<datalist>` alimentées par le dernier relevé de l'agent +
  des listes curées. `QuestValidator` / `StoryValidator` produisent des diagnostics
  ERROR (bloquant) / WARNING (confirmable) / INFO **avant** enregistrement ; l'aperçu montre le
  YAML généré et le diff avec la source. L'écriture passe par `ContentWorkspace` : whitelist
  stricte `quests/*.yml` + `stories/*.yml` du **checkout source** (jamais le serveur live, jamais
  de FTP, **aucun déploiement**), hash SHA-256 de version, refus de conflit / d'écrasement,
  écriture atomique, garde-fou round-trip (émettre → relire → ré-émettre → égalité). Si le
  service n'a pas les droits d'écriture (ou `content.repo-dir` absent) : éditeur en **lecture
  seule** avec bannière explicite, sans aucun `chmod` / `sudo`. Permissions dédiées
  `QUEST_CONTENT_WRITE` / `STORY_CONTENT_WRITE` (rôles `CONTENT_EDITOR` + `OWNER`), CSRF, audit
  `*.content.write`. `QuestDraft` / `StoryDraft` reprennent les **mêmes champs** que le moteur
  (pas de second modèle). **V2** : lignes guidées pour prérequis / variables, duplication de
  ligne, rechargement des champs au changement de type, action agent `quest.definition.validate`.

## Bugs connus et corrigés

- **Sélection RPGQuest vs WorldEdit** : l'outil `/rpgadmin zone wand` utilisait le même matériau
  que la wand par défaut de WorldEdit (`WOODEN_AXE`), causant une collision. Corrigé (matériau
  `BLAZE_ROD`, écoute robuste même si un autre plugin annule l'interaction en premier).
- **Fallback de message manquant affiché en jeu** : une clé `messages.yml` absente s'affichait
  littéralement (`[message manquant : ...]`) y compris en Title/Subtitle plein écran. Corrigé
  (fallback discret + log serveur, jamais le nom technique de la clé côté joueur).

## Bug résolu : reconnexion depuis le Wild renvoyée au Hub (issue #87)

Un joueur **non-OP** déconnecté dans `wild` se reconnectait dans `world_hub`, exactement au
spawn du village (`spawn.yml`) — logout/login devenait un échappatoire gratuit du Wild. **Cause
racine** : `SpawnPlayerListener` distinguait « nouveau joueur » ↔ « reconnexion » via le seul
`Player#hasPlayedBefore()`, puis `SpawnService.handleFirstJoin` redirigeait **inconditionnellement**
la position d'arrivée vers le spawn du village. Or `hasPlayedBefore()` renvoie `false` pour un
joueur qui a pourtant déjà joué dès que la métadonnée Bukkit `bukkit.firstPlayed` de son
`playerdata` est absente ou pas encore peuplée (migration / transfert de serveur — cas VeryGames,
bascule hors-ligne ↔ en-ligne des UUID, restauration de sauvegarde, aléa de timing du login).
Aucun autre chemin de connexion RPGQuest ne téléporte au Hub — audit exhaustif des ~15
`PlayerJoinEvent` / `PlayerSpawnLocationEvent` / `PlayerRespawnEvent`. Multiverse-Core sur DEV :
`enforce-access: false`, `first-spawn-override: false`, `enable-join-destination: false`, et
`wild` n'est même pas un monde Multiverse — donc pas MV non plus.

**Correction** (minimale, sans nouvelle persistance) : `spawn.JoinSpawnPolicy` (fonction pure,
testée séparément) décide de la redirection. On ne redirige vers le village que si un nouveau
joueur (`!hasPlayedBefore()`) est placé par Paper dans le **monde principal**
(`getServer().getWorlds().get(0)`) **ou** le **monde Hub** (`config.yml` → `hub.world`) — les deux
seuls mondes où un tout nouveau joueur apparaît légitimement. Si Paper restaure déjà le joueur
dans un autre monde chargé (`wild`, `claims`, …), sa position est **conservée**, quel que soit
`hasPlayedBefore()`. Onboarding inchangé ; repli Hub « monde précédent disparu » préservé (Paper
renvoie alors le joueur au monde principal → redirection village). Aucun `if (!world.equals("world_hub"))`
codé en dur : c'est un allowlist config/API. `dialogue.node.*`, la Rune/Pierre de rappel, les
Claims, le respawn après mort (`SpawnService.handleRespawn`, inchangé) ne sont pas touchés. Log
`join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION|REDIRECT_TO_VILLAGE_SPAWN` (une
ligne par connexion, sans coordonnées). Point d'intégration futur anti-combat-logging (#88)
documenté dans `JoinSpawnPolicy` (non implémenté). Tests : `JoinSpawnPolicyTest` (nouveau),
`SpawnServiceTest` étendu (régression #87 + onboarding Hub).

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
