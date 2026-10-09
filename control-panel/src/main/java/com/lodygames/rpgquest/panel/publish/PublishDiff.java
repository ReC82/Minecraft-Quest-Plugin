package com.lodygames.rpgquest.panel.publish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * La différence entre le fichier <strong>DEV</strong> et la <strong>source</strong>, lisible par un
 * administrateur (issue #47).
 *
 * <h2>Le sens de lecture, et pourquoi celui-là</h2>
 *
 * <p>DEV est l'<em>avant</em>, la source est l'<em>après</em>. Une ligne {@code +} est donc ce que
 * la publication <strong>ajouterait</strong>, une ligne {@code -} ce qu'elle
 * <strong>retirerait</strong>. C'est le sens qui répond à la seule question que se pose
 * l'administrateur devant ce bouton : « qu'est-ce que publier va changer ? ». L'inverse l'obligerait
 * à retourner mentalement chaque ligne.</p>
 *
 * <h2>Pourquoi pas {@code TextDiff}</h2>
 *
 * <p>{@code content.TextDiff} compare par préfixe et suffixe communs : il suffit pour l'aperçu
 * « avant / après » d'une seule édition, mais devant deux modifications éloignées dans le fichier il
 * marque <em>tout le bloc central</em> comme retiré puis réajouté. Pour « voir les différences »
 * d'un YAML réel, cela produirait quarante lignes rouges et quarante vertes là où deux ont changé —
 * donc une vue qu'on n'ose plus lire.</p>
 *
 * <p>Ici : plus longue sous-séquence commune, par programmation dynamique. Les fichiers de contenu
 * font quelques dizaines à quelques centaines de lignes, et une borne stricte protège du reste.</p>
 *
 * <h2>Ce qui n'est PAS fait, volontairement</h2>
 *
 * <p>Aucun diff sémantique, aucune reformulation du YAML avant comparaison. Les deux textes sont
 * comparés <strong>tels qu'ils sont</strong>, seules les fins de ligne étant normalisées : un
 * fichier écrit sous Windows ne doit pas apparaître intégralement modifié. Le diff reflète donc
 * exactement les octets qui seront transférés.</p>
 */
public record PublishDiff(List<Line> lines,
                          boolean identical,
                          boolean comparable,
                          String note,
                          int added,
                          int removed,
                          boolean truncated) {

    /** Au-delà, la comparaison ligne à ligne coûterait plus qu'elle n'apprend. */
    public static final int MAX_LINES = 4000;

    /** Même borne que la charge utile de publication : au-delà, on ne compare pas. */
    public static final int MAX_CHARS = 256 * 1024;

    /** Nombre maximal de lignes rendues : une page illisible n'aide personne. */
    public static final int MAX_RENDERED = 400;

    /** Lignes de contexte conservées autour de chaque changement. */
    private static final int CONTEXT = 3;

    public PublishDiff {
        lines = List.copyOf(lines == null ? List.of() : lines);
        note = note == null ? "" : note;
    }

    /** Nature d'une ligne du diff. */
    public enum Kind {
        /** Identique des deux côtés. */
        CONTEXT,
        /** Présente dans la source : la publication l'ajouterait. */
        ADDED,
        /** Présente sur DEV : la publication la retirerait. */
        REMOVED,
        /** Coupure de contexte entre deux changements éloignés. */
        GAP
    }

    /**
     * Une ligne du diff.
     *
     * @param devLine    numéro de ligne côté DEV, ou {@code 0} si la ligne n'y existe pas
     * @param sourceLine numéro de ligne côté source, ou {@code 0} si elle n'y existe pas
     */
    public record Line(Kind kind, int devLine, int sourceLine, String text) {

        /** Le signe affiché devant la ligne. */
        public String sign() {
            return switch (kind) {
                case ADDED -> "+";
                case REMOVED -> "-";
                case GAP -> "⋯";
                default -> " ";
            };
        }
    }

    /** Un diff qu'on ne peut pas calculer, avec son motif. */
    public static PublishDiff unavailable(String note) {
        return new PublishDiff(List.of(), false, false, note, 0, 0, false);
    }

    /**
     * Compare le fichier DEV à la source.
     *
     * @param devText    le contenu actuellement sur DEV, ou {@code null} s'il n'y est pas
     * @param sourceText le contenu de la source, ou {@code null} si elle n'existe pas
     */
    public static PublishDiff between(String devText, String sourceText) {
        if (devText == null && sourceText == null) {
            return unavailable("Ni la source ni le fichier DEV ne sont disponibles.");
        }
        if (sourceText == null) {
            return unavailable("Cette ressource n'existe pas dans la source : rien à comparer.");
        }
        if (devText == null) {
            return unavailable("Cette ressource n'est pas encore sur DEV : il n'y a rien à "
                    + "comparer, tout le fichier sera créé.");
        }
        if (devText.length() > MAX_CHARS || sourceText.length() > MAX_CHARS) {
            return unavailable("Fichier trop volumineux pour être comparé ici ("
                    + MAX_CHARS / 1024 + " Kio au plus). Les empreintes restent comparables.");
        }

        List<String> dev = split(devText);
        List<String> source = split(sourceText);
        if (dev.equals(source)) {
            // Les empreintes peuvent différer alors que les lignes sont identiques : fins de ligne.
            boolean sameBytes = devText.equals(sourceText);
            return new PublishDiff(List.of(), true, true,
                    sameBytes ? "" : "Les deux fichiers ne diffèrent que par leurs fins de ligne.",
                    0, 0, false);
        }
        if (dev.size() > MAX_LINES || source.size() > MAX_LINES) {
            return unavailable("Fichier trop long pour être comparé ici (" + MAX_LINES
                    + " lignes au plus).");
        }

        List<Line> full = build(dev, source);
        int added = (int) full.stream().filter(l -> l.kind() == Kind.ADDED).count();
        int removed = (int) full.stream().filter(l -> l.kind() == Kind.REMOVED).count();
        boolean truncated = full.size() > MAX_RENDERED;
        List<Line> shown = truncated ? List.copyOf(full.subList(0, MAX_RENDERED)) : full;
        return new PublishDiff(shown, false, true,
                truncated ? "Différence tronquée à " + MAX_RENDERED + " lignes." : "",
                added, removed, truncated);
    }

    /** Vrai s'il y a quelque chose à montrer. */
    public boolean hasChanges() {
        return comparable && !identical && !lines.isEmpty();
    }

    /** Résumé court : « 3 ajoutée(s), 1 retirée(s) ». */
    public String summary() {
        if (!comparable) {
            return note;
        }
        if (identical) {
            return "Aucune différence.";
        }
        return added + " ligne(s) ajoutée(s), " + removed + " retirée(s)";
    }

    // ---- Calcul ---------------------------------------------------------------------------------

    /**
     * Construit le diff, puis ne garde que les changements et leur contexte.
     *
     * <p>Sans la réduction au contexte, un fichier de 300 lignes dont une a changé produirait
     * 300 lignes à lire pour en trouver une. Les blocs éloignés sont séparés par une coupure
     * explicite plutôt que recollés en silence.</p>
     */
    private static List<Line> build(List<String> dev, List<String> source) {
        List<Line> all = align(dev, source);

        boolean[] keep = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).kind() != Kind.CONTEXT) {
                for (int j = Math.max(0, i - CONTEXT);
                        j < Math.min(all.size(), i + CONTEXT + 1); j++) {
                    keep[j] = true;
                }
            }
        }
        List<Line> out = new ArrayList<>();
        boolean gapPending = false;
        for (int i = 0; i < all.size(); i++) {
            if (keep[i]) {
                if (gapPending && !out.isEmpty()) {
                    out.add(new Line(Kind.GAP, 0, 0, ""));
                }
                gapPending = false;
                out.add(all.get(i));
            } else {
                gapPending = true;
            }
        }
        return out;
    }

    /**
     * Aligne les deux côtés par leur plus longue sous-séquence commune.
     *
     * <p>Programmation dynamique classique : {@code O(n×m)} en temps et en mémoire, ce qui est
     * largement supportable sous {@link #MAX_LINES} et borné par construction au-delà.</p>
     */
    private static List<Line> align(List<String> dev, List<String> source) {
        int n = dev.size();
        int m = source.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = dev.get(i).equals(source.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (dev.get(i).equals(source.get(j))) {
                out.add(new Line(Kind.CONTEXT, i + 1, j + 1, dev.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(Kind.REMOVED, i + 1, 0, dev.get(i)));
                i++;
            } else {
                out.add(new Line(Kind.ADDED, 0, j + 1, source.get(j)));
                j++;
            }
        }
        while (i < n) {
            out.add(new Line(Kind.REMOVED, i + 1, 0, dev.get(i)));
            i++;
        }
        while (j < m) {
            out.add(new Line(Kind.ADDED, 0, j + 1, source.get(j)));
            j++;
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Découpe en lignes, en normalisant <strong>uniquement</strong> les fins de ligne.
     *
     * <p>Aucune autre transformation : pas de {@code trim}, pas de réindentation, pas de
     * reformatage du YAML. Le diff doit refléter les octets qui seront réellement transférés, sinon
     * il décrirait un fichier qui n'existe pas.</p>
     */
    private static List<String> split(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return List.of(normalized.split("\n", -1));
    }
}
