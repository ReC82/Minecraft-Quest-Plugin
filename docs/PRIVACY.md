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

### 2.2 Données prévues avec les comptes (#53) — non réalisé

| Donnée | Pourquoi | Qui la voit | Durée proposée | Correction / export / suppression |
| --- | --- | --- | --- | --- |
| Identifiant interne du compte | clé technique | personne en public | vie du compte | export ; supprimé avec le compte |
| E-mail (ou identité d'authentification) | connexion, récupération, contact du parent si besoin | le joueur, l'exploitant | vie du compte | modifiable ; export ; supprimé avec le compte |
| Hachage du mot de passe (Argon2id ou PBKDF2 fort) | connexion | personne | vie du compte | changement de mot de passe ; jamais exporté |
| UUID Minecraft lié | relier compte ↔ joueur (preuve en jeu, #53) | le joueur, l'exploitant ; **jamais public** | vie de la liaison | dissociation depuis le compte |
| Dernier pseudo connu | affichage | public **seulement** si le joueur est public | vie de la liaison | suit Minecraft |
| Région (code pays) | appliquer le seuil d'âge du pays | l'exploitant | vie du compte | modifiable ; **pas** stockée si la région desservie est unique (décision) |
| Résultat de la vérification d'âge (`ALLOWED`, `PARENTAL_CONSENT_REQUIRED`) | appliquer la politique | l'exploitant | vie du compte | l'âge déclaré **n'est pas conservé**, seul le résultat l'est |
| Statut de consentement parental + date + moyen | preuve minimale | l'exploitant ; **jamais** les autres joueurs | vie du compte + **à décider** après retrait (proposé : 1 an, preuve) | retrait par le parent ou le joueur |
| Préférences de confidentialité | appliquer les choix | le joueur | vie du compte | modifiables |
| Sessions (cookie `HttpOnly`, `Secure`, `SameSite=Lax`) | rester connecté | personne | proposé : 30 jours glissants | déconnexion ; purge à expiration |
| Codes de liaison en jeu | prouver la possession du compte Minecraft | personne | 10 minutes, usage unique ; **purgés** à expiration | — |
| Journal de sécurité (connexions, liaisons) | détecter un abus | l'exploitant | proposé : 90 jours | purge automatique |
| Inscriptions à un événement (#57) | classer, récompenser | public selon le règlement de l'événement | durée de l'événement + archive (à décider) | retrait avant la fin |

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

## 4. Droits des joueurs (à réaliser avec #53)

Le compte devra offrir : consultation des données principales, correction, **export** (fichier
JSON de ses propres données), **suppression** (désactivation immédiate, effacement après le délai
décidé), **dissociation** Minecraft, gestion et **retrait** des consentements. Les données
conservées après suppression (sécurité, litige, obligation légale) seront listées ici avec leur
durée. Avant les comptes, la seule donnée publiable est le pseudo d'un joueur ayant accepté, et
`/profile web prive` suffit à le retirer.

## 5. Cookies, analyse d'audience, prestataires

- **Cookies** : aucun aujourd'hui. Avec les comptes : un cookie de session strictement nécessaire.
  Aucun traceur publicitaire en V1. Un outil d'analyse ne sera ajouté qu'après inventaire et, si
  requis, consentement.
- **Prestataires** (à compléter et valider avant ouverture) : hébergement AWS (instance du
  portail et de PlugAdmin) ; certificat Let's Encrypt ; e-mail (aucun aujourd'hui, à choisir avec
  #53) ; aucun CDN, aucune authentification externe, aucun paiement.

## 6. Sécurité

TLS (Let's Encrypt, nginx) ; écoute locale `127.0.0.1` ; `/api/` fermé par nginx et protégé par
jeton ; secrets hors Git (`/etc/lodyquests-portal/portal.env`) ; journaux sans secret ; service
sous utilisateur dédié avec plafond mémoire. **À documenter avant production** : procédure de
gestion d'incident (fuite, compte compromis), protection des sauvegardes de la future base des
comptes.

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
