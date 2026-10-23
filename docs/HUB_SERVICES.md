# Services de Hub — salles de préparation (issue #107)

Le Hub est l'endroit où le joueur **prépare son expédition** avant de partir dans le Wild :

```
Arrivée / retour au Hub -> salle des coffres -> salle de craft -> préparation -> portail Wild
```

Cette page définit les **services logiques** qu'un Hub expose. Elle ne fixe ni bâtiment, ni
coordonnées, ni plan : chaque Hub (actuel ou futur) peut avoir son architecture propre, du moment
qu'il offre les mêmes fonctions avec les mêmes règles.

## Services d'un Hub

| Service | Rôle | Source de vérité | Mise en place |
|---|---|---|---|
| **Stockage personnel** (salle des coffres) | Récupérer / déposer son équipement et ses ressources. | Coffres personnels LodyQuests `UUID + vault_id` (#104, table `personal_vaults`). Le bloc ne contient rien. | Poser des coffres **vides**, puis `/rpgadmin vault bind <vault_id>` sur chacun (#105). |
| **Craft** (salle de craft) | Fabriquer outils, armes et ressources avec les recettes **vanilla**. | Minecraft (table de craft vanilla). Aucune donnée RPGQuest. | Poser des établis (`CRAFTING_TABLE`) avec un compte constructeur du Hub, **puis les ouvrir par une exception `hub.stations.exceptions`** (fermés par défaut depuis #675, voir « Postes de travail »). |
| Plus tard : soins, marchands, artisans… | — | Systèmes dédiés (#17 marchands, #18 artisans…). | — |

Un service n'est pas un objet enregistré dans le plugin : c'est un **ensemble de blocs ordinaires**
dont le comportement découle des règles du monde Hub (`hub.world`) et, pour les coffres, du registre
des points d'accès. Aucune commande propre à #107 ; depuis #675, la salle de craft s'ouvre par une
exception de poste dans `config.yml` (`hub.stations.exceptions`).

## Salle des coffres

- Elle s'appuie **exclusivement** sur #104/#105 : aucun second stockage. Le contenu affiché est
  toujours celui du joueur qui clique ; deux joueurs sur le même coffre voient deux contenus.
- Le décor n'est pas la source de vérité : déplacer la salle = lier les nouveaux coffres puis délier
  les anciens (`/rpgadmin vault unbind`) — aucun contenu ne bouge (voir [PERSONAL_VAULTS.md](PERSONAL_VAULTS.md)).
- **Signalétique** : si plusieurs coffres sont exposés, un panneau ou un cadre au-dessus de chacun
  indique son rôle (« Coffre 1 », « Coffre 2 — à débloquer »…). La fenêtre ouverte s'intitule déjà
  « Coffre personnel <vault_id> ». Un coffre encore verrouillé (#106) affiche un message générique.
- **Multi-Hub** : exposer le même `vault_id` dans chaque Hub donne le même contenu partout.

## Salle de craft

MVP volontairement vanilla :

- établis Minecraft normaux, **fermés par défaut depuis #675** (retour du propriétaire du 22/10) et
  ouverts aux joueurs par une exception explicite (`hub.stations.exceptions`, voir « Postes de
  travail ») — par type, par bloc précis et/ou sous condition ;
- aucune recette spéciale, aucune interface custom ;
- les établis sont des blocs du Hub : **ni cassables, ni remplaçables, ni brûlables** par un joueur
  standard.

Règles réellement appliquées dans tout le monde Hub (`hub.HubWorldProtectionListener`) :

| Action d'un joueur sans droit de construction | Résultat |
|---|---|
| Clic droit sur un établi ou un autre poste de travail | **Refusé par défaut** (#675, `hub.HubStationLockListener`) — « Les postes de travail du Hub sont fermés. » ; ouvert seulement par une exception `hub.stations.exceptions` (voir « Postes de travail »). |
| Casser / poser un bloc | Refusé (règle Hub existante). |
| Vider un seau (eau, lave…) | Refusé (#107) — équivaut à poser un bloc. |
| Remplir un seau sur le décor (eau, lave, neige poudreuse) | Refusé (#107) — équivaut à casser un bloc. **Traire une vache reste permis.** |
| Allumer un feu (briquet, boule de feu…) | Refusé (#107). |
| Propagation du feu, combustion d'un bloc (établi, bois…) | Toujours annulées dans le Hub (#107), même près d'un feu posé par un constructeur. |
| Explosions | Aucun bloc détruit (règle Hub existante). |
| Ouvrir un coffre, tonneau, shulker, hopper, distributeur, dropper, crafter (bloc non lié à un coffre personnel) | Refusé (#675, `hub.HubResourceLockListener`) — « Les coffres du Hub sont fermés. » Rien n'est retiré du bloc. |
| Clic droit sur un pot décoré, une bibliothèque sculptée, une étagère | Refusé (#675). |
| Commercer avec un villageois / marchand ambulant (non Citizens) | Refusé (#675) — « Le commerce n'est pas disponible dans le Hub. » |
| Composteur, jukebox, chaudrons, pots de fleurs, coffre des épreuves | Refusé (#675) — « Ce bloc du Hub n'est pas utilisable. » Aucune exception possible. |
| Lutrin | Le livre **se lit** ; le prendre est refusé (#675). |
| Coffre de l'Ender | **Autorisé** — stockage personnel du joueur. |

Un **constructeur du Hub** (`rpgquest.build.hub.<monde>`, `rpgquest.build.hub.*` ou l'ombrelle
`rpgquest.admin.world`, voir [PERMISSIONS.md](PERMISSIONS.md)) peut toujours casser, poser, utiliser
des seaux et allumer un feu décoratif — c'est un droit de construction, pas un bypass (#35). Le feu
qu'il allume ne se propage pas et ne brûle rien.

Le droit de construction **n'ouvre pas** les conteneurs de stockage vanilla du Hub (#675) : garnir un
coffre de décor exige le nœud `rpgquest.admin.hub.resources` **pendant** une session
`/rpgadmin bypass on`. Un coffre lié par `/rpgadmin vault bind` n'est jamais concerné : son clic ouvre
toujours le coffre personnel de celui qui clique.

Limite connue : les **pistons** ne sont pas bloqués à l'échelle du monde Hub (un joueur standard ne
peut pas en poser ; un mécanisme existant est la responsabilité du constructeur). Ne pas placer
d'établi devant un piston.

## Postes de travail (#675, retour du 22/10)

Dans tout le monde Hub (`hub.world`), les postes de travail vanilla sont **fermés par défaut**
(`hub.HubStationLockListener`) : un clic droit n'ouvre rien, message gris discret (un au plus toutes
les 2 s, partagé avec les refus de commerce et de coffres). L'objet tenu en main reste utilisable ;
aucun bloc, contenu ni joueur n'est modifié ; la règle est évaluée à chaque clic, elle tient donc
après redémarrage et `/rpgadmin reload`. Wild et Claims ne sont pas concernés. Contournement admin :
`rpgquest.admin.hub.resources` **pendant** `/rpgadmin bypass on` (#35), comme pour les coffres.

### Inventaire et décision par bloc

| Bloc | Décision | Exception possible |
|---|---|---|
| Établi (`CRAFTING_TABLE`) | Fermé | Oui |
| Four, haut fourneau, fumoir (`FURNACE`, `BLAST_FURNACE`, `SMOKER`) | Fermés | Oui |
| Alambic (`BREWING_STAND`) | Fermé | Oui |
| Enclume, enclume ébréchée/endommagée (`ANVIL`) | Fermées | Oui |
| Meule (`GRINDSTONE`) | Fermée | Oui |
| Table de forgeron (`SMITHING_TABLE`) | Fermée | Oui |
| Tailleur de pierre (`STONECUTTER`) | Fermé | Oui |
| Métier à tisser (`LOOM`) | Fermé | Oui |
| Table de cartographie (`CARTOGRAPHY_TABLE`) | Fermée | Oui |
| Table d'enchantement (`ENCHANTING_TABLE`) | Fermée | Oui |
| Balise (`BEACON`) — changer l'effet de zone avec un minerai | Fermée | Oui |
| Crafter | Fermé comme conteneur (#675, lot du 21/10) : il stocke des objets | Non |
| Coffres, tonneaux, shulkers, hoppers, distributeurs, droppers, wagonnets/bateaux à coffre | Fermés (lot du 21/10) | Non |
| Composteur (poudre d'os), jukebox (disque), chaudrons (eau/lave/neige poudreuse), pots de fleurs (la fleur), coffre des épreuves (butin) | Fermés : source de ressource ou bloc qu'un seau modifierait | Non |
| Pot décoré, bibliothèque sculptée, étagères | Fermés (lot du 21/10) | Non |
| Lutrin | Lecture libre, **prise du livre refusée** | Non (rien à rouvrir) |
| Coffre de l'Ender | **Libre** — stockage personnel | — |
| Coffres personnels liés (#104/#105) | **Libres** — `storage.VaultAccessListener` ouvre le coffre du joueur avant toute règle | — |
| PNJ Citizens, boutiques (#17), ateliers d'artisan (#18), menus RPGQuest | **Libres** — ce ne sont pas des blocs vanilla | — |
| Portes, boutons, leviers, lits, cloches, panneaux | Inchangés (ni ressource, ni poste) | — |

Non couvert (hors périmètre, à signaler si besoin) : cadres et porte-armures du décor (entités).

### Exceptions explicites

Liste `hub.stations.exceptions` de `config.yml`, **vide par défaut** (aucune exception sur DEV). Elle
arrive sur un serveur existant par le complément automatique de `config.yml` (`ConfigFileCompleter`) —
ne jamais téléverser `config.yml`. Une erreur (poste inconnu, bloc hors du Hub, condition non admise…)
refuse le chargement de la configuration : une exception mal écrite n'ouvre jamais un poste.

```yaml
hub:
  stations:
    exceptions:
      - id: salle_de_craft               # identifiant stable (minuscules, chiffres, _ ou -)
        stations: [CRAFTING_TABLE, FURNACE]  # absent ou vide = tous les postes
        blocks:                          # absent ou vide = tous les blocs de ces postes du Hub
          - { x: 12, y: 64, z: -30 }     # world: facultatif, doit être hub.world
        required-level: 5                # facultatif : niveau RPG (règle #532, comme les portails)
        conditions:                      # facultatif : mêmes clés que access.conditions d'un portail (#567)
          - type: QUEST_STATE
            quest: rpgquest:guard_tier1
            state: COMPLETED
        message: "<gray>Termine Gauthier — Palier 1 pour utiliser cet établi.</gray>"
```

- Conditions admises : celles des portails (`QUEST_STATE`, `HAS_ITEM`, `HAS_PERMISSION`,
  `VARIABLE_EQUALS`, `HAS_MAIN_CLAIM`, avec `negate`), évaluées par le **même**
  `PlayerConditionEvaluator` que les dialogues et les portails — aucun second système.
- Plusieurs exceptions visent le même bloc : **une seule** suffit. Sans condition, le poste s'ouvre
  normalement. Si le verdict demande une lecture en base (état d'une quête terminée, variable), le
  clic est retenu puis le poste s'ouvre tout seul, lié au bloc, si le joueur est toujours à portée et
  n'a rien ouvert entre-temps ; une erreur de lecture refuse.
- Refus : `message` de l'exception, sinon « Ce poste du Hub ne t'est pas encore ouvert. ».

### Révision des exemptions de #107

#107 (salles de préparation) exemptait **les établis** — « utilisables par tout joueur » — et, par
extension, les fours, fumoirs, hauts fourneaux et alambics (le lot #675 du 21/10 les laissait libres
pour cette raison). Le 22/10, le propriétaire a décidé que le Hub n'est pas un atelier libre-service :
ces postes sont **désormais fermés par défaut**, comme tous les autres. Ce qui ne change pas : la
salle des coffres (#104/#105), la protection contre la casse/le feu/les seaux, et la distinction craft
vanilla ≠ artisan RPG. Pour rouvrir la salle de craft, déclarer ses établis (types ou blocs précis)
dans `hub.stations.exceptions`, avec ou sans condition. Tant qu'aucune exception n'existe, la salle de
craft du Hub est fermée : les quêtes de craft ou de cuisson se font dans le Wild ou dans son claim.

### Accès achetés (prévu, non implémenté)

Le point d'extension est l'exception elle-même : son `id` est la clé stable qu'un futur droit acheté
désignera. Le modèle prévu n'ajoute **aucun** type de condition : un achat (prix centralisés #336) ou
un droit à récompense (#422) posera une variable joueur, par exemple `HUB_STATION_SALLE_DE_CRAFT=true`,
et l'exception l'exigera par une condition `VARIABLE_EQUALS`. Aucun achat n'existe aujourd'hui.

## Craft vanilla ≠ artisan RPG

| | Salle de craft (#107) | Artisan PNJ (#18) |
|---|---|---|
| Interface | Table de craft vanilla | Vitrine d'atelier RPGQuest (catalogue `type: CRAFT`) |
| Recettes | Minecraft standard | Prix `CRAFT` de Prix et coûts (ingrédients et/ou pièces) |
| Économie | Aucune | Débit LodyCoins audité (`ARTISAN_CRAFT`), voir #336 |
| Données | Aucune | `merchants/*.yml`, `prices.yml` |

Les deux peuvent cohabiter dans un même Hub sans interaction.

## Créer la salle d'un nouveau Hub (checklist admin)

1. Avec un compte constructeur du Hub : construire la salle, poser des coffres **vides** et des établis.
2. Sur chaque coffre : `/rpgadmin vault bind vault_01` (ou un autre `vault_id`), vérifier par
   `/rpgadmin vault info`.
3. Ajouter la signalétique (panneaux).
4. Ouvrir les établis de la salle aux joueurs : une exception `hub.stations.exceptions` avec
   `stations: [CRAFTING_TABLE]` et les `blocks` de la salle (voir « Postes de travail »), puis
   `/rpgadmin reload`.
5. Avec un compte joueur **sans** droit de construction : ouvrir un coffre, utiliser un établi, essayer
   de casser l'établi (refusé), puis rejoindre le portail Wild. Procédure détaillée : TC-355 de
   [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md).

## Tests

`HubWorldProtectionListenerTest` (section « Salle de craft ») : établi utilisable sous une zone aux
drapeaux par défaut (témoin : un coffre public y reste fermé), établi incassable, seaux vidés/remplis
refusés sauf traite, constructeur autorisé, feu (propagation, briquet, combustion) refusé, autre monde
jamais touché. Validation en jeu : `PENDING MANUAL VALIDATION` (TC-355).

`HubStationLockListenerTest` (#675) : chaque poste fermé par défaut avec un seul message, objet en
main préservé, Wild et blocs ordinaires inchangés, blocs-ressources fermés même avec une exception
générale, exception par type, par bloc, conditions immédiates (message de l'exception), niveau RPG,
verdict asynchrone (clic retenu puis une seule réouverture ; refus, erreur ou joueur parti : rien
n'est ouvert), bypass seulement en session. `HubStationExceptionsYamlTest` : liste vide livrée,
lecture complète, refus lisibles. Validation en jeu : `PENDING MANUAL VALIDATION` (TC-401).
