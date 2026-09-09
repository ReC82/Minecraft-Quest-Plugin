# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 10:05 → 10:18 (heure locale machine, UTC)
* Sujet : Polish / bugfix visuel du Control Panel après validation de la nouvelle UX — titres de fiches `/docs` en double, bloc de code + bouton « Copier » cassés, action « Créer la définition » affichée deux fois dans le détail PNJ (issues #49 / #89)
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `65cfb9a` ; poussé sur `origin/feat/control-panel-admin-tools`
* Début de la tâche : 2026-09-09 10:05:13
* Fin de la tâche : 2026-09-09 10:18:20
* Durée totale : 00:13:07

## Demande

L'utilisateur a validé visuellement la nouvelle UX (#89 généralisée + #49). Reste
des défauts visuels précis à corriger, **sans nouvelle refonte** :

1. **Titres de documentation en double** — plusieurs fiches `/docs` affichent leur
   titre deux fois (ex. « Résoudre les problèmes de PNJ » deux fois ; « Créer et
   configurer un PNJ (Citizens + RPGQuest) » puis « Créer et configurer un PNJ »).
   Auditer front matter / titre passé à `DocsPages` / premier H1 Markdown /
   renderer. Choisir **une** source de titre visible : le titre de fiche sert au
   header, et si le Markdown commence par un H1 identique/équivalent, ne pas le
   rendre une 2ᵉ fois. Ne pas toucher aux H2/H3.
2. **Généraliser** le fix à toutes les fiches embarquées ; aucune fiche ne doit
   avoir deux titres principaux successifs ; ajouter un test de non-régression.
3. **Bloc de code / bouton « Copier »** (fiche « Créer et configurer un PNJ ») :
   un grand rectangle sombre vide à droite, « Copier » perdu dedans. Le bouton
   doit rester dans le bloc, compact, dans l'angle haut-droit, sans créer de 2ᵉ
   zone, sans masquer le texte, sans augmenter la hauteur, sans déborder.
4. **Code block responsive** — desktop : pleine largeur utile, overflow horizontal
   seulement si commande longue ; mobile : scroll horizontal si besoin, bouton
   toujours accessible, aucun overlay, aucune largeur fixe absurde. Auditer le CSS
   du wrapper (`position`, `grid/flex`, `width/height`, `inset`, `z-index`).
5. **Action « Créer la définition » en double** — dans le détail du PNJ Guide, le
   diagnostic « Fiche RPGQuest manquante » propose déjà `[Créer la définition]` +
   `[Comment corriger ?]`, puis la section **Actions** réaffiche
   `[Créer la définition]`. Une action primaire ne doit pas apparaître deux fois
   dans le même détail. Pour `BINDING_NO_DEFINITION`, le diagnostic garde ses deux
   boutons ; la section Actions n'affiche que les **autres** actions, ou disparaît.
6. **Généraliser la règle des CTA** — PNJ / Dialogues / Quêtes / Stories : si un
   diagnostic propose déjà `Corriger maintenant / Créer… / Lier… / Ouvrir…`, ne
   pas répéter ce bouton dans « Actions ». La section « Actions » doit apporter
   quelque chose de différent.
7. **Polish diagnostic** — conserver la structure (titre humain / explication /
   conséquence / à faire / boutons / code technique secondaire). Pas de jargon en
   message principal.
8. **Tests** — un seul titre principal par fiche ; front matter + H1 Markdown =
   pas de doublon ; bouton Copier dans le même wrapper ; pas d'overlay parasite ;
   classes responsive ; `BINDING_NO_DEFINITION` = un seul CTA « Créer la
   définition » ; section Actions masquée si vide ; aucune régression Markdown
   safe/XSS, recherche docs, diagnostics #89, notifications #93. Puis
   `:control-panel:test`, `test`, `build` verts.
9. **Déploiement** Control Panel uniquement (`scripts/plugadmin/deploy.sh`) ;
   vérifier `/docs/...` (titre unique, blocs code propres), `/npcs` (diagnostic
   Guide, un seul bouton, « Comment corriger ? »), + une fiche Dialogues/Quêtes/
   Stories. Ne pas toucher VeryGames, ne pas redémarrer Minecraft.
10. Polish / bugfix uniquement, pas de changement d'architecture. Ne pas fermer
    #49 ni #89, ne rien merger, commit/push sur `feat/control-panel-admin-tools`,
    règles permanentes de fin de tâche.

## Analyse

### 1/2 — Titres en double

`DocsPages.renderPage` construit le corps ainsi :

```java
sb.append("<article class=\"doc-body\">");
sb.append("<h1>").append(Http.esc(p.title())).append("</h1>");   // titre de fiche (front matter)
sb.append(Markdown.render(p.markdown()));                          // corps
```

Or **les 13 fichiers `.md` embarqués commencent par une ligne `# <titre>`**.
`Markdown.render` la transforme en un second `<h1 id="…">`. D'où le doublon —
parfois texte identique, parfois variante (front matter `title:` plus long que le
`# …` du corps → deux titres différents à la suite).

`Markdown.render` n'est appelé **que** depuis `DocsPages.renderPage` ; le corps
(`DocPage.markdown()`) reste la source de vérité (utilisé aussi par la recherche
et le sommaire). Le plus sûr : la page possède **toujours** son `<h1>` de header ;
un `# …` d'ouverture dans le corps est donc **toujours** redondant. On le retire
au rendu, sans jamais toucher `##`/`###` ni un éventuel second `#` plus bas.

### 3/4 — Bloc de code / bouton « Copier »

`Markdown.renderCodeBlock` émettait :

```html
<button ... class="doc-copy" ...><svg class="ic" aria-hidden="true"><use href="#i-copy"></use></svg>Copier</button>
```

Le sprite SVG (`#i-copy`) a été **supprimé lors de la migration Bootstrap #92**
(`Icons.icon()` rend désormais `<i class="bi …">`, `sprite()` neutralisé). Le
`<svg>` sans contenu ni `width`/`height`/`viewBox` prend sa **taille intrinsèque
par défaut de 300×150** dans le bouton `inline-flex` → le « grand rectangle
sombre vide », « Copier » repoussé, hauteur du bloc explosée, débordement.

Aucun autre `<use href>` / `<svg class="ic">` dans le code Java (`grep`).
`panel.js` copie par délégation sur `[data-copy]` — indépendant de l'icône.

### 5/6 — CTA dupliqué

`renderNpcAccordionItem` :

- section **Diagnostics** : `npcFixButton(code, …)` rend un bouton « Corriger
  maintenant » qui déplie `#<slug>-f-create` / `-f-edit` / `-f-link` selon le code.
- section **Actions** : `actionToggle(slug + "-f-create", "Créer la définition", …)`
  etc., inconditionnellement dès qu'une permission d'écriture existe.

Pour `BINDING_NO_DEFINITION` (PNJ sans définition), les deux pointent vers
`-f-create` avec le libellé « Créer la définition » → doublon. Idem potentiel
pour `-f-edit` (DIALOGUE_MISSING/DISABLED/GIVER_NO_DIALOGUE) et `-f-link`
(NOT_LINKED).

Sur `/quests`, `/stories`, `/dialogues`, les diagnostics n'émettent **aucun**
bouton d'action immédiate (`fixHtml = ""`) : la section « Actions » n'y contient
qu'un lien « Modifier … » (éditeur #46) ou le formulaire « Ajouter un nœud »,
toujours distincts. Aucun doublon possible → aucune modification nécessaire, mais
la règle est vérifiée.

## Travail effectué

### `Markdown.java`

- Nouveau `static String stripLeadingH1(String)` (pur, testé) : retire la
  **première** ligne de contenu si — et seulement si — elle est un titre ATX de
  **niveau 1** (`# …` avec espace) en tête du document. Null-safe, conservateur
  (`#pas-un-titre` sans espace ou `## …` → intact ; un `#` plus bas → intact).
- `render()` appelle `stripLeadingH1(src)` avant le découpage en lignes.
- `renderCodeBlock()` : le bouton « Copier » est désormais **texte seul**
  (`title="Copier dans le presse-papiers"`), toujours en tête du wrapper
  `<div class="doc-cmd">`, juste avant `<pre><code>`. Plus de `<svg>`, plus de
  `<use href="#i-copy">`.

### `plugadmin.css`

- `.doc-copy` : `position:absolute; top:7px; right:7px; z-index:2; line-height:1;
  padding:4px 9px; white-space:nowrap` — compact, dans l'angle haut-droit, au-
  dessus du `<pre>` qui défile.
- `.doc-cmd pre` : `padding:14px 72px 14px 16px` (gouttière droite pour que le
  texte ne passe jamais sous le bouton) ; `overflow-x:auto` conservé.
- `.doc-cmd pre code` : `line-height:1.55` ajouté (lisibilité).
- Ancienne règle en double `.codeblock .doc-copy,.doc-cmd .doc-copy{position:
  absolute;top:8px;right:8px}` **supprimée** (`.codeblock` de l'éditeur n'a pas
  de bouton copier ; `.doc-cmd .doc-copy` est couvert par la règle de base).
- Responsive : `.doc-body` a déjà `padding:18px 18px` / `max-width:100%` et
  `.doc-layout` passe en 1 colonne sous 820 px ; la gouttière de 72 px + le
  `overflow-x:auto` suffisent sur mobile (bouton ~62 px).

### `AgentPages.java` — `renderNpcAccordionItem`

- `npcFixButton(...)` → `npcFixTarget(...)` qui renvoie `{targetId, libellé}` ou
  `null`. Le libellé reprend **exactement** celui du toggle de la section Actions
  (`"Créer la définition"`, `"Modifier"`, `"Lier un PNJ Citizens"`) pour permettre
  la déduplication.
- La boucle des diagnostics mémorise les `targetId` déjà exposés par un
  « Corriger maintenant » dans `diagFixTargets`.
- La section **Actions** :
  * les `actionCollapse` (formulaires masqués) sont **toujours** rendus si la
    permission le permet — le bouton du diagnostic doit pouvoir les ouvrir ;
  * les toggles sont d'abord collectés, puis filtrés : ceux dont la cible est
    dans `diagFixTargets` sont retirés ;
  * la section (`detailSection("target", "Actions")` + `<div class="npc-actions">`)
    n'est rendue **que** si au moins un toggle subsiste.

Résultat pour le PNJ Guide (`BINDING_NO_DEFINITION`, sans définition) : le
diagnostic « Fiche RPGQuest manquante » garde `[Créer la définition]` +
`[Comment corriger ?]` ; la section « Actions » **disparaît** (elle ne
contiendrait que ce même bouton) ; le formulaire `#<slug>-f-create` reste présent.

### 7 — Polish diagnostic

Aucune régression : la structure `DiagnosticHelp.render` (titre humain /
explication / « Conséquence : » / « À faire : » / boutons / « Code technique : »)
est inchangée ; le libellé du CTA du diagnostic pour `-f-edit` passe de
« Modifier la fiche » à « Modifier » (aligné sur le toggle, pour la dédup).

## Fichiers créés

* `docs/claude-reports/2026-09-09_1005_polish-docs-titres-codeblock-cta-49-89.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/docs/Markdown.java`
  — `stripLeadingH1()` + appel dans `render()` ; bouton « Copier » sans SVG.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
  — `npcFixButton` → `npcFixTarget` ; déduplication CTA diagnostic ↔ section
  Actions ; section Actions masquée si vide ; formulaires masqués toujours rendus.
* `control-panel/src/main/resources/assets/plugadmin.css`
  — `.doc-copy` / `.doc-cmd pre` repositionnés ; ancienne règle en double retirée.
* `control-panel/src/test/java/.../docs/MarkdownTest.java`
  — `stripLeadingH1` (unitaire + null-safe/conservateur) ; bouton Copier compact
  sans SVG.
* `control-panel/src/test/java/.../web/DocsPagesTest.java`
  — `everySheetShowsItsMainTitleExactlyOnce` (les 13 fiches) ;
  `codeBlockCopyButtonStaysInsideItsBlockWithNoBrokenIconOverlay` (markup + CSS).
* `control-panel/src/test/java/.../web/NpcsCatalogTest.java`
  — `bindingNoDefinitionExposesOneCreateCtaAndHidesTheEmptyActionsSection`.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-09 (AWS uniquement).

## Base de données / migrations

Aucune.

## Configuration / données

Aucune. Aucun nouveau paramètre. Les fiches `.md` sont inchangées (le `# …`
d'ouverture reste dans le fichier source ; il est masqué au rendu).

## Tests automatiques

* `./gradlew :control-panel:test` — **210 tests, 0 échec** (205 + 5 nouveaux :
  2 `MarkdownTest`, 2 `DocsPagesTest`, 1 `NpcsCatalogTest`).
* `./gradlew test` — inchangé (`:test` / `:web-api:test` `UP-TO-DATE`).
* `./gradlew build` — **SUCCESS**.

Non-régression vérifiée par les suites existantes : `MarkdownTest` (XSS / HTML
brut / liens sûrs / code inline littéral), `DocLibraryTest`, `DocSearchIndexTest`,
`DiagnosticHelpTest` (#89), `NotificationsBugfixTest` + `ActionsCenterTest` (#93),
`NpcsCatalogTest` / `QuestsCatalogTest` / `StoriesCatalogTest` / `DialoguesCatalogTest`.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (mot de passe owner non détenu) — en navigateur
authentifié sur `https://plugadmin.lodylands.com` :

1. `/docs/pnj-depannage`, `/docs/pnj-citizens` (et 2-3 autres) : **un seul** titre
   principal, les `##`/`###` toujours présents, le sommaire inchangé.
2. `/docs/pnj-citizens` : chaque bloc de commande = rectangle sombre plein
   largeur, code lisible, **« Copier » compact dans l'angle haut-droit**, aucun
   grand rectangle vide, hauteur normale ; clic « Copier » → « Copié ».
3. Mobile : le bloc scrolle horizontalement si la commande est longue, « Copier »
   reste accessible, aucun overlay.
4. `/npcs` → PNJ Guide : le diagnostic « Fiche RPGQuest manquante » montre
   `[Créer la définition]` + `[Comment corriger ?]` ; **aucune** section
   « Actions » avec un 2ᵉ « Créer la définition » ; « Comment corriger ? » ouvre
   `/docs/pnj-depannage#fiche-rpgquest-manquante` ; « Créer la définition » déplie
   bien le formulaire.
5. Une fiche `/quests` ou `/stories` ou `/dialogues` : diagnostics et section
   Actions cohérents, pas de bouton en double.

## Résultat attendu

Interface « finie » : aucun texte en double, aucun bouton en double, aucun
overlay étrange, aucun composant qui paraît cassé.

## Reset / retour à l'état initial

* Code : `git revert 65cfb9a`.
* Déploiement : `scripts/plugadmin/rollback.sh app` (restaure
  `/opt/plugadmin/releases/20260909-101733`).

## Déploiement VeryGames

### À transférer
Rien. **Aucun changement plugin / agent / monde / donnée Minecraft.**

### Ne PAS transférer/altérer
Le serveur VeryGames (JAR RPGQuest, `data.db`, config, mondes, Citizens). Ne pas
redémarrer Minecraft.

### Redémarrage requis
Uniquement le service `plugadmin` sur AWS (fait par `deploy.sh`).

### Migration automatique
Aucune.

## Déploiement AWS (Control Panel) — effectué

* `scripts/plugadmin/deploy.sh` — release `/opt/plugadmin/releases/20260909-101733`,
  `systemctl restart plugadmin`, health local **ONLINE**.
* Vérifications :
  * `https://plugadmin.lodylands.com/health` → `{"panel":"ONLINE"}` ;
  * `/docs` `/docs/pnj-citizens` `/npcs` `/quests` `/stories` `/dialogues`
    anonymes → **303** vers `/login` ;
  * En-tête **CSP inchangé** ;
  * CSS déployé : `.doc-copy{position:absolute;top:7px;right:7px;z-index:2;…}`,
    `.doc-cmd pre{…padding:14px 72px 14px 16px;overflow-x:auto}`, ancienne règle
    `.codeblock .doc-copy,.doc-cmd .doc-copy` **absente** ;
  * `dig.lodygames.com` → **200**, `lodylands.com` → **200** ;
  * `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique**
    au build (`SHA-256 955d5055…`) ;
  * un `WARNING event=handler_error path=/login java.io.IOException: stream
    closed` = déconnexion client d'un `curl -I` (HEAD), **bénin**, non lié au
    changement.

## Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune
migration à défaire.

## Logs / diagnostic

`journalctl -u plugadmin -f` — démarrage propre (`PanelApp start`, health
`ONLINE`), aucun `ERROR`.

## Documentation mise à jour

* `docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-09, AWS uniquement).
* `docs/control-panel/ROADMAP.md` / `docs/current_state.md` : le comportement
  cible (accordéons, `DiagnosticHelp`, fiches de dépannage) est déjà décrit ; ce
  polish ne change pas l'architecture, pas de nouvelle entrée nécessaire.

## Limitations / travail restant

* Validation navigateur authentifiée : `PENDING MANUAL VALIDATION` (voir « Tests
  manuels »).
* Les fichiers `.md` conservent leur `# <titre>` d'ouverture (masqué au rendu) —
  pas de réécriture de contenu dans ce ticket.
* Une fiche (`pnj-citizens.md`) contient un bloc ```` ``` ```` vide : cosmétique,
  hors périmètre de ce polish (nettoyage de contenu #49).

## Prochaine étape suggérée

* Faire la validation navigateur authentifiée des 5 points ci-dessus.
* Nettoyer le bloc de code vide de `pnj-citizens.md` (contenu #49).
