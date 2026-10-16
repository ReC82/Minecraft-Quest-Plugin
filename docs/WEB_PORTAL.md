# Portail communautaire RPGQuest (issues #52 à #57)

Document de cadrage et d'état. Il décrit ce qui **existe dans le code** et ce qui est **conçu mais
pas encore réalisé**, sans mélanger les deux. Dernière mise à jour : 15/10/2026 (#56, V1 publique : actualités, classements par portée, profils, infos).

## 1. Cadrage (#52)

### Ce que le portail est

- un site **public**, consultable sans compte : actualités, statut, classements, profils publics
  limités, wiki, confidentialité ;
- **distinct de PlugAdmin** : autre processus (`web-api/`), autre service systemd, autre
  utilisateur système, autre port, autre vhost nginx, aucun compte, secret ni permission
  d'administration partagés. PlugAdmin administre, le portail montre ;
- **hébergé sur l'instance AWS** (celle de PlugAdmin), domaine **https://lodyquests.lodylands.com**
  (décision du propriétaire du 13/10) — exploitation : [deployment/WEB_PORTAL_AWS.md](deployment/WEB_PORTAL_AWS.md) ;
- **en lecture seule sur le jeu** : il lit uniquement l'instantané `snapshot.json` exporté par le
  plugin (`web.WebSnapshotWriter`). Il n'ouvre jamais `data.db`, n'écrit jamais dans les données de
  gameplay et ne tient aucune copie faisant autorité d'un solde ou d'une progression ;
- **sans paiement** en V1. Le module boutique historique (`web-api/.../store`, mission étape 22,
  fournisseur « sandbox ») n'entre pas dans le périmètre du portail communautaire et n'est pas
  étendu ici.

### Ce qu'il n'est pas (encore)

- pas de compte joueur ouvert au public : le code existe (§2) mais reste désactivé
  (`accounts-enabled=false`) tant que le propriétaire n'a pas tranché #55 ;
- pas de MySQL : la bascule #42 **n'est pas présumée faite**. Tout fonctionne sur l'instantané
  produit depuis SQLite ; le jour où la base change, seul l'exporteur change ;
- pas de saison ni d'événement définis : le modèle et le calcul existent (§4, `statistics.yml`), aucun
  fichier n'est installé sur DEV ; le moteur d'événements (#57, §6) est réalisé mais aucun événement
  n'est programmé.

### Feuille de route, dans l'ordre des dépendances

État au 15/10/2026. « Fait » = dans le code et testé automatiquement ; la validation navigateur ou
en jeu par le propriétaire reste à faire là où c'est indiqué.

| # | Étape | Ticket | État |
| --- | --- | --- | --- |
| 0 | Socle d'hébergement AWS : service, nginx, `/health`, déploiement, rollback | #52 | **fait** — en ligne en HTTPS depuis le 15/10 (certificat Let's Encrypt ECDSA, échéance 13/01/2027, renouvellement automatique certbot) |
| 1 | Instantané v2 : statistiques par portée, profils publics | #54, #56 | **fait** |
| 2 | Confidentialité : profils privés par défaut, liste blanche, politique d'âge configurable, registre des données | #55 | **fait** (garde-fous, [PRIVACY.md](PRIVACY.md)) ; pays, seuils d'âge, durées de conservation et textes juridiques **à décider** par le propriétaire après revue juridique |
| 3 | Acheminer l'instantané du serveur Minecraft vers AWS | #421 | **fait**, inactif : `POST /api/snapshot` (jeton dédié) + envoi par le plugin (`portal-snapshot.properties` hors Git) ; statut en quatre états (en ligne, hors ligne annoncé, non actualisé, inconnu). L'activation (`web-export.enabled`, jetons des deux côtés) reste une décision du propriétaire |
| 4 | Publication des pseudos (`player-data-public=true`, `web-export` réactivé) | #55 → #56 | **attend la décision** du propriétaire (#55) ; seuls les joueurs ayant tapé `/profile web public` seraient publiés |
| 5 | V1 publique : actualités, profils, premiers classements | #56 | **fait** (§8) : actualités en fichiers, classements lifetime / saison / événement, profils, infos & FAQ, SEO de base ; pseudos visibles seulement après 3 et 4 ; édition des actualités depuis PlugAdmin à faire |
| 6 | Comptes joueurs et liaison UUID par code en jeu | #53 | **fait**, désactivé (`accounts-enabled=false`) ; export et suppression de compte à faire avant ouverture ; validation navigateur + jeu à faire |
| 7 | Saisons et événements de classement, puis moteur d'événements | #54, #57 | modèle **fait** (§4 : fenêtres du journal d'XP, archivage figé, exclusions, `statistics.yml`) ; moteur #57 **fait** (§6 : journal d'activité générique, filtres, règles de classement, objectif commun, cycle de vie audité, récompenses déclaratives) ; back-office PlugAdmin à faire |
| 8 | Entitlements : récompenses remises par le plugin | #422 | à spécifier, après #53 et #57 |
| — | Premium (VIP, pass, boutique) | — | **hors périmètre** : après cadrage juridique et règles Minecraft ; `store-enabled=false` |

## 2. Comptes joueurs et liaison au compte Minecraft (#53) — fait, désactivé

Une saisie de pseudo ou d'UUID ne prouve rien. La liaison se prouve **en jeu**.

### Parcours

1. sur `/compte`, le joueur crée un compte : **identifiant** (3–32 caractères, ni vrai nom ni pseudo
   Minecraft conseillés), **mot de passe** (10 caractères minimum), **pays** et **âge** — ces deux
   derniers servent uniquement à la politique d'âge (#55) puis sont oubliés. Aucun e-mail ;
2. connecté, il clique « Obtenir un code de liaison » : le portail crée un **défi** — code de
   8 caractères (alphabet sans 0/O/1/I/L), valable 10 minutes (`accounts-link-code-ttl-minutes`),
   usage unique ; un nouveau code annule le précédent ; 5 codes par heure au plus ;
3. en jeu, il tape `/lien <code>` (alias `/link`). Le plugin envoie au portail
   `{code, uuid, pseudo}` de **l'émetteur** — aucun argument ne permet de choisir un autre UUID —
   par `POST https://lodyquests.lodylands.com/api/link/confirm`, avec un **jeton dédié** ;
4. le portail consomme le code en une transaction : code inconnu, expiré ou déjà utilisé →
   « Code invalide ou expiré » (sans dire lequel) ; sinon liaison `compte ↔ UUID`. Le joueur reçoit
   un message simple, jamais d'information sur le compte web ;
5. la page `/compte` montre : relié ou non, pseudo, date de liaison, 10 dernières opérations, et
   « Délier » (mot de passe exigé). Relier de nouveau exige un nouveau code.

### Règles de sécurité réalisées

| Exigence (#53) | Réalisation |
| --- | --- |
| code aléatoire, court, usage unique | `SecureRandom`, 31⁸ ≈ 8·10¹¹ combinaisons, consommé par `UPDATE … WHERE used_at IS NULL AND expires_at > now` |
| tentatives limitées | 5 preuves ratées par UUID / 15 min (portail) ; 5 inscriptions par IP / heure ; 1 essai en vol et 5 s entre deux essais par joueur (plugin) ; 10 échecs de connexion par IP ou identifiant / 15 min |
| aucun secret dans les journaux | le code et les jetons ne sont jamais journalisés ; seules leurs **empreintes** SHA-256 sont stockées ; `PortalLinkConfig.toString()` masque le jeton |
| un UUID → un seul compte actif | `minecraft_links` : clé primaire sur le compte, `UNIQUE` sur l'UUID ; tentative refusée et journalisée |
| audit des liaisons/dissociations | table `link_audit` (`LINKED`, `UNLINKED`, `REFUSED_UUID_TAKEN`, `REFUSED_RESTRICTED`) |
| CSRF / session | cookie `lq_session` `HttpOnly; Secure; SameSite=Lax`, jeton neuf à chaque connexion, invalidé côté serveur à la déconnexion ; jeton CSRF par session sur tout formulaire connecté, double soumission (`lq_form`, `SameSite=Strict`) avant connexion ; `Origin` / `Sec-Fetch-Site` étrangers refusés ; `Cache-Control: no-store` |
| mot de passe haché moderne | PBKDF2-HMAC-SHA256, 600 000 itérations, sel 16 octets (JDK, aucune dépendance) ; vérification à coût constant même pour un identifiant inconnu |
| aucune réutilisation PlugAdmin | autre base (`accounts.db`), autre cookie, autre service ; le compte ne donne **aucun** droit, ni sur le jeu ni sur PlugAdmin |
| mineurs (#55) | `AgePolicy` décide ; sous le seuil, le compte est créé **limité** (`PENDING`) et ne peut pas se lier ; refus = rien n'est enregistré |

### Mise en route (décision du propriétaire)

Désactivé partout aujourd'hui. Pour ouvrir (après #55) :

1. portail — `/etc/lodyquests-portal/web-api.properties` : `accounts-enabled=true` et au moins une
   ligne `accounts.age-threshold.<PAYS>=<âge>` ; `/etc/lodyquests-portal/portal.env` :
   `RPGQUEST_PORTAL_LINK_TOKEN=<jeton aléatoire>` ; `sudo systemctl restart lodyquests-portal` ;
2. serveur Minecraft — `plugins/RPGQuest/portal-link.properties` (modèle
   `scripts/portal-link.properties.example`) avec `enabled=true`, `base-url` et **le même jeton** ;
   redémarrage du serveur ;
3. nginx — le vhost doit contenir le bloc `location = /api/link/confirm` de
   `scripts/web-portal/nginx-lodyquests-portal.conf` (seule route `/api/` ouverte).

Avant l'ouverture publique restent à faire : export et suppression de compte, changement de mot de
passe, demande de consentement parental, durée de conservation du journal (PRIVACY.md §4, §7).

### Évolutions prévues, non réalisées

Changement d'identifiant ou de mot de passe ; **récupération de compte par une nouvelle preuve en
jeu** (pas besoin d'e-mail) ; e-mail facultatif si un prestataire est choisi ; MFA ; OAuth/SSO ;
plusieurs identités Minecraft par compte (la table de liaison devra alors perdre sa clé primaire
sur le compte, l'unicité de l'UUID restant). La liaison fournira l'identité des inscriptions aux
événements (#57) et des récompenses (#422).

## 3. Instantané — format v2 (#54, #56) — fait

`schemaVersion: 2` ajoute deux clés ; toutes les clés de la v1 sont inchangées. Les ajouts de #54
(`metrics`, `rank`, contenu de `season` et `events`) sont additifs : la version ne change pas.

```json
"statistics": {
  "scopes": ["lifetime", "season", "event"],
  "metrics": [
    {"id": "RPG_XP_COMBAT", "aggregation": "SUM", "order": "DESC", "scopes": ["lifetime", "season", "event"]},
    {"id": "QUESTS_COMPLETED", "aggregation": "SUM", "order": "DESC", "scopes": ["lifetime"]}
  ],
  "lifetime": {
    "RPG_XP_GLOBAL":   [{"name": "Alice", "value": 4500, "rank": 1}],
    "QUESTS_COMPLETED":[{"name": "Alice", "value": 12, "rank": 1}]
  },
  "season": {
    "id": "season_01", "name": "Saison 1",
    "start": "2026-11-01T00:00:00Z", "end": "2027-02-01T00:00:00Z",
    "status": "ACTIVE",
    "leaderboards": {"RPG_XP_GLOBAL": [{"name": "Alice", "value": 300, "rank": 1}]}
  },
  "events": [
    {"id": "zombie_weekend_2026_11", "name": "Week-end des zombies",
     "start": "2026-11-07T18:00:00Z", "end": "2026-11-09T22:00:00Z",
     "status": "SCHEDULED", "leaderboards": {"RPG_XP_COMBAT": []}}
  ]
},
"profiles": [
  {"name": "Alice", "skills": {"GLOBAL": 4500}, "questsCompleted": 12}
]
```

`season` vaut `null` et `events` `[]` tant qu'aucun `statistics.yml` n'existe. `status` :
`SCHEDULED` (pas encore commencée, classements vides), `ACTIVE`, `ENDED` (terminée, pas encore
figée), `ARCHIVED` (figée). Côté portail, `privacy.PublicPlayerData` relaie ces clés par liste
blanche ; `/classements` et l'accueil les affichent (§8).

## 4. Modèle de statistiques (#54) — fait

Une statistique = **(métrique, portée, joueur) → valeur**, calculée **depuis une source de vérité
existante**, jamais depuis un compteur parallèle. Le portail n'en calcule aucune : il affiche ce que
le plugin exporte.

### Métriques — `stats.StatMetric`

Chaque métrique déclare son agrégation (`SUM`, ou `MAX` pour un futur meilleur score), son sens de
classement (`DESC`, ou `ASC` pour un futur chrono) et les portées **réellement calculables** avec
sa source. Ajouter une métrique = une entrée du catalogue et sa requête, sans migration.

| Métrique | Source de vérité | Portées |
| --- | --- | --- |
| `RPG_XP_<PISTE>` (`GLOBAL`, `COMBAT`, `MINING`, `FARMING`, `FISHING`, `EXPLORATION`) | lifetime : `player_skills.total_xp` ; fenêtres : `xp_grants` (journal horodaté, dédupliqué par action de jeu) | lifetime, season, event |
| `QUESTS_COMPLETED` | `quest_progress.state = COMPLETED` (dernier état seulement, pas d'historique) | lifetime |
| `ENTITY_KILL` (#57) | `stat_activity_log` : mob tué par un joueur, cible = type d'entité, variante = mob spécial ; filtrable `entity` / `special-mob` | season, event |
| `FISH_CAUGHT` (#57) | `stat_activity_log` : prise de pêche, cible = matériau ; filtrable `item` | season, event |
| blocs minés, temps de jeu, boss… | même journal (§6) : un type d'action de plus dans `ActivityListener`, aucune migration | — |

L'XP RPG (#183) n'est ni le niveau vanilla ni une statistique Minecraft. Une métrique sans journal
horodaté est refusée sur une saison ou un événement (`statistics.yml` invalide) : on ne publie pas
une valeur approximative.

### Portées

- `lifetime` : depuis toujours, depuis les totaux existants ;
- `season` : une fenêtre `[début, fin)` en UTC. **Une seule saison à la fois** (chevauchement refusé
  au chargement). La valeur d'un joueur = somme de ses octrois `xp_grants` dans la fenêtre ;
- `event` : même calcul, sur la fenêtre de l'événement. Les événements **peuvent se chevaucher** :
  la même action compte dans chacun, sans compteur dédié.

### Cohérence et idempotence

- **Pas de double comptage** : `xp_grants` est dédupliqué par (joueur, piste, id d'action de jeu) ;
  un retry ou une reconnexion ne crée pas de seconde ligne, donc pas de second point de classement ;
- **Opérations admin exclues** : les octrois de raison `admin_grant` (`/skills admin grant`) ne comptent
  jamais dans une saison ou un événement (`excluded-xp-reasons`) ; `/skills admin set` n'écrit pas
  dans le journal. Le `lifetime`, lui, lit les totaux : un compte de test s'en retire par une
  **exclusion** (ci-dessous) ;
- **Exclusions de modération** : par UUID (jamais par pseudo), pour toutes les portées (`*`), le
  `lifetime` ou une période. Appliquées **à la lecture**, y compris sur les archives : un tricheur
  découvert après coup disparaît aussi des classements figés ;
- **Bornes horaires** : dates obligatoirement avec fuseau (`Z` ou `+01:00`), à la seconde entière,
  fin exclue. Un octroi à `fin` exacte appartient à la période suivante ;
- **Égalités** : rang « compétition » (1, 1, 3) ; ordre d'affichage des ex-aequo par pseudo puis UUID,
  stable d'un export à l'autre ;
- **Confidentialité** : la visibilité `/profile web` (#55) s'applique à toutes les portées.

### Archivage — `stats.StatisticsService`

Toutes les minutes, hors du thread principal, le plugin relit `statistics.yml` s'il a changé et
**fige** chaque période terminée (`stat_period_archives`, `stat_period_results`, migration V39).
L'archivage est idempotent (clé primaire sur la période, même transaction que les résultats), ne
dépend pas de `web-export.enabled`, et survit à un reset de joueur ou à une modification ultérieure
de la définition. Un serveur arrêté à la fin d'une période l'archive au démarrage suivant : seuls
les octrois horodatés dans la fenêtre comptent, l'archive est donc la même.

### Performance

Une fenêtre se lit par l'index `idx_xp_grants_skill_created (skill, created_at)` (V39) : un export
ne balaie jamais tout le journal. Test automatisé : 50 000 octrois, 500 joueurs, top 10 calculé
largement sous 2 s sur SQLite. Une période archivée se lit dans ses résultats figés.

### Fichier `plugins/RPGQuest/statistics.yml`

Absent par défaut (aucune saison, aucun événement, aucune exclusion). Invalide → **aucune** période
appliquée et erreurs dans la console, le plugin démarre normalement. Exemple :

```yaml
seasons:
  - id: season_01                 # minuscules, chiffres, _ (64 max) ; unique saisons + événements
    name: "Saison 1"
    start: "2026-11-01T00:00:00+01:00"
    end: "2027-02-01T00:00:00+01:00"
    metrics: [RPG_XP_GLOBAL, RPG_XP_COMBAT, RPG_XP_MINING]
events:
  - id: zombie_weekend_2026_11
    name: "Week-end des zombies"
    start: "2026-11-07T18:00:00Z"
    end: "2026-11-09T22:00:00Z"
    metrics: [RPG_XP_COMBAT]
excluded-xp-reasons: [admin_grant]  # défaut si la clé est absente
exclusions:
  - player: 00000000-0000-0000-0000-000000000000   # UUID du compte de test
    scopes: ["*"]                                   # *, lifetime, ou des id de périodes
```

### Hors périmètre, prévu

- **Plusieurs serveurs** : `xp_grants` n'a pas de colonne serveur ; le jour venu, une colonne
  `server_id` et un filtre dans la fenêtre suffisent, le format exporté ne change pas ;
- **Position personnelle** hors top N et `GET /players/{uuid}/stats` : demandent l'identité du
  joueur connecté (#53) ; même requête de fenêtre, filtrée sur un joueur ;
- **Guildes**, récompenses de classement, anti-triche complet : #57 / #422 ;
- **Gestion en jeu des exclusions** (commande ou PlugAdmin) : aujourd'hui, édition du fichier.

## 5. Confidentialité et mineurs (#55)

Registre complet des données, règles d'âge et décisions à prendre : [PRIVACY.md](PRIVACY.md).

Fait :

- l'instantané ne contient **ni UUID, ni IP, ni position, ni inventaire, ni date de connexion** ;
- profils **privés par défaut** (`web-export.profile-visibility-default: private`) : un joueur
  n'apparaît dans les classements, les profils et la liste des connectés qu'après
  `/profile web public`. `/profile web prive` le retire dès l'export suivant ; `/profile web`
  affiche le réglage. Le filtre est appliqué dans les requêtes SQL d'export
  (`player.PublicProfilePolicy`) : aucune donnée d'un joueur non visible n'atteint l'instantané ;
- côté portail, une **liste blanche** (`privacy.PublicPlayerData`) ne laisse sortir de l'API que
  `name`, `skills`, `questsCompleted`, `totalXp`, `value` ; aucun pseudo tant que
  `player-data-public=false` ;
- **politique d'âge configurable** (`privacy.AgePolicy`, clés `accounts.*`) : sans pays configuré,
  aucun compte ne peut s'ouvrir ; statut de consentement parental représentable
  (`privacy.ParentalConsentStatus`), compte limité tant qu'il manque ;
- un pseudo inconnu et un pseudo masqué reçoivent **la même** réponse (« aucun profil public ») ;
- page `/confidentialite` décrivant exactement ce comportement et l'état réel de la configuration.

À trancher avant toute ouverture publique (décision du propriétaire, après revue juridique) : pays
et seuils d'âge, durées de conservation, prestataire e-mail, textes (confidentialité, conditions,
information des mineurs, règlements de concours) — liste dans [PRIVACY.md §7](PRIVACY.md).

## 6. Moteur d'événements et classements temporaires (#57) — fait (plugin + portail)

Un événement est **une entrée de `statistics.yml`** (§4), jamais du code : un « Zombie Weekend » ou
un concours de pêche ne demande ni listener ni migration.

### Journal d'activité générique — `stats.ActivityListener`, `stat_activity_log` (V40)

Un seul listener, pour tous les concours, note chaque action comptable : mob tué
(`ENTITY_KILL`, cible = `EntityType`, variante = id du mob spécial RPGQuest) et prise de pêche
(`FISH_CAUGHT`, cible = matériau). Comme `xp_grants`, c'est un **journal**, pas un compteur :

- **idempotent** : `event_key` unique (UUID de l'entité tuée, de l'hameçon) — un événement Bukkit
  rejoué ou un retry n'insère rien ;
- **plusieurs événements simultanés** relisent la même ligne, chacun sur sa fenêtre et ses filtres,
  sans double comptage à l'intérieur d'un événement ;
- **admin et debug exclus d'office** (contrôle côté plugin, pas d'interface) : créatif, spectateur,
  mode bypass administrateur actif (#35), mobs de test (`/rpgadmin mob test`), mobs de spawner et
  descendants de division (mêmes règles anti-farm que l'XP de combat), victimes joueurs (PvP entre
  comptes) ;
- `MONITOR` + événements annulés ignorés ; écriture asynchrone, aucun SQL sur le thread principal ;
- les données n'existent qu'à partir du déploiement de V40 : ces métriques ne sont donc pas
  proposées en `lifetime`.

### Définition d'un événement

```yaml
events:
  - id: zombie_weekend_2026_11
    name: "Zombie Weekend"
    start: "2026-11-07T18:00:00+01:00"
    end: "2026-11-09T22:00:00+01:00"
    status: planned              # planned (défaut) | draft | cancelled
    metrics: [ENTITY_KILL]
    filter:
      entity: [ZOMBIE, ZOMBIE_VILLAGER, HUSK]   # ou special-mob: [rpgquest:forest_brute] ; item: [COD] pour la pêche
    ranking: HIGHEST_TOTAL       # HIGHEST_TOTAL (défaut) | BEST_SINGLE | COMMUNITY_GOAL | LOWEST_TOTAL
    rewards:                     # déclaratives : rien n'est livré par le moteur
      - {rank: 1, entitlement: title_zombie_slayer, label: "Titre « Pourfendeur de zombies »"}
      - {rank: 10, entitlement: badge_zombie_top10, label: "Badge Top 10"}
      - {threshold: 50, entitlement: badge_zombie_hunter, label: "Badge Chasseur (50 zombies)"}
  - id: grande_peche
    name: "La grande pêche"
    start: "2026-11-14T00:00:00Z"
    end: "2026-11-21T00:00:00Z"
    metrics: [FISH_CAUGHT]
    ranking: COMMUNITY_GOAL
    goal: 2000                   # total à atteindre ensemble
    rewards:
      - {community: true, entitlement: cosmetic_fisher_hat, label: "Chapeau de pêcheur pour tous les participants"}
```

Validation au chargement (fichier refusé **en entier** à la moindre erreur, comme au §4) : filtre
accepté seulement si **toutes** les métriques de l'événement savent l'appliquer (un filtre ignoré
fausserait le classement), valeurs au format Paper (`ZOMBIE`) ou id namespacé ; `goal` positif et
une seule métrique pour un objectif commun ; `LOWEST_TOTAL` refusé tant qu'aucune métrique n'est un
chrono (un joueur absent gagnerait avec 0) ; récompense = exactement un critère (`rank`, `threshold`
ou `community: true`), une clé `entitlement` et un `label`. `ranking`, `filter`, `goal` et
`rewards` sont réservés aux événements : une saison classe toujours par plus grand total.

### Classements

| Règle | Calcul | Usage |
| --- | --- | --- |
| `HIGHEST_TOTAL` | somme sur la fenêtre, plus grand en tête | « tuer le plus de zombies » |
| `BEST_SINGLE` | meilleure action unique (`MAX`) | plus gros octroi d'XP |
| `COMMUNITY_GOAL` | total de **tous** les participants (profils privés compris, aucun pseudo lu) contre `goal`, plus la liste des contributeurs | objectif commun |
| `LOWEST_TOTAL` | somme, plus petit en tête | réservé aux futures métriques chrono |

Top N, rang « compétition » et ex-aequo déterministes (pseudo puis UUID) : comme au §4. La
**position personnelle** hors top N attend l'identité du joueur connecté (#53).

### Cycle de vie — `stats.EventLifecycle`

`DRAFT → SCHEDULED → ACTIVE → ENDED → ARCHIVED`, et `CANCELLED`. `DRAFT` et `CANCELLED` sont des
décisions de l'administrateur (`status:`) ; les autres découlent **de l'horloge** (`[start, end)`) et
de l'archive — personne ne démarre un événement à la main.

- `DRAFT` : jamais exporté, jamais calculé, jamais archivé ;
- `CANCELLED` : exporté comme « Annulé », sans classement, jamais archivé ni récompensé (annulé après
  coup : l'archive reste en base mais n'est plus montrée) ;
- `ENDED → ARCHIVED` : au plus une minute après la fin, résultats figés (somme ou meilleure action
  selon la règle) dans `stat_period_results` ; ils ne bougent plus, même si une action tardive est
  rejouée ensuite ;
- **audit** : chaque transition observée (`SCHEDULED`, `ACTIVE`, `CANCELLED`, `ARCHIVED`) est notée
  une seule fois dans `stat_period_audit` (V40) et annoncée en console
  (`Statistiques : event « id » → ACTIVE.`).

### Export et portail

`statistics.events[]` porte en plus `ranking`, `filter`, `community` (`goal`, `total`, `reached`)
et `rewards` (`kind`, `value`, `label` — **jamais** la clé `entitlement`). Le portail relaie ces
clés par liste blanche (`PublicPlayerData`) et affiche sur `/classements?periode=<id>` : statut,
compte à rebours (avant le début, puis jusqu'à la fin, calculé à l'affichage), règles en français,
cibles, récompenses annoncées, barre de progression de l'objectif commun, classement live ou figé.

### Récompenses et modération

- le moteur **décrit** les récompenses ; ni le plugin ni le portail ne livrent d'objet. La livraison
  passera par les entitlements (#422), qui liront l'archive figée (rang, valeur, objectif atteint) ;
- modérer un score : exclusion par UUID (§4) pour l'événement ou pour tout, appliquée **à la
  lecture**, archives comprises — aucune correction silencieuse des données de jeu, le fichier fait
  foi (versionné ou sauvegardé par l'administrateur).

### Restant (hors de cette tranche)

- back-office PlugAdmin : création en brouillon, aperçu, validation, planification, annulation,
  consultation de l'audit (aujourd'hui : édition de `statistics.yml`) ;
- inscription préalable, exigence d'un compte lié (#53), environnement/serveur (`server_id`) ;
- nouvelles actions du journal (blocs minés hors blocs posés, boss, temps de jeu) et métriques chrono ;
- purge ou agrégation du journal d'activité après archivage (volumétrie à surveiller) ;
- saisons complètes, pass, guildes, jury humain : hors périmètre du ticket.

## 7. Exploitation

Hébergement, déploiement, rollback et vérifications : [deployment/WEB_PORTAL_AWS.md](deployment/WEB_PORTAL_AWS.md).
Configuration du processus et jeton d'API : [WEB_API.md](WEB_API.md).

Garde-fous en place sur l'instance publique (`/etc/lodyquests-portal/web-api.properties`) :
`player-data-public=false` (aucun pseudo publié, même si un instantané arrivait),
`accounts-enabled=false` (aucun compte, aucune base `accounts.db`),
`store-enabled=false` (aucune boutique), écoute sur `127.0.0.1` seulement, `/api/` fermé par nginx.
Le portail lit `snapshot.json` dans `/var/lib/lodyquests-portal/web-export/`, que le plugin y dépose
par `POST /api/snapshot` (#421, [WEB_API.md](WEB_API.md)). L'export du plugin (`web-export.enabled`)
reste désactivé sur DEV et aucun jeton d'instantané n'est configuré : `/health` dit
`"snapshot":"MISSING","server":"UNKNOWN"` et le site affiche « état inconnu », pas « hors ligne ».

## 8. V1 publique (#56) — faite

Pages publiques, sans compte, servies par `web-api` (aucun script, aucune ressource externe, une
seule colonne lisible sur téléphone, zones cliquables de 44 px, focus clavier visible) :

| Page | Contenu | Sans instantané (serveur de jeu arrêté) | `player-data-public=false` |
| --- | --- | --- | --- |
| `/` | présentation, statut du serveur, saison et événements (noms et dates), 3 dernières actualités, message du serveur, aperçu de 2 classements (top 3), « Comment rejoindre » | présentation et actualités intactes, statut « État inconnu » (« Hors ligne » seulement après un arrêt annoncé par le serveur, #421) | aperçu remplacé par « ouvriront quand… » ; aucun pseudo |
| `/actualites`, `/actualites/<slug>` | liste paginée (10 par page), filtre `?categorie=`, article complet | **inchangées** : ne lisent pas l'instantané | inchangées |
| `/classements` (alias `/leaderboards`) | onglets « Depuis toujours », saison, événements (`?periode=<id>`) ; top 10 par métrique, rang du plugin (ex aequo), statut et dates de la période, date de relevé | « Aucun classement » | « pas encore publiée » |
| `/joueurs`, `/joueurs/<pseudo>`, `/joueurs?pseudo=` | liste alphabétique paginée (60), recherche, profil : XP par piste, quêtes, rangs « depuis toujours » ; initiale à la place d'un skin (pas de service tiers ni d'UUID) | liste vide | « pas encore publiée » |
| `/infos` | rejoindre (`server-address`, `minecraft-version`), premiers pas, règles de base, FAQ | inchangée | inchangée |
| `/robots.txt`, `/sitemap.xml` | pages stables et actualités publiées ; **jamais** de profil ; `/compte` exclu | — | — |

Chaque page a un titre, une description et un lien canonique (`public-base-url`). Les données de
joueurs passent toutes par la liste blanche `privacy.PublicPlayerData`. Un pseudo inconnu ou masqué,
un brouillon, une actualité programmée, une période ou une catégorie inconnue → 404 identique.
Limite de fréquence : celle du portail (nginx 10 req/s, `rate-limit-per-minute` par visiteur).

### Actualités — fichiers `news-dir/<slug>.md`

Indépendantes du jeu et de Git (pas de commit à faire pour publier). Sur l'instance AWS :
`/var/lib/lodyquests-portal/news/`, propriété de root, lue seulement par le service, relue au plus
toutes les 30 s (aucun redémarrage). Le nom du fichier est l'adresse : minuscules, chiffres, tirets.

```text
title: Maintenance du samedi            # 120 caractères au plus
summary: Le serveur sera fermé 1 heure.  # 300 au plus, affiché dans les listes
date: 2026-10-18T09:00:00+02:00         # fuseau obligatoire ; date future = programmée
status: published                       # published | draft (brouillon, jamais visible)
category: maintenance                   # news | announcement | maintenance | event | release | story
author: L'équipe LodyQuests             # facultatif
tags: serveur                           # facultatif, 8 au plus
---
Texte. Une ligne vide = nouveau paragraphe. « ## Intertitre », « - liste », **gras**,
`commande`, [lien](/infos) ou [lien](https://…) — rien d'autre : aucun HTML n'est interprété.
```

Un fichier invalide (clé inconnue ou manquante, date sans fuseau, trop long, pas en UTF-8…) est
ignoré seul ; la raison apparaît une fois dans `journalctl -u lodyquests-portal` (« Actualité
ignorée <fichier> : <raison> »), jamais son contenu. `/health` donne le nombre d'actualités visibles
(`"news"`). Pas d'image en V1 (aucun service de fichiers statiques). Exemple installé en brouillon :
`scripts/web-portal/news/bienvenue.md`.

**Évolution prévue** : édition depuis PlugAdmin (back-office existant, sans second système
d'authentification), qui écrira ces mêmes fichiers.

### Hors V1

API JSON publique (l'API `/api/*` reste à jeton et fermée par nginx), cartes de partage avec
image, avatar de skin, historique saisonnier des profils, statistiques globales du serveur (pas de
source agrégée fiable dans l'instantané aujourd'hui), métriques kills/boss (§4, journal à créer).
