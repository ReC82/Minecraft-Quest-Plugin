# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-05
* Heure : 20:02
* Sujet : Reset admin complet « nouveau joueur » — commande `/rpgadmin player resetnew <joueur> confirm` qui remet l'état RPGQuest d'un seul joueur (en ligne ou hors ligne) dans l'équivalent d'un joueur jamais connecté, pour retester tout le parcours d'onboarding.
* Statut : DONE — `./gradlew clean build` **BUILD SUCCESSFUL** (voir « Tests automatiques »)
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `83d4c4f` (HEAD) — ce travail reste dans l'arbre de travail (non commité), **par-dessus** les deux lots non commités précédents (`2026-09-05_1729` corrections UX claims, `2026-09-05_1837` boucle joueur Hub ↔ Wild).
* Début de la tâche : `non mesurable` (timestamp de début non capturé au lancement de la tâche ; le rapport précédent s'est terminé à 18:52:49, la campagne de tests de celui-ci tournait à ~20:00)
* Fin de la tâche : 2026-09-05 20:08:00
* Durée totale : `non calculable` (début non capturé — voir ci-dessus)

## Demande

Ajouter un reset admin ciblé, réutilisant au maximum les resets existants, permettant de simuler
un vrai nouveau joueur RPGQuest :

- commande ADMIN explicite (`/rpgadmin player resetnew <joueur>`, nom adaptable aux conventions) ;
- remet l'état **gameplay RPGQuest** du joueur à l'équivalent « jamais joué » ;
- **ne** supprime **pas** compte Minecraft / UUID / playerdata vanilla / données externes sans
  nécessité ;
- couvre : état first join/onboarding, Stories + progression, quêtes (actives/progression/
  terminées/suivie), variables joueur dont `CLAIM_TIER_1` et autres unlocks, progression RPG/
  niveaux/XP RPG si elle appartient au profil RPGQuest, découvertes de hubs si ce système existe,
  hub de départ/home sélectionné si implémenté, découvertes de Waystones si persistées par joueur,
  état journal/livre de quêtes, état objets spéciaux/soulbound/rappel si persisté, cooldowns
  RPGQuest persistants, claim principal + membres/trust, tout autre état RPGQuest ;
- **inventaire** : éviter que le joueur garde des objets RPGQuest qui fausseraient le test ;
  réutiliser l'identification CustomItem existante ; ne jamais vider l'inventaire vanilla ;
- **online/offline** : fonctionner pour les deux ; si une opération exige la connexion (nettoyage
  d'inventaire Bukkit), soit la différer au prochain login, soit expliquer clairement la
  limitation — jamais faire semblant ;
- **sécurité** : admin uniquement, protection anti-erreur selon les conventions admin existantes,
  ciblé sur UN joueur ; ne jamais wipe `data.db`, reset d'autres joueurs, supprimer des mondes,
  toucher aux PNJ Citizens, supprimer des définitions de quêtes/Stories, modifier les portails,
  modifier les Waystones globales générées, toucher aux constructions Hub/Wild ;
- **claim** : supprimer le claim principal de la base (pour refaire Story → CLAIM_TIER_1 → Jo →
  Acte → création du claim) **sans** détruire les blocs physiques — après reset, l'ancienne zone
  n'est simplement plus son claim ;
- **résultat** : afficher un résumé admin concis ;
- **tests** ciblés : reset quêtes, reset Story, reset variables/unlocks, suppression du claim
  principal, reset des découvertes persistantes existantes, isolation (aucun autre joueur
  modifié), possibilité de recommencer le parcours, comportement offline si supporté ; ne pas
  inventer de systèmes juste pour cocher la liste ;
- exécuter les tests puis `./gradlew clean build` ;
- **documentation** : mettre à jour la doc admin appropriée + ajouter la commande dans la doc
  permanente du projet ; rapport Claude avec une section clairement identifiable
  `## Reset complet "nouveau joueur"` (commande exacte, permission, online/offline, données
  supprimées, données conservées, inventaire, claim, reconnexion, exemple exact pour LoDyMcFly,
  limitations) ; référence courte dans `docs/claude-reports/README.md` et dans la doc admin
  permanente ; format de rapport permanent conservé ;
- **déploiement** : indiquer explicitement si seul le JAR doit être transféré ou d'autres
  fichiers, et s'il faut redémarrer le serveur ;
- **STOP après cette fonctionnalité.**

## Analyse

Resets **déjà présents** (vérifiés avant d'écrire quoi que ce soit) — réutilisés tels quels :

- `ClaimService#resetTierOneClaimForTesting(UUID)` — supprime **tous** les claims du joueur
  (`ClaimRepository#delete`, cascade `claim_members`), recharge le cache mémoire, remet
  `CLAIM_TIER_1`. Déjà exposé par `/claim admin resettier1 <joueur>` (permission
  `rpgquest.admin.world`, fonctionne hors ligne).
- `QuestProgressEngine#resetAllQuests(UUID)` — supprime `quest_progress`/`quest_objective_progress`,
  vide le cache actif, notifie (`onProgressChanged` → le journal `/quests` retire sa bossbar de
  suivi).
- `StoryService#reset(UUID, "all")` — supprime toute progression `story_progress`, vide le cache.
- `WaystoneService#resetDiscoveries(UUID)` — `waystone_discoveries` + cache.

**Systèmes inexistants** (donc N/A, pas créés artificiellement) : aucune « découverte de hubs » par
joueur ; aucun « home/hub de départ sélectionné » par joueur (`SpawnService` est un spawn global
unique, pas une donnée par joueur). L'« état journal/livre de quêtes » = l'objet custom dans
l'inventaire + la quête suivie (variable `__tracked_quest__`) — pas de table dédiée.

**Manquait** uniquement : suppression par joueur des variables, de la progression RPG
(`player_skills`/`xp_grants`), et des cooldowns persistants (`portal_cooldowns`,
`item_travel_cooldowns`). Ajouté a minima.

`RpgAdminCommand` a déjà le patron « sous-commande qui cible un joueur en argument, utilisable
console + hors ligne » (`story`, via `resolveTargetPlayer`) — réutilisé pour `player`.

## Travail effectué

### Nouveau service d'orchestration

`player.PlayerResetService#resetToNewPlayer(UUID, String)` → `CompletableFuture<ResetSummary>` :

1. en parallèle : `resetAllQuests`, `storyService.reset("all")`, `waystoneService.resetDiscoveries`,
   `progressionRepository.resetPlayer`, `portalCooldownRepository.deleteAllForPlayer`,
   `itemTravelCooldownRepository.deleteAllForPlayer` ;
2. puis `claimService.resetTierOneClaimForTesting` (claims + `CLAIM_TIER_1="false"`, cascade
   membres) ;
3. puis `variableRepository.deleteAllForPlayer` — efface **toutes** les variables (dont ce
   `"false"`, `RUNE_RAPPEL_GRANTED`, `__tracked_quest__`, tout unlock) → état final : aucune ligne ;
4. sur le thread principal :
   - **joueur en ligne** : retire les objets custom RPGQuest de l'inventaire (`removeRpgItems`,
     identification PDC via `YamlCustomItemRegistry#isCustomItem`, 36 cases + armure + main
     secondaire + curseur ; inventaire vanilla intact), puis `progressionService.loadForPlayer`,
     `portalService.reloadCooldownsForPlayer`, `itemTravelService.reloadCooldownsForPlayer`,
     `questJournalService.clearTrackingFor` ;
   - **joueur hors ligne** : pose la variable persistante `__pending_new_player_reset__` et
     `progressionService.unloadForPlayer` (sûr si non chargé).

`ResetSummary(boolean online, int inventoryItemsRemoved, boolean inventoryDeferred)`.

### Nettoyage d'inventaire différé (offline)

`player.NewPlayerResetJoinListener` (`PlayerJoinEvent`, priorité `LOWEST`) : si
`__pending_new_player_reset__` est posé, retire les objets custom RPGQuest et efface le marqueur —
**avant** `StarterKitListener` (`NORMAL`), qui redonne ensuite la Rune de départ. Ordre
déterministe : dispatch d'événement par priorité + exécuteur SQLite mono-thread FIFO + scheduler
thread principal FIFO.

### Commande

`RpgAdminCommand#handlePlayer(CommandSender, String[])`, branché avant la contrainte « joueur en
jeu » (comme `story`) → `player resetnew <joueur> [confirm]` :
- permission `rpgquest.admin.world` (constante `PERMISSION` existante) ;
- **protection anti-erreur** : sans `confirm`, affiche seulement l'avertissement listant ce qui
  serait effacé (même esprit que `/rpgadmin flatten confirm`) ;
- `resolveTargetPlayer` (online **ou** offline, console incluse) ;
- résumé admin concis après coup (systèmes réinitialisés, inventaire immédiat/différé, données
  conservées volontairement) ;
- complétion de tab : `player` dans `TOP_LEVEL_SUBCOMMANDS`, `PLAYER_SUBCOMMANDS = ["resetnew"]`,
  pseudos en ligne, `confirm`.

### Nouvelles méthodes de repository (par joueur, additives, aucune migration)

- `PlayerVariableRepository#deleteAllForPlayer(UUID) → Integer`
- `ProgressionRepository#resetPlayer(UUID)` (transaction : `xp_grants` puis `player_skills`)
- `PortalCooldownRepository#deleteAllForPlayer(UUID) → Integer`
- `ItemTravelCooldownRepository#deleteAllForPlayer(UUID) → Integer`

### Petites méthodes de service publiques (invalidation ciblée)

- `PortalService#reloadCooldownsForPlayer(UUID)` (extrait de `handleJoin`)
- `ItemTravelService#reloadCooldownsForPlayer(UUID)` (extrait de `handleJoin`)
- `QuestJournalService#clearTrackingFor(UUID)` (oubli quête suivie + bossbar)

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/player/PlayerResetService.java`
- `src/main/java/com/lodygames/rpgquest/player/NewPlayerResetJoinListener.java`
- `src/test/java/com/lodygames/rpgquest/player/PlayerResetServiceTest.java`
- `docs/ADMIN_PLAYER_RESET.md`
- `docs/claude-reports/2026-09-05_2002_reset-admin-nouveau-joueur.md` (ce rapport)

## Fichiers modifiés

- `bootstrap/RPGQuestBootstrap.java` — construction de `PlayerResetService`, enregistrement de
  `NewPlayerResetJoinListener`, passage de `playerResetService` à `RpgAdminCommand`.
- `admin/RpgAdminCommand.java` — sous-commande `player resetnew` (+ dispatch console, tab).
- `database/PlayerVariableRepository.java`, `database/ProgressionRepository.java`,
  `database/PortalCooldownRepository.java`, `database/ItemTravelCooldownRepository.java` — méthodes
  de suppression par joueur.
- `travel/PortalService.java`, `travel/ItemTravelService.java` — `reloadCooldownsForPlayer(UUID)`.
- `ui/QuestJournalService.java` — `clearTrackingFor(UUID)`.
- `docs/INDEX.md`, `docs/RPGQUEST_BIBLE.md` — référence de la commande.

## Base de données / migrations

**Aucune migration.** Uniquement des `DELETE ... WHERE player_uuid = ?` sur des tables existantes
(`player_variables`, `player_skills`, `xp_grants`, `portal_cooldowns`, `item_travel_cooldowns`) et
la réutilisation des suppressions existantes (`quest_progress`, `quest_objective_progress`,
`story_progress`, `waystone_discoveries`, `claims` + cascade `claim_members`). `data.db` n'est
jamais remplacé ; `player_profiles` (nom/UUID/`created_at`) n'est jamais touché.

## Configuration / données

Aucune nouvelle clé de configuration. Nouvelle variable joueur interne
`__pending_new_player_reset__` (posée puis consommée automatiquement au login pour un reset fait
hors ligne).

## Tests automatiques

`./gradlew clean build` : **BUILD SUCCESSFUL** (voir la sortie dans la session).

`PlayerResetServiceTest` (4 tests, harness MockBukkit + SQLite réel, plugin complet chargé) :

- `resettingAnOfflinePlayerWipesEveryOnboardingSystemAndSetsThePendingInventoryFlag` — un UUID
  jamais connecté : quêtes, Stories, variables (`CLAIM_TIER_1` + unlock arbitraire), progression
  RPG, cooldowns portails + Rune, découvertes de Waystones **et** le claim principal sont supprimés
  de la base ; `summary.online()==false`, `inventoryDeferred()==true`, marqueur
  `__pending_new_player_reset__` posé.
- `resettingAnOnlinePlayerAlsoRemovesRpgItemsButKeepsVanillaItems` — joueur en ligne : objets
  RPGQuest (Pierre de retour + Journal ajoutés, éventuelle Rune de départ) retirés de l'inventaire,
  le diamant vanilla reste, `summary.online()==true`, état base vidé.
- `resettingOnePlayerNeverTouchesAnother` — deux joueurs semés à l'identique ; reset de A → état de
  B intact (quêtes, variables, claim, `player_skills` GLOBAL = 750).
- `afterResetTheOnboardingPathCanBeStartedAgain` — après reset : `hasClaimTierOne` de nouveau
  `false`, quête `premiers_pas` de nouveau `NOT_STARTED`, `story_progress` vide.

## Tests manuels à effectuer

Sur VeryGames (client Minecraft réel) :

1. `/rpgadmin player resetnew LoDyMcFly` (sans `confirm`) → avertissement uniquement, rien n'est
   modifié.
2. `/rpgadmin player resetnew LoDyMcFly confirm` avec LoDyMcFly **en ligne** → résumé admin ;
   vérifier : plus de claim principal (Jo ne propose plus « me rendre sur ma propriété »), Story
   remise à zéro, quête d'intro reproposée par le Guide, inventaire vidé de ses objets RPGQuest
   (Acte/Pierre/Journal/Rune) mais objets vanilla intacts, Rune de rappel redonnée (kit de départ),
   cooldown de Rune/portail effacé.
3. Refaire le parcours complet : Guide → quête → `CLAIM_TIER_1` → Jo → Acte → clic droit dans le
   monde des claims → nouveau claim (y compris au même endroit que l'ancien).
4. `/rpgadmin player resetnew <joueur hors ligne> confirm` → résumé indiquant le nettoyage
   d'inventaire **différé** ; à la reconnexion du joueur, vérifier que ses objets RPGQuest sont
   retirés puis qu'il reçoit une Rune de départ.
5. Isolation : reset d'un joueur de test, vérifier qu'un autre joueur (claim, quêtes, niveaux) est
   inchangé.

## Résultat attendu

Un seul joueur repart de zéro côté gameplay RPGQuest, sans perte de compte/UUID/inventaire vanilla,
sans impact sur les autres joueurs ni sur le monde. Le parcours d'onboarding est intégralement
rejouable.

## Reset / retour à l'état initial

`git checkout -- <fichiers>` / suppression des fichiers créés. Aucune donnée SQLite existante n'est
migrée ; rien à défaire côté base (les nouvelles requêtes ne sont que des suppressions déclenchées
à la demande).

## Déploiement VeryGames

### À transférer
- **Uniquement le nouveau JAR** (`build/libs/`) après `./gradlew clean build`.

### Ne PAS transférer/altérer
- `data.db` (aucune migration, aucune modification de schéma).
- `config.yml` (aucune nouvelle clé).
- `dialogues/`, `items/`, `quests/`, `stories/`, `portals/`, `world-portals/`, `zones/` — inchangés
  par cette intervention.

### Redémarrage requis
- **Oui** — nouveau code Java (nouveau service, nouveau listener, nouvelle sous-commande, nouvelles
  méthodes de repository). Un simple `/rpgquest reload` ne suffit pas.

### Migration automatique
- Aucune.

## Rollback
Redéployer le JAR précédent. Aucune donnée persistée n'a changé de forme ; les variables
`__pending_new_player_reset__` éventuellement restées en base (reset offline non encore consommé)
sont inertes sans ce code.

## Logs / diagnostic
`NewPlayerResetJoinListener` journalise en `INFO` (`[player resetnew] Inventaire RPGQuest de <nom>
nettoyé à la reconnexion (<n> objet(s) retiré(s)).`). Le résumé du reset est envoyé à l'exécutant
de la commande, jamais journalisé. Aucune instrumentation temporaire.

## Documentation mise à jour
- `docs/ADMIN_PLAYER_RESET.md` (nouveau) — page dédiée complète.
- `docs/INDEX.md` — ligne d'index vers la nouvelle page.
- `docs/RPGQUEST_BIBLE.md` — section « Reset « nouveau joueur » — `/rpgadmin player resetnew` » dans
  le bloc des commandes admin `/rpgadmin`.
- `docs/claude-reports/README.md` — ligne d'index de ce rapport.

## Limitations / travail restant
- **Répit de premier login vanilla** (`PlayerSpawnLocationEvent` / redirection vers le spawn du
  Hub) : ne se redéclenche pas (le playerdata vanilla n'est pas touché,
  `hasPlayedBefore()` reste `true`). Sans impact sur le parcours testé (quête d'intro,
  `CLAIM_TIER_1`, kit de départ, dialogues sont bien remis à zéro).
- **Économie / backpacks / entitlements / annonces de marché** : conservés volontairement (hors
  parcours d'onboarding, données potentiellement liées à des achats/objets en dépôt). À remettre à
  zéro via `/money`, `/backpack`, etc. si un test le nécessite.
- **Reset hors ligne** : le nettoyage d'inventaire est **différé** au prochain login ; tant que le
  joueur ne s'est pas reconnecté, ses objets RPGQuest sont encore présents. Le résumé admin le dit
  explicitement — aucune donnée n'est présentée comme nettoyée alors qu'elle ne l'est pas.
- **Timestamp de début** de cette tâche non capturé (voir `Informations`) — durée non calculable.

## Prochaine étape suggérée
Validation manuelle des 5 scénarios ci-dessus sur VeryGames, puis commit de ce lot **avec** les
deux lots non commités précédents (`2026-09-05_1729` et `2026-09-05_1837`).

---

## Reset complet "nouveau joueur"

**Commande exacte**
```
/rpgadmin player resetnew <joueur>            # avertissement seulement
/rpgadmin player resetnew <joueur> confirm    # exécute
```

**Permission** : `rpgquest.admin.world` (unique pour tout `/rpgadmin`). Utilisable **depuis la
console**. Protection anti-erreur : mot `confirm` obligatoire.

**Online / offline**
- **En ligne** : tout est fait immédiatement, y compris le retrait des objets RPGQuest de
  l'inventaire et l'invalidation des caches mémoire (progression, cooldowns, quête suivie).
- **Hors ligne** : toutes les suppressions en base sont faites immédiatement ; le **nettoyage
  d'inventaire est différé** à la prochaine connexion (marqueur persistant
  `__pending_new_player_reset__`, consommé par `NewPlayerResetJoinListener` en priorité `LOWEST`,
  donc avant la redistribution du kit de départ). Le résumé admin annonce clairement ce report.

**Données supprimées** (RPGQuest, ce joueur uniquement)
- Quêtes : actives, progression d'objectifs, terminées, quête suivie (+ bossbar).
- Stories : toute progression (`story_progress`, mode `all`).
- **Toutes** les variables joueur (`player_variables`) — `CLAIM_TIER_1`, `RUNE_RAPPEL_GRANTED`
  (kit de départ), tout autre unlock.
- Progression RPG : `player_skills` (niveaux/XP RPG) + `xp_grants` (dédup des octrois).
- Découvertes de Waystones : `waystone_discoveries`.
- Cooldowns persistants : `portal_cooldowns` + `item_travel_cooldowns` (Rune de rappel).
- Claim principal : ligne `claims` (`main_<uuid>`) + `claim_members` (cascade).
- Inventaire : objets personnalisés RPGQuest (identifiés par PDC, jamais par matériau).

**Données volontairement conservées**
- Compte Minecraft, UUID, `player_profiles`, playerdata vanilla.
- Économie (`wallets`, `transactions`), backpacks/entitlements, annonces de marché
  (`market_listings`).
- Blocs construits, Waystones globales (`waystones`), mondes, PNJ Citizens, définitions de
  quêtes/Stories, portails, zones. `data.db` jamais wipé ; aucun autre joueur touché.

**Comportement de l'inventaire**
- L'inventaire **vanilla n'est jamais vidé**. Seuls les objets marqués comme objets personnalisés
  RPGQuest sont retirés (36 cases + armure + main secondaire + curseur). En ligne : immédiat. Hors
  ligne : au prochain login, avant le kit de départ.

**Comportement du claim**
- Le claim disparaît **en tant que donnée** (protection, « rentrer chez soi » via Jo, `CLAIM_TIER_1`
  re-verrouillé). Les **blocs construits restent**. Le joueur peut reposer un claim, y compris au
  même endroit.

**À la reconnexion**
1. Nettoyage d'inventaire différé (si reset fait hors ligne), priorité `LOWEST`.
2. `StarterKitListener` redonne une Rune de rappel de départ (`RUNE_RAPPEL_GRANTED` absent).
3. Les moteurs rechargent un état vide.
4. Le Guide propose de nouveau la quête d'introduction ; Jo proposera l'Acte une fois
   `CLAIM_TIER_1` re-débloqué par la Story.

**Exemple exact — LoDyMcFly**
```
/rpgadmin player resetnew LoDyMcFly            # → avertissement (rien n'est fait)
/rpgadmin player resetnew LoDyMcFly confirm    # → reset + résumé admin
```
Résumé affiché : `Reset « nouveau joueur » effectué pour LoDyMcFly (<uuid>)`, liste des systèmes
réinitialisés, ligne inventaire (immédiat si en ligne / différé sinon), rappel des données
conservées. LoDyMcFly peut ensuite se (re)connecter et recommencer le parcours depuis le début.

**Limitations**
- Répit de premier login vanilla non redéclenché (playerdata vanilla intact) — sans impact sur le
  parcours.
- Économie / backpacks / entitlements / annonces de marché non réinitialisés.
- Reset hors ligne : inventaire nettoyé seulement à la reconnexion (annoncé explicitement).
