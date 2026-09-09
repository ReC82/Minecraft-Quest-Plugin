---
title: Commandes RPGQuest (référence)
category: Administration serveur
tags: [rpgadmin, rpgquest, commandes, reference, syntaxe, console, admin, aide]
order: 1
---

# Commandes RPGQuest — référence

Une fiche par commande utile : **ce qu'elle fait** d'abord, puis **comment exactement**
(syntaxe, paramètres, exemple copiable, résultat attendu). Les identifiants des exemples
(`LoDyMcFly`, `guard`, `first_steps`, `main_story`, `world_hub`…) sont des valeurs de test
valides — remplacez-les par les vôtres.

Toutes les sous-commandes `/rpgadmin` demandent la permission `rpgquest.admin.world`.
`quest`, `story`, `player` et `guide` sont **utilisables depuis la console** (elles ciblent un
joueur passé en argument) ; les autres exigent un joueur en jeu (elles utilisent sa position
ou sa sélection).

## En un coup d'œil

| Je veux… | Commande |
|---|---|
| Voir la version du plugin | `/rpgquest version` |
| Recharger `config.yml` | `/rpgquest reload` |
| Voir le profil d'un joueur | `/rpgquest profile <joueur>` |
| Associer un PNJ visé à RPGQuest | `/rpgadmin npc tag <id>` |
| Retirer l'identifiant d'un PNJ | `/rpgadmin npc untag` |
| Démarrer une quête pour un joueur | `/rpgadmin quest start <joueur> <quête>` |
| Terminer une quête (récompenses incluses) | `/rpgadmin quest complete <joueur> <quête>` |
| Rendre une quête rejouable | `/rpgadmin quest reset <joueur> <quête>` |
| Voir l'état des stories d'un joueur | `/rpgadmin story info <joueur>` |
| Faire avancer une story d'une étape | `/rpgadmin story advance <joueur> <story>` |
| Remettre un joueur « à neuf » (test) | `/rpgadmin player resetnew <joueur> confirm` |
| Prévisualiser ce reset sans rien changer | `/rpgadmin player resetnew <joueur> preview` |
| Définir le spawn du village | `/rpgadmin spawn set` |
| Créer / recharger un monde | `/rpgadmin world create <nom>` |
| Créer un portail entre deux mondes | `/rpgadmin worldportal create <id> <mondeDestination>` |
| Ouvrir un dialogue à distance (test) | `/dialogue open <joueur> <dialogueId>` |
| Donner un objet personnalisé | `/customitem give <joueur> <id> [quantité]` |

---

## Joueurs

### Voir la version du plugin — `/rpgquest version`

Affiche la version de RPGQuest installée (et, en mode debug, quelques réglages de configuration).

**Syntaxe**

```
/rpgquest version
```

**Résultat attendu**

Une ligne `RPGQuest v<version>`.

---

### Voir le profil d'un joueur — `/rpgquest profile`

Affiche le profil RPGQuest d'un joueur : pseudo, UUID, date de création et de dernière mise à
jour du profil. Fonctionne aussi pour un joueur **hors ligne**.

**Syntaxe**

```
/rpgquest profile [joueur]
```

**Paramètres**

- `joueur` — pseudo à consulter. Omis : votre propre profil (si vous êtes en jeu).

**Exemple**

```
/rpgquest profile LoDyMcFly
```

**Résultat attendu**

Le bloc `Profil LoDyMcFly` avec l'UUID et les dates.

---

### Recharger la configuration — `/rpgquest reload`

Recharge **`config.yml`** uniquement (locale, base de données, resource pack…).

**Syntaxe**

```
/rpgquest reload
```

**Résultat attendu**

`Configuration RPGQuest rechargée.` — ou un refus détaillé si le fichier est invalide (l'ancienne
configuration reste alors active).

**Permission**

`rpgquest.admin` (distincte de `rpgquest.admin.world`).

**Attention**

Ne recharge **pas** les dialogues, quêtes, stories, zones ni portails. Un ajout/retrait de
fichier de dialogue demande un redémarrage du serveur (ou l'éditeur guidé du Control Panel).
Pour les quêtes et messages, voir `/quest admin reload`.

---

### Remettre un joueur « à neuf » — `/rpgadmin player resetnew`

Remet l'état **RPGQuest** d'**un seul** joueur dans l'équivalent d'un joueur qui n'a jamais
joué, pour rejouer tout l'onboarding. Utilisable **depuis la console**. Joueur en ligne ou hors
ligne.

Sont réinitialisés : quêtes (actives, progression, terminées, quête suivie), stories,
variables et unlocks (dont `CLAIM_TIER_1`), progression RPG (niveaux / XP), découvertes de
Waystones, cooldowns de portails et de la Rune de rappel, claim principal (**données de
protection uniquement**, pas les blocs), et les objets RPGQuest de l'inventaire.

Sont **conservés** : profil / UUID, économie, backpacks et entitlements, annonces de marché,
blocs construits, Waystones globales.

**Syntaxe**

```
/rpgadmin player resetnew <joueur> <preview|confirm>
```

**Paramètres**

- `joueur` — pseudo cible (en ligne ou hors ligne).
- `preview` — **dry-run** : liste, catégorie par catégorie, ce qu'un reset effacerait, **sans
  rien modifier**.
- `confirm` — mot obligatoire pour **exécuter réellement** le reset (protection anti-erreur).
  Sans `preview` ni `confirm` : seul un avertissement s'affiche, rien n'est fait.

**Exemple — prévisualiser**

```
/rpgadmin player resetnew LoDyMcFly preview
```

**Exemple — exécuter**

```
/rpgadmin player resetnew LoDyMcFly confirm
```

**Résultat attendu**

`preview` : un aperçu « Dry-run : aucune donnée n'a été modifiée » suivi du détail par
catégorie. `confirm` : `Reset « nouveau joueur » effectué pour LoDyMcFly` puis la liste des
éléments réinitialisés. Si le joueur est hors ligne, son inventaire RPGQuest est nettoyé à sa
prochaine connexion.

---

### Lire une variable joueur — `/rpgadmin player variable get`

Lit une variable persistée d'un joueur (`player_variables`). Lecture pure, joueur hors ligne
accepté.

**Syntaxe**

```
/rpgadmin player variable get <joueur> <clé>
```

**Paramètres**

- `joueur` — pseudo cible.
- `clé` — nom de la variable (ex. `CLAIM_TIER_1`).

**Exemple**

```
/rpgadmin player variable get LoDyMcFly CLAIM_TIER_1
```

**Résultat attendu**

`LoDyMcFly : variable CLAIM_TIER_1 = true` — ou « absente (équivaut à non définie) ».

---

### Écrire une variable joueur — `/rpgadmin player variable set`

Écrit une variable joueur. **Outil bas niveau de debug** : une variable seule ne reproduit pas
une progression de quête / story (objectifs, récompenses, prérequis).

**Syntaxe**

```
/rpgadmin player variable set <joueur> <clé> <valeur>
```

**Exemple**

```
/rpgadmin player variable set LoDyMcFly CLAIM_TIER_1 false
```

**Résultat attendu**

`Variable CLAIM_TIER_1 = false écrite pour LoDyMcFly (ancienne : true).` L'opération est
journalisée (ancienne + nouvelle valeur).

**Permission**

`rpgquest.admin.world` **et en plus** `rpgquest.admin.debug`. Ne jamais accorder cette
permission à un joueur normal.

---

## PNJ

Voir aussi la fiche **Créer et configurer un PNJ** pour le parcours complet (Citizens +
identifiant RPGQuest) et **Résoudre les problèmes de PNJ** pour les diagnostics.

### Associer un PNJ à RPGQuest — `/rpgadmin npc tag`

Associe l'entité **actuellement visée** (à 6 blocs maximum) à un identifiant RPGQuest stable,
indépendant de son nom affiché. C'est cet identifiant qui relie le PNJ à son contenu (dialogue
`rpgquest:<id>`, `giver` d'une quête, objectif « parler à »).

**Syntaxe**

```
/rpgadmin npc tag <id>
```

**Paramètres**

- `id` — identifiant technique souhaité : minuscules, chiffres, `.`, `_`, `-`. **Optionnel** :
  omis, un identifiant `npc_<n>` est généré automatiquement.

**Exemple**

```
/rpgadmin npc tag guard
```

**Résultat attendu**

`Entité identifiée : guard` (+ `(Citizens NPC #n)` si c'est un PNJ Citizens).

**Attention**

Commande **idempotente** : une entité déjà identifiée doit d'abord être libérée avec
`/rpgadmin npc untag`. L'identifiant doit correspondre au PNJ logique attendu par le contenu.

---

### Retirer l'identifiant d'un PNJ — `/rpgadmin npc untag`

Retire l'identifiant RPGQuest de l'entité visée (nécessaire avant de la ré-identifier, ou avant
de supprimer le PNJ Citizens).

**Syntaxe**

```
/rpgadmin npc untag
```

**Résultat attendu**

`Identifiant retiré.` — ou `Cette entité n'est pas identifiée.`

---

### Voir l'identifiant d'un PNJ — `/rpgadmin npc info`

Affiche l'identifiant RPGQuest de l'entité visée.

**Syntaxe**

```
/rpgadmin npc info
```

**Résultat attendu**

`Identifiant : guard` (+ `(Citizens NPC #n)` le cas échéant), ou « Cette entité n'est pas
identifiée. »

---

## Quêtes

Raccourcis d'administration / test (issue #36). Ils réutilisent le moteur de quête (jamais
d'écriture directe en base) et sont **journalisés** avec l'exécutant, la cible et l'opération.
Utilisables depuis la console. L'identifiant de quête peut être court (`first_steps`) ou
namespacé (`rpgquest:first_steps`).

### Démarrer une quête — `/rpgadmin quest start`

Démarre la quête pour un joueur **en ligne**, comme une acceptation normale. Les prérequis sont
respectés par défaut.

**Syntaxe**

```
/rpgadmin quest start <joueur> <quête> [force]
```

**Paramètres**

- `joueur` — cible, **en ligne** (le moteur de quête n'agit que sur un joueur connecté).
- `quête` — identifiant de la quête.
- `force` — mot-clé optionnel : **ignore les prérequis**.

**Exemple**

```
/rpgadmin quest start LoDyMcFly first_steps
```

**Résultat attendu**

`Quête démarrée pour LoDyMcFly : rpgquest:first_steps`. Si des prérequis manquent, la commande
les liste et suggère `force`.

---

### Terminer une quête — `/rpgadmin quest complete`

Complète la quête et **applique ses récompenses normales une seule fois** (dont les récompenses
`VARIABLE` comme `CLAIM_TIER_1`). Une quête déjà terminée renvoie `ALREADY_COMPLETED` et rien
n'est re-crédité. Cible **en ligne**.

**Syntaxe**

```
/rpgadmin quest complete <joueur> <quête>
```

**Exemple**

```
/rpgadmin quest complete LoDyMcFly first_steps
```

**Résultat attendu**

`Quête complétée (récompenses appliquées) pour LoDyMcFly : rpgquest:first_steps`.

---

### Rendre une quête rejouable — `/rpgadmin quest reset`

Supprime la ligne de progression et les compteurs d'objectifs (la quête redevient acceptable).
Fonctionne **hors ligne**.

**Syntaxe**

```
/rpgadmin quest reset <joueur> <quête>
```

**Exemple**

```
/rpgadmin quest reset LoDyMcFly first_steps
```

**Résultat attendu**

`Quête réinitialisée (progression + compteurs) pour LoDyMcFly`.

**Attention**

**N'annule pas** les récompenses déjà accordées (XP, objets, variables comme `CLAIM_TIER_1`,
effets de commande). Pour une remise à zéro complète : `/rpgadmin player resetnew <joueur>
confirm`. Pour annuler une variable précise : `/rpgadmin player variable set <joueur> <clé>
false`.

---

### Recharger les quêtes et messages — `/quest admin reload`

Recharge les définitions de quêtes et les messages depuis le disque.

**Syntaxe**

```
/quest admin reload
```

**Résultat attendu**

Confirmation du rechargement, ou un rapport d'erreurs si un fichier est invalide.

**Permission**

`rpgquest.admin`.

---

### Valider les quêtes sans appliquer — `/quest admin validate`

Analyse les fichiers de quêtes et signale les problèmes **sans rien recharger**.

**Syntaxe**

```
/quest admin validate
```

---

### Réinitialiser les quêtes d'un joueur — `/quest admin reset`

Variante joueur de `reset` : une quête précise, ou **toutes**. Cible **en ligne**.

**Syntaxe**

```
/quest admin reset <joueur> <quête|all>
```

**Exemple**

```
/quest admin reset LoDyMcFly all
```

**Permission**

`rpgquest.admin`.

---

## Stories

Une story est un conteneur ordonné de quêtes existantes. Ces commandes ciblent un joueur passé
en argument et sont utilisables **depuis la console**. `advance` et `complete` exigent le
joueur **en ligne**.

### Voir l'état des stories d'un joueur — `/rpgadmin story info`

Liste toutes les stories chargées et leur état pour ce joueur (avec la quête courante si la
story est active).

**Syntaxe**

```
/rpgadmin story info <joueur>
```

**Exemple**

```
/rpgadmin story info LoDyMcFly
```

**Résultat attendu**

`=== Stories de LoDyMcFly ===` suivi d'une ligne par story : `- main_story (…) : ACTIVE — quête
courante : … (2/4)`.

---

### Démarrer une story — `/rpgadmin story start`

Démarre la story pour le joueur.

**Syntaxe**

```
/rpgadmin story start <joueur> <story>
```

**Exemple**

```
/rpgadmin story start LoDyMcFly main_story
```

**Résultat attendu**

`Story démarrée : main_story pour LoDyMcFly` — ou `déjà active` / `déjà terminée` / `Story
inconnue`.

---

### Avancer d'une étape — `/rpgadmin story advance`

Complète l'étape (quête) courante et passe à la suivante. Démarre la story si elle n'était pas
commencée. Joueur **en ligne**.

**Syntaxe**

```
/rpgadmin story advance <joueur> <story>
```

**Exemple**

```
/rpgadmin story advance LoDyMcFly main_story
```

**Résultat attendu**

`➜ LoDyMcFly est maintenant à l'étape 3/4 de main_story : …` — ou `➜ Story main_story TERMINÉE`
si c'était la dernière étape.

---

### Compléter toute la story — `/rpgadmin story complete`

Complète toutes les étapes restantes, une par une. Joueur **en ligne**.

**Syntaxe**

```
/rpgadmin story complete <joueur> <story>
```

**Exemple**

```
/rpgadmin story complete LoDyMcFly main_story
```

---

### Réinitialiser une story — `/rpgadmin story reset`

Réinitialise la progression d'une story (ou de **toutes** avec `all`). **Ne touche pas** aux
quêtes de la story.

**Syntaxe**

```
/rpgadmin story reset <joueur> <story|all>
```

**Exemple**

```
/rpgadmin story reset LoDyMcFly main_story
```

**Résultat attendu**

`Story réinitialisée : main_story pour LoDyMcFly` — ou `Toute la progression Story de LoDyMcFly
a été réinitialisée.` avec `all`.

---

### Réinitialiser une story ET ses quêtes — `/rpgadmin story resetwithquests`

Comme `reset`, mais remet **aussi** les quêtes de cette story dans un état rejouable. Jamais
`all`, jamais les autres quêtes du joueur.

**Syntaxe**

```
/rpgadmin story resetwithquests <joueur> <story>
```

**Exemple**

```
/rpgadmin story resetwithquests LoDyMcFly main_story
```

**Résultat attendu**

`Story ET ses quêtes réinitialisées : main_story pour LoDyMcFly`.

---

## Mondes

### Définir le spawn du village — `/rpgadmin spawn set`

Capture votre position et orientation exactes comme nouveau spawn du village (remplace l'ancien).

**Syntaxe**

```
/rpgadmin spawn set
```

**Résultat attendu**

`Spawn du village défini : (world_hub x, y, z, yaw=…)`.

---

### Se téléporter au spawn du village — `/rpgadmin spawn tp`

Vous téléporte au spawn du village.

**Syntaxe**

```
/rpgadmin spawn tp
```

**Attention**

Si aucun spawn n'est défini, la commande renvoie vers `/rpgadmin spawn set`.

---

### Créer ou recharger un monde — `/rpgadmin world create`

Crée le monde (première fois) ou le recharge (dossier déjà présent), toujours en environnement
**NORMAL**, seed aléatoire.

**Syntaxe**

```
/rpgadmin world create <nom>
```

**Paramètres**

- `nom` — minuscules, chiffres, `.`, `_`, `-`.

**Exemple**

```
/rpgadmin world create wild
```

**Résultat attendu**

`Monde créé et chargé : wild` — ou `déjà chargé` / `Nom de monde invalide`.

---

### Se téléporter au spawn d'un monde — `/rpgadmin world tp`

Vous téléporte au spawn du monde indiqué.

**Syntaxe**

```
/rpgadmin world tp <nom>
```

**Exemple**

```
/rpgadmin world tp world_hub
```

**Attention**

Le monde doit déjà être chargé (`/rpgadmin world create <nom>` au besoin).

---

### Lister les mondes chargés — `/rpgadmin world list`

Liste les mondes actuellement chargés, avec leur environnement et un marqueur « géré par
RPGQuest ».

**Syntaxe**

```
/rpgadmin world list
```

---

## Portails & Waystones

### Créer un portail entre deux mondes — `/rpgadmin worldportal create`

Crée un portail simple : la **zone sélectionnée** (outil de sélection de zone) devient l'entrée,
et le joueur qui y pénètre est envoyé au **spawn du monde destination**.

**Syntaxe**

```
/rpgadmin worldportal create <id> <mondeDestination>
```

**Paramètres**

- `id` — identifiant du portail.
- `mondeDestination` — nom d'un monde chargé.

**Exemple**

```
/rpgadmin worldportal create hub_to_claims claims
```

**Résultat attendu**

Le portail est créé et actif immédiatement.

---

### Gérer les portails entre mondes

`info`, `list`, `enable`, `disable`, `delete` prennent l'`id` du portail. `disable` bloque le
déclenchement mais conserve la configuration ; `delete` est définitif.

**Détail d'un portail**

```
/rpgadmin worldportal info hub_to_claims
```

**Lister les portails**

```
/rpgadmin worldportal list
```

**Désactiver un portail**

```
/rpgadmin worldportal disable hub_to_claims
```

**Réactiver un portail**

```
/rpgadmin worldportal enable hub_to_claims
```

**Supprimer un portail (définitif)**

```
/rpgadmin worldportal delete hub_to_claims
```

**Diagnostic : portails à ma position**

```
/rpgadmin worldportal here
```

---

### Waystones

Réseau de téléportation. `list` / `here` inspectent, `tp <id>` s'y téléporte, `generatehere`
crée une Waystone dans la cellule courante, `reset discoveries <joueur>` efface les découvertes
d'un joueur.

**Lister les Waystones**

```
/rpgadmin waystone list
```

**Waystone de la cellule courante**

```
/rpgadmin waystone here
```

**Se téléporter à une Waystone**

```
/rpgadmin waystone tp forest_01
```

**Générer une Waystone ici**

```
/rpgadmin waystone generatehere
```

**Réinitialiser les découvertes d'un joueur**

```
/rpgadmin waystone reset discoveries LoDyMcFly
```

---

## Administration serveur

### Ouvrir un dialogue à distance — `/dialogue open`

Ouvre un dialogue pour un joueur sans passer par un PNJ. Outil d'administration / test.

**Syntaxe**

```
/dialogue open <joueur> <dialogueId>
```

**Exemple**

```
/dialogue open LoDyMcFly rpgquest:guard
```

**Permission**

`rpgquest.admin`.

---

### Donner un objet personnalisé — `/customitem give`

Donne un objet du registre d'objets personnalisés à un joueur.

**Syntaxe**

```
/customitem give <joueur> <id> [quantité]
```

**Paramètres**

- `id` — identifiant de l'objet (voir `/customitem list`).
- `quantité` — optionnelle, 1 par défaut.

**Exemple**

```
/customitem give LoDyMcFly rpgquest:miner_pickaxe 1
```

**Permission**

`rpgquest.admin`.

---

### Lister / inspecter les objets personnalisés

**Lister les objets chargés**

```
/customitem list
```

**Identifier l'objet en main**

```
/customitem inspect
```

`inspect` demande `rpgquest.item` (utilisable par un joueur).

---

### Diagnostic des Guides de Hub — `/rpgadmin guide list`

Inspection en lecture seule des Guides de Hub configurés (`hub-guides/*.yml`). N'ouvre aucun
dialogue, ne modifie rien. Utilisable depuis la console.

**Lister les Guides**

```
/rpgadmin guide list
```

**Détail d'un Guide**

```
/rpgadmin guide info world_hub
```

---

## Outils builder & avancés

Commandes de terrain (zones, portails simples, mobs, aplatissement). Elles agissent sur votre
**position** ou votre **sélection** et exigent un joueur en jeu.

### Zones — `/rpgadmin zone`

`wand` donne l'outil de sélection, `create <id>` crée une zone depuis la sélection, `list` /
`info <id>` inspectent, `delete <id>` supprime.

**Outil de sélection**

```
/rpgadmin zone wand
```

**Créer une zone depuis la sélection**

```
/rpgadmin zone create village_square
```

**Détail d'une zone**

```
/rpgadmin zone info village_square
```

**Supprimer une zone**

```
/rpgadmin zone delete village_square
```

---

### Portails simples (intra-monde) — `/rpgadmin portal`

Portails à destination configurable (`portal` ≠ `worldportal`). `create <id>` depuis la
sélection, `setdestination <id> <destinationId>` fixe la sortie à votre position.

**Créer un portail**

```
/rpgadmin portal create market_entrance
```

**Fixer sa destination sur ma position**

```
/rpgadmin portal setdestination market_entrance market_inside
```

**Lister / détail / supprimer**

```
/rpgadmin portal list
```

---

### Mobs spéciaux — `/rpgadmin mob`

`spawn <id>` invoque une variante à votre position ; `list` / `inspect <id>` inspectent ;
`reload` recharge les définitions ; `metrics` affiche les compteurs.

**Invoquer une variante**

```
/rpgadmin mob spawn rpgquest:spider_broodmother
```

**Recharger les variantes**

```
/rpgadmin mob reload
```

---

### Aplatir le terrain — `/rpgadmin flatten`

Aperçu d'abord, exécution ensuite (même esprit que `player resetnew`). Centré sur votre
position.

**Aperçu (ne modifie rien)**

```
/rpgadmin flatten 16
```

`<rayon>` obligatoire, `[hauteur]` optionnelle (`/rpgadmin flatten 16 64`).

**Exécuter l'aperçu en attente**

```
/rpgadmin flatten confirm
```

**Annuler l'aperçu / l'opération en cours**

```
/rpgadmin flatten cancel
```

**Annuler le dernier aplatissement**

```
/rpgadmin flatten undo
```

---

> [!NOTE]
> Source de vérité : le code des commandes (`RpgAdminCommand`, `RPGQuestCommand`, `QuestCommand`,
> `DialogueCommand`, `CustomItemCommand`). Si cette fiche et le jeu divergent, le jeu a raison —
> signalez-le pour corriger la fiche.
