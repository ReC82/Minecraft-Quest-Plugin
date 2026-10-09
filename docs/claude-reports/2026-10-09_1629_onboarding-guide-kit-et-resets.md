# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-09
* Heure : 16:29 (heure locale, CEST)
* Sujet : #235 — onboarding du Guide : reset cohérent, démarrage de quête explicite, progression
  des paliers de kit compréhensible
* Statut : DONE (validation manuelle en jeu restante — TC-273)
* Branche Git : `feat/235-onboarding-guide-kit`
* Commit actuel si disponible : `c293faa` (+ le commit de ce rapport)
* Début de la tâche : 2026-10-09 15:01:08
* Fin de la tâche : 2026-10-09 16:29:01
* Durée totale : 01:27:53

## Demande

Traiter #235 en priorité, avant de poursuivre les tests #123/#218. Le ticket vient d'un test
utilisateur réel : après un reset de `LoDyMcFly`, trois défauts sont apparus dans les premières
minutes de jeu.

1. **Reset incohérent avec le kit de départ** : les objets du kit restaient dans l'inventaire alors
   que le droit d'en demander un était rétabli → **second kit** obtenu immédiatement.
2. **« Très bien, j'y vais. » démarrait une quête sans le dire**, et après activation le joueur ne
   savait pas quoi faire.
3. **Aucune explication de la progression du kit** : ni palier actuel, ni palier suivant, ni comment
   le débloquer, ni le fait que la quête de ressources sert à améliorer le kit.

Consignes explicites : **auditer les resets existants avant de coder** ; distinguer au minimum deux
intentions de reset ; **ne pas supprimer arbitrairement des items vanilla** en prétendant reconnaître
le kit ; libellés de reset explicites dans PlugAdmin ; ne plus cacher une mutation de gameplay
derrière une phrase générique ; afficher le palier courant **sans PlugAdmin ni commande** ; **ne pas
casser #26** ; **réutiliser #123 et #218** plutôt que créer une seconde logique ; rester
**exclusivement** sur #235 ; ne rien fusionner ; préserver les fichiers non suivis ; déploiement DEV
autorisé.

## Analyse

### 1. Audit exact des resets existants (avant toute modification)

**Il n'existait qu'un seul reset** : `/rpgadmin player resetnew <joueur> [preview|confirm]`, exposé au
panel par `player.resetnew.preview` / `player.resetnew.confirm`, implémenté par
`player.PlayerResetService`.

| Donnée | Effet avant ce lot |
|---|---|
| Quêtes | `QuestProgressEngine#resetAllQuests` — tout revient à `NOT_STARTED` |
| Stories | `StoryService#reset(uuid, "all")` |
| **Toutes** les variables joueur | `PlayerVariableRepository#deleteAllForPlayer` |
| → droit au kit (`STARTER_TOOL_KIT_AVAILABLE`) | **rétabli** (absence = disponible) |
| → palier (`STARTER_KIT_TIER`) | **remis au palier 1** |
| `CLAIM_TIER_1` | re-verrouillé (`ClaimService#resetTierOneClaimForTesting`), puis effacé par le wipe |
| Claims | supprimés (données de protection ; les blocs restent) |
| Progression RPG | `ProgressionRepository#resetPlayer` (`player_skills`, `xp_grants`) |
| Découvertes de Waystones | `WaystoneService#resetDiscoveries` |
| Cooldowns portails / voyage par objet | supprimés |
| Quête suivie (journal) | `QuestJournalService#clearTrackingFor` |
| Caches mémoire | progression, cooldowns portails, cooldowns de voyage rechargés |
| **Inventaire** | **seuls les objets RPGQuest (PDC)** retirés ; **le vanilla n'est jamais touché**. Hors ligne → marqueur `__pending_new_player_reset__` + nettoyage au login (`NewPlayerResetJoinListener`, priorité `LOWEST`, donc avant la remise de la Rune) |
| Économie, backpacks, annonces de marché | **conservés volontairement** |
| Profil/UUID, mondes, PNJ Citizens, définitions, portails, Waystones globales | **jamais touchés** |

Autres resets existants, hors périmètre : `quest.reset` (une seule quête, panel),
`/rpgadmin story reset[withquests]`, `/rpgadmin claim grant-tier`, `/rpgadmin kit grant-tier`.

### 2. Cause exacte du double kit

`StarterToolKitService` remet le kit avec `new ItemStack(Material, 1)` — **du vanilla pur, sans
`PersistentDataContainer`**. Or `PlayerResetService#removeRpgItems` filtre par
`YamlCustomItemRegistry#isCustomItem`, donc par PDC : **il ignore structurellement ces outils**. Le
reset effaçait le droit (→ redisponible) et laissait les outils en place.

Ce n'est pas un défaut de la logique du kit, c'est l'écart entre « reset des **données** » et « reset
du **monde physique** ».

Et **aucune détection sûre n'est possible** : une pioche en bois du kit est rigoureusement
indiscernable d'une pioche en bois fabriquée. Supprimer la seconde en croyant retirer la première
détruirait les outils légitimes du joueur. Le ticket avait donc raison : la seule option sûre est un
reset complet **explicite**.

### 3. Deux constats supplémentaires, non demandés mais bloquants

* **`rpgquest:kit_tier2` était injouable.** Elle n'était citée que dans `config.yml`
  (`unlock-quest:`) ; **aucun dialogue ne la démarrait**, et `giver:` est purement informatif — il
  n'existe aucune offre automatique de quête par PNJ donneur. La progression de kit livrée par #218
  n'était donc atteignable par aucun joueur.
* **Le démarrage d'une quête n'annonçait aucun objectif** : seulement un `Title` « Quête commencée »
  + le nom, qui disparaît en deux secondes. C'est exactement le reproche du ticket.

### 4. Contraintes techniques qui ont dicté la conception

* Les **conditions** de dialogue sont asynchrones (`CompletableFuture`), mais les **marqueurs de
  texte** sont synchrones et substitués **sur le thread principal**, juste avant le rendu : aucune
  requête SQL n'y est permise. Le palier devait donc être lisible en mémoire.
* Le projet a déjà ce motif : `ProgressionService`, `PortalService` et `ItemTravelService` tiennent
  un cache par joueur, chargé au login et **invalidé par `PlayerResetService`**. Le palier suit la
  même voie — ce n'est pas une invention de ce lot.
* `%delivery_status%` (#123) établit la convention : le marqueur rend **les valeurs**, la phrase qui
  les entoure vit dans le YAML du dialogue, donc éditable depuis le panel.

## Travail effectué

### A. Deux portées de reset explicites

`PlayerResetService.ResetScope` porte l'**intention**, pas un détail d'implémentation :

| | `PROGRESSION` (`resetnew`) | `NEW_PLAYER` (`resetfull`) |
|---|---|---|
| Données RPGQuest, droit au kit, palier | réinitialisés | réinitialisés |
| Inventaire Minecraft | **conservé** (objets RPGQuest retirés) | **vidé** |
| Armure, main secondaire, curseur, Ender | conservés | vidés |
| Permission panel | `ACTION_PLAYER_RESET` | `ACTION_PLAYER_RESET_FULL` |
| Action agent | `player.resetnew.*` | `player.resetfull.*` |

**Deux actions agent distinctes, et non un paramètre booléen** : un clic ne peut pas se tromper
d'intention, et le journal d'audit dit laquelle a eu lieu.

**Permission dédiée** pour le vidage : réinitialiser une progression se refait en rejouant, vider un
inventaire ne se défait pas. Un droit commun aurait fait du second un effet de bord du premier.
Accordée à `OWNER` et `ADMIN` (outil de test), à aucun autre rôle.

**Le coffre de l'Ender est inclus** dans le vidage, volontairement : y laisser du matériel rendrait
« nouveau joueur » faux. La portée est annoncée mot pour mot avant confirmation.

**Marqueur différé** : `__pending_new_player_reset__` porte désormais la **portée** comme valeur. Sa
valeur historique `"1"` et toute valeur illisible sont relues comme `PROGRESSION` — la portée la
**moins destructrice**. Un marqueur douteux ne doit jamais vider un inventaire.

### B. UX du reset dans PlugAdmin

Le bouton unique « Reset « nouveau joueur » » devient **deux blocs** sous un déclencheur nommé
« Resets joueur (2 portées) ». Chaque bloc :

* énumère **ce qu'il conserve** autant que ce qu'il efface — « conserve » est l'information qui
  manquait ;
* nomme explicitement le **droit au kit de départ** et le **palier de kit** parmi ce qui est
  réinitialisé ;
* a son **propre aperçu** et sa **propre confirmation** (un aperçu « conserve l'inventaire » ne peut
  pas servir de caution à un bouton qui le vide) ;
* vérifie **sa** permission : un rôle qui n'a qu'un des deux droits ne voit pas l'autre bouton.

Le bloc « progression » porte l'avertissement que le ticket exige : le droit au kit est rétabli, un
kit déjà reçu **restera** en place, un **second kit** sera donc possible, et **pourquoi** (outils
vanilla indiscernables). Le bloc complet porte un bandeau d'erreur : vider un inventaire ne se défait
pas.

L'aperçu gagne deux catégories dédiées, **Droit au kit de départ** et **Palier de kit**, lues dans la
carte de variables déjà chargée — aucune requête supplémentaire.

### C. Dialogue du Guide

* L'accueil explique son rôle, **y compris** qu'il remet l'équipement de secours.
* « Très bien, j'y vais. » est **supprimé**. À la place, deux gestes : « Que dois-je faire pour
  commencer ? » → nœud `intro_quest` qui **explique et nomme** la quête → « **Commencer la quête :
  Premiers pas** ».
* Nouveau nœud `kit_progress` (« Comment améliorer mon kit ? », proposé **sans condition**) :
  palier actuel, palier suivant, matériaux attendus avec **ce qui est déjà remis**, et l'explication
  de la remise progressive (ce qui est remis est sécurisé, survit à la mort, le kit s'améliore
  définitivement).
* Depuis ce nœud : « **Commencer la quête : Premiers pas dans le Wild** » (si `NOT_STARTED`), « J'ai
  des matériaux à te remettre » (si remise en attente, **branche #123 existante**), retour à
  l'accueil.

### D. Progression du kit — tout dérivé, rien recopié

| Marqueur | Source |
|---|---|
| `%kit_tier_current%` | palier du joueur (`STARTER_KIT_TIER`, source de vérité de #218) + noms des paliers de `config.yml` |
| `%kit_tier_next%` | palier contigu suivant de `config.yml` ; **phrase complète** s'il n'y en a pas (« aucune — tu as déjà le meilleur… »), pour qu'un seul nœud suffise à tous les paliers |
| `%kit_upgrade_requirements%` | objectifs `DELIVER_ITEM_TO_NPC` de la quête `unlock-quest:`, avec la progression réelle (#123), rendus par `DeliveryStatusText` — **même présentation** que la branche de remise, et noms d'objets traduits par le client via `<lang:…>` |

Modifier le palier 2 ou sa quête change donc le discours du Guide **sans toucher au code ni au
dialogue**. Les libellés qui entourent ces valeurs sont dans `dialogues/guide.yml`, donc éditables
depuis le panel.

`KitProgressText` est **pure** ; `KitProgressService` reçoit ses dépendances en fonctions (le moteur
de quêtes n'est pas instanciable hors d'un serveur), ce qui rend chaque cas réellement exécutable.

### E. Guidance après démarrage

`QuestProgressEngine#showQuestStarted` envoie désormais, après le `Title`, les objectifs de la
**première étape** dans le chat : un en-tête nommant la quête et renvoyant vers `/quests`, puis une
ligne par objectif. Aucune règle dupliquée — le libellé vient du même helper que l'ActionBar de
progression et le journal (`QuestObjective.describe`), la quantité de `QuestObjective.requiredAmount`.

Textes dans `messages.yml`. `QuestMessagesService#reload` **fusionne déjà les clés manquantes** au
démarrage : un serveur déjà déployé reçoit les deux nouvelles clés sans aucune édition.

### F. Ce qui n'a PAS été fait, et pourquoi

* **`guard.yml` n'a pas été modifié.** Mon test de règle, écrit d'abord pour tous les dialogues
  livrés, a immédiatement révélé **trois** choix du Garde qui démarrent une quête sans le dire
  (« J'ai entendu dire que tu avais besoin d'aide… » → `crystal_hunt`, « Je veux prouver ma valeur… »
  → `guard_tier1`, « Je veux agrandir mon terrain… » → `guard_tier2`). C'est le **même défaut**, mais
  dans du contenu appartenant à d'autres tickets, et le propriétaire édite `crystal_hunt.yml` en ce
  moment même (fichier non suivi, modifié). J'avais corrigé un libellé, puis je suis revenu en
  arrière : le ticket dit « travaille exclusivement sur #235 ». Le test est **recentré sur le Guide**
  et documente comment l'élargir le jour où ces libellés seront revus.
* **Les paliers de kit ne sont pas éditables depuis le panel** (`config.yml` n'est pas administrable
  côté panel). Le lien palier ↔ quête, le contenu des kits et les ressources de la quête restent donc
  des fichiers serveur. En revanche le **dialogue du Guide**, les **textes** et la **quête
  `kit_tier2`** sont éditables depuis le panel, et le palier d'un joueur est lisible par
  `player.variable.get` (`STARTER_KIT_TIER` est dans la liste blanche des variables). Constat de
  vérification demandé par le §12, pas un travail ajouté.

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/player/KitProgressText.java`
* `src/main/java/com/lodygames/rpgquest/player/KitProgressService.java`
* `src/test/java/com/lodygames/rpgquest/player/KitProgressTextTest.java`
* `src/test/java/com/lodygames/rpgquest/player/KitProgressServiceTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/PlayerResetScopesTest.java`
* `docs/claude-reports/2026-10-09_1629_onboarding-guide-kit-et-resets.md` (ce rapport)

## Fichiers modifiés

**Moteur** : `player/PlayerResetService.java` (portées, vidage complet, aperçu par portée,
catégories kit), `player/NewPlayerResetJoinListener.java` (portée relue dans le marqueur),
`player/StarterToolKitService.java` (palier lisible en mémoire),
`quest/progress/QuestProgressEngine.java` (objectifs au démarrage), `admin/RpgAdminCommand.java`
(`resetfull`, messages par portée), `bootstrap/RPGQuestBootstrap.java` (câblage),
`web/agent/{AgentActionType,AgentActions,BukkitAgentActions,AgentActionExecutor}.java`.

**Contenu** : `src/main/resources/dialogues/guide.yml`, `src/main/resources/messages.yml`.

**Panel** : `panel/authz/Permission.java`, `panel/authz/Role.java`,
`panel/agent/AgentActionCatalog.java`, `panel/web/AgentPages.java`.

**Tests** : `player/PlayerResetServiceTest.java`, `player/StarterToolKitServiceTest.java`,
`quest/progress/QuestProgressEngineTest.java`, `dialogue/BundledDialoguesValidityTest.java`,
`web/agent/{AgentActionExecutorTest,StubAgentActions}.java`, `panel/agent/AgentActionCatalogTest.java`.

**Documentation** : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/ADMIN_PLAYER_RESET.md`, `docs/MANUAL_TEST_PLAN.md` (TC-273),
`control-panel/src/main/resources/docs/joueurs-reset.md`.

## Base de données / migrations

**Aucune.** `user_version` reste **29**. Aucune table, colonne ni index ajoutés : le palier et le
droit au kit étaient déjà des variables joueur, et la portée du reset voyage dans la valeur d'un
marqueur existant.

## Configuration / données

Aucune nouvelle clé de `config.yml`. **Deux nouvelles clés de `messages.yml`**
(`quest.started-objectives-header`, `quest.started-objective`), ajoutées automatiquement au
démarrage par la fusion des clés manquantes — aucune édition manuelle sur le serveur.

`dialogues/guide.yml` est modifié : c'est un fichier **livré avec le JAR**. ⚠️ Un redéploiement du
JAR **ne met jamais à jour un fichier de dialogue existant** sur le serveur (il ne sème que les
manquants). Voir « Déploiement » ci-dessous.

## Tests automatiques

**Tests ciblés pendant le développement**, puis **une seule** build complète finale, depuis un
worktree propre, après `./gradlew --stop` :

```
./gradlew clean build   (worktree propre sur c293faa)
BUILD SUCCESSFUL in 39m 30s
3389 tests, 0 échec, 0 erreur, 38 ignorés
  plugin         2167  (37 ignorés — limitations MockBukkit héritées)
  control-panel  1192  (1 ignoré)
  web-api          30
```

**+55 tests** par rapport au lot précédent (3334). Deux tests ajoutés après le gel du worktree ont
été exécutés séparément : `PlayerResetServiceTest` passe à **20 tests, 0 échec**.

**Tests ajoutés / remaniés pour ce lot :**

| Classe | Objet |
|---|---|
| `KitProgressTextTest` (9) | palier actuel, palier maximum, palier absent de la configuration, exigences rendues |
| `KitProgressServiceTest` (10) | exigences **dérivées** de la quête, progression superposée, étape étrangère ignorée, compteur aberrant borné, quête inconnue |
| `PlayerResetServiceTest` (+13) | inventaire conservé vs vidé (Ender compris), palier et droit au kit remis pour **les deux** portées, invalidation du cache d'affichage, marqueur portant la portée, marqueur illisible → portée la moins destructrice, ce que l'aperçu **annonce**, et « reset complet → un seul kit, seconde demande refusée » |
| `StarterToolKitServiceTest` (+6) | palier lisible immédiatement, mis à jour au déblocage, inchangé sur refus, oublié à la déconnexion, et **un cache périmé ne change jamais le kit remis** |
| `BundledDialoguesValidityTest` (+6) | aucun choix du Guide ne démarre une quête sans la nommer, l'ancien libellé a disparu, l'introduction explique avant de démarrer, `kit_tier2` est **atteignable**, le nœud de progression n'a **aucun** matériau en dur |
| `QuestProgressEngineTest` (remanié) | les objectifs de la première étape sont annoncés, et tous le sont |
| `AgentActionExecutorTest` (+3) | chaque type d'action porte **sa** portée, le reset complet exige aussi `confirm`, un aperçu n'écrit jamais |
| `PlayerResetScopesTest` (9) | frontière de permission, et ce que la **page réellement rendue** dit des deux portées |

**Une attente de test corrigée, et c'est le test qui avait tort** : `acceptingAQuestNeverSendsAChatMessage`
affirmait qu'accepter une quête n'envoie aucun message. Le contrat a changé (c'est l'objet du
ticket), mais surtout **ce test ne prouvait rien** : la notification est planifiée sur le thread
principal et il ne tickait pas le scheduler — il aurait passé quel que soit le comportement. Il est
remplacé par deux tests qui tickent explicitement.

**Deux faux positifs de mon propre harnais, identifiés puis corrigés dans les tests** : un total
d'inventaire absolu était instable (la Rune de rappel de départ est remise par une tâche planifiée,
donc le total dépend du nombre de ticks), et une file de messages contenait déjà celui d'un autre
écouteur. Dans les deux cas le produit avait raison.

## Tests manuels à effectuer

* **TC-273** (nouveau, ~10 min) — rejoue **exactement** le parcours qui a révélé les trois défauts :
  deux resets distincts et ce qu'ils annoncent, aperçu sans écriture, reset complet + reconnexion,
  un seul kit puis refus, quête nommée avant démarrage, objectif lisible immédiatement, progression
  du kit avec les vraies quantités, démarrage de la quête de palier 2, puis enchaînement direct sur
  **TC-257/#123**. `PENDING MANUAL VALIDATION`.
* Restent par ailleurs : TC-272, TC-271, TC-270, TC-269, TC-268, TC-267, TC-265, TC-266, TC-264,
  TC-257, TC-258..TC-263.

Ce qui reste à constater est **ce qu'un humain comprend** : aucun test automatisé ne peut l'établir.

## Résultat attendu

Un nouveau joueur parle au Guide, comprend son rôle, demande son kit, lit « Que dois-je faire pour
commencer ? », voit le nom de la quête **avant** de l'accepter, et sait immédiatement quoi faire
grâce aux objectifs envoyés dans le chat. En revenant, il choisit « Comment améliorer mon kit ? » et
lit son palier, le suivant, et les quatre matériaux à rapporter — avec ce qu'il a déjà remis. Il peut
démarrer la quête de palier 2 depuis le dialogue, ce qui était **impossible** avant ce lot.

Côté administration, deux resets clairement nommés, chacun annonçant ce qu'il conserve et ce qu'il
efface, et le reset complet remettant le joueur dans un état réellement neuf.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée persistée par ce lot, aucune migration. Le comportement
historique du reset reste disponible à l'identique sous `resetnew` / `player.resetnew.*`.

## Déploiement VeryGames

### À transférer

1. **JAR du plugin** — contient les deux nouvelles actions agent, le palier lisible, les objectifs au
   démarrage et les textes.
2. **Panel** (`scripts/plugadmin/deploy.sh`) — les deux nouvelles actions au catalogue, la permission
   dédiée et les deux blocs de reset.
3. ⚠️ **`dialogues/guide.yml` doit être transféré séparément**, via
   `--also src/main/resources/dialogues/guide.yml:RPGQuest/dialogues/guide.yml`.
   Vérifié dans le code : `YamlDialogueEngine.BUNDLED_EXAMPLES` ne contient que **`guard.yml`** —
   `guide.yml` n'est donc **même pas** semé par le plugin (il a été déposé à la main par un
   administrateur, comme l'indique l'en-tête du fichier), et un redéploiement du JAR ne le remplace
   jamais. Sans ce transfert, le moteur gagnerait les marqueurs mais le Guide garderait son ancien
   dialogue : **aucun** changement d'onboarding ne serait visible en jeu, et le joueur verrait
   l'ancien libellé « Très bien, j'y vais. ».

### Ne PAS transférer/altérer

`data.db` (aucune migration), les mondes, `plugins/Citizens/`, les autres fichiers de dialogue, les
quêtes et stories du serveur (dont celles que le propriétaire édite actuellement), et
`plugins/RPGQuest/content-backups/`.

### Redémarrage requis

**Oui, un seul** : les deux nouvelles actions agent et les nouveaux messages sont dans le JAR. Le
rechargement de contenu suffit pour `guide.yml` seul, mais le JAR impose de toute façon un
redémarrage.

### Migration automatique

Aucune. `user_version` reste 29.

## Rollback

* **Plugin** : redéployer le JAR sauvegardé avant ce lot, puis redémarrer. Les actions
  `player.resetfull.*` redeviennent inconnues de l'agent (le panel affichera leur échec, sans
  conséquence), les objectifs ne sont plus annoncés au démarrage, et les trois marqueurs de
  progression de kit ne sont plus substitués — le Guide afficherait alors `%kit_tier_current%`
  littéralement si `guide.yml` du nouveau lot est resté en place. **Rollback du JAR ⇒ remettre aussi
  l'ancien `guide.yml`** (sauvegardé par le script de déploiement avant remplacement).
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Aucun reset déjà exécuté ne se défait** : c'est vrai avant comme après ce lot, et c'est pourquoi
  l'écran annonce la portée avant de confirmer.

## Logs / diagnostic

* `[player reset:<PORTÉE>] Inventaire de <joueur> nettoyé à la reconnexion (<n> objet(s) retiré(s),
  …)` — le nettoyage différé dit **quelle portée** il applique.
* Journal d'actions de l'agent : l'aperçu porte `scope`, `scope_label` et `wipes_inventory` ; la
  confirmation porte le libellé de la portée et le nombre d'objets retirés.
* `/rpgadmin kit status <joueur>` reste la lecture de référence du palier ; le panel peut lire
  `STARTER_KIT_TIER` via `player.variable.get`.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — deux portées de reset, règles d'onboarding du Guide, tableau des
  marqueurs dérivés, palier lisible en mémoire.
* `docs/ADMIN_PLAYER_RESET.md` — cause du double kit, pourquoi « reconnaître les outils du kit » n'est
  pas une option, tableau des deux portées, nouvelle syntaxe.
* `control-panel/src/main/resources/docs/joueurs-reset.md` — fiche d'aide consultable **depuis le
  panel**, réécrite pour les deux portées, avec l'avertissement du second kit.
* `docs/current_state.md` — état courant.
* `docs/MANUAL_TEST_PLAN.md` — **TC-273** + ligne d'index.

## Limitations / travail restant

* **TC-273 n'est pas exécuté** : ce qui reste à établir est qu'un humain comprend le parcours sans
  aide. #235 **ne doit donc pas être fermée** maintenant.
* **Trois libellés du Garde** présentent le même défaut que celui corrigé chez le Guide
  (`crystal_hunt`, `guard_tier1`, `guard_tier2` démarrent sans le dire). Volontairement **non
  corrigés** : contenu d'autres tickets, et `crystal_hunt.yml` est en cours d'édition par le
  propriétaire. Correction d'une ligne chacun, à décider.
* **Les paliers de kit ne sont pas éditables depuis le panel** (`config.yml` n'est pas administrable).
  Candidat à un sous-ticket si l'édition des paliers doit devenir self-service.
* **Paliers 3 à 5** : l'architecture les accepte, leur contenu n'est pas décidé — rien n'a été
  inventé. Le nœud du Guide s'y adapte déjà sans modification.
* **Le cache de palier peut afficher « Palier 1 » pendant la fraction de seconde** qui suit la
  connexion, avant la fin de la lecture asynchrone. Jamais une valeur plus flatteuse que la réalité,
  et sans effet sur le kit réellement remis (`requestKit` relit la base).

## Prochaine étape suggérée

**TC-273** (~10 min, un client Minecraft), qui s'enchaîne directement sur **TC-257/#123** — c'était
l'objectif du ticket. Ensuite, décider du sort des trois libellés du Garde.
