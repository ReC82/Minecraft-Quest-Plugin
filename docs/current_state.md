# État actuel du projet

Snapshot de ce qui est **actuellement implémenté** dans RPGQuest — une vue d'ensemble rapide, pas
une référence détaillée (voir [RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md) et [INDEX.md](INDEX.md) pour
le détail par système). À mettre à jour à chaque étape livrée qui ajoute/change un système.

## Systèmes implémentés

- **Quêtes** — définitions YAML, 10 types d'objectifs, prérequis, récompenses, progression
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
  permanents), séparation Hub/wild. Hub également : aucun mob indésirable quelle que soit la
  raison de spawn (hostiles, enderman même neutre, marchand ambulant/lama — issues #121/#155,
  nettoyage ciblé des entités déjà présentes) ; dégâts joueur causés à une entité protégée
  (animaux...) toujours annulés sauf bypass explicite `rpgquest.admin.hub.combat` (issues #30/#31,
  distinct de la construction) ; faim/saturation jamais réduites, restaurées au maximum à
  l'arrivée (connexion/changement de monde/réapparition) — issue #33, `hub.HubComfortService`,
  strictement scopé au monde `hub.world` (`isHub()` par joueur, le Wild garde sa survie normale,
  couvert par `foodLevelDecreaseInTheWildIsNeverCancelled`/`periodicSweepNeverTouchesPlayersInTheWild`).
  **Signalement #159** (faim bloquée dans le Wild après les correctifs #33), **validé en jeu le
  2026-10-04** : la faim baisse normalement dans le Wild — comportement confirmé correct (la
  saturation posée au maximum en sortant du Hub retardait simplement, en vanilla, la baisse de
  faim le temps qu'elle s'épuise, pas un bug). Trace de diagnostic temporaire `[HUNGER-TRACE]`
  retirée une fois la validation confirmée.
  **Secours Hub (#154)** : la Rune de rappel téléporte désormais aussi, gratuitement et sans
  canalisation/cooldown, vers le spawn configuré **quand elle est utilisée dans le Hub**
  (`ItemTravelDefinition#freeRescueWorld`, indépendant de sa restriction `requiredWorld` au Wild) ;
  filet de secours graphique (`hub.HubRescueFallbackService`) si elle venait à manquer avec
  l'inventaire plein ; `player.StarterKitListener` ne marque plus jamais la Rune distribuée si
  `addItem` échoue (inventaire plein à la première connexion), pour réessayer à la connexion
  suivante au lieu de perdre l'objet pour toujours.
- **Claims** — terrains protégés créés par les joueurs eux-mêmes, confiance par UUID ; monde
  résidentiel `claims` réellement pacifique (tout dégât joueur annulé, tout mob hostile empêché et
  nettoyé, Nether bloqué en sortie) ; frontière visualisée par particules (propriétaire uniquement,
  automatique à l'entrée ou volontaire via l'Acte réutilisé), faisceau dense (DUST + END_ROD)
  hors du claim ; retour au Hub sans commande via la Pierre de retour (mécanique générique de
  voyage par objet, `travel.ItemTravelService`).
- **Administration de la monnaie (issues #16/#140, premier lot économie)** — le socle existait
  déjà (`EconomyService` + `WalletRepository`, tables `wallets` et `transactions`, transactions JDBC
  explicites, débit qui refuse de passer négatif) : ce lot complète sa **fiabilité et son
  administration**. Le journal était **écrit sans être lisible** → `WalletRepository#history`
  (lecture seule, bornée, asynchrone) et trois actions whitelistées : `economy.balance`
  (`ECONOMY_READ`), `economy.credit` / `economy.debit` (`ECONOMY_WRITE`, sensibles). Fiche joueur :
  solde réel, journal récent, crédit/débit avec **raison obligatoire** enregistrée au journal,
  montant entier positif **plafonné par opération** (garde-fou de saisie, pas d'équilibrage), débit
  au-delà du disponible = **refus métier** sans aucune ligne de journal, solde **relu** et affiché
  avant → après. **Source de vérité tranchée** : le portefeuille persistant ; aucun objet
  d'inventaire n'est de la monnaie, aucune monnaie reconnue par nom ou lore. Une monnaie physique
  (#138) serait une **seconde source** : décision de gameplay non prise, et **aucune conversion ni
  migration** n'a eu lieu. `TESTER`/`READ_ONLY` lisent sans créer ; `BUILDER`/`CONTENT_EDITOR` n'ont
  aucun accès.
- **Récompense monétaire de quête et bourse en jeu (issue #16)** — nouveau `RewardType.MONEY`
  (`MoneyReward`, champ `amount` entier > 0), lu par `QuestDefinitionParser`, éditable depuis
  l'éditeur de quête du panel (descripteur `MONEY`, icône de pièce), exporté dans les content packs
  et décrit dans les relevés de l'agent. **Crédité au plus une fois par occasion de complétion** :
  le moteur fournit un identifiant d'occasion stable (`ActiveQuestProgress#rewardGrantId`) et
  `WalletRepository#creditQuestReward` réserve cet identifiant dans `quest_reward_grants` (**schéma
  V25**) *dans la même transaction SQL* que la mise à jour du portefeuille — donc ni double
  paiement sur un retry, ni récompense perdue sur une panne ; une quête répétable ouvre une
  nouvelle occasion à chaque reprise (`occurrence` numérotée par joueur et par quête). **Aucune
  seconde source de vérité** : `wallets` reste le seul solde, `quest_reward_grants` ne répond qu'à
  « cette occasion a-t-elle déjà été payée ? ».
- **Récupération des récompenses monétaires (issue #16, second lot — schéma V26)** — le premier lot
  ne gardait trace que de ce qui avait **déjà** été payé : une récompense due mais non créditée
  (crash, panne SQL) ne laissait **aucune** trace et devenait invisible. Trois défauts corrigés,
  dont un **mesuré** : (1) l'identité de paiement vivait **uniquement en mémoire**, donc perdue au
  redémarrage ; (2) l'état `COMPLETED` était persisté dans une transaction **séparée** du crédit,
  laissant une fenêtre « terminée mais jamais payée, sans trace » ; (3) **plusieurs récompenses
  `MONEY` sur une même complétion partageaient une identité**, donc `100 + 30` créditait **100**,
  la seconde étant avalée comme « déjà payée ». Désormais :
  `QuestProgressRepository#completeQuestWithMoneyDebts` écrit **dans une seule transaction**
  l'état `COMPLETED` et une ligne `PENDING` par récompense (identité `<jeton>#<index>`), puis
  `WalletRepository#payQuestRewardDebt` fait passer `PENDING → PAID` **avec** le crédit et la ligne
  de journal, sous un `WHERE status = 'PENDING'` qui empêche deux reprises concurrentes de
  l'emporter. **Le montant et le contexte du journal viennent de la ligne**, jamais de l'appelant :
  rééditer une quête ne change pas une dette déjà née. **Reprise bornée** : relecture une fois par
  chargement de joueur (jamais par tick, jamais sur le thread principal), 20 dettes au plus, et
  au-delà de 5 échecs la dette est **laissée au panel** ; une reprise échouée ne parle pas au
  joueur (sinon message d'échec à chaque reconnexion) et ne rejoue **que** le crédit monétaire.
  `SETTLED_MANUALLY` empêche qu'une compensation administrative laisse la même récompense payable
  une seconde fois. **Panel** : `economy.debts` (lecture), `economy.debt.retry` et
  `economy.debt.settle` (sensibles, confirmation + audit) ; la page distingue explicitement
  reprendre et compenser, affiche l'état réel (« en échec (N tentatives) » + motif) et la **limite
  historique**. **Aucun paiement rétroactif** : la migration marque les lignes existantes `PAID`
  (elles l'étaient, par construction de V25) et n'invente aucune dette pour les quêtes terminées
  avant cette mise à jour. Trace `transactions` de type `QUEST_REWARD`,
  contexte `quest:<id>#<occasion>`. **Le message de gain part seulement après confirmation de la
  base** (`quest.reward-money-credited`, avec le solde relu) ; un échec est dit explicitement
  (`quest.reward-money-failed`) et une occasion déjà payée ne dit **rien** au joueur. Les deux
  messages passent par le chat, jamais par l'ActionBar (réservée à la progression des objectifs).
  **Bourse** affichée dans le journal de quêtes (liste et vue détail, emplacement inerte), relue
  après chaque transaction, « indisponible » en cas d'erreur plutôt qu'un `0` trompeur. **Aucun
  plafond moteur** sur le montant (équilibrage = décision de jeu) ; le panel **avertit** au-delà de
  1 000 000. Restent non décidés : monnaie physique et conversion solde ↔ objet (#138), perte à la
  mort, prix et règles d'échange.
- **Rechargement du contenu dans le runtime (issue #131)** — service **central**
  `content.reload.ContentReloadService`, seul point qui permute un ensemble actif : dry-run de
  chaque famille, **annulation totale** sur la moindre erreur de contenu (l'ancien runtime valide
  est conservé — un `reload()` nu faisait *disparaître en silence* une définition devenue
  invalide), validation des **références croisées** sur le graphe candidat avec désignation du
  **contenu lié** à recharger conjointement, application dans l'ordre de dépendance, puis relecture
  avec **empreinte** et durée. Six familles (objets, PNJ, quêtes, stories, dialogues, profils de
  mobs). `/rpgadmin content preview|reload` et `/rpgadmin mob reload` **délèguent** au même service,
  ce qui supprime la seule porte qui contournait les garanties. Les **trois états** du ticket sont
  distinguables parce que l'aperçu lit le **disque du serveur** : « jamais publié » (déploiement
  requis) ≠ « publié mais pas chargé » (rechargement suffit) — et la page écrit qu'un rechargement
  **ne transfère rien** depuis AWS. Jamais de `/reload` Bukkit, aucun despawn/respawn, aucune
  récompense redistribuée, progression et instances vivantes intactes ; `NpcHintService#invalidateAll`
  garde le signal #12 cohérent. Single-flight. `config.yml` n'est **pas** rechargeable de façon
  fiable (valeurs capturées au démarrage) : le panel annonce **redémarrage requis**.
- **OP/DEOP et actions de secours sur un joueur (issue #210)** — fiche `/players` : statut **OP
  réel relu du serveur**, OP/DEOP, **renvoi au Hub** (mécanisme de la Pierre de retour, inventaire /
  Acte / claim / progression préservés), **expulsion** avec raison, **whitelist** (qui dit si elle
  est réellement appliquée). Permission **dédiée** `PLAYER_OP_WRITE` réservée à `OWNER` — **pas**
  `ADMIN`, par exigence du ticket ; le reste réutilise `PLAYER_MODERATE`. Élévation OP : raison
  obligatoire **et** pseudo exact à retaper, idempotente, état **relu** après écriture
  (`NOT_APPLIED` plutôt qu'un faux succès). OP Minecraft reste distinct du rôle PlugAdmin, du droit
  de construction (#200) et du bypass de gameplay (#35) : aucun n'est modifié. Cible par **UUID** ;
  actions impossibles hors ligne affichées avec leur motif.
- **Exploitation serveur depuis PlugAdmin (issue #95, lot 1)** — page `/ops` : état réel et
  **fraîcheur** du dernier heartbeat (vivacité agent, joueurs, uptime, version), annonce globale
  (`server.announce` — trois canaux réellement supportés `chat`/`actionbar`/`title`, message traité
  en **texte littéral** jamais en commande ni en MiniMessage, résultat structuré avec destinataires
  réels et `NO_PLAYERS` quand personne n'est là), **redémarrage** immédiat ou différé avec annonces
  de compte à rebours, annulation tant que rien n'est exécuté, single-flight et **retour en ligne
  vérifié** (un `stop` n'est jamais présenté comme un redémarrage : il faut avoir vu le serveur
  hors ligne, ou l'uptime du plugin diminué), et **console récente** en lecture seule (recherche,
  filtres niveau, pause, suivi auto) alimentée par un appender Log4j2 côté plugin
  (`ops.ConsoleTap`, `log4j-core` en `compileOnly`, `LinkageError` rattrapée) et remontée par
  l'**agent sortant** existant, sans SSE ni port entrant. Le redémarrage est exécuté par le panel
  en **RCON depuis AWS** avec une énumération fermée de trois commandes (`list`, `save-all`,
  `stop`) ; permissions dédiées `OPS_VIEW` / `OPS_ANNOUNCE` / `OPS_RESTART` / `OPS_LOGS`. Les
  fonctions absentes sont **affichées avec leur motif réel** (pas d'API de supervision chez
  l'hébergeur, log serveur inatteignable car la racine FTP est `plugins/`, `save-all` ≠ point de
  restauration) ; #131 et #210 annoncés comme lots suivants.
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
  (`rpgquest.admin.world`, défaut `op`, contourne le contrôle d'éligibilité et n'est jamais renvoyé
  — décisions journalisées `[claims-access]` / `[claims-safety]` / `[claims-retour]`). Voir
  `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1b.
- **Aucune entrée dans `claims` sans moyen d'en repartir (issue #22)** — être éligible ne suffit
  plus : avant la téléportation, `ClaimWorldAccessGuard` exige, via l'unique
  `claim.ClaimReturnService`, que la destination de retour se résolve (la même que la Pierre de
  retour elle-même) **et** que le joueur détienne ou puisse recevoir une `rpgquest:pierre_retour`
  **dans son inventaire** ; sinon l'entrée est refusée avec le motif exact, sans rien laisser au
  sol au Hub. Le bypass `rpgquest.admin.world` n'est jamais refusé mais **ne dispense plus** de la
  Pierre — le dispenser des deux laissait un administrateur éligible sans aucune sortie (blocage
  réel du 05/10/2026). Inventaire plein à l'arrivée : l'objet tombe aux pieds du joueur et le
  message le dit. Le choix « Obtenir une Pierre de retour » de Jo n'exige plus un claim déjà posé,
  seulement `CLAIM_TIER_1`.
- **Boucle joueur Hub ↔ Wild** — Journal des quêtes (`rpgquest:journal_quetes`, donné par le
  Libraire, clic droit → GUI deux onglets, voir ligne « Guide / journal » ci-dessus) ; Rune de
  rappel (`rpgquest:rune_rappel`,
  Wild → Hub, canalisation 10 s, cooldown 30 min persistant, remise à chaque nouveau joueur, filet
  via le Guide) ; Waystones générées paresseusement et de façon déterministe dans le Wild
  (`waystone.WaystoneService`), découverte individuelle par joueur, retour au Hub par canalisation
  courte. Système **soulbound générique** (`item.SoulboundItemService`) : un seul écouteur anti-perte
  pour tous les objets permanents (Acte, Pierre de retour, Journal, Rune).
- **Kit d'outils en bois** *(issue #26, partie A)* — demandé explicitement au Guide (« Demander mon
  kit de départ », action de dialogue `GIVE_STARTER_KIT`), jamais de remise automatique (ni clic
  simple, ni connexion, ni réapparition). Un exemplaire de chaque matériau de `config.yml` →
  `starter-tool-kit.items` (par défaut `WOODEN_SWORD`/`WOODEN_PICKAXE`/`WOODEN_SHOVEL`/`WOODEN_AXE`).
  Droit persistant par joueur (`player.StarterToolKitService`, variable
  `STARTER_TOOL_KIT_AVAILABLE`), renouvelé à chaque mort, sans limite de récupérations ; une mort
  avant la première remise ne retire jamais le droit initial. Remise tout ou rien (emplacements
  libres du stockage normal vérifiés avant toute écriture, hors armure/main secondaire) ; anti
  double-clic. `/rpgadmin player resetnew` restaure le droit initial (variables déjà toutes
  effacées — aucun code dédié). Distinct de la Rune de rappel (`player.StarterKitListener`, remise
  unique à vie, automatique à la connexion).
- **Entrée dans le Wild : avertissement de danger et retour d'attente** *(issue #161, couvre la
  partie B de #26)* — `travel.WildEntryWarningService` suspend tout passage vers
  `travel.wild-world` et demande une confirmation explicite. Avertissement **générique**, **aucune
  inspection d'inventaire** (décision du 2026-10-07 : ni nourriture, ni arme, ni outil, ni Rune, ni
  kit, ni gear score — l'ancien message « sans moyen de rappel » a été retiré) : zone dangereuse,
  PvP autorisé, mort = perte d'inventaire, + renvoi vers le Garde pour les conditions actuelles.
  Trois actions (« Entrer dans le Wild », « Entrer et ne plus afficher cet avertissement »,
  « Annuler ») ; fermer la fenêtre = annuler. Option persistée par joueur
  (`WILD_ENTRY_WARNING_HIDDEN`) écrite **uniquement** sur un départ réel, masquant l'avertissement
  seul. Retour immédiat « Recherche d'un point d'arrivée sûr… Téléportation en préparation. » puis
  réussite **ou échec explicite** (le listener n'annonce plus « réussie » après un échec). Une seule
  demande en vol par joueur, pas de réouverture tant que le joueur reste dans le portail, aucune
  téléportation tardive après déconnexion. `RANDOM_SAFE`, les contrôles de sécurité et le répit
  d'arrivée de 40 ticks sont inchangés ; chaque passage émet une ligne `[TP-LATENCY]` séparant
  recherche / chargement de chunks / téléportation.
- **Texte stylé sans MiniMessage — couverture étendue** *(suite de #195)* — le composant guidé
  partagé (palette au clic, gras/italique/souligné/barré, aperçu, mode code explicite) couvre
  désormais **tous** les champs de texte destinés aux joueurs : titre et description d'une quête,
  nom d'une story, réplique de départ d'un dialogue, en plus des noms de PNJ, de mobs/boss et des
  textes de nœuds et de choix déjà couverts. Deux variantes : simple ligne et multiligne. L'ancien
  `select` « Couleur du texte » de l'éditeur de dialogue, second mécanisme concurrent appliqué au
  texte simple et ignoré dès qu'il contenait du MiniMessage, a été retiré — le serveur continue
  d'accepter le champ `text_color`, le formulaire ne l'émet plus. Les seuls champs restés en texte
  simple sont ceux qui ne sont jamais colorés en jeu (locuteur d'un dialogue, valeurs techniques
  des descripteurs).
- **Contrat de contenu machine-readable** *(issue #110, phase 1)* — `/content/export` publie trois
  documents **générés** et téléchargeables : le schéma officiel du format
  `lodyquests-content-pack` en JSON Schema (`/content/schema.json`), des gabarits YAML commentés par
  famille ou pour le pack complet (`/content/template`), et un contrat rédigé pensé pour être collé
  dans un prompt (`/content/contract.md`). Tous sont dérivés de `Descriptors`, la source qui pilote
  déjà le formulaire, l'écriture YAML et la validation du panel : aucune liste de types n'est
  recopiée, et un test échoue si le contrat décrit un type inexistant, en oublie un, ou laisse
  passer un champ qu'aucun descripteur ne déclare. Chaque type d'objectif et de récompense est une
  branche `oneOf` avec `additionalProperties: false`. Les exemples portent le préfixe `tc110_`.
  **Limite assumée** : la section `dialogues` n'est contrainte que sur son squelette, le vocabulaire
  de ses actions et conditions n'étant pas encore dérivable côté panel.
- **Cuisson** *(issue #141)* — type d'objectif `SMELT_ITEM` (`material` = l'objet **obtenu**,
  `amount`) : la progression vient de `FurnaceExtractEvent`, le seul événement de four qui porte un
  joueur, donc le seul permettant une attribution correcte en multijoueur. Les trois fours vanilla
  comptent, vérifiés explicitement ; une extraction qui sort plusieurs objets avance d'autant, sans
  jamais dépasser la quantité demandée. Obtenir l'objet autrement (coffre, `/give`, craft,
  ramassage) ou le laisser sortir par un entonnoir ne compte jamais.
- **Découverte de waypoints** *(issue #185)* — type d'objectif `DISCOVER_WAYPOINT` (`amount`,
  `worlds` optionnel, `count-mode`). La progression vient de l'abonnement aux **premières**
  découvertes du système de waypoints, branché une seule fois au bootstrap : passer à proximité, se
  téléporter ou recliquer un waypoint déjà connu ne compte jamais, et aucune table supplémentaire
  n'est nécessaire (une première découverte est unique par couple joueur/waypoint en base).
  L'identité comptée est l'id stable du waypoint. `count-mode` est explicite — `NEW_ONLY` (défaut)
  ou `INCLUDE_EXISTING` — et la portée comme la règle sont **annoncées au joueur** dans le libellé.
  Aucune découverte n'est jamais supprimée ni aucun waypoint débloqué pour rendre une quête
  répétable.
- **Paliers du kit de départ** *(issue #218)* — le kit rendu au Guide après une mort progresse par
  paliers définis en configuration (`starter-tool-kit.tiers`). Le palier atteint est persistant, un
  palier ne peut jamais être sauté (garantie du moteur, pas une convention de données), la remise
  reste atomique et limitée à une par vie, et les emplacements requis sont calculés sur le contenu
  réel du palier. Le déblocage passe par une quête ordinaire (`kit_tier2.yml`) utilisant
  `DELIVER_ITEM_TO_NPC` : rien n'est codé en dur dans le Guide.
- **Densité des bornes de voyage du Hub** *(issue #156)* — correction d'un défaut du déclencheur
  d'appariement : la persistance du waypoint étant asynchrone, le premier passage d'un joueur ne
  pouvait jamais apparier, et une sortie anticipée « déjà dans cette instance » interdisait ensuite
  toute nouvelle tentative jusqu'à ce qu'il quitte l'instance puis y revienne. Aucun ratio
  borne/waypoint n'a été introduit et aucune politique de génération n'a changé. Le diagnostic
  correspondant est administrable et en lecture seule (`/travel` et `/rpgadmin travel diagnose`,
  même source) : pour chaque instance encore sans borne, il donne la cause, la distance à la borne
  la plus proche et le délai avant réessai.
- **Remise d'objets à un PNJ** *(issue #123)* — nouveau type d'objectif `DELIVER_ITEM_TO_NPC`
  (`npc` + `material` + `amount`) : le joueur doit **réellement remettre** les objets au PNJ, dans
  son dialogue. Cet objectif n'écoute **aucun** événement de jeu — ramasser, fabriquer ou posséder
  ne le fait jamais avancer, ce qui le distingue de `COLLECT_ITEM`. **Dépôts partiels persistants** :
  le compteur de l'objectif *est* la quantité déjà remise, stockée dans `quest_objective_progress`
  (aucune migration), donc acquise après une mort, une reconnexion ou un redémarrage ; le reliquat
  se dépose plus tard. Objets **consommés**, jamais restitués. Une **seule** interaction remet tout
  l'utile pour tous les objectifs de ce PNJ (plusieurs matériaux, plusieurs quêtes) :
  `QuestItemWithdrawal` retire au plus le reliquat et renvoie ce qu'il a réellement retiré, le
  compteur n'avance que de cette quantité (jamais de progression sans retrait, jamais de
  consommation au-delà du besoin, surplus laissé au joueur), plusieurs piles additionnées, stockage
  normal uniquement, et **aucun objet personnalisé RPGQuest consommé** (identité PDC). Un jeton par
  joueur refuse toute remise réentrante (double-clic, spam, cascade) ; la complétion d'étape n'est
  évaluée qu'une fois toute la remise appliquée. Côté dialogue : action `DELIVER_QUEST_ITEMS` et
  condition `HAS_PENDING_DELIVERY`, à PNJ **optionnel** (vide = PNJ porteur du dialogue, convention
  id de dialogue = id de PNJ) — la branche livrée dans `dialogues/guard.yml` ne nomme donc ni quête,
  ni matériau, ni PNJ, et se recopie telle quelle. `%delivery_status%` affiche remis/restant par
  matériau ; wording des messages dans `messages.yml` (`quest.delivery-*`). Control Panel : type
  « Rapporter des objets à un PNJ » dans l'éditeur guidé (PNJ, objet, quantité), résumé
  « Rapporter Cuir (x4) à Garde », aller-retour YAML complet.
- **État du Wild demandé au Garde** *(issue #24)* — `travel.WildConditionsService` lit l'état réel
  du monde d'exploration (jour/nuit depuis l'horloge, météo globale `clair`/`pluie`/`orage`) sans
  jamais le modifier. Exposé **sans commande** par un choix permanent du dialogue du Garde
  (« Comment est le Wild actuellement ? ») dont la réponse porte le marqueur dynamique
  `%wild_conditions%` (`dialogue.DialogueTextPlaceholders`, substitué au moment du rendu). Jamais
  injecté dans l'avertissement du portail : le Garde est la source volontaire d'information.
- **Waypoints par instance de biome** *(issue #124, MVP)* — `com.lodygames.rpgquest.waypoint`,
  **distinct des Waystones**. Repères physiques persistants et partagés générés **par instance
  réelle de biome** dans `travel.wild-world` : à l'entrée d'un joueur dans une zone de biome sans
  waypoint (`PlayerMoveEvent` throttlé), le moteur cherche **une seule fois** un emplacement de
  surface à `min-distance..max-distance` blocs (jamais au pied du joueur), dans la même instance,
  hors claim, sans écraser de construction ; verrou mémoire + index unique `(world, biome_instance)`
  → deux entrées simultanées ne créent jamais deux waypoints ; échec → retry borné, pas de boucle.
  **Instance de biome** = `(monde, type de biome, tuile de `region-size` blocs)` (`region-size`
  défaut 256) — jamais un simple `biomeType -> waypoint`, deux forêts éloignées → deux waypoints
  (compromis documentés dans `docs/WAYPOINTS.md`). **Rendu versionné** (`waypoint.render`,
  `WaypointModelRegistry`) : V1 = `COBBLESTONE_WALL` + `GOLD_BLOCK` + `STONE_BUTTON` latéral ;
  l'identité (`id`, `biome_instance`) est indépendante du rendu. **Découverte** : la proximité ne
  découvre rien, seul un clic droit sur le **bouton** valide la découverte, persistée par UUID
  (`waypoint_discoveries`). **Protection MVP** (`WaypointProtectionListener`) : casse joueur,
  explosion, piston, feu, fluide, entités — bypass `rpgquest.admin.world` ; la protection
  fonctionnelle anti-enfermement est hors périmètre → issue #122. Migration **V18** (`waypoints`,
  `waypoint_discoveries`), config `travel.waypoint.*`. Hors périmètre MVP : téléportation, coût,
  menus, waypoint de quête, éditeur/lecture PlugAdmin (`WaypointService` expose déjà `all()` /
  `byId()` / `discoveryCount()`). Validation en jeu : `PENDING MANUAL VALIDATION`.
- **Réseau de voyage / bornes** *(issues #132/#150/#151/#149/#133/#135)* — `com.lodygames.rpgquest.travel.beacon`,
  **strictement distinct** des waypoints/waystones (aucune fusion d'identité/table, lecture seule
  de `WaypointService#discoveredBy`/`#hasActivelyDiscovered`). Borne = même support qu'un waypoint
  mais `DIAMOND_BLOCK` + bouton en bois configurable (`OAK_BUTTON` par défaut) ; son bouton ouvre un
  **menu graphique** (`BeaconMenuHolder`, anti-vol/duplication comme `ui.QuestJournalService`) à
  trois catégories, **toutes revalidées fraîchement à chaque clic** : « Waypoints découverts »
  (ouvre d'abord un **choix du monde** — Hub/Wild toujours proposés même à 0 découverte, tout autre
  monde extensible dès qu'une découverte y existe — puis la liste filtrée à ce monde, triée par
  **nom d'affichage**, pagination 45/page, **recherche graphique par enclume virtuelle**
  `InventoryType.ANVIL` sans bloc réel ni coût XP (`repairCost` **et** `repairCostAmount` à 0,
  retour joueur 2026-10-04), insensible casse/accents, indicateur de recherche active + nombre de
  résultats + bouton d'effacement une fois filtré (manquait jusqu'ici, laissant croire que le
  filtre n'avait aucun effet), monde mémorisé pendant le détour par l'enclume) ; « Mon claim » (résout `ClaimService#mainClaimOf`, jamais une coordonnée
  copiée — icône grisée + message si absent, arrivée revérifiée **dans** le cuboïde actif du claim) ;
  « Villages » (centres administrés `VillageCenter`, table `village_centers`, identité
  **indépendante du monde** — plusieurs centres possibles dans `world_hub`, arrivée à la
  position/orientation exacte administrée). Sélection toujours revalidée côté serveur (jamais un
  clic périmé), arrivée sûre (`RandomSafeLocationFinder#findAtColumn` pour waypoints/claim).
  Placement administré : `/rpgadmin travel beacon set` (borne **Wild**, position réelle, idempotent)
  et `/rpgadmin travel village sethub|set|remove|enable|disable|list` (centres — `sethub` réutilise
  le spawn du Hub déjà configuré, jamais une coordonnée inventée). **Génération automatique Hub
  (#149)** : à l'entrée d'un joueur dans une instance de biome du Hub sans waypoint,
  `WaypointService#ensureGenerated` (même mécanisme exact que #124, réutilisé tel quel — le Wild
  continue de passer exclusivement par `handleMovement`, jamais touché) génère le waypoint, puis
  une borne **distincte** (jamais à la même position) est appariée à proximité une fois ce waypoint
  réellement persisté (anneau configurable, verrou mono-vol + retry borné — jamais de doublon même
  avec plusieurs joueurs/après redémarrage) ; gating indépendant `travel.waypoint.hub-enabled` /
  `travel.beacon.hub-generation.enabled` ; jamais dans le Wild ; les claims restent exclus ; les
  waypoints/découvertes déjà existants du Wild ne sont jamais affectés. **Noms de waypoints
  (#133/#135)** : nom d'affichage humain unique et persistant (`waypoints.display_name`), biome
  relégué en métadonnée secondaire — réserve statique bundlée (`waypoint-names.txt` via
  `WaypointNameCatalog`, jamais d'appel IA au runtime, dédoublonnée à l'import), attribution
  synchrone sans doublon à la génération (+ index SQL unique), nom de secours si la réserve est
  épuisée, backfill des waypoints déjà existants (migration V22, id/découvertes inchangés).
  **Noms lisibles (retour joueur 2026-10-04)** : la réserve bundlée ne concatène plus deux mots
  sans séparateur (ex. l'ancien `Lacgivre`) — grammaire `<nom> de <nom>` ou `<adjectif accordé>
  <nom>` (ex. `Lac de Givre`), migration **V23** renommant les noms déjà attribués via une table de
  correspondance figée (`SchemaMigrator#RENAMED_DISPLAY_NAMES`, comparaison normalisée), id et
  découvertes inchangés, unicité revérifiée par l'index SQL existant. **Retour « déjà découvert »
  (retour joueur 2026-10-04)** : un reclic sur un waypoint déjà découvert affichait un silence
  total (clic juste consommé) — affiche désormais `Waypoint déjà découvert : <nom>`, sans nouvelle
  récompense ni ré-écriture. **Bug de clics corrigé** : la session du menu était posée avant
  l'ouverture de l'inventaire, effacée par la fermeture synchrone de l'ancien menu (même classe que
  le bug #11 du journal de quêtes) — tous les clics (destination/retour/recherche) restaient
  silencieux ; corrigé en posant la session après l'ouverture partout, couvert par des tests routant
  un vrai `InventoryClickEvent`. Migrations **V19** (`travel_beacons`), **V20** (`village_centers`),
  **V21** (`travel_beacons.biome_instance`, ALTER idempotent), **V22** (`waypoints.display_name`,
  ALTER + backfill + index unique) et **V23** (renommage lisible). **Control Panel (#152, terminé)**
  : page `/travel` en lecture seule (action agent `travel.catalog`), listes séparées
  waypoints/bornes, pairage waypoint↔borne visible (manquants inclus), recherche/filtre par monde,
  pagination, horodatage de fraîcheur — aucun éditeur (hors périmètre explicite du ticket).
  Validation en jeu : `PENDING MANUAL VALIDATION` (couverture automatisée détaillée dans le rapport
  de session — tout ce qui précède l'appel `teleportAsync` est testé, l'appel lui-même ne l'est
  jamais dans cet environnement ; la saisie réelle dans l'enclume — `PrepareAnvilEvent`/`AnvilView`
  — n'est pas non plus simulable par cette version de MockBukkit, voir le rapport).
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
- **Mobs spéciaux / boss** (issue #169, lot 1) — profils catégorisés SPECIAL/BOSS, attributs étendus
  (résistance au recul, taille, rayon d'explosion creeper), capacités Enragé et invocation de
  renforts en plus des trois précédentes, visuels BOSS (barre de vie + aura de particules), tirage
  aléatoire Wild à deux étages (throttle global puis tirage pondéré entre profils simultanément
  gagnants, BOSS toujours exclu). Éditeur Control Panel complet (`/mobs`) : créer/modifier/activer-
  désactiver un profil, régler le throttle, faire apparaître/nettoyer une instance de test — aucune
  YAML ni commande requise.
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
  `dialogue.choice.update` / `dialogue.choice.delete` (redirige vers un nœud existant *ou* termine
  le dialogue ; `delete` refuse le dernier choix d'un nœud et un choix porteur d'effets de jeu).
  Écriture sûre : localisation du fichier par `id`, refus d'un fichier
  déjà invalide, **sérialisation fidèle du dialogue complet** (`DialogueDefinitionWriter` — 10
  actions + 8 conditions + négation, aucune perte), **garde-fou round-trip** (re-parse en mémoire
  + égalité sémantique) avant écriture atomique, **rechargement** puis **restauration du contenu
  d'origine** si le fichier ne recharge pas. Le fichier édité adopte le **format canonique** du
  panel (commentaires / mise en forme d'origine non conservés — choix assumé). Cibles de choix =
  `<select>` des nœuds du dialogue (jamais un champ libre). Formulaires `<details>` semi-inline
  par nœud, aucun JS inline (conforme CSP `default-src 'self'`).
- **Édition d'un choix porteur d'actions / conditions (#82)** *(branche
  `feat/control-panel-admin-tools`)* — `dialogue.choice.update` n'est plus réservé aux « choix
  simples » : le texte et la cible d'un choix s'éditent même s'il porte une condition
  `QUEST_STATE` et une action `START_QUEST`, et **ces propriétés sont reconduites à l'identique**
  (la mutation repart du modèle métier du fichier, pas du formulaire). Deux propriétés sont en
  plus éditables par sélecteurs pré-remplis : l'**action de quête** (`quest_action` ∈ `keep` /
  `none` / `start_quest` / `advance_quest` / `turn_in_quest` + `quest_id`) et la **condition
  d'état** (`quest_condition` ∈ `keep` / `none` / un `QuestState` + `condition_quest_id` +
  `condition_negate`) — défaut `keep` : un paramètre absent ne touche à rien. Les actions et
  conditions non représentables sont **listées sous le choix** (« conservé à l'identique ») et,
  si un choix porte deux actions de quête, le formulaire bascule en `keep` plutôt que d'en perdre
  une. Champs de texte = source MiniMessage brute + **aperçu rendu** + **palette de couleurs**
  (réécrit seulement la balise englobante ; désactivée et annoncée sur un texte composite ;
  module `panel.js`, aucun JS inline). `dialogue.choice.delete` reste gardé.
  **Non fait** (suite de #82) : renommage / déplacement / suppression de nœud, réordonnancement
  des choix, édition des autres types d'actions et de conditions, rendu graphe interactif.
- **Aucune simplification silencieuse à l'enregistrement (#145 / #46)** *(branche
  `feat/control-panel-admin-tools`)* — `/dialogues/edit/<id>` repartait d'un formulaire à trois
  champs et réécrivait le fichier comme un **squelette à un nœud**, détruisant les autres nœuds,
  les choix, les conditions et les actions. Corrigé : le modèle `DialogueDraft` / `DialogueYaml`
  porte désormais les conditions et les actions (listes ordonnées de couples clé → valeur, format
  d'émission identique au moteur), l'enregistrement **repart du fichier réel** et n'applique que
  les champs du formulaire, et la page annonce ce qu'elle conserve. Si la relecture signale
  quelque chose de non représentable (table de traductions `title` / `description` / `text`,
  construction YAML exotique), l'enregistrement est **refusé** avec la raison — pour les dialogues
  comme pour les quêtes (`/quests/edit/<id>`, dont le formulaire reconstruit toute la quête).
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
- **Bug `/npcs` : PNJ Citizens réel masqué (issue #101)** *(branche
  `feat/control-panel-admin-tools`)* — un PNJ créé directement en jeu (`/npc create …`), sans
  fiche RPGQuest **ni** liaison, n'apparaissait dans aucune ligne de `/npcs` : la liste était
  construite **uniquement** à partir de `npc.list` (`NpcCatalog` = définitions + liaisons +
  références de contenu), qui ne connaît pas le registre Citizens ; le relevé `npc.citizens.list`
  n'alimentait que la ligne de synthèse et les noms des PNJ déjà liés. La cause n'était **ni**
  dans le registre Citizens runtime, **ni** dans l'action agent `npc.citizens.list` (qui renvoie
  bien toutes les entrées, PNJ libres inclus), **ni** dans le stockage — uniquement dans le rendu
  du Control Panel. Corrigé côté `AgentPages.npcs()` : chaque PNJ du registre Citizens sans
  `linkedNpcId` est désormais raccroché à la liste comme une **ligne d'identité physique**
  (libellé = nom en jeu, sous-titre `Citizens #N`, badges « sans fiche RPGQuest » + « non lié »,
  catégorie de filtre `unlinked`, recherche par nom / id numérique / UUID), avec un détail
  « Identité Citizens » (numéro, UUID, spawné) et deux actions facultatives : **Créer une fiche
  RPGQuest** (id pré-rempli = nom normalisé) et **Lier à une fiche existante** (liaison inverse :
  `npc.citizens.link` avec le Citizens fixé, choix de la fiche prête non liée). Nouvel état
  d'affichage `CITIZENS_ONLY` (information, jamais une erreur) + entrée `DiagnosticHelp` /
  section `pnj-depannage.md` associées ; `NpcDiagnosticProvider` émet un INFO `CITIZENS_ONLY` par
  PNJ Citizens libre sur `/diagnostics`. Le compteur `/npcs` inclut les PNJ Citizens libres.
  **Control Panel uniquement — aucune modification plugin/agent.**
- **Hotfix #103 — 502 Bad Gateway après connexion** *(branche `feat/control-panel-admin-tools`)* —
  suite à #101 : `/login` OK mais toute page authentifiée → 502. **Pas une régression du code
  #101.** Une ligne `agent_action` insérée directement en base pendant la validation live de #101
  avait un `created_at` sans « Z » (`2026-09-09T12:51:33`) ; `AgentStore.readAction` faisait
  `Instant.parse` sans tolérance → `DateTimeParseException` remontant par `NotificationCenter`
  (cloche de la topbar, rendue sur **toute** page authentifiée) → 500 → 502 nginx. `/login`
  n'affiche pas la cloche → le smoke anonyme « → 303 » ne l'a pas vu. **Fix** : (1) réparation de
  la ligne en base (UPDATE ciblé, ajout du « Z ») ; (2) durcissement `AgentStore` —
  `parseTimestamp` tolérant (formes héritées sans décalage → UTC), `created_at`/`received_at` →
  `Instant.EPOCH` en dernier recours, **frontière de sécurité par ligne** dans `recentActions`
  (ligne illisible ignorée + WARNING, jamais propagée). Régression
  `PanelHardeningMalformedAgentDataTest` (connexion owner réelle ; échoue sans le fix) + smoke
  optionnel contre une **copie de la base de prod réelle**. Règle : donnée agent invalide ⇒
  diagnostic, jamais crash du serveur web.
- **`/players` — annuaire d'administration des joueurs (issue #96)** *(branche
  `feat/control-panel-admin-tools`)* — la page passe d'un simple relevé « joueurs connectés » à un
  **annuaire** : connectés **+** joueurs hors ligne déjà venus. **Côté plugin/agent** : nouvelle
  action `player.catalog` (source de vérité = serveur Paper `OfflinePlayer` — aucune base joueurs
  propre à PlugAdmin ; `uuid`, `name`, `online`, `hasPlayedBefore`, `firstPlayed`, `lastSeen`,
  `banned` + raison, monde/position si en ligne), lecture disque sur thread asynchrone Bukkit ;
  actions `player.ban` / `player.unban` via l'API `BanList` de profil Paper (fonctionnent **hors
  ligne** ; expulsion si connecté ; raison obligatoire). Résolution nom↔UUID par le
  `PlayerDirectory` existant (déjà offline-aware). **Côté Control Panel** : modèle pur
  `PlayerCatalog` (parse + recherche pseudo/UUID + filtres Tous/En ligne/Hors ligne/Bannis + tri
  « récent »/« nom » + pagination 50, **tout côté serveur** pour tenir le volume). Page réécrite
  au pattern LISTE = synthèse / CLIC = détail (accordion) : badges **● En ligne** / **○ Hors
  ligne** / **Banni** (texte, pas que la couleur), sections **Identité / Activité / RPGQuest
  (liens) / Droits / Modération / Actions**. Chaque action déclare sa compatibilité hors ligne ;
  « donner un objet » est **en ligne uniquement** (indisponible expliqué, jamais de faux succès).
  Nouvelles permissions `PLAYER_MODERATE` (ban/unban) et `PLAYER_BUILD_WRITE` (OWNER seul). Toasts
  #93, historique dans `/actions` domaine Joueurs, fiche `/docs/joueurs-admin`.
  **`PLAYER_BUILD_WRITE` = livré comme permission mais fonctionnalité BLOQUÉE** : accorder un droit
  de construction *persistant* à un joueur hors ligne exige un gestionnaire de permissions
  persistant (permissions granulaires #27 — **non fusionnées sur cette branche** — + LuckPerms).
  La fiche affiche « non géré », sans faux interrupteur. Bans temporaires : hors périmètre.
- **Éditeur guidé de quêtes et de stories — chemin principal (issue #46)** *(branche
  `feat/control-panel-admin-tools`)* — depuis `/quests` (« Créer une quête ») et `/stories`
  (« Créer une story »), ou « Modifier » sur une carte : formulaire guidé multi-sections
  (Général / Prérequis / Objectifs / Récompenses / Variables ; Général / Chaîne de quêtes),
  **sans JavaScript** (aller-retour serveur, boutons `_action` pour ajouter / supprimer /
  réordonner). Les 10 types d'objectifs et 5 récompenses **réels** du moteur sont décrits par des
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
  (pas de second modèle).
  **V3 (passe UX ciblée éditeur de quêtes, issue #46)** : (1) *un type = une source de vérité* —
  pour chaque type d'objectif / récompense, le serveur émet le jeu de champs complet issu du même
  `Descriptors` ; un seul est visible+actif, les autres `hidden` + `disabled` (ni soumis, ni
  validés). `panel.js` (`initEditorForms`) bascule au changement de `<select>` sans recharger la
  page et **vide** les champs du type précédent (jamais de valeur d'un autre type conservée en
  douce) ; sans JavaScript le serveur re-rend le bon jeu au prochain aller-retour, et `_was`
  garantit le nettoyage côté serveur. (2) Les actions de brouillon (ajouter / supprimer /
  réordonner étape · objectif · récompense) portent `formnovalidate` : construire la structure
  n'est **jamais** bloqué par la validation HTML `required` ; la validation métier complète n'a
  lieu qu'à « Vérifier » / « Enregistrer ». (3) Chaque bloc a un id stable (`step-<i>`,
  `obj-<i>-<j>`, `rew-<i>`, `sec-*`) et chaque bouton d'action un `formaction=".../save#<ancre>"` :
  le scroll se recale sur le composant concerné après l'aller-retour (fallback JS
  `sessionStorage` pour les suppressions). (4) Les champs à liste longue (entité, matériau, PNJ,
  icône, catégorie, quête) deviennent des listes **recherchables** (`panel.js` `initCombo`,
  progressif, aucune dépendance) alimentées par la bonne source — la catégorie vient d'une liste
  curée `RefData.CATEGORIES` + saisie libre, l'icône des matériaux, plus jamais d'un mauvais
  registre. Entités / matériaux portent un libellé FR (`MinecraftNames`). « Points d'expérience »
  remplace « Montant » pour la récompense EXPERIENCE. `EditorDescriptorsTest` verrouille la
  cohérence des types. Action agent `quest.definition.validate` : toujours à faire.
  **V4 (passe UX ciblée, issue #46 — bugs navigateur confirmés)** : (a) le `<form class="editor">`
  porte `novalidate` **et** l'attribut HTML `required` n'est **plus émis** (le marqueur visuel
  « * » reste) — construire / supprimer / réordonner un brouillon ne peut plus déclencher
  « Veuillez renseigner ce champ », quel que soit le chemin de soumission (bouton, Entrée) ; la
  validation métier reste 100 % serveur, aux seuls « Vérifier » / « Aperçu » / « Enregistrer ».
  (b) **Conservation du scroll** : un POST qui renvoie du HTML 200 n'applique pas de façon fiable
  le fragment d'un `formaction`, et une ancre disparue (suppression) laissait la page en haut ;
  `panel.js` restaure désormais explicitement au chargement — cible du hash via
  `scrollIntoView({block:'center'})` + focus du premier champ, sinon position mémorisée. (c)
  **Bascule de type immédiate** : `applyType` est appliqué une fois à l'initialisation par
  `<select>` (sync défensive) puis à chaque changement ; chaque module `panel.js` est isolé
  (`try/catch`) — un module qui échoue n'empêche plus la bascule de type ni les combos. (d) La
  liste **PNJ** (`dl-npc`, champs *PNJ donneur* et objectif `TALK_TO_NPC`) porte un libellé
  humain : `<option value="guard" label="Garde">`, `RefData` transporte `id -> displayName`
  depuis `npc.list`, la recherche filtre sur le nom **et** l'id. (e) L'aide de la récompense
  `ITEM` précise « objet vanilla, pas un objet personnalisé RPGQuest » (le moteur ne lit qu'un
  `Material`), et la quantité d'une récompense n'affiche plus une aide d'objectif. Tests :
  `ContentEditorPagesTest` (+10 : `novalidate` + aucun `required`, ancre de scroll sur chaque
  bouton structurel, suppression d'une ligne à champs vides, isolation des 7 objectifs / 4
  récompenses, changement de type qui efface l'incompatible, libellés de PNJ, validation finale
  qui bloque toujours + aperçu conservé), `EditorDescriptorsTest` (+2).
  **V5 (passe UX ciblée éditeur de *stories*, issue #46)** : l'éditeur de story reçoit la même
  logique validée que l'éditeur de quêtes (construire le brouillon sans blocage → « Vérifier »
  → « Aperçu » YAML + diff → « Enregistrer » si valide ; `<form novalidate>`, aucun `required`,
  conservation du scroll, bouton « Actualiser le formulaire » ajouté pour la parité). Le modèle
  reste **strictement** celui du moteur (`StoryDefinition` : `id`, `name`, `secret`, liste
  ordonnée de `questIds`) — aucun champ inventé. **Sélection recherchable des quêtes** : la
  datalist `dl-quest` porte désormais le **titre humain** en plus de l'id
  (`<option value="first_steps" label="Premiers pas">`) ; `RefData` transporte `id -> titre`
  depuis le dernier `quest.list`, `questLabel()` le résout (namespace `rpgquest:` toléré), le
  combo `panel.js` filtre sur le **titre et l'id**. **Ordre clair** : chaque ligne de la chaîne
  affiche le rang `N.` + le titre humain au-dessus de l'identifiant technique éditable, avec les
  contrôles monter / descendre / retirer ; une référence absente du catalogue chargé est marquée
  d'un badge « quête inconnue » dès la construction. Ajout / suppression / réordonnancement
  ne déclenchent jamais la validation métier ; `StoryValidator` (id, nom, chaîne non vide,
  référence inconnue → WARNING, doublon → WARNING car le moteur l'autorise, round-trip) ne
  s'exécute qu'à « Vérifier » / « Enregistrer ». Le catalogue `/stories` (liste, détail
  accordéon, « Modifier la story », « Créer une story ») était déjà cohérent — inchangé. Tests :
  `StoryEditorPassTest` (nouveau, 17 : rendu new/edit, datalist titre+id, `questLabel`, titre
  au-dessus de l'id dans la ligne, badge quête inconnue, add/del/reorder sur brouillon
  incomplet, ancres de scroll, aucun `required`, id/nom/chaîne vide à la vérification, doublon +
  référence inconnue, aperçu = YAML écrit + round-trip, diff, conflit de hash).
- **Catalogue fusionné source + runtime pour `/quests` et `/stories` (issue #144)**
  *(branche `feat/control-panel-admin-tools`)* — jusqu'ici les pages `/quests` et `/stories` du
  Control Panel n'affichaient **que** le dernier relevé runtime de l'agent (`quest.list` /
  `story.list`). Une quête enregistrée depuis `/quests/new` (écrite dans le checkout source par
  `ContentWorkspace`) n'apparaissait donc **jamais** avant un rechargement RPGQuest côté serveur —
  et ne pouvait pas servir de prérequis ni composer une story. Nouveau `SourceCatalog`
  (`panel.content`, lecture seule) qui relit `quests/*.yml` + `stories/*.yml` via les mêmes
  `QuestYaml.read` / `StoryYaml.read` que l'éditeur. `AgentPages` **fusionne** les deux origines
  sur l'id « nu » et calcule un état par entrée : `SYNCED` (source + serveur), `SOURCE_ONLY`
  (enregistrée mais pas encore chargée en jeu → badge **« Source uniquement »** + note explicite),
  `RUNTIME_ONLY` (chargée par le serveur mais absente de la source → badge **« Hors source »**).
  La source est relue **à chaque affichage** ; « Rafraîchir » continue d'interroger le serveur.
  Jamais de fusion silencieuse : une entrée `SOURCE_ONLY` n'est **jamais** présentée comme active
  en jeu. `AgentPages.referenceData()` (lookups de l'éditeur : prérequis de quête, chaîne de
  story) fusionne aussi les quêtes de la source → une quête tout juste créée est immédiatement
  sélectionnable sans redémarrage Minecraft. Le diagnostic « quête inconnue dans la chaîne »
  d'une story tient compte des quêtes source. Les listes déroulantes d'**actions admin**
  (`quest.start` / `story.advance`…) restent limitées au runtime (une entrée source-only
  échouerait côté agent). Sans `content.repo-dir` configuré, aucun badge d'origine n'est affiché
  (aucune comparaison possible). **Aucun changement plugin, aucune action agent nouvelle, aucun
  `content.reload` déclenché** — édition et activation en jeu restent deux étapes distinctes.
  Tests : `SourceCatalogTest` (4), `MergedCatalogTest` (11 : runtime-only, source-only, fusion en
  une entrée, création éditeur visible sans appel agent, lookup story, lookup prérequis, refresh
  runtime qui préserve une source-only, aucune confusion « actif en jeu », édition source
  reflétée, story source-only).
- **Catalogue de dialogues fusionné source + runtime + création avec PNJ (issue #145)**
  *(branche `feat/control-panel-admin-tools`)* — même problème que #144 mais pour `/dialogues` :
  la page n'affichait que le relevé runtime `dialogue.list`, et la création passait par l'action
  agent `dialogue.definition.create` (écriture côté serveur DEV) — un dialogue créé restait
  invisible tant que le serveur ne l'avait pas rechargé. Désormais : `"dialogues"` est un `KIND`
  de `ContentWorkspace` ; nouveaux `DialogueDraft` / `DialogueYaml` (écriture au **format
  canonique du moteur**, identique octet pour octet au squelette `DialogueDefinitionYaml`) /
  `DialogueValidator` (`panel.content`). `SourceCatalog.dialogues()` relit `dialogues/*.yml`.
  `AgentPages` fusionne `dialogue.list` (runtime) et la source sur l'id « nu » avec le même
  triptyque d'états que #144 (`SYNCED` / `SOURCE_ONLY` badge **« Source uniquement »** /
  `RUNTIME_ONLY` badge **« Hors source »**), source relue à chaque affichage. La création se fait
  maintenant par l'éditeur source **`/dialogues/new`** (`ContentEditorPages`, comme `/quests/new`)
  — identité, PNJ à rattacher (recherche par nom ou id), locuteur, couleur, réplique de départ —
  qui écrit `dialogues/<id>.yml` dans le checkout. Si un PNJ est sélectionné, une **seconde**
  écriture met à jour sa définition via l'action agent existante `npc.definition.update`
  (`dialogue_id` = le nouveau dialogue ; champs `display_name` / `role` / `enabled` repris du
  dernier `npc.list` pour ne rien écraser) ; PNJ absent du relevé → dialogue enregistré quand
  même, rattachement signalé comme à refaire depuis la fiche PNJ (demi-état explicite).
  `dialogueSelectOptions` (select Dialogue de la fiche PNJ) fusionne aussi la source → un
  dialogue tout juste créé est immédiatement sélectionnable. Fiche PNJ : bouton **« Créer un
  dialogue pour ce PNJ »** → `/dialogues/new?npc=<id>` (locuteur prérempli). Un
  `DIALOGUE_DECLARED_MISSING` dont le dialogue existe dans la source est rétrogradé en **info**
  (« rechargement en attente »), pas une erreur. L'édition guidée des nœuds/choix (#82) reste
  réservée aux dialogues chargés par le serveur. L'action `dialogue.definition.create` reste
  whitelistée (compat) mais n'a plus de formulaire dédié. **Aucun changement plugin, aucun
  `content.reload`.** Tests : `DialogueYamlTest` (6), `SourceCatalogTest` (+1),
  `DialogueSourceMergeTest` (10 : A→J).
- **Resynchronisation après mutation + UX formulaires PNJ / Dialogues (issues #111 → #120)**
  *(branche `feat/control-panel-admin-tools`)* — plusieurs mutations réussissaient côté backend
  (notification SUCCESS) mais la page métier restait périmée : seule la séquence « Rafraîchir
  catalogue **puis** F5 » montrait le nouvel état. **Cause** : les pages lisent le **dernier
  relevé `*.list` réussi** stocké (`AgentPages#latestDetails` → `AgentStore`), et rien ne
  ré-enfilait ce relevé après une mutation. **Correctif mutualisé** : `AgentActionCatalog.Spec`
  gagne `refreshTypes()` (catalogues périmés par l'action) et `sensitive()` (action réellement
  sensible). `AgentEndpoints#handleActionResult` ré-enfile les relevés à la **première**
  transition SUCCESS (`created_by = "auto"`, dédup `AgentStore#hasOpenActionOfType`, jamais sur
  échec ni renvoi idempotent) ; `NotificationCenter` masque ces lignes `auto`,
  `/agents/actions.json` les expose (`auto:true`) et les compte dans `pending`. `panel.js` :
  un **unique** plan de rechargement s'arme quand une action **vue s'exécuter** (pending →
  succès) se termine, et se déclenche dès que `pending == 0` (garde `sawPending` +
  `sessionStorage`, repli 30 s) — plus aucun `location.reload()` par bouton. Correspondances :
  `npc.definition.*`→`npc.list` ; `npc.citizens.link/create`→`npc.list`+`npc.citizens.list` ;
  `dialogue.*`→`dialogue.list` ; `quest.giver.set`→`npc.list`+`quest.list` ;
  `player.ban/unban`→`player.catalog`. **UX** : `AgentPages#mutationConsent` supprime la case à
  cocher cachée des éditions réversibles (création/modif de définition, nœud, choix, liaison
  logique, `quest.giver.set`) — une phrase d'info suffit ; la confirmation explicite ne reste que
  pour `player.ban/unban`, `player.resetnew.confirm`, `npc.citizens.create`, `dialogue.choice.delete`.
  Aide métier sous chaque champ du formulaire PNJ, exemples en `placeholder` uniquement, lien doc
  `target=_blank`. Page `/dialogues` : bouton primaire `+ Nouveau dialogue` (fin de l'accordéon
  discret), bandeau « phase 1 / format canonique » réduit à une phrase + `<details>`, **palette
  de couleurs MiniMessage** (`AgentActionCatalog.PALETTE_COLORS`, 14 teintes, pastilles + aperçu
  `panel.js#initColorPalette`) qui génère `<couleur>…</couleur>` sans ré-enrober un MiniMessage
  déjà saisi. Tests : `CatalogResyncTest` (5), `AgentActionCatalogTest` (+3),
  `NpcsCatalogTest` / `DialoguesCatalogTest` mis à jour. **Non traité (délibéré)** : #114
  (sélecteur Citizens recherchable/paginé), #118 (sélecteur de locuteur alimenté par les PNJ),
  #113 (modale de liaison dédiée).
- **Export versionné du contenu déclaratif — phase 1 du pipeline de contenus (issue #108)**
  *(branche `feat/control-panel-admin-tools`)* — le Control Panel peut exporter quêtes, stories,
  dialogues et PNJ logiques dans un **format public versionné** `lodyquests-content-pack`
  (`schemaVersion: 1`, YAML déterministe), destiné à sauvegarder / archiver / fournir à une IA,
  et à alimenter l'import #109 puis la génération IA #110. **Couche d'export dans le plugin**
  (`com.lodygames.rpgquest.content.pack`) : DTO sans Bukkit (`ContentPack`, `*PackEntry`,
  `PackText`), `ContentPackMapper` (modèle runtime → DTO, jamais de sérialisation runtime directe,
  aucun accès disque), `ContentPackSerializer` (YAML canonique écrit à la main, ordre de clés
  fixe, familles triées par id, textes entre guillemets — octet-pour-octet reproductible),
  `ContentFamily` + `ContentPackAssembler` (`exportAll` / `exportFamily` / `exportElement` /
  `exportSelection`, manifest + counts, `exportedAt` injecté). **Vocabulaire = le schéma YAML
  RPGQuest existant** (contrat stable, pas une 2ᵉ représentation métier), donc re-parsable par les
  parseurs réels — round-trip visé pour #109. **Action agent lecture seule** `content.export`
  (`AgentActionType.CONTENT_EXPORT`, params `family` + `ids`), résultat sous `details.pack`, borné
  à ~56 Kio (plafond transport 64 Kio — au-delà : échec lisible, exporter par famille/élément).
  **Control Panel** : `Permission.CONTENT_EXPORT` (rôles en lecture), page `/content/export`
  (« Exporter tout » / par famille / sélection d'ids), route de téléchargement
  `GET /content/export/download?action=<id>` (`Content-Disposition: attachment`,
  `lodyquests-<famille>-<AAAA-MM-JJ>.yaml`), audit à l'enfilement et au téléchargement, entrée de
  menu. **Sécurité** : par construction le pack ne contient aucune donnée joueur / secret / chemin
  système / état runtime (les modèles source ne portent que du contenu éditorial) — vérifié par
  test. Le contenu `secret: true` **est** exporté (backup fidèle) avec son flag. Format documenté
  dans [docs/CONTENT_PACK.md](CONTENT_PACK.md) (exemple complet + stratégie `schemaVersion` +
  lien #109/#110). Tests : plugin `content.pack.*` (22) + `AgentActionExecutorTest` (+6) ;
  control-panel `AgentActionCatalogTest` (+2), `ContentExportNameTest` (3), `AuthenticatedSmokeTest`
  (`/content/export`). **Hors périmètre** (assumé) : import (#109), génération IA (#110), familles
  items/recettes, découpage/compression du transport, boutons d'export par ligne sur les 4 pages
  métier (la page dédiée couvre tous les critères #108).
- **Socle RBAC PlugAdmin — rôles, permissions et gestion des comptes (issue #50)**
  *(branche `feat/control-panel-admin-tools`)* — consolidation du contrôle d'accès du Control
  Panel autour d'un modèle **utilisateur → rôle → `Set<Permission>` → contrôle backend → UI
  filtrée**. Ces droits sont **propres à PlugAdmin**, sans aucune correspondance automatique avec
  OP / Paper / LuckPerms. **Rôles** (`authz.Role`) : `OWNER` (= `EnumSet.allOf(Permission.class)`,
  aucune liste à maintenir), `ADMIN` (exploitation serveur : joueurs, PNJ, dialogues,
  diagnostics, contenu, actions admin — **pas** de gestion des comptes ni du module dev/déploiement),
  `TESTER` (lectures + `ACTION_QUEST`/`ACTION_STORY`/`ACTION_VARIABLE_GET` — pas d'écriture de
  contenu, pas de reset, pas de modération), `BUILDER` (docs + infos PNJ/contenu — **pas** de
  données joueurs, pas d'action serveur), `CONTENT_EDITOR` (lecture + édition guidée quêtes /
  stories / dialogues / PNJ logiques — pas de modération, pas de spawn), `READ_ONLY` (aucune
  permission qui pilote une mutation d'`AgentActionCatalog`). Nouvelle permission `USER_MANAGE`
  (OWNER par défaut). Les rôles portent un libellé FR (`Role.label()`). **Comptes** stockés dans
  `control-panel.db`, table `panel_user` (migration additive idempotente `CREATE TABLE IF NOT
  EXISTS`, aucun rapport avec `data.db` ni MariaDB #42) : `users.PanelUser`,
  `users.UserRepository` (`SqliteUserRepository` / `InMemoryUserRepository`), `users.UserDirectory`
  (validation + garde-fous). Le compte `owner` d'environnement
  (`RPGQUEST_PANEL_OWNER_USERNAME` / `RPGQUEST_PANEL_OWNER_HASH`) est **réamorcé** au démarrage
  (créé s'il manque, sinon forcé actif + `OWNER` + hash réaligné) — chemin de récupération
  anti-verrouillage. **Auth** : `AuthService.authenticate` consulte `panel_user` (casse ignorée),
  coût PBKDF2 payé même compte absent / inactif, un compte **désactivé** ne peut pas se connecter
  (`Outcome.DISABLED`), `last_login_at` posé à la réussite. Hachage inchangé : `PasswordHasher`
  PBKDF2-HMAC-SHA256 210k itérations, sel 16 o, comparaison temps constant — audité, conservé.
  **Session** : re-contrôle par requête (`currentSession`) — un compte supprimé/désactivé perd sa
  session en cours, un changement de rôle prend effet sans reconnexion (`Session.refreshRole`).
  **Page `/users`** (`USER_MANAGE`, motif liste compacte → clic → détail) : `/users` (liste +
  formulaire de création : identifiant, mot de passe ≥ 12, rôle), `/users/<id>` (détail),
  `POST /users/create`, `POST /users/<id>/role`, `POST /users/<id>/active`. **Garde-fous** : le
  dernier OWNER actif ne peut être ni rétrogradé ni désactivé ; on ne peut pas désactiver son
  propre compte. **Sécurité** : chaque route et mutation exige la permission côté backend (403
  cohérent « Vous n'avez pas l'autorisation… » même en appel direct — masquer un bouton n'est pas
  une sécurité), CSRF synchroniseur sur tous les POST, aucune élévation via paramètre client,
  mot de passe jamais réaffiché ni journalisé. **Audit** : `user.create`, `user.role.change`
  (`from=…/to=…`), `user.active.change`, `login.failure` avec motif `compte désactivé` ;
  `login.success` porte `role=…`. **Navigation** : `Layout.nav()` et les tuiles `HomePages` sont
  filtrées par permission (groupe entièrement masqué s'il est vide) ; la topbar affiche
  l'utilisateur **et** son rôle (`userbox-r`). **Hors périmètre** (assumé, prévu plus tard sans
  bloquer l'architecture) : permissions par environnement/serveur, droits
  temporaires, approbation à deux niveaux, 2FA, SSO/OAuth, invitations, reset de
  mot de passe en self-service, suppression de compte (désactivation seulement). Tests :
  `RolePermissionMatrixTest`, `UserDirectoryTest`, `SqliteUserRepositoryTest`, `AuthServiceTest`,
  `UserManagementTest` (bout-en-bout : 403 backend, CSRF, audit, dernier OWNER, session d'un
  compte désactivé, navigation filtrée).
- **Groupes multiples et droits effectifs (issue #199)** — `PanelGroup` + trois tables additives
  (`panel_group`, `panel_group_permission`, `panel_user_group`) dans `control-panel.db`. Un compte
  appartient à **plusieurs** groupes ; ses droits effectifs sont l'**UNION** de son rôle et de ses
  groupes (`EffectivePermissions`). **La règle d'union est explicite et documentée** : le modèle
  existant n'a **aucun refus explicite** (`Role` est un `EnumSet`, `has` est un `contains`), donc
  rien à arbitrer — en introduire un rendrait « retirer un droit » ambigu. Conséquence assumée :
  **un groupe ne peut jamais réduire les droits d'un rôle**. **Anti-élévation de privilège par une
  seule règle générale** (`GroupDirectory`) : on ne peut ni accorder ni retirer une permission qu'on
  ne détient pas soi-même — ce qui ferme d'un coup la création d'un groupe `PLAYER_OP_WRITE` par un
  ADMIN, son ajout à un groupe existant, son retrait d'un groupe réservé, et son attribution ; la
  règle couvre automatiquement toute permission ajoutée demain. Second verrou redondant :
  `USER_MANAGE` reste OWNER-only. **Révocation immédiate** : les droits effectifs sont recalculés à
  **chaque requête** (`Session#refreshAuthz` depuis `PanelApp.currentSession`), donc retirer un
  groupe, le vider ou le supprimer agit sur les **sessions déjà ouvertes** sans reconnexion — et il
  n'existe volontairement **aucun cache** plus long qu'une requête. **Provenance** affichée sur
  `/users/<id>` : chaque droit accordé liste **toutes** ses origines (« rôle ADMIN », « groupe
  « Console » »), y compris quand il arrive par plusieurs chemins. **Migration sans élargissement** :
  `panel_user` n'est pas touché, les rôles ne sont pas convertis, une base existante démarre avec
  zéro groupe donc des droits strictement égaux à ceux du rôle ; une permission disparue du code est
  ignorée à la lecture plutôt que de casser la page. **Routes** `/groups`, `/groups/<id>`,
  `POST /groups/create|rename|permissions|delete` et `POST /users/groups`, toutes gardées par
  `USER_MANAGE` **sur les droits effectifs** côté backend, CSRF exigé, suppression confirmée par
  **ressaisie du nom**, et **audit de chaque mutation y compris refusée**. **Mobile** : grilles de
  cases à cocher `row-cols-1 row-cols-sm-2 row-cols-lg-3` et tables en `.table-responsive`, sans
  introduire un second style de mise en page. Tests : `EffectivePermissionsTest` (9),
  `GroupDirectoryTest` (20, dont 8 sur l'anti-élévation), `GroupAdminHttpTest` (14 de bout en bout
  en HTTP, dont la révocation sur session active par les trois chemins). **Aucun lien avec OP
  Minecraft ni LuckPerms** : le pont vers les droits Minecraft par monde est l'issue #200, **non
  livrée** — voir son rapport d'audit pour le blocage identifié.

- **PNJ — « regarder les joueurs » et promenade administrables depuis le panel (#165).** Deux
  nouvelles actions agent, `npc.citizens.lookclose` (trait Citizens `lookclose`) et
  `npc.citizens.wander` (fournisseur `wander` du trait Citizens `waypoints`). Toutes deux portent un
  état **explicite** `true`/`false` — il n'existe aucune valeur « inverser » — de sorte qu'un double
  clic, un retry réseau ou un rejeu par le cache d'idempotence aboutit au **même** état. Le regard
  expose la portée en blocs et est **relu sur le trait** après écriture (`LOOKCLOSE_NOT_APPLIED`
  plutôt qu'un succès trompeur). La promenade est **bornée** par une ancre et une zone
  (`x_range` 1-64, `y_range` 0-32) : `WanderGoal` filtre ses destinations par cette boîte, dans le
  monde du PNJ, qui ne peut donc ni en sortir ni changer de monde. L'**ancre est distincte de la
  position** et un déplacement manuel ne la déplace pas — la fiche et l'aide le disent. **Aucun
  écrasement silencieux** : Citizens n'accorde qu'un fournisseur de parcours par PNJ, donc
  `WanderChangePlanner` (pur, testé) distingue l'état neutre (`linear` vide, ce dans quoi Citizens
  laisse tout PNJ jamais configuré) d'une patrouille réelle, et refuse (`WANDER_CONFLICT`) en
  nommant ce qui serait perdu tant que la confirmation n'est pas donnée ; désactiver ne retire
  **que** la promenade et laisse intact un autre parcours. Permissions **inchangées** : le regard
  relève de `NPC_BIND_WRITE` (cosmétique, comme renommer/habiller), la promenade de
  `NPC_SPAWN_WRITE` (comme déplacer). **Nouvelle dépendance de compilation** :
  `net.citizensnpcs:citizens-main` en `compileOnly` strict (`isTransitive = false`), jamais
  empaqueté, sans amener WorldGuard ni Denizen ; toute la surface est confinée à
  `CitizensBehaviourBridge`, qui intercepte `LinkageError` et renvoie `CITIZENS_INCOMPATIBLE` sur une
  build inadaptée — le reste de l'intégration PNJ continue de fonctionner. Voir
  `docs/deployment/CITIZENS.md`. Tests : `WanderChangePlannerTest` (10), `NpcBehaviourActionTest`
  (14), ajouts à `NpcsCatalogTest` (12) et `NpcCitizensPayloadTest`. **Limite assumée** :
  `MockBukkit` n'embarque pas Citizens, donc le pont lui-même n'est couvert par **aucun** test
  automatisé — validation manuelle TC-255.

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
