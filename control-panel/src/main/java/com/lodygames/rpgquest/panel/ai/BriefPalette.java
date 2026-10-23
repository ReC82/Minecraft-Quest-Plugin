package com.lodygames.rpgquest.panel.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * La palette d'identifiants valides adaptée à une description (#747, retour du 23/10/2026).
 *
 * <p>Les essais de l'Église du hub employaient {@code minecraft:stained_glass},
 * {@code minecraft:potted_yellow_flower} et {@code minecraft:lion_bed} : des noms plausibles qui
 * n'existent pas. Le serveur les refusait, la correction suivante en inventait d'autres — ou
 * prenait le voisin orthographique ({@code lime_bed}), ce qui ajoutait un lit à une église.</p>
 *
 * <p>Pour chaque élément ou matériau que la description nomme, la palette donne les identifiants
 * <strong>réels</strong> du registre de la version servie ({@link BlockRegistry}). Elle garantit
 * l'orthographe, sans limiter l'architecture : tout identifiant du registre reste permis. Le
 * lexique est général — aucun bâtiment n'est codé ici.</p>
 */
public final class BriefPalette {

    /** Au-delà, une famille est résumée plutôt qu'énumérée. */
    private static final int MAX_LISTED = 10;

    private record Material(String label, Pattern trigger, Pattern blocks) {
    }

    private static Material material(String label, String trigger, String blocks) {
        return new Material(label, Pattern.compile(trigger), Pattern.compile(blocks));
    }

    /** Matériaux courants (texte sans accents, minuscules) → identifiants du registre. */
    private static final List<Material> MATERIALS = List.of(
            material("Béton", "\\bbetons?\\b", "^minecraft:[a-z_]+_concrete$"),
            material("Quartz", "\\bquartz\\b", "^minecraft:(smooth_)?quartz(_block|_bricks|_pillar|_stairs|_slab)?$|"
                    + "^minecraft:chiseled_quartz_block$"),
            material("Pierre", "\\b(pierres?|moellons?|soubassement)\\b",
                    "^minecraft:(stone|smooth_stone|stone_bricks|mossy_stone_bricks|cracked_stone_bricks|"
                            + "chiseled_stone_bricks|cobblestone|andesite|polished_andesite|diorite|polished_diorite|"
                            + "calcite|stone_brick_stairs|stone_brick_slab|stone_brick_wall)$"),
            material("Ardoise / deepslate", "\\b(ardoises?|deepslate|abimes)\\b",
                    "^minecraft:(cobbled_|polished_)?deepslate(_bricks|_tiles)?(_stairs|_slab|_wall)?$"),
            material("Pierre noire", "\\b(pierre noire|blackstone|sombre)\\b",
                    "^minecraft:(polished_)?blackstone(_bricks)?(_stairs|_slab|_wall)?$"),
            material("Briques", "\\bbriques?\\b", "^minecraft:(bricks|brick_stairs|brick_slab|brick_wall)$"),
            material("Bois (chêne)", "\\b(bois|chene)\\b",
                    "^minecraft:(stripped_)?oak_(planks|log|wood|stairs|slab|fence|trapdoor|door)$"),
            material("Bois (sapin)", "\\b(sapin|epicea)\\b",
                    "^minecraft:(stripped_)?spruce_(planks|log|wood|stairs|slab|fence|trapdoor|door)$"),
            material("Bois sombre", "\\b(bois sombre|chene noir|fonce)\\b",
                    "^minecraft:(stripped_)?dark_oak_(planks|log|wood|stairs|slab|fence|trapdoor|door)$"),
            material("Verre", "\\b(verres?|vitres?|fenetres?)\\b", "^minecraft:(glass|glass_pane|tinted_glass)$"),
            material("Laine", "\\blaines?\\b", "^minecraft:[a-z_]+_wool$"),
            material("Terre cuite", "\\b(terre cuite|terracotta)\\b", "^minecraft:([a-z_]+_)?terracotta$"),
            material("Grès", "\\bgres\\b", "^minecraft:(smooth_|cut_|chiseled_)?sandstone(_stairs|_slab|_wall)?$"),
            material("Cuivre", "\\bcuivre\\b", "^minecraft:(waxed_)?(exposed_|weathered_|oxidized_)?(cut_)?copper(_block)?$"),
            material("Poutres / supports", "\\b(poutres?|supports?|chaines?)\\b",
                    "^minecraft:(chain|iron_chain|oak_log|spruce_log|dark_oak_log|stripped_dark_oak_log)$"));

    private BriefPalette() {
    }

    /**
     * Le bloc « PALETTE D'IDENTIFIANTS VÉRIFIÉS » d'une description ; vide si elle ne nomme rien de
     * reconnu ou si le registre manque.
     */
    public static String block(String brief) {
        return block(brief, BlockRegistry.vanilla());
    }

    static String block(String brief, BlockRegistry registry) {
        if (brief == null || brief.isBlank() || !registry.available()) {
            return "";
        }
        Map<String, List<String>> families = new LinkedHashMap<>();
        for (BriefRequirements.Requirement r : BriefRequirements.extract(brief)) {
            if (r.check() == BriefRequirements.Check.DETERMINISTIC && r.blocks() != null) {
                List<String> ids = registry.matching(r.blocks());
                if (!ids.isEmpty()) {
                    families.put(r.label(), ids);
                }
            }
        }
        String folded = BriefRequirements.fold(brief);
        for (Material m : MATERIALS) {
            if (m.trigger().matcher(folded).find()) {
                List<String> ids = registry.matching(m.blocks());
                if (!ids.isEmpty()) {
                    families.putIfAbsent(m.label(), ids);
                }
            }
        }
        if (families.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("PALETTE D'IDENTIFIANTS VÉRIFIÉS (registre Minecraft ")
                .append(BlockRegistry.VERSION).append(") — pour ce que la description nomme\n\n");
        families.forEach((label, ids) -> sb.append("- ").append(label).append(" : ").append(render(ids)).append('\n'));
        sb.append("""

                Cette liste garantit l'orthographe ; elle ne limite rien : tout identifiant du registre
                reste permis. N'invente aucun nom. Si le serveur refuse un identifiant, ne le remplace
                PAS par un nom voisin (« lion_bed » n'est pas « lime_bed ») : reprends l'élément que la
                description demande, avec un identifiant de cette liste — et s'il n'est pas demandé,
                supprime l'instruction.
                """);
        return sb.toString();
    }

    /** Une famille colorée se résume en « minecraft:&lt;couleur&gt;_x » ; sinon, liste bornée. */
    static String render(List<String> ids) {
        Map<String, Integer> bySuffix = new LinkedHashMap<>();
        for (String id : ids) {
            String bare = id.substring("minecraft:".length());
            for (String color : COLORS) {
                if (bare.startsWith(color + "_")) {
                    bySuffix.merge(bare.substring(color.length() + 1), 1, Integer::sum);
                    break;
                }
            }
        }
        List<String> parts = new ArrayList<>();
        List<String> colored = new ArrayList<>();
        bySuffix.forEach((suffix, n) -> {
            if (n >= 8) {
                colored.add(suffix);
                parts.add("minecraft:<couleur>_" + suffix);
            }
        });
        int listed = 0;
        int skipped = 0;
        for (String id : ids) {
            String bare = id.substring("minecraft:".length());
            boolean inFamily = colored.stream().anyMatch(suffix -> COLORS.stream()
                    .anyMatch(c -> bare.equals(c + "_" + suffix)));
            if (inFamily) {
                continue;
            }
            if (listed < MAX_LISTED) {
                parts.add(id);
                listed++;
            } else {
                skipped++;
            }
        }
        String out = String.join(", ", parts) + (skipped > 0 ? " (et " + skipped + " autre(s) de la même famille)" : "");
        return colored.isEmpty() ? out : out + " — couleurs : " + String.join(", ", COLORS);
    }

    /** Les seize couleurs du jeu (celles du plugin, {@code BlockCatalog.COLORS}). */
    static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
}
