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
  (`/rpgadmin story info|start|reset|resetwithquests`) — voir [storylines.md](storylines.md).
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

SQLite (`data.db`), migrations séquentielles via `SchemaMigrator` (version courante : **17** —
V15 réservation foncière des claims, V16 `item_travel_cooldowns` (cooldown Rune de rappel),
V17 `waystones` + `waystone_discoveries`). YAML pour tout ce qui est éditable à la main par un
administrateur (quêtes, zones, portails, stories, dialogues, items...).

## Non implémenté / hors périmètre à ce jour

- Chapitres et récompenses de palier pour les Stories (modèle prêt à l'extension — voir
  `current_index`, pas implémenté).
- Agrandissement de claim, équipement légendaire, points de compétence, PNJ Story du Wild, GUI
  Story avancée (explicitement hors périmètre de l'étape « progression automatique »).
- i18n effective (résolution par langue du joueur) — la donnée est prête (`LocalizedText`) mais
  la résolution n'est pas câblée.
- Commandes joueur pour les Stories (explicitement hors périmètre : UX finale prévue sans
  commande joueur).
