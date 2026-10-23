package com.lodygames.rpgquest.panel.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les exigences d'un brief, et leur réalisation dans le plan et dans le résultat compilé (#747).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Aucune des versions de l'Église du hub (22/10/2026) ne contenait de cloche, de clocher ni de
 * bancs, alors que le brief les demandait — et rien ne le disait : les contrôles du serveur sont
 * géométriques. Un bâtiment valide mais sans ce que le brief demande n'est pas une réussite.</p>
 *
 * <h2>Deux sortes de contrôles, jamais confondues</h2>
 *
 * <ul>
 *   <li><strong>Déterministes</strong> : l'élément se reconnaît à un bloc précis — une cloche est
 *       {@code minecraft:bell}, un pupitre {@code minecraft:lectern}, un vitrail un verre teinté. On
 *       vérifie sa présence dans le plan ET dans la palette du résultat compilé (un « clear » peut
 *       l'avoir effacé).</li>
 *   <li><strong>Appréciations visuelles</strong> : une tour distincte, un autel, une allée, une
 *       estrade ne se reconnaissent à aucun bloc. Elles sont listées « à apprécier », jamais
 *       déclarées présentes : compter des blocs de quartz ne prouve pas qu'il y a un autel.</li>
 * </ul>
 *
 * <p>Le lexique est général — vocabulaire courant du bâtiment et du mobilier —, pas propre à un
 * projet : aucun bâtiment n'est codé ici. Une exigence niée dans sa phrase (« sans lit », « ne pas
 * générer de personnage ») n'est pas retenue.</p>
 */
public final class BriefRequirements {

    /** Comment une exigence se vérifie. */
    public enum Check {
        DETERMINISTIC, VISUAL
    }

    /**
     * Une exigence reconnue dans le brief.
     *
     * @param blocks motif des identifiants de blocs qui la réalisent (déterministe), ou {@code null}
     * @param word   le mot du brief qui l'a fait reconnaître
     */
    public record Requirement(String id, String label, Check check, Pattern blocks, String hint, String word) {
    }

    /** L'état d'une exigence : présente dans le plan ? dans le résultat compilé (si connu) ? */
    public record Status(Requirement requirement, boolean inPlan, Boolean inResult) {

        /** Manquante : absente du plan, ou du résultat quand celui-ci est connu. */
        public boolean missing() {
            return requirement.check() == Check.DETERMINISTIC && (!inPlan || Boolean.FALSE.equals(inResult));
        }

        /**
         * Dans le plan, mais le résultat compilé n'a pas été mesuré (version non construite) : NON
         * VÉRIFIÉE, pas réalisée (#747) — un « clear » ou une erreur de construction peut l'effacer.
         * Les bancs se jugent sur le plan seul, par construction.
         */
        public boolean unverified() {
            return requirement.check() == Check.DETERMINISTIC && inPlan && inResult == null
                    && !"benches".equals(requirement.id());
        }

        public String line() {
            Requirement r = requirement;
            if (r.check() == Check.VISUAL) {
                return "◌ " + r.label() + " — à apprécier visuellement (aucun bloc ne la prouve)";
            }
            String where = !inPlan ? "absent du plan"
                    : Boolean.FALSE.equals(inResult) ? "dans le plan mais ABSENT du résultat compilé (effacé ?)"
                    : inResult == null && !"benches".equals(r.id())
                    ? "présent dans le plan, NON VÉRIFIÉ dans le résultat (version non construite ou non mesurée)"
                    : inResult == null ? "présent dans le plan"
                    : "présent dans le plan et dans le résultat";
            return (missing() ? "✗ " : unverified() ? "◐ " : "✓ ") + r.label() + " — " + where + (missing() ? " : " + r.hint() : "");
        }
    }

    /** Le bilan de conformité d'une version au brief. */
    public record Report(List<Status> statuses) {

        public Report {
            statuses = List.copyOf(statuses == null ? List.of() : statuses);
        }

        public List<Status> missing() {
            return statuses.stream().filter(Status::missing).toList();
        }

        public List<Status> visual() {
            return statuses.stream().filter(s -> s.requirement().check() == Check.VISUAL).toList();
        }

        public boolean complete() {
            return missing().isEmpty();
        }

        public boolean isEmpty() {
            return statuses.isEmpty();
        }

        /** Une ligne pour le journal et l'écran. */
        public String summary() {
            if (statuses.isEmpty()) {
                return "Aucune exigence reconnue dans la description.";
            }
            long deterministic = statuses.size() - visual().size();
            long unverified = statuses.stream().filter(Status::unverified).count();
            return (deterministic - missing().size() - unverified) + "/" + deterministic
                    + " exigence(s) vérifiable(s) réalisée(s)"
                    + (unverified == 0 ? "" : ", " + unverified + " non vérifiée(s) dans le résultat")
                    + (missing().isEmpty() ? "" : " — manquent : " + String.join(", ",
                    missing().stream().map(s -> s.requirement().label()).toList()))
                    + (visual().isEmpty() ? "" : " ; " + visual().size() + " à apprécier visuellement");
        }

        /** Le bloc du prompt : ce que le brief exige et que la version transmise ne réalise pas. */
        public String promptBlock() {
            if (statuses.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("CONFORMITÉ AU BRIEF — exigences relevées dans la description\n\n");
            for (Status s : statuses) {
                sb.append("- ").append(s.line()).append('\n');
            }
            if (!missing().isEmpty()) {
                sb.append("\nUn plan qui passe les contrôles techniques sans ces éléments N'EST PAS terminé : "
                        + "ajoute chaque élément marqué ✗, avec le bloc indiqué, sans retirer ce qui est déjà juste.\n");
            }
            return sb.toString();
        }
    }

    private record Entry(String id, String label, Check check, Pattern trigger, Pattern blocks, String hint) {
    }

    private static Entry det(String id, String label, String trigger, String blocks, String hint) {
        return new Entry(id, label, Check.DETERMINISTIC, Pattern.compile(trigger), Pattern.compile(blocks), hint);
    }

    private static Entry visual(String id, String label, String trigger) {
        return new Entry(id, label, Check.VISUAL, Pattern.compile(trigger), null, "");
    }

    /** Le lexique : déclencheurs (texte sans accents, minuscules) → bloc qui réalise l'élément. */
    private static final List<Entry> LEXICON = List.of(
            det("bell", "Cloche", "\\bcloches?\\b", "^minecraft:bell$",
                    "pose un bloc minecraft:bell (suspendu sous une poutre ou posé sur un bloc plein)"),
            det("lectern", "Pupitre / lutrin", "\\b(pupitres?|lutrins?)\\b", "^minecraft:lectern$",
                    "pose un minecraft:lectern"),
            det("stained_glass", "Vitraux (verre teinté)", "\\b(vitra(il|ux)|verres? (colore|teinte)s?)\\b",
                    "^minecraft:[a-z_]+_stained_glass(_pane)?$",
                    "utilise des vitrages colorés (minecraft:blue_stained_glass_pane…) dans les fenêtres"),
            det("carpet", "Tapis", "\\btapis\\b", "^minecraft:[a-z_]+_carpet$",
                    "pose un tapis coloré (minecraft:red_carpet…) sur le sol"),
            det("lantern", "Lanternes", "\\blanternes?\\b", "^minecraft:(soul_)?lantern$",
                    "pose des minecraft:lantern (sur un bloc plein, ou suspendues sous un plafond)"),
            det("candle", "Bougies", "\\bbougies?\\b", "^minecraft:([a-z_]+_)?candle$",
                    "pose des bougies (minecraft:candle ou une bougie colorée) sur un support"),
            det("flowers", "Fleurs / compositions florales", "\\b(fleurs?|florales?|bouquets?)\\b",
                    "^minecraft:(potted_[a-z_]+|flower_pot|[a-z_]*tulip|poppy|dandelion|allium|azure_bluet|"
                            + "oxeye_daisy|cornflower|lily_of_the_valley|rose_bush|peony|lilac|sunflower)$",
                    "pose des fleurs en pot (minecraft:potted_poppy…)"),
            det("benches", "Bancs / assises", "\\b(bancs?|assises?|banquettes?)\\b", "^minecraft:[a-z_]+_stairs$",
                    "pose des rangées d'escaliers en bois au niveau du sol (op: box avec un bloc *_stairs), "
                            + "en plus de ceux du toit"),
            det("bed", "Lit(s)", "\\blits?\\b", "^minecraft:[a-z_]+_bed$", "pose un lit avec « op: bed »"),
            det("bookshelf", "Étagères de livres", "\\b(bibliotheques?|etageres?|livres)\\b",
                    "^minecraft:(chiseled_)?bookshelf$", "pose des minecraft:bookshelf le long des murs"),
            det("furnace", "Four", "\\b(fours?|fourneaux?)\\b", "^minecraft:(furnace|smoker|blast_furnace)$",
                    "pose un minecraft:furnace ou un minecraft:smoker"),
            det("anvil", "Enclume", "\\benclumes?\\b", "^minecraft:(chipped_|damaged_)?anvil$",
                    "pose une minecraft:anvil"),
            det("crafting", "Établi", "\\b(etablis?|tables? de craft)\\b", "^minecraft:crafting_table$",
                    "pose une minecraft:crafting_table"),
            det("chest", "Coffre(s)", "\\b(coffres?|tonneaux?)\\b", "^minecraft:(chest|barrel|trapped_chest)$",
                    "pose un minecraft:chest ou un minecraft:barrel"),
            visual("tower", "Tour distincte du corps principal", "\\b(tours?|clochers?|tourelles?|donjons?)\\b"),
            visual("altar", "Autel", "\\bautels?\\b"),
            visual("dais", "Estrade / podium", "\\b(estrades?|podiums?)\\b"),
            visual("aisle", "Allée centrale", "\\ballees?\\b"),
            visual("porch", "Porche", "\\bporches?\\b"),
            visual("cross", "Croix", "\\bcroix\\b"),
            visual("chimney", "Cheminée", "\\bcheminees?\\b"),
            visual("frame", "Charpente apparente", "\\bcharpentes?\\b"));

    private static final Pattern NEGATION = Pattern.compile(
            "\\b(sans|aucune?|ni|ne pas|pas de|pas d'|eviter|evite|interdit|jamais)\\b");
    private static final Pattern CONTRAST = Pattern.compile(
            "\\b(avec|mais|cependant|pourtant|plutot|en revanche|par contre)\\b");

    private BriefRequirements() {
    }

    /** Les exigences reconnues dans un brief, dans l'ordre du lexique, chacune une seule fois. */
    public static List<Requirement> extract(String brief) {
        String text = fold(brief);
        List<Requirement> out = new ArrayList<>();
        for (Entry e : LEXICON) {
            Matcher m = e.trigger().matcher(text);
            while (m.find()) {
                if (!negated(text, m.start())) {
                    out.add(new Requirement(e.id(), e.label(), e.check(), e.blocks(), e.hint(), m.group()));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Le mot est-il nié dans sa propre phrase (« sans lit », « ne pas générer de personnage ») ? Une
     * négation porte jusqu'au mot, sauf si un mot de contraste l'interrompt : dans « sans lit ni
     * coffre, avec un four », le four reste demandé.
     */
    private static boolean negated(String text, int at) {
        int start = Math.max(0, at - 80);
        String before = text.substring(start, at);
        int stop = Math.max(Math.max(before.lastIndexOf('.'), before.lastIndexOf('\n')), before.lastIndexOf(';'));
        String clause = stop < 0 ? before : before.substring(stop + 1);
        Matcher negation = NEGATION.matcher(clause);
        int last = -1;
        while (negation.find()) {
            last = negation.end();
        }
        return last >= 0 && !CONTRAST.matcher(clause.substring(last)).find();
    }

    /**
     * Le bilan d'une version : exigences du brief contre les blocs de son plan et, si le serveur l'a
     * mesurée, la palette de son résultat compilé.
     *
     * @param palette bloc → cellules du résultat compilé ; {@code null} ou vide si inconnu
     */
    public static Report evaluate(String brief, String planYaml, Map<String, ?> palette) {
        List<Requirement> requirements = extract(brief);
        Set<String> planBlocks = planBlocks(planYaml);
        Set<String> furnitureBlocks = furnitureBlocks(planYaml);
        boolean measured = palette != null && !palette.isEmpty();
        List<Status> out = new ArrayList<>();
        for (Requirement r : requirements) {
            if (r.check() == Check.VISUAL) {
                out.add(new Status(r, false, null));
                continue;
            }
            // Les bancs se reconnaissent à des escaliers posés hors toit et hors volée : ceux d'un
            // « gable » ou d'un « staircase » ne sont pas des assises. Le résultat compilé ne permet
            // pas de faire la différence ; seul le plan est jugé.
            boolean benches = "benches".equals(r.id());
            Set<String> pool = benches ? furnitureBlocks : planBlocks;
            boolean inPlan = pool.stream().anyMatch(b -> r.blocks().matcher(b).matches());
            Boolean inResult = !measured || benches ? null
                    : palette.keySet().stream().anyMatch(b -> r.blocks().matcher(b).matches());
            out.add(new Status(r, inPlan, inResult));
        }
        return new Report(out);
    }

    private static final Pattern BLOCK = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final Pattern OP = Pattern.compile("(?m)^\\s*-\\s*op:\\s*([a-z_]+)");

    /** Tous les blocs cités par le plan (sans propriétés). */
    static Set<String> planBlocks(String plan) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = BLOCK.matcher(plan == null ? "" : plan);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Les blocs des instructions qui ne sont ni un toit, ni une volée d'escalier. */
    static Set<String> furnitureBlocks(String plan) {
        Set<String> out = new LinkedHashSet<>();
        String text = plan == null ? "" : plan;
        Matcher op = OP.matcher(text);
        List<int[]> starts = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        while (op.find()) {
            starts.add(new int[] {op.start()});
            kinds.add(op.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            String kind = kinds.get(i);
            if ("gable".equals(kind) || "staircase".equals(kind)) {
                continue;
            }
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            out.addAll(planBlocks(text.substring(starts.get(i)[0], end)));
        }
        return out;
    }

    /** Minuscules, sans accents, apostrophes typographiques normalisées. */
    static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('’', '\'').toLowerCase(Locale.ROOT);
    }
}
