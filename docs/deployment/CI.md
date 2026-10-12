# Intégration continue et validation adaptée (issue #257)

## Pourquoi

Sur la machine de développement AWS (3,8 Go de RAM, qui swappe), la suite complète — trois modules,
environ 3 800 tests — prend 20 à 45 minutes. La rejouer à chaque petite correction, puis encore au
déploiement, coûtait 30 à 60 minutes par correction. Le principe désormais :

| Où | Quoi | Quand |
|---|---|---|
| Machine de développement | **tests ciblés** des modules touchés (`scripts/dev/check.sh`) | à chaque itération |
| GitHub Actions | **suite complète** des trois modules + **artefacts** | à chaque push de code |
| Scripts de déploiement | **validation adaptée** au module déployé, sans rejouer ce qui est prouvé | au déploiement |

## GitHub Actions — `.github/workflows/ci.yml`

Déclenché par tout push et toute pull request qui touche du code, et manuellement
(`workflow_dispatch`). Un push plus récent sur la même branche annule le run en cours.

| Job | Contenu |
|---|---|
| `Tests plugin` / `Tests control-panel` / `Tests web-api` | `./gradlew :test`, `:control-panel:test`, `:web-api:test`, **en parallèle** ; bilan dans le résumé ; rapport HTML en artefact en cas d'échec |
| `Artefacts` | `./gradlew assemble` : JAR du plugin, distributions du Control Panel et du web-api, publiés 30 jours (`rpgquest-<sha>`), avec leurs SHA-256 dans le résumé |
| `Scripts` | `shellcheck` + `scripts/dev/test-validation.sh` (les scripts qui décident de ce qui est revalidé sont testés eux aussi) |
| **`CI OK`** | statut agrégé : vert seulement si **tous** les jobs sont verts. C'est **le** statut que lisent les scripts |

Java 21 (Temurin), Gradle Wrapper du dépôt, cache Gradle (`gradle/actions/setup-gradle`).

**Ce qui ne déclenche pas la CI** : `docs/**`, `docs-site/**`, `.ai/**`, les `*.md` à la racine —
**sauf** `docs/manual-tests/**`, lu par `ManualTestQuestPackTest`. La même règle est codée dans
`val::is_doc_path` (`scripts/lib/validation.sh`) ; les deux listes doivent rester alignées, et
`test-validation.sh` vérifie l'exception.

Aucun secret n'est utilisé par ce workflow : le déploiement reste sur la machine AWS.

## Tests ciblés en local — `scripts/dev/check.sh`

```bash
scripts/dev/check.sh                         # modules touchés depuis l'amont + arbre de travail
scripts/dev/check.sh --base <ref>            # modules touchés depuis <ref>
scripts/dev/check.sh --module panel --tests '*BuildingPlan*'   # une classe, un module
scripts/dev/check.sh --full                  # les trois modules
scripts/dev/check.sh --dry-run               # dire ce qui serait lancé
scripts/dev/check.sh --force                 # ignorer « déjà validé »
```

| Fichier modifié | Tests lancés |
|---|---|
| `src/**`, `docs/manual-tests/**` | plugin (`:test`) |
| `control-panel/**` | panel (`:control-panel:test`) |
| `web-api/**` | web-api (`:web-api:test`) |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `gradlew` | les trois |
| `*.sh` | `bash -n` (syntaxe), pas de Gradle |
| documentation | rien |

**« Déjà validé »** : après un succès sans filtre `--tests`, un marqueur est écrit dans
`~/.cache/rpgquest/validated/<module>/<empreinte>`. L'empreinte est celle du **code** du module dans
Git (arbres de ses dossiers + fichiers de build communs), pas du commit : un commit de documentation
ne l'invalide pas, un commit du panel n'invalide pas le plugin. Un module dont le code a des
modifications non commitées n'est jamais considéré comme validé, et n'écrit aucun marqueur.

## Déploiements DEV

Les deux scripts construisent toujours ce qu'ils déploient **depuis le commit courant**. Seule la
question « faut-il rejouer les tests ? » dépend de ce qui est déjà prouvé.

Le commit dont la CI fait foi est `HEAD`, ou — si les derniers commits ne sont que de la
documentation, sur lesquels la CI ne tourne pas — le dernier commit de code (`val::ci_commit`).

| Statut « CI OK » | `scripts/plugadmin/deploy.sh` (panel) | `scripts/deploy-verygames.sh` (plugin) |
|---|---|---|
| vert | aucun test, `installDist` | aucun test, `./gradlew assemble` |
| **rouge** | **refus** — sauf `--allow-ci-red` | **refus** — sauf `--allow-ci-red` |
| en cours | `--wait-ci` attend (30 min max) ; sinon comme « absent » | idem |
| absent / injoignable | marqueur local du **panel**, sinon `:control-panel:test` | marqueur local du **plugin**, sinon `:test` |

Garde-fous qui ne changent pas : arbre propre exigé (le panel vérifie `control-panel/` et les
fichiers de build ; le plugin, tout l'arbre), sauvegarde datée, transfert atomique, rétention.
Une CI injoignable (pas de `gh`, pas de réseau) n'est **jamais** lue comme un vert.

**Traçabilité** : le récapitulatif de `deploy-verygames.sh` affiche la ligne « validation : … » ;
`deploy.sh` écrit `/opt/plugadmin/app/DEPLOYED` (commit, branche, date, preuve de validation).
`--allow-ci-red` y apparaît en clair.

`deploy-verygames.sh --local-validation` ignore la CI et rejoue les tests du plugin.
