# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-09
* Heure : 00:11 (CEST)
* Sujet : #213 — bibliothèque de bâtiments, hutte de test générée par le code, et premier placement contrôlé avec retour arrière
* Statut : DONE
* Branche Git : `fix/227-building-sites-actions` (poussée, **jamais fusionnée**)
* Commit actuel si disponible : `db518fe` (+ un commit de documentation de clôture)
* Début de la tâche : 2026-10-08 22:19:56
* Fin de la tâche : 2026-10-09 00:11:17
* Durée totale : 01:51:21

---

## Demande

Poursuivre #213 avec le lot suivant : **associer un schematic simple à un `BuildingSite` et faire un
premier placement contrôlé**. Consigne explicite : **« Je ne veux importer aucun fichier externe
pour ce premier test. Crée toi-même un schematic de test extrêmement simple et déterministe »** —
une hutte 7 × 5 × ~6, palette minimale, intérieur vide, aucune décoration. Le but n'est pas qu'elle
soit belle, mais de pouvoir vérifier **orientation, dimensions, ancre, rotation, hauteur,
placement**.

Étapes demandées : générer le `.schem` (en auditant d'abord WorldEdit réellement disponible, sans
NMS, avec le writer officiel s'il existe), introduire un `BuildingDefinition` minimal, l'afficher
dans le Control Panel, pouvoir l'associer à un emplacement, afficher **emprise et rotation prévue**,
poser dans le monde, et **préparer un retour arrière sans développer un moteur de démolition**.

Contraintes notables : ancre = **centre de la porte au niveau du sol**, offsets jamais implicites ;
**aucune commande WorldEdit libre depuis le navigateur** ; permissions dédiées ; refus clair si le
collage échoue, sans faux placement ; **« Ne choisis pas une suppression destructive naïve »** et
**« ne prétends pas que supprimer est sûr »** si un retour arrière sûr n'est pas livrable.

**Contrainte ajoutée en cours de lot par le propriétaire**, et qui a structuré tout le reste :

> « WorldEdit est une dépendance d'infrastructure remplaçable, jamais une dépendance du domaine
> métier. Toute interaction avec WorldEdit doit passer par un adaptateur/interface RPGQuest dédié.
> Les modèles `BuildingSite`, `BuildingDefinition` et `BuildingPlacement` ne doivent exposer aucun
> type WorldEdit. »

Hors périmètre, explicitement : génération IA de bâtiment, analyse d'image. **Non commencées.**

---

## Analyse

### L'audit a tranché la question centrale, et il l'a fait avant tout code

**WorldEdit est installé sur le DEV** : `7.4.1`, obtenu par `/version WorldEdit` en RCON, puis
confirmé après déploiement par la présence de `worldedit-bukkit-7.4.1.jar` dans le dossier des
plugins. La dépendance `compileOnly` est alignée sur cette version exacte.

**La sonde décisive.** Le prompt demandait de préférer « construire une Clipboard en mémoire puis
l'exporter ». La question était : peut-on le faire **sur la machine de build**, ce qui permettrait
de versionner le `.schem` et de le vérifier en CI ? J'ai écrit un programme jetable contre
`worldedit-core` 7.4.1 hors serveur. Résultat sans ambiguïté :

```
Exception in thread "main" java.lang.ExceptionInInitializerError
Caused by: java.lang.IllegalStateException: WorldEdit is not initialized yet.
    at com.sk89q.worldedit.registry.Registry.get(Registry.java:107)
    at com.sk89q.worldedit.world.block.BlockTypes.get(BlockTypes.java:1210)
```

Le registre de blocs est peuplé par la **plateforme**, donc il n'existe pas hors d'un serveur. Un
`.schem` ne peut être **ni produit ni vérifié** sur la machine de build. D'où la décision : la
génération a lieu **au runtime, dans le plugin**, avec l'écrivain officiel du moteur — ce qui est
exactement la première préférence du prompt, et non un contournement.

### Les signatures de l'API ont été lues, pas supposées

Avant d'écrire l'adaptateur, j'ai désassemblé le JAR `worldedit-core` 7.4.1 au `javap` pour vérifier
chaque appel : `BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC`, `ClipboardFormat#getReader/getWriter`,
`ClipboardHolder#setTransform`, `PasteBuilder#to/ignoreAirBlocks/copyEntities/copyBiomes`,
`AffineTransform#rotateY/apply`, `BlockArrayClipboard#setOrigin/setBlock/getDimensions`,
`ForwardExtentCopy`, `Operations#complete`, `ParserContext#setRestricted/setTryLegacy`. Tout est
public : **aucun NMS, aucune réflexion CraftBukkit**.

`AffineTransform#apply(Vector3)` s'est révélé être la pièce la plus utile — elle permet de
**mesurer** ce que le moteur va faire au lieu de le supposer (voir plus bas).

### Pourquoi la définition est un fichier et le placement une table

Le prompt proposait cette séparation ; elle s'intègre effectivement bien, et pour une raison de
nature :

| Concept | Nature | Où il vit |
|---|---|---|
| `BuildingSite` | un point d'ancrage nommé | base (`building_sites`, V28) |
| `BuildingDefinition` | **ce qu'on peut poser** | fichier `plugins/RPGQuest/buildings/*.yml` |
| `BuildingPlacement` | **un fait** | base (`building_placements`, V29) |

Une définition est du *contenu* : elle se relit, se compare entre deux versions, se corrige dans un
éditeur, se versionne — exactement comme une quête ou un dialogue. Un placement est un *fait* :
« le 8 octobre à 22 h, la hutte a été posée ici, tournée de 90°, et voici la sauvegarde de ce qui s'y
trouvait avant ». Rien d'autre ne peut le reconstituer.

---

## Travail effectué

### La frontière, qui est la décision d'architecture du lot

Tout passe par **`SchematicGateway`**, une interface RPGQuest dont la surface ne parle que de plans,
de noms de fichiers, d'emprises et de degrés. `BuildingSite`, `BuildingDefinition`,
`BuildingPlacement`, `BuildingFootprint` et `BuildingRotation` **n'exposent aucun type WorldEdit** ;
ils ne savent pas qu'il existe.

Une seule classe le connaît : `building.worldedit.WorldEditSchematicGateway`. `compileOnly`,
`softdepend`, `LinkageError` intercepté, refus **nommé** si le plugin est absent, désactivé ou
incompatible — la conception du pont Citizens, déjà éprouvée dans ce dépôt.

**L'effet n'est pas théorique, il est mesurable** : la rotation, l'emprise, l'ordre des opérations,
tous les refus, le retour arrière et la survie à un redémarrage sont **exécutés par les tests**, avec
un faux moteur, sans WorldEdit sur le chemin de classe. Sans cette frontière, l'essentiel du lot
n'aurait été vérifiable qu'à la main, en jeu — c'est-à-dire presque jamais.

### La hutte est produite par notre code

`TestHutBlueprint` décrit la hutte **en Java** : 7 × 5 × 6, fondation pleine en pierre, quatre
poteaux d'angle en rondins, murs en planches, porte à deux battants centrée sur la façade, deux
fenêtres de part et d'autre, toit à deux pans avec arête, **intérieur vide**, rien à l'extérieur.
`SchematicWorkshop` l'écrit avec l'écrivain officiel (Sponge v3).

**Pourquoi un plan en code plutôt qu'un binaire dans Git.** Un blob gzip de 500 octets ne se relit
pas en revue : personne ne pourrait affirmer qu'il mesure 7 × 5 × 6 ni que sa porte est centrée. Le
plan, lui, s'inspecte — et `TestHutBlueprintTest` l'inspecte réellement, bloc par bloc (fondation
complète, quatre poteaux, porte aux bonnes coordonnées, intérieur effectivement vide, palette dans
les matériaux autorisés, rien hors du périmètre).

**La façade est volontairement dissymétrique.** Une hutte à symétrie parfaite ne dirait rien d'une
rotation de 180° : on ne pourrait pas la distinguer d'une rotation nulle, et le test manuel ne
conclurait rien.

### L'ancre, et un écart assumé par rapport à l'exemple du prompt

L'ancre est le **centre de la porte au niveau du sol** : `(3, 1, 0)`, conforme à l'exemple.

En revanche, `front` est **`NORTH` et non `SOUTH`**. La porte est sur la paroi `z = 0`, qui regarde
les `-Z`, c'est-à-dire le nord. Déclarer `SOUTH` sur cette géométrie aurait introduit un
**demi-tour permanent** caché dans le code de collage — exactement l'« offset implicite dispersé
dans le code » que le prompt interdisait. La déclaration est donc **géométriquement vraie**, et un
test le verrouille.

Le `y = 1` de l'ancre a une conséquence à connaître : la **fondation se place un bloc sous** l'ancre
de l'emplacement. C'est voulu — une fondation s'enfonce dans le sol, et l'ancre d'un emplacement est
précisément la case libre au-dessus du bloc cliqué. C'est documenté en trois endroits parce que cela
ressemble à un bug.

### La rotation : pure, puis mesurée

`BuildingRotation` est une fonction **pure** (azimut de boussole, nord = 0, horaire).
`BuildingFootprint` exprime les coins du schematic en **décalages relatifs à l'ancre**, les fait
tourner, puis les replace autour de l'ancre du monde — l'ancre est donc un point fixe, ce qui est ce
qu'on attend d'une ancre. À **90° et 270°, largeur et profondeur s'échangent** (7 × 5 devient
5 × 7) ; l'aperçu l'affiche et l'explique.

**Le sens de rotation du moteur n'est pas deviné.** La convention de signe de
`AffineTransform#rotateY` est une décision interne à WorldEdit ; la supposer serait un pari, et un
pari perdu pose la hutte à l'envers **sur du terrain déjà écrasé**. L'adaptateur applique donc la
transformation aux coins du schematic, **compare l'emprise obtenue à celle calculée par le
domaine**, et ne colle que si les deux coïncident. Si le signe opposé est celui qui correspond, il
est utilisé et journalisé une fois ; si aucun ne correspond, le collage est **refusé**. L'emprise
annoncée à l'administrateur est donc toujours celle qui sera réellement occupée.

### L'ordre des opérations est la garantie principale

1. **Revérifier** toutes les règles — *un aperçu n'est pas une réservation* : entre l'écran et le
   clic, un autre administrateur a pu occuper l'emplacement ou décharger le monde.
2. **Sauvegarder** la zone de l'emprise dans un `.schem` daté. Si cela échoue, **on ne colle pas**.
3. **Coller**, avec l'air — c'est lui qui creuse l'intérieur ; l'ignorer laisserait le terrain dans
   les murs.
4. **Enregistrer** le placement, puis marquer l'emplacement `OCCUPIED`.

Un échec de collage laisse l'emplacement **vide** et n'inscrit aucun placement : **aucun faux
placement n'est possible**. Si l'enregistrement échoue *après* un collage réussi, le message le dit
et nomme le fichier de sauvegarde — mieux vaut un bâtiment posé sans fiche, qu'un administrateur peut
constater, qu'une fiche sans bâtiment.

`site_id` est la **clé primaire** de `building_placements` : « un emplacement porte au plus un
bâtiment » est donc appliqué par le **schéma**, pas seulement par le service.

### Le retour arrière restaure, il ne détruit pas

L'option préférée du prompt a été retenue : la zone est **capturée avant** le collage et reposée
**telle quelle**, sans transformation, à l'endroit exact d'où elle vient.

**Sans sauvegarde, le retour arrière est refusé** — et le bouton **n'apparaît même pas**. Remettre de
l'air dans l'emprise détruirait le terrain d'origine : ce serait une destruction déguisée en
annulation, ce que le prompt interdisait. Je ne prétends donc pas que « supprimer » est sûr dans ce
cas : je refuse, et je dis pourquoi.

**La limite réelle est écrite, pas contournée** : la restauration repose un instantané, donc tout ce
qui a été construit dans l'emprise *après* la pose est également écrasé. C'est dit dans la zone de
danger du panel et dans la fiche d'aide.

### `SiteStatus.OCCUPIED`, arrivé sans migration

Le socle de #213 ne déclarait qu'`EMPTY`, exprès : aucun geste ne pouvait produire autre chose. Le
geste existe maintenant, et l'ajout n'a demandé **aucune migration** — colonne `TEXT`, lecture
tolérante. C'est le bénéfice exact que cette décision visait, constaté un lot plus tard.

L'état **suit** le fait : il n'existe volontairement **aucune action agent** pour l'éditer, ce qui
permettrait de déclarer « occupé » un emplacement vide.

---

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `building/model/BuildingRotation.java` | azimut et rotation — fonction pure |
| `building/model/BuildingFootprint.java` | emprise, chevauchement, volume — pure |
| `building/model/BuildingDefinition.java` | ce qu'on peut poser, avec sa validation |
| `building/model/BuildingPlacement.java` | un bâtiment réellement posé |
| `building/model/Blueprint.java` / `BlueprintBlock.java` | un plan, en coordonnées locales |
| `building/SchematicGateway.java` | **la frontière** — aucun type WorldEdit |
| `building/worldedit/WorldEditSchematicGateway.java` | **seule** classe qui connaît WorldEdit |
| `building/WorldProbe.java` / `BukkitWorldProbe.java` | les trois questions que seul Bukkit sait |
| `building/TestHutBlueprint.java` | la hutte, en Java |
| `building/SchematicWorkshop.java` | écrit le `.schem` |
| `building/BuildingLibrary.java` / `BuildingDefinitionYaml.java` | la bibliothèque, relue de fichiers |
| `building/BuildingPlacementService.java` | aperçu, pose, retour arrière |
| `database/BuildingPlacementRepository.java` | la table, en JDBC pur |
| `src/main/resources/buildings/test_hut_01.yml` | la définition embarquée |
| `panel/building/BuildingDefinitionView.java`, `BuildingLibraryDirectory.java`, `BuildingPlacementView.java`, `BuildingPlacementPreview.java` | projections côté panel |
| `control-panel/.../docs/batiments-bibliotheque.md` | fiche du centre d'aide |
| 6 fichiers de tests (voir « Tests automatiques ») | |

## Fichiers modifiés

`SchemaMigrator` (V29), `building/model/SiteStatus` (+ `OCCUPIED`), `BuildingSite` (+ `withStatus`),
`BuildingSiteService` (+ `markStatus`), `BuildingSiteRepository` (+ `updateStatus`),
`RPGQuestBootstrap` (câblage), `RpgAdminCommand` (`/rpgadmin building list|reload|generate`),
`plugin.yml` (softdepend WorldEdit), `build.gradle.kts` (dépôt EngineHub + WorldEdit `compileOnly`),
la couche agent (`AgentActionType`, `AgentActions`, `BukkitAgentActions`, `AgentActionExecutor`).
Panel : `AgentActionCatalog`, `Permission`, `Role`, `Layout`, `PanelApp`, `AgentPages`,
`BuildingSiteDirectory`, `docs/_index.txt`. Docs : Bible, `current_state.md`, `SERVER_CHANGELOG.md`,
`MANUAL_TEST_PLAN.md`, `.ai/ROADMAP.md`.

---

## Base de données / migrations

**Migration V29**, purement additive : une table `building_placements` (16 colonnes), un index
`idx_building_placements_world`. Aucune colonne ajoutée ailleurs, **aucune donnée existante lue ni
réécrite**.

`site_id` est la **clé primaire** — c'est une règle métier appliquée par le schéma. Vérifié sur la
base réelle après déploiement : `sqlite_autoindex_building_placements_1` est présent, ce qui le
prouve.

`SiteStatus.OCCUPIED` n'a demandé **aucune migration**.

## Configuration / données

Aucun changement de configuration. `plugins/RPGQuest/buildings/test_hut_01.yml` et
`plugins/RPGQuest/schematics/test_hut_01.schem` sont créés au premier démarrage, et **jamais écrasés
s'ils existent**. Aucune quête, dialogue, story ou PNJ n'est touché.

---

## Tests automatiques

`./gradlew clean build` depuis un **worktree propre** sur `db518fe`, **un seul Gradle à la fois** :

```
BUILD SUCCESSFUL in 37m 12s
```

| Module | Tests | Échecs | Ignorés |
|---|---|---|---|
| plugin | **2090** (+103) | 0 | 37 |
| control-panel | **1069** (+24) | 0 | 1 |
| web-api | **30** | 0 | 0 |
| **Total** | **3189** | **0** | **38** |

**+127 tests pour ce lot** :

| Classe | Tests | Ce qu'elle verrouille |
|---|---|---|
| `BuildingRotationTest` | 11 | les quatre orientations depuis `SOUTH` **et** depuis `NORTH`, « nord → est » à 90°, quatre quarts de tour qui reviennent au départ, angles aberrants normalisés |
| `BuildingFootprintTest` | 12 | ancre point fixe aux quatre rotations, **fondation un bloc sous l'ancre**, échange largeur/profondeur à 90°/270°, volume stable, chevauchement, **deux mondes ne se chevauchent jamais** |
| `BuildingDefinitionTest` | 13 | dimensions nulles/absurdes, **ancre hors du bâtiment**, orientation absente, traversée de chemin dans le nom de fichier |
| `TestHutBlueprintTest` | 15 | le plan bloc par bloc : dimensions, déterminisme, porte centrée, ancre sur la porte, fenêtres, **intérieur vide**, toit, palette, rien hors périmètre, **façade ≠ mur arrière**, définition cohérente avec le plan |
| `BuildingLibraryTest` | 15 | **le fichier embarqué décrit exactement le plan**, fichier invalide nommé et ignoré, doublon d'identifiant, exemple **jamais écrasé** |
| `BuildingPlacementServiceTest` | 37 | **l'aperçu n'écrit rien**, **sauvegarde avant collage prouvée par l'ordre des appels**, échec de collage → emplacement vide, échec de sauvegarde → aucun collage tenté, double clic, site occupé, incohérence signalée, chevauchement, limites du monde, avertissement sans refus, les **quatre refus** du retour arrière, survie à un service neuf |
| `BuildingPlacementPageTest` | 24 | formulaires **réellement rendus** et soumis tels quels, bouton de pose **absent sans aperçu**, aperçu d'un autre emplacement **non affiché**, rollback **absent sans sauvegarde**, bibliothèque en lecture seule |

**Aucun test existant assoupli.** Une seule attente a été corrigée : `BuildingSiteServiceTest`
utilisait `"OCCUPIED"` comme exemple de valeur *inconnue* ; cette valeur est devenue réelle, donc
l'exemple a changé — l'intention du test (une valeur inconnue est lue comme `EMPTY` et signalée
comme inconnue) est conservée, et deux assertions ont été ajoutées sur les deux valeurs réellement
connues.

## Tests manuels à effectuer

**TC-270** *(nouveau, ~12 min, `PENDING MANUAL VALIDATION`)* — la chaîne complète, **sur deux
orientations (EAST puis NORTH)**, parce qu'une seule ne dirait rien d'une erreur de signe. Inclut :
l'aperçu qui ne doit **rien** écrire, l'emprise **5 × 7 × 6** à 90°, le seuil de la porte exactement
à l'ancre, la fondation un bloc plus bas, l'intérieur vide, la survie à un redémarrage, et la
restauration comparée à une **capture d'écran prise avant la pose**.

Ce qui n'est pas vérifiable depuis la machine de build : le **collage réel** et le **sens de rotation
effectif du moteur**. En cas de convention inattendue, l'adaptateur **refuse** au lieu de poser de
travers — un refus serait donc une information, pas une catastrophe.

## Résultat attendu

Bibliothèque : la hutte, `7 × 5 × 6`, ancre `3 / 1 / 0`, façade nord, schematic présent.
Sur un emplacement vide : « Choisir un bâtiment » → aperçu avec rotation et emprise → « Placer dans
le monde » après confirmation. La hutte apparaît, porte face à l'orientation de l'emplacement, seuil
sur l'ancre. « Annuler la pose » rend le terrain tel qu'avant.

## Reset / retour à l'état initial

Annuler la pose depuis la fiche, puis supprimer les emplacements de test. Les fichiers de sauvegarde
restent dans `plugins/RPGQuest/schematics/` (`backup_<site>_<horodatage>.schem`) et peuvent être
supprimés à la main une fois les placements annulés.

---

## Déploiement VeryGames

**Effectué sur le DEV le 2026-10-09 de 00:06 à 00:12 (CEST)**, depuis un worktree propre sur
`db518fe`, après vérification que la branche est un **superset** de toutes les lignes déployées
récentes.

### À transférer

- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (**2 114 011 o**, SHA-256 `38053163…`, vérifié identique
  en ligne).
- Le **Control Panel**, déploiement **séparé** : `scripts/plugadmin/deploy.sh`.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes, les autres
plugins. **Ne pas supprimer** `plugins/RPGQuest/schematics/` : il contient les sauvegardes qui
rendent les retours arrière possibles.

### Redémarrage requis

**Oui, et un seul.** La migration V29, le chargement de la bibliothèque et la génération du schematic
ont lieu au démarrage du plugin. Effectué avec **0 joueur connecté**, `save-all` exécuté par le
script.

### Migration automatique

**Oui, V28 → V29**, et **vérifiée sur la base réelle** : `user_version = 29`, `integrity_check = ok`,
**36 tables** contre 35 avant, les 16 colonnes, l'index, et `sqlite_autoindex_building_placements_1`.

#### Ce qui a été vérifié, et une alerte de script à connaître

| Vérification | Résultat |
|---|---|
| `data.db` sauvegardé **avant**, puis **relu** | `data-20261008T220617Z-predeploy.db` — V28, integrity ok, 35 tables, `building_placements` **absente** |
| WorldEdit réellement installé | `worldedit-bukkit-7.4.1.jar` — **même version que le `compileOnly`** |
| Control Panel | ⚠️ `PANEL_DEPLOY_EXIT=1`, **faux négatif connu** (le script sonde `/health` avant que la JVM ait fini de se lier au port). Vérifié ensuite : service `active`, `/health` → `ONLINE`, `/buildings/library` → **303** vers `/login` |
| Ce qui est **servi** | le JAR de `/opt/plugadmin/app` contient `panel/building/*`, la fiche d'aide, et les **quatre** actions au catalogue |
| JAR plugin | `DEPLOY_EXIT=0`, backup du précédent (2 042 328 o) **sans écraser le plus ancien** ; le nouveau est plus gros, donc dans le sens attendu |
| RCON après redémarrage | RPGQuest, WorldEdit, Citizens **en vert** ; `/rpgadmin building` enregistrée |

**La vérification la plus utile** : le `.schem` produit au démarrage a été **retéléchargé et
décompressé**. C'est un vrai schematic Sponge (`Version`, `DataVersion`), mesurant
**`Width = 7`, `Height = 6`, `Length = 5`** — exactement les dimensions déclarées — avec pour palette
précisément les sept matériaux annoncés plus `minecraft:air`. Toute la chaîne *plan Java → écrivain
officiel → fichier valide* fonctionne donc sur le serveur réel.

## Rollback

- **Plugin** : `scripts/rollback-verygames.sh --latest` restaure
  `rpgquest-20261008T220718Z-predeploy.jar`, puis redémarrer. ⚠️ **Les bâtiments déjà posés restent
  dans le monde** (ce sont des blocs) : pour les retirer, il faut d'abord **annuler la pose depuis le
  panel**, *avant* de revenir en arrière.
- **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
- **Données** : la sauvegarde est en V28. La restaurer ferait perdre les placements enregistrés
  **sans retirer les bâtiments du monde** — à ne faire que si la migration a réellement échoué.
- Un rollback du JAR laisse `building_placements` en place, simplement inutilisée. Le
  `SchemaMigrationRunner` ne redescend jamais une version.

## Logs / diagnostic

`/rpgadmin building list|reload|generate` en jeu. La bibliothèque journalise au démarrage le nombre
de bâtiments chargés et **nomme** chaque fichier refusé. L'adaptateur journalise une fois s'il
constate que le moteur applique la rotation dans le sens opposé à l'azimut, et avertit s'il refuse un
collage dont l'emprise ne correspond pas.

## Documentation mise à jour

`docs/RPGQUEST_BIBLE.md` (section complète : les trois concepts, la frontière WorldEdit, la hutte
générée avec le résultat de l'audit, la convention d'ancre, la rotation mesurée, l'ordre des
opérations, les limites du retour arrière), `docs/current_state.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md` (TC-270), `.ai/ROADMAP.md`, et la
fiche du centre d'aide `batiments-bibliotheque.md`.

---

## Limitations / travail restant

- **Le collage réel n'est pas constaté.** C'est TC-270, et c'est la seule chose qui compte vraiment
  maintenant. Le fichier est valide, le schéma est migré, le panel sert le bon code — mais qu'une
  hutte apparaisse à la bonne ancre, porte dans la bonne direction, reste à voir.
- **La restauration repose un instantané** : elle écrase aussi ce qui a été bâti dans l'emprise
  *après* la pose. Écrit dans le panel et dans la fiche d'aide.
- **Sans sauvegarde, le retour arrière est refusé** et le bouton absent. C'est volontaire, et c'est
  la limite que le prompt demandait de documenter plutôt que de contourner.
- **Aucun aperçu visuel en jeu** de l'emprise : elle est annoncée en chiffres dans le panel.
- **Aucune analyse bloc par bloc du terrain** : seulement un comptage de blocs non-air, qui
  **avertit sans jamais refuser** (le prompt l'exigeait).
- **Le `.schem` n'est pas versionné dans le dépôt** — la sonde a prouvé qu'il n'est ni produisible ni
  vérifiable hors serveur. Le dépôt versionne le **générateur** et la **définition**, et un test
  échoue si les deux divergent.
- **Le titre de la fenêtre d'enclume** de #227 reste non garanti (limite héritée, inchangée).
- Aucun versioning de bâtiment, aucun remplacement d'un bâtiment posé, **aucune génération IA,
  aucune analyse d'image** — le prompt les plaçait hors de ce lot et elles n'ont pas été commencées.
- **#213 reste ouverte** : la validation manuelle est nécessaire.

### Constat incident, utile à connaître

L'emplacement présent sur le DEV s'appelle **« Hutte »** et porte l'identifiant `buildsite_0006`. Un
nom saisi à la main prouve que **l'enclume de #227 fonctionne en jeu**, et le passage de `0005` à
`0006` confirme que les identifiants ne sont pas recyclés. Les emplacements de test précédents ont
été supprimés entre les deux déploiements — ce qui confirme une fois de plus que la suppression
depuis le panel s'applique bien.

## Prochaine étape suggérée

**TC-270** (~12 min, un vrai client Minecraft, deux orientations). Si la hutte se pose correctement
aux deux orientations, la chaîne `BuildingSite → BuildingDefinition → schematic → aperçu → rotation
→ collage` est validée de bout en bout, et le chantier peut passer à la **bibliothèque de schematics
importables**, puis seulement ensuite à la génération assistée.

Si une porte regarde la mauvaise direction, le correctif est **localisé dans une seule classe**
(`WorldEditSchematicGateway`) — c'est précisément ce que la frontière achetait.
