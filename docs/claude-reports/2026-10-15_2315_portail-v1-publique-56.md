# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-15
* Heure : 23:15
* Sujet : Issue #56 — V1 publique du portail LodyQuests (actualités, classements, profils, infos)
* Statut : DONE (validation navigateur du propriétaire en attente, ticket laissé ouvert)
* Branche Git : feature/56-portail-v1-public → PR #444 fusionnée dans chore/257-ci-github-actions
* Commit actuel si disponible : b04a8ea (merge #444)
* Début de la tâche : 2026-10-15 22:47:10
* Fin de la tâche : 2026-10-15 23:14:52
* Durée totale : 00:27:42

## Demande
Session autonome lqorch sur #56 : créer la V1 publique du portail (accueil, actualités, premiers
classements basés sur #54, profils publics limités, infos/FAQ), pour un public de 13 ans, sur
téléphone et ordinateur, séparée de PlugAdmin, sans pseudo publié tant que le propriétaire n'a pas
validé #55 (`player-data-public=false`).

## Analyse
Existant (#52–#55) : portail `web-api` en ligne en HTTPS sur AWS, pages minimales (accueil avec les
annonces de l'instantané, `/leaderboards` en tableaux, `/joueurs`), liste blanche
`PublicPlayerData` qui relaie déjà saisons/événements (#54) mais qu'aucune page n'affichait, aucun
modèle d'actualités. Instantané absent sur AWS (#421 non fait), export désactivé : le site tourne en
mode dégradé, c'est attendu.

Choix :
- **Actualités en fichiers** `news-dir/<slug>.md` (en-tête `clé: valeur`, `---`, texte) : ni Git, ni
  serveur de jeu, ni second système d'administration (le ticket demande d'éviter un back-office
  concurrent de PlugAdmin) ; dossier root, lu seulement par le service. Édition PlugAdmin = suite.
- **Aucun HTML** accepté : mini-balisage échappé d'abord (paragraphes, `##`, `-`, gras, code, liens
  `https://` ou `/` uniquement — `javascript:`, `//hôte`, `/\\hôte` refusés).
- **Classements** : uniquement le modèle #54 via la liste blanche, top 10, rang du plugin.
- **Pas d'avatar de skin** : il faudrait envoyer l'UUID et l'IP du visiteur à un service tiers.
- **Pas de « statistiques globales »** chiffrées : aucune source agrégée fiable dans l'instantané ;
  l'accueil montre le statut et les périodes à la place (pas de donnée fictive).

## Travail effectué
- Paquet `webapi.news` : `NewsArticle`, `NewsCategory` (news, announcement, maintenance, event,
  release, story), `NewsParser` (validation stricte, limites de taille, date à fuseau obligatoire),
  `NewsRepository` (brouillons et dates futures invisibles, fichier invalide ignoré seul et
  journalisé une fois sans son contenu, relecture au plus toutes les 30 s, 500 fichiers / 64 Ko max).
- Pages : `/actualites` (pagination 10, filtre `?categorie=`), `/actualites/<slug>`,
  `/classements` (+ alias `/leaderboards`) avec onglets Depuis toujours / saison / événements
  (`?periode=`), `/joueurs` (recherche `?pseudo=` → 303, pagination 60, profil avec tuiles et rangs),
  `/infos` (rejoindre, premiers pas, règles de base, FAQ), `/robots.txt`, `/sitemap.xml` (jamais de
  profil), accueil refondu (présentation, statut, saison/événements, 3 dernières actualités, message
  du serveur, aperçu top 3, appels « Comment rejoindre »).
- `PageLayout` refait : en-tête + menu (aria-current), lien d'évitement, focus visible, zones de
  44 px, une colonne, cartes, listes ordonnées au lieu de tableaux, pied de page, titre/description/
  canonique/Open Graph. Aucun script, aucune ressource externe.
- Config : clés facultatives `news-dir` (défaut `news`), `server-address`, `minecraft-version`
  (`SiteSettings`) ; `/health` expose `"news"` (nombre visible).
- Exploitation : `install.sh` crée `/var/lib/lodyquests-portal/news` (root, 0755) et y copie le
  brouillon `scripts/web-portal/news/bienvenue.md` si le dossier est vide.

## Fichiers créés
web-api : `SiteSettings`, `news/{NewsArticle,NewsCategory,NewsFormatException,NewsParser,NewsRepository}`,
`site/{BoardView,InfoPageHandler,NewsMarkup,NewsPageHandler,PageMeta,SeoHandler,Site,SitePaging,StatLabels}` ;
tests `PublicPortalV1Test`, `news/{NewsParserTest,NewsRepositoryTest}`, `site/NewsMarkupTest` ;
`scripts/web-portal/news/bienvenue.md`.

## Fichiers modifiés
`HttpServerBootstrap`, `WebApiConfig`, `WebApiConfigLoader`, `api/HealthHandler`,
`site/{HomePageHandler,LeaderboardsPageHandler,PageLayout,PlayersPageHandler,UnpublishedPageHandler}`,
tests `HttpServerBootstrapTest` (libellé du bandeau hors-ligne), `account/MutableClock` (rendue
publique pour réutilisation) ; `scripts/web-portal/{install.sh,paths.sh,web-api.properties.example}` ;
docs listées plus bas.

## Base de données / migrations
Aucune. Aucune écriture SQL, aucun accès à `data.db`, aucune bascule MariaDB.

## Configuration / données
`/etc/lodyquests-portal/web-api.properties` **inchangé** (`player-data-public=false`,
`accounts-enabled=false`, `store-enabled=false`). Créé : `/var/lib/lodyquests-portal/news/`
(root:root 0755) + `bienvenue.md` en **brouillon** (rien de publié au nom du propriétaire).

## Tests automatiques
- `./gradlew :web-api:test` local : vert (nouveaux tests : brouillons/dates futures invisibles,
  actualités lisibles sans instantané, pagination, 404 homogènes, aucun pseudo nulle part en mode
  privé y compris sitemap, champs hors liste blanche jamais rendus, portées saison/événement,
  recherche de pseudo, robots/sitemap, viewport + lien d'évitement + zéro `<script>` sur toutes les
  pages, XSS du balisage, exemple livré = brouillon valide).
- CI PR #444 : run *push* du commit `feature/56` **entièrement vert** ; run *pull_request* : Tests
  plugin rouge deux fois sur un test **différent** à chaque essai (`HubComfortServiceTest` puis
  `ModCompatServiceTest`), même cause `AsyncTaskException/RejectedExecutionException` de MockBukkit —
  instabilité de la suite plugin, module non modifié. Fusion décidée sur cette base (commentée sur la PR).
- Rendu local Playwright (instance de démonstration, données factices locales jamais déployées) à
  1280 px et 390 px sur 7 pages : `scrollWidth` = largeur de la fenêtre partout (aucun défilement
  horizontal), contrôle visuel des captures accueil / classements / profil.
- `./gradlew build` complet non lancé localement (machine 3,8 Go ; règle : CI pour la suite complète).

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — navigateur, ordinateur **et** téléphone, sur https://lodyquests.lodylands.com :
1. `/` : présentation, « Le serveur : Hors ligne ou injoignable » (attendu, #421), « Saison et
   événements : aucune », « Dernières actualités : aucune », boutons « Comment rejoindre » ;
2. publier le brouillon : `sudo nano /var/lib/lodyquests-portal/news/bienvenue.md` → `status: published`
   (et la date voulue) ; sous 30 s il apparaît sur l'accueil et sur `/actualites/bienvenue` ;
3. `/classements`, `/joueurs` : « pas encore publiée », aucun pseudo ; `/infos` lisible pour un ado,
   **relire les « Règles de base »** (rédigées génériquement, à valider ou remplacer) ;
4. téléphone : menu sur deux lignes, pas de défilement horizontal ; tabulation clavier : focus jaune visible.
Minecraft : rien à tester pour ce lot (plugin non modifié). Après #421 + décision #55 : vérifier un
joueur ayant tapé `/profile web public` dans `/classements` et `/joueurs/<pseudo>`.

## Résultat attendu
`curl -s http://127.0.0.1:8092/health` →
`{"status":"UP","snapshot":"MISSING","playerDataPublic":false,"store":false,"news":0}` (constaté).
HTTPS public constaté : 200 sur `/`, `/actualites`, `/classements`, `/leaderboards`, `/joueurs`,
`/infos`, `/robots.txt`, `/sitemap.xml`, `/status`, `/confidentialite`, `/compte` ; 404 sur
`/actualites/bienvenue` (brouillon), `/api/status`, `/store` ; PlugAdmin répond toujours (303 → login).
Mémoire du service : ~36 Mo (plafond 256 Mo).

## Reset / retour à l'état initial
Supprimer ou repasser en `draft` les fichiers de `/var/lib/lodyquests-portal/news/`.

## Déploiement VeryGames
Aucun : le serveur Minecraft n'est pas concerné. Déploiement AWS effectué :
`scripts/web-portal/deploy.sh` sur `b04a8ea` (tests web-api locaux), release précédente sauvegardée
`/opt/lodyquests-portal/releases/20261015-231353`, `/health` OK. nginx non modifié (aucun reload).
Entrée ajoutée dans `docs/deployment/SERVER_CHANGELOG.md`.

## Rollback
`scripts/web-portal/rollback.sh app` (remet la release du 15/10 23:13) ; le dossier `news/` peut rester.

## Logs / diagnostic
`journalctl -u lodyquests-portal | grep 'Actualité ignorée'` : fichier d'actualité refusé et raison.

## Documentation mise à jour
`docs/WEB_PORTAL.md` (feuille de route, §3, nouveau §8 V1 publique et format des actualités),
`docs/deployment/WEB_PORTAL_AWS.md` (publier une actualité, vérifications navigateur 6–10),
`docs/RPGQUEST_BIBLE.md` (entrée « Actualités du portail public — news-dir »),
`docs/deployment/SERVER_CHANGELOG.md`. Pas de page docs-site portail existante.

## Limitations / travail restant
- Édition des actualités depuis PlugAdmin (aujourd'hui : fichiers via sudo) ;
- pas d'image d'actualité, pas d'API JSON publique, pas d'avatar, pas de statistiques globales ;
- pseudos/classements réels : attendent #421 (acheminement de l'instantané) et la décision #55 ;
- instabilité MockBukkit (`RejectedExecutionException` des tâches asynchrones) dans la suite plugin CI.

## Prochaine étape suggérée
#421 (acheminer l'instantané vers AWS), puis édition des actualités depuis PlugAdmin ; ticket
dédié pour stabiliser les tests plugin asynchrones MockBukkit.
