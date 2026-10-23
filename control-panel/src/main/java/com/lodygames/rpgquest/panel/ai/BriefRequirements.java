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

    /**
     * Comment une exigence se vérifie : un bloc précis (déterministe), une mesure géométrique sur le
     * résultat compilé (#747 : une tour, une allée), ou l'œil seulement.
     */
    public enum Check {
        DETERMINISTIC, MEASURED, VISUAL
    }

    /**
     * Une exigence reconnue dans le brief.
     *
     * @param blocks motif des identifiants de blocs qui la réalisent (déterministe), ou {@code null}
     * @param word   le mot du brief qui l'a fait reconnaître
     */
    public record Requirement(String id, String label, Check check, Pattern blocks, String hint, String word) {
    }

    /**
     * L'état d'une exigence : présente dans le plan ? dans le résultat compilé (si connu) ?
     *
     * @param evidence pour une exigence mesurée, ce que la mesure a trouvé (ou pas)
     */
    public record Status(Requirement requirement, boolean inPlan, Boolean inResult, String evidence) {

        public Status(Requirement requirement, boolean inPlan, Boolean inResult) {
            this(requirement, inPlan, inResult, "");
        }

        /** Manquante : absente du plan, ou du résultat quand celui-ci est connu (ou mesurée absente). */
        public boolean missing() {
            if (requirement.check() == Check.MEASURED) {
                return Boolean.FALSE.equals(inResult);
            }
            return requirement.check() == Check.DETERMINISTIC && (!inPlan || Boolean.FALSE.equals(inResult));
        }

        /**
         * Dans le plan, mais le résultat compilé n'a pas été mesuré (version non construite) : NON
         * VÉRIFIÉE, pas réalisée (#747) — un « clear » ou une erreur de construction peut l'effacer.
         * Les bancs se jugent sur le plan seul, par construction.
         */
        public boolean unverified() {
            if (requirement.check() == Check.MEASURED) {
                return inResult == null;
            }
            return requirement.check() == Check.DETERMINISTIC && inPlan && inResult == null
                    && !"benches".equals(requirement.id());
        }

        public String line() {
            Requirement r = requirement;
            if (r.check() == Check.MEASURED) {
                return (inResult == null ? "◐ " : inResult ? "✓ " : "✗ ") + r.label() + " — "
                        + (inResult == null ? "non mesurée (aperçu du résultat compilé indisponible)"
                        : (inResult ? "mesurée sur le résultat compilé : " : "ABSENTE du résultat compilé : ")
                        + evidence + (inResult ? "" : " — " + r.hint()));
            }
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

    /**
     * Le bilan de conformité d'une version au brief.
     *
     * @param extras les éléments posés par le plan que la description ne demande pas (#747 : un lit
     *               dans une église) — signalés, jamais retirés en silence
     */
    public record Report(List<Status> statuses, List<String> extras) {

        public Report {
            statuses = List.copyOf(statuses == null ? List.of() : statuses);
            extras = List.copyOf(extras == null ? List.of() : extras);
        }

        public Report(List<Status> statuses) {
            this(statuses, List.of());
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
                return "Aucune exigence reconnue dans la description." + extrasSuffix();
            }
            long deterministic = statuses.size() - visual().size();
            long unverified = statuses.stream().filter(Status::unverified).count();
            return (deterministic - missing().size() - unverified) + "/" + deterministic
                    + " exigence(s) vérifiable(s) réalisée(s)"
                    + (unverified == 0 ? "" : ", " + unverified + " non vérifiée(s) dans le résultat")
                    + (missing().isEmpty() ? "" : " — manquent : " + String.join(", ",
                    missing().stream().map(s -> s.requirement().label()).toList()))
                    + (visual().isEmpty() ? "" : " ; " + visual().size() + " à apprécier visuellement")
                    + extrasSuffix();
        }

        private String extrasSuffix() {
            return extras.isEmpty() ? "" : " ; ajout(s) non demandé(s) : " + String.join(", ", extras);
        }

        /** Le bloc du prompt : ce que le brief exige et que la version transmise ne réalise pas. */
        public String promptBlock() {
            if (statuses.isEmpty() && extras.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("CONFORMITÉ AU BRIEF — exigences relevées dans la description\n\n");
            for (Status s : statuses) {
                sb.append("- ").append(s.line()).append('\n');
            }
            for (String extra : extras) {
                sb.append("- ⚠ ").append(extra).append(" — AJOUT NON DEMANDÉ par la description : retire-le, ou "
                        + "remplace-le par un élément qu'elle demande.\n");
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

    private static Entry measured(String id, String label, String trigger, String hint) {
        return new Entry(id, label, Check.MEASURED, Pattern.compile(trigger), null, hint);
    }

    /**
     * Les éléments FONCTIONNELS qu'un plan ajoute volontiers par habitude (#747 : le lit de l'église) :
     * présents sans être demandés, ils sont signalés. Le décor courant (tapis, lanternes…) ne l'est pas.
     */
    private static final Set<String> FLAGGED_EXTRAS = Set.of("bed", "furnace", "anvil", "crafting", "chest",
            "bookshelf");

    /** Exigence dérivée : la cloche est-elle DANS la tour, quand la description demande les deux ? */
    private static final Requirement BELL_IN_TOWER = new Requirement("bell_tower", "Cloche dans la tour",
            Check.MEASURED, null, "suspends la cloche dans la partie haute de la tour (chambre des cloches), "
            + "sous une poutre", "");

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
            measured("tower", "Tour distincte du corps principal", "\\b(tours?|clochers?|tourelles?|donjons?)\\b",
                    "construis la tour comme un volume à part (ses murs sur sa propre emprise, d'au moins 3 × 3), "
                            + "qui dépasse le toit du corps principal d'au moins 4 blocs, avec son propre toit"),
            visual("altar", "Autel", "\\bautels?\\b"),
            visual("dais", "Estrade / podium", "\\b(estrades?|podiums?)\\b"),
            measured("aisle", "Allée centrale", "\\ballees?\\b",
                    "trace l'allée par une file continue de tapis au sol, de l'entrée vers le fond"),
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
        return evaluate(brief, planYaml, palette, null);
    }

    /**
     * Le même bilan, avec les MESURES prises sur la grille compilée de l'aperçu (#747) : tour, allée,
     * cloche dans la tour. Sans grille, ces exigences restent « non mesurées » — jamais présumées.
     */
    public static Report evaluate(String brief, String planYaml, Map<String, ?> palette,
                                  com.lodygames.rpgquest.panel.building.BuildingVoxels voxels) {
        return evaluate(brief, planYaml, palette, voxels, "");
    }

    /**
     * Le bilan complet : exigences du brief, et chaque VOLUME de la conception structurée mesuré sur le
     * résultat compilé (#747) — un volume prévu mais non construit est une exigence manquante.
     */
    public static Report evaluate(String brief, String planYaml, Map<String, ?> palette,
                                  com.lodygames.rpgquest.panel.building.BuildingVoxels voxels, String design) {
        Report base = evaluateBrief(brief, planYaml, palette, voxels);
        ArchitecturalDesign structure = ArchitecturalDesign.parse(design);
        if (!structure.structured()) {
            return base;
        }
        java.util.Optional<CompiledMeasures> measures = CompiledMeasures.of(voxels);
        List<Status> out = new ArrayList<>(base.statuses());
        for (ArchitecturalDesign.Volume v : structure.volumes()) {
            Requirement r = new Requirement("volume", "Volume « " + v.name() + " » de la conception", Check.MEASURED,
                    null, "construis-le à ses coordonnées (" + v.coordinates() + "), avec ses propres murs et son toit",
                    v.name());
            if (measures.isEmpty()) {
                out.add(new Status(r, true, null, ""));
                continue;
            }
            int[] reach = measures.get().perimeterReaching(v.x0(), v.x1(), v.z0(), v.z1(), v.y1());
            boolean realised = reach[1] > 0 && reach[0] * 10 >= reach[1] * 7;
            out.add(new Status(r, true, realised, reach[0] + "/" + reach[1] + " colonnes de son pourtour montent "
                    + "jusqu'à y=" + (v.y1() - 1) + " ou plus"));
        }
        return new Report(out, base.extras());
    }

    private static Report evaluateBrief(String brief, String planYaml, Map<String, ?> palette,
                                        com.lodygames.rpgquest.panel.building.BuildingVoxels voxels) {
        java.util.Optional<CompiledMeasures> measures = CompiledMeasures.of(voxels);
        Matcher front = FRONT.matcher(planYaml == null ? "" : planYaml);
        String axis = CompiledMeasures.entranceAxis(front.find() ? front.group(1) : "");
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
            if (r.check() == Check.MEASURED) {
                out.add(measure(r, measures, axis));
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
        boolean tower = requirements.stream().anyMatch(r -> "tower".equals(r.id()));
        boolean bell = requirements.stream().anyMatch(r -> "bell".equals(r.id()));
        if (tower && bell) {
            out.add(measure(BELL_IN_TOWER, measures, axis));
        }
        Set<String> requested = new java.util.HashSet<>();
        requirements.forEach(r -> requested.add(r.id()));
        List<String> extras = new ArrayList<>();
        for (Entry e : LEXICON) {
            if (FLAGGED_EXTRAS.contains(e.id()) && !requested.contains(e.id())
                    && furnitureBlocks.stream().anyMatch(b -> e.blocks().matcher(b).matches())) {
                extras.add(e.label());
            }
        }
        return new Report(out, extras);
    }

    /** Une mesure sur le résultat compilé ; « non mesurée » sans grille. */
    private static Status measure(Requirement r, java.util.Optional<CompiledMeasures> measures, String axis) {
        if (measures.isEmpty()) {
            return new Status(r, true, null, "");
        }
        CompiledMeasures m = measures.get();
        return switch (r.id()) {
            case "tower" -> m.highPart().map(h -> new Status(r, true, true, h.describe()))
                    .orElseGet(() -> new Status(r, true, false, "aucune partie d'au moins 3 × 3 colonnes (un pourtour de murs) ne dépasse "
                            + "de " + CompiledMeasures.TOWER_RISE + " blocs la hauteur médiane du bâtiment (y="
                            + m.medianTop() + ")"));
            case "bell_tower" -> m.bellInHighPart().map(c -> new Status(r, true, true, "cloche en " + c[0] + "/"
                            + c[1] + "/" + c[2] + ", dans la partie haute"))
                    .orElseGet(() -> new Status(r, true, false, m.highPart().isEmpty() ? "pas de partie haute qui "
                            + "puisse l'abriter" : m.cellsOf("bell").isEmpty() ? "aucune cloche dans le résultat"
                            : "la cloche n'est pas dans la partie haute (" + m.highPart().get().describe() + ")"));
            case "aisle" -> {
                CompiledMeasures.Run run = m.longestCarpetRun(axis);
                String found = run.length() == 0 ? "aucun tapis" + (axis.isEmpty() ? "" : " en file selon " + axis
                        + " (l'axe de l'entrée)") : "plus longue file de tapis : " + run.length() + " blocs selon "
                        + run.axis() + (axis.isEmpty() ? "" : " (l'axe de l'entrée)") + ", depuis " + run.x() + "/"
                        + run.y() + "/" + run.z();
                yield new Status(r, true, m.hasAisle(axis), found);
            }
            default -> new Status(r, true, null, "");
        };
    }

    private static final Pattern BLOCK = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final Pattern FRONT = Pattern.compile("(?m)^front:\\s*([A-Za-z]+)");
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
