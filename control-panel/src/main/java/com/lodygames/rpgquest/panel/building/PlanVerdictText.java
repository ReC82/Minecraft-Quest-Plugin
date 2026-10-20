package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le verdict du serveur sur un plan, dit en français courant (issue #265).
 *
 * <p>PlugAdmin n'est pas réservé aux développeurs : un joueur administrateur ne doit jamais lire
 * {@code PLAN_INVALID}, {@code NO_ENTRANCE} ou « instruction 16 » pour savoir quoi faire. Chaque code
 * devient une phrase sur le <em>bâtiment</em> (entrée, fenêtres, lumière, toit, étages) et une piste
 * de correction. Les codes bruts restent dans les détails techniques, pour l'assistance.</p>
 */
public final class PlanVerdictText {

    /** Un problème, en clair, et ce qu'on peut faire. */
    public record Problem(String text, String hint) {
    }

    private PlanVerdictText() {
    }

    private static final Map<String, Problem> BY_CODE = Map.ofEntries(
            Map.entry("NO_ENTRANCE", new Problem("Le bâtiment n'a pas de porte utilisable du côté de "
                    + "l'entrée.", "Demander à l'IA d'ajouter une porte sur la façade d'entrée.")),
            Map.entry("GLAZING_MISSING", new Problem("Il manque des fenêtres.",
                    "Demander au moins deux fenêtres.")),
            Map.entry("GLAZING_ONE_SIDED", new Problem("Toutes les fenêtres sont du même côté.",
                    "Demander des fenêtres sur au moins deux côtés.")),
            Map.entry("NO_LIGHT", new Problem("L'intérieur n'a aucune lumière.",
                    "Demander des lanternes ou des torches à l'intérieur.")),
            Map.entry("DARK_AREA", new Problem("Une partie de l'intérieur reste dans le noir.",
                    "Demander davantage de lumières, mieux réparties.")),
            Map.entry("ENVELOPE_OPEN", new Problem("Les murs ont des trous.",
                    "Demander des murs fermés (en dehors des portes et fenêtres).")),
            Map.entry("ROOF_MISSING", new Problem("Le toit ne couvre pas tout le bâtiment : il "
                    + "pleuvrait à l'intérieur.", "Demander un toit complet.")),
            Map.entry("LEVEL_UNREACHABLE", new Problem("Un étage n'est relié au rez-de-chaussée par "
                    + "aucun escalier ni aucune échelle.",
                    "Demander un escalier ou une échelle, avec une ouverture dans le plancher.")),
            // #305
            Map.entry("PROJECT_LIMITS_EXCEEDED", new Problem("Le plan est plus grand que les limites du "
                    + "projet (largeur X, longueur Z ou hauteur Y) : le motif nomme l'axe dépassé.",
                    "Demander à l'IA de réduire cette dimension, ou agrandir la limite du projet.")),
            Map.entry("PLAN_INVALID", new Problem("Le plan proposé par l'IA contient une erreur que le "
                    + "serveur ne sait pas construire.",
                    "Demander à l'IA de corriger le plan : le motif exact lui est transmis.")),
            Map.entry("ENVELOPE_MONOTONOUS", new Problem("La façade n'utilise presque qu'un seul "
                    + "matériau.", "Demander un ou deux matériaux de plus (bois, pierre…).")),
            Map.entry("MATERIAL_DOMINANT", new Problem("Un seul matériau domine nettement.",
                    "Demander plus de variété dans les matériaux.")),
            Map.entry("FURNISHING_THIN", new Problem("L'intérieur est presque vide.",
                    "Demander du mobilier (étagères, tables, coffres…).")),
            // #288–#292
            Map.entry("ENTRANCE_STEP", new Problem("On n'entre qu'en sautant : le seuil dépasse le terrain "
                    + "sans marche.", "Le moteur pose les marches s'il a la place ; sinon demander un perron.")),
            Map.entry("OPENING_DETACHED", new Problem("Une porte ou une fenêtre est posée hors des murs.",
                    "Demander à l'IA de la placer dans le mur construit : le motif exact lui est transmis.")),
            Map.entry("UNSUPPORTED_BLOCK", new Problem("Des blocs tomberaient à la pose (lanterne en l'air, "
                    + "torche sans mur…).", "Le moteur suspend les lanternes s'il le peut ; sinon demander "
                    + "une correction.")),
            Map.entry("BROKEN_ASSEMBLY", new Problem("Un lit ou une porte est incomplet : il disparaîtrait à "
                    + "la pose.", "Demander « op: bed » / « op: door » à la place.")),
            Map.entry("BLOCK_REJECTED", new Problem("Le serveur ne connaît pas certains blocs du plan.",
                    "Demander à l'IA d'employer les identifiants réels : le motif exact lui est transmis.")),
            Map.entry("LEVEL_CLUTTERED", new Problem("Un étage est encombré : on ne peut presque pas y "
                    + "circuler.", "Demander du mobilier le long des murs, avec des allées.")),
            Map.entry("WINDOW_OBSTRUCTED", new Problem("Des fenêtres sont masquées par un bloc plein à "
                    + "l'intérieur.", "Demander de déplacer ces fenêtres ou ce qui les bouche."))
    );

    /** Les problèmes bloquants, en clair. Un code inconnu n'est jamais tu. */
    public static List<Problem> blocking(BuildingPlanVerdict verdict) {
        List<Problem> out = new ArrayList<>();
        if (verdict == null || !verdict.available() || verdict.accepted()) {
            return out;
        }
        List<String> codes = verdict.codes().isEmpty() ? List.of(verdict.code()) : verdict.codes();
        for (String code : codes) {
            out.add(of(code));
        }
        return out;
    }

    /** Un code, en clair. */
    public static Problem of(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Problem known = BY_CODE.get(c);
        return known != null ? known : new Problem("Le serveur a refusé ce plan.",
                "Demander une correction à l'IA, ou transmettre le diagnostic à l'assistance.");
    }

    /** La phrase de synthèse d'un verdict, pour le haut de la page. */
    public static String headline(BuildingPlanVerdict verdict, boolean briefDivergence) {
        if (verdict == null || !verdict.available()) {
            return "Le bâtiment n'a pas encore été vérifié.";
        }
        if (!verdict.accepted()) {
            int n = blocking(verdict).size();
            return n <= 1 ? "Un point est à corriger avant de pouvoir enregistrer ce bâtiment."
                    : n + " points sont à corriger avant de pouvoir enregistrer ce bâtiment.";
        }
        // #289 : un audit géométrique ne juge ni la beauté ni la fidélité au brief — le dire.
        return briefDivergence
                ? "Le bâtiment est solide, mais il ne correspond pas tout à fait à votre description."
                : "Contrôles techniques réussis : entrée, fenêtres, lumière, toit, étages et stabilité sont "
                        + "en ordre. L'esthétique et le respect de votre description se jugent sur l'aperçu.";
    }
}
