package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Issue #542 — règles de composition des capacités d'un profil (décisions Q2, Q3, Q8 de #541).
 *
 * <ul>
 *   <li><b>Q2</b> : {@value #MAX_ABILITIES_PER_PROFILE} capacités au plus par profil.</li>
 *   <li><b>Q3</b> : un type non répétable ne figure qu'une fois. Un fichier existant qui en contient
 *       plusieurs se charge quand même (avertissement) et seule la première instance agit.</li>
 *   <li><b>Q8</b> : une capacité « en développement » ne va que sur un profil hors tirage naturel
 *       (BOSS, ou SPECIAL désactivé / à {@code spawn-chance: 0}).</li>
 * </ul>
 *
 * <p>{@link #violations} refuse à l'enregistrement et à l'import ; {@link #loadWarnings} ne fait que
 * signaler au chargement, pour ne jamais rendre illisible un profil déjà en place.</p>
 */
public final class MobAbilityRules {

    public static final int MAX_ABILITIES_PER_PROFILE = 5;

    private MobAbilityRules() {
    }

    /** Problèmes bloquants pour un enregistrement ou un import (vide = conforme). */
    public static List<String> violations(SpecialMobDefinition d) {
        return violations(d, MobAbilityCatalog::descriptor);
    }

    static List<String> violations(SpecialMobDefinition d, Function<MobAbilityType, AbilityDescriptor> catalog) {
        List<String> out = new ArrayList<>(compositionProblems(d.abilities(), catalog));
        if (!naturallySpawned(d)) {
            return out;
        }
        for (MobAbility ability : d.abilities()) {
            AbilityDescriptor descriptor = catalog.apply(ability.type());
            if (descriptor.status() != AbilityDescriptor.Status.AVAILABLE) {
                out.add("Capacité « " + descriptor.label() + " » (" + ability.type() + ") encore en développement : "
                        + "réservée à un profil de test (BOSS, ou mob spécial désactivé ou à spawn-chance 0).");
            }
        }
        return out;
    }

    /** Ce que le chargement signale sans refuser le profil (anciens fichiers). */
    public static List<String> loadWarnings(SpecialMobDefinition d) {
        List<String> out = new ArrayList<>();
        for (String problem : compositionProblems(d.abilities(), MobAbilityCatalog::descriptor)) {
            out.add(problem + " Le profil reste chargé ; seule la première instance de chaque capacité agit.");
        }
        return out;
    }

    /**
     * Les instances que le moteur évalue, dans l'ordre du profil : activées, et pour un type non
     * répétable, la première seulement (Q3 — identique à l'ancien comportement de quatre types sur cinq).
     */
    public static List<MobAbility> activeAbilities(List<MobAbility> abilities) {
        List<MobAbility> active = new ArrayList<>(abilities.size());
        Set<MobAbilityType> seen = EnumSet.noneOf(MobAbilityType.class);
        for (MobAbility ability : abilities) {
            boolean first = seen.add(ability.type());
            if (!first && !MobAbilityCatalog.descriptor(ability.type()).repeatable()) {
                continue;
            }
            if (ability.options().enabled()) {
                active.add(ability);
            }
        }
        return active;
    }

    private static List<String> compositionProblems(List<MobAbility> abilities,
                                                    Function<MobAbilityType, AbilityDescriptor> catalog) {
        List<String> out = new ArrayList<>();
        if (abilities.size() > MAX_ABILITIES_PER_PROFILE) {
            out.add(abilities.size() + " capacités : " + MAX_ABILITIES_PER_PROFILE + " au plus par profil.");
        }
        Set<MobAbilityType> seen = EnumSet.noneOf(MobAbilityType.class);
        Set<MobAbilityType> reported = EnumSet.noneOf(MobAbilityType.class);
        for (MobAbility ability : abilities) {
            if (!seen.add(ability.type()) && !catalog.apply(ability.type()).repeatable() && reported.add(ability.type())) {
                out.add("Capacité « " + catalog.apply(ability.type()).label() + " » (" + ability.type()
                        + ") en double : une seule par profil.");
            }
        }
        return out;
    }

    private static boolean naturallySpawned(SpecialMobDefinition d) {
        return d.category() == MobCategory.SPECIAL && d.enabled() && d.spawnChance() > 0;
    }
}
