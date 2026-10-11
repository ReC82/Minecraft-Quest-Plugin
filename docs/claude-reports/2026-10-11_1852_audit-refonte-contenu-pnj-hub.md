# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-11
* Heure : 18:52
* Sujet : **audit de préparation** à la refonte du contenu, des PNJ et du Hub — inventaire réel,
  capacités de PlugAdmin, limitations, backlog et premier parcours de test
* Statut : DONE — **audit sans aucune mutation**
* Branche Git : `audit/refonte-contenu-pnj-hub` (depuis la ligne déployée)
* Commit actuel si disponible : voir « Commits »
* Début de la tâche : 2026-10-11 18:52:57
* Fin de la tâche : 2026-10-11 19:48:30
* Durée totale : 00:55:33

**Prérequis vérifié** : #239 est corrigé, déployé et vérifié sur le serveur réel. Seule la
validation visuelle depuis la page Chantiers reste à faire de ton côté.

**Aucune mutation** : aucune quête supprimée, aucun PNJ renommé ou déplacé, aucune construction
touchée, aucune progression modifiée, aucun contenu publié, aucun JAR déployé, aucun redémarrage,
aucun contournement TLS. Tous les relevés viennent d'actions agent **en lecture seule**.

---

## Le point le plus important de cet audit

**`rpgquest:kit_tier2` figure dans ta liste des 17 quêtes à supprimer — mais c'est la quête
d'amélioration du kit que tu veux précisément conserver et étendre.**

`config.yml` ligne 322 la déclare comme `unlock-quest` du palier 2 du kit de départ (#218). Son titre
est **« Premiers pas dans le Wild »**, ce qui explique sans doute qu'elle ne ressemble pas à une
quête de kit dans le catalogue — et répond à ta question « pourquoi la quête d'amélioration du kit
n'apparaît pas ». Elle y est, mais sous un nom qui ne le dit pas.

La supprimer casserait la progression de kit que #218 a construite et que tu as validée le
2026-10-11 (TC-273). **À décider avant tout nettoyage** : la conserver telle quelle, la renommer, ou
la remplacer par une nouvelle quête de palier — mais pas la supprimer sans remplaçante.

---

## A. Inventaire du contenu

### Les 22 quêtes, et leurs trois origines distinctes

Ta liste distingue « à conserver » et « à supprimer ». L'inventaire réel impose une **troisième
dimension** : l'origine, qui détermine le geste de suppression.

| Origine | Nombre | Geste de suppression |
|---|---:|---|
| **Suivie dans Git** | 10 | suppression source (`/quests/delete`) **+** suppression serveur (#194) |
| **Dans la source mais NON SUIVIE** (tes fichiers) | 6 | idem, mais **ce sont tes fichiers** — à ne pas toucher sans ton accord explicite |
| **Hors source — runtime DEV seulement** | 6 | suppression serveur (#194) **uniquement** ; rien à supprimer côté source |

#### Suivies dans Git (10)

| Identifiant | Décision |
|---|---|
| `rpgquest:guard_tier1` … `guard_tier5` | **CONSERVER** |
| `rpgquest:crystal_hunt` | supprimer |
| `rpgquest:first_steps` | supprimer — **porte une progression ACTIVE** |
| `rpgquest:kit_tier2` | ⚠️ **voir l'avertissement ci-dessus** — progression COMPLETED |
| `rpgquest:premiers_pas` | supprimer — progression COMPLETED |
| `rpgquest:woodcutters_request` | supprimer |

#### Non suivies — tes fichiers (6)

`jeff_skeleton`, `lily_pumpkin`, `st0_meet_people`, `tc265_ai_securiser_environs`, `test_remise`,
`test_smelt_item` (cette dernière porte une progression **ACTIVE**).

Ces six fichiers existent dans `src/main/resources/quests/` mais **ne sont pas dans Git**. Les
supprimer depuis `/quests/delete` effacerait des fichiers qui n'ont aucune copie versionnée : la
seule reprise possible serait la sauvegarde horodatée du moteur de suppression.

#### Hors source — n'existent que sur DEV (6)

`help_recolte`, `jeff_nettoyage`, `junior_chantier`, `test_break_block`, `test_collect_item`,
`test_place_block`.

**Bonne nouvelle pour ta préoccupation** : ces six-là **ne réapparaîtront pas après un
redémarrage**, puisqu'aucun fichier source ne les sème. Un redéploiement de JAR ne recrée que les
exemples embarqués (`BUNDLED_EXAMPLES`), et aucune de ces six n'en fait partie. La suppression
serveur (#194) suffit donc, et elle est définitive.

### Références qui casseraient — le vrai coût du nettoyage

Supprimer les 17 quêtes **casse 2 stories et 5 dialogues**. Ce n'est pas un nettoyage de quêtes,
c'est une refonte coordonnée.

| Élément | Référence vers une quête supprimée |
|---|---|
| story `main_story` | `premiers_pas`, `first_steps`, `crystal_hunt` — **ses 3 étapes** |
| story `lily_memories` | `lily_pumpkin` — **sa seule étape** |
| dialogue `guard` | `crystal_hunt`, `first_steps` (2 de ses 7 `START_QUEST`) |
| dialogue `guide` | `premiers_pas`, `kit_tier2` (**ses 2 seuls** `START_QUEST`) |
| dialogue `help` | `help_recolte` |
| dialogue `jeff` | `jeff_nettoyage` |
| dialogue `junior` | `junior_chantier` |

Les deux stories perdraient **toutes** leurs étapes : elles doivent être supprimées ou réécrites, pas
laissées vides.

### Progressions de joueurs

Trois joueurs connus : `LoDyMcFly`, `Madix666`, `Rondoudou9000`. Pour `LoDyMcFly` : 3 ACTIVE,
2 COMPLETED, 17 NOT_STARTED.

**Quatre quêtes de la liste de suppression portent une progression réelle** : `first_steps` (ACTIVE),
`test_smelt_item` (ACTIVE), `kit_tier2` (COMPLETED), `premiers_pas` (COMPLETED). Et
**`guard_tier1` est ACTIVE** — elle est à conserver, donc rien à craindre, mais c'est à savoir avant
tout reset.

**Ce que la suppression fait des historiques** (lu dans `ContentDefinitionDeleter`) : elle supprime
le **fichier de définition** après une **sauvegarde horodatée** placée hors des dossiers de contenu,
et retrouve le fichier par l'**identifiant déclaré dedans**, jamais par un nom de fichier deviné.
Elle **ne touche à aucune ligne de progression** : les lignes de `quest_progress` subsistent en
**orphelines**. Inoffensives pour le moteur — une progression dont la définition a disparu n'est plus
évaluée — mais elles restent en base et réapparaîtraient si la définition revenait.

**Restauration** : le chemin de la sauvegarde est renvoyé dans le message de l'action, pour qu'une
reprise ne se devine pas. Pour les quêtes suivies dans Git, Git est de toute façon la meilleure
reprise.

### Les quatre gestes à ne pas confondre

Ta demande de distinguer est fondée, et voici la correspondance réelle :

| Geste | Outil | Effet |
|---|---|---|
| Supprimer **dans la source** | `/quests/delete`, `/stories/delete` | efface le fichier de `PLUGADMIN_CONTENT_DIR` (= le dépôt) |
| **Décharger du moteur** | `content.reload` | recharge depuis les fichiers présents : une définition absente disparaît du runtime |
| Supprimer **la copie serveur** | `content.definition.delete` (#194) | efface le fichier **sur DEV** + sauvegarde + rechargement |
| **Conserver les historiques** | automatique | aucune progression n'est jamais supprimée par ces trois gestes |

Pour une quête suivie dans Git, un nettoyage complet demande donc **deux** gestes : source **et**
serveur. Ne faire que l'un des deux laisse la quête revenir à la prochaine publication (si on ne
supprime que le serveur) ou subsister sur DEV (si on ne supprime que la source).

### Écarts source ↔ DEV actuels

Cinq ressources divergent et **ne doivent pas être publiées sans ta décision** :
`dialogues/mira_cartographer` (absent de DEV), `quests/crystal_hunt` (ta modification non commitée),
`quests/first_steps`, `quests/premiers_pas`, `quests/woodcutters_request`.

Trois d'entre elles figurent dans la liste de suppression : il serait absurde de les publier avant de
les supprimer.

---

## B. Parcours joueur — état réel

### Ce qui existe et fonctionne

| Élément | Ticket | État réel |
|---|---|---|
| Guide « centre d'aide » + journal de quêtes | #11 **fermé** | en service ; le journal est remis par le Libraire |
| Kit de départ en bois, redemandable après chaque mort | #26, #67 **fermés** | en service, validé en jeu |
| Progression du kit par paliers | #218 **ouvert** | palier 2 en service via `kit_tier2` ; paliers 3 à 5 **acceptés par le moteur mais sans contenu** |
| Onboarding cohérent, quête nommée, paliers lisibles | #235 **fermé** | validé en jeu le 2026-10-11 (TC-273) |
| Les 5 quêtes du Garde | — | intactes, `guard_tier1` ACTIVE |

### Ce qui n'existe pas encore

* **« Bienvenue à LodyQuests »** n'existe pas. Le parcours actuel démarre sur `premiers_pas`
  (« va te présenter au Libraire ») lancée par le Guide.
* **Paliers 3 à 5 du kit** : le moteur les accepte, aucun contenu ne les définit. C'est de la
  **configuration + contenu**, pas du Java.
* **La rune de rappel** : distribuée par le Guide via l'option « La rune de rappel », qui la
  (re)donne gratuitement dès qu'elle manque, sous garde `LACKS_CUSTOM_ITEM`. C'est un **objet
  soulbound** (`SoulboundItemService`), donc jamais perdu — et la garde empêche structurellement le
  double exemplaire.

### Anti-doublon sur les objets uniques — ta préoccupation explicite

Le mécanisme existe et il est le bon : le choix de dialogue n'apparaît que si l'objet **manque**
(`LACKS_CUSTOM_ITEM`), et l'objet est soulbound. Double-clic, reconnexion et mort ne peuvent donc pas
produire deux journaux ni deux runes. **À vérifier tout de même en jeu** lors de la reconstruction :
c'est une garantie de conception, et je ne l'ai pas éprouvée par un test de double-clic réel.

### Quête, action de dialogue, ou mécanisme système ?

| Fonction | Nature réelle |
|---|---|
| Obtenir le journal | **action de dialogue** (`GIVE_CUSTOM_ITEM` gardée) — pas une récompense de quête |
| Obtenir le kit | **mécanisme système** (`StarterToolKitService`), déclenché par un choix de dialogue |
| Améliorer le kit | **quête** (`kit_tier2`) + **config** (`starter-tool-kit.tiers`) |
| Rune de rappel | **action de dialogue** gardée + objet soulbound |
| Quêtes du Garde | **quêtes** pures, avec récompense `VARIABLE` + `COMMAND` |

C'est pourquoi « remplacer le journal par une quête » serait une régression : la garde anti-doublon
vit dans l'action de dialogue, pas dans la quête.

---

## C. PNJ — inventaire et capacités

### Les 15 entrées logiques, et leurs 4 états

| npcId | Déf. | Citizens | Nom affiché | Dialogue | État |
|---|---|---|---|---|---|
| `guide` | oui | #0 | Guide | `rpgquest:guide` | **LINKED** |
| `guard` | oui | #6 | `<yellow>Garde</yellow>` | `rpgquest:guard` | **LINKED** |
| `jeff` | oui | #3 | Jeff | `rpgquest:jeff` | LINKED |
| `mira_cartographer` | oui | #9 | Mira la Cartographe | `rpgquest:mira_first_map` | LINKED |
| `lily` | oui | #10 | Lily | **aucun** | LINKED |
| `andy` | oui | #12 | `<dark_green>Andy</dark_green>` | aucun | LINKED |
| `julius` | oui | #11 | Julius | aucun | LINKED |
| `woodcutter_bob` | oui | **aucun** | Bûcheron Bob | aucun | **NOT_LINKED** |
| `libraire` | **NON** | #1 | Libraire | `rpgquest:libraire` | **CITIZENS_ORPHAN** |
| `help` | **NON** | #2 (nommé « Tania ») | Help | `rpgquest:help` | **CITIZENS_ORPHAN** |
| `jo` | **NON** | #5 | Jo | `rpgquest:jo` | **CITIZENS_ORPHAN** |
| `junior` | **NON** | #4 | Junior | `rpgquest:junior` | **CITIZENS_ORPHAN** |
| `lily_intro` | **NON** | aucun | Lily | `rpgquest:lily_intro` | **UNDEFINED_REFERENCE** |
| `merchant` | **NON** | aucun | Marchand | `rpgquest:merchant` | **UNDEFINED_REFERENCE** |
| `robert_writer` | **NON** | aucun | Robert | `rpgquest:robert_writer` | **UNDEFINED_REFERENCE** |

Côté Citizens : **12 PNJ**, 11 liés, **1 non lié** — `#7 « Stan »`. Tous dans `world_hub`. Le
numéro 8 manque : trace d'une suppression antérieure (#226). Tous à `spawned=false` faute de chunk
chargé, ce qui est normal sans joueur.

### Positions réelles (toutes dans `world_hub`)

| Citizens | Nom | Position | Lié à |
|---|---|---|---|
| #0 | Guide | 738, 68, −683 | `guide` |
| #1 | Libraire | 782, 67, −693 | `libraire` |
| #2 | Tania | 772, 67, −702 | `help` |
| #3 | jeff | 771, 67, −685 | `jeff` |
| #4 | junior | 749, 67, −720 | `junior` |
| #5 | Jo | 713, 67, −686 | `jo` |
| #6 | Garde | 742, 67, −673 | `guard` |
| #7 | Stan | 760, 67, −689 | **—** |
| #9 | Mira | 712, 67, −702 | `mira_cartographer` |
| #10 | Lily | 719, 67, −671 | `lily` |
| #11 | Julius | 755, 69, −1093 | `julius` |
| #12 | Andy | 760, 71, −707 | `andy` |

Aucun skin MineSkin n'est configuré sur aucun PNJ. Un seul a la promenade activée (Andy) ; le regard
joueur est actif sur 8 des 12.

### Trois anomalies à traiter avant la refonte

1. **Quatre PNJ sans définition logique** (`libraire`, `help`, `jo`, `junior`). Ils n'existent que
   parce qu'un PNJ Citizens porte leur étiquette. **Conséquence directe pour ton projet** : l'« ancien
   Libraire » que tu veux renommer **ne peut pas** l'être par sa fiche logique — il n'y en a pas. Il
   faut d'abord créer la définition (`npc.definition.create`), ce qui est faisable depuis `/npcs`.
2. **Trois entrées fantômes** (`lily_intro`, `merchant`, `robert_writer`), déduites du **nom du
   dialogue** faute de porteur déclaré. Personne ne peut déclencher ces dialogues en l'état. C'est la
   classe de défaut de #225, fermé — le catalogue les signale correctement, mais les trois cas
   subsistent dans les données.
3. **Le dialogue de Lily n'est pas attaché à Lily.** `lily` a une définition, un PNJ Citizens et le
   rôle de donneuse de quête, mais **aucun dialogue** ; son dialogue vit sous l'entrée fantôme
   `lily_intro`. Avant toute story de Lily, il faut rattacher `rpgquest:lily_intro` à `lily` via son
   champ `dialogueId`. **C'est un prérequis de la section 7 de ta demande.**

### Ce que PlugAdmin sait faire sur les PNJ — tout ce qu'il te faut

`npc.definition.create|update|delete`, `npc.citizens.create|link|unlink|move|rename|skin|lookclose|wander|delete|provision`,
`quest.giver.set`. Plus la page `/npcs` et `/npcs/delete` (#226, avec analyse des dépendances).

**Renommer sans changer l'identifiant est bien prévu** : le nom affiché est un champ distinct de
l'identifiant logique, et `npc.citizens.rename` agit sur le PNJ en jeu. L'identifiant logique n'est
jamais dérivé du nom.

### Ce qui manque pour ton modèle d'identité en 6 couches

Ton modèle : identifiant technique / prénom affiché / **métier** / dialogue / quêtes / identité
Citizens. Cinq couches sur six existent. La sixième, le **métier**, existe partiellement : le
catalogue expose un champ `role`, mais il est **dérivé** (`quest_giver` déduit du fait que le PNJ
donne une quête), pas saisissable. Il n'y a **ni champ métier administrable, ni mécanisme de
traduction** pour celui-ci.

C'est un manque réel, et il justifie un ticket (voir backlog F-1).

---

## D. Bâtiments

| Élément | État |
|---|---|
| Moteur de schematics | **disponible**, aucun problème signalé |
| Bibliothèque | **2 bâtiments** : `test_hut_01` (7×6×5), `test_watchtower_01` (9×14×9), schematics présents, v1 |
| Emplacements | **3**, tous `OCCUPIED` dans `world_hub` |
| Relevé des placements | **fonctionne depuis le correctif #239 de ce soir** |

Les trois emplacements : `buildsite_0006` (Hutte, site NORTH, posé 180°, **divergent**),
`buildsite_0007` (EmptySpot, site SOUTH, posé 0°, **divergent**), `buildsite_0008` (Tower_1, site
WEST, posé 270°, aligné).

### Ton parcours en 10 étapes, confronté au réel

| Étape | État | Outil |
|---|---|---|
| 1. Marquer un emplacement en jeu | ✅ | commande en jeu (#213) |
| 2. Le nommer | ✅ | `building.site.rename` |
| 3. Le retrouver dans PlugAdmin | ✅ | `/buildings/sites` |
| 4. **Décrire le bâtiment en langage naturel** | ❌ **absent** | — |
| 5. **Générer avec l'IA** | ❌ **absent** | — |
| 6. Prévisualiser | ✅ (bâtiment de bibliothèque) | `building.placement.preview` |
| 7. **Corriger / régénérer** | ❌ absent | — |
| 8. Confirmer le placement | ✅ | `building.placement.place` |
| 9. Déplacer / rattacher le PNJ | ✅ | `npc.citizens.move` |
| 10. Modifier, remplacer, restaurer | ✅ | `reorient`, `replace`, `rollback` (#234) |

**Sept étapes sur dix sont opérationnelles.** Les trois manquantes sont exactement **#94**, qui est
un EPIC ouvert et **entièrement non implémenté** : aucune action agent, aucune route de panel, aucun
code de génération. L'atelier IA existant ne génère **que** quêtes, stories et dialogues.

La séparation que tu demandes — bâtiments physiques / définitions logiques / PNJ Citizens — est
**déjà respectée** par l'architecture : `BuildingDefinition` (bibliothèque), `BuildingSite`
(intention), `BuildingPlacement` (fait), et les PNJ Citizens sont un troisième référentiel
indépendant.

---

## E. Création IA

| Capacité | État |
|---|---|
| `/ai/studio` | en service ; génère **quêtes, stories, dialogues** |
| Fournisseurs | Anthropic, OpenAI, Gemini, HTTP générique — configurables depuis `/ai/providers` |
| Contrat machine-readable | #110 : schéma JSON, gabarits, contrat téléchargeable |
| Export / import de content pack | #108 / #109 : `/content/export`, `/content/import` |
| Publication vers DEV | #47 **fermé**, en service — et **sans FTP** |
| Génération de **bâtiments** | ❌ absente (#94) |

### Limites réelles à connaître avant la story de Lily

1. **Prérequis bloquant** : le dialogue de Lily n'est pas rattaché à Lily (voir C-3). Une story
   générée qui référence Lily ne serait pas jouable.
2. **Pas de suppression de dialogue** depuis PlugAdmin — voir F-2. Une génération IA ratée produit un
   dialogue qu'on ne peut **pas** retirer par l'interface.
3. Les objectifs disponibles couvrent dix types (`KILL_ENTITY`, `COLLECT_ITEM`, `BREAK_BLOCK`,
   `PLACE_BLOCK`, `CRAFT_ITEM`, `SMELT_ITEM`, `TALK_TO_NPC`, `REACH_LOCATION`,
   `DELIVER_ITEM_TO_NPC`, `DISCOVER_WAYPOINT`). À confronter à l'histoire voulue avant de générer.
4. La génération multiple et la modification après génération existent, mais l'enregistrement final
   exige `CONTENT_IMPORT` en plus de `AI_USE` — deux permissions distinctes, par conception.

---

## F. Backlog

### Tickets existants à reprendre, par priorité

| Priorité | Ticket | Pourquoi maintenant |
|---|---|---|
| **1** | **#194** — supprimer quêtes et stories | C'est l'outil du nettoyage. Ouvert, l'action existe déjà (`content.definition.delete`) : à éprouver en conditions réelles, et à compléter sur le **nettoyage des références** que son titre promet |
| **2** | **#218** — paliers de kit | Décide du sort de `kit_tier2` et de l'extensibilité aux paliers 3 à 5 |
| **3** | **#81** — lier une définition PNJ à un Citizens | Couvre les 4 PNJ `CITIZENS_ORPHAN`, dont le Libraire |
| **4** | **#165** — renommer et appliquer un skin depuis la fiche | C'est ton premier test ; ouvert, mais la capacité existe déjà |
| **5** | **#94** — génération IA de bâtiments | EPIC entièrement à faire ; bloque les étapes 4, 5 et 7 du parcours Hub |
| 6 | **#213 / #234 / #239** | #239 clos ce soir ; #213 et #234 restent ouverts pour leurs tests manuels |

### Nouveaux tickets stricto sensu nécessaires

**F-1 — Métier/rôle administrable et localisable pour les PNJ.** Ton modèle d'identité à six couches
en a cinq ; le `role` actuel est **dérivé** et non saisissable. Nécessaire pour « Elias, guide /
formateur » sans toucher à l'identifiant. Doit préparer la traduction des métiers **sans** imposer
celle des prénoms.

**F-2 — Suppression d'un dialogue depuis PlugAdmin.** Aujourd'hui impossible : ni route
`/dialogues/delete`, ni action agent (`content.definition.delete` n'accepte que `quests` et
`stories`, vérifié dans le code des deux côtés). Conséquence : les trois dialogues fantômes
(`lily_intro`, `merchant`, `robert_writer`) ne peuvent pas être retirés par l'interface, et une
génération IA ratée laisse un déchet définitif. **C'est la limitation de PlugAdmin la plus gênante
pour ton projet de refonte.**

**F-3 — Nettoyage coordonné des références à une quête supprimée.** Supprimer les 17 quêtes casse
2 stories et 5 dialogues. Il faut soit un diagnostic pré-suppression qui liste les références
(« cette quête est l'étape 2 de `main_story` et le choix 3 du dialogue `guide` »), soit un refus
accompagné de la liste. Sans cela, le nettoyage produira un serveur aux références pendantes.
Recoupe le titre de #194 — à décider : compléter #194 ou ouvrir un ticket distinct.

**F-4 — Purge optionnelle des progressions orphelines.** Après suppression, les lignes de
progression subsistent. Inoffensif, mais sans outil pour les constater ni les purger. Priorité basse.

Je n'ai **ouvert aucun ticket** : tu m'as demandé de les *préparer*. Dis-moi lesquels créer.

---

## G. Premier test utilisateur — renommer le Guide

L'audit confirme que c'est le bon point de départ : `guide` a une **définition logique**, un **PNJ
Citizens lié** (#0) et un **dialogue**. Aucun prérequis n'est nécessaire.

*(Si tu voulais commencer par le Libraire, il faudrait d'abord lui créer une définition : il est
`CITIZENS_ORPHAN`.)*

### Le parcours exact

**URL** : `https://plugadmin.lodylands.com/npcs?agent=rpgquest-dev`

1. Cliquer **« Rafraîchir »** sur la barre du catalogue PNJ — sans cela la page travaille sur un
   relevé ancien.
2. Repérer la fiche **`guide`** — état **LINKED**, nom affiché « Guide », dialogue `rpgquest:guide`.
3. Ouvrir la fiche, puis la section de renommage **Citizens**.
4. **Champ à remplir** : le nouveau nom affiché, par exemple **`Elias`**.
   **Ne toucher à aucun champ d'identifiant.**
5. Cocher la **confirmation** (le renommage est une mutation sensible).
6. Cliquer le bouton de **renommage**.

### Résultat attendu dans PlugAdmin

* l'action rend **SUCCESS** ;
* la fiche affiche **`Elias`** comme nom affiché ;
* l'identifiant logique reste **`guide`**, et l'état reste **LINKED** sur Citizens **#0** ;
* le dialogue reste **`rpgquest:guide`** ;
* aucun avertissement nouveau n'apparaît sur la fiche.

### Contrôles dans Minecraft

* le PNJ en **738, 68, −683** (`world_hub`) porte le nom **Elias** au-dessus de la tête ;
* un clic droit ouvre **le même dialogue** qu'avant, et ses options de quête fonctionnent ;
* les quêtes qu'il donne restent proposées — son rôle de donneur est attaché à `guide`, pas au nom.

### Après rafraîchissement, et après redémarrage

* **F5 sur `/npcs`** : le nom reste `Elias`, l'identifiant reste `guide` ;
* **« Rafraîchir » puis relire** : idem — c'est le relevé serveur qui le confirme, pas le cache de
  page ;
* **après un redémarrage du serveur** (quand tu en feras un) : le nom doit persister, puisqu'il est
  porté par Citizens et non par un état mémoire.

### Le point qui compte vraiment

Vérifier qu'**aucune référence ne casse** : le dialogue, les quêtes données et la liaison Citizens
doivent être identiques avant et après. C'est la garantie « un renommage ne change pas
l'identifiant » que tu demandes, et c'est elle qu'il faut éprouver.

---

## Comment repartir sur une base propre — l'ordre que je recommande

1. **Trancher le sort de `kit_tier2`** avant tout nettoyage.
2. **Renommer le Guide** (test G ci-dessus) : court, réversible, et il valide la chaîne complète
   panel → serveur → jeu.
3. **Rattacher le dialogue de Lily à Lily**, et créer les définitions des 4 PNJ orphelins —
   prérequis de la story de Lily et du renommage du Libraire.
4. **Supprimer d'abord les 6 quêtes « hors source »** : un seul geste, aucun fichier concerné, aucune
   story cassée sauf via les dialogues `help`/`jeff`/`junior`. C'est le lot le moins risqué, et il
   éprouve #194 en conditions réelles.
5. **Puis les 2 stories** (`main_story`, `lily_memories`), avant leurs quêtes — sinon elles restent
   avec des étapes pendantes.
6. **Puis les quêtes suivies dans Git**, en corrigeant les dialogues `guard` et `guide` dans le même
   lot.
7. **Tes 6 fichiers non suivis** en dernier, et seulement sur ton geste explicite.
8. **Reconstruire** le parcours Guide, puis attaquer #94 pour le Hub.

---

## Limitations de PlugAdmin relevées par cet audit

| # | Limitation | Gravité |
|---|---|---|
| 1 | **Aucune suppression de dialogue** depuis l'interface | **forte** — bloque le nettoyage et la reprise après une génération IA ratée |
| 2 | **Aucune génération IA de bâtiment** (#94) | forte — 3 étapes du parcours Hub sur 10 |
| 3 | Pas de **métier/rôle** administrable ni localisable | moyenne — bloque ton modèle d'identité |
| 4 | Pas de **diagnostic des références** avant suppression | moyenne — risque de références pendantes |
| 5 | Pas d'outil pour les **progressions orphelines** | faible |

Tout le reste de ce que tu veux administrer — quêtes, stories, dialogues (hors suppression), PNJ,
emplacements, placement, publication, diagnostic — **est réalisable depuis PlugAdmin aujourd'hui**.

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `docs/claude-reports/2026-10-11_1852_audit-refonte-contenu-pnj-hub.md` | ce rapport |

## Fichiers modifiés

Aucun fichier de code, de contenu ou de configuration. **Audit strictement en lecture.**

## Base de données / migrations

Aucune. Les seules écritures en base sont les lignes `agent_action` des relevés en lecture seule,
dans la base du panel.

## Tests automatiques

Aucun test ajouté : aucun code modifié. Les relevés ont été obtenus par **13 actions agent en
lecture seule** (`quest.list`, `story.list`, `dialogue.list`, `npc.list`, `npc.citizens.list`,
`building.site.list`, `building.definition.list`, `item.list`, `mob.list`, `player.catalog`,
`quest.player.status`, `story.player.status`, `content.dev.state`), toutes **SUCCESS**.

## Tests manuels à effectuer

Le parcours **G** ci-dessus, et lui seul. En attente restent par ailleurs : TC-275/TC-276 (#156),
TC-277, TC-278, TC-273 et la validation visuelle de #239.

## Déploiement VeryGames

**Aucun.** Aucun JAR, aucun redémarrage, aucune publication de contenu, aucun contournement TLS.
L'autorisation exceptionnelle du JAR #239 n'a pas été réutilisée, conformément à ta consigne.

## Limitations / travail restant

* Les quatre nouveaux tickets (F-1 à F-4) sont **préparés, non créés** — j'attends ton accord.
* Je n'ai pas éprouvé par un test réel l'anti-doublon du journal et de la rune : c'est une garantie
  de conception que j'ai lue dans le code, pas une observation.
* Les progressions ne sont relevées que pour `LoDyMcFly` ; `Madix666` et `Rondoudou9000` n'ont pas
  été interrogés.

## Prochaine étape suggérée

Le test G, puis ta décision sur `kit_tier2` et sur les tickets à ouvrir.

## Commits

Branche `audit/refonte-contenu-pnj-hub`, créée depuis la ligne déployée. Aucune fusion. Les sept
fichiers de contenu non suivis et `crystal_hunt.yml` sont **intacts** et n'ont jamais été ajoutés au
suivi.
