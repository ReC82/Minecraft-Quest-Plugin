# Portail web (API read-only + site minimal)

Un portail web séparé du plugin, sans encore permettre d'achat, de
connexion joueur ni de modification de données (mission étape 21). Deux
pièces distinctes :

1.  **Le plugin** exporte périodiquement un instantané en lecture seule
    (`plugins/RPGQuest/<web-export.output-dir>/snapshot.json`) — voir
    `com.lodygames.rpgquest.web.WebSnapshotWriter` et la section
    [`web-export` de config.yml](../src/main/resources/config.yml).
2.  **Le module `web-api`**, un projet Gradle séparé (`web-api/`, aucune
    dépendance vers `io.papermc`/Paper, aucun accès à `data.db`), lit
    uniquement ce fichier et expose une API authentifiée ainsi qu'un site
    public minimal.

Cette séparation stricte est la garantie centrale de l'étape (mission
points 1-2) : un bug ou une compromission du portail web ne peut jamais
lire ni écrire directement dans la base de données du plugin.

## Export côté plugin

`web-export.enabled: true` dans `config.yml` active un job qui, toutes les
`interval-seconds`, régénère `snapshot.json` de façon atomique (écriture
dans un fichier temporaire puis renommage) :

```yaml
web-export:
  enabled: false          # désactivé par défaut : aucun fichier écrit tant que false
  output-dir: web-export
  interval-seconds: 30
  include-connected-players: false   # liste nominative des joueurs en ligne (visibles seulement)
  profile-visibility-default: private # #55 : sans « /profile web public », personne n'est publié
  leaderboard-size: 10
  leaderboard-skills: []             # vide = toutes les pistes (voir docs/PROGRESSION.md)
  announcements: []                  # [{title: "...", body: "..."}, ...]
```

Toutes les lectures nécessaires (classements SQLite, catalogue d'objets)
passent par des opérations asynchrones ou des caches déjà en mémoire
(mission point 4) : le thread principal ne fait qu'un contrôle
d'intervalle (housekeeping toutes les 5 s) et lit les compteurs de joueurs
déjà en mémoire (`Bukkit.getOnlinePlayers()`), jamais de disque.

Le contenu du snapshot :

```json
{
  "generatedAt": "2026-08-07T15:30:00Z",
  "server": {"online": true, "playerCount": 3, "maxPlayers": 20},
  "players": ["Steve", "Alex"],
  "leaderboards": {"GLOBAL": [{"name": "Steve", "totalXp": 4500}, ...], ...},
  "catalog": [{"id": "rpgquest:forest_blade", "name": "Forest Blade", "rarity": "RARE"}, ...],
  "announcements": [{"title": "Maintenance", "body": "..."}]
}
```

## Le module `web-api`

Processus JVM indépendant, aucune dépendance externe obligatoire : HTTP via
`com.sun.net.httpserver` (JDK) et un codec JSON maison
(`com.lodygames.rpgquest.webapi.json.Json`), tous deux fournis par le JDK ou
écrits dans ce module.

### Déploiement local

```
gradlew.bat :web-api:build
set RPGQUEST_WEB_API_TOKEN=un-secret-partage-long-et-aleatoire
gradlew.bat :web-api:run --args=""
```

Ou directement le jar généré (`web-api/build/libs/web-api.jar`) :

```
set RPGQUEST_WEB_API_TOKEN=un-secret-partage-long-et-aleatoire
java -jar web-api\build\libs\web-api.jar
```

Le processus lit `web-api.properties` dans le répertoire de travail (voir
[`web-api/web-api.properties.example`](../web-api/web-api.properties.example)
pour les clés disponibles : `port`, `snapshot-file`,
`snapshot-max-age-seconds`, `rate-limit-per-minute`, `site-title`), ou un
chemin fixé par `RPGQUEST_WEB_API_CONFIG`.

Clés d'hébergement public (#52), toutes à défaut **sûr** :

| Clé | Défaut | Effet |
|---|---|---|
| `bind-address` | `127.0.0.1` | interface d'écoute ; `0.0.0.0` pour un accès direct sans proxy |
| `store-enabled` | `false` | n'enregistre les routes boutique (`/store*`, `/api/store/*`) et n'ouvre `store.db` que si `true` |
| `player-data-public` | `false` | `/joueurs` et `/leaderboards` affichent « pas encore publiée » sans lire l'instantané, et `/api/players`, `/api/leaderboards`, `/api/profiles` ne renvoient aucun pseudo (#55) |
| `accounts.age-threshold.<PAYS>` | aucune | seuil d'âge par pays desservi (code ISO à deux lettres) pour les futurs comptes (#53) ; **aucune ligne = comptes fermés**. Voir [PRIVACY.md](PRIVACY.md) |
| `accounts.below-threshold` | `parental-consent` | sous le seuil : `parental-consent` ou `refuse` (jamais « autorisé ») |
| `accounts.unknown-region` | `refuse` | pays inconnu ou non desservi : `refuse` ou `parental-consent` |
| `trust-proxy-headers` | `false` | lit l'IP du visiteur dans `X-Real-IP` — seulement pour une connexion venue de la boucle locale (nginx sur la même machine) — pour que la limite de fréquence reste par visiteur |

Une valeur booléenne autre que `true`/`false`, un pays qui n'est pas un code à deux lettres ou un âge hors 1–25 arrête le démarrage avec un message clair.

**Le jeton d'authentification n'est jamais lu depuis ce fichier** —
uniquement depuis la variable d'environnement `RPGQUEST_WEB_API_TOKEN`, pour
qu'il ne puisse jamais finir versionné dans Git par erreur (mission point
7). Sans cette variable, le processus démarre quand même (le site public
reste utilisable) mais `/api/*` refuse systématiquement (401) — fail-closed,
jamais un accès ouvert par défaut.

### Déploiement production

Le portail public **lodyquests.lodylands.com** est hébergé sur l'instance AWS : voir
[deployment/WEB_PORTAL_AWS.md](deployment/WEB_PORTAL_AWS.md) (service, nginx, `/health`,
déploiement, rollback). Principes généraux :

-   Servir le plugin RPGQuest et le processus `web-api` sur des machines/
    comptes distincts si possible ; `web-api` n'a besoin que d'un accès en
    lecture au dossier `web-export/` du plugin (partage réseau, sync, ou
    simplement le même disque si colocalisés).
-   Fixer `RPGQUEST_WEB_API_TOKEN` via le gestionnaire de secrets de la
    plateforme d'hébergement (jamais dans un fichier versionné).
-   Placer un reverse proxy TLS devant le port `web-api` (le serveur HTTP
    intégré ne termine pas TLS lui-même).
-   `rate-limit-per-minute` et `snapshot-max-age-seconds` sont à ajuster
    selon le trafic attendu et l'intervalle d'export réel côté plugin
    (`snapshot-max-age-seconds` doit rester strictement supérieur à
    `web-export.interval-seconds`).

## API authentifiée (`/api/*`)

Toutes les routes `/api/*` exigent `Authorization: Bearer <jeton>`
(authentification serveur-à-serveur, mission point 5) — jeton absent ou
invalide : `401`. Une limite de fréquence par IP (`rate-limit-per-minute`,
fenêtre glissante d'une minute) protège toutes les routes, `/api/*` comme
le site public : au-delà, `429`. Une requête malformée (paramètre non
numérique, piste de classement inconnue...) renvoie `400`.

| Route | Contenu |
|---|---|
| `GET /api/status` | `online`, `status` (`ONLINE`, `STALE`, `OFFLINE`, `UNKNOWN` — #421), `lastUpdate` (date du dernier relevé ou `null`), et si en ligne `playerCount`/`maxPlayers`/`generatedAt` |
| `GET /api/players` | `count`, `players` (liste nominative seulement si `include-connected-players` l'autorise côté plugin) |
| `GET /api/leaderboards[?skill=COMBAT&limit=N]` | classements, un ou toutes les pistes |
| `GET /api/catalog` | catalogue public d'objets (id, nom en texte brut, rareté) |
| `GET /api/announcements` | actualités/annonces configurées |
| `GET /api/profiles` | profils publics limités et `statistics` par portée (instantané v2, #54/#56 — voir [WEB_PORTAL.md](WEB_PORTAL.md)) |

Les trois routes à pseudos passent par une liste blanche (`privacy.PublicPlayerData`, #55) : seuls
`name`, `skills`, `questsCompleted`, `totalXp` et `value` en sortent ; tout autre champ de
l'instantané est ignoré.

Toute route inconnue sous `/api/` renvoie `404` (toujours après vérification
du jeton).

## Comptes joueurs (`/compte`, `/api/link/confirm`, issue #53)

Désactivés par défaut. Clés de `web-api.properties` :

| Clé | Défaut | Rôle |
| --- | --- | --- |
| `accounts-enabled` | `false` | ouvre la base `accounts.db` et les formulaires ; sans région d'âge configurée (#55), les comptes restent fermés |
| `accounts-database-file` | `accounts.db` | base SQLite propre aux comptes (jamais `data.db` ni `store.db`) |
| `accounts-session-ttl-hours` | `168` | durée absolue d'une session (1–720) |
| `accounts-link-code-ttl-minutes` | `10` | validité d'un code de liaison (2–30) |

Jeton : `RPGQUEST_PORTAL_LINK_TOKEN` (variable d'environnement uniquement). Il authentifie
**seulement** `POST /api/link/confirm` — corps `{"code","uuid","name"}`, réponse
`{"result":"LINKED|INVALID_CODE|UUID_ALREADY_LINKED|ACCOUNT_ALREADY_LINKED|ACCOUNT_RESTRICTED|TOO_MANY_ATTEMPTS"}`,
401 sans le bon jeton (le jeton général `RPGQUEST_WEB_API_TOKEN` ne l'ouvre pas), 503 si les
comptes sont fermés. Pages, sécurité et parcours : [WEB_PORTAL.md §2](WEB_PORTAL.md).

Le même jeton authentifie `POST /api/link/progress` (progression personnelle des joueurs reliés,
64 Kio au plus) :

- sonde `{"uuid"}` → `{"result":"LINKED|NOT_LINKED"}`, aucune écriture ;
- envoi `{"uuid","name","progress":{"generatedAt","skills":[{"skill","xp","level"}],"quests":{"completed","rank"?,"players"?},"boards":[{"id","name","category","icon"?,"metric","period","window","status":"ACTIVE|ENDED","value","rank"?,"players"?}]}}`
  → `{"result":"STORED|NOT_LINKED|THROTTLED"}` ; tout champ hors contrat, négatif ou incohérent
  (`rank` > `players`) → 400 et rien d'écrit. `STORED` aussi pour un envoi plus ancien que celui
  gardé (sans effet). 401 sans le bon jeton, 503 si les comptes sont fermés.

## Réception de l'instantané (`POST /api/snapshot`, issue #421)

Le plugin écrit `snapshot.json` chez VeryGames ; le portail tourne sur AWS. Le plugin **pousse**
donc chaque instantané (même sens que l'agent PlugAdmin : le serveur Minecraft initie, AWS reçoit) :
`POST <base-url>/api/snapshot`, corps = le fichier tel quel.

- Jeton : `RPGQUEST_PORTAL_SNAPSHOT_TOKEN` (variable d'environnement uniquement), **dédié** : le
  jeton général, celui de `/lien` et ceux de PlugAdmin ne l'ouvrent pas. Absent : `401` pour tout.
- Taille : `snapshot-ingest-max-bytes` (défaut 1 Mio, 64 Kio à 8 Mio) → `413` au-delà.
- Validation avant écriture : objet JSON, `schemaVersion` entre 1 et 2, `generatedAt` ISO-8601
  au plus 5 min dans le futur, `server.online` booléen, aucune clé contenant `uuid` à aucun niveau
  (#55) → sinon `400` et **fichier précédent intact**.
- Écriture atomique (temporaire dans le même dossier puis `rename`) du `snapshot-file` ; aucune
  autre écriture, aucun accès à `data.db`.
- Réponse `200 {"result":"STORED"}` ; `405` hors `POST` ; chemin exact seulement.

Côté plugin : fichier hors Git `plugins/RPGQuest/portal-snapshot.properties` (modèle
`scripts/portal-snapshot.properties.example`) ; envoi depuis le fil `RPGQuest-WebExport`, jamais le
thread principal ; un échec est journalisé une fois (statut HTTP ou « injoignable », jamais le jeton
ni le contenu), puis au rétablissement. À l'arrêt du plugin, l'instantané minimal
`server.online=false` n'est plus écrit que localement : l'arrêt est annoncé au portail par le relevé
d'état, et le dernier instantané complet y reste jusqu'au suivant.

### Instantané complet réel (bêta restreinte, #421 — 24/10)

- **Activation** : `snapshot-enabled=true` dans `portal-snapshot.properties` (hors Git, propre au
  serveur). L'export tourne alors pour le portail toutes les `snapshot-interval-seconds` (**900 s**
  par défaut, 600 au plus court, 3600 au plus long), même si `web-export.enabled` reste `false` dans
  `config.yml` ; `snapshot-profile-visibility=public|private` remplace, pour cet envoi et pour
  `/profile web`, `web-export.profile-visibility-default`. `/profile web prive` reste respecté.
- **Numérotation** : chaque instantané porte `bootId` (aléatoire par démarrage) et `sequence`
  (croissante). Même règle que le relevé d'état : déjà reçu → `DUPLICATE`, plus ancien →
  `OUT_OF_ORDER`, rien n'est écrit. Un instantané non numéroté (plugin plus ancien) remplace le
  précédent comme avant ; `bootId` sans `sequence` (ou l'inverse) → `400`.
- **Nouveaux essais** : portail injoignable ou `429`/`5xx` → deux nouveaux essais (30 s puis 2 min)
  avec **le même corps** ; un refus définitif (`400`, `401`, `413`) n'est pas réessayé. Les essais
  occupent le fil d'export : aucun autre instantané ne part en parallèle.
- **Coût mesuré** : bloc `export` = `intervalSeconds`, `collectMs` (collecte totale), `mainThreadMs`
  (part sur le thread principal), `profiles` ; une ligne de journal par envoi (taille en octets,
  résultat, durée, essais). Le relevé d'état ajoute `server.tps` et `server.mspt` (API Paper,
  facultatifs, bornés 0–100 et 0–60 000).
- **Affichage** : les données de jeu d'un instantané restent affichées tant que le relevé d'état
  dit le serveur en ligne et que l'instantané a moins de `snapshot-data-max-age-seconds` (défaut
  3600 s) ; au-delà, elles sont retirées (jamais montrées comme actuelles). `syncedAt` (état résolu)
  et la mention « Données relevées sur le serveur le … » (Joueurs, fiches, Classements) les datent.
- **`/health`** : `sync` = `data` (`MISSING` / `FRESH` / `STALE`), `lastSync`, `sequence`, `export` ;
  `load` = `tps`, `mspt` du dernier relevé.

### Relevé d'état agrégé (`"kind": "status"`)

Même route, même jeton. Envoyé par le plugin (`ServerStatusReporter`, fil `RPGQuest-PortalStatus`)
toutes les `status-interval-seconds` (30 s), **même avec `web-export.enabled: false`** :

```json
{"schemaVersion":1,"kind":"status","generatedAt":"2026-10-22T17:00:00Z","bootId":"boot-…","sequence":42,
 "server":{"online":true,"playerCount":1,"maxPlayers":20}}
```

- **Périmètre fermé** : toute autre clé (racine ou `server`) → `400`, rien n'est écrit. Aucun
  pseudo, UUID ou position ne peut transiter. Compteurs entiers 0–100 000 ; `bootId`
  `[A-Za-z0-9-]{8,64}` aléatoire par démarrage ; `generatedAt` au plus 5 min dans le futur ; `kind`
  inconnu → `400`.
- **Idempotent** : même `bootId` et `sequence` déjà reçue → `200 {"result":"DUPLICATE"}` ; plus
  petite → `OUT_OF_ORDER` ; autre `bootId` plus ancien que le dernier relevé → `OUT_OF_ORDER`. Rien
  n'est écrit dans ces cas : un renvoi ne fait jamais reculer l'état, les compteurs sont remplacés,
  jamais additionnés.
- Stocké atomiquement dans `server-status.json`, voisin du `snapshot-file`, avec `receivedAt`
  (horloge du portail, qui sert à juger la fraîcheur). Le portail journalise un nouveau démarrage
  et un arrêt annoncé, sans jeton.
- Dès qu'il est au moins aussi récent que `snapshot.json`, le relevé fait foi pour l'état du serveur
  (ONLINE / OFFLINE / STALE) ; classements, catalogue et profils ne viennent que d'un instantané
  complet frais, sinon ils restent vides.

## Sonde `GET /health` (pas d'authentification)

`{"status":"UP","snapshot":"MISSING|STALE|FRESH","server":"UNKNOWN|STALE|OFFLINE|ONLINE","heartbeat":"MISSING|STALE|FRESH","lastUpdate":"…|null","playerDataPublic":false,"store":false,"news":0}` —
`status` atteste le portail seul ; `server` l'état du jeu ; `heartbeat` l'arrivée du relevé d'état ;
`lastUpdate` la date du dernier relevé pris en compte. Toujours `200` tant que le processus sert des requêtes (un instantané absent n'est pas une panne).
Ni donnée joueur, ni chemin, ni configuration sensible. Les requêtes `HEAD` reçoivent les en-têtes
sans corps.

## Site public minimal (`/`, pas d'authentification)

`/` (accueil + annonces), `/status`, `/leaderboards`, `/joueurs` et
`/joueurs/<pseudo>` (profils publics limités, #56), `/wiki` (catalogue),
`/confidentialite` (#55). Avec un instantané v2, `/leaderboards` affiche
les statistiques « depuis toujours » (XP par piste **et** quêtes terminées)
et renvoie vers les profils.
Pages HTML strictement en lecture (aucune donnée envoyée par le
visiteur n'est jamais persistée), échappement HTML systématique des
données du snapshot (`com.lodygames.rpgquest.webapi.site.Html`). Ni paiement, ni
connexion joueur, ni modification de données à cette étape (mission point
11) — ce sera l'objet d'une étape ultérieure.

### Identité visuelle et icônes (#428)

Habillage « RPG fantasy voxel » (variante B de `docs/design/428-icones/`) dans `PageLayout` : menu,
titres, états vides et podium (or / argent / bronze) illustrés par des icônes pixel art **originales**
(`site.PortalIcons`). Le sprite `web-api/src/main/resources/portal/lq-icons.svg` est la seule
ressource statique du portail : `GET /assets/lq-icons.svg?v=<empreinte>` (`image/svg+xml`, cache
un an `immutable`, `ETag`, 304 ; sans `?v`, une heure ; toute autre adresse sous `/assets/` → 404,
autre méthode que GET/HEAD → 405). Aucune ressource externe, CSP nginx inchangée (`'self'`).
`material-map.json` (même dossier) fait correspondre material / entité / métrique → icône : nom
exact, suffixe (`_SWORD`, `_ORE`…), puis repli `unknown`. Régénération et règles :
`docs/design/428-icones/README.md`. Pied de page : mention exigée par les Usage Guidelines Mojang
(« NOT AN OFFICIAL MINECRAFT SERVICE… »).

## Mode dégradé

Si `snapshot.json` est absent, illisible ou périmé (plus vieux que
`snapshot-max-age-seconds`), l'API et le site répondent normalement (`200`, jamais d'erreur) avec
`online: false` — mission point 9. Depuis #421, le portail ne confond plus absence de données et
arrêt du serveur :

| `status` | Cause | Bandeau du site |
|---|---|---|
| `UNKNOWN` | aucun instantané (ou JSON corrompu) | neutre : « État du serveur de jeu inconnu — aucune donnée reçue pour le moment » |
| `STALE` | dernier instantané / relevé plus vieux que `snapshot-max-age-seconds` | neutre : « Informations non actualisées depuis le … » |
| `OFFLINE` | instantané ou relevé `server.online=false`, publié par le plugin à son arrêt | rouge : « Serveur de jeu hors ligne — arrêt annoncé par le serveur le … » |
| `ONLINE` | instantané ou relevé d'état récent `server.online=true` | aucun |

Une panne du canal de télémétrie donne `UNKNOWN` ou `STALE`, jamais `OFFLINE`.

## Sécurité

-   Jeton en temps constant (`java.security.MessageDigest.isEqual`), jamais
    de comparaison de chaînes classique (résistance aux attaques par
    mesure de temps).
-   Le journal d'accès (`com.lodygames.rpgquest.webapi.http.AccessLogger`)
    n'écrit jamais l'en-tête `Authorization` ni aucun jeton — seulement
    méthode, chemin, IP, statut, durée.
-   Toutes les données affichées (pseudos, annonces, catalogue) sont
    échappées avant insertion HTML.

## Tests

-   Côté plugin : `WebSnapshotWriterTest` (activé/désactivé, contenu JSON,
    caractères Unicode), `ProgressionRepositoryTest#topPlayers*`,
    `ConfigValidatorTest` (section `web-export`).
-   Côté `web-api` : `JsonTest` (codec, Unicode, JSON tronqué/malformé),
    `HttpServerBootstrapTest` — bout-en-bout via un vrai serveur HTTP et un
    vrai client : snapshot absent/périmé (mode dégradé), jeton
    manquant/invalide, requête malformée, rate limit, route inconnue,
    données vides, caractères Unicode, page publique sans authentification.

## Limites connues

-   Pas de pagination sur `/api/catalog` ni sur le tableau du wiki (le
    volume attendu à ce stade reste faible).
-   Le rate limiting est en mémoire par processus : redémarrer `web-api`
    réinitialise les compteurs (acceptable pour un portail à ce stade,
    sans état partagé entre plusieurs instances).
-   Aucun HTTPS natif : à faire terminer par un reverse proxy en production.
