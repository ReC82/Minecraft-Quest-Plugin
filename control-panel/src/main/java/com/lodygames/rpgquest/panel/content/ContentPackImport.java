package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Import d'un content pack {@code lodyquests-content-pack} (issue #109) :
 * <strong>analyse pure</strong>, puis écriture seulement sur confirmation explicite.
 *
 * <p>Le pipeline du ticket est respecté dans cet ordre, et chaque étape peut échouer sans qu'aucune
 * écriture n'ait lieu : {@code IMPORT → ANALYSE → VALIDATION → DIFF → BROUILLON → CONFIRMATION →
 * ENREGISTREMENT SOURCE}. {@link #analyze} ne touche <strong>jamais</strong> au disque ;
 * {@link #apply} est le seul point d'écriture, et il refuse de travailler sur une analyse non
 * importable.</p>
 *
 * <p><strong>Trois propriétés de sécurité structurelles</strong>, obtenues par construction plutôt
 * que par vérification :</p>
 * <ol>
 *   <li><strong>Aucun chemin ne vient du fichier.</strong> La destination est calculée par
 *       {@code ContentWorkspace.write(kind, slug, …)} à partir d'une famille prise dans une liste
 *       blanche et d'un {@code slug} dérivé de l'identifiant métier, validé par l'expression
 *       régulière du workspace. Un pack ne peut donc pas écrire ailleurs, et la traversée de chemin
 *       est impossible — il n'y a pas de chemin à traverser.</li>
 *   <li><strong>Aucun octet du fichier n'est écrit tel quel.</strong> Chaque élément est relu en
 *       brouillon par le lecteur réel de sa famille ({@code QuestYaml.fromMap} &amp; co.) puis
 *       <em>ré-émis</em> par l'écrivain réel. Ce qui atterrit sur le disque est donc produit par le
 *       Control Panel, dans sa forme canonique, et relisible par les parseurs du plugin. Une
 *       construction que l'éditeur ne sait pas représenter est signalée, jamais aplatie en
 *       silence.</li>
 *   <li><strong>Aucun écrasement silencieux.</strong> Un identifiant déjà présent produit un
 *       {@link Status#CONFLICT} qui bloque l'import tant que l'utilisateur n'a pas tranché
 *       explicitement entre {@link Decision#REPLACE} et {@link Decision#SKIP}. Le remplacement
 *       repasse par le verrou optimiste du workspace (empreinte attendue), donc une modification
 *       survenue entre l'analyse et la confirmation est refusée au lieu d'être écrasée.</li>
 * </ol>
 *
 * <p><strong>Familles supportées</strong> : {@code quests}, {@code stories}, {@code dialogues} —
 * exactement celles que {@code ContentWorkspace.KINDS} sait écrire. {@code npcs} fait partie du
 * format de pack mais n'est pas éditable depuis le panel : ces éléments sont rapportés
 * {@link Status#SKIPPED} avec leur motif, jamais ignorés en silence.</p>
 */
public final class ContentPackImport {

    /**
     * Taille maximale acceptée, avant toute analyse. Un pack de contenu déclaratif reste très
     * au-dessous ; au-delà, c'est un refus lisible plutôt qu'une analyse qui mobilise le panel.
     */
    public static final int MAX_BYTES = 512 * 1024;

    /** Familles réellement écrivables, dans l'ordre canonique du format. */
    public static final List<String> SUPPORTED_FAMILIES = List.of("quests", "stories", "dialogues");

    private ContentPackImport() {
    }

    /** Sort d'un élément du pack, dans le vocabulaire exact demandé par le ticket. */
    public enum Status {
        /** Aucun contenu de cet identifiant côté source : sera créé. */
        NEW,
        /** Existe, diffère, et l'utilisateur a demandé le remplacement. */
        MODIFIED,
        /** Existe et est déjà identique, octet pour octet : rien à écrire. */
        UNCHANGED,
        /** Existe et diffère, sans décision : bloque l'import jusqu'à arbitrage explicite. */
        CONFLICT,
        /** Volontairement laissé de côté (décision de l'utilisateur, ou famille non écrivable). */
        SKIPPED,
        /** Inexploitable (erreur de validation, identifiant invalide, doublon…) : bloque l'import. */
        INVALID
    }

    /** Arbitrage d'une collision. Toujours fourni par l'utilisateur, jamais déduit. */
    public enum Decision { REPLACE, SKIP }

    /**
     * État d'une dépendance déclarée par le pack. Le ticket #110 exige de distinguer les trois cas
     * plutôt que de deviner.
     */
    public enum DependencyState {
        /** Fournie par le pack lui-même. */
        IN_PACK,
        /** Déjà présente côté serveur / source. */
        ON_SERVER,
        /** Introuvable : l'import reste possible, mais le contenu sera incomplet. */
        MISSING
    }

    /**
     * @param family     famille du pack ({@code quests}/{@code stories}/{@code dialogues}/{@code npcs})
     * @param slug       nom de fichier sans extension, dérivé de l'identifiant — jamais du fichier
     * @param id         identifiant métier tel qu'il sera écrit
     * @param yaml       contenu canonique ré-émis par l'écrivain réel ({@code null} si inexploitable)
     * @param currentSha empreinte du fichier existant, pour le verrou optimiste ({@code null} si absent)
     * @param reason     explication d'un {@code SKIPPED} ou d'un {@code INVALID}, pour l'affichage
     */
    public record Element(String family, String slug, String id, Status status,
                          List<Diagnostic> diagnostics, String yaml, String currentSha,
                          List<TextDiff.Line> diff, String reason) {

        public Element {
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
            diff = List.copyOf(diff == null ? List.of() : diff);
        }

        public String key() {
            return family + "/" + slug;
        }

        /** {@code true} si cet élément provoquera une écriture lors de la confirmation. */
        public boolean writes() {
            return status == Status.NEW || status == Status.MODIFIED;
        }

        public boolean blocking() {
            return status == Status.INVALID || status == Status.CONFLICT;
        }
    }

    public record Dependency(String family, String id, DependencyState state) {
    }

    /**
     * Résultat de l'analyse. {@code envelope} porte les diagnostics qui concernent le pack entier
     * (format, version, familles inconnues) ; {@code elements} les éléments dans l'ordre canonique.
     */
    public record Analysis(List<Diagnostic> envelope, List<Element> elements,
                           List<Dependency> dependencies, Map<String, String> metadata,
                           boolean readable) {

        public Analysis {
            envelope = List.copyOf(envelope == null ? List.of() : envelope);
            elements = List.copyOf(elements == null ? List.of() : elements);
            dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        }

        /** Refus d'enveloppe : le pack n'a même pas pu être parcouru. */
        public static Analysis refused(Diagnostic why) {
            return new Analysis(List.of(why), List.of(), List.of(), Map.of(), false);
        }

        public long conflicts() {
            return elements.stream().filter(e -> e.status() == Status.CONFLICT).count();
        }

        public long invalid() {
            return elements.stream().filter(e -> e.status() == Status.INVALID).count();
        }

        public long writing() {
            return elements.stream().filter(Element::writes).count();
        }

        /**
         * L'import peut-il être confirmé ? Il faut un pack lisible, aucune erreur d'enveloppe, aucun
         * élément inexploitable, aucune collision non tranchée, et au moins une écriture à faire —
         * confirmer un import qui n'écrit rien n'aurait aucun sens.
         */
        public boolean importable() {
            return readable && !Diagnostic.hasError(envelope) && invalid() == 0 && conflicts() == 0
                    && writing() > 0;
        }
    }

    // ---- Analyse ------------------------------------------------------------------------------

    /**
     * Analyse un pack sans rien écrire.
     *
     * @param decisions arbitrages déjà rendus, indexés par {@link Element#key()} ; vide au premier
     *                  passage, ce qui fait apparaître les collisions comme telles
     */
    public static Analysis analyze(String packYaml, ContentWorkspace workspace, RefData ref,
                                   Map<String, Decision> decisions) {
        Map<String, Decision> choices = decisions == null ? Map.of() : decisions;
        if (packYaml == null || packYaml.isBlank()) {
            return Analysis.refused(Diagnostic.error("fichier", "Aucun contenu à importer."));
        }
        if (packYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Analysis.refused(Diagnostic.error("fichier",
                    "Fichier trop volumineux (maximum " + (MAX_BYTES / 1024) + " Kio). "
                            + "Importer famille par famille."));
        }

        Object root;
        try {
            root = MiniYaml.parse(packYaml);
        } catch (RuntimeException e) {
            return Analysis.refused(Diagnostic.error("fichier", "YAML illisible : " + e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> pack)) {
            return Analysis.refused(Diagnostic.error("fichier",
                    "Racine YAML inattendue : un content pack est une table avec « format », "
                            + "« schemaVersion » et « content »."));
        }

        List<Diagnostic> envelope = new ArrayList<>();
        String format = str(pack.get("format"));
        if (!ContentPackSchema.FORMAT.equals(format)) {
            return Analysis.refused(Diagnostic.error("format",
                    format.isBlank()
                            ? "« format » absent : ce fichier n'est pas un content pack LodyQuests."
                            : "Format inconnu « " + format + " » — attendu « "
                                    + ContentPackSchema.FORMAT + " »."));
        }
        Integer version = asInt(pack.get("schemaVersion"));
        if (version == null) {
            return Analysis.refused(Diagnostic.error("schemaVersion",
                    "« schemaVersion » absente ou non numérique — attendue "
                            + ContentPackSchema.SCHEMA_VERSION + "."));
        }
        if (version > ContentPackSchema.SCHEMA_VERSION) {
            return Analysis.refused(Diagnostic.error("schemaVersion",
                    "Version " + version + " plus récente que celle supportée ("
                            + ContentPackSchema.SCHEMA_VERSION + ") : refus explicite. Ce pack a été "
                            + "produit par une version plus récente de LodyQuests, et l'interpréter "
                            + "approximativement ferait perdre ce qu'elle ajoute."));
        }
        if (version < ContentPackSchema.SCHEMA_VERSION) {
            return Analysis.refused(Diagnostic.error("schemaVersion",
                    "Version " + version + " antérieure à la version supportée ("
                            + ContentPackSchema.SCHEMA_VERSION + ") et aucun migrateur n'existe "
                            + "pour elle. Ré-exporter le contenu depuis l'instance d'origine, ou "
                            + "mettre le pack à jour à la main."));
        }

        Map<String, String> metadata = readMetadata(pack.get("metadata"));

        if (!(pack.get("content") instanceof Map<?, ?> content)) {
            return Analysis.refused(Diagnostic.error("content",
                    "« content » absent ou mal formé : rien à importer."));
        }
        for (Object key : content.keySet()) {
            String family = str(key);
            if (!ContentPackSchema.FAMILIES.contains(family)) {
                envelope.add(Diagnostic.warning("content." + family,
                        "Famille inconnue « " + family + " » : ignorée. Le format n'en déclare que "
                                + String.join(", ", ContentPackSchema.FAMILIES) + "."));
            }
        }

        // Premier passage : collecter les identifiants fournis par le pack, pour que les références
        // internes se résolvent à la validation (exigence explicite du ticket).
        List<String> packQuestIds = new ArrayList<>();
        Map<String, List<String>> packPrereqs = new LinkedHashMap<>();
        for (Object raw : asList(content.get("quests"))) {
            if (raw instanceof Map<?, ?> m) {
                String id = QuestYaml.nsId(str(m.get("id")));
                if (!id.isBlank()) {
                    packQuestIds.add(id);
                    List<String> prereqs = new ArrayList<>();
                    for (Object p : asList(m.get("prerequisites"))) {
                        prereqs.add(QuestYaml.nsId(String.valueOf(p)));
                    }
                    packPrereqs.put(id, prereqs);
                }
            }
        }
        List<String> packNpcIds = new ArrayList<>();
        for (Object raw : asList(content.get("npcs"))) {
            if (raw instanceof Map<?, ?> m) {
                String id = str(m.get("id"));
                if (!id.isBlank()) {
                    packNpcIds.add(id);
                }
            }
        }
        RefData augmented = (ref == null ? RefData.empty() : ref)
                .plus(packQuestIds, packNpcIds, packPrereqs);

        List<Element> elements = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String family : ContentPackSchema.FAMILIES) {
            for (Object raw : asList(content.get(family))) {
                elements.add(element(family, raw, workspace, augmented, choices, seen));
            }
        }

        List<Dependency> dependencies = readDependencies(pack.get("dependencies"), packQuestIds,
                packNpcIds, workspace, augmented);

        return new Analysis(envelope, elements, dependencies, metadata, true);
    }

    private static Element element(String family, Object raw, ContentWorkspace workspace,
                                   RefData ref, Map<String, Decision> decisions, Set<String> seen) {
        if (!(raw instanceof Map<?, ?> m)) {
            return invalid(family, "", "", "Élément mal formé : une table était attendue.");
        }
        String rawId = str(m.get("id"));
        if (rawId.isBlank()) {
            return invalid(family, "", "", "« id » absent : un élément sans identifiant ne peut pas "
                    + "être enregistré.");
        }
        // Le slug — donc le nom de fichier — est DÉRIVÉ de l'identifiant métier, jamais lu du
        // fichier. C'est ce qui rend toute traversée de chemin structurellement impossible.
        String slug = slugOf(family, rawId);
        if (slug.isEmpty()) {
            return invalid(family, "", rawId, "Identifiant « " + rawId + " » inutilisable comme nom "
                    + "de contenu : minuscules, chiffres, « _ » et « - » seulement.");
        }
        String key = family + "/" + slug;
        if (!seen.add(key)) {
            return invalid(family, slug, rawId, "Doublon dans le pack : « " + slug + " » apparaît "
                    + "plusieurs fois dans la famille « " + family + " ».");
        }
        if (!SUPPORTED_FAMILIES.contains(family)) {
            return new Element(family, slug, rawId, Status.SKIPPED, List.of(), null, null, List.of(),
                    "La famille « " + family + " » fait partie du format de pack mais n'est pas "
                            + "éditable depuis le Control Panel : cet élément est laissé de côté. "
                            + "Il n'est ni écrit, ni perdu — il reste dans le fichier importé.");
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        String yaml;
        switch (family) {
            case "quests" -> {
                QuestYaml.ReadResult r = QuestYaml.fromMap(m);
                if (r.draft() == null) {
                    return invalid(family, slug, rawId, String.join(" ; ", r.problems()));
                }
                r.problems().forEach(p -> diagnostics.add(Diagnostic.error("contenu", p)));
                diagnostics.addAll(QuestValidator.validate(r.draft(), ref));
                yaml = QuestYaml.write(r.draft());
            }
            case "stories" -> {
                StoryYaml.ReadResult r = StoryYaml.fromMap(m);
                if (r.draft() == null) {
                    return invalid(family, slug, rawId, String.join(" ; ", r.problems()));
                }
                r.problems().forEach(p -> diagnostics.add(Diagnostic.error("contenu", p)));
                diagnostics.addAll(StoryValidator.validate(r.draft(), ref));
                yaml = StoryYaml.write(r.draft());
            }
            case "dialogues" -> {
                DialogueYaml.ReadResult r = DialogueYaml.fromMap(m);
                if (r.draft() == null) {
                    return invalid(family, slug, rawId, String.join(" ; ", r.problems()));
                }
                r.problems().forEach(p -> diagnostics.add(Diagnostic.error("contenu", p)));
                diagnostics.addAll(DialogueValidator.validate(r.draft()));
                yaml = DialogueYaml.write(r.draft());
            }
            default -> {
                return invalid(family, slug, rawId, "Famille non prise en charge.");
            }
        }

        if (Diagnostic.hasError(diagnostics)) {
            return new Element(family, slug, rawId, Status.INVALID, diagnostics, yaml, null, List.of(),
                    "Erreurs de validation : l'import est impossible tant qu'elles subsistent.");
        }

        var existing = workspace == null ? java.util.Optional.<ContentWorkspace.ContentFile>empty()
                : workspace.read(family, slug);
        if (existing.isEmpty()) {
            return new Element(family, slug, rawId, Status.NEW, diagnostics, yaml, null, List.of(), null);
        }
        String current = existing.get().text();
        if (TextDiff.identical(current, yaml)) {
            return new Element(family, slug, rawId, Status.UNCHANGED, diagnostics, yaml,
                    existing.get().sha256(), List.of(), null);
        }
        List<TextDiff.Line> diff = TextDiff.diff(current, yaml);
        Decision decision = decisions.get(key);
        if (decision == Decision.SKIP) {
            return new Element(family, slug, rawId, Status.SKIPPED, diagnostics, yaml,
                    existing.get().sha256(), diff, "Collision ignorée : le contenu existant est conservé.");
        }
        if (decision == Decision.REPLACE) {
            return new Element(family, slug, rawId, Status.MODIFIED, diagnostics, yaml,
                    existing.get().sha256(), diff, null);
        }
        return new Element(family, slug, rawId, Status.CONFLICT, diagnostics, yaml,
                existing.get().sha256(), diff,
                "Un contenu « " + slug + " » existe déjà et diffère. Choisir explicitement de le "
                        + "remplacer ou de l'ignorer — rien n'est écrasé sans décision.");
    }

    private static Element invalid(String family, String slug, String id, String reason) {
        return new Element(family, slug, id, Status.INVALID,
                List.of(Diagnostic.error("contenu", reason)), null, null, List.of(), reason);
    }

    /**
     * {@code slug} = nom de fichier, dérivé de l'identifiant métier. Une quête porte un identifiant
     * {@code namespace:clé} dont seule la clé sert de nom de fichier, exactement comme l'éditeur.
     */
    private static String slugOf(String family, String rawId) {
        String id = "quests".equals(family) ? QuestYaml.plainId(rawId) : rawId.trim();
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.matches("[a-z0-9][a-z0-9_-]{0,63}") ? lower : "";
    }

    // ---- Dépendances --------------------------------------------------------------------------

    private static List<Dependency> readDependencies(Object raw, List<String> packQuests,
                                                     List<String> packNpcs,
                                                     ContentWorkspace workspace, RefData ref) {
        if (!(raw instanceof Map<?, ?> m)) {
            return List.of();
        }
        List<Dependency> out = new ArrayList<>();
        for (String family : List.of("quests", "npcs", "dialogues", "items")) {
            for (Object o : asList(m.get(family))) {
                String id = str(o);
                if (id.isBlank()) {
                    continue;
                }
                out.add(new Dependency(family, id, dependencyState(family, id, packQuests, packNpcs,
                        workspace, ref)));
            }
        }
        return out;
    }

    private static DependencyState dependencyState(String family, String id, List<String> packQuests,
                                                   List<String> packNpcs, ContentWorkspace workspace,
                                                   RefData ref) {
        switch (family) {
            case "quests" -> {
                String ns = QuestYaml.nsId(id);
                if (packQuests.contains(ns)) {
                    return DependencyState.IN_PACK;
                }
                return ref != null && ref.isQuestKnown(ns) ? DependencyState.ON_SERVER
                        : DependencyState.MISSING;
            }
            case "npcs" -> {
                if (packNpcs.contains(id)) {
                    return DependencyState.IN_PACK;
                }
                return ref != null && ref.isNpcKnown(id) ? DependencyState.ON_SERVER
                        : DependencyState.MISSING;
            }
            case "dialogues" -> {
                return workspace != null && workspace.exists("dialogues", id)
                        ? DependencyState.ON_SERVER : DependencyState.MISSING;
            }
            // « items » : le panel n'a aucun relevé fiable des objets personnalisés, donc dire
            // « manquant » serait faux. On ne prétend rien : l'état reste indéterminé côté serveur.
            default -> {
                return DependencyState.MISSING;
            }
        }
    }

    // ---- Écriture (confirmation) ---------------------------------------------------------------

    /**
     * @param element élément concerné
     * @param result  résultat d'écriture du workspace ({@code null} = rien n'a été tenté)
     */
    public record WriteOutcome(Element element, ContentWorkspace.WriteResult result) {
    }

    /**
     * Applique une analyse <strong>déjà confirmée</strong>. Seul point d'écriture de tout l'import.
     *
     * <p>Refuse de travailler si l'analyse n'est pas importable : c'est la garantie qu'aucune
     * confirmation ne peut contourner la validation ni un conflit non tranché. Chaque remplacement
     * repasse par le verrou optimiste du workspace, donc un fichier modifié entre l'analyse et la
     * confirmation est refusé plutôt qu'écrasé.</p>
     *
     * <p>Une écriture qui échoue n'interrompt pas les suivantes : le rapport dit élément par élément
     * ce qui a été écrit et ce qui ne l'a pas été, plutôt que de laisser un import à moitié appliqué
     * sans trace.</p>
     */
    public static List<WriteOutcome> apply(Analysis analysis, ContentWorkspace workspace) {
        if (analysis == null || !analysis.importable()) {
            throw new IllegalStateException("Analyse non importable : apply() ne doit jamais être "
                    + "appelé sans une analyse valide et sans collision en attente.");
        }
        List<WriteOutcome> out = new ArrayList<>();
        for (Element e : analysis.elements()) {
            if (!e.writes()) {
                out.add(new WriteOutcome(e, null));
                continue;
            }
            // NEW : empreinte attendue vide, donc le workspace refuse si le fichier est apparu
            // entre-temps. MODIFIED : empreinte relevée à l'analyse, donc il refuse s'il a changé.
            String expected = e.status() == Status.MODIFIED ? e.currentSha() : null;
            out.add(new WriteOutcome(e, workspace.write(e.family(), e.slug(), e.yaml(), expected)));
        }
        return out;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static Map<String, String> readMetadata(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object v = e.getValue();
            if (v != null && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                out.put(str(e.getKey()), str(v));
            }
        }
        return out;
    }

    private static Integer asInt(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(str(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static List<Object> asList(Object raw) {
        return raw instanceof List<?> list ? List.copyOf(list) : List.of();
    }
}
