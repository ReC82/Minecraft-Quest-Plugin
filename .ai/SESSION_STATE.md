# RPGQuest — Session State

- Heure de début : 2026-09-09 20:38:36
- Dernier checkpoint : 2026-09-09 21:20
- Branche : feat/control-panel-admin-tools (consigne : rester sur la branche courante, ne rien merger)
- Étape : Issue #124 — MVP waypoints par instance de biome — **DONE (moteur)**, validation en jeu PENDING

## Décision d'architecture (structurante)
Nouveau package `com.lodygames.rpgquest.waypoint`, additif, sur l'infra partagée.
Système `waystone` (déployé) non modifié. Choix réversible. Détail : docs/WAYPOINTS.md + rapport.

## Identité « instance de biome »
`(world, biomeKey, regionX, regionZ)` avec region = floor(coord / region-size, défaut 256).
Compromis (tuile, pas flood-fill) documentés.

## Avancement
- [x] Audit architecture
- [x] Persistance V18 + WaypointRepository
- [x] Modèle de rendu versionné (WaypointModelRegistry + V1)
- [x] WaypointService + planner + identity resolver
- [x] Listeners (move throttlé, interaction bouton, protection)
- [x] Config travel.waypoint + ConfigValidator + config.yml + bootstrap
- [x] Tests : 33 waypoint + extensions Schema/Dialect/ConfigValidator
- [x] Build complet : ./gradlew build VERT (root :test 1254/0, control-panel 279/0)
- [x] Docs : WAYPOINTS.md, bible, current_state, TRAVEL, MANUAL_TEST_PLAN TC-210, README, TODO, ROADMAP, SERVER_CHANGELOG
- [x] Rapport docs/claude-reports/2026-09-09_2118_waypoints-mvp-instance-biome-124.md + index
- [ ] Commit + push (en cours)
- [ ] HANDOFF + NEXT_SESSION_PROMPT
- [ ] Mail de fin

## Déploiement
NON effectué (scripts/verygames.env absent + règle "aucun déploiement automatique" + 1re pose
de blocs autonome). Commande pour demain : renseigner scripts/verygames.env, puis
`./gradlew build && scripts/deploy-verygames.sh && scripts/verygames-restart.sh`, puis TC-210.

## Dernier build : ./gradlew build BUILD SUCCESSFUL (2026-09-09, ~11m36s)
## Derniers tests : root :test 1254/0/29skip ; control-panel:test 279/0/1skip
## Dernier commit : 7af1031 (avant le commit #124 à venir)
