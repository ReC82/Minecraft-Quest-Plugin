# Journal de nuit — Générateur IA de structures V2 (#94)

Session autonome du 2026-10-11 au 2026-10-12. Horodatages en heure locale (CEST).
Journaux de build consultables sans relancer : voir la section « Fichiers de log ».

## Fichiers de log

| Fichier | Contenu |
|---|---|
| `scratchpad/build-lotA.log` | build complet du lot A |
| `scratchpad/build-final.log` | build complet de fin de session (si atteint) |

> Le dossier `scratchpad` de la session est
> `/tmp/claude-1000/-srv-rpgquest-repo/86cab748-c957-4d18-8f57-e2db35f448a6/scratchpad`.

## Ordre de priorité retenu

Imposé par la mission : **A + B + C + D + F, puis E**.

## Chronologie

### 23:18 — Diagnostic V1 (session précédente, rappel)
Mesuré sur le plan réel : bâtiment scellé (532 cellules de façade, 0 vide), étage inaccessible
(plancher 289/289 plein), pignons ouverts à 62 %, 0 vitre, 0 lumière, 0 porte, 0 échelle.

### 23:37 — Sous-tickets créés
#249 (A), #250 (B), #251 (C), #252 (D), #253 (E), #254 (F), avec dépendances. Carte sur #94.

### 23:40–23:55 — LOT A implémenté
`BlockSemantics`, `StructureKind`, `ArchitecturalRules`, `ArchitecturalFinding`,
`ArchitecturalAudit` + câblage dans `BuildingPlan`/`BuildingPlanYaml`/`BuildingGenerator`.
Commit `8b31128` sur `feature/249-audit-architectural`.

Tests ciblés verts : `ArchitecturalAuditV1RegressionTest` (7), `ArchitecturalAuditTest` (14),
`BlockSemanticsTest` (7), `BuildingGeneratorTest` (11), balayage `*Building* *Plan* *Block*` vert.

Deux bugs attrapés par les tests pendant l'écriture :
- `glass_pane` ne finit pas par `_glass_pane` → famille par suffixe inopérante, déclaré en exact ;
- `EnumSet.copyOf` lève sur collection vide → toute source de lumière sans autre rôle cassait
  l'initialisation statique de la classe.
Un avertissement retiré car c'était du bruit : « blocs inconnus » en comptait 339 (briques,
planches). Un bloc sans rôle déclaré n'est pas une anomalie.

### 23:56 — Build complet lot A lancé
Worktree propre `/srv/rpgquest/worktree-nuit` @ `8b31128`, `--max-workers=1`, heap de test 640 Mo.

### 00:14 — Build en cours
Module plugin `:test` toujours en cours à 18 min. Aucun second processus Gradle lancé (règle de
mémoire). Travail poursuivi sur ce qui ne compile pas : étude des blueprints existants et
conception du lot B.

### 00:20 — Autorisation FTPS reçue (complément à la section 14)
Contournement TLS autorisé **pour cette mission de nuit uniquement**, via la variable temporaire
`VERYGAMES_FTP_EXTRA_CURL_ARGS`, aux conditions suivantes que je m'engage à tenir :
vérifier à chaque transfert qu'il s'agit bien du **certificat VeryGames expiré attendu** et non
d'une autre erreur TLS ; ne rien laisser dans Git, les scripts, les fichiers de configuration ni
l'environnement persistant ; ne déployer qu'après tests **et** build complets verts ; vérifier le
JAR réellement installé et son empreinte SHA-256 ; respecter les règles de redémarrage et de
présence des joueurs. L'autorisation expire à la fin de cette mission.

### 00:18 — Étude des blueprints existants (consigne §2)
`TestHutBlueprint` et `TestWatchtowerBlueprint` relus. Ce qui en est **extrait** plutôt que
réinventé :
- porte : `oak_door[facing,half=lower|upper,hinge,open=false,powered=false]`, **deux** blocs ;
- vitre : `glass_pane[east,north,south,west,waterlogged]`, connexions selon l'axe du mur ;
- escalier : `oak_stairs[facing,half=bottom,shape=straight,waterlogged=false]` — la propriété est
  bien **`half`**, et non `face` comme l'avait écrit le modèle ;
- dalle : `oak_slab[type=bottom,waterlogged]` ; tronc : `oak_log[axis=y]` ;
- torche murale : `wall_torch[facing]` ;
- **volée d'escalier** : une marche par pas, `facing` = sens de montée, et une **trémie** sautée
  dans le plancher au-dessus (tour de garde, `floorsAndHatches`) ;
- **pas de 4 entre planchers** = 3 blocs libres + 1 plancher, condition d'un étage praticable.

D'où `BlockStates`, point de passage **unique** pour tout état à propriétés : un état produit par le
moteur est correct par construction, et la liste blanche du lot D n'aura qu'un endroit à décrire.
