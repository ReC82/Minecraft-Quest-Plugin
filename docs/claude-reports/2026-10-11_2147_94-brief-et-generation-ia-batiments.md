# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-11
* Heure : 21:47
* Sujet : Issues #94 / #213 — brief architectural des emplacements, et première génération de bâtiments par IA depuis PlugAdmin
* Statut : DONE pour le code, les tests ciblés et la documentation — `PENDING MANUAL VALIDATION` pour la génération elle-même (aucun appel à un vrai fournisseur d'IA, aucun bâtiment regardé en jeu) ; **aucun push, aucune PR, aucun merge, aucun déploiement**
* Branche Git : `feature/94-brief-et-generation-ia`, créée depuis la ligne d'intégration `feature/238-guard-dialogue-ux` @ `e969f5d`
* Commit actuel si disponible : `339e7c2` (4 commits)
* Début de la tâche : 2026-10-11 20:45:18
* Fin de la tâche : 2026-10-11 21:47:10
* Durée totale : 01:01:52

## Demande

Deux chantiers dans un même lot, explicitement séparés de #197 :

1. **Priorité UX immédiate** — corriger le champ Description des emplacements, qui avait tronqué un
   brief architectural silencieusement sur `buildsite_0010`, la fiche indiquant ensuite
   « Description : aucune ». Attendu : accepter un brief réel, afficher la limite et un compteur,
   message d'erreur clair, **jamais de troncature silencieuse**, donnée persistée proprement, ne pas
   casser les emplacements existants, prévoir une migration de lecture.
2. **MVP de génération IA** — depuis la fiche d'un emplacement : lire son nom, sa position et son
   orientation, saisir un brief, lancer « Générer avec IA », obtenir une proposition exploitable
   enregistrée dans une forme cohérente avec l'architecture (bâtiment réutilisable, définition
   intermédiaire ou `.schem`), avec un **statut clair** (demandée / en cours / réussie / échouée).
   « Je veux éviter une fausse UX qui ferait semblant de générer sans livrer un vrai artefact. »

Bâtiment pilote : **Bibliothèque du Hub** (`buildsite_0009`). Contraintes : ne pas casser le cycle
de vie validé des emplacements, ne pas toucher aux données PNJ, ne pas reprendre #197, aucun faux
succès, préserver les permissions, ne publier aucun contenu quêtes/dialogues/stories.

## Analyse

### La prémisse, corrigée par la mesure

Avant d'écrire une ligne, j'ai lu la base réelle du panel (`control-panel.db`, copie en lecture
seule). Les faits contredisent en partie le constat du ticket :

| Emplacement | Enregistré à | Longueur | État du texte |
|---|---|---|---|
| `buildsite_0009` Bibliothèque du Hub | 18:16 | 488 | complet, finit sur une phrase |
| `buildsite_0010` Maison des aventuriers | 18:25 | 385 | complet |
| `buildsite_0011` Caserne des Gardiens | 18:33 | 490 | complet |
| `buildsite_0006` Maison délabrée de Lily | 18:39 | 401 | complet |

Les quatre actions `building.site.describe` sont en **SUCCESS**, et aucun texte stocké n'est coupé
en milieu de mot. **Rien n'a été perdu en base.** Le « Description : aucune » du PDF était un
instantané pris avant l'enregistrement — le panel ré-enfile automatiquement le relevé après une
mutation (`enqueueCatalogRefreshes`) et `panel.js` recharge la page quand la file est au repos.

Ce qui a bel et bien eu lieu, c'est la coupure **à la saisie**. J'ai trouvé sa cause exacte :
`maxlength="500"` sur la zone de texte. Le navigateur applique cet attribut en coupant le collage au
caractère 500, **sans message, sans compteur, sans aucun signal** — il n'en produit aucun. Le brief
collé s'arrêtait donc au milieu de « L'entrée doit être facilement accessib », exactement comme
décrit, et l'administrateur l'a raccourci.

### Quatre seuils, dont deux coupaient en silence

| Où | Seuil | Comportement avant | Après |
|---|---|---|---|
| navigateur (`maxlength`) | 500 | **coupe en silence** | supprimé |
| validation panel | 500 | refus lisible | conservée pour la note |
| validation exécuteur | 500 | refus lisible | conservée pour la note |
| `BuildingSiteService.trimTo` | 500 | **coupe en silence** | refus bruyant |

Le dernier n'avait jamais perdu de données — les deux frontières refusent avant lui — mais c'était
une perte **en attente d'un appelant distrait**. Une exception y est le bon comportement : aucun
utilisateur ne peut la déclencher, et elle réveille le développeur qui a oublié sa validation.

**Une exception découverte en cours de route** : un test existant,
`aTooLongNameIsTruncatedRatherThanRefused`, documente que le **nom** est tronqué *volontairement*.
J'avais étendu le refus à `rename` ; le test l'a attrapé. J'ai reverti : c'est une décision du
projet, et le chemin de saisie en jeu passe par une enclume, où couper un libellé trop long vaut
mieux que refuser la création d'un emplacement déjà cliqué. La différence tient debout : personne ne
rédige un nom de 64 caractères sans s'en apercevoir, un brief coupé au caractère 500 passe inaperçu.

### Le pipeline de #94 existait déjà presque entièrement

C'est la découverte qui a dimensionné tout le reste. En lisant le code avant de concevoir :

* `Blueprint` (taille + liste de `BlueprintBlock(x, y, z, state)`) — plan de blocs déclaratif ;
* `SchematicGateway.write(blueprint, fileName)` — écrit le `.schem` via WorldEdit ;
* `BuildingDefinition` + `BuildingDefinitionYaml` — la définition qui désigne le `.schem` ;
* `BuildingLibrary` — la bibliothèque rechargeable depuis `buildings/*.yml` ;
* le flux de pose de #213/#234/#239 au bout, avec aperçu d'emprise, sauvegarde et restauration.

Il ne manquait **que le maillon d'entrée** : les plans étaient écrits **en Java**
(`TestHutBlueprint`, `TestWatchtowerBlueprint`). Le MVP de #94 n'est donc pas un nouveau pipeline,
c'est l'ouverture de ce maillon aux données.

### Décisions de conception

**1. L'IA écrit des primitives, pas des blocs.** Un bâtiment de 19 × 12 × 15 compte 3 420 cellules.
Les énumérer serait lent, coûteux, et surtout fragile : une coordonnée de travers dans un mur de
deux cents blocs produit un trou que personne ne relit. C'est littéralement ce que #94 demande
d'éviter. Cinq instructions au vocabulaire fermé : `box`, `shell`, `walls`, `clear`, `gable`.

**2. YAML, pas JSON.** C'est déjà la langue du projet (quêtes, dialogues, stories, définitions de
bâtiment), l'atelier IA #146 demande déjà du YAML à ses modèles, et son `AiYamlExtractor` est
éprouvé. Choisir JSON imposerait un analyseur de plus au plugin pour décrire la même chose.

**3. Les instructions se recouvrent, dans l'ordre.** On pose un volume, on y creuse les pièces, on
perce les fenêtres. Sans recouvrement il faudrait décrire chaque mur en quatre morceaux pour laisser
place à une porte — exactement le découpage qu'un modèle rate. Effet secondaire utile : une position
étant une clé de carte, le `Blueprint` produit ne peut pas contenir deux blocs au même endroit.

**4. Déterminisme non négociable.** L'empreinte du `.schem` sert de **version** au bâtiment posé
(#234). Une expansion variable ferait croire à un bâtiment périmé à chaque régénération. Les
cellules sont donc réémises dans un ordre **fixe** (y, z, x) : l'ordre des instructions décide du
contenu d'une cellule, jamais du rang d'un bloc. Deux tests le verrouillent, dont un qui permute les
instructions.

**5. Le panel ne valide pas le plan.** Il n'embarque **aucune bibliothèque YAML** — isolation assumée
du module, « codec maison » pour JSON — et le plugin est le validateur autoritaire : lui seul connaît
les bornes du moteur, le vocabulaire et la bibliothèque existante. Dupliquer ces règles garantirait
qu'elles divergent, et afficherait « plan valide » sur un plan refusé ensuite. Le refus du serveur
est **précis** (rang de l'instruction) et un bouton de reprise le renvoie au modèle.

**6. Deux champs plutôt qu'un.** Une note de liste ne peut pas s'étendre sur deux pages sans rendre
la liste illisible ; un brief ne tient pas en 500 caractères sans perdre matériaux, étages et pièces.

**7. La migration ne déplace rien.** Les quatre emplacements portent leur brief dans leur
description, faute d'un autre champ. Recopier aurait l'air serviable, mais déciderait à la place de
l'administrateur et se tromperait pour les notes réellement courtes (« Emplacement test pour la
première hutte générée. »). L'écran propose la recopie, en le disant, sans rien préremplir.

## Travail effectué

### Partie A — le brief (commit `bf8dc0a`)

1. `BuildingSite` porte `aiBrief`, borne `MAX_AI_BRIEF_LENGTH = 6000` (borne de **sécurité**, pas de
   confort), et `hasAiBrief()`.
2. Migration **V31** : `ALTER TABLE building_sites ADD COLUMN ai_brief TEXT NOT NULL DEFAULT ''`,
   idempotente via `columnExists`, strictement additive, **sans recopie**.
3. `BuildingSiteRepository` : insertion, `updateAiBrief`, et lecture **défensive** du brief (une base
   d'une version antérieure renverrait `null` ; perdre tout le chargement des emplacements pour un
   brief absent serait hors de proportion).
4. `BuildingSiteService.brief(id, text)`, et `trimTo` → `requireAtMost` **pour la note et le brief
   seulement** (le nom garde sa politique, documentée en commentaire).
5. Action `building.site.brief` : type, exécuteur (refus chiffré), façade, validation panel.
6. `aiBrief` sérialisé dans `building.site.list` + porté par `BuildingSiteSummary` et
   `BuildingSiteView`.
7. UI : deux champs clairement nommés, **plus de `maxlength`**, compteur **rendu par le serveur**
   (donc juste même sans JS), brief replié dans les métadonnées avec sa taille, et signalement d'une
   note qui serait en réalité un brief.
8. `panel.js` : module `initCharCount`, isolé comme les autres par `run(...)`. `plugadmin.css` :
   `.charcount`, `.charcount-over`, `.brief-text`.

### Partie B — le pipeline déterministe (commit `f42e3ba`)

9. Nouveau paquet `com.lodygames.rpgquest.building.plan`, **sans Bukkit** sauf le lecteur YAML :
   * `PlanBox` — zone à bornes incluses, coins normalisés ;
   * `PlanStep` + `Kind` — le vocabulaire fermé ;
   * `BlockState` — contrôle de **forme** d'un état de bloc, et `with()` pour orienter les marches ;
   * `BuildingPlan` — le plan, ses bornes (64/côté, 150 000 cellules, 400 instructions) et un
     `validate()` qui **nomme l'instruction fautive par son rang** ;
   * `BuildingPlanExpander` — l'expansion déterministe, avec plafond de coût cumulé refusé **avant**
     toute allocation ;
   * `BuildingPlanYaml` — lecture tolérante sur les types numériques, **intolérante** sur le
     vocabulaire : une instruction inconnue refuse le plan, elle n'est jamais ignorée ;
   * `BuildingGenerator` — l'orchestration, et la garantie des trois artefacts.
10. Action `building.generate` : type, exécuteur (borne de 64 000 caractères + détail factuel),
    façade `buildingGenerate`, setter facultatif câblé dans le bootstrap, entrée panel.

### Partie C — le parcours dans PlugAdmin (commit `2ebef83`)

11. `BuildingPromptBuilder` — le contrat passé au modèle : les cinq instructions, la convention de
    bornes incluses, l'ordre de travail recommandé, et ce qui fait un bâtiment praticable (3 blocs
    de hauteur libre, porte de 2, escalier si plusieurs étages, fenêtres). Plus un
    `correctionPrompt` qui transmet le **motif réel** du refus.
12. `AiBuildingStudio` — même registre de fournisseurs et mêmes réglages que l'atelier #146 (une
    seule clé API à gérer), même `AiYamlExtractor`. **N'écrit rien, ne valide pas.**
13. `BuildingPlanPages` + route `/buildings/plan` + `handleBuildingPlan` : deux gestes distincts,
    deux permissions (`AI_USE` / `BUILDING_WRITE`), audit du fournisseur, du modèle et du verdict
    (jamais la clé, jamais le brief complet), trois refus à **coût nul** avant tout appel payant.
14. Point d'entrée sur la fiche de l'emplacement, visible **seulement si un brief existe**.

## Fichiers créés

Plugin :
* `src/main/java/com/lodygames/rpgquest/building/plan/PlanBox.java`
* `src/main/java/com/lodygames/rpgquest/building/plan/PlanStep.java`
* `src/main/java/com/lodygames/rpgquest/building/plan/BlockState.java`
* `src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlan.java`
* `src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlanExpander.java`
* `src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlanYaml.java`
* `src/main/java/com/lodygames/rpgquest/building/plan/BuildingGenerator.java`
* `src/test/java/com/lodygames/rpgquest/building/plan/BuildingPlanExpanderTest.java`
* `src/test/java/com/lodygames/rpgquest/building/plan/BuildingGeneratorTest.java`

Panel :
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/BuildingPromptBuilder.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/AiBuildingStudio.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/BuildingPlanPages.java`

## Fichiers modifiés

Plugin : `building/model/BuildingSite.java`, `building/BuildingSiteService.java`,
`database/BuildingSiteRepository.java`, `database/SchemaMigrator.java`,
`web/agent/AgentActions.java`, `web/agent/AgentActionType.java`,
`web/agent/AgentActionExecutor.java`, `web/agent/BukkitAgentActions.java`,
`bootstrap/RPGQuestBootstrap.java`, et les doubles de test `StubAgentActions`,
`AgentActionExecutorTest`, plus `BuildingSiteServiceTest`, `SchemaMigratorTest`.

Panel : `panel/agent/AgentActionCatalog.java`, `panel/building/BuildingSiteView.java`,
`panel/building/BuildingSiteDirectory.java`, `panel/web/AgentPages.java`, `panel/web/PanelApp.java`,
`assets/panel.js`, `assets/plugadmin.css`, et les tests `AgentActionCatalogTest`,
`BuildingSiteDirectoryTest`, `BuildingSitesPageTest`.

Documentation : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `control-panel/src/main/resources/docs/batiments-emplacements.md`.

## Base de données / migrations

**Migration V31** — `building_sites.ai_brief TEXT NOT NULL DEFAULT ''`.

* Strictement **additive** : aucune colonne existante modifiée, aucune ligne réécrite.
* **Idempotente** : `columnExists` d'abord, donc un rejeu ne plante pas sur « duplicate column ».
* **Ne déplace aucune donnée** : les descriptions existantes sont préservées telles quelles.
* Compatible MySQL par `dialect.ddl(...)`, comme toutes les migrations du projet.
* Rollback : la colonne **reste** après un retour arrière du JAR. Une colonne en trop est inerte
  pour un plugin qui l'ignore ; la retirer risquerait bien plus que de la laisser.

`CURRENT_VERSION` passe de 30 à 31.

## Configuration / données

Aucun changement de `config.yml`, aucune variable d'environnement nouvelle. **Aucune clé API d'IA
n'est requise par le déploiement** : sans fournisseur activé, la page de génération le dit et ne
propose aucun appel.

Les sept fichiers de contenu non suivis du propriétaire et `src/main/resources/quests/crystal_hunt.yml`
sont **restés intacts** et ne sont dans aucun commit. Un point de vigilance mérite d'être noté : en
rebasant la partie A de la branche #197 vers la ligne d'intégration, mon premier `git diff` **avait
capturé `crystal_hunt.yml`** ; je l'ai vu, exclu explicitement, et vérifié à zéro occurrence avant
d'appliquer le patch.

## Tests automatiques

### Exécutés, et verts

| Commande | Verdict |
|---|---|
| `:test --tests '*BuildingPlanExpanderTest*'` | BUILD SUCCESSFUL (14 tests, verts du premier coup) |
| `:test --tests '*BuildingGeneratorTest*'` | BUILD SUCCESSFUL |
| `:test --tests '*BuildingSiteServiceTest*'` | BUILD SUCCESSFUL (51 tests) |
| `:test --tests '*SchemaMigratorTest*'` | BUILD SUCCESSFUL |
| `:test --tests '*AgentActionExecutorTest*'` | BUILD SUCCESSFUL (105 tests) |
| `:test --tests '*Building*' '*Agent*' '*SchemaMigrat*' '*BlockState*' '*PlanBox*'` | BUILD SUCCESSFUL (balayage plugin) |
| `:control-panel:test --tests '*BuildingSitesPageTest*'` | BUILD SUCCESSFUL |
| `:control-panel:test --tests '*AgentActionCatalogTest*'` | BUILD SUCCESSFUL |
| `:control-panel:test --tests '*Building*' '*Agent*' '*PanelApp*' '*Ai*'` | BUILD SUCCESSFUL (balayage panel) |
| `:control-panel:test --tests '*DocsPagesTest*' '*DocLibraryTest*'` | BUILD SUCCESSFUL |
| `compileJava` + `:control-panel:compileJava` + les deux `compileTestJava` | sans erreur |

### Ce que les tests vérifient réellement

* **`BuildingPlanExpanderTest`** (14) — chaque primitive (`box` remplit bornes incluses, `shell`
  laisse l'intérieur vide, `walls` ne pose ni sol ni plafond, `clear` perce, `gable` monte en pente
  **et oriente les marches vers l'extérieur**), le recouvrement dans l'ordre, et **deux tests de
  déterminisme** dont un qui permute les instructions pour prouver que le rang d'un bloc n'en dépend
  pas. Plus les refus : plan vide, plan qui s'annule, zone hors volume (avec le rang nommé), plan
  `null`, plan trop coûteux.
* **`BuildingGeneratorTest`** (8) — le succès signifie bien **trois artefacts** ; le plan source est
  conservé dans la définition ; régénérer incrémente la version ; même plan = même nombre de blocs ;
  un identifiant pris n'est pas écrasé et le fichier existant est **inchangé** ; un plan invalide
  n'écrit **rien du tout** (quatre formes de plan fautif) ; un échec du `.schem` n'écrit **aucune
  définition** ; un état de bloc mal formé (`"minecraft:stone; /op Pirate"`) est refusé avant toute
  écriture.
* **`BuildingSiteServiceTest`** (+8) — un brief de plus de 1 000 caractères revient **à l'octet près**
  après redémarrage ; note et brief sont indépendants ; vider est valide ; un dépassement **lève**
  et **n'écrit rien** ; la borne exacte est acceptée ; Unicode, apostrophes typographiques, emoji et
  sauts de ligne survivent ; un emplacement d'avant la colonne charge avec un brief vide **et sa
  note préservée**.
* **`SchemaMigratorTest`** (+2) — la colonne existe, vaut vide, et la migration **ne déplace pas** la
  description ; un brief de plus de 5 000 caractères traverse la colonne.
* **`AgentActionExecutorTest`** (+2) — un brief de 2 000 caractères arrive **entier** à la façade ;
  au-delà, refus chiffré et **rien n'atteint la façade**.
* **`AgentActionCatalogTest`** (+2) — mêmes bornes, mêmes droits, même relevé invalidé que la note.
* **`BuildingSitesPageTest`** (+12) — **aucun `maxlength`** sur les deux champs longs, compteurs
  rendus par le serveur avec la vraie longueur initiale, deux champs nommés, brief replié, note
  signalée sans être déplacée, brief de 2 000 caractères enfilé entier et 6 001 refusé avant la
  file ; et pour la page de génération : les faits de l'emplacement affichés, l'absence de brief
  dite sans proposer d'appel payant, un emplacement inconnu en 404 lisible, et la garantie
  structurelle qu'**aucune écriture ne part de cette page**.

### Ce qui n'est PAS prouvé

1. **La suite complète n'a pas été relancée.** Le harness l'a tuée faute de mémoire pendant le lot
   précédent (#197), avec consigne de ne pas la redémarrer sans accord. Je m'y suis tenu et j'ai
   balayé par familles ciblées. `./gradlew build` n'a pas tourné non plus. **Donc : aucun push,
   aucune PR, aucun merge, aucun déploiement.**
2. **Aucun appel réel à un fournisseur d'IA.** Le prompt n'a jamais été soumis à Claude, GPT ou
   Gemini. Je ne sais donc pas empiriquement si un modèle respecte ce contrat du premier coup, ni à
   quelle fréquence le serveur refusera son plan. C'est la principale inconnue de ce lot.
3. **Aucun bâtiment regardé en jeu.** Un plan géométriquement valide n'est pas automatiquement beau
   ni praticable, et aucun test ne peut en juger.
4. **Le rendu visuel du compteur et des deux champs** n'a pas été vu dans un navigateur, ni sur
   mobile.

## Tests manuels à effectuer

### Protocole — la Bibliothèque du Hub (`buildsite_0009`)

**Prérequis** : JAR déployé (migration V31) et panel déployé.

**Étape 1 — le brief, sans troncature.**
1. **Bâtiments → Emplacements** → bouton **« Emplacements »** pour rafraîchir le relevé.
2. Ouvrir `buildsite_0009`. Vérifier **deux** champs : « Note d'administrateur » et « Brief
   architectural », chacun avec son compteur et sa limite (500 / 6 000).
3. Le bandeau signale que la note actuelle est peut-être un brief, **sans l'avoir déplacée**.
4. Coller dans le **brief** le texte de référence (479 caractères), puis l'étoffer bien au-delà de
   500. **Attendu** : rien n'est coupé, le compteur suit la frappe.
5. « Enregistrer le brief », puis **rafraîchir la page** : le brief revient entier, replié avec sa
   taille dans les métadonnées.
6. Coller plus de 6 000 caractères : **refus** indiquant combien retirer, et **rien n'est enregistré**.

**Étape 2 — demander un plan.**
7. Vérifier qu'un fournisseur d'IA est activé avec sa clé (**Fournisseurs d'IA**).
8. Sur la fiche, **« Générer un bâtiment avec l'IA »**.
9. La page montre les faits transmis : nom, monde, position `784 / 67 / -693`, façade **ouest**, et
   le brief tel qu'il sera envoyé.
10. Emprise maximale **19**, hauteur **14**, puis **« Demander un plan »**.
11. **Attendu** : un plan YAML s'affiche, avec le fournisseur, le modèle et la consommation. Aucun
    fichier n'a encore été écrit.

**Étape 3 — générer.**
12. Relire le plan. Vérifier `front: WEST`, `id: bibliotheque_du_hub` (ou proche), une taille dans
    les bornes, et des instructions qui ressemblent à un bâtiment (sol, murs, percements, toit).
13. **« Générer le bâtiment »** (case « Remplacer » décochée).
14. **Attendu** : succès annonçant l'identifiant, l'emprise, le nombre de blocs et la version 1.
15. Si **refus** : le message nomme l'instruction fautive. Soit corriger dans le champ et relancer,
    soit **« Demander un plan corrigé »**, qui renvoie le motif et le plan au modèle.

**Étape 4 — retrouver le résultat.**
16. **Bâtiments → Bibliothèque** → rafraîchir. Le bâtiment apparaît avec ses dimensions et **sans**
    la mention « schematic absent ».
17. Sur le serveur : `plugins/RPGQuest/buildings/<id>.yml` et `plugins/RPGQuest/schematics/<id>.schem`
    existent ; le `.yml` contient la clé `plan` avec le plan source.

**Étape 5 — poser, et juger (le vrai test).**
18. Depuis la fiche de `buildsite_0009`, **aperçu** du placement puis **pose**.
19. **Aller voir en jeu.** Juger ce qu'aucun test ne peut juger : l'intérieur est-il praticable, la
    porte franchissable, l'étage accessible, le toit correct, l'orientation la bonne ?
20. En cas de déception : **restaurer** depuis la fiche, affiner le brief, régénérer avec
    « Remplacer » coché.

### Cas limites à éprouver

* **TC-94-A** — collage de plus de 6 000 caractères : refus chiffré, rien d'enregistré.
* **TC-94-B** — brief avec accents, apostrophes typographiques et emoji : revient identique.
* **TC-94-C** — formulaire sur **mobile** : compteur lisible, pas de débordement horizontal.
* **TC-94-D** — identifiant déjà pris sans cocher « Remplacer » : refus, fichier existant intact.
* **TC-94-E** — plan modifié à la main avec une instruction inventée : refus nommant son rang.
* **TC-94-F** — WorldEdit absent ou en échec : la génération échoue et **aucune définition**
  n'apparaît dans la bibliothèque.
* **TC-94-G** — un utilisateur avec `AI_USE` mais sans `BUILDING_WRITE` : peut demander un plan,
  pas le générer.
* **TC-94-H** — non-régression : renommer, décrire, changer l'orientation et libérer un emplacement
  existant fonctionnent comme avant.

### Les autres emplacements

Une fois la bibliothèque validée, répéter l'étape 1 (brief) puis les étapes 2-5 pour
`buildsite_0010` Maison des aventuriers, `buildsite_0011` Caserne des Gardiens (brief plus
ambitieux : deux niveaux, hall, armurerie, dortoir, zone d'entraînement extérieure) et
`buildsite_0006` Maison délabrée de Lily.

## Résultat attendu

Un administrateur ouvre un emplacement, écrit un brief d'architecte **aussi long qu'il le faut**,
clique « Générer un bâtiment avec l'IA », relit le plan proposé, et obtient un bâtiment réellement
présent dans la bibliothèque, posable par le flux déjà validé.

Et quand ça ne marche pas, il sait pourquoi : le compteur dit qu'il dépasse, le serveur dit quelle
instruction cloche, le bouton de reprise renvoie ce motif au modèle.

## Reset / retour à l'état initial

* **Un brief** : vider le champ et enregistrer.
* **Un bâtiment généré** : supprimer `buildings/<id>.yml` et `schematics/<id>.schem`, puis
  rafraîchir la bibliothèque. Rien n'y fait référence tant qu'il n'a pas été **posé**.
* **Un bâtiment posé** : restauration depuis la fiche de l'emplacement (flux #234, inchangé).
* **Le code** : `git revert` des quatre commits, ou `git checkout e969f5d`. La colonne `ai_brief`
  reste et demeure inerte.

## Déploiement VeryGames

### À transférer

* **JAR RPGQuest** — obligatoire : migration V31, action `building.site.brief`, action
  `building.generate`, et tout le paquet `building.plan`.
* **PlugAdmin** — déploiement **séparé** (`scripts/plugadmin/deploy.sh`). Il embarque `panel.js` et
  `plugadmin.css`, tous deux modifiés : un panel périmé afficherait un compteur figé (pas faux) et
  **aucune** route `/buildings/plan`.

### Ne PAS transférer/altérer

* Les sept fichiers de contenu non suivis du propriétaire, ni `crystal_hunt.yml`.
* La colonne `description` de `building_sites` : elle porte les quatre briefs actuels.
* Les dossiers `buildings/` et `schematics/` existants — à sauvegarder, pas à remplacer.
* Aucun bloc du monde, aucune donnée PNJ.

### Redémarrage requis

Oui pour le plugin (JAR + migration). Le panel se recharge par son propre service.

### Migration automatique

Oui : **V31**, au démarrage, idempotente et strictement additive.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest`, puis redémarrer. La colonne reste.
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Contenu** : sans objet.

## Logs / diagnostic

* Génération : `[building] bâtiment généré : <id> (x × y × z, N blocs, version V)` dans la console du
  serveur ; le détail complet (identifiant, fichier, dimensions, blocs, version, remplacement) est
  dans le résultat de l'action, page **Actions** du panel.
* Appel IA : journal d'audit du panel, action `building.plan.generate` — fournisseur, modèle,
  consommation, verdict. **Jamais la clé, jamais le brief complet.**
* Bibliothèque : `[building] bibliothèque : N bâtiment(s), M erreur(s)` au rechargement.
* Migration : la trace de `SchemaMigrator` au démarrage doit mentionner
  `building_sites.ai_brief`.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — deux nouvelles sections : « Deux textes, deux métiers » (les quatre
  seuils, dont deux muets, et pourquoi le nom garde sa politique) et « Génération de bâtiments par
  IA » (pipeline, cinq primitives, déterminisme et sa raison, ce qui n'est pas exécuté, et ce que
  « généré » garantit). Tableau des actions agent complété.
* `docs/current_state.md` — entrée #94/#213, avec la mesure qui corrige la prémisse.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée du 2026-10-11 (nuit, suite), dont le point de
  rollback non évident : la colonne reste.
* `control-panel/src/main/resources/docs/batiments-emplacements.md` — « Note ou brief ? », le
  parcours de génération en deux gestes, que faire d'un refus, et **ce que l'IA fait moins bien**.

## Limitations / travail restant

1. **Aucun appel réel à une IA.** C'est la limite principale. Le contrat du prompt est écrit avec
   soin — vocabulaire fermé, convention de bornes explicitée, ordre de travail recommandé — mais je
   ne sais pas empiriquement si un modèle le respecte du premier coup, ni à quelle fréquence le
   serveur refusera. Le bouton de reprise existe précisément parce que je m'attends à des refus.
2. **Pas d'aperçu du bâtiment avant la pose**, au-delà de l'aperçu d'emprise existant de #213. Le
   plan se relit en YAML, ce qui demande un œil ; un rendu visuel serait un chantier à part.
3. **Les statuts demandés sont portés par le cycle de vie des actions**, pas par un champ dédié :
   demandée = `PENDING`, en cours = `DELIVERED`, réussie = `SUCCESS` avec les faits réels, échouée =
   `FAILED` avec le motif. C'est honnête et déjà visible dans le panel, mais ce n'est pas un suivi
   de génération persistant par emplacement — un bâtiment généré n'est pas « rattaché » à
   l'emplacement qui a servi de brief tant qu'il n'est pas posé.
4. **L'ancre par défaut est le centre de la base.** Convention documentée dans le prompt, mais un
   modèle peut déclarer une ancre absurde ; elle est seulement bornée au volume, pas jugée.
5. **`gable` tronque un toit plus haut que sa zone** plutôt que de refuser. Choix assumé (ne jamais
   sortir du volume annoncé), mais un plan mal dimensionné produira un toit plat au sommet.
6. **Troncature du nom d'emplacement** : incohérente avec la nouvelle politique du brief, conservée
   délibérément (test dédié, chemin de saisie par enclume). Mérite une décision explicite un jour.
7. **Deux bugs de #213 non traités**, hors périmètre de cette demande : l'objet temporaire
   « Nouvel emplacement » qui reste dans l'inventaire après création, et la génération d'un plan à
   partir d'une **image** de référence.
8. **Rien n'est déployé.** Voir « Ce qui n'est PAS prouvé ».

## Prochaine étape suggérée

1. **Relancer `./gradlew test` puis `build`** — c'est le seul préalable au push, au merge et au
   déploiement. Je ne l'ai pas fait de ma propre initiative : le harness a tué la suite complète
   faute de mémoire pendant le lot précédent, avec consigne de ne pas la redémarrer sans accord.
2. **Déployer le JAR** (migration V31) **et** le panel, puis dérouler l'étape 1 du protocole — elle
   valide la correction UX sans dépenser un seul jeton d'IA.
3. **Dérouler les étapes 2 à 5** sur la Bibliothèque du Hub. C'est là qu'on apprendra ce que vaut
   réellement le prompt.
4. Selon le résultat : affiner le contrat du prompt (c'est un fichier de texte, itérable sans toucher
   au moteur) plutôt que le moteur, qui est testé et déterministe.
5. Ne pas fermer #94 : c'est un EPIC, et ce lot n'en livre que le premier prototype. Ne pas fermer
   #213 non plus : l'objet temporaire reste dans l'inventaire.
