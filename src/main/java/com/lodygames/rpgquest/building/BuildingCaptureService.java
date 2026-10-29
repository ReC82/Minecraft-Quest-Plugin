package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingHistoryEntry;
import com.lodygames.rpgquest.building.model.BuildingOperation;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.building.model.BuildingRotation;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.database.BuildingHistoryRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Capturer une construction posée comme <strong>nouveau</strong> bâtiment de bibliothèque (issue
 * #246).
 *
 * <h2>Trois choses à ne pas confondre</h2>
 *
 * <ul>
 *   <li>le <strong>terrain d'origine</strong> sauvegardé avant la pose (baseline) — intouché ;</li>
 *   <li>la <strong>définition d'origine</strong> du bâtiment posé — intouchée ;</li>
 *   <li>l'<strong>état actuel</strong> du monde, retouches en jeu comprises — c'est lui, et lui
 *       seul, qui est copié ici, sous un identifiant nouveau.</li>
 * </ul>
 *
 * <h2>Orientation et ancre : aucune rotation des blocs</h2>
 *
 * <p>La zone est copiée telle qu'elle est dans le monde. La nouvelle définition déclare donc comme
 * façade de référence la direction que regarde <em>réellement</em> la façade posée (façade
 * d'origine tournée de la rotation de pose), et comme ancre la position de l'ancre du monde dans la
 * zone. Posée ailleurs, la variante est tournée par le flux existant (#213/#234) exactement comme
 * n'importe quel bâtiment — sans offset caché, puisque rien n'a été retourné à la capture.</p>
 *
 * <h2>Politique explicite, affichée avant confirmation</h2>
 *
 * <ul>
 *   <li><strong>air</strong> : capturé. Posée, la variante remplace toute son emprise, comme tout
 *       bâtiment de la bibliothèque ;</li>
 *   <li><strong>blocs à contenu</strong> (coffres, tonneaux, fours, pupitres, étagères…) : bloc et
 *       propriétés conservés, contenu jamais exporté. L'aperçu distingue ce qui reste visible
 *       (livres d'une bibliothèque sculptée ou d'un pupitre, portés par l'état du bloc), ce qui
 *       disparaît à l'œil (objets d'une étagère…) et ce qui ne se voyait pas — voir
 *       {@link CaptureContent} (issue #903). Une perte visible exige un acquittement explicite ;</li>
 *   <li><strong>entités</strong> (cadres, tableaux, supports d'armure, animaux…) : <strong>non
 *       capturées</strong>, et listées par type ;</li>
 *   <li>enseignes, bannières, pots de fleurs, têtes : capturés avec leurs données.</li>
 * </ul>
 *
 * <h2>Garanties</h2>
 *
 * <p>Aucun bloc du monde n'est modifié. Rien n'est écrasé : un identifiant ou un fichier déjà
 * présent est refusé. Une capture qui recouvre un autre bâtiment posé est refusée. Le jeton de
 * l'aperçu doit être renvoyé : une décision prise sur un aperçu périmé est refusée. Deux envois
 * simultanés pour le même emplacement ou le même identifiant : le second est refusé.</p>
 */
public final class BuildingCaptureService {

    /** Ajustement maximal des bornes, par face, en blocs. Au-delà, ce n'est plus « ce bâtiment ». */
    public static final int MAX_MARGIN = 16;

    /**
     * Volume maximal capturé, en blocs. La copie se fait sur le thread principal : au-delà, elle
     * gèlerait le serveur de façon perceptible pour les joueurs.
     */
    public static final long MAX_VOLUME = 262_144L;

    public static final int MAX_NAME = 64;
    public static final int MAX_DESCRIPTION = 500;
    public static final Pattern CATEGORY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final BuildingPlacementService placements;
    private final BuildingSiteService sites;
    private final BuildingLibrary library;
    private final SchematicGateway gateway;
    private final WorldProbe worldProbe;
    private final Clock clock;
    private final BuildingHistoryRepository history;
    private final Logger logger;

    /** Anti-double-envoi : emplacements et identifiants dont une capture est en cours. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public BuildingCaptureService(BuildingPlacementService placements, BuildingSiteService sites,
                                  BuildingLibrary library, SchematicGateway gateway,
                                  WorldProbe worldProbe, Clock clock,
                                  BuildingHistoryRepository history, Logger logger) {
        this.placements = placements;
        this.sites = sites;
        this.library = library;
        this.gateway = gateway;
        this.worldProbe = worldProbe;
        this.clock = clock;
        this.history = history;
        this.logger = logger;
    }

    /**
     * Ajustement des bornes, en blocs, face par face, dans les axes du monde. Positif : on agrandit
     * la zone de ce côté ; négatif : on la réduit.
     */
    public record Margins(int west, int east, int down, int up, int north, int south) {

        public static final Margins NONE = new Margins(0, 0, 0, 0, 0, 0);

        boolean withinLimits() {
            for (int value : new int[] {west, east, down, up, north, south}) {
                if (Math.abs(value) > MAX_MARGIN) {
                    return false;
                }
            }
            return true;
        }

        boolean none() {
            return equals(NONE);
        }

        BuildingFootprint apply(BuildingFootprint base) {
            return new BuildingFootprint(base.world(),
                    base.minX() - west, base.minY() - down, base.minZ() - north,
                    base.maxX() + east, base.maxY() + up, base.maxZ() + south);
        }

        String label() {
            return "ouest " + west + ", est " + east + ", bas " + down + ", haut " + up
                    + ", nord " + north + ", sud " + south;
        }
    }

    /**
     * Ce que l'administrateur demande.
     *
     * @param acceptVisualLoss acquittement explicite des objets affichés qui disparaîtront de la
     *                         variante (issue #903) ; sans lui, une telle capture est refusée
     */
    public record Request(String siteId, Margins margins, String newId, String name,
                          String description, String category, boolean acceptVisualLoss) {

        public Request(String siteId, Margins margins, String newId, String name, String description,
                       String category) {
            this(siteId, margins, newId, name, description, category, false);
        }

        public Request {
            margins = margins == null ? Margins.NONE : margins;
            newId = newId == null ? "" : newId.trim().toLowerCase(Locale.ROOT);
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description.trim();
            category = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * L'aperçu : ce qui serait capturé, avec la politique appliquée. N'écrit rien.
     *
     * @param region        les bornes de capture, après ajustement
     * @param localAnchor   {@code {x, y, z}} de l'ancre dans la zone capturée
     * @param front         la façade de référence de la variante
     */
    public record Preview(Request request, BuildingSite site, BuildingPlacement placement,
                          BuildingDefinition source, BuildingFootprint region, int[] localAnchor,
                          Facing front, Optional<CaptureSurvey> survey, List<String> refusals,
                          List<String> warnings, String token) {

        public Preview {
            refusals = List.copyOf(refusals);
            warnings = List.copyOf(warnings);
            survey = survey == null ? Optional.empty() : survey;
            localAnchor = localAnchor == null ? new int[] {0, 0, 0} : localAnchor.clone();
        }

        public boolean capturable() {
            return refusals.isEmpty();
        }

        public String firstRefusal() {
            return refusals.isEmpty() ? "" : refusals.get(0);
        }

        @Override
        public int[] localAnchor() {
            return localAnchor.clone();
        }
    }

    /** Résultat d'une capture : réussie avec le nouveau bâtiment, ou refusée avec le motif. */
    public record Result(boolean captured, BuildingDefinition definition, String error,
                         List<String> effects) {

        public Result {
            error = error == null ? "" : error;
            effects = effects == null ? List.of() : List.copyOf(effects);
        }

        static Result refused(String error) {
            return new Result(false, null, error, List.of());
        }
    }

    // ---- Aperçu ----------------------------------------------------------------------------------

    /**
     * Calcule l'aperçu. <strong>Lecture seule</strong> : ni bloc, ni fichier, ni ligne en base. À
     * appeler sur le thread principal (le relevé lit le monde).
     *
     * <p>Tous les refus sont listés, pas seulement le premier : un formulaire qui ne révèle les
     * problèmes qu'un par un fait recommencer cinq fois.</p>
     */
    public Preview preview(Request request) {
        List<String> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Optional<BuildingSite> site = sites.find(request.siteId());
        Optional<BuildingPlacement> placement = placements.at(request.siteId());
        if (site.isEmpty()) {
            refusals.add("Emplacement « " + request.siteId() + " » introuvable.");
        }
        if (placement.isEmpty()) {
            refusals.add("Aucun bâtiment n'est posé sur cet emplacement : il n'y a rien à capturer.");
        }
        refusals.addAll(identityRefusals(request));
        if (!request.margins().withinLimits()) {
            refusals.add("Ajustement des bornes hors limites : " + MAX_MARGIN
                    + " blocs au plus par face, vers l'extérieur ou l'intérieur ("
                    + request.margins().label() + ").");
        }
        if (!gateway.available()) {
            refusals.add("Capture impossible : " + gateway.unavailableReason());
        }
        if (site.isEmpty() || placement.isEmpty() || !request.margins().withinLimits()) {
            return new Preview(request, site.orElse(null), placement.orElse(null), null, null, null,
                    null, Optional.empty(), refusals, warnings, "");
        }

        BuildingPlacement placed = placement.get();
        Optional<BuildingDefinition> source = library.find(placed.buildingId());
        Facing front = null;
        if (source.isEmpty()) {
            refusals.add("La définition d'origine « " + placed.buildingId() + " » n'est plus dans la "
                    + "bibliothèque : l'orientation de la façade posée ne peut pas être établie.");
        } else {
            front = BuildingRotation.rotate(source.get().front(), placed.rotationDegrees());
        }

        BuildingFootprint region = request.margins().apply(placed.footprint());
        int[] anchor = {placed.anchorX() - region.minX(), placed.anchorY() - region.minY(),
                placed.anchorZ() - region.minZ()};
        refusals.addAll(regionRefusals(placed, region, anchor));

        Optional<CaptureSurvey> survey = Optional.empty();
        if (region.blockCount() <= MAX_VOLUME && worldProbe.loaded(region.world())) {
            survey = worldProbe.survey(region);
            if (survey.isEmpty()) {
                refusals.add("Le contenu de la zone n'a pas pu être relevé : capture refusée plutôt "
                        + "que faite à l'aveugle.");
            } else if (!survey.get().complete()) {
                refusals.add(survey.get().incompleteReason());
            }
        } else if (!worldProbe.loaded(region.world())) {
            refusals.add("Le monde « " + region.world() + " » n'est pas chargé.");
        }
        survey.filter(CaptureSurvey::complete).ifPresent(s -> warnings.addAll(policyWarnings(s)));
        if (!request.margins().none()) {
            warnings.add("Bornes ajustées par rapport à l'emprise posée (" + request.margins().label()
                    + ") : vérifiez qu'aucune construction voisine n'entre dans la zone.");
        }
        otherSitesInside(request.siteId(), region).ifPresent(warnings::add);

        String token = source.isEmpty() ? "" : tokenFor(request, placed, region);
        return new Preview(request, site.get(), placed, source.orElse(null), region, anchor, front,
                survey, refusals, warnings, token);
    }

    private List<String> identityRefusals(Request request) {
        List<String> refusals = new ArrayList<>();
        if (!BuildingDefinition.ID_PATTERN.matcher(request.newId()).matches()) {
            refusals.add("Identifiant du nouveau bâtiment invalide : « " + request.newId()
                    + " » (minuscules, chiffres et « _ », 64 caractères au plus).");
        } else {
            library.find(request.newId()).ifPresent(existing -> refusals.add("L'identifiant « "
                    + request.newId() + " » est déjà pris par « " + existing.name()
                    + " » : une capture n'écrase jamais un bâtiment. Choisissez un autre identifiant."));
            if (library.find(request.newId()).isEmpty()
                    && Files.exists(library.directory().resolve(request.newId() + ".yml"))) {
                refusals.add("Un fichier buildings/" + request.newId() + ".yml existe déjà (peut-être "
                        + "invalide) : il n'est pas écrasé. Choisissez un autre identifiant.");
            }
            if (gateway.has(request.newId() + ".schem")) {
                refusals.add("Un fichier schematics/" + request.newId() + ".schem existe déjà : il "
                        + "n'est pas écrasé. Choisissez un autre identifiant.");
            }
        }
        if (request.name().isEmpty()) {
            refusals.add("Le nouveau bâtiment doit avoir un nom.");
        } else if (request.name().length() > MAX_NAME || request.name().contains("\n")) {
            refusals.add("Nom trop long ou sur plusieurs lignes (" + MAX_NAME + " caractères au plus).");
        }
        if (request.description().length() > MAX_DESCRIPTION) {
            refusals.add("Description trop longue : " + request.description().length() + " caractères pour "
                    + MAX_DESCRIPTION + " au plus.");
        }
        if (!request.category().isEmpty() && !CATEGORY.matcher(request.category()).matches()) {
            refusals.add("Catégorie invalide : « " + request.category()
                    + " » (minuscules, chiffres, « _ » ou « - », 32 caractères au plus).");
        }
        return refusals;
    }

    private List<String> regionRefusals(BuildingPlacement placed, BuildingFootprint region,
                                        int[] anchor) {
        List<String> refusals = new ArrayList<>();
        if (region.sizeX() > BuildingDefinition.MAX_SIZE || region.sizeY() > BuildingDefinition.MAX_SIZE
                || region.sizeZ() > BuildingDefinition.MAX_SIZE) {
            refusals.add("Zone trop grande : " + region.label() + " (" + BuildingDefinition.MAX_SIZE
                    + " blocs au plus par axe).");
        }
        if (region.blockCount() > MAX_VOLUME) {
            refusals.add("Volume trop grand : " + region.blockCount() + " blocs pour " + MAX_VOLUME
                    + " au plus — la copie gèlerait le serveur. Réduisez les bornes.");
        }
        if (anchor[0] < 0 || anchor[0] >= region.sizeX() || anchor[1] < 0 || anchor[1] >= region.sizeY()
                || anchor[2] < 0 || anchor[2] >= region.sizeZ()) {
            refusals.add("Les bornes réduites excluent l'ancre du bâtiment (" + placed.anchorX() + " / "
                    + placed.anchorY() + " / " + placed.anchorZ() + ") : la variante ne pourrait pas "
                    + "être reposée au même point de référence.");
        }
        OptionalInt minHeight = worldProbe.minHeight(region.world());
        OptionalInt maxHeight = worldProbe.maxHeight(region.world());
        if (minHeight.isPresent() && region.minY() < minHeight.getAsInt()
                || maxHeight.isPresent() && region.maxY() >= maxHeight.getAsInt()) {
            refusals.add("Les bornes sortent des limites de hauteur du monde.");
        }
        for (BuildingPlacement other : placements.all()) {
            if (!other.siteId().equals(placed.siteId()) && other.footprint().overlaps(region)) {
                refusals.add("La zone recouvre le bâtiment posé sur « " + other.siteId() + " » ("
                        + other.buildingId() + ", " + other.footprint().label() + ") : une capture "
                        + "n'inclut jamais un bâtiment voisin. Réduisez les bornes.");
            }
        }
        return refusals;
    }

    private Optional<String> otherSitesInside(String siteId, BuildingFootprint region) {
        List<String> inside = new ArrayList<>();
        for (BuildingSite other : sites.all()) {
            if (!other.id().equals(siteId) && region.contains(other.world(), other.x(), other.y(), other.z())) {
                inside.add(other.id());
            }
        }
        return inside.isEmpty() ? Optional.empty() : Optional.of("La zone contient le repère d'un autre "
                + "emplacement (" + String.join(", ", inside) + ") : ce qui s'y trouve serait capturé.");
    }

    /**
     * Seule la perte visible reste un avertissement : le reste du contenu est décrit, sans alarme,
     * dans la section « Blocs avec contenu » de l'aperçu ({@link CaptureContent#groups}).
     */
    private static List<String> policyWarnings(CaptureSurvey survey) {
        List<String> warnings = new ArrayList<>();
        if (survey.visualLosses() > 0) {
            warnings.add(survey.visualLosses() + " bloc(s) perdront les objets qu'ils affichent ("
                    + CaptureContent.blocksLabel(survey, CaptureSurvey.ContentFate.VISUAL_LOST)
                    + ") : la variante ne sera pas identique à l'œil. Retirez-les de la capture, ou "
                    + "acceptez explicitement cette perte avant de capturer.");
        }
        return warnings;
    }

    // ---- Capture ---------------------------------------------------------------------------------

    /**
     * Capture. Revérifie tout, compare le jeton, écrit le {@code .schem} puis la définition, recharge
     * la bibliothèque et n'annonce un succès que si elle porte réellement le nouveau bâtiment.
     *
     * <p>À appeler sur le thread principal : la copie lit le monde.</p>
     */
    public Result capture(Request request, String actor, String expectedToken) {
        String siteKey = "site:" + request.siteId();
        String idKey = "id:" + request.newId();
        if (!inFlight.add(siteKey)) {
            return Result.refused("Une capture est déjà en cours sur cet emplacement.");
        }
        if (!inFlight.add(idKey)) {
            inFlight.remove(siteKey);
            return Result.refused("Une capture vers l'identifiant « " + request.newId()
                    + " » est déjà en cours.");
        }
        try {
            return captureLocked(request, actor, expectedToken);
        } finally {
            inFlight.remove(idKey);
            inFlight.remove(siteKey);
        }
    }

    private Result captureLocked(Request request, String actor, String expectedToken) {
        Preview preview = preview(request);
        if (!preview.capturable()) {
            return Result.refused(preview.firstRefusal());
        }
        if (expectedToken == null || !expectedToken.equals(preview.token())) {
            return Result.refused("L'aperçu n'est plus à jour (le bâtiment posé, l'emplacement ou les "
                    + "bornes ont changé) : rien n'a été capturé. Recalculez l'aperçu.");
        }
        CaptureSurvey survey = preview.survey().orElseThrow();
        if (survey.visualLosses() > 0 && !request.acceptVisualLoss()) {
            return Result.refused(survey.visualLosses() + " bloc(s) perdraient les objets qu'ils "
                    + "affichent (" + CaptureContent.blocksLabel(survey, CaptureSurvey.ContentFate.VISUAL_LOST)
                    + ") : cochez l'acquittement dans l'aperçu pour capturer quand même. Rien n'a été "
                    + "capturé.");
        }
        BuildingFootprint region = preview.region();
        String schematic = request.newId() + ".schem";
        List<int[]> emptied = survey.containers().stream()
                .map(spot -> new int[] {spot.x(), spot.y(), spot.z()})
                .toList();

        SchematicGateway.Outcome written = gateway.captureForLibrary(region, schematic, emptied);
        if (!written.ok()) {
            record(preview, actor, false, written.error());
            return Result.refused("La capture a échoué, rien n'a été ajouté à la bibliothèque : "
                    + written.error());
        }

        int[] anchor = preview.localAnchor();
        BuildingDefinition definition = new BuildingDefinition(request.newId(), request.name(),
                request.description(), schematic, region.sizeX(), region.sizeY(), region.sizeZ(),
                anchor[0], anchor[1], anchor[2], preview.front(), survey.materials(), 1);
        Optional<String> invalid = definition.validate();
        if (invalid.isPresent()) {
            gateway.discard(schematic);
            return Result.refused("La définition dérivée est invalide : " + invalid.get()
                    + " Le fichier capturé a été retiré.");
        }
        Path yml = library.directory().resolve(request.newId() + ".yml");
        try {
            write(yml, definition, preview, actor);
        } catch (IOException | RuntimeException error) {
            gateway.discard(schematic);
            record(preview, actor, false, "définition non écrite : " + error.getClass().getSimpleName());
            return Result.refused("Le fichier capturé a été écrit mais la définition n'a pas pu l'être ("
                    + error.getClass().getSimpleName() + ") : le fichier a été retiré, la bibliothèque "
                    + "est inchangée.");
        }

        library.reload();
        if (library.find(request.newId()).isEmpty()) {
            return Result.refused("La définition a été écrite mais la bibliothèque ne la porte pas après "
                    + "rechargement — voir les erreurs de la bibliothèque (buildings/" + request.newId()
                    + ".yml).");
        }

        String detail = "Capturé comme « " + request.newId() + " » : " + region.label() + ", "
                + survey.nonAirBlocks() + " blocs non-air, " + survey.containers().size()
                + " bloc(s) à contenu sans leurs données (" + survey.count(CaptureSurvey.ContentFate.VISUAL_KEPT)
                + " gardant leurs livres visibles, " + survey.visualLosses() + " perdant leurs objets "
                + "affichés), " + survey.entityCount() + " entité(s) non capturée(s).";
        record(preview, actor, true, detail);
        logger.info("[building] " + preview.placement().siteId() + " capturé vers " + request.newId()
                + " (" + region.label() + ", par " + actor + ").");

        List<String> effects = new ArrayList<>();
        effects.add("schematics/" + schematic);
        effects.add("buildings/" + request.newId() + ".yml");
        effects.add("façade de référence : " + preview.front().name() + ", ancre locale "
                + definition.anchorLabel());
        CaptureContent.groups(survey).forEach(group -> effects.add(group.text()));
        String entities = CaptureContent.entityNote(survey);
        if (!entities.isEmpty()) {
            effects.add("Entités non incluses : " + entities);
        }
        effects.addAll(preview.warnings());
        return new Result(true, library.find(request.newId()).orElse(definition), "", effects);
    }

    private void write(Path yml, BuildingDefinition definition, Preview preview, String actor)
            throws IOException {
        if (Files.exists(yml)) {
            throw new IOException("fichier déjà présent");
        }
        Files.createDirectories(yml.getParent());
        BuildingPlacement placed = preview.placement();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", definition.id());
        yaml.set("name", definition.name());
        yaml.set("description", definition.description());
        if (!preview.request().category().isEmpty()) {
            yaml.set("category", preview.request().category());
        }
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
        yaml.set("provenance.kind", "capture");
        yaml.set("provenance.site", placed.siteId());
        yaml.set("provenance.building", placed.buildingId());
        yaml.set("provenance.building-version", placed.buildingVersion());
        yaml.set("provenance.rotation", placed.rotationDegrees());
        yaml.set("provenance.world", preview.region().world());
        yaml.set("provenance.region", preview.region().label());
        yaml.set("provenance.by", actor == null ? "" : actor);
        yaml.set("provenance.at", clock.instant().toString());
        yaml.set("provenance.containers-emptied",
                preview.survey().map(s -> s.containers().size()).orElse(0));
        yaml.set("provenance.books-kept-as-decor",
                preview.survey().map(s -> s.count(CaptureSurvey.ContentFate.VISUAL_KEPT)).orElse(0L));
        yaml.set("provenance.displayed-items-lost",
                preview.survey().map(CaptureSurvey::visualLosses).orElse(0L));
        yaml.set("provenance.entities-omitted",
                preview.survey().map(s -> CaptureContent.entitiesLabel(s.entities())).orElse(""));
        yaml.options().setHeader(List.of(
                "Bâtiment capturé depuis une construction posée (issue #246).",
                "Le schematic est une copie de l'état du monde : il n'existe pas de plan à régénérer.",
                "Blocs copiés avec leurs propriétés (orientation, livres visibles) ; contenu des",
                "blocs (objets, livres, textes) jamais copié ; entités (cadres…) non capturées."));
        Path temporary = yml.resolveSibling(yml.getFileName() + ".part");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temporary, yml, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private void record(Preview preview, String actor, boolean ok, String detail) {
        if (history == null) {
            return;
        }
        BuildingPlacement placed = preview.placement();
        Instant now = clock.instant();
        history.append(BuildingHistoryEntry.of(placed.siteId(), BuildingOperation.CAPTURE,
                placed.buildingId(), placed.buildingVersion(), placed.schematicSha256(),
                placed.rotationDegrees(), preview.region(), actor == null ? "" : actor, now, ok,
                detail, ""));
    }

    private static String tokenFor(Request request, BuildingPlacement placed, BuildingFootprint region) {
        String raw = request.siteId() + '|' + placed.buildingId() + '@' + placed.buildingVersion() + '|'
                + placed.rotationDegrees() + '|' + placed.placedAt() + '|' + region.world() + ':'
                + region.label() + '|' + request.newId() + '|' + request.name() + '|'
                + request.description() + '|' + request.category();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
