# Économie et marchands PNJ

Portefeuille par joueur, paiements entre joueurs, et marchands PNJ reliés
au système de dialogues existant. Voir
[docs/ARCHITECTURE.md](ARCHITECTURE.md) (section `economy`) pour le détail
de l'implémentation, et [MERCHANT_FORMAT.md](../MERCHANT_FORMAT.md) pour le
format YAML d'un marchand.

## Monnaie

-   Un seul solde entier (`long`) par joueur — pas de virgule flottante,
    pas de devises multiples.
-   Aucun solde de départ : un portefeuille naît à `0` au premier contact
    (débit, crédit ou consultation).
-   Persisté en SQLite (`wallets`), chaque mouvement journalisé dans
    `transactions` (`id`, `player_uuid`, `type`, `amount`, `context`,
    `created_at`) — un journal d'audit brut, non exposé par une commande à
    ce stade (consultable directement via une requête SQL si besoin).

## Commandes

-   `/money` (`rpgquest.money`, `default: true`) — affiche le solde de
    l'exécutant.
-   `/money pay <joueur> <montant>` (`rpgquest.money`) — transfert atomique
    (voir plus bas). `<joueur>` doit être **en ligne** (limitation connue,
    voir `docs/ARCHITECTURE.md`).
-   `/money admin give|take|set <joueur> <montant>` (`rpgquest.admin`) —
    seule façon de faire entrer de la monnaie dans l'économie hors
    marchand (aucun solde de départ, voir plus haut).
-   `/merchant reload|validate|list` (`rpgquest.admin`) — recharge/valide
    les définitions de marchands, ou liste celles actuellement chargées.
    Aucune sous-commande joueur : un marchand n'existe qu'à travers un
    dialogue qui l'ouvre.

## Atomicité et anti-duplication

-   Débit, crédit, paiement et réglage admin s'exécutent chacun comme
    **une seule transaction SQL** (pas d'étape intermédiaire observable) —
    un débit refusé (fonds insuffisants) ne modifie rien ; un paiement
    modifie les deux comptes ou aucun.
-   Un montant nul ou négatif est rejeté avant même de toucher la base.
-   Un débit qui dépasserait la capacité d'un `long` échoue proprement
    plutôt que de déborder silencieusement.
-   Deux opérations concurrentes sur le même portefeuille (double-clic,
    deux joueurs qui achètent en même temps) sont sérialisées par le
    thread base de données unique du plugin : l'une des deux voit toujours
    l'état déjà à jour laissé par l'autre.

## Marchands PNJ

Un marchand est une **vitrine YAML statique** (voir
[MERCHANT_FORMAT.md](../MERCHANT_FORMAT.md)), ouverte exclusivement depuis
une action de dialogue :

```yaml
choices:
  - text: "Voir la boutique"
    actions:
      - type: OPEN_MERCHANT
        merchant: rpgquest:village_merchant
```

Aucun marchand ne s'ouvre par un clic direct sur un PNJ — c'est le
dialogue qui identifie l'entité (comme pour les quêtes), le marchand n'a
aucune notion de PNJ. Voir `dialogues/merchant.yml` pour un exemple complet
reliant les deux.

Chaque offre peut porter des conditions cumulatives (permission, quête,
niveau d'expérience vanilla) ; une offre non satisfaite reste visible mais
refuse l'échange avec un message explicite au clic.

**Achat** (le marchand vend) : le débit est tenté avant de donner l'objet ;
un débit refusé ne donne jamais rien.

**Vente** (le marchand achète) : l'objet est retiré de l'inventaire de
façon synchrone avant même que le crédit (asynchrone) ne démarre, ce qui
empêche un double-clic de vendre deux fois le même stock.

## Intégration Vault (préparée, non câblée)

Aucune dépendance à [Vault](https://github.com/MilkBowl/VaultAPI) n'a été
ajoutée au projet : ni le plugin ni son API ne sont nécessaires en
l'absence de serveur qui les utilise, et le projet évite les dépendances
externes non indispensables (voir `CONTEXT.md`).

`EconomyService` expose néanmoins une forme délibérément compatible avec
l'API Vault (solde, débit/crédit avec vérification de fonds, paiement) :
un futur adaptateur peut être ajouté sans modifier `EconomyService` lui-même,
sur ce schéma :

1.  ajouter `net.milkbowl.vault:VaultAPI` en `compileOnly` (le jar de Vault
    n'est pas nécessaire sur le serveur si aucun autre plugin ne le
    requiert — dépendance strictement optionnelle, comme `paper-api`) ;
2.  écrire une classe `VaultEconomyAdapter implements
    net.milkbowl.vault.economy.Economy` qui délègue chaque méthode à
    `EconomyService` (`getBalance` → `balance()`, `withdrawPlayer` →
    `debit()`, `depositPlayer` → `credit()`, ...) ;
3.  dans `RPGQuestBootstrap`, n'enregistrer cet adaptateur auprès du
    `ServicesManager` de Bukkit que si `Bukkit.getPluginManager()
    .getPlugin("Vault") != null` (`softdepend: [Vault]` dans `plugin.yml`),
    pour ne jamais échouer au démarrage si Vault n'est pas installé.

## Marché entre joueurs

`/market` ouvre une vitrine partagée (tous vendeurs confondus, triée par
ancienneté, paginée comme le journal de quêtes) où n'importe quel joueur
peut mettre en vente l'objet tenu en main :

-   `/market sell <prix>` — met en vente **la pile entière** actuellement en
    main (pas de sélection de quantité ; pour vendre moins, séparer la pile
    dans l'inventaire au préalable comme d'habitude). L'objet est
    sérialisé **tel quel** (`ItemStack#serializeAsBytes()` — méta, PDC d'un
    objet personnalisé compris) et mis en dépôt (« escrow ») dans
    `market_listings` jusqu'à achat ou annulation. Aucune dépendance au
    registre d'objets personnalisés n'est nécessaire : l'objet sérialisé
    porte déjà toute son identité.
-   Cliquer sur l'offre d'**un autre joueur** dans la vitrine l'achète
    immédiatement (prix fixe, pas de négociation).
-   Cliquer sur **sa propre** offre l'annule et restitue l'objet — même
    effet que `/market cancel <id>`.
-   `/market admin list` (`rpgquest.admin`) — liste en lecture seule
    toutes les offres actives, pour la modération.

**Le vendeur n'a pas besoin d'être en ligne** pour être payé — contrairement
à `/money pay`, le crédit du vendeur ne dépend pas d'une session active
(cohérent avec un marché qui doit continuer à fonctionner pendant
l'absence du vendeur).

### Anti-duplication (achat en deux temps)

Le prix d'une offre n'étant connu qu'après lecture en base (contrairement à
un marchand PNJ, dont le prix vient d'un YAML déjà chargé), l'ordre
« débiter puis remettre » utilisé par les marchands ne suffit pas seul ici.
L'achat se déroule donc en deux étapes bien séparées :

1.  **Réservation atomique** (`MarketRepository#claim`) — bascule l'offre
    `ACTIVE → SOLD` uniquement si elle l'était encore au moment de la
    requête (comparaison et écriture dans la même transaction SQL). Au
    plus un acheteur peut jamais réserver la même offre, quel que soit le
    nombre de clics simultanés.
2.  **Débit de l'acheteur**, tenté seulement après la réservation réussie.
    S'il échoue (fonds insuffisants), l'offre est **réactivée**
    (`MarketRepository#reactivate`) plutôt que perdue — elle redevient
    immédiatement achetable par n'importe qui.

Seul un débit réussi déclenche le crédit du vendeur et la remise de
l'objet à l'acheteur. Annuler sa propre offre suit la même garde
(`MarketRepository#cancel`, atomique, vérifie propriétaire + état actif) :
l'objet n'est restitué que si l'annulation a réellement eu lieu.

### Limitation connue

`/market admin` ne permet pas de forcer l'annulation d'une offre d'un
joueur hors ligne avec restitution de l'objet (pas de système de « boîte
aux lettres » différée) — seul le vendeur lui-même (en ligne) peut annuler
sa propre offre. Mirroir facile d'un futur système de livraison différée
si nécessaire (voir aussi backpacks, étape 20).

## Tests

Automatisés :

-   `WalletRepositoryTest` — solde par défaut, débit/crédit/paiement
    atomiques, montant invalide rejeté, réglage admin, double-débit
    concurrent (aucun découvert possible).
-   `MerchantDefinitionParserTest`/`MerchantLoaderTest` — validation
    structurelle d'un marchand (offres, direction, matériau/objet
    personnalisé, conditions), id dupliqué entre fichiers.
-   `MerchantTradeServiceTest` — achat/vente réels (fonds suffisants et
    insuffisants, stock suffisant et insuffisant), permission/niveau/quête
    non satisfaits, marchand inconnu.
-   `DialogueDefinitionParserTest`/`DialogueSessionEngineTest` — parsing de
    `OPEN_MERCHANT`, ouverture réelle de la vitrine depuis un choix de
    dialogue (fermeture de la session de dialogue au passage).
-   `MarketRepositoryTest` — création d'offre, jointure du nom du vendeur,
    filtrage « mes offres », réservation atomique (dont l'échec d'une
    seconde réservation sur une offre déjà vendue), réactivation après
    débit refusé, annulation (par le vendeur, par un tiers refusé, sur une
    offre déjà vendue refusée).
-   `MarketServiceTest` — mise en vente (retrait de l'objet en main),
    achat réel (fonds suffisants et insuffisants — dans ce dernier cas,
    l'offre redevient active), clic sur sa propre offre (annulation),
    `/market cancel` par le vendeur et par un tiers.

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : ouverture
d'une vitrine par clic sur un PNJ renommé, lisibilité du lore des offres,
`/money pay` entre deux vrais joueurs, latence réseau sur un achat/vente,
navigation entre pages de `/market` avec un grand nombre d'offres, achat
concurrent réel de la même offre par deux joueurs.
