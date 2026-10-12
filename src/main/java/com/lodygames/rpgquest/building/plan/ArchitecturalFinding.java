package com.lodygames.rpgquest.building.plan;

/**
 * Un constat d'audit architectural (issue #94, lot A).
 *
 * <h2>Pourquoi un objet et pas une chaîne</h2>
 *
 * <p>Ces constats ont deux lecteurs, et ils n'ont pas les mêmes besoins. L'administrateur lit
 * {@link #message()} : une phrase française qui dit ce qui manque et où. Le <strong>modèle d'IA</strong>
 * lit la même phrase dans la boucle de correction, et c'est pour lui que la précision compte : « il
 * manque une entrée » ne lui apprend rien, « aucune ouverture de 2 blocs de haut dans la façade
 * OUEST (x=0), 133 cellules de mur toutes pleines » lui dit exactement quoi corriger.</p>
 *
 * <p>Le {@link #code()} existe pour que l'écran et les tests puissent raisonner sans analyser du
 * texte, et {@link #blocking()} pour que la différence entre « je refuse » et « je signale » soit
 * portée par la donnée et non par la couleur d'un message.</p>
 *
 * @param code     identifiant stable, exploitable par l'écran et les tests
 * @param blocking vrai si ce constat doit empêcher la génération
 * @param message  phrase française, précise, destinée à l'administrateur ET à la correction par l'IA
 */
public record ArchitecturalFinding(String code, boolean blocking, String message) {

    public ArchitecturalFinding {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }

    public static ArchitecturalFinding blocking(String code, String message) {
        return new ArchitecturalFinding(code, true, message);
    }

    public static ArchitecturalFinding warning(String code, String message) {
        return new ArchitecturalFinding(code, false, message);
    }
}
