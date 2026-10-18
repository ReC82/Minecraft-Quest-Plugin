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
-   `/hud [sidebar|off]` (`rpgquest.hud`, `default: true`, prototype
    #184) — panneau latéral « Bourse / Niveau RPG » lu sur ce même
    portefeuille. Chaque mouvement **réussi** passé par `EconomyService`
    prévient `WalletChangeListener` : le HUD relit le solde et affiche
    `+N LodyCoins · solde S` dans l'action bar. Aucun solde parallèle.
-   `/merchant reload|validate|list` (`rpgquest.admin`) — recharge/valide
    les définitions de marchands, ou liste celles actuellement chargées.
    Aucune sous-commande joueur : un marchand n'existe qu'à travers un
    dialogue qui l'ouvre.
-   `/merchant open <id>` (`rpgquest.admin`, issue #336) — ouvre la vitrine
    pour l'administrateur lui-même, sans câbler de dialogue. Les échanges y
    sont **réels** (son solde, son inventaire).

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
niveau RPG `GLOBAL`, ou niveau vanilla si `progression.vanilla-xp-progression:
true` — issue #532) ; une offre non satisfaite reste visible mais
refuse l'échange avec un message explicite au clic.

**Achat** (le marchand vend) : le débit est tenté avant de donner l'objet ;
un débit refusé ne donne jamais rien.

**Vente** (le marchand achète) : l'objet est retiré de l'inventaire de
façon synchrone avant même que le crédit (asynchrone) ne démarre, ce qui
empêche un double-clic de vendre deux fois le même stock.

### Catalogues administrés depuis PlugAdmin (issue #17)

-   Chaque marchand est un **catalogue** (`type: SELL` boutique, `BUY` rachat, `MIXED`
    historique, `CRAFT` atelier d'artisan #18) réutilisable par plusieurs PNJ ; `enabled: false` ferme la boutique.
-   PlugAdmin → **Marchands** (`/merchants`) : créer, modifier, dupliquer (copie fermée),
    ouvrir/fermer, supprimer (refusé si un dialogue l'ouvre, ou pour un exemple fourni).
    Écriture relue par le vrai parseur, contrôle de version, atomique, ancienne version
    remise si le rechargement la refuse.
-   **Rachat filtré** : un comptoir ne rachète que les objets qu'il liste ; un prix de vente
    cité par un catalogue de rachat (ou l'inverse) est « indisponible ».
-   **Vitrine** : solde affiché (dernière ligne) et rafraîchi après chaque échange ; si l'étal
    a changé depuis l'affichage, le clic est refusé et la vitrine réaffichée (aucun échange
    sur une offre non vue) ; boutique fermée entre-temps → vitrine fermée, rien échangé.
-   **Audit** : `transactions.context` commence par `marchand:<id>`.
-   **Préparé, non actif** : stock, conditions de monde/ville/période/réputation, offres
    spéciales — aucun de ces filtres n'existe encore (tickets distincts).

### Ateliers d'artisan (issue #18)

-   Un catalogue `type: CRAFT` (« Atelier d'artisan » dans PlugAdmin → Marchands) ne
    propose que des **recettes** : des prix `CRAFT` du catalogue central. Trois formes :
    ingrédients seuls (`coins: 0`), pièces seules (aucun ingrédient), ingrédients + pièces.
    Une recette sans ingrédient ni pièce est refusée ; une recette « pièces seules » reste
    soumise au contrôle des boucles d'enrichissement (rachat plus cher que son coût).
-   La vitrine montre à chaque joueur ce qu'il possède de chaque ingrédient (✔ / ✘) et les
    pièces qui lui manquent ; au clic, la liste complète de ce qui manque, rien n'étant pris.
-   Mêmes garanties que la fabrication #336 (place vérifiée avant débit, remboursement des
    seules pièces, double-clic = une fabrication). Audit `ARTISAN_CRAFT`.
-   **Pas encore** : lien avec les recettes Bukkit `recipes/*.yml`, conditions de monde /
    ville / période / métier / réputation, stocks, remises (socle #336).

## Catalogue des prix et coûts (issue #336)

Tous les tarifs peuvent vivre dans **un seul catalogue**, `plugins/RPGQuest/prices.yml`,
géré depuis **PlugAdmin → Prix et coûts** (`/prices`, droit « Économie » : lecture
pour voir, écriture pour modifier). Le portefeuille LodyCoins existant reste la seule
source de vérité des soldes : le catalogue ne fait que dire combien coûte quoi.

Trois natures de prix, **toujours saisies, jamais déduites l'une de l'autre** (un
rachat n'est pas « la moitié de la vente ») :

| Nature | Le PNJ… | Le joueur… |
|---|---|---|
| `SELL_TO_PLAYER` | vend | paie `coins`, reçoit l'objet × quantité |
| `BUY_FROM_PLAYER` | rachète | donne l'objet × quantité, reçoit `coins` |
| `CRAFT` | fabrique | donne les ingrédients exacts et/ou paie `coins`, reçoit l'objet |

Un objet est un matériau Minecraft (`BREAD`) ou un objet RPGQuest (`rpgquest:…`),
reconnu par son PersistentDataContainer — jamais par son nom.

### Aucun prix implicite

-   `coins` est **obligatoire** (0 seulement si c'est voulu ; un avertissement le
    signale). Pas de montant par défaut, pas de calcul.
-   Une offre de marchand qui cite un prix **absent ou désactivé** est affichée
    « Offre indisponible » (barrière) et ne s'échange pas. Un `prices.yml` refusé
    par le serveur rend **toutes** les offres liées indisponibles.
-   Les offres à prix écrit dans le fichier du marchand (forme historique)
    continuent de fonctionner sans changement.
-   Le champ `demo` marque une **valeur de démonstration** (badge dans PlugAdmin,
    avertissement au chargement) : aucune valeur définitive n'est livrée.

### Validation au chargement et avant chaque écriture

Refusés (le fichier entier, jamais à moitié) : montant négatif ou non entier
(borne 1 000 000 000 contre une faute de frappe), quantité hors 1–64, objet
inconnu, fabrication sans ingrédient, ingrédient en double ou égal au résultat,
ingrédients sur une vente, et toute **boucle d'enrichissement** entre prix actifs,
calculée exactement sur le prix unitaire, tous marchands confondus :

-   un objet racheté plus cher qu'il n'est vendu ;
-   une fabrication dont le résultat se rachète plus cher que ce qu'elle coûte
    (pièces + ingrédients au prix de vente le plus bas, quand tous s'achètent).

Limite connue : les chaînes de plusieurs fabrications ne sont pas suivies.

PlugAdmin vérifie en plus que les objets RPGQuest cités existent sur le serveur.
L'écriture suit les garanties de `entitlements.yml` : fichier actuel valide,
relecture par le vrai chargeur, écriture atomique (le premier enregistrement crée
le fichier sans jamais en écraser un apparu entre-temps), rechargement immédiat et
restauration à l'octet près si le serveur n'accepte pas le résultat. Pas de
suppression : on désactive (aucun marchand cassé). Une copie naît désactivée.

### Relier une offre de marchand

```yaml
offers:
  - price-ref: pain_x4          # tout vient du catalogue
    required-level: 2           # les conditions d'accès restent propres à l'offre
```

`price-ref` exclut `direction`, `material`, `custom-item`, `quantity` et `price`
(une seule source pour un prix). Le bundle fournit `merchants/atelier_demo.yml`
(trois offres `demo_vente`, `demo_rachat`, `demo_fabrication`, **sans aucun prix**),
à ouvrir avec `/merchant open rpgquest:atelier_demo`. L'édition des marchands
depuis PlugAdmin relève de #17, les recettes d'artisan de #18.

### Garanties des échanges liés

-   **Prix vu = prix payé** : la vitrine retient la génération du catalogue
    affichée ; si un prix a changé depuis, le clic est refusé (« Les prix viennent
    de changer ») et la vitrine se réaffiche.
-   Vente et rachat réutilisent exactement les flux historiques (débit avant
    remise, remboursement si l'objet ne tient plus ; retrait synchrone avant
    crédit).
-   **Fabrication** : contrôle des ingrédients exacts et de la place (simulée
    *après* retrait des ingrédients) → débit des pièces → sur le thread principal,
    nouvelle vérification, retrait des ingrédients et remise du résultat en une
    seule passe synchrone. Fonds insuffisants : rien n'est pris. Ingrédients
    disparus entre-temps (double-clic, dépôt), inventaire rempli ou joueur
    déconnecté : **seules les pièces sont remboursées**, les ingrédients n'ont
    jamais été touchés. Un double-clic fabrique donc une seule fois.
-   **Audit** : chaque mouvement porte dans `transactions.context` la clé et la
    version du prix appliqué (`prix:epee@1a2b3c4d 1 × IRON_SWORD`), type
    `MERCHANT_BUY` / `MERCHANT_SELL` / `ARTISAN_CRAFT` ; chaque écriture PlugAdmin
    est journalisée côté serveur (`Prix : « clé » vXXXX écrit depuis PlugAdmin`)
    et dans l'historique des actions du panel.

### Modèle extensible (non activé)

Les règles évoquées par le ticket (stock, bornes, monde, ville, PNJ, période,
niveau, métier, réputation, événement, priorités, historique) ne sont **pas**
implémentées : elles demandent des arbitrages du propriétaire. Le modèle
(une clé stable par prix, une version par contenu, une nature explicite) permet
de les ajouter plus tard sans changer les offres existantes.

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
