# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-05
* Heure : 19:30 (locale machine)
* Sujet : #12 — signal visuel discret sur les PNJ : quête réellement disponible ou dialogue accessible non lu (première version)
* Statut : DONE pour la première version livrée — validation **en jeu** entièrement à faire (TC-244)
* Branche Git : `feature/169-special-mobs-boss` (worktree dédié `/srv/rpgquest/worktree-169`)
* Commit actuel si disponible : `f3f89d0` (code + tests), documentation committée ensuite
* Début de la tâche : 2026-10-05 18:18:59 (heure locale réelle, première action)
* Fin de la tâche : 2026-10-05 19:30:40 (heure locale réelle)
* Durée totale : 01:11:41

## État des opérations engagées avant la bascule

Le ticket demandait de terminer proprement ce qui était en cours avant de passer à #12 :

- **#196** (catalogue des objets) : livré **et déployé** au lot 6. Rien en suspens.
- **#194** (suppression de quêtes/stories) : livré **et déployé** au lot 7. Il attend uniquement
  le **test manuel TC-243** du propriétaire ; l'étape 1 du protocole lui a été fournie.
- Working tree propre, branche poussée, aucun build en cours. **Rien n'a été interrompu.**

## Demande

Réaliser la première version du périmètre **réactivé le 05/10** dans le ticket #12 (cette
activation remplace la mention « plus tard / hors périmètre actuel » du corps de l'issue) : signal
visuel discret, **propre à chaque joueur**, pour une quête réellement disponible ou un
dialogue/nœud accessible jamais lu. Particules individuelles compatibles client vanilla, Paper et
Citizens. Réutiliser la disponibilité existante. Lecture **persistante** et limitée aux
dialogues/nœuds **réellement présentés**. Réglages configurables, traitement limité aux PNJ
proches, sans boucle coûteuse à chaque tick.

## Analyse — l'audit a décidé la conception

### 1. « Quête prête à être rendue » n'est pas un état observable

Le ticket dit : « intégrer uniquement si cet état existe effectivement dans le moteur ; sinon
documenter hors MVP, sans inventer une nouvelle mécanique de remise ».

`QuestState.READY_TO_TURN_IN` **existe** dans l'énumération, et trois endroits du moteur le
testent — de quoi croire qu'il est utilisable. Mais `QuestProgressEngine#turnIn` fait ceci :

```java
progress.setState(QuestState.READY_TO_TURN_IN);
progress.setState(QuestState.COMPLETED);
```

Il est posé puis remplacé **dans la même méthode**, et seul `COMPLETED` est persisté. Ce n'est
donc jamais un état qu'un joueur traverse de façon observable. → **documenté hors MVP**, aucune
mécanique de remise inventée.

### 2. Un PNJ ne référence aucune quête

`NpcDefinition` ne porte que `dialogueId`. Le lien quête → PNJ vit dans le champ `giver:` **de la
quête**, écrit par `QuestGiverStore` dans le fichier de quête. Le signal part donc des **quêtes**
(filtre sur `giver`), pas d'une table de liaison qui n'existe pas. C'est le même constat que celui
qui avait décidé la conception de #194 — vérifié, pas réutilisé de mémoire.

### 3. Rien n'existait pour la lecture des dialogues

Aucune trace de « lu » / « vu » / « visité » dans le moteur de dialogue. Il fallait donc créer la
persistance, et surtout choisir **où** marquer : `DialogueSessionEngine#openNode` est le
**seul** point où `renderer.render(...)` est appelé, donc le seul endroit où « lu » a un sens.

## Travail effectué

### Disponibilité réutilisée, pas dupliquée
Extraction de `QuestProgressEngine#availability(playerId, questId, ignorePrerequisites)` — lecture
pure, qui renvoie un statut et, le cas échéant, les prérequis manquants. **`accept()` délègue
désormais à cette méthode** : il n'existe qu'une seule implémentation des règles de refus. Une
quête verrouillée, déjà active ou terminée non répétable **ne peut pas** être annoncée comme
nouvelle. Refactoring d'une méthode cœur, donc validé par les tests de quête **immédiatement**
avant de continuer.

### Lecture persistante, limitée au contenu réellement présenté
- Table `dialogue_node_reads` (migration **V24**, idempotente, clé primaire composite) +
  `DialogueReadRepository`.
- Marquage dans `openNode` uniquement. **Ouvrir un PNJ n'écrit qu'une ligne**, celle du nœud de
  départ : une branche non parcourue reste non lue, donc toujours signalée, et une branche
  nouvellement débloquée réapparaît.
- Identité par **UUID** : survit à une reconnexion, à un redémarrage, et à un changement de pseudo.

### Atteignabilité réelle
`DialogueSessionEngine#reachableNodes` : parcours en largeur **borné** depuis le nœud de départ,
suivant uniquement les choix dont les conditions **passent**, via l'évaluateur de conditions
existant (`visibleChoices` rendu public). Un choix qui ferme le dialogue n'est pas suivi.

### Le service
`NpcHintService` : particules envoyées au **seul** joueur concerné (`Player#spawnParticle`, API
publique Paper). Deux conventions distinguables (`HAPPY_VILLAGER` pour une quête, `ENCHANT` pour un
dialogue), **quête prioritaire**. Compatible Citizens parce que le signal suit l'entité dès qu'elle
porte un id RPGQuest — aucune dépendance à l'API Citizens pour cela. Le PNJ n'est **jamais**
modifié.

### Coût maîtrisé — trois mécanismes
1. **Aucune boucle par tick** : une passe toutes les `period-ticks` (1 s par défaut), et **par
   joueur connecté** — jamais un balayage des PNJ du monde.
2. **Aucun PNJ distant** : candidats pris par `getNearbyEntities` dans le rayon, même monde, ligne
   de vue optionnelle. **Aucun chunk chargé** pour l'occasion.
3. **Calcul découplé de l'affichage** : recalcul au plus toutes les `refresh-seconds`, en
   asynchrone, **un seul à la fois par joueur**, et cache invalidé **immédiatement** sur quête
   acceptée, progression, nœud lu ou changement de monde. Nettoyage à la déconnexion et à l'arrêt.

### Réglages bornés
Section `npc-hints:` (9 clés). Une valeur numérique hors plage est **corrigée** — un signal visuel
ne doit pas empêcher le serveur de démarrer. Une **particule inconnue est refusée** au démarrage,
avec un message nommant la clé : c'est une faute de frappe qu'il faut voir, pas une option à
ignorer silencieusement.

## Fichiers créés
- `config/NpcHintConfig.java`, `database/DialogueReadRepository.java`,
  `npc/hint/NpcHintService.java`, `npc/hint/NpcHintListener.java`
- Tests : `config/NpcHintConfigTest.java`, `database/DialogueReadRepositoryTest.java`
- ce rapport

## Fichiers modifiés
- `quest/progress/QuestProgressEngine.java` (extraction de `availability`, `accept` délègue)
- `dialogue/session/DialogueSessionEngine.java` (hook de lecture, `reachableNodes`,
  `visibleChoices` public)
- `database/SchemaMigrator.java` (migration V24 + `CURRENT_VERSION`)
- `config/PluginConfig.java`, `config/ConfigValidator.java`, `src/main/resources/config.yml`
- `bootstrap/RPGQuestBootstrap.java` (câblage)
- Tests : `SchemaMigratorTest`, `DialogueSessionEngineTest`, `QuestProgressEngineTest`
- Docs : `RPGQUEST_BIBLE.md`, `MANUAL_TEST_PLAN.md`, `deployment/SERVER_CHANGELOG.md`,
  `.ai/ROADMAP.md`

**Aucun contenu du propriétaire modifié, aucune progression touchée, aucun reset joueur.**
`crystal_hunt`, `first_steps`, les quêtes du Garde et `main_story` sont intacts ; le working tree
du checkout principal est identique à son état de début de session.

## Base de données / migrations
**Migration V24** : crée `dialogue_node_reads` et son index. Idempotente, appliquée au démarrage.
**Aucune ligne existante n'est lue ni modifiée** — c'est une table nouvelle, et la fonctionnalité
n'écrit jamais ailleurs.

## Configuration / données
Nouvelle section `npc-hints:` dans `config.yml`, avec valeurs par défaut. Un `config.yml` existant
**sans** cette section continue de fonctionner (les défauts s'appliquent).

## Tests automatiques

**Suite complète des trois modules verte**, `./gradlew test build` : **1486** tests plugin
(34 ignorés — limitations MockBukkit déjà documentées), **476** control-panel (1 ignoré),
**30** web-api, **0 échec, 0 erreur**.

28 nouveaux cas, choisis sur le risque :
- **`NpcHintConfigTest`** (7) : bornage dans les deux sens, particule absente remplacée,
  interrupteurs non altérés, bornage idempotent.
- **`DialogueReadRepositoryTest`** (9) : rien n'est lu par défaut, lecture **par joueur** et **par
  nœud**, idempotence, **survie à la fermeture/réouverture de la base** (l'équivalent d'un
  redémarrage), reset d'un joueur sans effet sur un autre.
- **`DialogueSessionEngineTest`** (+6) : atteignabilité réelle selon les conditions,
  **rétrécissement** quand une condition cesse de passer, choix fermant non suivi, **ouvrir un PNJ
  ne marque que le nœud affiché**, parcourir une branche la marque aussi, absence d'observateur
  inoffensive.
- **`QuestProgressEngineTest`** (+6) : disponibilité d'une quête neuve, refus si active, quête
  inconnue, **prérequis manquants nommés**, **aucune progression créée par une simple lecture**,
  `ignorePrerequisites` ne saute que les prérequis.

### Un défaut introduit par ce lot, attrapé par les tests

`SchemaMigrator.CURRENT_VERSION` était resté à **23** alors que le catalogue allait à **24** : la
version déclarée mentait sur le catalogue. C'est exactement ce que
`SchemaMigrationRunnerTest#realCatalogueTargetsTheDeclaredCurrentVersion` existe pour détecter, et
il l'a détecté. Corrigé. Les tests de migration pointent désormais la **constante du code** plutôt
qu'un littéral, pour que la prochaine migration ne fasse plus tomber quinze tests pour la même
raison et ne noie plus le vrai signal.

## Vérification sur le serveur déployé

- **JAR** : SHA-256 `1ab198d032f16cbbd6c7f47e7904ae26d0968c155b556419301cba6cbed33975`
  (1 731 370 octets), taille en ligne identique au local.
- **Un seul redémarrage** RCON — serveur revenu `ONLINE`, 0 joueur connecté.
- **RCON `plugins`** → les **4 plugins en vert** : Citizens, Multiverse-Core, RPGQuest, WorldEdit.
- **RCON `rpgquest version`** → `v0.1.0-SNAPSHOT`. Le nouveau JAR a donc chargé **sans erreur**,
  migration V24 incluse.
- **Control Panel** : **non redéployé**, ce lot ne touche que le plugin. Service actif,
  `/health` → `200`.

**Ce que cela ne prouve pas, et je ne le présente pas autrement** : le plugin charge, c'est tout.
Le **rendu visuel réel des particules**, la **cadence en charge** et la **différence entre deux
joueurs** ne sont pas vérifiés. Aucun test en jeu n'a été exécuté.

## Tests manuels à effectuer
**TC-244 (nouveau)** — protocole complet en jeu, en six étapes (A à F), **sur contenu préfixé
`tc12_` uniquement**, avec **deux joueurs** pour l'étape D qui est la plus décisive : deux états
différents devant le même PNJ. Le protocole couvre aussi les cas « aucun signal » (hors rayon,
derrière un mur, autre monde, PNJ sans contenu, fonctionnalité désactivée) et le bornage de la
configuration.

## Résultat attendu
Un joueur qui passe devant un PNJ voit immédiatement, et pour lui seul, s'il a quelque chose à y
faire — sans que le Hub se transforme en sapin de Noël, et sans qu'un autre joueur voie son état.

## Reset / retour à l'état initial
`npc-hints.enabled: false` dans `config.yml` + redémarrage : plus aucun signal, rien d'autre ne
change. La table `dialogue_node_reads` peut rester en place sans effet. Pour effacer la lecture
d'un joueur précis, c'est le reset admin « nouveau joueur » qui s'en charge — jamais la suppression
d'un contenu.

## Déploiement VeryGames
### À transférer
JAR — **fait**. SHA-256 `1ab198d032f16cbbd6c7f47e7904ae26d0968c155b556419301cba6cbed33975`.
JAR précédent sauvegardé : `rpgquest-20261005T172148Z-predeploy.jar`.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes.
### Redémarrage requis
**Oui — un seul, effectué.**
### Migration automatique
**Oui — V24**, idempotente, appliquée au démarrage. Aucune donnée existante touchée.

## Rollback
`scripts/rollback-verygames.sh --latest`. Un JAR antérieur ignore simplement
`dialogue_node_reads`. Aucun rollback du panel n'est concerné (non redéployé).

## Logs / diagnostic
Au démarrage, le service écrit soit « Signal visuel sur les PNJ actif : passe toutes les N ticks,
rayon R blocs, recalcul au plus toutes les S s », soit « Signal visuel sur les PNJ désactivé » —
de quoi vérifier d'un coup d'œil la configuration **réellement** appliquée après bornage.

## Documentation mise à jour
- `docs/RPGQUEST_BIBLE.md` : nouvelle section « Signal visuel sur les PNJ (issue #12, première
  version) » — conventions, absence de duplication des règles, ce que « lu » veut dire, les trois
  mécanismes de coût, le tableau des réglages avec leurs bornes, et ce qui est **hors** de cette
  version avec la raison technique.
- `docs/MANUAL_TEST_PLAN.md` : **TC-244** + ligne dans la table de recette.
- `docs/deployment/SERVER_CHANGELOG.md` : entrée « lot 8 », avec le SHA, la migration V24 et la
  vérification RCON.
- `.ai/ROADMAP.md` : entrée de journal du lot 8.

## Limitations / travail restant
- **Aucune validation en jeu** : c'est la limite principale. TC-244 est écrit mais n'a pas été
  exécuté.
- **« Quête prête à rendre » hors MVP**, pour la raison technique ci-dessus. À reprendre si un
  véritable état « à rendre » est introduit dans le moteur.
- **Un dialogue par PNJ** : le signal suit la convention du projet (`rpgquest:<npcId>`). Un PNJ
  dont le dialogue serait atteint autrement n'est pas couvert.
- **Pas de halo ni d'icône** au-dessus du PNJ : les pistes UX du ticket évoquaient ces options, mais
  elles demandent un affichage serveur supplémentaire (entité ou texture) — hors première version.
- **Atteignabilité calculée à l'instant du recalcul** : une condition qui dépend de l'inventaire
  peut donc être évaluée jusqu'à `refresh-seconds` en retard. C'est le prix assumé du cache, et
  c'est configurable.
- **Ligne de vue via `hasLineOfSight`** : suffisant et peu coûteux, mais ce n'est pas une
  simulation d'occlusion exacte.
- **Pas d'extension multi-Hub explicite** : rien ne s'y oppose (le service est par joueur et par
  monde), mais aucun test ne le couvre encore.

## Prochaine étape suggérée
1. Dérouler **TC-244** en jeu, sur contenu `tc12_`, avec deux joueurs pour l'étape D.
2. Dérouler **TC-243** (#194), toujours en attente.
3. **Attendre le choix du prochain ticket** par le propriétaire. #13 est préparé mais **ne doit pas
   être commencé** : #12 reste le seul ticket actif.
