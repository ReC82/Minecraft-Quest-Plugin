# État actuel du projet

Snapshot de ce qui est **actuellement implémenté** dans RPGQuest — une vue d'ensemble rapide, pas
une référence détaillée (voir [RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md) et [INDEX.md](INDEX.md) pour
le détail par système). À mettre à jour à chaque étape livrée qui ajoute/change un système.

## Systèmes implémentés

- **Quêtes** — définitions YAML, 7 types d'objectifs, prérequis, récompenses, progression
  persistée par joueur (`quest.progress.QuestProgressEngine`).
- **Storyline** *(nouveau)* — conteneur logique ordonné de quêtes existantes, indépendant du moteur
  de quête, état `NOT_STARTED`/`ACTIVE`/`COMPLETED` par joueur, commandes admin/debug uniquement
  (`/rpgadmin story info|start|reset`) — voir [storylines.md](storylines.md).
- **Dialogues** — graphes de nœuds PNJ (Citizens et entités vanilla), conditions et actions par
  choix, rendu via Paper Dialog.
- **NPC / Citizens** — identité logique RPGQuest découplée du nom affiché de l'entité.
- **Zones protégées** — cuboïdes avec flags configurables (PvP, casse, explosions, etc.),
  outil de sélection dédié (indépendant de WorldEdit, voir la note ci-dessous).
- **Portails** — `/rpgadmin portal` (canalisation, coût, cooldown, conditions quête/niveau) et
  `/rpgadmin worldportal` (téléportation instantanée entre mondes, sans coût).
- **Mondes** — création/chargement de mondes supplémentaires, règles du monde Hub (jour/météo
  permanents), séparation Hub/wild.
- **Claims** — terrains protégés créés par les joueurs eux-mêmes, confiance par UUID.
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

## Bug connu et corrigé cette session

- **Sélection RPGQuest vs WorldEdit** : l'outil `/rpgadmin zone wand` utilisait le même matériau
  que la wand par défaut de WorldEdit (`WOODEN_AXE`), causant une collision. Corrigé (matériau
  `BLAZE_ROD`, écoute robuste même si un autre plugin annule l'interaction en premier).
- **Fallback de message manquant affiché en jeu** : une clé `messages.yml` absente s'affichait
  littéralement (`[message manquant : ...]`) y compris en Title/Subtitle plein écran. Corrigé
  (fallback discret + log serveur, jamais le nom technique de la clé côté joueur).
- **Instrumentation `TP-TRACE`** : logs temporaires ajoutés devant chaque téléportation déclenchée
  par RPGQuest (`WorldPortalTeleportListener`, `PortalService`, `RpgAdminCommand`, `SpawnService`)
  pour diagnostiquer un bug de téléportation automatique dans le Hub — voir les commentaires
  `TODO(debug bug TP hub)` dans le code, à retirer une fois la cause confirmée. Gap async corrigé
  au passage dans `PortalService` (vérification `isOnline()` avant `startChanneling`/`finishTeleport`
  dans les callbacks asynchrones).

## Persistance

SQLite (`data.db`), migrations séquentielles via `SchemaMigrator` (version courante : **13**,
ajoutée par cette étape pour `story_progress`). YAML pour tout ce qui est éditable à la main par un
administrateur (quêtes, zones, portails, stories, dialogues, items...).

## Non implémenté / hors périmètre à ce jour

- Progression automatique d'une Story à partir de la complétion de ses quêtes (prévu, pas câblé).
- Chapitres et récompenses de palier pour les Stories (modèle prêt à l'extension, pas implémenté).
- i18n effective (résolution par langue du joueur) — la donnée est prête (`LocalizedText`) mais
  la résolution n'est pas câblée.
- Commandes joueur pour les Stories (explicitement hors périmètre : UX finale prévue sans
  commande joueur).
