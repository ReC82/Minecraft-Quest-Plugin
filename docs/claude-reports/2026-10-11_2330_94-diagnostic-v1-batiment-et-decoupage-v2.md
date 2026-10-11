# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-11
* Heure : 23:30
* Sujet : Issue #94 — diagnostic technique de la V1 générée (Bibliothèque du Hub) et découpage en lots testables pour une V2
* Statut : DONE — diagnostic mesuré et découpage proposé. **Aucune modification de code, aucun bâtiment touché.**
* Branche Git : `feature/238-guard-dialogue-ux` (aucun commit de code)
* Commit actuel si disponible : `9f71767`
* Début de la tâche : 2026-10-11 23:18:00
* Fin de la tâche : 2026-10-11 23:30:00 (approximatif — voir note)
* Durée totale : 00:12:00

> Note : la rédaction de ce rapport s'est poursuivie après la dernière mesure ; l'horodatage de fin
> correspond à la fin de l'analyse technique, pas à la dernière frappe.

## Demande

Analyser les causes du mauvais résultat architectural de la V1 et proposer les corrections, **sans se
limiter au prompt** : examiner aussi les capacités du langage de construction, la gestion des
matériaux et des block states, les orientations et les validations architecturales. Garantir sept
critères minimaux (entrée fonctionnelle, fenêtres vitrées, éclairage, toit cohérent, accès aux
niveaux, façades travaillées, aménagement conforme au brief). Quand ils ne sont pas respectés, le
système doit **identifier les problèmes et demander une correction à l'IA** plutôt que produire une
construction médiocre. Étudier également une prévisualisation visuelle depuis PlugAdmin.

Commencer par un diagnostic technique et un découpage en lots testables. **Ne pas modifier le
bâtiment placé ni les autres emplacements du Hub.**

## Analyse

### Méthode

Je n'ai pas raisonné sur les captures. J'ai récupéré le **plan réellement envoyé** (paramètre de
l'action `building.generate` dans la base du panel, 1 700 caractères, 21 instructions), puis je l'ai
**expansé** avec un modèle fidèle de `BuildingPlanExpander` et mesuré la grille obtenue.

Contrôle de fidélité du modèle : il produit **2 210 blocs**, exactement le nombre que le serveur a
rapporté. Les chiffres ci-dessous portent donc sur la géométrie réelle du bâtiment posé.

### Ce que les mesures disent

**Le bâtiment est hermétiquement fermé.** Sur les quatre façades, entre y=1 et y=7, soit
4 × 133 = 532 cellules de mur : **zéro cellule vide**.

| Façade | Cellules vides | Total |
|---|---|---|
| Ouest (x=0) | **0** | 133 |
| Est (x=18) | **0** | 133 |
| Nord (z=0) | **0** | 133 |
| Sud (z=18) | **0** | 133 |

Ce n'est donc pas « la porte n'est pas visible » : **il n'y a aucune ouverture**. Le modèle avait
bien tenté une porte — deux fois — mais aux mauvaises coordonnées :

```
- op: clear
  from: [5, 1, 9]     # ← x=5 : de l'air intérieur, déjà vide
  to:   [5, 2, 9]
```

Le mur ouest est en **x=0**. Le modèle a visé x=5, soit cinq blocs à l'intérieur, dans un volume
déjà creusé à l'instruction précédente. Son `clear` n'a donc rien percé du tout. Même erreur pour
le second (`[9,1,6] → [9,2,6]`).

**L'étage est inaccessible.** Le plancher posé en y=3 compte 289 cellules, **toutes pleines**. Le
modèle avait prévu une trémie :

```
- op: clear
  from: [8, 4, 8]     # ← y=4 : un niveau AU-DESSUS du plancher
  to:   [10, 4, 10]
```

Le plancher est en y=3 ; le `clear` est en y=4. Il a vidé de l'air, pas percé le sol.

**Les pignons du toit sont ouverts à 62 %.** Sur chaque face d'extrémité (x=0 et x=18, y=9 à 13) :
**59 cellules vides sur 95**. Ce sont les « grandes ouvertures » constatées.

**En revanche le toit couvre bien par le dessus** : sur 361 colonnes (x,z), **aucune** n'est à ciel
ouvert. Il faut être précis — le toit n'est pas « troué » verticalement, ce sont ses **pignons
triangulaires** qui ne sont pas fermés.

**Quatre critères minimaux échouent de façon absolue**, par simple comptage de matériaux :

| Critère | Blocs trouvés |
|---|---|
| Fenêtres vitrées (`*glass*`) | **0** |
| Éclairage (torche, lanterne, glowstone…) | **0** |
| Porte (`*door*`) | **0** |
| Échelle (`*ladder*`) | **0** |

**Et les façades sont monomatière** : 6 matériaux au total, dont `stone_bricks` pour **1 221 blocs
sur 2 210 — 55 %**. Aucun relief : l'enveloppe est un unique `walls` de 19 × 19 × 7.

```
  1221  minecraft:stone_bricks
   553  minecraft:dark_oak_stairs
   289  minecraft:dark_oak_planks
    97  minecraft:oak_planks
    49  minecraft:bookshelf
     1  minecraft:oak_stairs      ← « l'escalier »
```

L'escalier ne compte **qu'un seul bloc** : le modèle en avait posé deux, et son propre plancher d'étage
(instruction 12) a recouvert le second.

**Un état de bloc invalide est passé** : `minecraft:oak_stairs[face=bottom,shape=straight]`. La
propriété `face` n'existe pas pour un escalier — c'est `half=bottom|top`. Mon contrôle
`BlockState.valid()` ne vérifie que la **forme** (minuscules, pas d'espace, `clé=valeur`), jamais
les noms de propriétés. Ce que WorldEdit en a fait reste à vérifier : les 2 210 blocs ont bien été
écrits, donc il ne s'est pas arrêté — il a soit ignoré la propriété, soit pris un défaut.

### Les causes, par ordre d'importance

**A. Le vocabulaire n'a aucune primitive sémantique — c'est la cause principale.**

`clear` est le seul moyen de faire une ouverture, et il exige du modèle qu'il calcule lui-même les
coordonnées exactes du mur. Il n'existe **ni** `door`, **ni** `window`, **ni** `light`, **ni**
`stair_run`, **ni** `ladder`. Or chacun de ces éléments demande des block states précis qu'un modèle
de langue rate : une porte est **deux** blocs (`half=lower` et `half=upper`) avec `facing` et
`hinge` cohérents ; une vitre en panneau a quatre états de connexion ; une lanterne a
`hanging=true|false`.

Résultat : les quatre critères qui échouent à zéro ne sont pas des oublis du modèle, ce sont des
éléments que **le langage ne sait pas exprimer** autrement qu'à la main, bloc par bloc.

**B. Rien n'ancre sémantiquement les façades.**

`front: WEST` est déclaré, et le moteur s'en sert pour la rotation à la pose — mais le plan n'a
aucun moyen de dire « sur la façade avant ». Le modèle doit traduire WEST → x=0 lui-même. Il a
traduit en x=5. C'est exactement la classe d'erreur qui a scellé le bâtiment.

**C. Ma primitive `gable` est incomplète.**

Relecture du code : elle pose **une seule rangée par rang, à une seule hauteur**. Cela produit une
couverture correcte vue du dessus, mais **ne remplit jamais les triangles de pignon** — d'où les
62 % d'ouverture mesurés. Le modèle a aggravé le problème en empilant un **second `gable` croisé**
(`axis: z`) sur le même volume, et en perçant quatre `clear` à travers ses propres rangées de toit
pour « creuser les combles ».

**D. Aucune validation architecturale.**

Le générateur valide la **géométrie** (zone dans le volume, pas de doublon, coût borné) et refuse
proprement — il a d'ailleurs refusé le premier plan (`GABLE` hors volume). Mais il ne valide **rien
de sémantique**. Une boîte scellée, sans porte, sans fenêtre, sans lumière et dont l'étage est
inaccessible est passée en `SUCCESS`, et le message a annoncé « prêt à être posé ». C'est le défaut
le plus grave, parce qu'il transforme une mauvaise proposition en artefact validé.

**E. Le contrôle de block state est purement syntaxique.** Voir `face=bottom` ci-dessus.

**F. Le budget de jetons n'était PAS le facteur limitant — je m'étais trompé.**

Hier, en voyant `max_output_tokens = 4000`, j'avais recalibré le prompt à 30-80 instructions en
supposant que le plafond pousserait vers des volumes basiques. La mesure dit autre chose : le modèle
n'a utilisé que **21 instructions**, soit le quart de ce que je lui autorisais. Il n'a pas été bridé
par le budget, il a été bridé par la **granularité du vocabulaire** : avec `box`/`walls`, ajouter du
relief coûte une instruction par bandeau, et meubler coûte une instruction par objet. Rien ne lui
permet de dire « un pilastre tous les 4 blocs ».

La correction d'hier n'était pas inutile (un plan long serait tronqué), mais elle visait le mauvais
goulot. Il faut rendre les instructions **plus expressives**, pas seulement en autoriser davantage.

**G. L'ancre par défaut est ambiguë.** Sans section `anchor`, le moteur prend le centre de la base —
ici (9, 0, 9). Si l'administrateur a marqué l'emplacement là où il veut **l'entrée**, centrer le
bâtiment est un contresens. Le ticket le signalait ; la mesure le confirme.

## Travail effectué

Diagnostic uniquement. Extraction du plan réel depuis la base du panel, expansion fidèle, et six
séries de mesures (ouvertures de façade, plancher d'étage, pignons, couverture, matériaux, états de
bloc). **Aucun fichier de code modifié, aucun bâtiment touché, aucun emplacement du Hub modifié.**

## Fichiers créés

* `docs/claude-reports/2026-10-11_2330_94-diagnostic-v1-batiment-et-decoupage-v2.md` (ce rapport)

## Fichiers modifiés

Aucun.

## Base de données / migrations

Aucune. Lecture seule d'une **copie** de `control-panel.db` pour extraire le plan.

## Configuration / données

Aucun changement. La V1 reste en place sur `buildsite_0012`, son `.schem` et sa définition sont
intacts, et aucun autre emplacement n'a été touché.

## Tests automatiques

Aucun test ajouté (lot de diagnostic). Les 3 598 tests de la ligne d'intégration restent verts — ce
lot ne touche à rien.

Fidélité du modèle de mesure vérifiée par recoupement : 2 210 blocs calculés = 2 210 blocs rapportés
par le serveur.

## Tests manuels à effectuer

Aucun pour ce lot. Les mesures remplacent une inspection visuelle : les quatre critères à zéro et
les 532 cellules de mur pleines sont des faits, pas des impressions.

## Résultat attendu

Un découpage que l'on peut exécuter et tester lot par lot, sans retoucher un plan YAML à la main.

## Découpage proposé

### Lot A — Contrat architectural vérifié sur la grille *(à faire en premier)*

Une classe **pure** `ArchitecturalAudit`, sur la grille expansée, qui produit des constats
structurés — bloquants ou avertissements — et dont le refus est renvoyé à l'IA par la boucle de
correction **qui existe déjà**.

Contrôles, tous mesurables :

| Contrôle | Règle vérifiable |
|---|---|
| Entrée | ≥ 1 ouverture de 2 blocs de haut dans la façade `front`, reliée à l'extérieur **et** à l'intérieur |
| Fenêtres | ≥ N cellules vitrées réparties sur ≥ 2 façades |
| Éclairage | ≥ 1 source de lumière par volume fermé, et aucune cellule de circulation à plus de K blocs d'une source |
| Toit | aucune colonne à ciel ouvert **et** pignons fermés (le défaut de la V1 n'était pas la couverture) |
| Accès aux niveaux | remplissage par diffusion depuis l'entrée : chaque plancher est atteignable (escalier, échelle ou trémie) |
| Façades | ≥ 3 matériaux dans l'enveloppe, et aucun matériau au-delà de X % |
| Aménagement | avertissement seulement : présence de blocs cohérents avec le brief — ne jamais prétendre juger l'esthétique |

**Pourquoi en premier :** c'est le seul lot qui transforme « produit silencieusement un bâtiment
médiocre » en « refuse et explique ». Il rend aussi **mesurable** chaque itération suivante. Pur,
sans Minecraft, entièrement testable par fixtures — y compris sur le plan V1, qui devient un cas de
test : il doit échouer sur cinq contrôles précis.

### Lot B — Primitives sémantiques

| Primitive | Ce qu'elle garantit |
|---|---|
| `door {on, at, block}` | les deux moitiés avec `half`, `facing`, `hinge` cohérents, et l'espace dégagé |
| `window {on, from, to, block, pane}` | percement + vitrage avec les états de connexion corrects |
| `light {box, spacing, block}` | lanternes/torches réparties, `hanging` correct selon le support |
| `stair_run {from, to, facing, block}` | un escalier réellement praticable, **et la trémie percée au-dessus** |
| `ladder {from, to, on}` | échelle adossée, `facing` correct |
| `pillars {box, block, spacing}` | du relief pour **une** instruction |
| `trim {box, block, every}` | bandeaux, corniches, soubassements |
| `gable` **corrigé** | pignons remplis, contremarches fermées, option `closed` |

Chaque primitive reste **déterministe** et expansée par le moteur : le modèle n'écrit jamais un
block state à propriétés. Cela attaque directement la cause A **et** la cause F — le relief et les
équipements deviennent bon marché en instructions.

### Lot C — Adressage sémantique des façades

Permettre `on: FRONT|BACK|LEFT|RIGHT` (résolu depuis `front` par le moteur) au lieu de coordonnées
brutes, et clarifier l'ancre (`anchor: center|door`). Supprime la classe d'erreur qui a scellé le
bâtiment. Petit lot, qui se greffe naturellement sur le B.

### Lot D — Validation stricte des block states

Liste blanche **par type de bloc** des propriétés admises, au moins pour ce que le DSL émet
(escaliers, portes, panneaux de verre, lanternes, dalles, troncs). Refuse `face=bottom` sur un
escalier. Indépendant, petit, et il ferme la cause E.

### Lot E — Prévisualisation dans PlugAdmin

Contrainte à connaître : le panneau n'embarque **aucune bibliothèque YAML** et sa CSP interdit le JS
en ligne comme les scripts externes. Trois options étudiées :

1. **Coupes 2D (recommandé)** — plan par niveau + élévations des quatre façades, rendus en SVG
   *côté serveur*. Aucune dépendance, CSP respectée, et c'est la vue qui **révèle exactement** les
   défauts de la V1 : une élévation ouest aurait montré un mur plein, un plan de l'étage aurait
   montré un plancher sans trémie.
2. **Isométrie SVG** — plus parlant, plus de travail, toujours sans dépendance.
3. **3D WebGL** — exige d'élargir la CSP pour un script externe. À éviter pour l'instant.

Dans les trois cas, l'expansion doit rester **autoritaire côté plugin** : une action
`building.plan.preview` qui renvoie des coupes compactes, plutôt qu'un second expanseur dans le
panneau — dupliquer le moteur garantirait seulement que les deux divergent.

### Lot F — Prompt *(en dernier, et c'est volontaire)*

Une fois le vocabulaire stable : exemple complet d'un **bon** plan utilisant les nouvelles
primitives, adressage de façade explicite, et le contrat du lot A énoncé comme une liste de
vérification. Le prompt ne peut pas compenser un langage qui ne sait pas dire « porte ».

### Ordre et dépendances

```
A (audit)  →  B (primitives)  →  C (façades)  →  F (prompt)
D (block states)  ─ indépendant, petit
E (prévisualisation)  ─ indépendant, parallélisable
```

A d'abord parce qu'il rend tout le reste mesurable. F en dernier parce qu'il dépend de B et C.

### Essais

Chaque lot se valide sur `buildsite_0012`, l'emplacement d'essai. La V1 et son instantané de terrain
sont **conservés** ; une V2 utilisera un identifiant distinct plutôt que d'écraser la V1.

## Reset / retour à l'état initial

Rien à défaire : aucune modification n'a été faite.

## Déploiement VeryGames

### À transférer

Rien. Lot de diagnostic.

### Ne PAS transférer/altérer

La V1 posée sur `buildsite_0012`, son `.schem`, sa définition, et les quatre emplacements du Hub.

### Redémarrage requis

Non.

### Migration automatique

Aucune.

## Rollback

Sans objet.

## Logs / diagnostic

* Plan V1 extrait : `scratchpad/plan-v1.yml` (1 700 caractères, 21 instructions).
* Les deux actions `building.generate` sont dans la base du panel : le premier plan refusé
  (`PLAN_INVALID`, `GABLE` hors volume) et le second accepté.

## Documentation mise à jour

Aucune pour l'instant : la Bible décrit le vocabulaire actuel, qui va changer. Elle sera mise à jour
avec le lot B, quand les primitives seront arrêtées — documenter maintenant un langage qu'on va
remplacer créerait une référence fausse.

## Limitations / travail restant

1. **Ce que WorldEdit a fait de l'état invalide n'est pas vérifié.** Les 2 210 blocs ont été écrits,
   donc il ne s'est pas arrêté ; savoir s'il a ignoré `face=bottom` ou pris un défaut demande
   d'inspecter le `.schem`. À traiter dans le lot D.
2. **Les seuils du lot A restent à fixer** (combien de fenêtres, quelle distance maximale à une
   source de lumière, quel pourcentage maximal pour un matériau). Ce sont des choix de goût qu'il
   vaut mieux arrêter avec le propriétaire que décider seul.
3. **Le critère 7 — « aménagement correspondant au brief » — n'est pas entièrement automatisable.**
   On peut vérifier la présence de blocs cohérents (étagères pour une bibliothèque), pas qu'un
   bâtiment soit agréable. Je le propose en avertissement, jamais en refus.
4. **Aucune estimation de la qualité réelle d'une V2.** Les lots rendent les sept critères
   vérifiables et le vocabulaire suffisant ; ils ne garantissent pas qu'un modèle produira un beau
   bâtiment du premier coup. La boucle de correction existe pour cela.

## Prochaine étape suggérée

1. **Valider le découpage** et arbitrer les seuils du lot A (fenêtres, lumière, part maximale d'un
   matériau). Je peux proposer des valeurs de départ défendables si vous préférez.
2. **Créer les sous-tickets** de #94 : un par lot, A à F. Je ne les ai pas créés — vous avez demandé
   une proposition de découpage, pas son enregistrement.
3. Commencer par le **lot A**, avec le plan V1 comme cas de test : il doit échouer sur l'entrée, les
   fenêtres, l'éclairage, les pignons et l'accès à l'étage — cinq refus précis, chacun vérifiable.
