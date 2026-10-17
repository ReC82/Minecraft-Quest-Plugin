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
| 1 | [#475](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/475) · TC-335 journal kit | À tester | Sur compte existant, ouvrir Journal → **Explorateur** : badge « Amélioration du kit — palier 3 » et chaque dépôt **remis/requis**, PNJ Guide ; ensuite dépôt partiel, reconnexion, fin sans reset | Rapport 2026-10-16_2124 ; audit DEV : kit_tier2 COMPLETED, kit_tier3 ACTIVE |
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
| 12 | [#171](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/171) · TC-337 boss de quête | À tester + décisions | Créer une quête test avec `KILL_QUEST_BOSS` et profil BOSS QUEST, voir spawn/waypoint/journal, mort, abandon et reload. **Valider auparavant les politiques propriétaire** (aide extérieure, 64 blocs, respawn 60 s, plafond 40) | Rapport 2026-10-17_0141 |
| 13 | [#471](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/471) · spawn autonome boss | À tester à la livraison du correctif | Tous joueurs au Hub/Claims → boss apparaît dans Wild → annonce biome/waypoint, glow si choisi, surface et persistance ; scheduler OFF sauf test autorisé | Bug NO_PLAYER constaté 16/10 |
| 14 | [#430](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/430) / [#369](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/369) · brigands catalogue | Partiel | Éditeur : dialogue récupéré, copie modifiable, action « hasard dans une liste » et catalogue sélectionnables. **Pas de publication du dialogue modifié**. Reprendre après TC-340 pour cohérence 0/1/N quêtes | Captures PlugAdmin 16/10 |
| 15 | [#421](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/421) · portail public | Partiel | « État inconnu » affiché correctement ; activer puis vérifier canal de statut VeryGames→AWS uniquement après validation sécurité | Captures portail 16/10 |
| 16 | [#166](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/166) · cœurs | Partiel | Cœur +1 après quête test, persistance mort/reconnexion, non-duplication après reset **validés** ; restent Stan/villes et persistance après restart | Test en jeu 16/10 |

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

1. **Récompenses** : #474 (catalogue créé et activé consciemment) → #177 (tables) → #176 (test de participation en jeu). Ne pas créer `entitlements.yml` à la main.
2. **Économie** : #336 (prix) → #17 (marchands) → #18 (artisans), en conservant l'absence de tarifs définitifs ; seuls les prix de démonstration peuvent être employés pour essais.
3. **IA** : #465 (dialogues) et #214 (mobs) sont deux parcours d'import distincts, tous deux livrés **mais non validés par le propriétaire**.
4. **Julius** : #180 indique Julius positionné au centre de **Nerana** (`ville_nerana`), à environ 420 blocs au nord de Lily ; le propriétaire doit **confirmer** ce rattachement. Décider éventuellement PNJ central/point d'arrivée #322 ; `lily_pumpkin` reste hors story.
5. **Boss de quête #171** : confirmer monde partagé/instance possédée, crédits, mort/recréation, plafond et effets sur objectifs KILL_ENTITY. Rien n'autorise leur adoption comme règles définitives simplement parce que Claude les a codées.
6. **#475** : enquête DEV suggère qu'« Explorateur » était déjà listé mais non identifié comme quête de kit ; **l'absence initiale n'a pas été reproduite**. Un vrai test utilisateur après correctif est indispensable.
7. **#214** : le zombie déjà créé en jeu prouve que les profils et le spawn existent, **pas** que le nouveau flux export → IA → import est validé.

## Mode opératoire pour la tenue du registre

Après **chaque** livraison Claude ou séance de jeu, modifier ce fichier dans le même commit/PR ou un commit documentaire séparé :
- ajouter/mettre à jour la ligne du ticket et son **TC** ;
- indiquer **version DEV** (commit si connu), captures/logs/actions vérifiables, joueur testeur sans secret ;
- renseigner ce qui a réellement été essayé, ce qui est uniquement testé automatiquement et les cas négatifs ;
- faire apparaître « prochaine étape » claire et aussi courte que possible ;
- en cas d'échec, créer/lier un bug GitHub et garder le ticket parent **partiel**, sans marquer « Validé » ;
- quand tout le périmètre est vérifié, proposer la fermeture puis reporter la clôture effective dans le tableau/historique.

**Responsabilités :** Claude met à jour ce registre après ses livraisons ; ChatGPT l'actualise après les tests que le propriétaire décrit ici. **Aucun agent ne doit déclarer un test manuel réussi sans preuve fournie par le propriétaire.**
