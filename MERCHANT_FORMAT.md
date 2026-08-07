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
    required-level: 5                  # niveau d'expérience vanilla (Player#getLevel())
```

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
-   Chaque offre : `direction` (`SELL_TO_PLAYER`/`BUY_FROM_PLAYER`),
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
    `docs/ARCHITECTURE.md`.
