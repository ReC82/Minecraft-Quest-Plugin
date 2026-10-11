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

### 00:20–00:24 — LOTS B, C et D écrits (non encore compilés)
Un seul processus Gradle à la fois : le build du lot A occupe la machine, j'ai donc écrit sans
compiler et je compilerai d'un bloc.

**Lot B — primitives sémantiques.** `BlockStates` (point de passage **unique** pour tout état à
propriétés, extrait des blueprints qui fonctionnent) + six primitives dans `PlanStep.Kind` :
`DOOR`, `WINDOW`, `LIGHT`, `STAIR_RUN`, `LADDER`, `PILLARS`, et `GABLE` **corrigé**.

Décisions prises seul, documentées :
- `trim` **non ajouté** : `walls` sur une tranche d'un bloc de haut produit déjà un bandeau de
  pourtour en une instruction. Ajouter une primitive redondante aurait grossi le vocabulaire sans
  rien permettre de nouveau.
- Les primitives acceptent un `block` **optionnel** avec un défaut (lanterne, porte en chêne,
  escalier en chêne) : exiger l'identifiant exact obligerait le modèle à s'en souvenir.
- Si le modèle écrit malgré tout des propriétés (`oak_door[facing=west]`), le moteur **ne garde que
  le type** et recalcule : accepter ses propriétés annulerait tout le bénéfice des primitives.
- `gable` prend un `fill` distinct pour les pignons — un escalier employé en remplissage laisse des
  creux visibles.

**Lot C — façades.** `Facade` (`FRONT`/`BACK`/`LEFT`/`RIGHT`) résolue depuis `front`. Le décalage
court selon `+X` pour une façade nord/sud et selon `+Z` pour une façade est/ouest : le décalage 0
est donc toujours le coin de coordonnée minimale, repère prévisible quelle que soit l'orientation.
Un nom de façade inconnu est **refusé**, jamais replié sur `FRONT` — sinon une faute de frappe
sortirait un bâtiment avec sa porte du mauvais côté sans que rien ne le signale.

**Lot D — block states.** `BlockStateRules` : table déclarée par famille (escaliers, portes,
trappes, dalles, panneaux, lanternes, échelles, troncs, torches murales, portillons) avec les
propriétés **et** leurs valeurs. Refuse `oak_stairs[face=bottom]` en nommant `half`. Un bloc d'une
famille non décrite est **accepté** : refuser l'inconnu interdirait les milliers de blocs du jeu
dont nous ne savons rien, alors que la faute observée porte sur ce que nous écrivons nous-mêmes.

**Correction d'une faiblesse de mon lot A.** Mon contrôle d'enveloppe comptait toute cellule vide
des faces latérales au-dessus de la bande d'entrée. Un vrai toit en pente aurait donc été signalé à
tort : au-dessus de ses avant-toits, les faces latérales sont du ciel ouvert, pas une fuite. Le
critère devient : une cellule vide de face latérale n'est un trou que s'il y a de la **matière
au-dessus d'elle dans sa colonne**. Et j'ai ajouté un contrôle distinct `ROOF_MISSING` (colonnes
sans aucune couverture), car un bâtiment sans toit du tout n'a aucune face « sous couverture » et
échappait au premier contrôle.

Tests écrits : `SemanticPrimitivesTest`, `FacadeTest`, `BlockStateRulesTest`.
