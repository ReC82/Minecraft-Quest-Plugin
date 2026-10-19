# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 21:19
* Sujet : #506 — vérifier que les tests CI instables DeedClaimListenerTest, QuestHeartRewardIntegrationTest et NpcsCatalogTest ne retombent plus
* Statut : DONE
* Branche Git : docs/506-verification-ci (depuis chore/257-ci-github-actions `5ccf698`)
* Commit actuel si disponible : voir PR documentaire
* Début de la tâche : 2026-10-19 21:19:06
* Fin de la tâche : 2026-10-19 21:21:59
* Durée totale : 00:02:53

## Demande
Reprise de #506 (session lqorch) : une session du 17/10 a déjà corrigé les trois causes (PR #511, merge `d6fbcef`).
Vérifier dans l'historique GitHub Actions depuis cette date si ces tests (ou d'autres) échouent encore
de façon intermittente ; corriger la cause si oui, sinon le prouver et documenter.

## Analyse
Le correctif #511 a été fusionné le 2026-10-17 à 13:41 UTC. Relevé de **toutes** les exécutions du workflow
`CI` créées depuis (API `actions/runs?created=>=2026-10-17T13:41:00Z`, toutes branches) :

| Résultat | Nombre |
|---|---|
| success | 82 |
| failure | 13 |
| cancelled | 4 (exécution remplacée par un push plus récent) |
| **Total** | **99** |

`run_attempt` vaut 1 pour les 99 exécutions : **aucune relance manuelle** n'a « verdi » un échec.

Les 13 échecs portent sur 7 commits ; chaque fois le job en échec est nommé ci-dessous, et les deux
exécutions (push + pull_request) d'un même commit échouent sur **le même test** — échec reproductible,
donc défaut réel du commit en cours de développement, corrigé par le commit suivant de la branche :

| Commit | Branche | Test(s) en échec |
|---|---|---|
| `23862285` (push+PR) | feature/15-quetes-periodiques | `BundledExamplesDriftTest` (control-panel), `QuestJournalServiceTest.periodicQuestsLiveInTheirOwnTab…` (plugin) |
| `0c43acb2` (push) | feature/15-quetes-periodiques | `QuestJournalServiceTest.periodicQuestsLiveInTheirOwnTab…` |
| `789407f8` (push+PR) | feature/177-reward-table-form-ux | `OpsAutoRefreshTest.resultFlagsCarriedByTheUrlAreOneShot` |
| `159b213e` (push+PR) | feature/474-reward-catalog-ux | `CatalogResyncTest.panelScriptCarriesTheSharedReloadOnIdleMechanism`, `OpsAutoRefreshTest.theOnlyPageReloadLeftIsGuarded…` |
| `33c0290b` (push+PR) | fix/214-mob-import-polling | mêmes deux tests que ci-dessus |
| `5906c610` (push+PR) | fix/142-material-search | `EditorDescriptorsTest.everySelectFieldPointsAtAKnownRefDataSource` |
| `bfaf1eee` (push+PR) | feature/128-resync-feedback | `OpsAutoRefreshTest.theOnlyPageReloadLeftIsGuarded…` |

Ces tests (`OpsAutoRefreshTest`, `CatalogResyncTest`) sont des gardes statiques sur le JavaScript du panel :
ils ont échoué quand une branche modifiait `panel.js` sans mettre à jour la garde, puis sont repassés après correction.
Ce n'est pas de l'instabilité.

**Aucun des trois tests de #506 n'apparaît dans un échec depuis le 17/10.** Aucune autre instabilité détectée.

## Travail effectué
- Audit des 99 exécutions CI et des journaux des 13 jobs en échec (`gh api …/actions/jobs/<id>/logs`).
- Vérifié : `d6fbcef` est ancêtre de `chore/257-ci-github-actions` et du JAR DEV déployé `67a556c`
  → le correctif de l'Acte de propriété (`removeItem(asOne())`) est déjà en ligne sur DEV.
- Aucun code modifié (rien à corriger : ne pas faire une 2e solution quand la 1re marche).
- Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne 7h #506 ajoutée ; section « Livraison #128 » de `origin/main`
  (commit `498d52f`) reportée ; les autres modifications de `main` y figuraient déjà (versions plus récentes).

## Fichiers créés
- `docs/claude-reports/2026-10-19_2119_506-verification-tests-instables-ci.md`

## Fichiers modifiés
- `docs/SUIVI_VALIDATIONS_DEV.md`
- `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune.

## Tests automatiques
Aucun lancement local (aucun code changé). Preuve = historique CI ci-dessus (82 exécutions vertes de la suite complète, dont les trois classes).

## Tests manuels à effectuer
Aucun. Effet joueur déjà livré le 17/10 : poser un Acte de propriété n'en retire qu'un seul.

## Résultat attendu
La CI ne retombe plus sur DeedClaimListenerTest, QuestHeartRewardIntegrationTest, NpcsCatalogTest.

## Reset / retour à l'état initial
Sans objet.

## Déploiement VeryGames
### À transférer
Rien (correctif déjà dans le JAR DEV `67a556c`).
### Ne PAS transférer/altérer
Tout.
### Redémarrage requis
Non.
### Migration automatique
Non.

## Rollback
Sans objet (documentation seule).

## Logs / diagnostic
Commandes : `gh api "repos/{owner}/{repo}/actions/runs?created=>=2026-10-17T13:41:00Z" --paginate`,
`gh api repos/{owner}/{repo}/actions/jobs/<id>/logs | grep FAILED`.

## Documentation mise à jour
Registre DEV, index des rapports, ce rapport.

## Limitations / travail restant
Ticket #506 laissé ouvert : fermeture à la discrétion du propriétaire.

## Prochaine étape suggérée
Fermer #506.
