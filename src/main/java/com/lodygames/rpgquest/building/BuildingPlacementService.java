package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.SiteStatus;
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

    private final Map<String, BuildingPlacement> placements = new ConcurrentHashMap<>();

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
        this(repository, sites, library, gateway, worldProbe, Clock.systemUTC());
    }

    public BuildingPlacementService(BuildingPlacementRepository repository,
                                    BuildingSiteService sites,
                                    BuildingLibrary library,
                                    SchematicGateway gateway,
                                    WorldProbe worldProbe,
                                    Clock clock) {
        this.repository = repository;
        this.sites = sites;
        this.library = library;
        this.gateway = gateway;
        this.worldProbe = worldProbe;
        this.clock = clock;
    }

    /** Charge les placements existants au démarrage. La base est la source de vérité. */
    public CompletableFuture<Integer> load() {
        return repository.loadAll().thenApply(loaded -> {
            placements.clear();
            for (BuildingPlacement placement : loaded) {
                placements.put(placement.siteId(), placement);
            }
            return placements.size();
        });
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

        SchematicGateway.Outcome pasted = gateway.paste(new SchematicGateway.PasteOrder(
                building.schematic(), site.world(),
                site.x(), site.y(), site.z(),
                building.anchorX(), building.anchorY(), building.anchorZ(),
                preview.rotationDegrees(), footprint));
        if (!pasted.ok()) {
            inFlight.remove(siteId);
            return CompletableFuture.completedFuture(PlaceResult.refused(pasted.error()));
        }

        BuildingPlacement placement = BuildingPlacement.of(siteId, building, footprint,
                site.x(), site.y(), site.z(), preview.rotationDegrees(), backupName, placedBy, now);

        return repository.insert(placement)
                .thenCompose(ignored -> sites.markStatus(siteId, SiteStatus.OCCUPIED))
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
}
