# Confidentialité, RGPD et joueurs mineurs — portail LodyQuests (issue #55)

Document de cadrage. Il sépare trois choses qui ne doivent jamais se confondre :

- **Fait** : ce que le code fait aujourd'hui, testé automatiquement ;
- **Proposé** : une valeur ou une règle suggérée pour aider la décision, **sans valeur juridique** ;
- **Décision du propriétaire** : ce qui reste à trancher, après une revue juridique adaptée aux
  pays réellement visés. Rien de ce qui suit n'est un avis juridique.

> **Revue juridique obligatoire avant toute ouverture publique** (comptes, publication de pseudos,
> concours). Tant qu'elle n'a pas eu lieu, les garde-fous ci-dessous restent dans leur position
> fermée : `player-data-public=false`, `web-export.enabled=false`, aucune région de compte configurée.

## 1. Garde-fous en place (fait)

| Garde-fou | Où | Effet |
| --- | --- | --- |
| Profils **privés par défaut** | plugin, `web-export.profile-visibility-default: private` | Un joueur qui n'a jamais tapé `/profile web public` n'apparaît **nulle part** dans l'instantané : classements, profils, liste des connectés. |
| Choix du joueur, toujours prioritaire | plugin, `/profile web public\|prive` (variable `WEB_PROFILE_HIDDEN` par UUID) | `public` = accord explicite (`false`), `prive` = retrait (`true`). Une valeur inconnue suit le défaut, jamais plus permissive. |
| Filtre appliqué **à l'export** | plugin, `player.PublicProfilePolicy` dans les requêtes SQL | Aucune donnée d'un joueur non visible n'atteint le fichier : le portail ne peut pas la divulguer. |
| Jamais d'UUID, d'IP, de position, d'inventaire, de date de connexion dans l'instantané | plugin, `web.WebSnapshotWriter` | Testé (`PublicPortalExportTest`). |
| **Liste blanche** côté portail | web-api, `privacy.PublicPlayerData` | `/api/profiles`, `/api/leaderboards`, `/api/players` ne renvoient que `name`, `skills`, `questsCompleted`, `totalXp`, `value`. Un champ en trop (UUID, e-mail, âge, IP) est ignoré. |
| Aucun pseudo publié | web-api, `player-data-public=false` | Pages et API ne montrent aucun pseudo, même si un instantané arrive. |
| Politique d'âge **configurable**, fermée par défaut | web-api, `privacy.AgePolicy` | Sans région configurée, aucun compte ne peut s'ouvrir. Aucune règle « Europe = X ans » codée en dur. |
| Consentement parental représentable | web-api, `privacy.ParentalConsentStatus` | `NOT_REQUIRED / PENDING / APPROVED / REVOKED / EXPIRED` ; seul `NOT_REQUIRED` ou `APPROVED` débloque les fonctions personnelles. |
| Page `/confidentialite` | web-api | Décrit le comportement réel ; ses phrases d'état suivent la configuration (pseudos publiés ou non, comptes ouverts ou non). |
| Journaux sans secret | web-api, `http.AccessLogger` | Méthode, chemin (sans paramètres de requête), IP, statut, durée ; ni jeton, ni en-tête `Authorization`, ni corps de requête. |
| Aucune analyse d'audience, aucun traceur, aucun cookie | web-api | Le portail ne pose aucun cookie aujourd'hui. |

## 2. Registre des données

Pour chaque donnée : pourquoi, qui la voit, durée de conservation, justification, correction /
export / suppression. Les colonnes « durée » et « justification » sont **proposées** ; la base
juridique exacte est une décision du propriétaire après revue.

### 2.1 Données traitées aujourd'hui (portail sans compte)

| Donnée | Pourquoi | Qui la voit | Durée | Justification proposée | Correction / suppression |
| --- | --- | --- | --- | --- | --- |
| Pseudo Minecraft d'un joueur **ayant accepté** | classements, profil public | tout visiteur (si `player-data-public=true`) | tant que le joueur reste public ; disparaît à l'export suivant après `/profile web prive` | consentement (accord explicite en jeu) | `/profile web prive` ; le pseudo suit Minecraft |
| XP par piste, nombre de quêtes terminées | classements | idem | idem | idem | idem |
| Nombre de joueurs connectés | statut du serveur | tout visiteur | instantané courant | intérêt légitime (statistique anonyme) | — |
| Adresse IP du visiteur | limite de fréquence (en mémoire), journal d'accès du portail (journald) et de nginx | exploitant seulement | mémoire : 1 min ; journaux : **à décider** (proposé : 14 jours) | intérêt légitime (sécurité) | rotation des journaux |
| Choix `/profile web` | appliquer la préférence | exploitant (base du jeu) | tant que le compte Minecraft existe sur le serveur | nécessaire à l'exécution du choix | modifiable à tout moment en jeu |

### 2.2 Données des comptes (#53) — réalisé, désactivé sur l'instance publique

Base dédiée `accounts.db` du portail (ni `data.db`, ni PlugAdmin). Elle n'existe pas tant que
`accounts-enabled=false`.

| Donnée | Pourquoi | Qui la voit | Durée | Correction / export / suppression |
| --- | --- | --- | --- | --- |
| Identifiant interne du compte (UUID aléatoire) | clé technique | personne en public | vie du compte | supprimé avec le compte |
| Identifiant de connexion choisi (3–32 caractères ; ni vrai nom ni pseudo conseillés) | connexion | le joueur, l'exploitant | vie du compte | export JSON et suppression immédiate depuis `/compte` (mot de passe exigé) |
| Empreinte du mot de passe (PBKDF2-HMAC-SHA256, 600 000 itérations, sel aléatoire) | connexion | personne | vie du compte | jamais exportée ; changement depuis `/compte` (ancien exigé) ; effacée avec le compte |
| Statut de consentement (`NOT_REQUIRED`, `PENDING`…) | appliquer la politique d'âge | l'exploitant | vie du compte | l'âge et le pays déclarés **ne sont pas conservés**, seul le statut l'est |
| UUID Minecraft lié | relier compte ↔ joueur (preuve en jeu) | le joueur (indirectement), l'exploitant ; **jamais public** | vie de la liaison | dissociation depuis le compte (mot de passe exigé) |
| Pseudo Minecraft au moment de la liaison | affichage sur la page du compte | le joueur, l'exploitant | vie de la liaison | dissociation |
| Sessions : **empreinte** SHA-256 du jeton + jeton anti-CSRF | rester connecté | personne | 7 jours (absolu, `accounts-session-ttl-hours`) ; purge à expiration | déconnexion |
| Codes de liaison : **empreinte** SHA-256 | prouver la possession du compte Minecraft | personne | 10 minutes, usage unique ; purgés une heure après expiration | un nouveau code annule le précédent |
| Progression personnelle reçue du serveur de jeu (XP et niveaux par piste, quêtes terminées, valeur / rang / effectif par classement publié ; aucun autre pseudo, aucun UUID) | afficher sa progression sur `/compte` | **le joueur seul** (connecté) et l'exploitant ; jamais publique | vie de la liaison ; remplacée à chaque envoi | effacée à la dissociation (et avec le compte) ; incluse dans l'export JSON |
| Journal des liaisons (liaison, dissociation, refus ; pseudo, UUID, date) | audit, détection d'abus | le joueur (10 derniers) et l'exploitant | **à décider** (proposé : 1 an) — pas encore de purge automatique | export JSON (complet) ; effacé avec le compte |
| Compteurs d'échecs (connexion par IP et identifiant, preuves par UUID) | anti-abus | personne | mémoire seulement, 15 minutes | — |

Aucun e-mail n'est demandé : pas de prestataire choisi, et la récupération de compte pourra se
faire par une nouvelle preuve en jeu (voir [WEB_PORTAL.md §2](WEB_PORTAL.md)).

### 2.3 Données à ne pas collecter

Nom réel, adresse postale, école, téléphone, date de naissance affichée ou publique, pièce
d'identité, photo, toute donnée « au cas où ». Une nouvelle donnée n'entre dans ce registre qu'avec
sa ligne complète (pourquoi, qui, durée, justification, suppression).

## 3. Âge et consentement parental

Configuration dans `web-api.properties` (lue par `privacy.AgePolicy`) :

```properties
# Seuil par pays desservi (code ISO à deux lettres). Aucune ligne = comptes FERMÉS.
#accounts.age-threshold.BE=13
#accounts.age-threshold.FR=15
# Sous le seuil : parental-consent (par défaut) ou refuse.
#accounts.below-threshold=parental-consent
# Pays inconnu ou non desservi : refuse (par défaut) ou parental-consent.
#accounts.unknown-region=refuse
```

Les valeurs commentées sont **des exemples de format, pas une décision** : le seuil applicable
dépend du pays et du traitement concerné, et doit être fixé par le propriétaire après revue.

Règles du code :

- un âge sous le seuil ou une région inconnue ne peut **jamais** donner `ALLOWED` (refusé au
  chargement) ;
- l'âge déclaré sert au calcul puis est oublié ; seul le résultat est destiné à être conservé ;
- un compte `PENDING`, `REVOKED` ou `EXPIRED` reste **limité** : pas de profil public, pas de
  liaison Minecraft, pas d'inscription à un événement ;
- un consentement retiré ou expiré ne redevient jamais `APPROVED` sans nouvelle demande
  (`PENDING`) ;
- l'identité du parent n'est jamais montrée à un autre joueur ;
- aucun paiement de mineur n'est conçu ici (hors périmètre).

Vérification d'âge : déclaration simple, sans pièce d'identité, tant qu'aucune obligation plus
forte n'est identifiée par la revue juridique.

## 4. Droits des joueurs (#53 : en partie réalisé)

Fait : consultation (page `/compte` : liaison, pseudo, date, 10 dernières opérations, progression
personnelle du joueur relié),
**dissociation** Minecraft (mot de passe exigé, journalisée), et depuis le lot 7 (23/10/2026) :
**changement de mot de passe** (ancien exigé, autres sessions fermées), **export** JSON de toutes
ses données (`/compte/export` : compte, liaison, progression, historique complet, sessions — jamais
de mot de passe ni de jeton), **suppression** du compte (`/compte/supprimer` : case de
confirmation + mot de passe, effacement **immédiat et définitif** du compte, des sessions, de la
liaison, de la progression et du journal de ses liaisons). **Rien n'est conservé après
suppression** ; si une durée de conservation (sécurité, litige) est décidée un jour, elle sera
listée ici. Reste à réaliser avant l'ouverture : gestion et **retrait** des consentements
(consentement parental). Avant les comptes, la seule donnée publiable est le pseudo d'un joueur ayant accepté, et
`/profile web prive` suffit à le retirer.

## 5. Cookies, analyse d'audience, prestataires

- **Cookies** : aucun tant que les comptes sont désactivés. Comptes activés : deux cookies
  strictement nécessaires, `HttpOnly` et `Secure` — `lq_session` (session, `SameSite=Lax`,
  7 jours) et `lq_form` (anti-CSRF des formulaires de connexion et d'inscription,
  `SameSite=Strict`, 2 heures).
  Aucun traceur publicitaire en V1. Un outil d'analyse ne sera ajouté qu'après inventaire et, si
  requis, consentement.
- **Prestataires** (à compléter et valider avant ouverture) : hébergement AWS (instance du
  portail et de PlugAdmin) ; certificat Let's Encrypt ; e-mail (aucun aujourd'hui, à choisir avec
  #53) ; aucun CDN, aucune authentification externe, aucun paiement.

## 6. Sécurité

TLS (Let's Encrypt, nginx) ; écoute locale `127.0.0.1` ; `/api/` fermé par nginx et protégé par
jeton ; secrets hors Git (`/etc/lodyquests-portal/portal.env`) ; journaux sans secret ; service
sous utilisateur dédié avec plafond mémoire. **À documenter avant production** : procédure de
gestion d'incident (fuite, compte compromis). Base des comptes : copiée à chaque déploiement,
service arrêté, dans `/opt/lodyquests-portal/backups/` (root, 700, 10 copies) ; mots de passe,
jetons de session et codes n'y figurent qu'en empreinte.

## 7. Décisions du propriétaire avant ouverture

1. Pays desservis et seuils d'âge (`accounts.age-threshold.*`), comportement sous le seuil et en
   pays inconnu.
2. Garder `profile-visibility-default: private` (recommandé) ou passer à `public`.
3. Publier les pseudos : `player-data-public=true` sur le portail **et** `web-export.enabled=true`
   sur le serveur, une fois #421 (acheminement) prêt.
4. Durées de conservation proposées au §2 (journaux nginx et journald, sessions, journal de sécurité, preuve de
   consentement après retrait, archives d'événements).
5. Prestataire e-mail et moyen de recueillir le consentement parental.
6. Textes à publier après revue : politique de confidentialité, conditions d'utilisation,
   information adaptée aux mineurs, règlement de chaque concours.
