# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-11
* Heure : 20:15
* Sujet : Issue #197 — refonte de l'identité et de l'apparence des PNJ dans PlugAdmin : un seul nom de personnage, apparence décrite et vérifiable
* Statut : **PARTIAL** — code, documentation et commits locaux terminés ; la suite de tests complète a été **tuée par le harness faute de mémoire système** avant d'écrire le moindre résultat, `./gradlew build` n'a pas tourné, donc **aucun push, aucune PR, aucun merge, aucun déploiement**
* Branche Git : `feature/197-npc-identity-appearance` (créée depuis la ligne d'intégration `feature/238-guard-dialogue-ux` @ `e969f5d`)
* Commit actuel si disponible : `f67430c`
* Début de la tâche : 2026-10-11 19:28:54
* Fin de la tâche : 2026-10-11 20:35:41
* Durée totale : 01:06:47

## Demande

Implémenter complètement l'issue #197, pas seulement la documenter.

Objectif central, cité : « un administrateur ne doit plus avoir à comprendre la différence entre
nom RPGQuest et nom Citizens pour renommer un personnage. Il choisit un prénom et une apparence ;
PlugAdmin gère la cohérence technique. »

Demandes détaillées :

1. **Un seul champ « Nom du PNJ »**, qui coordonne la définition RPGQuest, le nom Citizens affiché
   au-dessus de l'entité, et le nom utilisé comme locuteur par défaut des dialogues. Diagnostic
   clair si les deux noms ne sont pas synchronisés, « sans laisser un succès partiel passer pour un
   succès complet ».
2. **RPGQuest devient la référence de l'apparence** : source de skin persistée pour les PNJ de type
   joueur, type d'entité et propriétés visuelles pour les autres.
3. **Catalogue en tuiles** : prénom, identifiant technique en secondaire, état de liaison Citizens,
   aperçu réel du skin, état d'apparence (configurée / à configurer / en cours / erreur / non
   applicable) et filtre « Apparence à configurer ».
4. **Fiche réorganisée en six sections** (Identité, Apparence, Emplacement, Comportement, Contenu,
   Administration), confortable sur mobile, sans longs paragraphes techniques.
5. **Parcours guidé** de renommage et de skin, avec avertissement **avant** la soumission.
6. Tester les cas Elias, Libraire, Help/Tania, Lily, sans skin, sans définition, non joueur, absent
   ou chunk déchargé — **sans renommer ni appliquer de skin à ces personnages**, en utilisant des
   fixtures et des mocks pour les tests destructifs.
7. Ne jamais modifier les identifiants techniques, ne jamais écraser une apparence inconnue, ne pas
   toucher aux fichiers de contenu non suivis.
8. Développer, tester, commiter, créer une PR, merger dans la ligne d'intégration réellement
   utilisée par DEV, déployer PlugAdmin DEV. Si un JAR est nécessaire : TLS sécurisé par défaut,
   autorisation explicite demandée si le certificat bloque encore, **aucun contournement TLS
   conservé**. Puis s'arrêter.

## Analyse

### Ce qui existait avant

Le découpage en place était **techniquement correct et inutilisable en pratique**.

* `npc.definition.update` changeait le nom de la définition RPGQuest (`npcs/<id>.yml`).
* `npc.citizens.rename` changeait le nom affiché en jeu par Citizens.
* Rien ne reliait les deux. La fiche PNJ exposait **deux champs de nom concurrents**, dans deux
  formulaires différents (« Modifier » et « Nom en jeu & apparence »), accompagnés de paragraphes
  expliquant la distinction.

Conséquence constatée en production : le Guide a été renommé « Elias » dans sa définition et a
continué d'afficher « Guide » au-dessus de la tête. La tentative de corriger la couche Citizens a
échoué sur `FAILED / SKIN_SOURCE_UNKNOWN`, un code sans explication lisible.

### Pourquoi ce refus existait, et pourquoi il était justifié

Vérifié dans le code avant d'y toucher : `SkinPreservationPlanner` refuse de renommer un PNJ de
type `PLAYER` dont RPGQuest ne connaît pas la source d'apparence. La raison est réelle et
documentée dans sa javadoc : **l'artefact `citizensapi`, seule dépendance Citizens de ce projet,
n'expose aucune API de skin** — ni `SkinTrait`, ni clé de skin dans l'énumération publique
`NPC.Metadata` (vérifié sur `citizensapi:2.0.43`). Il est donc impossible de *lire* le skin
appliqué à un PNJ.

Or un PNJ de type `PLAYER` sans skin explicite **dérive son visage de son nom**. Le renommer change
donc aussi son apparence, et comme son origine est inconnue, elle ne pourrait pas être restituée.
Le refus est le bon comportement. Le défaut n'était pas le refus : c'était qu'il arrivait **après le
clic**, sous forme de code technique.

### Le vrai blocage découvert

La source de skin **était déjà persistée** (table `npc_citizens_skins`, migration V27,
`NpcSkinSourceRepository`). Mais **ni cette source ni le type d'entité n'étaient exposés au panel** :

* `AgentActions.CitizensNpcSummary` n'avait aucun champ de skin ni de type d'entité ;
* `CitizensNpc` ne portait pas le type d'entité ;
* `npc.list` n'en parlait pas davantage.

Le panel ne pouvait donc **afficher aucun état d'apparence**, ni avertir avant la soumission. C'est
la cause racine de l'expérience subie : toute l'information nécessaire existait côté serveur et
s'arrêtait avant le relevé. C'est exactement le défaut de l'issue #239 (le bâtiment posé) et celui
de la localisation des PNJ avant lui — une clé absente d'une sérialisation écrite à la main ne
produit aucune erreur, elle rend seulement l'écran muet.

### Décisions de conception

**1. Citizens d'abord, définition ensuite.** Dans l'action coordonnée, le renommage Citizens est
exécuté en premier parce que c'est le seul des deux qui peut légitimement refuser. Un refus laisse
donc **zéro couche modifiée**. L'ordre inverse reproduirait exactement le défaut d'origine : un nom
de catalogue déjà changé pour un personnage qui s'appelle encore autrement en jeu.

**2. Le verdict est rendu sur une relecture, pas sur un code de retour.** Les deux couches sont
relues après l'opération — le nom Citizens dans le registre, le nom de la définition dans le moteur
rechargé. Une écriture qui « réussit » sans que le nom ait changé reste un échec. La décision est
isolée dans une classe pure, `NpcRenameVerdict`, parce que c'est la partie où l'on peut mentir.

**3. Les `speaker` de dialogue ne sont jamais réécrits.** Un locuteur est une donnée d'auteur,
parfois partagée entre plusieurs personnages, parfois volontairement différente du nom du PNJ. Le
renommage **liste** les dialogues qui nomment encore l'ancien nom ; il ne les remplace pas. C'est la
règle déjà posée par l'issue : « conserver les surcharges explicites ».

**4. « Source inconnue » n'est pas « sans skin ».** C'est la distinction qui a coûté une session de
diagnostic en jeu, et elle est désormais **tenue par les tests** : aucun écran ne peut écrire « sans
skin » pour un PNJ dont seule la source manque. De même, un type d'entité absent veut dire
« inconnu » et jamais « pas un joueur ».

**5. Le filtre est plus étroit que l'état.** « Apparence à configurer » ne liste que les PNJ dont la
*source* est inconnue, pas ceux dont le *type* est inconnu. Raison : un relevé Citizens antérieur à
ce lot ne porte aucun type, donc inclure ce cas ferait apparaître **tous** les PNJ dans la liste de
travail juste après le déploiement du panel. L'état reste visible sur la tuile ; seule la liste de
travail reste honnête.

**6. Lecture stricte du type d'entité.** Le trait `MobType` est lu avec `getTraitNullable`, pas
`getOrAddTrait`. Dresser l'inventaire des PNJ ne doit rien écrire : attacher un trait à chaque PNJ
au seul motif qu'on le consulte modifierait le registre Citizens à chaque relevé du panel.

**7. Les deux lectures de base avant le thread principal.** `citizensRoster()` consomme les liaisons
**et** les sources d'apparence avant d'entrer sur le thread principal. Attendre un `Future` dans ce
bloc gèlerait le serveur — leçon directement reprise de l'issue #156, où j'avais introduit ce défaut.

**8. Pas d'aperçu image du skin — décision assumée, pas un oubli.** Voir « Limitations ».

## Travail effectué

### Plugin (exige un nouveau JAR)

1. **`CitizensNpc`** porte le type d'entité, avec deux prédicats séparés : `playerType()` et
   `entityTypeKnown()`. La paire est ce qui empêche de conclure « villageois » d'un type absent.
2. **`CitizensNpcBridge`** lit le trait public `MobType` sans l'ajouter, et répond `null` plutôt que
   de supposer en cas de trait absent ou d'exception.
3. **`AgentActions.CitizensNpcSummary`** gagne `entityType`, `skinSourceKind`, `skinSourceValue`,
   avec la javadoc qui dit explicitement ce que `null` signifie.
4. **`NpcSkinSourceRepository.loadAll()`** : une seule lecture de `npc_citizens_skins`, indexée par
   UUID Citizens, au lieu d'une requête par PNJ.
5. **`BukkitAgentActions.citizensRoster()`** croise les trois sources sans accès disque sur le
   thread principal.
6. **`AgentActionExecutor`** sérialise les trois nouvelles clés, avec le commentaire qui rappelle
   pourquoi l'oubli serait silencieux.
7. **Nouvelle action `npc.identity.rename`** (`AgentActionType.NPC_IDENTITY_RENAME`) :
   validation de forme dans l'exécuteur, orchestration dans `BukkitAgentActions.npcIdentityRename`.
8. **`NpcRenameVerdict`** (nouveau, pur) : six combinaisons de couches → six codes, dont trois
   succès et trois échecs.
9. **`writeDisplayName`** écrit la définition **sans emprunter** `npcDefinitionUpdate` — ce chemin
   reconstruit la définition depuis des champs de formulaire et **perdait la description**. Un
   renommage ne doit rien effacer d'autre que l'ancien nom. (Défaut préexistant, contourné ici pour
   ce chemin ; `npc.definition.update` lui-même n'a pas été modifié — hors périmètre.)
10. **`dialogueSpeakerNote`** parcourt les dialogues chargés et liste ceux qui nomment encore
    l'ancien locuteur, sans rien écrire.

### Panel (déploiement PlugAdmin séparé)

11. **`NpcAppearance`** (nouveau, pur) : cinq états, sept raisons, libellé court, explication
    complète en français, classe de pastille, catégorie de filtre, et `blocksRename()` aligné sur
    les deux refus de `SkinPreservationPlanner`.
12. **`AgentActionCatalog`** : entrée `npc.identity.rename` exigeant `NPC_WRITE` **et**
    `NPC_BIND_WRITE` (nouvelle surcharge `addContentWrite(type, List<Permission>, …)` — distincte de
    `addOrchestratedWrite`, qui marquerait l'action comme sensible alors qu'un renommage est
    réversible), plus sa validation de paramètres.
13. **`AgentPages`** :
    * tuile : prénom d'abord, identifiant technique en secondaire, pastille d'apparence, catégorie
      de filtre `skin-todo` ;
    * filtre « Apparence à configurer » dans la barre de la page ;
    * fiche en **six sections** ; les explications longues dans un `<details>` natif (aucun JS
      inline, CSP respectée) ;
    * section Identité : **un seul** champ de nom, la divergence signalée comme anomalie quand elle
      existe, et le formulaire de renommage sur place ;
    * section Apparence : état, source exacte nommée, action « Définir / Changer l'apparence » ;
    * **avertissement avant la soumission** quand l'apparence bloque le renommage, avec le bouton
      qui le lève juste à côté et la garantie que rien n'a bougé ;
    * l'ancien formulaire « Nom en jeu & apparence » est **supprimé** (c'était le doublon visé) ;
    * le champ nom de « Modifier » devient **lecture seule**, sa valeur restant soumise en champ
      caché pour qu'une édition de dialogue ou de rôle n'efface pas le nom ;
    * les PNJ Citizens **sans définition** (cas « Help ») portent aussi leur état d'apparence, et la
      fiche dit pourquoi l'action n'y est pas proposée ;
    * helpers `appearanceOf`, `lastAppearanceAttempt`, `surveyInstant`.
14. **`MinecraftNames`** : `PLAYER`, `WANDERING_TRADER`, `ARMOR_STAND` ajoutés (`VILLAGER` existait
    déjà).

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/npc/NpcRenameVerdict.java`
* `src/test/java/com/lodygames/rpgquest/npc/NpcRenameVerdictTest.java`
* `src/test/java/com/lodygames/rpgquest/npc/CitizensNpcTest.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/NpcAppearance.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/npc/NpcAppearanceTest.java`

## Fichiers modifiés

Plugin :

* `src/main/java/com/lodygames/rpgquest/npc/CitizensNpc.java`
* `src/main/java/com/lodygames/rpgquest/npc/CitizensNpcBridge.java`
* `src/main/java/com/lodygames/rpgquest/database/NpcSkinSourceRepository.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/AgentActionExecutorTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/NpcCitizensPayloadTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java`
* `src/test/java/com/lodygames/rpgquest/database/NpcSkinSourceRepositoryTest.java`

Panel :

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/MinecraftNames.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalogTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcsCatalogTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/MinecraftNamesTest.java`

Documentation :

* `docs/RPGQUEST_BIBLE.md`
* `docs/current_state.md`
* `docs/deployment/SERVER_CHANGELOG.md`
* `docs-site/npc.html`
* `control-panel/src/main/resources/docs/pnj-citizens.md`
* `control-panel/src/main/resources/docs/pnj-depannage.md`

## Base de données / migrations

**Aucune migration.** La table `npc_citizens_skins` existe déjà (migration **V27**, livrée avec
l'issue #165). Ce lot ne fait que la **lire**, par `loadAll()`, pour construire le catalogue.

Aucune écriture nouvelle en base n'est introduite : `npc.identity.rename` écrit un fichier YAML de
définition et appelle Citizens, rien d'autre. La source d'apparence continue d'être écrite
exclusivement par `npc.citizens.skin`, inchangée.

## Configuration / données

Aucun changement de `config.yml`, aucune variable d'environnement, aucun fichier de configuration
nouveau. Aucun contenu (quête, story, dialogue) publié ni modifié.

Les sept fichiers de contenu non suivis du propriétaire et `src/main/resources/quests/crystal_hunt.yml`
sont **restés intacts** et ne sont dans aucun commit — vérifié avant chaque commit par un `git add`
nommant explicitement chaque fichier (jamais `git add -A`).

## Tests automatiques

### Ce qui a été réellement exécuté, et vert

Chaque ligne ci-dessous correspond à une exécution Gradle observée dans cette session, avec son
verdict. Le filtre `--tests` de Gradle vide le dossier de résultats à chaque passage : les XML des
exécutions antérieures ne sont donc plus sur le disque, seul le verdict de chaque run fait preuve.

| Commande | Verdict observé |
|---|---|
| `:control-panel:test --tests '*NpcAppearanceTest*'` | BUILD SUCCESSFUL |
| `:control-panel:test --tests '*NpcsCatalogTest*' --tests '*NpcAppearanceTest*'` | BUILD SUCCESSFUL (63 tests dans `NpcsCatalogTest`) |
| `:control-panel:test --tests '*DiagnosticHelp*' --tests '*Docs*'` | BUILD SUCCESSFUL |
| `:test --tests '*AgentActionExecutorTest*' --tests '*NpcCitizensPayloadTest*'` | BUILD SUCCESSFUL |
| `:test --tests '*NpcRenameVerdictTest*'` | BUILD SUCCESSFUL |
| `:test --tests '*CitizensNpcTest*'` | BUILD SUCCESSFUL |
| `:test --tests '*NpcSkinSourceRepositoryTest*'` | BUILD SUCCESSFUL |
| `compileJava` + `:control-panel:compileJava` | sans erreur (warnings de dépréciation préexistants seulement) |

Contenu des tests ajoutés :

* **`NpcAppearanceTest`** (nouveau, 13 tests) — les cinq états, le cas d'Elias, l'interdiction
  d'écrire « sans skin » quand seule la source manque, le PNJ non joueur, le type inconnu, la chaîne
  littérale `"null"` du relevé, la priorité d'un échec sur une source enregistrée, et une boucle qui
  vérifie qu'**aucune** combinaison ne produit un libellé ou une explication vide.
* **`NpcRenameVerdictTest`** (nouveau, 11 tests) — la panne partielle demandée par l'issue : chaque
  combinaison de couches, avec une boucle finale qui vérifie sur **les neuf combinaisons** que seuls
  « les deux à jour » et « une seule couche existe » sont annoncés comme des succès.
* **`CitizensNpcTest`** (nouveau, 5 tests) — « type absent » ≠ « pas un joueur », et le type survit
  à `withBehaviour`.
* **`NpcsCatalogTest`** (+11 tests) — champ de nom unique, absence de l'ancien formulaire en double,
  nom en lecture seule dans « Modifier » (assertion **bornée** au formulaire d'édition : le
  formulaire de création garde légitimement sa saisie stylée), avertissement rendu **avant** le
  formulaire (comparaison d'index, pas simple présence), pastille et catégorie de filtre sur la
  tuile, PNJ non joueur, type inconnu, PNJ sans définition, et refus d'un nom vide ou trop long sans
  rien enfiler.
* **`NpcCitizensPayloadTest`** (+3 assertions) — les trois nouvelles clés traversent le payload JSON.
  Ce fichier portait déjà un test **structurel par réflexion** qui exige une clé pour chaque
  composant du record : il couvre donc aussi mes trois champs, et aurait échoué si je les avais
  oubliés dans la sérialisation.
* **`AgentActionExecutorTest`** (+2 tests) — validation de l'action coordonnée, et vérification
  qu'elle n'emprunte **pas** le chemin limité à Citizens.
* **`NpcSkinSourceRepositoryTest`** (+3 tests) — `loadAll()`, table vide, et cohérence entre
  `loadAll()` et `find()` (deux lectures divergentes afficheraient « configurée » sur un PNJ dont le
  renommage serait refusé).

### Ce qui n'est PAS prouvé — à lire avant de déployer

1. **La suite complète n'a pas tourné.** Lancée à 20 h 12 depuis le worktree propre
   `/srv/rpgquest/worktree-nuit` sur le commit `f67430c`, elle a été **tuée par le harness Claude
   Code à 20 h 32**, parce que la machine était en manque critique de mémoire (3,8 Go, 2,7 Go
   utilisés). Ce n'est **pas** un échec de test : la tâche `:test` a été interrompue **avant d'écrire
   le moindre rapport de classe** — vérifié, zéro XML postérieur à 20 h 10 dans le worktree. Il n'y a
   donc aucun résultat partiel à interpréter, dans un sens ni dans l'autre.
2. **`./gradlew build` n'a pas été exécuté.**
3. **Trois tests que j'ai ajoutés n'ont jamais été exécutés** : les deux de
   `AgentActionCatalogTest` (validation et permissions de `npc.identity.rename`) et celui de
   `MinecraftNamesTest`. Ils **compilent** — la passe `:control-panel:test` de 20 h 09 a compilé tout
   le source set de test — mais aucune exécution ne les a couverts. À faire tourner avant le push.
4. **Aucune vérification de non-régression hors du périmètre PNJ.** Les suites exécutées ci-dessus
   couvrent ce que j'ai touché ; `CitizensNpc` et `CitizensNpcSummary` sont construits ailleurs dans
   le code (liaison, spawn, provisionnement), et seule la suite complète peut le prouver.

Conséquence respectée : **aucun push, aucune PR, aucun merge, aucun déploiement.** `CLAUDE.md`
n'autorise le push vers la branche de travail qu'une fois `./gradlew test` **et** `./gradlew build`
verts. Les trois commits restent locaux sur `feature/197-npc-identity-appearance`.

## Tests manuels à effectuer

Ces tests exigent un vrai client Minecraft et/ou le panel déployé : `PENDING MANUAL VALIDATION`.

* **TC-197-A — Elias, le cas d'origine.** Page PNJ → « Citizens » → ouvrir `guide`. Vérifier :
  pastille « apparence à configurer » sur la tuile, six sections sur la fiche, **un seul** champ
  « Nom du PNJ », et l'avertissement « Le renommage sera refusé en l'état » **au-dessus** du
  formulaire avec le bouton « Définir une apparence ». Puis : définir l'apparence, rafraîchir le
  relevé Citizens, renommer, et **constater en jeu** que le visage est inchangé et que le nom
  affiché au-dessus de la tête suit le catalogue.
* **TC-197-B — divergence existante.** Sur un PNJ dont les deux noms diffèrent déjà, vérifier le
  badge « divergent » dans la section Identité, puis qu'un seul renommage les remet d'accord.
* **TC-197-C — PNJ non joueur.** Vérifier « apparence non applicable », l'absence d'avertissement,
  et que son renommage passe sans toucher à son apparence.
* **TC-197-D — PNJ sans définition (Help).** Vérifier que sa tuile porte un état d'apparence et que
  la fiche explique pourquoi l'action n'y est pas proposée.
* **TC-197-E — PNJ absent / chunk déchargé.** Vérifier que l'état d'apparence reste lisible et que
  la section Emplacement dit la fraîcheur du relevé, sans forcer de chargement de chunk.
* **TC-197-F — filtre.** « Apparence à configurer » ne liste que les PNJ dont la source est
  inconnue, et le compteur suit.
* **TC-197-G — mobile.** Fiche sur téléphone : une seule identité visible, pastille lisible, aucun
  débordement horizontal, les explications longues repliées.
* **TC-197-H — panne partielle.** Couvert par mocks côté test (`NpcRenameVerdictTest`) ; une
  vérification réelle demanderait de provoquer un échec d'écriture — non effectuée.

Aucun renommage ni application de skin n'a été effectué sur Elias, le Libraire, Help, Tania ou Lily
pendant ce lot, conformément à la demande.

## Résultat attendu

Un administrateur ouvre la fiche d'un PNJ, lit **un** nom, le change, et obtient le même nom partout
— ou un message qui dit précisément quelle couche n'a pas suivi. Il voit l'état d'apparence de chaque
personnage sans ouvrir sa fiche, et sait lesquels attendent une décision. Quand une apparence bloque
un renommage, il l'apprend **avant** de cliquer, avec le geste qui le débloque à côté.

Il n'a plus jamais à savoir ce qu'est « le nom Citizens ».

## Reset / retour à l'état initial

Aucun état à réinitialiser : ce lot n'écrit aucune donnée nouvelle. Un renommage déjà effectué se
défait en renommant à nouveau — jamais en restaurant la base.

Pour revenir au code précédent : `git revert` des trois commits, ou `git checkout e969f5d`.

## Déploiement VeryGames

### À transférer

* **JAR RPGQuest** — obligatoire pour que le relevé transporte le type d'entité et la source
  d'apparence, et pour que l'action `npc.identity.rename` existe côté serveur.
* **PlugAdmin** — déploiement **séparé** (`scripts/plugadmin/deploy.sh`). Les deux moitiés sont
  indépendantes ; sans le JAR, le panel affiche « type inconnu » partout, ce qui est le comportement
  voulu et non une anomalie.

### Ne PAS transférer/altérer

* Les sept fichiers de contenu non suivis du propriétaire, ni `crystal_hunt.yml`.
* La table `npc_citizens_skins` : à conserver telle quelle, elle porte l'information que le
  catalogue lit.
* Aucun PNJ ne doit être renommé ni rhabillé par le déploiement. Aucune migration d'apparence n'est
  déclenchée : l'état « à configurer » est un affichage, pas une action.

### Redémarrage requis

Oui pour le plugin (remplacement de JAR). Le panel se recharge par son propre service.

### Migration automatique

Aucune.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest`, puis redémarrer. Rien à défaire en base.
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Contenu** : sans objet, aucune ressource publiée.

## Logs / diagnostic

* Résultat d'un renommage : message de l'action dans le journal PlugAdmin (page Actions), avec le
  code (`RENAMED`, `DEFINITION_NOT_RENAMED`, `CITIZENS_NOT_RENAMED`, `RENAMED_CITIZENS_ONLY`,
  `RENAMED_DEFINITION_ONLY`, `NOT_FOUND`, `SKIN_SOURCE_UNKNOWN`) et la liste des dialogues à vérifier.
* État d'apparence : relevé `npc.citizens.list` → clés `entityType`, `skinSourceKind`,
  `skinSourceValue`.
* En jeu : `/rpgadmin npc info` en visant le PNJ ; `/npc select` puis `/npc` pour voir son type.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — nouvelle section « Un seul nom de personnage, et une apparence décrite
  (issue #197) » : le champ unique, le tableau des six verdicts, les cinq états d'apparence, les six
  sections de la fiche, et l'absence d'aperçu image comme décision. **Correction d'une affirmation
  devenue fausse** : la Bible disait qu'un renommage sans source connue rattachait l'apparence à
  l'ancien nom ; `SkinPreservationPlanner` refuse ce cas depuis #165, et la raison est désormais
  écrite.
* `docs/current_state.md` — entrée #197.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée du 2026-10-11 (nuit).
* `control-panel/src/main/resources/docs/pnj-citizens.md` — section « 3 bis. Renommer un PNJ », le
  chemin PlugAdmin pour l'apparence avec le tableau des états, et un avertissement sur
  `/npc rename`, qui ne change qu'une couche.
* `control-panel/src/main/resources/docs/pnj-depannage.md` — deux fiches : « Apparence à configurer »
  et « Type de PNJ inconnu ».
* `docs-site/npc.html` — étape PlugAdmin et la raison du refus de renommage.

## Limitations / travail restant

1. **Pas d'aperçu image du skin — décision à prendre par le propriétaire.** L'issue demande un
   aperçu réel. Je ne l'ai pas implémenté, et ce n'est pas un oubli : Citizens ne donne pas accès à
   la texture appliquée, et rendre une tête à partir du pseudo enregistré supposerait d'appeler un
   service d'images tiers **depuis le navigateur de l'administrateur**. Cela exige soit d'élargir la
   CSP du panel (aujourd'hui `img-src 'self' data:`) et de divulguer les pseudos des PNJ à ce
   service, soit d'écrire un proxy d'images côté panel. Ajouter une dépendance réseau sortante au
   panel est une décision structurante : je ne l'ai pas prise de ma propre initiative. En attendant,
   la fiche affiche la **source exacte, nommée** — jamais un visage peut-être faux ni une silhouette
   générique présentée comme un aperçu. **Question à trancher : proxy côté panel, service tiers
   direct avec CSP élargie, ou pas d'aperçu.**
2. **L'état « en cours » repose sur la dernière action de son type seulement.** `lastAppearanceAttempt`
   consulte la dernière action `npc.citizens.skin` du journal et vérifie qu'elle cible bien ce PNJ —
   même limite que `latestForPlayer` pour les joueurs. Si l'apparence d'un autre PNJ a été modifiée
   depuis, celui-ci retombe sur ce que dit le relevé. C'est un défaut de fraîcheur d'affichage,
   jamais une affirmation fausse. Documenté dans la javadoc de la méthode.
3. **`npc.definition.update` efface toujours la description.** Défaut **préexistant** découvert en
   lisant `writeDefinition` : il reconstruit la définition depuis les champs du formulaire et passe
   `null` en description. Mon chemin de renommage ne l'emprunte pas, précisément pour cette raison,
   mais le formulaire « Modifier » reste concerné. Hors périmètre de #197 — **mérite un ticket**.
4. **Les propriétés visuelles des PNJ non joueurs ne sont pas administrables.** L'issue demande de
   « conserver dans RPGQuest le type d'entité et les propriétés visuelles pertinentes ». Le type
   d'entité est désormais relevé et affiché ; les autres propriétés visuelles (variante de
   villageois, équipement, taille) ne sont ni relevées ni modifiables. Non traité.
5. **Aucune configuration guidée à la création.** L'issue demande de « proposer une configuration
   guidée de l'apparence » à la création d'un PNJ. `npc.citizens.provision` accepte déjà un skin
   optionnel, mais le parcours n'a pas été repensé en assistant. Non traité.
6. **Validation visuelle en jeu non effectuée.** Aucun test automatisé ne peut prouver qu'un visage
   est inchangé après un renommage. C'est `PENDING MANUAL VALIDATION` (TC-197-A).
7. **Déploiement non effectué dans ce lot.** Voir la section suivante.

## Prochaine étape suggérée

1. **Déployer PlugAdmin DEV** (`scripts/plugadmin/deploy.sh`), vérifier ce qui est réellement servi,
   puis parcourir TC-197-A à TC-197-G dans le panel. Le panel seul suffit pour juger l'UX ; les états
   d'apparence resteront « type inconnu » jusqu'au JAR, ce qui est attendu.
2. **Demander l'autorisation TLS pour le JAR** si le certificat FTPS VeryGames est toujours expiré.
   Je ne l'ai pas demandée dans ce lot : conformément à la règle en vigueur, je ne redemande pas
   l'autorisation tant qu'un déploiement JAR n'est pas réellement engagé.
3. **Trancher la question de l'aperçu du skin** (limitation 1).
4. **Ouvrir un ticket** pour la description effacée par `npc.definition.update` (limitation 3).
5. Ne pas fermer #197 : les critères « apparence constatée en jeu », « création guidée » et
   « propriétés visuelles des PNJ non joueurs » ne sont pas couverts.
