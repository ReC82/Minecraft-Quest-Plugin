# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 03:49
* Sujet : Issue #180 — fin Lily → Julius : retour à l'intro après la tarte, ville de Julius, PNJ nommés
* Statut : PARTIAL (livré et déployé sur DEV ; validation en jeu et décisions propriétaire en attente — ticket laissé ouvert)
* Branche Git : `feature/180-lily-julius-ville` (PR #488, fusionnée dans `chore/257-ci-github-actions`)
* Commit actuel si disponible : `12ae767` (merge de la PR #488)
* Début de la tâche : 2026-10-17 02:54:34
* Fin de la tâche : 2026-10-17 03:49:49
* Durée totale : 00:55:15

## Demande
Ordre propriétaire du 16/10 (blocage narratif) : (1) trouver la cause exacte du retour à l'intro de
Lily et du bouton « Goûtons la tarte » après « Parle-moi de Julius », corriger à la source ;
(2) vérifier la progression de la story ; (3) identifier dans les données DEV la ville de Julius sans
rien inventer ; (4) journal : nommer le PNJ au lieu de « Parler au PNJ ». Contenu de référence = DEV
et les fichiers non commités du propriétaire, à ne modifier que par le circuit officiel, avec
sauvegarde. Pas de migration, aucune progression de joueur touchée. #467 traite à part les boutons.

## Analyse
**Contenu DEV = fichiers du propriétaire** : `dialogues/lily.yml`, `julius.yml`, `quests/lily_*.yml`,
`stories/lily_souvenirs.yml` téléchargés en lecture seule, identiques octet pour octet à
`/srv/rpgquest/repo/src/main/resources/…`.

**(1) Cause exacte — contenu, pas le moteur de routage.** Dans `lily.yml`, le nœud `gouter` porte le
choix « Parle-moi de Julius » avec seulement `next: accueil`, **sans action**. Or la seconde étape de
`lily_tarte` (« goûter ») est un objectif `TALK_TO_NPC lily`, et cet objectif ne progresse **qu'au clic
droit sur le PNJ** (`QuestNpcInteractListener` / `QuestCitizensNpcInteractListener`), jamais en
naviguant dans son dialogue. Parcours du propriétaire : remise du charbon dans la conversation →
« Retour » → « Goûtons la tarte » (condition `lily_tarte` ACTIVE + aucune remise en attente) → `gouter`
→ « Parle-moi de Julius » → `accueil` : `lily_tarte` est toujours ACTIVE, donc l'accueil (texte
d'introduction) repropose « Goûtons la tarte ». La quête n'est validée qu'au clic droit suivant, d'où la
quête « Sur la piste de Julius » obtenue plus tard. Reproduit par un test automatisé.
Le moteur se comporte comme spécifié : faire compter toute navigation comme « parler » aurait sauté le
moment de la tarte (validé dès « Retour » après la remise) et changé la sémantique de toutes les quêtes
existantes ; l'outil prévu pour « ce choix termine l'étape » est l'action `ADVANCE_QUEST`.
Ordre des écouteurs au clic droit vérifié : la progression des quêtes est enregistrée au démarrage
(`registry.start(questProgressEngine)`) avant l'écouteur des dialogues, donc la validation précède
l'évaluation de l'accueil. Chaîne `ADVANCE_QUEST` → nœud suivant : écritures et lectures SQLite sur
le même exécuteur mono-thread, l'offre Julius voit bien la tarte terminée.

**(2) Progression de la story** : `lily_souvenirs` enchaîne `lily_repas → lily_fleurs →
lily_ingredients → lily_tarte → lily_julius` (prérequis cohérents). `lily_pumpkin` existe sur DEV mais
n'est **pas** dans la story (aucun prérequis ni lien de dialogue). `lily_julius` = `TALK_TO_NPC julius`,
et `julius.yml` propose « Lily m'envoie » dès que `lily_julius` n'est plus `NOT_STARTED`. Aucun
`story_progress` n'existe encore dans la base DEV.

**(3) Ville de Julius — données DEV (lecture seule)** :
- villes (`village_centers`) : `ville_central_city` « Central City », `ville_nerana` « Nerana »,
  `ville_biscofy` « Academy », toutes dans `world_hub`, aucune avec PNJ central ni point d'arrivée ;
- PNJ Citizens (`Citizens/saves.yml`, lecture) : **Julius** (#11) à `world_hub` 754.5 / 69 / -1093.3,
  soit **exactement le centre de Nerana** (754.5 / 69 / -1093.5) ; **Lily** (#10) à 719 / 67 / -671, dans
  Central City ; Nerana est à ~420 blocs **au nord** de Lily ;
- balises de voyage automatiques à ~50–110 blocs de Nerana (`stony_shore_2_-5`, `sparse_jungle_3_-5`).
La ville de Julius est donc **déduite des données posées par le propriétaire**, pas inventée :
`ville_nerana`. Le lieu de l'attaque, lui, n'existe dans aucune donnée (le texte dit seulement « près de
notre ancien village ») : rien n'a été inventé à ce sujet.

**(4) Journal** : depuis #475 (déployé le 16/10 à 21:23, après les captures), les lignes du journal
nomment déjà le PNJ (« Parler à Lily », « Charbon (à remettre à Lily) ») — `npcs/lily.yml` et
`npcs/julius.yml` ont un `display_name`. Restaient la **bossbar de la quête suivie** et `/quest <id>`,
qui affichaient l'identifiant logique (« Parler à lily ») via `QuestObjective.describe`.

## Travail effectué
1. **Moteur partagé — villes citées par identifiant** : valeurs de dialogue à argument
   `%city_name:<id_ville>%` (nom actuel, renommable dans le panel) et `%city_direction:<id_ville>%`
   (« au nord, à environ 420 blocs », « tout près : tu y es déjà », « loin d'ici, dans un autre
   monde… »), calculées à l'affichage depuis le centre réel de la ville et la position du joueur.
   Ville inconnue ou désactivée : le jeton reste visible (même règle qu'une clé inconnue). Aucune
   coordonnée affichée, aucun waypoint débloqué.
2. **Libellés d'objectifs** : `QuestObjective.describe(objective, npcNames)` — bossbar, `/quest`,
   journal de secours et `quest.player.status` du panel nomment le PNJ par son nom affiché ; sans
   nom connu, texte historique inchangé.
3. **Contenu Lily corrigé sur DEV par le circuit officiel** (éditeur de dialogues PlugAdmin →
   « Enregistrer » → « Mettre en jeu ») après sauvegarde datée — trois changements, rien d'autre :
   - `gouter` / « Parle-moi de Julius » : `ADVANCE_QUEST rpgquest:lily_tarte` puis `next: offre_julius`
     (au lieu de `next: accueil`) ;
   - `offre_julius` : « … qu'il vit à %city_name:ville_nerana%, une autre ville, près de notre ancien
     village. C'est %city_direction:ville_nerana%. … » ;
   - `rappel_julius` (« Je cherche toujours Julius. », consultable à tout moment pendant la quête) :
     « Julius vit à %city_name:ville_nerana%, près de notre ancien village. C'est
     %city_direction:ville_nerana%. Je t'en prie, va lui parler. ».
   Résultat agent : `PUBLISHED`, « Mis à jour sur DEV, rechargé et confirmé par le moteur. » ; fichier
   relu sur DEV : SHA-256 `a5c1e6bc2f5b…`, identique à celui de PlugAdmin.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/city/CityDirections.java`
- `src/test/java/com/lodygames/rpgquest/city/CityDirectionsTest.java`
- `src/test/java/com/lodygames/rpgquest/quest/model/QuestObjectiveDescribeTest.java`
- ce rapport

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/dialogue/DialogueTextPlaceholders.java` (valeurs à argument)
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` (`city_name`, `city_direction`)
- `src/main/java/com/lodygames/rpgquest/quest/model/QuestObjective.java`,
  `quest/progress/QuestProgressEngine.java`, `ui/QuestJournalService.java` (PNJ nommé)
- tests : `DialogueTextPlaceholdersTest`, `DialogueSessionEngineTest` (reproduction + correctif Lily)
- docs : `RPGQUEST_BIBLE.md`, `docs-site/dialogues.html`, `current_state.md`, `MANUAL_TEST_PLAN.md`
  (TC-339), `deployment/SERVER_CHANGELOG.md`
- **hors Git** : `plugins/RPGQuest/dialogues/lily.yml` sur DEV et, par construction du circuit panel
  (`PLUGADMIN_CONTENT_DIR`), le fichier non suivi du propriétaire
  `/srv/rpgquest/repo/src/main/resources/dialogues/lily.yml` — **non commité**.

## Base de données / migrations
Aucune migration, aucune écriture. `data.db` DEV seulement **copiée** pour lecture (villes, waypoints,
balises, liaisons Citizens). Aucune progression de joueur modifiée.

## Configuration / données
Aucune configuration modifiée. Aucune ville, aucun PNJ, aucun waypoint créé ou déplacé.

## Tests automatiques
- Local (heap 768 m), ciblé : `CityDirectionsTest`, `QuestObjectiveDescribeTest`,
  `DialogueTextPlaceholdersTest`, `QuestProgressEngineTest`, `KitProgressServiceTest`,
  `DialogueSessionEngineTest` (45 tests, 0 échec, dont
  `aChoiceBackToTheGreetingWithoutAdvancingOffersTheTasteAgain` qui reproduit le bug et
  `advancingTheTasteStepLeadsStraightToTheJuliusOffer` qui valide le correctif), `*QuestJournal*` : verts.
- CI GitHub PR #488 : verte. Un premier run « Tests plugin » a échoué sur
  `DeedClaimListenerTest.rightClickingTheDeedOutsideOwnMainClaimShowsTheBeaconInstead`
  (`ArrayIndexOutOfBoundsException` l. 294, course asynchrone de création de claim, sans lien avec ce
  lot ; le run jumeau du même commit était vert) ; relancé → vert. CI du merge `12ae767` verte
  (vérifiée par le script de déploiement).

## Tests manuels à effectuer
TC-339 (`docs/MANUAL_TEST_PLAN.md`) — **PENDING MANUAL VALIDATION**. Aucun test Minecraft n'a été fait.

## Résultat attendu
Après la tarte, « Parle-moi de Julius » termine « La tarte de maman » (récompenses une seule fois) et
mène directement à l'offre de Lily qui nomme Nerana et donne la direction ; plus jamais l'intro ni
« Goûtons la tarte ». Pendant « Sur la piste de Julius », « Je cherche toujours Julius. » redonne la
ville et la direction recalculée. Journal, bossbar et `/quest` : « Parler à Lily » / « Parler à Julius ».

## Reset / retour à l'état initial
Contenu : republier la sauvegarde
`~/.local/share/rpgquest/content-backups/20261017T012251Z-180-lily/lily.yml` (SHA `0eb6eb5a…`) via
PlugAdmin. JAR : `scripts/rollback-verygames.sh --latest`.

## Déploiement VeryGames
- JAR : `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --wait-ci` depuis le worktree
  propre à `12ae767` — `DEPLOY_EXIT=0`, JAR en ligne 3 387 171 o (== local, SHA-256
  `e6bdbde9…`), sauvegarde `rpgquest-20261017T014626Z-predeploy.jar` (3 383 974 o).
  Journal : `~/.local/share/rpgquest/deploy-logs/20261017T*-180.log`.
- Redémarrage : `scripts/verygames-restart.sh` (0 joueur) → ONLINE, RPGQuest vert dans `plugins`.
- Contenu : `dialogues/lily.yml` publié par PlugAdmin (voir ci-dessus). PlugAdmin lui-même non
  redéployé (aucun changement `control-panel/`).
- À ne pas altérer : `data.db`, les autres dialogues/quêtes du propriétaire. Pas de migration.

## Rollback
`scripts/rollback-verygames.sh --latest` + redémarrage ; contenu : republier la sauvegarde datée.

## Logs / diagnostic
Résultat de l'action agent `2c519cd2-eab6-4b69-9c1e-4a8c70c9b21c` : `PUBLISHED`, `runtimeConfirmed=true`.
Compte PlugAdmin temporaire `claude-180` créé pour la publication puis supprimé.

## Documentation mise à jour
Bible (table des valeurs dynamiques), `docs-site/dialogues.html`, `current_state.md`, TC-339,
`SERVER_CHANGELOG.md`.

## Limitations / travail restant
- **Décision propriétaire** : confirmer que la ville de Julius est bien **Nerana** (`ville_nerana`) —
  déduction tirée de la position de Julius, au centre exact de Nerana. Sinon, remplacer l'identifiant
  dans les deux nœuds via l'éditeur de dialogues.
- Option propriétaire : désigner Julius comme **PNJ central** de Nerana (page Villes, #322) et/ou un
  point d'arrivée de voyage ; non fait (décision de configuration).
- La description statique de la quête `lily_julius` (journal) dit toujours « une autre ville » : les
  descriptions de quête ne prennent pas de valeurs dynamiques ; à reformuler par le propriétaire s'il
  le souhaite (écrire « Nerana » en dur ne suivrait pas un renommage).
- Lieu précis de l'attaque : absent de toute donnée ; non inventé.
- Cas « charbon remis puis conversation rouverte » : la tarte est validée au clic droit et le nœud
  `gouter` (souvenir de Julius) est sauté ; l'offre Julius porte l'information nécessaire.
- `lily_pumpkin` hors story : à décider (garder, intégrer ou retirer).
- Les cinq chapitres « cultures » de la vision initiale (#180, première partie) ne sont pas traités ici.

## Prochaine étape suggérée
Test propriétaire TC-339 sur DEV, puis décision sur Nerana / PNJ central.
