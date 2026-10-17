# LodyQuests — Registre de validation manuelle DEV

> **Source de vérité des validations faites par le propriétaire**, à mettre à jour au fil des sessions de test. Dernière consolidation : **17 octobre 2026**.
>
> Ce tableau **ne remplace pas** [le plan de test technique](MANUAL_TEST_PLAN.md) (TC-xxx), les tickets GitHub ou les rapports Claude. Il permet de savoir **quoi tester ensuite, ce qui a déjà été vérifié et ce qui bloque**, sans refaire les mêmes manipulations.

## Règles de suivi

- **À tester** : Claude a livré/déployé, pas encore vérifié par le propriétaire.
- **Partiel** : certains critères vérifiés en jeu / dans PlugAdmin, mais pas toute la recette.
- **Bloqué** : dépendance, bug ou décision empêchant le test.
- **Validé par propriétaire** : cas manuel passé, avec preuve (capture/résultat/action) et date. On peut fermer le ticket *si* ses autres critères sont couverts.
- **Clôturé** : ticket fermé sur GitHub après validation suffisante.
- Les tests automatisés et la CI sont notés comme **livraison technique**, jamais comme validation manuelle.
- Ne **jamais réinitialiser** progression, inventaire, base de données, worlds ou contenus utilisateurs pour faciliter un test sans consentement. Distinguer **DEV** et production.
- Chaque mise à jour ajoute date, personne, ticket/TC, résultat observé, défaut éventuel et action suivante. **Pas de fermeture automatique**.
- Privilégier une étape manuelle à la fois, écran réel, sans commandes côté joueur. Les réglages de test (boss, économie, scheduler) restent désactivés par défaut jusqu'à décision.
- Si un ticket dépend d'un autre, garder visible la dépendance, puis reprendre exactement au dernier jalon validé.

## Priorité immédiate (17/10/2026)

| Ordre | Ticket / test | État | Test concret / prochaine action | Rapport ou preuve |
|---|---|---|---|---|
| 1 | [#475](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/475) · TC-335 / TC-348 / **TC-349 / TC-351** kit et Elias | **Partiel (propriétaire)** ; dernier correctif à tester | Acquis : « Explorateur » et badge palier 3 visibles, dépôt partiel 1/4, remise en un clic et nouvelle présentation du kit jugée acquise par le propriétaire. **TC-351 prioritaire** : avec kit déjà reçu, « Demander mon kit de départ » → fenêtre d'Elias reste ouverte, explication « déjà reçu », boutons Retour/Fermer ; aucun kit supplémentaire. **Puis fin TC-349** : détails paginés du journal intégralement visibles. Ne pas reset ni répéter dépôt, ne pas provoquer une mort sans accord. | Rapport 2026-10-17_1848 ; PR #516 merge `a9d6934`, JAR + guide.yml DEV déployés, 204 tests ciblés/CI verts ; aucun test Minecraft post-correctif |
| 2 | [#467](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/467) · TC-338 dialogues | À tester | Lily : boutons **1 par ligne**, textes lisibles ; message démarrage sans `/quests` ; remise partielle et complète → bilan/merci ; tester Wild prompt | Rapport 2026-10-17_0252 |
| 3 | [#180](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/180) · TC-339 Lily/Julius | Partiel / à retester fin | Depuis progression Lily déjà obtenue, « Parle-moi de Julius » ne renvoie plus à l'intro ; **Nerana** et direction affichées ; rappel, journal nommé. **Arbitrage : confirmer Julius chef de Nerana** ; `lily_pumpkin` hors story à décider | Rapport 2026-10-17_0349 ; dialogue corrigé et publié |
| 4 | [#474](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/474) · TC-336 récompenses | À tester | PlugAdmin → Récompenses : initialiser catalogue **vide/désactivé** ; créer deux définitions choisies, sans activation implicite ; vérifier statut, erreurs et références | Rapport 2026-10-17_0005 ; `entitlements.yml` absent au rapport |
| 5 | [#177](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/177) · TC-333/331 tables | Bloqué par TC-336 | Créer table de test (chances, duplication, désactivation), associer au zombie, tester tirage réel, inventaire plein et absence de doublon | Rapport 2026-10-16_1845 |
| 6 | [#176](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/176) · TC-330 participation | Bloqué par TC-336 / plusieurs joueurs utiles | Boss zombie : un coup valide + rayon ≤100, >100, mort puis nouveau coup, deux joueurs ; attributs de récompense contrôlés | Rapport 2026-10-16_1638 ; rayon 100 configurable livré |
| 7 | [#214](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/214) · TC-341 mobs IA | À tester (parcours import) | Modèle BOSS complet → IA externe → import vérifié/diff → confirmation. **Zombie d'entraînement existe déjà** : utiliser autre ID d'essai ou prévisualiser sans écraser ; ne pas supposer que ce zombie a été créé via le nouveau flux | Rapport 2026-10-17_0600 |
| 8 | [#465](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/465) · TC-340 dialogues IA | À tester | Brigand : exporter depuis dialogue en source → modification IA → import même ID avec diff ; **tester sur copie**, pas sur dialogue réel sans autorisation | Rapport 2026-10-17_0430 |
| 9 | [#472](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/472) · estrade Elias | À tester | Atelier : relancer génération « L'estrade d'Elias », vérifier une marche de façade et pose réelle (pas de répétition PLAN_INVALID) | Rapport 2026-10-16_2242 |
| 10 | [#336](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/336) · TC-342 prix | À tester | PlugAdmin → Prix et coûts : 3 prix démo (vente, rachat, fabrication) ; vérifier refus incohérences, prix visible/payé. **Choisir ensuite** politique des prix 0, tarifs réels, règles conditionnelles | Rapport 2026-10-17_0620 ; `prices.yml` absent au rapport |
| 11 | [#17](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/17) · TC-344 marchands | À tester (dépend de prix pour prix liés) | PlugAdmin → Marchands : boutique + comptoir test, lier PNJ et dialogue, achat/rachat, solde, inventaire plein, double clic et prix modifié en cours d'achat | Rapport 2026-10-17_0806 ; aucun marchand modifié sur DEV |
| 11b | [#18](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/18) · TC-345 atelier artisan | À tester (dépend de prix #336) | PlugAdmin → Prix et coûts : créer trois recettes démo (ingrédients seuls, pièces seules, mixtes), puis Marchands → **Atelier d'artisan** ; associer à un dialogue PNJ, vérifier ingrédients possédés/manquants, manque de pièces, fabrication, double clic et inventaire plein. Aucune commande pour joueur ; **ne pas confondre marchand exemple chargé avec recettes opérationnelles** | Rapport 2026-10-17_1102 ; PR #500 / merge `0e56482`, JAR + PlugAdmin DEV ; `artisan_demo` chargé avec 3 offres, `prices.yml` absent au rapport |
| 11c | [#184](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/184) · TC-346 HUD bourse/niveau | À tester (prototype livré, OFF par défaut) | En Minecraft DEV, activer provisoirement `/hud sidebar` ; capturer affichage bourse réelle + niveau RPG avec quête suivie et bossbar, gains/dépenses et reconnexion ; désactiver via `/hud off`. **Arbitrage après essai : HUD par défaut ou autre interface** | Rapport 2026-10-17_1223, PR #502 merge `ca1de6b`, JAR DEV déployé, rendu réel non testé automatiquement |
| 11d | [#335](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/335) · paramètres généraux | À tester (audit : déjà livré sur DEV) | PlugAdmin → Paramètres généraux : Économie (renommer temporairement « LodyCoins » sans modifier le solde, puis remettre), Connexion (avertissement bêta), Administration (bypass OFF), persistance au redémarrage, droits et mobile. **Décider si solde de départ configurable est souhaité** ; non implémenté | Rapport 2026-10-17_1225, audit PR #337 / commit `9060a03` (fonction préexistante) |
| 11e | [#183](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/183) · TC-347 XP RPG et XP vanilla | À tester + décisions propriétaire | En DEV survie : vérifier absence de nouvelles orbes/niveaux vanilla (combat, minage, fours…), gain `+ N XP RPG` sur quête et progression persistante après mort/reconnexion/restart ; comparer avec HUD #184. **Décider : conservation ou effacement des anciens niveaux vanilla ; futur des enchantements, enclume, meule et raccommodage**. **Anomalie potentielle à traiter** : `xp-reward` des profils mobs devient sans effet tandis que le champ reste visible dans PlugAdmin. | Rapport 2026-10-17_1342, PR #507 merge `43f4596`, JAR DEV déployé ; aucune vérification joueur |
| 12 | [#171](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/171) · TC-337 boss de quête | À tester + décisions | Créer une quête test avec `KILL_QUEST_BOSS` et profil BOSS QUEST, voir spawn/waypoint/journal, mort, abandon et reload. **Valider auparavant les politiques propriétaire** (aide extérieure, 64 blocs, respawn 60 s, plafond 40) | Rapport 2026-10-17_0141 |
| 13 | [#471](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/471) · spawn autonome boss | À tester à la livraison du correctif | Tous joueurs au Hub/Claims → boss apparaît dans Wild → annonce biome/waypoint, glow si choisi, surface et persistance ; scheduler OFF sauf test autorisé | Bug NO_PLAYER constaté 16/10 |
| 14 | [#430](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/430) / [#369](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/369) · brigands catalogue | Partiel | Éditeur : dialogue récupéré, copie modifiable, action « hasard dans une liste » et catalogue sélectionnables. **Pas de publication du dialogue modifié**. Reprendre après TC-340 pour cohérence 0/1/N quêtes | Captures PlugAdmin 16/10 |
| 15 | [#421](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/421) · portail public | Partiel | « État inconnu » affiché correctement ; activer puis vérifier canal de statut VeryGames→AWS uniquement après validation sécurité | Captures portail 16/10 |
| 16 | [#166](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/166) · cœurs | Partiel | Cœur +1 après quête test, persistance mort/reconnexion, non-duplication après reset **validés** ; restent Stan/villes et persistance après restart | Test en jeu 16/10 |

| 21 | [#15](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/15) · **TC-350 quêtes quotidiennes / hebdomadaires** | **Partiel (lot 1 livré)** ; test manuel en attente | **Avant première ouverture du journal, contrôler l'activation des exemples et récompenses** : service périodique déjà `enabled: true` sur DEV, fuseau Europe/Paris, renouvellement 04:00 / lundi, 2 quotidiennes + 1 hebdomadaire, 4 quêtes démo chargées. Après accord, ouvrir le journal : onglets dédiés, attribution 2+1, stabilité reconnexion, progression, expiration sans récompense, un seul paiement par période. Tester changement d'heure/reset et non-duplication avec méthode sûre ; **ne pas changer l'heure système ni forcer reset d'un joueur**. **Lot 2 manquant** : onglets dédiés et gestion des modèles depuis PlugAdmin. | Rapport 2026-10-17_1820, PR #514 merge `c6a45c9`, JAR + PlugAdmin DEV, migration V42 ; aucun test Minecraft ; modèles et montants **démonstration** |
## Derniers correctifs livrés, encore à valider

- **17/10 #475 (PR #509, `d0c8bdd`)** : Guide renommé « Elias » dans les textes dynamiques, un seul bouton de remise quand l'inventaire contient un objet attendu, bilan intégré au dialogue et descriptions du journal repliées. Correctif également appliqué au moteur partagé ; autres dialogues à plusieurs écrans non republiés. **TC-348 en attente, pas de validation manuelle du nouveau comportement.**

## Historique des tests propriétaire réellement effectués

| Date | Ticket | Résultat observé | Portée de la validation |
|---|---|---|---|
| 16/10 | [#461](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/461) | **SUCCESS/UPDATED**, action `587674cc-8982-4ed6-a3b7-6e90b4ac56b1` ; Diag Boss conserve `swamp,plains`, monde `wild`, COMMUNITY | **Validé, ticket clôturé** : sauvegarde profil multi-biomes sans YAML invalide |
| 16/10 | [#175](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/175) | **SUCCESS/SPAWNED**, zombie `rpgquest:boss_zombie_entrainement` apparu près de LoDyMcFly, nom/barre de vie visibles, tué avec annonce « a été vaincu … (test) » | **Partiel** : spawn manuel et victoire ; scheduler, annonce localisation et rappel restent |
| 16/10 | [#471](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/471) | Apparition sans joueur Wild **FAILED/NO_PLAYER**, action `f9f09ac1-48b3-4b99-b8bb-2ee28a2e5e68` ; boss difficile à localiser avec autre méthode | Défaut reproduit ; correctif demandé, glow/surface configurables |
| 16/10 | [#180](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/180) | Repas et fleurs Lily enchaînés ; étapes tarte/Julius atteintes ; « Parle-moi de Julius » revenait à l'intro | **Partiel** ; correctif publié par Claude le 17/10, **pas encore retesté** |
| 16/10 | [#430](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/430) | Dialogue Brigand serveur lu, copié dans PlugAdmin, action liste aléatoire et catalogue visibles. Éditions manuelles **non enregistrées/pas publiées** | **Partiel**, passer au workflow IA #465 |
| 16/10 | [#425](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/425) | Champ « Prompt prêt à copier » pleine largeur confirmé par capture | **Validé et clôturé** |
| 16/10 | [#427](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/427) | Vérification pack Lily PREVIEW_OK puis publication 3/3, dialogues visibles en jeu | **Validé et clôturé**, non équivalent à terminer l'histoire |
| 16/10 | [#166](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/166) | 10→11 cœurs, persiste après reconnexion/mort, 2e tentative refuse un second gain | **Partiel**, Stan et restart non testés |

## Dépendances et décisions à garder visibles

- **Quêtes périodiques #15** : configuration DEV déjà active (`enabled: true`, `Europe/Paris`, `04:00`, lundi, 2 quotidiennes + 1 hebdomadaire) et modèles de démonstration contenant des gains de pièces et XP. La première ouverture du journal débloque les quêtes périodiques. **Décision propriétaire sur activation/volume/valeurs/expiration impérative avant de considérer ces paramètres comme définitifs.** Lot 2 PlugAdmin requis pour leur administration complète. Les quêtes expireront sans paiement à la frontière de période sauf si `READY_TO_TURN_IN` (conservée), selon le rapport. 


1. **Récompenses** : #474 (catalogue créé et activé consciemment) → #177 (tables) → #176 (test de participation en jeu). Ne pas créer `entitlements.yml` à la main.
2. **Économie** : #336 (prix) → #17 (marchands) → #18 (artisans, **code livré mais TC-345 non validé**), en conservant l'absence de tarifs définitifs ; seuls les prix de démonstration peuvent être employés pour essais. **#18** : trois modèles de recettes (ingrédients seuls, pièces seules, mixte), aucun disponible tant que les prix correspondants ne sont pas créés. Arbitrer plus tard liaison avec recettes Bukkit, conditions d'accès, tarifs réels.
3. **HUD et paramètres** : #184 = prototype `/hud sidebar` avec choix OFF par défaut, à juger visuellement ; #335 = renommage de la monnaie et réglages connexion/bypass, **pas** de paramétrage du solde initial. Décider du solde initial avant une éventuelle extension.
4. **Progression RPG #183** : XP RPG GLOBAL persistante préexistante ; XP vanilla de survie bloquée par défaut, sans supprimer/conversion des niveaux historiques. Risque UX : l'ancienne jauge Minecraft reste figée ; enchantements, enclume et raccommodage n'ont pas de remplacement fonctionnel. Le champ `xp-reward` des mobs ne produit plus de récompense, à arbitrer/corriger. Aucune décision définitive ni test manuel.
5. **IA** : #465 (dialogues) et #214 (mobs) sont deux parcours d'import distincts, tous deux livrés **mais non validés par le propriétaire**.
5. **Julius** : #180 indique Julius positionné au centre de **Nerana** (`ville_nerana`), à environ 420 blocs au nord de Lily ; le propriétaire doit **confirmer** ce rattachement. Décider éventuellement PNJ central/point d'arrivée #322 ; `lily_pumpkin` reste hors story.
6. **Boss de quête #171** : confirmer monde partagé/instance possédée, crédits, mort/recréation, plafond et effets sur objectifs KILL_ENTITY. Rien n'autorise leur adoption comme règles définitives simplement parce que Claude les a codées.
7. **#475** : enquête DEV suggère qu'« Explorateur » était déjà listé mais non identifié comme quête de kit ; **l'absence initiale n'a pas été reproduite**. Un vrai test utilisateur après correctif est indispensable.
8. **#214** : le zombie déjà créé en jeu prouve que les profils et le spawn existent, **pas** que le nouveau flux export → IA → import est validé.

## Mode opératoire pour la tenue du registre

Après **chaque** livraison Claude ou séance de jeu, modifier ce fichier dans le même commit/PR ou un commit documentaire séparé :
- ajouter/mettre à jour la ligne du ticket et son **TC** ;
- indiquer **version DEV** (commit si connu), captures/logs/actions vérifiables, joueur testeur sans secret ;
- renseigner ce qui a réellement été essayé, ce qui est uniquement testé automatiquement et les cas négatifs ;
- faire apparaître « prochaine étape » claire et aussi courte que possible ;
- en cas d'échec, créer/lier un bug GitHub et garder le ticket parent **partiel**, sans marquer « Validé » ;
- quand tout le périmètre est vérifié, proposer la fermeture puis reporter la clôture effective dans le tableau/historique.

**Responsabilités :** Claude met à jour ce registre après ses livraisons ; ChatGPT l'actualise après les tests que le propriétaire décrit ici. **Aucun agent ne doit déclarer un test manuel réussi sans preuve fournie par le propriétaire.**
