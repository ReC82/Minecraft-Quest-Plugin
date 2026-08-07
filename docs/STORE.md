# Boutique web (achats sandbox, livraison idempotente)

Vend des avantages de **confort** uniquement (mission étape 22), livrés au
serveur de jeu via le module `web-api` déjà séparé du plugin (voir
[docs/WEB_API.md](WEB_API.md)). Le serveur de jeu reste l'autorité finale
pour tout avantage : `web-api` ne fait jamais qu'enqueue des livraisons,
jamais un octroi direct (il n'a de toute façon aucun accès à `data.db`).

## Politique pay-to-convenience / anti-pay-to-win

Un produit ne peut accorder que l'un de ces deux types, et rien d'autre —
c'est appliqué par construction, pas seulement documenté :

-   `BACKPACK_SIZE` — un palier de backpack (voir
    [docs/BACKPACKS.md](BACKPACKS.md)).
-   `ENTITLEMENT` — un avantage générique via
    `entitlement.EntitlementService` (pass VIP, cosmétique...).

Aucun type ne permet de définir un attribut, une arme, un bonus de dégâts,
de vitesse ou de résistance. Le pass VIP de test et les cosmétiques livrés
à cette étape n'ont eux-mêmes **aucun effet de gameplay** — ils posent la
plomberie (achat → avantage stocké) pour une fonctionnalité future, jamais
un raccourci compétitif dès aujourd'hui.

## Catalogue produit vs avantage technique (mission point 1)

Deux fichiers distincts, volontairement non partagés :

-   `web-api/products.json` (commercial : nom, prix, devise — voir
    [docs/WEB_API.md](WEB_API.md)).
-   `plugins/RPGQuest/store-products/*.yml` (ce que l'id accorde en jeu —
    voir `store.StoreProductRegistry`), cinq exemples générés
    automatiquement : `small_backpack`, `upgrade_medium`, `upgrade_large`,
    `vip_pass_test`, `cape_aurora`.

Les deux catalogues doivent partager les mêmes identifiants de produit. Si
web-api valide un achat pour un id absent du registre plugin, la livraison
reste `PENDING` (jamais acquittée à l'aveugle) et l'administrateur doit
corriger `store-products/` puis attendre le prochain sondage — l'achat
n'est jamais perdu.

## Prestataire de paiement (mission points 3, 14)

**Décision d'ingénierie documentée** : cet environnement de développement
n'a accès à aucun compte ni identifiants d'un vrai prestataire (Stripe,
PayPal...), et aucun accès réseau externe pour les tester. `SandboxPaymentProvider`
simule fidèlement le flux d'un vrai PSP en mode test :

1.  Le site crée une session de paiement hébergée (`/store/pay/{id}`).
2.  Le "prestataire" (ce même processus) notifie web-api via un webhook
    HTTP signé (`POST /store/webhook`, HMAC-SHA256), exactement comme le
    ferait un vrai fournisseur externe.
3.  web-api ne fait confiance qu'à ce webhook signé, jamais à un simple
    retour de redirection navigateur — même garantie de sécurité qu'avec
    un vrai prestataire.

Toute la logique métier (commandes, webhook, livraisons, remboursement) est
donc déjà exercée de bout en bout. Passer à un vrai prestataire ne
nécessite qu'une nouvelle implémentation de l'interface `PaymentProvider` —
aucun autre code du module n'a besoin de changer.

**Aucune donnée de carte bancaire n'est jamais demandée ni stockée**
(mission point 4) : le formulaire d'achat ne demande que le produit et
l'UUID Minecraft du joueur ; la page de paiement sandbox ne propose que
"Payer (sandbox)" / "Simuler un échec".

## Commandes, livraisons, idempotence (mission points 5-7)

SQLite propre à web-api (`store.db`, jamais `data.db`) :

-   `orders` (id, produit, joueur, statut `PENDING`/`PAID`/`REFUNDED`/`FAILED`, montant, session du prestataire).
-   `deliveries` (id, commande, `GRANT`/`REVOKE`, statut, tentatives).
-   `webhook_events` (id d'événement du prestataire = clé primaire —
    déduplication d'un rejeu, test "webhook répété").

Chaque commande et chaque livraison a un identifiant unique généré côté
web-api (UUID). Acquitter deux fois la même livraison (`POST
/api/store/deliveries/{id}/ack`) est un no-op sans erreur (mission point 7).

Le serveur de jeu **sonde** (`GET /api/store/deliveries/pending`, mission
point 8) plutôt que de recevoir un push — aucun port entrant sur le serveur
Minecraft, et un simple redémarrage du sondage suffit à couvrir aussi bien
un redémarrage normal qu'une reprise après crash : les livraisons non
acquittées réapparaissent simplement au sondage suivant.

## Authentification serveur-à-serveur (mission point 9)

Deux mécanismes distincts, jamais confondus :

-   **Site ↔ serveur de jeu** (`/api/store/*`) : même jeton porteur que
    l'export en lecture seule de l'étape 21
    (`RPGQUEST_WEB_API_TOKEN`, jamais dans un fichier versionné).
-   **Prestataire ↔ web-api** (`/store/webhook`) : signature HMAC-SHA256
    (`RPGQUEST_STORE_WEBHOOK_SECRET`, jamais dans un fichier versionné),
    comparaison en temps constant. Une signature invalide est refusée
    (401) avant même de lire le corps comme un événement valide.

## Gestion des cas particuliers (mission point 10)

| Cas | Comportement |
|---|---|
| Joueur hors ligne | Octroi/révocation purement via UUID (`EntitlementService`/`BackpackService#applySizeChange`), jamais besoin d'un `Player` en ligne. |
| UUID inconnu | `PlayerProfileRepository#findOrCreate` crée le profil avant l'octroi ; le pseudo se corrigera de lui-même à la prochaine vraie connexion. |
| Produit déjà possédé | Comparé côté serveur de jeu (palier/tier actuel) avant tout octroi — jamais de rétrogradation, jamais de double octroi, toujours acquitté comme un succès. |
| Upgrade | Même logique que "déjà possédé" : un palier strictement supérieur déclenche l'octroi (et le redimensionnement de backpack), un palier égal ou inférieur est ignoré. |
| Remboursement | `POST /api/store/orders/{id}/refund` (admin, sandbox) bascule la commande `REFUNDED` et enqueue une livraison `REVOKE`. |
| Révocation | `REVOKE` retire l'avantage (`EntitlementService#revoke`) ; pour un backpack, le palier retombe sur `backpacks.fallback-size` (simplification assumée : impossible de vérifier une permission par-joueur hors ligne, voir Limites). |
| Échec temporaire | Le serveur de jeu n'acquitte que sur succès ; toute erreur (web-api indisponible, base locale en panne...) laisse la livraison `PENDING`, réessayée automatiquement au sondage suivant — aucune logique de nouvelle tentative dédiée. |

## Historique admin (mission point 11) et audit (point 12)

`/store history [joueur|uuid]` (`rpgquest.admin`) interroge `GET
/api/store/orders` de web-api et affiche produit/joueur/statut/date. Les
journaux d'accès de web-api (`AccessLogger`, étape 21) et les erreurs de
livraison ne contiennent jamais le jeton, la signature ni aucune donnée de
paiement — uniquement identifiants, statuts et messages d'erreur.

## Sandbox / mode test (mission point 14)

Tout le flux tourne aujourd'hui en sandbox par construction (voir
"Prestataire de paiement" ci-dessus). Pour tester en local :

```
gradlew.bat :web-api:build
set RPGQUEST_WEB_API_TOKEN=un-secret-partage
set RPGQUEST_STORE_WEBHOOK_SECRET=un-autre-secret
java -jar web-api\build\libs\web-api.jar
```

Puis ouvrir `/store`, choisir un produit, saisir un UUID Minecraft valide,
suivre le lien vers `/store/pay/{id}`, cliquer "Payer (sandbox)". Démarrer
le plugin avec `store.enabled: true` et le même `RPGQUEST_WEB_API_TOKEN`
pour voir la livraison arriver au sondage suivant.

## Limites connues

-   Le remboursement d'un backpack retombe toujours sur `fallback-size`,
    jamais sur un calcul dépendant d'une permission par-joueur (impossible
    à vérifier pour un joueur hors ligne avec l'API Bukkit standard).
-   Le pass VIP et les cosmétiques livrés à cette étape n'ont encore aucun
    effet en jeu (plomberie uniquement, voir "Politique pay-to-convenience").
-   Une livraison dont le produit est absent du registre plugin reste en
    attente indéfiniment jusqu'à correction manuelle — comportement voulu
    (jamais un octroi à l'aveugle), mais nécessite une supervision admin.
-   Aucune interface web d'administration pour le remboursement à ce
    stade : `POST /api/store/orders/{id}/refund` s'utilise directement
    (curl, ou un futur outil admin).
