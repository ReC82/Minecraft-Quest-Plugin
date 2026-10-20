# Ajouter une capacité de mob

> Socle livré par l'issue #542 (EPIC #540). Remplace le « ADDING_MOB_EFFECT » prévu par #230.
> Décisions de référence : `docs/design/541-capacites/ETUDE.md` (Q1–Q11 approuvées le 19/10/2026).

Une capacité = **un record**, **une valeur d'enum**, **une entrée de registre**, **un exécuteur**.
Aucune ligne à ajouter dans le parseur, le rendu YAML, le schéma #214 ni le modèle téléchargeable :
ils lisent le registre.

## 1. Le modèle (`mob/model`)

1. Ajouter la valeur à `MobAbilityType` (identifiant stable : c'est la clé `type:` du YAML, ne
   plus jamais la renommer).
2. Créer le record `XxxAbility implements MobAbility`, avec ses paramètres **et** un dernier composant
   `AbilityOptions options` (`null` → `AbilityOptions.DEFAULTS` dans le constructeur compact). Le
   constructeur compact revalide les bornes (dernier filet si le record est construit hors YAML).
3. L'ajouter au `permits` de `MobAbility`.

Le compilateur signale ensuite chaque `switch` à compléter (registre, résumé agent).

## 2. Le descripteur (`mob/ability/MobAbilityCatalog#entryFor`)

Ajouter un `case` qui retourne une `Entry` :

```java
case XXX -> new Entry<>(descriptor(type, "Libellé FR", "Ce que voit le joueur, une ligne.",
        Category.REACTION, Set.of(AbilityTrigger.DAMAGED), Set.of() /* toutes créatures */, 0 /* entités créées */,
        List.of(required("ma-cle", ValueType.DECIMAL, 0.0, true, null, false, "1.5",
                        "Libellé court", "Explication d'une ligne, bornes comprises."),
                optional("autre-cle", ValueType.INT, 1.0, false, 10.0, false, 3, false, "3",
                        "Libellé", "… Absent = 3.")),
        presets(Map.of("ma-cle", 1.2), Map.of("ma-cle", 1.5), Map.of("ma-cle", 2.0))),
        XxxAbility.class,
        (v, o) -> new XxxAbility(v.decimal("ma-cle"), v.integer("autre-cle"), o),
        a -> ordered("ma-cle", a.maCle(), "autre-cle", a.autreCle()));
```

Règles :

- **Clés plates** au même niveau que `type` (format A, décision Q1). Les champs communs `enabled`,
  `chance`, `cooldown-seconds` sont ajoutés automatiquement à la fin ; une capacité peut les
  redéclarer (comme `SUMMON_ON_DAMAGE`) pour les rendre obligatoires.
- `writeDefault` : `false` pour toute **nouvelle** clé facultative, afin qu'un fichier existant ne
  change jamais à l'écriture.
- Bornes obligatoires sur tout nombre ; un plafond d'entités (`max-alive`…) obligatoire pour toute
  capacité qui fait apparaître quelque chose.
- Préréglages Doux / Normal / Brutal : valeurs **copiées** dans l'instance au choix (Q6).
- Statut : nouvelle capacité = `Status.IN_DEVELOPMENT` tant qu'elle n'est pas validée en jeu ; elle
  n'est alors acceptée que sur un profil BOSS ou un SPECIAL désactivé / à `spawn-chance: 0` (Q8).
  Aujourd'hui `descriptor(...)` pose `AVAILABLE` : ajouter un paramètre de statut à ce moment-là.
- `WorldMutation.NONE` est le seul accepté : un effet de terrain (#230) exige d'abord une politique
  de mutation dédiée (Q11).

## 3. L'exécuteur (`mob/ability/XxxExecutor`)

```java
final class XxxExecutor implements AbilityExecutor<XxxAbility> {
    public MobAbilityType type() { return MobAbilityType.XXX; }
    public Class<XxxAbility> abilityClass() { return XxxAbility.class; }

    public void execute(AbilityContext<XxxAbility> ctx) {
        // 1. conditions propres (vie, distance, profondeur…) ;
        // 2. if (!ctx.passesGate()) return;   // délai + chance communs, tenus par le moteur
        // 3. agir ;
        // 4. ctx.markActivated();             // démarre le délai, compte le déclenchement
    }
}
```

Puis l'enregistrer dans le constructeur d'`AbilityEngine` (le démarrage échoue si un type n'a pas
d'exécuteur).

Obligatoire :

- **Aucun état par entité dans l'exécuteur.** Délais et combat sont dans le moteur (nettoyés à la
  mort, au retrait du monde, à l'arrêt). Un état durable va en PDC sur l'entité.
- **Toute apparition d'entité** passe par `AbilityWorldPolicy#allowsSpawnAt` (Hub, claims, zones).
- **Toute cible joueur** passe par `AbilityWorldPolicy#isValidTarget` (pas de PNJ, ni créatif, ni
  spectateur).
- Capacité offensive « en action » : exiger `CombatState#isInCombat` (cible joueur + coup échangé
  < 10 s + portée, Q4) et réserver le créneau `AbilityLimits#tryBeginAction` / `endAction`
  (une action à la fois par mob, 32 au total, Q5) — déclarer `Kind.ACTION` dans le descripteur.
- Jamais de scan global (`World#getLivingEntities`) ni de tâche par entité ; jamais de chargement de
  chunk. La tâche d'1 s du moteur (`AbilityTrigger.TICK`) ne parcourt que la population connue.
- Entité temporaire : `setPersistent(false)` + PDC propriétaire, et purge des reliquats.

## 4. Tests attendus

- `MobAbilityCatalogTest` couvre déjà automatiquement : clés uniques, exemples et préréglages valides,
  vue schéma = registre. Vérifier qu'il passe.
- Un test d'exécuteur MockBukkit (voir `SplitOnHitExecutorTest`, `AbilityEngineTest`) : déclenchement,
  non-déclenchement (annulé, hors portée, désactivée), plafond, zone protégée, nettoyage.
- `MobAbilityGoldenTest` doit rester vert : les profils existants ne changent pas.
- PlugAdmin (#543) : rien à coder — la page « Capacités » et les cartes du profil se dérivent du
  descripteur. Régénérer la copie du catalogue des tests du panel :
  `RPGQUEST_REGEN_FIXTURES=1 ./gradlew :test --tests '*MobAbilityCatalogFixtureTest'`
  (sinon ce test échoue, volontairement).

## 5. Documentation

`SPECIAL_MOB_FORMAT.md` (clés, bornes, exemple), `docs/RPGQUEST_BIBLE.md` et sa page `docs-site/`,
`docs/MANUAL_TEST_PLAN.md` (TC), `docs/SUIVI_VALIDATIONS_DEV.md`.
