# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-09
* Heure : 12:45 (heure locale, CEST)
* Sujet : #47 — diff réel source ↔ DEV, page « Changements en attente », publication groupée,
  fraîcheur de l'affichage, et vérification réelle sur le serveur DEV (lots A → E)
* Statut : DONE
* Branche Git : `feat/47-content-publish`
* Commit actuel si disponible : `c880a19` (code), puis le commit de documentation de ce rapport
* Début de la tâche : 2026-10-09 10:49:47
* Fin de la tâche : 2026-10-09 12:45:00
* Durée totale : 01:55:13

## Demande

Poursuivre #47 pendant une absence d'environ 1 h 30, dans un ordre imposé :

* **Lot A (priorité 1)** — un vrai diff lisible entre la source et le fichier DEV, pour les quêtes,
  dialogues et stories. Lignes ajoutées / retirées / modifiées, contexte, numéros de ligne. Pas
  besoin de « construire GitHub dans PlugAdmin ». Échapper le HTML, ne jamais interpréter le YAML
  comme du HTML, supporter l'Unicode, ne pas exposer de chemin du système de fichiers ni de secret,
  plafonner la taille avec un message propre. Normaliser **uniquement** les fins de ligne ; ne pas
  reformater le YAML avant comparaison. En conflit : **ne pas proposer un remplacement aveugle**.
* **Lot B** — une page `Contenu → Changements en attente` listant les ressources non synchronisées
  des trois familles, avec type, nom, identifiant, état, empreinte source, empreinte DEV,
  chargement par le moteur, date de dernière vérification et action. **Ne pas afficher seulement un
  compteur.** Filtres et recherche. Publication multiple **explicite**, **pas** de bouton « Publier
  tout », pas d'opération globale contournant les protections, résultat par ressource, succès
  partiel, pas de rollback automatique des autres, protection contre la double soumission, et
  jamais deux fois la même ressource dans un même lot.
* **Lot C** — rendre l'actualisation plus claire. **Ne jamais afficher « Synchronisé » avant
  `runtimeConfirmed = true`.**
* **Lot D** — vérification **réelle** sur DEV avec un dialogue de test **dédié**
  (`test_publish_dialogue_47`), sans modifier aucun dialogue existant, cycle complet puis
  nettoyage. **Ne pas toucher à `guard.yml`.** Ne pas profiter du lot pour réécrire le parser YAML.
* **Lot E** — le même cycle pour `test_publish_story_47`, ne référençant qu'une quête existante et
  sûre. **Ne casser aucune Story réelle.**

Contraintes de méthode : ne **pas** commencer par un `clean build` (tests ciblés d'abord), puis une
**seule** tentative de build complète ; en cas de nouvel échec mémoire, ne pas boucler dessus.
Tester en rendant les **vraies** pages et en soumettant les **vrais** formulaires, jamais des corps
de requête écrits à la main (le problème historique de #227). Ne travailler **que** sur #47 —
bâtiments/#213/#234, WorldEdit, bornes/#156, mobs #229/#230 et portails explicitement exclus. **Ne
rien fusionner**, préserver tous les fichiers utilisateur non suivis, et décider seul des petits
choix techniques en les documentant.

## Analyse

Le lot précédent avait livré le **moteur** de publication (liste blanche, sauvegarde, écriture
atomique, rechargement d'une seule famille, relecture du runtime, états partagés) et l'avait prouvé
sur le cas de référence `tc265_ai_securiser_environs`. Ses deux limites assumées étaient exactement
les lots A et B de ce prompt : **aucune comparaison visuelle** (seules les empreintes étaient
affichées) et **aucune vue d'ensemble** de ce qui restait à publier.

Trois éléments de l'existant ont dicté la conception, et aucun n'était négociable :

1. **L'agent est sortant.** Le panel ne peut pas lire un fichier du serveur de jeu ; il doit le
   **demander** et attendre. Afficher un diff suppose donc une action de lecture, et un état
   « comparaison pas encore disponible » qui n'est pas une erreur.
2. **La politique CSP du panel interdit le script en ligne.** Filtres, recherche et accordéons ne
   peuvent donc pas être pilotés par du JavaScript écrit dans la page : ce sont des liens `GET` et
   des `<details>` natifs. Contrainte déjà connue du projet, pas une découverte de ce lot.
3. **`Http.parseUrlEncoded` utilise `map.put`**, donc deux champs de formulaire du **même nom
   s'écrasent** au lieu de s'accumuler. Une case à cocher nommée `sel` répétée par ligne aurait
   silencieusement perdu toutes les sélections sauf une — défaut qui se serait manifesté comme
   « la publication groupée n'a publié qu'une ressource », donc très difficile à attribuer.

Le point délicat du lot A n'est pas l'algorithme de diff, c'est le **sens de la comparaison** et ce
qu'il autorise. La question réellement posée à l'écran est « *qu'est-ce que publier va changer sur
le serveur ?* » : DEV est donc l'**avant** et la source l'**après**. Et puisqu'un conflit signifie
« quelqu'un a modifié DEV hors du panel », la bonne réponse n'est pas un avertissement de plus mais
de rendre le remplacement aveugle **inatteignable** : le bouton n'existe qu'après consultation de la
version DEV **courante**.

## Travail effectué

### Lot A — le diff

`PublishDiff` est une fonction **pure** : alignement par plus longue sous-séquence commune
(programmation dynamique), puis réduction aux lignes de contexte avec un **repère de saut
explicite** (`GAP`) plutôt qu'un silence. Elle ne connaît ni HTTP, ni Bukkit, ni fichier — donc ses
20 tests s'exécutent réellement.

Décisions documentées :

* **DEV = avant, source = après.** L'inverse se lirait « ce que le serveur a en trop », qui n'est
  pas la question.
* **Seules les fins de ligne sont normalisées** (`\r\n` et `\r` → `\n`). Reformater le YAML avant
  comparaison décrirait un fichier qui n'existe pas, et masquerait précisément les remaniements
  d'indentation qui, eux, cassent le chargement.
* **Plafonds explicites** : 4 000 lignes, 256 Kio, 400 lignes rendues, 3 lignes de contexte. Au-delà,
  un message propre remplace la comparaison. Un diff qui fait tomber la page serait une régression
  déguisée en fonctionnalité.
* **Échappement systématique**, numéros de ligne des deux côtés, aucun chemin du système de fichiers
  nulle part dans le rendu.

Côté serveur, une action **nouvelle et en lecture seule**, `content.dev.read`, renvoie le texte d'un
fichier de contenu de DEV : même liste blanche `PublishKind` que la publication, texte plafonné,
permission `CONTENT_READ`. Aucun chemin ne vient du navigateur.

**Consentement éclairé en conflit** : `sawCurrentDev` compare l'empreinte de la lecture DEV à
l'empreinte DEV affichée. Si elles diffèrent, le bouton de publication **n'est pas rendu** — avoir
regardé une version antérieure ne compte pas, puisque c'est exactement ce qui a changé.

### Lot B — la page « Changements en attente »

`/content/pending` liste chaque ressource non synchronisée des trois familles avec sa famille, son
nom, son identifiant déclaré, son état, les deux empreintes abrégées, le chargement par le moteur et
la date du dernier relevé. **Les problèmes d'abord** : conflit, puis publié-non-chargé, puis le
reste. Filtres par famille et par état, recherche sur identifiant / nom / identifiant déclaré, le
tout en liens `GET`.

**La publication groupée est l'orchestration de publications individuelles**, et par construction,
pas par intention : chaque ressource cochée repasse par `AgentActionCatalog.validate("content.publish", …)`
**puis** par la relecture de la source et la revérification d'empreinte, exactement comme une
publication unitaire. Il n'existe aucun chemin d'écriture parallèle.

* **Aucun bouton « Publier tout ».**
* **Un conflit n'est jamais cochable** (`selectable()` exige un état publiable *et* une empreinte
  source) : il se règle sur la fiche, après consultation du diff.
* **Nom de champ unique par ressource** (`sel_<famille>/<identifiant>`) : conséquence du piège
  `map.put` ci-dessus, et accessoirement la garantie qu'une ressource ne peut pas figurer deux fois
  dans le même lot — deux cases de même nom n'existent pas.
* **L'échec d'une ressource n'annule pas les autres** : succès partiel assumé, résultat affiché
  ressource par ressource (`○` en attente, `✓` réussie, `✗` refusée).

### Lot C — la fraîcheur de l'affichage

`PublishState.reconciled(...)` : si le relevé DEV est **antérieur** à une publication qui a
**confirmé le runtime**, c'est le compte rendu de la publication qui fait foi — il a relu le moteur,
le relevé non. Un bandeau « Actualisation en cours » l'explique au lieu de le taire.

La règle du ticket **tient** : « Synchronisé » exige toujours `runtimeConfirmed = true` **et** une
empreinte DEV égale à celle de la source du moment. Si la source a bougé depuis, on ne réconcilie
pas et l'état redevient celui du relevé.

### Lots D et E — vérification sur le serveur réel

Deux cycles **complets**, sur des ressources de test **dédiées**, via le vrai panel HTTPS et les
**vrais formulaires extraits des pages rendues**. Détail des étapes et des empreintes :
`docs/deployment/SERVER_CHANGELOG.md`, entrée du 2026-10-09.

Résumé : dialogue publié (11 dialogues chargés, `runtimeConfirmed`) → modifié → **Différent** → diff
rendu → republié **avec sauvegarde** → **`RESTORED`** → la zone de retour arrière **disparaît** →
**`WITHDRAWN`** (11 → 10) → source supprimée, moteur et liste confirment l'absence. Idem pour la
story (2 → 1), où le bouton proposé après la création était bien **« Retirer de DEV »** et non
« Restaurer » : la ressource était neuve, il n'y avait pas de version précédente.

`guard.yml` n'a pas été touché, aucune Story réelle n'a été modifiée, et le parser YAML n'a pas été
réécrit.

### Une fausse alerte, tranchée plutôt que rapportée telle quelle

Au premier passage, le badge de la story s'est affiché « Source uniquement » là où « Différent »
était attendu. C'était **mon harnais de test**, pas le produit : il cherchait le badge dans une
fenêtre de texte située après la **première** occurrence de l'identifiant, et cette fenêtre tombait
sur la fiche d'**une autre** ressource. Mesuré : sur `/dialogues`, **3 identifiants sur 6**
renvoyaient la section d'un voisin.

Le cycle a été **rejoué** avec un extracteur qui remonte depuis le formulaire de *la* ressource :
badge **« Différent »**, et `expected_dev_sha` non vide dans le formulaire — ce que la republication
confirmait déjà en prenant une sauvegarde. Le produit disait vrai ; l'instrument de mesure était
faux.

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/publish/PublishDiff.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/publish/PendingChanges.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/publish/PublishDiffTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/publish/PendingChangesTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ContentPendingPageTest.java`
* `docs/claude-reports/2026-10-09_1245_diff-reel-et-changements-en-attente.md` (ce rapport)

## Fichiers modifiés

Panel : `panel/web/AgentPages.java` (bloc de diff, fiche, page `/content/pending`, filtres, résultats
récents), `panel/web/PanelApp.java` (route et traitement de la publication groupée),
`panel/web/Layout.java` (entrée de menu), `panel/web/Icons.java` (icône `deploy`),
`panel/publish/PublishState.java` (`reconciled`), `panel/agent/AgentActionCatalog.java`
(`content.dev.read`), `src/main/resources/assets/plugadmin.css`.

Plugin : `web/agent/AgentActionType.java`, `web/agent/AgentActions.java`,
`web/agent/BukkitAgentActions.java`, `web/agent/AgentActionExecutor.java`,
`content/publish/ContentPublishService.java` (lecture plafonnée).

Tests : `panel/web/ContentPublishPageTest.java`, `panel/publish/PublishStateTest.java`,
`web/agent/AgentActionExecutorTest.java`, `web/agent/StubAgentActions.java`.

Documentation : `control-panel/src/main/resources/docs/contenu-publier-sur-dev.md`,
`docs/RPGQUEST_BIBLE.md`, `docs/MANUAL_TEST_PLAN.md` (TC-272), `docs/current_state.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`, `docs/claude-reports/README.md`.

## Base de données / migrations

**Aucune.** `user_version` reste **29**, vérifié sur la base réelle avant et après déploiement.

## Configuration / données

Aucun nouveau fichier de configuration. Aucune clé YAML ajoutée. Les sauvegardes de publication
restent sous `plugins/RPGQuest/content-backups/`, **hors** des dossiers de contenu, donc jamais
relues comme des définitions.

## Tests automatiques

**Tests ciblés d'abord**, comme demandé, puis **une seule** build complète — qui a abouti cette fois :

```
./gradlew clean build   (worktree propre, après ./gradlew --stop)
3334 tests, 0 échec, 38 ignorés
  plugin         2121
  control-panel  1183
  web-api          30
```

`./gradlew --stop` avant de lancer a rendu environ 650 Mo (1107 → 1749 Mo disponibles) : c'est la
différence avec la session précédente, interrompue par le système pour manque de mémoire.

**+145 tests** pour ces lots : `PublishDiffTest` 20, `PendingChangesTest` 20,
`ContentPendingPageTest` 23, `PublishStateTest` +5, `ContentPublishPageTest` +10, plus la mise à jour
des doubles d'actions agent.

Les tests de page **rendent les vraies pages et soumettent les vrais formulaires extraits de ces
pages** — jamais des corps de requête écrits à la main, qui porteraient le bon champ même quand la
page en émet un refusé. C'est précisément le défaut qui avait produit #227.

Une attente de test corrigée, et c'est l'instrument qui avait tort : une assertion interdisait
**toute** balise `<script`, alors que la CSP du panel n'interdit que le script **en ligne** et que la
mise en page charge légitimement Bootstrap depuis la même origine. L'assertion vise maintenant les
balises **sans** attribut `src`.

## Tests manuels à effectuer

* **TC-272** (nouveau, ~8 min) — lire le diff à l'œil, parcourir la page « Changements en attente »,
  exécuter une publication groupée, et juger le **confort mobile**. `PENDING MANUAL VALIDATION`.
* **TC-271** (~6 min) — le parcours de publication à l'œil, reporté du lot précédent.

Aucune des deux ne peut être couverte par des requêtes HTTP : ce qui reste à constater est
**visuel**.

## Résultat attendu

Sur la fiche d'une quête, d'un dialogue ou d'une story : l'état, les deux empreintes, puis — dès
qu'il y a quelque chose à comparer — un bloc **Différences (DEV → source)** avec les lignes
ajoutées et retirées, numérotées. En conflit, un bandeau rouge et **aucun bouton de publication**
tant que la version DEV courante n'a pas été consultée.

Dans le menu **Contenu → Changements en attente** : la liste de tout ce qui n'est pas synchronisé,
les problèmes en haut, des filtres, une recherche, des cases à cocher (sauf sur les conflits) et un
bouton de publication groupée qui rend compte **ressource par ressource**.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée persistée par ce lot, aucune migration. Les deux ressources de
test ont été retirées de DEV **et** supprimées de la source, et l'absence a été confirmée par le
moteur lui-même. Les sauvegardes qu'elles ont produites subsistent sous `content-backups/` comme
trace d'audit.

Le compte PlugAdmin jetable utilisé pour la recette a été supprimé et les secrets de test effacés.

## Déploiement VeryGames

### À transférer

1. **Panel** (`scripts/plugadmin/deploy.sh`) — classes `panel/publish/{PublishDiff,PendingChanges}`
   et route `/content/pending`, toutes nouvelles.
2. **JAR du plugin** — nécessaire **uniquement** pour l'action `content.dev.read`. Sans lui, le panel
   affiche la comparaison comme indisponible ; il ne se casse pas.

**Fait le 2026-10-09 vers 10:10 UTC** : `DEPLOY_EXIT=0`, JAR **2 147 202 o**, SHA-256
`e7814da7141cd8c87e4a98ce6251290802349f966e77cb4f4197eca444b653c7`, sauvegarde
`rpgquest-20261009T101041Z-predeploy.jar`, **un seul redémarrage**, **0 joueur connecté**.

### Ne PAS transférer/altérer

`data.db`, les mondes, `plugins/Citizens/`, les fichiers de contenu déjà publiés, et
`plugins/RPGQuest/content-backups/`.

### Redémarrage requis

**Oui, un seul**, pour installer `content.dev.read`. Le panel seul n'en demande pas.

### Migration automatique

Aucune.

## Rollback

* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Plugin** : redéployer `rpgquest-20261009T101041Z-predeploy.jar`, puis redémarrer. Seule
  conséquence : la comparaison redevient indisponible. **Publier et restaurer continuent de
  fonctionner**, et aucun fichier déjà publié n'est affecté.
* **Une publication précise** se défait depuis sa fiche (« Restaurer la version précédente » ou
  « Retirer de DEV »).

## Logs / diagnostic

Le journal d'actions de l'agent porte la preuve de chaque étape : pour une publication, `code`,
`created`, `reloadCode`, `loadedCount`, `issueCount`, `runtimeConfirmed`, `devShaBefore`,
`devShaAfter`, `sourceSha` et `backupPath`. Un `RELOAD_FAILED` indique explicitement que le fichier
**est** écrit et où se trouve sa sauvegarde ; un `RUNTIME_MISSING` que le rechargement a eu lieu mais
que le moteur ignore l'identifiant.

La page « Changements en attente » affiche en bas les **dernières publications** avec leur état
(`○` en attente, `✓` réussie, `✗` refusée).

## Documentation mise à jour

* `control-panel/src/main/resources/docs/contenu-publier-sur-dev.md` — sections « Voir les
  différences », « Changements en attente » et « Actualisation en cours » (fiche d'aide consultable
  depuis le panel).
* `docs/RPGQUEST_BIBLE.md` — diff, consentement en conflit, page des changements en attente,
  publication groupée, relevé antérieur à une publication.
* `docs/MANUAL_TEST_PLAN.md` — **TC-272** et sa ligne d'index.
* `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`.

## Limitations / travail restant

* **Une ressource supprimée de la source ne peut plus être retirée de DEV depuis sa fiche** : la
  fiche disparaît avec la source. Le retrait doit donc **précéder** la suppression. Pour les
  ressources de test, le retrait a été effectué par l'action `content.publish.rollback` prévue pour
  cela (rollback sans sauvegarde = retrait), soumise au panel qui l'a validée normalement. C'est un
  **manque d'écran, pas un manque de moteur**.
* **Le diff est textuel**, pas sémantique YAML. C'est ce que le prompt jugeait suffisant, et c'est
  aussi le bon choix : un diff sémantique masquerait un remaniement d'indentation qui, lui, peut
  casser le chargement.
* **Les familles PNJ / mobs / boss / objets / recettes** ne passent toujours pas par ce moteur, pour
  les raisons de l'audit du lot précédent (les premières s'appliquent déjà au runtime ; les
  secondes n'ont aucune source côté panel — il leur manque un **éditeur**).
* **TC-272 et TC-271** restent à exécuter à l'œil, confort mobile compris.

### Évaluation objective de #47, comme demandé

**#47 ne doit pas être fermée aujourd'hui**, et pas par prudence de principe : deux éléments
objectifs l'en empêchent.

1. Le ticket énumère **huit familles** ; le moteur en couvre **trois**. L'audit a montré que c'est la
   bonne décision technique, mais **c'est une décision de périmètre, et elle appartient au
   propriétaire**, pas au rapport qui la propose.
2. Les deux vérifications qui restent (TC-272, TC-271) sont **visuelles**, donc hors de portée d'un
   test automatisé — et le confort mobile était une exigence explicite du ticket.

Ce qui est en revanche **acquis et vérifié sur le serveur réel** : publier, comparer, republier,
restaurer, retirer, et refuser proprement. Trois sous-tickets découpent proprement ce qui reste :

* **(a) Retirer de DEV une ressource absente de la source** — l'écran manquant décrit ci-dessus.
* **(b) Un éditeur d'objets et de recettes côté panel** — sans source, il n'y a rien à publier ; c'est
  le vrai manque de ces deux familles, et il est indépendant du moteur de publication.
* **(c) Décision de périmètre sur les PNJ / mobs / boss** — confirmer qu'ils restent sur leur chemin
  runtime actuel, ce qui permettrait alors de clore #47.

## Prochaine étape suggérée

**TC-272 puis TC-271** (~14 min au total, un navigateur et idéalement un téléphone). Ensuite, trancher
avec le propriétaire la question de périmètre (c) : c'est la seule chose qui empêche de clore #47.
