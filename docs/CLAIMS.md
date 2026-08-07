# Claims de terrain

Permet à un joueur de réclamer et protéger un terrain cuboïde éloigné du
village, sans dépendre d'un administrateur. Voir
[docs/ARCHITECTURE.md](ARCHITECTURE.md) (section `claim`) pour le détail
d'implémentation.

## Commandes (`rpgquest.claim`, ouvert à tout joueur)

-   `/claim wand` — outil de sélection (hache en bois marquée par
    PersistentDataContainer, jamais reconnue par son nom — dédiée, distincte
    de l'outil de sélection de zone protégée). Clic gauche = position 1,
    clic droit = position 2.
-   `/claim create <id>` — crée un claim cuboïde depuis la sélection
    courante. Seule sous-commande qui prend un identifiant explicite (rien
    n'existe encore à « ta position actuelle »).
-   `/claim delete` — supprime le claim où tu te trouves (propriétaire
    uniquement).
-   `/claim info` — détail du claim où tu te trouves (propriétaire, monde,
    bornes, nombre de membres, redstone publique).
-   `/claim trust <joueur>` — ajoute un membre de confiance au claim où tu
    te trouves (propriétaire uniquement, joueur cible en ligne).
-   `/claim untrust <joueur>` — retire un membre de confiance.
-   `/claim list` — liste **tes** claims (id, monde), où que tu sois.
-   `/claim flag redstone <true|false>` — autorise ou non les non-membres à
    utiliser boutons/leviers/portes/dalles de pression du claim où tu te
    trouves (propriétaire uniquement). Seule permission réellement
    configurable, voir plus bas.

Toutes les sous-commandes qui agissent sur un claim précis (`delete`,
`info`, `trust`, `untrust`, `flag`) opèrent sur **le claim où tu te
trouves**, jamais sur un id tapé à la main — délibéré, cohérent avec la
mission (qui ne montre un argument que pour `trust`/`untrust <joueur>`).

## Un claim contient

-   un identifiant (choisi à la création, `/claim create <id>`) ;
-   un propriétaire, identifié **par UUID**, jamais par pseudo (un pseudo
    peut changer ; toutes les vérifications de propriété/confiance
    utilisent l'UUID stocké) ;
-   un monde et des bornes cuboïdes ;
-   une liste de membres de confiance (UUID) ;
-   des paramètres de permissions (aujourd'hui : redstone publique
    uniquement).

Persisté en SQLite (`claims`/`claim_members`, migration V7) — contrairement
aux zones protégées (YAML, curées par un administrateur), un claim est créé
et modifié fréquemment par n'importe quel joueur, ce qui correspond mieux à
une base de données qu'à des fichiers à éditer à la main.

## Refus à la création

`/claim create <id>` est rejeté si la sélection :

-   chevauche un claim existant (même monde) ;
-   chevauche une zone protégée (village/safe zone, voir
    [docs/SAFE_ZONE.md](SAFE_ZONE.md)) ;
-   se trouve à moins de `claims.portal-buffer-blocks` (16 par défaut) d'un
    portail existant (voir [docs/TRAVEL.md](TRAVEL.md)) ;
-   dépasse `claims.max-width`/`claims.max-height` (64×384 par défaut) ;
-   ferait dépasser `claims.max-claims-per-player` (3 par défaut) au
    propriétaire.

**Aucun claim invalide ne peut être créé** : toutes ces vérifications sont
faites avant toute écriture en base ; un refus ne modifie rien.

## Protections

| Catégorie | Configurable ? | Comportement |
|---|---|---|
| Blocs (casse/pose) | non | Bloqué pour tout non-membre |
| Conteneurs (coffres, tonneaux, fourneaux...) | non | Bloqué pour tout non-membre |
| Animaux (dégâts) | non | Bloqué pour tout non-membre |
| Armor stands (dégâts et manipulation d'équipement) | non | Bloqué pour tout non-membre |
| Redstone (boutons, leviers, portes/trappes/portails en bois, dalles de pression) | **oui** (`/claim flag redstone`) | Membre uniquement par défaut ; public si activé |
| Explosions | non | Toujours bloquées (creeper, TNT... l'entité se consume normalement, seule la destruction de bloc est empêchée) |
| Pistons traversant la frontière | non | Toujours bloqué dès qu'un claim est concerné d'un côté ou de l'autre |

Seule la redstone est réellement configurable par le propriétaire — toutes
les autres protections sont fixes, conformément à la mission.

## Bypass administrateur

`rpgquest.admin.world` (même permission que le bypass des zones protégées)
exempte l'acteur direct d'une action de la protection d'un claim — jamais
la victime.

## Politique d'extension future liée à la progression

`ClaimService#effectiveMaxWidth`/`effectiveMaxHeight`/`effectiveMaxClaims`
prennent déjà un `Player` en paramètre mais ne retournent aujourd'hui que la
valeur globale de `config.yml` (identique pour tous les joueurs) — un seam
volontaire : une étape ultérieure (XP RPG, étape 19) pourra faire varier
ces limites selon le niveau/la progression du joueur sans changer un seul
appelant. Aucun avantage payant n'est prévu pour cette étape (mission,
point 9) : ces limites ne dépendent d'aucune monnaie ni d'aucun achat.

## Performance

Les claims sont indexés par monde (`ClaimService#claimsInWorld`, reconstruit
à chaque mutation) — même patron que les zones protégées/portails : aucun
balayage de tous les claims de tous les mondes à chaque événement protégé.

## Configuration (`config.yml` → `claims`)

```yaml
claims:
  max-width: 64
  max-height: 384
  max-claims-per-player: 3
  portal-buffer-blocks: 16
```

Validée au démarrage et à `/rpgquest reload` ; une section absente vaut les
valeurs par défaut ci-dessus.

## Tests

Automatisés : `ClaimTest` (invariants du modèle), `ClaimRepositoryTest`
(persistance, propriétaire/non-propriétaire, cascade de suppression des
membres), `ConfigValidatorTest` (section `claims`), `ClaimServiceTest`
(chevauchement claim/zone protégée/portail, taille, nombre maximal,
suppression, confiance/retrait de confiance, monde absent, protection
indépendante du statut en ligne du propriétaire), `ClaimProtectionListenerTest`
(frontière incluse, membre autorisé/non autorisé, conteneurs, redstone
configurable, animaux, explosion externe, piston traversant la frontière,
monde sans claim, suppression).

`PENDING MANUAL VALIDATION` (client Minecraft réel requis) : deux joueurs
voisins, coffres/portes/animaux/redstone réels, TNT dedans/dehors, piston
traversant la limite en jeu, redémarrage complet du serveur (persistance
réelle).
