package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce qu'on montre <strong>avant</strong> d'importer dans un projet le plan d'une action qui ne lui
 * appartient pas (issue #308) : d'où il vient, ce qu'il décrit, et en quoi il dépasse les limites
 * du projet de destination.
 *
 * <p>L'import est toujours une copie : une nouvelle version du projet de destination. La source —
 * action, brouillon, autre projet — n'est ni déplacée, ni modifiée ; ni brief ni conception ne sont
 * repris.</p>
 *
 * @param sourceDraftId   brouillon qui porte déjà cette action, vide si elle n'appartient à aucun
 * @param sourceProjectId projet de ce brouillon, vide s'il n'en a pas (brouillon d'emplacement ancien)
 * @param sourceProject   nom de ce projet, vide s'il n'existe plus ou s'il n'y en a pas
 * @param ownedHere       l'action appartient déjà à une version du projet de destination
 * @param importedDraftId version du projet de destination déjà importée depuis cette action, vide sinon
 * @param conflicts       incompatibilités avec les limites du projet de destination, en français
 */
public record PlanImportPreview(String actionId, String actionType, Instant createdAt, String buildingId,
                                String name, String front, int sizeX, int sizeY, int sizeZ,
                                String sourceDraftId, String sourceProjectId, String sourceProject,
                                boolean ownedHere, String importedDraftId, List<String> conflicts) {

    private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*[\"']?(.*?)[\"']?\\s*$");
    private static final Pattern FRONT = Pattern.compile("(?m)^front:\\s*[\"']?([A-Za-z]+)[\"']?\\s*$");
    private static final Pattern SIZE = Pattern.compile("(?m)^size:\\s*\\n((?:[ \\t]+.*\\n?)+)");

    /** Ce qu'un plan dit de lui-même : identifiant, nom, entrée, dimensions (0 si illisibles). */
    public record Summary(String buildingId, String name, String front, int sizeX, int sizeY, int sizeZ) {
    }

    public PlanImportPreview {
        conflicts = List.copyOf(conflicts);
    }

    /** Lecture tolérante des en-têtes du plan — l'affichage seulement ; le plugin reste juge. */
    public static Summary summaryOf(String plan) {
        int[] size = sizeOf(plan);
        return new Summary(BuildingPlanDraft.buildingIdOf(plan), group(NAME, plan),
                group(FRONT, plan).toUpperCase(Locale.ROOT), size[0], size[1], size[2]);
    }

    /**
     * @param owner         brouillon qui contient déjà cette action (rattachement ou récupération)
     * @param ownerProject  le projet de ce brouillon, s'il existe encore
     * @param importedHere  version du projet de destination déjà importée depuis cette action
     */
    public static PlanImportPreview of(AgentActionRow row, String planText, BuildingProject target,
                                       Optional<BuildingPlanDraft> owner, Optional<BuildingProject> ownerProject,
                                       Optional<BuildingPlanDraft> importedHere) {
        Summary plan = summaryOf(planText);
        int[] size = {plan.sizeX(), plan.sizeY(), plan.sizeZ()};
        String front = plan.front();
        String buildingId = plan.buildingId();
        List<String> conflicts = new ArrayList<>();
        if (target.maxWidth() > 0 && size[0] > target.maxWidth()) {
            conflicts.add("Largeur (X) " + size[0] + " : dépasse la largeur maximale du projet ("
                    + target.maxWidth() + ").");
        }
        if (target.maxDepth() > 0 && size[2] > target.maxDepth()) {
            conflicts.add("Longueur (Z) " + size[2] + " : dépasse la longueur maximale du projet ("
                    + target.maxDepth() + ").");
        }
        if (target.maxHeight() > 0 && size[1] > target.maxHeight()) {
            conflicts.add("Hauteur " + size[1] + " : dépasse la hauteur maximale du projet ("
                    + target.maxHeight() + ").");
        }
        if (size[0] == 0 || size[1] == 0 || size[2] == 0) {
            conflicts.add("Dimensions illisibles dans le plan : la vérification les établira.");
        }
        if (!front.isEmpty() && !front.equals(target.front())) {
            conflicts.add("Entrée orientée " + front + " ; le projet attend " + target.front() + ".");
        }
        if (!buildingId.isEmpty() && !buildingId.equals(target.buildingId())) {
            conflicts.add("Identifiant « " + buildingId + " » : le projet enregistre sous « "
                    + target.buildingId() + " » (alignement proposé avant l'enregistrement).");
        }
        String sourceProjectId = owner.map(BuildingPlanDraft::projectId).orElse("");
        return new PlanImportPreview(row.id(), row.type(), row.createdAt(), buildingId, plan.name(), front,
                size[0], size[1], size[2], owner.map(BuildingPlanDraft::id).orElse(""), sourceProjectId,
                ownerProject.map(BuildingProject::name).orElse(""),
                !sourceProjectId.isEmpty() && sourceProjectId.equals(target.id()),
                importedHere.map(BuildingPlanDraft::id).orElse(""), conflicts);
    }

    /** D'où vient ce plan, en une phrase. */
    public String provenance() {
        if (sourceDraftId.isEmpty()) {
            return "Action de l'historique global, rattachée à aucun projet.";
        }
        if (ownedHere) {
            return "Version déjà présente dans ce projet.";
        }
        if (sourceProjectId.isEmpty()) {
            return "Ancien brouillon d'emplacement, sans projet (" + sourceDraftId + ").";
        }
        return "Projet « " + (sourceProject.isEmpty() ? sourceProjectId : sourceProject) + " » ("
                + sourceProjectId + ").";
    }

    /** Les dimensions x × y × z, ou « inconnues ». */
    public String dimensions() {
        return sizeX == 0 && sizeY == 0 && sizeZ == 0 ? "inconnues"
                : sizeX + " × " + sizeZ + " × " + sizeY + " (largeur X × longueur Z × hauteur Y)";
    }

    static int[] sizeOf(String plan) {
        int[] out = new int[3];
        Matcher m = SIZE.matcher(plan == null ? "" : plan);
        if (!m.find()) {
            return out;
        }
        String block = m.group(1);
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            Matcher axis = Pattern.compile("(?m)^[ \\t]+" + axes[i] + ":\\s*(\\d{1,4})\\s*$").matcher(block);
            out[i] = axis.find() ? Integer.parseInt(axis.group(1)) : 0;
        }
        return out;
    }

    private static String group(Pattern p, String plan) {
        Matcher m = p.matcher(plan == null ? "" : plan);
        return m.find() ? m.group(1).trim() : "";
    }
}
