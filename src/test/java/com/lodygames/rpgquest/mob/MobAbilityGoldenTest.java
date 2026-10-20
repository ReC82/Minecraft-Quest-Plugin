package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.AbilityOptions;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import com.lodygames.rpgquest.web.agent.BukkitAgentActions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Issue #542 — compatibilité stricte (décision Q1 de #541 : aucun fichier de profil réécrit).
 *
 * <p>Les fichiers {@code src/test/resources/mob-golden/rendered/*.yml} ont été produits par le rendu
 * <strong>d'avant #542</strong> (commit {@code b23dbda}), à partir des quatre profils livrés
 * ({@code src/main/resources/mobs}) et de trois profils de type DEV ({@code mob-golden/input} : boss
 * Enragé + Renforts écrit par le panel, Renforts avant Enragé avec entiers sans décimale, les trois
 * types anciens avec un flottant et des clés facultatives absentes). Le nouveau moteur doit les relire
 * et les réécrire <strong>octet pour octet</strong>, sans aucune option non par défaut.</p>
 *
 * <p>Les listes {@code worlds}/{@code biomes}/{@code zones} des profils de référence n'ont qu'un élément :
 * le modèle les range dans un {@code Set} dont l'ordre d'itération varie d'une JVM à l'autre (défaut
 * antérieur à #542, sans rapport avec les capacités, signalé dans le rapport de session).</p>
 */
class MobAbilityGoldenTest {

    private static final Path RENDERED = Path.of("src/test/resources/mob-golden/rendered");

    @BeforeEach
    void setUp() {
        MockBukkit.mock(); // Sound/Particle se résolvent via le registre du serveur.
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static List<Path> profiles() throws Exception {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(Path.of("src/main/resources/mobs"), Path.of("src/test/resources/mob-golden/input"))) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".yml")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    private static SpecialMobDefinition parse(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        SpecialMobDefinitionParser.ParseResult r = new SpecialMobDefinitionParser().parse(file.getFileName().toString(), yaml);
        assertTrue(r.isSuccess(), file + " : " + r.issues());
        return r.definition();
    }

    @Test
    void everyBundledAndDevTypeProfileRendersExactlyAsBefore() throws Exception {
        List<Path> profiles = profiles();
        assertEquals(7, profiles.size(), "4 profils livrés + 3 profils de type DEV : " + profiles);
        for (Path file : profiles) {
            String expected = Files.readString(RENDERED.resolve(file.getFileName()));
            assertEquals(expected, SpecialMobDefinitionYaml.render(parse(file)), "rendu modifié : " + file);
        }
    }

    @Test
    void existingProfilesCarryOnlyDefaultOptions() throws Exception {
        for (Path file : profiles()) {
            for (MobAbility ability : parse(file).abilities()) {
                if (ability instanceof SummonOnDamageAbility summon) {
                    assertTrue(summon.options().enabled() && !summon.despawnWithOwner(), file.toString());
                } else {
                    assertEquals(AbilityOptions.DEFAULTS, ability.options(), file + " " + ability.type());
                }
            }
        }
    }

    @Test
    void savingTheFormUnchangedFromPlugAdminKeepsOrderAndRewritesTheSameFile() throws Exception {
        for (Path file : profiles()) {
            SpecialMobDefinition current = parse(file);
            EnragedAbility enraged = current.abilities().stream().filter(EnragedAbility.class::isInstance)
                    .map(EnragedAbility.class::cast).findFirst().orElse(null);
            SummonOnDamageAbility summon = current.abilities().stream().filter(SummonOnDamageAbility.class::isInstance)
                    .map(SummonOnDamageAbility.class::cast).findFirst().orElse(null);
            // Ce que renvoie le formulaire : les seules valeurs éditables, sans options.
            EnragedAbility formEnraged = enraged == null ? null
                    : new EnragedAbility(enraged.healthFraction(), enraged.speedMultiplier(), enraged.damageMultiplier());
            SummonOnDamageAbility formSummon = summon == null ? null
                    : new SummonOnDamageAbility(summon.summonedEntityType(), summon.amount(), summon.chance(),
                    summon.cooldownSeconds(), summon.maxAlive());

            List<MobAbility> merged = BukkitAgentActions.mergeFormAbilities(
                    current.abilities(), formEnraged, formSummon);

            assertEquals(current.abilities(), merged, "ordre ou valeurs changés : " + file);
        }
    }

    @Test
    void formKeepsWhatItDoesNotEditOnEnragedAndSummon() {
        EnragedAbility disabledEnraged = new EnragedAbility(0.3, 1.2, 1.4, new AbilityOptions(false, 0.5, 10));
        SummonOnDamageAbility summonThatDespawns = new SummonOnDamageAbility(org.bukkit.entity.EntityType.ZOMBIE, 2, 4,
                true, new AbilityOptions(false, 0.25, 20));

        List<MobAbility> merged = BukkitAgentActions.mergeFormAbilities(
                List.of(summonThatDespawns, disabledEnraged),
                new EnragedAbility(0.4, 1.3, 1.5),
                new SummonOnDamageAbility(org.bukkit.entity.EntityType.HUSK, 3, 0.5, 5, 6));

        assertEquals(List.of(
                new SummonOnDamageAbility(org.bukkit.entity.EntityType.HUSK, 3, 6, true, new AbilityOptions(false, 0.5, 5)),
                new EnragedAbility(0.4, 1.3, 1.5, new AbilityOptions(false, 0.5, 10))), merged);
    }

    /**
     * Issue #543 — les cartes de capacités de PlugAdmin renvoient chaque valeur en texte, telle que
     * {@code mob.list} l'a publiée : enregistrer sans rien changer doit redonner exactement les mêmes
     * lignes de capacités, dans le même ordre (aucune réécriture de fichier existant).
     */
    @Test
    void panelCardsSentBackUnchangedRenderTheSameAbilities() throws Exception {
        for (Path file : profiles()) {
            SpecialMobDefinition def = parse(file);
            List<Object> sent = new ArrayList<>();
            for (MobAbility ability : def.abilities()) {
                java.util.Map<String, Object> view = com.lodygames.rpgquest.mob.ability.MobAbilityExchange.instanceView(ability);
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("type", view.get("type"));
                ((java.util.Map<?, ?>) view.get("values")).forEach((k, v) -> entry.put((String) k, String.valueOf(v)));
                sent.add(entry);
            }
            com.lodygames.rpgquest.mob.ability.MobAbilityExchange.Parsed back =
                    com.lodygames.rpgquest.mob.ability.MobAbilityExchange.parseEntries(sent);
            assertTrue(back.ok(), file + " : " + back.errors());
            assertEquals(renderAbilities(def.abilities()), renderAbilities(back.abilities()), "capacités modifiées : " + file);
        }
    }

    private static String renderAbilities(List<MobAbility> abilities) {
        StringBuilder sb = new StringBuilder();
        abilities.forEach(a -> com.lodygames.rpgquest.mob.ability.MobAbilityCatalog.render(a, sb));
        return sb.toString();
    }
}
