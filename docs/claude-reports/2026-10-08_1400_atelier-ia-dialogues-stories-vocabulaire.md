# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-08
* Heure : 14:00
* Sujet : Atelier IA étendu aux dialogues et aux stories, et vocabulaire de dialogue enfin déclaré,
  contraint et validé (#146, suite du lot 4)
* Statut : DONE
* Branche Git : `feature/218-starter-kit-tiers`
* Commit actuel si disponible : `1b6982f` (+ un commit de documentation, voir « Déploiement »)
* Début de la tâche : 2026-10-08 10:56:12 (horodatage réel du commit `e52bcc3` qui a clôturé le lot
  précédent ; la demande a suivi immédiatement, mais son heure exacte n'a pas été capturée)
* Fin de la tâche : 2026-10-08 14:00:27
* Durée totale : 03:04:15

## Demande

« continue sur les dialogues et stories dans l'atelier ».

La phase 1 de #146 ne savait générer qu'**une quête**. La demande est d'étendre l'atelier aux deux
autres familles éditables. Les contraintes du lot précédent restent en vigueur : fondation
#110 → résultat IA → import #109 ; aucune sauvegarde ni publication sans confirmation ; règle d'UX
du texte stylé (palette et styles partout où du texte stylé est possible, jamais de balise
MiniMessage dans le parcours normal) ; ne pas rouvrir les lots terminés ; ne pas fermer #109 avant
TC-264 ; un seul Gradle à la fois ; préserver les fichiers de contenu locaux non suivis.

## Analyse

**Un blocage à lever avant toute chose.** Je l'avais déjà identifié deux fois dans les lots
précédents et documenté comme une limite assumée : le vocabulaire des actions et des conditions de
dialogue ne vivait **que** dans les énumérations `ActionType` et `ConditionType` du plugin. Le
Control Panel ne dépend pas du module du plugin — c'est une isolation voulue — donc il ne pouvait
ni le contraindre dans le schéma de #110, ni le documenter, ni le fournir à une IA. Étendre
l'atelier aux dialogues sans régler cela aurait produit un générateur inventant des actions
plausibles mais inexistantes, et c'est exactement ce que le ticket demande d'éviter.

**Audit du moteur** (lecture de `ActionType`, `ConditionType`, `DialogueDefinitionParser`,
`QuestState`, `ContentPackSerializer`) : 12 actions, 8 conditions, des champs lus cas par cas, un
`negate` booléen commun à toutes les conditions, six états de quête, et des nœuds écrits sous forme
de **map indexée par identifiant**.

Cet audit a fait apparaître **quatre défauts réels dans mon propre travail des lots précédents**,
tous les quatre dans ce que #110 et #109 avaient livré le matin même :

1. le schéma **et** les exemples déclaraient `nodes` comme une **liste** de nœuds portant un `id`,
   alors que le moteur et l'export du plugin écrivent une **map**. L'exemple de référence du contrat
   était donc **inimportable** : le contrat décrivait une forme que son propre import refuse ;
2. l'exemple annoncé comme « le plus petit pack **valide** » n'avait pas de `category`, pourtant
   obligatoire pour le validateur réel — il était refusé à l'import ;
3. l'aide du champ d'état de quête n'énonçait que **cinq** des six états réels (`ABANDONED`
   manquait) ; une autre partie du panel, elle, en listait bien six ;
4. `negate` était déclaré `string` dans le schéma, parce qu'il était dérivé d'un descripteur et
   qu'aucun `FieldType` ne décrit un booléen. Le moteur le lit avec `getBoolean` : tout validateur
   JSON aurait donc refusé un pack écrivant `negate: true` — la forme même que la documentation et
   l'exemple recommandent.

La cause commune est identifiable : les exemples du contrat n'étaient vérifiés que sur leur
**texte** (« tel type y figure-t-il ? »), jamais en les faisant réellement passer par l'import.

**Un trou de validation, plus grave que les quatre défauts.** `DialogueValidator` ne regardait pas
du tout les actions et les conditions. Un dialogue citant `type: TELEPORTER_LE_JOUEUR` traversait
l'éditeur guidé **et** l'import sans un mot, et n'échouait qu'au chargement du serveur Minecraft —
c'est-à-dire longtemps après le clic sur « enregistrer », dans un journal que personne ne lit à ce
moment-là. Acceptable tant que le vocabulaire n'était pas déclarable ; inacceptable avec un atelier
IA dont toute la promesse est que la proposition soit confrontée aux validateurs **réels** avant
qu'un administrateur confirme.

## Travail effectué

### 1. Vocabulaire déclaré et verrouillé sur le moteur

`Descriptors` porte désormais `DIALOGUE_ACTIONS` (12) et `DIALOGUE_CONDITIONS` (8), plus le champ
commun `NEGATE`. `DialogueDescriptorsTest` compare ces ensembles **à l'identique** aux énumérations
réelles du moteur, vérifie que chaque champ est une clé que le parseur lit vraiment, que chaque
champ `SELECT` pointe une source connue, que les deux catalogues ne se recouvrent pas, et que
l'aide de l'état de quête nomme les six états. Une action ajoutée ou retirée côté moteur fait
échouer la suite jusqu'à ce que quelqu'un décide quoi en faire — c'est ce qui rend ce catalogue
utilisable au lieu d'être une copie qui dérive.

### 2. Le schéma de #110 contraint les dialogues

Les tableaux `conditions` et `actions` d'un choix sont contraints par des branches `oneOf`
**dérivées** des catalogues, exactement comme les objectifs et les récompenses. `negate` est admis
sur **toute** condition et sur **aucune** action. La limite explicitement documentée de #110 est
levée, dans le javadoc comme dans la documentation destinée à l'IA.

### 3. Trou de validation fermé

`DialogueValidator` refuse désormais : un type d'action ou de condition inconnu (en **listant** les
types acceptés — un refus qui ne dit pas ce qui est accepté n'aide personne), un champ obligatoire
absent, un champ appartenant à un autre type, un entier non positif, un état de quête inexistant, et
un `negate` qui n'est ni `true` ni `false`. Ce dernier cas est le pire de tous : le moteur le
traiterait **silencieusement** comme `false`, donc une condition écrite pour être inversée ne
l'aurait pas été — le dialogue fonctionne, mais à l'envers de l'intention.

Un resserrement de validation peut condamner du contenu existant. Les cinq dialogues embarqués du
dépôt sont donc confrontés au vocabulaire **à chaque exécution de la suite**, avec un message qui
dit explicitement que si le moteur accepte un type refusé ici, c'est le catalogue qu'il faut
compléter, pas le fichier qu'il faut changer. Aucun des cinq n'est en infraction.

### 4. Atelier étendu aux trois familles

`QuestPromptBuilder` devient `ContentPromptBuilder` et `AiQuestStudio` devient `AiContentStudio` :
les noms étaient devenus faux. Une énumération `Kind` porte ce qui **change** d'une famille à
l'autre ; tout ce qui est coûteux à maintenir — contrat de #110, schéma, références réelles,
consigne de format, demande de correction — reste commun. Une quatrième famille n'aurait qu'une
entrée d'énumération et un record de demande à ajouter.

Les consignes propres à chaque famille sont celles que les modèles manquent spontanément :

* **dialogue** — l'id du dialogue **est** celui du PNJ ; `nodes` est une **map** ; un choix dont une
  condition est fausse **n'est pas affiché** (et donc : pour une branche visible mais refusée,
  laisser le choix sans condition et expliquer le refus au nœud suivant) ; aucune action ni
  condition ne s'invente ; toujours prévoir une sortie ;
* **story** — un enchaînement **ordonné de quêtes existantes**, aucune quête inventée, et **aucune**
  section `quests` : une quête manquante est une dépendance, pas une quête à écrire.

**Une famille à la fois, volontairement.** Demander « une quête, son dialogue et une story » en un
appel produit un pack dont une partie est bonne et l'autre refusée, sans moyen simple de ne corriger
que la mauvaise. Pour une quête **et** son dialogue : deux demandes, la seconde pouvant citer la
première, qui existe alors déjà.

Le choix de la famille passe par un **lien**, donc par un GET (`/ai/studio?kind=dialogue`), parce
que la politique de sécurité du panel interdit le JavaScript en ligne et qu'un sélecteur échangeant
le formulaire côté client exigerait un script. La famille voyage ensuite en **champ caché**, pour
que l'envoi ne dépende pas de l'URL d'où il part. Une famille inconnue dans l'URL retombe sur la
quête, sans erreur ni page vide.

**La garantie structurelle est inchangée** : aucune des trois méthodes de génération n'a de code
d'écriture. L'exigence « aucune publication ou modification automatique sans approbation humaine »
reste obtenue **par construction** et non par vigilance à l'écran, et un test le vérifie pour les
trois familles.

**Texte stylé** : le nom affiché du locuteur d'un dialogue et le titre d'une story utilisent le
composant guidé partagé, comme le titre de quête. Un test vérifie sa présence dans les **trois**
formulaires.

### 5. Le test qui manquait

Les exemples et gabarits du contrat passent désormais par un **vrai** import. C'est ce test qui a
fait tomber les défauts (1) et (2) ci-dessus, et c'est lui qui empêchera qu'un contrat décrive à
nouveau une forme que son propre import refuse.

## Fichiers créés

* `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/DialogueDescriptorsTest.java` —
  verrou du catalogue sur les énumérations du moteur (12 actions, 8 conditions, champs, sources,
  six états).
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/DialogueVocabularyValidationTest.java` —
  ce qui est refusé, ce qui reste accepté, chaque type du catalogue réellement écrit et validé, les
  dialogues embarqués du dépôt confrontés au vocabulaire, et la troncature du lecteur YAML vérifiée
  comme **signalée** et non silencieuse.
* `docs/claude-reports/2026-10-08_1400_atelier-ia-dialogues-stories-vocabulaire.md` — ce rapport.

## Fichiers modifiés

Plugin (`src/`) : **aucun**. Ce lot est entièrement dans `control-panel/`.

* `panel/content/Descriptors.java` — `DIALOGUE_ACTIONS`, `DIALOGUE_CONDITIONS`, `NEGATE`, deux
  recherches par type ; aide de l'état de quête corrigée à six états.
* `panel/content/ContentPackSchema.java` — `$defs` `dialogueAction` / `dialogueCondition` dérivés ;
  `nodes` déclaré comme une **map** indexée par id de nœud ; `negate` déclaré **booléen** ; limite
  documentée retirée.
* `panel/content/ContentPackTemplates.java` — vocabulaire complet dans la documentation IA et dans
  les gabarits ; exemples passés en forme map ; dialogue d'exemple enrichi d'une condition et d'un
  `negate` ; `category` ajoutée à l'exemple minimal.
* `panel/content/DialogueValidator.java` — validation du vocabulaire des actions et conditions.
* `panel/ai/ContentPromptBuilder.java` (ex `QuestPromptBuilder.java`) — `Kind`, `DialogueRequest`,
  `StoryRequest`, prompts par famille, partie commune factorisée.
* `panel/ai/AiContentStudio.java` (ex `AiQuestStudio.java`) — `generateDialogue`, `generateStory`,
  famille portée par la génération et par la correction.
* `panel/web/AiStudioPages.java` — choix de la famille, trois formulaires, encart explicatif.
* `panel/web/PanelApp.java` — famille lue en GET et en POST, dispatch, audit enrichi de la famille.
* Tests modifiés : `ContentPackContractTest`, `ContentPackImportTest`, `AiContentStudioTest`
  (ex `AiQuestStudioTest`), `AiPagesTest`.
* Documentation : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`
  (TC-266), `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`,
  `control-panel/src/main/resources/docs/content-packs.md`.

## Base de données / migrations

**Aucune.** La table `ai_provider` créée ce matin est inchangée, et les clés déjà configurées
restent en place. Aucune migration, aucune écriture de schéma.

## Configuration / données

**Aucune.** Aucun fichier de configuration touché, aucune donnée joueur concernée. Les fichiers de
contenu locaux non suivis du propriétaire (`crystal_hunt.yml` modifié, `jeff_skeleton.yml`,
`lily_pumpkin.yml`, `st0_meet_people.yml`, `tc265_ai_securiser_environs.yml`, `test_remise.yml`,
`lily_memories.yml`) sont **intacts** : chaque commit a été fait avec des chemins explicites, et
vérifié par un filtre comptant les fichiers du propriétaire mis en index — zéro à chaque fois.

## Tests automatiques

* `./gradlew test` sur `7cd7dbd` : **BUILD SUCCESSFUL en 11 min 21 s**.
* `./gradlew build` sur `1b6982f` : **BUILD SUCCESSFUL en 11 min 21 s**.
* Les deux depuis le **worktree propre** `/srv/rpgquest/worktree-nuit`, un seul Gradle à la fois.
* Décompte relevé sur les résultats réels : **1890** (plugin) + **914** (panel) + **30** (web-api) =
  **2834 tests, 0 échec**. Soit **+54 tests** sur le panel pour ce lot. Un seul test ignoré, et il
  préexiste : `PanelHardeningMalformedAgentDataTest.authenticatedSmokeAgainstProductionDbCopyWhenProvided`
  s'abstient faute d'une copie de base de production.
* **Aucun appel réseau sortant** : le pipeline de génération est testé avec un fournisseur bouchon,
  aucune clé réelle, aucun jeton consommé.
* Seul changement de code postérieur au build final : le texte de l'encart explicatif de
  `/ai/studio` (concaténation de chaînes), vérifié par `AiPagesTest` seul — **BUILD SUCCESSFUL en
  50 s**.

Quelques tests méritent d'être nommés, parce qu'ils encodent une décision :

* `actionKindsMatchTheEngineExactly` / `conditionKindsMatchTheEngineExactly` — comparaison **à
  l'identique**, pas une inclusion : le catalogue ne peut ni dépasser ni manquer le moteur.
* `theCompleteExampleOfTheContractIsFullyImportable` — l'exemple de référence passe un **vrai**
  import. C'est le test qui a révélé la forme `nodes` erronée.
* `generatingADialogueOrAStoryNeverWritesAnything` — la garantie structurelle, pour les trois
  familles.
* `aDialogueTruncatedByTheReaderIsReportedRatherThanSilentlyAccepted` — une troncature du lecteur
  YAML doit être **signalée** ; sinon l'éditeur enregistrerait une version amputée du dialogue.
* `negateIsAcceptedOnAConditionAndRefusedOnAnAction` et `aNegateValueThatIsNotABooleanIsRejected`.

## Tests manuels à effectuer

* **TC-266** (nouveau, `PENDING MANUAL VALIDATION`) — génération réelle d'un dialogue et d'une
  story, puis **chargement effectif par le serveur** (`/dialogue reload`, `/story reload`). C'est la
  vérification qui compte : un vocabulaire respecté sur le papier mais refusé par le moteur serait
  un échec. Comprend aussi le refus d'un type inventé soumis à la main, et la vérification que la
  correction reste dans la même famille. **Exige une vraie clé API et consomme des jetons
  facturés.**
* **TC-265** (#146, phase 1) — toujours **non exécuté**. Il contient le seul point qui dirait si les
  trois implémentations de fournisseur sont justes : « Tester la connexion ».
* Restent par ailleurs : TC-264 (#109), TC-257 (#123), TC-258 à TC-263.

**#146 reste OUVERTE** : ses deux tests manuels n'ont pas été exécutés. **#109 reste OUVERTE**
conformément à la consigne (TC-264 non exécuté).

## Résultat attendu

Sur `/ai/studio`, une section « Que voulez-vous créer ? » propose trois liens. Le formulaire de
dialogue demande le PNJ porteur, le locuteur (stylé à la palette), le ton, le nombre de nœuds et la
quête concernée ; celui de story demande les quêtes à enchaîner, en rappelant celles qui existent
réellement. La proposition est précédée de la famille, du fournisseur, du modèle et des jetons ;
elle passe par les validateurs réels ; un refus affiche les diagnostics et propose une correction
qui reste dans la même famille. Rien n'est enregistré sans passer par la page d'import et sa
confirmation.

Côté édition : un dialogue citant une action inexistante est désormais **refusé**, avec la liste des
types acceptés.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée n'est écrite par ce lot. Pour neutraliser l'IA sans
redéployer, décocher « Activer » sur chaque fournisseur ou effacer les clés depuis `/ai/providers` ;
pour retirer l'accès, enlever `AI_USE` / `AI_CONFIGURE` du rôle concerné.

## Déploiement VeryGames

### À transférer

**Rien sur VeryGames.** Aucun changement de plugin : pas de JAR, pas de fichier de contenu, pas de
configuration. Le déploiement concerne **uniquement le Control Panel** sur la machine AWS
(`scripts/plugadmin/deploy.sh`).

### Ne PAS transférer/altérer

* Le JAR RPGQuest en ligne — il n'est pas concerné par ce lot.
* `data.db`, les mondes, la progression des joueurs : rien à toucher.
* La base du panel `/var/lib/plugadmin/control-panel.db` — elle contient les clés d'API ; le
  déploiement ne la remplace pas, et une sauvegarde non chiffrée de ce fichier contiendrait les
  clés en clair.

### Redémarrage requis

**Aucun redémarrage Minecraft.** Le service du panel est redémarré par son script de déploiement.

### Migration automatique

Aucune.

## Rollback

`scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`. Le rollback retire les deux
nouvelles familles de l'atelier et rend au validateur de dialogues son silence sur le vocabulaire.
Aucune donnée n'est concernée, et les clés configurées survivent.

## Logs / diagnostic

Le journal d'audit du panel porte désormais la famille sur chaque génération
(`kind=QUEST|DIALOGUE|STORY`), en plus du fournisseur, du modèle, des jetons et du verdict. Toujours
**jamais** la clé, **jamais** le prompt complet. Un refus de vocabulaire apparaît comme un
diagnostic d'erreur à l'écran, avec le chemin exact du choix fautif
(`nodes[accueil].choices[2].actions[0]`).

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — section de l'atelier réécrite (trois familles, tableau des consignes
  par famille, limites révisées) ; **nouvelle section** sur le vocabulaire des actions et conditions,
  avec le tableau complet, la forme en map des nœuds, et la limite du lecteur YAML.
* `docs/current_state.md` — atelier et vocabulaire.
* `control-panel/src/main/resources/docs/content-packs.md` — fiche d'aide dans le panel : le
  parcours pour un dialogue (dont le piège des conditions, à expliquer à l'auteur) et pour une
  story.
* `docs/MANUAL_TEST_PLAN.md` — TC-266 et son entrée dans la table de recette ; limites de TC-265
  révisées.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée du lot, avec l'effet de bord du resserrement de
  validation.
* `.ai/ROADMAP.md` — journal du lot.

## Limitations / travail restant

* **Un élément par génération** — c'est un choix, pas une lacune, et il est expliqué à l'écran.
* **Les PNJ ne sont pas générables** depuis l'atelier : la famille `npcs` n'est pas éditable depuis
  le panel, donc un PNJ proposé serait « laissé de côté » à l'import. Un pack peut toujours en
  contenir.
* **Aucun coût monétaire estimé**, alors que #146 le demande. Les jetons réels sont affichés et
  audités ; convertir en euros supposerait une grille tarifaire qui change sans préavis, et un
  chiffre faux serait pire que pas de chiffre.
* **Garde-fou de budget par appel seulement** (plafond de jetons, délai), pas de budget cumulé.
* **Aucun des trois fournisseurs n'a encore été appelé avec une vraie clé.** C'est la première chose
  à faire et elle prend quelques secondes.
* **Dette signalée — lecteur YAML du panel** : `MiniYaml` ne gère pas les scalaires repliés
  (`text: >`) et abandonne la suite de la map. `dialogues/guard.yml` en contient : **six de ses
  treize nœuds** ne sont pas vus par le panel, qui ne peut donc pas l'éditer. Cette limitation
  **préexiste** à ce lot et était déjà documentée sur la classe ; la protection prévue (garde-fou
  round-trip, refus d'écraser à l'aveugle) **fonctionne** — je l'ai vérifiée, et un test le vérifie
  désormais fichier par fichier, ce qui garantit que la troncature ne devienne jamais silencieuse.
  Gérer cette construction est une tâche à part entière, délibérément pas traitée en effet de bord.
* **Dette rappelée** : `RestartServiceTest` reste sensible au temps réel (voir le lot précédent) ;
  il mériterait une horloge injectable.
* Le sort de `crystal_hunt.yml` (réécrit par le panel chez le propriétaire, ce qui fait échouer
  `CrystalHuntIntegrationTest` dans l'arbre de travail) reste à trancher : adapter le test ou
  retirer ce fichier des exemples embarqués. Les builds de ce lot ont été faits depuis le worktree
  propre, où il passe.

## Prochaine étape suggérée

Configurer un fournisseur sur `/ai/providers` et faire le **point 4 de TC-265** (« Tester la
connexion »). C'est quelques secondes, et c'est le seul moyen de savoir si les trois implémentations
d'API sont justes — tout le reste des tests manuels en dépend. Puis TC-265 en entier, puis TC-266,
puis TC-264 pour pouvoir enfin fermer #109.
