# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-05
* Heure : 20:32 (heure locale de la machine AWS)
* Sujet : Refonte de `CLAUDE.md` — cadre des règles pour toutes les futures sessions Claude Code
* Statut : DONE
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `918c0f0` (avant ce travail) → commit de ce rapport/CLAUDE.md créé en fin de tâche
* Début de la tâche : non mesurable (l'horodatage de début n'a pas été capturé au premier geste ; la première mesure d'heure locale disponible est `2026-09-05 20:31:36`, en fin de tâche)
* Fin de la tâche : 2026-09-05 20:32:30
* Durée totale : non calculable (début non mesuré — voir ci-dessus)

## Demande

Faire de l'instance AWS l'environnement de développement principal de RPGQuest et
formaliser un `CLAUDE.md` racine encadrant toutes les futures sessions Claude Code,
avec un ensemble de règles imposées (Git / source de vérité, Tests / Build,
Documentation, Sécurité, Déploiement, GitHub Issues, Mode de travail).

Contraintes explicites de la mission :

- Lire d'abord toute la documentation structurante du projet et l'état Git.
- Résumer les règles existantes et signaler toute contradiction avec les règles
  demandées **avant** d'écrire le fichier.
- Ne modifier **aucun code fonctionnel** du plugin ; seul `CLAUDE.md` peut être
  créé/adapté.
- Ne rien commiter ni pousser avant validation du contenu par l'utilisateur.

Ajustements demandés après une première proposition validée :

1. Politique de push : autoriser le push automatique de Claude vers sa branche de
   travail dédiée une fois `./gradlew test` et `./gradlew build` verts ; aucun push
   direct vers `main` ; fusion vers `main`, suppression de branche distante,
   réécriture d'historique et release sur instruction explicite uniquement.
2. Marquer la mention de `feature/23-mod-prototype` comme branche d'intégration
   comme **temporaire**, à retirer/mettre à jour dès son intégration.
3. Préciser l'ordre de priorité : code + tests = vérité sur l'état actuel ;
   GitHub Issue / spécification approuvée = vérité sur le comportement cible ;
   documentation technique = référence architecture et conventions.

## Analyse

### Documents lus

- Racine : `CLAUDE.md` (version anglaise préexistante), `PROJECT_RULES.md`,
  `GIT_WORKFLOW.md`, `MODIFICATIONS_EXISTING_FILES.md`, `README.md`, `TODO.md`,
  `build.gradle.kts`, `settings.gradle.kts`, `.gitignore`.
- `docs/` : `current_state.md`, `INDEX.md`, `claude-reports/README.md`,
  `deployment/SERVER_CHANGELOG.md` ; survol `RPGQUEST_BIBLE.md`, `ARCHITECTURE.md`.
- `.ai/` : `SESSION_START.md`, `NIGHT_MODE.md`, `README.md`, `CONTEXT.md`.
- État Git : branche courante `feature/23-mod-prototype` (propre, à jour avec
  `origin`), remote `github.com/ReC82/Minecraft-Quest-Plugin.git`, branches
  distantes = `main` + `feature/01…23-*`, **pas de branche `develop`**, aucun tag,
  Conventional Commits en usage.
- Build : Gradle Wrapper 9.6.1, Kotlin DSL, toolchain Java 21, module `web-api`
  séparé, 115 fichiers de test sous `src/test`.

### Contradictions relevées entre l'existant et les règles demandées

1. **Branche de travail** : `CLAUDE.md` disait « work on the current branch »,
   contre `PROJECT_RULES.md` (« une branche Git par fonctionnalité ») et la règle
   demandée (branche dédiée par tâche). → Arbitré par l'utilisateur : branche
   dédiée créée depuis la branche d'intégration à jour, aujourd'hui
   `feature/23-mod-prototype` (mention temporaire), cible à terme = repartir de
   `main`.
2. **`develop`** : `GIT_WORKFLOW.md` / `.ai/CONTEXT.md` / `.ai/SESSION_START.md`
   décrivent `feature → develop → main`, mais `develop` n'existe pas sur `origin`.
   → Arbitré : pas de `develop` permanente ; réconciliation documentaire reportée
   à une tâche dédiée.
3. **Source de vérité + `git fetch`** : les docs existantes ne mentionnaient ni le
   `git fetch` initial ni l'obligation de signaler un working tree sale. → Ajoutés.
4. **Autonomie vs. demander** : tension entre « ne jamais interrompre » et
   « demander avant une hypothèse structurante ». → Résolu par un découpage
   explicite mineur réversible / structurant ambigu.
5. **Déploiement / backups** : règles de backup daté, non-écrasement du dernier
   backup, rollback et « scripts officiels » — ces scripts n'existent pas encore.
   → Règles inscrites, avec renvoi provisoire à `docs/deployment/VERYGAMES.md` +
   `SERVER_CHANGELOG.md`.
6. **Section « Session bootstrap » manquante** : `MODIFICATIONS_EXISTING_FILES.md`
   impose un pointeur vers `.ai/SESSION_START.md` / `.ai/NIGHT_MODE.md` en tête de
   `CLAUDE.md`, absent de la version anglaise. → Ajouté.
7. **Seuil des rapports** : « chaque demande » (existant) vs « travaux
   significatifs » (demandé). → Règle stricte existante conservée.
8. **`git reset --hard`** : interdit par la règle demandée, absent du `CLAUDE.md`
   anglais (présent seulement dans `.ai/CONTEXT.md`). → Remonté explicitement.
9. **Conventional Commits** : imposés par `PROJECT_RULES.md`, non écrits dans le
   `CLAUDE.md` anglais. → Explicités.

### Sécurité — état constaté

Déjà conforme aux règles demandées : le token web-api vient de
`RPGQUEST_WEB_API_TOKEN`, `.gitignore` exclut `web-api/web-api.properties` /
`store.db`, `src/main/resources/backup-ftp/config.yml` ne contient qu'une config
par défaut (aucun secret). Aucun script `deploy`/`rollback` versionné.

## Travail effectué

- Lecture complète de la documentation structurante et audit Git (aucune
  modification).
- Synthèse des règles existantes + rapport de contradictions fourni à
  l'utilisateur avant écriture.
- Réécriture intégrale de `CLAUDE.md` (anglais → français, aligné sur la langue de
  documentation du projet), en conservant toutes les règles autonomes et
  spécifiques préexistantes et en intégrant les sections demandées.
- Application des 3 ajustements demandés après première validation.
- Exécution de `./gradlew test` puis `./gradlew build` (tous deux verts).
- Création de ce rapport + mise à jour de l'index chronologique
  `docs/claude-reports/README.md`.

### Contenu du nouveau `CLAUDE.md`

Sections : Ordre de priorité des sources — Bootstrap de session — Rôle —
Autonomie et prise de décision — GIT / Source de vérité — Branches — Commits —
Workflow de développement — TESTS / BUILD — DOCUMENTATION — Rapports de session
(+ Suivi du temps) — SÉCURITÉ — DÉPLOIEMENT — GitHub Issues — Mode de travail
(+ Résumé de fin de tâche) — Qualité de code — Contraintes techniques permanentes
— Sessions autonomes longues.

Points notables :

- **Push** : automatique autorisé vers la branche de travail dédiée après
  `test` + `build` verts ; aucun push direct vers `main` ; fusion vers `main`,
  suppression de branche distante, réécriture d'historique partagé, release =
  instruction explicite.
- **Branches** : branche dédiée par tâche significative depuis la branche
  d'intégration à jour ; `feature/23-mod-prototype` marquée explicitement comme
  **mention temporaire** à retirer/mettre à jour après intégration ; pas de
  `develop` permanente ; cible = retour à `main`.
- **Ordre de priorité** : état actuel = code + tests + Git ; comportement cible =
  Issue / spécification approuvée ; architecture & conventions = doc technique ;
  ROADMAP / anciens prompts = indicatifs.
- La consigne « ne pas modifier le code du plugin » **n'a pas** été inscrite dans
  `CLAUDE.md` : elle est propre à cette mission, pas une règle permanente.

## Fichiers créés

- `docs/claude-reports/2026-09-05_2032_claude-md-refonte-regles-sessions.md` (ce
  rapport).

## Fichiers modifiés

- `CLAUDE.md` — réécriture complète (316 insertions, 130 suppressions au diff).
- `docs/claude-reports/README.md` — une ligne ajoutée à l'index chronologique.

Aucun fichier de code (`src/`, `web-api/src/`, `build.gradle.kts`, ressources)
modifié.

## Base de données / migrations

Aucune. Version de schéma inchangée.

## Configuration / données

Aucune modification de configuration ou de données runtime.

## Tests automatiques

- `./gradlew test` → BUILD SUCCESSFUL (tâches `:test` et `:web-api:test`
  `UP-TO-DATE` — aucun code modifié depuis le dernier run vert).
- `./gradlew build` → BUILD SUCCESSFUL.
- Dernier décompte connu (fichiers `build/test-results/`) : 911 tests plugin +
  30 tests `web-api` = 941, aucun échec.

## Tests manuels à effectuer

Aucun. La modification est purement documentaire ; elle n'a aucun effet runtime
sur le plugin ni sur le serveur.

## Résultat attendu

Toute future session Claude Code sur ce dépôt dispose d'un cadre unique et
explicite (`CLAUDE.md`) cohérent avec `PROJECT_RULES.md`, `.ai/` et le workflow
Git réel, avec une politique de push définie et une source de vérité hiérarchisée.

## Reset / retour à l'état initial

`git checkout <commit précédent> -- CLAUDE.md` puis suppression de ce rapport et
de sa ligne d'index restaurent l'état antérieur. Aucune donnée persistante
concernée.

## Déploiement VeryGames

### À transférer
Rien. Aucun changement de JAR, de configuration serveur, de monde ou de données.

### Ne PAS transférer/altérer
`data.db`, mondes, configuration serveur, plugins — non concernés.

### Redémarrage requis
Non.

### Migration automatique
Aucune.

## Rollback

Sans objet côté serveur. Côté dépôt : revert du commit documentaire.

## Logs / diagnostic

Aucun log runtime ajouté ou retiré.

## Documentation mise à jour

- `CLAUDE.md` (réécriture).
- `docs/claude-reports/README.md` (index).

Documents constatés comme désormais partiellement divergents, à réconcilier dans
une tâche dédiée (hors périmètre de cette mission) : `GIT_WORKFLOW.md`,
`.ai/CONTEXT.md`, `.ai/SESSION_START.md` (cycle `feature → develop → main` et
mention d'un `develop` qui n'existe pas).

## Limitations / travail restant

- `GIT_WORKFLOW.md` et les fichiers `.ai/` continuent de décrire un `develop`
  inexistant ; `CLAUDE.md` signale la contradiction mais ne la corrige pas.
- Les scripts officiels `deploy`/`rollback` référencés par `CLAUDE.md` n'existent
  pas encore : à créer.
- La règle « une branche d'intégration temporaire » impose une revue de
  `CLAUDE.md` dès que `feature/23-mod-prototype` est fusionnée.
- Horodatage de début de tâche non capturé (première mesure seulement en fin de
  tâche) → durée non calculable pour ce rapport ; à capturer dès la première
  action lors des prochaines sessions.

## Prochaine étape suggérée

Réconcilier `GIT_WORKFLOW.md` et `.ai/` (CONTEXT.md, SESSION_START.md) avec la
décision « pas de `develop` permanente », puis planifier l'intégration de
`feature/23-mod-prototype` vers `main` et la mise à jour correspondante de la
section « Branches » de `CLAUDE.md`.
