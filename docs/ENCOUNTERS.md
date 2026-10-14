# PNJ récurrents par biome (issue #13)

Des PNJ « de rencontre » apparaissent automatiquement dans le Wild, un par **instance de biome**
explorée, et proposent des quêtes secondaires d'exploration. Un même **modèle** (brigand, marchand
égaré, forgeron perdu…) se retrouve dans de nombreuses instances : c'est un type de PNJ
*récurrent*, jamais un spawn infini.

> Statut : **étape 1 livrée** (moteur, modèles, persistance, apparition sûre, réparation).
> À venir : onglet PlugAdmin « PNJ récurrents » et actions *Localiser / Retenter / Désactiver*,
> apparence (skin), quêtes secondaires renouvelables, onglet Quêtes « Secondaires / Rencontres ».

## Instance de biome

Le découpage est **celui des waypoints** (`waypoint.model.BiomeInstanceKey`) :
`(monde, biome, tuile de travel.waypoint.region-size blocs)`. Deux forêts éloignées sont deux
instances ; une forêt et une plaine dans la même tuile aussi. Les deux systèmes partagent ce
découpage mais **pas leurs états** : une instance peut avoir un waypoint et une rencontre, l'un
n'attend jamais l'autre, et rien n'est modifié dans la génération des waypoints. Compromis hérité :
une très grande forêt à cheval sur deux tuiles peut accueillir deux rencontres.

## Modèles — `plugins/RPGQuest/encounters/*.yml`

Un fichier par modèle. Trois exemples sont copiés au premier démarrage, jamais réécrits ensuite.

| Champ | Obligatoire | Rôle |
| --- | --- | --- |
| `id` | oui | identifiant du modèle (`[a-z0-9._-]`) |
| `display_name` | oui | nom affiché au-dessus du PNJ (texte simple, 64 car., sans balise) |
| `biomes` | oui | biomes éligibles ; `forest` = `minecraft:forest` |
| `npc_id` | non (défaut `id`) | identité RPGQuest de tous les PNJ du modèle : dialogue `rpgquest:<npc_id>`, objectifs « parler à » |
| `description`, `role`, `family` | non | libellés pour l'administration, sans effet mécanique |
| `worlds` | non (vide) | restreint à certains mondes autorisés |
| `weight` | non (10) | poids du tirage si plusieurs modèles acceptent le même biome (1–1000) |
| `max_sites` | non (0) | plafond d'occurrences du modèle ; 0 = pas de plafond propre |
| `skin_url` | non | lien MineSkin (appliqué à une étape ultérieure) |
| `enabled` | non (`true`) | `false` = plus aucune nouvelle occurrence |

Livrés : `forest_bandit` (forêts → `encounter_bandit`), `desert_merchant` (désert et badlands →
`encounter_merchant`), `mountain_smith` (collines, prairies, pics → `encounter_smith`), chacun
avec un dialogue d'accueil `dialogues/encounter_*.yml`, partagé par toutes ses occurrences.

Un fichier invalide est rejeté seul (les autres chargent) ; un biome inconnu du serveur est signalé
dans les logs. Les modèles sont relus au démarrage (rechargement à chaud : étape PlugAdmin).

## Apparition

Quand un joueur **entre** dans une instance (mouvement throttlé, comme les waypoints) :

1. le monde doit être autorisé (`encounters.worlds`, vide = `travel.wild-world`) ; Hub et monde des
   claims sont **toujours** exclus ;
2. Citizens doit être actif, `encounters.enabled` vrai, le plafond `max-per-world` non atteint ;
3. un modèle actif, autorisé dans ce monde et ce biome, sous son plafond, est tiré (pondéré,
   **déterministe** par instance). Aucun modèle → rien, jamais un modèle d'un autre biome ;
4. jusqu'à `candidate-attempts` points sont testés entre `min-distance` et `max-distance` du joueur :
   chunk **déjà chargé** (aucun chargement forcé), même instance, sol accessible
   (`RandomSafeLocationFinder.findAccessibleColumn` : pas de canopée, pas d'eau ni de lave, deux
   blocs d'air), hors claim, à plus de `waypoint-clearance` d'un waypoint et de `minimum-spacing`
   d'une autre rencontre ;
5. la ligne `encounter_sites` est écrite **d'abord** (index unique `(world, biome_instance)`), puis
   le PNJ Citizens est créé, marqué `rpgquest-encounter = <id d'occurrence>`, regard vers les joueurs.

Pas d'emplacement : nouvel essai après 1 min, 5 min, 30 min puis 1 h (en mémoire, remis à zéro au
redémarrage), journalisé `NO_SAFE_SPOT`. Rien n'est généré au démarrage.

## Stabilité et réparation

- Position mémorisée : le PNJ réapparaît au même endroit, pour tous les joueurs.
- PNJ supprimé à la main (`/npc remove`) : recréé à la prochaine entrée dans l'instance ou au
  prochain chargement de son chunk — jamais deux.
- Arrêt brutal entre l'écriture et l'enregistrement du PNJ : le PNJ marqué est **réadopté**.
- Politique MVP : **présence stable**, aucune disparition aléatoire. Désactiver un modèle empêche
  les nouvelles occurrences sans retirer les existantes ; `encounters.enabled: false` arrête
  apparitions et réparations sans retirer les PNJ déjà présents.
- Une occurrence désactivée par un administrateur perd son PNJ et ne génère plus rien ; une
  occurrence retirée libère l'instance (nouvelle rencontre possible à la visite suivante). Ces
  actions existent dans le service (`retry`, `disable`, `remove`) et seront exposées dans PlugAdmin.

## Identité, dialogue, quêtes

Les PNJ de rencontre **ne passent pas** par `npc_citizens_bindings` (un `npc_id` n'y est lié qu'à
un seul PNJ Citizens). Leur identité vient de `encounter_sites` : `NpcIdentityService.currentId`
consulte d'abord les liaisons classiques, puis le registre des rencontres. Le clic droit ouvre donc
`rpgquest:<npc_id>` comme pour tout PNJ ; dialogue et progression restent personnels à chaque
joueur, le PNJ physique est partagé. Une occurrence désactivée ne porte plus d'identité.

## Configuration — `config.yml`, section `encounters`

| Clé | Défaut | Bornes |
| --- | --- | --- |
| `enabled` | `true` | |
| `worlds` | `[]` (= `travel.wild-world`) | Hub/claims toujours exclus |
| `max-per-world` | 200 | 0–5000 |
| `min-distance` / `max-distance` | 24 / 64 | 8–128 / min–160 |
| `candidate-attempts` | 12 | 1–32 |
| `move-throttle-millis` | 2000 | 250–60000 |
| `minimum-spacing` | 96 | 0–1024 |
| `waypoint-clearance` | 12 | 0–64 |

## Persistance

Migration **V34** : table `encounter_sites` (id, world, biome_instance, biome_key, region_x/z,
template_id, npc_id, x/y/z, citizens_uuid, citizens_id, status `ACTIVE|DISABLED`, last_error,
created_at, updated_at), index unique `(world, biome_instance)`. Additive, automatique.

## Validation en jeu

`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md`, TC-303.
