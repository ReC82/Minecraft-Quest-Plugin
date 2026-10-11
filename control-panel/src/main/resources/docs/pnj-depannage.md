---
title: Résoudre les problèmes de PNJ
category: PNJ / Citizens
tags: [pnj, npc, diagnostic, depannage, binding, definition, citizens, warning, error]
order: 2
---

# Résoudre les problèmes de PNJ

Chaque alerte de la page **PNJ** renvoie ici, à la section correspondant à son code
technique. Le principe est toujours : *comprendre → corriger → vérifier*.

---

## Fiche RPGQuest manquante

### Ce que cela signifie

Le PNJ existe bien dans le jeu (plugin Citizens), mais il n'a pas encore de **fiche
RPGQuest** (`npcs/<id>.yml`). Le cas se présente aussi quand un contenu (quête, dialogue)
fait référence à un identifiant de PNJ qu'aucune fiche ne décrit.

### Pourquoi il faut corriger

Sans cette fiche, PlugAdmin ne peut pas gérer ses dialogues, ses quêtes ni son rôle. Le
contenu qui pointe vers ce PNJ ne fonctionnera pas correctement.

### Comment corriger

Depuis PlugAdmin :

1. Ouvrir **PNJ**.
2. Cliquer sur le PNJ concerné pour ouvrir son détail.
3. Section **Actions**, cliquer **« Créer la définition »**.
4. Vérifier l'**ID technique** (il est pré-rempli et non modifiable dans cette fiche).
5. Choisir son **dialogue** et son **rôle** si nécessaire.
6. Cliquer **« Créer la définition »**.

Si l'alerte venait d'un contenu (quête/dialogue) et non d'un PNJ du jeu, l'autre option est
de corriger l'identifiant dans ce contenu.

### Vérification

Le badge **« sans définition »** disparaît de la ligne du PNJ.

### Référence technique

`BINDING_NO_DEFINITION`, `NO_DEFINITION`

---

## Dialogue sans PNJ porteur

### Ce que cela signifie

L'entrée que vous regardez **n'est pas un vrai PNJ**. C'est un **dialogue** dont aucune
fiche RPGQuest ne se déclare porteuse : faute de mieux, le catalogue en déduit un PNJ du
même nom, et l'affiche « sans définition ».

C'est exactement le cas des **deux entrées « Mira »** observées au test du 2026-10-08 :

| Entrée | Ce que c'est |
|---|---|
| `mira_cartographer` | le vrai PNJ — fiche RPGQuest, lié à Citizens #9, déclare le dialogue `rpgquest:mira_first_map` |
| `mira_first_map` | **le dialogue lui-même**, déduit en PNJ parce que personne ne le réclamait |

Depuis la correction, une fiche qui **déclare** un dialogue le réclame : un dialogue
déclaré ne produit plus d'entrée fantôme. L'alerte ne reste donc que pour les dialogues
réellement sans porteur — ceux que personne ne peut déclencher en jeu.

> **Un PNJ peut porter un dialogue qui ne s'appelle pas comme lui.** La convention « le
> dialogue porte le nom du PNJ » n'est que le *défaut* : le champ **Dialogue** de la fiche
> fait autorité. Ne renommez rien pour « respecter la convention » — vous casseriez le
> lien existant.

### Pourquoi il faut corriger

Un dialogue sans porteur est inatteignable : aucun PNJ ne l'ouvre, et il encombre le
catalogue d'une entrée qui ressemble à un PNJ cassé.

### Comment corriger

Deux remèdes, au choix — **jamais les deux** :

- **Rattacher le dialogue à un PNJ existant** (le plus fréquent). Ouvrir la fiche du PNJ
  qui doit le porter, **Modifier**, et choisir ce dialogue dans le champ **Dialogue**.
- **Créer la fiche RPGQuest de ce nom**, si le dialogue correspond bien à un personnage
  qui n'existe pas encore.

### Vérification

L'entrée sans définition disparaît du catalogue après **« Rafraîchir »**, et le dialogue
apparaît sur la fiche du PNJ qui le porte.

### Référence technique

`DIALOGUE_WITHOUT_NPC`

---

## Dialogue introuvable

### Ce que cela signifie

La fiche du PNJ pointe vers un dialogue (`rpgquest:<clé>`) qui n'existe pas, ou qui n'est
pas chargé par le serveur.

### Pourquoi il faut corriger

Le PNJ n'ouvrira **aucune conversation** en jeu.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir le PNJ concerné.
2. Section **Actions** → **« Modifier »**.
3. Dans **Dialogue**, choisir un dialogue existant dans la liste.
4. Enregistrer.

Si le dialogue devrait exister, le créer depuis la page **Dialogues** puis revenir ici.

### Vérification

L'alerte disparaît après un rafraîchissement du catalogue.

### Référence technique

`DIALOGUE_MISSING`

---

## Fiche RPGQuest en double

### Ce que cela signifie

Plusieurs fichiers `npcs/*.yml` décrivent le **même identifiant**.

### Pourquoi il faut corriger

PlugAdmin ne sait pas laquelle utiliser : le comportement en jeu devient imprévisible.

### Comment corriger

Côté serveur, dans `plugins/RPGQuest/npcs/`, ne garder **qu'un seul** fichier pour cet
identifiant et supprimer les autres, puis recharger (`/rpgquest reload` ou redémarrage).

### Vérification

Après rechargement du catalogue, l'alerte disparaît.

### Référence technique

`DUPLICATE_DEFINITION`

---

## Plusieurs PNJ pour le même identifiant

### Ce que cela signifie

Plusieurs PNJ du jeu (Citizens) sont **associés au même identifiant** RPGQuest.

### Pourquoi il faut corriger

En jeu, on ne sait pas lequel doit porter les dialogues et les quêtes.

### Comment corriger

En jeu, retirer l'association en trop : `/rpgadmin npc untag` sur le ou les PNJ Citizens qui
ne doivent pas porter cet identifiant. Un seul PNJ doit rester associé.

### Vérification

La ligne ne montre plus qu'un seul PNJ Citizens.

### Référence technique

`DUPLICATE_BINDING`

---

## Fiche désactivée

### Ce que cela signifie

La fiche RPGQuest du PNJ existe mais elle est marquée **inactive** (`enabled: false`).

### Pourquoi il faut corriger

Le PNJ est **ignoré** par RPGQuest tant que sa fiche reste inactive — utile pour préparer un
contenu sans l'exposer, mais à réactiver ensuite.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir le PNJ.
2. **Actions** → **« Modifier »**.
3. Activer l'interrupteur **« PNJ actif »**.
4. Enregistrer.

### Vérification

Le badge **« désactivé »** disparaît.

### Référence technique

`DISABLED`

---

## PNJ pas encore présent en jeu

### Ce que cela signifie

La fiche RPGQuest est prête, mais **aucun PNJ du jeu** ne lui est encore associé.

### Pourquoi il faut corriger

Les joueurs ne verront pas ce PNJ tant qu'il n'existe pas en jeu.

### Comment corriger

Deux options depuis PlugAdmin (**PNJ** → ouvrir le PNJ → **Actions**) :

- **« Lier un PNJ Citizens »** : associer un PNJ déjà présent en jeu.
- **« Créer le PNJ Citizens »** : le faire apparaître à des coordonnées précises et l'associer.

### Vérification

Le badge **« à lier »** est remplacé par **« Citizens #… »**.

### Référence technique

`NOT_LINKED`

---

## Donneur de quête sans conversation

### Ce que cela signifie

Le PNJ est déclaré **donneur de quête**, mais il n'a pas de dialogue associé.

### Pourquoi il faut corriger

La quête ne pourra pas être **proposée au joueur** au cours d'une conversation.

### Comment corriger

1. Ouvrir **PNJ**, ouvrir le PNJ.
2. **Actions** → **« Modifier »**.
3. Choisir un **dialogue** dans la liste (le créer d'abord depuis **Dialogues** s'il n'existe pas).
4. Enregistrer.

### Vérification

L'alerte disparaît après rafraîchissement du catalogue.

### Référence technique

`GIVER_NO_DIALOGUE`

---

## Citizens inactif sur le serveur cible

### Ce que cela signifie

Le plugin **Citizens** n'est pas actif sur le serveur RPGQuest. Les liaisons entre les PNJ du
jeu et les fiches RPGQuest ne peuvent donc pas être vérifiées.

### Pourquoi il faut corriger

Les fiches RPGQuest restent créables et modifiables, mais PlugAdmin ne peut pas contrôler leur
présence réelle en jeu (aucun PNJ visible, aucune position).

### Comment corriger

1. Installer / activer **Citizens** sur le serveur cible (version supportée par RPGQuest).
2. Redémarrer le serveur.
3. Rafraîchir le catalogue PNJ depuis PlugAdmin.

### Vérification

La page **PNJ** ne montre plus le bandeau « Citizens inactif » ; les liaisons Citizens
s'affichent.

### Référence technique

`CITIZENS_UNAVAILABLE`

---

## PNJ du jeu sans fiche RPGQuest

### Ce que cela signifie

Le PNJ a été créé **directement dans Citizens** (par exemple `/npc create Stan`) et n'a jamais
été rattaché à RPGQuest. Il apparaît dans la liste **PNJ** avec son nom en jeu, le badge
**« sans fiche RPGQuest »** et le badge **« non lié »**, sous les filtres **Tous** et
**Non liés**.

C'est une **information**, pas une erreur : un PNJ purement décoratif (figurant, garde
d'ambiance…) n'a pas besoin de fiche.

### Les trois états possibles d'un PNJ

| Situation | Ce que RPGQuest en fait |
|---|---|
| **Citizens uniquement** (ce cas) | Le PNJ existe en jeu ; RPGQuest ne gère ni ses dialogues, ni ses quêtes, ni son rôle. |
| **Fiche RPGQuest uniquement** | La configuration est prête (`npcs/<id>.yml`) mais aucun PNJ du jeu ne la porte encore → badge « à lier ». |
| **Lié** | Un PNJ du jeu porte une fiche RPGQuest → dialogues, quêtes et rôle sont actifs. |

### Comment le rattacher (facultatif)

Depuis PlugAdmin : **PNJ** → ouvrir le PNJ (son nom en jeu) → section **Actions** :

- **« Créer une fiche RPGQuest »** : crée `npcs/<id>.yml` (identifiant pré-rempli à partir du
  nom, modifiable ensuite côté serveur). Le PNJ reste à lier après création.
- **« Lier à une fiche existante »** : associe ce PNJ Citizens à une fiche RPGQuest déjà prête
  et non liée (aucun spawn, aucun déplacement).

### Vérification

Après rafraîchissement du catalogue, le PNJ passe de **« non lié »** à **« Citizens #… »**
et affiche l'état **Lié** (ou **à lier** si seule la fiche a été créée).

### Référence technique

`CITIZENS_ONLY`

---

## Apparence à configurer

### Ce que cela signifie

Ce PNJ est de type **joueur** et son apparence n'est **pas décrite dans RPGQuest**. Elle est
probablement bien visible en jeu — mais elle a été posée en dehors du panel (commande Citizens
`/npc skin`, ou avant l'arrivée de ce suivi), et RPGQuest ignore d'où elle vient.

> [!IMPORTANT]
> Ce n'est **pas** « ce PNJ n'a pas de skin ». C'est « on ne sait pas quel skin il porte ». L'API
> publique de Citizens ne permet pas de *lire* la texture appliquée à un PNJ : RPGQuest ne connaît
> que les apparences qu'il a lui-même posées et enregistrées.

### Pourquoi il faut corriger

Un PNJ de type joueur **sans skin explicite dérive son visage de son nom**. Le renommer changerait
donc aussi son apparence — et comme son origine est inconnue, il serait impossible de la restituer.
PlugAdmin **refuse** donc son renommage (`SKIN_SOURCE_UNKNOWN`) plutôt que de remplacer son visage
en silence. Tant que l'apparence n'est pas définie, ce personnage ne peut pas être renommé.

### Comment corriger

Depuis PlugAdmin :

1. Ouvrir **PNJ**, puis le PNJ concerné. Le filtre **« Apparence à configurer »** les liste tous.
2. Section **Apparence** → **« Définir une apparence »**.
3. Choisir la source :
   - **Pseudo Minecraft** : le plus simple. Pour conserver *exactement* le visage actuel, indiquer
     le pseudo dont il dérivait — en pratique, l'ancien nom du PNJ.
   - **Lien MineSkin** (`https://minesk.in/…`) : pour une texture sur mesure.
4. Appliquer, puis rafraîchir le relevé **Citizens** en haut de page.
5. Le renommage est alors possible : section **Identité** → **« Renommer le PNJ »**.

L'avertissement apparaît **avant** la soumission du renommage, avec ce bouton juste à côté : le nom
saisi n'est pas perdu, et aucune couche n'a été modifiée entre-temps.

### Vérification

L'état d'apparence passe à **« configurée »** sur la tuile et sur la fiche, la source enregistrée y
est nommée, et le PNJ disparaît du filtre « Apparence à configurer ». Vérifier le visage **en jeu** :
le téléchargement est asynchrone côté Citizens, et un client déjà connecté doit parfois se
reconnecter pour le voir.

### Référence technique

`SKIN_SOURCE_UNKNOWN`, table `npc_citizens_skins` (migration V27)

---

## Type de PNJ inconnu

### Ce que cela signifie

Le relevé Citizens ne dit pas de quel **type d'entité** est ce PNJ. Sans cette information, il est
impossible de savoir si la question du skin le concerne : un villageois n'a pas de skin de joueur,
un PNJ de type joueur en a un qui dépend de son nom.

Le panel affiche donc **« type inconnu »** plutôt que de supposer. Un type absent ne veut jamais
dire « ce n'est pas un joueur ».

### Pourquoi il faut corriger

Par prudence, le renommage est refusé tant que le type est inconnu : il pourrait s'agir d'un PNJ
joueur dont le visage dériverait du nom.

### Comment corriger

Cliquer sur **« Citizens »** en haut de la page PNJ pour refaire le relevé. La cause la plus
fréquente est un relevé antérieur à la version du plugin qui transporte le type d'entité — un
simple rafraîchissement suffit alors.

Si le type reste inconnu après un relevé frais, c'est que Citizens n'expose pas le trait `MobType`
pour ce PNJ. Vérifier en jeu avec `/npc select` puis `/npc` (le type est affiché dans l'en-tête).

### Vérification

La fiche affiche le type en français (« Joueur », « Villageois »…) avec sa valeur technique en
secondaire, et l'état d'apparence devient exploitable.

### Référence technique

`entityType` absent du relevé `npc.citizens.list`
