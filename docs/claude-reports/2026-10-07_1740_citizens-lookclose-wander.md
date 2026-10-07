# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-07
* Heure : 17:40
* Sujet : Citizens en `compileOnly` complet — « regarder les joueurs » (Look Close) et promenade
  (Wander) pilotables depuis le panel ; achèvement du déploiement du lot 4 ; vérification de
  l'instabilité de `RestartServiceTest` (#165)
* Statut : DONE (fonctionnalité livrée ; validation en jeu PENDING MANUAL VALIDATION — TC-255)
* Branche Git : `feature/169-special-mobs-boss`
* Commit actuel si disponible : 993c615
* Début de la tâche : 2026-10-07 16:27:09
* Fin de la tâche : 2026-10-07 17:40:16
* Durée totale : 01:13:07

## Demande

Reprise après un point d'étape où j'avais conclu, à tort, que Look Close et Wander n'étaient pas
implémentables. Le propriétaire a donné son **feu vert pour utiliser le JAR complet de Citizens en
`compileOnly`**, à la version installée, et a fixé le cadre :

- vérifier d'abord les **classes, signatures et sources réelles** ;
- privilégier les **appels typés** — ni réflexion ni clés de persistance internes si les méthodes
  sont directement accessibles ;
- **ne pas embarquer** Citizens dans notre JAR ; **ne pas ajouter** WorldGuard ni Denizen ;
- **isoler** l'intégration dans un adaptateur, **documenter la compatibilité**, et **renvoyer une
  erreur claire** si la version ne convient pas ;
- livrer **Look Close** (état explicite activé/désactivé, portée si supportée, relecture fidèle) et
  **Wander** (activation/désactivation, ancrage et zone bornée, conservation des comportements
  existants ou confirmation avant remplacement) ; **aucun toggle aveugle au retry** ;
- tester la logique et les contrats disponibles ; prévoir une vérification serveur avec un PNJ
  temporaire si possible, sinon **lister précisément les étapes utilisateur restantes**, sans
  déclarer ces validations acquises ;
- **terminer d'abord le déploiement déjà lancé** et relever sa version, sans déploiement concurrent ;
  regrouper ensuite les changements restants en **une seule livraison supplémentaire** ;
- concernant `RestartServiceTest` : **ne pas considérer la suite verte avec un échec**, vérifier le
  diagnostic d'instabilité, relancer le périmètre concerné, et **rapporter distinctement l'échec
  initial et le résultat final** ;
- poursuivre commit, push et déploiement autorisés, sans sous-agents, monitors ni Gradle parallèle ;
- rapporter les **versions réellement installées** et les limites.

## Analyse

### 1. Le déploiement « déjà lancé » n'avait rien transféré

Le déploiement du lot 4 (commit `2f181fd`) lancé à la fin de la session précédente était **en
échec silencieux** : son fichier de sortie contenait 49 octets et s'arrêtait à `==> 3/8 —
./gradlew test`, et surtout **aucun backup n'avait été créé** — or le script sauvegarde la version
déployée *avant* de transférer. Donc rien n'était parti sur le serveur.

J'ai relancé, et j'ai **provoqué moi-même un second échec** : la tâche de fond précédente tournait
encore quand j'ai lancé la nouvelle, et les deux exécutions Gradle se sont chevauchées sur le même
répertoire de build —
`java.nio.file.NoSuchFileException: control-panel/build/test-results/test/binary/in-progress-results-generic.bin`.
C'est exactement la règle que je devais respecter (jamais deux Gradle simultanés). J'ai arrêté les
démons, nettoyé le répertoire de résultats, et relancé **seul**. Le troisième essai est passé.

### 2. `RestartServiceTest` — échec initial et résultat final, distinctement

- **Échec initial** : lors de la suite du lot 4, le module `control-panel` rapportait
  `708 tests, 1 échec` sur `RestartServiceTest`. Ce run avait lieu alors qu'une autre exécution
  Gradle était active sur la machine.
- **Diagnostic vérifié dans le code du test**, et non supposé : `RestartServiceTest` construit son
  service avec `FAST_TIMEOUT = Duration.ofSeconds(3)` et attend les transitions par un
  `await(...)` qui scrute pendant au plus 6 s réelles (`600 × Thread.sleep(10)`). Ce sont des
  budgets de temps **réels**. Sur une machine de 3,8 Go qui pagine, avec deux démons Gradle en
  concurrence, 3 s de mur peuvent s'écouler avant que le travail attendu n'aboutisse : le service
  bascule en `FAILED`/timeout et l'assertion tombe. Le test est donc **sensible à la charge par
  construction**.
- **Résultat final** : la suite complète du lot 4, relancée en exécution unique, est passée
  `BUILD SUCCESSFUL`, avec `RestartServiceTest` à **24 tests, 0 échec, 0 erreur** (XML JUnit), et
  `control-panel` à **708 tests, 0 échec**.
- **Ce que je ne prétends pas** : je n'ai pas conservé le message d'assertion du run en échec, donc
  je n'affirme pas avoir prouvé *quelle* assertion précise a cédé. Ce qui est établi : le test
  dépend de budgets temporels réels, l'échec a eu lieu sous contention, et il ne s'est pas
  reproduit en exécution isolée. Aucune suite n'a été déclarée verte en présence d'un échec.

### 3. Ce que les sources Citizens réelles disent — et ce que ma documentation précédente disait à tort

J'ai récupéré `citizens-main` **et son JAR de sources** depuis le dépôt Maven officiel de Citizens,
puis lu le code plutôt que la documentation en ligne. Trois affirmations que j'avais écrites dans
`docs/RPGQUEST_BIBLE.md` sont fausses et ont été corrigées :

| Ce que j'avais écrit | Ce que les sources montrent |
|---|---|
| « Look Close n'est pilotable que par un **toggle** » | Le toggle n'est une limite que de la *commande* `/npc lookclose`. Le trait expose `lookClose(boolean)` — un setter d'état explicite — à côté de `toggle()`. |
| « Wander **ne persiste pas**, ce serait un moteur parallèle » | Faux : la promenade n'est pas un `WanderGoal` posé à la main, c'est le fournisseur `wander` du trait `waypoints`, que Citizens persiste comme les autres. |
| « Impossible de **détecter** une patrouille existante » | `Waypoints#getCurrentProviderName()` et `WaypointProvider.EnumerableWaypointProvider#waypoints()` le permettent. |

Deux particularités du code de Citizens 2.0.43 conditionnent l'implémentation, et je les ai vues
dans les sources plutôt que découvertes en production :

1. **`WanderWaypointProvider#setXYRange` ne recalcule pas l'arbre de régions.** Seuls
   `addRegionCentre`/`removeRegionCentres` appellent `recalculateTree()`. Il faut donc régler la
   zone **avant** de poser l'ancre, sinon la zone effective reste l'ancienne alors que la fiche
   afficherait la nouvelle.
2. **`Waypoints#setWaypointProvider` détruit le fournisseur précédent** (`onRemove()` puis
   remplacement). Activer la promenade sur un PNJ qui patrouille **perd** sa patrouille, sans
   avertissement. C'est ce qui justifie la confirmation obligatoire.

Et une vérification qui soutient une affirmation que je fais au propriétaire : dans `WanderGoal`,
les destinations sont tirées autour de `npc.getStoredLocation()` **et filtrées** par la boîte
`ancre ± (xrange, yrange)`, dans le monde du PNJ. Le PNJ ne peut donc ni sortir de sa zone, ni
changer de monde — ce n'est pas une promesse, c'est le filtre du code.

### 4. Aucune dépendance implicite ajoutée

Le POM de `citizens-main` déclare WorldGuard, Denizen, PlaceholderAPI, Vault, Spigot, packetevents,
phtree et mocha — **tous en scope `provided`**, que Gradle ne résout pas transitivement. Vérifié
empiriquement par `./gradlew dependencies --configuration compileClasspath` : seuls `citizensapi`
(déjà déclaré) et `libby-bukkit` remontaient. `libby-bukkit` (le chargeur de bibliothèques
d'exécution de Citizens) **n'est pas résolvable** depuis nos dépôts et ne sert pas à la
compilation : la déclaration porte donc `isTransitive = false`.

## Travail effectué

### Déploiement du lot 4 (terminé, version relevée)

| Élément | Valeur |
|---|---|
| Branche / commit | `feature/169-special-mobs-boss` @ `2f181fd` |
| JAR transféré | `1 899 262 o`, SHA-256 `9b27134e684173bea4d77b3c64c73404b9f8000f304872c793c543c71c3d0a09` |
| Backup créé | `rpgquest-20261007T145132Z-predeploy.jar`, `1 895 945 o`, SHA-256 `14703d4c912df3a15781a7faea0de422c2bda9b53bc4b732c1ebb89b12aae86a` |
| Cible | `ftp://si-16041.dg.vg:21/rpgquest-0.1.0-SNAPSHOT.jar` |
| Tests du déploiement | plugin 1747 / 0 échec / 37 ignorés ; panel 708 / 0 échec / 1 ignoré |
| Redémarrage | **non effectué** — groupé avec le lot 5 |

### Lot 5 — l'adaptateur Citizens

`CitizensBehaviourBridge` (nouveau, paquet `npc`) est **le seul** fichier du projet qui référence
des types de `citizens-main`. Il est distinct de `CitizensNpcBridge`, qui ne touche que
`citizensapi` : ainsi une build Citizens inadaptée ne fait échouer **que** ces deux options.

- L'instanciation est protégée : un `LinkageError` est journalisé en `WARN` et le pont reste `null`.
- Chaque appel est en plus enveloppé dans une garde qui intercepte `LinkageError`. Les écritures
  renvoient alors `CITIZENS_INCOMPATIBLE` avec un message explicite ; les lectures renvoient
  « inconnu ».
- Même discipline que `ops.ConsoleTap` pour Log4j, qui sert de précédent documenté dans le projet.

**Look Close** — appels typés `isEnabled()`, `lookClose(boolean)`, `getRange()`, `setRange(double)`,
`useRealisticLooking()`, `disableWhileNavigating()`, `targetNPCs()`. La lecture **n'ajoute pas** le
trait pour le lire ; quand il est absent, les **défauts effectifs du serveur** sont lus sur
`Settings.Setting` (`DEFAULT_LOOK_CLOSE` = faux, `DEFAULT_LOOK_CLOSE_RANGE` = 10 blocs) plutôt que
recopiés en constantes. Après écriture, l'état est **relu sur le trait** : s'il ne correspond pas,
l'action échoue (`LOOKCLOSE_NOT_APPLIED`) au lieu de rendre un succès trompeur.

**Wander** — `Waypoints#getCurrentProviderName/getCurrentProvider/setWaypointProvider` et
`WanderWaypointProvider#setXYRange/addRegionCentre/removeRegionCentres/getXRange/getYRange`.
L'ordre d'application est volontaire (zone puis ancre, cf. analyse). La désactivation revient au
fournisseur neutre `linear`, et annule explicitement la navigation en cours si le PNJ naviguait
encore, puis **rapporte la position finale**.

`WanderChangePlanner` (nouveau, **pur**, sans aucune dépendance Citizens) porte la règle de
non-écrasement, pour qu'elle soit testable sans serveur : l'état **neutre** — aucun fournisseur, ou
`linear` sans aucun point, ce dans quoi Citizens laisse tout PNJ jamais configuré — autorise la pose
directe ; une patrouille `linear` garnie, un parcours `guided` ou un fournisseur tiers exigent une
confirmation explicite, refusée sinon (`WANDER_CONFLICT`) **en nommant ce qui serait perdu**.
Réactiver sur un PNJ déjà en promenade donne `RECONFIGURE` — donc idempotent. Désactiver ne retire
**que** la promenade : sur un autre fournisseur, l'action répond `WANDER_NOT_ACTIVE` et ne touche à
rien, pour que « désactiver » ne devienne pas une façon détournée d'effacer la patrouille d'autrui.

### Lot 5 — la chaîne agent

Deux nouvelles actions whitelistées, `npc.citizens.lookclose` et `npc.citizens.wander`.

**Aucune bascule, à aucune couche.** Le paramètre `enabled` ne connaît que `true` et `false` : pas
de valeur « inverser », pas de défaut implicite, et un paramètre vide ou illisible est **rejeté**.
C'est ce qui garantit qu'un double clic, un retry réseau ou un rejeu par `ProcessedActionCache`
aboutit au **même** état. Les formulaires suivent : deux boutons d'état, jamais un interrupteur.

**Permissions inchangées, aucun droit implicite.** Le regard est cosmétique et relève de
`NPC_BIND_WRITE` (comme renommer ou habiller) ; la promenade fait bouger le PNJ et relève de
`NPC_SPAWN_WRITE` (comme le déplacer). Aucune permission nouvelle n'est créée.

**Ancre jamais partielle.** Monde + X/Y/Z vont ensemble ; une saisie partielle est refusée **des
deux côtés** (panel et plugin), jamais complétée au jugé. Tout laisser vide reprend la position
réellement connue du PNJ — et si elle est inconnue, l'action échoue (`NO_ANCHOR`) plutôt que
d'inventer un point de départ, car sans ancre Citizens ne bornerait pas la zone.

Le relevé `npc.citizens.list` transporte onze clés nouvelles. Elles sont recopiées **explicitement**
comme le reste de la ligne, et la garde structurelle par réflexion de `NpcCitizensPayloadTest` —
ajoutée après le défaut de localisation du lot 3 — vérifie que chaque composant du record apparaît
dans la ligne sérialisée.

### Lot 5 — le panel

Fiche PNJ : une ligne **« Comportement »** et deux formulaires repliés, « Promenade » et
« Regarder les joueurs ».

**Trois états, pas deux.** Une valeur absente du relevé est affichée « **inconnu** », jamais
« désactivé » : confondre les deux ferait croire à un réglage qui n'a jamais été lu.

**L'ancre est distinguée de la position**, explicitement, dans la fiche comme dans l'aide : déplacer
le PNJ ne déplace pas son ancre, il faut réappliquer la promenade pour la réancrer. Les champs
d'ancre sont préremplis depuis l'ancre enregistrée quand la promenade tourne, sinon depuis la
position du PNJ — et le formulaire **dit laquelle**.

Chaque champ porte une aide visible sous lui (unités, exemple, défaut réel, comportement si vide),
selon le même gabarit `fieldHelp` que les formulaires de mobs.

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/npc/CitizensBehaviourBridge.java`
* `src/main/java/com/lodygames/rpgquest/npc/WanderChangePlanner.java`
* `src/main/java/com/lodygames/rpgquest/npc/LookCloseState.java`
* `src/main/java/com/lodygames/rpgquest/npc/WanderState.java`
* `src/main/java/com/lodygames/rpgquest/npc/NpcBehaviourOutcome.java`
* `src/test/java/com/lodygames/rpgquest/npc/WanderChangePlannerTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/NpcBehaviourActionTest.java`
* `docs/deployment/CITIZENS.md`

## Fichiers modifiés

* `build.gradle.kts` — `net.citizensnpcs:citizens-main` en `compileOnly`, `isTransitive = false`
* `src/main/java/com/lodygames/rpgquest/npc/NpcIdentityService.java` — pont des comportements,
  enrichissement du roster
* `src/main/java/com/lodygames/rpgquest/npc/CitizensNpc.java` — composants `lookClose` / `wander`
* `src/main/java/com/lodygames/rpgquest/npc/CitizensNpcBridge.java` — adaptation du constructeur
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
* `control-panel/.../panel/agent/AgentActionCatalog.java`
* `control-panel/.../panel/web/AgentPages.java`
* `control-panel/.../panel/web/Icons.java`
* `src/test/.../StubAgentActions.java`, `AgentActionExecutorTest.java`, `NpcCitizensPayloadTest.java`
* `control-panel/src/test/.../NpcsCatalogTest.java`
* `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`

## Base de données / migrations

**Aucune.** `SchemaMigrator.CURRENT_VERSION` reste à **27**. Les deux comportements sont stockés par
**Citizens**, dans sa propre persistance — RPGQuest n'en garde aucune copie, ce qui évite d'avoir
deux sources de vérité qui divergeraient.

## Configuration / données

Aucun changement de `config.yml`. Aucune donnée de jeu touchée : ni `data.db`, ni les PNJ existants,
ni leurs skins, ni leurs liaisons. **Aucun comportement Citizens n'est modifié** tant qu'un
administrateur ne le demande pas explicitement depuis le panel.

## Tests automatiques

`./gradlew test` + `./gradlew build` sur un worktree propre, **exécution unique**, aucun Gradle
parallèle :

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| plugin | **1772** | 0 | 0 | 37 |
| `control-panel` | **722** | 0 | 0 | 1 |

`BUILD SUCCESSFUL in 27m 32s`. Comptes relevés sur les XML JUnit, pas sur la sortie console.

**Vérification que Citizens n'est pas empaqueté** : le JAR produit (1 917 683 o, SHA-256
`71020c82514de40d513a4cae7755c49421a493368c7906d002ebabf031dc2572`) ne contient **aucune** classe
`net.citizensnpcs`, `worldguard`, `denizen` ou `sk89q` — la seule occurrence de ces motifs est
`com/lodygames/rpgquest/web/agent/AgentActions$CitizensNpcSummary.class`, une de nos classes.

Nouveaux tests :

* `WanderChangePlannerTest` (10) — état neutre vs comportement réel, confirmation, idempotence de la
  réactivation et de la désactivation, fournisseur tiers nommé et non masqué, comparaison
  insensible à la casse.
* `NpcBehaviourActionTest` (15) — refus de tout état ambigu (`""`, `toggle`, `inverser`), **trois
  rejeux de la même requête donnent trois fois le même état**, bornes de portée et de zone, ancre
  partielle refusée, `y_range = 0` accepté comme légitime, confirmation jamais supposée.
* `NpcsCatalogTest` (+14, soit 53 au total) — rendu de la ligne « Comportement », « inconnu » jamais affiché comme
  « désactivé », deux boutons d'état sans bascule, avertissement chiffré avant remplacement d'une
  patrouille, absence de confirmation quand il n'y a rien à perdre, préremplissage depuis l'ancre
  **et non** depuis la position, transmission exacte des paramètres, rejeu sans inversion.
* `NpcCitizensPayloadTest` — fixtures étendues ; la garde structurelle par réflexion couvre
  automatiquement les onze composants nouveaux.

## Tests manuels à effectuer

**`PENDING MANUAL VALIDATION` — TC-255** (nouveau, dans `docs/MANUAL_TEST_PLAN.md`).

**Pourquoi ces validations ne peuvent pas être acquises ici, et ne le sont pas :** `MockBukkit`
n'embarque pas Citizens. **`CitizensBehaviourBridge` n'est couvert par aucun test automatisé.** Rien
de ce qui touche aux traits réels n'a été exécuté — je n'ai pas de serveur Minecraft sur cette
machine, et je ne crée pas de PNJ temporaire sur le serveur de production sans instruction. Les
étapes restantes sont donc **entièrement à votre charge**, et détaillées en 20 points dans TC-255.
Les points décisifs :

1. **Créer un PNJ de test** depuis « Créer un PNJ » — ne pas utiliser Andy, Tania, Tan, Help ni le
   Guide. Le supprimer à la fin.
2. Activer « Regarder les joueurs » à portée 6, vérifier en jeu que le PNJ suit la tête, puis
   **recliquer « Activer » deux fois** : il doit **rester** activé. C'est le test de la non-bascule.
3. Activer la promenade, observer quelques minutes : le PNJ ne doit pas sortir de sa zone ni changer
   de monde ; lui parler pendant qu'il se promène doit fonctionner.
4. **Le déplacer de 30 blocs**, puis vérifier que l'**ancre affichée n'a pas bougé** et qu'il revient
   se promener autour de son ancre d'origine.
5. **Arrêter la promenade** : il doit s'immobiliser immédiatement et le message doit donner sa
   position finale. Recliquer : « la promenade n'est pas active », rien ne change.
6. **Le test le plus important** : donner une patrouille au PNJ de test (`/npc path`, 2-3 points),
   rafraîchir, puis tenter d'activer la promenade **sans** cocher la confirmation. L'action doit
   **échouer** et la patrouille doit être **intacte**.
7. Redémarrer le serveur : les deux réglages doivent être conservés.

## Résultat attendu

Depuis la fiche d'un PNJ Citizens lié, un administrateur peut activer ou désactiver « regarder les
joueurs » avec une portée, et activer ou arrêter une promenade bornée autour d'une ancre, sans
jamais écraser silencieusement un parcours existant ni risquer d'inverser un état en recliquant.

## Reset / retour à l'état initial

Chaque option est réversible par son bouton opposé depuis le panel. Un PNJ dont on n'a jamais touché
les comportements reste exactement dans l'état où Citizens l'a laissé : le code ne modifie rien
sans demande explicite.

## Déploiement VeryGames

### À transférer

* `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (lot 5) — remplace le JAR déployé.
* Control Panel : `scripts/plugadmin/deploy.sh`.

### Ne PAS transférer/altérer

* `plugins/RPGQuest/data.db`
* `config.yml`, clés et configuration du serveur
* les mondes, les PNJ Citizens existants et leur persistance
* les cinq fichiers de contenu locaux du dépôt principal, non committés et non touchés
* **le dernier backup** (`rpgquest-20261007T145132Z-predeploy.jar`) — ne jamais l'écraser

### Redémarrage requis

**Oui** — redémarrage Minecraft requis, **groupé** pour les lots 4 et 5 (un seul redémarrage).
Prévenir les joueurs avant. Le panel, lui, ne demande aucun redémarrage Minecraft.

### Migration automatique

Aucune. `CURRENT_VERSION` = 27, inchangé.

## Rollback

`scripts/rollback-verygames.sh --latest` restaure le JAR précédent, puis redémarrer. Côté panel,
`scripts/plugadmin/deploy.sh` conserve la release précédente. Aucune donnée n'ayant été migrée, un
retour arrière ne perd rien.

## Logs / diagnostic

* Build Citizens incompatible : un `WARN` au démarrage nomme la cause et annonce que ces deux
  options seules sont indisponibles.
* Codes d'échec exposés au panel : `CITIZENS_INCOMPATIBLE`, `CITIZENS_NOT_FOUND`,
  `CITIZENS_UNAVAILABLE`, `NO_CITIZENS_BINDING`, `NO_ANCHOR`, `LOOKCLOSE_NOT_APPLIED`,
  `WANDER_CONFLICT`, `WANDER_NOT_ACTIVE`, `WANDER_UNAVAILABLE`, `WANDER_NOT_APPLIED`.

## Documentation mise à jour

* `docs/deployment/CITIZENS.md` — **nouveau** : versions, surface utilisée, absence de dépendance
  implicite, comportement en cas d'incompatibilité, limite de couverture, procédure si Citizens est
  mis à jour.
* `docs/RPGQUEST_BIBLE.md` — section Citizens réécrite, **avec correction explicite** des trois
  affirmations erronées de la version précédente.
* `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-255),
  `docs/deployment/SERVER_CHANGELOG.md` (lots 4 et 5), `.ai/ROADMAP.md`.

## Limitations / travail restant

1. **Aucun test automatisé ne couvre le pont Citizens** (`MockBukkit` n'embarque pas Citizens). Ce
   qui est testé est tout ce qui a pu être rendu pur ou contractuel. La validation du comportement
   réel est **manuelle et non acquise** — TC-255.
2. **Les numéros de version ne se recoupent pas.** Le serveur affiche une build **Jenkins**
   (`2.0.43-SNAPSHOT build 4232`) ; le dépôt Maven publie des snapshots horodatés de la même
   version `2.0.43-SNAPSHOT` (celui compilé date du 2026-09-11). Je **ne peux pas garantir** par
   ces seuls numéros que le JAR compilé correspond exactement à la build installée. C'est
   précisément pourquoi l'intégration est gardée : une divergence se manifesterait par un
   `CITIZENS_INCOMPATIBLE` explicite, sans affecter le reste.
3. **Non exposé dans ce lot**, et pourquoi : `worldguardregion` exigerait WorldGuard (exclu) ; la
   **vitesse** n'est pas un réglage de promenade mais de navigation globale du PNJ (`/npc speed`) ;
   `delay` (pauses, en ticks, défaut `-1` = aucune) et `pathfind` existent et pourraient être
   exposés, mais n'étaient pas nécessaires à ce premier lot.
4. `SkinTrait` devient accessible en appels typés maintenant que `citizens-main` est là. Le chemin
   actuel (commande structurée `/npc skin` avec sélection explicite) fonctionne et est déployé : je
   ne l'ai **pas** touché dans ce lot pour ne pas mêler un refactoring à une fonctionnalité.
5. Les validations manuelles antérieures restent en attente : TC-236 à TC-254.

## Prochaine étape suggérée

Dérouler TC-255 avec un PNJ de test après le redémarrage groupé, en commençant par le point 6
(remplacement refusé sans confirmation). Ensuite, selon le retour : exposer `delay`/`pathfind`, ou
basculer le skin sur `SkinTrait` en appels typés.
