# Format d'une quête

Un fichier par quête, dans `plugins/RPGQuest/quests/*.yml`. Voir
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (section `quest`) pour le
détail de la validation. Exemple complet :

```yaml
id: rpgquest:woodcutters_request   # namespacé ; sans ':', le namespace par défaut "rpgquest" est utilisé
title: "<gold>La requête du bûcheron</gold>"       # MiniMessage ; ou une table de traductions (voir plus bas)
description: "<gray>Récolte du bois...</gray>"
category: gathering
giver: woodcutter_bob              # optionnel : id stable du PNJ donneur (cf. /rpgadmin npc tag)
repeatable: true

prerequisites:
  - rpgquest:first_steps

steps:
  - id: chop_wood
    objectives:
      - type: BREAK_BLOCK          # BREAK_BLOCK | PLACE_BLOCK | KILL_ENTITY | COLLECT_ITEM
        material: OAK_LOG          # | CRAFT_ITEM | TALK_TO_NPC | REACH_LOCATION
        amount: 20
  - id: report_to_npc
    objectives:
      - type: TALK_TO_NPC
        npc: woodcutter_bob

rewards:
  - type: EXPERIENCE               # EXPERIENCE | ITEM | VARIABLE | COMMAND | MONEY | HEART
    amount: 30
  - type: MONEY                    # crédite le portefeuille persistant (aucun objet donné)
    amount: 100
  - type: VARIABLE
    key: woodcutter_reputation
    value: "1"
  - type: COMMAND
    command: "give %player% oak_planks 16"

variables:
  wood_collected: "0"
```

## Récompense `HEART` (cœur supplémentaire)

`HEART` augmente **durablement** la vie maximale du joueur (issue #166) :
1 cœur = 2 PV, affichage natif, sans resource pack.

```yaml
rewards:
  - type: HEART          # 1 cœur, propre à cette quête
  - type: HEART
    amount: 2            # facultatif : 1 par défaut, de 1 à 512
    key: city:central_city   # facultatif : clé de droit partagée
```

- **Une seule fois par joueur et par clé de droit.** Sans `key`, la clé est
  `quest:<id de la quête>` : une quête `repeatable: true` n'accorde son cœur
  qu'au premier passage. Avec une `key` partagée (ex. une ville), toutes les
  quêtes portant cette clé n'accordent **qu'un** cœur au total — renommer,
  remplacer ou déplacer le PNJ ne rouvre jamais le droit.
- **Persistant par UUID** (table `player_heart_grants`, schéma V36), écrit
  dans la **même transaction** que la complétion : ni perte, ni doublon sur
  double clic, retry, reconnexion, mort ou redémarrage.
- **Recalculé, jamais cumulé** : le bonus est un modificateur unique
  `rpgquest:quest_heart_bonus` (`ADD_NUMBER`) sur `max_health`, recalculé
  depuis la base à chaque connexion et réappliqué à chaque réapparition. Les
  autres sources de vie maximum (équipement, effets, autres plugins) ne sont
  jamais touchées ; la valeur de base reste 20.
- **Pas une guérison** : seuls les PV nouvellement ajoutés sont remplis.
- **Plafond gameplay** : `progression.max-bonus-hearts` dans `config.yml`
  (`0` = aucun, défaut). Un droit gagné au-delà reste acquis et s'active si le
  plafond est relevé. Plafond technique : `attribute.maxHealth.max` de
  `spigot.yml` (1024 PV par défaut) — ce n'est pas une garantie de lisibilité
  du HUD au-delà de quelques rangées de cœurs.
- **Reset joueur** (`/rpgadmin player reset…`) : les cœurs **sont conservés**,
  comme le portefeuille — un reset n'est jamais une façon de regagner un droit.

## Récompense `MONEY`

`MONEY` crédite le **portefeuille persistant** du joueur : le même solde que
`/money`, les marchands, le marché et le Control Panel. Elle ne donne
**aucun objet** — la monnaie RPGQuest est un solde, et aucun objet
d'inventaire n'est jamais reconnu comme de l'argent.

`amount:` est un entier **strictement positif**. Le moteur n'impose aucun
plafond : le montant est une décision d'équilibrage, pas une règle technique.
L'éditeur du Control Panel se contente d'**avertir** au-delà d'un million,
pour attraper la faute de frappe à six zéros.

Le crédit a lieu **au plus une fois par complétion**, et une récompense due
n'est jamais perdue. À la remise, la complétion de la quête **et** une ligne
de dette par récompense monétaire sont écrites dans la **même transaction**
(table `quest_reward_grants`) ; le paiement fait ensuite passer cette ligne
`PENDING → PAID` **avec** le crédit du portefeuille et la ligne de journal,
toujours dans une seule transaction. Conséquences :

- un arrêt brutal **avant** le paiement laisse une dette identifiable
  (joueur, quête, occurrence, montant, état) : elle est **reprise** à la
  prochaine connexion du joueur, avec la **même** identité de paiement, donc
  sans risque de payer deux fois ;
- une quête portant **plusieurs** récompenses `MONEY` les paie toutes :
  chacune a sa propre identité (`<jeton>#<index>`) ;
- le montant payé est celui **enregistré à la complétion**, jamais celui de
  la définition courante : rééditer la quête ne change pas une dette déjà née.

Une quête `repeatable: true` ouvre une nouvelle occurrence à chaque reprise et
se paie donc à nouveau. Chaque crédit laisse une ligne `transactions` de type
`QUEST_REWARD` dont le contexte cite la quête et l'identité de paiement.

Les reprises sont **bornées** : une relecture par chargement de joueur, 20
dettes au plus, et au-delà de 5 échecs la dette attend une action
d'administrateur (PlugAdmin → fiche joueur → « Récompenses en attente »)
plutôt que d'être réessayée indéfiniment.

Le message de gain arrive **séparément** du résumé de fin de quête, un court
instant plus tard, et seulement après confirmation de la base : il n'est
jamais affiché pour un gain qui n'a pas eu lieu. Un échec de persistance est
dit explicitement au joueur.

## Champ `giver` (optionnel)

`giver:` déclare le PNJ **donneur** de la quête — l'id logique stable posé via
`/rpgadmin npc tag <id>` (même convention que `objectives[].npc`), jamais le
nom affiché. Purement informatif : il n'affecte ni la progression ni les
conditions d'acceptation ; il est exposé tel quel dans le catalogue du
Control Panel (`quest.list` → `giverId`). Absent = aucun donneur déclaré ;
présent mais vide = erreur de chargement.

## Objectif `TALK_TO_NPC`

`npc:` (ex. `woodcutter_bob` ci-dessus) est l'identifiant stable attribué à
une entité via `/rpgadmin npc tag <id>` (voir `docs/ARCHITECTURE.md`,
package `npc`) — **jamais** le nom personnalisé affiché au-dessus d'elle.
Le nom affiché reste purement cosmétique et peut être renommé librement sans
casser l'objectif : marquez d'abord l'entité (`/rpgadmin npc tag
woodcutter_bob`), puis nommez-la comme vous le souhaitez à l'enclume.

## Textes localisables

`title`/`description` acceptent soit un texte simple, soit une table avec
une clé `default` obligatoire :

```yaml
title:
  default: "<gold>Premiers pas</gold>"
  en: "<gold>First Steps</gold>"
```

Un PNJ se cite par son **identifiant**, jamais par son nom écrit en dur :
`%npc_name:guide%` affiche le nom actuel du PNJ `guide` (« Elias ») dans le
journal et les offres de quête ; renommer le PNJ suffit, rien à republier
(issue #475). PNJ inconnu : le marqueur reste visible.

## Validation

-   `id`, `title`, `description`, `category`, `steps` (≥ 1) sont obligatoires.
-   `giver` est optionnel ; s'il est présent il ne peut pas être vide.
-   Chaque étape a un `id` unique dans la quête et ≥ 1 `objectives`.
-   Types d'objectif/récompense inconnus, matériaux/entités inconnus,
    nombres ≤ 0, et prérequis référençant la quête elle-même sont rejetés.
-   Un fichier invalide est rejeté seul ; les autres continuent de charger.
    `id` dupliqué entre fichiers ou prérequis introuvable après chargement
    de tous les fichiers → rejeté aussi (voir `/quest admin reload|validate`).
