# Changelog serveur — actions VeryGames

Historique de tout changement nécessitant une action manuelle sur le
serveur VeryGames (remplacement de JAR, configuration, données, monde,
commande...). Complète [VERYGAMES.md](VERYGAMES.md) (procédures génériques)
avec un journal daté des actions réellement effectuées ou à effectuer.

**Toute modification qui a un impact sur le serveur de production doit
ajouter une entrée ici, dans la même branche/PR** — voir la règle dans
[PROJECT_RULES.md](../../PROJECT_RULES.md#déploiement-verygames).

## Modèle d'entrée

```md
## YYYY-MM-DD - Nom du changement

### Changement

Résumé fonctionnel.

### Action serveur

Indiquer précisément :
- remplacer uniquement le JAR RPGQuest ;
- modifier/copier un fichier de configuration ;
- migrer des données ;
- ajouter/modifier un monde ;
- ajouter/mettre à jour un plugin externe ;
- exécuter une commande ;
- ou "aucune action manuelle autre que remplacement du JAR".

### Sauvegarde préalable

Ce qu'il faut sauvegarder.

### Déploiement

Étapes exactes.

### Validation

Tests/logs/commandes à vérifier.

### Rollback

Procédure de retour arrière.
```

---

## 2026-08-15 - HubWorldRulesService (règles du monde Hub)

### Changement

Ajout du service `HubWorldRulesService` (+ `HubWorldProtectionListener`) :
applique automatiquement, par code, jour permanent et météo permanente sur
le monde Hub (nom lu depuis `hub.world` en config, `world_hub` par défaut
si aucune section `hub:` n'existe), ainsi que les protections associées
(dégâts joueurs annulés, PvP bloqué, casse/pose de bloc bloquée sauf
bypass admin `rpgquest.admin.world`, explosions sans destruction de bloc,
spawn naturel de mobs hostiles bloqué, claims interdits). Réappliqué au
démarrage et à chaque (re)chargement tardif du monde (ex. par
Multiverse-Core après RPGQuest).

### Action serveur

Remplacement du JAR RPGQuest uniquement — aucune action manuelle autre que
remplacement du JAR.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar`.
- `plugins/RPGQuest/data.db` (les règles ne touchent à aucune donnée
  persistante, mais suivre la procédure standard de
  [mise à jour du seul JAR](VERYGAMES.md#mise-à-jour-du-seul-jar-rpgquest-scénario-2)).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP).
   Ne toucher à aucun autre fichier (`config.yml` n'a pas besoin d'être
   modifié : `hub.world` est déjà `world_hub` par défaut si la section
   `hub:` est absente).
4. Redémarrage complet requis (un `/rpgquest reload` ne suffit pas : les
   règles sont appliquées au démarrage du service et au chargement du
   monde).

### Validation

- Log attendu au démarrage :
  `Règles du monde Hub appliquées : world_hub (jour et météo permanents).`
- Avec un joueur **non-OP** dans `world_hub` :
  - jour fixe (l'heure ne progresse pas) ;
  - météo claire en permanence ;
  - aucun dégât subi (chute, faim, feu, mob, PvP...) ;
  - casse/pose de bloc impossible.
- Avec un joueur **OP** (`rpgquest.admin.world`) : construction (casse/pose
  de bloc) toujours possible dans `world_hub` (bypass conservé).
- Aucun mob hostile n'apparaît par spawn naturel dans `world_hub`.
- `/claim create` refusé dans `world_hub`.

### Rollback

1. Arrêter le serveur.
2. Remettre en place l'ancien JAR RPGQuest sauvegardé.
3. Redémarrer et vérifier `/rpgquest version` (ancienne version affichée).

Aucune donnée n'a été migrée par ce changement : le rollback ne touche que
le JAR.

---

## 2026-09-05 - Preview / dry-run du reset joueur admin (issue #8)

### Changement

Ajout de la sous-commande `/rpgadmin player resetnew <joueur> preview` :
dry-run qui liste, catégorie par catégorie, ce qu'un reset réel effacerait
pour le joueur ciblé, **sans effectuer aucune écriture** (aucune
suppression en base, aucun marqueur `__pending_new_player_reset__`, aucune
invalidation de cache, aucun objet retiré de l'inventaire). En ligne ou
hors ligne. Le comportement de `resetnew <joueur> confirm` est **inchangé**
(seule une factorisation interne de `PlayerResetService.removeRpgItems` en
`countOrRemoveRpgItems`). Nouveaux points de lecture seule :
`PlayerVariableRepository#findAllForPlayer`, `WaystoneService#discoveryCount`,
`StoryService#progressRecords`. Voir
[docs/ADMIN_PLAYER_RESET.md](../ADMIN_PLAYER_RESET.md).

### Action serveur

Remplacement du JAR RPGQuest uniquement — aucune action manuelle autre que
remplacement du JAR.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar`.
- `plugins/RPGQuest/data.db` par précaution (aucune migration, aucun schéma
  modifié — suivre la procédure standard de
  [mise à jour du seul JAR](VERYGAMES.md#mise-à-jour-du-seul-jar-rpgquest-scénario-2)).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP).
   Ne toucher à aucun autre fichier (aucune nouvelle clé de config).
4. Redémarrer.

### Validation

- `/rpgadmin player resetnew <joueur> preview` affiche l'en-tête, la ligne
  `Dry-run : aucune donnée n'a été modifiée.`, une ligne par catégorie,
  puis le rappel `Pour exécuter réellement : … confirm`.
- Après un `preview`, `/rpgadmin player resetnew <joueur> confirm` (sur un
  joueur de test) montre toujours les mêmes données qu'avant le preview
  (le preview n'a rien altéré).
- Tab-complétion : `resetnew <joueur> <TAB>` propose `confirm` et `preview`.

### Rollback

1. Arrêter le serveur.
2. Remettre en place l'ancien JAR RPGQuest sauvegardé.
3. Redémarrer et vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR.

---

## 2026-09-05 - Guide « centre d'aide » + journal de quêtes en GUI (issue #11)

### Changement

- `dialogues/guide.yml` réécrit en **centre d'aide structuré** (nœud
  `help_menu` + sujets ; orientations textuelles). Livré dans le jar,
  **copie manuelle** par l'admin (seul `guard.yml` est copié
  automatiquement — inchangé).
- Nouveau dossier `hub-guides/` (registre `hub.HubGuideRegistry`) :
  structure multi-Hub, exemple `hub_depart.yml` auto-copié au premier
  démarrage. Nouveau diagnostic admin `/rpgadmin guide list|info <hub>`
  (lecture seule).
- Le journal de quêtes (`rpgquest:journal_quetes`, remis par le Libraire)
  ouvre désormais la **GUI `/quests`** au clic droit (avant : résumé chat).
  La GUI passe de **3 à 2 onglets** : « Quêtes en cours » / « Quêtes
  terminées ». L'onglet catalogue « Disponibles » est **supprimé** — le
  journal ne liste plus que les quêtes déjà acceptées.
- `dialogues/libraire.yml` : texte mis à jour (nœud `journal_lost` ajouté).
  `items/journal_quetes.yml` : lore mis à jour.
- Service `QuestJournalBookService` (résumé chat) supprimé.

### Action serveur

Remplacement du JAR RPGQuest uniquement.

**Fichiers de configuration existants** (`dialogues/guide.yml`,
`dialogues/libraire.yml`) sur un serveur déjà en service : ils ne sont
**pas** réécrits automatiquement (seuls les fichiers absents sont
recréés). Pour bénéficier du nouveau Guide « centre d'aide » et du nouveau
texte du Libraire, **remplacer manuellement** `plugins/RPGQuest/dialogues/guide.yml`
et `plugins/RPGQuest/dialogues/libraire.yml` par les versions du jar (ou du
dépôt : `src/main/resources/dialogues/`). Le dossier `hub-guides/` et son
exemple `hub_depart.yml` sont créés automatiquement au premier démarrage
sur la nouvelle version.

Aucune migration de base, aucune nouvelle clé `config.yml`
(`dialogue.allowed-commands` contient déjà `customitem` et `claim`).

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<version>.jar`.
- `plugins/RPGQuest/dialogues/guide.yml` et `libraire.yml` (avant
  remplacement manuel).
- `plugins/RPGQuest/data.db` par précaution (procédure standard « mise à
  jour du seul JAR »).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer `plugins/RPGQuest-*.jar`.
4. Remplacer manuellement `plugins/RPGQuest/dialogues/guide.yml` et
   `plugins/RPGQuest/dialogues/libraire.yml` par les versions du jar.
5. Redémarrer.

### Validation

- Log au démarrage : `Chargement des Guides de Hub : 1 chargé(s), 0 erreur(s).`
- `plugins/RPGQuest/hub-guides/hub_depart.yml` a été créé.
- `/rpgadmin guide list` affiche `hub_depart` ; `/rpgadmin guide info hub_depart`
  affiche accueil, spécialité et orientations.
- Parler au Guide → « Comment fonctionne le jeu ? » → menu d'aide, chaque
  sujet s'affiche et ramène au menu.
- Parler au Libraire sans journal → recevoir exactement un journal ; lui
  reparler → l'option a disparu (pas de second exemplaire).
- Clic droit sur le journal → GUI à deux onglets « en cours » / « terminées ».
- Accepter une quête via un PNJ → elle apparaît en « en cours » ; la
  terminer → elle passe en « terminées ». Une quête jamais acceptée
  n'apparaît nulle part.
- Aucun item de la GUI n'est récupérable (tout clic/drag annulé).

### Rollback

1. Arrêter le serveur.
2. Remettre l'ancien JAR **et** les anciens `dialogues/guide.yml` /
   `libraire.yml` sauvegardés.
3. Le dossier `hub-guides/` peut rester : il est simplement ignoré par
   l'ancienne version.
4. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR et deux fichiers de
dialogue.

---

## 2026-09-06 - Parcours Claims cohérent : accès au monde des claims + retour Hub (issues #21/#22/#23)

### Changement

Rend le parcours joueur autour des claims cohérent de bout en bout, y
compris après `/rpgadmin player resetnew <joueur> confirm`.

- **Accès au monde `claims` réservé au déblocage réel** : nouveau
  `claim.ClaimWorldAccessGuard` (un `travel.WorldPortalEntryGuard`, composé
  avec l'avertissement d'entrée dans le Wild via
  `travel.CompositeWorldPortalEntryGuard`). Un portail simple vers
  `claims.world` ne laisse passer que les joueurs qui ont
  `CLAIM_TIER_1 == "true"` (même vérité que `ClaimService.create` pour un
  premier claim) **ou** possèdent déjà un claim. Sinon : aucune
  téléportation, message d'orientation vers Jo / le Guide. Seul le bypass
  `rpgquest.admin.world` passe outre.
- **Aucun joueur coincé, retour Hub sans commande** : nouveau
  `claim.ClaimWorldSafetyListener`. À l'arrivée dans le monde des claims
  (changement de monde ou connexion), un joueur éligible sans
  `rpgquest:pierre_retour` en reçoit une automatiquement (voyage
  claims → Hub, clic droit, jamais consommée) ; un joueur non éligible qui
  s'y retrouve autrement (`/tp`, reconnexion, présence antérieure) est
  renvoyé au Hub (spawn du village, sinon spawn du monde Hub).
- **Dialogues alignés sur la logique réelle** :
  `dialogues/guide.yml` (`help_claims`, `help_qui`) énonce le prérequis —
  terminer l'histoire principale (`crystal_hunt`, rendue au Garde) *puis*
  voir Jo. `dialogues/jo.yml` adapte son texte aux 3 états : non débloqué
  (« Comment obtenir mon premier terrain ? »), débloqué sans claim (remet
  l'Acte), claim existant (retour / limites / Pierre de retour).
- **Moteur de dialogue** : nouveau modificateur `negate: true` sur
  n'importe quelle condition (`dialogue.model.NegatedCondition`).

### Action serveur

Remplacer le JAR RPGQuest **et** trois fichiers de dialogue :
`plugins/RPGQuest/dialogues/guide.yml`, `plugins/RPGQuest/dialogues/jo.yml`.
(`guide.yml` était déjà à remplacer pour l'issue #11.) Aucune migration de
données, aucun changement de `config.yml` (`dialogue.allowed-commands`
contient déjà `customitem` et `claim`).

### Sauvegarde préalable

- JAR RPGQuest en ligne → backup daté.
- `plugins/RPGQuest/dialogues/guide.yml` et `plugins/RPGQuest/dialogues/jo.yml`
  → backup daté avant remplacement.
- `plugins/RPGQuest/data.db` par précaution (procédure « mise à jour du
  seul JAR »). **Ne pas** toucher `data.db`, les mondes, Citizens, les
  autres plugins, ni les autres fichiers de `plugins/RPGQuest/`.

### Déploiement

1. Compiler (`./gradlew test` puis `./gradlew build`).
2. Arrêter le serveur (ou déployer à chaud puis `reload confirm` — dialogues
   rechargés au démarrage du plugin).
3. Remplacer `plugins/RPGQuest-*.jar`.
4. Remplacer `plugins/RPGQuest/dialogues/guide.yml` et
   `plugins/RPGQuest/dialogues/jo.yml` par les versions du jar.
5. Redémarrer.

### Validation

- Nouveau joueur / après `resetnew` : parler à Jo → seule l'option
  « Comment obtenir mon premier terrain ? » ; le portail Hub → claims
  refuse l'entrée avec un message, aucune téléportation.
- Terminer l'histoire principale (rendre `crystal_hunt` au Garde) : Jo
  propose « Je viens réclamer mon acte de propriété » ; le portail laisse
  passer et une Pierre de retour est reçue à l'arrivée.
- Dans le monde des claims, clic droit sur la Pierre de retour → retour au
  village, sans commande.
- `resetnew` du même joueur → le portail refuse de nouveau l'entrée.
- Portails vers le Wild / autres mondes : comportement inchangé.

### Rollback

1. Arrêter le serveur.
2. Remettre l'ancien JAR **et** les anciens `dialogues/guide.yml` /
   `dialogues/jo.yml` sauvegardés.
3. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR et deux fichiers de
dialogue. `CLAIM_TIER_1` et les claims existants ne sont jamais modifiés
par ce changement.

---

## 2026-09-06 - Audit parcours principal : Garde manquant + `guard.yml` périmé, journalisation d'accès claims

Voir le rapport `docs/claude-reports/2026-09-06_1219_audit-parcours-principal-garde-crystal-hunt.md`.

### Constat (audit du contenu réel DEV)

- **Aucun PNJ Citizens lié `guard`** sur DEV (`npc_citizens_bindings` : `guide`,
  `libraire`, `help`, `jeff`, `junior`, `jo` — pas de `guard`). Conséquence :
  `first_steps` **et** `crystal_hunt` sont indémarrables → `CLAIM_TIER_1`
  jamais accordé → premier claim impossible. Preuve base : 0 progression
  `first_steps`/`crystal_hunt`, 0 `CLAIM_TIER_1`, 0 claim, `story_progress`
  vide.
- **`plugins/RPGQuest/dialogues/guard.yml` périmé** : ne contient pas la
  branche `crystal_hunt` (« J'ai entendu dire… ») ni le nœud
  `crystal_hunt_accepted`. Même avec un PNJ `guard`, `crystal_hunt` resterait
  indémarrable.
- **#21/#22** : le code refuse bien l'entrée / renvoie au Hub pour un compte
  **non opéré** ; les symptômes rapportés (« j'entre sans déblocage »,
  « je reste coincé sans Pierre de retour ») correspondent au **bypass
  `rpgquest.admin.world`** (défaut `op`, aucun plugin de permissions installé)
  déclenché par un test depuis un compte OP.

### Changement

- **Code** : `ClaimWorldAccessGuard` et `ClaimWorldSafetyListener` journalisent
  désormais chaque décision (`INFO`, préfixes `[claims-access]` /
  `[claims-safety]`) — bypass OP, refus, autorisation, renvoi Hub, Pierre de
  retour. Aucun changement de comportement.

### Action serveur

Remplacer le JAR RPGQuest **et** :

- `plugins/RPGQuest/dialogues/guard.yml` — **obligatoire** (débloque
  `crystal_hunt`).
- `plugins/RPGQuest/quests/first_steps.yml` — **recommandé** (réaligne une
  édition faite à la main sur le serveur ; à omettre pour la conserver).

Puis, **en jeu** (admin, après redémarrage) : créer le PNJ Garde et le lier —
`/npc create Garde --type player` puis, en le visant, `/rpgadmin npc tag guard`
(ajoute une ligne à `npc_citizens_bindings`). Détail :
`docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1b.

### Sauvegarde préalable

- JAR RPGQuest en ligne → backup daté (script).
- `dialogues/guard.yml` (+ `quests/first_steps.yml` s'il est remplacé) →
  backup daté avant remplacement (script).
- **Ne pas** toucher `data.db`, `config.yml`, `messages.yml`, `spawn.yml`,
  `worlds.yml`, les mondes, `plugins/Citizens/**`, `plugins/Multiverse-Core/**`,
  les autres plugins.

### Redémarrage requis

Oui (nouveau JAR + rechargement des dialogues au démarrage du plugin).

### Exécution

- **2026-09-06 13:04:54Z** : `scripts/deploy-verygames.sh -y --also
  src/main/resources/dialogues/guard.yml:RPGQuest/dialogues/guard.yml`.
  - JAR : ancien `3806243c…8946` (1 117 023 o) → nouveau
    `89b226b8fee9773a46ef90120244be8163ca275a96359e53c33dd027bc60cb58`
    (1 117 682 o). Backup :
    `verygames-backups/rpgquest-20260906T130454Z-predeploy.jar`.
  - `RPGQuest/dialogues/guard.yml` : 1108 o (version périmée, sans
    `crystal_hunt`) → 1850 o (== dépôt, diff vérifié après upload). Backup :
    `verygames-backups/extra-20260906T130454Z/RPGQuest/dialogues/guard.yml`.
  - `quests/first_steps.yml` **non transféré** (édition serveur conservée).
  - **Redémarrage serveur + création du PNJ Garde : actions manuelles non
    encore effectuées** au moment de ce déploiement.

### Migration automatique

Aucune. La création du PNJ Garde ajoute une seule ligne à
`npc_citizens_bindings` via `/rpgadmin npc tag`.

### Validation

Avec un compte **non opéré** : portail Hub → claims refusé sans `CLAIM_TIER_1`
(aucune téléportation) ; après `crystal_hunt` rendue au Garde, portail
autorisé + Pierre de retour à l'arrivée ; clic droit Pierre de retour →
retour Hub sans commande ; `/tp` forcé dans `claims` sans droit → renvoi Hub.
Observer les logs `[claims-access]` / `[claims-safety]`.

### Rollback

1. Arrêter le serveur.
2. Restaurer l'ancien JAR **et** l'ancien `dialogues/guard.yml` (+
   `quests/first_steps.yml` s'il a été remplacé) depuis les backups datés
   (`scripts/rollback-verygames.sh --latest` pour le JAR).
3. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée. La liaison PNJ `guard` créée en jeu peut être retirée
avec `/rpgadmin npc untag` (en visant le PNJ) si besoin.

---

## 2026-09-06 - Raccourcis d'administration / test quêtes & stories (issue #36)

Voir le rapport `docs/claude-reports/2026-09-06_2028_admin-test-shortcuts-issue-36.md`
et `docs/ADMIN_TEST_SHORTCUTS.md`.

### Changement

Nouvelles sous-commandes `/rpgadmin` (outils DEV/admin, réutilisent les
services métier, aucune écriture directe en base) :

- `/rpgadmin quest start|complete|reset <joueur> <quest-id> [force]`
- `/rpgadmin story advance|complete <joueur> <storyId>`
- `/rpgadmin player variable get|set <joueur> <clé> [valeur]`

`complete` / `story advance|complete` appliquent les récompenses (dont
`VARIABLE`, ex. `CLAIM_TIER_1`) **une seule fois**. `quest reset` rend la
quête rejouable mais **n'annule pas** les récompenses déjà accordées.

### Nouvelle permission

`rpgquest.admin.debug` (`default: op`) — requise **en plus** de
`rpgquest.admin.world` pour `/rpgadmin player variable set` uniquement.
Livrée avec le JAR (`plugin.yml` embarqué). Ne jamais l'accorder à un
joueur normal.

### Action serveur

Remplacer **uniquement** le JAR RPGQuest. Aucun fichier de contenu, aucune
migration, aucun changement de `config.yml`.

- 2026-09-06 21:28:47Z : `scripts/deploy-verygames.sh -y`.
  - JAR : `89b226b8…cb58` → `bcc3a6ec765c7fb7adeff2a15f2b49637a535fcc89c156184ab16bbd700bccdd`
    (1 136 148 o, vérifié après upload). Backup :
    `verygames-backups/rpgquest-20260906T212847Z-predeploy.jar`.
  - **Redémarrage serveur : action manuelle non encore effectuée.**

### Redémarrage requis

Oui (nouveau JAR + nouvelle permission dans le `plugin.yml` embarqué).

### Rollback

`scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20260906T212847Z-predeploy.jar`),
puis redémarrer et vérifier `/rpgquest version`. Aucune donnée migrée.
