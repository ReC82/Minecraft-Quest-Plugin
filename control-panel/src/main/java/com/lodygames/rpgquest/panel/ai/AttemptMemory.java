package com.lodygames.rpgquest.panel.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La mémoire structurée des tentatives d'un projet de l'Atelier (issue #747).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Le 22/10/2026, neuf « Améliorer avec l'IA » de suite sur l'Église du hub ne transmettaient que
 * le dernier plan et son dernier diagnostic : « Les diagnostics des versions précédentes ne sont pas
 * transmis. » Le modèle corrigeait les lanternes, les cassait de nouveau à la passe suivante, et
 * rien ne le lui disait — ni à l'administrateur. Comparer les diagnostics par leur texte entier ne
 * suffisait pas non plus : la même faute à deux coordonnées différentes donnait deux « nouveaux »
 * diagnostics.</p>
 *
 * <h2>Ce qu'elle retient</h2>
 *
 * <ul>
 *   <li>chaque version vérifiée, ses codes et ses constats, chacun réduit à un {@link Issue} : code,
 *       emplacement (coordonnées, face ou instruction) et cause (le texte du constat sans ses
 *       nombres) — c'est ce qui permet de reconnaître une faute répétée ou revenue ;</li>
 *   <li>les erreurs <strong>résolues</strong> (vues, puis disparues d'une version évaluable), les
 *       <strong>régressions</strong> (une erreur résolue qui revient), la <strong>meilleure</strong>
 *       version refusée et la <strong>stagnation</strong>.</li>
 * </ul>
 *
 * <p>Une version refusée pour {@code PLAN_INVALID} n'a pas été construite : elle ne dit RIEN des
 * autres contrôles. Elle ne résout donc aucune erreur architecturale — sinon un plan illisible
 * passerait pour une correction de l'éclairage.</p>
 *
 * <p>Elle est bornée : le prompt n'en reçoit qu'un résumé de quelques lignes par version, jamais le
 * journal entier.</p>
 */
public final class AttemptMemory {

    /** Versions rendues dans le prompt : les plus récentes. */
    public static final int MAX_VERSIONS = 8;

    /** Tentatives consécutives sans améliorer la meilleure version : au-delà, c'est une stagnation. */
    public static final int STAGNATION = 3;

    /** Le code d'un plan illisible ou inexpansible : il ne juge aucun autre contrôle. */
    public static final String INVALID = "PLAN_INVALID";

    /**
     * Les codes qui arrêtent la vérification avant les contrôles d'architecture (#747, 23/10/2026) :
     * une version qui les porte n'a pas été jugée sur le reste — ses autres erreurs sont « non
     * vérifiées », jamais « résolues ».
     */
    public static final Set<String> EARLY_STOP = Set.of(INVALID, "EMPTY_STRUCTURE");

    private static final int PROMPT_CHARS = 4_000;

    /**
     * Un constat du serveur, réduit à ce qui permet de le reconnaître d'une version à l'autre.
     *
     * @param location coordonnées, face ou instruction citées en premier ; vide si aucune
     * @param cause    le texte du constat, en minuscules, nombres remplacés par « # »
     */
    public record Issue(String code, String location, String cause) {

        public Issue {
            code = code == null ? "" : code;
            location = location == null ? "" : location;
            cause = cause == null ? "" : cause;
        }

        /** Même faute : même code et même cause, quel que soit l'endroit. */
        public boolean sameFault(Issue other) {
            return other != null && code.equals(other.code) && cause.equals(other.cause);
        }

        public String label() {
            return code + (location.isEmpty() ? "" : " en " + location);
        }
    }

    /** Une version vérifiée par le serveur. */
    public record Attempt(String draftId, int version, boolean accepted, List<String> codes,
                          List<Issue> issues, int missingRequirements, List<String> unknownBlocks) {

        public Attempt {
            draftId = draftId == null ? "" : draftId;
            codes = List.copyOf(codes == null ? List.of() : codes);
            issues = List.copyOf(issues == null ? List.of() : issues);
            unknownBlocks = List.copyOf(unknownBlocks == null ? List.of() : unknownBlocks);
        }

        /** Sans conformité au brief ni identifiants relevés (forme antérieure). */
        public Attempt(String draftId, int version, boolean accepted, List<String> codes, List<Issue> issues) {
            this(draftId, version, accepted, codes, issues, -1, List.of());
        }

        /** Le plan a-t-il été construit, donc jugé sur tous les contrôles ? */
        public boolean evaluable() {
            return accepted || codes.stream().noneMatch(EARLY_STOP::contains);
        }

        /**
         * Moins c'est mieux. Pas au seul nombre de codes (#747, 23/10/2026) : une version arrêtée tôt
         * (plan illisible) n'a qu'UN code mais n'a pas été jugée sur le reste — elle passe après toute
         * version construite ; et chaque exigence du brief manquante compte comme un défaut.
         * Acceptée : 0 + exigences manquantes ; construite et refusée : 20 + codes + exigences
         * manquantes ; non construite : 100 et plus.
         */
        public int score() {
            int missing = Math.max(0, missingRequirements);
            return accepted ? missing : evaluable() ? 20 + codes.size() + missing : 100 + missing;
        }

        public String shortId() {
            return "V-" + draftId.substring(0, Math.min(8, draftId.length()));
        }
    }

    /** Une erreur résolue qui revient : la version fautive, et celle qui l'avait résolue. */
    public record Regression(Attempt culprit, String code, Attempt resolvedBy) {
    }

    private final List<Attempt> attempts;

    private AttemptMemory(List<Attempt> attempts) {
        this.attempts = List.copyOf(attempts);
    }

    public static AttemptMemory empty() {
        return new AttemptMemory(List.of());
    }

    public static AttemptMemory of(List<Attempt> attempts) {
        return new AttemptMemory(attempts == null ? List.of() : attempts);
    }

    /**
     * La mémoire d'un projet, relue dans son journal IA (#739) : chaque vérification, dans l'ordre où
     * la version est apparue ; une version revérifiée garde sa place et prend son dernier verdict.
     */
    @SuppressWarnings("unchecked")
    public static AttemptMemory fromJournal(List<AiJournalStore.Event> events) {
        Map<String, Attempt> byDraft = new LinkedHashMap<>();
        for (AiJournalStore.Event e : events == null ? List.<AiJournalStore.Event>of() : events) {
            if (!"VALIDATION".equals(e.kind()) || e.draftId() == null || e.draftId().isEmpty()) {
                continue;
            }
            Map<String, Object> body = e.body() == null ? Map.of() : e.body();
            boolean accepted = Boolean.TRUE.equals(body.get("accepted"));
            List<String> codes = new ArrayList<>();
            if (body.get("codes") instanceof List<?> list) {
                list.forEach(c -> codes.add(String.valueOf(c)));
            }
            int version = body.get("version") instanceof Number n ? n.intValue() : 0;
            String diagnosis = body.get("diagnosis") == null ? "" : String.valueOf(body.get("diagnosis"));
            byDraft.put(e.draftId(), new Attempt(e.draftId(), version, accepted, codes,
                    accepted ? List.of() : issuesOf(codes, diagnosis)));
        }
        return new AttemptMemory(new ArrayList<>(byDraft.values()));
    }

    /**
     * La mémoire d'un projet relue dans ses VERSIONS et le dernier verdict du serveur sur chacune, de
     * la plus ancienne à la plus récente. C'est la source qui fait foi : le journal IA ne relève une
     * vérification qu'au moment d'une amélioration — la version produite par l'IA puis réparée par le
     * moteur, jamais améliorée elle-même, en était absente (#747, parcours réel du 22/10/2026) — et il
     * ne couvre pas les versions antérieures à son installation. Une version jamais jugée est ignorée.
     */
    public static AttemptMemory fromVersions(com.lodygames.rpgquest.panel.building.BuildingProject project,
                                             List<com.lodygames.rpgquest.panel.building.BuildingPlanDraft> drafts,
                                             com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench workbench) {
        List<com.lodygames.rpgquest.panel.building.BuildingPlanDraft> ordered = new ArrayList<>(
                drafts == null ? List.of() : drafts);
        ordered.sort(java.util.Comparator.comparing(com.lodygames.rpgquest.panel.building.BuildingPlanDraft::createdAt));
        List<Attempt> out = new ArrayList<>();
        for (var draft : ordered) {
            if (!draft.hasPlan()) {
                continue;
            }
            var state = workbench.state(draft, com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Check.AUDIT);
            var verdict = com.lodygames.rpgquest.panel.building.PlanDiagnosis.verdict(state);
            if (!verdict.available()) {
                continue;
            }
            Map<String, Object> palette = paletteOf(state);
            int missing = project == null || project.brief() == null ? -1
                    : BriefRequirements.evaluate(project.brief(), draft.plan(), palette).missing().size();
            out.add(new Attempt(draft.id(), draft.version(), verdict.accepted(), verdict.codes(), verdict.accepted()
                    ? List.of() : issuesOf(verdict.codes(),
                    com.lodygames.rpgquest.panel.building.PlanDiagnosis.of(project, state)), missing,
                    new ArrayList<>(BlockRegistry.vanilla().unknownIn(draft.plan()).keySet())));
        }
        return new AttemptMemory(out);
    }

    /** La palette du résultat compilé relevée par la vérification ; vide si inconnue. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paletteOf(
            com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.CheckState state) {
        try {
            Object p = com.lodygames.rpgquest.panel.building.BuildingPlanVerdict.from(state.details()).metrics()
                    .get("palette");
            return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** La même mémoire, avec une vérification de plus (remplace celle de la même version). */
    public AttemptMemory with(Attempt attempt) {
        List<Attempt> next = new ArrayList<>();
        boolean replaced = false;
        for (Attempt a : attempts) {
            if (a.draftId().equals(attempt.draftId())) {
                next.add(attempt);
                replaced = true;
            } else {
                next.add(a);
            }
        }
        if (!replaced) {
            next.add(attempt);
        }
        return new AttemptMemory(next);
    }

    public List<Attempt> attempts() {
        return attempts;
    }

    public boolean isEmpty() {
        return attempts.isEmpty();
    }

    public Optional<Attempt> last() {
        return attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.get(attempts.size() - 1));
    }

    public Optional<Attempt> find(String draftId) {
        return attempts.stream().filter(a -> a.draftId().equals(draftId)).findFirst();
    }

    // ---- Lecture des diagnostics -------------------------------------------------------------

    private static final Pattern POINT = Pattern.compile("(?m)^\\s{0,6}(\\d{1,2})\\.\\s+");
    private static final Pattern FACE = Pattern.compile(
            "face (NORD|SUD|EST|OUEST), x [0-9–-]+, y [0-9–-]+, z [0-9–-]+");
    private static final Pattern CELL = Pattern.compile("(?<![\\d/])(\\d{1,3})/(\\d{1,3})/(\\d{1,3})(?![\\d/])");
    private static final Pattern STEP = Pattern.compile("[Ii]nstruction (\\d+) \\(([A-Za-z_]+)");

    /**
     * Les constats d'un diagnostic, un par code. Le serveur numérote ses points dans l'ordre de ses
     * codes ; si le compte ne correspond pas, chaque code garde un constat sans emplacement plutôt
     * qu'un emplacement attribué au mauvais code.
     */
    public static List<Issue> issuesOf(List<String> codes, String diagnosis) {
        List<String> list = codes == null ? List.of() : codes;
        String text = diagnosis == null ? "" : diagnosis;
        List<String> points = points(text);
        List<Issue> out = new ArrayList<>();
        if (list.size() == 1 && points.isEmpty()) {
            out.add(issue(list.get(0), text.replaceFirst("^Plan refusé\\s*:\\s*", "")));
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            out.add(points.size() == list.size() ? issue(list.get(i), points.get(i)) : new Issue(list.get(i), "", ""));
        }
        return out;
    }

    private static List<String> points(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = POINT.matcher(text);
        List<int[]> starts = new ArrayList<>();
        int expected = 1;
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == expected) {
                starts.add(new int[] {m.start(), m.end()});
                expected++;
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            String point = text.substring(starts.get(i)[1], end);
            int stop = point.indexOf("\nRien n'a été écrit");
            out.add((stop < 0 ? point : point.substring(0, stop)).trim());
        }
        return out;
    }

    private static Issue issue(String code, String point) {
        String location = "";
        Matcher face = FACE.matcher(point);
        Matcher cell = CELL.matcher(point);
        Matcher step = STEP.matcher(point);
        if (face.find()) {
            location = face.group();
        } else if (cell.find()) {
            location = cell.group();
        } else if (step.find()) {
            location = "instruction " + step.group(1) + " (" + step.group(2).toLowerCase(Locale.ROOT) + ")";
        }
        return new Issue(code, location, cause(point));
    }

    /** La première phrase du constat, sans nombres ni coordonnées : la nature de la faute. */
    static String cause(String point) {
        String first = point.split("\\.(\\s|$)", 2)[0];
        String c = first.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
        return c.length() > 120 ? c.substring(0, 120) : c;
    }

    // ---- Analyse -----------------------------------------------------------------------------

    /** Codes vus dans une version, puis absents d'une version évaluable plus récente. */
    public Map<String, Attempt> resolvedCodes() {
        Map<String, Attempt> resolved = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Attempt a : attempts) {
            if (a.evaluable()) {
                for (String code : seen) {
                    if (!a.codes().contains(code)) {
                        resolved.putIfAbsent(code, a);
                    }
                }
                a.codes().forEach(resolved::remove);
            }
            seen.addAll(a.codes());
        }
        seen.remove(INVALID);
        resolved.keySet().retainAll(seen);
        return resolved;
    }

    /**
     * Les erreurs qu'une version a résolues : vues dans une AUTRE version construite, absentes de
     * celle-ci. C'est la liste à ne pas réintroduire quand on repart d'elle — y compris celles qu'un
     * candidat plus récent avait cassées de nouveau. Vide si la version n'a pas été construite.
     */
    public Map<String, Attempt> resolvedFor(String draftId) {
        Optional<Attempt> reference = find(draftId).filter(Attempt::evaluable);
        if (reference.isEmpty()) {
            return resolvedCodes();
        }
        Map<String, Attempt> out = new LinkedHashMap<>();
        for (Attempt a : attempts) {
            if (a == reference.get() || !a.evaluable()) {
                continue;
            }
            for (String code : a.codes()) {
                if (!INVALID.equals(code) && !reference.get().codes().contains(code)) {
                    out.putIfAbsent(code, reference.get());
                }
            }
        }
        return out;
    }

    /** Toutes les régressions : une version qui réintroduit un code qu'une version antérieure avait résolu. */
    public List<Regression> regressions() {
        List<Regression> out = new ArrayList<>();
        for (int i = 0; i < attempts.size(); i++) {
            out.addAll(regressionsOf(i));
        }
        return out;
    }

    /** Les régressions apportées par cette version, ou aucune si elle est inconnue. */
    public List<Regression> regressionsOf(String draftId) {
        for (int i = 0; i < attempts.size(); i++) {
            if (attempts.get(i).draftId().equals(draftId)) {
                return regressionsOf(i);
            }
        }
        return List.of();
    }

    private List<Regression> regressionsOf(int index) {
        Attempt a = attempts.get(index);
        if (!a.evaluable() || a.accepted()) {
            return List.of();
        }
        List<Regression> out = new ArrayList<>();
        for (String code : a.codes()) {
            Attempt resolvedBy = null;
            boolean present = false;
            for (int j = 0; j < index; j++) {
                Attempt before = attempts.get(j);
                if (!before.evaluable()) {
                    continue;
                }
                if (before.codes().contains(code)) {
                    present = true;
                    resolvedBy = null;
                } else if (present) {
                    resolvedBy = before;
                }
            }
            if (resolvedBy != null) {
                out.add(new Regression(a, code, resolvedBy));
            }
        }
        return out;
    }

    /**
     * La meilleure version refusée mais construite, selon {@link Attempt#score()} (codes ET exigences
     * du brief manquantes), la plus récente à égalité.
     */
    public Optional<Attempt> bestRefused() {
        Attempt best = null;
        for (Attempt a : attempts) {
            if (!a.accepted() && a.evaluable() && (best == null || a.score() <= best.score())) {
                best = a;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Nombre de tentatives depuis la dernière qui a amélioré la meilleure version. */
    public int sinceImprovement() {
        int best = Integer.MAX_VALUE;
        int since = 0;
        for (Attempt a : attempts) {
            if (a.score() < best) {
                best = a.score();
                since = 0;
            } else {
                since++;
            }
        }
        return since;
    }

    /** {@value #STAGNATION} tentatives de suite sans faire mieux que la meilleure : on tourne en rond. */
    public boolean stagnating() {
        return !attempts.isEmpty() && !last().get().accepted() && sinceImprovement() >= STAGNATION;
    }

    /**
     * Les erreurs NON VÉRIFIÉES d'une version arrêtée tôt (#747) : celles qu'une version construite
     * avait relevées, et dont rien ne dit qu'elles ont disparu — le contrôle n'a pas été exécuté.
     * Vide si la version a été construite.
     */
    public List<String> unverifiedFor(String draftId) {
        Optional<Attempt> reference = find(draftId);
        if (reference.isEmpty() || reference.get().evaluable()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Attempt a : attempts) {
            if (a.evaluable() && !a.accepted()) {
                out.addAll(a.codes());
            }
        }
        out.removeAll(EARLY_STOP);
        return List.copyOf(out);
    }

    /** Les identifiants inexistants employés par les versions vérifiées, dans l'ordre d'apparition. */
    public List<String> inventedBlocks() {
        Set<String> out = new LinkedHashSet<>();
        attempts.forEach(a -> out.addAll(a.unknownBlocks()));
        return List.copyOf(out);
    }

    /**
     * Les enseignements à garder pour une NOUVELLE conception (#747) : ce qui a échoué de façon
     * répétée, les identifiants inventés, les erreurs que des versions avaient résolues. Aucun plan
     * n'est recopié : c'est la conception qui repart, pas la correction.
     */
    public String lessonsBlock() {
        if (attempts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("ENSEIGNEMENTS DES ").append(attempts.size())
                .append(" VERSION(S) PRÉCÉDENTE(S) — nouvelle conception : ne recopie aucun ancien plan, "
                        + "mais n'y refais pas ces erreurs\n\n");
        Map<String, Integer> recurring = recurringFaults();
        if (!recurring.isEmpty()) {
            sb.append("Fautes qui revenaient d'une version à l'autre :\n");
            recurring.forEach((fault, n) -> sb.append("- ").append(fault).append(" (").append(n)
                    .append(" versions)\n"));
        }
        Set<String> seen = new LinkedHashSet<>();
        attempts.stream().filter(Attempt::evaluable).forEach(a -> seen.addAll(a.codes()));
        seen.removeAll(EARLY_STOP);
        if (!seen.isEmpty()) {
            sb.append("Contrôles du serveur déjà échoués (à respecter dès la première version) : ")
                    .append(String.join(", ", seen)).append('\n');
        }
        List<String> invented = inventedBlocks();
        if (!invented.isEmpty()) {
            sb.append("Identifiants INEXISTANTS déjà employés — ne les réemploie pas, ni un nom voisin : ")
                    .append(String.join(", ", invented)).append('\n');
        }
        long early = attempts.stream().filter(a -> !a.evaluable()).count();
        if (early > 0) {
            sb.append(early).append(" version(s) n'ont pas pu être construites (plan illisible) : respecte "
                    + "strictement la forme des instructions.\n");
        }
        String text = sb.toString();
        return text.length() > PROMPT_CHARS ? text.substring(0, PROMPT_CHARS) + "\n[…]\n" : text;
    }

    /** Les fautes (code + cause) revenues dans au moins trois versions : celles que les corrections ratent. */
    public Map<String, Integer> recurringFaults() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Attempt a : attempts) {
            Set<String> once = new LinkedHashSet<>();
            for (Issue i : a.issues()) {
                if (!INVALID.equals(i.code())) {
                    once.add(i.code() + (i.cause().isEmpty() ? "" : " — " + i.cause()));
                }
            }
            once.forEach(k -> count.merge(k, 1, Integer::sum));
        }
        count.values().removeIf(n -> n < 3);
        return count;
    }

    // ---- Rendu ---------------------------------------------------------------------------------

    /**
     * Le bloc « MÉMOIRE DES TENTATIVES » du prompt : borné, factuel, sans recopier le journal.
     *
     * @param currentDraftId la version transmise avec la demande
     */
    public String promptBlock(String currentDraftId) {
        if (attempts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("MÉMOIRE DES TENTATIVES DE CE PROJET\n\n");
        int from = Math.max(0, attempts.size() - MAX_VERSIONS);
        sb.append(attempts.size()).append(" version(s) vérifiée(s) par le serveur")
                .append(from > 0 ? ", les " + MAX_VERSIONS + " plus récentes" : "").append(", de la plus ancienne "
                        + "à la plus récente :\n");
        for (int i = from; i < attempts.size(); i++) {
            Attempt a = attempts.get(i);
            sb.append("- ").append(a.shortId()).append(a.draftId().equals(currentDraftId) ? " (version transmise)" : "")
                    .append(" : ").append(a.accepted() ? "ACCEPTÉE" : (a.evaluable() ? "refusée — " : "NON CONSTRUITE — ")
                            + String.join(", ", a.codes()) + (a.evaluable() ? ""
                            : " (vérification arrêtée avant les contrôles d'architecture : rien n'y est vérifié)"));
            if (a.missingRequirements() > 0) {
                sb.append(" ; ").append(a.missingRequirements()).append(" exigence(s) du brief manquante(s)");
            }
            List<Regression> regs = regressionsOf(i);
            if (!regs.isEmpty()) {
                sb.append(" ⚠ RÉGRESSION : ").append(String.join(", ", regs.stream().map(r -> r.code()
                        + " (résolu en " + r.resolvedBy().shortId() + ")").toList()));
            }
            sb.append('\n');
        }
        Map<String, Attempt> resolved = resolvedFor(currentDraftId);
        if (!resolved.isEmpty()) {
            sb.append("\nERREURS DÉJÀ RÉSOLUES — ne les réintroduis pas : ");
            List<String> parts = new ArrayList<>();
            resolved.forEach((code, by) -> parts.add(code + " (absent de " + by.shortId() + ")"));
            sb.append(String.join(", ", parts)).append('\n');
        }
        List<String> unverified = unverifiedFor(currentDraftId);
        if (!unverified.isEmpty()) {
            sb.append("\nNON VÉRIFIÉ sur la version transmise (elle n'a pas été construite) : ")
                    .append(String.join(", ", unverified)).append(" — ces erreurs ne sont PAS résolues ; "
                            + "corrige d'abord ce qui empêche la construction, sans les réintroduire.\n");
        }
        List<String> invented = inventedBlocks();
        if (!invented.isEmpty()) {
            sb.append("\nIDENTIFIANTS INEXISTANTS déjà employés dans ce projet : ").append(String.join(", ", invented))
                    .append(" — ne les réemploie pas, et ne les remplace pas par un nom voisin : reprends "
                            + "l'élément demandé avec un identifiant réel, ou supprime-le s'il n'est pas demandé.\n");
        }
        Map<String, Integer> recurring = recurringFaults();
        if (!recurring.isEmpty()) {
            sb.append("\nFAUTES QUI REVIENNENT malgré les corrections (la même cause, d'une version à l'autre) :\n");
            recurring.forEach((fault, n) -> sb.append("- ").append(fault).append(" — ").append(n)
                    .append(" versions\n"));
            sb.append("Corriger encore au même endroit ne suffit pas : traite la cause (ordre des instructions, "
                    + "volume, support), pas seulement la coordonnée citée.\n");
        }
        bestRefused().filter(b -> !b.draftId().equals(currentDraftId)).ifPresent(b -> sb.append(
                "\nLa version refusée la plus proche du but est ").append(b.shortId()).append(" (")
                .append(b.codes().size()).append(" code(s) : ").append(String.join(", ", b.codes()))
                .append(").\n"));
        if (stagnating()) {
            sb.append("\nSTAGNATION : ").append(sinceImprovement()).append(" tentatives de suite sans faire mieux "
                    + "que la meilleure version. Change d'approche sur les volumes en cause au lieu d'une nouvelle "
                    + "variante du même défaut.\n");
        }
        String text = sb.toString();
        return text.length() > PROMPT_CHARS ? text.substring(0, PROMPT_CHARS) + "\n[…]\n" : text;
    }

    /** Une ligne pour le journal et l'écran : ce que la mémoire contient. */
    public String summary() {
        if (attempts.isEmpty()) {
            return "Aucune version vérifiée dans le journal de ce projet.";
        }
        return attempts.size() + " version(s) vérifiée(s), " + resolvedCodes().size() + " erreur(s) résolue(s), "
                + regressions().size() + " régression(s)" + (stagnating() ? ", STAGNATION ("
                + sinceImprovement() + " tentatives sans progrès)" : "") + ".";
    }
}
