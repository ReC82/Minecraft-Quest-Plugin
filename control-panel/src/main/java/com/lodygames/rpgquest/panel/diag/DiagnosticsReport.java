package com.lodygames.rpgquest.panel.diag;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Résultat agrégé d'une passe de diagnostics (issue #38). Les entrées sont déjà <strong>triées</strong>
 * (ERROR → WARNING → INFO, puis domaine, ressource, code) et dédoublonnées.
 *
 * @param entries        diagnostics triés
 * @param errors         nombre d'erreurs
 * @param warnings       nombre d'avertissements
 * @param infos          nombre d'informations
 * @param domainsPresent domaines réellement représentés (pour les filtres)
 * @param anyDataLoaded  au moins un relevé source a été chargé (sinon la page invite à rafraîchir)
 * @param oldestSnapshot horodatage du relevé le plus ancien parmi ceux qui ont produit une entrée
 *                       ({@code null} si inconnu)
 */
public record DiagnosticsReport(
        List<DiagnosticEntry> entries,
        int errors,
        int warnings,
        int infos,
        Set<Domain> domainsPresent,
        boolean anyDataLoaded,
        Instant oldestSnapshot) {

    public int total() {
        return errors + warnings + infos;
    }

    public boolean isClean() {
        return errors == 0 && warnings == 0;
    }
}
