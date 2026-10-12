# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-12
* Heure : 12:06 (heure locale, CEST)
* Sujet : #256 — brouillons persistants des plans IA, reprise d'un plan après audit sans nouvel
  appel payant, et avertissement « écart avec le brief » sur le nombre de niveaux (suite de #94)
* Statut : **DONE** côté code, tests et déploiement PlugAdmin — validation manuelle TC-279 en attente
* Branche Git : `fix/256-plan-drafts` (partie de `feature/238-guard-dialogue-ux` @ `1745a0b`, la ligne
  déployée)
* Commit actuel si disponible : `f5f8eaf` (déployé), précédé de `03f7bf5` et `11eeb5d`
* Début de la tâche : 2026-10-12 10:40:33
* Fin de la tâche : 2026-10-12 12:05:38 *(fin du déploiement vérifié ; rédaction du rapport et
  envoi du courriel ensuite)*
* Durée totale : 01:25:05

## Demande

Sur `buildsite_0009`, un plan OpenAI a été demandé puis audité (action
`7a1ab38d-1b11-4e9d-901a-3e2749ca90ff`, `SUCCESS`, 2 107 blocs, 22 cellules vitrées, 80 sources
lumineuses). « Auditer ce plan » menait à la fiche de l'action, dont le « Retour » mène à
`/actions` : le parcours était perdu, et le plan payé n'était plus réutilisable.

Exigé : brouillon persistant rattaché à l'emplacement sans rien écraser ; reprise après audit,
aperçu, navigation, fermeture, reconnexion ; verdict et aperçu affichés dans le parcours ; actions
« Reprendre / Auditer / Prévisualiser / Demander une correction / Générer » selon l'état ; aucun
appel payant déclenché par la navigation ; récupération du plan de l'action `7a1ab38d` sans appel à
l'IA ; contrôle de concurrence et de version ; tests du parcours complet et de la reprise après
redémarrage du panel. Analyser l'écart `floors: 1` contre un brief à deux étages. Créer un ticket lié
à #94, déployer PlugAdmin si aucun JAR n'est nécessaire. Interdits : nouveau plan payant, pose de
bâtiment, modification de la V1 sur `buildsite_0012`, contournement TLS.

Consigne reçue en cours de tâche : ne pas lancer de troisième suite complète, pousser et déployer
dès que les vérifications nécessaires sont terminées.

## Analyse

Mesuré dans le code et dans la base du panel (lecture seule), pas supposé :

1. **Le plan n'était stocké nulle part.** `BuildingPlanPages` le rendait dans le HTML de la réponse
   au POST `/buildings/plan`, et rien d'autre.
2. **Le retour de l'audit était impossible.** Le formulaire envoyait `return=/buildings/plan`, chemin
   **absent** de `RETURN_PATHS`, et sans identifiant d'emplacement : `safeReturnPath` retombait sur
   `/agents`, d'où le toast puis la fiche d'action.
3. **Le verdict affiché n'était pas celui du plan.** La page lisait « la dernière action
   `building.plan.audit` de l'agent » : un autre plan audité entre-temps aurait pris sa place.
4. **Recharger après une demande renvoyait le POST**, donc un nouvel appel facturé.
5. **Le plan n'est pas perdu** : `params_json` de l'action `7a1ab38d` contient le plan complet
   (1 577 caractères après le trim de validation). Il est donc récupérable sans appel.

**Écart de niveaux.** `ArchitecturalAudit.analyseLevels` compte un niveau quand un sol couvre au
moins le quart de l'emprise avec deux blocs libres au-dessus, et ne lève `LEVEL_UNREACHABLE` que si
`floors > 1`. Le plan récupéré a un rez-de-chaussée (`y=0`), des murs jusqu'à `y=9`, un plafond en
`y=10` recouvert de bibliothèques en `y=11` puis le pignon : aucun second plancher praticable, et
l'escalier (`stair_run` de 8 marches depuis `y=1`) ne mène nulle part. L'audit ignore le brief, donc
l'étage **absent** passe en « conforme ».

**Second risque relevé** : l'audit renvoie `exists: true` pour `bibliotheque_du_hub`, l'identifiant de
la V1. Générer ce plan avec « Remplacer » écraserait la définition V1. L'écran le dit désormais.

## Travail effectué

### Brouillons (panel seul)

- `BuildingPlanDraftStore` : quatre tables **additives** dans `control-panel.db`
  (`building_plan_draft`, `_revision`, `_action`, `building_plan_request`), `CREATE TABLE IF NOT
  EXISTS`, une connexion par opération, `busy_timeout`.
  - **Rien ne s'écrase** : chaque modification crée une révision ; une correction IA crée un
    **nouveau** brouillon avec `parent_id` ; l'archivage masque sans supprimer.
  - **Version optimiste** : `UPDATE … WHERE id = ? AND version = ?` dans une transaction ; une
    version périmée n'écrit rien.
  - **Actions rattachées avec leur version** : un verdict de la version 1 est affiché comme périmé
    sur la version 2, jamais comme le sien.
  - **Jeton à usage unique** réservé par clé primaire **avant** l'appel payant.
  - Horodatage à largeur fixe (millisecondes) : `Instant.toString()` omet les millisecondes nulles,
    ce qui aurait cassé le tri SQL sur le texte.
- `BuildingPlanWorkbench` : état d'un geste (aucun / en attente / terminé, courant ou périmé) lu par
  l'identifiant d'action rattaché ; liste des actions récupérables.
- `PanelApp.handleBuildingPlan` réécrit : **chaque POST se termine par une redirection** vers
  `/buildings/plan?site=…&draft=…#brouillon`, sauf le conflit de version, rendu sur place pour ne pas
  perdre le texte saisi. Gestes : `propose`, `correct` (payants, avec jeton), `save`, `audit`,
  `preview`, `generate`, `recover`, `archive` (aucun appel IA).
- `enqueueValidated` : mise en file avec exactement les contrôles de `/agents/action` (type
  whitelisté, toutes les permissions, agent connu, validation du catalogue, journal d'audit).
- `BuildingPlanPages` réécrit autour du brouillon : liste des plans de l'emplacement avec
  « Reprendre ce plan », panneau du brouillon (origine, fournisseur, consommation, étape suivante),
  verdict et aperçu **de la version courante**, formulaire unique (Enregistrer / Auditer /
  Prévisualiser / Générer), correction prérempli avec le verdict réel, versions précédentes, réponse
  brute, archivage, récupération, nouvelle demande repliée quand des plans existent déjà.
- Fiche `/actions/<id>` : bandeau **« Reprendre ce plan »** (choix de l'emplacement) ou
  **« Revenir au plan »** si un brouillon le contient.
- Bandeau « identifiant déjà dans la bibliothèque » au-dessus de « Générer », avec `<id>_v2` proposé.
- Code mort supprimé : `AgentPages.buildingPlanVerdict/buildingPlanPreview` (plus aucun appelant).

### Écart avec le brief (panel seul)

`BriefLevels` lit un nombre de niveaux **explicite** (« sur deux étages », « 3 niveaux », « R+1 »,
« à étage » / « avec un étage ») et ignore une simple mention (« au premier étage »). Interprétation
toujours **la plus basse** (« deux étages » = au moins 2 niveaux, pas R+2), donc un avertissement
signale un écart certain. Le verdict devient « conforme au contrat, **mais en écart avec le brief** »
(carte orange), et l'écart chiffré préremplit la demande de correction.

**Pourquoi un avertissement et pas un refus** : le français du brief est ambigu, et le plugin ne voit
pas le brief. **Évolution plugin possible, non faite** (elle exigerait un JAR) : une clé de plan
`levels:` déclarée par le modèle et contrôlée par l'audit, qui transformerait l'écart en défaut
bloquant mesuré sur la grille. Notée dans #256.

## Fichiers créés

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BuildingPlanDraft.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BuildingPlanDraftStore.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BuildingPlanWorkbench.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BriefLevels.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/BuildingPlanDraftStoreTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/BriefLevelsTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/BuildingPlanDraftFlowTest.java`

## Fichiers modifiés

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PanelApp.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/BuildingPlanPages.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ActionsPages.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/BuildingSitesPageTest.java` — une
  assertion précisée : la page porte désormais un formulaire de **récupération** légitime ; ce qui
  ne doit pas apparaître sans fournisseur, c'est le formulaire **payant** (`value="propose"`).
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-279),
  `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`, `docs/claude-reports/README.md`

## Base de données / migrations

- `data.db` (plugin) : **aucune modification**.
- `control-panel.db` : quatre tables ajoutées au démarrage, strictement additives ; aucune table
  existante touchée. Constaté après déploiement : les quatre tables existent, `building_plan_draft`
  est vide (aucun brouillon créé par moi : la reprise du plan est laissée au propriétaire).

## Configuration / données

Aucune configuration modifiée. Aucun appel à un fournisseur d'IA. Aucun bâtiment posé ni généré.
`buildsite_0012` et sa V1 intacts. Aucun contenu (quête, story, dialogue, PNJ) touché.

## Tests automatiques

| Test | Couvre |
|---|---|
| `BuildingPlanDraftStoreTest` (9) | version 1 + révision ; **réouverture de la base** ; nouvelle révision sans écraser l'ancienne ; version périmée refusée sans rien écrire ; plan identique ≠ nouvelle version ; jeton à usage unique ; une action = un brouillon ; archivage sans suppression ; récupération retrouvée par son action |
| `BriefLevelsTest` (7) | le brief réel de `buildsite_0009` ; `floors: 1` signalé ; aucun faux positif |
| `BuildingPlanDraftFlowTest` (17) | parcours HTTP réel, fournisseur OpenAI **bouchonné qui compte ses appels** : proposition → brouillon ; navigation et rechargement sans appel ; double soumission = un appel ; formulaire sans jeton refusé ; audit **revient au brouillon** ; verdict du brouillon non remplacé par un autre audit ; écart au brief + identifiant pris ; aperçu dans le brouillon ; édition → version 2 et verdict périmé ; conflit de deux onglets (texte conservé, rien envoyé) ; plan modifié enregistré avant audit ; **redémarrage du panel** ; **récupération depuis une action d'audit sans appel**, idempotente, fiche d'action « Revenir au plan » ; génération sans remplacement implicite ; correction = nouveau brouillon ; archivage |

**Preuve par neutralisation** : en désactivant le jeton à usage unique et le contrôle de version dans
`BuildingPlanDraftStore`, **6 tests échouent** (dont la double soumission, qui fait 2 appels, et le
conflit d'onglets). Fichier restauré à l'identique (`cmp`), puis vérifié.

**Suites complètes**, journaux dans `~/.local/share/rpgquest/claude-logs/2026-10-12-256-plan-drafts/` :

| Exécution | Résultat |
|---|---|
| 1 — `./gradlew test` puis `build` (10:59 → 11:45) | **OK**, 3 783 tests, 0 échec (plugin 2 384, panel 1 369, web-api 30) |
| 2 — après suppression du code mort (11:45 → 12:05) | **OK** — plugin et web-api `UP-TO-DATE`, panel re-testé : 1 369, 0 échec ; `build` OK |

La seconde exécution avait une raison technique (une suppression dans `src/main` après la première).
Conformément à la consigne reçue, **aucune troisième** n'a été lancée ; le commit de documentation
qui suit le déploiement ne touche aucun code.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — **TC-279** (5 min, aucune écriture serveur, aucun appel IA) : ouvrir
`/actions/7a1ab38d-1b11-4e9d-901a-3e2749ca90ff` → choisir `buildsite_0009` → **Reprendre ce plan** →
vérifier le verdict repris et l'écart au brief → **Prévisualiser** → **Rafraîchir** → les coupes
s'affichent dans le même brouillon. Variante : `/buildings/plan?site=buildsite_0009`, section
« Récupérer un plan déjà audité… ».

## Résultat attendu

Le plan payé redevient un brouillon reprenable, avec son verdict, sans appel à OpenAI ; tous les
gestes suivants ramènent au même brouillon ; une reconnexion ou un redémarrage du panel n'y change
rien.

## Reset / retour à l'état initial

« Retirer ce plan de la liste » archive un brouillon. Les tables ajoutées peuvent rester en place
même après un rollback du panel.

## Déploiement VeryGames

### À transférer

**Rien.** Aucun changement du plugin : aucun JAR, aucun FTPS, donc la question du certificat expiré
ne se pose pas.

### Ne PAS transférer/altérer

JAR RPGQuest, `data.db`, mondes, `buildsite_0012`.

### Redémarrage requis

Minecraft : non. PlugAdmin : oui, fait par `deploy.sh`.

### Migration automatique

Côté panel uniquement : quatre `CREATE TABLE IF NOT EXISTS` au démarrage.

### Déploiement PlugAdmin effectué

| Étape | Résultat |
|---|---|
| Sauvegarde de la base | `/var/lib/plugadmin/control-panel.db.bak-256-20261012-120515` (`sqlite3 .backup`, 600, `plugadmin`) |
| Release précédente | `/opt/plugadmin/releases/20261012-120515/` (par `deploy.sh`) |
| `scripts/plugadmin/deploy.sh` depuis le worktree propre `f5f8eaf` | `{"panel":"ONLINE"}`, service `active (running)` |
| JAR servi | SHA-256 **identique** à la distribution construite (`7bad5b40…`) ; classes `BuildingPlanDraftStore`, `BuildingPlanWorkbench` et `BriefLevels` présentes |
| Routes | `/buildings/plan` → `303 /login`, `/actions/7a1ab38d…` → `303` (enregistrées) |
| Base | quatre tables `building_plan_*` créées ; action `7a1ab38d` présente, agent `rpgquest-dev`, plan de 1 577 caractères |

## Rollback

`scripts/plugadmin/rollback.sh app` (ou réinstaller `/opt/plugadmin/releases/20261012-120515/`
puis redémarrer `plugadmin.service`). La base n'a pas besoin d'être restaurée ; la sauvegarde ci-dessus
existe si nécessaire.

## Logs / diagnostic

Journal d'audit du panel : `building.plan.generate`, `building.plan.draft.create|save|recover|archive`,
`building.plan.request.replayed` (resoumission refusée sans appel).

## Documentation mise à jour

Bible (section « Brouillons de plan — reprendre un plan sans le repayer »), `current_state.md`,
TC-279, `SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`. Aucune page `docs-site/` ne couvre les bâtiments.

## Limitations / travail restant

- L'écart de niveaux est un **avertissement panel**, pas un contrôle du plugin (évolution `levels:`
  décrite plus haut, JAR nécessaire).
- Pas d'actualisation automatique pendant un audit en attente : bouton « Rafraîchir » (la CSP
  interdit le script en ligne).
- Générer le plan récupéré tel quel exigerait « Remplacer » sur `bibliotheque_du_hub` (V1) :
  changer son `id:` d'abord.
- #256 reste ouverte jusqu'à TC-279.

## Prochaine étape suggérée

TC-279 par le propriétaire. Puis #257 (CI GitHub Actions, tests ciblés, déploiements adaptés),
demandée en priorité.
