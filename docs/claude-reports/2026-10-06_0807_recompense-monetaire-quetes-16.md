# #16 — Récompense monétaire de quête, gestion panel et solde dans l'interface joueur

## Informations

* Date : 2026-10-06
* Issue : [#16](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/16)
* Branche : `feature/169-special-mobs-boss` (aucun merge)
* Commits : `ee96254` (lot #16), plus le commit de clôture documentaire — voir §7
* Début de la tâche : 2026-10-06 08:07:52 (heure locale réelle, première action)
* Fin de la tâche : 2026-10-06 09:45:00 (heure locale réelle)
* Durée totale : 01:37:08

**Aucun ticket GitHub n'a été modifié ni fermé. Aucun test manuel n'a été coché ni présenté comme
exécuté. Aucun mail, aucune annonce serveur.**

---

## 1. Audit préalable : ce qui existait déjà

J'ai lu le code avant de concevoir, et trois constats ont décidé l'implémentation.

**(a) Le socle économique existait, complet.** `EconomyService` au-dessus de `WalletRepository`
(tables `wallets` + `transactions`), avec crédit, débit refusant de passer sous zéro, transfert
atomique, réglage admin et — depuis le lot précédent — lecture du journal. Il n'y avait donc
**rien à recréer** : le travail consistait à brancher les quêtes dessus, pas à inventer une
monnaie.

**(b) Les récompenses sont un `sealed interface`.** `QuestReward permits ExperienceReward,
ItemReward, VariableReward, CommandReward` : ajouter un type force le compilateur à signaler
**tous** les endroits qui traitent les récompenses (parseur, moteur, journal, agent, content pack).
C'est ce qui a rendu l'ajout sûr : aucun `switch` n'a pu être oublié en silence.

**(c) Côté panel, l'éditeur est entièrement piloté par descripteurs.** `Descriptors.REWARDS`
alimente à lui seul le formulaire, l'écriture YAML, la relecture YAML et la validation. Ajouter un
type de récompense = ajouter **un** descripteur. Je n'ai donc écrit aucun formulaire spécifique.

Un quatrième constat a orienté la conception du message joueur : `grantRewards` est
**synchrone**, alors que le crédit du portefeuille est **asynchrone**. Les autres récompenses
peuvent être annoncées dans la foulée ; une récompense monétaire, non — sauf à annoncer un gain
avant d'avoir la preuve qu'il a eu lieu.

---

## 2. Le point difficile : « jamais deux fois, jamais perdue »

C'est l'exigence centrale du ticket, et elle ne se règle pas dans le moteur de quêtes.

Le moteur a déjà une garde anti-double-remise : `turnIn` bascule l'état en mémoire **de façon
synchrone** avant toute opération asynchrone. Mais cette garde ne protège que les chemins que je
peux énumérer. Elle ne dit rien d'un retry, d'un crash entre le crédit et son enregistrement, ou
d'un appelant futur.

**La garantie a donc été placée en SQL**, là où elle est vraie par construction :

```java
public CompletableFuture<QuestRewardGrant> creditQuestReward(
        UUID uuid, String questId, String grantId, long amount, String type, String context)
```

Une **seule transaction SQL** fait trois choses : réserver `grantId` dans la nouvelle table
`quest_reward_grants` (`INSERT OR IGNORE`), mettre à jour `wallets`, et écrire la ligne
`transactions`. Conséquence directe :

- si l'insertion ne prend pas (occasion déjà réservée) → **rien n'est crédité**, et le reçu dit
  `ALREADY_CREDITED` ;
- si quoi que ce soit échoue → **tout** est annulé : ni argent sans trace, ni trace sans argent, et
  l'occasion reste payable ;
- il n'existe **aucun instant** entre les deux où un retry pourrait doubler le gain.

L'identifiant d'occasion vient de la progression du joueur (`ActiveQuestProgress#rewardGrantId`,
généré une fois puis conservé) : rejouer la **même** remise présente le **même** identifiant et ne
paie rien, tandis qu'une quête répétable repart d'une nouvelle progression, donc d'une nouvelle
occasion légitimement payée. `quest_reward_grants.occurrence` numérote ces complétions par joueur
et par quête — c'est la « occurrence de complétion » demandée par le ticket.

**Aucune seconde source de vérité.** Cette table ne contient **aucun solde** : `wallets` reste le
seul solde, et `quest_reward_grants` ne répond qu'à « cette occasion a-t-elle déjà été payée ? ».

Un détail de schéma qui n'est pas un oubli : la table est volontairement **sans clé étrangère** vers
`player_profiles`, contrairement à `transactions`. Un `ON DELETE CASCADE` rendrait un profil
supprimé puis recréé payable une seconde fois pour les mêmes occasions. Une ligne orpheline ne
coûte rien ; un double paiement, si.

---

## 3. Ce qui a été livré

### Moteur (plugin)

| Élément | Détail |
|---|---|
| `RewardType.MONEY` + `MoneyReward(int amount)` | montant **strictement positif**, aucun plafond moteur |
| `QuestDefinitionParser` | `type: MONEY` / `amount:` ; montant absent ou ≤ 0 → erreur de chargement |
| Schéma **V25** | `quest_reward_grants` (clé primaire `grant_id`) + index `(player_uuid, quest_id)` |
| `WalletRepository#creditQuestReward` | crédit idempotent en **une** transaction SQL (voir §2) |
| `TransactionType.QUEST_REWARD` | trace au journal, contexte `quest:<id>#<occasion>` |
| `QuestRewardPayer` / `QuestRewardReceipt` | port minimal implémenté par `EconomyService` ; reçu **typé** `CREDITED` / `ALREADY_CREDITED` / `FAILED` |
| `QuestProgressEngine#payMoneyReward` | paie, puis annonce **seulement** si la base a confirmé |
| `messages.yml` | `quest.reward-money-credited` (montant + solde relu), `quest.reward-money-failed` |
| Agent + content pack | `describeRewards`, `rewardDetails` (`kind: MONEY`), `QuestPackEntry.Reward.money` |

**Pourquoi le montant est un `int` et non un `long`** : c'est un nombre *conçu* par un auteur de
contenu, comme l'XP ou une quantité d'objets — pas un solde *accumulé*. Le solde reste un `long`
partout. Ce choix garde aussi inchangés deux formats publics (`QuestPackEntry.Reward.amount`,
`AgentActions.RewardSummary.amount`).

### Control Panel

- **Descripteur `MONEY`** dans l'éditeur de quête : un seul champ (montant entier), icône de pièce
  (glyphe vérifié présent dans la police embarquée, donc jamais le repli silencieux
  `info-circle`), et une aide qui dit explicitement que le crédit va au **portefeuille persistant**,
  qu'**aucun objet n'est donné** et qu'aucun objet d'inventaire n'est compté comme de la monnaie.
  Ajout, modification, retrait et réordonnancement viennent du mécanisme existant.
- **Validation backend** : entier strictement positif (erreur sinon), via le mécanisme de champs
  `INT` déjà en place. Au-delà de 1 000 000 :
  **avertissement**, jamais refus — l'équilibrage appartient à l'auteur, et refuser reviendrait à
  décider du gain maximum à sa place. Un rappel `info` explique systématiquement où va l'argent.
- **Affichage admin** : `RewardText` lit `MONEY` comme « N pièce(s) », y compris l'ancien format
  texte, et surtout **jamais** comme un objet — un admin doit voir d'un coup d'œil que cette
  récompense crédite un solde au lieu de remplir un inventaire.
- **États source / publié / chargé (#131)** : la bannière d'enregistrement d'une quête disait « le
  fichier sera validé au prochain chargement du serveur », ce qui laissait croire qu'un redémarrage
  suffisait. Elle dit maintenant **source → publié → rechargé** et renvoie vers l'aperçu et le
  rechargement de `/quests`. C'est particulièrement important ici : une quête restée dans la source
  ne paierait **personne**, sans aucune erreur visible en jeu.

### Interface joueur

- **Bourse dans le journal de quêtes** (`/quests`, ou clic droit sur l'item Journal) : un
  emplacement **inerte** de la barre du haut, en vue liste **et** en vue détail. C'était la demande
  explicite du ticket — le solde doit être lisible dans une interface déjà utilisée, sans imposer
  de connaître `/money`.
- Le montant vient du **portefeuille réel** (la même source que `/money`, les marchands et le
  panel) et est relu à chaque ouverture **et après chaque transaction** : le crédit déclenche la
  notification de changement existante, qui recompose un menu ouvert.
- **Gestion honnête des erreurs** : si le solde ne peut pas être lu, la bourse affiche
  « indisponible » avec son motif. Jamais un `0` inventé, qui ferait croire à un vol — et jamais un
  journal cassé : l'échec de lecture ne fait pas échouer l'ouverture du menu.
- **Récompense prévue** dans l'infobulle de détail (« 250 pièce(s) »), au même titre que l'XP.
- **Confirmation après crédit réussi**, dans le **chat** : montant **et** nouveau solde, relu en
  base. En cas d'échec, un message explicite. Si l'occasion était déjà payée : **rien** pour le
  joueur (ce serait un gain fantôme) et un avertissement dans les logs serveur.
- **La progression des objectifs est préservée.** Ces messages passent par le chat et **jamais**
  par l'ActionBar : l'ActionBar appartient à la progression des objectifs et se remplace en place,
  donc y écrire une notification d'économie effacerait durablement l'avancement affiché. C'est une
  exigence explicite du ticket et c'est verrouillé par la conception, pas seulement par une
  intention.

---

## 4. Hors périmètre, et pourquoi

Conformément au ticket, je n'ai **pris aucune décision de gameplay**. Restent à décider :

- **monnaie physique et conversion solde ↔ objet** (#138) : ce serait une seconde source de vérité,
  donc une décision structurante ;
- **perte à la mort**, **prix**, **règles d'échange**, **équilibrage des gains** ;
- **plafond de gain** : aucun côté moteur, volontairement.

Aucune conversion, aucune migration, aucun solde existant modifié. Aucun objet vanilla transformé
implicitement en monnaie. Aucun montant posé sur une quête réelle.

---

## 5. Tests

### Suite complète — verte

| Module | Tests | Ignorés | Échecs | Erreurs |
|---|---|---|---|---|
| plugin | **1638** | 34 | 0 | 0 |
| control-panel | **586** | 1 | 0 | 0 |
| web-api | **30** | 0 | 0 | 0 |
| **Total** | **2254** | 35 | **0** | **0** |

`./gradlew test` (22 min 34 s) puis `./gradlew build` : tous deux `BUILD SUCCESSFUL`.
Progression : 1603 → **1638** côté plugin (**+35**) et 577 → **586** côté panel (**+9**),
soit **44 cas nouveaux** pour ce lot. Totaux relevés dans les XML JUnit réels, pas estimés.

### Ce qui est couvert, et où

| Suite | Cas | Ce qu'elle verrouille |
|---|---|---|
| `WalletRepositoryTest` | +8 | **la garantie elle-même, en SQL** : crédit unique tracé, rejeu du même grant qui ne recrédite rien, rejeu avec un autre montant qui rapporte le montant **réellement** payé, nouvelle occurrence payée pour une répétable, occurrences comptées par joueur **et** par quête, refus d'un montant ≤ 0 et d'un `grantId` vide, addition à un solde existant, survie à un redémarrage sans second paiement, et **20 rejeux concurrents qui créditent exactement une fois** |
| `SchemaMigratorTest` | +2 | la table existe, et **la contrainte d'unicité refuse deux lignes pour la même occasion** — sans elle, le code applicatif continuerait de compiler et paierait deux fois |
| `QuestProgressEngineTest` | +7 | le comportement du **moteur** : une seule demande par complétion, message de succès **après** confirmation et portant le solde du reçu, échec dit honnêtement sans citer de montant, occasion déjà payée silencieuse, **aucune ligne monétaire dans le résumé synchrone**, occasions distinctes pour une répétable, et aucune sollicitation de l'économie quand la quête n'a pas de récompense monétaire |
| `QuestMoneyRewardIntegrationTest` | 5 (nouveau) | que les morceaux sont réellement **branchés** : vrai moteur + vrai `EconomyService` + vraie base SQLite, complétion réelle, solde réel, journal réel, reconnexion, et complétion forcée admin |
| `QuestDefinitionParserTest` | +4 | lecture YAML, refus des montants invalides, acceptation d'un gros montant, coexistence et **ordre** avec les autres types |
| `QuestJournalServiceTest` | +5 | solde réel affiché, bourse en vue détail, portefeuille vierge affiché `0` et non une panne, slot **inerte**, récompense prévue dans l'infobulle |
| `ManualTestQuestPackTest` | +3 | le pack de test manuel reste chargeable et couvre répétable **et** non répétable |
| `EditorDescriptorsTest` | +2 | un seul champ, type `INT`, aide qui parle de portefeuille et d'absence d'objet |
| `ContentYamlRoundTripTest` | +4 | aller-retour YAML exact, **préservation des récompenses existantes et de leur ordre**, validation backend, avertissement sans refus |
| `RewardTextTest` | +3 | « pièces » et non « objet », y compris sur l'ancien format texte |

### Deux défauts de mes propres tests, mesurés et non supposés

1. **`performTicks` ne consomme aucun temps d'horloge.** Mon attente de message pompait 200 ticks
   d'affilée, ce qui s'exécute en quelques microsecondes — la boucle pouvait donc s'épuiser
   entièrement **avant** la fin de l'écriture asynchrone en base, et le test échouait pour une
   raison étrangère au code testé. Les attentes du test d'intégration pompent désormais les ticks
   **et** laissent passer du temps réel.
2. **Le slot 4 porte l'indicateur de page en vue liste.** Mon test de la bourse en vue détail
   attendait que le slot 4 soit non nul : il l'était immédiatement, encore en vue liste, et le test
   lisait « Page 1/1 » au lieu du solde. Il attend maintenant l'icône de détail, qui n'existe que
   dans cette vue.

### Un test existant a refusé mon code, et il avait raison

`EditorDescriptorsTest.rewardKindsMatchTheEngineExactly` comparait les descripteurs du panel aux
**quatre** types du moteur. L'ajout d'un cinquième l'a fait échouer : exactement son rôle. Je l'ai
mis à jour sur la réalité (cinq types), sans l'affaiblir.

---

## 6. Déploiement

### Déployé et vérifié

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| **JAR VeryGames DEV** | SHA-256 `6fe9952576cc9240ccaf4cf1639d603d6739585df56f1ecd309315cb03f26f7d` (1 807 514 octets, commit `ee96254`) | `DEPLOY_EXIT=0`, puis `JAR en ligne : 1807514 octets (== local)` |
| **Control Panel AWS** | distribution `20261006-090923` | `PANEL_EXIT=0`, `/health` → `{"panel":"ONLINE"}`, service `active` |

**Backup préalable** : `rpgquest-20261006T074047Z-predeploy.jar` (1 796 897 octets, SHA-256
`3a29b540…` — le JAR du lot économie). **Le backup précédent n'a pas été écrasé.**

**Un seul redémarrage Minecraft** (`RESTART_EXIT=0`) : arrêt **constaté OFFLINE**, retour
**constaté ONLINE**. Après redémarrage, par RCON : `plugins` → **4 plugins verts** (Citizens,
Multiverse-Core, RPGQuest, WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`.

### Preuve que le code neuf est réellement chargé

Le numéro de version ne change pas d'un lot à l'autre : il ne prouve rien. J'ai donc vérifié la
seule chose qui ne peut pas mentir — le **schéma de la base du serveur**, puisque ce lot apporte
une migration. Copie de `RPGQuest/data.db` récupérée **en lecture seule** par FTP, lue localement,
puis **supprimée** :

| Contrôle | Résultat observé |
|---|---|
| `PRAGMA user_version` | **25** (était 24 avant ce lot) |
| Table `quest_reward_grants` | **présente**, avec exactement le schéma écrit dans la migration V25 |
| Lignes dans cette table | **0** — personne n'a joué, donc aucune récompense n'a été payée |

Aucune écriture sur le serveur, aucune donnée joueur modifiée, aucun solde touché.

### Les deux échecs de déploiement rencontrés, et ce qu'ils signifiaient

**Le premier a été mal lu de ma part, et je le dis explicitement.** La commande d'attente qui
enveloppait le script a rendu `exit code 0` — ce qui signifie seulement que *l'attente* s'est
terminée normalement. Le résultat réel était `DEPLOY_EXIT=1` : le script avait **refusé de
livrer**. Seul `DEPLOY_EXIT` prouve un déploiement ; le code de sortie de l'enveloppe ne prouve
rien. C'est la distinction demandée par le propriétaire, et elle est maintenant écrite dans
`docs/deployment/VERYGAMES.md`.

| Tentative | Pas atteint | Motif réel | Conséquence |
|---|---|---|---|
| 1 | **3/8** (`./gradlew test`) | `Java heap space` sur deux workers — le script relance les tests lui-même et héritait d'un shell **sans** `RPGQUEST_TEST_MAX_HEAP=768m`. Échec d'**environnement**, pas de code : la même suite était verte 20 min plus tôt avec le plafond. | **Aucun transfert** : le garde-fou a fait son travail |
| 2 | **5/8** (confirmation) | `/dev/tty: No such device or address` — le script demande à l'écran si le serveur est arrêté, et il n'y a pas de terminal. | **Aucun transfert** |
| 3 | **8/8** | — | JAR transféré, taille en ligne relue identique au local |

Dans les deux échecs, le serveur n'a **jamais** été laissé arrêté ni dans un état partiellement
déployé : les deux interruptions ont eu lieu **avant** la moindre opération FTP.

**Correctif documentaire** (demandé par le propriétaire pour éviter la répétition) :
`docs/deployment/VERYGAMES.md` porte désormais deux encadrés — le **plafond mémoire obligatoire**
sur la machine de build AWS (avec le symptôme exact à reconnaître) et le **déploiement non
interactif** (`-y`), plus la séquence complète réellement utilisée et l'avertissement sur les deux
codes de sortie. Le script lui-même n'a **pas** été modifié, et n'a jamais été touché pendant son
exécution.

---

## 7. Branche, commits et validation restante

### Branche et commits

**Branche : `feature/169-special-mobs-boss`** (la branche d'intégration en cours, conformément à
`CLAUDE.md`). **Aucun merge**, aucun push vers `main`, aucune intervention sur la PROD.

| Commit | Contenu |
|---|---|
| `ee96254` | `feat(quest): récompense monétaire de quête, créditée une seule fois par complétion (#16)` — **tout le lot** : moteur, schéma V25, panel, interface joueur, tests, documentation et contenu de test manuel |
| _(clôture)_ | `docs`: SHA réellement déployé, changelog serveur, roadmap, index des rapports et procédure de déploiement |

Le lot tient en **un seul commit fonctionnel** : les trois parties (moteur, panel, interface
joueur) ne sont pas séparables sans laisser le dépôt dans un état où un type de récompense existe
d'un côté et pas de l'autre.

Commits précédents de la branche, non inclus dans ce lot mais présents sur le serveur :
`b9cb971` (clôture de la nuit), `b7686c4` (économie lot 1), `50b5aad` (#210), `61623f8` (#131).

### Limites connues

- **Décisions de gameplay non prises** (volontairement) : monnaie physique et conversion
  solde ↔ objet (#138), perte à la mort, prix, règles d'échange, équilibrage des gains. Aucun
  montant n'a été posé sur une quête réelle.
- **Aucun plafond de montant côté moteur.** C'est un choix, pas un oubli : décider du gain maximum
  serait décider de l'équilibrage. Le panel se contente d'avertir au-delà d'un million.
- **Un crédit échoué n'est pas réessayé automatiquement.** Il est dit honnêtement au joueur et
  journalisé côté serveur ; l'administrateur peut créditer depuis le panel avec une raison (lot
  économie précédent). Un mécanisme de reprise automatique serait un lot à part entière : mal fait,
  il risquerait précisément le double paiement que ce lot interdit.
- **Rien ne transporte le contenu du panel AWS vers VeryGames.** Une quête restée dans la source ne
  paiera personne, sans erreur visible en jeu. C'est pour cela que la bannière d'enregistrement
  nomme désormais les trois états.
- **Aucun test en jeu exécuté.** Le rendu visuel de la bourse et des infobulles, l'ordre perçu des
  messages et le parcours de l'éditeur dans un navigateur restent à valider.

### Protocole manuel ajouté

**TC-250** dans `docs/MANUAL_TEST_PLAN.md` (27 étapes, 7 sections), avec sa ligne dans la table de
recette. **Aucune case cochée, aucun test présenté comme exécuté.**

- **A — éditeur du panel** (8 étapes) : présence et aide du type « Pièces (monnaie) », aller-retour
  d'enregistrement, **préservation des récompenses existantes et de leur ordre**, refus des montants
  invalides, avertissement sans refus au-delà d'un million, retrait propre.
- **B — états source / publié / chargé** (3 étapes) : publication puis rechargement sans
  redémarrage, et vérification qu'une quête seulement en source **ne devient pas** active.
- **C — crédit réel en jeu** (6 étapes) : message de gain **après** le Title, solde cité identique
  à `/money`, **ActionBar de progression non écrasée**, bourse visible dans le journal, récompense
  prévue dans l'infobulle.
- **D — crédit unique** (4 étapes) : recomplétion, complétion forcée depuis le panel, **double clic
  rapide**, et **une seule** ligne `QUEST_REWARD` au journal.
- **E — quête répétable** (3 étapes) : deux complétions, deux crédits, deux lignes.
- **F — persistance** (2 étapes) : redémarrage et reconnexion sans duplication.
- **G — erreur honnête** (1 étape, optionnelle) : un crédit impossible doit produire un message
  d'échec explicite, **jamais** un message de gain.

Deux quêtes prêtes à copier sont fournies dans `docs/manual-tests/rewards/`
(`test_money_reward.yml`, non répétable, 100 pièces ; `test_money_repeatable.yml`, répétable,
25 pièces) — **hors du JAR**, donc elles n'atterrissent jamais toutes seules dans le contenu du
serveur. Le nettoyage est décrit étape par étape.

### État final

- **Minecraft DEV** : **ONLINE**, 4 plugins verts, JAR `6fe99525…` chargé (schéma V25 constaté).
- **Panel AWS** : `active`, `/health` → 200, distribution `20261006-090923`.
- **Agent** : inchangé, aucune action en attente laissée par ce lot.
- **Redémarrages Minecraft** : **un seul**.
- **Dépôt** : arbre de travail propre, branche poussée, tests et build verts.
