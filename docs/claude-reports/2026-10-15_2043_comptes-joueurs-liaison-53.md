# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-15
* Heure : 20:43
* Sujet : Issue #53 — comptes joueurs du portail et liaison sécurisée compte ↔ UUID Minecraft par code en jeu
* Statut : DONE (livré désactivé ; ouverture et validation navigateur + jeu = propriétaire)
* Branche Git : feature/53-comptes-joueurs-liaison (PR #440 fusionnée dans chore/257-ci-github-actions → e363a03) ; rapport : docs/53-rapport-comptes
* Commit actuel si disponible : e363a03
* Début de la tâche : 2026-10-15 20:09:10
* Fin de la tâche : 2026-10-15 20:43:35
* Durée totale : 00:34:25

## Demande
Session autonome lqorch, ticket #53 : concevoir et réaliser le socle d'identité joueur du portail
https://lodyquests.lodylands.com — compte web, authentification sûre, liaison au compte Minecraft
par preuve en jeu, dissociation contrôlée, séparation stricte d'avec PlugAdmin, anti-abus, sans
écriture SQL gameplay, sans exposer de données joueur (#55 non tranché).

## Analyse
- Existant : `web-api` (portail, JDK HttpServer, sqlite-jdbc déjà présent pour la boutique),
  `privacy.AgePolicy` / `ParentalConsentStatus` (#55, fermés par défaut), design §2 de
  WEB_PORTAL.md (code 8 caractères, 10 min). Le portail est sur AWS, le serveur Minecraft sur
  VeryGames : la preuve doit traverser Internet. Le canal « fichier d'échange » du design n'existe
  pas (#421 non fait) ; le plugin sait déjà appeler le web-api en HTTP avec jeton (StoreClient) et
  lire une config hors Git (agent PlugAdmin). Choix : appel HTTPS sortant du plugin vers
  `POST /api/link/confirm`, jeton dédié, réponse immédiate au joueur.
- Pas d'e-mail (aucun prestataire décidé, minimisation pour les mineurs) : identifiant + mot de
  passe ; récupération future par nouvelle preuve en jeu.

## Travail effectué
- Portail, package `webapi.account` : `AccountStore` (accounts.db, migrations user_version, accès
  sérialisé, consommation de code en une transaction `UPDATE … RETURNING`), `AccountService`
  (inscription soumise à AgePolicy — âge/pays non conservés, compte PENDING limité ; connexion
  PBKDF2-SHA256 600 000 it. avec leurre à coût constant ; limites : 10 échecs de connexion par IP
  ou identifiant / 15 min, 5 inscriptions par IP / h, 5 codes par compte / h, 5 preuves ratées par
  UUID / 15 min ; un code neuf révoque le précédent), `PasswordHasher`, `SecureTokens` (empreintes
  SHA-256 seules stockées pour sessions et codes), `AttemptLimiter`, `AccountPagesHandler`
  (/compte : inscription, connexion, code, dissociation avec mot de passe, déconnexion,
  historique ; CSRF session + double soumission `lq_form`, Origin/Sec-Fetch-Site, no-store,
  cookies HttpOnly/Secure/SameSite), `LinkConfirmHandler` (jeton `RPGQUEST_PORTAL_LINK_TOKEN`,
  distinct du jeton général). Config `accounts-*`, désactivé par défaut. Lien « Mon compte » dans le menu.
- Plugin, package `web.link` : `/lien <code>` (alias `/link`, `rpgquest.portal.link`), UUID/pseudo
  de l'émetteur seulement, `PortalLinkConfigLoader` (portal-link.properties hors Git, env en
  surcharge, fail-closed, HTTPS), `PortalLinkClient` (timeouts, pas de redirection, toute panne →
  message simple), `LinkAttemptGuard` (1 essai en vol, 5 s). Code et jeton jamais journalisés.
- Infra : bloc nginx `location = /api/link/confirm` (POST seul), `deploy.sh` copie accounts.db
  service arrêté dans `/opt/lodyquests-portal/backups` (10 gardées), exemples sans secret, .gitignore.
- Correctif en cours de session : limite d'inscriptions par IP (chaque inscription coûte un hachage).

## Fichiers créés
- web-api/src/main/java/com/lodygames/rpgquest/webapi/account/{AccountSettings,PasswordHasher,SecureTokens,AttemptLimiter,LinkOutcome,AccountStore,AccountService,Cookies,LinkConfirmHandler,AccountPagesHandler}.java
- web-api/src/test/java/com/lodygames/rpgquest/webapi/account/{MutableClock,PasswordHasherTest,SecureTokensTest,AccountServiceTest,AccountHttpTest,AccountSettingsLoaderTest}.java
- src/main/java/com/lodygames/rpgquest/web/link/{PortalLinkConfig,PortalLinkConfigLoader,LinkReply,PortalLinkClient,LinkAttemptGuard,LinkCommand}.java
- src/test/java/com/lodygames/rpgquest/web/link/{PortalLinkConfigLoaderTest,LinkAttemptGuardTest,PortalLinkClientTest}.java
- scripts/portal-link.properties.example

## Fichiers modifiés
- web-api : WebApiConfig, WebApiConfigLoader, WebApiMain, HttpServerBootstrap, site/PageLayout
- plugin : bootstrap/RPGQuestBootstrap, resources/plugin.yml, test RPGQuestPluginTest
- scripts/web-portal : deploy.sh, paths.sh, nginx-lodyquests-portal.conf, portal.env.example, web-api.properties.example ; .gitignore
- docs : WEB_PORTAL.md, WEB_API.md, PRIVACY.md, deployment/WEB_PORTAL_AWS.md, deployment/SERVER_CHANGELOG.md, RPGQUEST_BIBLE.md, MANUAL_TEST_PLAN.md (TC-326)

## Base de données / migrations
Aucune migration de `data.db`. Nouvelle base `accounts.db` du portail (v1 : accounts, sessions,
link_challenges, minecraft_links, link_audit), créée seulement si `accounts-enabled=true` —
inexistante en production aujourd'hui.

## Configuration / données
- Portail : `accounts-enabled=false` (défaut, inchangé dans /etc) ; clés `accounts-database-file`,
  `accounts-session-ttl-hours` (168), `accounts-link-code-ttl-minutes` (10) ; secret
  `RPGQUEST_PORTAL_LINK_TOKEN` (non créé).
- Plugin : `plugins/RPGQuest/portal-link.properties` (non créé sur DEV) ; aucune clé config.yml.

## Tests automatiques
- `:web-api:test` complet : vert (ajouts : AccountServiceTest 16, AccountHttpTest 6, PasswordHasherTest 4, SecureTokensTest 3, AccountSettingsLoaderTest 3).
- Plugin ciblé : web.link.* 9, RPGQuestPluginTest 6 (dont /lien inerte sans fichier), RpgPermissionsMatrixTest 6 : verts.
- CI GitHub complète verte sur 424d057 (PR) et e363a03 (fusion, run 38786825578).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — TC-326 (docs/MANUAL_TEST_PLAN.md) : partie A (état livré) puis
partie B après ouverture par le propriétaire. Aucun test navigateur ou Minecraft avec un vrai
client n'a été fait dans cette session.

## Résultat attendu
Aujourd'hui : /compte « pas encore ouverts » ; /lien répond « pas activée ». Après ouverture : un
compte web ne se relie qu'au compte Minecraft qui a tapé le code, une seule fois, avant expiration.

## Reset / retour à l'état initial
Désactivé par défaut ; supprimer `accounts.db` (si créée) remet les comptes à zéro sans toucher le jeu.

## Déploiement VeryGames
### À transférer
JAR `e363a03` — **fait** (sauvegarde `rpgquest-20261015T184135Z-predeploy.jar`).
### Ne PAS transférer/altérer
data.db, config.yml ; ne pas créer portal-link.properties avant la décision du propriétaire.
### Redémarrage requis
Oui — fait par RCON à 20:41, serveur revenu en ligne, `lien`/`link` enregistrées.
### Migration automatique
Aucune.

## Rollback
JAR : `scripts/rollback-verygames.sh --latest` ; portail : `scripts/web-portal/rollback.sh app` ;
nginx : copie `/opt/lodyquests-portal/releases/nginx-lodyquests-portal.pre-53-20261015-202439.bak`,
`nginx -t`, `reload`.

## Logs / diagnostic
`journalctl -u lodyquests-portal` (« comptes=désactivés/fermés/ouverts », avertissement si jeton de
liaison absent) ; plugin : « Liaison portail (/lien) … » au démarrage, « Liaison portail : <résultat>
pour <pseudo> » par essai — jamais le code ni le jeton.

## Documentation mise à jour
WEB_PORTAL.md §2 (parcours, tableau sécurité, mise en route, évolutions), WEB_API.md, PRIVACY.md
(registre réel, cookies, droits), WEB_PORTAL_AWS.md (vérifications), SERVER_CHANGELOG.md,
RPGQUEST_BIBLE.md (/lien), MANUAL_TEST_PLAN.md (TC-326). Aucune page docs-site n'existe pour le
portail : non modifié.

## Limitations / travail restant
- Avant ouverture publique : export et suppression de compte, changement de mot de passe,
  demande de consentement parental, durée de conservation du journal (PRIVACY.md §4/§7).
- Compteurs anti-abus en mémoire (remis à zéro au redémarrage du service).
- Skin non affiché (éviter un service tiers).
- PrivacyPageHandler ne décrit les comptes que d'après la politique d'âge, pas `accounts-enabled`.

## Prochaine étape suggérée
Décision du propriétaire (#55) puis ouverture selon WEB_PORTAL.md §2 « Mise en route » et TC-326 ;
ensuite export/suppression de compte ; #421 (acheminement de l'instantané).
