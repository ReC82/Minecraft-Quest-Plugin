# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 20:03 (locale, UTC sur cette machine)
* Sujet : Issue #49 — centre de documentation (wiki d'administration privé) dans le Control Panel — MVP
* Statut : DONE — `:control-panel:test` **154/0**, `:test` **1208/0** (29 ignorés), `./gradlew build` **SUCCESSFUL**. Déploiement **AWS** + validation navigateur : voir « Déploiement » / `PENDING MANUAL VALIDATION`.
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : commit de suivi de ce rapport
* Début de la tâche : 2026-09-08 19:41:21 (heure locale réelle)
* Fin de la tâche : 2026-09-08 20:12:00 (heure locale réelle)
* Durée totale : 00:30:39

## Demande

Ajouter dans PlugAdmin une section **Documentation** utilisable au quotidien : navigation dédiée,
pages wiki, recherche plein texte, catégories, rendu Markdown, commandes copiables, accès
**authentifié uniquement**. Git/Markdown reste la source de vérité — **pas de stockage
propriétaire opaque en base**. Fiche prioritaire : **Créer et configurer un PNJ** (Citizens +
RPGQuest), plus des fiches courtes reset joueur / quêtes / VeryGames / rollback / Claims / Wild.
Sécurité filesystem stricte (pas de `../`, pas de lecture arbitraire, whitelist explicite, slug
interne résolu côté serveur). Ne pas fermer #49. Ne rien merger. **Hors scope** : éditeur
WYSIWYG, édition Markdown depuis le navigateur, commentaires, workflow review, wiki public,
moteur IA, permissions complexes par rôle.

## Audit documentaire

- `docs/` du dépôt contient déjà des procédures **vérifiées** : `NPC_DIALOGUES_QUESTS_GUIDE.md`
  (commandes Citizens réelles + `/rpgadmin npc tag|untag|info`), `ADMIN_PLAYER_RESET.md`
  (`/rpgadmin player resetnew` + `preview`/`confirm`), `CLAIMS.md`, `TRAVEL.md`,
  `deployment/VERYGAMES.md`, `control-panel/DEPLOYMENT_AWS.md`, `RPGQUEST_BIBLE.md`.
- Commandes réelles auditées dans le code avant rédaction : `RpgAdminCommand`
  (`npc`, `quest start|complete|reset`, `story info|start|advance|complete|reset|resetwithquests`,
  `player resetnew|variable`, `world`, `worldportal`, `waystone`, `guide`), `QuestCommand`
  (`/quest`, `/quest admin`), `plugin.yml` (`usage:` de chaque commande). Citizens installé :
  `2.0.43` build `b4232` (`/npc create|select|skin|rename|move|tphere|remove`…).
- **Décision sur la racine documentaire** : ne **pas** exposer l'arborescence `docs/` du dépôt
  (non déployée avec le Control Panel, risque de fuite si on élargit). À la place, un **ensemble
  fermé** de fiches opérationnelles authored sous
  `control-panel/src/main/resources/docs/*.md` — livrées **dans le jar**, versionnées dans Git,
  listées par un manifeste `_index.txt` qui **est** la liste blanche. Le contenu s'appuie sur
  les docs auditées ci-dessus mais reste ciblé « procédures réellement utilisées maintenant »
  (9 fiches, pas 50 pages).

## Analyse — pourquoi ces choix

- **Zéro dépendance externe** (contrainte `control-panel/build.gradle.kts`) → renderer Markdown
  **maison** (`docs.Markdown`), sûr par construction (tout échappé).
- **Pas de base de données pour le contenu** → `DocLibrary` charge les ressources en mémoire au
  démarrage ; `DocSearchIndex` est un index inversé en mémoire. Git reste la vérité.
- **Sécurité filesystem** → aucune ouverture de fichier au moment de la requête : une fiche est
  adressée par un `slug` (`[a-z0-9-]{1,64}`) validé côté serveur puis résolu par un lookup
  `Map<slug, DocPage>`. Path traversal, lecture `.env` / clés / configs : **impossibles par
  construction**.
- **CSP** (`default-src 'self'`) → aucun script inline ; le bouton « Copier » réutilise
  `/assets/panel.js` (délégation sur `[data-copy]`, déjà en place).

## Travail effectué

### Nouveau package `com.lodygames.rpgquest.panel.docs`

- **`DocFrontMatter`** — parseur minimal du front matter (`title`, `category`, `tags` en `[a,b]`
  ou liste `- a`, `order`). Markdown **sans** front matter accepté (titre = 1er `# …` sinon nom
  de fichier).
- **`Markdown`** — rendu Markdown → HTML **sûr** : titres (avec ancres), paragraphes, listes
  (2 niveaux), tableaux simples, blocs `` ``` `` (avec bouton « Copier »), callouts
  `> [!NOTE]` / `> [!WARNING]`, `---`, inline `` `code` `` / `**gras**` / `*italique*` /
  `[texte](lien)`. **Tout le texte source est échappé** ; aucune balise HTML brute, aucun
  `<script>`, aucun `on*=`, aucune URL `javascript:` ; liens rendus uniquement vers `/docs/…`,
  ancre `#…`, ou `https://…` (sinon seul le libellé subsiste).
- **`DocPage`** (record) — slug, titre, catégorie, tags, order, markdown, chemin source,
  commandes repérées.
- **`DocLibrary`** — lit `docs/_index.txt` (manifeste = liste blanche), charge chaque `.md`,
  parse le front matter, trie (catégorie, order, titre). `bySlug(slug)` = lookup mémoire, jamais
  d'exception, jamais de disque. `byCategory()`.
- **`DocSearchIndex`** — recherche plein texte mémoire : bonus **plats** par présence d'un terme
  (titre 25 > tags 12 > catégorie 7 > commandes 5 > corps plafonné à 5), **ET** de tous les
  termes, correspondance exacte + préfixe (`citizen` trouve `citizens`), tri score puis order
  puis titre, extrait contextualisé autour de la 1ʳᵉ occurrence.

### `com.lodygames.rpgquest.panel.web`

- **`DocsPages`** — accueil (`home`) : gros champ de recherche + raccourcis « Comment faire ? »
  + catégories ; résultats de recherche (extraits) ; fiche (`page`) : fil d'Ariane + sommaire
  (titres `##`/`###`, mêmes ancres que `Markdown`) + tags + Markdown rendu + « Source : … ».
  Slug inconnu → page « Fiche introuvable » (jamais une 500).
- **`PanelApp`** — nouvelle route `/docs` (contexte, dispatch `/docs` accueil-ou-recherche vs
  `/docs/<slug>` fiche). `requireSession` + `Permission.DOCS_READ`. Slug borné puis résolu ;
  `/docs/<slug>` inexistant → `404` (page propre), slug malformé (`/`, `..`, majuscules,
  espace…) → `404` avant tout traitement.
- **`Layout`** — entrée **Documentation** (`/docs`) dans le menu ; ~60 lignes de CSS
  (`doc-search`, `doc-cats`, `doc-hit`, `doc-layout` sidebar+contenu, `doc-body` typographie,
  `doc-cmd` + `doc-copy`, `doc-tablewrap`, `doc-callout`) + règles responsive (sidebar sous le
  contenu < 720 px, recherche en colonne).
- **`AgentPages`** — liens contextuels : `/npcs` → « Documentation : créer et configurer un
  PNJ », `/quests` → quêtes/stories, `/players` → reset d'un joueur, `/dialogues` →
  `?q=dialogue`.

### Autorisation

- **`Permission.DOCS_READ`** (nouveau) accordé à **tous** les rôles (`OWNER` via `allOf`,
  `TESTER` / `CONTENT_EDITOR` / `READ_ONLY` explicitement) — « tous les utilisateurs
  authentifiés peuvent lire », permissions fines pour plus tard.

### 9 fiches livrées (`control-panel/src/main/resources/docs/`)

| slug | Catégorie | Contenu |
|---|---|---|
| `pnj-citizens` | PNJ / Citizens | **prioritaire** : `/npc create/select/skin/move/rename/remove`, `/rpgadmin npc tag|untag|info`, nom affiché vs id technique (`Garde` vs `guard`), id canonique, risque d'un mauvais tag, skin par pseudo (+ URL selon Citizens), lien au contenu (NpcDefinition / dialogue `rpgquest:<id>` / giver / `TALK_TO_NPC` / binding), diagnostic (`/npcs`, warnings), séquence type |
| `citizens-commandes` | PNJ / Citizens | référence rapide Citizens `2.0.43` |
| `joueurs-reset` | Joueurs / Reset | `/rpgadmin player resetnew <j> [preview|confirm]`, `confirm` obligatoire, console vs en jeu, online/offline, OP/deop pour tester, page Joueurs du panel |
| `quetes` | Quêtes / Stories | `/rpgadmin quest start|complete|reset`, `/quest admin`, `/rpgadmin story …`, `player variable get|set`, ids `rpgquest:<clé>` |
| `claims` | Claims | parcours acte de propriété (Jo), `/claim …`, `/claim admin resettier1|tp`, principes de test non-OP |
| `wild` | Wild | portail Hub→Wild, Rune / Pierre de rappel, Waystones, reconnexion #87, tests |
| `verygames-deploiement` | VeryGames | `deploy-verygames.sh -y` + `verygames-restart.sh` + `rollback-verygames.sh`, **règle anti-auto-reboot** (`10 auto-reboot in less than 30 minutes … We exit now`) + quoi faire |
| `aws-control-panel-deploiement` | Déploiement / Rollback | `scripts/plugadmin/deploy.sh` + `rollback.sh app`, checks `/health` / 303 / vhosts |
| `rpgquest-commandes` | Administration serveur | référence rapide `/rpgquest`, `/rpgadmin`, `/quest`, `/customitem`, `/dialogue open`… |

## Fichiers créés

- `control-panel/src/main/java/.../panel/docs/DocFrontMatter.java`, `Markdown.java`,
  `DocPage.java`, `DocLibrary.java`, `DocSearchIndex.java`
- `control-panel/src/main/java/.../panel/web/DocsPages.java`
- `control-panel/src/main/resources/docs/_index.txt` + 9 fiches `.md`
- `control-panel/src/test/java/.../panel/docs/DocFrontMatterTest.java`, `MarkdownTest.java`,
  `DocLibraryTest.java`, `DocSearchIndexTest.java`
- `control-panel/src/test/java/.../panel/web/DocsPagesTest.java`
- `docs/claude-reports/2026-09-08_2003_control-panel-centre-documentation-49.md` (ce rapport)

## Fichiers modifiés

- `control-panel/src/main/java/.../panel/web/PanelApp.java`, `Layout.java`, `AgentPages.java`
- `control-panel/src/main/java/.../panel/authz/Permission.java`, `Role.java`
- `docs/control-panel/ROADMAP.md`, `docs/control-panel/SECURITY.md`, `docs/current_state.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations

**Aucune.** Le contenu documentaire n'est jamais écrit en base — index mémoire uniquement, Git
source de vérité.

## Configuration / données

Aucun changement `config.yml` / `control-panel.properties`. Aucun secret exposé (fiches authored,
manifeste fermé).

## Tests automatiques

- **`DocFrontMatterTest`** — front matter inline, tags en liste, sans front matter, bloc non
  fermé, guillemets / clés inconnues.
- **`MarkdownTest`** — titres+ancres, listes (ordonnées, imbriquées), tableau, bloc de code +
  bouton « Copier » + échappement, callout `[!WARNING]`, liens (interne gardé / `https://` gardé
  / `javascript:` & `../` → libellé seul, jamais d'`href`), **`<script>` / `<img onerror>` jamais
  transmis** (rendus en entités), code inline littéral (pas de re-parse), bloc de code avec HTML
  + guillemets sûr en attribut et en corps.
- **`DocLibraryTest`** — les 9 fiches chargent avec titre + slug `[a-z0-9-]` unique ; fiches
  prioritaires présentes ; slug inconnu / `null` / `../secret` → vide sans exception ; la fiche
  PNJ contient bien `/rpgadmin npc tag`, `/npc create`, `/npc skin` ; la fiche VeryGames porte la
  règle anti-auto-reboot.
- **`DocSearchIndexTest`** — `tag npc`, `skin`, `citizens`, `reset joueur`, `quest complete`,
  `story complete`, `verygames`, `auto reboot`, `claim`, `wild`, `rune rappel` → **fiche
  attendue en tête** ; `rollback` → une fiche de déploiement en tête (ambigu VeryGames/AWS,
  accepté) ; requête vide/blanche/null → rien ; ET des termes ; extrait + score ; insensible à
  la casse + tolérant au préfixe.
- **`DocsPagesTest`** (HTTP) — `/docs` & `/docs/<slug>` anonymes → **303** `/login` ; accueil
  authentifié → recherche + catégories + « Comment faire ? » + nav active ; `/docs?q=tag npc` →
  résultat vers `pnj-citizens` ; `skin` / `reset joueur` / `rollback` → fiches attendues ;
  requête sans résultat → « Aucune fiche » ; `/docs/pnj-citizens` → 200, Markdown rendu, bloc
  commande + `data-copy` + `/assets/panel.js` + fil d'Ariane + sommaire ; `/docs/pas-une-fiche`
  → **404** « Fiche introuvable » ; **path traversal** (`/docs/../secret`,
  `/docs/..%2f..%2fetc%2fpasswd`, `/docs/%2e%2e/config`, `/docs/PNJ-Citizens`, `/docs/a%20b`,
  `/docs/_index`) → 404/400, **jamais** de contenu système, **jamais** de 500.

Résultats : `./gradlew :control-panel:test` → **154 tests, 0 échec** (was 121, +33) ; `./gradlew :test` → **1208 tests, 0 échec** (29 ignorés — module plugin non modifié) ; `./gradlew build` → **BUILD SUCCESSFUL**.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (énoncé §17, à faire dans le navigateur authentifié) :

1. Ouvrir **Documentation** dans le menu.
2. Taper `tag npc` → la fiche PNJ apparaît → ouvrir → voir `/rpgadmin npc tag <id>` → **Copier**.
3. Taper `skin` → retrouver l'info skin (`/npc skin <pseudo>`).
4. Taper `reset joueur` → retrouver `/rpgadmin player resetnew <joueur> confirm`.
5. Taper `rollback` → retrouver la procédure VeryGames **ou** AWS.
6. Vérifier le rendu sur mobile (sidebar sous le contenu, recherche pleine largeur).

## Résultat attendu

Entrée **Documentation** visible ; wiki privé (auth obligatoire) ; Markdown rendu proprement ;
recherche fonctionnelle sur les requêtes de l'énoncé ; catégories ; commandes copiables ; fiche
PNJ/Citizens complète + fiches reset/quêtes/VeryGames/rollback/Claims/Wild. **Atteint.**

## Reset / retour à l'état initial

`git revert` du commit suffit (aucune donnée, aucune migration, aucun état serveur).

## Déploiement VeryGames

**Aucun.** Changement **100 % Control Panel** — aucun code plugin, aucun agent, aucun schéma.
Ne pas redéployer ni redémarrer VeryGames.

## Déploiement — AWS Control Panel

```
scripts/plugadmin/deploy.sh
```

Vérifs :
- `curl -s https://plugadmin.lodylands.com/health` → `"panel":"ONLINE"` ;
- `/docs` **anonyme** → 303 vers `/login` ;
- `/docs` **authentifié** → 200, recherche + catégories ;
- `/docs?q=tag+npc` → résultat vers la fiche PNJ ;
- autres vhosts nginx (`dig.lodygames.com`, `lodylands.com`) → 200 ;
- `journalctl -u plugadmin` : aucun `ERROR`.

### Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration à défaire.

## Logs / diagnostic — exécution réelle du 2026-09-08 (~20:10 UTC)

Branche `feat/control-panel-admin-tools` @ `0516a0b`.

- `scripts/plugadmin/deploy.sh` — OK. Release `/opt/plugadmin/releases/20260908-201035` ;
  `systemctl restart plugadmin` → `active (running)` ; `event=panel_started port=8090`.
- Le jar déployé embarque **9** fiches `docs/*.md` + `docs/_index.txt`.
- `/health` public **ONLINE** ×3.
- **`/docs` anonyme → 303** vers `/login` ; **`/docs/pnj-citizens` anonyme → 303**.
- Autres vhosts nginx : `dig.lodygames.com` → **200**, `lodylands.com` → **200** (inchangés).
- `journalctl -u plugadmin` depuis le déploiement : **0 ligne `ERROR` / `Exception` / `SEVERE`**.
- **Navigateur authentifié** (ouvrir Documentation, `tag npc`, `skin`, `reset joueur`,
  `rollback`, copier une commande, rendu mobile) : `PENDING MANUAL VALIDATION` — non réalisable
  depuis le périmètre (identifiants owner). Couvert par `DocsPagesTest` (flux complet avec un
  compte owner de test).

## Documentation mise à jour

- `docs/control-panel/ROADMAP.md` : puce Étape 3 « Centre de documentation `/docs` (#49) — MVP »
  + reste V2.
- `docs/control-panel/SECURITY.md` : section « Centre de documentation `/docs` (issue #49) »
  (accès, source fermée, pas de chemin navigateur, rendu Markdown sûr) + `DOCS_READ` dans la
  liste des permissions.
- `docs/current_state.md` : puce « Centre de documentation `/docs` — MVP (issue #49) ».
- `docs/claude-reports/README.md` : ligne d'index.

## Limitations / travail restant (V2 de #49)

- **Édition depuis le navigateur** (Markdown), historique, prévisualisation — hors scope MVP.
- **Permissions fines par rôle** (lecture par catégorie) — `DOCS_READ` global pour l'instant.
- **Indexation de l'arborescence `docs/` du dépôt** — le MVP ne sert qu'un ensemble curé
  bundlé ; exposer `docs/` demanderait une whitelist par fichier + un filtrage secrets.
- **Dernier commit / date de modif par fiche** — nécessite l'accès Git au runtime (non présent
  sur AWS pour le Control Panel) ; « Source : `<chemin>` » est affiché à défaut.
- **Recherche** : index en mémoire, reconstruit au démarrage. Suffisant sur ce volume (9
  fiches) ; pas de stemming FR, pas de fuzzy — préfixe seulement.
- **Ancre `rollback` ambiguë** entre VeryGames et AWS : les deux fiches remontent, l'ordre
  dépend du score ; acceptable (l'énoncé demande « VeryGames/AWS »).

## Prochaine étape suggérée

1. Déployer AWS (`scripts/plugadmin/deploy.sh`) + valider dans le navigateur (§17).
2. Ajouter les fiches manquantes au fil de l'usage (dialogues dédiée, économie, backpacks,
   flatten…) — il suffit d'un `.md` + une ligne dans `_index.txt`.
3. V2 : dernier commit Git par fiche, puis édition guidée.
