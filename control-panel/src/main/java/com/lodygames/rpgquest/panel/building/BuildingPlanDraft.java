package com.lodygames.rpgquest.panel.building;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un plan de bâtiment proposé par l'IA, conservé comme <strong>brouillon de travail</strong>
 * (issue #256, suite de #94).
 *
 * <p>Avant ce ticket, le plan n'existait que dans le HTML de la réponse : un clic sur « Auditer »,
 * un retour arrière ou une reconnexion le faisaient disparaître, alors qu'il avait été payé. Le
 * brouillon vit dans la base du panel ; il ne touche ni à la bibliothèque de bâtiments, ni au monde,
 * ni au contenu. Seule la génération, geste distinct, écrit quelque chose sur le serveur.</p>
 *
 * @param id             identifiant du brouillon (UUID)
 * @param agentId        cible à laquelle les audits et générations seront envoyés
 * @param siteId         emplacement auquel le plan est rattaché
 * @param plan           texte du plan à la version courante ; peut être vide si le modèle n'a rien
 *                       rendu d'exploitable (la réponse brute reste alors consultable)
 * @param planSha        empreinte SHA-256 du plan courant
 * @param version        version courante, à partir de 1 ; chaque modification l'incrémente
 * @param origin         d'où vient le brouillon
 * @param parentId       brouillon corrigé, pour une correction ; vide sinon
 * @param sourceActionId action d'où le plan a été récupéré, pour une récupération ; vide sinon
 * @param maxWidth       emprise demandée à l'IA, reprise pour une correction
 * @param maxHeight      hauteur demandée à l'IA, reprise pour une correction
 */
public record BuildingPlanDraft(String id, String agentId, String siteId, String plan,
                                String planSha, int version, Origin origin, String parentId,
                                String sourceActionId, String providerId, String providerLabel,
                                String model, String usage, String rawResponse,
                                String extractionNote, int maxWidth, int maxHeight,
                                boolean archived, String createdBy, Instant createdAt,
                                String updatedBy, Instant updatedAt) {

    /** D'où vient un brouillon : c'est ce qui dit s'il a coûté un appel. */
    public enum Origin {
        /** Première proposition du modèle — un appel facturé. */
        AI_PROPOSAL("Proposé par l'IA"),
        /** Correction demandée au modèle à partir d'un autre brouillon — un appel facturé. */
        AI_CORRECTION("Corrigé par l'IA"),
        /** Repris depuis une action existante, sans aucun appel au modèle. */
        RECOVERED("Récupéré depuis une action");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Origin parse(String raw) {
            for (Origin origin : values()) {
                if (origin.name().equals(raw)) {
                    return origin;
                }
            }
            return AI_PROPOSAL;
        }
    }

    private static final Pattern BUILDING_ID =
            Pattern.compile("(?m)^id:\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*$");

    public BuildingPlanDraft {
        plan = plan == null ? "" : plan;
        planSha = planSha == null ? "" : planSha;
        parentId = parentId == null ? "" : parentId;
        sourceActionId = sourceActionId == null ? "" : sourceActionId;
        providerId = providerId == null ? "" : providerId;
        providerLabel = providerLabel == null ? "" : providerLabel;
        model = model == null ? "" : model;
        usage = usage == null ? "" : usage;
        rawResponse = rawResponse == null ? "" : rawResponse;
        extractionNote = extractionNote == null ? "" : extractionNote;
        createdBy = createdBy == null ? "" : createdBy;
        updatedBy = updatedBy == null ? "" : updatedBy;
    }

    public boolean hasPlan() {
        return !plan.isBlank();
    }

    /**
     * L'identifiant de bâtiment déclaré en tête du plan, pour l'affichage seulement.
     *
     * <p>Le panel ne valide pas le plan (le plugin est le validateur autoritaire) : cette lecture
     * sert à nommer le brouillon dans une liste, jamais à décider quoi que ce soit.</p>
     */
    public String buildingId() {
        return buildingIdOf(plan);
    }

    /** Même lecture, sur un texte de plan quelconque (une action à récupérer, par exemple). */
    public static String buildingIdOf(String plan) {
        Matcher m = BUILDING_ID.matcher(plan == null ? "" : plan);
        return m.find() ? m.group(1) : "";
    }
}
