package com.lodygames.rpgquest.building.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Les propriétés <strong>réellement reconnues</strong> par les blocs que le moteur manipule
 * (issue #94, lot D).
 *
 * <h2>Ce que le contrôle précédent laissait passer</h2>
 *
 * <p>{@link BlockState} ne vérifie que la <em>forme</em> : minuscules, pas d'espace, grammaire
 * {@code clé=valeur}. Le plan V1 contenait donc :</p>
 *
 * <pre>minecraft:oak_stairs[face=bottom,shape=straight]</pre>
 *
 * <p>et il est passé. Or {@code face} n'existe pas pour un escalier — c'est {@code half}.
 * {@code face} appartient aux boutons et aux meules. Un seul mot de travers, et le bloc écrit dans
 * le schematic n'est pas celui que le plan décrivait.</p>
 *
 * <h2>Pourquoi une table déclarée plutôt que Bukkit</h2>
 *
 * <p>Le mécanisme autoritaire serait {@code Bukkit.createBlockData(state)}, qui lève sur un état
 * invalide. Il a deux défauts ici : il exige un serveur en cours d'exécution — donc aucune
 * validation possible dans un test pur, ni dans le panel — et il n'est pas disponible au moment où
 * le plan est lu. Cette classe est donc la validation <strong>de première ligne</strong>, pure et
 * testable, et elle couvre exactement les familles que les primitives du lot B émettent.</p>
 *
 * <p>La table décrit, par famille, les propriétés admises et leurs valeurs. Un bloc dont la famille
 * n'est pas décrite voit ses propriétés <strong>acceptées sans contrôle</strong> : refuser
 * l'inconnu reviendrait à interdire les 1 200 blocs du jeu dont nous ne savons rien, alors que la
 * faute observée porte précisément sur les familles que nous écrivons nous-mêmes.</p>
 */
public final class BlockStateRules {

    private BlockStateRules() {
    }

    /** Une famille de blocs et les propriétés qu'elle accepte. */
    private record Family(String suffix, Map<String, Set<String>> properties) {
    }

    private static final Set<String> FACINGS = Set.of("north", "south", "east", "west");
    private static final Set<String> BOOLEANS = Set.of("true", "false");

    /**
     * Les familles décrites, du suffixe le plus long au plus court.
     *
     * <p>Ordre significatif : {@code _trapdoor} doit être examiné avant {@code _door}, sinon une
     * trappe serait jugée avec les propriétés d'une porte — et {@code half=top} d'une trappe, qui
     * est valide, serait refusé.</p>
     */
    private static final List<Family> FAMILIES = families();

    /** Le verdict : la propriété fautive, et ce qui était attendu. */
    public record Verdict(boolean valid, String property, String expected) {

        static Verdict ok() {
            return new Verdict(true, "", "");
        }

        static Verdict bad(String property, String expected) {
            return new Verdict(false, property, expected);
        }
    }

    /**
     * Les propriétés de cet état sont-elles reconnues par son type ?
     *
     * <p>La <em>forme</em> est supposée déjà validée par {@link BlockState#valid(String)} : cette
     * méthode se concentre sur la sémantique des propriétés.</p>
     */
    public static Verdict check(String state) {
        if (state == null || state.isBlank()) {
            return Verdict.ok();
        }
        String value = state.trim();
        int bracket = value.indexOf('[');
        if (bracket < 0 || !value.endsWith("]")) {
            return Verdict.ok();
        }
        String type = BlockState.type(value.substring(0, bracket));
        Family family = familyOf(type);
        if (family == null) {
            return Verdict.ok();
        }
        String inside = value.substring(bracket + 1, value.length() - 1);
        for (String pair : inside.split(",", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String val = pair.substring(eq + 1);
            Set<String> allowed = family.properties().get(key);
            if (allowed == null) {
                return Verdict.bad(key, "propriétés acceptées pour cette famille : "
                        + String.join(", ", family.properties().keySet()));
            }
            if (!allowed.isEmpty() && !allowed.contains(val)) {
                return Verdict.bad(key, "valeurs acceptées pour « " + key + " » : "
                        + String.join(", ", allowed));
            }
        }
        return Verdict.ok();
    }

    /** Le message de refus, rédigé pour être renvoyé au modèle. */
    public static Optional<String> refusal(String state) {
        Verdict verdict = check(state);
        if (verdict.valid()) {
            return Optional.empty();
        }
        return Optional.of("état de bloc « " + state + " » : la propriété « " + verdict.property()
                + " » n'est pas reconnue — " + verdict.expected()
                + ". Laisse le moteur calculer les propriétés : écris seulement le type du bloc.");
    }

    private static Family familyOf(String type) {
        String id = type.toLowerCase(Locale.ROOT);
        for (Family family : FAMILIES) {
            if (id.endsWith(family.suffix())) {
                return family;
            }
        }
        return null;
    }

    private static List<Family> families() {
        // Un ensemble vide signifie « toute valeur » : la propriété existe, mais nous n'avons pas
        // besoin d'en énumérer les valeurs pour attraper la faute observée.
        Map<String, Set<String>> stairs = new LinkedHashMap<>();
        stairs.put("facing", FACINGS);
        stairs.put("half", Set.of("top", "bottom"));
        stairs.put("shape", Set.of("straight", "inner_left", "inner_right",
                "outer_left", "outer_right"));
        stairs.put("waterlogged", BOOLEANS);

        Map<String, Set<String>> door = new LinkedHashMap<>();
        door.put("facing", FACINGS);
        door.put("half", Set.of("upper", "lower"));
        door.put("hinge", Set.of("left", "right"));
        door.put("open", BOOLEANS);
        door.put("powered", BOOLEANS);

        Map<String, Set<String>> trapdoor = new LinkedHashMap<>();
        trapdoor.put("facing", FACINGS);
        trapdoor.put("half", Set.of("top", "bottom"));
        trapdoor.put("open", BOOLEANS);
        trapdoor.put("powered", BOOLEANS);
        trapdoor.put("waterlogged", BOOLEANS);

        Map<String, Set<String>> slab = new LinkedHashMap<>();
        slab.put("type", Set.of("top", "bottom", "double"));
        slab.put("waterlogged", BOOLEANS);

        Map<String, Set<String>> pane = new LinkedHashMap<>();
        pane.put("north", BOOLEANS);
        pane.put("south", BOOLEANS);
        pane.put("east", BOOLEANS);
        pane.put("west", BOOLEANS);
        pane.put("waterlogged", BOOLEANS);

        Map<String, Set<String>> lantern = new LinkedHashMap<>();
        lantern.put("hanging", BOOLEANS);
        lantern.put("waterlogged", BOOLEANS);

        Map<String, Set<String>> ladder = new LinkedHashMap<>();
        ladder.put("facing", FACINGS);
        ladder.put("waterlogged", BOOLEANS);

        Map<String, Set<String>> log = new LinkedHashMap<>();
        log.put("axis", Set.of("x", "y", "z"));

        Map<String, Set<String>> torch = new LinkedHashMap<>();
        torch.put("facing", FACINGS);

        Map<String, Set<String>> gate = new LinkedHashMap<>();
        gate.put("facing", FACINGS);
        gate.put("in_wall", BOOLEANS);
        gate.put("open", BOOLEANS);
        gate.put("powered", BOOLEANS);

        // Du suffixe le plus long au plus court : « _trapdoor » avant « _door ».
        return List.of(
                new Family("_trapdoor", Map.copyOf(trapdoor)),
                new Family("_fence_gate", Map.copyOf(gate)),
                new Family("_glass_pane", Map.copyOf(pane)),
                new Family("glass_pane", Map.copyOf(pane)),
                new Family("_stairs", Map.copyOf(stairs)),
                new Family("_door", Map.copyOf(door)),
                new Family("_slab", Map.copyOf(slab)),
                new Family("wall_torch", Map.copyOf(torch)),
                new Family("_lantern", Map.copyOf(lantern)),
                new Family("lantern", Map.copyOf(lantern)),
                new Family("ladder", Map.copyOf(ladder)),
                new Family("_log", Map.copyOf(log)),
                new Family("_wood", Map.copyOf(log)),
                new Family("_stem", Map.copyOf(log)),
                new Family("_hyphae", Map.copyOf(log)));
    }
}
