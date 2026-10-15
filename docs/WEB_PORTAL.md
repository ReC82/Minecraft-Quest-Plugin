# Portail communautaire RPGQuest (issues #52 à #57)

Document de cadrage et d'état. Il décrit ce qui **existe dans le code** et ce qui est **conçu mais
pas encore réalisé**, sans mélanger les deux. Dernière mise à jour : 15/10/2026 (#52, hébergement AWS).

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

- pas de compte joueur ni de connexion (conception en §2) ;
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
| 6 | Comptes joueurs et liaison UUID par code en jeu | #53 | conçu (§2), non réalisé |
| 7 | Saisons, puis moteur d'événements | #54, #57 | conçus (§4, §6), non réalisés |
| 8 | Entitlements : récompenses remises par le plugin | #422 | à spécifier, après #53 et #57 |
| — | Premium (VIP, pass, boutique) | — | **hors périmètre** : après cadrage juridique et règles Minecraft ; `store-enabled=false` |

## 2. Comptes joueurs et liaison au compte Minecraft (#53) — conçu, non réalisé

Une saisie de pseudo ou d'UUID ne prouve rien. La liaison se prouve **en jeu** :

1. le joueur, connecté au portail, demande une liaison → le portail crée un **défi** : code de 8
   caractères (alphabet sans ambiguïté), valable 10 minutes, usage unique, lié à son compte web ;
2. en jeu, le joueur tape `/lien <code>` → le plugin, seul à connaître l'UUID authentique de
   l'émetteur, publie la preuve `{code, uuid, pseudo, horodatage}` dans un fichier d'échange (même
   mécanisme atomique que l'instantané), jamais dans la base du portail ;
3. le portail consomme la preuve : code connu, non expiré, non utilisé → liaison `compte ↔ UUID`.
   Un code faux, expiré ou déjà utilisé est refusé sans dire lequel des trois ;
4. un UUID ne peut être lié qu'à un compte à la fois ; délier se fait depuis le compte, relier
   exige un nouveau défi.

Exigences retenues : mots de passe hachés (Argon2id ou PBKDF2 fort), sessions à cookie `HttpOnly`
+ `Secure` + `SameSite=Lax`, jeton CSRF sur tout POST, limitation de débit sur la connexion et sur
la création de défis, journal des liaisons. **Aucune** permission PlugAdmin ne s'obtient par ce
compte. La table des comptes vit dans la base du portail, jamais dans `data.db`.

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
`store-enabled=false` (aucune boutique), écoute sur `127.0.0.1` seulement, `/api/` fermé par nginx.
Le portail lit `snapshot.json` dans `/var/lib/lodyquests-portal/web-export/` ; l'export du plugin
(`web-export.enabled`) reste désactivé sur DEV et aucun canal ne l'achemine encore (#421).
