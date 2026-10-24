# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-24
* Heure : 21:07
* Sujet : #421 — instantanés réels VeryGames → AWS toutes les 15 min, vrais joueurs sur le portail en bêta restreinte (lot 1 de la bêta du 24/10)
* Statut : WAITING_OWNER (livré, déployé, vérifié de bout en bout ; recette navigateur TC-327 D à faire par le propriétaire)
* Branche Git : `feature/421-instantanes-reels` (PR #831), `fix/421-premier-export-planifie` (PR #832), toutes deux fusionnées dans `chore/257-ci-github-actions` ; rapport : `docs/421-rapport-instantanes-reels`
* Commit actuel si disponible : `455379aa` (fusion #832, JAR DEV), `69a6c068` (fusion #831, portail AWS)
* Début de la tâche : 2026-10-24 19:17:01
* Fin de la tâche : 2026-10-24 21:07:23
* Durée totale : 01:50:22

## Demande
Session lqorch sur #421, specs `2026-10-24b-portail-beta-vrais-joueurs.md` (prioritaire, avec le complément de
20:45) et `2026-10-24-portail-donnees-reelles.md` : finaliser le transfert VeryGames → AWS d'instantanés RÉELS
(15 min, configurable à 10, versionnés, atomiques, reprises sans doublon, relevé périmé signalé), tester
redémarrage / erreur réseau / réessai, mesurer la charge, afficher les vrais joueurs en bêta restreinte (accès
limité, sans secret dans Git ni les tickets), jamais d'UUID, d'IP, d'inventaire ni de coordonnées, aucun joueur
réinitialisé. Complément 20:45 : installer le correctif #832, prouver l'export planifié toutes les 15 min,
prévenir les joueurs avant redémarrage, fournir des preuves concrètes.

## Analyse
- Existant : l'instantané complet (`WebSnapshotWriter`, schéma v2 : classements, statistiques, profils) et son
  envoi au portail existaient, mais n'étaient produits que si `web-export.enabled: true` dans `config.yml` — que
  la règle « jamais d'upload de `config.yml` » interdit de basculer sur DEV — et à 30 s, non numérotés, sans
  réessai. Le portail retirait les données d'un instantané de plus de 120 s : à 15 min, rien ne se serait affiché.
- L'instantané d'arrêt (`online=false`, données vides) remplaçait les données du portail à chaque redémarrage.
- **Bug découvert au déploiement** : `now - lastWriteMillis` avec `lastWriteMillis = Long.MIN_VALUE` déborde en
  négatif → aucun export planifié n'est jamais parti (seul `triggerNow()` produisait un instantané). Les tests
  n'utilisaient que `triggerNow()`. Corrigé par #832 avec un test de régression du premier tick planifié.
- Choix : activer l'envoi depuis le fichier propre au serveur `portal-snapshot.properties` (hors Git, contient
  déjà le jeton) plutôt que `config.yml` — réversible, sans changer la configuration livrée.

## Travail effectué
- **Plugin** (PR #831) : clés `snapshot-enabled`, `snapshot-interval-seconds` (900 par défaut, 600–3600),
  `snapshot-profile-visibility` ; configuration d'export effective = `config.yml` relayée par ce fichier (aussi
  pour `/profile web` et l'aperçu des classements PlugAdmin) ; instantané numéroté `bootId` + `sequence`, bloc
  `export` (intervalle, durée de collecte totale et part thread principal, nombre de profils) ; envoi réessayé
  2 fois (30 s, 2 min) avec le même corps si injoignable ou 429/5xx, jamais sur 400/401/413 ; journal d'une ligne
  par envoi (octets, résultat, durée, essais) ; instantané d'arrêt local seulement ; TPS/MSPT dans le relevé d'état.
- **Plugin** (PR #832) : premier export planifié immédiat, suivants à l'intervalle.
- **Portail** (PR #831) : doublon / retard d'un instantané numéroté accusés sans écriture (même règle que le relevé
  d'état), non numéroté accepté ; `snapshot-data-max-age-seconds` (3600) : données affichées entre deux envois
  tant que le serveur est en ligne, retirées au-delà ; `syncedAt` ; « Données relevées sur le serveur le … » sur
  Joueurs et fiches ; `/health` : `sync` (data, lastSync, sequence, export) et `load` (tps, mspt).
- **nginx** : modèle du vhost avec `include` du snippet d'accès bêta, `auth_basic off` des routes machine
  (`/api/snapshot`, `/api/link/*` : leur en-tête Authorization porte un jeton), `/health` et `/demo/` publics ;
  modèle `nginx-lodyquests-portal-beta.conf.example`.
- Déploiements et vérifications réelles (détail plus bas).

## Fichiers créés
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/ingest/RealSnapshotSyncTest.java`
- `scripts/web-portal/nginx-lodyquests-portal-beta.conf.example`
- ce rapport

## Fichiers modifiés
- plugin : `bootstrap/RPGQuestBootstrap`, `web/WebSnapshotWriter`, `web/push/ServerStatusReporter`,
  `SnapshotPushClient`, `SnapshotPushConfig`, `SnapshotPushConfigLoader` ; tests `WebSnapshotWriterTest`,
  `ServerStatusReporterTest`, `SnapshotPushClientTest`, `SnapshotPushConfigLoaderTest`
- web-api : `ServerState`, `SnapshotStore`, `WebApiConfigLoader`, `WebApiMain`, `api/HealthHandler`,
  `ingest/SnapshotIngestHandler`, `SnapshotIngestSettings`, `StatusReportIngest`, `site/PlayersPageHandler`
- `scripts/portal-snapshot.properties.example`, `scripts/web-portal/nginx-lodyquests-portal.conf`,
  `scripts/web-portal/web-api.properties.example`, `web-api/web-api.properties.example`
- docs : `WEB_API.md`, `deployment/WEB_PORTAL_AWS.md`, `RPGQUEST_BIBLE.md`, `current_state.md`,
  `MANUAL_TEST_PLAN.md` (TC-327 partie D), `deployment/SERVER_CHANGELOG.md`, `SUIVI_VALIDATIONS_DEV.md`,
  `claude-reports/README.md`

## Base de données / migrations
Aucune. Lectures existantes uniquement (asynchrones), aucun joueur modifié ni réinitialisé.

## Configuration / données
- DEV `RPGQuest/portal-snapshot.properties` : + `snapshot-enabled=true`, `snapshot-interval-seconds=900`,
  `snapshot-profile-visibility=public` (copie locale `~/.config/rpgquest/portal-snapshot.properties`, ancienne
  version `.bak-pre421-reel-20261024`). **`config.yml` DEV inchangé** (`web-export.enabled: false`).
- AWS `web-api.properties` : `player-data-public=true` (copie `.bak-pre421-beta-20261024-*`).
- AWS nginx : vhost `lodyquests-portal` modifié (copie `sites-available/lodyquests-portal.bak-pre421-beta-20261024-193050`),
  `/etc/nginx/snippets/lodyquests-portal-beta.conf`, `/etc/nginx/lodyquests-portal-beta.htpasswd` (compte partagé
  `beta`). Le délai nginx de 150 s de PlugAdmin n'a pas été touché.
- **Accès bêta** : identifiant `beta`, mot de passe dans `~/.config/rpgquest/portal-beta-access.txt` sur la
  machine AWS (600, hors Git), avec la commande pour ajouter un testeur. Incident : pendant une vérification, une
  sortie de `curl` (URL de redirection) a affiché le premier mot de passe dans la session ; il a été **renouvelé
  aussitôt** (l'ancien répond 401, vérifié). Aucun secret dans Git, les tickets ou les journaux du dépôt.

## Tests automatiques
- `./gradlew :web-api:test` complet : vert (dont `RealSnapshotSyncTest` 3 : doublon / retard / redémarrage /
  non numéroté / numérotation incomplète 400 ; données d'un instantané de 10 min affichées puis retirées à 2 h,
  `syncedAt`, `/health` sync ; TPS/MSPT bornés).
- Plugin `web.push.*` + `WebSnapshotWriterTest` : 23 verts (numérotation croissante, arrêt local seulement,
  réessai avec corps identique sur 503 et aucun réessai sur 401, 502 = passager, surcharge de config bornée,
  TPS/MSPT ⊂ clés acceptées par le portail, premier tick planifié sans forçage).
- CI GitHub : PR #831 verte (un job « Scripts » annulé par le runner relancé : vert), fusion `69a6c068` verte ;
  PR #832 verte ; fusion `455379aa` : `ClaimArrivalResolverTest` en échec une fois (sans lien, vert sur les deux
  runs de PR), relancé : vert.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-327 partie D. Procédure courte, une étape à la fois :
1. Ouvrir https://lodyquests.lodylands.com : le navigateur demande un identifiant → `beta` + mot de passe remis.
2. **Joueurs** : LoDyMcFly, Rondoudou9000, Madix666, « Données relevées sur le serveur le … ».
3. Ta fiche : comparer Combat / Minage / Agriculture et quêtes terminées avec `/skills` en jeu.
4. **Classements** : mêmes joueurs et rangs.
5. Recherche « rondou » → fiche de Rondoudou9000.
Aucun test en jeu n'a été fait par Claude.

## Résultat attendu
Le portail montre, aux seuls testeurs munis de l'accès bêta, les vrais joueurs, leur XP par piste, leurs quêtes
terminées, les classements réels et la recherche, actualisés toutes les 15 min et datés.

## Preuves (valeurs réelles relevées)
- **Derniers exports reçus sur AWS** : n° 1 généré à 20:49:01 (premier tick après redémarrage), n° 2 à 21:04:01
  → **intervalle observé 900,4 s** entre deux exports planifiés (`/health` : `"sequence":2`, `lastSync`
  `2026-10-24T19:04:01.926Z`). Journal portail : « Instantané du serveur de jeu reçu : n° 1, 18033 caractères »,
  « n° 2, 18032 caractères ».
- **Joueurs synchronisés** : 3 (= les 3 joueurs connus de `player.catalog` PlugAdmin) : LoDyMcFly, Madix666,
  Rondoudou9000.
- **Statistiques réellement disponibles** : XP par piste depuis toujours (`RPG_XP_GLOBAL`, `COMBAT`, `MINING`,
  `FARMING` ; `FISHING` et `EXPLORATION` vides faute d'XP), quêtes terminées, et les 23 classements du catalogue
  #734 (monstres jour/semaine/mois/total, zombies/squelettes/araignées/creepers semaine, boss, blocs, minerais,
  bûches, cultures, poissons, waypoints, villes, pierres de voyage, quêtes semaine/total, histoires, XP jour/semaine).
  Portée « saison » et événements absents (aucun `statistics.yml` sur DEV).
- **Pages avec l'accès bêta** : `/joueurs` 200 (3 pseudos + date de relevé), `/joueurs/LoDyMcFly` 200 (Combat
  2 205, Minage 3 615, Agriculture 16, quêtes 1er), `/classements` 200, `/classements/tous` 200, `/` 200,
  recherche `?pseudo=rondou` 303 → `/joueurs/Rondoudou9000` 200, `?pseudo=zzzz` « Aucun profil public ne
  correspond ». **Sans accès** : `/`, `/joueurs`, `/classements` 401 ; `/health` et `/demo/joueurs` 200 (joueurs
  `Demo_*` seulement). Faux jeton sur `/api/snapshot` : 401 applicatif (route non bloquée par l'accès bêta).
- **Confidentialité** : instantané stocké sur AWS (18 081 o) relu : 0 UUID, 0 adresse IP, aucune clé `world`,
  `x/y/z`, `inventory`, `location` ; clés racine `schemaVersion, generatedAt, server, players (vide),
  leaderboards, catalog, announcements, statistics, profiles, bootId, sequence, export`.

### Cohérence avec PlugAdmin et Minecraft (3 joueurs)
| Joueur | Portail : quêtes terminées | PlugAdmin `quest.player.status` COMPLETED | Portail XP Combat / Minage / Agri / Global |
|---|---|---|---|
| LoDyMcFly | 12 | 12 | 2 205 / 3 615 / 16 / 4 374 |
| Rondoudou9000 | 5 | 5 | 1 560 / 4 265 / — / 3 832 |
| Madix666 | 1 | 1 | 645 / 1 665 / — / 1 393 |

- Classements comparés à l'aperçu PlugAdmin (`leaderboard.preview`, calcul indépendant côté plugin) :
  `xp_week` portail 3 300 + 3 109 = **6 409** = total PlugAdmin 6 409 (2 joueurs) ; `mobs_total` 99 + 60 = **159**
  = total PlugAdmin 159 (2 joueurs). Aucun écart.
- Écart apparent expliqué : la piste Global n'est pas la somme des pistes (LoDyMcFly 4 374 < 5 836) — chaque gain
  sur une piste ne reflète qu'une **partie** de son montant sur Global (`ProgressionService`, miroir partiel).
- Madix666 absent de `xp_week` / `mobs_total` : aucune activité journalisée cette semaine / depuis le début du
  journal (16/10), cohérent avec « 2 joueurs » côté PlugAdmin.
- Côté Minecraft : l'XP par piste d'un joueur hors ligne n'est lisible par aucune commande console ; la
  comparaison avec `/skills` en jeu est l'étape 3 de la recette propriétaire (PENDING MANUAL VALIDATION).

### Redémarrage, erreur réseau, réessai, relevé périmé
- **Redémarrage** (20:48:15, LoDyMcFly prévenu par `say [Maintenance]` à 20:47:10, 65 s d'attente) : portail
  « arrêt annoncé » 20:48:19 → « nouveau démarrage reçu » 20:48:57 → instantané n° 1 20:49:01 ; RCON répond,
  5 plugins chargés (Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit), heartbeat ONLINE. Premier
  redémarrage (20:01, 0 joueur) : idem pour le relevé ; c'est l'absence d'instantané qui a révélé le bug #832.
- **Erreur réseau + réessai réels** : portail arrêté volontairement de 21:03:41 à 21:04:21 ; export n° 2 à
  21:04:02 → nginx 502 (journal plugin « … impossible : HTTP 502 »), réessai 30 s plus tard 21:04:32 → STORED
  (« Instantané portail : 18080 octets, envoi STORED en 30715 ms (2 essai(s)) », « rétabli »). Relevé d'état
  sauté à 21:03:57 (502) puis rétabli à 21:04:28. Aucun doublon : une seule écriture n° 2 côté portail.
- **Relevé périmé** : couvert par les tests automatiques (données d'un instantané de 2 h retirées, `sync.data`
  STALE ; relevé d'état STALE jamais OFFLINE) et par l'essai réel du 22/10 (jeton retiré → STALE). Non rejoué en
  réel ce soir pour ne pas couper le canal des testeurs plus longtemps.

### Charge mesurée
| Mesure | Valeur |
|---|---|
| Collecte (lectures async comprises) | 20 ms (n° 1, au démarrage), 5 ms (n° 2) |
| Part sur le thread principal | 3 ms, puis 0 ms |
| Taille d'un instantané | 18 081 o (3 joueurs) |
| Envoi HTTPS | 348 ms (1 essai) |
| TPS (RCON `tps`, 1/5/15 min) à 21:03:56, 21:04:11, 21:04:46, 21:05:21 | 20,0 / 20,0 / 20,0 |
| MSPT (relevé d'état) | 3,34 ms à 21:04:27 ; 0,1 ms avg avant le chantier (RCON `mspt`) ; 45 ms juste après le démarrage (chargement des mondes, pas l'export) |
| Volume | ≈ 72 Ko/h d'instantanés (4 × 18 Ko) + relevés de 0,3 Ko toutes les 30 s |

Conclusion : passer à 10 min (`snapshot-interval-seconds=600`) est sans risque mesurable ; laissé à 15 min, valeur
demandée par le propriétaire.

## Reset / retour à l'état initial
- Retirer les données de joueurs du portail : `player-data-public=false` dans
  `/etc/lodyquests-portal/web-api.properties` + `sudo systemctl restart lodyquests-portal` (immédiat).
- Arrêter l'envoi : retirer les trois lignes `snapshot-*` de `portal-snapshot.properties` (sauvegarde
  `verygames-backups/extra-20261024T180031Z/`) + redémarrage DEV.
- Ouvrir le portail au public (décision #55) : supprimer `/etc/nginx/snippets/lodyquests-portal-beta.conf`,
  `sudo nginx -t && sudo systemctl reload nginx`.

## Déploiement VeryGames
### À transférer
Fait : JAR `69a6c068` (SHA-256 `38be0d39…`) à 20:00, puis JAR `455379aa` (4 389 656 o, SHA-256 `4096ebc7…`,
retéléchargé identique) à 20:46 ; `RPGQuest/portal-snapshot.properties` via `--also` (retéléchargé identique).
Portail AWS `69a6c06` (`scripts/web-portal/deploy.sh`, `/health` UP).
### Ne PAS transférer/altérer
`config.yml` (laisser `web-export.enabled: false`), `data.db`, mondes, contenus, joueurs.
### Redémarrage requis
Oui (fichier lu au démarrage) — fait par `scripts/verygames-restart.sh` à 20:01 (0 joueur) et 20:48 (LoDyMcFly
prévenu 65 s avant).
### Migration automatique
Aucune.

## Rollback
- JAR : `scripts/rollback-verygames.sh` avec `rpgquest-20261024T184640Z-predeploy.jar` (= `69a6c068`) ou
  `rpgquest-20261024T180031Z-predeploy.jar` (= `e9b4376a`, avant ce lot) puis `scripts/verygames-restart.sh`.
  Un JAR antérieur ignore les clés `snapshot-*` (relevé d'état seul).
- Portail : `scripts/web-portal/rollback.sh` (`releases/20261024-194558`) ; un portail ancien accepte les
  instantanés numérotés (clés en plus) mais retire les données après 120 s.
- nginx : remettre la sauvegarde du vhost, supprimer le snippet, `nginx -t`, reload.

## Logs / diagnostic
- `/health` public : `sync.data` FRESH / STALE / MISSING, `lastSync`, `sequence`, `export`, `load`.
- Portail : `journalctl -u lodyquests-portal | grep -E "Instantané|Relevé d'état"`.
- Plugin : « Instantané portail : N octets, envoi X en Y ms (Z essai(s)) » (lisible via l'action PlugAdmin
  `server.logs.tail`) ; « Portail : instantané complet envoyé toutes les 900 s » au démarrage.

## Documentation mise à jour
`WEB_API.md` (instantané réel en bêta), `deployment/WEB_PORTAL_AWS.md` (procédure bêta, diagnostic `sync`),
`RPGQUEST_BIBLE.md`, `current_state.md`, modèles `portal-snapshot.properties.example`,
`web-api.properties.example` (×2), vhost nginx + snippet bêta, TC-327 D, `SERVER_CHANGELOG.md`, registre
`SUIVI_VALIDATIONS_DEV.md` (ligne #421 « À tester » + décision visible), index des rapports.

## Limitations / travail restant
- Les profils exportés sont ceux présents dans un classement (top `leaderboard-size` = 10 par piste) : avec plus
  de 10 joueurs actifs, certains n'auraient pas de fiche → à traiter dans #54 / #56.
- Pendant un redémarrage (≈ 40 s) ou une coupure du relevé, le portail masque les données (état « hors ligne » /
  « non actualisé ») ; elles reviennent au premier relevé.
- Pas de saison ni d'événement réel (aucun `statistics.yml` sur DEV).
- Décisions propriétaire : ouverture publique (#55, reportée), éventuel passage à 10 min.

## Prochaine étape suggérée
Propriétaire : TC-327 D (5 min). Sessions : #54 (statistiques réelles persistées, profils au-delà du top 10),
puis #56 (pages). #421 laissé ouvert.
