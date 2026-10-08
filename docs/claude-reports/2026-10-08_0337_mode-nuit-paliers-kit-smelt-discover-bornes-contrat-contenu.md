# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-08
* Heure : 03:37 (heure locale)
* Sujet : Mode nuit autonome — paliers du kit de départ (#218), objectif `SMELT_ITEM` (#141), audit et correction de l'appariement des bornes du Hub (#156), objectif `DISCOVER_WAYPOINT` (#185), contrat de contenu machine-readable (#110), texte stylé sans MiniMessage partout (#195), plus deux petits bugs du Control Panel
* Statut : DONE pour les lots livrés — PARTIAL sur le programme global de la nuit : #109 (import de content pack) et le LOT 4 (atelier IA) ne sont pas commencés, volontairement et avec motif
* Branche Git : `feature/218-starter-kit-tiers` (aucun merge, aucune issue fermée)
* Commit actuel si disponible : b0c0b8f (déployé) — 7 commits cette nuit, tous poussés
* Début de la tâche : 2026-10-08 00:33:42
* Fin de la tâche : 2026-10-08 03:37:27
* Durée totale : 03:03:45

## Demande

Session autonome de nuit (« MODE NUIT AUTONOME — LodyQuests »), avec pour
consigne de maximiser les fonctionnalités livrées et les bugs corrigés, dans
cet ordre de priorité : (1) quêtes / stories / PNJ, (2) content packs,
(3) véritable atelier de génération de quêtes par IA dans le Control Panel,
(4) rendre le Control Panel utilisable sans DevOps manuel, (5) unifier toute
saisie de texte stylé pour qu'un utilisateur n'ait jamais à taper de
MiniMessage dans le chemin normal, (6) corriger un maximum de petits bugs
clairement spécifiés, (7) laisser une grande liste de choses à tester.

Lots annoncés : LOT 0 (vérifier que la branche contient tout #123, ne pas le
réimplémenter, ne pas fermer l'issue), LOT 1 (#218 paliers du kit de départ),
LOT 2 (#141 `SMELT_ITEM` et #185 `DISCOVER_WAYPOINT`), LOT 3 (content packs
#108 / #109 / #110), LOT 4 (atelier IA + configuration de fournisseurs).
En cours de session, l'utilisateur a ajouté : « ajoute le ticket 156 a tout
ca » (#156, densité et accessibilité des bornes de voyage, en PRIORITÉ).

Contraintes : partir de la ligne réellement déployée en DEV, préserver tous
les fichiers de contenu locaux non suivis, aucun merge, commit et push
autorisés, déploiement DEV autorisé, un seul Gradle à la fois, pas de
sous-agents, tests ciblés pendant le développement puis suite complète avant
le rapport, ne jamais masquer un test en échec, ne pas inventer une décision
de gameplay non spécifiée, ne pas fermer une issue dont un test manuel
pertinent reste à faire.

**Le prompt reçu était tronqué** : il s'arrête au titre de section
« COÛT / TRAÇABILITÉ », dont le contenu n'a jamais été transmis. Aucune
consigne de cette section n'a donc pu être appliquée.

## Analyse

### LOT 0 — état réel de la branche de départ

Audit Git d'abord. La branche de travail `feature/218-starter-kit-tiers`
contenait déjà **tout** #123 (`DELIVER_ITEM_TO_NPC` : moteur, dialogue,
descripteur du panel, journal, export, correctif TC-257 du PNJ destinataire).
Rien n'a été réimplémenté, l'objectif reste le mécanisme officiel de remise,
et #123 n'a pas été fermé — sa validation manuelle TC-257 reste à dérouler.

### #156 — audit factuel avant toute correction

Le ticket demandait un rapport factuel (« bug moteur, configuration,
terrain/placement, densité insuffisante ou simple repérage ; nombres et
exemples réels à l'appui ») et interdisait explicitement de fixer un ratio
arbitraire « une borne pour N waypoints » ou d'activer en silence une nouvelle
politique de génération.

**Une copie en lecture seule de la base DEV a été téléchargée** pour mesurer
au lieu de supposer. Première correction : le ratio évoqué (115 waypoints pour
13 bornes) comparait deux populations différentes.

| Monde | Waypoints | Bornes |
|---|---|---|
| `wild` | 110 | 0 — **par conception**, aucune borne n'est générée dans le Wild |
| `world_hub` | 22 | 13, toutes appariées |

Le vrai écart est donc **9 instances du Hub sur 22** sans borne, pas 102 sur
115.

**Hypothèse testée puis réfutée.** L'appariement rejette un emplacement situé
à moins de `travel.waypoint.minimum-spacing` (80 blocs) d'une autre borne. Pour
les 9 instances concernées, la borne la plus proche est à **88 blocs ou plus** :

| Instance | Distance à la borne la plus proche |
|---|---|
| `sparse_jungle_2_-5` | 88 |
| `forest_2_-3` | 93 |
| `windswept_forest_0_-2` | 107 |
| `savanna_0_-3` | 108 |
| `savanna_1_-3` | 136 |
| `dark_forest_0_-2` | 140 |
| `birch_forest_0_0` | 156 |
| `mangrove_swamp_1_-1` | 166 |
| `swamp_0_0` | 219 |

L'anneau de candidats fait 6 à 16 blocs autour du waypoint : à 88 blocs, une
partie de l'anneau reste à plus de 80 blocs de la borne voisine. L'espacement
ne pouvait donc pas rejeter **tous** les candidats. Ce n'est ni un problème de
terrain, ni de configuration, ni de densité.

**Preuve décisive, dans les horodatages.** Aucune des 13 bornes existantes n'a
été créée en même temps que son waypoint : l'écart va de **17 secondes à
11 h 50**.

C'est la signature exacte d'un **défaut du déclencheur**, confirmé dans le
code : la persistance du waypoint est asynchrone, donc le tout premier passage
d'un joueur ne trouve pas encore de waypoint à apparier et abandonne ; et la
sortie anticipée « ce joueur est déjà dans cette instance » interdisait ensuite
toute nouvelle tentative jusqu'à ce qu'il quitte l'instance puis y revienne.
Le commentaire du code promettait déjà le bon comportement (« l'appariement
réessaiera au prochain déplacement du joueur dans cette instance ») — c'était
la garde juste au-dessus qui l'en empêchait.

## Travail effectué

### 1. #218 — paliers du kit de départ (LOT 1, terminé)

Le kit unique de #26 devient le **palier 1** d'une progression déclarative
(`starter-tool-kit.tiers` dans `config.yml`). Palier 2 décidé par
l'utilisateur : remettre 1 `STICK` + 2 `COBBLESTONE` + 4 `LEATHER` +
3 `WHEAT_SEEDS` au Guide donne épée en pierre, pioche / pelle / hache en bois,
bottes en cuir et un pain. Les **graines** sont une décision de gameplay
assumée et commentée dans la quête : produire du blé suppose déjà un endroit
sécurisé pour cultiver, des graines se trouvent immédiatement dans le Wild.

Décision structurante : **l'impossibilité de sauter un palier est une garantie
du moteur, pas une convention de données.** Le déblocage passe par la
récompense `COMMAND` → `/rpgadmin kit grant-tier`, et le service refuse un
niveau non contigu (`SKIPPED`), un niveau non défini (`UNKNOWN_TIER`) et ne
fait rien si le palier est déjà atteint (`ALREADY_AT_LEAST`, donc une quête
rejouée ne redonne rien). Débloquer en écrivant directement la variable
contournerait ce garde-fou : la configuration et la documentation l'interdisent
explicitement.

Les emplacements libres exigés sont calculés sur le **contenu réel** du palier
(six pour le palier 2, pas quatre) et le message annonce le bon chiffre. Une
valeur de palier aberrante en base sert le meilleur palier défini, jamais une
absence de kit. La configuration exige des niveaux **contigus depuis 1** : un
trou ferait du palier suivant une impasse, mieux vaut refuser de démarrer. Un
`config.yml` antérieur à #218 n'a que `items:` — il est lu tel quel et devient
le palier 1, donc un serveur déjà déployé ne perd rien.

L'architecture accepte les paliers 3 à 5 ; **leur contenu n'a pas été inventé**,
conformément à la consigne.

### 2. #141 — objectif `SMELT_ITEM` (LOT 2)

Neuvième type d'objectif. Audit des deux événements de four : `FurnaceSmeltEvent`
ne porte **aucun joueur**, `FurnaceExtractEvent` porte le joueur, le type
d'objet et la quantité réellement retirée — c'est donc le seul qui permette une
attribution correcte en multijoueur. Les trois fours vanilla (`FURNACE`,
`BLAST_FURNACE`, `SMOKER`) sont vérifiés explicitement, jamais supposés.

Le moteur sait désormais progresser **par lots** : une extraction qui sort
plusieurs objets d'un coup avance d'autant, plafonnée au reste à faire.
`ActiveQuestProgress` passe en `ConcurrentHashMap`, ses compteurs étant aussi
lus depuis un thread base.

Limites assumées et documentées : obtenir l'objet autrement (coffre, `/give`,
craft, ramassage) ou le laisser sortir par un entonnoir ne progresse jamais ;
un joueur qui vide le four d'un autre progresse, seule attribution que l'API
publique garantisse.

### 3. #156 — appariement des bornes du Hub (PRIORITÉ ajoutée en cours de session)

Correctif ciblé : l'évaluation se poursuit tant que l'instance n'a pas sa
borne, au lieu d'être coupée par « même instance ». Le coût reste borné par le
throttle de déplacement, le verrou mono-vol et le backoff de réessai déjà en
place. **Aucun ratio borne/waypoint introduit, aucune politique de génération
modifiée** : la densité reste « une borne par instance de biome visitée ».

Le test de non-régression a été **vérifié comme tel** : l'ancien comportement a
été réintroduit temporairement, le test a échoué, le correctif a été remis, il
passe. Un test qui passe dans les deux cas ne vaut rien.

Le diagnostic devient actionnable, en lecture seule, avec **une source unique**
(`TravelBeaconService#hubPairingGaps`) pour les deux surfaces :
`/rpgadmin travel diagnose` ne confond plus « jamais tenté » et « en cours », et
la page `/travel` du Control Panel gagne une section « Appariements manquants
dans le Hub » donnant pour chaque instance la **cause** (*jamais tenté depuis
le démarrage* = déclencheur, *appariement en cours*, *N essai(s), candidats
refusés* = terrain ou espacement), la distance à la borne la plus proche et le
délai avant le prochain essai. Les compteurs vivant en mémoire, l'affichage
annonce qu'un redémarrage les remet à zéro.

### 4. #185 — objectif `DISCOVER_WAYPOINT` (LOT 2)

Dixième type d'objectif : découvrir N waypoints **distincts**.

Décision d'architecture : **aucun écouteur d'événement de jeu**, volontairement.
Un écouteur de déplacement ou d'interaction recréerait une seconde règle de
découverte à côté de celle du système de waypoints, avec la quasi-certitude de
diverger (passage à proximité, téléportation, reclic). La progression vient
exclusivement de l'abonnement aux **premières** découvertes, branché une seule
fois au bootstrap : le système de waypoints ne connaît pas le moteur de quêtes
et réciproquement.

**Aucune table supplémentaire.** Une première découverte est unique par couple
(joueur, waypoint) au niveau de la base : un second clic ne notifie jamais, et
le compteur ne peut pas monter deux fois pour le même waypoint, même après une
mort, une reconnexion ou un redémarrage. L'identité comptée est l'**id stable**
du waypoint — deux waypoints du même biome comptent séparément, renommer ou
déplacer un waypoint ne le fait pas compter deux fois.

La règle de comptage est **explicite et annoncée au joueur**, comme l'exige le
ticket : `NEW_ONLY` (défaut) ou `INCLUDE_EXISTING` (le total déjà acquis compte,
recalculé depuis le vrai total du joueur donc exact après un redémarrage, et
jamais en régression). Un `count-mode` inconnu est **refusé** au chargement
plutôt que rabattu en silence sur le défaut. Le libellé du journal énonce
toujours la portée (tous mondes, ou la liste retenue) et la règle.

Conséquence assumée et documentée : en `NEW_ONLY`, un joueur ayant déjà tout
découvert ne peut pas valider un nouveau cycle d'une quête répétable — aucune
découverte n'est supprimée ni aucun waypoint débloqué pour y remédier, le
ticket l'interdit.

Le moteur gagne un **écrivain de compteur unique** partagé par la progression
événementielle et le recalcul cumulatif, pour qu'il n'existe jamais deux façons
d'écrire un compteur.

### 5. #110 — contrat de contenu machine-readable (LOT 3, phase 1)

Trois documents décrivent le format `lodyquests-content-pack` v1 sans accès au
code ni au serveur, téléchargeables depuis `/content/export` :
`GET /content/schema.json` (JSON Schema draft 2020-12),
`GET /content/template?family=…` (gabarits YAML commentés, par famille ou pack
complet) et `GET /content/contract.md` (contrat rédigé de ~13 Ko, pensé pour
être collé dans un prompt : règles d'identifiants, les 10 objectifs et 5
récompenses avec leurs paramètres, règles de stories, références autorisées,
conventions de dialogue, équilibrage, exemple minimal et exemple complet).

Décision centrale : **rien n'est maintenu à la main.** Tout est dérivé de
`Descriptors`, la source qui pilote déjà le formulaire, l'écriture YAML,
l'aller-retour et la validation du panel — et dont `EditorDescriptorsTest`
verrouille l'ensemble des types sur l'`ObjectiveType` du moteur. Un type ajouté
au moteur apparaît dans les trois documents sans qu'une ligne soit recopiée, et
le test du contrat échoue si celui-ci décrit un type inexistant, en oublie un,
ou laisse passer un champ qu'aucun descripteur ne déclare.

Le schéma épingle `format` et `schemaVersion` en constantes (les seuls champs
sur lesquels un import décide), contraint les identifiants de quête par
expression régulière, exige au moins une étape et un objectif par étape, et
refuse tout champ étranger par branche de type (`additionalProperties: false`).
Les dépendances externes sont déclarables par famille, les métadonnées de
provenance restent ouvertes et sans effet sur le gameplay.

Exemples préfixés `tc110_` : reconnaissables, donc faciles à retrouver et à
supprimer après un essai.

**Limite assumée, écrite dans le contrat lui-même** : la section `dialogues`
n'est contrainte que sur son squelette. Le vocabulaire de ses actions et
conditions vit dans les énumérations du plugin et n'est, à ce jour, déclaré
nulle part que le Control Panel puisse dériver ; le schéma laisse donc ces
tableaux libres plutôt que de recopier une liste qui divergerait en silence.

Au passage : le lien « fiche Content pack » de la page d'export pointait vers
une fiche **inexistante** du centre d'aide. La fiche existe maintenant.

### 6. #195 (suite) — plus aucun MiniMessage à écrire dans le chemin normal

Audit de **toutes** les pages du panel, documenté dans la Bible. Le composant
guidé partagé couvre désormais tous les champs de texte destinés aux joueurs :
**titre** et **description** d'une quête, **nom** d'une story, **réplique de
départ** d'un dialogue, en plus des noms de PNJ, de mobs/boss et des textes de
nœuds et de choix déjà couverts. Une variante **multiligne** a été ajoutée.

Un **second mécanisme concurrent** a été retiré : l'éditeur de dialogue offrait
un `select` « Couleur du texte » appliqué au texte simple et ignoré dès que le
texte contenait du MiniMessage. Deux mécanismes pour le même besoin divergent
tôt ou tard. Le serveur continue d'accepter le champ `text_color` — un
enregistrement antérieur ou une requête directe fonctionnent à l'identique — le
formulaire ne l'émet simplement plus.

Contrat serveur inchangé partout : chaque champ soumis garde son nom et sa
valeur MiniMessage, donc `QuestYaml`, les validateurs, les actions agent et le
plugin ne voient aucune différence. Sans JavaScript, le champ stocké reste
visible et éditable. Un texte déjà écrit avec plusieurs styles est conservé tel
quel et n'est **jamais** simplifié sans geste explicite.

Les seuls champs restés en texte simple sont ceux qui ne sont jamais colorés en
jeu : le locuteur d'un dialogue et les valeurs techniques des descripteurs.

## Décisions prises seul, et pourquoi

Conformément à la consigne « si un ticket nécessite une décision gameplay non
spécifiée, n'invente pas », voici ce qui a été tranché et ce qui a été laissé
ouvert.

**Tranché** (choix techniques, réversibles, cohérents avec l'existant) :

- `DISCOVER_WAYPOINT` ne pose **aucune** table : l'unicité (joueur, waypoint)
  déjà garantie en base suffit au comptage distinct.
- `DISCOVER_WAYPOINT` ne s'abonne à **aucun** événement de jeu : la règle de
  découverte reste celle du système de waypoints, une seule fois.
- Le schéma #110 est généré **côté Control Panel** depuis `Descriptors`, parce
  que c'est la seule source déclarative déjà verrouillée sur le moteur par un
  test.
- Le `select` de couleur concurrent de l'éditeur de dialogue est **retiré**
  plutôt que conservé en parallèle du composant guidé.
- L'appariement de borne est **réessayé au fil de l'exploration**, jamais par un
  balayage du monde ni par un rattrapage au chargement — ce dernier serait une
  nouvelle politique de génération, que le ticket interdit d'activer en silence.

**Laissé ouvert, volontairement** :

- Contenu des **paliers 3 à 5** du kit (#218) : décision de gameplay.
- Valeurs de **densité** des bornes (#156) : le ticket demande de proposer des
  valeurs mesurées pour validation, pas de les appliquer. Les mesures sont dans
  ce rapport et dans TC-261 ; aucune valeur n'a été changée.
- **Vocabulaire des dialogues** dans le schéma #110 : laissé non contraint,
  explicitement, plutôt que recopié.
- `COBBLESTONE` affiché « Pierre taillée » : signalé au lot précédent comme un
  défaut. **Non corrigé** : je n'ai trouvé aucune source fiable dans le dépôt
  pour trancher entre « Pierre » et « Pierre taillée », et remplacer une
  étiquette douteuse par une autre n'apporte rien. À trancher sur le fichier de
  langue réel du client.

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `src/main/java/.../quest/model/DiscoverWaypointObjective.java` | objectif #185 : quantité, filtre de mondes, règle de comptage |
| `src/test/java/.../quest/progress/QuestDiscoverWaypointObjectiveTest.java` | 10 cas #185 (vraie base SQLite, vrai moteur) |
| `docs/manual-tests/quests/test_discover_waypoint.yml` | quête de test manuel #185 (exigée par `ManualTestQuestPackTest`) |
| `control-panel/.../content/ContentPackSchema.java` | JSON Schema #110 généré depuis les descripteurs |
| `control-panel/.../content/ContentPackTemplates.java` | gabarits, exemples et contrat rédigé #110, générés |
| `control-panel/src/test/.../content/ContentPackContractTest.java` | 20 cas #110 — vérifient la *dérivation*, pas une liste recopiée |
| `control-panel/src/test/.../web/ContentContractRoutesTest.java` | 6 cas #110 — les trois routes servies, authentifiées, agent hors ligne |
| `control-panel/src/main/resources/docs/content-packs.md` | fiche du centre d'aide « Content packs » (lien jusqu'ici cassé) |

Créés plus tôt dans la même session (lots #218 et #141, déjà décrits) :
`config/StarterKitTier.java`, `quests/kit_tier2.yml`,
`player/StarterKitTierProgressionTest.java`, `quest/model/SmeltItemObjective.java`,
`quest/progress/QuestSmeltListener.java`,
`quest/progress/QuestSmeltObjectiveTest.java`,
`docs/manual-tests/quests/test_smelt_item.yml`.

## Fichiers modifiés

**Moteur (plugin)**

| Fichier | Changement |
|---|---|
| `quest/model/ObjectiveType.java` | +`SMELT_ITEM`, +`DISCOVER_WAYPOINT` (9 puis 10 types) |
| `quest/model/QuestObjective.java` | interface scellée étendue — les `switch` exhaustifs forcent chaque consommateur à être mis à jour |
| `quest/QuestDefinitionParser.java` | parsing des deux nouveaux types ; `count-mode` inconnu **refusé** |
| `quest/progress/QuestObjectiveIndex.java` | index des deux nouveaux types ; liste plate pour la découverte (aucune clé naturelle) |
| `quest/progress/QuestProgressEngine.java` | progression par lots plafonnée, écrivain de compteur **unique** partagé, `handleWaypointDiscovered`, recalcul cumulatif |
| `quest/progress/ActiveQuestProgress.java` | `ConcurrentHashMap` (compteurs lus depuis un thread base) |
| `waypoint/WaypointService.java` | publication des **premières** découvertes + comptage filtré par monde, sans accès base ni scan monde |
| `travel/beacon/TravelBeaconService.java` | correctif d'appariement #156 + `hubPairingGaps()` (lecture des index en mémoire seulement) |
| `admin/RpgAdminCommand.java` | branche `kit` (#218) ; `travel diagnose` ne confond plus « jamais tenté » et « en cours » |
| `bootstrap/RPGQuestBootstrap.java` | branchement unique découvertes ↔ moteur de quêtes |
| `ui/ObjectiveLabels.java` | libellés des deux nouveaux types ; portée et règle de comptage **annoncées** |
| `config/StarterToolKitConfig.java`, `config/ConfigValidator.java` | paliers (#218), compatibilité `items:` héritée |
| `player/StarterToolKitService.java` | `grant-tier`, refus de saut de palier, emplacements calculés sur le contenu réel |
| `content/pack/{ContentPackMapper,ContentPackSerializer,QuestPackEntry}.java` | les deux nouveaux types voyagent dans un pack, portée et règle incluses |
| `web/agent/{AgentActions,AgentActionExecutor,BukkitAgentActions}.java` | `travel.catalog` expose la **cause** de chaque appariement manquant ; l'objectif transporte `worlds`/`countMode` |

**Control Panel**

| Fichier | Changement |
|---|---|
| `content/Descriptors.java` | descripteurs `SMELT_ITEM` et `DISCOVER_WAYPOINT` ; nouveau type de champ `LIST` |
| `content/QuestYaml.java` | aller-retour exact d'un champ liste (`a, b` ↔ `[a, b]`) |
| `content/QuestValidator.java` | validation d'un champ liste (entrées inconnues, doublons) et des vocabulaires **fixes** |
| `content/ContentDeletionAnalyzer.java` | `kit_tier2` ajouté aux exemples embarqués connus |
| `json/Json.java` | `writePretty` pour les documents JSON destinés à être lus |
| `web/ObjectiveText.java` | résumé identique que l'objectif vienne de la source ou du runtime |
| `web/AgentPages.java` | section « Appariements manquants dans le Hub » ; `worlds`/`count-mode` de la source normalisés ; badges étapes/objectifs corrigés |
| `web/ContentEditorPages.java` | champ liste ; titre/description/nom/réplique passés au composant guidé ; `select` de couleur retiré |
| `web/ContentExportPages.java` | carte « Contrat de contenu » |
| `web/PanelApp.java` | trois routes de téléchargement du contrat |
| `web/StyleField.java` | variante multiligne ; marqueur « * » séparé de l'attribut HTML `required` |
| `resources/docs/_index.txt` | fiche « Content packs » exposée |

## Configuration / données

- `config.yml` du dépôt : nouveau bloc documenté `starter-tool-kit.tiers`
  (paliers 1 et 2), l'ancien `items:` conservé pour compatibilité.
- **Aucun fichier de configuration à transférer sur le serveur** : la section
  manquante est ajoutée au démarrage par `ConfigFileCompleter`, qui ne copie que
  les clés absentes et n'écrase jamais une valeur existante.
- Nouvelle quête embarquée `quests/kit_tier2.yml` : **semée automatiquement** au
  démarrage car absente du serveur.
- `dialogues/guide.yml` : **doit** être transféré explicitement (un
  redéploiement de JAR ne met jamais à jour un dialogue déjà présent).

## Base de données / migrations

**Aucune migration, aucun changement de schéma.** C'est une décision, pas un
hasard :

- #185 n'a besoin d'aucune table : l'unicité (joueur, waypoint) de la table
  `waypoint_discoveries` existante suffit à garantir le comptage distinct, et
  le compteur d'objectif vit déjà dans `quest_objective_progress` ;
- #141 réutilise le même compteur d'objectif ;
- #218 stocke le palier atteint dans `player_variables`
  (`STARTER_KIT_TIER`), comme toute autre variable joueur — donc
  `resetnew` le remet à zéro sans une ligne de code dédiée ;
- #156 et #110 ne touchent à aucune donnée.

Aucune progression joueur n'est modifiée, effacée ni réinitialisée.

## Préservation du contenu local (vérifiée)

Les fichiers de contenu créés depuis le Control Panel ou à la main, non suivis
par Git, ont été **laissés strictement intacts** — aucun n'a été modifié, ni
committé, ni supprimé :

| Fichier | Dernière modification | État |
|---|---|---|
| `quests/jeff_skeleton.yml` | 2026-10-04 10:54 | intact, non suivi |
| `quests/lily_pumpkin.yml` | 2026-09-10 20:54 | intact, non suivi |
| `quests/st0_meet_people.yml` | 2026-09-10 15:29 | intact, non suivi |
| `quests/test_remise.yml` | 2026-10-07 22:34 | intact, non suivi |
| `stories/lily_memories.yml` | 2026-09-10 22:28 | intact, non suivi |
| `quests/crystal_hunt.yml` | 2026-10-04 15:11 | **modifié par le propriétaire**, laissé tel quel, non committé |

Aucune de ces dates n'est postérieure au début de la session (00:33:42).

Chaque `git add` de cette session a listé des chemins **explicites**, et chaque
commit a été vérifié par un filtre cherchant ces fichiers dans l'index avant
d'être créé — précaution prise après l'incident du lot #218 de cette même nuit,
où un `git add -A` avait ajouté ces fichiers par mégarde (rattrapé
immédiatement, aucun n'est entré dans l'historique).

Le build et le déploiement ont été faits depuis un **worktree Git propre**
(`/srv/rpgquest/worktree-nuit`, détaché sur le commit poussé) et non depuis
l'arbre de travail : c'est la seule façon d'obtenir une suite de tests
représentative du commit, puisque `crystal_hunt.yml` modifié localement fait
échouer `CrystalHuntIntegrationTest` et bloquerait le script de déploiement.

## Tests automatiques

Exécutés depuis un **worktree Git propre** détaché sur le commit déployé — seule
façon d'obtenir un résultat représentatif du commit (voir « Préservation du
contenu local »).

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| Plugin (`:test`) | 1890 | 0 | 0 | 37 |
| Control Panel (`:control-panel:test`) | 770 | 0 | 0 | 1 |
| `web-api` (`:web-api:test`) | 30 | 0 | 0 | 0 |

`./gradlew build` : **BUILD SUCCESSFUL**. Deux passages : le premier (commit `7072d92`) en 30 min 51 s, tout construit depuis zéro ; le second (commit `b0c0b8f`, correctif de badge) en 10 min 12 s, avec `:test` et `:web-api:test` **UP-TO-DATE** — ce qui prouve au passage qu'aucune source du plugin n'a changé entre les deux. **Total : 2 690 tests, 0 échec, 0 erreur.**

Les 38 tests ignorés sont **tous préexistants** et aucun n'appartient
aux classes ajoutées cette nuit : tests d'intégration MariaDB (qui exigent un
vrai serveur) et quelques cas que MockBukkit ne peut pas simuler
(`ClaimTeleportServiceTest`, `SpecialMobServiceTest`,
`TravelBeaconServiceTest`, `SplitOnHitAbilityListenerTest`…).

### Nouveaux tests écrits cette nuit

| Classe | Cas | Ce qu'ils verrouillent |
|---|---|---|
| `StarterKitTierProgressionTest` | 14 | palier par défaut, remise réelle du palier 2, idempotence, **saut refusé**, palier inconnu refusé, désactivation, emplacements suivant le contenu réel (5 libres insuffisants pour 6 objets), une remise par vie à travers une montée de palier, survie à la mort / reconnexion / redémarrage, palier illisible ou supérieur à la configuration, double-clic |
| `QuestSmeltObjectiveTest` | 12 | progression unitaire et par lots, dépassement plafonné, les **trois** fours vanilla, bloc non-four, autre objet, objet obtenu autrement, attribution au seul joueur qui extrait, reconnexion, événement sans quantité |
| `QuestDiscoverWaypointObjectiveTest` | 10 | trois découvertes distinctes valident une cible de trois, **reclic sans effet**, deux waypoints du même biome comptés séparément, monde exclu, persistance après reconnexion, `NEW_ONLY` ignore l'historique, `INCLUDE_EXISTING` le compte dès l'acceptation puis suit les nouvelles découvertes, aucune progression après complétion, aucun effet sur un autre joueur |
| `ContentPackContractTest` | 20 | le schéma est du JSON valide, l'enveloppe épingle `format`/`schemaVersion`, `content` refuse une famille inconnue, **les branches d'objectifs et de récompenses sont exactement celles des descripteurs**, chaque branche exige précisément les champs obligatoires réels et refuse tout champ étranger, entiers strictement positifs, champ liste en tableau sans doublon, identifiants contraints, dépendances déclarables, gabarits par famille, exemples parsables, préfixe `tc110_` sur tout élément défini, documentation couvrant tous les types et énonçant ses limites |
| `ContentContractRoutesTest` | 6 | les trois documents exigent une session, sont servis en pièce jointe, nommés honnêtement (y compris pour une famille inconnue), et la page d'export les référence |
| `TravelBeaconServiceTest` (+1) | 1 | **un joueur seul** obtient la borne sans quitter l'instance, et aucun doublon ensuite |
| `QuestsCatalogTest` (+2) | 2 | badges étapes/objectifs distincts, dont le cas « une étape, quatre objectifs » |
| `QuestDefinitionParserTest` (+5) | 5 | parsing de `SMELT_ITEM` et `DISCOVER_WAYPOINT`, défauts du mode de comptage, **refus** d'un `count-mode` inconnu |
| `ContentPackMapperTest`, `EditorDescriptorsTest`, `ObjectiveTextTest`, `TravelCatalogTest`, `AgentActionExecutorTest`, `ContentEditorPagesTest` | +14 | aller-retour en content pack, ensemble des types verrouillé sur le moteur, libellés identiques entre source et runtime, cause des appariements manquants, champs stylés |

### Le test de non-régression de #156 a été vérifié comme tel

Un test qui passe avant **et** après un correctif ne prouve rien. L'ancien
comportement a donc été réintroduit temporairement : le test a bien **échoué**
(`aLoneHubPlayerStayingInTheInstanceStillGetsThePairedBeacon() FAILED`,
32 tests, 1 échec), le correctif a été remis, et il passe. C'est la seule
manière d'affirmer qu'il attrape le défaut.

### Échecs intermédiaires rencontrés, tous corrigés

Aucun n'a été masqué ; chacun était un vrai filet qui a fait son travail.

1. `YamlQuestEngineTest` — « expected: <9> but was: <10> ». `kit_tier2.yml`
   avait rejoint les exemples embarqués sans que le compteur soit mis à jour.
   Le fichier généré est désormais vérifié **nommément**, pour que l'oubli se
   voie au lieu d'un simple écart de compteur.
2. `ManualTestQuestPackTest` — le pack de tests manuels exige **une quête par
   type d'objectif**. Deux fois de suite (`SMELT_ITEM` puis
   `DISCOVER_WAYPOINT`), ce filet a attrapé le fichier manquant, invisible pour
   les tests ciblés.
3. `BundledExamplesDriftTest` — le panel garde une copie de la liste des
   exemples embarqués ; `kit_tier2` y manquait, donc la suppression de cette
   quête n'aurait pas été signalée comme réapparaissant au redéploiement.
4. `EditorDescriptorsTest` — deux listes blanches volontaires (noms de champs
   connus du moteur, sources de liste autorisées) refusaient `worlds`,
   `count-mode` et le vocabulaire fixe du mode de comptage. Ce sont exactement
   les garde-fous qui empêchent d'inventer un champ : ils ont été **étendus
   explicitement**, pas contournés.
5. `ContentEditorPagesTest` / `StoryEditorPassTest` — mes champs stylés
   émettaient l'attribut HTML `required`, que ces formulaires interdisent : ils
   sont `novalidate` et portent des boutons de brouillon qui doivent pouvoir
   être soumis sur un formulaire incomplet. Le composant distingue désormais le
   **marqueur visuel** « * » de l'**attribut HTML**.
6. `QuestSmeltObjectiveTest.anEventWithoutAnyItemIsIgnored` —
   `IllegalArgumentException: amount must be greater than 0`. **Mon test était
   faux**, pas le code : Bukkit refuse un `ItemStack` de quantité 0 alors que
   l'événement, lui, peut annoncer 0. Le helper a été scindé.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — rien de ce qui suit n'a été testé en jeu ni au
navigateur. Aucune issue n'est fermée.

### TC-258 — #218, paliers du kit de départ

1. Joueur neuf : `/rpgadmin kit status <joueur>` → palier **1**.
2. Mourir, revenir, demander le kit au Guide → **4 outils en bois**, une seule
   fois par vie (redemander ne donne rien).
3. Accepter `rpgquest:kit_tier2` auprès du Guide ; vérifier que le journal
   affiche les **quatre** remises (bâton, 2 pierres, 4 cuirs, 3 graines).
4. Remettre les matériaux **en plusieurs visites** : vérifier que le déjà-remis
   est conservé, y compris après une mort entre deux visites.
5. Une seule interaction doit tout remettre d'un coup quand tout est en
   inventaire.
6. Quête terminée → `/rpgadmin kit status` → palier **2**.
7. Mourir, demander le kit → **épée en pierre + 3 outils bois + bottes en cuir
   + 1 pain** (six objets).
8. Avec **5 emplacements libres seulement** : le refus doit annoncer **6**
   emplacements nécessaires, pas 4.
9. `/rpgadmin kit grant-tier <joueur> 4` → refus **saut de palier**.
10. `/rpgadmin kit grant-tier <joueur> 9` → refus **palier non défini**.
11. Rejouer la quête (si rendue répétable) → aucun nouveau cadeau.
12. Redémarrer le serveur → le palier est conservé.

### TC-259 — #141, objectif `SMELT_ITEM`

1. Copier `docs/manual-tests/quests/test_smelt_item.yml` côté serveur,
   `/quest admin reload`, accepter la quête.
2. Four + combustible + **cactus** → retirer soi-même la teinture verte :
   le compteur avance.
3. Shift-clic sur le résultat (plusieurs objets d'un coup) → le compteur avance
   **d'autant**, sans dépasser la quantité demandée.
4. Haut fourneau et fumoir comptent aussi.
5. `/give` de `GREEN_DYE` → **aucune** progression.
6. Entonnoir sous le four → **aucune** progression.
7. Deux joueurs, un seul four : seul celui qui **retire** progresse.
8. Se déconnecter / revenir → compteur conservé.
9. Supprimer la quête de test ensuite.

### TC-260 — #185, objectif `DISCOVER_WAYPOINT`

1. Copier `docs/manual-tests/quests/test_discover_waypoint.yml`,
   `/quest admin reload`, accepter la quête.
2. **Passer à côté** d'un waypoint inconnu → aucune progression.
3. **Se téléporter** vers un waypoint inconnu depuis une borne → aucune
   progression.
4. Cliquer le bouton d'un waypoint **inconnu** → 1/2.
5. **Recliquer le même** → message « déjà découvert » mais compteur **inchangé**.
6. Découvrir un second waypoint, **du même biome** si possible → 2/2, quête
   terminée (deux waypoints d'un même biome comptent séparément).
7. Vérifier que le libellé du journal annonce la **portée** (« tous mondes »)
   et la **règle** (« nouvelles découvertes »).
8. Variante filtre : ajouter `worlds: [world_hub]` → une découverte dans le
   Wild ne fait rien avancer.
9. Variante cumulative : `count-mode: INCLUDE_EXISTING` sur un joueur ayant
   déjà des découvertes → la progression part du total déjà acquis.
10. Redémarrer → compteur conservé ; aucune découverte perdue.
11. Supprimer la quête de test ensuite.

### TC-261 — #156, appariement des bornes du Hub

1. Avant tout : `/rpgadmin travel diagnose world_hub` → noter le nombre
   d'instances sans borne (**9 attendues** avant correction).
2. Entrer **une seule fois** dans une instance de biome du Hub **jamais
   visitée** et y rester une dizaine de secondes → une borne doit apparaître
   **pendant la même visite** (c'était impossible avant).
3. Repasser dans les 9 instances connues sans borne → chacune doit s'apparier
   au fil des passages.
4. Vérifier qu'une borne apparue est bien **distincte** du waypoint (6 à 16
   blocs), posée sur une colonne accessible, et que son bouton ouvre le menu de
   voyage.
5. Vérifier qu'**aucun doublon** n'apparaît en faisant des allers-retours
   rapides.
6. `/rpgadmin travel diagnose` → pour toute instance restante, l'état doit dire
   *jamais tenté depuis le démarrage*, *appariement en cours*, ou
   *N essai(s), candidats refusés* — plus jamais « jamais tenté/en cours ».
7. Panel `/travel` → section « Appariements manquants dans le Hub » : causes,
   distance à la borne la plus proche, délai avant réessai, et la mention que
   les compteurs repartent de zéro après un redémarrage.
8. Vérifier qu'aucune borne n'apparaît dans le **Wild**.

### TC-262 — #110, contrat de contenu (navigateur, sans Minecraft)

1. `/content/export` → la carte « Contrat de contenu » est présente.
2. Télécharger le **schéma** → JSON valide, `format` et `schemaVersion` en
   constantes, **10** branches d'objectifs et **5** de récompenses.
3. Télécharger le **gabarit « pack complet »** → les quatre familles, et la
   liste commentée des 10 objectifs et 5 récompenses en bas.
4. Télécharger un **gabarit par famille** → le nom du fichier correspond.
5. `?family=inventee` → gabarit complet servi, nom de fichier
   `lodyquests-template-content.yml`.
6. Télécharger le **contrat rédigé** → les sections attendues, l'exemple
   minimal et l'exemple complet `tc110_`, la limite sur les dialogues énoncée.
7. **Essai réel avec une IA** : coller le contrat, demander une mini-campagne,
   vérifier que le YAML produit respecte les identifiants et ne contient aucun
   type inventé.
8. Les trois routes sans session → redirection vers `/login`.
9. Centre d'aide `/docs` → la fiche « Content packs » apparaît et s'ouvre.

### TC-263 — #195, texte stylé partout (navigateur, sans Minecraft)

1. `/quests/new` → **Titre** et **Description** offrent palette, cases de style
   et aperçu ; aucune balise à écrire.
2. Saisir un titre coloré, enregistrer, rouvrir → la couleur est conservée et
   le mode guidé est prérempli.
3. Ouvrir une quête dont le titre est **multi-styles** → mode avancé, texte
   intact, avertissement affiché, bascule guidée seulement sur geste explicite.
4. `/stories/new` → **Nom affiché** idem.
5. `/dialogues/new` → **Réplique de départ** idem, et le select « Couleur du
   texte » a **disparu**.
6. Enregistrer un dialogue depuis le nouveau formulaire → le YAML produit est
   correct et la réplique s'affiche bien en jeu.
7. Boutons d'action de brouillon (ajouter une étape, changer un type) sur un
   formulaire incomplet → ils fonctionnent toujours, aucune bulle native
   bloquante.
8. Avec JavaScript désactivé → les champs restent visibles et éditables.

### Toujours en attente des lots précédents

- **TC-257** (#123) : à dérouler entièrement — c'est le test qui a motivé les
  deux sessions précédentes.
- **TC-256** (#161/#24), **TC-255** (#165), **TC-224** (#153/#156),
  **TC-239/TC-240** (#172/#195) : voir `docs/MANUAL_TEST_PLAN.md`.

## Résultat attendu

- Le kit de départ **progresse** : le Guide rend un kit meilleur après la quête
  de palier 2, et aucun palier ne peut être sauté.
- Deux nouveaux types d'objectifs utilisables depuis le Control Panel comme
  dans un YAML écrit à la main : **cuire** un objet, **découvrir** des
  waypoints.
- Les instances de biome du Hub obtiennent leur borne **pendant la visite en
  cours**, et toute instance restée sans borne est expliquée, pas seulement
  listée.
- Une IA peut produire un content pack valide à partir d'un seul document
  téléchargeable, sans accès au serveur ni au code.
- Plus aucun champ de texte destiné aux joueurs n'impose d'écrire une balise
  MiniMessage, et aucun texte multi-styles existant n'est dégradé.

## Reset / retour à l'état initial

- **#218** : `/rpgadmin player resetnew <joueur> confirm` remet le palier à 1
  (c'est une variable joueur ordinaire). Pour désactiver la progression :
  `starter-tool-kit.enabled: false`, ou ne laisser qu'un seul palier dans
  `tiers:`.
- **#141 / #185** : supprimer les quêtes de test
  (`test_smelt_item.yml`, `test_discover_waypoint.yml`) de
  `plugins/RPGQuest/quests/` puis `/quest admin reload`. Les compteurs
  d'objectifs se réinitialisent avec `/quest admin reset <joueur> <id>`.
  **Aucune découverte de waypoint n'est jamais supprimée** — ni par le code, ni
  comme procédure de reset.
- **#156** : `travel.beacon.hub-generation.enabled: false` coupe l'appariement
  sans toucher à la génération des waypoints. Les bornes déjà posées restent ;
  `/rpgadmin travel beacon` permet de les gérer.
- **#110 / #195** : purement Control Panel, sans état. Un rollback de
  l'application suffit (`scripts/plugadmin/rollback.sh app`).

## Déploiement VeryGames

Deux déploiements **distincts**, par deux scripts différents — c'est la cause
exacte de l'oubli du lot #123, où seul le serveur Minecraft avait été mis à jour.

Les deux ont été lancés depuis le **worktree Git propre**
`/srv/rpgquest/worktree-nuit`, détaché sur le commit poussé. L'arbre de travail
habituel contient des fichiers de contenu modifiés par le propriétaire qui font
échouer `CrystalHuntIntegrationTest` et bloqueraient le script de déploiement.

### À transférer

| Cible | Fichier | Pourquoi |
|---|---|---|
| Minecraft | `rpgquest-0.1.0-SNAPSHOT.jar` | #218, #141, #156, #185 sont côté plugin |
| Minecraft | `RPGQuest/dialogues/guide.yml` | **obligatoire** — un redéploiement de JAR ne met jamais à jour un dialogue déjà présent, et le Guide a besoin de la branche de remise pour que la quête de palier 2 soit jouable |
| AWS | distribution `control-panel` | #110, #195, la section d'appariements de #156 et le descripteur de #185 sont côté panel |

La version en ligne de `guide.yml` a été **comparée avant transfert** :
**0 ligne supprimée, 49 ajoutées** — la seule différence était l'absence de la
branche de remise, donc aucune personnalisation du propriétaire n'est perdue.

### Ne PAS transférer/altérer

- `config.yml` — la section `starter-tool-kit.tiers` est ajoutée **au
  démarrage** par `ConfigFileCompleter`, qui ne copie que les clés absentes,
  n'écrase jamais une valeur existante et écrit un `.bak` avant de réécrire.
  Vérifié dans le code plutôt que supposé. Réserve honnête : les
  **commentaires** du bloc ajouté ne sont pas reportés (les valeurs le sont) ;
  ceux des clés déjà présentes sont conservés.
- `quests/kit_tier2.yml` — fait partie des exemples embarqués du JAR, **semé
  automatiquement** car absent du serveur (16 → 17 quêtes).
- `data.db`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les autres
  plugins et les mondes — jamais touchés, le script les refuse explicitement.

### Redémarrage requis

**Oui**, pour le serveur Minecraft : le JAR change. Fait via
`scripts/verygames-restart.sh` (stop RCON, VeryGames relance). **0 joueur
connecté** au moment du redémarrage (vérifié par RCON `list` avant), donc aucun
avertissement en jeu à envoyer et aucune session interrompue.

Le Control Panel redémarre son propre service (`systemctl restart plugadmin`),
sans rapport avec Minecraft.

### Migration automatique

Aucune migration de schéma, aucun changement de base. Seules deux écritures
automatiques au démarrage, toutes deux idempotentes et déjà en service depuis
plusieurs lots : complétion de `config.yml` et semis de la quête embarquée
manquante.

### Déploiement réellement effectué

**2026-10-08, 03:31–03:33 (heure locale), commit `b0c0b8f`.** `DEPLOY_EXIT=0` et
`RESTART_EXIT=0` — ce sont les seules sorties qui prouvent un succès.

| Élément | Valeur |
|---|---|
| JAR déployé | 1 985 816 o, SHA-256 `acc195411336ac6d…` (relu en ligne : identique au local) |
| JAR précédent, sauvegardé | `rpgquest-20261008T013149Z-predeploy.jar`, 1 954 747 o — celui du lot #123 |
| `guide.yml` | 6 571 → 8 653 o ; backup `extra-20261008T013149Z/` (sha256 `4b86a69d…`) |
| Control Panel | nouveau JAR 1 188 383 o (contre 1 161 883) ; release précédente en `/opt/plugadmin/releases/20261008-033256/` |

Le nouveau JAR est **plus gros** que le backup (+31 Ko), cohérent avec les
classes ajoutées — un backup plus gros que le nouveau JAR aurait été un signal
d'arrêt.

### Validation après déploiement

| Vérification | Résultat |
|---|---|
| `/plugins` | **5 plugins, tous verts** (Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit) |
| `/rpgquest version` | `v0.1.0-SNAPSHOT` |
| `/quest admin validate` | **17 quête(s), 0 erreur(s)** — contre 16 avant, donc `kit_tier2.yml` a bien été semé |
| `config.yml` du serveur, relu après redémarrage | section `starter-tool-kit.tiers` présente avec les **deux** paliers, ancien `items:` intact |
| `/rpgadmin kit` (console) | affiche son aide — la branche d'administration de #218 est active |
| Panel `/health` | `{"panel":"ONLINE"}` |
| Panel, trois nouvelles routes | **303 vers /login** — elles existent et sont protégées, et n'existaient pas avant ce déploiement |
| **Bytecode réellement installé** | `ContentPackSchema`/`ContentPackTemplates` présents, `DISCOVER_WAYPOINT` dans `Descriptors`, libellés « étape(s) / objectif(s) » dans `AgentPages`, fiche `content-packs.md` embarquée |

Cette dernière vérification est faite **parce que** le lot #123 a été rapporté
déployé alors que seul le serveur Minecraft l'avait été : aucun contrôle côté
Minecraft ne peut révéler un panel oublié. Le panel se déploie par un script
distinct, et la seule preuve fiable est d'inspecter l'artefact servi.

Le script du panel a de nouveau rapporté un faux `/health KO` : il sonde le port
environ 2 secondes après le redémarrage, avant que la JVM ne l'ait lié. Vérifié
manuellement ensuite. Aucun rollback.

**Non vérifié, volontairement** : `/rpgadmin travel diagnose` exige un **joueur
en jeu** (position requise, console refusée) ; le même relevé est consultable au
navigateur sur `/travel`. Tout le comportement en jeu relève de **TC-258 à
TC-263**.

## Rollback

- **Minecraft** : `scripts/rollback-verygames.sh` restaure le JAR sauvegardé
  (`*.predeploy-<horodatage>`) ; `guide.yml` a aussi son backup daté.
  ⚠️ Un rollback du JAR **antérieur à #123** casserait `guide.yml`, qui utilise
  `HAS_PENDING_DELIVERY` et `DELIVER_QUEST_ITEMS` — restaurer alors les deux
  ensemble.
- **`config.yml`** : `.bak` écrit par le plugin avant complétion.
- **Quête `kit_tier2.yml`** : la supprimer puis `/quest admin reload`. Elle sera
  **re-semée** au prochain démarrage (exemple embarqué) ; pour la désactiver
  durablement, retirer le palier 2 de `starter-tool-kit.tiers`.
- **Control Panel** : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`.
- **Données joueur** : aucune touchée, rien à annuler.

## Logs / diagnostic

- `/rpgadmin travel diagnose [monde]` — comptes, instances du Hub sans borne
  **avec leur cause**, structures inaccessibles ou abîmées.
- Panel `/travel` — mêmes données en lecture seule, section « Appariements
  manquants dans le Hub ».
- `/rpgadmin kit status <joueur>` — palier atteint ; `grant-tier` journalise
  son refus et son motif.
- Journal serveur : `Borne de voyage « … » appariée en …` à chaque appariement,
  et `Aucun emplacement de borne trouvé pour … (essai N), nouvel essai dans X s`
  en cas d'échec borné.

## Documentation mise à jour

| Fichier | Contenu |
|---|---|
| `docs/RPGQUEST_BIBLE.md` | paliers du kit (#218) ; tableau des objectifs passé à **10 types** avec `SMELT_ITEM` et `DISCOVER_WAYPOINT` et leurs limites ; section dédiée à la découverte de waypoints ; densité des bornes du Hub (#156) avec les chiffres mesurés ; contrat de contenu (#110) ; **audit complet** des champs de texte stylé (#195) |
| `docs/current_state.md` | une entrée par lot livré, 10 types d'objectifs |
| `docs/CONTENT_PACK.md` | nouvelle section « Contrat machine-readable (#110) » |
| `docs/MANUAL_TEST_PLAN.md` | pack de tests manuels passé à **10 quêtes** ; procédures **TC-258 à TC-263** et leurs lignes de recette |
| `docs-site/quests.html` | lignes `SMELT_ITEM` et `DISCOVER_WAYPOINT`, « Dix types au total » |
| `control-panel/.../docs/content-packs.md` | fiche du centre d'aide, avec la marche à suivre pour faire générer un pack par une IA et ce qu'il faut relire |
| `.ai/ROADMAP.md` | entrée de session, avec ce qui n'a **pas** été livré et pourquoi |
| `docs/deployment/SERVER_CHANGELOG.md` | entrée de déploiement |

## Limitations / travail restant

### Non livré, et pourquoi

- **#109 — import sécurisé de content pack.** Le pipeline demandé
  (IMPORT → ANALYSE → VALIDATION → DIFF → BROUILLON → CONFIRMATION →
  ENREGISTREMENT SOURCE, avec les états NEW / MODIFIED / UNCHANGED / CONFLICT /
  SKIPPED, sans jamais d'écriture aveugle ni d'écrasement silencieux)
  représente à lui seul plusieurs heures avec sa couverture de tests. **Un
  import à moitié construit est plus dangereux que pas d'import du tout** :
  c'est le seul morceau du programme qui écrit dans la source. Je ne l'ai pas
  commencé plutôt que de le laisser inachevé. Le contrat #110 livré cette nuit
  est précisément la brique dont il a besoin : schéma, dépendances déclarables,
  et un vocabulaire de types garanti conforme au moteur.
- **LOT 4 — atelier IA et configuration de fournisseurs.** Même raison, plus
  une raison de sécurité : ce lot manipule des **clés d'API**, avec des
  exigences précises (jamais exposées au navigateur après enregistrement,
  jamais journalisées, jamais dans Git, stockage serveur sécurisé, affichage
  masqué, CSRF, audit sans révéler le secret, appels **uniquement** par le
  backend). Livrer cette surface à moitié serait le pire choix possible. À
  noter qu'une partie de sa valeur est **déjà disponible** sans aucun appel
  d'API : le contrat rédigé de #110 se colle tel quel dans ChatGPT, Claude ou
  Gemini pour obtenir un pack conforme.

### Limites de ce qui a été livré

- **Dialogues dans le schéma #110** : seul le squelette est contraint. Le
  vocabulaire des actions et conditions vit dans les énumérations du plugin et
  n'est aujourd'hui déclaré nulle part que le Control Panel puisse dériver.
  J'ai préféré laisser ces tableaux libres, et l'écrire noir sur blanc dans le
  contrat, plutôt que recopier une liste qui divergerait en silence. Le rendre
  dérivable (descripteurs de dialogue côté panel, ou relevé d'agent) est la
  suite directe.
- **Paliers 3 à 5 du kit (#218)** : l'architecture les accepte, leur contenu
  n'est **pas** inventé — c'est une décision de gameplay non spécifiée.
- **`DISCOVER_WAYPOINT` et quêtes répétables** : en mode `NEW_ONLY`, un joueur
  ayant tout découvert ne peut pas valider un nouveau cycle. Assumé et
  documenté : le ticket interdit de supprimer des découvertes ou de débloquer
  un waypoint pour y remédier. Le mode cumulatif existe pour qui veut l'autre
  comportement.
- **#156** : les compteurs d'essai d'appariement vivent en mémoire et repartent
  de zéro à chaque redémarrage. L'affichage le dit, au lieu de laisser croire
  qu'aucun essai n'a jamais eu lieu. Par ailleurs le rattrapage se fait **au
  fil de l'exploration**, jamais par un balayage du monde : une instance jamais
  revisitée restera sans borne, et le diagnostic la liste.
- **Export de pack** : toujours plafonné à environ 56 Kio par le transport vers
  l'agent. Le découpage viendra avec #109.

### Défaut préexistant rencontré, non corrigé

`CrystalHuntIntegrationTest` échoue sur cette branche, et ce **n'est pas dû à
cette session** : le propriétaire a réécrit
`src/main/resources/quests/crystal_hunt.yml` depuis le Control Panel (la quête
est passée d'araignées/artisanat à zombies/chasse), alors que ce test
d'intégration vérifie le scénario d'origine. Le fichier est resté **non
committé et non modifié** par moi. Deux issues possibles, au choix du
propriétaire : adapter le test au nouveau contenu, ou sortir `crystal_hunt` des
exemples embarqués pour qu'il devienne du contenu libre.

Également signalé mais hors mandat lors du lot précédent, toujours vrai : le
badge d'une quête annonce « 1 objectif » pour quatre (il compte les **étapes**
avec un libellé d'objectifs), et `COBBLESTONE` est rendu « Pierre taillée » au
lieu de « Pierre ».

## Prochaine étape suggérée

1. Dérouler **TC-257** (#123), toujours jamais exécuté — c'est lui qui bloque
   la fermeture de l'issue.
2. Dérouler **TC-261** (#156) et **TC-258** (#218) : les deux lots qui changent
   quelque chose de visible en jeu.
3. Attaquer **#109** en s'appuyant sur le schéma et les dépendances déclarables
   de #110, puis le **LOT 4** (atelier IA), dans cet ordre — l'atelier n'a
   d'intérêt que si le pack produit peut être importé.
4. Trancher le cas `crystal_hunt.yml` (adapter le test, ou retirer la quête des
   exemples embarqués).
