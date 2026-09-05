# Permissions granulaires (rôle / monde / action)

Issue #27. Objectif : **déléguer des capacités précises sans donner `op`**.
Donner `op` à un contributeur pour qu'il construise dans un Hub lui donnait
aussi le droit de casser des blocs dans le monde des claims, d'utiliser
toutes les commandes `/rpgadmin` sans rapport avec sa mission, etc. Ce
document décrit l'arbre de permissions RPGQuest et des profils de rôles
recommandés.

Tout repose sur le système standard Bukkit/Paper (`hasPermission`,
permissions déclarées dans `plugin.yml`). Un gestionnaire externe type
**LuckPerms** fonctionne sans configuration supplémentaire mais **n'est
jamais obligatoire** : `op`, `permissions.yml` du serveur ou tout autre
gestionnaire conviennent aussi.

Principe : **tout ce qui n'est pas explicitement accordé est refusé.** Les
nœuds ci-dessous sont `default: false` sauf mention contraire.

---

## 1. Vue d'ensemble de l'arbre

```
rpgquest.admin.world            (default: op)  ── parapluie : garde TOUT
├── rpgquest.build.*            (default: op)  ── construire partout (jamais de bypass claim)
│   ├── rpgquest.build.hub.*                   ── tous les Hubs
│   │   └── rpgquest.build.hub.<id>            ── un Hub précis (ex. hub.0)
│   ├── rpgquest.build.wild                    ── structures admin dans le Wild
│   ├── rpgquest.build.zone                    ── build/interaction dans toute zone protégée
│   └── rpgquest.build.world.<clé>             ── un monde spécialisé (ex. world.claims)
├── rpgquest.claim.bypass       (default: op)  ── ignorer la protection d'un claim joueur
├── rpgquest.claim.admin        (default: op)  ── /claim admin (suppression d'un claim tiers…)
├── rpgquest.admin.flatten                     ── /rpgadmin flatten
├── rpgquest.admin.zone                        ── /rpgadmin zone
├── rpgquest.admin.portal                      ── /rpgadmin portal + /rpgadmin worldportal
├── rpgquest.admin.mob                         ── /rpgadmin mob
├── rpgquest.admin.npc                         ── /rpgadmin npc tag|untag|info
├── rpgquest.admin.spawn                       ── /rpgadmin spawn
├── rpgquest.admin.worlds                      ── /rpgadmin world create|tp|list
├── rpgquest.admin.waystone                    ── /rpgadmin waystone
├── rpgquest.admin.story                       ── /rpgadmin story
├── rpgquest.admin.player                      ── /rpgadmin player resetnew (destructif)
└── rpgquest.admin.guide                       ── /rpgadmin guide (lecture seule)
```

`rpgquest.admin.world` porte **tous** les nœuds ci-dessus en enfants : un
**OP**, ou un rôle existant à qui `rpgquest.admin.world` avait été accordé,
conserve exactement le même accès qu'avant l'issue #27. Aucune migration
n'est nécessaire.

---

## 2. Séparation build ⁄ claims — garantie centrale

**Aucune** permission `rpgquest.build.*` (à quelque niveau que ce soit)
n'accorde le droit de contourner la protection d'un **claim joueur**. Le
contournement d'un claim passe uniquement par `rpgquest.claim.bypass`
(ou `rpgquest.admin.world`).

Conséquences vérifiées par les tests automatisés :

| Le joueur a… | Peut construire dans le Hub 0 | Peut casser un bloc dans un claim de `world_claim` | Peut construire hors claim dans le monde des claims |
|---|---|---|---|
| `rpgquest.build.hub.0` | ✅ | ❌ | ❌ |
| `rpgquest.build.wild` | ❌ | ❌ | ❌ |
| `rpgquest.build.*` | ✅ | ❌ | ✅ |
| `rpgquest.claim.bypass` seul | ❌ (pas une permission de build) | ✅ | ❌ |
| `rpgquest.admin.world` / OP | ✅ | ✅ | ✅ |

---

## 3. Permissions de build par monde ⁄ Hub

La protection de monde (`hub.HubWorldProtectionListener`),
la protection de zone (`zone.ZoneProtectionListener`) et la règle « pas de
construction hors claim dans le monde des claims »
(`claim.ClaimsWorldRulesListener`) consultent toutes le même service,
`permission.BuildPermissionService`.

### Résolution du monde → nœud de permission

`config.yml` → section `permissions.build-areas` mappe un **nom de monde**
vers une **zone de build** :

```yaml
permissions:
  build-areas:
    world_hub: hub.0          # -> rpgquest.build.hub.0
    world_hub_arene: hub.arena # -> rpgquest.build.hub.arena
    build_staging: world.staging # -> rpgquest.build.world.staging
```

Valeurs acceptées : `hub.<id>`, `wild`, `world.<clé>` (ou une clé nue,
traitée comme `world.<clé>`).

**Sans entrée explicite**, RPGQuest applique déjà des valeurs par défaut à
partir des noms de mondes qu'il connaît déjà :

| Monde | Zone de build par défaut | Nœud |
|---|---|---|
| `hub.world` (défaut `world_hub`) | `hub.0` | `rpgquest.build.hub.0` |
| `travel.wild-world` (défaut `wild`) | `wild` | `rpgquest.build.wild` |
| `claims.world` (défaut `claims`) | `world.claims` | `rpgquest.build.world.claims` |

Ajouter un **Hub supplémentaire** ne demande donc que :

1. une ligne dans `permissions.build-areas` (`mon_monde: hub.2`) ;
2. le nœud `rpgquest.build.hub.2` côté gestionnaire de permissions
   (LuckPerms, ou une entrée dans `permissions.yml` du serveur si on reste
   en Bukkit pur — voir §6).

Aucun changement de code.

### Ordre d'évaluation (`BuildPermissionService#mayBuild`)

1. `rpgquest.admin.world` **ou** `rpgquest.build.*` → autorisé partout ;
2. sinon, selon la zone du monde :
   - Hub → `rpgquest.build.hub.*` ou `rpgquest.build.hub.<id>` ;
   - Wild → `rpgquest.build.wild` ;
   - monde spécialisé → `rpgquest.build.world.<clé>` ;
   - monde non répertorié → aucune permission de build RPGQuest ne s'y
     applique (les protections propres à ce monde, s'il y en a, décident
     seules).

### Zones protégées

`rpgquest.build.zone` ouvre la construction/interaction dans **toute** zone
protégée. Un `builder-hub-0` sans ce nœud peut quand même éditer une zone
**située dans le Hub 0** (le service reconnaît que la zone est dans un monde
où il a le droit de construire) ; il ne peut pas toucher une zone du Wild
sans `rpgquest.build.wild`. Les règles de **combat** d'une zone (PvP, dégâts
PNJ) ne sont jamais contournées par une permission de build — seul
`rpgquest.admin.world` les outrepasse.

### Mode créatif

RPGQuest ne fait **pas** passer un joueur en créatif. Un builder utilise le
créatif via `op`, un plugin de permissions (`minecraft.command.gamemode`
ciblé) ou EssentialsX. RPGQuest se contente d'autoriser la casse/pose dans
la zone permise — sans donner les autres commandes admin du serveur.

---

## 4. Profils de rôles recommandés

Exemples ; RPGQuest n'impose aucun système de rôles interne. Notation
LuckPerms, transposable à tout gestionnaire.

### Builder Hub 0

```
/lp group create builder-hub-0
/lp group builder-hub-0 permission set rpgquest.build.hub.0 true
```

Construit dans le Hub 0. Aucun droit sur les claims, l'économie, le reset
joueur, les quêtes ou les PNJ.

### Builder Wild

```
/lp group create builder-wild
/lp group builder-wild permission set rpgquest.build.wild true
```

Construit des structures administratives dans le Wild. **Aucun** bypass de
claim.

### NPC editor

```
/lp group create npc-editor
/lp group npc-editor permission set rpgquest.admin.npc true
# + les permissions Citizens si Citizens est installé (voir §5) :
/lp group npc-editor permission set citizens.npc.create true
/lp group npc-editor permission set citizens.npc.move true
/lp group npc-editor permission set citizens.npc.select true
/lp group npc-editor permission set citizens.npc.remove true
```

Crée/déplace/configure les PNJ et lie leur identité RPGQuest
(`/rpgadmin npc tag|untag|info`). Pas de build global, pas d'accès aux
opérations destructives joueur.

> **Note** : l'outil de sélection cuboïde est partagé — `/rpgadmin zone wand`
> (nœud `rpgquest.admin.zone`) — et sert aussi à `/rpgadmin portal create`.
> Un éditeur de portails a donc besoin de `rpgquest.admin.zone` **en plus**
> de `rpgquest.admin.portal` pour récupérer la baguette.

### Quest / Content editor

```
/lp group create content-editor
/lp group content-editor permission set rpgquest.admin.story true
/lp group content-editor permission set rpgquest.admin.zone true
/lp group content-editor permission set rpgquest.admin.portal true
```

Gère le contenu concerné (adapter la liste). Pas de droits serveur globaux,
pas de `rpgquest.admin.player`.

### Tester

```
/lp group create tester
/lp group tester permission set rpgquest.admin.mob true
/lp group tester permission set rpgquest.admin.waystone true
```

Permissions de test ciblées uniquement.

### Admin monde complet

`rpgquest.admin.world` (ou `op`). Garde tout, y compris le bypass des
claims.

---

## 5. Intégration Citizens

RPGQuest **ne wrappe aucune commande Citizens**. La création/suppression/
déplacement d'un PNJ Citizens passe par les commandes **de Citizens**
(`/npc …`), protégées par les permissions **de Citizens**
(`citizens.npc.*`). RPGQuest n'ajoute qu'une couche d'**identité logique**
(`/rpgadmin npc tag|untag|info`, nœud `rpgquest.admin.npc`) et l'écoute des
interactions/dégâts sur les entités Citizens.

Un NPC editor a donc besoin, en plus de `rpgquest.admin.npc`, des
permissions Citizens correspondantes — accordées via le même gestionnaire.
Citizens reste une intégration **optionnelle** : sans Citizens, les entités
vanilla marquées via `/rpgadmin npc tag` continuent de fonctionner et seul
`rpgquest.admin.npc` est requis.

---

## 6. Bukkit pur (sans gestionnaire de permissions)

Les nœuds « courants » sont déclarés dans `plugin.yml` et donc accordables
tels quels (`permissions.yml` du serveur, ou plugin de permissions). Les
nœuds **dynamiques** non énumérables — `rpgquest.build.hub.<id>` pour un id
autre que `0`, `rpgquest.build.world.<clé>` — doivent être déclarés
explicitement si l'on reste en Bukkit pur. Exemple `permissions.yml` :

```yaml
rpgquest.build.hub.2:
  default: false
builder-hub-2:
  default: false
  children:
    rpgquest.build.hub.2: true
```

Avec LuckPerms (ou équivalent), aucune déclaration préalable n'est
nécessaire : tout nœud peut être accordé directement.

---

## 7. Récapitulatif des nœuds joueurs (rappel, hors #27)

Non modifiés par l'issue #27, `default: true` :
`rpgquest.quest`, `rpgquest.item`, `rpgquest.money`, `rpgquest.market`,
`rpgquest.claim`, `rpgquest.progression`, `rpgquest.backpack`,
`rpgquest.backpack.free`. `rpgquest.admin` (`default: op`) couvre encore
`/rpgquest reload`, `/quest admin`, `/customitem give|list`, etc. — hors
périmètre de l'issue #27, qui ne traite que de l'administration du monde et
de la construction.
