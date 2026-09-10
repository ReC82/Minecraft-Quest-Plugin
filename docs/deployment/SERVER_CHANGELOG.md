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

---

## 2026-09-08 - Control Panel : centre de documentation /docs (#49) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Nouvelle section **Documentation** dans PlugAdmin : route `/docs` (authentifiée, permission
`DOCS_READ` accordée à tous les rôles), recherche plein texte en mémoire, rendu Markdown sûr,
catégories, commandes copiables. Contenu = 9 fiches Markdown versionnées et livrées dans le jar
(`control-panel/src/main/resources/docs/`), listées par `_index.txt` (liste blanche). Aucun
contenu en base ; aucun chemin du navigateur ouvert (slug interne résolu en mémoire).

### Action serveur

- `scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
- Aucune autre action.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` public **ONLINE**.
- `/docs` **anonyme** → **303** vers `/login` ; `/docs/<slug>` anonyme → **303**.
- `/docs` **authentifié** → 200 (recherche + catégories) ; `/docs?q=tag+npc` → fiche PNJ.
- Autres vhosts nginx (`dig.lodygames.com`, `lodylands.com`) → **200**.
- `journalctl -u plugadmin` : aucun `ERROR`.

### Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration.

### Exécution réelle

Session du 2026-09-08 (~20:10 UTC). Branche `feat/control-panel-admin-tools` @ `0516a0b`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260908-201035`). Jar : 9 `docs/*.md` +
`_index.txt` embarqués. `/health` ONLINE ×3 ; `/docs` anon → **303**, `/docs/pnj-citizens` anon
→ **303** ; `dig` / `lodylands` → **200** ; `journalctl -u plugadmin` **0 `ERROR`**. Navigateur
authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-08_2003_control-panel-centre-documentation-49.md`.

## 2026-09-09 - Control Panel : refonte UX généralisée /quests /stories /dialogues + diagnostics humains (#89 / #49) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

La philosophie de `/npcs` (liste = synthèse, clic = détail, clic action = formulaire) est
appliquée à `/quests`, `/stories`, `/dialogues` : chaque liste devient un accordéon Bootstrap,
le détail est en sections repliées, le graphe des dialogues n'est plus ouvert d'office.
Toolbars de rafraîchissement compactes (`btn-sm`) et recherche `input-group` partout.
`/players` : toolbar compacte + recherche au-delà de 6 joueurs.

Nouveau registre **`DiagnosticHelp`** : chaque avertissement/erreur (codes du moteur PNJ et
dialogues + vérifications de référence calculées côté panel — prérequis inconnu, donneur sans
fiche, quête absente d'une chaîne, fichier de dialogue rejeté, dialogue déclaré absent) est
rendu en français clair (problème → conséquence → action) avec un lien vers une ancre précise
du centre de documentation et le code technique en secondaire. Quatre nouvelles fiches
`/docs` : `pnj-depannage`, `dialogues-depannage`, `quetes-depannage`, `stories-depannage`
(embarquées dans le jar, whitelist `_index.txt`). `Markdown.slug` replie désormais les accents.

### Action serveur

- `scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
- Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : l'app précédente est déplacée sous
`/opt/plugadmin/releases/<horodatage>` (rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE**.
- `/npcs` `/dialogues` `/quests` `/stories` `/docs` `/players` anonymes → **303** vers `/login`.
- En-tête **CSP inchangé** (`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'
  data:; form-action 'self'; frame-ancestors 'none'`).
- `/assets/plugadmin.css` → **200** (nouveau hash `?v=…`).
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; `journalctl -u plugadmin` : **aucun `ERROR`**.
- Jar déployé : `DiagnosticHelp.class` + 4 `docs/*-depannage.md` + `_index.txt` embarqués.
- Rendu **authentifié** des accordéons /quests /stories /dialogues : `PENDING MANUAL VALIDATION`
  (couvert par la suite de tests `:control-panel:test`).

### Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~09:53 UTC). Branche `feat/control-panel-admin-tools` @ `6835c15`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-095349`). `/health` ONLINE (local +
`plugadmin.lodylands.com`) ; 6 routes → **303** ; CSP inchangé ; `plugadmin.css?v=bedb0b2d`
→ 200 ; `dig` / `lodylands` → 200 ; `plugadmin.service` `active` `NRestarts=0` ;
`journalctl -u plugadmin` **0 `ERROR`**. Navigateur authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_0930_control-panel-ux-metier-diagnostics-89-49.md`.

## 2026-09-09 - Control Panel : polish #49/#89 (titres docs en double, bouton Copier, CTA dupliqué) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Correctifs visuels après validation de la nouvelle UX :

- **#49 — titre en double sur les fiches `/docs`** : `DocsPages` rend déjà le titre de fiche dans
  son propre `<h1>` ; `Markdown.render` ré-affichait le `# …` d'ouverture du corps. Nouveau
  `Markdown.stripLeadingH1()` : retire uniquement le premier titre s'il est de niveau 1 et en
  tête ; `##`/`###` et un éventuel second `#` plus bas sont conservés. Vérifié sur les 13 fiches.
- **#49 — bloc de code / bouton « Copier »** : le grand rectangle sombre vide venait d'un
  `<svg class="ic"><use href="#i-copy">` (sprite retiré à la migration Bootstrap #92) sans
  contenu, taille intrinsèque 300×150 dans le bouton. Bouton « Copier » = texte seul, compact,
  en tête du wrapper `.doc-cmd`. CSS : `.doc-copy` en `position:absolute` coin haut-droit,
  `.doc-cmd pre` garde `overflow-x:auto` + gouttière droite pour ne jamais passer sous le
  bouton ; ancienne règle de positionnement en double retirée.
- **#89 — action « Créer la définition » affichée deux fois** dans le détail d'un PNJ (diagnostic
  + section Actions) : règle CTA — une action déjà proposée par un « Corriger maintenant » de
  diagnostic n'est plus répétée dans « Actions » ; la section « Actions » disparaît si elle
  devient vide. Les formulaires masqués restent rendus (le bouton du diagnostic les ouvre).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente déplacée sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE**.
- `/docs` `/docs/pnj-citizens` `/npcs` `/quests` `/stories` `/dialogues` anonymes → **303** vers
  `/login`.
- En-tête **CSP inchangé**.
- CSS déployé : `.doc-copy{position:absolute;top:7px;right:7px;z-index:2;…}`,
  `.doc-cmd pre{…padding:14px 72px 14px 16px;overflow-x:auto}`, ancienne règle
  `.codeblock .doc-copy,.doc-cmd .doc-copy` **retirée**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** à un build frais
  (SHA-256 `955d5055…`).
- Rendu **authentifié** des fiches `/docs` (titre unique, bloc code propre) et du détail PNJ
  Guide (un seul « Créer la définition ») : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-101733`).
Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~10:17 UTC). Branche `feat/control-panel-admin-tools` @ `65cfb9a`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-101733`). `/health` ONLINE (local +
public) ; 6 routes → **303** ; CSP inchangé ; CSS `.doc-copy`/`.doc-cmd pre` corrigés servis,
ancienne règle absente ; `dig` / `lodylands` → 200 ; `plugadmin.service` `active` `NRestarts=0` ;
jar byte-identique au build (`955d5055…`). Un `WARNING event=handler_error path=/login
java.io.IOException: stream closed` observé = déconnexion client d'un `curl -I` (HEAD), bénin,
non lié au changement. Navigateur authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_1005_polish-docs-titres-codeblock-cta-49-89.md`.

## 2026-09-09 - Control Panel : fiche /docs des commandes réécrite (une section par commande) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

`control-panel/src/main/resources/docs/rpgquest-commandes.md` est réécrite : le gros bloc
fourre-tout (plusieurs familles de commandes derrière un seul bouton « Copier ») est remplacé
par une fiche structurée — table « En un coup d'œil » (Je veux… → commande), catégories `##`
(Joueurs / PNJ / Quêtes / Stories / Mondes / Portails & Waystones / Administration serveur /
Outils builder & avancés), puis **une section `###` par commande** : nom humain d'abord,
description, Syntaxe, Paramètres, Exemple(s), Résultat attendu, Permission / Attention.

**Chaque bloc de code = une seule commande exécutable → son propre bouton « Copier »** (78
blocs, 78 boutons). Contenu audité sur le code réel (`RpgAdminCommand`, `RPGQuestCommand`,
`QuestCommand`, `DialogueCommand`, `CustomItemCommand`). CSS : sections `###` en cartes légères
(bord gauche accent) + mini-libellés discrets. **Aucune modification du renderer Markdown.**

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE**.
- `/docs` `/docs/rpgquest-commandes` `/npcs` `/docs?q=npc+tag` anonymes → **303** vers `/login`.
- En-tête **CSP inchangé**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** au build
  (SHA-256 `f25c5633…`) ; `docs/rpgquest-commandes.md` embarqué (156 marqueurs ``` = 78 blocs).
- `:control-panel:test` **221/0** (dont `CommandReferenceSheetTest`) ; `DocSearchIndexTest`
  inchangé (fiches de tête #49 conservées).
- Rendu navigateur **authentifié** de la fiche : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-111244`).
Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~11:12 UTC). Branche `feat/control-panel-admin-tools` @ `577c4cd`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-111244`). `/health` ONLINE (local +
public) ; routes → **303** ; CSP inchangé ; `dig` / `lodylands` → 200 ; `plugadmin.service`
`active` `NRestarts=0` ; jar byte-identique (`f25c5633…`) ; fiche embarquée (78 blocs). Un
`WARNING event=handler_error path=/login java.io.IOException: stream closed` = `curl -I` (HEAD)
client qui ferme, bénin, non lié au changement. Navigateur authentifié : `PENDING MANUAL
VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_1104_fiche-commandes-reference-49.md`.

## 2026-09-09 - Control Panel : dashboard d'observabilité / page /diagnostics (#38) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Nouvelle page **`/diagnostics`** : centralise tous les diagnostics de cohérence RPGQuest (PNJ,
dialogues, quêtes, stories, serveur/agent, mondes) en un seul endroit trié (erreurs d'abord),
avec pour chaque problème : titre humain, domaine + ressource, conséquence, action, boutons
**Ouvrir** (lien profond vers la page métier qui déplie l'élément) / **Corriger maintenant**
(action déjà offerte par le panel — aucune correction auto) / **Comment corriger ?** (ancre doc
précise), et le code technique en dernier.

Nouveau paquet `panel.diag` (modèle `DiagnosticEntry`, providers Npc/Dialogue/Quest/Story/Server,
`DiagnosticsService` qui agrège / dédoublonne / trie). Lecture seule du dernier snapshot agent —
**aucune requête déclenchée** au chargement. Bouton « Actualiser les diagnostics » →
`POST /diagnostics/refresh` (CSRF, `DIAGNOSTICS_READ`) qui enqueue en **une** action les 4
relevés `*.list` nécessaires ; feedback toast (#93). Tuile Home + section Dashboard mises à jour.
Nouvelle fiche `/docs/serveur-depannage` + section Citizens dans `/docs/pnj-depannage`.
`panel.js` : filtres à plusieurs groupes de puces combinables (rétro-compatible).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local + `https://plugadmin.lodylands.com/health` : **ONLINE**.
- `/home` `/dashboard` `/diagnostics` `/npcs` `/dialogues` `/quests` `/stories` `/docs`
  `/docs/serveur-depannage` anonymes → **303** vers `/login` ; `POST /diagnostics/refresh`
  anonyme → **303**.
- En-tête **CSP inchangé**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; **aucun `ERROR`** au journal.
- Jar déployé **byte-identique** au build (SHA-256 `c5897313…`) ; 17 classes `panel/diag/`
  + `serveur-depannage.md` embarqués.
- `:control-panel:test` **235/0** (`DiagnosticsServiceTest` + `DiagnosticsPageTest`) ;
  `:test` inchangé ; `./gradlew build` vert.
- Validation navigateur **authentifiée** (11 points de l'énoncé) : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-120245`).
Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~12:02 UTC). Branche `feat/control-panel-admin-tools` @ `6428f82`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-120245`). `/health` ONLINE (local +
public) ; routes → **303** ; `POST /diagnostics/refresh` anon → 303 ; CSP inchangé ; `dig` /
`lodylands` → 200 ; `plugadmin.service` `active` `NRestarts=0` ; **0 `ERROR`** ; jar
byte-identique (`c5897313…`) ; 17 classes `panel/diag/` + fiche `serveur-depannage.md`.
Navigateur authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_1139_dashboard-observabilite-diagnostics-38.md`.

---

## 2026-09-09 - Control Panel : fix /npcs — PNJ Citizens réel masqué (#101) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Un PNJ créé directement dans Citizens en jeu (`/npc create …`), sans fiche RPGQuest ni liaison,
n'apparaissait dans aucune ligne de la page **PNJ** : la liste ne venait que de `npc.list`
(`NpcCatalog` = définitions + liaisons + références de contenu), qui ne lit pas le registre
Citizens. La ligne de synthèse « Citizens : N » le comptait pourtant. Ni le registre Citizens
runtime, ni l'action agent `npc.citizens.list` (qui renvoie bien tous les PNJ, libres inclus —
vérifié sur le payload DEV réel : `#7 Stan`, `linkedNpcId=null`), ni le stockage n'étaient en
cause — uniquement le rendu du Control Panel.

`AgentPages.npcs()` raccroche désormais chaque PNJ de `npc.citizens.list` sans `linkedNpcId`
comme une ligne d'identité physique : libellé = nom en jeu, sous-titre `Citizens #N`, badges
« sans fiche RPGQuest » + « non lié », filtre *Non liés*, recherche par nom / id numérique /
UUID ; détail « Identité Citizens » (numéro, UUID, spawné) ; actions facultatives *Créer une
fiche RPGQuest* et *Lier à une fiche existante* (liaison inverse `npc.citizens.link`). Nouvel
état d'affichage `CITIZENS_ONLY` (information, jamais erreur) + entrée `DiagnosticHelp` + section
« PNJ du jeu sans fiche RPGQuest » dans `/docs/pnj-depannage`. `NpcDiagnosticProvider` émet un
INFO `CITIZENS_ONLY` par PNJ Citizens libre sur `/diagnostics`. Le compteur `/npcs` inclut les
PNJ Citizens libres.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local + `https://plugadmin.lodylands.com/health` : **ONLINE**.
- `/home` `/npcs` `/diagnostics` `/docs/pnj-depannage` anonymes → **303** vers `/login`.
- En-tête **CSP inchangé**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; **aucun `ERROR`** au journal.
- `:control-panel:test` **237/0** (`NpcsCatalogTest` 14, `DiagnosticHelpTest` 7) ; `:test`
  **0 échec / 0 erreur** (`NpcCitizensPayloadTest` 7) ; `./gradlew build` vert.
- Validation live `/npcs` (Stan visible, `Citizens #7`, non lié) + navigateur authentifié :
  `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~12:50 UTC). Branche `feat/control-panel-admin-tools` @ `5c7d333`.
`deploy.sh --no-build` OK (release `/opt/plugadmin/releases/20260909-125035`). Jar déployé
**byte-identique** au build (SHA-256 `553004d4…`) ; `renderFreeCitizensAccordionItem` +
`citizensInverseLinkForm` présents dans le bytecode, `pnj-depannage.md` (section « PNJ du jeu
sans fiche RPGQuest ») embarquée. `/health` ONLINE local + `https://plugadmin.lodylands.com` ;
`/home` `/npcs` `/diagnostics` `/docs/pnj-depannage` anon → **303** ; **CSP inchangée**
(`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action
'self'; frame-ancestors 'none'`) ; `dig.lodygames.com` + `lodylands.com` → **200** ;
`plugadmin.service` `active` `NRestarts=0` ; **0 `ERROR`** au journal.
Un relevé `npc.citizens.list` frais enfilé pour validation live est resté **DELIVERED** sans
résultat (exécuteur de l'agent DEV VeryGames — **hors périmètre de ce changement**, agent non
modifié). Preuve agent = payload DEV réel du 2026-09-09 11:33 (`#7 Stan`, `linkedNpcId:null`).
Rendu `/npcs` authentifié : `PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

---

## 2026-09-09 - Control Panel : hotfix #103 — 502 Bad Gateway après connexion (durcissement AgentStore) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Après le déploiement #101 (release `20260909-125035`), `/login` fonctionnait mais toute page
authentifiée (`/home`, `/npcs`, `/dashboard`, …) renvoyait **502 Bad Gateway**.

**Cause racine — ce n'est PAS une régression du code #101.** Pendant la « validation live » de
#101, une ligne `agent_action` a été insérée directement en base (sonde `npc.citizens.list`)
avec `created_at = '2026-09-09T12:51:33'` — **sans « Z »**, car écrite par
`strftime('%Y-%m-%dT%H:%M:%S','now')` au lieu du `Instant` ISO de PlugAdmin. `AgentStore.readAction`
faisait `Instant.parse(created_at)` sans tolérance → `DateTimeParseException`. L'exception
remontait par `NotificationCenter` (cloche de la topbar, rendue sur **toute** page authentifiée)
→ HTTP 500 → 502 nginx. `/login` n'affiche pas la cloche, d'où l'insuffisance du smoke anonyme.

**Correctif (déployé) — `AgentStore` :** analyse d'horodatage tolérante (`parseTimestamp` : ISO
canonique + formes héritées sans décalage, interprétées UTC ; illisible → `null` + WARNING) ;
`created_at`/`received_at` → `Instant.EPOCH` en dernier recours (jamais d'exception) ; **frontière
de sécurité par ligne** dans `recentActions` (une ligne illisible est ignorée + WARNING, jamais
propagée). Une donnée agent invalide ⇒ diagnostic, jamais crash du serveur web.

**Correctif de données (déjà appliqué en prod avant le redéploiement) :** la ligne fautive
(`agent_action` id `0cd8dd38…`, `created_by='claude-101-validation'`, statut `EXPIRED`) a vu son
`created_at` réparé `'2026-09-09T12:51:33'` → `'2026-09-09T12:51:33Z'` (UPDATE ciblé, aucune
suppression). Vérifié : 0 ligne au `created_at` sans « Z » dans `control-panel.db`.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret. **Réparation de données déjà faite.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché par le déploiement (réparation de la ligne faite
séparément, UPDATE ciblé, valeur d'origine consignée dans le rapport).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `:control-panel:test` **241** (0 échec ; 1 ignoré = smoke prod-copy, exécuté à la demande) —
  `PanelHardeningMalformedAgentDataTest` : connexion owner réelle + `/home /dashboard /npcs
  /diagnostics /docs /actions` = **200** avec la ligne exacte de #103 ; **vérifié que le test
  échoue (EOFException = 502) sans le correctif**. `:test` plugin inchangé. `./gradlew build` vert.
- **Smoke authentifié contre une COPIE de la base de prod réelle** (`-DpanelProdDbCopy`) :
  connexion owner + `/home /dashboard /npcs /diagnostics /docs /actions /agents /players /quests
  /stories /dialogues` = **200** ; Stan (#101) toujours visible ; ligne mal formée réinjectée →
  `/home` reste 200.
- Prod : `/health` ONLINE local + `https://plugadmin.lodylands.com` ; `plugadmin.service`
  `active` `NRestarts=0` `ExecMainStatus=0` ; **0 `ERROR`** au journal depuis le redéploiement ;
  jar déployé **byte-identique** au build (SHA-256 `9a21828f…`), `parseTimestamp` présent dans
  le bytecode ; `control-panel.db` = 79 lignes `agent_action`, **0** au `created_at` sans « Z ».
- Navigateur authentifié sur le service de prod : dernier point à confirmer par l'owner (mot de
  passe non détenu) — équivalent couvert par le smoke authentifié sur copie de prod.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-125035` = #101
sans le durcissement). ⚠️ Avec ce rollback, réparer aussi toute future ligne `created_at` sans
« Z » en base, sinon le 502 revient — le durcissement est la vraie protection. Aucune migration
à défaire.

### Exécution réelle

Session du 2026-09-09 (~13:16 UTC). Branche `feat/control-panel-admin-tools` @ `aa6270b`
(fix) / `c92ae3f` (test infra). `deploy.sh --no-build` OK (release
`/opt/plugadmin/releases/20260909-131643`, PID 480917). `/health` ONLINE local + public ;
`/login` → 200 ; `/home` `/npcs` `/diagnostics` `/dashboard` anon → 303 ; `plugadmin.service`
`active` `NRestarts=0` ; **0 `ERROR`** ; jar `9a21828f51e71e8784223bd2f8e549dbd0d09bf22c48e5fbc13ad0f8d608983c`.
Réparation de la ligne `0cd8dd38…` faite à ~13:09 UTC (avant redéploiement) — panel déjà
re-servi à ce moment ; le redéploiement apporte la protection permanente.

---

## 2026-09-09 - #96 : annuaire des joueurs /players (player.catalog + ban/unban) — plugin VeryGames + Control Panel AWS

### Changement

**Ce changement touche le plugin RPGQuest (agent) ET le Control Panel.**

- **Plugin/agent** : trois nouvelles actions agent, aucune commande console, aucun SQL, aucune
  migration :
  - `player.catalog` — annuaire complet : joueurs **connectés** + joueurs **hors ligne déjà
    venus** (source = serveur Paper `OfflinePlayer` ; instantané des connectés sur le thread
    principal puis `getOfflinePlayers()` sur un thread **asynchrone** Bukkit — jamais de lecture
    disque sur le main). Champs : `uuid`, `name`, `online`, `hasPlayedBefore`, `firstPlayed`,
    `lastSeen`, `banned` (+ `banReason`), `world`/`x`/`y`/`z` si en ligne. Cap de sécurité 5000
    (demande sans limite → `truncated=true`).
  - `player.ban` / `player.unban` — via l'API publique `BanList` de **profil** Paper. Fonctionnent
    **en ligne comme hors ligne** ; expulsion (`kick`) si le joueur est connecté ; **raison
    obligatoire** pour le ban (stockée telle quelle, jamais une commande) ; idempotents
    (`ALREADY_BANNED` / `NOT_BANNED`). Cible résolue en **UUID canonique** par le
    `PlayerDirectory` existant avant toute écriture.
- **Control Panel** : page `/players` réécrite en annuaire (recherche pseudo/UUID, filtres
  Tous/En ligne/Hors ligne/Bannis, tri, pagination 50 — tout côté serveur), détail en accordion
  (Identité / Activité / RPGQuest / Droits / Modération / Actions), ban/unban avec confirmation
  forte + raison obligatoire, permission `PLAYER_MODERATE`. « Donner un objet » reste **en ligne
  uniquement** (indisponible expliqué hors ligne). Fiche `/docs/joueurs-admin`.
- **Droit de construction persistant : NON livré** — bloqué par #27 (permissions granulaires
  non fusionnées sur cette branche + pas de store de grant persistant). La fiche affiche
  « non géré », sans faux interrupteur.

### Action serveur

1. **VeryGames DEV** : remplacer **uniquement le JAR RPGQuest** (`scripts/deploy-verygames.sh -y`,
   backup daté auto). Aucun autre fichier. **Ne pas** toucher `data.db`, config, mondes, Citizens.
2. **Un seul** redémarrage : `scripts/verygames-restart.sh` (stop RCON → relance auto VeryGames).
   Vérifier le retour **ONLINE**. Respecter la règle anti-auto-reboot (10 reboots / 30 min → STOP,
   pas de retry, attendre relance manuelle).
3. **Control Panel AWS** : `scripts/plugadmin/deploy.sh` (release + `systemctl restart plugadmin`
   + `/health`).

### Sauvegarde préalable

- VeryGames : backup JAR daté automatique par `deploy-verygames.sh`
  (`~/.local/share/rpgquest/verygames-backups/…-predeploy.jar`).
- AWS : release précédente sous `/opt/plugadmin/releases/<horodatage>` (rétention 5).
- Aucune donnée (`data.db`, `control-panel.db`) touchée.

### Déploiement

```
scripts/deploy-verygames.sh -y
scripts/verygames-restart.sh
scripts/plugadmin/deploy.sh
```

### Validation

- `:test` **1214/0** (29 ignorés MockBukkit, inchangés) — `AgentActionExecutorTest` +5
  (`player.catalog` compteurs + cap ; `player.ban` exige la raison + résout l'UUID ;
  `player.unban`). `:control-panel:test` **257/0** — `PlayerCatalogTest` (8),
  `PlayersPageTest` (8, HTTP authentifié). `./gradlew build` vert.
- VeryGames : `/plugins` (RCON) verts après restart ; heartbeat agent **ONLINE** ;
  `player.catalog` déclenché depuis PlugAdmin → **SUCCESS** avec au moins le joueur de test
  connecté + des joueurs hors ligne ; `journalctl -u plugadmin` **0 `ERROR`**.
- AWS : `/health` ONLINE local + public ; `/players` (session de test) rend l'annuaire ;
  `plugadmin.service` `active` `NRestarts=0`.
- **ban/unban en réel** : uniquement sur une cible de test sûre si disponible ; sinon
  `PENDING MANUAL VALIDATION` (ne jamais bannir l'owner sans rollback sûr).
- Navigateur authentifié `/players` par l'owner : `PENDING OWNER`.

### Rollback

- Plugin : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
- Control Panel : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire. Un joueur banni pendant la validation doit être débanni
  (`player.unban` ou `/pardon <uuid>` console) — les bans ne sont pas annulés par le rollback JAR.

### Exécution réelle

Session du 2026-09-09 (~14:15–14:30 UTC). Branche `feat/control-panel-admin-tools` @ `faf87ec`.

- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — `./gradlew test` + `build` **OK** (gate
  interne), JAR `rpgquest-0.1.0-SNAPSHOT.jar` **1 437 876 o**, SHA-256
  `4f39e1e41e6860921a38b9c5893f5f56b95a0fac7fc1500d321ca2b64316765c` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260909T142659Z-predeploy.jar`
  (SHA-256 `d9a47cf868991dae8f6a072856f1f0519e87fd201cfc63388c486e4b0ba9f7ee`) ; téléversement
  atomique, JAR en ligne == local.
- **Un seul** redémarrage : `scripts/verygames-restart.sh` → save-all → stop RCON → **OFFLINE** →
  relance auto VeryGames → **ONLINE** (< 180 s, aucun message anti-auto-reboot). `/plugins` (RCON)
  = `Citizens, Multiverse-Core, RPGQuest, WorldEdit` **tous verts** ; `/rpgquest version` répond
  `v0.1.0-SNAPSHOT`.
- **Control Panel AWS** : `scripts/plugadmin/deploy.sh` — release
  `/opt/plugadmin/releases/20260909-141707`, jar **byte-identique** au build (SHA-256
  `ee72425ae36d87b3a52bfc54c5f214aed7eb44580b1f240fb45060df040507dc`) ; `PlayerCatalog` +
  `joueurs-admin.md` embarqués. `/health` ONLINE local + `https://plugadmin.lodylands.com` ;
  `/players` `/home` `/npcs` anon → **303** ; `/login` → 200 ; **CSP inchangée** ;
  `dig.lodygames.com` + `lodylands.com` → **200** ; `plugadmin.service` `active` `NRestarts=0`
  `ExecMainStatus=0` ; **0 `ERROR`** depuis le redéploiement (un `WARNING handler_error
  path=/login` isolé = `curl -I` HEAD qui ferme, bénin).
- **Validation live agent** (`player.catalog` enfilé, `created_by='claude-96-validation'`) →
  **SUCCESS** : `total=3`, `online=0`, `offline=3`, `banned=0`, `truncated=false` — 3 joueurs
  réels déjà venus (`Rondoudou9000`, `Madix666`, `LoDyMcFly`) avec UUID + `firstPlayed` /
  `lastSeen` crédibles (LoDyMcFly vu le 2026-09-08, le plus récent). Aucun joueur connecté sur
  DEV au moment du test → pas de monde/position (attendu). `npc.list` baseline → **SUCCESS
  inchangé** (8 PNJ, 7 avec avertissement).
- **Smoke authentifié** (`-DpanelProdDbCopy` = copie de la vraie `control-panel.db` après le
  relevé) : `/players` (+ `/home /dashboard /npcs /diagnostics /docs /actions …`) = **200**
  authentifié sur le payload `player.catalog` réel.
- **ban / unban en réel** : `PENDING MANUAL VALIDATION` — les 3 joueurs DEV ne sont pas des cibles
  de test sûres (l'owner `LoDyMcFly` ne doit pas être banni ; `Rondoudou9000` / `Madix666` sans
  autorisation). Logique couverte par les tests unitaires + HTTP.
- **Navigateur authentifié `/players`** par l'owner : `PENDING OWNER` (mot de passe non détenu).

Rappel : `npc.list` reste **SUCCESS** (8 PNJ inchangés), aucune progression joueur touchée,
aucune migration, `data.db` / `control-panel.db` non modifiés. Rollback plugin :
`scripts/rollback-verygames.sh --latest` + `verygames-restart.sh` ; rollback CP :
`scripts/plugadmin/rollback.sh app` (→ `20260909-131643`).

Rapport : `docs/claude-reports/2026-09-09_1400_players-annuaire-offline-96.md`.

## 2026-09-09 - Control Panel : passe UX éditeur de quêtes (#46) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun code agent, aucune migration,
aucun impact serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Passe UX ciblée sur l'éditeur guidé `/quests/new` (issue #46) :

- un type d'objectif / récompense pilote seul ses champs (descripteur unique) ; le
  changement de type est immédiat (JS progressif) et efface les valeurs du type
  précédent ; sans JavaScript, le serveur re-rend au premier aller-retour ;
- les actions de brouillon (ajouter / supprimer / réordonner) ne déclenchent plus la
  validation HTML `required` (`formnovalidate`) ; la validation métier n'a lieu qu'à
  « Vérifier » / « Enregistrer » ;
- ids stables + `formaction=".../save#ancre"` → la page se recale sur le composant
  concerné après chaque action ;
- listes recherchables (`panel.js`, composant local, aucun CDN) pour entité / matériau /
  PNJ / icône / catégorie ; la catégorie vient d'une liste curée + saisie libre.

Fichiers : `panel/content/{Descriptors,RefData}.java`, `panel/web/ContentEditorPages.java`,
`assets/panel.js`, `assets/plugadmin.css`, `resources/docs/quetes.md`. **CSP inchangée**
(`default-src 'self'`).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous
`/opt/plugadmin/releases/<horodatage>` + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base. `control-panel.db`
non touché.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE** ;
- `/quests/new`, `/quests/edit/<slug>`, `/stories/new`, `/quests/save` anonymes → **303**
  vers `/login` ;
- en-tête **CSP inchangée** ;
- `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** au build ;
- `:control-panel:test` **271/0** ; `./gradlew build` vert (plugin 1214/0, web-api 30/0) ;
- rendu navigateur **authentifié** de l'éditeur : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à
défaire.

### Exécution réelle

Session du 2026-09-09 (~16:17 UTC). Branche `feat/control-panel-admin-tools` @ `3c5228c`.
`scripts/plugadmin/deploy.sh` OK : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava`/`jar` **UP-TO-DATE** — distribution issue du build déjà testé), release
précédente sous `/opt/plugadmin/releases/20260909-161744`, `systemctl restart plugadmin` →
`active (running)` `NRestarts=0`. `/health` **ONLINE** local + public
(`https://plugadmin.lodylands.com/health`). `/quests/new` `/quests/save` `/stories/new` `/home`
anonymes → **303** `/login`. **CSP inchangée**
(`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'`).
Jar déployé **byte-identique** au build local (SHA-256 `24c141993222c066…`). `panel.js` servi
contient `initCombo`/`initEditorForms` ; `plugadmin.css?v=22a51c4f`. **0 ERROR** au journal
depuis le redéploiement ; un `WARNING handler_error path=/login stream closed` = `curl -I`
(HEAD) client qui ferme, bénin. **VeryGames / Minecraft non touchés.** Navigateur authentifié :
`PENDING MANUAL VALIDATION`.

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260909-161744`).

Rapport : `docs/claude-reports/2026-09-09_1607_editeur-quetes-passe-ux-46.md`.

---

## 2026-09-09 - PlugAdmin : resynchronisation auto après mutation + UX formulaires PNJ/Dialogues (issues #111 → #120)

### Changement

Control Panel uniquement — **aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration**.

- **Resynchronisation mutualisée après une mutation** (#112/#115/#116/#119/#120) : une
  mutation de contenu qui réussit (`npc.definition.*`, `npc.citizens.link/create`,
  `dialogue.*`, `quest.giver.set`, `player.ban/unban`) ré-enfile **automatiquement** les
  relevés `*.list` qu'elle périme (`AgentActionCatalog.Spec#refreshTypes()` →
  `AgentEndpoints`), `created_by = "auto"`, dédupliqués, jamais sur échec ni renvoi
  idempotent. Le centre de notifications masque ces lignes ; `panel.js` recharge **une seule
  fois** la page métier quand la file d'actions est retombée au repos. Plus besoin de
  « Rafraîchir catalogue + F5 ».
- **Confirmations** (#111/#113/#118) : `Spec#sensitive()` — l'édition de contenu réversible
  n'a plus de case à cocher cachée (phrase d'information + `confirm` implicite) ; la
  confirmation explicite ne reste que pour `player.ban/unban`, `player.resetnew.confirm`,
  `npc.citizens.create`, `dialogue.choice.delete`.
- **Formulaires** : aide métier sous chaque champ PNJ, exemples en `placeholder`, lien doc
  `target=_blank`. Page Dialogues : bouton primaire **+ Nouveau dialogue**, bandeau technique
  réduit à une phrase + `<details>`, **palette de couleurs MiniMessage** (14 teintes,
  pastilles + aperçu) qui génère `<couleur>…</couleur>` sans ré-enrober un MiniMessage saisi
  à la main.

Fichiers : `panel/agent/{AgentActionCatalog,AgentStore,AgentEndpoints}.java`,
`panel/web/{AgentPages,NotificationCenter,PanelApp,MiniText}.java`, `assets/panel.js`,
`assets/plugadmin.css`, `resources/docs/dialogues-depannage.md`. **CSP inchangée**
(`default-src 'self'`). `control-panel.db` non touché (aucune migration de schéma agent).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous
`/opt/plugadmin/releases/<horodatage>` + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base. **VeryGames / Minecraft
non touchés.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE** ;
- `/npcs`, `/dialogues`, `/home` anonymes → **303** vers `/login` ;
- en-tête **CSP inchangée** ;
- `plugadmin.service` `active`, `NRestarts=0` ;
- `:control-panel:test` **279/0** (1 skip pré-existant) ; `./gradlew build` vert ;
- rendu navigateur **authentifié** (mutation PNJ + dialogue, liaison Citizens, absence de F5,
  mobile) : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à
défaire.

### Exécution réelle

Session du 2026-09-09 (~20:32 UTC). Branche `feat/control-panel-admin-tools` @ `dc17f23`.
`scripts/plugadmin/deploy.sh` OK : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava`/`jar` **UP-TO-DATE** — distribution issue du build déjà testé), release précédente
sauvegardée sous `/opt/plugadmin/releases/20260909-203227`, `systemctl restart plugadmin` →
`active (running)`, `NRestarts=0`, drop-in `10-content-workspace.conf` toujours chargé, `Memory`
~52 M. `/health` **ONLINE** local + public (`https://plugadmin.lodylands.com/health`).
`/home` `/npcs` `/dialogues` anonymes → **303** `/login`. **CSP inchangée**
(`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'`).
Jar déployé **byte-identique** au build local (SHA-256 `d3a2f34fbbfcf390…`). `panel.js` servi
publiquement contient `reloadPlan` / `sawPending` / `initColorPalette` ; le jar embarque le
`panel.js` à jour et la section « Comprendre l'éditeur de dialogues » de `dialogues-depannage.md`.
**0 `ERROR`/`SEVERE`** au journal depuis le redéploiement. `control-panel.db` non touché (aucune
migration de schéma agent). **VeryGames / Minecraft non touchés.** Navigateur authentifié
(mutation PNJ + dialogue, liaison Citizens, absence de F5, mobile) : `PENDING MANUAL VALIDATION`.

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260909-203227`).

Rapport : `docs/claude-reports/2026-09-09_2016_resync-mutations-ux-formulaires-controlpanel-111-120.md`.

---

## 2026-09-09 - Waypoints par instance de biome — MVP moteur (issue #124)

### Changement

Nouveau système **plugin RPGQuest** : package `com.lodygames.rpgquest.waypoint`. Repères
physiques persistants et partagés, générés **par instance réelle de biome** dans
`travel.wild-world` (par défaut `wild`), à découvrir par interaction explicite. **Distinct des
Waystones** (V17, réseau de voyage sur grille) — les deux systèmes cohabitent, waystone inchangé.

- **Nouvelle migration de schéma V18** : tables `waypoints` et `waypoint_discoveries` (+ index
  unique `idx_waypoints_instance (world, biome_instance)`). **Additive et idempotente** : aucun
  `ALTER` sur une table existante, `CREATE TABLE IF NOT EXISTS`. `SchemaMigrator.CURRENT_VERSION`
  passe de 17 à 18 ; appliquée automatiquement au démarrage par `SchemaMigrationRunner`.
- **Nouvelle section de configuration** `travel.waypoint.*` dans `config.yml`
  (`enabled`, `region-size`, `min-distance`, `max-distance`, `candidate-attempts`,
  `move-throttle-millis`, `minimum-spacing`, `model-version`). Ajoutée automatiquement au
  `config.yml` du serveur par `ConfigFileCompleter` au prochain démarrage (les valeurs
  personnalisées ne sont jamais écrasées). Section absente → tous les défauts, comportement sûr.
- **Nouveau comportement runtime** : à l'entrée d'un joueur dans une zone de biome sans waypoint
  (dans `wild` uniquement), le plugin **pose une petite structure** (barrière de pierre + bloc
  d'or + bouton) à 24-72 blocs, sur terrain naturel, **hors claim**, **sans écraser de
  construction joueur**. Non destructif par conception. Blocs du waypoint protégés (casse,
  explosion, piston, feu, fluide, gravité) sauf `rpgquest.admin.world`.
- Fichiers : `src/main/java/com/lodygames/rpgquest/waypoint/**`,
  `database/WaypointRepository.java`, `database/SchemaMigrator.java` (V18),
  `config/TravelConfig.java`, `config/ConfigValidator.java`, `bootstrap/RPGQuestBootstrap.java`,
  `src/main/resources/config.yml`.

### Action serveur

**Remplacer uniquement le JAR RPGQuest** (`plugins/RPGQuest-*.jar`) sur le serveur **DEV**.
Aucun autre fichier à copier : la migration V18 et la section `travel.waypoint` sont appliquées
automatiquement au démarrage. Aucun plugin externe, aucune version Java, aucun monde à créer.
**Cible : DEV uniquement.** Production non concernée par cette session.

### Sauvegarde préalable

Via `scripts/deploy-verygames.sh` (backup daté automatique : JAR courant + `data.db` + config).
Ne jamais écraser le dernier backup. Sauvegarder en plus, par sécurité, le dossier du monde
`wild` (`world_wild/` côté serveur) : le nouveau comportement y **ajoute** des blocs (structures
de waypoint) — additif, mais un backup permet un retour à l'état « aucun waypoint ».

### Déploiement

Non exécuté par la session (pas de `scripts/verygames.env` sur la box de build ; règle CLAUDE.md
« aucun déploiement automatique » ; première mise en service d'un comportement de pose de blocs
autonome = validation humaine souhaitable sur la première génération en jeu).

À lancer demain, depuis `/srv/rpgquest/repo`, après avoir renseigné `scripts/verygames.env`
(voir `scripts/verygames.env.example`) :

```
./gradlew build                       # déjà vert ce jour ; re-vérifier
scripts/deploy-verygames.sh           # upload FTP du JAR + backup daté
scripts/verygames-restart.sh          # redémarrage RCON (stop -> attente retour)
```

### Validation

Après redémarrage, au journal serveur : ligne `Waypoints chargés : N.` (N=0 au premier
démarrage). Puis en jeu (compte **non-op**, monde `wild`) :

1. marcher ~1-2 min dans un biome jamais visité → au journal : `Waypoint « wp_wild_… » généré
   en wild (x,y,z) [biome …, modèle v1]` ; trouver la structure (barrière de pierre + bloc d'or
   + bouton) à quelques dizaines de blocs, **pas sous ses pieds** ;
2. **passer à côté sans cliquer** → rien ;
3. **clic droit sur le bouton** → message « Waypoint découvert — <biome> » + son ; re-cliquer →
   silencieux ;
4. casser un bloc du waypoint (non-op) → refusé ; en `rpgquest.admin.world` → autorisé ;
5. relancer le serveur → même waypoint, même découverte, aucun doublon (`Waypoints chargés : 1`).

Détail : `docs/WAYPOINTS.md` §9 et `docs/MANUAL_TEST_PLAN.md`.

### Rollback

- Remettre l'ancien JAR RPGQuest (`scripts/rollback-verygames.sh`) + redémarrage RCON.
- La migration V18 (tables `waypoints`, `waypoint_discoveries`) reste en base : **inerte** sans
  le code, aucune action requise. Un ancien JAR (schéma attendu 17) démarre sans souci, les
  tables surnuméraires sont ignorées.
- Structures de waypoint déjà posées dans le monde : retirées manuellement si besoin (pas de
  script), ou restauration du backup `world_wild/`. Non urgent (blocs vanilla inoffensifs).
- `mettre travel.waypoint.enabled: false` dans `config.yml` + `/rpgquest reload` désactive la
  génération et la découverte sans rollback de JAR.

### Exécution réelle

**Session initiale (2026-09-09 ~21:18)** : moteur livré, `./gradlew build` vert
(root `:test` 1254/0, `:control-panel:test` 279/0). Déploiement **non effectué** à ce stade.

**Déploiement DEV effectué (2026-09-09 ~21:32 UTC, sur demande explicite)** — DEV VeryGames
uniquement, aucun merge, aucune modification de contenu :

- Branche `feat/control-panel-admin-tools` @ `0d1c978` (contient bien `2473086` feat + `01f6839`
  docs + `0d1c978` handoff). Working tree propre.
- `scripts/deploy-verygames.sh -y` : `./gradlew test` **OK**, `./gradlew build` **OK** (tout
  `UP-TO-DATE`, code inchangé depuis le build initial).
- **JAR déployé** : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar`, 1 475 924 o,
  SHA-256 `27f427409a6d6879d4230f9efb0afc4f9b893eb6a829cd187fa048bc2ead1668` ; taille en ligne
  après transfert atomique == locale.
- **Backup préalable automatique** : `~/.local/share/rpgquest/verygames-backups/rpgquest-20260909T213229Z-predeploy.jar`
  (1 437 876 o, SHA-256 `4f39e1e41e6860921a38b9c5893f5f56b95a0fac7fc1500d321ca2b64316765c`) + `.meta`.
- Aucun `--also` : **seul le JAR** a été transféré. `data.db`, `config.yml`, `messages.yml`,
  `spawn.yml`, Citizens, mondes et autres plugins **non touchés** (le script refuse ces chemins).
- **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` (un seul appel) — `save-all`
  puis `stop` RCON, serveur passé **OFFLINE** puis revenu **ONLINE** (script sorti 0).
- **Vérifications post-redémarrage (RCON — pas d'accès aux logs)** : `/rpgquest version` →
  `RPGQuest v0.1.0-SNAPSHOT` ; `/plugins` → `RPGQuest` en **vert** (activé, non désactivé) ;
  `/rpgquest reload` → « Configuration RPGQuest rechargée. » (la section auto-complétée
  `travel.waypoint` passe `ConfigValidator`) ; `/rpgquest help` et `/rpgadmin` répondent
  normalement. Le plugin étant **pleinement activé et réactif**, `SchemaMigrationRunner` a
  appliqué **V18 sans erreur critique** (le bootstrap avorte l'activation sinon).
- **Non vérifiable directement** : la ligne de log `Waypoints chargés : N.` et un scan `ERROR`
  du démarrage — le compte FTP est chrooté sur le dossier des plugins (pas d'accès à `logs/`),
  RCON n'expose pas l'historique console, pas d'identifiants du panel VeryGames. À constater
  dans la console du panel lors de la validation manuelle (TC-210).
- **Aucun waypoint généré par cette session**, monde non modifié : la première génération sera
  déclenchée en jeu par l'owner (TC-210).

Rollback : `scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20260909T213229Z-predeploy.jar`)
puis `scripts/verygames-restart.sh`.

Rapport : `docs/claude-reports/2026-09-09_2118_waypoints-mvp-instance-biome-124.md`.

---

## 2026-09-09 - Export versionné du contenu déclaratif — phase 1 (issue #108)

### Changement

Phase 1 du pipeline de contenus LodyQuests : **export** de quêtes / stories / dialogues / PNJ
logiques dans un format public versionné `lodyquests-content-pack` (`schemaVersion: 1`).

- **Plugin RPGQuest** : nouveau package `com.lodygames.rpgquest.content.pack` (DTO + mapper +
  serializer + assembler) et **nouvelle action agent lecture seule** `content.export`
  (`AgentActionType.CONTENT_EXPORT`). Aucune migration de schéma, aucune nouvelle donnée
  persistante, aucun changement `config.yml` / `messages.yml`, aucun effet de bord runtime
  (l'action lit l'état en mémoire des moteurs et renvoie un YAML).
- **Control Panel (AWS)** : permission `CONTENT_EXPORT`, page `/content/export`, route de
  téléchargement `GET /content/export/download`. Nouvelle entrée de menu. CSP inchangée
  (`default-src 'self'`), aucun CDN. `control-panel.db` non touché (aucune migration agent).

### Action serveur

**Aucune pour l'instant.** Rien n'est déployé cette session (consigne : pas de déploiement).
Au prochain déploiement de la branche `feat/control-panel-admin-tools` :

- **Control Panel AWS** : `scripts/plugadmin/deploy.sh` (build + release + `systemctl restart
  plugadmin` + `/health`). C'est ce qui active réellement la page `/content/export`.
- **JAR RPGQuest DEV** (optionnel, seulement si on veut exporter depuis DEV) : remplacer
  `plugins/RPGQuest-*.jar` par le nouveau JAR (contient l'action `content.export`). Migration :
  aucune. Redémarrage : oui (remplacement de JAR).

**VeryGames / Minecraft / `data.db` / mondes / Citizens : non concernés par ce changement.**

### Sauvegarde préalable

Standard (backup daté automatique des scripts). Rien de spécifique — aucune donnée modifiée.

### Déploiement

Non exécuté. Voir « Action serveur ».

### Validation

- `./gradlew build` vert (voir rapport).
- Après déploiement AWS : `/content/export` rendu (auth), bouton « Exporter tout », téléchargement
  d'un `.yaml` conforme (`format: lodyquests-content-pack`, `schemaVersion: 1`). `PENDING MANUAL
  VALIDATION` (navigateur owner).

### Rollback

Control Panel : `scripts/plugadmin/rollback.sh app`. Plugin : ancien JAR + restart. L'action
`content.export` étant lecture seule et additive, aucun état à défaire.

### Exécution réelle

Aucune. Développement seul, branche `feat/control-panel-admin-tools`, non fusionnée, non déployée.

Rapport : `docs/claude-reports/2026-09-09_2215_export-versionne-contenu-108.md`.

---

## 2026-09-10 - Éditeur guidé Quêtes/Stories — passe UX (issue #46) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration.** Corrige des bugs navigateur confirmés manuellement sur
`/quests/new` et `/quests/edit/...` :

- Le `<form class="editor">` porte `novalidate` et l'attribut HTML `required` n'est plus émis
  (marqueur visuel « * » conservé) : construire / supprimer / réordonner un brouillon ne peut
  plus déclencher « Veuillez renseigner ce champ ». La validation métier reste 100 % serveur
  (`QuestValidator` / `StoryValidator`) aux seuls « Vérifier » / « Aperçu » / « Enregistrer ».
- Conservation du scroll après une action de brouillon : `panel.js` restaure explicitement la
  position au chargement (cible du hash via `scrollIntoView` + focus, sinon position mémorisée) —
  un POST HTML 200 n'applique pas de façon fiable le fragment d'un `formaction`.
- Bascule de type d'objectif / récompense immédiate : `applyType` appliqué une fois à l'init par
  `<select>` ; chaque module `panel.js` isolé en `try/catch`.
- Listes déroulantes des PNJ (`dl-npc`) avec libellé humain (`<option value="guard" label="Garde">`) ;
  `RefData` transporte `id -> displayName` depuis `npc.list`.
- Aide de la récompense `ITEM` : précise « objet vanilla, pas un objet personnalisé RPGQuest ».

Fichiers : `panel/web/ContentEditorPages.java`, `panel/content/Descriptors.java`,
`panel/content/RefData.java`, `panel/web/AgentPages.java`, `assets/panel.js`. CSP inchangée
(`default-src 'self'`), aucun CDN. `control-panel.db` non touché (aucune migration).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous `/opt/plugadmin/releases/<horodatage>`
+ `systemctl restart plugadmin` + check `/health`. Aucune autre action. Aucun changement nginx /
TLS / secret / base. **VeryGames / Minecraft non touchés.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `/quests/new` et `/quests/edit/<slug>` rendus (auth).
- `:control-panel:test` vert ; `./gradlew build` vert.
- Rendu navigateur authentifié (ajout/suppression sur formulaire incomplet, changement de type,
  combos entité/PNJ/material, conservation du scroll) : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~09:51 UTC** depuis `feat/control-panel-admin-tools`
@ `4cb278c`. `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava`/`jar` **UP-TO-DATE** — distribution issue du build déjà testé), release précédente
sauvegardée sous `/opt/plugadmin/releases/20260910-095149`, `systemctl restart plugadmin` →
`active (running)`, drop-in `10-content-workspace.conf` toujours chargé, `Memory` ~51 M.

Vérifications live : `/health` **ONLINE** local **et** public
(`https://plugadmin.lodylands.com/health`). `/quests/new` et `/quests/edit/x` anonymes → **303**
vers `/login` (routes vivantes, auth appliquée). `panel.js` servi publiquement contient bien
`function run(name, fn)` (isolation des modules) et `scrollIntoView({ block: "center" })`
(restauration du scroll de l'éditeur). **0 `ERROR` / `SEVERE` / `Exception`** au journal depuis le
redéploiement. `control-panel.db` non touché. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

Validation navigateur **authentifiée** de l'éditeur : couverte par `ContentEditorPagesTest` (28,
`PanelApp` réel + login owner + POST des actions sur `/quests/new` et `/quests/edit/<slug>`) et
`AuthenticatedSmokeTest`. Session owner navigateur réelle (8 scénarios manuels du rapport) :
`PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-095149`).

Rapport : `docs/claude-reports/2026-09-10_0949_editeur-quetes-passe-ux-46.md`.

---

## 2026-09-10 - Éditeur guidé de Stories — passe UX (issue #46) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration.** Passe UX ciblée sur l'éditeur guidé de **stories**
(`/stories/new`, `/stories/edit/<slug>`), pour lui donner la même ergonomie que l'éditeur de
quêtes déjà validé :

- **Sélection recherchable des quêtes par titre humain OU id technique.** La datalist `dl-quest`
  porte désormais un `label` humain (`<option value="first_steps" label="Premiers pas">`) ;
  `RefData` transporte `id -> titre` depuis le dernier relevé `quest.list`, `RefData.questLabel()`
  le résout (namespace `rpgquest:` toléré). Le combo `panel.js` (déjà) filtre sur le libellé
  **et** la valeur.
- **Ordre clair** : chaque ligne de la chaîne affiche le rang `N.` + le titre humain **au-dessus**
  de l'identifiant technique éditable, avec monter / descendre / retirer. Une quête absente du
  catalogue chargé porte un badge « quête inconnue » dès la saisie.
- Bouton **« Actualiser le formulaire »** ajouté à la section Validation (parité avec l'éditeur de
  quêtes). `<form novalidate>`, aucun `required`, conservation du scroll : déjà en place (V4),
  inchangés.
- Modèle **strictement** celui du moteur (`StoryDefinition` : `id`, `name`, `secret`, liste
  ordonnée de `questIds`) — aucun champ ajouté. `StoryValidator` inchangé (doublon = avertissement
  car le moteur l'autorise ; référence inconnue = avertissement ; round-trip).

Fichiers : `panel/content/RefData.java` (+`questNames` / `questLabel`), `panel/web/AgentPages.java`
(`referenceData` remplit `questNames` depuis `quest.list`), `panel/web/ContentEditorPages.java`
(datalist `dl-quest` labellisée, ligne de chaîne `renderStoryQuestRow`, bouton « Actualiser »),
`assets/plugadmin.css` (`.row-n` / `.row-title`). `panel.js` **non modifié**. CSP inchangée
(`default-src 'self'`), aucun CDN. `control-panel.db` non touché (aucune migration).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous `/opt/plugadmin/releases/<horodatage>`
+ `systemctl restart plugadmin` + check `/health`. Aucune autre action. Aucun changement nginx /
TLS / secret / base. **VeryGames / Minecraft non touchés.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `/stories`, `/stories/new`, `/stories/edit/<slug>`
  rendus (auth).
- `:control-panel:test` vert (`StoryEditorPassTest` +17) ; `./gradlew build` vert.
- Rendu navigateur owner (créer une story, recherche quête par titre puis par id, ajout multiple,
  monter/descendre, retirer, scroll conservé, Vérifier/Aperçu, YAML + ordre, mobile) :
  `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~11:14 UTC** depuis `feat/control-panel-admin-tools`
@ `007a419`. `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava` / `jar` **UP-TO-DATE** — distribution issue du build déjà testé), release
précédente sauvegardée sous `/opt/plugadmin/releases/20260910-111417`,
`systemctl restart plugadmin` → `active (running)`, drop-in `10-content-workspace.conf` toujours
chargé, `Memory` ~51 M.

Vérifications live : `/health` **ONLINE** local **et** public
(`https://plugadmin.lodylands.com/health`). `/stories`, `/stories/new` et
`/stories/edit/<slug>` anonymes → **303** vers `/login` (routes vivantes, auth appliquée).
`assets/plugadmin.css` servi publiquement contient bien `.row-title{ … }` (titre de la ligne de
chaîne) ; le JAR déployé contient `questDatalist`, `renderStoryQuestRow` et
« Actualiser le formulaire ». **0 `ERROR` / `SEVERE` / `Exception`** au journal depuis le
redéploiement. `control-panel.db` non touché. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

Validation navigateur : `StoryEditorPassTest` (17, rendu direct de `ContentEditorPages` avec un
`RefData` porteur de titres) + `ContentEditorPagesTest` (`storyEditorCreatesOrderedChain`,
`storyEditorFormAlsoDisablesNativeValidation`, `PanelApp` réel + login owner). Session owner
navigateur réelle (10 scénarios manuels du rapport) : `PENDING MANUAL VALIDATION` (mot de passe
owner non détenu).

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-111417`).

Rapport : `docs/claude-reports/2026-09-10_1032_editeur-stories-passe-ux-46.md`.

---

## 2026-09-10 - Control Panel : socle RBAC — rôles, permissions et comptes /users (issue #50) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, des
mondes, ou de `data.db`. Aucune migration MariaDB (#42).** Consolide le contrôle d'accès de
PlugAdmin autour du modèle **utilisateur → rôle → `Set<Permission>` → contrôle backend → UI
filtrée** (issue #50).

- **Rôles** (`authz.Role`) : `OWNER` (= `EnumSet.allOf(Permission.class)`), `ADMIN`, `TESTER`,
  `BUILDER`, `CONTENT_EDITOR`, `READ_ONLY`. Nouvelle permission `USER_MANAGE` (OWNER par défaut).
  Rôles propres à PlugAdmin — aucune correspondance avec OP / Paper / LuckPerms.
- **Comptes** : nouvelle table `panel_user` dans `control-panel.db` (`CREATE TABLE IF NOT EXISTS`
  additif et idempotent). `users.PanelUser` / `UserRepository` (`SqliteUserRepository`) /
  `UserDirectory`. Le compte `owner` défini par `RPGQUEST_PANEL_OWNER_USERNAME` /
  `RPGQUEST_PANEL_OWNER_HASH` est **réamorcé au démarrage** (créé s'il manque, sinon forcé actif
  + `OWNER` + hash réaligné) : chemin de récupération anti-verrouillage. Ces variables sont déjà
  requises pour que le service démarre — pas de risque de perte d'accès.
- **Auth** (`AuthService`) : identifiants vérifiés contre `panel_user` (casse ignorée), coût
  PBKDF2 payé même compte absent / inactif, un compte **désactivé** ne peut plus se connecter,
  `last_login_at` posé à la réussite. Hachage inchangé (`PasswordHasher` PBKDF2-HMAC-SHA256
  210k) — audité, conservé.
- **Session** : re-contrôle par requête — un compte désactivé perd sa session en cours (→ `303
  /login`), un changement de rôle prend effet sans reconnexion. Le `SessionStore` reste en
  mémoire : le redémarrage déconnecte les sessions en cours (comportement habituel d'un déploiement
  PlugAdmin).
- **Page `/users`** (`USER_MANAGE`) : liste + création (identifiant, mot de passe ≥ 12, rôle),
  détail `/users/<id>`, `POST /users/create` / `/users/<id>/role` / `/users/<id>/active`. CSRF
  synchroniseur sur chaque POST, permission vérifiée côté backend (403 « Vous n'avez pas
  l'autorisation… » même en appel direct). Dernier OWNER actif protégé (ni rétrogradation ni
  désactivation) ; désactivation de son propre compte interdite. Pas de suppression
  (désactivation seulement).
- **Navigation** : `Layout.nav()` + tuiles `HomePages` filtrées par permission ; la topbar
  affiche l'utilisateur **et** son rôle.
- **Audit** : `user.create`, `user.role.change` (`from=… to=…`), `user.active.change`,
  `login.failure` motif `compte désactivé`, `login.success` porte `role=…`. Jamais de mot de
  passe / hash.

Fichiers principaux : `authz/Permission.java`, `authz/Role.java`, `users/*` (nouveau paquet),
`security/AuthService.java`, `security/Session.java`, `security/SessionStore.java`,
`web/PanelApp.java`, `web/Layout.java`, `web/HomePages.java`, `web/UsersPages.java` (nouveau),
`web/Icons.java`, `agent/AgentActionCatalog.java` (+ `all()`), `assets/plugadmin.css`. CSP
inchangée (`default-src 'self'`), aucun CDN, aucun script inline.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — `:control-panel:installDist` + release sous
`/opt/plugadmin/releases/<horodatage>` + `systemctl restart plugadmin` + check `/health`. Aucune
autre action. Aucun changement nginx / TLS / secret. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

### Sauvegarde préalable

- **`control-panel.db`** (migration = nouvelle table `panel_user`) : copie datée avant le
  redémarrage, p. ex. `cp /opt/plugadmin/shared/control-panel.db
  /opt/plugadmin/backups/control-panel.db.$(date +%Y%m%d-%H%M%S)` (chemin réel à confirmer via le
  drop-in systemd / `RPGQUEST_PANEL_DB`).
- App précédente : automatique via `deploy.sh` (`/opt/plugadmin/releases/<horodatage>`,
  rétention 5).

### Déploiement

```
# sauvegarde de la base du Control Panel (migration additive)
cp <chemin>/control-panel.db <chemin>/control-panel.db.$(date +%Y%m%d-%H%M%S)
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `systemctl status plugadmin` = `active (running)`.
- `/login` rendu ; connexion owner OK ; `/users` rendu (auth owner) et **403** pour un rôle sans
  `USER_MANAGE` ; URL `/users` anonyme → `303 /login`.
- Journal sans `ERROR` / `SEVERE` / `Exception` depuis le redémarrage.
- `:control-panel:test` vert (354 tests, +43) ; `./gradlew test` + `./gradlew build` verts.
- Checklist navigateur owner (créer un compte TESTER, se connecter avec lui, menus visibles,
  URL interdite → 403, revenir OWNER, désactiver le compte de test) : `PENDING MANUAL VALIDATION`.

### Rollback

1. `scripts/plugadmin/rollback.sh app` (restaure la release précédente).
2. La table `panel_user` peut rester en place sans effet sur l'ancienne version (elle l'ignore).
   Pour un retour strictement à l'identique : restaurer la copie datée de `control-panel.db`.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~15:52 UTC** depuis `feat/control-panel-admin-tools`
@ `ea34d73`.

- Sauvegarde préalable : `sudo cp -a /var/lib/plugadmin/control-panel.db
  /opt/plugadmin/backups/control-panel.db.20260910-155247` (migration = nouvelle table
  `panel_user`).
- `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL** (`compileJava`
  / `jar` **UP-TO-DATE**), app précédente sauvegardée sous
  `/opt/plugadmin/releases/20260910-155250`, `systemctl restart plugadmin` → `active (running)`
  (PID 756465, `Memory` ~69 M), drop-in `10-content-workspace.conf` toujours chargé.
- Vérifications live : `/health` **ONLINE** local **et** public
  (`https://plugadmin.lodylands.com/health`) ; `/login` local → **200** ; `/users` anonyme →
  **303** vers `/login` (`USER_MANAGE` appliqué avant tout) ; `control-panel.db` : table
  **`panel_user`** créée, ligne `owner | OWNER | active=1 | last_login_at=NULL` (amorçage OK,
  `data.db` non touché) ; `journalctl -u plugadmin` depuis le redémarrage : **0**
  `ERROR` / `SEVERE` / `Exception` / `WARN`. **VeryGames / Minecraft non touchés, aucun
  redémarrage Minecraft.**

Validation navigateur **authentifiée** de `/users` : couverte par `UserManagementTest` (11,
`PanelApp` réel + sessions HTTP, dont 403 backend en appel direct, CSRF, audit, dernier OWNER,
session d'un compte désactivé, navigation filtrée). Session owner navigateur réelle (checklist du
rapport) : `PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-155250`) ; base :
`/opt/plugadmin/backups/control-panel.db.20260910-155247`.

Rapport : `docs/claude-reports/2026-09-10_1553_rbac-plugadmin-50.md`.
