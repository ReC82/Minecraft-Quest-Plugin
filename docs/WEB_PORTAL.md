# Portail communautaire RPGQuest (issues #52 à #57)

Document de cadrage et d'état. Il décrit ce qui **existe dans le code** et ce qui est **conçu mais
pas encore réalisé**, sans mélanger les deux. Dernière mise à jour : nuit du 12 au 13/10/2026.

## 1. Cadrage (#52)

### Ce que le portail est

- un site **public**, consultable sans compte : actualités, statut, classements, profils publics
  limités, wiki, confidentialité ;
- **distinct de PlugAdmin** : autre processus (`web-api/`), autre port, aucun compte ni permission
  d'administration partagés. PlugAdmin administre, le portail montre ;
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

| Étape | Ticket | État |
| --- | --- | --- |
| Instantané v2 : statistiques par portée, profils publics | #54, #56 | **fait** (cette nuit) |
| Retrait du profil par le joueur, page confidentialité | #55 | **fait** (socle) ; politique juridique à valider avant ouverture |
| Pages `/joueurs`, classements des quêtes, API `/api/profiles` | #56 | **fait** |
| Comptes joueurs et liaison UUID par code en jeu | #53 | conçu (§2), non réalisé |
| Saisons, puis moteur d'événements | #54, #57 | conçus (§4, §6), non réalisés |
| Récompenses d'événement via les services du plugin | #57 | conçu, non réalisé |

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

Fait :

- l'instantané ne contient **ni UUID, ni IP, ni position, ni inventaire, ni date de connexion** ;
- un joueur se retire du portail en jeu : `/profile web prive` (variable persistante
  `WEB_PROFILE_HIDDEN`, par UUID). Il disparaît de **tous** les classements et profils dès l'export
  suivant — le filtre est appliqué dans les requêtes SQL d'export (`player.PublicProfilePolicy`),
  donc aucune donnée le concernant n'atteint l'instantané. `/profile web public` annule,
  `/profile web` affiche le réglage ;
- un pseudo inconnu et un pseudo masqué reçoivent **la même** réponse (« aucun profil public ») ;
- page `/confidentialite` décrivant exactement ce comportement.

À trancher avant toute ouverture publique (décision du propriétaire, pas du code) :

- profil **visible par défaut** (comportement actuel, hérité des classements existants) ou
  **masqué par défaut** avec consentement explicite ;
- âge minimal et consentement parental selon les pays visés ; droits d'accès et d'effacement une
  fois les comptes ouverts ; durée de conservation des comptes inactifs.

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

Le portail lit `plugins/RPGQuest/<web-export.output-dir>/snapshot.json` : l'export doit être
activé (`web-export.enabled: true`). Démarrage, configuration et jeton d'API : voir
[WEB_API.md](WEB_API.md). Aucune nouvelle variable d'environnement n'est introduite par ce lot.
