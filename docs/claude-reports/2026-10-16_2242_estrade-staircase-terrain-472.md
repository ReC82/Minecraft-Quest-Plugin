# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 22:42
* Sujet : Issue #472 — Atelier bâtiments : l'estrade d'Elias bloquée par 3 PLAN_INVALID (STAIRCASE)
* Statut : DONE (code livré et déployé sur DEV) — génération IA réelle et pose en jeu : PENDING MANUAL VALIDATION
* Branche Git : `fix/472-estrade-staircase` (PR #480, fusionnée dans `chore/257-ci-github-actions`)
* Commit actuel si disponible : `5c335e7` (merge de la PR #480)
* Début de la tâche : 2026-10-16 21:26:16
* Fin de la tâche : 2026-10-16 22:42:53
* Durée totale : 01:16:37

## Demande
Corriger le parcours réel de l'Atelier bâtiments : une estrade simple (plateau de chêne 7 × 5
surélevé d'un bloc, 5 marches frontales centrées, emprise 7 × 6, rambarde et 2 poteaux à lanternes
à l'arrière, entrée Nord) échouait trois fois en `PLAN_INVALID` sur l'instruction 2 `STAIRCASE`.
Trouver la cause exacte, corriger le moteur et/ou le prompt sans demander au propriétaire de
simplifier ni de retoucher les coordonnées, et rejouer le cas en test automatisé.

## Analyse
Les plans bruts ont été relus dans une **copie** de `control-panel.db` (tables
`building_plan_draft`, `building_plan_draft_action`, `audit_log` ; aucune écriture) :

| Action | Brouillon | Plan IA (gpt-4o) | Refus |
|---|---|---|---|
| 8dfbb7f2 | 93efb9cc (AI_PROPOSAL) | `staircase from [1,1,6] north floor 1 width 5` | zone 1/1/6 hors volume (z va de 0 à 5) |
| 17f3325c | 7581d3e7 (AI_CORRECTION) | `from [1,0,5] north floor 1 width 5` | « au moins en y=1 » |
| d355f78c | 0350bf5d (AI_CORRECTION) | `from [1,1,5] north floor 1 width 5` | « floor doit être AU-DESSUS » |
| (4ᵉ, 16:26) | fc6e6eb8 | identique au 2ᵉ | « au moins en y=1 » |

**Cause racine (confirmée par les plans et le code) :** `op: staircase` (#282) n'existait que pour
l'escalier **intérieur entre deux étages**. Une marche d'estrade posée sur le terrain était
impossible à écrire, quelle que soit la façon de faire :
- sa première marche est en y=0 (le terrain porte la couche `anchor.y`) → refus « au moins en y=1 » ;
- son départ (la place devant) est hors du volume → refus « sort du volume » ;
- devant un plateau dont le dessus est en y=0, c'est **une** marche, `floor == y` → refus
  « floor doit être AU-DESSUS » ;
- en plus, `width: 5` était ramené **sans le dire** à 3 (`MAX_WIDTH = 3`).

Les plans IA étaient aussi faux par eux-mêmes : marches à l'arrière (z=5) montant vers le nord,
plateau sur toute l'emprise 7 × 6. Mais même un plan juste ne pouvait pas passer.

**Valeurs du formulaire :** le projet `e7802b8d` (« L'estrade d'Elias ») a été enregistré dès sa
création (16:21:23Z) avec `max_width = 7`, `max_height = 6`. Aucun code ne transforme 4 en 6
(`Demand` limite seulement à 4 au minimum) : c'est donc **6** qui a été envoyé. On ne peut pas dire
pourquoi le propriétaire pensait avoir mis 4. « 7 × 6 × 6 » dans le message veut dire x × y × z
(largeur × **hauteur** × profondeur), et non largeur × profondeur × hauteur : d'où la confusion.

**Boucle de correction :** les messages de refus arrivent bien jusqu'à l'IA, puisque chaque
nouveau plan modifie l'instruction mise en cause. Mais aucun plan correct n'était possible, donc la
boucle tournait en rond.

## Travail effectué
- `Staircase` : volée **posée sur le terrain** (`y == anchor.y`). Son départ peut être hors du
  volume, aucun appui n'est exigé sous ce départ, et ses marches peuvent remplacer le bord d'un
  plateau. Elles ne remplacent jamais une porte ni un vitrage, et les vides au-dessus restent
  soumis à la règle de l'enveloppe. `floor == y` est accepté pour **toute** volée (une marche) ; seul
  `floor < y` est refusé, avec un message qui dit comment écrire une seule marche.
- Nouvelle forme **`facade:`** (FRONT/BACK/LEFT/RIGHT, `offset` = centre, `width`, `floor`). Le moteur
  pose la volée sur la rangée du bord du volume, au niveau du terrain, et la fait monter vers
  l'intérieur. Les quatre orientations de `front` sont testées.
- `MAX_WIDTH` passe de 3 à 9, et une largeur hors de 1–9 est **refusée** au lieu d'être réduite en
  silence.
- Message « sort du volume » : il donne maintenant les coordonnées permises pour chaque axe
  (`x 0..6, y 0..5, z 0..5`).
- `BuildingPlan.staircase(step)` : un seul endroit lit la géométrie, pour la validation comme pour
  l'expansion.
- Prompt de planification (`BuildingPromptBuilder`) : il décrit les deux usages, ajoute un exemple
  minimal par façade et un exemple complet d'estrade (validé par `PromptPlanConsistencyTest`). Le
  tableau des capacités et la règle « entrée praticable » sont alignés.

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/building/plan/Estrade472Test.java`
- `src/test/resources/building/plans/estrade_elias_472_v1.yml`, `_v2.yml`, `_v3.yml` (plans IA réels)

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/building/plan/Staircase.java`, `BuildingPlan.java`,
  `BuildingPlanExpander.java`, `BuildingPlanYaml.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/BuildingPromptBuilder.java`
- Tests : `BuildingGeneratorTest` (FakeGateway visible dans le package), `VerticalCirculation282Test`
  (nouvelle règle de `floor`), `PromptPlanConsistencyTest` (exemple d'estrade)
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Les bâtiments existants se régénèrent à l'identique : aucune volée existante ne part du
terrain, et le cas `floor == y` était refusé jusqu'ici.

## Tests automatiques
- `Estrade472Test` (15 cas) : le cas exact du ticket passe plan → validation → audit → génération
  (`.schem` simulé) ; 5 marches centrées x 1..5 en z=0, `facing=south`, plateau 7 × 5 intact ; même
  résultat écrit avec `from`/`direction` ; plateau plein 7 × 6 accepté (les marches prennent le bord
  avant) ; la forme `facade` est conservée à la réécriture ; 4 orientations (N/S/E/O) ; les 3 plans IA
  réels restent refusés, pour leur vraie raison et avec un message utile ; les garde-fous tiennent
  (marche sous le terrain, `floor < y`, largeur > 9, volée plus large que la façade).
- En local : `:test --tests 'com.lodygames.rpgquest.building.*'` vert ;
  `:control-panel:test --tests 'panel.ai.*' --tests 'panel.building.*'` vert.
- CI GitHub de la PR #480 : tous les jobs verts (plugin, control-panel, web-api, scripts,
  artefacts).
- `deploy-verygames.sh` a relancé la suite complète et le build : verts (`DEPLOY_EXIT=0`).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — aucune génération IA réelle n'a été lancée (pas d'appel payant non
annoncé), et rien n'a été construit sur DEV :
1. Atelier → projet « L'estrade d'Elias » → générer (ou améliorer) : le plan doit être accepté du
   premier coup, avec `op: staircase` + `facade: FRONT`, `width: 5`, `floor: 0`.
2. Vérifier l'aperçu : plateau 7 × 5, une rangée de 5 marches devant, emprise 7 × 6.
3. Poser sur l'emplacement : on monte sur le plateau sans sauter ; rambarde et 2 lanternes à
   l'arrière.
4. Si le formulaire affiche encore une hauteur max de 6, la passer à 4 : c'est la valeur que l'IA
   reçoit.

## Résultat attendu
Une estrade ou un perron se décrit sans calculer de coordonnées de marche, et le moteur accepte
toute volée posée sur le terrain.

## Reset / retour à l'état initial
Revenir au JAR précédent (ci-dessous). Aucune donnée n'a été écrite.

## Déploiement VeryGames
### À transférer
JAR RPGQuest `5c335e7` : 3 319 147 o, SHA-256 `b92d1c64…7bd239d4f`. Transfert fait par
`deploy-verygames.sh -y` (« JAR en ligne : 3319147 octets (== local) »). PlugAdmin redéployé
(`scripts/plugadmin/deploy.sh`, health ONLINE). Le JAR installé contient bien le nouveau prompt
(« EXEMPLE VALIDE D'ESTRADE » trouvé dans `BuildingPromptBuilder.class`).
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `buildings/`, contenus. Aucun bâtiment n'a été posé sur DEV.
### Redémarrage requis
Oui, fait : `verygames-restart.sh` (0 joueur), serveur ONLINE, heartbeat de l'agent reçu à
20:41:35Z.
### Migration automatique
Aucune.

## Rollback
Remettre `~/.local/share/rpgquest/verygames-backups/rpgquest-20261016T204028Z-predeploy.jar` (JAR
#475, 3 316 879 o, `87e696ac…481ab280`) et redémarrer. PlugAdmin : `scripts/plugadmin/rollback.sh`.

## Logs / diagnostic
`~/rpgquest-logs/deploy-472.log`, `~/rpgquest-logs/plugadmin-472.log`.

## Documentation mise à jour
Bible (`op: staircase`, marches d'entrée sur le terrain), `current_state.md`, `SERVER_CHANGELOG.md`.

## Limitations / travail restant
- L'IA reste libre d'écrire un plan faux (par exemple des marches à l'arrière). Le prompt lui donne
  maintenant le bon gabarit et des messages qui disent quoi écrire, mais seule une génération réelle
  par le propriétaire peut confirmer que le plan passe « du premier coup ».
- `PlanRepair` ne corrige pas lui-même des marches mal orientées : ce n'était pas nécessaire ici.

## Prochaine étape suggérée
Le propriétaire relance la génération de l'estrade d'Elias et vérifie la pose en jeu.
