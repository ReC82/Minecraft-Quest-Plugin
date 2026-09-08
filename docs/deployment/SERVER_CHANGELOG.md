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

---

## 2026-09-07 - Agent sortant PlugAdmin (issue #51)

### Changement

Nouveau composant plugin `com.lodygames.rpgquest.web.agent` (`PlugAdminAgent`) :
RPGQuest ouvre une connexion **HTTPS SORTANTE** vers PlugAdmin
(`https://plugadmin.lodylands.com`) pour publier un heartbeat régulier et
récupérer une file d'actions whitelistées. Aucune écoute entrante ajoutée
côté serveur. Aucun impact gameplay : tout est asynchrone, avec timeouts
courts, backoff et arrêt propre ; si PlugAdmin est injoignable, le serveur
Minecraft continue normalement.

Le heartbeat réutilise `HealthSource` (#37) — aucune logique de health
dupliquée. La seule action ouverte est **non destructive** :
`player.variable.get` (lecture de `player_variables` via le service métier).
Tout autre type d'action est refusé (`REJECTED`).

### Action serveur

1. **Remplacer le JAR RPGQuest** (build de la branche `feat/51-plugadmin-outbound-agent`).
   À ce stade, **l'agent reste INERTE** : aucun fichier de configuration
   agent n'est présent → aucune connexion sortante, aucun changement de
   comportement observable.
2. **Déposer le fichier de configuration agent** (hors Git, contient un
   jeton) :
   `plugins/RPGQuest/plugadmin-agent.properties`
   à partir du modèle `scripts/plugadmin-agent.properties.example`, avec au
   minimum `enabled=true`, `base-url=https://plugadmin.lodylands.com`,
   `agent-id=rpgquest-dev`, `environment=dev`, `token=<jeton>`.
   Transfert : `scripts/deploy-verygames.sh --also
   scripts/plugadmin-agent.properties:RPGQuest/plugadmin-agent.properties`
   (le fichier réel avec le vrai jeton doit être préparé localement, hors
   dépôt) **ou** upload manuel dans le panel FTP VeryGames.
3. Côté PlugAdmin/AWS (déjà fait par cette session) : ajouter
   `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV=<le même jeton>` dans
   `/etc/plugadmin/plugadmin.env` et déclarer l'agent dans
   `/etc/plugadmin/control-panel.properties`, puis `systemctl restart plugadmin`.
4. **Redémarrer le serveur RPGQuest** (VeryGames) — action manuelle owner
   dans le panel VeryGames (pas d'API/RCON).

### Ne PAS altérer

`data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, autres
plugins. Le fichier agent est le SEUL ajout sous `plugins/RPGQuest/`.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar` (backup automatique
  par `deploy-verygames.sh`).
- `plugins/RPGQuest/data.db` (procédure standard, même si aucune donnée
  n'est touchée).
- Aucun `plugadmin-agent.properties` préexistant à sauvegarder (fichier
  nouveau).

### Déploiement

1. `./gradlew clean build` sur `feat/51-plugadmin-outbound-agent`.
2. `scripts/deploy-verygames.sh -y` (JAR seul) — **agent inerte**.
3. Vérifier au redémarrage : aucune régression, log
   `Agent PlugAdmin désactivé` ou `Agent PlugAdmin inactif`.
4. Déposer `plugins/RPGQuest/plugadmin-agent.properties` (jeton hors Git).
5. Redémarrer le serveur.
6. Vérifier le log `event=plugadmin_probe status=ok …` (connectivité HTTPS
   sortante confirmée) et le dashboard PlugAdmin (« RPGQuest DEV — ONLINE »).

### Validation

- Serveur RPGQuest : démarre normalement ; log
  `Agent PlugAdmin démarré : cible rpgquest-dev …` puis
  `event=plugadmin_probe status=ok`.
- PlugAdmin : `journalctl -u plugadmin` → `event=agent_heartbeat agent=rpgquest-dev …` ;
  dashboard `https://plugadmin.lodylands.com/dashboard` → **ONLINE**, version
  plugin / joueurs / mondes réels.
- Aller-retour d'action : page **Agents** → envoyer `player.variable.get`
  (`CLAIM_TIER_1`) → résultat `SUCCESS` avec la valeur.
- Coupure PlugAdmin (ex. `systemctl stop plugadmin` quelques minutes) : le
  serveur Minecraft n'est pas affecté ; l'agent passe en backoff puis se
  reconnecte au retour.

### Rollback

- **Simple** : mettre `enabled=false` dans
  `plugins/RPGQuest/plugadmin-agent.properties` (ou supprimer le fichier) et
  redémarrer → agent inerte, JAR conservable en l'état.
- **JAR** : `scripts/rollback-verygames.sh --latest` (restaure le backup
  `*-predeploy.jar`), redémarrer, vérifier `/rpgquest version`.
- Côté PlugAdmin : `agents=` (vide) dans `control-panel.properties` +
  `systemctl restart plugadmin`, ou `scripts/plugadmin/rollback.sh app`.
- Aucune migration `data.db` à défaire. Les tables `agent_*` de
  `control-panel.db` sont sans effet sur RPGQuest.

### Exécution réelle — 2026-09-07 ~12:20 UTC

- **RCON VeryGames confirmé fonctionnel** depuis AWS (DEV : `51.68.57.28:7469`).
  Les mentions antérieures « pas de RCON / redémarrage manuel owner » sont
  **obsolètes**. Outils ajoutés au dépôt : `scripts/verygames-rcon.py`,
  `scripts/verygames-restart.sh`.
- Config RCON : `~/.config/rpgquest/verygames.env` (mêmes que FTP), clés
  `RCON_HOST` / `RCON_PORT` / `RCON_PASSWORD`, `chmod 600`. Mot de passe jamais
  affiché / committé.
- `scripts/deploy-verygames.sh -y --allow-no-backup --also
  ~/.config/rpgquest/plugadmin-agent.properties:RPGQuest/plugadmin-agent.properties`
  - `./gradlew test` + `build` : OK.
  - Backup du JAR en ligne :
    `verygames-backups/rpgquest-20260907T121945Z-predeploy.jar`
    (`bcc3a6ec…` — c'était l'ancien JAR de l'issue #36, jamais redémarré).
  - JAR déployé : `rpgquest-0.1.0-SNAPSHOT.jar`
    `5e9d9a5e02ae9b515f1bcd06147b3bdb0794a59efc97a6159d687ae91e39a3e4`
    (1 181 666 o, branche `feat/51-plugadmin-outbound-agent`, commit `858e7d4`).
  - Fichier agent déployé : `plugins/RPGQuest/plugadmin-agent.properties`
    (283 o, hors Git, `enabled=true`, jeton partagé avec AWS).
  - Aucun autre fichier touché.
- `scripts/verygames-restart.sh` : `save-all` → `stop` (RCON) → serveur OFFLINE
  → relance automatique VeryGames → **ONLINE** en < 1 min.
- **Validation live** : heartbeats réels reçus par PlugAdmin
  (`event=agent_heartbeat agent=rpgquest-dev version=0.1.0-SNAPSHOT`,
  mondes hub/claims/wild chargés) ; action `player.variable.get`
  (Rondoudou9000 / CLAIM_TIER_1) → `SUCCESS` (« variable absente ») ; type
  inconnu → `REJECTED` ; idempotence (re-livraison) OK ; arrêt de PlugAdmin
  ~45 s → Minecraft non affecté, agent reconnecté automatiquement au retour.
- Côté AWS : `/etc/plugadmin/control-panel.properties` (bloc `agents=`) et
  `/etc/plugadmin/plugadmin.env` (`RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV`, généré,
  jamais affiché) ; PlugAdmin redéployé (`scripts/plugadmin/deploy.sh`) ;
  autres sites (`dig.lodygames.com`, `lodylands.com`) non impactés.
- Rapport : `docs/claude-reports/2026-09-07_1127_plugadmin-outbound-agent-issue-51.md`.

---

## 2026-09-07 - Actions métier whitelistées pour l'outillage Control Panel

### Changement

Extension de l'agent sortant (#51) : `com.lodygames.rpgquest.web.agent` gagne
la façade `AgentActions` (impl. `BukkitAgentActions`) et de nouveaux types
whitelistés dans `AgentActionType`, chacun adossé à un **service métier
existant** (`QuestProgressEngine`, `StoryService`, `YamlCustomItemRegistry`,
`PlayerResetService`, `PlayerVariableRepository`) — jamais une commande texte
`/rpgadmin`, jamais de SQL direct, mutations replacées sur le thread principal.

Lectures : `player.list`, `quest.list`, `quest.player.status`, `story.list`,
`story.player.status`, `item.list`, `player.resetnew.preview`.
Mutations : `player.item.give`, `quest.start|complete|reset`,
`story.advance|complete`, `player.variable.set`, `player.resetnew.confirm`.

Tout type absent des listes blanches → `REJECTED`. Les mutations exigent une
confirmation explicite côté panel ; l'agent exige en plus `confirm=true` pour
`player.resetnew.confirm`.

### Action serveur

1. **Remplacer le JAR RPGQuest** (build de la branche
   `feat/control-panel-admin-tools`).
2. **Aucune autre action** : pas de nouveau fichier de configuration, pas de
   migration, pas de changement `config.yml` / `data.db` / mondes / Citizens.
3. L'agent reste **inerte** sans `plugins/RPGQuest/plugadmin-agent.properties`
   (comme #51). Avec l'agent actif, **aucune** de ces actions ne s'exécute
   tant que le Control Panel n'en crée pas une explicitement (file `PENDING`).
4. Redémarrage serveur requis (remplacement de JAR).

### Ne PAS altérer

`data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, autres plugins,
`plugadmin-agent.properties` existant.

### Sauvegarde préalable

- Ancien JAR (backup automatique par `deploy-verygames.sh`).
- `plugins/RPGQuest/data.db` (procédure standard).

### Déploiement

1. `./gradlew test build` sur `feat/control-panel-admin-tools`.
2. `scripts/deploy-verygames.sh -y` (JAR seul).
3. Redémarrer le serveur.
4. Côté PlugAdmin/AWS : redéployer l'app du panel (`scripts/plugadmin/deploy.sh`)
   pour obtenir les pages Joueurs / Quêtes / Stories et la liste blanche
   `AgentActionCatalog`.

### Validation

- Serveur RPGQuest démarre normalement ; agent : `event=plugadmin_probe status=ok`.
- Panel → page **Joueurs** → « Rafraîchir la liste » → l'action passe
  `PENDING → SUCCESS` **sans rechargement** (issue #65) et le roster s'affiche.
- Page **Quêtes** → « Rafraîchir le catalogue » → titres lisibles + étapes.
- `quest.complete` sur une quête déjà terminée → `FAILED` « déjà terminée »
  (aucune récompense re-créditée) ; sans confirmation → refusée côté panel.
- Un type d'action inconnu (ex. `console.run`) → `REJECTED`, audité `DENIED`.

### Rollback

- **Simple** : `enabled=false` dans `plugadmin-agent.properties` → agent inerte.
- **JAR** : `scripts/rollback-verygames.sh --latest`, redémarrer.
- Aucune migration `data.db` à défaire. `control-panel.db` (tables `agent_*`)
  sans effet sur RPGQuest.

### Exécution réelle

Non déployé par cette session (développement uniquement, sur AWS). Branche
`feat/control-panel-admin-tools` poussée, **non fusionnée**.
Rapport : `docs/claude-reports/2026-09-07_2159_control-panel-admin-tools.md`.

---

## 2026-09-08 - Déploiement VeryGames DEV : JAR RPGQuest de `feat/control-panel-admin-tools`

### Changement

Déploiement effectif sur **VeryGames DEV** du JAR RPGQuest bâti depuis
`feat/control-panel-admin-tools` @ `3c3ea5a` (contenu métier = commit
`e1ddb8c`, voir l'entrée du 2026-09-07 ci-dessus). Objectif : que l'agent
sortant reconnaisse les 15 nouveaux types d'action déjà servis par le Control
Panel AWS (`player.list`, `quest.list`, `quest.player.status`, `story.list`,
`story.player.status`, `item.list`, `player.resetnew.preview|confirm`,
`player.item.give`, `quest.start|complete|reset`, `story.advance|complete`,
`player.variable.set`). Avant : ces types revenaient `REJECTED` « Type
d'action non whitelisté » (agent plus ancien que le panel).

### Action serveur

- **Remplacement du seul JAR RPGQuest** (`rpgquest-0.1.0-SNAPSHOT.jar`).
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames).
- Aucun autre fichier : ni `data.db`, ni `config.yml`, ni `messages.yml`, ni
  mondes, ni `Citizens/`, ni `plugadmin-agent.properties` (déjà en place
  depuis #51), ni autre plugin. Aucune migration. Aucune donnée joueur touchée.
- Rien changé côté AWS/PlugAdmin (déjà déployé plus tôt le 2026-09-08).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` →
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T075204Z-predeploy.jar`
  (SHA-256 `2d648c0ba384494175f8eed3d23539198637a560d9dfd3e06a6ebce3500d58d1`,
  1 220 229 o) + `.meta`.

### Déploiement (exécuté)

1. `scripts/deploy-verygames.sh -y` — `./gradlew test` + `build` OK, backup du
   JAR en ligne, transfert FTP atomique du nouveau JAR
   (SHA-256 `cd66574cf3c780e877ab815b06aa9d8cb84c94440b49b43a29151d01a9e113cb`,
   1 256 923 o ; taille distante finale == locale).
2. `scripts/verygames-restart.sh` — `save-all` → `stop` RCON → serveur OFFLINE
   → relance auto VeryGames → **ONLINE** en < 1 min.

### Validation (exécutée)

- `/plugins` (RCON) : **RPGQuest en vert** (+ Citizens, Multiverse-Core, WorldEdit).
- `rpgquest version` (RCON) : répond `v0.1.0-SNAPSHOT`.
- Heartbeat agent reçu par PlugAdmin AWS après redémarrage :
  `server_state=ONLINE`, mondes `world_hub` / `claims` / `wild` **tous
  `loaded:true`**, `uptime_seconds≈125`. Aucune ligne `ERROR`/exception dans
  `journalctl -u plugadmin`.
- **Canal agent** (actions `PENDING` insérées dans la file `control-panel.db`,
  relevées et exécutées par l'agent) :
  - `player.list` → **SUCCESS** « 0 joueur(s) connecté(s). » (`details.players: []`) ;
  - `quest.list` → **SUCCESS** « 10 quête(s) chargée(s). » (catalogue réel).
  - → les deux ne sont **plus** `REJECTED` comme type inconnu. Objectif atteint.

### Rollback

- `scripts/rollback-verygames.sh --latest` → restaure
  `rpgquest-20260908T075204Z-predeploy.jar`, puis `scripts/verygames-restart.sh`.
- Aucune migration à défaire. `control-panel.db` (tables `agent_*`) sans effet
  sur RPGQuest.

### Exécution réelle

Déployé par cette session le 2026-09-08 (~07:52–07:56 UTC). Branche
`feat/control-panel-admin-tools` @ `3c3ea5a`, **non fusionnée**.
Rapport : `docs/claude-reports/2026-09-08_0756_deploy-verygames-actions-agent.md`.

---

## 2026-09-08 - Protocole `quest.list` structuré (objectifs / récompenses / donneur) — issues #78 / #75

### Changement

L'action agent `quest.list` transporte désormais, **en plus** des chaînes
legacy déjà présentes, des champs **structurés** :

- `steps[].objectiveDetails[]` = `{kind, target, amount, raw}` — le panel
  détermine type / cible / quantité sans regex ;
- `rewardDetails[]` = `{kind, amount, target, value, command, raw}` — la
  commande console **n'est plus tronquée à 60 caractères** ;
- `giverId` — id du PNJ donneur, **présent uniquement** si la quête déclare le
  nouveau champ optionnel `giver:` dans son YAML.

Aucun changement de gameplay, de schéma SQL, de commande en jeu. Les YAML de
quêtes existants restent valides tels quels (`giver:` est optionnel).

### Action serveur

- **Remplacement du seul JAR RPGQuest.**
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames) pour
  recharger le plugin.
- Aucun autre fichier : ni `data.db`, ni `config.yml`, ni `messages.yml`, ni
  mondes, ni `Citizens/`, ni `plugadmin-agent.properties`, ni autre plugin.
  Aucune migration. Aucune donnée joueur touchée.
- Aucune action AWS/PlugAdmin requise pour le protocole (le panel gère déjà
  les deux formats) ; le Control Panel AWS est tout de même redéployé dans la
  même session pour embarquer le rendu structuré.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` dans
  `~/.local/share/rpgquest/verygames-backups/` (+ `.meta`). Ne jamais écraser
  le dernier backup.

### Déploiement

1. `scripts/deploy-verygames.sh -y` (lance `./gradlew test` + `build`, backup
   du JAR en ligne, transfert FTP atomique).
2. `scripts/verygames-restart.sh` (`save-all` → `stop` RCON → relance auto).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu par PlugAdmin AWS après redémarrage (aucune ligne
  `ERROR` dans `journalctl -u plugadmin`).
- Action `quest.list` (file `control-panel.db`) → **SUCCESS** ; le `details`
  renvoyé contient `steps[].objectiveDetails` et `rewardDetails` non vides, et
  `giverId` pour au moins la quête `crystal_hunt` (après ajout de `giver:` à
  son YAML).
- Catalogue `/quests` du panel exploitable avec ces données (noms FR par
  jeton, commande longue non tronquée, ligne « Donneur »).

### Rollback

- `scripts/rollback-verygames.sh --latest` → restaure le JAR `*-predeploy.jar`
  sauvegardé, puis `scripts/verygames-restart.sh`.
- AWS : `scripts/plugadmin/rollback.sh app` (release précédente du Control
  Panel).
- Aucune migration à défaire.

### Exécution réelle

Déployé par cette session le 2026-09-08 (~11:00–11:04 UTC). Branche
`feat/control-panel-admin-tools` @ `02642e9`, **non fusionnée**.

- **AWS Control Panel** : `scripts/plugadmin/deploy.sh` → `installDist` OK,
  ancienne app sauvegardée sous `/opt/plugadmin/releases/20260908-105940`,
  `systemctl restart` → `active` (PID 124361, `event=panel_started port=8090`).
  JAR déployé sha256 `6493fc1584deafb1a61b78f9d46b91ec0a5a834c37a6d2963df59816ce2a5bff`
  == build frais de la branche ; classes `ObjectiveText`/`RewardText` présentes.
  `/health` local + public `ONLINE` (×3) ; `/dashboard` `/quests` `/players`
  `/stories` (anon) → 303 `/login` ; `/login` 200 ; autres vhosts
  (`dig.lodygames.com`, `lodylands.com`, `www.lodylands.com`) → 200 ; `nginx -t` OK,
  nginx/secrets/TLS non touchés.
- **VeryGames DEV** :
  1. `scripts/deploy-verygames.sh -y` → `./gradlew test`+`build` OK, backup auto
     `rpgquest-20260908T110044Z-predeploy.jar` (sha256 `cd66574c…`), transfert FTP
     atomique du JAR **sha256 `4fbaa3456b00534c6309b4b1fcbf789fd9c2e0bdf43e6c51180817f6ddeb9c2f`**
     (1 261 384 o, taille distante == locale).
  2. `scripts/verygames-restart.sh` → `save-all` → `stop` RCON → OFFLINE → relance
     auto VeryGames → **ONLINE** en < 1 min.
  3. `scripts/deploy-verygames.sh -y --also src/main/resources/quests/crystal_hunt.yml:RPGQuest/quests/crystal_hunt.yml`
     → ancien `crystal_hunt.yml` sauvegardé
     (`verygames-backups/extra-20260908T110340Z/…`, sha256 `06156d6d…`), nouveau
     transféré (sha256 `e8f2c8ce…`) ; puis RCON `quest admin reload` →
     « 10 quête(s) chargée(s), 0 erreur(s) ».
- **Vérifs VeryGames** : `/plugins` → RPGQuest **en vert** (+ Citizens,
  Multiverse-Core) ; `rpgquest version` → `v0.1.0-SNAPSHOT` ; heartbeat agent reçu
  par PlugAdmin (`server_state=ONLINE`, `uptime_seconds` faible → redémarrage pris
  en compte) ; aucune ligne `ERROR` dans `journalctl -u plugadmin`.
- **Validation `quest.list` réelle** (action `PENDING` injectée dans
  `control-panel.db`, relevée + exécutée par l'agent DEV) → **SUCCESS**
  « 10 quête(s) chargée(s). » ; `details.quests[].steps[].objectiveDetails`
  (`kind`/`target`/`amount`/`raw` pour `KILL_ENTITY`/`COLLECT_ITEM`/`CRAFT_ITEM`/
  `TALK_TO_NPC`) et `rewardDetails` (`EXPERIENCE`/`COMMAND`/`VARIABLE`, `command`
  complète non tronquée) **présents** ; après le reload avec `giver: guard`,
  `rpgquest:crystal_hunt` porte `giverId: "guard"`. Champs legacy toujours
  présents en parallèle (compat).

### Rollback (points exacts)

- VeryGames JAR : `scripts/rollback-verygames.sh --latest` →
  `rpgquest-20260908T110044Z-predeploy.jar` (sha256 `cd66574c…`).
- VeryGames `crystal_hunt.yml` : `scripts/rollback-verygames.sh --also
  /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20260908T110340Z/RPGQuest/quests/crystal_hunt.yml:RPGQuest/quests/crystal_hunt.yml`
  puis RCON `quest admin reload` (voir `MANIFEST.txt` du dossier de backup).
- AWS : `scripts/plugadmin/rollback.sh app` → release `20260908-105940`.
- Rapport : `docs/claude-reports/2026-09-08_1100_quest-list-structure-78-75.md`.

---

## 2026-09-08 - Action agent `npc.list` + page Control Panel `/npcs` (V1) — issues #66 / #75

### Changement

Nouvelle action agent **`npc.list`** (lecture seule, whitelistée `AgentActionType` ↔
`AgentActionCatalog`). Elle renvoie un **catalogue PNJ RPGQuest** dérivé (`NpcCatalog`,
classe pure) par croisement de : liaisons Citizens (`npc_citizens_bindings`), dialogues
`rpgquest:<id>`, champ `giver:` des quêtes (#75), objectifs `TALK_TO_NPC`. Le payload porte
`npcs[]` (id, nom lisible, id Citizens, dialogue, quêtes données/référencées, `warnings[]`),
`canonicalIds` (préparation #66) et des compteurs.

Aucun changement de gameplay, de schéma SQL, de commande en jeu, ni de contenu YAML.
`NpcCatalog` **ne lit jamais le monde** (pas de scan d'entités ; position/monde et PNJ
Citizens non tagués hors périmètre V1). Le seul accès disque est le `SELECT` déjà asynchrone
des liaisons Citizens.

Côté Control Panel : l'entrée « PNJ » du menu (`/npcs`) n'est plus « à venir ».

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse le type `npc.list`).
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames).
- Aucun autre fichier : ni `data.db`, ni `config.yml`, ni `messages.yml`, ni mondes, ni
  `Citizens/`, ni YAML de contenu, ni `plugadmin-agent.properties`. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`) pour la page `/npcs`.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` dans
  `~/.local/share/rpgquest/verygames-backups/` (+ `.meta`). Ne jamais écraser le dernier backup.

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu par PlugAdmin ; aucun `ERROR` dans `journalctl -u plugadmin`.
- Action `npc.list` (file `control-panel.db`) → **SUCCESS** ; `details.npcs` non vide,
  `canonicalIds` contient `guard`, warnings cohérents.
- `/npcs` du panel exploitable (nom lisible, ids copiables, anomalies visibles).
- `/health` AWS local + public `ONLINE` ; `/npcs` (anon) → 303 `/login`.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Déployé par cette session le 2026-09-08 (~11:46–11:50 UTC). Branche
`feat/control-panel-admin-tools` @ `9fa57d1`, **non fusionnée**.

- **AWS Control Panel** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
  `/opt/plugadmin/releases/20260908-114604`, `systemctl restart` → `active` (PID 146189,
  `event=panel_started port=8090`). JAR déployé sha256
  `2bf90a25a949453eb37bc2ab82c2eff2d3dc50c1b01d3c14f47b251cdf8aa0d7` == build de la branche.
  `/health` local + public `ONLINE` ×3 ; `/npcs` (anon) → 303 `/login` (route **active**) ;
  autres vhosts (`dig.lodygames.com`, `lodylands.com`, `www.lodylands.com`) → 200 ;
  nginx/secrets/TLS non touchés.
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` → `./gradlew test`+`build` OK, backup auto
  `rpgquest-20260908T114637Z-predeploy.jar` (sha256 `4fbaa3456b00534c6309b4b1fcbf789fd9c2e0bdf43e6c51180817f6ddeb9c2f`),
  transfert FTP atomique du JAR **sha256
  `12786cb3fc0d997e55d3f7eba2f1432cdcdcaa479ae4b6100fcf8d83fa122ba3`** (1 282 340 o, distant ==
  local) ; puis `scripts/verygames-restart.sh` → `stop` RCON → OFFLINE → relance auto → **ONLINE**
  en < 1 min.
- **Vérifs VeryGames** : `/plugins` → RPGQuest **en vert** (+ Citizens) ; `rpgquest version` →
  `v0.1.0-SNAPSHOT` ; heartbeat agent (`server_state=ONLINE`, `uptime_seconds=65` → restart pris
  en compte) ; **aucune** ligne `ERROR` dans `journalctl -u plugadmin`.
- **Validation `npc.list` réelle** (action `PENDING` injectée dans `agent_action`, relevée +
  exécutée par l'agent DEV) → **SUCCESS** « 8 PNJ RPGQuest (1 avec avertissement). ».
  `details` réel : `citizensAvailable=true`, `total=8`, `bound=7`, `unbound=1`, `withWarnings=1` ;
  `canonicalIds = [guard, guide, help, jeff, jo, junior, libraire, woodcutter_bob]` ;
  `rpgquest:guard` = `{displayName:"Garde", citizensNumericId:6, dialogueId:"rpgquest:guard"
  (5 nœuds/8 choix), questsGiven:["rpgquest:crystal_hunt"],
  questsReferenced:["rpgquest:crystal_hunt"], dialogueStartsQuests:["rpgquest:first_steps",
  "rpgquest:crystal_hunt"], warnings:[]}` ; **anomalie réelle détectée** :
  `woodcutter_bob` → `QUEST_REF_NO_NPC` (`warning`) — référencé par `woodcutters_request` mais
  aucun PNJ Citizens tagué sur ce serveur.

### Rollback (points exacts)

- VeryGames : `scripts/rollback-verygames.sh --latest` →
  `rpgquest-20260908T114637Z-predeploy.jar` (sha256 `4fbaa345…`), puis `scripts/verygames-restart.sh`.
- AWS : `scripts/plugadmin/rollback.sh app` → release `20260908-114604`.
- Rapport : `docs/claude-reports/2026-09-08_1147_npc-list-page-npcs-v1.md`.

---

## 2026-09-08 - Système PNJ V2 déclarative : NpcDefinition + écritures de contenu — issues #66 / #75

### Changement

Introduit une **définition logique** de PNJ RPGQuest, indépendante de Citizens et du monde :
un fichier par PNJ sous `plugins/RPGQuest/npcs/*.yml` (`YamlNpcEngine`, `NpcDefinition` :
`id`, `display_name`, `dialogue?`, `role?`, `enabled`). Voir `NPC_FORMAT.md`.

- `npc.list` distingue désormais `logicalDefinitionPresent` vs `citizensBindingPresent`,
  calcule un `state`, et expose `definedIds` (source canonique, transition #66). Nouveaux codes
  d'anomalie : `NO_DEFINITION` / `BINDING_NO_DEFINITION` / `DIALOGUE_MISSING` (err), `NOT_LINKED`
  / `DISABLED` (info).
- Nouvelles actions agent d'**écriture de contenu** whitelistées + auditées :
  `npc.definition.create` / `npc.definition.update` (`NpcDefinitionStore` — écriture atomique,
  jamais d'écrasement silencieux, jamais de YAML brut ni de chemin arbitraire) et
  `quest.giver.set` (`QuestGiverEditor` — pose `giver:` sur le YAML d'une quête en préservant
  commentaires et format ; recharge le moteur de quêtes).

**Un exemple `npcs/guard.yml` est livré** et copié au premier démarrage (comme
`dialogues/guard.yml`). Aucun autre changement de contenu, de schéma SQL, de commande en jeu.

### Action serveur

- **Remplacement du seul JAR RPGQuest.**
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames).
- Le dossier `plugins/RPGQuest/npcs/` et `npcs/guard.yml` sont **créés automatiquement** au
  démarrage. Aucun fichier existant n'est modifié. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).
- Les définitions PNJ créées ensuite via le Control Panel écrivent dans `plugins/RPGQuest/npcs/`
  (via l'agent, sur le serveur) ; `quest.giver.set` édite un fichier de `plugins/RPGQuest/quests/`.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` (+ `.meta`). Ne jamais
  écraser le dernier backup.
- `plugins/RPGQuest/quests/` : à sauvegarder si des `quest.giver.set` sont exécutés après le
  déploiement (le script `deploy-verygames.sh --also` sauvegarde de toute façon avant tout envoi ;
  ici c'est l'agent qui édite en place — prévoir un backup FTP du dossier `quests/` avant une
  session d'attribution de quêtes).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Log de démarrage : `Chargement des PNJ : N définition(s), 0 erreur(s).`
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- Action `npc.list` → **SUCCESS** ; `details.npcs[].logicalDefinitionPresent` renseigné,
  `definedIds` contient `guard`.
- Action `npc.definition.create` (id de test, ex. `woodcutter_bob`) → **SUCCESS** ; le fichier
  `npcs/woodcutter_bob.yml` apparaît ; `npc.list` suivant montre `state: NOT_LINKED`.
- Action `quest.giver.set` sur une quête DEV (ex. `rpgquest:woodcutters_request` → `woodcutter_bob`)
  → **SUCCESS** ; le YAML de la quête contient `giver: woodcutter_bob`, commentaires préservés.
- `/npcs` du panel : deux blocs (Définition / Binding), création + édition + attribution visibles.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Les fichiers `npcs/*.yml` créés restent (inertes avec l'ancien JAR) ; les supprimer si besoin.
  Un `quest.giver.set` se défait en éditant / restaurant le YAML de la quête concernée.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~12:35–12:46 UTC). Branche `feat/control-panel-admin-tools` @ `1340dda`,
**non fusionnée**.

- **AWS Control Panel — DÉPLOYÉ** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
  `/opt/plugadmin/releases/20260908-123552`, `systemctl restart` → `active` (PID 166440). JAR
  déployé sha256 `01f14c6071584261f932eca36cc38ff11769999bc3c1ff3d60be1c3819dd51a5` == build de
  la branche. `/health` local + public `ONLINE` ×3 ; `/npcs` + `/dashboard` (anon) → 303 ; autres
  vhosts 200 ; nginx/secrets/TLS non touchés. Rollback : `scripts/plugadmin/rollback.sh app`.
- **VeryGames DEV — DÉPLOYÉ** (le FTP a eu une panne transitoire ~12:38–12:43 UTC ; deux tentatives
  ont échoué **avant toute écriture** — `curl (28) timed out` — puis le FTP est revenu). Réussi
  ~12:44 UTC : `scripts/deploy-verygames.sh -y` → JAR sha256
  `261d7a378c58f714a35c1bea861b2cdf69727ad73b94b2f73cf9e9c8a30c3549` (1 317 563 o), backup
  `rpgquest-20260908T124242Z-predeploy.jar` (sha256 `12786cb3…`) ; puis `scripts/verygames-restart.sh`
  → `stop` RCON → OFFLINE → relance auto → **ONLINE** en < 1 min.
- **Vérifs VeryGames** : `/plugins` → RPGQuest **en vert** (+ Citizens) ; `rpgquest version` →
  `v0.1.0-SNAPSHOT` ; heartbeat agent (`uptime_seconds=6`) ; aucun `ERROR` `journalctl -u plugadmin`.
- **Validation réelle** (actions injectées dans `agent_action`, exécutées par l'agent DEV) :
  - `npc.list` → **SUCCESS** « 8 PNJ (7 avec avertissement) » ; `guard` = définition
    (`npcs/guard.yml` auto-créé) + binding → `state: LINKED`, `role: quest_giver`, `displayName`
    de la définition, aucune anomalie ; `definedIds = [guard]` ; `guide`/`help`/`jeff`/`jo`/`junior`/
    `libraire` = binding sans définition → `CITIZENS_ORPHAN` + `BINDING_NO_DEFINITION` (err) ;
    `woodcutter_bob` = référencé sans définition → `UNDEFINED_REFERENCE` + `NO_DEFINITION` (err).
  - `npc.definition.create` (`woodcutter_bob`, `display_name=Bûcheron Bob`, `role=quest_giver`) →
    **SUCCESS** `CREATED`, effet `npcs/woodcutter_bob.yml`.
  - `quest.giver.set` (`rpgquest:woodcutters_request` → `woodcutter_bob`) → **SUCCESS** `SET`,
    effets `quests/woodcutters_request.yml` + `giver: woodcutter_bob` ; RCON `quest admin validate`
    → « 10 quête(s), 0 erreur(s) » (YAML édité valide, commentaires préservés).
  - `npc.list` (2e) → `definedIds = [guard, woodcutter_bob]`, `withDefinition = 2` ;
    `woodcutter_bob` porte `questsGiven: [rpgquest:woodcutters_request]` (l'attribution a pris,
    moteur de quêtes rechargé).
  - `npc.definition.update` (`woodcutter_bob`, sans `dialogue`) → **SUCCESS** — nettoie l'anomalie
    `DIALOGUE_MISSING` introduite par le `dialogue_id` de test.

### Rollback (points exacts)

- AWS : `scripts/plugadmin/rollback.sh app` → release `20260908-123552`.
- VeryGames : rien à défaire (déploiement non effectué). Après déploiement futur :
  `scripts/rollback-verygames.sh --latest` + `scripts/verygames-restart.sh` ; supprimer les
  `npcs/*.yml` créés ; restaurer un YAML de quête édité par `quest.giver.set`.
- Rapport : `docs/claude-reports/2026-09-08_1235_npc-v2-declarative.md`.

---

## 2026-09-08 - Lier une définition PNJ à un PNJ Citizens existant — issue #81 (phase 1)

### Changement

Nouvelles actions agent :

- `npc.citizens.list` (lecture) — parcourt le **registre Citizens** (`NpcIdentityService.citizensRoster`,
  thread principal, jamais un scan d'entités/chunks) et croise avec `npc_citizens_bindings` :
  `{numericId, uuid, name, linkedNpcId?, availableForBinding, spawned}` + compteurs. Vide si
  Citizens inactif.
- `npc.citizens.link` (mutation whitelistée, permission dédiée `NPC_BIND_WRITE`, `confirm`
  obligatoire, audit) — lie une **définition logique existante** (`npc_id`) à un **PNJ Citizens
  existant** (`citizens_id` numérique). `CitizensBindPlanner` (pur) : liaison identique → succès
  no-op ; ce PNJ Citizens ou ce `npc_id` déjà lié → refus lisible (**jamais de rebind
  silencieux**). Écriture atomique `NpcBindingRepository.insertIfAbsent` (`INSERT OR IGNORE`) puis
  rafraîchissement du cache `NpcIdentityService` (l'identification en jeu prend effet **sans
  redémarrage**).

**Aucun spawn, aucun rebind, aucune suppression de PNJ Citizens.** Aucun changement de schéma SQL
(la table `npc_citizens_bindings` de la migration V12 est réutilisée), de commande en jeu, de
contenu YAML.

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse `npc.citizens.list` /
  `npc.citizens.link`).
- Redémarrage serveur (RCON `stop` → relance auto VeryGames).
- Aucun autre fichier. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- `plugins/RPGQuest/data.db` par précaution (les liaisons créées ensuite via `npc.citizens.link`
  écrivent dans `npc_citizens_bindings` — un `INSERT` non destructif).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- Action `npc.citizens.list` → **SUCCESS** ; `details.citizens[]` peuplé, `availableForBinding`
  cohérent avec les liaisons existantes.
- Action `npc.citizens.link` sur une définition **NOT_LINKED** + un PNJ Citizens **libre** →
  **SUCCESS** `LINKED` ; un `npc.list` suivant montre `state: LINKED`.
- Action `npc.citizens.link` sur un PNJ Citizens **déjà lié** → **FAILED** `CITIZENS_TAKEN`.
- `npc.list` toujours **SUCCESS**.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Une liaison créée se défait par `DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?`
  (ou `/rpgadmin npc untag` en visant l'entité) — hors périmètre de ce lot.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~13:19–13:25 UTC). Branche `feat/control-panel-admin-tools` @ `3d1f50c`.

- **AWS / PlugAdmin** : `scripts/plugadmin/deploy.sh` — OK (release `/opt/plugadmin/releases/20260908-131859`,
  JAR `control-panel-0.1.0-SNAPSHOT.jar` SHA-256 `a52ad4d03e7752483be13a33e811ca094d115d8d2cc3ac303988313e16258f11`).
  `/health` public **ONLINE** ; `dig.lodygames.com` / `lodylands.com` inchangés (200).
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar`
  1331243 o, SHA-256 `342bf808cf1b4d1bdba0981dc2c0044190ca577f38176730b699d1b08c39b165`.
  Backup auto de l'ancien JAR : `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T131925Z-predeploy.jar`.
- `scripts/verygames-restart.sh` — `stop` RCON → OFFLINE → relance auto → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` tous verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`.
- Heartbeat agent reçu `2026-09-08T13:24:04Z` (`ONLINE`, plugin `0.1.0-SNAPSHOT`) ;
  aucun `ERROR` / `WARN` dans `journalctl -u plugadmin` (fenêtre du déploiement).
- `npc.citizens.list` → **SUCCESS** : 7 PNJ Citizens (`#0..#6`), `citizensAvailable:true`,
  chaque entrée `{numericId, uuid, name, linkedNpcId, availableForBinding, spawned}`, **aucune
  position ni monde**. Les 7 Citizens DEV sont **déjà liés** (`/rpgadmin npc tag` antérieur) —
  `available: 0`. Seul `guard` possède aussi une définition logique ; `guide/help/jeff/jo/junior/libraire`
  restent des `CITIZENS_ORPHAN` (binding sans définition) — non détournés.
- `npc.list` → **SUCCESS** (8 PNJ, 7 avec avertissement) — inchangé.
- `npc.citizens.link` — cas exercés en direct (aucun binding créé) :
  - `woodcutter_bob` → Citizens `#6` (déjà lié à `guard`) → **FAILED** `CITIZENS_TAKEN`
    (« Citizens #6 est déjà lié à npc_id=guard. Aucune réaffectation dans cette phase. »).
  - `guard` → Citizens `#6` (liaison identique) → **SUCCESS** `NOOP`
    (« Citizens #6 est déjà lié à « guard » — rien à faire. »).
  - `does_not_exist_xyz` → `#6` → **FAILED** `UNKNOWN_NPC`.
  - `woodcutter_bob` → Citizens `#9999` → **FAILED** `UNKNOWN_CITIZENS`.
- **Chemin nominal `INSERT` / `LINKED` non exerçable en direct** : aucun PNJ Citizens libre sur
  DEV et consigne de ne pas en créer / ne pas détourner un PNJ existant. Couvert par les tests
  automatisés (`CitizensBindPlannerTest`, `NpcIdentityServiceTest#bindCitizens*`,
  `NpcBindingRepositoryTest#insertIfAbsent*`, `NpcCitizensPayloadTest`, `AgentActionExecutorTest`,
  `NpcsCatalogTest`).
- Aucun PNJ Citizens créé ni supprimé ; aucune progression joueur touchée ; aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1325_npc-citizens-link-81.md`.

## 2026-09-08 - Spawn d'un PNJ Citizens depuis une définition — issue #81 (phase 2)

### Changement

Nouvelle action agent **`npc.citizens.create`** (mutation whitelistée `AgentActionType` ↔
`AgentActionCatalog`, **permission dédiée `NPC_SPAWN_WRITE`**, `confirm` obligatoire, audit,
validation 3 couches). Crée **physiquement** un PNJ Citizens (`EntityType.PLAYER`) à partir d'une
`NpcDefinition` existante — nom = `displayName` — puis le lie immédiatement à son `npc_id`.

- Paramètres **métier uniquement** : `npc_id`, `world`, `x`/`y`/`z`, `yaw`?/`pitch`? (défaut `0`).
  Le navigateur n'envoie jamais de nom Citizens libre, d'UUID, de commande console, de YAML ni de
  chemin.
- `CitizensSpawnPlanner` (pur, sans Bukkit) vérifie **toutes** les préconditions logiques avant la
  moindre création : Citizens actif (`CITIZENS_UNAVAILABLE`), définition présente (`UNKNOWN_NPC`)
  et `enabled` (`NPC_DISABLED`), `npc_id` pas déjà lié (`NPC_ALREADY_LINKED`), `world` dans la
  **liste blanche RPGQuest** (hub / claims / exploration de la config — `UNKNOWN_WORLD`), position
  **finie** et bornée (`|x|,|z| ≤ 29 999 984`, `-2048 ≤ y ≤ 2048`, `-90 ≤ pitch ≤ 90` —
  `INVALID_POSITION`, jamais « corrigée »).
- Thread principal : le monde doit être **chargé** et `y` dans ses limites réelles, puis
  `createNPC` + `npc.spawn(loc, CREATE)` (`CREATE_FAILED` si Citizens refuse).
- `CitizensSpawnCoordinator` (pur) orchestre `create → bind → success` ; si la liaison échoue
  **après** création, `destroyCitizensNpc(numericId, uuid)` supprime **le seul PNJ créé par cette
  action** (double clé — jamais un PNJ préexistant) → `BIND_FAILED_ROLLED_BACK`.
- Persistance du binding : `NpcIdentityService.bindCitizens` (réutilise `CitizensBindPlanner` +
  `NpcBindingRepository.insertIfAbsent`, anti-collision phase 1) ; cache d'identification rafraîchi
  → identification en jeu effective **sans redémarrage**.

**Aucune migration SQL** (table `npc_citizens_bindings` de la V12 réutilisée). Aucune commande en
jeu, aucun contenu YAML modifié. Aucune suppression générale de PNJ Citizens (seul le rollback
interne supprime — une action `npc.citizens.delete` éventuelle serait un chantier séparé).

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse `npc.citizens.create`).
- Redémarrage serveur (RCON `stop` → relance auto VeryGames).
- Aucun autre fichier. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- `plugins/RPGQuest/data.db` par précaution (un spawn réussi écrit une ligne
  `npc_citizens_bindings` — un `INSERT` non destructif). Les fichiers Citizens
  (`plugins/Citizens/saves.yml` ou base) évoluent aussi dès qu'un PNJ est réellement créé.

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest **et Citizens** en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `npc.list` et `npc.citizens.list` toujours **SUCCESS**.
- `npc.citizens.create` **sans position sûre explicite = non exécuté en réel** (consigne : ne pas
  faire apparaître un PNJ n'importe où sur DEV). Validé sur DEV **sans spawn réel** : refus
  `NPC_ALREADY_LINKED` / `UNKNOWN_WORLD` / `INVALID_POSITION` / `UNKNOWN_NPC`. Le chemin nominal
  `CREATED` est couvert par les tests automatisés.
- Premier spawn réel : `PENDING MANUAL VALIDATION` (owner, position explicite fournie).

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Un PNJ Citizens réellement créé se retire par `/npc select <id>` + `/npc remove` **puis**
  `DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?` (ou `/rpgadmin npc untag` en visant
  l'entité) — hors périmètre de ce lot.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~14:22–14:45 UTC). Branche `feat/control-panel-admin-tools`.

- **AWS / PlugAdmin** : `scripts/plugadmin/deploy.sh` — OK (release
  `/opt/plugadmin/releases/20260908-143939`, `control-panel-0.1.0-SNAPSHOT.jar` SHA-256
  `bff44c5b08c86a89b6afd989db4a2589e5e2a40edcb232e677244c9db33bec65`). `NPC_SPAWN_WRITE` +
  `npc.citizens.create` présents dans le JAR. `/health` public **ONLINE** ; `dig.lodygames.com` /
  `lodylands.com` inchangés (200).
- **VeryGames DEV — 1re passe (`e56930c`)** : `deploy-verygames.sh -y` + `verygames-restart.sh`.
  Validation live : `npc.citizens.create` échouait en **`NullPointerException`** sur tous les
  chemins de refus (`AgentActionOutcome` recopie ses détails via `Map.copyOf`, qui refuse les
  valeurs `null` ; `citizens_id` était `null` avant création).
- **Correctif `b9f3beb`** (`citizens_id` → `-1` quand aucun PNJ Citizens ; test de régression) —
  `./gradlew build` vert.
- **VeryGames DEV — 2e passe (`b9f3beb`)** : `deploy-verygames.sh -y` — JAR
  `rpgquest-0.1.0-SNAPSHOT.jar` 1 349 308 o, SHA-256
  `6a7ff0d4b2147f8153c05bed954a93df35f0cb6cf60033152840b7f31f4d94a9` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T144029Z-predeploy.jar`.
- `scripts/verygames-restart.sh` — `stop` RCON → OFFLINE → relance auto → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent `2026-09-08T14:41:43Z` (`ONLINE`,
  `0.1.0-SNAPSHOT`) ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `npc.list` → **SUCCESS** (8 PNJ) ; `npc.citizens.list` → **SUCCESS** (7 Citizens, 0 libre) —
  inchangés.
- `npc.citizens.create` — **aucun spawn réel** (consigne : pas de position sûre explicite sur
  DEV) :
  - `guard` (déjà lié à Citizens #6) → **FAILED `NPC_ALREADY_LINKED`** ;
  - `woodcutter_bob` → `the_nether` → **FAILED `UNKNOWN_WORLD`** (message : « hors de la liste
    blanche RPGQuest (claims, wild, world_hub) ») ;
  - `woodcutter_bob` → `world_hub`, `y=99999` → **FAILED `INVALID_POSITION`** ;
  - `does_not_exist_xyz` → **FAILED `UNKNOWN_NPC`** ; `details.citizens_id = -1` (plus de NPE) ;
  - `woodcutter_bob` → `world_hub`, `x=NaN` → **REJECTED** (`AgentActionExecutor`, avant la
    couche métier).
- **Chemin nominal `CREATED` non exercé en direct** — couvert par les tests automatisés.
- Aucun PNJ Citizens créé ni supprimé ; aucune progression joueur touchée ; aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1423_npc-citizens-create-81-phase2.md`.

## 2026-09-08 - Page /dialogues V1 : lecture structurée + squelette — action agent dialogue.list / dialogue.definition.create

### Changement

Nouveau canal agent **lecture** `dialogue.list` (permission dédiée `DIALOGUE_READ`) et **écriture**
`dialogue.definition.create` (permission dédiée `DIALOGUE_WRITE`, `confirm` obligatoire, audit).

- `dialogue.list` : `DialogueCatalog` (pur, sans Bukkit) dérive de `YamlDialogueEngine.dialogues()`
  + `lastReport().issues()` + `YamlNpcEngine` + `YamlQuestEngine` un catalogue structuré — par
  dialogue : nœuds ordonnés (départ d'abord) avec `reachable` (BFS des `next`), choix avec
  **actions et conditions typées** `{kind, target, value, raw}`, relations PNJ (convention
  `rpgquest:<id>` + `NpcDefinition.dialogue`), quêtes référencées/démarrées, warnings de
  cohérence. Les fichiers rejetés au chargement (dialogue sans nœud, `start` invalide, cycle
  `OPEN_DIALOGUE`, id dupliqué) sont remontés à part (`loadIssues[]`), jamais dans la liste des
  dialogues.
- `dialogue.definition.create` : crée un **squelette** `dialogues/<key>.yml` (un nœud `start`,
  un choix « fermer »). `DialogueDraft` → `DialogueDefinitionYaml.render` (déterministe) →
  `DialogueDefinitionStore.create` (écriture atomique tmp + `ATOMIC_MOVE`, **refus d'écrasement**,
  rechargement complet du dossier — fichier supprimé si le nouveau dialogue ne se recharge pas).
  Jamais de YAML brut, jamais de chemin. Après succès : `YamlDialogueEngine.reload()` (le
  dialogue devient immédiatement ouvrable en jeu — l'ouverture se fait par convention
  `rpgquest:<id>`).

**Aucune migration SQL. Aucun contenu de dialogue existant modifié.** L'édition fine des
nœuds/choix/actions viendra avec un futur éditeur.

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse `dialogue.list` /
  `dialogue.definition.create`).
- Redémarrage serveur (RCON `stop` → relance auto VeryGames).
- Aucun autre fichier. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- `plugins/RPGQuest/dialogues/` par précaution (une création via `dialogue.definition.create`
  ajoute un fichier `<key>.yml` — jamais d'écrasement).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `dialogue.list` → **SUCCESS** ; `details.dialogues[]` peuplé (dialogues livrés : `guard`,
  `guide`, `jo`, `libraire`, `merchant`), `loadIssues` cohérent, actions typées présentes.
- `npc.list` toujours **SUCCESS**.
- `dialogue.definition.create` : **non exécuté en réel** pour ne pas ajouter de contenu de
  dialogue à DEV ; validé sur fixture / tests (round-trip parse, refus d'écrasement).

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Un squelette créé se retire en supprimant `plugins/RPGQuest/dialogues/<key>.yml` puis
  `quest admin reload` / redémarrage.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~15:16–15:22 UTC). Branche `feat/control-panel-admin-tools` @ `1fa7f4a`.

- **AWS / PlugAdmin** : `scripts/plugadmin/deploy.sh` — OK (release
  `/opt/plugadmin/releases/20260908-151618`, `control-panel-0.1.0-SNAPSHOT.jar` SHA-256
  `3c873e538a01c88ba4bf5a1a4dfea46840e9ce6c589b9b2888b576bfbfc259e9`). `DIALOGUE_READ` /
  `DIALOGUE_WRITE` + `dialogue.list` / `dialogue.definition.create` présents dans le JAR.
  `/health` public **ONLINE** ; `/dialogues` anon → 303 ; `dig.lodygames.com` /
  `lodylands.com` inchangés (200).
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar`
  1 403 924 o, SHA-256 `57bda61b54ea726e957a4d37079cd4aa85644e73a865c31d027c5472c2733b42` ;
  backup auto `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T151640Z-predeploy.jar`.
  `scripts/verygames-restart.sh` → `stop` RCON → OFFLINE → relance auto → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent `2026-09-08T15:19:17Z` (`ONLINE`,
  `0.1.0-SNAPSHOT`) ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `dialogue.list` → **SUCCESS** : « 7 dialogue(s) (4 avec avertissement, 14 fichier(s)
  rejeté(s)) », `nodeTotal=22`.
  - 7 dialogues chargés (`guide`, `help`, `jeff`, `jo`, `guard`, `junior`, `libraire`).
  - Actions/conditions **typées** présentes (`START_QUEST`, `RUN_SAFE_COMMAND`, `CLOSE`,
    `QUEST_STATE`, `VARIABLE_EQUALS`, `NO_MAIN_CLAIM`, `LACKS_CUSTOM_ITEM`).
  - Relations PNJ : `guard` / `junior` / `libraire` liés (ids canoniques) ;
    `guide` / `help` / `jeff` / `jo` non liés → warning `DIALOGUE_NO_NPC` (PNJ Citizens sans
    `NpcDefinition`, cohérent avec #81 phase 1).
  - `loadIssues` (14) : 7 fixtures `test_*.yml` **préexistantes** (`test_break_block`, …,
    `test_talk_to_npc`), chacune « `start` obligatoire » + « `nodes` obligatoire ». Correctement
    rejetées et absentes de la liste des dialogues — la page se rend quand même. Non touchées
    par cette tâche. `declaredButMissing: []`.
- `npc.list` → toujours **SUCCESS** (8 PNJ).
- `dialogue.definition.create` **non exécuté en réel** (consigne : ne pas ajouter de contenu de
  dialogue à DEV) — couvert par les tests.
- Aucun dialogue existant modifié ; aucune progression joueur touchée ; aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1510_dialogues-page-v1.md`.

---

## 2026-09-08 - Nettoyage fixtures test_*.yml du dossier dialogues DEV (#83)

### Changement

**Contenu DEV uniquement — aucun code, aucun JAR, aucune migration.**

Sept fichiers `test_*.yml` (`test_break_block`, `test_collect_item`, `test_craft_item`,
`test_kill_entity`, `test_place_block`, `test_reach_location`, `test_talk_to_npc`) avaient été
copiés à tort dans `plugins/RPGQuest/dialogues/` du serveur **DEV**. Ce sont des **fixtures de
quête de test manuel** (déjà versionnées dans le dépôt à `docs/manual-tests/quests/test_*.yml`,
emplacement non chargé par `YamlDialogueEngine`), structure de **quête**, pas de dialogue → le
loader de dialogues les rejette correctement (« `start` obligatoire » + « `nodes` obligatoire »
= 14 `loadIssues`). Contenu distant comparé octet à octet aux fixtures du dépôt : identique 7/7.

Retrait des 7 fichiers du dossier actif `dialogues/`. Le **loader n'est pas modifié** : aucun
filtre `test_*`, aucun `loadIssue` masqué — tout YAML invalide réellement présent continuera
d'être signalé.

`plugins/RPGQuest/quests/` **non touché** : il contient légitimement 3 fixtures `test_*.yml`
(scénario `story_test` du `MANUAL_TEST_PLAN`, dossier lu par le moteur de quêtes).

### Action serveur

- Suppression FTP de `plugins/RPGQuest/dialogues/{test_break_block,test_collect_item,test_craft_item,test_kill_entity,test_place_block,test_reach_location,test_talk_to_npc}.yml`.
- **Aucun remplacement de JAR** (aucun code modifié).
- Redémarrage serveur (`scripts/verygames-restart.sh` — `stop` RCON → relance auto) : seul
  mécanisme qui recharge le dossier `dialogues/` (pas de reload à chaud dédié).
- Aucun autre fichier. Aucune migration. Control Panel AWS non redéployé.

### Sauvegarde préalable

Téléchargement FTPS des 7 fichiers avant retrait →
`~/.local/share/rpgquest/verygames-backups/issue-83-dialogues-20260908T154228Z/`
(tailles + SHA-256 dans le rapport).

### Déploiement

1. Backup FTPS des 7 `test_*.yml`.
2. `DELE` FTP des 7 fichiers dans `RPGQuest/dialogues/` (garde-fou : le nom doit matcher
   `test_*.yml`).
3. `scripts/verygames-restart.sh`.

### Validation

Session du 2026-09-08 (~15:42–15:47 UTC). Branche `feat/control-panel-admin-tools` @ `ceddc30`.

- `RPGQuest/dialogues/` après retrait : `guard.yml guide.yml help.yml jeff.yml jo.yml
  junior.yml libraire.yml` (7 dialogues gameplay, 0 `test_*.yml`).
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent `2026-09-08T15:46:54Z` (`ONLINE`,
  `0.1.0-SNAPSHOT`) ; **0 `ERROR`** dans `journalctl -u plugadmin`.
- `dialogue.list` (action agent injectée) → **SUCCESS**, `value=7` : « 7 dialogue(s)
  (4 avec avertissement, **0 fichier(s) rejeté(s)**) », `nodeTotal=22`.
  - **`loadIssues` : 0** (était 14) — les 14 erreurs des `test_*.yml` ont disparu.
  - 7 dialogues gameplay chargés (`guide`, `help`, `jeff`, `jo`, `guard`, `junior`,
    `libraire`) ; 4 × `DIALOGUE_NO_NPC` **préexistants** (PNJ Citizens sans `NpcDefinition`,
    cohérent avec #81 phase 1) ; **aucun nouveau warning**. `declaredButMissing: []`.
- `npc.list` → toujours **SUCCESS** (8 PNJ) — inchangé.
- Vue navigateur authentifiée `/dialogues` : `PENDING MANUAL VALIDATION`.

### Rollback

Restaurer les 7 fichiers dans `plugins/RPGQuest/dialogues/` depuis
`~/.local/share/rpgquest/verygames-backups/issue-83-dialogues-20260908T154228Z/` (upload FTPS),
puis `scripts/verygames-restart.sh`. Aucune migration à défaire, aucun JAR à restaurer.

Rapport : `docs/claude-reports/2026-09-08_1547_nettoyage-fixtures-test-dialogues-dev-83.md`.

---

## 2026-09-08 - Éditeur guidé /dialogues — phase 1 (#82) : dialogue.node.* / dialogue.choice.*

### Changement

Cinq nouvelles mutations agent (`DIALOGUE_WRITE`, `confirm` obligatoire côté panel, audit,
whitelist `AgentActionType` ↔ `AgentActionCatalog`) pour **éditer un dialogue déjà chargé** —
périmètre restreint et sûr :

- `dialogue.node.update` — locuteur + texte d'un nœud (choix / conditions / actions conservés) ;
- `dialogue.node.create` — nœud simple (locuteur + texte + un choix « fermer »), **orphelin** ;
- `dialogue.choice.add` / `dialogue.choice.update` / `dialogue.choice.delete` — **choix simple**
  uniquement (aucune condition, aucune action hors `CLOSE`) : redirige vers un nœud existant
  **ou** ferme le dialogue ; `delete` refuse le dernier choix d'un nœud.

`DialogueDefinitionEditor` (nouveau) : localise le fichier par son `id`, refuse un fichier déjà
invalide, applique la mutation sur le modèle métier, **re-sérialise le dialogue complet**
(`DialogueDefinitionWriter` — 10 actions + 8 conditions + négation, aucune perte), **re-parse en
mémoire + exige l'égalité sémantique** (garde-fou round-trip), **écrit atomiquement**, **recharge
tout le dossier** puis **restaure le contenu d'origine** si le fichier ne recharge pas. Le
fichier édité adopte le **format canonique** du panel (commentaires / mise en forme d'origine
non conservés — choix assumé ; l'intégrité fonctionnelle est garantie par le round-trip).

Page `/dialogues` **refondue** (4 blocs : en-tête + état, résumé, diagnostics triés
erreur→attention→info, graphe de cartes nœud) avec l'édition guidée en `<details>` semi-inline
par nœud (aucun JS, conforme CSP). **Aucune migration SQL. `data.db` jamais touché.**

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse les 5 nouveaux types).
- Redémarrage serveur (`scripts/verygames-restart.sh` — `stop` RCON → relance auto).
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).
- Aucun autre fichier. Aucune migration.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- **Recommandé avant toute mutation réelle** : sauvegarde FTPS du dossier
  `plugins/RPGQuest/dialogues/` (l'éditeur restaure le contenu d'origine en cas d'échec de
  rechargement, mais un backup hors-ligne reste la garantie).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `dialogue.list` → **SUCCESS** ; `npc.list` toujours **SUCCESS**.
- Si exécutée : une mutation `dialogue.node.update` / `dialogue.choice.add` sur un **dialogue de
  test sûr** (jamais un dialogue gameplay pour la seule démonstration) → **SUCCESS**, dialogue
  toujours chargé, aucune régression sur les autres dialogues.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`. Un
  dialogue édité en réel : restaurer son `.yml` depuis le backup puis redémarrage.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~16:52–17:08 UTC). Branche `feat/control-panel-admin-tools` @ `171849b`.

- **AWS** : `scripts/plugadmin/deploy.sh` — OK (release
  `/opt/plugadmin/releases/20260908-165221`). `/health` public **ONLINE** ×3 ; `/dialogues`
  anon → **303** ; `dig.lodygames.com` / `lodylands.com` → **200**. Les 5 nouveaux types
  présents dans `AgentActionCatalog.class` du JAR déployé.
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar`
  1 427 111 o, SHA-256
  `12f66391be6ca65fc694095b21cc3a603c779187026ecde189392e6b61b3788d` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T165252Z-predeploy.jar`
  (SHA-256 `57bda61b54ea726e957a4d37079cd4aa85644e73a865c31d027c5472c2733b42`).
  `scripts/verygames-restart.sh` → OFFLINE → relance auto → **ONLINE** ; `/plugins` (RCON) :
  `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ; heartbeat agent **ONLINE**.
- Baseline : `dialogue.list` → **SUCCESS** (7 dialogues, `nodeTotal=22`, `loadIssues=0`) ;
  `npc.list` → **SUCCESS** (8 PNJ).
- **Mutations exercées en réel** sur un dialogue de test sûr `rpgquest:panel_edit_probe` (créé
  via `dialogue.definition.create`, jamais un dialogue gameplay) : `dialogue.node.update`,
  `dialogue.node.create`, `dialogue.choice.add`, `dialogue.choice.update`,
  `dialogue.choice.delete` → **tous SUCCESS `UPDATED`**. Garde-fous : suppression du dernier
  choix d'un nœud → **FAILED `LAST_CHOICE`** ; `node.update` sur un nœud inexistant → **FAILED
  `UNKNOWN_NODE`**.
- `dialogue.list` après édition : **SUCCESS**, `loadIssues=0`, les **7 dialogues gameplay
  inchangés** (compteurs + warnings identiques à la baseline).
- **Nettoyage** : `panel_edit_probe.yml` supprimé de `plugins/RPGQuest/dialogues/` via FTP.
  `verygames-restart.sh` : VeryGames a tardé ~6 min à relancer (aléa hébergeur, > délai 180 s du
  script), serveur **revenu de lui-même à 17:06 UTC**. État final : `/plugins` verts,
  `dialogue.list` → **SUCCESS** 7 dialogues `loadIssues=0`, `npc.list` → **SUCCESS** 8 PNJ,
  `journalctl -u plugadmin` **0 `ERROR`**. DEV revenu à son état d'avant-tâche + nouveau JAR.
- Vue navigateur `/dialogues` authentifiée (rendu 4 blocs, formulaires d'édition) =
  `PENDING MANUAL VALIDATION`.

Rappel : `dialogue.list` reste **SUCCESS**, `npc.list` **inchangé**, aucune progression joueur
touchée, aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1642_dialogues-editeur-guide-phase1-82.md`.

---

## 2026-09-08 - #87 : reconnexion depuis le Wild ne renvoie plus au Hub

### Changement

**Code plugin uniquement. Aucune migration. `data.db` / `config.yml` / `spawn.yml` / mondes non
touchés.**

Un joueur non-OP déconnecté dans `wild` se reconnectait au spawn du village (`world_hub`).
**Cause** : `SpawnPlayerListener` traitait comme « nouveau joueur » tout joueur dont
`Player#hasPlayedBefore()` renvoie `false`, puis `SpawnService` redirigeait sa position
d'arrivée vers `spawn.yml`. Or `hasPlayedBefore()` renvoie `false` à tort pour un joueur déjà
venu quand la métadonnée Bukkit `bukkit.firstPlayed` de son `playerdata` manque
(migration/transfert de serveur, UUID hors-ligne↔en-ligne, restauration de sauvegarde).

**Correction** : `spawn.JoinSpawnPolicy` (règle pure) ne redirige vers le village que si un
nouveau joueur (`!hasPlayedBefore()`) est placé par Paper dans le **monde principal**
(`getWorlds().get(0)`) **ou** le **monde Hub** (`hub.world`). Sinon la position restaurée par
Paper est conservée — une reconnexion depuis `wild` reste dans `wild`. Onboarding et repli
« monde disparu » (Paper renvoie au monde principal → redirection village) inchangés. Respawn
après mort (`SpawnService.handleRespawn`) inchangé. Nouveau log par connexion
`join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION|REDIRECT_TO_VILLAGE_SPAWN`.

### Action serveur

- **Remplacement du seul JAR RPGQuest**.
- **Un seul** redémarrage (`scripts/verygames-restart.sh`) — ne pas enchaîner (protection
  anti-boucle VeryGames : 10 auto-reboots en 30 min → relance manuelle panel).
- Aucun autre fichier. Aucune migration. Control Panel AWS non concerné.

### Sauvegarde préalable

Ancien JAR sauvegardé automatiquement par `deploy-verygames.sh`.

### Déploiement

1. `scripts/deploy-verygames.sh -y` puis `scripts/verygames-restart.sh` (une fois).

### Validation

- `/plugins` (RCON) : RPGQuest vert ; heartbeat agent `ONLINE` ; `dialogue.list` / `npc.list`
  toujours `SUCCESS`.
- **Manuel (owner, client réel)** : `LoDyMcFly` non-OP entre dans le Wild, relève sa position,
  se déconnecte/reconnecte ×2 → `world` toujours `wild`, position identique ou très proche,
  aucun passage Hub. Non-régression : déconnexion dans le Hub → retour Hub ; nouveau compte →
  spawn du village.

### Rollback

`scripts/rollback-verygames.sh --latest` puis un seul `scripts/verygames-restart.sh`. Aucune
migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~19:14–19:18 UTC). Branche `feat/control-panel-admin-tools` @ `4006095`.

- `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar` 1 429 560 o, SHA-256
  `d9a47cf868991dae8f6a072856f1f0519e87fd201cfc63388c486e4b0ba9f7ee` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T191434Z-predeploy.jar`
  (SHA-256 `12f66391…`).
- `scripts/verygames-restart.sh` **une fois** → OFFLINE → relance auto → **ONLINE** (aucune
  protection anti-boucle déclenchée ; serveur stable depuis ~2 h avant le restart). `/plugins`
  RPGQuest + Citizens + Multiverse + WorldEdit **verts** ; `rpgquest version` → `v0.1.0-SNAPSHOT` ;
  heartbeat agent **ONLINE** ; mondes `world_hub` / `claims` / `wild` `loaded:true`.
- Non-régression : `dialogue.list` → **SUCCESS** (7) ; `npc.list` → **SUCCESS** (8) ;
  `journalctl -u plugadmin` **0 `ERROR`**.
- **Test manuel en jeu #87** (owner, non-OP) : `PENDING MANUAL VALIDATION` — entrer dans le Wild,
  relever la position, se déconnecter/reconnecter ×2 → `world` doit rester `wild`, position
  identique ou très proche, aucun passage Hub ; log serveur `join_restore … world=wild
  action=KEEP_LAST_LOCATION`. Non-régression à confirmer : déconnexion dans le Hub → retour Hub ;
  nouveau compte → spawn du village.

Rapport : `docs/claude-reports/2026-09-08_1905_bug-wild-reconnexion-hub-87.md`.
