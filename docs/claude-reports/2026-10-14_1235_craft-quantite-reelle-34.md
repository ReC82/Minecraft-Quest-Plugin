# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 12:35
* Sujet : Issue #34 — `CRAFT_ITEM` compte la quantité réellement fabriquée
* Statut : DONE (validation en jeu : PENDING MANUAL VALIDATION)
* Branche Git : `fix/34-craft-multiple` (PR #366, fusionnée dans `chore/257-ci-github-actions` → `1ba3b89`)
* Commit actuel si disponible : `56f7a09` (code), `d5e445f` (docs), merge `1ba3b89`
* Début de la tâche : 2026-10-14 11:41:08
* Fin de la tâche : 2026-10-14 12:36:10
* Durée totale : 00:55:02

## Demande
Session autonome lancée par le superviseur `lqorch`. Ticket #34 : un craft produisant plusieurs
objets (4 planches) ne faisait progresser un objectif `CRAFT_ITEM` que de 1. Attendu : quantité
réelle, shift-clic exact, inventaire plein, pas de double comptage, plafond à la quantité requise.

## Analyse
`QuestCraftItemListener` appelait `engine.handleCraftItem(player, material)` sans quantité ; le
moteur avançait donc toujours de 1. `CraftItemEvent` est émis **une seule fois**, avant le
déplacement du résultat, y compris pour un shift-clic qui enchaîne plusieurs crafts : la quantité
d'un shift-clic dépend de la place réellement disponible et ne peut pas être lue dans l'événement.
Le moteur savait déjà avancer d'une quantité plafonnée (`handleCandidates(..., step)`, utilisé par
`SMELT_ITEM` #141).

## Travail effectué
- `QuestCraftItemListener` (priorité `MONITOR`, `ignoreCancelled`) calcule la quantité selon le clic :
  - clic gauche/droit : un craft (`quantité du résultat`) si le curseur peut le recevoir (vide, ou
    même objet sans dépasser la pile), sinon 0 ;
  - Q / Ctrl+Q : un craft si le curseur est vide ;
  - shift-clic, touche numérique, main secondaire : objets identiques au résultat comptés
    (inventaire + curseur) avant l'événement, puis au tick suivant ; écart borné à
    `[0, crafts possibles avec la grille × quantité par craft]` — un ramassage dans le même tick
    ne gonfle jamais la progression ; joueur déconnecté entre-temps : rien ;
  - autres clics (clonage créatif, double-clic) : 0.
  - Une quantité 0 n'appelle jamais le moteur (qui imposerait sinon un minimum de 1).
- `QuestProgressEngine#handleCraftItem(player, material, amount)` ; l'ancienne signature délègue
  avec 1 (utilisée par des tests existants).

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestCraftObjectiveTest.java`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/quest/progress/QuestCraftItemListener.java`
- `src/main/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngine.java`
- `docs/RPGQUEST_BIBLE.md`, `docs-site/quests.html`, `docs/NPC_DIALOGUES_QUESTS_GUIDE.md`,
  `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-015), `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Format de quête inchangé.

## Tests automatiques
- `QuestCraftObjectiveTest` (9 tests, vrais `CraftItemEvent` diffusés au plugin) : +4 pour 4
  planches, deux crafts cumulés (8), curseur incompatible ou pile pleine → 0, shift-clic 3 bûches →
  12, inventaire plein avec pile partielle → 8 seulement, ramassage simultané non compté (plafond
  ingrédients), objectif à 18/20 → plafonné et quête terminée une fois, clonage créatif → 0, bornes.
- Ciblés en local : `QuestCraftObjectiveTest`, `QuestProgressEngineTest` (50), `ManualTestQuestPackTest`,
  `CrystalHuntIntegrationTest`, `QuestSmeltObjectiveTest`, `QuestItemDeliveryTest` : verts.
- CI GitHub de la PR #366 : verte (plugin, control-panel, web-api, scripts, artefacts).
- `scripts/deploy-verygames.sh` a relancé la suite complète `./gradlew test` + assemble : verts
  (36 min).

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-015 : 1 bûche → +4 ; deux crafts →
cumul ; curseur tenant un autre objet → rien ; shift-clic 3 bûches → +12 ; inventaire plein sauf
pile partielle → seule la quantité rangée ; objectif 18/20 → 20/20 sans dépassement ; relog → pas de
recomptage. Aucun test en jeu n'a été effectué par cette session.

## Résultat attendu
Une quête « fabriquer 20 planches » avance de 4 par bûche transformée, et un shift-clic compte le
total réellement obtenu.

## Reset / retour à l'état initial
Rien à réinitialiser (aucune donnée nouvelle).

## Déploiement VeryGames
Effectué sur DEV : `scripts/deploy-verygames.sh -y` depuis `1ba3b89` (worktree propre
`/srv/rpgquest/wt-orch`), JAR 2 810 895 o, SHA-256 `ebbb2335…27a2` ; JAR distant retéléchargé :
même SHA-256. Backup du JAR précédent (`048e0f2`, SHA-256 `d8eca3ef…`) :
`rpgquest-20261014T103348Z-predeploy.jar`. Redémarrage par `scripts/verygames-restart.sh` :
serveur revenu en ligne, `version RPGQuest` répond, plugin actif. Panel non concerné (inchangé).
### À transférer
Uniquement le JAR RPGQuest.
### Ne PAS transférer/altérer
`config.yml`, `data.db`, quêtes/dialogues du serveur.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh` ; aucune donnée à
restaurer.

## Logs / diagnostic
Journal de déploiement : `~/deploy-logs/deploy-34.log` (machine AWS).

## Documentation mise à jour
Bible (`CRAFT_ITEM`), `docs-site/quests.html`, guide PNJ/dialogues/quêtes, `current_state.md`,
`MANUAL_TEST_PLAN.md` TC-015, `SERVER_CHANGELOG.md`.

## Limitations / travail restant
- Limite préexistante inchangée : `CRAFT_ITEM` ne distingue pas un objet personnalisé d'un objet
  vanilla du même `Material`.
- Ctrl+Q sur le résultat est compté comme un seul craft (comportement vanilla non garanti par
  l'API : sous-comptage possible, jamais de sur-comptage).
- Ticket #34 laissé ouvert jusqu'à la validation en jeu.

## Prochaine étape suggérée
Le propriétaire déroule TC-015 sur DEV, puis ferme #34.
