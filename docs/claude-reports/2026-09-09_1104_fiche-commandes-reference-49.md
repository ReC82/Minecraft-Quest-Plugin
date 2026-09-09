# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 11:04 → 11:13 (heure locale machine, UTC)
* Sujet : Réécriture de la fiche `/docs` de référence des commandes — une section par commande (nom humain, description, syntaxe, paramètres, exemple copiable, résultat), fin des gros blocs multi-commandes à bouton « Copier » unique (issue #49)
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `577c4cd` ; poussé sur `origin/feat/control-panel-admin-tools`
* Début de la tâche : 2026-09-09 11:04:00
* Fin de la tâche : 2026-09-09 11:13:40
* Durée totale : 00:09:40

## Demande

La fiche de référence des commandes (`/docs/rpgquest-commandes`) affichait encore de gros
blocs listant plusieurs commandes différentes derrière un **seul** bouton « Copier ». Objectif :
chaque commande / sous-commande importante a sa propre explication —

1. nom humain ; 2. à quoi elle sert ; 3. syntaxe ; 4. paramètres ; 5. exemple concret ;
6. résultat attendu ; 7. warning / permission si pertinent ; 8. bouton « Copier »
**uniquement** sur une commande réellement exécutable telle quelle. **Jamais** de bouton
« Copier » sur une liste entière de commandes différentes.

Détail des sections demandées : structure par commande (§1) ; `/rpgquest`
version/help/profile/reload (§2) ; `/rpgadmin npc` tag/untag/info et toutes les sous-commandes
réellement présentes (§3) ; `/rpgadmin quest` start/complete/reset + `force` (§4) ;
`/rpgadmin story` info/start/advance/complete/reset/resetwithquests (§5) ; `/rpgadmin player
resetnew` preview/confirm/console/conséquence (§6) ; autres familles réellement présentes
(spawn, world, worldportal, waystone, guide, flatten, zone, portal, mob…) groupées par
catégorie (§7) ; exemples avec des IDs de test **valides**, jamais inventés (§8) ; bouton
« Copier » par bloc d'exemple (§9) ; présentation en cards/sections Bootstrap, page facile à
parcourir (§10) ; la recherche doit continuer à trouver `npc tag`, `reset joueur`,
`quest complete`, `story reset`, `rollback`, `skin`… (§11) ; toujours l'explication humaine
avant l'ID technique (§12) ; **auditer le code réel avant rédaction**, ne rien supposer, si la
doc contredit le code corriger la doc (§13) ; tests (§14) ; déploiement Control Panel
uniquement, ne pas toucher VeryGames ni redémarrer Minecraft (§15) ; répondre à « je veux faire
X, quelle commande ? » puis « comment exactement ? » (§16) ; ne pas fermer #49, ne rien merger.

## Analyse

### Audit du code (source de vérité, §13)

Classes de commandes lues intégralement avant rédaction :

* **`RpgAdminCommand`** (2405 lignes) — permission `rpgquest.admin.world` en tête ; branches
  `story` / `player` / `quest` / `guide` s'exécutent **depuis la console** (cible = joueur en
  argument), toutes les autres exigent un joueur en jeu (position / sélection). Sous-commandes
  réelles relevées :
  * `npc` : `tag [id]` (id optionnel — `npc_<n>` auto sinon ; minuscules/chiffres/`._-` ;
    idempotent, `untag` d'abord), `untag`, `info` (ciblent l'entité visée ≤ 6 blocs).
  * `quest` : `start <joueur> <quête> [force]` (en ligne), `complete <joueur> <quête>` (en
    ligne, récompenses une seule fois), `reset <joueur> <quête>` (hors ligne, n'annule pas les
    récompenses). Id court → `rpgquest:<id>` (`resolveQuestId`, `DEFAULT_NAMESPACE = rpgquest`).
  * `story` : `info|start|advance|complete|reset|resetwithquests`. `advance`/`complete` en
    ligne. `reset <story|all>` ne touche pas aux quêtes ; `resetwithquests <story>` si.
  * `player` : `resetnew <joueur> <preview|confirm>` (mot `confirm` obligatoire ; `preview` =
    dry-run ; sans les deux = avertissement seul) ; `variable get <joueur> <clé>` /
    `variable set <joueur> <clé> <valeur>` (`set` exige **en plus** `rpgquest.admin.debug`).
  * `spawn` : `set` / `tp`. `world` : `create <nom>` / `tp <nom>` / `list`.
  * `worldportal` : `create <id> <mondeDestination>` / `info|list|enable|disable|delete <id>` /
    `here` (diagnostic) / `debug show|hide|showall|hideall`.
  * `waystone` : `list` / `here` / `tp <id>` / `generatehere` / `reset discoveries <joueur>`.
  * `guide` : `list` / `info <hub>` (lecture seule).
  * `zone` : `wand` / `create|delete|info <id>` / `list`. `portal` : `create|delete|info <id>`
    / `list` / `setdestination <id> <destinationId>`. `mob` : `spawn|inspect <id>` / `list` /
    `reload` / `metrics`. `flatten <rayon> [hauteur]` / `confirm` / `cancel` / `undo`.
* **`RPGQuestCommand`** — `/rpgquest version|help|profile [joueur]|reload`. `reload` exige
  `rpgquest.admin` (≠ `rpgquest.admin.world`) et ne recharge **que** `config.yml`.
* **`QuestCommand`** — `/quest list|accept|progress|abandon|complete` + `/quest admin
  reload|validate|reset <joueur> <id|all>` (`rpgquest.admin`).
* **`DialogueCommand`** — `/dialogue open <joueur> <dialogueId>` (`rpgquest.admin`).
* **`CustomItemCommand`** — `/customitem give <joueur> <id> [quantité]` / `list`
  (`rpgquest.admin`) / `inspect` (`rpgquest.item`).

IDs de test valides retenus (§8) : `LoDyMcFly`, `guard`, `first_steps`, `main_story`,
`world_hub` (nom réel du monde Hub d'après `config.yml`), `wild`, `claims`.

### Contrainte du renderer Markdown (§10)

`Markdown` (module `control-panel`) est volontairement restreint : titres, paragraphes, listes,
tableaux, blocs `` ``` ``, citations/callouts `> [!NOTE]`, `---`, inline
`` `code` ``/`**gras**`/`*italique*`/liens sûrs. **Aucun HTML brut** (protection XSS : tout est
échappé). Pas de syntaxe d'accordéon. **Chaque bloc `` ``` `` produit déjà son propre
`<div class="doc-cmd">` + bouton « Copier »** (`renderCodeBlock`). Donc la règle « pas de
Copier sur une liste » = **ne jamais mettre plus d'une commande dans un bloc**. La navigation
« cards/sections » est assurée par les titres `##`/`###` + le sommaire latéral (`doc-toc`) déjà
en place ; j'ajoute une touche CSS pour distinguer visuellement chaque section `###` de
commande, **sans toucher au renderer** (zéro risque de régression Markdown / XSS).

### Contrainte recherche (§11)

`DocSearchIndexTest` épingle des fiches de tête : `tag npc` → `pnj-citizens`,
`quest complete` / `story complete` → `quetes`, `reset joueur` → `joueurs-reset`. Le score
pondère titre (25) > tag (12) > catégorie (7) > commande (5) > corps (plafonné à 5). Pour ne
pas déclasser ces fiches, la fiche de commandes garde un `title:` et des `tags:` **sans**
`npc`/`tag`/`quest`/`story`/`complete`/`reset`/`joueur` comme jetons isolés (tags →
`[rpgadmin, rpgquest, commandes, reference, syntaxe, console, admin, aide]`). La fiche reste
trouvée via corps + commandes, mais n'arrive pas en tête sur ces requêtes topiques.

## Travail effectué

### `control-panel/src/main/resources/docs/rpgquest-commandes.md` (réécrit)

* Front matter : `title: Commandes RPGQuest (référence)`, tags recentrés « référence ».
* **Table « En un coup d'œil »** (Je veux… → commande) — répond à « quelle commande ? » (§16).
  Les commandes y sont en `` `code` `` inline (pas de bouton — c'est un index, pas une cible
  de copie).
* **8 catégories `##`** : Joueurs / PNJ / Quêtes / Stories / Mondes / Portails & Waystones /
  Administration serveur / Outils builder & avancés.
* **37 sections `###`**, une par commande / sous-commande : titre = nom humain +
  `` `commande` `` ; puis description, `**Syntaxe**` (1 bloc), `**Paramètres**` (liste),
  `**Exemple(s)**` (1 bloc chacun), `**Résultat attendu**`, `**Permission**` / `**Attention**`
  si pertinent.
* **78 blocs `` ``` ``, 78 boutons « Copier »**, chacun **une seule** commande exécutable.
  Plus aucun bloc n'énumère d'alternatives avec `|`.
* `player resetnew` : preview + confirm + « utilisable depuis la console » + liste explicite de
  ce qui est réinitialisé vs conservé + les deux exemples (`preview` puis `confirm`).
* Callout de bas de page : source de vérité = le code des commandes ; si divergence, le jeu a
  raison.

### `control-panel/src/main/resources/assets/plugadmin.css`

* `.doc-body h3` : bord gauche accent (`border-left:3px solid var(--primary)`), plus lisible,
  fait lire chaque commande comme une carte.
* `.doc-body h3 code` : le `` `id` `` du titre stylé en puce discrète.
* `.doc-body p > strong:first-child:only-child` : les mini-libellés « Syntaxe » / « Paramètres »
  / « Exemple » / « Résultat attendu » / « Attention » rendus en petites capitales grises
  discrètes (ils sont émis comme `<p><strong>…</strong></p>` par le renderer).

### Tests — `CommandReferenceSheetTest` (6, `panel.docs`)

* `everyCodeBlockHoldsExactlyOneExecutableCommand` — chaque bloc `` ``` `` = **une** ligne
  non vide, commençant par `/` (≥ 30 blocs).
* `renderedCopyButtonsNeverCarryAMultiCommandPayload` — chaque `data-copy` rendu : pas de
  saut de ligne, **au plus un** préfixe `/rpgadmin|/rpgquest|/quest|/customitem|/dialogue`.
* `everyMajorCommandHasItsOwnSectionWithDescriptionSyntaxAndExample` — 21 commandes majeures :
  une section `### …\`<commande>\``, un bloc contenant la commande complète ; présence de
  `**Syntaxe**` / `**Exemple** / `**Résultat attendu**` / `**Paramètres**` ; `resetnew`
  documente preview/confirm/console/dry-run.
* `theOldMonolithicReferenceBlockIsGone` — anciens blocs fourre-tout absents ; aucun bloc
  n'énumère d'alternatives ` | `.
* `searchStillFindsTheCommands` — `npc tag`, `npc untag`, `quest complete`, `story reset`,
  `player resetnew`, `world create`, `worldportal create`, `dialogue open` renvoient des
  résultats **incluant** la fiche de commandes ; `tag npc` → `pnj-citizens`,
  `quest complete` → `quetes`, `reset joueur` → `joueurs-reset` **inchangés** (fiches de tête).
* `renderingIsSafeAndPlaceholdersAreEscaped` — pas de `<script`, `<joueur>` → `&lt;joueur&gt;`,
  pas de `<h1` dans le corps rendu, `## `/`### ` bien rendus.

## Fichiers créés

* `control-panel/src/test/java/com/lodygames/rpgquest/panel/docs/CommandReferenceSheetTest.java`
* `docs/claude-reports/2026-09-09_1104_fiche-commandes-reference-49.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/resources/docs/rpgquest-commandes.md` — réécriture complète.
* `control-panel/src/main/resources/assets/plugadmin.css` — styles `.doc-body h3` / mini-libellés.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-09 (AWS uniquement).

## Base de données / migrations

Aucune.

## Configuration / données

Aucune. La fiche `.md` est embarquée dans le jar du Control Panel (whitelist `_index.txt`
inchangée : `rpgquest-commandes.md` y était déjà).

## Tests automatiques

* `./gradlew :control-panel:test` — **221 tests, 0 échec** (210 + 6 `CommandReferenceSheetTest`
  + 5 déjà présents des tickets précédents ; `DocSearchIndexTest` / `MarkdownTest` /
  `DocLibraryTest` / `DocFrontMatterTest` inchangés au vert).
* `./gradlew test` — inchangé (aucun module hors `control-panel`).
* `./gradlew build` — **SUCCESS**.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (mot de passe owner non détenu) — en navigateur authentifié sur
`https://plugadmin.lodylands.com/docs/rpgquest-commandes` :

1. La table « En un coup d'œil » en tête ; puis 8 catégories, une section par commande.
2. Chaque exemple = un petit bloc sombre avec **son** bouton « Copier » compact ; **aucun**
   bloc listant plusieurs commandes.
3. Sommaire latéral navigable ; sur mobile, blocs scrollables horizontalement si besoin,
   bouton « Copier » toujours accessible.
4. Recherche `/docs?q=npc tag`, `q=story reset`, `q=player resetnew` : la fiche de commandes
   apparaît dans les résultats ; `q=tag npc` garde `pnj-citizens` en tête.
5. Non-régression : `q=skin` → `pnj-citizens`, `q=rollback` → fiche de déploiement.

## Résultat attendu

La fiche répond à « je veux faire X, quelle commande ? » (table + catégories) puis « comment
exactement ? » (section dédiée par commande). Plus aucun inventaire technique brut.

## Reset / retour à l'état initial

* Contenu : `git revert 577c4cd` (ou `git checkout 46cb794 -- control-panel/src/main/resources/docs/rpgquest-commandes.md`).
* Déploiement : `scripts/plugadmin/rollback.sh app` (release `20260909-111244`).

## Déploiement VeryGames

### À transférer
Rien. **Aucun changement plugin / agent / monde / donnée Minecraft.**

### Ne PAS transférer/altérer
Le serveur VeryGames. Ne pas redémarrer Minecraft.

### Redémarrage requis
Uniquement le service `plugadmin` sur AWS (fait par `deploy.sh`).

### Migration automatique
Aucune.

## Déploiement AWS (Control Panel) — effectué

* `scripts/plugadmin/deploy.sh` — release `/opt/plugadmin/releases/20260909-111244`,
  `systemctl restart plugadmin`, health local **ONLINE**.
* Vérifications :
  * `https://plugadmin.lodylands.com/health` → `{"panel":"ONLINE"}` ;
  * `/docs` `/docs/rpgquest-commandes` `/npcs` `/docs?q=npc+tag` anonymes → **303** vers `/login` ;
  * En-tête **CSP inchangé** ;
  * `dig.lodygames.com` → **200**, `lodylands.com` → **200** ;
  * `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** au build
    (`SHA-256 f25c5633…`) ; `docs/rpgquest-commandes.md` embarqué (78 blocs) ;
  * un `WARNING event=handler_error path=/login java.io.IOException: stream closed` =
    déconnexion client d'un `curl -I` (HEAD), **bénin**, non lié au changement.

## Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration.

## Logs / diagnostic

`journalctl -u plugadmin -f` — démarrage propre (`PanelApp start`, health `ONLINE`), aucun
`ERROR`.

## Documentation mise à jour

* `docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-09, AWS uniquement).
* La fiche `/docs/rpgquest-commandes` **est** de la documentation (contenu réécrit).

## Limitations / travail restant

* Validation navigateur authentifiée : `PENDING MANUAL VALIDATION`.
* Le renderer Markdown ne supporte pas d'accordéon natif — les commandes très avancées
  (`worldportal debug`, `mob metrics`, `player variable set`, `flatten`) sont regroupées sous
  « Outils builder & avancés » plutôt que masquées dans un accordéon. Ajouter un vrai accordéon
  demanderait d'étendre le renderer (hors périmètre de ce ticket de contenu).
* `/quest` joueur (`list`/`accept`/`progress`/`abandon`) et les familles économie / claims /
  backpacks / skills renvoient vers leurs fiches dédiées ou restent hors de cette fiche
  « administration » — à documenter séparément si besoin.

## Prochaine étape suggérée

* Validation navigateur authentifiée de la fiche.
* Si un accordéon est souhaité pour les commandes très avancées : petite extension du renderer
  `Markdown` (`> [!DETAILS]` → `<details>`), avec tests XSS dédiés.
