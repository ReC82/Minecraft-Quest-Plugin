# Guide pour une IA : écrire une histoire importable dans RPGQuest

Ce guide se suffit à lui-même : aucun accès au code ni au serveur n'est nécessaire. Il explique
comment transformer une **fiche narrative** (texte libre d'un auteur) en **un seul fichier JSON**
que l'administrateur dépose dans PlugAdmin (page **Import contenu**).

RPGQuest est un plugin de quêtes pour un serveur Minecraft. Le fichier que vous produisez décrit
des **personnages**, une **story** (suite ordonnée de quêtes), des **quêtes** et des **dialogues**.
Le serveur sait déjà jouer tout cela : vous ne programmez rien, vous **décrivez**.

> La seconde partie de ce document (« Contrat de contenu ») est générée automatiquement depuis le
> moteur : c'est la liste **exhaustive** des objectifs, récompenses, actions et conditions qui
> existent. Ce qui n'y figure pas n'existe pas. Ses exemples sont écrits en YAML : le JSON attendu
> utilise **exactement les mêmes clés**.

---

## 1. Le fichier à produire

Un seul objet JSON, sans commentaire, de cette forme :

```json
{
  "format": "lodyquests-content-pack",
  "schemaVersion": 1,
  "metadata": { "title": "…", "author": "…", "generator": "…", "notes": "…", "synopsis": "…", "placements": "…" },
  "content": {
    "quests":    [ … ],
    "stories":   [ … ],
    "dialogues": [ … ],
    "npcs":      [ … ]
  }
}
```

- `format` et `schemaVersion` sont obligatoires et **exactement** ces valeurs.
- `metadata` est purement informatif (jamais utilisé par le jeu). Utilisez-le pour :
  `synopsis` = le récit original résumé ; `placements` = où doivent se trouver les personnages ;
  `notes` = **tout ce qui reste à valider par l'auteur** (« À VALIDER : … »).
- Aucune autre clé à la racine. Aucune autre famille dans `content`.

## 2. Comment les éléments se relient

```
story ──(liste ordonnée)──▶ quêtes ──(objectifs « npc »)──▶ personnages (npcs)
                              ▲                                   │
                              │                       dialogue de même nom
                              └──── START_QUEST ◀── dialogues ◀───┘
```

- **Personnage (npc)** : identité logique (`id`, nom affiché, description). **Jamais de
  position.** Son placement physique dans le monde est fait plus tard par l'administrateur.
- **Dialogue** : son `id` est **toujours** `rpgquest:` + l'id du personnage qui parle
  (personnage `lily` → dialogue `rpgquest:lily`). C'est ainsi que le jeu ouvre le bon dialogue.
- **Quête** : des étapes successives, chaque étape contient un ou plusieurs objectifs à réaliser
  (tous, dans n'importe quel ordre). Quand la dernière étape est faite, la quête est terminée et
  les récompenses sont versées automatiquement.
- **Déblocage** : une quête s'ouvre quand toutes ses `prerequisites` sont terminées. Dans une
  story, chaque quête a pour prérequis la quête précédente.
- **Story** : la liste ordonnée des quêtes (même ordre que les prérequis). Elle ne contient
  aucun objectif.
- **Qui propose la quête ?** Le dialogue du personnage, avec l'action `START_QUEST`. Le champ
  `giver` d'une quête est facultatif : il indique simplement quel personnage la propose (aide
  visuelle et journal du joueur).

## 3. Identifiants

| Élément | Forme | Exemple |
|---|---|---|
| Quête | `rpgquest:` + clé | `rpgquest:lily_repas` |
| Dialogue | `rpgquest:` + id du personnage | `rpgquest:lily` |
| Personnage | clé seule | `lily` |
| Story | clé seule | `lily_souvenirs` |
| Étape, nœud de dialogue | clé seule | `nourrir`, `offre_repas` |

Une **clé** : minuscules sans accent, chiffres et `_`, sans espace (ex. `la_vieille_recette`).
Chaque identifiant est **unique** dans le fichier. Préfixez les clés des quêtes par le nom de
l'histoire ou du personnage (`lily_…`) pour éviter de heurter un contenu existant.
N'utilisez l'identifiant d'un personnage **existant** que si la fiche narrative le donne.

## 4. Une quête

```json
{
  "id": "rpgquest:lily_repas",
  "title": "Un repas pour Lily",
  "description": "Lily n'a rien mangé depuis sa fuite. Apporte-lui du pain et des pommes.",
  "category": "lily",
  "icon": "BREAD",
  "repeatable": false,
  "secret": false,
  "giver": "lily",
  "prerequisites": [],
  "steps": [
    { "id": "nourrir", "objectives": [
      { "type": "DELIVER_ITEM_TO_NPC", "npc": "lily", "material": "BREAD", "amount": 3 }
    ] }
  ],
  "rewards": [ { "type": "EXPERIENCE", "amount": 20 } ]
}
```

Obligatoires : `id`, `title`, `description`, `category` (un mot, souvent le nom de l'histoire),
au moins une étape avec au moins un objectif. `icon` : un objet Minecraft (voir § 7).

## 5. Traduire les souhaits de l'auteur en objectifs

| L'auteur écrit… | Objectif à utiliser | Attention |
|---|---|---|
| « apporter / donner / remettre X à Lily » | `DELIVER_ITEM_TO_NPC` (npc, material, amount) | Les objets sont **réellement retirés** de l'inventaire, dans le dialogue de Lily. Ajouter la branche de remise (§ 6). |
| « parler à Julius », « aller voir Julius » | `TALK_TO_NPC` (npc) | Réussi dès que le joueur clique sur le personnage. |
| « goûter / discuter ensuite avec Lily » | une **étape suivante** avec `TALK_TO_NPC` vers ce personnage | |
| « tuer des zombies » | `KILL_ENTITY` (entity, amount) | |
| « fabriquer une épée » | `CRAFT_ITEM` (material, amount) | Recettes **Minecraft existantes** seulement. |
| « cuire du fer » | `SMELT_ITEM` (material = ce qui **sort** du four) | |
| « casser / poser des blocs » | `BREAK_BLOCK` / `PLACE_BLOCK` | |
| « ramasser » | `COLLECT_ITEM` | Compte un objet **ramassé au sol**, pas un objet possédé. |
| « aller à tel endroit » | `REACH_LOCATION` | **Seulement** avec de vraies coordonnées fournies par l'auteur. Sinon : `TALK_TO_NPC` vers le personnage qui s'y trouve, et une note « À VALIDER ». |

**Interdits** : inventer un type, une recette personnalisée (« tarte de maman » n'est pas un
objet), un objet qui n'existe pas dans Minecraft, une coordonnée, un monde ou une ville.
Posséder un objet n'est pas le remettre : pour « apporter », c'est toujours `DELIVER_ITEM_TO_NPC`.

Exemple de traduction honnête : « préparer la tarte de sa mère » → l'auteur ne demande pas de
recette ; le personnage cuisine dans le récit, le joueur **remet** les ingrédients (et du
charbon), puis **reparle** au personnage. Si l'auteur veut que le joueur fabrique la tarte :
`CRAFT_ITEM` avec `PUMPKIN_PIE` (recette vanilla).

## 6. Les dialogues

```json
{
  "id": "rpgquest:lily",
  "start": "accueil",
  "nodes": {
    "accueil": {
      "speaker": "Lily",
      "text": "Bonjour…",
      "choices": [
        { "text": "Je peux t'aider ?",
          "conditions": [ { "type": "QUEST_AVAILABLE", "quest": "rpgquest:lily_repas" } ],
          "next": "offre_repas" },
        { "text": "Au revoir", "actions": [ { "type": "CLOSE" } ] }
      ]
    }
  }
}
```

- `nodes` est un **objet** dont les clés sont les identifiants des nœuds (jamais une liste).
- Chaque nœud : `speaker`, `text` (512 caractères maximum), `choices`.
- Un choix : `text`, puis facultativement `conditions`, `actions` (exécutées dans l'ordre),
  `next` (nœud suivant). Un choix sans `next` ferme la conversation.
- Un choix dont **une** condition est fausse **n'est pas affiché**. `"negate": true` inverse une
  condition.
- Chaque nœud doit offrir une sortie (un choix avec `CLOSE`, ou un retour).

**Modèles à réutiliser** (copiez-les depuis `exemple-pack-lily.json`) :

1. **Proposer une quête** — choix conditionné par `QUEST_AVAILABLE` (vrai seulement si la quête
   est débloquée et pas encore faite) → nœud d'offre → choix « Commencer la quête : … » avec
   `START_QUEST` puis `CLOSE`, et un choix « Pas maintenant ». Ainsi, un personnage ne propose
   **jamais** une quête terminée ou verrouillée.
2. **Remettre des objets** — choix conditionné par `HAS_PENDING_DELIVERY` → nœud dont le texte
   contient `%delivery_status%` (le jeu y écrit ce qui reste à donner) → choix avec l'action
   `DELIVER_QUEST_ITEMS` → nœud `apres_remise`. Une seule branche suffit pour toutes les quêtes
   du personnage.
3. **Pendant la quête** — choix conditionné par `QUEST_STATE` = `ACTIVE`.
4. **Après la quête** — choix conditionné par `QUEST_STATE` = `COMPLETED`.

Textes : français, tutoiement si l'auteur le souhaite. `<newline>` force un retour à la ligne.
Les balises de couleur MiniMessage (`<gold>…</gold>`) sont permises, pas obligatoires.

## 7. Objets, créatures et icônes

Les objets sont des **identifiants Minecraft en anglais majuscules** :
pain `BREAD`, pomme `APPLE`, coquelicot `POPPY`, pissenlit `DANDELION`, citrouille `PUMPKIN`,
sucre `SUGAR`, œuf `EGG`, blé `WHEAT`, charbon `COAL`, tarte à la citrouille `PUMPKIN_PIE`,
bûche de chêne `OAK_LOG`, cuir `LEATHER`, lingot de fer `IRON_INGOT`, émeraude `EMERALD`,
livre `BOOK`, livre et plume `WRITABLE_BOOK`. Créatures : `ZOMBIE`, `SKELETON`, `SPIDER`…
En cas de doute sur un nom exact, choisissez un objet courant et notez-le dans `metadata.notes`.

## 8. Personnages

```json
{ "id": "lily", "displayName": "Lily",
  "description": "Qui elle est. Placement : village central — à lier à un PNJ Citizens.",
  "dialogue": "rpgquest:lily", "role": "quest_giver", "enabled": true }
```

Aucune autre clé (pas de position, de monde, de skin). Écrivez dans `description` où le
personnage doit se trouver ; l'administrateur le placera.

## 9. Récompenses

Modestes : `EXPERIENCE` (20 à 80 par quête), `ITEM` (objet Minecraft), `MONEY` (pièces),
`VARIABLE` (repère pour une suite, ex. `LILY_JULIUS_RETROUVE` = `true`). Pas de `COMMAND`
sauf demande explicite de l'auteur.

## 10. Checklist avant de répondre

- [ ] `format` = `lodyquests-content-pack`, `schemaVersion` = `1`.
- [ ] Chaque quête : id `rpgquest:…`, titre, description, catégorie, ≥ 1 étape, ≥ 1 objectif.
- [ ] Chaque quête de la story (sauf la première) a la précédente en `prerequisites`.
- [ ] La story liste toutes ses quêtes, dans l'ordre.
- [ ] Chaque `npc` cité par un objectif existe dans `npcs` (ou est un personnage existant donné
      par la fiche).
- [ ] Chaque personnage qui parle a un dialogue `rpgquest:<son id>`, et `"dialogue"` y renvoie.
- [ ] Chaque quête est proposée par un `START_QUEST` dans un dialogue, derrière
      `QUEST_AVAILABLE`.
- [ ] Toute quête avec `DELIVER_ITEM_TO_NPC` a sa branche de remise chez ce personnage.
- [ ] Tous les `next` pointent vers un nœud existant ; chaque nœud a une sortie.
- [ ] Aucun type, objet, coordonnée ou recette inventé ; les manques sont dans `metadata.notes`.
- [ ] Réponse = le JSON seul.

## 11. Ce que fait PlugAdmin ensuite (pour information)

L'administrateur dépose le JSON sur **Import contenu**. PlugAdmin l'analyse avec les validateurs
du jeu, affiche chaque élément, refuse tout identifiant déjà utilisé sans décision explicite, et
n'enregistre rien avant confirmation. Un seul élément invalide bloque tout l'import : rien n'est
créé à moitié. La publication vers le serveur de jeu est une étape séparée.

---

