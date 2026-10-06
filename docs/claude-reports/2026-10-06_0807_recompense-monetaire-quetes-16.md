# #16 — Récompense monétaire de quête, gestion panel et solde dans l'interface joueur

## Informations

* Date : 2026-10-06
* Issue : [#16](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/16)
* Branche : `feature/169-special-mobs-boss` (aucun merge)
* Commit(s) : voir §7
* Début de la tâche : 2026-10-06 08:07:52 (heure locale réelle, première action)
* Fin de la tâche : _à compléter_
* Durée totale : _à compléter_

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

### Suite complète

_à compléter_

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

_à compléter_

---

## 7. Commits, fichiers et validation restante

_à compléter_
