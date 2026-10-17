# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 06:20
* Sujet : Issue #336 — gestion centralisée des prix et coûts depuis PlugAdmin (tranche 1)
* Statut : WAITING_OWNER (livré et déployé sur DEV ; aucun prix créé ; TC-342 et arbitrages réservés au propriétaire)
* Branche Git : `feature/336-price-catalog` (PR #494, fusionnée dans `chore/257-ci-github-actions`) ; rapport : `docs/336-rapport`
* Commit actuel si disponible : `28e1c6a` (merge PR #494)
* Début de la tâche : 2026-10-17 06:20:12
* Fin de la tâche : 2026-10-17 07:47:38
* Durée totale : 01:27:26

## Demande
Session autonome `lqorch`, ticket #336 (Marchands et économie 1/6). La demande : auditer d'abord l'existant ; un prix central réutilisable par les marchands (#17) et les artisans (#18) ; vente, rachat et fabrication (pièces et objets) distincts, sans prix implicite. Aucune règle économique définitive : les valeurs de démonstration doivent être marquées. Transactions sûres et auditées, portefeuille LodyCoins comme source de vérité, aucune migration de base. Ticket laissé ouvert.

## Analyse
- **Audit** : rien n'était livré pour #336. Aucun rapport, aucune PR, aucun commentaire. Les prix vivaient seulement en dur dans chaque `merchants/*.yml` (`price` par offre), invisibles et non éditables depuis PlugAdmin. Les recettes (`recipes/*.yml`) sont de vraies recettes Bukkit sans coût en pièces. Le portefeuille (`EconomyService`, `wallets`/`transactions`) et les garanties des marchands (débit avant remise, retrait synchrone avant crédit, remboursement #318) sont solides : ils sont réutilisés tels quels.
- **Choix de modèle** : un fichier `prices.yml` indépendant (même famille que `entitlements.yml` #474), avec une clé stable par prix. Les marchands le **citent** (`price-ref`) au lieu de dupliquer le montant : une seule source par prix. Les offres historiques à prix écrit restent valides et intactes.
- **Aucun prix implicite** : `coins` est obligatoire (0 doit être écrit et déclenche un avertissement). Un prix absent ou désactivé, ou un fichier refusé, rend l'offre « indisponible », jamais un montant par défaut.
- **Risques d'arbitrage** : le catalogue étant central, on peut détecter les boucles d'enrichissement, calculées exactement en fractions et tous marchands confondus. Deux cas sont refusés au chargement et avant écriture : un rachat plus cher que la vente, et une fabrication revendue plus cher que son coût quand ses ingrédients s'achètent.
- **Fabrication sûre** : l'ordre est choisi pour que seuls les **pièces** aient à être rendues, ce qui fonctionne même hors ligne. On débite d'abord. Ensuite, sur le thread principal, on revérifie les ingrédients et la place (simulée après retrait), puis on retire les ingrédients et on remet l'objet dans la même passe synchrone. Un double-clic produit deux débits : le second ne trouve plus les ingrédients et il est remboursé.
- **Prix vu = prix payé** : la vitrine retient la génération du catalogue. Si un prix change, le clic est refusé et la vitrine se réaffiche.
- **Hors périmètre** (arbitrages métier, non inventés) : les règles conditionnelles du ticket (stock, monde, ville, PNJ, période, niveau, métier, réputation, événement, priorités, historique), l'édition des marchands (#17) et le lien avec les recettes d'artisan (#18).

## Travail effectué
1. **Plugin** (`economy.price`) :
   - `PriceType`, `PriceDefinition` (version courte), `PriceCatalog`.
   - `PriceCatalogLoader` (validation stricte, avertissements, `demo`) et `PriceArbitrage`.
   - `PriceCatalogFile` : écriture sûre (création sans écrasement, restauration à l'octet près).
   - `PriceCatalogEditor` : créer, modifier, dupliquer en copie désactivée, activer/désactiver ; pas de suppression.
   - `PriceCatalogService` (génération) et câblage dans le bootstrap.
2. **Marchands** :
   - `MerchantOffer.priceRef` et parseur `price-ref` (champs locaux interdits avec).
   - `MerchantTradeService` : icônes liées ou barrière « Offre indisponible », contrôle de génération, fabrication, trace `prix:<clé>@<version>`.
   - `TransactionType.ARTISAN_CRAFT`.
   - `/merchant open <id>` (admin) et `merchants/atelier_demo.yml` (trois `price-ref`, aucun prix).
3. **Agent** : `price.catalog.status`, `price.save`, `price.duplicate`, `price.toggle`. Les objets RPGQuest cités sont vérifiés côté serveur, et les marchands touchés sont cités à la désactivation.
4. **PlugAdmin** : page `/prices` « Prix et coûts » (navigation, ECONOMY_READ ; écritures ECONOMY_WRITE).
   - Prix groupés par nature, badges actif/démonstration, « Cité par ».
   - Formulaire avec nature en boutons radio, objet recherchable, quantité, pièces obligatoires, ingrédients, démonstration et actif.
   - Tableau des marchands, avec les offres liées sans prix actif.
   - `PriceForm` assure la normalisation et des refus lisibles.
5. Docs, tests, CI, fusion, déploiement DEV.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/economy/price/` : `PriceType`, `PriceDefinition`, `PriceCatalog`, `PriceCatalogLoader`, `PriceArbitrage`, `PriceCatalogFile`, `PriceCatalogEditor`, `PriceCatalogService`
- `src/main/resources/merchants/atelier_demo.yml`
- `control-panel/.../panel/agent/PriceForm.java`
- Tests : `PriceCatalogLoaderTest`, `PriceCatalogEditorTest`, `PriceCatalogAgentFlowTest`, `MerchantPriceCatalogTradeTest`, `PriceFormTest`, `PricesPageTest`
- Ce rapport

## Fichiers modifiés
- Plugin : `MerchantOffer`, `MerchantDefinitionParser`, `MerchantSession`, `MerchantTradeService`, `YamlMerchantRegistry`, `MerchantCommand`, `TransactionType`, `RPGQuestBootstrap`, `AgentActionType`, `AgentActions`, `AgentActionExecutor`, `BukkitAgentActions`, `plugin.yml`
- Tests plugin : `MerchantDefinitionParserTest` (+2), `MerchantLoaderTest` (+1)
- PlugAdmin : `AgentActionCatalog`, `AgentPages`, `PanelApp`, `Layout`
- Docs : `docs/ECONOMY.md` (section « Catalogue des prix et coûts »), `MERCHANT_FORMAT.md`, `docs/RPGQUEST_BIBLE.md` (§12), `docs-site/dialogues.html`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-342), `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. `ARTISAN_CRAFT` est une simple valeur texte de `transactions.type`. Aucun solde, objet ni donnée joueur n'a été touché.

## Configuration / données
- `prices.yml` est nouveau, créé **uniquement** par PlugAdmin au premier prix enregistré. **Absent sur DEV** : rien n'a été créé.
- `merchants/atelier_demo.yml` est seedé au démarrage (fichier absent). Ses trois offres restent « indisponibles » tant que `demo_vente`, `demo_rachat` et `demo_fabrication` n'existent pas.

## Tests automatiques
- **Plugin**, ciblés en local (`RPGQUEST_TEST_MAX_HEAP=768m`), verts : `PriceCatalogLoaderTest` (9), `PriceCatalogEditorTest` (7), `PriceCatalogAgentFlowTest` (2), `MerchantPriceCatalogTradeTest` (10), `MerchantDefinitionParserTest` (11), `MerchantLoaderTest` (5), `MerchantTradeServiceTest` (7), `MarketServiceTest`, `DialogueSessionEngineTest`. Total : 102.
- **Cas couverts** :
  - trois natures lues ;
  - montant absent, négatif ou décimal refusé ;
  - objet inconnu, AIR, quantité 0 ou 65 refusés ;
  - fabrication sans ingrédient, avec ingrédient en double ou égal au résultat, et ingrédients sur une vente : refusés ;
  - boucles d'enrichissement : rachat > vente, et fabrication 25 acceptée / 26 refusée ;
  - prix désactivé ignoré pour l'arbitrage ;
  - premier enregistrement qui crée le fichier ; commentaires et ordre conservés ;
  - STALE / NOT_FOUND / EXISTS / UNCHANGED / clé `yes` ;
  - écriture refusée sans aucun octet modifié ; fichier actuel invalide jamais écrasé ;
  - restauration exacte sans fichier temporaire ;
  - en vitrine : vente et rachat au prix du catalogue ; fabrication (pièces, ingrédients PDC, résultat) ;
  - fonds insuffisants : rien pris ; faux ingrédient vanilla : rien débité ; inventaire plein : rien débité ;
  - double-clic : une seule fabrication, second débit remboursé ;
  - prix inconnu ou désactivé : aucun échange ; prix changé vitrine ouverte : clic refusé puis achat au nouveau prix ;
  - fichier refusé : tout est indisponible ;
  - via l'agent : objet RPGQuest inconnu et boucle refusés sans écriture ; désactivation qui cite le marchand.
- **PlugAdmin**, ciblés en local, verts : `PriceFormTest` (5), `PricesPageTest` (6), `AgentActionCatalogTest` (52), `ActionReturnPathTest`, `ContentEditorPagesTest`.
- **CI GitHub PR #494** (plugin, control-panel, web-api, scripts, artefacts) : verte.
- **Playwright local** (panel local + faux agent en mémoire, `/tmp/qc336`), à 1280 px :
  - page vide ;
  - création de `demo_vente` (BREAD × 4, 3 pièces), `demo_rachat` (COBBLESTONE × 8, 1 pièce) et `demo_fabrication` (IRON_SWORD ← 2 IRON_INGOT + 1 STICK, 5 pièces) ;
  - désactivation du rachat : marchand signalé « Indisponible en jeu ».
  
  À 1280 et 390 px : aucun débordement horizontal, aucune erreur console.
  
  Le premier essai a révélé un conflit de nom de champ (`type` = type d'action), corrigé en `price_type` avant la PR.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — **TC-342** : créer les trois prix `demo_*` depuis PlugAdmin, `/merchant open rpgquest:atelier_demo`, puis :
- achat, rachat, fabrication ;
- ingrédients manquants, double-clic, inventaire plein ;
- prix changé vitrine ouverte, désactivation.

## Résultat attendu
Le propriétaire définit tous les tarifs (vente, rachat, fabrication en pièces et objets exacts) dans PlugAdmin, sans YAML. Le serveur refuse un prix incohérent ou exploitable avant de l'écrire. En jeu, une offre sans prix défini ne s'échange jamais, et le joueur paie toujours le prix qu'il voit.

## Reset / retour à l'état initial
Désactiver les prix depuis PlugAdmin (pas de suppression). Les essais en jeu ne touchent que le solde et l'inventaire du compte qui les fait.

## Déploiement VeryGames
Effectué le 2026-10-17 (heure locale), depuis `28e1c6a` :
- `scripts/deploy-verygames.sh -y` : le script relance tests + build (verts). JAR 3 468 994 o, SHA-256 `592830fb72c10cc107de77cf660ff1bdd9f08e23117835e209add489d3d3a388`. Le script affiche « JAR en ligne : 3468994 octets (== local) » puis « Déploiement terminé ».
- **JAR distant retéléchargé** (`vg::remote_download`) : même taille, même SHA-256.
- Sauvegarde : `rpgquest-20261017T054550Z-predeploy.jar` (3 413 355 o, `e76fb441…719e`). C'est le JAR #214 `ec5af0f`, plus petit que le nouveau, ce qui est attendu.
- Redémarrage : `verygames-restart.sh` avec 0 joueur connecté → ONLINE.
- RCON `merchant list` (lecture seule) : `rpgquest:atelier_demo (3 offre(s))` et `village_merchant (5)`. Le nouveau JAR est donc chargé, et `price-ref` est accepté.
- PlugAdmin : `scripts/plugadmin/deploy.sh` → ONLINE. Le jar installé contient `PriceForm`, et `/prices` est servi (303 → /login sur le port réel).
- Sur DEV : **aucun prix créé**, `prices.yml` absent, aucune action d'écriture lancée.
- Journaux : `~/.local/share/rpgquest/logs/deploy-336.log`, `restart-336.log`, `plugadmin-336.log`.

### À transférer
JAR (fait) + PlugAdmin (fait).
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `merchants/village_merchant.yml`. **Ne pas créer `prices.yml` à la main.**
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` puis redémarrage ; PlugAdmin `scripts/plugadmin/rollback.sh`. L'ancien JAR ignore `prices.yml` et refuse l'offre `price-ref`, donc `atelier_demo.yml` serait signalé invalide (les autres marchands chargent normalement).

## Logs / diagnostic
- `Prix : N prix chargé(s) depuis prices.yml (fichier absent : aucun prix défini).`
- `Prix : « clé » vXXXXXXXX écrit depuis PlugAdmin (CREATED|UPDATED|DUPLICATED|TOGGLED) — résumé.`
- `Prix : prices.yml refusé, AUCUN prix appliqué (N erreur(s)) : …`
- `Offre de marchand indisponible : prix « clé » absent ou désactivé dans prices.yml.`
- Transactions : `context = prix:<clé>@<version> <n> × <objet>` (types `MERCHANT_BUY`, `MERCHANT_SELL`, `ARTISAN_CRAFT`).

## Documentation mise à jour
`docs/ECONOMY.md`, `MERCHANT_FORMAT.md`, Bible §12, `docs-site/dialogues.html`, `current_state.md`, TC-342, `SERVER_CHANGELOG.md`, index des rapports.

## Limitations / travail restant
- **Décisions du propriétaire** (signalées sur le ticket) :
  - quelles règles conditionnelles activer et dans quel ordre de priorité ;
  - les vraies valeurs de prix ;
  - faut-il refuser les prix à 0, ou seulement avertir comme aujourd'hui ;
  - faut-il suivre l'arbitrage sur les chaînes de fabrications (non fait).
- L'édition des marchands et des offres depuis PlugAdmin relève de #17 : pour l'instant, un `price-ref` s'écrit dans le fichier du marchand. L'atelier de démo est fourni prêt.
- Le lien avec les recettes Bukkit (#18) n'est pas fait ; ici, la fabrication passe par la vitrine du PNJ.
- Pas d'historique ni de versionnement consultable au-delà des journaux et des transactions.

## Prochaine étape suggérée
Le propriétaire passe TC-342 sur DEV et tranche les points ci-dessus. Ensuite viennent #17 (marchands éditables dans PlugAdmin, offres `price-ref` choisies dans une liste) et #18 (recettes d'artisan sur les prix CRAFT).
