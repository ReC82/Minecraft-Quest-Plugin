# #16 (second lot) — Récupération des récompenses monétaires après échec ou crash

## Informations

* Date : 2026-10-06
* Issue : [#16](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/16) (suite du premier lot)
* Branche : `feature/169-special-mobs-boss` (aucun merge)
* Commits : `9f3b28d` (le lot) + le commit de clôture documentaire
* Début de la tâche : 2026-10-06 09:51:31 (heure locale réelle, première action)
* Fin de la tâche : 2026-10-06 10:45:00 (heure locale réelle)
* Durée totale : 00:53:29

**Aucun ticket GitHub n'a été modifié ni fermé. Aucun test manuel n'a été coché ni présenté comme
exécuté. Aucun mail, aucune annonce serveur.**

---

## 1. Diagnostic : audit du parcours réel

J'ai parcouru les six points demandés. Trois défauts réels, dont un **mesuré** avant d'être affirmé.

| Point audité | État réel constaté |
|---|---|
| **Persistance de `rewardGrantId`** | **Aucune.** Champ en mémoire sur `ActiveQuestProgress`, généré paresseusement, jamais écrit en base. Un arrêt du serveur le perdait **définitivement** — donc aucune reprise ne pouvait réutiliser l'identité de paiement initiale. |
| **Ordre entre sauvegarde de COMPLETED et crédit SQL** | `upsertState(COMPLETED)` partait **avant** le crédit, en *fire-and-forget*, dans une transaction **séparée**. Fenêtre réelle : quête terminée, crédit jamais fait, **aucune trace** de la dette. |
| **Crash avant paiement** | Récompense **perdue en silence** : rien en base, identité perdue, et pour une quête non répétable la complétion n'était plus rejouable. |
| **Crash pendant la transaction** | **Déjà correct** : rollback complet. Mais l'identité étant perdue au redémarrage, la dette n'était en pratique pas reprenable. |
| **Crash après crédit, avant notification** | **Argent bien crédité** (le portefeuille est persistant) ; seul le message était perdu. Acceptable, et confirmé par un test. |
| **Reconnexion / redémarrage** | `loadForPlayer` ne recharge que `ACTIVE`/`READY_TO_TURN_IN` : une quête `COMPLETED` non payée n'était **jamais** reprise. |
| **Quêtes répétables** | Correct : une nouvelle acceptation crée une nouvelle progression, donc une nouvelle identité. |
| **Plusieurs récompenses `MONEY` sur une même complétion** | **DÉFAUT MESURÉ.** Toutes partageaient la **même** identité de paiement, donc la clé primaire rejetait les suivantes comme « déjà payées ». Un test écrit avant correction a donné : une quête `100 + 30` créditait **100**, sans aucune erreur visible. |

Le point à retenir du diagnostic : `grant_id` protégeait bien contre les **doublons**, mais rien ne
conservait **ce qui restait à payer**. Les deux moitiés sont nécessaires.

---

## 2. Garanties réellement obtenues

### Une dette durable, née avec la complétion

`quest_reward_grants` (schéma **V26**) devient « ce qui est **dû** et ce qui est **payé** » :
`status` ∈ {`PENDING`, `PAID`, `SETTLED_MANUALLY`}, plus `reward_index`, `attempts`, `last_error`,
`settled_reason`.

`QuestProgressRepository#completeQuestWithMoneyDebts` écrit l'état `COMPLETED` **et** une ligne
`PENDING` par récompense monétaire **dans une seule transaction**. Cette atomicité ferme la fenêtre
dans les **deux** sens, et c'est pour ça qu'elle est nécessaire :

- dettes d'abord → un arrêt entre les deux laisse des dettes pour une quête **pas terminée** : le
  joueur la refait, une seconde dette naît, et il est payé **deux fois au total** ;
- état d'abord → c'est exactement le défaut d'origine : terminée, jamais payée, **aucune trace**.

### Un paiement atomique et idempotent

`WalletRepository#payQuestRewardDebt` fait, dans une seule transaction : `PENDING → PAID`, crédit du
portefeuille, ligne `transactions`. Le passage est **conditionnel** (`WHERE status = 'PENDING'`) :
deux reprises concurrentes ne peuvent pas l'emporter toutes les deux. Une erreur SQL annule tout —
la dette reste `PENDING`, donc **encore payable**.

### L'identité initiale, jamais une nouvelle

L'identité de paiement est `<jeton de complétion>#<index de récompense>`, **écrite en base** dès la
complétion. Une reprise relit cette ligne et réutilise la **même** identité : il n'existe aucun
chemin qui en fabrique une nouvelle pour une dette existante.

### Le montant vient de la ligne, pas de l'appelant

`payQuestRewardDebt` ne prend **aucun montant** en paramètre, et le `context` du journal est composé
**depuis la ligne**. C'est ce qui rend vraie l'exigence « les montants dus sont conservés même si la
définition de quête change ensuite » : par construction, pas par vigilance.

### Des reprises bornées

- relecture **une fois par chargement de joueur** (`recoverPendingMoneyRewards`) — jamais une tâche
  par tick, jamais sur le thread principal ;
- au plus `RECOVERY_BATCH = 20` dettes par passage ;
- au-delà de `RECOVERY_MAX_ATTEMPTS = 5` échecs, la dette est **laissée au panel** : s'acharner ne
  corrigerait pas la cause et noierait les logs ;
- **une reprise échouée ne parle pas au joueur.** Sans cette règle, une base en panne lui
  enverrait le même message d'échec à **chaque** reconnexion. Seule la remise initiale annonce un
  échec.

### Une reprise ne rejoue que l'argent

Elle ne touche qu'aux lignes de dette : ni XP, ni objets, ni variables, ni commandes, ni la
complétion de quête. Un test le vérifie en comparant l'XP du joueur avant et après.

### Aucun succès annoncé avant confirmation

Inchangé et renforcé : le message de gain part **après** la réponse de la base. Un reçu
`FAILED` produit un message d'échec explicite ; une dette inconnue est traitée comme un **échec**,
jamais comme un gain.

---

## 3. Control Panel

Fiche joueur → **Récompenses en attente** :

- **`economy.debts`** (lecture, `ECONOMY_READ`) : montant, quête, occurrence, et l'**état réel** —
  « En attente », ou « **En échec (N tentative(s))** » avec son **motif**. Un simple « en attente »
  cacherait trois échecs.
- **`economy.debt.retry`** (sensible, `ECONOMY_WRITE`, confirmation + audit) : paie le montant
  enregistré. **Aucun montant n'est saisissable** — un test vérifie qu'un `amount` glissé dans le
  formulaire n'est même pas transmis. En accepter un permettrait d'en inventer un.
- **`economy.debt.settle`** (sensible, `ECONOMY_WRITE`, **raison obligatoire**) : marque la dette
  réglée à la main. Ne touche **aucun** solde.

**La différence entre les deux gestes est écrite à l'écran**, dans les deux sens :

- le bloc « Récompenses en attente » explique que *reprendre* paie l'enregistré avec l'identité
  initiale, tandis que *créditer manuellement* est une compensation libre qui **ne règle aucune
  dette** ;
- le bloc « Monnaie » (crédit manuel) renvoie explicitement vers « marquez-la ensuite réglée à la
  main, sinon elle resterait payable une seconde fois ».

C'est exactement l'exigence « une compensation ne doit pas laisser cette même récompense payable une
seconde fois » : le statut `SETTLED_MANUALLY` la rend non payable, et le test
`aManuallySettledDebtIsNeverPayableAgain` l'ancre.

Une reprise qui ne paie rien est rapportée comme **échec** et non comme succès : sinon l'audit
laisserait croire que le joueur a été crédité.

---

## 4. Limites historiques

**C'est la limite la plus importante de ce lot, et elle est dite à l'écran.**

Les complétions antérieures à V26 n'ont laissé **aucune trace** d'une récompense non payée. Rien ne
permet de prouver une dette passée : la seule donnée disponible serait la définition *actuelle* de
la quête, et s'en servir **inventerait** un montant.

Conséquences assumées :

- la migration marque les lignes existantes `PAID` — ce n'est pas une supposition : par construction
  de V25, une ligne n'était écrite que *dans* la transaction qui créditait ;
- **aucune dette rétroactive n'est créée**, et **aucun paiement rétroactif** n'a lieu pour les
  quêtes déjà `COMPLETED` ;
- le panel affiche un encadré disant que seules les complétions postérieures peuvent apparaître, et
  que la seule voie pour un gain ancien signalé est un crédit manuel avec sa raison.

Autres limites :

- **un échec persistant n'est pas résolu tout seul.** Au-delà de 5 tentatives, il faut une action
  d'administrateur. C'est voulu : une reprise automatique infinie masquerait la cause.
- **un statut inconnu en base est lu comme `PAID`**, donc non payable. En cas de donnée inattendue,
  ne rien payer est le choix sûr ; l'inverse créditerait à tort.
- **aucune décision de gameplay prise** : monnaie physique et conversion solde ↔ objet (#138), perte
  à la mort, prix, règles d'échange restent ouvertes. Aucun montant posé sur une quête réelle.

---

## 5. Tests

### Suite complète — verte

| Module | Tests | Ignorés | Échecs | Erreurs |
|---|---|---|---|---|
| plugin | **1660** | 34 | 0 | 0 |
| control-panel | **595** | 1 | 0 | 0 |
| web-api | **30** | 0 | 0 | 0 |
| **Total** | **2285** | 35 | **0** | **0** |

`./gradlew test` (25 min 56 s) puis `./gradlew build` : tous deux `BUILD SUCCESSFUL`. Progression :
1638 → **1660** plugin (**+22**) et 586 → **595** panel (**+9**), soit **31 cas nouveaux** —
plusieurs anciens ayant été **réécrits** sur la nouvelle API plutôt qu'ajoutés. Totaux relevés dans
les XML JUnit réels.

### Ce qui est couvert, et où

| Suite | Cas | Ce qu'elle verrouille |
|---|---|---|
| `WalletRepositoryTest` | **19** | la garantie elle-même, sur une **vraie base SQLite rouverte** : dette créée avant tout paiement, **complétion et dette écrites ensemble**, paiement unique avec sa ligne de journal, rejeu sans second crédit, **plusieurs récompenses = identités distinctes (100+30+5 = 135)**, **survie au redémarrage puis paiement unique**, dette payée qui reste payée après redémarrage, **montant pris sur la dette et non sur la définition**, répétable qui crée une occurrence neuve, ré-enregistrement du même jeton sans doublon, **20 reprises concurrentes qui créditent exactement une fois**, dette inconnue jamais payée, échec enregistré avec son motif qui **borne** les reprises, échec tardif sans effet sur une dette payée, **règlement manuel non payable sans toucher au solde**, raison obligatoire et règlement impossible sur une dette payée, bornes de lecture par joueur et globales, complétion sans argent qui ne crée aucune dette, jeton manquant refusé |
| `QuestProgressEngineTest` | **12** | le comportement du moteur : une demande par complétion avec l'identité écrite en base, **deux identités pour deux récompenses**, succès annoncé seulement après confirmation, échec dit honnêtement, **échec enregistré** pour borner, occasion déjà payée silencieuse, aucune ligne monétaire dans le résumé synchrone, occasions distinctes pour une répétable, économie jamais sollicitée sans récompense monétaire, **reprise au chargement avec l'identité initiale**, **dette trop souvent en échec laissée au panel**, **reprise qui ne rejoue aucune récompense non monétaire** |
| `QuestMoneyRewardIntegrationTest` | 6 | bout en bout avec vrai moteur + vrai `EconomyService` + vraie base, dont **100 + 30 = 130** (le test qui a mesuré le défaut) |
| `AgentActionExecutorTest` | **6** | relevé complet, bornes, reprise qui **ignore tout montant soumis**, reprise sans paiement rapportée comme **échec**, identité obligatoire, raison obligatoire |
| `EconomyAdminTest` | **9** | permissions séparées lecture/écriture, TESTER qui voit sans régler, sensibilité et confirmation, **aucun montant transmis par une reprise**, identité et raison obligatoires, bornes |
| `SchemaMigratorTest` | 2 (hérités) | table et contrainte d'unicité toujours vérifiées |

### Une régression que j'ai introduite, attrapée par un test existant

En déplaçant le paiement vers la dette, j'ai fait composer le `context` du journal à partir du seul
identifiant de paiement — **l'identifiant de quête disparaissait**, c'est-à-dire exactement la
traçabilité que j'avais documentée la veille. Le test
`finishingAQuestReallyCreditsTheWalletOnceAndLeavesOneLedgerLine` a échoué dessus. Corrigé en
composant le contexte **depuis la ligne de dette** (la source autoritative), ce qui est de toute
façon la bonne conception, et le test asserte désormais la présence de la quête **et** de l'identité.

---

## 6. Déploiement

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| **JAR VeryGames DEV** | SHA-256 `5b2d7b85857711e7cd4bf2cd75d535e103a986697fa33d5e5153c9dd45c61f81` (1 825 931 octets, commit `9f3b28d`) | `DEPLOY_EXIT=0`, `JAR en ligne : 1825931 octets (== local)` |
| **Control Panel AWS** | distribution `20261006-104128` | `PANEL_EXIT=0`, `/health` → `{"panel":"ONLINE"}`, service `active` |

**Backup préalable** : `rpgquest-20261006T084211Z-predeploy.jar` (1 807 514 octets, SHA-256
`6fe99525…`). **Le backup précédent n'a pas été écrasé.**

**Un seul redémarrage Minecraft** (`RESTART_EXIT=0`) : arrêt **constaté OFFLINE**, retour **constaté
ONLINE**. Par RCON : `plugins` → **4 plugins verts** (Citizens, Multiverse-Core, RPGQuest,
WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`. Panel `/health` → **200**.

Procédure suivie telle que documentée : `./gradlew --stop`, `export RPGQUEST_TEST_MAX_HEAP=768m`,
`scripts/deploy-verygames.sh -y`, puis `scripts/verygames-restart.sh --timeout 300`. **Aucun échec
de déploiement cette fois** — les deux pièges de la session précédente étaient bien ceux-là.

### Chargement réellement vérifié

Le numéro de version ne change pas d'un lot à l'autre. La migration, elle, se constate : copie de
`RPGQuest/data.db` récupérée **en lecture seule**, lue localement, puis **supprimée**.

| Contrôle | Résultat observé |
|---|---|
| `PRAGMA user_version` | **26** (était 25 avant ce lot) |
| Colonnes de `quest_reward_grants` | les **six** nouvelles présentes : `status`, `reward_index`, `attempts`, `last_error`, `updated_at`, `settled_reason` |
| Lignes par statut | **aucune ligne** — donc **aucune dette rétroactive inventée**, et aucun paiement rétroactif |

Aucune écriture sur le serveur, aucun solde touché, aucune donnée joueur modifiée. **Aucune panne
provoquée sur la base DEV.**

---

## 7. Validation manuelle restante

**TC-251** ajouté à `docs/MANUAL_TEST_PLAN.md` (24 étapes, 7 sections) avec sa ligne dans la table de
recette. **Aucune case cochée.**

- **A** — non-régression du cas normal (3 étapes) ;
- **B** — **deux récompenses sur une complétion** : le solde doit augmenter de 130, pas de 100
  (5 étapes) ;
- **C** — reprise automatique à la connexion, avec le point clé : **une seule** ligne de journal
  pour une complétion, quel que soit le message vu (3 étapes) ;
- **D** — reprise et règlement manuel depuis le panel, double clic, **403 depuis un compte TESTER**,
  et vérification que la page distingue bien les deux gestes (6 étapes) ;
- **E** — échec réel de paiement : **optionnelle, et explicitement à ne pas faire sur la base DEV
  réelle**. Elle est entièrement couverte par les tests automatisés, donc la sauter ne laisse aucun
  trou (3 étapes) ;
- **F** — répétable et modification de définition : les complétions déjà faites ne changent pas de
  montant (2 étapes) ;
- **G** — redémarrage, et absence de paiement rétroactif (2 étapes).

Contenus et comptes de test indiqués, nettoyage décrit étape par étape.

Restent également en attente : TC-243 à TC-250.

### État final

- **Minecraft DEV** : **ONLINE**, 4 plugins verts, JAR `5b2d7b85…` chargé (schéma V26 constaté).
- **Panel AWS** : `active`, `/health` → 200, distribution `20261006-104128`.
- **Redémarrages Minecraft** : **un seul**.
- **Dépôt** : arbre propre, branche poussée, tests et build verts.
