package com.lodygames.rpgquest.building.plan;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Les défauts d'architecture qu'un administrateur peut accepter en connaissance de cause (#747).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Le 23/10/2026, la version V-8776ad09 de l'église de test se compilait entièrement — aperçu et
 * coupes disponibles — mais ne pouvait pas être enregistrée à cause d'une seule lanterne pendue sous
 * la cloche ({@code UNSUPPORTED_BLOCK}). Le propriétaire : « Si on a un aperçu on devrait pouvoir le
 * construire avec un avertissement. » Il voulait l'examiner en jeu puis la retoucher, sans appel IA.</p>
 *
 * <h2>Ce qui reste interdit</h2>
 *
 * <p>Seuls les constats de l'audit architectural, mesurés sur une grille COMPLÈTE, sont
 * contournables, et seulement ceux qui touchent l'esthétique, la lumière, l'accessibilité ou un
 * support décoratif. Un plan illisible, un bloc ou une opération inconnus, une zone hors du volume,
 * une grille vide ou un lit / une porte incomplets ({@code BROKEN_ASSEMBLY}) ne le sont jamais : ce
 * ne sont pas des défauts, ce sont des impossibilités. Aucun de ces refus ne passe par ici — ils
 * arrêtent la génération avant l'audit, ou ne figurent pas dans {@link #EFFECTS}.</p>
 *
 * <p>Le contrôle est fait côté serveur : le panel ne fait que transmettre la liste des codes que
 * l'administrateur a lus et confirmés. Elle doit être EXACTEMENT celle que le serveur relève sur le
 * plan envoyé ; sinon rien n'est écrit — l'administrateur a confirmé autre chose.</p>
 */
public final class AcceptableDefects {

    /** Marque portée par la description d'un bâtiment enregistré malgré des défauts. */
    public static final String MARK = "ENREGISTRÉ AVEC AVERTISSEMENTS ACCEPTÉS";

    /** Code de résultat d'une génération faite malgré des défauts acceptés. */
    public static final String GENERATED_WITH_WARNINGS = "GENERATED_WITH_WARNINGS";

    /** Code du refus quand les défauts confirmés ne sont pas ceux du plan envoyé. */
    public static final String WARNINGS_CHANGED = "WARNINGS_CHANGED";

    /** Code du refus quand un défaut relevé n'est pas contournable. */
    public static final String NOT_ACCEPTABLE = "DEFECT_NOT_ACCEPTABLE";

    /** Chaque défaut contournable, et ce qu'il fera réellement une fois posé. */
    static final Map<String, String> EFFECTS = effects();

    private AcceptableDefects() {
    }

    private static Map<String, String> effects() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("UNSUPPORTED_BLOCK", "des blocs décoratifs sans support (lanterne, torche, fleur…) peuvent se "
                + "détacher et tomber en objet à la première mise à jour voisine");
        m.put("DARK_AREA", "une partie de l'intérieur reste sombre : des monstres peuvent y apparaître");
        m.put("NO_LIGHT", "l'intérieur n'a aucune lumière : des monstres peuvent y apparaître");
        m.put("GLAZING_MISSING", "il manque des fenêtres : intérieur fermé et sombre");
        m.put("GLAZING_ONE_SIDED", "toutes les fenêtres sont du même côté");
        m.put("ROOF_MISSING", "le toit ne couvre pas tout : la pluie et les monstres peuvent entrer");
        m.put("ENVELOPE_OPEN", "les murs ont des trous : la pluie et les monstres peuvent entrer");
        m.put("OPENING_DETACHED", "une porte ou une fenêtre est posée hors des murs");
        m.put("CLEAR_ERASES", "une instruction « vider » a effacé une porte ou une fenêtre : elle manquera");
        m.put("NO_ENTRANCE", "pas de porte utilisable côté entrée : il faudra en ouvrir une à la main");
        m.put("ENTRANCE_STEP", "on n'entre qu'en sautant : seuil plus haut que le terrain");
        m.put("NO_PASSAGE", "la porte ne mène nulle part : passage à dégager à la main");
        m.put("LEVEL_UNREACHABLE", "un étage n'est relié par aucun escalier : inaccessible sans retouche");
        m.put("LEVEL_CLUTTERED", "un étage est encombré : circulation difficile");
        return Map.copyOf(m);
    }

    /** Ce que fera ce défaut une fois posé ; vide s'il n'est pas contournable. */
    public static Optional<String> effect(String code) {
        return Optional.ofNullable(code == null ? null : EFFECTS.get(code));
    }

    /** Tous ces codes sont-ils contournables (et y en a-t-il au moins un) ? */
    public static boolean allAcceptable(Collection<String> codes) {
        return codes != null && !codes.isEmpty() && codes.stream().allMatch(EFFECTS::containsKey);
    }

    /**
     * Les défauts d'un verdict qu'un administrateur peut accepter, avec leur effet — vide si le
     * verdict n'est pas un refus d'architecture sur une grille complète, ou si un seul de ses codes
     * est une impossibilité.
     */
    public static Map<String, String> of(BuildingGenerator.Verdict verdict) {
        return verdict == null ? Map.of() : of(verdict.code(), verdict.blocks(), verdict.codes());
    }

    /** Même chose, à partir du code, du nombre de blocs et des codes d'un verdict à plat. */
    public static Map<String, String> of(String verdictCode, int blocks, List<String> codes) {
        if (!"ARCHITECTURE_REFUSED".equals(verdictCode) || blocks <= 0 || !allAcceptable(codes)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String code : new TreeSet<>(codes)) {
            out.put(code, EFFECTS.get(code));
        }
        return out;
    }

    /**
     * Le refus éventuel d'une génération malgré défauts : vide si l'administrateur a confirmé
     * exactement les défauts relevés, tous contournables.
     *
     * @param found    les codes bloquants relevés par le serveur sur ce plan
     * @param accepted les codes confirmés par l'administrateur
     */
    static Optional<String[]> refusal(List<String> found, Set<String> accepted) {
        Set<String> actual = new TreeSet<>(found);
        List<String> impossible = actual.stream().filter(c -> !EFFECTS.containsKey(c)).toList();
        if (!impossible.isEmpty()) {
            return Optional.of(new String[] {NOT_ACCEPTABLE, "Ce plan ne peut pas être enregistré malgré ses "
                    + "défauts : " + String.join(", ", impossible) + " n'est pas contournable (impossibilité, pas "
                    + "simple défaut). Rien n'a été écrit."});
        }
        if (!actual.equals(new TreeSet<>(accepted))) {
            return Optional.of(new String[] {WARNINGS_CHANGED, "Les défauts confirmés (" + String.join(", ",
                    new TreeSet<>(accepted)) + ") ne sont pas ceux que le serveur relève sur ce plan ("
                    + String.join(", ", actual) + ") : rien n'a été écrit. Revérifiez la version, relisez ses "
                    + "défauts, puis confirmez de nouveau."});
        }
        return Optional.empty();
    }

    /** La phrase ajoutée à la description du bâtiment : jamais requalifié « conforme ». */
    static String descriptionNote(Collection<String> codes) {
        return MARK + " : " + String.join(", ", new TreeSet<>(codes)) + ".";
    }
}
