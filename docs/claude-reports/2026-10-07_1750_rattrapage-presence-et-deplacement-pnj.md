# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-07
* Heure : 17:50
* Sujet : **Rapport de rattrapage** — état de présence exact d'un PNJ Citizens et déplacement sans
  recréation (#165, lot 4)
* Statut : DONE (déploiement achevé au lot 5 ; validation en jeu `PENDING MANUAL VALIDATION`)
* Branche Git : `feature/169-special-mobs-boss`
* Commit actuel si disponible : `2f181fd`
* Début de la tâche : non mesurable
* Fin de la tâche : non mesurable
* Durée totale : non mesurable

> **Pourquoi ce rapport est écrit après coup.** Même raison que le rapport de rattrapage du lot 3 :
> la session a atteint sa limite de contexte avant que je l'écrive. Les horodatages de session
> n'ayant pas été capturés, ils sont déclarés **`non mesurable`** plutôt qu'inventés. Le contenu
> technique est reconstitué à partir du commit `2f181fd`, de l'entrée « lot 4 » de
> `docs/deployment/SERVER_CHANGELOG.md`, du journal de déploiement et des tests du dépôt.

## Demande

Retour utilisateur : Andy était **visible en jeu** à `world_hub, 737.5 / 67 / -684.5`, mais sa fiche
le déclarait « non apparu » avec un relevé vieux d'une seconde. Consignes : diagnostiquer `spawned`
et `liveLocation` sur **toute** la chaîne, vérifier aussi le **chargement des chunks** et l'**ordre
temporel des relevés** avant de conclure, et corriger le doublon d'affichage « Relevé il y a il y a
1 s ». Ajouter en outre la **modification de localisation** depuis la fiche, sans recréer le PNJ.

## Analyse

`spawned` n'était pas faux par erreur : il était **exact mais mal interprété**. Citizens
**dématérialise** ses PNJ dès qu'aucun joueur n'est à portée. Afficher « non apparu » tout court
laissait donc croire à un PNJ cassé alors que son comportement était normal, et qu'il réapparaît dès
qu'un joueur approche.

Il manquait deux informations pour lever l'ambiguïté, toutes deux disponibles via des traits publics
de `citizensapi` :

* le trait **`Spawned`** — l'**intention persistante** (« ce PNJ doit-il se matérialiser »),
  distincte de l'état transitoire du moment ;
* l'état de **chargement du chunk** de sa position, lu **sans** le charger
  (`World#isChunkLoaded`) — jamais de génération de terrain forcée pour sonder un PNJ.

Ces deux éléments expliquent à eux seuls la quasi-totalité des cas « pas matérialisé ».

Le doublon « Relevé il y a il y a 1 s » venait d'un libellé déjà porteur de « il y a » auquel le
rendu ajoutait le sien.

## Travail effectué

### État de présence : trois états au lieu de deux

* **présent en jeu** — l'entité existe réellement, et la position vient d'elle ;
* **en veille** — non matérialisé mais le trait `Spawned` est vrai : Citizens le fera apparaître dès
  qu'un joueur approchera. La fiche précise si c'est parce que le chunk n'est pas chargé.
  **Ce n'est pas une anomalie**, et la fiche le dit ;
* **désactivé dans Citizens** — trait `Spawned` à faux : il n'apparaîtra pas, même si un joueur
  s'approche.

La **source** de la position reste affichée et distinguée : position de l'entité réellement
présente, ou **dernière position enregistrée** — qui ne prouve aucune présence actuelle.

### Déplacement sans recréation

Nouvelle action `npc.citizens.move` :

* passe par `NPC#teleport(Location, TeleportCause.PLUGIN)` — donc identité Citizens (UUID, id
  numérique), identifiant RPGQuest, skin, traits et liaisons dialogues/quêtes sont conservés **par
  construction** : rien n'est détruit ni recréé ;
* **arrivée validée** avant tout mouvement, avec le même critère que le placement automatique du lot
  2 (`NpcPlacementPlanner.isAcceptable`) : sol praticable, deux cases libres, ni liquide ni portail.
  **Aucun bloc n'est cassé ni posé** ; destination refusée ⇒ le PNJ ne bouge pas
  (`UNSAFE_DESTINATION`) ;
* coordonnées validées (nombres finis, bords de monde, bornes **réelles** du monde chargé), monde
  choisi parmi les mondes effectivement chargés ; changer de monde est permis ;
* pour un PNJ **non matérialisé**, c'est sa position **enregistrée** qui change et il n'est **pas**
  fait apparaître — le formulaire et le message le disent ;
* la position obtenue est **relue et comparée** à celle demandée ; en cas d'écart, l'action échoue
  (`MOVE_NOT_APPLIED`) en indiquant la position conservée, plutôt que de rendre un succès trompeur ;
* formulaire prérempli depuis la position **réelle** si elle est connue, sinon depuis la dernière
  position **enregistrée** — et le formulaire dit laquelle, parce que les deux n'engagent pas la
  même confiance. Orientation yaw/pitch en section avancée repliée.

## Fichiers créés

Aucun fichier de classe nouveau (l'action réutilise les planificateurs existants).

## Fichiers modifiés

* `src/main/java/com/lodygames/rpgquest/npc/CitizensNpc.java` — `shouldSpawn`, `chunkLoaded`
* `src/main/java/com/lodygames/rpgquest/npc/CitizensNpcBridge.java` — trait `Spawned`,
  `isChunkLoaded`, `moveByUuid`
* `src/main/java/com/lodygames/rpgquest/npc/NpcIdentityService.java` — `moveCitizens`
* `src/main/java/com/lodygames/rpgquest/web/agent/` — `AgentActionType`, `AgentActions`,
  `AgentActionExecutor`, `BukkitAgentActions`
* `control-panel/.../panel/agent/AgentActionCatalog.java` — validation de `npc.citizens.move`
* `control-panel/.../panel/web/AgentPages.java` — trois états, formulaire de déplacement, correction
  du doublon « il y a »
* tests : `NpcCitizensPayloadTest`, `NpcsCatalogTest`, `StubAgentActions`, `AgentActionExecutorTest`
* `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations

Aucune. `SchemaMigrator.CURRENT_VERSION` reste à 27.

## Configuration / données

Aucune. Aucun PNJ réel modifié : le déplacement n'a lieu que sur demande explicite d'un
administrateur.

## Tests automatiques

`./gradlew test` + `./gradlew build` : **1747 tests plugin (0 échec, 37 ignorés)** et **708 tests
panel (0 échec, 1 ignoré)**.

> **Honnêteté sur cette suite.** Un premier run de cette suite a rapporté **1 échec** sur
> `RestartServiceTest` — un test sans rapport avec ce lot. Conformément à la consigne reçue ensuite,
> cette suite **n'a pas été considérée comme verte** sur la base de ce run. Elle a été relancée en
> exécution unique lors du lot 5 : `BUILD SUCCESSFUL`, `RestartServiceTest` **24 / 0**, panel
> **708 / 0**. Le diagnostic (budgets de temps réels de 3 s et 6 s dans le test, donc sensibilité à
> la charge) est détaillé dans le rapport du lot 5.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — vérifier en jeu qu'un PNJ « en veille » réapparaît à l'approche d'un
joueur, et qu'un déplacement conserve skin, dialogue et quêtes. Aucune case n'a été cochée.

## Résultat attendu

La fiche d'un PNJ Citizens indique un état de présence **exact et non alarmant**, et permet de le
déplacer sans le recréer.

## Reset / retour à l'état initial

Un déplacement se défait en redéplaçant. Aucune donnée persistante propre à RPGQuest n'est écrite.

## Déploiement VeryGames

### À transférer

JAR RPGQuest **et** Control Panel.

### Ne PAS transférer/altérer

`data.db`, config, clés, mondes, PNJ Citizens existants, les cinq fichiers de contenu locaux du
dépôt principal.

### Redémarrage requis

**Oui** — non effectué au moment de ce lot, **groupé avec le lot 5**.

### Migration automatique

Aucune.

**Exécution réelle du transfert**, achevée au début du lot 5 :

* Branche `feature/169-special-mobs-boss` @ `2f181fd`, worktree propre.
* JAR : 1 899 262 o, SHA-256
  `9b27134e684173bea4d77b3c64c73404b9f8000f304872c793c543c71c3d0a09`.
* Backup : `rpgquest-20261007T145132Z-predeploy.jar`, 1 895 945 o, SHA-256
  `14703d4c912df3a15781a7faea0de422c2bda9b53bc4b732c1ebb89b12aae86a` (= le lot 3).
* **Deux tentatives antérieures n'avaient rien transféré** : la première s'est arrêtée à l'étape
  3/8 sans créer de backup ; la seconde a échoué parce que j'avais lancé une exécution Gradle alors
  que la précédente tournait encore. Détail et cause dans le rapport du lot 5.

## Rollback

`scripts/rollback-verygames.sh --latest`, puis redémarrer.

## Logs / diagnostic

Codes d'échec exposés : `UNSAFE_DESTINATION`, `MOVE_NOT_APPLIED`, `UNKNOWN_WORLD`,
`INVALID_POSITION`, `CITIZENS_NPC_MISSING`, `NO_CITIZENS_BINDING`.

## Documentation mise à jour

`docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`
(entrée « lot 4 »).

## Limitations / travail restant

* Rapport reconstitué après coup : horodatages de session `non mesurable`.
* Validation en jeu non faite.
* Au moment de ce lot, Look Close et Wander restaient non livrés — traités au lot 5.

## Prochaine étape suggérée

Traitée depuis : lot 5 (Look Close et Wander).
