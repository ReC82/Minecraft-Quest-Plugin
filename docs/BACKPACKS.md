# Backpacks (sacs à dos persistants)

Un inventaire virtuel persistant par joueur, en trois paliers (Small,
Medium, Large), indépendant de l'inventaire vanilla (mission étape 20). Voir
[docs/ARCHITECTURE.md](ARCHITECTURE.md) (section `backpack`) pour le détail
d'implémentation.

## Paliers

Trois tailles configurables (`config.yml` → `backpacks:`), en lignes de 9
cases :

```yaml
backpacks:
  small-rows: 1     # 9 cases
  medium-rows: 3    # 27 cases
  large-rows: 6     # 54 cases
  forbidden-materials: []
  fallback-size: SMALL
  open-item-material: BUNDLE
```

`small-rows < medium-rows < large-rows` est obligatoire (validé au
démarrage).

## Comment un joueur obtient un backpack

Le palier effectif est résolu via `entitlement.EntitlementService`
(avantage `"backpack"`, premier consommateur concret de cette interface
générique — mission point 11) :

1. Un avantage explicite (`/backpack admin grant <joueur> <taille>`) fixe
   le palier, quel qu'il soit.
2. À défaut, la **permission de secours** `rpgquest.backpack.free`
   (accordée à tous par défaut) donne le palier `backpacks.fallback-size`
   (`SMALL` par défaut) — mission point 5.
3. Sans avantage ni permission de secours : aucun accès (`/backpack`
   répond « Tu n'as accès à aucun backpack pour l'instant. »).

## Ouverture

-   `/backpack` (`rpgquest.backpack`) — ouvre ton backpack.
-   Un objet dédié (`BackpackService#createOpenItem`, matériau
    configurable, identifié **uniquement** par PersistentDataContainer,
    jamais par son matériau ou son nom) : clic droit pour ouvrir.

## Anti-abus (mission point 7)

-   **Backpack dans backpack** : l'objet d'ouverture est lui-même détecté
    via sa PDC et refusé à l'entrée d'un backpack, quel que soit le
    matériau configuré.
-   **Objets explicitement interdits** : liste de matériaux configurable
    (`backpacks.forbidden-materials`), vide par défaut — un administrateur
    y ajoute par exemple les coffres à coquillage s'il veut fermer ce
    vecteur de contournement.
-   **Ouverture simultanée** : un joueur n'a jamais plus d'une instance
    d'inventaire vivante pour son backpack. Une seconde tentative
    d'ouverture (double clic rapide, ou une commande admin pendant qu'il
    est déjà ouvert) réutilise la même instance, ne recharge jamais une
    seconde copie depuis la base.
-   **Interaction non autorisée** : le backpack est un inventaire
    entièrement virtuel (jamais adossé à un bloc du monde) — aucun vecteur
    de vol par hopper/entonnoir n'existe par construction.

Seuls les vecteurs classiques (pose directe, échange avec le curseur,
shift-clic, échange de barre de raccourcis, glisser-déposer) sont
surveillés — l'édition du contenu interne d'un objet type paquet (bundle)
légitimement présent dans le backpack n'est pas auditée récursivement
(simplification assumée).

## Sauvegarde (mission point 8)

Le contenu est sérialisé (format binaire maison, voir
`backpack.ItemArraySerializer`) et sauvegardé :

-   à la fermeture du GUI (`InventoryCloseEvent`) ;
-   à la déconnexion (`PlayerQuitEvent`, filet de sécurité si la fermeture
    n'a pas eu lieu) ;
-   à l'arrêt du plugin (`BackpackService#stop` force la fermeture de tout
    backpack encore ouvert — cette fermeture déclenche la sauvegarde
    normale de façon synchrone sur le thread principal, mise en file sur
    l'exécuteur de base de données *avant* que celui-ci ne s'arrête, grâce
    à l'ordre LIFO d'arrêt des services).

Le format est **versionné** (`backpacks.schema_version`, distinct du
schéma SQL) : un futur changement du format binaire pourra migrer les
lignes existantes sans avoir à d'abord les décoder avec l'ancien format.

## Upgrade / downgrade (mission points 9/10)

Un changement de palier (via `/backpack admin grant`) redimensionne
toujours le contenu existant :

-   **Upgrade** : rien ne déborde jamais (plus de cases disponibles).
-   **Downgrade** : le contenu est compacté dans les cases restantes ; ce
    qui ne rentre plus part dans la **boîte de récupération**, jamais
    perdu.

Si le joueur a son backpack ouvert au moment du changement, il est d'abord
fermé (sauvegarde sous l'ancienne taille), puis redimensionné en base — le
joueur voit la nouvelle taille à la prochaine ouverture.

## Boîte de récupération

-   `/backpack recover` — liste les entrées en attente (raison, date).
-   `/backpack recover <numéro>` — réclame une entrée : les objets sont
    ajoutés à l'inventaire vanilla du joueur, et tout ce qui ne rentre pas
    est déposé au sol à ses pieds — jamais supprimé.

Une entrée de récupération est aussi créée si un contenu sauvegardé
s'avère illisible au chargement (corruption) : le joueur ne perd jamais
silencieusement son backpack, il le retrouve (partiellement, si le format
est irrécupérable) via cette même boîte. Chaque anomalie de ce type inscrit
aussi une ligne dans `backpack_audit` (mission, validation « toute anomalie
crée une entrée de récupération ou d'audit »).

## Commandes admin (mission point 5)

-   `/backpack admin grant <joueur> <taille>` (`rpgquest.admin`) — fixe
    l'avantage et redimensionne immédiatement.
-   `/backpack admin revoke <joueur>` (`rpgquest.admin`) — retire
    l'avantage explicite. Si le joueur garde un accès via la permission de
    secours, son backpack est redimensionné à `fallback-size` ; sinon, son
    contenu reste stocké tel quel (jamais touché) en attendant un nouvel
    octroi.

## Persistance

SQLite (migration V9) : `player_entitlements` (avantage générique),
`backpacks` (contenu courant), `backpack_overflow` (boîte de récupération),
`backpack_audit` (journal d'anomalies). Toute opération qui doit rester
cohérente (redimensionnement + surplus) passe par une seule transaction
JDBC explicite — un objet qui ne rentre plus après une réduction de taille
ne peut jamais simplement disparaître.
