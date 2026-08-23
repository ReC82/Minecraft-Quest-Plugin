# RPGQuest — Rapport Claude

## Informations
* Date : 2026-08-21
* Heure : 20:09 (rapport rédigé rétroactivement, immédiatement après la fin des travaux décrits ci-dessous, dans la continuité de la même session)
* Sujet : Outils de diagnostic admin pour les portails simples (WorldPortal) — `/rpgadmin worldportal here`/`debug`, `info` enrichi, logs `[TP-TRACE]` unifiés
* Statut : DONE (outils de diagnostic livrés, testés, build vert) — le bug de téléportation automatique dans le Hub qui a motivé cette étape reste **non résolu**, cause encore à confirmer sur le serveur réel avec ces outils
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `77ff6fc` (HEAD au moment de la rédaction) — **tout le travail décrit ci-dessous est encore non commité** dans l'arbre de travail à ce stade

## Demande

Après deux sessions précédentes de correctifs « à l'aveugle » sur un bug de téléportation
automatique dans `world_hub` (répit d'arrivée + logs `TP-TRACE` ad hoc ajoutés à une session
antérieure), de nouvelles observations réelles sur VeryGames ont contredit l'hypothèse de départ
(le joueur affecté n'était pas physiquement dans une zone `worldportal` visible ; deux joueurs à la
même position n'ont pas le même comportement). La demande explicite était de **ne plus corriger à
l'aveugle** et d'ajouter des outils ADMIN de diagnostic pour :

1. visualiser en jeu où se trouvent réellement les zones `WorldPortal` (particules/entités
   temporaires, jamais de bloc modifié) ;
2. enrichir `/rpgadmin worldportal info` (bornes, largeur/hauteur/profondeur, centre, durée de
   canalisation/répit) ;
3. lister tous les portails contenant la position actuelle du joueur (`here`) ;
4. des logs `[TP-TRACE]` plus précis et structurés (uuid, event, portal, world, x/y/z, inside,
   previousInside, grace, channel, from, destination), filtrés aux transitions réelles seulement ;
5. investiguer (sans corriger silencieusement) comment une téléportation pourrait se déclencher
   pour un joueur immobile (tâche répétée suspecte, zones superposées, anciennes définitions non
   supprimées, sélection Y incorrecte, min/max inversés...) et rapporter précisément toute anomalie
   trouvée ;
6. corriger un gap async déjà identifié (vérifier `player.isOnline()` avant `startChanneling`/
   `finishTeleport` dans les callbacks asynchrones de `PortalService`) ;
7. tests automatiques, build (`./gradlew clean build`), documentation à jour, déploiement JAR-only.

Explicitement demandé de ne travailler sur rien d'autre (pas de Story/Claim/économie) et de
s'arrêter à la fin de cette étape.

## Analyse

**Ce qui a été inspecté avant modification** : lecture complète de `WorldPortalTeleportListener`,
`WorldPortalRegistry`, `WorldPortalDefinition`, `PortalService`, `PortalListener`,
`RpgAdminCommand` (branche `worldportal`), et grep exhaustif de toute tâche Bukkit répétée
(`runTaskTimer`/`runTaskTimerAsynchronously`) dans tout `src/main` pour vérifier l'hypothèse d'un
poll périodique « isInside ».

**Symptômes rapportés** : un joueur téléporté automatiquement hors du Hub ~1-2 s après son
arrivée (connexion ou `/tp` admin), y compris à des positions signalées comme hors de toute zone
visible ; comportement reproductible pour un joueur mais pas pour un autre à la même position.

**Cause recherchée** : initialement soupçonnée comme une entrée immédiate dans une zone
`WorldPortal` mal positionnée (déjà partiellement traitée par le répit d'arrivée d'une session
précédente).

**Cause réellement identifiée** : **toujours pas confirmée** — deux hypothèses concrètes
documentées, chacune avec une preuve de plausibilité dans le code, mais aucune confirmée sur le
serveur réel faute d'avoir eu les outils pour le faire avant cette étape :

1. **Le répit d'arrivée ajouté précédemment ne corrige pas le bug, il le retarde exactement de sa
   durée (2 s)** — `WorldPortalTeleportListener#onMove` ne met jamais à jour l'état « portail
   courant » pendant le répit (retour anticipé avant toute lecture du registre). Si la zone couvre
   réellement le point d'arrivée, le premier `PlayerMoveEvent` de règlement *après* l'expiration du
   répit voit un état « jamais enregistré » et déclenche une téléportation neuve, immédiate — ce
   délai correspond exactement aux « 1-2 secondes » observées.
2. **Anomalie de code confirmée, non corrigée** : `WorldPortalRegistry#reload()` ne valide
   **jamais** les id dupliqués ni les chevauchements de zones entre fichiers, contrairement à
   `zone.ZoneLoader`/`travel.PortalLoader`. Seul `create()` (donc uniquement
   `/rpgadmin worldportal create`) vérifie les chevauchements, et seulement à la création. Un
   fichier ajouté/édité à la main dans `plugins/RPGQuest/world-portals/` (chemin explicitement
   supporté par `reload()`) peut donc faire coexister deux zones actives superposées sans qu'aucune
   erreur ne soit jamais journalisée — invisible à `list`/`info`, seul le nouvel outil `here` le
   révèle. Prouvé par un test exécutable, pas seulement affirmé.

**Hypothèses écartées, avec preuve** :
- Une tâche Bukkit répétée qui vérifierait périodiquement « le joueur est-il dans une zone » —
  **exclue** : grep exhaustif de `runTaskTimer(Async)` sur tout `src/main` ne trouve aucune
  occurrence liée à un portail en dehors de `PortalService#tickChannel`, qui ne fait que surveiller
  la tolérance de mouvement d'une canalisation **déjà démarrée**, jamais en démarrer une seule.
- Min/max inversés dans une définition de portail — **exclu** : le constructeur compact de
  `WorldPortalDefinition` rejette `minX > maxX` (et Y/Z) par une `IllegalArgumentException`, que
  `WorldPortalRegistry#reload()` intercepte déjà et journalise (fichier ignoré, pas de zone
  corrompue chargée).
- Sélection WorldEdit avec Y incorrect côté `/rpgadmin worldportal create` — **exclu** : le
  handler `handleWorldPortalCreate` calcule bien `Math.min`/`Math.max` sur les trois axes avant de
  construire la définition, aucune inversion possible par ce chemin.

## Travail effectué

### Outils de diagnostic ajoutés
- `/rpgadmin worldportal here` — liste TOUS les portails simples dont la zone contient la position
  actuelle (nouvelle méthode `WorldPortalRegistry#portalsContaining`, ajoutée à côté de `portalAt`
  sans le remplacer — `portalAt` reste le seul chemin consulté en jeu par
  `WorldPortalTeleportListener`, pour ne jamais ralentir le chemin chaud).
- `/rpgadmin worldportal debug show|hide <id>` / `showall` / `hideall` — visualisation par
  particules (`Particle.DUST`, couleur dérivée d'un hash de l'id pour distinguer deux zones
  superposées) le long des 12 arêtes du cuboïde (calcul géométrique pur dans le nouveau
  `WorldPortalDebugGeometry`, décalage de +1 sur les bornes max pour matcher le comportement
  inclusif de `WorldPortalDefinition#contains`) plus une étiquette flottante temporaire
  (`TextDisplay`, `setPersistent(false)`, jamais sauvegardée). État global (visible par tous les
  joueurs à proximité, comportement natif de `World#spawnParticle`), rendu rafraîchi toutes les
  0,5 s par une tâche répétée unique (`WorldPortalDebugService`). Jamais de bloc modifié.
- `/rpgadmin worldportal info <id>` enrichi : `enabled` en tête, monde source/destination séparés,
  mode de destination, bornes X/Y/Z, largeur×hauteur×profondeur, centre, et le répit d'arrivée
  global (ticks/secondes, exposé via `WorldPortalTeleportListener.arrivalGraceTicks()`).

### Instrumentation TP-TRACE unifiée
Toutes les lignes `[TP-TRACE]` (auparavant des chaînes ad hoc différentes selon la classe)
migrées vers un format unique centralisé dans `travel.TpTraceLogger` (un seul endroit à retirer
plus tard). Nouveaux événements ajoutés : `player_join`, `world_change`
(`PlayerChangedWorldEvent`), `external_teleport` (`PlayerTeleportEvent`, avec un garde-fou de
ré-entrance `selfInitiatedTeleport` pour éviter un doublon avec les propres téléportations de
`WorldPortalTeleportListener` — limitation documentée pour `PortalService`, dont le
`teleportAsync` rend la ré-entrance moins fiable à détecter), `portal_enter`/`portal_exit` (sur
les deux systèmes de portail), `channel_cancel` (nouveau — absent avant cette étape, avec la cause
`movement`/`damage`/`quit`), `teleport_start`/`teleport_success`/`teleport_failed` (le booléen
réel renvoyé par `Player#teleport`/`teleportAsync` est désormais journalisé, révélant si un autre
plugin a annulé la téléportation).

Les logs `[TP-TRACE]` ad hoc précédemment posés dans `RpgAdminCommand` (`/rpgadmin spawn tp`,
`/rpgadmin world tp`) ont été **retirés** : désormais couverts génériquement par
`WorldPortalTeleportListener#onTeleport` (`event=external_teleport`), qui capte tout
`PlayerTeleportEvent` quelle qu'en soit l'origine — évite un doublon incohérent.

### Décision technique notable
Le rendu de debug (`WorldPortalDebugService#renderVisiblePortals`) est **isolé par portail**
(`try`/`catch` autour du rendu de chaque zone) : un échec sur une zone (API d'affichage
indisponible, position aberrante) ne doit jamais empêcher le rendu des autres zones visibles au
même cycle, ni interrompre la tâche répétée. Cette protection a été ajoutée après avoir constaté
que MockBukkit v4.110.0 ne supporte pas encore `DisplayMock#setBillboard` (levait
`UnimplementedOperationException`) — la protection reste légitime indépendamment de
l'environnement de test (un vrai serveur Paper pourrait un jour rencontrer un cas limite similaire
sur une entité d'affichage).

### Gap async (déjà identifié en amont, corrigé ici)
`PortalService` : ajout de `player.isOnline()` avant `startChanneling` (deux branches : coût nul,
et après lecture du solde) et avant `finishTeleport` (après le débit) — abandon propre si le
joueur s'est déconnecté pendant l'appel asynchrone, plus aucune action sur un `Player` périmé.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/travel/TpTraceLogger.java`
- `src/main/java/com/lodygames/rpgquest/travel/WorldPortalDebugGeometry.java`
- `src/main/java/com/lodygames/rpgquest/travel/WorldPortalDebugService.java`
- `src/test/java/com/lodygames/rpgquest/travel/TpTraceLoggerTest.java`
- `src/test/java/com/lodygames/rpgquest/travel/WorldPortalDebugGeometryTest.java`
- `src/test/java/com/lodygames/rpgquest/travel/WorldPortalDebugServiceTest.java`
- `docs/claude-reports/README.md` (ce dossier)
- `docs/claude-reports/2026-08-21_2009_worldportal-debug-tools.md` (ce rapport)

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/travel/WorldPortalRegistry.java` — `portalsContaining`, Javadoc anomalie.
- `src/main/java/com/lodygames/rpgquest/travel/WorldPortalTeleportListener.java` — TP-TRACE unifié, nouveaux événements, `arrivalGraceTicks()`, garde-fou de ré-entrance.
- `src/main/java/com/lodygames/rpgquest/travel/PortalService.java` — TP-TRACE unifié, `channel_cancel`, `teleport_success`/`failed`, correctif `isOnline()`.
- `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java` — sous-commandes `worldportal here`/`debug`, `info` enrichi, retrait des logs ad hoc, tab-complete.
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — câblage de `WorldPortalDebugService`.
- `src/test/java/com/lodygames/rpgquest/travel/WorldPortalRegistryTest.java` — tests `portalsContaining` + preuves de l'anomalie.
- `docs/TRAVEL.md`, `docs/RPGQUEST_BIBLE.md`, `docs/ARCHITECTURE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`, `docs/deployment/VERYGAMES.md` — voir « Documentation mise à jour ».
- `CLAUDE.md` — nouvelle règle permanente « Session Reports » (voir ce rapport lui-même, section suivante de cette même demande utilisateur, appliquée dès ce rapport).

## Base de données / migrations
Aucune modification. Aucune nouvelle table, aucune nouvelle colonne, aucune migration
`SchemaMigrator` pour cette étape. Compatible tel quel avec toute base `data.db` existante.

## Configuration / données
Aucun fichier YAML de configuration ajouté ou modifié (`config.yml`, `messages.yml` non touchés).
Aucun nouveau dossier de données runtime requis — `plugins/RPGQuest/world-portals/` existant est
réutilisé tel quel par les nouveaux outils.

## Tests automatiques
- Commande exécutée : `./gradlew clean build`
- Résultat : `BUILD SUCCESSFUL in 2m 12s`
- Nouveaux tests ajoutés pour cette étape : 27 (répartis dans `TpTraceLoggerTest` : 2,
  `WorldPortalDebugGeometryTest` : 5, `WorldPortalDebugServiceTest` : 11, et 4 nouveaux tests dans
  `WorldPortalRegistryTest` — dont les deux preuves exécutables de l'anomalie de validation
  croisée manquante ; le solde vient d'ajustements mineurs comptés dans ces mêmes fichiers).
- Nombre total (dernier build complet, module principal uniquement, hors `web-api`) : **773 tests,
  0 échec, 11 ignorés** (`skipped` — préexistants, sans rapport avec cette étape).
- Un échec intermédiaire a été rencontré puis corrigé pendant cette même session (avant le build
  final ci-dessus) : `WorldPortalDebugServiceTest#renderingTickNeverThrowsWithAVisiblePortalInALoadedWorld`
  échouait à cause d'une limitation de MockBukkit (`DisplayMock#setBillboard` non implémenté) — corrigé
  en isolant le rendu par portail (voir « Travail effectué »), qui a fait repasser le test au vert.

## Tests manuels à effectuer

1. `/rpgadmin worldportal list` — confirmer que les portails existants apparaissent normalement
   (aucune régression sur les commandes déjà existantes).
2. Se tenir dans la zone d'activation d'un portail simple connu, taper `/rpgadmin worldportal
   here` → doit lister ce portail avec `inside=true`.
3. Se tenir hors de toute zone, `/rpgadmin worldportal here` → message « aucun portail simple à la
   position actuelle ».
4. `/rpgadmin worldportal debug show <id>` sur un portail existant → des particules colorées
   doivent apparaître le long du contour du cuboïde (coins au sol + arêtes verticales visibles)
   plus une étiquette flottante avec l'id au-dessus de la zone.
5. `/rpgadmin worldportal debug show <id-inconnu>` → message « Portail simple inconnu », pas
   d'exception.
6. `/rpgadmin worldportal debug showall` puis, après quelques secondes, `hideall` → toutes les
   zones chargées s'affichent puis disparaissent complètement (aucune particule ni étiquette
   résiduelle).
7. `/rpgadmin worldportal info <id>` → vérifier la présence de tous les nouveaux champs (largeur ×
   hauteur × profondeur, centre, répit d'arrivée).
8. Reproduire le scénario du bug signalé (connexion ou `/tp` vers le Hub, rester immobile) et
   relever dans les logs serveur toutes les lignes `[TP-TRACE]` du joueur concerné (filtrer par
   `uuid=`) sur les ~30 secondes entourant l'incident.
9. Vérifier `//wand` (WorldEdit) fonctionne toujours normalement (non concerné par cette étape,
   mais bon réflexe de non-régression vu l'historique récent).

## Résultat attendu

- Les commandes de diagnostic fonctionnent sans exception, quel que soit l'état (portail
  existant/inconnu, zone chargée/non chargée, monde destination chargé/non chargé).
- Aucune commande existante (`create`/`info`/`list`/`enable`/`disable`/`delete`) n'a changé de
  comportement observable, hormis l'enrichissement de `info`.
- Les lignes `[TP-TRACE]` apparaissent au nouveau format unifié, uniquement sur des transitions
  réelles (jamais un déluge à chaque tick pour un joueur immobile).
- Le bug de téléportation automatique **n'est pas censé être résolu** par cette étape — l'objectif
  est uniquement d'obtenir, via `here`/`debug`/les logs, la preuve directe permettant de trancher
  entre les deux hypothèses documentées dans « Analyse ».

## Reset / retour à l'état initial

Reset ciblé, pas de wipe nécessaire :
- `/rpgadmin worldportal debug hideall` supprime immédiatement tout rendu de debug en cours
  (particules qui expirent naturellement, étiquettes explicitement retirées).
- Aucun état persistant n'est créé par ces outils (`visiblePortalIds`/`labelEntities` sont
  en mémoire uniquement, remis à zéro à chaque redémarrage du plugin/serveur).
- Pour repartir d'un jeu de portails simples propre : supprimer les fichiers concernés dans
  `plugins/RPGQuest/world-portals/` puis `/rpgadmin worldportal list` pour confirmer (pas besoin de
  toucher à `data.db`).

## Déploiement VeryGames

### À transférer
- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (ou le nom de version exact généré par
  `./gradlew clean build`).

### Ne PAS transférer/altérer
- `data.db` — aucune migration, aucune raison d'y toucher.
- `plugins/RPGQuest/world-portals/` (et tout autre dossier de données) — inchangés par ce JAR.
- Mondes locaux (`world_hub`, `wild`, etc.) — non concernés.
- Données joueurs locales.
- `Citizens/saves.yml`.
- Logs/caches existants (les nouveaux logs `[TP-TRACE]` viendront s'ajouter aux logs normaux du
  serveur, rien à préparer côté fichier).

### Redémarrage requis
Oui — remplacement de JAR standard (scénario 2 de `docs/deployment/VERYGAMES.md`).

### Migration automatique
Non applicable — aucune migration de schéma pour cette étape.

## Rollback

Remettre l'ancien `rpgquest-*.jar` sauvegardé avant remplacement (voir la procédure « Mise à jour
du seul JAR RPGQuest » de `docs/deployment/VERYGAMES.md`, qui recommande explicitement cette
sauvegarde préalable) puis redémarrer. Aucune donnée à restaurer (aucune migration n'a eu lieu).

## Logs / diagnostic

- Préfixe de log : `[TP-TRACE]` (niveau `INFO`).
- Commandes de diagnostic : `/rpgadmin worldportal here`, `/rpgadmin worldportal debug
  show|hide|showall|hideall`, `/rpgadmin worldportal info <id>`.
- Lignes importantes à récupérer en cas de reproduction du bug : toutes les lignes `[TP-TRACE]`
  contenant l'`uuid=` du joueur concerné, sur les ~30 secondes entourant l'incident — en
  particulier chercher si une ligne `event=channel_start` (portail classique `/rpgadmin portal`)
  précède la téléportation, ce qui trancherait en faveur de ce système plutôt que du portail
  simple.
- À me renvoyer si le problème persiste : ces lignes `[TP-TRACE]` brutes, plus le résultat de
  `/rpgadmin worldportal here` exécuté à l'endroit exact où le bug se produit.

## Documentation mise à jour
- `docs/TRAVEL.md` — nouvelle section complète sur les portails simples, les outils de diagnostic,
  l'anomalie identifiée, et l'analyse du bug.
- `docs/RPGQUEST_BIBLE.md` — tableau des commandes `worldportal` enrichi (section 6).
- `docs/ARCHITECTURE.md` — nouvelle sous-section « Diagnostic WorldPortal » (décisions techniques).
- `docs/current_state.md` — section « Investigation en cours » remplaçant l'ancienne mention de
  l'instrumentation TP-TRACE de la session précédente.
- `docs/MANUAL_TEST_PLAN.md` — nouveau cas de test TC-190 + ligne dans la table de recette.
- `docs/deployment/VERYGAMES.md` — note sur le caractère JAR-only de ce déploiement.
- `CLAUDE.md` — nouvelle règle permanente sur les rapports de session (voir ci-dessus).

## Limitations / travail restant
- **Le bug de téléportation automatique n'est pas résolu.** Cette étape ne livre que les outils
  pour le diagnostiquer précisément ; aucune correction fonctionnelle n'a été appliquée à la
  logique de téléportation elle-même (consigne explicite de la mission).
- L'anomalie de validation croisée manquante dans `WorldPortalRegistry#reload()` reste non
  corrigée (documentée, prouvée par test, mais volontairement laissée telle quelle).
- Aucun garde-fou de ré-entrance pour `event=external_teleport` côté `PortalService`
  (`teleportAsync`) — un doublon de log reste possible dans ce cas précis, documenté dans le
  Javadoc plutôt que corrigé (complexité jugée disproportionnée pour une instrumentation
  temporaire).
- Le rendu visuel réel (particules/étiquette) n'a pas pu être vérifié visuellement (aucun client
  Minecraft dans cet environnement) — seule l'absence d'exception a été vérifiée via MockBukkit.
- Aucun commit n'a encore été créé pour ce travail au moment de la rédaction de ce rapport.

## Prochaine étape suggérée
Déployer ce JAR sur VeryGames, reproduire le bug avec `/rpgadmin worldportal here` et les logs
`[TP-TRACE]` à l'appui, puis revenir avec ces éléments pour trancher entre les deux hypothèses
documentées et appliquer un correctif ciblé (pas commencé automatiquement).
