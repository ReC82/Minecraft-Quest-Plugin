# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 11:02
* Sujet : Issue #18 — artisans : recettes et fabrication avec ressources + LodyCoins via la gestion des prix (MVP)
* Statut : WAITING_OWNER (livré et déployé sur DEV ; aucun prix ni marchand créé ; TC-345 réservé au propriétaire)
* Branche Git : `feature/18-artisan-recipes` (PR #500, fusionnée dans `chore/257-ci-github-actions`) ; rapport : `docs/18-rapport`
* Commit actuel si disponible : `0e56482` (merge PR #500)
* Début de la tâche : 2026-10-17 09:47:13
* Fin de la tâche : 2026-10-17 11:03:53
* Durée totale : 01:16:40

## Demande
Session autonome `lqorch`, ticket #18 (Marchands et économie 4/6). La demande :
* auditer d'abord l'existant ;
* livrer des PNJ artisans rattachés à un catalogue de recettes réutilisable, ouvert par dialogue ;
* recettes avec ingrédients exacts et/ou LodyCoins ;
* afficher ce qui manque ;
* transaction sûre (double-clic, échec, inventaire plein) ;
* tout administrer depuis PlugAdmin, la tarification restant dans #336 (pas d'éditeur de prix concurrent).

Aucune règle économique définitive, aucune migration. Ticket laissé ouvert.

## Analyse
**Audit — déjà livré avant cette session (#336, #17) :**
* prix `CRAFT` dans `prices.yml` (résultat, quantité, ingrédients exacts, pièces), édités dans PlugAdmin → Prix et coûts ;
* offres de marchand `price-ref` et fabrication sûre dans `MerchantTradeService` (débit d'abord, ingrédients revérifiés puis pris, objet remis en une passe synchrone, remboursement des seules pièces, double-clic = une fabrication) ;
* catalogues de marchands éditables dans PlugAdmin et ouverts par l'action de dialogue `OPEN_MERCHANT`.

Il n'y avait donc pas de moteur à redévelopper. Aucun commentaire, aucune PR, aucun rapport sur #18.

**Écarts réels avec le MVP du ticket :**
1. **Pas de catalogue « artisan »** : les recettes vivaient dans une boutique `SELL` mêlée aux ventes.
2. **Recette « monnaie seule » impossible** : #336 refusait une fabrication sans ingrédient. La case de validation du ticket « monnaie seule » ne pouvait pas passer.
3. **Manques mal affichés** :
   * la vitrine listait les ingrédients sans dire ce que le joueur possède ;
   * au clic, seul le *premier* ingrédient manquant était cité, avec la quantité requise et non le manque ;
   * en cas de fonds insuffisants, le message disait seulement « Fonds insuffisants », sans montant.

**Décisions** (réversibles, sans nouveau format de données ni migration) :
* Nouvelle nature de catalogue `type: CRAFT` (« Atelier d'artisan »). Elle n'accepte **que** des `price-ref` vers des prix `CRAFT`. Une offre à prix écrit est refusée et un prix de vente ou de rachat est « indisponible ». `SELL` et `MIXED` gardent leur comportement (fabrication incluse).
* Prix `CRAFT` sans ingrédient **permis s'il coûte des pièces**. Sans ingrédient **ni** pièce, il reste refusé. La recette « pièces seules » reste soumise au contrôle des boucles d'enrichissement de #336 : son coût est comparé au meilleur rachat.

**Non inventé (hors MVP, renvoyé au socle #336 / arbitrage propriétaire) :**
* conditions de monde, ville, période, métier, réputation ;
* stocks, remises ;
* lien avec les recettes Bukkit `recipes/*.yml`.

## Travail effectué
1. **Modèle** :
   * `MerchantCatalogType.CRAFT` : `accepts(direction)` est toujours faux, `accepts(PriceType)` vaut `CRAFT` seulement ;
   * message de refus dédié dans `MerchantDefinition` ;
   * agent : `catalog_type` accepte `CRAFT`, libellé « d'artisan ».
2. **Prix** : `PriceCatalogLoader` accepte une recette « pièces seules » et refuse « ni ingrédient ni pièce ». Même règle dans `PriceForm` (PlugAdmin).
3. **Vitrine** (`MerchantTradeService`) :
   * chaque recette est dessinée pour le joueur : ✔/✘ par ingrédient avec « tu en as N », « Ingrédients : aucun », « Coût : aucune pièce » ou le coût ;
   * une fois le solde lu, la ligne « ✘ Il te manque N LodyCoins » s'ajoute ;
   * les recettes sont redessinées après la lecture du solde et après chaque fabrication, seulement si les prix n'ont pas changé depuis l'affichage ;
   * au clic, la liste complète est donnée (« 1 × IRON_INGOT (tu en as 1 sur 2), … Rien n'a été pris. ») ;
   * en cas de fonds insuffisants, le solde est relu pour donner le montant exact manquant (« coût C, solde S »).
   * Le comptage utilise le même test `isSimilar` (PDC compris) que le retrait. Les garanties de transaction de #336 sont inchangées.
4. **Exemple embarqué** `merchants/artisan_demo.yml` (`type: CRAFT`, sans prix). Il cite `demo_recette_ingredients`, `demo_recette_pieces` et `demo_recette_mixte`. Ajouté à `BUNDLED_EXAMPLES` : seedé s'il est absent, jamais écrasé.
5. **PlugAdmin** :
   * nature « Atelier d'artisan » dans Marchands (formulaire et regroupement) ;
   * une ligne « objet + prix » dans un atelier est refusée avant l'envoi ;
   * chaque offre liée affiche le résumé du prix (« 1 × IRON_SWORD fabriqué contre 2 × IRON_INGOT + 5 pièce(s) ») ;
   * aides de la page Prix mises à jour ;
   * l'éditeur de dialogues affiche « atelier d'artisan (recettes) ».
6. Docs, tests, CI, fusion, déploiement DEV.

## Fichiers créés
- `src/main/resources/merchants/artisan_demo.yml`
- `src/test/java/com/lodygames/rpgquest/economy/merchant/ArtisanWorkshopTradeTest.java` (7 tests)
- Ce rapport

## Fichiers modifiés
- Plugin : `MerchantCatalogType`, `MerchantDefinition`, `MerchantDefinitionParser` (javadoc), `MerchantTradeService`, `YamlMerchantRegistry`, `PriceCatalogLoader`, `AgentActionExecutor`, `AgentActions` (javadoc), `BukkitAgentActions`
- Tests plugin : `PriceCatalogLoaderTest` (+2, 1 adapté), `MerchantDefinitionParserTest` (+1), `MerchantLoaderTest` (exemple artisan)
- PlugAdmin : `MerchantForm`, `PriceForm`, `AgentPages`, `DialoguePages` ; tests `MerchantsPageTest` (+2), `PriceFormTest` (adapté)
- Docs : `MERCHANT_FORMAT.md` (section « Atelier d'artisan »), `docs/ECONOMY.md`, `docs/RPGQUEST_BIBLE.md` (§12), `docs-site/dialogues.html`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-345), `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Les transactions restent de type `ARTISAN_CRAFT`. Aucun solde, objet ni donnée joueur n'a été touché.

## Configuration / données
- `merchants/artisan_demo.yml` a été seedé au démarrage sur DEV (RCON `merchant list` : `rpgquest:artisan_demo (3 offre(s))`). Ses recettes restent « indisponibles » tant que les trois prix `demo_recette_*` n'existent pas.
- `prices.yml` : rien n'a été créé ni écrit.

## Tests automatiques
- **Plugin**, ciblés en local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `*economy.*` vert, 94 tests. `ArtisanWorkshopTradeTest` couvre :
  - recette à ingrédients seuls : aucune pièce prise ;
  - pièces seules et double-clic : une seule lanterne, 12 pièces débitées ;
  - ingrédients + pièces ;
  - tous les ingrédients manquants listés, rien pris ;
  - fonds insuffisants : « il te manque 3 », « coût 10, solde 7 », rien pris ;
  - lore ✔/✘, « Il te manque 6 », « Ingrédients : aucun », « aucune pièce » ;
  - prix de vente dans un atelier = barrière, jamais vendu.
  
  Ailleurs : chargement des trois formes de recette, recette « pièces seules » revendue plus cher refusée, « ni l'un ni l'autre » refusé, atelier avec une offre à prix écrit refusé, exemple embarqué chargé en `CRAFT`.
- **PlugAdmin**, ciblés : `MerchantsPageTest` (atelier regroupé, recette en clair, nature relue ; offre fixe refusée sans envoi), `PriceFormTest`, `PricesPageTest`, `*Dialogue*`, `AgentActionCatalogTest` : verts.
- **CI GitHub PR #500** (plugin, control-panel, web-api, scripts, artefacts) : verte.
- `deploy-verygames.sh` a relancé tests + build : verts.
- Aucune vérification visuelle Playwright : le rendu n'est couvert que par les tests de page.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — **TC-345**. Créer les trois recettes `demo_recette_*` dans Prix et coûts, puis `/merchant open rpgquest:artisan_demo`. À vérifier :
- l'affichage ✔/✘ et le manque de pièces ;
- les messages d'ingrédients manquants et de fonds insuffisants ;
- les trois formes de recette ;
- le double-clic ;
- l'inventaire plein ;
- l'ouverture par un dialogue de PNJ.

Aucun test Minecraft n'a été fait dans cette session.

## Résultat attendu
Le propriétaire crée un atelier d'artisan dans PlugAdmin, sans YAML, et y place des recettes de la page Prix et coûts : ingrédients, pièces, ou les deux. Il le relie à un ou plusieurs PNJ par dialogue. Le joueur voit ce qu'il a et ce qui lui manque, et rien n'est pris tant que tout n'est pas réuni.

## Reset / retour à l'état initial
Désactiver les prix `demo_recette_*`, puis fermer l'atelier ou le retirer des dialogues. Les essais ne touchent que le solde et l'inventaire du compte de test.

## Déploiement VeryGames
Effectué le 2026-10-17, depuis `0e56482` :
- `scripts/deploy-verygames.sh -y` : « JAR en ligne : 3506080 octets (== local) », puis « Déploiement terminé ».
- JAR distant retéléchargé (`vg::remote_download`) : SHA-256 `1f63f1d60dfdb7e89053129221db8f3b125655e4fa104e3685caaa23c8243b1c`, identique au local.
- Sauvegarde : `rpgquest-20261017T090108Z-predeploy.jar` (3 502 505 o, `1f50f2ee…5d43` = le JAR #17). Elle est plus petite que le nouveau JAR, ce qui est attendu.
- Redémarrage : `verygames-restart.sh` avec 0 joueur connecté → ONLINE.
- RCON `merchant list` : 3 marchands chargés (`artisan_demo` 3, `atelier_demo` 3, `village_merchant` 5).
- PlugAdmin : `scripts/plugadmin/deploy.sh` → ONLINE. Le jar installé contient le nouveau `MerchantForm`, et `/merchants` répond 303 → /login.
- Journaux : `~/.local/share/rpgquest/logs/deploy-18.log`, `restart-18.log`, `plugadmin-18.log`.

### À transférer
JAR (fait) + PlugAdmin (fait). Rien d'autre.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `prices.yml` (absent sur DEV), `merchants/*.yml` existants.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
1. Remettre `rpgquest-20261017T090108Z-predeploy.jar` (`scripts/rollback-verygames.sh --latest`), puis redémarrer.
2. Redéployer le PlugAdmin précédent (`scripts/plugadmin/rollback.sh`).

Conséquences avec l'ancien JAR :
- il refuse `type: CRAFT` : `artisan_demo.yml` et tout atelier seraient signalés invalides, les autres marchands chargeant normalement ;
- **si une recette « pièces seules » existe dans `prices.yml`**, l'ancien JAR refuse tout le fichier. Il faut alors ajouter un ingrédient à cette recette avant le rollback.

## Logs / diagnostic
- `Offre de marchand indisponible : prix « clé » absent, désactivé ou d'un type refusé par ce catalogue (CRAFT).`
- Refus au chargement : `… interdit dans un catalogue de type CRAFT (recettes du catalogue des prix seulement : « price-ref »).`
- Transactions : `ARTISAN_CRAFT`, `context = marchand:<id> prix:<clé>@<version> …`.

## Documentation mise à jour
`MERCHANT_FORMAT.md`, `docs/ECONOMY.md`, Bible §12, `docs-site/dialogues.html`, `current_state.md`, TC-345, `SERVER_CHANGELOG.md`, index des rapports.

## Limitations / travail restant
- **Arbitrages du propriétaire** (signalés sur le ticket) :
  - faut-il relier les ateliers aux recettes Bukkit `recipes/*.yml` (import d'une recette existante en prix CRAFT) ?
  - quelles conditions de disponibilité (monde, ville, métier, réputation, stock), et dans quel ordre ;
  - les vraies valeurs.
- Les noms d'objets en vitrine restent les identifiants (`IRON_INGOT`, `rpgquest:…`), comme pour #336.
- Les quantités affichées ✔/✘ sont indicatives : la vitrine n'est pas redessinée si l'inventaire change en dehors d'une fabrication. Tout est revérifié au clic.

## Prochaine étape suggérée
Le propriétaire passe TC-345 sur DEV et tranche les points ci-dessus.
