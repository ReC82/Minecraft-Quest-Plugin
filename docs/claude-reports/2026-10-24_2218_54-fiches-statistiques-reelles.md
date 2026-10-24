# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-24
* Heure : 22:18
* Sujet : #54 — lot 2 de la bêta portail : fiches de tous les joueurs visibles avec leurs statistiques réellement persistées
* Statut : WAITING_OWNER (livré, déployé sur DEV et AWS, vérifié sur les vraies données ; recette navigateur TC-327 E à faire par le propriétaire)
* Branche Git : `feature/54-fiches-statistiques-reelles` (PR #834), `fix/54-libelle-creatures` (PR #835), toutes deux fusionnées dans `chore/257-ci-github-actions` ; rapport : `docs/54-rapport-fiches-statistiques`
* Commit actuel si disponible : `9d0cd6c5` (fusion #834, JAR DEV), `e026766c` (fusion #835, portail AWS)
* Début de la tâche : 2026-10-24 21:09:41
* Fin de la tâche : 2026-10-24 22:17:38
* Durée totale : 01:07:57

## Demande
Session lqorch sur #54, specs `2026-10-24b-portail-beta-vrais-joueurs.md` (prioritaire) et
`2026-10-24-portail-donnees-reelles.md` (lot 2) : utiliser les statistiques déjà persistées par RPGQuest plutôt
que des compteurs inventés, préparer les classements et leur actualisation, en bêta restreinte (vrais pseudos,
jamais d'UUID, d'IP, d'inventaire ni de coordonnées), incréments testables, CI, déploiement, preuves réelles.

## Analyse
- Le modèle de statistiques #54 (catalogue `StatMetric`, saisons/événements, archivage V39) et le catalogue de
  23 classements #734 existaient et étaient déjà publiés dans l'instantané réel depuis #421 (20:49).
- Limite relevée par le rapport #421 : les **fiches** ne contenaient que les joueurs présents dans un top 10
  (`leaderboard-size`), et seulement leur XP et leurs quêtes. Les autres statistiques persistées (créatures
  tuées, blocs cassés, pêche, découvertes de waypoints / villes / pierres de voyage, histoires) n'apparaissaient
  sur aucune fiche, alors que les tables existent et sont alimentées.
- Choix : un total « depuis le début du suivi » par métrique, lu directement dans la table source déjà déclarée
  dans le catalogue (`StatMetric.Source`). Pas de nouvelle table, pas de migration, pas de compteur parallèle.
  L'XP depuis toujours reste lue dans `player_skills`, les quêtes dans `quest_progress`.

## Travail effectué
- **Plugin** (PR #834) :
  - `StatisticsRepository.lifetimeTotals(metric, limit, visibility, excluded)` : somme de toute la source, avec la
    visibilité du profil (#55) et les exclusions de modération appliquées dans le SQL ; refuse les métriques d'XP.
  - `WebSnapshotWriter` : collecte asynchrone séparée des fiches (`ProfileSources`) — XP par piste, quêtes
    terminées et `stats` pour ENTITY_KILL, BLOCK_BREAK, FISH_CAUGHT, WAYPOINT_DISCOVERED, CITY_DISCOVERED,
    WAYSTONE_DISCOVERED, STORY_COMPLETED ; **tous** les joueurs visibles (plafond 1 000, au-delà on garde les
    plus fortes XP globales) ; une métrique sans valeur est absente, jamais un 0 inventé. Les classements
    (top 10) sont inchangés.
- **Portail** (PR #834) : liste blanche `stats` dans `PublicPlayerData` (seuls des identifiants
  `MAJUSCULES_SOULIGNÉS` avec une valeur numérique) ; section « Statistiques » de `/joueurs/<pseudo>`, chaque
  ligne datée de son début de suivi (`statistics.catalog.metrics[].trackedSince`).
- **Portail** (PR #835, écart trouvé sur les vraies données) : sur la fiche, ENTITY_KILL n'a pas de filtre
  (animaux compris) : 101 / 81 contre 99 / 60 au classement « Monstres » (familles hostile + boss). Le libellé
  « Monstres vaincus » était trompeur : il devient « Créatures tuées — N créatures, animaux compris ». L'unité
  est aussi mise au singulier (« 1 ville »).
- Déploiements DEV et AWS, vérifications réelles (détail plus bas).

## Fichiers créés
- ce rapport

## Fichiers modifiés
- plugin : `database/StatisticsRepository.java`, `web/WebSnapshotWriter.java` ; test `web/PublicPortalExportTest.java`
- web-api : `privacy/PublicPlayerData.java`, `site/PlayersPageHandler.java` ; tests `privacy/PublicPlayerDataTest.java`,
  `PublicPortalV1Test.java`
- docs : `WEB_PORTAL.md`, `WEB_API.md`, `current_state.md`, `deployment/SERVER_CHANGELOG.md`,
  `MANUAL_TEST_PLAN.md` (TC-327 partie E), `SUIVI_VALIDATIONS_DEV.md`, `claude-reports/README.md`

## Base de données / migrations
Aucune. Lectures seules, asynchrones, sur des tables existantes. Aucun joueur modifié ni réinitialisé.

## Configuration / données
Aucun changement de configuration (DEV `config.yml` et `portal-snapshot.properties` inchangés ; AWS
`web-api.properties` et nginx inchangés ; délai nginx de 150 s de PlugAdmin non touché). Accès bêta inchangé
(compte partagé `beta`, mot de passe uniquement dans `~/.config/rpgquest/portal-beta-access.txt` sur AWS — jamais
affiché pendant les vérifications : authentification lue par un script, sans sortie du secret ni des URL de
redirection).

## Tests automatiques
- Locaux ciblés, verts : `PublicPortalExportTest` 13 (dont 3 nouveaux : fiche avec `stats` sans UUID et sans valeur
  inventée, retry d'activité non doublé ; totaux sans les profils masqués ni les exclus, XP refusée ; 25 joueurs →
  25 fiches, plus de limite au top 10), `StatisticsRepositoryTest` 15, `WebSnapshotWriterTest` 10,
  `PublicPortalV1Test` 9 (section Statistiques, date de suivi, singulier, aucune section sans valeur),
  `PublicPlayerDataTest` 5 (clés `stats` non conformes ou non numériques rejetées).
- CI GitHub : PR #834 verte (plugin, web-api, control-panel, scripts, artefacts) et fusion `9d0cd6c5` verte ;
  PR #835 verte et fusion `e026766c` verte.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-327 partie E. Une étape à la fois (5 min) :
1. Ouvrir https://lodyquests.lodylands.com/joueurs/LoDyMcFly avec l'accès bêta → section **Statistiques**.
2. Comparer « Waypoints découverts » (89) et « Villes découvertes » (3) à ce que tu as découvert en jeu.
3. En jeu, tuer un zombie, attendre au plus 15 min, recharger : « Créatures tuées » passe de 101 à 102.
4. Fiche de Madix666 : pas de section Statistiques.
Aucun test en jeu n'a été fait par Claude.

## Résultat attendu
Chaque testeur visible a une fiche, avec son XP, ses quêtes et ses statistiques réelles du serveur, actualisées
toutes les 15 min, sans aucune donnée privée.

## Preuves (valeurs réelles relevées)
- **Exports reçus sur AWS** après redémarrage : n° 1 à 21:45:57, n° 2 à 22:01:02, n° 3 à 22:16:07 → **intervalle
  observé ≈ 905 s** entre exports planifiés (`/health` `sequence` 1→2→3 ; journal portail « Instantané du
  serveur de jeu reçu : n° 2, 18193 caractères »).
- **Joueurs synchronisés** : 3 (`/health` `export.profiles: 3`) — LoDyMcFly, Madix666, Rondoudou9000.
- **Instantané stocké** (`/var/lib/lodyquests-portal/web-export/snapshot.json`, 18 195 o) relu : 0 UUID, 0 adresse
  IP, aucune clé `world`, `x`, `inventory`, `location`.
- **Statistiques réellement disponibles par fiche** :

| Joueur | XP Global / Combat / Minage / Agri | Quêtes | Créatures tuées | Blocs cassés | Waypoints | Villes |
|---|---|---|---|---|---|---|
| LoDyMcFly | 4 374 / 2 205 / 3 615 / 16 | 12 | 101 | 7 | 89 | 3 |
| Rondoudou9000 | 3 832 / 1 560 / 4 265 / — | 5 | 81 | — | 15 | 1 |
| Madix666 | 1 393 / 645 / 1 665 / — | 1 | — | — | — | — |

  Pêche, pierres de voyage et histoires : aucune valeur sur DEV (aucune ligne dans leurs tables), donc absentes.
  Début de suivi : créatures 16/10, blocs 23/10, waypoints 09/09, villes 13/10.
- **Cohérence** : valeurs des fiches comparées aux classements du même instantané, calculés par un autre chemin
  de code (`BoardCatalogExport`) : waypoints 89 / 15 = `waypoints_total`, villes 3 / 1 = `cities_total`,
  blocs 7 = `blocks_week`, quêtes 12 / 5 / 1 = `quests_total` (et = PlugAdmin `quest.player.status`, relevé #421).
  **Écart expliqué** : créatures 101 / 81 sur la fiche contre `mobs_total` 99 / 60, parce que le classement filtre
  `entity-family: [boss, hostile]` et pas la fiche (animaux compris) — d'où le libellé corrigé par #835.
  XP inchangée depuis le relevé #421 (4 374 / 3 832 / 1 393).
- **Pages avec l'accès bêta** (portail `e026766c`) : `/joueurs` 200 (3 pseudos + « Données relevées »),
  `/joueurs/LoDyMcFly` 200 (Statistiques : « 101 créatures, animaux compris », « 7 blocs », « 89 waypoints »,
  « 3 villes », « depuis le … »), `/joueurs/Rondoudou9000` 200 (« 1 ville »), `/joueurs/Madix666` 200 (sans
  section Statistiques), `/classements` 200, `/classements/tous` 200, recherche `?pseudo=rondou` 303 →
  `/joueurs/Rondoudou9000`, `?pseudo=zzzz` 200 « Aucun profil public ». **Sans accès** : `/joueurs`,
  `/joueurs/LoDyMcFly`, `/classements` 401.
- **Redémarrage DEV** : `list` à 21:44:02 = LoDyMcFly en ligne → `say [Maintenance] …` à 21:44:05, attente
  jusqu'à 21:45:07 (62 s), `scripts/verygames-restart.sh` → ONLINE à 21:45:53 ; RCON répond, 5 plugins chargés
  (Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit) ; portail « arrêt annoncé » 21:45:12 → « nouveau
  démarrage reçu » 21:45:53 → instantané n° 1 21:45:57 ; heartbeat FRESH / ONLINE.
- **Charge** : collecte 16 ms dont 11 ms thread principal au premier export (démarrage), puis 8 ms dont 0 ms ;
  instantané 18,2 Ko pour 3 joueurs (+ ≈ 0,1 Ko par rapport à #421) ; TPS 20,0 et MSPT 0,08–0,1 ms aux
  exports n° 2 et 3. Les 7 requêtes de fiches de plus sont des agrégats indexés par joueur, sur le fil asynchrone.
- **Erreur réseau / réessai / relevé périmé** : mécanisme d'envoi inchangé par ce lot ; prouvé en réel par #421
  (502 → réessai STORED 30 s après, données retirées au-delà d'1 h) et couvert par `RealSnapshotSyncTest`. Non
  rejoué ce soir pour ne pas couper le canal des testeurs.

## Reset / retour à l'état initial
Rien à réinitialiser (aucune donnée écrite). Retirer toutes les données joueurs du portail : `player-data-public=false`
dans `/etc/lodyquests-portal/web-api.properties` + `sudo systemctl restart lodyquests-portal`.

## Déploiement VeryGames
### À transférer
Fait : JAR `9d0cd6c5` (4 393 456 o, SHA-256 `6503f237…`, retéléchargé depuis le FTP : identique) par
`scripts/deploy-verygames.sh -y` (« Validation : CI verte sur 9d0cd6c »), 21:43. Portail AWS : `9d0cd6c` à 21:42,
puis `e026766c` à 22:17 (`scripts/web-portal/deploy.sh`, `/health` UP).
### Ne PAS transférer/altérer
`config.yml`, `portal-snapshot.properties`, `data.db`, mondes, contenus, joueurs.
### Redémarrage requis
Oui (JAR) — fait à 21:45 avec LoDyMcFly prévenu 62 s avant.
### Migration automatique
Aucune.

## Rollback
- JAR : `scripts/rollback-verygames.sh` avec `rpgquest-20261024T194326Z-predeploy.jar` (= `455379aa`, JAR #421) puis
  `scripts/verygames-restart.sh` (prévenir les joueurs). Un JAR antérieur exporte les fiches limitées au top 10.
- Portail : `scripts/web-portal/rollback.sh` (`releases/20261024-221730` = `9d0cd6c`, `releases/20261024-214255`
  = `69a6c06`). Un portail antérieur ignore simplement `stats`.

## Logs / diagnostic
- `/health` public : `sync.export.profiles` = nombre de fiches exportées, `collectMs` / `mainThreadMs`.
- Plugin : « Instantané portail : N octets, envoi X en Y ms » ; portail : `journalctl -u lodyquests-portal | grep Instantané`.

## Documentation mise à jour
`WEB_PORTAL.md` (contrat `profiles[].stats`, plafond, différence fiche / classement Monstres), `WEB_API.md` (liste
blanche), `current_state.md`, `SERVER_CHANGELOG.md`, `MANUAL_TEST_PLAN.md` (TC-327 E), registre
`SUIVI_VALIDATIONS_DEV.md` (ligne 15a « À tester » + décision visible ; version de `main` déjà intégrée, rien à
reporter), index des rapports.

## Limitations / travail restant
- Les totaux d'activité ne remontent qu'au début de leur journal (créatures 16/10, blocs 23/10) : rien avant n'est
  reconstitué, la fiche affiche la date.
- Pas de saison ni d'événement réel (aucun `statistics.yml` sur DEV) ; les classements jour / semaine / mois du
  catalogue #734 sont, eux, publiés.
- Pas de rang par statistique sur la fiche (seulement les médailles des classements existants) → #56 si souhaité.
- Décisions propriétaire : passage éventuel à 10 min (mesures toujours sans risque), ouverture publique (#55).

## Prochaine étape suggérée
Propriétaire : TC-327 E (5 min). Sessions : #56 (pages Joueurs / Fiche / Classements). #54 laissé ouvert.
