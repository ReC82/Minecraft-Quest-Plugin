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

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : ouverture
d'une vitrine par clic sur un PNJ renommé, lisibilité du lore des offres,
`/money pay` entre deux vrais joueurs, latence réseau sur un achat/vente.
