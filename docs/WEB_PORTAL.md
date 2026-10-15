# Portail communautaire RPGQuest (issues #52 à #57)

Document de cadrage et d'état. Il décrit ce qui **existe dans le code** et ce qui est **conçu mais
pas encore réalisé**, sans mélanger les deux. Dernière mise à jour : 15/10/2026 (#53, comptes joueurs et liaison Minecraft).

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
- pas de saisons ni d'événements actifs (le format les prévoit, le moteur est conçu en §6).

### Feuille de route, dans l'ordre des dépendances

État au 15/10/2026. « Fait » = dans le code et testé automatiquement ; la validation navigateur ou
en jeu par le propriétaire reste à faire là où c'est indiqué.

| # | Étape | Ticket | État |
| --- | --- | --- | --- |
| 0 | Socle d'hébergement AWS : service, nginx, `/health`, déploiement, rollback | #52 | **fait** — en ligne en HTTPS depuis le 15/10 (certificat Let's Encrypt ECDSA, échéance 13/01/2027, renouvellement automatique certbot) |
| 1 | Instantané v2 : statistiques par portée, profils publics | #54, #56 | **fait** |
| 2 | Confidentialité : profils privés par défaut, liste blanche, politique d'âge configurable, registre des données | #55 | **fait** (garde-fous, [PRIVACY.md](PRIVACY.md)) ; pays, seuils d'âge, durées de conservation et textes juridiques **à décider** par le propriétaire après revue juridique |
| 3 | Acheminer l'instantané du serveur Minecraft vers AWS | #421 | **à faire** — sans lui, le portail reste en mode dégradé |
| 4 | Publication des pseudos (`player-data-public=true`, `web-export` réactivé) | #55 → #56 | **attend la décision** du propriétaire (#55) ; seuls les joueurs ayant tapé `/profile web public` seraient publiés |
| 5 | V1 publique : actualités, profils, premiers classements | #56 | pages faites ; actualités éditables et publication à faire après 3 et 4 |
| 6 | Comptes joueurs et liaison UUID par code en jeu | #53 | **fait**, désactivé (`accounts-enabled=false`) ; export et suppression de compte à faire avant ouverture ; validation navigateur + jeu à faire |
| 7 | Saisons, puis moteur d'événements | #54, #57 | conçus (§4, §6), non réalisés |
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

`schemaVersion: 2` ajoute deux clés ; toutes les clés de la v1 sont inchangées.

```json
"statistics": {
  "scopes": ["lifetime"],
  "lifetime": {
    "RPG_XP_GLOBAL":   [{"name": "Alice", "value": 4500}],
    "QUESTS_COMPLETED":[{"name": "Alice", "value": 12}]
  },
  "season": null,
  "events": []
},
"profiles": [
  {"name": "Alice", "skills": {"GLOBAL": 4500}, "questsCompleted": 12}
]
```

## 4. Modèle de statistiques (#54)

Une statistique = **(métrique, portée, joueur) → valeur**, calculée **depuis une source de vérité
existante**, jamais depuis un compteur parallèle.

| Métrique | Source de vérité | Portée disponible |
| --- | --- | --- |
| `RPG_XP_<PISTE>` | `player_skills.total_xp` (XP RPG, #183 — pas les niveaux vanilla) | lifetime |
| `QUESTS_COMPLETED` | `quest_progress.state = COMPLETED` | lifetime |
| kills par espèce, blocs minés, temps de jeu… | à créer dans le plugin (événements) | — |

Portées :

- `lifetime` : depuis toujours. Seule alimentée aujourd'hui ;
- `season` : une période nommée `[début, fin)`. Les valeurs de saison se calculeront **par
  différence** de deux relevés `lifetime` (début et fin), sans nouvelle table de gameplay ;
- `events` : même principe, sur la fenêtre et le filtre d'un événement (§6).

Les métriques absentes du tableau n'existent pas encore : elles demandent un journal d'événements
côté plugin (kills, minage…), qui doit être écrit **une fois** et servir aussi bien aux quêtes
qu'aux classements.

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

## 6. Moteur d'événements et classements temporaires (#57) — conçu, non réalisé

Un événement est **une définition déclarative** (YAML, comme les quêtes), jamais du code :

```yaml
id: zombie_weekend
name: "Week-end des zombies"
metric: ENTITY_KILL          # une métrique du §4
filter: {entity: ZOMBIE}
ranking: HIGHEST_TOTAL       # HIGHEST_TOTAL | FIRST_TO_REACH | COMMUNITY_GOAL
start: 2026-11-07T18:00:00Z
end: 2026-11-09T22:00:00Z
visibility: PUBLIC
rewards: []                  # déclaratives, distribuées par les services du plugin
```

Cycle de vie : `DRAFT → SCHEDULED → ACTIVE → ENDED → ARCHIVED`, et `CANCELLED` depuis
`DRAFT`/`SCHEDULED`/`ACTIVE`. Le classement d'un événement terminé est **figé** (relevé final
archivé) : il ne bouge plus si des données changent ensuite.

Règles :

- le plugin calcule et exporte (`statistics.events`) ; le portail affiche ;
- les récompenses sont remises **par le plugin**, une seule fois par (événement, joueur, rang), avec
  la même idempotence que les récompenses de quêtes — jamais par une écriture SQL du portail ;
- les joueurs masqués (§5) ne sont pas classés publiquement ; s'ils restent éligibles à une
  récompense, c'est un choix à documenter dans la définition de l'événement.

## 7. Exploitation

Hébergement, déploiement, rollback et vérifications : [deployment/WEB_PORTAL_AWS.md](deployment/WEB_PORTAL_AWS.md).
Configuration du processus et jeton d'API : [WEB_API.md](WEB_API.md).

Garde-fous en place sur l'instance publique (`/etc/lodyquests-portal/web-api.properties`) :
`player-data-public=false` (aucun pseudo publié, même si un instantané arrivait),
`accounts-enabled=false` (aucun compte, aucune base `accounts.db`),
`store-enabled=false` (aucune boutique), écoute sur `127.0.0.1` seulement, `/api/` fermé par nginx.
Le portail lit `snapshot.json` dans `/var/lib/lodyquests-portal/web-export/` ; l'export du plugin
(`web-export.enabled`) reste désactivé sur DEV et aucun canal ne l'achemine encore (#421).
