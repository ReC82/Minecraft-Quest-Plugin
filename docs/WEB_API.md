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
  include-connected-players: false   # liste nominative des joueurs en ligne
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

**Le jeton d'authentification n'est jamais lu depuis ce fichier** —
uniquement depuis la variable d'environnement `RPGQUEST_WEB_API_TOKEN`, pour
qu'il ne puisse jamais finir versionné dans Git par erreur (mission point
7). Sans cette variable, le processus démarre quand même (le site public
reste utilisable) mais `/api/*` refuse systématiquement (401) — fail-closed,
jamais un accès ouvert par défaut.

### Déploiement production

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
| `GET /api/status` | `online`, et si en ligne `playerCount`/`maxPlayers`/`generatedAt` |
| `GET /api/players` | `count`, `players` (liste nominative seulement si `include-connected-players` l'autorise côté plugin) |
| `GET /api/leaderboards[?skill=COMBAT&limit=N]` | classements, un ou toutes les pistes |
| `GET /api/catalog` | catalogue public d'objets (id, nom en texte brut, rareté) |
| `GET /api/announcements` | actualités/annonces configurées |

Toute route inconnue sous `/api/` renvoie `404` (toujours après vérification
du jeton).

## Site public minimal (`/`, pas d'authentification)

`/` (accueil + annonces), `/status`, `/leaderboards`, `/wiki` (catalogue).
Pages HTML strictement en lecture (aucune donnée envoyée par le
visiteur n'est jamais persistée), échappement HTML systématique des
données du snapshot (`com.lodygames.rpgquest.webapi.site.Html`). Ni paiement, ni
connexion joueur, ni modification de données à cette étape (mission point
11) — ce sera l'objet d'une étape ultérieure.

## Mode dégradé

Si `snapshot.json` est absent (le plugin n'a jamais exporté, ou le dossier
n'est pas partagé correctement) ou périmé (plus vieux que
`snapshot-max-age-seconds` — typiquement le serveur Minecraft est arrêté),
l'API et le site répondent normalement (`200`, jamais d'erreur) avec
`online: false` et un bandeau « Serveur hors-ligne » côté site — mission
point 9. Un snapshot illisible (JSON corrompu) est traité de la même façon,
jamais une erreur 500.

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
