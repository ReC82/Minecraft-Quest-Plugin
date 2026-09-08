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
