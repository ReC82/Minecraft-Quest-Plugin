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

## 00:56 — Lots B/C/D validés, et une correction du diagnostic de la V1

`./gradlew :test --tests '*building.*'` → **BUILD SUCCESSFUL**, 81 tests dans
`building.plan`, 0 échec.

Mais le passage au vert a demandé de réécrire trois classes de test, et la raison
mérite d'être écrite noir sur blanc, parce qu'elle **corrige mon propre diagnostic
du lot A** :

> Le plan V1, inchangé, ré-expansé par le moteur du lot B, produit **2 368 blocs
> au lieu de 2 210**. Les 158 blocs de différence sont les **triangles de pignon**
> que la primitive `gable` ne remplissait pas.

Autrement dit : sur les cinq défauts constatés en jeu, **le quatrième — « 62 % des
pignons ouverts » — n'était pas une faute du modèle, mais une faute du moteur.**
La primitive `gable` posait une rangée d'escaliers par rang et ne fermait jamais
les extrémités. N'importe quel plan, même parfait, aurait eu les combles béants.

Conséquences concrètes :

- `ArchitecturalAuditV1RegressionTest` n'assure plus cinq refus mais **quatre** —
  `NO_ENTRANCE`, `GLAZING_MISSING`, `NO_LIGHT`, `LEVEL_UNREACHABLE` — et vérifie
  explicitement qu'`ENVELOPE_OPEN` **n'est plus levé**, ce qui est la preuve que
  le correctif porte sur les plans déjà enregistrés sans les modifier.
- `openEnvelopeCellsHigh` passe de 118 à **0** sur la V1.
- Le test du lot A qui figeait la limite (« la primitive gable actuelle ne peut pas
  satisfaire le contrôle d'enveloppe ») était écrit pour être retourné le jour où
  le lot B atterrirait. Il l'est, et il vérifie maintenant en plus que mon contrôle
  d'enveloppe **ne refuse pas** un vrai toit en pente — le faux positif que je
  craignais le plus.
- La part de `stone_bricks` passe de 55 % à 63 % de l'enveloppe, parce que faute de
  `fill:` déclaré ce plan remplit ses pignons avec le matériau du toit. Les deux
  valeurs restent sous le seuil de 70 % : la monotonie des matériaux était un défaut
  esthétique réel, mais elle **n'a jamais été l'un des cinq refus**.

### Ce que cela implique pour les plans déjà enregistrés

Le même plan produit désormais un bâtiment différent — meilleur, mais différent.
Je ne régénère rien : le `.schem` de la V1 sur `buildsite_0012` reste tel quel,
conformément à l'interdiction explicite. Mais si quelqu'un relance une génération
depuis ce plan, l'empreinte du schematic changera, et le mécanisme de version de
#234 signalera à juste titre le bâtiment placé comme « obsolète ». C'est le
comportement souhaitable : la correction est visible, pas silencieuse.

## 01:11 — Lot F (#254) : le prompt, l'inspection à blanc et la boucle de correction

Trois pièces, dont une qui n'était pas prévue au découpage et sans laquelle les
deux autres ne tenaient pas.

### 1. L'inspection à blanc — la pièce manquante

En écrivant la boucle de correction, je me suis heurté à un fait simple : **le seul
moyen de connaître le verdict du serveur était de générer**. Donc d'écrire un
`.schem`, une définition et une entrée de bibliothèque… pour apprendre que le plan
était mauvais, puis recommencer en cochant « remplacer ». Une boucle de correction
bâtie là-dessus laisserait derrière elle une succession de bâtiments ratés.

D'où `BuildingGenerator.inspect(planYaml)` et l'action `building.plan.audit` :
même lecture, même expanseur, même audit, **aucune écriture**. Déclarée en
`BUILDING_READ` dans le catalogue, parce qu'elle n'écrit rien — et c'est ce qui
permet de juger un plan autant de fois qu'il le faut.

Le refus est rédigé **une seule fois** (`refusalText`) et partagé par la génération
et l'inspection : deux textes divergents apprendraient deux choses différentes du
même refus. Un test le vérifie en comparant les deux messages caractère par
caractère.

Les cinq maillons ont été câblés et **testés** — `AgentActionType`,
`AgentActions`, `BukkitAgentActions`, le `switch` de `AgentActionExecutor`,
`AgentActionCatalog`. C'est la leçon de #239 : une action absente du dispatch ne
produit aucune erreur, elle disparaît simplement.

### 2. Le prompt

Le diagnostic du lot A disait que le prompt n'était pas le fautif principal — le
**vocabulaire** l'était. Le prompt enseigne donc maintenant les primitives du
lot B, et surtout il **interdit explicitement au modèle de calculer** :

> Tu conçois la structure. Le moteur calcule les éléments architecturaux fiables.

Avec la convention de façade (`FRONT`/`BACK`/`LEFT`/`RIGHT`, « JAMAIS une
coordonnée »), le contrat architectural en sept cases à cocher, et un exemple
complet qui respecte son propre contrat — un test vérifie ce dernier point, parce
qu'un exemple qui enfreindrait la consigne qu'il illustre serait la pire des
consignes.

Deux tests figent l'alignement prompt ↔ serveur : les sept points énoncés sont
exactement les sept contrôlés. Un contrat divergent produirait des refus que le
prompt n'annonçait pas, et c'est ce qui rend une boucle de correction frustrante
*et* coûteuse.

### 3. La boucle, et son budget

`BuildingCorrectionLoop` reçoit deux fonctions — interroger le modèle, faire
auditer le plan — et ne connaît ni HTTP, ni Minecraft. Elle est donc éprouvée
**entièrement hors ligne, sans dépenser un jeton**, alors que c'est la pièce dont
une erreur coûte de l'argent réel. Quatorze tests.

Les garde-fous, et pourquoi :

- le budget est **borné à la construction** (`[1, 5]`) : aucun formulaire ne peut
  demander vingt tentatives ;
- un appel qui **échoue est quand même compté** : chez certains fournisseurs il est
  déjà facturé, et compter autrement ferait du budget une illusion ;
- le **mode automatique est explicite** ; par défaut la boucle s'arrête au premier
  refus et rend la main, motif sous les yeux. Personne ne doit découvrir une
  dépense ;
- un modèle qui **ressert le même plan** interrompt la boucle plutôt que d'être
  repayé ;
- un **agent injoignable n'est pas un refus** : relancer le modèle parce que le
  serveur n'a pas répondu serait payer pour une information qu'on n'a pas obtenue.

### Une limite que je ne franchis pas seul

Le panel communique avec le plugin **par scrutation** : il met une action en file,
le serveur la consomme et poste le résultat. Il n'existe aucun appel synchrone.
L'enchaînement automatique de plusieurs tentatives exigerait donc un **worker de
fond côté panel**, qui attendrait les verdicts entre deux appels au modèle. C'est
une décision structurante — un nouveau fil d'exécution, un nouvel état persistant,
et des appels payants déclenchés sans personne devant l'écran.

J'ai donc livré l'**itération au clic** : « Auditer ce plan » (gratuit, n'écrit
rien), le verdict affiché avec ses mesures, puis « Demander un plan corrigé » qui
transmet au modèle le motif réel **et les chiffres mesurés sur la grille**. Le
moteur de boucle complet est écrit et testé, prêt à être branché sur un worker le
jour où ce choix sera arbitré.

### Aucune génération payante cette nuit

Conformément à la consigne, **aucun appel à un fournisseur d'IA n'a été émis**.
Toute la chaîne est éprouvée sur des modèles scriptés. L'enchaînement réel
prompt → plan → audit → correction reste donc `PENDING MANUAL VALIDATION`.

## 01:27 — Lot E (#253) implémenté, et une erreur de ma part rattrapée

### Lot E — l'aperçu 2D

`PlanPreview` prend des coupes sur la **grille expansée** : un plan par niveau
(nord en haut, niveaux vides omis sauf le rez-de-chaussée), les quatre façades
vues de l'extérieur, l'ancre, les dimensions mesurées et les avertissements de
l'audit. Nouvelle action `building.plan.preview`, déclarée en **lecture**.

Les coupes sont calculées par le **plugin**, comme le ticket l'exige. Un test le
justifie sur un plan scellé en miniature : un aperçu dessiné par le panel depuis
le texte du plan aurait reproduit l'erreur du modèle — percement en `x = 5`, mur
en `x = 0` — et aurait montré une porte qui n'existe pas.

`BuildingGenerator.examine` devient le **chemin unique** : inspection, aperçu et
génération partagent la même lecture, la même expansion et le même audit.

### Deux désalignements prompt ↔ moteur, trouvés par relecture

Avant de lancer la validation, j'ai relu ligne à ligne les clés que le prompt
annonce contre celles que le lecteur et les primitives acceptent réellement.
Deux écarts :

- `pillars` : le prompt annonçait « spacing (défaut 3) », le moteur applique 4
  avec un minimum de 2 ;
- `ladder` : le prompt annonçait une clé `offset` que la primitive **ignore** —
  elle se place par `from`/`to`, la façade ne servant qu'à l'orientation. Un
  modèle écrivant `offset: 3` aurait obtenu une échelle ailleurs, sans erreur.

Corrigés. C'est exactement le genre de divergence que je reproche à un contrat
mal aligné, et aucun test ne l'aurait attrapée : les deux côtés étaient
cohérents avec eux-mêmes.

### L'erreur : j'ai commité le contenu local du propriétaire

`CrystalHuntIntegrationTest` a échoué dans le worktree **propre**, ce qui n'avait
aucune raison d'arriver. Cause trouvée immédiatement : le commit `165f9d0` a été
préparé avec `git add -A src`, qui a balayé six fichiers de contenu appartenant
au propriétaire — cinq nouveaux (`mira_cartographer`, `jeff_skeleton`,
`lily_pumpkin`, `st0_meet_people`, `lily_memories`) et la modification locale de
`crystal_hunt.yml`, dont la version du propriétaire ne satisfait pas les attentes
du test d'intégration.

C'est une consigne explicite de la mission que j'ai enfreinte. Rattrapage :

1. les six fichiers sauvegardés hors du dépôt, avec empreintes ;
2. `git rm --cached` sur les cinq nouveaux — ils redeviennent non suivis, et
   `git rm --cached` ne touche pas le disque, donc ils n'ont jamais bougé ;
3. `crystal_hunt.yml` ramené à sa version de `40e8176` dans l'historique, puis la
   version du propriétaire restaurée dans l'arbre de travail ;
4. commit `8b447be`, **additif**. Historique non réécrit : la correction se voit,
   ce qui vaut mieux qu'un historique maquillé.

Vérifié après coup : `crystal_hunt.yml` porte à nouveau l'empreinte
`9de7de59…` du propriétaire, et les cinq autres sont bien en `??`. Rien de perdu.

Le build en cours était invalidé par ce fichier : arrêté, et relancé après
correction. Désormais je mets en scène des **chemins explicites**, jamais
`git add -A`.

## 01:33 — Un troisième désalignement, et le pire : `axis`

La compilation du lot E a échoué (`Slice.text()` n'existait que côté panel, ajouté
des deux côtés), puis un test a révélé un problème de **conception du langage**,
pas une coquille :

> `axis:` désignait l'**axe du faîte** pour `gable` (`x` ou `z`) et la **direction
> de montée** pour `stair_run` (un point cardinal).

Un seul mot-clé pour deux vocabulaires. Un modèle écrivant `axis: x` sur un
escalier — ce que la symétrie avec `gable` invite à faire — voyait son plan
**refusé**. Mon propre test l'a écrit ainsi spontanément, ce qui est la meilleure
démonstration du piège.

Corrigé dans le **langage** et pas dans le prompt : `stair_run` lit désormais
`direction:` (un point cardinal), `axis` reste accepté pour ne casser aucun plan
déjà écrit, et le message de refus dit maintenant quoi écrire plutôt que de lister
des valeurs sans expliquer la différence.

C'est le troisième écart prompt ↔ moteur de la nuit, et les trois ont la même
forme : chaque côté était cohérent avec lui-même. Aucun test ne pouvait les
attraper — seulement la relecture croisée des clés annoncées contre les clés lues.

## 02:32 — Validation complète VERTE

`./gradlew test build` depuis le worktree propre `/srv/rpgquest/worktree-nuit` @ `83d65c1`.

| Module | Tests | Classes | Échecs | Erreurs |
|---|---|---|---|---|
| plugin | 2 384 | 225 | 0 | 0 |
| panel | 1 336 | 103 | 0 | 0 |
| **total** | **3 720** | **328** | **0** | **0** |

`BUILD SUCCESSFUL`. JAR produit : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar`, 2 334 717 octets.

Il a fallu trois lancements, et les deux premiers ont échoué pour des raisons que
je consigne telles quelles :

1. le premier a été **invalidé par ma propre erreur** — la version du propriétaire
   de `crystal_hunt.yml` commitée par mégarde faisait échouer
   `CrystalHuntIntegrationTest` ;
2. le second a buté sur `:control-panel:compileJava` : `getOrDefault` refuse une
   valeur par défaut typée sur une `Map<?, ?>` à clé capturée. Le module panel
   n'avait jamais été compilé avec le lot E — les tests ciblés que j'avais lancés
   précédaient son écriture.

La leçon est la même dans les deux cas : **compiler les deux modules après chaque
lot**, et ne pas laisser un module hors du cycle sous prétexte que les tests
ciblés de l'autre passent.
