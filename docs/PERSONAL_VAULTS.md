# Coffres personnels (vaults) — socle, issue #104

Stockage personnel du joueur, partagé entre tous les Hubs : `UUID joueur + vault_id → inventaire`.
Proche d'un Ender Chest, mais géré par LodyQuests et **distinct** de l'Ender Chest vanilla (qui
n'est ni remplacé ni détourné).

## État livré (phase 1)

- **Socle seulement** : aucune commande, aucun bloc physique, aucune page PlugAdmin. Le joueur ne
  peut pas encore ouvrir de coffre personnel en jeu — c'est l'objet de #105 (coffres physiques des
  Hubs), puis #106 (capacité progressive) et #107 (salles de préparation).
- **Aucun accès distant depuis le Wild** : il n'existe volontairement pas de commande joueur.
- API interne : `PersonalVaultService#open(Player, VaultId)` (accessible via
  `RPGQuestBootstrap#personalVaultService()`), `PersonalVaultService#vaultIds(UUID)`.

## Modèle

| Élément | Règle |
|---|---|
| Propriétaire | UUID canonique du joueur (jamais le pseudo). |
| `VaultId` | Identifiant logique `[a-z0-9_]{1,32}` ; `vault_01` (`VaultId.DEFAULT`) est le seul utilisé par le MVP, plusieurs coffres par joueur sont prévus (`vault_02`…). |
| Identité du contenu | Aucune coordonnée, aucun monde, aucun Hub : deux coffres physiques de deux Hubs liés au même `vault_id` montreront le même contenu. |
| Capacité | 27 cases (3 rangées, `PersonalVaultService.DEFAULT_SLOTS`). Un contenu persisté plus grand garde sa taille (jamais tronqué, 54 au plus). |
| Format | `ItemStack[]` sérialisé par `backpack.ItemArraySerializer` (méta complète : nom, lore, enchantements, PDC), version du format dans `schema_version`. |

## Persistance

Table `personal_vaults` (schéma **V43**, SQLite et MariaDB — `BLOB` → `LONGBLOB`, clé
`(player_uuid, vault_id)` en `VARCHAR(191)`) :

```
player_uuid TEXT, vault_id TEXT, schema_version INTEGER, contents BLOB, updated_at TEXT
PRIMARY KEY (player_uuid, vault_id)
```

Une ligne n'existe qu'après la première fermeture du coffre. Repository : `PersonalVaultRepository`
(JDBC asynchrone sur l'exécuteur unique de `DatabaseManager`). La table est reprise par l'outil de
migration SQLite → MariaDB (#42, groupe « Coffres personnels ») ; aucune migration réelle n'est faite.

## Garanties anti-perte / anti-duplication

- **Un seul inventaire vivant** par (joueur, coffre) : ouvrir deux fois, ou depuis deux Hubs,
  réutilise la même ; deux ouvertures concurrentes n'en créent jamais deux.
- Sauvegarde à la **fermeture**, à la **déconnexion** (filet de sécurité si la fermeture n'a pas
  déjà sauvegardé, sans double écriture) et à l'**arrêt** du plugin (avant la fermeture de la base).
- Passer directement d'un coffre à un autre sauvegarde le premier.
- Tant que la base n'a pas confirmé une sauvegarde, l'instantané reste en mémoire et c'est lui que la
  réouverture suivante présente (jamais une version plus ancienne lue en base) ; en cas d'échec
  d'écriture il est réécrit à la fermeture suivante.
- Contenu illisible : ouverture **refusée** (`LOAD_FAILED`), ligne laissée intacte pour récupération
  par un administrateur — jamais écrasée par un coffre vide.
- Inventaire virtuel (aucun bloc) : aucun hopper ni autre joueur ne peut y accéder.

Limite connue (phase 1) : comme pour les backpacks, un crash brutal du serveur **pendant** qu'un
coffre est ouvert perd les mouvements faits depuis l'ouverture (rien n'est sauvegardé en continu).

## Tests

`PersonalVaultServiceTest` (isolation entre joueurs, plusieurs `vault_id`, objets complexes avec
PDC, redémarrage, déconnexion, arrêt, ouvertures concurrentes, contenu illisible) et
`PersonalVaultRepositoryTest` (clé composite, upsert, survie à la réouverture de la base).
