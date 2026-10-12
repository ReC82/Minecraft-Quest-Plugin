# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-12
* Heure : 16:36 (heure locale, CEST)
* Sujet : #265 — refonte des Bâtiments en trois modules (Atelier de création, Bibliothèque,
  Emplacements) ; complément prioritaire : rétablir « Libérer l'emplacement », parcours pour
  non-techniciens, aperçu visuel réel
* Statut : **DONE côté code** — les six lots (A #266, B #267, C #268, D #269, E #270, F #273) et
  la libération (#239, panel + serveur) sont fusionnés dans la ligne d'intégration, CI verte.
  **Rien n'est déployé** (consigne : pas de DEV sans feu vert) ; tests manuels TC-284 à TC-289 à faire.
* Branche Git : `chore/257-ci-github-actions` (ligne d'intégration) ; branches de lot
  `ux/266-…` (PR #271), `fix/239-release-site` (PR #272), `feature/267-workshop-projects` (PR #274),
  `fix/275-restart-test-race` (PR #276), `feature/268-sites-library-focus` (PR #277),
  `feature/273-preview-3d` (PR #278), `fix/239-plugin-occupied-site-guard` (PR #279),
  `feature/269-anchor-meaning` (PR #280), `test/270-building-regressions` (PR #281)
* Commit actuel si disponible : fusion `63f7b19` (PR #279) sur `chore/257-ci-github-actions`
* Début de la tâche : 2026-10-12 14:57:54
* Fin de la tâche : 2026-10-12 16:36:42
* Durée totale : 01:38:48

## Demande

Séparer Bâtiments en trois modules distincts — **Atelier de création** (décrire en français, l'IA
propose et corrige, aperçu compréhensible, enregistrement en bibliothèque, sans emplacement),
**Bibliothèque** (catalogue), **Emplacements** (seulement les lieux physiques) ; lever l'ambiguïté
« Note d'administrateur » / « Brief architectural » ; chaque action de l'historique ouvrable depuis sa
ligne ; pas de coupes 2D ni de YAML imposés ; ne perdre aucune donnée (deux brouillons de la
Bibliothèque du Hub, brief de `buildsite_0009`, schémas, bâtiments posés). Découper en lots, tests
ciblés + CI, PR, parcours avant/après, tests manuels PlugAdmin.

Complément reçu en cours de tâche, dans cet ordre de priorité : **1.** rétablir « Libérer
l'emplacement » (`buildsite_0012` occupé ; retirer le bâtiment, restaurer le terrain, garder
l'emplacement ; distinct de la suppression) ; **2.** utilisateurs non techniques (français courant,
jamais de YAML, coordonnées ni `PLAN_INVALID` imposés ; diagnostic dans « Détails techniques » avec
**« Copier le diagnostic »**) ; **3.** un vrai aperçu visuel, les coupes ASCII n'étant pas présentées
comme un aperçu fini — lot à part si nécessaire, limites documentées.

Consignes : aucun déploiement DEV, aucun appel IA payant, aucune modification de bâtiment existant,
aucune restauration effective.

## Analyse

- **Découpage** (issues créées) : A #266 historique cliquable ; B #267 Atelier indépendant des
  emplacements ; C #268 Emplacements / Bibliothèque recentrés ; D #269 finitions du parcours guidé ;
  E #270 régressions et documentation ; F #273 aperçu visuel fidèle. La libération (#239, issue
  existante) est passée en tête, comme demandé.
- **« Libérer l'emplacement » n'avait pas disparu du code.** Rendu sur une **copie** de la base réelle
  du panel (lignes `panel_user` et `ai_provider` retirées de la copie : aucun secret manipulé), la
  fiche de `buildsite_0012` affichait bien l'action, mais au **5ᵉ rang de 15 sections**, sous
  « Réorienter », intitulée de façon technique et confondue avec « Supprimer l'emplacement ». Défaut
  d'ergonomie, pas régression. Mais la vérification a montré un vrai trou : **supprimer le repère d'un
  emplacement occupé était accepté** par le plugin, laissant un bâtiment sans moyen de restauration.
- **Pourquoi l'Atelier ne pouvait pas rester sur l'emplacement** : le brief vivait dans la fiche de
  l'emplacement, les brouillons y étaient rattachés, et l'identifiant de bibliothèque était dérivé du
  nom du site — c'est ce qui avait visé la V1 sous #94. Un bâtiment se conçoit une fois et se pose
  plusieurs fois : il fallait un **projet** propre, avec un nom technique choisi.
- **Aperçu visuel** : un rendu fidèle aux blocs exige des données que seul le plugin produit (bloc par
  bloc, après expansion du plan) → JAR requis, donc lot F (#273) séparé. En attendant, les coupes
  ASCII sont rangées dans « Détails techniques » et libellées « diagnostic, pas un aperçu », et la
  carte principale annonce l'aperçu à venir. Je ne les présente pas comme un aperçu fini.

## Travail effectué

### Lot A — #266, historique (PR #271, fusionnée)

- Chaque ligne de `/actions` ouvre la fiche de l'action (ligne entière, libellé lien, « Voir le
  détail », clavier) ; le retour conserve filtre et recherche (`back` filtré par `safeBack`). Le
  comportement JS respecte la CSP (pas de script en ligne, `panel.js`).
- « Revenir au plan » renommé « Retour à ce brouillon ». TC-284.

### Libération — #239 (PR #272, fusionnée)

- Bloc **« Retirer le bâtiment et restaurer le terrain »** placé **premier sous « Bâtiment posé »** :
  explique que le bâtiment est retiré, que le terrain revient à son état d'avant la première pose et
  que **l'emplacement est conservé** ; confirmation forte (identifiant retapé + case), **revérifiée
  par le panel** (`AgentActionCatalog` exige `confirm_site == id`) ; explique pourquoi l'action est
  indisponible (pas de sauvegarde du terrain, rôle insuffisant) et d'où vient le terrain rendu.
- « Supprimer l'emplacement (le repère seulement) » en bas de fiche, **refusé par le panel** sur un
  emplacement occupé (`PanelApp.createAgentAction`), avec explication. Le garde-fou côté plugin reste
  à livrer avec le prochain JAR.
- Aucune restauration effective. TC-285.

### Lot B — #267, Atelier de création (PR #274)

- Nouveau module **Atelier de création** (`/buildings/workshop`), premier du menu Bâtiments (Atelier
  → Bibliothèque → Emplacements). Projet = nom, « Décrivez le bâtiment souhaité », côté de l'entrée,
  taille et **nom technique** repliés.
- **Parcours en 4 étapes** (Décrire, Proposer un plan, Vérifier, Enregistrer) avec **une seule action
  principale** à la fois ; résultat de vérification **en français** (`PlanVerdictText` : chaque code
  devient une phrase sur le bâtiment + une piste) ; écart avec la description dit simplement
  (`BriefLevels`). Aucun code ni jargon dans la carte principale (testé).
- Enregistrement **jamais en remplacement** ; nom déjà pris signalé ; le plan est **aligné sur le
  nom du projet** en nouvelle version tracée (sinon il aurait visé l'identifiant inventé).
- **Détails techniques** repliés : plan brut éditable, codes, mesures, coupes ASCII (diagnostic),
  versions, réponse du modèle, reprise depuis l'historique, **« Copier le diagnostic »** (sans
  secret, testé).
- **Reprise non destructive** : « Reprendre dans l'Atelier » copie la description d'un emplacement
  et rattache ses brouillons (colonne `project_id` **ajoutée**, `site_id` conservé) ; aucune action
  envoyée au serveur, fiche de l'emplacement intacte, idempotent. Les deux brouillons de la
  Bibliothèque du Hub (`buildsite_0009`) sont repris ainsi.
- `/buildings/plan` ne fait plus que **rediriger** (un ancien formulaire ne peut plus déclencher
  d'appel IA).
- Garanties de #256 conservées (PRG, jetons à usage unique, versions, reprise après redémarrage,
  récupération sans IA).

### Lot C — #268, Emplacements recentrés (PR #277, fusionnée)

- Fiche d'un emplacement = un lieu : « Note d'administrateur » → **« Description du lieu »** (même
  champ, rien à migrer) ; **plus de brief ni de génération IA**.
- **Ancien brief non détruit** (le plugin le garde) : affiché replié, en lecture seule, avec le
  **lien vers le projet** qui l'a repris, ou **« Reprendre dans l'Atelier »** (aucune action serveur,
  testé).
- Bibliothèque : **« Placer sur un emplacement »** → Emplacements avec le bâtiment **présélectionné**
  sur les emplacements libres (rien envoyé à l'arrivée ; identifiant inconnu ignoré). L'étape
  « Enregistré » de l'Atelier y mène aussi. Aide intégrée du panel réécrite (elle décrivait encore
  l'ancien parcours).

### Lot F — #273, aperçu du bâtiment en 3D (PR #278, fusionnée)

- **Plugin** : `building.plan.preview` renvoie la grille complète (`details.voxels` : palette
  bloc/rôle/forme + une couche par niveau), tirée de la **même expansion que l'audit**. Bornée
  (120 000 cellules, 62 types) ; au-delà ou bloc hors volume : absente **avec son motif**, jamais
  tronquée. Champ additif.
- **Panel** : vue isométrique **SVG rendue côté serveur, sans script** (CSP) ; 4 orientations (on
  tourne, sans miroir), coupe au-dessus d'un niveau pour voir l'intérieur, faces cachées omises
  (≈ 130 Ko pour la V1 de 2 368 blocs), couleurs par matériau et par rôle, vitres translucides,
  dalles en demi-blocs, lumières en petits cubes, légende ; vue par défaut = celle qui montre
  l'entrée, dite sous l'image. « Vérifier le bâtiment » demande aussi l'aperçu (gratuit). Tourner /
  couper = paramètres d'URL, aucune action ni appel IA (testé). Plugin ancien → message explicite.
- **Vérification visuelle réelle** : grille du **vrai plan V1** expansée par le plugin, rendue par
  le code du panel, rastérisée et regardée (tests jetables, supprimés). Elle a trouvé un défaut que
  les tests ne voyaient pas — les briques de pierre peintes en rouge brique — corrigé, et conduit à
  un liseré léger contre les interstices d'anticrénelage. Limites V1 affichées à l'écran (pas de
  textures ; portes, vitres, escaliers, mobilier en cubes).

### Lot D — #269 (PR #280, fusionnée)

Le gros de #269 était livré par B et F (états explicites dans la liste des projets, un bouton
principal, verdict en français, détails repliés, correction IA consentie, aperçu par sélecteurs).
Restait « l'ancre libellée selon la convention réelle » — et la vérification a montré qu'il n'y en a
**pas une** : hutte de test = milieu de la porte (`3/1/0`), bâtiment de l'Atelier = centre de la base
(`sizeX/2, 0, sizeZ/2`). La Bibliothèque affichait « centre de la porte » pour **tous** (faux pour
l'Atelier), et l'aperçu de plan prenait l'ancre par défaut d'une largeur paire pour « explicite ».
`AnchorMeaning` décrit l'ancre réelle de chaque bâtiment.

### Lot E — #270 (PR #281, fusionnée)

Test ajouté : le **même bâtiment sur deux emplacements** donne deux placements indépendants, chacun
avec sa sauvegarde ; retirer l'un restaure **son** terrain et laisse l'autre. Table « exigence de
#265 → test » dans `BATIMENTS_PARCOURS.md` (les autres exigences étaient déjà couvertes).

### Libération côté serveur — #239 (PR #279)

`BuildingSiteService.delete` renvoie `DELETED`/`ABSENT`/`OCCUPIED` et **refuse** un emplacement
occupé ; `building.site.delete` répond `SITE_OCCUPIED`, y compris si un placement actif existe avec
une fiche désynchronisée. Avant, le plugin supprimait le repère et le bâtiment restait sans
restauration possible.

### CI — #275 (PR #276)

Le run *push* de la fusion #239 a échoué sur `RestartServiceTest` (run *PR* vert). Cause réelle,
pas l'infrastructure : `RestartService` relève l'uptime de référence **après** l'envoi de `stop`, le
test le faisait chuter dès l'arrêt vu ; quand le thread de test gagnait, la référence valait déjà 3.
Le test attend désormais la lecture de la référence. Test seul, aucun changement de comportement.

## Fichiers créés

- Panel : `building/BuildingProject.java`, `BuildingProjectStore.java`, `PlanVerdictText.java`,
  `BuildingVoxels.java`, `IsometricView.java`, `AnchorMeaning.java`, `web/WorkshopPages.java`
- Tests panel : `WorkshopFlowTest`, `BuildingProjectStoreTest`, `IsometricViewTest`, `BuildingVoxelsTest`,
  `AnchorMeaningTest`
- Test plugin : `building/plan/PlanPreviewVoxelsTest`
- `docs/control-panel/BATIMENTS_PARCOURS.md` (parcours avant / après, garde-fous)

## Fichiers modifiés

- Panel : `PanelApp`, `AgentPages`, `ActionsPages`, `Layout`, `AgentActionCatalog`, `BuildingPlanDraft`,
  `BuildingPlanDraftStore`, `BriefLevels`, `BuildingPromptBuilder`, `BuildingPlanPreview`,
  `BuildingDefinitionView`, `plugadmin.css`, `panel.js`, aides intégrées `batiments-emplacements.md` et
  `batiments-bibliotheque.md`
- Plugin : `PlanPreview`, `AgentActions`, `BukkitAgentActions`, `AgentActionExecutor`,
  `BuildingSiteService`
- Tests adaptés ou étendus : `BuildingSitesPageTest`, `BuildingPlacementPageTest`,
  `BuildingLifecyclePageTest`, `BriefLevelsTest`, `BuildingPlanPreviewTest`, `AgentActionExecutorTest`,
  `BuildingSiteServiceTest`, `BuildingPlacementServiceTest`, `RestartServiceTest` (#275)
- `docs/RPGQUEST_BIBLE.md`, `docs/MANUAL_TEST_PLAN.md` (TC-284 à TC-289),
  `docs/deployment/SERVER_CHANGELOG.md`

## Fichiers supprimés

- `control-panel/.../web/BuildingPlanPages.java` (remplacé par `WorkshopPages`)
- `control-panel/src/test/.../web/BuildingPlanDraftFlowTest.java` (remplacé par `WorkshopFlowTest`)

## Base de données / migrations

`control-panel.db`, au démarrage du panel, **additives seulement** : table `building_project` ;
colonne `project_id` (+ index) dans `building_plan_draft`, vide, **aucune ligne réécrite** (testé sur
une base issue de #256). **Aucune migration côté plugin** (`data.db` inchangée).

## Configuration / données

Aucune donnée modifiée : ni bâtiment, ni emplacement, ni brouillon, ni brief. Aucun appel IA payant
(fournisseur bouchonné qui compte ses appels). Aucune restauration effective.

## Tests automatiques

Ciblés en local à chaque lot, tous verts au moment du push, dont : `WorkshopFlowTest`
18, `BuildingSitesPageTest` 39, `BuildingPlacementPageTest` 30, `BuildingLifecyclePageTest` 21,
`IsometricViewTest` 11, `PlanPreviewVoxelsTest` 7, `BuildingSiteServiceTest` 52,
`BuildingPlacementServiceTest` 38, `AgentActionExecutorTest` 114. Suite complète en **GitHub Actions** :
run PR vert pour chaque PR (#271, #272, #274, #276, #277, #278, #279, #280, #281) avant fusion.

Incidents : le run *push* de la fusion #239 a échoué sur `RestartServiceTest` — **vraie course** dans
le test (pas d'infrastructure), corrigée par #275. Le contrôle « placement actif » de
`BukkitAgentActions` (#279) dépend de Bukkit et n'a pas de test unitaire ; la règle de domaine est
testée.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` :

- **TC-284** — historique : ouvrir une action depuis sa ligne, retour avec le filtre.
- **TC-285** — `buildsite_0012` : « Retirer le bâtiment et restaurer le terrain » en tête ; repère
  non supprimable tant qu'occupé. **Sans restauration réelle**, sauf décision du propriétaire.
- **TC-286** — Atelier : reprise de la Bibliothèque du Hub, parcours guidé, « Copier le diagnostic ».
- **TC-287** — Emplacements = des lieux ; « Placer sur un emplacement » depuis la Bibliothèque.
- **TC-288** — aperçu en 3D (JAR + panel) : tourner, couper, comparer au plan.
- **TC-289** — l'ancre décrite pour chaque bâtiment.

## Résultat attendu

On conçoit un bâtiment dans l'Atelier sans repère, guidé pas à pas en français, et on le **voit** en
3D avant de l'enregistrer ; on le pose depuis la Bibliothèque sur autant d'emplacements qu'on veut ;
on libère un emplacement sans risquer de supprimer son repère ; chaque action s'ouvre depuis
l'historique.

## Reset / retour à l'état initial

Projets de l'Atelier archivables ; la reprise n'a rien modifié sur l'emplacement source.

## Déploiement VeryGames

### À transférer

**JAR** (aperçu 3D #273, refus serveur #239 — et, déjà en attente : #263 prioritaire, #248, #216)
**et panel** (`scripts/plugadmin/deploy.sh`), depuis `chore/257-ci-github-actions`, **au feu vert du
propriétaire**. Ordre indifférent (compatibilité dans les deux sens).

### Ne PAS transférer/altérer

`data.db`, mondes, bâtiments posés, emplacements et leurs briefs, brouillons, `control-panel.db`
(hors migration additive automatique).

### Redémarrage requis

Serveur Minecraft pour le JAR ; panel.

### Migration automatique

Panel : additive (voir plus haut), sauvegarder `control-panel.db` avant. Plugin : aucune.

## Rollback

JAR et release panel précédents (sauvegardes datées). L'ancien panel ignore la table et la colonne
ajoutées ; l'ancien plugin ne renvoie pas de grille (le nouveau panel le dit) et accepte de nouveau la
suppression d'un emplacement occupé (le panel la refuse toujours).

## Logs / diagnostic

« Copier le diagnostic » dans un projet ; journal d'audit `building.project.import`,
`building.plan.draft.*`, `agent.action.create`.

## Documentation mise à jour

`BATIMENTS_PARCOURS.md`, Bible (Atelier, Emplacements/Bibliothèque recentrés, aperçu 3D, ancre par
bâtiment, refus de suppression), aides intégrées du panel, TC-284 à TC-289, `SERVER_CHANGELOG.md`.

## Limitations / travail restant

- **Historique de la branche #267** : erreur d'indexation de ma part — `71c6cd4` ne compile pas seul,
  `218797e` (intitulé `docs`) porte la fonctionnalité. État final correct et testé ; branche poussée,
  donc non réécrite. Signalé dans PR #274.
- Aperçu 3D V1 : pas de textures ; portes, vitres, escaliers, mobilier en cubes ; pas de rotation
  libre à la souris.
- Issues #239, #265–#270, #273 laissées **ouvertes** jusqu'aux tests manuels.

## Prochaine étape suggérée

Au feu vert : déployer JAR + panel de la ligne d'intégration, puis TC-284 → TC-289 (TC-288 en
priorité pour juger l'aperçu).
