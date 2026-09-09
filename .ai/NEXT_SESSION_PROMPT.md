Reprends la session RPGQuest précédente.

## Avant toute chose

1. Lis intégralement :
   - CLAUDE.md
   - PROJECT_RULES.md
   - .ai/SESSION_START.md
   - .ai/ROADMAP.md
   - .ai/HANDOFF.md
   - docs/claude-reports/2026-09-09_2118_waypoints-mvp-instance-biome-124.md
   - docs/WAYPOINTS.md

2. Fais l'audit Git obligatoire (`git status`, `git branch --show-current`, `git branch -a`,
   `git log --oneline --graph --decorate --all -30`, `git fetch`).

3. Vérifie que .ai/HANDOFF.md correspond encore au dépôt réel (le code, les tests, l'état Git
   sont la source de vérité).

## Contexte

La session précédente (2026-09-09) a livré le **MVP de l'issue #124 — waypoints par instance de
biome** : nouveau package `com.lodygames.rpgquest.waypoint` (moteur complet), migration V18
(`waypoints`, `waypoint_discoveries`), section config `travel.waypoint.*`, 33 tests waypoint,
`./gradlew build` vert, docs à jour, 2 commits poussés sur `feat/control-panel-admin-tools`
(`2473086` feat, `01f6839` docs).

- Branche de travail : **`feat/control-panel-admin-tools`** — y rester, ne rien merger, aucun push
  vers `main`.
- Le système `waystone` existant (réseau de voyage sur grille) **n'a pas été touché** : le waypoint
  est un système distinct, additif. Cette décision d'architecture est **à confirmer par l'owner**.
- **Déploiement DEV non effectué.** **Validation en jeu non faite** (PENDING MANUAL VALIDATION,
  `docs/MANUAL_TEST_PLAN.md` TC-210).

## RESUME HERE — reprends précisément ici

Priorité, dans l'ordre :

1. **Terminer #124** : si l'owner veut le tester en jeu, déployer le MVP sur **DEV VeryGames
   uniquement** (jamais la prod) — renseigner `scripts/verygames.env` à partir de
   `scripts/verygames.env.example`, puis depuis `/srv/rpgquest/repo` :
   ```
   ./gradlew build
   scripts/deploy-verygames.sh
   scripts/verygames-restart.sh
   ```
   Backup daté automatique (JAR + data.db + config), ne jamais écraser le dernier backup. Ne toucher
   à aucune donnée monde de façon destructive. Un seul redémarrage RCON (règle anti auto-reboot
   VeryGames). Puis dérouler la recette TC-210 et consigner les résultats (jamais inventés).
   Mettre à jour la section « Exécution réelle » de l'entrée SERVER_CHANGELOG du 2026-09-09.

2. Si le moteur #124 est validé et qu'il reste du temps : lecture `/waypoints` (lecture seule) dans
   PlugAdmin, ou a minima l'action agent `waypoint.list` (`WaypointService` expose déjà `all()`,
   `byId(String)`, `discoveryCount(String)`).

3. Ensuite seulement : **issue #122** (protection fonctionnelle anti-enfermement de proximité :
   noyau strict + zone tampon + chemin praticable garanti + signal vertical repérable de loin +
   auto-heal/watchdog). Sur une **branche dédiée** créée depuis la branche d'intégration courante.
   Lire #122 entièrement (idée à spécifier) et transformer en spécification avant de coder.

Ne commence aucun autre ticket que #124/#122.

## Règles de session

- Budget maximal 4 h, SOFT DEADLINE à 3 h 30 (mode HANDOFF ensuite). Capture l'heure de début tôt
  dans `.ai/SESSION_STATE.md`.
- `./gradlew test` et `./gradlew build` doivent rester verts avant de considérer une tâche finie.
  Box contrainte en RAM : `RPGQUEST_TEST_MAX_HEAP=640m ./gradlew build -Dorg.gradle.jvmargs="-Xmx700m"`.
- Rapport Markdown obligatoire dans `docs/claude-reports/` + ligne d'index, en français.
- Avant de t'arrêter : dépôt compilable, tests verts, ROADMAP à jour, HANDOFF.md et
  NEXT_SESSION_PROMPT.md régénérés, mail de fin avec le rapport en pièce jointe.
