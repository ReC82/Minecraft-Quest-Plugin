# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-12
* Heure : 01:30 (heure locale, CEST)
* Sujet : Générateur IA de structures V2 (#94) — audit architectural, primitives sémantiques,
  façades nommées, block states vérifiés, aperçu 2D et boucle de correction budgétée
* Statut : **PARTIAL** — les six lots A à F sont codés et testés, mais rien n'est déployé à l'heure
  où ces lignes sont écrites, et aucune génération réelle n'a été lancée
* Branche Git : `feature/249-audit-architectural`
* Commit actuel si disponible : voir la section « Commits » — le dernier commit du lot E est
  postérieur à la rédaction de cette en-tête
* Début de la tâche : 2026-10-11 23:37:00 *(première action horodatée de la mission : création des
  sous-tickets #249 à #254. L'instant exact d'arrivée de la consigne n'a pas été capturé.)*
* Fin de la tâche : à compléter en fin de session
* Durée totale : à compléter en fin de session

## Demande

Mission autonome de nuit : faire avancer #94 aussi loin que possible vers une **V2 réellement
testable**, avec autorisation d'analyser, décider les questions techniques ordinaires, créer et
mettre à jour des tickets GitHub, coder, tester, corriger, committer, pousser, ouvrir et fusionner
des PR, intégrer dans la ligne DEV, et déployer plugin et PlugAdmin si toutes les conditions de
sûreté et de validation sont réunies.

Consigne de priorité imposée : **A + B + C + D + F, puis E**.

Consignes explicites retenues :

- étudier d'abord `TestHutBlueprint` et `TestWatchtowerBlueprint`, et identifier ce qui peut être
  extrait, généralisé, réutilisé et couvert par des tests de régression ;
- philosophie directrice : « L'IA conçoit la structure. RPGQuest se charge de calculer les éléments
  architecturaux fiables » ;
- ne pas s'arrêter après un simple diagnostic ni après le premier lot ;
- tenir un journal horodaté, et garder les journaux de build dans des fichiers consultables ;
- **ne jamais annoncer une fonctionnalité comme livrée si elle est seulement codée localement**, ni
  annoncer un déploiement si la version installée n'est pas vérifiée ;
- ne pas fermer l'EPIC #94 ;
- interdictions sur le monde : ne pas toucher au bâtiment V1 posé sur `buildsite_0012`, ne pas
  restaurer le terrain, ne pas réorienter ni remplacer la construction, ne pas placer de nouvelle
  bibliothèque dans le Hub, ne pas modifier `buildsite_0006/0009/0010/0011/0012`, ne supprimer,
  renommer ni déplacer aucun PNJ, ne modifier aucune quête, story ou dialogue ;
- **ne pas lancer de génération IA payante sans budget explicitement autorisé.**

Complément reçu en cours de mission : autorisation exceptionnelle, **pour cette nuit uniquement**,
de contourner la validation du certificat TLS expiré de VeryGames afin de transférer le JAR, en
privilégiant `VERYGAMES_FTP_EXTRA_CURL_ARGS`, sans jamais désactiver la vérification TLS
globalement ni laisser de trace persistante.

## Analyse

### Ce qui existait, et ce qui manquait

Le pipeline de #94 était déjà presque complet avant cette mission :

```
Blueprint → SchematicGateway.write (WorldEdit) → .schem → BuildingDefinition → pose #213/#234/#239
```

Ce qui manquait n'était pas le pipeline mais **le jugement**. Le générateur validait la géométrie
d'un plan — bornes, volume, forme des états de bloc — et **rien de sémantique**. C'est ainsi que la
V1 de la Bibliothèque du Hub est passée en `SUCCESS` avec le message « prêt à être posé » alors
qu'elle était hermétiquement scellée.

### Le diagnostic, mesuré et non supposé

Le plan réel a été réextrait du `params_json` de l'action, ré-expansé par un modèle fidèle
(2 210 blocs attendus, **2 210 obtenus** — la fidélité du modèle était la condition préalable à
toute conclusion), puis compté :

| Mesure | Valeur |
|---|---|
| Cellules de mur sur les quatre façades | 532, dont **0 vide** |
| Plancher de l'étage | 289 / 289 plein |
| Pignons ouverts | 59 / 95 par face, soit 62 % |
| Colonnes ouvertes au ciel | **0 sur 361** — la couverture était bonne |
| Vitrages / lumières / portes / échelles | 0 / 0 / 0 / 0 |
| Part de `stone_bricks` dans l'enveloppe | 55 % |
| « Escalier » | 1 bloc |

Le percement de porte du modèle visait `x = 5`, alors que la façade ouest d'un plan `front: WEST`
est le plan `x = 0`. Il a donc vidé de l'air intérieur **déjà vide**.

### Le vrai fautif n'était pas le prompt

Lors de la session précédente, j'avais recalibré le prompt en croyant que le plafond
`max_output_tokens = 4000` poussait le modèle vers des volumes basiques. La mesure l'a démenti : le
plan n'utilisait que **21 instructions sur ~80 autorisées**. La contrainte mordante était la
**granularité du vocabulaire**, pas le plafond de jetons.

Avec cinq instructions de volume (`box`, `shell`, `walls`, `clear`, `gable`), « une porte » ne
pouvait s'écrire qu'en calculant soi-même une coordonnée de façade, l'orientation des deux moitiés,
le sens de la charnière et le dégagement intérieur. C'est une arithmétique que le modèle a ratée, et
qu'il n'avait aucune raison de réussir.

D'où la philosophie imposée par la demande, qui se retrouve littéralement dans le prompt livré :

> Tu conçois la structure. Le moteur calcule les éléments architecturaux fiables.

### Ce qui a été extrait plutôt que réinventé

Comme demandé, `TestHutBlueprint` et `TestWatchtowerBlueprint` ont été étudiés **avant** d'écrire la
moindre primitive. Ce sont les deux seules structures dont on sait qu'elles fonctionnent en jeu.
Leurs états de bloc ont été extraits dans `BlockStates` — porte, vitre, escalier, dalle, rondin,
lanterne, torche murale, échelle, trappe — et `BlockStateRulesTest` vérifie que les états **repris
verbatim de ces deux blueprints** sont acceptés. La généralisation part donc du code qui marche, et
non d'une idée de ce qui devrait marcher.

## Travail effectué

### Lot A (#249) — l'audit architectural

`ArchitecturalAudit.audit(Blueprint, Facing, ArchitecturalRules)` s'exécute dans
`BuildingGenerator` **avant toute écriture**, sur la **grille expansée**.

Le choix décisif : auditer la grille, jamais les instructions. Un plan peut écrire une instruction
nommée « porte » ; seule la grille dit s'il y a un trou dans le mur. La V1 l'a démontré.

| Contrôle | Règle | Sévérité |
|---|---|---|
| `NO_ENTRANCE` | passage de 2 blocs de haut traversant la façade `front`, près du sol | bloquant |
| `GLAZING_MISSING` / `GLAZING_ONE_SIDED` | ≥ 2 cellules vitrées, sur ≥ 2 façades | bloquant |
| `NO_LIGHT` / `DARK_AREA` | ≥ 1 source utile, aucune cellule praticable à plus de 8 blocs | bloquant |
| `ENVELOPE_OPEN` / `ROOF_MISSING` | couverture et pignons fermés (tolérance 4 cellules) | bloquant |
| `LEVEL_UNREACHABLE` | chaque niveau atteignable depuis le rez-de-chaussée | bloquant |
| `ENVELOPE_MONOTONOUS` / `MATERIAL_DOMINANT` | ≥ 3 matériaux, aucun au-delà de 70 % | avertissement |
| `FURNISHING_THIN` | intérieur meublé | avertissement |

**Pas de validation par nom de matériau**, comme exigé. `BlockSemantics` est un **registre déclaré**
— identifiants exacts, plus des familles déclarées sur la grammaire des identifiants Minecraft — qui
porte des *rôles* : vitrage, lumière avec son niveau, franchissable, grimpable, mobilier. Un test
documente ce que `contains("glass")` aurait coûté : il manquerait `glowstone`, `shroomlight`,
`redstone_lamp` et `campfire`, et prendrait une bougie de niveau 3 pour une source utile. Un bloc
sans rôle déclaré est de la matière opaque, ce qui est le cas normal.

**Seuils centralisés et dépendants du type**, comme exigé. `ArchitecturalRules` est un record, un
profil par `StructureKind` (`habitable`, `decoration`, `portal`), déclaré par le plan via `kind:`.
Exiger une porte et des fenêtres d'une fontaine aurait rendu l'audit inutilisable pour le décor
(#244) et l'architecture de portail (#228).

**Architecture extensible aux primitives du lot B**, comme exigé : le contrôle d'entrée cherche deux
cellules franchissables superposées, donc un percement brut et une vraie porte le satisfont **sans
qu'une ligne de l'audit change**.

### Lots B, C, D (#250, #251, #252) — le vocabulaire

**Lot B** — six primitives sémantiques : `door`, `window`, `light`, `stair_run`, `ladder`,
`pillars`. Le modèle décrit une intention ; le moteur calcule coordonnée, orientation, les deux
moitiés de porte, la charnière **et le dégagement intérieur**. Corrige au passage `gable`, qui ne
remplissait pas ses triangles de pignon.

**Lot C** — `Facade` (FRONT / BACK / LEFT / RIGHT) résout la façade depuis le `front` déclaré. Les
conventions sont arrêtées une fois pour toutes et vérifiées sur les quatre orientations :
`LEFT`/`RIGHT` sont vues depuis l'extérieur face au bâtiment, et `offset` part toujours du coin de
coordonnée minimale.

**Lot D** — `BlockStateRules` vérifie les propriétés réellement admises par famille de blocs.
`minecraft:oak_stairs[face=bottom,shape=straight]` est désormais refusé **avant toute écriture**, en
nommant la bonne propriété (`half`). Une famille non décrite voit ses propriétés acceptées sans
contrôle : refuser l'inconnu interdirait les milliers de blocs du jeu dont nous ne savons rien.

### Lot F (#254) — juger avant d'écrire, et la boucle

**L'inspection à blanc, qui n'était pas au découpage.** En écrivant la boucle, je me suis heurté à
un fait simple : le seul moyen de connaître le verdict du serveur était de **générer**. Donc
d'écrire un `.schem`, une définition et une entrée de bibliothèque pour apprendre que le plan était
mauvais, puis de recommencer en cochant « remplacer ». Une boucle de correction bâtie là-dessus
laisserait derrière elle une succession de bâtiments ratés.

D'où `BuildingGenerator.inspect()` et l'action **`building.plan.audit`**, déclarée en lecture.
Le texte du refus est rédigé **une seule fois** et partagé par la génération et l'inspection ; un
test compare les deux messages caractère par caractère.

**Le prompt** enseigne les primitives, la convention de façade, le `kind:`, le `fill:` du pignon, et
le contrat architectural en **sept cases à cocher**. Trois tests le gardent honnête : les sept points
énoncés sont exactement les sept contrôlés ; chaque primitive est effectivement enseignée ;
**l'exemple complet respecte son propre contrat** — un exemple qui enfreindrait la consigne qu'il
illustre serait la pire des consignes.

**`BuildingCorrectionLoop`** reçoit deux fonctions (interroger le modèle, faire auditer le plan) et
ne connaît ni HTTP, ni Minecraft. Elle s'éprouve donc entièrement hors ligne, **sans dépenser un
jeton**, alors que c'est la pièce dont une erreur coûte de l'argent réel.

| Garde-fou | Pourquoi |
|---|---|
| budget borné à `[1, 5]` **par le constructeur** | aucun formulaire ne peut demander vingt tentatives |
| un appel **échoué est compté comme dépensé** | chez certains fournisseurs il est déjà facturé |
| mode automatique **explicite**, pas par défaut | personne ne doit découvrir une dépense |
| arrêt si le modèle ressert le même plan | continuer serait payer pour la même réponse |
| un agent injoignable **n'est pas un refus** | relancer serait payer pour une information non obtenue |

La reprise transmet au modèle le motif réel **et les chiffres mesurés**. C'est la pièce la plus
efficace : un modèle est volontiers convaincu d'avoir posé des fenêtres, et « cellules vitrées : 0 »
le détrompe sans discussion.

### Lot E (#253) — l'aperçu 2D

`building.plan.preview`, déclarée en lecture elle aussi, rend les coupes du bâtiment : un plan par
niveau (nord en haut, niveaux vides omis sauf le rez-de-chaussée), les **quatre façades** vues de
l'extérieur avec l'avant désigné comme l'entrée, les dimensions mesurées, l'ancre avec la convention
qui s'applique, et les avertissements de l'audit.

**Les coupes sont calculées par le plugin**, comme le ticket l'exige. La raison est la V1 : un
aperçu dessiné par le panel depuis le texte du plan aurait reproduit l'erreur du modèle — un
percement visant `x = 5` là où le mur est en `x = 0` — et aurait donc montré une porte qui n'existe
pas. Un aperçu qui mentirait exactement là où on a besoin qu'il dise la vérité serait pire que pas
d'aperçu. Un test le vérifie sur un plan scellé en miniature.

Rendu en caractères (`#` mur, `o` vitrage, `+` franchissable, `/` marche, `H` échelle, `*` lumière,
`f` mobilier, `.` vide), issus du **même registre `BlockSemantics`** que l'audit : deux légendes
séparées finiraient par désigner deux choses différentes. Pas d'image, pas de JavaScript — la CSP du
panel interdit le script en ligne — et un texte à espacement fixe défile sur un téléphone.

L'aperçu est rendu **même pour un plan refusé** : c'est le cas où il sert le plus.

**Un seul chemin d'examen** : inspection, aperçu et génération partagent la même lecture, la même
expansion et le même audit (`BuildingGenerator.examine`). Trois implémentations finiraient par
diverger, et montreraient un aperçu qui ne correspond pas à ce qui sera construit.

## La découverte qui corrige le diagnostic

C'est le fait le plus important de cette session, et il contredit une de mes propres conclusions.

> Le plan V1 **inchangé**, ré-expansé par le moteur du lot B, produit **2 368 blocs au lieu de
> 2 210**. Les 158 blocs d'écart sont exactement les triangles de pignon.

Autrement dit : **l'un des cinq défauts constatés en jeu était imputable au moteur, pas au modèle.**
La primitive `gable` posait une rangée d'escaliers par rang et ne fermait jamais les extrémités ;
n'importe quel plan, même parfait, aurait eu les combles béants.

Il reste **quatre** défauts du modèle : pas de porte, pas de vitrage, pas de lumière, étage
inaccessible. Les tests de régression de la V1 l'affirment explicitement et vérifient
qu'`ENVELOPE_OPEN` n'est plus levé.

**Conséquence de versionnement.** Le même plan produit maintenant un bâtiment différent, et
meilleur. Aucun `.schem` déjà écrit n'est modifié, mais régénérer depuis un plan conservé changera
l'empreinte du schematic, et le mécanisme de version de #234 signalera à juste titre le bâtiment
posé comme obsolète. C'est le comportement souhaitable : la correction doit être visible, pas
silencieuse.

## Fichiers créés

**Plugin** (`src/main/java/com/lodygames/rpgquest/building/plan/`)

| Fichier | Rôle |
|---|---|
| `BlockSemantics.java` | registre déclaré des rôles de blocs |
| `StructureKind.java` | habitable / décoration / portail |
| `ArchitecturalRules.java` | les seuils, centralisés, un profil par type |
| `ArchitecturalFinding.java` | un constat : code stable, sévérité, phrase précise |
| `ArchitecturalAudit.java` | l'audit sur la grille expansée |
| `BlockStates.java` | les états de bloc, extraits des blueprints qui fonctionnent |
| `Facade.java` | les façades désignées par leur rôle |
| `BlockStateRules.java` | les propriétés admises par famille |
| `PlanPreview.java` | les coupes 2D |

**Panel** (`control-panel/src/main/java/com/lodygames/rpgquest/panel/`)

| Fichier | Rôle |
|---|---|
| `ai/BuildingCorrectionLoop.java` | la boucle et son budget |
| `building/BuildingPlanVerdict.java` | projection du verdict d'audit |
| `building/BuildingPlanPreview.java` | projection des coupes |

**Tests créés**

`ArchitecturalAuditTest`, `ArchitecturalAuditV1RegressionTest`, `BlockSemanticsTest`,
`ArchitecturalRulesTest`, `SemanticPrimitivesTest`, `FacadeTest`, `BlockStateRulesTest`,
`PlanPreviewTest` (plugin) ; `BuildingCorrectionLoopTest`, `BuildingPlanVerdictTest`,
`BuildingPlanPreviewTest` (panel).

**Journal**

`docs/claude-reports/JOURNAL-2026-10-11-nuit-generateur-v2.md` — horodaté, avec les chemins des
journaux de build.

## Fichiers modifiés

**Plugin** — `BuildingPlan`, `BuildingPlanYaml`, `BuildingPlanExpander`, `PlanStep`,
`BuildingGenerator`, `AgentActionType`, `AgentActions`, `BukkitAgentActions`,
`AgentActionExecutor`.

**Panel** — `BuildingPromptBuilder`, `AiBuildingStudio`, `AgentActionCatalog`, `AgentPages`,
`BuildingPlanPages`, `PanelApp`.

**Tests modifiés** — `BuildingGeneratorTest`, `BuildingPlanExpanderTest`,
`AgentActionExecutorTest`, `StubAgentActions`, `AgentActionCatalogTest`.

**Documentation** — `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations

**Aucune.** Ce lot n'ajoute ni table, ni colonne, ni index, et ne lit ni n'écrit aucune donnée
nouvelle. Le dernier schéma reste **V31** (la colonne `building_sites.ai_brief` de la session
précédente).

## Configuration / données

Aucun fichier de configuration modifié. Aucune clé API ajoutée ni requise. Aucun contenu de quête,
story, dialogue ou PNJ touché.

Les trois fichiers de contenu non suivis du propriétaire et la modification locale de
`crystal_hunt.yml` sont restés intacts : tous les builds ont été lancés depuis le worktree propre
`/srv/rpgquest/worktree-nuit`, jamais depuis le dépôt de travail.

## Tests automatiques

*(Section complétée en fin de session — voir « Résultat des validations » ci-dessous.)*

Discipline de mémoire respectée : `RPGQUEST_TEST_MAX_HEAP=640m`, `--max-workers=1`, **jamais deux
exécutions Gradle simultanées**. Les journaux complets sont dans
`scratchpad/build-lotA.log` et `scratchpad/build-final.log`, consultables sans relancer.

**Aucun test n'a été désactivé, ignoré ni affaibli pour obtenir un résultat vert.** Les seules
assertions modifiées l'ont été parce qu'elles étaient devenues **fausses** après le correctif de
`gable`, et chaque modification est justifiée en commentaire dans le test lui-même.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — aucun de ces points ne peut être couvert par un test automatisé, et
**aucun appel payant n'a été émis cette nuit**, conformément à la consigne.

| # | À vérifier |
|---|---|
| TC-94-V2-A | Depuis la fiche d'un emplacement pourvu d'un brief, « Générer un bâtiment avec l'IA » : l'avertissement de coût apparaît **avant** le bouton |
| TC-94-V2-B | Le plan produit par le modèle emploie bien `facade:` et les primitives `door` / `window` / `light`, et non des coordonnées calculées |
| TC-94-V2-C | « Auditer ce plan » : le verdict apparaît avec ses codes et ses mesures, et **aucun fichier** n'apparaît dans `schematics/` ni `buildings/` |
| TC-94-V2-D | « Prévisualiser ce plan » : les quatre façades et les plans par niveau s'affichent, avec légende, orientation d'entrée et ancre |
| TC-94-V2-E | Un plan refusé affiche quand même ses coupes, et le défaut s'y voit |
| TC-94-V2-F | « Demander un plan corrigé » : le motif réel **et les chiffres** sont transmis, et le plan suivant corrige le point désigné |
| TC-94-V2-G | Générer, poser **sur un emplacement libre**, et comparer le bâtiment en jeu aux coupes de l'aperçu |
| TC-94-V2-H | Vérifier que le vocabulaire de l'écran ne dit jamais « généré » pour un plan seulement audité |

Restent également ouverts, hérités des sessions précédentes : TC-197-A à H, TC-94-A à H,
TC-273/275/276/277/278/257/271/272, et le sort de `kit_tier2`.

## Résultat attendu

Un plan mauvais est **refusé** avant d'écrire quoi que ce soit, avec un motif précis et réutilisable
par le modèle. Un plan bon est auditable et prévisualisable **gratuitement** avant d'engager la
moindre écriture. Et le modèle, à qui l'on ne demande plus d'arithmétique, a une chance réelle de
produire un bâtiment où l'on peut entrer.

## Reset / retour à l'état initial

Rien à défaire : aucune migration, aucune donnée écrite, aucun bloc du monde touché. Un `.schem`
produit pendant un essai se retire à la main, avec sa définition `buildings/<id>.yml`.

## Déploiement VeryGames

### À transférer

* **Plugin** : le JAR RPGQuest uniquement.
* **Panel** : déploiement PlugAdmin distinct (`scripts/plugadmin/deploy.sh`) — **obligatoire**. Un
  panel périmé n'afficherait ni le bouton d'audit, ni l'aperçu, et continuerait d'envoyer
  **l'ancien prompt** au modèle, donc des plans sans portes ni fenêtres. **Aucun indicateur côté
  Minecraft ne révélerait l'oubli.**

### Ne PAS transférer/altérer

* le bâtiment V1 posé sur `buildsite_0012` — ne pas régénérer, ne pas remplacer, ne pas réorienter,
  ne pas restaurer le terrain ;
* les emplacements `buildsite_0006 / 0009 / 0010 / 0011 / 0012` ;
* aucun PNJ, aucune quête, aucune story, aucun dialogue ;
* `data.db`, les mondes, les plugins externes, les fichiers de configuration.

### Redémarrage requis

**Oui** pour le plugin (remplacement de JAR). Le panel se recharge par son propre service.

### Migration automatique

**Aucune migration.** Rien à surveiller au démarrage.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest`, puis redémarrer. Rien à défaire en base.
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Attention** : revenir en arrière côté panel **sans** revenir en arrière côté plugin laisse
  l'ancien prompt envoyer des plans que le nouvel audit refusera — bruyant et sans danger, mais
  déroutant. Faire les deux, ou aucun.

## Logs / diagnostic

| Fichier | Contenu |
|---|---|
| `scratchpad/build-lotA.log` | build complet du lot A |
| `scratchpad/build-final.log` | build complet de fin de session |
| `docs/claude-reports/JOURNAL-2026-10-11-nuit-generateur-v2.md` | journal horodaté de la nuit |

Le dossier `scratchpad` de la session est
`/tmp/claude-1000/-srv-rpgquest-repo/86cab748-c957-4d18-8f57-e2db35f448a6/scratchpad`.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — tableau des primitives refondu (volume + architecture), convention de
  façade, validation des block states, correction de la note devenue fausse sur `gable`, audit à
  blanc, aperçu 2D, boucle et budget.
* `docs/current_state.md` — entrée « Générateur V2 », avec les mesures, la correction de diagnostic
  et les limites assumées.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée datée, avec l'avertissement de changement de
  comportement sur les plans existants et l'obligation de déployer le panel.
* Aucune page `docs-site/` ne couvre les bâtiments : rien à répliquer.
* Tickets #249 à #254 commentés avec leurs critères d'acceptation et la vérification associée.

## Limitations / travail restant

1. **L'enchaînement automatique des corrections n'est pas branché.** Le panel parle au plugin par
   *scrutation* : il met une action en file, le serveur la consomme et poste le résultat. Il n'existe
   aucun appel synchrone. Enchaîner plusieurs tentatives exigerait un **worker de fond côté panel** —
   un nouveau fil d'exécution, un nouvel état persistant, et des appels payants déclenchés sans
   personne devant l'écran. C'est une décision structurante, et je ne l'ai pas prise seul. Le moteur
   de boucle est écrit et testé, prêt à être branché. **Ce qui est livré est l'itération au clic.**
2. **Aucune génération réelle n'a été lancée** — consigne respectée. Toute la chaîne est éprouvée sur
   des modèles scriptés, donc le comportement du modèle face au nouveau prompt est **inconnu**.
   C'est la plus grande inconnue qui reste.
3. **Les conventions d'ancre ne sont pas encore explicitées dans l'interface** (lot C, reste à
   faire). L'aperçu dit laquelle s'applique, mais la fiche d'un emplacement ne l'explique pas. Rien
   n'a été changé rétroactivement sur les emplacements ni les bâtiments posés.
4. **L'isométrie légère** du lot E n'est pas faite. Les coupes orthogonales répondent aux questions
   qu'on se pose réellement devant un plan ; une vue isométrique en caractères serait difficile à
   lire sans rien ajouter.
5. **`FURNISHING_THIN` reste un simple avertissement.** Refuser un bâtiment pour un intérieur
   dépouillé serait trop strict tant que le vocabulaire de mobilier est aussi mince.
6. L'EPIC #94 **n'est pas fermé**, conformément à la consigne.

## Prochaine étape suggérée

**Lancer une génération réelle**, puisque c'est la seule inconnue qui reste et qu'aucun test ne peut
la lever. Concrètement : sur un emplacement **libre** du Hub, demander un plan, l'auditer, le
prévisualiser, et comparer. Trois issues possibles, et chacune est une information utile :

* le plan passe l'audit du premier coup → le vocabulaire était bien le fautif, et c'est démontré ;
* il est refusé mais la correction aboutit en une ou deux passes → la boucle fait son travail ;
* il est refusé de façon répétée sur le même point → le prompt doit être précisé **sur ce point
  précis**, et le motif de refus dira lequel.

Ensuite seulement, trancher la question du worker de fond : elle ne se pose vraiment que si la
correction manuelle se révèle fastidieuse en pratique.
