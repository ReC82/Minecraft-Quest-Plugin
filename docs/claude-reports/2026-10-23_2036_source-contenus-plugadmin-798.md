# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-23
* Heure : 20:36
* Sujet : #798 (P0) — PlugAdmin : sécuriser et réconcilier la source des contenus avec DEV (statuts fiables, publications sans écrasement)
* Statut : DONE côté développement (livré, déployé et vérifié sur DEV) — validation en jeu du propriétaire attendue (TC-424), ticket laissé ouvert
* Branche Git : `feature/798-source-contenus-dev` (PR #800, fusionnée dans `chore/257-ci-github-actions`) ; rapport sur `docs/798-rapport-source-contenus`
* Commit actuel si disponible : fusion `e039d4b7` (code)
* Début de la tâche : 2026-10-23 19:16:55
* Fin de la tâche : 2026-10-23 20:36:31
* Durée totale : 01:19:36

## Demande
Ticket #798 (P0, demande du propriétaire du 23/10, liée à #475, #778, #732), lancé par le superviseur
`lqorch` : diagnostiquer les sources de vérité des contenus (dépôt, `PLUGADMIN_CONTENT_DIR`, fichiers
DEV, contenus chargés, mécanismes d'édition/import/export/publication), sauvegarder les trois états
avant toute écriture, réconcilier durablement sans écrasement ni reset, rendre les statuts PlugAdmin
fiables (6 états vérifiés réellement, avec Comparer / Récupérer depuis DEV / Conserver mon brouillon),
sécuriser les publications individuelles et groupées (DEV inchangé depuis la lecture, conflits
refusés, quêtes retirées bloquées, chargement réel vérifié), tests de la liste de l'issue, CI,
déploiement DEV, scénario manuel une étape à la fois. Ne pas clôturer.

## Analyse

### 1. Sources de vérité (relevé du 23/10, 19:16–19:25, lecture seule)
| Source | Constat |
|---|---|
| Dépôt / branches | Intégration `chore/257-ci-github-actions` @ `5914eb06`. Les sessions travaillent dans des worktrees (`/srv/rpgquest/wt-orch`) ; le contenu livré par le code part sur DEV via `deploy-verygames.sh --also`. |
| `PLUGADMIN_CONTENT_DIR` | `/srv/rpgquest/repo/src/main/resources` (`/etc/plugadmin/plugadmin.env`), drop-in `10-content-workspace.conf` (#162) qui ouvre `quests/`, `stories/`, `dialogues/` en écriture. |
| Arbre du propriétaire | Branche `feature/249-audit-architectural` @ `5c74b7f8` ; **dernier checkout le 12/10 à 10:23** (reflog). 22 fichiers de contenu non commités (écrits par PlugAdmin, propriétaire `plugadmin`) : 19 non suivis, `guard.yml` et `crystal_hunt.yml` modifiés, `merchant.yml` supprimé. Un `stash` ancien (`feature/267`) — non touché. |
| Fichiers DEV | 50 fichiers (32 quêtes, 16 dialogues, 2 stories), téléchargés par FTP ; empreintes identiques une à une à celles du relevé agent `content.dev.state`. |
| Chargé par le moteur | Relevé `content.dev.state` + `quest.list` / `dialogue.list` / `story.list` (actions `claude-798`) : 32 quêtes, 16 dialogues, 2 stories chargés — tout fichier DEV est chargé. |

**Pourquoi l'arbre est resté sur une ancienne branche** : depuis la mise en place de `lqorch`
(13/10), la règle est de ne jamais toucher `/srv/rpgquest/repo` ; plus personne ne le fait avancer,
et les contenus livrés par les sessions vont directement sur DEV (`--also`), sans jamais repasser par
la source de PlugAdmin. **Autres mécanismes reproduisant la divergence** : (a) chaque `--also`
futur ; (b) les actions PlugAdmin qui modifient DEV directement (ex. `premiers_pas` sans `giver`,
`lucien` avec « Ouvrir l'atelier », `test_smelt` avec `giver: junior` : DEV plus riche que la
source) ; (c) tout `git checkout` dans cet arbre, qui changerait d'un coup toute la « source » ;
(d) côté panel, la référence « notre dernière publication » n'était lue que dans les 200 dernières
actions : passé ce seuil, une ressource perdait sa référence.

**Cause du statut trompeur** : sans référence connue, `PublishState.of` rendait `DIFFERENT` pour
tout écart, affiché « Enregistré — pas encore en jeu » avec « Mettre en jeu » — même quand DEV
portait la version la plus récente (Elias : source = version d'octobre « Je suis le Guide », DEV =
#475/#778).

### 2. Inventaire fichier par fichier (source → DEV)
- **15 identiques** (dont tous les contenus Lily, `julius`, `musty`, `encounter_bandit`, `jeff_skeleton`, `st0_meet_people`, `test_coeur_bonus`, `test_lucien_artisanat`, `lily_souvenirs`).
- **20 seulement sur DEV** (livrés par le JAR / `--also` : `encounter_*`, `daily_*`, `weekly_*`, `kit_tier3..5`, `help`, `help_recolte`, `jeff*`, `junior*`, `mira_first_map`, `woodcutter_bob`…).
- **15 différents** — dans **tous** les cas DEV est la version la plus récente :
  - 12 fichiers suivis, non modifiés depuis `5c74b7f8` (`guide`, `jo`, `libraire`, `guard_tier1..5`, `kit_tier2`, `woodcutters_request`, `main_story`, `premiers_pas`) : DEV = version de l'intégration (#475/#778), sauf `premiers_pas` (DEV sans `giver: libraire`).
  - `dialogues/guard.yml` (modifié par le propriétaire via PlugAdmin le 14/10 : retrait du choix « Premiers pas ») : DEV = #778, qui contient déjà ce retrait (et Gauthier).
  - `dialogues/lucien.yml`, `quests/test_smelt.yml` (brouillons du propriétaire) : DEV = même contenu **plus** un ajout (« Ouvrir l'atelier » ; `giver: junior`). Aucun contenu propre à la source.
- **2 brouillons jamais publiés** du propriétaire : `dialogues/cuire_un_truc.yml`, `dialogues/mira_cartographer.yml` → conservés.
- **2 retirés** : `quests/first_steps.yml`, `quests/crystal_hunt.yml` → jamais recréés.

**Aucun conflit n'exige d'arbitrage** : la source ne porte aucun contenu absent de DEV, hormis les deux brouillons, conservés tels quels.

## Travail effectué
1. **Sauvegardes vérifiables avant toute écriture** (hôte AWS, `~/.local/share/rpgquest/verygames-backups/`) :
   - `snapshot-798-20261023T171806Z/` : `dev/` (quests, dialogues, stories, npcs, merchants téléchargés par FTP), `source/` (34 fichiers de l'arbre du propriétaire + `git-status.txt`, `git-head.txt`), `loaded/` (résultats JSON `content.dev.state`, `quest.list`, `dialogue.list`, `story.list`), `INVENTAIRE.tsv`, `plan.tsv`, `MANIFEST.sha256` (101 fichiers).
   - `content-snapshot-20261023T180327Z/` (script officiel, juste avant la bascule) : `dev/` + `source/` + `MANIFEST.sha256` chacun, `plan.tsv` identique au premier relevé, `workspace/` (52 fichiers).
2. **Statuts fondés sur une version commune** (panel) : `PublishState` distingue Synchronisé, Brouillon non publié, Absent de DEV, Plus récent sur DEV, Versions différentes, Conflit, Sur DEV non chargé, Retiré définitivement, Hors source, État inconnu. La version commune (`content_dev_reference`) vient de nos écritures, d'une version récupérée ou conservée, ou d'un relevé où les deux côtés sont identiques (enregistrée dès l'arrivée du relevé, `auto:identique`). Quand notre dernière écriture est plus récente que le relevé, c'est son empreinte qui fait foi.
3. **Comparer / Récupérer depuis DEV / Conserver mon brouillon** pour quêtes, stories et dialogues (`/content/recover`, `/content/keep-draft` ; « Recharger depuis DEV » #732 dans l'éditeur de dialogues). Récupérer : version DEV lue à l'empreinte près, sauvegarde de la version PlugAdmin, aucune écriture en jeu. Conserver : case à cocher, la version DEV vue devient la version commune. Les deux refusent si l'une des versions a bougé depuis l'affichage.
4. **Un seul garde-fou de publication** (`AgentPages.publishRefusal`, appelé par `PanelApp.enrichPublish`) : fiche, éditeur de dialogues, lot, création de PNJ. Refus de Plus récent sur DEV, Versions différentes, Conflit, Retiré — un lot qui en contient une est refusé en entier avant tout envoi. Côté plugin, `RetiredContent` refuse `first_steps`/`crystal_hunt` (`RETIRED`, seul ou en lot). La détection « DEV inchangé depuis la lecture » (empreinte attendue) et la vérification du chargement réel après publication (`runtimeConfirmed`) existaient déjà et sont conservées.
5. **Éditeur de dialogues** : la limite de réplique passe de 512 à 1 024 caractères (le moteur n'en a pas) : le vrai dialogue d'Elias (`help_claims` 579 caractères depuis #475) ne pouvait **pas** être enregistré depuis l'éditeur guidé — le critère d'acceptation était inatteignable.
6. **Espace de travail dédié** : script `scripts/plugadmin/content-workspace-reconcile.sh` (snapshot → plan → seed → switch, `rollback-env`).
7. Documentation, tests, registre, changelog.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/publish/RetiredContent.java`
- `src/main/java/com/lodygames/rpgquest/content/publish/RetiredContent.java`
- `scripts/plugadmin/content-workspace-reconcile.sh`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ContentSourceSafetyTest.java`
- `control-panel/src/test/resources/fixtures/798/` (versions réelles figées : `guide-249`, `guide-475`, `guard-plugadmin-avant-778`, `guard_tier1-249`, `woodcutters_request-249`, `first_steps-249`, `crystal_hunt-249`, `README.md`)
- `docs/claude-reports/2026-10-23_2036_source-contenus-plugadmin-798.md`

## Fichiers modifiés
- Panel : `publish/PublishState.java` (nouveaux états, logique de version commune), `publish/DevContentIndex.java`, `publish/PendingChanges.java`, `web/AgentPages.java` (`referenceOf`, `recordCommonVersions`, `readState`, `stateNow`, `publishRefusal`, `devShaNow`, section « Publication sur DEV » avec `decisionForms`), `web/PanelApp.java` (`/content/recover`, `/content/keep-draft`, garde-fou dans `enrichPublish`, écouteur de relevé), `web/DialoguePages.java` (bandeaux, puces, « Conserver mon brouillon »), `content/DialogueValidator.java` (1 024), `content/DialogueAiExport.java`, `config/PanelConfig.java` (Javadoc).
- Plugin : `content/publish/ContentPublishService.java` (refus `RETIRED`).
- Tests : `PublishStateTest`, `ContentPublishPageTest`, `ContentPendingPageTest`, `ContentPublishServiceTest`.
- Aide intégrée : `docs/contenu-publier-sur-dev.md`, `docs/dialogues.md`, `docs/dialogues-depannage.md`.
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-424), `docs/control-panel/CONFIGURATION.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`.

## Base de données / migrations
Aucune migration RPGQuest. Côté panel, la table additive existante `content_dev_reference` (#732) sert désormais de « version commune » (auteurs `auto:identique`, `keep-draft:<compte>`, ou le compte qui a récupéré). Au relevé de 20:33, 49 versions communes ont été enregistrées automatiquement (la 50ᵉ, `musty`, était déjà connue par sa publication du matin). Aucune donnée joueur touchée.

## Configuration / données
- `/etc/plugadmin/plugadmin.env` : `PLUGADMIN_CONTENT_DIR=/srv/rpgquest/repo/src/main/resources` → `/var/lib/plugadmin/content` (ancienne valeur sauvegardée dans `plugadmin.env.pre-798-20261023T183319Z`). Le drop-in `10-content-workspace.conf` (#162) est laissé tel quel (inoffensif, utile au retour arrière).
- `/var/lib/plugadmin/content` (propriétaire `plugadmin`, mode 750) : 52 fichiers = les 50 de DEV + `dialogues/cuire_un_truc.yml` et `dialogues/mira_cartographer.yml` (brouillons du propriétaire), copie vérifiée par SHA-256. Jamais `first_steps` / `crystal_hunt`.
- **Aucun fichier DEV modifié** (FTP en lecture seule ; aucune publication de contenu).
- `/srv/rpgquest/repo` **non touché** : même branche, même HEAD `5c74b7f8`, mêmes 22 fichiers non commités, stash intact.

## Tests automatiques
- `ContentSourceSafetyTest` (9 scénarios, vrais contenus) : Elias de l'ancienne branche → « Versions différentes », jamais publié (fiche, éditeur, lot forgé) ; Elias récupéré depuis DEV, réplique modifiée, enregistré et publié — logique du kit identique ligne à ligne (hors commentaires/guillemets) ; Elias #475 face à DEV #778 → « Plus récent sur DEV », publication refusée ; Gauthier (dialogue + palier 1) : choix explicite « Conserver mon brouillon » puis lot avec l'empreinte DEV ; Bob : version commune retenue, puis changement DEV détecté ; `first_steps`/`crystal_hunt` : jamais publiables ni récupérables ; choix faits sur un écran périmé → rien changé ; brouillon publié seul et en lot ; publication écrite puis refusée → source intacte, « Sur DEV, non chargé », republication possible.
- Locaux ciblés (`RPGQUEST_TEST_MAX_HEAP=768m`) : panel 749 tests sur 75 classes (`*Content*`, `*Dialogue*`, `*Publish*`, `*Pending*`, `*Npc*`, `*Docs*`, `*DiagnosticHelp*`) verts ; plugin `ContentPublishServiceTest` 37 + `ContentPackPublishTest` 11 verts.
- CI GitHub : PR #800 verte (plugin, control-panel, web-api, scripts, artefacts), puis CI du commit de fusion `e039d4b7` verte (run 40002040880).
- Non fait : test de bout en bout du refus `RETIRED` sur le vrai DEV (il aurait fallu envoyer une publication de `first_steps` ; jugé non souhaitable — couvert par les tests unitaires du service).

## Tests manuels à effectuer
TC-424 — **PENDING MANUAL VALIDATION** (aucun test en jeu fait par Claude), une étape à la fois :
1. PlugAdmin → Dialogues → Elias : « À jour en jeu ».
2. Ajouter un mot à la fin de la première réplique → Enregistrer : « Brouillon enregistré — vos modifications ne sont pas encore en jeu ».
3. Mettre en jeu → « À jour en jeu ».
4. En jeu, parler à Elias : le mot apparaît ; « Demander mon kit de départ » et l'amélioration du kit fonctionnent comme avant.
5. (Facultatif) remettre le texte d'origine de la même façon ; « Changements en attente » ne montre ni `first_steps` ni `crystal_hunt`.

## Résultat attendu
PlugAdmin affiche exactement ce que DEV porte ; un écart dit qui a changé ; aucune ancienne version ni quête retirée ne peut repartir en jeu sans un choix explicite ; un contenu livré plus tard par un déploiement apparaît « Plus récent sur DEV » avec « Récupérer depuis DEV ».

## Reset / retour à l'état initial
Aucun reset effectué ni nécessaire.

## Déploiement VeryGames
### À transférer
Fait : JAR `e039d4b7` (4 379 631 octets) le 23/10 à 20:31 ; PlugAdmin `e039d4b7` à 20:32 ; bascule de l'espace de contenu à 20:33.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, aucun fichier de contenu DEV (aucun `--also`), Citizens, l'arbre `/srv/rpgquest/repo`.
### Redémarrage requis
Oui (JAR) — fait par RCON à 20:31, 0 joueur, DEV ONLINE. PlugAdmin redémarré par ses scripts.
### Migration automatique
Aucune.

## Rollback
- JAR : `scripts/rollback-verygames.sh --latest` (`rpgquest-20261023T183122Z-predeploy.jar` = JAR `c15b4c35`) + redémarrage.
- PlugAdmin : `scripts/plugadmin/rollback.sh app`.
- Espace de contenu : `scripts/plugadmin/content-workspace-reconcile.sh rollback-env /etc/plugadmin/plugadmin.env.pre-798-20261023T183319Z` (remet l'ancienne valeur — à éviter tant que l'arbre du propriétaire reste sur `feature/249` ; `/var/lib/plugadmin/content` reste en place).

## Logs / diagnostic
- `deploy-verygames.sh -y` : « Validation : CI verte sur e039d4b », « JAR en ligne : 4379631 octets (== local) », « Déploiement terminé » ; JAR distant retéléchargé : SHA-256 `540cb5c542bc5989ece5ba4701cbcc9851873d0e7a3867e5e7150cfc7406f01e` = local, contient `content/publish/RetiredContent`.
- `verygames-restart.sh --timeout 300` : OFFLINE puis ONLINE (0 joueur).
- PlugAdmin : `DEPLOYED commit=e039d4b7…` ; le KO `/health` du script est la course au démarrage connue — `/health` 200 ensuite ; `POST /content/recover` et `/content/keep-draft` → 303 `/login` (routes servies) ; classe `panel/publish/RetiredContent` présente dans le JAR servi.
- Relevé `content.dev.state` de 20:33 (action `claude-798`) : SUCCESS ; 49 versions communes `auto:identique` enregistrées par l'écouteur ; 0 action `content.publish*` depuis 19:00.
- Vérification d'écran (compte Propriétaire jetable via HTTPS, supprimé ensuite) : « Changements en attente » = 2 lignes « Absent de DEV » (`cuire_un_truc`, `mira_cartographer`), non cochées ; 0 mention de `first_steps`/`crystal_hunt` ; Elias « À jour en jeu » ; liste des dialogues : 16 « À jour en jeu », 2 « Jamais mis en jeu ».

## Documentation mise à jour
Aide intégrée « Publier du contenu sur DEV » (tableau des états, version commune, choix), « Dialogues » et « Dialogues — dépannage » ; Bible (section #798 sous #47) ; `current_state.md` ; `MANUAL_TEST_PLAN.md` (TC-424) ; `control-panel/CONFIGURATION.md` (espace dédié, script) ; registre `SUIVI_VALIDATIONS_DEV.md` (ligne #798, dépendance #778/#798 mise à jour, report du « Retour propriétaire — TC-423 » ajouté sur `main` par 6497d0ac) ; `SERVER_CHANGELOG.md`.

## Limitations / travail restant
- L'éditeur guidé de dialogues réécrit le fichier : commentaires perdus et guillemets ajoutés (dette préexistante, logique intacte, vérifiée ligne à ligne pour Elias).
- Pas de « Récupérer depuis DEV » groupé : après un futur `--also`, chaque ressource « Plus récent sur DEV » se récupère sur sa fiche (comparer puis récupérer). À proposer en suite si le volume le justifie.
- « Récupérer » n'est proposé que pour une ressource présente des deux côtés (états à décider) ; une ressource seulement sur DEV reste « Hors source » (copie existante #430 pour les dialogues).
- La création d'un dialogue garde une première réplique limitée à 512 caractères (formulaire de création seulement).
- Décision non bloquante du propriétaire : sort de ses brouillons `cuire_un_truc` et `mira_cartographer` ; avenir de l'arbre `/srv/rpgquest/repo` (plus utilisé par PlugAdmin).

## Prochaine étape suggérée
Le propriétaire déroule TC-424 (Elias : modifier, enregistrer, mettre en jeu, vérifier en jeu). Ensuite, si besoin, un lot « Récupérer en lot les nouveautés de DEV ».
