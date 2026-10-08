---
title: Créer et configurer un PNJ (Citizens + RPGQuest)
category: PNJ / Citizens
tags: [citizens, npc, pnj, tag, skin, rpgadmin, binding, dialogue, giver]
order: 1
---

# Créer et configurer un PNJ

RPGQuest **ne fournit ni ne modifie aucune commande Citizens**. On crée et on gère le PNJ avec
les commandes **Citizens** normales, puis on lui ajoute **un seul** identifiant RPGQuest par
`/rpgadmin npc tag`. Cet identifiant est la clé qui relie le PNJ au contenu (dialogue, quête).

Référence complète : `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` dans le dépôt.

## 1. Créer le PNJ Citizens

En jeu, visé sur l'endroit où le placer :

```
/npc create Garde --type player
```

- `Garde` est le **nom affiché** (cosmétique, libre, accents et majuscules autorisés).
- `--type player` donne une apparence de joueur (skin possible). Sans option, c'est un villageois.
- La commande crée le PNJ **et le sélectionne** automatiquement (les commandes suivantes agissent
  dessus).

## 2. Sélectionner un PNJ existant

```
/npc select <id numérique>
```

Le PNJ le plus proche est aussi sélectionné en le regardant (selon la config Citizens). L'id
numérique Citizens (`#3`, `#4`…) est visible avec `/npc list` ou au survol.

## 3. Taguer côté RPGQuest — l'étape qui relie au contenu

Se placer à moins de 6 blocs, **regarder le PNJ**, puis :

| Commande | Effet |
|---|---|
| `/rpgadmin npc tag <id>` | Identifie le PNJ visé avec **l'id de votre choix** (voir règles ci-dessous). |
| `/rpgadmin npc tag` | Identifie avec un id **auto-généré** `npc_<n>` (séquentiel, jamais réutilisé). |
| `/rpgadmin npc untag` | Retire l'identifiant RPGQuest. Le PNJ Citizens n'est **pas** supprimé, seul le mapping l'est. |
| `/rpgadmin npc info` | Affiche l'id RPGQuest actuel du PNJ visé + son id Citizens entre parenthèses (`guard (Citizens NPC #3)`). |

Règles de l'id :

- minuscules, chiffres, `.` `_` `-` uniquement ;
- `tag` est **idempotent** : si le PNJ est déjà identifié, la commande ne change rien et rappelle
  l'id existant — il faut d'abord `untag` pour ré-identifier ;
- le mapping est persisté dans `data.db` (`npc_citizens_bindings`) et **survit aux redémarrages**
  (RPGQuest suit l'UUID interne Citizens, pas le nom ni l'id numérique).

### Nom affiché vs identifiant technique

Ce sont **deux choses différentes** :

| | Exemple | Rôle |
|---|---|---|
| Nom affiché | `Garde` | cosmétique, au-dessus de la tête, peut changer à tout moment |
| Identifiant RPGQuest | `guard` | **technique**, invariable, c'est lui que le contenu référence |

L'**identifiant canonique** attendu par un contenu donné est celui écrit dans le YAML
(`giver:`, objectif `TALK_TO_NPC` → `npc:`, convention de dialogue `rpgquest:<id>`). Il faut
taguer le PNJ **exactement** avec cet id.

> [!WARNING]
> Un mauvais tag = contenu cassé silencieusement. Si le YAML attend `guard` et que le PNJ est
> tagué `garde` (ou `Garde`, refusé car majuscule), le dialogue ne s'ouvre pas et l'objectif
> `TALK_TO_NPC` ne valide jamais. Vérifier avec `/rpgadmin npc info` et la page **PNJ** du
> Control Panel avant de conclure « ça marche ».

## 4. Mettre un skin

Le PNJ doit être `--type player`. Sélectionné, puis :

```
/npc skin <pseudo>
```

Applique le skin du compte Minecraft `<pseudo>` (le plus simple et le plus fiable). Le skin est
mémorisé par Citizens et persiste.

- `/npc skin -c <pseudo>` : idem mais force le rafraîchissement du cache de skin.
- Skin par **URL de texture** : `/npc skin -t <url>` — ne fonctionne qu'avec une vraie URL de
  texture `textures.minecraft.net` **et** selon la configuration/version de Citizens installée
  (Citizens `2.0.43` sur ce serveur). En cas de doute, préférer le skin par pseudo.

## 5. Déplacer / repositionner

- `/npc move` : déplace le PNJ sélectionné vers votre position (validez avec `/npc move` à
  nouveau, ou `/npc move --stop` pour annuler selon la config).
- `/npc tp` : vous téléporte au PNJ. `/npc tphere` : amène le PNJ à vous.
- Pour un placement fin, se mettre exactement où on veut le PNJ puis `/npc tphere` puis ajuster
  l'orientation avec `/npc rotate`.

## 6. Renommer / supprimer

- `/npc rename <nouveau nom>` : change le **nom affiché** uniquement (l'id RPGQuest ne bouge pas).
- `/npc remove` : **supprime définitivement** le PNJ Citizens sélectionné. À n'utiliser que si on
  est sûr. Faire d'abord `/rpgadmin npc untag` pour retirer proprement le mapping RPGQuest.
- `/npc remove all` : supprime **tous** les PNJ — jamais sur un serveur avec du contenu.

## 7. Lier au contenu RPGQuest

Une fois le PNJ tagué `guard`, le contenu s'y rattache **par cet id** :

| Lien | Où | Ce qui se passe |
|---|---|---|
| **Dialogue** | fichier `dialogues/guard.yml` avec `id: rpgquest:guard` (convention `rpgquest:<id>`) | cliquer sur le PNJ tagué ouvre ce dialogue automatiquement |
| **Giver de quête** | `giver: guard` dans le YAML de la quête | la quête est « donnée » par ce PNJ (affiché dans le journal / la page Quêtes) |
| **Objectif `TALK_TO_NPC`** | `npc: guard` dans un `objective` de quête | parler au PNJ tagué `guard` valide l'objectif |
| **NpcDefinition** | `npcs/guard.yml` (`display_name`, `dialogue`, `role`, `enabled`) | définition **logique** du PNJ, indépendante de Citizens — pilotable depuis la page PNJ |
| **Binding Citizens** | table `npc_citizens_bindings` (posée par `/rpgadmin npc tag` sur un PNJ Citizens) | rattache la définition logique au PNJ physique Citizens |

Depuis le Control Panel : la page **PNJ** liste définitions + bindings + anomalies, et permet
`npc.definition.create/update`, `npc.citizens.link`, `npc.citizens.create`, `quest.giver.set`.

## 8. Diagnostic

- En jeu : `/rpgadmin npc info` en visant le PNJ (id RPGQuest + id Citizens).
- Control Panel → page **PNJ** (`/npcs`) : rafraîchir le catalogue et lire les warnings —

| Warning | Signification | Action |
|---|---|---|
| `NO_DEFINITION` / `BINDING_NO_DEFINITION` | un binding Citizens existe mais aucune `npcs/<id>.yml` | créer la définition (`npc.definition.create`) |
| `DIALOGUE_MISSING` | la définition déclare un `dialogue:` qui n'est pas chargé | créer/corriger le dialogue, ou retirer le champ |
| `DIALOGUE_WITHOUT_NPC` | un dialogue que personne ne porte, déduit en « PNJ » par le catalogue | rattacher le dialogue à un PNJ existant (champ **Dialogue** de sa fiche), ou créer la fiche de ce nom |
| `DUPLICATE_BINDING` | deux PNJ Citizens liés au même id RPGQuest | `untag` l'un des deux |
| `NOT_LINKED` | définition présente mais aucun PNJ Citizens tagué avec cet id | taguer le bon PNJ en jeu (`/rpgadmin npc tag <id>`) |
| `QUEST_REF_NO_NPC` | un YAML référence un id de PNJ qui n'existe nulle part | taguer un PNJ, ou corriger l'id dans le YAML |

- Vérifier l'état réel côté serveur : table `npc_citizens_bindings` de `data.db` (une ligne par
  PNJ lié).

## Séquence type (nouveau PNJ « guard »)

```
/npc create Garde --type player      # crée + sélectionne le PNJ Citizens
/npc skin Notch                      # optionnel, cosmétique
/rpgadmin npc info                    # -> "Cette entité n'est pas identifiée."
/rpgadmin npc tag guard              # -> "Entité identifiée : guard (Citizens NPC #N)"
/rpgadmin npc info                    # -> "Identifiant : guard (Citizens NPC #N)"
/npc tphere                           # placer le PNJ précisément
```

Puis, si besoin, depuis la page PNJ du panel : créer `npcs/guard.yml`, poser le `giver:` d'une
quête, etc.

## 9. Quel dialogue un PNJ porte-t-il vraiment ?

Le rattachement PNJ → dialogue se lit dans **cet ordre** :

1. le dialogue que la fiche **déclare** dans son champ `dialogue:` ;
2. à défaut seulement, le dialogue qui porte le **nom** du PNJ.

La convention « le dialogue s'appelle comme le PNJ » n'est donc que le défaut. Un PNJ peut
parfaitement porter un dialogue nommé autrement — `mira_cartographer` déclare
`rpgquest:mira_first_map` — et c'est **la fiche qui fait autorité**.

Conséquence pratique dans l'atelier IA : quand on demande un dialogue pour un PNJ qui en a
déjà un sous un autre nom, le panel **s'arrête et pose la question** avant d'appeler l'IA :

- **modifier le dialogue existant** — la proposition portera son identifiant, aucun second
  dialogue n'est créé ;
- **créer un nouveau dialogue et remplacer le lien** — l'ancien n'est ni supprimé ni délié,
  et il faut repointer la fiche du PNJ ensuite, sinon les joueurs continuent d'entendre
  l'ancien.

Il n'existe pas de troisième possibilité : un dialogue « supplémentaire mais non lié » ne
serait joignable par personne, et réapparaîtrait lui-même dans `/npcs` comme une entrée
sans définition.

## 10. Supprimer un PNJ (issue #226)

Depuis la fiche d'un PNJ, **Zone de danger → « Supprimer… »** ouvre un aperçu des
dépendances. Rien ne se supprime depuis la liste : l'aperçu est le seul point de départ.

L'aperçu montre les **cinq couches**, présentes *et* absentes — définition logique, liaison
RPGQuest ↔ Citizens, PNJ Citizens physique, dialogue lié, et les quêtes qui citent le PNJ
(donneur, `TALK_TO_NPC`, `DELIVER_ITEM_TO_NPC`). Puis il propose quatre opérations, de la
moins à la plus destructrice :

| | Opération | Ce qu'elle fait | Ce qu'elle ne fait pas |
|---|---|---|---|
| **A** | Supprimer la définition logique seulement | supprime `npcs/<id>.yml`, après sauvegarde serveur dans `npc-backups/` | ne touche ni au PNJ Citizens, ni à sa liaison, ni au dialogue |
| **B** | Délier le PNJ Citizens | retire la liaison (**réversible** : on peut relier) | ne supprime ni l'entité, ni la fiche, ni le dialogue |
| **C** | Supprimer le PNJ Citizens physique | détruit l'entité et retire sa liaison | ne supprime ni la fiche, ni le dialogue |
| **D** | Nettoyage complet | définition + liaison + entité | **jamais le dialogue** |

### Ce qui bloque

Une quête qui désigne encore le PNJ — donneur, cible d'un « parler à », ou **destinataire
d'une remise** — bloque la suppression de sa définition, en nommant les quêtes. Il faut
corriger ces références d'abord : retirer un donneur ou réaffecter un objectif est une
décision éditoriale, pas un nettoyage mécanique.

Une opération bloquée **n'a pas de bouton** : il n'y a rien à cliquer.

### Garde-fous

- **Permission dédiée** : supprimer un PNJ n'est pas accordé par le droit d'écriture.
  Détruire l'entité Citizens exige en plus le droit d'apparition de PNJ.
- **Confirmation tapée** : il faut retaper l'identifiant du PNJ, et l'identifiant numérique
  Citizens quand l'opération touche l'entité.
- **Jamais le mauvais Citizens** : le serveur confronte l'identifiant numérique à la
  liaison réelle, puis l'UUID de l'entité, avant de détruire quoi que ce soit.
- **Aucun dialogue n'est jamais supprimé.** Un dialogue partagé par deux fiches est signalé
  nommément dans l'aperçu.
- **Sans relevé `npc.list`, aucune opération n'est proposée** : une liste de dépendances
  vide ne veut pas dire « aucune dépendance » quand on n'a pas regardé. Rafraîchir le
  catalogue PNJ d'abord.
- **Asynchrone** : la demande part à l'agent, qui **revérifie les dépendances** au moment
  d'exécuter et peut refuser même si l'aperçu proposait l'opération. Le verdict se lit dans
  le journal d'actions.
- Rejouer la même opération est sans danger : une définition déjà absente ou une liaison
  déjà retirée renvoient « rien à faire » au lieu d'une erreur.

### Une entrée « sans définition »

Elle n'est pas un PNJ cassé : c'est la **trace d'une référence**. L'aperçu en dit la
provenance (un dialogue du même nom, un PNJ Citizens tagué, une quête qui le cite) et les
remèdes possibles. On ne supprime pas une entrée de catalogue — on supprime ou on corrige
*ce qui la crée*. Voir « Dialogue sans PNJ porteur » dans *Résoudre les problèmes de PNJ*.
