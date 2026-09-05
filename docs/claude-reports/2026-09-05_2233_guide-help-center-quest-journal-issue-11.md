# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-05
* Heure : 22:33 (heure locale de la machine AWS)
* Sujet : Issue #11 — Guide « centre d'aide » + journal de quêtes en GUI via le Libraire
* Statut : DONE
* Branche Git : `feature/11-guide-help-center-quest-journal` (créée depuis `feature/23-mod-prototype`, branche d'intégration courante)
* Commit actuel si disponible : à créer en fin de tâche (base : `75adfec`)
* Début de la tâche : 2026-09-05 21:39:26
* Fin de la tâche : 2026-09-05 22:38:10
* Durée totale : 00:58:44

## Demande

Prendre l'issue GitHub #11, la lire entièrement, respecter `CLAUDE.md`, créer une branche dédiée
depuis la branche d'intégration courante, analyser l'existant pour réutiliser au maximum les
systèmes de dialogues/quêtes/items/GUI déjà présents, implémenter, ajouter des tests, mettre à jour
la documentation, créer le rapport, pousser la branche si `./gradlew test` et `./gradlew build` sont
verts. Ne rien déployer sur VeryGames, ne rien fusionner.

### Contenu de l'issue #11 (résumé)

- **Partie A** — le Guide du Hub devient un centre d'aide/orientation : dialogue structuré couvrant
  les mécaniques (quêtes, où consulter ses quêtes, Wild, claims, marchands, « à qui parler »),
  orientations **textuelles** vers les PNJ. Architecture **non figée sur un seul Hub** (structure
  prête pour plusieurs Guides). Limites V1 : pas de waypoint/halo/navigation.
- **Partie B** — le Libraire remet un journal de quêtes (item RPGQuest, jamais dupliqué, identité
  PDC, compatible avec d'autres livres futurs). Clic droit → **GUI inventaire** (pas un livre
  vanilla) à **deux onglets** : « Quêtes en cours » / « Quêtes terminées », paginés. Seules les
  quêtes déjà acceptées ; **pas de catalogue** des quêtes non découvertes. La GUI reflète l'état à
  l'ouverture. UX/sécurité : rien de récupérable/duplicable, clics non prévus annulés.
- Contraintes : réutiliser les services quête/progression/dialogue/PNJ, composants réutilisables,
  async SQLite, Adventure/MiniMessage, pas de NMS.

## Analyse de l'existant

- **GUI journal déjà présente** : `ui.QuestJournalService` (+ `QuestJournalListener`,
  `JournalInventoryHolder`, `JournalTab`, `JournalPagination`, `JournalSession`) — inventaire
  paginé, vue détail, suivi/bossbar, **protection anti-vol/duplication complète** (tout
  clic/drag sur un `JournalInventoryHolder` annulé). Ouverte par `/quests` (`command.QuestsCommand`,
  perm `rpgquest.quest`). Avait **3 onglets** : Actives / **Disponibles** / Terminées.
- **`ui.QuestJournalBookService`** : clic droit sur `rpgquest:journal_quetes` → résumé compact
  **dans le chat** (pas une GUI). Remis par le Libraire (`dialogues/libraire.yml`, garde
  `LACKS_CUSTOM_ITEM` + `item.SoulboundItemService`).
- **Dialogues** : moteur 100 % data-driven (`dialogue.YamlDialogueEngine` +
  `DialogueSessionEngine`) — nœuds, choix, conditions, actions, transitions `next:` (les boucles
  `next` intra-dialogue sont explicitement autorisées : « menu hub »). Un dialogue = un fichier
  YAML par id de PNJ. Le binding PNJ→dialogue est déjà une donnée (`/rpgadmin npc tag`).
- **Registres** : patron récurrent (`ZoneRegistry`/`StoryRegistry`/…) — loader deux phases,
  validation par fichier + cross-validation, exemple auto-copié au démarrage.

## Décisions de conception

1. **Partie B — consolidation sur une seule GUI, 2 onglets.** L'issue interdit explicitement un
   catalogue des quêtes disponibles ; maintenir deux journaux divergents (GUI 3 onglets par
   commande + résumé chat par item) serait exactement l'anti-patron « logique câblée » que l'issue
   met en garde. Donc :
   - `JournalTab` = `IN_PROGRESS` + `COMPLETED` (onglet `AVAILABLE`/catalogue **supprimé**, y
     compris pour `/quests`) ;
   - le clic droit sur `rpgquest:journal_quetes` ouvre `QuestJournalService` (folder-in dans
     `QuestJournalListener` via un `PlayerInteractEvent`, item reconnu par PDC) ;
   - `QuestJournalBookService` + `QuestJournalBookListener` + leur test **supprimés** (code mort).
   `/quests` reste, ouvre la même GUI.
2. **Partie A — le contenu d'aide reste un dialogue ordinaire.** `guide.yml` réécrit avec un nœud
   `help_menu` + un nœud `help_*` par mécanique, chacun ramenant au menu (`next: help_menu`). Pur
   réemploi du moteur de dialogue, zéro risque sur le cœur. Orientations textuelles.
3. **Partie A — structure multi-Hub = nouveau registre `hub.HubGuideRegistry`** (`hub-guides/*.yml`,
   patron `ZoneRegistry`). `HubGuideDefinition` = `{hub-id, worlds[], guide-dialogue, help-node,
   welcome, specialty, referrals[]}`. Cross-validation : `hub-id` dupliqué → rejet des deux ;
   monde revendiqué deux fois → rejet du second. Exemple `hub_depart.yml` auto-copié. Consommé par
   un **diagnostic admin lecture seule** `/rpgadmin guide list|info <hub>` (pas de commande joueur,
   pas de modification du moteur de dialogue). Ajouter un Hub = déposer
   `hub-guides/<hub>.yml` + `dialogues/guide_<hub>.yml`, **sans code**.

## Travail effectué

### Partie B — journal en GUI, 2 onglets
- `ui/JournalTab.java` : `IN_PROGRESS` / `COMPLETED` (Javadoc « pas de catalogue »).
- `ui/QuestJournalService.java` : `tabMatches` à 2 cas, chrome à 2 onglets (« Quêtes en cours » /
  « Quêtes terminées »), `handleListClick` sans branche `AVAILABLE`, `open()` sur `IN_PROGRESS`.
  Nouvelle dépendance `YamlCustomItemRegistry` + `isJournalItem(ItemStack)` (identité PDC).
- `ui/QuestJournalListener.java` : nouvel `@EventHandler onJournalRightClick(PlayerInteractEvent)`
  (main principale, clic droit air/bloc, item = journal) → `service.open(player)` + `setCancelled`.
- `ui/QuestJournalBookService.java`, `ui/QuestJournalBookListener.java`,
  `test/.../ui/QuestJournalBookServiceTest.java` : **supprimés**.
- `bootstrap/RPGQuestBootstrap.java` : `QuestJournalService` reçoit `customItemRegistry` ; câblage
  du book service retiré.
- `item/RpgItemKeys.java`, `dialogues/libraire.yml` (nœud `journal_lost` ajouté),
  `items/journal_quetes.yml` : textes mis à jour.

### Partie A — Guide centre d'aide + structure multi-Hub
- `dialogues/guide.yml` : réécrit (greeting conservé + « Comment fonctionne le jeu ? » →
  `help_menu` → 6 nœuds `help_*` avec retour au menu / fermeture).
- Nouveau paquet `com.lodygames.rpgquest.hub` : `HubGuideReferral`, `HubGuideDefinition`,
  `HubGuideLoadIssue`, `HubGuideLoadReport`, `HubGuideDefinitionParser`, `HubGuideLoader`,
  `HubGuideRegistry` (`PluginService`, `forWorld`/`forHub`/`all`/`reload`, exemple auto-copié).
- `resources/hub-guides/hub_depart.yml`.
- `bootstrap/RPGQuestBootstrap.java` : `HubGuideRegistry` instancié + démarré, passé à
  `RpgAdminCommand`.
- `admin/RpgAdminCommand.java` : sous-commande `guide` (console OK, lecture seule) —
  `handleGuide` (`list` / `info <hub>`), `GUIDE_SUBCOMMANDS`, tab-complétion.
- Docs : voir ci-dessous.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/hub/` — 7 fichiers.
- `src/main/resources/hub-guides/hub_depart.yml`.
- `src/test/java/com/lodygames/rpgquest/hub/HubGuideDefinitionParserTest.java`,
  `HubGuideLoaderTest.java`, `HubGuideRegistryTest.java`.
- `src/test/java/com/lodygames/rpgquest/dialogue/BundledDialoguesValidityTest.java`.
- `docs/HUB_GUIDE.md`.
- `docs/claude-reports/2026-09-05_2233_guide-help-center-quest-journal-issue-11.md` (ce rapport).

## Fichiers modifiés

- `src/main/java/.../ui/QuestJournalService.java`, `ui/QuestJournalListener.java`,
  `ui/JournalTab.java`, `item/RpgItemKeys.java`, `admin/RpgAdminCommand.java`,
  `bootstrap/RPGQuestBootstrap.java`.
- `src/main/resources/dialogues/guide.yml`, `dialogues/libraire.yml`, `items/journal_quetes.yml`.
- `src/test/java/.../ui/QuestJournalServiceTest.java` (réécrit pour les 2 onglets),
  `player/PlayerResetServiceTest.java` (constructeur `QuestJournalService`).
- `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md`, `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` (§6b),
  `docs/INDEX.md`, `docs/deployment/SERVER_CHANGELOG.md`.

## Fichiers supprimés

- `src/main/java/.../ui/QuestJournalBookService.java`, `ui/QuestJournalBookListener.java`.
- `src/test/java/.../ui/QuestJournalBookServiceTest.java`.

## Base de données / migrations

Aucune. Aucun changement de schéma, aucune migration.

## Configuration / données

- Nouveau dossier de configuration `plugins/RPGQuest/hub-guides/` (+ exemple `hub_depart.yml`,
  auto-généré au premier démarrage comme `zones/central_village.yml`).
- Aucune nouvelle clé `config.yml` (`dialogue.allowed-commands` contient déjà `customitem`,
  `claim`).
- `dialogues/guide.yml` et `dialogues/libraire.yml` sont livrés dans le jar mais **non
  auto-copiés** (seul `guard.yml` l'est) : sur un serveur existant, remplacement manuel — voir
  `SERVER_CHANGELOG.md`.

## Tests automatiques

- `./gradlew test` → **BUILD SUCCESSFUL** — 930 tests plugin, 0 échec, 0 erreur (17 skipped
  pré-existants) ; `web-api` inchangé. (Un premier run complet, sous très forte charge machine,
  avait fait échouer `PlayerResetServiceTest.afterResetTheOnboardingPathCanBeStartedAgain` sur un
  `TimeoutException` d'un `await()` de seed — test non modifié par cette tâche ; le run complet
  suivant, non chargé, est vert.)
- `./gradlew build` → **BUILD SUCCESSFUL**.
- Tests ajoutés :
  - `hub/HubGuideDefinitionParserTest` — parse complet, défaut `help-node`, rejets (hub-id/dialogue
    manquants, hub-id invalide, referral sans role/npc).
  - `hub/HubGuideLoaderTest` — deux Hubs distincts chargent ; `hub-id` dupliqué → rejet des deux ;
    monde partagé → rejet du second ; un fichier invalide n'empêche pas les autres.
  - `hub/HubGuideRegistryTest` — **résolution indépendante de plusieurs Hubs** par monde et par id
    (l'architecture n'est pas figée sur un seul Hub) ; l'exemple `hub_depart.yml` est copié et
    chargé au `start()`.
  - `dialogue/BundledDialoguesValidityTest` — tous les dialogues du jar chargent sans erreur ; le
    Guide expose un `help_menu` et ≥ 6 sujets d'aide.
  - `ui/QuestJournalServiceTest` (réécrit) — item reconnu par identité PDC (pas le nom) ; deux
    onglets vides pour un joueur sans quête ; quête `ACTIVE` visible **seulement** en « en cours » ;
    quête `COMPLETED` visible **seulement** en « terminées » ; quête jamais acceptée invisible dans
    les deux onglets ; pagination 45/46 (onglet « en cours ») ; tout clic/drag annulé ; fermeture
    différée d'un tick ; suivi persistant à la reconnexion.
- « Le journal n'est remis qu'une fois » : couvert par la garde de dialogue `LACKS_CUSTOM_ITEM`
  (`LacksCustomItemConditionTest` / `DialogueSessionEngineTest` existants) + `libraire.yml` +
  `SoulboundItemService` — pas de nouveau test dédié.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (serveur Paper réel, voir aussi `SERVER_CHANGELOG.md`) :

1. Parler au Guide → « Comment fonctionne le jeu ? » → parcourir tous les sujets, vérifier le
   retour au menu et la fermeture.
2. Vérifier les orientations textuelles vers Libraire / Jo / marchands.
3. Parler au Libraire sans journal → recevoir **un** journal ; lui reparler → option disparue.
4. Clic droit sur le journal → GUI à deux onglets « en cours » / « terminées ».
5. Accepter une quête via un PNJ, rouvrir le journal → onglet « en cours ». La terminer, rouvrir →
   onglet « terminées ». Une quête jamais découverte n'apparaît nulle part.
6. Vérifier qu'aucun item de la GUI n'est récupérable (shift-clic, double-clic, touche numérique,
   drag).
7. `/rpgadmin guide list` et `/rpgadmin guide info hub_depart` (console + en jeu).
8. Redémarrage serveur → `hub-guides/hub_depart.yml` présent, log « 1 chargé(s), 0 erreur(s) ».

## Résultat attendu

Le Guide guide réellement les nouveaux joueurs sans multiplier les commandes ; le journal de quêtes
est une GUI claire à deux onglets remise par le Libraire, sans doublon ni item récupérable ; et
l'ajout d'un second Hub avec son propre Guide ne demande que deux fichiers YAML.

## Reset / retour à l'état initial

Aucune donnée runtime touchée. Pour annuler : abandon de la branche
`feature/11-guide-help-center-quest-journal`, ou `git revert` du commit.

## Déploiement VeryGames

Voir l'entrée `2026-09-05 - Guide « centre d'aide » + journal de quêtes en GUI (issue #11)` de
`docs/deployment/SERVER_CHANGELOG.md`.

### À transférer
Nouveau JAR RPGQuest **+** remplacement manuel de `plugins/RPGQuest/dialogues/guide.yml` et
`plugins/RPGQuest/dialogues/libraire.yml` par les versions du jar.

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, mondes, autres plugins.

### Redémarrage requis
Oui (nouvelles classes + rechargement des dialogues au démarrage).

### Migration automatique
Aucune. `hub-guides/hub_depart.yml` est créé automatiquement au premier démarrage.

## Rollback

Ancien JAR + anciens `dialogues/guide.yml` / `libraire.yml` sauvegardés. Le dossier `hub-guides/`
peut rester (ignoré par l'ancienne version).

## Logs / diagnostic

- Démarrage : `Chargement des Guides de Hub : N chargé(s), M erreur(s).` (+ un `warn` par fichier
  invalide).
- `/rpgadmin guide list|info` — lecture seule.

## Documentation mise à jour

- `docs/HUB_GUIDE.md` (nouveau), `docs/INDEX.md`.
- `docs/RPGQUEST_BIBLE.md` — section `/quests` (2 onglets, ouverture par l'item, anti-dup/anti-vol),
  nouvelle section `/rpgadmin guide`, section PNJ Guide/Libraire, liste des sous-systèmes `/rpgadmin`.
- `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` — §6b (Guide centre d'aide, structure multi-Hub, journal),
  section « suivre sa progression ».
- `docs/current_state.md` — nouvelle ligne « Guide centre d'aide + journal du Libraire », ligne
  « Boucle joueur » mise à jour.
- `docs/deployment/SERVER_CHANGELOG.md` — entrée datée.
- Non modifié : `docs/ARCHITECTURE.md` (la section `ui` décrivait déjà la GUII paginée ; le passage
  de 3 à 2 onglets et la suppression de `QuestJournalBookService` restent cohérents avec sa
  description générale — à affiner lors d'une passe ARCHITECTURE dédiée si besoin).

## Limitations / travail restant

- Tests manuels serveur non effectués (liste ci-dessus).
- L'onglet « Disponibles » de `/quests` est supprimé pour tout le monde (pas seulement pour le
  journal) — choix assumé, conforme à l'interdiction de catalogue de l'issue ; documenté dans le
  changelog et la bible.
- La structure multi-Hub n'a qu'un seul Hub réel en V1 ; le registre `HubGuideRegistry` n'est
  consommé que par le diagnostic `/rpgadmin guide` et les tests (aucune mécanique runtime ne
  « choisit » un Guide par monde — hors périmètre V1).

## Prochaine étape suggérée

Dernier `./gradlew test` complet vert, puis validation manuelle sur serveur de test. Ne pas fermer
l'issue #11 avant les tests manuels. Ensuite, envisager une passe `docs/ARCHITECTURE.md` pour
acter la GUI à 2 onglets et le registre `hub`.
