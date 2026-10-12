package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicGateway;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Produit un bâtiment <strong>réel</strong> à partir d'un plan déclaratif (issue #94).
 *
 * <h2>Ce que « réel » veut dire ici</h2>
 *
 * <p>Le ticket l'exige : « aucun faux succès : un résultat affiché comme généré doit correspondre à
 * un artefact réel ». Un succès de cette classe signifie donc que <em>trois</em> choses existent
 * sur le disque du serveur :</p>
 *
 * <ol>
 *   <li>un fichier {@code .schem} valide, écrit par l'écrivain officiel de WorldEdit ;</li>
 *   <li>une définition {@code buildings/<id>.yml} qui le désigne, avec ses vraies dimensions ;</li>
 *   <li>une entrée dans la bibliothèque rechargée, donc posable par le flux de placement #213.</li>
 * </ol>
 *
 * <p>Si l'une des trois échoue, l'opération échoue et le dit. Il n'y a pas d'état « généré à
 * moitié » affiché comme un succès.</p>
 *
 * <h2>Rien de ce que l'IA écrit n'est exécuté</h2>
 *
 * <p>L'IA ne fournit qu'un {@link BuildingPlan} : une taille, une ancre, une orientation, et une
 * liste finie d'instructions au vocabulaire fermé. Le plan est revalidé ici, expansé par un moteur
 * déterministe, et le seul texte qui atteint WorldEdit est un état de bloc dont la forme a été
 * contrôlée. Aucun chemin de fichier, aucune commande, aucun identifiant de fichier ne vient du
 * modèle : le nom du {@code .schem} est <strong>dérivé</strong> de l'identifiant du plan, lui-même
 * borné à {@code [a-z0-9_]}.</p>
 */
public final class BuildingGenerator {

    private final BuildingLibrary library;
    private final SchematicWorkshop workshop;
    private final Path buildingsDirectory;
    private final Logger logger;
    private Path schematicsDirectory;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    public BuildingGenerator(BuildingLibrary library, SchematicWorkshop workshop,
                             Path buildingsDirectory, Logger logger) {
        this.library = library;
        this.workshop = workshop;
        this.buildingsDirectory = buildingsDirectory;
        this.logger = logger;
    }

    /**
     * Où archiver la version remplacée d'un bâtiment republié (#290). Sans cet appel, rien n'est
     * archivé côté schematic — la définition l'est toujours.
     */
    public BuildingGenerator archivingTo(Path schematics, java.time.Clock clock) {
        this.schematicsDirectory = schematics;
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
        return this;
    }

    /**
     * Le résultat d'une génération, avec les faits constatés — jamais la demande.
     *
     * @param buildingId identifiant réellement écrit
     * @param schematic  nom du fichier {@code .schem} produit
     * @param blocks     nombre de blocs de matière réellement posés dans le plan
     * @param version    version de la définition après écriture
     * @param replaced   une définition portant cet identifiant existait et a été remplacée
     */
    public record Result(boolean ok, String code, String message, String buildingId,
                         String schematic, int sizeX, int sizeY, int sizeZ, int blocks,
                         int version, boolean replaced, List<String> effects) {

        public Result {
            buildingId = buildingId == null ? "" : buildingId;
            schematic = schematic == null ? "" : schematic;
            message = message == null ? "" : message;
            effects = List.copyOf(effects == null ? List.of() : effects);
        }

        static Result failure(String code, String message) {
            return new Result(false, code, message, "", "", 0, 0, 0, 0, 0, false, List.of());
        }
    }

    /**
     * Le verdict d'un plan, obtenu <strong>sans rien écrire</strong> (issue #94, lot F).
     *
     * <p>C'est ce que la boucle de correction consomme. Les {@code codes} sont faits pour être
     * testés et affichés ; le {@code message} est fait pour être renvoyé tel quel au modèle ; les
     * {@code metrics} donnent au modèle les chiffres qui contredisent son intention — « tu crois
     * avoir mis des fenêtres, la grille en compte zéro ».</p>
     *
     * @param code       {@code OK}, {@code PLAN_INVALID} ou {@code ARCHITECTURE_REFUSED}
     * @param exists     un bâtiment de cet identifiant est déjà dans la bibliothèque
     */
    public record Verdict(boolean ok, String code, String message, String buildingId,
                          String kind, int sizeX, int sizeY, int sizeZ, int blocks,
                          List<String> codes, List<String> warnings, boolean exists,
                          ArchitecturalAudit.Metrics metrics, Repair repair) {

        public Verdict {
            buildingId = buildingId == null ? "" : buildingId;
            kind = kind == null ? "" : kind;
            message = message == null ? "" : message;
            codes = List.copyOf(codes == null ? List.of() : codes);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        public Verdict(boolean ok, String code, String message, String buildingId, String kind,
                       int sizeX, int sizeY, int sizeZ, int blocks, List<String> codes,
                       List<String> warnings, boolean exists, ArchitecturalAudit.Metrics metrics) {
            this(ok, code, message, buildingId, kind, sizeX, sizeY, sizeZ, blocks, codes, warnings,
                    exists, metrics, null);
        }

        static Verdict unreadable(String message) {
            return new Verdict(false, "PLAN_INVALID", message, "", "", 0, 0, 0, 0,
                    List.of("PLAN_INVALID"), List.of(), false, null);
        }

        Verdict withRepair(Repair found) {
            return new Verdict(ok, code, message, buildingId, kind, sizeX, sizeY, sizeZ, blocks,
                    codes, warnings, exists, metrics, found);
        }
    }

    /**
     * La réparation déterministe proposée par le moteur pour un plan refusé (issue #282).
     *
     * @param plan     le plan réparé, complet, prêt à devenir une nouvelle version
     * @param changes  une phrase par correction appliquée
     * @param codes    les refus qui restent — vide si le plan réparé est conforme
     * @param accepted le plan réparé passe entièrement l'audit
     */
    public record Repair(String plan, List<String> changes, List<String> codes, boolean accepted) {

        public Repair {
            changes = List.copyOf(changes == null ? List.of() : changes);
            codes = List.copyOf(codes == null ? List.of() : codes);
        }
    }

    /**
     * Juge un plan sans produire d'artefact : ni {@code .schem}, ni définition, ni entrée de
     * bibliothèque.
     *
     * <p>C'est la pièce qui rend la boucle de correction tenable. Avant ce lot, le seul moyen de
     * connaître le verdict du serveur était de <em>générer</em> — donc d'écrire un bâtiment pour
     * apprendre qu'il était mauvais, puis de le remplacer. Inspecter d'abord, écrire ensuite :
     * l'ordre naturel, et le seul qui permette de corriger un plan plusieurs fois sans laisser de
     * traces derrière soi.</p>
     *
     * <p>Et c'est <strong>le même</strong> pipeline que {@link #generate(String, boolean)} : même
     * lecture, même expanseur, même audit. Un second chemin de validation finirait par diverger du
     * premier et ferait passer pour bon un plan que la génération refuse.</p>
     */
    public Verdict inspect(String planYaml) {
        return inspect(planYaml, PlanRepair.Limits.none());
    }

    /** Même chose, en sachant jusqu'où le projet autorise le bâtiment à grandir (réparation). */
    public Verdict inspect(String planYaml, PlanRepair.Limits limits) {
        return examine(planYaml, limits).verdict();
    }

    /**
     * L'aperçu du bâtiment que ce plan produirait (issue #94, lot E), avec son verdict.
     *
     * <p>Les coupes sont prises sur la <strong>même grille</strong> que l'audit, après la même
     * expansion. C'est la raison d'être de cette méthode : un aperçu dessiné ailleurs montrerait ce
     * qu'on <em>croit</em> que le plan décrit, et la V1 a prouvé que les deux peuvent divergier de
     * cinq blocs. Les avertissements accompagnent donc l'aperçu, et non un second appel.</p>
     *
     * <p>N'écrit rien, comme {@link #inspect(String)}.</p>
     */
    public Examination preview(String planYaml) {
        return preview(planYaml, PlanRepair.Limits.none());
    }

    public Examination preview(String planYaml, PlanRepair.Limits limits) {
        return examine(planYaml, limits);
    }

    /** Un plan examiné : son verdict, et l'aperçu s'il a pu être construit. */
    public record Examination(Verdict verdict, PlanPreview.Preview preview) {

        /** Y a-t-il des coupes à montrer ? Un plan illisible n'en a aucune. */
        public boolean hasPreview() {
            return preview != null;
        }
    }

    /**
     * Le chemin unique : lire, expanser, auditer, prendre les coupes.
     *
     * <p>Un seul chemin, délibérément. L'inspection, l'aperçu et la génération doivent donner le
     * même verdict sur le même plan, et trois implémentations finiraient par diverger — ce qui
     * ferait passer pour bon un plan que la génération refuse, ou montrerait un aperçu qui ne
     * correspond pas à ce qui sera construit.</p>
     */
    private Examination examine(String planYaml, PlanRepair.Limits limits) {
        Examination raw = examineAsIs(planYaml);
        if (raw.verdict().ok()) {
            return raw;
        }
        // #282 : le serveur connaît la faute ; s'il sait la corriger sans risque, il le dit — avec
        // le plan corrigé et vérifié — au lieu de renvoyer l'administrateur vers l'IA.
        List<String> ignored = new ArrayList<>();
        Optional<BuildingPlan> loose = BuildingPlanYaml.readUnvalidated(planYaml, ignored);
        if (loose.isEmpty()) {
            return raw;
        }
        PlanRepair.Outcome outcome = PlanRepair.repair(loose.get(), limits);
        if (!outcome.repaired()) {
            return raw;
        }
        return new Examination(raw.verdict().withRepair(new Repair(
                BuildingPlanYaml.write(outcome.plan()), outcome.changes(), outcome.after(),
                outcome.accepted())), raw.preview());
    }

    private Examination examineAsIs(String planYaml) {
        List<String> problems = new ArrayList<>();
        Optional<BuildingPlan> parsed = BuildingPlanYaml.read(planYaml, problems);
        if (parsed.isEmpty()) {
            return new Examination(Verdict.unreadable("Plan refusé : "
                    + (problems.isEmpty() ? "illisible." : String.join(" ", problems))), null);
        }
        BuildingPlan plan = parsed.get();
        boolean exists = library.find(plan.id()).isPresent();

        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        if (!expansion.ok()) {
            return new Examination(new Verdict(false, "PLAN_INVALID",
                    "Plan refusé : " + expansion.error(),
                    plan.id(), plan.kind().label(), plan.sizeX(), plan.sizeY(), plan.sizeZ(), 0,
                    List.of("PLAN_INVALID"), List.of(), exists, null), null);
        }

        Optional<String> engineRefusal = engineRefusal(expansion);
        if (engineRefusal.isPresent()) {
            return new Examination(new Verdict(false, "PLAN_INVALID", engineRefusal.get(),
                    plan.id(), plan.kind().label(), plan.sizeX(), plan.sizeY(), plan.sizeZ(), 0,
                    List.of("PLAN_INVALID", "BLOCK_REJECTED"), List.of(), exists, null), null);
        }

        ArchitecturalAudit.Report audit = ArchitecturalAudit.audit(
                expansion.blueprint(), plan.front(), plan.rules(), expansion.provenance(),
                plan.anchorY());
        List<String> warnings = new ArrayList<>();
        for (ArchitecturalFinding warning : audit.warnings()) {
            warnings.add(warning.message());
        }
        Verdict verdict = new Verdict(!audit.refused(),
                audit.refused() ? "ARCHITECTURE_REFUSED" : "OK",
                audit.refused() ? refusalText(plan, audit) : "Plan conforme au contrat "
                        + plan.kind().label() + " : " + expansion.solidCount() + " blocs.",
                plan.id(), plan.kind().label(), plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                expansion.solidCount(), audit.blockingCodes(), List.copyOf(warnings), exists,
                audit.metrics());
        // L'aperçu est produit même pour un plan REFUSÉ, et c'est tout l'intérêt : on veut voir
        // POURQUOI il est refusé. Une façade sans porte se constate sur une coupe en une seconde.
        return new Examination(verdict, PlanPreview.of(expansion.blueprint(), plan.front(),
                plan.anchorX(), plan.anchorY(), plan.anchorZ()));
    }

    /**
     * Génère le bâtiment décrit par ce plan YAML.
     *
     * @param planYaml le plan, tel que l'IA l'a écrit et que le panel l'a transmis
     * @param replace  autorise le remplacement d'une définition existante. Faux par défaut : un
     *                 bâtiment retouché à la main ne doit pas disparaître parce qu'une génération
     *                 a réutilisé son identifiant
     */
    public Result generate(String planYaml, boolean replace) {
        return generate(planYaml, replace, 0);
    }

    /**
     * Génère, en publiant au besoin une NOUVELLE VERSION d'un bâtiment existant (#290).
     *
     * @param expectedVersion la version que l'administrateur a vue en décidant de remplacer ; si la
     *                        bibliothèque en porte une autre (quelqu'un a republié entre-temps), rien
     *                        n'est écrit ({@code VERSION_CONFLICT}). 0 : pas de contrôle
     */
    public Result generate(String planYaml, boolean replace, int expectedVersion) {
        List<String> problems = new ArrayList<>();
        Optional<BuildingPlan> parsed = BuildingPlanYaml.read(planYaml, problems);
        if (parsed.isEmpty()) {
            return Result.failure("PLAN_INVALID", "Plan refusé : "
                    + (problems.isEmpty() ? "illisible." : String.join(" ", problems)));
        }
        BuildingPlan plan = parsed.get();

        Optional<BuildingDefinition> existing = library.find(plan.id());
        if (existing.isPresent() && replace && expectedVersion > 0
                && existing.get().version() != expectedVersion) {
            return Result.failure("VERSION_CONFLICT", "« " + existing.get().name() + " » est en version "
                    + existing.get().version() + " dans la bibliothèque, et non en version "
                    + expectedVersion + " comme lorsque la publication a été décidée : quelqu'un l'a "
                    + "republié entre-temps. Rien n'a été écrit — rechargez la bibliothèque et "
                    + "recommencez en connaissance de cause.");
        }
        if (existing.isPresent() && !replace) {
            return Result.failure("ALREADY_EXISTS", "Un bâtiment « " + plan.id()
                    + " » existe déjà dans la bibliothèque (« " + existing.get().name()
                    + " »). Rien n'a été écrit — cocher le remplacement pour l'écraser, ou "
                    + "changer l'identifiant du plan.");
        }

        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        if (!expansion.ok()) {
            return Result.failure("PLAN_INVALID", "Plan refusé : " + expansion.error());
        }

        Optional<String> engineRefusal = engineRefusal(expansion);
        if (engineRefusal.isPresent()) {
            return Result.failure("PLAN_INVALID", engineRefusal.get());
        }

        // ---- Contrat architectural (issue #94, lot A) -------------------------------------
        // Audité sur la GRILLE, jamais sur les instructions : un plan peut écrire une instruction
        // nommée « porte », seule la grille dit s'il y a un trou dans le mur. La V1 l'a prouvé —
        // son percement visait x=5 alors que le mur était en x=0.
        //
        // Le refus arrive AVANT toute écriture : ni .schem, ni définition, ni entrée de
        // bibliothèque. Et son message est rédigé pour être renvoyé tel quel au modèle par la
        // boucle de correction qui existe déjà.
        ArchitecturalAudit.Report audit = ArchitecturalAudit.audit(
                expansion.blueprint(), plan.front(), plan.rules(), expansion.provenance(),
                plan.anchorY());
        if (audit.refused()) {
            return new Result(false, "ARCHITECTURE_REFUSED", refusalText(plan, audit),
                    plan.id(), "", plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                    expansion.solidCount(), 0, false, List.of());
        }

        String schematic = plan.id() + ".schem";
        List<String> archived = new ArrayList<>();
        if (existing.isPresent()) {
            // #290 : la version remplacée est ARCHIVÉE avant toute écriture — définition et
            // schematic — pour qu'un retour en arrière reste possible. Les exemplaires déjà posés ne
            // bougent pas : seule une action explicite de remplacement les met à jour.
            try {
                archived.addAll(archive(existing.get()));
            } catch (IOException error) {
                return Result.failure("WRITE_FAILED", "La version " + existing.get().version()
                        + " n'a pas pu être archivée avant d'être remplacée ("
                        + error.getClass().getSimpleName() + ") : rien n'a été écrit.");
            }
        }
        SchematicGateway.Outcome written = workshop.regenerate(schematic, expansion.blueprint());
        if (!written.ok()) {
            // Le .schem n'existe pas : surtout ne pas écrire la définition, qui désignerait un
            // fichier absent et afficherait « schematic absent » dans la bibliothèque.
            // Échec d'ÉCRITURE, pas de contenu : les états ont tous passé le registre et le
            // parseur du moteur juste au-dessus. Le dire évite de renvoyer le plan à l'IA pour une
            // panne de disque ou de moteur.
            return Result.failure("SCHEMATIC_FAILED",
                    "Le schematic n'a pas pu être écrit : " + written.error()
                            + " Aucune définition n'a été créée. (Échec d'écriture : le plan avait "
                            + "passé la validation de ses blocs ; réessayer, ou vérifier WorldEdit "
                            + "et le disque du serveur.)");
        }

        int version = existing.map(definition -> definition.version() + 1).orElse(1);
        BuildingDefinition definition = new BuildingDefinition(plan.id(), plan.name(),
                "Généré depuis un plan (issue #94).", schematic,
                plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                plan.anchorX(), plan.anchorY(), plan.anchorZ(),
                plan.front(), plan.materials(), version);
        Optional<String> invalid = definition.validate();
        if (invalid.isPresent()) {
            return Result.failure("DEFINITION_INVALID",
                    "Le schematic a été écrit, mais la définition dérivée est invalide : "
                            + invalid.get() + " Rien n'a été ajouté à la bibliothèque.");
        }

        try {
            write(definition, planYaml);
        } catch (IOException error) {
            return Result.failure("WRITE_FAILED", "Le schematic a été écrit, mais la définition "
                    + "n'a pas pu être enregistrée : " + error.getClass().getSimpleName()
                    + ". Le bâtiment n'apparaîtra pas dans la bibliothèque.");
        }

        library.reload();
        // Relecture : on n'annonce un succès que si la bibliothèque porte RÉELLEMENT le bâtiment.
        if (library.find(plan.id()).isEmpty()) {
            return Result.failure("NOT_IN_LIBRARY", "La définition a été écrite mais la "
                    + "bibliothèque ne la porte pas après rechargement — voir les erreurs de "
                    + "chargement de la bibliothèque.");
        }

        logger.info("[building] bâtiment généré : " + plan.id() + " (" + plan.sizeX() + " × "
                + plan.sizeY() + " × " + plan.sizeZ() + ", " + expansion.solidCount()
                + " blocs, version " + version + ").");
        List<String> effects = new ArrayList<>(List.of("schematics/" + schematic,
                "buildings/" + plan.id() + ".yml"));
        effects.addAll(archived);
        // Les avertissements ne bloquent pas, mais les taire reviendrait à livrer un bâtiment dont
        // on sait quelque chose sans le dire.
        for (ArchitecturalFinding warning : audit.warnings()) {
            effects.add("⚠ " + warning.message());
        }
        return new Result(true, existing.isPresent() ? "REGENERATED" : "GENERATED",
                "Bâtiment « " + plan.name() + " » généré : " + plan.sizeX() + " × " + plan.sizeZ()
                        + " × " + plan.sizeY() + " blocs d'emprise, " + expansion.solidCount()
                        + " blocs de matière, version " + version + ". "
                        + (existing.isPresent()
                                ? "Une définition existante a été remplacée."
                                : "Ajouté à la bibliothèque, prêt à être posé."),
                plan.id(), schematic, plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                expansion.solidCount(), version, existing.isPresent(), List.copyOf(effects));
    }

    /**
     * Les états de la grille que le VRAI parseur du moteur refuserait (#288–#292). Le registre
     * embarqué les a déjà tous contrôlés à l'expansion ; ce second contrôle couvre l'écart possible
     * entre la table et le serveur réellement en service, avant de prononcer « conforme ».
     */
    private Optional<String> engineRefusal(BuildingPlanExpander.Result expansion) {
        java.util.Map<String, java.util.List<int[]>> where = new java.util.LinkedHashMap<>();
        for (var block : expansion.blueprint().blocks()) {
            where.computeIfAbsent(block.state(), k -> new ArrayList<>())
                    .add(new int[] {block.x(), block.y(), block.z()});
        }
        java.util.Map<String, String> refused = workshop.rejected(where.keySet());
        if (refused.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("Plan refusé : le moteur de schematics du serveur "
                + "refuse " + refused.size() + " état(s) de bloc — rien n'a été écrit :");
        for (var e : refused.entrySet()) {
            int[] at = where.get(e.getKey()).get(0);
            int rank = expansion.provenance().stepAt(at[0], at[1], at[2]);
            sb.append("\n- « ").append(e.getKey()).append(" » en ").append(at[0]).append('/')
                    .append(at[1]).append('/').append(at[2]).append(" (")
                    .append(where.get(e.getKey()).size()).append(" cellule(s), ")
                    .append(BuildingPlanExpander.describeStep(expansion.plan(), rank)).append(") : ")
                    .append(e.getValue());
        }
        return Optional.of(sb.toString());
    }

    /**
     * Copie la version courante dans {@code buildings/.history/<id>/v<version>_<horodatage>/}.
     * Une copie et non un déplacement : si l'écriture qui suit échoue, la bibliothèque garde son
     * bâtiment intact.
     */
    private List<String> archive(BuildingDefinition current) throws IOException {
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(clock.instant());
        Path target = buildingsDirectory.resolve(".history").resolve(current.id())
                .resolve("v" + current.version() + "_" + stamp);
        Files.createDirectories(target);
        List<String> effects = new ArrayList<>();
        Path yml = buildingsDirectory.resolve(current.id() + ".yml");
        if (Files.isRegularFile(yml)) {
            Files.copy(yml, target.resolve(yml.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            effects.add("version " + current.version() + " archivée : buildings/.history/" + current.id()
                    + "/" + target.getFileName() + "/");
        }
        if (schematicsDirectory != null) {
            Path schem = schematicsDirectory.resolve(current.schematic());
            if (Files.isRegularFile(schem)) {
                Files.copy(schem, target.resolve(schem.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return effects;
    }

    /**
     * Le texte d'un refus architectural — rédigé une seule fois, lu par l'écran <em>et</em> renvoyé
     * au modèle. Deux rédactions divergentes apprendraient deux choses différentes du même refus.
     */
    private static String refusalText(BuildingPlan plan, ArchitecturalAudit.Report audit) {
        return "Plan refusé — contrat architectural non respecté (" + plan.kind().label() + "). "
                + audit.refusalMessage()
                + "\nRien n'a été écrit : ni schematic, ni définition.";
    }

    /**
     * Écrit {@code buildings/<id>.yml}.
     *
     * <p>Le <strong>plan source est conservé dans le fichier</strong>, sous la clé {@code plan}.
     * C'est délibéré : le {@code .schem} est un blob que personne ne relit, alors que le plan se
     * relit, se corrige et se régénère. Sans lui, un bâtiment généré serait un cul-de-sac — on ne
     * pourrait plus que le refaire de zéro.</p>
     */
    private void write(BuildingDefinition definition, String planYaml) throws IOException {
        Files.createDirectories(buildingsDirectory);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", definition.id());
        yaml.set("name", definition.name());
        yaml.set("description", definition.description());
        yaml.set("schematic", definition.schematic());
        yaml.set("size.x", definition.sizeX());
        yaml.set("size.y", definition.sizeY());
        yaml.set("size.z", definition.sizeZ());
        yaml.set("anchor.x", definition.anchorX());
        yaml.set("anchor.y", definition.anchorY());
        yaml.set("anchor.z", definition.anchorZ());
        yaml.set("front", definition.front().name());
        yaml.set("materials", definition.materials());
        yaml.set("version", definition.version());
        yaml.set("plan", planYaml);
        yaml.options().setHeader(List.of(
                "Bâtiment généré par RPGQuest (issue #94) — ne pas éditer à la main sans savoir.",
                "Le schematic est un artefact DÉRIVÉ du plan conservé sous la clé « plan ».",
                "Régénérer depuis ce plan reproduit exactement le même fichier."));
        yaml.save(buildingsDirectory.resolve(definition.id() + ".yml").toFile());
    }
}
