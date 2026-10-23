---
title: Lancer, compléter, réinitialiser une quête / story
category: Quêtes / Stories
tags: [quest, story, quete, complete, start, reset, advance, variable, ids]
order: 1
---

# Quêtes et stories — commandes admin

Deux familles :
- `/quest …` : commandes joueur + `/quest admin …` ;
- `/rpgadmin quest …` et `/rpgadmin story …` : outils de test, **utilisables depuis la console**,
  ciblant un joueur passé en argument.

## Quêtes

```
/rpgadmin quest start <joueur> <questId> [force]
/rpgadmin quest complete <joueur> <questId>
/rpgadmin quest reset <joueur> <questId>
```

Équivalents joueur / `/quest admin` :

```
/quest complete <questId>              # force la fin de SA quête (rpgquest.admin)
/quest admin reset <joueur> <questId|all>
/quest admin reload                    # recharge les définitions de quêtes
/quest admin validate
```

- `start … force` ignore les prérequis.
- `complete` applique les récompenses **une seule fois**. Une récompense **monétaire** (`MONEY`,
  issue #16) est en plus idempotente **en base** : la même complétion ne peut pas être payée deux
  fois, même en cas de double clic ou de retry.
- Une récompense `MONEY` crédite le **portefeuille persistant** (le solde lu par le bloc
  « Monnaie » de la fiche joueur, par `/money` et par les marchands). Elle ne donne **aucun
  objet** : aucun objet d'inventaire n'est jamais compté comme de la monnaie. Le montant est une
  décision d'équilibrage — rien ne le plafonne côté serveur, l'éditeur se contente d'avertir
  au-delà d'un million.
- Une récompense **« Cœur supplémentaire »** (`HEART`, issue #166) ajoute durablement des cœurs
  de vie maximum (1 par défaut). Elle n'est accordée qu'**une fois par joueur et par clé de
  droit** : laisser la clé vide la rend propre à la quête (même répétable) ; une même clé sur
  plusieurs quêtes (ex. `city:central_city`) n'accorde qu'un cœur pour elles toutes. Les cœurs
  survivent à la mort, à la reconnexion, au redémarrage **et au reset joueur**. Au-delà de 5 cœurs
  d'un coup, l'éditeur avertit ; au-delà de 512, il refuse.
- **Un montant saisi dans l'éditeur n'agit pas tout seul** : la quête doit être *publiée* sur le
  serveur, puis *rechargée* (voir « Rechargement du contenu »). Tant qu'elle n'est que dans la
  source, le joueur ne sera pas payé — et aucune erreur ne s'affichera en jeu.

## Récompenses monétaires en attente

Si une récompense monétaire n'a pas pu être créditée (arrêt du serveur, panne de base), elle **n'est
pas perdue** : elle reste enregistrée avec son joueur, sa quête, son occurrence et son **montant
figé à la complétion**. Elle est reprise automatiquement à la prochaine connexion du joueur, avec la
**même identité de paiement** — un double paiement est donc impossible.

Fiche joueur → **Récompenses en attente** :

- **Lire les récompenses en attente** interroge le serveur et affiche l'état **réel** : « En
  attente », ou « En échec (N tentative(s)) » **avec son motif**.
- **Reprendre le paiement** paie le montant enregistré. Il n'y a **aucun montant à saisir** — donc
  aucun à inventer. Rejouer la reprise ne crédite rien une seconde fois.
- **Marquer réglée à la main** s'utilise **après** avoir compensé le joueur par un crédit manuel.
  Elle ne touche **aucun** solde : elle empêche seulement cette même récompense d'être payée une
  seconde fois.

**Ne confondez pas les deux gestes.** Un crédit manuel est une compensation libre et **ne règle
aucune dette** : si vous compensez sans marquer la récompense réglée, elle resterait payable.

**Limite** : seules les complétions **postérieures** à la mise à jour qui a introduit ce mécanisme
peuvent apparaître. Une récompense perdue avant n'a laissé aucune trace exploitable ; aucun montant
n'est deviné, et rien n'est payé rétroactivement.
- `reset` remet la quête à zéro (état + compteurs d'objectifs) ; **n'annule pas** les récompenses
  déjà données ; utilisable joueur hors ligne.

## Stories

```
/rpgadmin story info <joueur>
/rpgadmin story start <joueur> <storyId>
/rpgadmin story advance <joueur> <storyId>
/rpgadmin story complete <joueur> <storyId>
/rpgadmin story reset <joueur> <storyId|all>
/rpgadmin story resetwithquests <joueur> <storyId|all>
```

- Une story `ACTIVE` **avance toute seule** quand sa quête courante se complète : `advance` /
  `complete` sont des raccourcis de test, pas le fonctionnement normal.
- `resetwithquests` remet aussi à zéro les quêtes qui composent la story.

## Variables joueur

```
/rpgadmin player variable get <joueur> <clé>
/rpgadmin player variable set <joueur> <clé> <valeur>
```

Clés connues : `CLAIM_TIER_1`, `tutorial_started`, `crystal_hunt_started`,
`woodcutter_reputation`, `RUNE_RAPPEL_GRANTED`. `set` est un **outil de debug** : il écrit la
valeur brute, il **ne rejoue pas** une progression (pas de récompense, pas d'effet de bord).

## Identifiants techniques

- Un id de quête / story est de la forme `rpgquest:<clé>` (ex. `rpgquest:guard_tier1`,
  `rpgquest:premiers_pas`). Le namespace `rpgquest:` est implicite pour la plupart des commandes.
- Les ids exacts sont visibles :
  - dans le dépôt : `src/main/resources/quests/*.yml`, `stories/*.yml` (champ `id:`) ;
  - dans le Control Panel : pages **Quêtes** et **Stories** (id copiable sur chaque carte).
- Un id d'**étape** (`step_1`, `prove_worth`…) n'est unique que **dans sa quête** : deux quêtes
  peuvent chacune avoir un `step_1`. Il est enregistré dans la progression des joueurs ; le
  renommer sur une quête publiée fait reprendre cette étape **à l'étape 1** pour les joueurs qui y
  sont. L'éditeur guidé le génère et ne le renumérote jamais (voir ci-dessous).

## Depuis le Control Panel

Page **Quêtes** / **Stories** : `quest.list`, `quest.player.status`, `story.list`,
`story.player.status` (lectures) ; `quest.start` / `quest.complete` / `quest.reset`,
`story.advance` / `story.complete`, `player.variable.set` (mutations, confirmation obligatoire).
Le donneur de quête (`giver:`) se pose via `quest.giver.set` depuis la page **PNJ**.

Dans la fiche d'une quête, chaque objectif et chaque récompense est précédé d'une petite icône
pixel art (la même que sur le portail public) : araignée, crâne, minerai, pièce, XP… L'icône est
décorative ; le texte reste la référence. Un objet sans icône dédiée prend l'icône de son type
(épée pour « Tuer », coffre pour « Collecter »…), jamais une image cassée.

## Éditeur guidé (créer / modifier une quête sans YAML)

Page **Quêtes** → « Créer une quête », ou « Modifier » sur une carte. Principe :

1. **Construire le brouillon d'abord.** Ajouter étapes, objectifs et récompenses ne demande
   *aucun* champ rempli au préalable : on pose la structure, on remplit ensuite. La page reste
   au niveau du bloc qu'on vient d'ajouter/supprimer.
2. **Choisir le type = voir seulement les bons champs.** Un objectif `KILL_ENTITY` demande une
   entité + une quantité ; `CRAFT_ITEM` un objet + une quantité ; `TALK_TO_NPC` un PNJ ; etc.
   Changer le type remplace les champs immédiatement et **efface** ce qui avait été saisi pour
   l'ancien type.
3. **Listes recherchables.** Entité, matériau, PNJ, icône, catégorie et quête prérequise se
   filtrent en tapant quelques lettres. La catégorie accepte aussi une valeur nouvelle.
4. **Valider à la fin.** « Vérifier » liste les anomalies (ERREUR bloquante / ATTENTION / INFO)
   et montre le YAML généré + le diff. « Enregistrer dans la source » écrit le fichier
   `src/main/resources/quests/<id>.yml` du dépôt — **aucun déploiement**, le serveur le validera
   à son prochain chargement.
5. **Identifiant d'étape : rien à saisir.** Chaque étape reçoit `step_1`, `step_2`… (affiché à
   côté de son numéro). Déplacer ou supprimer une étape ne change pas l'id des autres ; une étape
   ajoutée prend le numéro suivant, sans jamais reprendre celui d'une étape supprimée. Le bloc
   replié « Identifiant technique (avancé) » permet de le changer ; sur une quête déjà
   enregistrée, « Vérifier » prévient alors que les joueurs à cette étape repartiraient de
   l'étape 1.

## Catalogue : « source » et « serveur » (badges d'origine)

Les pages **Quêtes** et **Stories** montrent **deux origines fusionnées** :

- la **source éditable** — les fichiers `src/main/resources/quests/*.yml` et `stories/*.yml`,
  relus à chaque affichage de la page ;
- le **serveur DEV** — ce que le serveur a réellement chargé (dernier « Rafraîchir le catalogue »).

Chaque entrée porte un badge quand les deux ne coïncident pas :

| Badge | Sens | Ce qu'il faut faire |
|---|---|---|
| *(aucun)* | La quête est dans la source **et** chargée par le serveur. | Rien. |
| **Source uniquement** | Enregistrée dans la source (par ex. tout juste créée) mais **pas encore chargée en jeu**. | Elle est utilisable comme prérequis / étape de story dès maintenant. Pour la rendre active en jeu : recharger le contenu RPGQuest côté serveur (`/quest admin reload` ou redémarrage), puis « Rafraîchir le catalogue ». |
| **Hors source** | Chargée par le serveur mais **introuvable** dans la source éditable (fichier absent, renommé, ou source non montée sur PlugAdmin). | Vérifier le fichier dans le dépôt ; si c'est normal (quête livrée autrement), aucune action. |

« Source uniquement » **n'est pas une erreur** : c'est le fonctionnement normal du flux
« créer → enregistrer → utiliser ». La création dans l'éditeur et l'activation en jeu restent
**deux étapes séparées** — enregistrer ne redémarre jamais Minecraft.

## Catalogues de quêtes secondaires (PNJ récurrents)

Bouton **Catalogues de quêtes** en haut de la page Quêtes (`/quests/catalogs`). Un catalogue est
une liste de quêtes **existantes** qu'un PNJ récurrent (brigand, marchand égaré…) peut proposer :
il ne copie rien, chaque quête garde ses objectifs, ses récompenses et son délai.

1. **Créer un catalogue** : un nom, puis « Ajouter une quête » — tapez quelques lettres du titre et
   cochez. L'identifiant est calculé à partir du nom (modifiable dans « Informations techniques »).
2. **PNJ qui proposent ces quêtes** : cochez les PNJ récurrents dans le même écran (ou plus tard
   avec « Choisir des PNJ » sur la fiche).
3. **Dialogue** : dans le dialogue du PNJ, un choix avec l'action « Proposer une quête du
   catalogue ». Sans elle, le PNJ ne propose rien : la fiche l'indique (« ne sait pas encore
   proposer ») avec le lien vers son dialogue.

4. **Comment choisir la prochaine quête ?** Les quêtes sont proposées **au hasard** parmi celles
   que le joueur peut faire ; l'ordre de la liste sert seulement à la ranger.
   - **Au hasard, même chance pour toutes** (recommandé) : 3 quêtes = environ 1 chance sur 3 chacune.
   - **Au hasard, certaines plus souvent** : Rare, Normale ou Souvent à côté de chaque quête
     (Souvent, Normale, Normale = environ 50 %, 25 %, 25 %). Une valeur qui ne tombe sur aucun de
     ces niveaux reste affichée « Personnalisée (n) » et n'est jamais arrondie toute seule.
   - **Peut-on proposer deux fois de suite la même quête ?** Éviter les répétitions (recommandé)
     ou les autoriser ; « Éviter plus longtemps » (2 à 10 dernières quêtes finies) est replié.
   Ces chances valent quand toutes les quêtes sont possibles pour le joueur : celles déjà finies,
   en attente ou pas encore accessibles sont mises de côté.

La fiche d'un catalogue se lit ; **Modifier** permet de retirer une quête, **Dupliquer** et
**Supprimer** ont chacun leur écran. « Autoriser les PNJ à utiliser cette liste » met la liste en
pause quand la case est décochée. La note pour les administrateurs est dans **Notes** ;
l'identifiant et les options expertes de chaque quête (pause, rangement, valeur exacte de la
fréquence : Rare = 5, Normale = 10, Souvent = 20) dans **Informations techniques**.

En jeu, chaque joueur reçoit **sa** proposition, la même tant qu'il n'a pas terminé une quête du
catalogue ; rien n'est inscrit dans son journal avant « Commencer (j'accepte) ». Les contrôles
affichés (quête absente, catalogue vide, modèle sans dialogue adapté…) ne bloquent rien : ils
disent ce qui ne sera pas proposé. Un enregistrement est en jeu aussitôt, sans publication.
