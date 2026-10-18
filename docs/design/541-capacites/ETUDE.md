# #541 — Audit technique et maquettes : bibliothèque de capacités composables (EPIC #540)

> Étude et maquettes uniquement. Aucun code moteur, aucune capacité nouvelle, aucun déploiement.
> **STOP : le propriétaire doit valider la présentation (§3) et l'architecture (§2, décisions §4) avant #542.**
> Audit fait sur `chore/257-ci-github-actions` @ `303231d` (refonte /mobs #169 incluse, PR #557).

Maquettes : `catalogue.html`, `fiche.html`, `profil.html`, `ajout.html` (ce dossier, à ouvrir dans un
navigateur depuis le dépôt) ; captures 1280/390 px dans `docs/claude-reports/assets/541-capacites/`.

---

## 1. État des lieux (ce qui existe réellement)

### 1.1 Correction de l'hypothèse de départ

Le ticket parle de « deux capacités livrées (ENRAGED, SUMMON_ON_DAMAGE) ». Le moteur en compte en fait
**cinq** : trois plus anciennes (étape 18 / #190) et les deux de #173. Seules les deux de #173 sont
éditables dans le formulaire PlugAdmin ; les trois autres ne passent que par export / IA / import (#214).

| Type (`type:` YAML) | Modèle (record) | Exécution | Déclencheur réel | Paramètres YAML | État persistant | Profil livré |
|---|---|---|---|---|---|---|
| `STRONGER_EXPLOSION` | `StrongerExplosionAbility` | `StrongerExplosionAbilityListener` | `ExplosionPrimeEvent` | `radius-multiplier` | aucun | `red_creeper` |
| `EXPLOSIVE_ON_ATTACK` | `ExplosiveOnAttackAbility` | `ExplosiveOnAttackAbilityService` (tâche 1 s) | joueur à portée (balayage) + poursuite `Pathfinder` | `power`, `set-fire`, `trigger-range-blocks` | aucun | `creeper_pig` |
| `SPLIT_ON_HIT` | `SplitOnHitAbility` | `SplitOnHitAbilityListener` | `EntityDamageEvent` non mortel | `max-depth`, `max-children-per-hit`, `max-alive-per-parent` (déf. 2) | PDC `special-mob-split-depth`, `special-mob-split-parent` | `splitting_zombie` |
| `ENRAGED` (#173) | `EnragedAbility` | `EnragedAbilityService` (tâche 1 s) | vie ≤ seuil (balayage) | `health-fraction`, `speed-multiplier`, `damage-multiplier` | PDC `special-mob-enraged` + attributs de base multipliés (persistés avec l'entité) | aucun livré (profils DEV) |
| `SUMMON_ON_DAMAGE` (#173) | `SummonOnDamageAbility` | `SummonOnDamageAbilityListener` | `EntityDamageEvent` effectif (`finalDamage > 0`) | `summon-entity-type`, `amount`, `chance`, `cooldown-seconds`, `max-alive` | PDC `special-mob-summon-owner` sur chaque renfort ; délai en mémoire | aucun livré |

Fichiers : `src/main/java/com/lodygames/rpgquest/mob/model/*Ability.java`, `mob/ability/*`,
câblage `bootstrap/RPGQuestBootstrap.java` l. 536-540.

### 1.2 Où une capacité est « définie » aujourd'hui (8 endroits par type)

1. `MobAbilityType` (enum) ; 2. record + `permits` de l'interface scellée `MobAbility` ;
3. `SpecialMobDefinitionParser.parseAbility` (switch, validation, messages) ;
4. `SpecialMobDefinitionYaml.appendAbilities` (rendu disque, ordre des clés) ;
5. `SpecialMobSchema.abilityFields` / `abilityDoc` (contrat #214 : modèle commenté, clés connues, invite IA) ;
6. `SpecialMobImport.unknownKeys` / `compatibility` (refus des clés inconnues, avertissements) ;
7. `BukkitAgentActions.toSummary` (résumé lisible + champs plats `enraged*` / `summon*` envoyés au panel) ;
8. un listener ou service dédié + son enregistrement dans le bootstrap.

Côté PlugAdmin : `AgentPages.mobDefForm` (deux `<details>` codés en dur, 8 champs `enraged_*`/`summon_*`),
`AgentActionCatalog` (validation dupliquée des mêmes champs), `MobRandomProfile`, `MobAiPrompt`.

Les `switch` exhaustifs (interface scellée) garantissent qu'un oubli ne compile pas : bonne discipline à garder.
Mais le **coût d'ajout** (8 fichiers plugin + 2-4 panel, formulaire codé à la main) est exactement ce que #230 et
#540 veulent supprimer.

### 1.3 Comportements et écarts constatés (à connaître avant #542)

- **Plusieurs capacités par profil** : déjà possible (`abilities:` est une liste), sans contrôle de doublon.
  Deux `ENRAGED` : seule la première agit (`findAbility` prend la première), la seconde est ignorée en silence.
  Deux `STRONGER_EXPLOSION` : toutes s'appliquent (multiplication). Comportement **incohérent** selon le type.
- **Ordre** : à l'enregistrement depuis le panel (`writeMobDefinition`), les capacités non éditables sont recopiées
  d'abord puis Enragé et Renforts ajoutés à la fin → l'ordre du fichier peut changer, sans perte (#461 a sécurisé
  la conservation et refuse si le profil actuel est illisible).
- **Aucune notion commune** de déclencheur, de chance, de délai, d'activation/désactivation par capacité ni
  d'« en combat ». Seul `SUMMON_ON_DAMAGE` a chance + délai ; `ENRAGED` ne vérifie pas que le mob combat.
- **Tâches planifiées** : deux tâches répétitives globales de 1 s (Enragé, Explosif), chacune parcourt
  `registry.definitions()` × `aliveEntityIds` (population connue, jamais un scan du monde : bonne base).
  Aucune tâche par entité.
- **Nettoyage** : rien à nettoyer pour les tâches (globales, arrêtées à `stop()`). Mais
  `SummonOnDamageAbilityListener.lastSummonAtMillis` n'est **jamais purgé** (une entrée par mob blessé, petite
  fuite mémoire) ; les renforts sont des mobs vanilla qui **survivent** à la mort de leur invocateur (comportement
  actuel à conserver ou non : question Q7) ; la population `tracked` n'est pas retirée au déchargement (voulu, #169).
- **Protections** : `EXPLOSIVE_ON_ATTACK` et `STRONGER_EXPLOSION` passent par une vraie explosion
  (`EntityExplodeEvent`), donc les protections Hub/claims/zones s'appliquent. `SUMMON_ON_DAMAGE` et `SPLIT_ON_HIT`
  font `world.spawnEntity` (raison `CUSTOM`) **sans consulter** Hub/claims/zones : aujourd'hui sans risque pratique
  parce que le mob parent est lui-même dans le Wild, mais rien ne le garantit (boss de quête, test).
  Il n'existe **aucune politique centrale** (`TerrainMutationPolicy` de #230 non créée).
- **Cible joueur** : `ExplosiveOnAttackAbilityService.nearestEligiblePlayer` lit `world.getPlayers()` et
  n'exclut que les spectateurs — ni créatif, ni PNJ Citizens (les PNJ Citizens de type joueur peuvent figurer dans
  cette liste ; `NpcIdentityService.isNpc` existe et n'est pas utilisé ici).
- **Redémarrage** : Enragé survit (PDC + attributs persistés) ; le délai des renforts repart à zéro (acceptable).
- **Mesures** : `SpecialMobService.recordAbilityTrigger(label)` compte les déclenchements par type (réutilisable).

### 1.4 Briques à réutiliser

| Brique | Rôle pour #540 |
|---|---|
| `SpecialMobService` (PDC `special-mob-id`, population, `apply`, `discard`, `EntitiesLoadEvent`, instances de test #172) | identification et cycle de vie des lanceurs |
| `SpecialMobSchema.Field` (#214) | ancêtre direct du **descripteur de paramètre** ; le schéma doit être dérivé du registre |
| `SpecialMobImport` + `MobProfileDiff` + `MobAiPrompt` (#214, #465) | import / diff / IA : continuent de fonctionner si le format reste plat (§2.6) |
| `SpecialMobDefinitionStore` (#461) | écriture atomique + relecture + rollback : inchangé |
| `ZoneRegistry.zoneAt`, `ClaimService.claimAt`, `HubConfig`, `NpcIdentityService.isNpc` | politique centrale de ciblage/zone |
| Refonte /mobs #169 (trois vues, fiche, test rattaché) | point d'accroche des cartes de capacités |

### 1.5 #230 et #170 : ce qui est fait, ce qui ne l'est pas

- **#230 (moteur extensible + effets de terrain)** : **non implémenté**. Ni registre, ni descripteurs, ni
  `TerrainMutationPolicy`, ni `docs/development/ADDING_MOB_EFFECT.md`. Son contrat générique (registre, descripteur,
  paramètres, validation, budget, formulaire dérivé, doc « ajouter un effet ») est **exactement le socle de #542**.
  Ses effets **de terrain** (citrouilles, puits, lave, prison) contredisent la règle #540 « aucune modification
  durable du monde par défaut » : ils doivent rester un lot ultérieur, derrière une politique de mutation explicite.
- **#170** : liste d'idées ; « Onde de choc annoncée » = #545, « Charge annoncée » = #548, « Soutien » ≈ #547/#553,
  « Bouclier temporaire » ≈ #552. Les autres (vampirique, salves, téléportation, phases…) ne sont pas couvertes.

---

## 2. Architecture proposée

### 2.1 Vocabulaire

- **Type de capacité** : code Java enregistré une fois (ex. `ENRAGED`, `JUMP_GRAVEL_RAIN`). Décrit par un
  **descripteur** (libellé FR, catégorie, statut, compatibilité, paramètres typés et bornés, budget).
- **Instance** : une entrée `abilities[]` d'un profil = un type + **ses propres valeurs**. Deux profils qui
  utilisent la même capacité ne partagent jamais leurs valeurs.
- **Préréglage** : valeurs de départ nommées (Doux/Normal/Brutal), **copiées** dans l'instance au moment du choix.

### 2.2 Registre et descripteur (plugin, `com.lodygames.rpgquest.mob.ability`)

```java
interface MobAbilityType<A extends MobAbility> {          // un par capacité
    AbilityDescriptor descriptor();                         // id stable, libellé, catégorie, statut, params, budget
    A parse(AbilityParams p, List<String> errors);          // validation bornée, messages FR
    void render(A ability, YamlWriter out);                 // ordre des clés stable
    AbilityExecutor<A> executor(AbilityContext ctx);        // runtime Paper
}
record AbilityDescriptor(String id, String label, String summaryTemplate, Category category, Status status,
        Set<Trigger> triggers, Compatibility compatibility, boolean repeatable, Kind kind /* PASSIVE|ACTION */,
        List<ParamSpec> params, List<Preset> presets, Budget budget, WorldMutation mutation /* NONE par défaut */)
record ParamSpec(String key, ParamKind kind /* INT, DECIMAL, PERCENT, SECONDS, BOOL, ENTITY_TYPE, ENUM */,
        boolean required, Number min, Number max, Object defaultValue, String unit, String label, String help, boolean advanced)
```

- `MobAbilityRegistry` : registre unique, id refusé s'il est déjà pris, liste ordonnée pour le catalogue.
- `SpecialMobSchema.abilityFields/abilityDoc/abilityKeys` deviennent des **vues du registre** (une seule source) :
  le modèle commenté, la vérification des clés à l'import et l'invite IA suivent automatiquement.
- **Option d'implémentation retenue par défaut** : garder l'interface scellée `MobAbility` et ses records
  (sécurité du compilateur), et ajouter le descripteur à côté. Chaque nouvelle capacité ajoute un record + un
  `MobAbilityType` + son exécuteur, **plus aucune ligne** dans le parseur, le rendu YAML, le schéma, le résumé
  agent ou le formulaire panel. (Alternative « instance générique `Map` non typée » rejetée : on perd la
  validation forte et les bornes deviennent implicites.)

### 2.3 Moteur d'exécution (`AbilityEngine`)

- **Un seul listener** distribue les événements aux instances concernées, **dans l'ordre du profil** :

| Déclencheur | Source Paper (API publique) |
|---|---|
| `DAMAGED` | `EntityDamageEvent` non annulé, `finalDamage > 0` (comme aujourd'hui) |
| `HIT_PLAYER` | `EntityDamageByEntityEvent` (lanceur → joueur), non annulé, `finalDamage > 0` |
| `HEALTH_BELOW` | évalué après chaque dégât effectif (remplace à terme le balayage 1 s d'Enragé, même résultat) |
| `COMBAT_TICK` | tâche partagée **toutes les 10 ticks** sur les seuls mobs « en combat » |
| `TARGET_ACQUIRED` | `EntityTargetLivingEntityEvent` vers un joueur |
| `EXPLOSION_PRIME` | `ExplosionPrimeEvent` (Explosion renforcée) |
| `LANDED` | interne : fin de saut détectée par le suivi d'action (§2.5) |
| `DEATH` | `EntityDeathEvent` |

- **Champs communs gérés par le moteur** (pas par chaque capacité) : `enabled` (défaut `true`), `chance`
  (défaut 1), `cooldown-seconds` (défaut 0), `max-uses` (défaut illimité). RNG injectable (tests déterministes).
- **État « en combat »** (`CombatState`, mémoire, par UUID) : vrai si la cible actuelle (`Mob#getTarget`) est un
  joueur valide (survie/aventure, pas PNJ, même monde, distance ≤ portée) **et** qu'un coup a été donné ou reçu
  entre eux depuis moins de `combat-timeout` (10 s proposé). Toute capacité `ACTION` offensive exige cet état.
- **Coexistence déterministe** : `PASSIVE` (Enragé, Explosion renforcée…) se cumulent ; `ACTION` (saut, charge,
  onde…) → **une seule action en cours par mob** ; à égalité, la première dans l'ordre du profil gagne. Une
  capacité désactivée n'est jamais évaluée.
- **Doublons** : un type non `repeatable` ne peut apparaître qu'une fois (refus à l'enregistrement/import ; pour
  un fichier existant : avertissement et première instance seule active, ce qui reproduit le comportement actuel
  de 4 types sur 5 — voir Q3 pour `STRONGER_EXPLOSION`).

### 2.4 Sécurité, plafonds, nettoyage

- **`AbilityWorldPolicy`** (politique centrale, jamais de `if world == hub` dans une capacité) :
  refuse Hub, claims (`ClaimService.claimAt`), zones protégées (`ZoneRegistry`), et hors des mondes autorisés du
  profil ; **cibles** : jamais un PNJ (`NpcIdentityService`), un spectateur/créatif, ni un joueur d'un autre monde.
  Toute apparition d'entité par une capacité (renforts compris) passe par elle (corrige l'écart §1.3).
- **`WorldMutation.NONE` par défaut** : aucun bloc posé/cassé. Un futur effet de terrain (#230) devra déclarer
  `TEMPORARY`/`PERMANENT` et passer par une politique dédiée, sous un ticket séparé.
- **Plafonds serveur** (config, valeurs proposées) : 32 actions en cours au total, 64 entités temporaires
  (blocs qui tombent, totems, leurres), 3 renforts invoqués par seconde et par monde, 5 capacités par profil.
  Le descripteur déclare son budget (entités max par activation) ; une instance qui dépasserait est refusée à la
  validation, et l'exécution vérifie de nouveau le plafond global (refus silencieux + compteur diagnostic).
- **Pas de tâche par entité** : une tâche partagée (10 ticks) pour `COMBAT_TICK`, une tâche partagée (1 tick)
  qui n'itère que les **actions en cours** (bornées par le plafond). Aucun chunk chargé ni forcé.
- **Nettoyage** : état mémoire (délais, combat, actions) retiré sur `EntityDeathEvent`, `discard`,
  `EntitiesUnloadEvent`, `EntityRemoveFromWorldEvent` (Paper) et `stop()`. Entités temporaires : `setPersistent(false)`
  (non sauvegardées au déchargement) + PDC `ability-owner`/`ability-cast` + purge au `EntitiesLoadEvent` de tout
  reliquat (crash). États durables uniquement si nécessaires et en PDC (Enragé garde sa clé actuelle).

### 2.5 Faisabilité Paper 1.21.11 — Zombie des Éboulements (#544)

Méthodes `FallingBlock` (`setCancelDrop`, `setHurtEntities`, `setDamagePerBlock`, `setMaxDamage`) et `EntityRemoveFromWorldEvent` vérifiées dans le JAR `paper-api-1.21.11-R0.1-SNAPSHOT` du projet (`javap`).

| Besoin | API publique Paper | Verdict |
|---|---|---|
| Saut vertical | `entity.setVelocity(new Vector(0, v, 0))` | OK |
| Détecter l'atterrissage | pas d'événement « mob atterrit » : suivi dans la tâche des actions en cours, `isOnGround()` après avoir quitté le sol, délai max 3 s ; invalide si `isInWater()`/`isInLava()` ou si le mob a été téléporté | OK, à prototyper |
| Gravier qui tombe | `World#spawn(loc, FallingBlock.class, …)` / `spawnFallingBlock(loc, BlockData)` ; `setDropItem(false)`, `setCancelDrop(true)`, `setHurtEntities(true)`, `setDamagePerBlock`, `setMaxDamage`, `setPersistent(false)` | OK |
| Aucun bloc posé | `EntityChangeBlockEvent` (entité = notre FallingBlock) → annuler + retirer l'entité | OK |
| Rien à ramasser | `setCancelDrop(true)` + `setDropItem(false)` ; `EntityDropItemEvent` en filet | OK |
| Immunité du lanceur | `EntityDamageByEntityEvent` (damager = FallingBlock taggé PDC `ability-owner`) → annulé si victime = lanceur ou non-joueur | OK |
| Isolation de 2 zombies | PDC `ability-cast` (UUID du lancer) sur chaque bloc | OK |
| Protections | positions refusées par `AbilityWorldPolicy` avant apparition ; jamais au-dessus d'un claim/Hub | OK |
| Plafond | budget 12 blocs/lancer, 64 au total ; retrait forcé après 5 s | OK |

Points à vérifier en prototype (#544, sur un profil au tirage naturel désactivé) : dégâts réels d'un gravier
avec `setHurtEntities(true)` (valeur par défaut faible → réglée par `setDamagePerBlock`), comportement sur pente
et sous un plafond bas (hauteur de chute réduite, jamais à travers un bloc), latence perçue.

### 2.6 Format YAML et compatibilité (à trancher : Q1)

| Option | Forme | Fichiers existants | Import/diff/IA #214 | Avis |
|---|---|---|---|---|
| **A — plat étendu** (recommandée) | `- type: X` + paramètres + champs communs facultatifs `enabled`, `chance`, `cooldown-seconds`, `max-uses` au même niveau | lus **à l'identique**, jamais réécrits ; un enregistrement depuis le panel produit les **mêmes lignes** (test « golden ») | inchangés | migration nulle, `chance`/`cooldown-seconds` ont déjà ce nom et ce sens dans `SUMMON_ON_DAMAGE` |
| B — `params:` imbriqué | `- type: X` / `enabled:` / `params: {…}` ; les deux formes lues | lus ; réécrits sous la nouvelle forme **au premier enregistrement** | deux formes à documenter, diffs bruyants | plus « propre », mais double format durable |
| C — `format-version: 2` + conversion | conversion automatique | **réécrits** | à refaire | rejetée (contraire à la consigne) |

Avec A : absent = comportement actuel exact (Enragé : chance 1, délai 0 ; Renforts : ses `chance`/`cooldown-seconds`
actuels). Un nouveau champ commun inconnu d'un ancien plugin serait refusé à l'import par cet ancien plugin — le
panel et le plugin sont déployés ensemble, rien à faire de plus.

### 2.7 PlugAdmin

- Le plugin publie le **catalogue des descripteurs** via une action de lecture (`mob.ability.catalog`, liste
  blanche agent comme les autres) ; le panel le garde comme les autres relevés. Le panel **ne code plus aucun
  formulaire de capacité** : champs, bornes, aides, préréglages et résumés viennent du descripteur.
- Le profil envoie `abilities` sous forme de liste structurée (type + valeurs) au lieu des 8 champs plats ;
  le plugin revalide tout (le panel ne fait qu'une validation de confort). Les anciens champs plats restent
  acceptés pendant un lot pour la compatibilité, puis sont retirés.
- Page **Capacités** (nouvelle entrée de menu, lecture seule) + **cartes** dans le profil (maquettes §3).
  Les réglages mondes/apparition restent dans /mobs (consigne #543).

---

## 3. Maquettes

| Écran | Fichier | Captures |
|---|---|---|
| Catalogue « Capacités » : filtres (catégorie, compatibilité, état), cartes Disponible / En développement / Prévue, nombre de profils utilisateurs | `catalogue.html` | `catalogue-1280.png`, `catalogue-390.png` |
| Fiche d'une capacité (Saut et pluie de gravier) : ce que voit le joueur, réglages avec bornes, garanties, compatibilité, préréglages, profils qui l'utilisent | `fiche.html` | `fiche-1280.png`, `fiche-390.png` |
| Profil court « Zombie des Éboulements » : 4 lignes résumées (Identité, Statistiques, Apparition, Butin) + 3 cartes (Saut + gravier en cours de réglage, Enragé, Invocation de renforts **désactivée** qui garde ses valeurs), ordre ↑↓, Retirer, vérifications, barre d'enregistrement avec différences | `profil.html` | `profil-1280.png`, `profil-390.png` |
| Ajouter une capacité : étapes Choisir → Régler → Vérifier, liste à cocher, capacités déjà présentes / incompatibles / prévues grisées avec la raison | `ajout.html` | `ajout-1280.png`, `ajout-390.png` |

Principes : on ne voit **que** les réglages des capacités choisies ; 6 réglages principaux au plus par carte, le
reste sous « Réglages avancés » ; chaque carte a une phrase-résumé générée par le descripteur ; aucune valeur
d'une capacité désactivée n'est perdue.

---

## 4. Décisions à trancher par le propriétaire

| # | Question | Proposition |
|---|---|---|
| Q1 | Format YAML (§2.6) | **A — plat étendu**, aucun fichier réécrit |
| Q2 | Plafond de capacités par profil | 5 (3 recommandées) |
| Q3 | Doublons d'un même type | refusés (sauf type déclaré répétable) ; anciens fichiers : avertissement, 1ʳᵉ active. `STRONGER_EXPLOSION` en double : garder la multiplication actuelle ou ne garder que la 1ʳᵉ ? (aucun profil livré n'en a deux) |
| Q4 | Définition de « en combat » | cible joueur + coup échangé < 10 s + distance ≤ portée |
| Q5 | Coexistence | passives cumulées, **une action à la fois**, priorité = ordre du profil, réordonnable dans le panel |
| Q6 | Préréglages | Doux/Normal/Brutal fournis par chaque capacité, **copiés** ; préréglages partagés enregistrés par l'admin : plus tard, ticket séparé |
| Q7 | Renforts à la mort de l'invocateur | aujourd'hui ils restent ; proposer « restent » (inchangé) ou « disparaissent » (nouveau réglage, défaut = inchangé) |
| Q8 | Capacités « en développement » | sélectionnables seulement sur un profil au tirage naturel désactivé ou de test |
| Q9 | Explosif au contact | ajouter l'exclusion des PNJ et des joueurs en créatif (petit correctif, dans #542) |
| Q10 | Emplacement du menu | « Capacités » juste sous « Mobs spéciaux & boss » |
| Q11 | #230 effets de terrain | reportés après #553, derrière une politique de mutation explicite ; #542 reprend seulement le contrat générique de #230 |

---

## 5. Découpage confirmé / corrigé

| Ticket | Verdict | Précisions |
|---|---|---|
| #542 Socle | **confirmé, élargi** | registre + descripteurs pour les **5** types existants (pas 2), champs communs, `CombatState`, `AbilityWorldPolicy`, plafonds, nettoyage, schéma #214 dérivé du registre, action `mob.ability.catalog`, test « golden » de réécriture identique des 4 profils livrés et de profils DEV types, doc `docs/development/ADDING_MOB_ABILITY.md` (le « ADDING_MOB_EFFECT » de #230). Sans nouvelle capacité. |
| #543 Page Capacités + cartes | confirmé | dépend de #542 ; formulaires 100 % dérivés du descripteur ; retrait des champs plats `enraged_*`/`summon_*` |
| #544 Zombie des Éboulements | confirmé | prototype de faisabilité §2.5 d'abord ; profil au tirage naturel désactivé |
| #545 Onde sismique | confirmé | recouvre « Onde de choc annoncée » de #170 |
| #546 Éclair en chaîne | confirmé | visuel seul (pas de `strikeLightning`) : particules + son |
| #547 Totem de soin | confirmé | entité temporaire non persistante, budget « entités temporaires » |
| #548 Charge du béhémoth | confirmé | recouvre « Charge annoncée » de #170 |
| #549 → #553 | confirmés | une capacité à la fois ; #552 doit tester la combinaison avec Enragé (deux déclencheurs `HEALTH_BELOW`) |
| #170 | à mettre à jour | retirer de sa liste Onde de choc et Charge (→ #545/#548) ; le reste demeure des idées |
| #230 | à mettre à jour | contrat générique absorbé par #542 ; effets de terrain = suite, non planifiée |

Aucun doublon de ticket créé. Aucun nouveau ticket proposé avant validation.
