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
| **Craft** (salle de craft) | Fabriquer outils, armes et ressources avec les recettes **vanilla**. | Minecraft (table de craft vanilla). Aucune donnée RPGQuest. | Poser des établis (`CRAFTING_TABLE`) avec un compte constructeur du Hub. |
| Plus tard : soins, marchands, artisans… | — | Systèmes dédiés (#17 marchands, #18 artisans…). | — |

Un service n'est pas un objet enregistré dans le plugin : c'est un **ensemble de blocs ordinaires**
dont le comportement découle des règles du monde Hub (`hub.world`) et, pour les coffres, du registre
des points d'accès. Aucune commande ni configuration propre à #107.

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

- établis Minecraft normaux, **utilisables par tout joueur** (clic droit) ;
- aucune recette spéciale, aucune interface custom ;
- les établis sont des blocs du Hub : **ni cassables, ni remplaçables, ni brûlables** par un joueur
  standard.

Règles réellement appliquées dans tout le monde Hub (`hub.HubWorldProtectionListener`) :

| Action d'un joueur sans droit de construction | Résultat |
|---|---|
| Clic droit sur un établi | **Autorisé** — aucune règle du Hub n'annule l'interaction. Une zone protégée (`/rpgadmin zone`, drapeau `public-containers: false`) ne bloque pas non plus un établi : ce n'est pas un conteneur. |
| Casser / poser un bloc | Refusé (règle Hub existante). |
| Vider un seau (eau, lave…) | Refusé (#107) — équivaut à poser un bloc. |
| Remplir un seau sur le décor (eau, lave, neige poudreuse) | Refusé (#107) — équivaut à casser un bloc. **Traire une vache reste permis.** |
| Allumer un feu (briquet, boule de feu…) | Refusé (#107). |
| Propagation du feu, combustion d'un bloc (établi, bois…) | Toujours annulées dans le Hub (#107), même près d'un feu posé par un constructeur. |
| Explosions | Aucun bloc détruit (règle Hub existante). |
| Ouvrir un coffre, tonneau, shulker, hopper, distributeur, dropper, crafter (bloc non lié à un coffre personnel) | Refusé (#675, `hub.HubResourceLockListener`) — « Les coffres du Hub sont fermés. » Rien n'est retiré du bloc. |
| Clic droit sur un pot décoré, une bibliothèque sculptée, une étagère | Refusé (#675). |
| Commercer avec un villageois / marchand ambulant (non Citizens) | Refusé (#675) — « Le commerce n'est pas disponible dans le Hub. » |
| Four, fumoir, haut fourneau, alambic, coffre de l'Ender | **Autorisé** — blocs utilitaires / stockage personnel, non concernés par #675. |

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
4. Avec un compte joueur **sans** droit de construction : ouvrir un coffre, utiliser un établi, essayer
   de casser l'établi (refusé), puis rejoindre le portail Wild. Procédure détaillée : TC-355 de
   [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md).

## Tests

`HubWorldProtectionListenerTest` (section « Salle de craft ») : établi utilisable sous une zone aux
drapeaux par défaut (témoin : un coffre public y reste fermé), établi incassable, seaux vidés/remplis
refusés sauf traite, constructeur autorisé, feu (propagation, briquet, combustion) refusé, autre monde
jamais touché. Validation en jeu : `PENDING MANUAL VALIDATION` (TC-355).
