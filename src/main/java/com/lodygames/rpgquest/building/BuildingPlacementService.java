package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingBaseline;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingHistoryEntry;
import com.lodygames.rpgquest.building.model.BuildingOperation;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.database.BuildingBaselineRepository;
import com.lodygames.rpgquest.database.BuildingHistoryRepository;
import com.lodygames.rpgquest.database.BuildingPlacementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Affecter un bâtiment à un emplacement, l'y poser, et revenir en arrière (issue #213, lot
 * « placement »).
 *
 * <h2>Trois étapes, et une seule écrit dans le monde</h2>
 *
 * <ol>
 *   <li>{@link #preview(String, String)} — calcule rotation et emprise, énumère les refus et les
 *       avertissements. <strong>Ne touche rien</strong>, ni le monde, ni la base.</li>
 *   <li>{@link #place(String, String, String)} — refait toutes les vérifications, sauvegarde la
 *       zone, colle, puis enregistre. Dans cet ordre, qui est le seul sûr.</li>
 *   <li>{@link #rollback(String)} — restaure la sauvegarde et libère l'emplacement.</li>
 * </ol>
 *
 * <h2>Pourquoi l'aperçu ne suffit pas, et pourquoi on revérifie tout</h2>
 *
 * <p>Entre l'aperçu affiché à l'écran et le clic sur « Placer », le monde a pu changer : un autre
 * administrateur a pu poser un bâtiment chevauchant, décharger le monde, ou occuper l'emplacement.
 * Les vérifications sont donc <strong>rejouées intégralement</strong> au moment de poser. Un aperçu
 * n'est pas une réservation.</p>
 *
 * <h2>L'ordre des opérations est la garantie principale</h2>
 *
 * <p>Sauvegarder <em>avant</em> de coller, et n'enregistrer le placement qu'<em>après</em> un
 * collage réussi. Si le collage échoue, l'emplacement reste {@code EMPTY} et aucun placement n'est
 * inscrit : il n'y a pas de faux placement. Si l'enregistrement échoue après un collage réussi, le
 * refus le dit explicitement — mieux vaut un bâtiment posé sans fiche, qu'un administrateur peut
 * constater, qu'une fiche sans bâtiment.</p>
 *
 * <p>Aucun type WorldEdit n'apparaît ici : le moteur est derrière {@link SchematicGateway}, le monde
 * derrière {@link WorldProbe}. C'est ce qui rend ce service — donc l'essentiel du lot — exécutable
 * par des tests.</p>
 */
public final class BuildingPlacementService {

    private final BuildingPlacementRepository repository;
    private final BuildingSiteService sites;
    private final BuildingLibrary library;
    private final SchematicGateway gateway;
    private final WorldProbe worldProbe;
    private final Clock clock;
    /** Terrain d'origine par emplacement (issue #234). {@code null} tant que #234 n'est pas câblé. */
    private final BuildingBaselineRepository baselines;
    /** Journal des opérations (issue #234). {@code null} tant que #234 n'est pas câblé. */
    private final BuildingHistoryRepository history;

    private final Map<String, BuildingPlacement> placements = new ConcurrentHashMap<>();

    /**
     * Fragments de terrain d'origine, par emplacement, du plus ancien au plus récent.
     *
     * <p>En mémoire parce qu'ils sont consultés par chaque aperçu : l'écran doit pouvoir dire « le
     * terrain d'origine est connu » sans requête SQL. La base reste la source de vérité, et
     * {@link #load()} la relit au démarrage.</p>
     */
    private final Map<String, List<BuildingBaseline>> baselineCache = new ConcurrentHashMap<>();

    /**
     * Les emplacements dont une pose est en cours.
     *
     * <p>C'est l'anti-double-clic : coller et sauvegarder prennent du temps, et deux clics sur
     * « Placer » arriveraient tous les deux avant que le premier n'ait écrit sa ligne. Le second est
     * refusé immédiatement, sans toucher au monde.</p>
     */
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public BuildingPlacementService(BuildingPlacementRepository repository,
                                    BuildingSiteService sites,
                                    BuildingLibrary library,
                                    SchematicGateway gateway,
                                    WorldProbe worldProbe) {
        this(repository, sites, library, gateway, worldProbe, Clock.systemUTC(), null, null);
    }

    public BuildingPlacementService(BuildingPlacementRepository repository,
                                    BuildingSiteService sites,
                                    BuildingLibrary library,
                                    SchematicGateway gateway,
                                    WorldProbe worldProbe,
                                    Clock clock) {
        this(repository, sites, library, gateway, worldProbe, clock, null, null);
    }

    /**
     * Constructeur complet (issue #234) : avec le terrain d'origine et le journal des opérations.
     *
     * <p>Les deux repositories sont <strong>facultatifs</strong>, et c'est voulu : les tests du lot
     * « placement » de #213 construisent ce service sans eux, et doivent continuer de passer
     * inchangés. Sans {@code baselines}, le cycle de vie retombe sur la sauvegarde de la pose —
     * exactement ce qui existait avant, donc aucune régression.</p>
     */
    public BuildingPlacementService(BuildingPlacementRepository repository,
                                    BuildingSiteService sites,
                                    BuildingLibrary library,
                                    SchematicGateway gateway,
                                    WorldProbe worldProbe,
                                    Clock clock,
                                    BuildingBaselineRepository baselines,
                                    BuildingHistoryRepository history) {
        this.repository = repository;
        this.sites = sites;
        this.library = library;
        this.gateway = gateway;
        this.worldProbe = worldProbe;
        this.clock = clock;
        this.baselines = baselines;
        this.history = history;
    }

    /** Charge les placements et le terrain d'origine au démarrage. La base est la source de vérité. */
    public CompletableFuture<Integer> load() {
        CompletableFuture<Integer> loadPlacements = repository.loadAll().thenApply(loaded -> {
            placements.clear();
            for (BuildingPlacement placement : loaded) {
                placements.put(placement.siteId(), placement);
            }
            return placements.size();
        });
        if (baselines == null) {
            return loadPlacements;
        }
        return loadPlacements.thenCompose(count -> baselines.loadAll().thenApply(all -> {
            baselineCache.clear();
            for (BuildingBaseline baseline : all) {
                baselineCache.computeIfAbsent(baseline.siteId(), k -> new ArrayList<>())
                        .add(baseline);
            }
            // L'ordre vient de la base (ORDER BY site_id, id) : du plus ancien au plus récent.
            return count;
        }));
    }

    // ---- Terrain d'origine (issue #234) --------------------------------------------------------

    /** Les fragments de terrain d'origine d'un emplacement, du plus ancien au plus récent. */
    public List<BuildingBaseline> baselinesFor(String siteId) {
        List<BuildingBaseline> list = baselineCache.get(siteId);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Comment on pourrait rendre son terrain d'origine à cet emplacement.
     *
     * <p>Trois cas, et l'écran doit les distinguer parce qu'ils n'offrent pas la même garantie :</p>
     * <ul>
     *   <li><strong>baseline enregistrée</strong> — le terrain d'avant le <em>premier</em> bâtiment,
     *       quel que soit le nombre d'essais depuis ;</li>
     *   <li><strong>sauvegarde de la pose seulement</strong> — un placement antérieur à #234, dont
     *       la sauvegarde <em>est</em> le terrain d'origine puisque c'était la première et unique
     *       opération. Sûr, mais il faut le dire plutôt que de le confondre avec le cas précédent ;</li>
     *   <li><strong>rien</strong> — on refuse. Remettre de l'air détruirait le terrain.</li>
     * </ul>
     */
    public RestoreSource restoreSourceFor(String siteId) {
        List<BuildingBaseline> fragments = baselinesFor(siteId);
        if (!fragments.isEmpty()) {
            boolean allPresent = fragments.stream().allMatch(f -> gateway.has(f.schematic()));
            return new RestoreSource(RestoreSource.Kind.BASELINE, fragments, "", allPresent);
        }
        BuildingPlacement placement = placements.get(siteId);
        if (placement != null && placement.restorable()) {
            return new RestoreSource(RestoreSource.Kind.PLACEMENT_BACKUP, List.of(),
                    placement.backupSchematic(), gateway.has(placement.backupSchematic()));
        }
        return new RestoreSource(RestoreSource.Kind.NONE, List.of(), "", false);
    }

    /**
     * La rotation que le bâtiment posé <strong>aurait</strong> si on le reposait selon l'orientation
     * actuelle de l'emplacement, ou vide si elle n'est pas calculable.
     *
     * <p>Fonde l'affichage de divergence : un emplacement est une <em>intention</em>, un placement
     * est un <em>fait</em>, et changer l'intention ne déplace aucun bloc.</p>
     */
    public OptionalInt desiredRotation(String siteId) {
        BuildingPlacement placement = placements.get(siteId);
        Optional<BuildingSite> site = sites.find(siteId);
        if (placement == null || site.isEmpty()) {
            return OptionalInt.empty();
        }
        return library.find(placement.buildingId())
                .map(definition -> OptionalInt.of(definition.rotationFor(site.get().facing())))
                .orElse(OptionalInt.empty());
    }

    /**
     * Vrai si l'orientation souhaitée de l'emplacement ne correspond plus à celle du bâtiment posé.
     *
     * <p>Ce n'est pas une anomalie à corriger d'office : c'est un fait à <strong>afficher</strong>.
     * Tourner des blocs sans qu'on l'ait demandé serait une mutation implicite du monde.</p>
     */
    public boolean diverges(String siteId) {
        BuildingPlacement placement = placements.get(siteId);
        OptionalInt desired = desiredRotation(siteId);
        return placement != null && desired.isPresent()
                && desired.getAsInt() != placement.rotationDegrees();
    }

    public List<BuildingPlacement> all() {
        List<BuildingPlacement> list = new ArrayList<>(placements.values());
        list.sort(Comparator.comparing(BuildingPlacement::siteId));
        return list;
    }

    public Optional<BuildingPlacement> at(String siteId) {
        return siteId == null ? Optional.empty() : Optional.ofNullable(placements.get(siteId));
    }

    // ---- Aperçu ------------------------------------------------------------------------------

    /**
     * Calcule ce que donnerait la pose, sans rien modifier.
     *
     * <p>Renvoie <strong>tous</strong> les refus, pas seulement le premier : un administrateur qui
     * doit corriger trois choses préfère les voir ensemble.</p>
     */
    public Preview preview(String siteId, String buildingId) {
        Optional<BuildingSite> site = sites.find(siteId);
        if (site.isEmpty()) {
            return Preview.refused("Emplacement « " + siteId + " » inconnu.");
        }
        Optional<BuildingDefinition> definition = library.find(buildingId);
        if (definition.isEmpty()) {
            return Preview.refused("Bâtiment « " + buildingId + " » inconnu dans la bibliothèque.");
        }

        BuildingSite found = site.get();
        BuildingDefinition building = definition.get();
        int rotation = building.rotationFor(found.facing());
        BuildingFootprint footprint = building.footprintAt(found.world(),
                found.x(), found.y(), found.z(), rotation);

        List<String> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!gateway.available()) {
            refusals.add(gateway.unavailableReason());
        }
        at(siteId).ifPresent(existing -> refusals.add(
                "L'emplacement porte déjà « " + existing.buildingId() + " », posé le "
                        + existing.placedAt() + ". Retirez-le d'abord."));
        if (found.status() == SiteStatus.OCCUPIED && at(siteId).isEmpty()) {
            // État incohérent : marqué occupé sans placement enregistré. On le dit plutôt que de
            // poser par-dessus quelque chose dont on ne sait rien.
            refusals.add("L'emplacement est marqué occupé mais aucun placement n'est enregistré. "
                    + "Incohérence à examiner avant de poser.");
        }
        if (!building.valid()) {
            refusals.add(building.validate().orElse("Définition de bâtiment invalide."));
        }
        if (gateway.available() && !gateway.has(building.schematic())) {
            refusals.add("Le fichier « " + building.schematic() + " » est absent du dossier des "
                    + "schematics. Générez-le avant de poser.");
        }

        // Dimensions réelles du fichier confrontées à la définition : une divergence ferait annoncer
        // une emprise fausse, et la faute ne se verrait qu'après le collage.
        if (gateway.available() && gateway.has(building.schematic())) {
            Optional<SchematicGateway.Dimensions> actual = gateway.inspect(building.schematic());
            if (actual.isEmpty()) {
                refusals.add("Le fichier « " + building.schematic() + " » est illisible.");
            } else {
                SchematicGateway.Dimensions dimensions = actual.get();
                if (dimensions.sizeX() != building.sizeX()
                        || dimensions.sizeY() != building.sizeY()
                        || dimensions.sizeZ() != building.sizeZ()) {
                    refusals.add("Le fichier mesure " + dimensions.label()
                            + " alors que la définition annonce " + building.sizeLabel()
                            + ". Rien n'est posé tant que les deux ne concordent pas.");
                }
            }
        }

        if (!worldProbe.loaded(found.world())) {
            refusals.add("Le monde « " + found.world() + " » n'est pas chargé.");
        } else {
            OptionalInt min = worldProbe.minHeight(found.world());
            OptionalInt max = worldProbe.maxHeight(found.world());
            if (min.isPresent() && footprint.minY() < min.getAsInt()) {
                refusals.add("L'emprise descend sous le plancher du monde (" + footprint.minY()
                        + " < " + min.getAsInt() + ").");
            }
            if (max.isPresent() && footprint.maxY() >= max.getAsInt()) {
                refusals.add("L'emprise dépasse le plafond du monde (" + footprint.maxY()
                        + " ≥ " + max.getAsInt() + ").");
            }
        }

        // Chevauchement avec un bâtiment DÉJÀ POSÉ. On ne regarde pas les autres emplacements :
        // deux repères voisins sont permis (#213), c'est la matière posée qui ne peut pas se
        // superposer.
        for (BuildingPlacement other : all()) {
            if (!other.siteId().equals(siteId) && footprint.overlaps(other.footprint())) {
                refusals.add("L'emprise chevauche « " + other.buildingId() + " » posé sur "
                        + other.siteId() + " (" + other.footprint().label() + ").");
            }
        }

        OptionalLong nonAir = worldProbe.countNonAir(footprint);
        if (nonAir.isPresent() && nonAir.getAsLong() > 0) {
            long occupied = nonAir.getAsLong();
            long total = footprint.blockCount();
            // Un avertissement, jamais un refus : du terrain naturel à l'endroit où l'on veut bâtir
            // est la situation normale. Le seuil est haut pour ne pas crier à chaque fois.
            if (occupied * 2 >= total) {
                warnings.add("La zone contient déjà " + occupied + " blocs non-air sur " + total
                        + " : vérifiez qu'il ne s'agit pas d'une construction existante.");
            }
        }
        if (!gateway.available()) {
            warnings.add("Sans moteur de schematics, aucun retour arrière ne serait possible.");
        }

        return new Preview(found, building, rotation, footprint, refusals, warnings,
                nonAir.isPresent() ? nonAir.getAsLong() : -1L);
    }

    // ---- Pose --------------------------------------------------------------------------------

    /**
     * Pose le bâtiment. Toutes les vérifications de l'aperçu sont <strong>rejouées</strong>.
     *
     * <p>Ordre : vérifier → sauvegarder la zone → coller → enregistrer → marquer l'emplacement
     * occupé. Un échec à n'importe quelle étape laisse l'emplacement {@code EMPTY} et n'inscrit
     * aucun placement.</p>
     */
    public CompletableFuture<PlaceResult> place(String siteId, String buildingId, String placedBy) {
        Preview preview = preview(siteId, buildingId);
        if (!preview.placeable()) {
            return CompletableFuture.completedFuture(
                    PlaceResult.refused(preview.firstRefusal()));
        }
        // Anti-double-clic : le second clic n'attend pas le premier, il est refusé.
        if (inFlight.putIfAbsent(siteId, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(
                    PlaceResult.refused("Une pose est déjà en cours sur cet emplacement."));
        }

        BuildingDefinition building = preview.definition();
        BuildingFootprint footprint = preview.footprint();
        BuildingSite site = preview.site();
        Instant now = clock.instant();
        String backupName = backupNameFor(siteId, now);

        // La sauvegarde AVANT le collage : c'est elle qui rend le retour arrière possible. Si elle
        // échoue, on s'arrête là — poser sans pouvoir revenir en arrière n'est pas acceptable pour
        // une première validation.
        SchematicGateway.Outcome captured = gateway.capture(footprint, backupName);
        if (!captured.ok()) {
            inFlight.remove(siteId);
            return CompletableFuture.completedFuture(PlaceResult.refused(
                    "Sauvegarde de la zone impossible, donc rien n'a été posé : "
                            + captured.error()));
        }

        // Issue #234 : cette capture est AUSSI le terrain d'origine de l'emplacement, si aucun
        // fragment ne couvre encore cette emprise. Un seul fichier pour deux rôles, et c'est exact :
        // la zone est vierge à cet instant — soit l'emplacement n'a jamais rien porté, soit il vient
        // d'être libéré, ce qui a remis le terrain d'origine. Capturer deux fois le même état aurait
        // doublé le temps et l'espace pour rien.
        CompletableFuture<Void> baselineRecorded =
                baselines == null || baselineCovers(siteId, footprint)
                        ? CompletableFuture.completedFuture(null)
                        : baselines.insert(BuildingBaseline.of(siteId, backupName, footprint,
                                        placedBy, now))
                                .thenAccept(stored -> baselineCache
                                        .computeIfAbsent(siteId, k -> new ArrayList<>())
                                        .add(stored));

        SchematicGateway.Outcome pasted = gateway.paste(new SchematicGateway.PasteOrder(
                building.schematic(), site.world(),
                site.x(), site.y(), site.z(),
                building.anchorX(), building.anchorY(), building.anchorZ(),
                preview.rotationDegrees(), footprint));
        if (!pasted.ok()) {
            inFlight.remove(siteId);
            record(BuildingHistoryEntry.of(siteId, BuildingOperation.PLACE, building.id(),
                    building.version(), "", preview.rotationDegrees(), footprint, placedBy, now,
                    false, "Collage échoué : " + pasted.error(), backupName));
            return CompletableFuture.completedFuture(PlaceResult.refused(pasted.error()));
        }

        String sha = gateway.fingerprint(building.schematic()).orElse("");
        BuildingPlacement placement = BuildingPlacement.of(siteId, building, sha, footprint,
                site.x(), site.y(), site.z(), preview.rotationDegrees(), backupName, placedBy, now);

        return baselineRecorded
                .thenCompose(ignored -> repository.insert(placement))
                .thenCompose(ignored -> sites.markStatus(siteId, SiteStatus.OCCUPIED))
                .thenCompose(ignored -> record(BuildingHistoryEntry.of(siteId,
                        BuildingOperation.PLACE, building.id(), building.version(), sha,
                        preview.rotationDegrees(), footprint, placedBy, now, true,
                        "Bâtiment posé.", backupName)))
                .thenApply(ignored -> {
                    placements.put(siteId, placement);
                    inFlight.remove(siteId);
                    return new PlaceResult(true, placement, "");
                })
                .exceptionally(error -> {
                    inFlight.remove(siteId);
                    // Le bâtiment EST posé mais la fiche n'a pas pu s'écrire. On le dit : c'est
                    // constatable en jeu, et prétendre le contraire serait pire.
                    return PlaceResult.refused("Le bâtiment a été collé mais l'enregistrement a "
                            + "échoué (" + rootName(error) + "). La sauvegarde de la zone est « "
                            + backupName + " » : le retour arrière doit être fait à la main.");
                });
    }

    // ---- Retour arrière ----------------------------------------------------------------------

    /**
     * Restaure la zone telle qu'elle était avant la pose, puis libère l'emplacement.
     *
     * <p><strong>Sans sauvegarde, on refuse.</strong> Remettre de l'air dans l'emprise détruirait le
     * terrain d'origine — ce serait une destruction déguisée en annulation.</p>
     */
    public CompletableFuture<RollbackResult> rollback(String siteId) {
        BuildingPlacement placement = placements.get(siteId);
        if (placement == null) {
            return CompletableFuture.completedFuture(
                    RollbackResult.refused("Aucun bâtiment enregistré sur « " + siteId + " »."));
        }
        if (!gateway.available()) {
            return CompletableFuture.completedFuture(
                    RollbackResult.refused(gateway.unavailableReason()));
        }
        if (!placement.restorable()) {
            return CompletableFuture.completedFuture(RollbackResult.refused(
                    "Aucune sauvegarde n'est associée à ce placement : le retour arrière est "
                            + "refusé. Remettre de l'air dans l'emprise détruirait le terrain "
                            + "d'origine."));
        }
        if (!gateway.has(placement.backupSchematic())) {
            return CompletableFuture.completedFuture(RollbackResult.refused(
                    "Le fichier de sauvegarde « " + placement.backupSchematic()
                            + " » est introuvable : le retour arrière est refusé."));
        }
        if (inFlight.putIfAbsent(siteId, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(
                    RollbackResult.refused("Une opération est déjà en cours sur cet emplacement."));
        }

        SchematicGateway.Outcome restored =
                gateway.restore(placement.backupSchematic(), placement.footprint());
        if (!restored.ok()) {
            inFlight.remove(siteId);
            return CompletableFuture.completedFuture(RollbackResult.refused(restored.error()));
        }
        return repository.delete(siteId)
                .thenCompose(ignored -> sites.markStatus(siteId, SiteStatus.EMPTY))
                .thenApply(ignored -> {
                    placements.remove(siteId);
                    inFlight.remove(siteId);
                    return new RollbackResult(true, placement, "");
                })
                .exceptionally(error -> {
                    inFlight.remove(siteId);
                    return RollbackResult.refused("La zone a été restaurée mais la fiche n'a pas "
                            + "pu être mise à jour (" + rootName(error) + ").");
                });
    }

    // ---- Libérer l'emplacement (issue #234, priorité 1) ----------------------------------------

    /**
     * Ce que donnerait une libération, <strong>sans rien modifier</strong>.
     *
     * <p>Énumère tous les refus, et dit <em>d'où</em> viendrait le terrain : une baseline
     * enregistrée et la sauvegarde d'une pose antérieure à #234 n'offrent pas la même garantie, et
     * l'écran doit pouvoir le dire.</p>
     */
    public FreePreview previewFree(String siteId) {
        Optional<BuildingSite> site = sites.find(siteId);
        if (site.isEmpty()) {
            return FreePreview.refused("Emplacement « " + siteId + " » inconnu.");
        }
        BuildingPlacement placement = placements.get(siteId);
        if (placement == null) {
            return FreePreview.refused("Aucun bâtiment enregistré sur « " + siteId + " ».");
        }
        RestoreSource source = restoreSourceFor(siteId);
        List<String> refusals = new ArrayList<>();
        if (!gateway.available()) {
            refusals.add(gateway.unavailableReason());
        }
        if (source.kind() == RestoreSource.Kind.NONE) {
            refusals.add("Aucune sauvegarde du terrain n'est associée à cet emplacement : la "
                    + "libération est refusée. Remettre de l'air dans l'emprise détruirait le "
                    + "terrain d'origine.");
        } else if (!source.filesReady()) {
            refusals.add("Une des sauvegardes du terrain est introuvable (" + source.label()
                    + ") : la libération est refusée plutôt que faite à moitié.");
        }
        if (!worldProbe.loaded(placement.world())) {
            refusals.add("Le monde « " + placement.world() + " » n'est pas chargé.");
        }
        return new FreePreview(site.get(), placement, source, refusals,
                tokenFor(site.get(), placement, null, 0));
    }

    /**
     * Rend son terrain d'origine à l'emplacement, puis le libère.
     *
     * <p>Ordre non négociable : <strong>restaurer d'abord, libérer ensuite</strong>. Si la
     * restauration échoue, l'emplacement reste {@code OCCUPIED} et le placement actif est conservé —
     * il n'y a jamais de faux {@code EMPTY}. L'inverse laisserait un emplacement annoncé libre avec
     * un bâtiment encore debout dessus, c'est-à-dire exactement l'impasse que ce ticket corrige.</p>
     *
     * <p><strong>La baseline n'est pas supprimée</strong> : l'emplacement doit pouvoir être rebâti
     * puis libéré à nouveau, et retrouver le <em>même</em> terrain d'origine. C'est le but de toute
     * la chaîne d'expérimentation.</p>
     *
     * <p>Idempotent : un second appel ne trouve plus de placement et renvoie un refus explicite,
     * sans toucher au monde.</p>
     */
    public CompletableFuture<FreeResult> free(String siteId, String actor) {
        FreePreview preview = previewFree(siteId);
        if (!preview.freeable()) {
            return CompletableFuture.completedFuture(FreeResult.refused(preview.firstRefusal()));
        }
        if (inFlight.putIfAbsent(siteId, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(
                    FreeResult.refused("Une opération est déjà en cours sur cet emplacement."));
        }

        BuildingPlacement placement = preview.placement();
        Instant now = clock.instant();
        Optional<String> failure = restoreAll(preview.source(), placement.footprint());
        if (failure.isPresent()) {
            inFlight.remove(siteId);
            record(BuildingHistoryEntry.of(siteId, BuildingOperation.RESTORE,
                    placement.buildingId(), placement.buildingVersion(),
                    placement.schematicSha256(), placement.rotationDegrees(),
                    placement.footprint(), actor, now, false, failure.get(),
                    placement.backupSchematic()));
            return CompletableFuture.completedFuture(FreeResult.refused(
                    "Restauration du terrain impossible, l'emplacement reste occupé : "
                            + failure.get()));
        }

        return repository.delete(siteId)
                .thenCompose(ignored -> sites.markStatus(siteId, SiteStatus.EMPTY))
                .thenCompose(ignored -> record(BuildingHistoryEntry.of(siteId,
                        BuildingOperation.RESTORE, placement.buildingId(),
                        placement.buildingVersion(), placement.schematicSha256(),
                        placement.rotationDegrees(), placement.footprint(), actor, now, true,
                        "Terrain restauré depuis " + preview.source().label(),
                        placement.backupSchematic())))
                .thenApply(ignored -> {
                    placements.remove(siteId);
                    inFlight.remove(siteId);
                    return new FreeResult(true, placement, "");
                })
                .exceptionally(error -> {
                    inFlight.remove(siteId);
                    return FreeResult.refused("Le terrain a été restauré mais la fiche n'a pas pu "
                            + "être mise à jour (" + rootName(error) + ").");
                });
    }

    // ---- Réorienter / remplacer (issue #234) ---------------------------------------------------

    /**
     * Ce que donnerait une réorientation, sans rien modifier.
     *
     * <p>La rotation cible est <strong>recalculée depuis la définition</strong> et l'orientation
     * demandée — jamais obtenue en tournant les blocs déjà posés. C'est la stratégie déterministe
     * que le ticket demande : terrain d'origine + définition + rotation → nouveau placement.</p>
     */
    public RetargetPreview previewReorient(String siteId, Facing target) {
        BuildingPlacement placement = placements.get(siteId);
        if (placement == null) {
            return RetargetPreview.refused(BuildingOperation.ROTATE,
                    "Aucun bâtiment enregistré sur « " + siteId + " ».");
        }
        return previewRetarget(siteId, placement.buildingId(), target, BuildingOperation.ROTATE);
    }

    /** Ce que donnerait un remplacement par un autre bâtiment, sans rien modifier. */
    public RetargetPreview previewReplace(String siteId, String newBuildingId, Facing target) {
        return previewRetarget(siteId, newBuildingId, target, BuildingOperation.REPLACE);
    }

    /**
     * Le calcul commun à la réorientation et au remplacement.
     *
     * <p>Les deux gestes exécutent la <strong>même séquence dangereuse</strong> — retirer puis
     * coller. La partager est donc une exigence de sûreté, pas une économie de lignes : deux copies
     * divergeraient, et c'est la copie la moins soignée qui détruirait un terrain.</p>
     */
    private RetargetPreview previewRetarget(String siteId, String buildingId, Facing target,
                                            BuildingOperation operation) {
        Optional<BuildingSite> siteOpt = sites.find(siteId);
        if (siteOpt.isEmpty()) {
            return RetargetPreview.refused(operation, "Emplacement « " + siteId + " » inconnu.");
        }
        BuildingPlacement current = placements.get(siteId);
        if (current == null) {
            return RetargetPreview.refused(operation,
                    "Aucun bâtiment enregistré sur « " + siteId + " » : rien à transformer.");
        }
        Optional<BuildingDefinition> definitionOpt = library.find(buildingId);
        if (definitionOpt.isEmpty()) {
            return RetargetPreview.refused(operation,
                    "Bâtiment « " + buildingId + " » inconnu dans la bibliothèque.");
        }
        if (target == null) {
            return RetargetPreview.refused(operation, "Orientation cible manquante.");
        }

        BuildingSite site = siteOpt.get();
        BuildingDefinition definition = definitionOpt.get();
        int rotation = definition.rotationFor(target);
        BuildingFootprint currentFootprint = current.footprint();
        BuildingFootprint targetFootprint = definition.footprintAt(site.world(),
                current.anchorX(), current.anchorY(), current.anchorZ(), rotation);

        List<String> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        RestoreSource source = restoreSourceFor(siteId);

        if (!gateway.available()) {
            refusals.add(gateway.unavailableReason());
        }
        if (!definition.valid()) {
            refusals.add(definition.validate().orElse("Définition de bâtiment invalide."));
        }
        if (gateway.available() && !gateway.has(definition.schematic())) {
            refusals.add("Le fichier « " + definition.schematic() + " » est absent du dossier des "
                    + "schematics. Générez-le avant de transformer.");
        }
        // Sans terrain d'origine, retirer l'ancien bâtiment laisserait un trou : on refuse.
        if (source.kind() == RestoreSource.Kind.NONE) {
            refusals.add("Aucune sauvegarde du terrain n'est associée à cet emplacement : retirer "
                    + "le bâtiment actuel laisserait un trou. Opération refusée.");
        } else if (!source.filesReady()) {
            refusals.add("Une des sauvegardes du terrain est introuvable (" + source.label()
                    + ") : opération refusée.");
        }
        if (operation == BuildingOperation.ROTATE
                && rotation == current.rotationDegrees()
                && definition.id().equals(current.buildingId())) {
            refusals.add("Le bâtiment est déjà orienté ainsi (" + rotation + "°) : il n'y a rien à "
                    + "faire, et tourner pour rien réécrirait des blocs sans raison.");
        }

        if (!worldProbe.loaded(site.world())) {
            refusals.add("Le monde « " + site.world() + " » n'est pas chargé.");
        } else {
            OptionalInt min = worldProbe.minHeight(site.world());
            OptionalInt max = worldProbe.maxHeight(site.world());
            if (min.isPresent() && targetFootprint.minY() < min.getAsInt()) {
                refusals.add("La nouvelle emprise descend sous le plancher du monde ("
                        + targetFootprint.minY() + " < " + min.getAsInt() + ").");
            }
            if (max.isPresent() && targetFootprint.maxY() >= max.getAsInt()) {
                refusals.add("La nouvelle emprise dépasse le plafond du monde ("
                        + targetFootprint.maxY() + " ≥ " + max.getAsInt() + ").");
            }
        }

        // Chevauchement avec un AUTRE bâtiment posé. Le bâtiment courant est exclu : c'est
        // justement lui qu'on retire.
        for (BuildingPlacement other : all()) {
            if (!other.siteId().equals(siteId) && targetFootprint.overlaps(other.footprint())) {
                refusals.add("La nouvelle emprise chevauche « " + other.buildingId() + " » posé sur "
                        + other.siteId() + " (" + other.footprint().label() + ").");
            }
        }

        long nonAir = -1L;
        if (!targetFootprint.equals(currentFootprint)) {
            OptionalLong counted = worldProbe.countNonAir(targetFootprint);
            if (counted.isPresent()) {
                nonAir = counted.getAsLong();
            }
        }
        if (!currentFootprint.covers(targetFootprint)) {
            warnings.add("La nouvelle emprise sort de l'ancienne : du terrain jamais touché sera "
                    + "écrasé, et son état d'origine est sauvegardé au passage.");
        }
        if (source.kind() == RestoreSource.Kind.PLACEMENT_BACKUP) {
            warnings.add("Aucune baseline n'est enregistrée pour cet emplacement (pose antérieure "
                    + "au suivi du terrain d'origine) : c'est la sauvegarde de la pose qui sera "
                    + "utilisée. Elle est fiable ici, puisque c'était la seule opération.");
        }

        return new RetargetPreview(operation, site, current, definition, rotation,
                currentFootprint, targetFootprint, source, refusals, warnings, nonAir,
                tokenFor(site, current, definition, rotation));
    }

    /** Réoriente le bâtiment posé. Voir {@link #retarget} pour la séquence et ses garanties. */
    public CompletableFuture<RetargetResult> reorient(String siteId, Facing target, String actor,
                                                       String expectedToken) {
        return retarget(previewReorient(siteId, target), actor, expectedToken);
    }

    /** Remplace le bâtiment posé par un autre. Voir {@link #retarget}. */
    public CompletableFuture<RetargetResult> replace(String siteId, String newBuildingId,
                                                      Facing target, String actor,
                                                      String expectedToken) {
        return retarget(previewReplace(siteId, newBuildingId, target), actor, expectedToken);
    }

    /**
     * La séquence commune, et c'est elle qui porte toutes les garanties du ticket.
     *
     * <ol>
     *   <li><strong>revérifier</strong>, puis comparer l'empreinte de l'aperçu : si l'emplacement,
     *       le bâtiment posé ou la définition visée ont changé depuis l'affichage, on refuse. Un
     *       aperçu n'est pas une réservation ;</li>
     *   <li><strong>sauvegarder l'union</strong> des deux emprises — c'est la compensation. La
     *       sauvegarder avant toute mutation est ce qui rend l'échec rattrapable ;</li>
     *   <li><strong>restaurer le terrain d'origine</strong> : l'ancien bâtiment disparaît, et la
     *       zone redevient vierge. C'est aussi ce qui rend la capture suivante honnête ;</li>
     *   <li><strong>capturer la baseline de la nouvelle emprise</strong> si elle n'est pas déjà
     *       couverte — elle contient bien du terrain d'origine, puisque l'étape 3 vient de le
     *       remettre ;</li>
     *   <li><strong>coller</strong> le nouveau bâtiment ;</li>
     *   <li>en cas d'échec du collage, <strong>remettre la compensation</strong> : le monde revient à
     *       l'ancien bâtiment, et l'opération n'a simplement pas eu lieu ;</li>
     *   <li>n'écrire la fiche qu'<strong>après</strong> un collage réussi.</li>
     * </ol>
     *
     * <p>Ce qui est structurellement impossible avec cet ordre : un emplacement annoncé transformé
     * avec un monde vide, ou une fiche qui dit « succès » alors que rien n'est posé.</p>
     */
    private CompletableFuture<RetargetResult> retarget(RetargetPreview preview, String actor,
                                                        String expectedToken) {
        if (!preview.applicable()) {
            return CompletableFuture.completedFuture(
                    RetargetResult.refused(preview.firstRefusal()));
        }
        if (expectedToken != null && !expectedToken.isBlank()
                && !expectedToken.equals(preview.token())) {
            return CompletableFuture.completedFuture(RetargetResult.refused(
                    "L'emplacement, le bâtiment posé ou la définition visée ont changé depuis "
                            + "l'aperçu. Relancez l'aperçu avant de confirmer."));
        }

        String siteId = preview.site().id();
        if (inFlight.putIfAbsent(siteId, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(
                    RetargetResult.refused("Une opération est déjà en cours sur cet emplacement."));
        }

        BuildingPlacement current = preview.current();
        BuildingDefinition target = preview.target();
        BuildingFootprint targetFootprint = preview.targetFootprint();
        BuildingFootprint compensation = preview.compensationFootprint();
        Instant now = clock.instant();
        String compensationName = compensationNameFor(siteId, now);

        // (2) Compensation AVANT toute mutation.
        SchematicGateway.Outcome saved = gateway.capture(compensation, compensationName);
        if (!saved.ok()) {
            inFlight.remove(siteId);
            String why = "Sauvegarde de compensation impossible, donc rien n'a été touché : "
                    + saved.error();
            record(historyFor(preview, actor, now, false, why, compensationName));
            return CompletableFuture.completedFuture(RetargetResult.refused(why));
        }

        // (3) Terrain d'origine remis : l'ancien bâtiment disparaît.
        Optional<String> restoreFailure = restoreAll(preview.source(), current.footprint());
        if (restoreFailure.isPresent()) {
            // Rien de mieux à faire que de remettre la compensation : la restauration a pu être
            // partielle.
            SchematicGateway.Outcome back = gateway.restore(compensationName, compensation);
            inFlight.remove(siteId);
            String why = "Impossible de retirer le bâtiment actuel (" + restoreFailure.get() + ")"
                    + (back.ok() ? " — état précédent remis en place." : " ET la compensation a "
                            + "elle aussi échoué (" + back.error() + ") : état à vérifier en jeu.");
            record(historyFor(preview, actor, now, false, why, compensationName));
            return CompletableFuture.completedFuture(back.ok()
                    ? RetargetResult.compensated(why) : RetargetResult.refused(why));
        }

        // (4) Baseline de la nouvelle emprise, si elle n'est pas déjà couverte.
        CompletableFuture<Void> baselineReady = ensureBaseline(siteId, targetFootprint, actor, now);

        return baselineReady.thenCompose(ignored -> {
            // (5) Collage.
            SchematicGateway.Outcome pasted = gateway.paste(new SchematicGateway.PasteOrder(
                    target.schematic(), preview.site().world(),
                    current.anchorX(), current.anchorY(), current.anchorZ(),
                    target.anchorX(), target.anchorY(), target.anchorZ(),
                    preview.targetRotation(), targetFootprint));
            if (!pasted.ok()) {
                // (6) Compensation : on remet l'ancien bâtiment.
                SchematicGateway.Outcome back = gateway.restore(compensationName, compensation);
                inFlight.remove(siteId);
                String why = "Le collage a échoué (" + pasted.error() + ")"
                        + (back.ok() ? " — l'ancien bâtiment a été remis en place, l'opération n'a "
                                + "pas eu lieu." : " ET la compensation a échoué (" + back.error()
                                + ") : état à vérifier en jeu.");
                record(historyFor(preview, actor, now, false, why, compensationName));
                return CompletableFuture.completedFuture(back.ok()
                        ? RetargetResult.compensated(why) : RetargetResult.refused(why));
            }

            String sha = gateway.fingerprint(target.schematic()).orElse("");
            BuildingPlacement updated = BuildingPlacement.of(siteId, target, sha, targetFootprint,
                    current.anchorX(), current.anchorY(), current.anchorZ(),
                    preview.targetRotation(), compensationName, actor, now);

            // (7) La fiche seulement maintenant. L'emplacement reste OCCUPIED : il l'était déjà.
            return repository.delete(siteId)
                    .thenCompose(x -> repository.insert(updated))
                    .thenCompose(x -> record(historyFor(preview, actor, now, true,
                            preview.operation().label() + " appliquée.", compensationName)))
                    .thenApply(x -> {
                        placements.put(siteId, updated);
                        inFlight.remove(siteId);
                        return new RetargetResult(true, updated, "", false);
                    })
                    .exceptionally(error -> {
                        inFlight.remove(siteId);
                        return RetargetResult.refused("Le bâtiment a été collé mais la fiche n'a "
                                + "pas pu être mise à jour (" + rootName(error) + "). La "
                                + "sauvegarde de compensation est « " + compensationName + " ».");
                    });
        });
    }

    // ---- Rouages internes du cycle de vie ------------------------------------------------------

    /**
     * Restaure toutes les sauvegardes d'une source, dans l'ordre, et renvoie la première erreur.
     *
     * <p>L'ordre va du plus ancien au plus récent. Il n'a pas d'importance <em>logique</em> — chaque
     * fragment contient du terrain d'origine, par construction — mais le fixer rend deux
     * restaurations successives identiques bloc pour bloc, ce qui est la base de tout diagnostic.</p>
     */
    private Optional<String> restoreAll(RestoreSource source, BuildingFootprint fallback) {
        if (source.kind() == RestoreSource.Kind.PLACEMENT_BACKUP) {
            SchematicGateway.Outcome outcome = gateway.restore(source.backupName(), fallback);
            return outcome.ok() ? Optional.empty() : Optional.of(outcome.error());
        }
        for (BuildingBaseline fragment : source.fragments()) {
            SchematicGateway.Outcome outcome =
                    gateway.restore(fragment.schematic(), fragment.footprint());
            if (!outcome.ok()) {
                return Optional.of("fragment « " + fragment.schematic() + " » : " + outcome.error());
            }
        }
        return Optional.empty();
    }

    /** Vrai si un fragment de baseline couvre déjà entièrement cette emprise. */
    private boolean baselineCovers(String siteId, BuildingFootprint footprint) {
        return baselinesFor(siteId).stream().anyMatch(fragment -> fragment.covers(footprint));
    }

    /**
     * Capture et enregistre le terrain d'origine de cette emprise si ce n'est pas déjà fait.
     *
     * <p>À n'appeler que lorsque la zone est <strong>vierge</strong> : c'est la responsabilité de
     * l'appelant, et l'ordre de {@link #retarget} la garantit. Capturer une zone portant déjà un
     * bâtiment enregistrerait ce bâtiment comme « terrain d'origine », ce qui ruinerait toute la
     * chaîne d'expérimentation sans qu'aucune erreur ne se manifeste.</p>
     */
    private CompletableFuture<Void> ensureBaseline(String siteId, BuildingFootprint footprint,
                                                    String actor, Instant now) {
        if (baselines == null || baselineCovers(siteId, footprint)) {
            return CompletableFuture.completedFuture(null);
        }
        String name = baselineNameFor(siteId, now, baselinesFor(siteId).size());
        SchematicGateway.Outcome captured = gateway.capture(footprint, name);
        if (!captured.ok()) {
            // On ne fait pas échouer l'opération : le monde n'est pas encore touché par le collage,
            // et la compensation couvre déjà le retour en arrière immédiat. Mais on le DIT dans le
            // journal, parce que le terrain d'origine de cette nouvelle zone sera inconnu.
            record(BuildingHistoryEntry.of(siteId, BuildingOperation.RESTORE, "", 0, "", 0,
                    footprint, actor, now, false,
                    "Capture du terrain d'origine impossible : " + captured.error(), ""));
            return CompletableFuture.completedFuture(null);
        }
        BuildingBaseline fragment = BuildingBaseline.of(siteId, name, footprint, actor, now);
        return baselines.insert(fragment).thenAccept(stored ->
                baselineCache.computeIfAbsent(siteId, k -> new ArrayList<>()).add(stored));
    }

    /** Ajoute une ligne au journal, si le journal est câblé. Jamais bloquant pour l'opération. */
    private CompletableFuture<Void> record(BuildingHistoryEntry entry) {
        if (history == null) {
            return CompletableFuture.completedFuture(null);
        }
        return history.append(entry).exceptionally(error -> null);
    }

    private BuildingHistoryEntry historyFor(RetargetPreview preview, String actor, Instant now,
                                             boolean ok, String detail, String backup) {
        return BuildingHistoryEntry.of(preview.site().id(), preview.operation(),
                preview.target().id(), preview.target().version(),
                gateway.fingerprint(preview.target().schematic()).orElse(""),
                preview.targetRotation(), preview.targetFootprint(), actor, now, ok, detail,
                backup);
    }

    /**
     * Empreinte de tout ce dont dépend une opération (issue #234, §17).
     *
     * <p>Elle couvre l'emplacement (position et orientation souhaitée), le bâtiment actuellement
     * posé (identité, rotation, empreinte du fichier collé) et la cible visée (identité, version,
     * empreinte du fichier). Si l'un de ces éléments change entre l'aperçu et la confirmation,
     * l'empreinte change et l'opération est refusée — plutôt que d'appliquer une décision prise sur
     * des informations périmées.</p>
     */
    private String tokenFor(BuildingSite site, BuildingPlacement current,
                            BuildingDefinition target, int rotation) {
        StringBuilder raw = new StringBuilder()
                .append(site.id()).append('|')
                .append(site.world()).append('|')
                .append(site.x()).append('/').append(site.y()).append('/').append(site.z())
                .append('|').append(site.facing().name()).append('|')
                .append(site.status().name()).append('|');
        if (current == null) {
            raw.append("vide|");
        } else {
            raw.append(current.buildingId()).append('@').append(current.rotationDegrees())
                    .append('#').append(current.schematicSha256()).append('|');
        }
        if (target == null) {
            raw.append("aucune");
        } else {
            raw.append(target.id()).append('v').append(target.version())
                    .append('#').append(gateway.fingerprint(target.schematic()).orElse(""))
                    .append('@').append(rotation);
        }
        return sha256Short(raw.toString());
    }

    private static String sha256Short(String raw) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 est garanti par la plateforme ; si jamais il manquait, un jeton vide désactive
            // la vérification au lieu de bloquer toute opération.
            return "";
        }
    }

    /** Nom de la sauvegarde de compensation d'une transformation. Déterministe. */
    static String compensationNameFor(String siteId, Instant when) {
        String clean = siteId == null ? "inconnu" : siteId.replaceAll("[^a-z0-9_]", "_");
        return "compens_" + clean + "_" + when.toEpochMilli() + ".schem";
    }

    /** Nom d'un fragment de terrain d'origine. L'index évite toute collision à la même milliseconde. */
    static String baselineNameFor(String siteId, Instant when, int index) {
        String clean = siteId == null ? "inconnu" : siteId.replaceAll("[^a-z0-9_]", "_");
        return "origine_" + clean + "_" + when.toEpochMilli() + "_" + index + ".schem";
    }

    /** Nom du fichier de sauvegarde : déterministe, en minuscules, sans séparateur de chemin. */
    static String backupNameFor(String siteId, Instant when) {
        String clean = siteId == null ? "inconnu" : siteId.replaceAll("[^a-z0-9_]", "_");
        return "backup_" + clean + "_" + when.toEpochMilli() + ".schem";
    }

    private static String rootName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    // ---- Résultats ---------------------------------------------------------------------------

    /**
     * Ce que donnerait la pose. {@code nonAirBlocks} vaut {@code -1} quand le comptage n'a pas pu
     * être fait (monde déchargé), ce qui n'est pas la même chose que zéro.
     */
    public record Preview(BuildingSite site,
                          BuildingDefinition definition,
                          int rotationDegrees,
                          BuildingFootprint footprint,
                          List<String> refusals,
                          List<String> warnings,
                          long nonAirBlocks) {

        public Preview {
            refusals = List.copyOf(refusals == null ? List.of() : refusals);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        static Preview refused(String reason) {
            return new Preview(null, null, 0, null, List.of(reason), List.of(), -1L);
        }

        public boolean placeable() {
            return refusals.isEmpty() && site != null && definition != null;
        }

        public String firstRefusal() {
            return refusals.isEmpty() ? "" : refusals.get(0);
        }

        /** L'orientation que regardera la façade — c'est-à-dire celle de l'emplacement. */
        public String rotationLabel() {
            return rotationDegrees + "°" + (site == null ? "" : " / " + site.facing().label());
        }
    }

    public record PlaceResult(boolean placed, BuildingPlacement placement, String error) {

        static PlaceResult refused(String error) {
            return new PlaceResult(false, null, error);
        }
    }

    public record RollbackResult(boolean restored, BuildingPlacement placement, String error) {

        static RollbackResult refused(String error) {
            return new RollbackResult(false, null, error);
        }
    }

    // ---- Types du cycle de vie (issue #234) ----------------------------------------------------

    /**
     * D'où viendrait le terrain si on libérait l'emplacement maintenant.
     *
     * @param fragments  les fragments de baseline, du plus ancien au plus récent ; vide hors du cas
     *                   {@link Kind#BASELINE}
     * @param backupName la sauvegarde de la pose, hors du cas {@link Kind#PLACEMENT_BACKUP} vide
     * @param filesReady vrai si <strong>tous</strong> les fichiers nécessaires sont présents. Faux
     *                   signifie qu'on refusera : une restauration partielle rendrait un terrain
     *                   « presque d'origine », ce qui est pire qu'un refus parce que ça en a l'air
     */
    public record RestoreSource(Kind kind, List<BuildingBaseline> fragments, String backupName,
                                boolean filesReady) {

        public enum Kind {
            /** Terrain d'avant le PREMIER bâtiment, conservé explicitement. */
            BASELINE,
            /**
             * Sauvegarde de la pose, pour un placement antérieur à #234 : elle <em>est</em> le
             * terrain d'origine, puisque c'était la première et unique opération.
             */
            PLACEMENT_BACKUP,
            /** Rien de restaurable — la libération sera refusée. */
            NONE
        }

        public RestoreSource {
            fragments = List.copyOf(fragments == null ? List.of() : fragments);
            backupName = backupName == null ? "" : backupName;
        }

        public boolean restorable() {
            return kind != Kind.NONE && filesReady;
        }

        public String label() {
            return switch (kind) {
                case BASELINE -> fragments.size() == 1
                        ? "terrain d'origine (1 sauvegarde)"
                        : "terrain d'origine (" + fragments.size() + " sauvegardes)";
                case PLACEMENT_BACKUP -> "sauvegarde de la pose (aucune baseline enregistrée)";
                case NONE -> "aucune sauvegarde";
            };
        }

        /** Les fichiers à restaurer, dans l'ordre d'application. */
        public List<String> files() {
            if (kind == Kind.PLACEMENT_BACKUP) {
                return List.of(backupName);
            }
            return fragments.stream().map(BuildingBaseline::schematic).toList();
        }
    }

    /** Ce que donnerait une libération, sans rien modifier. */
    public record FreePreview(BuildingSite site, BuildingPlacement placement,
                              RestoreSource source, List<String> refusals, String token) {

        public FreePreview {
            refusals = List.copyOf(refusals == null ? List.of() : refusals);
        }

        static FreePreview refused(String reason) {
            return new FreePreview(null, null,
                    new RestoreSource(RestoreSource.Kind.NONE, List.of(), "", false),
                    List.of(reason), "");
        }

        public boolean freeable() {
            return refusals.isEmpty() && placement != null && source.restorable();
        }

        public String firstRefusal() {
            return refusals.isEmpty() ? "" : refusals.get(0);
        }
    }

    public record FreeResult(boolean freed, BuildingPlacement previous, String error) {

        static FreeResult refused(String error) {
            return new FreeResult(false, null, error);
        }
    }

    /**
     * Ce que donnerait une réorientation ou un remplacement, sans rien modifier.
     *
     * @param token empreinte de tout ce dont dépend l'opération (issue #234, §17). Elle voyage avec
     *              le formulaire et est revérifiée à la confirmation : si l'emplacement, le bâtiment
     *              posé ou la définition visée ont changé entre-temps, l'opération est refusée au
     *              lieu d'appliquer un aperçu périmé
     */
    public record RetargetPreview(BuildingOperation operation,
                                  BuildingSite site,
                                  BuildingPlacement current,
                                  BuildingDefinition target,
                                  int targetRotation,
                                  BuildingFootprint currentFootprint,
                                  BuildingFootprint targetFootprint,
                                  RestoreSource source,
                                  List<String> refusals,
                                  List<String> warnings,
                                  long targetNonAir,
                                  String token) {

        public RetargetPreview {
            refusals = List.copyOf(refusals == null ? List.of() : refusals);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        static RetargetPreview refused(BuildingOperation operation, String reason) {
            return new RetargetPreview(operation, null, null, null, 0, null, null,
                    new RestoreSource(RestoreSource.Kind.NONE, List.of(), "", false),
                    List.of(reason), List.of(), -1L, "");
        }

        public boolean applicable() {
            return refusals.isEmpty() && site != null && current != null && target != null;
        }

        public String firstRefusal() {
            return refusals.isEmpty() ? "" : refusals.get(0);
        }

        /** Vrai si l'ancienne et la nouvelle emprise se recouvrent — le cas d'une rotation. */
        public boolean overlapping() {
            return currentFootprint != null && targetFootprint != null
                    && currentFootprint.overlaps(targetFootprint);
        }

        /** L'emprise réellement sauvegardée avant mutation : l'union des deux. */
        public BuildingFootprint compensationFootprint() {
            if (currentFootprint == null) {
                return targetFootprint;
            }
            return targetFootprint == null ? currentFootprint
                    : currentFootprint.union(targetFootprint);
        }
    }

    public record RetargetResult(boolean applied, BuildingPlacement placement, String error,
                                 boolean compensated) {

        static RetargetResult refused(String error) {
            return new RetargetResult(false, null, error, false);
        }

        /**
         * Échec du collage, <strong>mais</strong> l'ancien état a été remis.
         *
         * <p>C'est le cas le plus important à distinguer : le monde est cohérent, l'opération n'a
         * simplement pas eu lieu.</p>
         */
        static RetargetResult compensated(String error) {
            return new RetargetResult(false, null, error, true);
        }
    }
}
