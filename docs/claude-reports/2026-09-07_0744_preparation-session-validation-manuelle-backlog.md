# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 07:44 (heure locale réelle de la machine)
* Sujet : Préparation d'une session de validation manuelle DEV optimisée — audit de tout le
  backlog `needs-manual-test`, checklist séquentielle unique, prérequis serveur, + audit du
  socle `web-api` pour l'issue #37 (control-panel).
* Statut : DONE (aucune validation Minecraft effectuée — l'utilisateur est au travail ; c'est un
  livrable de préparation). Aucune issue fermée. Aucune branche fusionnée.
* Branche Git : `feat/36-admin-test-shortcuts` (= état déployé sur DEV : JAR `bcc3a6ec…`).
* Commit(s) : voir « Commit(s) » — rapport + doc `docs/control-panel/`.
* Début de la tâche : 2026-09-07 07:44:55
* Fin de la tâche : 2026-09-07 07:59:00
* Durée totale : 00:14:05

## Demande

Utiliser les commandes admin de l'issue #36 (déjà implémentées et déployées) pour préparer une
session de validation manuelle très efficace : auditer tous les tickets `needs-manual-test`,
regrouper les tests par scénario, préparer les états via les commandes #36, vérifier les prérequis
serveur DEV, produire une **checklist unique séquentielle** (PHASE 0→7), classer les tickets par
rapidité de fermeture, pré-rédiger les commentaires de validation. Si du temps reste, avancer sur
l'issue #37 (socle du Control Panel — audit + architecture, pas de grosse UI). Rien fermer, rien
fusionner, DEV uniquement avec backup.

------------------------------------------------------------------------

## 1. AUDIT DU BACKLOG `needs-manual-test` — état RÉEL d'implémentation

> **Constat central :** 15 issues portent `needs-manual-test`. Seules **#21, #22, #23 et #36**
> ont du code réellement implémenté **et déployé** sur le JAR DEV actuel (`bcc3a6ec…`). **#27**
> existe sur une branche très divergente non déployée. **Les 10 autres n'ont aucune
> implémentation** — les tester ce soir est impossible.

| # | Type | Titre (court) | Implémenté ? | Où / preuve | Testable ce soir ? |
|---|---|---|---|---|---|
| **21** | bug | Accès monde Claims verrouillé sans unlock | ✅ **oui, déployé** | `claim.ClaimWorldAccessGuard` (commits `c8cd5bb`, `692ac36`) ; rapports `2026-09-06_1116`, `2026-09-06_1219` | **OUI** (après création du Garde + redémarrage) |
| **22** | bug | Retour Hub garanti depuis monde Claims | ✅ **oui, déployé** | `claim.ClaimWorldSafetyListener` (mêmes commits) | **OUI** (idem) |
| **23** | bug | Jo explique le déblocage claim (dialogue 3 états) | ✅ **oui, déployé** | modificateur `negate` (`15a76fb`) + `dialogues/jo.yml` (`0a484cc`) | **OUI** (idem) |
| **36** | feat | Commandes admin de test quêtes/stories | ✅ **oui, déployé** | `admin.RpgAdminCommand` + `StoryService.adminAdvance/Complete` (`a3c3049`) ; rapport `2026-09-06_2028` | **OUI** (compte OP ou console) |
| **27** | feat | Permissions granulaires par rôle/monde/action | ⚠️ **code sur `feature/27-granular-permissions` (`c915684`)** — branche divergente de `main` (**+36 684 lignes / 673 fichiers**, inclut une refonte `webapi`), **jamais fusionnée ni déployée** | non mergeable proprement sur la ligne déployée | **NON** (pas déployé ; déploiement = tout un autre codebase) |
| **24** | feat | Aperçu état du Wild (jour/nuit/météo) avant entrée | ❌ **non** | `travel.WildEntryWarningService` n'avertit que sur l'absence de Rune ; aucune info jour/nuit/météo | **NON** |
| **25** | feat | Rune de rappel consommable / rare | ❌ **non** | `items/rune_rappel.yml` = « permanent, jamais consommé » ; aucune décrémentation dans `travel.ItemTravelService` | **NON** |
| **26** | feat | PNJ kit de première expédition + avertissement pré-Wild | ❌ **non** (partiel non pertinent : `StarterKitListener` donne la Rune au 1er login, mais pas de PNJ « préparateur », pas de kit configurable, pas d'avertissement nourriture/arme) | — | **NON** |
| **28** | feat | Message de première connexion + avertissement bêta | ❌ **non** | aucun listener de 1re connexion affichant un accueil ; les occurrences « welcome » sont le champ du HubGuide (`/rpgadmin guide info`), pas un message joueur | **NON** |
| **30** | bug | Empêcher de tuer les entités protégées dans le Hub | ❌ **non** | `hub.HubWorldProtectionListener.onEntityDamage` n'annule le dégât **que si la victime est un `Player`** — un joueur peut donc frapper/tuer une vache | **NON** |
| **31** | bug | Empêcher de tuer les animaux dans le Hub (**doublon de #30**) | ❌ **non** | même code, même cause que #30 | **NON** |
| **32** | feat | Objet permanent de retour au spawn du Hub | ❌ **non** | aucun item de ce type (`items/` : acte, pierre_retour, rune_rappel, journal, forest_blade, miner_pickaxe, refined_crystal, spider_fang) | **NON** |
| **33** | bug | Restaurer faim/vie dans le Hub (ou point de soin) | ❌ **non** | aucun `setFoodLevel`/`setHealth`/`FoodLevelChangeEvent` dans `hub.*` ni `world.*` | **NON** |
| **34** | bug | Compter les crafts multiples dans les objectifs de quête | ❌ **non** | `quest.progress.QuestCraftItemListener.onCraft` appelle `handleCraftItem` **une fois par événement** → progression +1, jamais la quantité réelle (recette ×4, shift-click…) | **NON** |
| **35** | feat | Remplacer le bypass OP silencieux par un mode admin explicite | ❌ **non** | aucun `/rpgadmin bypass`, aucun `AdminBypassService`. Les gardes testent directement `player.hasPermission("rpgquest.admin.world")` (issu de l'audit #21/#22) | **NON** |

### Détail par ticket testable (les 4 seuls)

| # | Tests auto déjà passés | Validation Minecraft encore nécessaire | Dépendances | PNJ requis | Monde | OP requis | État joueur requis | Cmd #36 utile | Durée manuelle |
|---|---|---|---|---|---|---|---|---|---|
| **21** | `ClaimWorldAccessGuardTest`, `CompositeWorldPortalEntryGuardTest` (verts) | portail réel + compte **non-OP** ; refus sans `CLAIM_TIER_1`, log `[claims-access]` ; autorisé après unlock | Garde créé (voir §4) ; #23 (Jo) ; #36 (préparer l'état) | **Garde** (`guard`), Jo, Guide, Libraire | `world_hub` → `claims` | **NON-OP** (un test OP est faussé — cf. #35) | `resetnew` puis `quest complete first_steps`/`crystal_hunt` | ~6 min |
| **22** | `ClaimWorldSafetyListenerTest` (vert) | dans `claims`, Pierre de retour reçue à l'arrivée ; clic droit → Hub ; `/tp` forcé non-éligible → renvoi Hub ; log `[claims-safety]` | idem #21 | idem | `claims` | **NON-OP** | idem | ~4 min |
| **23** | `DialogueSessionEngineTest` (Jo 3 états), `DialogueDefinitionParserTest` (`negate`) | Jo : état non débloqué / débloqué sans claim / claim existant ; cohérence avec le Guide | #21/#22 (même parcours) | Jo, Guide | `world_hub` | NON-OP | `resetnew` ; `quest complete crystal_hunt` ; `variable set CLAIM_TIER_1 false` | ~3 min |
| **36** | `RpgAdminTestShortcutsCommandTest` (11), `StoryServiceTest` (+8), `QuestProgressEngineTest` (+2) | commandes réelles en jeu : `quest start/complete/reset`, `story advance/complete`, `variable get/set` ; non-duplication récompense ; message « étape à tester » | — (mais permet de tester #21/#22/#23) | Garde (pour vérifier que `crystal_hunt` apparaît après `quest complete first_steps`) | console + `world_hub` | OP **ou console** | n/a | ~5 min |

------------------------------------------------------------------------

## 2. REGROUPEMENT PAR SCÉNARIO

Une **seule séquence Claims** couvre **#21 + #22 + #23** (+ démontre #36 en la préparant). Les
autres phases n'ont, ce soir, quasiment rien à valider — elles sont listées pour mémoire.

| Scénario | Tickets couverts | Réalité ce soir |
|---|---|---|
| **S1 — Validation #36** (console/OP) | #36 | ✅ 5 min, fiche §5 |
| **S2 — Parcours Claims non-OP** (Rondoudou9000) | **#21, #22, #23** | ✅ ~12 min, §6 — **le cœur de la soirée** |
| **S3 — Nouveau joueur / onboarding** | #28 | ❌ non implémenté |
| **S4 — Hub / protections** | #30, #31, #33 | ❌ non implémentés (bugs confirmés, correctifs courts — voir §7) |
| **S5 — Quêtes / craft / journal** | #34 | ❌ non implémenté |
| **S6 — Wild / objets de retour** | #24, #25, #26, #32 | ❌ non implémentés |
| **S7 — Permissions / bypass** | #27, #35 | ❌ #27 non déployé, #35 non implémenté |

------------------------------------------------------------------------

## 3. UTILISATION DES COMMANDES #36 POUR ÉVITER LE GRIND

Toutes utilisables **depuis la console** VeryGames ou en jeu (compte OP). `<J>` = joueur cible **en
ligne** (pour `quest complete` / `story advance|complete`).

```
# Repartir d'un état neuf sans reconstruire un personnage
/rpgadmin player resetnew <J> confirm

# Sauter first_steps sans tuer 10 araignées
/rpgadmin quest complete <J> rpgquest:first_steps

# Sauter crystal_hunt (5 araignées + 2 cristaux + 1 épée diamant + parler au Garde)
/rpgadmin quest complete <J> rpgquest:crystal_hunt

# Vérifier l'unlock claim sans lire data.db
/rpgadmin player variable get <J> CLAIM_TIER_1        # attendu : true

# Re-verrouiller pour re-tester le refus d'accès
/rpgadmin player variable set <J> CLAIM_TIER_1 false  # (permission rpgquest.admin.debug)

# Variante « piloter la story » (après resetnew)
/rpgadmin story advance <J> main_story    # complète l'étape courante, indique la suivante
/rpgadmin story complete <J> main_story   # complète toute la chaîne, dans l'ordre
```

**Grind évité :** 10 araignées (first_steps) + 5 araignées + 2 `AMETHYST_SHARD` + 1 craft épée
diamant + aller-retour Garde (crystal_hunt). Gain estimé : **10–15 min de gameplay par itération**.

**Validation end-to-end conservée :** garder **une** exécution réellement jouée du parcours
principal complet (Guide → Libraire → Garde → 10 araignées → Garde → crystal_hunt joué → Jo →
portail → claim → Pierre de retour) une fois que la version est stabilisée — pas ce soir si le
temps manque, mais avant de fermer #21/#22/#23 définitivement, ou noter la limite dans le
commentaire de validation.

------------------------------------------------------------------------

## 4. PRÉREQUIS SERVEUR — AUDIT DEV (état au 2026-09-07 07:48Z, lecture FTP seule)

| Élément | État | Action |
|---|---|---|
| **JAR déployé** | `rpgquest-0.1.0-SNAPSHOT.jar` SHA-256 `bcc3a6ec765c7fb7adeff2a15f2b49637a535fcc89c156184ab16bbd700bccdd` = build local du commit `a3c3049` (#36). ✅ | — |
| **Redémarrage serveur** | **INCONNU / probablement pas effectué** — `data.db` inchangé depuis la dernière session (aucune activité de test), le processus tourne peut-être encore l'ancien JAR. | **Redémarrer le serveur** depuis le panel VeryGames avant toute validation. Vérifier `/rpgquest version` + absence d'ERROR + `/plugins` vert. |
| **PNJ `guide`** | ✅ Citizens #0, lié `guide` (`npc_citizens_bindings`) | — |
| **PNJ `libraire`** | ✅ Citizens #1, lié `libraire` | — |
| **PNJ `jo`** | ✅ Citizens #5, lié `jo` | — |
| **PNJ `guard` (Garde)** | ❌ **ABSENT** — Citizens sur DEV : `Guide, Libraire, help, jeff, junior, Jo`. Aucun `guard` dans `npc_citizens_bindings`. **C'est le blocant n°1** : sans lui, `first_steps`/`crystal_hunt` restent impossibles à démarrer **en jeu**, et on ne peut pas vérifier « le Garde propose crystal_hunt » (test explicite de #36 et du parcours #21). | **À CRÉER en jeu** (voir procédure ci-dessous). Non automatisable depuis le dépôt. |
| PNJ `help`, `jeff`, `junior` | ✅ présents — contenu de test hors parcours principal, sans effet sur les validations de ce soir | — |
| `dialogues/guard.yml` | ✅ **identique au dépôt** (contient la branche `crystal_hunt` + nœud `crystal_hunt_accepted`) — déployé la session précédente | — |
| `dialogues/jo.yml`, `dialogues/guide.yml` | ✅ identiques au dépôt (3 états / `help_claims`) | — |
| `quests/crystal_hunt.yml`, `stories/main_story.yml` | ✅ identiques au dépôt | — |
| `quests/first_steps.yml` | ⚠️ version éditée à la main sur le serveur (`description` « Apprends les bases modifiées ! », `icon` retiré). **Non bloquant** : la quête fonctionne (KILL SPIDER ×10, récompense 50 XP + IRON_SWORD). Ne pas la re-déployer sans l'accord de l'utilisateur (écraserait l'édition serveur). | optionnel |
| `world-portals/hub_to_claims.yml` | ✅ présent : `world_hub` [730–732 / 66–69 / −676] → `claims`, `enabled: true`, `WORLD_SPAWN` | — |
| `world-portals/hub_to_wild.yml` | ✅ présent | — |
| `config.yml` | ✅ `claims.world: claims`, `hub.world: world_hub`, `travel.wild-world: wild` — cohérent | — |
| Custom items | ✅ les 8 fichiers présents (`acte_propriete`, `pierre_retour`, `rune_rappel`, `journal_quetes`, `forest_blade`, `miner_pickaxe`, `refined_crystal`, `spider_fang`) | — |
| Mondes (Multiverse) | ✅ `claims`, `world_hub`, `overworld`, `the_nether`, `the_end` (`Multiverse-Core/worlds.yml`) | — |
| Comptes joueurs | `LoDyMcFly` = **OP** (propriétaire de tous les PNJ Citizens). `Rondoudou9000` = **aucune progression, aucune variable** → parfait pour le test Claims non-OP. `Madix666` = quelques quêtes de test. **À confirmer par l'utilisateur : `Rondoudou9000` n'est pas OP.** | vérifier `ops.json` / `/deop Rondoudou9000` par sécurité |

### Procédure exacte — créer le PNJ Garde (en jeu, compte OP, APRÈS redémarrage)

```
/npc create Garde --type player          # crée le PNJ à ta position, le sélectionne
/npc skin <un_pseudo>                     # optionnel, cosmétique
# se placer à ≤ 6 blocs et REGARDER DROIT le PNJ, puis :
/rpgadmin npc info                        # -> "Cette entité n'est pas identifiée."
/rpgadmin npc tag guard                   # -> "Entité identifiée : guard (Citizens NPC #6)"
/rpgadmin npc info                        # -> "Identifiant : guard (Citizens NPC #6)"  (persiste au redémarrage)
# placer le Garde près des autres PNJ du Hub (/npc move, ou recréer à l'endroit voulu)
```

Cela ajoute **une** ligne à `npc_citizens_bindings` (`<uuid>|6|guard|<horodatage>`) — via la
commande du plugin, jamais d'édition directe de `data.db`. Voir
`docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1b.

### Rien à déployer ce soir

Tous les fichiers de contenu nécessaires aux 4 tickets testables sont **déjà en place et
corrects**. Aucun déploiement DEV n'a été fait par cette session (préparation uniquement).

------------------------------------------------------------------------

## 5. FICHE DE VALIDATION #36 (5 minutes) — console ou compte OP

> Faisable avant même de créer le Garde, sauf l'étape D qui vérifie l'affichage du Garde.

| # | Commande | Résultat attendu | Ticket |
|---|---|---|---|
| A | `/rpgadmin player resetnew Rondoudou9000 confirm` | « état RPGQuest de Rondoudou9000 réinitialisé » | #36 |
| B | `/rpgadmin quest complete Rondoudou9000 rpgquest:first_steps` | « Quête complétée (récompenses appliquées) … first_steps » | #36 |
| C | `/rpgadmin quest complete Rondoudou9000 rpgquest:first_steps` (répété) | « Déjà terminée … — aucune récompense re-créditée » (pas de double XP/épée) | #36 |
| D | *(en jeu, si le Garde existe)* clic droit sur le Garde | il propose « J'ai entendu dire que tu avais besoin d'aide pour forger un équipement » (= `crystal_hunt` démarrable) | #36 + parcours #21 |
| E | `/rpgadmin quest complete Rondoudou9000 rpgquest:crystal_hunt` | « Quête complétée (récompenses appliquées) … crystal_hunt » | #36 |
| F | `/rpgadmin player variable get Rondoudou9000 CLAIM_TIER_1` | `Rondoudou9000 : variable CLAIM_TIER_1 = true` | #36 |
| G | `/rpgadmin quest reset Rondoudou9000 rpgquest:crystal_hunt` | « Quête réinitialisée (progression + compteurs) » **+ rappel** « n'annule PAS les récompenses déjà accordées … » | #36 |
| H | `/rpgadmin player variable get Rondoudou9000 CLAIM_TIER_1` | **toujours `true`** (démontre la limite documentée du reset ciblé) | #36 |
| I | `/rpgadmin player resetnew Rondoudou9000 confirm` puis `/rpgadmin story advance Rondoudou9000 main_story` | « premiers_pas complétée … ➜ étape 2/3 : rpgquest:first_steps (ACTIVE) — à tester manuellement » | #36 |
| J | `/rpgadmin story complete Rondoudou9000 main_story` | « premiers_pas, first_steps, crystal_hunt complétées dans l'ordre » ; `variable get CLAIM_TIER_1` → `true` | #36 |
| K | `/rpgadmin quest complete Rondoudou9000 rpgquest:pas_une_quete` | « Quête inconnue : rpgquest:pas_une_quete » (cas invalide propre, pas d'exception) | #36 |

Console à surveiller : lignes `[admin] <exécutant> : /rpgadmin quest complete …` / `[admin] story advance « main_story » : … → étape n/3 …`.

------------------------------------------------------------------------

## 6. TESTS CLAIMS — compte **NON-OP** `Rondoudou9000` (cœur de la soirée : #21 + #22 + #23)

> Prérequis : serveur redémarré sur `bcc3a6ec…`, **Garde créé**, `Rondoudou9000` **non-OP** et
> **connecté**. Garder la console ouverte pour les logs `[claims-access]` / `[claims-safety]`.

| # | Compte | OP | Commande admin (console) | Action Minecraft (Rondoudou9000) | Attendu | Ticket(s) | Log |
|---|---|---|---|---|---|---|---|
| 1 | admin | OP | `/rpgadmin player resetnew Rondoudou9000 confirm` | — | reset OK | #21/#22/#23 | — |
| 2 | Rondoudou9000 | non-OP | — | parler au **Guide** | menu d'aide ; « Comment fonctionnent les claims ? » énonce le prérequis (finir l'histoire au Garde puis voir Jo) | #23 | — |
| 3 | Rondoudou9000 | non-OP | — | parler à **Jo** | **seule** option « Comment obtenir mon premier terrain ? » → explique : terminer l'histoire principale, rendre *La chasse aux cristaux* au Garde, puis revenir. **Pas** d'option « réclamer l'acte » | **#23** | — |
| 4 | Rondoudou9000 | non-OP | — | marcher dans le **portail Hub → claims** | **aucune téléportation** ; 2 messages rouges (« réservé aux joueurs qui ont débloqué… », orientation vers Jo / le Guide) | **#21** | `[claims-access] Rondoudou9000 : entrée dans « claims » … REFUSÉE (CLAIM_TIER_1 non débloqué, aucun claim) — aucune téléportation.` |
| 5 | admin | OP | `/rpgadmin quest complete Rondoudou9000 rpgquest:first_steps` | — | « Quête complétée … first_steps » | (prépare #21) | `[admin] … quest complete … first_steps` |
| 6 | Rondoudou9000 | non-OP | — | reparler au **Garde** | il propose « J'ai entendu dire… » (= `crystal_hunt` démarrable en jeu) | parcours #21 / #36-D | — |
| 7 | admin | OP | `/rpgadmin quest complete Rondoudou9000 rpgquest:crystal_hunt` | — | « Quête complétée … crystal_hunt » | (prépare #21/#23) | `[admin] … quest complete … crystal_hunt` |
| 8 | admin | OP | `/rpgadmin player variable get Rondoudou9000 CLAIM_TIER_1` | — | `= true` | #36 / preuve #21 | — |
| 9 | Rondoudou9000 | non-OP | — | parler à **Jo** | option « **Je viens réclamer mon acte de propriété** » ; la choisir → reçoit `rpgquest:acte_propriete` + explication (portail + Pierre de retour) | **#23** | — |
| 10 | Rondoudou9000 | non-OP | — | reprendre le **portail Hub → claims** | **téléportation OK** vers le monde `claims` | **#21** | `[claims-access] Rondoudou9000 : … autorisée (CLAIM_TIER_1 débloqué) — téléportation relancée.` |
| 11 | Rondoudou9000 | non-OP | — | à l'arrivée dans `claims` | message « Tu reçois une Pierre de retour. » + `rpgquest:pierre_retour` en inventaire (**une seule fois**) | **#22** | `[claims-safety] Rondoudou9000 éligible dans « claims » … garantie d'une Pierre de retour.` |
| 12 | Rondoudou9000 | non-OP | — | clic droit avec l'**Acte** dans `claims` | 1er claim posé (5×5), faisceau de bordure visible | #23 (état « claim existant ») | — |
| 13 | Rondoudou9000 | non-OP | — | clic droit avec la **Pierre de retour** | retour au spawn du village (`world_hub`), **sans commande** | **#22** | — |
| 14 | Rondoudou9000 | non-OP | — | reparler à **Jo** (a maintenant un claim) | options « Me rendre sur ma propriété » / « Revoir les limites » / « Obtenir une Pierre de retour » (état 3) | **#23** | — |
| 15 | admin | OP | `/tp Rondoudou9000` dans `claims` **après** `/rpgadmin player variable set Rondoudou9000 CLAIM_TIER_1 false` et suppression du claim (`/claim admin` … ou `resetnew`) | — puis le joueur observe | joueur **renvoyé au Hub** + message | **#22** (filet de sécurité voie non-portail) | `[claims-safety] Rondoudou9000 NON éligible dans « claims » : renvoi au Hub.` |
| 16 | admin | OP | `/rpgadmin player resetnew Rondoudou9000 confirm` | — | remet à zéro | vérifie #21/#23 « retour à l'état non débloqué » | — |
| 17 | Rondoudou9000 | non-OP | — | reprendre le **portail Hub → claims** | **refusé de nouveau** (comme étape 4) | **#21** (cas 3 de l'issue) | `[claims-access] … REFUSÉE …` |

### Compte OP — uniquement pour rendre le bypass visible (#35, pas pour conclure #21/#22)

| # | Compte | Commande | Attendu | Ticket |
|---|---|---|---|---|
| O1 | LoDyMcFly (OP) | marcher dans le portail Hub → claims **sans** `CLAIM_TIER_1` | entre **quand même** (bypass) — **et la console le dit explicitement** | contexte #35 |
| — | — | log attendu | `[claims-access] LoDyMcFly : … AUTORISÉE par le bypass rpgquest.admin.world (compte OP ou permission explicite) — contrôle CLAIM_TIER_1 non appliqué.` | #35 : confirme que le bypass est **tracé** mais **silencieux côté joueur** — motive #35 |

**Ne jamais conclure #21/#22 depuis un compte OP.** Le journal `[claims-access] … bypass` est la
preuve que le test doit se faire non-OP.

------------------------------------------------------------------------

## 7. BUGS ISOLÉS `needs-manual-test` — état réel

Aucun n'est implémenté ⇒ **rien à valider ce soir** pour ces tickets. Résumé pour le backlog :

| # | Bug | Cause identifiée dans le code | Correctif estimé | Fermable ce soir ? |
|---|---|---|---|---|
| **30 / 31** | Un joueur non-OP peut tuer une vache dans le Hub (doublon) | `HubWorldProtectionListener.onEntityDamage` n'annule le dégât **que quand la victime est un `Player`**. Aucun cas « attaquant = joueur, victime = animal/entité protégée, monde = Hub → annuler » (le pattern existe déjà dans `zone.ZoneProtectionListener.handleNpcDamage`). | **~15 lignes** + 1 test. Exclure les PNJ Citizens (déjà protégés ailleurs). Bypass séparé du build. | ❌ (à implémenter d'abord — session courte possible) |
| **33** | Joueur revient au Hub blessé/affamé, pas de moyen de soin | Aucun code de restauration faim/vie dans `hub.*`. `HubWorldProtectionListener` annule déjà tous les dégâts, mais ne restaure pas l'état existant. | **~25 lignes** (restaurer faim+saturation+vie à l'entrée du monde Hub / au join dans le Hub) + 1 test + doc de la règle choisie. | ❌ (décision de gameplay à documenter) |
| **34** | Craft de 4 planches en une fois = progression +1 | `QuestCraftItemListener.onCraft` : `handleCraftItem(player, type)` **une fois par événement**, jamais la quantité produite (clic normal vs shift-click : calcul non trivial). | **~40 lignes** (quantité réelle : `getRecipe().getResult().getAmount()` × nb de crafts déduit du shift-click / place libre) + tests clic normal / shift-click / plafond objectif. | ❌ (le plus délicat des trois) |
| **24** | Pas d'aperçu jour/nuit du Wild avant entrée | `WildEntryWarningService` n'expose pas l'heure/météo du monde cible. | ~30 lignes (lire `World#getTime()`/`hasStorm()` du monde Wild dans le message de portail). | ❌ |
| **25** | Rune de rappel non consommable | `ItemTravelService` ne décrémente jamais. | ~30 lignes (consommer 1 après TP **confirmée**, jamais sur échec) + tests. | ❌ |
| **26** | Pas de PNJ kit / avertissement pré-Wild nourriture-arme | inexistant | moyen (PNJ + kit configurable + checks) | ❌ |
| **28** | Pas d'accueil 1re connexion / avertissement bêta | inexistant | moyen (config + livre/message + marqueur 1re connexion, réutiliser l'état onboarding) | ❌ |
| **32** | Pas d'objet permanent de retour Hub | inexistant | moyen (custom item PDC + listener clic droit + restauration + PNJ/soulbound) | ❌ |
| **35** | Bypass OP silencieux | les gardes testent `hasPermission` direct | moyen-gros (service `AdminBypassSession` + `/rpgadmin bypass on|off|status` + actionbar + câblage dans tous les gardes) — **dépend d'un choix d'archi** ; interagit avec #27 | ❌ |
| **27** | Permissions granulaires | **existe sur `feature/27-granular-permissions`**, non fusionné, branche à +36k lignes divergente | (déjà écrit ; à rebaser/auditer/déployer séparément) | ❌ (hors périmètre soirée) |

**Recommandation :** une session de dev courte dédiée à **#30/#31 + #33 + #34** (3 bugs Hub/craft,
tous confirmés par des tests DEV réels, correctifs courts, même zone du code) permettrait de
fermer 4 tickets en une passe. À planifier **avant** ou **à la place** d'une soirée de test si
l'objectif est le volume de fermetures.

------------------------------------------------------------------------

## 8. ORDRE OPTIMAL — CHECKLIST UNIQUE SÉQUENTIELLE

> Un seul reset par phase, un seul changement de compte majeur (OP console ↔ Rondoudou9000
> non-OP). Durée totale réaliste : **~25–35 min** pour tout ce qui est réellement testable.

### PHASE 0 — Préparation serveur (~5 min, compte OP)
| Étape | Qui | OP | Commande / action | Attendu | Ticket |
|---|---|---|---|---|---|
| 0.1 | admin | — | **Redémarrer le serveur** (panel VeryGames) | serveur up | tous |
| 0.2 | admin | OP | console : rien d'`ERROR` au démarrage ; `/rpgquest version` | nouvelle version, Java 21 | tous |
| 0.3 | admin | OP | `/plugins` | RPGQuest vert | tous |
| 0.4 | admin | OP | `/mv list` (ou `/mvl`) | `world_hub`, `claims`, `wild` chargés | #21/#22 |
| 0.5 | admin | OP | `/npc create Garde --type player` → viser → `/rpgadmin npc tag guard` → `/rpgadmin npc info` | « Identifiant : guard (Citizens NPC #6) » | **blocant #21/#23/#36** |
| 0.6 | admin | OP | placer le Garde près du Guide/Libraire/Jo | PNJ visible en jeu | — |
| 0.7 | admin | — | vérifier `Rondoudou9000` **non-OP** (`ops.json` ou `/deop Rondoudou9000`) | non-OP confirmé | #21/#22 |

### PHASE 1 — Validation #36 (~5 min, console)
→ Fiche §5, étapes A → K. **Ticket : #36.** Logs `[admin] …`.

### PHASE 2 — Nouveau joueur / Hub
→ **Rien à valider ce soir** (#28, #30, #31, #33 non implémentés). Voir §7.
Seul contrôle utile (non bloquant, 1 min, non-OP) : Rondoudou9000 frappe une vache dans le Hub →
**bug #30/#31 confirmé si elle meurt** (documenter l'observation pour le futur correctif).

### PHASE 3 — Quêtes / journal / craft
→ **Rien à valider** (#34 non implémenté).
Contrôle utile (non bloquant) : accepter une quête de craft, fabriquer 4 planches d'un coup →
**+1 au lieu de +4 = bug #34 confirmé**.
Test de non-régression rapide (2 min, non-OP) : Libraire → journal → clic droit → GUI 2 onglets
fonctionne (issue #11, déjà fermée/à re-confirmer si souhaité).

### PHASE 4 — Claims (~12 min) ⇐ **cœur de la soirée**
→ §6, étapes 1 → 17, compte **Rondoudou9000 NON-OP**. **Tickets : #21, #22, #23.**
Puis étape O1 en OP pour observer le log de bypass (contexte #35).

### PHASE 5 — Wild
→ **Rien à valider** (#24, #25, #26 non implémentés).
Contrôle non-régression (2 min) : portail Hub → wild sans Rune → l'avertissement « emporte une
Rune » s'affiche (bouton Continuer / Annuler) ; avec Rune → pas d'avertissement. *(mécanique
existante « boucle joueur », pas un ticket ouvert.)*

### PHASE 6 — Bugs isolés
→ **Aucun** implémenté. Consigner les observations des Phases 2–3 (vache tuable, craft +1) comme
confirmations pour les futurs correctifs #30/#31/#33/#34.

### PHASE 7 — Fermeture GitHub
→ §10 : coller les commentaires de validation sur **#21, #22, #23, #36** si les Phases 1 & 4 sont
vertes. **Ne rien fermer automatiquement — l'utilisateur ferme.**

------------------------------------------------------------------------

## 9. CLASSEMENT PAR RAPIDITÉ DE FERMETURE

| Classe | Critère | Tickets |
|---|---|---|
| **A — < 2 min** | vérif immédiate | **#36** (fiche §5 : commandes console, résultat immédiat) |
| **B — 2 à 5 min** | courte séquence | **#23** (Jo 3 états, 3 clics), **#22** (Pierre de retour + retour Hub) |
| **C — > 5 min** | séquence complète non-OP | **#21** (parcours refus → unlock → accès → re-refus, ~6 min + dépend du Garde) |
| **D — bloqué** | pas d'implémentation / prérequis manquant | **#24, #25, #26, #28, #30, #31, #32, #33, #34, #35** (non implémentés) ; **#27** (branche divergente non déployée) ; **#21/#22/#23/#36** aussi bloqués tant que le **Garde** n'existe pas et que le serveur n'est pas redémarré |
| **E — probablement déjà satisfait / à réévaluer** | couvert par les tests auto + cohérence code | rien de net ici : les 4 implémentés méritent la confirmation en jeu (surtout côté logs `[claims-*]` et affichage Garde/Jo, non couvrables en MockBukkit) |

**Priorité de fermeture ce soir : #36 → #23 → #22 → #21** (dans cet ordre : le plus rapide et le
moins dépendant d'abord ; #21 en dernier car c'est la séquence la plus longue et la plus
dépendante du Garde).

------------------------------------------------------------------------

## 10. COMMENTAIRES DE VALIDATION PRÉ-RÉDIGÉS (à coller si vert — **ne pas fermer soi-même**)

### #36
```
Validation manuelle DEV OK :
- /rpgadmin quest complete <J> rpgquest:first_steps → récompenses appliquées ; 2e appel = « déjà terminée », aucun double gain.
- /rpgadmin quest complete <J> rpgquest:crystal_hunt → VARIABLE appliquée ; /rpgadmin player variable get <J> CLAIM_TIER_1 = true.
- /rpgadmin quest reset <J> rpgquest:crystal_hunt → quête rejouable ; le rappel de limite s'affiche ; CLAIM_TIER_1 reste true (limite documentée).
- /rpgadmin story advance <J> main_story → complète l'étape courante et indique l'étape suivante à tester.
- /rpgadmin story complete <J> main_story → 3 quêtes complétées dans l'ordre, CLAIM_TIER_1 posée.
- Cas invalide (quest-id inconnu) → message propre, aucune exception.
- Logs [admin] présents pour chaque opération sensible.
Issue validée.
```

### #23
```
Validation manuelle DEV OK (compte NON-OP, après /rpgadmin player resetnew) :
- État non débloqué : Jo ne propose QUE « Comment obtenir mon premier terrain ? » et explique le prérequis (histoire principale → Garde) ; le Guide dit la même chose.
- Après /rpgadmin quest complete crystal_hunt (CLAIM_TIER_1 = true), sans claim : Jo propose « Je viens réclamer mon acte de propriété » et remet l'Acte.
- Avec un claim posé : Jo propose retour propriété / revoir limites / Pierre de retour.
- Après /rpgadmin player resetnew : retour correct à l'état « non débloqué ».
Pas de duplication de logique claim/unlock (conditions de dialogue sur CLAIM_TIER_1 / NO_MAIN_CLAIM). Cohérent avec le Guide (#11).
Issue validée.
```

### #22
```
Validation manuelle DEV OK (compte NON-OP) :
- Arrivée dans le monde Claims (joueur éligible) : Pierre de retour reçue automatiquement, une seule fois. Log [claims-safety] « éligible … garantie d'une Pierre de retour ».
- Clic droit sur la Pierre de retour → retour au spawn du Hub, sans commande.
- Joueur non éligible amené dans Claims par /tp → renvoyé au Hub + message. Log [claims-safety] « NON éligible … renvoi au Hub ».
- Aucun joueur bloqué ; le spawn Hub configuré sert de cible de repli.
Issue validée.
```

### #21
```
Validation manuelle DEV OK (compte NON-OP, portail réel hub_to_claims) :
- Sans CLAIM_TIER_1 : le portail Hub → claims REFUSE l'entrée, aucune téléportation, message d'orientation vers Jo/le Guide. Log [claims-access] « REFUSÉE (CLAIM_TIER_1 non débloqué…) ».
- Après /rpgadmin quest complete crystal_hunt (CLAIM_TIER_1 = true) : le portail autorise l'entrée. Log [claims-access] « autorisée (CLAIM_TIER_1 débloqué) ».
- Après /rpgadmin player resetnew : le portail refuse de nouveau (cas 3 de l'issue).
- Portails vers le Wild / autres mondes : comportement inchangé.
- (compte OP : entre via le bypass rpgquest.admin.world, tracé explicitement dans la console — voir #35.)
Issue validée.
```

------------------------------------------------------------------------

## 11. ISSUE #37 — CONTROL PANEL : audit du socle `web-api` + architecture

Livrable de cette session : **audit + documentation d'architecture** (`docs/control-panel/`), pas
de code (conforme au point 11 : « audit … architecture … pas de grosse UI »).

### Audit du module `web-api/` existant

- **Module Gradle séparé** (`settings.gradle.kts` → `include("web-api")`), **process JVM
  indépendant** (`WebApiMain`), **zéro dépendance Paper/Bukkit**, **aucun accès à `data.db`** —
  garantie d'isolation explicitement affirmée dans `web-api/build.gradle.kts` et `docs/WEB_API.md`.
- **Modèle d'intégration actuel = snapshot pull** : le plugin écrit périodiquement
  `plugins/RPGQuest/web-export/snapshot.json` (atomique) via `web.WebSnapshotWriter` (config
  `web-export`), le module `web-api` le **lit seulement** (`SnapshotStore`). Read-only, pas
  d'action admin possible.
- **Exception SQLite documentée** : `web-api/store/` a sa propre base `store.db` (xerial
  sqlite-jdbc) pour les commandes boutique idempotentes — **séparée de `data.db`**.
- **Stack HTTP** : `com.sun.net.httpserver` (JDK), codec JSON maison (`webapi.json.Json`),
  `RequestPipeline` (rate limit + access log + validation), `AuthFilter` (Bearer token partagé,
  comparaison temps constant, fail-closed). Config via `web-api.properties` + env
  `RPGQUEST_WEB_API_TOKEN`.
- **Handlers** : `api/` (Status, Players, Leaderboards, Catalog, Announcements — read-only),
  `site/` (pages HTML publiques : home, status, leaderboards, wiki), `store/` (checkout, webhook
  signé, livraisons).
- **Ce qui manque pour un Control Panel** : (1) pas de **session utilisateur** (juste un token
  serveur-à-serveur) ; (2) pas d'**action admin** (tout est read-only) ; (3) pas de **canal live
  plugin → panel** (le snapshot a 30 s de retard, pas de version plugin, pas d'état PNJ, pas de
  liste des bindings, pas de diagnostic) ; (4) pas d'**audit log** ; (5) pas de notion de
  **cible/environnement** (un seul serveur implicite) ; (6) pas de **couche permissions**.

### Décision d'architecture proposée (à valider)

- **Réutiliser le socle HTTP/JSON/pipeline de `web-api`**, mais **le Control Panel est un domaine
  distinct** du portail public + boutique : soit un **nouveau module `control-panel/`** partageant
  le code commun extrait dans un petit module `web-common/`, soit un package
  `webapi/controlpanel/` clairement cloisonné avec sa propre chaîne d'auth. Recommandation :
  **`control-panel/` séparé** (posture de sécurité différente : sessions + actions admin vs
  portail public anonyme).
- **Bridge RPGQuest = nouvel endpoint HTTP admin *dans le plugin*** (`web.admin`), authentifié
  par token, exposant progressivement : `GET /admin/health` (version plugin, mondes chargés,
  PNJ bindings, compteurs), puis des **actions déclaratives whitelistées** qui **appellent les
  services métier existants** (`QuestProgressEngine`, `StoryService`, `PlayerResetService`,
  `RpgAdminCommand` refactoré en service) — **jamais** de shell, **jamais** d'écriture directe
  `data.db` depuis le panel. Le snapshot read-only reste pour le portail public.
- **#36** : les commandes admin de test deviennent des **actions du bridge** ; leur logique reste
  dans le plugin (ne pas la dupliquer côté web).
- **#29** (pilotage Claude/mobile) : module « Développement » du Control Panel, **même socle
  auth/backend**, pas une 2e app.
- **Multi-environnement** : toute cible = objet `{env, bridgeUrl, token}` configurable, jamais de
  `localhost`/`world_hub` en dur.
- **Contrat versionné** : préfixe `/admin/v1/…`.
- **Audit log** : table/append-only dès le socle (`{who, action, target, result, ts, details}`),
  même avec 2 actions en V1.

### État du travail #37 dans cette session

- ✅ Audit `web-api` (ci-dessus).
- ✅ `docs/control-panel/` créé : `README.md` (vision), `ARCHITECTURE.md`, `SECURITY.md`,
  `CONFIGURATION.md`, `RPGQUEST_BRIDGE.md` (contrat de communication), `ROADMAP.md`,
  `DECISIONS.md` (ADR), relation #29/#36.
- ⛔ **Aucun code** (skeleton applicatif, bridge plugin) — à faire dans une issue/branche
  dédiée `feat/37-control-panel` avec `./gradlew build` vert, hors de cette session de
  préparation.

------------------------------------------------------------------------

## 12. Fichiers créés / modifiés

### Créés
- `docs/claude-reports/2026-09-07_0744_preparation-session-validation-manuelle-backlog.md` (ce rapport).
- `docs/control-panel/README.md`, `ARCHITECTURE.md`, `SECURITY.md`, `CONFIGURATION.md`,
  `RPGQUEST_BRIDGE.md`, `ROADMAP.md`, `DECISIONS.md`.

### Modifiés
- `docs/claude-reports/README.md` — index.

Aucun code source touché. Aucun déploiement. Aucune migration.

## Base de données / migrations

Aucune. Lecture seule d'une copie de `data.db` (hors ligne, scratchpad) pour l'audit — jamais
d'écriture, jamais sur le fichier serveur.

## Tests automatiques

Sans objet (aucun code modifié). Rappel : `./gradlew test build` était **BUILD SUCCESSFUL** au
dernier commit de code (`a3c3049`, #36).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — c'est précisément l'objet de ce rapport : voir la checklist §8.

## Déploiement VeryGames

**Aucun déploiement effectué par cette session.** Le JAR DEV actuel (`bcc3a6ec…`, commit `a3c3049`)
contient déjà tout le nécessaire pour #21/#22/#23/#36. Seules actions serveur ce soir :
**redémarrer** + **créer le PNJ Garde** (voir §4).

## Rollback

Sans objet (aucune modification serveur).

## Logs / diagnostic

- `[claims-access] …` (`ClaimWorldAccessGuard`) : bypass OP / refus / autorisation / téléportation.
- `[claims-safety] …` (`ClaimWorldSafetyListener`) : bypass / renvoi Hub / Pierre de retour.
- `[admin] <exécutant> : /rpgadmin …` : chaque commande #36 sensible.

## Documentation mise à jour

`docs/control-panel/*` (nouveau), `docs/claude-reports/README.md`.

## Limitations / travail restant

- **10 tickets `needs-manual-test` n'ont aucune implémentation** (#24, #25, #26, #28, #30, #31,
  #32, #33, #34, #35) — non testables tant qu'ils ne sont pas développés.
- **#27** est sur une branche divergente non déployable telle quelle.
- **Blocant commun #21/#23/#36** : le **PNJ Garde** n'existe toujours pas sur DEV et le serveur
  n'a probablement pas été redémarré sur le JAR #36.
- **#37** : documentation d'architecture livrée, **aucun code** — reste une issue de dev à part
  entière.
- Aucune issue fermée, aucune branche fusionnée, aucun déploiement (conforme à la demande).

## Prochaine étape suggérée

1. Ce soir : PHASE 0 (redémarrage + Garde) → PHASE 1 (#36) → PHASE 4 (#21/#22/#23 non-OP) →
   coller les commentaires §10 et fermer #21, #22, #23, #36 si vert.
2. Session de dev courte : **#30/#31 + #33 + #34** (bugs Hub/craft, correctifs courts groupés) —
   meilleur ratio « tickets fermés / effort ».
3. Puis : `feat/37-control-panel` (skeleton app + bridge plugin health), sur la base
   `docs/control-panel/`.
