package com.lodygames.rpgquest.panel.diag;

import java.util.List;

/**
 * Producteur de diagnostics pour <strong>un</strong> domaine (issue #38). Ajouter une nouvelle
 * source d'anomalies = ajouter une implémentation et l'enregistrer dans {@code DiagnosticsService}
 * — pas de gros {@code switch} unique.
 */
public interface DiagnosticProvider {

    /** Domaine principal alimenté par ce producteur (pour la liste des filtres réellement supportés). */
    Domain domain();

    /** Libellé court du producteur (journalisation / rapport). */
    default String label() {
        return domain().label();
    }

    /**
     * Collecte les diagnostics à partir du snapshot courant. <strong>Jamais</strong> de requête
     * réseau ici : uniquement {@link DiagnosticContext}. Retourne une liste vide si la donnée
     * source n'est pas encore chargée.
     */
    List<DiagnosticEntry> collect(DiagnosticContext ctx);
}
