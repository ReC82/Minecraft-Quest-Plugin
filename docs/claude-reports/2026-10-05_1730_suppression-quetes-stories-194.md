# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-05
* Heure : 17:30 (locale machine)
* Sujet : #194 — supprimer des quêtes et des stories depuis le panel, avec aperçu des conséquences et confirmation
* Statut : DONE
* Branche Git : `feature/169-special-mobs-boss` (worktree dédié `/srv/rpgquest/worktree-169`)
* Commit actuel si disponible : `44c1446`
* Début de la tâche : 2026-10-05 16:56:42 (heure locale réelle, première action)
* Fin de la tâche : 2026-10-05 17:50:10 (heure locale réelle)
* Durée totale : 00:53:28

## Demande

« Ajoute la suppression des quêtes et stories dans le panel avec aperçu des conséquences et
confirmation explicite. Traite les liens PNJ, dialogues, stories et prérequis sans supprimer les
contenus associés ni effacer les progressions des joueurs. Bloque la suppression si des références
ne peuvent pas être traitées proprement. Teste uniquement avec des contenus de test, sans supprimer
mes quêtes ou stories existantes. »

Le ticket #194 ajoute : permission dédiée, confirmation manuelle sur nom/ID, traitement explicite
**source et runtime** pour éviter la réapparition après rafraîchissement / redémarrage /
déploiement, sauvegarde et rollback en cas d'échec partiel, politique documentée sur la progression
joueur, et acceptance « annulation sans effet ».

## Analyse

### Cartographie des références, vérifiée dans le code et non supposée

Avant d'écrire quoi que ce soit, j'ai cherché **qui référence réellement une quête**. Le résultat
le moins évident change la conception :

| Source possible | Réalité vérifiée |
|---|---|
| Prérequis d'autres quêtes | oui — dans `quests/*.yml`, et **facultatifs** : retirables sans casser la quête |
| Chaîne d'une story | oui — dans `stories/*.yml`, retirable **sauf** si c'était la seule quête |
| Dialogues | oui — conditions `QUEST_STATE`, actions `START_QUEST` / `ADVANCE_QUEST` / `TURN_IN_QUEST` |
| **PNJ** | **non.** `NpcDefinition` ne porte que `dialogueId`. Le lien quête → PNJ vit dans le champ `giver:` **de la quête**, écrit par `QuestGiverStore` dans le fichier de quête. |

Conséquence directe : **supprimer une quête n'orpheline aucun PNJ** — son lien part avec elle. Ce
qui couple réellement un PNJ à une quête, c'est **son dialogue**, et c'est là qu'il faut bloquer.
Si j'avais supposé qu'un PNJ pointe vers une quête, j'aurais écrit un nettoyage inutile et manqué
le vrai cas.

### Pourquoi les dialogues bloquent au lieu d'être nettoyés

Retirer une action `START_QUEST` laisserait un choix de dialogue **sans effet** : le joueur clique,
rien ne se passe. Retirer le choix entier **change le dialogue**. Les deux sont des décisions
éditoriales, pas un nettoyage mécanique. Le service bloque donc, en citant le fichier et les
**numéros de ligne**, et laisse trancher un humain. C'est exactement ce que demande le ticket :
« proposer sa correction ou bloquer avec explication avant toute mutation ; aucun lien orphelin ».

### Pourquoi le runtime devait être traité, pas seulement décrit

Supprimer un fichier de la source ne retire rien du serveur : le fichier déployé reste chargé, et
le contenu réapparaît au prochain rafraîchissement du catalogue. C'était le symptôme rapporté. Une
nouvelle action agent supprime donc aussi la copie serveur, puis **relit** les définitions — sans
redémarrage.

Et un piège qui aurait annulé tout le travail : les **9 quêtes et la story d'exemple** du JAR sont
**recréées au démarrage si le fichier manque** (`YamlQuestEngine#BUNDLED_EXAMPLES`,
`StoryRegistry#BUNDLED_EXAMPLES`). Supprimer `first_steps` partout la ferait revenir au prochain
redémarrage. L'aperçu le dit, avec la conséquence exacte.

## Travail effectué

### Panel — aperçu, confirmation, nettoyage
- `ContentDeletionAnalyzer` : calcule un **plan** complet avant toute mutation — ce qui sera
  nettoyé (avec le **YAML exact** qui sera écrit), ce qui bloque, ce qui ne sera pas touché. Ce qui
  est montré est donc exactement ce qui sera fait.
- `ContentDeletionExecutor` : applique le plan dans un ordre choisi pour que le contenu visé reste
  récupérable — tout sauvegarder, réécrire les références (échec ⇒ **restauration** et arrêt
  **avant** toute suppression), supprimer en **dernier**.
- `ContentDeletionPages` : aperçu, confirmation par **identifiant retapé**, page de résultat citant
  le chemin de sauvegarde. Tant qu'un blocage subsiste, **aucun formulaire de confirmation n'est
  rendu** : il n'y a rien à cliquer.
- `ContentWorkspace` : `backup`, `delete` (hash obligatoire) et `restore`.
- Routes `/quests/delete` et `/stories/delete` (GET = aperçu, POST = application), boutons
  « Supprimer… » sur les fiches des deux catalogues.
- **Permission dédiée `CONTENT_DELETE`** : `OWNER` et `ADMIN` oui, **`CONTENT_EDITOR` non**.

### Plugin — suppression de la copie serveur
- `ContentDefinitionDeleter` : retrouve le fichier par l'**identifiant déclaré dedans** — jamais
  par un nom de fichier deviné, car le nom n'a pas à correspondre à l'id et supprimer le mauvais
  fichier serait irréparable. Sauvegarde horodatée, puis suppression.
- Action `content.definition.delete` (types fermés `quests`/`stories`), puis **relecture** des
  définitions : les quêtes sur le thread principal, car recharger rebranche des écouteurs.
- `StoryService#reloadDefinitions()` ajouté pour la même raison côté stories.

### Deux corrections décidées en cours de route
1. **Emplacement des sauvegardes.** Elles allaient d'abord sous
   `src/main/resources/.plugadmin-backups/`. C'était faux pour deux raisons : elles seraient
   entrées dans le **JAR construit**, et elles auraient **sali le working tree Git**, ce qui bloque
   `deploy-verygames.sh` (il refuse un arbre non propre). Déplacées dans
   `/var/lib/plugadmin/content-backups/`, à côté de la base du panel.
2. **Ne pas présenter une absence de donnée comme une donnée.** Sans relevé du serveur, dire « le
   serveur ne connaît pas ce contenu » serait **faux** : on ne lui a jamais demandé. L'aperçu
   distingue maintenant les trois cas — connu, inconnu, et *jamais relevé* — ce dernier avec la
   conséquence écrite (le contenu réapparaîtra) et le geste qui lève le doute.

## Fichiers créés
- Panel : `content/DeletionPlan.java`, `content/ContentDeletionAnalyzer.java`,
  `content/ContentDeletionExecutor.java`, `web/ContentDeletionPages.java`
- Plugin : `content/ContentDefinitionDeleter.java`
- Tests : `ContentDeletionAnalyzerTest`, `ContentDeletionExecutorTest`, `ContentDeletionPagesTest`,
  `BundledExamplesDriftTest`, `ContentDefinitionDeleterTest`
- ce rapport

## Fichiers modifiés
- Panel : `ContentWorkspace.java`, `Permission.java`, `Role.java`, `AgentActionCatalog.java`,
  `AgentPages.java`, `PanelApp.java`
- Plugin : `AgentActions.java`, `BukkitAgentActions.java`, `AgentActionType.java`,
  `AgentActionExecutor.java`, `StoryService.java`
- Tests : `AgentActionExecutorTest.java`, `StubAgentActions.java`
- Docs : `RPGQUEST_BIBLE.md`, `MANUAL_TEST_PLAN.md`, `deployment/SERVER_CHANGELOG.md`,
  `.ai/ROADMAP.md`

**Aucune quête ni story du propriétaire supprimée ou modifiée.** `crystal_hunt`, les fichiers
Lily/Jeff, les configurations, les secrets et la progression sont intacts ; le working tree du
checkout principal est identique à son état de début de session.

## Base de données / migrations
Aucune migration, et **aucune lecture ni écriture de progression joueur** par cette
fonctionnalité — c'est le point de conception, pas un effet de bord.

## Configuration / données
Nouveau dossier de sauvegardes `/var/lib/plugadmin/content-backups/<horodatage>/` (panel) et
`plugins/RPGQuest/content-backups/<horodatage>/` (serveur). Aucune nouvelle clé de configuration :
l'emplacement est dérivé de `RPGQUEST_PANEL_DB` et du dossier de données du plugin.

## Tests automatiques

**Suite complète des trois modules verte**, `./gradlew test build` en un seul passage :
**1458** tests plugin (34 ignorés — limitations MockBukkit déjà documentées), **476**
control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

Nouveaux tests, organisés par risque :
- **`ContentDeletionAnalyzerTest`** (17 cas) — nettoyage des prérequis et des chaînes de story ;
  **blocages** : story qui perdrait sa dernière quête, dialogue référençant (avec vérification que
  les **deux** lignes, condition et action, sont citées), fichier référençant non analysable,
  espace de travail en lecture seule, absence de dossier de sauvegarde ; un dialogue qui référence
  une *autre* quête ne bloque pas ; politiques annoncées (progression joueur, runtime, exemple
  embarqué) ; et le cas « aucun relevé » qui ne doit pas affirmer une absence jamais vérifiée.
- **`ContentDeletionExecutorTest`** (6 cas) — ordre des écritures, sauvegardes **hors** des
  dossiers de contenu (vérifié en relisant le catalogue source après coup), plan bloqué qui n'écrit
  **rien** et ne crée même pas de sauvegarde, **conflit de version** entre l'aperçu et la
  confirmation avec **rollback** des réécritures déjà faites, restauration fidèle au bit près.
- **`ContentDeletionPagesTest`** (9 cas) — seule la saisie exacte confirme (« oui », vide, `null`,
  autre identifiant : refusés), une confirmation erronée est un **no-op** vérifié sur le disque,
  aucun formulaire de confirmation si blocage, permission dédiée **refusée au rôle éditeur**.
- **`ContentDefinitionDeleterTest`** (9 cas, plugin) — fichier retrouvé par l'**id déclaré** même
  quand le nom de fichier n'a aucun rapport, namespace optionnel des deux côtés, sauvegarde avant
  suppression et **hors** du dossier de contenu, fichier illisible jamais supprimé « au cas où »,
  types fermés (`dialogues`, `npcs`, `..`, `/etc`, `""`, `null` tous refusés).
- **`BundledExamplesDriftTest`** (3 cas) — confronte la copie des exemples embarqués du panel aux
  **fichiers Java réels du plugin** dans le dépôt. Le panel ne peut pas dépendre du plugin, donc il
  porte une copie ; une copie dérive toujours, sauf si un test la confronte à l'original.
- **`AgentActionExecutorTest`** (+3) — types refusés, identifiant obligatoire, et paramètres
  validés réellement transmis.

**Trois défauts de mes propres tests ont été trouvés et corrigés à la bonne couche** : deux
fixtures écrites à la main que `SourceCatalog` marquait « non analysables » (corrigées en passant
par les sérialiseurs réels, sinon le test vérifiait autre chose que ce qu'il annonçait), et une
assertion qui cherchait une phrase non échappée alors que `Http.esc` fait correctement son travail.

## Vérification sur les services déployés (sans rien supprimer)

- **Control Panel AWS** : service actif, `/health` → `200`.
- **Serveur DEV** : `ONLINE`, heartbeat de l'agent vu à **1 seconde** au moment du contrôle.
- **L'action `content.definition.delete` est bien dans le JAR livré** : sonde sur un identifiant
  **volontairement inexistant** → « Aucun fichier de *quests* ne déclare l'identifiant
  `tc243_inexistant_sonde` sur le serveur : rien à supprimer ». Elle est donc reconnue (et non
  rejetée comme type inconnu) et **n'a rien touché**.
- **13** boutons « Supprimer… » sur `/quests`, **3** sur `/stories`.
- **Protection vérifiée sur vos données réelles** : l'aperçu de `first_steps` **bloque** parce que
  le dialogue `guard` la référence **aux lignes 12, 16 et 21**, et **aucun formulaire de
  confirmation n'est rendu**. Il est donc impossible de supprimer `first_steps` par accident.
  Requêtes **GET uniquement** : rien n'a été supprimé.
- Compte panel de vérification créé puis **supprimé** avec ses identifiants ; `owner` et `TESTER`
  intacts. Aucun fichier `tc243_` créé par moi.

**Non couvert automatiquement** : le parcours navigateur complet (clics réels, saisie de la
confirmation) et l'exécution d'une **vraie** suppression de bout en bout — c'est l'objet de TC-243,
à dérouler sur du contenu `tc243_`.

## Tests manuels à effectuer
**TC-243 (nouveau)** — parcours complet, **sur du contenu de test préfixé `tc243_` uniquement**.
Le test dit explicitement de **ne pas** le dérouler sur `crystal_hunt`, `first_steps`, les quêtes
du Garde ou `main_story`. Il couvre : aperçu, confirmation erronée sans effet, nettoyage sans
cascade, les deux blocages, suppression d'une story, état côté serveur, exemple embarqué, et
annulation sans effet.

## Résultat attendu
Supprimer un doublon depuis le panel, en voyant d'abord exactement ce que ça va changer, sans
jamais détruire un PNJ, un dialogue, une autre quête, ni la progression d'un joueur — et sans que
le contenu revienne au rafraîchissement suivant.

## Reset / retour à l'état initial
Restaurer un contenu supprimé par erreur : recopier les fichiers depuis
`/var/lib/plugadmin/content-backups/<horodatage>/` (source) et
`plugins/RPGQuest/content-backups/<horodatage>/` (serveur). La page de résultat cite toujours le
chemin exact, pour qu'une restauration ne se devine pas.

## Déploiement VeryGames
### À transférer
JAR — **fait**. SHA-256 `d8991636879071c1bdb741bce8ece084407bcaf19697a36d98e3980fbfac3d59`
(1 705 052 octets). Nécessaire : sans lui, l'action `content.definition.delete` n'existe pas côté
serveur et le contenu supprimé de la source resterait chargé. JAR précédent sauvegardé :
`rpgquest-20261005T155027Z-predeploy.jar`.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes.
### Redémarrage requis
Oui — **un seul, effectué**. Serveur revenu `ONLINE`, 0 joueur connecté au moment de l'opération.
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` ; `scripts/plugadmin/rollback.sh app`. Pour un contenu :
voir « Reset » ci-dessus.

## Logs / diagnostic
Chaque suppression est inscrite au journal d'audit (`quests.content.delete` /
`stories.content.delete`) avec son identifiant de requête, et une ligne de log structurée
(`event=content_delete`) indique le type, l'identifiant, le succès et si la suppression serveur a
été enfilée. L'action `content.definition.delete` apparaît dans le journal d'actions du panel avec
le nom du fichier supprimé et sa sauvegarde.

## Documentation mise à jour
- `docs/RPGQUEST_BIBLE.md` : nouvelle sous-section « Supprimer une quête ou une story (issue
  #194) » — les trois règles de conduite, le tableau des références et de leur traitement, la
  confirmation, source/runtime, les exemples embarqués, et la sauvegarde avec l'ordre d'exécution.
- `docs/MANUAL_TEST_PLAN.md` : **TC-243** + ligne dans la table de recette.
- `docs/deployment/SERVER_CHANGELOG.md` : entrée « lot 7 ».
- `.ai/ROADMAP.md` : entrée de journal du lot 7.

## Limitations / travail restant
- **Les dialogues ne sont jamais nettoyés automatiquement** : c'est un choix, pas un manque. Une
  correction guidée (« retirer ce choix » / « réaffecter cette action ») serait un lot à part, avec
  son propre aperçu — et elle ne doit pas se déclencher au milieu d'une suppression.
- **Un seul contenu à la fois** : pas de suppression en lot. Le ticket ne la demande pas, et un
  aperçu de conséquences croisées sur plusieurs suppressions serait un sujet en soi.
- **Détection des références de dialogue par analyse du texte YAML** : le modèle de dialogue du
  panel est volontairement simplifié et ne porte ni conditions ni actions. C'est suffisant ici
  **parce qu'on bloque** au lieu de réécrire — mais ce ne le serait pas pour un nettoyage
  automatique.
- **Liste des exemples embarqués dupliquée** dans le panel (isolation des modules). Le risque de
  dérive est couvert par un test de cohérence du dépôt, pas par une dépendance.
- **Pas de corbeille dans l'interface** : les sauvegardes existent sur disque et leur chemin est
  affiché, mais il n'y a pas d'écran « restaurer ». Candidat naturel si le besoin se confirme.
- La suppression côté serveur est **asynchrone** : la page de résultat le dit et renvoie au journal
  d'actions, plutôt que de laisser croire que tout est fini.

## Prochaine étape suggérée
1. Dérouler **TC-243** au navigateur sur du contenu `tc243_`.
2. Lot suivant : **#108/#109** — recherche et filtres d'export, puis import accessible avec
   validation, aperçu/diff et choix de collision.
3. Toujours en attente : la décision « Wild présélectionné par défaut » (#172).
