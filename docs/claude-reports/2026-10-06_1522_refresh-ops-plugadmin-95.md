# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-06
* Heure : 15:22 (heure locale, CEST)
* Sujet : #95 — refresh automatique de `/ops` : la saisie d'annonce était effacée et la
  notification de résultat se répétait indéfiniment
* Statut : DONE
* Branche Git : `feature/169-special-mobs-boss`
* Commit actuel : `1e0cbfc` — *fix(control-panel): le refresh de /ops effaçait la saisie et
  répétait le résultat (#95)*
* Début de la tâche : 2026-10-06 14:58:38
* Fin de la tâche : 2026-10-06 15:22:18
* Durée totale : 00:23:40

## Demande

Corriger **uniquement** le rafraîchissement automatique de `/ops` (#95). Deux bugs reproduits par
l'exploitant :

1. pendant la saisie d'une annonce, le refresh vide le texte et décoche les cases ;
2. après envoi, la notification du résultat réapparaît sans cesse.

Exigences : actualiser les blocs **état**, **logs** et **suivi des actions** sans reconstruire les
formulaires ; préserver texte, cases, canal, focus et aperçu pendant plusieurs cycles ; afficher le
résultat **une seule fois par action**, une nouvelle action produisant sa propre notification ;
vérifier l'**historique** pour distinguer répétition visuelle et éventuelle réexécution ; le refresh
ne doit jamais rejouer une soumission. Ajouter les vérifications pertinentes, tester, commit, push,
déployer le panel AWS, puis vérifier `/health` et le comportement dans un navigateur. Aucun
redémarrage Minecraft. Pas de sous-agents, pas d'exécutions Gradle parallèles, pas de relance des
suites plugin pour une modification exclusivement panel. Aucun ticket fermé, aucune case de test
utilisateur cochée. GitHub reste géré par ChatGPT.

## Analyse

### Les deux bugs n'en étaient qu'un

`panel.js` contenait `initOpsRestart()`, chargé de suivre un redémarrage sans F5. Il démarrait **dès
qu'une carte d'opération existait dans la page** :

```js
var card = document.querySelector("[data-ops-operation]");
if (!card) { return; }
// … puis, à chaque relevé :
if (data.terminal) { runReload(); return; }   // rechargement COMPLET de la page
```

Rien ne distinguait une opération **en cours** d'une opération **déjà terminée**. Or `/ops` affiche
la carte dès que la phase n'est pas `IDLE` — donc aussi pour `DONE`, `FAILED` et `CANCELLED`. Le
cycle était :

1. la page porte une carte `DONE` (cas normal après un redémarrage réussi) ;
2. au bout de 3 s, le script demande `/ops/state.json`, qui répond `terminal: true` ;
3. `runReload()` recharge **toute** la page ;
4. le serveur re-rend la **même** carte `DONE` → retour à l'étape 1.

Une boucle de rechargement toutes les 3 secondes, sans fin. Elle explique **les deux symptômes** :

* **bug 1** — un rechargement complet reconstruit le formulaire d'annonce : le texte saisi, les
  cases, le canal, le focus et l'aperçu repartent de zéro. En saisie, c'est inutilisable ;
* **bug 2** — la notification de résultat vient d'un **drapeau dans l'URL** (`?toast=<id>`, ou
  `?ok=` / `?err=` pour une opération synchrone), posé par la redirection après POST. Chaque
  rechargement re-rend donc la même notification. Elle ne « réapparaissait » pas : elle était
  recréée, à l'identique, toutes les 3 secondes.

`runReload()` dispose d'un garde anti-boucle (`markReloadedFor`), mais il ne s'applique qu'aux
identifiants d'action enregistrés dans `reloadPlan.ids` ; ce chemin-là n'en enregistrait aucun,
donc aucun garde ne s'appliquait.

Contributeur secondaire : `initNotifications()` arme aussi un rechargement quand une action passe de
« en cours » à « succès ». Sur `/ops`, cela reconstruisait le formulaire une fois de plus, juste
après l'envoi d'une annonce.

### Répétition visuelle ou réexécution ?

Vérifié sur l'historique **réel** du panel (`agent_action` de `/var/lib/plugadmin/control-panel.db`),
sur la fenêtre où l'exploitant a reproduit le bug :

| Action | Créée par | Horodatage |
|---|---|---|
| `server.announce` `075bf998` | `owner` | 2026-10-06T12:35:57Z |
| `server.announce` `176f923c` | `owner` | 2026-10-06T12:36:24Z |
| `server.announce` `2a172b29` | `owner` | 2026-10-06T12:40:37Z |
| `server.announce` `48e4384a`, `96058dd0`, `9bc1205f` | `auto` | 12:40:21 / 12:44:21 / 12:45:11 |

Trois annonces de l'exploitant, espacées de minutes, **et pas une de plus** ; les trois autres sont
les annonces du compte à rebours de redémarrage, émises par le service (`auto`). Si la boucle avait
rejoué la soumission, l'historique porterait des dizaines de lignes à 3 s d'intervalle. **La
répétition était purement visuelle.** C'est cohérent avec le mécanisme : le rechargement rejouait
une **redirection GET**, jamais le POST.

## Travail effectué

1. **Plus aucun rechargement de page depuis le suivi d'opération.** `initOpsRestart` est remplacé
   par `initOpsState`, qui ne remplace que des conteneurs **sans formulaire** :
   * le bloc d'état (`[data-ops-state]`) reçoit le HTML renvoyé par le serveur ;
   * la carte d'opération est remplacée **en place** (`outerHTML`), et seulement si le serveur en
     renvoie une — une opération retombée à `IDLE` ne laisse pas un trou ;
   * cadence **20 s** au repos (le rythme réel des heartbeats) et **3 s** seulement pendant une
     opération active ;
   * une opération **déjà terminale au chargement** (`data-ops-terminal="true"`) ne déclenche plus
     de cycle rapide. Un attribut absent est traité comme non terminal, ce qui reste sans danger
     puisque plus rien ne recharge.
2. **Le bloc d'état se rafraîchit vraiment.** Il n'avait jusqu'ici aucun mécanisme propre : il ne
   vieillissait que par le rechargement complet — celui-là même qui cassait la saisie.
   `/ops/state.json` renvoie désormais `stateHtml`, et `OpsPages.stateBlockHtml(query)` le produit
   pour l'agent de la page. Ce bloc ne contient **ni `<form>`, ni `<input>`, ni jeton CSRF** ; un
   test le verrouille, pour qu'on ne puisse pas y glisser un formulaire plus tard.
3. **`/ops` se déclare auto-rafraîchissante** (`data-pa-selfrefresh="ops"`). Le rechargement
   mutualisé du panel (`runReload`) l'épargne, et `initNotifications` n'arme plus de plan de
   rechargement sur une telle vue — la cloche continue de se mettre à jour normalement.
4. **Les drapeaux de résultat deviennent à usage unique.** `dropOneShotResultFlags()` retire `ok`,
   `err` et `toast` de l'URL par `history.replaceState`, juste **après** l'affichage du toast. Le
   résultat reste à l'écran et continue d'être suivi par son identifiant d'action, mais il ne peut
   plus ressusciter — ni par un F5, ni par un retour arrière. Une nouvelle action produit sa propre
   redirection, donc sa propre notification.
5. Les blocs **logs** (`initOpsConsole`) et **suivi des actions** (`initNotifications`) se
   rafraîchissaient déjà de façon ciblée : ils sont inchangés, et ne reconstruisent aucun
   formulaire.

## Fichiers créés

* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/OpsAutoRefreshTest.java` — 8 tests.

## Fichiers modifiés

* `control-panel/src/main/resources/assets/panel.js` — `initOpsState` (remplace
  `initOpsRestart`), `selfRefreshingView`, garde dans `runReload`, `dropOneShotResultFlags` +
  `ONE_SHOT_FLAGS`, plan de rechargement non armé sur une vue auto-rafraîchissante.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/OpsPages.java` — marqueur
  `data-pa-selfrefresh`, conteneur `data-ops-state`, attributs `data-ops-phase` /
  `data-ops-terminal` sur la carte d'opération, nouvelle méthode `stateBlockHtml`.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PanelApp.java` — `stateHtml` dans
  la réponse de `/ops/state.json`.
* `control-panel/src/main/resources/docs/exploitation-serveur.md` — section « Rafraîchissement
  automatique » (ce qui se rafraîchit, à quel rythme, ce qui n'est jamais reconstruit, et le fait
  qu'une notification répétée serait un affichage et jamais une seconde exécution).

## Base de données / migrations

Aucune. Aucun schéma touché, côté panel comme côté plugin.

## Configuration / données

Aucune. Aucun secret, aucune configuration, aucun contenu modifié.

## Tests automatiques

`RPGQUEST_TEST_MAX_HEAP=768m ./gradlew :control-panel:test` — **BUILD SUCCESSFUL in 8m 36s**,
`TEST_EXIT=0`. **669 tests panel, 0 échec, 0 erreur, 1 ignoré** (ignoré préexistant). Les suites
plugin n'ont pas été relancées : la modification est exclusivement panel.

`OpsAutoRefreshTest` (8 tests) :

| Test | Ce qu'il verrouille |
|---|---|
| `thePageDeclaresItselfSelfRefreshingSoTheSharedReloadSkipsIt` | marqueur + conteneurs présents |
| `theRefreshedStateBlockCarriesNoFormAndNoCsrfToken` | le bloc remplacé ne porte aucun formulaire |
| `theStateEndpointHonoursTheAgentOfTheCurrentPage` | l'état rafraîchi décrit bien l'agent affiché |
| `anIdleServiceReturnsNoOperationCardSoNothingIsEmptiedInPlace` | `html` vide à l'arrêt |
| `theOpsPollerNeverReloadsThePage` | aucun `runReload` / `location.*` dans `initOpsState` |
| `theOnlyPageReloadLeftIsGuardedAgainstSelfRefreshingViews` | un seul `location.reload()`, gardé |
| `resultFlagsCarriedByTheUrlAreOneShot` | `ok`/`err`/`toast` retirés, après l'affichage du toast |
| `renderingThePageAgainAfterAnAnnounceDoesNotReplayTheSubmission` | **preuve par l'historique** |

Le dernier test est le plus parlant : il envoie une annonce, puis **re-rend quatre fois l'URL de
retour** (ce que faisait la boucle). Le toast est bien re-rendu à chaque fois — la répétition
visuelle est reproduite — mais l'historique d'actions ne compte **qu'une seule** annonce. Puis la
même page sans drapeau ne montre plus ce résultat.

### Vérification « navigateur » (hors dépôt)

Le vrai `panel.js` **servi par le panel déployé** a été exécuté dans un DOM (jsdom, installé dans le
répertoire de travail temporaire, **pas** dans le dépôt), sur le **vrai HTML de `/ops`** récupéré en
session authentifiée. Délais compressés ÷100 pour observer ~23 cycles d'état en 2,5 s.

* **A) page telle que servie (phase `IDLE`)** : 14 vérifications, toutes vertes ;
* **B) même page + carte d'opération `DONE` réinjectée** — exactement la situation qui bouclait :
  14 vérifications, toutes vertes.

Dans les deux cas : **0 tentative de rechargement ou de navigation**, message saisi, canal, focus et
aperçu intacts après les cycles, bloc d'état réellement remplacé par le contenu du serveur, drapeau
`toast` retiré de l'URL (`?agent=…` conservé), notification toujours visible, et **uniquement des
requêtes GET** — aucune soumission rejouée.

* **Contrôle négatif** : le **même** harnais exécuté sur le `panel.js` d'**avant** le correctif,
  avec une carte `DONE`, signale `Not implemented: navigation to another Document` (jsdom refuse la
  navigation) — soit les appels à `location.reload()` — et le drapeau `toast` reste dans l'URL. Le
  bug est donc bien reproduit par le harnais, et le correctif bien ce qui le fait disparaître.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — à faire par l'exploitant dans son navigateur, sur
`https://plugadmin.lodylands.com/ops` :

1. commencer à rédiger une annonce (texte + canal « Barre d'action »), **attendre 1 à 2 minutes**
   sans rien faire : le texte, le canal, le curseur et l'aperçu doivent être intacts, et le bloc
   « État du serveur » doit avoir vieilli (ligne « Fraîcheur »).
2. envoyer l'annonce : la notification de résultat s'affiche **une fois**. Attendre une minute :
   elle ne doit pas revenir. Un `F5` ne doit pas la ressusciter.
3. envoyer une seconde annonce : elle doit produire **sa propre** notification.
4. demander un redémarrage différé de 5 min puis l'annuler : la carte de suivi doit se mettre à
   jour toute seule, puis **s'arrêter** une fois terminale — plus aucun clignotement de page.
5. ouvrir la page **Actions** et confirmer qu'il y a exactement autant d'annonces que d'envois.

Aucune case du plan de test n'a été cochée, aucun ticket fermé.

## Résultat attendu

Sur `/ops`, l'état, la console et le suivi des actions se mettent à jour tout seuls ; les
formulaires ne sont jamais reconstruits ; le résultat d'une action s'affiche une fois et une seule,
et une nouvelle action produit sa propre notification.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée, aucun état persistant n'est concerné. Revenir en arrière =
redéployer la release précédente (voir « Rollback »).

## Déploiement VeryGames

**Aucun déploiement VeryGames, aucun redémarrage Minecraft.** Le plugin n'est pas modifié : le JAR
en place sur le serveur de jeu reste `ba6c4e3c7147…` (#200). Seul le panel AWS est redéployé.

### À transférer

Rien vers VeryGames. Côté AWS : `scripts/plugadmin/deploy.sh`, exécuté — il reconstruit
`:control-panel:installDist`, sauvegarde l'ancienne application et redémarre le service.

* Release sauvegardée : `/opt/plugadmin/releases/20261006-152004`
* JAR déployé : `/opt/plugadmin/app/lib/control-panel-0.1.0-SNAPSHOT.jar`,
  SHA-256 `bb705a8b53a1e5325ae88441a0f4d3b77c1d2dd107552a5400ebc575efe165e5`
* `panel.js` **réellement servi** : SHA-256
  `3ae4b2b3fdcd026750e11a126684807d5602be35ffcdf1e7a677ed7b5d5fb114` — **identique** au fichier
  source du dépôt, donc le correctif est bien celui qui tourne.

### Ne PAS transférer/altérer

`/etc/plugadmin/plugadmin.env` (secrets), `/var/lib/plugadmin/control-panel.db`, la configuration
nginx, les certificats TLS, les autres sites hébergés, et tout ce qui concerne le serveur Minecraft.

### Redémarrage requis

`systemctl restart plugadmin` — fait par le script. Aucun redémarrage Minecraft.

### Migration automatique

Aucune.

## Rollback

`scripts/plugadmin/rollback.sh app` restaure la release précédente
(`/opt/plugadmin/releases/20261006-152004`) et redémarre le service. Côté dépôt, `1e0cbfc` est un
commit isolé, révocable seul.

## Logs / diagnostic

* `/health` local : `{"panel":"ONLINE","disabled":false,"time":"2026-10-06T13:20:25Z"}`
* `/health` public (`https://plugadmin.lodylands.com/health`) : `{"panel":"ONLINE",…13:20:52Z}`
* `systemctl status plugadmin` : `active (running)` depuis 15:20:23 CEST
* `/ops` en session authentifiée : **200**, 22 101 octets, marqueurs `data-pa-selfrefresh="ops"`,
  `data-ops-state` et `data-ops-announce` présents une fois chacun.
* `/ops/state.json` en direct : `phase=IDLE`, `terminal=true`, `html` vide, `stateHtml` 1 316
  octets **sans** `<form>` ni `_csrf`.

Un compte PlugAdmin jetable a servi à ces vérifications authentifiées, puis a été **supprimé**
(table `panel_user` revenue à `owner`, `TESTER`, `tc252-lecteur`). Aucun compte réel touché.

## Documentation mise à jour

* `control-panel/src/main/resources/docs/exploitation-serveur.md` — nouvelle section
  « Rafraîchissement automatique ».
* `docs/deployment/SERVER_CHANGELOG.md` — lot 16.
* `docs/claude-reports/README.md` — index.

## Limitations / travail restant

* **Le bloc d'état ne se rafraîchit qu'à 20 s** : c'est délibéré (le heartbeat arrive toutes les
  ~20 s, aller plus vite ne montrerait rien de neuf), mais cela signifie qu'un changement d'état
  réel peut mettre jusqu'à ~40 s à apparaître — somme du relevé et du cycle. La ligne
  « Fraîcheur » dit toujours l'âge réel de la donnée.
* **Le retrait du drapeau d'URL dépend de `history.replaceState`.** Sur un navigateur qui le
  refuserait, l'ancien comportement subsiste (le résultat réapparaît à chaque rechargement
  manuel) ; le code échoue silencieusement sans rien casser d'autre.
* **La carte d'opération vit en mémoire côté panel** (limite connue de #95, lot 1) : un
  redémarrage de PlugAdmin fait disparaître le suivi d'une opération différée. Inchangé ici.
* Le rafraîchissement ciblé n'est posé que sur `/ops`. Les autres pages métier continuent
  d'utiliser le rechargement mutualisé, qui reste correct pour elles — elles n'ont pas de
  formulaire long à remplir pendant qu'une action s'exécute.
* **Hors périmètre, à signaler** : le correctif de nommage LuckPerms `4fc5985` (#200) a été
  déployé, mais son rapport de session et les deux actions de vérification depuis le parcours
  panel (synchronisation d'un groupe sans membre, puis second passage sans modification) n'ont pas
  encore été faits.

## Prochaine étape suggérée

Faire la validation manuelle ci-dessus dans le navigateur (5 points, ~5 min), puis trancher entre
terminer la vérification panel de `4fc5985` (#200) et passer au ticket suivant.
