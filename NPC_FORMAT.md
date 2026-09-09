# Format d'une définition logique de PNJ

Un fichier par PNJ, dans `plugins/RPGQuest/npcs/*.yml`. C'est la **V2
déclarative** du système PNJ : une définition logique existe indépendamment
de Citizens, du monde et du binding physique — on peut préparer toute la
configuration RPGQuest d'un PNJ **avant** qu'il n'existe en jeu.

```yaml
id: woodcutter_bob            # obligatoire ; minuscules, chiffres, « . _ - » (fragment de clé)
display_name: "Bûcheron Bob"  # obligatoire ; MiniMessage autorisé
description: "Bûcheron du village."   # optionnel
dialogue: rpgquest:woodcutter_bob    # optionnel ; « namespace:clé » ou une clé simple (→ rpgquest:clé)
role: quest_giver             # optionnel ; minuscules, « _ - », max 32 — purement informatif
enabled: true                 # optionnel ; true par défaut
```

## Champs

| Champ | Obligatoire | Détail |
|---|---|---|
| `id` | oui | Identifiant logique stable. Sert de nom de fichier (`<id>.yml`) quand la définition est créée depuis le Control Panel. **Jamais renommé** par l'édition (cascades de références). |
| `display_name` | oui | Nom affiché, MiniMessage. Max 128 caractères, pas de saut de ligne. |
| `description` | non | Note libre pour l'admin. |
| `dialogue` | non | Dialogue *déclaré* pour ce PNJ. Une clé simple est normalisée en `rpgquest:<clé>`. S'il ne correspond à aucun dialogue chargé → anomalie `DIALOGUE_MISSING`. |
| `role` | non | Classification libre (`quest_giver`, `merchant`, `guide`…). Aucun effet mécanique. |
| `enabled` | non | `false` = définition conservée mais marquée désactivée (`DISABLED`). |

## Définition logique vs binding Citizens

La **définition** (`npcs/*.yml`) ne crée aucune entité. Le **binding
Citizens** (table `npc_citizens_bindings`) lie un PNJ Citizens à cet `id`. Il
se pose de trois façons :

- en jeu par `/rpgadmin npc tag <id>` ;
- depuis le Control Panel, en **liant un PNJ Citizens déjà créé**
  (`/npcs` → « Lier un PNJ Citizens existant », action `npc.citizens.link` —
  issue #81 phase 1 ; jamais de spawn ni de rebind) ;
- depuis le Control Panel, en **créant physiquement le PNJ Citizens** à partir
  de la définition puis en le liant (`/npcs` → « Créer le PNJ Citizens »,
  action `npc.citizens.create` — issue #81 phase 2 ; nom = `display_name`,
  monde de la liste blanche RPGQuest, position bornée ; rollback du PNJ créé si
  la liaison échoue).

Les deux couches restent indépendantes :

| Définition | Binding Citizens | État |
|---|---|---|
| oui | oui | `LINKED` |
| oui | non | `NOT_LINKED` (« à lier ») |
| oui (`enabled: false`) | — | `DISABLED` |
| non | oui | `CITIZENS_ORPHAN` (erreur : binding sans définition) |
| non | non (mais référencé par une quête / un dialogue) | `UNDEFINED_REFERENCE` (erreur de contenu) |
| non | non — PNJ Citizens réel, jamais tagué | `CITIZENS_ONLY` (information : PNJ d'ambiance, hors RPGQuest) |
| — | — + dialogue manquant / doublon | `BROKEN` |

L'état `CITIZENS_ONLY` n'est pas produit par `NpcCatalog` (qui ne lit pas le
registre Citizens) : c'est le **Control Panel** qui, sur la page `/npcs`,
raccroche chaque PNJ du registre Citizens (`npc.citizens.list`) sans binding ni
définition, pour qu'un PNJ créé directement en jeu (`/npc create …`) reste
**visible et rattachable** au lieu d'être silencieusement absent (issue #101).
Un PNJ purement décoratif peut légitimement rester dans cet état.

## Références (source canonique)

À terme, `NpcDefinition` est la **source de vérité** des ids PNJ. Les autres
systèmes doivent référencer un id **défini** :

- `giver:` dans une quête (voir [QUEST_FORMAT.md](QUEST_FORMAT.md)) ;
- objectif `TALK_TO_NPC` (`npc:`) ;
- id d'un dialogue `rpgquest:<id>` (convention des listeners d'interaction).

Pendant la transition, un id référencé **sans** définition est signalé comme
**erreur de contenu** (`NO_DEFINITION`) dans le Control Panel (`/npcs`) mais
ne casse rien : les anciens contenus continuent de fonctionner. Migrer =
créer la définition (fichier, ou bouton « Créer la définition » du panel).

## Rechargement

`YamlNpcEngine` charge le dossier au démarrage. Les créations / éditions
faites via le Control Panel (`npc.definition.create` / `npc.definition.update`)
rechargent automatiquement le registre. Aucun listener n'est rebranché (les
définitions sont de pures données).

## Validation

- `id` et `display_name` obligatoires ; `id` doit matcher `[a-z0-9._-]{1,64}`.
- `dialogue` : `namespace:clé` ou clé simple ; sinon rejeté.
- `role` : `[a-z0-9_-]{1,32}` ; sinon rejeté.
- `enabled` : booléen strict.
- `id` dupliqué entre deux fichiers → les deux sont rejetés.
- Un fichier invalide est rejeté seul ; les autres continuent de charger.
