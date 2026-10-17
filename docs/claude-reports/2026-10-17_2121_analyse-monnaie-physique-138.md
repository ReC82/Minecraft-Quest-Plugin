# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-17
* Heure : 21:21
* Sujet : Issue #138 — analyse de la monnaie physique LodyQuests basée sur un item PDC (analyse seulement)
* Statut : DONE (analyse livrée ; ticket en attente de décisions du propriétaire)
* Branche Git : `docs/138-analyse-monnaie-physique` (depuis `chore/257-ci-github-actions` `67cf1e7`)
* Commit actuel si disponible : voir la PR de ce rapport
* Début de la tâche : 2026-10-17 21:19:14
* Fin de la tâche : 2026-10-17 21:21:53
* Durée totale : 00:02:39

## Demande

Session autonome `lqorch` sur #138, avec consigne du propriétaire (16/10) : **analyse seulement** —
auditer l'existant, rédiger options, dépendances, risques, découpage en lots et questions à
trancher, en commentaire sur le ticket et dans ce rapport. Aucune implémentation, aucune PR de
code, aucun déploiement.

## Analyse

Audit du code de la branche d'intégration (`67cf1e7`) :

* `economy/EconomyService` + tables `wallets` / `transactions` : source de vérité des soldes,
  opérations atomiques sérialisées sur le thread BD ; `CurrencyName` (« LodyCoins », #335) purement
  affiché ; `MoneyReward` payé au portefeuille et idempotent par `grantId`.
* Marchands, artisans et catalogue de prix (#17, #18, #336) : uniquement en pièces du portefeuille.
* `/market` sérialise l'`ItemStack` complet (PDC compris) → une monnaie physique y serait vendable.
* `/money pay` : transfert P2P déjà actif, en contradiction avec la phrase « pas de P2P » de #139.
* `item/YamlCustomItemRegistry` : identité par PDC `rpgquest:custom_item_id`, jamais nom/lore ;
  `custom-model-data` et `item-model` déjà gérés (Paper 1.21.11).
* `SoulboundItemService` (drop/mort) et `ItemDelivery` (#318, remise sûre) réutilisables.
* `crafting/RecipeCraftGuardListener` ne garde que les recettes RPGQuest : la recette vanilla de la
  boussole de récupération (8 `ECHO_SHARD`) consommerait la monnaie.
* Aucune branche ni code existant pour #138/#139.

Conclusions (détail dans le commentaire GitHub) :

* Option recommandée **A** : monnaie « au porteur » adossée au portefeuille (retrait = débit puis
  objets, dépôt = objets retirés puis crédit), registre d'émission pour contrôler la masse
  monétaire. Options B (jetons indépendants, multi-devises) et C (remplacement du portefeuille,
  écartée par le périmètre du 12/10) documentées.
* Clé PDC dédiée `rpgquest:currency`, sans numéro de série (empilement), définition configurable
  désactivée par défaut, `PhysicalCurrencyService` (`isCurrency`, `currencyId`, `create`, `count`,
  `remove` tout-ou-rien).
* Risques : duplication (un PDC ne l'empêche pas), crash entre SQL et inventaire, consommation
  vanilla, destruction, P2P de fait, encombrement, rééquilibrage de valeur.
* Lots : 1 socle + création admin (désactivé) ; 2 garde-fous #139 ; 3 retrait/dépôt banquier avec
  migration ; 4 consommateurs (marchands, récompenses) ; 5 supervision PlugAdmin.
* 8 questions au propriétaire : modèle, conversion, valeur/coupures, support et nom, politique
  #139 (drop, mort, stockage, marché, P2P), récompenses en objets, création admin, priorité vis-à-vis
  de #336/#17/#18 non validés en jeu.

## Travail effectué

* Commentaire d'analyse publié sur #138 :
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/138#issuecomment-6239450116
* Note de dépendance publiée sur #139 :
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/139#issuecomment-6239451172
* Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne #138 ajoutée (« Bloqué — décisions
  propriétaire », aucun test en jeu) et dépendance économie complétée ; point #475 de `main`
  (« absence initiale non reproduite ») reporté dans la décision correspondante pour ne rien perdre.
* Ce rapport et son entrée d'index.

## Fichiers créés

* `docs/claude-reports/2026-10-17_2121_analyse-monnaie-physique-138.md`

## Fichiers modifiés

* `docs/claude-reports/README.md` (index)
* `docs/SUIVI_VALIDATIONS_DEV.md`

## Base de données / migrations

Aucune.

## Configuration / données

Aucune.

## Tests automatiques

Aucun : aucun code modifié (PR documentaire, pas de CI déclenchée).

## Tests manuels à effectuer

Aucun : rien n'est livré en jeu. Prochaine étape = décisions du propriétaire sur #138.

## Résultat attendu

Le propriétaire dispose d'une analyse lisible sur #138 pour trancher le modèle avant tout code.

## Reset / retour à l'état initial

Sans objet.

## Déploiement VeryGames

### À transférer

Rien.

### Ne PAS transférer/altérer

Tout : aucun changement serveur.

### Redémarrage requis

Non.

### Migration automatique

Non.

## Rollback

Sans objet (documentation seulement).

## Logs / diagnostic

Sans objet.

## Documentation mise à jour

`docs/SUIVI_VALIDATIONS_DEV.md`, index des rapports. `docs/ECONOMY.md` et la Bible ne changent pas :
aucun comportement nouveau.

## Limitations / travail restant

* Toute implémentation attend les réponses aux 8 questions du commentaire.
* La contradiction `/money pay` (P2P actif) ↔ « pas de P2P » de #139 doit être levée.

## Prochaine étape suggérée

Après décision du propriétaire : lot 1 (définition + `PhysicalCurrencyService` + tests, désactivé
par défaut) sur une branche `feature/138-monnaie-physique` depuis la branche d'intégration.
