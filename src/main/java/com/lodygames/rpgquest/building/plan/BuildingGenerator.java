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

    public BuildingGenerator(BuildingLibrary library, SchematicWorkshop workshop,
                             Path buildingsDirectory, Logger logger) {
        this.library = library;
        this.workshop = workshop;
        this.buildingsDirectory = buildingsDirectory;
        this.logger = logger;
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
                          ArchitecturalAudit.Metrics metrics) {

        public Verdict {
            buildingId = buildingId == null ? "" : buildingId;
            kind = kind == null ? "" : kind;
            message = message == null ? "" : message;
            codes = List.copyOf(codes == null ? List.of() : codes);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        static Verdict unreadable(String message) {
            return new Verdict(false, "PLAN_INVALID", message, "", "", 0, 0, 0, 0,
                    List.of("PLAN_INVALID"), List.of(), false, null);
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
        List<String> problems = new ArrayList<>();
        Optional<BuildingPlan> parsed = BuildingPlanYaml.read(planYaml, problems);
        if (parsed.isEmpty()) {
            return Verdict.unreadable("Plan refusé : "
                    + (problems.isEmpty() ? "illisible." : String.join(" ", problems)));
        }
        BuildingPlan plan = parsed.get();
        boolean exists = library.find(plan.id()).isPresent();

        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        if (!expansion.ok()) {
            return new Verdict(false, "PLAN_INVALID", "Plan refusé : " + expansion.error(),
                    plan.id(), plan.kind().label(), plan.sizeX(), plan.sizeY(), plan.sizeZ(), 0,
                    List.of("PLAN_INVALID"), List.of(), exists, null);
        }

        ArchitecturalAudit.Report audit = ArchitecturalAudit.audit(
                expansion.blueprint(), plan.front(), plan.rules());
        List<String> warnings = new ArrayList<>();
        for (ArchitecturalFinding warning : audit.warnings()) {
            warnings.add(warning.message());
        }
        return new Verdict(!audit.refused(),
                audit.refused() ? "ARCHITECTURE_REFUSED" : "OK",
                audit.refused() ? refusalText(plan, audit) : "Plan conforme au contrat "
                        + plan.kind().label() + " : " + expansion.solidCount() + " blocs.",
                plan.id(), plan.kind().label(), plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                expansion.solidCount(), audit.blockingCodes(), List.copyOf(warnings), exists,
                audit.metrics());
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
        List<String> problems = new ArrayList<>();
        Optional<BuildingPlan> parsed = BuildingPlanYaml.read(planYaml, problems);
        if (parsed.isEmpty()) {
            return Result.failure("PLAN_INVALID", "Plan refusé : "
                    + (problems.isEmpty() ? "illisible." : String.join(" ", problems)));
        }
        BuildingPlan plan = parsed.get();

        Optional<BuildingDefinition> existing = library.find(plan.id());
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

        // ---- Contrat architectural (issue #94, lot A) -------------------------------------
        // Audité sur la GRILLE, jamais sur les instructions : un plan peut écrire une instruction
        // nommée « porte », seule la grille dit s'il y a un trou dans le mur. La V1 l'a prouvé —
        // son percement visait x=5 alors que le mur était en x=0.
        //
        // Le refus arrive AVANT toute écriture : ni .schem, ni définition, ni entrée de
        // bibliothèque. Et son message est rédigé pour être renvoyé tel quel au modèle par la
        // boucle de correction qui existe déjà.
        ArchitecturalAudit.Report audit = ArchitecturalAudit.audit(
                expansion.blueprint(), plan.front(), plan.rules());
        if (audit.refused()) {
            return new Result(false, "ARCHITECTURE_REFUSED", refusalText(plan, audit),
                    plan.id(), "", plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                    expansion.solidCount(), 0, false, List.of());
        }

        String schematic = plan.id() + ".schem";
        SchematicGateway.Outcome written = workshop.regenerate(schematic, expansion.blueprint());
        if (!written.ok()) {
            // Le .schem n'existe pas : surtout ne pas écrire la définition, qui désignerait un
            // fichier absent et afficherait « schematic absent » dans la bibliothèque.
            return Result.failure("SCHEMATIC_FAILED",
                    "Le schematic n'a pas pu être écrit : " + written.error()
                            + " Aucune définition n'a été créée.");
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
