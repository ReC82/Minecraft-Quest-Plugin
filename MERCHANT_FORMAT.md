# Format d'un marchand

Un fichier par marchand, dans `plugins/RPGQuest/merchants/*.yml` (un
exemple généré automatiquement au premier démarrage : `village_merchant`).
Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (section `economy`) pour
le détail de l'implémentation et des garanties anti-duplication.

Un marchand n'a **aucun lien direct à une entité PNJ** : il ne s'ouvre que
depuis une action de dialogue `OPEN_MERCHANT` (voir
[DIALOGUE_FORMAT.md](DIALOGUE_FORMAT.md) et `dialogues/merchant.yml`) —
c'est le dialogue qui identifie le PNJ (nom personnalisé de l'entité), pas
le marchand.

## Exemple

```yaml
id: rpgquest:village_merchant
title: "<gold>Marchand du village</gold>"   # MiniMessage, titre de l'inventaire

offers:
  # Le marchand vend au joueur (le joueur paie et reçoit l'objet)
  - direction: SELL_TO_PLAYER
    material: BREAD                # ou "custom-item: rpgquest:forest_blade"
    quantity: 4
    price: 3

  # Le marchand achète au joueur (le joueur donne l'objet et reçoit le paiement)
  - direction: BUY_FROM_PLAYER
    custom-item: rpgquest:spider_fang
    quantity: 4
    price: 10

  # Offre soumise à des conditions cumulatives (toutes optionnelles)
  - direction: SELL_TO_PLAYER
    custom-item: rpgquest:forest_blade
    quantity: 1
    price: 250
    required-permission: rpgquest.vip
    required-quest: rpgquest:first_steps
    required-quest-state: COMPLETED   # par défaut si omis : COMPLETED
    required-level: 5                  # niveau RPG GLOBAL (vanilla si progression.vanilla-xp-progression: true, #532)
```

## Nature du catalogue et fermeture (issue #17)

```yaml
id: rpgquest:comptoir_port
title: "<aqua>Comptoir du port</aqua>"
type: BUY          # SELL (boutique), BUY (rachat), MIXED (les deux), CRAFT (atelier #18) — absent = MIXED
enabled: false     # absent = true ; false = boutique fermée
offers:
  - direction: BUY_FROM_PLAYER
    material: COD
    quantity: 8
    price: 4
```

-   `type: SELL` n'accepte que des offres `SELL_TO_PLAYER` et des prix du
    catalogue de vente ou de fabrication ; `type: BUY` que des offres
    `BUY_FROM_PLAYER` et des prix de rachat. Une offre fixe dans le mauvais
    sens rejette le fichier ; un `price-ref` d'un autre type est affiché
    « Offre indisponible ».
-   `type: CRAFT` (atelier d'artisan, issue #18) n'accepte **que** des
    `price-ref` vers des prix de fabrication (`CRAFT`) : aucune offre à prix
    écrit, aucun prix de vente ou de rachat (affiché « Offre indisponible »).
    Voir « Atelier d'artisan » ci-dessous.
-   Un marchand de rachat ne rachète **que** les objets qu'il liste : il
    n'existe aucun rachat générique.
-   `enabled: false` : le dialogue répond « Cette boutique est fermée pour le
    moment » ; une vitrine déjà ouverte se ferme au clic suivant sans rien
    échanger.
-   Un même catalogue peut être ouvert par plusieurs PNJ (plusieurs
    dialogues avec la même action `OPEN_MERCHANT`). Pour un PNJ « mixte »,
    deux choix de dialogue : « Ouvrir la boutique » → catalogue `SELL`,
    « Vendre des objets » → catalogue `BUY`.
-   Ces fichiers se créent et se modifient depuis **PlugAdmin → Marchands**
    (`/merchants`), sans YAML. L'éditeur réécrit `id`, `title`, `type`,
    `enabled` et `offers` (les commentaires d'en-tête restent).

## Offre au prix du catalogue (`price-ref`, issue #336)

```yaml
offers:
  - price-ref: pain_x4                # clé d'un prix de PlugAdmin → Prix et coûts
  - price-ref: epee_forgee            # un prix CRAFT : fabrication (pièces + ingrédients)
    required-quest: rpgquest:first_steps
```

-   Sens, objet, quantité et montant viennent du catalogue `prices.yml`
    (voir `docs/ECONOMY.md`) ; `direction`, `material`, `custom-item`,
    `quantity` et `price` sont alors **interdits** dans l'offre.
-   Les conditions `required-*` restent propres à l'offre.
-   Prix absent ou désactivé : l'offre est affichée « Offre indisponible »
    et ne s'échange pas — jamais un montant par défaut.
-   Exemple embarqué : `merchants/atelier_demo.yml`.

## Atelier d'artisan (`type: CRAFT`, issue #18)

```yaml
id: rpgquest:forge_village
title: "<gold>Forge du village</gold>"
type: CRAFT
offers:
  - price-ref: pain_cuit        # recette à ingrédients seuls (coins: 0)
  - price-ref: lanterne         # recette à pièces seules (aucun ingrédient)
  - price-ref: epee_forgee      # ingrédients + pièces
    required-level: 5
```

-   Chaque recette est un prix `CRAFT` de **PlugAdmin → Prix et coûts** :
    résultat et quantité, ingrédients exacts (objets Minecraft ou RPGQuest,
    reconnus par PDC) et/ou montant en pièces. Une recette sans ingrédient
    **ni** pièce est refusée.
-   Vitrine : chaque recette affiche, pour le joueur qui la regarde, chaque
    ingrédient avec ce qu'il possède (✔ / ✘) et, une fois son solde lu, les
    pièces qui lui manquent. Au clic, tous les ingrédients manquants sont
    listés (« 1 × IRON_INGOT (tu en as 1 sur 2) ») ; fonds insuffisants →
    montant exact manquant. Rien n'est pris tant que tout n'est pas réuni.
-   Sécurité : place vérifiée (ingrédients retirés compris) avant le débit ;
    pièces débitées d'abord, puis — sur le thread principal — ingrédients
    revérifiés, retirés et objet remis en une passe ; tout échec rembourse
    les pièces seules. Double-clic : une seule fabrication.
-   Le catalogue se crée dans **PlugAdmin → Marchands** (nature « Atelier
    d'artisan ») et se relie au PNJ par l'action de dialogue « Ouvrir un
    marchand ». Exemple embarqué : `merchants/artisan_demo.yml` (recettes
    `demo_recette_ingredients`, `demo_recette_pieces`, `demo_recette_mixte`
    à créer dans Prix et coûts).

## Ouvrir la vitrine depuis un dialogue

```yaml
choices:
  - text: "Voir la boutique"
    actions:
      - type: OPEN_MERCHANT
        merchant: rpgquest:village_merchant
```

## Validation

-   `id`, `title`, `offers` (≥ 1 entrée) sont obligatoires.
-   Chaque offre : soit `price-ref` (voir plus haut), soit `direction` (`SELL_TO_PLAYER`/`BUY_FROM_PLAYER`),
    exactement un de `material` ou `custom-item`, `quantity` (entier
    strictement positif) et `price` (entier ≥ 0, la monnaie du plugin est
    toujours un nombre entier — voir `docs/ECONOMY.md`) sont obligatoires.
-   `required-permission`, `required-quest` (+ `required-quest-state`
    optionnel, `COMPLETED` par défaut), `required-level` sont optionnels et
    cumulatifs : toutes les conditions présentes doivent être satisfaites.
-   Un `custom-item:` référencé qui ne correspond à aucune définition
    chargée n'est pas rejeté au chargement du marchand (contrairement à un
    `custom-item:` de recette) : l'offre est simplement ignorée à
    l'affichage de la vitrine (avertissement au log), pour ne pas coupler
    le chargement des marchands à celui des objets.
-   Un `id` de marchand dupliqué entre fichiers rejette les deux fichiers
    concernés.

## Vitrine et échange

-   Chaque clic (gauche ou droit, peu importe) sur une offre affichée
    déclenche l'échange complet défini par l'offre (quantité et prix fixes,
    pas de sélection de quantité).
-   Toutes les offres sont toujours affichées ; une condition non remplie
    ne masque pas l'offre, elle bloque seulement l'achat/la vente au clic
    avec un message explicite.
-   Au-delà de 45 offres (vitrine à 6 lignes, la maximale), les offres
    excédentaires ne sont pas affichées — limitation connue, voir
    `docs/ARCHITECTURE.md`. L'éditeur PlugAdmin refuse plus de 45 offres.
-   La dernière ligne affiche le **solde** du joueur (rafraîchi après
    chaque échange ; purement informatif, le débit revérifie le solde).
-   Si le marchand a été modifié (PlugAdmin, `/merchant reload`) depuis
    l'ouverture de la vitrine, le clic est refusé, rien n'est échangé et la
    vitrine est réaffichée : le joueur n'échange jamais une offre qu'il n'a
    pas vue.
-   Audit : `transactions.context` commence par `marchand:<id>`.
