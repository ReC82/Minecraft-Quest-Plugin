# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 13:11
* Sujet : #135 — diagnostic en lecture seule des waypoints nommés « Avant-poste N »
* Statut : DONE (diagnostic) — correction en attente de décision propriétaire
* Branche Git : `feature/135-diagnostic-avant-poste` (depuis `chore/257-ci-github-actions` `5b13e31f`)
* Commit actuel si disponible : voir PR
* Début de la tâche : 2026-10-21 13:07:52
* Fin de la tâche : 2026-10-21 13:11:45
* Durée totale : 00:03:53

## Demande

Section « Diagnostic propriétaire — 20/10/2026 » de #135 : expliquer pourquoi les derniers
waypoints découverts s'appellent « Avant-poste N » (ex. « Avant-poste 17 — désert »), **en
lecture seule** : chemin d'attribution, état du catalogue, comptages sur DEV, ampleur du
phénomène, proposition d'un lot de 500 à 1 000 noms et d'un seuil d'alerte, stratégie pour les
noms déjà attribués. Interdits : toute écriture sur DEV (catalogue, waypoints, bornes, données
joueurs), migration, déploiement ayant un effet sur le nommage.

## Analyse

### Code responsable

- `waypoint.model.WaypointNameCatalog` charge une réserve **statique, bundlée dans le JAR**
  (`src/main/resources/waypoint-names.txt`) : **220 noms** distincts après dédoublonnage.
- `WaypointService#attemptGeneration` appelle `NAME_CATALOG.reserveName(seed, usedDisplayNames)`
  de façon synchrone au moment de poser un nouveau waypoint. `usedDisplayNames` est rempli au
  démarrage avec **tous** les waypoints persistés (tous mondes, actifs ou non), normalisés
  (casse/accents/espaces).
- `reserveName` parcourt la réserve dans un ordre mélangé par la seed et prend le premier nom
  libre. **Si aucun ne l'est**, il génère `"Avant-poste " + n` avec le plus petit `n` libre. Il
  n'existe **aucune règle par biome ou par monde** : le catalogue est global ; le suffixe
  « — désert » vu par le propriétaire est le biome affiché à côté du nom, pas une partie du nom.
- Aucun journal ni compteur n'avertit quand la réserve est vide : le passage au fallback est
  silencieux par conception (« jamais un échec de génération »).

### Mesures DEV (lecture seule)

DEV est arrêté depuis le 20/10 23:02 UTC (dernier heartbeat de l'agent) : aucun relevé agent
frais n'était possible. Deux sources lues, **aucune écriture** :

1. Dernier résultat `travel.catalog` déjà présent dans la base PlugAdmin (19/10 09:15) :
   195 waypoints, 194 noms du catalogue utilisés, **26 encore libres**, 0 « Avant-poste ».
2. Copie de `RPGQuest/data.db` téléchargée par FTP (serveur arrêté, donc cohérente ;
   `integrity_check` = ok), analysée localement hors du dépôt, ouverte en `-readonly` :

| Mesure | Valeur |
|---|---|
| Waypoints | **247** (wild 219, world_hub 28), tous actifs |
| Noms du catalogue attribués | **220 / 220** — 0 disponible |
| Nom hors catalogue | 1 (« Allée du Vent », renommage #134 ; le nom d'origine a été rendu à la réserve puis réattribué) |
| Noms « Avant-poste » | **26** (« Avant-poste 1 » à « Avant-poste 26 », suite continue) |
| Doublons de nom (normalisés) | 0 |
| Rang du premier « Avant-poste » | 222ᵉ waypoint par date de création |
| Waypoints nommés par le catalogue après le premier « Avant-poste » | **0** |
| Waypoints créés le 20/10 | 47 (record ; 26 d'entre eux en fallback, de 13:35 à 19:43 UTC) |
| Découvertes de waypoints « Avant-poste » | 13 découvertes, 13 waypoints distincts, 1 seul joueur |

Liste des 26 (identifiant · biome) : `wp_wild_forest_7_16` forest · `wp_wild_savanna_9_-3` ·
`wp_wild_savanna_10_-3` · `wp_wild_plains_-11_-7` · `wp_wild_sunflower_plains_-10_-7` ·
`wp_wild_jungle_-3_2` · `wp_wild_plains_-4_3` · `wp_wild_savanna_-2_-2` · `wp_wild_beach_-1_-8` ·
`wp_wild_plains_-2_-8` · `wp_wild_desert_7_-3` · `wp_world_hub_beach_3_-3` (Hub) ·
`wp_wild_eroded_badlands_-1_2` · `wp_wild_eroded_badlands_-1_3` · `wp_wild_stony_shore_-1_3` ·
`wp_wild_desert_0_3` · **`wp_wild_desert_-1_4` = « Avant-poste 17 » (désert)** ·
`wp_wild_old_growth_birch_forest_7_9` · `wp_wild_forest_8_9` ·
`wp_wild_old_growth_birch_forest_8_9` · `wp_wild_old_growth_birch_forest_7_8` ·
`wp_wild_forest_7_7` · `wp_wild_birch_forest_-11_-15` · `wp_wild_stony_shore_-11_-15` ·
`wp_wild_plains_-8_5` · `wp_wild_beach_-8_5`.

### Cause réelle

**Épuisement de la réserve**, pas un défaut de chargement : le catalogue se charge bien
(220 noms attribués), il n'en reste simplement plus. Le phénomène n'est pas limité : **tous** les
waypoints créés depuis le 20/10 13:35 UTC reçoivent le fallback, et ce sera le cas de tous les
suivants tant que la réserve n'est pas rallongée. La suite continue 1…26 montre en outre
qu'aucune réservation n'a été perdue (pas de trou).

### Incidence sur la recherche et le voyage

- Les noms restent **uniques** (index SQL `idx_waypoints_display_name` + ensemble mémoire) : le
  voyage, les découvertes et les panneaux fonctionnent normalement.
- La recherche du menu de voyage filtre sur « nom + biome » normalisés : chercher « avant »
  renverra les 26 ; les noms sont peu mémorisables et se distinguent surtout par leur biome.
- Les panneaux posés affichent le nom persisté : les « Avant-poste » sont donc inscrits en dur
  sur leurs panneaux, comme tout autre nom.

### Point secondaire relevé (non bloquant, non corrigé)

Si la persistance d'un nouveau waypoint échoue ou perd une course (`persist`, branche
`!inserted` ou `exceptionally`), le nom réservé reste dans `usedDisplayNames` jusqu'au prochain
redémarrage (fuite de réservation en mémoire). Sans effet observé sur DEV (aucun trou), à
corriger avec le lot qui touchera l'attribution.

## Travail effectué

- Audit du code d'attribution (ci-dessus) et mesures DEV en lecture seule.
- **Reproduction simulée** : `WaypointNameExhaustionDiagnosticTest` — avec la réserve réelle
  du JAR, les 220 premières attributions donnent des noms du catalogue, les suivantes
  « Avant-poste 1, 2, … » dans l'ordre (aucune base, aucun serveur).
- **Lot de noms proposé, non importé** : `docs/waypoints/waypoint-names-proposition-lot1.txt`,
  **600 noms** générés hors ligne sans IA (script de grammaire local, seed fixe), même style que
  la réserve (`<nom> de <nom>`, `<nom> des <pluriel>`, adjectif accordé en genre), chacun ≤ 40
  caractères, valide pour `validateDisplayName` (#134), sans doublon interne ni avec les 220 noms
  existants (casse/accents/espaces), jamais préfixé « Avant-poste ». Ces garanties sont vérifiées
  par le même test. Le fichier est dans `docs/` : **il n'est pas chargé par le plugin**. Le
  propriétaire doit le relire (quelques associations peuvent sembler étranges et sont à retirer
  librement).
- Documentation : constat ajouté dans `docs/TRAVEL.md` (section « Noms de waypoints »).

### Propositions (aucune appliquée)

1. **Correction immédiate la plus simple** : après relecture, ajouter le lot à
   `waypoint-names.txt` et livrer un JAR. Effet : les prochains waypoints reçoivent de nouveaux
   noms ; les 247 existants ne changent pas (la migration V22, seule à relire ce fichier en base,
   ne s'exécute plus sur une base déjà migrée). Le mélange par seed change, sans conséquence sur
   l'unicité.
2. **Cible #135 (imports successifs depuis PlugAdmin)** : stocker les noms importés hors du JAR
   (table `waypoint_names` avec état disponible/désactivé, ou fichier `waypoint-names.yml`
   modifiable via l'agent). C'est une **décision de schéma / format** : à trancher par le
   propriétaire avant développement. Prévisualisation avec comptes (lignes, valides, doublons
   internes, doublons catalogue, invalides, importés) ; idempotence par clé normalisée.
3. **Seuil d'alerte proposé** : avertissement (log au démarrage + bandeau PlugAdmin) quand moins
   de **50** noms sont libres — le 20/10 a consommé 47 noms en une journée — ou moins de 10 % de
   la réserve si celle-ci devient grande ; compteur « noms libres / total / Avant-poste » sur la
   page Voyages. Pas d'application automatique.
4. **Noms déjà attribués** : conservés par défaut. Renommage **individuel et explicite** des
   « Avant-poste » via l'outil #134 existant (même `id`, découvertes, borne, emplacement et
   destinations ; le nouveau nom doit être libre). Aucun renommage en masse automatique.

## Fichiers créés

- `docs/waypoints/waypoint-names-proposition-lot1.txt` — lot de 600 noms proposé (non chargé).
- `src/test/java/com/lodygames/rpgquest/waypoint/model/WaypointNameExhaustionDiagnosticTest.java`
- `docs/claude-reports/2026-10-21_1311_135-diagnostic-avant-poste.md` (ce rapport).

## Fichiers modifiés

- `docs/TRAVEL.md` — constat DEV et pointeur vers la proposition.
- `docs/SUIVI_VALIDATIONS_DEV.md` — ligne `0h` #135 (Bloqué : décision propriétaire) et point 19
  des dépendances. Le registre de `origin/main` ne contenait aucune modification plus récente à
  reporter (seulement d'anciennes versions de lignes déjà à jour sur la branche d'intégration).
- `docs/claude-reports/README.md` — index.

## Base de données / migrations

Aucune. La copie de `data.db` a été lue localement en lecture seule, hors du dépôt, et n'est pas
committée.

## Configuration / données

Aucune modification sur DEV : aucun fichier de catalogue, waypoint, panneau, découverte ou donnée
joueur touché ; aucune action agent mise en file ; aucun redémarrage.

## Tests automatiques

`./gradlew :test --tests 'com.lodygames.rpgquest.waypoint.model.*'` (RPGQUEST_TEST_MAX_HEAP=768m) :
`WaypointNameCatalogTest` 7/7, `WaypointNameExhaustionDiagnosticTest` 2/2 — verts. Suite complète
laissée à la CI GitHub de la PR.

## Tests manuels à effectuer

Aucun pour ce diagnostic. Après décision et livraison d'un correctif : `PENDING MANUAL
VALIDATION` — découvrir un nouveau waypoint et vérifier qu'il porte un nom du nouveau lot.

## Résultat attendu

Le propriétaire dispose de la cause (réserve de 220 noms épuisée le 20/10), des chiffres DEV et
d'un lot de 600 noms à relire, et choisit la voie de correction.

## Reset / retour à l'état initial

Rien à défaire : seuls un test et des documents ont été ajoutés.

## Déploiement VeryGames

Aucun déploiement (interdit pour ce diagnostic). Le test et la proposition n'ont aucun effet sur
le JAR fonctionnel.
