package com.lodygames.rpgquest.panel.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La partie STRUCTURÉE d'une conception détaillée (#747) : volumes nommés et reliés, éléments du brief
 * rattachés à un volume — lue, vérifiée, puis imposée au plan comme un contrat.
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Église du hub, V-2caffe63 (23/10/2026) : « conception détaillée » vide, et un plan écrit d'un seul
 * jet qui posait trois cloches au sommet d'une colonne d'un bloc au lieu d'une tour-clocher, un lit
 * dans la nef, aucune bougie ni fleur. Le texte libre de #289 ne suffisait pas : rien ne disait au
 * panel quels volumes le bâtiment devait avoir, ni où chaque exigence se trouvait. La conception
 * déclare donc ses volumes et ses éléments sous une forme fixe, une ligne chacun :</p>
 *
 * <pre>
 * VOLUME nef | rôle: corps principal | x 3..13 | z 4..18 | y 0..9 | toit: deux pans axe z | relié à: clocher par arche
 * ÉLÉMENT Bancs | volume: nef | position: x 4..7 et 9..12, z 8..15, y 1 | blocs: minecraft:spruce_stairs
 * </pre>
 *
 * <p>Rien n'est propre à un bâtiment : les rôles et les exigences viennent de la description. La seule
 * règle générale : un élément DISTINCT demandé (tour, clocher, porche…) est un volume à part, relié au
 * reste, et plus haut que le corps principal quand c'est une tour.</p>
 */
public final class ArchitecturalDesign {

    /** Un toit de volume qui n'est pas plat : deux pans, pointu, en pente, à pignons. */
    private static final java.util.regex.Pattern PITCHED_ROOF = java.util.regex.Pattern.compile(
            "\\b(pans?|pointu|pente|pignons?|gable)");

    /** Un volume déclaré. Coordonnées du plan, bornes incluses ; {@code y1} = haut des murs. */
    public record Volume(String name, String role, int x0, int x1, int z0, int z1, int y0, int y1, String roof,
                         String link) {

        boolean tower() {
            String t = BriefRequirements.fold(name + " " + role);
            return TOWER.matcher(t).find();
        }

        int area() {
            return (x1 - x0 + 1) * (z1 - z0 + 1);
        }

        public String coordinates() {
            return "x " + x0 + "..." + x1 + ", z " + z0 + "..." + z1 + ", y " + y0 + "..." + y1;
        }
    }

    /** Un élément du brief rattaché à un volume. */
    public record Element(String requirement, String volume, String position, String blocks) {
    }

    /** Une constatation : {@code ok} ou une lacune à corriger. */
    public record Finding(boolean ok, String text) {

        public String line() {
            return (ok ? "✓ " : "✗ ") + text;
        }
    }

    private static final Pattern TOWER = Pattern.compile("\\b(tours?|clochers?|campaniles?|tourelles?|donjons?|beffrois?)\\b");
    private static final Pattern VOLUME = Pattern.compile("(?im)^[\\s>*•-]*VOLUME\\s+([^|\\n]+)\\|([^\\n]*)$");
    private static final Pattern ELEMENT = Pattern.compile("(?im)^[\\s>*•-]*[ÉE]L[ÉE]MENT\\s+([^|\\n]+)\\|([^\\n]*)$");
    private static final String RANGE = "\\s*[:=]?\\s*(\\d+)\\s*(?:\\.\\.\\.?|…|–|—|-|à)\\s*(\\d+)";
    private static final Pattern X = Pattern.compile("(?i)(?<![a-z])x" + RANGE);
    private static final Pattern Y = Pattern.compile("(?i)(?<![a-z])y" + RANGE);
    private static final Pattern Z = Pattern.compile("(?i)(?<![a-z])z" + RANGE);

    private final List<Volume> volumes;
    private final List<Element> elements;
    private final List<String> unreadable;

    private ArchitecturalDesign(List<Volume> volumes, List<Element> elements, List<String> unreadable) {
        this.volumes = List.copyOf(volumes);
        this.elements = List.copyOf(elements);
        this.unreadable = List.copyOf(unreadable);
    }

    /** Lit les lignes VOLUME et ÉLÉMENT d'une conception ; le reste du texte est ignoré. */
    public static ArchitecturalDesign parse(String design) {
        String text = design == null ? "" : design.replace("**", "").replace("`", "");
        List<Volume> volumes = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        Matcher v = VOLUME.matcher(text);
        while (v.find()) {
            String name = v.group(1).strip();
            String rest = v.group(2);
            Matcher x = X.matcher(rest);
            Matcher y = Y.matcher(rest);
            Matcher z = Z.matcher(rest);
            if (!x.find() || !y.find() || !z.find()) {
                unreadable.add(name);
                continue;
            }
            volumes.add(new Volume(name, field(rest, "r[ôo]le"), lo(x), hi(x), lo(z), hi(z), lo(y), hi(y),
                    field(rest, "toit"), field(rest, "reli[ée]e? [àa]|reli[ée]s? [àa]|li[ée] [àa]")));
        }
        List<Element> elements = new ArrayList<>();
        Matcher e = ELEMENT.matcher(text);
        while (e.find()) {
            String rest = e.group(2);
            elements.add(new Element(e.group(1).strip(), field(rest, "volume"), field(rest, "position"),
                    field(rest, "blocs?")));
        }
        return new ArchitecturalDesign(volumes, elements, unreadable);
    }

    public List<Volume> volumes() {
        return volumes;
    }

    public List<Element> elements() {
        return elements;
    }

    public boolean structured() {
        return !volumes.isEmpty();
    }

    /** Le corps principal : le rôle le dit, sinon la plus grande emprise. */
    public Optional<Volume> main() {
        return volumes.stream().filter(v -> BriefRequirements.fold(v.role()).contains("principal")).findFirst()
                .or(() -> volumes.stream().max(java.util.Comparator.comparingInt(Volume::area)));
    }

    /**
     * La conception confrontée au brief et aux limites du projet. Chaque lacune est formulée pour être
     * corrigée dans le plan : c'est le même texte que le panel affiche et que l'IA reçoit.
     */
    public List<Finding> check(String brief, int maxWidth, int maxDepth, int maxHeight) {
        List<Finding> out = new ArrayList<>();
        if (!structured()) {
            out.add(new Finding(false, "La conception ne déclare aucun VOLUME lisible : le plan n'a aucune "
                    + "structure à suivre."));
            return out;
        }
        out.add(new Finding(true, volumes.size() + " volume(s) déclaré(s) : " + String.join(", ",
                volumes.stream().map(Volume::name).toList())));
        for (String name : unreadable) {
            out.add(new Finding(false, "Volume « " + name + " » sans coordonnées x, y, z lisibles."));
        }
        Set<String> names = new LinkedHashSet<>();
        volumes.forEach(v -> names.add(BriefRequirements.fold(v.name())));
        for (Volume v : volumes) {
            if (v.x0() > v.x1() || v.z0() > v.z1() || v.y0() > v.y1() || v.x1() >= maxWidth
                    || v.z1() >= maxDepth || v.y1() >= maxHeight) {
                out.add(new Finding(false, "Volume « " + v.name() + " » (" + v.coordinates() + ") hors des limites du "
                        + "projet : x ≤ " + (maxWidth - 1) + ", z ≤ " + (maxDepth - 1) + ", y ≤ " + (maxHeight - 1)
                        + " (toit compris)."));
            }
            if (volumes.size() > 1 && !v.link().isBlank() && names.stream().noneMatch(
                    n -> BriefRequirements.fold(v.link()).contains(n))) {
                out.add(new Finding(false, "Volume « " + v.name() + " » relié à « " + v.link() + " », qui n'est "
                        + "pas un volume déclaré."));
            }
        }
        if (volumes.size() > 1 && volumes.stream().allMatch(v -> v.link().isBlank())) {
            out.add(new Finding(false, "Aucun volume n'est relié à un autre (« relié à: … ») : la circulation "
                    + "entre eux n'est pas conçue."));
        }
        List<BriefRequirements.Requirement> wanted = BriefRequirements.extract(brief);
        boolean towerWanted = wanted.stream().anyMatch(r -> "tower".equals(r.id()));
        if (towerWanted) {
            Optional<Volume> main = main();
            Optional<Volume> tower = volumes.stream().filter(Volume::tower)
                    .filter(v -> main.isEmpty() || v != main.get()).findFirst();
            if (tower.isEmpty()) {
                out.add(new Finding(false, "La description demande une tour : aucun VOLUME distinct de rôle tour / "
                        + "clocher n'est déclaré. Une colonne ou une cloche posée sur un autre volume n'en est pas une."));
            } else if (main.isPresent() && tower.get().y1() <= main.get().y1()) {
                out.add(new Finding(false, "La tour « " + tower.get().name() + " » (murs jusqu'à y=" + tower.get().y1()
                        + ") n'est pas plus haute que le corps principal (y=" + main.get().y1() + ")."));
            } else {
                out.add(new Finding(true, "Tour distincte : « " + tower.get().name() + " » (" + tower.get().coordinates()
                        + ")" + (tower.get().link().isBlank() ? "" : ", reliée à " + tower.get().link())));
            }
        }
        for (BriefRequirements.Requirement r : wanted) {
            if ("tower".equals(r.id())) {
                continue;
            }
            if ("roof".equals(r.id())) {
                // #747 (24/10) : un toit se déclare sur la ligne VOLUME (« toit: deux pans »), pas en ÉLÉMENT.
                Optional<Volume> pitched = volumes.stream().filter(v -> PITCHED_ROOF.matcher(
                        BriefRequirements.fold(v.roof())).find()).findFirst();
                out.add(pitched.map(v -> new Finding(true, r.label() + " : " + v.name() + " (toit : " + v.roof() + ")"))
                        .orElseGet(() -> new Finding(false, r.label() + " : aucun VOLUME ne déclare un toit à pans ou "
                                + "pointu (« toit: deux pans axe x/z » ou « pointu »).")));
                continue;
            }
            Optional<Element> placed = elements.stream().filter(el -> matches(r, el)).findFirst();
            out.add(placed.map(el -> new Finding(true, r.label() + " : " + el.volume()
                            + (el.position().isBlank() ? "" : ", " + el.position())))
                    .orElseGet(() -> new Finding(false, r.label() + " : aucune ligne ÉLÉMENT ne le place dans un volume.")));
        }
        return out;
    }

    /** Les lacunes seules. */
    public List<Finding> gaps(String brief, int maxWidth, int maxDepth, int maxHeight) {
        return check(brief, maxWidth, maxDepth, maxHeight).stream().filter(f -> !f.ok()).toList();
    }

    /**
     * Le contrat que le plan doit tenir : chaque volume avec ses coordonnées, chaque élément à sa place,
     * et les lacunes de la conception à combler. Vide si la conception n'est pas structurée.
     */
    public String contract(String brief, int maxWidth, int maxDepth, int maxHeight) {
        if (!structured()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""
                CONTRAT DE VOLUMES (lu dans la conception — le panel le vérifie sur le résultat compilé)

                Chaque VOLUME ci-dessous est construit par SES PROPRES instructions, à ces coordonnées
                exactes : son sol, ses murs (« walls » ou « shell » de son emprise, de y bas à y haut), son
                toit posé sur SON emprise (un toit pointu = un « gable » sur l'emprise étroite de ce volume,
                donc très pentu), ses ouvertures (« window » avec « wall: » pour viser le mur de ce volume).
                Les volumes reliés communiquent par l'ouverture annoncée. Ne fusionne pas deux volumes, n'en
                réduis aucun à une colonne ou à un bloc décoratif.

                """);
        for (Volume v : volumes) {
            sb.append("- ").append(v.name()).append(v.role().isBlank() ? "" : " (" + v.role() + ")").append(" : ")
                    .append(v.coordinates()).append(v.roof().isBlank() ? "" : " ; toit : " + v.roof())
                    .append(v.link().isBlank() ? "" : " ; relié à : " + v.link()).append('\n');
        }
        if (!elements.isEmpty()) {
            sb.append("\nÉLÉMENTS À PLACER (chacun dans son volume, à sa position) :\n");
            for (Element el : elements) {
                sb.append("- ").append(el.requirement()).append(el.volume().isBlank() ? "" : " → " + el.volume())
                        .append(el.position().isBlank() ? "" : ", " + el.position())
                        .append(el.blocks().isBlank() ? "" : " — " + el.blocks()).append('\n');
            }
        }
        List<Finding> gaps = gaps(brief, maxWidth, maxDepth, maxHeight);
        if (!gaps.isEmpty()) {
            sb.append("\nLACUNES DE LA CONCEPTION — corrige-les dans le plan :\n");
            gaps.forEach(g -> sb.append("- ").append(g.text()).append('\n'));
        }
        return sb.toString();
    }

    private static boolean matches(BriefRequirements.Requirement r, Element el) {
        String label = BriefRequirements.fold(el.requirement() + " " + el.blocks());
        if (r.blocks() != null && r.blocks().matcher(firstBlock(el.blocks())).matches()) {
            return true;
        }
        for (String word : BriefRequirements.fold(r.label()).split("[^a-z]+")) {
            if (word.length() >= 4 && label.contains(word.substring(0, Math.min(word.length(), 6)))) {
                return true;
            }
        }
        String said = BriefRequirements.fold(r.word());
        return !said.isBlank() && label.contains(said.length() > 5 ? said.substring(0, 5) : said);
    }

    private static String firstBlock(String blocks) {
        Matcher m = Pattern.compile("minecraft:[a-z0-9_]+").matcher(blocks == null ? "" : blocks);
        return m.find() ? m.group() : "";
    }

    private static String field(String rest, String key) {
        Matcher m = Pattern.compile("(?i)(?:^|\\|)\\s*(?:" + key + ")\\s*:?\\s*([^|]*)").matcher(rest);
        return m.find() ? m.group(1).strip() : "";
    }

    private static int lo(Matcher m) {
        return Math.min(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    private static int hi(Matcher m) {
        return Math.max(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }
}
