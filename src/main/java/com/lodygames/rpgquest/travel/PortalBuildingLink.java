package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.util.Optional;

/**
 * Liaison portail ↔ construction posée (issue #568, #228-P5) : fonctions pures, sans Bukkit.
 *
 * <p>Le lien est porté par le portail ({@code site} + {@code building}) ; la pose reste celle de
 * #234 ({@code building_placements}), jamais écrite ici. La zone d'activation d'un portail lié est
 * la <strong>zone de passage</strong> de l'arche, transformée par l'ancre et la rotation réellement
 * appliquées à la pose ({@link com.lodygames.rpgquest.building.model.PortalPassage#inWorld}).</p>
 */
public final class PortalBuildingLink {

    private PortalBuildingLink() {
    }

    /**
     * La construction posée sur un emplacement, relevée sur le thread principal.
     *
     * @param definition la définition actuelle de la bibliothèque, {@code null} si elle a été retirée
     */
    public record SiteBuilding(BuildingPlacement placement, BuildingDefinition definition) {
    }

    /** État du lien d'un portail, du plus sain au plus grave. */
    public enum Status {
        /** Aucune construction liée : zone saisie (dimensions ou coins). */
        NONE,
        /** L'arche liée est posée et la zone du portail est exactement son passage. */
        OK,
        /** Plus rien n'est posé sur l'emplacement (pose libérée, rollback). */
        BUILDING_MISSING,
        /** Une autre construction a été posée à la place de l'arche liée. */
        BUILDING_CHANGED,
        /** La définition de l'arche n'est plus dans la bibliothèque. */
        DEFINITION_MISSING,
        /** L'arche ne déclare plus de passage (type ou zone retirés). */
        NO_PASSAGE,
        /** L'arche a été remplacée ou réorientée : son passage n'est plus la zone du portail. */
        ZONE_MISMATCH
    }

    /** Zone déduite, ou la raison du refus. */
    public record Derived(Zone zone, String code, String message) {

        static Derived refused(String code, String message) {
            return new Derived(null, code, message);
        }

        public boolean ok() {
            return zone != null;
        }
    }

    /** La zone d'activation que donnerait la construction posée, ou pourquoi c'est impossible. */
    public static Derived derive(SiteBuilding building) {
        BuildingPlacement placement = building.placement();
        BuildingDefinition definition = building.definition();
        if (definition == null) {
            return Derived.refused("DEFINITION_MISSING", "La construction posée (« " + placement.buildingId()
                    + " ») n'est plus dans la bibliothèque : sa zone de passage est inconnue.");
        }
        if (!definition.portal()) {
            return Derived.refused("NOT_A_PORTAL", "« " + definition.name() + " » n'est pas une arche de portail "
                    + "(type « " + definition.kind().label() + " ») : elle n'a pas de zone de passage.");
        }
        if (definition.passage() == null) {
            return Derived.refused("NO_PASSAGE", "« " + definition.name() + " » est une arche sans zone de passage "
                    + "déclarée : la régénérer depuis l'Atelier, ou garder une zone saisie.");
        }
        BuildingFootprint f = definition.passage().inWorld(definition, placement);
        Zone zone = new Zone(f.minX(), f.minY(), f.minZ(), f.maxX(), f.maxY(), f.maxZ());
        String tooBig = PortalZone.checkExplicit(zone);
        if (tooBig != null) {
            return Derived.refused("INVALID_SIZE", "Passage de l'arche trop grand pour un portail. " + tooBig);
        }
        return new Derived(zone, "", "");
    }

    /** L'état du lien d'un portail, d'après ce qui est posé sur son emplacement. */
    public static Status status(WorldPortalDefinition portal, Optional<SiteBuilding> onSite) {
        WorldPortalDefinition.Details d = portal.details();
        if (!d.linked()) {
            return Status.NONE;
        }
        if (onSite.isEmpty()) {
            return Status.BUILDING_MISSING;
        }
        SiteBuilding sb = onSite.get();
        if (!sb.placement().buildingId().equals(d.building())) {
            return Status.BUILDING_CHANGED;
        }
        if (!sb.placement().world().equals(portal.world())) {
            return Status.ZONE_MISMATCH;
        }
        Derived derived = derive(sb);
        if (!derived.ok()) {
            return "DEFINITION_MISSING".equals(derived.code()) ? Status.DEFINITION_MISSING : Status.NO_PASSAGE;
        }
        return derived.zone().equals(portal.zone()) ? Status.OK : Status.ZONE_MISMATCH;
    }

    /** Phrase lisible d'un état anormal (vérification, page Portails). */
    public static String describe(Status status, WorldPortalDefinition portal) {
        String b = portal.details().building();
        String site = portal.details().site();
        return switch (status) {
            case NONE, OK -> "";
            case BUILDING_MISSING -> "L'arche liée « " + b + " » n'est plus posée sur l'emplacement « " + site
                    + " » : la zone reste celle de l'ancien passage. Reposer l'arche puis « Lier », ou « Délier ».";
            case BUILDING_CHANGED -> "Une autre construction que « " + b + " » est posée sur « " + site
                    + " » : relier pour reprendre son passage, ou délier.";
            case DEFINITION_MISSING -> "L'arche « " + b + " » n'est plus dans la bibliothèque.";
            case NO_PASSAGE -> "L'arche « " + b + " » ne déclare plus de zone de passage.";
            case ZONE_MISMATCH -> "La zone du portail ne correspond plus au passage de l'arche posée "
                    + "(remplacée ou réorientée) : « Lier » à nouveau pour la relire.";
        };
    }
}
