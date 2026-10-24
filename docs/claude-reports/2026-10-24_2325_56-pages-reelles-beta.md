# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-24
* Heure : 23:25
* Sujet : #56 — lot 3 de la bêta portail : pages Joueurs, Fiche, Classements et Recherche sur les vraies données
* Statut : WAITING_OWNER (livré et déployé sur le portail AWS, vérifié sur les vraies données ; recette navigateur TC-327 F à faire par le propriétaire)
* Branche Git : `fix/56-pages-reelles-indisponibles` (PR #838), `fix/56-fiche-quete-singulier` (PR #839), fusionnées dans `chore/257-ci-github-actions` ; rapport : `docs/56-rapport-pages-reelles`
* Commit actuel si disponible : `64808ee6` (fusion #838), `c6f72791` (fusion #839, portail AWS servi)
* Début de la tâche : 2026-10-24 22:19:54
* Fin de la tâche : 2026-10-24 23:26:08
* Durée totale : 01:06:14

## Demande
Session lqorch sur #56, specs `2026-10-24b-portail-beta-vrais-joueurs.md` (prioritaire) et
`2026-10-24-portail-donnees-reelles.md` (lot 3) : pages Joueurs, Fiche, Classements et Recherche alimentées par
les vrais instantanés, avec cache et date de synchronisation, `/demo` séparé, cohérence vérifiée sur au moins
3 joueurs, accès bêta, preuves réelles, procédure de validation très courte.

## Analyse
État réel relevé au début (portail `e026766c`, JAR DEV `9d0cd6c5`) :
- Les lots #421 et #54 avaient déjà branché les vraies pages sur l'instantané réel : `/joueurs`,
  `/joueurs/<pseudo>`, `/classements`, `/classements/tous` et la recherche répondaient 200 / 303 avec les
  3 vrais testeurs, la date de relevé, derrière l'accès bêta nginx (401 sans accès, `/demo/` et `/health`
  ouverts). Le « cache » est l'instantané stocké sur AWS : aucune consultation n'interroge Minecraft.
- Écarts trouvés en parcourant les vraies pages :
  1. **Données indisponibles mal dites** : sans relevé complet récent (redémarrage DEV, coupure, relevé de plus
     d'une heure alors que le relevé d'état reste frais), la liste disait « Aucun profil public pour le
     moment », une fiche existante répondait **404 « aucun profil public pour ce pseudo »**, les classements
     « Personne pour l'instant ». Le bandeau d'état ne couvre pas le cas « serveur en ligne mais données
     périmées ». La spec demande une indication explicite d'un relevé ancien ou indisponible.
  2. **Pluriels** : « Madix666 1 quêtes » (classements) et « 1 quêtes terminées » (fiche).
  3. **Textes obsolètes** depuis #54 : « Joueurs présents dans au moins un classement » (liste, 404) alors que
     tous les joueurs visibles ont une fiche ; la FAQ et la page Confidentialité ne citaient pas les
     statistiques publiées par #54.
- PlugAdmin n'a aucune vue d'XP (seulement `quest.player.status` pour les quêtes, comparé par #421) et le jeu
  n'affiche pas l'XP totale (`/skills` = niveau + XP dans le niveau) : la comparaison avec Minecraft passe par
  un gain observé (étape manuelle 25).

## Travail effectué
- **PR #838** (`web-api`) :
  - `LeaderboardsPageHandler.unavailable(state)` : avis « Profils et classements momentanément indisponibles… »
    dès que `ServerState.SYNCED_AT` est absent (aucune donnée de jeu affichée), en tête de `/classements`,
    `/classements/tous` et de l'aperçu de l'accueil.
  - `PlayersPageHandler` : liste, fiche et recherche répondent **503** + `Retry-After: 300` + le même avis
    (`noindex`), au lieu d'une liste vide ou d'un 404 trompeur. L'aperçu `/demo` n'est pas concerné.
  - `StatLabels.unit(metric, value)` partagé (« 1 quête », « 1 ville ») : classements et fiche (remplace
    `PlayersPageHandler.singular`).
  - Textes : liste et 404 sans « présents dans un classement » ; FAQ (« Ta fiche apparaît alors sur la page
    Joueurs ; un classement ne montre que les meilleurs ») ; Confidentialité : statistiques de jeu listées,
    délai « en général sous un quart d'heure ».
- **PR #839** : « 1 quête terminée » sur l'en-tête de la fiche (relevé sur la vraie fiche de Madix666 après
  le déploiement de #838) ; tests d'unités dans `StatLabelsTest`.
- Deux déploiements du portail AWS par `scripts/web-portal/deploy.sh` (CI des fusions vertes).

## Fichiers créés
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/site/StatLabelsTest.java`
- ce rapport

## Fichiers modifiés
- `web-api` : `site/LeaderboardsPageHandler.java`, `site/PlayersPageHandler.java`, `site/HomePageHandler.java`,
  `site/BoardView.java`, `site/StatLabels.java`, `site/InfoPageHandler.java`, `site/PrivacyPageHandler.java` ;
  test `PublicPortalV1Test.java`
- docs : `WEB_PORTAL.md` (§ 8 : avis d'indisponibilité, 503, unités), `MANUAL_TEST_PLAN.md` (TC-327 partie F),
  `SUIVI_VALIDATIONS_DEV.md` (ligne 15e), `claude-reports/README.md`

## Base de données / migrations
Aucune. Rien n'est écrit côté plugin ni côté portail ; aucun joueur modifié ni réinitialisé.

## Configuration / données
Aucun changement : `web-api.properties`, nginx (accès bêta, délai de 150 s de PlugAdmin), `config.yml` et
`portal-snapshot.properties` DEV intacts. Compte bêta partagé `beta` conservé.

**Incident de session (secret)** : lors d'une vérification de la recherche, une commande `curl` avec
`%{redirect_url}` a affiché une fois l'URL de redirection contenant l'identifiant **et le mot de passe** de
l'accès bêta dans la sortie de la session locale (journal lqorch de la session, sur cette machine). Le secret
n'a été copié dans aucun fichier du dépôt, ticket, commit ni e-mail. Les vérifications suivantes n'ont plus
affiché de redirection. Rotation du mot de passe bêta = décision du propriétaire (les testeurs devraient
recevoir le nouveau) ; la procédure est dans `~/.config/rpgquest/portal-beta-access.txt` (« Ajouter un
testeur » / `htpasswd`).

## Tests automatiques
- Locaux : `:web-api:test` complet vert (176 tests) après #838 ; après #839 : `PublicPortalV1Test` 11 (dont 2
  nouveaux : relevé de 2 h → 503 + avis sur `/joueurs`, `/joueurs/Alice`, `/joueurs?pseudo=ali`, avis sur `/`,
  `/classements`, `/classements/tous`, aucun pseudo, `/demo/joueurs` sans avis ; données fraîches → aucun
  avis, « 3 quêtes terminées »), `PublicPortalRefonteTest` 11, `StatLabelsTest` 2 (nouveau).
- CI GitHub : PR #838 verte et fusion `64808ee6` verte ; PR #839 verte et fusion `c6f72791` verte.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` TC-327 partie F, navigateur avec l'accès bêta, une
étape à la fois (3 min) :
1. `/joueurs` : 3 testeurs + « Données relevées sur le serveur le … » (moins de 15 min).
2. Chercher `rondou` → fiche Rondoudou9000 ; `o` → « 2 profils correspondent » ; `zzzz` → « Aucun profil
   public ne correspond ».
3. Fiche de Madix666 : « 1 quête terminée » ; `/classements` : « Madix666 1 quête ».
4. Noter l'XP Minage de ta fiche, miner un minerai en jeu, recharger au plus 15 min après : même gain que
   `/skills`.
Aucun test en jeu n'a été fait par Claude.

## Résultat attendu
Les vraies pages montrent les vrais joueurs, à jour toutes les 15 min, et disent clairement quand les données
sont momentanément absentes au lieu de laisser croire qu'il n'y a personne.

## Preuves (valeurs réelles relevées)
- **Exports reçus sur AWS** (journal `lodyquests-portal`, « Instantané du serveur de jeu reçu ») : n° 1 21:45:57,
  n° 2 22:01:02, n° 3 22:16:07, n° 4 22:31:12, n° 5 22:46:17, n° 6 23:01:22, n° 7 23:16:27 → **intervalle
  observé 905 s** entre chaque export planifié, 18 193 caractères chacun. Les deux redémarrages du portail
  (22:54, 23:24) n'ont rien perdu : `/health` `sequence` 5 puis 7 juste après, `data: FRESH`.
- **Joueurs synchronisés** : 3 (`/health` `sync.export.profiles: 3`) — LoDyMcFly, Madix666, Rondoudou9000.
- **Instantané stocké** relu (export n° 5) : 0 UUID, 0 adresse IP, aucune clé `world`, `x`, `y`, `z`,
  `inventory`, `location`, `ip`, `uuid`, `email`.
- **Statistiques réellement disponibles** : XP par piste (Global, Combat, Minage, Agriculture ; Pêche et
  Exploration vides sur DEV), quêtes terminées, créatures tuées, blocs cassés, waypoints et villes découverts ;
  23 classements du catalogue #734. Pêche, pierres de voyage, histoires : aucune valeur sur DEV.
- **Cohérence page = instantané** (script, 3 joueurs, export n° 5) : chaque valeur de chaque fiche (XP globale,
  chaque piste, quêtes, chaque statistique) présente telle quelle — 0 manquant sur 23 valeurs ; classements
  XP globale, Quêtes, XP Minage : les 9 lignes identiques. Valeurs : LoDyMcFly 4 374 / 2 205 / 3 615 / 16, 12
  quêtes, 101 créatures, 7 blocs, 89 waypoints, 3 villes ; Rondoudou9000 3 832 / 1 560 / 4 265, 5 quêtes, 81,
  15, 1 ; Madix666 1 393 / 645 / 1 665, 1 quête. Quêtes = PlugAdmin `quest.player.status` (relevé #421, valeurs
  inchangées depuis). Écart expliqué (déjà relevé par #54) : créatures 101 / 81 sur la fiche contre « Monstres »
  99 / 60 (familles hostile + boss seulement). XP face à Minecraft : non comparable directement (le jeu
  n'affiche pas l'XP totale) → étape manuelle 4.
- **Pages avec l'accès bêta** (portail `64808ee` puis `c6f7279`) : `/joueurs` 200 (3 pseudos, « Données
  relevées », plus de « présents dans au moins un classement ») ; `/joueurs/LoDyMcFly`, `/joueurs/Rondoudou9000`,
  `/joueurs/Madix666` 200 (« 12 quêtes terminées », « 5 quêtes terminées », « 1 quête terminée ») ;
  `/classements` 200 (« Madix666 1 quête ») ; `/classements/tous` 200 ; `/` 200 ; recherche `rondou` 303 vers
  la fiche, `o` 200 « 2 profils correspondent », `zzzz` 200 « Aucun profil public » ; `/joueurs/Inconnu42` 404 ;
  aucun avis d'indisponibilité (données fraîches). `/demo/joueurs` 200 avec les `Demo_*`. **Sans accès** :
  `/`, `/joueurs`, `/joueurs/LoDyMcFly`, `/classements` → 401.
- **Charge** (`/health`) : collecte 7–8 ms, 0 ms sur le thread principal, TPS 20,0, MSPT 0,08–0,09 ms ; le lot ne
  change rien côté plugin.
- **Redémarrage / erreur réseau / réessai / relevé périmé** : aucun redémarrage DEV dans cette session (aucun
  JAR à changer). L'affichage d'un relevé périmé est couvert par le nouveau test (relevé de 2 h) ; le réessai
  réel (502 → STORED) et la reprise après redémarrage ont été prouvés par #421 et #54.

## Reset / retour à l'état initial
Rien à réinitialiser. Masquer toutes les données joueurs du portail : `player-data-public=false` dans
`/etc/lodyquests-portal/web-api.properties` + `sudo systemctl restart lodyquests-portal`.

## Déploiement VeryGames
### À transférer
Rien sur VeryGames (aucun changement de `src/`). Portail AWS : `64808ee` à 22:54 (sauvegarde
`releases/20261024-225451`), puis `c6f7279` à 23:24 (sauvegarde `releases/20261024-232422`),
`scripts/web-portal/deploy.sh`, `/health` UP.
### Ne PAS transférer/altérer
JAR DEV (`9d0cd6c5`), `config.yml`, `portal-snapshot.properties`, `data.db`, nginx, `web-api.properties`.
### Redémarrage requis
Non pour Minecraft. Le service du portail est redémarré par `deploy.sh` (quelques secondes).
### Migration automatique
Aucune.

## Rollback
`scripts/web-portal/rollback.sh` : `releases/20261024-232422` = `64808ee` (sans le singulier de la fiche),
`releases/20261024-225451` = `e026766c` (avant ce lot).

## Logs / diagnostic
- `/health` : `sync.data` (FRESH / STALE), `sync.lastSync`, `sync.sequence`, `export.profiles`.
- `journalctl -u lodyquests-portal | grep Instantané`.
- Sur une page : avis « momentanément indisponibles » = aucun relevé complet de moins de
  `snapshot-data-max-age-seconds` affiché.

## Documentation mise à jour
`WEB_PORTAL.md`, `MANUAL_TEST_PLAN.md` (TC-327 F), registre `SUIVI_VALIDATIONS_DEV.md` (ligne 15e réécrite pour
la bêta réelle, ancien contenu TC-414-bis conservé dans la ligne ; version de `main` déjà intégrée, rien à
reporter), index des rapports. Pas d'entrée au changelog serveur : aucun changement VeryGames.

## Limitations / travail restant
- Pas de rang par statistique sur la fiche (seulement les médailles des classements existants).
- Pas de saison ni d'événement réel (aucun `statistics.yml` sur DEV).
- Décisions propriétaire : rotation éventuelle du mot de passe bêta (incident ci-dessus), passage à 10 min
  (mesures toujours sans risque), ouverture publique (#55, reportée), garder l'aperçu fictif public.

## Prochaine étape suggérée
Propriétaire : TC-327 F (3 min) puis TC-327 E. #56 laissé ouvert.
